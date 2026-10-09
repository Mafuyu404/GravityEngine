package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.collision.DynamicCollisionObstacleSnapshot;
import cc.sighs.gravityengine.gravity.collision.provider.ExternalRigidCollisionQuery;
import cc.sighs.gravityengine.gravity.collision.provider.RigidCollisionPublicationRegistry;
import cc.sighs.gravityengine.gravity.integration.collision.MinecraftCollisionSubject;
import cc.sighs.gravityengine.gravity.integration.compat.create.CreateRigidCollisionProvider;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.bearing.BearingContraption;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;

/** Real transformed Create entities and Sable plots. Tests prescribe motion,
 * then compare captured geometry with the native transform at each endpoint. */
final class CreateRuntimeChecks {
    static java.util.Set<String> run(ServerLevel level) {
        var container = SubLevelContainer.getContainer(level);
        var pose = new Pose3d(); pose.position().set(32, 300, 32);
        var parent = container.allocateNewSubLevel(pose);
        var plot = parent.getPlot(); plot.newEmptyChunk(plot.getCenterChunk());
        plot.getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), 3);
        parent.updateLastPose(); parent.updateBoundingBox();
        class Geometry extends BearingContraption {
            Geometry() {
                super(false, Direction.UP);
                blocks.put(BlockPos.ZERO, new StructureTemplate.StructureBlockInfo(BlockPos.ZERO, Blocks.ICE.defaultBlockState(), null));
                blocks.put(new BlockPos(2, 0, 0), new StructureTemplate.StructureBlockInfo(new BlockPos(2, 0, 0), Blocks.STONE.defaultBlockState(), null));
                bounds = new AABB(0, 0, 0, 3, 1, 1); anchor = BlockPos.ZERO;
                seats.add(new BlockPos(1,1,0));
            }
        }
        class Vehicle extends ControlledContraptionEntity {
            Vehicle() {
                super(BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.fromNamespaceAndPath("create", "stationary_contraption")), level);
                setContraption(new Geometry());
            }
            @Override protected void tickContraption() {} // fixture prescribes bearing motion
        }
        var vehicle = new Vehicle();
        var center = plot.getCenterBlock();
        vehicle.setPos(center.getX()+3, center.getY(), center.getZ());
        vehicle.xo = vehicle.getX(); vehicle.yo = vehicle.getY(); vehicle.zo = vehicle.getZ();
        var actor = new ContactBehaviorChecks.Actor(level); actor.setPos(32, 300, 32);
        try {
            ContactBehaviorChecks.check(level.addFreshEntity(vehicle), "native Create entity join");
            vehicle.tick(); // Native Sable initialization builds and attaches the contraption mass.
            ContactBehaviorChecks.check(CreateRigidCollisionProvider.publishes(vehicle), "native join registers Create source");
            vehicle.setAngle(45);
            parent.logicalPose().position().add(.3, .1, 0);
            parent.logicalPose().orientation().rotateY(.2);
            parent.updateBoundingBox();
            var time = KinematicStepContext.fullTick(level.getGameTime(), 0);
            var domain = new Aabb3d(16, 284, 16, 48, 316, 48);
            var captured = new ArrayList<DynamicCollisionObstacleSnapshot>();
            try (var subject = MinecraftCollisionSubject.open(actor)) {
                RigidCollisionPublicationRegistry.capture(level, new ExternalRigidCollisionQuery(domain, domain, time), captured::add);
            }
            var children = captured.stream().filter(p -> p.providerNamespace().equals(CreateRigidCollisionProvider.ID)).toList();
            ContactBehaviorChecks.check(children.size() == 2, "Create collision publishes both blocks separately");
            var localCenter = new Vec3(.5, .5, .5);
            var expected = parent.logicalPose().transformPosition(vehicle.toGlobalVector(localCenter, 1));
            var first = children.stream().filter(p -> p.localBody().center().lengthSquared() == 0).findFirst().orElseThrow();
            ContactBehaviorChecks.check(first.bodyAt(1).center().distanceSquared(new Vec3d(expected.x, expected.y, expected.z)) < 1e-7,
                    "Create child pose composes with native SubLevel endpoint");
            var point = first.motion().poseAt(.5).transformPoint(new Vec3d(1, .2, .3));
            var derivative = first.motion().poseAt(.500001).transformPoint(new Vec3d(1, .2, .3))
                    .subtract(first.motion().poseAt(.499999).transformPoint(new Vec3d(1, .2, .3))).divide(.000002);
            ContactBehaviorChecks.check(first.motion().velocityAt(point, .5).distanceSquared(derivative) < 1e-12,
                    "Create material velocity includes both motions once");
            attachedMass(level,(dev.ryanhcode.sable.sublevel.ServerSubLevel)parent,vehicle);
            seat(level,parent,vehicle,actor);
            try (var subject = MinecraftCollisionSubject.open(actor)) {
                ContactBehaviorChecks.check(RigidCollisionPublicationRegistry.resolve(level, first.identity(), time).isPresent(),
                        "Create identity resolves before removal");
                vehicle.discard();
                ContactBehaviorChecks.check(RigidCollisionPublicationRegistry.resolve(level, first.identity(), time).isEmpty(),
                        "native removal retires Create source");
                ContactBehaviorChecks.check(first.bodyAt(1).center().distanceSquared(new Vec3d(expected.x, expected.y, expected.z)) < 1e-7,
                        "completed capture survives native removal");
            }
            System.out.println("CREATE_RUNTIME_PASSED geometry composed-motion removal");
            return java.util.Set.of("geometry", "composed-motion", "removal", "attached-mass", "seat");
        } finally {
            vehicle.discard(); actor.discard();
            container.removeSubLevel(parent, SubLevelRemovalReason.REMOVED); container.processSubLevelRemovals();
        }
    }
    private static void seat(ServerLevel level,dev.ryanhcode.sable.sublevel.SubLevel parent,
                             ControlledContraptionEntity vehicle,ContactBehaviorChecks.Actor actor) {
        actor.setPos(40,300,40);
        cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.applyDirectAssignment(actor,
                new cc.sighs.gravityengine.gravity.GravityState(new Vec3d(1,0,0),.08));
        vehicle.addSittingPassenger(actor,0);
        ContactBehaviorChecks.check(actor.getVehicle()==vehicle,"native Create seat boarding");
        cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.updateBody(actor);
        for(int step=0;step<2;step++) {
            if(step==1) { parent.updateLastPose();parent.logicalPose().position().add(.2,.1,0);parent.logicalPose().orientation().rotateY(.1);parent.updateBoundingBox(); }
            var nativePosition=vehicle.getPassengerPosition(actor,1).add(0,
                    com.simibubi.create.content.contraptions.actors.seat.SeatEntity.getCustomEntitySeatOffset(actor)-.125,0);
            var eyeOffset=actor.getEyePosition().subtract(actor.position());
            var expected=parent.logicalPose().transformPosition(nativePosition.add(eyeOffset)).subtract(eyeOffset);
            vehicle.positionRider(actor);
            ContactBehaviorChecks.check(actor.position().distanceTo(expected)<1e-7,"native vehicle owns mounted transform once actual="+actor.position()+" expected="+expected);
            var first=actor.position();vehicle.positionRider(actor);
            ContactBehaviorChecks.check(actor.position().distanceTo(first)<1e-9,"repeated native rider placement adds no GE carry");
        }
        actor.stopRiding();
        ContactBehaviorChecks.check(!actor.isPassenger(),"native Create dismount lifecycle");
        ContactBehaviorChecks.check(cc.sighs.gravityengine.gravity.integration.CharacterPlacementValidation.clearAt(actor,actor.position()),
                "native Create dismount leaves installed geometry clear");
        System.out.println("CREATE_SEAT_PASSED boarding parent-motion no-duplicate-placement dismount-clearance");
    }
    private static void attachedMass(ServerLevel level,dev.ryanhcode.sable.sublevel.ServerSubLevel parent,
                                     ControlledContraptionEntity vehicle) {
        var child=(dev.ryanhcode.sable.api.sublevel.KinematicContraption)vehicle;
        ContactBehaviorChecks.check(child.sable$getMassTracker()!=null && parent.getPlot().getContraptions().contains(child),
                "native contraption tick owns attached mass initialization");
        var system=SubLevelContainer.getContainer(level).physicsSystem();
        parent.updateMergedMassData((float)system.getPartialPhysicsTick());
        var points=((cc.sighs.gravityengine.gravity.integration.compat.sable.SableMassPoints.Access)child.sable$getMassTracker())
                .gravityengine$massPoints().snapshot();
        ContactBehaviorChecks.check(points!=null && points.size()==2,"native attached sparse mass capture");
        double phase=(float)system.getPartialPhysicsTick();
        var localPose=child.sable$getLocalPose(new Pose3d(),phase);
        var expected=new ArrayList<Vec3d>();
        for(var p:points) {
            var local=new org.joml.Vector3d(p.position().x(),p.position().y(),p.position().z());
            var world=parent.logicalPose().transformPosition(localPose.transformPosition(local));
            expected.add(new Vec3d(world.x,world.y,world.z));
        }
        var sampled=new ArrayList<Vec3d>();
        try(var publication=cc.sighs.gravityengine.api.GravityEngineApi.publish(level,com.example.examplemod.gravity.ProviderFixture.ID,
                cc.sighs.gravityengine.api.GravityFieldDefinition.block(ResourceLocation.fromNamespaceAndPath("gravityengine","attached_mass"),
                        BlockPos.ZERO,query->{sampled.add(query.position());return new cc.sighs.gravityengine.api.field.GravityFieldSample(Vec3d.ZERO);},
                        cc.sighs.gravityengine.api.field.GravityFields.infiniteInfluence(),cc.sighs.gravityengine.api.field.GravityFieldCompositionMode.OVERRIDE,1))) {
            var handle=system.getPhysicsHandle(parent);var before=handle.getLinearVelocity(new org.joml.Vector3d());
            cc.sighs.gravityengine.gravity.integration.compat.sable.SableGravityBridge.apply(parent,system,handle,.025);
            ContactBehaviorChecks.check(sampled.size()==3 && expected.stream().allMatch(p->sampled.stream().anyMatch(q->q.distance(p)<1e-7)),
                    "own and attached mass sampled once at native merged phase: "+sampled);
            ContactBehaviorChecks.check(handle.getLinearVelocity(new org.joml.Vector3d()).distance(before)>0,
                    "validated merged mass commits one gravity correction");
        }
        System.out.println("CREATE_ATTACHED_MASS_PASSED native-initialization sparse-points merged-phase force-commit");
    }
}
