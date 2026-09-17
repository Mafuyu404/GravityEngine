package cc.sighs.gravityengine.gravity;

import cc.sighs.gravityengine.api.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GravityStateTest {
    @Test
    void defaultStateUsesCanonicalVanillaGravity() {
        assertEquals(
                GravityState.DEFAULT_DOWN,
                GravityState.DEFAULT.down()
        );
        assertEquals(
                GravityState.VANILLA_STRENGTH,
                GravityState.DEFAULT.strength()
        );
        assertTrue(
                GravityState.DEFAULT.isDefault()
        );
    }

    @Test
    void constructorNormalizesDirection() {
        GravityState state =
                new GravityState(
                        new Vec3d(
                                0.0D,
                                -4.0D,
                                0.0D
                        ),
                        0.12D
                );

        assertEquals(
                GravityState.DEFAULT_DOWN,
                state.down()
        );
        assertEquals(
                0.12D,
                state.strength()
        );
    }

    @Test
    void rejectsDegenerateAndNonFiniteDirection() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new GravityState(
                        Vec3d.ZERO,
                        0.08D
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new GravityState(
                        new Vec3d(
                                Double.NaN,
                                0.0D,
                                0.0D
                        ),
                        0.08D
                )
        );
    }

    @Test
    void rejectsNonFiniteStrength() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new GravityState(
                        Vec3d.Y.negate(),
                        Double.NaN
                )
        );
    }

    @Test
    void equalityIsExactButDirectionComparisonIsTolerant() {
        GravityState first =
                new GravityState(
                        new Vec3d(
                                0.0D,
                                -1.0D,
                                0.0D
                        ),
                        0.08D
                );

        GravityState close =
                new GravityState(
                        new Vec3d(
                                5.0E-5D,
                                -1.0D,
                                0.0D
                        ),
                        0.08D
                );

        assertNotEquals(first, close);
        assertTrue(first.sameDirection(close));
        assertTrue(first.sameSyncData(close));

        GravityState far =
                new GravityState(
                        new Vec3d(
                                2.0E-4D,
                                -1.0D,
                                0.0D
                        ),
                        0.08D
                );

        assertFalse(first.sameDirection(far));
    }
}
