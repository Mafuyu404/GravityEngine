package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.api.FieldPresence;
import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.client.ClientGravitySyncService;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.integration.BallisticGravityIntegration;
import cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator;
import cc.sighs.gravityengine.gravity.integration.geometry.GravityApplicationBarrier;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.model.*;
import cc.sighs.gravityengine.network.SyncGravityStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;

/** Real replica admission and deferred installation; no client producer exists. */
final class ClientFieldCoverageChecks {
    static void run(Minecraft minecraft) {
        var actor = new ItemEntity(EntityType.ITEM, minecraft.level);
        actor.setPos(minecraft.player.position().add(0, 20, 0));
        var state = GravityEntityAccess.cast(actor).gravityengine$gravityComponent().state();
        var seed = new GravityState(new Vec3d(1, 0, 0), .04);
        var application = new CommittedGravityApplication(seed, GravitySuppressionReason.NONE,
                GravityApplicationPlan.ballistic(GravityAccelerationMode.FIELD));
        var packet = new SyncGravityStatePayload(actor.getId(), actor.getUUID(),
                actor.level().dimension().location(), 10, seed.down(), seed.strength(), 0,
                GravitySuppressionReason.NONE, GravityAuthorityMode.FIELD, FieldPresence.UNKNOWN, 5, application);
        try {
            try (var barrier = GravityApplicationBarrier.hold(actor)) {
                ClientGravitySyncService.applySnapshot(actor, packet);
                check(state.fieldPresence() == FieldPresence.UNKNOWN, "UNKNOWN replica evidence");
                check(!state.appliedPlan().usesFieldAcceleration(), "barrier retains installed application");
                check(state.pendingApplication() != null, "server application retained for retry");
                GravityApplicationCoordinator.applyRemoteApplication(actor,
                        CommittedGravityApplication.vanillaNoAssignment(), 4);
            }
            GravityApplicationCoordinator.updateReplicaBody(actor);
            check(state.committedApplication().equals(application) && state.applicationEpoch() == 5,
                    "retry installs server plan and epoch, ignoring older packets");
            check(state.fieldReferenceInForce() && !state.assignedFieldPresent(),
                    "committed continuity does not fabricate presence");
            check(BallisticGravityIntegration.applyGravity(actor, 1), "UNKNOWN committed ballistic continuity applies");
            check(actor.getDeltaMovement().distanceToSqr(new net.minecraft.world.phys.Vec3(.04, 0, 0)) < 1e-20,
                    "client uses server committed acceleration");
            check(state.assignmentRevision() == 0, "client never creates durable truth");
        } finally { actor.discard(); }
        System.out.println("CLIENT_FIELD_COVERAGE_CHECKS_PASSED unknown deferred stale application continuity");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
