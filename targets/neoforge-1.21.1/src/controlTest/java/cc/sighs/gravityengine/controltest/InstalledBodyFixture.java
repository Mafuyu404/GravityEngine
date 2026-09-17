package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.gravity.integration.geometry.*;

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

/** Test-only installed-body snapshot and axis fixture. Never used by movement acceptance. */
public record InstalledBodyFixture(EntityDimensions dimensions, Pose pose, float eyeHeight, Vec3d up) {
    public static InstalledBodyFixture capture(ServerPlayer player) {
        var access = GravityEntityAccess.cast(player);
        return new InstalledBodyFixture(access.gravityengine$installedDimensions(),
                access.gravityengine$installedPose(), player.getEyeHeight(),
                access.gravityengine$gravityComponent().operationState().installedCollisionUp());
    }

    public boolean exact() { return BodyRepresentation.ofAxis(up).isExact(); }

    public boolean sameGeometry(InstalledBodyFixture other) {
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

    /** Fixture axis change only, without a production historical-dimensions seam. */
    public void install(ServerPlayer player) {
        var before = capture(player);
        if (!dimensions.equals(before.dimensions) || pose != before.pose || eyeHeight != before.eyeHeight) {
            throw new IllegalArgumentException("fixture may change only the installed axis");
        }
        var body = at(player);
        try (var ignored = BodyCommitTransaction.begin(player)) {
            GravityEntityGeometry.restoreExactGeometry(player, player.position(),
                    MinecraftCollisionGeometryAdapter.toMinecraft(body.enclosingAabb()), up);
            GravityEntityAccess.cast(player).gravityengine$gravityComponent().operationState().invalidateMovementContinuity();
        }
    }
}
