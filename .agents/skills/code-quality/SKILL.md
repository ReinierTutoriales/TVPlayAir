---
name: code-quality
description: Use when optimizing, cleaning, or reviewing TVPlayAir. Prioritizes correctness, bounded resource use, low latency and Android TV hardware constraints.
---

# Code Quality

- Optimize only a demonstrated bottleneck or defect. State the baseline, measurement method and result; otherwise describe the change as a correctness or maintainability improvement, not a performance win.
- Prefer the smallest correct implementation that matches project patterns and keeps the app predictable on low-memory TV hardware.
- Account for VPU decoder slots, native heap, SharedMemory capacity, Binder traffic, network-thread blocking, audio callback deadlines and Surface lifecycle.
- Never block the network/media producer on a full ring buffer. Keep SPSC ownership single-producer/single-consumer and prove memory ordering and wrap-around behavior with native tests.
- Keep protocol parsing/session work isolated in `:session`; do not leak protocol-specific details into UI or presentation contracts.
- Keep DACP mandatory and Bluetooth HID optional/best-effort. Failure of optional HID must not affect playback or receiver availability.
- Preserve native SurfaceView presentation and v1.0's no-OpenGL constraint.
- Do not introduce dependencies, abstractions, allocations, copies, threads or callbacks without a demonstrated need.
- Verify compatibility against the active minSdk, targetSdk and compileSdk from the build.
- Check current official Android/AndroidX/Media3/NDK/Oboe documentation before replacing APIs or relying on codec/OS behavior.
- Report residual device validation explicitly; CI cannot establish HDR passthrough, VPU capacity, audio latency or Bluetooth stack behavior.
- Follow `agent-governance` for all Git mutations.

## Media and resource transitions

- Model mirror ↔ direct-web playback as an explicit state machine with one owner of the active VPU decode path.
- Release the mirror MediaCodec before preparing ExoPlayer; do not rely on simultaneous 4K HEVC decode.
- On return to mirror, restore required codec configuration and gate output until a valid IDR frame. Test transition ordering, cancellation and failure paths.
- For Oboe, implement Exclusive-to-Shared fallback and stream recovery using the pinned API. Never perform blocking work in the real-time audio callback.
- For SharedMemory, define FD ownership, map/unmap lifetime, generation invalidation and bounded packet handling. No stale pointer may survive detach or generation change.

## Replacing older code

Replace an implementation only when the alternative improves security, correctness, resource use, maintainability or compatibility. Identify the old defect and verify the replacement against actual code, tests and official references. Avoid speculative refactors and duplicated abstractions.
