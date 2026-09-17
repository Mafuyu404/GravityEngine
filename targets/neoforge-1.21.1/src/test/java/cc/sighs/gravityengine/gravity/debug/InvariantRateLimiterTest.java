package cc.sighs.gravityengine.gravity.debug;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InvariantRateLimiterTest {
    @Test void repeatSuppressionUsesLastReportAndStorageIsBounded() {
        var limiter = new InvariantRateLimiter(2, 10);
        assertTrue(limiter.acquire("a", 0));
        assertFalse(limiter.acquire("a", 9));
        assertTrue(limiter.acquire("a", 10));
        assertTrue(limiter.acquire("b", 10));
        assertFalse(limiter.acquire("a", 11));
        assertTrue(limiter.acquire("c", 11));
        assertEquals(2, limiter.size());
        assertFalse(limiter.acquire("a", 12));
        assertTrue(limiter.acquire("b", 12), "least recently used key was evicted");
        for (int i = 0; i < 10000; i++) limiter.acquire("entity-" + i, 12);
        assertEquals(2, limiter.size());
    }
}
