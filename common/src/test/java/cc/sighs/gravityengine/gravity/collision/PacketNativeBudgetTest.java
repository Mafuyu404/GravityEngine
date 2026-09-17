package cc.sighs.gravityengine.gravity.collision;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PacketNativeBudgetTest {
    @Test void candidateTraversalStopsBeforeThirdCandidateIsProcessed() {
        var tracker = new CollisionWorkTracker(new CollisionWorkBudget(10, 2, 10, 10, 10));
        int processed = 0;
        for (int i = 0; i < 1000; i++) {
            if (!tracker.recordRigidCandidate()) break;
            processed++;
        }
        assertEquals(2, processed);
        assertEquals(2, tracker.rigidCandidatesVisited());
        assertTrue(tracker.limitExceeded());
        assertFalse(tracker.recordObstacles(1));
    }

    @Test void wholePublicationReservationFailsBeforeValidationAndIsNotPartiallyCharged() {
        var tracker = new CollisionWorkTracker(new CollisionWorkBudget(10, 2, 10, 10, 10));
        assertTrue(tracker.recordObstacles(1)); // One external publication already accepted.
        assertFalse(tracker.recordObstacles(1000)); // Native publication rejected before walking its list.
        assertEquals(1, tracker.snapshot().obstaclesProduced());
        assertTrue(tracker.limitExceeded());
    }
}
