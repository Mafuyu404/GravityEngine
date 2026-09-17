package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.GravityEngineAttachments;
import cc.sighs.gravityengine.api.FieldPresence;
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
        attachmentPersistence(level);
        queryCoverageReconciliation(level);
        fieldCompositionOrder(level);
        legacyImportAndPrecedence(level);
        nativeLifecycleFreshSync(level);
        ordinaryEntityLoad(level);
        logoutThenLogin(level, false);
        logoutThenLogin(level, true);
        replacement(level, false);
        replacement(level, true);

        System.out.println(
                "PERSISTENCE_LIFECYCLE_CHECKS_PASSED "
                        + "attachment-direct attachment-field "
                        + "empty-incomplete additive-partial "
                        + "complete-additive runtime-coverage-loss "
                        + "complete-present complete-absent "
                        + "save-while-unknown legacy-migration "
                        + "legacy-precedence malformed-diagnostics "
                        + "field-continuity-bit composition-order "
                        + "query-local-coverage ordinary-entity DIRECT "
                        + "tracking login respawn dimension fresh-sync "
                        + "no-lifetime-packet"
        );
    }

    private static final java.util.Map<ServerPlayer, java.util.List<Packet<?>>> SENT = new java.util.IdentityHashMap<>();

    private static final String ATTACHMENTS_KEY = "neoforge:attachments";
    private static final String GRAVITY_ATTACHMENT_KEY =
            "gravityengine:gravity_persistence";

    private static void queryCoverageReconciliation(ServerLevel level) {
        var runtime = cc.sighs.gravityengine.gravity.field.GravityFieldRuntime.get(level);
        var provider = com.example.examplemod.gravity.ProviderFixture.ID;
        var second = com.example.examplemod.gravity.ProviderFixture.SECOND_ID;
        var complete = cc.sighs.gravityengine.api.field.FieldCoverage.COMPLETE;
        var incomplete = cc.sighs.gravityengine.api.field.FieldCoverage.INCOMPLETE;
        var seed = new GravityState(new Vec3d(1, 0, 0), .05);
        var source = player(level, new GameProfile(UUID.randomUUID(), "CoverageSource"));
        source.setPos(8, 300, 8);
        gravity(source).state().setAssigned(seed, GravityAuthorityMode.FIELD, true);
        cc.sighs.gravityengine.gravity.persistence.GravityPersistence.materialize(source);
        var disk = source.saveWithoutId(new net.minecraft.nbt.CompoundTag());
        long durable = gravity(source).state().assignmentRevision();
        source.discard();

        com.example.examplemod.gravity.ProviderFixture.secondCoverage(level, query -> incomplete);
        var loaded = player(level, new GameProfile(UUID.randomUUID(), "CoverageLoaded"));
        loaded.load(disk);
        loaded.setPos(8, 300, 8);
        long syncBefore = gravity(loaded).state().assignmentSyncRevision();
        try (var barrier = cc.sighs.gravityengine.gravity.integration.geometry.GravityApplicationBarrier.hold(loaded)) {
            serverTick(loaded);
            check(gravity(loaded).state().fieldPresence() == FieldPresence.UNKNOWN,
                    "rejected continuity installation cannot fabricate PRESENT");
            check(!gravity(loaded).state().appliedPlan().usesFieldAcceleration(),
                    "blocked geometry keeps Vanilla application");
            check(seed.sameSyncData(gravity(loaded).state().assignedState()),
                    "blocked geometry preserves durable seed");
        }
        serverTick(loaded);
        check(gravity(loaded).state().fieldPresence() == FieldPresence.UNKNOWN, "empty incomplete load stays UNKNOWN");
        check(gravityPackets(loaded) > 0, "UNKNOWN committed truth is synchronized");
        var retained = gravity(loaded).state().committedApplication();
        check(retained.plan().usesFieldAcceleration(), "safe UNKNOWN seed installs FIELD continuity");

        try (var a = cc.sighs.gravityengine.api.GravityEngineApi.publish(level, provider,
                     additive("coverage_a", new Vec3d(.02, 0, 0), 1))) {
            long publicationRevision = runtime.publicationRevision();
            serverTick(loaded);
            check(seed.sameSyncData(gravity(loaded).state().assignedState()), "partial positive cannot replace durable seed");
            check(retained.equals(gravity(loaded).state().committedApplication()), "partial positive keeps installed continuity");
            check(gravity(loaded).state().assignmentRevision() == durable, "partial query cannot increment durable revision");
            check(gravityRecord(loaded.saveWithoutId(new net.minecraft.nbt.CompoundTag())).equals(gravityRecord(disk)),
                    "saving UNKNOWN round-trips the authoritative tuple");
            var partial = cc.sighs.gravityengine.api.GravityEngineApi.sample(level, new Vec3d(8, 300, 8));
            check(partial.coverage() == incomplete && partial.fieldPresent(), "API exposes partial positive coverage");

            try (var b = cc.sighs.gravityengine.api.GravityEngineApi.publish(level, second,
                         additive("coverage_b", new Vec3d(.03, 0, 0), 1))) {
                serverTick(loaded);
                check(gravity(loaded).state().fieldPresence() == FieldPresence.UNKNOWN, "both publications cannot certify domain coverage");
                com.example.examplemod.gravity.ProviderFixture.secondCoverage(level, query -> complete);
                serverTick(loaded);
                check(gravity(loaded).state().fieldPresence() == FieldPresence.PRESENT, "all complete providers authorize PRESENT");
                check(gravity(loaded).state().assignmentRevision() == durable, "same complete composition retains durable revision");
                check(gravity(loaded).state().assignmentSyncRevision() > syncBefore, "UNKNOWN to PRESENT advances live revision");
                long liveRevision = gravity(loaded).state().assignmentSyncRevision();
                serverTick(loaded);
                check(gravity(loaded).state().assignmentSyncRevision() == liveRevision, "identical live result is idempotent");

                // Coverage changes without any publication revision change, and varies by query.
                long beforeLoss = runtime.publicationRevision();
                com.example.examplemod.gravity.ProviderFixture.secondCoverage(level,
                        query -> query.position().x() < 100 ? incomplete : complete);
                check(cc.sighs.gravityengine.api.GravityEngineApi.sample(level, new Vec3d(101, 300, 8)).coverage() == complete,
                        "coverage is query-local, not Level readiness");
                serverTick(loaded);
                check(runtime.publicationRevision() == beforeLoss, "coverage loss needs no publication generation");
                check(gravity(loaded).state().fieldPresence() == FieldPresence.UNKNOWN, "runtime PRESENT loses authority on incomplete query");
                check(gravity(loaded).state().assignmentRevision() == durable, "coverage loss preserves durable tuple");
                check(runtime.publicationRevision() > publicationRevision, "second provider publication is registered independently");
                try (var override = cc.sighs.gravityengine.api.GravityEngineApi.publish(level, second,
                        override("coverage_override", new Vec3d(0, 0, .04), 1))) {
                    serverTick(loaded);
                    check(seed.sameSyncData(gravity(loaded).state().assignedState()),
                            "partial OVERRIDE cannot replace authoritative additive seed");
                    com.example.examplemod.gravity.ProviderFixture.secondCoverage(level, query -> complete);
                    serverTick(loaded);
                    check(gravity(loaded).state().assignedState().down().equals(new Vec3d(0, 0, 1)),
                            "complete late OVERRIDE excludes all additive domains");

                    var early = player(level, new GameProfile(UUID.randomUUID(), "AlreadyComplete"));
                    early.load(disk);
                    early.setPos(8, 300, 8);
                    serverTick(early);
                    check(gravity(early).state().fieldPresence() == FieldPresence.PRESENT,
                            "publication before load reconciles on the first complete query");
                    check(gravity(early).state().assignedState().sameSyncData(gravity(loaded).state().assignedState()),
                            "early publication replaces old durable seed with full composition");
                    early.discard();
                    com.example.examplemod.gravity.ProviderFixture.secondCoverage(level, query -> incomplete);
                }
            }
        }
        serverTick(loaded);
        check(gravity(loaded).state().fieldPresence() == FieldPresence.UNKNOWN, "removing all partial sources does not invent absence");
        com.example.examplemod.gravity.ProviderFixture.secondCoverage(level, query -> complete);
        serverTick(loaded);
        check(gravity(loaded).state().fieldPresence() == FieldPresence.ABSENT, "complete empty result authorizes ABSENT");
        com.example.examplemod.gravity.ProviderFixture.secondCoverage(level, query -> incomplete);
        serverTick(loaded);
        check(gravity(loaded).state().fieldPresence() == FieldPresence.UNKNOWN, "runtime ABSENT loses authority too");
        com.example.examplemod.gravity.ProviderFixture.secondCoverage(level, query -> complete);
        serverTick(loaded);
        check(gravity(loaded).state().fieldPresence() == FieldPresence.ABSENT, "absence recovers through complete query");
        loaded.discard();

        boolean lateRejected = false;
        try { cc.sighs.gravityengine.api.GravityEngineApi.registerFieldProvider(provider, ignored -> query ->
                new cc.sighs.gravityengine.api.field.GravityFieldProviderResult(complete, java.util.List.of())); }
        catch (IllegalStateException expected) { lateRejected = true; }
        check(lateRejected, "provider set cannot grow after Level creation");
    }

    private static net.minecraft.nbt.CompoundTag gravityRecord(
            net.minecraft.nbt.CompoundTag entityTag
    ) {
        return entityTag.getCompound(ATTACHMENTS_KEY)
                .getCompound(GRAVITY_ATTACHMENT_KEY);
    }

    private static cc.sighs.gravityengine.api.GravityFieldDefinition field(
            String name,
            Vec3d acceleration,
            cc.sighs.gravityengine.api.field.GravityFieldCompositionMode mode,
            long revision
    ) {
        return cc.sighs.gravityengine.api.GravityFieldDefinition.named(
                net.minecraft.resources.ResourceLocation.parse(
                        "gravityengine_control_tests:" + name),
                query -> new cc.sighs.gravityengine.api.field.GravityFieldSample(
                        acceleration),
                cc.sighs.gravityengine.api.field.GravityFields.infiniteInfluence(),
                mode,
                revision);
    }

    private static cc.sighs.gravityengine.api.GravityFieldDefinition override(
            String name,
            Vec3d acceleration,
            long revision
    ) {
        return field(
                name,
                acceleration,
                cc.sighs.gravityengine.api.field.GravityFieldCompositionMode.OVERRIDE,
                revision);
    }

    private static cc.sighs.gravityengine.api.GravityFieldDefinition additive(
            String name,
            Vec3d acceleration,
            long revision
    ) {
        return field(
                name,
                acceleration,
                cc.sighs.gravityengine.api.field.GravityFieldCompositionMode.ADDITIVE,
                revision);
    }

    /**
     * DIRECT and FIELD durable state round-trip through the NeoForge
     * serializable attachment, and the legacy root NBT entry is never a
     * second writer.
     */
    private static void attachmentPersistence(ServerLevel level) {
        var source = player(level, new GameProfile(UUID.randomUUID(), "AttachmentDirect"));
        GravityApplicationCoordinator.applyDirectAssignment(source, DIRECT);
        var disk = source.saveWithoutId(new net.minecraft.nbt.CompoundTag());
        var record = gravityRecord(disk);
        check(!record.isEmpty(), "DIRECT durable gravity is written to the attachment");
        check(
                !disk.contains("GravityEngineGravity"),
                "legacy root NBT is not a second persistence writer");
        check(
                record.getInt("FormatVersion")
                        == cc.sighs.gravityengine.gravity.persistence
                        .GravityPersistenceCodec.CURRENT_FORMAT_VERSION,
                "current attachment format");
        check(
                record.getInt("Authority")
                        == GravityAuthorityMode.DIRECT.networkId(),
                "DIRECT authority encoded");
        long revision = gravity(source).state().assignmentRevision();

        var loaded = player(level, new GameProfile(UUID.randomUUID(), "AttachmentDirectLoad"));
        loaded.load(disk);
        check(
                gravity(loaded).state().assignedAuthority()
                        == GravityAuthorityMode.DIRECT,
                "DIRECT authority restored from the attachment");
        check(
                DIRECT.sameSyncData(gravity(loaded).state().assignedState()),
                "DIRECT value restored from the attachment");
        check(
                gravity(loaded).state().assignmentRevision() == revision,
                "durable revision restored from the attachment");
        check(
                gravity(loaded).state().applicationBootstrapPending(),
                "attachment load marks the application bootstrap pending");

        source.discard();
        loaded.discard();
    }





    /**
     * A numerically default FIELD resultant still owns durable provenance, and
     * multiple producers/revisions compose deterministically.
     */
    private static void fieldCompositionOrder(ServerLevel level) {
        cc.sighs.gravityengine.gravity.field.GravityFieldRuntime.remove(level);
        var a = cc.sighs.gravityengine.api.GravityEngineApi.publish(
                level, com.example.examplemod.gravity.ProviderFixture.ID, additive("compose_a", new Vec3d(.01, 0, 0), 1));
        var b = cc.sighs.gravityengine.api.GravityEngineApi.publish(
                level, com.example.examplemod.gravity.ProviderFixture.ID, additive("compose_b", new Vec3d(0, .01, 0), 1));
        var first = cc.sighs.gravityengine.api.GravityEngineApi.sample(
                level, new Vec3d(8, 300, 8));
        check(first.fieldPresent(), "two ADDITIVE producers compose as present");
        check(
                Math.abs(first.accelerationMagnitude() - Math.sqrt(2e-4)) < 1e-9,
                "ADDITIVE producers sum deterministically");

        // Republish in the opposite order with higher revisions.
        var b2 = cc.sighs.gravityengine.api.GravityEngineApi.publish(
                level, com.example.examplemod.gravity.ProviderFixture.ID, additive("compose_b", new Vec3d(0, .01, 0), 2));
        var a2 = cc.sighs.gravityengine.api.GravityEngineApi.publish(
                level, com.example.examplemod.gravity.ProviderFixture.ID, additive("compose_a", new Vec3d(.01, 0, 0), 2));
        var second = cc.sighs.gravityengine.api.GravityEngineApi.sample(
                level, new Vec3d(8, 300, 8));
        check(
                Math.abs(
                        first.accelerationMagnitude()
                                - second.accelerationMagnitude()) < 1e-12,
                "republish order does not change the composed result");

        // A later, higher-priority OVERRIDE excludes ADDITIVE contributions.
        var o = cc.sighs.gravityengine.api.GravityEngineApi.publish(
                level, com.example.examplemod.gravity.ProviderFixture.ID, override("compose_override", new Vec3d(0, 0, .5), 1));
        var overridden = cc.sighs.gravityengine.api.GravityEngineApi.sample(
                level, new Vec3d(8, 300, 8));
        check(
                Math.abs(overridden.accelerationMagnitude() - .5) < 1e-12,
                "an active OVERRIDE excludes ADDITIVE contributions");
        o.close();

        // A numerically default active contribution keeps FIELD provenance.
        var zero = cc.sighs.gravityengine.api.GravityEngineApi.publish(
                level, com.example.examplemod.gravity.ProviderFixture.ID, override("compose_default", new Vec3d(0, -0.08, 0), 1));
        var actor = player(level, new GameProfile(UUID.randomUUID(), "DefaultValued"));
        GravityApplicationCoordinator.refreshLoadedInputs(actor);
        var state = gravity(actor).state();
        check(
                state.fieldPresence() == FieldPresence.PRESENT,
                "a default-valued active contribution is PRESENT");
        check(
                state.assignedState().isDefault(),
                "the composed resultant is numerically default");
        check(
                state.durableFieldContinuity()
                        == cc.sighs.gravityengine.gravity.model.GravityEntityState
                        .FieldContinuity.SEED,
                "numeric equality with default gravity keeps FIELD provenance");
        check(
                gravityRecord(actor.saveWithoutId(new net.minecraft.nbt.CompoundTag()))
                        .getBoolean("FieldContinuity"),
                "default-valued FIELD provenance is persisted");

        zero.close();
        a.close();
        b.close();
        a2.close();
        b2.close();
        actor.discard();
        cc.sighs.gravityengine.gravity.field.GravityFieldRuntime.remove(level);
    }



    /**
     * A bounded one-way legacy import, with the new attachment always taking
     * precedence, and malformed/unsupported data staying diagnosable.
     */
    private static void legacyImportAndPrecedence(ServerLevel level) {
        cc.sighs.gravityengine.gravity.field.GravityFieldRuntime.remove(level);
        var legacyRoot = new net.minecraft.nbt.CompoundTag();
        legacyRoot.putInt(
                "FormatVersion",
                cc.sighs.gravityengine.gravity.persistence
                        .GravityPersistenceCodec.CURRENT_FORMAT_VERSION);
        legacyRoot.putDouble("DownX", 1);
        legacyRoot.putDouble("DownY", 0);
        legacyRoot.putDouble("DownZ", 0);
        legacyRoot.putDouble("Strength", .02);
        legacyRoot.putLong("AssignmentRev", 7);
        legacyRoot.putInt("Authority", GravityAuthorityMode.DIRECT.networkId());
        legacyRoot.putBoolean("FieldContinuity", false);

        var legacyTag = new net.minecraft.nbt.CompoundTag();
        legacyTag.put("GravityEngineGravity", legacyRoot.copy());

        var migrated = player(level, new GameProfile(UUID.randomUUID(), "LegacyImported"));
        migrated.load(legacyTag);
        check(
                gravity(migrated).state().assignedAuthority()
                        == GravityAuthorityMode.DIRECT,
                "legacy root NBT is imported exactly once");
        check(
                gravity(migrated).state().assignmentRevision() == 7,
                "legacy durable revision is imported");
        var migratedDisk = migrated.saveWithoutId(new net.minecraft.nbt.CompoundTag());
        check(
                gravityRecord(migratedDisk).getLong("AssignmentRev") == 7,
                "the migrated record is written by the attachment serializer");

        // A stale legacy tag must never overwrite a valid new attachment.
        var stale = legacyTag.copy();
        stale.getCompound("GravityEngineGravity").putDouble("Strength", .99);
        var authoritative = player(level, new GameProfile(UUID.randomUUID(), "AttachmentWins"));
        GravityApplicationCoordinator.applyDirectAssignment(authoritative, DIRECT);
        var authoritativeDisk =
                authoritative.saveWithoutId(new net.minecraft.nbt.CompoundTag());
        authoritativeDisk.getCompound(ATTACHMENTS_KEY).put(
                GRAVITY_ATTACHMENT_KEY,
                gravityRecord(authoritativeDisk).copy());
        authoritativeDisk.put(
                "GravityEngineGravity",
                stale.getCompound("GravityEngineGravity").copy());
        var reloaded = player(level, new GameProfile(UUID.randomUUID(), "AttachmentWinsReload"));
        reloaded.load(authoritativeDisk);
        check(
                DIRECT.sameSyncData(gravity(reloaded).state().assignedState()),
                "the new attachment wins over a stale legacy root tag");

        // Malformed and unsupported records stay diagnosable and never become
        // a successful default assignment.
        var malformedRoot = legacyRoot.copy();
        malformedRoot.putDouble("DownX", Double.NaN);
        var malformedTag = new net.minecraft.nbt.CompoundTag();
        malformedTag.put("GravityEngineGravity", malformedRoot);
        var malformed = player(level, new GameProfile(UUID.randomUUID(), "LegacyMalformed"));
        malformed.load(malformedTag);
        check(
                malformed.getExistingDataOrNull(
                        cc.sighs.gravityengine.GravityEngineAttachments.GRAVITY_PERSISTENCE) == null,
                "malformed legacy data is not silently promoted to a new record");

        var futureRoot = legacyRoot.copy();
        futureRoot.putInt("FormatVersion", 99);
        var futureTag = new net.minecraft.nbt.CompoundTag();
        futureTag.put("GravityEngineGravity", futureRoot);
        var future = player(level, new GameProfile(UUID.randomUUID(), "LegacyFuture"));
        future.load(futureTag);
        check(
                future.getExistingDataOrNull(
                        cc.sighs.gravityengine.GravityEngineAttachments.GRAVITY_PERSISTENCE) == null,
                "unsupported future legacy data is not silently promoted");

        migrated.discard();
        authoritative.discard();
        reloaded.discard();
        malformed.discard();
        future.discard();
    }

    /**
     * Fresh state is emitted from the native lifecycle boundaries; no synthetic
     * lifetime packet and no rendering callback participates.
     */
    private static void nativeLifecycleFreshSync(ServerLevel level) {
        cc.sighs.gravityengine.gravity.field.GravityFieldRuntime.remove(level);
        var tracked = player(level, new GameProfile(UUID.randomUUID(), "LifecycleTarget"));
        GravityApplicationCoordinator.applyDirectAssignment(tracked, DIRECT);
        var observer = player(level, new GameProfile(UUID.randomUUID(), "LifecycleObserver"));
        SENT.get(observer).clear();

        // StartTracking: NeoForge queues the add-entity pairing bundle before
        // this event, so the client already has the entity object.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                new net.neoforged.neoforge.event.entity.player.PlayerEvent.StartTracking(
                        observer, tracked));
        check(
                gravityPackets(observer) == 1,
                "initial tracking receives exactly one fresh gravity state");

        // Login: the client login packet precedes the logged-in hook.
        SENT.get(tracked).clear();
        check(
                PlayerPhysicalLoadBootstrap.bootstrapLoadedPlayer(tracked),
                "login bootstrap succeeds");
        check(
                gravityPackets(tracked) >= 1,
                "player login receives fresh self state");

        // Dimension change: the same player object is resynchronized.
        SENT.get(tracked).clear();
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                new net.neoforged.neoforge.event.entity.player.PlayerEvent
                        .PlayerChangedDimensionEvent(
                        tracked,
                        net.minecraft.world.level.Level.OVERWORLD,
                        net.minecraft.world.level.Level.OVERWORLD));
        check(
                gravityPackets(tracked) >= 1,
                "dimension change receives fresh state");

        // A removed entity is never synchronized.
        SENT.get(observer).clear();
        tracked.discard();
        cc.sighs.gravityengine.network.GravitySyncService.syncEntityToPlayer(
                observer, tracked);
        check(
                gravityPackets(observer) == 0,
                "a removed entity never publishes a stale snapshot");

        // Ordinary revision admission rejects an older snapshot.
        var replacementState = new cc.sighs.gravityengine.gravity.model.GravityEntityState();
        check(
                replacementState.acceptRemoteAssignment(
                        DIRECT,
                        GravityAuthorityMode.DIRECT,
                        false,
                        5L)
                        == cc.sighs.gravityengine.gravity.model.GravityEntityState
                        .SnapshotAcceptance.ACCEPTED,
                "first snapshot for a fresh object is accepted");
        check(
                replacementState.acceptRemoteAssignment(
                        DIRECT,
                        GravityAuthorityMode.DIRECT,
                        false,
                        4L)
                        == cc.sighs.gravityengine.gravity.model.GravityEntityState
                        .SnapshotAcceptance.STALE,
                "an older snapshot cannot be applied to the replacement");

        observer.discard();
        SENT.clear();
    }

    private static long gravityPackets(ServerPlayer player) {
        return SENT.get(player).stream().filter(packet -> packet instanceof
                net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom
                && custom.payload() instanceof cc.sighs.gravityengine.network.ClientboundPlayerBodyCommitPayload).count();
    }

    /**
     * One complete server-side player tick including the production
     * connection-tick body boundary, which is the only owner allowed to change
     * a connected player's body.
     */
    private static void serverTick(ServerPlayer player) {
        player.baseTick();
        PlayerBodyHandoff.afterConnectionTick(player, false);
    }

    private static ServerPlayer player(ServerLevel level, GameProfile profile) {
        var player = new ServerPlayer(level.getServer(), level, profile,
                net.minecraft.server.level.ClientInformation.createDefault());
        SENT.put(player, new java.util.ArrayList<>());
        var connection = new Connection(PacketFlow.SERVERBOUND) {
            @Override public void send(Packet<?> packet) { SENT.get(player).add(packet); }
            @Override public void send(Packet<?> packet, PacketSendListener listener) {}
        };
        player.connection = new ServerGamePacketListenerImpl(level.getServer(), connection, player,
                CommonListenerCookie.createInitial(profile, false)) {
            @Override public void send(Packet<?> packet) { SENT.get(player).add(packet); }
        };
        player.setPos(8, 300, 8);
        return player;
    }

    private static void initialize(ServerPlayer player) {
        GravityApplicationCoordinator.applyDirectAssignment(player, DIRECT);
        var component = BodyAttitudeRuntime.Access.component(player);
        /*
         * This fixture actor is explicitly FREE_ATTITUDE, i.e. KINEMATIC
         * ownership. It therefore installs q with no angular dynamics at all;
         * a durable momentum seed only exists for a dynamic attitude owner.
         */
        var state = BodyAttitudeState.kinematic(
                BODY,
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
        boolean commitFailed = false;
        try { bootstrapThroughHandoff(loaded); }
        catch (ArithmeticException expected) { commitFailed = true; }
        check(commitFailed, "unexpected commit overflow propagates after rollback");
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
                actor.state().hasAngularDynamics(),
                "receiving Elytra contract installs "
                        + "dynamic ownership before publication"
        );

        check(
                actor.state()
                        .angularMomentum()
                        .orElseThrow()
                        .angularMomentumWorld()
                        .equals(Vec3d.ZERO),
                "kinematic seed enters Elytra with "
                        + "explicit zero angular momentum"
        );

        check(
                actor.state()
                        .effectiveInertia()
                        .orElseThrow()
                        .equals(
                                BodyAttitudeRuntime.Config
                                        .server()
                                        .simulation()
                                        .elytraEffectiveAngularInertia()
                        ),
                "receiving config owns restored Elytra inertia"
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
        cc.sighs.gravityengine.gravity.field.GravityFieldRuntime.get(level).publish(
                com.example.examplemod.gravity.ProviderFixture.ID,
                new cc.sighs.gravityengine.gravity.field.GravityFieldInstance(id,
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
                    if (tagged) {
                        GravityEntityAccess.cast(source)
                                .gravityengine$gravityComponent()
                                .state()
                                .setAssigned(DIRECT, authority, false);
                        /*
                         * NeoForge persists only attachment instances present
                         * on the holder, so the first durable mutation
                         * materializes the slot.
                         */
                        cc.sighs.gravityengine.gravity.persistence
                                .GravityPersistence.materialize(source);
                    }
                    var disk = source.saveWithoutId(new net.minecraft.nbt.CompoundTag());
                    check(
                            !gravityRecord(disk).isEmpty() == tagged,
                            "tagged/untagged ordinary attachment save");
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
        check(seed.angularMomentumWorld().isEmpty(),
                "kinematic seed persists no fabricated momentum");
    }

    private static cc.sighs.gravityengine.gravity.component.EntityGravityComponent gravity(ServerPlayer player) {
        return GravityEntityAccess.cast(player).gravityengine$gravityComponent();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("Persistence lifecycle: " + message);
    }
}
