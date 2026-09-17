package cc.sighs.gravityengine.gravity;

import cc.sighs.gravityengine.api.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GravityFrameTest {
    private static final double EPS =
            1.0E-12D;

    @Test
    void defaultFrameUsesCanonicalAxes() {
        GravityFrame frame =
                GravityFrame.DEFAULT;

        assertVecEquals(
                Vec3d.X,
                frame.left()
        );
        assertVecEquals(
                Vec3d.Y,
                frame.up()
        );
        assertVecEquals(
                Vec3d.Z,
                frame.forward()
        );
        assertVecEquals(
                Vec3d.Y.negate(),
                frame.down()
        );
    }

    @Test
    void arbitraryFrameIsOrthonormalAndMatchesDown() {
        Vec3d down =
                new Vec3d(
                        0.3D,
                        -0.8D,
                        0.5D
                ).normalized();

        GravityFrame frame =
                GravityFrame.fromDown(
                        down,
                        0.08D
                );

        assertVecEquals(
                down,
                frame.down()
        );

        assertEquals(
                1.0D,
                frame.left().lengthSquared(),
                EPS
        );
        assertEquals(
                1.0D,
                frame.up().lengthSquared(),
                EPS
        );
        assertEquals(
                1.0D,
                frame.forward().lengthSquared(),
                EPS
        );

        assertEquals(
                0.0D,
                frame.left()
                        .dot(frame.up()),
                EPS
        );
        assertEquals(
                0.0D,
                frame.left()
                        .dot(frame.forward()),
                EPS
        );
        assertEquals(
                0.0D,
                frame.up()
                        .dot(frame.forward()),
                EPS
        );

        assertVecEquals(
                frame.forward(),
                frame.left()
                        .cross(frame.up())
        );
    }

    @Test
    void worldLocalTransformsRoundTrip() {
        GravityFrame frame =
                GravityFrame.fromDown(
                        new Vec3d(
                                0.2D,
                                -0.9D,
                                0.35D
                        ),
                        0.08D
                );

        Vec3d world =
                new Vec3d(
                        2.5D,
                        -1.0D,
                        7.25D
                );

        assertVecEquals(
                world,
                frame.localToWorld(
                        frame.worldToLocal(world)
                )
        );
    }

    @Test
    void previousFramePreservesTangentContinuity() {
        GravityFrame previous =
                GravityFrame.fromDown(
                        new Vec3d(
                                0.15D,
                                -0.98D,
                                0.02D
                        ),
                        0.08D
                );

        Vec3d acceleration =
                new Vec3d(
                        0.150001D,
                        -0.980001D,
                        0.020001D
                ).normalized()
                        .multiply(0.08D);

        GravityFrame next =
                GravityFrame.fromEnvironmentalEvidence(
                        Vec3d.ZERO,
                        acceleration,
                        previous.down(),
                        previous
                );

        assertTrue(
                previous.forward()
                        .dot(next.forward())
                        > 0.999D
        );
    }

    @Test
    void exactAntiparallelDownStillProducesProperFrame() {
        GravityFrame frame =
                GravityFrame.fromDown(
                        Vec3d.Y,
                        0.08D
                );

        assertVecEquals(
                Vec3d.Y,
                frame.down()
        );

        assertVecEquals(
                frame.forward(),
                frame.left()
                        .cross(frame.up())
        );
    }

    private static void assertVecEquals(
            Vec3d expected,
            Vec3d actual
    ) {
        assertEquals(
                expected.x(),
                actual.x(),
                EPS
        );
        assertEquals(
                expected.y(),
                actual.y(),
                EPS
        );
        assertEquals(
                expected.z(),
                actual.z(),
                EPS
        );
    }
}
