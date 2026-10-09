package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Native branch operands under rotated gravity; geometry/immersion are separate controls. */
final class EnvironmentMovementChecks {
    static void run(ServerLevel level) {
        for(int mode:new int[]{1,2}) for(double strength:new double[]{.08,0}) {
            if(mode==1) for(int x=94;x<=98;x++) for(int y=278;y<=284;y++) for(int z=94;z<=98;z++)
                level.setBlock(new net.minecraft.core.BlockPos(x,y,z),net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(),18);
            Vec3d reference=null;
            for(var down:new Vec3d[]{new Vec3d(0,-1,0),new Vec3d(1,0,0),new Vec3d(0,1,0),new Vec3d(1,-2,.5).normalized()}) {
                var actor=new ContactBehaviorChecks.Actor(level); actor.mode=mode;
                actor.setPos(96,mode==1?280:340,96);
                GravityApplicationCoordinator.applyDirectAssignment(actor,new GravityState(down,strength));
                var frame=GravityFrameAccess.authoritativeFrame(actor);
                actor.setDeltaMovement(MinecraftMathAdapter.toMinecraft(frame.localToWorld(new Vec3d(.2,-.1,.3))));
                actor.travel(Vec3.ZERO);
                var result=frame.worldToLocal(MinecraftMathAdapter.toVec3d(actor.getDeltaMovement()));
                if(reference==null) reference=result;
                ContactBehaviorChecks.check(reference.distance(result)<1e-7,
                        "native environment branch covariance mode="+mode+" gravity="+down+" expected="+reference+" actual="+result);
                if(mode==1) {
                    ContactBehaviorChecks.check(Math.abs(result.x()-.16)<1e-7 && Math.abs(result.z()-.24)<1e-7,
                            "physical water volume selects native water drag");
                    actor.setDeltaMovement(Vec3.ZERO);
                    actor.jumpInFluid(net.neoforged.neoforge.common.NeoForgeMod.WATER_TYPE.value());
                    var jump=frame.worldToLocal(MinecraftMathAdapter.toVec3d(actor.getDeltaMovement()));
                    ContactBehaviorChecks.check(jump.distance(new Vec3d(0,.04F,0))<1e-8,"gravity-relative native swim propulsion");
                    actor.sinkInFluid(net.neoforged.neoforge.common.NeoForgeMod.WATER_TYPE.value());
                    ContactBehaviorChecks.check(actor.getDeltaMovement().length()<1e-8,"native descend cancels equal ascent");
                }
            }
            if(mode==1) for(int x=94;x<=98;x++) for(int y=278;y<=284;y++) for(int z=94;z<=98;z++)
                level.setBlock(new net.minecraft.core.BlockPos(x,y,z),net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),18);
        }
        System.out.println("ENVIRONMENT_MOVEMENT_OPERANDS_PASSED water climbing swim-input sideways inverted oblique zero");
        dismount(level);
    }
    private static void dismount(ServerLevel level) {
        var upper=new net.minecraft.core.BlockPos(96,301,96);
        var lower=upper.below();var oldUpper=level.getBlockState(upper);var oldLower=level.getBlockState(lower);
        try {
            var actor=new ContactBehaviorChecks.Actor(level);actor.setPos(96.5,310,96.5);
            GravityApplicationCoordinator.applyDirectAssignment(actor,new GravityState(new Vec3d(1,0,0),.08));
            level.setBlock(upper,net.minecraft.world.level.block.Blocks.STONE_SLAB.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.SlabBlock.TYPE,net.minecraft.world.level.block.state.properties.SlabType.TOP),18);
            level.setBlock(lower,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),18);
            var candidate=actor.getDimensions(net.minecraft.world.entity.Pose.STANDING).makeBoundingBox(new Vec3(96.5,300,96.5));
            ContactBehaviorChecks.check(!level.noCollision(actor,candidate),"native upright dismount box intersects overhead slab");
            ContactBehaviorChecks.check(net.minecraft.world.entity.vehicle.DismountHelper.canDismountTo(level,actor,candidate),
                    "installed sideways body fits dismount beneath slab");
            level.setBlock(lower,net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),18);
            ContactBehaviorChecks.check(!net.minecraft.world.entity.vehicle.DismountHelper.canDismountTo(level,actor,candidate),
                    "installed dismount body rejects real obstruction");
            var before=actor.position();actor.dismountTo(96.5,300,96.5);
            ContactBehaviorChecks.check(actor.position().equals(before),"native dismount fallback cannot commit obstructed placement");
            level.setBlock(lower,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),18);
            actor.dismountTo(96.5,300,96.5);
            ContactBehaviorChecks.check(actor.position().equals(new Vec3(96.5,300,96.5)),"clear native dismount commits placement");
        } finally { level.setBlock(upper,oldUpper,18);level.setBlock(lower,oldLower,18); }
        System.out.println("DISMOUNT_INSTALLED_BODY_PASSED sideways-slab-fit blocked-candidate");
    }
}
