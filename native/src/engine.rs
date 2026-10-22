//! The Spotify Connect engine: zeroconf discovery, session, player and the reconnect loop.
//!
//! Shape (after librespot's own `main.rs`, with the `exit(1)` paths replaced by retries):
//!
//! * One `Player` and one `SoftMixer` live for the whole run. Sessions come and go; the player is
//!   pointed at the current one with `Player::set_session`.
//! * Zeroconf discovery runs the whole time, including while a session is up, so a phone can always
//!   (re-)pair, even after the session was lost. Losing the session hides the device from the
//!   Spotify device list (librespot #1700), so a lost session is rebuilt from the cached reusable
//!   credentials with a growing delay, for as long as the engine runs.
//! * Credentials that arrive from a phone win over the cached ones until one connect succeeded.
//! * Nothing here ends the process: errors become events for the Kotlin side and retries.

use std::future::Future;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::pin::Pin;
use std::sync::Arc;
use std::time::Duration;

use futures_util::StreamExt;
use librespot_connect::{ConnectConfig, Spirc};
use librespot_core::authentication::Credentials;
use librespot_core::cache::Cache;
use librespot_core::config::DeviceType;
use librespot_core::error::ErrorKind;
use librespot_core::{Error, Session, SessionConfig};
use librespot_discovery::Discovery;
use librespot_playback::audio_backend::Sink;
use librespot_playback::config::{Bitrate, PlayerConfig};
use librespot_playback::mixer::softmixer::SoftMixer;
use librespot_playback::mixer::{Mixer, MixerConfig};
use librespot_playback::player::{Player, PlayerEvent};
use log::{info, warn};
use serde_json::{json, Value};
use tokio::sync::watch;
use tokio::time::{interval, sleep_until, timeout, Instant};

use crate::events::{mask, EventQueue};
use crate::ring::{Ring, RingSink};

/// The desktop (keymaster) client id. A receiver replays the credential a controller hands over,
/// and Spotify only honours it under the id that issued it; the Android default makes desktop
/// controllers hang on "Connecting...". Used for the zeroconf advertisement and the session. The
/// vendored librespot-core uses the session's id for the client token as well.
pub const CLIENT_ID: &str = "65b708073fc0480ea92a077233ca87bd";

/// Size limit of the audio cache in the app cache dir.
const AUDIO_CACHE_LIMIT: u64 = 256 * 1024 * 1024;
const DISCOVERY_RETRY: Duration = Duration::from_secs(5);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(40);
const GOODBYE_GRACE: Duration = Duration::from_millis(1500);

type SpircTask = Pin<Box<dyn Future<Output = ()>>>;

pub struct Config {
    pub files_dir: PathBuf,
    pub cache_dir: PathBuf,
    pub device_name: String,
}

/// What the JNI side and the engine thread share.
pub struct Shared {
    pub ring: Arc<Ring>,
    pub events: Arc<EventQueue>,
}

struct Backoff {
    next_secs: u64,
}

impl Backoff {
    fn new() -> Self {
        Backoff { next_secs: 1 }
    }

    fn reset(&mut self) {
        self.next_secs = 1;
    }

    /// 1 s, 2 s, 4 s ... capped at one minute.
    fn next_delay(&mut self) -> Duration {
        let d = Duration::from_secs(self.next_secs);
        self.next_secs = (self.next_secs * 2).min(60);
        d
    }
}

async fn next_credentials(discovery: &mut Option<Discovery>) -> Option<Credentials> {
    match discovery.as_mut() {
        Some(d) => d.next().await,
        None => std::future::pending().await,
    }
}

async fn poll_task(task: &mut Option<SpircTask>) {
    match task.as_mut() {
        Some(t) => t.await,
        None => std::future::pending().await,
    }
}

async fn sleep_opt(deadline: Option<Instant>) {
    match deadline {
        Some(t) => sleep_until(t).await,
        None => std::future::pending::<()>().await,
    }
}

fn start_discovery(name: &str, device_id: &str) -> Result<Discovery, Error> {
    Discovery::builder(device_id.to_string(), CLIENT_ID.to_string())
        .name(name.to_string())
        .device_type(DeviceType::Speaker)
        .is_group(false)
        .port(0)
        .launch()
}

/// Stops the Connect loop of the current session: asks Spirc to say goodbye, gives it a moment,
/// then invalidates the session.
async fn teardown(spirc: &mut Option<Spirc>, task: &mut Option<SpircTask>, session: &Session) {
    if let Some(s) = spirc.take() {
        if let Err(e) = s.shutdown() {
            warn!("spirc shutdown request failed: {e}");
        }
    }
    if let Some(t) = task.take() {
        let _ = timeout(GOODBYE_GRACE, t).await;
    }
    if !session.is_invalid() {
        session.shutdown();
    }
}

fn is_permanent(e: &Error) -> bool {
    // A refused login (bad or revoked credentials, Premium required, ...) will not get better by
    // asking again with the same credentials.
    matches!(e.kind, ErrorKind::PermissionDenied | ErrorKind::Unauthenticated)
}

/// Runs until `stop_rx` fires (`Ok`) or something unrecoverable happens (`Err` with a reason).
pub async fn run(
    cfg: Config,
    shared: Arc<Shared>,
    mut stop_rx: watch::Receiver<bool>,
) -> Result<(), String> {
    let events = shared.events.clone();

    // Credentials and volume live in filesDir (they must survive), the audio cache in cacheDir.
    let state_dir = cfg.files_dir.join("librespot");
    let audio_dir = cfg.cache_dir.join("audio");
    let cache = match Cache::new(
        Some(state_dir.clone()),
        Some(state_dir.clone()),
        Some(audio_dir),
        Some(AUDIO_CACHE_LIMIT),
    ) {
        Ok(c) => Some(c),
        Err(e) => {
            warn!("cache with audio dir unavailable ({e}), trying credentials and volume only");
            events.push(
                "error",
                json!({ "where": "cache", "message": e.to_string() }),
            );
            Cache::new(
                Some(state_dir.clone()),
                Some(state_dir.clone()),
                None::<PathBuf>,
                None,
            )
            .ok()
        }
    };
    if cache.is_none() {
        events.push(
            "error",
            json!({ "where": "cache", "message": "no cache, credentials will not survive a restart" }),
        );
    }

    let device_id = load_or_create_device_id(&state_dir);
    info!(
        "device '{}' id {}.. client id {}, cached credentials: {}",
        cfg.device_name,
        &device_id[..8],
        CLIENT_ID,
        cache.as_ref().map_or(false, |c| c.credentials().is_some())
    );
    let default_volume = u16::MAX / 2;

    let session_config = SessionConfig {
        client_id: CLIENT_ID.to_string(),
        device_id: device_id.clone(),
        tmp_dir: cfg.cache_dir.clone(),
        ..SessionConfig::default()
    };
    let mut session = Session::new(session_config.clone(), cache.clone());

    let mixer = match <SoftMixer as Mixer>::open(MixerConfig::default()) {
        Ok(m) => Arc::new(m),
        Err(e) => return Err(format!("mixer: {e}")),
    };
    let mixer_dyn: Arc<dyn Mixer> = mixer.clone();

    let player_config = PlayerConfig {
        bitrate: Bitrate::Bitrate320,
        ..PlayerConfig::default()
    };
    let sink_ring = shared.ring.clone();
    let sink_events = shared.events.clone();
    let player = Player::new(
        player_config,
        session.clone(),
        mixer.get_soft_volume(),
        move || -> Box<dyn Sink> { Box::new(RingSink::new(sink_ring, sink_events)) },
    );

    // Every PlayerEvent becomes one JSON event.
    let forward_events = events.clone();
    let mut player_rx = player.get_player_event_channel();
    let forwarder = tokio::spawn(async move {
        while let Some(ev) = player_rx.recv().await {
            let (t, fields) = describe_event(&ev);
            forward_events.push(t, fields);
        }
    });

    events.push(
        "engine_started",
        json!({ "device_name": cfg.device_name, "bitrate": 320 }),
    );

    let mut discovery: Option<Discovery> = None;
    let mut discovery_due: Option<Instant> = Some(Instant::now());
    let mut discovery_failures: u32 = 0;
    let mut spirc: Option<Spirc> = None;
    let mut spirc_task: Option<SpircTask> = None;
    // Credentials from a phone, used until one connect with them succeeded.
    let mut pending_creds: Option<Credentials> = None;
    let mut connect_due: Option<Instant> = if cache.as_ref().map_or(false, |c| c.credentials().is_some()) {
        // Cached credentials: connect right away, discovery keeps running next to it.
        Some(Instant::now())
    } else {
        None
    };
    let mut backoff = Backoff::new();
    let mut ticker = interval(Duration::from_secs(2));

    let exit: Result<(), String> = loop {
        tokio::select! {
            biased;

            _ = stop_rx.changed() => {
                break Ok(());
            }

            _ = ticker.tick() => {
                if player.is_invalid() {
                    break Err("the player thread ended".to_string());
                }
            }

            creds = next_credentials(&mut discovery) => {
                match creds {
                    Some(creds) => {
                        info!("credentials received from a Spotify client");
                        events.push(
                            "credentials_received",
                            json!({ "user": creds.username.as_deref().map(mask) }),
                        );
                        pending_creds = Some(creds);
                        // A new (or re-pairing) controller takes over: drop the current session first.
                        teardown(&mut spirc, &mut spirc_task, &session).await;
                        backoff.reset();
                        connect_due = Some(Instant::now());
                    }
                    None => {
                        warn!("zeroconf discovery stopped, restarting it");
                        events.push(
                            "error",
                            json!({ "where": "discovery", "message": "discovery stopped, restarting" }),
                        );
                        if let Some(d) = discovery.take() {
                            let _ = timeout(GOODBYE_GRACE, d.shutdown()).await;
                        }
                        discovery_due = Some(Instant::now() + DISCOVERY_RETRY);
                    }
                }
            }

            _ = poll_task(&mut spirc_task) => {
                // The Connect loop ended on its own: the session went invalid (network loss,
                // remote logout). The device is gone from the Spotify list until we reconnect.
                warn!("Spirc ended, the session was lost");
                spirc_task = None;
                spirc = None;
                events.push("session_lost", json!({}));
                if !session.is_invalid() {
                    session.shutdown();
                }
                connect_due = Some(Instant::now() + backoff.next_delay());
            }

            _ = sleep_opt(connect_due) => {
                connect_due = None;
                let creds = pending_creds
                    .clone()
                    .or_else(|| cache.as_ref().and_then(|c| c.credentials()));
                let Some(creds) = creds else {
                    continue;
                };
                if session.is_invalid() {
                    session = Session::new(session_config.clone(), cache.clone());
                    player.set_session(session.clone());
                }
                let connect_config = ConnectConfig {
                    name: cfg.device_name.clone(),
                    device_type: DeviceType::Speaker,
                    is_group: false,
                    initial_volume: cache
                        .as_ref()
                        .and_then(|c| c.volume())
                        .unwrap_or(default_volume),
                    disable_volume: false,
                    volume_steps: 64,
                    ..ConnectConfig::default()
                };
                info!("connecting to Spotify");
                let attempt = Spirc::new(
                    connect_config,
                    session.clone(),
                    creds,
                    player.clone(),
                    mixer_dyn.clone(),
                );
                let outcome = tokio::select! {
                    r = timeout(CONNECT_TIMEOUT, attempt) => Some(r),
                    _ = stop_rx.changed() => None,
                };
                match outcome {
                    None => {
                        break Ok(());
                    }
                    Some(Ok(Ok((s, task)))) => {
                        info!("Spotify Connect session is up");
                        let boxed: SpircTask = Box::pin(task);
                        spirc = Some(s);
                        spirc_task = Some(boxed);
                        pending_creds = None;
                        backoff.reset();
                        events.push(
                            "session_connected",
                            json!({ "user": mask(&session.username()) }),
                        );
                    }
                    Some(Ok(Err(e))) => {
                        warn!("connect failed: {e}");
                        if !session.is_invalid() {
                            session.shutdown();
                        }
                        if is_permanent(&e) {
                            events.push(
                                "error",
                                json!({
                                    "where": "connect",
                                    "message": format!("Spotify refused the login ({e}). Select Whole Flat in Spotify again."),
                                    "permanent": true,
                                }),
                            );
                            pending_creds = None;
                        } else {
                            let delay = backoff.next_delay();
                            events.push(
                                "error",
                                json!({
                                    "where": "connect",
                                    "message": e.to_string(),
                                    "retry_in_s": delay.as_secs(),
                                }),
                            );
                            connect_due = Some(Instant::now() + delay);
                        }
                    }
                    Some(Err(_elapsed)) => {
                        warn!("connect timed out");
                        if !session.is_invalid() {
                            session.shutdown();
                        }
                        let delay = backoff.next_delay();
                        events.push(
                            "error",
                            json!({
                                "where": "connect",
                                "message": "connect timed out",
                                "retry_in_s": delay.as_secs(),
                            }),
                        );
                        connect_due = Some(Instant::now() + delay);
                    }
                }
            }

            _ = sleep_opt(discovery_due) => {
                discovery_due = None;
                match start_discovery(&cfg.device_name, &device_id) {
                    Ok(d) => {
                        info!("zeroconf discovery is up as '{}'", cfg.device_name);
                        events.push("discovery_started", json!({ "name": cfg.device_name }));
                        discovery_failures = 0;
                        discovery = Some(d);
                    }
                    Err(e) => {
                        // Typically no network yet right after boot: keep trying.
                        discovery_failures += 1;
                        warn!("zeroconf discovery failed to start: {e}");
                        if discovery_failures == 1 || discovery_failures % 12 == 0 {
                            events.push(
                                "error",
                                json!({
                                    "where": "discovery",
                                    "message": e.to_string(),
                                    "attempts": discovery_failures,
                                }),
                            );
                        }
                        discovery_due = Some(Instant::now() + DISCOVERY_RETRY);
                    }
                }
            }
        }
    };

    // Shutdown. The ring goes first: it releases a player thread that waits for room in it.
    shared.ring.close();
    teardown(&mut spirc, &mut spirc_task, &session).await;
    if let Some(d) = discovery.take() {
        // Dropping the mDNS responder without this makes libmdns panic.
        let _ = timeout(GOODBYE_GRACE, d.shutdown()).await;
    }
    forwarder.abort();
    // Dropping the last Player handle joins its thread, so do it off the runtime and bounded.
    let joiner = tokio::task::spawn_blocking(move || drop(player));
    let _ = timeout(Duration::from_secs(3), joiner).await;
    exit
}

fn load_or_create_device_id(dir: &Path) -> String {
    let path = dir.join("device_id");
    if let Ok(s) = std::fs::read_to_string(&path) {
        let s = s.trim();
        if s.len() == 40 && s.bytes().all(|b| b.is_ascii_hexdigit()) {
            return s.to_ascii_lowercase();
        }
    }
    let id = random_hex_40();
    let _ = std::fs::create_dir_all(dir);
    if let Err(e) = std::fs::write(&path, &id) {
        warn!("cannot persist the device id: {e}");
    }
    id
}

fn random_hex_40() -> String {
    let mut bytes = [0u8; 20];
    let from_urandom = std::fs::File::open("/dev/urandom")
        .and_then(|mut f| f.read_exact(&mut bytes))
        .is_ok();
    if !from_urandom {
        // /dev/urandom exists on every Android; this is only a last resort.
        use std::hash::{BuildHasher, Hasher};
        let mut h = std::collections::hash_map::RandomState::new().build_hasher();
        h.write_u128(
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_nanos())
                .unwrap_or(0),
        );
        for chunk in bytes.chunks_mut(8) {
            h.write_u8(1);
            let v = h.finish().to_le_bytes();
            chunk.copy_from_slice(&v[..chunk.len()]);
        }
    }
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// One JSON event per `PlayerEvent`, with the track id and the position wherever there is one.
fn describe_event(ev: &PlayerEvent) -> (&'static str, Value) {
    use PlayerEvent::*;
    match ev {
        PlayRequestIdChanged { play_request_id } => (
            "play_request_id_changed",
            json!({ "play_request_id": play_request_id }),
        ),
        Stopped { play_request_id, track_id } => (
            "stopped",
            json!({ "play_request_id": play_request_id, "track_id": track_id.to_uri() }),
        ),
        Loading { play_request_id, track_id, position_ms } => (
            "loading",
            json!({
                "play_request_id": play_request_id,
                "track_id": track_id.to_uri(),
                "position_ms": position_ms,
            }),
        ),
        Preloading { track_id } => ("preloading", json!({ "track_id": track_id.to_uri() })),
        Playing { play_request_id, track_id, position_ms } => (
            "playing",
            json!({
                "play_request_id": play_request_id,
                "track_id": track_id.to_uri(),
                "position_ms": position_ms,
            }),
        ),
        Paused { play_request_id, track_id, position_ms } => (
            "paused",
            json!({
                "play_request_id": play_request_id,
                "track_id": track_id.to_uri(),
                "position_ms": position_ms,
            }),
        ),
        TimeToPreloadNextTrack { play_request_id, track_id } => (
            "time_to_preload_next_track",
            json!({ "play_request_id": play_request_id, "track_id": track_id.to_uri() }),
        ),
        EndOfTrack { play_request_id, track_id } => (
            "end_of_track",
            json!({ "play_request_id": play_request_id, "track_id": track_id.to_uri() }),
        ),
        Unavailable { play_request_id, track_id } => (
            "unavailable",
            json!({ "play_request_id": play_request_id, "track_id": track_id.to_uri() }),
        ),
        VolumeChanged { volume } => (
            "volume_changed",
            json!({ "volume": volume, "percent": (u32::from(*volume) * 100 + 32767) / 65535 }),
        ),
        PositionCorrection { play_request_id, track_id, position_ms } => (
            "position_correction",
            json!({
                "play_request_id": play_request_id,
                "track_id": track_id.to_uri(),
                "position_ms": position_ms,
            }),
        ),
        PositionChanged { play_request_id, track_id, position_ms } => (
            "position_changed",
            json!({
                "play_request_id": play_request_id,
                "track_id": track_id.to_uri(),
                "position_ms": position_ms,
            }),
        ),
        Seeked { play_request_id, track_id, position_ms } => (
            "seeked",
            json!({
                "play_request_id": play_request_id,
                "track_id": track_id.to_uri(),
                "position_ms": position_ms,
            }),
        ),
        TrackChanged { audio_item } => {
            let cover = audio_item
                .covers
                .iter()
                .max_by_key(|c| c.width)
                .map(|c| c.url.clone());
            (
                "track_changed",
                json!({
                    "track_id": audio_item.track_id.to_uri(),
                    "uri": audio_item.uri,
                    "title": audio_item.name,
                    "artists": audio_item.unique_fields.artists_display(),
                    "duration_ms": audio_item.duration_ms,
                    "explicit": audio_item.is_explicit,
                    "cover_url": cover,
                }),
            )
        }
        SessionConnected { connection_id, user_name } => (
            "player_session_connected",
            json!({ "connection_id": connection_id, "user": mask(user_name) }),
        ),
        SessionDisconnected { connection_id, user_name } => (
            "player_session_disconnected",
            json!({ "connection_id": connection_id, "user": mask(user_name) }),
        ),
        SessionClientChanged { client_id, client_name, client_brand_name, client_model_name } => (
            "session_client_changed",
            json!({
                "client_id": client_id,
                "client_name": client_name,
                "client_brand_name": client_brand_name,
                "client_model_name": client_model_name,
            }),
        ),
        ShuffleChanged { shuffle } => ("shuffle_changed", json!({ "shuffle": shuffle })),
        RepeatChanged { context, track } => (
            "repeat_changed",
            json!({ "context": context, "track": track }),
        ),
        AutoPlayChanged { auto_play } => ("auto_play_changed", json!({ "auto_play": auto_play })),
        FilterExplicitContentChanged { filter } => (
            "filter_explicit_content_changed",
            json!({ "filter": filter }),
        ),
        SetQueue { context_uri, current_track, next_tracks, prev_tracks } => (
            "set_queue",
            json!({
                "context_uri": context_uri,
                "has_current": current_track.is_some(),
                "next": next_tracks.len(),
                "prev": prev_tracks.len(),
            }),
        ),
        #[allow(unreachable_patterns)]
        other => ("player_event", json!({ "debug": format!("{other:?}") })),
    }
}
