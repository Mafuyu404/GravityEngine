package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterCapsule;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d;
import cc.sighs.gravityengine.math.geometry.RigidPose;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RotatingSupportClearanceTest {
    @Test void tangentYawClearanceDoesNotExemptEnteringMotion() {
        var box = new OrientedBox(new Vec3d(2, 300.9, 0), new Vec3d(.3, .9, .3), OrthonormalFrame3d.IDENTITY);
        var capsule = new CharacterCapsule(box.center(), new Vec3d(0, 1, 0), .3, .6);
        var floor = new EntityObstacle(new DynamicCollisionObstacleSnapshot(1, 0,
                new OrientedBox(Vec3d.ZERO, new Vec3d(3, 1, 3), OrthonormalFrame3d.IDENTITY),
                new RigidMotionSnapshot(new RigidPose(new Vec3d(0, 299, 0), OrthonormalFrame3d.IDENTITY),
                        Vec3d.ZERO, new Vec3d(0, .1, 0), 1, 1, 1, 1)));
        var tangent = new Vec3d(-.01, 0, -.2);
        var obbClear = RigidObstacleSweep.sweep(box, tangent, floor, 0, 1, new ObbQueryContext());
        var capsuleClear = CapsuleRigidObstacleSweep.sweep(capsule, tangent, floor, 0, 1);
        assertFalse(obbClear.indeterminate());
        assertFalse(obbClear.hasBlockingContacts());
        assertFalse(capsuleClear.indeterminate());
        assertFalse(capsuleClear.hasBlockingContacts());
        var entering = tangent.add(0, -.01, 0);
        assertTrue(RigidObstacleSweep.sweep(box, entering, floor, 0, 1, new ObbQueryContext()).hasBlockingContacts());
        assertTrue(CapsuleRigidObstacleSweep.sweep(capsule, entering, floor, 0, 1).hasBlockingContacts());
    }
    @Test void translatingFloorEndpointTouchIsClearButCrossingStillBlocks() {
        var capsule = new CharacterCapsule(new Vec3d(0, 301, 0), new Vec3d(0, 1, 0), .3, .6);
        var floor = new EntityObstacle(new DynamicCollisionObstacleSnapshot(1, 0,
                new OrientedBox(Vec3d.ZERO, new Vec3d(3, 1, 3), OrthonormalFrame3d.IDENTITY),
                new RigidMotionSnapshot(new RigidPose(new Vec3d(0, 299, 0), OrthonormalFrame3d.IDENTITY),
                        new Vec3d(0, .1, 0), Vec3d.ZERO, 1, 1, 1, 1)));
        var tangent = new Vec3d(.025, 0, .035);
        var clear = CollisionNarrowPhase.sweptContactResult(capsule, tangent, floor);
        assertFalse(clear.indeterminate());
        assertFalse(clear.hasBlockingContacts());
        assertEquals(SweepInitialState.SEPARATED, clear.initialState());
        assertTrue(CollisionNarrowPhase.sweptContactResult(capsule, tangent.add(0, -.02, 0), floor).hasBlockingContacts());
    }
}
