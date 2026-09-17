package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityEvent;
import java.util.*;

/** Real Entity.move -> fallOn -> hurt -> die -> synced pose -> refreshDimensions. */
final class DeathGeometryChecks {
    private static TestCow survivor;
    private static long startTick;
    private static boolean originallyForced;
    private static final Map<TestCow, EntityDimensions> nextTickBodies = new LinkedHashMap<>();
    static void register() {
        NeoForge.EVENT_BUS.addListener(DeathGeometryChecks::size);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) -> {
            if (event.getEntity() instanceof TestCow cow) { cow.deathEvents++; }
        });
    }
    private static void size(EntityEvent.Size event) {
        if (event.getEntity() instanceof TestCow cow) {
            cow.sizeEvents++;
            if (cow.overrideSize) event.setNewSize(EntityDimensions.scalable(.7F, 1.1F).withEyeHeight(.8F));
            if (cow.requestedDimensions != null) event.setNewSize(cow.requestedDimensions);
            if (cow.throwSize) throw new FixtureFailure();
        }
    }
    static void run(ServerLevel level) {
        originallyForced=level.getForcedChunks().contains(new net.minecraft.world.level.ChunkPos(0,0).toLong());
        level.setChunkForced(0,0,true);
        Map<BlockPos, BlockState> saved = new HashMap<>();
        for (int x=6;x<=10;x++) for(int z=6;z<=10;z++) {
            var p=new BlockPos(x,299,z); saved.put(p,level.getBlockState(p)); level.setBlock(p,Blocks.STONE.defaultBlockState(),18);
        }
        try {
            var fatal = cow(level); int sizes=fatal.sizeEvents;
            fatal.fallDistance=30; fatal.move(MoverType.SELF,new Vec3(0,-.2,0));
            check(fatal.isDeadOrDying() && fatal.getPose()==Pose.DYING && fatal.deaths==1 && fatal.deathEvents==1,"fatal fall must run native death once");
            check(fatal.sizeEvents==sizes+1,"same-size DYING refresh must run Size once");
            coherent(fatal); check(runtime(fatal).persistentSupportState()==null,"death cannot republish old support");
            fatal.setDeltaMovement(Vec3.ZERO); level.addFreshEntity(fatal); survivor=fatal; startTick=level.getGameTime();

            var nonfatal=cow(level); nonfatal.fallDistance=4; nonfatal.move(MoverType.SELF,new Vec3(0,-.2,0));
            check(nonfatal.isAlive() && nonfatal.getHealth()<nonfatal.getMaxHealth() && nonfatal.deaths==0,"nonfatal native damage"); coherent(nonfatal);
            var outside=cow(level); outside.hurt(outside.damageSources().generic(),1000);
            check(outside.deaths==1 && outside.getPose()==Pose.DYING,"outside-move native death"); coherent(outside);

            for(int mode=0;mode<4;mode++) {
                var actor=cow(level); long revision=runtime(actor).bodyShapeRevision(); int before=actor.sizeEvents;
                int selected=mode;
                actor.callback=()->{
                    actor.overrideSize=selected==1;
                    actor.throwSize=selected==3;
                    actor.refreshDimensions();
                    coherentGeometry(actor);
                    if(selected==2) throw new FixtureFailure();
                };
                boolean threw=false;
                try { actor.move(MoverType.SELF,new Vec3(.01,-.1,0)); }
                catch(FixtureFailure expected) { threw=true; }
                check(threw==(mode>=2),"callback exception propagated");
                check(actor.sizeEvents==before+1,"Size callback executes exactly once");
                coherent(actor);
                if(mode==1) {
                    check(GravityEntityGeometry.dimensions(actor).width()==.7F && actor.getEyeHeight()==.8F,"Size override committed");
                    check(runtime(actor).bodyShapeRevision()>revision,"dimension revision advanced");
                }
                check(runtime(actor).currentMoveResult()==null && runtime(actor).persistentSupportState()==null,"old move result/support invalidated");
                actor.throwSize=false; actor.callback=null; actor.move(MoverType.SELF,new Vec3(.01,0,0)); coherent(actor);
                actor.setDeltaMovement(Vec3.ZERO); nextTickBodies.put(actor,GravityEntityGeometry.dimensions(actor)); level.addFreshEntity(actor);
            }
            rejectedResizeCases(level);
            System.out.println("DEATH_GEOMETRY_CHECKS_PASSED fatal nonfatal outside same-size changed-size callback-exception size-exception");
        } finally { saved.forEach((p,s)->level.setBlock(p,s,18)); }
    }
    private static void rejectedResizeCases(ServerLevel level) {
        var wide = cow(level);
        var oldDimensions = GravityEntityGeometry.dimensions(wide);
        var oldPose = wide.getPose();
        int events = wide.sizeEvents;
        wide.requestedDimensions = EntityDimensions.scalable(1.4F, .9F);
        wide.callback = () -> wide.setPose(Pose.CROUCHING);
        wide.move(MoverType.SELF, new Vec3(0, 0, .01));
        coherent(wide);
        check(wide.getPose() == oldPose && GravityEntityGeometry.dimensions(wide).equals(oldDimensions),
                "unsupported nested pose must restore pose and installed size");
        check(wide.sizeEvents == events + 1, "pose rollback must not repeat Size event");
        check(runtime(wide).currentMoveResult() == null && runtime(wide).persistentSupportState() == null,
                "rejected resize must retire old movement evidence");
        wide.requestedDimensions = null;
        wide.move(MoverType.SELF, new Vec3(0, 0, .01));
        coherent(wide);

        var growing = cow(level);
        GravityApplicationCoordinator.applyDirectAssignment(growing, new GravityState(new Vec3d(-1, 0, 0), .08));
        var wallPos = new BlockPos(9, 300, 8);
        var saved = level.getBlockState(wallPos);
        var beforeGrowth = GravityEntityGeometry.dimensions(growing);
        try {
            level.setBlock(wallPos, Blocks.STONE.defaultBlockState(), 18);
            growing.requestedDimensions = EntityDimensions.scalable(.9F, 2.4F);
            growing.callback = growing::refreshDimensions;
            growing.move(MoverType.SELF, new Vec3(0, 0, .01));
            coherent(growing);
            check(GravityEntityGeometry.dimensions(growing).equals(beforeGrowth),
                    "nested growth into side wall must retain accepted size");
        } finally {
            level.setBlock(wallPos, saved, 18);
        }

        var fatalWide = cow(level);
        var beforeDeath = GravityEntityGeometry.dimensions(fatalWide);
        events = fatalWide.sizeEvents;
        fatalWide.requestedDimensions = EntityDimensions.scalable(1.4F, .9F);
        fatalWide.fallDistance = 30;
        fatalWide.move(MoverType.SELF, new Vec3(0, -.2, 0));
        coherent(fatalWide);
        check(fatalWide.isDeadOrDying() && fatalWide.getPose() == Pose.DYING
                        && fatalWide.deaths == 1 && fatalWide.deathEvents == 1,
                "rejecting a death shape must not undo or repeat semantic death");
        check(GravityEntityGeometry.dimensions(fatalWide).equals(beforeDeath)
                        && fatalWide.sizeEvents == events + 1,
                "invalid death size retains accepted geometry without replaying Size");
        System.out.println("NESTED_RESIZE_REJECTION_PASSED unsupported-pose wall-growth invalid-death-size");
    }

    static boolean tickReady(ServerLevel level) {
        return survivor!=null && survivor.tickCount>0 && nextTickBodies.keySet().stream().allMatch(e->e.tickCount>0)
                || level.getGameTime()-startTick>=40;
    }
    static void nextTick(ServerLevel level) {
        check(survivor!=null && level.getGameTime()>startTick,"actual server tick advanced after fatal fall");
        coherent(survivor); check(survivor.deaths==1 && survivor.deathEvents==1 && survivor.getPose()==Pose.DYING
                && survivor.tickCount>0,"next native entity tick did not repeat death or roll back pose: ticks="+survivor.tickCount+" deaths="+survivor.deaths+" events="+survivor.deathEvents+" pose="+survivor.getPose());
        nextTickBodies.forEach((actor,dimensions)->{
            coherent(actor); check(actor.tickCount>0 && GravityEntityGeometry.dimensions(actor).equals(dimensions),"next native tick retained committed dimensions");
            actor.discard();
        });
        nextTickBodies.clear();
        survivor.discard(); survivor=null;
        if(!originallyForced) level.setChunkForced(0,0,false);
        System.out.println("DEATH_GEOMETRY_NEXT_TICK_PASSED");
    }
    private static TestCow cow(ServerLevel level) {
        var cow=new TestCow(level); cow.setPos(8,300,8); cow.setNoAi(true); cow.setNoGravity(true);
        GravityApplicationCoordinator.applyDirectAssignment(cow,new GravityState(new Vec3d(0,-1,.1).normalized(),.08));
        return cow;
    }
    private static cc.sighs.gravityengine.gravity.runtime.GravityOperationState runtime(Entity e) {
        return GravityEntityAccess.cast(e).gravityengine$gravityComponent().operationState();
    }
    private static void coherent(TestCow cow) {
        check(!runtime(cow).isInMove() && !runtime(cow).isApplyingGeometry(),"scopes closed"); coherentGeometry(cow);
    }
    private static void coherentGeometry(TestCow cow) {
        check(GravityEntityGeometry.geometryMatchesFrame(cow,runtime(cow).geometryReferenceFrame()),"exact body/proxy/axis agree");
        check(cow.getEyeHeight()==GravityEntityGeometry.dimensions(cow).eyeHeight(),"installed eye height agrees");
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    private static final class FixtureFailure extends RuntimeException {}
    private static final class TestCow extends Cow {
        int deaths,deathEvents,sizeEvents; boolean overrideSize,throwSize; Runnable callback;
        EntityDimensions requestedDimensions;
        TestCow(ServerLevel level) { super(EntityType.COW,level); }
        @Override public void die(DamageSource source) { deaths++; super.die(source); }
        @Override protected void checkFallDamage(double movement,boolean grounded,BlockState state,BlockPos pos) {
            super.checkFallDamage(movement,grounded,state,pos);
            if(callback!=null) { var next=callback; callback=null; next.run(); }
        }
    }
}
