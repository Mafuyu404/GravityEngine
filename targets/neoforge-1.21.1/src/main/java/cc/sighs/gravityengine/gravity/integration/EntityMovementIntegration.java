package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.collision.CollisionCaptureDomain;
import cc.sighs.gravityengine.gravity.collision.CollisionSceneCoverageException;
import cc.sighs.gravityengine.gravity.collision.GravityMoveResult;
import cc.sighs.gravityengine.gravity.collision.SupportTransport;
import cc.sighs.gravityengine.gravity.debug.GravityDebugLog;
import cc.sighs.gravityengine.gravity.geometry.PositionAuthorityPolicy;
import cc.sighs.gravityengine.gravity.integration.collision.GravityCollisionEngine;
import cc.sighs.gravityengine.gravity.integration.compat.sable.SableMovementCompatibility;
import cc.sighs.gravityengine.gravity.kinematic.KinematicMoveRequest;
import cc.sighs.gravityengine.gravity.kinematic.MovementEvidence;
import cc.sighs.gravityengine.gravity.kinematic.OwnedMotion;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.collision.MinecraftCollisionGeometryAdapter;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.model.GravityCollisionRoute;
import cc.sighs.gravityengine.gravity.model.GravityOperationType;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import cc.sighs.gravityengine.gravity.runtime.ExternalSubLevelMoveEvidence;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState;
import cc.sighs.gravityengine.gravity.runtime.SubLevelMovementPolicy;
import cc.sighs.gravityengine.gravity.runtime.VanillaCollisionState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.Optional;

/**
 * Vanilla {@code Entity.move} / {@code Entity.collide} movement integration.
 *
 * <p>Vanilla continues to own the movement callbacks and side effects. The
 * custom collide hook returns one resolved translation from the pure resolver;
 * the move wrapper commits that translation through Vanilla's positional write
 * and the owning geometry boundary re-anchors the exact gravity-oriented body
 * afterwards.</p>
 */
public final class EntityMovementIntegration {
    private EntityMovementIntegration() {}

    /** Frozen after preparation for a physical move; independent support calls use current ownership. */
    public static GravityCollisionRoute movementRoute(Entity entity) {
        var selected = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().operationState().movementCollisionRoute();
        return selected != null ? selected : GravityInfluencePolicy.collisionRoute(entity);
    }

    /** Ordinary native seams run only after collide. noPhysics/piston early returns never enter here. */
    public static VanillaCollisionState movementCollisionState(Entity entity) {
        var component = GravityEntityAccess.cast(entity).gravityengine$gravityComponent();
        var operationState = component.operationState();
        if (operationState.discontinuityDestination() != null) return null;
        if (movementRoute(entity) == GravityCollisionRoute.VANILLA) return null;
        var state = component.moveInterop().currentCollisionState();
        if (state == null) throw new IllegalStateException("ordinary custom Entity.move seam requires its collision result");
        return state;
    }

    /** Install only the flags preceding native minor-contact policy; ground/support commit later. */
    public static void installMovementCollisionFlags(Entity entity) {
        var state = movementCollisionState(entity);
        if (state == null) return;
        entity.horizontalCollision = state.horizontalCollision();
        entity.verticalCollision = state.verticalCollision();
        entity.verticalCollisionBelow = state.verticalCollisionBelow();
    }

    /**
     * Support setters also run outside movement (jump, packet ground, lifecycle).
     * Only the move projection supplies a named witness; absent evidence clears
     * custom support without requiring a fictitious collision solve.
     */
    public static VanillaCollisionState supportingCollisionState(Entity entity) {
        if (movementRoute(entity) == GravityCollisionRoute.VANILLA) return null;
        return GravityEntityAccess.cast(entity)
                .gravityengine$gravityComponent()
                .moveInterop()
                .currentCollisionStateOrEmpty();
    }

    /** A fall callback has different landing semantics from gameplay ground continuity. */
    public record FallMovement(double vertical, boolean landed) {}

    public static FallMovement fallMovement(Entity entity, double vanillaVertical, boolean vanillaGrounded) {
        var runtime = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().operationState();
        return switch (movementRoute(entity)) {
            case VANILLA -> new FallMovement(vanillaVertical, vanillaGrounded);
            case EXACT_BODY -> {
                movementCollisionState(entity);
                var result = runtime.currentMoveResult();
                yield result != null && result.isAuthoritative()
                        ? new FallMovement(result.fallDistanceVertical(), result.landedOnStableSupport()) : null;
            }
            case PASSIVE_AABB -> {
                movementCollisionState(entity);
                var result = runtime.currentPassiveMoveResult();
                yield PassiveGravityCollisionIntegration.isAuthoritative(result)
                        ? new FallMovement(PassiveGravityCollisionIntegration.fallDistanceVertical(result),
                        PassiveGravityCollisionIntegration.landedOnPhysicalSupport(result)) : null;
            }
        };
    }

    public static boolean movementVerticalResponse(Entity entity) {
        var state = movementCollisionState(entity);
        return state != null && state.verticalCollision();
    }

    public static Vec3 collide(Entity entity, Vec3 movement) {
        var component =
                GravityEntityAccess.cast(entity)
                        .gravityengine$gravityComponent();
        var runtime = component.operationState();

        GravityCollisionRoute route =
                movementRoute(entity);

        ExternalSubLevelMoveEvidence external = null;

        if (route != GravityCollisionRoute.VANILLA) {
            /*
             * The first Sable -> parent-world collide belongs to the ordinary
             * Entity.move transaction.
             *
             * After that solve has been captured, Sable may call Entity.collide
             * again from LivingEntity.travel for inheritedMotion. The two vectors
             * are allowed to be numerically identical, so call phase -- not Vec3
             * equality -- distinguishes the two operations.
             */
            boolean parentSubLevelSolveAlreadyCaptured =
                    component.moveInterop().subLevelMoveEvidence() != null;

            if (parentSubLevelSolveAlreadyCaptured
                    && SableMovementCompatibility
                    .isInheritedSupportTransport(
                            entity,
                            movement
                    )) {
                return collideExternalSupportTransport(
                        entity,
                        movement,
                        runtime,
                        route
                );
            }

            /*
             * No parent solve has yet been consumed for this physical movement.
             * Capture Sable's completed SubLevel result now.
             */
            external =
                    SableMovementCompatibility.capture(
                            entity,
                            movement
                    );

            if (external != null) {
                component.moveInterop().setSubLevelMoveEvidence(external);

                Vec3 currentVelocity =
                        entity.getDeltaMovement();

                Vec3 preserved =
                        SubLevelMovementPolicy
                                .preserveExternalContactVelocity(
                                        currentVelocity,
                                        external,
                                        MinecraftMathAdapter.toMinecraft(
                                                runtime.activeFrame().up())
                                );

                if (!preserved.equals(currentVelocity)) {
                    entity.setDeltaMovement(preserved);
                }
            }
        }
        Vec3 parentWorldInput =
                SubLevelMovementPolicy.parentWorldSolveInput(
                        movement,
                        external
                );

        /*
         * Engine-owned persistent support transport is part of the requested
         * trajectory, not post-solve carry. It is applied before the one
         * authoritative collision solve and consumed at most once.
         */
        var input = MinecraftMathAdapter.toVec3d(parentWorldInput);
        var body = GravityEntityGeometry.body(entity, runtime.activeFrame());
        var preResolved = runtime.preResolvedTranslation(input, body);
        if (preResolved.isPresent()) {
            return MinecraftMathAdapter.toMinecraft(preResolved.get());
        }

        // Only this solve owns the consumed transport. Independent requests,
        // including auxiliary support and packet requests, cannot replay it.
        SupportTransport transport = runtime.currentMovementChannel()
                == KinematicMoveRequest.Channel.SELF
                ? runtime.consumeEngineSupportTransport().orElse(null) : null;
        Vec3 requestedInput = transport == null ? parentWorldInput
                : parentWorldInput.add(MinecraftMathAdapter.toMinecraft(
                        transport.displacement()));
        var request = transport == null
                ? runtime.reconcileActualMovement(input)
                : runtime.reconcileActualMovement(
                        MinecraftMathAdapter.toVec3d(requestedInput), transport.displacement());
        Vec3 resolved;
        switch (route) {
            case VANILLA -> {
                // The collide Mixin normally excludes Vanilla. Preserve the
                // physical identity operation if this facade is called directly.
                return requestedInput;
            }
            case PASSIVE_AABB -> {
                resolved = PassiveGravityCollisionIntegration.collide(entity, requestedInput);
            }
            case EXACT_BODY -> resolved = GravityCollisionEngine.collide(entity, request, transport);
            default -> throw new IllegalStateException("unknown collision route: " + route);
        }

        /*
         * The collision solver alone decides the committed displacement. A
         * later velocity/contact response never re-solves, clips or rejects
         * this valid translation.
         */
        runtime.setPreResolved(input, body, MinecraftMathAdapter.toVec3d(resolved));
        return resolved;
    }

    private static Vec3 collideExternalSupportTransport(
            Entity entity,
            Vec3 movement,
            GravityOperationState runtime,
            GravityCollisionRoute route
    ) {
        if (!runtime.isInMove()
                || runtime.collisionOperation() == null) {
            throw new IllegalStateException(
                    "Sable inherited support transport escaped its owning "
                            + "MOVE/TRAVEL collision scope"
            );
        }

        KinematicMoveRequest request =
                MovementEvidence.capture(
                        KinematicMoveRequest.Channel.SUPPORT_TRANSPORT,
                        MinecraftMathAdapter.toVec3d(movement),
                        OwnedMotion.ZERO
                ).reconcile(MinecraftMathAdapter.toVec3d(movement));

        Vec3 resolved;
        GravityMoveResult endpointResult = null;

        try {
            switch (route) {
                case EXACT_BODY -> {
                    /*
                     * Keep the full auxiliary result only as endpoint evidence.
                     * Do not install it as runtime.currentMoveResult().
                     */
                    endpointResult =
                            GravityCollisionEngine
                                    .resolveUnpublished(
                                            entity,
                                            request
                                    );

                    resolved = MinecraftMathAdapter.toMinecraft(
                            endpointResult.resolvedMovement());
                }

                case PASSIVE_AABB -> {
                    resolved =
                            PassiveGravityCollisionIntegration
                                    .collideUnpublished(
                                            entity,
                                            movement
                                    );
                }

                case VANILLA -> resolved = movement;

                default ->
                        throw new IllegalStateException(
                                "unknown collision route: "
                                        + route
                        );
            }
        } catch (CollisionSceneCoverageException uncovered) {
            if (GravityDebugLog.shouldLog(entity)) {
                GravityDebugLog.log(entity,
                        "sable-support-transport-coverage-fail",
                        "movement=%s reason=%s",
                        GravityDebugLog.vec(movement),
                        uncovered.getMessage()
                );
            }

            /*
             * No displacement is applied, so the ordinary Entity.move endpoint
             * remains the physical endpoint and remains valid.
             */
            resolved = Vec3.ZERO;
            endpointResult = null;
        }

        if (endpointResult != null) {
            runtime.stageExternalSupportTransport(
                    MinecraftMathAdapter.toVec3d(entity.position()),
                    MinecraftMathAdapter.toVec3d(resolved),
                    endpointResult
            );
        } else {
            runtime.stageExternalSupportTransport(
                    MinecraftMathAdapter.toVec3d(entity.position()),
                    MinecraftMathAdapter.toVec3d(resolved)
            );
        }

        return resolved;
    }

    public static void move(
            Entity entity,
            MoverType moverType,
            Vec3 movement,
            Runnable vanillaMove
    ) {
        var trace = GravityDebugLog.shouldLogMovement(entity)
                ? cc.sighs.gravityengine.gravity.integration.diagnostics.MovementCollisionDiagnostics.begin(entity, moverType, movement)
                : null;
        boolean completed = false;
        try {
            moveObserved(entity, moverType, movement, vanillaMove);
            completed = true;
        } catch (cc.sighs.gravityengine.gravity.collision.CollisionComplexityLimitException
                | CollisionSceneCoverageException unavailable) {
            // An unavailable publication cannot become an empty, Vanilla-owned scene.
            GravityEntityAccess.cast(entity).gravityengine$gravityComponent().operationState()
                    .clearPersistentSupportState();
            entity.setDeltaMovement(Vec3.ZERO);
            if (GravityDebugLog.shouldLog(entity)) GravityDebugLog.log(entity, "movement-capture-fail-closed", "%s", unavailable.getMessage());
        } finally {
            if (trace != null) {
                if (completed) trace.finish(entity);
                else trace.abort(entity);
            }
        }
    }

    private static void moveObserved(Entity entity, MoverType moverType, Vec3 movement, Runnable vanillaMove) {
        Objects.requireNonNull(entity);
        Objects.requireNonNull(moverType);
        Objects.requireNonNull(movement);
        Objects.requireNonNull(vanillaMove);
        GravityOperationState rt = GravityEntityAccess.cast(entity).gravityengine$gravityComponent().operationState();
        if (rt.discontinuityDestination() != null) return;
        var plan = GravityInfluencePolicy.committedPlan(entity);
        if (!plan.needsGravityOperation() && !GravityInfluencePolicy.usesCustomBody(entity)
                && !GravityInfluencePolicy.hasExternalCollisionProviders(entity)) {
            vanillaMove.run();
            return;
        }

        // Nested move: reuse the outer operation frame. Do not reinstall body.
        if (rt.isInMove()) {
            try (var scope = rt.openMove(
                    rt.activeFrame(),
                    entity.level().getGameTime(),
                    GravityOperationType.MOVE
            )) {
                /*
                 * A nested physical move in the same tick borrows the outer
                 * operation's immutable GravityFrame and CollisionScene; it
                 * never captures or replaces collision world state.
                 */
                runOwnedMove(entity, moverType, movement, vanillaMove, rt);
            }
            return;
        }

        // Outermost move: one operation owns one frozen frame/scene. The
        // capture domain is derived from the actual requested movement before
        // Vanilla runs, so no solver query can lazily re-read the world.
        Optional<SupportTransport> supportTransportPreflight =
                GravityEntityAccess.cast(entity)
                        .gravityengine$gravityComponent()
                        .moveInterop()
                        .subLevelMoveEvidence() == null
                        ? EngineSupportTransportIntegration.preflight(
                                entity,
                                rt,
                                entity.level().getGameTime(),
                                1.0D
                        )
                        : Optional.empty();
        Vec3 requestedTrajectory = supportTransportPreflight
                .map(SupportTransport::displacement)
                .map(MinecraftMathAdapter::toMinecraft)
                .map(movement::add)
                .orElse(movement);
        Optional<cc.sighs.gravityengine.math.geometry.Aabb3d>
                supportRelativeBounds =
                supportTransportPreflight
                        .flatMap(SupportTransport::trajectory)
                        .filter(
                                cc.sighs.gravityengine.gravity
                                        .collision
                                        .SupportMotionTrajectory::rotating
                        )
                        .map(
                                cc.sighs.gravityengine.gravity
                                        .collision
                                        .SupportMotionTrajectory::relativeBounds
                        );
        var captureDomain = supportRelativeBounds
                .map(relative -> CollisionCaptureDomain
                        .forTranslationRange(
                                MinecraftCollisionGeometryAdapter
                                        .toAabb3d(
                                                entity.getBoundingBox()
                                        ),
                                MinecraftMathAdapter.toVec3d(
                                        movement
                                ),
                                MinecraftMathAdapter.toVec3d(
                                        movement
                                ),
                                relative,
                                entity.maxUpStep()
                        ))
                .orElseGet(() ->
                        CollisionCaptureDomain.forTranslation(
                                MinecraftCollisionGeometryAdapter
                                        .toAabb3d(
                                                entity.getBoundingBox()
                                        ),
                                MinecraftMathAdapter.toVec3d(
                                        requestedTrajectory
                                ),
                                entity.maxUpStep()
                        ));
        try (var op = cc.sighs.gravityengine.gravity.integration.GravityOperation.open(
                entity,
                GravityOperationType.MOVE,
                rt.geometryReferenceFrame() == null
                        ? GravityEntityGeometry.proxyCenter(entity)
                        : GravityEntityGeometry.bodyCenter(
                                entity, rt.geometryReferenceFrame()
                        ),
                1.0D,
                captureDomain,
                // Vanilla's packet loop owns the position anchor it captured:
                // its pre-move baseline, lastGood bookkeeping and correction
                // destination all describe that anchor, so the current
                // Entity.move must not re-anchor the pose. Ordinary self-driven
                // movement keeps the normal support-preserving preparation.
                moverType == MoverType.PLAYER
                        ? PositionAuthorityPolicy.EXTERNAL_POSITION_ANCHOR
                        : PositionAuthorityPolicy.OPERATION_MAY_REANCHOR
        )) {
            EngineSupportTransportIntegration.stageResolvedTransport(
                    rt,
                    supportTransportPreflight
            );
            runOwnedMove(entity, moverType, movement, vanillaMove, rt);
        }
    }

    private static void runOwnedMove(Entity entity, MoverType type, Vec3 movement,
                                     Runnable vanillaMove, GravityOperationState runtime) {
        runtime.beginMovement(GravityInfluencePolicy.collisionRoute(entity));
        var evidence = MovementProvenanceIntegration.capture(entity, type, movement);
        try (var ignored = runtime.openMovementEvidence(evidence)) {
            vanillaMove.run();
        }
    }

    /**
     * Discontinuous position/body lifecycle entry (teleport,
     * respawn, dimension transfer, {@code refreshDimensions} and application
     * transitions that destroy movement continuity).
     *
     * <p>Owned {@code Entity.move} commits never call this method: the
     * owning operation consumes/closes its provenance. Invoking this entry
     * inside an active physics or geometry scope is a caller bug and is
     * rejected rather than silently corrupting the live transaction.</p>
     */
    public static void invalidateMovementContinuity(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        var component = GravityEntityAccess.cast(entity)
                .gravityengine$gravityComponent();
        GravityOperationState rt = component.operationState();
        if (rt.isInMove() || rt.isApplyingGeometry()) {
            throw new IllegalStateException(
                    "cannot invalidate movement continuity inside a physics "
                            + "or geometry scope");
        }
        cc.sighs.gravityengine.gravity.integration.diagnostics.MovementCollisionDiagnostics.discontinuity(entity);
        rt.invalidateMovementContinuity();
        component.moveInterop().clear();
        if (entity instanceof cc.sighs.gravityengine.gravity.minecraft.access.CharacterControlAccess control)
            control.gravityengine$characterControl().clear();
    }
}
