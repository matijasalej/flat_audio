//! The Connect volume, stored and not applied.
//!
//! librespot normally multiplies the decoded audio by the volume (the "soft volume"). Here the soft
//! volume is always 1.0 (the trait's default `NoOpVolume`), so the living room gets the full signal;
//! what the phone's volume slider does is up to the Kotlin side (the Sonos volume, and the living
//! room only when "living room fixed volume" is off). The value reaches Kotlin as the player's
//! `VolumeChanged` event, which Spirc emits for every change including the initial one.

use std::sync::atomic::{AtomicU16, Ordering};

use librespot_core::Error;
use librespot_playback::mixer::{Mixer, MixerConfig};

pub struct ForwardMixer {
    volume: AtomicU16,
}

impl Mixer for ForwardMixer {
    fn open(_config: MixerConfig) -> Result<Self, Error> {
        Ok(ForwardMixer {
            volume: AtomicU16::new(u16::MAX / 2),
        })
    }

    fn volume(&self) -> u16 {
        self.volume.load(Ordering::Relaxed)
    }

    fn set_volume(&self, volume: u16) {
        self.volume.store(volume, Ordering::Relaxed);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_stores_the_volume_and_leaves_the_audio_alone() {
        let mixer = <ForwardMixer as Mixer>::open(MixerConfig::default()).unwrap();
        assert_eq!(mixer.volume(), u16::MAX / 2);
        mixer.set_volume(1234);
        assert_eq!(mixer.volume(), 1234);
        // the soft volume handed to the player never attenuates
        assert_eq!(mixer.get_soft_volume().attenuation_factor(), 1.0);
    }
}
