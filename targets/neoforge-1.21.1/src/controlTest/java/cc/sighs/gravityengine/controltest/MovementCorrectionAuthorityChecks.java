package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.network.ClientboundPlayerBodyCommitPayload;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.*;
import net.minecraft.server.network.*;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Executes the transformed producer and Vanilla pending-ID/ACK lifecycle. */
final class MovementCorrectionAuthorityChecks {
    static void run(ServerLevel level) throws ReflectiveOperationException {
        List<Packet<?>> sent = new ArrayList<>();
        var profile = new GameProfile(UUID.randomUUID(), "CorrectionAuthority");
        var actor = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        actor.connection = new ServerGamePacketListenerImpl(level.getServer(), connection, actor,
                CommonListenerCookie.createInitial(profile, false)) {
            @Override public void send(Packet<?> packet) { sent.add(packet); }
        };
        try {
            actor.setPos(48, 250, 48);
            actor.setYRot(35); actor.setXRot(-17);
            actor.connection.resetPosition();
            // Deterministic moved-too-quickly branch; client view is deliberately newer.
            actor.connection.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(1000, 250, 48, false));
            var first = correction(sent);
            check(first.getRelativeArguments().equals(RelativeMovement.ROTATION), "only rotation is relative");
            check(first.getYRot() == 0 && first.getXRot() == 0, "stale server 35/-17 becomes wire 0/0");
            check(first.getX() == 48 && first.getY() == 250, "native authoritative position");

            var awaiting = ServerGamePacketListenerImpl.class.getDeclaredField("awaitingPositionFromClient");
            awaiting.setAccessible(true);
            var tick = ServerGamePacketListenerImpl.class.getDeclaredField("tickCount"); tick.setAccessible(true);
            tick.setInt(actor.connection, 30);
            actor.connection.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(48, 250, 48, false));
            var retry = correction(sent);
            check(retry.getId() != first.getId(), "Vanilla allocates resend ID");
            check(retry.getRelativeArguments().equals(RelativeMovement.ROTATION)
                    && retry.getYRot() == 0 && retry.getXRot() == 0, "timeout retains position-only authority");
            actor.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(first.getId()));
            check(awaiting.get(actor.connection) != null, "old ACK does not finish newer correction");
            actor.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(retry.getId()));
            check(awaiting.get(actor.connection) == null, "matching native ACK finishes correction");

            var wall = new net.minecraft.core.BlockPos(49, 250, 48);
            var wallState = level.getBlockState(wall);
            var topState = level.getBlockState(wall.above());
            try {
                level.setBlock(wall, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 18);
                level.setBlock(wall.above(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 18);
                actor.connection.resetPosition();
                actor.connection.handleMovePlayer(new ServerboundMovePlayerPacket.PosRot(49.5, 250, 48.5, 120, 23, false));
                var collision = correction(sent);
                check(collision.getId() != retry.getId(), "real occupied endpoint produces a new correction");
                check(collision.getRelativeArguments().equals(RelativeMovement.ROTATION)
                        && collision.getYRot() == 0 && collision.getXRot() == 0,
                        "collision rejection also preserves look despite mismatching packet/server angles");
                actor.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(collision.getId()));
            } finally {
                level.setBlock(wall, wallState, 18);
                level.setBlock(wall.above(), topState, 18);
            }

            actor.connection.teleport(49, 251, 48, 70, 12, EnumSet.of(RelativeMovement.X));
            var explicit = correction(sent);
            check(explicit.getRelativeArguments().equals(Set.of(RelativeMovement.X)), "explicit position flags intact");
            check(explicit.getX() == 1 && explicit.getYRot() == 70 && explicit.getXRot() == 12,
                    "explicit rotation teleport keeps absolute look");
            tick.setInt(actor.connection, 60);
            actor.connection.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(49, 251, 48, false));
            var explicitRetry = correction(sent);
            check(explicitRetry.getRelativeArguments().isEmpty() && explicitRetry.getYRot() == 70,
                    "explicit resend is still look authoritative");
            actor.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(explicitRetry.getId()));
            check(awaiting.get(actor.connection) == null, "explicit native ACK");
            cc.sighs.gravityengine.gravity.integration.geometry.PlayerBodyHandoff.geometryRelocation(actor,
                    () -> actor.connection.teleport(49.01, 251.02, 48, actor.getYRot(), actor.getXRot(), RelativeMovement.ROTATION));
            var relocation = body(sent);
            check(relocation.mode() == ClientboundPlayerBodyCommitPayload.CommitMode.RELOCATION,
                    "geometry producer carries no-impulse semantics");
            tick.setInt(actor.connection, 90);
            actor.connection.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(49.01, 251.02, 48, false));
            var relocationRetry = body(sent);
            check(relocationRetry.mode() == ClientboundPlayerBodyCommitPayload.CommitMode.RELOCATION
                    && relocationRetry.correction().getId() != relocation.correction().getId(),
                    "native timeout retains geometry relocation provenance");
            actor.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(relocationRetry.correction().getId()));
            actor.connection.teleport(49, 251, 48, 70, 12);
            check(body(sent).mode() == ClientboundPlayerBodyCommitPayload.CommitMode.CORRECTION,
                    "next native teleport does not inherit geometry provenance");
            System.out.println("MOVEMENT_CORRECTION_AUTHORITY_PASSED speed collision stale-look resend explicit-rotation native-ack");
        } finally { actor.discard(); }
    }

    private static ClientboundPlayerPositionPacket correction(List<Packet<?>> sent) {
        return body(sent).correction();
    }
    private static ClientboundPlayerBodyCommitPayload body(List<Packet<?>> sent) {
        for (int i = sent.size() - 1; i >= 0; i--) {
            if (sent.get(i) instanceof ClientboundCustomPayloadPacket custom
                    && custom.payload() instanceof ClientboundPlayerBodyCommitPayload body
                    && body.correction() != null) return body;
        }
        throw new AssertionError("No native correction envelope: " + sent);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
