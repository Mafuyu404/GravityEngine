package cc.sighs.gravityengine.gravity.collision;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StepDecisionObservationTest {
    @Test void diagnosticReaderMaterializesActualBranchOperandsAndResetClearsThem() {
        var context = new ObbQueryContext();
        assertNull(context.stepDecision());
        context.recordStepDecision(true, false, "blocked", 0.25, 0.125);
        var first = context.stepDecision();
        assertEquals(new ObbQueryContext.StepDecision(true, false, "blocked", 0.25, 0.125), first);
        context.recordStepDecision(true, true, "accepted", 0.25, 0.5);
        assertFalse(first.accepted(), "old observation must remain immutable");
        assertTrue(context.stepDecision().accepted());
        context.setStepDecision(null);
        assertNull(context.stepDecision());
    }
}
