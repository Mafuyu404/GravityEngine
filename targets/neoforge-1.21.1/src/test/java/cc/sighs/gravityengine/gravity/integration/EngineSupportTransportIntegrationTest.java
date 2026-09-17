package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.collision.CapturedCollisionScene;
import cc.sighs.gravityengine.gravity.collision.CollisionCaptureDomain;
import cc.sighs.gravityengine.gravity.collision.CollisionSceneBuilder;
import cc.sighs.gravityengine.gravity.collision.CollisionWorkBudget;
import cc.sighs.gravityengine.gravity.collision.CollisionWorkTracker;
import cc.sighs.gravityengine.gravity.collision.DynamicCollisionObstacleSnapshot;
import cc.sighs.gravityengine.gravity.collision.GravitySupportContact;
import cc.sighs.gravityengine.gravity.collision.ObbQueryContext;
import cc.sighs.gravityengine.gravity.collision.PersistentSupportState;
import cc.sighs.gravityengine.gravity.collision.RigidObstacleIdentity;
import cc.sighs.gravityengine.gravity.collision.SupportFaceIdentity;
import cc.sighs.gravityengine.gravity.collision.SupportTransport;
import cc.sighs.gravityengine.gravity.collision.WorldBorderCollisionSnapshot;
import cc.sighs.gravityengine.gravity.collision.provider.ExternalRigidCollisionProvider;
import cc.sighs.gravityengine.gravity.collision.provider.ExternalRigidCollisionQuery;
import cc.sighs.gravityengine.gravity.collision.provider.RigidCollisionPublicationRegistry;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.gravity.model.GravityOperationType;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d;
import cc.sighs.gravityengine.math.geometry.RigidPose;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineSupportTransportIntegrationTest {
    private static final Vec3d UP =
            new Vec3d(0.0D, 1.0D, 0.0D);

    @Test
    void preflightResolvesGenericExternalProviderWithoutEntityAssumptions() {
        Object scope = new Object();
        MutableExternalProvider provider =
                new MutableExternalProvider(
                        publication(
                                1000L,
                                7L,
                                1L,
                                3L,
                                Vec3d.ZERO,
                                new Vec3d(1.0D, 0.0D, 0.0D),
                                Vec3d.ZERO
                        )
                );
        RigidCollisionPublicationRegistry.register(
                scope,
                provider
        );
        DynamicCollisionObstacleSnapshot primitive =
                provider.current;
        RigidCollisionPublicationRegistry.recordPublication(
                scope,
                primitive,
                provider
        );

        SupportTransport transport =
                EngineSupportTransportPreflight.preflight(
                        support(1000L, 7L, 1L, 2L, Vec3d.ZERO),
                        scope,
                        100L,
                        1.0D
                ).orElseThrow();

        assertEquals(
                new Vec3d(1.0D, 0.0D, 0.0D),
                transport.displacement()
        );
    }

    @Test
    void continuityReplacementFailsClosed() {
        Object scope = new Object();
        MutableExternalProvider provider =
                new MutableExternalProvider(
                        publication(
                                42L,
                                1L,
                                4L,
                                9L,
                                Vec3d.ZERO,
                                new Vec3d(0.25D, 0.0D, 0.0D),
                                Vec3d.ZERO
                        )
                );
        RigidCollisionPublicationRegistry.register(
                scope,
                provider
        );
        RigidCollisionPublicationRegistry.recordPublication(
                scope,
                provider.current,
                provider
        );

        provider.current = publication(
                42L,
                1L,
                5L,
                10L,
                Vec3d.ZERO,
                new Vec3d(0.25D, 0.0D, 0.0D),
                Vec3d.ZERO
        );

        assertTrue(
                EngineSupportTransportPreflight.preflight(
                        support(42L, 1L, 4L, 9L, Vec3d.ZERO),
                        scope,
                        101L,
                        1.0D
                ).isEmpty()
        );
    }

    @Test
    void resolvedTransportIsStagedAndConsumedExactlyOnce() {
        Object scope = new Object();
        MutableExternalProvider provider =
                new MutableExternalProvider(
                        publication(
                                1000L,
                                7L,
                                1L,
                                4L,
                                Vec3d.ZERO,
                                new Vec3d(1.0D, 0.0D, 0.0D),
                                Vec3d.ZERO
                        )
                );
        RigidCollisionPublicationRegistry.register(
                scope,
                provider
        );
        RigidCollisionPublicationRegistry.recordPublication(
                scope,
                provider.current,
                provider
        );
        PersistentSupportState support =
                support(1000L, 7L, 1L, 2L, Vec3d.ZERO);
        SupportTransport preflight =
                EngineSupportTransportPreflight.preflight(
                        support,
                        scope,
                        100L,
                        1.0D
                ).orElseThrow();

        GravityOperationState state = new GravityOperationState();
        state.setPersistentSupportState(support);
        GravityOperationState.MoveScope move =
                state.openMove(
                        GravityFrame.DEFAULT,
                        100L,
                        GravityOperationType.MOVE
                );
        try {
            KinematicStepContext time =
                    KinematicStepContext.fullTick(100L, 7L);
            CapturedCollisionScene scene =
                    new CapturedCollisionScene(
                            CollisionCaptureDomain.around(
                                    new Aabb3d(
                                            -4.0D, -4.0D, -4.0D,
                                            4.0D, 4.0D, 4.0D
                                    ),
                                    4.0D
                            ),
                            100L,
                            7L,
                            time,
                            new CollisionWorkTracker(
                                    CollisionWorkBudget.defaults()
                            ),
                            new WorldBorderCollisionSnapshot(
                                    -1000.0D,
                                    -1000.0D,
                                    1000.0D,
                                    1000.0D
                            ),
                            List.of(),
                            List.of(provider.current),
                            List.of(),
                            List.of(),
                            Map.of(),
                            0
                    );
            state.setCollisionOperation(
                    new GravityOperationState
                            .CollisionOperationContext(
                            GravityFrame.DEFAULT,
                            time,
                            scene,
                            new ObbQueryContext(),
                            new CollisionWorkTracker(
                                    CollisionWorkBudget.defaults()
                            )
                    )
            );

            assertEquals(
                    Optional.of(preflight),
                    EngineSupportTransportIntegration
                            .stageResolvedTransport(
                                    state,
                                    Optional.of(preflight)
                            )
            );
            assertEquals(
                    preflight,
                    state.pendingEngineSupportTransport()
                            .orElseThrow()
            );
            assertEquals(
                    preflight,
                    state.consumeEngineSupportTransport()
                            .orElseThrow()
            );
            assertTrue(
                    state.consumeEngineSupportTransport().isEmpty()
            );
        } finally {
            move.close();
            RigidCollisionPublicationRegistry.clear(scope);
        }
    }

    @Test
    void rotatingSupportProducesTangentialTransportExactly() {
        Object scope = new Object();
        MutableExternalProvider provider =
                new MutableExternalProvider(
                        rotatingPublication()
                );
        RigidCollisionPublicationRegistry.register(
                scope,
                provider
        );
        RigidCollisionPublicationRegistry.recordPublication(
                scope,
                provider.current,
                provider
        );

        SupportTransport transport =
                EngineSupportTransportPreflight.preflight(
                        support(
                                9000L,
                                3L,
                                1L,
                                10L,
                                new Vec3d(2.0D, 0.0D, 0.0D)
                        ),
                        scope,
                        101L,
                        1.0D
                ).orElseThrow();

        assertEquals(
                4.0D * Math.sin(0.5D),
                transport.displacement().length(),
                1.0E-9D
        );
        assertEquals(
                2.0D,
                transport.surfaceVelocity().length(),
                1.0E-9D
        );
        assertTrue(transport.hasRotationalTrajectory());
    }

    private static PersistentSupportState support(
            long sourceId,
            long primitiveId,
            long continuityEpoch,
            long motionRevision,
            Vec3d localAnchor
    ) {
        RigidPose captured = new RigidPose(
                Vec3d.ZERO,
                OrthonormalFrame3d.IDENTITY
        );
        return new PersistentSupportState(
                SupportFaceIdentity.dynamic(
                        sourceId,
                        primitiveId,
                        continuityEpoch,
                        GravitySupportContact.blockFaceIndex(UP)
                ),
                localAnchor,
                UP,
                captured,
                motionRevision,
                5L,
                100L,
                GravitySupportContact.SupportGeometryKind
                        .REAL_OBSTACLE_FACE
        );
    }

    private static DynamicCollisionObstacleSnapshot publication(
            long sourceId,
            long primitiveId,
            long continuityEpoch,
            long motionRevision,
            Vec3d start,
            Vec3d translation,
            Vec3d angular
    ) {
        return new DynamicCollisionObstacleSnapshot(
                sourceId,
                primitiveId,
                OrientedBox.axisAligned(new Aabb3d(
                        -0.5D, -0.5D, -0.5D,
                        0.5D, 0.5D, 0.5D
                )),
                new cc.sighs.gravityengine.gravity.collision
                        .RigidMotionSnapshot(
                        new RigidPose(
                                start,
                                OrthonormalFrame3d.IDENTITY
                        ),
                        translation,
                        angular,
                        100L,
                        motionRevision,
                        continuityEpoch,
                        1.0D
                )
        );
    }

    private static DynamicCollisionObstacleSnapshot rotatingPublication() {
        return new DynamicCollisionObstacleSnapshot(
                9000L,
                3L,
                new OrientedBox(
                        new Vec3d(0.0D, -1.0D, 0.0D),
                        new Vec3d(3.0D, 1.0D, 3.0D),
                        OrthonormalFrame3d.IDENTITY
                ),
                new cc.sighs.gravityengine.gravity.collision
                        .RigidMotionSnapshot(
                        new RigidPose(
                                Vec3d.ZERO,
                                OrthonormalFrame3d.IDENTITY
                        ),
                        Vec3d.ZERO,
                        new Vec3d(0.0D, -1.0D, 0.0D),
                        101L,
                        11L,
                        1L,
                        1.0D
                )
        );
    }

    private static final class MutableExternalProvider
            implements ExternalRigidCollisionProvider {
        private DynamicCollisionObstacleSnapshot current;

        private MutableExternalProvider(
                DynamicCollisionObstacleSnapshot current
        ) {
            this.current = current;
        }

        @Override
        public String id() {
            return "test-external-provider";
        }

        @Override
        public void capture(
                ExternalRigidCollisionQuery query,
                cc.sighs.gravityengine.gravity.collision.RigidPublicationCollector output
        ) {
            output.addObstacle(this.current);
        }

        @Override
        public Optional<DynamicCollisionObstacleSnapshot> resolve(
                RigidObstacleIdentity identity,
                KinematicStepContext time
        ) {
            return identity.matchesProviderComponents(this.current)
                    ? Optional.of(this.current)
                    : Optional.empty();
        }
    }
}
