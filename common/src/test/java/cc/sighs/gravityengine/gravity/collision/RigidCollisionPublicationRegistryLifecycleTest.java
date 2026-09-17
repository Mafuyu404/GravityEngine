package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.collision.provider.ExternalRigidCollisionProvider;
import cc.sighs.gravityengine.gravity.collision.provider.ExternalRigidCollisionQuery;
import cc.sighs.gravityengine.gravity.collision.provider.RigidCollisionPublicationRegistry;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d;
import cc.sighs.gravityengine.math.geometry.RigidPose;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RigidCollisionPublicationRegistryLifecycleTest {
    private static final long SOURCE_ID = 42L;
    private static final long PRIMITIVE_ID = 3L;

    @Test
    void teardownAndReregistrationCannotReviveAnOldIdentity() {
        for (boolean clearScope : new boolean[]{false, true}) {
            Object scope = new Object();
            var provider = new MutableProvider(publication(100, 1));
            var time = KinematicStepContext.fullTick(100, 0);
            try {
                RigidCollisionPublicationRegistry.register(scope, provider);
                var first = RigidCollisionPublicationRegistry.capture(scope, query(time)).build().dynamicObstacles().get(0);
                if (clearScope) RigidCollisionPublicationRegistry.clear(scope);
                else RigidCollisionPublicationRegistry.unregister(scope, provider.id());
                RigidCollisionPublicationRegistry.register(scope, provider);
                var second = RigidCollisionPublicationRegistry.capture(scope, query(time)).build().dynamicObstacles().get(0);
                assertTrue(RigidCollisionPublicationRegistry.resolve(scope, RigidObstacleIdentity.of(first), time).isEmpty());
                assertTrue(RigidCollisionPublicationRegistry.resolve(scope, RigidObstacleIdentity.of(second), time).isPresent());
            } finally { RigidCollisionPublicationRegistry.clear(scope); }
        }
    }

    @Test
    void sameLogicalNativeOwnerMayRefreshResolverWrapper() {
        Object scope = new Object();
        DynamicCollisionObstacleSnapshot first = publication(100L, 1L);
        DynamicCollisionObstacleSnapshot second = publication(101L, 2L);

        try {
            RigidCollisionPublicationRegistry.recordPublication(
                    scope,
                    first,
                    new LogicalOwnerResolver(7L, first)
            );

            assertDoesNotThrow(
                    () -> RigidCollisionPublicationRegistry.recordPublication(
                            scope,
                            second,
                            new LogicalOwnerResolver(7L, second)
                    )
            );
        } finally {
            RigidCollisionPublicationRegistry.clear(scope);
        }
    }

    @Test
    void differentLiveNativeOwnerCannotClaimTheSameSource() {
        Object scope = new Object();
        DynamicCollisionObstacleSnapshot first = publication(100L, 1L);
        DynamicCollisionObstacleSnapshot second = publication(101L, 2L);

        try {
            RigidCollisionPublicationRegistry.recordPublication(
                    scope,
                    first,
                    new LogicalOwnerResolver(7L, first)
            );

            assertThrows(
                    CollisionSceneCoverageException.class,
                    () -> RigidCollisionPublicationRegistry.recordPublication(
                            scope,
                            second,
                            new LogicalOwnerResolver(8L, second)
                    )
            );
        } finally {
            RigidCollisionPublicationRegistry.clear(scope);
        }
    }

    @Test
    void deadExternalSourceRouteIsReleasedWithoutUnregisteringProvider() {
        Object scope = new Object();
        MutableProvider provider = new MutableProvider(
                publication(100L, 1L)
        );
        RigidCollisionPublicationRegistry.register(scope, provider);

        try {
            KinematicStepContext firstTime =
                    new KinematicStepContext(100L, 1.0D, 1L);
            RigidCollisionPublicationRegistry.capture(
                    scope,
                    query(firstTime)
            );

            provider.sourceLive = false;
            assertTrue(
                    RigidCollisionPublicationRegistry.resolve(
                            scope,
                            RigidObstacleIdentity.of(provider.current),
                            new KinematicStepContext(101L, 1.0D, 2L)
                    ).isEmpty()
            );

            DynamicCollisionObstacleSnapshot replacement =
                    publication(101L, 2L);
            assertDoesNotThrow(
                    () -> RigidCollisionPublicationRegistry.recordPublication(
                            scope,
                            replacement,
                            new LogicalOwnerResolver(99L, replacement)
                    )
            );
        } finally {
            RigidCollisionPublicationRegistry.clear(scope);
        }
    }

    @Test
    void deadProviderIsPrunedWithItsOwnedSourceRoutes() {
        Object scope = new Object();
        MutableProvider provider = new MutableProvider(
                publication(100L, 1L)
        );
        RigidCollisionPublicationRegistry.register(scope, provider);

        try {
            RigidCollisionPublicationRegistry.capture(
                    scope,
                    query(new KinematicStepContext(100L, 1.0D, 1L))
            );

            provider.live = false;
            assertTrue(
                    RigidCollisionPublicationRegistry.providers(scope).isEmpty()
            );

            DynamicCollisionObstacleSnapshot replacement =
                    publication(101L, 2L);
            assertDoesNotThrow(
                    () -> RigidCollisionPublicationRegistry.recordPublication(
                            scope,
                            replacement,
                            new LogicalOwnerResolver(123L, replacement)
                    )
            );
        } finally {
            RigidCollisionPublicationRegistry.clear(scope);
        }
    }

    private static ExternalRigidCollisionQuery query(
            KinematicStepContext time
    ) {
        Aabb3d bounds = new Aabb3d(
                -4.0D, -4.0D, -4.0D,
                4.0D, 4.0D, 4.0D
        );
        return new ExternalRigidCollisionQuery(
                bounds,
                bounds,
                time
        );
    }

    private static DynamicCollisionObstacleSnapshot publication(
            long tick,
            long revision
    ) {
        return new DynamicCollisionObstacleSnapshot(
                SOURCE_ID,
                PRIMITIVE_ID,
                OrientedBox.axisAligned(
                        new Aabb3d(
                                -0.5D, -0.5D, -0.5D,
                                0.5D, 0.5D, 0.5D
                        )
                ),
                new RigidMotionSnapshot(
                        new RigidPose(
                                Vec3d.ZERO,
                                OrthonormalFrame3d.IDENTITY
                        ),
                        Vec3d.ZERO,
                        Vec3d.ZERO,
                        tick,
                        revision,
                        1L,
                        1.0D
                )
        );
    }

    private static final class LogicalOwnerResolver
            implements RigidCollisionPublicationResolver {
        private final long ownerId;
        private final DynamicCollisionObstacleSnapshot current;

        private LogicalOwnerResolver(
                long ownerId,
                DynamicCollisionObstacleSnapshot current
        ) {
            this.ownerId = ownerId;
            this.current = current;
        }

        @Override
        public Optional<DynamicCollisionObstacleSnapshot> resolve(
                RigidObstacleIdentity identity,
                KinematicStepContext time
        ) {
            return identity.matchesProviderComponents(current)
                    ? Optional.of(current)
                    : Optional.empty();
        }

        @Override
        public boolean sameOwner(
                RigidCollisionPublicationResolver other
        ) {
            return other instanceof LogicalOwnerResolver resolver
                    && resolver.ownerId == ownerId;
        }
    }

    private static final class MutableProvider
            implements ExternalRigidCollisionProvider {
        private final DynamicCollisionObstacleSnapshot current;
        private boolean live = true;
        private boolean sourceLive = true;

        private MutableProvider(
                DynamicCollisionObstacleSnapshot current
        ) {
            this.current = current;
        }

        @Override
        public String id() {
            return "lifecycle-provider";
        }

        @Override
        public void capture(
                ExternalRigidCollisionQuery query,
                cc.sighs.gravityengine.gravity.collision.RigidPublicationCollector output
        ) {
            if (live && sourceLive) {
                output.addObstacle(current);
            }
        }

        @Override
        public Optional<DynamicCollisionObstacleSnapshot> resolve(
                RigidObstacleIdentity identity,
                KinematicStepContext time
        ) {
            return live && sourceLive && identity.matchesProviderComponents(current)
                    ? Optional.of(current)
                    : Optional.empty();
        }

        @Override
        public boolean isLive() {
            return live;
        }

        @Override
        public boolean isSourceLive(
                long sourceId,
                KinematicStepContext time
        ) {
            return live
                    && sourceLive
                    && sourceId == current.sourceId();
        }
    }
}
