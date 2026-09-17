package cc.sighs.gravityengine.gravity.integration.vanilla;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.collision.CollisionWorkBudget;
import cc.sighs.gravityengine.gravity.collision.CollisionWorkTracker;
import cc.sighs.gravityengine.gravity.collision.DynamicCollisionObstacleSnapshot;
import cc.sighs.gravityengine.gravity.collision.RigidMotionSnapshot;
import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.collision.provider.*;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d;
import cc.sighs.gravityengine.math.geometry.RigidPose;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RigidOccupancySnapshotTest {
    private static final KinematicStepContext TIME =
            KinematicStepContext.fullTick(100L, 0L);

    @Test
    void providerIsCapturedOnceAndLaterPacketsSeeNewPublications() {
        Object scope = new Object();
        class Provider implements ExternalRigidCollisionProvider {
            int captures;
            DynamicCollisionObstacleSnapshot current = obstacleAt(1.5);
            public String id() { return "test-provider"; }
            public void capture(ExternalRigidCollisionQuery query, RigidPublicationCollector output) {
                captures++;
                output.addObstacle(current);
            }
            public Optional<DynamicCollisionObstacleSnapshot> resolve(RigidObstacleIdentity id, KinematicStepContext time) {
                throw new AssertionError("occupancy must not reacquire a live publication");
            }
        }
        Provider provider = new Provider();
        try {
            RigidCollisionPublicationRegistry.register(scope, provider);
            Aabb3d bounds = new Aabb3d(-10, -10, -10, 10, 10, 10);
            var query = new ExternalRigidCollisionQuery(bounds, bounds, TIME);
            var frozen = RigidCollisionPublicationRegistry.capture(scope, query).build().dynamicObstacles();
            var snapshot = new RigidOccupancySnapshot(100, 0, TIME, frozen);
            assertTrue(VanillaBodyOccupancy.dynamicRigidClear(bodyAt(0), snapshot));
            provider.current = obstacleAt(5);
            assertTrue(VanillaBodyOccupancy.introducesDynamicRigidCollision(bodyAt(0), bodyAt(1.5), snapshot));
            assertEquals(1, provider.captures);

            // Gameplay, terminal support and occupancy consume the same identity.
            var scene = new CapturedCollisionScene(CollisionCaptureDomain.around(bounds, 1),
                    100, 0, TIME, new CollisionWorkTracker(CollisionWorkBudget.defaults()),
                    new WorldBorderCollisionSnapshot(-100, -100, 100, 100), List.of(), frozen,
                    List.of(), List.of(), Map.of(), 0);
            var obstacle = snapshot.obstacles().get(0);
            var witness = new EndpointSupportWitness(new GravitySupportContact(new Vec3d(0, 1, 0),
                    Vec3d.ZERO, new Vec3d(1.5, .5, 0),
                    GravitySupportContact.SupportGeometryKind.REAL_OBSTACLE_FACE,
                    SupportFaceIdentity.dynamic(new EntityObstacle(obstacle), 3)), 100, 0);
            var support = PersistentSupportState.from(witness, scene).orElseThrow();
            assertEquals(RigidObstacleIdentity.of(obstacle), support.identity().obstacleIdentity());
            assertEquals(obstacle, scene.dynamicObstacle(support.identity().obstacleIdentity()).orElseThrow());

            var next = new RigidOccupancySnapshot(100, 0, TIME,
                    RigidCollisionPublicationRegistry.capture(scope, query).build().dynamicObstacles());
            assertEquals(2, provider.captures);
            assertFalse(VanillaBodyOccupancy.introducesDynamicRigidCollision(bodyAt(0), bodyAt(1.5), next));
            assertTrue(VanillaBodyOccupancy.introducesDynamicRigidCollision(bodyAt(0), bodyAt(1.5), snapshot));
        } finally {
            RigidCollisionPublicationRegistry.clear(scope);
        }
    }

    @Test
    void oldAndNewOccupancyUseTheSameFrozenRigidSnapshot() {
        DynamicCollisionObstacleSnapshot obstacle =
                obstacleAt(1.5D);
        List<DynamicCollisionObstacleSnapshot> mutable =
                new ArrayList<>(List.of(obstacle));
        RigidOccupancySnapshot snapshot =
                new RigidOccupancySnapshot(
                        100L,
                        0L,
                        TIME,
                        mutable
                );

        OrientedBox oldBody = bodyAt(0.0D);
        OrientedBox requestedBody = bodyAt(1.5D);

        assertTrue(
                VanillaBodyOccupancy.dynamicRigidClear(
                        oldBody,
                        snapshot
                )
        );
        assertFalse(
                VanillaBodyOccupancy.dynamicRigidClear(
                        requestedBody,
                        snapshot
                )
        );
        assertTrue(
                VanillaBodyOccupancy
                        .introducesDynamicRigidCollision(
                                oldBody,
                                requestedBody,
                                snapshot
                        )
        );

        mutable.clear();
        assertEquals(1, snapshot.obstacles().size());
        assertTrue(
                VanillaBodyOccupancy
                        .introducesDynamicRigidCollision(
                                oldBody,
                                requestedBody,
                                snapshot
                        )
        );
    }

    @Test
    void emptySnapshotReproducesVanillaOnlyDynamicResult() {
        RigidOccupancySnapshot snapshot =
                new RigidOccupancySnapshot(
                        100L,
                        0L,
                        TIME,
                        List.of()
                );
        OrientedBox body = bodyAt(0.0D);

        assertTrue(snapshot.empty());
        assertTrue(
                VanillaBodyOccupancy.dynamicRigidClear(
                        body,
                        snapshot
                )
        );
        assertFalse(
                VanillaBodyOccupancy
                        .introducesDynamicRigidCollision(
                                body,
                                body,
                                snapshot
                        )
        );
    }

    @Test
    void snapshotRevisionIsAvailableToBothPredicates() {
        RigidOccupancySnapshot snapshot =
                new RigidOccupancySnapshot(
                        100L,
                        7L,
                        TIME,
                        List.of()
                );

        assertEquals(7L, snapshot.revision());
        assertEquals(100L, snapshot.gameTick());
    }

    @Test
    void ordinarySmallSnapshotStaysCompleteAndUnchanged() {
        RigidOccupancySnapshot snapshot =
                new RigidOccupancySnapshot(
                        100L,
                        0L,
                        TIME,
                        List.of(obstacleAt(1.5D))
                );

        assertFalse(snapshot.indeterminate());
        assertTrue(snapshot.chargeNarrowPhaseTest());
        assertEquals(1, snapshot.obstacles().size());
    }

    @Test
    void budgetExhaustionFailsClosedForAnOccupancyThatCannotBeProvenClear() {
        CollisionWorkTracker tracker =
                new CollisionWorkTracker(
                        CollisionWorkBudget.defaults()
                );
        /*
         * The bounded packet capture reports exhaustion as an explicit
         * indeterminate flag; the predicates must treat that as
         * "not proven clear" rather than as a proof of occupancy.
         */
        RigidOccupancySnapshot snapshot =
                new RigidOccupancySnapshot(
                        100L,
                        0L,
                        TIME,
                        List.of(obstacleAt(0.0D)),
                        true,
                        tracker
                );

        OrientedBox body = bodyAt(0.0D);
        assertTrue(snapshot.indeterminate());
        assertFalse(
                VanillaBodyOccupancy.dynamicRigidClear(
                        body,
                        snapshot,
                        true
                ),
                "indeterminate old occupancy must not be reported clear"
        );
        assertTrue(
                VanillaBodyOccupancy
                        .introducesDynamicRigidCollision(
                                body,
                                body,
                                snapshot,
                                true
                        ),
                "indeterminate new occupancy must fail closed"
        );
        assertFalse(snapshot.chargeNarrowPhaseTest());
    }

    private static OrientedBox bodyAt(double centerX) {
        return OrientedBox.axisAligned(
                new Aabb3d(
                        centerX - 0.5D,
                        -0.5D,
                        -0.5D,
                        centerX + 0.5D,
                        0.5D,
                        0.5D
                )
        );
    }

    private static DynamicCollisionObstacleSnapshot obstacleAt(
            double centerX
    ) {
        return new DynamicCollisionObstacleSnapshot(
                "test-provider",
                42L,
                0L,
                OrientedBox.axisAligned(
                        new Aabb3d(
                                centerX - 0.5D,
                                -0.5D,
                                -0.5D,
                                centerX + 0.5D,
                                0.5D,
                                0.5D
                        )
                ),
                new RigidMotionSnapshot(
                        new RigidPose(
                                Vec3d.ZERO,
                                OrthonormalFrame3d.IDENTITY
                        ),
                        Vec3d.ZERO,
                        Vec3d.ZERO,
                        100L,
                        0L,
                        0L,
                        1.0D
                )
        );
    }
}
