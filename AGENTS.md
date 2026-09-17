# AGENTS.md

## Project mission

GravityEngine is a reusable Minecraft gravity and body-attitude engine. It owns loader-neutral gravity-domain values, field mathematics, kinematic/collision kernels, attitude mathematics, and the stable APIs intentionally exported by `cc.sighs.gravityengine.api.*`. Minecraft-, loader-, Mixin-, rendering-, packet-, registry-, and lifecycle-specific integration belongs in versioned targets under `targets/`.

GravityEngine was extracted from StarminerR, but StarminerR is now a separate content/application mod that consumes GravityEngine. Migration history is evidence, not ownership: do not reintroduce `starminerr` as GravityEngine's canonical package, mod id, resource namespace, system property, internal bridge identity, or architecture owner.

The repository identity is:

- Java/Gradle group: `cc.sighs.gravityengine`
- mod id: `gravityengine`
- mod name: `GravityEngine`
- shared kernel baseline: Java 17
- supported loader-neutral field API math value: `cc.sighs.gravityengine.api.math.Vec3d`

## Instruction and evidence precedence

When instructions or documentation disagree, use this order:

1. The current user request.
2. The current checked-out target's version-matched Minecraft/loader contract.
3. Current source behavior verified by focused tests or a reproducible build.
4. This `AGENTS.md` and the documented public API boundary.
5. Historical StarminerR code, migration notes, comments, plans, and old implementation details.

Current code is not automatically correct, but do not force verified GravityEngine code back into a stale StarminerR design merely because the old repository used different class names or ownership boundaries.

If a public API change intentionally contradicts `docs/API_BOUNDARY.md`, update that document and the package Javadocs in the same change.

## Start every task

Before editing:

1. Read the repository layout, root Gradle files, `gradle.properties`, the affected target's Gradle/metadata/Mixin configuration, and relevant tests.
2. Inspect `git status` and preserve unrelated user changes.
3. Identify whether the task belongs to `common`, one target, several targets, or the supported public API.
4. State the producer -> owner -> consumer -> lifecycle path for any stateful change.
5. Prefer the smallest coherent change that preserves cross-target boundaries.
6. For Minecraft/loader changes, inspect the version-matched target and version-difference notes instead of assuming another target has the same seam.

Do not perform unrelated cleanup during a focused fix. Record follow-up debt separately.

## Repository topology

### Java baselines

`common` is the loader-neutral compatibility baseline and must compile as
Java 17 source/API/classfile output. Targets retain the Java baseline required
by their own Minecraft/loader version: the 1.20.1 targets use Java 17,
`neoforge-1.21.1` uses Java 21, and `neoforge-26.1` uses Java 25. A newer
target may consume Java 17 `common` bytecode.

### `common/`

`common` is the loader-neutral Java kernel/API. It must remain independently compilable without Minecraft or any loader on its compile classpath.

Allowed in `common`:

- Java 17 standard library;
- GravityEngine-owned immutable mathematical values (`cc.sighs.gravityengine.api.math.Vec3d`
  and internal `cc.sighs.gravityengine.math.Quatd`);
- pure immutable/value-oriented domain models;
- gravity field interfaces and mathematical evaluators;
- collision and kinematic kernels;
- body-attitude mathematics/runtime values that do not require Minecraft ownership;
- protocol-neutral data and validation logic that do not import a platform transport.

Forbidden in `common`:

- `net.minecraft.*` imports;
- Forge, NeoForge, Fabric, or Mixin imports;
- client renderer/camera classes;
- platform packet APIs;
- platform registry/event APIs;
- direct loader lifecycle hooks;
- target-specific source sets or compatibility branches disguised as generic code.

### `common/src/main/java/cc/sighs/gravityengine/api/`

This is the compatibility boundary for external consumers. Java `public` visibility elsewhere does **not** make a type supported API.

At the current API generation, the supported external namespace is:

- `cc.sighs.gravityengine.api.*`
- `cc.sighs.gravityengine.api.field.*`
- `cc.sighs.gravityengine.api.math.Vec3d`

`cc.sighs.gravityengine.math.*` remains internal except where a supported
`cc.sighs.gravityengine.api.*` type explicitly exposes behavior. The exact API
contract is documented in `docs/API_BOUNDARY.md` and in package/type Javadocs.

### Internal `common` packages

Packages such as these are implementation details unless a future API document explicitly promotes them:

- `cc.sighs.gravityengine.gravity.*`
- `cc.sighs.gravityengine.attitude.*`
- `cc.sighs.gravityengine.math.*`
- `cc.sighs.gravityengine.network.*`
- `cc.sighs.gravityengine.protocol.*`

Do not make external examples, target adapters, or compatibility promises depend on an internal class merely because it is currently `public`.

Current responsibility split is semantic rather than a promise that package names never change:

- `gravity.field`: built-in field/influence implementations behind the public factories;
- `gravity.model`: internal gravity application/authority/capability values;
- `gravity.collision`: internal collision values and kernels;
- `gravity.kinematic`: internal kinematic solver machinery;
- `gravity.movement`: internal movement-domain values/kernels;
- `attitude`: internal body-attitude model/runtime;
- `math`: reusable internal mathematical primitives;
- `network` / `protocol`: internal synchronization/representation details, not a public transport API.

Move a class when its semantic owner changes. Do not introduce forwarding interfaces, service locators, or package-level facades solely to satisfy a source-layout assertion.

### `targets/<loader>-<minecraft-version>/`

A target owns all loader/Minecraft integration for that exact loader/version, including as applicable:

- mod entry point and metadata;
- Minecraft-facing adapters;
- Mixins and accessors;
- loader events and registries;
- platform networking;
- client/render integration;
- version-specific Vanilla seams;
- target-specific build and run configuration.

Keep targets thin. They may adapt Minecraft/loader concepts into `common` values and commit common results back to the platform, but must not fork the gravity mathematics or maintain a second implementation of a common algorithm.

Do not duplicate the same Java class in `common` and a target.

## Current target status

Treat target maturity as an explicit repository fact, not an assumption from directory presence.

- `neoforge-1.21.1` is the current authoritative migrated target.
- `fabric-1.20.1`, `forge-1.20.1`, and `neoforge-26.1` are ports/scaffolding until their integration is independently completed and verified.
- `neoforge-26.1` requires JDK 25; do not silently include it in a JDK 21 build matrix.

When porting, preserve semantics from the authoritative implementation but relocate the actual version-matched Vanilla/loader seam. Do not mechanically copy Mixins, method descriptors, ordinals, event names, packet APIs, or loader bootstrap code between versions.

## Public API contract

### Supported namespace

Only `cc.sighs.gravityengine.api.*` is intended as a source/binary compatibility
surface. At present, the supported public API includes `api.field` and the
immutable value type `api.math.Vec3d`.

A public API change includes:

- adding/removing/renaming a public API type;
- changing a public method/constructor signature;
- changing record component order or type;
- changing enum semantics;
- changing validation rules or value ownership in a way callers can observe;
- leaking an internal type into an API signature;
- changing documented thread, purity, unit, composition, or lifecycle semantics.

Any such change requires an explicit compatibility decision and synchronized
updates to Javadocs, `docs/API_BOUNDARY.md`, and focused API tests.

### API dependency direction

Public API signatures may depend only on:

- Java 17 standard-library types;
- `cc.sighs.gravityengine.api.math.Vec3d`;
- other supported `cc.sighs.gravityengine.api.*` types.

A public API signature must not expose `gravity.*`, `attitude.*`, `math.*`, `network.*`, `protocol.*`, Minecraft, loader, or Mixin implementation types.

Built-in factories may internally instantiate implementation classes, but callers must receive only supported API interfaces/value types.

### Value ownership

API values use immutable GravityEngine-owned `Vec3d`. Caller/evaluator isolation is guaranteed by value immutability rather than defensive copying of mutable vectors. Do not replace `Vec3d` with a mutable or platform vector in a supported API signature.

Reject non-finite public numeric state at the API boundary where the current contract requires it. Do not let NaN/Infinity become hidden revision, ordering, or spatial-index state.

## Gravity field model

### Evaluator purity

`GravityField` is a mathematical evaluator:

`GravityFieldQuery -> GravityFieldSample`

A field evaluator owns no:

- registration/source identity;
- influence geometry;
- composition mode;
- ordering key;
- revision;
- Level/Entity reference;
- collision geometry;
- loader object;
- mutable world lookup.

Implementations must be deterministic and side-effect free for the same query. They are evaluated on the thread that owns the Level to which the field is published, but they must not read or mutate that Level.

Dynamic world state must be captured by the publication/integration owner into immutable evaluator/configuration state before evaluation; the evaluator itself does not reach back into Minecraft.

### Query and sample semantics

`GravityFieldQuery` is an immutable snapshot of position, velocity, game tick, and interval length. Its `Vec3d` components are immutable values, so accessors do not expose mutable state. `gameTick` and `intervalTicks` follow their current non-negative validation rules.

`GravityFieldSample` is a finite acceleration result. `GravityFieldSample.ZERO` is a valid sample and does **not** mean that the registered field is absent.

Never infer contribution presence from acceleration magnitude.

### Influence is separate from evaluation

`GravityInfluenceVolume` determines candidate/activity geometry before a field is evaluated.

- finite volumes return inclusive `GravityFieldBounds` that conservatively contain the full volume;
- infinite volumes return `Optional.empty()` from `finiteBounds()` and belong to a global/non-bucketed candidate set;
- `contains(position)` is the exact activity predicate for the influence volume;
- bounds are indexing data, not a substitute for the exact `contains` predicate.

Do not add collision shapes to the public field-influence API. Gravity influence geometry and physical collision geometry are separate capabilities.

### Composition belongs to registration

`GravityFieldCompositionMode` is registration metadata, not evaluator behavior.

- If no active `OVERRIDE` field exists, active `ADDITIVE` fields compose by deterministic world-space vector sum.
- If one or more active `OVERRIDE` fields exist, active `ADDITIVE` fields are excluded and all active `OVERRIDE` fields compose by deterministic world-space vector sum with each other.
- There is no implicit first-wins, strongest-wins, nearest-wins, evaluator-type priority, or zero-vector absence rule.

Do not branch on concrete evaluator class to choose composition semantics.

### Built-in implementations are behind factories

External consumers should use `GravityFields`, not internal implementation classes.

Supported factories currently include:

- `zeroGravity()`;
- `sphericalMass(...)`;
- `height(...)`;
- `sphereInfluence(...)`;
- `infiniteInfluence()`;
- spherical producer-side helper math exposed by `GravityFields`.

Internal classes such as `ZeroGravityField`, `HeightGravityField`, `SphericalMassGravityField`, `SphereInfluenceVolume`, `InfiniteInfluenceVolume`, and `SphericalGravityMath` remain replaceable implementation details.

### Registration/publication boundary

The current supported `api.field` package defines fields, queries, samples, influence volumes, bounds, composition vocabulary, and built-in factories. It does **not** currently expose a loader-neutral public registration/publication facade.

Until such a facade is deliberately promoted into `cc.sighs.gravityengine.api.*`:

- do not document an internal runtime/registry class as stable external API;
- do not make content mods compile against an internal field runtime merely for convenience;
- keep target/host publication integration behind adapters;
- when a stable publication API is added, define source identity, revision/lifecycle, ordering, Level ownership, replacement/removal, and side behavior explicitly before exposing it.

## StarminerR migration boundary

GravityEngine inherits reusable gravity concepts, not StarminerR application ownership.

When migrating old StarminerR code:

- replace old field SPI references with `cc.sighs.gravityengine.api.field` where an exact supported counterpart exists;
- use `GravityFields` factories instead of naming migrated built-in implementation classes;
- keep StarminerR-specific blocks, entities, world generation, gameplay policy, configuration ownership, and content IDs in StarminerR;
- keep Minecraft/loader adapters in a GravityEngine target only when they implement a reusable engine capability;
- do not copy old StarminerR package names or resource identities into GravityEngine;
- do not treat historical StarminerR runtime/service class names as compatibility requirements;
- do not expose an internal GE class solely to make an old StarminerR import compile.

Migration succeeds when the reusable semantic contract is preserved with one owner, not when historical source topology is reproduced.

## Determinism and numerical rules

Gravity behavior that can affect simulation must be deterministic for identical immutable inputs.

- Normalize only where the owning algorithm requires normalization; do not silently normalize values whose magnitude is semantic.
- Reject or explicitly handle non-finite public values.
- Stable ordering must be explicit where floating-point accumulation order can change results.
- Do not use approximate vectors/quaternions as identity/revision keys.
- Do not average gravity directions as a substitute for composing acceleration vectors.
- A zero acceleration result is still a real result.
- Preserve algorithmic operation ordering during refactors unless a test/documented behavior change explicitly authorizes a numerical change.

## Multi-version and loader integration

Port semantic intent, not bytecode locators.

For every non-trivial target bridge, identify:

- the Vanilla/loader operation being intercepted;
- which side owns the authoritative result;
- which representation is translated into/out of `common`;
- the state commit/lifecycle boundary;
- fallback behavior when GravityEngine capability is inactive;
- the version-specific locator that may change in the next port.

Mixins should locate seams and delegate. Reusable mathematics belongs in `common`; reusable loader-neutral API contracts belong under `api` only after a compatibility decision.

Client-only code must remain outside dedicated-server loading paths.

Use `docs/version-differences/` as migration evidence. Those notes help locate version changes but do not override verified current source.

## Networking and body attitude

`common` may contain protocol-neutral state/math, but a concrete packet transport belongs to a target.

Do not promote `network.*`, `protocol.*`, or `attitude.*` to supported API merely because an external mod asks for one internal value. First define the semantic ownership, thread/lifecycle contract, compatibility requirements, and smallest loader-neutral surface.

A body-attitude API and a gravity-field API are distinct capabilities. Do not make field evaluation depend on rendering, camera state, local player input, or packet transport.

## Performance rules

- Do not optimize by weakening immutability, deterministic ordering, validation, or API isolation.
- Do not cache mutable caller-owned platform or JOML objects in public value types; supported values use immutable `Vec3d`.
- Avoid allocations only after measuring a relevant hot path; API defensive-copy guarantees take precedence over speculative micro-optimization.
- Spatial bounds are broad/candidate data. Preserve the exact influence predicate where required.
- Keep loader/world lookups out of pure evaluators and kernels.

## Documentation rules

`docs/API_BOUNDARY.md` is the human-facing contract for the supported external API.

When the public API changes, update in the same change:

1. API source/Javadocs;
2. `docs/API_BOUNDARY.md`;
3. examples that use affected signatures;
4. focused tests for validation/ownership/semantics;
5. migration notes when an existing consumer must change.

Do not document planned API as if it already exists. Clearly label experimental/internal integration seams.

## Verification

Use the repository wrapper and the actual target JDK.

Common API/kernel verification on Windows/CI parity:

```powershell
.\gradlew.bat -p common clean test --console plain --no-daemon
```

Build the authoritative migrated target with JDK 21:

```powershell
.\scripts\build-target.ps1 -Target neoforge-1.21.1
```

Equivalent aggregate selection:

```powershell
.\gradlew.bat '-Ptarget=neoforge-1.21.1' build
```

The root JDK-21 aggregate can build the enabled JDK-21 target set with:

```powershell
.\gradlew.bat -PallTargets=true build
```

`neoforge-26.1` requires its own JDK 25 build. Do not report it as verified by a JDK 21 aggregate run.

For documentation-only changes, at minimum verify type names, signatures, package boundaries, links, examples, and the diff. Do not claim game/client/server verification that was not run.

For code changes affecting a target's Mixins, networking, metadata, client/server isolation, or registration/lifecycle, also run the relevant target build/tests and applicable client/dedicated-server smoke checks provided by that target.

If the repository currently has no focused test for a changed public contract, add one rather than relying only on compilation.

## Review checklist

Before declaring a GravityEngine change complete, check:

- Is `common` still free of Minecraft/loader/Mixin imports?
- Is every externally supported signature contained in `cc.sighs.gravityengine.api.*`?
- Did an internal type accidentally leak into a public API signature or example?
- Are StarminerR identity and application policy still outside GravityEngine?
- Does field evaluation remain pure and independent of live world/entity state?
- Are influence geometry and evaluator mathematics still separate?
- Is contribution presence independent from result magnitude?
- Are ADDITIVE/OVERRIDE semantics deterministic and registration-owned?
- Are built-in implementations reached through `GravityFields` rather than documented internal constructors?
- Did a target adapter remain version-specific instead of moving Minecraft code into `common`?
- Did a port preserve semantic intent rather than copied Mixin ordinals/descriptors?
- Are API vector ownership and finite-value checks preserved?
- Are target maturity/JDK assumptions accurate?
- Are Javadocs and `docs/API_BOUNDARY.md` synchronized with the implementation?
- Were only the checks actually executed reported as passing?

## Definition of done

A task is complete when:

1. the requested behavior/documentation is implemented with the smallest coherent change;
2. ownership and package boundaries remain explicit;
3. supported API changes have synchronized source, Javadocs, docs, examples, and tests;
4. applicable common and target verification passes, or exact blockers are reported;
5. no unrelated files or user changes are overwritten;
6. no stale StarminerR identity or internal GE implementation type is introduced into the supported API;
7. remaining risks, unverified targets, and intentionally experimental/internal seams are stated explicitly.
