//! What to do when Spotify refuses the audio key of a track.
//!
//! Some accounts get their audio keys refused for a while ("error audio key 0 1", librespot issue
//! #1649). Left alone, librespot answers by skipping to the next track, whose key request is refused
//! as well, and so on through the whole queue; the phone then sends the track again and the requests
//! never stop. Those storms keep the refusal going.
//!
//! So a refusal is handled here instead of in the player:
//!
//! * The (patched) player does not skip. It tells the engine (`AudioKeyUnavailable`) and stays in its
//!   loading state. The engine pauses Spirc, so that the phone shows a paused device, and keeps the
//!   track.
//! * The same track is loaded again after 3 s, 10 s, 30 s, 2 min, 5 min and then every 15 min, until
//!   it plays, or until the next try would be later than 30 min after the first refusal.
//! * A command of the user ends the waiting where it makes sense: "resume" tries again at once (but
//!   never sooner than 3 s after the last refusal), "pause" stops the tries until the user presses
//!   play, anything that loads another track or position starts over from zero.
//! * Independently of all this, the vendored `librespot-core` spaces out the key requests (at most one
//!   per 3 s after an error), so even a user who taps play like mad cannot make a storm.
//!
//! This module is the state machine only: it takes the events and the time as arguments and returns
//! what to do (`Action`), so that the engine stays thin and the back-off can be tested without a clock.

use std::time::{Duration, Instant};

/// The waits before the 1st, 2nd, ... try; the last one repeats.
pub const RETRY_DELAYS: [Duration; 6] = [
    Duration::from_secs(3),
    Duration::from_secs(10),
    Duration::from_secs(30),
    Duration::from_secs(120),
    Duration::from_secs(300),
    Duration::from_secs(900),
];

/// No try is scheduled later than this after the first refusal of an episode.
pub const GIVE_UP_AFTER: Duration = Duration::from_secs(30 * 60);

/// Never two key requests closer than this (also enforced in `librespot-core`).
pub const MIN_GAP: Duration = Duration::from_secs(3);

/// A try that has not ended in success or in another refusal after this long counts as a failure.
pub const RETRY_TIMEOUT: Duration = Duration::from_secs(120);

/// The load that is stuck.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Track {
    pub uri: String,
    pub request_id: u64,
    pub position_ms: u32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Phase {
    /// Nothing is wrong.
    Ok,
    /// The key was refused, the next try is scheduled.
    Waiting,
    /// The same track was loaded again, the outcome is not known yet.
    Retrying,
    /// The user wants it paused: nothing is tried until they press play.
    UserPaused,
    /// Refused for 30 minutes: nothing is tried until the user presses play.
    GaveUp,
}

impl Phase {
    pub fn name(self) -> &'static str {
        match self {
            Phase::Ok => "ok",
            Phase::Waiting => "waiting",
            Phase::Retrying => "retrying",
            Phase::UserPaused => "user_paused",
            Phase::GaveUp => "gave_up",
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Action {
    /// Pause Spirc: the phone shows the device as paused instead of loading for ever.
    Pause,
    /// Load the same track again (`Spirc::play`, then `Player::seek`, which restarts a load).
    Retry { position_ms: u32 },
    /// Nothing more will be tried for now.
    GiveUp,
}

/// The commands of the user that matter here; the rest is `Other`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum UserCommand {
    Resume,
    Pause,
    Other,
}

impl UserCommand {
    /// From the name the dealer gives a controller command ("resume", "pause", "skip_next", ...).
    pub fn from_name(name: &str) -> Self {
        match name {
            "resume" => UserCommand::Resume,
            "pause" => UserCommand::Pause,
            _ => UserCommand::Other,
        }
    }
}

/// What the status page shows.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Status {
    pub phase: Phase,
    /// Refusals in the current episode.
    pub refusals: u32,
    /// Refusals since the app started.
    pub total: u64,
    /// Whole seconds to the next try (rounded up), if one is scheduled.
    pub retry_in_s: Option<u64>,
}

#[derive(Debug)]
pub struct KeyGuard {
    phase: Phase,
    track: Option<Track>,
    loading: Option<Track>,
    refusals: u32,
    total: u64,
    episode_start: Option<Instant>,
    last_refusal: Option<Instant>,
    retry_at: Option<Instant>,
    retrying_since: Option<Instant>,
}

impl Default for KeyGuard {
    fn default() -> Self {
        Self::new()
    }
}

impl KeyGuard {
    pub fn new() -> Self {
        KeyGuard {
            phase: Phase::Ok,
            track: None,
            loading: None,
            refusals: 0,
            total: 0,
            episode_start: None,
            last_refusal: None,
            retry_at: None,
            retrying_since: None,
        }
    }

    pub fn phase(&self) -> Phase {
        self.phase
    }

    pub fn track(&self) -> Option<&Track> {
        self.track.as_ref()
    }

    /// A new session or a restarted engine: whatever was going on is void.
    pub fn reset(&mut self) {
        let total = self.total;
        *self = KeyGuard::new();
        self.total = total;
    }

    fn end_episode(&mut self) {
        self.phase = Phase::Ok;
        self.track = None;
        self.refusals = 0;
        self.episode_start = None;
        self.retry_at = None;
        self.retrying_since = None;
    }

    /// The player started loading a track (event `Loading`).
    pub fn on_loading(&mut self, now: Instant, uri: &str, request_id: u64, position_ms: u32) {
        self.loading = Some(Track {
            uri: uri.to_string(),
            request_id,
            position_ms,
        });
        let Some(stuck) = self.track.as_mut() else {
            return;
        };
        if self.phase == Phase::Ok {
            return;
        }
        if stuck.request_id != request_id || stuck.uri != uri {
            // Spirc or the user moved on to something else
            self.end_episode();
            return;
        }
        stuck.position_ms = position_ms;
        if self.phase != Phase::Retrying {
            // the same load again that we did not ask for: the user sought. Start over.
            self.phase = Phase::Retrying;
            self.refusals = 0;
            self.episode_start = Some(now);
            self.retry_at = None;
            self.retrying_since = Some(now);
        }
    }

    /// The key of the track being loaded was refused (event `AudioKeyUnavailable`).
    /// `start_playback` says whether the load was meant to start playing.
    pub fn on_refused(&mut self, now: Instant, uri: &str, request_id: u64, start_playback: bool) -> Vec<Action> {
        self.total += 1;
        self.last_refusal = Some(now);
        let same_episode = self.phase != Phase::Ok
            && self
                .track
                .as_ref()
                .map_or(false, |t| t.request_id == request_id && t.uri == uri);
        if same_episode {
            self.refusals += 1;
        } else {
            let position_ms = match &self.loading {
                Some(l) if l.request_id == request_id && l.uri == uri => l.position_ms,
                _ => 0,
            };
            self.track = Some(Track {
                uri: uri.to_string(),
                request_id,
                position_ms,
            });
            self.refusals = 1;
            self.episode_start = Some(now);
        }
        self.retrying_since = None;
        self.retry_at = None;

        if !start_playback || self.phase == Phase::UserPaused {
            // nobody wants it to play right now: wait for the user
            self.phase = Phase::UserPaused;
            return vec![Action::Pause];
        }
        self.schedule(now)
    }

    fn schedule(&mut self, now: Instant) -> Vec<Action> {
        let index = (self.refusals.max(1) as usize - 1).min(RETRY_DELAYS.len() - 1);
        let at = now + RETRY_DELAYS[index];
        let started = self.episode_start.unwrap_or(now);
        if at > started + GIVE_UP_AFTER {
            self.phase = Phase::GaveUp;
            self.retry_at = None;
            return vec![Action::Pause, Action::GiveUp];
        }
        self.phase = Phase::Waiting;
        self.retry_at = Some(at);
        vec![Action::Pause]
    }

    /// The track plays (event `Playing`, `Paused` with a track, ...). Returns the number of refusals
    /// of the episode that just ended, if there was one.
    pub fn on_success(&mut self) -> Option<u32> {
        if self.phase == Phase::Ok {
            return None;
        }
        let refusals = self.refusals;
        self.end_episode();
        Some(refusals)
    }

    /// The player was stopped (event `Stopped`): there is nothing to wait for any more.
    pub fn on_stopped(&mut self) {
        self.end_episode();
    }

    /// A command of the user (see `UserCommand`). The actions are returned by the next `poll`.
    pub fn on_user(&mut self, now: Instant, command: UserCommand) {
        match (command, self.phase) {
            (_, Phase::Ok) | (UserCommand::Other, _) => {}
            (UserCommand::Pause, Phase::Waiting | Phase::Retrying) => {
                self.phase = Phase::UserPaused;
                self.retry_at = None;
                self.retrying_since = None;
            }
            (UserCommand::Pause, _) => {}
            (UserCommand::Resume, Phase::Retrying) => {
                // a try is under way: only the back-off starts over
                self.refusals = 0;
                self.episode_start = Some(now);
            }
            (UserCommand::Resume, _) => {
                self.refusals = 0;
                self.episode_start = Some(now);
                let earliest = self.last_refusal.map_or(now, |t| t + MIN_GAP);
                self.retry_at = Some(earliest.max(now));
                self.retrying_since = None;
                self.phase = Phase::Waiting;
            }
        }
    }

    /// When `poll` has something to do next, if ever.
    pub fn deadline(&self) -> Option<Instant> {
        let timeout = self.retrying_since.map(|t| t + RETRY_TIMEOUT);
        match (self.retry_at, timeout) {
            (Some(a), Some(b)) => Some(a.min(b)),
            (a, b) => a.or(b),
        }
    }

    /// Call when `deadline()` has come.
    pub fn poll(&mut self, now: Instant) -> Vec<Action> {
        match self.phase {
            Phase::Waiting => match (self.retry_at, self.track.as_ref()) {
                (Some(at), Some(track)) if at <= now => {
                    let position_ms = track.position_ms;
                    self.phase = Phase::Retrying;
                    self.retry_at = None;
                    self.retrying_since = Some(now);
                    vec![Action::Retry { position_ms }]
                }
                _ => Vec::new(),
            },
            Phase::Retrying => match self.retrying_since {
                Some(since) if since + RETRY_TIMEOUT <= now => {
                    // no answer at all: count it as a refusal
                    self.last_refusal = Some(now);
                    self.refusals += 1;
                    self.total += 1;
                    self.retrying_since = None;
                    self.schedule(now)
                }
                _ => Vec::new(),
            },
            _ => Vec::new(),
        }
    }

    pub fn status(&self, now: Instant) -> Status {
        let retry_in_s = self.retry_at.map(|at| {
            let left = at.saturating_duration_since(now);
            (left.as_millis() as u64).div_ceil(1000)
        });
        Status {
            phase: self.phase,
            refusals: self.refusals,
            total: self.total,
            retry_in_s,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const TRACK: &str = "spotify:track:6rqhFgbbKwnb9MLmUQDhG6";
    const OTHER: &str = "spotify:track:14XWXWv5FoCbFzLksawpEe";

    struct Clock(Instant);

    impl Clock {
        fn new() -> Self {
            Clock(Instant::now())
        }

        fn at(&self, secs: u64) -> Instant {
            self.0 + Duration::from_secs(secs)
        }

        fn at_ms(&self, ms: u64) -> Instant {
            self.0 + Duration::from_millis(ms)
        }
    }

    /// Runs one refusal-and-retry cycle at `t`: the key is refused, the guard schedules; returns when the
    /// retry is due.
    fn refuse(g: &mut KeyGuard, t: Instant) -> Vec<Action> {
        g.on_refused(t, TRACK, 1, true)
    }

    #[test]
    fn the_waits_are_3_10_30_120_300_then_900_seconds() {
        let clock = Clock::new();
        let mut g = KeyGuard::new();
        g.on_loading(clock.at(0), TRACK, 1, 0);
        let mut now = clock.at(0);
        let mut waits = Vec::new();
        for _ in 0..6 {
            assert_eq!(refuse(&mut g, now), vec![Action::Pause]);
            let due = g.deadline().expect("a try is scheduled");
            waits.push((due - now).as_secs());
            now = due;
            assert_eq!(g.poll(now), vec![Action::Retry { position_ms: 0 }]);
            assert_eq!(g.phase(), Phase::Retrying);
        }
        assert_eq!(waits, vec![3, 10, 30, 120, 300, 900]);
    }

    #[test]
    fn it_gives_up_when_the_next_try_would_be_later_than_30_minutes() {
        let clock = Clock::new();
        let mut g = KeyGuard::new();
        g.on_loading(clock.at(0), TRACK, 1, 0);
        let mut now = clock.at(0);
        // the first six tries happen at 3, 13, 43, 163, 463 and 1363 s
        for _ in 0..6 {
            refuse(&mut g, now);
            now = g.deadline().unwrap();
            g.poll(now);
        }
        assert_eq!((now - clock.at(0)).as_secs(), 1363);
        // the seventh would be at 2263 s, more than 1800 s after the first refusal
        assert_eq!(refuse(&mut g, now), vec![Action::Pause, Action::GiveUp]);
        assert_eq!(g.phase(), Phase::GaveUp);
        assert_eq!(g.deadline(), None);
        assert!(g.poll(now + Duration::from_secs(3600)).is_empty());
    }

    #[test]
    fn the_status_counts_down_and_rounds_up() {
        let clock = Clock::new();
        let mut g = KeyGuard::new();
        g.on_loading(clock.at(0), TRACK, 1, 0);
        refuse(&mut g, clock.at(0));
        let s = g.status(clock.at(0));
        assert_eq!((s.phase, s.refusals, s.total, s.retry_in_s), (Phase::Waiting, 1, 1, Some(3)));
        assert_eq!(g.status(clock.at_ms(1_200)).retry_in_s, Some(2));
        assert_eq!(g.status(clock.at_ms(2_900)).retry_in_s, Some(1));
        assert_eq!(g.status(clock.at(5)).retry_in_s, Some(0));
        assert_eq!(KeyGuard::new().status(clock.at(0)).retry_in_s, None);
    }

    #[test]
    fn nothing_is_tried_before_its_time() {
        let clock = Clock::new();
        let mut g = KeyGuard::new();
        g.on_loading(clock.at(0), TRACK, 1, 0);
        refuse(&mut g, clock.at(0));
        assert!(g.poll(clock.at_ms(2_999)).is_empty());
        assert_eq!(g.poll(clock.at(3)), vec![Action::Retry { position_ms: 0 }]);
        // and once only
        assert!(g.poll(clock.at(4)).is_empty());
    }

    #[test]
    fn the_track_comes_back_at_the_position_it_was_loaded_at() {
        let clock = Clock::new();
        let mut g = KeyGuard::new();
        g.on_loading(clock.at(0), TRACK, 1, 83_000);
        refuse(&mut g, clock.at(1));
        assert_eq!(g.poll(clock.at(4)), vec![Action::Retry { position_ms: 83_000 }]);
        // our own try loads it again: still the same episode
        g.on_loading(clock.at(4), TRACK, 1, 83_000);
        assert_eq!(g.phase(), Phase::Retrying);
        assert_eq!(g.status(clock.at(4)).refusals, 1);
    }

    #[test]
    fn success_ends_the_episode_and_the_next_one_starts_at_3_seconds() {
        let clock = Clock::new();
        let mut g = KeyGuard::new();
        g.on_loading(clock.at(0), TRACK, 1, 0);
        refuse(&mut g, clock.at(0));
        refuse(&mut g, clock.at(3));
        assert_eq!(g.on_success(), Some(2));
        assert_eq!(g.phase(), Phase::Ok);
        assert_eq!(g.deadline(), None);
        assert_eq!(g.on_success(), None);
        g.on_loading(clock.at(100), OTHER, 2, 0);
        g.on_refused(clock.at(100), OTHER, 2, true);
        assert_eq!((g.deadline().unwrap() - clock.at(100)).as_secs(), 3);
        assert_eq!(g.status(clock.at(100)).total, 3);
    }

    #[test]
    fn another_load_ends_the_wait() {
        let clock = Clock::new();
        let mut g = KeyGuard::new();
        g.on_loading(clock.at(0), TRACK, 1, 0);
        refuse(&mut g, clock.at(0));
        // the user skipped: a new request id
        g.on_loading(clock.at(1), OTHER, 2, 0);
        assert_eq!(g.phase(), Phase::Ok);
        assert_eq!(g.deadline(), None);
        // its refusal is a first refusal, again 3 s
        g.on_refused(clock.at(2), OTHER, 2, true);
        assert_eq!(g.status(clock.at(2)).refusals, 1);
        assert_eq!(g.status(clock.at(2)).retry_in_s, Some(3));
    }

    #[test]
    fn a_seek_by_the_user_starts_the_back_off_over() {
        let clock = Clock::new();
        let mut g = KeyGuard::new();
        g.on_loading(clock.at(0), TRACK, 1, 0);
        for i in 0..3 {
            refuse(&mut g, clock.at(i));
        }
        assert_eq!(g.status(clock.at(2)).refusals, 3);
        // the same request id loads again at another position, and we did not ask for it
        g.on_loading(clock.at(5), TRACK, 1, 40_000);
        assert_eq!(g.phase(), Phase::Retrying);
        assert_eq!(g.status(clock.at(5)).refusals, 0);
        // it fails: first refusal of a new back-off, at the new position
        g.on_refused(clock.at(6), TRACK, 1, true);
        assert_eq!(g.status(clock.at(6)).retry_in_s, Some(3));
        assert_eq!(g.poll(clock.at(9)), vec![Action::Retry { position_ms: 40_000 }]);
    }

    #[test]
    fn resume_tries_at_once_but_not_sooner_than_3_seconds_after_the_last_refusal() {
        let clock = Clock::new();
        let mut g = KeyGuard::new();
        g.on_loading(clock.at(0), TRACK, 1, 0);
        // a long wait is under way: 4 refusals, the next try 120 s away
        for i in 0..4 {
            refuse(&mut g, clock.at(i * 40));
        }
        assert_eq!(g.status(clock.at(120)).retry_in_s, Some(120));
        // the user presses play 50 s after the last refusal: right away
        g.on_user(clock.at(170), UserCommand::Resume);
        assert_eq!(g.status(clock.at(170)).refusals, 0);
        assert_eq!(g.poll(clock.at(170)), vec![Action::Retry { position_ms: 0 }]);
        // the user presses play 1 s after the last refusal: 2 s later
        let mut h = KeyGuard::new();
        h.on_loading(clock.at(0), TRACK, 1, 0);
        refuse(&mut h, clock.at(0));
        h.on_user(clock.at(1), UserCommand::Resume);
        assert!(h.poll(clock.at(2)).is_empty());
        assert_eq!(h.poll(clock.at(3)), vec![Action::Retry { position_ms: 0 }]);
    }

    #[test]
    fn resume_starts_the_back_off_over_after_giving_up() {
        let clock = Clock::new();
        let mut g = KeyGuard::new();
        g.on_loading(clock.at(0), TRACK, 1, 0);
        let mut now = clock.at(0);
        for _ in 0..7 {
            refuse(&mut g, now);
            if let Some(due) = g.deadline() {
                now = due;
                g.poll(now);
            }
        }
        assert_eq!(g.phase(), Phase::GaveUp);
        let later = now + Duration::from_secs(600);
        g.on_user(later, UserCommand::Resume);
        assert_eq!(g.phase(), Phase::Waiting);
        assert_eq!(g.poll(later), vec![Action::Retry { position_ms: 0 }]);
        // and the new back-off begins with 3 s again
        g.on_refused(later, TRACK, 1, true);
        assert_eq!(g.status(later).retry_in_s, Some(3));
        assert_eq!(g.status(later).refusals, 1);
    }

    #[test]
    fn pause_stops_the_tries_until_resume() {
        let clock = Clock::new();
        let mut g = KeyGuard::new();
        g.on_loading(clock.at(0), TRACK, 1, 0);
        refuse(&mut g, clock.at(0));
        g.on_user(clock.at(1), UserCommand::Pause);
        assert_eq!(g.phase(), Phase::UserPaused);
        assert_eq!(g.deadline(), None);
        assert!(g.poll(clock.at(60)).is_empty());
        g.on_user(clock.at(100), UserCommand::Resume);
        assert_eq!(g.poll(clock.at(100)), vec![Action::Retry { position_ms: 0 }]);
    }

    #[test]
    fn a_refusal_that_comes_after_the_user_paused_does_not_schedule_anything() {
        let clock = Clock::new();
        let mut g = KeyGuard::new();
        g.on_loading(clock.at(0), TRACK, 1, 0);
        refuse(&mut g, clock.at(0));
        g.poll(clock.at(3));
        assert_eq!(g.phase(), Phase::Retrying);
        g.on_user(clock.at(4), UserCommand::Pause);
        assert_eq!(g.phase(), Phase::UserPaused);
        // the try that was under way fails
        assert_eq!(refuse(&mut g, clock.at(5)), vec![Action::Pause]);
        assert_eq!(g.phase(), Phase::UserPaused);
        assert_eq!(g.deadline(), None);
    }

    #[test]
    fn a_load_that_was_meant_to_stay_paused_is_not_tried_again_by_itself() {
        let clock = Clock::new();
        let mut g = KeyGuard::new();
        g.on_loading(clock.at(0), TRACK, 1, 0);
        assert_eq!(g.on_refused(clock.at(1), TRACK, 1, false), vec![Action::Pause]);
        assert_eq!(g.phase(), Phase::UserPaused);
        assert_eq!(g.deadline(), None);
        g.on_user(clock.at(10), UserCommand::Resume);
        assert_eq!(g.poll(clock.at(10)), vec![Action::Retry { position_ms: 0 }]);
    }

    #[test]
    fn other_commands_change_nothing() {
        let clock = Clock::new();
        let mut g = KeyGuard::new();
        g.on_loading(clock.at(0), TRACK, 1, 0);
        refuse(&mut g, clock.at(0));
        let before = g.deadline();
        g.on_user(clock.at(1), UserCommand::Other);
        assert_eq!(g.deadline(), before);
        assert_eq!(g.phase(), Phase::Waiting);
        // and while nothing is wrong they change nothing either
        let mut ok = KeyGuard::new();
        ok.on_user(clock.at(1), UserCommand::Resume);
        ok.on_user(clock.at(1), UserCommand::Pause);
        assert_eq!(ok.phase(), Phase::Ok);
        assert_eq!(ok.deadline(), None);
    }

    #[test]
    fn a_try_that_never_ends_counts_as_a_refusal() {
        let clock = Clock::new();
        let mut g = KeyGuard::new();
        g.on_loading(clock.at(0), TRACK, 1, 0);
        refuse(&mut g, clock.at(0));
        g.poll(clock.at(3));
        assert_eq!(g.deadline(), Some(clock.at(3) + RETRY_TIMEOUT));
        assert!(g.poll(clock.at(100)).is_empty());
        let actions = g.poll(clock.at(3) + RETRY_TIMEOUT);
        assert_eq!(actions, vec![Action::Pause]);
        assert_eq!(g.phase(), Phase::Waiting);
        assert_eq!(g.status(clock.at(3) + RETRY_TIMEOUT).refusals, 2);
        assert_eq!((g.deadline().unwrap() - (clock.at(3) + RETRY_TIMEOUT)).as_secs(), 10);
    }

    #[test]
    fn stopped_and_reset_end_the_episode_but_keep_the_total() {
        let clock = Clock::new();
        let mut g = KeyGuard::new();
        g.on_loading(clock.at(0), TRACK, 1, 0);
        refuse(&mut g, clock.at(0));
        g.on_stopped();
        assert_eq!(g.phase(), Phase::Ok);
        g.on_loading(clock.at(1), TRACK, 3, 0);
        g.on_refused(clock.at(1), TRACK, 3, true);
        g.reset();
        assert_eq!(g.phase(), Phase::Ok);
        assert_eq!(g.status(clock.at(2)).total, 2);
        assert_eq!(g.deadline(), None);
    }

    #[test]
    fn the_delay_never_beats_the_gap_between_key_requests() {
        // every delay of the schedule is at least the minimum gap
        assert!(RETRY_DELAYS.iter().all(|d| *d >= MIN_GAP));
    }

    #[test]
    fn commands_are_recognised_by_their_dealer_names() {
        assert_eq!(UserCommand::from_name("resume"), UserCommand::Resume);
        assert_eq!(UserCommand::from_name("pause"), UserCommand::Pause);
        assert_eq!(UserCommand::from_name("skip_next"), UserCommand::Other);
        assert_eq!(UserCommand::from_name("seek_to"), UserCommand::Other);
        assert_eq!(UserCommand::from_name(""), UserCommand::Other);
    }
}

/// The spacing of key requests that `librespot-core` applies (`KeyGate` lives there, next to the code
/// that uses it; it is tested here with the rest of the key handling).
#[cfg(test)]
mod key_gate {
    use librespot_core::audio_key::{KeyGate, KeyOutcome, MIN_GAP_AFTER_ERROR};
    use std::time::{Duration, Instant};

    fn secs(n: u64) -> Duration {
        Duration::from_secs(n)
    }

    #[test]
    fn without_errors_every_request_goes_out_at_once() {
        let t0 = Instant::now();
        let mut gate = KeyGate::new();
        for i in 0..5 {
            assert_eq!(gate.reserve(t0 + secs(i)), Duration::ZERO);
        }
        gate.record(KeyOutcome::Granted, t0);
        assert_eq!(gate.reserve(t0), Duration::ZERO);
        assert_eq!(gate.errors_in_row(), 0);
    }

    #[test]
    fn after_a_refusal_requests_are_lined_up_three_seconds_apart() {
        let t0 = Instant::now();
        let mut gate = KeyGate::new();
        gate.record(KeyOutcome::Refused, t0);
        assert_eq!(MIN_GAP_AFTER_ERROR, secs(3));
        // 1 s after the error: the next request may start at 3 s
        assert_eq!(gate.reserve(t0 + secs(1)), secs(2));
        // another one that wants to start right then goes after that, at 6 s
        assert_eq!(gate.reserve(t0 + secs(1)), secs(5));
        // and a third at 9 s
        assert_eq!(gate.reserve(t0 + secs(2)), secs(7));
        // much later the line is empty: no wait, and the next one is a gap after this one
        assert_eq!(gate.reserve(t0 + secs(60)), Duration::ZERO);
        assert_eq!(gate.reserve(t0 + secs(60)), secs(3));
    }

    #[test]
    fn a_success_opens_the_gate() {
        let t0 = Instant::now();
        let mut gate = KeyGate::new();
        gate.record(KeyOutcome::Refused, t0);
        gate.record(KeyOutcome::Refused, t0 + secs(3));
        assert_eq!(gate.errors_in_row(), 2);
        assert_eq!(gate.reserve(t0 + secs(4)), secs(2));
        gate.record(KeyOutcome::Granted, t0 + secs(6));
        assert_eq!(gate.errors_in_row(), 0);
        assert_eq!(gate.reserve(t0 + secs(6)), Duration::ZERO);
        assert_eq!(gate.reserve(t0 + secs(6)), Duration::ZERO);
    }

    #[test]
    fn a_timeout_counts_like_a_refusal() {
        let t0 = Instant::now();
        let mut gate = KeyGate::new();
        gate.record(KeyOutcome::TimedOut, t0);
        assert_eq!(gate.errors_in_row(), 1);
        assert_eq!(gate.reserve(t0), secs(3));
    }

    #[test]
    fn an_error_never_pulls_a_reserved_start_earlier() {
        let t0 = Instant::now();
        let mut gate = KeyGate::new();
        gate.record(KeyOutcome::Refused, t0);
        assert_eq!(gate.reserve(t0), secs(3));
        assert_eq!(gate.reserve(t0), secs(6));
        // a request that was started earlier ends in an error at 4 s: the line behind it stays where it is
        gate.record(KeyOutcome::Refused, t0 + secs(4));
        assert_eq!(gate.reserve(t0 + secs(4)), secs(5));
    }
}
