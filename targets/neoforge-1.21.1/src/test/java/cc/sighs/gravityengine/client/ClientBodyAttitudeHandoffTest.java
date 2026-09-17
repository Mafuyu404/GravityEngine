package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.math.Quatd;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientBodyAttitudeHandoffTest {
    @Test
    void handoffPreservesFullLiveInputAtEveryProgress() {
        var source = Quatd.rotationZYX(.7, -.4, .2);
        var target = Quatd.rotationZYX(-.3, .5, -.1);
        var offset = target.conjugate().multiply(source).normalized();
        var mouseMotion = Quatd.rotationY(.35).multiply(Quatd.rotationX(-.2));
        for (double progress : new double[]{0, .01, .25, .5, .99, 1}) {
            var displayed = ClientBodyAttitudeHandoff.controllerAt(offset, target, progress);
            var moved = ClientBodyAttitudeHandoff.controllerAt(offset, mouseMotion.multiply(target), progress);
            same(mouseMotion.multiply(displayed), moved);
        }
        same(source, ClientBodyAttitudeHandoff.controllerAt(offset, target, 0));
        same(target, ClientBodyAttitudeHandoff.controllerAt(offset, target, 1));
    }

    @Test
    void interruptedTransitionStartsFromDisplayedPoseAndStillAcceptsInput() {
        var target = Quatd.rotationY(.5);
        var visible = ClientBodyAttitudeHandoff.controllerAt(Quatd.rotationZ(1), target, .3);
        var nextTarget = Quatd.rotationX(-.6);
        var offset = nextTarget.conjugate().multiply(visible).normalized();
        same(visible, ClientBodyAttitudeHandoff.controllerAt(offset, nextTarget, 0));
        var input = Quatd.rotationY(.2);
        same(input.multiply(visible), ClientBodyAttitudeHandoff.controllerAt(offset, input.multiply(nextTarget), 0));
    }

    private static void same(Quatd expected, Quatd actual) {
        assertTrue(Math.abs(expected.normalized().dot(actual.normalized())) > 1 - 1e-12);
    }
}
