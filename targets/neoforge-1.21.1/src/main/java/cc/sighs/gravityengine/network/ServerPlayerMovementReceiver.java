package cc.sighs.gravityengine.network;

import cc.sighs.gravityengine.gravity.integration.geometry.MovementValidationBody;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.access.PlayerMovementConnectionAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.RelativeMovement;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.WeakHashMap;

/** MAIN-thread admission of a server-issued body epoch, followed by Vanilla's one movement handler. */
public final class ServerPlayerMovementReceiver {
    private record History(ResourceLocation dimension, MovementBodyHistory<MovementValidationBody> bodies) {}
    private static final Map<ServerPlayer, History> HISTORY = new WeakHashMap<>();
    private static final ThreadLocal<Validation> ACTIVE = new ThreadLocal<>();
    private static final class Validation {
        final ServerPlayer player;
        final MovementValidationBody body;
        MovementValidationBody previous;
        Validation(ServerPlayer player, MovementValidationBody body) { this.player = player; this.body = body; }
    }
    private ServerPlayerMovementReceiver() {}

    public static void published(ServerPlayer player) {
        var dimension = player.level().dimension().location();
        var history = HISTORY.get(player);
        if (history == null || !history.dimension.equals(dimension)) {
            history = new History(dimension, new MovementBodyHistory<>(32, 20));
            HISTORY.put(player, history);
        }
        history.bodies.publish(GravityEntityAccess.cast(player).gravityengine$gravityComponent()
                .state().applicationEpoch(), MovementValidationBody.capture(player), player.level().getGameTime());
    }

    /** Native discontinuities retire all pre-teleport geometry, including same-body versions. */
    public static void invalidate(ServerPlayer player) {
        HISTORY.remove(player);
        GravityEntityAccess.cast(player).gravityengine$gravityComponent().state().advanceBodyPublicationEpoch();
    }

    public static void receive(ServerPlayer player, ServerboundPlayerMovePayload payload) {
        if (player.isRemoved() || !payload.dimension().equals(player.level().dimension().location())) return;
        var connection = player.connection;
        // Vanilla owns teleport gating, invalid input and disconnect behavior. No body mutation before these gates.
        var move = payload.movement();
        if (((PlayerMovementConnectionAccess) connection).gravityengine$awaitingTeleport()
                || !Double.isFinite(move.getX(player.getX())) || !Double.isFinite(move.getY(player.getY()))
                || !Double.isFinite(move.getZ(player.getZ())) || !Float.isFinite(move.getYRot(player.getYRot()))
                || !Float.isFinite(move.getXRot(player.getXRot()))) {
            connection.handleMovePlayer(move);
            return;
        }
        var history = HISTORY.get(player);
        var body = history == null ? null : history.bodies.accept(payload.bodyEpoch(), player.level().getGameTime()).orElse(null);
        if (body == null) { correct(player); return; }
        var validation = new Validation(player, body);
        var parent = ACTIVE.get();
        ACTIVE.set(validation);
        try {
            connection.handleMovePlayer(move);
        } catch (RuntimeException | Error failure) {
            if (validation.previous != null && HISTORY.get(player) == history) {
                try { validation.previous.install(player); }
                catch (RuntimeException | Error rollback) { failure.addSuppressed(rollback); }
            }
            throw failure;
        } finally {
            if (parent == null) ACTIVE.remove(); else ACTIVE.set(parent);
        }
        // A native correction already retired this operation and published the actual validation body.
        var current = validation.previous;
        if (current == null || HISTORY.get(player) != history || player.isRemoved()) return;
        if (current.fits(player)) {
            current.install(player);
        } else {
            // Accepted old-body endpoint is not permission to penetrate with the newer body.
            // Retain legal geometry as new truth; the normal server handoff can retry later.
            GravityEntityAccess.cast(player).gravityengine$gravityComponent().state().advanceBodyPublicationEpoch();
            var snapshot = ClientboundPlayerBodyCommitPayload.capture(player, null);
            PacketDistributor.sendToPlayer(player, snapshot.withMode(ClientboundPlayerBodyCommitPayload.CommitMode.TRANSACTION));
            PacketDistributor.sendToPlayersTrackingEntity(player, snapshot);
        }
    }

    /** Called after native input, teleport, passenger/sleeping and speed gates, before old-bounds capture. */
    public static boolean prepareAtNativeMovement(ServerPlayer player) {
        var validation = ACTIVE.get();
        if (validation == null || validation.player != player) return true;
        var current = MovementValidationBody.capture(player);
        if (current.sameGeometry(validation.body)) return true;
        // Retired geometry cannot authorize a different application/representation route.
        if (current.exact() != validation.body.exact()
                || (current.up() == null) != (validation.body.up() == null)
                || !validation.body.fits(player)) {
            correct(player);
            return false;
        }
        validation.body.install(player);
        validation.previous = current;
        return true;
    }

    private static void correct(ServerPlayer player) {
        player.connection.teleport(player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(), RelativeMovement.ROTATION);
    }
}
