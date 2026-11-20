# TVPlayAir — Agent Instructions

## Project
Receptor AirPlay independiente para Android TV. Arquitectura de 3 capas con proceso aislado `:session`, SharedMemory SPSC para datos de video/audio, dos SurfaceView nativos (MediaCodec + ExoPlayer) y control DACP + HID opcional.

## Stack
- Kotlin / Android (Compose for TV overlay)
- Gradle Kotlin DSL
- NDK r27+ / CMake 3.22+
- Media3 ExoPlayer, Google Oboe, MediaCodec
- AIDL + SharedMemory (ASharedMemory_create)

## Verification commands
Use the Gradle wrapper. For a functional change, run the affected unit tests first.
Use `testDebugUnitTest`, `lintDebug` and `assembleDebug` before integration when the environment supports Android builds. CI on the exact head is the integration gate; report local tests and CI separately.

## Priorities
- Keep the app stable, low-latency and predictable on Android TV hardware.
- Respect VPU limits (no simultaneous 4K HEVC decode).
- Isolate protocol crashes in `:session` process.
- Prefer native Android APIs (SurfaceView, MediaCodec, Oboe, SharedMemory). Avoid unnecessary dependencies.
- Do not invent APIs, behaviors or protocol features not present in the codebase or official documentation.

## Target and validation (mandatory)
- Target Android TV and compatible TV boxes on API 29+, with D-pad input.
- Validate on hardware for video decode, HDR, audio sync and process recovery.
- Verify in this order and report only what was run: (1) build and unit tests, (2) emulator or device with D-pad for UI, (3) real Android TV for MediaCodec/ExoPlayer/HDR/SharedMemory claims.
- Never describe a change as performance-improved or latency-reduced without a measurement; never call a flow validated without a run.

## Hard Rules for All Agents (mandatory)
- **Branches**: Never create or push branches whose name contains an AI name, model name, tool name, or any non-owner identifier. Use only descriptive, owner-neutral names.
- **Authorship / signing**: Never commit, sign, or set author/committer to any name, email, or identity that is not the repository owner (`ReinierTutoriales`). Do not use AI names, tool names, or third-party identities in commit messages, trailers, or metadata.
- **Write only what is verified**: Read the relevant files, tests, contracts, and documentation before proposing or writing any change. Every addition must be the minimal change that solves a demonstrated need.
- **Documentation**: Update or create documentation only when it reflects verified current behavior. Prefer updating existing docs over creating new ones.
- **Optimal code**: Prefer the simplest correct solution that matches existing style and patterns. Avoid speculative refactors or changes without measured benefit.

## Skills
Read only the relevant `.agents/skills/` instructions: `precise-coding` for implementation, `testing-discipline` for functional changes, `tv-ui` for focus/layout/overlay, `code-quality` for refactors, and `agent-governance` before Git mutations. Use `no-ai-slop` for prose only (documentation, commit and PR text).

## Contracts that every change must preserve
- SharedMemory ring buffer is SPSC lock-free; producer never blocks.
- MediaCodec is released when ExoPlayer takes the foreground (VPU limit).
- SPS/PPS cache + IDR gate on return to mirror.
- Two native SurfaceViews in v1.0 (no OpenGL).
- DACP is mandatory; Bluetooth HID is optional and fails silently.
- Process `:session` crash must not kill the UI.

## Method and verification
1. Record base SHA, reproducible failure, expected behavior and consumers of the changed code. Read current tests and contracts before editing.
2. Fix one cause per commit when possible.
3. For functional changes, add a test that detects the old defect. Do not weaken existing assertions.
4. Require green CI for the exact SHA before integrating. For video, audio, HDR and process recovery, also record device validation or mark it as pending.
5. Do not promise zero regressions. Distinguish automatic coverage, measurement and residual risk.
