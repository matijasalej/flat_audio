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

#[cfg(test)]
mod tests {
    use super::*;
    use std::thread;

    fn read(ring: &Ring, max: usize) -> Vec<i16> {
        let mut out = vec![0i16; max];
        match ring.read_timeout(&mut out, Duration::from_millis(20)) {
            ReadResult::Data(n) => {
                out.truncate(n);
                out
            }
            ReadResult::Timeout => Vec::new(),
            ReadResult::Closed => panic!("closed"),
        }
    }

    #[test]
    fn keeps_order_across_the_wrap_around() {
        let ring = Ring::new(8); // four stereo frames
        assert!(ring.write_blocking(&[1, 2, 3, 4, 5, 6]));
        assert_eq!(read(&ring, 4), vec![1, 2, 3, 4]);
        assert!(ring.write_blocking(&[7, 8, 9, 10])); // 5..10 now straddles the end of the buffer
        assert_eq!(read(&ring, 16), vec![5, 6, 7, 8, 9, 10]);
        assert!(read(&ring, 16).is_empty());
    }

    #[test]
    fn only_whole_frames_go_in_and_come_out() {
        let ring = Ring::new(16);
        assert!(ring.write_blocking(&[1, 2, 3])); // the lone third sample is dropped
        assert_eq!(read(&ring, 16), vec![1, 2]);
        assert!(ring.write_blocking(&[1, 2, 3, 4]));
        assert_eq!(read(&ring, 3), vec![1, 2]); // an odd buffer gets one frame, not 3 samples
        assert_eq!(read(&ring, 3), vec![3, 4]);
    }

    #[test]
    fn read_times_out_when_nothing_arrives() {
        let ring = Ring::new(8);
        let mut out = [0i16; 4];
        let started = Instant::now();
        assert!(matches!(
            ring.read_timeout(&mut out, Duration::from_millis(30)),
            ReadResult::Timeout
        ));
        assert!(started.elapsed() >= Duration::from_millis(25));
    }

    #[test]
    fn a_full_ring_blocks_the_writer_until_the_reader_makes_room() {
        let ring = Arc::new(Ring::new(8));
        assert!(ring.write_blocking(&[0; 8])); // exactly full
        let writer = {
            let ring = ring.clone();
            thread::spawn(move || ring.write_blocking(&[9, 9, 9, 9]))
        };
        thread::sleep(Duration::from_millis(80));
        assert!(!writer.is_finished(), "the writer must wait while the ring is full");
        assert_eq!(read(&ring, 4), vec![0, 0, 0, 0]);
        assert!(writer.join().unwrap());
        assert_eq!(read(&ring, 16), vec![0, 0, 0, 0, 9, 9, 9, 9]);
    }

    #[test]
    fn close_releases_a_blocked_writer_and_a_waiting_reader() {
        let ring = Arc::new(Ring::new(8));
        assert!(ring.write_blocking(&[0; 8]));
        let writer = {
            let ring = ring.clone();
            thread::spawn(move || ring.write_blocking(&[1, 1]))
        };
        thread::sleep(Duration::from_millis(30));
        ring.close();
        assert!(!writer.join().unwrap(), "a closed ring drops the rest");
        let mut out = [0i16; 4];
        assert!(matches!(
            ring.read_timeout(&mut out, Duration::from_millis(10)),
            ReadResult::Closed
        ));
    }

    #[test]
    fn the_sink_converts_to_i16_and_stop_never_fails() {
        let ring = Arc::new(Ring::new(16));
        let events = Arc::new(EventQueue::new());
        let mut sink = RingSink::new(ring.clone(), events.clone());
        let mut converter = Converter::new(None);
        sink.start().unwrap();
        sink.write(AudioPacket::Samples(vec![0.0, 1.0, -1.0, 0.5]), &mut converter)
            .unwrap();
        sink.stop().unwrap();
        let got = read(&ring, 8);
        assert_eq!(got.len(), 4, "{got:?}");
        assert_eq!(got[0], 0);
        assert!(got[1] >= 32766, "full scale positive: {got:?}");
        assert!(got[2] <= -32767, "full scale negative: {got:?}");
        assert!((i32::from(got[3]) - 16384).abs() <= 1, "half scale: {got:?}");
        // start and stop leave a trace on the event queue (debug page)
        assert!(events.next(Duration::from_millis(5)).unwrap().contains("\"start\""));
        assert!(events.next(Duration::from_millis(5)).unwrap().contains("\"stop\""));
    }

    #[test]
    fn a_steady_producer_and_consumer_lose_nothing() {
        let ring = Arc::new(Ring::new(64));
        let producer = {
            let ring = ring.clone();
            thread::spawn(move || {
                for chunk in 0..200i16 {
                    let block: Vec<i16> = (0..10).map(|i| chunk * 10 + i).collect();
                    assert!(ring.write_blocking(&block));
                }
            })
        };
        let mut seen: Vec<i16> = Vec::new();
        while seen.len() < 2000 {
            seen.extend(read(&ring, 7)); // 7 rounds down to 6: whole frames at an odd size
        }
        producer.join().unwrap();
        assert_eq!(seen, (0..2000).collect::<Vec<i16>>());
    }
}
