package cc.sighs.gravityengine.controltest;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import com.mojang.authlib.GameProfile;
import java.util.UUID;

/** Real Sable classes, loader, plot geometry, Mixin redirect and GE solver. */
final class SableRuntimeChecks {
    static void run(ServerLevel level) {
        var container = SubLevelContainer.getContainer(level);
        if (container == null) throw new AssertionError("Sable container missing");
        var pose = new Pose3d();
        pose.position().set(9, 300, 8);
        var body = container.allocateNewSubLevel(pose);
        var plot = body.getPlot();
        plot.newEmptyChunk(plot.getCenterChunk());
        plot.getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), 3);
        body.updateLastPose();
        body.updateBoundingBox();
        Player actor = new Player(level, BlockPos.ZERO, 0, new GameProfile(UUID.randomUUID(), "sable-check")) {
            @Override public boolean isSpectator() { return false; }
            @Override public boolean isCreative() { return false; }
        };
        var packetOwner = new net.minecraft.server.level.ServerPlayer(level.getServer(), level,
                new GameProfile(UUID.randomUUID(), "sable-packet"), net.minecraft.server.level.ClientInformation.createDefault());
        var oldBody = new cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterCapsule(
                new cc.sighs.gravityengine.api.math.Vec3d(8, 300.9, 8),
                new cc.sighs.gravityengine.api.math.Vec3d(0, 1, 0), .3, .6);
        var newBody = oldBody.move(new cc.sighs.gravityengine.api.math.Vec3d(1, 0, 0));
        var packet = cc.sighs.gravityengine.gravity.integration.vanilla.RigidOccupancySnapshot.capture(packetOwner, oldBody, newBody);
        if (!cc.sighs.gravityengine.gravity.integration.vanilla.VanillaBodyOccupancy.oldBodyClear(level, packetOwner,
                cc.sighs.gravityengine.gravity.minecraft.collision.MinecraftCollisionGeometryAdapter.toMinecraft(oldBody.enclosingAabb()),
                oldBody, packet)) throw new AssertionError("Sable packet old position should be clear");
        actor.setPos(8, 300, 8);
        actor.move(MoverType.SELF, new Vec3(2, 0, 0));
        if (actor.getX() >= 9 || actor.getX() <= 8) throw new AssertionError("Sable wall missed: " + actor.position());
        var extension = (dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension)actor;
        if (!(extension.sable$getCollisionInfo() instanceof cc.sighs.gravityengine.gravity.integration.compat.sable.SableCollisionOwnership.EngineCollisionInfo)) throw new AssertionError("Sable native solver also executed");
        BodyAuthorityChecks.sablePacket(level, false);
        container.removeSubLevel(body, SubLevelRemovalReason.REMOVED);
        container.processSubLevelRemovals();
        BodyAuthorityChecks.sablePacket(level, true);
        if (!cc.sighs.gravityengine.gravity.integration.vanilla.VanillaBodyOccupancy.hasNewCollision(
                level, packetOwner, oldBody, newBody, packet))
            throw new AssertionError("Sable packet did not retain frozen geometry after removal");
        var nextPacket = cc.sighs.gravityengine.gravity.integration.vanilla.RigidOccupancySnapshot.capture(packetOwner, oldBody, newBody);
        if (cc.sighs.gravityengine.gravity.integration.vanilla.VanillaBodyOccupancy.hasNewCollision(
                level, packetOwner, oldBody, newBody, nextPacket))
            throw new AssertionError("Sable packet did not observe removal on next capture");
        System.out.println("SABLE_PACKET_SNAPSHOT_CHECKS_PASSED old-new-frozen removal");
        platforms(level);
        gravity(level);
        physicsPassenger(level);
        System.out.println("SABLE_RUNTIME_CHECKS_PASSED static-geometry default-gravity single-solver removal");
    }
    private static void platforms(ServerLevel level) {
        var container = SubLevelContainer.getContainer(level);
        long originalTime = level.getGameTime();
        for (int mode = 0; mode < 3; mode++) {
            var pose = new Pose3d();
            pose.position().set(8, 299.5, 8);
            var body = container.allocateSubLevel(UUID.randomUUID(), 10 + mode, 0, pose);
            var plot = body.getPlot();
            plot.newEmptyChunk(plot.getCenterChunk());
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
                plot.getEmbeddedLevelAccessor().setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
            body.updateLastPose();
            body.updateBoundingBox();
            Player actor = new Player(level, BlockPos.ZERO, 0, new GameProfile(UUID.randomUUID(), "sable-platform")) {
                @Override public boolean isSpectator() { return false; }
                @Override public boolean isCreative() { return false; }
                @Override public boolean isControlledByLocalInstance() { return true; }
            };
            actor.setPos(8.5, 300, 8);
            actor.setDeltaMovement(0, -.01, 0);
            var runtime = cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess.cast(actor)
                    .gravityengine$gravityComponent().operationState();
            try {
                actor.travel(Vec3.ZERO);
                if (runtime.persistentSupportState() == null) throw new AssertionError("Sable floor did not establish support");
                for (double speed : new double[]{.08, .12, -.06, 0}) {
                    ((net.minecraft.world.level.storage.ServerLevelData)level.getLevelData()).setGameTime(level.getGameTime() + 1);
                    body.updateLastPose();
                    if (mode == 0) body.logicalPose().position().add(0, speed, 0);
                    else if (mode == 1) body.logicalPose().position().add(speed, 0, 0);
                    else body.logicalPose().orientation().rotateY(speed);
                    body.updateBoundingBox();
                    var carry = cc.sighs.gravityengine.gravity.integration.EngineSupportTransportIntegration
                            .preflight(actor, runtime, level.getGameTime(), 1).orElseThrow().displacement();
                    var before = actor.position();
                    actor.travel(Vec3.ZERO);
                    var actual = cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter
                            .toVec3d(actor.position().subtract(before));
                    if (actual.distanceSquared(carry) > 1e-9)
                        throw new AssertionError("Sable platform mode=" + mode + " carry=" + carry + " actual=" + actual);
                }
                var identity = runtime.persistentSupportState().identity().obstacleIdentity();
                plot.getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
                var time = cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext.fullTick(level.getGameTime()+1, 0);
                if (cc.sighs.gravityengine.gravity.collision.provider.RigidCollisionPublicationRegistry
                        .resolve(level, identity, time).isPresent()) throw new AssertionError("edited plot retained old support identity");
            } finally {
                ((net.minecraft.world.level.storage.ServerLevelData)level.getLevelData()).setGameTime(originalTime);
                container.removeSubLevel(body, SubLevelRemovalReason.REMOVED);
                container.processSubLevelRemovals();
            }
        }
        System.out.println("SABLE_PLATFORM_CHECKS_PASSED elevator horizontal rotation acceleration reversal stop geometry-epoch");
    }

    private static void physicsPassenger(ServerLevel level) {
        var container=SubLevelContainer.getContainer(level); var system=container.physicsSystem();
        var pose=new Pose3d(); pose.position().set(8,299.5,8);
        var body=(dev.ryanhcode.sable.sublevel.ServerSubLevel)container.allocateSubLevel(UUID.randomUUID(),24,0,pose);
        var plot=body.getPlot(); plot.newEmptyChunk(plot.getCenterChunk());
        for(int x=-1;x<=1;x++) for(int z=-1;z<=1;z++) plot.getEmbeddedLevelAccessor().setBlock(new BlockPos(x,0,z),Blocks.STONE.defaultBlockState(),3);
        var publication=cc.sighs.gravityengine.api.GravityEngineApi.publish(level,
                cc.sighs.gravityengine.api.GravityFieldDefinition.block(
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("gravityengine","physics_passenger"),BlockPos.ZERO,
                        cc.sighs.gravityengine.api.field.GravityFields.zeroGravity(),
                        cc.sighs.gravityengine.api.field.GravityFields.infiniteInfluence(),
                        cc.sighs.gravityengine.api.field.GravityFieldCompositionMode.OVERRIDE,1));
        container.addForceLoadTicket(body,dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType.COMMAND_FORCED,net.minecraft.util.Unit.INSTANCE);
        long originalTime=level.getGameTime();
        try {
            for(int settle=0;settle<3;settle++) system.tick(container);
            // The passenger is spawned after initialization, at this completed
            // pose. Its first landing does not replay a pre-spawn interval.
            body.updateLastPose(); body.updateBoundingBox();
            Player actor=new Player(level,BlockPos.ZERO,0,new GameProfile(UUID.randomUUID(),"physics-passenger")) {
                @Override public boolean isSpectator(){return false;}
                @Override public boolean isCreative(){return false;}
                @Override public boolean isControlledByLocalInstance(){return true;}

            };
            // The zero field suspends the rigid body; the passenger separately
            // owns ordinary gravity, so this is a grounded carry test.
            cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.applyDirectAssignment(
                    actor,cc.sighs.gravityengine.gravity.GravityState.DEFAULT);
            actor.setPos((body.boundingBox().minX()+body.boundingBox().maxX())*.5,body.boundingBox().maxY()+.1,(body.boundingBox().minZ()+body.boundingBox().maxZ())*.5); actor.setDeltaMovement(0,-.2,0); actor.travel(Vec3.ZERO);
            var runtime=cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess.cast(actor).gravityengine$gravityComponent().operationState();
            if(runtime.persistentSupportState()==null) throw new AssertionError("real physics floor must establish support bounds="+body.boundingBox()+" pose="+body.logicalPose()+" actor="+actor.position());
            var handle=system.getPhysicsHandle(body);
            for(double speed:new double[]{2,4,-2,0}) {
                var previous=handle.getLinearVelocity(new org.joml.Vector3d());
                handle.addLinearAndAngularVelocity(new org.joml.Vector3d(speed,0,0).sub(previous),new org.joml.Vector3d());
                ((net.minecraft.world.level.storage.ServerLevelData)level.getLevelData()).setGameTime(level.getGameTime()+1);
                system.tick(container); body.updateBoundingBox();
                var carry=cc.sighs.gravityengine.gravity.integration.EngineSupportTransportIntegration.preflight(actor,runtime,level.getGameTime(),1).orElseThrow().displacement();
                var before=actor.position(); actor.travel(Vec3.ZERO);
                var actual=cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter.toVec3d(actor.position().subtract(before));
                if(actual.distanceSquared(carry)>1e-10) throw new AssertionError("physics tick passenger mismatch: "+actual+" carry="+carry);
                if(speed!=0 && Math.signum(carry.x())!=Math.signum(speed)) throw new AssertionError("physics backend did not advance platform");
            }
            // Walkoff through actual travel; no post-solve position attachment.
            actor.setDeltaMovement(3,0,0); actor.travel(Vec3.ZERO);
            if(runtime.persistentSupportState()!=null || runtime.supportVelocityContribution().lengthSquared()!=0)
                throw new AssertionError("walkoff retained platform provenance");
            System.out.println("SABLE_PHYSICS_PASSENGER_PASSED acceleration reversal stop walkoff");
        } finally {
            ((net.minecraft.world.level.storage.ServerLevelData)level.getLevelData()).setGameTime(originalTime);
            publication.close(); container.removeForceLoadTicket(body,dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType.COMMAND_FORCED,net.minecraft.util.Unit.INSTANCE);
            container.removeSubLevel(body,SubLevelRemovalReason.REMOVED); container.processSubLevelRemovals();
        }
    }

    private static void gravity(ServerLevel level) {
        var container = SubLevelContainer.getContainer(level);
        var system = container.physicsSystem();
        String previous = System.getProperty("gravityengine.sableGravity");
        try {
            for (int mode = 0; mode < 4; mode++) {
                var pose = new Pose3d();
                pose.position().set(8, 300, 8);
                pose.orientation().rotateZ(.4);
                var body = container.allocateSubLevel(UUID.randomUUID(), mode + 1, 0, pose);
                var plot = body.getPlot();
                plot.newEmptyChunk(plot.getCenterChunk());
                plot.getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), 3);
                cc.sighs.gravityengine.api.FieldPublication publication = null;
                try {
                    System.setProperty("gravityengine.sableGravity", mode == 3 ? "false" : "true");
                    if (mode != 0) {
                        var acceleration = mode == 2 ? new cc.sighs.gravityengine.api.math.Vec3d(.01, 0, 0)
                                : cc.sighs.gravityengine.api.math.Vec3d.ZERO;
                        publication = cc.sighs.gravityengine.api.GravityEngineApi.publish(level,
                                cc.sighs.gravityengine.api.GravityFieldDefinition.block(
                                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("gravityengine", "sable_test"),
                                        BlockPos.ZERO, query -> new cc.sighs.gravityengine.api.field.GravityFieldSample(acceleration),
                                        cc.sighs.gravityengine.api.field.GravityFields.infiniteInfluence(),
                                        cc.sighs.gravityengine.api.field.GravityFieldCompositionMode.OVERRIDE, 1));
                    }
                    var serverBody = (dev.ryanhcode.sable.sublevel.ServerSubLevel)body;
                    body.updateLastPose();
                    body.updateBoundingBox();
                    System.out.println("SABLE_MASS mass=" + serverBody.getMassTracker().getMass()
                            + " center=" + serverBody.getMassTracker().getCenterOfMass());
                    container.addForceLoadTicket(serverBody,
                            dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType.COMMAND_FORCED,
                            net.minecraft.util.Unit.INSTANCE);
                    for (int tick = 0; tick < 3; tick++) system.tick(container);
                    var velocity = system.getPhysicsHandle((dev.ryanhcode.sable.sublevel.ServerSubLevel)body).getLinearVelocity(new org.joml.Vector3d());
                    System.out.println("SABLE_GRAVITY mode=" + mode + " velocityBlocksPerSecond=" + velocity);
                    if (mode == 0 || mode == 3) {
                        if (velocity.y >= -.1) throw new AssertionError("native Sable gravity not restored: " + velocity);
                    } else if (mode == 1) {
                        if (velocity.length() > 1e-4) throw new AssertionError("zero field did not replace gravity: " + velocity);
                    } else if (Math.abs(velocity.x - .6) > .015 || Math.abs(velocity.y) > 1e-4
                            || Math.abs(velocity.z) > 1e-4) {
                        throw new AssertionError("Sable gravity units/reference/double application: " + velocity);
                    }
                } finally {
                    if (publication != null) publication.close();
                    container.removeForceLoadTicket((dev.ryanhcode.sable.sublevel.ServerSubLevel)body,
                            dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType.COMMAND_FORCED,
                            net.minecraft.util.Unit.INSTANCE);
                    container.removeSubLevel(body, SubLevelRemovalReason.REMOVED);
                    container.processSubLevelRemovals();
                }
            }
        } finally {
            if (previous == null) System.clearProperty("gravityengine.sableGravity");
            else System.setProperty("gravityengine.sableGravity", previous);
        }
    }

}
