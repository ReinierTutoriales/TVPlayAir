---
name: code-quality
description: Use when optimizing, cleaning, or reviewing code. Prioritizes simplicity, low-latency behavior, maintainability, and TV remote usability.
---

# Code Quality

- Analyze for real improvements in clarity, performance, memory use, latency and remote navigation.
- Prefer the simplest correct solution that matches existing style and keeps the app small/predictable.
- If a superior alternative exists, choose the most production-solid one for Android TV; state what improved.
- Keep changes focused and measurable against TV hardware constraints (VPU, memory, Binder).
- Do not write for the sake of writing.
- Verify against existing tests and code before considering the change complete.
- Update documentation only when it reflects verified current behavior.
- Note any remaining need for real Android TV device validation.
- Follow agent-governance rules on branches and authorship.

## Replacing older code

Replace an implementation only when the alternative improves security, correctness, performance, maintainability or compatibility from minSdk to targetSdk, and say which. Check the current official Android/AndroidX reference first.
