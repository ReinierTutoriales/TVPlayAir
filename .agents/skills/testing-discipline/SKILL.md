---
name: testing-discipline
description: Use for TVPlayAir product changes, dependency/build fixes and milestone closure. Enforces regression coverage, exact-SHA CI gates and separate hardware evidence.
---

# Testing Discipline

## Before testing

- Record base SHA, changed behavior, reproducible failure, expected result and affected consumers.
- Read the existing test and build setup. Choose the smallest test that would fail before the fix.
- Classify the failure first: product logic, dependency graph, Gradle/SDK configuration, test fixture or CI runner/environment.
- Do not change unrelated product code or weaken assertions to make CI green.

## Required verification

- Run the focused test first, then affected neighboring suites.
- Run `testDebugUnitTest`, `lintDebug` and `assembleDebug` when supported locally; state clearly when unavailable.
- Inspect the exact GitHub Actions run and SHA. A queued, in-progress, cancelled, failed or older-SHA run is not a pass.
- Integration gate: dependency metadata, JVM unit tests, Android native build and native host suites on exact PR HEAD.
- Verify `assembleDebug` separately when APK packaging is requested.
- Run native tests for SPSC correctness: concurrent producer/consumer, wrap-around, full-buffer nonblocking drop, packet integrity, oversized packet handling and generation replacement.
- Inspect resolved dependencies when a build fails due to minimum compile SDK. Use `dependencyInsight` to identify the path introducing the incompatible artifact; do not guess from declared dependencies.

## Hardware-only acceptance

CI and emulators cannot prove hardware decoder behavior, VPU coexistence, HDR passthrough, Wi-Fi throughput, audio callback stability, DACP interoperability or Bluetooth HID support. For relevant milestones, record:
- exact TV/SoC/model and Android/API version;
- codec, resolution, frame rate, bit depth and HDR format;
- test duration and network conditions;
- measured frame drops, latency, audio offset, memory and crash/recovery behavior;
- pass/fail and reproducible steps.

If hardware was not tested, mark it pending. Never convert a target requirement into a claim of device support.

## Failure-to-regression workflow

1. Preserve the failing log and exact SHA.
2. Identify the first causal error, not the last cascade message.
3. Reproduce it with a minimal fixture or command.
4. Add the smallest assertion that detects the original defect.
5. Apply one cause-focused fix.
6. Re-run the focused test and required suites.
7. Require results for the new exact SHA. Never reuse a prior green result.

## Reporting

Separate: expected behavior, tests run, CI result and SHA, hardware measurements, delivery/merge state, and remaining risks. Use explicit `PASS`, `FAIL`, `PENDING` labels. Do not promise zero regressions or describe unmeasured work as faster, smoother or lower-latency.
