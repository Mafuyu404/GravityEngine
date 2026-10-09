package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.geometry.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ComposedRigidTrajectoryTest {
    private static RigidMotionSnapshot motion(Vec3d origin, Vec3d translation, Vec3d rotation) {
        return new RigidMotionSnapshot(new RigidPose(origin, OrthonormalFrame3d.IDENTITY),
                translation, rotation, 12, 3, 1, .5);
    }
    private static ComposedRigidTrajectory trajectory() {
        return new ComposedRigidTrajectory(
                motion(new Vec3d(10, 4, -3), new Vec3d(1, -2, .4), new Vec3d(0, 2, 0)),
                motion(new Vec3d(3, 0, 1), new Vec3d(-1, .3, 2), new Vec3d(1, 0, 0)), 5, 2);
    }
    private static void close(Vec3d a, Vec3d b, double tolerance) {
        assertEquals(a.x(), b.x(), tolerance);
        assertEquals(a.y(), b.y(), tolerance);
        assertEquals(a.z(), b.z(), tolerance);
    }
    @Test void composedPoseAndVelocityUseBothMotionsAtTheSameTime() {
        var motion = trajectory();
        var point = new Vec3d(1, 2, 3);
        for (int i = 1; i < 100; i++) {
            double t = i / 100.0, h = 1e-6;
            var position = motion.poseAt(t).transformPoint(point);
            close(position, motion.parent().poseAt(t).transformPoint(
                    motion.child().poseAt(t).transformPoint(point)), 1e-12);
            var numerical = motion.poseAt(t+h).transformPoint(point)
                    .subtract(motion.poseAt(t-h).transformPoint(point)).divide(2*h*motion.intervalTicks());
            close(numerical, motion.velocityAt(position, t), 1e-8);
        }
    }
    @Test void nestedEnclosuresContainEveryMaterialCornerIncludingObserverMotion() {
        var motion = trajectory();
        var local = new OrientedBox(new Vec3d(2, 1, -1), new Vec3d(.3, .7, .2), OrthonormalFrame3d.IDENTITY);
        var observer = new Vec3d(2, -3, 1);
        for (int interval = 1; interval <= 10; interval++) {
            double lo = .2, hi = lo + .08*interval;
            var bounds = motion.envelope(local, lo, hi, observer).enclosingAabb();
            for (int i = 0; i <= 100; i++) {
                double fraction = i/100.0, t = lo+(hi-lo)*fraction;
                for (int corner = 0; corner < 8; corner++) {
                    var point = local.center().add(new Vec3d((corner&1)==0?-.3:.3,
                            (corner&2)==0?-.7:.7, (corner&4)==0?-.2:.2));
                    var world = motion.poseAt(t).transformPoint(point).subtract(observer.multiply(fraction));
                    assertTrue(world.x() >= bounds.minX()-1e-10 && world.x() <= bounds.maxX()+1e-10);
                    assertTrue(world.y() >= bounds.minY()-1e-10 && world.y() <= bounds.maxY()+1e-10);
                    assertTrue(world.z() >= bounds.minZ()-1e-10 && world.z() <= bounds.maxZ()+1e-10);
                    assertTrue(motion.velocityAt(world.add(observer.multiply(fraction)), t).length()
                            <= motion.maximumPointSpeed(local)+1e-10);
                }
            }
        }
    }
    @Test void rotatingParentCarriesChildOriginEvenWhenAnchorIsZero() {
        var motion = new ComposedRigidTrajectory(
                motion(Vec3d.ZERO, Vec3d.ZERO, new Vec3d(0, Math.PI*2, 0)),
                motion(new Vec3d(3, 0, 0), Vec3d.ZERO, Vec3d.ZERO), 4, 1);
        var support = new SupportMotionTrajectory(motion, Vec3d.ZERO);
        close(support.positionAt(0), support.positionAt(1), 1e-12);
        close(new Vec3d(-3, 0, 0), support.positionAt(.5), 1e-12);
        assertTrue(support.bounds().minX() <= -3);
        assertTrue(support.requiredSegments(.01) > 1);
        int count = support.requiredSegments(.01);
        for (int i = 0; i < count; i++) {
            double lo = (double)i/count, hi = (double)(i+1)/count;
            var chordMid = support.positionAt(lo).add(support.positionAt(hi)).multiply(.5);
            assertTrue(chordMid.subtract(support.positionAt((lo+hi)*.5)).length() <= .01);
        }
    }
    @Test void pureTranslationKeepsLinearFastPathAndSumsVelocityOnce() {
        var motion = new ComposedRigidTrajectory(
                motion(Vec3d.ZERO, new Vec3d(2, 0, 0), Vec3d.ZERO),
                motion(new Vec3d(3, 0, 0), new Vec3d(0, 1, 0), Vec3d.ZERO), 4, 1);
        assertFalse(motion.rotating());
        close(new Vec3d(4, 2, 0), motion.velocityAt(Vec3d.ZERO, .5), 1e-12);
        close(new Vec3d(1, .5, 0), motion.centerDisplacement(.25, .75), 1e-12);
    }
    @Test void rejectsMismatchedIntervalsAndUncoveredQueries() {
        var parent = trajectory().parent();
        var wrong = new RigidMotionSnapshot(parent.start(), Vec3d.ZERO, Vec3d.ZERO, 13, 1, 1, .5);
        assertThrows(CollisionSceneCoverageException.class, () -> new ComposedRigidTrajectory(parent, wrong, 1, 1));
        assertThrows(CollisionSceneCoverageException.class, () -> trajectory().poseAt(1.01));
        assertThrows(IllegalArgumentException.class, () -> trajectory().centerDisplacement(.8, .2));
    }

    @Test void independentChildRotationCannotUseParentsInvariantPlane() {
        var motion = new ComposedRigidTrajectory(
                motion(Vec3d.ZERO, Vec3d.ZERO, new Vec3d(0, Math.PI/6, 0)),
                motion(Vec3d.ZERO, Vec3d.ZERO, new Vec3d(0, 0, Math.PI/2)), 4, 1);
        var local = new OrientedBox(new Vec3d(2, 0, 0), .1, OrthonormalFrame3d.IDENTITY);
        var obstacle = new EntityObstacle(new DynamicCollisionObstacleSnapshot(1, 1, local, motion));
        var center = motion.bodyAt(local, .5).center();
        var capsule = new cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterCapsule(
                center, new Vec3d(0, 1, 0), .1, .1);
        var box = new OrientedBox(center, .1, OrthonormalFrame3d.IDENTITY);
        var capsuleResult = CapsuleRigidObstacleSweep.sweep(capsule, Vec3d.ZERO, obstacle, 0, 1);
        var boxResult = RigidObstacleSweep.sweep(box, Vec3d.ZERO, obstacle, 0, 1, new ObbQueryContext());
        assertTrue(capsuleResult.hasBlockingContacts());
        assertTrue(boxResult.hasBlockingContacts());
        assertFalse(capsuleResult.indeterminate());
        assertFalse(boxResult.indeterminate());
    }

    @Test void accelerationBoundControlsChordErrorWithNlerpAndCoriolisMotion() {
        var parent = new RigidMotionSnapshot(new RigidPose(Vec3d.ZERO, OrthonormalFrame3d.IDENTITY),
                .1, .2, .3, 0, Math.PI, 0, 12, 3, 1, .5, true);
        var motion = new ComposedRigidTrajectory(parent, trajectory().child(), 4, 1);
        var point = new Vec3d(2, -1, 3);
        var support = new SupportMotionTrajectory(motion, point);
        var pointBody = new OrientedBox(point, 0, OrthonormalFrame3d.IDENTITY);
        int count = support.requiredSegments(.001);
        assertTrue(count > 1 && count < 512, "ordinary composed motion must have usable subdivisions");
        for (int i = 0; i < count; i++) {
            double lo = (double)i/count, hi = (double)(i+1)/count;
            for (int j = 1; j < 10; j++) {
                double fraction = j/10.0;
                var chord = support.positionAt(lo).fma(fraction, support.displacement(lo, hi));
                double time = lo+(hi-lo)*fraction;
                assertTrue(chord.subtract(support.positionAt(time)).length() <= .001);
                double h = 1e-6;
                var acceleration = support.velocityAt(time+h).subtract(support.velocityAt(time-h))
                        .multiply(motion.intervalTicks()/(2*h));
                assertTrue(acceleration.length() <= motion.maximumPointAcceleration(pointBody)+1e-8);
            }
        }
    }
}
