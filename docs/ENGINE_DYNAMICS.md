# Engine-owned dynamics architecture

Status: implemented internal engine seams (not supported public API).

Everything described here lives in internal packages
(`cc.sighs.gravityengine.gravity.acceleration`,
`gravity.collision`, `gravity.runtime`). Nothing in this document is part of
`cc.sighs.gravityengine.api.*`, and none of it may depend on Minecraft, a
loader, Mixin or an optional compatibility mod.

## Layer model

| Layer | Owner | Examples |
| --- | --- | --- |
| A. engine physics | `common` | gravity evaluation, body dynamics, collision primitives, obstacle motion, contact manifold, support identity, relative-motion response |
| B. Minecraft integration | `targets/<loader>-<mc>` | entity/level capture, collision geometry adapter, world border, fluid material capture, network/occupancy policy, position commit |
| C. optional mod compatibility | target-only compatibility packages | Sable/SubLevel integration |

Layer C may consume the engine's collision/support contracts. It must never
define them, and no Sable type, ABI or identity may reach Layers A or B.

## 1. One gravity truth per operation

```text
GravityAuthorityState      semantic ownership: FIELD | DIRECT, source revision
        |
GravityEvaluationSnapshot  one immutable physical result for one operation/tick
   - GravityEvaluationContext
      - authority binding
      - applicationEpoch
      - committed GravityState + application plan
      - field-registry publication generation when FIELD
   - complete GravityFieldQuery (position, velocity, gameTick, intervalTicks)
   - composed field evidence
   - plan-gated effective acceleration
   - completed GravityFrame
   - sample tick, sample interval
```

`GravityEvaluationService` is the only place that produces that snapshot:

- `evaluateCharacterOperation` applies the committed-direction fallback rule
  (character evidence);
- `sampleFieldEvaluation` returns raw composed field evidence for assignment
  resolution; it is not a physical runtime snapshot;
- `evaluateForPlan` is the plan-generic (NONE/FIELD/DIRECT) variant used by
  non-character routes;
- `reusable` is the deliberately narrow reuse policy for a tick/assignment
  snapshot: identical query inputs and the complete physical evaluation
  context. Reuse checks authority binding, application epoch, committed
  application state/plan, and - for FIELD - the field-registry publication
  generation.

`GravityFieldRegistry.publicationRevision()` is a cheap monotonic generation
advanced by successful `put`, `remove`, revision-aware `remove` and effective
`clear`. It is independent from entity assignment revision. DIRECT evaluation
does not read it.

Lifecycle:

```text
entity tick
    sample raw field evidence once when FIELD assignment discovery is needed
    commit assignment if FIELD evidence changed
    capture the actually committed application context
    build the physical snapshot from raw evidence only when that committed
    context is FIELD; otherwise build DIRECT/NONE physical truth
    publish tick snapshot    -> GravityOperationState.publishTickEvaluation(...)
    movement operation, if any
        consumes that tick snapshot when its inputs are unchanged
        otherwise evaluates once and installs it for the operation
        all consumers (frame, collision, locomotion, support, publication)
        read that single snapshot
```

Invariants enforced in code:

- publishing a tick evaluation while a movement operation is open fails fast;
- installing a second, different evaluation inside one operation fails fast.
- a published snapshot binds authority, application epoch, committed
  application state/plan and FIELD-registry generation;
- an operation may republish its own frozen evaluation as the current tick
  snapshot only while the complete physical context still matches;
- application changes committed during operation close invalidate promotion
  of the old operation evaluation;
- DIRECT publishes a valid current snapshot every tick and never samples the
  field registry.

`GravityEntityState.assignedState()` remains the durable/bootstrap and
network-compatibility representation. It is not the canonical current FIELD
physics once a runtime `GravityEvaluationSnapshot` exists.

`GravityEngineApi.entityGravity()` reads the active/current evaluation first
and falls back to persisted assignment/applied state only when no matching
runtime evaluation exists.

Ballistic and passive acceleration consume the same
`GravityEvaluationSnapshot` model. They do not run character locomotion or
collision, but one logical integration step reuses the current tick snapshot
when its complete query/context matches, otherwise it evaluates exactly once
through `evaluateForPlan`. DIRECT never samples the field registry; FIELD keeps
the complete position/velocity/tick/interval query. `BallisticGravityIntegrator`
integrates the effective acceleration and interval from that one snapshot.

## 2. One immutable collision scene per operation

`CapturedCollisionScene` is the engine's collision-scene snapshot. It is
captured exactly once per outer operation and holds only immutable data:

- static block primitives;
- the world-border snapshot;
- generic kinematic rigid obstacles
  (`DynamicCollisionObstacleSnapshot` + `RigidMotionSnapshot`);
- pose-strict entity snapshots and frozen movement materials.

Target-specific world capture stays in the target; the kernel only filters the
captured lists and fails closed (`CollisionSceneCoverageException`) when a
caller asks for a region outside the captured envelope.

External moving collision geometry enters through the engine-owned
`RigidCollisionPublicationRegistry` and
`ExternalRigidCollisionProvider` SPI. Spatial discovery and identity
reacquisition are separate operations:

```text
capture
    ExternalRigidCollisionQuery { staticBounds, dynamicBounds, time }
        -> provider publishes zero or more immutable obstacle snapshots
        -> registry records source-id ownership

resolve
    RigidObstacleIdentity {
        providerNamespace, sourceId, primitiveId, continuityEpoch
    }
        -> owner adapter/provider
        -> current immutable DynamicCollisionObstacleSnapshot
```

A provider is registered against an opaque scope (the target normally uses its
level). Providers are invoked in stable id order, and a provider failure aborts
capture instead of silently dropping collision geometry. The target merges
provider publications with native entity rigid publications into the same
`CollisionSceneBuilder` before one `CapturedCollisionScene` is frozen, so the
solve, support classification, terminal support and packet occupancy consume
the same obstacle set. Common code never imports the optional compatibility
mod.

Packet occupancy uses a separate validation-owned `RigidOccupancySnapshot`.
It captures only external rigid publications and native moving rigid
publications once per logical packet validation. Both Vanilla's old-body and
new-body GravityEngine occupancy predicates consume that same frozen snapshot;
the packet path does not perform a full gameplay-scene capture. Endpoint rigid
bodies are prepared once and reused by both predicates. Occupancy methods require
the invocation-owned snapshot; they cannot capture a fallback world view. Vanilla
continues to own ordinary block/entity collision acceptance and rejection.

Added engine lookups for support revalidation:

- `dynamicObstacles()`
- `dynamicObstacle(RigidObstacleIdentity)`
- `blockObstacleAt(CellPos)`
- `blockObstaclesAt(CellPos)`
- `blockObstacle(CellPos, Aabb3d)`

## 3. Path contact is not terminal support

Terminal support is represented by `EndpointSupportWitness`, produced from
the solved final body pose and bound to the game tick and captured scene
revision that produced it. Path contact remains solver-level
`GravitySupportContact`/`GravityMoveResult` evidence and is never promoted to
cross-tick support. The previous transitional `ContactWitness` type was
removed because no production path consumed it.

`RestingContactSnapshot` remains the per-operation resting-plane continuity
record; `PersistentSupportState` is the cross-tick identity derived from an
endpoint witness only.

## 4. Persistent support and support transport

```text
PersistentSupportState
    SupportFaceIdentity        static block cell, or provider/source/primitive/continuity epoch
    localAnchor                obstacle-local anchor for every support kind
    normal
    obstaclePoseAtCapture      pose at the solved terminal instant
    motionRevision             publication revision of that pose
    capturedSceneRevision, gameTick, geometryKind
```

Static block support uses the identity pose, so its local anchor is also its
world contact point, and it keeps a zero surface velocity. No world-space
anchor is stored for moving support because it changes every published
interval.

`SupportTransportResolver.resolve(persistent, scene)` derives moving/rotating
transport from the engine's own rigid-motion publication:

```text
displacement     = anchorAt(poseEnd) - anchorAt(poseStart)
surfaceVelocity  = omega x (p - center) + v_linear        (blocks/tick)
```

Rules:

- missing obstacle, changed continuity epoch or discontinuous start pose
  invalidates support (the caller clears it);
- the same motion revision is never transported twice;
- transport is a position delta for one operation interval and modifies the
  requested displacement, never the `GravityFrame`, body orientation or actor
  velocity directly;
- the pose-derived displacement is the sole positional platform transport;
  traction may constrain relative motion but never replaces it with
  `surfaceVelocity * intervalTicks`;
- friction/traction keeps acting on `v_actor - surfaceVelocity`.

Production ordering in the NeoForge 1.21.1 target:

```text
persistent support
    -> engine-owned RigidCollisionPublicationRegistry.resolve(identity, time)
    -> scene-free publication preflight (widens capture domain)
    -> one CollisionCaptureDomain including actor request + transport
    -> one CapturedCollisionScene
    -> revalidate transport against that same scene
    -> stage transport on the outer operation
    -> one authoritative collision solve
    -> terminal support / next persistent state
```

The preflight read is used only to size the capture envelope. The operation's
single captured scene performs the authoritative validation; a preflight/final
mismatch clears the carry and applies no transport. External compatibility
transports remain isolated in their target adapters.

The registry and broadphase validation share `RigidSourceKey` (provider namespace,
engine-assigned provider registration epoch, provider-local source id) per level
scope. `RigidObstacleIdentity` adds primitive id and physical continuity epoch.
Scene/support lookup matches this complete identity exactly; native namespaces
and unassigned registration epochs are never wildcards. Providers may compare
local components only after the registry has selected their namespace.
Replacement/removal invalidates old routes, and source/primitive continuity is
never inferred from numeric IDs alone. A target-native moving entity is represented
by a target adapter that
keeps the entity weakly and resolves through `CollisionSurfaceMotionProvider`;
an external provider is routed by its stable provider id and resolves directly
from the same engine identity. Both enter the same registry, and a level unload
clears the whole scope. Common dynamic source ids are never assumed to be
Minecraft entity ids, and separate providers may reuse the same numeric source
id safely.

A motion revision lower than the persistent support revision is stale temporal
order and invalidates support; it is never interpreted as a new transport
interval.

Static support revalidation compares the captured primitive bounds with the
stored voxel-piece identity and, when a discrete face exists, re-proves the
normal/witness face. Oblique identities without a discrete face fall back to
cell plus primitive identity by design.

## 5. What target integration still owes

These are known, explicit port gaps - not architecture:

1. `forge-1.20.1`, `fabric-1.20.1` and `neoforge-26.1` are not adapted to the
   new seams; they must keep compiling without optional compatibility-mod
   references in `common`.
2. The NeoForge 1.21.1 Sable 2.0.5 adapter registers an
   `ExternalRigidCollisionProvider` against its level scope and implements both
   bounded capture and identity resolve. The engine owns capture validation,
   scene freezing and support transport.

No persisted `GravityState`/body-attitude format changed. Persistent support is
runtime-only and is never serialized.

## 6. Dynamics Core verification

Use the target's canonical executable verification task:

```powershell
.\gradlew.bat -p targets/neoforge-1.21.1 dynamicsCoreVerification --console=plain
```

The task runs:

```text
common unit/architecture tests
target unit tests
supported-API consumer import checks
behavioural control checks in an isolated dedicated server
```

`controlCheck` compiles `controlTest` and executes `JvmControlChecks` with JDK 21:
API value/source identity, packet geometry operands (including whole-shape old
occupancy exemption), and the existing nested movement-scope checks. Assertion
failures exit non-zero and fail Gradle. `check` and `build` depend on this task.
`baselineSnapshot` retains its compile/assemble-only meaning.

`controlBoundaryVerification` remains a separate heavyweight runtime gate for
`ControlBoundaryChecks`, the world-dependent `ApiBoundaryChecks` and
`GeometryAuthorityChecks`, packet capture/thread ownership, and persistence.
It runs a dedicated server in `run/control-verification` on an automatically
assigned port, deletes its previous result before launching, and requires a fresh
PASS result. `dynamicsCoreVerification` includes both JVM and server gates.
The JVM gate does not claim transformed Minecraft/Mixin or GameTest coverage.
Client smoke controls remain opt-in (`-PcontrolBoundaryChecks=true runClient`)
and use `run/control-client`; inspect that directory's `control-boundary-result.txt`
because a normal `runClient` process exit alone does not prove their assertions passed.


## 7. Request ownership and Sable 2.0.5

The NeoForge 1.21.1 integration now publishes Sable plot collision boxes through
`SableRigidCollisionProvider`. This is an internal, exact-version target adapter;
it does not extend the supported public API. See
[Sable verification and limitations](SABLE_2_0_5_VERIFICATION.md) for the actual
runtime matrix, version seams, gravity bridge, and unresolved client checks.

A discovered provider makes an actor eligible for capture. The frozen scene,
actor capability, and relevant external geometry select the operation route.
Default-down living actors therefore collide with external geometry without
requiring a gravity assignment. Provider installation alone does not select
`EXACT_BODY`. Ordinary and packet captures validate query intersection with
conservative swept bounds, not containment of the entire primitive in the query.
Publisher-owned geometry containment and material-point reach remain separate
validation obligations.

Support transport belongs to the solve which consumes it. The solver receives
that value explicitly; it never selects an arc from residual operation state.
The translation cache requires the identical movement evidence and collision
operation, equal exact body, and equal input. An independent request cannot reuse
another request's carry merely because its vector is equal.

Persistent `deltaMovement` remains world velocity in blocks/tick. Runtime support
credit records the contribution already present in that velocity. At Vanilla's
specific ground/air call consuming `deltaMovement`, only that credit is removed
from the displacement operand; the solver adds the newly qualified material-point
carry once. Arbitrary SELF, piston, packet, and auxiliary requests do not subtract
this credit. Friction acts on the relative component. Contact response clips the
credit against its finite constraints so blocked platform momentum cannot be
subtracted on a later reversal. Momentum injected by another obstacle is not
relabelled as support credit.

Accepted jumps preserve relative tangent momentum and inherit the qualified
surface velocity once; a rejected jump changes neither support nor velocity.
Normal walkoff adds only the release velocity not already credited. Invalid
identity/epoch, hard position discontinuity, and source removal clear support
provenance and do not manufacture a release impulse. Clearing provenance does
not itself erase already committed world momentum.

Sable pose interpolation uses normalized quaternion linear interpolation. The
common motion value has an internal optional NLERP angular profile with matching
point velocity and conservative reach bounds. Existing constant-angular motion
retains its operation order. Sable-specific objects and units stay in the target.


## 8. Installed body authority and synchronous dimension refresh

`EXACT_BODY` describes solver ownership, not a compulsory capsule representation.
`GravityEntityGeometry.body` is the target's shared physical-body capture contract:
Vanilla geometry is an exact axis-aligned box; installed non-default reference
geometry retains the real capsule. Movement, auxiliary collision/contact queries,
and packet occupancy consume this same decision. Neither provider discovery nor
a diagnostic bounding box can change the physical shape. The default spider does
not pass through the custom capsule dimension policy.

A synchronous Vanilla dimensions refresh nested inside movement borrows the
geometry owner, runs Vanilla and its Size event once, and installs the resulting
exact body/proxy before returning. It supersedes the old movement publication
through the existing discontinuity mechanism. Outer close retires stale contact,
support and movement evidence instead of reinstalling the old dimensions.
Try-with-resources and a finally commit cover callback exceptions as well as
normal completion. Details and actual server/client acceptance boundaries are in
[the 120656 report](ACCEPTANCE_120656_FIXES.md).
