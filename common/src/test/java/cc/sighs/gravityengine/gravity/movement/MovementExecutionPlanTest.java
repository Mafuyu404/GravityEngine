package cc.sighs.gravityengine.gravity.movement;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.geometry.BodyRepresentation;
import cc.sighs.gravityengine.gravity.model.GravityAccelerationMode;
import cc.sighs.gravityengine.gravity.model.GravityApplicationPlan;
import cc.sighs.gravityengine.gravity.model.GravityCollisionRoute;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState.MovementMode;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The movement-ownership contract: locomotion policy, installed physical
 * representation, committed application and external collision ownership are
 * independent facts, and only the outer {@code Entity.move} boundary may
 * combine them into one frozen collision route.
 */
class MovementExecutionPlanTest {
    private static final GravityApplicationPlan VANILLA =
            GravityApplicationPlan.vanilla();
    private static final GravityApplicationPlan CHARACTER =
            GravityApplicationPlan.character(
                    GravityAccelerationMode.DIRECT
            );
    private static final GravityApplicationPlan PASSIVE =
            GravityApplicationPlan.passive(
                    GravityAccelerationMode.DIRECT
            );

    private static MovementExecutionPlan decide(
            MovementMode mode,
            BodyRepresentation representation,
            GravityApplicationPlan application,
            boolean external
    ) {
        return MovementExecutionPlan.decide(
                mode,
                representation,
                application,
                external
        );
    }

    /**
     * Required state matrix. Case D is the observed regression:
     * {@code NATIVE_FALLBACK} may own locomotion semantics while an installed
     * exact body still owns collision.
     */
    @Test
    void stateMatrixSelectsPhysicalCollisionOwnership() {
        MovementExecutionPlan normalNative = decide(
                MovementMode.GROUND_AIR,
                BodyRepresentation.NATIVE_AABB,
                VANILLA,
                false
        );

        assertFalse(
                normalNative.operationRequired(),
                "normal + native needs no GE operation"
        );

        MovementExecutionPlan normalNativeExecution =
                normalNative.resolveExecution(
                        BodyRepresentation.NATIVE_AABB,
                        GravityCollisionRoute.VANILLA
                );

        assertFalse(
                normalNativeExecution.engineOwnsCollision()
        );

        MovementExecutionPlan normalExact = decide(
                MovementMode.GROUND_AIR,
                BodyRepresentation.EXACT_BODY,
                CHARACTER,
                false
        );

        assertTrue(
                normalExact.operationRequired(),
                "normal + exact body requires a GE operation"
        );

        MovementExecutionPlan normalExactExecution =
                normalExact.resolveExecution(
                        BodyRepresentation.EXACT_BODY,
                        GravityCollisionRoute.EXACT_BODY
                );

        assertTrue(
                normalExactExecution.engineOwnsCollision()
        );

        MovementExecutionPlan fallbackNative = decide(
                MovementMode.NATIVE_FALLBACK,
                BodyRepresentation.NATIVE_AABB,
                VANILLA,
                false
        );

        assertFalse(
                fallbackNative.operationRequired(),
                "native fallback over a native representation stays pure Vanilla"
        );

        MovementExecutionPlan fallbackNativeExecution =
                fallbackNative.resolveExecution(
                        BodyRepresentation.NATIVE_AABB,
                        GravityCollisionRoute.VANILLA
                );

        assertFalse(
                fallbackNativeExecution.engineOwnsCollision()
        );

        MovementExecutionPlan fallbackExact = decide(
                MovementMode.NATIVE_FALLBACK,
                BodyRepresentation.EXACT_BODY,
                CHARACTER,
                false
        );

        assertTrue(
                fallbackExact.operationRequired(),
                "NATIVE_FALLBACK must not mean VANILLA collision while an "
                        + "exact body is installed"
        );

        assertEquals(
                MovementMode.NATIVE_FALLBACK,
                fallbackExact.movementMode(),
                "locomotion semantics stay Vanilla-owned"
        );

        MovementExecutionPlan fallbackExactExecution =
                fallbackExact.resolveExecution(
                        BodyRepresentation.EXACT_BODY,
                        GravityCollisionRoute.EXACT_BODY
                );

        assertTrue(
                fallbackExactExecution.engineOwnsCollision()
        );
    }

    /**
     * Installed physical representation constrains ownership on its own. A
     * deferred native handoff keeps exact collision until the representation
     * has actually committed, and only a committed native representation
     * makes the pure Vanilla fast path legal.
     */
    @Test
    void exactBodyKeepsCollisionUntilNativeRepresentationCommits() {
        MovementExecutionPlan installedExact = decide(
                MovementMode.NATIVE_FALLBACK,
                BodyRepresentation.EXACT_BODY,
                VANILLA,
                false
        );
        assertTrue(
                installedExact.operationRequired(),
                "an installed exact body requires an engine-owned collision "
                        + "operation even without a committed application"
        );

        MovementExecutionPlan committedNative = decide(
                MovementMode.NATIVE_FALLBACK,
                BodyRepresentation.NATIVE_AABB,
                VANILLA,
                false
        );
        assertFalse(
                committedNative.operationRequired(),
                "after EXACT_BODY -> NATIVE_AABB commits the Vanilla fast "
                        + "path becomes legal"
        );
    }

    /** A committed application or a registered external collision owner
     * independently requires the engine-owned operation. */
    @Test
    void committedApplicationAndExternalOwnerIndependentlyRequireOperation() {
        assertTrue(decide(
                MovementMode.GROUND_AIR,
                BodyRepresentation.NATIVE_AABB,
                CHARACTER,
                false
        ).operationRequired());
        assertTrue(decide(
                MovementMode.GROUND_AIR,
                BodyRepresentation.NATIVE_AABB,
                PASSIVE,
                false
        ).operationRequired());
        assertTrue(decide(
                MovementMode.GROUND_AIR,
                BodyRepresentation.NATIVE_AABB,
                VANILLA,
                true
        ).operationRequired());
    }

    /**
     * Ownership is a function of the installed physical facts alone: the plan
     * value contains no side, client/server or prediction input, so both sides
     * resolve the same representation to the same ownership.
     */
    @Test
    void ownershipDecisionIsSideIndependent() {
        MovementExecutionPlan first = decide(
                MovementMode.NATIVE_FALLBACK,
                BodyRepresentation.EXACT_BODY,
                CHARACTER,
                false
        );

        MovementExecutionPlan second = decide(
                MovementMode.NATIVE_FALLBACK,
                BodyRepresentation.EXACT_BODY,
                CHARACTER,
                false
        );

        assertEquals(first, second);
        assertEquals(
                first.operationRequired(),
                second.operationRequired()
        );
        assertEquals(
                first.installedRepresentation(),
                second.installedRepresentation()
        );

        MovementExecutionPlan firstExecution =
                first.resolveExecution(
                        BodyRepresentation.EXACT_BODY,
                        GravityCollisionRoute.EXACT_BODY
                );

        MovementExecutionPlan secondExecution =
                second.resolveExecution(
                        BodyRepresentation.EXACT_BODY,
                        GravityCollisionRoute.EXACT_BODY
                );

        assertEquals(
                firstExecution,
                secondExecution
        );
    }

    /**
     * The plan used to enter the Vanilla move body must describe one coherent
     * physical instant. Geometry preparation inside the owning operation can
     * commit {@code EXACT_BODY -> NATIVE_AABB}, so the execution plan re-reads
     * the installed representation instead of carrying the pre-operation
     * inspection snapshot into the move.
     *
     * <p>Case A: preparation changes nothing; the exact body keeps the
     * engine-owned route.</p>
     */
    @Test
    void executionPlanKeepsExactBodyWhenPreparationChangesNothing() {
        MovementExecutionPlan execution = decide(
                MovementMode.NATIVE_FALLBACK,
                BodyRepresentation.EXACT_BODY,
                VANILLA,
                false
        ).resolveExecution(
                BodyRepresentation.EXACT_BODY,
                GravityCollisionRoute.EXACT_BODY
        );

        assertEquals(
                BodyRepresentation.EXACT_BODY,
                execution.installedRepresentation()
        );
        assertEquals(
                GravityCollisionRoute.EXACT_BODY,
                execution.collisionRoute()
        );
        assertTrue(execution.operationRequired());
        assertTrue(execution.engineOwnsCollision());
    }

    /**
     * Case B: preparation successfully committed
     * {@code EXACT_BODY -> NATIVE_AABB}, so the execution plan describes the
     * native representation and the Vanilla fast path becomes legal.
     */
    @Test
    void executionPlanFollowsACommittedNativeRepresentation() {
        MovementExecutionPlan initial = decide(
                MovementMode.NATIVE_FALLBACK,
                BodyRepresentation.EXACT_BODY,
                VANILLA,
                false
        );
        assertTrue(initial.operationRequired());
        assertEquals(
                BodyRepresentation.EXACT_BODY,
                initial.installedRepresentation(),
                "the pre-preparation inspection still describes the exact body"
        );

        MovementExecutionPlan execution = initial.resolveExecution(
                BodyRepresentation.NATIVE_AABB,
                GravityCollisionRoute.VANILLA
        );

        assertEquals(
                BodyRepresentation.NATIVE_AABB,
                execution.installedRepresentation(),
                "the execution plan must not retain the pre-preparation body"
        );
        assertEquals(
                GravityCollisionRoute.VANILLA,
                execution.collisionRoute()
        );
        assertFalse(
                execution.operationRequired(),
                "a committed native representation permits the Vanilla fast path"
        );
        assertFalse(execution.engineOwnsCollision());
    }

    /**
     * Case C: a native handoff was requested but deferred. The installed exact
     * body is unchanged, so the route stays engine-owned and the pure Vanilla
     * fast path is forbidden.
     */
    @Test
    void deferredNativeHandoffKeepsEngineOwnedCollision() {
        MovementExecutionPlan execution = decide(
                MovementMode.NATIVE_FALLBACK,
                BodyRepresentation.EXACT_BODY,
                VANILLA,
                false
        ).resolveExecution(
                BodyRepresentation.EXACT_BODY,
                GravityCollisionRoute.EXACT_BODY
        );

        assertEquals(
                BodyRepresentation.EXACT_BODY,
                execution.installedRepresentation()
        );
        assertTrue(execution.operationRequired());
        assertTrue(execution.engineOwnsCollision());
    }

    /**
     * Case D: an external rigid collision provider keeps the operation
     * mandatory even over a native representation. Representation alone must
     * never force the pure Vanilla fast path.
     */
    @Test
    void externalCollisionOwnerKeepsOperationOverNativeRepresentation() {
        MovementExecutionPlan execution = decide(
                MovementMode.NATIVE_FALLBACK,
                BodyRepresentation.NATIVE_AABB,
                VANILLA,
                true
        ).resolveExecution(
                BodyRepresentation.NATIVE_AABB,
                GravityCollisionRoute.EXACT_BODY
        );

        assertEquals(
                BodyRepresentation.NATIVE_AABB,
                execution.installedRepresentation()
        );
        assertTrue(
                execution.operationRequired(),
                "external collision ownership is independent of representation"
        );
        assertTrue(execution.engineOwnsCollision());
    }

    /**
     * An installed exact body may never be handed to pure native-AABB
     * collision ownership. The mixed pre-operation/post-preparation
     * combination fails at the outer boundary instead of surfacing later as a
     * missing active frame or a packet correction.
     */
    @Test
    void installedExactBodyRejectsVanillaCollisionOwnership() {
        MovementExecutionPlan initial = decide(
                MovementMode.NATIVE_FALLBACK,
                BodyRepresentation.EXACT_BODY,
                CHARACTER,
                false
        );

        IllegalStateException resolvedFailure =
                org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalStateException.class,
                        () -> initial.resolveExecution(
                                BodyRepresentation.EXACT_BODY,
                                GravityCollisionRoute.VANILLA
                        )
                );

        assertTrue(
                resolvedFailure.getMessage().contains("exact body"),
                "the invariant failure must name the installed exact body: "
                        + resolvedFailure.getMessage()
        );

        IllegalStateException directInvariantFailure =
                org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalStateException.class,
                        () -> MovementExecutionPlan.requireCoherentExecution(
                                BodyRepresentation.EXACT_BODY,
                                GravityCollisionRoute.VANILLA
                        )
                );

        assertTrue(
                directInvariantFailure
                        .getMessage()
                        .contains("collision ownership")
        );

        IllegalStateException constructorFailure =
                org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalStateException.class,
                        () -> new MovementExecutionPlan(
                                MovementMode.NATIVE_FALLBACK,
                                BodyRepresentation.EXACT_BODY,
                                CHARACTER,
                                false,
                                true,
                                GravityCollisionRoute.VANILLA,
                                "illegal-test-plan"
                        )
                );

        assertTrue(
                constructorFailure.getMessage().contains("exact body"),
                "the canonical constructor must not bypass execution coherence"
        );
    }

    /** The inverse is deliberately not asserted: a native representation is a
     * legal engine-owned state (external providers, passive solves). */
    @Test
    void nativeRepresentationMayStillRequireAnEngineOperation() {
        assertTrue(decide(
                MovementMode.NATIVE_FALLBACK,
                BodyRepresentation.NATIVE_AABB,
                PASSIVE,
                false
        ).resolveExecution(
                BodyRepresentation.NATIVE_AABB,
                GravityCollisionRoute.PASSIVE_AABB
        ).engineOwnsCollision());
    }

    /**
     * The frozen route exists only while its owning operation is open, and an
     * active EXACT_BODY publication has the operation frame available.
     */
    @Test
    void frozenRouteIsPublishedOnlyInsideItsOwningOperation() {
        GravityOperationState runtime = new GravityOperationState();
        assertNull(runtime.movementCollisionRoute(),
                "no frozen route exists outside a movement operation");
        try (var scope = runtime.openMove(
                GravityFrame.DEFAULT,
                42L
        )) {
            assertNull(runtime.movementCollisionRoute(),
                    "opening a scope does not grant collision ownership");
            runtime.beginMovement(GravityCollisionRoute.EXACT_BODY);
            assertEquals(
                    GravityCollisionRoute.EXACT_BODY,
                    runtime.movementCollisionRoute(),
                    "beginMovement publishes the frozen route"
            );
            assertNotNull(runtime.activeFrame(),
                    "an active exact route owns an operation frame");
            assertSame(
                    GravityFrame.DEFAULT,
                    runtime.activeFrame()
            );
        }
        assertNull(runtime.movementCollisionRoute(),
                "closing the operation clears the frozen route");
    }

    /** {@code activeFrame()} stays fail-fast outside an operation. */
    @Test
    void activeFrameStaysFailFastOutsideAnOperation() {
        GravityOperationState runtime = new GravityOperationState();
        var failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                runtime::activeFrame
        );
        assertTrue(failure.getMessage().contains("No active GravityFrame"));
    }

}
