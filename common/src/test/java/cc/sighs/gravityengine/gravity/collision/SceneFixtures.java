package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d;
import cc.sighs.gravityengine.math.geometry.RigidPose;
import java.util.List;
import java.util.Map;

/** Shared immutable-scene fixtures for focused engine dynamics tests. */
final class SceneFixtures {
    private SceneFixtures() {}

    static CapturedCollisionScene scene(
            long tick,
            long revision,
            double intervalTicks,
            List<BlockObstacle> blocks,
            List<DynamicCollisionObstacleSnapshot> dynamics
    ) {
        Aabb3d bounds = new Aabb3d(
                -64.0D, -64.0D, -64.0D,
                64.0D, 64.0D, 64.0D
        );
        return new CapturedCollisionScene(
                CollisionCaptureDomain.around(bounds, 8.0D),
                tick,
                revision,
                new KinematicStepContext(tick, intervalTicks, revision),
                new CollisionWorkTracker(CollisionWorkBudget.defaults()),
                new WorldBorderCollisionSnapshot(
                        -1000.0D, -1000.0D, 1000.0D, 1000.0D
                ),
                blocks,
                dynamics,
                List.of(),
                List.of(),
                Map.of(),
                blocks.size()
        );
    }

    static DynamicCollisionObstacleSnapshot dynamicObstacle(
            long sourceId,
            long primitiveId,
            long continuityEpoch,
            Vec3d startCenter,
            double halfExtent,
            Vec3d displacement,
            Vec3d angularDisplacement,
            long tick,
            long revision,
            double intervalTicks
    ) {
        OrientedBox local = new OrientedBox(
                Vec3d.ZERO,
                new Vec3d(halfExtent, halfExtent, halfExtent),
                OrthonormalFrame3d.IDENTITY
        );
        RigidMotionSnapshot motion = new RigidMotionSnapshot(
                new RigidPose(startCenter, OrthonormalFrame3d.IDENTITY),
                displacement,
                angularDisplacement,
                tick,
                revision,
                continuityEpoch,
                intervalTicks
        );
        return new DynamicCollisionObstacleSnapshot(
                sourceId,
                primitiveId,
                local,
                motion
        );
    }

    static BlockObstacle block(CellPos cell) {
        return new BlockObstacle(
                cell,
                new Aabb3d(
                        cell.x(), cell.y(), cell.z(),
                        cell.x() + 1.0D,
                        cell.y() + 1.0D,
                        cell.z() + 1.0D
                )
        );
    }

    static GravitySupportContact support(
            Vec3d normal,
            Vec3d surfaceVelocity,
            Vec3d point,
            SupportFaceIdentity identity
    ) {
        return new GravitySupportContact(
                normal,
                surfaceVelocity,
                point,
                GravitySupportContact.SupportGeometryKind.REAL_OBSTACLE_FACE,
                identity
        );
    }
}
