package cc.sighs.gravityengine.gravity.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loader-neutral coverage of the body-transition decision vocabulary.
 *
 * <p>The decisive contract: a synchronization request is never a physical
 * discontinuity, and only an observed position relocation may relocate the
 * body.</p>
 */
class BodyTransitionDecisionTest {

    /** Case 1: same geometry and no request - nothing to do. */
    @Test
    void quietTickStaysUntouched() {
        assertEquals(
                BodyTransitionDecision.NONE,
                BodyTransitionDecision.decide(false, false, false)
        );
    }

    /** Case 2: same geometry plus a receiver request is synchronization only. */
    @Test
    void resyncRequestIsSynchronizationOnly() {
        BodyTransitionDecision decision =
                BodyTransitionDecision.decide(false, false, true);
        assertEquals(BodyTransitionDecision.SYNC_ONLY, decision);
        assertFalse(decision.relocatesPosition());
        assertTrue(decision.preservesPositionAnchor());
        assertTrue(decision.publishesAuthoritativeState());
    }

    /**
     * An application snapshot change or a routing change alone reaches this
     * decision as a synchronization requirement (the caller folds both into
     * {@code synchronizationRequired}), and is therefore synchronization only:
     * neither is installed geometry.
     */
    @Test
    void applicationOrRoutingChangeIsSynchronizationOnly() {
        BodyTransitionDecision synchronizationOnly =
                BodyTransitionDecision.decide(false, false, true);
        assertEquals(BodyTransitionDecision.SYNC_ONLY, synchronizationOnly);
        assertTrue(synchronizationOnly.publishesAuthoritativeState());
    }

    /** Case 3: a representation change keeps the position anchor. */
    @Test
    void representationChangeKeepsPosition() {
        BodyTransitionDecision decision =
                BodyTransitionDecision.decide(false, true, false);
        assertEquals(
                BodyTransitionDecision.GEOMETRY_REPRESENTATION_CHANGE,
                decision
        );
        assertTrue(decision.preservesPositionAnchor());
        assertFalse(decision.relocatesPosition());
        assertTrue(decision.publishesAuthoritativeState());
    }

    /** A representation change outranks a simultaneous synchronization. */
    @Test
    void representationChangeOutranksSynchronization() {
        assertEquals(
                BodyTransitionDecision.GEOMETRY_REPRESENTATION_CHANGE,
                BodyTransitionDecision.decide(false, true, true)
        );
    }

    /** Case 4: relocation requires the explicit physical fact. */
    @Test
    void relocationRequiresObservedPositionChange() {
        assertFalse(BodyTransitionDecision.SYNC_ONLY.relocatesPosition());
        assertFalse(
                BodyTransitionDecision.GEOMETRY_REPRESENTATION_CHANGE
                        .relocatesPosition()
        );

        BodyTransitionDecision relocation =
                BodyTransitionDecision.decide(true, false, false);
        assertEquals(BodyTransitionDecision.PHYSICAL_RELOCATION, relocation);
        assertTrue(relocation.relocatesPosition());
        assertFalse(relocation.preservesPositionAnchor());
    }

    /** Relocation outranks every other observed fact. */
    @Test
    void relocationOutranksRepresentationAndSynchronization() {
        assertEquals(
                BodyTransitionDecision.PHYSICAL_RELOCATION,
                BodyTransitionDecision.decide(true, true, true)
        );
    }
}
