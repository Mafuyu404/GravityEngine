package cc.sighs.gravityengine.gravity.field;

import cc.sighs.gravityengine.api.field.GravityField;
import cc.sighs.gravityengine.api.field.GravityFieldQuery;
import cc.sighs.gravityengine.api.field.GravityFieldSample;
import cc.sighs.gravityengine.api.field.GravityFields;
import cc.sighs.gravityengine.api.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused numerical contract for the existing spherical-mass field.
 *
 * <p>The tests deliberately exercise the public factory and the current
 * enclosed-mass profile rather than reimplementing the kernel.</p>
 */
class SphericalGravityFieldRegressionTest {
    private static final Vec3d CENTER = new Vec3d(3.0D, -4.0D, 5.0D);
    private static final double MASS = 100.0D;
    private static final double SURFACE_RADIUS = 10.0D;
    private static final double GRAVITY_CONSTANT = 1.0D;

    @Test
    void uniformSphereIsFiniteAndZeroAtTheCenter() {
        GravityField field = uniformSphere(MASS, SURFACE_RADIUS);

        Vec3d acceleration = sampleAt(field, CENTER).acceleration();

        assertTrue(acceleration.isFinite());
        assertEquals(Vec3d.ZERO, acceleration);
    }

    @Test
    void uniformDensityInteriorIsLinearInRadius() {
        GravityField field = uniformSphere(MASS, SURFACE_RADIUS);

        double quarter = magnitude(field, SURFACE_RADIUS * 0.25D);
        double half = magnitude(field, SURFACE_RADIUS * 0.5D);
        double threeQuarters = magnitude(field, SURFACE_RADIUS * 0.75D);

        assertEquals(0.25D, quarter, 1.0E-12D);
        assertEquals(0.50D, half, 1.0E-12D);
        assertEquals(0.75D, threeQuarters, 1.0E-12D);
        assertEquals(2.0D, half / quarter, 1.0E-12D);
    }

    @Test
    void interiorAndExteriorSolutionsAreContinuousAtTheSurface() {
        GravityField field = uniformSphere(MASS, SURFACE_RADIUS);

        double inside = magnitude(field, SURFACE_RADIUS - 1.0E-9D);
        double surface = magnitude(field, SURFACE_RADIUS);
        double outside = magnitude(field, SURFACE_RADIUS + 1.0E-9D);

        assertEquals(surface, inside, 1.0E-7D);
        assertEquals(surface, outside, 1.0E-7D);
        assertEquals(1.0D, surface, 1.0E-12D);
    }

    @Test
    void exteriorFieldFollowsInverseSquareLaw() {
        GravityField field = uniformSphere(MASS, SURFACE_RADIUS);

        double near = magnitude(field, 20.0D);
        double far = magnitude(field, 40.0D);

        assertEquals(0.25D, near, 1.0E-12D);
        assertEquals(0.0625D, far, 1.0E-12D);
        assertEquals(4.0D, near / far, 1.0E-12D);
    }

    @Test
    void cutoffRadiusMatchesTheConfiguredMinimumAcceleration() {
        double minimum = 0.25D;
        double cutoff = GravityFields.sphericalCutoffRadius(
                GRAVITY_CONSTANT,
                MASS,
                SURFACE_RADIUS,
                minimum
        );
        GravityField field = uniformSphere(MASS, SURFACE_RADIUS);

        assertEquals(20.0D, cutoff, 1.0E-12D);
        assertEquals(minimum, magnitude(field, cutoff), 1.0E-12D);
        assertTrue(magnitude(field, cutoff - 1.0E-9D) > minimum);
        assertTrue(magnitude(field, cutoff + 1.0E-9D) < minimum);
    }

    @Test
    void cutoffNeverMovesInsideThePhysicalSurface() {
        double cutoff = GravityFields.sphericalCutoffRadius(
                GRAVITY_CONSTANT,
                MASS,
                SURFACE_RADIUS,
                10.0D
        );

        assertEquals(SURFACE_RADIUS, cutoff);
    }

    @Test
    void zeroMassCutoffIsThePhysicalSurface() {
        assertEquals(
                SURFACE_RADIUS,
                GravityFields.sphericalCutoffRadius(
                        GRAVITY_CONSTANT,
                        0.0D,
                        SURFACE_RADIUS,
                        0.25D
                )
        );
    }

    @Test
    void cutoffRejectsNonFiniteOrNonPositiveThresholds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> GravityFields.sphericalCutoffRadius(
                        GRAVITY_CONSTANT,
                        MASS,
                        SURFACE_RADIUS,
                        0.0D
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> GravityFields.sphericalCutoffRadius(
                        GRAVITY_CONSTANT,
                        MASS,
                        SURFACE_RADIUS,
                        Double.NaN
                )
        );
    }

    @Test
    void zeroMassAndZeroGravityAreFiniteZeroFields() {
        assertEquals(
                Vec3d.ZERO,
                sampleAt(uniformSphere(0.0D, SURFACE_RADIUS), CENTER)
                        .acceleration()
        );
        assertEquals(
                Vec3d.ZERO,
                sampleAt(
                        GravityFields.sphericalMass(
                                CENTER,
                                MASS,
                                GravityFields.uniformDensity(
                                        MASS,
                                        SURFACE_RADIUS
                                ),
                                SURFACE_RADIUS,
                                0.0D
                        ),
                        new Vec3d(100.0D, 100.0D, 100.0D)
                ).acceleration()
        );
    }

    @Test
    void negativeMassProducesFiniteRepulsiveAcceleration() {
        GravityField field = uniformSphere(-MASS, SURFACE_RADIUS);
        Vec3d point = CENTER.add(
                new Vec3d(20.0D, 0.0D, 0.0D)
        );

        Vec3d acceleration = sampleAt(field, point).acceleration();

        assertTrue(acceleration.isFinite());
        assertEquals(0.25D, acceleration.x(), 1.0E-12D);
        assertEquals(0.0D, acceleration.y(), 1.0E-12D);
        assertEquals(0.0D, acceleration.z(), 1.0E-12D);
    }

    @Test
    void extremeButRepresentableScalesRemainFinite() {
        GravityField tiny = uniformSphere(MASS, 1.0E-9D);
        GravityField large = uniformSphere(1.0E300D, 1.0D);

        assertTrue(
                sampleAt(tiny, CENTER.add(new Vec3d(1.0D, 0.0D, 0.0D)))
                        .acceleration()
                        .isFinite()
        );
        assertTrue(
                sampleAt(large, CENTER.add(new Vec3d(2.0D, 0.0D, 0.0D)))
                        .acceleration()
                        .isFinite()
        );
    }

    @Test
    void referenceDensityControlsTheEnclosedMassProfileNotTotalMass() {
        double meanDensity = GravityFields.uniformDensity(
                MASS,
                SURFACE_RADIUS
        );
        SphericalMassGravityField centered = (SphericalMassGravityField)
                GravityFields.sphericalMass(
                        CENTER,
                        MASS,
                        meanDensity,
                        SURFACE_RADIUS,
                        GRAVITY_CONSTANT
                );
        SphericalMassGravityField centrallyWeighted =
                (SphericalMassGravityField) GravityFields.sphericalMass(
                        CENTER,
                        MASS,
                        meanDensity * 2.0D,
                        SURFACE_RADIUS,
                        GRAVITY_CONSTANT
                );
        SphericalMassGravityField surfaceWeighted =
                (SphericalMassGravityField) GravityFields.sphericalMass(
                        CENTER,
                        MASS,
                        meanDensity * 0.5D,
                        SURFACE_RADIUS,
                        GRAVITY_CONSTANT
                );

        /*
         * The radius from uniformDensity() is the sphere's mean density.
         * Passing that as referenceDensity selects the uniform profile.
         */
        assertEquals(0.125D, centered.enclosedMassFraction(0.5D), 1.0E-12D);

        /*
         * referenceDensity is a profile shape parameter, not a pointwise
         * material density. A larger reference density means a relatively
         * steeper outward density decline, so more of the fixed total mass is
         * enclosed by the same radius.
         */
        assertTrue(
                centrallyWeighted.enclosedMassFraction(0.5D)
                        > centered.enclosedMassFraction(0.5D)
        );
        assertTrue(
                surfaceWeighted.enclosedMassFraction(0.5D)
                        < centered.enclosedMassFraction(0.5D)
        );
        assertEquals(
                1.0D,
                centered.enclosedMassFraction(1.0D),
                1.0E-12D
        );
        assertEquals(
                1.0D,
                centrallyWeighted.enclosedMassFraction(1.0D),
                1.0E-12D
        );
        assertEquals(
                1.0D,
                surfaceWeighted.enclosedMassFraction(1.0D),
                1.0E-12D
        );
        assertEquals(
                0.25D,
                magnitude(centrallyWeighted, 20.0D),
                1.0E-12D
        );
    }

    @Test
    void uniformDensityDerivationsRoundTrip() {
        double density = GravityFields.uniformDensity(
                MASS,
                SURFACE_RADIUS
        );
        double radius = GravityFields.uniformDensityRadius(
                MASS,
                density
        );

        assertEquals(SURFACE_RADIUS, radius, 1.0E-12D);
        assertEquals(
                density,
                MASS / ((4.0D / 3.0D) * Math.PI
                        * SURFACE_RADIUS * SURFACE_RADIUS
                        * SURFACE_RADIUS),
                1.0E-12D
        );
    }

    @Test
    void nonFiniteAndInvalidInputsFailAtTheBoundary() {
        assertThrows(
                IllegalArgumentException.class,
                () -> GravityFields.sphericalMass(
                        new Vec3d(Double.NaN, 0.0D, 0.0D),
                        MASS,
                        1.0D,
                        1.0D,
                        1.0D
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> GravityFields.sphericalMass(
                        CENTER,
                        Double.POSITIVE_INFINITY,
                        1.0D,
                        1.0D,
                        1.0D
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> GravityFields.sphericalMass(
                        CENTER,
                        MASS,
                        Double.POSITIVE_INFINITY,
                        1.0D,
                        1.0D
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> GravityFields.sphericalMass(
                        CENTER,
                        MASS,
                        1.0D,
                        0.0D,
                        1.0D
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> GravityFields.sphericalMass(
                        CENTER,
                        MASS,
                        1.0D,
                        1.0D,
                        -1.0D
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> GravityFields.sphericalMass(
                        CENTER,
                        Double.MAX_VALUE,
                        1.0D,
                        1.0D,
                        2.0D
                )
        );
    }

    private static GravityField uniformSphere(
            double mass,
            double surfaceRadius
    ) {
        return GravityFields.sphericalMass(
                CENTER,
                mass,
                mass == 0.0D
                        ? 1.0D
                        : GravityFields.uniformDensity(
                                Math.abs(mass),
                                surfaceRadius
                        ),
                surfaceRadius,
                GRAVITY_CONSTANT
        );
    }

    private static GravityFieldSample sampleAt(
            GravityField field,
            Vec3d position
    ) {
        return field.sample(GravityFieldQuery.at(position));
    }

    private static double magnitude(
            GravityField field,
            double radius
    ) {
        return sampleAt(
                field,
                CENTER.add(new Vec3d(radius, 0.0D, 0.0D))
        ).acceleration().length();
    }
}
