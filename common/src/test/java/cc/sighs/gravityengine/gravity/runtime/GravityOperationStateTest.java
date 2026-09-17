package cc.sighs.gravityengine.gravity.runtime;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.acceleration.AccelerationQuery;
import cc.sighs.gravityengine.gravity.acceleration.GravityAuthorityState;
import cc.sighs.gravityengine.gravity.acceleration.GravityEvaluationContext;
import cc.sighs.gravityengine.gravity.acceleration.GravityEvaluationService;
import cc.sighs.gravityengine.gravity.acceleration.GravityEvaluationSnapshot;
import cc.sighs.gravityengine.gravity.collision.CellPos;
import cc.sighs.gravityengine.gravity.collision.CollisionScene;
import cc.sighs.gravityengine.gravity.collision.CollisionSceneBuilder;
import cc.sighs.gravityengine.gravity.collision.CollisionWorkBudget;
import cc.sighs.gravityengine.gravity.collision.CollisionWorkTracker;
import cc.sighs.gravityengine.gravity.collision.GravityMoveResult;
import cc.sighs.gravityengine.gravity.collision.GravitySupportContact;
import cc.sighs.gravityengine.gravity.collision.MovementIndeterminateReason;
import cc.sighs.gravityengine.gravity.collision.ObbQueryContext;
import cc.sighs.gravityengine.gravity.collision.SupportTransport;
import cc.sighs.gravityengine.gravity.field.GravityFieldRegistry;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.gravity.model.GravityCollisionRoute;
import cc.sighs.gravityengine.gravity.model.GravityAccelerationMode;
import cc.sighs.gravityengine.gravity.model.GravityApplicationPlan;
import cc.sighs.gravityengine.gravity.model.CommittedGravityApplication;
import cc.sighs.gravityengine.gravity.model.GravitySuppressionReason;
import cc.sighs.gravityengine.gravity.model.GravityOperationType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused loader-neutral lifecycle coverage for the operation/state owner:
 * root publication, nested move scopes, LIFO restoration, scope-local
 * isolation and discontinuity cleanup.
 */
class GravityOperationStateTest {
    private static final GravityFrame FRAME = GravityFrame.DEFAULT;
    private static final long TICK = 42L;

    @Test
    void rootPublicationOpensAndClosesOnce() {
        GravityOperationState state = new GravityOperationState();
        assertFalse(state.isInMove());
        assertNull(state.activePublicationScope());
        assertNull(state.currentMovementCommitFacts());

        GravityOperationState.MoveScope scope =
                state.openMove(FRAME, TICK, GravityOperationType.MOVE);
        assertTrue(state.isInMove());
        assertSame(scope, state.activePublicationScope());
        assertFalse(scope.closed());
        assertSame(FRAME, state.activeFrame());

        long revision = state.presentationRevision();
        scope.close();

        assertTrue(scope.closed());
        assertFalse(state.isInMove());
        assertNull(state.activePublicationScope());
        assertNull(state.currentMovementCommitFacts());
        assertSame(FRAME, state.lastCompletedFrame());
        assertTrue(state.presentationRevision() > revision);
    }

    @Test
    void nestedScopeRestoresPublicationAndScopeLocalState() {
        GravityOperationState state = new GravityOperationState();
        GravityOperationState.MoveScope parent =
                state.openMove(FRAME, TICK, GravityOperationType.MOVE);
        state.beginMovement(GravityCollisionRoute.EXACT_BODY);
        state.setCurrentMoveResult(groundedResult());
        state.setExternalSupportingContactDuringMove(true);

        MovementCommitFacts parentFacts =
                state.currentMovementCommitFacts();
        assertTrue(parentFacts != null && parentFacts.grounded());
        assertTrue(state.externalSupportingContactDuringMove());

        GravityOperationState.MoveScope child =
                state.openMove(FRAME, TICK, GravityOperationType.MOVE);
        state.beginMovement(GravityCollisionRoute.EXACT_BODY);

        assertSame(child, state.activePublicationScope());
        assertNull(state.currentMovementCommitFacts());
        assertFalse(state.externalSupportingContactDuringMove());

        state.setCurrentMoveResult(groundedResult());
        state.setExternalSupportingContactDuringMove(false);
        assertTrue(state.currentMovementCommitFacts() != null);

        child.close();

        assertSame(parent, state.activePublicationScope());
        assertSame(parentFacts, state.currentMovementCommitFacts());
        assertTrue(state.externalSupportingContactDuringMove());
        assertTrue(child.closed());
        assertFalse(parent.closed());

        parent.close();
        assertNull(state.activePublicationScope());
        assertNull(state.currentMovementCommitFacts());
    }

    @Test
    void scopesMustCloseInLifoOrderAndOnlyOnce() {
        GravityOperationState state = new GravityOperationState();
        GravityOperationState.MoveScope parent =
                state.openMove(FRAME, TICK, GravityOperationType.MOVE);
        GravityOperationState.MoveScope child =
                state.openMove(FRAME, TICK, GravityOperationType.MOVE);

        assertThrows(IllegalStateException.class, parent::close);

        child.close();
        parent.close();
        assertThrows(IllegalStateException.class, child::close);
        assertThrows(IllegalStateException.class, parent::close);
        assertFalse(state.isInMove());
    }

    @Test
    void discontinuityClearsTransientAndSupportContinuity() {
        GravityOperationState state = new GravityOperationState();
        RestingContactSnapshot snapshot = new RestingContactSnapshot(
                new Vec3d(0.0D, 1.0D, 0.0D),
                Vec3d.ZERO,
                new Vec3d(0.5D, 64.0D, 0.5D),
                GravitySupportContact.SupportGeometryKind
                        .TRUSTED_CURRENT_BLOCK_FACE,
                Optional.of(new CellPos(0, 63, 0)),
                TICK
        );
        GravityOperationState.MoveScope scope =
                state.openMove(FRAME, TICK, GravityOperationType.MOVE);
        state.beginMovement(GravityCollisionRoute.EXACT_BODY);
        state.setCurrentMoveResult(groundedResult());
        state.setRestingContactSnapshot(snapshot);
        state.stageSoftPositionSupportRevalidation(snapshot);
        state.recordSelfWalk(TICK, new Vec3d(0.1D, 0.0D, 0.0D));
        state.recordExternalPush(TICK, new Vec3d(0.0D, 1.0D, 0.0D));

        long continuity = state.movementContinuity();
        assertSame(snapshot, state.restingContactSnapshot());

        state.invalidateMovementContinuity();

        assertNull(state.restingContactSnapshot());
        assertTrue(
                state.consumeSoftPositionSupportRevalidation().isEmpty()
        );
        assertTrue(state.lastCommittedLocomotionDisplacement(TICK).isEmpty());
        assertTrue(state.movementContinuity() > continuity);
        assertTrue(
                state.consumeMovementEvidence(TICK)
                        .selfWalk()
                        .equals(Vec3d.ZERO)
        );

        scope.close();
        assertFalse(state.isInMove());
    }

    @Test
    void engineSupportTransportIsConsumedExactlyOncePerOperation() {
        GravityOperationState state = new GravityOperationState();
        GravityOperationState.MoveScope scope =
                state.openMove(FRAME, TICK, GravityOperationType.MOVE);
        SupportTransport transport = new SupportTransport(
                new Vec3d(1.0D, 0.0D, 0.0D),
                new Vec3d(1.0D, 0.0D, 0.0D),
                1.0D,
                7L,
                TICK
        );

        state.stageEngineSupportTransport(transport);
        assertEquals(
                transport,
                state.pendingEngineSupportTransport().orElseThrow()
        );
        assertEquals(
                transport,
                state.consumeEngineSupportTransport().orElseThrow()
        );
        assertTrue(state.pendingEngineSupportTransport().isEmpty());
        assertTrue(state.consumeEngineSupportTransport().isEmpty());
        assertEquals(
                transport,
                state.engineSupportTransport().orElseThrow()
        );

        state.clearEngineSupportTransport();
        assertTrue(state.engineSupportTransport().isEmpty());
        scope.close();
    }

    @Test
    void releasedSupportMotionIsConsumedAsSupportOwnedEvidenceOnce() {
        GravityOperationState state = new GravityOperationState();
        Vec3d supportVelocity =
                new Vec3d(0.0D, 0.0D, 0.25D);

        state.recordSupportMotion(TICK, supportVelocity);

        assertEquals(
                supportVelocity,
                state.consumeMovementEvidence(TICK)
                        .supportMotion()
        );
        assertEquals(
                Vec3d.ZERO,
                state.consumeMovementEvidence(TICK)
                        .supportMotion()
        );
    }

    @Test
    void committedTickEvaluationRefusesAStaleAuthorityBinding() {
        GravityOperationState state = new GravityOperationState();
        GravityFieldRegistry registry = new GravityFieldRegistry();
        AccelerationQuery query = new AccelerationQuery(
                Vec3d.ZERO,
                Vec3d.ZERO,
                TICK,
                1.0D,
                cc.sighs.gravityengine.gravity.GravityState.DEFAULT
        );
        GravityEvaluationSnapshot snapshot =
                GravityEvaluationService.evaluateCharacterOperation(
                        fieldContext(
                                GravityAuthorityState.field(false, 4L),
                                registry,
                                1L
                        ),
                        registry,
                        query,
                        GravityState.DEFAULT.down(),
                        null
                );

        GravityEvaluationContext committedContext = fieldContext(
                GravityAuthorityState.field(false, 4L),
                registry,
                1L
        );
        assertTrue(
                state.publishCommittedTickEvaluation(
                        snapshot,
                        committedContext
                )
        );
        assertSame(
                snapshot,
                state.tickGravityEvaluation().orElseThrow()
        );

        assertFalse(
                state.publishCommittedTickEvaluation(
                        snapshot,
                        fieldContext(
                                GravityAuthorityState.field(false, 4L),
                                registry,
                                2L
                        )
                )
        );
        assertTrue(state.tickGravityEvaluation().isEmpty());

        state.publishTickEvaluation(snapshot);
        assertFalse(
                state.publishCommittedTickEvaluation(
                        snapshot,
                        fieldContext(
                                GravityAuthorityState.field(false, 5L),
                                registry,
                                1L
                        )
                )
        );
        assertTrue(state.tickGravityEvaluation().isEmpty());
    }

    @Test
    void openOperationKeepsItsFrozenEvaluationWhenContextChanges() {
        GravityOperationState state = new GravityOperationState();
        GravityFieldRegistry registry = new GravityFieldRegistry();
        GravityEvaluationContext firstContext = fieldContext(
                GravityAuthorityState.field(false, 4L),
                registry,
                1L
        );
        AccelerationQuery query = new AccelerationQuery(
                Vec3d.ZERO,
                Vec3d.ZERO,
                TICK,
                1.0D,
                GravityState.DEFAULT
        );
        GravityEvaluationSnapshot first =
                GravityEvaluationService.evaluateCharacterOperation(
                        firstContext,
                        registry,
                        query,
                        GravityState.DEFAULT.down(),
                        null
                );
        GravityEvaluationSnapshot changedContext =
                GravityEvaluationService.evaluateCharacterOperation(
                        fieldContext(
                                GravityAuthorityState.field(false, 4L),
                                registry,
                                2L
                        ),
                        registry,
                        query,
                        GravityState.DEFAULT.down(),
                        null
                );

        GravityOperationState.MoveScope scope =
                state.openMove(
                        FRAME,
                        TICK,
                        GravityOperationType.MOVE
                );
        try {
            state.installOperationEvaluation(first);

            assertThrows(
                    IllegalStateException.class,
                    () -> state.installOperationEvaluation(
                            changedContext
                    )
            );
            assertSame(
                    first,
                    state.activeOperationEvaluation().orElseThrow()
            );
        } finally {
            scope.close();
        }
    }

    @Test
    void operationInstallsExactlyOneCollisionScene() {
        GravityOperationState state = new GravityOperationState();
        GravityOperationState.MoveScope scope =
                state.openMove(
                        FRAME,
                        TICK,
                        GravityOperationType.MOVE
                );

        KinematicStepContext time =
                new KinematicStepContext(TICK, 1.0D, 3L);
        CollisionScene firstScene =
                new CollisionSceneBuilder(time).build();
        CollisionScene secondScene =
                new CollisionSceneBuilder(time).build();

        GravityOperationState.CollisionOperationContext first =
                new GravityOperationState.CollisionOperationContext(
                        FRAME,
                        time,
                        firstScene,
                        new ObbQueryContext(),
                        new CollisionWorkTracker(
                                CollisionWorkBudget.defaults()
                        )
                );
        GravityOperationState.CollisionOperationContext second =
                new GravityOperationState.CollisionOperationContext(
                        FRAME,
                        time,
                        secondScene,
                        new ObbQueryContext(),
                        new CollisionWorkTracker(
                                CollisionWorkBudget.defaults()
                        )
                );

        try {
            state.setCollisionOperation(first);

            assertThrows(
                    IllegalStateException.class,
                    () -> state.setCollisionOperation(second)
            );
            assertSame(first, state.collisionOperation());
        } finally {
            scope.close();
        }
    }

    @Test
    void resolvedTranslationRequiresSameEvidenceBodyAndOperation() {
        var state = new GravityOperationState();
        var body = new cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox(
                Vec3d.ZERO, new Vec3d(.3, .9, .3),
                cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d.IDENTITY);
        var input = new Vec3d(1, 0, 0);
        var resolved = new Vec3d(.4, 0, 0);
        var time = KinematicStepContext.fullTick(TICK, 1);
        var scene = new CollisionSceneBuilder(time).build();
        try (var move = state.openMove(FRAME, TICK, GravityOperationType.MOVE)) {
            state.setCollisionOperation(new GravityOperationState.CollisionOperationContext(FRAME,
                    time, scene, new ObbQueryContext(), new CollisionWorkTracker(CollisionWorkBudget.defaults())));
            try (var evidence = state.openMovementEvidence(cc.sighs.gravityengine.gravity.kinematic.MovementEvidence.capture(
                    cc.sighs.gravityengine.gravity.kinematic.KinematicMoveRequest.Channel.SELF,
                    input, cc.sighs.gravityengine.gravity.kinematic.OwnedMotion.ZERO))) {
                state.setPreResolved(input, body, resolved);
                assertEquals(Optional.of(resolved), state.preResolvedTranslation(input, body));
                assertTrue(state.preResolvedTranslation(Vec3d.ZERO, body).isEmpty());
                var movedBody = new cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox(
                        input, body.halfExtents(), body.orientation());
                assertTrue(state.preResolvedTranslation(input, movedBody).isEmpty());
                try (var nested = state.openMove(FRAME, TICK, GravityOperationType.MOVE)) {
                    assertTrue(state.preResolvedTranslation(input, body).isEmpty());
                }
                assertEquals(Optional.of(resolved), state.preResolvedTranslation(input, body));
            }
            for (var channel : cc.sighs.gravityengine.gravity.kinematic.KinematicMoveRequest.Channel.values()) {
                try (var independent = state.openMovementEvidence(cc.sighs.gravityengine.gravity.kinematic.MovementEvidence.capture(
                        channel, input, cc.sighs.gravityengine.gravity.kinematic.OwnedMotion.ZERO))) {
                    assertTrue(state.preResolvedTranslation(input, body).isEmpty(), channel.toString());
                }
            }
        }
        try (var next = state.openMove(FRAME, TICK + 1, GravityOperationType.MOVE)) {
            assertTrue(state.preResolvedTranslation(input, body).isEmpty());
        }
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

    private static GravityMoveResult groundedResult() {
        return new GravityMoveResult(
                Vec3d.ZERO,
                Vec3d.ZERO,
                Vec3d.ZERO,
                Vec3d.ZERO,
                true,
                false,
                false,
                true,
                Optional.empty(),
                true,
                true,
                Optional.empty(),
                0.0D,
                false,
                List.of(),
                0.0D,
                FRAME,
                Optional.empty(),
                List.of(),
                MovementIndeterminateReason.NONE
        );
    }
}
