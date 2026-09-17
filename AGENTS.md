# AGENTS.md

## Purpose and scope

GravityEngine provides reusable gravity fields, movement/collision kernels, body-attitude control, angular dynamics and Minecraft integration. StarminerR is an external content consumer; its blocks, world generation and gameplay policy remain outside this engine. Preserve the `cc.sighs.gravityengine` / `gravityengine` identity.

This file defines long-term ownership and dependency contracts. Package layout, algorithms, body shapes, supported movement modes and adapter coverage may evolve while preserving these contracts. A current limitation is not a permanent prohibition; an existing implementation is not proof of correctness.

Use the current checkout, its version-matched Minecraft/loader contracts and reproducible evidence. Treat old plans, comments and previous acceptance reports as context. When implementation and a maintained contract disagree, explain the discrepancy and either repair the implementation or explicitly revise the contract and its tests. Do not silently turn a discovered bug into policy.

## Before changing code

1. Inspect applicable instructions, affected source, callers, tests and build configuration. Check `git status` when Git metadata exists; preserve unrelated edits. A source archive may omit Git metadata, documentation and scripts.
2. Identify the target/version and whether the change affects the common kernel, platform integration or supported consumer API.
3. For stateful work, identify the producer, authoritative owner, consumers, lifetime and invalidation events. State which values are durable, which are transient evidence, and what event makes absence authoritative after reload. Reuse an existing owner when it already expresses the required semantics.
4. Read the matching Minecraft/loader/optional-mod implementation or transformed bytecode before changing a Mixin seam. Verify descriptors, locals, slices and callback ordering for that version.
5. Make a coherent change with explicit failure behavior and focused validation. Avoid unrelated cleanup, forwarding layers and duplicate state created only to satisfy a layout preference.
6. If a deliberate contract correction makes an existing test assert obsolete behavior, delete that conflicting test rather than preserving the obsolete contract through compatibility code. Normally replace it with focused coverage of the corrected contract; an explicit task instruction not to add tests takes precedence. Never delete tests merely because they fail.

## Required architecture contracts

Before changing an affected subsystem, read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
Its ownership, provider coverage, geometry, attitude, persistence, network and
failure contracts are part of these instructions. They were moved there without
weakening their requirements. Do not treat a current algorithm as a permanent API.

- Common production stays Java 17 and platform-neutral. Target code owns live
  worlds, lifecycle, Mixins, mutation and optional integrations.
- Public compatibility is limited to supported API packages and verified targets;
  Java public visibility alone grants no promise. Maintain API docs and fixtures.
- One mutable fact has one owner. Keep durable assignment, live FIELD evidence,
  evaluated gravity, committed application, geometry and transport distinct.
- FIELD completeness is per query across every expected provider. Incomplete
  evidence retains the authoritative assignment; registry emptiness is no proof.
- Position, collision orientation, attitude, semantic aim and presentation have
  independent authority. An operation owns its captured scene and work budget.
- Dynamic attitude has one momentum owner and one atomic integrator. Policies
  contribute torque; presentation cannot write physical state.
- Reuse native attachment and platform lifecycle semantics. Do not introduce
  duplicated readiness, persistence, clone or network-lifetime protocols.
- Expected coverage/budget failures reject or defer according to the operation.
  Unexpected failures roll back and propagate, preserving original causes.

## Verification and delivery

Follow [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md), including its binding verification
and delivery rules and change/evidence matrix. Inspect target metadata and use the
matching wrapper/JDK. Build, JVM controls, transformed servers, client smoke and
performance measurements prove different things; report PASS, FAIL, BLOCKED and
NOT RUN accurately. Never reuse stale PASS files.

Test behavior and ownership, not class counts or layout. Do not weaken thresholds,
exclude failures or retain compatibility branches solely for obsolete tests.
Delete tests asserting a deliberately superseded contract, retain valid cases,
and replace with focused coverage unless the task explicitly forbids new tests.

Use [docs/API_BOUNDARY.md](docs/API_BOUNDARY.md) for consumer migrations and
[docs/REFACTOR_STATUS.md](docs/REFACTOR_STATUS.md) for checkpoint evidence.
Do not put task inventories or performance claims in this file. No automatic
push, release tag or remote publication follows from a refactor request.
