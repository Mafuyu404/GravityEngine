package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterCapsule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CapsuleCapsuleCollisionRegressionTest {
    private static final double EPS = 1.0E-12D;

    @Test
    void parallelCapsulesReportTouchingAndUnitNormal() {
        CharacterCapsule first = capsule(Vec3d.ZERO, Vec3d.X, 0.5D, 1.0D);
        CharacterCapsule second = capsule(
                new Vec3d(0.0D, 0.0D, 1.0D), Vec3d.X, 0.5D, 1.0D
        );

        CapsuleCapsuleCollision.ContactGeometry geometry =
                CapsuleCapsuleCollision.contactGeometry(first, second);

        assertEquals(0.0D, geometry.signedGap(), EPS);
        assertEquals(1.0D, geometry.normal().lengthSquared(), EPS);
        assertEquals(0.0D, geometry.penetration(), EPS);
    }

    @Test
    void nearParallelCapsulesRemainFiniteAndFeasible() {
        double epsilon = 1.0E-9D;
        CharacterCapsule first = capsule(Vec3d.ZERO, Vec3d.X, 0.5D, 1.0D);
        CharacterCapsule second = capsule(
                new Vec3d(0.0D, 0.0D, 1.0D),
                new Vec3d(1.0D, epsilon, 0.0D),
                0.5D,
                1.0D
        );

        CapsuleCapsuleCollision.ContactGeometry geometry =
                CapsuleCapsuleCollision.contactGeometry(first, second);

        assertTrue(Double.isFinite(geometry.signedGap()));
        assertTrue(Double.isFinite(geometry.normal().x()));
        assertTrue(Double.isFinite(geometry.normal().y()));
        assertTrue(Double.isFinite(geometry.normal().z()));
        assertEquals(1.0D, geometry.normal().lengthSquared(), 1.0E-10D);
    }

    @Test
    void coincidentSpinesUseDeterministicUnitFallback() {
        CharacterCapsule first = capsule(Vec3d.ZERO, Vec3d.X, 0.5D, 1.0D);
        CharacterCapsule second = capsule(Vec3d.ZERO, Vec3d.X, 0.5D, 1.0D);

        CapsuleCapsuleCollision.ContactGeometry geometry =
                CapsuleCapsuleCollision.contactGeometry(first, second);

        assertEquals(-1.0D, geometry.signedGap(), EPS);
        assertEquals(1.0D, geometry.normal().lengthSquared(), EPS);
        assertEquals(1.0D, geometry.penetration(), EPS);
    }

    @Test
    void endpointContactAndZeroSpineLengthArePreserved() {
        CharacterCapsule left = capsule(Vec3d.ZERO, Vec3d.X, 0.5D, 0.5D);
        CharacterCapsule right = capsule(
                new Vec3d(2.0D, 0.0D, 0.0D), Vec3d.X, 0.5D, 0.5D
        );
        assertEquals(0.0D,
                CapsuleCapsuleCollision.contactGeometry(left, right).signedGap(),
                EPS);

        CharacterCapsule sphereA = capsule(Vec3d.ZERO, Vec3d.X, 0.5D, 0.0D);
        CharacterCapsule sphereB = capsule(
                new Vec3d(0.0D, 0.0D, 1.0D), Vec3d.X, 0.5D, 0.0D
        );
        assertEquals(0.0D,
                CapsuleCapsuleCollision.contactGeometry(sphereA, sphereB).signedGap(),
                EPS);
    }

    @Test
    void aBSwapAndSegmentReversalPreserveDistanceSemantics() {
        CharacterCapsule first = capsule(Vec3d.ZERO, Vec3d.X, 0.5D, 1.0D);
        CharacterCapsule second = capsule(
                new Vec3d(0.0D, 0.0D, 3.0D), Vec3d.X, 0.5D, 1.0D
        );
        CapsuleCapsuleCollision.ContactGeometry forward =
                CapsuleCapsuleCollision.contactGeometry(first, second);
        CapsuleCapsuleCollision.ContactGeometry swapped =
                CapsuleCapsuleCollision.contactGeometry(second, first);

        assertEquals(forward.signedGap(), swapped.signedGap(), EPS);
        assertTrue(forward.normal().dot(swapped.normal()) < -0.99D);

        CharacterCapsule reversedSecond = new CharacterCapsule(
                second.center(),
                second.axis().negate(),
                second.radius(),
                second.halfSegmentLength()
        );
        assertEquals(
                forward.signedGap(),
                CapsuleCapsuleCollision.contactGeometry(first, reversedSecond)
                        .signedGap(),
                1.0E-9D
        );
    }

    @Test
    void fallbackNormalOpposesClosingMotion() {
        CharacterCapsule first = capsule(Vec3d.ZERO, Vec3d.X, 0.5D, 1.0D);
        CharacterCapsule second = capsule(Vec3d.ZERO, Vec3d.X, 0.5D, 1.0D);
        Vec3d closingMotion = Vec3d.Z;

        CapsuleCapsuleCollision.SweepGeometry sweep =
                CapsuleCapsuleCollision.sweep(
                        first,
                        closingMotion,
                        second,
                        Vec3d.ZERO
                );

        assertNotNull(sweep.contact());
        assertTrue(sweep.contact().normal().dot(closingMotion) < 0.0D);
    }

    @Test
    void sweepFindsEndpointContactTime() {
        CharacterCapsule moving = capsule(Vec3d.ZERO, Vec3d.X, 0.5D, 0.5D);
        CharacterCapsule stationary = capsule(
                new Vec3d(0.0D, 0.0D, 2.0D), Vec3d.X, 0.5D, 0.5D
        );

        CapsuleCapsuleCollision.SweepGeometry sweep =
                CapsuleCapsuleCollision.sweep(
                        moving,
                        new Vec3d(0.0D, 0.0D, 2.0D),
                        stationary,
                        Vec3d.ZERO
                );

        assertEquals(SweepInitialState.SEPARATED, sweep.initialState());
        assertFalse(sweep.indeterminate());
        assertNotNull(sweep.contact());
        assertEquals(0.5D, sweep.timeOfImpact(), 1.0E-6D);
    }

    private static CharacterCapsule capsule(
            Vec3d center,
            Vec3d axis,
            double radius,
            double halfSegmentLength
    ) {
        return new CharacterCapsule(center, axis, radius, halfSegmentLength);
    }
}
