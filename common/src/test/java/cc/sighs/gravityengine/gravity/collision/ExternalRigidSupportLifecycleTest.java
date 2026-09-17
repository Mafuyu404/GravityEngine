package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.collision.provider.ExternalRigidCollisionProvider;
import cc.sighs.gravityengine.gravity.collision.provider.ExternalRigidCollisionQuery;
import cc.sighs.gravityengine.gravity.collision.provider.RigidCollisionPublicationRegistry;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0 lifecycle regression: a generic non-Minecraft external provider can
 * create endpoint support and reacquire that same obstacle next tick through
 * the engine publication registry.
 */
class ExternalRigidSupportLifecycleTest {
    private static final Vec3d UP =
            new Vec3d(0.0D, 1.0D, 0.0D);
    private static final long SOURCE = 501L;
    private static final long PRIMITIVE = 7L;
    private static final long EPOCH = 3L;

    @Test
    void externalMovingObstacleIsReacquiredAcrossTicks() {
        Object scope = new Object();
        MutableProvider provider = new MutableProvider(
                obstacle(
                        100L,
                        1L,
                        EPOCH,
                        Vec3d.ZERO,
                        Vec3d.ZERO,
                        Vec3d.ZERO
                )
        );
        RigidCollisionPublicationRegistry.register(scope, provider);

        try {
            KinematicStepContext firstTime =
                    new KinematicStepContext(100L, 1.0D, 5L);
            CollisionSceneBuilder builder =
                    RigidCollisionPublicationRegistry.capture(
                            scope,
                            query(firstTime)
                    );
            DynamicCollisionObstacleSnapshot first =
                    builder.build().dynamicObstacles().get(0);
            CapturedCollisionScene firstScene = scene(
                    100L,
                    5L,
                    first
            );

            PersistentSupportState support =
                    PersistentSupportState.from(
                            witness(first, 100L, 5L),
                            firstScene
                    ).orElseThrow();
            provider.current = obstacle(
                    101L,
                    2L,
                    EPOCH,
                    Vec3d.ZERO,
                    new Vec3d(1.0D, 0.0D, 0.0D),
                    Vec3d.ZERO
            );

            KinematicStepContext secondTime =
                    new KinematicStepContext(101L, 1.0D, 6L);
            SupportTransport transport =
                    RigidSupportTransportPreflight.preflight(
                            support,
                            scope,
                            secondTime
                    ).orElseThrow();

            assertEquals(
                    new Vec3d(1.0D, 0.0D, 0.0D),
                    transport.displacement()
            );
            assertEquals(
                    new Vec3d(1.0D, 0.0D, 0.0D),
                    transport.surfaceVelocity()
            );

            DynamicCollisionObstacleSnapshot resolved =
                    RigidCollisionPublicationRegistry.resolve(
                            scope,
                            support.identity().obstacleIdentity(),
                            secondTime
                    ).orElseThrow();
            assertEquals(
                    resolved,
                    scene(101L, 6L, resolved)
                            .dynamicObstacle(support.identity().obstacleIdentity())
                            .orElseThrow()
            );
        } finally {
            RigidCollisionPublicationRegistry.clear(scope);
        }
    }

    @Test
    void rotatingExternalObstaclePreservesTheLocalMaterialAnchor() {
        Object scope = new Object();
        MutableProvider provider = new MutableProvider(
                obstacle(
                        100L,
                        1L,
                        EPOCH,
                        Vec3d.ZERO,
                        Vec3d.ZERO,
                        Vec3d.ZERO
                )
        );
        RigidCollisionPublicationRegistry.register(scope, provider);

        try {
            KinematicStepContext firstTime =
                    new KinematicStepContext(100L, 1.0D, 5L);
            DynamicCollisionObstacleSnapshot first =
                    RigidCollisionPublicationRegistry
                            .capture(scope, query(firstTime))
                            .build()
                            .dynamicObstacles()
                            .get(0);
            CapturedCollisionScene firstScene = scene(
                    100L,
                    5L,
                    first
            );
            PersistentSupportState support =
                    PersistentSupportState.from(
                            witness(
                                    first,
                                    new Vec3d(2.0D, 0.0D, 0.0D),
                                    100L,
                                    5L
                            ),
                            firstScene
                    ).orElseThrow();

            provider.current = obstacle(
                    101L,
                    2L,
                    EPOCH,
                    Vec3d.ZERO,
                    Vec3d.ZERO,
                    new Vec3d(0.0D, 0.0D, 1.0D)
            );

            SupportTransport transport =
                    RigidSupportTransportPreflight.preflight(
                            support,
                            scope,
                            new KinematicStepContext(101L, 1.0D, 6L)
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
            assertEquals(
                    new Vec3d(2.0D, 0.0D, 0.0D),
                    transport.trajectory()
                            .orElseThrow()
                            .localAnchor()
            );
        } finally {
            RigidCollisionPublicationRegistry.clear(scope);
        }
    }

    @Test
    void providerNamespaceSurvivesPersistentSupportAndRouting() {
        Object scope = new Object();
        MutableProvider providerA = new MutableProvider(
                "provider-a",
                obstacle(
                        100L,
                        1L,
                        EPOCH,
                        Vec3d.ZERO,
                        new Vec3d(1.0D, 0.0D, 0.0D),
                        Vec3d.ZERO
                )
        );
        MutableProvider providerB = new MutableProvider(
                "provider-b",
                obstacle(
                        100L,
                        1L,
                        EPOCH,
                        Vec3d.ZERO,
                        new Vec3d(2.0D, 0.0D, 0.0D),
                        Vec3d.ZERO
                )
        );
        RigidCollisionPublicationRegistry.register(scope, providerA);
        RigidCollisionPublicationRegistry.register(scope, providerB);

        try {
            var captured = RigidCollisionPublicationRegistry
                    .capture(
                            scope,
                            query(new KinematicStepContext(
                                    100L,
                                    1.0D,
                                    5L
                            ))
                    )
                    .build()
                    .dynamicObstacles();
            DynamicCollisionObstacleSnapshot first =
                    captured.stream()
                            .filter(obstacle ->
                                    obstacle.providerNamespace()
                                            .equals(
                                                    providerA.id()
                                            ))
                            .findFirst()
                            .orElseThrow();
            DynamicCollisionObstacleSnapshot other =
                    captured.stream()
                            .filter(obstacle ->
                                    obstacle.providerNamespace()
                                            .equals(
                                                    providerB.id()
                                            ))
                            .findFirst()
                            .orElseThrow();

            assertFalse(
                    RigidObstacleIdentity.of(first)
                            .equals(
                                    RigidObstacleIdentity.of(other)
                            )
            );

            PersistentSupportState support =
                    PersistentSupportState.from(
                            witness(first, 100L, 5L),
                            scene(100L, 5L, first)
                    ).orElseThrow();

            assertEquals(
                    providerA.id(),
                    support.identity().providerNamespace()
            );
            providerA.current = obstacle(
                    101L,
                    2L,
                    EPOCH,
                    new Vec3d(1.0D, 0.0D, 0.0D),
                    new Vec3d(1.0D, 0.0D, 0.0D),
                    Vec3d.ZERO
            );
            providerB.current = obstacle(
                    101L,
                    2L,
                    EPOCH,
                    new Vec3d(2.0D, 0.0D, 0.0D),
                    new Vec3d(2.0D, 0.0D, 0.0D),
                    Vec3d.ZERO
            );
            assertEquals(
                    new Vec3d(1.0D, 0.0D, 0.0D),
                    RigidSupportTransportPreflight.preflight(
                            support,
                            scope,
                            new KinematicStepContext(
                                    101L,
                                    1.0D,
                                    6L
                            )
                    ).orElseThrow().displacement()
            );
        } finally {
            RigidCollisionPublicationRegistry.clear(scope);
        }
    }

    @Test
    void missingProviderAndPrimitiveClearSupportSafely() {
        Object scope = new Object();
        MutableProvider provider = new MutableProvider(
                obstacle(
                        100L,
                        1L,
                        EPOCH,
                        Vec3d.ZERO,
                        Vec3d.ZERO,
                        Vec3d.ZERO
                )
        );
        RigidCollisionPublicationRegistry.register(scope, provider);

        try {
            KinematicStepContext firstTime =
                    new KinematicStepContext(100L, 1.0D, 5L);
            DynamicCollisionObstacleSnapshot first =
                    RigidCollisionPublicationRegistry
                            .capture(scope, query(firstTime))
                            .build()
                            .dynamicObstacles()
                            .get(0);
            PersistentSupportState support =
                    PersistentSupportState.from(
                            witness(first, 100L, 5L),
                            scene(100L, 5L, first)
                    ).orElseThrow();

            provider.current = obstacleWithPrimitive(
                    PRIMITIVE + 1L,
                    101L,
                    1L,
                    EPOCH,
                    Vec3d.ZERO,
                    Vec3d.ZERO,
                    Vec3d.ZERO
            );
            assertTrue(
                    RigidSupportTransportPreflight.preflight(
                            support,
                            scope,
                            new KinematicStepContext(101L, 1.0D, 6L)
                    ).isEmpty()
            );

            provider.current = obstacleWithPrimitive(
                    PRIMITIVE + 1L,
                    101L,
                    2L,
                    EPOCH,
                    Vec3d.ZERO,
                    new Vec3d(0.5D, 0.0D, 0.0D),
                    Vec3d.ZERO
            );
            assertTrue(
                    RigidSupportTransportPreflight.preflight(
                            support,
                            scope,
                            new KinematicStepContext(101L, 1.0D, 6L)
                    ).isEmpty()
            );

            RigidCollisionPublicationRegistry.unregister(
                    scope,
                    provider.id()
            );
            assertTrue(
                    RigidSupportTransportPreflight.preflight(
                            support,
                            scope,
                            new KinematicStepContext(101L, 1.0D, 6L)
                    ).isEmpty()
            );
        } finally {
            RigidCollisionPublicationRegistry.clear(scope);
        }
    }

    @Test
    void continuityEpochChangeInvalidatesOldSupport() {
        Object scope = new Object();
        MutableProvider provider = new MutableProvider(
                obstacle(
                        100L,
                        1L,
                        EPOCH,
                        Vec3d.ZERO,
                        Vec3d.ZERO,
                        Vec3d.ZERO
                )
        );
        RigidCollisionPublicationRegistry.register(scope, provider);

        try {
            DynamicCollisionObstacleSnapshot first =
                    RigidCollisionPublicationRegistry
                            .capture(
                                    scope,
                                    query(new KinematicStepContext(
                                            100L,
                                            1.0D,
                                            5L
                                    ))
                            )
                            .build()
                            .dynamicObstacles()
                            .get(0);
            PersistentSupportState support =
                    PersistentSupportState.from(
                            witness(first, 100L, 5L),
                            scene(100L, 5L, first)
                    ).orElseThrow();

            provider.current = obstacle(
                    101L,
                    1L,
                    EPOCH + 1L,
                    Vec3d.ZERO,
                    new Vec3d(1.0D, 0.0D, 0.0D),
                    Vec3d.ZERO
            );

            assertTrue(
                    RigidSupportTransportPreflight.preflight(
                            support,
                            scope,
                            new KinematicStepContext(101L, 1.0D, 6L)
                    ).isEmpty()
            );
            assertFalse(provider.current.motion().continuityEpoch()
                    == support.identity().continuityEpoch());
        } finally {
            RigidCollisionPublicationRegistry.clear(scope);
        }
    }

    @Test
    void staleReacquiredPublicationFailsClosed() {
        Object scope = new Object();
        MutableProvider provider = new MutableProvider(
                obstacle(
                        100L,
                        1L,
                        EPOCH,
                        Vec3d.ZERO,
                        Vec3d.ZERO,
                        Vec3d.ZERO
                )
        );
        RigidCollisionPublicationRegistry.register(scope, provider);

        try {
            DynamicCollisionObstacleSnapshot first =
                    RigidCollisionPublicationRegistry
                            .capture(
                                    scope,
                                    query(new KinematicStepContext(
                                            100L,
                                            1.0D,
                                            5L
                                    ))
                            )
                            .build()
                            .dynamicObstacles()
                            .get(0);
            PersistentSupportState support =
                    PersistentSupportState.from(
                            witness(first, 100L, 5L),
                            scene(100L, 5L, first)
                    ).orElseThrow();

            provider.current = obstacle(
                    102L,
                    2L,
                    EPOCH,
                    Vec3d.ZERO,
                    new Vec3d(1.0D, 0.0D, 0.0D),
                    Vec3d.ZERO
            );

            assertTrue(
                    RigidSupportTransportPreflight.preflight(
                            support,
                            scope,
                            new KinematicStepContext(101L, 1.0D, 6L)
                    ).isEmpty()
            );
        } finally {
            RigidCollisionPublicationRegistry.clear(scope);
        }
    }

    @Test
    void samePrimitiveWithValidContinuityRemainsSupported() {
        Object scope = new Object();
        MutableProvider provider = new MutableProvider(
                obstacle(
                        100L,
                        1L,
                        EPOCH,
                        Vec3d.ZERO,
                        Vec3d.ZERO,
                        Vec3d.ZERO
                )
        );
        RigidCollisionPublicationRegistry.register(scope, provider);

        try {
            DynamicCollisionObstacleSnapshot first =
                    RigidCollisionPublicationRegistry
                            .capture(
                                    scope,
                                    query(new KinematicStepContext(
                                            100L,
                                            1.0D,
                                            5L
                                    ))
                            )
                            .build()
                            .dynamicObstacles()
                            .get(0);
            PersistentSupportState support =
                    PersistentSupportState.from(
                            witness(first, 100L, 5L),
                            scene(100L, 5L, first)
                    ).orElseThrow();

            provider.current = obstacle(
                    101L,
                    1L,
                    EPOCH,
                    Vec3d.ZERO,
                    new Vec3d(0.25D, 0.0D, 0.0D),
                    Vec3d.ZERO
            );

            assertTrue(
                    RigidSupportTransportPreflight.preflight(
                            support,
                            scope,
                            new KinematicStepContext(101L, 1.0D, 6L)
                    ).isPresent()
            );
        } finally {
            RigidCollisionPublicationRegistry.clear(scope);
        }
    }

    private static ExternalRigidCollisionQuery query(
            KinematicStepContext time
    ) {
        Aabb3d bounds = new Aabb3d(
                -16.0D, -16.0D, -16.0D,
                16.0D, 16.0D, 16.0D
        );
        return new ExternalRigidCollisionQuery(
                bounds,
                DynamicEntityBroadphasePolicy.candidateQueryBounds(bounds),
                time
        );
    }

    private static DynamicCollisionObstacleSnapshot obstacle(
            long tick,
            long motionRevision,
            long continuityEpoch,
            Vec3d start,
            Vec3d translation,
            Vec3d angular
    ) {
        return obstacleWithPrimitive(
                PRIMITIVE,
                tick,
                motionRevision,
                continuityEpoch,
                start,
                translation,
                angular
        );
    }

    private static DynamicCollisionObstacleSnapshot obstacleWithPrimitive(
            long primitiveId,
            long tick,
            long motionRevision,
            long continuityEpoch,
            Vec3d start,
            Vec3d translation,
            Vec3d angular
    ) {
        return SceneFixtures.dynamicObstacle(
                SOURCE,
                primitiveId,
                continuityEpoch,
                start,
                3.0D,
                translation,
                angular,
                tick,
                motionRevision,
                1.0D
        );
    }

    private static CapturedCollisionScene scene(
            long tick,
            long revision,
            DynamicCollisionObstacleSnapshot obstacle
    ) {
        return SceneFixtures.scene(
                tick,
                revision,
                1.0D,
                List.of(),
                List.of(obstacle)
        );
    }

    private static EndpointSupportWitness witness(
            DynamicCollisionObstacleSnapshot obstacle,
            long gameTick,
            long sceneRevision
    ) {
        return witness(
                obstacle,
                new Vec3d(0.5D, 1.0D, 0.5D),
                gameTick,
                sceneRevision
        );
    }

    private static EndpointSupportWitness witness(
            DynamicCollisionObstacleSnapshot obstacle,
            Vec3d worldPoint,
            long gameTick,
            long sceneRevision
    ) {
        return new EndpointSupportWitness(
                new GravitySupportContact(
                        UP,
                        Vec3d.ZERO,
                        worldPoint,
                        GravitySupportContact.SupportGeometryKind
                                .REAL_OBSTACLE_FACE,
                        SupportFaceIdentity.dynamic(
                                new EntityObstacle(obstacle),
                                GravitySupportContact
                                        .blockFaceIndex(UP)
                        )
                ),
                gameTick,
                sceneRevision
        );
    }

    private static final class MutableProvider
            implements ExternalRigidCollisionProvider {
        private final String id;
        private DynamicCollisionObstacleSnapshot current;

        private MutableProvider(
                DynamicCollisionObstacleSnapshot current
        ) {
            this(
                    "external-moving-provider",
                    current
            );
        }

        private MutableProvider(
                String id,
                DynamicCollisionObstacleSnapshot current
        ) {
            this.id = id;
            this.current = current;
        }

        @Override
        public String id() {
            return id;
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
