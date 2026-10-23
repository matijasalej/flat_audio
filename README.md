# Whole Flat Audio

One Spotify speaker for the whole flat: an Android TV app that plays Spotify in sync in the living room and the kitchen.

**What it is:** an app for the Chromecast with Google TV that shows up in Spotify as a single speaker, "Whole Flat". It plays the same music in the living room and on the kitchen Sonos at the same time, in sync, with no extra hardware.

**How it works**
- **Spotify → Google TV:** Spotify Connect at the highest quality (320 kbps), via librespot. You pick "Whole Flat" in the Spotify app on your phone.
- **Living room:** Google TV → HDMI → Philips TV, with the screen switched off over the network (JointSpace) → optical → amplifier → speakers.
- **Kitchen:** the app streams the same audio losslessly (FLAC) to a Sonos speaker over the home network (UPnP).
- **Sync:** both rooms start together. The app tracks the Sonos's playback position and corrects clock drift continuously, with a target of ±10 ms between the rooms.

**Features**
- Pause, skip and seek act in both rooms at once.
- The kitchen joins by itself when the Sonos wakes up or returns from Bluetooth, and the living room never stops.
- The living room stays at full volume, set on the amp, and the phone volume controls the kitchen. This is a setting.
- When music starts, the TV is woken if it is in standby (best effort) and turns its screen off, but not while you're watching video.
- It starts with the Google TV, reconnects to Spotify by itself and recovers after sleep.
- **Remote-friendly settings:** kitchen on/off, sync offset, kitchen balance and TV options.
- **Status page and calibration:** a status page in the browser, and a 30-second calibration from the doorway using a Mac (or by ear, with the Test button on the TV).

**Requirements:** a Chromecast with Google TV (or another device with Android TV 8 or newer), Spotify Premium, a Sonos speaker on the same network with UPnP on, and optionally a Philips TV with JointSpace. It's installed via the Downloader app from the GitHub release link.

Spotify Premium is really needed: librespot calls `exit(1)` for a non-Premium account, which ends the app process.

## Install

### With the Downloader app (no computer)

1. On the TV: Settings, System, About, click "Android TV OS build" seven times to enable developer options.
2. Install **Downloader** from the Play Store and allow it to install unknown apps (Settings, Apps, Security & restrictions, Unknown sources).
3. Open Downloader and enter `https://github.com/matijasalej/flat_audio/releases/latest/download/WholeFlatAudio.apk`, then install. The same address installs updates (the app is signed with the same key every time).

### With adb

```sh
adb pair <tv-ip>:<pairing-port>     # Settings, System, Developer options, Wireless debugging
adb connect <tv-ip>:<port>
adb install -r WholeFlatAudio.apk
```

Wireless debugging switches itself off when the Google TV reboots; turn it on again with the remote.

## First-time setup

1. Open **Whole Flat Audio** once on the TV and press **Start service**. From then on it starts by itself at boot. (Allow the notification permission if the TV asks.)
2. In the Spotify app on your phone, open the device list and pick **Whole Flat** once. No typing: the phone hands over the login through zeroconf, and the app keeps it for later boots.
3. **Sonos:** make sure UPnP is on for the speaker, and that its battery saver or auto-sleep does not take it off the network too early. If it does go to sleep, the kitchen joins again by itself when it wakes up.
4. **Philips TV:** turn off the TV's switch-off timer, or it will turn itself off while music plays. JointSpace (port 1925) may only open after the TV has been unplugged from the mains for a minute once. The app finds the TV by itself; if it does not, type its address on the settings screen.
5. **Google TV:** set the energy saver (Settings, System, Power & energy) to Never, and switch off "turn off the device with the TV" in the HDMI-CEC settings. A sleeping Google TV freezes the audio output; the app notices and rebuilds it, but it cannot play while the box sleeps.
6. **Grants over adb**, once (they survive updates and reboots):

   ```sh
   adb shell appops set io.github.matijasalej.wholeflat GET_USAGE_STATS allow
   ```

   This lets the app see which app is in front, so that the TV's screen is not switched off under a video. Without it the screen is left on.
7. **Calibrate** the two rooms (below) once, and again if the speakers or the TV's audio delay change.

## Settings on the TV

Open the app: the left column works with the remote (up and down to move, OK to switch, left and right on a slider).

| Setting | Meaning |
| --- | --- |
| Kitchen | ON: the Sonos plays along. OFF: only the living room. |
| Living room fixed volume | ON (default): the living room always gets the full signal (loudness is on the amplifier), and the phone's volume controls only the Sonos. OFF: the phone's volume also sets the living room (60 dB logarithmic curve). |
| TV screen off | Switch the TV's picture off when music starts after a silence (not while a video app is in front). |
| Wake the TV | If the TV is in standby when music starts, ask it to switch on (best effort). |
| Kitchen offset | -300 to +300 ms. Positive: the kitchen plays later than the living room. This is the calibration of the two rooms. |
| Kitchen balance | -20 to +20, added to the Sonos volume that follows the phone. |
| TV address | Empty: found automatically. |
| Test | 20 s of clicks, see below. |

## Calibration

The sync keeps the Sonos in step with the *moment the living room's audio chip presents each sample*. What sits behind that point (the HDMI link, the TV's audio delay on its optical output, the amplifier, and the Sonos's own output) is different in every flat, so the two rooms need one offset, measured once.

**With a Mac, from the doorway** (microphone where both rooms can be heard): the tool in `spikes/` (see `spikes/README.md`) starts the test signal over HTTP, records, matches the clicks of the two rooms and can write the result into the offset (`tworoom --apply`). The test signal is also usable by hand:

```sh
curl 'http://<tv-ip>:8080/calibrate/start?seconds=30'   # a start event through the whole pipeline
curl  http://<tv-ip>:8080/calibrate/stop
curl  http://<tv-ip>:8080/settings                       # read the settings
curl -X POST -d '{"kitchen_offset_ms": 35}' http://<tv-ip>:8080/settings
```

The test signal is one 5 ms click per second (Hann-windowed sine, half of full scale) at frame k·44100 from the start, and nothing else. The living room plays only the left channel (2 kHz, every 10th click at 4 kHz), the kitchen only the right channel (3 kHz, every 10th at 6 kHz), so a microphone can tell the rooms apart and count the clicks.

**By ear:** press **Test** on the settings screen, stand in the doorway and move the **Kitchen offset** slider until the low click and the high click fall together as one. If you hear the kitchen after the living room, the offset is positive.

## Status page and API

`http://<tv-ip>:8080` shows everything and refreshes every 2 s (the status screen in the app shows the address).

| Route | |
| --- | --- |
| `/status.json` | service, Spotify, living room, kitchen, TV and the settings |
| `/log` | the last 2000 log lines |
| `/kitchen`, `/tv` | their status only |
| `/settings` | `GET` returns the settings, `POST` with some of the keys (`kitchen_enabled`, `kitchen_offset_ms`, `kitchen_balance`, `living_fixed_volume`, `tv_screen_off`, `tv_wake`, `tv_ip`) changes them and returns all |
| `/calibrate/start?seconds=N`, `/calibrate/stop` | the test signal |
| `/kitchen/rejoin`, `/kitchen/search`, `/kitchen/select?ip=...` | restart only the Sonos stream, look for speakers, choose one |
| `/tv/run`, `/tv/detect` | run the TV sequence now, look for the TV |
| `/sys` | device facts |

What to watch in `/status.json`: `living_room.underruns`, `stalls` and `recoveries` (the audio output), `kitchen.error_ms` (the median error of the last readings, the number that should stay within ±10), `kitchen.drift_ppm` (the Sonos plays slower by about 22 ppm), `kitchen.trimmed_frames`, `kitchen.rejoins` with `last_rejoin_reason`, `kitchen.websocket.open`, and `tv.last_action`.

## How the sync works

- The living room is the clock master and is never adjusted after a start. Every discontinuity of the music (seek, skip, resume, recovery) is a *start event*: the Rust core drops what has not been played, and both rooms start again at the same frame.
- At a start the app prepares the Sonos (mute, stop, set the stream URL), then places the first frame in the living room's audio track so that it is heard when the Sonos's first sound arrives (about 0.29 s after Play), using `AudioTrack.getTimestamp`, and sends Play at that moment.
- During playback the app asks the Sonos for its play position five times a second over its local websocket, maps it to the music frame it is playing, and compares that with when the living room plays the same frame. Small errors are removed by dropping or repeating single frames in the stream (never more than one per 10 ms); an error of more than 40 ms for three readings restarts only the Sonos stream at the right frame.
- If the Sonos goes away (sleep, Bluetooth, another app), the living room keeps playing. The app polls the Sonos every 3 s and listens for its announcements; when it is free again, it joins the music in progress.

## Building

The APK is built by CI only (GitHub Actions, `.github/workflows/build.yml`); no Android SDK, JDK or NDK is needed locally. Every push builds the release APK and uploads it as the `WholeFlatAudio` artifact of the run. A push to `main` also publishes a GitHub release `build-<run number>`, which is what the Downloader URL above serves. The workflow also runs the Rust unit tests (`cargo test --lib` in `native/`) and the JVM unit tests (`gradle :app:testDebugUnitTest`: the sync math, the controller, the FLAC encoder, the settings).

`native/Cargo.lock` is committed so that CI builds the dependency versions that were checked. The Rust core also compiles, and its unit tests run, on a plain host toolchain; only the Android build itself needs the NDK.

The repository needs three secrets:

| Secret | Content |
| --- | --- |
| `ANDROID_KEYSTORE_B64` | the PKCS12 keystore, base64 encoded |
| `ANDROID_KEYSTORE_PASSWORD` | its password (also used for the key) |
| `ANDROID_KEY_ALIAS` | the key alias |

The keystore lives at `~/.config/flat_audio/release.p12`. **Back it up**: a build signed with another key cannot be installed over this one, you would have to uninstall first and lose the cached login. Without the secrets the release is signed with the debug key so that test builds still work.

Layout: `native/` is the Rust core (librespot behind JNI, built by `cargo-ndk`; `ring.rs` is the frame ring with the epochs, `calib.rs` the test signal), `app/` the Kotlin app (`sync/` is the plain-Kotlin math that the JVM tests cover; `LivingRoom` the audio output; `Kitchen`, `SonosSoap`, `SonosWs`, `SonosFinder` and `KitchenStream` the kitchen; `TvControl` the TV). `spikes/` holds the measurement tooling that the design is based on. The app targets SDK 34 on purpose: from SDK 35 on Android no longer lets a `BOOT_COMPLETED` receiver start a media playback foreground service.

librespot is used at a pinned git revision (the crates.io 0.8.0 release does not build). `native/vendor/librespot-core` is a copy of its core crate with three small changes (client token, user agent and claimed version present the desktop identity): the receiver pins the desktop client id, which desktop Spotify controllers need, and upstream would still announce Android.

## Credits

- [librespot](https://github.com/librespot-org/librespot) (MIT), the Spotify Connect implementation.
- [Rusty](https://github.com/SerafiniJose/rusty) (MIT), an Android TV Spotify receiver around librespot. Its feature flags, JNI/audio-sink structure and the client-id patch to librespot-core are the reference for this project.
