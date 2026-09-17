package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.Quatd;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the camera snapshot stores canonical values while preserving
 * the previous JOML composition result at the boundary.
 */
class GravityCameraOrientationInstallerTest {
    private static final double TOLERANCE = 2.0E-6D;

    @Test
    void computeAttitudeMatchesReferenceAndExposesCanonicalBasis() {
        Quatd worldFromController = new Quatd(
                0.270598050073099D,
                0.653281482438188D,
                -0.270598050073099D,
                0.653281482438188D
        ).normalized();
        float modifierYaw = 22.5F;
        float modifierPitch = -13.0F;
        float roll = 7.5F;

        GravityCameraOrientationInstaller.CameraOrientationState state =
                GravityCameraOrientationInstaller.computeAttitude(
                        worldFromController,
                        modifierYaw,
                        modifierPitch,
                        roll
                );

        Quaternionf expected = reference(
                worldFromController,
                modifierYaw,
                modifierPitch,
                roll
        );
        assertQuatEquals(expected, state.rotation(), TOLERANCE);
        assertVecEquals(
                new Vector3f(0.0F, 0.0F, -1.0F).rotate(expected),
                state.forwards(),
                TOLERANCE
        );
        assertVecEquals(
                new Vector3f(0.0F, 1.0F, 0.0F).rotate(expected),
                state.up(),
                TOLERANCE
        );
        assertVecEquals(
                new Vector3f(-1.0F, 0.0F, 0.0F).rotate(expected),
                state.left(),
                TOLERANCE
        );
    }

    private static Quaternionf reference(
            Quatd worldFromController,
            float modifierYaw,
            float modifierPitch,
            float roll
    ) {
        Quaternionf local = new Quaternionf()
                .rotationY((float) -Math.toRadians(modifierYaw))
                .mul(new Quaternionf().rotationX(
                        (float) Math.toRadians(modifierPitch)))
                .mul(new Quaternionf().rotationZ(
                        (float) Math.toRadians(roll)));
        Quaternionf body = new Quaternionf(
                (float) worldFromController.x(),
                (float) worldFromController.y(),
                (float) worldFromController.z(),
                (float) worldFromController.w()
        ).normalize();
        return body.mul(local)
                .mul(new Quaternionf().rotationY((float) Math.PI))
                .normalize();
    }

    private static void assertQuatEquals(
            Quaternionf expected,
            Quatd actual,
            double tolerance
    ) {
        double direct = Math.max(
                Math.max(
                        Math.abs(expected.x - actual.x()),
                        Math.abs(expected.y - actual.y())
                ),
                Math.max(
                        Math.abs(expected.z - actual.z()),
                        Math.abs(expected.w - actual.w())
                )
        );
        double negated = Math.max(
                Math.max(
                        Math.abs(expected.x + actual.x()),
                        Math.abs(expected.y + actual.y())
                ),
                Math.max(
                        Math.abs(expected.z + actual.z()),
                        Math.abs(expected.w + actual.w())
                )
        );
        assertTrue(
                Math.min(direct, negated) <= tolerance,
                "quaternion mismatch: expected=" + expected
                        + " actual=" + actual
        );
    }

    private static void assertVecEquals(
            Vector3f expected,
            Vec3d actual,
            double tolerance
    ) {
        assertEquals(expected.x, actual.x(), tolerance);
        assertEquals(expected.y, actual.y(), tolerance);
        assertEquals(expected.z, actual.z(), tolerance);
    }
}
