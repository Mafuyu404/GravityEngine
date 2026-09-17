package cc.sighs.gravityengine.gravity.model;

import cc.sighs.gravityengine.api.FieldPresence;
import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityState;
import org.junit.jupiter.api.Test;

import static cc.sighs.gravityengine.api.field.FieldCoverage.*;
import static cc.sighs.gravityengine.gravity.model.GravityEntityState.*;
import static org.junit.jupiter.api.Assertions.*;

class GravityEntityStateRevisionTest {
    private static final GravityState SEED = new GravityState(new Vec3d(1, 0, 0), .08);
    private static final GravityState OTHER = new GravityState(new Vec3d(0, 0, 1), .04);

    private static GravityEntityState loaded(long revision) {
        var state = new GravityEntityState();
        state.restoreDurableAssignment(new StoredAssignment(SEED, GravityAuthorityMode.FIELD,
                revision, FieldContinuity.SEED));
        return state;
    }

    private static StoredAssignment durable(GravityEntityState state) {
        return new StoredAssignment(state.assignedState(), state.assignedAuthority(),
                state.assignmentRevision(), state.durableFieldContinuity());
    }

    private static void unchangedOnExhaustion(GravityEntityState state, Runnable mutation) {
        var before = durable(state);
        var evidence = state.fieldPresence();
        var sync = state.assignmentSyncRevision();
        var application = state.committedApplication();
        var pending = state.pendingApplication();
        assertThrows(ArithmeticException.class, mutation::run);
        assertEquals(before, durable(state), "durable tuple remains valid and saveable");
        assertEquals(evidence, state.fieldPresence());
        assertEquals(sync, state.assignmentSyncRevision());
        assertSame(application, state.committedApplication());
        assertSame(pending, state.pendingApplication());
    }

    @Test void persistedRevisionRangeIsValidatedBeforeRestore() {
        for (long revision : new long[]{-1, Long.MIN_VALUE, Long.MAX_VALUE}) {
            assertTrue(decodeStoredAssignment(1, 0, 0, .08, revision,
                    GravityAuthorityMode.FIELD.networkId(), true).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> new StoredAssignment(
                    SEED, GravityAuthorityMode.FIELD, revision, FieldContinuity.SEED));
        }
        for (long revision : new long[]{0, 10, MAX_DURABLE_ASSIGNMENT_REVISION}) {
            assertEquals(revision, decodeStoredAssignment(1, 0, 0, .08, revision,
                    GravityAuthorityMode.FIELD.networkId(), true).orElseThrow().revision());
        }
    }

    @Test void largestDurableSeedCanResolveEvidenceWithoutChangingDurableTruth() {
        var state = loaded(MAX_DURABLE_ASSIGNMENT_REVISION);
        var before = durable(state);
        assertTrue(state.commitFieldEvaluation(SEED, true, COMPLETE));
        assertEquals(FieldPresence.PRESENT, state.fieldPresence());
        assertEquals(before, durable(state));
        assertEquals(Long.MAX_VALUE, state.assignmentSyncRevision());
        assertFalse(state.commitFieldEvaluation(SEED, true, COMPLETE));
        unchangedOnExhaustion(state, () -> state.commitFieldEvaluation(OTHER, true, COMPLETE));
        unchangedOnExhaustion(state, () -> state.commitFieldEvaluation(OTHER, true, INCOMPLETE));
    }

    @Test void durableExhaustionRejectsBeforeChangingAssignmentAuthorityOrProvenance() {
        var state = loaded(MAX_DURABLE_ASSIGNMENT_REVISION);
        unchangedOnExhaustion(state, () -> state.setAssigned(OTHER, GravityAuthorityMode.DIRECT, false));
        unchangedOnExhaustion(state, () -> state.commitFieldEvaluation(GravityState.DEFAULT, false, COMPLETE));
    }

    @Test void lastDurableIncrementSucceedsAndRemainsReloadable() {
        var state = loaded(MAX_DURABLE_ASSIGNMENT_REVISION - 1);
        assertTrue(state.setAssigned(OTHER, GravityAuthorityMode.DIRECT, false));
        assertEquals(MAX_DURABLE_ASSIGNMENT_REVISION, state.assignmentRevision());
        assertEquals(MAX_DURABLE_ASSIGNMENT_REVISION, state.assignmentSyncRevision());
        var reloaded = new GravityEntityState();
        reloaded.restoreDurableAssignment(durable(state));
        assertEquals(durable(state), durable(reloaded));
        unchangedOnExhaustion(state, () -> state.setAssigned(SEED, GravityAuthorityMode.FIELD, true));
    }

    @Test void syncExhaustionDoesNotPartiallyCommitAnAvailableDurableIncrement() {
        var state = loaded(10);
        state.acceptRemoteAssignment(SEED, GravityAuthorityMode.FIELD, FieldPresence.PRESENT, Long.MAX_VALUE);
        unchangedOnExhaustion(state, () -> state.setAssigned(OTHER, GravityAuthorityMode.DIRECT, false));
        unchangedOnExhaustion(state, state::invalidateFieldEvidence);
        assertFalse(state.setAssigned(SEED, GravityAuthorityMode.FIELD, true));
    }

    @Test void evidenceOnlyCompletionFailsAtomicallyWhenSyncIsExhausted() {
        var state = loaded(Long.MAX_VALUE - 2);
        state.commitFieldEvaluation(SEED, true, COMPLETE);
        state.invalidateFieldEvidence();
        assertEquals(Long.MAX_VALUE, state.assignmentSyncRevision());
        assertEquals(FieldContinuity.SEED, state.durableFieldContinuity());
        unchangedOnExhaustion(state, () -> state.commitFieldEvaluation(SEED, true, COMPLETE));
        assertFalse(state.invalidateFieldEvidence());
    }

    @Test void replicaAcceptsEvidenceOnlyUpdatesAndRejectsOldOrConflictingTruthAtTheLimit() {
        var server = loaded(MAX_DURABLE_ASSIGNMENT_REVISION);
        var replica = new GravityEntityState();
        assertEquals(SnapshotAcceptance.ACCEPTED, replica.acceptRemoteAssignment(SEED,
                GravityAuthorityMode.FIELD, FieldPresence.UNKNOWN, server.assignmentSyncRevision()));
        server.commitFieldEvaluation(SEED, true, COMPLETE);
        assertEquals(SnapshotAcceptance.ACCEPTED, replica.acceptRemoteAssignment(SEED,
                GravityAuthorityMode.FIELD, FieldPresence.PRESENT, server.assignmentSyncRevision()));
        assertEquals(SnapshotAcceptance.STALE, replica.acceptRemoteAssignment(SEED,
                GravityAuthorityMode.FIELD, FieldPresence.UNKNOWN, MAX_DURABLE_ASSIGNMENT_REVISION));
        assertEquals(SnapshotAcceptance.STALE, replica.acceptRemoteAssignment(SEED,
                GravityAuthorityMode.FIELD, FieldPresence.PRESENT, Long.MAX_VALUE));
        assertEquals(SnapshotAcceptance.CONFLICT, replica.acceptRemoteAssignment(OTHER,
                GravityAuthorityMode.FIELD, FieldPresence.PRESENT, Long.MAX_VALUE));
        assertEquals(SEED, replica.assignedState());
        assertEquals(FieldPresence.PRESENT, replica.fieldPresence());
        assertEquals(0, replica.assignmentRevision(), "network chronology is not durable chronology");
        var freshReplica = new GravityEntityState();
        assertEquals(SnapshotAcceptance.ACCEPTED, freshReplica.acceptRemoteAssignment(OTHER,
                GravityAuthorityMode.DIRECT, FieldPresence.ABSENT, 1));
    }
}
