package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator;
import cc.sighs.gravityengine.controltest.InstalledBodyFixture;
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
                @Override public void disconnect(net.minecraft.network.chat.Component reason) { actor.invalidDisconnects++; }
            };
            level.addNewPlayer(actor);
            actor.connection.resetPosition();
            var old = InstalledBodyFixture.capture(actor);
            long epoch = ClientboundPlayerBodyCommitPayload.capture(actor, null).applicationEpoch();
            var newUp = new Vec3d(.12350409959240435, .8888542857189842, .4412310008860031);
            var newer = new InstalledBodyFixture(old.dimensions(), old.pose(), old.eyeHeight(), newUp);
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
            check(newer.sameGeometry(InstalledBodyFixture.capture(actor)), "packet never installs historical geometry");
            check(GravityEntityAccess.cast(actor).gravityengine$gravityComponent().state().assignedState().equals(assignment),
                    "packet preserves assignment");
            for (long diagnosticEpoch : new long[] { Long.MAX_VALUE, 0, epoch }) {
                actor.connection.resetPosition();
                sent.clear();
                var endpoint = actor.position().add(0, -.1, 0);
                int moves = actor.moves;
                ServerPlayerMovementReceiver.receive(actor, new ServerboundPlayerMovePayload(level.dimension().location(),
                        diagnosticEpoch, new ServerboundMovePlayerPacket.Pos(endpoint.x, endpoint.y, endpoint.z, true)));
                check(actor.position().distanceTo(endpoint) < 1e-9 && actor.moves == moves + 1,
                        "unknown/regressed epoch and occupied endpoint accepted through one move");
                check(corrections(sent) == 0, "wrapped penetration produces no correction");
                check(newer.sameGeometry(InstalledBodyFixture.capture(actor)), "installed body unchanged");
                check(!ServerPlayerMovementReceiver.acceptsClientEndpoint(actor), "scope closes after packet");
            }

            // Default gravity is included, even across an in-flight exact -> native transition.
            actor.setPos(start.add(0, 10, 0));
            GravityApplicationCoordinator.applyDirectAssignment(actor, GravityState.DEFAULT);
            cc.sighs.gravityengine.gravity.integration.geometry.PlayerBodyHandoff.afterConnectionTick(actor, false);
            var nativeBody = InstalledBodyFixture.capture(actor);
            check(!nativeBody.exact(), "default-gravity handoff installs native representation");
            actor.connection.resetPosition();
            sent.clear();
            var fast = actor.position().add(12, 0, 0);
            var momentum = new Vec3(.1, .2, .3);
            actor.setDeltaMovement(momentum);
            ServerPlayerMovementReceiver.receive(actor, new ServerboundPlayerMovePayload(level.dimension().location(),
                    epoch, new ServerboundMovePlayerPacket.Pos(fast.x, fast.y, fast.z, false)));
            check(actor.position().equals(fast) && corrections(sent) == 0, "wrapped speed accepted across native transition");
            check(nativeBody.sameGeometry(InstalledBodyFixture.capture(actor)), "native geometry retained");
            check(actor.getDeltaMovement().equals(momentum), "epoch mismatch does not reset world momentum");
            try {
                var floating = ServerGamePacketListenerImpl.class.getDeclaredField("clientIsFloating");
                floating.setAccessible(true);
                check(!floating.getBoolean(actor.connection), "wrapped floating is suppressed");
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }

            // Raw native retains its speed gate outside the scope.
            actor.connection.resetPosition();
            sent.clear();
            actor.connection.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(fast.x + 12, fast.y, fast.z, false));
            check(corrections(sent) == 1, "raw native speed still corrects");
            var correction = lastCorrection(sent);
            int moves = actor.moves;
            ServerPlayerMovementReceiver.receive(actor, new ServerboundPlayerMovePayload(level.dimension().location(),
                    epoch, new ServerboundMovePlayerPacket.Pos(fast.x + 1, fast.y, fast.z, false)));
            check(actor.moves == moves && actor.position().equals(fast), "pending teleport still gates wrapped movement");
            actor.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(correction.getId()));
            sent.clear();
            ServerPlayerMovementReceiver.receive(actor, new ServerboundPlayerMovePayload(level.dimension().location(),
                    epoch, new ServerboundMovePlayerPacket.StatusOnly(true)));
            check(corrections(sent) == 0, "old diagnostic epoch remains accepted after native ACK");

            // A residual can disagree even when both endpoints are clear. This
            // fixture omits translation inside its one native move callback.
            actor.skipTranslation = true;
            actor.connection.resetPosition();
            var residualTarget = actor.position().add(1, 0, 0);
            sent.clear();
            ServerPlayerMovementReceiver.receive(actor, new ServerboundPlayerMovePayload(level.dimension().location(),
                    0, new ServerboundMovePlayerPacket.Pos(residualTarget.x, residualTarget.y, residualTarget.z, false)));
            check(actor.position().equals(residualTarget) && corrections(sent) == 0,
                    "wrapped clear-endpoint residual disagreement is accepted");
            actor.connection.resetPosition();
            sent.clear();
            actor.connection.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(
                    residualTarget.x + 1, residualTarget.y, residualTarget.z, false));
            if (net.neoforged.fml.ModList.get().isLoaded("sable")) {
                check(corrections(sent) == 0, "raw Sable moved-wrongly bypass is not restored by GE");
            } else {
                check(corrections(sent) == 1, "raw native residual rejection remains");
                actor.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(lastCorrection(sent).getId()));
            }
            actor.skipTranslation = false;

            // Reverse representation transition also accepts the in-flight native epoch.
            GravityApplicationCoordinator.applyDirectAssignment(actor, new GravityState(oldUp.negate(), .08));
            cc.sighs.gravityengine.gravity.integration.geometry.PlayerBodyHandoff.afterConnectionTick(actor, false);
            check(InstalledBodyFixture.capture(actor).exact(), "return to exact representation");
            sent.clear();
            actor.connection.resetPosition();
            ServerPlayerMovementReceiver.receive(actor, new ServerboundPlayerMovePayload(level.dimension().location(),
                    0, new ServerboundMovePlayerPacket.StatusOnly(false)));
            check(corrections(sent) == 0 && InstalledBodyFixture.capture(actor).exact(), "native epoch does not restore native geometry");

            int movesBeforeInvalid = actor.moves;
            ServerPlayerMovementReceiver.receive(actor, new ServerboundPlayerMovePayload(level.dimension().location(),
                    0, new ServerboundMovePlayerPacket.Pos(Double.NaN, actor.getY(), actor.getZ(), false)));
            check(actor.invalidDisconnects == 1 && actor.moves == movesBeforeInvalid,
                    "native invalid-value disconnect remains before physical movement");
            check(!ServerPlayerMovementReceiver.acceptsClientEndpoint(actor), "invalid packet closes scope");

            var unchanged = actor.position();
            ServerPlayerMovementReceiver.receive(actor, new ServerboundPlayerMovePayload(
                    net.minecraft.resources.ResourceLocation.parse("gravityengine:other"), epoch,
                    new ServerboundMovePlayerPacket.Pos(1, 2, 3, false)));
            check(actor.position().equals(unchanged), "dimension mismatch drops packet");

            actor.failMove = true;
            boolean failed = false;
            try {
                ServerPlayerMovementReceiver.receive(actor, new ServerboundPlayerMovePayload(level.dimension().location(),
                        epoch, new ServerboundMovePlayerPacket.StatusOnly(true)));
            } catch (IllegalStateException expected) { failed = true; }
            check(failed && !ServerPlayerMovementReceiver.acceptsClientEndpoint(actor),
                    "unexpected native failure propagates and closes acceptance scope");
            actor.failMove = false;
            System.out.println("MOVEMENT_BODY_VERSION_PASSED client-endpoint one-native-move no-history unknown-regressed-epochs speed penetration floating native-ack scope-cleanup corrections=0");
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
        int moves, invalidDisconnects;
        boolean failMove, skipTranslation;
        Actor(ServerLevel level, GameProfile profile) { super(level.getServer(), level, profile, ClientInformation.createDefault()); }
        @Override public void move(MoverType type, Vec3 movement) {
            if (type == MoverType.PLAYER) moves++;
            if (failMove) throw new IllegalStateException("fixture move failure");
            if (skipTranslation) return;
            super.move(type, movement);
        }
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
