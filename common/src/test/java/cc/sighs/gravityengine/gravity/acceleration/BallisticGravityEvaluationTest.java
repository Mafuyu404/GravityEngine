package cc.sighs.gravityengine.gravity.acceleration;

import cc.sighs.gravityengine.api.field.GravityField;
import cc.sighs.gravityengine.api.field.GravityFieldCompositionMode;
import cc.sighs.gravityengine.api.field.GravityFieldQuery;
import cc.sighs.gravityengine.api.field.GravityFieldSample;
import cc.sighs.gravityengine.api.field.GravityFields;
import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.ballistic.BallisticGravityIntegrator;
import cc.sighs.gravityengine.gravity.field.GravityFieldInstance;
import cc.sighs.gravityengine.gravity.field.GravityFieldOrder;
import cc.sighs.gravityengine.gravity.field.GravityFieldRegistry;
import cc.sighs.gravityengine.gravity.model.CommittedGravityApplication;
import cc.sighs.gravityengine.gravity.model.GravityAccelerationMode;
import cc.sighs.gravityengine.gravity.model.GravityApplicationPlan;
import cc.sighs.gravityengine.gravity.model.GravityFieldId;
import cc.sighs.gravityengine.gravity.model.GravitySuppressionReason;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BallisticGravityEvaluationTest {
    private static final Vec3d DOWN =
            new Vec3d(0.0D, -1.0D, 0.0D);

    @Test
    void ballisticFieldUsesOneCompleteQueryAndOneIntegrableSnapshot() {
        AtomicInteger invocations = new AtomicInteger();
        AtomicReference<GravityFieldQuery> observed =
                new AtomicReference<>();
        GravityFieldRegistry registry = registry(query -> {
            invocations.incrementAndGet();
            observed.set(query);
            return new GravityFieldSample(
                    new Vec3d(
                            query.velocity().x(),
                            -1.0D - 0.25D * query.gameTick(),
                            query.velocity().z()
                    )
            );
        });
        GravityState applied = GravityState.DEFAULT;
        GravityEvaluationContext context = context(
                GravityAuthorityState.field(true, 4L),
                GravityApplicationPlan.ballistic(
                        GravityAccelerationMode.FIELD
                ),
                applied,
                registry,
                9L
        );
        AccelerationQuery query = new AccelerationQuery(
                new Vec3d(1.0D, 2.0D, 3.0D),
                new Vec3d(0.5D, 0.0D, 0.25D),
                12L,
                0.5D,
                applied
        );

        GravityEvaluationSnapshot snapshot =
                GravityEvaluationService.evaluateForPlan(
                        context,
                        registry,
                        query,
                        DOWN,
                        null
                );

        assertEquals(1, invocations.get());
        assertEquals(query.samplePoint(), observed.get().position());
        assertEquals(query.velocity(), observed.get().velocity());
        assertEquals(query.gameTick(), observed.get().gameTick());
        assertEquals(
                query.intervalTicks(),
                observed.get().intervalTicks()
        );

        Vec3d integrated = BallisticGravityIntegrator.integrate(
                query.velocity(),
                snapshot
        );
        assertEquals(
                query.velocity().add(
                        snapshot.effectiveAcceleration()
                                .multiply(0.5D)
                ),
                integrated
        );
    }

    @Test
    void ballisticDirectNeverSamplesTheFieldRegistry() {
        AtomicBoolean sampled = new AtomicBoolean();
        GravityFieldRegistry registry = registry(query -> {
            sampled.set(true);
            return new GravityFieldSample(
                    new Vec3d(9.0D, 9.0D, 9.0D)
            );
        });
        GravityState applied = new GravityState(
                new Vec3d(1.0D, 0.0D, 0.0D),
                2.5D
        );
        GravityEvaluationContext context = context(
                GravityAuthorityState.direct(6L),
                GravityApplicationPlan.ballistic(
                        GravityAccelerationMode.DIRECT
                ),
                applied,
                registry,
                11L
        );

        GravityEvaluationSnapshot snapshot =
                GravityEvaluationService.evaluateForPlan(
                        context,
                        registry,
                        new AccelerationQuery(
                                Vec3d.ZERO,
                                Vec3d.ZERO,
                                4L,
                                1.0D,
                                applied
                        ),
                        applied.down(),
                        null
                );

        assertFalse(sampled.get());
        assertEquals(
                new Vec3d(2.5D, 0.0D, 0.0D),
                snapshot.effectiveAcceleration()
        );
        assertFalse(snapshot.hasActiveField());
    }

    @Test
    void passiveAndBallisticPlansShareAuthoritySemantics() {
        GravityFieldRegistry registry = registry(
                GravityFields.zeroGravity()
        );
        GravityState applied = new GravityState(
                new Vec3d(0.0D, -1.0D, 0.0D),
                0.4D
        );
        AccelerationQuery query = new AccelerationQuery(
                Vec3d.ZERO,
                Vec3d.ZERO,
                3L,
                1.0D,
                applied
        );

        GravityEvaluationSnapshot directBallistic =
                GravityEvaluationService.evaluateForPlan(
                        context(
                                GravityAuthorityState.direct(2L),
                                GravityApplicationPlan.ballistic(
                                        GravityAccelerationMode.DIRECT
                                ),
                                applied,
                                registry,
                                7L
                        ),
                        registry,
                        query,
                        applied.down(),
                        null
                );
        GravityEvaluationSnapshot directPassive =
                GravityEvaluationService.evaluateForPlan(
                        context(
                                GravityAuthorityState.direct(2L),
                                GravityApplicationPlan.passive(
                                        GravityAccelerationMode.DIRECT
                                ),
                                applied,
                                registry,
                                7L
                        ),
                        registry,
                        query,
                        applied.down(),
                        null
                );

        assertEquals(
                directBallistic.effectiveAcceleration(),
                directPassive.effectiveAcceleration()
        );
        assertEquals(
                directBallistic.authority(),
                directPassive.authority()
        );
    }

    private static GravityEvaluationContext context(
            GravityAuthorityState authority,
            GravityApplicationPlan plan,
            GravityState applied,
            GravityFieldRegistry registry,
            long applicationEpoch
    ) {
        return new GravityEvaluationContext(
                authority,
                applicationEpoch,
                new CommittedGravityApplication(
                        applied,
                        GravitySuppressionReason.NONE,
                        plan
                ),
                plan.accelerationMode() == GravityAccelerationMode.FIELD
                        ? registry.publicationRevision()
                        : GravityEvaluationSnapshot
                        .NO_FIELD_REGISTRY_REVISION
        );
    }

    private static GravityFieldRegistry registry(GravityField evaluator) {
        GravityFieldRegistry registry = new GravityFieldRegistry();
        GravityFieldId id = new GravityFieldId("test", "field");
        registry.put(new GravityFieldInstance(
                id,
                GravityFieldOrder.named(id),
                evaluator,
                GravityFields.infiniteInfluence(),
                GravityFieldCompositionMode.ADDITIVE,
                1L
        ));
        return registry;
    }
}
