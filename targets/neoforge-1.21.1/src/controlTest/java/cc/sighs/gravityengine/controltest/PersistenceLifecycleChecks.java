package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.GravityEngineAttachments;
import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.attitude.*;
import cc.sighs.gravityengine.attitude.persistence.BodyAttitudePersistentSeed;
import cc.sighs.gravityengine.attitude.runtime.*;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator;
import cc.sighs.gravityengine.gravity.integration.PlayerPhysicalLoadBootstrap;
import cc.sighs.gravityengine.gravity.integration.geometry.PlayerBodyHandoff;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.model.GravityAuthorityMode;
import cc.sighs.gravityengine.math.Quatd;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Real NeoForge attachment/event and production Entity load Mixin checks. */
final class PersistenceLifecycleChecks {
    private static final GravityState DIRECT =
            new GravityState(new Vec3d(1, 0, 0), .001);
    private static final Quatd BODY =
            Quatd.rotationZYX(.6, .7, .8);
    private static final Quatd CONTROLLER =
            Quatd.rotationZYX(.9, .4, -.3);

    static void run(ServerLevel level) {
        logoutThenLogin(level, false);
        logoutThenLogin(level, true);
        replacement(level, false);
        replacement(level, true);
        ordinaryEntityLoad(level);
        System.out.println("PERSISTENCE_LIFECYCLE_CHECKS_PASSED logout-login non-death-clone death-clone");
    }

    private static ServerPlayer player(ServerLevel level, GameProfile profile) {
        var player = new ServerPlayer(level.getServer(), level, profile,
                net.minecraft.server.level.ClientInformation.createDefault());
        var connection = new Connection(PacketFlow.SERVERBOUND) {
            @Override public void send(Packet<?> packet) {}
            @Override public void send(Packet<?> packet, PacketSendListener listener) {}
        };
        player.connection = new ServerGamePacketListenerImpl(level.getServer(), connection, player,
                CommonListenerCookie.createInitial(profile, false)) {
            @Override public void send(Packet<?> packet) {}
        };
        player.setPos(8, 300, 8);
        return player;
    }

    private static void initialize(ServerPlayer player) {
        GravityApplicationCoordinator.applyDirectAssignment(player, DIRECT);
        var component = BodyAttitudeRuntime.Access.component(player);
        var state = BodyAttitudeState.initialized(
                BODY,
                new Vec3d(.1, .2, .3),
                5,
                5
        );
        var view = BodyRelativeViewState.fromSemantic(new cc.sighs.gravityengine.attitude.SemanticView(CONTROLLER), BODY,
                new cc.sighs.gravityengine.attitude.AttitudeSpaceTransform.LocalLookAngles(0, 0));
        var decision = BodyAttitudeDecision.active(new BodyAttitudeControlProfile(
                BodyAttitudeConstraintKind.FREE_ATTITUDE, 0, BodyAttitudeControlAuthority.full()));
        check(component.installReplicated(new ReplicatedAttitudeState(state, view, decision,
                BodyAttitudeContinuity.CONTINUOUS, BodyAttitudeOwnership.ACTIVE, 5, 5, 1, 1)).accepted(), "install live seed source");
        selectExplicitSwim(player);
        player.getData(GravityEngineAttachments.BODY_ATTITUDE_PERSISTENCE);
        player.serverLevel().addNewPlayer(player);
    }

    /**
     * Durable actor state belongs to an explicitly selected special contract.
     * Ordinary reference-aligned motion discards the dormant seed instead of
     * resurrecting free ownership after a load or replacement.
     */
    private static void selectExplicitSwim(ServerPlayer player) {
        ((cc.sighs.gravityengine.gravity.minecraft.access.CharacterControlAccess) player)
                .gravityengine$characterMode().installReplicated(true);
    }

    private static void logoutThenLogin(ServerLevel level, boolean receivingElytra) {
        var profile = new GameProfile(UUID.randomUUID(), receivingElytra ? "PersistElytra" : "PersistenceDisk");
        var old = player(level, profile);
        initialize(old);
        long revision = gravity(old).state().assignmentRevision();
        // 1.21.1 PlayerList.remove: LoggedOut -> player.dat save -> level removal/Leave.
        // Later PlayerList.load executes Entity.load, including attachment decode BEFORE
        // readAdditionalSaveData/reapplyPosition and the gravity load RETURN injection.
        level.getServer().getPlayerList().remove(old);
        check(BodyAttitudeRuntime.Access.component(old).ownership() == BodyAttitudeOwnership.INACTIVE, "logout invalidates live state");
        var loaded = player(level, profile);
        check(level.getServer().getPlayerList().load(loaded).isPresent(), "real player.dat loaded into new instance");
        check(gravity(loaded).state().assignedAuthority() == GravityAuthorityMode.DIRECT, "disk DIRECT authority");
        check(gravity(loaded).state().assignmentRevision() == revision, "disk revision");
        check(gravity(loaded).state().applicationBootstrapPending(), "disk bootstrap pending");
        checkSeed(loaded.getData(GravityEngineAttachments.BODY_ATTITUDE_PERSISTENCE).pendingLoadedSeed().orElseThrow());
        if (!receivingElytra) {
            /*
             * Production completes the player physical bootstrap at the
             * completed connection-tick boundary, where body changes are
             * authorized. Calling the bootstrap owner directly would defer
             * exactly like a packet-driven body change before that boundary.
             */
            PlayerBodyHandoff.afterConnectionTick(loaded, false);
            check(
                    !gravity(loaded).state()
                            .applicationBootstrapPending(),
                    "ordinary login bootstrap"
            );
            checkOrdinary(loaded);
            return;
        }
        loaded.startFallFlying();
        var actor = BodyAttitudeRuntime.Access.component(loaded);
        var original = actor.snapshot();
        var slot = loaded.getData(GravityEngineAttachments.BODY_ATTITUDE_PERSISTENCE);
        var seed = slot.pendingLoadedSeed().orElseThrow();
        actor.restoreSnapshot(new BodyAttitudeComponent.Snapshot(original.state(), original.view(), original.lastStepResult(),
                original.decision(), original.continuity(), original.ownership(), original.lastLocalSimulationStep(),
                Long.MAX_VALUE, original.lifecycleEpoch(), original.authoritativeServerGameTick(), original.authoritativeRevision(),
                original.authoritativeStreamEpoch(), original.authoritativeConfigGeneration(), original.authoritativeStreamOpen()));
        var before = actor.snapshot();
        check(!bootstrapThroughHandoff(loaded), "real post-read commit failure");
        check(actor.snapshot() == before, "failed actor commit rolls back");
        check(slot.pendingLoadedSeed().orElseThrow() == seed, "failed bootstrap retains same seed");
        check(slot.snapshotForSave().orElseThrow() == seed, "save during retry retains pending seed");
        check(gravity(loaded).state().applicationBootstrapPending(), "whole bootstrap remains pending on actor failure");
        actor.restoreSnapshot(original);
        check(bootstrapThroughHandoff(loaded), "login bootstrap succeeds");
        checkRestored(loaded);
    }

    /**
     * Runs the production connection-tick handoff and reports whether the
     * load bootstrap completed.
     */
    private static boolean bootstrapThroughHandoff(ServerPlayer player) {
        PlayerBodyHandoff.afterConnectionTick(player, false);
        return !gravity(player).state().applicationBootstrapPending();
    }

    private static void replacement(ServerLevel level, boolean death) {
        var profile = new GameProfile(UUID.randomUUID(), death ? "PersistenceDeath" : "PersistenceClone");
        var old = player(level, profile);
        initialize(old);
        long revision = gravity(old).state().assignmentRevision();
        var oldFrame = gravity(old).operationState().geometryReferenceFrame();
        // PlayerList.respawn removes BEFORE restoreFrom posts Clone. For End return,
        // showEndCredits may already have removed the player; no Entity.load is involved.
        level.removePlayerImmediately(old, death ? Entity.RemovalReason.KILLED : Entity.RemovalReason.CHANGED_DIMENSION);
        check(BodyAttitudeRuntime.Access.component(old).ownership() == BodyAttitudeOwnership.INACTIVE, "leave invalidates live actor");
        check(!BodyAttitudeRuntime.Access.component(old).view().initialized(), "leave invalidates view");
        checkSeed(old.getData(GravityEngineAttachments.BODY_ATTITUDE_PERSISTENCE).pendingLoadedSeed().orElseThrow());
        var replacement = player(level, profile);
        replacement.restoreFrom(old, !death); // real Clone event and NeoForge attachment copy handler
        check(gravity(replacement).state().assignedAuthority() == GravityAuthorityMode.DIRECT, "clone DIRECT authority");
        check(DIRECT.sameSyncData(gravity(replacement).state().assignedState()), "clone assignment");
        check(gravity(replacement).state().assignmentRevision() == revision, "clone revision");
        check(gravity(replacement).state().applicationBootstrapPending(), "clone pending before bootstrap");
        check(gravity(replacement).state().applicationEpoch() == 0, "clone has no old application epoch");
        check(gravity(replacement).operationState().geometryReferenceFrame() == null, "clone has no old geometry/frame");
        var slot = replacement.getData(GravityEngineAttachments.BODY_ATTITUDE_PERSISTENCE);
        check(slot.pendingLoadedSeed().isPresent() != death, "explicit death/non-death attitude policy");
        if (!death) checkSeed(slot.pendingLoadedSeed().orElseThrow());
        replacement.setPos(12, 300, 8); // Vanilla's post-Clone destination restoration
        Vec3 position = replacement.position();
        check(PlayerPhysicalLoadBootstrap.bootstrapReplacementPlayer(replacement), "replacement bootstrap succeeds");
        check(position.equals(replacement.position()), "attitude restore never moves replacement");
        check(gravity(replacement).operationState().geometryReferenceFrame() != oldFrame, "fresh destination geometry evidence");
        check(!gravity(replacement).state().applicationBootstrapPending(), "complete replacement clears pending");
        check(slot.pendingLoadedSeed().isEmpty(), "committed restore consumes seed");
        check(DIRECT.sameSyncData(gravity(replacement).state().appliedState()), "bootstrap respects DIRECT");
        checkOrdinary(replacement);
    }

    private static void checkRestored(ServerPlayer player) {
        var actor = BodyAttitudeRuntime.Access.component(player);
        check(Math.abs(BODY.dot(actor.state().currentWorldFromBody())) > 1 - 1e-12, "body restored");
        check(Math.abs(CONTROLLER.dot(actor.view().semantic(actor.state().currentWorldFromBody()).worldFromController())) > 1 - 1e-12,
                "controller restored");
        check(
                actor.state().angularVelocityWorld().equals(Vec3d.ZERO),
                "free-attitude seed has no Elytra inertia"
        );
        check(actor.ownership() == BodyAttitudeOwnership.ACTIVE, "receiving Elytra owns actor");
        check(actor.currentDecision().profile().constraint() == BodyAttitudeConstraintKind.ELYTRA_ALIGNED,
                "restored actor uses receiving Elytra policy");
        check(!((cc.sighs.gravityengine.gravity.minecraft.access.CharacterControlAccess) player)
                .gravityengine$characterMode().swimActive(), "load cleared transient swim");
        check(!gravity(player).state().applicationBootstrapPending(), "bootstrap completed");
        check(player.getData(GravityEngineAttachments.BODY_ATTITUDE_PERSISTENCE).pendingLoadedSeed().isEmpty(), "seed consumed once");
    }

    private static void checkOrdinary(ServerPlayer player) {
        var actor = BodyAttitudeRuntime.Access.component(player);
        check(actor.ownership() == BodyAttitudeOwnership.INACTIVE, "ordinary load has no free owner");
        check(actor.renderableSnapshot() == null, "ordinary load ignores dormant actor pose");
        check(!((cc.sighs.gravityengine.gravity.minecraft.access.CharacterControlAccess) player)
                .gravityengine$characterMode().swimActive(), "ordinary load has no transient swim");
        check(actor.snapshot().authoritativeStreamOpen(), "ordinary state has a new stream");
        check(!gravity(player).state().applicationBootstrapPending(), "ordinary bootstrap completed");
        check(player.getData(GravityEngineAttachments.BODY_ATTITUDE_PERSISTENCE).pendingLoadedSeed().isEmpty(),
                "ordinary load consumed or discarded the detached seed");
    }

    private static void ordinaryEntityLoad(ServerLevel level) {
        var samples = new java.util.concurrent.atomic.AtomicInteger();
        var id = new cc.sighs.gravityengine.gravity.model.GravityFieldId(
                "gravityengine_control_tests", "load");
        var fields = cc.sighs.gravityengine.gravity.field.GravityFieldRuntime.get(level)
                .registry();
        fields.put(new cc.sighs.gravityengine.gravity.field.GravityFieldInstance(id,
                cc.sighs.gravityengine.gravity.field.GravityFieldOrder.named(id), query -> {
                    samples.incrementAndGet();
                    return new cc.sighs.gravityengine.api.field.GravityFieldSample(
                            new Vec3d(.001, 0, 0)
                    );
                }, cc.sighs.gravityengine.gravity.field.InfiniteInfluenceVolume.INSTANCE,
                cc.sighs.gravityengine.api.field.GravityFieldCompositionMode.OVERRIDE, 1));
        try {
            for (boolean tagged : new boolean[]{false, true}) {
                for (var authority : new GravityAuthorityMode[]{GravityAuthorityMode.FIELD, GravityAuthorityMode.DIRECT}) {
                    if (!tagged && authority == GravityAuthorityMode.DIRECT) continue;
                    var source = new net.minecraft.world.entity.animal.Pig(net.minecraft.world.entity.EntityType.PIG, level);
                    source.setPos(8, 300, 8);
                    if (tagged) GravityEntityAccess.cast(source).gravityengine$gravityComponent().state().setAssigned(DIRECT, authority, false);
                    var disk = source.saveWithoutId(new net.minecraft.nbt.CompoundTag());
                    check(disk.contains("GravityEngineGravity") == tagged, "tagged/untagged ordinary save");
                    var loaded = new net.minecraft.world.entity.animal.Pig(net.minecraft.world.entity.EntityType.PIG, level);
                    loaded.load(disk); // actual Entity load RETURN hook
                    var component = GravityEntityAccess.cast(loaded).gravityengine$gravityComponent();
                    check(component.state().applicationBootstrapPending(), "ordinary load marks one bootstrap");
                    samples.set(0);
                    loaded.tickCount = 20; // prove bootstrap does not repeat the periodic refresh
                    loaded.baseTick();
                    check(!component.state().applicationBootstrapPending(), "first base tick completes bootstrap");
                    check(component.state().applicationEpoch() == 1, "one first-tick application commit");
                    check(samples.get() == (authority == GravityAuthorityMode.FIELD ? 1 : 0), "one bootstrap FIELD sample");
                    check(component.state().appliedPlan().usesCustomBody(), "first movement has current custom geometry");
                    long epoch = component.state().applicationEpoch();
                    loaded.move(net.minecraft.world.entity.MoverType.SELF, new Vec3(0, .02, 0));
                    check(component.state().applicationEpoch() == epoch, "first move does not bootstrap again");
                    check(samples.get() == (authority == GravityAuthorityMode.FIELD ? 2 : 0), "first movement owns its one sample");
                }
            }
        } finally { fields.remove(id, 1); }
    }

    private static void checkSeed(BodyAttitudePersistentSeed seed) {
        check(Math.abs(BODY.dot(seed.worldFromBody())) > 1 - 1e-12, "detached body seed");
        check(Math.abs(CONTROLLER.dot(seed.worldFromController())) > 1 - 1e-12, "detached controller seed");
        check(seed.elytraDynamics().isEmpty(), "non-Elytra seed contains no dynamics");
    }

    private static cc.sighs.gravityengine.gravity.component.EntityGravityComponent gravity(ServerPlayer player) {
        return GravityEntityAccess.cast(player).gravityengine$gravityComponent();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("Persistence lifecycle: " + message);
    }
}
