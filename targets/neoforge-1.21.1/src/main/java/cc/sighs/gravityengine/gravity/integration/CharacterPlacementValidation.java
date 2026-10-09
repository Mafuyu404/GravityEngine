package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.integration.collision.MinecraftCollisionSceneCapture;
import cc.sighs.gravityengine.gravity.integration.compat.sable.SableMovementCompatibility;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Invocation-local dismount clearance. Vehicle transforms and native candidate
 * selection retain ownership; only the installed physical collider is tested. */
public final class CharacterPlacementValidation {
    private CharacterPlacementValidation() {}
    public static boolean owns(LivingEntity actor) {
        return !actor.noPhysics && (GravityInfluencePolicy.usesExactBodyCollision(actor)
                || GravityInfluencePolicy.hasExternalCollisionProviders(actor));
    }
    public static boolean clearAt(LivingEntity actor,Vec3 nativeAnchor) {
        var anchor=SableMovementCompatibility.projectOut(actor,nativeAnchor);
        var body=GravityEntityGeometry.body(actor).move(MinecraftMathAdapter.toVec3d(anchor.subtract(actor.position())));
        var bounds=body.enclosingAabb().inflate(CollisionTolerances.CONTACT_SKIN*2);
        var tracker=new CollisionWorkTracker(CollisionWorkBudget.defaults());
        var time=KinematicStepContext.fullTick(actor.level().getGameTime(),0);
        try {
            var scene=MinecraftCollisionSceneCapture.capture(actor,new CollisionCaptureDomain(bounds,bounds),
                    time.gameTick(),0,time,tracker);
            var context=new ObbQueryContext();context.setWorkTracker(tracker);
            var contacts=CurrentContactQuery.contacts(body,scene,1,context);
            return !tracker.limitExceeded() && !scene.diagnostics().limitExceeded()
                    && !contacts.indeterminate() && !CurrentContactQuery.hasMeaningfulPenetration(contacts);
        } catch(CollisionSceneCoverageException | CollisionComplexityLimitException unavailable) {
            cc.sighs.gravityengine.gravity.debug.CollisionCoverageDiagnostics.report(actor,unavailable);
            return false;
        }
    }
}
