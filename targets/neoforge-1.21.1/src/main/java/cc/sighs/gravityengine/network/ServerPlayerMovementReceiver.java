package cc.sighs.gravityengine.network;

import cc.sighs.gravityengine.gravity.debug.GravityDebugLog;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import net.minecraft.server.level.ServerPlayer;

/** Main-thread client-owned endpoint acceptance through the single native movement handler. */
public final class ServerPlayerMovementReceiver {
    private static final ThreadLocal<ServerPlayer> ACTIVE = new ThreadLocal<>();
    private ServerPlayerMovementReceiver() {}

    public static boolean acceptsClientEndpoint(ServerPlayer player) {
        return ACTIVE.get() == player;
    }

    public static void receive(ServerPlayer player, ServerboundPlayerMovePayload payload) {
        if (player.isRemoved() || player.connection == null || player.connection.player != player
                || !payload.dimension().equals(player.level().dimension().location())) return;
        var parent = ACTIVE.get();
        ACTIVE.set(player);
        try {
            if (GravityDebugLog.shouldLogMovement(player)) {
                GravityDebugLog.movement(player, "NETWORK", "client-endpoint",
                        "predictionEpoch=%s currentEpoch=%s bypass=speed,residual,endpoint,floating",
                        payload.bodyEpoch(), GravityEntityAccess.cast(player)
                                .gravityengine$gravityComponent().state().applicationEpoch());
            }
            player.connection.handleMovePlayer(payload.movement());
        } finally {
            if (parent == null) ACTIVE.remove(); else ACTIVE.set(parent);
        }
    }
}
