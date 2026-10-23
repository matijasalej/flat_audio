//! The way into the ring for a source of music that does not live in this native core.
//!
//! librespot, the one source there is today, feeds the ring from inside (`RingSink`, with the player's
//! events telling the ring where the audio jumps). A second source (the capture of the Spotify TV app,
//! say) lives on the Kotlin side and needs the same four things, which is all of this interface:
//!
//! * `start`: playback begins or resumes (both rooms start together on the next frames),
//! * `pause`: playback pauses (the rooms fall silent, the ring remembers what was heard),
//! * `flush`: the audio jumps (a seek, a skip): what is not played yet is void,
//! * `write`: interleaved stereo 16 bit PCM at the source's sample rate, blocking while the ring is full.
//!
//! The ring itself is 44.1 kHz. A source with another rate has to be converted before it gets here
//! (`write` says no to any other rate); the 48 kHz capture of another app is the case in mind, and the
//! resampler belongs to that source.
//!
//! What a source says about the track (title, artist, duration) and its volume does not pass the ring;
//! the Kotlin side takes those straight into the status (see `source/AudioSource.kt`).

use std::sync::Arc;

use crate::ring::{FrameRing, RATE};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SourceError {
    /// The ring plays 44.1 kHz only; the source has to convert first.
    UnsupportedRate(u32),
    /// The engine is shutting down.
    Closed,
}

/// What a source can do to the ring. Cheap to clone-by-Arc, safe to call from any thread.
pub struct RingInput {
    ring: Arc<FrameRing>,
}

impl RingInput {
    pub fn new(ring: Arc<FrameRing>) -> Self {
        RingInput { ring }
    }

    pub fn accepts_rate(rate: u32) -> bool {
        rate == RATE as u32
    }

    /// Playback begins or resumes.
    pub fn start(&self) {
        self.ring.sink_start();
    }

    /// Playback pauses.
    pub fn pause(&self) {
        self.ring.sink_stop();
    }

    /// The audio jumps: drop what has not been played, the next frame opens a new epoch.
    pub fn flush(&self) {
        self.ring.request_flush();
    }

    /// Writes interleaved stereo frames (`pcm.len()` is twice the number of frames) at `rate`.
    pub fn write(&self, pcm: &[i16], rate: u32) -> Result<(), SourceError> {
        if !Self::accepts_rate(rate) {
            return Err(SourceError::UnsupportedRate(rate));
        }
        if self.ring.sink_write(pcm) {
            Ok(())
        } else {
            Err(SourceError::Closed)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ring::{EpochKind, LrRead};
    use std::time::Duration;

    const MS: Duration = Duration::from_millis(1);

    fn frames(from: u64, n: u64) -> Vec<i16> {
        let mut v = Vec::new();
        for i in from..from + n {
            v.push(i as i16);
            v.push(-(i as i16));
        }
        v
    }

    /// What a Kotlin-side source does: start, write, and the living room (the Kotlin audio thread) reads.
    fn read(ring: &FrameRing, max: usize) -> LrRead {
        let mut out = vec![0i16; max * 2];
        ring.lr_read(&mut out, max, None, 20 * MS)
    }

    #[test]
    fn a_second_source_starts_plays_and_the_living_room_hears_it() {
        let ring = Arc::new(FrameRing::new());
        let input = RingInput::new(ring.clone());
        input.start();
        let s = ring.snapshot();
        assert!(s.start_pending);
        assert_eq!(s.kind, EpochKind::Resume);
        // nothing is handed out before the start was scheduled
        assert!(matches!(read(&ring, 100), LrRead::StartPending { .. }));
        ring.lr_ack(s.epoch);
        input.write(&frames(0, 1000), 44_100).unwrap();
        match read(&ring, 400) {
            LrRead::Data { first, frames, .. } => assert_eq!((first, frames), (0, 400)),
            _ => panic!("expected data"),
        }
    }

    #[test]
    fn pause_and_start_again_resumes_where_it_was_heard() {
        let ring = Arc::new(FrameRing::new());
        let input = RingInput::new(ring.clone());
        input.start();
        let epoch = ring.snapshot().epoch;
        ring.lr_ack(epoch);
        input.write(&frames(0, 2000), 44_100).unwrap();
        let mut out = vec![0i16; 2000 * 2];
        // the living room has taken 1500 frames and reports that it heard up to 1200
        assert!(matches!(
            ring.lr_read(&mut out, 1500, Some(1200), 20 * MS),
            LrRead::Data { frames: 1500, .. }
        ));
        input.pause();
        assert!(matches!(read(&ring, 10), LrRead::Paused));
        input.start();
        let s = ring.snapshot();
        assert_eq!((s.kind, s.epoch_start), (EpochKind::Resume, 1200));
    }

    #[test]
    fn a_flush_drops_what_was_not_played_and_opens_a_new_epoch_at_the_next_frame() {
        let ring = Arc::new(FrameRing::new());
        let input = RingInput::new(ring.clone());
        input.start();
        let first_epoch = ring.snapshot().epoch;
        ring.lr_ack(first_epoch);
        input.write(&frames(0, 3000), 44_100).unwrap();
        // the user seeks: the source flushes and goes on with other audio
        input.flush();
        let s = ring.snapshot();
        assert_eq!(s.kind, EpochKind::Flush);
        assert!(s.epoch > first_epoch && s.start_pending);
        assert_eq!(s.epoch_start, 3000);
        ring.lr_ack(s.epoch);
        input.write(&frames(3000, 500), 44_100).unwrap();
        match read(&ring, 500) {
            LrRead::Data { first, frames, epoch } => assert_eq!((first, frames, epoch), (3000, 500, s.epoch)),
            _ => panic!("expected the new epoch's frames"),
        }
    }

    #[test]
    fn a_flush_while_paused_is_applied_by_the_next_start() {
        let ring = Arc::new(FrameRing::new());
        let input = RingInput::new(ring.clone());
        input.start();
        let epoch = ring.snapshot().epoch;
        ring.lr_ack(epoch);
        input.write(&frames(0, 500), 44_100).unwrap();
        input.pause();
        input.flush();
        assert_eq!(ring.snapshot().epoch, epoch, "nothing plays, so nothing starts");
        input.start();
        assert_eq!(ring.snapshot().kind, EpochKind::Flush);
    }

    #[test]
    fn a_rate_other_than_44100_is_refused_until_a_resampler_is_in_front() {
        let ring = Arc::new(FrameRing::new());
        let input = RingInput::new(ring.clone());
        assert!(RingInput::accepts_rate(44_100));
        assert!(!RingInput::accepts_rate(48_000));
        assert_eq!(input.write(&frames(0, 10), 48_000), Err(SourceError::UnsupportedRate(48_000)));
        assert_eq!(ring.snapshot().write_idx, 0);
    }

    #[test]
    fn writing_to_a_closed_ring_reports_it() {
        let ring = Arc::new(FrameRing::new());
        let input = RingInput::new(ring.clone());
        input.start();
        ring.close();
        assert_eq!(input.write(&frames(0, 10), 44_100), Err(SourceError::Closed));
    }
}
