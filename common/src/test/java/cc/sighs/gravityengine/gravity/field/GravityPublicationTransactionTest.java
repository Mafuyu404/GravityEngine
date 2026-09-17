package cc.sighs.gravityengine.gravity.field;

import cc.sighs.gravityengine.api.field.*;
import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.model.GravityFieldId;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class GravityPublicationTransactionTest {
    private static final GravityFieldId ID = new GravityFieldId("test", "field");
    private static final GravityFieldBounds BOX = new GravityFieldBounds(-1, -1, -1, 1, 1, 1);

    private static GravityInfluenceVolume influence(Supplier<Optional<GravityFieldBounds>> bounds) {
        return new GravityInfluenceVolume() {
            public boolean contains(Vec3d p) { return p.lengthSquared() <= 1; }
            public Optional<GravityFieldBounds> finiteBounds() { return bounds.get(); }
        };
    }

    private static GravityFieldInstance field(long revision, GravityInfluenceVolume volume) {
        return new GravityFieldInstance(ID, GravityFieldOrder.named(ID), GravityFields.zeroGravity(),
                volume, GravityFieldCompositionMode.ADDITIVE, revision);
    }

    @Test void failedFirstPublicationLeavesNoMembershipOwnerOrGeneration() {
        for (var invalid : List.<Supplier<Optional<GravityFieldBounds>>>of(
                () -> { throw new IllegalArgumentException("consumer failure"); }, () -> null,
                () -> Optional.of(new GravityFieldBounds(0, 0, 0, Double.NaN, 0, 0)))) {
            var registry = new GravityFieldRegistry();
            assertThrows(RuntimeException.class, () -> registry.publish(field(1, influence(invalid)), "A"));
            assertEquals(0, registry.size());
            assertEquals(0, registry.globalFieldCount());
            assertEquals(0, registry.bucketedFieldCount());
            assertFalse(registry.isOwnedBy(ID, "A"));
            assertEquals(0, registry.publicationRevision());
            assertTrue(registry.query(Vec3d.ZERO).isEmpty());
            assertNotNull(registry.publish(field(1, GravityFields.infiniteInfluence()), "B"));
        }
    }

    @Test void failedReplacementPreservesOldInstanceQueryAndLease() {
        for (var volume : List.of(GravityFields.infiniteInfluence(), influence(() -> Optional.of(BOX)))) {
            var registry = new GravityFieldRegistry();
            var original = field(1, volume);
            var token = registry.publish(original, "A");
            var result = registry.evaluate(new GravityFieldQuery(Vec3d.ZERO, Vec3d.ZERO, 0, 0));
            assertThrows(IllegalStateException.class, () -> registry.publish(field(2, influence(() -> {
                throw new IllegalStateException("bounds");
            })), "A"));
            assertSame(original, registry.get(ID).orElseThrow());
            assertEquals(List.of(original), registry.query(Vec3d.ZERO));
            assertEquals(result, registry.evaluate(result.query()));
            assertEquals(1, registry.publicationRevision());
            assertTrue(registry.isOwnedBy(ID, "A"));
            assertTrue(registry.removeIfOwned(ID, token));
            assertTrue(registry.query(Vec3d.ZERO).isEmpty());
        }
    }

    @Test void tokensSurviveNeitherReplacementNorSameRevisionRecreation() {
        for (String nextOwner : List.of("A", "B")) {
            var registry = new GravityFieldRegistry();
            var old = registry.publish(field(1, GravityFields.infiniteInfluence()), "A");
            var replacement = registry.publish(field(2, GravityFields.infiniteInfluence()), "A");
            assertNotSame(old, replacement);
            assertTrue(registry.removeIfOwned(ID, replacement));
            var recreated = registry.publish(field(1, GravityFields.infiniteInfluence()), nextOwner);
            long generation = registry.publicationRevision();
            assertFalse(registry.removeIfOwned(ID, old));
            assertEquals(generation, registry.publicationRevision());
            assertTrue(registry.isOwnedBy(ID, nextOwner));
            assertTrue(registry.removeIfOwned(ID, recreated));
        }
    }

    @Test void staleOrWrongOwnerSubmissionDoesNotCallConsumerOrAcquireToken() {
        var registry = new GravityFieldRegistry();
        registry.publish(field(2, GravityFields.infiniteInfluence()), "A");
        var invalid = influence(() -> { fail("must not prepare rejected publication"); return null; });
        assertNull(registry.publish(field(1, invalid), "A"));
        assertNull(registry.publish(field(2, invalid), "A"));
        assertThrows(IllegalArgumentException.class, () -> registry.publish(field(3, invalid), "B"));
        assertEquals(1, registry.publicationRevision());
    }

    @Test void preparationRejectsEveryReentrantWriteAndReadsOldCommittedState() {
        var registry = new GravityFieldRegistry();
        var original = field(1, GravityFields.infiniteInfluence());
        var token = registry.publish(original, "A");
        var calls = new AtomicInteger();
        registry.publish(field(2, influence(() -> {
            calls.incrementAndGet();
            assertEquals(List.of(original), registry.query(Vec3d.ZERO));
            assertThrows(IllegalStateException.class, () -> registry.publish(original, "A"));
            assertThrows(IllegalStateException.class, () -> registry.removeIfOwned(ID, token));
            assertThrows(IllegalStateException.class, registry::clear);
            return Optional.of(BOX);
        })), "A");
        assertEquals(1, calls.get());
        assertEquals(0, registry.globalFieldCount());
        assertEquals(1, registry.bucketedFieldCount());
        assertEquals(2, registry.publicationRevision());
    }

    @Test void generationOverflowIsCheckedBeforeAllDestructiveCommits() throws Exception {
        var registry = new GravityFieldRegistry();
        var original = field(1, GravityFields.infiniteInfluence());
        var token = registry.publish(original, "A");
        var generation = GravityFieldRegistry.class.getDeclaredField("publicationRevision");
        generation.setAccessible(true);
        generation.setLong(registry, Long.MAX_VALUE);
        for (Runnable mutation : List.<Runnable>of(
                () -> registry.publish(field(2, influence(() -> Optional.of(BOX))), "A"),
                () -> registry.removeIfOwned(ID, token), registry::clear)) {
            assertThrows(IllegalStateException.class, mutation::run);
            assertEquals(List.of(original), registry.query(Vec3d.ZERO));
            assertTrue(registry.isOwnedBy(ID, "A"));
            assertEquals(1, registry.globalFieldCount());
            assertEquals(0, registry.bucketedFieldCount());
            assertEquals(Long.MAX_VALUE, registry.publicationRevision());
        }
    }

    @Test void finiteBoundsAtSaturatedCellCoordinateTerminate() {
        var registry = new GravityFieldRegistry();
        assertNotNull(registry.publish(field(1, influence(() -> Optional.of(
                new GravityFieldBounds(1e20, 1e20, 1e20, 1e20, 1e20, 1e20)))), "A"));
        assertEquals(1, registry.bucketedFieldCount());
    }
}
