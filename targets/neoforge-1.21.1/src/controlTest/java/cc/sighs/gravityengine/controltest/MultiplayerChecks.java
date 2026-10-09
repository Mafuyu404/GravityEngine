package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator;
import cc.sighs.gravityengine.network.GravitySyncService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;

/** Test orchestration uses fresh local files; all entity state travels through
 * native game connections and the existing production synchronization. */
@EventBusSubscriber(modid="gravityengine_control_tests")
public final class MultiplayerChecks {
    static final boolean ENABLED=Boolean.getBoolean("gravityengine.multiplayerVerification");
    static final String[] NAMES={"GEFirst","GESecond"};
    static final Path ROOT=Path.of(System.getProperty("gravityengine.multiplayerEvidence","."));
    private static int phase,ticks;
    private static long began;
    private static boolean finished,waitingReplacement;
    private static List<ServerPlayer> oldPlayers=List.of();
    static Vec3d down(int phase) {
        return phase==1?new Vec3d(0,-1,0):phase==2?new Vec3d(1,0,0):new Vec3d(.3,-.7,.2).normalized();
    }
    static Vec3 position(int phase,int index) { return new Vec3((phase==7?4096:16)+index*4,phase==4?200:304,16); }
    static void write(String file,String value) {
        try { Files.writeString(ROOT.resolve(file),value); }
        catch(java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    static boolean exists(String file) { return Files.exists(ROOT.resolve(file)); }
    static int phase() {
        try { return Integer.parseInt(Files.readString(ROOT.resolve("phase.txt")).trim()); }
        catch(java.io.IOException|NumberFormatException pending) { return 0; }
    }
    private static void place(net.minecraft.server.MinecraftServer server,List<ServerPlayer> players) {
        var level=server.getLevel(phase==4?Level.NETHER:Level.OVERWORLD);
        for(int i=0;i<players.size();i++) {
            var player=players.get(i); var target=position(phase,i);
            player.getAbilities().invulnerable=true;
            player.getAbilities().mayfly=false; player.getAbilities().flying=false;
            player.onUpdateAbilities();
            player.teleportTo(level,target.x,target.y,target.z,0,0);
            player.setDeltaMovement(Vec3.ZERO);
            GravityApplicationCoordinator.applyDirectAssignment(player,new GravityState(down(phase),0));
            GravitySyncService.syncPlayer(player);
            player.connection.resumeFlushing();
        }
        write("phase.txt",Integer.toString(phase));
        System.out.println("MULTIPLAYER_PHASE="+phase);
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if(!ENABLED||finished||!event.getServer().isDedicatedServer()) return;
        if(began==0) began=System.nanoTime();
        try {
            if(System.nanoTime()-began>420_000_000_000L) throw new AssertionError("multiplayer deadline phase="+phase);
            for(String name:NAMES) if(exists(name+"-FAIL.txt")) throw new AssertionError("client failed: "+name);
            var server=event.getServer();
            var players=Arrays.stream(NAMES).map(name->server.getPlayerList().getPlayerByName(name)).toList();
            if(players.stream().anyMatch(Objects::isNull)) return;
            if(phase==0) {phase=1;place(server,players);}
            if(waitingReplacement) {
                if(players.stream().anyMatch(oldPlayers::contains)) return;
                waitingReplacement=false;
                place(server,players);
            }
            if(++ticks<40) return;
            for(String name:NAMES) if(!exists(name+"-phase-"+phase+".txt")) return;
            if(phase==8) {
                write("server-PASS.txt","PASS two real clients: delayed corrections, observer geometry, dimension, death/respawn, reconnect, chunk streaming");
                write("phase.txt","9"); finished=true; server.halt(false);return;
            }
            phase++;ticks=0;
            if(phase==5||phase==6) {
                oldPlayers=List.copyOf(players);waitingReplacement=true;
                write("phase.txt",Integer.toString(phase));
                if(phase==5) for(var player:players) player.kill();
            } else place(server,players);
        } catch(Throwable failure) {
            failure.printStackTrace();write("server-FAIL.txt",failure.toString());
            finished=true;event.getServer().halt(false);
        }
    }
}
