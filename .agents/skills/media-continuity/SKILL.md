---
name: media-continuity
description: Mandatory TVPlayAir skill for AirPlay session arbitration, app/media switching, audio/video/mirroring transitions, metadata freshness, TV UI and latency. Read before every media-related change.
---

# TVPlayAir — Deterministic multimedia continuity

## North star
A CarPlay-inspired Android TV presentation, not CarPlay protocol/API emulation. When the iPhone changes apps, media items or output modes, the TV follows the **latest valid observable AirPlay state** with no stale artwork, progress, frames, audio destination or remote controls. Preserve audio/video quality supported by source, protocol, hardware and Android; never promise zero latency or universal third-party-app/DRM compatibility.

## Required reading and sources
Read `AGENTS.md`, `docs/TVPLAYAIR_CONTINUITY_ARCHITECTURE_2026.md`, `docs/ENGINEERING_SPEC_v1.0.md`, `docs/DEPENDENCY_UPDATE_POLICY.md`, affected implementation and tests. Consult current official Media3 release notes (https://developer.android.com/jetpack/androidx/releases/media3), Android TV playback guidance (https://developer.android.com/media/implement/surfaces/android-tv), Android media sessions and Apple CarPlay/Now Playing docs. Distinguish CarPlay UI inspiration from actual AirPlay protocol capabilities.

## Invariants
1. One serialized orchestrator owns active transport session, media item, presentation mode and generation. A transport session is not the same as a media item.
2. All callbacks and jobs carry a generation/session/media identity. Late artwork, video frames, decoder events, DACP, IPC, network responses and timers cannot mutate current state.
3. UI consumes one immutable snapshot: Idle, Preparing, Audio, Video, Mirror, Recovering or Error. No contradictory independent flags.
4. A new media item invalidates previous metadata and control target before asynchronous work. Partial metadata may merge only for the same media item.
5. New video becomes visible only after confirmed valid first frame; never show previous content. Preparing and Recovering have bounded timeouts and a deterministic exit.
6. Remote commands are capability-checked and bound to the active session, never routed using a stale token or IP alone.
7. AudioFocus, renderer ownership, VPU/decoder capacity, surfaces and stream lifecycle are explicit. Avoid two concurrent 4K decoders without capability evidence.
8. Media preparation, network and artwork decoding never block UI or real-time audio threads. Animations are cancellable and must not gate playback.
9. Disconnect, binder death, replaced session and rapid app switching release/reattach resources safely. Do not treat `playWhenReady` alone as effective playback.
10. Keep a record of observable limitations: app-specific AirPlay, DRM, iOS routing, hardware HDR and codec support.

## Implementation sequence
- Reproduce the failure using an event trace; record base SHA, source, timestamps and active generation.
- Write a failing deterministic regression test before the fix; include events arriving late, duplicated or out of order.
- Implement smallest cause-focused change in the orchestrator, engine or presentation layer. No speculative refactor or UI-only masking.
- Run focused tests, adjacent tests, native stress, build/lint and exact-HEAD CI; report what was and was not executed.
- Measure request-to-audio, first frame, artwork freshness, p50/p95/p99 transition latency, A/V drift, underruns, dropped frames, memory and recovery.
- Validate compatibility claims on real devices when required; CI/emulator is not proof of real AirPlay or VPU behavior.

## Mandatory scenarios
- Same app next track, same transport; old artwork response arrives last.
- Audio app A -> audio app B, with reverse channel from A still active.
- Audio -> video -> audio, rapid and repeated.
- Video A -> video B with stale last frame and delayed first frame.
- Mirror -> direct video -> mirror with decoder release and keyframe gating.
- Pause, seek, stop and DACP commands while a transition is in flight.
- 100+ deterministic simulated transitions; reorder, duplicate and drop selected events.
- Wi-Fi interruption, timeout, process death, Activity recreation, background restrictions.
- D-pad OK, long-press repeat, Back, focus restoration and transient HUD.

## Technology policy
Always evaluate the latest published stable and relevant beta/RC/alpha versions against official documentation. Coordinate AGP/Kotlin/Gradle/SDK/Compose/Media3/Oboe/native dependencies. Upgrade compatible groups together, pin verified versions and checksums, and never label an update complete without passing evidence. Evaluate Media3 MediaSession for Android TV metadata and controls; consider PlayerPool only when profiling proves a benefit. Do not add iOS-only CarPlay APIs to an Android AirPlay receiver.

## Definition of done
No stale-generation state or commands in deterministic tests; no indefinite transition; reproducible build; passing exact-SHA CI; published latency/error measurements and explicit hardware validation status. Distinguish design, implemented, tested and verified. Never claim perfect reliability or zero loss without evidence.
