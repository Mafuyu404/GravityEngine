package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterCapsule;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OperationPoseResizeTest {
    record Fixture(CapturedCollisionScene scene, ObbQueryContext context) {}

    static Fixture fixture(List<BlockObstacle> blocks,
                           List<DynamicCollisionObstacleSnapshot> dynamics,
                           List<EntityObstacle> poses, CollisionWorkBudget budget) {
        var bounds = new Aabb3d(-10, -10, -10, 10, 10, 10);
        var tracker = new CollisionWorkTracker(budget);
        var context = new ObbQueryContext();
        context.setWorkTracker(tracker);
        return new Fixture(new CapturedCollisionScene(
                new CollisionCaptureDomain(bounds, bounds), 100, 1,
                KinematicStepContext.fullTick(100, 1), tracker,
                new WorldBorderCollisionSnapshot(-1000, -1000, 1000, 1000),
                blocks, dynamics, List.of(), poses, Map.of(), blocks.size()), context);
    }

    static OrientedBox box(double half) {
        return OrientedBox.axisAligned(new Aabb3d(-half, -half, -half, half, half, half));
    }

    @Test void sidewaysGrowthRejectsWallInvisibleToVanillaAabb() {
        var oldBody = new CharacterCapsule(new Vec3d(0, .75, 0), new Vec3d(1, 0, 0), .3, .45);
        var candidate = new CharacterCapsule(new Vec3d(0, .9, 0), new Vec3d(1, 0, 0), .3, .6);
        var wall = new BlockObstacle(new CellPos(1, 0, 0), new Aabb3d(.8, -2, -2, 1.8, 3, 2));
        var f = fixture(List.of(wall), List.of(), List.of(), CollisionWorkBudget.defaults());
        assertFalse(BodyCollisionDelta.comparePoseAt(oldBody, candidate, f.scene(), 1, f.context()).legal());
    }

    @Test void shrinkingBodyCanReduceExistingOverlap() {
        var wall = new BlockObstacle(new CellPos(1, 0, 0), new Aabb3d(.8, -2, -2, 1.8, 3, 2));
        var f = fixture(List.of(wall), List.of(), List.of(), CollisionWorkBudget.defaults());
        assertTrue(BodyCollisionDelta.comparePoseAt(box(1), box(.5), f.scene(), 1, f.context()).legal());
    }

    @Test void movingWallUsesRequestedInstantForDiscoveryAndContact() {
        var wall = SceneFixtures.dynamicObstacle(1, 1, 1, new Vec3d(3, 0, 0), .1,
                new Vec3d(-2, 0, 0), Vec3d.ZERO, 100, 1, 1);
        var f = fixture(List.of(), List.of(wall), List.of(), CollisionWorkBudget.defaults());
        assertTrue(BodyCollisionDelta.comparePoseAt(box(.5), box(1.2), f.scene(), 0, f.context()).legal());
        assertFalse(BodyCollisionDelta.comparePoseAt(box(.5), box(1.2), f.scene(), 1, f.context()).legal());
    }

    @Test void poseStrictEntityParticipatesInResize() {
        var obstacle = new EntityObstacle(99, new Aabb3d(.8, -.1, -.1, 1, .1, .1));
        var f = fixture(List.of(), List.of(), List.of(obstacle), CollisionWorkBudget.defaults());
        assertFalse(BodyCollisionDelta.comparePoseAt(box(.5), box(1.2), f.scene(), 1, f.context()).legal());
    }

    @Test void outOfCaptureGrowthIsUnknownInsteadOfClear() {
        var f = fixture(List.of(), List.of(), List.of(), CollisionWorkBudget.defaults());
        assertThrows(CollisionSceneCoverageException.class,
                () -> BodyCollisionDelta.comparePoseAt(box(.5), box(20), f.scene(), 1, f.context()));
    }

    @Test void partialQueryCannotBecomeClearanceProof() {
        var one = new EntityObstacle(1, new Aabb3d(.8, -.1, -.1, 1, .1, .1));
        var two = new EntityObstacle(2, new Aabb3d(-1, -.1, -.1, -.8, .1, .1));
        var f = fixture(List.of(), List.of(), List.of(one, two), new CollisionWorkBudget(100, 1, 100, 100, 10));
        assertThrows(CollisionComplexityLimitException.class,
                () -> BodyCollisionDelta.comparePoseAt(box(.5), box(1.2), f.scene(), 1, f.context()));
    }
}
