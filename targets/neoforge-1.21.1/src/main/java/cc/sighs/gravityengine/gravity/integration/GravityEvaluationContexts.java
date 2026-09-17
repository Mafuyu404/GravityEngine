package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.acceleration.GravityAuthorityState;
import cc.sighs.gravityengine.gravity.acceleration.GravityEvaluationContext;
import cc.sighs.gravityengine.gravity.acceleration.GravityEvaluationSnapshot;
import cc.sighs.gravityengine.gravity.component.EntityGravityComponent;
import cc.sighs.gravityengine.gravity.field.GravityFieldRegistry;
import cc.sighs.gravityengine.gravity.model.GravityAccelerationMode;
import cc.sighs.gravityengine.gravity.model.GravityAuthorityMode;

import java.util.Objects;
import java.util.Optional;

/**
 * Single target adapter that derives the common physical-evaluation context
 * from committed engine state.
 */
public final class GravityEvaluationContexts {
    private GravityEvaluationContexts() {}

    public static GravityAuthorityState authorityOf(
            EntityGravityComponent component
    ) {
        Objects.requireNonNull(component, "component");
        var state = component.state();
        return state.assignedAuthority() == GravityAuthorityMode.DIRECT
                ? GravityAuthorityState.direct(state.assignmentRevision())
                : GravityAuthorityState.field(
                        state.assignedFieldPresent(),
                        state.assignmentRevision()
                );
    }

    public static GravityEvaluationContext capture(
            EntityGravityComponent component,
            GravityFieldRegistry registry
    ) {
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(registry, "registry");

        var state = component.state();
        long registryRevision = state.committedApplication()
                .plan()
                .accelerationMode() == GravityAccelerationMode.FIELD
                ? registry.publicationRevision()
                : GravityEvaluationSnapshot
                .NO_FIELD_REGISTRY_REVISION;

        return new GravityEvaluationContext(
                authorityOf(component),
                state.applicationEpoch(),
                state.committedApplication(),
                registryRevision
        );
    }

    /** True when a runtime evaluation still matches current committed state. */
    public static boolean isValidCurrent(
            GravityEvaluationSnapshot evaluation,
            EntityGravityComponent component,
            GravityFieldRegistry registry
    ) {
        Objects.requireNonNull(evaluation, "evaluation");
        return evaluation.context().samePhysicalContext(
                capture(component, registry)
        );
    }

    /**
     * The current tick/current evaluation only when its complete physical
     * context is still valid.
     */
    public static Optional<GravityEvaluationSnapshot> validTickEvaluation(
            EntityGravityComponent component,
            GravityFieldRegistry registry
    ) {
        return component.operationState()
                .tickGravityEvaluation()
                .filter(evaluation -> isValidCurrent(
                        evaluation,
                        component,
                        registry
                ));
    }
}
