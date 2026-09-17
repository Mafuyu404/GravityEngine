package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.acceleration.AccelerationQuery;
import cc.sighs.gravityengine.gravity.acceleration.GravityAuthorityState;
import cc.sighs.gravityengine.gravity.acceleration.GravityEvaluationContext;
import cc.sighs.gravityengine.gravity.acceleration.GravityEvaluationService;
import cc.sighs.gravityengine.gravity.acceleration.GravityEvaluationSnapshot;
import cc.sighs.gravityengine.api.field.GravityFieldQuery;
import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.component.EntityGravityComponent;
import cc.sighs.gravityengine.gravity.field.GravityFieldRuntime;
import cc.sighs.gravityengine.gravity.geometry.GeometryTransitionKind;
import cc.sighs.gravityengine.gravity.geometry.PositionAuthorityPolicy;
import cc.sighs.gravityengine.gravity.integration.collision.MinecraftCollisionSceneCapture;
import cc.sighs.gravityengine.gravity.integration.geometry.GravityGeometryTransitionService;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.collision.MinecraftCollisionGeometryAdapter;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.model.GravityOperationType;
import cc.sighs.gravityengine.gravity.model.GravityAuthorityMode;
import cc.sighs.gravityengine.gravity.model.GravitySample;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState.CollisionOperationContext;
import cc.sighs.gravityengine.gravity.runtime.RestingContactSnapshot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.Optional;

/**
 * One gravity movement operation transaction.
 *
 * <p>An operation freezes one authoritative {@link GravityFrame}, one
 * collision scene and one simulation interval for the whole logical step.
 * Nested operations inherit the outer operation's frame/scene/interval
 * instead of capturing a second one, and closing the outer operation
 * publishes the transition that followed it.</p>
 */
public final class GravityOperation implements AutoCloseable {
    private final Entity entity;
    private final EntityGravityComponent component;
    private final GravityOperationType type;
    private final GravitySample sample;
    private final GravityOperationState.MoveScope operation;
    private final GravityEvaluationSnapshot evaluation;
    private final CollisionScene collisionScene;
    private final boolean outermost;

    /**
     * Endpoint-support evidence for character-control resolution in this
     * operation.
     *
     * Geometry preparation is allowed to invalidate the runtime's live endpoint
     * slot. PRESERVE_SUPPORT, however, has independently revalidated the previous
     * finite support face, so that transition may carry the pre-preparation
     * endpoint into this logical step.
     */
    private final Optional<Boolean> controlTerminalSupportAtStepStart;

    private boolean closed;

    private GravityOperation(
            Entity e,
            EntityGravityComponent c,
            GravityOperationType t,
            GravitySample s,
            GravityOperationState.MoveScope sc,
            GravityEvaluationSnapshot evaluation,
            boolean o,
            Optional<Boolean> controlTerminalSupportAtStepStart
    ) {
        this.entity = Objects.requireNonNull(e);
        this.component = Objects.requireNonNull(c);
        this.type = Objects.requireNonNull(t);
        this.sample = Objects.requireNonNull(s);
        this.operation = Objects.requireNonNull(sc);
        this.evaluation = Objects.requireNonNull(evaluation);
        this.collisionScene =
                Objects.requireNonNull(
                        c.operationState()
                                .collisionOperation(),
                        "operation collision scene"
                ).scene();
        this.outermost = o;
        this.controlTerminalSupportAtStepStart =
                Objects.requireNonNull(
                        controlTerminalSupportAtStepStart,
                        "controlTerminalSupportAtStepStart"
                );
    }

    private static GravityFieldQuery currentGravityQuery(
            Entity entity,
            EntityGravityComponent component,
            double intervalTicks
    ) {
        GravityOperationState runtime =
                component.operationState();

        boolean customBody =
                component.state()
                        .appliedPlan()
                        .usesCustomBody()
                        || GravityInfluencePolicy
                        .usesCustomBody(entity);

        Vec3 samplePoint;

        if (customBody
                && runtime.geometryReferenceFrame() != null) {
            samplePoint =
                    GravityEntityGeometry.bodyCenter(
                            entity,
                            runtime.geometryReferenceFrame()
                    );
        } else {
            samplePoint =
                    entity.getBoundingBox().getCenter();
        }

        return new GravityFieldQuery(
                MinecraftMathAdapter.toVec3d(samplePoint),
                MinecraftMathAdapter.toVec3d(
                        entity.getDeltaMovement()
                ),
                entity.level().getGameTime(),
                intervalTicks
        );
    }

    public static GravityOperation open(Entity e, GravityOperationType t, Vec3 samplePoint) {
        return open(e, t, samplePoint, 1.0D, defaultDomain(e));
    }

    /**
     * Opens an operation with an explicit simulation interval in ticks.
     * Ordinary vanilla travel uses {@code 1.0}; sub-tick or interval-spanning
     * operations supply their real interval.
     */
    public static GravityOperation open(
            Entity e,
            GravityOperationType t,
            Vec3 samplePoint,
            double intervalTicks
    ) {
        return open(e, t, samplePoint, intervalTicks, defaultDomain(e));
    }

    /**
     * Opens an outer operation with an explicit conservative capture domain.
     * The scene is captured from the Minecraft world exactly once before pose
     * preparation or any collision solve.
     */
    public static GravityOperation open(
            Entity e,
            GravityOperationType t,
            Vec3 samplePoint,
            double intervalTicks,
            CollisionCaptureDomain domain
    ) {
        return open(e, t, samplePoint, intervalTicks, domain,
                t == GravityOperationType.LOAD_RESTORE
                        ? PositionAuthorityPolicy.EXTERNAL_POSITION_ANCHOR
                        : PositionAuthorityPolicy.OPERATION_MAY_REANCHOR);
    }

    /**
     * Opens an outer operation whose translation proposal already has an
     * externally owned position anchor.
     *
     * <p>{@link PositionAuthorityPolicy} is
     * supplied by every owner that translates the entity by a displacement
     * computed against a previously captured anchor - most importantly
     * Vanilla's packet loop, whose pre-move baseline, {@code lastGood}
     * bookkeeping and correction destination all assume the anchor it
     * captured. Such an operation must not re-anchor the pose; it still owns
     * one frozen frame/scene and its normal collision solve.</p>
     */
    public static GravityOperation open(
            Entity e,
            GravityOperationType t,
            Vec3 samplePoint,
            double intervalTicks,
            CollisionCaptureDomain domain,
            PositionAuthorityPolicy positionAuthority
    ) {
        Objects.requireNonNull(domain, "domain");
        return open(e, t, samplePoint, intervalTicks, (sample, frame) -> domain,
                positionAuthority);
    }

    @FunctionalInterface
    public interface CollisionCaptureDomainResolver {
        CollisionCaptureDomain resolve(GravitySample sample, GravityFrame proposedFrame);
    }

    public static GravityOperation open(Entity e, GravityOperationType t, Vec3 samplePoint,
                                                                                                     double intervalTicks, CollisionCaptureDomainResolver domainResolver) {
        return open(e, t, samplePoint, intervalTicks, domainResolver,
                t == GravityOperationType.LOAD_RESTORE
                        ? PositionAuthorityPolicy.EXTERNAL_POSITION_ANCHOR
                        : PositionAuthorityPolicy.OPERATION_MAY_REANCHOR);
    }

    public static GravityOperation open(Entity e, GravityOperationType t, Vec3 samplePoint,
                                                                                                     double intervalTicks, CollisionCaptureDomainResolver domainResolver,
                                                                                                     PositionAuthorityPolicy positionAuthority) {
        Objects.requireNonNull(e); Objects.requireNonNull(t); Objects.requireNonNull(samplePoint);
        Objects.requireNonNull(domainResolver, "domainResolver");
        if (!Double.isFinite(intervalTicks) || intervalTicks <= 0.0D) {
            throw new IllegalArgumentException(
                    "intervalTicks must be positive and finite: " + intervalTicks
            );
        }
        var c = GravityEntityAccess.cast(e).gravityengine$gravityComponent();
        var rt = c.operationState();

        RestingContactSnapshot softPositionSupportCandidate =
                rt.consumeSoftPositionSupportRevalidation()
                        .orElse(null);

        /*
         * Capture the completed movement fact before operation-frame preparation is
         * allowed to revise the installed collision axis and invalidate the live
         * endpoint slot.
         *
         * This value is immutable evidence belonging to the boundary between the
         * previous completed movement and the current logical step.
         */
        Optional<Boolean> completedEndpointBeforePreparation =
                rt.completedEndpointGround()
                        .map(
                                GravityOperationState.CompletedEndpointGround
                                        ::terminalSupported
                        );
        Optional<RestingContactSnapshot> completedSupportBeforePreparation =
                Optional.ofNullable(rt.restingContactSnapshot())
                        .filter(snapshot ->
                                snapshot.usableAtStepStart(
                                        e.level().getGameTime()
                                ));

        boolean customBody = c.state().appliedPlan().usesCustomBody()
                || cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy
                .usesCustomBody(e);

        // Nested operation: reuse existing frame
        if (rt.isInMove()) {
            // A shared CollisionScene corresponds to exactly one
            // KinematicStepContext; the nested operation must inherit the
            // parent's gameTick/intervalTicks/sceneRevision and cannot
            // silently substitute a different interval.
            CollisionOperationContext parentOperation =
                    rt.collisionOperation();
            if (parentOperation == null) {
                throw new IllegalStateException(
                        "nested movement transaction has no parent collision operation"
                );
            }
            requireNestedIntervalMatches(
                    parentOperation.time(), intervalTicks
            );
            if (customBody && rt.installedCollisionUp() == null || customBody && rt.installedCollisionUp().distanceSquared(rt.activeFrame().up()) > 1e-20) {
                throw new IllegalStateException(
                        "nested gravity operation frame does not own live geometry"
                );
            }
            if (customBody) {
                GravityEntityGeometry.requireGeometryMatchesFrame(
                        e, rt.activeFrame(), "nested operation start"
                );
            }
            var scope = rt.openMove(
                    rt.activeFrame(),
                    rt.activeSample(),
                    e.level().getGameTime(),
                    t
            );
            return new GravityOperation(
                    e,
                    c,
                    t,
                    rt.activeSample(),
                    scope,
                    rt.activeOperationEvaluation().orElseThrow(),
                    false,
                    customBody && GravityInfluencePolicy.requiresReferenceGeometry(rt.activeFrame())
                            ? completedEndpointBeforePreparation : Optional.empty()
            );
        }

        if (!customBody) {
            return openVanillaBodyOperation(
                    e, c, t, samplePoint, intervalTicks, domainResolver
            );
        }

        /*
         * Completed history seeds tangent continuity only. physical sampling
         * and fallback begin from the frame that actually owns live geometry.
         */
        var installedFrame = GravityGeometryTransitionService.installedFallback(rt);
        GravityEntityGeometry.requireGeometryMatchesFrame(
                e, installedFrame, "outer operation start"
        );

        Vec3 exactSamplePoint = GravityEntityGeometry.bodyCenter(
                e, installedFrame
        );

        /*
         * The committed application plan is the sole acceleration authority.
         *
         * FIELD  -> sample the composed world field.
         * DIRECT -> sample the committed direct GravityState.
         * NONE   -> zero sample.
         *
         * GravityOperation must never directly choose GravityFieldService, otherwise
         * DIRECT character operations would silently fall back to FIELD authority.
         */
        long gameTick = e.level().getGameTime();

        AccelerationQuery accelerationQuery =
                new AccelerationQuery(
                        MinecraftMathAdapter.toVec3d(exactSamplePoint),
                        MinecraftMathAdapter.toVec3d(
                                e.getDeltaMovement()),
                        gameTick,
                        intervalTicks,
                        c.state().appliedState()
                );

        /*
         * One gravity truth for the whole outer operation.
         *
         * The tick/assignment lifecycle publishes a full-query evaluation
         * before any movement operates. When this operation's complete query
         * inputs are unchanged we consume that exact snapshot; otherwise we
         * perform exactly one authoritative evaluation here. Every consumer -
         * acceleration, frame selection, collision and publication - reads
         * this one immutable value.
         */
        GravityEvaluationSnapshot evaluation =
                rt.gravityEvaluationFor(
                                new GravityFieldQuery(
                                        accelerationQuery.samplePoint(),
                                        accelerationQuery.velocity(),
                                        accelerationQuery.gameTick(),
                                        accelerationQuery.intervalTicks()
                                )
                        )
                        .filter(candidate ->
                                GravityEvaluationService.reusable(
                                        candidate,
                                        GravityEvaluationContexts.capture(
                                                c,
                                                GravityFieldRuntime
                                                        .get(e.level())
                                                        .registry()
                                        ),
                                        accelerationQuery
                                ).isPresent()
                        )
                        .orElseGet(() ->
                                GravityEvaluationService
                                        .evaluateCharacterOperation(
                                                GravityEvaluationContexts
                                                        .capture(
                                                                c,
                                                                GravityFieldRuntime
                                                                        .get(e.level())
                                                                        .registry()
                                                        ),
                                                GravityFieldRuntime
                                                        .get(e.level())
                                                        .registry(),
                                                accelerationQuery,
                                                c.state().appliedState().down(),
                                                rt.lastCompletedFrame()
                                        )
                        );

        GravitySample accelerationSample = evaluation.acceleration();
        var proposedFrame = evaluation.frame();

        /*
         * One operation-local scene (one CollisionContext epoch, one block
         * cache, one border snapshot, one work tracker) is created before pose
         * preparation. Frame preparation and the subsequent movement solve
         * consume this same scene; no second one-shot world query is created.
         */
        long sceneRevision =
                rt.nextSceneRevision();

        CollisionWorkTracker tracker =
                new CollisionWorkTracker(
                        CollisionWorkBudget.defaults()
                );

        KinematicStepContext time =
                new KinematicStepContext(
                        gameTick,
                        intervalTicks,
                        sceneRevision
                );

        CollisionScene scene =
                MinecraftCollisionSceneCapture.capture(
                        e,
                        domainResolver.resolve(
                                accelerationSample,
                                proposedFrame),
                        gameTick,
                        sceneRevision,
                        time,
                        tracker
                );

        /*
         * Geometry preparation and the subsequent movement solve share both the
         * captured scene and query contexts. Hard collision and support classification
         * still have distinct trackers inside this context.
         */
        ObbQueryContext queryContext =
                new ObbQueryContext();

        queryContext.setWorkTracker(
                tracker
        );

        /*
         * A completed dynamic support snapshot from the previous operation is only
         * continuity evidence.
         *
         * It may not become current-step ground authority until its stable rigid
         * identity has been revalidated against THIS operation's one frozen scene.
         */
        RestingContactSnapshot stepStartContinuityCandidate =
                softPositionSupportCandidate;

        if (completedSupportBeforePreparation
                .map(RestingContactSnapshot::dynamicSupport)
                .orElse(false)) {

            RestingContactSnapshot previousDynamicSupport =
                    completedSupportBeforePreparation
                            .orElseThrow();

            Optional<SupportTransport> revalidatedTransport =
                    rt.resolveSupportTransport(scene);

            /*
             * Never carry the previous dynamic resting snapshot directly into
             * controlSupport. The current scene must produce a fresh support query.
             */
            completedSupportBeforePreparation =
                    Optional.empty();

            completedEndpointBeforePreparation =
                    Optional.empty();

            if (revalidatedTransport.isPresent()) {
                /*
                 * Identity continuity is now proven. The previous face may guide the
                 * CURRENT-scene query, but it still grants no support on its own.
                 */
                stepStartContinuityCandidate =
                        previousDynamicSupport;
            } else {
                /*
                 * The rigid body disappeared, was replaced, rolled its continuity
                 * epoch, or otherwise failed publication continuity.
                 */
                rt.clearRestingContactSnapshot();

                if (stepStartContinuityCandidate != null
                        && stepStartContinuityCandidate
                        .dynamicSupport()) {
                    stepStartContinuityCandidate = null;
                }
            }
        }

        var prepResult =
                GravityGeometryTransitionService
                        .prepareOperationFrame(
                                e,
                                proposedFrame,
                                scene,
                                positionAuthority,
                                queryContext
                        );

        var selectedFrame =
                prepResult.selectedFrame();

        if (rt.installedCollisionUp() == null || rt.installedCollisionUp().distanceSquared(selectedFrame.up()) > 1e-20) {
            throw new IllegalStateException(
                    "selected operation frame does not own installed geometry: selected="
                            + selectedFrame
                            + ", installed="
                            + rt.geometryReferenceFrame()
            );
        }

        GravityEntityGeometry
                .requireGeometryMatchesFrame(
                        e,
                        selectedFrame,
                        "prepared operation start"
                );

        /*
         * For ordinary geometry changes the runtime's post-preparation endpoint is the
         * authority:
         *
         *   UNCHANGED / DEADBAND
         *       -> live endpoint was never invalidated.
         *
         *   DIRECT_AT_ANCHOR
         *       -> changed geometry invalidated the endpoint, so support is unknown.
         *
         * Only PRESERVE_SUPPORT is allowed to carry the pre-preparation endpoint
         * through the invalidation. That path has already:
         *
         *   - found the previous RestingContactSnapshot,
         *   - rejected discontinuous axis changes,
         *   - verified the original static support plane still exists,
         *   - queried feet support against the proposed body/frame,
         *   - required a walkable supporting face,
         *   - committed the support-preserving pose.
         *
         * Therefore carrying the previous completed endpoint here does not manufacture
         * ground from stale geometry.
         */
        Optional<Boolean> controlTerminalSupport =
                prepResult.transitionKind()
                        == GeometryTransitionKind.PRESERVE_SUPPORT
                        ? completedEndpointBeforePreparation
                        : rt.completedEndpointGround()
                        .map(
                                GravityOperationState.CompletedEndpointGround
                                        ::terminalSupported
                        );
        Optional<RestingContactSnapshot> controlSupport =
                controlTerminalSupport
                        .filter(Boolean::booleanValue)
                        .isPresent()
                        ? completedSupportBeforePreparation
                        : Optional.empty();

        /*
         * A joint multi-contact support is deliberately never persisted as a
         * cross-tick resting plane, so an at-rest character carried by one has
         * no carried snapshot at all. Re-verify the CURRENT prepared body
         * against this operation's already captured scene instead of
         * inheriting the previous step's unsupported/true claim.
         *
         * The query runs before the move scope opens: it never changes o,
         * never captures a second world scene, never resamples gravity and
         * never calls Entity.move.
         */
        boolean exactContactOwner =
                GravityInfluencePolicy.requiresReferenceGeometry(selectedFrame);
        if (!exactContactOwner) {
            // CHARACTER may still use Vanilla AABB collision. Vanilla owns
            // grounded/traction for that route; empty means no override.
            controlSupport = Optional.empty();
            controlTerminalSupport = Optional.empty();
        } else if (controlSupport.isEmpty()) {
            StepStartSupportQuery.Result revalidated =
                    reverifyStepStartSupport(
                            e,
                            selectedFrame,
                            scene,
                            queryContext,
                            stepStartContinuityCandidate
                    );
            if (revalidated.indeterminate()) {
                /*
                 * Support classification uncertainty is not a grounded claim:
                 * an unproven query never carries the previous step's `true`
                 * forward, while an already known unsupported endpoint stays
                 * known-unsupported.
                 */
                controlTerminalSupport =
                        controlTerminalSupport.filter(Boolean.FALSE::equals);
            } else {
                controlSupport = revalidated.support();
                controlTerminalSupport = Optional.of(controlSupport.isPresent());
            }
        }

        var scope =
                rt.openMove(
                        selectedFrame,
                        accelerationSample,
                        gameTick,
                        t
                );

        try {
            rt.installOperationEvaluation(evaluation);
            rt.setCollisionOperation(
                    new CollisionOperationContext(
                            selectedFrame,
                            time,
                            scene,
                            queryContext,
                            tracker
                    )
            );
            rt.setStepStartSupport(controlSupport.orElse(null));
        } catch (RuntimeException | Error failure) {
            /* No failure path may leave an open outer move scope behind. */
            scope.close();
            throw failure;
        }

        return new GravityOperation(
                e,
                c,
                t,
                accelerationSample,
                scope,
                evaluation,
                true,
                controlTerminalSupport
        );
    }

    /**
     * Minecraft capture adapter for the loader-neutral step-start support
     * re-verification.
     *
     * <p>The body, the world velocity and the operation's logical game tick are
     * the only platform facts the common query needs. Everything else - the
     * frozen frame, the frozen scene, the shared geometry context and the
     * previous support snapshot - is already an engine value.</p>
     */
    private static StepStartSupportQuery.Result reverifyStepStartSupport(
            Entity entity,
            GravityFrame frame,
            CollisionScene scene,
            ObbQueryContext queryContext,
            RestingContactSnapshot continuityCandidate
    ) {
        return StepStartSupportQuery.query(
                GravityEntityGeometry.body(entity, frame),
                frame,
                scene,
                queryContext,
                MinecraftMathAdapter.toVec3d(
                        entity.getDeltaMovement()),
                entity.level().getGameTime(),
                continuityCandidate
        );
    }

    /** Opens a frozen-frame/scene operation without installing character geometry. */
    private static GravityOperation openVanillaBodyOperation(
            Entity entity,
            EntityGravityComponent component,
            GravityOperationType type,
            Vec3 samplePoint,
            double intervalTicks,
            CollisionCaptureDomainResolver domainResolver
    ) {
        GravityOperationState runtime = component.operationState();
        Vec3 exactSamplePoint = entity.getBoundingBox().getCenter();
        long gameTick = entity.level().getGameTime();

        AccelerationQuery accelerationQuery =
                new AccelerationQuery(
                        MinecraftMathAdapter.toVec3d(exactSamplePoint),
                        MinecraftMathAdapter.toVec3d(
                                entity.getDeltaMovement()),
                        gameTick,
                        intervalTicks,
                        component.state().appliedState()
                );

        GravityEvaluationSnapshot evaluation =
                runtime.gravityEvaluationFor(
                                new GravityFieldQuery(
                                        accelerationQuery.samplePoint(),
                                        accelerationQuery.velocity(),
                                        accelerationQuery.gameTick(),
                                        accelerationQuery.intervalTicks()
                                )
                        )
                        .filter(candidate ->
                                GravityEvaluationService.reusable(
                                        candidate,
                                        GravityEvaluationContexts.capture(
                                                component,
                                                GravityFieldRuntime
                                                        .get(entity.level())
                                                        .registry()
                                        ),
                                        accelerationQuery
                                ).isPresent()
                        )
                        .orElseGet(() ->
                                GravityEvaluationService.evaluateForPlan(
                                        GravityEvaluationContexts.capture(
                                                component,
                                                GravityFieldRuntime
                                                        .get(entity.level())
                                                        .registry()
                                        ),
                                        GravityFieldRuntime
                                                .get(entity.level())
                                                .registry(),
                                        accelerationQuery,
                                        component.state().appliedState().down(),
                                        runtime.lastCompletedFrame()
                                )
                        );
        GravitySample authoritySample = evaluation.evidence();
        GravityFrame selectedFrame = evaluation.frame();

        long sceneRevision = runtime.nextSceneRevision();
        CollisionWorkTracker tracker = new CollisionWorkTracker(
                CollisionWorkBudget.defaults()
        );
        KinematicStepContext time = new KinematicStepContext(
                gameTick, intervalTicks, sceneRevision
        );
        CollisionScene scene = MinecraftCollisionSceneCapture.capture(
                entity, domainResolver.resolve(authoritySample, selectedFrame), gameTick, sceneRevision, time, tracker
        );
        GravityOperationState.MoveScope scope = runtime.openMove(
                selectedFrame, authoritySample, gameTick, type
        );
        runtime.installOperationEvaluation(evaluation);
        runtime.setCollisionOperation(new CollisionOperationContext(
                selectedFrame, time, scene, new ObbQueryContext(), tracker
        ));
        return new GravityOperation(
                entity,
                component,
                type,
                authoritySample,
                scope,
                evaluation,
                true,
                Optional.empty()
        );
    }

    /**
     * Nested operations share the outer operation's simulation interval.
     * Intervals come from API-defined durations, so an exact {@code double}
     * comparison is used rather than an epsilon that could mask a contract
     * mismatch.
     */

    public EntityGravityComponent component() { return component; }
    public static void requireNestedIntervalMatches(KinematicStepContext parentTime, double intervalTicks) {
        if (Double.compare(parentTime.intervalTicks(), intervalTicks) != 0) {
            throw new IllegalStateException("nested gravity operation interval mismatch: outer="
                    + parentTime.intervalTicks() + " nested=" + intervalTicks);
        }
    }
    public GravityOperationType type() { return type; }
    public GravitySample sample() { return sample; }
    public GravityFrame frame() { return operation.frame(); }
    public boolean outermost() { return outermost; }

    /**
     * The one immutable physical gravity truth installed for this operation.
     *
     * <p>This is the same snapshot the runtime state installs as the
     * operation's gravity authority; consumers must read this value rather
     * than evaluating a field again inside the operation.</p>
     */
    public GravityEvaluationSnapshot gravitySnapshot() {
        return evaluation;
    }

    /**
     * The one immutable collision scene captured for this operation.
     *
     * <p>The scene was captured once before the operation opened and is
     * consumed by geometry preparation, the solve, support classification and
     * terminal publication. Calling this method never re-reads the world.</p>
     */
    public CollisionScene collisionScene() {
        return collisionScene;
    }

    /**
     * Immutable terminal-support evidence that character control must consume for
     * this logical step.
     */
    public Optional<Boolean> controlTerminalSupportAtStepStart() {
        return controlTerminalSupportAtStepStart;
    }

    /**
     * Stable support plane carried from the previous completed movement
     * through this step's geometry preparation. present only when the
     * previous endpoint was terminal-supported and its support witness
     * survived the transition.
     */
    public Optional<RestingContactSnapshot> controlSupportAtStepStart() {
        return operation.stepStartSupport();
    }

    /** Conservative domain around the current entity proxy with no requested translation. */
    public static CollisionCaptureDomain defaultDomain(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        return CollisionCaptureDomain.forTranslation(
                MinecraftCollisionGeometryAdapter.toAabb3d(entity.getBoundingBox()),
                cc.sighs.gravityengine.api.math.Vec3d.ZERO,
                entity.maxUpStep());
    }

    /**
     * Engine-owned authority binding of the committed application. The result
     * describes ownership only; the physical acceleration is produced
     * separately by {@link GravityEvaluationService}.
     */
    private static GravityAuthorityState authorityOf(
            EntityGravityComponent component
    ) {
        return GravityEvaluationContexts.authorityOf(component);
    }

    @Override
    public void close() {
        if (closed) {
            throw new IllegalStateException(
                    "GravityOperation already closed"
            );
        }

                closed = true;
                var runtime = component.operationState();
        cc.sighs.gravityengine.api.math.Vec3d discontinuity =
                outermost ? runtime.discontinuityDestination() : null;
        cc.sighs.gravityengine.api.math.Vec3d discontinuityVelocity = outermost
                ? runtime.consumePostDiscontinuityVelocity()
                : null;

        PersistentSupportState supportBeforeClose =
                outermost
                        && type == GravityOperationType.TRAVEL
                        ? runtime.persistentSupportState()
                        : null;

        SupportTransport supportTransportBeforeClose =
                outermost
                        && type == GravityOperationType.TRAVEL
                        ? runtime.engineSupportTransport()
                        .orElse(null)
                        : null;

        GravityMoveResult moveBeforeClose =
                outermost
                        && type == GravityOperationType.TRAVEL
                        ? runtime.currentMoveResult()
                        : null;

        var creditedSupportVelocity = runtime.supportVelocityContribution();
        try {
            // Outer close publishes the reference frame. Vanilla owns position history;
            // rendering derives geometry from that history and current dimensions.
            operation.close();
            if (discontinuity != null) {
                EntityPositionIntegration.commitPositionDiscontinuity(
                        entity,
                        MinecraftMathAdapter.toMinecraft(discontinuity));
                if (discontinuityVelocity != null) {
                    entity.setDeltaMovement(
                            MinecraftMathAdapter.toMinecraft(
                                    discontinuityVelocity));
                }
            }

            /*
             * Walking off a moving support is a release transition, just like
             * an accepted jump. It must inherit exactly the support material
             * velocity proven for this completed operation interval, not the
             * stale snapshot from the previous one.
             *
             * Only TRAVEL is handled here: a PLAYER packet reconciliation MOVE
             * already carries the client's world displacement, so adding
             * platform velocity there could double-apply it.
             */
            boolean releasedMovingSupport =
                    supportBeforeClose != null
                            && !supportBeforeClose.staticSupport()
                            && supportTransportBeforeClose != null
                            && moveBeforeClose != null
                            && moveBeforeClose.isAuthoritative()
                            && !moveBeforeClose.terminalGrounded()
                            && runtime.persistentSupportState()
                            == null;

            if (releasedMovingSupport) {
                cc.sighs.gravityengine.api.math.Vec3d inherited =
                        supportTransportBeforeClose
                                .endSurfaceVelocity().subtract(creditedSupportVelocity);

                if (inherited.lengthSquared() > 0.0D) {
                    entity.setDeltaMovement(
                            entity.getDeltaMovement()
                                    .add(
                                            MinecraftMathAdapter
                                                    .toMinecraft(
                                                            inherited
                                                    )
                                    )
                    );

                    runtime.recordSupportMotion(
                            entity.level().getGameTime(),
                            inherited
                    );
                }
            }
        } finally {
            var rt = component.operationState();

            if (outermost
                    && !rt.isInMove()
                    && !rt.isApplyingGeometry()) {
                cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.applyPending(entity);
                GravityFieldQuery currentQuery =
                        currentGravityQuery(
                                entity,
                                component,
                                evaluation.intervalTicks()
                        );

                rt.publishCommittedTickEvaluation(
                        evaluation,
                        GravityEvaluationContexts.capture(
                                component,
                                GravityFieldRuntime.get(
                                        entity.level()
                                ).registry()
                        ),
                        currentQuery
                );
            }
        }
    }
}
