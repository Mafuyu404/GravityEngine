package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.api.*;
import cc.sighs.gravityengine.api.field.*;
import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.field.GravityFieldRuntime;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import com.example.examplemod.gravity.ProviderFixture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Executed in the transformed server, with failures scoped to the isolated Nether Level. */
final class PublicationLifecycleChecks {
    private static final ResourceLocation FIELD = ResourceLocation.fromNamespaceAndPath("lifecycle_checks", "field");
    private static final Vec3d POINT = new Vec3d(0, 1000, 0);
    private static int assertions;

    static void run(ServerLevel overworld) {
        var level = overworld.getServer().getLevel(Level.NETHER);
        check(level != null, "isolated Level exists");
        GravityFieldRuntime.remove(level);
        var probe = new ProviderFixture.LifecycleProbe();
        ProviderFixture.probe(level, probe);
        Entity entity = new Pig(EntityType.PIG, level);
        try {
            GravityFieldRuntime.remove(level); // entity construction may use native engine hooks
            snapshotDoesNotWrite(entity, probe);
            aba(level);
            frozenSnapshot(level, entity, probe);
            retryClose(level, probe);
            failedReplacement(level);
            GravityFieldRuntime.remove(level);
            factoryFailures(level, entity, probe);
            openingFailuresAndRelease(level, entity, probe);
            unloadReentry(level, probe);
            snapshotDoesNotWrite(entity, probe);
            coverage(level, probe);
        } finally {
            probe.factory = id -> {}; probe.opening = id -> {};
            probe.evaluation = id -> {}; probe.closing = id -> {};
            GravityFieldRuntime.remove(level);
            ProviderFixture.probe(level, null);
            entity.discard();
        }
        System.out.println("PUBLICATION_LIFECYCLE_CHECKS_PASSED assertions=" + assertions);
    }

    private static GravityFieldDefinition definition(long revision) {
        return GravityFieldDefinition.named(FIELD, GravityFields.zeroGravity(),
                GravityFields.infiniteInfluence(), GravityFieldCompositionMode.OVERRIDE, revision);
    }
    private static FieldPublication publish(ServerLevel level, ResourceLocation provider, long revision) {
        return GravityEngineApi.publish(level, provider, definition(revision));
    }
    private static void aba(ServerLevel level) {
        for (var next : List.of(ProviderFixture.ID, ProviderFixture.SECOND_ID)) {
            var old = publish(level, ProviderFixture.ID, 1);
            var newer = publish(level, ProviderFixture.ID, 2);
            check(old.accepted() && !old.isClosed(), "replacement does not locally close old handle");
            newer.close();
            var recreated = publish(level, next, 1);
            long generation = GravityFieldRuntime.get(level).publicationRevision();
            old.close(); old.close();
            check(GravityEngineApi.sample(level, POINT).contributions().size() == 1, "ABA publication survives old handle");
            check(GravityFieldRuntime.get(level).publicationRevision() == generation, "stale close does not advance generation");
            recreated.close();
        }
        var old = publish(level, ProviderFixture.ID, 1);
        GravityFieldRuntime.remove(level);
        var recreated = publish(level, ProviderFixture.ID, 1);
        old.close();
        check(GravityEngineApi.sample(level, POINT).contributions().size() == 1, "new runtime survives old handle");
        recreated.close();
    }

    private static void frozenSnapshot(ServerLevel level, Entity entity, ProviderFixture.LifecycleProbe probe) {
        var component = ((GravityEntityAccess) entity).gravityengine$gravityComponent();
        var operation = component.operationState();
        var runtime = GravityFieldRuntime.get(level);
        var query = new GravityFieldQuery(POINT, Vec3d.ZERO, 0, 0);
        var gravity = new cc.sighs.gravityengine.gravity.GravityState(new Vec3d(1, 0, 0), .123);
        var sample = cc.sighs.gravityengine.gravity.model.GravitySample.fromState(gravity, POINT);
        var frame = cc.sighs.gravityengine.gravity.GravityFrame.fromState(gravity, POINT);
        var evaluation = new cc.sighs.gravityengine.gravity.acceleration.GravityEvaluationSnapshot(
                cc.sighs.gravityengine.gravity.acceleration.GravityEvaluationContexts.capture(component.state(), runtime),
                query, sample, sample, frame, 0, 0);
        snapshotDoesNotWrite(entity, probe); // OPEN, no usable evaluation
        try (var scope = operation.openMove(frame, 0, cc.sighs.gravityengine.gravity.model.GravityOperationType.MOVE)) {
            operation.installOperationEvaluation(evaluation);
            try (var handle = publish(level, ProviderFixture.ID, 1)) {
                snapshotDoesNotWrite(entity, probe);
                check(GravityEngineApi.entityGravity(entity).orElseThrow().effectiveAcceleration().equals(sample.accelerationVector()),
                        "publication change cannot replace frozen movement input");
                check(operation.activeOperationEvaluation().orElseThrow() == evaluation, "getter retains operation evaluation identity");
            }
        }
    }

    private static void retryClose(ServerLevel level, ProviderFixture.LifecycleProbe probe) {
        var handle = publish(level, ProviderFixture.ID, 1);
        long generation = GravityFieldRuntime.get(level).publicationRevision();
        var failure = new AtomicReference<Throwable>();
        var worker = new Thread(() -> {
            try { handle.close(); } catch (Throwable e) { failure.set(e); }
        }, "publication-wrong-thread");
        worker.start();
        try { worker.join(5000); } catch (InterruptedException e) { throw new AssertionError(e); }
        check(!worker.isAlive() && failure.get() instanceof IllegalStateException, "wrong-thread close rejected");
        check(!handle.isClosed(), "wrong-thread rejection remains retryable");
        probe.evaluation = id -> {
            expect(IllegalStateException.class, handle::close);
            check(!handle.isClosed(), "evaluation rejection remains retryable");
            expect(IllegalStateException.class, () -> GravityEngineApi.sample(level, POINT));
            expect(IllegalStateException.class, () -> publish(level, id, 2));
        };
        check(GravityEngineApi.sample(level, POINT).contributions().size() == 1, "evaluation sees original publication");
        probe.evaluation = id -> {};
        check(GravityFieldRuntime.get(level).publicationRevision() == generation, "rejected closes preserve generation");
        handle.close();
        check(handle.isClosed() && GravityEngineApi.sample(level, POINT).contributions().isEmpty(), "legal retry releases");
        GravityFieldRuntime.remove(level);
        int factories = probe.factories;
        failure.set(null);
        worker = new Thread(() -> {
            try { publish(level, ProviderFixture.ID, 1); } catch (Throwable e) { failure.set(e); }
        });
        worker.start();
        try { worker.join(5000); } catch (InterruptedException e) { throw new AssertionError(e); }
        check(!worker.isAlive() && failure.get() instanceof IllegalStateException, "wrong-thread creation rejected");
        check(probe.factories == factories && GravityFieldRuntime.getIfPresent(level) == null, "thread check precedes factories");
    }

    private static void failedReplacement(ServerLevel level) {
        var handle = publish(level, ProviderFixture.ID, 1);
        long generation = GravityFieldRuntime.get(level).publicationRevision();
        var invalid = new GravityInfluenceVolume() {
            public boolean contains(Vec3d point) { return true; }
            public java.util.Optional<GravityFieldBounds> finiteBounds() {
                expect(IllegalStateException.class, handle::close);
                check(!handle.isClosed(), "preparation rejection keeps handle retryable");
                expect(IllegalStateException.class, () -> publish(level, ProviderFixture.SECOND_ID, 3));
                throw new IllegalArgumentException("bounds failure");
            }
        };
        expect(IllegalArgumentException.class, () -> GravityEngineApi.publish(level, ProviderFixture.ID,
                GravityFieldDefinition.named(FIELD, GravityFields.zeroGravity(), invalid, GravityFieldCompositionMode.OVERRIDE, 2)));
        check(GravityFieldRuntime.get(level).publicationRevision() == generation, "failed replacement generation unchanged");
        check(GravityEngineApi.sample(level, POINT).contributions().size() == 1, "failed replacement retains source");
        handle.close();
        var other = publish(level, ProviderFixture.SECOND_ID, 1);
        check(other.accepted(), "release also removes provider ownership");
        other.close();
    }

    private static void factoryFailures(ServerLevel level, Entity entity, ProviderFixture.LifecycleProbe probe) {
        for (int action = 0; action < 3; action++) {
            final int selected = action;
            int before = probe.closes;
            probe.factory = id -> {
                snapshotDoesNotWrite(entity, probe);
                check(GravityFieldRuntime.getIfPresent(level) == null, "initializing runtime hidden from snapshots");
                if (!id.equals(ProviderFixture.ID)) return; // ID sorts after SECOND_ID: one acquired session
                if (selected == 0) publish(level, id, 1);
                else if (selected == 1) GravityEngineApi.sample(level, POINT);
                else GravityEngineApi.samplePublications(level, id, new GravityFieldQuery(POINT, Vec3d.ZERO, 0, 0));
            };
            expect(IllegalStateException.class, () -> GravityEngineApi.sample(level, POINT));
            check(probe.closes == before + 1 && ProviderFixture.sessionCount(level) == 0, "factory failure cleans acquired session once");
            check(GravityFieldRuntime.getIfPresent(level) == null, "factory failure removes slot");
        }
        probe.factory = id -> {};
    }

    private static void openingFailuresAndRelease(ServerLevel level, Entity entity, ProviderFixture.LifecycleProbe probe) {
        var handles = new ArrayList<FieldPublication>();
        var closedOrder = new ArrayList<ResourceLocation>();
        var initialFailure = new IllegalArgumentException("opening failure");
        probe.opening = id -> {
            snapshotDoesNotWrite(entity, probe);
            check(ProviderFixture.sessionCount(level) == 2, "all sessions precede onOpen");
            expect(IllegalStateException.class, () -> GravityEngineApi.sample(level, POINT));
            expect(IllegalStateException.class, () -> GravityEngineApi.samplePublications(level, id,
                    new GravityFieldQuery(POINT, Vec3d.ZERO, 0, 0)));
            var other = id.equals(ProviderFixture.ID) ? ProviderFixture.SECOND_ID : ProviderFixture.ID;
            expect(IllegalStateException.class, () -> publish(level, other, 1));
            if (id.equals(ProviderFixture.ID)) throw initialFailure;
            var released = publish(level, id, 1);
            released.close();
            check(released.isClosed(), "onOpen handle releases exact opening runtime");
            var retained = publish(level, id, 1);
            check(retained.accepted(), "onOpen release really removed previous source");
            handles.add(retained);
        };
        probe.closing = id -> { closedOrder.add(id); throw new IllegalStateException("close " + id); };
        int before = probe.closes;
        try { GravityEngineApi.sample(level, POINT); throw new AssertionError("expected opening failure"); }
        catch (IllegalArgumentException e) {
            check(e == initialFailure && e.getSuppressed().length == 1
                    && e.getSuppressed()[0].getSuppressed().length == 1, "original failure preserves all cleanup failures");
        }
        check(probe.closes == before + 2, "all opening sessions closed once");
        check(closedOrder.equals(List.of(ProviderFixture.ID, ProviderFixture.SECOND_ID)), "reverse construction cleanup order");
        check(ProviderFixture.sessionCount(level) == 0 && GravityFieldRuntime.getIfPresent(level) == null, "opening rollback removes sessions");
        probe.opening = id -> {}; probe.closing = id -> {};
        check(GravityEngineApi.sample(level, POINT).contributions().isEmpty(), "opening rollback revokes all sources");
        for (var handle : handles) handle.close();
        GravityFieldRuntime.remove(level);
        // A successful opening publication is released by its receipt even though snapshot access was hidden.
        handles.clear();
        probe.opening = id -> { if (id.equals(ProviderFixture.ID)) handles.add(publish(level, id, 1)); };
        check(GravityEngineApi.sample(level, POINT).contributions().size() == 1, "successful onOpen publication visible after all callbacks");
        handles.getFirst().close();
        check(GravityEngineApi.sample(level, POINT).contributions().isEmpty(), "onOpen receipt remains bound after opening");
        probe.opening = id -> {};
    }

    private static void unloadReentry(ServerLevel level, ProviderFixture.LifecycleProbe probe) {
        var handle = publish(level, ProviderFixture.ID, 1);
        int factories = probe.factories;
        probe.closing = id -> {
            expect(IllegalStateException.class, () -> publish(level, id, 2));
            expect(IllegalStateException.class, () -> GravityEngineApi.sample(level, POINT));
            check(GravityFieldRuntime.getIfPresent(level) == null, "closing runtime hidden");
            handle.close();
            GravityFieldRuntime.remove(level); // idempotent nested cleanup
        };
        GravityFieldRuntime.remove(level);
        check(handle.isClosed() && probe.factories == factories, "cleanup cannot recreate sessions");
        probe.closing = id -> {};
    }

    private static void coverage(ServerLevel level, ProviderFixture.LifecycleProbe probe) {
        try (var handle = publish(level, ProviderFixture.ID, 1)) {
            long generation = GravityFieldRuntime.get(level).publicationRevision();
            for (var coverage : List.of(FieldCoverage.COMPLETE, FieldCoverage.INCOMPLETE, FieldCoverage.COMPLETE)) {
                ProviderFixture.coverage(level, coverage);
                check(GravityEngineApi.sample(level, POINT).coverage() == coverage, "query coverage independent of OPEN phase");
                check(GravityFieldRuntime.get(level).publicationRevision() == generation, "coverage is not publication generation");
            }
        }
    }

    static void snapshotDoesNotWrite(Entity entity, ProviderFixture.LifecycleProbe probe) {
        var state = ((GravityEntityAccess) entity).gravityengine$gravityComponent().state();
        long assignment = state.assignmentRevision(), sync = state.assignmentSyncRevision(), epoch = state.applicationEpoch();
        int runtimes = GravityFieldRuntime.loadedLevelCount(), factories = probe.factories,
                evaluations = probe.evaluations, closes = probe.closes;
        var presence = state.fieldPresence();
        var assigned = state.assignedState();
        var snapshot = GravityEngineApi.entityGravity(entity).orElseThrow();
        check(snapshot.fieldPresence() == presence, "snapshot retains existing evidence");
        check(state.assignedState().equals(assigned) && state.assignmentRevision() == assignment
                && state.assignmentSyncRevision() == sync && state.applicationEpoch() == epoch, "snapshot preserves entity owners");
        check(GravityFieldRuntime.loadedLevelCount() == runtimes && probe.factories == factories
                && probe.evaluations == evaluations && probe.closes == closes, "snapshot creates/calls/closes no sessions");
    }

    private static void expect(Class<? extends Throwable> type, Runnable call) {
        try { call.run(); } catch (Throwable failure) {
            check(type.isInstance(failure), "expected " + type + " got " + failure); return;
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
