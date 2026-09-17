# Development

## Targets and prerequisites

| Target | Toolchain | Status |
| --- | --- | --- |
| common | Java 17 | Tested kernel and supported neutral API |
| neoforge-1.21.1 | Java 21, NeoForge 21.1.256 | Primary integration; Sable 2.0.6 optional |
| forge-1.20.1 | Java 17 | Scaffold; integration parity unverified |
| fabric-1.20.1 | Java 25 launcher, Java 17 production | Loom 1.18.3; scaffold, integration parity unverified |
| neoforge-26.1 | Java 25 | Scaffold; integration parity unverified |

Use a compatible Gradle launcher JDK and provision the target toolchain. Wrappers
and pinned loader versions remain target-owned. Directory presence or a successful
scaffold build is not gameplay acceptance. Current Loom requires a Java 25
launcher even for Minecraft 1.20.1. Root aggregation selects each target's
launcher from `ci.java` using Gradle toolchains; provision both Java 21 and 25.
Forge retains the compatible Gradle 8.14.6 line; the other wrappers use 9.8.1.
Run independent target builds sequentially: they share `common/build`, so parallel
compilation can remove common classes while a transformed server is loading them.

## Build and consumer verification

From the repository root (PowerShell):

```powershell
.\gradlew.bat -p common test --console plain --no-daemon
.\targets\neoforge-1.21.1\gradlew.bat -p targets/neoforge-1.21.1 build --console plain --no-daemon
.\gradlew.bat build '-Ptarget=neoforge-1.21.1' --console plain --no-daemon
```

On POSIX replace the executable with `bash ./gradlew` or
`bash ./targets/neoforge-1.21.1/gradlew`. Root aggregation discovers target
`ci.properties`; `-PallTargets=true` selects all descriptors and explicitly fails
for a disabled selected target. Target status is printed before execution.

Use `:javadoc` in the primary target to generate only the supported target/common
API documentation. Internal public classes are not exported as consumer Javadocs.

`build` includes JUnit, `controlCheck`, `verifyApiConsumerImports` and
`verifyApiArtifact`. The latter compiles the real API-only fixtures against the
finished engine JAR plus platform dependencies, excluding engine/common output
directories and Sable. It loads the artifact, inspects public generic signatures
(including transitively referenced API types), checks metadata/resources/common
embedding and runs the fixture's value-copy check. Its sources are in
[verification/artifact-consumer](../verification/artifact-consumer/ArtifactCheck.java).
This is independent artifact compilation/loading, not a claim of packaged-client
acceptance. The transformed server suites separately exercise actual lifecycle.

For an external NeoForge 21.1.256 workspace, build the JAR, copy it into `libs/`,
and add `implementation files('libs/GravityEngine-neoforge-1.21.1-0.0.2.jar')`.
Do not also add common: its bytecode is embedded. The complete compiling examples
are [ExternalConsumerFixture](../targets/neoforge-1.21.1/src/controlTest/java/com/example/examplemod/gravity/ExternalConsumerFixture.java)
and [ProviderFixture](../targets/neoforge-1.21.1/src/controlTest/java/com/example/examplemod/gravity/ProviderFixture.java).
No remote Maven artifact availability is implied.

## Server gates and CI

`dynamicsCoreVerification` uses the fixed `runControlVerification` configuration,
which always loads controls. It works as an indirect task dependency and does not
inspect command-line task names. `contactCompatibilityVerification` reuses this
full gate. Ordinary `runServer` is independent; `-PcontrolBoundaryChecks=true`
enables controls for manual development. Strict `sableCompatibilityVerification`
uses a separate pinned runtime and directory. Preparation removes old result files
before each server launch; absence or failure of a fresh result fails the gate.
Server runs require the user's Minecraft EULA acceptance in their run directory;
verification does not write acceptance on the user's behalf.

[The workflow](../.github/workflows/verify-common.yml) runs common and every enabled
target for changes to common, targets (including resources/Mixins), shared scripts,
wrappers and verification code. This is a conservative superset of affected targets.
Both CI and root aggregation use each target's `ci.properties`, with no separate
handwritten target list. Disabled descriptors print an explicit unverified status;
a disabled target is not evidence of successful validation. Heavy server gates are
manual/pre-release requirements; build-only CI does not certify game behavior.

## Performance and publishing

Run `scripts/measure-hot-paths.ps1 -JavaHome 'C:\Program Files\Java\jdk-21.0.11'`
for the retained CP1 baseline comparison. Raw results and environment go under
`build/hot-path-measurements`. The baseline export uses Git and includes index
construction cost; it is not a whole-game profiler. Do not replace measured results
with estimates or compare different warmup/JIT configurations as equivalent.

Publishing configuration lives in
[gradle/target-conventions/publish.gradle](../gradle/target-conventions/publish.gradle).
It requires an explicit `-PpublishType=snapshot` or `release` for remote publication,
and separate credentials. Build/artifact verification does not publish, push or tag.
Metadata is expanded from shared identity and pinned target properties; optional
Sable remains compile-only for ordinary runs. Release operators must review the
fresh verification evidence and license/attribution before publishing.

## Verification and delivery

Use the affected target's real wrapper/JDK. The current root includes common and orchestrates independent target builds; its target `Exec` commands choose Windows `cmd` or POSIX `bash`. Inspect the actual wrapper and script paths before invoking them. Do not treat an aggregate build as cross-version runtime proof.

From repository root on Windows:

```powershell
# Common-only verification using an available wrapper.
.\targets\neoforge-1.21.1\gradlew.bat -p common test --console plain --no-daemon

# Primary target build and full dynamics gate.
.\targets\neoforge-1.21.1\gradlew.bat -p targets/neoforge-1.21.1 build dynamicsCoreVerification --console plain --no-daemon

# Required additional gate for Sable changes.
.\targets\neoforge-1.21.1\gradlew.bat -p targets/neoforge-1.21.1 sableCompatibilityVerification --console plain --no-daemon
```

| Change | Evidence to obtain |
| --- | --- |
| Common mathematics, state or support lifecycle | Focused behavioral regression and common tests; preserve Java 17/platform isolation. |
| Angular dynamics / flight attitude | Conservation and torque-response tests, substep consistency, ownership/single-integration checks, mode-handoff tests and affected target integration tests. Zero-damping tests must distinguish conserved `L_world` from derived `omega_world`. |
| Public API | Signature/consumer fixture checks, validation and lifecycle tests, synchronized contract documentation. |
| Target movement, geometry, callbacks or Mixins | Target build/tests, relevant transformed control checks and `dynamicsCoreVerification`; client smoke where the behavior is client-owned. |
| Sable integration | Above plus strict `sableCompatibilityVerification`, installed/absent behavior and affected real scenarios. |
| Networking/correction/presentation | Native spawn/tracking/login/respawn/dimension ordering; stale-revision rejection; missing/mismatched-target drop behavior; fresh lifecycle resynchronization; applicable client/server checks. Synthetic incarnation tests are required only if such a protocol is independently justified. |
| FIELD providers / query coverage | Registration before Level creation; duplicate/late registration rejection; all expected sessions consulted; missing-session failure; per-Level disposal; complete-empty/incomplete-empty/partial-positive results; global deterministic composition; zero resultant presence; query-domain discovery and cache invalidation when coverage changes without publication changes. |
| Persistence / load reconciliation | Native attachment creation/save/load and player-copy behavior; current-format roundtrip and unsupported-version rejection; publication-before-load and load-before-complete-query; partial-positive samples; complete PRESENT/ABSENT; runtime coverage loss/recovery; save while UNKNOWN; unchanged-seed durable/sync revision separation; safe continuity installation and rejection; Level unload/respawn replacement; self/observer synchronization; malformed/unsupported diagnostics. |
| Documentation only | Verify referenced declarations, paths, commands and diff; a game run is not required for prose alone. |

`contactCompatibilityVerification` now shares the full control server gate. Running it together with dynamics verification launches that server only once. Strict Sable verification must load the pinned runtime and produce fresh required coverage; missing dependencies, skipped suites and stale PASS files are not acceptance.

For FIELD persistence/load work, acceptance must include publication-before-load and load-before-publication orderings **and** a partial-composition case with at least two possible contributors/providers, including one reporting `INCOMPLETE` while another supplies a contribution. Neither incomplete-empty nor incomplete-positive results may replace the durable seed, advance its durable revision or poison a save. All-complete non-empty composition commits `PRESENT`; all-complete empty composition commits `ABSENT`. Include zero/cancelling contributions and a later OVERRIDE so numerical equality or an already non-empty sample cannot hide the bug. Provider-owned query coverage is the completeness oracle; a Level/chunk event, registry revision or fixed tick delay is not.

Also cover `PRESENT -> UNKNOWN` and `ABSENT -> UNKNOWN` on runtime coverage loss, repeated incomplete queries, recovery to each complete outcome, chunk unload/reload and source-index rebuild under the declared source-lifetime policy. A restored unchanged FIELD tuple at durable revision 10 must stay at 10 after `UNKNOWN -> PRESENT`, while its sync revision advances; changing durable provenance alone must count as a durable tuple change. Verify UNKNOWN continuity with both accepted and rejected geometry installation, and that clients accept evidence-only updates without running provider reconciliation. Preserve DIRECT authority throughout FIELD coverage changes.

Use tests that exercise behavior and ownership boundaries. Keep architecture/import checks for real dependency contracts, not arbitrary class placement.

Do not weaken thresholds, remove assertions, exclude failing cases or delete tests merely to obtain PASS.

When a deliberate implementation/contract correction makes a test explicitly assert superseded behavior, that test is no longer valid verification. Delete the conflicting test directly. Add focused replacement coverage unless the task explicitly prohibits new tests; in that case run the applicable existing checks and report the coverage gap.

If an entire test file exists only to enforce the superseded contract, delete the file. If a file mixes obsolete and still-valid behavior, delete only the obsolete test cases and retain the valid ones.

Do not preserve an invalid contract through compatibility branches merely because an old test expects it. Do not leave obsolete tests ignored, disabled or excluded from the build.

For movement-ownership changes, test combinations of locomotion mode, installed representation, collision route and operation lifetime rather than testing each flag in isolation. At minimum cover the legal `NATIVE_FALLBACK + EXACT_BODY + engine collision` state and the `NATIVE_FALLBACK + NATIVE_AABB + pure Vanilla collision` state.

Test compilation, untransformed JVM tests, transformed server checks and client execution prove different things.

Unless the task explicitly requests a different delivery format, report what changed, why it preserves or deliberately revises a contract, the checks actually run and remaining blockers. Distinguish PASS, FAIL, BLOCKED and NOT RUN.

A task may explicitly suppress the narrative report without suppressing required implementation or validation work. If the task explicitly requests no report, perform the same implementation and verification work but obey its requested completion response.

An offline harness or different test dependency version is auxiliary evidence, and a partial source archive is not proof of a release artifact. Never carry a previous snapshot's test counts forward as current results.
