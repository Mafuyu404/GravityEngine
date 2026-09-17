package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.collision.provider.*;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class QualifiedRigidIdentityTest {
    private static final Vec3d UP = new Vec3d(0, 1, 0);
    private static final KinematicStepContext TIME = new KinematicStepContext(100, 1, 1);
    private static final Aabb3d BOUNDS = new Aabb3d(-10, -10, -10, 10, 10, 10);

    @Test
    void sourceRemovalAndPrimitiveContinuityAreProviderLocal() {
        Object scope = new Object();
        Provider a = new Provider("a", Vec3d.ZERO);
        Provider b = new Provider("b", Vec3d.ZERO);
        try {
            RigidCollisionPublicationRegistry.register(scope, a);
            RigidCollisionPublicationRegistry.register(scope, b);
            List<DynamicCollisionObstacleSnapshot> publications = capture(scope);
            assertEquals(4, publications.size());
            var a0 = RigidObstacleIdentity.of(publications.get(0));
            var a1 = RigidObstacleIdentity.of(publications.get(1));
            var b0 = RigidObstacleIdentity.of(publications.get(2));
            assertNotEquals(a0, a1);
            assertNotEquals(a0, b0);
            assertFalse(a0.matches(publications.get(2)));
            assertFalse(a0.matches(a.publication(0)), "unqualified publication is not physics identity");
            assertFalse(a0.matches(publications.get(0).withProviderIdentity("a", 0)),
                    "unassigned registration epoch is not a wildcard");

            a.epoch++;
            assertTrue(resolve(scope, a0).isEmpty());
            assertTrue(resolve(scope, b0).isPresent());
            a.live = false;
            assertEquals(2, capture(scope).size());
            assertTrue(resolve(scope, a1).isEmpty());
            assertTrue(resolve(scope, b0).isPresent());
        } finally {
            RigidCollisionPublicationRegistry.clear(scope);
        }
    }

    @Test
    void supportTransportNeverFallsBackToAnotherProvidersNumericIds() {
        Object scope = new Object();
        Provider a = new Provider("a", Vec3d.ZERO);
        Provider b = new Provider("b", new Vec3d(4, 0, 0));
        try {
            RigidCollisionPublicationRegistry.register(scope, a);
            RigidCollisionPublicationRegistry.register(scope, b);
            var publications = capture(scope);
            var first = publications.get(0);
            var other = publications.get(2);
            var scene = SceneFixtures.scene(100, 1, 1, List.of(), publications);
            var witness = new EndpointSupportWitness(SceneFixtures.support(
                    UP, Vec3d.ZERO, new Vec3d(0, 1, 0),
                    SupportFaceIdentity.dynamic(new EntityObstacle(first), 3)), 100, 1);
            var support = PersistentSupportState.from(witness, scene).orElseThrow();
            assertEquals(RigidObstacleIdentity.of(first), witness.identity().obstacleIdentity());
            assertEquals(witness.identity(), support.identity());
            assertSame(first, scene.dynamicObstacle(support.identity().obstacleIdentity()).orElseThrow());

            a.tick = b.tick = 101;
            a.revision = b.revision = 2;
            a.motion = new Vec3d(1, 0, 0);
            b.motion = new Vec3d(2, 0, 0);
            var nextTime = new KinematicStepContext(101, 1, 2);
            assertEquals(a.motion, RigidSupportTransportPreflight.preflight(support, scope, nextTime)
                    .orElseThrow().displacement());
            RigidCollisionPublicationRegistry.unregister(scope, "a");
            assertTrue(RigidSupportTransportPreflight.preflight(support, scope, nextTime).isEmpty());
            assertTrue(RigidCollisionPublicationRegistry.resolve(scope, RigidObstacleIdentity.of(other), nextTime)
                    .isPresent());
            var remaining = RigidCollisionPublicationRegistry.capture(scope,
                    new ExternalRigidCollisionQuery(BOUNDS, BOUNDS, nextTime)).build().dynamicObstacles();
            assertTrue(SupportTransportResolver.resolve(support,
                    SceneFixtures.scene(101, 2, 1, List.of(), remaining)).isEmpty());
        } finally {
            RigidCollisionPublicationRegistry.clear(scope);
        }
    }

    private static List<DynamicCollisionObstacleSnapshot> capture(Object scope) {
        return RigidCollisionPublicationRegistry.capture(scope,
                new ExternalRigidCollisionQuery(BOUNDS, BOUNDS, TIME)).build().dynamicObstacles();
    }

    private static Optional<DynamicCollisionObstacleSnapshot> resolve(Object scope, RigidObstacleIdentity id) {
        return RigidCollisionPublicationRegistry.resolve(scope, id, TIME);
    }

    private static final class Provider implements ExternalRigidCollisionProvider {
        private final String id;
        private final Vec3d center;
        private long epoch = 7, tick = 100, revision = 1;
        private boolean live = true;
        private Vec3d motion = Vec3d.ZERO;

        private Provider(String id, Vec3d center) { this.id = id; this.center = center; }
        public String id() { return id; }
        public boolean isSourceLive(long sourceId, KinematicStepContext time) { return live; }
        public void capture(ExternalRigidCollisionQuery query, RigidPublicationCollector output) {
            if (live) {
                output.addObstacle(publication(0));
                output.addObstacle(publication(1));
            }
        }
        private DynamicCollisionObstacleSnapshot publication(long primitive) {
            return SceneFixtures.dynamicObstacle(42, primitive, epoch, center, 1,
                    motion, Vec3d.ZERO, tick, revision, 1);
        }
        public Optional<DynamicCollisionObstacleSnapshot> resolve(RigidObstacleIdentity id, KinematicStepContext time) {
            var current = publication(id.primitiveId());
            return live && id.matchesProviderComponents(current) ? Optional.of(current) : Optional.empty();
        }
    }
}
