package cc.sighs.gravityengine.gravity.acceleration;

import cc.sighs.gravityengine.api.field.GravityField;
import cc.sighs.gravityengine.api.field.GravityFieldCompositionMode;
import cc.sighs.gravityengine.api.field.GravityFieldQuery;
import cc.sighs.gravityengine.api.field.GravityFieldSample;
import cc.sighs.gravityengine.api.field.GravityFields;
import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.field.GravityFieldInstance;
import cc.sighs.gravityengine.gravity.field.GravityFieldOrder;
import cc.sighs.gravityengine.gravity.field.GravityFieldRegistry;
import cc.sighs.gravityengine.gravity.model.CommittedGravityApplication;
import cc.sighs.gravityengine.gravity.model.GravityAccelerationMode;
import cc.sighs.gravityengine.gravity.model.GravityApplicationPlan;
import cc.sighs.gravityengine.gravity.model.GravityFieldId;
import cc.sighs.gravityengine.gravity.model.GravitySample;
import cc.sighs.gravityengine.gravity.model.GravitySuppressionReason;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GravityEvaluationTruthTest {
    private static final Vec3d ORIGIN = Vec3d.ZERO;
    private static final Vec3d DOWN = new Vec3d(0.0D, -1.0D, 0.0D);

    @Test
    void snapshotPreservesCompleteQueryAndContext() {
        AtomicReference<GravityFieldQuery> observed =
                new AtomicReference<>();
        GravityFieldRegistry registry = registry(query -> {
            observed.set(query);
            return new GravityFieldSample(DOWN);
        });
        GravityEvaluationContext context = fieldContext(
                GravityAuthorityState.field(true, 7L),
                registry,
                12L
        );
        AccelerationQuery query = query(
                new Vec3d(1.0D, 2.0D, 3.0D),
                new Vec3d(0.5D, -0.25D, 0.75D),
                42L
        );

        GravityEvaluationSnapshot snapshot =
                GravityEvaluationService.evaluateCharacterOperation(
                        context,
                        registry,
                        query,
                        DOWN,
                        null
                );

        assertEquals(query.samplePoint(), snapshot.query().position());
        assertEquals(query.velocity(), snapshot.query().velocity());
        assertEquals(query.gameTick(), snapshot.query().gameTick());
        assertEquals(1.0D, snapshot.query().intervalTicks());
        assertEquals(7L, snapshot.authorityRevision());
        assertEquals(12L, snapshot.applicationEpoch());
        assertEquals(
                GravityApplicationPlan.character(
                        GravityAccelerationMode.FIELD
                ),
                snapshot.committedApplication().plan()
        );
        assertEquals(query.velocity(), observed.get().velocity());
        assertEquals(query.gameTick(), observed.get().gameTick());
    }

    @Test
    void velocityAndTimeDependentFieldsUseTheFullQuery() {
        GravityFieldRegistry registry = registry(
                query -> new GravityFieldSample(
                        new Vec3d(
                                query.velocity().x(),
                                -1.0D - 0.1D * query.gameTick(),
                                query.velocity().z()
                        )
                )
        );
        GravityEvaluationContext context = fieldContext(
                GravityAuthorityState.field(true, 1L),
                registry,
                4L
        );

        GravityEvaluationSnapshot slow =
                GravityEvaluationService.evaluateCharacterOperation(
                        context,
                        registry,
                        query(ORIGIN, new Vec3d(0.1D, 0.0D, 0.0D), 5L),
                        DOWN,
                        null
                );
        GravityEvaluationSnapshot fast =
                GravityEvaluationService.evaluateCharacterOperation(
                        context,
                        registry,
                        query(ORIGIN, new Vec3d(0.9D, 0.0D, 0.0D), 6L),
                        DOWN,
                        null
                );

        assertNotEquals(
                slow.effectiveAcceleration(),
                fast.effectiveAcceleration()
        );
        assertEquals(
                new Vec3d(0.9D, -1.6D, 0.0D),
                fast.effectiveAcceleration()
        );
    }

    @Test
    void directAuthorityNeverSamplesTheFieldRegistry() {
        AtomicBoolean sampled = new AtomicBoolean();
        GravityFieldRegistry registry = registry(query -> {
            sampled.set(true);
            return new GravityFieldSample(
                    new Vec3d(9.0D, 9.0D, 9.0D)
            );
        });
        GravityState committed = new GravityState(
                new Vec3d(1.0D, 0.0D, 0.0D),
                2.5D
        );
        GravityEvaluationContext context = directContext(
                GravityAuthorityState.direct(4L),
                committed,
                8L
        );

        GravityEvaluationSnapshot snapshot =
                GravityEvaluationService.evaluateCharacterOperation(
                        context,
                        registry,
                        query(
                                ORIGIN,
                                Vec3d.ZERO,
                                8L,
                                committed
                        ),
                        committed.down(),
                        null
                );

        assertFalse(sampled.get());
        assertEquals(
                new Vec3d(2.5D, 0.0D, 0.0D),
                snapshot.effectiveAcceleration()
        );
        assertFalse(snapshot.hasActiveField());
        assertEquals(
                GravityEvaluationSnapshot.NO_FIELD_REGISTRY_REVISION,
                snapshot.fieldRegistryRevision()
        );
    }

    @Test
    void reuseRequiresCompleteAuthorityAndApplicationContext() {
        GravityFieldRegistry registry = registry(
                GravityFields.zeroGravity()
        );
        GravityEvaluationContext context = fieldContext(
                GravityAuthorityState.field(true, 10L),
                registry,
                20L
        );
        AccelerationQuery query = query(ORIGIN, Vec3d.ZERO, 5L);
        GravityEvaluationSnapshot candidate =
                GravityEvaluationService.evaluateCharacterOperation(
                        context,
                        registry,
                        query,
                        DOWN,
                        null
                );

        assertTrue(
                GravityEvaluationService.reusable(
                        candidate,
                        context,
                        query
                ).isPresent()
        );
        assertTrue(
                GravityEvaluationService.reusable(
                        candidate,
                        fieldContext(
                                GravityAuthorityState.field(true, 11L),
                                registry,
                                20L
                        ),
                        query
                ).isEmpty()
        );
        assertTrue(
                GravityEvaluationService.reusable(
                        candidate,
                        fieldContext(
                                GravityAuthorityState.field(true, 10L),
                                registry,
                                21L
                        ),
                        query
                ).isEmpty()
        );
    }

    @Test
    void directReuseDependsOnApplicationContextButNotRegistry() {
        GravityFieldRegistry registry = registry(
                GravityFields.zeroGravity()
        );
        GravityState appliedA = new GravityState(
                new Vec3d(0.0D, -1.0D, 0.0D),
                0.08D
        );
        GravityState appliedB = new GravityState(
                new Vec3d(1.0D, 0.0D, 0.0D),
                0.04D
        );
        GravityEvaluationContext contextA = directContext(
                GravityAuthorityState.direct(4L),
                appliedA,
                3L
        );
        AccelerationQuery query = query(
                ORIGIN,
                Vec3d.ZERO,
                9L,
                appliedA
        );
        GravityEvaluationSnapshot candidate =
                GravityEvaluationService.evaluateCharacterOperation(
                        contextA,
                        registry,
                        query,
                        appliedA.down(),
                        null
                );

        registry.put(new GravityFieldInstance(
                new GravityFieldId("test", "field"),
                GravityFieldOrder.named(
                        new GravityFieldId("test", "field")
                ),
                GravityFields.zeroGravity(),
                GravityFields.infiniteInfluence(),
                GravityFieldCompositionMode.ADDITIVE,
                2L
        ));

        assertTrue(
                GravityEvaluationService.reusable(
                        candidate,
                        contextA,
                        query
                ).isPresent()
        );
        assertTrue(
                GravityEvaluationService.reusable(
                        candidate,
                        directContext(
                                GravityAuthorityState.direct(4L),
                                appliedB,
                                4L
                        ),
                        query(
                                ORIGIN,
                                Vec3d.ZERO,
                                9L,
                                appliedB
                        )
                ).isEmpty()
        );
    }

    @Test
    void fieldRegistryMutationInvalidatesCachedField() {
        GravityFieldRegistry registry = registry(
                GravityFields.zeroGravity()
        );
        AccelerationQuery query = query(ORIGIN, Vec3d.ZERO, 5L);
        GravityEvaluationSnapshot candidate =
                GravityEvaluationService.evaluateCharacterOperation(
                        fieldContext(
                                GravityAuthorityState.field(true, 1L),
                                registry,
                                6L
                        ),
                        registry,
                        query,
                        DOWN,
                        null
                );

        GravityFieldId id = new GravityFieldId("test", "field");
        assertTrue(registry.put(new GravityFieldInstance(
                id,
                GravityFieldOrder.named(id),
                GravityFields.zeroGravity(),
                GravityFields.infiniteInfluence(),
                GravityFieldCompositionMode.ADDITIVE,
                2L
        )));

        assertTrue(
                GravityEvaluationService.reusable(
                        candidate,
                        fieldContext(
                                GravityAuthorityState.field(true, 1L),
                                registry,
                                6L
                        ),
                        query
                ).isEmpty()
        );
    }

    @Test
    void rawAssignmentSampleIsNotAPhysicalSnapshot() {
        GravityFieldRegistry registry = registry(query -> {
            return new GravityFieldSample(Vec3d.ZERO);
        });
        GravityFieldEvaluation evaluation =
                GravityEvaluationService.sampleFieldEvaluation(
                        query(ORIGIN, Vec3d.ZERO, 5L),
                        registry
                );

        assertEquals(
                registry.publicationRevision(),
                evaluation.fieldRegistryRevision()
        );
        assertEquals(ORIGIN, evaluation.sample().samplePoint());
    }

    @Test
    void committedEvaluationReusesRawEvidenceWhenQueryIsUnchanged() {
        AtomicInteger invocations = new AtomicInteger();
        GravityFieldRegistry registry = registry(query -> {
            invocations.incrementAndGet();
            return new GravityFieldSample(
                    new Vec3d(query.position().x(), 0.0D, 0.0D)
            );
        });
        AccelerationQuery query = query(
                new Vec3d(1.0D, 0.0D, 0.0D),
                Vec3d.ZERO,
                5L
        );
        GravityFieldEvaluation raw =
                GravityEvaluationService.sampleFieldEvaluation(
                        query,
                        registry
                );
        assertEquals(1, invocations.get());

        GravityEvaluationSnapshot snapshot =
                GravityEvaluationService.evaluateCommitted(
                        fieldContext(
                                GravityAuthorityState.field(true, 1L),
                                registry,
                                2L
                        ),
                        registry,
                        query,
                        raw,
                        DOWN,
                        null
                );

        assertEquals(1, invocations.get());
        assertEquals(
                new Vec3d(1.0D, 0.0D, 0.0D),
                snapshot.effectiveAcceleration()
        );
    }

    @Test
    void committedQueryChangeReevaluatesAtTheActualPosition() {
        AtomicInteger invocations = new AtomicInteger();
        GravityFieldRegistry registry = registry(query -> {
            invocations.incrementAndGet();
            return new GravityFieldSample(
                    new Vec3d(query.position().x(), 0.0D, 0.0D)
            );
        });
        GravityFieldEvaluation raw =
                GravityEvaluationService.sampleFieldEvaluation(
                        query(
                                new Vec3d(1.0D, 0.0D, 0.0D),
                                Vec3d.ZERO,
                                5L
                        ),
                        registry
                );
        AccelerationQuery committed = query(
                new Vec3d(10.0D, 0.0D, 0.0D),
                Vec3d.ZERO,
                5L
        );

        GravityEvaluationSnapshot snapshot =
                GravityEvaluationService.evaluateCommitted(
                        fieldContext(
                                GravityAuthorityState.field(true, 1L),
                                registry,
                                2L
                        ),
                        registry,
                        committed,
                        raw,
                        DOWN,
                        null
                );

        assertEquals(2, invocations.get());
        assertEquals(
                committed.samplePoint(),
                snapshot.samplePoint()
        );
        assertEquals(
                new Vec3d(10.0D, 0.0D, 0.0D),
                snapshot.effectiveAcceleration()
        );
    }

    @Test
    void operationEvaluationSupersedesOlderTickSnapshotOnlyWhenContextMatches() {
        GravityOperationState state = new GravityOperationState();
        GravityFieldRegistry registry = registry(
                query -> new GravityFieldSample(
                        new Vec3d(0.0D, -query.velocity().x(), 0.0D)
                )
        );
        GravityEvaluationContext context = fieldContext(
                GravityAuthorityState.field(true, 9L),
                registry,
                3L
        );
        AccelerationQuery tickQuery =
                query(ORIGIN, new Vec3d(0.1D, 0.0D, 0.0D), 30L);
        GravityEvaluationSnapshot tick =
                GravityEvaluationService.evaluateCharacterOperation(
                        context,
                        registry,
                        tickQuery,
                        DOWN,
                        null
                );
        state.publishTickEvaluation(tick);

        GravityEvaluationSnapshot operation =
                GravityEvaluationService.evaluateCharacterOperation(
                        context,
                        registry,
                        query(
                                ORIGIN,
                                new Vec3d(0.9D, 0.0D, 0.0D),
                                30L
                        ),
                        DOWN,
                        null
                );

        assertTrue(
                state.publishCommittedTickEvaluation(
                        operation,
                        context
                )
        );
        assertSame(
                operation,
                state.activeGravityEvaluation().orElseThrow()
        );
        assertEquals(
                new Vec3d(0.0D, -0.9D, 0.0D),
                state.activeGravityEvaluation()
                        .orElseThrow()
                        .effectiveAcceleration()
        );

        GravityFieldRegistry changedRegistry = registry(
                GravityFields.zeroGravity()
        );
        GravityFieldId changedId =
                new GravityFieldId("test", "field");
        changedRegistry.put(new GravityFieldInstance(
                changedId,
                GravityFieldOrder.named(changedId),
                GravityFields.zeroGravity(),
                GravityFields.infiniteInfluence(),
                GravityFieldCompositionMode.ADDITIVE,
                2L
        ));
        assertFalse(
                state.publishCommittedTickEvaluation(
                        operation,
                        fieldContext(
                                GravityAuthorityState.field(true, 9L),
                                changedRegistry,
                                3L
                        )
                )
        );
        assertTrue(state.tickGravityEvaluation().isEmpty());
    }

    @Test
    void publicationRevisionAdvancesOnlyOnAcceptedMutation() {
        GravityFieldRegistry registry = registry(
                GravityFields.zeroGravity()
        );
        GravityFieldId id = new GravityFieldId("test", "field");
        long initial = registry.publicationRevision();

        GravityFieldInstance stale = new GravityFieldInstance(
                id,
                GravityFieldOrder.named(id),
                GravityFields.zeroGravity(),
                GravityFields.infiniteInfluence(),
                GravityFieldCompositionMode.ADDITIVE,
                1L
        );
        assertFalse(registry.put(stale));
        assertEquals(initial, registry.publicationRevision());

        assertTrue(registry.put(new GravityFieldInstance(
                id,
                GravityFieldOrder.named(id),
                GravityFields.zeroGravity(),
                GravityFields.infiniteInfluence(),
                GravityFieldCompositionMode.ADDITIVE,
                2L
        )));
        assertEquals(initial + 1L, registry.publicationRevision());
        assertFalse(registry.remove(id, 1L));
        assertTrue(registry.remove(id, 2L));
        assertEquals(initial + 2L, registry.publicationRevision());
    }

    private static GravityEvaluationContext fieldContext(
            GravityAuthorityState authority,
            GravityFieldRegistry registry,
            long applicationEpoch
    ) {
        return new GravityEvaluationContext(
                authority,
                applicationEpoch,
                new CommittedGravityApplication(
                        GravityState.DEFAULT,
                        GravitySuppressionReason.NONE,
                        GravityApplicationPlan.character(
                                GravityAccelerationMode.FIELD
                        )
                ),
                registry.publicationRevision()
        );
    }

    private static GravityEvaluationContext directContext(
            GravityAuthorityState authority,
            GravityState applied,
            long applicationEpoch
    ) {
        return new GravityEvaluationContext(
                authority,
                applicationEpoch,
                new CommittedGravityApplication(
                        applied,
                        GravitySuppressionReason.NONE,
                        GravityApplicationPlan.character(
                                GravityAccelerationMode.DIRECT
                        )
                ),
                GravityEvaluationSnapshot.NO_FIELD_REGISTRY_REVISION
        );
    }

    private static AccelerationQuery query(
            Vec3d position,
            Vec3d velocity,
            long tick
    ) {
        return query(
                position,
                velocity,
                tick,
                GravityState.DEFAULT
        );
    }

    private static AccelerationQuery query(
            Vec3d position,
            Vec3d velocity,
            long tick,
            GravityState applied
    ) {
        return new AccelerationQuery(
                position,
                velocity,
                tick,
                1.0D,
                applied
        );
    }

    private static GravityFieldRegistry registry(GravityField evaluator) {
        return registry(
                evaluator,
                GravityFieldCompositionMode.ADDITIVE
        );
    }

    private static GravityFieldRegistry registry(
            GravityField evaluator,
            GravityFieldCompositionMode mode
    ) {
        GravityFieldRegistry registry = new GravityFieldRegistry();
        GravityFieldId id = new GravityFieldId("test", "field");
        registry.put(
                new GravityFieldInstance(
                        id,
                        GravityFieldOrder.named(id),
                        evaluator,
                        GravityFields.infiniteInfluence(),
                        mode,
                        1L
                )
        );
        return registry;
    }
}
