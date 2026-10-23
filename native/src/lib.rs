//! Whole Flat Audio native core.
//!
//! librespot (Spotify Connect) behind JNI calls on `io.github.matijasalej.wholeflat.NativeBridge`:
//!
//! * `start(filesDir, cacheDir, deviceName): Boolean`: starts the engine on a dedicated thread that
//!   owns one tokio runtime, returns as soon as the queues exist.
//! * `nextEvent(timeoutMs): String?`: the next engine event as one JSON line, null on timeout.
//! * `stop()`: signals the engine to shut down and returns immediately.
//! * `version(): String`.
//! * the ring (see ring.rs): `ringState`, `lrRead` / `lrAck` / `lrRestart` for the living room and
//!   `kRead` for the kitchen's streams, `calibStart` / `calibStop` for the click track.
//!
//! The engine itself is in `engine.rs`, the frame ring in `ring.rs`, the calibration source in
//! `calib.rs`, the event queue in `events.rs`, the Connect volume in `mixer.rs`.

mod calib;
mod engine;
mod events;
mod mixer;
mod ring;

use std::cell::RefCell;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::mpsc;
use std::sync::{Arc, Mutex, Once};
use std::thread::JoinHandle;
use std::time::Duration;

use jni::objects::{JClass, JLongArray, JShortArray, JString};
use jni::sys::{jboolean, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
#[cfg(target_os = "android")]
use log::LevelFilter;
use log::{error, info};
use serde_json::json;
use tokio::sync::watch;

use crate::engine::{Config, Shared};
use crate::events::{lock, EventQueue};
use crate::ring::{FrameRing, KRead, LrRead, Snapshot, CHANNELS};

const LIBRESPOT_REV: &str = "99b6088bf3ea028f90192676ac1a337fa4b2ed12";

struct Engine {
    shared: Arc<Shared>,
    stop_tx: watch::Sender<bool>,
    /// Fires when the engine thread has finished.
    done_rx: mpsc::Receiver<()>,
}

static ENGINE: Mutex<Option<Engine>> = Mutex::new(None);
/// The `done_rx` of the engine that was stopped last; the next `start` waits for it, because two
/// live libmdns responders in one process make the old one panic on drop.
static STOPPING: Mutex<Option<mpsc::Receiver<()>>> = Mutex::new(None);
static INIT: Once = Once::new();
/// The calibration source's thread, if one ran.
static CALIB: Mutex<Option<JoinHandle<()>>> = Mutex::new(None);

thread_local! {
    /// Scratch space for the PCM copies of the ring calls, so that the audio threads do not allocate.
    static SCRATCH: RefCell<Vec<i16>> = const { RefCell::new(Vec::new()) };
}

fn init() {
    INIT.call_once(|| {
        #[cfg(target_os = "android")]
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(LevelFilter::Info)
                .with_tag("WholeFlatNative"),
        );
        std::panic::set_hook(Box::new(|info| {
            error!("panic: {info}");
        }));
    });
}

fn current_shared() -> Option<Arc<Shared>> {
    lock(&ENGINE).as_ref().map(|e| e.shared.clone())
}

fn start_engine(files_dir: String, cache_dir: String, device_name: String) -> bool {
    init();
    // Some code in the dependency tree (tempfiles, the audio cache) asks the environment for a
    // temp dir, and Android has no /tmp.
    std::env::set_var("TMPDIR", &cache_dir);
    info!(
        "start: {} device '{}' files={} cache={}",
        version_string(),
        device_name,
        files_dir,
        cache_dir
    );

    // A running or still-stopping engine has to be gone first.
    stop_engine();
    if let Some(rx) = lock(&STOPPING).take() {
        if rx.recv_timeout(Duration::from_secs(8)).is_err() {
            error!("the previous engine did not finish in time, starting anyway");
        }
    }

    let shared = Arc::new(Shared {
        ring: Arc::new(FrameRing::new()),
        events: Arc::new(EventQueue::new()),
    });
    let (stop_tx, stop_rx) = watch::channel(false);
    let (done_tx, done_rx) = mpsc::channel::<()>();
    let cfg = Config {
        files_dir: files_dir.into(),
        cache_dir: cache_dir.into(),
        device_name,
    };

    let thread_shared = shared.clone();
    let spawned = std::thread::Builder::new()
        .name("wholeflat-rt".to_string())
        .spawn(move || {
            let outcome = catch_unwind(AssertUnwindSafe(|| -> Result<Result<(), String>, String> {
                let rt = tokio::runtime::Builder::new_multi_thread()
                    .worker_threads(2)
                    .thread_name("wholeflat-tokio")
                    .enable_all()
                    .build()
                    .map_err(|e| format!("tokio runtime: {e}"))?;
                let result = rt.block_on(engine::run(cfg, thread_shared.clone(), stop_rx));
                rt.shutdown_timeout(Duration::from_millis(800));
                Ok(result)
            }));
            let (unexpected, reason) = match outcome {
                Ok(Ok(Ok(()))) => (false, "stopped".to_string()),
                Ok(Ok(Err(reason))) => (true, reason),
                Ok(Err(reason)) => (true, reason),
                Err(_) => (true, "the engine panicked".to_string()),
            };
            if unexpected {
                error!("engine exited unexpectedly: {reason}");
            } else {
                info!("engine stopped");
            }
            thread_shared.events.push(
                "engine_exit",
                json!({ "unexpected": unexpected, "reason": reason }),
            );
            let _ = done_tx.send(());
        });
    match spawned {
        Ok(_) => {
            *lock(&ENGINE) = Some(Engine {
                shared,
                stop_tx,
                done_rx,
            });
            true
        }
        Err(e) => {
            error!("cannot spawn the engine thread: {e}");
            false
        }
    }
}

fn stop_engine() {
    let engine = lock(&ENGINE).take();
    if let Some(e) = engine {
        info!("stopping the engine");
        e.shared.ring.synth_abort();
        e.shared.ring.close();
        e.shared.events.close();
        let _ = e.stop_tx.send(true);
        *lock(&STOPPING) = Some(e.done_rx);
    }
}

fn version_string() -> String {
    format!(
        "wholeflat_native {} (librespot {})",
        env!("CARGO_PKG_VERSION"),
        &LIBRESPOT_REV[..9]
    )
}

fn java_string(env: &mut JNIEnv, s: &JString) -> Option<String> {
    env.get_string(s).ok().map(|js| js.into())
}

#[no_mangle]
pub extern "system" fn Java_io_github_matijasalej_wholeflat_NativeBridge_start(
    mut env: JNIEnv,
    _class: JClass,
    files_dir: JString,
    cache_dir: JString,
    device_name: JString,
) -> jboolean {
    let (Some(files), Some(cache), Some(name)) = (
        java_string(&mut env, &files_dir),
        java_string(&mut env, &cache_dir),
        java_string(&mut env, &device_name),
    ) else {
        return JNI_FALSE;
    };
    let ok = catch_unwind(AssertUnwindSafe(|| start_engine(files, cache, name))).unwrap_or(false);
    if ok {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_matijasalej_wholeflat_NativeBridge_nextEvent(
    env: JNIEnv,
    _class: JClass,
    timeout_ms: jint,
) -> jstring {
    let timeout = Duration::from_millis(timeout_ms.max(0) as u64);
    let Some(shared) = current_shared() else {
        // Not started (or already stopped): behave like a timeout instead of spinning the caller.
        std::thread::sleep(timeout.min(Duration::from_millis(250)));
        return std::ptr::null_mut();
    };
    match shared.events.next(timeout) {
        Some(line) => match env.new_string(line) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_io_github_matijasalej_wholeflat_NativeBridge_stop(
    _env: JNIEnv,
    _class: JClass,
) {
    let _ = catch_unwind(AssertUnwindSafe(stop_engine));
}

#[no_mangle]
pub extern "system" fn Java_io_github_matijasalej_wholeflat_NativeBridge_version(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    match env.new_string(version_string()) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

// ---- the ring ------------------------------------------------------------------------------------

/// Layout of the `LongArray` the ring calls fill in (at least [`META_LEN`] long).
///
/// ```text
///  0 epoch          3 flags: bit0 start pending, bit1 sink running, bit2 synthetic, bit3 closed, bit4 playing
///  1 epoch start    4 write index    5 living-room position    6 first valid index
///  2 epoch kind (0 none, 1 flush, 2 resume, 3 synthetic, 4 restart)
///  7 flushes  8 resumes  9 pauses  10 restarts
/// 11 first frame index of the data just read (lrRead)
/// ```
const META_LEN: usize = 12;

fn fill_meta(env: &JNIEnv, meta: &JLongArray, s: &Snapshot, first: u64) {
    let flags = (s.start_pending as i64)
        | ((s.sink_running as i64) << 1)
        | ((s.synthetic as i64) << 2)
        | ((s.closed as i64) << 3)
        | ((s.playing() as i64) << 4);
    let values: [jlong; META_LEN] = [
        s.epoch as jlong,
        s.epoch_start as jlong,
        s.kind as jlong,
        flags,
        s.write_idx as jlong,
        s.lr_pos as jlong,
        s.valid_start as jlong,
        s.flushes as jlong,
        s.resumes as jlong,
        s.pauses as jlong,
        s.restarts as jlong,
        first as jlong,
    ];
    let len = env.get_array_length(meta).unwrap_or(0) as usize;
    let n = len.min(META_LEN);
    let _ = env.set_long_array_region(meta, 0, &values[..n]);
}

fn ring_of_engine() -> Option<Arc<FrameRing>> {
    current_shared().map(|s| s.ring.clone())
}

/// The ring's state, for polling while no frames are being read. -1 when no engine runs.
#[no_mangle]
pub extern "system" fn Java_io_github_matijasalej_wholeflat_NativeBridge_ringState(
    env: JNIEnv,
    _class: JClass,
    meta: JLongArray,
) -> jint {
    let Some(ring) = ring_of_engine() else {
        return -1;
    };
    let snapshot = ring.snapshot();
    fill_meta(&env, &meta, &snapshot, 0);
    0
}

/// The living room's read. Returns the number of frames written to `buf` (interleaved stereo, at most
/// `maxFrames`), 0 on timeout, -1 closed, -2 nothing is playing, -3 a start event waits for `lrAck`.
/// `heardHint` is the ring frame heard about now (negative: unknown). `meta` gets the ring state.
#[no_mangle]
pub extern "system" fn Java_io_github_matijasalej_wholeflat_NativeBridge_lrRead(
    env: JNIEnv,
    _class: JClass,
    buf: JShortArray,
    max_frames: jint,
    timeout_ms: jint,
    heard_hint: jlong,
    meta: JLongArray,
) -> jint {
    let Some(ring) = ring_of_engine() else {
        return -1;
    };
    let capacity = env.get_array_length(&buf).unwrap_or(0) as usize / CHANNELS;
    let max = (max_frames.max(0) as usize).min(capacity);
    let hint = if heard_hint >= 0 { Some(heard_hint as u64) } else { None };
    let timeout = Duration::from_millis(timeout_ms.max(0) as u64);
    let (code, first) = SCRATCH.with(|cell| {
        let mut scratch = cell.borrow_mut();
        if scratch.len() < max * CHANNELS {
            scratch.resize(max * CHANNELS, 0);
        }
        match ring.lr_read(&mut scratch, max, hint, timeout) {
            LrRead::Data { first, frames, .. } => {
                let ok = env
                    .set_short_array_region(&buf, 0, &scratch[..frames * CHANNELS])
                    .is_ok();
                (if ok { frames as jint } else { 0 }, first)
            }
            LrRead::Timeout => (0, 0),
            LrRead::Closed => (-1, 0),
            LrRead::Paused => (-2, 0),
            LrRead::StartPending { .. } => (-3, 0),
        }
    });
    fill_meta(&env, &meta, &ring.snapshot(), first);
    code
}

/// The start event of `epoch` has been scheduled: frames may be read from now on.
#[no_mangle]
pub extern "system" fn Java_io_github_matijasalej_wholeflat_NativeBridge_lrAck(
    _env: JNIEnv,
    _class: JClass,
    epoch: jlong,
) {
    if let Some(ring) = ring_of_engine() {
        ring.lr_ack(epoch as u64);
    }
}

/// The living room lost its output: start again from `heardHint` (a start event).
#[no_mangle]
pub extern "system" fn Java_io_github_matijasalej_wholeflat_NativeBridge_lrRestart(
    _env: JNIEnv,
    _class: JClass,
    heard_hint: jlong,
) {
    if let Some(ring) = ring_of_engine() {
        ring.lr_restart(if heard_hint >= 0 { Some(heard_hint as u64) } else { None });
    }
}

/// The kitchen's read: up to `maxFrames` frames at the absolute index `cursor` (not consumed) for a
/// session of `epoch`. Returns the frames written, 0 on timeout, -1 closed, -2 lapped (the first valid
/// index is in `meta[6]`), -3 the epoch changed (the new one is in `meta[0]`).
#[no_mangle]
pub extern "system" fn Java_io_github_matijasalej_wholeflat_NativeBridge_kRead(
    env: JNIEnv,
    _class: JClass,
    cursor: jlong,
    epoch: jlong,
    buf: JShortArray,
    max_frames: jint,
    timeout_ms: jint,
    meta: JLongArray,
) -> jint {
    let Some(ring) = ring_of_engine() else {
        return -1;
    };
    let capacity = env.get_array_length(&buf).unwrap_or(0) as usize / CHANNELS;
    let max = (max_frames.max(0) as usize).min(capacity);
    let timeout = Duration::from_millis(timeout_ms.max(0) as u64);
    let code = SCRATCH.with(|cell| {
        let mut scratch = cell.borrow_mut();
        if scratch.len() < max * CHANNELS {
            scratch.resize(max * CHANNELS, 0);
        }
        match ring.k_read(cursor.max(0) as u64, epoch.max(0) as u64, &mut scratch, max, timeout) {
            KRead::Data { frames } => {
                let ok = env
                    .set_short_array_region(&buf, 0, &scratch[..frames * CHANNELS])
                    .is_ok();
                if ok {
                    frames as jint
                } else {
                    0
                }
            }
            KRead::Timeout => 0,
            KRead::Closed => -1,
            KRead::Lapped { .. } => -2,
            KRead::EpochChanged { .. } => -3,
        }
    });
    if code <= 0 {
        fill_meta(&env, &meta, &ring.snapshot(), 0);
    }
    code
}

/// Starts the calibration click track (`seconds` of it) as a start event through the ring.
#[no_mangle]
pub extern "system" fn Java_io_github_matijasalej_wholeflat_NativeBridge_calibStart(
    _env: JNIEnv,
    _class: JClass,
    seconds: jint,
) -> jboolean {
    let Some(ring) = ring_of_engine() else {
        return JNI_FALSE;
    };
    let mut slot = lock(&CALIB);
    if let Some(old) = slot.take() {
        ring.synth_abort();
        let _ = old.join();
    }
    *slot = Some(calib::spawn(ring, seconds.clamp(1, 3600) as u32));
    JNI_TRUE
}

/// Aborts the calibration click track.
#[no_mangle]
pub extern "system" fn Java_io_github_matijasalej_wholeflat_NativeBridge_calibStop(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Some(ring) = ring_of_engine() {
        ring.synth_abort();
    }
}
