package cc.sighs.gravityengine.gravity.model;

import cc.sighs.gravityengine.api.FieldPresence;
import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityState;
import org.junit.jupiter.api.Test;
import static cc.sighs.gravityengine.api.field.FieldCoverage.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural coverage for the single-uncertainty FIELD model.
 *
 * <p>The central regression is that a partially rebuilt producer set publishes
 * a non-empty sample. It must never replace the durable FIELD seed while the
 * destination evidence is still UNKNOWN.</p>
 */
class GravityEntityStateFieldReconciliationTest {
    private static final GravityState SAVED_RESULTANT =
            new GravityState(new Vec3d(1, 0, 0), .08);
    /** What a partial producer set (field A alone) would resolve to. */
    private static final GravityState PARTIAL =
            new GravityState(new Vec3d(0, 0, 1), .03);

    private static GravityEntityState loadedSeed() {
        var state = new GravityEntityState();
        state.restoreDurableAssignment(new GravityEntityState.StoredAssignment(
                SAVED_RESULTANT,
                GravityAuthorityMode.FIELD,
                17L,
                GravityEntityState.FieldContinuity.SEED));
        return state;
    }

    @Test
    void loadedFieldSeedStartsWithExactlyOneUnknownState() {
        var state = loadedSeed();

        assertEquals(FieldPresence.UNKNOWN, state.fieldPresence());
        assertTrue(state.fieldEvidenceUnknown());
        assertFalse(state.assignedFieldPresent());
        assertEquals(SAVED_RESULTANT, state.assignedState());
        assertEquals(17L, state.assignmentRevision());
        assertEquals(17L, state.assignmentSyncRevision());

        assertFalse(
                state.fieldReferenceInForce(),
                "a disk-restored FIELD seed is continuity only; "
                        + "it has no live destination reference before "
                        + "a FIELD application is committed"
        );
    }

    @Test
    void emptySampleWhileUnknownCannotReplaceTheSeed() {
        var state = loadedSeed();

        for (int i = 0; i < 64; i++) {
            assertFalse(state.commitFieldEvaluation(GravityState.DEFAULT, false, INCOMPLETE));
        }

        assertEquals(SAVED_RESULTANT, state.assignedState());
        assertEquals(17L, state.assignmentRevision());
        assertEquals(17L, state.assignmentSyncRevision());
        assertTrue(state.fieldEvidenceUnknown());
    }

    @Test
    void partialPositiveSampleWhileUnknownCannotReplaceTheSeed() {
        var state = loadedSeed();

        for (int i = 0; i < 64; i++) {
            assertFalse(
                    state.commitFieldEvaluation(PARTIAL, true, INCOMPLETE),
                    "a non-empty partial sample is still incomplete evidence"
            );
        }

        assertEquals(SAVED_RESULTANT, state.assignedState());
        assertEquals(17L, state.assignmentRevision());
        assertEquals(17L, state.assignmentSyncRevision());
        assertEquals(FieldPresence.UNKNOWN, state.fieldPresence());
        assertFalse(state.assignedFieldPresent());
    }

    @Test
    void authoritativeCompletionWithContributionCommitsPresent() {
        var state = loadedSeed();
        var composed = new GravityState(new Vec3d(0, -1, 0), .12);

        assertTrue(state.commitFieldEvaluation(composed, true, COMPLETE));
        assertFalse(state.commitFieldEvaluation(composed, true, COMPLETE));

        assertEquals(composed, state.assignedState());
        assertEquals(FieldPresence.PRESENT, state.fieldPresence());
        assertEquals(
                GravityEntityState.FieldContinuity.SEED,
                state.durableFieldContinuity()
        );
        assertEquals(18L, state.assignmentRevision());
    }

    @Test
    void authoritativeCompletionWithoutContributionCommitsAbsent() {
        var state = loadedSeed();

        assertTrue(
                state.commitFieldEvaluation(GravityState.DEFAULT, false, COMPLETE));
        assertFalse(
                state.commitFieldEvaluation(GravityState.DEFAULT, false, COMPLETE));

        assertEquals(GravityState.DEFAULT, state.assignedState());
        assertEquals(FieldPresence.ABSENT, state.fieldPresence());
        assertEquals(
                GravityEntityState.FieldContinuity.NONE,
                state.durableFieldContinuity()
        );
        assertEquals(18L, state.assignmentRevision());
    }

    @Test
    void ordinaryRuntimeRemovalAfterCompletionStaysAbsent() {
        var state = loadedSeed();
        state.commitFieldEvaluation(SAVED_RESULTANT, true, COMPLETE);
        long revision = state.assignmentRevision();

        assertTrue(
                state.commitFieldEvaluation(GravityState.DEFAULT, false, COMPLETE),
                "complete query removal is authoritative live evidence"
        );

        assertEquals(FieldPresence.ABSENT, state.fieldPresence());
        assertFalse(state.fieldEvidenceUnknown());
        assertEquals(revision + 1, state.assignmentRevision());
    }

    @Test
    void directAuthorityDerivesNotApplicableInsteadOfStoringIt() {
        var state = loadedSeed();

        state.setAssigned(
                new GravityState(new Vec3d(0, -1, 0), .04),
                GravityAuthorityMode.DIRECT,
                false);

        assertFalse(state.fieldEvidenceUnknown());
        assertEquals(FieldPresence.ABSENT, state.fieldPresence());
        state.invalidateFieldEvidence();
        assertFalse(
                state.fieldEvidenceUnknown(),
                "FIELD reconciliation is never opened under DIRECT authority"
        );
        assertFalse(state.commitFieldEvaluation(GravityState.DEFAULT, false, INCOMPLETE));
    }

    @Test
    void defaultValuedPresentFieldKeepsItsDurableProvenance() {
        var live = new GravityEntityState();
        assertTrue(live.setAssigned(
                GravityState.DEFAULT,
                GravityAuthorityMode.FIELD,
                true));

        assertEquals(FieldPresence.PRESENT, live.fieldPresence());
        assertEquals(
                GravityEntityState.FieldContinuity.SEED,
                live.durableFieldContinuity(),
                "numeric equality with default gravity is not FIELD absence"
        );

        var reloaded = new GravityEntityState();
        reloaded.restoreDurableAssignment(new GravityEntityState.StoredAssignment(
                live.assignedState(),
                live.assignedAuthority(),
                live.assignmentRevision(),
                live.durableFieldContinuity()));

        assertEquals(GravityState.DEFAULT, reloaded.assignedState());
        assertEquals(FieldPresence.UNKNOWN, reloaded.fieldPresence());
    }

    @Test
    void confirmedAbsenceLosesEvidenceWhenQueryBecomesIncomplete() {
        var state = new GravityEntityState();
        state.commitFieldEvaluation(GravityState.DEFAULT, false, COMPLETE);
        long durable = state.assignmentRevision();
        long sync = state.assignmentSyncRevision();
        assertTrue(state.commitFieldEvaluation(PARTIAL, true, INCOMPLETE));
        assertEquals(FieldPresence.UNKNOWN, state.fieldPresence());
        assertEquals(GravityState.DEFAULT, state.assignedState());
        assertEquals(durable, state.assignmentRevision());
        assertEquals(sync + 1, state.assignmentSyncRevision());
        assertFalse(state.commitFieldEvaluation(PARTIAL, true, INCOMPLETE));
        assertTrue(state.commitFieldEvaluation(GravityState.DEFAULT, false, COMPLETE));
        assertEquals(FieldPresence.ABSENT, state.fieldPresence());
    }

    @Test
    void unchangedSeedChangesOnlyLiveRevisionAndProvenanceChangesDurableRevision() {
        var state = loadedSeed();
        assertTrue(state.commitFieldEvaluation(SAVED_RESULTANT, true, COMPLETE));
        assertEquals(17, state.assignmentRevision());
        assertEquals(18, state.assignmentSyncRevision());
        assertFalse(state.commitFieldEvaluation(SAVED_RESULTANT, true, COMPLETE));
        var defaults = new GravityEntityState();
        defaults.commitFieldEvaluation(GravityState.DEFAULT, true, COMPLETE);
        assertEquals(1, defaults.assignmentRevision());
        defaults.commitFieldEvaluation(GravityState.DEFAULT, false, COMPLETE);
        assertEquals(2, defaults.assignmentRevision());
    }

    @Test
    void saveWhileUnknownPreservesTheExactDurableSeed() {
        var loaded = loadedSeed();
        loaded.commitFieldEvaluation(PARTIAL, true, INCOMPLETE);

        var savedAgain = new GravityEntityState.StoredAssignment(
                loaded.assignedState(),
                loaded.assignedAuthority(),
                loaded.assignmentRevision(),
                loaded.durableFieldContinuity());

        assertEquals(SAVED_RESULTANT, savedAgain.state());
        assertEquals(17L, savedAgain.revision());
        assertEquals(
                GravityEntityState.FieldContinuity.SEED,
                savedAgain.fieldContinuity()
        );
    }

    @Test
    void confirmedPresenceReopensUnknownOnRuntimeChange() {
        var state = new GravityEntityState();

        state.setAssigned(
                SAVED_RESULTANT,
                GravityAuthorityMode.FIELD,
                true
        );

        /*
         * This distinguishes runtime continuity from a fresh disk seed.
         * The old destination application is a real committed FIELD consumer.
         */
        state.commitAuthoritativeApplication(
                new CommittedGravityApplication(
                        SAVED_RESULTANT,
                        GravitySuppressionReason.NONE,
                        GravityApplicationPlan.character(
                                GravityAccelerationMode.FIELD
                        )
                )
        );

        state.invalidateFieldEvidence();

        assertEquals(FieldPresence.UNKNOWN, state.fieldPresence());
        assertFalse(state.assignedFieldPresent());

        assertTrue(
                state.fieldReferenceInForce(),
                "runtime reconciliation retains only an already-committed "
                        + "FIELD reference"
        );
    }
}
