package cc.sighs.gravityengine.gravity.minecraft.access;

/** Read-only native teleport gate; GE does not mirror its lifetime. */
public interface PlayerMovementConnectionAccess {
    boolean gravityengine$awaitingTeleport();
}
