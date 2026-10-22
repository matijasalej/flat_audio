//! PCM hand-over between librespot's player thread and the Kotlin `AudioTrack` thread.
//!
//! The player thread decodes faster than real time. Its `Sink` pushes interleaved stereo i16 into
//! this bounded ring (about one second) and blocks when it is full, so decoding is paced by whoever
//! drains the ring: `NativeBridge.readPcm`, called in a loop by the thread that writes into a
//! blocking `AudioTrack`. One producer, one consumer; a mutex plus two condvars is plenty for
//! chunks of a few thousand samples.
//!
//! Everything is counted in whole stereo frames (two samples), so the consumer can never see a
//! left sample without its right neighbour.

use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

use librespot_playback::audio_backend::{Sink, SinkError, SinkResult};
use librespot_playback::convert::Converter;
use librespot_playback::decoder::AudioPacket;
use serde_json::json;

use crate::events::{lock, EventQueue};

pub const SAMPLE_RATE: usize = 44_100;
pub const CHANNELS: usize = 2;
/// About one second of interleaved stereo.
pub const CAPACITY_SAMPLES: usize = SAMPLE_RATE * CHANNELS;

pub enum ReadResult {
    /// This many samples were copied into the caller's buffer (always even, never zero).
    Data(usize),
    Timeout,
    /// The ring was closed; the engine is shutting down.
    Closed,
}

struct State {
    buf: Vec<i16>,
    head: usize,
    len: usize,
    closed: bool,
}

pub struct Ring {
    state: Mutex<State>,
    not_full: Condvar,
    not_empty: Condvar,
}

impl Ring {
    pub fn new(capacity_samples: usize) -> Self {
        let cap = (capacity_samples & !1).max(2);
        Ring {
            state: Mutex::new(State {
                buf: vec![0; cap],
                head: 0,
                len: 0,
                closed: false,
            }),
            not_full: Condvar::new(),
            not_empty: Condvar::new(),
        }
    }

    /// Pushes all of `data`, blocking while the ring is full. Returns `false` if the ring was closed
    /// before everything fit (the rest is dropped).
    pub fn write_blocking(&self, data: &[i16]) -> bool {
        let mut data = &data[..data.len() & !1];
        let mut g = lock(&self.state);
        while !data.is_empty() {
            if g.closed {
                return false;
            }
            let cap = g.buf.len();
            let free = cap - g.len;
            if free < 2 {
                // Full: wait for the consumer, but wake regularly to notice a close().
                let (ng, _timed_out) = self
                    .not_full
                    .wait_timeout(g, Duration::from_millis(100))
                    .unwrap_or_else(|poisoned| poisoned.into_inner());
                g = ng;
                continue;
            }
            let n = free.min(data.len()) & !1;
            let tail = (g.head + g.len) % cap;
            let first = n.min(cap - tail);
            g.buf[tail..tail + first].copy_from_slice(&data[..first]);
            if n > first {
                g.buf[..n - first].copy_from_slice(&data[first..n]);
            }
            g.len += n;
            data = &data[n..];
            self.not_empty.notify_one();
        }
        true
    }

    /// Copies up to `out.len()` samples (rounded down to whole frames), waiting up to `timeout` for
    /// the first ones to arrive.
    pub fn read_timeout(&self, out: &mut [i16], timeout: Duration) -> ReadResult {
        let max = out.len() & !1;
        if max == 0 {
            return ReadResult::Timeout;
        }
        let deadline = Instant::now() + timeout;
        let mut g = lock(&self.state);
        loop {
            if g.closed {
                return ReadResult::Closed;
            }
            if g.len >= 2 {
                let cap = g.buf.len();
                let n = g.len.min(max) & !1;
                let head = g.head;
                let first = n.min(cap - head);
                out[..first].copy_from_slice(&g.buf[head..head + first]);
                if n > first {
                    out[first..n].copy_from_slice(&g.buf[..n - first]);
                }
                g.head = (head + n) % cap;
                g.len -= n;
                drop(g);
                self.not_full.notify_one();
                return ReadResult::Data(n);
            }
            let now = Instant::now();
            if now >= deadline {
                return ReadResult::Timeout;
            }
            let (ng, _timed_out) = self
                .not_empty
                .wait_timeout(g, deadline - now)
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            g = ng;
        }
    }

    /// Wakes a blocked producer and consumer for good: the producer's remaining samples are dropped
    /// and the consumer gets [`ReadResult::Closed`].
    pub fn close(&self) {
        lock(&self.state).closed = true;
        self.not_full.notify_all();
        self.not_empty.notify_all();
    }
}

/// librespot `Sink` that feeds the [`Ring`].
pub struct RingSink {
    ring: Arc<Ring>,
    events: Arc<EventQueue>,
}

impl RingSink {
    pub fn new(ring: Arc<Ring>, events: Arc<EventQueue>) -> Self {
        RingSink { ring, events }
    }
}

impl Sink for RingSink {
    fn start(&mut self) -> SinkResult<()> {
        self.events.push("sink", json!({ "state": "start" }));
        Ok(())
    }

    /// Never returns `Err`: the player answers a failing `stop` with `exit(1)`, which would take
    /// the whole app process down. The samples already queued simply play out.
    fn stop(&mut self) -> SinkResult<()> {
        self.events.push("sink", json!({ "state": "stop" }));
        Ok(())
    }

    fn write(&mut self, packet: AudioPacket, converter: &mut Converter) -> SinkResult<()> {
        let samples = packet
            .samples()
            .map_err(|e| SinkError::OnWrite(e.to_string()))?;
        let pcm: Vec<i16> = converter.f64_to_s16(samples);
        // Blocks while the ring is full; that is what keeps decoding in step with playback. A
        // closed ring means shutdown, the samples are not needed any more.
        let _ = self.ring.write_blocking(&pcm);
        Ok(())
    }
}
