package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.kinematic.KinematicMoveRequest;
import cc.sighs.gravityengine.gravity.kinematic.OwnedMotion;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d;
import cc.sighs.gravityengine.math.geometry.RigidPose;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production character-route regressions for persistent moving support.
 *
 * <p>A rotating support material point follows a curved world trajectory. It
 * must not be collapsed into the endpoint chord: that chord is only the net
 * displacement, not the material path the character is carried along.</p>
 */
class MovingSupportCharacterRouteTest {
    private static final Vec3d UP = new Vec3d(0.0D, 1.0D, 0.0D);
    private static final long SOURCE = 41L;
    private static final long EPOCH = 2L;
    private static final Vec3d DEFAULT_LOCAL_CENTER =
            new Vec3d(0.0D, -1.0D, 0.0D);
    private static final Vec3d DEFAULT_LOCAL_HALF =
            new Vec3d(3.0D, 1.0D, 3.0D);

    @Test
    void rotatingSupportPublishesCurvedMaterialPointTrajectory() {
        CapturedCollisionScene scene = scene(
                Vec3d.ZERO,
                new Vec3d(0.0D, -1.0D, 0.0D)
        );
        SupportTransport transport = transport(
                scene,
                new Vec3d(2.0D, 0.0D, 0.0D)
        );

        SupportMotionTrajectory trajectory =
                transport.trajectory().orElseThrow();

        assertTrue(trajectory.rotating());
        assertTrue(
                trajectory.requiredSegments() > 1,
                "a rotating material point needs more than one chord"
        );

        assertVecEquals(
                trajectory.positionAt(1.0D)
                        .subtract(
                                trajectory.positionAt(0.0D)
                        ),
                transport.displacement()
        );

        Vec3d midpoint =
                trajectory.positionAt(0.5D);

        Vec3d chordMidpoint =
                trajectory.positionAt(0.0D)
                        .add(
                                transport.displacement()
                                        .multiply(0.5D)
                        );

        assertTrue(
                midpoint.distanceSquared(chordMidpoint)
                        > 1.0E-10D,
                "rotating support must not collapse to its endpoint chord"
        );
    }

    /**
     * The obstacle below sits on the real material-point arc and is clear of
     * the endpoint chord. A solver that only consumes the chord would pass
     * straight through it.
     */
    @Test
    void rotatingSupportRouteChecksTheArcNotOnlyTheEndpointChord() {
        Vec3d angular = new Vec3d(0.0D, -1.0D, 0.0D);
        double radius = 3.0D;
        double actorHalf = 0.05D;
        double blockHalf = 0.05D;

        Vec3d startAnchor =
                arcPoint(radius, 0.0D);
        Vec3d arcMidAnchor =
                arcPoint(radius, 0.5D);
        Vec3d endAnchor =
                arcPoint(radius, 1.0D);

        Vec3d startCenter =
                startAnchor.add(new Vec3d(0.0D, actorHalf, 0.0D));
        Vec3d arcMidCenter =
                arcMidAnchor.add(new Vec3d(0.0D, actorHalf, 0.0D));
        Vec3d endCenter =
                endAnchor.add(new Vec3d(0.0D, actorHalf, 0.0D));
        Vec3d chordMidCenter =
                startCenter.add(endCenter)
                        .multiply(0.5D);

        OrientedBox blockGeometry =
                OrientedBox.axisAligned(
                        new Aabb3d(
                                arcMidCenter.x() - blockHalf,
                                arcMidCenter.y() - blockHalf,
                                arcMidCenter.z() - blockHalf,
                                arcMidCenter.x() + blockHalf,
                                arcMidCenter.y() + blockHalf,
                                arcMidCenter.z() + blockHalf
                        )
                );

        /*
         * Premise of the regression: the chord body is clear, the arc body
         * is not.
         */
        assertFalse(
                CollisionNarrowPhase.intersects(
                        actorAt(chordMidCenter, actorHalf),
                        blockGeometry
                ),
                "fixture must be clear along the endpoint chord"
        );
        assertTrue(
                CollisionNarrowPhase.intersects(
                        actorAt(arcMidCenter, actorHalf),
                        blockGeometry
                ),
                "fixture must intersect the true arc"
        );

        BlockObstacle block =
                new BlockObstacle(
                        new CellPos(0, 0, 0),
                        blockGeometry.enclosingAabb()
                );

        CapturedCollisionScene clearScene = scene(
                Vec3d.ZERO,
                angular,
                new Vec3d(0.0D, -1.0D, 0.0D),
                new Vec3d(6.0D, 1.0D, 6.0D),
                List.of()
        );
        CapturedCollisionScene blockedScene = scene(
                Vec3d.ZERO,
                angular,
                new Vec3d(0.0D, -1.0D, 0.0D),
                new Vec3d(6.0D, 1.0D, 6.0D),
                List.of(block)
        );

        SupportTransport clearTransport = transport(
                clearScene,
                startAnchor
        );
        assertTrue(
                clearTransport.trajectory()
                        .orElseThrow()
                        .requiredSegments()
                        <= 64,
                "fixture must stay inside the trajectory budget"
        );

        GravityMoveResult control = route(
                clearScene,
                clearTransport,
                actorAt(startCenter, actorHalf)
        );
        assertVecEquals(
                clearTransport.displacement(),
                control.resolvedMovement(),
                1.0E-6D
        );

        GravityMoveResult blocked = route(
                blockedScene,
                transport(blockedScene, startAnchor),
                actorAt(startCenter, actorHalf)
        );

        assertTrue(
                blocked.blockedTangent()
                        || blocked.indeterminate(),
                "arc obstacle must stop or deprove the route: "
                        + blocked.indeterminateReason()
        );
        assertTrue(
                blocked.resolvedMovement()
                        .distanceSquared(
                                clearTransport.displacement()
                        )
                        > 1.0E-6D,
                "blocked arc route must not reproduce the clear chord"
        );
    }

    @Test
    void translatingSupportPreservesExactDisplacementThroughCharacterRoute() {
        CapturedCollisionScene scene = scene(
                new Vec3d(1.0D, 0.0D, 0.0D),
                Vec3d.ZERO
        );
        SupportTransport exact = transport(
                scene,
                new Vec3d(2.0D, 0.0D, 0.0D)
        );

        GravityMoveResult result = route(
                scene,
                exact,
                actor()
        );

        assertVecEquals(
                exact.displacement(),
                result.resolvedMovement()
        );
    }

    @Test
    void translationPlusRotationPreservesPoseDerivedDisplacement() {
        CapturedCollisionScene scene = scene(
                new Vec3d(0.25D, 0.0D, 0.0D),
                new Vec3d(0.0D, -1.0D, 0.0D)
        );
        SupportTransport exact = transport(
                scene,
                new Vec3d(2.0D, 0.0D, 0.0D)
        );

        GravityMoveResult result = route(
                scene,
                exact,
                actor()
        );

        assertVecEquals(
                exact.displacement(),
                result.resolvedMovement()
        );
    }

    @Test
    void acceptedRotatingSupportTrajectoriesStayInsideCaptureEnvelope() {
        List<MotionFixture> fixtures = List.of(
                new MotionFixture(
                        new Vec3d(0.25D, 0.0D, 0.0D),
                        Vec3d.ZERO,
                        new Vec3d(0.0D, 0.0D, 2.8D)
                ),
                new MotionFixture(
                        new Vec3d(8.0D, 0.0D, 0.0D),
                        Vec3d.ZERO,
                        new Vec3d(0.0D, 0.0D, 0.05D)
                ),
                new MotionFixture(
                        new Vec3d(2.0D, 1.0D, 0.0D),
                        new Vec3d(0.5D, 0.2D, -0.3D),
                        new Vec3d(0.1D, 0.7D, -0.2D)
                ),
                new MotionFixture(
                        new Vec3d(1.2D, -0.4D, 2.0D),
                        new Vec3d(-0.25D, 0.4D, 0.1D),
                        new Vec3d(0.45D, 0.82D, -0.26D)
                ),
                new MotionFixture(
                        new Vec3d(3.0D, 0.0D, 0.0D),
                        Vec3d.ZERO,
                        new Vec3d(0.0D, -1.0D, 0.0D)
                )
        );

        for (MotionFixture fixture : fixtures) {
            SupportMotionTrajectory trajectory =
                    trajectory(
                            fixture.localAnchor(),
                            fixture.translation(),
                            fixture.angular()
                    );
            assertTrue(
                    trajectory.requiredSegments() <= 64,
                    "fixture must be accepted by the trajectory budget"
            );
            Aabb3d relativeBounds =
                    trajectory.relativeBounds();
            Aabb3d worldBounds = trajectory.bounds();

            for (int sample = 0; sample <= 20; sample++) {
                double t = sample / 20.0D;
                Vec3d point = trajectory.positionAt(t);
                assertTrue(
                        worldBounds.contains(point),
                        "world trajectory point outside envelope: "
                                + point
                                + " bounds=" + worldBounds
                );
                assertTrue(
                        relativeBounds.contains(
                                point.subtract(
                                        trajectory.positionAt(0.0D)
                                )
                        ),
                        "relative trajectory point outside envelope"
                );
            }

            Vec3d pathStart = trajectory.positionAt(0.0D);
            Aabb3d initialBounds = new Aabb3d(
                    pathStart.x() - 0.3D,
                    pathStart.y() - 0.9D,
                    pathStart.z() - 0.3D,
                    pathStart.x() + 0.3D,
                    pathStart.y() + 0.9D,
                    pathStart.z() + 0.3D
            );
            CollisionCaptureDomain domain =
                    CollisionCaptureDomain.forTranslationRange(
                            initialBounds,
                            Vec3d.ZERO,
                            Vec3d.ZERO,
                            relativeBounds,
                            0.0D
                    );
            for (int sample = 0; sample <= 20; sample++) {
                assertTrue(
                        domain.staticBounds().contains(
                                trajectory.positionAt(
                                        sample / 20.0D
                                )
                        ),
                        "capture domain does not contain trajectory"
                );
            }
        }
    }

    @Test
    void trajectorySubdivisionUsesOperationBudgetAndFailsClosed() {
        CapturedCollisionScene scene = scene(
                Vec3d.ZERO,
                new Vec3d(0.0D, -0.02D, 0.0D)
        );
        SupportTransport transport = transport(
                scene,
                new Vec3d(3.0D, 0.0D, 0.0D)
        );
        int required =
                transport.trajectory()
                        .orElseThrow()
                        .requiredSegments();
        assertTrue(required > 1);

        GravityMoveResult atBudget = route(
                scene,
                transport,
                actor(),
                budget(required)
        );
        assertFalse(atBudget.indeterminate());

        GravityMoveResult overBudget = route(
                scene,
                transport,
                actor(),
                budget(required - 1)
        );
        assertTrue(overBudget.indeterminate());
        assertEquals(
                MovementIndeterminateReason
                        .SUPPORT_TRAJECTORY_BUDGET,
                overBudget.indeterminateReason()
        );
        assertVecEquals(
                Vec3d.ZERO,
                overBudget.resolvedMovement()
        );
        assertTrue(
                overBudget.resolvedMovement().distanceSquared(
                        transport.displacement()
                ) > 1.0E-12D,
                "budget failure must not fall back to the endpoint chord"
        );

        GravityMoveResult repeated = route(
                scene,
                transport,
                actor(),
                budget(required - 1)
        );
        assertEquals(
                overBudget.indeterminateReason(),
                repeated.indeterminateReason()
        );
        assertVecEquals(
                overBudget.resolvedMovement(),
                repeated.resolvedMovement()
        );
    }

    private static GravityMoveResult route(
            CapturedCollisionScene scene,
            SupportTransport transport,
            CollisionBody actor
    ) {
        return route(
                scene,
                transport,
                actor,
                CollisionWorkBudget.defaults()
        );
    }

    private static GravityMoveResult route(
            CapturedCollisionScene scene,
            SupportTransport transport,
            CollisionBody actor,
            CollisionWorkBudget budget
    ) {
        ObbQueryContext context = new ObbQueryContext();
        context.setWorkTracker(
                new CollisionWorkTracker(budget)
        );
        return SupportedCharacterRoute.resolve(
                actor,
                new KinematicMoveRequest(
                        transport.displacement(),
                        new OwnedMotion(
                                Vec3d.ZERO,
                                Vec3d.ZERO,
                                Vec3d.ZERO,
                                transport.displacement()
                        ),
                        KinematicMoveRequest.Channel.SELF
                ),
                GravityFrame.DEFAULT,
                scene,
                context,
                StepUpIntent.disabled(),
                transport
        );
    }

    private static CollisionWorkBudget budget(
            int maxSupportTrajectorySegments
    ) {
        CollisionWorkBudget defaults =
                CollisionWorkBudget.defaults();
        return new CollisionWorkBudget(
                defaults.maxBlockPositions(),
                defaults.maxObstaclePrimitives(),
                defaults.maxNarrowPhaseTests(),
                defaults.maxCollectedContacts(),
                defaults.maxRecoveryPasses(),
                defaults.maxVoxelReducerChecks(),
                defaults.maxVoxelIndexRegistrations(),
                maxSupportTrajectorySegments
        );
    }

    private static SupportTransport transport(
            CapturedCollisionScene scene,
            Vec3d worldAnchor
    ) {
        return SupportTransportResolver.resolve(
                support(scene, worldAnchor),
                scene
        ).orElseThrow();
    }

    private static PersistentSupportState support(
            CapturedCollisionScene scene,
            Vec3d worldAnchor
    ) {
        DynamicCollisionObstacleSnapshot obstacle =
                scene.dynamicObstacle(new RigidObstacleIdentity(SOURCE, 0L, EPOCH)).orElseThrow();

        return new PersistentSupportState(
                SupportFaceIdentity.dynamic(
                        SOURCE,
                        0L,
                        EPOCH,
                        GravitySupportContact.blockFaceIndex(UP)
                ),
                PersistentSupportState.toLocal(
                        obstacle.motion().poseAt(0.0D),
                        worldAnchor
                ),
                UP,
                obstacle.motion().poseAt(0.0D),
                10L,
                5L,
                100L,
                GravitySupportContact.SupportGeometryKind
                        .REAL_OBSTACLE_FACE
        );
    }

    private static CapturedCollisionScene scene(
            Vec3d angularDisplacement
    ) {
        return scene(Vec3d.ZERO, angularDisplacement);
    }

    private static CapturedCollisionScene scene(
            Vec3d translation,
            Vec3d angularDisplacement
    ) {
        return scene(
                translation,
                angularDisplacement,
                DEFAULT_LOCAL_CENTER,
                DEFAULT_LOCAL_HALF,
                List.of()
        );
    }

    private static SupportMotionTrajectory trajectory(
            Vec3d localAnchor,
            Vec3d translation,
            Vec3d angularDisplacement
    ) {
        RigidMotionSnapshot motion = new RigidMotionSnapshot(
                new RigidPose(
                        Vec3d.ZERO,
                        OrthonormalFrame3d.IDENTITY
                ),
                translation,
                angularDisplacement,
                101L,
                11L,
                EPOCH,
                1.0D
        );
        return new SupportMotionTrajectory(
                motion,
                localAnchor
        );
    }

    private record MotionFixture(
            Vec3d localAnchor,
            Vec3d translation,
            Vec3d angular
    ) {}

    private static CapturedCollisionScene scene(
            Vec3d translation,
            Vec3d angularDisplacement,
            Vec3d localCenter,
            Vec3d localHalfExtents,
            List<BlockObstacle> blocks
    ) {
        OrientedBox local = new OrientedBox(
                localCenter,
                localHalfExtents,
                OrthonormalFrame3d.IDENTITY
        );
        RigidMotionSnapshot motion = new RigidMotionSnapshot(
                new RigidPose(Vec3d.ZERO, OrthonormalFrame3d.IDENTITY),
                translation,
                angularDisplacement,
                101L,
                11L,
                EPOCH,
                1.0D
        );

        return SceneFixtures.scene(
                101L,
                6L,
                1.0D,
                blocks,
                List.of(new DynamicCollisionObstacleSnapshot(
                        SOURCE,
                        0L,
                        local,
                        motion
                ))
        );
    }

    private static OrientedBox actor() {
        return new OrientedBox(
                new Vec3d(2.0D, 0.5D, 0.0D),
                new Vec3d(0.5D, 0.5D, 0.5D),
                OrthonormalFrame3d.IDENTITY
        );
    }

    private static OrientedBox actorAt(
            Vec3d center,
            double halfExtent
    ) {
        return new OrientedBox(
                center,
                new Vec3d(halfExtent, halfExtent, halfExtent),
                OrthonormalFrame3d.IDENTITY
        );
    }

    private static Vec3d arcPoint(
            double radius,
            double normalizedTime
    ) {
        return new Vec3d(
                radius * Math.cos(normalizedTime),
                0.0D,
                radius * Math.sin(normalizedTime)
        );
    }

    private static void assertVecEquals(
            Vec3d expected,
            Vec3d actual
    ) {
        assertVecEquals(expected, actual, 1.0E-9D);
    }

    private static void assertVecEquals(
            Vec3d expected,
            Vec3d actual,
            double tolerance
    ) {
        assertEquals(expected.x(), actual.x(), tolerance);
        assertEquals(
                expected.y(),
                actual.y(),
                tolerance,
                "expected=" + expected + " actual=" + actual
        );
        assertEquals(expected.z(), actual.z(), tolerance);
    }
}
