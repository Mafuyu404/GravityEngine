package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.api.math.Vec3d;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FallingBlockDirectionTest {
    @Test
    void tiesAreDeterministicButSubConfidenceResultantsHaveNoDirection() {
        for (int x : new int[]{-1, 1}) {
            for (int y : new int[]{-1, 1}) {
                for (int z : new int[]{-1, 1}) {
                    assertEquals(
                            y < 0
                                    ? Direction.DOWN
                                    : Direction.UP,
                            FallingBlockStartIntegration
                                    .dominantDown(
                                            new Vec3d(
                                                    x,
                                                    y,
                                                    z
                                            )
                                    )
                    );

                    assertEquals(
                            x < 0
                                    ? Direction.WEST
                                    : Direction.EAST,
                            FallingBlockStartIntegration
                                    .dominantDown(
                                            new Vec3d(
                                                    x,
                                                    0,
                                                    z
                                            )
                                    )
                    );
                }
            }
        }

        assertNull(
                FallingBlockStartIntegration
                        .dominantDown(
                                Vec3d.ZERO
                        )
        );

        assertNull(
                FallingBlockStartIntegration
                        .dominantDown(
                                new Vec3d(
                                        Double.MIN_VALUE,
                                        0,
                                        0
                                )
                        )
        );

        assertNull(
                FallingBlockStartIntegration
                        .dominantDown(
                                new Vec3d(
                                        cc.sighs.gravityengine.gravity.GravityFrame
                                                .DIRECTION_ATTACH_STRENGTH
                                                * 0.5D,
                                        0,
                                        0
                                )
                        )
        );

        assertEquals(
                Direction.EAST,
                FallingBlockStartIntegration
                        .dominantDown(
                                new Vec3d(
                                        cc.sighs.gravityengine.gravity.GravityFrame
                                                .DIRECTION_ATTACH_STRENGTH,
                                        0,
                                        0
                                )
                        )
        );
    }
}
