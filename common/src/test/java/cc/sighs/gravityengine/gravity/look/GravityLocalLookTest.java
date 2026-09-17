package cc.sighs.gravityengine.gravity.look;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.math.Quatd;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Differential checks for the canonical look composition. JOML remains a
 * test-only oracle; production code uses {@link Quatd} and {@link Vec3d}.
 */
class GravityLocalLookTest {
    private static final double QUATERNION_TOLERANCE = 2.0E-6D;
    private static final double VECTOR_TOLERANCE = 2.0E-6D;

    @Test
    void lookQuaternionMatchesReferenceComposition() {
        GravityFrame frame = GravityFrame.fromDown(
                new Vec3d(0.25D, -0.9D, 0.35D).normalized(),
                1.0D
        );

        for (float yaw : new float[]{-180.0F, -37.5F, 0.0F, 61.25F, 180.0F}) {
            for (float pitch : new float[]{-89.0F, -12.5F, 0.0F, 37.0F, 89.0F}) {
                for (float roll : new float[]{-120.0F, 0.0F, 45.0F}) {
                    Quatd actual = GravityLocalLook.lookQuaternion(
                            frame, yaw, pitch, roll);
                    Quaternionf expected = referenceLook(frame, yaw, pitch, roll);

                    assertQuatEquals(expected, actual, QUATERNION_TOLERANCE);
                }
            }
        }
    }

    @Test
    void cameraQuaternionAndBasisMatchReferenceComposition() {
        GravityFrame frame = GravityFrame.fromDown(
                new Vec3d(-0.4D, 0.2D, 1.0D).normalized(),
                1.0D
        );
        float yaw = 33.0F;
        float pitch = -17.0F;
        float roll = 11.0F;

        Quatd actualCamera = GravityLocalLook.cameraQuaternion(
                frame, yaw, pitch, roll);
        Quaternionf expectedCamera = new Quaternionf(
                referenceLook(frame, yaw, pitch, roll)
        ).mul(new Quaternionf().rotationY((float) Math.PI)).normalize();
        assertQuatEquals(
                expectedCamera,
                actualCamera,
                QUATERNION_TOLERANCE
        );

        GravityLocalLook.WorldLook actual =
                GravityLocalLook.toWorld(frame, yaw, pitch, roll);
        assertVecEquals(
                transform(expectedCamera, 0.0F, 0.0F, -1.0F),
                actual.forward(),
                VECTOR_TOLERANCE
        );
        assertVecEquals(
                transform(expectedCamera, 0.0F, 1.0F, 0.0F),
                actual.up(),
                VECTOR_TOLERANCE
        );
        assertVecEquals(
                transform(expectedCamera, -1.0F, 0.0F, 0.0F),
                actual.left(),
                VECTOR_TOLERANCE
        );
        assertVecEquals(
                transform(expectedCamera, 1.0F, 0.0F, 0.0F),
                actual.right(),
                VECTOR_TOLERANCE
        );
    }

    private static Quaternionf referenceLook(
            GravityFrame frame,
            float yaw,
            float pitch,
            float roll
    ) {
        Quaternionf gravity = joml(
                cc.sighs.gravityengine.math.geometry.BodyOrientation3d
                        .quaternion(frame.orientation())
        );
        Quaternionf look = new Quaternionf()
                .rotationY((float) -Math.toRadians(yaw))
                .mul(new Quaternionf().rotationX(
                        (float) Math.toRadians(pitch)))
                .mul(new Quaternionf().rotationZ(
                        (float) Math.toRadians(roll)));
        return gravity.mul(look).normalize();
    }

    private static Quaternionf joml(Quatd value) {
        return new Quaternionf(
                (float) value.x(),
                (float) value.y(),
                (float) value.z(),
                (float) value.w()
        );
    }

    private static Vector3f transform(
            Quaternionf rotation,
            float x,
            float y,
            float z
    ) {
        return new Vector3f(x, y, z).rotate(rotation);
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
