package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.client.ClientGravityFrameSampler;
import cc.sighs.gravityengine.client.ClientPlayerBodyCommitHandler;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.integration.geometry.InstalledBodySnapshot;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.math.Quatd;
import cc.sighs.gravityengine.math.geometry.BodyOrientation3d;
import cc.sighs.gravityengine.network.ClientboundPlayerBodyCommitPayload;
import cc.sighs.gravityengine.network.SyncGravityStatePayload;
import net.minecraft.client.Camera;
import net.minecraft.client.player.LocalPlayer;

/** Real body-packet admission and Camera.setup on the isolated client actor. */
public final class ClientPresentationTimingChecks {
    private ClientPresentationTimingChecks() {}

    public static void run(LocalPlayer actor, SyncGravityStatePayload assignment) {
        var component = GravityEntityAccess.cast(actor).gravityengine$gravityComponent();
        var runtime = component.operationState();
        var original = runtime.lastCompletedFrame();
        int originalTick = actor.tickCount;
        var body = InstalledBodySnapshot.capture(actor);
        var anchor = actor.position();
        var camera = new Camera();
        try {
            invokeSampler("remove", actor);
            actor.tickCount = 153;
            invokeSampler("tick", actor);
            var target = twist(original, .2);
            runtime.acceptFrameEndpoint(target, actor.level().getGameTime());
            actor.tickCount = 154;
            invokeSampler("tick", actor);
            var displayed = ClientGravityFrameSampler.sample(actor, .9f).frame();
            camera.setup(actor.level(), actor, false, false, .9f);
            var displayedCamera = new org.joml.Quaternionf(camera.rotation());

            var latest = twist(original, .20000000000001);
            long epoch = component.state().applicationEpoch() + 1;
            ClientPlayerBodyCommitHandler.handle(new ClientboundPlayerBodyCommitPayload(
                    assignment, epoch, component.state().committedApplication(), body.pose(),
                    body.width(), body.height(), body.installedUp(), latest, body.representation(),
                    ClientboundPlayerBodyCommitPayload.CommitMode.TRANSACTION, null));
            check(component.state().applicationEpoch() == epoch
                            && runtime.lastCompletedFrame().equals(latest),
                    "physical reference installs immediately before the entity tick");
            check(actor.position().equals(anchor), "presentation scheduling cannot move the actor");
            check(ClientGravityFrameSampler.sample(actor, .9f).frame().equals(displayed),
                    "network admission cannot rewrite the already displayed trajectory");
            camera.setup(actor.level(), actor, false, false, .9f);
            check(camera.rotation().equals(displayedCamera), "camera remains on the same visual trajectory");

            actor.tickCount = 155;
            invokeSampler("tick", actor);
            var next = ClientGravityFrameSampler.sample(actor, .06f).frame();
            check(next.forward().distance(latest.forward()) <= displayed.forward().distance(latest.forward()),
                    "camera convergence cannot backtrack across the packet/tick boundary");
            actor.tickCount = 158;
            check(ClientGravityFrameSampler.sample(actor, 0).frame().equals(latest),
                    "packet visual target converges in three ticks");
            System.out.println("CLIENT_PRESENTATION_TIMING_PASSED immediate physical install / deferred visual retarget / no camera rewind");
        } finally {
            actor.tickCount = originalTick;
            runtime.acceptFrameEndpoint(original, actor.level().getGameTime());
            invokeSampler("remove", actor);
        }
    }

    private static GravityFrame twist(GravityFrame base, double angle) {
        return new GravityFrame(base.samplePoint(), BodyOrientation3d.frame(
                BodyOrientation3d.quaternion(base.orientation()).multiply(Quatd.rotationY(angle))), base.strength());
    }

    private static void invokeSampler(String method, LocalPlayer actor) {
        try {
            var callback = ClientGravityFrameSampler.class.getDeclaredMethod(method, net.minecraft.world.entity.Entity.class);
            callback.setAccessible(true);
            callback.invoke(null, actor);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("presentation lifecycle callback " + method, failure);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
