package cc.sighs.gravityengine.api.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Vec3dTest {
    private static final double TIGHT = 1.0E-15D;

    @Test
    void exactConstructionAndEqualityAreValueSemantics() {
        Vec3d value = new Vec3d(1.0D, -2.0D, 3.0D);

        assertEquals(1.0D, value.x());
        assertEquals(-2.0D, value.y());
        assertEquals(3.0D, value.z());
        assertEquals(new Vec3d(1.0D, -2.0D, 3.0D), value);
        assertNotEquals(new Vec3d(1.0D, -2.0D, 3.0D + 1.0E-12D), value);
    }

    @Test
    void addSubtractNegateAndScalePreserveComponentOrder() {
        Vec3d a = new Vec3d(1.0D, 2.0D, 3.0D);
        Vec3d b = new Vec3d(-4.0D, 5.0D, -6.0D);

        assertEquals(new Vec3d(-3.0D, 7.0D, -3.0D), a.add(b));
        assertEquals(new Vec3d(5.0D, -3.0D, 9.0D), a.subtract(b));
        assertEquals(new Vec3d(-1.0D, -2.0D, -3.0D), a.negate());
        assertEquals(new Vec3d(2.5D, 5.0D, 7.5D), a.multiply(2.5D));
        assertEquals(new Vec3d(3.0D, 4.0D, 5.0D),
                new Vec3d(1.0D, 2.0D, 1.0D)
                        .fma(2.0D, new Vec3d(1.0D, 1.0D, 2.0D)));
    }

    @Test
    void dotAndCrossUseRightHandedConvention() {
        Vec3d x = Vec3d.X;
        Vec3d y = Vec3d.Y;

        assertEquals(0.0D, x.dot(y));
        assertEquals(Vec3d.Z, x.cross(y));
        assertEquals(Vec3d.X, y.cross(Vec3d.Z));
        assertEquals(Vec3d.Y, Vec3d.Z.cross(Vec3d.X));
    }

    @Test
    void lengthsAndDistancesMatchPythagoreanValues() {
        Vec3d value = new Vec3d(3.0D, 4.0D, 12.0D);

        assertEquals(169.0D, value.lengthSquared());
        assertEquals(13.0D, value.length(), TIGHT);
        assertEquals(169.0D, value.distanceSquared(Vec3d.ZERO));
        assertEquals(25.0D,
                value.distanceSquared(new Vec3d(0.0D, 0.0D, 12.0D)));
        assertEquals(5.0D, value.distance(new Vec3d(0.0D, 0.0D, 12.0D)), TIGHT);
    }

    @Test
    void normalizationHandlesOrthogonalAndParallelInputs() {
        assertEquals(Vec3d.X, new Vec3d(5.0D, 0.0D, 0.0D).normalized());
        assertEquals(new Vec3d(0.0D, -1.0D, 0.0D),
                new Vec3d(0.0D, -3.0D, 0.0D).normalized());
        assertEquals(1.0D,
                new Vec3d(1.0D, 2.0D, 2.0D).normalized().lengthSquared(),
                TIGHT);
        assertThrows(IllegalArgumentException.class,
                () -> Vec3d.ZERO.normalized());
        assertThrows(IllegalArgumentException.class,
                () -> new Vec3d(1.0E-300D, 0.0D, 0.0D).normalized());
    }

    @Test
    void verySmallAndLargeFiniteVectorsRemainFinite() {
        Vec3d small = new Vec3d(1.0E-150D, -2.0E-150D, 3.0E-150D);
        Vec3d large = new Vec3d(1.0E150D, -2.0E150D, 3.0E150D);

        assertTrue(small.isFinite());
        assertTrue(large.isFinite());
        assertTrue(small.lengthSquared() > 0.0D);
        assertTrue(Double.isFinite(large.length()));
    }

    @Test
    void nonFiniteValuesAreExplicitlyInspectableButNotNormalizable() {
        Vec3d nan = new Vec3d(Double.NaN, 0.0D, 0.0D);
        Vec3d infinity = new Vec3d(Double.POSITIVE_INFINITY, 0.0D, 0.0D);

        assertFalse(nan.isFinite());
        assertFalse(infinity.isFinite());
        assertThrows(IllegalArgumentException.class, nan::normalized);
        assertThrows(IllegalArgumentException.class, infinity::normalized);
    }

    @Test
    void exactEqualityRemainsSeparateFromGeometricTolerance() {
        Vec3d first = new Vec3d(0.1D + 0.2D, 0.0D, 0.0D);
        Vec3d second = new Vec3d(0.3D, 0.0D, 0.0D);

        assertNotEquals(first, second);
        assertEquals(0.0D, first.distance(second), 1.0E-16D);
    }
}
