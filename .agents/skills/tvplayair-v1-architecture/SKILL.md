---
name: tvplayair-v1-architecture
description: Mandatory product and architecture guardrails for TVPlayAir v1.0. Use before planning milestones, changing media pipelines, IPC, protocol boundaries or product scope.
---

# TVPlayAir v1.0 — Product and Architecture Guardrails

## Product outcome

Deliver an independent AirPlay multimedia receiver for Android TV with four fixed outcomes:

- **Independent playback:** web video selected from the mobile device plays directly on the TV through Media3 ExoPlayer; the phone is not the video display path.
- **Native-quality target:** pursue 4K/60, HEVC 10-bit and native HDR passthrough without forced lossy recompression. v1.0 uses native SurfaceView/SurfaceFlinger presentation and no OpenGL path.
- **Remote control:** DACP is required for the supported AirPlay control flow. Bluetooth HID is optional/best-effort and must fail without taking down playback.
- **Replaceable protocol engine:** protocol/network implementation is isolated in `:session`. AIDL carries control/events and FD handoff; SPSC SharedMemory carries media payloads. Presentation/UI must not depend on protocol-engine internals.

These are product targets. Hardware support varies by SoC, firmware, Android release, decoder, display and source. Never promise universal 4K/60, HDR/Dolby Vision, codec or HID support without device evidence.

## Process and data boundaries

- Main process owns UI, presentation, MediaCodec/ExoPlayer surfaces and audio output.
- `:session` owns discovery, protocol/session handling and media production.
- AIDL is control plane only; SharedMemory is the high-volume media data plane.
- Ring buffer is strictly SPSC and lock-free. Producer never waits for consumer; full buffer drops according to the defined policy.
- Ring header must be versioned and generation-scoped. Validate magic/version/generation and every payload length before access.
- Place write/read positions on separate cache lines (`alignas(64)`) and use correct atomics/memory ordering; prove behavior with native stress tests.
- Define FD ownership at every handoff. Consumer closes received PFD immediately after successful mmap. Detach, binder death and generation replacement must munmap, invalidate pointers and prevent stale callbacks.
- Binder death recovery must detach old resources, unbind and reconnect with bounded exponential backoff. Do not reuse stale binder or memory generations.

## Video and VPU

- Only one 4K HEVC decode path may be active unless a target device is measured to support more.
- Before direct web playback, stop/release the mirror MediaCodec before preparing ExoPlayer. Do not wait for ExoPlayer's first rendered frame while retaining a second 4K decoder.
- ExoPlayer owns direct web playback on the TV. Preserve native HDR-capable surface path; do not add OpenGL conversion/composition in v1.0.
- Query documented MediaCodec low-latency feature support on API 30+ before enabling it. Use documented priority fallback as appropriate. Never force `KEY_OPERATING_RATE = 32767`.
- On return to mirror, recreate/configure MediaCodec, restore cached codec configuration (SPS/PPS/VPS as applicable) and discard output until a valid IDR/keyframe gate is satisfied.
- Model transitions explicitly: mirror active, switching to direct, direct active, switching to mirror, recovering/error. Define cancellation, prepare failure and surface destruction behavior.
- Validate on hardware: decoder release order, first-frame latency, drops, HDR metadata, memory and 30-minute stability.

## Audio

- Normalize supported incoming audio to the specified PCM format before writing to the audio ring; verify actual supported input codecs and FFmpeg configuration.
- Oboe attempts Exclusive where appropriate, explicitly retries Shared if Exclusive cannot open, and recovers stream errors through the pinned API including `onErrorAfterClose()`.
- Real-time callback must not block, allocate unpredictably, perform I/O or acquire contended locks.
- Audio offset adjustment is bounded and testable; AudioFocus coordinates mirror audio and ExoPlayer transitions.
- Validate drift/offset and underruns on actual TV hardware. A successful build is not audio validation.

## DACP and Bluetooth HID

- DACP is mandatory for the product's remote-control contract. Implement discovery/token handling and commands only as supported by the verified protocol behavior.
- Bluetooth HID is capability-checked and optional. If profile/service support is absent or registration fails, disable HID cleanly; never make it a dependency for DACP or playback.
- Test real device interoperability; do not infer support from Android API presence alone.

## Deterministic build pins

- minSdk 29; compileSdk 36; targetSdk 35.
- NDK 30.0.16248370; CMake 4.1.2.
- Oboe 1.11.0; Compose BOM 2026.06.01.
- No floating versions or dependencies requiring API 37+. Resolve and inspect the complete dependency graph; use Gradle dependencyInsight for unexpected Compose/AndroidX artifacts.
- Change pins only with explicit compatibility evidence and CI proof. Do not upgrade compileSdk as a workaround for a dependency mismatch.

## v1.0 scope firewall

Six milestones, in order:
1. Android baseline and deterministic CI.
2. AIDL + native SharedMemory SPSC + binder recovery + native stress CI.
3. Oboe audio consumer, fallback/recovery, offset and AudioFocus.
4. Mirror MediaCodec + direct ExoPlayer + VPU state machine.
5. mDNS/AirPlay session isolation and DACP reverse control.
6. Compose TV overlay, optional HID and hardware acceptance.

Do not start a later milestone until the previous milestone's exact-HEAD gate is green and its deliverable is verified. Keep these v2.0 ideas out of v1.0: GLSurfaceView/OpenGL PiP or split-screen, USB CDC-NCM, PTP multiroom, persistent HomeKit pairing and protocol-engine rewrite.

## Evidence hierarchy

- Source code/tests establish what is implemented.
- Official versioned Android/AndroidX/Media3/NDK/Oboe documentation establishes API contracts.
- Exact-SHA CI establishes only the checks actually run.
- Emulator validates only behavior it can faithfully represent.
- Physical Android TV testing establishes device-specific codec, HDR, VPU, audio, DACP, HID and long-run behavior.

Every milestone closes with a verifiable artifact: green exact-SHA CI, native test result or recorded hardware test. Additional documentation alone does not close a milestone.
