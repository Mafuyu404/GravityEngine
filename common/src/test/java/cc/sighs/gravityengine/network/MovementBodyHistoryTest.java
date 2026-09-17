package cc.sighs.gravityengine.network;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MovementBodyHistoryTest {
    @Test void inFlightVersionExpiresFromSupersessionNotBirth() {
        var history = new MovementBodyHistory<String>(32, 20);
        history.publish(10, "old", 0);
        assertEquals("old", history.accept(10, 10000).orElseThrow());
        history.publish(11, "new", 10000);
        assertEquals("old", history.accept(10, 10020).orElseThrow());
        assertTrue(history.accept(10, 10021).isEmpty());
        assertEquals("new", history.accept(11, 99999).orElseThrow());
    }

    @Test void acknowledgementRetiresOlderBodiesButAllowsRepeatedMoves() {
        var history = new MovementBodyHistory<String>(32, 20);
        history.publish(1, "a", 0);
        history.publish(2, "b", 1);
        assertTrue(history.accept(99, 1).isEmpty());
        assertEquals("a", history.accept(1, 1).orElseThrow());
        assertEquals("b", history.accept(2, 1).orElseThrow());
        assertTrue(history.accept(1, 1).isEmpty());
        assertEquals("b", history.accept(2, 2).orElseThrow());
    }

    @Test void historyIsBoundedAndEpochCannotChangeMeaning() {
        var history = new MovementBodyHistory<String>(3, 20);
        for (int i = 0; i < 100; i++) history.publish(i, "body" + i, 0);
        assertEquals(3, history.size());
        assertTrue(history.accept(96, 0).isEmpty());
        history.publish(99, "body99", 0);
        assertThrows(IllegalStateException.class, () -> history.publish(99, "forged", 0));
        assertThrows(IllegalArgumentException.class, () -> history.publish(98, "body98", 0));
    }
}
