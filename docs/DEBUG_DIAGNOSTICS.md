# NeoForge 1.21.1 diagnostics

These are internal instrumentation controls, not supported public API. They do
not change gravity, collision, attitude, camera, packets or publication ownership.
Options are read once per game JVM. Restart to change instrumentation or filters.

## Entry points

From the repository root (the NeoForge target is an independent Gradle build):

```bat
gradlew.bat -p targets/neoforge-1.21.1 runClient -PgeDebugMovement=true

gradlew.bat -p targets/neoforge-1.21.1 runClient ^
  -PgeDebugView=true ^
  -PgeDebugViewStacks=true ^
  -PgeDebugEntity=<uuid> ^
  -PgeDebugSide=client
```

From `targets/neoforge-1.21.1`, omit `-p targets/neoforge-1.21.1`.
The same forwarding works for `runServer`. It uses the run configuration's
`systemProperty` mechanism; do not put these options in `org.gradle.jvmargs`.

| Gradle run property | Game JVM property | Default |
| --- | --- | --- |
| `geDebugGravity` | `gravityengine.debugGravity` | false |
| `geDebugMovement` | `gravityengine.debugMovement` | false |
| `geDebugSpatialState` | `gravityengine.debugSpatialState` | gravity, unless explicitly overridden; always enabled by movement |
| `geDebugView` | `gravityengine.debugView` | false |
| `geDebugViewStacks` | `gravityengine.debugViewStacks` | **false** |
| `geDebugEntity` | `gravityengine.debugEntity` | no filter |
| `geDebugSide` | `gravityengine.debugSide` | both |

Existing direct game JVM options remain supported, including
`gravityengine.debugGravityVelocityThreshold` (positive finite value, default
0.05) and `gravityengine.failOnGravityInvariant` (false). The latter controls
throwing on invariant failure, not whether correctness warnings are emitted.

Legacy domain booleans select internal `OFF` / `FULL` levels. `ANOMALY` and
`MUTATION` are reserved internal vocabulary, not additional accepted CLI values.
A canonical UUID filters all entity-scoped text before diagnostic capture and
formatting. Side selection uses the entity's logical Level, including separate
client/server actors in an integrated game. Pending packets use their declared
entity UUID and the receiving Level; an unknown side is omitted when a specific
side is requested. Invalid UUID/side settings warn once and fall back to no
entity filter / BOTH. Warnings do not echo arbitrary property contents.

`gravityengine-client.toml` retains `debug.gravityHitboxes` and
`debug.bodyAttitude`. These runtime visualization/HUD controls are independent
of JVM text instrumentation and Mixin selection. F3 gravity text retains its
existing overlay lifecycle. Neither rendering nor logging owns simulation state.

## Mixin inventory and apply decisions

`META-INF/neoforge.mods.toml` registers `gravityengine.mixins.json`, whose
`OptionalCompatibilityPlugin` consults an explicit table of fully qualified
class names. Boot options and that table contain no Minecraft/client linkage.
The existing Sable-presence check is retained.

| Instrumentation | Apply condition | Production owner retained |
| --- | --- | --- |
| `PlayerViewWriteTraceMixin`, `PlayerViewTickDebugMixin` | view | `PlayerMixin` control/pose/attitude and Vanilla scalar writes |
| `CameraViewDebugMixin` | view | `CameraMixin` setup scope, quaternion/basis install and eye position |
| `CameraGravityDebugMixin` | gravity | same camera owner; preserves independent gravity-camera diagnostics |
| `ClientEntityLocalLookDebugMixin` | gravity (legacy domain) | **always-on** client `ClientEntityLocalLookInputMixin.turn` pre-clamp accumulation |
| `EntityVelocityDebugMixin` | gravity | `EntityMixin.setDeltaMovement` discontinuity velocity recording |
| `LocalPlayerMovementDebugMixin` | gravity (legacy domain) | `LocalPlayerMixin` input and body-state send |
| `EntityMovementDebugMixin` | movement | `EntityMixin.collide` custom collision dispatch |
| `ClientPacketViewDebugMixin`, `ServerPlayerViewDebugMixin` | view | native packet handling, teleport and server movement integration |
| `ClientPacketSpatialDebugMixin` | spatial | `ClientPacketListenerMixin` ability-suppression update |

Client instrumentation is listed only in the JSON `client` section. Pure
debug wrappers/injections are absent when their domain is off. The raw mouse
path still forwards `turn` deltas multiplied by Vanilla's 0.15 factor before
pitch clamping, and `ClientBodyAttitudeControl.beginTick` drains them once.
Names alone never determine whether a Mixin is optional.

## Observation ownership and costs

* Boot properties -> immutable options -> plugin selection and target filters;
  no per-tick parsing or mutable global configuration manager.
* Actual move/solve -> invocation-local movement span -> real contact-velocity
  commit -> log -> `finally` release. The target's `MovementDiagnosticSpans`
  uses thread-local weak **identity** keys: Minecraft 1.21.1 `Entity.equals`
  compares numeric entity IDs, so an ordinary `WeakHashMap` can merge distinct
  client/server objects. Map synchronization alone cannot protect a span lifetime.
  All observation and lifecycle calls belong to the owning Level thread; even
  accidental use of the same object on another thread gets an independent store.
  Neither keys nor payloads retain an Entity/Level; queued dead keys are removed
  on subsequent store access, and player leave explicitly clears the entry.
* `sequence` is per (Level thread, entity object identity), increasing across
  discontinuities and restarting after `clear`. It is not a network sequence
  or a shared client/server ordering key. Interpret it with the side and entity
  lifetime. Custom and Vanilla collision calls carry the captured span through
  input/result; unpublished support solves never claim the ordinary move record.
  Velocity observations additionally require the identical published collision result.
* Normal nested closure restores the valid parent. Out-of-order closure retires
  only its token, leaving the child active; restoration skips retired ancestors.
  Duplicate, wrong-object, foreign-thread and invalidated closes publish nothing.
  Discontinuity/clear invalidate the whole old stack, independent of log filters;
  a later `finally` cannot resurrect it. Exceptional moves retire their span
  without publishing a partial move result or swallowing the physical exception.
* `ContactVelocityIntegration` passes the actual committed resolution to the
  matching move result. Diagnostics never run contact projection with a
  synthetic zero velocity. A missing observation is `unavailable`, not false.
* Collision trace lists are enabled only for a filtered, active movement span.
  Step decisions retain primitive branch evidence and allocate an immutable
  snapshot only when a diagnostic reader asks for it.
* Camera instrumentation reads installed output. The old diagnostic-only
  render/frame sampling and reconstructed expected orientation were removed.
  Existing guarded presentation logs observe operands already computed by
  the production presentation resolver.
* Entity, movement, body-state and presentation call sites guard before
  formatting, snapshots, varargs and diagnostic streams. Unavoidable inline
  observation of actual production results uses cheap domain/filter guards;
  disabled view spans return one shared NOOP without a lambda or snapshot.
* Both stack walkers are lazily initialized behind the explicit
  `debugViewStacks=true` guard. There is no `Thread.getStackTrace` fallback.

## Severity and compatibility

Startup emits one restricted INFO summary when any text domain is enabled.
Ownership/lifecycle handoffs use INFO, movement/spatial/packet/body transitions
use DEBUG, and per-write/per-frame/kernel details use TRACE. If the environment
does not enable the requested DEBUG/TRACE logger level, the explicit JVM opt-in
uses INFO transport tagged `[DEBUG]` or `[TRACE]`. No global logging settings are
changed. Existing `[GravityEngine/GravityDebug]`, `[SMR-MOVE-*]`, `[SMR-VIEW]` and
spatial tags remain as legacy log-search compatibility labels, not mod identity.

Diagnostic payloads are internal and may change: camera logs now report actual
installed values instead of a second inferred pose; movement normals are observed
contacts rather than a second support classification; fallback is reported from
the committed response; movement summaries now also work for server and non-player
entities. Consumers parsing these internal log fields may need adjustment.

`GravityInvariant` is **correctness telemetry, not debug tracing**. All debug
domains and filters may be off and violations still warn. Its synchronized LRU
holds at most 1024 rate keys, with a 10-second monotonic repeat interval. Eviction
can permit an earlier repeated report under churn, but storage cannot grow without
bound. Explicit fail-on-invariant mode retains its existing throw-before-warning
behavior and bypasses suppression.

Inventory was based on current 1.21.1 source and call sites, including packet,
attitude, geometry and Sable bridges. The referenced `docs/version-differences/`
directory is absent in this checkout; no other target's locators were used.

## Refactor verification (2026-09-19)

Executed with `JAVA_HOME=C:/Program Files/Java/jdk-21.0.11`; common still compiles
with its Java 17 toolchain and release target.

| Command from repository root | Result |
| --- | --- |
| `gradlew.bat compileJava test --console plain --no-daemon` | PASS |
| `gradlew.bat -p targets/neoforge-1.21.1 compileJava --console plain --no-daemon` | PASS after retry with build network/cache access |
| `gradlew.bat -p targets/neoforge-1.21.1 compileJava test build --console plain --no-daemon` | PASS: 263 common tests, 29 target tests, JVM controls, API import check, JAR |
| `gradlew.bat -p targets/neoforge-1.21.1 controlBoundaryVerification --console plain --no-daemon` | FAIL: existing `DeathGeometryChecks` assertion `Size override committed` |
| Same server command with `-PgeDebugGravity=true -PgeDebugMovement=true -PgeDebugView=true -PgeDebugSide=server` | FAIL at the same assertion; debug Mixins load, one startup summary, real velocity-resolution records |
| `gradlew.bat -p targets/neoforge-1.21.1 runClient -PcontrolBoundaryChecks=true --console plain --no-daemon` | Behavioral FAIL: `same-step swim plan at fixture tick 36`; launcher exits 0, so its build success is not a test PASS |
| Same client command with `-PgeDebugGravity=true -PgeDebugMovement=true -PgeDebugView=true -PgeDebugViewStacks=true -PgeDebugSide=client` | Behavioral FAIL at the same assertion; camera/view/input instrumentation loads without Mixin errors |

Both runtime assertions reproduced in an isolated archive of unmodified HEAD
`76a3c56`, using the same commands with target path
`build/debug-baseline/targets/neoforge-1.21.1`. No production physics or fixtures
were changed to hide these failures. These pre-existing failures prevent claiming
complete runtime behavioral verification. Sable-present runtime and performance
benchmarks were NOT RUN.

Evidence under ignored `build/`: `debug-final-build.log`, `debug-server-off.log`,
`debug-server-on.log`, `debug-client-off.log`, `debug-client-on.log`,
`debug-baseline-server.log`, `debug-baseline-client.log`, `debug-static-audit.txt`.
Static checks and ASM tests verify explicit Mixin selection, client section
isolation, permanent raw input/discontinuity hooks, no diagnostic solver calls,
guarded stack walkers, shared NOOP scopes, bounded invariant storage and unchanged
kernel numerical code outside the step-observation helper. Debug-OFF runtime
logs contain no text diagnostic tags. The remaining `LocalPlayerMixin` list is
the production closest-space escape candidates, not a diagnostic allocation.

## Movement span ownership regression (2026-09-20)

Verified the current checkout against its generated
`build/moddev/artifacts/neoforge-21.1.249-sources.jar`: `Entity.equals(Object)`
compares `id`, and `hashCode()` returns `id`. UUID does not distinguish map keys.
The former global synchronized WeakHashMap therefore shared mutable state between
equal client/server entities; its per-map-operation lock did not protect input,
result, logging and finish as a transaction. In particular, finish could clear
active between result's check and its later assignment. Contaminated diagnostic
velocity is not evidence that the solver generated that velocity.

The implementation and tests stay in the 1.21.1 target. No common physics,
supported API, metadata, collision tolerances, grounding, friction, velocity or
support rules changed. Existing local `BlockFallResponseMixin` and `build.gradle`
edits were preserved. The supplied named source archive was not present in this
checkout; version matching used the checked-out target and actual generated game
sources. The `docs/version-differences/` directory remains absent.

Eight deterministic tests execute the production `MovementDiagnosticSpans`
implementation: equal/mutable-hash keys; latch-controlled client/server interleave
and independent sequences/input/result payloads; foreign-thread access using the
same object; normal nesting and wrong-object close; clear/discontinuity during
nested movement followed by stale finally; out-of-order/duplicate finish;
exceptional finally and the next begin; lazy store creation and explicit weak-key
queue processing without depending on GC. Additional production entry-point and
ASM tests cover debug-OFF/no-active calls, explicit solve-span arguments, one
Vanilla invocation and the unpublished solve's null diagnostic owner.

All commands used `JAVA_HOME=C:/Program Files/Java/jdk-21.0.11` and
`--console plain --no-daemon`. Common compilation retains Java 17.

| Working directory and command | Actual result |
| --- | --- |
| Repository root: `./gradlew.bat -p common clean test` | PASS, 263 tests, zero failures/errors/skips |
| `targets/neoforge-1.21.1`: `./gradlew.bat tasks --all` | PASS; target tasks confirmed |
| Same target: `./gradlew.bat test build` | PASS, 39 target tests, 263 common tests, JVM controls, API import guard, distributable JAR |
| Same target: `./gradlew.bat dynamicsCoreVerification -PgeDebugMovement=true -PgeDebugSide=both` | FAIL, dedicated-server assertion `real aiStep and block response slide on near-vertical physical support` at `ControlBoundaryChecks.releasedInputUsesGroundFriction` |
| Same target: `./gradlew.bat dynamicsCoreVerification -PgeDebugMovement=false` | FAIL at the same assertion with diagnostic instrumentation disabled |
| Same target: `./gradlew.bat runClient -PcontrolBoundaryChecks=true -PgeDebugMovement=true -PgeDebugSide=both`, with process-local `JAVA_TOOL_OPTIONS=-Dgravityengine.movementSupportChecks=true` | Integrated client and custom gravity ran; fixture FAIL at `support client phase=2 tick=35 steep physical contact slides`. Launcher exit 0 is not behavioral PASS. |

The integrated run emitted 250 CLIENT and 390 SERVER input/result pairs. All 640
results matched their same-side UUID/sequence input's requested movement, initial
position, entity tick and collision route; no orphan, duplicate or unmatched
input/result was found. All 600 velocity records (233 CLIENT, 367 SERVER) belonged
to an open same-side record. Both sides executed EXACT_BODY custom solves and
logged on their respective Render/Server thread. No NullPointerException occurred.
This verifies observation ownership in this run; it does not make the failed
steep-slide physical assertions pass or establish a fix for residual bouncing.

Evidence is in the target's ignored `build/` directory:
`movement-diagnostics-common.log`, `movement-diagnostics-build-final.log`,
`movement-diagnostics-dynamics.log`, `movement-diagnostics-dynamics-off.log`,
`movement-diagnostics-client.log`, and `movement-diagnostics-pairing.json`.
Initial sandbox restrictions on wrapper network/cache access were resolved by
running the authorized verification with elevated build access. There is no
remaining build-environment blocker.

Not verified: the original StarminerR gameplay/content setup, optional Sable
runtime, long-duration play, or an in-game dimension/unload/reconnect lifecycle
matrix. Invalidated-span behavior is covered deterministically at the production
state-component level. The remainder of each runtime suite after the failed
physical assertion did not execute. Other version/loader targets were not changed
or built.
