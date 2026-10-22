# Whole Flat Audio

Two-room Spotify Connect receiver for Android TV (Google TV), with synchronized Sonos output planned.

**Status: spike.** This build is the scaffold: it shows up in the Spotify app as a Connect device named **Whole Flat**, plays locally at 320 kbps through `AudioTrack`, runs as a foreground service, restarts after a reboot with cached credentials, and has a debug page plus test routes for streaming to a Sonos over UPnP. Target is a Chromecast with Google TV (Android 14, probably 32-bit userland, so the APK carries `armeabi-v7a` and `arm64-v8a`). Nothing here has been run on a device yet.

Spotify Premium is required: librespot calls `exit(1)` for a non-Premium account, which ends the app process.

## Install

### With the Downloader app (no computer)

1. On the TV: Settings, System, About, click "Android TV OS build" seven times to enable developer options.
2. Install **Downloader** from the Play Store and allow it to install unknown apps (Settings, Apps, Security & restrictions, Unknown sources).
3. Open Downloader and enter `https://github.com/matijasalej/flat_audio/releases/latest/download/WholeFlatAudio.apk`, then install.

### With adb

```sh
adb pair <tv-ip>:<pairing-port>     # Settings, System, Developer options, Wireless debugging
adb connect <tv-ip>:<port>
adb install -r WholeFlatAudio.apk
```

## First-time setup

1. Open **Whole Flat Audio** once on the TV and press **Start service**. From then on it starts by itself at boot.
2. In the Spotify app on your phone, open the device list and pick **Whole Flat**. No typing: the phone hands over the login through zeroconf, and the app keeps it for later boots.
3. The debug page is at `http://<tv-ip>:8080` (the status screen in the app shows the address).

Debug routes on port 8080: `/` (HTML, refreshes every 2 s), `/status.json`, `/log`, `/sys`, `/net/udp319` (can this app bind UDP 319/320, i.e. is AirPlay 2 timing possible without root).

Sonos spike (device to Sonos over UPnP, from a laptop):

```sh
curl http://<tv-ip>:8080/sonos/discover
curl 'http://<tv-ip>:8080/sonos/play?ip=<sonos-ip>&fmt=flac&scheme=http&prebuffer=2'   # fmt=wav|flac, scheme=http|x-rincon-mp3radio
curl 'http://<tv-ip>:8080/sonos/position?ip=<sonos-ip>'
curl 'http://<tv-ip>:8080/sonos/stop?ip=<sonos-ip>'
```

The test signal is a click every second (2 kHz, every 10th at 4 kHz) over a quiet 440 Hz tone.

## Building

CI only (GitHub Actions, `.github/workflows/build.yml`); there is no Android SDK, JDK or Rust toolchain to install locally. Every push builds the release APK and uploads it as the `WholeFlatAudio` artifact of the run. A push to `main` also publishes a GitHub release `build-<run number>`, which is what the Downloader URL above serves.

The repository needs three secrets:

| Secret | Content |
| --- | --- |
| `ANDROID_KEYSTORE_B64` | the PKCS12 keystore, base64 encoded |
| `ANDROID_KEYSTORE_PASSWORD` | its password (also used for the key) |
| `ANDROID_KEY_ALIAS` | the key alias |

The keystore lives at `~/.config/flat_audio/release.p12`. **Back it up**: a build signed with another key cannot be installed over this one, you would have to uninstall first and lose the cached login. Without the secrets the release is signed with the debug key so that test builds still work.

Layout: `native/` is the Rust core (librespot behind JNI, built by `cargo-ndk`), `app/` the Kotlin app. The app targets SDK 34 on purpose: from SDK 35 on Android no longer lets a `BOOT_COMPLETED` receiver start a media playback foreground service.

librespot is used at a pinned git revision (the crates.io 0.8.0 release does not build). `native/vendor/librespot-core` is a copy of its core crate with three small changes (client token, user agent and claimed version present the desktop identity): the receiver pins the desktop client id, which desktop Spotify controllers need, and upstream would still announce Android.

## Credits

- [librespot](https://github.com/librespot-org/librespot) (MIT), the Spotify Connect implementation.
- [Rusty](https://github.com/SerafiniJose/rusty) (MIT), an Android TV Spotify receiver around librespot. Its feature flags, JNI/audio-sink structure and the client-id patch to librespot-core are the reference for this project.
