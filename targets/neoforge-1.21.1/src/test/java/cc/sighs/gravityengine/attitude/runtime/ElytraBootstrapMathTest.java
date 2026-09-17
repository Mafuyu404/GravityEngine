package cc.sighs.gravityengine.attitude.runtime;

import cc.sighs.gravityengine.math.Quatd;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Differential check for the Vanilla Elytra bootstrap composition after its
 * ownership moved from JOML to {@link Quatd}.
 */
class ElytraBootstrapMathTest {
    private static final double TOLERANCE = 2.0E-6D;

    @Test
    void composeElytraBootstrapMatchesPreviousJomlComposition() {
        Quatd frameRotation = new Quatd(
                0.182574185835055D,
                -0.365148371670111D,
                0.547722557505166D,
                0.730296743340222D
        ).normalized();

        for (float yBodyRot : new float[]{
                -135.0F, -20.0F, 0.0F, 47.5F, 179.0F
        }) {
            for (float xRot : new float[]{
                    -89.0F, -30.0F, 0.0F, 25.0F, 89.0F
            }) {
                for (float blend : new float[]{0.0F, 0.25F, 0.75F, 1.0F}) {
                    for (Double alignment : new Double[]{
                            null, -1.2D, 0.35D, 2.4D
                    }) {
                        Quatd actual = ElytraBootstrapMath.compose(
                                frameRotation,
                                yBodyRot,
                                xRot,
                                blend,
                                alignment
                        );
                        Quatd expected = referenceCompose(
                                frameRotation,
                                yBodyRot,
                                xRot,
                                blend,
                                alignment
                        );

                        assertQuatEquals(
                                expected,
                                actual,
                                TOLERANCE
                        );
                    }
                }
            }
        }
    }

    private static Quatd referenceCompose(
            Quatd frameRotation,
            float yBodyRotDegrees,
            float xRotDegrees,
            float flightBlend,
            Double alignmentAngleRadians
    ) {
        Quaternionf local = new Quaternionf().rotationY(
                (float) Math.toRadians(180.0F - yBodyRotDegrees));
        local.mul(new Quaternionf().rotationX(
                (float) Math.toRadians(
                        flightBlend * (-90.0F - xRotDegrees))));
        if (alignmentAngleRadians != null) {
            local.mul(new Quaternionf().rotationY(
                    (float) (double) alignmentAngleRadians));
        }
        local.mul(new Quaternionf().rotationY((float) -Math.PI));
        Quaternionf world = new Quaternionf(
                (float) frameRotation.x(),
                (float) frameRotation.y(),
                (float) frameRotation.z(),
                (float) frameRotation.w()
        ).mul(local);
        return new Quatd(
                world.x(),
                world.y(),
                world.z(),
                world.w()
        ).normalized();
    }

    private static void assertQuatEquals(
            Quatd expected,
            Quatd actual,
            double tolerance
    ) {
        double direct = Math.max(
                Math.max(
                        Math.abs(expected.x() - actual.x()),
                        Math.abs(expected.y() - actual.y())
                ),
                Math.max(
                        Math.abs(expected.z() - actual.z()),
                        Math.abs(expected.w() - actual.w())
                )
        );
        double negated = Math.max(
                Math.max(
                        Math.abs(expected.x() + actual.x()),
                        Math.abs(expected.y() + actual.y())
                ),
                Math.max(
                        Math.abs(expected.z() + actual.z()),
                        Math.abs(expected.w() + actual.w())
                )
        );
        assertTrue(
                Math.min(direct, negated) <= tolerance,
                "quaternion mismatch: expected=" + expected
                        + " actual=" + actual
        );
    }
}
