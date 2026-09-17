package cc.sighs.gravityengine.math.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.Quatd;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Differential verification against the JOML behavior that defined the
 * pre-refactor implementation. JOML remains a test-only oracle.
 */
class JomlDifferentialTest {
    private static final long SEED = 0x5EED_1234_5678_9ABCL;
    private static final int SAMPLE_COUNT = 512;
    /*
     * JOML uses fused multiply-add in Vector3d.dot on modern JVMs. The
     * absolute 1e-12 band is about 2e-15 relative for the generated
     * sample magnitude and only covers that final rounding difference.
     */
    private static final double VECTOR_TOLERANCE = 1.0E-12D;
    private static final double QUATERNION_TOLERANCE = 1.0E-12D;

    @Test
    void vectorOperationsMatchJoml() {
        List<Vec3d> samples = randomVectors(SAMPLE_COUNT);
        for (int i = 0; i + 1 < samples.size(); i += 2) {
            Vec3d first = samples.get(i);
            Vec3d second = samples.get(i + 1);
            Vector3d firstJoml = joml(first);
            Vector3d secondJoml = joml(second);

            assertEquals(firstJoml.dot(secondJoml),
                    first.dot(second),
                    VECTOR_TOLERANCE);
            assertVecEquals(firstJoml.cross(secondJoml, new Vector3d()),
                    first.cross(second),
                    VECTOR_TOLERANCE);
            assertVecEquals(firstJoml.normalize(new Vector3d()),
                    first.normalized(),
                    VECTOR_TOLERANCE);
            assertVecEquals(new Vector3d(first.x(), first.y(), first.z())
                            .mul(3.25D, new Vector3d()),
                    first.multiply(3.25D),
                    VECTOR_TOLERANCE);
        }
    }

    @Test
    void quaternionCompositionAndTransformsMatchJoml() {
        List<Quatd> samples = randomQuaternions(SAMPLE_COUNT / 2);
        List<Vec3d> vectors = randomVectors(SAMPLE_COUNT / 2);
        for (int i = 0; i < samples.size(); i++) {
            Quatd first = samples.get(i);
            Quatd second = samples.get((i * 37 + 11) % samples.size());
            Vec3d vector = vectors.get(i);
            Quaterniond firstJoml = joml(first);
            Quaterniond secondJoml = joml(second);

            assertQuatEquals(firstJoml.mul(secondJoml, new Quaterniond()),
                    first.multiply(second),
                    QUATERNION_TOLERANCE);
            assertQuatEquals(firstJoml.conjugate(new Quaterniond()),
                    first.conjugate(),
                    QUATERNION_TOLERANCE);
            assertVecEquals(firstJoml.transform(joml(vector), new Vector3d()),
                    first.transform(vector),
                    VECTOR_TOLERANCE);
        }
    }

    @Test
    void rotationToMatchesJomlRotationResult() {
        List<Vec3d> samples = randomVectors(128);

        for (int i = 0;
                i + 1 < samples.size();
                i += 2) {

            Vec3d from =
                    samples.get(i);
            Vec3d to =
                    samples.get(i + 1);

            Quatd gravityRotation =
                    Quatd.rotationTo(
                            from,
                            to
                    );

            Quaterniond jomlRotation =
                    new Quaterniond()
                            .rotationTo(
                                    joml(from),
                                    joml(to)
                            )
                            .normalize();

            Vec3d actual =
                    gravityRotation.transform(
                            from.normalized()
                    );

            Vector3d expected =
                    jomlRotation.transform(
                            joml(from.normalized()),
                            new Vector3d()
                    );

            assertVecEquals(
                    expected,
                    actual,
                    VECTOR_TOLERANCE
            );

            assertVecEquals(
                    joml(to.normalized()),
                    actual,
                    VECTOR_TOLERANCE
            );

            /*
             * Quaternion components are not uniquely comparable for an
             * exact antiparallel mapping because the 180-degree axis is
             * not unique. Compare components only away from that case.
             */
            double dot =
                    from.normalized()
                            .dot(to.normalized());

            if (dot > -1.0D + 1.0E-14D) {
                assertQuatEquals(
                        jomlRotation,
                        gravityRotation,
                        1.0E-11D
                );
            }
        }
    }

    @Test
    void quaternionInterpolationMatchesJoml() {
        List<Quatd> samples = randomQuaternions(64);
        for (int i = 0; i + 1 < samples.size(); i += 2) {
            Quatd start = samples.get(i);
            Quatd end = samples.get(i + 1);
            for (double t : new double[]{0.0D, 0.25D, 0.5D, 0.8D, 1.0D}) {
                Quatd actual = start.slerp(end, t);
                Quaterniond expected = new Quaterniond(joml(start))
                        .slerp(joml(end), t)
                        .normalize();
                assertQuatEquals(expected, actual, 1.0E-11D);
            }
        }
    }

    @Test
    void frameQuaternionConversionMatchesJomlRotation() {
        for (Quatd sample : randomQuaternions(128)) {
            OrthonormalFrame3d frame = BodyOrientation3d.frame(sample);
            Matrix3d matrix = new Matrix3d()
                    .setColumn(0, joml(frame.axisX()))
                    .setColumn(1, joml(frame.axisY()))
                    .setColumn(2, joml(frame.axisZ()));
            Quaterniond expected = matrix
                    .getNormalizedRotation(new Quaterniond())
                    .normalize();
            Quatd actual = BodyOrientation3d.quaternion(frame);

            assertQuatEquals(expected, actual, 1.0E-12D);
        }
    }

    @Test
    void angularDistanceMatchesJomlDefinition() {
        List<Quatd> samples = randomQuaternions(128);
        for (int i = 0; i + 1 < samples.size(); i += 2) {
            Quatd first = samples.get(i);
            Quatd second = samples.get(i + 1);
            Quaterniond delta = new Quaterniond(joml(first))
                    .conjugate()
                    .mul(joml(second))
                    .normalize();
            double expected = 2.0D * Math.atan2(
                    Math.sqrt(
                            delta.x * delta.x
                                    + delta.y * delta.y
                                    + delta.z * delta.z
                    ),
                    Math.abs(delta.w)
            );

            assertEquals(expected,
                    Quatd.angularDistance(first, second),
                    1.0E-12D);
        }
    }

    private static List<Vec3d> randomVectors(int count) {
        Random random = new Random(SEED);
        List<Vec3d> result = new ArrayList<>(count);
        while (result.size() < count) {
            Vec3d candidate = new Vec3d(
                    random.nextGaussian() * 10.0D,
                    random.nextGaussian() * 10.0D,
                    random.nextGaussian() * 10.0D
            );
            if (candidate.lengthSquared() > 1.0E-12D
                    && candidate.isFinite()) {
                result.add(candidate);
            }
        }
        return result;
    }

    private static List<Quatd> randomQuaternions(int count) {
        Random random = new Random(SEED ^ 0x00C0_FFEE_D00D_1234L);
        List<Quatd> result = new ArrayList<>(count);
        while (result.size() < count) {
            Quatd candidate = new Quatd(
                    random.nextGaussian(),
                    random.nextGaussian(),
                    random.nextGaussian(),
                    random.nextGaussian()
            );
            if (candidate.lengthSquared() > 1.0E-6D) {
                result.add(candidate.normalized());
            }
        }
        return result;
    }

    private static Vector3d joml(Vec3d value) {
        return new Vector3d(value.x(), value.y(), value.z());
    }

    private static Quaterniond joml(Quatd value) {
        return new Quaterniond(value.x(), value.y(), value.z(), value.w());
    }

    private static void assertVecEquals(
            Vector3d expected,
            Vec3d actual,
            double tolerance
    ) {
        assertEquals(expected.x, actual.x(), tolerance);
        assertEquals(expected.y, actual.y(), tolerance);
        assertEquals(expected.z, actual.z(), tolerance);
    }

    private static void assertQuatEquals(
            Quaterniond expected,
            Quatd actual,
            double tolerance
    ) {
        double direct = Math.max(
                Math.max(Math.abs(expected.x - actual.x()),
                        Math.abs(expected.y - actual.y())),
                Math.max(Math.abs(expected.z - actual.z()),
                        Math.abs(expected.w - actual.w()))
        );
        double negated = Math.max(
                Math.max(Math.abs(expected.x + actual.x()),
                        Math.abs(expected.y + actual.y())),
                Math.max(Math.abs(expected.z + actual.z()),
                        Math.abs(expected.w + actual.w()))
        );
        assertTrue(Math.min(direct, negated) <= tolerance,
                "quaternion mismatch: expected=" + expected + " actual=" + actual);
    }
}
