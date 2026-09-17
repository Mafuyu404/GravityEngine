package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.attitude.*;
import cc.sighs.gravityengine.attitude.runtime.*;
import cc.sighs.gravityengine.client.*;
import cc.sighs.gravityengine.gravity.*;
import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.integration.geometry.InstalledBodySnapshot;
import cc.sighs.gravityengine.gravity.minecraft.access.*;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.model.*;
import cc.sighs.gravityengine.gravity.movement.*;
import cc.sighs.gravityengine.gravity.runtime.RestingContactSnapshot;
import cc.sighs.gravityengine.math.Quatd;
import cc.sighs.gravityengine.network.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.*;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.*;
import net.minecraft.network.protocol.*;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Real transformed native client handler, body envelope, position classifier,
 * controller owner and Camera.setup; isolated connection records native ACKs. */
final class ClientCorrectionAuthorityChecks {
    private static final GravityFrame FRAME = GravityFrame.fromDown(new Vec3d(0, -.98, -.2), .001);
    static void run(Minecraft mc) {
        var real = mc.player;
        var wire = new ArrayList<Packet<?>>();
        var cookie = new CommonListenerCookie(real.connection.getLocalGameProfile(), null,
                mc.level.registryAccess().freeze(), mc.level.enabledFeatures(), null, null, null, Map.of(), null,
                false, Map.of(), net.minecraft.server.ServerLinks.EMPTY,
                net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE);
        var connection = new Connection(PacketFlow.CLIENTBOUND) {
            @Override public void send(Packet<?> packet) { wire.add(packet); }
            @Override public void send(Packet<?> packet, PacketSendListener listener) { wire.add(packet); }
        };
        var listener = new ClientPacketListener(mc, connection, cookie);
        var actor = new LocalPlayer(mc, mc.level, listener, new net.minecraft.stats.StatsCounter(),
                new net.minecraft.client.ClientRecipeBook(), false, false);
        actor.setId(1234567);
        actor.setUUID(UUID.randomUUID());
        var floor = real.blockPosition().above(10);
        var originalFloor = mc.level.getBlockState(floor);
        try {
            mc.player = actor;
            mc.level.addEntity(actor);
            check(mc.level.getEntity(actor.getId()) == actor, "isolated client player admitted");
            actor.setPos(real.position().add(0, 10, 0));
            var gravity = GravityEntityAccess.cast(actor).gravityengine$gravityComponent();
            var frame = FRAME;
            var assignment = new GravityState(frame.down(), frame.strength());
            var application = new CommittedGravityApplication(assignment, GravitySuppressionReason.NONE,
                    GravityApplicationPlan.character(GravityAccelerationMode.DIRECT));
            var snapshot = new SyncGravityStatePayload(actor.getId(), actor.getUUID(), mc.level.dimension().location(),
                    1, assignment.down(), assignment.strength(), 0, GravitySuppressionReason.NONE,
                    GravityAuthorityMode.DIRECT, cc.sighs.gravityengine.api.FieldPresence.ABSENT, 1, application);
            ClientGravitySyncService.applySnapshot(actor, snapshot);
            GravityEntityGeometry.installFromPositionAnchor(actor, frame.up(), actor.position());
            gravity.state().acceptRemoteApplication(application, 1);
            gravity.operationState().acceptFrameEndpoint(frame, mc.level.getGameTime());
            gravity.state().takePending();

            var installedDimensions = GravityEntityGeometry.dimensions(actor);
            var installedPose = actor.getPose();
            var installedBox = actor.getBoundingBox();
            actor.setPose(net.minecraft.world.entity.Pose.CROUCHING);
            var nativeCrouch = actor.getEntityData().getNonDefaultValues().stream()
                    .filter(value -> value.serializer() == net.minecraft.network.syncher.EntityDataSerializers.POSE)
                    .toList();
            actor.setPose(installedPose);
            // Native metadata assignment bypasses Entity.setPose entirely.
            real.connection.handleSetEntityData(new ClientboundSetEntityDataPacket(actor.getId(), nativeCrouch));
            check(GravityEntityGeometry.dimensions(actor).equals(installedDimensions)
                            && actor.getBoundingBox().equals(installedBox)
                            && InstalledBodySnapshot.capture(actor).pose() == installedPose,
                    "client native pose notification cannot replace server-installed dimensions");
            long shapeRevision = gravity.operationState().bodyShapeRevision();
            ClientPlayerBodyCommitHandler.handle(new ClientboundPlayerBodyCommitPayload(snapshot, 2, application,
                    installedPose, installedDimensions.width(), installedDimensions.height(), frame.up(), frame,
                    cc.sighs.gravityengine.gravity.geometry.BodyRepresentation.EXACT_BODY,
                    ClientboundPlayerBodyCommitPayload.CommitMode.TRANSACTION, null));
            check(actor.getPose() == installedPose && actor.getBoundingBox().equals(installedBox)
                            && gravity.operationState().bodyShapeRevision() == shapeRevision,
                    "same-body confirmation discards pose proposal without reinstalling geometry");
            real.connection.handleSetEntityData(new ClientboundSetEntityDataPacket(actor.getId(), nativeCrouch));
            var crouch = actor.getDimensions(net.minecraft.world.entity.Pose.CROUCHING);
            ClientPlayerBodyCommitHandler.handle(new ClientboundPlayerBodyCommitPayload(snapshot, 3, application,
                    net.minecraft.world.entity.Pose.CROUCHING, crouch.width(), crouch.height(), frame.up(), frame,
                    cc.sighs.gravityengine.gravity.geometry.BodyRepresentation.EXACT_BODY,
                    ClientboundPlayerBodyCommitPayload.CommitMode.TRANSACTION, null));
            check(GravityEntityGeometry.dimensions(actor).equals(crouch),
                    "complete server transaction installs dimensions even when native pose already matches");
            ClientPlayerBodyCommitHandler.handle(new ClientboundPlayerBodyCommitPayload(snapshot, 4, application,
                    installedPose, installedDimensions.width(), installedDimensions.height(), frame.up(), frame,
                    cc.sighs.gravityengine.gravity.geometry.BodyRepresentation.EXACT_BODY,
                    ClientboundPlayerBodyCommitPayload.CommitMode.TRANSACTION, null));

            try (var prediction = ClientMovementBodyVersion.begin(actor)) {
                var predictedEpoch = gravity.state().applicationEpoch();
                gravity.state().advanceBodyPublicationEpoch();
                var move = new ServerboundMovePlayerPacket.StatusOnly(true);
                var wrapped = (net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket)
                        ClientMovementBodyVersion.envelope(actor, move, false);
                check(((ServerboundPlayerMovePayload) wrapped.payload()).bodyEpoch() == predictedEpoch,
                        "send uses epoch captured before prediction, not later publication");
                var reply = (net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket)
                        ClientMovementBodyVersion.envelope(actor, move, true);
                check(((ServerboundPlayerMovePayload) reply.payload()).bodyEpoch() == gravity.state().applicationEpoch(),
                        "correction reply uses installed epoch even inside retiring prediction scope");
            }
            check(!ClientMovementBodyVersion.predicting(actor), "prediction scope closes");

            for (boolean active : new boolean[]{false, true}) {
                check(cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy.usesCustomBody(actor),
                        "fixture requires a non-default installed collision axis");
                installLook(actor, active, 120, 23);
                var runtime = gravity.operationState();
                mc.level.setBlock(floor, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 18);
                actor.setPos(floor.getX() + .5, floor.getY() + 1, floor.getZ() + .5);
                actor.setPos(actor.position().add(0, floor.getY() + 1 - actor.getBoundingBox().minY, 0));
                actor.setDeltaMovement(Vec3.ZERO);
                actor.setOnGround(false); // gameplay carrier does not certify terminal evidence
                var support = cc.sighs.gravityengine.gravity.integration.collision.GravityCurrentSupportQuery
                        .stableSupport(actor).orElseThrow(() -> new AssertionError("fixture must have real finite resting support"));
                runtime.setRestingContactSnapshot(support);
                var before = semantic(actor);
                var camera = camera(mc, actor, false);
                var third = camera(mc, actor, true);
                var origin = actor.position();
                long continuity = runtime.movementContinuity();
                apply(actor, new ClientboundPlayerPositionPacket(origin.x, origin.y, origin.z,
                        0, 0, RelativeMovement.ROTATION, 400));
                check(runtime.restingContactSnapshot() == support && runtime.movementContinuity() == continuity,
                        "same-position correction does not invalidate physical continuity");
                // Wire output of server stale 35/-17 with relative zero deltas.
                var correction = new ClientboundPlayerPositionPacket(origin.x + 5e-5, origin.y, origin.z,
                        0, 0, RelativeMovement.ROTATION, 401);
                apply(actor, correction);
                check(actor.getX() == correction.getX(), "corrected position applied actual=" + actor.position()
                        + " target=" + correction.getX());
                check(actor.getYRot() == 120 && actor.getXRot() == 23
                        && actor.yRotO == 120 && actor.xRotO == 23, "new look and history survive stale correction");
                check(semantic(actor).equals(before), "controller unchanged");
                check(camera(mc, actor, false).equals(camera), "camera unchanged");
                check(camera(mc, actor, true).equals(third), "third-person obstruction cannot change look");
                check(runtime.restingContactSnapshot() == null, "stale resting evidence invalidated");
                var hint = runtime.consumeSoftPositionSupportRevalidation().orElseThrow();
                check(hint.equals(support),
                        "envelope leaves prior support for one-shot classifier revalidation");
                check(runtime.consumeSoftPositionSupportRevalidation().isEmpty(), "candidate is one-shot");
                check(runtime.completedEndpointGround().isEmpty(), "UNKNOWN, never fabricated unsupported");
                var captured = cc.sighs.gravityengine.gravity.integration.collision.GravityCurrentSupportQuery.capture(actor);
                var verified = StepStartSupportQuery.query(captured.body(), captured.frame(), captured.scene(),
                        captured.context(), Vec3d.ZERO, mc.level.getGameTime(), hint);
                check(!verified.indeterminate() && verified.support().isPresent(), "finite support revalidates at corrected position");
                if (active) {
                    var control = (CharacterControlAccess) actor;
                    control.gravityengine$characterMode().installReplicated(true);
                    control.gravityengine$characterControl().clear();
                    var plan = cc.sighs.gravityengine.player.CharacterControlRuntime.resolve(actor, frame,
                            BodyAttitudeConfigSnapshot.DEFAULT, actor.tickCount);
                    check(plan.attitude() == CharacterAttitudeContract.FREE_ATTITUDE,
                            "correction UNKNOWN retains free attitude");
                    check(BodyAttitudeRuntime.Access.component(actor).ownership() == BodyAttitudeOwnership.ACTIVE,
                            "no ownership release");
                }
                check(wire.stream().anyMatch(p -> p instanceof ServerboundAcceptTeleportationPacket ack
                        && ack.getId() == 401), "original native teleport ACK emitted");
                check(wire.stream().anyMatch(p -> p instanceof net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket c
                        && c.payload() instanceof ServerboundPlayerMovePayload move && move.movement().hasPosition()),
                        "native correction movement reply carries body version atomically");

                // t0 packet generated, t1 local look changes, t2 packet arrives.
                var delayed = new ClientboundPlayerPositionPacket(actor.getX(), actor.getY(), actor.getZ(),
                        0, 0, RelativeMovement.ROTATION, 402);
                installLook(actor, active, 155, -31);
                var newer = semantic(actor);
                apply(actor, delayed);
                check(actor.getYRot() == 155 && semantic(actor).equals(newer), "newer local input survives delayed packet");
                if (active) {
                    ClientBodyAttitudeControl.accumulateRawLook(actor, 8, -4);
                    var pending = ClientBodyAttitudeControl.pendingLook(actor);
                    var pendingCamera = camera(mc, actor, false);
                    apply(actor, delayed);
                    check(ClientBodyAttitudeControl.pendingLook(actor).equals(pending)
                            && camera(mc, actor, false).equals(pendingCamera), "uncommitted newer mouse input also survives");
                }

                runtime.setRestingContactSnapshot(support);
                runtime.stageSoftPositionSupportRevalidation(support);
                apply(actor, new ClientboundPlayerPositionPacket(actor.getX() + 10, actor.getY(), actor.getZ(),
                        0, 0, RelativeMovement.ROTATION, 403));
                check(runtime.restingContactSnapshot() == null
                        && runtime.consumeSoftPositionSupportRevalidation().isEmpty(), "hard displacement drops stale support");
                check(semantic(actor).equals(newer), "hard position correction still has no look authority");

                apply(actor, new ClientboundPlayerPositionPacket(actor.getX() + 1, actor.getY(), actor.getZ(),
                        70, 12, Set.of(), 404));
                check(actor.getYRot() == 70 && actor.getXRot() == 12, "explicit teleport sets carriers");
                var expected = cc.sighs.gravityengine.gravity.look.GravityLocalLook.lookQuaternion(frame, 70, 12, 0);
                check(semantic(actor).equals(expected), "explicit teleport rebases semantic controller");
                var expectedCamera = cc.sighs.gravityengine.gravity.look.GravityLocalLook.cameraQuaternion(frame, 70, 12, 0);
                check(camera(mc, actor, false).equals(new org.joml.Quaternionf((float) expectedCamera.x(),
                        (float) expectedCamera.y(), (float) expectedCamera.z(), (float) expectedCamera.w()), 1e-5f),
                        "explicit teleport rebases rendered camera");
                // Active release must project Q even after unrelated stale scalar writes.
                if (active) {
                    actor.setYRot(35); actor.setXRot(-17);
                    BodyAttitudeTransactionCoordinator.projectActiveLook(actor);
                    check(Math.abs(actor.getYRot() - 70) < 1e-4 && Math.abs(actor.getXRot() - 12) < 1e-4,
                            "handoff projects semantic authority over stale scalar");
                    actor.setYRot(35); actor.setXRot(-17);
                    var beforeRelease = BodyAttitudeRuntime.Access.component(actor).snapshot();
                    var configGeneration = BodyAttitudeRuntime.Access.component(real).snapshot().authoritativeConfigGeneration();
                    ClientBodyAttitudeSync.handle(new ClientboundBodyAttitudeStatePayload(actor.getId(), actor.getUUID(),
                            mc.level.dimension().location(), Math.max(1, beforeRelease.authoritativeStreamEpoch() + 1),
                            0, mc.level.getGameTime(), configGeneration, true, false,
                            BodyAttitudeContinuity.INVALID, BodyAttitudeOwnership.INACTIVE, BodyAttitudeSuspensionReason.INACTIVE,
                            Quatd.IDENTITY, cc.sighs.gravityengine.gravity.look.GravityLocalLook.lookQuaternion(frame, 35, -17, 0),
                            false, false, Vec3d.ZERO));
                    check(BodyAttitudeRuntime.Access.component(actor).ownership() == BodyAttitudeOwnership.INACTIVE,
                            "replicated release accepted");
                    check(Math.abs(actor.getYRot() - 70) < 1e-4 && Math.abs(actor.getXRot() - 12) < 1e-4,
                            "replicated release projects retiring local controller, not stale server view");
                }
            }
            replicaDoesNotSolveGeometry(actor);
            System.out.println("CLIENT_CORRECTION_AUTHORITY_PASSED both-modes stale-look delayed-input explicit-rotation soft-hard-support camera third-person native-ack server-relocation replica-only");
        } finally {
            mc.level.setBlock(floor, originalFloor, 18);
            ClientPlayerBodyCommitHandler.onEntityRemoved(actor);
            ClientBodyAttitudeControl.clear(actor);
            actor.discard();
            mc.player = real;
        }
    }

    private static void apply(LocalPlayer actor, ClientboundPlayerPositionPacket correction) {
        var c = GravityEntityAccess.cast(actor).gravityengine$gravityComponent();
        var body = InstalledBodySnapshot.capture(actor);
        ClientPlayerBodyCommitHandler.handle(new ClientboundPlayerBodyCommitPayload(SyncGravityStatePayload.from(actor),
                c.state().applicationEpoch(), c.state().committedApplication(), body.pose(), body.width(), body.height(),
                c.operationState().installedCollisionUp(), c.operationState().lastCompletedFrame(), body.representation(),
                ClientboundPlayerBodyCommitPayload.CommitMode.CORRECTION, correction));
    }

    private static void replicaDoesNotSolveGeometry(LocalPlayer actor) {
        var c = GravityEntityAccess.cast(actor).gravityengine$gravityComponent();
        var body = InstalledBodySnapshot.capture(actor);
        var position = actor.position();
        var momentum = new Vec3(.12, -.24, .08);
        actor.setDeltaMovement(momentum);
        var frame = GravityFrame.fromDown(new Vec3d(-.51, -.86, 0).normalized(), .08);
        long epoch = c.state().applicationEpoch() + 1;
        var payload = new ClientboundPlayerBodyCommitPayload(SyncGravityStatePayload.from(actor), epoch,
                c.state().committedApplication(), body.pose(), body.width(), body.height(), frame.up(), frame,
                body.representation(), ClientboundPlayerBodyCommitPayload.CommitMode.TRANSACTION, null);
        ClientPlayerBodyCommitHandler.handle(payload);
        check(actor.position().equals(position) && actor.getDeltaMovement().equals(momentum),
                "replica installs server geometry without local anchor/velocity solving");
        check(c.operationState().installedCollisionUp().equals(frame.up()), "received axis installs exactly");
        var correction = new ClientboundPlayerPositionPacket(position.x + .01, position.y + .02, position.z,
                0, 0, RelativeMovement.ROTATION, 410);
        var relocation = new ClientboundPlayerBodyCommitPayload(payload.assignment(), epoch,
                payload.application(), payload.pose(), payload.installedWidth(), payload.installedHeight(),
                frame.up(), frame, payload.representation(), ClientboundPlayerBodyCommitPayload.CommitMode.RELOCATION, correction);
        var look = semantic(actor);
        ClientPlayerBodyCommitHandler.handle(relocation);
        check(actor.getX() == correction.getX() && actor.getY() == correction.getY(),
                "server relocation owns final anchor");
        check(actor.getDeltaMovement().equals(momentum), "geometry relocation leaves predicted world momentum intact");
        check(semantic(actor).equals(look), "server relocation leaves immediate input intact");
        check(c.operationState().lastCompletedFrame().orientation().equals(frame.orientation()),
                "complete server reference survives native position invalidation");
        // A duplicate state publication cannot undo the relocation or rewrite momentum.
        ClientPlayerBodyCommitHandler.handle(payload);
        check(actor.getX() == correction.getX() && actor.getDeltaMovement().equals(momentum), "duplicate transaction is inert");
    }
    private static void installLook(LocalPlayer actor, boolean active, float yaw, float pitch) {
        actor.setYRot(yaw); actor.setXRot(pitch); actor.yRotO = yaw; actor.xRotO = pitch;
        var component = BodyAttitudeRuntime.Access.component(actor);
        var before = component.snapshot();
        var q = cc.sighs.gravityengine.gravity.look.GravityLocalLook.lookQuaternion(FRAME, yaw, pitch, 0);
        var view = BodyRelativeViewState.fromSemantic(new SemanticView(q), Quatd.IDENTITY,
                new AttitudeSpaceTransform.LocalLookAngles(0, 0));
        component.restoreSnapshot(new BodyAttitudeComponent.Snapshot(BodyAttitudeState.kinematic(Quatd.IDENTITY, 0, 1),
                view, null, active ? BodyAttitudeControlPolicyResolver.resolveActive(CharacterAttitudeContract.FREE_ATTITUDE)
                        : BodyAttitudeDecision.suspended(BodyAttitudeSuspensionReason.INACTIVE),
                active ? BodyAttitudeContinuity.CONTINUOUS : BodyAttitudeContinuity.INVALID,
                active ? BodyAttitudeOwnership.ACTIVE : BodyAttitudeOwnership.INACTIVE, -1, 0, 0,
                before.authoritativeServerGameTick(), before.authoritativeRevision(), before.authoritativeStreamEpoch(),
                before.authoritativeConfigGeneration(), before.authoritativeStreamOpen()));
    }
    private static Quatd semantic(LocalPlayer actor) {
        var c = BodyAttitudeRuntime.Access.component(actor);
        return c.ownership() == BodyAttitudeOwnership.ACTIVE
                ? c.view().semantic(c.state().currentWorldFromBody()).worldFromController()
                : cc.sighs.gravityengine.gravity.look.GravityLocalLook.lookQuaternion(
                        FRAME, actor.getYRot(), actor.getXRot(), 0);
    }
    private static org.joml.Quaternionf camera(Minecraft mc, LocalPlayer actor, boolean detached) {
        var camera = new net.minecraft.client.Camera();
        camera.setup(mc.level, actor, detached, false, 1);
        return new org.joml.Quaternionf(camera.rotation());
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
