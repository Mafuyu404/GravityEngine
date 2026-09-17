package cc.sighs.gravityengine.gravity.field;

import cc.sighs.gravityengine.api.field.*;
import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.acceleration.*;
import cc.sighs.gravityengine.gravity.model.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProviderCompositionTest {
    private static final GravityFieldQuery QUERY = new GravityFieldQuery(Vec3d.ZERO, new Vec3d(1, 2, 3), 42, 1);

    private static cc.sighs.gravityengine.api.field.GravityContribution contribution(
            String id, double acceleration, GravityFieldCompositionMode mode) {
        return new cc.sighs.gravityengine.api.field.GravityContribution("test:" + id, "test:source", 0, 0, 0,
                new Vec3d(acceleration, 0, 0), mode, 1);
    }

    @Test
    void globalOverrideAndZeroPresenceSurviveProviderBoundaries() {
        var additive = contribution("a", 12, GravityFieldCompositionMode.ADDITIVE);
        var first = contribution("b", 3, GravityFieldCompositionMode.OVERRIDE);
        var second = contribution("c", -3, GravityFieldCompositionMode.OVERRIDE);
        var result = GravityFieldService.composeContributions(List.of(second, additive, first), QUERY);
        assertEquals(Vec3d.ZERO, result.accelerationVector());
        assertTrue(result.hasActiveField());
        assertEquals(List.of("test:b", "test:c"), result.contributions().stream().map(c -> c.source().value()).toList());
        assertEquals(result, GravityFieldService.composeContributions(List.of(first, second, additive), QUERY));
        assertThrows(IllegalArgumentException.class,
                () -> GravityFieldService.composeContributions(List.of(first, first), QUERY));
    }

    @Test
    void registryAndProviderPathsValidateExcludedEvaluatorsAndOverflow() {
        var registry = new GravityFieldRegistry();
        var id = new GravityFieldId("test", "override");
        registry.publish(new GravityFieldInstance(id, GravityFieldOrder.named(id), GravityFields.zeroGravity(),
                GravityFields.infiniteInfluence(), GravityFieldCompositionMode.OVERRIDE, 1), "owner");
        var bad = new GravityFieldId("test", "bad");
        registry.publish(new GravityFieldInstance(bad, GravityFieldOrder.named(bad), query -> {
            throw new IllegalArgumentException("bad additive");
        }, GravityFields.infiniteInfluence(), GravityFieldCompositionMode.ADDITIVE, 1), "owner");
        assertThrows(IllegalArgumentException.class, () -> registry.evaluate(QUERY));
        assertThrows(IllegalArgumentException.class,
                () -> GravityFieldService.contributions(registry.query(QUERY.position(), "owner"), QUERY));
        assertThrows(IllegalArgumentException.class, () -> GravityFieldService.composeContributions(List.of(
                contribution("huge1", Double.MAX_VALUE, GravityFieldCompositionMode.ADDITIVE),
                contribution("huge2", Double.MAX_VALUE, GravityFieldCompositionMode.ADDITIVE)), QUERY));
        assertThrows(IllegalArgumentException.class, () -> new GravityFieldQuery(Vec3d.ZERO, Vec3d.ZERO, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new GravityFieldQuery(Vec3d.ZERO, Vec3d.ZERO, 0, Double.NaN));
    }

    @Test
    void ownerQueryDoesNotInvokeAnotherDomainsInfluence() {
        var registry = new GravityFieldRegistry();
        var id = new GravityFieldId("test", "private");
        var influence = new GravityInfluenceVolume() {
            public boolean contains(Vec3d point) { throw new AssertionError("unrelated domain queried"); }
            public java.util.Optional<GravityFieldBounds> finiteBounds() { return java.util.Optional.empty(); }
        };
        registry.publish(new GravityFieldInstance(id, GravityFieldOrder.named(id), GravityFields.zeroGravity(),
                influence, GravityFieldCompositionMode.ADDITIVE, 1), "other");
        assertTrue(registry.query(Vec3d.ZERO, "owner").isEmpty());
    }

    @Test
    void providerResultCapturesPartialPositiveContributions() {
        var source = new ArrayList<>(List.of(contribution("a", 1, GravityFieldCompositionMode.ADDITIVE)));
        var result = new GravityFieldProviderResult(FieldCoverage.INCOMPLETE, source);
        source.clear();
        assertEquals(1, result.contributions().size());
        assertEquals(FieldCoverage.INCOMPLETE, result.coverage());
        assertThrows(UnsupportedOperationException.class, () -> result.contributions().clear());
    }

    @Test
    void partialPositiveCannotDrivePhysicsAndCoverageLossInvalidatesRawReuse() {
        var applied = new GravityState(new Vec3d(0, 0, 1), .08);
        var application = new CommittedGravityApplication(applied, GravitySuppressionReason.NONE,
                GravityApplicationPlan.character(GravityAccelerationMode.FIELD));
        var context = new GravityEvaluationContext(GravityAuthorityState.field(true, 2), 1, application, 1);
        var query = new AccelerationQuery(QUERY.position(), QUERY.velocity(), QUERY.gameTick(), QUERY.intervalTicks(), applied);
        var values = List.of(contribution("partial", 4, GravityFieldCompositionMode.ADDITIVE));
        class Source implements GravityFieldEvaluationSource {
            FieldCoverage coverage = FieldCoverage.COMPLETE;
            public long publicationRevision() { return 1; }
            public boolean revisionCoversEvaluation() { return false; }
            public GravityFieldEvaluation evaluate(GravityFieldQuery request) {
                assertEquals(QUERY, request);
                return new GravityFieldEvaluation(request, GravityFieldService.composeContributions(values, request), coverage, 1);
            }
        }
        var source = new Source();
        var old = source.evaluate(QUERY);
        source.coverage = FieldCoverage.INCOMPLETE;
        var physical = GravityEvaluationService.evaluateCommitted(context, source, query, old, applied.down(), null);
        assertEquals(new Vec3d(0, 0, .08), physical.acceleration().accelerationVector());
        assertEquals(FieldCoverage.INCOMPLETE, physical.fieldCoverage());
        assertFalse(physical.evidence().hasActiveField(), "continuity cannot invent a contribution identity");
        var direct = GravityEvaluationService.evaluateCharacterOperation(context, source, query, applied.down(), null);
        assertEquals(physical.acceleration(), direct.acceleration());
    }
}
