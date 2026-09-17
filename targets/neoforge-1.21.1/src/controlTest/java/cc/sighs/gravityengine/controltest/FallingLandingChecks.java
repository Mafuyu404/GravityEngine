package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.api.*;
import cc.sighs.gravityengine.api.field.*;
import cc.sighs.gravityengine.api.math.Vec3d;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;

/** Actual scheduled start -> server entity ticks -> observed BlockState installation. */
final class FallingLandingChecks {
    private final MinecraftServer server;
    private final Runnable next;
    private final java.util.function.Consumer<ServerTickEvent.Post> listener=this::tick;
    private final List<FallingBlockEntity> actors=new ArrayList<>();
    private final Map<BlockPos,net.minecraft.world.level.block.state.BlockState> saved=new HashMap<>();
    private final java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> joins=e->{
        if(e.getEntity() instanceof FallingBlockEntity actor && e.getLevel()==level() && actor.getStartPos().equals(this.source)) {
            actors.add(actor);
            if(this.scenario>=18) actor.setDeltaMovement(8,0,0);
        }
    };
    private FieldPublication lease;
    private int scenario=-1,age;
    private final BlockPos source=new BlockPos(8,270,8);
    private BlockPos expected;
    private Block block;
    private final float rate;
    private net.minecraft.server.level.ServerLevel level(){return server.overworld();}
    static void start(MinecraftServer server,Runnable next){
        var check=new FallingLandingChecks(server,next);
        NeoForge.EVENT_BUS.addListener(check.listener);NeoForge.EVENT_BUS.addListener(check.joins);
    }
    private FallingLandingChecks(MinecraftServer server,Runnable next){
        this.server=server;this.next=next;rate=server.tickRateManager().tickrate();
        server.tickRateManager().setTickRate(200);level().setChunkForced(0,0,true);
    }
    private void set(BlockPos pos,Block block){
        saved.putIfAbsent(pos.immutable(),level().getBlockState(pos));level().setBlock(pos,block.defaultBlockState(),18);
    }
    private void enter(){
        cleanup();age=0;scenario++;
        for(var p:BlockPos.betweenClosed(source.offset(-4,-4,-4),source.offset(4,4,4)))set(p,Blocks.AIR);
        var down=scenario<18?Direction.values()[scenario/3]:Direction.EAST;
        var n=down.getNormal();var acceleration=new Vec3d(n.getX(),n.getY(),n.getZ()).multiply(.04);
        var support=source.relative(down,3);expected=support.relative(down.getOpposite());
        set(support,scenario>=18?Blocks.IRON_BARS:Blocks.STONE);
        var volume=new GravityInfluenceVolume(){
            public boolean contains(Vec3d p){return p.x()>3 && p.x()<14 && p.y()>260 && p.y()<280 && p.z()>3 && p.z()<14;}
            public Optional<GravityFieldBounds> finiteBounds(){return Optional.of(new GravityFieldBounds(3,260,3,14,280,14));}
        };
        // First direction DOWN deliberately uses the wholly absent registry/native path.
        if(down!=Direction.DOWN) lease=GravityEngineApi.publish(level(), com.example.examplemod.gravity.ProviderFixture.ID,GravityFieldDefinition.named(
                ResourceLocation.fromNamespaceAndPath("gravityengine_control_tests","falling_landing"),
                q->new GravityFieldSample(acceleration),volume,GravityFieldCompositionMode.OVERRIDE,scenario+1));
        block=switch(scenario%3){case 0->Blocks.SAND;case 1->Blocks.GRAVEL;default->Blocks.ANVIL;};set(source,block);
    }
    private void tick(ServerTickEvent.Post event){
        if(event.getServer()!=server)return;
        try{
            if(scenario<0){enter();return;}
            age++;
            if(actors.size()>1)throw new AssertionError("duplicate native start scenario="+scenario);
            if(!actors.isEmpty() && actors.getFirst().isRemoved()){
                var actor=actors.getFirst();
                if(!level().getBlockState(expected).equals(actor.getBlockState()))
                    throw new AssertionError("entity consumed without BlockState installation scenario="+scenario+" expected="+expected+" state="+level().getBlockState(expected));
                if(scenario>=18 && actor.getBoundingBox().maxX>11.43751)
                    throw new AssertionError("high-speed bars penetration "+actor.position());
                if(scenario<20){enter();return;}
                finish();System.out.println("FALLING_LANDING_REAL_TICKS_PASSED scenarios=21 installed_states=21");next.run();return;
            }
            if(age>80)throw new AssertionError("no completed landing scenario="+scenario+" actors="+actors);
        }catch(Throwable error){
            finish();error.printStackTrace();
            try{java.nio.file.Files.writeString(java.nio.file.Path.of("control-boundary-result.txt"),"FAIL "+error);}
            catch(java.io.IOException write){error.addSuppressed(write);}
            server.halt(false);
        }
    }
    private void cleanup(){
        if(lease!=null){lease.close();lease=null;}
        // Cleanup is never a success condition; all accepted scenarios verify installation first.
        actors.stream().filter(a->!a.isRemoved()).forEach(FallingBlockEntity::discard);actors.clear();
        saved.forEach((p,s)->level().setBlock(p,s,18));saved.clear();
    }
    private void finish(){NeoForge.EVENT_BUS.unregister(listener);NeoForge.EVENT_BUS.unregister(joins);cleanup();level().setChunkForced(0,0,false);server.tickRateManager().setTickRate(rate);}
}
