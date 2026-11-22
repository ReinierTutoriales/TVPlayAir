---
name: precise-coding
description: Use when writing or modifying TVPlayAir code. Requires source-grounded changes, pinned dependency compatibility and preservation of v1.0 media/process contracts.
---

# Precise Coding

1. Read the actual Kotlin/Java/C++/AIDL/build files, callers, tests and engineering spec before editing. Never code from a roadmap alone.
2. Check official Android, AndroidX, Media3, NDK and Oboe documentation for APIs and behavior that are version- or device-dependent.
3. Do not invent APIs, codec capabilities, AirPlay/DACP behavior, ExoPlayer callbacks, Binder semantics, SharedMemory guarantees or Bluetooth support.
4. Keep the v1.0 baseline pinned: minSdk 29, compileSdk 36, targetSdk 35, NDK 30.0.16248370, CMake 4.1.2, Oboe 1.11.0 and Compose BOM 2026.06.01. No dependency may require API 37+. Inspect resolved transitives with Gradle when resolution is unexpected; a BOM does not necessarily govern every AndroidX artifact.
5. Preserve the product boundary: independent direct web playback on TV through Media3; native video presentation; DACP required; HID optional; protocol isolated in `:session`; AIDL control plane; SPSC SharedMemory data plane.
6. Respect the single-VPU-path contract. Release mirror MediaCodec before preparing ExoPlayer; define the reverse transition with codec-config restoration and IDR gating. Never force undocumented operating rates or assume low-latency codec support; query documented capability on API 30+.
7. Oboe must attempt Exclusive where appropriate, explicitly fall back to Shared and recover stream errors using the pinned API. Do not block the real-time callback.
8. SharedMemory must be created in native code with `ASharedMemory_create`. Define FD ownership and close the consumer PFD after mmap. Every detach/generation replacement must munmap and invalidate old access. The producer must never block.
9. Keep v2.0 features out of v1.0: no OpenGL/GLSurfaceView PiP or split-screen, USB CDC-NCM, PTP multiroom, persistent HomeKit or protocol rewrite until all six v1.0 milestones close.
10. Make the smallest change that solves a demonstrated need. Avoid unrelated formatting, speculative abstractions and duplicate code.
11. Add a focused regression test for each functional defect. Never weaken assertions or claim tests/builds passed without an actual run on the exact SHA.
12. Every line must trace to source, a test, an official contract or an explicitly labeled product requirement. State uncertainty rather than guessing.
