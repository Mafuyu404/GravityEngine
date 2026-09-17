package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.integration.EntityMovementIntegration;
import cc.sighs.gravityengine.gravity.kinematic.*;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.model.*;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState;
import cc.sighs.gravityengine.math.geometry.*;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.UUID;

/** Executed with real transformed entities in the dedicated control server. */
final class RigidMovementChecks {
    static void run(ServerLevel level) {
        consumedArcDoesNotReplay(level);
        defaultGravityUsesExternalGeometry(level);
        for (int mode = 0; mode < 3; mode++) {
            platformTravelDoesNotDoubleCarry(level, true, mode);
            platformTravelDoesNotDoubleCarry(level, false, mode);
        }
        System.out.println("RIGID_MOVEMENT_CHECKS_PASSED");
    }

    private static void defaultGravityUsesExternalGeometry(ServerLevel level) {
        Player actor = new Player(level, BlockPos.ZERO, 0,
                new GameProfile(UUID.randomUUID(), "default-rigid")) {
            @Override public boolean isSpectator() { return false; }
            @Override public boolean isCreative() { return false; }
        };
        actor.setPos(8, 300, 8);
        var provider = new cc.sighs.gravityengine.gravity.collision.provider.ExternalRigidCollisionProvider() {
            int captures;
            boolean fail;
            @Override public String id() { return "default-test"; }
            @Override public void capture(cc.sighs.gravityengine.gravity.collision.provider.ExternalRigidCollisionQuery query,
                                          RigidPublicationCollector output) {
                captures++;
                if (fail) throw new CollisionSceneCoverageException("test unavailable publication");
                output.addObstacle(new DynamicCollisionObstacleSnapshot(20, 0,
                        new OrientedBox(Vec3d.ZERO, new Vec3d(.1, 100, 100), OrthonormalFrame3d.IDENTITY),
                        new RigidMotionSnapshot(new RigidPose(new Vec3d(9, 300, 8), OrthonormalFrame3d.IDENTITY),
                                Vec3d.ZERO, Vec3d.ZERO, query.time().gameTick(), 1, 1, query.time().intervalTicks())));
            }
            @Override public Optional<DynamicCollisionObstacleSnapshot> resolve(RigidObstacleIdentity identity,
                                                                                 KinematicStepContext time) {
                return Optional.empty();
            }
        };
        cc.sighs.gravityengine.gravity.collision.provider.RigidCollisionPublicationRegistry.register(level, provider);
        try {
            actor.move(net.minecraft.world.entity.MoverType.SELF, new Vec3(2, 0, 0));
            if (actor.getX() > 8.61 || actor.getX() < 8.5) {
                throw new AssertionError("default gravity bypassed external wall: " + actor.position());
            }
            if (provider.captures != 1) throw new AssertionError("move recaptured provider: " + provider.captures);
            provider.fail = true;
            var stopped = actor.position();
            actor.move(net.minecraft.world.entity.MoverType.SELF, new Vec3(2, 0, 0));
            if (!stopped.equals(actor.position())) throw new AssertionError("failed capture delegated to Vanilla");
            if (GravityEntityAccess.cast(actor).gravityengine$gravityComponent().operationState().isInMove())
                throw new AssertionError("failed capture leaked operation");
        } finally {
            cc.sighs.gravityengine.gravity.collision.provider.RigidCollisionPublicationRegistry.unregister(level, provider.id());
        }
    }

    private static void platformTravelDoesNotDoubleCarry(ServerLevel level, boolean assigned, int mode) {
        // The verification world is reused. Prior geometry checks leave native
        // blocks here; they must not win the dynamic platform's support identity.
        for (var pos : BlockPos.betweenClosed(3, 297, 3, 14, 304, 13))
            level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 18);
        var actor = new Player(level, BlockPos.ZERO, 0,
                new GameProfile(UUID.randomUUID(), "elevator-check")) {
            @Override public boolean isSpectator() { return false; }
            @Override public boolean isCreative() { return false; }
            float power = .42F;
            @Override protected float getJumpPower() { return power; }
            @Override public boolean isControlledByLocalInstance() { return true; }
        };
        actor.setPos(mode == 2 ? 10 : 8, 300, 8);
        if (assigned) cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.applyDirectAssignment(actor,
                new cc.sighs.gravityengine.gravity.GravityState(new Vec3d(0, -1, 0), 1));
        var provider = new cc.sighs.gravityengine.gravity.collision.provider.ExternalRigidCollisionProvider() {
            double y = 299, x = 8, angle;
            double speed;
            boolean wall;
            double wallX;
            long revision;
            long source = 30, epoch = 1;
            boolean present = true;
            @Override public String id() { return "elevator-test"; }
            DynamicCollisionObstacleSnapshot publication(KinematicStepContext time) {
                return new DynamicCollisionObstacleSnapshot(source, 0,
                        new OrientedBox(Vec3d.ZERO, new Vec3d(3, 1, 3), OrthonormalFrame3d.IDENTITY),
                        new RigidMotionSnapshot(new RigidPose(new Vec3d(x, y, 8), BodyOrientation3d.frame(
                                cc.sighs.gravityengine.math.Quatd.rotationY(angle))),
                                mode == 0 ? new Vec3d(0, speed, 0) : mode == 1 ? new Vec3d(speed, 0, 0) : Vec3d.ZERO,
                                mode == 2 ? new Vec3d(0, speed, 0) : Vec3d.ZERO, time.gameTick(), revision, epoch, time.intervalTicks()));
            }
            @Override public void capture(cc.sighs.gravityengine.gravity.collision.provider.ExternalRigidCollisionQuery query,
                                          RigidPublicationCollector output) {
                if (!present) return;
                output.addObstacle(publication(query.time()));
                if (wall) output.addObstacle(new DynamicCollisionObstacleSnapshot(31, 0,
                        new OrientedBox(Vec3d.ZERO, new Vec3d(.1, 5, 5), OrthonormalFrame3d.IDENTITY),
                        new RigidMotionSnapshot(new RigidPose(new Vec3d(wallX, 300, 8), OrthonormalFrame3d.IDENTITY),
                                Vec3d.ZERO, Vec3d.ZERO, query.time().gameTick(), revision, 1, 1)));
            }
            @Override public Optional<DynamicCollisionObstacleSnapshot> resolve(RigidObstacleIdentity identity,
                                                                                 KinematicStepContext time) {
                return present ? Optional.of(publication(time)) : Optional.empty();
            }
        };
        cc.sighs.gravityengine.gravity.collision.provider.RigidCollisionPublicationRegistry.register(level, provider);
        long originalTime = level.getGameTime();
        try {
            actor.setDeltaMovement(0, -.01, 0);
            actor.travel(Vec3.ZERO);
            for (double speed : new double[]{.1, .1, .2, -.1, 0, .1}) {
                ((net.minecraft.world.level.storage.ServerLevelData) level.getLevelData()).setGameTime(level.getGameTime() + 1);
                provider.revision++;
                if (mode == 0) provider.y += provider.speed;
                else if (mode == 1) provider.x += provider.speed;
                else provider.angle += provider.speed;
                provider.speed = speed;
                var runtime = GravityEntityAccess.cast(actor).gravityengine$gravityComponent().operationState();
                var carry = cc.sighs.gravityengine.gravity.integration.EngineSupportTransportIntegration
                        .preflight(actor, runtime, level.getGameTime(), 1).orElseThrow().displacement();
                var before = actor.position();
                var requested = MinecraftMathAdapter.toVec3d(actor.getDeltaMovement())
                        .subtract(runtime.supportVelocityContribution()).add(carry);
                actor.travel(Vec3.ZERO);
                var actual = actor.position().subtract(before);
                System.out.println("PLATFORM mode=" + mode + " assigned=" + assigned + " tick=" + level.getGameTime() + " request=" + requested + " carry=" + carry
                        + " displacement=" + actual + " worldVelocity=" + actor.getDeltaMovement());
                if (MinecraftMathAdapter.toVec3d(actual).distanceSquared(carry) > 1e-10) {
                    throw new AssertionError("elevator carry counted incorrectly: expected=" + speed + " actual=" + actual);
                }

            }
            var runtime = GravityEntityAccess.cast(actor).gravityengine$gravityComponent().operationState();
            if (mode == 1) {
                provider.wall = true;
                provider.wallX = actor.getX() + .4;
                provider.x += provider.speed;
                provider.speed = .1;
                provider.revision++;
                ((net.minecraft.world.level.storage.ServerLevelData)level.getLevelData()).setGameTime(level.getGameTime() + 1);
                actor.travel(Vec3.ZERO);
                if (Math.abs(runtime.supportVelocityContribution().x()) > 1e-6)
                    throw new AssertionError("clipped support velocity remained credited: " + runtime.supportVelocityContribution());
                provider.wall = false;
                provider.x += provider.speed;
                provider.speed = -.1;
                provider.revision++;
                ((net.minecraft.world.level.storage.ServerLevelData)level.getLevelData()).setGameTime(level.getGameTime() + 1);
                var beforeReverse = actor.position();
                actor.travel(Vec3.ZERO);
                if (Math.abs(actor.getX() - beforeReverse.x + .1) > 1e-5)
                    throw new AssertionError("wall-clipped carry replayed on platform reversal");
            }
            ((net.minecraft.world.level.storage.ServerLevelData)level.getLevelData()).setGameTime(level.getGameTime() + 1);
            provider.revision++;
            if (mode == 0) provider.y += provider.speed;
            else if (mode == 1) provider.x += provider.speed;
            else provider.angle += provider.speed;
            provider.speed = 0;
            var externalImpulse = new Vec3(.025, 0, .035);
            actor.setDeltaMovement(actor.getDeltaMovement().add(externalImpulse));
            var beforeImpulse = actor.position();
            actor.travel(Vec3.ZERO);
            if (actor.position().subtract(beforeImpulse).distanceTo(externalImpulse) > 1e-6)
                throw new AssertionError("support credit removed an external impulse");
            // Publish a moving interval again so accepted jump exercises actual release.
            ((net.minecraft.world.level.storage.ServerLevelData)level.getLevelData()).setGameTime(level.getGameTime() + 1);
            provider.revision++;
            provider.speed = .1;
            actor.travel(Vec3.ZERO);
            var support = runtime.persistentSupportState();
            var beforeJump = actor.getDeltaMovement();
            actor.power = 0;
            actor.jumpFromGround();
            if (runtime.persistentSupportState() != support || !actor.getDeltaMovement().equals(beforeJump))
                throw new AssertionError("cancelled jump consumed support momentum");
            actor.power = .42F;
            var release = cc.sighs.gravityengine.gravity.integration.EngineSupportTransportIntegration
                    .captureJumpReleaseVelocity(actor, runtime).orElseThrow();
            var credited = runtime.supportVelocityContribution();
            var expected = beforeJump.add(MinecraftMathAdapter.toMinecraft(release.subtract(credited)));
            expected = new Vec3(expected.x, actor.power + release.y(), expected.z);
            actor.jumpFromGround();
            System.out.println("PLATFORM_RELEASE mode=" + mode + " assigned=" + assigned + " credited=" + credited
                    + " release=" + release + " worldVelocity=" + actor.getDeltaMovement());
            if (actor.getDeltaMovement().distanceTo(expected) > 1e-6 || runtime.persistentSupportState() != null)
                throw new AssertionError("accepted jump counted support twice: " + actor.getDeltaMovement() + " expected=" + expected);
            if (mode == 1) {
                provider.x += provider.speed; provider.speed=0; provider.revision++;
                ((net.minecraft.world.level.storage.ServerLevelData)level.getLevelData()).setGameTime(level.getGameTime()+1);
                actor.setDeltaMovement(0,-.01,0); actor.travel(Vec3.ZERO);
                if(runtime.persistentSupportState()==null) throw new AssertionError("relanding must establish support");
                provider.source=32; provider.revision++;
                ((net.minecraft.world.level.storage.ServerLevelData)level.getLevelData()).setGameTime(level.getGameTime()+1);
                actor.travel(Vec3.ZERO);
                if(runtime.persistentSupportState()==null || runtime.persistentSupportState().identity().obstacleIdentity().sourceId()!=32)
                    throw new AssertionError("support switch must acquire new terminal witness");
                provider.epoch++; provider.revision++;
                var velocity=actor.getDeltaMovement();
                if(cc.sighs.gravityengine.gravity.integration.EngineSupportTransportIntegration.preflight(actor,runtime,level.getGameTime()+1,1).isPresent()
                        || !actor.getDeltaMovement().equals(velocity))
                    throw new AssertionError("epoch invalidation must not reuse carry or add release");
                ((net.minecraft.world.level.storage.ServerLevelData)level.getLevelData()).setGameTime(level.getGameTime()+1);
                actor.travel(Vec3.ZERO);
                if(runtime.persistentSupportState()==null) throw new AssertionError("new epoch may establish new support");
                provider.present=false; velocity=actor.getDeltaMovement();
                if(cc.sighs.gravityengine.gravity.integration.EngineSupportTransportIntegration.preflight(actor,runtime,level.getGameTime()+1,1).isPresent()
                        || !actor.getDeltaMovement().equals(velocity))
                    throw new AssertionError("deleted support must not generate release velocity");
                ((net.minecraft.world.level.storage.ServerLevelData)level.getLevelData()).setGameTime(level.getGameTime()+1);
                actor.travel(Vec3.ZERO);
                if(runtime.persistentSupportState()!=null || runtime.supportVelocityContribution().lengthSquared()!=0)
                    throw new AssertionError("movement owner must retire deleted support");
                System.out.println("SUPPORT_LIFECYCLE_CHECKS_PASSED assigned="+assigned+" switch epoch removal");
            }
        } finally {
            ((net.minecraft.world.level.storage.ServerLevelData) level.getLevelData()).setGameTime(originalTime);
            cc.sighs.gravityengine.gravity.collision.provider.RigidCollisionPublicationRegistry.unregister(level, provider.id());
        }
    }

    private static void consumedArcDoesNotReplay(ServerLevel level) {
        Player actor = new Player(level, BlockPos.ZERO, 0,
                new GameProfile(UUID.randomUUID(), "rigid-check")) {
            @Override public boolean isSpectator() { return false; }
            @Override public boolean isCreative() { return false; }
        };
        var origin = new Vec3d(8, 300, 8);
        var motion = new RigidMotionSnapshot(new RigidPose(origin, OrthonormalFrame3d.IDENTITY),
                Vec3d.ZERO, new Vec3d(0, 1, 0), 100, 1, 1, 1);
        var trajectory = new SupportMotionTrajectory(motion, new Vec3d(10, 0, 0));
        var transport = new SupportTransport(trajectory.displacement(0, 1),
                trajectory.velocityAt(0), trajectory.velocityAt(1),
                Optional.of(trajectory), 1, 1, 100);
        actor.setPos(MinecraftMathAdapter.toMinecraft(trajectory.positionAt(1).add(new Vec3d(0, 2, 0))));
        var frame = GravityFrame.DEFAULT;
        var body = GravityEntityGeometry.body(actor);
        var phantom = body.enclosingAabb().center().add(trajectory.displacement(0, .5))
                .subtract(transport.displacement().multiply(.5));
        var builder = new CollisionSceneBuilder(KinematicStepContext.fullTick(100, 1));
        builder.addObstacle(new DynamicCollisionObstacleSnapshot(99, 0,
                new OrientedBox(Vec3d.ZERO, new Vec3d(.03, .03, .03), OrthonormalFrame3d.IDENTITY),
                new RigidMotionSnapshot(new RigidPose(phantom, OrthonormalFrame3d.IDENTITY),
                        Vec3d.ZERO, Vec3d.ZERO, 100, 1, 1, 1)));
        var scene = builder.build();
        var runtime = GravityEntityAccess.cast(actor).gravityengine$gravityComponent().operationState();
        try (var outer = runtime.openMove(frame, 100, GravityOperationType.TRAVEL)) {
            runtime.setCollisionOperation(new GravityOperationState.CollisionOperationContext(frame,
                    scene.time(), scene, new ObbQueryContext(), new CollisionWorkTracker(CollisionWorkBudget.defaults())));
            runtime.stageEngineSupportTransport(transport);
            runtime.consumeEngineSupportTransport();
            try (var move = runtime.openMove(frame, 100, GravityOperationType.MOVE)) {
                runtime.beginMovement(GravityCollisionRoute.EXACT_BODY);
                try (var evidence = runtime.openMovementEvidence(MovementEvidence.capture(
                        KinematicMoveRequest.Channel.SELF, Vec3d.ZERO, OwnedMotion.ZERO))) {
                    var resolved = EntityMovementIntegration.collide(actor, Vec3.ZERO);
                    if (resolved.lengthSqr() > 1e-18) {
                        throw new AssertionError("consumed arc replayed on zero request: " + resolved);
                    }
                    if (runtime.currentMoveResult().indeterminate()) {
                        throw new AssertionError("zero request must remain determinate");
                    }
                }
            }
        }
    }
}

