//! wholeflat: a tap on the commands that Spotify controllers (the phone) send to this device.
//!
//! The app needs to tell a command of the user ("resume", "pause", ...) from what its own code does
//! with the player. The dealer is where the user's commands arrive and nothing else comes through it,
//! so this is where they are counted: every command received on `hm://connect-state/v1/player/command`
//! is sent, by name ("resume", "pause", "skip_next", "seek_to", ...), to the channel set with
//! [`set_sink`].

use std::sync::Mutex;

use tokio::sync::mpsc::UnboundedSender;

use crate::dealer::protocol::Command;

static SINK: Mutex<Option<UnboundedSender<String>>> = Mutex::new(None);

/// Sends the name of every controller command to `tx`. `None` stops it.
pub fn set_sink(tx: Option<UnboundedSender<String>>) {
    *SINK.lock().unwrap_or_else(|p| p.into_inner()) = tx;
}

/// The name of a command as the dealer calls it ("resume", "skip_next", ...).
pub fn name_of(command: &Command) -> String {
    let text = command.to_string();
    text.strip_prefix("endpoint: ").unwrap_or(&text).to_string()
}

pub(crate) fn note(command: &Command) {
    if let Some(tx) = SINK.lock().unwrap_or_else(|p| p.into_inner()).as_ref() {
        let _ = tx.send(name_of(command));
    }
}
