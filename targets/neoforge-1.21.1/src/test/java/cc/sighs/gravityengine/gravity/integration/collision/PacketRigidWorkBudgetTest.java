package cc.sighs.gravityengine.gravity.integration.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.collision.CollisionComplexityLimitException;
import cc.sighs.gravityengine.gravity.collision.CollisionWorkBudget;
import cc.sighs.gravityengine.gravity.collision.CollisionWorkTracker;
import cc.sighs.gravityengine.gravity.collision.DynamicCollisionObstacleSnapshot;
import cc.sighs.gravityengine.gravity.collision.RigidMotionSnapshot;
import cc.sighs.gravityengine.gravity.collision.RigidPublicationCollector;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d;
import cc.sighs.gravityengine.math.geometry.RigidPose;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The packet rigid path must bound dynamic-rigid work and fail closed instead
 * of accumulating an unbounded obstacle list.
 */
class PacketRigidWorkBudgetTest {
    private static final KinematicStepContext TIME =
            KinematicStepContext.fullTick(100L, 0L);

    @Test
    void boundedCollectorAcceptsOrdinarySmallCapture() {
        CollisionWorkTracker tracker =
                new CollisionWorkTracker(
                        CollisionWorkBudget.defaults()
                );
        RigidPublicationCollector collector =
                MinecraftCollisionSceneCapture
                        .boundedRigidCollector(TIME, tracker);

        for (int index = 0; index < 4; index++) {
            collector.addObstacle(obstacle(index));
        }

        assertFalse(tracker.limitExceeded());
        assertEquals(4, tracker.snapshot().obstaclesProduced());
    }

    @Test
    void externalProviderPublicationCannotExceedThePacketBudgetSilently() {
        CollisionWorkTracker tracker =
                new CollisionWorkTracker(
                        new CollisionWorkBudget(
                                1L,
                                2,
                                1,
                                1,
                                1,
                                1,
                                1,
                                1
                        )
                );
        RigidPublicationCollector collector =
                MinecraftCollisionSceneCapture
                        .boundedRigidCollector(TIME, tracker);

        collector.addObstacle(obstacle(0));
        collector.addObstacle(obstacle(1));

        assertThrows(
                CollisionComplexityLimitException.class,
                () -> collector.addObstacle(obstacle(2)),
                "the third primitive must be refused, not accumulated"
        );
        assertTrue(tracker.limitExceeded());
    }

    private static DynamicCollisionObstacleSnapshot obstacle(int index) {
        return new DynamicCollisionObstacleSnapshot(
                "packet-budget-provider",
                100L + index,
                0L,
                OrientedBox.axisAligned(
                        new Aabb3d(
                                -0.5D, -0.5D, -0.5D,
                                0.5D, 0.5D, 0.5D
                        )
                ),
                new RigidMotionSnapshot(
                        new RigidPose(
                                Vec3d.ZERO,
                                OrthonormalFrame3d.IDENTITY
                        ),
                        Vec3d.ZERO,
                        Vec3d.ZERO,
                        TIME.gameTick(),
                        1L,
                        0L,
                        TIME.intervalTicks()
                )
        );
    }
}
