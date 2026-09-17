package cc.sighs.gravityengine.gravity.debug;

import org.junit.jupiter.api.Test;
import cc.sighs.gravityengine.gravity.integration.diagnostics.MovementCollisionDiagnostics;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** No game bootstrap: exercise boot-OFF entry points without even an entity. */
class DebugDisabledPathTest {
    @Test void disabledMovementNeedsNoEntityOrSolveAndCleanupIsAlwaysSafe() {
        assertFalse(BootDebugOptions.movementEnabled());
        assertNull(MovementCollisionDiagnostics.begin(null, null, null));
        assertNull(MovementCollisionDiagnostics.capture(null));
        assertDoesNotThrow(() -> {
            MovementCollisionDiagnostics.input(null, null, null, null, null, null, null);
            MovementCollisionDiagnostics.result(null, null, null, null);
            MovementCollisionDiagnostics.vanillaInput(null, null, null);
            MovementCollisionDiagnostics.vanillaResult(null, null, null);
            MovementCollisionDiagnostics.recordVelocityResolution(null, null, null);
            MovementCollisionDiagnostics.discontinuity(null);
            MovementCollisionDiagnostics.clear(null);
        });
    }

    @Test void disabledViewUsesOneNoopAndStacksRemainOptIn() {
        assertFalse(BootDebugOptions.viewEnabled());
        assertFalse(BootDebugOptions.viewStacksEnabled());
        assertSame(PlayerViewDebugLog.Scope.NOOP, PlayerViewDebugLog.begin(null, "first"));
        assertSame(PlayerViewDebugLog.Scope.NOOP, PlayerViewDebugLog.begin(null, "second"));
        assertDoesNotThrow(PlayerViewDebugLog.Scope.NOOP::close);
        assertEquals("disabled", GravityDebugLog.callerStack());
    }

    @Test void invariantTelemetryRunsWithDebugOffAndFormatsOnlyAnEmittedViolation() {
        assertFalse(BootDebugOptions.options().anyTextEnabled());
        assertFalse(GravityInvariant.THROW_ON_VIOLATION);
        var formatted = new AtomicInteger();
        java.util.function.Supplier<String> diagnostic = () -> "observation=" + formatted.incrementAndGet();
        GravityInvariant.report("unit-test-debug-independent-invariant", false, diagnostic);
        assertEquals(0, formatted.get());
        GravityInvariant.report("unit-test-debug-independent-invariant", true, diagnostic);
        assertEquals(1, formatted.get());
        GravityInvariant.report("unit-test-debug-independent-invariant", true, diagnostic);
        assertEquals(1, formatted.get());
    }
}
