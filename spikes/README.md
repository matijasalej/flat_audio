# Sonos spike: measurement tooling

`sonos_probe.py` measures a Sonos Play (S2) as a UPnP renderer for a stream we serve from this Mac: which
formats/URIs it accepts, start and stop latency, buffer depth, how good each position signal is, clock
drift and dropouts, with the Mac's microphone as acoustic ground truth. Test signal: a click every second
(click *k* at sample *k*·44100; 2 kHz, 4 kHz when *k* % 10 = 0) over a quiet 440 Hz tone, so index, timing
and stutters are recoverable from a recording.

## Setup (once)

```
brew install sox lame                      # if missing (afconvert, dns-sd, openssl ship with macOS)
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt     # numpy + websockets
.venv/bin/python sonos_probe.py selftest   # ~3.5 min, fully offline, must end with "0 failed"
```

`discover`, `listen`, `play`, `volume`, `wol` run on plain `python3` (3.9); `ws`, `measure`, `drift`,
`analyze`, `selftest` need `.venv/bin/python`. For a dry run without the speaker: `python3 sonos_probe.py mock &` starts a
fake renderer on 127.0.0.1:1400, then `play --ip 127.0.0.1 --fmt mp3 --duration 15`. Everything lands in `results/<timestamp>_<command>/`
(JSONL logs, `summary.md`, wav recordings; wavs and `results/cache/` are git-ignored).
`--fmt aac` builds a cached ADTS click track once (6 s; the 60-minute drift run needs ~1 GB free for ~1 min).

## What the user has to do

* Sonos **on its charger** (or Battery Saver off), on Wi-Fi, **UPnP enabled in the Sonos app**, not grouped.
* Mac on the **same subnet** (no VPN), **~1 m from the speaker**, room quiet, **Mic Mode = Standard**
  (not Voice Isolation), input volume up. Keep the Mac plugged in (the tool runs `caffeinate`).
* Say **yes once** to the macOS prompts: Local Network, Microphone (terminal app), and "accept incoming
  connections" for python (the stream server listens on port 8090).

## Home session, in this order  (`IP` = speaker address printed by step 1)

```
python3 sonos_probe.py discover                          # 1. model, firmware, room, serial, MAC (note it), services

python3 sonos_probe.py listen --ip $IP --minutes 20      # 2. now put the speaker to sleep, wake it, connect/disconnect
                                                         #    Bluetooth, lift it off the charger ...; every NOTIFY and
                                                         #    reachable/unreachable transition is logged with timestamps
python3 sonos_probe.py wol --mac <MAC>                   #    (second terminal, while it sleeps: does WoL wake it?)

for f in wav flac-chunked flac-nolen mp3 aac; do         # 3. each format >= 5 min. Default: x-rincon-mp3radio:// + audioBroadcast
  python3 sonos_probe.py play --ip $IP --fmt $f --duration 300 --volume 15
done
python3 sonos_probe.py play --ip $IP --fmt wav --scheme http --class track --duration 300   # retry rejected/dying ones this way
python3 sonos_probe.py play --ip $IP --fmt mp3 --prebuffer 20 --duration 120 --pause-at 40  # buffer depth + Pause behaviour

.venv/bin/python sonos_probe.py measure --ip $IP --fmt mp3 --volume 20 --ws    # 4. best two formats, 10 trials each (~6 min),
.venv/bin/python sonos_probe.py measure --ip $IP --fmt aac --volume 20 --ws    #    uses the microphone; drop --ws if step 5 fails

.venv/bin/python sonos_probe.py ws --ip $IP --info-only                        # 5. websocket API: ids, then loadStreamUrl
.venv/bin/python sonos_probe.py ws --ip $IP --fmt aac --duration 120

.venv/bin/python sonos_probe.py drift --ip $IP --fmt mp3 --minutes 60 --ws     # 6. 60 min, 5-min chunks, low volume
```

The first trial prints a warning if the microphone signal is weak (raise `--volume`, move closer).
`analyze <wav...>` re-analyses recordings later. `volume --ip $IP --get|--set N` is a plain RenderingControl call.

## Reading the results

* **`play` summary**: *Play -> PLAYING*, *Stop -> connection closed* (does Sonos drop the stream on Stop?), the
  connection table (User-Agent, Range, Icy-MetaData, who closed it), *RelTime* (does it advance, in 1.000 s steps?),
  *read pacing* (audio handed to the socket minus elapsed time: saturates at the renderer's buffer once sends block).
* **`measure` `summary.md`**, one row per trial plus mean/stdev:
  *play->sound* = Play request to click 0 heard; *stop->silence* = Stop request to the tone falling 6 dB;
  *RelTime / ws / renderer buffer* = `t_signal(P) - P - t_heard(0)`: how late a position claim is versus the sound
  (negative = the signal runs ahead of what you hear). **The stdev across trials is what matters**: a constant offset
  can be calibrated away, a varying one cannot. The read-pacing figure (audio buffered in renderer + socket buffers)
  needs `--prebuffer` larger than the renderer's buffer (default 15 s here), else "not full".
  *dropouts*: gap = tone dropped > 9 dB, jump = tone phase jump (skipped/repeated audio), miss = click never heard,
  seg-break = click grid shifted (stutter).
* **`drift_summary.md`**: **positive ppm = the speaker plays slower than the Mac sends**. Rows for the acoustic clicks
  (corrected for the recorder's own clock), RelTime, websocket position and read pacing, then dropouts, stream
  restarts and the clicks' offset per 5 minutes (`drift_offsets.csv` has every click).
* **Uncertainties**: the recording is tied to the Mac clock through the read times of `rec`, which leaves a constant
  bias of the mic chain's input latency (typically 10-40 ms) in absolute numbers (not in stdevs, not in drift);
  RelTime edges are known to +-25 ms (50 ms poll grid); MP3/AAC rows subtract the encoder+decoder delay measured
  offline (MP3 576 samples = 13.06 ms, AAC 2112 = 47.89 ms, `results/cache/codec_delays.json`) but Sonos' own decoder
  may differ by up to ~12 ms; the mock/real buffer figure includes kernel socket buffers on both ends (our send
  buffer is 16 KB).

## Troubleshooting

* `discover` finds nothing: speaker asleep, other subnet/VPN, or Local Network permission denied for the terminal.
* `SetAVTransportURI` refused (e.g. 714/716): the log prints the UPnP error and raw reply; try the other `--scheme`/`--class`.
* `microphone gives no signal`: grant Microphone access to the terminal app, check that the input is not muted.
* Port 8090 busy: `--port`. Speaker cannot reach the Mac: `--local-ip <Mac LAN address>`.
