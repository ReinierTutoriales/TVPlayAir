# TVPlayAir — Agent Instructions

## Product north star

Build an independent AirPlay multimedia receiver for Android TV. The iPhone is the controller/source, not the display pipeline for direct web playback: supported web video must play on the TV through Media3 ExoPlayer. v1.0 has four non-negotiable pillars:

1. **Independent playback:** direct web video on the TV, independent of mirroring the phone screen.
2. **Native presentation quality:** target 4K/60, HEVC 10-bit and HDR passthrough where the TV SoC, decoder, display path, source and Android stack support them. No forced lossy recompression and no OpenGL in v1.0.
3. **Sofa control:** DACP is mandatory; Bluetooth HID is optional/best-effort and must never block core operation.
4. **Replaceable protocol engine:** isolate protocol work in `:session`; AIDL is the control plane and a lock-free SPSC SharedMemory ring is the media data plane. Keep protocol implementation behind stable internal contracts so C can later be replaced by Rust/Kotlin without rewriting presentation/UI.

These are product requirements, not claims that every Android TV device supports every codec, HDR format, bitrate or input feature. Prove each capability on representative hardware before claiming it.

## Non-negotiable continuity-first mandate

The product is a CarPlay-inspired **TV multimedia continuity experience**, not a CarPlay implementation or a collection of separate AirPlay screens. The iPhone drives content and TVPlayAir must track the current supported AirPlay session and media item across rapid audio/video/mirror and app transitions, without stale artwork, metadata, last frames, controls or indefinite black screens. Never infer universal access to third-party iOS app state. The **highest-priority skill** for any protocol, playback, UI, metadata, performance or testing change is `.agents/skills/media-continuity/SKILL.md`; also read `docs/TVPLAYAIR_CONTINUITY_ARCHITECTURE_2026.md`.

A single serialized coordinator must own active transport session, media item identity, generation and presentation state. Every asynchronous callback (artwork fetch, player, binder, reverse channel, decoder, timeout) must be generation-checked. Distinguish transport connection from media item; use explicit preparing/first-frame/playing/recovering states and bounded recovery. Tests must reproduce rapid app/media switches, delayed and reordered events, stale commands and binder death before claiming continuity.

**Update policy supersedes old fixed-version constraints:** follow `docs/DEPENDENCY_UPDATE_POLICY.md`. Evaluate latest published stable and relevant prereleases using official release notes, SDK/AGP/Kotlin/Compose compatibility and exact-HEAD CI. Do not let historical compileSdk/BOM pins block verified migrations; update compatible groups atomically. Never invent versions or checksums.

## v1.0 scope firewall

Only implement the six v1.0 milestones: (1) deterministic Android baseline/CI, (2) AIDL + SharedMemory IPC and native stress tests, (3) Oboe audio pipeline, (4) MediaCodec/ExoPlayer video and VPU handoff, (5) AirPlay/mDNS/DACP session, (6) TV UI/HID and device validation. Do not start v2.0 work until all six are closed: GLSurfaceView/OpenGL PiP or split-screen, USB CDC-NCM, PTP multiroom, persistent HomeKit pairing, or protocol-engine rewrite. Reject unrelated scope creep; record it for later instead of implementing it.

## Deterministic build baseline

Pin and keep aligned in Gradle and CI:
- `minSdk = 29`, `compileSdk = 37.1`, `targetSdk = 35`
- NDK `30.0.16248370`
- CMake `4.1.2`
- Oboe `1.11.0`
- Compose BOM `2026.11.01`

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

1. Read the product goal, relevant skill, implementation, configuration and tests. Check official platform and provider documentation for the exact source-to-TV route. For undocumented behavior, use the comparison method in [Media Continuity](.agents/skills/media-continuity/SKILL.md).
2. Record the base SHA, expected outcome, first failing or missing behavior, route owner and applicable API/configuration. Use the provider's supported integration when it already delivers the outcome. Inspect and reuse the implementation already in the repo.
3. Distinguish a project defect from missing external integration, configuration, dependency resolution, a test fixture or a runner/device issue. For undocumented behavior, distinguish a reproducible observation from a hypothesis.
4. Trace the affected entry points and shared lifecycle once. Group changes that fix the same contract in a reviewable candidate. Add the smallest regression that would have caught the failure.
5. Use stable documented APIs and official samples. Check current release notes and resolved dependency versions for the change. Upgrade compatible groups together when needed, and evaluate previews against a documented benefit.
6. Run focused checks locally, then one full CI pass on the candidate SHA before integration. Repeat a failed check once the identified cause has changed.
7. Report the actual commit, checks, prerequisites and device behavior verified. Separate conclusions from build, tests and device evidence.

## Git and authorship

- Use descriptive, owner-neutral branch names; never put AI/model/tool names in branches.
- Author and committer identity must remain the repository owner, `ReinierTutoriales`. No AI/tool attribution, trailers or metadata.
- Read the relevant Git governance skill before branch, commit, PR or merge operations.

## Skills

Read `.agents/skills/media-continuity/SKILL.md` first for any multimedia work; then the relevant `.agents/skills/` file before work: `tvplayair-v1-architecture` for product boundaries/media architecture; `precise-coding` for implementation; `testing-discipline` for tests and CI; `tv-ui` for focus/layout; `code-quality` for optimization/refactors; `agent-governance` before Git mutations; `no-ai-slop` for prose only.
