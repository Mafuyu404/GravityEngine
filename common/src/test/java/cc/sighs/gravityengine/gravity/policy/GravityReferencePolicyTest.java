package cc.sighs.gravityengine.gravity.policy;


import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.model.GravityAuthorityMode;
import cc.sighs.gravityengine.gravity.model.GravitySuppressionReason;
import cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GravityReferencePolicyTest {
    private static final GravityFrame TILTED =
            GravityFrame.fromDown(new Vec3d(1.0, 0.0, 0.0), 0.08);
    private static final GravityFrame WORLD_DOWN_ROTATED_TANGENT =
            new GravityFrame(
                    Vec3d.ZERO,
                    new OrthonormalFrame3d(
                            new Vec3d(0.0, 0.0, -1.0),
                            new Vec3d(0.0, 1.0, 0.0),
                            new Vec3d(1.0, 0.0, 0.0)),
                    GravityState.VANILLA_STRENGTH);

    @Test
    void activeFieldKeepsReferenceIndependentFromApplicationKind() {
        assertTrue(GravityReferencePolicy.hasGravityReference(
                GravityAuthorityMode.FIELD,
                true,
                GravitySuppressionReason.NONE,
                true,
                TILTED));
    }

    @Test
    void directAssignmentKeepsReference() {
        assertTrue(GravityReferencePolicy.hasGravityReference(
                GravityAuthorityMode.DIRECT,
                false,
                GravitySuppressionReason.NONE,
                true,
                TILTED));
    }

    @Test
    void missingFieldContributionHasNoGravityReference() {
        assertFalse(GravityReferencePolicy.hasGravityReference(
                GravityAuthorityMode.FIELD,
                false,
                GravitySuppressionReason.NONE,
                true,
                TILTED));
    }

    @Test
    void authoritativeSuppressionDisablesPresentationReference() {
        assertFalse(GravityReferencePolicy.hasGravityReference(
                GravityAuthorityMode.FIELD,
                true,
                GravitySuppressionReason.CREATIVE_FLIGHT,
                true,
                TILTED));
    }

    @Test
    void worldDownReferenceWithRotatedTangentRemainsActive() {
        assertTrue(GravityReferencePolicy.hasGravityReference(
                GravityAuthorityMode.FIELD,
                true,
                GravitySuppressionReason.NONE,
                true,
                WORLD_DOWN_ROTATED_TANGENT));
    }

    @Test
    void frameWithoutActiveAuthorityHasNoReference() {
        assertFalse(GravityReferencePolicy.hasGravityReference(
                GravityAuthorityMode.FIELD,
                false,
                GravitySuppressionReason.NONE,
                true,
                WORLD_DOWN_ROTATED_TANGENT));
    }

    @Test
    void worldVerticalGravityCanStillHaveReference() {
        assertTrue(GravityReferencePolicy.hasGravityReference(
                GravityAuthorityMode.FIELD,
                true,
                GravitySuppressionReason.NONE,
                true,
                GravityFrame.DEFAULT));
        assertTrue(GravityReferencePolicy.hasGravityReference(
                GravityAuthorityMode.DIRECT,
                false,
                GravitySuppressionReason.NONE,
                true,
                GravityFrame.DEFAULT));
    }

    @Test
    void missingFieldHasNoReference() {
        assertFalse(GravityReferencePolicy.hasGravityReference(
                GravityAuthorityMode.FIELD,
                false,
                GravitySuppressionReason.NONE,
                true,
                GravityFrame.DEFAULT));
        assertFalse(GravityReferencePolicy.hasGravityReference(
                GravityAuthorityMode.FIELD,
                false,
                GravitySuppressionReason.NONE,
                true,
                null));
    }
}
