package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.api.*;
import cc.sighs.gravityengine.api.field.*;
import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.integration.FallingBlockRechecks;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;

/** Actual MinecraftServer -> ServerLevel -> LevelTicks -> Post level event, never scan.tick. */
final class PassiveSchedulingChecks {
    private static PassiveSchedulingChecks active;
    private static final net.minecraft.server.level.TicketType<net.minecraft.world.level.ChunkPos> FULL_ONLY =
            net.minecraft.server.level.TicketType.create("ge-passive-full-test",Comparator.comparingLong(net.minecraft.world.level.ChunkPos::toLong));
    private final MinecraftServer server;
    private final ServerLevel level;
    private final Runnable next;
    private final java.util.function.Consumer<ServerTickEvent.Post> listener=this::tick;
    private final List<net.minecraft.world.entity.Entity> spawned=new ArrayList<>();
    private final java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> joins=event->{
        if(event.getLevel()==levelForFixture() && event.getEntity() instanceof net.minecraft.world.entity.item.FallingBlockEntity)spawned.add(event.getEntity());
    };
    private ServerLevel levelForFixture(){return level;}
    private final Map<Long,Long> lastCall=new HashMap<>();
    private final Map<Long,Integer> calls=new HashMap<>();
    private final Map<BlockPos,net.minecraft.world.level.block.state.BlockState> saved=new HashMap<>();
    private final BlockPos a=new BlockPos(8,240,8), b=new BlockPos(24,240,8), cold=new BlockPos(1608,240,1608);
    private FieldPublication lease;
    private int age, phase=-1, phaseAge, previousCalls;
    private float rate;
    private Object loadStatus;
    private Map<Long,Object> loadStatuses;
    private boolean requireBudget;
    private int recoveryBefore;
    private int geCalls, previousGeCalls;
    private long timeBoundary;

    static void start(MinecraftServer server, Runnable next) {
        active=new PassiveSchedulingChecks(server,next);
        NeoForge.EVENT_BUS.addListener(active.listener);
        NeoForge.EVENT_BUS.addListener(active.joins);
    }
    private PassiveSchedulingChecks(MinecraftServer server,Runnable next) {
        this.server=server;this.next=next;level=server.getLevel(Level.NETHER);
        rate=server.tickRateManager().tickrate();server.tickRateManager().setTickRate(200);
        level.setChunkForced(0,0,true);level.setChunkForced(1,0,true);
        level.getChunk(100,100);
        var coldChunk=new net.minecraft.world.level.ChunkPos(100,100);
        level.getChunkSource().addRegionTicket(FULL_ONLY,coldChunk,0,coldChunk);
        ControlBoundaryChecks.GUARDED_FALLING.get().enabled=false;
    }
    static void callback(ServerLevel level,BlockPos pos) {
        var test=active;
        if(test==null||test.level!=level)return;
        long tick=level.getGameTime(), key=pos.asLong();
        if(Objects.equals(test.lastCall.put(key,tick),tick))throw new AssertionError("duplicate subclass callback same game tick at "+pos);
        test.calls.merge(key,1,Integer::sum);
        if (StackWalker.getInstance().walk(frames -> frames.anyMatch(f -> f.getClassName().equals(FallingBlockRechecks.class.getName())))) test.geCalls++;

    }
    private int total(){return calls.values().stream().mapToInt(Integer::intValue).sum();}
    private void check(boolean value,String message){if(!value)throw new AssertionError(message+" phase="+phase+" tick="+phaseAge);}
    private void raw(BlockPos pos,net.minecraft.world.level.block.state.BlockState state) {
        saved.putIfAbsent(pos,level.getBlockState(pos));
        var chunk=level.getChunkAt(pos);
        chunk.getSection(level.getSectionIndex(pos.getY())).setBlockState(pos.getX()&15,pos.getY()&15,pos.getZ()&15,state);
        // Section fixture deliberately creates no native onPlace/scheduled tick.
    }
    private void publish(int region,boolean time) {
        var previous=lease;
        final long boundary=timeBoundary;
        var volume=new GravityInfluenceVolume(){
            public boolean contains(Vec3d p){return region<0 || (p.x()>=region*16 && p.x()<(region+1)*16 && p.y()>=0 && p.y()<=320 && p.z()>=0 && p.z()<=16);}
            public Optional<GravityFieldBounds> finiteBounds(){return region<0?Optional.empty():Optional.of(new GravityFieldBounds(region*16,0,0,(region+1)*16,320,16));}
        };
        lease=GravityEngineApi.publish(level, com.example.examplemod.gravity.ProviderFixture.ID,GravityFieldDefinition.named(
                ResourceLocation.fromNamespaceAndPath("gravityengine_control_tests","schedule"),
                q->new GravityFieldSample(time&&q.gameTick()>=boundary?new Vec3d(0,.04,0):Vec3d.ZERO),volume,
                GravityFieldCompositionMode.OVERRIDE,phase+1));
        check(lease.accepted(),"current publication accepted");
        long revision=cc.sighs.gravityengine.gravity.field.GravityFieldRuntime.get(level).registry().publicationRevision();
        if(previous!=null)previous.close();
        check(revision==cc.sighs.gravityengine.gravity.field.GravityFieldRuntime.get(level).registry().publicationRevision(),"replaced lease close has no effect");
    }
    @SuppressWarnings("unchecked")
    private void entitiesReady(boolean ready) throws Exception {
        if(loadStatuses==null){
            var f=ServerLevel.class.getDeclaredField("entityManager");f.setAccessible(true);var manager=f.get(level);
            var statuses=manager.getClass().getDeclaredField("chunkLoadStatuses");statuses.setAccessible(true);
            loadStatuses=(Map<Long,Object>)statuses.get(manager);loadStatus=loadStatuses.get(0L);
        }
        if(ready)loadStatuses.put(0L,loadStatus);
        else loadStatuses.remove(0L); // no pending load remains; real level tick must honor entities-loaded=false
    }
    private void enter(int phase) throws Exception {
        this.phase=phase;phaseAge=0;previousCalls=total();
        System.out.println("PASSIVE_SCHEDULING_PHASE="+phase);
        var guarded=ControlBoundaryChecks.GUARDED_FALLING.get().defaultBlockState();
        switch(phase){
            case 0 -> {raw(a,Blocks.SAND.defaultBlockState());raw(b,guarded);}
            case 1 -> {publish(-1,false);raw(cold,Blocks.SAND.defaultBlockState());
                level.setBlock(b,Blocks.AIR.defaultBlockState(),18);level.setBlock(b,guarded,18);}
            case 2 -> {raw(a.east(),guarded);entitiesReady(false);}
            case 3 -> {entitiesReady(true);server.tickRateManager().setFrozen(true);}
            case 4 -> server.tickRateManager().stepGameIfPaused(100);
            case 5 -> {server.tickRateManager().setFrozen(false);lease.close();lease=null;recoveryBefore=calls.getOrDefault(b.asLong(),0);}
            case 6 -> {
                calls.clear();lastCall.clear();previousCalls=0;requireBudget=true;
                for(int x=0;x<32;x++)for(int z=0;z<16;z++)raw(new BlockPos(x,240,z),guarded);
                publish(-1,false);
            }
            case 7 -> {
                raw(a,Blocks.SAND.defaultBlockState());raw(b,Blocks.SAND.defaultBlockState());publish(0,false);
            }
            case 8 -> publish(1,false);
            case 9 -> {raw(a,Blocks.SAND.defaultBlockState());timeBoundary=level.getGameTime()+100;publish(-1,true);}
        }
    }
    private void tick(ServerTickEvent.Post event) {
        if(event.getServer()!=server)return;
        try {
            // Start tests assert native source replacement and joins; continuous motion has its own
            // retained suite. Dispose fixture entities before they fall through the generated Nether.
            for(var entity:spawned)entity.discard();spawned.clear();
            if(++age>2500)throw new AssertionError("scheduler fixture timeout");
            if(phase<0){
                if(!FallingBlockRechecks.mayExecute(level,0)||!FallingBlockRechecks.mayExecute(level,1))return;
                if(level.getChunkSource().getChunkNow(100,100)==null)return;
                check(!level.getChunkSource().isPositionTicking(net.minecraft.world.level.ChunkPos.asLong(100,100)),"cold fixture not block-ticking");
                enter(0);return;
            }
            phaseAge++;
            int now=total();
            // Native neighbor-scheduled callbacks have Vanilla's own budget. Only recheck calls
            // are charged to GE's unchanged 64-candidate budget (observed total=68, GE=64).
            if(requireBudget)check(geCalls-previousGeCalls<=FallingBlockRechecks.CHECKS_PER_TICK,"GE execution budget per real server tick");
            previousGeCalls=geCalls;
            previousCalls=now;
            switch(phase){
                case 0 -> {check(level.getBlockState(a).is(Blocks.SAND)&&total()==0,"never affected absent blocks unchanged");if(phaseAge==120)enter(1);}
                case 1 -> {
                    check(level.getBlockState(a).is(Blocks.SAND),"present-zero sand retained");
                    check(level.getBlockState(cold).is(Blocks.SAND),"FULL inactive chunk cannot execute");
                    if(phaseAge<80)check(calls.getOrDefault(b.asLong(),0)==0,"native placement delay preserved");
                    if(phaseAge==150){check(total()>0,"native/subsequent GE callbacks execute");enter(2);}
                }
                case 2 -> {check(!level.areEntitiesLoaded(0),"entities-ready fixture remains false");check(calls.getOrDefault(a.east().asLong(),0)==0,"entities-unready cannot invoke subclass");if(phaseAge==80)enter(3);}
                case 3 -> {check(now==previousFrozenCalls,"freeze prevents callback");if(phaseAge==40)enter(4);}
                case 4 -> {if(phaseAge==105){check(now>previousFrozenCalls,"step resumes budgeted callbacks");enter(5);}}
                case 5 -> {if(phaseAge==160){check(level.getBlockState(a).isAir(),"present-zero removal restores native falling");int recovered=calls.getOrDefault(b.asLong(),0);check(recovered==recoveryBefore+1,"exactly one recovery callback, then absent stays silent");enter(6);}}
                case 6 -> {if(phaseAge==180){check(lastCall.size()>=512,"dense candidates fairly visited");enter(7);}}
                case 7 -> {if(phaseAge==120){check(level.getBlockState(a).is(Blocks.SAND)&&level.getBlockState(b).isAir(),"finite replacement restores old area");raw(b,Blocks.SAND.defaultBlockState());enter(8);}}
                case 8 -> {if(phaseAge==120){check(level.getBlockState(a).isAir()&&level.getBlockState(b).is(Blocks.SAND),"replacement covers old and new area");enter(9);}}
                case 9 -> {if(phaseAge<100)check(level.getBlockState(a).is(Blocks.SAND),"time-dependent zero retained");if(phaseAge==400){check(level.getBlockState(a).isAir(),"infinite time-dependent field rechecked: source="+level.getBlockState(a)+" support="+level.getBlockState(a.above())+" sample="+cc.sighs.gravityengine.gravity.integration.FallingBlockStartIntegration.sample(level,a)+" ready="+FallingBlockRechecks.mayExecute(level,0));System.out.println("PASSIVE_SCHEDULING_PASSED native gates delay authorization freeze step replacement fairness");finish();next.run();}}
            }
            if(phase==3 && phaseAge==0)previousFrozenCalls=total();
        } catch(Throwable error){
            error.printStackTrace();finish();
            try{java.nio.file.Files.writeString(java.nio.file.Path.of("control-boundary-result.txt"),"FAIL "+error);}catch(Exception write){error.addSuppressed(write);}
            server.halt(false);
        }
    }
    private int previousFrozenCalls;
    private void finish(){
        NeoForge.EVENT_BUS.unregister(listener);active=null;
        NeoForge.EVENT_BUS.unregister(joins);
        if(lease!=null)lease.close();server.tickRateManager().setFrozen(false);server.tickRateManager().setTickRate(rate);
        if(loadStatuses!=null)loadStatuses.put(0L,loadStatus);
        for(var e:saved.entrySet())raw(e.getKey(),e.getValue());
        level.setChunkForced(0,0,false);level.setChunkForced(1,0,false);
        var coldChunk=new net.minecraft.world.level.ChunkPos(100,100);
        level.getChunkSource().removeRegionTicket(FULL_ONLY,coldChunk,0,coldChunk);
        cc.sighs.gravityengine.gravity.field.GravityFieldRuntime.remove(level);
        System.out.println("PASSIVE_SCHEDULING_FINISHED real-server-ticks="+age);
    }
}
