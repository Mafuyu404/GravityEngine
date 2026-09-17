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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provider-instance replacement is a physical continuity break.
 *
 * <p>Two distinct provider instances registered under the same id must never
 * rebind persistent support captured from the first instance, even when every
 * other identity component is reused. The result must not depend on whether
 * support preflight or the replacement's first capture runs first.</p>
 */
class RigidProviderReplacementContinuityTest {
    private static final String PROVIDER_ID = "example:provider";
    private static final long SOURCE_ID = 42L;
    private static final long PRIMITIVE_ID = 7L;

    @Test
    void providerReplacementAdvancesRegistrationEpochAndBreaksSupport() {
        Object scope = new Object();
        RigidCollisionPublicationRegistry.register(
                scope,
                new ReusableProvider(PROVIDER_ID, 0L)
        );

        try {
            RigidObstacleIdentity oldIdentity =
                    identityAt(scope, 100L);
            assertTrue(
                    resolve(scope, oldIdentity, 100L).isPresent(),
                    "the live provider must resolve its own capture"
            );

            /*
             * Replacement instance B reuses namespace/source/primitive/
             * continuity exactly. Only the engine-owned registration epoch
             * distinguishes it.
             */
            RigidCollisionPublicationRegistry.register(
                    scope,
                    new ReusableProvider(PROVIDER_ID, 0L)
            );

            assertTrue(
                    resolve(scope, oldIdentity, 100L).isEmpty(),
                    "old support must not rebind across provider replacement"
            );
            assertTrue(
                    resolve(
                            scope,
                            identityAt(scope, 100L),
                            100L
                    ).isPresent(),
                    "the replacement must own its own new capture"
            );
        } finally {
            RigidCollisionPublicationRegistry.clear(scope);
        }
    }

    @Test
    void replacementResultIsIndependentOfCaptureOrPreflightOrder() {
        assertTrue(
                replacementClearsSupport(true),
                "preflight-before-capture must clear old support"
        );
        assertTrue(
                replacementClearsSupport(false),
                "capture-before-preflight must clear old support"
        );
    }

    @Test
    void continuityEpochChangeStillInvalidatesSameProvider() {
        Object scope = new Object();
        ReusableProvider provider =
                new ReusableProvider(PROVIDER_ID, 0L);
        RigidCollisionPublicationRegistry.register(scope, provider);

        try {
            RigidObstacleIdentity oldIdentity =
                    identityAt(scope, 100L);

            provider.continuityEpoch = 1L;

            assertTrue(
                    resolve(scope, oldIdentity, 100L).isEmpty(),
                    "a continuity epoch change must clear old support"
            );
        } finally {
            RigidCollisionPublicationRegistry.clear(scope);
        }
    }

    @Test
    void sameLiveProviderOrdinaryNextPublicationRemainsContinuous() {
        Object scope = new Object();
        ReusableProvider provider =
                new ReusableProvider(PROVIDER_ID, 0L);
        RigidCollisionPublicationRegistry.register(scope, provider);

        try {
            RigidObstacleIdentity identity =
                    identityAt(scope, 100L);

            provider.tick = 101L;
            provider.revision = 2L;

            assertTrue(
                    resolve(scope, identity, 101L).isPresent(),
                    "ordinary publication advance must stay continuous"
            );
        } finally {
            RigidCollisionPublicationRegistry.clear(scope);
        }
    }

    @Test
    void reRegisteringTheSameInstanceKeepsItsRegistrationEpoch() {
        Object scope = new Object();
        ReusableProvider provider =
                new ReusableProvider(PROVIDER_ID, 0L);
        RigidCollisionPublicationRegistry.register(scope, provider);

        try {
            RigidObstacleIdentity identity =
                    identityAt(scope, 100L);
            RigidCollisionPublicationRegistry.register(scope, provider);

            assertTrue(
                    resolve(scope, identity, 100L).isPresent(),
                    "re-registering the same instance is not a replacement"
            );
        } finally {
            RigidCollisionPublicationRegistry.clear(scope);
        }
    }

    @Test
    void externalProviderCannotClaimTheReservedNativeNamespace() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RigidCollisionPublicationRegistry.register(
                        new Object(),
                        new ReusableProvider(
                                RigidObstacleIdentity
                                        .NATIVE_PROVIDER_NAMESPACE,
                                0L
                        )
                )
        );
    }

    @Test
    void twoProvidersMayReuseTheSameSourceIdUnderDifferentNamespaces() {
        Object scope = new Object();
        RigidCollisionPublicationRegistry.register(
                scope,
                new ReusableProvider("example:one", 0L)
        );
        RigidCollisionPublicationRegistry.register(
                scope,
                new ReusableProvider("example:two", 0L)
        );

        try {
            List<DynamicCollisionObstacleSnapshot> obstacles =
                    RigidCollisionPublicationRegistry
                            .capture(scope, query(100L))
                            .build()
                            .dynamicObstacles();

            assertEquals(2, obstacles.size());
            assertNotEquals(
                    RigidObstacleIdentity.of(obstacles.get(0)),
                    RigidObstacleIdentity.of(obstacles.get(1))
            );
        } finally {
            RigidCollisionPublicationRegistry.clear(scope);
        }
    }

    private static boolean replacementClearsSupport(
            boolean preflightBeforeCapture
    ) {
        Object scope = new Object();
        RigidCollisionPublicationRegistry.register(
                scope,
                new ReusableProvider(PROVIDER_ID, 0L)
        );

        try {
            RigidObstacleIdentity oldIdentity =
                    identityAt(scope, 100L);
            assertTrue(resolve(scope, oldIdentity, 100L).isPresent());

            RigidCollisionPublicationRegistry.register(
                    scope,
                    new ReusableProvider(PROVIDER_ID, 0L)
            );

            Optional<DynamicCollisionObstacleSnapshot> preflight;
            if (preflightBeforeCapture) {
                preflight = resolve(scope, oldIdentity, 100L);
                RigidCollisionPublicationRegistry.capture(
                        scope,
                        query(100L)
                );
            } else {
                RigidCollisionPublicationRegistry.capture(
                        scope,
                        query(100L)
                );
                preflight = resolve(scope, oldIdentity, 100L);
            }

            return preflight.isEmpty();
        } finally {
            RigidCollisionPublicationRegistry.clear(scope);
        }
    }

    private static RigidObstacleIdentity identityAt(
            Object scope,
            long tick
    ) {
        List<DynamicCollisionObstacleSnapshot> obstacles =
                RigidCollisionPublicationRegistry
                        .capture(scope, query(tick))
                        .build()
                        .dynamicObstacles();
        DynamicCollisionObstacleSnapshot match = null;
        for (DynamicCollisionObstacleSnapshot obstacle : obstacles) {
            if (obstacle.sourceId() == SOURCE_ID
                    && obstacle.primitiveId() == PRIMITIVE_ID) {
                if (match != null) {
                    throw new IllegalStateException(
                            "ambiguous provider capture"
                    );
                }
                match = obstacle;
            }
        }
        if (match == null) {
            throw new IllegalStateException("no provider capture");
        }
        assertTrue(
                match.providerRegistrationEpoch()
                        != DynamicCollisionObstacleSnapshot
                        .PROVIDER_LOCAL_REGISTRATION_EPOCH,
                "registry must normalize the provider registration epoch"
        );
        return RigidObstacleIdentity.of(match);
    }

    private static Optional<DynamicCollisionObstacleSnapshot> resolve(
            Object scope,
            RigidObstacleIdentity identity,
            long tick
    ) {
        return RigidCollisionPublicationRegistry.resolve(
                scope,
                identity,
                new KinematicStepContext(tick, 1.0D, 1L)
        );
    }

    private static ExternalRigidCollisionQuery query(long tick) {
        Aabb3d bounds = new Aabb3d(
                -4.0D, -4.0D, -4.0D,
                4.0D, 4.0D, 4.0D
        );
        return new ExternalRigidCollisionQuery(
                bounds,
                DynamicEntityBroadphasePolicy.candidateQueryBounds(
                        bounds
                ),
                new KinematicStepContext(tick, 1.0D, 1L)
        );
    }

    private static final class ReusableProvider
            implements ExternalRigidCollisionProvider {
        private final String id;
        private long continuityEpoch;
        private long tick = 100L;
        private long revision = 1L;

        private ReusableProvider(
                String id,
                long continuityEpoch
        ) {
            this.id = id;
            this.continuityEpoch = continuityEpoch;
        }

        private DynamicCollisionObstacleSnapshot current() {
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
                            continuityEpoch,
                            1.0D
                    )
            );
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
            output.addObstacle(current());
        }

        @Override
        public Optional<DynamicCollisionObstacleSnapshot> resolve(
                RigidObstacleIdentity identity,
                KinematicStepContext time
        ) {
            DynamicCollisionObstacleSnapshot current = current();
            return identity.matchesProviderComponents(current)
                    ? Optional.of(current)
                    : Optional.empty();
        }

        @Override
        public boolean isSourceLive(
                long sourceId,
                KinematicStepContext time
        ) {
            return sourceId == SOURCE_ID;
        }
    }
}
