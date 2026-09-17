package cc.sighs.gravityengine.gravity.presentation;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClientGravityPresentationStateTest {
    private static GravityFrame tilted(double x) {
        return GravityFrame.fromDown(new Vec3d(x, -1, 0), .08);
    }

    @Test
    void packetBeforeEntityTickCannotRewindAnAlreadyRenderedTransition() {
        var state = new ClientGravityPresentationState();
        var target = tilted(.1);
        state.tick(GravityFrame.DEFAULT, 153);
        state.tick(target, 154);
        double displayedTilt = -state.sampleAt(154.90).up().x();

        // Timer has wrapped to .06 but the entity is still on tick 154.
        // Packet admission must not sample or retarget at the mixed time 154.06.
        state.requestConvergence();
        assertEquals(displayedTilt, -state.sampleAt(154.90).up().x());
        state.tick(tilted(.10000000000001), 155);
        assertTrue(-state.sampleAt(155.06).up().x() >= displayedTilt,
                "a near-identical publication must not pull the camera backward");
    }

    @Test
    void packetsBetweenTicksCoalesceWithoutInterruptingTheDisplayedTrajectory() {
        var state = new ClientGravityPresentationState();
        var first = tilted(.1);
        var latest = tilted(.4);
        state.tick(GravityFrame.DEFAULT, 10);
        state.tick(first, 11);
        var displayed = state.sampleAt(11.5);

        state.requestConvergence();
        state.requestConvergence();
        assertEquals(displayed, state.sampleAt(11.5));
        assertSame(first, state.current());
        state.tick(latest, 12);
        assertSame(first, state.sampleAt(12));
        assertSame(latest, state.sampleAt(15));
        assertTrue(state.transitioning(14.99), "physical commits retain three-tick convergence");

        state.tick(tilted(.5), 16);
        assertFalse(state.transitioning(17), "the convergence request is consumed once");
    }

    @Test
    void repeatedIdenticalCommitsDoNotRestartConvergence() {
        var state = new ClientGravityPresentationState();
        var target = tilted(.4);
        state.tick(GravityFrame.DEFAULT, 20);
        state.requestConvergence();
        state.tick(target, 21);
        for (long tick = 22; tick <= 24; tick++) {
            state.requestConvergence();
            state.tick(target, tick);
        }
        assertSame(target, state.sampleAt(24));
        assertFalse(state.transitioning(24));
    }

    @Test
    void disablingGravityKeepsTheOldDisplayUntilTheTickStartsConvergence() {
        var state = new ClientGravityPresentationState();
        var old = tilted(.4);
        state.tick(old, 30);
        assertFalse(state.transitioning(30.9));
        state.requestConvergence();
        assertTrue(state.transitioning(30.06), "disable must not expose the vanilla camera between packet and tick");
        assertSame(old, state.sampleAt(30.95));
        state.tick(GravityFrame.DEFAULT, 31);
        assertSame(old, state.sampleAt(31));
        assertTrue(state.transitioning(32));
        assertSame(GravityFrame.DEFAULT, state.sampleAt(34));
        assertFalse(state.transitioning(34));
    }
}
