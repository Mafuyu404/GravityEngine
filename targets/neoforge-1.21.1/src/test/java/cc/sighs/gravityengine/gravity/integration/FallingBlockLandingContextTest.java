package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import net.minecraft.core.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FallingBlockLandingContextTest {

    private GravitySupportContact contact(
            Vec3d normal,
            boolean dynamic
    ) {
        var identity =
                new SupportFaceIdentity(
                        dynamic
                                ? null
                                : new CellPos(
                                12,
                                70,
                                -9
                        ),
                        dynamic
                                ? null
                                : new Aabb3d(
                                12,
                                70,
                                -9,
                                13,
                                71,
                                -8
                        ),
                        1,
                        SupportFaceIdentity.NO_FACE
                );

        return new GravitySupportContact(
                normal,
                Vec3d.ZERO,
                Vec3d.ZERO,
                GravitySupportContact
                        .SupportGeometryKind
                        .SWEEP_CONTACT,
                identity
        );
    }

    @Test
    void sixFacesProduceInstallableStaticLanding() {
        for (Direction down : Direction.values()) {
            var n = down.getNormal();

            var vector =
                    new Vec3d(
                            n.getX(),
                            n.getY(),
                            n.getZ()
                    );

            var context =
                    FallingBlockPlacementIntegration.fromContact(
                            contact(
                                    vector.multiply(-1.0D),
                                    false
                            ),
                            GravityFrame.fromDown(
                                    vector,
                                    0.04D
                            )
                    );

            assertNotNull(context);
            assertTrue(context.landed());
            assertTrue(context.installable());
            assertEquals(
                    down,
                    context.down()
            );
        }
    }

    @Test
    void dynamicSupportLandsButCannotInstallBlock() {
        var frame =
                GravityFrame.fromDown(
                        Vec3d.X,
                        0.04D
                );

        var context =
                FallingBlockPlacementIntegration.fromContact(
                        contact(
                                Vec3d.X.multiply(-1.0D),
                                true
                        ),
                        frame
                );

        assertNotNull(context);
        assertTrue(context.landed());
        assertFalse(context.installable());
        assertEquals(
                Direction.EAST,
                context.down()
        );
    }

    @Test
    void unrelatedNormalsStillDoNotBecomeLanding() {
        var frame =
                GravityFrame.fromDown(
                        Vec3d.X,
                        0.04D
                );

        assertNull(
                FallingBlockPlacementIntegration.fromContact(
                        contact(
                                Vec3d.Y,
                                false
                        ),
                        frame
                )
        );

        assertNull(
                FallingBlockPlacementIntegration.fromContact(
                        contact(
                                Vec3d.X,
                                false
                        ),
                        frame
                )
        );

        assertNull(
                FallingBlockPlacementIntegration.fromContact(
                        contact(
                                Vec3d.X.multiply(-1.0D),
                                false
                        ),
                        GravityFrame.fromDown(
                                Vec3d.X,
                                0.0D
                        )
                )
        );
    }

    @Test
    void floatingPointContactNormalUsesTolerance() {
        var context =
                FallingBlockPlacementIntegration.fromContact(
                        contact(
                                new Vec3d(
                                        -1.0D,
                                        1.0E-8D,
                                        0.0D
                                ).normalized(),
                                false
                        ),
                        GravityFrame.fromDown(
                                Vec3d.X,
                                0.04D
                        )
                );

        assertNotNull(context);
        assertTrue(context.installable());
    }
}