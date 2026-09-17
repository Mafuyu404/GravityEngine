package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator;
import cc.sighs.gravityengine.gravity.integration.geometry.MovementValidationBody;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.network.*;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Real .249 move/occupancy/correction path with the exact axes from latest.log. */
final class MovementBodyVersionChecks {
    static void run(ServerLevel level) {
        var profile = new GameProfile(UUID.randomUUID(), "BodyEpoch");
        var actor = new Actor(level, profile);
        var sent = new ArrayList<Packet<?>>();
        var floor = new BlockPos(-55, 212, -36);
        var saved = new HashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
        try {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                var pos = floor.offset(dx, 0, dz);
                saved.put(pos, level.getBlockState(pos));
                level.setBlock(pos, Blocks.STONE.defaultBlockState(), 18);
            }
            var start = new Vec3(-54.47533560300333, 212.97447053696612, -35.83928186229294);
            var target = new Vec3(-54.48680428895487, 212.932658679687, -35.905567948837195);
            actor.setPos(start);
            var oldUp = new Vec3d(.12366528407500979, .887764292788568, .4433750759393911);
            GravityApplicationCoordinator.applyDirectAssignment(actor, new GravityState(oldUp.multiply(-1), .08));
            actor.connection = new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND),
                    actor, CommonListenerCookie.createInitial(profile, false)) {
                @Override public void send(Packet<?> packet) { sent.add(packet); }
            };
            level.addNewPlayer(actor);
            actor.connection.resetPosition();
            var old = MovementValidationBody.capture(actor);
            long epoch = ClientboundPlayerBodyCommitPayload.capture(actor, null).applicationEpoch();
            var newUp = new Vec3d(.12350409959240435, .8888542857189842, .4412310008860031);
            var newer = new MovementValidationBody(old.dimensions(), old.pose(), old.eyeHeight(), newUp);
            check(newer.fits(actor), "new body legal at original anchor");
            newer.install(actor);
            long latest = ClientboundPlayerBodyCommitPayload.capture(actor, null).applicationEpoch();
            check(latest > epoch, "geometry publication advances existing epoch");
            var assignment = GravityEntityAccess.cast(actor).gravityengine$gravityComponent().state().assignedState();
            sent.clear();
            ServerPlayerMovementReceiver.receive(actor, new ServerboundPlayerMovePayload(level.dimension().location(),
                    epoch, new ServerboundMovePlayerPacket.Pos(target.x, target.y, target.z, true)));
            check(actor.position().distanceTo(target) < 1e-9, "old body move accepted at requested surface endpoint");
            check(actor.moves == 1, "exactly one native PLAYER move");
            check(corrections(sent) == 0, "no false correction for in-flight old body");
            check(old.sameGeometry(MovementValidationBody.capture(actor)), "newer penetrating body cannot be restored");
            check(!newer.fits(actor), "log regression really distinguishes old and new occupancy");
            check(GravityEntityAccess.cast(actor).gravityengine$gravityComponent().state().assignedState().equals(assignment),
                    "history does not roll back assignment");
            long retained = ClientboundPlayerBodyCommitPayload.capture(actor, null).applicationEpoch();
            check(retained > latest, "retained legal body published as new truth");
            check(sent.stream().anyMatch(p -> p instanceof ClientboundCustomPayloadPacket c
                    && c.payload() instanceof ClientboundPlayerBodyCommitPayload b
                    && b.mode() == ClientboundPlayerBodyCommitPayload.CommitMode.TRANSACTION),
                    "retained geometry reaches local player as a transaction");

            // A second old-body move into clear space can restore the newer geometry.
            actor.setPos(start.add(0, 1, 0));
            old.install(actor);
            long clearOld = ClientboundPlayerBodyCommitPayload.capture(actor, null).applicationEpoch();
            newer.install(actor);
            long clearNew = ClientboundPlayerBodyCommitPayload.capture(actor, null).applicationEpoch();
            old.install(actor);
            newer.install(actor);
            check(ClientboundPlayerBodyCommitPayload.capture(actor, null).applicationEpoch() == clearNew,
                    "temporary historical installation cannot create duplicate publication epochs");
            actor.connection.resetPosition();
            var clearTarget = actor.position().add(.02, 0, 0);
            ServerPlayerMovementReceiver.receive(actor, new ServerboundPlayerMovePayload(level.dimension().location(),
                    clearOld, new ServerboundMovePlayerPacket.Pos(clearTarget.x, clearTarget.y, clearTarget.z, false)));
            check(newer.sameGeometry(MovementValidationBody.capture(actor)), "newer body restored when legal");

            actor.setPos(target);
            old.install(actor);
            retained = ClientboundPlayerBodyCommitPayload.capture(actor, null).applicationEpoch();

            actor.connection.resetPosition();
            sent.clear();
            ServerPlayerMovementReceiver.receive(actor, new ServerboundPlayerMovePayload(level.dimension().location(),
                    retained, new ServerboundMovePlayerPacket.Pos(target.x, target.y - .1, target.z, true)));
            check(corrections(sent) == 1, "real floor penetration still triggers native correction");
            var correction = lastCorrection(sent);
            actor.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(correction.getId()));
            sent.clear();
            ServerPlayerMovementReceiver.receive(actor, new ServerboundPlayerMovePayload(level.dimension().location(),
                    retained, new ServerboundMovePlayerPacket.StatusOnly(true)));
            check(corrections(sent) == 1, "teleport invalidates even same-geometry old versions");
            System.out.println("MOVEMENT_BODY_VERSION_PASSED log-surface-repro one-native-move retained-body real-penetration teleport-expiry");
        } finally {
            saved.forEach((pos, state) -> level.setBlock(pos, state, 18));
            actor.discard();
        }
    }
    private static long corrections(List<Packet<?>> sent) {
        return sent.stream().filter(p -> p instanceof ClientboundCustomPayloadPacket c
                && c.payload() instanceof ClientboundPlayerBodyCommitPayload b && b.correction() != null).count();
    }
    private static ClientboundPlayerPositionPacket lastCorrection(List<Packet<?>> sent) {
        return sent.stream().filter(p -> p instanceof ClientboundCustomPayloadPacket c
                        && c.payload() instanceof ClientboundPlayerBodyCommitPayload b && b.correction() != null)
                .map(p -> ((ClientboundPlayerBodyCommitPayload)((ClientboundCustomPayloadPacket)p).payload()).correction())
                .reduce((a, b) -> b).orElseThrow();
    }
    private static final class Actor extends ServerPlayer {
        int moves;
        Actor(ServerLevel level, GameProfile profile) { super(level.getServer(), level, profile, ClientInformation.createDefault()); }
        @Override public void move(MoverType type, Vec3 movement) {
            if (type == MoverType.PLAYER) moves++;
            super.move(type, movement);
        }
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
