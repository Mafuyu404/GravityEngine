package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.collision.provider.ExternalRigidCollisionProvider;
import cc.sighs.gravityengine.gravity.collision.provider.ExternalRigidCollisionQuery;
import cc.sighs.gravityengine.gravity.collision.provider.RigidCollisionPublicationRegistry;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.gravity.kinematic.SweepTimeWindow;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * External rigid providers publish through the same immutable engine scene
 * contract and receive the explicit engine discovery query.
 */
class ExternalRigidCollisionProviderTest {
    @Test
    void intersectingLargePrimitivesAreNotRequiredToFitInsideActorQuery() {
        for (Vec3d angular : List.of(Vec3d.ZERO, new Vec3d(0, 0.01, 0))) {
            Object scope = new Object();
            var large = SceneFixtures.dynamicObstacle(900L, 0L, 1L,
                    Vec3d.ZERO, 100.0D, Vec3d.ZERO, angular, 50L, 1L, 1.0D);
            RigidCollisionPublicationRegistry.register(scope, new RecordingProvider("large", large));
            try {
                var captured = RigidCollisionPublicationRegistry.capture(scope, query(TIME)).build();
                assertEquals(1, captured.dynamicObstacles().size());
                assertEquals(large.initialBounds(), captured.dynamicObstacles().get(0).initialBounds());
            } finally {
                RigidCollisionPublicationRegistry.clear(scope);
            }
        }
    }

    private static final KinematicStepContext TIME =
            new KinematicStepContext(50L, 1.0D, 7L);

    @Test
    void builderFreezesProviderObstaclesIntoAnImmutableScene() {
        DynamicCollisionObstacleSnapshot obstacle =
                obstacle(100L, 0L, 9L);
        CollisionSceneBuilder builder =
                new CollisionSceneBuilder(TIME);

        builder.addObstacle(obstacle);

        CollisionScene scene = builder.build();

        assertEquals(
                List.of(obstacle),
                scene.dynamicObstacles()
        );
        assertTrue(
                scene.time().gameTick() == 50L
        );
        assertThrows(
                UnsupportedOperationException.class,
                () -> scene.dynamicObstacles().add(obstacle)
        );
    }

    @Test
    void builderRejectsDuplicateEngineObstacleIdentity() {
        CollisionSceneBuilder builder =
                new CollisionSceneBuilder(TIME);
        builder.addObstacle(obstacle(100L, 0L, 9L));
        builder.addObstacle(obstacle(100L, 0L, 9L));

        assertThrows(
                CollisionSceneCoverageException.class,
                builder::build
        );
    }

    @Test
    void dynamicSceneQueryUsesTheFrozenProviderPublication() {
        CollisionSceneBuilder builder =
                new CollisionSceneBuilder(TIME);
        builder.addObstacle(obstacle(100L, 0L, 9L));
        CollisionScene scene = builder.build();

        OrientedBox body = OrientedBox.axisAligned(
                new Aabb3d(
                        2.0D, -0.5D, -0.5D,
                        3.0D, 0.5D, 0.5D
                )
        );

        List<CollisionObstacle> hits = scene.query(
                body,
                new Vec3d(-2.0D, 0.0D, 0.0D),
                SweepTimeWindow.full(scene.time())
        );

        assertEquals(1, hits.size());
        assertTrue(hits.get(0) instanceof EntityObstacle);
    }

    @Test
    void providersReceiveTheExactSpatialAndTimeQuery() {
        Object scope = new Object();
        ExternalRigidCollisionQuery query = query(TIME);
        RecordingProvider provider = new RecordingProvider(
                "query-provider",
                obstacle(10L, 0L, 1L)
        );
        RigidCollisionPublicationRegistry.register(scope, provider);

        try {
            RigidCollisionPublicationRegistry.capture(scope, query);
        } finally {
            RigidCollisionPublicationRegistry.clear(scope);
        }

        assertSame(query, provider.observed);
        assertEquals(50L, provider.observed.time().gameTick());
    }

    @Test
    void registryRunsProvidersInStableIdentityOrderAndPerScope() {
        Object scope = new Object();
        Object otherScope = new Object();
        List<String> calls = new ArrayList<>();

        RigidCollisionPublicationRegistry.register(
                scope,
                provider("z-provider", calls, 200L)
        );
        RigidCollisionPublicationRegistry.register(
                scope,
                provider("a-provider", calls, 100L)
        );
        RigidCollisionPublicationRegistry.register(
                otherScope,
                provider("other", calls, 300L)
        );

        try {
            CollisionSceneBuilder builder =
                    RigidCollisionPublicationRegistry.capture(
                            scope,
                            query(TIME)
                    );

            assertEquals(
                    List.of("a-provider", "z-provider"),
                    calls
            );
            assertEquals(
                    2,
                    builder.build().dynamicObstacles().size()
            );
        } finally {
            RigidCollisionPublicationRegistry.clear(scope);
            RigidCollisionPublicationRegistry.clear(otherScope);
        }

        assertTrue(
                RigidCollisionPublicationRegistry
                        .providers(scope)
                        .isEmpty()
        );
    }

    @Test
    void overlappingPrimitiveIdsRemainDistinctWhenSourcesDiffer() {
        Object scope = new Object();
        RigidCollisionPublicationRegistry.register(
                scope,
                provider("one", new ArrayList<>(), 100L)
        );
        RigidCollisionPublicationRegistry.register(
                scope,
                provider("two", new ArrayList<>(), 200L)
        );

        try {
            CollisionSceneBuilder builder =
                    RigidCollisionPublicationRegistry.capture(
                            scope,
                            query(TIME)
                    );
            assertEquals(
                    2,
                    builder.build().dynamicObstacles().size()
            );
        } finally {
            RigidCollisionPublicationRegistry.clear(scope);
        }
    }

    @Test
    void providersMayReuseTheSameSourceIdWithoutIdentityCollision() {
        Object scope = new Object();
        RigidCollisionPublicationRegistry.register(
                scope,
                provider("provider-a", new ArrayList<>(), 42L)
        );
        RigidCollisionPublicationRegistry.register(
                scope,
                provider("provider-b", new ArrayList<>(), 42L)
        );

        try {
            var obstacles =
                    RigidCollisionPublicationRegistry
                            .capture(scope, query(TIME))
                            .build()
                            .dynamicObstacles();

            assertEquals(2, obstacles.size());
            assertEquals(
                    List.of("provider-a", "provider-b"),
                    obstacles.stream()
                            .map(DynamicCollisionObstacleSnapshot
                                    ::providerNamespace)
                            .sorted()
                            .toList()
            );

            for (DynamicCollisionObstacleSnapshot obstacle
                    : obstacles) {
                assertTrue(
                        RigidCollisionPublicationRegistry.resolve(
                                scope,
                                RigidObstacleIdentity.of(obstacle),
                                TIME
                        ).orElseThrow()
                                .providerNamespace()
                                .equals(
                                        obstacle.providerNamespace()
                                )
                );
            }
            assertFalse(
                    RigidObstacleIdentity.of(obstacles.get(0))
                            .equals(
                                    RigidObstacleIdentity.of(
                                            obstacles.get(1)
                                    )
                            )
            );
        } finally {
            RigidCollisionPublicationRegistry.clear(scope);
        }
    }

    @Test
    void providerFailureAbortsCaptureWithProviderIdentity() {
        Object scope = new Object();
        RigidCollisionPublicationRegistry.register(
                scope,
                new ExternalRigidCollisionProvider() {
                    @Override
                    public String id() {
                        return "broken-provider";
                    }

                    @Override
                    public void capture(
                            ExternalRigidCollisionQuery query,
                            cc.sighs.gravityengine.gravity.collision.RigidPublicationCollector output
                    ) {
                        throw new IllegalStateException("boom");
                    }

                    @Override
                    public Optional<DynamicCollisionObstacleSnapshot> resolve(
                            RigidObstacleIdentity identity,
                            KinematicStepContext time
                    ) {
                        return Optional.empty();
                    }
                }
        );

        try {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> RigidCollisionPublicationRegistry.capture(
                            scope,
                            query(TIME)
                    )
            );
            assertEquals("boom", failure.getMessage());
            assertTrue(
                    failure.getSuppressed().length == 1
                            && failure.getSuppressed()[0]
                            .getMessage()
                            .contains("broken-provider")
            );
        } finally {
            RigidCollisionPublicationRegistry.clear(scope);
        }
    }

    @Test
    void registrationRejectsBlankProviderIdentity() {
        ExternalRigidCollisionProvider provider =
                new ExternalRigidCollisionProvider() {
                    @Override
                    public String id() {
                        return "  ";
                    }

                    @Override
                    public void capture(
                            ExternalRigidCollisionQuery query,
                            cc.sighs.gravityengine.gravity.collision.RigidPublicationCollector output
                    ) {
                        // no-op
                    }

                    @Override
                    public Optional<DynamicCollisionObstacleSnapshot> resolve(
                            RigidObstacleIdentity identity,
                            KinematicStepContext time
                    ) {
                        return Optional.empty();
                    }
                };

        assertThrows(
                IllegalArgumentException.class,
                () -> RigidCollisionPublicationRegistry.register(
                        new Object(),
                        provider
                )
        );
    }

    @Test
    void engineFailClosedExceptionTypeSurvivesProviderCapture() {
        Object scope = new Object();
        RigidCollisionPublicationRegistry.register(
                scope,
                new ExternalRigidCollisionProvider() {
                    @Override
                    public String id() {
                        return "coverage-provider";
                    }

                    @Override
                    public void capture(
                            ExternalRigidCollisionQuery query,
                            cc.sighs.gravityengine.gravity.collision.RigidPublicationCollector output
                    ) {
                        throw new CollisionSceneCoverageException(
                                "outside captured domain"
                        );
                    }

                    @Override
                    public Optional<DynamicCollisionObstacleSnapshot> resolve(
                            RigidObstacleIdentity identity,
                            KinematicStepContext time
                    ) {
                        return Optional.empty();
                    }
                }
        );

        try {
            assertThrows(
                    CollisionSceneCoverageException.class,
                    () -> RigidCollisionPublicationRegistry.capture(
                            scope,
                            query(TIME)
                    )
            );
        } finally {
            RigidCollisionPublicationRegistry.clear(scope);
        }
    }

    @Test
    void providerPublicationOutsideTheQueryIsRejected() {
        Object scope = new Object();
        RigidCollisionPublicationRegistry.register(
                scope,
                new ExternalRigidCollisionProvider() {
                    @Override
                    public String id() {
                        return "out-of-query";
                    }

                    @Override
                    public void capture(
                            ExternalRigidCollisionQuery query,
                            cc.sighs.gravityengine.gravity.collision.RigidPublicationCollector output
                    ) {
                        output.addObstacle(
                                SceneFixtures.dynamicObstacle(
                                        991L,
                                        0L,
                                        1L,
                                        new Vec3d(
                                                1000.0D,
                                                1000.0D,
                                                1000.0D
                                        ),
                                        1.0D,
                                        Vec3d.ZERO,
                                        Vec3d.ZERO,
                                        50L,
                                        1L,
                                        1.0D
                                )
                        );
                    }

                    @Override
                    public Optional<DynamicCollisionObstacleSnapshot> resolve(
                            RigidObstacleIdentity identity,
                            KinematicStepContext time
                    ) {
                        return Optional.empty();
                    }
                }
        );

        try {
            assertThrows(
                    CollisionSceneCoverageException.class,
                    () -> RigidCollisionPublicationRegistry.capture(
                            scope,
                            query(TIME)
                    )
            );
        } finally {
            RigidCollisionPublicationRegistry.clear(scope);
        }
    }

    @Test
    void nonFiniteRigidMotionIsRejectedAtConstruction() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RigidMotionSnapshot(
                        new cc.sighs.gravityengine.math.geometry.RigidPose(
                                Vec3d.ZERO,
                                cc.sighs.gravityengine.math.geometry
                                        .OrthonormalFrame3d.IDENTITY
                        ),
                        Double.NaN,
                        0.0D,
                        0.0D,
                        0.0D,
                        0.0D,
                        0.0D,
                        TIME.gameTick(),
                        1L,
                        1L,
                        TIME.intervalTicks()
                )
        );
    }

    private static ExternalRigidCollisionQuery query(
            KinematicStepContext time
    ) {
        Aabb3d bounds = new Aabb3d(
                -8.0D, -8.0D, -8.0D,
                8.0D, 8.0D, 8.0D
        );
        return new ExternalRigidCollisionQuery(
                bounds,
                DynamicEntityBroadphasePolicy.candidateQueryBounds(bounds),
                time
        );
    }

    private static ExternalRigidCollisionProvider provider(
            String id,
            List<String> calls,
            long sourceId
    ) {
        return new ExternalRigidCollisionProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public void capture(
                    ExternalRigidCollisionQuery query,
                    cc.sighs.gravityengine.gravity.collision.RigidPublicationCollector output
            ) {
                calls.add(id);
                output.addObstacle(
                        obstacle(sourceId, 0L, 1L)
                );
            }

            @Override
            public Optional<DynamicCollisionObstacleSnapshot> resolve(
                    RigidObstacleIdentity identity,
                    KinematicStepContext time
            ) {
                return identity.sourceId() == sourceId
                        ? Optional.of(obstacle(sourceId, 0L, 1L))
                        : Optional.empty();
            }
        };
    }

    private static DynamicCollisionObstacleSnapshot obstacle(
            long sourceId,
            long primitiveId,
            long continuityEpoch
    ) {
        return SceneFixtures.dynamicObstacle(
                sourceId,
                primitiveId,
                continuityEpoch,
                Vec3d.ZERO,
                1.0D,
                Vec3d.ZERO,
                Vec3d.ZERO,
                TIME.gameTick(),
                continuityEpoch,
                TIME.intervalTicks()
        );
    }

    private static final class RecordingProvider
            implements ExternalRigidCollisionProvider {
        private final String id;
        private final DynamicCollisionObstacleSnapshot obstacle;
        private ExternalRigidCollisionQuery observed;

        private RecordingProvider(
                String id,
                DynamicCollisionObstacleSnapshot obstacle
        ) {
            this.id = id;
            this.obstacle = obstacle;
        }

        @Override
        public String id() {
            return this.id;
        }

        @Override
        public void capture(
                ExternalRigidCollisionQuery query,
                cc.sighs.gravityengine.gravity.collision.RigidPublicationCollector output
        ) {
            this.observed = query;
            output.addObstacle(this.obstacle);
        }

        @Override
        public Optional<DynamicCollisionObstacleSnapshot> resolve(
                RigidObstacleIdentity identity,
                KinematicStepContext time
        ) {
            return identity.matchesProviderComponents(this.obstacle)
                    ? Optional.of(this.obstacle)
                    : Optional.empty();
        }
    }
}
