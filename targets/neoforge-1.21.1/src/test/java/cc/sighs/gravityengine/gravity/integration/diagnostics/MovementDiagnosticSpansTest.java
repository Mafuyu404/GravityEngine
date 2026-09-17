package cc.sighs.gravityengine.gravity.integration.diagnostics;

import org.junit.jupiter.api.Test;

import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MovementDiagnosticSpansTest {
    // Like Entity in 1.21.1: different instances with the same numeric ID compare equal.
    private record EntityKey(int id) {}
    private record Observation(String side, long sequence, String input, String result) {}
    private static final class Pending {
        final String side;
        final long sequence;
        final String input;
        String result;

        Pending(String side, long sequence, String input) {
            this.side = side;
            this.sequence = sequence;
            this.input = input;
        }

        Observation snapshot() { return new Observation(side, sequence, input, result); }
    }

    @Test void equalKeysAndChangingHashCodesDoNotShareState() {
        var spans = new MovementDiagnosticSpans<Long>();
        var client = new EntityKey(7);
        var server = new EntityKey(7);
        assertEquals(client, server);
        var c = spans.begin(client, n -> n);
        var s = spans.begin(server, n -> n);
        assertNotSame(c, s);
        assertEquals(1L, c.value());
        assertEquals(1L, s.value());
        assertTrue(c.finish(client));
        assertSame(s, spans.active(server));
        assertTrue(s.finish(server));
        assertEquals(2L, spans.begin(client, n -> n).value());

        var mutableKey = new ArrayList<>();
        var token = spans.begin(mutableKey, n -> n);
        mutableKey.add("different hashCode");
        assertSame(token, spans.active(mutableKey));
        assertTrue(token.finish(mutableKey));
    }

    @Test void clientServerInterleavingKeepsInputsResultsAndSequencesPaired() throws Exception {
        var spans = new MovementDiagnosticSpans<Pending>();
        var client = new EntityKey(7);
        var server = new EntityKey(7);
        var clientBegan = new CountDownLatch(1);
        var serverBegan = new CountDownLatch(1);
        var clientFinished = new CountDownLatch(1);
        List<Observation> published = Collections.synchronizedList(new ArrayList<>());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var clientTask = executor.submit(() -> {
                // Different local counts ensure sequence confusion is detectable.
                assertTrue(spans.begin(client, n -> new Pending("CLIENT", n, "warmup"))
                        .finish(client));
                var c = spans.begin(client, n -> new Pending("CLIENT", n, "client-input"));
                clientBegan.countDown();
                await(serverBegan);
                assertSame(c, spans.active(client));
                assertTrue(c.active(client)); // result/velocity writes require this token.
                spans.active(client).value().result = "client-result";
                assertTrue(c.finish(client));
                published.add(c.value().snapshot());
                assertNull(spans.active(client));
                clientFinished.countDown();
            });
            var serverTask = executor.submit(() -> {
                await(clientBegan);
                var s = spans.begin(server, n -> new Pending("SERVER", n, "server-input"));
                serverBegan.countDown();
                await(clientFinished);
                assertSame(s, spans.active(server));
                assertTrue(s.active(server));
                assertNull(s.value().result, "client result must not have reached the server span");
                spans.active(server).value().result = "server-result";
                assertTrue(s.finish(server));
                published.add(s.value().snapshot());
            });
            clientTask.get(10, TimeUnit.SECONDS);
            serverTask.get(10, TimeUnit.SECONDS);
        }
        assertEquals(List.of(new Observation("CLIENT", 2, "client-input", "client-result"),
                new Observation("SERVER", 1, "server-input", "server-result")), published);
    }

    @Test void sameObjectOnAnotherThreadCannotReadWriteOrCloseOwnerSpan() throws Exception {
        var spans = new MovementDiagnosticSpans<Long>();
        var entity = new EntityKey(1);
        var owner = spans.begin(entity, n -> n);
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> {
                assertNull(spans.active(entity));
                assertFalse(owner.active(entity));
                assertFalse(owner.finish(entity));
                var other = spans.begin(entity, n -> n);
                spans.clear(entity);
                assertFalse(other.finish(entity));
            }).get(10, TimeUnit.SECONDS);
        }
        assertSame(owner, spans.active(entity));
        assertTrue(owner.finish(entity));
    }

    @Test void nestingRestoresOnlyTheValidParentAndRejectsWrongEntity() {
        var spans = new MovementDiagnosticSpans<String>();
        var entity = new EntityKey(1);
        var a = spans.begin(entity, n -> "A:" + n);
        var b = spans.begin(entity, n -> "B:" + n);
        assertFalse(a.active(entity)); // Outer result cannot overwrite the child.
        assertTrue(b.active(entity));
        assertFalse(b.finish(new EntityKey(1)));
        assertTrue(b.finish(entity));
        assertEquals("B:2", b.value());
        assertSame(a, spans.active(entity));
        assertTrue(a.active(entity));
        assertTrue(a.finish(entity));
        assertEquals("A:1", a.value());
        assertNull(spans.active(entity));
    }

    @Test void discontinuityAndClearInvalidateTheWholeOldStackBeforeFinally() {
        for (boolean clear : List.of(false, true)) {
            var spans = new MovementDiagnosticSpans<Long>();
            var entity = new EntityKey(1);
            var a = spans.begin(entity, n -> n);
            var b = spans.begin(entity, n -> n);
            if (clear) spans.clear(entity);
            else spans.discontinuity(entity);
            assertNull(spans.active(entity));
            var fresh = spans.begin(entity, n -> n);
            assertEquals(clear ? 1L : 3L, fresh.value());
            assertFalse(b.active(entity));
            assertFalse(b.finish(entity));
            assertFalse(a.finish(entity));
            assertSame(fresh, spans.active(entity));
            assertTrue(fresh.finish(entity));
            assertNull(spans.active(entity));
        }
    }

    @Test void outOfOrderAndDuplicateFinishCannotClobberChildrenOrResurrectParents() {
        var spans = new MovementDiagnosticSpans<Long>();
        var entity = new EntityKey(1);
        var root = spans.begin(entity, n -> n);
        var a = spans.begin(entity, n -> n);
        var b = spans.begin(entity, n -> n);
        assertFalse(a.finish(entity));
        assertSame(b, spans.active(entity));
        assertTrue(b.finish(entity));
        assertSame(root, spans.active(entity));
        var fresh = spans.begin(entity, n -> n);
        assertFalse(a.finish(entity));
        assertFalse(b.finish(entity));
        assertSame(fresh, spans.active(entity));
        assertTrue(fresh.finish(entity));
        assertTrue(root.finish(entity));
        assertNull(spans.active(entity));
    }

    @Test void exceptionalFinallyRetiresSpanAndNextMovementStartsCleanly() {
        var spans = new MovementDiagnosticSpans<Long>();
        var entity = new EntityKey(1);
        assertNull(spans.active(entity)); // No result target without begin.
        var failed = spans.begin(entity, n -> n);
        var physicsFailure = new IllegalStateException("physical failure must propagate");
        assertSame(physicsFailure, assertThrows(IllegalStateException.class, () -> {
            try { throw physicsFailure; }
            finally { assertTrue(failed.finish(entity)); }
        }));
        assertNull(spans.active(entity));
        var next = spans.begin(entity, n -> n);
        assertEquals(2L, next.value());
        assertFalse(failed.finish(entity));
        assertTrue(next.active(entity));
    }

    @Test void cleanupWithoutBeginDoesNotAllocateAndQueuedWeakKeysAreRemoved() throws Exception {
        var spans = new MovementDiagnosticSpans<Long>();
        var localField = MovementDiagnosticSpans.class.getDeclaredField("local");
        localField.setAccessible(true);
        var local = (ThreadLocal<?>) localField.get(spans);
        var entity = new EntityKey(1);
        spans.clear(entity);
        spans.discontinuity(entity);
        assertNull(spans.active(entity));
        assertNull(local.get());

        var old = spans.begin(entity, n -> n);
        var store = local.get();
        var statesField = store.getClass().getDeclaredField("states");
        statesField.setAccessible(true);
        var states = (Map<?, ?>) statesField.get(store);
        var weakKey = (Reference<?>) states.keySet().iterator().next();
        // Deterministic queue processing: do not rely on GC or sleeps.
        weakKey.clear();
        assertTrue(weakKey.enqueue());
        assertNull(spans.active(entity));
        assertTrue(states.isEmpty());
        assertFalse(old.finish(entity));
        var fresh = spans.begin(entity, n -> n);
        assertTrue(fresh.finish(entity));
        spans.clear(entity);
        assertNull(local.get());
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "controlled interleaving timed out"); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
