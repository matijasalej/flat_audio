//! The PCM hand-over between librespot's player thread and the two rooms.
//!
//! One writer (the librespot sink, or the calibration source), two kinds of readers:
//!
//! * the **living room** (one reader, the Kotlin `AudioTrack` pull thread) consumes frames and is
//!   the clock master; it never skips,
//! * the **kitchen** (any number of short-lived readers, the HTTP stream of one Sonos session) reads
//!   by absolute frame index, always ahead of the living room, and is told when it was lapped.
//!
//! Frames carry absolute indices (the n-th stereo frame since the engine started). The ring holds the
//! last [`CAPACITY_FRAMES`] of them (3 s), which bounds the decoder's lead over the living room, and
//! with it how far Spotify's progress bar runs ahead. Half a second is kept *behind* the living-room
//! reader so that a pause can rewind to exactly what was heard.
//!
//! ## Epochs
//!
//! A discontinuity of the audio starts a new **epoch** and a **start event**: both rooms are then
//! started together by the Kotlin side, which acknowledges it with [`FrameRing::lr_ack`]. The
//! discontinuities are found from the order of the player's events relative to the sink calls, which
//! is exact because librespot sends an event from the player thread before the writes that follow it:
//!
//! * `Seeked`, or `PlayRequestIdChanged` that is not preceded by `EndOfTrack`: **flush**. Everything
//!   not yet consumed by the living room is dropped and the next written frame opens the epoch.
//! * `stop()` is a **pause**: the living room stops and keeps its unplayed frames, the reader goes
//!   back to what was last heard.
//! * `start()` is a **resume**: a new epoch at the first unplayed frame (or at the next written frame
//!   if a flush is waiting).
//! * `EndOfTrack` followed by `PlayRequestIdChanged` is a gapless transition: nothing happens.
//!
//! The events are drained under the ring's lock, by the writer (top of `write`, `start`, `stop`) and
//! by the living-room reader on every read, so a seek takes effect when it happens and not only when
//! the decoder has produced the next packet. Whoever drains first sees the event before any frame that
//! follows it, so both orders are exact.

use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::time::{Duration, Instant};

use librespot_playback::audio_backend::{Sink, SinkError, SinkResult};
use librespot_playback::convert::Converter;
use librespot_playback::decoder::AudioPacket;
use librespot_playback::player::PlayerEvent;
use serde_json::json;
use tokio::sync::mpsc::UnboundedReceiver;

use crate::events::EventQueue;

pub const RATE: u64 = 44_100;
pub const CHANNELS: usize = 2;
/// Three seconds: the most the decoder can run ahead of the living room (less the history).
pub const CAPACITY_FRAMES: u64 = 3 * RATE;
/// Half a second behind the living-room reader, for the pause rewind.
pub const HISTORY_FRAMES: u64 = RATE / 2;

/// The player events the ring cares about.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SinkEvent {
    Seeked,
    EndOfTrack,
    PlayRequestIdChanged,
    Other,
}

/// Where the ring gets the player's events from (a second receiver of the player's event channel; a
/// queue of fake events in the tests).
pub trait EventSource: Send {
    fn try_next(&mut self) -> Option<SinkEvent>;
}

/// The player's event channel as an [`EventSource`].
pub struct PlayerEvents {
    rx: UnboundedReceiver<PlayerEvent>,
}

impl PlayerEvents {
    pub fn new(rx: UnboundedReceiver<PlayerEvent>) -> Self {
        PlayerEvents { rx }
    }
}

impl EventSource for PlayerEvents {
    fn try_next(&mut self) -> Option<SinkEvent> {
        let ev = self.rx.try_recv().ok()?;
        Some(match ev {
            PlayerEvent::Seeked { .. } => SinkEvent::Seeked,
            PlayerEvent::EndOfTrack { .. } => SinkEvent::EndOfTrack,
            PlayerEvent::PlayRequestIdChanged { .. } => SinkEvent::PlayRequestIdChanged,
            _ => SinkEvent::Other,
        })
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EpochKind {
    /// Nothing has been played yet.
    None = 0,
    /// A seek, a skip or a new play request: the pending frames were dropped.
    Flush = 1,
    /// `start()` after a pause: continues at the first unplayed frame.
    Resume = 2,
    /// The calibration source took over.
    Synthetic = 3,
    /// The living room lost its output (AudioTrack stall) and starts again where it was heard.
    Restart = 4,
}

#[derive(Clone, Copy, Debug)]
pub struct Snapshot {
    pub epoch: u64,
    pub epoch_start: u64,
    pub kind: EpochKind,
    pub start_pending: bool,
    pub sink_running: bool,
    pub synthetic: bool,
    pub closed: bool,
    pub write_idx: u64,
    pub lr_pos: u64,
    pub valid_start: u64,
    pub flushes: u64,
    pub resumes: u64,
    pub pauses: u64,
    pub restarts: u64,
}

impl Snapshot {
    pub fn playing(&self) -> bool {
        self.sink_running || self.synthetic
    }
}

/// (The fields are read by the tests; the Kotlin side gets the same facts from the snapshot.)
#[allow(dead_code)]
pub enum LrRead {
    /// `frames` frames from absolute index `first`, all of epoch `epoch`.
    Data { first: u64, frames: usize, epoch: u64 },
    Timeout,
    /// Nothing is playing (paused, stopped, not started).
    Paused,
    /// A new epoch is waiting for [`FrameRing::lr_ack`]; no frame is handed out before that.
    StartPending { epoch: u64 },
    Closed,
}

#[allow(dead_code)]
pub enum KRead {
    Data { frames: usize },
    Timeout,
    /// The frames at the cursor are gone (the ring moved on); the stream has to be re-joined.
    Lapped { valid_start: u64 },
    /// A new epoch started since the session was set up; the session is obsolete.
    EpochChanged { epoch: u64 },
    Closed,
}

struct Inner {
    buf: Vec<i16>,
    cap: u64,
    history: u64,
    write_idx: u64,
    lr_pos: u64,
    /// The ring frame the living room reported as being heard (a little ahead of now), used by a pause.
    lr_heard: Option<u64>,
    epoch: u64,
    epoch_start: u64,
    kind: EpochKind,
    start_pending: bool,
    sink_running: bool,
    synthetic: bool,
    synth_abort: bool,
    /// The sink keeps writing while the calibration source ends: its next write opens a flush epoch.
    sink_resync: bool,
    flush_requested: bool,
    last_end_of_track: bool,
    closed: bool,
    events: Option<Box<dyn EventSource>>,
    flushes: u64,
    resumes: u64,
    pauses: u64,
    restarts: u64,
}

impl Inner {
    fn playing(&self) -> bool {
        self.sink_running || self.synthetic
    }

    fn valid_start(&self) -> u64 {
        self.write_idx.saturating_sub(self.cap)
    }

    fn snapshot(&self) -> Snapshot {
        Snapshot {
            epoch: self.epoch,
            epoch_start: self.epoch_start,
            kind: self.kind,
            start_pending: self.start_pending,
            sink_running: self.sink_running,
            synthetic: self.synthetic,
            closed: self.closed,
            write_idx: self.write_idx,
            lr_pos: self.lr_pos,
            valid_start: self.valid_start(),
            flushes: self.flushes,
            resumes: self.resumes,
            pauses: self.pauses,
            restarts: self.restarts,
        }
    }

    /// Reads the player's events that have been sent so far. They only *record* what happened; the
    /// flush itself is applied by the caller at the right moment.
    fn drain_events(&mut self) {
        let mut source = match self.events.take() {
            Some(s) => s,
            None => return,
        };
        while let Some(ev) = source.try_next() {
            match ev {
                SinkEvent::Seeked => {
                    self.flush_requested = true;
                    self.last_end_of_track = false;
                }
                SinkEvent::EndOfTrack => self.last_end_of_track = true,
                SinkEvent::PlayRequestIdChanged => {
                    if self.last_end_of_track {
                        // gapless: the next track follows the last one without a break
                        self.last_end_of_track = false;
                    } else {
                        self.flush_requested = true;
                    }
                }
                SinkEvent::Other => {}
            }
        }
        self.events = Some(source);
    }

    /// Opens a new epoch and a start event.
    fn begin_epoch(&mut self, kind: EpochKind) {
        self.epoch += 1;
        self.kind = kind;
        match kind {
            EpochKind::Flush | EpochKind::Synthetic => {
                // everything not yet consumed is dropped: the next written frame is the first one
                self.lr_pos = self.write_idx;
                self.flush_requested = false;
            }
            EpochKind::Resume | EpochKind::Restart | EpochKind::None => {}
        }
        self.epoch_start = self.lr_pos;
        self.start_pending = true;
        self.lr_heard = None;
        match kind {
            EpochKind::Flush => self.flushes += 1,
            EpochKind::Resume => self.resumes += 1,
            EpochKind::Restart => self.restarts += 1,
            _ => {}
        }
    }

    /// Applies a recorded flush if something is playing; a flush while paused waits for `start()`.
    fn apply_flush_if_playing(&mut self) {
        if self.flush_requested && self.playing() && !self.synthetic {
            self.begin_epoch(EpochKind::Flush);
        }
    }

    fn copy_in(&mut self, src: &[i16]) {
        let frames = (src.len() / CHANNELS) as u64;
        let start = (self.write_idx % self.cap) as usize;
        let first = ((self.cap as usize) - start).min(frames as usize);
        self.buf[start * CHANNELS..(start + first) * CHANNELS].copy_from_slice(&src[..first * CHANNELS]);
        let rest = frames as usize - first;
        if rest > 0 {
            self.buf[..rest * CHANNELS].copy_from_slice(&src[first * CHANNELS..(first + rest) * CHANNELS]);
        }
        self.write_idx += frames;
    }

    fn copy_out(&self, idx: u64, out: &mut [i16], frames: usize) {
        let start = (idx % self.cap) as usize;
        let first = ((self.cap as usize) - start).min(frames);
        out[..first * CHANNELS].copy_from_slice(&self.buf[start * CHANNELS..(start + first) * CHANNELS]);
        let rest = frames - first;
        if rest > 0 {
            out[first * CHANNELS..(first + rest) * CHANNELS].copy_from_slice(&self.buf[..rest * CHANNELS]);
        }
    }

    /// How many frames the writer may add now.
    fn room(&self) -> u64 {
        let pending = self.write_idx - self.lr_pos;
        (self.cap - self.history).saturating_sub(pending)
    }
}

pub struct FrameRing {
    inner: Mutex<Inner>,
    cv: Condvar,
}

/// Longest a blocked call sleeps before looking at the state again.
const SLICE: Duration = Duration::from_millis(40);

impl FrameRing {
    pub fn new() -> Self {
        Self::with_capacity(CAPACITY_FRAMES, HISTORY_FRAMES)
    }

    pub fn with_capacity(cap: u64, history: u64) -> Self {
        assert!(history < cap);
        FrameRing {
            inner: Mutex::new(Inner {
                buf: vec![0; cap as usize * CHANNELS],
                cap,
                history,
                write_idx: 0,
                lr_pos: 0,
                lr_heard: None,
                epoch: 0,
                epoch_start: 0,
                kind: EpochKind::None,
                start_pending: false,
                sink_running: false,
                synthetic: false,
                synth_abort: false,
                sink_resync: false,
                flush_requested: false,
                last_end_of_track: false,
                closed: false,
                events: None,
                flushes: 0,
                resumes: 0,
                pauses: 0,
                restarts: 0,
            }),
            cv: Condvar::new(),
        }
    }

    fn lock(&self) -> MutexGuard<'_, Inner> {
        self.inner.lock().unwrap_or_else(|p| p.into_inner())
    }

    fn wait<'a>(&self, g: MutexGuard<'a, Inner>, slice: Duration) -> MutexGuard<'a, Inner> {
        self.cv
            .wait_timeout(g, slice)
            .unwrap_or_else(|p| p.into_inner())
            .0
    }

    pub fn set_event_source(&self, source: Box<dyn EventSource>) {
        self.lock().events = Some(source);
    }

    pub fn close(&self) {
        self.lock().closed = true;
        self.cv.notify_all();
    }

    pub fn snapshot(&self) -> Snapshot {
        let mut g = self.lock();
        g.drain_events();
        g.apply_flush_if_playing();
        self.cv.notify_all();
        g.snapshot()
    }

    // ---- the sink side ---------------------------------------------------------------------------

    /// `Sink::start`: playback begins or resumes.
    pub fn sink_start(&self) {
        let mut g = self.lock();
        g.drain_events();
        g.sink_running = true;
        if g.synthetic {
            // the calibration source is playing: the sink joins when it ends
            g.sink_resync = true;
        } else if g.flush_requested {
            g.begin_epoch(EpochKind::Flush);
        } else {
            g.begin_epoch(EpochKind::Resume);
        }
        g.last_end_of_track = false;
        drop(g);
        self.cv.notify_all();
    }

    /// `Sink::stop`: a pause. The living-room reader goes back to what was last heard.
    pub fn sink_stop(&self) {
        let mut g = self.lock();
        g.drain_events();
        let was_playing = g.playing();
        if g.flush_requested && was_playing && !g.synthetic {
            // a seek, then a pause: what is pending is from before the seek
            g.flush_requested = false;
            g.lr_pos = g.write_idx;
            g.epoch_start = g.lr_pos;
            g.flushes += 1;
        } else if was_playing && !g.synthetic {
            g.rewind_to_heard();
        }
        g.sink_running = false;
        g.sink_resync = false;
        if !g.playing() {
            g.start_pending = false;
            g.lr_heard = None;
            if was_playing {
                g.pauses += 1;
            }
        }
        g.last_end_of_track = false;
        drop(g);
        self.cv.notify_all();
    }

    /// `Sink::write`: appends interleaved stereo frames, blocking while the ring is full.
    /// False if the ring was closed before everything went in.
    pub fn sink_write(&self, samples: &[i16]) -> bool {
        let mut data = &samples[..samples.len() - samples.len() % CHANNELS];
        let mut g = self.lock();
        g.drain_events();
        let mut wrote = false;
        while !data.is_empty() {
            if g.closed {
                return false;
            }
            if g.synthetic {
                // held while the calibration source plays
                g = self.wait(g, SLICE);
                g.drain_events();
                continue;
            }
            if g.sink_resync {
                g.sink_resync = false;
                g.flush_requested = false;
                g.begin_epoch(EpochKind::Flush);
            } else if g.flush_requested {
                g.begin_epoch(EpochKind::Flush);
            }
            let room = g.room();
            if room == 0 {
                self.cv.notify_all();
                g = self.wait(g, SLICE);
                continue;
            }
            let n = room.min((data.len() / CHANNELS) as u64) as usize;
            g.copy_in(&data[..n * CHANNELS]);
            data = &data[n * CHANNELS..];
            wrote = true;
            self.cv.notify_all();
        }
        if wrote {
            g.last_end_of_track = false;
        }
        true
    }

    // ---- the calibration source ------------------------------------------------------------------

    /// The calibration source takes over: a synthetic epoch, pending frames dropped.
    pub fn synth_begin(&self) {
        let mut g = self.lock();
        g.drain_events();
        g.synthetic = true;
        g.synth_abort = false;
        g.begin_epoch(EpochKind::Synthetic);
        drop(g);
        self.cv.notify_all();
    }

    /// Appends calibration frames; blocks like the sink. False if aborted or closed.
    pub fn synth_write(&self, samples: &[i16]) -> bool {
        let mut data = &samples[..samples.len() - samples.len() % CHANNELS];
        let mut g = self.lock();
        while !data.is_empty() {
            if g.closed || g.synth_abort || !g.synthetic {
                return false;
            }
            let room = g.room();
            if room == 0 {
                self.cv.notify_all();
                g = self.wait(g, SLICE);
                continue;
            }
            let n = room.min((data.len() / CHANNELS) as u64) as usize;
            g.copy_in(&data[..n * CHANNELS]);
            data = &data[n * CHANNELS..];
            self.cv.notify_all();
        }
        true
    }

    pub fn write_idx(&self) -> u64 {
        self.lock().write_idx
    }

    /// Waits until the living room has consumed everything up to `target`, or the source is aborted.
    pub fn synth_wait_consumed(&self, target: u64, timeout: Duration) -> bool {
        let deadline = Instant::now() + timeout;
        let mut g = self.lock();
        loop {
            if g.closed || g.synth_abort || !g.synthetic {
                return false;
            }
            if g.lr_pos >= target {
                return true;
            }
            let now = Instant::now();
            if now >= deadline {
                return false;
            }
            g = self.wait(g, SLICE.min(deadline - now));
        }
    }

    pub fn synth_abort(&self) {
        self.lock().synth_abort = true;
        self.cv.notify_all();
    }

    /// The calibration source is done (or was aborted): playback stops, or the sink takes over again.
    pub fn synth_end(&self) {
        let mut g = self.lock();
        if !g.synthetic {
            return;
        }
        g.synthetic = false;
        g.synth_abort = false;
        // what is left of the test signal is of no use to anyone
        g.lr_pos = g.write_idx;
        g.epoch_start = g.lr_pos;
        if g.sink_running {
            // Spotify was playing underneath: its next write opens a flush epoch, both rooms restart
            g.sink_resync = true;
        }
        if !g.playing() {
            g.start_pending = false;
            g.lr_heard = None;
            g.pauses += 1;
        }
        drop(g);
        self.cv.notify_all();
    }

    // ---- the living-room reader ------------------------------------------------------------------

    /// Hands out up to `max_frames` frames to the living room. `heard_hint` is the ring frame the
    /// living room hears about now (a little ahead), so that a pause knows where to rewind to.
    pub fn lr_read(
        &self,
        out: &mut [i16],
        max_frames: usize,
        heard_hint: Option<u64>,
        timeout: Duration,
    ) -> LrRead {
        let max_frames = max_frames.min(out.len() / CHANNELS);
        let deadline = Instant::now() + timeout;
        let mut g = self.lock();
        loop {
            g.drain_events();
            g.apply_flush_if_playing();
            if g.closed {
                return LrRead::Closed;
            }
            if let Some(h) = heard_hint {
                g.lr_heard = Some(h);
            }
            if !g.playing() {
                return LrRead::Paused;
            }
            if g.start_pending {
                return LrRead::StartPending { epoch: g.epoch };
            }
            let available = (g.write_idx - g.lr_pos) as usize;
            let n = available.min(max_frames);
            if n > 0 {
                let first = g.lr_pos;
                g.copy_out(first, out, n);
                g.lr_pos += n as u64;
                let epoch = g.epoch;
                drop(g);
                self.cv.notify_all();
                return LrRead::Data { first, frames: n, epoch };
            }
            let now = Instant::now();
            if now >= deadline {
                return LrRead::Timeout;
            }
            g = self.wait(g, SLICE.min(deadline - now));
        }
    }

    /// The Kotlin side has scheduled the start of `epoch`: frames may be handed out from now on.
    pub fn lr_ack(&self, epoch: u64) {
        let mut g = self.lock();
        if g.epoch == epoch {
            g.start_pending = false;
        }
    }

    /// The living room lost its output: start again where it was last heard (a start event).
    pub fn lr_restart(&self, heard_hint: Option<u64>) {
        let mut g = self.lock();
        g.drain_events();
        if !g.playing() || g.synthetic {
            return;
        }
        if let Some(h) = heard_hint {
            g.lr_heard = Some(h);
        }
        g.rewind_to_heard();
        g.begin_epoch(EpochKind::Restart);
        drop(g);
        self.cv.notify_all();
    }

    // ---- the kitchen reader ----------------------------------------------------------------------

    /// Reads up to `max_frames` frames starting at the absolute index `cursor` (not consumed: the
    /// caller keeps its own cursor). `epoch` is the epoch the session belongs to.
    pub fn k_read(
        &self,
        cursor: u64,
        epoch: u64,
        out: &mut [i16],
        max_frames: usize,
        timeout: Duration,
    ) -> KRead {
        let max_frames = max_frames.min(out.len() / CHANNELS);
        let deadline = Instant::now() + timeout;
        let mut g = self.lock();
        loop {
            if g.closed {
                return KRead::Closed;
            }
            if g.epoch != epoch {
                return KRead::EpochChanged { epoch: g.epoch };
            }
            let valid_start = g.valid_start();
            if cursor < valid_start {
                return KRead::Lapped { valid_start };
            }
            if cursor < g.write_idx {
                let n = ((g.write_idx - cursor) as usize).min(max_frames);
                if n > 0 {
                    g.copy_out(cursor, out, n);
                    return KRead::Data { frames: n };
                }
            }
            let now = Instant::now();
            if now >= deadline {
                return KRead::Timeout;
            }
            g = self.wait(g, SLICE.min(deadline - now));
        }
    }
}

impl Inner {
    /// Moves the living-room reader back to the frame it was heard at, never further than the history
    /// and never before the start of the epoch.
    fn rewind_to_heard(&mut self) {
        if let Some(h) = self.lr_heard {
            let lowest = self.lr_pos.saturating_sub(self.history).max(self.epoch_start);
            self.lr_pos = h.clamp(lowest, self.lr_pos);
        }
        self.lr_heard = None;
    }
}

impl Default for FrameRing {
    fn default() -> Self {
        Self::new()
    }
}

/// librespot `Sink` that feeds the [`FrameRing`].
pub struct RingSink {
    ring: Arc<FrameRing>,
    events: Arc<EventQueue>,
}

impl RingSink {
    pub fn new(ring: Arc<FrameRing>, events: Arc<EventQueue>) -> Self {
        RingSink { ring, events }
    }
}

impl Sink for RingSink {
    fn start(&mut self) -> SinkResult<()> {
        self.ring.sink_start();
        self.events.push("sink", json!({ "state": "start" }));
        Ok(())
    }

    /// Never returns `Err`: the player answers a failing `stop` with `exit(1)`, which would take
    /// the whole app process down.
    fn stop(&mut self) -> SinkResult<()> {
        self.ring.sink_stop();
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
        let _ = self.ring.sink_write(&pcm);
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::VecDeque;
    use std::thread;

    /// Fake player events, pushed by the test.
    #[derive(Clone, Default)]
    struct Fake(Arc<Mutex<VecDeque<SinkEvent>>>);

    impl Fake {
        fn push(&self, ev: SinkEvent) {
            self.0.lock().unwrap().push_back(ev);
        }
    }

    impl EventSource for Fake {
        fn try_next(&mut self) -> Option<SinkEvent> {
            self.0.lock().unwrap().pop_front()
        }
    }

    const MS: Duration = Duration::from_millis(1);

    fn ring_with_events(cap: u64, hist: u64) -> (Arc<FrameRing>, Fake) {
        let ring = Arc::new(FrameRing::with_capacity(cap, hist));
        let fake = Fake::default();
        ring.set_event_source(Box::new(fake.clone()));
        (ring, fake)
    }

    /// `n` frames whose left sample is the absolute index of the frame (starting at `from`).
    fn frames(from: u64, n: u64) -> Vec<i16> {
        let mut v = Vec::new();
        for i in from..from + n {
            v.push(i as i16);
            v.push(-(i as i16));
        }
        v
    }

    fn lr_data(ring: &FrameRing, max: usize, hint: Option<u64>) -> (u64, Vec<i16>) {
        let mut out = vec![0i16; max * 2];
        match ring.lr_read(&mut out, max, hint, 20 * MS) {
            LrRead::Data { first, frames, .. } => {
                out.truncate(frames * 2);
                (first, out)
            }
            LrRead::Timeout => (u64::MAX, Vec::new()),
            LrRead::Paused => (u64::MAX - 1, Vec::new()),
            LrRead::StartPending { .. } => (u64::MAX - 2, Vec::new()),
            LrRead::Closed => (u64::MAX - 3, Vec::new()),
        }
    }

    fn started(ring: &FrameRing) {
        ring.sink_start();
        let s = ring.snapshot();
        ring.lr_ack(s.epoch);
    }

    #[test]
    fn frames_keep_their_absolute_index_across_the_wrap() {
        let ring = FrameRing::with_capacity(100, 10);
        started(&ring);
        let mut next = 0u64;
        for _ in 0..7 {
            assert!(ring.sink_write(&frames(next, 40)));
            next += 40;
            let (first, got) = lr_data(&ring, 40, None);
            assert_eq!(first, next - 40);
            assert_eq!(got, frames(first, 40));
        }
    }

    #[test]
    fn the_writer_waits_when_the_pending_frames_fill_the_ring_less_the_history() {
        let ring = Arc::new(FrameRing::with_capacity(100, 20));
        started(&ring);
        assert!(ring.sink_write(&frames(0, 80))); // exactly cap - history pending
        let writer = {
            let ring = ring.clone();
            thread::spawn(move || ring.sink_write(&frames(80, 30)))
        };
        thread::sleep(60 * MS);
        assert!(!writer.is_finished(), "no room: the writer must wait");
        let (_, got) = lr_data(&ring, 50, None);
        assert_eq!(got.len(), 100);
        assert!(writer.join().unwrap());
        assert_eq!(ring.write_idx(), 110);
    }

    #[test]
    fn start_opens_a_resume_epoch_that_waits_for_the_ack() {
        let ring = FrameRing::with_capacity(1000, 100);
        ring.sink_start();
        let s = ring.snapshot();
        assert_eq!((s.epoch, s.kind, s.start_pending), (1, EpochKind::Resume, true));
        assert!(ring.sink_write(&frames(0, 50)));
        // nothing is handed out before the start was scheduled
        let mut out = vec![0i16; 100];
        assert!(matches!(
            ring.lr_read(&mut out, 50, None, 5 * MS),
            LrRead::StartPending { epoch: 1 }
        ));
        ring.lr_ack(1);
        assert_eq!(lr_data(&ring, 50, None).0, 0);
    }

    #[test]
    fn a_seek_flushes_what_the_living_room_has_not_consumed() {
        let (ring, fake) = ring_with_events(1000, 100);
        started(&ring);
        assert!(ring.sink_write(&frames(0, 300)));
        assert_eq!(lr_data(&ring, 100, None).0, 0); // 100 consumed, 200 pending
        fake.push(SinkEvent::Seeked);
        assert!(ring.sink_write(&frames(300, 50))); // the first write after the event
        let s = ring.snapshot();
        assert_eq!((s.epoch, s.kind, s.start_pending), (2, EpochKind::Flush, true));
        assert_eq!(s.epoch_start, 300, "the new epoch starts at the first frame written after the event");
        assert_eq!(s.lr_pos, 300);
        ring.lr_ack(2);
        let (first, got) = lr_data(&ring, 100, None);
        assert_eq!((first, got.len()), (300, 100));
    }

    #[test]
    fn the_reader_sees_a_seek_at_once_without_waiting_for_the_next_write() {
        let (ring, fake) = ring_with_events(1000, 100);
        started(&ring);
        assert!(ring.sink_write(&frames(0, 300)));
        fake.push(SinkEvent::Seeked);
        // the decoder is still busy after the seek: the reader drains the event itself
        let mut out = vec![0i16; 200];
        assert!(matches!(
            ring.lr_read(&mut out, 100, None, 5 * MS),
            LrRead::StartPending { epoch: 2 }
        ));
        let s = ring.snapshot();
        assert_eq!((s.epoch_start, s.lr_pos), (300, 300));
        // and the frames written afterwards are the new epoch's, whoever drained first
        assert!(ring.sink_write(&frames(300, 10)));
        assert_eq!(ring.snapshot().epoch, 2, "one event, one epoch");
    }

    #[test]
    fn a_new_play_request_flushes_but_a_gapless_transition_does_not() {
        let (ring, fake) = ring_with_events(1000, 100);
        started(&ring);
        assert!(ring.sink_write(&frames(0, 100)));
        // end of a track, the next one follows at once
        fake.push(SinkEvent::EndOfTrack);
        fake.push(SinkEvent::PlayRequestIdChanged);
        assert!(ring.sink_write(&frames(100, 100)));
        assert_eq!(ring.snapshot().epoch, 1, "gapless: no new epoch");
        // the listener picks another track: no EndOfTrack in front of the request
        fake.push(SinkEvent::PlayRequestIdChanged);
        assert!(ring.sink_write(&frames(200, 100)));
        let s = ring.snapshot();
        assert_eq!((s.epoch, s.kind, s.epoch_start), (2, EpochKind::Flush, 200));
    }

    #[test]
    fn an_end_of_track_does_not_excuse_a_much_later_play_request() {
        let (ring, fake) = ring_with_events(1000, 100);
        started(&ring);
        fake.push(SinkEvent::EndOfTrack);
        assert!(ring.sink_write(&frames(0, 10))); // a write after it: the transition did not happen
        fake.push(SinkEvent::PlayRequestIdChanged);
        assert!(ring.sink_write(&frames(10, 10)));
        assert_eq!(ring.snapshot().epoch, 2);
    }

    #[test]
    fn other_events_between_end_of_track_and_the_request_do_not_matter() {
        let (ring, fake) = ring_with_events(1000, 100);
        started(&ring);
        fake.push(SinkEvent::EndOfTrack);
        fake.push(SinkEvent::Other);
        fake.push(SinkEvent::Other);
        fake.push(SinkEvent::PlayRequestIdChanged);
        assert!(ring.sink_write(&frames(0, 10)));
        assert_eq!(ring.snapshot().epoch, 1);
    }

    #[test]
    fn a_pause_rewinds_to_what_was_heard_and_the_resume_continues_there() {
        let (ring, _fake) = ring_with_events(1000, 200);
        started(&ring);
        assert!(ring.sink_write(&frames(0, 400)));
        // the living room has taken 300 frames but only 250 were heard (50 are in the AudioTrack)
        assert_eq!(lr_data(&ring, 300, Some(250)).0, 0);
        ring.sink_stop();
        let s = ring.snapshot();
        assert!(!s.playing());
        assert_eq!(s.lr_pos, 250, "back to the head position");
        let mut out = vec![0i16; 20];
        assert!(matches!(ring.lr_read(&mut out, 10, None, MS), LrRead::Paused));
        // resume
        ring.sink_start();
        let s = ring.snapshot();
        assert_eq!((s.kind, s.epoch_start, s.start_pending), (EpochKind::Resume, 250, true));
        ring.lr_ack(s.epoch);
        let (first, got) = lr_data(&ring, 500, None);
        assert_eq!(first, 250);
        assert_eq!(got, frames(250, 150), "exactly the frames that were not heard, none twice, none lost");
    }

    #[test]
    fn the_rewind_never_goes_back_further_than_the_history_or_the_epoch() {
        let (ring, _fake) = ring_with_events(1000, 100);
        started(&ring);
        assert!(ring.sink_write(&frames(0, 600)));
        assert_eq!(lr_data(&ring, 500, Some(10)).0, 0); // a stale, far too old hint
        ring.sink_stop();
        assert_eq!(ring.snapshot().lr_pos, 400, "at most the history (100) behind the reader (500)");

        // a hint from before the epoch (the old track's tail) is clamped to the epoch start
        let (ring, fake) = ring_with_events(1000, 100);
        started(&ring);
        assert!(ring.sink_write(&frames(0, 300)));
        assert_eq!(lr_data(&ring, 200, None).0, 0);
        fake.push(SinkEvent::Seeked);
        assert!(ring.sink_write(&frames(300, 100))); // epoch 2 starts at 300
        ring.lr_ack(2);
        assert_eq!(lr_data(&ring, 50, Some(280)).0, 300); // the hint is still in the old epoch
        ring.sink_stop();
        assert_eq!(ring.snapshot().lr_pos, 300, "not before the epoch start");
    }

    #[test]
    fn a_pause_without_a_hint_stays_where_the_reader_is() {
        let (ring, _fake) = ring_with_events(1000, 100);
        started(&ring);
        assert!(ring.sink_write(&frames(0, 100)));
        assert_eq!(lr_data(&ring, 60, None).0, 0);
        ring.sink_stop();
        assert_eq!(ring.snapshot().lr_pos, 60);
    }

    #[test]
    fn a_seek_while_paused_is_applied_by_the_resume() {
        let (ring, fake) = ring_with_events(1000, 100);
        started(&ring);
        assert!(ring.sink_write(&frames(0, 300)));
        assert_eq!(lr_data(&ring, 100, None).0, 0);
        ring.sink_stop();
        fake.push(SinkEvent::Seeked);
        // while paused nothing is flushed, the stale frames just sit there
        assert_eq!(ring.snapshot().epoch, 1);
        ring.sink_start();
        let s = ring.snapshot();
        assert_eq!((s.epoch, s.kind), (2, EpochKind::Flush));
        assert_eq!(s.epoch_start, 300, "the pending pre-seek frames are gone");
        assert!(ring.sink_write(&frames(300, 20)));
        ring.lr_ack(2);
        assert_eq!(lr_data(&ring, 100, None).0, 300);
    }

    #[test]
    fn a_seek_right_before_a_pause_drops_the_pending_frames() {
        let (ring, fake) = ring_with_events(1000, 100);
        started(&ring);
        assert!(ring.sink_write(&frames(0, 300)));
        assert_eq!(lr_data(&ring, 100, Some(90)).0, 0);
        fake.push(SinkEvent::Seeked);
        ring.sink_stop(); // the stop drains the event first
        let s = ring.snapshot();
        assert_eq!((s.lr_pos, s.write_idx), (300, 300));
        ring.sink_start();
        let s = ring.snapshot();
        assert_eq!((s.kind, s.epoch_start), (EpochKind::Resume, 300));
    }

    #[test]
    fn the_kitchen_reads_by_absolute_index_without_consuming() {
        let (ring, _fake) = ring_with_events(1000, 100);
        started(&ring);
        assert!(ring.sink_write(&frames(0, 500)));
        let mut out = vec![0i16; 200];
        match ring.k_read(100, 1, &mut out, 100, 5 * MS) {
            KRead::Data { frames: n } => assert_eq!((n, &out[..200]), (100, &frames(100, 100)[..])),
            _ => panic!("expected data"),
        }
        // the same frames again: reading does not consume
        assert!(matches!(ring.k_read(100, 1, &mut out, 100, 5 * MS), KRead::Data { frames: 100 }));
        // and the living room is unaffected
        assert_eq!(lr_data(&ring, 50, None).0, 0);
        // at the end of the written frames it waits
        assert!(matches!(ring.k_read(500, 1, &mut out, 10, 5 * MS), KRead::Timeout));
    }

    #[test]
    fn two_readers_do_not_disturb_each_other() {
        let ring = Arc::new(FrameRing::with_capacity(2000, 200));
        started(&ring);
        let writer = {
            let ring = ring.clone();
            thread::spawn(move || {
                for i in 0..40u64 {
                    assert!(ring.sink_write(&frames(i * 50, 50)));
                }
            })
        };
        let kitchen = {
            let ring = ring.clone();
            thread::spawn(move || {
                let mut cursor = 0u64;
                let mut out = vec![0i16; 160];
                let mut got = Vec::new();
                while cursor < 2000 {
                    if let KRead::Data { frames: n } = ring.k_read(cursor, 1, &mut out, 80, 20 * MS) {
                        got.extend_from_slice(&out[..n * 2]);
                        cursor += n as u64;
                    }
                }
                got
            })
        };
        let mut living = Vec::new();
        while living.len() < 4000 {
            let (_, got) = lr_data(&ring, 70, None);
            living.extend(got);
        }
        writer.join().unwrap();
        let kitchen = kitchen.join().unwrap();
        assert_eq!(living, frames(0, 2000));
        assert_eq!(kitchen, frames(0, 2000));
    }

    #[test]
    fn a_slow_kitchen_is_lapped_and_a_new_epoch_makes_its_session_obsolete() {
        let (ring, fake) = ring_with_events(300, 50);
        started(&ring);
        let mut out = vec![0i16; 100];
        for i in 0..6u64 {
            assert!(ring.sink_write(&frames(i * 100, 100)));
            let _ = lr_data(&ring, 100, None); // the living room keeps up
        }
        // 600 frames written, the ring holds the last 300
        assert!(matches!(
            ring.k_read(100, 1, &mut out, 10, MS),
            KRead::Lapped { valid_start: 300 }
        ));
        assert!(matches!(ring.k_read(300, 1, &mut out, 10, MS), KRead::Data { .. }));
        fake.push(SinkEvent::Seeked);
        assert!(ring.sink_write(&frames(600, 10)));
        assert!(matches!(
            ring.k_read(300, 1, &mut out, 10, MS),
            KRead::EpochChanged { epoch: 2 }
        ));
    }

    #[test]
    fn a_stall_restart_goes_back_to_what_was_heard_and_opens_an_epoch() {
        let (ring, _fake) = ring_with_events(1000, 200);
        started(&ring);
        assert!(ring.sink_write(&frames(0, 400)));
        assert_eq!(lr_data(&ring, 300, Some(280)).0, 0);
        ring.lr_restart(Some(270));
        let s = ring.snapshot();
        assert_eq!((s.kind, s.epoch_start, s.lr_pos, s.start_pending), (EpochKind::Restart, 270, 270, true));
        ring.lr_ack(s.epoch);
        assert_eq!(lr_data(&ring, 500, None).0, 270);
    }

    #[test]
    fn the_calibration_source_holds_the_sink_and_hands_over_afterwards() {
        let (ring, _fake) = ring_with_events(1000, 100);
        started(&ring);
        assert!(ring.sink_write(&frames(0, 200)));
        ring.synth_begin();
        let s = ring.snapshot();
        assert_eq!((s.kind, s.epoch, s.synthetic, s.lr_pos), (EpochKind::Synthetic, 2, true, 200));
        ring.lr_ack(2);
        assert!(ring.synth_write(&frames(200, 100)));
        assert_eq!(lr_data(&ring, 100, None).0, 200);
        // the sink's next write is held while the synthetic source plays
        let sink = {
            let ring = ring.clone();
            thread::spawn(move || ring.sink_write(&frames(1000, 10)))
        };
        thread::sleep(60 * MS);
        assert!(!sink.is_finished());
        ring.synth_end();
        assert!(sink.join().unwrap());
        let s = ring.snapshot();
        assert_eq!((s.synthetic, s.kind, s.start_pending), (false, EpochKind::Flush, true));
        assert_eq!(s.epoch_start, 300, "the sink's frames open the next epoch");
    }

    #[test]
    fn ending_the_calibration_with_nothing_playing_stops_the_living_room() {
        let (ring, _fake) = ring_with_events(1000, 100);
        ring.synth_begin();
        ring.lr_ack(1);
        assert!(ring.synth_write(&frames(0, 100)));
        assert_eq!(lr_data(&ring, 50, None).0, 0);
        ring.synth_end();
        let mut out = vec![0i16; 20];
        assert!(matches!(ring.lr_read(&mut out, 10, None, MS), LrRead::Paused));
        assert!(!ring.snapshot().playing());
    }

    #[test]
    fn closing_releases_every_waiter() {
        let ring = Arc::new(FrameRing::with_capacity(100, 10));
        started(&ring);
        assert!(ring.sink_write(&frames(0, 90)));
        let writer = {
            let ring = ring.clone();
            thread::spawn(move || ring.sink_write(&frames(90, 50)))
        };
        let kitchen = {
            let ring = ring.clone();
            thread::spawn(move || {
                let mut out = vec![0i16; 20];
                matches!(ring.k_read(90, 1, &mut out, 10, Duration::from_secs(10)), KRead::Closed)
            })
        };
        thread::sleep(50 * MS);
        ring.close();
        assert!(!writer.join().unwrap());
        assert!(kitchen.join().unwrap());
        let mut out = vec![0i16; 20];
        assert!(matches!(ring.lr_read(&mut out, 10, None, Duration::from_secs(10)), LrRead::Closed));
    }

    #[test]
    fn the_sink_converts_to_i16_and_stop_never_fails() {
        let ring = Arc::new(FrameRing::with_capacity(1000, 100));
        let events = Arc::new(EventQueue::new());
        let mut sink = RingSink::new(ring.clone(), events.clone());
        let mut converter = Converter::new(None);
        sink.start().unwrap();
        ring.lr_ack(ring.snapshot().epoch);
        sink.write(AudioPacket::Samples(vec![0.0, 0.0, 1.0, -1.0, 0.5, 0.25]), &mut converter)
            .unwrap();
        sink.stop().unwrap();
        // paused again: the frames are still there for the resume
        ring.sink_start();
        ring.lr_ack(ring.snapshot().epoch);
        let (_, got) = lr_data(&ring, 10, None);
        assert_eq!(got.len(), 6, "{got:?}");
        assert_eq!(got[0], 0);
        assert!(got[2] >= 32766, "full scale positive: {got:?}");
        assert!(got[3] <= -32767, "full scale negative: {got:?}");
        assert!((i32::from(got[4]) - 16384).abs() <= 1, "half scale: {got:?}");
        // start and stop leave a trace on the event queue (debug page)
        assert!(events.next(Duration::from_millis(5)).unwrap().contains("\"start\""));
        assert!(events.next(Duration::from_millis(5)).unwrap().contains("\"stop\""));
    }
}
