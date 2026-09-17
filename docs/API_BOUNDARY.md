# GravityEngine Public API Boundary

This document describes the supported external Java API currently exported by GravityEngine. It is intentionally narrower than the set of Java classes that happen to be `public` inside the implementation.

## Supported namespace

The current supported loader-neutral API is:

```text
cc.sighs.gravityengine.api.*
cc.sighs.gravityengine.api.field.*
cc.sighs.gravityengine.api.math.*
```

It is a Java 17 SPI for defining and describing gravity fields without importing Minecraft, Forge, NeoForge, Fabric, Mixin, JOML, or GravityEngine implementation packages.

Stable public mathematical value:

```text
cc.sighs.gravityengine.api.math.Vec3d
```

Internal implementation math remains `cc.sighs.gravityengine.math.*` and must not appear in consumer-facing signatures.

JOML (`org.joml`) is **not** part of the GravityEngine public API. It may exist only in tests or target-side third-party/render compatibility code.

## Minecraft-aware target facade

The authoritative `neoforge-1.21.1` target additionally exposes the supported
Minecraft-aware facade:

```text
cc.sighs.gravityengine.api.GravityEngineApi
```

That facade accepts Minecraft `Level`/`Entity` at the call boundary for
level-local publication and sampling. GravityEngine-owned data-model values
returned by that facade remain loader-neutral:

| Type | Vector contract |
| --- | --- |
| `EntityGravitySnapshot` | `effectiveDirection`, `appliedDirection`, and derived accelerations are immutable `Vec3d` values. |
| `GravityFrameView` | `samplePoint`, `down`, `up`, `left`, and `forward` are immutable `Vec3d` values. |

Target adapters convert these values to Minecraft vectors only when a
Minecraft-facing call actually requires `net.minecraft.world.phys.Vec3`.

The supported types are:

| Type | Role |
| --- | --- |
| `GravityField` | Pure gravity evaluator: one immutable query -> one acceleration sample. |
| `GravityFieldQuery` | Immutable field-evaluation input snapshot. |
| `GravityFieldSample` | Finite acceleration result of one evaluation. |
| `GravityInfluenceVolume` | Spatial activity/candidate extent of a published field. |
| `GravityFieldBounds` | Inclusive finite world-axis-aligned bounds used by finite influence volumes. |
| `GravityFieldCompositionMode` | Registration-level `ADDITIVE` / `OVERRIDE` composition vocabulary. |
| `GravityFields` | Supported factories for built-in fields, influence volumes, and spherical producer-side helper math. |
| `Vec3d` | Immutable GravityEngine-owned world-space mathematical vector used by supported API signatures. |

`GravityEngineApi.entityGravity()` reports the current authoritative runtime
physical evaluation when one exists for the complete committed physical
context: authority binding, application epoch, committed application state/plan
and the FIELD registry generation when applicable. While a movement operation
is active, that operation's frozen evaluation is authoritative. A time- or
velocity-dependent FIELD result produced by the latest movement operation
therefore supersedes an older tick snapshot. Persisted assignment and
committed-application values are the fallback only when no matching runtime
evaluation exists.

Everything outside `cc.sighs.gravityengine.api.*` should be treated as an implementation detail unless a later API document explicitly promotes it. In particular, do not compile external integrations against `cc.sighs.gravityengine.gravity.*`, `attitude.*`, `math.*`, `network.*`, or `protocol.*` merely because a class is currently Java-`public`.

## Dependency contract

An implementation of the current field SPI needs only:

- Java 17;
- `cc.sighs.gravityengine.api.math.Vec3d`;
- `cc.sighs.gravityengine.api.field`.

Java 17 is the `common` API/kernel baseline, not the baseline of every target.
Targets retain the Java version required by their Minecraft/loader version and
may consume this Java 17 API from a newer baseline.

The API deliberately does not expose Minecraft or loader types. This lets the same field definition be shared across supported target adapters.

## Mental model

A published gravity source is conceptually split into independent concerns:

```text
source / host lifecycle
        |
        +-- identity / revision / ordering        (registration owner)
        +-- GravityInfluenceVolume                (where it participates)
        +-- GravityFieldCompositionMode           (how it composes)
        +-- GravityField                          (what acceleration it computes)
                                                     |
                                                     v
                                           GravityFieldQuery
                                                     |
                                                     v
                                           GravityFieldSample
```

The public field evaluator is deliberately only the mathematical part. It does not own source identity, registration, revision, Level/Entity state, collision geometry, or loader lifecycle.

## `GravityField`

```java
public interface GravityField {
    GravityFieldSample sample(GravityFieldQuery query);
}
```

A `GravityField` implementation must be pure and side-effect free:

- the same query produces the same result;
- it reads only the immutable query and immutable state captured by the evaluator;
- it does not read or mutate Minecraft world/entity state;
- it does not perform registration or lifecycle work;
- it may return zero acceleration.

Evaluation occurs on the thread that owns the Level to which the field is published. That is a scheduling fact, **not** permission to access the Level from the evaluator.

If a field depends on content configuration, capture the required values into the evaluator/publication snapshot before sampling instead of reading live mutable state from `sample`.

### Custom evaluator example

```java
import cc.sighs.gravityengine.api.field.GravityField;
import cc.sighs.gravityengine.api.field.GravityFieldSample;
import cc.sighs.gravityengine.api.math.Vec3d;

GravityField constantDown = query ->
        new GravityFieldSample(new Vec3d(0.0, -0.08, 0.0));
```

A custom evaluator can use `query.position()`, `query.velocity()`, `query.gameTick()`, and `query.intervalTicks()` when those values are semantically relevant. It must still remain pure.

## `GravityFieldQuery`

The record shape is:

```java
public record GravityFieldQuery(
        Vec3d position,
        Vec3d velocity,
        long gameTick,
        double intervalTicks
)
```

Contract:

- `position` and `velocity` must be non-null and finite;
- `gameTick >= 0`;
- `intervalTicks` must be finite and `>= 0`;
- `intervalTicks == 0` is valid for position-only sampling;
- `Vec3d` components are immutable, so accessors never expose mutable query state.

For position-only evaluation, use:

```java
GravityFieldQuery query = GravityFieldQuery.at(position);
```

`at(...)` uses zero velocity, tick `0`, and interval `0`.

Do not rely on mutating a vector returned by an accessor to mutate the query; it cannot and must not work.

## `GravityFieldSample`

The record shape is:

```java
public record GravityFieldSample(Vec3d acceleration)
```

Contract:

- acceleration must be non-null and finite;
- `Vec3d` acceleration is immutable;
- `GravityFieldSample.ZERO` is a valid sample.

A zero sample means **the evaluator produced zero acceleration at this query**. It does not mean that the field is absent or inactive. Presence/activity is determined by the registered instance and its influence volume.

## `GravityInfluenceVolume`

```java
public interface GravityInfluenceVolume {
    boolean contains(Vec3d position);
    Optional<GravityFieldBounds> finiteBounds();
}
```

`contains` is the exact activity predicate for the influence volume.

`finiteBounds()` has two forms:

- `Optional.of(bounds)` for a finite volume;
- `Optional.empty()` for an infinite/global volume.

For a finite volume, the returned bounds must conservatively contain the entire volume. The engine can use those bounds for spatial bucketing/candidate discovery and then use `contains` for exact membership.

Do not use an undersized bound to optimize the index; it makes valid field regions undiscoverable.

### Custom finite influence example

```java
import cc.sighs.gravityengine.api.field.GravityFieldBounds;
import cc.sighs.gravityengine.api.field.GravityInfluenceVolume;
import cc.sighs.gravityengine.api.math.Vec3d;

import java.util.Optional;

GravityInfluenceVolume box = new GravityInfluenceVolume() {
    private static final GravityFieldBounds BOUNDS =
            new GravityFieldBounds(-16, 0, -16, 16, 32, 16);

    @Override
    public boolean contains(Vec3d p) {
        return p.x() >= BOUNDS.minX() && p.x() <= BOUNDS.maxX()
                && p.y() >= BOUNDS.minY() && p.y() <= BOUNDS.maxY()
                && p.z() >= BOUNDS.minZ() && p.z() <= BOUNDS.maxZ();
    }

    @Override
    public Optional<GravityFieldBounds> finiteBounds() {
        return Optional.of(BOUNDS);
    }
};
```

## `GravityFieldBounds`

`GravityFieldBounds` is a six-scalar inclusive AABB value:

```java
public record GravityFieldBounds(
        double minX, double minY, double minZ,
        double maxX, double maxY, double maxZ
)
```

All components must be finite and each minimum must be `<=` its corresponding maximum.

This is an indexing/API value, not collision geometry. Do not treat field influence bounds as a physical obstacle or collision shape.

## `GravityFieldCompositionMode`

Composition is registration metadata; it is intentionally not encoded by the evaluator class.

### `ADDITIVE`

When no active override field exists, every active additive field contributes and the acceleration vectors are summed in world space.

### `OVERRIDE`

If at least one active override field exists at the query point:

1. active additive fields are excluded from the authoritative result;
2. all active override fields are still vector-summed with one another.

Therefore `OVERRIDE` is **not** first-wins, nearest-wins, strongest-wins, or last-wins.

A zero-valued override contribution can intentionally suppress ordinary additive gravity. The engine must not special-case evaluator types such as a zero-gravity implementation to decide this; composition belongs to registration metadata.

## `GravityFields` built-in factories

External consumers should use this class instead of importing the internal implementation classes returned by the factories.

### `zeroGravity()`

```java
GravityField field = GravityFields.zeroGravity();
```

Returns a stateless zero-acceleration evaluator. A common use is publishing it with `GravityFieldCompositionMode.OVERRIDE` to suppress additive gravity while the registered field remains active.

### `sphericalMass(...)`

```java
GravityField field = GravityFields.sphericalMass(
        center,
        mass,
        referenceDensity,
        surfaceRadius,
        gravityConstant
);
```

Parameters:

- `center`: world-space mass center, in blocks;
- `mass`: signed mass in the producer's chosen unit;
- `referenceDensity`: positive density/profile-shape parameter;
- `surfaceRadius`: positive physical surface radius, in blocks;
- `gravityConstant`: non-negative producer-chosen gravitational constant.

The evaluator captures these values. GravityEngine intentionally does not inject a universal `G`; choose a constant consistent with the content's unit scale.

Outside the physical surface, acceleration follows inverse-square `G*M/r^2`. Inside, it follows the engine's enclosed-mass profile. Use `sphericalCutoffRadius(...)` when deriving a finite influence radius from a minimum acceleration threshold.

### `height(...)`

```java
GravityField field = GravityFields.height(
        fullGravityY,
        zeroGravityY,
        accelerationMagnitude
);
```

This is an absolute world-space `-Y` field:

- at/below `fullGravityY`: `(0, -accelerationMagnitude, 0)`;
- strictly between the two heights: cubic smoothstep fade;
- at/above `zeroGravityY`: zero acceleration while the field may remain an active contribution.

It is suitable for world/layer base-gravity profiles where world `Y` is intentionally part of the field definition.

### `sphereInfluence(...)`

```java
GravityInfluenceVolume influence =
        GravityFields.sphereInfluence(center, radius);
```

Returns a finite spherical influence volume with closed-surface containment.

### `infiniteInfluence()`

```java
GravityInfluenceVolume influence =
        GravityFields.infiniteInfluence();
```

Returns a global influence volume. `finiteBounds()` is empty, so the engine treats it as a non-bucketed/global candidate rather than inventing artificial finite world bounds.

### Producer-side spherical helpers

`GravityFields` also exposes pure helper math so producers do not need to import internal `SphericalGravityMath`:

```java
double radius = GravityFields.uniformDensityRadius(
        absoluteMass,
        referenceDensity
);

double density = GravityFields.uniformDensity(
        absoluteMass,
        radius
);

double surfaceAcceleration = GravityFields.surfaceAccelerationMagnitude(
        gravityConstant,
        mass,
        surfaceRadius
);

double cutoffRadius = GravityFields.sphericalCutoffRadius(
        gravityConstant,
        mass,
        surfaceRadius,
        minimumFieldAcceleration
);
```

These are producer-side derivations. They do not sample a world or register a field.

## Built-in class names are not API

Do not write external code like this:

```java
// Do not do this in consumer code.
import cc.sighs.gravityengine.gravity.field.SphericalMassGravityField;
import cc.sighs.gravityengine.gravity.field.SphereInfluenceVolume;
```

Use `GravityFields.sphericalMass(...)` and `GravityFields.sphereInfluence(...)` instead.

The factory return type is the compatibility contract. The concrete implementation may move, be renamed, or be replaced without becoming an external migration requirement.

## Publication / registration status

The current loader-neutral API deliberately stops at the field/influence/composition definition boundary.

There is **not currently a supported public publication/registration facade under `cc.sighs.gravityengine.api.*`** that external mods should bind to for adding/removing a field from a live Minecraft Level.

Consequences:

- internal runtime/registry/service classes are not compatibility-stable publication APIs;
- target-specific registration plumbing is host integration, not part of the field SPI;
- this document does not invent a `register(...)`, `publish(...)`, key, revision, or lifecycle API that is absent from the supported package.

A future loader-neutral publication API should be promoted only after its contract explicitly defines at least:

- source/field identity;
- Level/side ownership;
- monotonic revision or replacement semantics;
- deterministic ordering;
- influence + composition association;
- add/update/remove lifecycle;
- thread requirements;
- unload/disconnect cleanup;
- behavior across target versions/loaders.

Until then, consumers should isolate any target/internal integration behind their own adapter so it can be replaced when the official publication surface is introduced.

## Migration from old StarminerR gravity API

GravityEngine is the new owner of reusable gravity-field contracts. Migrate **semantics**, not the old repository's package topology.

| Old StarminerR concept | GravityEngine supported replacement |
| --- | --- |
| field evaluator | `cc.sighs.gravityengine.api.field.GravityField` |
| field query | `GravityFieldQuery` |
| field sample/result | `GravityFieldSample` |
| influence volume | `GravityInfluenceVolume` |
| finite influence bounds | `GravityFieldBounds` |
| additive/override vocabulary | `GravityFieldCompositionMode` |
| zero-gravity evaluator implementation | `GravityFields.zeroGravity()` |
| spherical mass evaluator implementation | `GravityFields.sphericalMass(...)` |
| height/base-Y evaluator implementation | `GravityFields.height(...)` |
| sphere influence implementation | `GravityFields.sphereInfluence(...)` |
| infinite influence implementation | `GravityFields.infiniteInfluence()` |
| spherical helper math implementation | `GravityFields.uniformDensityRadius(...)`, `uniformDensity(...)`, `surfaceAccelerationMagnitude(...)`, `sphericalCutoffRadius(...)` |
| old runtime/service/registration implementation classes | No supported loader-neutral public replacement yet; keep integration isolated rather than binding to GE internals. |

Do not retain a StarminerR import as a compatibility shim inside GravityEngine unless an explicit migration task defines such a shim. StarminerR should consume GravityEngine through the supported API instead.

## Compatibility guarantees

For types documented here, changes should preserve the documented source-level semantics unless an intentional API revision is made.

Compatibility-sensitive behavior includes:

- package/type/method/record signatures;
- validation of finite/non-negative values;
- value ownership of immutable `Vec3d`;
- zero-sample meaning;
- influence-bound inclusion rules;
- ADDITIVE/OVERRIDE meaning;
- evaluator purity/thread contract;
- built-in factory semantics.

The following are **not** currently compatibility guarantees:

- internal class names or constructors;
- internal package layout;
- internal registries/services/runtimes;
- Mixin locations, target adapters, or loader events;
- target-specific network implementation;
- internal collision/kinematic/attitude types;
- private algorithm decomposition that does not change the supported API's observable semantics.

## API change checklist

Before changing `cc.sighs.gravityengine.api.*`, verify all of the following:

- Does the new contract remain loader-neutral?
- Can it be expressed without Minecraft/loader/internal GE types in its signature?
- Is the semantic owner clear, or is the API exposing an implementation detail?
- Are value-immutability/mutation rules explicit?
- Are numeric validation and units explicit enough for independent consumers?
- Are lifecycle/thread rules explicit if the API owns state?
- Are deterministic ordering/composition rules explicit if multiple producers interact?
- Have Javadocs and this document been updated together?
- Do focused tests cover the compatibility-sensitive behavior?
- Does the authoritative target still build against the changed API?

A new API should be added only when its semantic contract is stable enough that GravityEngine is willing to support consumers depending on it.
