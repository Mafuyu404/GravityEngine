package cc.sighs.gravityengine.gravity.integration.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.geometry.BodyRepresentation;
import cc.sighs.gravityengine.gravity.geometry.GravityGeometryTransitionPlanner;
import cc.sighs.gravityengine.gravity.integration.collision.MinecraftCollisionSceneCapture;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.collision.MinecraftCollisionGeometryAdapter;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;

/** Immutable server publication evidence. No anchor, world scene, attitude or assignment copy. */
public record MovementValidationBody(EntityDimensions dimensions, Pose pose, float eyeHeight, Vec3d up) {
    public static MovementValidationBody capture(ServerPlayer player) {
        var access = GravityEntityAccess.cast(player);
        return new MovementValidationBody(access.gravityengine$installedDimensions(),
                access.gravityengine$installedPose(), player.getEyeHeight(),
                access.gravityengine$gravityComponent().operationState().installedCollisionUp());
    }

    public boolean exact() { return BodyRepresentation.ofAxis(up).isExact(); }

    public boolean sameGeometry(MovementValidationBody other) {
        return dimensions.width() == other.dimensions.width() && dimensions.height() == other.dimensions.height()
                && pose == other.pose && eyeHeight == other.eyeHeight && java.util.Objects.equals(up, other.up);
    }

    private CollisionBody at(ServerPlayer player) {
        return exact() ? GravityEntityGeometry.candidateBody(dimensions, player.position(), up)
                : OrientedBox.axisAligned(MinecraftCollisionGeometryAdapter.toAabb3d(
                        dimensions.makeBoundingBox(player.position())));
    }

    /** A separate, read-only geometry handoff at the native anchor. Never a recovery move. */
    public boolean fits(ServerPlayer player) {
        if (player.noPhysics) return true;
        try {
            var body = at(player);
            var context = new ObbQueryContext();
            long tick = player.level().getGameTime();
            var scene = MinecraftCollisionSceneCapture.capture(player,
                    CollisionCaptureDomain.forTransition(body, player.maxUpStep()), tick, 0,
                    KinematicStepContext.fullTick(tick, 0), context.workTracker());
            return GravityGeometryTransitionPlanner.evaluatePoseFit(scene, body, context).legal();
        } catch (CollisionComplexityLimitException | CollisionSceneCoverageException unavailable) {
            return false;
        }
    }

    public void install(ServerPlayer player) {
        var before = capture(player);
        var box = player.getBoundingBox();
        var anchor = player.position();
        try (var ignored = BodyCommitTransaction.begin(player)) {
            try {
                var access = GravityEntityAccess.cast(player);
                access.gravityengine$installMovementDimensions(dimensions, pose, eyeHeight);
                var body = at(player);
                GravityEntityGeometry.restoreExactGeometry(player, anchor,
                        MinecraftCollisionGeometryAdapter.toMinecraft(body.enclosingAabb()), up);
                access.gravityengine$gravityComponent().operationState().invalidateMovementContinuity();
            } catch (RuntimeException | Error failure) {
                try {
                    GravityEntityAccess.cast(player).gravityengine$installMovementDimensions(
                            before.dimensions, before.pose, before.eyeHeight);
                    GravityEntityGeometry.restoreExactGeometry(player, anchor, box, before.up);
                } catch (RuntimeException | Error rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }
}
