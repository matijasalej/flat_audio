//! Whole Flat Audio native core.
//!
//! librespot (Spotify Connect) behind five JNI calls on `io.github.matijasalej.wholeflat.NativeBridge`:
//!
//! * `start(filesDir, cacheDir, deviceName): Boolean`: starts the engine on a dedicated thread that
//!   owns one tokio runtime, returns as soon as the queues exist.
//! * `readPcm(buf: ShortArray, timeoutMs): Int`: samples written (interleaved stereo, 44.1 kHz,
//!   16 bit), 0 on timeout, -1 once stopped.
//! * `nextEvent(timeoutMs): String?`: the next event as one JSON line, null on timeout.
//! * `stop()`: signals the engine to shut down and returns immediately.
//! * `version(): String`.
//!
//! The engine itself is in `engine.rs`, the PCM hand-over in `ring.rs`, the event queue in
//! `events.rs`.

mod engine;
mod events;
mod ring;

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::mpsc;
use std::sync::{Arc, Mutex, Once};
use std::time::Duration;

use jni::objects::{JClass, JShortArray, JString};
use jni::sys::{jboolean, jint, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
#[cfg(target_os = "android")]
use log::LevelFilter;
use log::{error, info};
use serde_json::json;
use tokio::sync::watch;

use crate::engine::{Config, Shared};
use crate::events::{lock, EventQueue};
use crate::ring::{ReadResult, Ring, CAPACITY_SAMPLES};

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
        ring: Arc::new(Ring::new(CAPACITY_SAMPLES)),
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
pub extern "system" fn Java_io_github_matijasalej_wholeflat_NativeBridge_readPcm(
    env: JNIEnv,
    _class: JClass,
    buf: JShortArray,
    timeout_ms: jint,
) -> jint {
    let Some(shared) = current_shared() else {
        return -1;
    };
    let len = match env.get_array_length(&buf) {
        Ok(n) if n > 0 => n as usize,
        _ => return 0,
    };
    let mut tmp = vec![0i16; len];
    let timeout = Duration::from_millis(timeout_ms.max(0) as u64);
    match shared.ring.read_timeout(&mut tmp, timeout) {
        ReadResult::Data(n) => {
            if env.set_short_array_region(&buf, 0, &tmp[..n]).is_err() {
                return 0;
            }
            n as jint
        }
        ReadResult::Timeout => 0,
        ReadResult::Closed => -1,
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
