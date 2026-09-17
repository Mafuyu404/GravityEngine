package cc.sighs.gravityengine.gravity.movement;

import cc.sighs.gravityengine.attitude.*;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.look.GravityLocalLook;
import cc.sighs.gravityengine.math.Quatd;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class CorrectionControlOwnershipTest {
    private static final BodyAttitudePlayerState AIR = new BodyAttitudePlayerState(
            false, false, false, false, false, false, false, false,
            false, false, false, false, false, false, true);
    private static final GravityFrame WEAK = new GravityFrame(GravityFrame.DEFAULT.samplePoint(),
            GravityFrame.DEFAULT.orientation(), .001);
    private static final BodyAttitudeConfigSnapshot CONFIG = BodyAttitudeConfigSnapshot.DEFAULT;

    private CharacterControlPlan step(CharacterControlMode mode, Optional<Boolean> support, GravityFrame frame) {
        var facts = new CharacterGroundFacts(false, support);
        mode.retainIfEligible(CharacterLocomotionControlPolicyResolver.lowGravitySwimEligible(
                        AIR, true, facts, frame, CONFIG),
                CharacterLocomotionControlPolicyResolver.lowGravitySwimMayContinue(AIR, true, facts, frame, CONFIG));
        return CharacterLocomotionControlPolicyResolver.resolve(AIR, true, mode.swimActive(), facts, frame, CONFIG);
    }

    @Test void unknownRetainsActiveUntilRealSupportRevokesIt() {
        var mode = new CharacterControlMode();
        mode.installReplicated(true);
        for (int i = 0; i < 20; i++) {
            assertEquals(CharacterAttitudeContract.FREE_ATTITUDE, step(mode, Optional.empty(), WEAK).attitude());
        }
        assertEquals(CharacterAttitudeContract.FREE_ATTITUDE, step(mode, Optional.of(false), WEAK).attitude());
        assertEquals(CharacterAttitudeContract.REFERENCE_ALIGNED, step(mode, Optional.of(true), WEAK).attitude());
        assertFalse(mode.swimActive());
        assertEquals(CharacterAttitudeContract.REFERENCE_ALIGNED, step(mode, Optional.empty(), WEAK).attitude());
    }

    @Test void unknownCannotActivateOrHideIndependentIneligibility() {
        var mode = new CharacterControlMode();
        step(mode, Optional.of(false), WEAK);
        mode.pressSprintIntent(true);
        step(mode, Optional.empty(), WEAK);
        step(mode, Optional.of(false), WEAK);
        assertFalse(mode.swimActive(), "unknown interrupted the pending activation proof");
        mode.installReplicated(true);
        assertEquals(CharacterAttitudeContract.REFERENCE_ALIGNED,
                step(mode, Optional.empty(), GravityFrame.DEFAULT).attitude());
        assertFalse(mode.swimActive(), "known gravity exclusion still revokes");
    }

    @Test void releaseProjectsSemanticLookDespiteStaleScalarCarrier() {
        for (var frame : new GravityFrame[]{GravityFrame.DEFAULT,
                GravityFrame.fromDown(new cc.sighs.gravityengine.api.math.Vec3d(1, 0, 0), .001)}) {
            var semantic = new SemanticView(GravityLocalLook.lookQuaternion(frame, 120, 23, 0));
            var view = BodyRelativeViewState.fromSemantic(semantic, Quatd.IDENTITY,
                    new AttitudeSpaceTransform.LocalLookAngles(0, 0));
            var release = AttitudeSpaceTransform.releaseLookRebase(frame, Quatd.IDENTITY, view, 35, -17);
            var projected = new SemanticView(GravityLocalLook.lookQuaternion(
                    frame, release.sourceYaw(), release.sourcePitch(), 0));
            assertTrue(semantic.forward().distance(projected.forward()) < 1e-6);
            assertEquals(120, release.sourceYaw(), 1e-4);
            assertEquals(23, release.sourcePitch(), 1e-4);
        }
    }

    @Test void poleReleaseUsesSemanticTangentInsteadOfStaleScalarYaw() {
        for (float pitch : new float[]{90, -90}) {
            var semantic = new SemanticView(GravityLocalLook.lookQuaternion(GravityFrame.DEFAULT, 120, pitch, 0));
            var view = BodyRelativeViewState.fromSemantic(semantic, Quatd.IDENTITY,
                    new AttitudeSpaceTransform.LocalLookAngles(0, 0));
            var release = AttitudeSpaceTransform.releaseLookRebase(GravityFrame.DEFAULT, Quatd.IDENTITY, view, 35, 0);
            assertEquals(120, release.sourceYaw(), 1e-4);
            assertEquals(pitch, release.sourcePitch(), 1e-4);
        }
    }
}
