package cc.sighs.gravityengine.gravity.movement;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TravelCapturePlanTest {
    @Test
    void actorAuthorizationIsSeparateFromResolvedCapturedTrajectory() {
        Vec3d transport = new Vec3d(2.0D, 0.0D, 0.0D);
        Vec3d actorRequest = new Vec3d(0.05D, 0.0D, 0.0D);
        TravelCapturePlan plan = TravelCapturePlan.buildIsotropic(
                new Aabb3d(
                        0.0D, 0.0D, 0.0D,
                        1.0D, 1.0D, 1.0D
                ),
                Vec3d.ZERO,
                0.1D,
                transport,
                0.6D
        );

        assertEquals(-0.1D, plan.actorMovementLower().x(), 1.0E-12D);
        assertEquals(0.1D, plan.actorMovementUpper().x(), 1.0E-12D);
        assertEquals(1.9D, plan.resolvedMovementLower().x(), 1.0E-12D);
        assertEquals(2.1D, plan.resolvedMovementUpper().x(), 1.0E-12D);

        assertTrue(plan.coversActorMovement(actorRequest));
        assertTrue(plan.coversResolvedMovement(transport.add(actorRequest)));
        assertTrue(!plan.coversActorMovement(transport));
    }

    @Test
    void transportOnlyMovementStillWidensTheResolvedDomain() {
        Vec3d transport = new Vec3d(2.0D, 0.0D, 0.0D);
        TravelCapturePlan plan = TravelCapturePlan.buildIsotropic(
                new Aabb3d(
                        0.0D, 0.0D, 0.0D,
                        1.0D, 1.0D, 1.0D
                ),
                Vec3d.ZERO,
                0.1D,
                transport,
                0.6D
        );

        assertTrue(plan.coversActorMovement(Vec3d.ZERO));
        assertTrue(plan.coversResolvedMovement(transport));
        assertTrue(
                plan.domain().staticBounds().contains(
                        new Vec3d(2.0D, 0.5D, 0.5D)
                )
        );
    }

    @Test
    void rotatingSupportPathWidensResolvedRangeBeyondTheChord() {
        TravelCapturePlan plan = TravelCapturePlan.buildIsotropic(
                new Aabb3d(
                        0.0D, 0.0D, 0.0D,
                        1.0D, 1.0D, 1.0D
                ),
                Vec3d.ZERO,
                0.1D,
                Vec3d.ZERO,
                new Aabb3d(
                        -1.0D, -0.5D, -0.25D,
                        1.0D, 0.5D, 0.25D
                ),
                0.6D
        );

        assertEquals(-1.1D, plan.resolvedMovementLower().x(), 1.0E-12D);
        assertEquals(1.1D, plan.resolvedMovementUpper().x(), 1.0E-12D);
        assertEquals(-0.6D, plan.resolvedMovementLower().y(), 1.0E-12D);
        assertEquals(0.6D, plan.resolvedMovementUpper().y(), 1.0E-12D);
        assertTrue(
                plan.domain().staticBounds().contains(
                        new Vec3d(1.0D, 0.5D, 0.25D)
                )
        );
    }
}
