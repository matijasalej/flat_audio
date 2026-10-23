//! The calibration source: a click track that runs through the whole pipeline like a Spotify stream.
//!
//! One click per second, at frame k * 44100 counted from the start of the test: a 5 ms sine burst
//! under a Hann window at half of full scale, nothing in between. The left channel carries 2 kHz
//! (4 kHz when k is a multiple of 10), the right channel 3 kHz (6 kHz). The living room plays the
//! left channel only and the kitchen the right channel only (the readers do that), so a microphone
//! between the rooms can tell them apart by pitch and count the clicks by their index.

use std::sync::Arc;
use std::thread::JoinHandle;
use std::time::Duration;

use crate::ring::{FrameRing, CHANNELS, RATE};

/// 5 ms.
pub const CLICK_FRAMES: u64 = 220;
const AMPLITUDE: f64 = 0.5;
/// A burst is cut into writes of this many frames (10 ms).
const CHUNK: u64 = 441;
/// Silence after the last click, so that it is played out before the source ends.
const TAIL_SECONDS: u64 = 3;

/// The sample at `frame` of the click track (frames counted from the start of the test).
pub fn click_sample(frame: u64) -> (f64, f64) {
    let k = frame / RATE;
    let i = frame % RATE;
    if i >= CLICK_FRAMES {
        return (0.0, 0.0);
    }
    let window = 0.5 - 0.5 * (2.0 * std::f64::consts::PI * i as f64 / (CLICK_FRAMES - 1) as f64).cos();
    let (fl, fr) = if k % 10 == 0 { (4000.0, 6000.0) } else { (2000.0, 3000.0) };
    let phase = 2.0 * std::f64::consts::PI * i as f64 / RATE as f64;
    (
        AMPLITUDE * window * (phase * fl).sin(),
        AMPLITUDE * window * (phase * fr).sin(),
    )
}

/// Interleaved i16 stereo for `n` frames starting at `first`.
pub fn fill_clicks(first: u64, n: u64) -> Vec<i16> {
    let mut out = Vec::with_capacity(n as usize * CHANNELS);
    for f in first..first + n {
        let (l, r) = click_sample(f);
        out.push((l * 32767.0).round() as i16);
        out.push((r * 32767.0).round() as i16);
    }
    out
}

/// Plays `seconds` of clicks through the ring on its own thread, then ends. The thread returns early
/// when the source is aborted or the ring is closed.
pub fn spawn(ring: Arc<FrameRing>, seconds: u32) -> JoinHandle<()> {
    std::thread::Builder::new()
        .name("wholeflat-calib".to_string())
        .spawn(move || run(&ring, seconds))
        .expect("spawn the calibration thread")
}

fn run(ring: &FrameRing, seconds: u32) {
    ring.synth_begin();
    let total = seconds as u64 * RATE;
    let mut n = 0u64;
    let mut ok = true;
    while n < total && ok {
        let chunk = CHUNK.min(total - n);
        ok = ring.synth_write(&fill_clicks(n, chunk));
        n += chunk;
    }
    if ok {
        // silence, so that the last click is played out of the ring
        let end = total + TAIL_SECONDS * RATE;
        let silence = vec![0i16; CHUNK as usize * CHANNELS];
        while n < end && ok {
            let chunk = CHUNK.min(end - n);
            ok = ring.synth_write(&silence[..chunk as usize * CHANNELS]);
            n += chunk;
        }
    }
    if ok {
        // let the living room consume the clicks before the source ends
        let target = ring.write_idx().saturating_sub(TAIL_SECONDS * RATE / 2);
        ring.synth_wait_consumed(target, Duration::from_secs(15));
    }
    ring.synth_end();
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Goertzel magnitude of `freq` in `x` (mono, 44.1 kHz).
    fn magnitude(x: &[f64], freq: f64) -> f64 {
        let w = 2.0 * std::f64::consts::PI * freq / RATE as f64;
        let (mut re, mut im) = (0.0, 0.0);
        for (i, v) in x.iter().enumerate() {
            re += v * (w * i as f64).cos();
            im -= v * (w * i as f64).sin();
        }
        (re * re + im * im).sqrt()
    }

    fn channel(first: u64, n: u64, ch: usize) -> Vec<f64> {
        fill_clicks(first, n).chunks(2).map(|f| f[ch] as f64 / 32767.0).collect()
    }

    #[test]
    fn there_is_one_burst_per_second_and_silence_between() {
        let x = fill_clicks(0, 3 * RATE);
        for sec in 0..3u64 {
            let base = (sec * RATE) as usize * 2;
            let burst = &x[base..base + CLICK_FRAMES as usize * 2];
            assert!(burst.iter().any(|v| v.abs() > 1000), "second {sec} has a click");
            let rest = &x[base + CLICK_FRAMES as usize * 2..base + RATE as usize * 2];
            assert!(rest.iter().all(|v| *v == 0), "second {sec}: nothing but the click");
        }
    }

    #[test]
    fn the_burst_is_hann_windowed_and_at_half_scale() {
        let l = channel(0, CLICK_FRAMES, 0);
        assert_eq!(l[0], 0.0);
        assert!(l[CLICK_FRAMES as usize - 1].abs() < 0.01, "ends at zero");
        let peak = l.iter().fold(0.0f64, |a, v| a.max(v.abs()));
        assert!(peak <= 0.5 + 1e-4 && peak > 0.45, "peak {peak}");
        // the click of second 1 is the same shape at 2 kHz, so is not louder than the first
        let l1 = channel(RATE, CLICK_FRAMES, 0);
        let peak1 = l1.iter().fold(0.0f64, |a, v| a.max(v.abs()));
        assert!(peak1 <= 0.5 + 1e-4);
    }

    #[test]
    fn left_is_2_khz_and_right_3_khz_except_every_tenth_click() {
        for (k, fl, fr) in [(1u64, 2000.0, 3000.0), (5, 2000.0, 3000.0), (10, 4000.0, 6000.0), (0, 4000.0, 6000.0), (20, 4000.0, 6000.0)] {
            let l = channel(k * RATE, CLICK_FRAMES, 0);
            let r = channel(k * RATE, CLICK_FRAMES, 1);
            let other_l = if fl == 2000.0 { 4000.0 } else { 2000.0 };
            let other_r = if fr == 3000.0 { 6000.0 } else { 3000.0 };
            assert!(magnitude(&l, fl) > 3.0 * magnitude(&l, other_l), "click {k} left is {fl} Hz");
            assert!(magnitude(&r, fr) > 3.0 * magnitude(&r, other_r), "click {k} right is {fr} Hz");
            assert!(magnitude(&l, fl) > 3.0 * magnitude(&l, 3000.0), "the left channel has no 3 kHz");
        }
    }

    #[test]
    fn the_source_runs_through_a_ring_and_ends_by_itself() {
        use crate::ring::{LrRead, FrameRing};
        let ring = Arc::new(FrameRing::with_capacity(20_000, 2_000));
        let handle = spawn(ring.clone(), 1);
        let mut got = 0u64;
        let mut out = vec![0i16; 882];
        let mut acked = false;
        let started = std::time::Instant::now();
        loop {
            assert!(started.elapsed() < Duration::from_secs(20), "the source must end");
            match ring.lr_read(&mut out, 441, None, Duration::from_millis(20)) {
                LrRead::Data { frames, .. } => got += frames as u64,
                LrRead::StartPending { epoch } => {
                    assert!(!acked);
                    ring.lr_ack(epoch);
                    acked = true;
                }
                LrRead::Paused if acked => break,
                _ => {}
            }
        }
        handle.join().unwrap();
        assert!(got >= RATE, "at least the second of clicks was played: {got}");
        assert!(!ring.snapshot().playing());
    }
}
