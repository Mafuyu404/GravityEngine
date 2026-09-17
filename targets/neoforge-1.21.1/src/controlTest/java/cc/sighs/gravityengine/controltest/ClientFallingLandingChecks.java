package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.api.*;
import cc.sighs.gravityengine.api.field.*;
import cc.sighs.gravityengine.api.math.Vec3d;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/** Real integrated-server FIELD start, tracking packets, settled client entity and renderer. */
final class ClientFallingLandingChecks {
    private static boolean started, done;
    private static int age;
    private static double lastX=Double.NaN;
    private static volatile int id=-1;
    private static volatile Throwable failure;
    private static FieldPublication lease;
    private static BlockPos source;
    private static final Map<BlockPos,net.minecraft.world.level.block.state.BlockState> saved=new HashMap<>();
    static boolean tick(Minecraft mc) {
        if(done) return true;
        if(failure!=null) throw new AssertionError("client falling fixture",failure);
        if(++age>200) throw new AssertionError("client falling tracking/contact timeout id="+id);
        if(!started) {
            started=true;
            var player=mc.player.getUUID();
            mc.getSingleplayerServer().execute(()->{
                try {
                    var serverPlayer=mc.getSingleplayerServer().getPlayerList().getPlayer(player);
                    var level=serverPlayer.serverLevel(); source=serverPlayer.blockPosition().offset(2,4,2);
                    for(var p:BlockPos.betweenClosed(source.offset(-1,-1,-1),source.offset(13,2,1))) {
                        saved.put(p.immutable(),level.getBlockState(p));level.setBlock(p,Blocks.AIR.defaultBlockState(),18);
                    }
                    for(int y=-1;y<=2;y++)for(int z=-1;z<=1;z++)level.setBlock(source.offset(12,y,z),Blocks.STONE.defaultBlockState(),18);
                    var volume=new GravityInfluenceVolume() {
                        public boolean contains(Vec3d p) {return p.x()>source.getX()-1 && p.x()<source.getX()+15
                                && p.y()>source.getY()-1 && p.y()<source.getY()+3 && p.z()>source.getZ()-1 && p.z()<source.getZ()+2;}
                        public Optional<GravityFieldBounds> finiteBounds(){return Optional.empty();}
                    };
                    lease=GravityEngineApi.publish(level, com.example.examplemod.gravity.ProviderFixture.ID,GravityFieldDefinition.named(ResourceLocation.fromNamespaceAndPath(
                            "gravityengine_control_tests","client_falling"),q->new GravityFieldSample(new Vec3d(.04,0,0)),volume,
                            GravityFieldCompositionMode.OVERRIDE,1));
                    level.setBlock(source,Blocks.SAND.defaultBlockState(),18);
                    level.getBlockState(source).tick(level,source,level.random);
                    var actors=level.getEntitiesOfClass(FallingBlockEntity.class,new net.minecraft.world.phys.AABB(source).inflate(1));
                    id=actors.getFirst().getId();
                }catch(Throwable error){failure=error;}
            });
            return false;
        }
        if (id>=0 && mc.level.getEntity(id) instanceof FallingBlockEntity actor) {
            var snapshot=((cc.sighs.gravityengine.gravity.minecraft.access.GravityFallingBlockAccess)actor).gravityengine$ballisticSnapshot();
            if (snapshot != null && snapshot.customAcceleration()) {
                if (snapshot.down()!=net.minecraft.core.Direction.EAST || snapshot.magnitude()!=.04)
                    throw new AssertionError("tracked ballistic acceleration differs from server");
                if (actor.getY()!=source.getY()) throw new AssertionError("tracked actor predicts conflicting vertical gravity");
                if (Double.isFinite(lastX) && actor.getX()<lastX-.02) throw new AssertionError("tracked actor snapped backward: "+lastX+" -> "+actor.getX());
                lastX=actor.getX();
            }
        }
        if(age<55 || id<0) return false;
        if(mc.level.getEntity(id)!=null) return false;
        if(!mc.level.getBlockState(source.east(11)).is(Blocks.SAND))
            throw new AssertionError("client saw entity removal without installed sand at "+source.east(11));
        mc.getSingleplayerServer().execute(()->{
            var level=mc.getSingleplayerServer().overworld();var entity=level.getEntity(id);if(entity!=null)entity.discard();
            lease.close();saved.forEach((p,s)->level.setBlock(p,s,18));saved.clear();
        });
        done=true;System.out.println("CLIENT_FALLING_LANDING_PASSED field start removal installed block update");return true;
    }
}
