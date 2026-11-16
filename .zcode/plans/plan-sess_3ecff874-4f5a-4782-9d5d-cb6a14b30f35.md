# wltb-droidcam — Implementation Plan

## Context

Brand-new repo (only a README). Goal: an Android camera app that turns a phone into a video source for OBS or similar. **Decisions made with user:** Kotlin + Android SDK, **RTSP first**, public GitHub repo, **no root required**. UVC device mode requires root (configfs gadget) or Android 14's system-level webcam feature (not app-accessible), so the README will scope v1 to RTSP and document UVC as a non-goal. The app runs an RTSP **server** on the phone; OBS connects as a client (`Media Source` → `rtsp://phone-ip:8554/live`) over Wi-Fi or USB tethering.

**Camera feature requirements (user notes):** zoom, macro/manual focus where the device allows it, resolution selectable from supported modes, front/rear switching.

Architecture in one line: **CameraX capture → MediaCodec hardware H.264 encode → in-repo minimal RTSP/RTP server → OBS**.

Key design choices:
- **Self-contained RTSP/RTP implementation** (no third-party streaming library): the protocol subset OBS/ffmpeg needs is small (OPTIONS/DESCRIBE/SETUP/PLAY/TEARDOWN + SDP + RTP packetization), and an auditable, dependency-light core fits a public repo. Fallback if interop drags: embed RootEncoder (Apache-2.0) which ships an RTSP server.
- **H.264 via MediaCodec input surface**: CameraX `Preview` provides its `SurfaceRequest` surface directly to `MediaCodec.createInputSurface()` — zero-copy hardware path.
- **Capability-driven UI for a public, device-diverse audience**: every camera control (zoom, manual focus, exposure, resolutions) is shown only when the device reports support, via `CameraInfo`/`CameraCharacteristics` queries. Enumerated in one place: `camera/CameraCapabilities.kt`.
- RTP per RFC 6184 (single-NAL, STAP-A, FU-A), 90 kHz clock, TCP interleaved default + UDP option. Audio (AAC) is a separate later milestone.
- Foreground service (`foregroundServiceType="camera|microphone"`) so streaming survives backgrounding.

Environment notes: Java 21 ✓, Android SDK at `/opt/android-sdk` (gitignored `local.properties`; install missing components with `sdkmanager`). System Gradle is 4.4.1 — too old for AGP, so we download a current Gradle distribution once to generate the Gradle 8.x wrapper. No device attached and no emulator installed yet; runtime verification needs the user's phone or an emulator install. Go toolchain is not used.

## Milestones

**M0 — Scaffold**
- `git init`; `.gitignore`, `LICENSE` (Apache-2.0), update `README.md` (RTSP scope, UVC non-goal rationale)
- Gradle 8.x wrapper + AGP 8.7.x + Kotlin 2.0.x, version catalog, `settings.gradle.kts`, `app/` module with placeholder `MainActivity`; package `io.github.wltbagent.droidcam`
- Verify `./gradlew :app:assembleDebug`

**M1 — Camera preview + basic camera controls**
- CameraX `Preview` via `PreviewView`, runtime permission flow
- **Front/rear quick-switch** button, plus a full **camera picker** listing every camera the device exposes (many phones hide macro/ultrawide as separate camera IDs — surfaced via `cameraProvider.getAvailableCameraInfos()` + `Camera2CameraInfo`)
- **Zoom**: pinch-to-zoom on preview + slider (`CameraControl.setZoomRatio`, range from `ZoomState`; multi-camera phones switch physical lenses automatically for optical zoom), torch toggle
- Files: `camera/CameraController.kt`, `camera/CameraCapabilities.kt`

**M2 — Video encode + resolution selection**
- `encode/VideoEncoder.kt`: MediaCodec AVC from the encoder input surface; defaults 1080p/30, 6 Mbps, 1 s I-frame interval; runtime bitrate reconfig
- **Resolution picker from supported modes**: enumerate camera output sizes (`StreamConfigurationMap` via Camera2 interop), intersect with MediaCodec AVC capabilities (`videoCapabilities.isSizeSupported`), let user pick; applied via CameraX `ResolutionSelector`/`ResolutionStrategy` + matching encoder config. Mid-stream change = encoder restart with in-band SPS/PPS (ffmpeg/OBS handles it) — graceful fallback is a brief stream restart
- Sanity check: dump elementary stream to `.h264`, `adb pull`, verify with `ffprobe`

**M3 — RTSP server (the core)**
- `rtsp/RtspServer.kt` (accept loop + per-client sessions), `rtsp/Sdp.kt` (live `sprop-parameter-sets`), `rtp/H264Packetizer.kt` (RFC 6184 FU-A/STAP-A), `rtp/RtcpSender.kt`
- TCP interleaved default, UDP optional; port 8554, path `/live`
- Unit tests for packetizer edge cases + SDP; verify with `ffplay`/VLC, then OBS

**M4 — Audio (optional toggle)**
- `AudioRecord` → MediaCodec AAC → RFC 3640 packetizer (`rtp/AacPacketizer.kt`), added to SDP, A/V sync via RTCP SR; off by default

**M5 — Advanced camera controls (capability-gated)**
- **Macro / manual focus**: if the device exposes a dedicated macro camera it appears in the camera picker (M1); otherwise a focus-distance slider (diopters from `LENS_INFO_MINIMUM_FOCUS_DISTANCE`) via Camera2 interop (`CONTROL_AF_MODE_OFF` + `CONTROL_LENS_FOCUS_DISTANCE`); AF lock toggle
- Exposure compensation slider, AE lock; white-balance presets if trivially supported
- All controls rendered only when the capability query says the device supports them

**M6 — Service + UI**
- `service/StreamingService.kt` (foreground, camera+mic type, notification with stop action)
- Start/stop UI, settings (resolution/fps/bitrate/port, audio toggle — SharedPreferences), status view with LAN IP + QR code (ZXing core, single small dep), reconnect handling (client drops, encoder restarts, camera switch mid-stream), wake lock, stats (fps/bitrate)

**M7 — Repo hygiene for public release**
- README: build steps, OBS setup guide, camera-control compatibility notes (what varies per device), latency tuning, troubleshooting
- GitHub Actions CI: JDK 21, `assembleDebug` + `testDebugUnitTest`; draft release with APK

Representative new files: `settings.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, and under `app/src/main/java/io/github/wltbagent/droidcam/`: `MainActivity.kt`, `camera/CameraController.kt`, `camera/CameraCapabilities.kt`, `encode/VideoEncoder.kt`, `encode/AudioEncoder.kt`, `rtsp/RtspServer.kt`, `rtsp/Sdp.kt`, `rtp/H264Packetizer.kt`, `rtp/AacPacketizer.kt`, `rtp/RtcpSender.kt`, `service/StreamingService.kt`, `Settings.kt`.

## Verification

1. `./gradlew :app:assembleDebug :app:testDebugUnitTest` clean at every milestone
2. Packetizer/SDP unit tests cover RTP headers, FU-A fragmentation, SPS/PPS injection
3. Encoder milestone: `ffprobe` on pulled `.h264` dump at the selected resolution
4. End-to-end on a physical device (user's phone on same LAN; emulator fallback — will ask when needed): `ffplay rtsp://<phone-ip>:8554/live`, then OBS Media Source; ≥5 min stable
5. Feature checks on device: pinch zoom reflects in OBS, front/rear switch recovers the stream, resolution change visible in `ffprobe`, controls appear/disappear per device capabilities

## Risks / fallbacks

- **Macro behavior varies widely per device** (separate camera vs manual focus vs none) — handled by capability detection; on unsupported devices the control is hidden, documented in README
- **OBS/ffmpeg RTSP pickiness** → correct SDP + RTCP SRs; fallback to RootEncoder behind the same `RtspServer` interface
- **Camera switch / resolution change mid-stream** → encoder restart with in-band SPS/PPS; brief stream restart as fallback
- **Vendor MediaCodec quirks** → configurable profile/level; test on available devices
- **No device attached during dev** → M0–M3 code+tests proceed device-free; end-to-end pauses until a phone is available
- **Wi-Fi latency** → document TCP/UDP tradeoffs and OBS buffer settings; SRT is future scope, not v1