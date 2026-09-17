package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SupportReleaseVelocityTest {
    private static final Vec3d ANCHOR = new Vec3d(1, 1, 0);

    private static DynamicCollisionObstacleSnapshot publication(
            long tick, long revision, long continuity, Vec3d center, Vec3d motion, Vec3d angular) {
        return SceneFixtures.dynamicObstacle(42, 7, continuity, center, 1,
                motion, angular, tick, revision, 1).withProviderIdentity("test:release", 3);
    }

    private static PersistentSupportState support(DynamicCollisionObstacleSnapshot p) {
        return new PersistentSupportState(
                SupportFaceIdentity.dynamic(new EntityObstacle(p), 3),
                ANCHOR, new Vec3d(0, 1, 0), p.motion().poseAt(1),
                p.motion().revision(), 1, p.motion().tick(),
                GravitySupportContact.SupportGeometryKind.REAL_OBSTACLE_FACE);
    }

    private static void vector(Vec3d expected, Vec3d actual) {
        assertEquals(expected.x(), actual.x(), 1e-9);
        assertEquals(expected.y(), actual.y(), 1e-9);
        assertEquals(expected.z(), actual.z(), 1e-9);
    }

    @Test void newerPublicationUsesCurrentStartVelocity() {
        var previous = publication(100, 1, 0, Vec3d.ZERO, new Vec3d(.1, 0, 0), Vec3d.ZERO);
        var current = publication(101, 2, 0, new Vec3d(.1, 0, 0),
                new Vec3d(.4, .2, 0), new Vec3d(0, Math.PI / 2, 0));
        vector(new Vec3d(.4, .2, -Math.PI / 2),
                SupportTransportResolver.resolveReleaseVelocity(support(previous), current,
                        KinematicStepContext.fullTick(101, 0)).orElseThrow());
    }

    @Test void consumedPublicationRetainsNonzeroEndpointVelocity() {
        var current = publication(100, 1, 0, Vec3d.ZERO,
                new Vec3d(.4, 0, 0), new Vec3d(0, Math.PI / 2, 0));
        var support = support(current);
        assertFalse(SupportTransportResolver.resolve(support, current, 1, 100).orElseThrow().moving());
        vector(new Vec3d(.4 - Math.PI / 2, 0, 0),
                SupportTransportResolver.resolveReleaseVelocity(support, current,
                        KinematicStepContext.fullTick(100, 0)).orElseThrow());
    }

    @Test void rejectsChangedContinuityAndProviderEpoch() {
        var previous = publication(100, 1, 0, Vec3d.ZERO, Vec3d.ZERO, Vec3d.ZERO);
        var changed = publication(101, 2, 1, Vec3d.ZERO, Vec3d.ZERO, Vec3d.ZERO);
        var time = KinematicStepContext.fullTick(101, 0);
        assertTrue(SupportTransportResolver.resolveReleaseVelocity(support(previous), changed, time).isEmpty());
        var replaced = publication(101, 2, 0, Vec3d.ZERO, Vec3d.ZERO, Vec3d.ZERO)
                .withProviderIdentity("test:release", 4);
        assertTrue(SupportTransportResolver.resolveReleaseVelocity(support(previous), replaced, time).isEmpty());
    }

    @Test void rejectsStaleTimeAndDiscontinuousPose() {
        var previous = publication(100, 1, 0, Vec3d.ZERO, Vec3d.ZERO, Vec3d.ZERO);
        var time = KinematicStepContext.fullTick(101, 0);
        assertTrue(SupportTransportResolver.resolveReleaseVelocity(support(previous), previous, time).isEmpty());
        var teleported = publication(101, 2, 0, new Vec3d(4, 0, 0), Vec3d.ZERO, Vec3d.ZERO);
        assertTrue(SupportTransportResolver.resolveReleaseVelocity(support(previous), teleported, time).isEmpty());
    }

    @Test void sameRevisionCannotHideChangedEndpointPose() {
        var previous = publication(100, 1, 0, Vec3d.ZERO, Vec3d.ZERO, Vec3d.ZERO);
        var corrupt = publication(100, 1, 0, Vec3d.ZERO, new Vec3d(1, 0, 0), Vec3d.ZERO);
        assertTrue(SupportTransportResolver.resolveReleaseVelocity(support(previous), corrupt,
                KinematicStepContext.fullTick(100, 0)).isEmpty());
    }
}
