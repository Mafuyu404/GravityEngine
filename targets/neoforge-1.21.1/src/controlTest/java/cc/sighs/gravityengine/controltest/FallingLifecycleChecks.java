package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.api.*;
import cc.sighs.gravityengine.api.field.*;
import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.collision.provider.*;
import cc.sighs.gravityengine.gravity.field.GravityFieldRuntime;
import cc.sighs.gravityengine.gravity.integration.*;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.*;
import java.util.*;

/** Transformed Vanilla tick, real block installation and failure boundaries. */
final class FallingLifecycleChecks {
    private static final BlockPos SOURCE = new BlockPos(8,280,8);

    /** Field evidence for the custom-axis installation fixtures. */
    private static FieldPublication publishAxis(ServerLevel level, Vec3d axis) {
        return GravityEngineApi.publish(level, com.example.examplemod.gravity.ProviderFixture.ID,GravityFieldDefinition.named(
                ResourceLocation.fromNamespaceAndPath("gravityengine_control_tests","falling_lifecycle"),
                q->new GravityFieldSample(axis.multiply(.04)),
                new GravityInfluenceVolume(){
                    public boolean contains(Vec3d p){return true;}
                    public Optional<GravityFieldBounds> finiteBounds(){return Optional.empty();}
                },
                GravityFieldCompositionMode.OVERRIDE,3));
    }

    private static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
    private static final class Actor extends FallingBlockEntity {
        int damageCalls, removals, broken;
        boolean exhaustMove;
        void stuck() { stuckSpeedMultiplier = new net.minecraft.world.phys.Vec3(.25, .05, .25); }
        @Override public void move(MoverType type, net.minecraft.world.phys.Vec3 movement) {
            if (exhaustMove) {
                exhaustMove=false;
                var tracker=((cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess)(Object)this)
                        .gravityengine$gravityComponent().operationState().collisionOperation().workTracker();
                while (tracker.recordNarrowPhaseTest()) { }
            }
            super.move(type,movement);
        }
        Actor(ServerLevel level) { super(EntityType.FALLING_BLOCK,level); }
        @Override public boolean causeFallDamage(float distance,float multiplier,net.minecraft.world.damagesource.DamageSource source) {
            if(distance>0) damageCalls++;
            return super.causeFallDamage(distance,multiplier,source);
        }
        @Override public void remove(Entity.RemovalReason reason) { removals++; super.remove(reason); }
        @Override public void callOnBrokenAfterFall(Block block,BlockPos pos) { broken++; super.callOnBrokenAfterFall(block,pos); }
    }
    private static Actor actor(ServerLevel level,Block block) {
        var tag=new CompoundTag(); tag.put("BlockState",net.minecraft.nbt.NbtUtils.writeBlockState(block.defaultBlockState()));
        tag.putBoolean("DropItem",true);
        var actor=new Actor(level);actor.load(tag);actor.setPos(8.5,280,8.5);
        return actor;
    }
    private static void clear(ServerLevel level) {
        for(var p:BlockPos.betweenClosed(SOURCE.offset(-3,-3,-3),SOURCE.offset(3,3,3)))
            level.setBlock(p,Blocks.AIR.defaultBlockState(),18);
    }
    static void run(ServerLevel level) {
        var saved=new HashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        for(var p:BlockPos.betweenClosed(SOURCE.offset(-3,-3,-3),SOURCE.offset(3,3,3))) saved.put(p.immutable(),level.getBlockState(p));
        try {
            vanillaTrajectory(level);
            for(var down:Direction.values()) for(var block:List.of(Blocks.SAND,Blocks.GRAVEL)) {
                clear(level); var actor=actor(level,block);
                var wall=SOURCE.relative(down,2);level.setBlock(wall,Blocks.STONE.defaultBlockState(),18);
                var n=down.getNormal();var vector=new Vec3d(n.getX(),n.getY(),n.getZ());
                /*
                 * Entity->Block conversion is owned by the field at the
                 * placement cell: a static installation is only legal while
                 * that axis still finds this support face occupied. Publish
                 * the matching six-axis evidence for the custom axes.
                 */
                try(FieldPublication lease=down==Direction.DOWN
                        ? null
                        : publishAxis(level,vector)) {
                    if(down!=Direction.DOWN) GravityApplicationCoordinator.applyDirectAssignment(actor,new GravityState(vector,.04));
                    actor.setDeltaMovement(n.getX()*2,n.getY()*2,n.getZ()*2);actor.tick();
                    check(actor.isRemoved() && actor.removals==1,"single native consumption "+block+" "+down);
                    check(level.getBlockState(actor.blockPosition()).equals(block.defaultBlockState()),"actual block installation "+block+" "+down);
                    check(actor.broken==0,"no broken callback on successful installation");
                }
            }
            for(var block:List.of(Blocks.ANVIL,Blocks.CHIPPED_ANVIL,Blocks.DAMAGED_ANVIL)) {
                clear(level);var actor=actor(level,block);actor.setHurtsEntities(2,40);actor.fallDistance=40;
                var wall=SOURCE.east(2);level.setBlock(wall,Blocks.STONE.defaultBlockState(),18);
                try(var lease=publishAxis(level,Vec3d.X)) {
                    GravityApplicationCoordinator.applyDirectAssignment(actor,new GravityState(Vec3d.X,.04));
                    actor.setDeltaMovement(2,0,0);actor.tick();
                    var expected=AnvilBlock.damage(block.defaultBlockState());
                    check(actor.damageCalls==1 && actor.removals==1,"anvil damage and discard exactly once "+block);
                    check(actor.broken==(expected==null?1:0),"anvil broken callback exactly once "+block);
                    check(expected==null?level.getBlockState(actor.blockPosition()).isAir():level.getBlockState(actor.blockPosition()).equals(expected),"native damaged anvil state installed "+block);
                }
            }
            for(boolean custom:new boolean[]{false,true}) {
                clear(level);var actor=actor(level,Blocks.WHITE_CONCRETE_POWDER);
                level.setBlock(SOURCE,Blocks.WATER.defaultBlockState(),18);
                if(custom) GravityApplicationCoordinator.applyDirectAssignment(actor,new GravityState(Vec3d.X,.04));
                actor.setNoGravity(true);actor.tick();
                check(actor.isRemoved() && actor.removals==1,"water consumes powder "+custom);
                check(level.getBlockState(SOURCE).is(Blocks.WHITE_CONCRETE),"Vanilla hydration/onLand installs concrete "+custom);
            }
            for (boolean custom : new boolean[]{false,true}) {
                clear(level); var actor=actor(level,Blocks.WHITE_CONCRETE_POWDER);
                level.setBlock(SOURCE.east(2),Blocks.WATER.defaultBlockState(),18);
                if(custom) GravityApplicationCoordinator.applyDirectAssignment(actor,new GravityState(Vec3d.X,.04));
                actor.setNoGravity(true); actor.setOldPosAndRot(); actor.setDeltaMovement(3,0,0); actor.tick();
                check(actor.isRemoved() && actor.removals==1,"high-speed hydration consumes powder once");
                check(level.getBlockState(SOURCE.east(2)).is(Blocks.WHITE_CONCRETE),"Vanilla water ray retains its native hydration placement exception");
                check(!actor.blockPosition().equals(SOURCE.east(2)),"hydration ray fixture must pass beyond water");
            }
            for(boolean complexity:new boolean[]{false,true}) fallback(level,complexity);
            lateFallback(level);
            discontinuity(level);
            zeroFields(level);
            System.out.println("FALLING_LIFECYCLE_PASSED six_axes anvil hydration fallback zero_fields discontinuity");
        } finally { saved.forEach((p,s)->level.setBlock(p,s,18)); }
    }
    private record Step(net.minecraft.world.phys.Vec3 position, net.minecraft.world.phys.Vec3 velocity, boolean ground, boolean removed) {}
    private static void vanillaTrajectory(ServerLevel level) {
        for (var block : List.of(Blocks.SAND, Blocks.ANVIL)) {
            var reference = new ArrayList<Step>();
            for (boolean active : new boolean[]{false, true}) {
                clear(level);
                level.setBlock(SOURCE.below(2), Blocks.STONE.defaultBlockState(), 18);
                var actor = actor(level, block);
                if (active) GravityApplicationCoordinator.applyDirectAssignment(actor, new GravityState(GravityState.DEFAULT_DOWN, .04));
                for (int tick = 0; tick < 40; tick++) {
                    actor.tick();
                    var actual = new Step(actor.position(), actor.getDeltaMovement(), actor.onGround(), actor.isRemoved());
                    if (!active) reference.add(actual);
                    else {
                        var expected = reference.get(tick);
                        check(actual.position.distanceToSqr(expected.position) < 1e-20, "Vanilla position parity " + block + " tick=" + tick);
                        check(actual.velocity.distanceToSqr(expected.velocity) < 1e-20, "Vanilla velocity/drag parity " + block + " tick=" + tick);
                        check(actual.ground == expected.ground && actual.removed == expected.removed, "Vanilla timing/ground parity " + tick);
                    }
                    if (actor.isRemoved()) break;
                }
                check(actor.isRemoved() && level.getBlockState(actor.blockPosition()).is(block), "trajectory ends at committed cell");
            }
        }
    }
    private static void fallback(ServerLevel level,boolean complexity) {
        clear(level);
        var provider=new ExternalRigidCollisionProvider() {
            public String id(){return "gravityengine_control_tests:unavailable";}
            public boolean isLive(){return true;}
            public Optional<DynamicCollisionObstacleSnapshot> resolve(RigidObstacleIdentity identity,KinematicStepContext time){return Optional.empty();}
            public void capture(ExternalRigidCollisionQuery query,RigidPublicationCollector output){
                if(complexity) throw new CollisionComplexityLimitException("test capture budget");
                throw new CollisionSceneCoverageException("test capture coverage");
            }
        };
        RigidCollisionPublicationRegistry.register(level,provider);
        try {
            var actor=actor(level,Blocks.SAND);
            GravityApplicationCoordinator.applyDirectAssignment(actor,new GravityState(Vec3d.X,.04));
            var before=actor.position();actor.tick();
            check(actor.position().distanceToSqr(before.add(0,-.04,0))<1e-12,"unavailable must execute native gravity AND movement");
            level.setBlock(SOURCE.below(2),Blocks.STONE.defaultBlockState(),18);
            actor.setDeltaMovement(0,-2,0);actor.tick();
            check(actor.isRemoved() && actor.removals==1,"unavailable preserves native onGround and landing");
            check(level.getBlockState(SOURCE.below()).is(Blocks.SAND),"fallback installs BlockState");
            clear(level); actor=actor(level,Blocks.WHITE_CONCRETE_POWDER);
            GravityApplicationCoordinator.applyDirectAssignment(actor,new GravityState(Vec3d.X,.04));
            actor.setNoGravity(true);level.setBlock(SOURCE,Blocks.WATER.defaultBlockState(),18);actor.tick();
            check(actor.isRemoved() && level.getBlockState(SOURCE).is(Blocks.WHITE_CONCRETE),"unavailable preserves hydration");
        } finally { RigidCollisionPublicationRegistry.unregister(level,provider.id()); }
    }
    private static void lateFallback(ServerLevel level) {
        for (boolean stuck : new boolean[]{false,true}) {
            clear(level);
            var nativeActor=actor(level,Blocks.SAND);
            nativeActor.setDeltaMovement(.1,.2,.3);
            if(stuck) nativeActor.stuck();
            nativeActor.tick();
            var actor=actor(level,Blocks.SAND);
            GravityApplicationCoordinator.applyDirectAssignment(actor,new GravityState(Vec3d.X,.04));
            actor.setDeltaMovement(.1,.2,.3); actor.exhaustMove=true;
            if(stuck) actor.stuck();
            actor.tick();
            check(actor.position().distanceToSqr(nativeActor.position())<1e-20,"narrow-phase failure restores native movement, stuck="+stuck);
            check(actor.getDeltaMovement().distanceToSqr(nativeActor.getDeltaMovement())<1e-20,"narrow-phase failure restores native velocity/drag");
            check(actor.time==1 && actor.damageCalls==0 && actor.removals==0,"retry never repeats tick lifecycle");
            var snapshot =
                    ((cc.sighs.gravityengine.gravity.minecraft.access
                            .GravityFallingBlockAccess) (Object) actor)
                            .gravityengine$ballisticSnapshot();

            check(
                    !snapshot.customAcceleration()
                            && !snapshot.engineCollision(),
                    "failure must release both acceleration and collision authority"
            );
        }
    }
    private static void discontinuity(
            ServerLevel level
    ) {
        clear(level);

        var volume =
                new GravityInfluenceVolume() {
                    @Override
                    public boolean contains(
                            Vec3d p
                    ) {
                        return p.x() > 5
                                && p.x() < 12
                                && p.y() > 276
                                && p.y() < 284
                                && p.z() > 5
                                && p.z() < 12;
                    }

                    @Override
                    public Optional<GravityFieldBounds>
                    finiteBounds() {
                        return Optional.of(
                                new GravityFieldBounds(
                                        5,
                                        276,
                                        5,
                                        12,
                                        284,
                                        12
                                )
                        );
                    }
                };

        var samples =
                new ArrayList<GravityFieldQuery>();

        try (var lease =
                     GravityEngineApi.publish(
                             level, com.example.examplemod.gravity.ProviderFixture.ID,
                             GravityFieldDefinition.named(
                                     ResourceLocation
                                             .fromNamespaceAndPath(
                                                     "gravityengine_control_tests",
                                                     "landing_discontinuity"
                                             ),
                                     query -> {
                                         samples.add(query);

                                         return new GravityFieldSample(
                                                 query.position().x() < 9
                                                         ? new Vec3d(
                                                         .04D,
                                                         0,
                                                         0
                                                 )
                                                         : new Vec3d(
                                                         0,
                                                         .04D,
                                                         0
                                                 )
                                         );
                                     },
                                     volume,
                                     GravityFieldCompositionMode.OVERRIDE,
                                     1
                             )
                     )) {

            var actor =
                    actor(
                            level,
                            Blocks.SAND
                    );

            level.setBlock(
                    SOURCE.east(2),
                    Blocks.STONE.defaultBlockState(),
                    18
            );

            actor.setDeltaMovement(
                    2,
                    0,
                    0
            );

            actor.tick();

            /*
             * First sample:
             *     movement operation.
             *
             * Second sample:
             *     prospective placement-cell Block -> Entity closure check.
             */
            check(
                    samples.size() == 2,
                    "landing owns exactly one movement sample "
                            + "and one conversion sample: "
                            + samples
            );

            /*
             * The placement cell belongs to a different gravity regime.
             *
             * Therefore committing Sand there would immediately schedule another
             * Block -> Entity transition. The entity is consumed through Vanilla's
             * failed-installation/drop path without ever publishing a transient
             * Sand BlockState.
             */
            check(
                    actor.isRemoved(),
                    "non-closed landing is consumed exactly once"
            );

            check(
                    level.getBlockState(
                            SOURCE.east()
                    ).isAir(),
                    "non-closed landing never publishes a transient block"
            );

            check(
                    actor.broken == 1,
                    "failed installation owns one broken-after-fall callback"
            );
        }
    }
    private static void zeroFields(ServerLevel level) {
        check(GravityFieldRuntime.get(level).registry().size()==0,"zero-field fixture registry");
        GravityFieldRuntime.remove(level);
        var chunk=level.getChunkAt(SOURCE);
        var bus=net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
        bus.post(new net.neoforged.neoforge.event.level.ChunkEvent.Load(chunk,false));
        bus.post(new net.neoforged.neoforge.event.tick.LevelTickEvent.Post(()->true,level));
        level.setBlock(SOURCE,Blocks.STONE.defaultBlockState(),18);level.setBlock(SOURCE,Blocks.AIR.defaultBlockState(),18);
        check(GravityFieldRuntime.getIfPresent(level)==null,"native world events must not create field runtime");
        var runtime=GravityFieldRuntime.get(level);
        long before=runtime.fallingBlocks().scannedPositions();
        for(int i=0;i<80;i++) runtime.fallingBlocks().tick(level);
        check(runtime.fallingBlocks().scannedPositions()==before && runtime.fallingBlocks().loadedChunkCount()==0,"empty registry scans zero positions");
    }
}
