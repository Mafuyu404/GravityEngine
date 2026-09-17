package cc.sighs.gravityengine.math;

import cc.sighs.gravityengine.api.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QuatdTest {
    private static final double TIGHT = 1.0E-14D;

    @Test
    void axisAngleNormalizesNonUnitAxis() {
        double angle = Math.PI * 0.5D;

        Quatd actual = Quatd.fromAxisAngle(
                new Vec3d(0.0D, 0.0D, 0.5D),
                angle
        );

        assertQuatEquals(
                Quatd.rotationZ(angle),
                actual,
                TIGHT
        );

        assertEquals(
                1.0D,
                actual.lengthSquared(),
                TIGHT
        );

        assertVecEquals(
                Vec3d.Y,
                actual.transform(Vec3d.X),
                TIGHT
        );
    }

    @Test
    void axisAngleRejectsDegenerateAndNonFiniteInputs() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Quatd.fromAxisAngle(
                        Vec3d.ZERO,
                        1.0D
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> Quatd.fromAxisAngle(
                        new Vec3d(Double.NaN, 0.0D, 1.0D),
                        1.0D
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> Quatd.fromAxisAngle(
                        Vec3d.X,
                        Double.NaN
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> Quatd.fromAxisAngle(
                        Vec3d.X,
                        Double.POSITIVE_INFINITY
                )
        );
    }

    @Test
    void identityLeavesVectorsUnchanged() {
        Vec3d value = new Vec3d(1.0D, -2.0D, 0.5D);

        assertEquals(value, Quatd.IDENTITY.transform(value));
        assertEquals(1.0D, Quatd.IDENTITY.lengthSquared());
    }

    @Test
    void normalizationRejectsDegenerateAndNonFiniteValues() {
        assertEquals(1.0D,
                new Quatd(1.0D, 2.0D, 3.0D, 4.0D)
                        .normalized().lengthSquared(),
                TIGHT);
        assertThrows(IllegalArgumentException.class,
                () -> new Quatd(0.0D, 0.0D, 0.0D, 0.0D).normalized());
        assertThrows(IllegalArgumentException.class,
                () -> new Quatd(Double.NaN, 0.0D, 0.0D, 1.0D).normalized());
    }

    @Test
    void conjugationInvertsAnActiveRotation() {
        Quatd rotation = Quatd.rotationZ(0.7D);
        Vec3d value = new Vec3d(1.0D, 2.0D, -3.0D);

        assertVecEquals(
                value,
                rotation.conjugate().transform(rotation.transform(value)),
                TIGHT
        );
    }

    @Test
    void multiplicationComposesInRightToLeftTransformOrder() {
        Quatd first = Quatd.rotationX(0.4D);
        Quatd second = Quatd.rotationY(-0.9D);
        Vec3d value = new Vec3d(2.0D, 1.0D, -0.5D);

        Vec3d composed = first.multiply(second).transform(value);
        Vec3d sequential = first.transform(second.transform(value));
        assertVecEquals(sequential, composed, TIGHT);
        assertEquals(1.0D, first.multiply(second).lengthSquared(), TIGHT);
    }

    @Test
    void basisVectorTransformsUseRightHandedAxisRotations() {
        assertVecEquals(new Vec3d(0.0D, 0.0D, 1.0D),
                Quatd.rotationX(Math.PI * 0.5D).transform(Vec3d.Y), TIGHT);
        assertVecEquals(new Vec3d(0.0D, 0.0D, -1.0D),
                Quatd.rotationY(Math.PI * 0.5D).transform(Vec3d.X), TIGHT);
        assertVecEquals(new Vec3d(0.0D, 1.0D, 0.0D),
                Quatd.rotationZ(Math.PI * 0.5D).transform(Vec3d.X), TIGHT);
    }

    @Test
    void axisAngleMatchesConvenienceRotations() {
        double angle = 1.1D;

        assertQuatEquals(
                Quatd.rotationX(angle),
                Quatd.fromAxisAngle(Vec3d.X, angle),
                TIGHT
        );
        assertQuatEquals(
                Quatd.rotationY(angle),
                Quatd.fromAxisAngle(Vec3d.Y, angle),
                TIGHT
        );
        assertQuatEquals(
                Quatd.rotationZ(angle),
                Quatd.fromAxisAngle(Vec3d.Z, angle),
                TIGHT
        );
    }

    @Test
    void rotationToCoversOrdinaryNearlyParallelAndAntiparallelCases() {
        Vec3d[] fromValues = {
                Vec3d.X,
                new Vec3d(1.0D, 0.2D, -0.4D).normalized(),
                new Vec3d(0.2D, -0.9D, 0.3D).normalized()
        };
        Vec3d[] toValues = {
                new Vec3d(0.3D, 0.7D, 0.2D).normalized(),
                new Vec3d(1.0D, 0.2000000001D, -0.3999999999D).normalized(),
                new Vec3d(-0.2D, 0.9D, -0.3D).normalized()
        };

        for (int i = 0; i < fromValues.length; i++) {
            Vec3d from = fromValues[i];
            Vec3d to = toValues[i];
            Vec3d rotated = Quatd.rotationTo(from, to).transform(from);
            assertVecEquals(to, rotated, 1.0E-10D);
        }
    }

    @Test
    void rotationToPreservesSmallRealOffsetsAroundAntiparallel() {
        double[] deviations = {
                1.0E-12D,
                1.0E-10D,
                1.0E-8D,
                1.0E-7D,
                5.0E-7D,
                1.0E-6D,
                1.4E-6D,
                2.0E-6D,
                1.0E-5D
        };

        Vec3d[] bases = {
                Vec3d.X,
                Vec3d.Y,
                new Vec3d(
                        0.2D,
                        -0.9D,
                        0.3D
                ).normalized()
        };

        for (Vec3d from : bases) {
            Vec3d tangent =
                    Math.abs(from.x()) < 0.75D
                            ? from.cross(Vec3d.X)
                                    .normalized()
                            : from.cross(Vec3d.Y)
                                    .normalized();

            for (double deviation : deviations) {
                Vec3d to =
                        from.negate()
                                .add(
                                        tangent.multiply(
                                                deviation
                                        )
                                )
                                .normalized();

                Quatd rotation =
                        Quatd.rotationTo(from, to);

                Vec3d actual =
                        rotation.transform(from);

                assertTrue(rotation.isFinite());
                assertEquals(
                        1.0D,
                        rotation.lengthSquared(),
                        1.0E-12D
                );

                assertVecEquals(
                        to,
                        actual,
                        1.0E-10D
                );
            }
        }
    }

    @Test
    void rotationToPreservesSmallRealOffsetsAroundParallel() {
        Vec3d from = Vec3d.Y;
        Vec3d tangent = Vec3d.X;

        for (double deviation : new double[]{
                1.0E-12D,
                1.0E-10D,
                1.0E-8D,
                1.0E-6D
        }) {
            Vec3d to =
                    from.add(
                            tangent.multiply(deviation)
                    ).normalized();

            Vec3d actual =
                    Quatd.rotationTo(from, to)
                            .transform(from);

            assertVecEquals(
                    to,
                    actual,
                    1.0E-10D
            );
        }
    }

    @Test
    void antiparallelRotationIsDeterministicAndMapsToTheTarget() {
        Vec3d from = new Vec3d(0.0D, 1.0D, 0.0D);
        Vec3d to = new Vec3d(0.0D, -1.0D, 0.0D);

        Quatd first = Quatd.rotationTo(from, to);
        Quatd second = Quatd.rotationTo(from, to);
        assertQuatEquals(first, second, 0.0D);
        assertVecEquals(to, first.transform(from), TIGHT);
    }

    @Test
    void slerpPreservesEndpointsAndIntermediateRotation() {
        Quatd start = Quatd.rotationY(-0.4D);
        Quatd end = Quatd.rotationY(1.7D);

        assertQuatEquals(start, start.slerp(end, 0.0D), TIGHT);
        assertQuatEquals(end, start.slerp(end, 1.0D), TIGHT);

        Quatd middle = start.slerp(end, 0.5D).normalized();
        Vec3d rotated = middle.transform(Vec3d.X);
        assertEquals(1.0D, rotated.lengthSquared(), TIGHT);
        assertTrue(Quatd.angularDistance(start, middle) > 0.0D);
        assertTrue(Quatd.angularDistance(middle, end) > 0.0D);
    }

    @Test
    void oppositeSignsRepresentTheSameRotation() {
        Quatd quaternion = Quatd.rotationZ(1.25D);
        Quatd negated = new Quatd(
                -quaternion.x(),
                -quaternion.y(),
                -quaternion.z(),
                -quaternion.w()
        );
        Vec3d value = new Vec3d(1.0D, -0.25D, 2.0D);

        assertVecEquals(
                quaternion.transform(value),
                negated.transform(value),
                TIGHT
        );
        assertEquals(0.0D,
                Quatd.angularDistance(quaternion, negated),
                1.0E-15D);
    }

    @Test
    void angularDistanceIsSignInvariantAndTracksTinyArcs() {
        Quatd identity = Quatd.IDENTITY;
        Quatd negatedIdentity = new Quatd(0.0D, 0.0D, 0.0D, -1.0D);
        Quatd tiny = Quatd.rotationX(1.0E-9D);

        assertEquals(0.0D,
                Quatd.angularDistance(identity, negatedIdentity),
                1.0E-15D);
        assertEquals(1.0E-9D,
                Quatd.angularDistance(identity, tiny),
                1.0E-15D);
    }

    private static void assertVecEquals(
            Vec3d expected,
            Vec3d actual,
            double tolerance
    ) {
        assertEquals(expected.x(), actual.x(), tolerance);
        assertEquals(expected.y(), actual.y(), tolerance);
        assertEquals(expected.z(), actual.z(), tolerance);
    }

    private static void assertQuatEquals(
            Quatd expected,
            Quatd actual,
            double tolerance
    ) {
        double direct = Math.max(
                Math.max(Math.abs(expected.x() - actual.x()),
                        Math.abs(expected.y() - actual.y())),
                Math.max(Math.abs(expected.z() - actual.z()),
                        Math.abs(expected.w() - actual.w()))
        );
        double negated = Math.max(
                Math.max(Math.abs(expected.x() + actual.x()),
                        Math.abs(expected.y() + actual.y())),
                Math.max(Math.abs(expected.z() + actual.z()),
                        Math.abs(expected.w() + actual.w()))
        );
        assertTrue(Math.min(direct, negated) <= tolerance);
    }

}
