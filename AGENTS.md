# TVPlayAir — Agent Instructions

## Product north star

Build an independent AirPlay multimedia receiver for Android TV. The iPhone is the controller/source, not the display pipeline for direct web playback: supported web video must play on the TV through Media3 ExoPlayer. v1.0 has four non-negotiable pillars:

1. **Independent playback:** direct web video on the TV, independent of mirroring the phone screen.
2. **Native presentation quality:** target 4K/60, HEVC 10-bit and HDR passthrough where the TV SoC, decoder, display path, source and Android stack support them. No forced lossy recompression and no OpenGL in v1.0.
3. **Sofa control:** DACP is mandatory; Bluetooth HID is optional/best-effort and must never block core operation.
4. **Replaceable protocol engine:** isolate protocol work in `:session`; AIDL is the control plane and a lock-free SPSC SharedMemory ring is the media data plane. Keep protocol implementation behind stable internal contracts so C can later be replaced by Rust/Kotlin without rewriting presentation/UI.

These are product requirements, not claims that every Android TV device supports every codec, HDR format, bitrate or input feature. Prove each capability on representative hardware before claiming it.

## v1.0 scope firewall

Only implement the six v1.0 milestones: (1) deterministic Android baseline/CI, (2) AIDL + SharedMemory IPC and native stress tests, (3) Oboe audio pipeline, (4) MediaCodec/ExoPlayer video and VPU handoff, (5) AirPlay/mDNS/DACP session, (6) TV UI/HID and device validation. Do not start v2.0 work until all six are closed: GLSurfaceView/OpenGL PiP or split-screen, USB CDC-NCM, PTP multiroom, persistent HomeKit pairing, or protocol-engine rewrite. Reject unrelated scope creep; record it for later instead of implementing it.

## Deterministic build baseline

Pin and keep aligned in Gradle and CI:
- `minSdk = 29`, `compileSdk = 36`, `targetSdk = 35`
- NDK `30.0.16248370`
- CMake `4.1.2`
- Oboe `1.11.0`
- Compose BOM `2026.06.01` (API 36-compatible baseline)

No floating versions, dynamic versions, unreviewed upgrades or dependency that requires API 37+. Before changing a pin, inspect release notes and the dependency graph. Confirm the resolved artifacts, not just declared coordinates. Use `dependencyInsight` for unexpected Compose/AndroidX versions. Do not upgrade the SDK to conceal an incompatible transitive dependency; first identify the dependency introducing it and assess a compatible stable version.

## Stack and architecture

- Kotlin / Android, Gradle Kotlin DSL, Compose for TV overlay.
- Media3 ExoPlayer, MediaCodec, Google Oboe, NDK/C++ and AIDL.
- Isolated `:session` process; main process owns presentation and UI.
- Two native SurfaceViews in v1.0; no OpenGL rendering path.
- SPSC ring: producer never blocks; overflow drops rather than stalls; validate header/version/generation and packet bounds.
- DACP mandatory. Bluetooth HID optional and best-effort.

## Hardware and media contracts

- Treat VPU capacity as a hard resource. Never run two simultaneous 4K HEVC decoders unless the specific device has been measured to support it. Release the mirror decoder before preparing the direct ExoPlayer path; define and test the reverse transition, including SPS/PPS/VPS restoration and IDR gating.
- On API 30+, query the documented MediaCodec low-latency feature before relying on it. Use documented priority fallback where supported. Never force `KEY_OPERATING_RATE = 32767`; do not invent codec capabilities.
- Oboe: attempt Exclusive when appropriate; on failure explicitly reopen Shared. Implement stream-error recovery, including `onErrorAfterClose()`, according to the pinned Oboe API contract.
- SharedMemory is created natively with `ASharedMemory_create`. Transfer FD ownership explicitly. Consumer closes its received `ParcelFileDescriptor` immediately after successful mmap; every detach/generation replacement must munmap and invalidate stale access.
- 4K/60, HEVC 10-bit, HDR10/HLG/Dolby Vision, audio latency and DACP interoperability are device/source/stack-dependent. Never turn a target into a universal guarantee.

## Verification commands and binary gates

Use the Gradle wrapper. Run focused tests first, then relevant neighboring tests. For a functional change, add a regression assertion that fails on the old defect. CI on the exact branch HEAD is the integration gate:
- Code verification: dependency metadata, JVM unit tests, Android native build and native host suites on the exact commit.
- APK assembly is a separate packaging gate when packaging is requested.
- Add relevant unit tests/lint/build checks; report each separately. A green older SHA does not validate a newer SHA.
- Never merge, create the next milestone branch, or begin its implementation while the current milestone gate is red, pending, or attached to another SHA.

Hardware validation is a separate gate for claims CI cannot prove: codec/VPU transitions, HDR metadata, audio sync, DACP device compatibility, Bluetooth HID, long-running stability and process-death recovery. Record device/model, Android/API level, source format, duration, measured results and failures. If no hardware run occurred, mark it pending; do not infer success from compilation.

## Working method

1. Read this file, the relevant skill, implementation, tests, engineering spec and official Android/AndroidX/NDK/Oboe/Media3 docs before changing behavior.
2. Record base SHA, exact failing check or missing behavior, expected outcome, affected consumers and the smallest regression test.
3. Classify failures before editing: product defect, dependency resolution, build configuration, test fixture or runner/environment. Fix the cause, not the symptom.
4. One root cause and one reviewable commit per change where practical. Avoid broad refactors and duplicate tests.
5. Inspect resolved dependencies when version alignment is in question. Do not assume the BOM controls artifacts outside its constraints.
6. Require exact-HEAD CI success before integration. Keep the next milestone blocked until the current milestone is closed.
7. Report evidence, unverified assumptions, residual risks and hardware checks separately. Never claim zero regressions or unmeasured performance gains.

## Git and authorship

- Use descriptive, owner-neutral branch names; never put AI/model/tool names in branches.
- Author and committer identity must remain the repository owner, `ReinierTutoriales`. No AI/tool attribution, trailers or metadata.
- Read the relevant Git governance skill before branch, commit, PR or merge operations.

## Skills

Read the relevant `.agents/skills/` file before work: `tvplayair-v1-architecture` for product boundaries/media architecture; `precise-coding` for implementation; `testing-discipline` for tests and CI; `tv-ui` for focus/layout; `code-quality` for optimization/refactors; `agent-governance` before Git mutations; `no-ai-slop` for prose only.
