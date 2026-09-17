# 120656 correctness fixes and verification

Date: 2026-09-19. Minecraft 1.21.1 / NeoForge 21.1.249 / Sable 2.0.5.

## Scope and result

The current checkout already contained later, uncommitted transport, large-primitive,
velocity-provenance and Sable integration work. Those changes were preserved.
The 120656 review, evidence ZIP and baseline ZIP were unavailable; implementation
used the user's detailed reproduction, current sources and the crash report.
The earlier 095208 prompt and audit archive were also retained.

The four reported correctness problems are fixed and covered by transformed
Minecraft server tests: nested death/dimensions refresh, native wide/short bodies,
movement/packet body disagreement, and debug capsule casting. No public API,
server validation, common Java baseline or optional dependency boundary was relaxed.

This is **not full Sable/client acceptance**. Both actual client runs reached the
world, passed phase 0, then failed the same tangential-support assertion at phase 1,
tick 93. Unsupported Sable scaffolding and kinematic contraptions remain rejected.

## Root causes and changes

| Problem | Root cause | Implementation and behavior |
| --- | --- | --- |
| Death inside move | `EntityMixin.gravityengine$refreshDims` rejected every refresh during movement before Vanilla could run, including equal dimensions. | The existing movement owner opens a nested geometry scope. Vanilla and Size run once. A finally block installs the resulting dimensions/eye height/body/proxy, supersedes the old move result, and invalidates persistent support. Refresh is synchronous and exceptions propagate. |
| Wide/short native actors | `GravityEntityGeometry.body` constructed a capsule whenever the collision engine owned the operation. | Physical shape now depends on installed geometry: native AABB becomes an exact box; custom reference geometry remains a real capsule. A 1.4 x 0.9 spider retains its dimensions. Provider relevance determines solver ownership, not body type. |
| Packet/movement disagreement | `VanillaBodyOccupancy.capturePhysicalBody` inferred shape from an operation-dependent collision route before a scene existed. | Packet capture delegates to the same physical-body factory as movement and auxiliary queries. Shape selection does not read providers or Sable again. Existing frozen rigid publication, budgets, fail-closed behavior and packet correction semantics remain in place. |
| Debug crash | `GravityCollisionEngine` cast a capsule to `OrientedBox` before diagnostic logging. | `MovementCollisionDiagnostics.input` accepts `CollisionBody`, logs the actual shape, and derives separate display bounds. Display bounds never replace solver geometry. Remaining unconditional body-to-OBB casts were checked. |

### Ownership and synchronous commit

| State | Producer -> owner -> consumer | Commit or invalidation |
| --- | --- | --- |
| Pose, dimensions, eye height | Native pose/data callback and NeoForge Size event -> Vanilla refresh -> geometry installation | Callback executes once; DYING is retained; no Size event bypass. |
| Exact body, proxy, reference axis | Current dimensions, installed axis and position -> geometry owner -> solver, queries and packet | Rebuilt in nested scope finally before refresh returns, including exceptional exit. |
| Move result, contact/cache publication | Frozen operation scene and solver -> movement owner | `supersedeMovement` retires the pre-refresh result. Superseded operations cannot reuse their cache or republish stale support at outer close. |
| Body revision | Actual dimension or axis change -> runtime | Equal dimensions do not create a false dimensions revision; movement evidence still becomes stale. |
| Support and velocity credit | Terminal witness -> runtime -> next operation and qualified transport | Cleared on geometry discontinuity; outer close does not restore stale support or manufacture release momentum. |
| Actual actor body | Installed geometry -> `GravityEntityGeometry.body` -> all collision consumers | Native box or actual custom capsule, independent of provider discovery. |
| Packet old/new occupancy | Packet owner -> shared body capture -> frozen rigid occupancy checks | Both checks retain the same rigid publication. Existing correction and teleport rules remain enabled. |
| Rigid-body gravity / passenger gravity | COM field bridge / actor assignment -> separate owners | Zero COM gravity does not imply zero passenger gravity. Physics-passenger tests assign passenger gravity explicitly. |

Controlled synchronous nesting was selected to preserve Vanilla's observable
refreshDimensions contract: dimensions and geometry agree as soon as the call
returns. Existing supersession/outer-close discontinuity handling retires old
movement publications. Try-with-resources releases scopes on normal and exceptional
exit; callbacks, damage, death and Size events are not replayed.

An assignment can request a new axis before a geometry/movement owner installs it.
Body capture follows the installed axis throughout that interval. The body-switch
packet regression explicitly crosses this commit boundary.

## Production-path regressions

`DeathGeometryChecks` uses real Cow subclasses delegating to native checkFallDamage,
hurt, die, pose and SynchedEntityData behavior. It covers fatal and nonfatal falls,
death outside move, equal-size refresh, a Size event that changes dimensions,
a callback throwing after refresh and a throwing Size listener. Entities enter the
actual server world and receive subsequent native ticks. Tests check event counts,
DYING, dimensions, eye height, exact/proxy coherence, cleared scopes and absence of
stale move/support publication. A 40-tick deadline prevents a fixture from passing
without actually ticking. These are not death-clone tests. NeoForge logs the
expected throwing-listener sentinel; the test catches that sentinel, not production.

`BodyAuthorityChecks` exercises default spiders, default players and installed
custom capsules, unrelated/relevant provider regions, movement out and back in,
body switches and provider removal. It checks collision endpoints, selected shape
and native callback counts. Actual ServerGamePacketListenerImpl.handleMovePlayer
calls reproduce the narrow corner obstacle: default boxes block while the custom
capsule clears it. The fixture observes both Vanilla position correction and the
GE body payload's non-null correction (metadata-only broadcasts do not count).
The same packet entry checks run against a real Sable wall and after its removal.
Real Sable ceiling collisions cover both box and capsule upward movement.

The existing RigidMovementChecks and SableRuntimeChecks were run before changes.
Their single transport consumption, large-primitive, carry/release provenance,
COM-only gravity and optional-Mixin checks remain passing. Added coverage includes:

- Assigned and unassigned elevator, translation and rotation cases; acceleration,
  reversal, stop, external impulse, jump and blocked reverse movement.
- Support source switching, epoch invalidation and removal. Pure preflight does
  not mutate persistent state; the next movement owner revalidates and commits it.
- Actual Sable SubLevelPhysicsSystem.tick(container) with Rapier velocity changes,
  rather than manually changing each platform pose. A passenger follows successive
  speeds 2, 4, -2 and 0 blocks/second, then walks off and releases support/credit.
- Existing publication, geometry change and source-removal lifecycle coverage.

Sable 2.0.5 source inspection confirms scaffolding uses an actor-specific
SubLevelEntityCollisionContext, transformed actor foot position and Shift state.
The current neutral provider query cannot represent that context correctly.
ServerLevelPlot.getContraptions() contains independently moving geometry that
cannot safely be published as static plot blocks. Rejection remains fail-closed;
these paths were not silently converted into absent or static geometry.

## Commands and actual results

Common uses Java 17 output (classfile 61); this target uses JDK 21.0.11 and classfile
65. All commands use the repository wrapper and the independent target project.

| Command | Result and log |
| --- | --- |
| `./gradlew.bat -p targets/neoforge-1.21.1 -PsableChecks=true dynamicsCoreVerification --console plain --no-daemon` (before edits) | PASS, `build/120656-baseline.log` |
| `./gradlew.bat -p common clean test --console plain --no-daemon` | PASS: 256 tests, zero failures/errors/skips. `build/120656-common-final.log` |
| `./gradlew.bat -p targets/neoforge-1.21.1 dynamicsCoreVerification build --console plain --no-daemon` | PASS: 17 target JUnit tests, JVM checks and transformed dedicated server. `build/120656-no-sable-final.log` |
| Same target command with `-PsableChecks=true` | PASS with actual Sable/Rapier. `build/120656-sable-final.log` |
| Same Sable command with `JAVA_TOOL_OPTIONS=-Dgravityengine.debugMovement=true` | PASS, including strengthened native next-tick checks. `build/120656-debug-final.log` |
| `JAVA_TOOL_OPTIONS=-Dgravityengine.movementSupportChecks=true`, `./gradlew.bat -p targets/neoforge-1.21.1 -PcontrolBoundaryChecks=true runClient --console plain --no-daemon` | World entered, phase 0 PASS, phase 1 tick 93 FAIL. `build/120656-client-native.log` |
| Same client command with `-PsableChecks=true` | Same behavioral FAIL. `build/120656-client-sable.log` |
| Same no-Sable client command with both movementSupportChecks and debugMovement enabled | Same FAIL, diagnostic trace in `build/120656-client-support-trace.log` |

Existing named assertion counts are control-boundary 2264, API-boundary 94 and
move-interop 11. The new named runtime checks are additional and are not included
in 2264. JUnit counts were read from XML, not copied from earlier review evidence.
A Gradle client BUILD SUCCESSFUL is not behavioral acceptance: the fixture result
and AssertionError mark these client runs failed.

The client trace shows a real capsule losing support after a requested upward
world-Y movement of approximately 0.000290469 at the end of tangential deceleration.
The solver reports UPWARD_INTENT. This localizes the failure but does not establish
the upstream root cause; no epsilon, support assertion or collision check was
weakened to hide it. The earlier full client suite's swim failure was not retested
by this focused movement-support run.

## Artifact identity

Final JAR: `targets/neoforge-1.21.1/build/libs/GravityEngine-neoforge-1.21.1-0.0.1.jar`

Size: 1,404,343 bytes. SHA-256:
`de80e93f6db43e30646e68194feaf87ae0c35270fbe209fc04401a470de21606`

The real Gradle game runs loaded compiled development outputs. The dedicated
fixture recorded this actual engine code source:

```text
union:/D:/javaProjects/GravityEngine/targets/neoforge-1.21.1/build/classes/java/main/%23128!/
```

ModDevGradle also includes `common/build/classes/java/main` in the development mod.
All 641 packaged production classes match their target/common compiled counterparts
byte for byte. The packaged JAR itself has **not** been installed and run in a
StarminerR instance. No Maven publication or replacement of an older cached
0.0.1-SNAPSHOT artifact was performed; loading that artifact does not verify this fix.

## Delivery status

### Fixed and verified

All four reported correctness bugs have transformed server/Mixin/packet/debug
coverage and a built JAR. The target adapter retains ownership of Minecraft
lifecycle. Common remains loader-neutral and its 256 tests pass.

### Implemented, awaiting broader verification

Existing Sable provider, ownership handoff and client prediction/camera/yaw bridges
remain installed. Server tests above verify only their stated cases. Complete
network correction, first/third-person entry and exit from rotating support, and
a real consumer installation still require acceptance; passing server tests is not
proof of those behaviors.

### Incomplete or unsupported

- ClientMovementSupportChecks phase 1 tick 93: stable foot support through
  tangential movement fails with and without Sable; upstream fix remains open.
- Sable scaffolding actor context and independently moving kinematic contraption
  publications remain unsupported and explicitly rejected.
- The full Sable wall/ceiling/tilted-gravity matrix is not complete. Ceiling and
  packet cases above do not establish all locomotion combinations.
- Fluid, climbing, flight/elytra and FallingBlock placement each need separate
  acceptance; ground/air checks are not evidence for them.
- Full client prediction/correction, single camera/body-yaw transform and both
  camera perspectives on rotating supports remain unverified.
- Other loader/version targets and an installed StarminerR consumer were not run.
- The gravity bridge remains COM-only; tidal torque is outside this change.

## Files changed in this follow-up

The working tree also contains the preserved earlier fixes; the full git diff is
therefore larger than this follow-up. This follow-up changes:

Production (under `targets/neoforge-1.21.1/src/main/java/cc/sighs/gravityengine/`):

- `mixin/EntityMixin.java`
- `gravity/minecraft/geometry/GravityEntityGeometry.java`
- `gravity/integration/vanilla/VanillaBodyOccupancy.java`
- `gravity/integration/collision/GravityCollisionEngine.java`
- `gravity/integration/diagnostics/MovementCollisionDiagnostics.java`

Tests (under `targets/neoforge-1.21.1/src/controlTest/java/cc/sighs/gravityengine/controltest/`):

- `DeathGeometryChecks.java` (new)
- `BodyAuthorityChecks.java` (new)
- `ControlBoundaryChecks.java`
- `RigidMovementChecks.java`
- `SableRuntimeChecks.java`

Documentation:

- `docs/ACCEPTANCE_120656_FIXES.md` (this report)
- `docs/ENGINE_DYNAMICS.md`
- `docs/SABLE_2_0_5_VERIFICATION.md` (link distinguishing the earlier checkpoint)
