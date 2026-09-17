package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.api.*;
import cc.sighs.gravityengine.api.field.*;
import cc.sighs.gravityengine.api.math.Vec3d;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Contract tests for the supported external API boundary.
 *
 * <p>Everything here is written the way an external content mod would write
 * it. The only implementation-detail reference is the fully qualified
 * {@code GravityFieldRuntime} used to simulate an unloaded level and to prove
 * that closing a stale publication does not recreate a runtime.</p>
 */
final class ApiBoundaryChecks {
    private static final String NAMESPACE = "gravityengine_api_checks";
    private static final double EPSILON = 1.0E-12D;

    /** Far above the Overworld base field's zero-gravity height: contributes (0,0,0). */
    private static final Vec3d PROBE =
            new Vec3d(8.0D, 1000.0D, 8.0D);

    private static int assertions;

    private ApiBoundaryChecks() {}

    static void run(ServerLevel level) {
        revisionOwnership(level);
        staleRevisions(level);
        runJvm();
        blockBackedCoexistence(level);
        unregisterAfterLevelRemoval(level);
        additiveComposition(level);
        overrideExcludesAdditive(level);
        multipleOverridesVectorSum(level);
        overrideZeroGravityKeepsPresence(level);
        deterministicOrder(level);
        identicalOrderTieBreak(level);
        immutableResults(level);
        builtInFactories(level);
        entitySnapshot(level);
        System.out.println(
                "API_BOUNDARY_CHECKS_PASSED assertions=" + assertions
        );
    }

    /** Value/identity checks that require no world, loader bootstrap or Mixins. */
    static void runJvm() {
        blockInstanceIdentity();
        System.out.println("API_VALUE_CONTROL_CHECKS_PASSED");
    }

    // -----------------------------------------------------------------
    // Publication / revision ownership
    // -----------------------------------------------------------------

    private static void revisionOwnership(ServerLevel level) {
        ResourceLocation id = id("revision");
        FieldPublication revisionOne = GravityEngineApi.publish(
                level, com.example.examplemod.gravity.ProviderFixture.ID,
                definition(
                        id,
                        constant(0.1D, 0.0D, 0.0D),
                        GravityFieldCompositionMode.OVERRIDE,
                        1L
                )
        );
        check(revisionOne.accepted(), "revision 1 publishes successfully");
        check(
                revisionOne.status() == FieldPublication.Status.ACCEPTED,
                "accepted publication reports ACCEPTED"
        );
        check(
                revisionOf(level, id) == 1L,
                "revision 1 is the registered instance"
        );

        FieldPublication revisionTwo = GravityEngineApi.publish(
                level, com.example.examplemod.gravity.ProviderFixture.ID,
                definition(
                        id,
                        constant(0.2D, 0.0D, 0.0D),
                        GravityFieldCompositionMode.OVERRIDE,
                        2L
                )
        );
        check(revisionTwo.accepted(), "revision 2 replaces revision 1");
        check(
                revisionOf(level, id) == 2L,
                "revision 2 is the registered instance"
        );

        revisionOne.close();

        check(
                revisionOf(level, id) == 2L,
                "closing the revision-1 handle cannot remove revision 2"
        );
        check(
                revisionOne.isClosed(),
                "closed revision-1 handle reports closed"
        );

        revisionTwo.close();
        revisionTwo.close();

        check(
                revisionOf(level, id) < 0L,
                "closing the current handle removes its field"
        );
        check(
                GravityEngineApi.sample(level, PROBE)
                        .contributions()
                        .stream()
                        .noneMatch(c -> c.fieldId().equals(id)),
                "removed field is absent from composed sampling"
        );
    }

    private static void staleRevisions(ServerLevel level) {
        ResourceLocation id = id("stale");

        FieldPublication current = GravityEngineApi.publish(
                level, com.example.examplemod.gravity.ProviderFixture.ID,
                definition(
                        id,
                        constant(0.3D, 0.0D, 0.0D),
                        GravityFieldCompositionMode.OVERRIDE,
                        5L
                )
        );
        check(current.accepted(), "baseline revision publishes");

        FieldPublication older = GravityEngineApi.publish(
                level, com.example.examplemod.gravity.ProviderFixture.ID,
                definition(
                        id,
                        constant(9.9D, 0.0D, 0.0D),
                        GravityFieldCompositionMode.OVERRIDE,
                        4L
                )
        );
        check(
                older.status() == FieldPublication.Status.REJECTED_STALE,
                "older revision is rejected as stale"
        );
        check(!older.accepted(), "rejected publication reports not accepted");
        check(
                older.revision() == 4L,
                "rejected publication still reports its submitted revision"
        );

        FieldPublication duplicate = GravityEngineApi.publish(
                level, com.example.examplemod.gravity.ProviderFixture.ID,
                definition(
                        id,
                        constant(9.9D, 0.0D, 0.0D),
                        GravityFieldCompositionMode.OVERRIDE,
                        5L
                )
        );
        check(
                duplicate.status() == FieldPublication.Status.REJECTED_STALE,
                "equal revision is rejected as stale"
        );

        check(
                revisionOf(level, id) == 5L,
                "rejected submissions never weaken the accepted revision"
        );

        older.close();
        duplicate.close();

        check(
                revisionOf(level, id) == 5L,
                "closing rejected handles changes nothing"
        );

        current.close();
        check(revisionOf(level, id) < 0L, "stale-check field cleaned up");
    }

    private static void unregisterAfterLevelRemoval(ServerLevel level) {
        ServerLevel isolated = level.getServer().getLevel(Level.NETHER);
        check(
                isolated != null,
                "dedicated server exposes a second dimension for the unload check"
        );

        boolean existed = cc.sighs.gravityengine.gravity.field.GravityFieldRuntime.getIfPresent(isolated) != null;
        int before = cc.sighs.gravityengine.gravity.field.GravityFieldRuntime
                .loadedLevelCount();

        FieldPublication publication = GravityEngineApi.publish(
                isolated, com.example.examplemod.gravity.ProviderFixture.ID,
                definition(
                        id("unload"),
                        GravityFields.zeroGravity(),
                        GravityFieldCompositionMode.OVERRIDE,
                        1L
                )
        );
        check(publication.accepted(), "field publishes into the isolated level");
        check(com.example.examplemod.gravity.ProviderFixture.hasSessions(isolated),
                "all expected providers have a per-Level session");
        check(
                cc.sighs.gravityengine.gravity.field.GravityFieldRuntime
                        .loadedLevelCount() == before + (existed ? 0 : 1),
                "publication owns exactly one level runtime"
        );

        // Simulate the real LevelEvent.Unload cleanup.
        cc.sighs.gravityengine.gravity.field.GravityFieldRuntime.remove(isolated);
        check(!com.example.examplemod.gravity.ProviderFixture.hasSessions(isolated),
                "unload disposes every provider session");
        check(com.example.examplemod.gravity.ProviderFixture.hasSessions(level),
                "unload preserves other Level sessions");
        int afterUnload = cc.sighs.gravityengine.gravity.field.GravityFieldRuntime
                .loadedLevelCount();
        check(
                afterUnload == before - (existed ? 1 : 0),
                "level removal destroys the level runtime"
        );

        publication.close();

        check(
                cc.sighs.gravityengine.gravity.field.GravityFieldRuntime
                        .loadedLevelCount() == afterUnload,
                "closing after level removal is harmless and recreates no runtime"
        );
        check(publication.isClosed(), "unloaded-level handle reports closed");
        com.example.examplemod.gravity.ProviderFixture.missingSession(isolated);
        boolean missingRejected = false;
        try { GravityEngineApi.sample(isolated, PROBE); }
        catch (NullPointerException expected) { missingRejected = true; }
        finally { com.example.examplemod.gravity.ProviderFixture.missingSession(null); }
        check(missingRejected, "missing expected session fails rather than proving absence");
        check(com.example.examplemod.gravity.ProviderFixture.sessionCount(isolated) == 0,
                "failed factory closes already-created sessions");
        check(cc.sighs.gravityengine.gravity.field.GravityFieldRuntime.getIfPresent(isolated) == null,
                "failed runtime construction leaves no usable partial provider set");
        check(GravityEngineApi.sample(isolated, PROBE).coverage()
                        == cc.sighs.gravityengine.api.field.FieldCoverage.COMPLETE,
                "fresh complete sessions recover after construction failure");
        cc.sighs.gravityengine.gravity.field.GravityFieldRuntime.remove(isolated);
    }

    // -----------------------------------------------------------------
    // Block-backed publication identity
    // -----------------------------------------------------------------

    private static void blockInstanceIdentity() {
        ResourceLocation type = id("identity");
        BlockPos position = new BlockPos(0, 64, 0);

        ResourceLocation first = GravityFieldIds.blockInstance(type, position);
        ResourceLocation again = GravityFieldIds.blockInstance(
                type,
                new BlockPos(0, 64, 0)
        );
        ResourceLocation other = GravityFieldIds.blockInstance(
                type,
                new BlockPos(100, 64, 0)
        );

        check(
                first.equals(again),
                "same base id and position derive the same instance id"
        );
        check(
                !first.equals(other),
                "different block positions derive different instance ids"
        );
        check(
                !first.equals(type),
                "derived instance id differs from the base field type id"
        );
        check(
                first.getNamespace().equals(type.getNamespace()),
                "derived instance id keeps the field type namespace"
        );
        check(
                GravityFieldIds.blockInstance(
                        type,
                        new BlockPos(-5, 64, -7)
                ).getPath().contains("-5_64_-7"),
                "derived instance id is deterministic for negative coordinates"
        );

        GravityFieldDefinition definition = GravityFieldDefinition.block(
                type,
                position,
                constant(0.001D, 0.0D, 0.0D),
                GravityFields.infiniteInfluence(),
                GravityFieldCompositionMode.ADDITIVE,
                1L
        );
        check(
                definition.id().equals(first),
                "block definition factory uses the derived instance id"
        );
        check(
                definition.source()
                        .equals(GravityFieldSource.block(type, position)),
                "block definition factory keeps the source order identity"
        );
    }

    private static void blockBackedCoexistence(ServerLevel level) {
        ResourceLocation fieldType = id("coexist");
        BlockPos positionA = new BlockPos(0, 64, 0);
        BlockPos positionB = new BlockPos(100, 64, 0);
        Vec3d probeA = new Vec3d(0.5D, 64.5D, 0.5D);
        Vec3d probeB = new Vec3d(100.5D, 64.5D, 0.5D);

        ResourceLocation idA = GravityFieldIds.blockInstance(fieldType, positionA);
        ResourceLocation idB = GravityFieldIds.blockInstance(fieldType, positionB);
        check(!idA.equals(idB), "coexisting blocks derive distinct ids");

        FieldPublication publicationA = GravityEngineApi.publish(
                level, com.example.examplemod.gravity.ProviderFixture.ID,
                blockDefinition(
                        fieldType,
                        positionA,
                        constant(0.01D, 0.0D, 0.0D),
                        1L
                )
        );
        FieldPublication publicationB = GravityEngineApi.publish(
                level, com.example.examplemod.gravity.ProviderFixture.ID,
                blockDefinition(
                        fieldType,
                        positionB,
                        constant(0.02D, 0.0D, 0.0D),
                        1L
                )
        );

        check(
                publicationA.accepted() && publicationB.accepted(),
                "two block-backed fields with one field type coexist"
        );
        check(
                publicationA.fieldId().equals(idA)
                        && publicationB.fieldId().equals(idB),
                "leases report their own derived instance ids"
        );

        ComposedGravitySample sampleA = GravityEngineApi.sample(level, probeA);
        ComposedGravitySample sampleB = GravityEngineApi.sample(level, probeB);

        check(
                sampleA.contributions().size() == 1
                        && sampleA.contributions().get(0).fieldId().equals(idA),
                "block A influence is observed at A: " + sampleA.contributions()
        );
        near(
                new Vec3d(0.01D, 0.0D, 0.0D),
                sampleA.acceleration(),
                "block A composes its own field"
        );
        check(
                sampleB.contributions().size() == 1
                        && sampleB.contributions().get(0).fieldId().equals(idB),
                "block B influence is observed at B: " + sampleB.contributions()
        );
        near(
                new Vec3d(0.02D, 0.0D, 0.0D),
                sampleB.acceleration(),
                "block B composes its own field"
        );

        // Independent revision sequences: A moves on, B must not be affected.
        FieldPublication revisionTwoA = GravityEngineApi.publish(
                level, com.example.examplemod.gravity.ProviderFixture.ID,
                blockDefinition(
                        fieldType,
                        positionA,
                        constant(0.03D, 0.0D, 0.0D),
                        2L
                )
        );
        check(
                revisionTwoA.accepted(),
                "revision 2 of block A is accepted"
        );
        check(
                revisionOf(level, probeA, idA) == 2L,
                "block A owns an independent revision sequence"
        );
        check(
                revisionOf(level, probeB, idB) == 1L,
                "block B stays at revision 1 while A is at revision 2"
        );
        near(
                new Vec3d(0.02D, 0.0D, 0.0D),
                GravityEngineApi.sample(level, probeB).acceleration(),
                "block B field is unaffected by block A's revision bump"
        );

        // Stale A lease cannot remove the newer A instance, nor B.
        publicationA.close();
        check(
                revisionOf(level, probeA, idA) == 2L,
                "closing block A revision 1 cannot remove revision 2"
        );

        revisionTwoA.close();
        check(
                revisionOf(level, probeA, idA) < 0L,
                "closing block A revision 2 removes block A"
        );
        check(
                revisionOf(level, probeB, idB) == 1L,
                "removing block A leaves block B registered"
        );

        publicationB.close();
        check(
                revisionOf(level, probeB, idB) < 0L,
                "closing block B removes block B"
        );
    }

    // -----------------------------------------------------------------
    // Composition semantics
    // -----------------------------------------------------------------

    private static void additiveComposition(ServerLevel level) {
        ResourceLocation first = id("additive_a");
        ResourceLocation second = id("additive_b");

        try (FieldPublication a = GravityEngineApi.publish(
                level, com.example.examplemod.gravity.ProviderFixture.ID,
                definition(
                        first,
                        constant(0.01D, 0.0D, 0.0D),
                        GravityFieldCompositionMode.ADDITIVE,
                        1L
                )
        ); FieldPublication b = GravityEngineApi.publish(
                level, com.example.examplemod.gravity.ProviderFixture.ID,
                definition(
                        second,
                        constant(0.0D, 0.02D, 0.0D),
                        GravityFieldCompositionMode.ADDITIVE,
                        1L
                )
        )) {
            check(a.accepted() && b.accepted(), "additive fields publish");

            ComposedGravitySample sample = GravityEngineApi.sample(
                    level,
                    PROBE,
                    Vec3d.ZERO,
                    100L,
                    1.0D
            );

            check(
                    sample.fieldPresent(),
                    "additive composition reports field presence"
            );
            check(
                    sample.contributions()
                            .stream()
                            .anyMatch(c -> c.fieldId().equals(first))
                            && sample.contributions()
                            .stream()
                            .anyMatch(c -> c.fieldId().equals(second)),
                    "both additive fields contribute"
            );
            near(
                    new Vec3d(0.01D, 0.02D, 0.0D),
                    sample.acceleration(),
                    "additive fields vector-sum"
            );
        }

        check(
                revisionOf(level, first) < 0L
                        && revisionOf(level, second) < 0L,
                "additive fields removed by lease close"
        );
    }

    private static void overrideExcludesAdditive(ServerLevel level) {
        ResourceLocation additive = id("excluded_additive");
        ResourceLocation override = id("authoritative_override");

        try (FieldPublication a = GravityEngineApi.publish(
                level, com.example.examplemod.gravity.ProviderFixture.ID,
                definition(
                        additive,
                        constant(0.5D, 0.0D, 0.0D),
                        GravityFieldCompositionMode.ADDITIVE,
                        1L
                )
        ); FieldPublication o = GravityEngineApi.publish(
                level, com.example.examplemod.gravity.ProviderFixture.ID,
                definition(
                        override,
                        constant(0.05D, 0.0D, 0.0D),
                        GravityFieldCompositionMode.OVERRIDE,
                        1L
                )
        )) {
            check(
                    a.accepted() && o.accepted(),
                    "override/composition fixtures publish"
            );

            ComposedGravitySample sample = GravityEngineApi.sample(
                    level,
                    PROBE
            );

            check(
                    sample.contributions().size() == 1
                            && sample.contributions()
                            .get(0)
                            .fieldId()
                            .equals(override),
                    "active OVERRIDE excludes ADDITIVE fields"
            );
            near(
                    new Vec3d(0.05D, 0.0D, 0.0D),
                    sample.acceleration(),
                    "override group is authoritative"
            );
        }
    }

    private static void multipleOverridesVectorSum(ServerLevel level) {
        ResourceLocation first = id("override_a");
        ResourceLocation second = id("override_b");

        try (FieldPublication a = GravityEngineApi.publish(
                level, com.example.examplemod.gravity.ProviderFixture.ID,
                definition(
                        first,
                        constant(0.01D, 0.0D, 0.0D),
                        GravityFieldCompositionMode.OVERRIDE,
                        1L
                )
        ); FieldPublication b = GravityEngineApi.publish(
                level, com.example.examplemod.gravity.ProviderFixture.ID,
                definition(
                        second,
                        constant(0.0D, 0.02D, 0.0D),
                        GravityFieldCompositionMode.OVERRIDE,
                        1L
                )
        )) {
            check(a.accepted() && b.accepted(), "override fixtures publish");

            ComposedGravitySample sample = GravityEngineApi.sample(
                    level,
                    PROBE
            );

            check(
                    sample.contributions().size() == 2,
                    "both OVERRIDE fields contribute"
            );
            near(
                    new Vec3d(0.01D, 0.02D, 0.0D),
                    sample.acceleration(),
                    "multiple OVERRIDE fields vector-sum"
            );
        }
    }

    private static void overrideZeroGravityKeepsPresence(ServerLevel level) {
        ResourceLocation additive = id("zero_suppressed_additive");
        ResourceLocation zero = id("zero_override");

        try (FieldPublication a = GravityEngineApi.publish(
                level, com.example.examplemod.gravity.ProviderFixture.ID,
                definition(
                        additive,
                        constant(0.08D, 0.0D, 0.0D),
                        GravityFieldCompositionMode.ADDITIVE,
                        1L
                )
        ); FieldPublication z = GravityEngineApi.publish(
                level, com.example.examplemod.gravity.ProviderFixture.ID,
                definition(
                        zero,
                        GravityFields.zeroGravity(),
                        GravityFieldCompositionMode.OVERRIDE,
                        1L
                )
        )) {
            check(
                    a.accepted() && z.accepted(),
                    "zero-gravity override publishes"
            );

            ComposedGravitySample sample = GravityEngineApi.sample(
                    level,
                    PROBE
            );

            check(
                    sample.fieldPresent(),
                    "zero-gravity OVERRIDE still reports fieldPresent"
            );
            check(
                    sample.contributions().size() == 1
                            && sample.contributions()
                            .get(0)
                            .acceleration()
                            .lengthSquared() == 0.0D,
                    "zero-gravity OVERRIDE is a zero-vector contribution"
            );
            check(
                    sample.acceleration().lengthSquared() == 0.0D,
                    "zero-result is not no-field"
            );
            check(
                    !sample.hasAcceleration() && sample.down().isEmpty(),
                    "zero resultant exposes no direction"
            );
        }
    }

    private static void deterministicOrder(ServerLevel level) {
        // Insertion order deliberately differs from structural order.
        ResourceLocation highZ = id("order_high_z");
        ResourceLocation named = id("order_named");
        ResourceLocation lowZ = id("order_low_z");
        ResourceLocation blockType = id("order_block");

        try (FieldPublication one = GravityEngineApi.publish(
                level, com.example.examplemod.gravity.ProviderFixture.ID,
                new GravityFieldDefinition(
                        highZ,
                        GravityFieldSource.block(
                                blockType,
                                new BlockPos(0, 0, 5)
                        ),
                        constant(0.01D, 0.0D, 0.0D),
                        GravityFields.infiniteInfluence(),
                        GravityFieldCompositionMode.OVERRIDE,
                        1L
                )
        ); FieldPublication two = GravityEngineApi.publish(
                level, com.example.examplemod.gravity.ProviderFixture.ID,
                new GravityFieldDefinition(
                        named,
                        GravityFieldSource.named(named),
                        constant(0.02D, 0.0D, 0.0D),
                        GravityFields.infiniteInfluence(),
                        GravityFieldCompositionMode.OVERRIDE,
                        1L
                )
        ); FieldPublication three = GravityEngineApi.publish(
                level, com.example.examplemod.gravity.ProviderFixture.ID,
                new GravityFieldDefinition(
                        lowZ,
                        GravityFieldSource.block(
                                blockType,
                                new BlockPos(0, 0, -5)
                        ),
                        constant(0.03D, 0.0D, 0.0D),
                        GravityFields.infiniteInfluence(),
                        GravityFieldCompositionMode.OVERRIDE,
                        1L
                )
        )) {
            check(
                    one.accepted() && two.accepted() && three.accepted(),
                    "ordering fixtures publish"
            );

            /*
             * Structural order sorts by source type string first:
             *
             *   "...:order_block" < "...:order_named"
             *
             * and then by block Z within the block source type. The named
             * field is published second and the low-Z block field third, so
             * registration order deliberately disagrees with the result.
             */
            List<ResourceLocation> expected = List.of(
                    lowZ,
                    highZ,
                    named
            );

            for (int attempt = 0; attempt < 3; attempt++) {
                check(
                        identifiers(level).equals(expected),
                        "accumulation order is structural, not insertion order: "
                                + identifiers(level)
                );
            }

            // A revision change must not move a field in the order.
            FieldPublication revised = GravityEngineApi.publish(
                    level, com.example.examplemod.gravity.ProviderFixture.ID,
                    new GravityFieldDefinition(
                            highZ,
                            GravityFieldSource.block(
                                    blockType,
                                    new BlockPos(0, 0, 5)
                            ),
                            constant(0.04D, 0.0D, 0.0D),
                            GravityFields.infiniteInfluence(),
                            GravityFieldCompositionMode.OVERRIDE,
                            2L
                    )
            );
            check(revised.accepted(), "ordering fixture revision accepted");
            check(
                    identifiers(level).equals(expected),
                    "revision never changes accumulation order"
            );
            revised.close();
        }
    }

    /**
     * Two fields with identical source-order coordinates must still order
     * deterministically: the final tie-break is the stable field identity,
     * never registration order, revision, hashing or object identity.
     */
    private static void identicalOrderTieBreak(ServerLevel level) {
        ResourceLocation sourceType = id("tie_source");
        ResourceLocation first = id("tie_a");
        ResourceLocation second = id("tie_b");
        BlockPos sharedPosition = new BlockPos(0, 0, 0);

        // Published deliberately in reverse of the expected order.
        try (FieldPublication b = GravityEngineApi.publish(
                level, com.example.examplemod.gravity.ProviderFixture.ID,
                new GravityFieldDefinition(
                        second,
                        GravityFieldSource.block(sourceType, sharedPosition),
                        constant(0.02D, 0.0D, 0.0D),
                        GravityFields.infiniteInfluence(),
                        GravityFieldCompositionMode.OVERRIDE,
                        1L
                )
        ); FieldPublication a = GravityEngineApi.publish(
                level, com.example.examplemod.gravity.ProviderFixture.ID,
                new GravityFieldDefinition(
                        first,
                        GravityFieldSource.block(sourceType, sharedPosition),
                        constant(0.01D, 0.0D, 0.0D),
                        GravityFields.infiniteInfluence(),
                        GravityFieldCompositionMode.OVERRIDE,
                        1L
                )
        )) {
            check(
                    a.accepted() && b.accepted(),
                    "tie-break fixtures publish"
            );
            check(
                    identifiers(level).equals(List.of(first, second)),
                    "identical source order falls back to stable field "
                            + "identity: " + identifiers(level)
            );

            FieldPublication revisedSecond = GravityEngineApi.publish(
                    level, com.example.examplemod.gravity.ProviderFixture.ID,
                    new GravityFieldDefinition(
                            second,
                            GravityFieldSource.block(sourceType, sharedPosition),
                            constant(0.03D, 0.0D, 0.0D),
                            GravityFields.infiniteInfluence(),
                            GravityFieldCompositionMode.OVERRIDE,
                            7L
                    )
            );
            check(
                    revisedSecond.accepted(),
                    "tie-break fixture revision accepted"
            );
            check(
                    identifiers(level).equals(List.of(first, second)),
                    "revision is never an ordering tie-break: "
                            + identifiers(level)
            );
            revisedSecond.close();
        }
    }

    // -----------------------------------------------------------------
    // Immutability
    // -----------------------------------------------------------------

    private static void immutableResults(ServerLevel level) {
        ComposedGravitySample sample = GravityEngineApi.sample(
                level,
                PROBE
        );

        check(
                throwsUnsupported(() -> sample.contributions().clear()),
                "composed sample contributions list is immutable"
        );
        check(
                throwsUnsupported(
                        () -> sample.contributions().add(
                                new GravityContributionView(
                                        id("fake"),
                                        1L,
                                        Vec3d.ZERO
                                )
                        )
                ),
                "composed sample contributions cannot be mutated"
        );

        Vec3d source =
                new Vec3d(
                        1.0D,
                        2.0D,
                        3.0D
                );

        GravityFieldQuery query =
                GravityFieldQuery.at(source);

        check(
                query.position().equals(source),
                "field query preserves the immutable position value"
        );

        GravityFieldSample fieldSample =
                new GravityFieldSample(Vec3d.X);

        check(
                fieldSample.acceleration().equals(Vec3d.X),
                "field sample preserves the immutable acceleration value"
        );

        GravityInfluenceVolume sphere = GravityFields.sphereInfluence(
                new Vec3d(0.0D, 0.0D, 0.0D),
                4.0D
        );
        check(
                sphere.finiteBounds().isPresent()
                        && sphere.contains(new Vec3d(4.0D, 0.0D, 0.0D))
                        && !sphere.contains(new Vec3d(4.1D, 0.0D, 0.0D)),
                "sphere influence uses closed-surface containment"
        );
        check(
                GravityFields.infiniteInfluence().finiteBounds().isEmpty()
                        && GravityFields.infiniteInfluence()
                        .contains(new Vec3d(1.0E9D, 0.0D, 0.0D)),
                "infinite influence bounds nothing"
        );
    }

    // -----------------------------------------------------------------
    // Built-in factories
    // -----------------------------------------------------------------

    private static void builtInFactories(ServerLevel level) {
        GravityField zero = GravityFields.zeroGravity();
        check(
                zero.sample(GravityFieldQuery.at(PROBE))
                        .acceleration()
                        .lengthSquared() == 0.0D,
                "zero gravity evaluates to zero"
        );

        GravityField height = GravityFields.height(200.0D, 300.0D, 0.08D);
        check(
                height.sample(GravityFieldQuery.at(new Vec3d(0.0D, 100.0D, 0.0D)))
                        .acceleration()
                        .equals(new Vec3d(0.0D, -0.08D, 0.0D)),
                "height gravity is full below fullGravityY"
        );
        check(
                height.sample(GravityFieldQuery.at(new Vec3d(0.0D, 400.0D, 0.0D)))
                        .acceleration()
                        .lengthSquared() == 0.0D,
                "height gravity fades to zero above zeroGravityY"
        );

        double mass = 1.0E12D;
        double surfaceRadius = 32.0D;
        double gravityConstant = 6.0E-11D;
        double minimumFieldAcceleration = 0.009375D;
        double referenceDensity = GravityFields.uniformDensity(
                mass,
                surfaceRadius
        );
        ResourceLocation id = id("spherical");
        GravityField spherical = GravityFields.sphericalMass(
                PROBE,
                mass,
                referenceDensity,
                surfaceRadius,
                gravityConstant
        );
        double cutoff = GravityFields.sphericalCutoffRadius(
                gravityConstant,
                mass,
                surfaceRadius,
                minimumFieldAcceleration
        );
        check(
                Math.abs(cutoff - 80.0D) < 1.0E-9D,
                "spherical cutoff derives from the producer's own G: " + cutoff
        );
        GravityInfluenceVolume influence = GravityFields.sphereInfluence(
                PROBE,
                cutoff
        );

        check(
                influence.contains(
                        new Vec3d(
                                PROBE.x() + 64.0D,
                                PROBE.y(),
                                PROBE.z()
                        )
                ),
                "spherical cutoff influence contains the test point"
        );
        check(
                spherical.sample(GravityFieldQuery.at(PROBE))
                        .acceleration()
                        .lengthSquared() == 0.0D,
                "spherical field is zero at the exact center"
        );

        try (FieldPublication publication = GravityEngineApi.publish(
                level, com.example.examplemod.gravity.ProviderFixture.ID,
                new GravityFieldDefinition(
                        id,
                        GravityFieldSource.named(id),
                        spherical,
                        influence,
                        GravityFieldCompositionMode.OVERRIDE,
                        1L
                )
        )) {
            check(
                    publication.accepted(),
                    "spherical field publishes through the API"
            );

            ComposedGravitySample sample = GravityEngineApi.sample(
                    level,
                    new Vec3d(
                            PROBE.x() + 64.0D,
                            PROBE.y(),
                            PROBE.z()
                    )
            );

            check(
                    sample.contributions().size() == 1,
                    "spherical OVERRIDE is the effective group"
            );
            check(
                    sample.acceleration().x() < 0.0D
                            && Math.abs(sample.acceleration().y()) < 1.0E-9D
                            && Math.abs(sample.acceleration().z()) < 1.0E-9D,
                    "spherical acceleration points at the center: "
                            + sample.acceleration()
            );
            check(
                    Math.abs(
                            sample.accelerationMagnitude()
                                    - gravityConstant * mass / (64.0D * 64.0D)
                    ) < 1.0E-9D,
                    "spherical exterior follows G*M/r^2: "
                            + sample.accelerationMagnitude()
            );
        }
    }

    // -----------------------------------------------------------------
    // Read-only entity snapshot
    // -----------------------------------------------------------------

    private static void entitySnapshot(ServerLevel level) {
        Entity entity = new net.minecraft.world.entity.animal.Pig(
                net.minecraft.world.entity.EntityType.PIG,
                level
        );
        try {
            entity.setPos(PROBE.x(), PROBE.y(), PROBE.z());

            Optional<EntityGravitySnapshot> snapshot =
                    GravityEngineApi.entityGravity(entity);

            check(
                    snapshot.isPresent(),
                    "registered entity exposes a gravity snapshot"
            );

            EntityGravitySnapshot view = snapshot.orElseThrow();
            check(
                    view.authority() != null,
                    "snapshot reports an authority"
            );
            check(
                    view.effectiveDirection().lengthSquared() > 0.0D
                            && Double.isFinite(view.effectiveStrength()),
                    "snapshot reports effective gravity"
            );
            check(
                    view.appliedDirection().lengthSquared() > 0.0D
                            && Double.isFinite(view.appliedStrength()),
                    "snapshot reports applied gravity"
            );
            check(
                    view.applicationEpoch() >= 0L,
                    "snapshot reports a non-negative application epoch"
            );
            Vec3d effectiveAcceleration = view.effectiveAcceleration();
            check(
                    effectiveAcceleration.isFinite(),
                    "snapshot derives a finite effective acceleration"
            );
            check(
                    view.referenceFrame().isEmpty()
                            || view.referenceFrame().orElseThrow()
                            .down().lengthSquared() > 0.0D,
                    "installed reference frame is a coherent snapshot"
            );
        } finally {
            entity.discard();
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private static GravityFieldDefinition definition(
            ResourceLocation id,
            GravityField field,
            GravityFieldCompositionMode mode,
            long revision
    ) {
        return new GravityFieldDefinition(
                id,
                GravityFieldSource.named(id),
                field,
                GravityFields.infiniteInfluence(),
                mode,
                revision
        );
    }

    /**
     * Block-backed definition whose finite influence is a custom
     * {@link GravityInfluenceVolume} implementation written against the
     * public SPI only (no internal geometry type).
     */
    private static GravityFieldDefinition blockDefinition(
            ResourceLocation fieldType,
            BlockPos position,
            GravityField field,
            long revision
    ) {
        return GravityFieldDefinition.block(
                fieldType,
                position,
                field,
                boxInfluence(position, 8.0D),
                GravityFieldCompositionMode.OVERRIDE,
                revision
        );
    }

    private static GravityInfluenceVolume boxInfluence(
            BlockPos center,
            double radius
    ) {
        return new TestBoxInfluence(
                new GravityFieldBounds(
                        center.getX() - radius,
                        center.getY() - radius,
                        center.getZ() - radius,
                        center.getX() + radius,
                        center.getY() + radius,
                        center.getZ() + radius
                )
        );
    }

    /** Custom finite influence written against the supported SPI only. */
    private static final class TestBoxInfluence
            implements GravityInfluenceVolume {
        private final GravityFieldBounds bounds;

        TestBoxInfluence(GravityFieldBounds bounds) {
            this.bounds = bounds;
        }

        @Override
        public boolean contains(Vec3d position) {
            return position.x() >= bounds.minX()
                    && position.x() <= bounds.maxX()
                    && position.y() >= bounds.minY()
                    && position.y() <= bounds.maxY()
                    && position.z() >= bounds.minZ()
                    && position.z() <= bounds.maxZ();
        }

        @Override
        public Optional<GravityFieldBounds> finiteBounds() {
            return Optional.of(bounds);
        }
    }

    private static GravityField constant(
            double x,
            double y,
            double z
    ) {
        return query -> new GravityFieldSample(
                new Vec3d(x, y, z)
        );
    }

    private static List<ResourceLocation> identifiers(ServerLevel level) {
        List<ResourceLocation> ids = new ArrayList<>();
        for (GravityContributionView contribution
                : GravityEngineApi.sample(level, PROBE).contributions()) {
            ids.add(contribution.fieldId());
        }
        return List.copyOf(ids);
    }

    /** Registered revision of one field id, or {@code -1} when absent. */
    private static long revisionOf(ServerLevel level, ResourceLocation id) {
        return revisionOf(level, PROBE, id);
    }

    /** Registered revision of one field id at one sample point. */
    private static long revisionOf(
            ServerLevel level,
            Vec3d at,
            ResourceLocation id
    ) {
        return GravityEngineApi.sample(level, at)
                .contributions()
                .stream()
                .filter(c -> c.fieldId().equals(id))
                .mapToLong(GravityContributionView::revision)
                .findFirst()
                .orElse(-1L);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE, path);
    }

    private static boolean throwsUnsupported(Runnable action) {
        try {
            action.run();
            return false;
        } catch (UnsupportedOperationException expected) {
            return true;
        }
    }

    private static void near(
            Vec3d expected,
            Vec3d actual,
            String message
    ) {
        check(
                expected.distance(actual) < EPSILON,
                message + " expected=" + expected + " actual=" + actual
        );
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError("API boundary: " + message);
        }
    }
}
