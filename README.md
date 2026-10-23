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
- **A second way in:** when Spotify refuses the "Whole Flat" device, cast to the Chromecast itself ("Den TV") instead, and the kitchen follows a copy of what it plays (see "If Spotify refuses Whole Flat" below).
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

**Wireless debugging switches itself off after every reboot** of the Google TV. Switch it on again in Settings, System, Developer options, Wireless debugging (with the remote), then look up the new port from the Mac: `adb mdns services` lists `_adb-tls-connect._tcp` with the address and port, and `adb connect <tv-ip>:<port>` connects (the port changes each time; an already paired Mac does not need the pairing code again).

## First-time setup

1. Open **Whole Flat Audio** once on the TV and press **Start service**. From then on it starts by itself at boot. (Allow the notification permission if the TV asks.)
2. In the Spotify app on your phone, open the device list and pick **Whole Flat** once. No typing: the phone hands over the login through zeroconf, and the app keeps it for later boots.
3. **Sonos:** make sure UPnP is on for the speaker, and that its battery saver or auto-sleep does not take it off the network too early. If it does go to sleep, the kitchen joins again by itself when it wakes up.
4. **Philips TV:** turn off the TV's switch-off timer, or it will turn itself off while music plays. JointSpace (port 1925) may only open after the TV has been unplugged from the mains for a minute once. The app finds the TV by itself; if it does not, type its address on the settings screen.
5. **Google TV:** the energy saver (Settings, System, Power & energy) **must be Never**. A Google TV that goes to sleep freezes the audio output (the app notices and rebuilds it, but it cannot play while the box sleeps). Also switch off "turn off the device with the TV" in the HDMI-CEC settings.
6. **Grants over adb**, once (they survive updates and reboots):

   ```sh
   adb shell appops set io.github.matijasalej.wholeflat GET_USAGE_STATS allow
   ```

   This lets the app see which app is in front, so that the TV's screen is not switched off under a video. Without it the screen is left on.

   The four grants of the capture mode are in "If Spotify refuses Whole Flat" below; they are only needed if you play to the Chromecast itself.
7. **Calibrate** the two rooms (below) once, and again if the speakers or the TV's audio delay change.

## One player per Spotify account

Only one librespot-based player should run per Spotify account at a time. This app is one; the Spotify app on a computer with librespot, Raspberry Pi players, spotifyd and similar are others. Every player asks Spotify for an audio key for every track it loads, and a few players on one account mean a few times the requests, which is how an account ends up with its keys refused (see below). Switch the others off while this one is in use.

## If Spotify refuses playback

Some accounts get the audio keys of their tracks refused by Spotify for a while (librespot issue #1649). It is a refusal on Spotify's side that is tied to the account, not a fault of the app: a stock librespot on a computer gets the same. The usual librespot answer, skipping to the next track, makes it worse, because each skipped track asks for a key again.

This app does not skip. When a key is refused, it:

- **pauses** (the Spotify app on the phone shows the device as paused) and **keeps the same track**,
- **tries that track again by itself** after 3 s, 10 s, 30 s, 2 min, 5 min and then every 15 min,
- never sends two key requests less than 3 s apart after an error,
- **gives up after about 30 minutes** of refusals and waits for you.

Your own commands are respected: **play** in the Spotify app tries again at once (but not sooner than 3 s after the last refusal) and starts the back-off over, **pause** stops the tries until you press play, and loading another track or position starts over from the beginning.

What you see meanwhile:

- on the TV, the first lines of the status say *Spotify is refusing playback for this account right now, retrying in 12 s*, or, after the 30 minutes, that it kept refusing and that you should press play on the phone;
- in `/status.json`: `"spotify_state": "key_refused"` (or `"key_gave_up"`, `"ok"`), `"retry_in_s"` and `"count"` (the refusals of this episode), and the object `spotify_key` with the phase (`waiting`, `retrying`, `user_paused`, `gave_up`) and the total since the app started;
- in the log (`/log`): lines tagged `spotify` and `native` for every refusal, try and recovery.

If it does not clear up, wait. The refusals have gone away by themselves after some hours; do not keep pressing play (the app already spaces its requests, and every press starts the back-off over).

## If Spotify refuses Whole Flat: play to Den TV

Spotify can refuse the audio keys of an account for a long time (see above), and then nothing helps but a player that is not librespot. The Chromecast is one: in the Spotify app on the phone pick **the Chromecast itself** (here "Den TV") instead of "Whole Flat". The Chromecast plays the music by itself, to the living room, through the TV and the amplifier as always. This app then **captures that audio** (Android's AudioPlaybackCapture of the Cast receiver, `com.google.android.apps.mediashell`, and of the Spotify TV app, `com.spotify.tv.android`) and sends a copy to the Sonos. It is the same kitchen, fed from another source.

**Four grants over adb, once** (they survive updates and reboots; without them the capture waits, and the status screen says which one is missing):

```sh
adb shell pm grant io.github.matijasalej.wholeflat android.permission.RECORD_AUDIO
adb shell appops set io.github.matijasalej.wholeflat PROJECT_MEDIA allow
adb shell appops set io.github.matijasalej.wholeflat SYSTEM_ALERT_WINDOW allow
adb shell cmd notification allow_listener io.github.matijasalej.wholeflat/io.github.matijasalej.wholeflat.capture.MediaWatcher
```

`RECORD_AUDIO` is what the capture is called ("recording"), `PROJECT_MEDIA` lets the app have its screen capture permission without the dialog on the TV, `SYSTEM_ALERT_WINDOW` lets it show the (invisible) permission screen from the background, for example after a reboot, and the notification listener access lets it read the play/pause state and the title of the two players, so that the kitchen can be made ready a moment before the sound. The last one is optional: without it the kitchen starts about 0.2 s later, on the sound itself. Without adb, the app asks for the recording permission when it opens, and the button **Capture: ask for permission now** makes the TV show the screen capture dialog (answer it with the remote); but that dialog then comes back every time the capture starts, for example after a reboot, which is what the app op avoids.

**What it does, by itself:** the capture starts with the app (also after a boot) and is shown on the status screen (`Capture: running, level -23 dBFS`). Sound for 200 ms is a start: the kitchen is prepared at the first sound and Play goes out at once. Silence for 2 s is a pause, but the kitchen **keeps streaming** (silence) for up to a minute, so skipping a track or pausing for a bit never restarts it; after a minute of silence the Sonos is muted and stopped, and the next sound starts it again. If the projection is lost the capture starts again with a wait of 2, 5, 15, 30 and then 60 s.

**Which source wins:** if the Spotify app plays on "Whole Flat" (librespot) and the Chromecast plays at the same time, librespot wins and the capture is ignored; when librespot stops, the capture takes over if its player still plays. `source` in `/status.json` and on the screen says `librespot`, `capture` or `idle`.

**The kitchen is late, by design.** A capture has no second room to be in step with: the Chromecast plays the living room by itself, at once, and the copy can only reach the Sonos after it was captured, through the Sonos's own buffer. So **the kitchen sounds a fraction of a second after the living room**: expect about 0.4 to 0.6 s (a Play-to-sound delay of 0.29 s plus the 150 ms of audio sent ahead at the start; the first run on the device will tell). The app measures it in the first seconds of a stream and then holds it constant against the drift of the Sonos clock (`kitchen.lag_ms`, `kitchen.lag_target_ms`, `kitchen.room_offset_ms` in `/status.json`, and on the screen next to the kitchen's offset). It cannot be made smaller than the Sonos allows, only moved:

| Setting | Meaning |
| --- | --- |
| `capture_lead_ms` (40 to 3000, default 150) | How much audio is sent to the Sonos ahead when the stream starts. A smaller number is a smaller lag; if the kitchen stutters, make it bigger. It is read when a stream starts. If the Sonos turns out to need more than asked (it waits for audio before it plays, or it stalls and fills its buffer again), the app remembers that until it is restarted or you change this setting, and starts the next streams that far ahead; `kitchen.capture_lead_ms` is the lead in use and `kitchen.capture_lead_learned_ms` what was learned. |
| `kitchen_offset_ms` | Still works in capture mode: a positive value makes the lag bigger by that much, a negative one smaller (never below 100 ms). A change of more than about 60 ms restarts the stream. |
| `capture_living_latency_ms` (default 0) | How much later than it is captured the living room sounds (the Chromecast's output buffer and the TV's delay). It is only subtracted in the reported `kitchen.room_offset_ms`; nothing is steered with it. |
| `capture_enabled` (default on) | Switches the capture off. |

Set them with `curl 'http://<tv-ip>:8080/settings?capture_lead_ms=300'`.

**Volume.** The phone's Cast volume changes the music volume of the Chromecast, and the capture is taken *before* that volume, so the Sonos would always get the full signal. The app therefore copies the volume to the Sonos: the Chromecast's music volume, as a fraction of its maximum, becomes the Sonos volume (plus the kitchen balance). With **Living room fixed volume** on (the loudness is set on the amplifier) the Chromecast's own volume is then put back to its maximum, half a second after the last change, so that the living room is not left quieter: you hear a short dip in the living room when you change the volume, and the volume bar on the phone jumps back to full while the Sonos keeps the level you chose. Until you change the Cast volume for the first time the Sonos keeps the volume it has (the box is parked at full on purpose, so its level says nothing about the kitchen). With it off, the Chromecast stays where you put it, and living room and Sonos follow the phone from the first note. Because the bar always jumps back to full, a drag to the very top is no change and does nothing; if the Sonos should go back to full, drag to the top before the bar has jumped back (within a quarter of a second of the last change), or leave it one step below full.

**Keep the Spotify TV app as it is.** A new version of the Spotify app can opt out of being captured (or play through another path), and then the capture hears silence. Once it works, switch off automatic updates for it: in the Play Store on the TV open the Spotify page, and in its menu switch off *Enable auto update* (or switch off auto-update for all apps in the Play Store's settings). The Cast receiver is part of the system and changes only with a system update.

**Not done in this mode:** the TV's picture is not switched off and the TV is not woken (the Chromecast shows its own screen for the cast), and the living room is never touched: our own audio output only plays silence and never the captured music. Anything else that the Cast receiver plays is captured as well, for example a video cast from the phone, and also goes to the kitchen.

## Free storage

The Google TV has little storage, and the Spotify app's own cache has filled it to 97% once, which made the installation of this app fail. This app keeps its own use small (no audio cache, only the login and a few settings on disk, the log is in memory and bounded), and shows the free space of `/data` in `/status.json` (`storage`) and on the status screen of the app. **Below 300 MB it warns** (the screen says LOW, and the log gets a line). If it does, clear the cache of the Spotify app and other apps in Settings, Apps.

## Settings on the TV

Open the app: the left column works with the remote (up and down to move, OK to switch, left and right on a slider).

| Setting | Meaning |
| --- | --- |
| Kitchen | ON: the Sonos plays along. OFF: only the living room. |
| Capture of the Cast receiver | ON (default): what the Chromecast plays when you cast to it is copied to the Sonos (see "If Spotify refuses Whole Flat"). OFF: no capture. |
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
| `/settings` | `GET` returns the settings, `POST` with some of the keys (`kitchen_enabled`, `kitchen_offset_ms`, `kitchen_balance`, `living_fixed_volume`, `tv_screen_off`, `tv_wake`, `tv_ip`, `capture_enabled`, `capture_living_latency_ms`, `capture_lead_ms`) changes them and returns all |
| `/calibrate/start?seconds=N`, `/calibrate/stop` | the test signal |
| `/kitchen/rejoin`, `/kitchen/search`, `/kitchen/select?ip=...` | restart only the Sonos stream, look for speakers, choose one |
| `/capture`, `/capture/start`, `/capture/restart` | the capture's status (state, grants, level, activity, media session, volume, timeline of the last attempt); an attempt now, also without the grants (the permission dialog then shows on the TV); end the capture in progress, it starts again by itself |
| `/tv/run`, `/tv/detect` | run the TV sequence now, look for the TV |
| `/sys` | device facts |

What to watch in `/status.json`: `spotify_state` (`ok`, `key_refused`, `key_gave_up`) with `retry_in_s` and `count`, `storage.data_free_mb` (warning below 300), `source`, `living_room.underruns`, `stalls` and `recoveries` (the audio output), `kitchen.error_ms` (the median error of the last readings, the number that should stay within ±10), `kitchen.drift_ppm` (the Sonos plays slower by about 22 ppm), `kitchen.trimmed_frames`, `kitchen.rejoins` with `last_rejoin_reason`, `kitchen.websocket.open`, and `tv.last_action`. In capture mode: `source` (`librespot`, `capture`, `idle`), `capture.state` (`running`, or what it waits for), `capture.level_dbfs` and `capture.activity` (`idle`, `active`, `paused`), `kitchen.lag_ms` with `lag_target_ms` and `room_offset_ms` (how far behind the capture, and behind the living room, the kitchen sounds), and `kitchen.lag_locked`.

## Sources of music

Where the music comes from is a small interface (`app/.../source/AudioSource.kt` and `native/src/source.rs`): a source delivers PCM frames with their sample rate and reports start, pause, resume and flush (a seek or a skip), the track and the volume. librespot feeds the ring from inside the native core. A source on the Kotlin side gets the app's `SourceBridge` as its sink and listener (`PlaybackService.sourceBridge`) and goes through the same ring, start events and sync as librespot. The ring plays 44.1 kHz, so a source with another rate has to convert first.

The **capture source** (`app/.../capture/CaptureSource.kt`) is the second one. It implements `AudioSource`, but its audio does not go through the ring: it is 48 kHz and cannot be slowed down, so `CaptureFeed` keeps the last 8 s in a buffer of its own (numbered frames, with a clock from `AudioRecord.getTimestamp`), and the kitchen's stream reads from there and sends FLAC at 48 kHz, without resampling. The living room plays no captured audio (the Cast receiver plays it by itself). `SourceArbiter` decides which source feeds the speakers.

## How the sync works

- The living room is the clock master and is never adjusted after a start. Every discontinuity of the music (seek, skip, resume, recovery) is a *start event*: the Rust core drops what has not been played, and both rooms start again at the same frame.
- At a start the app prepares the Sonos (mute, stop, set the stream URL), then places the first frame in the living room's audio track so that it is heard when the Sonos's first sound arrives (about 0.29 s after Play), using `AudioTrack.getTimestamp`, and sends Play at that moment.
- During playback the app asks the Sonos for its play position five times a second over its local websocket, maps it to the music frame it is playing, and compares that with when the living room plays the same frame. Small errors are removed by dropping or repeating single frames in the stream (never more than one per 10 ms); an error of more than 40 ms for three readings restarts only the Sonos stream at the right frame.
- If the Sonos goes away (sleep, Bluetooth, another app), the living room keeps playing. The app polls the Sonos every 3 s and listens for its announcements; when it is free again, it joins the music in progress.
- In capture mode there is no living room to follow, so the stream starts a little in the past (`capture_lead_ms`), the first readings of the Sonos's position measure how long after the capture the kitchen sounds, and that lag is then held constant with the same frame trims (a tolerance of 8 ms). If the lag itself steps (the Sonos stalled and filled its buffer again), it is measured again instead of restarting the stream (`kitchen.capture_relocks`); the stream is restarted only for a lag beyond 5 s, after six such steps, or when a change of the kitchen offset is more than the trims can follow (about 60 ms at once).

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

Layout: `native/` is the Rust core (librespot behind JNI, built by `cargo-ndk`; `ring.rs` is the frame ring with the epochs, `calib.rs` the test signal, `keyguard.rs` the back-off for refused audio keys, `source.rs` the way in for other sources), `app/` the Kotlin app (`sync/` is the plain-Kotlin math that the JVM tests cover, and so is most of `capture/`: the activity detector, the arbiter, the buffer and clock, the lag lock, the volume mirror; the Android parts of `capture/` are the source, the consent activity, the service and the media session watcher; `LivingRoom` the audio output; `Kitchen`, `SonosSoap`, `SonosWs`, `SonosFinder` and `KitchenStream` the kitchen; `TvControl` the TV). `spikes/` holds the measurement tooling that the design is based on. The app targets SDK 34 on purpose: from SDK 35 on Android no longer lets a `BOOT_COMPLETED` receiver start a media playback foreground service.

librespot is used at a pinned git revision (the crates.io 0.8.0 release does not build). Two crates are vendored in `native/vendor/`, each header of `Cargo.toml` lists the changes. `librespot-core` has three small changes (client token, user agent and claimed version present the desktop identity: the receiver pins the desktop client id, which desktop Spotify controllers need, and upstream would still announce Android), plus the spacing of audio key requests after an error and a tap on the commands the Spotify app sends. `librespot-playback` is librespot PR #1799 (a refused audio key no longer makes Spirc skip through the queue) with one change of ours: the player stays in its loading state, so that the same track can be loaded again later.

## Credits

- [librespot](https://github.com/librespot-org/librespot) (MIT), the Spotify Connect implementation.
- [Rusty](https://github.com/SerafiniJose/rusty) (MIT), an Android TV Spotify receiver around librespot. Its feature flags, JNI/audio-sink structure and the client-id patch to librespot-core are the reference for this project.
