use std::{
    collections::HashMap,
    io::Write,
    sync::Mutex,
    time::{Duration, Instant},
};

use byteorder::{BigEndian, ByteOrder, WriteBytesExt};
use bytes::Bytes;
use thiserror::Error;
use tokio::sync::{mpsc::UnboundedSender, oneshot};

use crate::{Error, FileId, SpotifyId, packet::PacketType, util::SeqGenerator};

#[derive(Debug, Hash, PartialEq, Eq, Copy, Clone)]
pub struct AudioKey(pub [u8; 16]);

#[derive(Debug, Error)]
pub enum AudioKeyError {
    #[error("audio key error")]
    AesKey,
    #[error("other end of channel disconnected")]
    Channel,
    #[error("unexpected packet type {0}")]
    Packet(u8),
    #[error("sequence {0} not pending")]
    Sequence(u32),
    #[error("audio key response timeout")]
    Timeout,
}

impl From<AudioKeyError> for Error {
    fn from(err: AudioKeyError) -> Self {
        match err {
            AudioKeyError::AesKey => Error::unavailable(err),
            AudioKeyError::Channel => Error::aborted(err),
            AudioKeyError::Sequence(_) => Error::aborted(err),
            AudioKeyError::Packet(_) => Error::unimplemented(err),
            AudioKeyError::Timeout => Error::aborted(err),
        }
    }
}

// wholeflat patch ------------------------------------------------------------------------------
//
// Spotify refuses the audio keys of some accounts for a while (librespot issue #1649). A player that
// answers a refusal by skipping to the next track, or by asking again at once, makes it worse: the
// requests keep coming. So, after a failed key request, requests are spaced out ([`KeyGate`]) until
// one succeeds, and every outcome is reported to the app ([`set_observer`]), which holds the track
// and tries it again with a back-off.

/// After a failed key request, at most one request is started per this much time.
pub const MIN_GAP_AFTER_ERROR: Duration = Duration::from_secs(3);

/// How a key request ended.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum KeyOutcome {
    Granted,
    /// Spotify answered with an error ("error audio key 0 1"): the key is refused.
    Refused,
    /// No answer in time (or the connection went away).
    TimedOut,
}

/// The spacing of key requests after an error. Plain state, no clock of its own: the callers pass
/// the time in, which is what the tests do.
#[derive(Debug, Default)]
pub struct KeyGate {
    errors_in_row: u32,
    next_start: Option<Instant>,
}

impl KeyGate {
    pub const fn new() -> Self {
        KeyGate {
            errors_in_row: 0,
            next_start: None,
        }
    }

    /// A request wants to start at `now`. Returns how long it has to wait first: nothing while the
    /// last request succeeded, else until `MIN_GAP_AFTER_ERROR` after the previous start (requests
    /// that queue up are lined up one gap apart).
    pub fn reserve(&mut self, now: Instant) -> Duration {
        if self.errors_in_row == 0 {
            return Duration::ZERO;
        }
        let start = match self.next_start {
            Some(t) if t > now => t,
            _ => now,
        };
        self.next_start = Some(start + MIN_GAP_AFTER_ERROR);
        start - now
    }

    /// A request ended at `now`.
    pub fn record(&mut self, outcome: KeyOutcome, now: Instant) {
        match outcome {
            KeyOutcome::Granted => {
                self.errors_in_row = 0;
                self.next_start = None;
            }
            KeyOutcome::Refused | KeyOutcome::TimedOut => {
                self.errors_in_row += 1;
                let earliest = now + MIN_GAP_AFTER_ERROR;
                self.next_start = Some(match self.next_start {
                    Some(t) if t > earliest => t,
                    _ => earliest,
                });
            }
        }
    }

    pub fn errors_in_row(&self) -> u32 {
        self.errors_in_row
    }
}

static GATE: Mutex<KeyGate> = Mutex::new(KeyGate::new());
static OBSERVER: Mutex<Option<UnboundedSender<KeyOutcome>>> = Mutex::new(None);

/// Reports the outcome of every key request to `tx` (the app's engine). `None` stops it.
pub fn set_observer(tx: Option<UnboundedSender<KeyOutcome>>) {
    *OBSERVER.lock().unwrap_or_else(|p| p.into_inner()) = tx;
}

fn observe(outcome: KeyOutcome) {
    if let Some(tx) = OBSERVER.lock().unwrap_or_else(|p| p.into_inner()).as_ref() {
        let _ = tx.send(outcome);
    }
}

component! {
    AudioKeyManager : AudioKeyManagerInner {
        sequence: SeqGenerator<u32> = SeqGenerator::new(0),
        pending: HashMap<u32, oneshot::Sender<Result<AudioKey, Error>>> = HashMap::new(),
    }
}

impl AudioKeyManager {
    pub(crate) fn dispatch(&self, cmd: PacketType, mut data: Bytes) -> Result<(), Error> {
        let seq = BigEndian::read_u32(data.split_to(4).as_ref());

        let sender = self
            .lock(|inner| inner.pending.remove(&seq))
            .ok_or(AudioKeyError::Sequence(seq))?;

        match cmd {
            PacketType::AesKey => {
                let mut key = [0u8; 16];
                key.copy_from_slice(data.as_ref());
                sender
                    .send(Ok(AudioKey(key)))
                    .map_err(|_| AudioKeyError::Channel)?
            }
            PacketType::AesKeyError => {
                error!(
                    "error audio key {:x} {:x}",
                    data.as_ref()[0],
                    data.as_ref()[1]
                );
                sender
                    .send(Err(AudioKeyError::AesKey.into()))
                    .map_err(|_| AudioKeyError::Channel)?
            }
            _ => {
                trace!("Did not expect {cmd:?} AES key packet with data {data:#?}");
                return Err(AudioKeyError::Packet(cmd as u8).into());
            }
        }

        Ok(())
    }

    pub async fn request(&self, track: SpotifyId, file: FileId) -> Result<AudioKey, Error> {
        // wholeflat: after an error the requests go out one gap apart
        let wait = GATE
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .reserve(Instant::now());
        if !wait.is_zero() {
            warn!(
                "holding back an audio key request for {} ms after an error",
                wait.as_millis()
            );
            tokio::time::sleep(wait).await;
        }

        let result = self.request_once(track, file).await;

        let outcome = match &result {
            Ok(_) => KeyOutcome::Granted,
            Err(e) => match e.error.downcast_ref::<AudioKeyError>() {
                Some(AudioKeyError::AesKey) => KeyOutcome::Refused,
                _ => KeyOutcome::TimedOut,
            },
        };
        GATE.lock()
            .unwrap_or_else(|p| p.into_inner())
            .record(outcome, Instant::now());
        observe(outcome);
        result
    }

    async fn request_once(&self, track: SpotifyId, file: FileId) -> Result<AudioKey, Error> {
        let (tx, rx) = oneshot::channel();

        let seq = self.lock(move |inner| {
            let seq = inner.sequence.get();
            inner.pending.insert(seq, tx);
            seq
        });

        self.send_key_request(seq, track, file)?;
        const KEY_RESPONSE_TIMEOUT: Duration = Duration::from_millis(1500);
        match tokio::time::timeout(KEY_RESPONSE_TIMEOUT, rx).await {
            Err(_) => {
                error!("Audio key response timeout");
                Err(AudioKeyError::Timeout.into())
            }
            Ok(k) => k?,
        }
    }

    fn send_key_request(&self, seq: u32, track: SpotifyId, file: FileId) -> Result<(), Error> {
        let mut data: Vec<u8> = Vec::new();
        data.write_all(&file.0)?;
        data.write_all(&track.to_raw())?;
        data.write_u32::<BigEndian>(seq)?;
        data.write_u16::<BigEndian>(0x0000)?;

        self.session().send_packet(PacketType::RequestKey, data)
    }
}
