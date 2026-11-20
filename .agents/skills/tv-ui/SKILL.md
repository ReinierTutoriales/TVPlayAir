---
name: tv-ui
description: Use for UI, focus, navigation, or layout work. Ensures polished Android TV remote (D-pad) experience for the overlay menu and controls.
---

# TV UI & Remote

- Optimize for Android TV remote: proper focus handling, D-pad navigation, clear focus indicators, and accessibility.
- Prefer Jetpack Compose for TV for the overlay menu and HUD. Keep the two SurfaceViews as the video presentation layer.
- Respect compact TV-oriented design. Avoid heavy animations or memory-intensive views.
- Trace focus in the actual layout. Cover adjacent controls and accessibility state with a focused test when behavior changes.
- Record what still needs a real remote/device check. Do not invent UI behaviors.
- A long press opens UI while the key is still held: the new window receives its repeats and release. Test accordingly.

## Compose for TV

- Use the newest compatible **stable** versions of Compose for TV and TV Material after checking release notes.
- Keep one owner of Back navigation. Do not create competing controllers.
- During any migration, retain existing behavior until Compose passes equivalent state, accessibility, pointer/D-pad and callback tests.
- Validate D-pad routes, menu open/close and audio offset adjustment on emulator or device.
