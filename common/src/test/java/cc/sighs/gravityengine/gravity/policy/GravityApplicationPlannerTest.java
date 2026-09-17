package cc.sighs.gravityengine.gravity.policy;

import cc.sighs.gravityengine.gravity.model.GravityAccelerationMode;
import cc.sighs.gravityengine.gravity.model.GravityApplicationPlan;
import cc.sighs.gravityengine.gravity.model.GravityAuthorityMode;
import cc.sighs.gravityengine.gravity.model.GravityEntityCapabilities;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GravityApplicationPlannerTest {
    @Test
    void activeCharacterFallsBackToVanillaForActualFluidLocomotion() {
        GravityApplicationPlanner.EntityState state =
                new GravityApplicationPlanner.EntityState(
                        false, false, false, false, false,
                        true, false, false, false, false,
                        false, false, false);

        GravityApplicationPlan plan = GravityApplicationPlanner.plan(
                GravityEntityCapabilities.CHARACTER,
                GravityAuthorityMode.FIELD,
                false,
                true,
                false,
                state);

        assertEquals(GravityApplicationPlan.Kind.VANILLA, plan.kind());
        assertEquals(GravityAccelerationMode.NONE, plan.accelerationMode());
    }

    @Test
    void activeCharacterFallsBackToVanillaForSwimmingPresentation() {
        GravityApplicationPlanner.EntityState state =
                new GravityApplicationPlanner.EntityState(
                        false, false, false, false, false,
                        false, true, false, false, false,
                        false, false, false);

        GravityApplicationPlan plan = GravityApplicationPlanner.plan(
                GravityEntityCapabilities.CHARACTER,
                GravityAuthorityMode.FIELD,
                false,
                true,
                false,
                state);

        assertEquals(GravityApplicationPlan.Kind.VANILLA, plan.kind());
    }

    @Test
    void activeCharacterStillUsesCharacterApplicationWhenPoseIsSupported() {
        GravityApplicationPlan plan = GravityApplicationPlanner.plan(
                GravityEntityCapabilities.CHARACTER,
                GravityAuthorityMode.FIELD,
                false,
                true,
                false,
                GravityApplicationPlanner.EntityState.ordinary());

        assertEquals(GravityApplicationPlan.Kind.CHARACTER, plan.kind());
        assertEquals(GravityAccelerationMode.FIELD, plan.accelerationMode());
    }
}
