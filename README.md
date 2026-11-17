# RTSP Camera

[![CI](https://github.com/WLTBAgent/rtsp-camera/actions/workflows/ci.yml/badge.svg)](https://github.com/WLTBAgent/rtsp-camera/actions/workflows/ci.yml)

**RTSP Camera** — an Android app that turns your phone into a network camera. It captures the camera feed (CameraX), hardware-encodes it to H.264 with `MediaCodec`, and serves it over RTSP from the phone itself, so OBS Studio, VLC, or ffplay can connect directly — no root required. The app installs on your device as *RTSP Camera*.

```
Phone (CameraX capture → MediaCodec H.264 → built-in RTSP server)
        │  Wi-Fi / USB tethering
        ▼
OBS Media Source ── rtsp://<phone-ip>:8554/live
```

## Features

- **Built-in RTSP server** — self-contained, no third-party streaming library. H.264 over RTP (RFC 6184 single-NAL + FU-A), AAC over RTP (RFC 3640), TCP-interleaved (default) and UDP transports, RTCP sender reports.
- **Hardware encoding** — zero-copy path from the camera into `MediaCodec` via its input surface; baseline profile, no B-frames for decoder-friendly streaming.
- **Camera controls**, all shown only when the device reports support:
  - camera picker listing every camera the device exposes (secondary ultrawide/macro/telephoto IDs included), plus a quick front/rear switch
  - pinch-to-zoom + zoom slider (optical zoom on multi-camera phones switches physical lenses automatically)
  - resolution selection from the camera's supported modes, intersected with what the encoder can encode
  - manual focus / macro focus distance slider (diopter-based; dedicated macro cameras are labeled in the picker)
  - exposure compensation, AE lock, torch
- **Optional AAC audio track** from the microphone, toggleable mid-stream.
- **Streaming survives backgrounding** — a foreground service owns the camera and server; notification with a stop action; wake lock held while streaming.
- **Easy OBS setup** — connect URL plus a scannable QR code.
- Persisted settings (port, fps, bitrate, resolution, audio).

## Scope & non-goals

- v1 transport is **RTSP**. Works on any Android 8.0+ device, no root.
- **UVC is out of scope**: presenting the phone as a USB Video Class *device* requires USB gadget mode, which third-party apps cannot use without root. (Android 14+ ships a system-level "USB webcam" mode, but that is built into the OS and not accessible to apps.)

## Building

Requirements: JDK 17 or newer, Android SDK with platform 35 (`sdkmanager` fetches anything missing).

```bash
./gradlew :app:assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

Run the unit tests (pure JVM, no device needed):

```bash
./gradlew :app:testDebugUnitTest
```

Install on a connected device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Using with OBS

1. Install the APK and grant the camera permission. The app appears in your launcher as **RTSP Camera**.
2. Connect the phone to the same Wi-Fi network as the streaming PC (USB tethering also works and is more stable).
3. Tap **Stream**. The status text shows the connect URL, e.g. `rtsp://192.168.1.23:8554/live`.
4. In OBS: **Sources → + → Media Source** → uncheck *Local File* → paste the URL into *Input*. Leave *Input Format* empty (ffmpeg autodetects) and set *Network Buffering* to taste (0–200 ms for lower latency).
5. Tap **QR** for a scannable version of the URL.

Quick check without OBS:

```bash
ffplay -fflags nobuffer rtsp://<phone-ip>:8554/live
```

### Latency tips

- **TCP-interleaved** (the default) is reliable on Wi-Fi; **UDP** can be slightly lower-latency on clean networks but drops packets under congestion.
- Reduce **Network Buffering** in OBS's Media Source properties for lower latency.
- Lower resolution/bitrate (Settings dialog) reduces end-to-end delay.
- USB tethering beats Wi-Fi for stability and latency.

### Camera control compatibility

Android camera capabilities vary a lot between vendors, so the app queries each camera and hides what it doesn't support:

| Control | How it's detected |
|---|---|
| Camera picker entries | Every camera ID the device exposes |
| Zoom | CameraX `ZoomState` range |
| Manual focus / macro | AF-off mode supported + nonzero minimum focus distance (diopters); cameras with ≥ 5 diopters (≈ 20 cm) are tagged *macro* |
| Exposure compensation | Non-degenerate `ExposureState` range |
| Torch | Flash unit present |

Macro behavior differs per device: some phones expose a dedicated macro camera (it appears in the picker), others reach macro range through manual focus on the main camera, and some support neither — in which case the control is hidden.

## Troubleshooting

- **No picture in OBS** — verify phone and PC are on the same network, the phone shows *1 client(s)* after OBS connects, and OBS's Media Source has *Local File* unchecked.
- **Video tears or stalls** — switch the resolution down; check Wi-Fi signal; try USB tethering.
- **Garbage/corrupt video right after connecting** — the stream starts on the next keyframe (≤ 1 s by default); if it persists, stop/start the stream.
- **Port already in use** — change the RTSP port in the settings dialog.
- **Audio missing in OBS** — enable the audio toggle before or during the stream (mid-stream toggles restart the stream).

## Project layout

```
app/src/main/java/io/github/wltbagent/droidcam/
├── camera/       CameraX control: binding, capability queries, focus/exposure
├── encode/       MediaCodec wrappers (video AVC, audio AAC)
├── rtp/          RTP packetizers (RFC 6184 H.264, RFC 3640 AAC), RTCP, Annex-B parser
├── rtsp/         RTSP server, SDP, transports (TCP-interleaved / UDP), sessions
├── stream/       StreamingEngine (capture↔encode glue), RtspStreamHub
├── service/      Foreground StreamingService owning the whole pipeline
└── util/         LAN IP discovery, QR generation
```

The `rtp/` and `rtsp/` protocol code is plain Kotlin with unit tests, including a JVM integration test that drives the real server through a full OPTIONS→DESCRIBE→SETUP→PLAY handshake and validates the RTP stream byte-by-byte.

## Roadmap

- [x] M0 — Project scaffold
- [x] M1 — Camera preview, camera picker, front/rear switch, zoom, torch
- [x] M2 — Hardware H.264 encode + resolution selection from supported modes
- [x] M3 — Built-in RTSP server (OBS/VLC/ffplay compatible)
- [x] M4 — Optional AAC audio streaming
- [x] M5 — Advanced camera controls: macro/manual focus, exposure, AE lock
- [x] M6 — Foreground streaming service, settings, QR connect info
- [ ] M7 — CI + release builds
- [ ] On-device verification pass (VLC/OBS playback, latency tuning)

## License

GPL-2.0-only — see [LICENSE](LICENSE).
