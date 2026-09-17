package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaBodyOccupancy;
import cc.sighs.gravityengine.gravity.minecraft.collision.MinecraftCollisionGeometryAdapter;
import cc.sighs.gravityengine.gravity.collision.DynamicCollisionObstacleSnapshot;
import cc.sighs.gravityengine.gravity.collision.RigidMotionSnapshot;
import cc.sighs.gravityengine.gravity.collision.RigidObstacleIdentity;
import cc.sighs.gravityengine.gravity.collision.provider.ExternalRigidCollisionProvider;
import cc.sighs.gravityengine.gravity.collision.provider.ExternalRigidCollisionQuery;
import cc.sighs.gravityengine.gravity.collision.provider.RigidCollisionPublicationRegistry;
import cc.sighs.gravityengine.gravity.integration.vanilla.RigidOccupancySnapshot;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d;
import cc.sighs.gravityengine.math.geometry.RigidPose;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executable packet-occupancy lifecycle assertions.
 *
 * <p>Runs on the dedicated server's owning thread and proves the two
 * packet-occupancy invariants that cannot be checked by a pure unit test:</p>
 *
 * <ul>
 *   <li>a live world/provider capture is refused off the owning server thread,
 *       so Vanilla's initial network-thread {@code handleMovePlayer} invocation
 *       cannot perform world work;</li>
 *   <li>one server-thread capture consults the registered external rigid
 *       provider and freezes its publication into the one snapshot.</li>
 * </ul>
 */
final class PacketOccupancyChecks {
    private PacketOccupancyChecks() {}

    static void run(ServerLevel level) {
        CollisionBody body = OrientedBox.axisAligned(
                new Aabb3d(
                        -0.3D, -0.9D, -0.3D,
                        0.3D, 0.9D, 0.3D
                )
        );

        ServerPlayer owner = new ServerPlayer(
                level.getServer(),
                level,
                new com.mojang.authlib.GameProfile(
                        java.util.UUID.randomUUID(),
                        "ControlPacket"
                ),
                net.minecraft.server.level.ClientInformation
                        .createDefault()
        );
        owner.setPos(8.0D, 300.0D, 8.0D);

        assertOffThreadCaptureRefused(owner, body);
        assertProviderPublicationIsCapturedOnce(level, owner, body);
        System.out.println(
                "PACKET_OCCUPANCY_CHECKS_PASSED off-thread-refused "
                        + "capture-reuse frozen-publication next-validation"
        );
    }

    private static void assertOffThreadCaptureRefused(
            ServerPlayer owner,
            CollisionBody body
    ) {
        AtomicReference<Throwable> observed =
                new AtomicReference<>();
        Thread offThread = new Thread(
                () -> {
                    try {
                        RigidOccupancySnapshot.capture(
                                owner,
                                body,
                                body
                        );
                    } catch (Throwable failure) {
                        observed.set(failure);
                    }
                },
                "gravityengine-packet-occupancy-control"
        );
        offThread.start();
        try {
            offThread.join(30_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(
                    "interrupted while proving the off-thread capture guard",
                    interrupted
            );
        }

        Throwable failure = observed.get();
        if (!(failure instanceof IllegalStateException)
                || failure.getMessage() == null
                || !failure.getMessage().contains("server thread")) {
            throw new AssertionError(
                    "off-thread rigid capture must fail fast with the "
                            + "server-thread contract, saw " + failure,
                    failure
            );
        }
    }

    private static void assertProviderPublicationIsCapturedOnce(
            ServerLevel level,
            ServerPlayer owner,
            CollisionBody body
    ) {
        body = body.move(new Vec3d(8, 300, 8));
        CollisionBody requested = body.move(new Vec3d(2, 0, 0));
        RecordingProvider provider = new RecordingProvider(requested.deflated(.1));
        RigidCollisionPublicationRegistry.register(level, provider);
        try {
            RigidOccupancySnapshot snapshot =
                    RigidOccupancySnapshot.capture(
                            owner,
                            body,
                            requested
                    );
            if (provider.captureCalls != 1) {
                throw new AssertionError(
                        "one logical capture must invoke the external rigid "
                                + "provider exactly once, was "
                                + provider.captureCalls
                );
            }
            if (snapshot.obstacles().isEmpty()) {
                throw new AssertionError(
                        "packet capture must freeze the provider publication"
                );
            }
            var work = snapshot.tracker().snapshot();
            if (work.blockPositionsVisited() != 0 || work.blockShapesEvaluated() != 0
                    || work.worldBorderSnapshots() != 0 || work.sourceSphereSnapshots() != 0) {
                throw new AssertionError("rigid occupancy must not capture static world geometry");
            }
            if (!VanillaBodyOccupancy.oldBodyClear(level, owner,
                    MinecraftCollisionGeometryAdapter.toMinecraft(body.enclosingAabb()), body, snapshot)) {
                throw new AssertionError("old body must be clear of the captured rigid publication");
            }
            provider.present = false;
            if (!VanillaBodyOccupancy.hasNewCollision(level, owner, body, requested, snapshot)
                    || provider.captureCalls != 1) {
                throw new AssertionError("new occupancy must reuse the old check's frozen publication");
            }
            var next = RigidOccupancySnapshot.capture(owner, body, requested);
            if (VanillaBodyOccupancy.hasNewCollision(level, owner, body, requested, next)
                    || provider.captureCalls != 2) {
                throw new AssertionError("the next validation must observe provider removal");
            }
            DynamicCollisionObstacleSnapshot captured =
                    snapshot.obstacles().get(0);
            if (!captured.providerNamespace()
                    .equals(RecordingProvider.ID)
                    || captured.providerRegistrationEpoch()
                    == DynamicCollisionObstacleSnapshot
                    .PROVIDER_LOCAL_REGISTRATION_EPOCH) {
                throw new AssertionError(
                        "captured publication must carry the registry-owned "
                                + "provider namespace and registration epoch"
                );
            }
        } finally {
            RigidCollisionPublicationRegistry.unregister(level, RecordingProvider.ID);
        }
    }

    private static final class RecordingProvider
            implements ExternalRigidCollisionProvider {
        private static final String ID =
                "gravityengine_control_tests:packet";
        private int captureCalls;
        private boolean present = true;
        private final CollisionBody geometry;

        private RecordingProvider(CollisionBody geometry) { this.geometry = geometry; }

        @Override
        public String id() {
            return ID;
        }

        @Override
        public void capture(
                ExternalRigidCollisionQuery query,
                cc.sighs.gravityengine.gravity.collision.RigidPublicationCollector output
        ) {
            captureCalls++;
            if (!present) return;
            output.addObstacle(
                    new DynamicCollisionObstacleSnapshot(
                            7L,
                            0L,
                            geometry,
                            new RigidMotionSnapshot(
                                    new RigidPose(
                                            Vec3d.ZERO,
                                            OrthonormalFrame3d.IDENTITY
                                    ),
                                    Vec3d.ZERO,
                                    Vec3d.ZERO,
                                    query.time().gameTick(),
                                    1L,
                                    0L,
                                    query.time().intervalTicks()
                            )
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
}
