---
name: testing-discipline
description: Use when changing product logic, adding features, or fixing regressions in TVPlayAir. Selects relevant tests and records what CI and device checks actually covered.
---

# Testing Discipline

- Identify the changed behavior and run its focused test first; require an assertion that detects the old defect for functional fixes.
- Run affected neighboring suites. Do not weaken existing assertions.
- Before integration, check the exact SHA in CI for unit tests, lint and a debug/release build. Record any unavailable check rather than treating it as passed.
- For MediaCodec, ExoPlayer, HDR, audio sync, SharedMemory or process recovery, name the physical Android TV check still needed; unit tests do not validate the device path.
- Report expected behavior, automatic checks, device measurements and delivery state separately.

## Convert failures into regression coverage

- Turn a reproducible error into the smallest fixture that exercises the failing behavior, then check the correction and adjacent contracts.
- Classify a failed check before acting: product logic, test fixture, build or environment. Do not weaken an assertion or change unrelated product code merely to make the run green.
- After any code change, require results for the new SHA rather than reusing an older green.
