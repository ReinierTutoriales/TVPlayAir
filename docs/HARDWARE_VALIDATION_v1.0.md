# TVPlayAir v1.0 — Hardware Validation Checklist

CI validates native SPSC ring integrity, audio PCM pipeline behavior, JVM unit tests and the Android native build, but **cannot** prove physical Android TV hardware behavior. Record every run below before claiming device support.

## Test Environment
- **TV / Box Model & SoC:** `PENDING`
- **Android / API Level:** `PENDING`
- **Apple Source Device & iOS Version:** `PENDING`
- **Network (Wi-Fi band / Ethernet):** `PENDING`
- **APK Git SHA:** `PENDING`

## Mandatory Hardware Gates

| Check | Expected Behavior | Status | Measured Notes |
|---|---|---|---|
| **1. Direct Web Video (4K HDR)** | `POST /play` launches Media3 ExoPlayer on native `SurfaceView`; TV switches to HDR10/HLG/Dolby Vision without OpenGL conversion | `PENDING` | |
| **2. Single-VPU Handoff** | `MirrorDecoder` (`stop()` + `release()`) completes before `ExoPlayer.prepare()`; no decoder exhaustion crash | `PENDING` | |
| **3. Return to Mirror (SPS/PPS + IDR)** | Stopping ExoPlayer recreates `MirrorDecoder`, reinjects cached SPS/PPS/VPS, drops non-IDR frames, zero green screen | `PENDING` | |
| **4. Oboe Audio & Recovery** | Logs granted `AudioApi` / `SharingMode` (`Exclusive` or `Shared` fallback), `framesPerBurst * 2`, zero audible clicks, recovers HDMI/BT route disconnect via `onErrorAfterClose()` | `PENDING` | |
| **5. Audio Offset & AudioFocus** | Live `-200..+200 ms` adjustment from Compose TV menu; clean focus yield to ExoPlayer and restore on return | `PENDING` | |
| **6. DACP Remote Control** | TV remote Play/Pause/Next/Prev/Seek control iOS playback; `GET /playback-info` reflects live ExoPlayer telemetry | `PENDING` | |
| **7. Optional Bluetooth HID** | If TV supports `BluetoothHidDevice`, D-pad navigates host; if unsupported, degrades silently with zero impact on DACP/playback | `PENDING` | |
| **8. `:session` Crash Recovery** | `adb shell am crash com.tvplayair.receiver:session` triggers `DeathRecipient`, stops engines before `munmap()`, reconnects with exponential backoff (`250–2000 ms`) | `PENDING` | |
| **9. 30-Minute Soak Test** | Continuous playback/mirroring for 30 min with bounded memory, no thermal/VPU hang, zero uncaught crashes | `PENDING` | |
