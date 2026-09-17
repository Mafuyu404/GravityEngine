# Consumer API boundary

The supported baseline is GravityEngine **0.0.2**, common Java 17 and
NeoForge 1.21.1 / 21.1.256 (Java 21). CP2 and dependency-update evidence and limits are in
[REFACTOR_STATUS.md](REFACTOR_STATUS.md). Other targets are scaffold/unverified.

Supported consumer packages are `cc.sighs.gravityengine.api`, `api.field`, and
`api.math`. Common signatures are platform neutral; the target facade may use
Minecraft types. Engine internals, collision providers, DIRECT mutation,
attitude transactions, networking, persistence formats and renderer types remain
internal regardless of Java visibility.

## Field providers and discovery

Register stable provider IDs and Level session factories through
`GravityEngineApi.registerFieldProvider` before any server Level runtime exists.
Factories construct sessions; `onOpen` may publish only its own domain. Duplicate
and late registrations fail. Level unload closes sessions and revokes publications.

`GravityFieldProvider.evaluate(GravityFieldQuery)` returns immutable contributions
and the provider's per-query `COMPLETE` or `INCOMPLETE` coverage. Every expected
provider participates. A partial positive result is not authoritative assignment
evidence. Complete-empty means absence; complete contributions summing to zero
still mean presence. No registry-empty or Level-readiness shortcut exists.

All active publications are evaluated and validated before global composition.
OVERRIDE excludes ADDITIVE values only from the sum, not from validation or
coverage. Multiple OVERRIDE values sum. Contributions sort by source-type string,
numeric X/Y/Z, then contribution ID namespace and path. IDs use
`[a-z0-9_.-]+:[a-z0-9/._-]+`, must be globally unique per query, and are not silently
deduplicated. Non-finite values and sum overflow fail explicitly. Position-only
sampling retains zero velocity, tick 0 and interval 0; negative/non-finite intervals
remain invalid.

`GravityFieldProvider.blockDiscovery()` is a new optional, default method called
once per server Level tick. Return `GravityFieldDiscovery` with immutable finite
`GravityFieldBounds` for non-publication sources. Its default `SAMPLING_ONLY`
does not automatically wake existing blocks. `UNBOUNDED` explicitly opts into
budgeted traversal of loaded chunks. Publication influence bounds participate
automatically, captured at successful publication preparation. An oversized finite
index entry remains finite for discovery; spatial index fallback does not change
the declared influence domain.

Discovery hints must conservatively cover source influence during the tick. They
select candidate work and never prove field coverage. Exact block sampling still
calls all registered providers. Changed hints take effect on subsequent bounded
passes; source removal preserves cleanup of previously affected positions.
Discovery and cleanup cursors survive source changes, retire on chunk unload,
and use native execution and scheduled-tick gates. Continuous sources are revisited
without changing publication revisions. No finite completion deadline is promised
under continuously unbounded incoming work.

These callbacks run on the owning Level thread. Discovery cannot sample,
recursively discover, or mutate publications. Evaluation may call
`GravityEngineApi.samplePublications` for its own domain but cannot recursively
sample composed gravity or mutate publications. Results defensively capture lists.

## CP1 behavior migration

- Existing provider lambdas remain valid: `evaluate` is still the only abstract
  method. Implement `blockDiscovery` to enable non-publication automatic discovery.
  `ProviderFixture` under the target's `src/controlTest/java/com/example` exercises
  this through supported API types.
- Runtime callback failures from factory/open/evaluation/discovery/close now throw
  `IllegalStateException` containing provider ID, Level and phase, retaining the
  original cause. Cleanup failures remain suppressed. Publication argument errors
  preserve their exception type and add contextual suppressed diagnostics.
- ADDITIVE evaluator failures are no longer hidden by an active OVERRIDE in the
  internal legacy registry sampling path. Registry sampling describes only its
  own publications, never completeness of the Level's provider domains.
- Publication tokens remain instance-owned and revision-aware. Closing an old
  lease cannot remove a replacement or recreate an unloaded runtime.
- CP1 retained snapshot shape; CP2 migrates it as specified below.

Assignment, installed application, geometry, FIELD evidence, durable/sync
revisions, native attachment persistence and wire formats retain their separate
owners. No save-format or network migration is introduced.

## Supported operations

| Operation | Side/thread and lifetime | Ownership, validation and failure |
| --- | --- | --- |
| `registerFieldProvider` | Mod initialization, before first server Level runtime; process-lifetime definitions | Non-null unique stable ID/factory. Duplicate: `IllegalArgumentException`; frozen/late: `IllegalStateException`. One session per server Level; factories construct, `onOpen` publishes, unload closes. |
| `publicationProvider` | Construct in a registered factory; evaluate only in its Level session | Delegates to `samplePublications`, with caller-supplied query coverage. No extra registry, composition, readiness or source owner. Non-null arguments/result required. |
| `publish` | Server Level owning thread, permitted mutation phase | Immutable descriptor; pure field/influence capture. Non-negative revision. Same/lower revision rejected as stale without replacing current source. Successful lease owns exact publication instance, not only ID/revision. |
| `FieldPublication.close` | Owning Level thread for a live lease; after unload no-op | Idempotent successful release; wrong-thread/phase release fails and remains retryable. Old leases cannot remove new instances or recreate runtimes. Rejected publication owns nothing. |
| `samplePublications` | Inside the owning provider's evaluation | Immutable list of that domain's active contributions, including both composition modes; no coverage claim. Wrong domain/phase/reentry fails. |
| `sample` | Server Level owning thread; one captured query | Immutable `ComposedGravitySample` exposes coverage. Inspect coverage before treating composition as resolved. Nulls rejected; finite position/velocity, non-negative game tick and finite non-negative interval. Unexpected callbacks fail with cause. |
| `entityGravity` | Owning entity thread, either side; instantaneous read | Immutable optional snapshot. No adapter/state yields empty, which is not FIELD absence. No sessions, callbacks, resampling, mutation or physical commits. See provenance below. |
| `registerEntityAdapter` | Mod initialization on both sides, before first capability resolution | Exact `EntityType` identity; one definition per type. Explicit declaration precedes built-in classification; duplicates rejected, no insertion-order/priority ambiguity. Late registration fails. |
| `GravityFields` and field/math values | Platform-neutral immutable values; pure evaluators may run independently of a Level | Inputs captured defensively; fields return finite world acceleration. Bounds select candidates; exact containment decides activity. Factories document their individual radius/mass/density constraints. |

Null references fail with `NullPointerException`; invalid numeric/value arguments
with `IllegalArgumentException`, unless a method documents a narrower result such
as a stale publication receipt. Provider execution failures are contextual
`IllegalStateException` wrappers retaining causes. Invalid output is never replaced
by a fabricated complete-empty result. Callbacks must bound their own work;
engine budgets cannot preempt arbitrary Java callbacks.

Position uses blocks, velocity blocks/tick, acceleration blocks/tick squared.
Ticks are logical game ticks; interval is in ticks, not seconds. Position-only
`sample(level, position)` and `GravityFieldQuery.at` retain zero velocity, tick 0,
interval 0. The full overload also accepts a zero interval for an instantaneous
query; it does not invent physical time. Vector values are immutable `Vec3d`.
No supported API exposes JOML, runtime, collision, protocol or attitude types.

## Entity observation and integration declarations

`EntityGravitySnapshot` separates three immutable pairs:

- `assignedDirection/Strength`: authoritative assignment (including retained FIELD seed).
- `effectiveDirection/Strength`: physical evaluation, or explicit assignment fallback.
- `appliedDirection/Strength`: currently committed application.

`observationSource` is derived while reading: `ACTIVE_OPERATION` uses the owning
operation's frozen evaluation; `CURRENT_TICK` requires matching time, position,
velocity and authority/application context; otherwise `ASSIGNMENT_FALLBACK`.
FIELD tick evaluations cannot be reused merely because publication revision is
unchanged: provider coverage can change without publication. They remain invalid
outside their operation unless a future contract proves the full source context.
No freshness bit or generation is saved. Strengths must be finite/non-negative;
engine-produced directions are unit world vectors (zero strength still has a
direction). `referenceFrame` is environmental evidence, not collider orientation.
`fieldPresence` remains UNKNOWN/PRESENT/ABSENT independently of numeric strength.

An entity adapter selects existing, finite integration modes. `NATIVE` retains
native motion/body/presentation while fields remain sampleable. `CHARACTER` permits
ordinary living travel with current capsule-compatible dimensions and the existing
native-state exclusions. `BALLISTIC` keeps native AABB collision for supported
projectile gravity seams; `PASSIVE_BALLISTIC` additionally uses existing passive
support for item/orb/TNT/falling blocks. `CONTROLLED_FLIGHT` prevents character
locomotion from claiming a flight controller. None of these modes enables arbitrary
solver replacement, attitude writes or new gravity hooks in a custom entity tick.
An incompatible family selects native behavior; a marker/wide-short current body
cannot be forced into CHARACTER. Consumers must audit and test their actual travel
and tick path before opting in.

Callbacks are pure, non-null, non-reentrant, run on the entity-owning thread and
are reevaluated on capability requests. Results are not cached by EntityType;
current dimensions and pose still constrain application. Exceptions propagate with
type context. Built-in Vanilla policies remain; an unregistered custom `travel`
override is conservatively native, while an inherited ordinary Vanilla travel path
remains eligible. The class-only override classification is derived once per Java
class; no dimensions, pose or mutable callback result is stored there.

## Compiling examples and source authority

Use the actual [consumer fixture](../targets/neoforge-1.21.1/src/controlTest/java/com/example/examplemod/gravity/ExternalConsumerFixture.java)
for publication/replacement/close, observation, snapshot copy and entity declarations.
The [provider fixture](../targets/neoforge-1.21.1/src/controlTest/java/com/example/examplemod/gravity/ProviderFixture.java)
uses `publicationProvider` and separately adds a bounded non-publication source.
It exercises complete/incomplete sampling and per-Level disposal through the same
engine path. These are compiled from the built JAR by `verifyApiArtifact`; see
[DEVELOPMENT.md](DEVELOPMENT.md) for a real file-dependency setup.

The fixture owns a deliberately finite, synthetic source domain; its coverage
switch is test evidence, not a production readiness recipe. A loaded-core-only
consumer closes leases/removes index entries at unload and captures every relevant
loaded chunk's discovery at the query. A proven influence-radius bound must justify
that candidate domain. In-progress discovery returns INCOMPLETE even if some sources
already contribute. A consumer requiring unloaded cores to act instead owns a
persistent source index of identities and field parameters. BlockEntity presence
alone cannot establish that authority. Neither policy adds GE persistence recovery
signals; the provider answers its own source-domain query, never an entity's load.

## 0.0.1 to 0.0.2 migration and compatibility

This is one coordinated pre-release API migration, not two retained truth paths.
Recompile consumers against 0.0.2. `EntityGravitySnapshot`'s canonical constructor
now appends `assignedDirection`, `assignedStrength`, `observationSource`; use the
fixture's `copySnapshot` to preserve all values. The old constructor is removed
rather than guessing provenance from equal numbers. Existing accessors remain.
Negative snapshot strengths now fail validation; they are magnitudes, not signed
acceleration components. Read `observationSource` before treating effective values
as evaluated. Old-tick or changed-query observations now fall back explicitly.

`publicationProvider`, `registerEntityAdapter`, `EntityGravityAdapter` and
`GravityObservationSource` are additive supported API. Existing provider lambdas
still compile. A custom living `travel` override that expects the character kernel
must register its audited CHARACTER declaration during initialization; ordinary
inherited travel and known Vanilla policies remain supported. No arbitrary custom
projectile/flight implementation is automatically adapted.

CP1 callback wrapping and discovery changes above are included in this version.
Units, position-only defaults, deterministic composition, lease instance ownership,
thread/side rules and per-query coverage are retained. New supported API changes
must preserve these contracts or ship an explicit versioned migration and fixture
update. Internal class layout, numerical algorithms, save/network formats and Java
public internals are not compatibility promises. Current supported attachment save
semantics are unchanged; this refactor adds no network or save migration.

There is no implicit experimental namespace: operations listed here are supported
for the verified primary target; other implementation entry points remain internal.
Client visual acceptance and other target parity are not implied by API stability.
