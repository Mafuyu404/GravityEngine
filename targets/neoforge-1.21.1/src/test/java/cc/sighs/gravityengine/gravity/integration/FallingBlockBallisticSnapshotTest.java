package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.api.math.Vec3d;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FallingBlockBallisticSnapshotTest {

    @Test
    void sixDirectionsRoundTripWithoutPrecisionLoss() {
        for (Direction down : Direction.values()) {
            var snapshot =
                    new FallingBlockBallisticSnapshot(
                            42,
                            down,
                            .040000000001D,
                            true,
                            true
                    );

            assertEquals(
                    snapshot,
                    FallingBlockBallisticSnapshot.decode(
                            snapshot.encode()
                    )
            );

            var axis = down.getNormal();

            assertEquals(
                    new Vec3d(
                            axis.getX(),
                            axis.getY(),
                            axis.getZ()
                    ).multiply(snapshot.magnitude()),
                    snapshot.acceleration()
            );

            /*
             * sampleTick is metadata, not physics identity.
             */
            assertTrue(
                    snapshot.samePhysics(
                            new FallingBlockBallisticSnapshot(
                                    1000,
                                    down,
                                    snapshot.magnitude(),
                                    true,
                                    true
                            )
                    )
            );

            assertFalse(
                    snapshot.samePhysics(
                            new FallingBlockBallisticSnapshot(
                                    42,
                                    down,
                                    .04D,
                                    true,
                                    true
                            )
                    )
            );
        }
    }

    @Test
    void accelerationAndCollisionAuthorityAreIndependent() {
        var pureVanilla =
                FallingBlockBallisticSnapshot.nativeGravity(
                        10,
                        false
                );

        var nativeWithEngineCollision =
                FallingBlockBallisticSnapshot.nativeGravity(
                        10,
                        true
                );

        assertFalse(
                pureVanilla.customAcceleration()
        );
        assertFalse(
                pureVanilla.engineCollision()
        );

        assertFalse(
                nativeWithEngineCollision.customAcceleration()
        );
        assertTrue(
                nativeWithEngineCollision.engineCollision()
        );

        assertEquals(
                new Vec3d(0, -.04D, 0),
                pureVanilla.acceleration()
        );

        assertEquals(
                pureVanilla.acceleration(),
                nativeWithEngineCollision.acceleration()
        );

        assertFalse(
                pureVanilla.samePhysics(
                        nativeWithEngineCollision
                ),
                "collision authority must participate in sync change detection"
        );
    }

    @Test
    void zeroCustomAccelerationRemainsCustomAuthority() {
        var zero =
                new FallingBlockBallisticSnapshot(
                        5,
                        Direction.DOWN,
                        0.0D,
                        true,
                        true
                );

        assertEquals(
                Vec3d.ZERO,
                zero.acceleration()
        );

        var decoded =
                FallingBlockBallisticSnapshot.decode(
                        zero.encode()
                );

        assertTrue(
                decoded.customAcceleration()
        );
        assertTrue(
                decoded.engineCollision()
        );
    }

    @Test
    void allAuthorityCombinationsRoundTripIndependently() {
        for (boolean acceleration : new boolean[]{false, true}) {
            for (boolean collision : new boolean[]{false, true}) {
                var snapshot = new FallingBlockBallisticSnapshot(
                        12, Direction.EAST, .04D, acceleration, collision);
                assertEquals(snapshot, FallingBlockBallisticSnapshot.decode(snapshot.encode()));
            }
        }
    }

    @Test
    void missingOrWronglyTypedFieldsAreRejected() {
        var valid = new FallingBlockBallisticSnapshot(12, Direction.EAST, .04D, true, false).encode();
        for (String key : new String[]{"Tick", "Down", "Magnitude", "Custom", "EngineCollision"}) {
            var missing = valid.copy();
            missing.remove(key);
            assertThrows(IllegalArgumentException.class, () -> FallingBlockBallisticSnapshot.decode(missing), key);
            var wrongType = valid.copy();
            wrongType.putString(key, "invalid");
            assertThrows(IllegalArgumentException.class, () -> FallingBlockBallisticSnapshot.decode(wrongType), key);
        }
        for (byte axis : new byte[]{-1, 6}) {
            var invalid = valid.copy();
            invalid.putByte("Down", axis);
            assertThrows(IllegalArgumentException.class, () -> FallingBlockBallisticSnapshot.decode(invalid));
        }
        for (double magnitude : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            var invalid = valid.copy();
            invalid.putDouble("Magnitude", magnitude);
            assertThrows(IllegalArgumentException.class, () -> FallingBlockBallisticSnapshot.decode(invalid));
        }
    }

    @Test
    void emptySnapshotIsStillUninitialized() {
        assertNull(
                FallingBlockBallisticSnapshot.decode(
                        new net.minecraft.nbt.CompoundTag()
                )
        );
    }

    @Test
    void invalidAccelerationIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new FallingBlockBallisticSnapshot(
                        0,
                        Direction.DOWN,
                        Double.NaN,
                        true,
                        true
                )
        );
    }
}
