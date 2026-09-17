package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.api.*;
import cc.sighs.gravityengine.api.field.*;
import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.field.GravityFieldRuntime;
import cc.sighs.gravityengine.gravity.integration.*;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import java.util.*;

/** Runs in the transformed dedicated server, with and without the pinned Sable runtime. */
final class PassiveCompatibilityChecks {
    private static final BlockPos SOURCE = new BlockPos(8, 280, 8);
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("gravityengine_control_tests", "passive_field");
    private static final GravityInfluenceVolume GLOBAL = new GravityInfluenceVolume() {
        public boolean contains(Vec3d position) { return true; }
        public Optional<GravityFieldBounds> finiteBounds() { return Optional.empty(); }
    };
    private static final List<Entity> SPAWNED = new ArrayList<>();
    static void run(ServerLevel level) {
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> listener = event -> {
            if (event.getLevel() == level) SPAWNED.add(event.getEntity());
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(listener);
        try {
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) level.getChunk(x, z);
        starts(level);
        loadedBoundary(level);
        fallingMotion(level);
        frozenBallisticTick(level);
        impactAndInstallation(level);
        installationCallbacks(level);
        endpointAuthority(level);
        FallingLifecycleChecks.run(level);
        armorStands(level);
        System.out.println("PASSIVE_COMPATIBILITY_CHECKS_PASSED start scheduler falling armorstand");
        } finally {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener);
            for (var entity : SPAWNED) entity.discard();
            SPAWNED.clear();
        }
    }
    private static FieldPublication field(ServerLevel level, Vec3d vector, long revision) {
        return publish(level, q -> new GravityFieldSample(vector), revision);
    }
    private static FieldPublication publish(ServerLevel level, GravityField evaluator, long revision) {
        return GravityEngineApi.publish(level, com.example.examplemod.gravity.ProviderFixture.ID, GravityFieldDefinition.named(ID, evaluator, GLOBAL,
                GravityFieldCompositionMode.OVERRIDE, revision));
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void near(Vec3 expected, Vec3 actual, String message) {
        check(expected.distanceToSqr(actual) < 1e-10, message + " expected=" + expected + " actual=" + actual);
    }
    private static void clear(ServerLevel level) {
        for (var pos : BlockPos.betweenClosed(SOURCE.offset(-3,-3,-3), SOURCE.offset(3,3,3)))
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        for (var entity : SPAWNED)
            if (entity instanceof FallingBlockEntity || entity instanceof ItemEntity) entity.discard();
    }
    private static List<FallingBlockEntity> falling(ServerLevel level) {
        return SPAWNED.stream().filter(entity -> !entity.isRemoved() && entity instanceof FallingBlockEntity).map(entity -> (FallingBlockEntity) entity).toList();
    }
    private static void blockTick(ServerLevel level) {
        level.getBlockState(SOURCE).tick(level, SOURCE, level.random);
    }
    private static void starts(ServerLevel level) {
        for (var block : new Block[]{Blocks.SAND, Blocks.GRAVEL, Blocks.ANVIL}) {
            for (Direction down : Direction.values()) {
                clear(level);
                var axis = down.getNormal();
                var state = block.defaultBlockState();
                if (block == Blocks.ANVIL) state = state.setValue(AnvilBlock.FACING, Direction.EAST);
                try (var lease = field(level, new Vec3d(axis.getX(),axis.getY(),axis.getZ()).multiply(.04), 1)) {
                    level.setBlock(SOURCE, state, 2);
                    for (var direction : Direction.values()) level.setBlock(SOURCE.relative(direction), Blocks.STONE.defaultBlockState(), 2);
                    blockTick(level);
                    check(level.getBlockState(SOURCE).equals(state), "supported start " + down);
                    level.setBlock(SOURCE.relative(down), Blocks.AIR.defaultBlockState(), 3);
                    blockTick(level);
                    check(level.getBlockState(SOURCE).isAir(), "six-axis start " + block + " " + down);
                    var actors = falling(level);
                    check(actors.size() == 1, "one native fall entity count=" + actors.size() + " block=" + block + " down=" + down);
                    var actor = actors.getFirst();
                    check(actor.getBlockState().equals(state), "fall preserves BlockState");
                    var tag = new CompoundTag(); actor.saveWithoutId(tag);
                    check(tag.getBoolean("HurtEntities") == (block == Blocks.ANVIL), "native anvil falling hook");
                    if (block == Blocks.ANVIL) check(tag.getFloat("FallHurtAmount") == 2 && tag.getInt("FallHurtMax") == 40, "anvil damage initialization");
                    blockTick(level);
                    check(falling(level).size() == 1, "no duplicate generation");
                }
            }
        }
        clear(level);
        long sampleTick = level.getGameTime();
        try (var zero = publish(level, q -> {
            check(q.position().equals(new Vec3d(8.5,280.5,8.5)), "center sample");
            check(q.velocity().equals(Vec3d.ZERO) && q.gameTick() == sampleTick && q.intervalTicks() == 1, "full physical start query");
            return new GravityFieldSample(Vec3d.ZERO);
        }, 1)) {
            level.setBlock(SOURCE, Blocks.SAND.defaultBlockState(), 2);
            blockTick(level);
            check(!level.getBlockState(SOURCE).isAir(), "present zero suppresses unsupported start");
        }
        blockTick(level);
        check(level.getBlockState(SOURCE).isAir(), "absent field restores native start");
        clear(level);
        try (var old = field(level, Vec3d.ZERO, 1); var replacement = field(level, Vec3d.X, 2)) {
            long generation = GravityFieldRuntime.get(level).registry().publicationRevision();
            try (var stale = field(level, Vec3d.ZERO, 1)) { check(!stale.accepted(), "stale revision rejected"); }
            old.close();
            check(GravityFieldRuntime.get(level).registry().publicationRevision() == generation, "ineffective close/revision creates no change");
            level.setBlock(SOURCE, Blocks.GRAVEL.defaultBlockState(), 2);
            level.setBlock(SOURCE.below(), Blocks.STONE.defaultBlockState(), 2);
            blockTick(level);
            check(level.getBlockState(SOURCE).isAir(), "replacement selects new direction");
        }
        clear(level);
    }
    static final class GuardedFallingBlock extends FallingBlock {
        private static final com.mojang.serialization.MapCodec<GuardedFallingBlock> CODEC = simpleCodec(GuardedFallingBlock::new);
        boolean enabled; int checks; int hooks; int lands; int broken;
        final Set<Long> visited = new HashSet<>();
        GuardedFallingBlock() { this(Properties.of()); }
        GuardedFallingBlock(Properties properties) { super(properties); }
        protected com.mojang.serialization.MapCodec<? extends FallingBlock> codec() { return CODEC; }
        protected void tick(BlockState state, ServerLevel level, BlockPos pos, net.minecraft.util.RandomSource random) {
            PassiveSchedulingChecks.callback(level,pos);
            checks++; visited.add(new net.minecraft.world.level.ChunkPos(pos).toLong());
            if (enabled) super.tick(state,level,pos,random);
        }
        protected int getDelayAfterPlace() { return 80; }
        protected void falling(FallingBlockEntity entity) { hooks++; entity.setHurtsEntities(3,30); }
        @Override public void onLand(net.minecraft.world.level.Level level, BlockPos pos, BlockState state,
                BlockState replaced, FallingBlockEntity actor) { lands++; }
        @Override public void onBrokenAfterFall(net.minecraft.world.level.Level level, BlockPos pos, FallingBlockEntity actor) { broken++; }
    }
    private static void loadedBoundary(ServerLevel level) {
        var source=new BlockPos(1615,280,1608);
        var fixtureChunk=level.getChunk(100,100);
        var section=fixtureChunk.getSection(level.getSectionIndex(source.getY()));
        check(level.getChunkSource().getChunkNow(101,100)==null,"neighbor fixture is not FULL loaded");
        var previous=level.getBlockState(source);
        try(var lease=field(level,Vec3d.X,1)) {
            // Populate as chunk data: Level.setBlock would itself load neighbors during shape notifications.
            section.setBlockState(15,8,8,Blocks.SAND.defaultBlockState());
            level.getBlockState(source).tick(level,source,level.random);
            check(level.getBlockState(source).is(Blocks.SAND),"unloaded neighbor cannot grant start");
            check(level.getChunkSource().getChunkNow(101,100)==null,"start check must not load neighbor");
            var runtime=GravityFieldRuntime.get(level).fallingBlocks();
            runtime.loaded(level,fixtureChunk.getPos());
            int loaded=runtime.loadedChunkCount(); var chunk=level.getChunk(100,100);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.level.ChunkEvent.Unload(chunk));
            check(runtime.loadedChunkCount()==loaded-1,"real unload subscriber clears cursor");
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.level.ChunkEvent.Load(chunk,false));
            check(runtime.loadedChunkCount()==loaded,"real reload subscriber restores discovery");
        } finally { section.setBlockState(15,8,8,previous); }
    }
    private static void fallingMotion(ServerLevel level) {
        clear(level);
        var actor = FallingBlockEntity.fall(level, SOURCE, Blocks.SAND.defaultBlockState());
        try {
            GravityApplicationCoordinator.applyDirectAssignment(actor, new GravityState(new Vec3d(1,-2,.5), .04));
            actor.setDeltaMovement(.1,.2,.3);
            Vec3 before = actor.position();
            Vec3 acceleration = new Vec3(0,-.04,0);
            actor.tick();
            near(before.add(new Vec3(.1,.2,.3)).add(acceleration), actor.position(), "six-axis quantized ballistic movement");
            near(new Vec3(.1,.2,.3).add(acceleration).scale(.98), actor.getDeltaMovement(), "one gravity and drag application");
            Vec3 momentum = actor.getDeltaMovement();
            GravityApplicationCoordinator.applyDirectAssignment(actor, new GravityState(new Vec3d(-1,0,0), .04));
            near(momentum, actor.getDeltaMovement(), "frame change preserves world momentum");
            actor.setNoGravity(true); actor.setOnGround(true);
            actor.setDeltaMovement(Vec3.ZERO); actor.time = 600;
            actor.tick();
            check(actor.isRemoved() && actor.time == 601, "sideward resting entity retains native timeout");
            check(SPAWNED.stream().filter(e -> e instanceof ItemEntity && !e.isRemoved()).count() == 1, "timeout drops exactly once");
        } finally { actor.discard(); clear(level); }
    }
    private static final class ImpactBlock extends FallingBlockEntity {
        int damageCalls; float damageDistance;
        ImpactBlock(ServerLevel level) { super(EntityType.FALLING_BLOCK,level); }
        @Override public boolean causeFallDamage(float distance,float multiplier,net.minecraft.world.damagesource.DamageSource source) {
            if(distance>0) { damageCalls++; damageDistance=distance; }
            return super.causeFallDamage(distance,multiplier,source);
        }
    }
    private static void impactAndInstallation(ServerLevel level) {
        int revision=40;
        for(var down:new Vec3d[]{Vec3d.X,Vec3d.Y}) {
            clear(level);
            var nativeActor=FallingBlockEntity.fall(level,SOURCE,Blocks.ANVIL.defaultBlockState());
            nativeActor.setHurtsEntities(2,40);
            var tag=new CompoundTag(); nativeActor.saveWithoutId(tag); nativeActor.discard();
            var actor=new ImpactBlock(level); actor.load(tag);
            var wall=down.equals(Vec3d.X)?SOURCE.offset(2,0,0):SOURCE.offset(0,2,0);
            level.setBlock(wall,Blocks.STONE.defaultBlockState(),18);
            /*
             * Block->Entity conversion is field-driven: the placement may only
             * become a static block while the field that owns this axis still
             * finds its support face occupied. Publish the same six-axis
             * evidence the acting falling entity was assigned.
             */
            try (var lease=field(level,down.multiply(.04),revision++)) {
                GravityApplicationCoordinator.applyDirectAssignment(actor,new GravityState(down,.04));
                actor.fallDistance=5; actor.setDeltaMovement(new Vec3(down.x(),down.y(),down.z()).scale(1.3));
                actor.tick();
                check(actor.isRemoved(),"side/ceiling anvil consumed by native installation");
                check(actor.damageCalls==1 && actor.damageDistance>=5,"anvil native impact damage callback once along gravity");
                var placement=down.equals(Vec3d.X)?wall.west():wall.below();
                check(level.getBlockState(placement).equals(actor.getBlockState()),"damaged anvil state installed at contact");
                check(level.getBlockState(SOURCE).isAir(),"no source reinstallation");
            } finally { actor.discard(); clear(level); }
        }
    }
    private static void frozenBallisticTick(ServerLevel level) {
        clear(level);
        var samples=new java.util.concurrent.atomic.AtomicInteger();
        try(var lease=publish(level,q->{
            samples.incrementAndGet();
            return new GravityFieldSample(q.velocity().x()==0 ? new Vec3d(.04,0,0) : new Vec3d(0,.04,0));
        },1)) {
            var actor=FallingBlockEntity.fall(level,SOURCE,Blocks.SAND.defaultBlockState());
            actor.setDeltaMovement(Vec3.ZERO); var before=actor.position();
            actor.tick();
            near(before.add(.04,0,0),actor.position(),"one frozen ballistic query drives acceleration and collision frame");
            check(samples.get()==1,"one frozen operation evaluation, actual="+samples.get());
            near(new Vec3(.04*.98,0,0),actor.getDeltaMovement(),"endpoint query cannot integrate a second acceleration");
            actor.discard();
        } finally { clear(level); }
    }
    private static ArmorStand stand(ServerLevel level, boolean small, boolean marker, boolean noGravity) {
        var stand = new ArmorStand(level, 8, 285, 8);
        var tag = new CompoundTag(); tag.putBoolean("Small",small); tag.putBoolean("Marker",marker); tag.putBoolean("NoGravity",noGravity);
        stand.load(tag); stand.setPos(8,285,8);
        return stand;
    }
    static void flags(ArmorStand stand, boolean small, boolean marker) {
        try {
            var field = ArmorStand.class.getDeclaredField("DATA_CLIENT_FLAGS"); field.setAccessible(true);
            @SuppressWarnings("unchecked") var key = (net.minecraft.network.syncher.EntityDataAccessor<Byte>)field.get(null);
            byte old = stand.getEntityData().get(key);
            stand.getEntityData().set(key, (byte)((old & ~17) | (small ? 1 : 0) | (marker ? 16 : 0)));
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void equipment(ServerLevel level, ArmorStand stand, boolean small) {
        var player = new net.minecraft.world.entity.player.Player(level,BlockPos.ZERO,0,
                new com.mojang.authlib.GameProfile(UUID.randomUUID(),"stand-equipment")) {
            public boolean isSpectator() { return false; }
            public boolean isCreative() { return false; }
        };
        stand.setItemSlot(EquipmentSlot.CHEST, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_CHESTPLATE));
        double half = GravityEntityGeometry.dimensions(stand).height() * .5;
        var frame = cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess.authoritativeFrame(stand);
        Vec3 up = new Vec3(frame.up().x(),frame.up().y(),frame.up().z());
        Vec3 hit = up.scale((small ? .7 : 1.2) - half).add(0,half,0);
        stand.interactAt(player,hit,net.minecraft.world.InteractionHand.MAIN_HAND);
        check(stand.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
                && player.getMainHandItem().is(net.minecraft.world.item.Items.DIAMOND_CHESTPLATE), "committed-frame chest interaction " + small);
        player.discard();
    }
    private static void sizeTransactions(ServerLevel level) {
        var stand = stand(level,true,false,false);
        var wall = new BlockPos(7,285,8); var previous = level.getBlockState(wall);
        try {
            stand.setPos(8.6,285,8.5);
            GravityApplicationCoordinator.applyDirectAssignment(stand,new GravityState(Vec3d.X,.04));
            var dimensions = GravityEntityGeometry.dimensions(stand); var box = stand.getBoundingBox(); var anchor = stand.position();
            level.setBlock(wall,Blocks.STONE.defaultBlockState(),18);
            flags(stand,false,false);
            check(stand.isSmall() && GravityEntityGeometry.dimensions(stand).equals(dimensions),"blocked growth rolls back Small and dimensions");
            check(stand.getBoundingBox().equals(box),"blocked growth keeps proxy"); near(anchor,stand.position(),"blocked growth keeps anchor");
            level.setBlock(wall,Blocks.AIR.defaultBlockState(),18);
            flags(stand,false,false);
            check(!stand.isSmall() && GravityEntityGeometry.dimensions(stand).height() > dimensions.height(),"unblocked growth commits");
            flags(stand,false,true);
            check(stand.isMarker() && GravityEntityGeometry.dimensions(stand).height() == 0
                    && !GravityInfluencePolicy.usesCustomBody(stand),"Marker uses legal native handoff");
            flags(stand,true,false);
            check(stand.isSmall() && !stand.isMarker() && GravityEntityGeometry.dimensions(stand).height() > 0,"Marker exit restores dimensions");
        } finally { level.setBlock(wall,previous,18); stand.discard(); }
    }
    private static void armorStands(ServerLevel level) {
        for (boolean small : new boolean[]{false,true}) for (boolean noGravity : new boolean[]{false,true}) {
            var stand = stand(level,small,false,noGravity);
            try {
                GravityApplicationCoordinator.applyDirectAssignment(stand, new GravityState(new Vec3d(1,-2,.5), .04));
                check(GravityInfluencePolicy.usesCustomBody(stand), "stand installs continuous exact body");
                stand.setDeltaMovement(.1,.2,.3);
                Vec3 before = stand.position();
                stand.travel(new Vec3(1,1,1));
                near(before.add(.1,.2,.3), stand.position(), "stand travel is passive and moves once");
                Vec3 local = new Vec3(.1,.2,.3);
                var frameAfter = cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess.authoritativeFrame(stand);
                Vec3 up = new Vec3(frameAfter.up().x(),frameAfter.up().y(),frameAfter.up().z());
                Vec3 expectedVelocity = local.subtract(up.scale(local.dot(up))).scale(.91)
                        .add(up.scale((local.dot(up) - (noGravity ? 0 : .04)) * .98));
                near(expectedVelocity, stand.getDeltaMovement(), "stand acceleration once, NoGravity only suppresses acceleration");
                equipment(level, stand, small);
                stand.setYRot(37); stand.tick();
                check(stand.yBodyRot==stand.getYRot(),"passive motion preserves ArmorStand authored yaw");
                Vec3 momentum=stand.getDeltaMovement();
                try {
                    var jump=LivingEntity.class.getDeclaredMethod("jumpFromGround"); jump.setAccessible(true); jump.invoke(stand);
                } catch(ReflectiveOperationException failure) { throw new AssertionError(failure); }
                near(momentum,stand.getDeltaMovement(),"passive stand cannot receive a GE jump");
                check(stand.getDeltaMovement().lengthSqr() > 0, "NoGravity preserves external motion");
                check(stand.isSmall() == small && !stand.isMarker(), "size flags retained");
                var frame = cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess.authoritativeFrame(stand);
                near(GravityEntityGeometry.bodyCenter(stand), stand.getBoundingBox().getCenter(), "body/proxy center agrees");
            } finally { stand.discard(); }
        }
        sizeTransactions(level);
        standRotationAndDamage(level);
        standOwnership(level);
        var marker = stand(level,false,true,false);
        GravityApplicationCoordinator.applyDirectAssignment(marker, new GravityState(Vec3d.X,.04));
        Vec3 before = marker.position(); marker.setDeltaMovement(.1,.2,.3); marker.travel(Vec3.ZERO);
        near(before,marker.position(),"Marker retains native exclusion");
        check(!GravityInfluencePolicy.usesCustomBody(marker), "Marker has no capsule"); marker.discard();
    }

    private static void installationCallbacks(ServerLevel level) {
        var block = ControlBoundaryChecks.GUARDED_FALLING.get();
        boolean enabled = block.enabled; block.enabled=true;
        try {
            for(int mode=0;mode<5;mode++) {
                clear(level); block.lands=0; block.broken=0;
                try(var lease=field(level,mode<2 ? new Vec3d(0,-.04,0) : new Vec3d(.04,0,0),1)) {
                    if(mode==4) lease.close(); // Absent FIELD must retain native default installation.
                    level.setBlock(SOURCE,block.defaultBlockState(),18);
                    level.setBlock(mode<2 || mode==4 ? SOURCE.below(2) : SOURCE.east(2),
                            (mode==1 ? Blocks.STONE_SLAB : Blocks.STONE).defaultBlockState(),18);
                    blockTick(level);
                    var actor=falling(level).getFirst();
                    actor.setDeltaMovement(mode<2 || mode==4 ? new Vec3(0,-2,0) : new Vec3(2,0,0));
                    if(mode==3) actor.disableDrop();
                    actor.tick();
                    check(block.lands==(mode==0 || mode==2 || mode==4 ? 1 : 0),"onLand only on installation mode="+mode);
                    check(block.broken==(mode==1 || mode==3 ? 1 : 0),"break callback once mode="+mode);
                    check(actor.isRemoved(),"installation/break lifecycle mode="+mode);
                    if(mode==0 || mode==2 || mode==4) {
                        var placement=mode==2?SOURCE.east():SOURCE.below();
                        check(level.getBlockState(placement).is(block),"callback installation restores actual BlockState mode="+mode);
                    }
                    long drops=SPAWNED.stream().filter(e->e instanceof ItemEntity && !e.isRemoved()).count();
                    check(drops==(mode==1?1:0),"single drop lifecycle mode="+mode+" count="+drops);
                }
            }
        } finally { block.enabled=enabled; clear(level); }
    }

    private static void endpointAuthority(ServerLevel level) {
        clear(level);
        var calls=new java.util.ArrayList<GravityFieldQuery>();
        var actor=FallingBlockEntity.fall(level,SOURCE,Blocks.GRAVEL.defaultBlockState());
        try(var lease=publish(level,q->{calls.add(q);return new GravityFieldSample(new Vec3d(0,.04,0));},1)) {
            GravityApplicationCoordinator.applyDirectAssignment(actor,new GravityState(Vec3d.X,.04));
            actor.setDeltaMovement(Vec3.ZERO); var start=actor.position(); actor.tick();
            near(start.add(.04,0,0),actor.position(),"DIRECT owns integration under opposing FIELD");
            check(calls.isEmpty(),"DIRECT endpoint cannot evaluate environmental FIELD");
        } finally { actor.discard(); clear(level); }
        actor=FallingBlockEntity.fall(level,SOURCE,Blocks.SAND.defaultBlockState());
        try(var old=field(level,new Vec3d(.04,0,0),1)) {
            actor.tick();
            var prior=actor.getDeltaMovement(); var start=actor.position();
            try(var replacement=field(level,new Vec3d(0,.04,0),2)) {
                old.close(); actor.tick();
                near(start.add(prior).add(0,.04,0),actor.position(),"replacement applies next operation once without rotating momentum");
            }
        } finally { actor.discard(); clear(level); }
    }

    private static void standOwnership(ServerLevel level) {
        var actor=stand(level,false,false,true);
        var vehicle=stand(level,false,false,true);
        try {
            var before=actor.position();actor.setDeltaMovement(.1,.2,.3);actor.tick();
            near(before,actor.position(),"absent NoGravity stand retains native immobility");
            try(var lease=field(level,new Vec3d(1,-2,.5),1)) {
                actor.tick();
                check(ArmorStandIntegration.ownsMotion(actor),"loaded NoGravity enters FIELD without stale noPhysics deadlock");
                check(actor.position().distanceToSqr(before)>0,"FIELD NoGravity permits external momentum");
            }
            actor.tick();
            /*
             * The lease above was closed by an authoritative removal, and this
             * entity already confirmed the publication live, so absence is
             * ordinary and live: no external completeness certification is
             * required for the ordinary removal case.
             */
            var state = GravityEntityAccess.cast(actor).gravityengine$gravityComponent().state();
            check(!state.fieldEvidenceUnknown(),
                    "authoritative removal resolves without a completeness call");
            check(state.assignedAuthority() == cc.sighs.gravityengine.gravity.model.GravityAuthorityMode.FIELD
                            && !state.assignedFieldPresent(),
                    "removed publication resolves as confirmed FIELD absence");
            check(!ArmorStandIntegration.ownsMotion(actor),"authoritative field removal exits stand ownership");
            before=actor.position();actor.travel(Vec3.ZERO);near(before,actor.position(),"absent native travel restored");
            GravityApplicationCoordinator.applyDirectAssignment(actor,new GravityState(Vec3d.X,.04));
            check(ArmorStandIntegration.ownsMotion(actor),"DIRECT takeover");
            actor.noPhysics=true;actor.tick();
            check(actor.noPhysics&&!ArmorStandIntegration.ownsMotion(actor),"explicit noPhysics is never cleared");
            actor.noPhysics=false;actor.tick();
            check(ArmorStandIntegration.ownsMotion(actor),"explicit noPhysics release re-enters");
            actor.startRiding(vehicle,true);actor.tick();
            check(!ArmorStandIntegration.ownsMotion(actor),"passenger native fallback");
            actor.stopRiding();actor.tick();
            check(ArmorStandIntegration.ownsMotion(actor),"dismount re-enters");
            try {
                var water=Entity.class.getDeclaredField("wasTouchingWater");water.setAccessible(true);
                water.setBoolean(actor,true);GravityApplicationCoordinator.updateBody(actor);
                check(!ArmorStandIntegration.ownsMotion(actor),"water native fallback");
                water.setBoolean(actor,false);GravityApplicationCoordinator.updateBody(actor);
                check(ArmorStandIntegration.ownsMotion(actor),"leaving water re-enters");
            } catch(ReflectiveOperationException error){throw new AssertionError(error);}
            flags(actor,false,true);check(!ArmorStandIntegration.ownsMotion(actor),"Marker fallback");
            flags(actor,false,false);actor.tick();check(ArmorStandIntegration.ownsMotion(actor),"Marker exit");
        } finally{actor.discard();vehicle.discard();}
    }

    private static void standRotationAndDamage(ServerLevel level) {
        var stand = stand(level,false,false,false);
        var ceiling = new BlockPos(8,286,8); var saved = level.getBlockState(ceiling);
        var player = new net.minecraft.world.entity.player.Player(level,BlockPos.ZERO,0,
                new com.mojang.authlib.GameProfile(UUID.randomUUID(),"stand-damage")) {
            public boolean isSpectator() { return false; }
            public boolean isCreative() { return false; }
        };
        try {
            stand.setPos(8.5,284.6,8.5);
            GravityApplicationCoordinator.applyDirectAssignment(stand,new GravityState(Vec3d.X,.04));
            var installedUp = GravityEntityAccess.cast(stand).gravityengine$gravityComponent()
                    .operationState().installedCollisionUp();
            var box = stand.getBoundingBox(); var anchor = stand.position();
            level.setBlock(ceiling,Blocks.STONE.defaultBlockState(),18);
            GravityApplicationCoordinator.applyDirectAssignment(stand,new GravityState(new Vec3d(0,-1,0),.04));
            var after = cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess.authoritativeFrame(stand);
            check(GravityEntityAccess.cast(stand).gravityengine$gravityComponent().operationState()
                            .installedCollisionUp().equals(installedUp) && stand.getBoundingBox().equals(box),
                    "blocked stand rotation retains installed axis and proxy independently of reference");
            check(after.down().equals(new Vec3d(0,-1,0)), "blocked collider does not roll back environmental reference");
            near(anchor,stand.position(),"blocked stand rotation retains anchor");
            level.setBlock(ceiling,Blocks.AIR.defaultBlockState(),18);
            stand.setItemSlot(EquipmentSlot.CHEST,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_CHESTPLATE));
            stand.lastHit=level.getGameTime()-10;
            var damage=level.damageSources().playerAttack(player);
            check(stand.hurt(damage,1) && !stand.isRemoved(),"first native hit preserves tilted stand");
            long drops=SPAWNED.stream().filter(e->e instanceof ItemEntity item && !e.isRemoved()
                    && item.getItem().is(net.minecraft.world.item.Items.DIAMOND_CHESTPLATE)).count();
            check(stand.hurt(damage,1) && stand.isRemoved(),"second native hit breaks tilted stand");
            check(SPAWNED.stream().filter(e->e instanceof ItemEntity item && !e.isRemoved()
                    && item.getItem().is(net.minecraft.world.item.Items.DIAMOND_CHESTPLATE)).count()==drops+1,
                    "native break drops equipment once");
            check(!stand.hurt(damage,1),"removed stand cannot repeat damage/drop callback");
        } finally { level.setBlock(ceiling,saved,18); stand.discard(); player.discard(); }
    }
}
