# Standardization checkpoints

## CP1 — implementation checkpoint

Input: `GravityEngine_Standardization_Two_Checkpoints.md`. Baseline:
`186f70fa765e1565c428768a2a5d5af178b0d8fc`. CP1 implementation committed as
`288bb99` on 2026-10-09. Scope: common Java 17 and NeoForge 1.21.1,
NeoForge 21.1.249; optional compatibility tested with Sable 2.0.5.

The pre-existing edits in `targets/neoforge-1.21.1/build.gradle` and
`src/main/resources/META-INF/neoforge.mods.toml` were preserved. Other targets,
root build orchestration, release configuration, save formats and network
protocols were not migrated. Nothing was pushed, tagged or published.

### Implementation closure

| Item | Implementation and direct evidence |
| --- | --- |
| CP1-01 | `GravityFieldService.compose` now adapts to `contributions` and `composeContributions`; the second summation algorithm is removed. All active evaluators validate before OVERRIDE selection. Duplicate identity, overflow, invalid intervals, zero presence and excluded evaluator failures have common regression coverage. Registry evaluation explicitly covers only its local publications. |
| CP1-02 | One spatial index per publication owner replaces the shared candidate index. Provider sampling selects its partition and performs contains once, then samples already-selected sources. Stable provider/evaluator ordering and global sum ordering remain. Identity validation reuses a compiled pattern; sorting no longer allocates parsed IDs per comparison. Measurements compare actual baseline/current production classes. |
| CP1-03 | `GravityFieldProvider.blockDiscovery` supports bounded non-publication sources, explicit unbounded traversal and sampling-only defaults. Publication bounds are captured once during successful preparation. Native chunk events feed an owned round-robin key queue; source updates cannot reset it. Section/cell and removal-cleanup cursors retain progress; unload removes both queued and active work. Real-server checks discover 40 chunks during every-tick replacement and clean each affected position once on removal. Existing native scheduling, freeze, readiness and duplicate-tick checks pass. |
| CP1-04 | Capture records unavailable chunk domains, including shape/neighbor dependencies. Movement, support, pose and material/cell queries reject only relevant gaps. No solve-time world read is added. Native-proven space outside build height is not marked unavailable. Common gap tests and a transformed loaded-interior/missing-neighbor/void test verify this. Existing geometry and movement failure boundaries preserve installed ownership. |
| CP1-05 | Qualified rigid identities have direct lookup. Cell lookup builds a private immutable-value map on first use and preserves multiple primitives. Repeated block broadphase scans activate a linear-build, packed hierarchy after four scans for scenes of at least 64 primitives; short-lived scenes keep linear queries. Large primitives occur once. A fixed-seed oracle compares indexed and linear results, including negative coordinates and large bounds. Construction, total query work, allocations and lookup costs are measured separately. |
| CP1-06 | Internal rigid providers can prove `mayAffect(query)==false` for a complete swept domain/time. Unknown providers default to true. Capture skips proven-empty providers; outer movement can bypass scene creation only when committed state allows it and no support/sublevel continuity could widen the trajectory. Common tests cover empty, distant, swept-intersecting and unknown sources. Travel/pose paths and Sable remain conservative without a suitable proof. |
| CP1-07 | Removed duplicate composition, the shared publication candidate path, the discovery revision mirror, retained field-list mirror and live ChunkHolder iterator. Kept independent assignment/application/geometry, FIELD coverage/presence, publication token/revision and durable/sync revisions. Existing attachment, replacement and synchronization controls pass unchanged. Architecture tests retain real dependency and platform-seam checks; no arbitrary layout rewrite was needed. |
| CP1-08 | Field callback failures identify provider, Level and phase and retain causes/cleanup failures. Publication failures retain argument exception types with contextual diagnostics. Geometry application now handles coverage/budget refusal explicitly and propagates unexpected failures after rollback instead of swallowing them. A real pre-connection geometry failure test verifies committed state, anchor, proxy, velocity and installed axis. Existing work budgets and fail-closed collectors remain; discovery and scene candidate counters are aggregate/on-demand. |

### Ownership and complexity

| Fact | Producer / unique owner | Consumers, lifetime and invalidation |
| --- | --- | --- |
| Publication identity, owner, revision, release token | Provider input / `GravityFieldRegistry` | Provider sampling and discovery; replaced/released/unloaded atomically. Bounds and owner-partition indexes are derived captured data, not additional source authorities. |
| Contributions and coverage | Each provider / one query in `GravityFieldRuntime` | Global composition and existing reconciliation; never persisted or inferred from discovery. After reload, only an all-complete query makes absence authoritative. |
| Loaded discovery candidates and scan progress | Native chunk events / `FallingBlockRechecks` | Budgeted periodic work and one-time removal cleanup; chunk unload or Level disposal invalidates them. The queue is not a completeness oracle. |
| Block coverage, exact primitives, lookup indexes | Target capture / one `CapturedCollisionScene` | Movement, geometry, support and materials within that operation. Derived lazy indexes have only the scene lifetime; they cannot read or replace world evidence. |
| Durable assignment / live FIELD evidence / installed application | Existing persistence slot / entity state / application and geometry owners | Retained unchanged; no new readiness flags, generations, incarnation protocol or persistence copier. |

The packed index replaces an initially attempted recursively sorted hierarchy:
measurement showed that version spent more time building than it saved. Maps
and the hierarchy now initialize only when used. Small-scene bookkeeping and
index memory costs are reported rather than presented as free improvements.

### Contract corrections and validation

Maintained API declarations, package Javadocs, the supported consumer fixture and
`API_BOUNDARY.md` describe the minimal discovery hook and callback exception
migration. No CP2 API stability claim is made.

Two former assertions were corrected to match the implementation contract:
provider opening failure is now the contextual exception's original cause, and
native chunk reload queues discovery rather than immediately bypassing its work
budget. Cleanup-failure and unload checks were retained. The packet known-floor
fixture now loads the neighboring chunks its shape queries depend on. The
entities-unready fixture compares callback counts at entry to that phase, so
callbacks from earlier phases in a reused test world cannot create a false
failure. No behavior case was disabled or excluded.

Pinned `neoforge-21.1.249-sources.jar` `BlockCollisions` was inspected: native
discovery uses a one-cell shape margin and `getChunkForCollisions`, and invokes
shape callbacks with the collision getter. The target records missing domains
and accounts for adjacent-cell dependencies instead of treating skipped chunks
as known air. No Mixin descriptor or injection seam changed.

Commands run from repository root:

```powershell
.\targets\neoforge-1.21.1\gradlew.bat -p common test --console plain --no-daemon
.\targets\neoforge-1.21.1\gradlew.bat -p targets/neoforge-1.21.1 tasks --all --console plain --no-daemon
.\targets\neoforge-1.21.1\gradlew.bat -p targets/neoforge-1.21.1 build dynamicsCoreVerification sableCompatibilityVerification --console plain --no-daemon
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/measure-hot-paths.ps1 -JavaHome 'C:/Program Files/Java/jdk-21.0.11'
git diff --check
```

Baseline common tests passed before edits. Final combined build/dynamics/Sable
command exited 0 (75 seconds). Current common reports contain 427 tests and target
reports contain 74 tests, with zero failures, errors or skips. JVM controls and
consumer import checks passed. The fresh full server result is
`PASS 2264 sable=SKIPPED (not installed)`; the separate strict result is
`PASS contact compatibility 2265 sable=PASS 2.0.5`, with all ten required Sable
coverage labels. Optional-mod absence and presence are therefore separate evidence.
`git diff --check` passed. Earlier development failures described above were fixed;
old PASS files were not used.

Fresh artifacts:

- `build/hot-path-measurements/verification.log` and `measurement.log`.
- `common/build/test-results/test/` and `targets/neoforge-1.21.1/build/test-results/test/`.
- `targets/neoforge-1.21.1/run/control-verification/control-boundary-result.txt`.
- `targets/neoforge-1.21.1/run/sable-verification/control-boundary-result.txt`
  and `sable-checks-result.txt`.

The fairness control used 40 explicitly populated chunks plus the native loaded
queue. It passed through 2,078 real server ticks. At the measurement boundary,
base/strict runs respectively reported 52,914/52,852 cumulative discovery visits,
a 245-key recurring discovery queue and oldest active-task ages of 112/120 ticks.
These counters describe the whole control sequence, not a per-source latency SLA.

### Performance evidence and limits

The standalone test-only measurement program compiles the baseline common source
from `git archive` and the current common source with the same `--release 17`
compiler settings. It runs each in a separate JVM with `-Xbatch -Xms256m -Xmx512m`, 500
warmup operations and 200 timed samples per scenario. Inputs use deterministic
grids (seed 0); timed loops do not print. It measures median/p95, thread allocation,
contains/evaluator counts and block candidate visits. Baseline block visits are
the known full-list scan count; current visits are the scene's measured counter.
The baseline field branch invokes the original registry/filter/contribution path;
the current branch uses the owner-scoped query through reflection.

Machine: Windows 11 10.0.26300, AMD Ryzen 9 9950X (16 cores / 32 logical CPUs),
approximately 61.5 GiB visible RAM. Final measurement JDK: Oracle 21.0.11.
The game gates likewise use Java 21; common production remains Java 17 compatible.
Raw CSVs, exported source/classes and environment metadata are under
`build/hot-path-measurements/`. These are common hot-path measurements, not a claim
of improved whole-game TPS, client FPS or tail latency under every workload.

Selected final results (microseconds; allocation is bytes per complete sample):

| Scenario | Median before / after | p95 before / after | Allocation before / after | Work before / after |
| --- | ---: | ---: | ---: | --- |
| Empty, 1 provider | 1.9 / 1.8 | 3.2 / 2.2 | 1,992 / 1,448 | 0 evaluations |
| 512 sources, 1 provider | 282.3 / 209.5 | 322.7 / 228.6 | 2,375,776 / 910,728 | contains 1,024 / 512 |
| 512 sources, 8 providers | 1,113.3 / 208.0 | 1,189.9 / 218.4 | 5,318,872 / 809,296 | contains 4,608 / 512 |
| 512 sources, 32 providers | 3,898.4 / 194.5 | 4,376.6 / 207.4 | 15,381,704 / 706,672 | contains 16,896 / 512 |
| 512 unbounded sources, 8 providers | 1,115.4 / 205.4 | 1,332.0 / 231.2 | 5,285,848 / 805,328 | contains 4,608 / 512 |
| 16 primitives, build + 32 queries | 0.9 / 1.1 | 1.0 / 1.1 | 2,968 / 4,808 | visits 512 / 512 |
| 256 primitives, build + 32 queries | 5.2 / 3.0 | 5.5 / 3.2 | 4,888 / 14,848 | visits 8,192 / 1,248 |
| 4,096 primitives, build + 1 query | 6.6 / 7.2 | 7.0 / 7.6 | 33,128 / 33,296 | visits 4,096 / 4,096 |
| 4,096 primitives, build + 32 queries | 98.1 / 30.5 | 101.7 / 31.5 | 35,608 / 137,728 | visits 131,072 / 16,608 |
| 4,096 primitives, build + 128 queries | 375.5 / 38.1 | 387.7 / 40.0 | 43,288 / 156,928 | visits 524,288 / 17,376 |
| 4,096 primitives, build + 128 cell lookups | 343.6 / 50.9 | 354.5 / 55.3 | 49,432 / 236,032 | direct cell lookup after one build |

Each nonempty field case still calls every source evaluator exactly once. Unbounded
sources retain the cost of visiting all owned global candidates; unrelated provider
partitions no longer multiply that work. Sorting remains at provider-local
ordering and global accumulation boundaries.

Index construction alone for 4,096 primitives remains a short-lived scene cost
of 3.9 / 3.1 microseconds (the hierarchy is deferred until repeated queries).
Small scenes and single-query scenes retain minor absolute overhead for coverage,
identity/index bookkeeping and counters: the table includes those regressions.
Repeated-query and cell-lookup indexes trade additional bounded operation-local
memory for fewer candidate visits. Restoring missing-space checks and fair block
work is correctness work, not an optimization against the old skipped-work path.

Background-JIT runs are retained as `baseline-background-jit.csv` and
`current-background-jit.csv`. They exposed a large-scene single-query timing
outlier (72.2 versus 7.1 microseconds); synchronous compilation reduced that case
to 7.2 versus 6.6 without a production-code change. The final comparison therefore
uses identical synchronous-JIT settings and claims steady-state costs only.
Very short empty-provider samples still show tier/compilation outliers in both
versions (roughly 0.39 ms p95 for the 8-provider case); all raw samples' summaries
are retained. No startup-latency or statistically robust p99 claim is made.

Client execution and independent published-artifact consumer loading are NOT RUN
at CP1. No client rendering/input implementation changed; independent artifact
validation remains CP2. Arbitrary Java callbacks cannot be safely time-preempted;
finite collection and operation budgets do not bound callback internals. Explicit
unbounded discovery still costs loaded-world traversal. Persistent support and
unknown external providers intentionally retain conservative capture.

### Result

**FAIL at the reviewed commits - CP1-03 reopened.** The historical checks above
ran, but did not establish non-publication Block -> Entity motion: the falling
tick still used registry emptiness to reject the operation. CP1-08 also lacked
cross-provider aggregation diagnostics. See the corrective verification entry
below for current evidence. Client smoke and whole-game profiling remain NOT RUN;
historical microbenchmarks are not whole-game performance guarantees.

## CP2 - API, artifacts and maintenance checkpoint

Input: verified CP1 commit `288bb99`. Implementation commit:
`e635938a6a7227b7e43f91b369e3e011f4ede05d`. This evidence update follows that implementation.
Date: 2026-10-09. Scope: common Java 17 and NeoForge 1.21.1 / 21.1.249,
Java 21, optional Sable 2.0.5. Software/API migration baseline: **0.0.2**.
The pre-existing Sable version-property edits were preserved and included in the
CP2 commit, as requested by the subsequent instruction to commit and complete CP2.
Other targets received descriptor status/task metadata only; no port is claimed.
Nothing was pushed, tagged or remotely published.

### Implementation closure

| Item | Implementation and direct evidence |
| --- | --- |
| CP2-01 | Supported operation contracts now cover provider registration/session lifecycle, query coverage/contributions, publication/leases, sampling, observation, discovery and finite entity declarations. API_BOUNDARY and package Javadocs specify ownership, thread/side, validation, units, ordering and failures. Artifact reflection checks all 27 public API types and their public generic signatures. No internal public classes are promoted. |
| CP2-02 | Snapshot exposes assigned, effective and applied values plus derived ACTIVE_OPERATION/CURRENT_TICK/ASSIGNMENT_FALLBACK provenance. Outside an operation, current time/position/velocity and physical context must match. FIELD tick reuse remains prohibited without provider-context proof. Lifecycle controls prove frozen operation evidence survives publication changes, old-tick fallback, no session/provider/mutation effects, and preservation of assigned state independently of evaluation. Position-only defaults remain unchanged. |
| CP2-03 | `publicationProvider` delegates to the sole provider-scoped sampling path; the existing provider fixture uses it and separately contributes a bounded non-publication source. Exact EntityType declarations select existing finite integration modes, with explicit-over-built-in precedence, duplicate/late rejection, callback reentry/failure handling and no per-type mutable capability cache. Native dimensional/state exclusions remain. Controls cover dynamic restriction/recovery, inherited ordinary travel, custom travel requiring explicit declaration and duplicate/late registration. Existing geometry controls retain dynamic dimension/pose coverage. Loaded-only versus persistent source authority is documented without a recovery/readiness API. |
| CP2-04 | README now has the verified support matrix, actual artifact dependency and compiling fixture links. AGENTS is shortened; its detailed architecture contracts were moved verbatim to ARCHITECTURE and detailed verification rules to DEVELOPMENT (with corrected build facts). A direct old/new comparison confirmed no ownership-rule loss. Historical execution plan is explicitly superseded. Local link checker passes. Existing LICENSE/source notices remain; LICENSE is also embedded in the built JAR. |
| CP2-05 | CI triggers include complete target trees, wrappers, shared scripts and artifact verification code. Root and CI discovery use target ci.properties; status and aggregate task names come from those descriptors. Windows root aggregate passes; POSIX selects bash, with actual POSIX execution NOT RUN. Windows PowerShell 5 discovery works after moving PSScriptRoot default evaluation into the script body. Fixed verification run configurations remove task-name inspection. An indirect aggregate with controlBoundaryChecks=false still launches the full controls and passes with fresh results. `verifyApiArtifact` independently compiles API-only fixtures from the finished JAR plus platform dependencies, excludes engine/common output directories and Sable, loads every public API type from that JAR, inspects signatures, checks common embedding, metadata, resources and license. |
| CP2-06 | One documented 0.0.1 -> 0.0.2 migration: append assigned values/provenance to the snapshot constructor, reject negative magnitude values, explicitly declare audited custom travel integration. Existing accessors, units, sampling defaults, coverage, ordering and lease lifetime remain. No compatibility constructor guesses provenance. Provider convenience and declarations are additive. Save and wire formats remain internal and unchanged. Support is restricted to verified common/primary-target API; other integrations and client visual acceptance are not implied. |

### Executed verification

Environment: Windows 11, Gradle 9.7.1, Java 26 launcher, pinned Java 21.0.11
target/game toolchain and Java 17 common production. Commands ran from repository
root unless specified otherwise. Logs below are local ignored build outputs.

```powershell
.\targets\neoforge-1.21.1\gradlew.bat -p targets/neoforge-1.21.1 build dynamicsCoreVerification sableCompatibilityVerification --console plain --no-daemon
.\gradlew.bat build '-Ptarget=neoforge-1.21.1' --console plain --no-daemon
.\targets\neoforge-1.21.1\gradlew.bat -p targets/neoforge-1.21.1 :javadoc --console plain --no-daemon
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/discover-targets.ps1
python scripts/check-doc-links.py
git diff --check
```

- Full build + dynamics + strict Sable: **exit 0**, 78 seconds, 27 tasks;
  `build/cp2-verification.log`. Common **427 tests**, target **74 tests**, zero
  failures/errors/skips. JVM controls passed. API controls: 110 assertions;
  publication lifecycle: 136 assertions in both servers. Passive scheduling
  exercised 2,078 real server ticks, including bounded non-publication discovery.
- Fresh full server result: `run/control-verification/control-boundary-result.txt`
  under the primary target: `PASS 2264 sable=SKIPPED (not installed)`.
- Fresh strict server result: `run/sable-verification/control-boundary-result.txt`:
  `PASS contact compatibility 2265 sable=PASS 2.0.5`. Its
  `sable-checks-result.txt` contains all ten required areas: contacts, context,
  continuity, delayed-endpoints, gravity, landing-commit, modes, passive-entities,
  physics and transport. Sable absence in the ordinary server is intentional;
  it is not substituted for the strict installed-mod gate.
- Root aggregate: **exit 0**, 20 seconds; `build/cp2-root-build.log`. It discovered
  and executed `buildNeoForge1211` from metadata, including artifact and JVM checks.
  Quote dotted -P values in Windows PowerShell; the unquoted initial invocation
  was parsed as an invalid target and was corrected.
- Independent artifact verification: **PASS**, 27 public API types, final
  `GravityEngine-neoforge-1.21.1-0.0.2.jar`. Both API and common classes were loaded
  from the JAR; Sable and production output directories were absent. Consumer
  fixture compilation and the snapshot-copy operation passed. This does not claim
  an independently packaged game/client launch: transformed behavior was checked
  separately by the server controls.
- Indirect-run regression: **exit 0**, 44 seconds; `build/cp2-indirect.log`. A local
  init script in `build/cp2-indirect.gradle` registered `indirectConsumerVerification`
  depending on `dynamicsCoreVerification` and `verifyApiArtifact`. Executed with
  `-I D:/javaProjects/GravityEngine/build/cp2-indirect.gradle` and
  `-PcontrolBoundaryChecks=false`; controls still loaded and wrote a new PASS.
  This is a temporary verification fixture, not another permanent task layer.
- Supported target/common API Javadocs: **exit 0**, `build/cp2-javadoc.log`; output
  in `targets/neoforge-1.21.1/build/docs/javadoc`. Missing @param/@return warnings
  remain. The task intentionally documents supported API rather than Java-public
  internals. The initial all-implementation doc run found existing malformed HTML
  outside the supported API; it is not claimed as an internal-documentation pass.
- Descriptor discovery: **exit 0**, all four descriptors reported with PRIMARY or
  SCAFFOLD_UNVERIFIED status. Documentation links: **PASS**, all maintained entry
  points. Diff whitespace check passes after removing a trailing blank line.

The first transformed attempt correctly rejected a geometry fixture's custom
player travel override; that fixture records operands then delegates to Vanilla.
It now declares CHARACTER through the same supported consumer API. The corrected
full and indirect runs above pass; the conservative production rule was retained.

### Complexity, compatibility and limits

No freshness state, FIELD coverage generation, second composition path or second
application authority was added. Observation provenance is derived. Entity adapter
definitions have process lifetime and freeze at first integration; their current
per-entity result is not retained. The class-only custom-travel classification is
immutable derived evidence. The publication convenience function owns no registry
or source lifecycle. Duplicate command-line verification-mode state was removed.

CP2 did not change CP1's measured composition, spatial index, scene query or block
scheduler algorithms; those benchmark numbers remain historical CP1 evidence,
not new measurements. Adapter dispatch and the stricter optional observation getter
were not profiled; no zero-cost or whole-game performance claim is made.

**NOT RUN:** client smoke/render/input acceptance, POSIX execution, other-target
runtime parity, live GitHub Actions execution and whole-game profiling. Build/CI
logic was verified locally, with Windows execution and source review of the POSIX
branch. No new client presentation implementation, Mixin seam, save format or
network protocol was introduced. These limits do not expand supported scope.

### Result

**BLOCKED at the reviewed commits - final stability confirmation depends on the
CP1 runtime correction.** The recorded API/artifact and server gate executions
remain historical evidence, not proof that all consumers obey provider coverage.
The historical API baseline was 0.0.2 for common and NeoForge 1.21.1 / 21.1.249.
See the corrective verification entry below; unverified domains remain explicit.

## Dependency refresh and Sable review — 2026-10-09

This entry supersedes version pins in the historical CP2 evidence above, not its
ownership contracts. Minecraft target lines and the supported API remain unchanged.

### Dependency changes

Versions were checked against official Maven metadata and release APIs on the
review date. Prereleases and unrelated Minecraft lines were not selected.

| Dependency | Previous | Selected |
| --- | --- | --- |
| Sable, NeoForge 1.21.1 | 2.0.5 | 2.0.6 |
| NeoForge, 1.21.1 target | 21.1.249 | 21.1.256 |
| NeoForge, 26.1 target | 26.1.2.109 | 26.1.2.114 |
| Forge, 1.20.1 target | 47.4.23 | 47.4.26 |
| ModDevGradle / LegacyForge plugin | 2.0.141 / 2.0.142 | 2.0.148 |
| Fabric Loom | 1.10.5 | 1.18.3 |
| Mod Publish plugin | 2.1.1 | 2.2.1 |
| Gradle, root / NeoForge / Fabric | 9.7.1 / 9.7.1 / 8.14.5 | 9.8.1 |
| Gradle, Forge | 8.14.5 | 8.14.6, retained compatible maintenance line |
| JUnit BOM | 5.11.4 | 6.1.3 |
| JOML, test oracle only | 1.10.5 | 1.10.9 |
| ASM / ASM Tree, target tests | 9.7.1 | 9.10.1 |

Fabric Loader 0.19.5 and Fabric API 0.92.12+1.20.1 were already current for the
target. Sable companion remains 1.6.0, matching the embedded API and Sable's
explicit incompatibility with newer companion versions. Minecraft/loader-owned
transitive libraries were not independently forced to unrelated versions.

Metadata sources include [NeoForge](https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml),
[Forge](https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml),
[Loom](https://maven.fabricmc.net/net/fabricmc/fabric-loom/maven-metadata.xml),
[JUnit](https://repo.maven.apache.org/maven2/org/junit/junit-bom/maven-metadata.xml),
and [Gradle releases](https://services.gradle.org/versions/all).
Sable release/tag/artifact provenance is in the
[compatibility review](SABLE_COMPAT_CHECKLIST.md).

Loom now needs a Java 25 launcher; production/common bytecode stays Java 17.
Fabric's CI descriptor therefore selects Java 25. Root aggregation now resolves
each target's launcher from the same descriptor through Gradle toolchains,
allowing Forge/primary NeoForge to use Java 21 and Fabric/NeoForge 26.1 to use 25.
No production collision algorithm, supported API, physical state owner,
persistence format or network protocol changed.

### Verification

- **PASS:** common JUnit, 427 tests, zero failures/errors/skips; primary JUnit,
  74 tests, zero failures/errors/skips. Counts are from this run's XML, not CP2.
- **PASS:** primary build, JVM controls, consumer import checks and independent
  final-JAR API fixture loading without Sable (27 public API types).
- **PASS:** full transformed server without Sable, fresh result
  `PASS 2264 sable=SKIPPED (not installed)`.
- **PASS:** strict transformed server with Sable 2.0.6, fresh result
  `PASS contact compatibility 2265 sable=PASS 2.0.6` and all eleven manifest areas.
  The new required `distributed-gravity` area checks rotated two-mass tidal
  torque against angular momentum and late incomplete-query atomicity.
- **PASS:** individual Forge, Fabric and NeoForge 26.1 builds. These remain
  scaffold build evidence, not runtime parity.
- **PASS:** root `build '-PallTargets=true' --console plain --no-daemon`, launched
  from Java 21 and selecting each target's declared JDK. All four target builds
  and common completed in 52s; log: `build/dependency-all-targets.log`.
- **PASS:** maintained documentation-link check and `git diff --check`.

Final primary command (Java 21, target wrapper 9.8.1):

```powershell
.\targets\neoforge-1.21.1\gradlew.bat -p targets/neoforge-1.21.1 build dynamicsCoreVerification sableCompatibilityVerification --console plain --no-daemon
```

It completed successfully in 1m 19s; log: `build/dependency-verification.log`.
The Forge 8.14.6 confirmation log is `build/dependency-forge-final.log`;
other target logs are `build/dependency-fabric.log` and `build/dependency-neo26.log`.
The user explicitly accepted the EULA for both verification directories before
the acceptance files were created. Existing gate preparation removed old PASS
files before each launch.

The first primary launch failed with missing common `CellPos` metadata while
independent builds were recompiling the shared `common/build` directory.
Sequential reruns passed without changing production code or weakening tests.
Independent targets sharing this directory must not compile concurrently.
An initial direct Maven metadata request returned HTTP 403; normal Gradle
resolution succeeded. Its Sable runtime hash matches the official Modrinth
artifact inspected during review. Neither initial attempt is claimed as PASS.

**NOT RUN:** client smoke, packaged-client/multiplayer acceptance, POSIX execution,
Create/Aeronautics combinations, other-target gameplay parity, and performance
profiling. Sable logs unknown `create:flywheel` physics-property entries when
Create is absent; the installed-mod gate still completed, and this does not
certify Create compatibility. Remaining supported-scope limits and prioritized
acceptance gaps are in [SABLE_COMPAT_CHECKLIST.md](SABLE_COMPAT_CHECKLIST.md).


## Provider lifecycle review correction - 2026-10-09

The review of `288bb99`, `e635938` and `631a57c` against `186f70f` correctly
reopened CP1-03 and blocked final CP2 stability confirmation. The historical
result paragraphs above now reflect that judgment; old successful executions
are not retroactive proof of this missing behavior.

### Correction and ownership

- FallingBlock tick opening no longer treats a missing runtime or an empty
  publication registry as absence. The committed application selects evaluation
  authority. The server evaluates through the existing ballistic/provider path
  once, before deciding whether a collision scene is necessary. Native
  acceleration with no external collision requirement skips scene capture;
  other cases install the same evaluation in the operation. Nested operations
  retain their existing owner and clients retain server snapshot authority.
- Contributions/coverage remain query-local evidence; committed application,
  durable assignment and native attachment ownership are unchanged. Only a
  complete provider query establishes FIELD absence, including after reload;
  discovery bounds and publication membership cannot do so. Incomplete results
  continue through the existing assignment/application continuity policy.
- Runtime aggregation keeps an invocation-local contribution ID -> provider map.
  Duplicate identities identify both providers, the Level and query. Pure
  composition failures include Level/query/provider context and retain their
  original cause. No diagnostic registry, persistence or new service was added.

### Retained behavior controls and fresh evidence

No new small unit-test class or standalone test framework was added. Existing
`FallingLifecycleChecks` trajectory controls now retain a real Block -> Entity
conversion with native downward support, then invoke nine transformed Vanilla
entity ticks per scenario: equivalent publication-backed gravity, non-publication
nonzero gravity with the entire Level registry empty, and that non-publication
source while adding/removing a spatially unrelated publication. They compare
position, velocity, acceleration, direction and ballistic ownership, and verify
one evaluation per expected provider per free-flight tick. These are actual
entity tick invocations within the server control boundary, not nine separately
scheduled world ticks or a client prediction test.

Existing `PublicationLifecycleChecks` now also exercise conflicting provider IDs,
composition overflow diagnostics and successful evaluation after failure. The
existing valid six-face landing, native trajectory, failure/rollback and dynamic
support tests remain; none was deleted merely for being small or failing.

Command (Java 21.0.11, primary target wrapper; NeoForge 21.1.256):

```powershell
./targets/neoforge-1.21.1/gradlew.bat -p targets/neoforge-1.21.1 build dynamicsCoreVerification sableCompatibilityVerification --console plain --no-daemon
```

- PASS: common 427 and target 74 JUnit tests, no failures/errors/skips; target
  build, JVM controls and finished-JAR API consumer validation. Unit tests ran
  in the earlier build of this correction; unchanged test tasks were up-to-date
  in the final aggregate.
- PASS: fresh ordinary and strict Sable 2.0.6 transformed server gates. Both log
  `FALLING_LIFECYCLE_PASSED ... provider_trajectory` and
  `PUBLICATION_LIFECYCLE_CHECKS_PASSED assertions=142`. Both include the provider
  correction alongside existing landing, native fallback and ownership checks.
- Logs: `build/provider-fix-build.log`, `build/provider-fix-verification.log`,
  and final `build/provider-fix-final.log` (BUILD SUCCESSFUL, 1m 11s).
  The gates cleared their result files before launching; no earlier PASS file
  was reused. Assertion counts are not counts of independent game scenarios.
- NOT RUN for this correction: client smoke/prediction, POSIX, other target
  runtime parity, baseline microbenchmark rerun and whole-game profiling.
  Sable's missing `create:flywheel` tag warning remains because Create is absent;
  it did not prevent the strict installed-mod gate from passing.

**Corrective scope PASS:** P1 provider-driven falling motion and P2 aggregation
diagnostics are repaired and verified above. The identified CP1 runtime blocker
for CP2 is resolved in this working tree. This does not reclassify historical
commits as complete or extend acceptance to the explicitly unverified domains.
