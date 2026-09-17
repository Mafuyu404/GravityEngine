package cc.sighs.gravityengine.attitude.presentation;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.Quatd;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused pure-behaviour coverage for the loader-neutral body-attitude
 * interpolation used by every presentation path.
 */
class BodyAttitudeInterpolationTest {
    private static final double TOLERANCE = 1.0E-9D;

    @Test
    void identicalEndpointsAreANoOp() {
        Quatd value = Quatd.rotationZ(0.75D).normalized();

        for (double t : new double[]{0.0D, 0.25D, 0.5D, 1.0D}) {
            Quatd result = BodyAttitudeInterpolation.shortestArc(
                    value,
                    value,
                    t
            );
            assertEquals(
                    0.0D,
                    Quatd.angularDistance(value, result),
                    TOLERANCE
            );
        }
    }

    @Test
    void interpolationIsMonotonicFromStartToEnd() {
        Quatd start = Quatd.IDENTITY;
        Quatd end = Quatd.rotationY(Math.toRadians(120.0D));
        double previous = -1.0D;

        for (double t = 0.0D; t <= 1.0D + 1.0E-12D; t += 0.1D) {
            Quatd sample = BodyAttitudeInterpolation.shortestArc(
                    start,
                    end,
                    t
            );
            double travelled = Quatd.angularDistance(start, sample);
            assertTrue(travelled + TOLERANCE >= previous);
            previous = travelled;
        }

        assertEquals(
                Quatd.angularDistance(start, end),
                previous,
                1.0E-6D
        );
    }

    @Test
    void endpointsSnapInsteadOfExtrapolating() {
        Quatd start = Quatd.rotationX(0.4D);
        Quatd end = Quatd.rotationY(-0.9D);

        assertEquals(
                0.0D,
                Quatd.angularDistance(
                        start,
                        BodyAttitudeInterpolation.shortestArc(
                                start, end, 0.0D)
                ),
                TOLERANCE
        );
        assertEquals(
                0.0D,
                Quatd.angularDistance(
                        end.normalized(),
                        BodyAttitudeInterpolation.shortestArc(
                                start, end, 1.0D)
                ),
                TOLERANCE
        );
        assertEquals(
                0.0D,
                Quatd.angularDistance(
                        start.normalized(),
                        BodyAttitudeInterpolation.shortestArc(
                                start, end, Double.NaN)
                ),
                TOLERANCE
        );
    }

    @Test
    void degenerateInputsFallBackDeterministically() {
        Quatd start = Quatd.rotationZ(0.25D);

        assertEquals(
                0.0D,
                Quatd.angularDistance(
                        start.normalized(),
                        BodyAttitudeInterpolation.shortestArc(
                                start,
                                new Quatd(0.0D, 0.0D, 0.0D, 0.0D),
                                0.5D
                        )
                ),
                TOLERANCE
        );
        assertEquals(
                0.0D,
                Quatd.angularDistance(
                        Quatd.IDENTITY,
                        BodyAttitudeInterpolation.shortestArc(
                                new Quatd(0.0D, 0.0D, 0.0D, 0.0D),
                                start,
                                0.0D
                        )
                ),
                TOLERANCE
        );
        assertEquals(
                1.0D,
                BodyAttitudeInterpolation.shortestArc(
                        new Quatd(0.0D, 0.0D, 0.0D, 0.0D),
                        start,
                        0.5D
                ).lengthSquared(),
                TOLERANCE
        );
    }

    @Test
    void quaternionSignEquivalenceDoesNotChangeThePath() {
        Quatd start = Quatd.rotationX(0.3D);
        Quatd end = Quatd.rotationZ(1.1D);
        Quatd negated = new Quatd(
                -end.x(),
                -end.y(),
                -end.z(),
                -end.w()
        );

        for (double t : new double[]{0.2D, 0.5D, 0.8D}) {
            Quatd a = BodyAttitudeInterpolation.shortestArc(
                    start, end, t);
            Quatd b = BodyAttitudeInterpolation.shortestArc(
                    start, negated, t);
            assertEquals(
                    0.0D,
                    Quatd.angularDistance(a, b),
                    TOLERANCE
            );
        }
    }

    @Test
    void directionInterpolationStaysOnTheUnitSphere() {
        Vec3d start = Vec3d.X;
        Vec3d end = Vec3d.Z;

        assertEquals(start, BodyAttitudeInterpolation.direction(
                start, end, 0.0D));
        assertEquals(end, BodyAttitudeInterpolation.direction(
                start, end, 1.0D));
        for (double t = 0.0D; t <= 1.0D; t += 0.05D) {
            Vec3d sample = BodyAttitudeInterpolation.direction(
                    start, end, t);
            assertEquals(
                    1.0D,
                    sample.lengthSquared(),
                    TOLERANCE
            );
        }

        Vec3d opposed = BodyAttitudeInterpolation.direction(
                start,
                start.negate(),
                0.5D
        );
        assertEquals(1.0D, opposed.lengthSquared(), TOLERANCE);
        assertEquals(
                Math.PI * 0.5D,
                Math.acos(Math.max(
                        -1.0D,
                        Math.min(1.0D, start.dot(opposed))
                )),
                1.0E-6D
        );
    }
}
