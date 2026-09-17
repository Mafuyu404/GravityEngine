package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.geometry.*;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NormalizedLinearRigidMotionTest {
    @Test void poseVelocityAndEnvelopeFollowNormalizedLinearInterpolation() {
        var motion = new RigidMotionSnapshot(new RigidPose(Vec3d.ZERO, OrthonormalFrame3d.IDENTITY),
                .2, -.1, .3, 0, 2, 0, 10, 1, 1, 1, true);
        var local = new OrientedBox(new Vec3d(2, 0, 0), new Vec3d(.2, .3, .4), OrthonormalFrame3d.IDENTITY);
        for (int i = 1; i < 100; i++) {
            double t = i / 100.0;
            var oracle = new Quaterniond().nlerp(new Quaterniond().rotateY(2), t)
                    .transform(new Vector3d(2, 0, 0)).add(.2*t, -.1*t, .3*t);
            var point = motion.poseAt(t).transformPoint(local.center());
            assertEquals(oracle.x, point.x(), 1e-12);
            assertEquals(oracle.y, point.y(), 1e-12);
            assertEquals(oracle.z, point.z(), 1e-12);
            double h = 1e-6;
            var derivative = motion.poseAt(t+h).transformPoint(local.center())
                    .subtract(motion.poseAt(t-h).transformPoint(local.center())).divide(2*h);
            assertTrue(derivative.distanceSquared(motion.velocityAt(point, t)) < 1e-16);
            assertTrue(motion.maximumPointSpeed(local) >= motion.velocityAt(point, t).length());
            var envelope = motion.sweptBounds(local, t-.01, t+.01);
            var bounds = motion.bodyAt(local, t).enclosingAabb();
            assertTrue(envelope.minX() <= bounds.minX() + 1e-10 && envelope.maxX() >= bounds.maxX() - 1e-10);
            assertTrue(envelope.minZ() <= bounds.minZ() + 1e-10 && envelope.maxZ() >= bounds.maxZ() - 1e-10);
        }
    }
}
