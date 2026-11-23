---
name: agent-governance
description: Mandatory rules for all agents. Enforces owner-only Git identity, evidence-first changes, deterministic build pins, scope control and exact-SHA integration gates.
---

# Agent Governance

These constraints override convenience and apply to every task.

## Before any change

- Read `AGENTS.md`, this skill, the relevant product skill, implementation, tests and current contracts.
- Record base SHA, reproducible failure or unmet requirement, expected behavior, affected consumers and the smallest meaningful regression test.
- Classify failures as product, dependency, build configuration, test fixture or environment before changing anything.
- Do not make speculative edits, cosmetic churn, duplicate tests or broad refactors to solve a narrow failure.

## Git identity and branches

- Use short, descriptive, owner-neutral branch names. Never use AI/model/tool names or identifiers in branch names.
- Author and committer must be the repository owner `ReinierTutoriales`; no AI/tool names, third-party identities, trailers or attribution in commit metadata.
- Commit messages state the technical change, not who performed it.
- Do not merge or create the next milestone branch while the current exact-HEAD gate is pending or failing.

## Deterministic dependency governance

Read the active build pins and follow the [dependency update policy](../../../docs/DEPENDENCY_UPDATE_POLICY.md). Use fixed versions and verified checksums. Any proposed change must:
1. identify the requirement that necessitates it;
2. inspect official release notes/API compatibility;
3. inspect the resolved dependency graph, including transitives and artifacts outside BOM control;
4. verify the minimum compile SDK and runtime compatibility of the selected artifacts;
5. update Gradle and CI consistently and prove the exact new SHA in CI.

Do not raise compileSdk to hide an incompatible transitive dependency without an explicit product decision.

## Evidence and documentation

- Every code or policy addition must trace to current source, a test, an official contract or an explicitly labeled product requirement.
- Distinguish desired behavior from implemented behavior and measured device capability.
- Do not invent APIs, guarantees, benchmark results or hardware compatibility.
- Update existing documentation only when it describes verified current behavior or a clearly labeled requirement. Prefer concise edits over new documents.
- Keep claims falsifiable: name the command, SHA, test, device or measurement that supports them.

## Integration gate

- Dependency metadata, JVM unit tests, Android native build and native host suites must pass on the exact PR HEAD.
- Verify `assembleDebug` separately when APK packaging is requested.
- Run relevant unit tests and lint; report their outcomes independently.
- A previous green SHA, a queued/running job, or a green job for another branch is not a pass.
- No squash merge, next-branch creation or next-milestone implementation until the current gate is green.
- Device-only requirements remain open until tested on real Android TV hardware.

## Minimality

Prefer one root cause per commit, the smallest regression test that detects it, no weakened assertions, and the least code that satisfies the verified requirement. Do not optimize by intuition; measure latency, memory, throughput or frame timing before claiming improvement.
