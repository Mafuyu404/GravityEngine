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
    void oldSnapshotDecodesCollisionAuthorityFromCustomFlag() {
        var tag = new net.minecraft.nbt.CompoundTag();

        tag.putLong("Tick", 12);
        tag.putByte(
                "Down",
                (byte) Direction.EAST.get3DDataValue()
        );
        tag.putDouble("Magnitude", .04D);
        tag.putBoolean("Custom", true);

        var decoded =
                FallingBlockBallisticSnapshot.decode(tag);

        assertTrue(decoded.customAcceleration());
        assertTrue(decoded.engineCollision());
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