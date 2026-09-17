package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.gravity.kinematic.SweepTimeWindow;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterCapsule;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ImmutableVectorMigrationRegressionTest {
    private static final double EPS = 1.0E-12D;
    private static final KinematicStepContext TIME =
            new KinematicStepContext(0L, 1.0D, 0L);

    @Test
    void singlePlaneProjectionActuallyAppliesCorrection() {
        Vec3d desired = new Vec3d(-1.0D, 2.0D, 3.0D);

        var result = ContactConstraintProjector.project(
                desired,
                List.of(
                        new ContactConstraintProjector.Constraint(
                                Vec3d.X,
                                0.0D
                        )
                )
        );

        assertNotNull(result);
        Vec3d projected = result.requireProjectedVector();

        assertEquals(0.0D, projected.x(), EPS);
        assertEquals(2.0D, projected.y(), EPS);
        assertEquals(3.0D, projected.z(), EPS);
    }

    @Test
    void translatedObbQueryContextActuallyMovesCenter() {
        ObbQueryContext context = new ObbQueryContext();
        Aabb3d bounds = new Aabb3d(
                0.0D,
                0.0D,
                0.0D,
                2.0D,
                2.0D,
                2.0D
        );

        var obstacle = context.axisAlignedObstacle(
                bounds,
                new Vec3d(3.0D, 4.0D, 5.0D)
        );

        assertVecEquals(
                new Vec3d(4.0D, 5.0D, 6.0D),
                obstacle.center()
        );
    }

    @Test
    void capsuleFallbackNormalIsUnitAndOpposesClosingMotion() {
        CharacterCapsule first = new CharacterCapsule(
                Vec3d.ZERO,
                Vec3d.X,
                1.0D,
                0.5D
        );
        CharacterCapsule second = new CharacterCapsule(
                Vec3d.ZERO,
                Vec3d.X,
                1.0D,
                0.5D
        );
        Vec3d closingMotion = Vec3d.Z;

        var sweep = CapsuleCapsuleCollision.sweep(
                first,
                closingMotion,
                second,
                Vec3d.ZERO
        );

        assertNotNull(sweep.contact());
        Vec3d normal = sweep.contact().normal();
        assertEquals(1.0D, normal.lengthSquared(), EPS);
        assertTrue(normal.dot(closingMotion) < 0.0D);
    }

    @Test
    void homogeneousAdvancePreservesRequestedDisplacement() {
        OrientedBox start = boxAt(Vec3d.ZERO);
        Vec3d movement = new Vec3d(1.0D, 2.0D, 3.0D);

        var result = KinematicSweepKernel.advanceHomogeneous(
                start,
                movement,
                emptyScene(),
                SweepTimeWindow.full(TIME),
                new ObbQueryContext(),
                4
        );

        assertFalse(result.indeterminate());
        assertVecEquals(movement, result.locomotion());
        assertVecEquals(movement, result.body().center());
    }

    @Test
    void projectedAdvancePreservesAppliedDisplacement() {
        OrientedBox start = boxAt(Vec3d.ZERO);
        Vec3d movement = new Vec3d(1.0D, 2.0D, 3.0D);
        ObbQueryContext context = new ObbQueryContext();
        CollisionScene scene = emptyScene();
        BodyCollisionDelta.Baseline baseline =
                new BodyCollisionDelta.Baseline(
                        start,
                        scene,
                        0.0D,
                        context
                );

        var result = KinematicSweepKernel.advanceProjected(
                start,
                movement,
                scene,
                SweepTimeWindow.full(TIME),
                context,
                baseline,
                4
        );

        assertEquals(
                MovementIndeterminateReason.NONE,
                result.reason()
        );
        assertVecEquals(movement, result.applied());
        assertVecEquals(movement, result.body().center());
    }

    @Test
    void characterRoutePreservesClearLocomotion() {
        OrientedBox start = boxAt(Vec3d.ZERO);
        Vec3d movement = new Vec3d(1.0D, 2.0D, 3.0D);

        GravityMoveResult result = GravityCharacterRoute.resolve(
                start,
                movement,
                GravityFrame.DEFAULT,
                emptyScene(),
                new ObbQueryContext()
        );

        assertFalse(result.indeterminate());
        assertVecEquals(movement, result.locomotionMovement());
        assertVecEquals(movement, result.resolvedMovement());
    }

    @Test
    void recoveryAccumulatesMultiplePassCorrections() {
        OrientedBox start = boxAt(Vec3d.ZERO);
        AtomicInteger calls = new AtomicInteger();
        CollisionObstacleQuery query = (body, movement) ->
                switch (calls.getAndIncrement()) {
                    case 0 -> List.of(
                            block(
                                    1,
                                    new Aabb3d(
                                            0.25D,
                                            -0.5D,
                                            -0.5D,
                                            1.5D,
                                            0.5D,
                                            0.5D
                                    )
                            )
                    );
                    case 2 -> List.of(
                            block(
                                    2,
                                    new Aabb3d(
                                            -0.5D,
                                            0.25D,
                                            -0.5D,
                                            0.5D,
                                            1.5D,
                                            0.5D
                                    )
                            )
                    );
                    default -> List.of();
                };

        CollisionRecovery.RecoveryResult result =
                CollisionRecovery.recover(
                        start,
                        query,
                        new ObbQueryContext()
                );

        assertTrue(result.recovered());
        assertTrue(
                result.recoveryMovement().lengthSquared()
                        > 1.0E-8D
        );
        assertVecEquals(
                result.recoveryMovement(),
                result.recoveredBody()
                        .center()
                        .subtract(start.center())
        );
    }

    private static OrientedBox boxAt(Vec3d center) {
        return OrientedBox.axisAligned(
                new Aabb3d(
                        center.x() - 0.5D,
                        center.y() - 0.5D,
                        center.z() - 0.5D,
                        center.x() + 0.5D,
                        center.y() + 0.5D,
                        center.z() + 0.5D
                )
        );
    }

    private static BlockObstacle block(
            int x,
            Aabb3d bounds
    ) {
        return new BlockObstacle(
                new CellPos(x, 0, 0),
                bounds
        );
    }

    private static CollisionScene emptyScene() {
        return new CollisionScene() {
            @Override
            public List<CollisionObstacle> query(
                    CollisionBody body,
                    Vec3d movement,
                    SweepTimeWindow window
            ) {
                return List.of();
            }

            @Override
            public KinematicStepContext time() {
                return TIME;
            }
        };
    }

    private static void assertVecEquals(
            Vec3d expected,
            Vec3d actual
    ) {
        assertEquals(expected.x(), actual.x(), EPS);
        assertEquals(expected.y(), actual.y(), EPS);
        assertEquals(expected.z(), actual.z(), EPS);
    }
}
