package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import io.netty.channel.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.util.concurrent.atomic.AtomicInteger;

@EventBusSubscriber(modid="gravityengine_control_tests",value=Dist.CLIENT)
public final class MultiplayerClientChecks {
    private static boolean connecting,finished,reconnected,respawned;
    private static int current,stable,offlineTicks;
    private static long began;
    private static Connection observed;
    private static final AtomicInteger delayed=new AtomicInteger();
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if(!MultiplayerChecks.ENABLED||finished) return;
        var mc=Minecraft.getInstance();
        var name=mc.getUser().getName();
        if(began==0) began=System.nanoTime();
        try {
            if(System.nanoTime()-began>420_000_000_000L) throw new AssertionError("client deadline phase="+current+" player="+(mc.player==null?null:mc.player.position()));
            int phase=MultiplayerChecks.phase();
            if(phase==9) {
                if(delayed.get()==0) throw new AssertionError("no real inbound packets delayed");
                MultiplayerChecks.write(name+"-PASS.txt","PASS phases=8 inboundDelayMillis=100 delayedPackets="+delayed.get());
                finished=true;mc.stop();return;
            }
            if(phase==6&&!reconnected&&mc.player!=null) {
                reconnected=true;mc.disconnect(new TitleScreen());connecting=false;offlineTicks=40;return;
            }
            if(offlineTicks>0) {offlineTicks--;return;}
            if(mc.player==null) {
                if(!connecting&&mc.getOverlay()==null&&mc.screen!=null) {
                    connecting=true;
                    String address="127.0.0.1:"+Integer.getInteger("gravityengine.multiplayerPort");
                    ConnectScreen.startConnecting(new TitleScreen(),mc,ServerAddress.parseString(address),
                            new ServerData("GE verification",address,ServerData.Type.OTHER),false,null);
                }
                return;
            }
            mc.options.pauseOnLostFocus=false;
            mc.setWindowActive(true);
            if(mc.player.isDeadOrDying()) {respawned=true;mc.player.respawn();return;}
            if(observed!=mc.player.connection.getConnection()) {
                observed=mc.player.connection.getConnection();
                observed.channel().pipeline().addBefore("packet_handler","ge_multiplayer_delay",new ChannelInboundHandlerAdapter() {
                    @Override public void channelRead(ChannelHandlerContext context,Object message) throws Exception {
                        // Delay the whole inbound game stream together, preserving ordering
                        // between native lifecycle packets and GE payloads.
                        delayed.incrementAndGet();
                        context.executor().schedule(()->context.fireChannelRead(message),100,java.util.concurrent.TimeUnit.MILLISECONDS);
                    }
                });
            }
            if(phase==0||phase>8) return;
            if(current!=phase) {current=phase;stable=0;}
            if(phase==5&&!respawned||phase==6&&!reconnected) return;
            if(!mc.level.dimension().equals(phase==4?net.minecraft.world.level.Level.NETHER:net.minecraft.world.level.Level.OVERWORLD)) {stable=0;return;}
            for(int i=0;i<MultiplayerChecks.NAMES.length;i++) {
                String expectedName=MultiplayerChecks.NAMES[i];
                var player=mc.level.players().stream().filter(p->p.getGameProfile().getName().equals(expectedName)).findFirst().orElse(null);
                if(player==null||player.position().distanceTo(MultiplayerChecks.position(phase,i))>.02
                        ||GravityFrameAccess.authoritativeFrame(player).down().distance(MultiplayerChecks.down(phase))>1e-6) {stable=0;return;}
                var anchor=player.position();var body=GravityEntityGeometry.body(player);
                mc.gameRenderer.getMainCamera().setup(mc.level,player,false,false,.5f);
                if(!anchor.equals(player.position())||!body.equals(GravityEntityGeometry.body(player))) throw new AssertionError("camera mutated physical pose");
            }
            if(++stable==30) {
                MultiplayerChecks.write(name+"-phase-"+phase+".txt","PASS own/observer pose, gravity, camera phase="+phase);
                System.out.println("MULTIPLAYER_CLIENT_PHASE_PASSED "+name+" phase="+phase);
            }
        } catch(Throwable failure) {
            failure.printStackTrace();MultiplayerChecks.write(name+"-FAIL.txt",failure.toString());finished=true;mc.stop();
        }
    }
}
