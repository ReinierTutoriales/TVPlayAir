---
name: media-continuity
description: Mandatory TVPlayAir skill for AirPlay session arbitration, app/media switching, audio/video/mirroring transitions, metadata freshness, TV UI and latency. Read before every media-related change.
---

# TVPlayAir — Deterministic multimedia continuity

## North star
A CarPlay-inspired Android TV presentation, not CarPlay protocol/API emulation. When the iPhone changes apps, media items or output modes, the TV follows the **latest valid observable AirPlay state** with no stale artwork, progress, frames, audio destination or remote controls. Preserve audio/video quality supported by source, protocol, hardware and Android; never promise zero latency or universal third-party-app/DRM compatibility.

## Required reading and sources
Read `AGENTS.md`, `docs/TVPLAYAIR_CONTINUITY_ARCHITECTURE_2026.md`, `docs/ENGINEERING_SPEC_v1.0.md`, `docs/DEPENDENCY_UPDATE_POLICY.md`, affected implementation and tests. Consult current official Media3 release notes (https://developer.android.com/jetpack/androidx/releases/media3), Android TV playback guidance (https://developer.android.com/media/implement/surfaces/android-tv), Android media sessions and Apple CarPlay/Now Playing docs. Distinguish CarPlay UI inspiration from actual AirPlay protocol capabilities.

## Choose the supported route first

1. Decide what the user needs to see on the TV: the provider's app, a playable item in TVPlayAir or the iPhone screen. Check the [route table](../../../docs/BASELINE_ESTABILIZACION.md#rutas-de-reproducción) and identify who owns launching the app, playback and remote commands.
2. Check Apple, Google and the provider's current docs, then the sender, receiver and configuration in the repo. Record the applicable API or sample and its prerequisites. An existing provider Cast route requires no TVPlayAir bridge. An official content link can open that item in the provider app; it does not transfer the transport session or playback controls.
3. Wire the smallest supported API/configuration change when TVPlayAir owns the gap. Choose stable documented APIs by default. Require an actual playable URL for direct media and a documented provider content identifier for native handoff. Never infer the provider app, item or active controls from an opaque CDN URL. Preserve the AirPlay paths when the sender uses them.
4. For an observed failure, trace the first broken transition from the sender through session, item and generation to the output. Replay the full transition and its return path in the existing regression before grouping fixes against the confirmed cause.

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

## Evidence-to-fix workflow

1. Start from the current branch SHA and the user's expected outcome. Trace one complete source-to-TV route through discovery, negotiation, session ownership, transport, decoding, presentation and cleanup. Mark the first divergence with its event, clock domain and generation. Use the [stabilization baseline](../../../docs/BASELINE_ESTABILIZACION.md) and preserve only the redacted evidence needed to reproduce it.
2. Consult official platform and provider documentation for supported APIs. For undocumented behavior, compare maintained implementations with a similar route and platform. Start with CastBay for Android TV integration, UxPlay for AirPlay mirroring and FCUP, and Shairport Sync for RAOP timing. Look for newer relevant candidates and verify the exact tag or commit, code path, release notes, tests and observed device coverage. Treat project claims and protocol hypotheses as such. Check license compatibility before reuse.
3. Use the comparison to choose the smallest portable fix. Prefer runtime display, codec and audio capabilities and bounded fallbacks across Android TV devices. Keep a device report as evidence for its own configuration. Reuse project APIs and group fixes that share the same failure mechanism. Remove code only after tracing its production entry points and callers.
4. Encode the failure, affected transition and recovery using a focused regression. Run it and the relevant neighboring checks, then verify CI against the exact candidate SHA. Follow [Testing Discipline](../testing-discipline/SKILL.md) for closure. When implementation is authorized, carry a demonstrated correction through delivery and verification.

## Apply the method to media failures

- **Mirroring:** offer the intersection of the user limit and detected display/decoder capabilities. Treat an advertised codec feature as eligibility and retain a basic startup fallback. Preserve codec config and wait for a valid keyframe on recovery with a bounded deadline. Trace first rendered frame and keep RTSP silence, EOF and explicit TEARDOWN distinct.
- **Direct video:** distinguish manifest exchange, transport response, Media3 load and first frame. Carry FCUP scope and status through the proxy and correlate by playback and request ID. Keep sender-facing live position continuous across sliding HLS windows while the local TV controls retain window coordinates.
- **A/V timing:** follow the sender timestamp through reorder, decode and PCM transport to the audio device's monotonic presentation clock. Check generation, clock domain and freshness before scheduling video. Keep timing queries outside the real-time callback and keep immediate presentation available when the mapping cannot be used.
- **UI and controls:** use shared presentation components and keep command meaning consistent. Validate availability and route by the active session. Clear stale artwork, controls and frames on replacement.

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
