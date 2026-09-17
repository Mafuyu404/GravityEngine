package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.kinematic.OwnedMotion;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Engine-owned persistent support identity, support transport and
 * surface-relative traction coverage.
 */
class PersistentSupportTransportTest {
    private static final Vec3d UP = new Vec3d(0.0D, 1.0D, 0.0D);
    private static final long SOURCE = 7L;

    @Test
    void terminalSupportIdentitySurvivesWhileTheObstacleIsUnchanged() {
        CapturedCollisionScene scene = translatingScene(
                100L, 5L, new Vec3d(0.0D, 0.0D, 0.0D), 10L
        );
        PersistentSupportState support = dynamicSupport(
                scene,
                new Vec3d(0.5D, 1.0D, 0.5D)
        );

        assertTrue(support.movingObstacleSupport());
        assertFalse(support.staticSupport());

        Optional<SupportTransport> repeated =
                SupportTransportResolver.resolve(support, scene);

        assertTrue(repeated.isPresent());
        assertEquals(Vec3d.ZERO, repeated.get().displacement());
        assertFalse(repeated.get().moving());
    }

    @Test
    void supportUsesLocalAnchor() {
        CapturedCollisionScene scene = translatingScene(
                100L, 5L, Vec3d.ZERO, 10L
        );
        Vec3d localAnchor =
                new Vec3d(0.5D, 1.0D, 0.5D);
        PersistentSupportState support =
                dynamicSupport(scene, localAnchor);

        assertEquals(localAnchor, support.localAnchor());
        assertEquals(10L, support.obstacleRevision());
        assertTrue(support.obstacleId().startsWith("rigid:"));
        assertEquals(
                localAnchor,
                scene.dynamicObstacle(new RigidObstacleIdentity(SOURCE, 0L, 1L))
                        .orElseThrow()
                        .motion()
                        .poseAt(1.0D)
                        .transformPoint(support.localAnchor())
        );
    }

    @Test
    void movingSupportTransformsAnchor() {
        CapturedCollisionScene previous = translatingScene(
                100L, 5L, Vec3d.ZERO, 10L
        );
        PersistentSupportState support =
                dynamicSupport(
                        previous,
                        new Vec3d(0.5D, 1.0D, 0.5D)
                );
        CapturedCollisionScene current = translatingScene(
                101L, 6L, new Vec3d(1.0D, 0.0D, 0.0D), 11L
        );
        DynamicCollisionObstacleSnapshot obstacle =
                current.dynamicObstacle(new RigidObstacleIdentity(SOURCE, 0L, 1L))
                        .orElseThrow();

        assertEquals(
                new Vec3d(1.5D, 1.0D, 0.5D),
                SupportTransportResolver.resolve(
                        support,
                        obstacle
                )
        );
    }

    @Test
    void translatingObstacleTransportsTheLocalAnchor() {
        CapturedCollisionScene previous = translatingScene(
                100L, 5L, new Vec3d(0.0D, 0.0D, 0.0D), 10L
        );
        PersistentSupportState support = dynamicSupport(
                previous,
                new Vec3d(0.5D, 1.0D, 0.5D)
        );

        CapturedCollisionScene current = translatingScene(
                101L, 6L, new Vec3d(1.0D, 0.0D, 0.0D), 11L
        );

        SupportTransport transport =
                SupportTransportResolver.resolve(support, current)
                        .orElseThrow();

        assertEquals(new Vec3d(1.0D, 0.0D, 0.0D), transport.displacement());
        assertEquals(
                new Vec3d(1.0D, 0.0D, 0.0D),
                transport.surfaceVelocity()
        );
        assertEquals(11L, transport.motionRevision());
        assertEquals(1.0D, transport.intervalTicks());
    }

    @Test
    void sceneFreePreflightUsesTheSamePublicationIdentity() {
        CapturedCollisionScene previous = translatingScene(
                100L, 5L, Vec3d.ZERO, 10L
        );
        PersistentSupportState support = dynamicSupport(
                previous,
                new Vec3d(0.5D, 1.0D, 0.5D)
        );
        CapturedCollisionScene current = translatingScene(
                101L, 6L, new Vec3d(1.0D, 0.0D, 0.0D), 11L
        );
        DynamicCollisionObstacleSnapshot obstacle =
                current.dynamicObstacle(new RigidObstacleIdentity(SOURCE, 0L, 1L)).orElseThrow();

        SupportTransport transport = SupportTransportResolver.resolve(
                support,
                obstacle,
                1.0D,
                101L
        ).orElseThrow();

        assertEquals(
                new Vec3d(1.0D, 0.0D, 0.0D),
                transport.displacement()
        );
        assertEquals(11L, transport.motionRevision());

        PersistentSupportState reanchored = new PersistentSupportState(
                support.identity(),
                support.localAnchor(),
                support.normal(),
                obstacle.motion().poseAt(1.0D),
                transport.motionRevision(),
                current.revision(),
                101L,
                support.geometryKind()
        );
        assertEquals(
                Vec3d.ZERO,
                SupportTransportResolver.resolve(
                        reanchored,
                        obstacle,
                        1.0D,
                        101L
                ).orElseThrow().displacement()
        );
    }

    @Test
    void rotatingObstacleProducesTangentialSupportPointVelocity() {
        CapturedCollisionScene previous = rotatingScene(
                100L, 5L, Vec3d.ZERO, 10L
        );
        PersistentSupportState support = dynamicSupport(
                previous,
                new Vec3d(2.0D, 0.0D, 0.0D)
        );

        CapturedCollisionScene current = rotatingScene(
                101L, 6L, new Vec3d(0.0D, 1.0D, 0.0D), 11L
        );

        SupportTransport transport =
                SupportTransportResolver.resolve(support, current)
                        .orElseThrow();

        /* Chord length of a 1 rad rotation at radius 2: 2 * r * sin(theta/2). */
        assertEquals(
                4.0D * Math.sin(0.5D),
                transport.displacement().length(),
                1.0E-9D
        );
        /* |omega x r| = 1 rad/interval * 2 blocks / 1 tick. */
        assertEquals(
                2.0D,
                transport.surfaceVelocity().length(),
                1.0E-9D
        );

        Vec3d endAnchor = support.anchorAt(
                current.dynamicObstacle(new RigidObstacleIdentity(SOURCE, 0L, 1L)).orElseThrow()
                        .motion().poseAt(1.0D)
        );
        assertEquals(
                0.0D,
                transport.surfaceVelocity()
                        .dot(endAnchor.subtract(Vec3d.ZERO)),
                1.0E-9D
        );
    }

    @Test
    void terminalSupportIsAnchoredAtTheSolvedFinalPose() {
        CapturedCollisionScene scene = rotatingScene(
                100L, 5L, new Vec3d(0.0D, 1.0D, 0.0D), 10L
        );
        PersistentSupportState support = dynamicSupport(
                scene,
                new Vec3d(2.0D, 0.0D, 0.0D)
        );
        DynamicCollisionObstacleSnapshot obstacle =
                scene.dynamicObstacle(new RigidObstacleIdentity(SOURCE, 0L, 1L)).orElseThrow();

        assertEquals(
                obstacle.motion().poseAt(1.0D),
                support.obstaclePoseAtCapture()
        );
        assertEquals(new Vec3d(2.0D, 0.0D, 0.0D), support.localAnchor());
        assertEquals(
                support.obstaclePoseAtCapture()
                        .transformPoint(support.localAnchor()),
                support.anchorAt(support.obstaclePoseAtCapture())
        );
    }

    @Test
    void staticSupportKeepsTheOldZeroSurfaceVelocityFriction() {
        GravitySupportContact staticSupport = SceneFixtures.support(
                UP,
                Vec3d.ZERO,
                new Vec3d(0.5D, 1.0D, 0.5D),
                new SupportFaceIdentity(
                        new CellPos(0, 0, 0),
                        SceneFixtures.block(new CellPos(0, 0, 0)).bounds(),
                        0L,
                        GravitySupportContact.blockFaceIndex(UP)
                )
        );
        Vec3d acceleration = new Vec3d(0.0D, -1.0D, 0.0D);

        assertEquals(
                Vec3d.ZERO,
                GroundTractionPolicy.supportedGravity(
                        acceleration,
                        new Vec3d(0.25D, 0.0D, 0.0D),
                        staticSupport
                )
        );
        assertEquals(
                acceleration,
                GroundTractionPolicy.supportedGravity(
                        acceleration,
                        new Vec3d(0.0D, 1.5D, 0.0D),
                        staticSupport
                )
        );
        assertEquals(
                Vec3d.ZERO,
                GroundTractionPolicy.supported(
                        new OwnedMotion(
                                new Vec3d(0.25D, 0.0D, 0.0D),
                                Vec3d.ZERO,
                                Vec3d.ZERO
                        ),
                        staticSupport,
                        1.0D
                ).supportMotion()
        );
    }

    @Test
    void repeatedResolutionNeverAppliesMotionTwice() {
        CapturedCollisionScene previous = translatingScene(
                100L, 5L, Vec3d.ZERO, 10L
        );
        PersistentSupportState support = dynamicSupport(
                previous,
                new Vec3d(0.5D, 1.0D, 0.5D)
        );
        CapturedCollisionScene current = translatingScene(
                101L, 6L, new Vec3d(1.0D, 0.0D, 0.0D), 11L
        );

        SupportTransport first =
                SupportTransportResolver.resolve(support, current)
                        .orElseThrow();
        assertEquals(new Vec3d(1.0D, 0.0D, 0.0D), first.displacement());

        /*
         * The post-application re-anchor captured at the new publication
         * revision must not transport the same interval again.
         */
        PersistentSupportState reanchored = new PersistentSupportState(
                support.identity(),
                support.localAnchor(),
                support.normal(),
                current.dynamicObstacle(new RigidObstacleIdentity(SOURCE, 0L, 1L)).orElseThrow()
                        .motion().poseAt(1.0D),
                first.motionRevision(),
                current.revision(),
                101L,
                support.geometryKind()
        );

        SupportTransport second =
                SupportTransportResolver.resolve(reanchored, current)
                        .orElseThrow();

        assertEquals(Vec3d.ZERO, second.displacement());
    }

    @Test
    void missingOrReplacedObstacleInvalidatesSupport() {
        CapturedCollisionScene previous = translatingScene(
                100L, 5L, Vec3d.ZERO, 10L
        );
        PersistentSupportState support = dynamicSupport(
                previous,
                new Vec3d(0.5D, 1.0D, 0.5D)
        );

        CapturedCollisionScene missing = SceneFixtures.scene(
                101L, 6L, 1.0D, List.of(), List.of()
        );
        assertTrue(
                SupportTransportResolver.resolve(support, missing).isEmpty()
        );

        CapturedCollisionScene replaced = SceneFixtures.scene(
                101L,
                6L,
                1.0D,
                List.of(),
                List.of(SceneFixtures.dynamicObstacle(
                        SOURCE, 0L, 2L, Vec3d.ZERO, 3.0D,
                        new Vec3d(1.0D, 0.0D, 0.0D), Vec3d.ZERO,
                        101L, 11L, 1.0D
                ))
        );
        assertTrue(
                SupportTransportResolver.resolve(support, replaced)
                        .isEmpty()
        );

        CapturedCollisionScene discontinuous = translatingScene(
                101L,
                6L,
                Vec3d.ZERO,
                11L,
                new Vec3d(3.0D, 0.0D, 0.0D)
        );
        assertTrue(
                SupportTransportResolver.resolve(support, discontinuous)
                        .isEmpty()
        );
    }

    @Test
    void resolvingAgainstAMissingObstacleClearsOperationState() {
        GravityOperationState state = new GravityOperationState();
        CapturedCollisionScene previous = translatingScene(
                100L, 5L, Vec3d.ZERO, 10L
        );
        state.setPersistentSupportState(
                dynamicSupport(previous, new Vec3d(0.5D, 1.0D, 0.5D))
        );

        assertTrue(
                state.resolveSupportTransport(
                        SceneFixtures.scene(
                                101L, 6L, 1.0D, List.of(), List.of()
                        )
                ).isEmpty()
        );
        assertNull(state.persistentSupportState());
    }

    @Test
    void staticSupportKeepsOldBehaviorAndZeroSurfaceVelocity() {
        CellPos cell = new CellPos(0, 0, 0);
        BlockObstacle block = SceneFixtures.block(cell);
        CapturedCollisionScene previous = SceneFixtures.scene(
                100L, 5L, 1.0D, List.of(block), List.of()
        );

        SupportFaceIdentity identity = new SupportFaceIdentity(
                cell,
                block.bounds(),
                0L,
                GravitySupportContact.blockFaceIndex(UP)
        );
        EndpointSupportWitness witness = new EndpointSupportWitness(
                SceneFixtures.support(
                        UP, Vec3d.ZERO, new Vec3d(0.5D, 1.0D, 0.5D), identity
                ),
                100L,
                5L
        );

        PersistentSupportState support = PersistentSupportState
                .from(witness, previous)
                .orElseThrow();

        assertTrue(support.staticSupport());
        assertEquals(
                support.localAnchor(),
                support.anchorAt(support.obstaclePoseAtCapture())
        );

        CapturedCollisionScene current = SceneFixtures.scene(
                101L, 6L, 1.0D, List.of(block), List.of()
        );
        SupportTransport transport =
                SupportTransportResolver.resolve(support, current)
                        .orElseThrow();

        assertEquals(Vec3d.ZERO, transport.displacement());
        assertEquals(Vec3d.ZERO, transport.surfaceVelocity());
        assertFalse(transport.moving());

        CapturedCollisionScene removed = SceneFixtures.scene(
                101L, 6L, 1.0D, List.of(), List.of()
        );
        assertTrue(
                SupportTransportResolver.resolve(support, removed).isEmpty()
        );
    }

    @Test
    void staticSupportRevalidatesPrimitiveAndFaceIdentity() {
        CellPos cell = new CellPos(0, 0, 0);
        BlockObstacle block = SceneFixtures.block(cell);
        CapturedCollisionScene previous = SceneFixtures.scene(
                100L, 5L, 1.0D, List.of(block), List.of()
        );
        SupportFaceIdentity identity = new SupportFaceIdentity(
                cell,
                block.bounds(),
                0L,
                GravitySupportContact.blockFaceIndex(UP)
        );
        PersistentSupportState support = PersistentSupportState.from(
                new EndpointSupportWitness(
                        SceneFixtures.support(
                                UP,
                                Vec3d.ZERO,
                                new Vec3d(0.5D, 1.0D, 0.5D),
                                identity
                        ),
                        100L,
                        5L
                ),
                previous
        ).orElseThrow();

        CapturedCollisionScene matching = SceneFixtures.scene(
                101L, 6L, 1.0D, List.of(block), List.of()
        );
        assertTrue(
                SupportTransportResolver.resolve(support, matching)
                        .isPresent()
        );

        BlockObstacle differentPiece = new BlockObstacle(
                cell,
                block.bounds().expanded(0.25D)
        );
        CapturedCollisionScene differentFeature = SceneFixtures.scene(
                102L, 7L, 1.0D, List.of(differentPiece), List.of()
        );
        assertTrue(
                SupportTransportResolver.resolve(
                        support,
                        differentFeature
                ).isEmpty()
        );
    }

    @Test
    void multiPrimitiveCellMatchesTheExactStoredPiece() {
        CellPos cell = new CellPos(0, 0, 0);
        Aabb3d firstBounds = SceneFixtures.block(cell).bounds();
        Aabb3d secondBounds = new Aabb3d(
                0.25D, 0.0D, 0.25D,
                0.75D, 1.0D, 0.75D
        );
        BlockObstacle first = new BlockObstacle(cell, firstBounds);
        BlockObstacle second = new BlockObstacle(cell, secondBounds);
        CapturedCollisionScene previous = SceneFixtures.scene(
                100L, 5L, 1.0D, List.of(first, second), List.of()
        );
        SupportFaceIdentity identity = new SupportFaceIdentity(
                cell,
                secondBounds,
                0L,
                GravitySupportContact.blockFaceIndex(UP)
        );
        PersistentSupportState support = PersistentSupportState.from(
                new EndpointSupportWitness(
                        SceneFixtures.support(
                                UP,
                                Vec3d.ZERO,
                                new Vec3d(0.5D, 1.0D, 0.5D),
                                identity
                        ),
                        100L,
                        5L
                ),
                previous
        ).orElseThrow();

        CapturedCollisionScene matching = SceneFixtures.scene(
                101L, 6L, 1.0D, List.of(first, second), List.of()
        );
        assertTrue(
                SupportTransportResolver.resolve(support, matching)
                        .isPresent()
        );

        CapturedCollisionScene secondChanged = SceneFixtures.scene(
                102L,
                7L,
                1.0D,
                List.of(
                        first,
                        new BlockObstacle(
                                cell,
                                secondBounds.expanded(0.1D)
                        )
                ),
                List.of()
        );
        assertTrue(
                SupportTransportResolver.resolve(
                        support,
                        secondChanged
                ).isEmpty()
        );
    }

    @Test
    void tractionActsOnVelocityRelativeToTheSupportSurface() {
        GravitySupportContact movingSupport = SceneFixtures.support(
                UP,
                new Vec3d(1.0D, 0.0D, 0.0D),
                new Vec3d(0.5D, 1.0D, 0.5D),
                SupportFaceIdentity.dynamic(SOURCE, 0L, 1L, 2)
        );

        /* Riding actor: relative tangential velocity is already zero. */
        assertEquals(
                Vec3d.ZERO,
                GroundTractionPolicy.supportedGravity(
                        new Vec3d(0.0D, -1.0D, 0.0D),
                        new Vec3d(1.0D, 0.0D, 0.0D),
                        movingSupport
                )
        );

        /* Jump: outward relative velocity keeps ordinary gravity. */
        assertEquals(
                new Vec3d(0.0D, -1.0D, 0.0D),
                GroundTractionPolicy.supportedGravity(
                        new Vec3d(0.0D, -1.0D, 0.0D),
                        new Vec3d(1.0D, 1.5D, 0.0D),
                        movingSupport
                )
        );

        /*
         * Surface velocity is relative-contact evidence. It must not replace
         * the exact positional transport owned by the operation.
         */
        Vec3d exactTransport =
                new Vec3d(0.25D, 0.0D, 0.0D);
        OwnedMotion carried = GroundTractionPolicy.supported(
                new OwnedMotion(
                        Vec3d.ZERO,
                        Vec3d.ZERO,
                        Vec3d.ZERO,
                        exactTransport
                ),
                movingSupport,
                1.0D
        );
        assertEquals(
                exactTransport,
                carried.supportMotion()
        );
    }

    @Test
    void motionRevisionRollbackInvalidatesSupport() {
        CapturedCollisionScene previous = translatingScene(
                100L, 5L, Vec3d.ZERO, 10L
        );
        PersistentSupportState support = dynamicSupport(
                previous,
                new Vec3d(0.5D, 1.0D, 0.5D)
        );
        CapturedCollisionScene rolledBack = translatingScene(
                101L, 6L, new Vec3d(1.0D, 0.0D, 0.0D), 9L
        );

        assertTrue(
                SupportTransportResolver.resolve(support, rolledBack)
                        .isEmpty()
        );
        assertTrue(
                SupportTransportResolver.resolve(
                        support,
                        rolledBack.dynamicObstacle(new RigidObstacleIdentity(SOURCE, 0L, 1L))
                                .orElseThrow(),
                        1.0D,
                        101L
                ).isEmpty()
        );
    }

    @Test
    void publishedRigidMotionRejectsInvalidTransforms() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RigidMotionSnapshot(
                        new cc.sighs.gravityengine.math.geometry.RigidPose(
                                Vec3d.ZERO,
                                cc.sighs.gravityengine.math.geometry
                                        .OrthonormalFrame3d.IDENTITY
                        ),
                        new Vec3d(Double.NaN, 0.0D, 0.0D),
                        Vec3d.ZERO,
                        1L,
                        1L,
                        1L,
                        1.0D
                )
        );
    }

    private static PersistentSupportState dynamicSupport(
            CapturedCollisionScene scene,
            Vec3d localContact
    ) {
        DynamicCollisionObstacleSnapshot obstacle =
                scene.dynamicObstacle(new RigidObstacleIdentity(SOURCE, 0L, 1L)).orElseThrow();
        Vec3d worldPoint = obstacle.motion()
                .poseAt(1.0D)
                .transformPoint(localContact);

        EndpointSupportWitness witness = new EndpointSupportWitness(
                SceneFixtures.support(
                        UP,
                        Vec3d.ZERO,
                        worldPoint,
                        SupportFaceIdentity.dynamic(
                                SOURCE,
                                0L,
                                obstacle.motion().continuityEpoch(),
                                GravitySupportContact.blockFaceIndex(UP)
                        )
                ),
                scene.time().gameTick(),
                scene.revision()
        );

        return PersistentSupportState.from(witness, scene)
                .orElseThrow();
    }

    private static CapturedCollisionScene translatingScene(
            long tick,
            long revision,
            Vec3d displacement,
            long motionRevision
    ) {
        return translatingScene(
                tick,
                revision,
                displacement,
                motionRevision,
                Vec3d.ZERO
        );
    }

    private static CapturedCollisionScene translatingScene(
            long tick,
            long revision,
            Vec3d displacement,
            long motionRevision,
            Vec3d startCenter
    ) {
        return SceneFixtures.scene(
                tick,
                revision,
                1.0D,
                List.of(),
                List.of(SceneFixtures.dynamicObstacle(
                        SOURCE,
                        0L,
                        1L,
                        startCenter,
                        3.0D,
                        displacement,
                        Vec3d.ZERO,
                        tick,
                        motionRevision,
                        1.0D
                ))
        );
    }

    private static CapturedCollisionScene rotatingScene(
            long tick,
            long revision,
            Vec3d angularDisplacement,
            long motionRevision
    ) {
        return SceneFixtures.scene(
                tick,
                revision,
                1.0D,
                List.of(),
                List.of(SceneFixtures.dynamicObstacle(
                        SOURCE,
                        0L,
                        1L,
                        Vec3d.ZERO,
                        3.0D,
                        Vec3d.ZERO,
                        angularDisplacement,
                        tick,
                        motionRevision,
                        1.0D
                ))
        );
    }
}
