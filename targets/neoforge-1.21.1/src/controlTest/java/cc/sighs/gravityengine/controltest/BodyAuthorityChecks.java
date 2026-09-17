package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.collision.provider.*;
import cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator;
import cc.sighs.gravityengine.gravity.integration.EntityMovementIntegration;
import cc.sighs.gravityengine.gravity.integration.vanilla.VanillaBodyOccupancy;
import cc.sighs.gravityengine.gravity.kinematic.geometry.*;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.model.GravityCollisionRoute;
import cc.sighs.gravityengine.math.geometry.*;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Movement and real packet entry share the installed body authority. */
final class BodyAuthorityChecks {
    static void run(ServerLevel level) {
        var provider=new Provider(); RigidCollisionPublicationRegistry.register(level,provider);
        try {
            provider.bounds=new Aabb3d(9,298,6,9.1,304,10);
            var spider=new Spider(EntityType.SPIDER,level) {
                int callbacks;
                @Override protected void checkFallDamage(double d,boolean g,BlockState s,BlockPos p) { callbacks++; super.checkFallDamage(d,g,s,p); }
            };
            spider.setPos(8,300,8); spider.move(MoverType.SELF,new Vec3(2,0,0));
            near(spider.getX(),9-spider.getBbWidth()/2.0,"wide short spider wall");
            check(spider.callbacks==1 && physical(spider) instanceof OrientedBox,"spider native shape and callback");
            check(spider.getBbWidth()==1.4F && spider.getBbHeight()==.9F,"spider dimensions unchanged");
            spider.setPos(30,300,8); spider.move(MoverType.SELF,new Vec3(1,0,0)); near(spider.getX(),31,"leave related provider");
            spider.setPos(8,300,8); spider.move(MoverType.SELF,new Vec3(2,0,0)); near(spider.getX(),9-spider.getBbWidth()/2.0,"reenter provider");

            // Exact audit counterexample: box corner intersects, capsule does not.
            provider.bounds=new Aabb3d(8.26,300.8,8.26,8.29,301,8.29);
            for(boolean custom:new boolean[]{false,true}) {
                var movement=player(level,new Vec3(7,300,8),custom);
                var expected=custom?CharacterCapsule.class:OrientedBox.class;
                check(expected.isInstance(physical(movement)),"installed movement body");
                movement.move(MoverType.SELF,new Vec3(1,0,0));
                if(custom) near(movement.getX(),8,"capsule clears corner");
                else check(movement.getX()<8,"box collides with corner");
                check(movement.route==GravityCollisionRoute.EXACT_BODY && expected.isInstance(movement.observedBody),"solver route does not select shape");
                var packet=player(level,new Vec3(7,300,8),custom);
                packet.connection.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(8,300,8,false));
                check(packet.moves==1 && expected.isInstance(packet.observedBody),"actual handleMovePlayer body");
                if(custom) { near(packet.getX(),8,"capsule packet accepted"); check(packet.corrections==0,"capsule no false corner correction"); }
                else check(packet.corrections==1 && packet.getX()<8,"box packet rejects occupied corner: corrections="+packet.corrections+" position="+packet.position()+" body="+packet.observedBody);
                movement.discard(); packet.discard();
            }
            var switching=player(level,new Vec3(7,300,8),true);
            check(physical(switching) instanceof CharacterCapsule,"custom body installed");
            assign(switching,false);
            // Assignment changes desired reference; only the final server handoff
            // commits the installed axis before subsequent packet capture.
            check(physical(switching) instanceof CharacterCapsule,"assignment cannot silently relabel installed body");
            switching.move(MoverType.SELF,Vec3.ZERO);
            check(physical(switching) instanceof CharacterCapsule,"movement retains server-installed axis");
            cc.sighs.gravityengine.gravity.integration.geometry.PlayerBodyHandoff.afterConnectionTick(switching, false);
            check(physical(switching) instanceof OrientedBox,"server handoff restores box");
            switching.connection.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(8,300,8,false));
            check(switching.corrections==1,"shape handoff packet uses box"); switching.discard();
            provider.bounds=null;
            var removed=player(level,new Vec3(7,300,8),false);
            removed.connection.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(8,300,8,false));
            near(removed.getX(),8,"removed source packet accepted");
            check(removed.corrections==0 && removed.route==GravityCollisionRoute.VANILLA,"unrelated provider retains native route"); removed.discard();
            System.out.println("BODY_AUTHORITY_CHECKS_PASSED spider box capsule packet-corner shape-handoff unrelated-provider removal");
        } finally { RigidCollisionPublicationRegistry.unregister(level,provider.id()); }
    }
    /** Called with the real Sable wall still published, then again after removal. */
    static void sablePacket(ServerLevel level,boolean removed) {
        for(boolean custom:new boolean[]{false,true}) {
            if(!removed) {
                var ceiling=player(level,new Vec3(9,297.5,8),custom);
                ceiling.move(MoverType.SELF,new Vec3(0,1,0));
                check(ceiling.verticalCollision && ceiling.getY()>297.5
                        && physical(ceiling).enclosingAabb().maxY()<=299.5+1e-6,"Sable ceiling preserves exact box/capsule collision");
                ceiling.discard();
            }
            var actor=player(level,new Vec3(8,300,8),custom);
            actor.connection.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(9,300,8,false));
            check(actor.moves==1,"Sable real packet entered movement");
            check((custom?CharacterCapsule.class:OrientedBox.class).isInstance(actor.observedBody),"Sable packet installed shape");
            if(removed) { near(actor.getX(),9,"Sable removal next packet"); check(actor.corrections==0,"removed Sable no correction"); }
            else check(actor.corrections==1 && actor.getX()<9,"Sable occupied endpoint corrected");
            actor.discard();
        }
        System.out.println("SABLE_HANDLE_MOVE_PLAYER_PASSED removed="+removed);
    }
    private static CollisionBody physical(Entity entity) { return VanillaBodyOccupancy.capturePhysicalBody(entity); }
    private static Actor player(ServerLevel level,Vec3 position,boolean custom) {
        var profile=new GameProfile(UUID.randomUUID(),"BodyAuthority"); var actor=new Actor(level,profile);
        actor.setPos(position); if(custom) assign(actor,true);
        var connection=new Connection(PacketFlow.SERVERBOUND) {
            @Override public void send(Packet<?> packet) {}
            @Override public void send(Packet<?> packet,net.minecraft.network.PacketSendListener listener) {}
        };
        actor.connection=new ServerGamePacketListenerImpl(level.getServer(),connection,actor,CommonListenerCookie.createInitial(profile,false)) {
            @Override public void send(Packet<?> packet) { if(packet instanceof net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
                        || packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom
                        && custom.payload() instanceof cc.sighs.gravityengine.network.ClientboundPlayerBodyCommitPayload commit
                        && commit.correction()!=null) actor.corrections++; }
        };
        level.addNewPlayer(actor); return actor;
    }
    private static void assign(Actor actor,boolean custom) {
        var connection=actor.connection; actor.connection=null;
        try { GravityApplicationCoordinator.applyDirectAssignment(actor,new GravityState(custom?new Vec3d(0,-1,.01).normalized():new Vec3d(0,-1,0),.08)); }
        finally { actor.connection=connection; }
    }
    private static final class Actor extends ServerPlayer {
        int corrections,moves; CollisionBody observedBody; GravityCollisionRoute route;
        Actor(ServerLevel level,GameProfile profile) { super(level.getServer(),level,profile,net.minecraft.server.level.ClientInformation.createDefault()); }
        @Override public void move(MoverType type,Vec3 movement) { if(type==MoverType.PLAYER) moves++; super.move(type,movement); }
        @Override protected void checkFallDamage(double d,boolean grounded,BlockState state,BlockPos p) {
            observedBody=physical(this); route=EntityMovementIntegration.activeMovementCollisionRoute(this); super.checkFallDamage(d,grounded,state,p);
        }
    }
    private static final class Provider implements ExternalRigidCollisionProvider {
        Aabb3d bounds;
        @Override public Optional<DynamicCollisionObstacleSnapshot> resolve(RigidObstacleIdentity identity,
                cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext time) { return Optional.empty(); }
        @Override public String id() { return "body-authority-test"; }
        @Override public void capture(ExternalRigidCollisionQuery query,RigidPublicationCollector output) {
            if(bounds!=null && bounds.intersects(query.dynamicBounds())) output.addObstacle(new DynamicCollisionObstacleSnapshot(61,0,
                    OrientedBox.axisAligned(bounds),new RigidMotionSnapshot(new RigidPose(Vec3d.ZERO,OrthonormalFrame3d.IDENTITY),
                    Vec3d.ZERO,Vec3d.ZERO,query.time().gameTick(),1,1,1)));
        }
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    private static void near(double actual,double expected,String message) { check(Math.abs(actual-expected)<1e-6,message+" actual="+actual+" expected="+expected); }
}
