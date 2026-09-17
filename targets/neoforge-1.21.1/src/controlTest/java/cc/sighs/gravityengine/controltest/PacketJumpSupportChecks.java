package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator;
import cc.sighs.gravityengine.gravity.integration.geometry.PlayerBodyHandoff;
import cc.sighs.gravityengine.network.ClientboundPlayerBodyCommitPayload;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.*;
import net.minecraft.server.network.*;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Exercises the actual .249 jump invocation, including a native relocation ACK/reply. */
final class PacketJumpSupportChecks {
    private static final Vec3d UP = new Vec3d(-.3751410896934297, .9051085866106957, .20011898777281173);

    static void run(ServerLevel level) {
        var saved = new HashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
        var actor = new Actor(level);
        try {
            for (int x = 60; x <= 62; x++) for (int z = 60; z <= 62; z++) {
                var pos = new BlockPos(x, 249, z);
                saved.put(pos, level.getBlockState(pos));
                level.setBlock(pos, Blocks.STONE.defaultBlockState(), 18);
            }
            actor.setPos(61.5, 250, 61.5);
            GravityApplicationCoordinator.applyDirectAssignment(actor, new GravityState(UP.negate(), .08));
            actor.connect();
            PlayerBodyHandoff.afterConnectionTick(actor, false);
            var floorPosition = actor.position().add(0, 250 - actor.getBoundingBox().minY, 0);
            reset(actor, floorPosition.add(0, .0001611003, 0));
            PlayerBodyHandoff.geometryRelocation(actor, () -> actor.connection.teleport(
                    floorPosition.x, floorPosition.y, floorPosition.z, 0, 0,
                    EnumSet.of(RelativeMovement.X, RelativeMovement.Z, RelativeMovement.Y_ROT, RelativeMovement.X_ROT)));
            var correction = actor.sent.stream()
                    .filter(p -> p instanceof ClientboundCustomPayloadPacket c
                            && c.payload() instanceof ClientboundPlayerBodyCommitPayload b && b.correction() != null)
                    .map(p -> ((ClientboundPlayerBodyCommitPayload)((ClientboundCustomPayloadPacket)p).payload()).correction())
                    .reduce((a, b) -> b).orElseThrow();
            actor.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(correction.getId()));
            actor.setOnGround(true);
            var tangent = new Vec3(-.006540673024304056, 0, -.003369180763755253);
            check(tangent.dot(new Vec3(UP.x(), UP.y(), UP.z())) > .0017, "logged delta has positive reference-up");
            for (int i = 0; i < 8; i++) {
                int before = actor.moves;
                send(actor, actor.position().add(tangent));
                check(actor.moves == before + 1, "one native packet move per reply");
                check(actor.jumps == 0 && actor.getDeltaMovement().lengthSqr() < 1e-18,
                        "relocation reply/floor tangent creates no impulse");
                check(actor.onGround(), "collision reacquires floor despite false packet ground flag");
            }
            reset(actor, floorPosition);
            send(actor, floorPosition.add(tangent).add(0, 1e-8, 0));
            check(actor.jumps == 0, "contact-sized numerical lift is not a jump");
            reset(actor, floorPosition);
            send(actor, floorPosition.add(tangent).add(0,
                    cc.sighs.gravityengine.gravity.collision.GravityGroundProbe.PROBE_DISTANCE / 2, 0));
            check(actor.jumps == 0, "displacement within the existing fresh-support band is not a jump");
            reset(actor, floorPosition.add(0, .1, 0));
            send(actor, actor.position().add(0, .1, 0));
            check(actor.jumps == 0, "stale grounded flag without contact cannot authorize impulse");
            reset(actor, floorPosition);
            send(actor, floorPosition.add(UP.x() * .42, UP.y() * .42, UP.z() * .42));
            check(actor.jumps == 1, "real support departure invokes native jump exactly once");
            check(Math.abs(actor.velocityBeforeMove.dot(new Vec3(UP.x(), UP.y(), UP.z())) - .42) < 1e-6,
                    "native jump impulse precedes the single move");
            reset(actor, floorPosition);
            var old = cc.sighs.gravityengine.gravity.integration.geometry.MovementValidationBody.capture(actor);
            long epoch = ClientboundPlayerBodyCommitPayload.capture(actor, null).applicationEpoch();
            var newer = new cc.sighs.gravityengine.gravity.integration.geometry.MovementValidationBody(
                    old.dimensions(), old.pose(), old.eyeHeight(), new Vec3d(-.45, .87, .2).normalized());
            check(newer.fits(actor), "newer tilted body is clear at old anchor");
            newer.install(actor);
            ClientboundPlayerBodyCommitPayload.capture(actor, null);
            check(cc.sighs.gravityengine.gravity.integration.collision.GravityCurrentSupportQuery
                    .stableSupport(actor).isEmpty(), "newer body has a real support gap");
            int moves = actor.moves;
            var jumpTarget = floorPosition.add(UP.x() * .42, UP.y() * .42, UP.z() * .42);
            cc.sighs.gravityengine.network.ServerPlayerMovementReceiver.receive(actor,
                    new cc.sighs.gravityengine.network.ServerboundPlayerMovePayload(level.dimension().location(), epoch,
                            new ServerboundMovePlayerPacket.Pos(jumpTarget.x, jumpTarget.y, jumpTarget.z, false)));
            check(actor.jumps == 2 && actor.moves == moves + 1,
                    "delayed packet support query uses admitted historical body before one native jump/move");
            System.out.println("PACKET_JUMP_SUPPORT_PASSED relocation-reply tangent noise absent-contact real-jump historical-body one-native-move");
        } finally {
            saved.forEach((pos, state) -> level.setBlock(pos, state, 18));
            actor.discard();
        }
        var nativeActor = new Actor(level);
        try {
            nativeActor.connect();
            reset(nativeActor, new Vec3(61.5, 254, 61.5));
            send(nativeActor, nativeActor.position().add(0, .42, 0));
            check(nativeActor.jumps == 1 && nativeActor.moves == 1,
                    "inactive custom collision retains the native packet jump gate");
        } finally { nativeActor.discard(); }
    }

    private static void reset(Actor actor, Vec3 position) {
        actor.setPos(position);
        actor.setDeltaMovement(Vec3.ZERO);
        actor.setOnGround(true);
        actor.connection.resetPosition();
    }
    private static void send(Actor actor, Vec3 position) {
        actor.connection.handleMovePlayer(new ServerboundMovePlayerPacket.PosRot(
                position.x, position.y, position.z, 0, 0, false));
    }
    private static final class Actor extends ServerPlayer {
        final List<Packet<?>> sent = new ArrayList<>();
        int moves, jumps;
        Vec3 velocityBeforeMove;
        Actor(ServerLevel level) {
            super(level.getServer(), level, new GameProfile(UUID.randomUUID(), "PacketJumpSupport"), ClientInformation.createDefault());
        }
        void connect() {
            var wire = new Connection(PacketFlow.SERVERBOUND) {
                @Override public void send(Packet<?> packet) {}
                @Override public void send(Packet<?> packet, net.minecraft.network.PacketSendListener listener) {}
            };
            connection = new ServerGamePacketListenerImpl(serverLevel().getServer(), wire,
                    this, CommonListenerCookie.createInitial(getGameProfile(), false)) {
                @Override public void send(Packet<?> packet) { sent.add(packet); }
            };
            serverLevel().addNewPlayer(this);
        }
        @Override public void jumpFromGround() { jumps++; super.jumpFromGround(); }
        @Override public void move(MoverType type, Vec3 movement) {
            if (type == MoverType.PLAYER) { moves++; velocityBeforeMove = getDeltaMovement(); }
            super.move(type, movement);
        }
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
