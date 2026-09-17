# Sable 2.0.5 implementation and verification

Follow-up: [120656 correctness fixes and current verification](ACCEPTANCE_120656_FIXES.md).
The report below records the preceding implementation checkpoint.

Date: 2026-09-19. Target: Minecraft 1.21.1 / NeoForge 21.1.249.

## Result and scope

A?D corrections are implemented and exercised through common tests and transformed
Minecraft server entities. The elevator concern was reproduced in the production
travel chain, not merely inferred from vector arithmetic. A real Sable 2.0.5
provider, single-solver handoff, and rigid-body gravity bridge are connected and
have dedicated-server behavioral coverage.

This is **not complete Sable compatibility**. Both client launches loaded and
entered their test world, then failed the same swim-control assertion at fixture
tick 36. First/third-person platform behavior and the additional gameplay matrix
below have not passed acceptance. No result here claims those paths work merely
because the server build passed. The separately supplied crash report is not
resolved by these changes.

No supported `api.*` signature or field semantics changed. No default Overworld
height field was restored. Other targets and the external StarminerR application
were not modified or verified.

## A?D correctness evidence

| Item | Root cause and change | Evidence |
| --- | --- | --- |
| A: consumed rotation | `GravityCollisionEngine` read residual operation transport after consumption. It now receives solve-local transport from `EntityMovementIntegration`. Only the SELF request consuming pending transport receives its arc. Auxiliary solves receive none. | Transformed zero second request beside the phantom arc obstacle stays zero; existing genuine arc collision tests pass. `GravityOperationStateTest` rejects reuse across evidence/channel/body/vector/nested operation/tick and permits identical re-entry. |
| B: large primitives | Provider validation confused actor discovery bounds with a publisher geometry container. `DynamicEntityBroadphasePolicy` now checks conservative swept intersection. Provider filtering omits irrelevant primitives; malformed emissions still fail. | Large stationary and rotating primitives pass; reach, tick, identity, uniqueness, and budgets remain checked. Normal movement and packet capture share registry validation. The real Sable packet fixture retains a captured wall after its live source is removed. |
| C: double carry | Contact response wrote surface velocity into persistent world velocity, and the next native travel consumed it before adding carry again. Runtime now records support credit; only the native velocity-consuming displacement operand removes that credit. Contact commit replaces old credit, relative friction excludes credit, release adds the missing contribution once. | Red production elevator log: 0.1 carry became 0.2 displacement. Green checks run vertical, horizontal, and rotating platforms under assigned and unassigned gravity through steady speed, acceleration, reversal, stop, lateral impulse, rejected jump, and accepted jump. A second red regression found wall-clipped credit; its fix and reversal test pass. |
| D: default gravity | Exact collision eligibility previously followed custom reference/locomotion ownership. Relevant external geometry in the single frozen scene now independently selects exact collision for living actors. Packet eligibility uses the same external-provider discovery principle. | Red default Player passed through a fake wall; green stops at x?8.6. The provider is captured once. Unavailable publication stops movement instead of silently falling back to Vanilla. Real Sable wall, default gravity, and marker proving native solver bypass pass. |

The extra narrow-phase corrections use fixed separating-plane proofs: rotation
about a floor normal preserves projection onto that axis; a translating pair
separated by the same box face at both endpoints remains separated throughout.
They avoid false zero-TOI loops for tangent movement without exempting entering
motion. Common regressions cover both clear and blocking cases.

## Ownership and lifecycle

| State/result | Producer ? owner ? consumer/commit | Lifetime and units |
| --- | --- | --- |
| Actor displacement | Native request + qualified carry ? GE frozen scene/solver ? native `Entity.move` position and callbacks | One physical request; blocks |
| Persistent velocity | Native impulses/input and GE contact projection ? entity `deltaMovement` ? next native travel | World blocks/tick; not support-relative storage |
| Support credit | Selected dynamic terminal witness and contact clipping ? `GravityOperationState` ? native displacement operand, friction, release | Runtime provenance; clears on invalidation, static/no support, or accepted jump |
| Support carry | Prior terminal material anchor + current qualified publication ? `SupportTransportResolver` ? one consuming solve | One publication interval; actual material-point trajectory enters collision |
| Release | Current qualified identity/epoch/revision ? jump or outer TRAVEL close ? velocity commit | Accepted jump/normal walkoff once; invalid source produces no new impulse |
| Sublevel gravity | GE composed field sampled at world COM ? target gravity bridge ? Sable physics handle | World acceleration converted from blocks/tick? to blocks/s?, then multiplied by substep seconds |
| Packet occupancy | Server-thread old/new corridor capture ? one immutable `RigidOccupancySnapshot` ? Vanilla server accept/correct logic | Both endpoint checks share publication and work tracker; exhausted/invalid capture fails closed |
| Body/reference/camera | Existing GE reference and attitude state ? GE physics/presentation consumers ? body/camera installation | Platform carry changes position; it does not install a second platform rotation into body attitude |

Support is derived from a terminal solver witness, never fabricated from Sable
first-collision/tracking evidence. Source removal invalidates identity; plot edits
advance epoch; pose/bounds updates advance revision. Resolve uses saved source and
primitive IDs, not a new spatial search. Hard teleports/dimension discontinuities
clear the existing operation/support state. Level unload clears both the registry
scope and the Sable adapter map. Client and server Levels register independently.
The map has weak Level keys; provider values retain only weak Level/SubLevel
references, avoiding a value-to-key strong cycle.

Source and primitive IDs are monotonic, stable for their local Level lifecycle,
and not recycled per query. They are runtime identities, not a cross-client/server
wire identity contract. Cross-side ordering under differing creation histories
has not been separately certified.

## Verified Sable interfaces and insertion points

Dependencies were obtained from the Gradle cache and examined as sources and
actual binaries: `dev.ryanhcode.sable:sable-neoforge-1.21.1:2.0.5`, Companion
`1.6.0`, and runtime nested Veil `4.3.2` / Rapier. The actual installed pipeline
was Rapier; the source fallback `StaticPhysicsPipeline` is not evidence of which
backend the runtime used. No guessed reflective API is used for the bridge.

| Exact-version seam | Use |
| --- | --- |
| `SubLevel.updateBoundingBox` / `markRemoved` | Maintain provider entries and index; remove entries on source retirement |
| `LevelPlot.onBlockChange` | Advance geometry epoch/revision so saved anchors do not survive edit/remove/re-add |
| `SubLevel.lastPose` / `logicalPose`; Companion `Pose3dc.lerp` | Linear position plus normalized quaternion interpolation over one tick |
| `SubLevelEntityCollision.collide(Entity, Vec3, Vec3, LevelReusedVectors)` | Return parent-only `EngineCollisionInfo` for the already-frozen EXACT_BODY operation; GE handles Sable and parent-world geometry together |
| Sable `EntityMixin` priority 1100 / native parent `Entity.collide` continuation | Preserve the parent call and Vanilla callbacks while bypassing the first Sable solver |
| Sable `LivingEntity.travel` inherited-motion branch | Empty native collision/tracking evidence prevents a second post-solve position carry |
| Sable packet `isCreative` redirect | GE expression modification restores the real creative predicate for owned validation; it does not disable moved-wrongly checks |
| `PhysicsSystem.initialize` ? `PhysicsPipeline.init` | Record the actual native baseline gravity |
| `ServerSubLevel.applyQueuedForces` RETURN before each physics substep | Apply the world-space velocity correction for replacement gravity |

The old `SableMovementAdapter` remains only for non-owned native behavior. It
returns no evidence for `EngineCollisionInfo`, so old post-solve evidence cannot
supply a second GE carry. The ownership handoff clears native tracking and moves
any previously queued inherited velocity into world velocity once. Optional
Mixins are gated by actual Sable class presence; no-Sable dedicated and client
loading were executed. Metadata restricts this integration to Sable 2.0.5.

The provider keeps 32-block index cells and a bounded large-body set, then visits
only blocks inside the relevant plot/query intersection. It publishes complete
collision boxes without clipping or moving their local anchors. Capture bounds,
checked bodies, block positions, emitted primitives, and GE narrow-phase work have
explicit limits. Those limits do not bound arbitrary block collision-shape
callbacks or all Minecraft internal indexing cost.

Local primitive coordinates are `(plot point - rotationPoint) * scale`; the rigid
pose contains world position/orientation. The zero initial rotation-point fallback
matches Sable's collision interpolation. The shortest quaternion delta uses the
same NLERP angle profile and derivative as Companion. A 100-sample JOML oracle
checks pose and velocity/reach bounds. Arbitrary accelerated physical paths are
not claimed exact: this publication describes Sable's sampled one-tick character
collision interpolation, not all physics substeps between those poses.

## Gravity bridge semantics

At each server physics substep, transform the plot-space mass COM to world space
and sample the existing composed field at that point. Query velocity is the
handle's world velocity divided by 20, and interval is substep seconds times 20.
For a present field, apply:

```text
world delta-v = (400 * GE acceleration_per_tick_squared - native_gravity_per_second_squared)
                * substep_seconds
```

Sable then applies its native gravity once. A present zero field cancels it;
absence or `-Dgravityengine.sableGravity=false` leaves native gravity alone.
Composition remains the existing field registry's ADDITIVE/OVERRIDE semantics.
This is one COM acceleration, with no tidal torque claim. No actor gravity is
injected by this bridge. Invalid/unavailable mass data does not fabricate a COM.

The verified Rapier `applyLinearAndAngularImpulse` entry consumes local-space
impulses and newly added bodies can have unavailable cached inverse mass before
the first step. The implementation therefore uses its verified world-space
`addLinearAndAngularVelocity` entry: acceleration is mass-independent, not an
unscaled force. The runtime fixture rotates the body about Z to detect a local/
world-axis error. After three system ticks, absent/disabled runs have y?-1.637
blocks/s, zero has y?1.88e-7, and a 0.01 blocks/tick? X field has x?0.5953 with
near-zero Y (expected 0.6 before native damping).

## Executed verification

Windows wrapper commands; common targets Java 17, target toolchain is installed
JDK 21 (`C:/Program Files/Java/jdk-21.0.11`). The Gradle host may be Java 25;
that does not change the target compile/runtime toolchain. Initial baseline common
run had 251 tests. Final common suite has **256**, target JUnit has **17**, all
passing with zero skips. Tests and server assertion counters are different units.

| Command / artifact | Result |
| --- | --- |
| `./gradlew.bat -p common clean test --console plain --no-daemon` (`build/common-final.log`) | PASS, 256 common tests |
| `./gradlew.bat -p targets/neoforge-1.21.1 dynamicsCoreVerification build --console plain --no-daemon` (`build/no-sable-final.log`) | PASS, 17 target tests, JVM controls and transformed dedicated server |
| Same command with `-PsableChecks=true` (`build/sable-final.log`) | PASS, real Sable provider, wall/packet snapshot/removal, platform and Rapier gravity fixtures |
| `./gradlew.bat -p targets/neoforge-1.21.1 -PcontrolBoundaryChecks=true runClient --console plain --no-daemon` (`build/client-without-sable.log`) | Loading PASS; behavior FAIL at swim fixture tick 36 |
| Same client command with `-PsableChecks=true` (`build/client-with-sable.log`) | Loading PASS; same behavior FAIL at tick 36 |

Dedicated checks report 2264 control-boundary assertions, 94 API-boundary
assertions, and 11 move-interop assertions, plus named geometry/packet/persistence
and new rigid movement/Sable fixtures (the latter are not included in 2264).
Client Gradle processes exited successfully even though their result file said
FAIL. The exact failure was `same-step swim plan at fixture tick 36:
CharacterControlPlan[locomotion=GROUND_AIR, ...]`; expected swim selection did not
occur. It is observed in both configurations, not proven to be a baseline defect.
Client runs preceded the final wall-credit and packet fixture additions; there
was no final visual acceptance after those additions.

Red logs retained locally include `build/dynamics-default-red.log`,
`build/dynamics-elevator-red.log`, and `build/wall-credit-red.log`. Build logs and
runtime worlds are ignored artifacts, not source delivery files.

## Matrix and remaining boundaries

| Area | Verified here | Not established / unsupported boundary |
| --- | --- | --- |
| Transport | Assigned/unassigned default-down fake platforms; real Sable vertical/horizontal/yaw, accelerating/reversing/stopped; impulses, jumps, wall/reversal | Dedicated production slope, active walking, tilted/zero-gravity Sable passenger matrix not completed |
| Lifecycle | Common identity/release tests, real block epoch and source removal, existing teleport/clone/persistence checks | End-to-end client dimension/unload/reconnect while on a moving Sable body not exercised |
| Packet | Actual Sable geometry in shared old/new snapshot; existing transformed packet authority/budget checks | Networked remote passenger under moving-wall/rotating-corner corrections not exercised |
| Client presentation | Both configurations load; body-state send check passes before swim failure | First/third person, yaw, jump/detach, gravity-reference changes, remote interpolation and corrections on a live platform not accepted |
| Fluids | Client fixture exposes a failing same-step swim transition | Swimming/sublevel fluid collision and currents are not certified; ground/air support-credit operand hook is not a fluid algorithm |
| Climbing | Existing general control tests run | Sable ladder/scaffolding/powder-snow interaction not certified; scaffolding publication explicitly fails closed because it needs actor context |
| Flight | Existing general controls/build run | Sable creative flight and elytra carry/release matrix not certified; ground/air ownership must not be assumed to prove these paths |
| FallingBlock | Existing engine paths left intact | Sable placement/landing/drop semantics not implemented or certified as part of the living-actor provider route |
| Vehicles | Non-owned actors retain Sable native paths | Vehicle-associated camera transform and passenger ownership transitions not certified |
| Gravity bridge | Absent, present zero, directional field, disabled fallback through real Rapier steps | No tidal torque, cross-mass matrix, or client visual rigid-body-gravity acceptance |

Changing scale/pivot or non-unit collision intervals fail publication. Excessive
material-point motion and work exhaustion fail closed. Server plots containing
kinematic contraptions fail closed because those children require their own
motion publication; equivalent client contraption representation is not yet
supported. Other entity-dependent block collision shapes beyond the explicitly
rejected scaffolding remain an adapter limitation.

For ordinary standing actors, clearing native Sable tracking prevents its camera
`setup` position wrapper (`getTrackingOrVehicleSubLevel`) and living tracking
rotation path from applying the ship transform again. The existing GE presentation
state still owns reference/body orientation. This is a source-level ownership
audit, not a claim that visual interpolation, foot placement, or vehicle behavior
has passed the missing client matrix. Rendering does not gain physical writeback.

## Changed files

The following inventory excludes the user's prompt, audit archive, and crash
report. Paths are relative to the repository root.

<!-- file-inventory -->

- `common/src/main/java/cc/sighs/gravityengine/gravity/collision/CapsuleRigidObstacleSweep.java`
- `common/src/main/java/cc/sighs/gravityengine/gravity/collision/CollisionNarrowPhase.java`
- `common/src/main/java/cc/sighs/gravityengine/gravity/collision/DynamicEntityBroadphasePolicy.java`
- `common/src/main/java/cc/sighs/gravityengine/gravity/collision/RigidMotionSnapshot.java`
- `common/src/main/java/cc/sighs/gravityengine/gravity/collision/RigidObstacleSweep.java`
- `common/src/main/java/cc/sighs/gravityengine/gravity/collision/SupportMotionTrajectory.java`
- `common/src/main/java/cc/sighs/gravityengine/gravity/collision/provider/ExternalRigidCollisionQuery.java`
- `common/src/main/java/cc/sighs/gravityengine/gravity/runtime/GravityOperationState.java`
- `common/src/test/java/cc/sighs/gravityengine/ArchitectureBoundaryTest.java`
- `common/src/test/java/cc/sighs/gravityengine/gravity/collision/ExternalRigidCollisionProviderTest.java`
- `common/src/test/java/cc/sighs/gravityengine/gravity/collision/NormalizedLinearRigidMotionTest.java`
- `common/src/test/java/cc/sighs/gravityengine/gravity/collision/RotatingSupportClearanceTest.java`
- `common/src/test/java/cc/sighs/gravityengine/gravity/runtime/GravityOperationStateTest.java`
- `docs/ENGINE_DYNAMICS.md`
- `docs/SABLE_2_0_5_VERIFICATION.md`
- `targets/neoforge-1.21.1/build.gradle`
- `targets/neoforge-1.21.1/src/controlTest/java/cc/sighs/gravityengine/controltest/ControlBoundaryChecks.java`
- `targets/neoforge-1.21.1/src/controlTest/java/cc/sighs/gravityengine/controltest/RigidMovementChecks.java`
- `targets/neoforge-1.21.1/src/controlTest/java/cc/sighs/gravityengine/controltest/SableRuntimeChecks.java`
- `targets/neoforge-1.21.1/src/main/java/cc/sighs/gravityengine/event/GravityEvents.java`
- `targets/neoforge-1.21.1/src/main/java/cc/sighs/gravityengine/gravity/integration/ContactVelocityIntegration.java`
- `targets/neoforge-1.21.1/src/main/java/cc/sighs/gravityengine/gravity/integration/EngineSupportTransportIntegration.java`
- `targets/neoforge-1.21.1/src/main/java/cc/sighs/gravityengine/gravity/integration/EntityMovementIntegration.java`
- `targets/neoforge-1.21.1/src/main/java/cc/sighs/gravityengine/gravity/integration/GravityOperation.java`
- `targets/neoforge-1.21.1/src/main/java/cc/sighs/gravityengine/gravity/integration/GroundAirGravityMovementHandler.java`
- `targets/neoforge-1.21.1/src/main/java/cc/sighs/gravityengine/gravity/integration/LivingGravityIntegration.java`
- `targets/neoforge-1.21.1/src/main/java/cc/sighs/gravityengine/gravity/integration/PassiveGravityCollisionIntegration.java`
- `targets/neoforge-1.21.1/src/main/java/cc/sighs/gravityengine/gravity/integration/collision/GravityCollisionEngine.java`
- `targets/neoforge-1.21.1/src/main/java/cc/sighs/gravityengine/gravity/integration/collision/MinecraftCollisionSceneCapture.java`
- `targets/neoforge-1.21.1/src/main/java/cc/sighs/gravityengine/gravity/integration/compat/sable/SableCollisionOwnership.java`
- `targets/neoforge-1.21.1/src/main/java/cc/sighs/gravityengine/gravity/integration/compat/sable/SableGravityBridge.java`
- `targets/neoforge-1.21.1/src/main/java/cc/sighs/gravityengine/gravity/integration/compat/sable/SableMovementAdapter.java`
- `targets/neoforge-1.21.1/src/main/java/cc/sighs/gravityengine/gravity/integration/compat/sable/SableMovementCompatibility.java`
- `targets/neoforge-1.21.1/src/main/java/cc/sighs/gravityengine/gravity/integration/compat/sable/SableRigidCollisionProvider.java`
- `targets/neoforge-1.21.1/src/main/java/cc/sighs/gravityengine/gravity/integration/vanilla/VanillaPropulsionBridge.java`
- `targets/neoforge-1.21.1/src/main/java/cc/sighs/gravityengine/gravity/policy/GravityInfluencePolicy.java`
- `targets/neoforge-1.21.1/src/main/java/cc/sighs/gravityengine/mixin/LivingEntityTravelMixin.java`
- `targets/neoforge-1.21.1/src/main/java/cc/sighs/gravityengine/mixin/OptionalCompatibilityPlugin.java`
- `targets/neoforge-1.21.1/src/main/java/cc/sighs/gravityengine/mixin/ServerGamePacketListenerImplMixin.java`
- `targets/neoforge-1.21.1/src/main/java/cc/sighs/gravityengine/mixin/compat/sable/SableGravityMixin.java`
- `targets/neoforge-1.21.1/src/main/java/cc/sighs/gravityengine/mixin/compat/sable/SablePhysicsBaselineMixin.java`
- `targets/neoforge-1.21.1/src/main/java/cc/sighs/gravityengine/mixin/compat/sable/SablePlotGeometryMixin.java`
- `targets/neoforge-1.21.1/src/main/java/cc/sighs/gravityengine/mixin/compat/sable/SablePublicationMixin.java`
- `targets/neoforge-1.21.1/src/main/java/cc/sighs/gravityengine/mixin/compat/sable/SableSubLevelEntityCollisionMixin.java`
- `targets/neoforge-1.21.1/src/main/resources/META-INF/neoforge.mods.toml`
- `targets/neoforge-1.21.1/src/main/resources/gravityengine.mixins.json`
