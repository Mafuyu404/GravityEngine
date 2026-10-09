# Sable compatibility review — 2026-10-09

Target: Minecraft 1.21.1, NeoForge 21.1.256, Sable **2.0.6**, Java 21.
This is an assessment of the existing adapter, not a claim of complete Sable
feature parity. Other loader targets do not contain this integration.

## Composed-motion implementation checkpoint

The common collision kernel now accepts an internal immutable `RigidTrajectory`
for both single-body motion and captured parent/child composition. Collision
poses, swept enclosures, contact velocity and support transport use that same
trajectory. Composite velocity includes both components once; chord subdivision
uses a conservative acceleration bound including the Coriolis term. The
single-axis clearance shortcut is restricted to single-body publications.

This is partial implementation of the requested NeoForge 21.1.256 / Sable 2.0.6 /
Create 6.0.10 / Aeronautics Bundled 1.3.2 work, **not complete compatibility**.
The Sable adapter still publishes single-body endpoint motion and rejects
collidable contraptions. Plot rebasing, sampled substep motion, lifecycle coverage,
swimming/climbing/scaffolding, vehicle interactions, sparse attached-mass capture,
lift operands and the three runtime profiles still need implementation. Existing
fixed-reach and unsupported-geometry failures have not been removed. Native
character/vehicle coupling still needs its requested audit.

Client/multiplayer acceptance and comparative performance measurements have not
been run for this checkpoint. Full-stack acceptance remains blocked by unfinished
integration and missing required runtime evidence. See the new checkpoint in
[REFACTOR_STATUS.md](REFACTOR_STATUS.md) for fresh commands and results; historical
PASS results below do not certify the requested full stack.

## Verdict

**PASS within the exercised server scope; compatibility is incomplete.**
The updated artifact compiles, its Mixin seams apply, the absent-mod server
works, and the strict installed-mod server passes all eleven required areas.
No production algorithm change was needed for this Sable patch update.
New regression coverage verifies distributed torque and all-or-nothing rejection
when the second mass point has incomplete FIELD evidence.

Client presentation/network ordering, fluid-surface locomotion and several
unsupported geometry domains remain outside this acceptance. Passing a server
fixture is not proof of multiplayer or packaged-client behavior.

## Version and source evidence

- Official [Sable release](https://github.com/ryanhcode/sable/releases/tag/mc1.21.1-2.0.6-neoforge),
  commit `ad8b4d3`, and
  [tag comparison](https://github.com/ryanhcode/sable/compare/mc1.21.1-2.0.5-neoforge...mc1.21.1-2.0.6-neoforge).
  The NeoForge-relevant behavior change corrects the fluid argument passed to
  `LivingEntity.canStandOnFluid`; the other code changes concern Fabric networking.
- The official [Modrinth release](https://modrinth.com/mod/sable/version/fg9dTRz9)
  and the actual resolved Maven runtime have identical SHA-512:
  `76d293ce751a01cb9c095a3659b0a41e03e005480bc9d76dfdc73ec7c2eabf7923740c7b077921d42616673c496cd5143569f2085ddcebe174fd91d81e2a04b8`.
- Embedded dependencies remain companion **1.6.0** and Veil **4.3.2**;
  Rapier is **2.0.6**. Keep the companion compile dependency at the embedded ABI:
  Sable explicitly rejects companion versions newer than 1.6.0.
- Reviewed tagged `SubLevel`, `ServerSubLevel`, `SubLevelPhysicsSystem`,
  `LevelPlot` and `SubLevelEntityCollision` sources. These match the local
  reference source. Actual release bytecode was also inspected with Java 21
  `javap -p -c -s`; output is `build/sable-2.0.6/seams-javap.txt`.
- All NeoForge attachment package sources are unchanged between 21.1.249 and
  21.1.256. Fresh transformed lifecycle controls validate the upgraded target;
  no attachment/persistence ownership migration was introduced.

## Correctness and ownership

| Area | Assessment and evidence |
| --- | --- |
| Optional loading | Sable is compile-only in ordinary runs. The compatibility plugin guards Sable Mixins, the facade delays adapter loading, and exact optional metadata pins 2.0.6. The ordinary server and independent API artifact loading pass without Sable. |
| Mixin ABI | Required injection points still apply. `collide` retains two `getFeetPos` calls and two raw position writes; only the first raw write is adapted. Bounding-box ordinals/slices and the local intersecting set survive transformed launch. `initialize` still calls `PhysicsPipeline.init(Vector3dc,double)`. `applyQueuedForces(SubLevelPhysicsSystem,RigidBodyHandle,double)` runs before each physics substep. |
| Collision authority | On the frozen GE exact-body route, `parentOnly` bypasses Sable's character solve and clears Sable tracking/inherited-motion carriers. Sable retains its native route otherwise. Strict controls verify single solving, packet occupancy, native-mode handoffs and passive entities. Sable owns rigid pose and integration throughout. |
| Geometry and coordinates | Plot voxels become immutable oriented obstacles, captured with the operation's pose/motion. Subject-specific collision shapes and plot-space `isAbove` are used. Tests cover translated/rotated geometry, changed local shape and material, context-sensitive blocks, and frozen packet captures across removal. |
| Support transport | Contact identity, local geometry and lifecycle epochs validate carry. Tests exercise elevators, translation, rotation, acceleration, reversal, stop, walkoff and real Rapier-driven movement. Stone/slime/bed landing checks exercise normal surface velocity and avoid duplicate bounce/release impulses. |
| Lifecycle | Bounds updates publish; block changes retire affected primitive identities; removal and Level unload invalidate sources. Registration/re-registration and unload are tested. Arbitrary client chunk-arrival/replacement order is not established by those fixtures. |
| Rigid gravity | Sable owns mass, COM, inertia, pose and velocities. GE samples mass points and submits one velocity increment before each solver substep; it stores no competing rigid angular state. Tick acceleration converts by 400, velocity by 20, and the captured native baseline is subtracted only where a complete active field applies. Absent or disabled fields leave native gravity intact. |
| Distributed torque | New rotated two-stone fixture checks the analytic angular impulse against `I_world * deltaOmega`. A spatially odd field distinguishes distributed torque from a COM-only approximation. A second provider reports incomplete coverage at the later mass point; both linear and angular velocities must remain unchanged. The strict gate requires the new `distributed-gravity` marker. |
| Failure behavior | Unsupported motion/geometry and collision budgets raise coverage failures rather than successful empty scenes. Incomplete gravity sampling or mass disagreement makes no partial velocity commit. Unexpected provider errors retain the engine's propagation behavior. |

The index and primitive IDs are transient per-Level collision evidence; they are
not durable field assignment or Sable pose authority. Gravity baseline capture is
per physics-system lifetime. FIELD coverage is evaluated for each query; registry
emptiness is never promoted into completeness. No new persistence, readiness,
transport protocol or physical state owner was added in this update.

## Limits and remaining acceptance work

| Priority | Gap | Current behavior / required evidence |
| --- | --- | --- |
| High | Real clients and multiplayer | **NOT RUN.** Verify riding/carry during delayed or reordered SubLevel pose/entity correction packets, login/respawn/dimension changes, camera/body rendering, remote players and chunk streaming. Dedicated-server delayed-endpoint fixtures do not reproduce client networking. |
| High | Fluids on moving plots | **NOT VERIFIED.** The 2.0.6 upstream fluid-standing fix does not establish gravity-aware fluid surfaces in GE's voxel-collision route. Test Striders, water/lava surfaces and swimming on rotated/moving SubLevels before claiming support. |
| High | Kinematic/Create contraptions | **UNSUPPORTED by GE rigid collision.** A relevant valid collidable contraption rejects capture; it needs its own motion publication. The gravity bridge uses COM-only fallback when contraptions are present, so distributed/tidal behavior is not implemented there. Create/Aeronautics were not installed in these gates. |
| Medium | Scale/pivot/deformation | Fixed positive scale can be represented by collision boxes. Changing scale/pivot, nonpositive scale and nonrigid geometry are rejected. Gravity uses COM-only fallback for non-unit scale. Dedicated scaled-body and contraption fallback force tests remain absent. |
| Medium | Scaffolding and special locomotion | Nearby scaffolding rejects GE capture. Shape context alone cannot implement climbing/descending policy. Native fallback remains capability-dependent; the existing mode tests are not exhaustive entity/mod compatibility. |
| Medium | Motion bounds | Publications cover one tick and refuse material-point displacement above 16 blocks. Endpoint shortest-arc rotation cannot certify arbitrary intra-tick rotation or multiple revolutions. High angular-speed/teleport cases require separate evidence and potentially a richer motion contract. |
| Medium | Plot chunk lifecycle | Source review confirms chunk-holder addition updates plot bounds and full removal clears loaded holders; bounds publication and source removal are separate hooks. Test same-bounds chunk replacement and actual client load/unload ordering before claiming complete streaming coverage. |
| Medium | Gravity cost and budget | Distributed capture scans up to 65,536 bounding-box positions per body per physics substep and samples each positive mass point. Budget/mass mismatch leaves Sable baseline gravity for that step. Large/sparse fleets and configurable substep counts need profiling; no performance acceptance is claimed. |
| Medium | Two-way physical response | Character collision/carry does not implement general character-to-rigid reaction impulses. One-way kinematic contact is not a complete coupled rigid/character solver. |

## Reproduction and evidence

Use the primary target's wrapper with Java 21 and accepted EULAs in both
verification directories:

```powershell
.\targets\neoforge-1.21.1\gradlew.bat -p targets/neoforge-1.21.1 build dynamicsCoreVerification sableCompatibilityVerification --console plain --no-daemon
```

The gates delete old result files before launching. Required fresh outputs are:

- `targets/neoforge-1.21.1/run/control-verification/control-boundary-result.txt`
- `targets/neoforge-1.21.1/run/sable-verification/control-boundary-result.txt`
- `targets/neoforge-1.21.1/run/sable-verification/sable-checks-result.txt`

The installed manifest must say `PASS Sable 2.0.6` and contain contacts, context,
continuity, delayed-endpoints, distributed-gravity, gravity, landing-commit,
modes, passive-entities, physics and transport. Final commands, logs and statuses
are recorded in [REFACTOR_STATUS.md](REFACTOR_STATUS.md).
