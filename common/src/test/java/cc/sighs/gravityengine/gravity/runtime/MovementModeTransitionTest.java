package cc.sighs.gravityengine.gravity.runtime;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d;
import cc.sighs.gravityengine.math.geometry.RigidPose;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class MovementModeTransitionTest {
    @Test void releaseReplacesCreditExactlyOnceAndInvalidatesSupport() {
        for (var next : new GravityOperationState.MovementMode[]{
                GravityOperationState.MovementMode.ELYTRA, GravityOperationState.MovementMode.NATIVE_FALLBACK}) {
            var runtime = new GravityOperationState();
            runtime.setPersistentSupportState(new PersistentSupportState(
                    new SupportFaceIdentity(null, null, "test:platform", 7, 1, 2, 3, 3),
                    Vec3d.ZERO, Vec3d.Y, new RigidPose(Vec3d.ZERO, OrthonormalFrame3d.IDENTITY), 1, 1, 10,
                    GravitySupportContact.SupportGeometryKind.REAL_OBSTACLE_FACE));
            runtime.setSupportVelocityContribution(new Vec3d(.1, .2, 0));
            Vec3d velocity = new Vec3d(.6, .2, .3);
            velocity = velocity.add(runtime.transitionMovementMode(next, Optional.of(new Vec3d(.4, .1, 0))));
            assertEquals(.9, velocity.x(), 1e-12);
            assertEquals(.1, velocity.y(), 1e-12);
            assertEquals(.3, velocity.z(), 1e-12);
            assertNull(runtime.persistentSupportState());
            assertEquals(Vec3d.ZERO, runtime.supportVelocityContribution());
            assertEquals(Vec3d.ZERO, runtime.transitionMovementMode(next, Optional.of(new Vec3d(10, 0, 0))));
            assertEquals(Vec3d.ZERO, runtime.transitionMovementMode(GravityOperationState.MovementMode.GROUND_AIR, Optional.empty()));
            assertNull(runtime.restingContactSnapshot());
        }
    }
}
