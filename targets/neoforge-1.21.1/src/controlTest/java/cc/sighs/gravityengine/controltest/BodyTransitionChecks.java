package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator;
import cc.sighs.gravityengine.gravity.geometry.BodyRepresentation;
import cc.sighs.gravityengine.gravity.integration.geometry.NativeAabbApplicationCommit;
import cc.sighs.gravityengine.gravity.integration.geometry.PlayerBodyHandoff;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.model.BodyTransitionDecision;
import cc.sighs.gravityengine.gravity.model.GravityAuthorityMode;
import cc.sighs.gravityengine.gravity.model.GravitySuppressionReason;
import cc.sighs.gravityengine.network.ClientboundPlayerBodyCommitPayload;
import cc.sighs.gravityengine.network.ClientboundPlayerBodyCommitPayload.CommitMode;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Real body-transition ownership: an authoritative synchronization request
 * must never become a teleport, a collider rewrite or a momentum reset.
 *
 * <p>Cases exercised here:</p>
 *
 * <ul>
 *   <li>Case 1 - application snapshot change over an unchanged native
 *       representation: metadata publish only;</li>
 *   <li>Case 2 - same representation plus a receiver request: reconciliation
 *       only, position and falling momentum preserved;</li>
 *   <li>Case 3 - real representation change with a valid current anchor:
 *       reconcile the collider, keep position and world-space velocity, no
 *       teleport;</li>
 *   <li>Case 4 - blocked routine application recovery retains the anchor,
 *       geometry and momentum until an unchanged-anchor proposal is legal.</li>
 *   <li>Case 5 - a pure application update over an installed exact body: no
 *       dimension refresh, no collider reinstall and no installed-shape churn.</li>
 * </ul>
 */
final class BodyTransitionChecks {
    private static final Vec3 ORIGIN = new Vec3(48.5, 250.0, 48.5);
    private static final Vec3 FALLING = new Vec3(-0.499, -0.839, -0.135);
    private static final GravityState ROTATED =
            new GravityState(new Vec3d(1, 0, 0), 0.001);
    private static final GravityState DEFAULT_DOWN =
            new GravityState(new Vec3d(0, -1, 0), 0.000625);

    /** Real {@code EntityEvent.Size} callback count: a refresh runs it once,
     * even when the resulting dimensions are equal. */
    private static int actorSizeEvents;

    private BodyTransitionChecks() {}

    /** Registered once from the mod constructor, before any check runs. */
    static void register() {
        NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.EntityEvent.Size event) -> {
                    if (event.getEntity() instanceof Actor) actorSizeEvents++;
                });
    }

    static void run(ServerLevel level) {
        clearFootprint(level, ORIGIN);
        var actor = new Actor(level, new GameProfile(UUID.randomUUID(), "BodyTransition"));
        try {
            actor.setPos(ORIGIN);
            installExactBody(actor);
            check(NativeAabbApplicationCommit.installedRepresentation(actor)
                    == BodyRepresentation.EXACT_BODY, "fixture installs an exact capsule body");

            representationChangePreservesMomentum(actor);
            applicationSnapshotChangeIsMetadataOnly(actor);
            resyncRequestIsSynchronizationOnly(actor);
            blockedApplicationRetainsAnchor(level, actor);
            applicationOnlyUpdateNeedsNoGeometryWork(actor);
            lostReferenceClearsFrameContinuity(actor);
            serverOwnsContinuousFrameTransaction(level);
            deferredPlayerDimensions(level);
            attitudeCommitFailureIsNotSuccess(level);

            System.out.println("BODY_TRANSITION_CHECKS_PASSED "
                    + "sync-only-metadata sync-only-momentum representation-change "
                    + "no-same-position-teleport routine-anchor-retention "
                    + "application-only-no-geometry-work");
        } finally {
            actor.discard();
        }
    }

    private static void serverOwnsContinuousFrameTransaction(ServerLevel level) {
        for (double increment : new double[] { -.5, .5, Math.toDegrees(.001) }) {
            var actor = new Actor(level, new GameProfile(UUID.randomUUID(), "ServerFrameCommit"));
            var floor = new BlockPos(56, 249, 56);
            var saved = level.getBlockState(floor);
            try {
                level.setBlock(floor, Blocks.STONE.defaultBlockState(), 18);
                actor.setPos(56.5, 251, 56.5);
                var component = GravityEntityAccess.cast(actor).gravityengine$gravityComponent();
                var runtime = component.operationState();
                GravityApplicationCoordinator.applyDirectAssignment(actor, tilted(30));
                PlayerBodyHandoff.afterConnectionTick(actor, false);
                actor.setPos(actor.position().add(0, 250 - actor.getBoundingBox().minY, 0));
                actor.setDeltaMovement(Vec3.ZERO);
                actor.connection.resetPosition();
                for (int step = 1; step <= 12; step++) {
                    var desired = tilted(30 + step * increment);
                    Vec3 before = actor.position();
                    Vec3d oldUp = runtime.installedCollisionUp();
                    actor.sent.clear();
                    GravityApplicationCoordinator.applyDirectAssignment(actor, desired);
                    PlayerBodyHandoff.afterConnectionTick(actor, false);
                    check(actor.position().equals(before), "routine rotation preserves accepted anchor");
                    // Tilting farther from world-up lifts this fixed-center
                    // capsule off the floor; tilting toward it penetrates.
                    Vec3d expectedUp = increment > 0 ? desired.down().negate() : oldUp;
                    check(runtime.installedCollisionUp().distanceSquared(expectedUp) < 1e-18,
                            "legal rotation installs; penetrating rotation retains installed axis");
                    check(actor.getDeltaMovement().equals(Vec3.ZERO), "geometry preserves world momentum");
                    check(!sentCorrection(actor), "routine support preparation emits zero corrections");
                    check(cc.sighs.gravityengine.gravity.integration.collision.GravityCurrentSupportQuery
                            .stableSupport(actor).isPresent() == (increment < 0),
                            "current-body query distinguishes retained contact from legal lift");
                    var published = ClientboundPlayerBodyCommitPayload.capture(actor, null);
                    check(published.installedUp().distanceSquared(expectedUp) < 1e-18
                                    && published.gravityReferenceFrame().down().distanceSquared(desired.down()) < 1e-20,
                            "body transaction publishes actual installed axis and environmental reference");
                }
                actor.setPos(actor.position().add(0, 3, 0));
                Vec3 airborneAnchor = actor.position();
                Vec3 momentum = new Vec3(.1, .2, .3);
                actor.setDeltaMovement(momentum);
                actor.sent.clear();
                PlayerBodyHandoff.afterConnectionTick(actor, false);
                check(actor.position().equals(airborneAnchor) && actor.getDeltaMovement().equals(momentum),
                        "airborne legal rotation preserves anchor and momentum");
                check(runtime.installedCollisionUp().distanceSquared(tilted(30 + 12 * increment).down().negate()) < 1e-18,
                        "airborne legal rotation commits requested axis");
                check(!sentCorrection(actor), "body-only transaction emits no correction");
                PlayerBodyHandoff.withAuthority(actor, () -> {
                    try (var transaction = cc.sighs.gravityengine.gravity.integration.geometry.BodyCommitTransaction
                            .beginAuthoritative(actor)) {
                        boolean rejected = false;
                        try { actor.move(net.minecraft.world.entity.MoverType.SELF, Vec3.ZERO); }
                        catch (IllegalStateException expected) { rejected = true; }
                        check(rejected, "callbacks cannot move against a partially installed server body");
                    }
                    return null;
                });
            } finally {
                actor.discard();
                level.setBlock(floor, saved, 18);
            }
        }
    }

    private static GravityState tilted(double degrees) {
        double angle = Math.toRadians(degrees);
        return new GravityState(new Vec3d(-Math.sin(angle), -Math.cos(angle), 0), .08);
    }

    private static void deferredPlayerDimensions(ServerLevel level) {
        var actor = new Actor(level, new GameProfile(UUID.randomUUID(), "DeferredDimensions"));
        try {
            actor.setPos(70.5, 310, 70.5);
            GravityApplicationCoordinator.applyDirectAssignment(actor, tilted(30));
            PlayerBodyHandoff.afterConnectionTick(actor, false);
            var access = GravityEntityAccess.cast(actor);
            var installed = access.gravityengine$installedDimensions();
            var pose = actor.getPose();
            var axis = access.gravityengine$gravityComponent().operationState().installedCollisionUp();
            var box = actor.getBoundingBox();
            actor.setPose(net.minecraft.world.entity.Pose.CROUCHING);
            check(actor.getPose() == net.minecraft.world.entity.Pose.CROUCHING,
                    "native pose selection remains immediate");
            check(access.gravityengine$installedDimensions().equals(installed) && actor.getBoundingBox().equals(box)
                            && access.gravityengine$installedPose() == pose,
                    "unowned pose notification cannot resize installed geometry");
            PlayerBodyHandoff.afterConnectionTick(actor, false);
            check(access.gravityengine$installedPose() == net.minecraft.world.entity.Pose.CROUCHING
                            && access.gravityengine$installedDimensions().height() < installed.height(),
                    "server body boundary installs deferred pose dimensions");
            check(access.gravityengine$gravityComponent().operationState().installedCollisionUp().equals(axis),
                    "resize never chooses an attitude or reference axis");
            cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry
                    .requireInstalledGeometry(actor, "deferred player dimensions");
        } finally { actor.discard(); }
    }

    private static void attitudeCommitFailureIsNotSuccess(ServerLevel level) {
        var actor = new Actor(level, new GameProfile(UUID.randomUUID(), "AttitudeCommitFailure"));
        try {
            var component = cc.sighs.gravityengine.attitude.runtime.BodyAttitudeRuntime.Access.component(actor);
            var before = component.snapshot();
            var candidate = new cc.sighs.gravityengine.attitude.runtime.BodyAttitudeLogicalCandidate(
                    before.state().asKinematic(), before.view(), null,
                    cc.sighs.gravityengine.attitude.runtime.BodyAttitudeDecision.suspended(
                            cc.sighs.gravityengine.attitude.runtime.BodyAttitudeSuspensionReason.INACTIVE),
                    cc.sighs.gravityengine.attitude.runtime.BodyAttitudeContinuity.INVALID,
                    cc.sighs.gravityengine.attitude.runtime.BodyAttitudeOwnership.INACTIVE,
                    1234, false, null,
                    cc.sighs.gravityengine.attitude.runtime.BodyAttitudeLogicalCandidate.Kind.SUSPENSION,
                    new cc.sighs.gravityengine.attitude.runtime.BodyAttitudeTransactionPrecondition(before),
                    new cc.sighs.gravityengine.attitude.runtime.BodyAttitudeLookRebase(25, 30));
            float yaw = actor.getYRot(), pitch = actor.getXRot(), oldYaw = actor.yRotO, oldPitch = actor.xRotO;
            actor.failNextPitch = true;
            boolean threw = false;
            try {
                cc.sighs.gravityengine.attitude.runtime.BodyAttitudeTransactionCoordinator
                        .commitLogicalOnly(actor, component, candidate);
            } catch (FixtureFailure expected) { threw = true; }
            check(threw && component.snapshot() == before, "unexpected commit failure propagates after actor rollback");
            check(actor.getYRot() == yaw && actor.getXRot() == pitch && actor.yRotO == oldYaw && actor.xRotO == oldPitch,
                    "failed partial look write restores all native carriers");
            component.invalidateContinuity();
            var newer = component.snapshot();
            check(!cc.sighs.gravityengine.attitude.runtime.BodyAttitudeTransactionCoordinator
                            .commitLogicalOnly(actor, component, candidate) && component.snapshot() == newer,
                    "stale candidate explicitly rejects without changing the newer owner");
        } finally { actor.discard(); }
    }

    private static final class FixtureFailure extends RuntimeException {}

    /**
     * Case 3: the collider representation changes but the current anchor stays
     * valid. The client must be told to reconcile its representation, and the
     * player must keep both position and momentum.
     */
    private static void representationChangePreservesMomentum(Actor actor) {
        actor.getAbilities().flying = true; // controlled flight suppresses CHARACTER
        actor.setDeltaMovement(FALLING);
        Vec3 position = actor.position();
        actor.sent.clear();

        var before = PlayerBodyHandoff.captureBodyState(actor);
        PlayerBodyHandoff.afterConnectionTick(actor, false);
        var after = PlayerBodyHandoff.captureBodyState(actor);

        check(PlayerBodyHandoff.transitionDecision(before, after, false)
                        == BodyTransitionDecision.GEOMETRY_REPRESENTATION_CHANGE,
                "representation change decision");
        check(before.installedBody().representation() == BodyRepresentation.EXACT_BODY
                && after.installedBody().representation() == BodyRepresentation.NATIVE_AABB,
                "exact body becomes the platform AABB");
        check(after.installedGeometryDiffersFrom(before),
                "installed geometry identity reports the collider change");
        check(after.installedBody().bodyShapeRevision()
                        != before.installedBody().bodyShapeRevision(),
                "installed shape generation advanced with the collider");
        check(actor.position().equals(position), "representation change never relocates the anchor");
        check(actor.getDeltaMovement().equals(FALLING),
                "representation change preserves world-space velocity actual=" + actor.getDeltaMovement());
        check(!sentCorrection(actor), "same-position representation change never teleports");
        check(sentMode(actor, CommitMode.TRANSACTION) == 1,
                "client receives exactly one representation reconciliation");
        actor.sent.clear();
    }

    /** Case 1: only the committed application snapshot changed. */
    private static void applicationSnapshotChangeIsMetadataOnly(Actor actor) {
        var component = GravityEntityAccess.cast(actor).gravityengine$gravityComponent();
        check(component.state().setAssigned(DEFAULT_DOWN, GravityAuthorityMode.DIRECT, false),
                "fixture publishes a new authoritative assignment snapshot");
        actor.setDeltaMovement(FALLING);
        Vec3 position = actor.position();
        long epochBefore = component.state().applicationEpoch();
        actor.sent.clear();

        var before = PlayerBodyHandoff.captureBodyState(actor);
        PlayerBodyHandoff.afterConnectionTick(actor, false);
        var after = PlayerBodyHandoff.captureBodyState(actor);

        check(PlayerBodyHandoff.transitionDecision(before, after, false)
                        == BodyTransitionDecision.SYNC_ONLY,
                "application snapshot change is synchronization only");
        check(after.application().epoch() > epochBefore, "application epoch advanced");
        check(after.applicationDiffersFrom(before), "application dimension changed");
        check(!after.installedGeometryDiffersFrom(before),
                "an application snapshot change is not installed geometry");
        check(after.installedBody().representation() == BodyRepresentation.NATIVE_AABB,
                "native representation survives the metadata publish");
        check(sentMode(actor, CommitMode.TRANSACTION) == 1,
                "publication uses the complete body transaction");
        check(!sentCorrection(actor), "metadata publish never teleports");
        check(actor.position().equals(position) && actor.getDeltaMovement().equals(FALLING),
                "metadata publish never writes position or momentum");
        actor.sent.clear();
    }

    /**
     * Case 2 with an explicit falling velocity: the exact reported failure.
     * The receiver could not install the last authoritative snapshot, so it
     * asked for reconciliation. That request must not touch momentum.
     */
    private static void resyncRequestIsSynchronizationOnly(Actor actor) {
        actor.setDeltaMovement(FALLING);
        Vec3 position = actor.position();
        PlayerBodyHandoff.requestSynchronization(actor);
        actor.sent.clear();

        var before = PlayerBodyHandoff.captureBodyState(actor);
        PlayerBodyHandoff.afterConnectionTick(actor, false);
        var after = PlayerBodyHandoff.captureBodyState(actor);

        check(PlayerBodyHandoff.transitionDecision(before, after, true)
                        == BodyTransitionDecision.SYNC_ONLY,
                "resync request is synchronization only");
        check(actor.getDeltaMovement().equals(FALLING),
                "resync preserves falling momentum actual=" + actor.getDeltaMovement());
        check(actor.getDeltaMovement().y() < 0
                        && actor.getDeltaMovement().length() > 0.9,
                "falling magnitude is not reset to near zero");
        check(actor.position().equals(position), "resync preserves position");
        check(!sentCorrection(actor), "resync never teleports");
        check(sentMode(actor, CommitMode.TRANSACTION) == 1,
                "resync re-publishes the authoritative representation");
        actor.sent.clear();
    }

    /** Routine application recovery must defer before installing a displaced candidate. */
    private static void blockedApplicationRetainsAnchor(ServerLevel level, Actor actor) {
        var component = GravityEntityAccess.cast(actor).gravityengine$gravityComponent();
        BlockPos obstacle = BlockPos.containing(actor.position()).east();
        var saved = level.getBlockState(obstacle);
        level.setBlock(obstacle, Blocks.STONE.defaultBlockState(), 2);
        try {
            actor.getAbilities().flying = false;
            component.state().setAssigned(ROTATED, GravityAuthorityMode.DIRECT, false);
            check(level.noCollision(actor, actor.getBoundingBox()), "native start is legal");
            actor.setDeltaMovement(FALLING);
            var before = PlayerBodyHandoff.captureBodyState(actor);
            actor.sent.clear();
            PlayerBodyHandoff.afterConnectionTick(actor, false);
            var after = PlayerBodyHandoff.captureBodyState(actor);
            check(!after.positionAnchorDiffersFrom(before) && !after.installedGeometryDiffersFrom(before),
                    "blocked application retains anchor and installed geometry");
            check(actor.getDeltaMovement().equals(FALLING), "deferred application preserves momentum");
            check(corrections(actor).isEmpty(), "routine recovery emits zero corrections");
        } finally {
            level.setBlock(obstacle, saved, 2);
        }
        PlayerBodyHandoff.afterConnectionTick(actor, false);
        check(NativeAabbApplicationCommit.installedRepresentation(actor) == BodyRepresentation.EXACT_BODY,
                "deferred application retries once geometry is legal");
    }

    /** Install the fixture body through the production planner, non-networked. */
    private static void installExactBody(Actor actor) {
        var listener = actor.connection;
        actor.connection = null;
        try {
            GravityApplicationCoordinator.applyDirectAssignment(actor, ROTATED);
            GravityApplicationCoordinator.updateBody(actor);
        } finally {
            actor.connection = listener;
        }
    }

    /**
     * Case 5: an application-only update over an installed exact body.
     *
     * <p>The assigned state changes its strength while the plan stays
     * {@code CHARACTER(DIRECT)}. Nothing about the installed collider may move:
     * no dimension refresh, no collider reinstall, no installed-shape
     * generation advance, no frame-continuity reset and no relocation.</p>
     */
    private static void applicationOnlyUpdateNeedsNoGeometryWork(Actor actor) {
        var component = GravityEntityAccess.cast(actor).gravityengine$gravityComponent();
        var runtime = component.operationState();
        check(NativeAabbApplicationCommit.installedRepresentation(actor)
                        == BodyRepresentation.EXACT_BODY,
                "application-only fixture keeps an installed exact body");

        actor.setDeltaMovement(FALLING);
        Vec3 position = actor.position();
        long revisionBefore = runtime.bodyShapeRevision();
        Vec3d installedUpBefore = runtime.installedCollisionUp();
        var poseBefore = actor.getPose();
        var dimensionsBefore = cc.sighs.gravityengine.gravity.minecraft.geometry
                .GravityEntityGeometry.dimensions(actor);
        int sizeEventsBefore = actorSizeEvents;
        var planBefore = component.state().appliedPlan();

        var before = PlayerBodyHandoff.captureBodyState(actor);
        check(component.state().setAssigned(
                        new GravityState(ROTATED.down(), ROTATED.strength() * 2.0D),
                        GravityAuthorityMode.DIRECT, false),
                "fixture publishes a new application snapshot for the same plan");
        actor.sent.clear();

        PlayerBodyHandoff.afterConnectionTick(actor, false);
        var after = PlayerBodyHandoff.captureBodyState(actor);

        check(after.applicationDiffersFrom(before),
                "application dimension reports the new snapshot");
        check(component.state().appliedPlan().equals(planBefore)
                        && component.state().appliedPlan().kind()
                        == cc.sighs.gravityengine.gravity.model.GravityApplicationPlan.Kind.CHARACTER,
                "application plan kind is unchanged");
        check(!after.installedGeometryDiffersFrom(before),
                "an application-only update is not installed geometry");
        check(!after.positionAnchorDiffersFrom(before),
                "an application-only update relocates nothing");
        check(PlayerBodyHandoff.transitionDecision(before, after, false)
                        == BodyTransitionDecision.SYNC_ONLY,
                "an application-only update is synchronization only");
        check(runtime.bodyShapeRevision() == revisionBefore,
                "no installed-shape generation churn");
        check(runtime.installedCollisionUp() == installedUpBefore,
                "the installed collider is not reinstalled");
        check(actor.getPose() == poseBefore, "no pose rewrite");
        check(cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry
                        .dimensions(actor).equals(dimensionsBefore),
                "no dimensions rewrite");
        check(actorSizeEvents == sizeEventsBefore,
                "no refreshDimensions/Size callback for a pure application update");
        check(actor.position().equals(position), "application update writes no position");
        check(actor.getDeltaMovement().equals(FALLING),
                "application update writes no momentum actual=" + actor.getDeltaMovement());
        check(!sentCorrection(actor), "application update never teleports");
        actor.sent.clear();
    }

    /**
     * Case 6: the environmental reference genuinely disappears.
     *
     * <p>Losing the reference is a reference discontinuity, not an application
     * or collider event: the retained presentation basis cannot outlive the
     * influence that produced it. An application fallback inside the same
     * influence (the committed plan changes, the reference does not) is not a
     * loss and clears nothing.</p>
     */
    private static void lostReferenceClearsFrameContinuity(Actor actor) {
        var component = GravityEntityAccess.cast(actor).gravityengine$gravityComponent();
        var runtime = component.operationState();
        long suppressionRevision = component.state().influenceRevision();
        long revision = component.state().assignmentRevision();

        // An authoritative snapshot that keeps the reference is not a loss.
        GravityApplicationCoordinator.applyRemoteSnapshot(actor, ROTATED,
                GravityAuthorityMode.FIELD, cc.sighs.gravityengine.api.FieldPresence.PRESENT, revision + 1L,
                GravitySuppressionReason.NONE, suppressionRevision);
        check(component.state().hasActiveGravityReference(),
                "the fixture holds an active environmental reference");
        try (var ignored = runtime.openMove(
                cc.sighs.gravityengine.gravity.GravityFrame.fromState(
                        ROTATED,
                        cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter
                                .toVec3d(actor.position())
                ),
                actor.level().getGameTime()
        )) {}
        check(runtime.lastCompletedFrame() != null,
                "the fixture publishes a completed presentation frame");
        long discontinuityBefore = runtime.lastPresentationDiscontinuityRevision();

        // The field disappears: the reference and its presentation basis are gone.
        GravityApplicationCoordinator.applyRemoteSnapshot(actor, GravityState.DEFAULT,
                GravityAuthorityMode.FIELD, cc.sighs.gravityengine.api.FieldPresence.ABSENT, revision + 2L,
                GravitySuppressionReason.NONE, suppressionRevision);

        check(!component.state().hasActiveGravityReference(),
                "the environmental reference is gone");
        check(runtime.lastCompletedFrame() == null,
                "losing the reference clears the retained frame basis");
        check(runtime.lastPresentationDiscontinuityRevision() > discontinuityBefore,
                "losing the reference declares a presentation discontinuity");
    }

    private static void clearFootprint(ServerLevel level, Vec3 origin) {
        BlockPos center = BlockPos.containing(origin).above();
        for (int x = -4; x <= 4; x++) {
            for (int y = -2; y <= 3; y++) {
                for (int z = -4; z <= 4; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    private static boolean sentCorrection(Actor actor) {
        return !corrections(actor).isEmpty()
                || actor.sent.stream().anyMatch(packet ->
                        packet instanceof net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket);
    }

    private static List<Set<RelativeMovement>> corrections(Actor actor) {
        List<Set<RelativeMovement>> found = new ArrayList<>();
        for (var packet : actor.sent) {
            if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom
                    && custom.payload() instanceof ClientboundPlayerBodyCommitPayload commit
                    && commit.correction() != null) {
                found.add(commit.correction().getRelativeArguments());
            }
        }
        return found;
    }

    private static int sentMode(Actor actor, CommitMode mode) {
        int count = 0;
        for (var packet : actor.sent) {
            if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom
                    && custom.payload() instanceof ClientboundPlayerBodyCommitPayload commit
                    && commit.mode() == mode
                    && commit.correction() == null) {
                count++;
            }
        }
        return count;
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError("Body transition: " + message);
    }

    private static String describe(Actor actor) {
        List<String> described = new ArrayList<>();
        for (var packet : actor.sent) {
            if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom) {
                described.add(custom.payload().type().id() + ":"
                        + (custom.payload() instanceof ClientboundPlayerBodyCommitPayload commit
                        ? commit.mode() + "/" + commit.representation()
                        + "/correction=" + (commit.correction() != null)
                        : "other"));
            } else {
                described.add(packet.getClass().getSimpleName());
            }
        }
        return described.toString();
    }

    private static String vec(Vec3 vec) {
        return vec.toString();
    }

    /** Real ServerPlayer with a recording connection; no client-authored state. */
    private static final class Actor extends ServerPlayer {
        final List<Packet<?>> sent = new ArrayList<>();
        boolean failNextPitch;

        @Override public void setXRot(float pitch) {
            if (failNextPitch) {
                failNextPitch = false;
                throw new FixtureFailure();
            }
            super.setXRot(pitch);
        }

        Actor(ServerLevel level, GameProfile profile) {
            super(level.getServer(), level, profile,
                    net.minecraft.server.level.ClientInformation.createDefault());
            var connection = new Connection(PacketFlow.SERVERBOUND) {
                @Override public void send(Packet<?> packet) {}
                @Override public void send(Packet<?> packet, net.minecraft.network.PacketSendListener listener) {}
            };
            this.connection = new ServerGamePacketListenerImpl(
                    level.getServer(), connection, this,
                    CommonListenerCookie.createInitial(profile, false)) {
                @Override public void send(Packet<?> packet) { sent.add(packet); }
            };
            level.addNewPlayer(this);
            sent.clear();
        }
    }
}
