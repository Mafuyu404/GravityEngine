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
    static java.util.Set<String> run(ServerLevel level) {
        var completed = new java.util.LinkedHashSet<String>();
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
        delayedEndpoints(level);
        completed.add("delayed-endpoints");
        landingCommits(level);
        completed.add("landing-commit");
        contacts(level);
        completed.add("contacts"); completed.add("context");
        coverageAndModes(level);
        completed.add("modes");
        passivePlatforms(level);
        fallingMaterialResponse(level);
        completed.add("passive-entities");
        platforms(level);
        completed.add("transport"); completed.add("continuity");
        gravity(level);
        completed.add("gravity");
        distributedGravity(level);
        completed.add("distributed-gravity");
        publicationLifecycle(level);
        completed.add("publication-lifecycle");
        transformedEnvironment(level);
        completed.add("environment");
        physicsPassenger(level);
        completed.add("physics");
        System.out.println("SABLE_RUNTIME_CHECKS_PASSED static-geometry default-gravity single-solver removal");
        return java.util.Set.copyOf(completed);
    }
    private static void transformedEnvironment(ServerLevel level) {
        var container=SubLevelContainer.getContainer(level);
        var pose=new Pose3d();pose.position().set(96,360,96);
        var body=container.allocateSubLevel(UUID.randomUUID(),51,0,pose);
        var plot=body.getPlot();plot.newEmptyChunk(plot.getCenterChunk());
        body.logicalPose().rotationPoint().set(plot.getCenterBlock().getX(),plot.getCenterBlock().getY(),plot.getCenterBlock().getZ());
        try {
            // Keep a massive structural block: native Sable removes empty rigid bodies.
            plot.getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO,Blocks.STONE.defaultBlockState(),18);
            for(int x=2;x<=6;x++) for(int y=2;y<=6;y++) for(int z=2;z<=6;z++)
                plot.getEmbeddedLevelAccessor().setBlock(new BlockPos(x,y,z),Blocks.WATER.defaultBlockState(),18);
            for(double angle:new double[]{0,Math.PI/2,Math.PI,.43}) {
                body.logicalPose().orientation().rotationZ(angle);
                body.updateLastPose();body.updateBoundingBox();
                Player actor=new Player(level,BlockPos.ZERO,0,new GameProfile(UUID.randomUUID(),"fluid-plot")) {
                    @Override public boolean isSpectator(){return false;}
                    @Override public boolean isCreative(){return false;}
                };
                var origin=plot.getCenterBlock();
                var world=body.logicalPose().transformPosition(new org.joml.Vector3d(origin.getX()+4.5,origin.getY()+4.5,origin.getZ()+4.5),new org.joml.Vector3d());
                if(Math.abs(world.x-96)>10 || Math.abs(world.z-96)>10)
                    throw new AssertionError("fixture plot transform="+world+" pivot="+body.logicalPose().rotationPoint());
                for(int x=(int)Math.floor(world.x)-2;x<world.x+3;x+=2)
                    for(int z=(int)Math.floor(world.z)-2;z<world.z+3;z+=2) level.getChunk(x>>4,z>>4);
                var down=new cc.sighs.gravityengine.api.math.Vec3d(1,-2,.5).normalized();
                actor.setPos(world.x+down.x()*.9,world.y+down.y()*.9,world.z+down.z()*.9);
                cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.applyDirectAssignment(actor,
                        new cc.sighs.gravityengine.gravity.GravityState(down,.08));
                var snapshot=cc.sighs.gravityengine.gravity.integration.MovementEnvironmentCapture.captureOrCurrent(actor);
                if(snapshot.fluids().isEmpty()) throw new AssertionError("rotated plot water missing angle="+angle);
                var normal=body.logicalPose().orientation().transform(new org.joml.Vector3d(0,1,0));
                for(var region:snapshot.fluids()) {
                    if(region.volume().orientation().axisY().distance(new cc.sighs.gravityengine.api.math.Vec3d(normal.x,normal.y,normal.z))>1e-8)
                        throw new AssertionError("fluid surface was aligned to character gravity instead of plot geometry");
                    if(!cc.sighs.gravityengine.gravity.integration.compat.sable.SableMovementCompatibility.isPlotPosition(level,region.address()))
                        throw new AssertionError("fluid lost its native plot address");
                }
                actor.baseTick();
                if(!actor.isInFluidType(net.neoforged.neoforge.common.NeoForgeMod.WATER_TYPE.value())
                        || !actor.isEyeInFluid(net.minecraft.tags.FluidTags.WATER))
                    throw new AssertionError("native body/eye fluid metadata missed rotated volume angle="+angle);
                level.getChunk(120>>4,120>>4);
                actor.setPos(120,360,120);actor.baseTick();
                if(actor.isInFluidType() || actor.isEyeInFluid(net.minecraft.tags.FluidTags.WATER))
                    throw new AssertionError("leaving plot water retained stale native immersion");
            }
            for(var block:new net.minecraft.world.level.block.Block[]{Blocks.LADDER,Blocks.VINE,Blocks.SCAFFOLDING}) {
                var climbState=block==Blocks.VINE?block.defaultBlockState().setValue(net.minecraft.world.level.block.VineBlock.NORTH,true)
                        :block.defaultBlockState();
                for(int x=2;x<=6;x++) for(int y=2;y<=6;y++) for(int z=2;z<=6;z++)
                    plot.getEmbeddedLevelAccessor().setBlock(new BlockPos(x,y,z),climbState,18);
                body.updateLastPose();body.updateBoundingBox();
                Player actor=new Player(level,BlockPos.ZERO,0,new GameProfile(UUID.randomUUID(),"climb-plot")) {
                    @Override public boolean isSpectator(){return false;}
                    @Override public boolean isCreative(){return false;}
                };
                var origin=plot.getCenterBlock();
                var foot=body.logicalPose().transformPosition(new org.joml.Vector3d(origin.getX()+4.5,origin.getY()+4.5,origin.getZ()+4.5),new org.joml.Vector3d());
                actor.setPos(foot.x,foot.y,foot.z);
                cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.applyDirectAssignment(actor,
                        new cc.sighs.gravityengine.gravity.GravityState(new cc.sighs.gravityengine.api.math.Vec3d(0,-1,0),.04));
                var snapshot=cc.sighs.gravityengine.gravity.integration.MovementEnvironmentCapture.captureOrCurrent(actor);
                if(snapshot.climbables().isEmpty() || snapshot.climbables().getFirst().scaffold()!=(block==Blocks.SCAFFOLDING))
                    throw new AssertionError("native climb eligibility/plot address missing "+block
                            +" actual="+plot.getEmbeddedLevelAccessor().getBlockState(new BlockPos(4,4,4))
                            +" removed="+body.isRemoved()+" bounds="+plot.getBoundingBox()+" foot="+foot
                            +" body="+cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry.body(actor));
                try(var scope=cc.sighs.gravityengine.gravity.integration.MovementEnvironmentCapture.open(actor,snapshot)) {
                    if(!actor.onClimbable()) throw new AssertionError("native climb consumption missed captured geometry "+block);
                }
            }
        } finally {
            container.removeSubLevel(body,SubLevelRemovalReason.REMOVED);container.processSubLevelRemovals();
        }
        System.out.println("SABLE_ENVIRONMENT_PASSED rotated-volume surface native-immersion physical-eye exit ladder vine scaffold");
    }
    private static void delayedEndpoints(ServerLevel level) {
        var container = SubLevelContainer.getContainer(level);
        var pose = new Pose3d();
        pose.position().set(64, 299.5, 64);
        var body = container.allocateNewSubLevel(pose);
        var plot = body.getPlot();
        plot.newEmptyChunk(plot.getCenterChunk());
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
            plot.getEmbeddedLevelAccessor().setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
        long originalTime = level.getGameTime();
        try {
            for (boolean custom : new boolean[] { false, true }) for (int mode = 0; mode < 3; mode++) {
                body.logicalPose().position().set(64, 299.5, 64);
                body.logicalPose().orientation().identity();
                body.updateLastPose();
                body.updateBoundingBox();
                int motion = mode;
                BodyAuthorityChecks.delayedSablePacket(level, custom, () -> {
                    ((net.minecraft.world.level.storage.ServerLevelData) level.getLevelData()).setGameTime(originalTime + 5);
                    body.updateLastPose();
                    if (motion == 0) body.logicalPose().position().add(0, .5, 0);
                    else if (motion == 1) body.logicalPose().position().add(.5, 0, 0);
                    else body.logicalPose().orientation().rotateZ(.2);
                    body.updateBoundingBox();
                });
                ((net.minecraft.world.level.storage.ServerLevelData) level.getLevelData()).setGameTime(originalTime);
            }
            System.out.println("SABLE_DELAYED_ENDPOINTS_PASSED cases=6 elevator translation rotation corrections=0");
        } finally {
            ((net.minecraft.world.level.storage.ServerLevelData) level.getLevelData()).setGameTime(originalTime);
            container.removeSubLevel(body, SubLevelRemovalReason.REMOVED);
            container.processSubLevelRemovals();
        }
    }

    private static void publicationLifecycle(ServerLevel level) {
        var container = SubLevelContainer.getContainer(level);
        var pose = new Pose3d(); pose.position().set(80, 280, 80);
        var body = (dev.ryanhcode.sable.sublevel.ServerSubLevel)container.allocateSubLevel(UUID.randomUUID(), 49, 0, pose);
        var plot = body.getPlot();
        var actor = new ContactBehaviorChecks.Actor(level);
        try {
            plot.newEmptyChunk(plot.getCenterChunk());
            plot.getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), 3);
            body.buildMassTracker(); body.updateMergedMassData(1);
            body.updateLastPose(); body.updateBoundingBox();
            var before = capture(level, actor, body).getFirst();
            var points = ((cc.sighs.gravityengine.gravity.integration.compat.sable.SableMassPoints.Access)
                    body.getSelfMassTracker()).gravityengine$massPoints().snapshot();
            ContactBehaviorChecks.check(points != null && points.size() == 1, "native build captures sparse mass");
            plot.getEmbeddedLevelAccessor().setBlock(new BlockPos(2,0,0), Blocks.STONE.defaultBlockState(), 3);
            body.updateMergedMassData(1); body.updateBoundingBox();
            var after = capture(level, actor, body).stream().filter(p -> p.primitiveId()==before.primitiveId()).findFirst().orElseThrow();
            ContactBehaviorChecks.check(before.motion().continuityEpoch()==after.motion().continuityEpoch(), "COM rebase retains epoch");
            ContactBehaviorChecks.check(before.bodyAt(1).center().distance(after.bodyAt(1).center()) < 1e-7,
                    "COM rebase preserves world geometry");
            ContactBehaviorChecks.check(after.motion().maximumPointDisplacement(after.localBody()) < 1e-7,
                    "COM rebase creates no artificial sweep");
            var updated = ((cc.sighs.gravityengine.gravity.integration.compat.sable.SableMassPoints.Access)
                    body.getSelfMassTracker()).gravityengine$massPoints().snapshot();
            ContactBehaviorChecks.check(updated != null && updated.size()==2 && points.size()==1,
                    "native edit replaces sparse evidence, preserving captured snapshot");
            var handle = container.physicsSystem().getPhysicsHandle(body);
            handle.teleport(new org.joml.Vector3d(body.logicalPose().position()).add(20,0,0), body.logicalPose().orientation());
            body.updateBoundingBox();
            var teleported = capture(level, actor, body).stream().filter(p -> p.primitiveId()==before.primitiveId()).findFirst().orElseThrow();
            ContactBehaviorChecks.check(teleported.motion().continuityEpoch()!=after.motion().continuityEpoch(), "native teleport retires support continuity");
            ContactBehaviorChecks.check(teleported.motion().maximumPointDisplacement(teleported.localBody())==0,
                    "native teleport is not continuous travel");
            var local = plot.toLocal(plot.getCenterChunk());
            var holder = plot.getChunkHolder(local);
            plot.addChunkHolder(local, holder, false);
            ContactBehaviorChecks.check(capture(level,actor,body).stream().anyMatch(p -> p.primitiveId()==before.primitiveId()),
                    "same chunk holder keeps geometry identity");
            var replacement = dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder.create(level, plot.getCenterChunk(),
                    plot.getLightEngine(), holder.getChunk());
            plot.addChunkHolder(local, replacement, false);
            ContactBehaviorChecks.check(capture(level,actor,body).stream().noneMatch(p -> p.primitiveId()==before.primitiveId()),
                    "replacement chunk holder retires geometry identity");
            System.out.println("SABLE_PUBLICATION_LIFECYCLE_PASSED sparse-build edit rebase teleport chunk-replacement");
        } finally {
            container.removeSubLevel(body, SubLevelRemovalReason.REMOVED); container.processSubLevelRemovals();
        }
    }

    private static void landingCommits(ServerLevel level) {
        var container = SubLevelContainer.getContainer(level);
        var pose = new Pose3d(); pose.position().set(8,299.5,8);
        var body = container.allocateSubLevel(UUID.randomUUID(),33,0,pose);
        var plot = body.getPlot(); plot.newEmptyChunk(plot.getCenterChunk());
        long tick = level.getGameTime();
        int cases = 0;
        try {
            for (var block : new net.minecraft.world.level.block.Block[]{Blocks.STONE, Blocks.SLIME_BLOCK, Blocks.RED_BED})
                for (double speed : new double[]{0,.1}) for (boolean travel : new boolean[]{false,true})
                    for (boolean suppress : new boolean[]{false,true})
                    for (boolean drag : travel ? new boolean[]{false,true} : new boolean[]{false}) {
                plot.getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO,block.defaultBlockState(),18);
                body.updateLastPose(); body.updateBoundingBox();
                var actor = new ContactBehaviorChecks.Actor(level);
                actor.setPos(12,298.6,8);
                cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.applyDirectAssignment(actor,
                        new cc.sighs.gravityengine.gravity.GravityState(new cc.sighs.gravityengine.api.math.Vec3d(-1,0,0),.08));
                var bounds = capture(level,actor,body).stream().map(p -> p.bodyAt(0).enclosingAabb())
                        .max(java.util.Comparator.comparingDouble(cc.sighs.gravityengine.math.geometry.Aabb3d::maxX)).orElseThrow();
                actor.setPos(bounds.maxX()+1.1,(bounds.minY()+bounds.maxY())*.5-actor.getBbHeight()*.5,
                        (bounds.minZ()+bounds.maxZ())*.5);
                actor.setShiftKeyDown(suppress); actor.setNoGravity(true); actor.setDiscardFriction(!drag);
                body.logicalPose().position().add(speed,0,0); body.updateBoundingBox();
                actor.setDeltaMovement(-.5,0,0);
                if (travel) actor.travel(Vec3.ZERO); else actor.move(MoverType.SELF,actor.getDeltaMovement());
                String label = block+" speed="+speed+" travel="+travel+" suppress="+suppress+" drag="+drag;
                ContactBehaviorChecks.check(actor.observedMove != null && actor.observedMove.terminalGrounded(),
                        "fixture must reach terminal geometry "+label+" "+actor.observedMove);
                double elasticity = suppress || block==Blocks.STONE ? 0 : block==Blocks.RED_BED ? .66F : 1;
                double expected = speed+(.5+speed)*elasticity*(drag ? .98 : 1);
                ContactBehaviorChecks.check(Math.abs(actor.getDeltaMovement().x-expected)<1e-6,
                        "normal response "+label+" expected="+expected+" actual="+actor.getDeltaMovement());
                var runtime = cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess.cast(actor).gravityengine$gravityComponent().operationState();
                if (elasticity>0) {
                    ContactBehaviorChecks.check(runtime.persistentSupportState()==null && runtime.restingContactSnapshot()==null,
                            "bounce cannot retain support "+label);
                    ContactBehaviorChecks.check(runtime.supportVelocityContribution().lengthSquared()==0,"bounce clears credit without deleting momentum");
                    ((net.minecraft.world.level.storage.ServerLevelData)level.getLevelData()).setGameTime(level.getGameTime()+1);
                    body.updateLastPose(); body.logicalPose().position().add(.05,0,0); body.updateBoundingBox();
                    ContactBehaviorChecks.check(cc.sighs.gravityengine.gravity.integration.EngineSupportTransportIntegration
                            .preflight(actor,runtime,level.getGameTime(),1).isEmpty(),"bounce has no next-tick transport");
                    var before = actor.position(); var velocity = actor.getDeltaMovement();
                    actor.move(MoverType.SELF,velocity);
                    ContactBehaviorChecks.check(actor.position().subtract(before).distanceTo(velocity)<1e-6,
                            "next move cannot add stale carry "+label);
                    ContactBehaviorChecks.check(actor.getDeltaMovement().distanceTo(velocity)<1e-6,"no duplicate release "+label);
                } else {
                    ContactBehaviorChecks.check(runtime.persistentSupportState()!=null && runtime.restingContactSnapshot()!=null,
                            "ordinary/suppressed contact retains support "+label);
                }
                cases++;
            }
            // Spin around the contact normal: nonzero off-center surface velocity is
            // tangential and must be inherited at that point, not the rigid COM.
            plot.getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO,Blocks.SLIME_BLOCK.defaultBlockState(),18);
            body.updateLastPose(); body.updateBoundingBox();
            var actor = new ContactBehaviorChecks.Actor(level); actor.setPos(16,298.6,8);
            cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.applyDirectAssignment(actor,
                    new cc.sighs.gravityengine.gravity.GravityState(new cc.sighs.gravityengine.api.math.Vec3d(-1,0,0),.08));
            var bounds = capture(level,actor,body).getFirst().bodyAt(0).enclosingAabb();
            actor.setPos(bounds.maxX()+1.1,(bounds.minY()+bounds.maxY())*.5+.15-actor.getBbHeight()*.5,
                    (bounds.minZ()+bounds.maxZ())*.5);
            body.logicalPose().orientation().rotateX(.03); body.updateBoundingBox();
            actor.setDeltaMovement(-.5,0,0); actor.move(MoverType.SELF,actor.getDeltaMovement());
            var contact = actor.observedMove.supportContact().orElseThrow();
            ContactBehaviorChecks.check(Math.abs(contact.surfaceVelocity().z())>1e-4,"rotating contact has off-center point speed");
            ContactBehaviorChecks.check(Math.abs(actor.getDeltaMovement().x-.5)<1e-6,"rotating normal bounce");
            ContactBehaviorChecks.check(Math.abs(actor.getDeltaMovement().z-contact.surfaceVelocity().z())<1e-6,
                    "rotating bounce inherits contact point tangent speed once");
            System.out.println("SABLE_LANDING_COMMIT_CHECKS_PASSED cases="+cases+" move travel stone slime bed suppression separation next-tick rotation");
        } finally {
            ((net.minecraft.world.level.storage.ServerLevelData)level.getLevelData()).setGameTime(tick);
            container.removeSubLevel(body,SubLevelRemovalReason.REMOVED); container.processSubLevelRemovals();
        }
    }

    private static void contacts(ServerLevel level) {
        var container = SubLevelContainer.getContainer(level);
        var pose = new Pose3d(); pose.position().set(8, 299.5, 8);
        var body = container.allocateSubLevel(UUID.randomUUID(), 31, 0, pose);
        var plot = body.getPlot(); plot.newEmptyChunk(plot.getCenterChunk());
        var actor = new ContactBehaviorChecks.Actor(level);
        try {
            ContactBehaviorChecks.staticMaterial(level, actor);
            for (var block : new net.minecraft.world.level.block.Block[]{Blocks.STONE, Blocks.ICE, Blocks.SOUL_SAND, Blocks.SLIME_BLOCK,
                    ControlBoundaryChecks.CONTACT_BLOCK.get()}) {
                plot.getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO, block.defaultBlockState(), 3);
                body.updateLastPose(); body.updateBoundingBox();
                actor.setPos(8.5, 300.2, 8);
                actor.setDeltaMovement(.1, -.5, 0); actor.fallDistance = 3;
                ControlBoundaryChecks.CONTACT_BLOCK.get().reset();
                actor.move(MoverType.SELF, actor.getDeltaMovement());
                var resolved = java.util.Objects.requireNonNull(actor.landing, "operation-local landing material");
                var contact = resolved.contact();
                ContactBehaviorChecks.check(resolved.state().is(block), "dynamic material " + block);
                ContactBehaviorChecks.check(!resolved.position().equals(actor.blockPosition().below()), "dynamic address is plot storage");
                ContactBehaviorChecks.check(resolved.material(actor).friction() == block.getFriction(), "dynamic friction " + block);
                if (block == Blocks.STONE || block == Blocks.ICE) {
                    actor.setDeltaMovement(.1, 0, 0);
                    actor.travel(Vec3.ZERO);
                    ContactBehaviorChecks.check(Math.abs(actor.getDeltaMovement().x - .1 * (.91F * block.getFriction())) < 1e-7,
                            "travel consumes dynamic friction " + block + " velocity=" + actor.getDeltaMovement());
                }
                if (block == Blocks.SOUL_SAND) ContactBehaviorChecks.check(Math.abs(actor.getDeltaMovement().x-.04)<1e-7, "soul sand damping once");
                if (block == Blocks.SLIME_BLOCK) {
                    ContactBehaviorChecks.check(actor.getDeltaMovement().y>.49, "dynamic slime bounce once");
                    var runtime = cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess.cast(actor).gravityengine$gravityComponent().operationState();
                    ContactBehaviorChecks.check(runtime.persistentSupportState()==null && runtime.restingContactSnapshot()==null,
                            "stationary slime bounce must clear cross-tick support despite terminal geometry");
                }
                if (block == ControlBoundaryChecks.CONTACT_BLOCK.get()) {
                    var recorded = ControlBoundaryChecks.CONTACT_BLOCK.get();
                    ContactBehaviorChecks.check(recorded.falls==1 && recorded.responses==1 && recorded.steps==1,
                            "callback counts fall="+recorded.falls+" response="+recorded.responses+" step="+recorded.steps);
                    var standing = capture(level, actor, body);
                    Player other = new Player(level, BlockPos.ZERO, 0, new GameProfile(UUID.randomUUID(), "other-context")) {
                        @Override public boolean isSpectator() { return false; }
                        @Override public boolean isCreative() { return false; }
                    };
                    other.setPos(actor.position()); other.setShiftKeyDown(true);
                    var descending = capture(level, other, body);
                    ContactBehaviorChecks.check(!standing.getFirst().localBody().equals(descending.getFirst().localBody()), "actor context changes geometry");
                    ContactBehaviorChecks.check(cc.sighs.gravityengine.gravity.integration.BlockContactResolver.resolve(other, contact).isEmpty(),
                            "resolve must reject another subject's different shape");
                    var packetShapes = cc.sighs.gravityengine.gravity.integration.collision.MinecraftCollisionSceneCapture.captureRigidOccupancy(other,
                            cc.sighs.gravityengine.gravity.minecraft.collision.MinecraftCollisionGeometryAdapter.toAabb3d(other.getBoundingBox().expandTowards(0,-1,0)),
                            cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext.fullTick(level.getGameTime(),0),
                            new cc.sighs.gravityengine.gravity.collision.CollisionWorkTracker(cc.sighs.gravityengine.gravity.collision.CollisionWorkBudget.defaults()));
                    ContactBehaviorChecks.check(packetShapes.stream().anyMatch(p -> p.localBody().equals(descending.getFirst().localBody())),
                            "packet capture agrees with actor geometry");
                    ContactBehaviorChecks.check(cc.sighs.gravityengine.gravity.integration.collision.MinecraftCollisionSubject.current(level)==null,
                            "collision subject scope must not leak");
                    actor.setShiftKeyDown(false);
                    ContactBehaviorChecks.check(standing.getFirst().localBody().equals(capture(level, actor, body).getFirst().localBody()), "other context cannot poison shape");
                }
            }
            var retired = ContactBehaviorChecks.retainedContact(actor);
            plot.getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO, Blocks.SLIME_BLOCK.defaultBlockState(), 3);
            body.updateLastPose(); body.logicalPose().position().add(0,.1,0); body.updateBoundingBox();
            actor.setPos(8.5,300.2,8); actor.setDeltaMovement(0,-.5,0);
            actor.move(MoverType.SELF,actor.getDeltaMovement());
            ContactBehaviorChecks.check(Math.abs(actor.getDeltaMovement().y-.7)<1e-6,
                    "rising slime reflects relative impact and restores surface velocity once: " + actor.getDeltaMovement());
            ContactBehaviorChecks.check(cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess.cast(actor)
                    .gravityengine$gravityComponent().operationState().persistentSupportState()==null,
                    "instantaneous bounce must not fabricate persistent support");
            container.removeSubLevel(body, SubLevelRemovalReason.REMOVED);
            container.processSubLevelRemovals();
            ContactBehaviorChecks.check(cc.sighs.gravityengine.gravity.integration.BlockContactResolver.resolve(actor, retired).isEmpty(),
                    "removed support cannot fall back to world");
            System.out.println("CONTACT_BEHAVIOR_CHECKS_PASSED static dynamic ice soul-sand slime callbacks context removal");
        } finally {
            if (!body.isRemoved()) { container.removeSubLevel(body, SubLevelRemovalReason.REMOVED); container.processSubLevelRemovals(); }
        }
    }

    private static java.util.List<cc.sighs.gravityengine.gravity.collision.DynamicCollisionObstacleSnapshot> capture(
            ServerLevel level, net.minecraft.world.entity.Entity actor, dev.ryanhcode.sable.sublevel.SubLevel body) {
        var b = body.boundingBox();
        var bounds = new cc.sighs.gravityengine.math.geometry.Aabb3d(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ()).inflate(2);
        var time = cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext.fullTick(level.getGameTime(),0);
        try (var subject = cc.sighs.gravityengine.gravity.integration.collision.MinecraftCollisionSubject.open(actor)) {
            return cc.sighs.gravityengine.gravity.collision.provider.RigidCollisionPublicationRegistry.capture(level,
                    new cc.sighs.gravityengine.gravity.collision.provider.ExternalRigidCollisionQuery(bounds,bounds,time)).build().dynamicObstacles();
        }
    }

    private static void coverageAndModes(ServerLevel level) {
        var container = SubLevelContainer.getContainer(level);
        var pose = new Pose3d(); pose.position().set(8,299.5,8);
        var body = container.allocateSubLevel(UUID.randomUUID(),32,0,pose);
        var plot = body.getPlot(); plot.newEmptyChunk(plot.getCenterChunk());
        plot.getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO,Blocks.STONE.defaultBlockState(),3);
        body.updateLastPose(); body.updateBoundingBox();
        var fixed = new Pose3d(body.logicalPose());
        var actor = new ContactBehaviorChecks.Actor(level);
        var runtime = cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess.cast(actor).gravityengine$gravityComponent().operationState();
        long tick = level.getGameTime();
        try {
            for (int mode=1; mode<=7; mode++) {
                actor.mode=0; actor.getAbilities().flying=false;
                cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.updateBody(actor);
                body.logicalPose().set(fixed); body.updateLastPose(); body.updateBoundingBox();
                actor.setPos(8.5,300.2,8); actor.setDeltaMovement(0,-.5,0); actor.move(MoverType.SELF,actor.getDeltaMovement());
                ContactBehaviorChecks.retainedContact(actor);
                ((net.minecraft.world.level.storage.ServerLevelData)level.getLevelData()).setGameTime(level.getGameTime()+1);
                body.updateLastPose(); body.logicalPose().position().add(.1,0,0); body.updateBoundingBox();
                var before = actor.getDeltaMovement();
                actor.mode=mode; actor.getAbilities().flying=mode==5;
                cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.updateBody(actor);
                var released = actor.getDeltaMovement();
                ContactBehaviorChecks.check(runtime.persistentSupportState()==null && runtime.restingContactSnapshot()==null, "mode detaches support "+mode);
                ContactBehaviorChecks.check(Math.abs(released.x-before.x-.1)<1e-9, "mode inherits current platform velocity once "+mode+" "+released);
                cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.updateBody(actor);
                ContactBehaviorChecks.check(released.equals(actor.getDeltaMovement()), "idempotent mode check "+mode);
                var expectedMode = switch (mode) {
                    case 1 -> cc.sighs.gravityengine.gravity.runtime.GravityOperationState.MovementMode.SWIMMING;
                    case 2 -> cc.sighs.gravityengine.gravity.runtime.GravityOperationState.MovementMode.CLIMBING;
                    case 7 -> cc.sighs.gravityengine.gravity.runtime.GravityOperationState.MovementMode.ELYTRA;
                    default -> cc.sighs.gravityengine.gravity.runtime.GravityOperationState.MovementMode.NATIVE_FALLBACK;
                };
                ContactBehaviorChecks.check(runtime.movementMode()==expectedMode, "selected mode "+mode);
            }
            actor.mode=0; actor.getAbilities().flying=false;
            cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.updateBody(actor);
            body.logicalPose().set(fixed); body.updateLastPose();
            plot.getEmbeddedLevelAccessor().setBlock(new BlockPos(8,0,0),Blocks.SCAFFOLDING.defaultBlockState(),3);
            body.logicalPose().set(fixed); body.updateBoundingBox();
            actor.setPos(8.5,300.2,8); actor.setDeltaMovement(0,-.5,0); actor.move(MoverType.SELF,actor.getDeltaMovement());
            ContactBehaviorChecks.retainedContact(actor); // distant scaffolding cannot block an ordinary landing
            plot.getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO,Blocks.SCAFFOLDING.defaultBlockState(),3);
            body.logicalPose().set(fixed); body.updateLastPose(); body.updateBoundingBox();
            actor.setPos(8.5,body.boundingBox().maxY()+.1,8); actor.setDeltaMovement(0,-.5,0);
            var before = actor.position(); var velocity = actor.getDeltaMovement();
            actor.move(MoverType.SELF,velocity);
            ContactBehaviorChecks.check(actor.getY()<before.y, "scaffolding admits resolved downward travel");
            before=actor.position();
            var packet = cc.sighs.gravityengine.gravity.integration.collision.MinecraftCollisionSceneCapture.captureRigidOccupancyBounded(actor,
                    cc.sighs.gravityengine.gravity.minecraft.collision.MinecraftCollisionGeometryAdapter.toAabb3d(actor.getBoundingBox().expandTowards(velocity)),
                    cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext.fullTick(level.getGameTime(),0),
                    new cc.sighs.gravityengine.gravity.collision.CollisionWorkTracker(cc.sighs.gravityengine.gravity.collision.CollisionWorkBudget.defaults()));
            ContactBehaviorChecks.check(!packet.budgetExhausted(), "scaffolding has complete packet geometry coverage");
            plot.getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO,Blocks.STONE.defaultBlockState(),3);
            plot.getEmbeddedLevelAccessor().setBlock(new BlockPos(8,0,0),Blocks.AIR.defaultBlockState(),3);
            body.logicalPose().set(fixed); body.logicalPose().position().add(-32,0,0); body.updateLastPose();
            body.logicalPose().position().add(64,0,0); body.updateBoundingBox();
            actor.move(MoverType.SELF,velocity);
            ContactBehaviorChecks.check(before.equals(actor.position()), "fast crossing body outside old 16-block discovery cannot be missed");
            body.logicalPose().set(fixed); body.updateLastPose(); body.updateBoundingBox();
            actor.move(MoverType.SELF,velocity);
            var old = ContactBehaviorChecks.retainedContact(actor);
            var provider = cc.sighs.gravityengine.gravity.collision.provider.RigidCollisionPublicationRegistry.providers(level).stream()
                    .filter(p -> p.id().equals("gravityengine:sable")).findFirst().orElseThrow();
            cc.sighs.gravityengine.gravity.collision.provider.RigidCollisionPublicationRegistry.unregister(level,provider.id());
            cc.sighs.gravityengine.gravity.collision.provider.RigidCollisionPublicationRegistry.register(level,provider);
            capture(level,actor,body);
            ContactBehaviorChecks.check(cc.sighs.gravityengine.gravity.integration.BlockContactResolver.resolve(actor,old).isEmpty(), "reregister cannot revive support");
            actor.setDeltaMovement(0,-.01,0); actor.move(MoverType.SELF,actor.getDeltaMovement());
            old=ContactBehaviorChecks.retainedContact(actor);
            cc.sighs.gravityengine.gravity.integration.compat.sable.SableRigidCollisionProvider.unload(level);
            cc.sighs.gravityengine.gravity.integration.compat.sable.SableRigidCollisionProvider.update(body);
            capture(level,actor,body);
            ContactBehaviorChecks.check(cc.sighs.gravityengine.gravity.integration.BlockContactResolver.resolve(actor,old).isEmpty(), "provider unload/reload cannot revive support");
            actor.setDeltaMovement(0,-.01,0); actor.move(MoverType.SELF,actor.getDeltaMovement());
            old=ContactBehaviorChecks.retainedContact(actor);
            before=actor.position();
            body.updateLastPose(); body.logicalPose().scale().set(0,1,1); body.updateBoundingBox();
            actor.move(MoverType.SELF,new Vec3(0,-.1,0));
            ContactBehaviorChecks.check(before.equals(actor.position()), "zero scale refuses movement without invalid box construction");
            body.logicalPose().set(fixed); body.updateLastPose(); body.updateBoundingBox();
            capture(level,actor,body);
            ContactBehaviorChecks.check(cc.sighs.gravityengine.gravity.integration.BlockContactResolver.resolve(actor,old).isEmpty(), "restored local basis cannot revive support");
            System.out.println("CONTACT_MODE_COVERAGE_CHECKS_PASSED seven-modes single-release nearby-refusal distant-filter fast-crossing packet reregister reload scale-epoch");
        } finally {
            ((net.minecraft.world.level.storage.ServerLevelData)level.getLevelData()).setGameTime(tick);
            container.removeSubLevel(body,SubLevelRemovalReason.REMOVED); container.processSubLevelRemovals();
        }
    }

    private static void passivePlatforms(ServerLevel level) {
        var container = SubLevelContainer.getContainer(level);
        long tick = level.getGameTime();
        for (int kind = 0; kind < 3; kind++) {
            var pose = new Pose3d(); pose.position().set(8,299.5,8);
            var body = container.allocateSubLevel(UUID.randomUUID(),40 + kind,0,pose);
            var plot = body.getPlot(); plot.newEmptyChunk(plot.getCenterChunk());
            for (int x=-1;x<=1;x++) for (int z=-1;z<=1;z++)
                plot.getEmbeddedLevelAccessor().setBlock(new BlockPos(x,0,z),Blocks.STONE.defaultBlockState(),3);
            body.updateLastPose(); body.updateBoundingBox();
            net.minecraft.world.entity.Entity actor = kind == 0
                    ? new net.minecraft.world.entity.decoration.ArmorStand(level,8.5,300,8)
                    : net.minecraft.world.entity.item.FallingBlockEntity.fall(level,new BlockPos(8,310,8),Blocks.SAND.defaultBlockState());
            actor.setPos(8.5,300,8); actor.setNoGravity(true);
            cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.applyDirectAssignment(actor,
                    new cc.sighs.gravityengine.gravity.GravityState(new cc.sighs.gravityengine.api.math.Vec3d(0,-1,kind==2?0:.01),.04));
            actor.setDeltaMovement(0,-.01,0);
            var runtime = cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess.cast(actor).gravityengine$gravityComponent().operationState();
            try {
                passiveStep(actor);
                if (kind != 0) {
                    // Falling blocks are ballistic: native failed-installation consumes the actor.
                    ContactBehaviorChecks.check(actor.isRemoved(),"native dynamic impact lifecycle kind="+kind);
                    ContactBehaviorChecks.check(runtime.persistentSupportState()==null && runtime.supportVelocityContribution().lengthSquared()==0,
                            "falling actor cannot retain platform credit");
                    ContactBehaviorChecks.check(cc.sighs.gravityengine.gravity.integration.EngineSupportTransportIntegration
                            .preflight(actor,runtime,level.getGameTime(),1).isEmpty(),"falling actor has no carry preflight");
                    continue;
                }

                ContactBehaviorChecks.check(runtime.persistentSupportState()!=null,"passive Sable material anchor kind="+kind);
                for (int mode=0;mode<3;mode++) {
                    ((net.minecraft.world.level.storage.ServerLevelData)level.getLevelData()).setGameTime(level.getGameTime()+1);
                    body.updateLastPose();
                    if (mode==0) body.logicalPose().position().add(.08,0,0);
                    if (mode==1) body.logicalPose().position().add(0,.08,0);
                    if (mode==2) body.logicalPose().orientation().rotateY(.03);
                    body.updateBoundingBox();
                    var carry=cc.sighs.gravityengine.gravity.integration.EngineSupportTransportIntegration.preflight(actor,runtime,level.getGameTime(),1).orElseThrow().displacement();
                    var before=actor.position();
                    passiveStep(actor);
                    var actual=cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter.toVec3d(actor.position().subtract(before));
                    ContactBehaviorChecks.check(actual.distanceSquared(carry)<1e-8,"passive Sable single carry kind="+kind+" mode="+mode+" actual="+actual+" expected="+carry);
                    ContactBehaviorChecks.check(runtime.persistentSupportState()!=null,"passive Sable retains valid identity");
                }
                var retained=runtime.persistentSupportState();
                container.removeSubLevel(body,SubLevelRemovalReason.REMOVED);
                container.processSubLevelRemovals();
                passiveStep(actor);
                ContactBehaviorChecks.check(runtime.persistentSupportState()==null,"passive removal retires support kind="+kind);
                ContactBehaviorChecks.check(retained!=null,"fixture established support before removal");
            } finally {
                actor.discard();
                if (!body.isRemoved()) { container.removeSubLevel(body,SubLevelRemovalReason.REMOVED); container.processSubLevelRemovals(); }
            }
        }
        ((net.minecraft.world.level.storage.ServerLevelData)level.getLevelData()).setGameTime(tick);
        System.out.println("SABLE_PASSIVE_ENTITIES_PASSED armorstand_transport falling_impact_no_carry");
    }
    private static void fallingMaterialResponse(ServerLevel level) {
        var container=SubLevelContainer.getContainer(level);
        int slot=56;
        for(var material:new net.minecraft.world.level.block.Block[]{Blocks.STONE,Blocks.SLIME_BLOCK,Blocks.RED_BED}) {
            var pose=new Pose3d();pose.position().set(8,299.5,8);
            var body=container.allocateSubLevel(UUID.randomUUID(),slot++,0,pose);
            var plot=body.getPlot();plot.newEmptyChunk(plot.getCenterChunk());
            plot.getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO,material.defaultBlockState(),18);
            body.updateLastPose();body.updateBoundingBox();
            class ImpactActor extends net.minecraft.world.entity.item.FallingBlockEntity {
                Vec3 afterMove; int removals, broken;
                ImpactActor() { super(net.minecraft.world.entity.EntityType.FALLING_BLOCK,level); }
                @Override public void move(MoverType type,Vec3 movement) { super.move(type,movement); afterMove=getDeltaMovement(); }
                @Override public void remove(net.minecraft.world.entity.Entity.RemovalReason reason) { removals++; super.remove(reason); }
                @Override public void callOnBrokenAfterFall(net.minecraft.world.level.block.Block block,BlockPos pos) { broken++; super.callOnBrokenAfterFall(block,pos); }
            }
            var actor=new ImpactActor(); actor.setPos(8,310,8);
            try {
                cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.applyDirectAssignment(actor,
                        new cc.sighs.gravityengine.gravity.GravityState(new cc.sighs.gravityengine.api.math.Vec3d(-1,0,0),.04));
                var bounds=capture(level,actor,body).stream().map(p->p.bodyAt(0).enclosingAabb())
                        .max(java.util.Comparator.comparingDouble(cc.sighs.gravityengine.math.geometry.Aabb3d::maxX)).orElseThrow();
                actor.setPos(bounds.maxX()+.49+.2,(bounds.minY()+bounds.maxY())*.5-actor.getBbHeight()*.5,
                        (bounds.minZ()+bounds.maxZ())*.5);
                actor.setNoGravity(true);actor.setDeltaMovement(-.5,0,0);
                body.logicalPose().position().add(.1,0,0);body.updateBoundingBox();
                System.out.println("SABLE_FALLING_MATERIAL_CASE material="+material+" id="+actor.getId()+" bounds="+bounds);
                actor.tick();
                double elasticity=material==Blocks.STONE?0:material==Blocks.SLIME_BLOCK?.8:.66F*.8;
                double expected=.1+.6*elasticity;
                ContactBehaviorChecks.check(Math.abs(actor.afterMove.x-expected)<1e-6,
                        "falling Sable current-contact response once "+material+" v="+actor.afterMove+" expected="+expected);
                var runtime=cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess.cast(actor).gravityengine$gravityComponent().operationState();
                ContactBehaviorChecks.check(actor.isRemoved() && actor.removals==1 && actor.broken==1,
                        "native failed installation owns one discard/broken callback");
                ContactBehaviorChecks.check(runtime.persistentSupportState()==null,"falling Sable has no persistent support "+material);
                var extension=(dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension)actor;
                ContactBehaviorChecks.check(extension.sable$getCollisionInfo() instanceof cc.sighs.gravityengine.gravity.integration.compat.sable.SableCollisionOwnership.EngineCollisionInfo,
                        "falling Sable has one exact collision owner");
            } finally {actor.discard();container.removeSubLevel(body,SubLevelRemovalReason.REMOVED);container.processSubLevelRemovals();}
        }
        System.out.println("SABLE_FALLING_MATERIAL_PASSED stone slime bed moving-normal single-response separation");
    }

    private static void passiveStep(net.minecraft.world.entity.Entity entity) {
        if (entity instanceof net.minecraft.world.entity.decoration.ArmorStand stand) stand.travel(Vec3.ZERO);
        else entity.tick();
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
                var retained = ContactBehaviorChecks.retainedContact(actor);
                var block = cc.sighs.gravityengine.gravity.integration.BlockContactResolver.resolve(actor, retained).orElseThrow();
                // This fixture prescribes poses directly (the Rapier fixture below owns real physics).
                // A block edit updates Rapier's COM; keep this test's prescribed pose fixed to isolate geometry.
                var prescribed = new Pose3d(body.logicalPose());
                plot.getEmbeddedLevelAccessor().setBlock(new BlockPos(-1,0,-1), Blocks.ANDESITE.defaultBlockState(),3);
                body.logicalPose().set(prescribed);
                ContactBehaviorChecks.check(cc.sighs.gravityengine.gravity.integration.BlockContactResolver.resolve(actor, retained).isPresent(),
                        "unrelated edit must preserve support");
                level.setBlock(block.position(), Blocks.ICE.defaultBlockState(), 3);
                body.logicalPose().set(prescribed);
                var changedMaterial = cc.sighs.gravityengine.gravity.integration.BlockContactResolver.resolve(actor, retained).orElseThrow();
                ContactBehaviorChecks.check(changedMaterial.state().is(Blocks.ICE) && changedMaterial.material(actor).friction()==.98F,
                        "equal geometry keeps identity but refreshes material");
                level.setBlock(block.position(), Blocks.STONE_SLAB.defaultBlockState(),3);
                body.logicalPose().set(prescribed);
                ContactBehaviorChecks.check(cc.sighs.gravityengine.gravity.integration.BlockContactResolver.resolve(actor, retained).isEmpty(),
                        "contact geometry change must retire witness");
                level.setBlock(block.position(), Blocks.ICE.defaultBlockState(),3);
                body.logicalPose().set(prescribed);
                capture(level, actor, body);
                ContactBehaviorChecks.check(cc.sighs.gravityengine.gravity.integration.BlockContactResolver.resolve(actor, retained).isEmpty(),
                        "restoring block cannot resurrect retired primitive");
            } finally {
                ((net.minecraft.world.level.storage.ServerLevelData)level.getLevelData()).setGameTime(originalTime);
                container.removeSubLevel(body, SubLevelRemovalReason.REMOVED);
                container.processSubLevelRemovals();
            }
        }
        System.out.println("SABLE_PLATFORM_CHECKS_PASSED elevator horizontal rotation acceleration reversal stop local-geometry material-refresh");
    }

    private static void physicsPassenger(ServerLevel level) {
        var container=SubLevelContainer.getContainer(level); var system=container.physicsSystem();
        var pose=new Pose3d(); pose.position().set(8,299.5,8);
        var body=(dev.ryanhcode.sable.sublevel.ServerSubLevel)container.allocateSubLevel(UUID.randomUUID(),24,0,pose);
        var plot=body.getPlot(); plot.newEmptyChunk(plot.getCenterChunk());
        for(int x=-1;x<=1;x++) for(int z=-1;z<=1;z++) plot.getEmbeddedLevelAccessor().setBlock(new BlockPos(x,0,z),Blocks.STONE.defaultBlockState(),3);
        var publication=cc.sighs.gravityengine.api.GravityEngineApi.publish(level, com.example.examplemod.gravity.ProviderFixture.ID,
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

    /** Two separated masses distinguish a real tidal torque from COM-only gravity.
     * Compare angular momentum, rather than duplicating the bridge's inverse-inertia solver. */
    private static void distributedGravity(ServerLevel level) {
        for(double scale:new double[]{1,2}) distributedGravity(level,scale);
    }
    private static void distributedGravity(ServerLevel level,double scale) {
        var container = SubLevelContainer.getContainer(level);
        var system = container.physicsSystem();
        var pose = new Pose3d();
        pose.position().set(80, 300, 80);
        pose.orientation().rotateZ(.4);
        var body = (dev.ryanhcode.sable.sublevel.ServerSubLevel) container.allocateSubLevel(UUID.randomUUID(),52+(int)scale,0,pose);
        cc.sighs.gravityengine.api.FieldPublication publication = null;
        String previous = System.getProperty("gravityengine.sableGravity");
        try {
            System.setProperty("gravityengine.sableGravity", "true");
            var plot = body.getPlot();
            plot.newEmptyChunk(plot.getCenterChunk());
            plot.getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), 3);
            plot.getEmbeddedLevelAccessor().setBlock(new BlockPos(2, 0, 0), Blocks.STONE.defaultBlockState(), 3);
            body.updateLastPose();
            body.updateBoundingBox();
            var mass = body.getMassTracker();
            body.logicalPose().scale().set(scale);
            var center = body.logicalPose().transformPosition(mass.getCenterOfMass(), new org.joml.Vector3d());
            var handle = system.getPhysicsHandle(body);
            // An odd field about the COM has zero net field force but nonzero torque.
            publication = cc.sighs.gravityengine.api.GravityEngineApi.publish(level,
                    com.example.examplemod.gravity.ProviderFixture.ID,
                    cc.sighs.gravityengine.api.GravityFieldDefinition.block(
                            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("gravityengine", "sable_tidal_test"),
                            BlockPos.ZERO, query -> new cc.sighs.gravityengine.api.field.GravityFieldSample(
                                    new cc.sighs.gravityengine.api.math.Vec3d(0, .01 * (query.position().x() - center.x), 0)),
                            cc.sighs.gravityengine.api.field.GravityFields.infiniteInfluence(),
                            cc.sighs.gravityengine.api.field.GravityFieldCompositionMode.OVERRIDE, 1));
            var linearBefore = handle.getLinearVelocity(new org.joml.Vector3d());
            var angularBefore = handle.getAngularVelocity(new org.joml.Vector3d());
            int[] queries = {0};
            com.example.examplemod.gravity.ProviderFixture.secondCoverage(level, query -> {
                queries[0]++;
                return query.position().x() > center.x
                        ? cc.sighs.gravityengine.api.field.FieldCoverage.INCOMPLETE
                        : cc.sighs.gravityengine.api.field.FieldCoverage.COMPLETE;
            });
            cc.sighs.gravityengine.gravity.integration.compat.sable.SableGravityBridge.apply(body, system, handle, .025);
            ContactBehaviorChecks.check(queries[0] == 2, "both separated mass points sampled before incomplete rejection");
            ContactBehaviorChecks.check(handle.getLinearVelocity(new org.joml.Vector3d()).equals(linearBefore)
                            && handle.getAngularVelocity(new org.joml.Vector3d()).equals(angularBefore),
                    "incomplete late mass sample must not commit partial linear or angular correction");
            com.example.examplemod.gravity.ProviderFixture.secondCoverage(level,
                    query -> cc.sighs.gravityengine.api.field.FieldCoverage.COMPLETE);
            cc.sighs.gravityengine.gravity.integration.compat.sable.SableGravityBridge.apply(body, system, handle, .025);
            var deltaOmega = handle.getAngularVelocity(new org.joml.Vector3d()).sub(angularBefore);
            var orientation = body.logicalPose().orientation();
            var localOmega = new org.joml.Quaterniond(orientation).conjugate().transform(deltaOmega, new org.joml.Vector3d());
            var deltaMomentum = orientation.transform(mass.getInertiaTensor().transform(localOmega, new org.joml.Vector3d()));
            // Equal stone blocks are +/- one local X unit from their COM.
            // F_y = mass * 4 * r_x in seconds units; tau_z = totalMass * 4 * cos(.4)^2.
            double expectedLz = mass.getMass() * 4 * Math.pow(scale*Math.cos(.4), 2) * .025;
            ContactBehaviorChecks.check(deltaMomentum.distance(new org.joml.Vector3d(0, 0, expectedLz))
                            < Math.abs(expectedLz) * 1e-6,
                    "distributed world torque must match angular momentum change: " + deltaMomentum + " expected z=" + expectedLz);
            var deltaLinear = handle.getLinearVelocity(new org.joml.Vector3d()).sub(linearBefore);
            ContactBehaviorChecks.check(Math.abs(deltaLinear.x) < 1e-7 && Math.abs(deltaLinear.z) < 1e-7,
                    "odd tidal field must not introduce net horizontal velocity");
            System.out.println("SABLE_DISTRIBUTED_GRAVITY_PASSED rotated-two-mass torque incomplete-atomicity scale="+scale);
        } finally {
            com.example.examplemod.gravity.ProviderFixture.secondCoverage(level,
                    query -> cc.sighs.gravityengine.api.field.FieldCoverage.COMPLETE);
            if (publication != null) publication.close();
            if (previous == null) System.clearProperty("gravityengine.sableGravity");
            else System.setProperty("gravityengine.sableGravity", previous);
            container.removeSubLevel(body, SubLevelRemovalReason.REMOVED);
            container.processSubLevelRemovals();
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
                        publication = cc.sighs.gravityengine.api.GravityEngineApi.publish(level, com.example.examplemod.gravity.ProviderFixture.ID,
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
