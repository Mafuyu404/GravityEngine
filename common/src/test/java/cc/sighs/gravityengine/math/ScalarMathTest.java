package cc.sighs.gravityengine.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ScalarMathTest {
    @Test
    void clampsInsideAndOutsideRange() {
        assertEquals(0.25D, ScalarMath.clamp(0.25D, 0.0D, 1.0D));
        assertEquals(0.0D, ScalarMath.clamp(-1.0D, 0.0D, 1.0D));
        assertEquals(1.0D, ScalarMath.clamp(2.0D, 0.0D, 1.0D));
        assertEquals(3.0D, ScalarMath.clamp(5.0D, 3.0D, 3.0D));
    }

    @Test
    void rejectsInvalidBounds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ScalarMath.clamp(1.0D, 2.0D, 1.0D)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ScalarMath.clamp(1.0D, Double.NaN, 1.0D)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ScalarMath.clamp(1.0D, 0.0D, Double.NaN)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ScalarMath.clamp(0.0D, 0.0D, -0.0D)
        );
    }

    @Test
    void preservesNonFiniteValueSemantics() {
        assertTrue(Double.isNaN(ScalarMath.clamp(Double.NaN, 0.0D, 1.0D)));
        assertEquals(1.0D, ScalarMath.clamp(Double.POSITIVE_INFINITY, 0.0D, 1.0D));
        assertEquals(0.0D, ScalarMath.clamp(Double.NEGATIVE_INFINITY, 0.0D, 1.0D));
        assertEquals(
                1.0D,
                ScalarMath.clamp(1.0D, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
        );
    }

    @Test
    void preservesSignedZero() {
        assertEquals(
                Double.doubleToRawLongBits(-0.0D),
                Double.doubleToRawLongBits(ScalarMath.clamp(-0.0D, -0.0D, 0.0D))
        );
        assertEquals(
                Double.doubleToRawLongBits(0.0D),
                Double.doubleToRawLongBits(ScalarMath.clamp(0.0D, -0.0D, 0.0D))
        );
    }
}
