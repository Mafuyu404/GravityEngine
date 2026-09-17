package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.client.ClientGravityFrameSampler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.nbt.CompoundTag;

/** Real tracking packet -> client body -> renderer check; client-only control-test subscriber calls it. */
final class ClientPassiveCompatibilityChecks {
    private record Evidence(int id, Vec3d down, float width, float height) {}
    private static volatile java.util.List<Evidence> evidence;
    private static volatile Throwable failure;
    private static boolean checked;
    private static int phase, age;
    private static ArmorStand tracked;
    private static volatile int replacementId=-1;
    private static final java.util.Map<net.minecraft.core.BlockPos,net.minecraft.world.level.block.state.BlockState> obstruction=new java.util.HashMap<>();

    private static boolean pending(ArmorStand stand) {
        return ((cc.sighs.gravityengine.gravity.minecraft.access.GravityArmorStandAccess)stand).gravityengine$pendingDimensions();
    }
    private static byte rawFlags(ArmorStand stand) {
        try {
            var f=ArmorStand.class.getDeclaredField("DATA_CLIENT_FLAGS");f.setAccessible(true);
            @SuppressWarnings("unchecked") var key=(net.minecraft.network.syncher.EntityDataAccessor<Byte>)f.get(null);
            return stand.getEntityData().get(key);
        } catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static void update(Minecraft mc,boolean small,boolean marker) {
        int id=tracked.getId();
        mc.getSingleplayerServer().execute(()->{
            try {
                var stand=(ArmorStand)mc.getSingleplayerServer().overworld().getEntity(id);
                PassiveCompatibilityChecks.flags(stand,small,marker);
                if(stand.isSmall()!=small||stand.isMarker()!=marker)throw new AssertionError("server fixture metadata rejected");
            }catch(Throwable e){failure=e;}
        });
    }
    private static void obstruct(Minecraft mc,boolean blocked) {
        if(blocked) {
            var p=tracked.blockPosition();
            for(int x=-1;x<=1;x++)for(int y=0;y<=2;y++)for(int z=-1;z<=1;z++){
                var at=p.offset(x,y,z);obstruction.put(at,mc.level.getBlockState(at));
                mc.level.setBlock(at,net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),18);
            }
        } else {
            obstruction.forEach((p,s)->mc.level.setBlock(p,s,18));obstruction.clear();
        }
    }
    /** Stall only the unrelated synthetic input fixture, while both real client/server ticks continue. */
    static boolean convergence(Minecraft mc) {
        if(failure!=null)throw new AssertionError("authoritative metadata fixture",failure);
        if(++age>200)throw new AssertionError("metadata convergence timeout phase="+phase);
        switch(phase) {
            case 0 -> {tracked=(ArmorStand)mc.level.getEntity(evidence.get(1).id());obstruct(mc,true);update(mc,false,false);advance();}
            case 1 -> {
                if((rawFlags(tracked)&1)!=0)return false;
                if(!pending(tracked)||!tracked.isSmall())throw new AssertionError("blocked client must retain installed Small and authoritative target");
                if(age<12)return false;
                update(mc,true,false);advance();
            }
            case 2 -> {
                if((rawFlags(tracked)&1)==0||pending(tracked))return false;
                update(mc,false,false);advance();
            }
            case 3 -> {if((rawFlags(tracked)&1)!=0||!pending(tracked))return false;obstruct(mc,false);advance();}
            case 4 -> {
                if(pending(tracked)||tracked.isSmall())return false;
                if(GravityEntityGeometry.dimensions(tracked).height()!=evidence.get(0).height())throw new AssertionError("grown body did not converge");
                assertRendered(mc,tracked);update(mc,false,true);advance();
            }
            case 5 -> {
                if(!tracked.isMarker()||pending(tracked))return false;
                if(GravityEntityGeometry.dimensions(tracked).height()!=0)throw new AssertionError("Marker dimensions did not converge");
                update(mc,true,false);advance();
            }
            case 6 -> {
                if(tracked.isMarker()||!tracked.isSmall()||pending(tracked))return false;
                if(GravityEntityGeometry.dimensions(tracked).height()<=0)throw new AssertionError("Marker exit did not converge");
                assertRendered(mc,tracked);obstruct(mc,true);update(mc,false,false);advance();
            }
            case 7 -> {
                if(!pending(tracked))return false;
                int id=tracked.getId();mc.getSingleplayerServer().execute(()->mc.getSingleplayerServer().overworld().getEntity(id).discard());advance();
            }
            case 8 -> {
                if(!tracked.isRemoved())return false;
                if(pending(tracked))throw new AssertionError("removed entity retained pending metadata");
                obstruct(mc,false);var uuid=tracked.getUUID();var pos=tracked.position();
                mc.getSingleplayerServer().execute(()->{
                    try {
                        var level=mc.getSingleplayerServer().overworld();var stand=new ArmorStand(level,pos.x,pos.y,pos.z);
                        stand.setUUID(uuid);stand.setNoGravity(true);PassiveCompatibilityChecks.flags(stand,true,false);
                        GravityApplicationCoordinator.applyDirectAssignment(stand,new GravityState(new Vec3d(1,-2,.5),.04));
                        level.addFreshEntity(stand);cc.sighs.gravityengine.network.GravitySyncService.syncTracking(stand);replacementId=stand.getId();
                    } catch(Throwable e){failure=e;}
                });advance();
            }
            case 9 -> {
                if(!(mc.level.getEntity(replacementId) instanceof ArmorStand replacement))return false;
                if(!replacement.isSmall())return false;
                if(pending(replacement)||replacement.isMarker())throw new AssertionError("old target revived on replacement entity");
                assertRendered(mc,replacement);
                mc.getSingleplayerServer().execute(()->{
                    for(var item:evidence){var e=mc.getSingleplayerServer().overworld().getEntity(item.id());if(e!=null)e.discard();}
                    var e=mc.getSingleplayerServer().overworld().getEntity(replacementId);if(e!=null)e.discard();
                });
                System.out.println("CLIENT_ARMOR_METADATA_CONVERGENCE_PASSED blocked grow supersede marker remove replacement renderer");advance();return true;
            }
            default -> {return true;}
        }
        return false;
    }
    private static void advance(){phase++;age=0;}
    private static void assertRendered(Minecraft mc,ArmorStand stand) {
        var frame=GravityFrameAccess.authoritativeFrame(stand);var render=ClientGravityFrameSampler.sample(stand,1);
        if(render.center().distanceToSqr(stand.getBoundingBox().getCenter())>1e-8
                ||render.frame().down().distanceSquared(frame.down())>1e-10)throw new AssertionError("converged render/body differ");
        var stack=new com.mojang.blaze3d.vertex.PoseStack();
        mc.getEntityRenderDispatcher().getRenderer(stand).render(stand,stand.getYRot(),1,stack,mc.renderBuffers().bufferSource(),15728880);
        if(!stack.clear())throw new AssertionError("render pose stack leaked");
    }
    static void start(Minecraft minecraft) {
        var playerId = minecraft.player.getUUID();
        minecraft.getSingleplayerServer().execute(() -> {
            try {
                var player = minecraft.getSingleplayerServer().getPlayerList().getPlayer(playerId);
                var found = new java.util.ArrayList<Evidence>();
                for (boolean small : new boolean[]{false,true}) {
                    var stand = new ArmorStand(player.serverLevel(),player.getX()+2,player.getY()+1,player.getZ()+2);
                    var tag = new CompoundTag(); tag.putBoolean("Small",small); tag.putBoolean("NoGravity",true);
                    stand.load(tag); stand.setPos(player.getX()+2,player.getY()+1,player.getZ()+2+(small?2:0));
                    GravityApplicationCoordinator.applyDirectAssignment(stand,new GravityState(new Vec3d(1,-2,.5),.04));
                    player.serverLevel().addFreshEntity(stand);
                    cc.sighs.gravityengine.network.GravitySyncService.syncTracking(stand);
                    var dimensions = GravityEntityGeometry.dimensions(stand);
                    found.add(new Evidence(stand.getId(),GravityFrameAccess.authoritativeFrame(stand).down(),dimensions.width(),dimensions.height()));
                }
                evidence = java.util.List.copyOf(found);
            } catch (Throwable error) { failure = error; }
        });
    }
    static void verify(Minecraft minecraft) {
        if (checked) return;
        if (failure != null) throw new AssertionError("server stand fixture",failure);
        if (evidence == null) throw new AssertionError("server stand fixture not ready");
        for (var expected : evidence) {
            if (!(minecraft.level.getEntity(expected.id()) instanceof ArmorStand stand))
                throw new AssertionError("tracked stand missing " + expected.id());
            var dimensions = GravityEntityGeometry.dimensions(stand);
            var physical = GravityFrameAccess.authoritativeFrame(stand);
            if (physical.down().distanceSquared(expected.down()) > 1e-10
                    || dimensions.width()!=expected.width() || dimensions.height()!=expected.height())
                throw new AssertionError("observer body differs from server");
            var render = ClientGravityFrameSampler.sample(stand,1);
            if (render.frame().down().distanceSquared(physical.down())>1e-10
                    || render.center().distanceToSqr(stand.getBoundingBox().getCenter())>1e-8)
                throw new AssertionError("render/body center or axis mismatch");
            var stack = new com.mojang.blaze3d.vertex.PoseStack();
            minecraft.getEntityRenderDispatcher().getRenderer(stand).render(stand,stand.getYRot(),1,stack,
                    minecraft.renderBuffers().bufferSource(),15728880);
            if (!stack.clear()) throw new AssertionError("ArmorStand render leaked pose stack");
        }
        checked=true;
        System.out.println("CLIENT_PASSIVE_COMPATIBILITY_PASSED tracked normal-small body renderer");
    }
}
