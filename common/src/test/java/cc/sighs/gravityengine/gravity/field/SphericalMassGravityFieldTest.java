package cc.sighs.gravityengine.gravity.field;

import cc.sighs.gravityengine.api.field.GravityField;
import cc.sighs.gravityengine.api.field.GravityFieldQuery;
import cc.sighs.gravityengine.api.field.GravityFields;
import cc.sighs.gravityengine.api.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SphericalMassGravityFieldTest {
    private static final double G = 6.5D;
    private static final double MASS = 1_000.0D;
    private static final double SURFACE_RADIUS = 10.0D;
    private static final double UNIFORM_REFERENCE_DENSITY =
            GravityFields.uniformDensity(
                    MASS,
                    SURFACE_RADIUS
            );

    @Test
    void uniformDensitySphereIsFiniteZeroAtCenterAndLinearInside() {
        GravityField field = uniformField();

        assertEquals(
                Vec3d.ZERO,
                sample(field, Vec3d.ZERO)
        );

        double atTwo = length(sample(field, new Vec3d(2.0D, 0.0D, 0.0D)));
        double atFour = length(sample(field, new Vec3d(4.0D, 0.0D, 0.0D)));
        double atEight = length(sample(field, new Vec3d(8.0D, 0.0D, 0.0D)));

        assertEquals(2.0D * atTwo, atFour, 1.0E-9D);
        assertEquals(4.0D * atTwo, atEight, 1.0E-9D);
    }

    @Test
    void uniformDensitySurfaceIsContinuousWithInverseSquareExterior() {
        GravityField field = uniformField();
        double surface = length(
                sample(
                        field,
                        new Vec3d(
                                SURFACE_RADIUS,
                                0.0D,
                                0.0D
                        )
                )
        );
        double inside = length(
                sample(
                        field,
                        new Vec3d(
                                SURFACE_RADIUS - 1.0E-6D,
                                0.0D,
                                0.0D
                        )
                )
        );
        double outside = length(
                sample(
                        field,
                        new Vec3d(
                                SURFACE_RADIUS + 1.0E-6D,
                                0.0D,
                                0.0D
                        )
                )
        );

        assertEquals(surface, inside, 1.0E-4D);
        assertEquals(surface, outside, 1.0E-4D);

        double firstR = 20.0D;
        double secondR = 40.0D;
        assertEquals(
                length(sample(field, new Vec3d(firstR, 0.0D, 0.0D)))
                        * firstR * firstR,
                length(sample(field, new Vec3d(secondR, 0.0D, 0.0D)))
                        * secondR * secondR,
                1.0E-7D
        );
    }

    @Test
    void cutoffRadiusMatchesTheConfiguredMinimumAcceleration() {
        double minimum = 0.125D;
        double cutoff = GravityFields.sphericalCutoffRadius(
                G,
                MASS,
                SURFACE_RADIUS,
                minimum
        );
        GravityField field = uniformField();

        assertEquals(
                Math.sqrt(G * MASS / minimum),
                cutoff,
                1.0E-9D
        );
        assertEquals(
                minimum,
                length(sample(field, new Vec3d(cutoff, 0.0D, 0.0D))),
                1.0E-7D
        );
        assertTrue(
                length(sample(
                        field,
                        new Vec3d(
                                cutoff - 1.0E-6D,
                                0.0D,
                                0.0D
                        )
                )) > minimum
        );
        assertTrue(
                length(sample(
                        field,
                        new Vec3d(
                                cutoff + 1.0E-6D,
                                0.0D,
                                0.0D
                        )
                )) < minimum
        );
    }

    @Test
    void finiteBehaviorCoversScaleAndSignedMass() {
        GravityField tiny = GravityFields.sphericalMass(
                Vec3d.ZERO,
                1.0D,
                1.0D,
                1.0E-6D,
                G
        );
        GravityField large = GravityFields.sphericalMass(
                Vec3d.ZERO,
                1.0E150D,
                1.0D,
                10.0D,
                G
        );
        GravityField zero = GravityFields.sphericalMass(
                Vec3d.ZERO,
                0.0D,
                1.0D,
                10.0D,
                G
        );
        GravityField repulsive = GravityFields.sphericalMass(
                Vec3d.ZERO,
                -MASS,
                UNIFORM_REFERENCE_DENSITY,
                SURFACE_RADIUS,
                G
        );

        assertTrue(allFinite(tiny));
        assertTrue(allFinite(large));
        assertEquals(Vec3d.ZERO, sample(zero, new Vec3d(5.0D, 0.0D, 0.0D)));
        assertEquals(Vec3d.ZERO, sample(zero, Vec3d.ZERO));

        Vec3d repulsiveSample =
                sample(repulsive, new Vec3d(20.0D, 0.0D, 0.0D));
        assertTrue(repulsiveSample.isFinite());
        assertTrue(repulsiveSample.x() > 0.0D);
        assertTrue(repulsiveSample.y() == 0.0D);
        assertTrue(repulsiveSample.z() == 0.0D);
    }

    @Test
    void nonFiniteArgumentsFailAtConstruction() {
        assertThrows(
                IllegalArgumentException.class,
                () -> GravityFields.sphericalMass(
                        new Vec3d(Double.NaN, 0.0D, 0.0D),
                        MASS,
                        UNIFORM_REFERENCE_DENSITY,
                        SURFACE_RADIUS,
                        G
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> GravityFields.sphericalMass(
                        Vec3d.ZERO,
                        Double.POSITIVE_INFINITY,
                        UNIFORM_REFERENCE_DENSITY,
                        SURFACE_RADIUS,
                        G
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> GravityFields.sphericalMass(
                        Vec3d.ZERO,
                        MASS,
                        Double.NaN,
                        SURFACE_RADIUS,
                        G
                )
        );
    }

    @Test
    void uniformDensityHelpersPreserveMeanDensitySemantics() {
        double density = GravityFields.uniformDensity(
                MASS,
                SURFACE_RADIUS
        );
        assertEquals(
                UNIFORM_REFERENCE_DENSITY,
                density,
                1.0E-12D
        );
        assertEquals(
                SURFACE_RADIUS,
                GravityFields.uniformDensityRadius(
                        MASS,
                        density
                ),
                1.0E-9D
        );

        /*
         * Reference density is a radial profile-shape parameter, not a
         * replacement for the configured total mass or physical surface
         * radius. Surface acceleration therefore remains the same while the
         * interior profile may differ.
         */
        GravityField nonUniform = GravityFields.sphericalMass(
                Vec3d.ZERO,
                MASS,
                density * 2.0D,
                SURFACE_RADIUS,
                G
        );
        assertEquals(
                length(sample(uniformField(), new Vec3d(
                        SURFACE_RADIUS,
                        0.0D,
                        0.0D
                ))),
                length(sample(nonUniform, new Vec3d(
                        SURFACE_RADIUS,
                        0.0D,
                        0.0D
                ))),
                1.0E-9D
        );
        assertNotEquals(
                length(sample(uniformField(), new Vec3d(
                        SURFACE_RADIUS * 0.5D,
                        0.0D,
                        0.0D
                ))),
                length(sample(nonUniform, new Vec3d(
                        SURFACE_RADIUS * 0.5D,
                        0.0D,
                        0.0D
                )))
        );
    }

    private static GravityField uniformField() {
        return GravityFields.sphericalMass(
                Vec3d.ZERO,
                MASS,
                UNIFORM_REFERENCE_DENSITY,
                SURFACE_RADIUS,
                G
        );
    }

    private static Vec3d sample(
            GravityField field,
            Vec3d position
    ) {
        return field.sample(
                GravityFieldQuery.at(position)
        ).acceleration();
    }

    private static double length(Vec3d value) {
        return value.length();
    }

    private static boolean allFinite(GravityField field) {
        return sample(field, Vec3d.ZERO).isFinite()
                && sample(
                field,
                new Vec3d(1.0E-9D, 0.0D, 0.0D)
        ).isFinite()
                && sample(
                field,
                new Vec3d(1.0E6D, 0.0D, 0.0D)
        ).isFinite();
    }
}
