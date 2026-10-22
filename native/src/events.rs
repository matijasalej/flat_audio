//! Event queue between the native engine and the Kotlin service.
//!
//! Every event is one JSON object (a single line) with at least `"t"` (the type) and `"ts"` (epoch
//! milliseconds). Kotlin drains the queue with `NativeBridge.nextEvent`. The queue is bounded: when
//! nobody drains it the oldest entries fall off, so a stuck consumer can never grow the heap.

use std::collections::VecDeque;
use std::sync::{Condvar, Mutex, MutexGuard};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use serde_json::{json, Map, Value};

const MAX_QUEUED: usize = 2000;

pub fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

struct Inner {
    queue: VecDeque<String>,
    closed: bool,
}

pub struct EventQueue {
    inner: Mutex<Inner>,
    cv: Condvar,
}

impl EventQueue {
    pub fn new() -> Self {
        EventQueue {
            inner: Mutex::new(Inner {
                queue: VecDeque::new(),
                closed: false,
            }),
            cv: Condvar::new(),
        }
    }

    /// Queues an already serialised event.
    pub fn push_raw(&self, line: String) {
        let mut g = lock(&self.inner);
        if g.closed {
            return;
        }
        if g.queue.len() >= MAX_QUEUED {
            g.queue.pop_front();
        }
        g.queue.push_back(line);
        drop(g);
        self.cv.notify_one();
    }

    /// Queues an event of type `t` with the given extra fields (`fields` should be a JSON object).
    pub fn push(&self, t: &str, fields: Value) {
        let mut map: Map<String, Value> = match fields {
            Value::Object(m) => m,
            Value::Null => Map::new(),
            other => {
                let mut m = Map::new();
                m.insert("value".to_string(), other);
                m
            }
        };
        map.insert("t".to_string(), json!(t));
        map.insert("ts".to_string(), json!(now_ms()));
        self.push_raw(Value::Object(map).to_string());
    }

    /// Waits up to `timeout` for the next event. `None` on timeout, or once the queue is closed and
    /// drained.
    pub fn next(&self, timeout: Duration) -> Option<String> {
        let deadline = Instant::now() + timeout;
        let mut g = lock(&self.inner);
        loop {
            if let Some(line) = g.queue.pop_front() {
                return Some(line);
            }
            if g.closed {
                return None;
            }
            let now = Instant::now();
            if now >= deadline {
                return None;
            }
            let (ng, _timed_out) = self
                .cv
                .wait_timeout(g, deadline - now)
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            g = ng;
        }
    }

    /// Wakes every waiter; later `push` calls are dropped.
    pub fn close(&self) {
        lock(&self.inner).closed = true;
        self.cv.notify_all();
    }
}

/// Keeps the first two characters of an account name and hides the rest, so the debug page that
/// listens on the LAN does not hand out the full Spotify user name.
pub fn mask(name: &str) -> String {
    let head: String = name.chars().take(2).collect();
    if name.chars().count() <= 2 {
        "**".to_string()
    } else {
        format!("{head}***")
    }
}
