package cc.sighs.gravityengine.gravity.presentation;

import cc.sighs.gravityengine.gravity.presentation.ClientGravityPresentationState;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState;
import cc.sighs.gravityengine.math.Quatd;
import cc.sighs.gravityengine.math.geometry.BodyOrientation3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CorrectionFrameContinuityTest {
    private static final GravityFrame CORRECTION = new GravityFrame(Vec3d.ZERO,
            BodyOrientation3d.frame(Quatd.rotationZ(.7).multiply(Quatd.rotationY(2.2))), .08);

    @Test
    void correctionCannotReplaceAnActiveOperationFrame() {
        var runtime = new GravityOperationState();
        try (var ignored = runtime.openMove(GravityFrame.DEFAULT, 1)) {
            assertThrows(IllegalStateException.class, () -> runtime.resetFrameContinuityTo(CORRECTION, 1));
            assertSame(GravityFrame.DEFAULT, runtime.activeFrame());
        }
        assertThrows(IllegalArgumentException.class, () -> runtime.resetFrameContinuityTo(CORRECTION, -1));
    }

    /**
     * An ordinary authoritative application/reference republication accepts the
     * new endpoint into the published history. It never declares a
     * discontinuity, so the render path interpolates from the old basis onto the
     * authoritative one instead of snapping to it.
     */
    @Test
    void acceptedReferenceEndpointInterpolatesOntoTheAuthoritativeBasis() {
        var runtime = new GravityOperationState();
        var presentation = new ClientGravityPresentationState();
        try (var ignored = runtime.openMove(GravityFrame.DEFAULT, 40)) {}
        tick(presentation, runtime, 40);

        long discontinuityBefore = runtime.lastPresentationDiscontinuityRevision();
        runtime.acceptFrameEndpoint(CORRECTION, 41);

        assertEquals(discontinuityBefore, runtime.lastPresentationDiscontinuityRevision(),
                "an accepted endpoint declares no presentation discontinuity");
        assertSame(CORRECTION, runtime.lastCompletedFrame());
        assertEquals(runtime.presentationRevision(),
                runtime.completedPresentationFrame().revision());

        tick(presentation, runtime, 41);
        assertSame(GravityFrame.DEFAULT, presentation.previous());
        assertSame(CORRECTION, presentation.current());

        var atOldBasis = presentation.sampleAt(41);
        assertTrue(atOldBasis.forward().distance(GravityFrame.DEFAULT.forward()) < 1.0E-9,
                "the published history still starts at the previous basis");
        var atEndpoint = presentation.sampleAt(42);
        assertTrue(atEndpoint.up().distance(CORRECTION.up()) < 1.0E-9,
                "the interpolation target is the authoritative endpoint");
        var midway = presentation.sampleAt(41.5);
        assertTrue(midway.up().distance(GravityFrame.DEFAULT.up()) > .05
                        && midway.up().distance(CORRECTION.up()) > .05,
                "the render path blends onto the authoritative basis instead of snapping");
    }

    private static void tick(ClientGravityPresentationState presentation, GravityOperationState runtime, long tick) {
        presentation.retarget(runtime.completedPresentationFrame().frame(), tick, 1);
    }
}
