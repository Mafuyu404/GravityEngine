package cc.sighs.gravityengine.api;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.acceleration.GravityEvaluationSnapshot;
import cc.sighs.gravityengine.gravity.acceleration.GravityEvaluationContexts;
import cc.sighs.gravityengine.gravity.field.GravityFieldInstance;
import cc.sighs.gravityengine.gravity.field.GravityFieldRuntime;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.model.GravityFieldId;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.Objects;
import java.util.Optional;

/**
 * Supported Minecraft-aware facade for external GravityEngine consumers.
 *
 * <p>This is the only entry point an external content mod needs to publish a
 * field, sample composed gravity, or inspect an entity's gravity. Every
 * operation delegates to the existing engine runtime; no evaluation,
 * indexing, composition, application or lifecycle logic is duplicated
 * here.</p>
 *
 * <p><b>Supported packages.</b> Consumer code should depend only on
 * {@code cc.sighs.gravityengine.api},
 * {@code cc.sighs.gravityengine.api.field}, and
 * {@code cc.sighs.gravityengine.api.math}
 * (plus normal Minecraft and NeoForge types). Packages such as
 * {@code gravity.field}, {@code gravity.runtime}, {@code gravity.component},
 * {@code gravity.integration}, {@code gravity.collision},
 * {@code gravity.minecraft.access}, {@code network}, {@code mixin} and
 * {@code client} are implementation details even where Java visibility is
 * currently {@code public}. JOML ({@code org.joml}) and
 * {@code cc.sighs.gravityengine.math} are not part of the consumer ABI.</p>
 *
 * <p>GravityEngine-owned data-model vectors returned by this facade are
 * immutable {@link Vec3d} values. Minecraft {@code Vec3} is used only where a
 * Minecraft-facing call boundary itself requires it.</p>
 *
 * <p><b>Thread and side.</b> All operations must run on the thread that owns
 * the level (server thread for a server level, client thread for a client
 * level). Provider sampling and publication are server-authoritative and level-local.
 * Field evaluators are pure and must never touch world or entity state.</p>
 *
 * <p><b>Lifecycle.</b> The engine clears every registered field when a
 * {@code Level} unloads. A {@link FieldPublication} remembers only the
 * accepted key and revision and holds its level weakly, so closing it after
 * unload is a no-op and a stale handle can never remove a newer revision or
 * recreate a runtime.</p>
 */
public final class GravityEngineApi {
    private GravityEngineApi() {}

    /** Register a stable producer domain before any server Level is created.
     * Factories create a fresh session per Level; sessions are closed at unload.
     * Duplicate or late registration fails explicitly. */
    public static void registerFieldProvider(net.minecraft.resources.ResourceLocation id,
            java.util.function.Function<net.minecraft.server.level.ServerLevel, GravityFieldProvider> factory) {
        cc.sighs.gravityengine.gravity.field.GravityFieldProviderRegistry.register(id, factory);
    }

    /** Evaluate only this provider's published sources, without composing them.
     * Call from that provider's evaluate method and attach its query coverage.
     * The returned immutable values include both ADDITIVE and OVERRIDE sources. */
    public static java.util.List<cc.sighs.gravityengine.api.field.GravityContribution> samplePublications(
            Level level, net.minecraft.resources.ResourceLocation provider,
            cc.sighs.gravityengine.api.field.GravityFieldQuery query) {
        return GravityFieldRuntime.get(level).samplePublications(provider, query);
    }

    /**
     * Publishes or replaces one field definition in the given level.
     *
     * <p>The internal dimension-bound key is built from {@code level}, so a
     * producer never duplicates the dimension. The returned
     * {@link FieldPublication} reports whether the submitted revision was
     * accepted or rejected as stale, and owns exactly that revision for
     * removal.</p>
     *
     * <p>A rejected publication changes nothing: the previously accepted
     * registration stays in place.</p>
     */
    public static FieldPublication publish(
            Level level,
            net.minecraft.resources.ResourceLocation provider,
            GravityFieldDefinition definition
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(definition, "definition");

        GravityFieldId id =
                MinecraftMathAdapter.toFieldId(definition.id());

        GravityFieldInstance instance = new GravityFieldInstance(
                id,
                definition.source().toInternalOrder(),
                definition.field(),
                definition.influence(),
                definition.compositionMode(),
                definition.revision()
        );

        boolean accepted =
                GravityFieldRuntime.get(level)
                        .publish(provider, instance);

        return new FieldPublication(
                level,
                id,
                definition.revision(),
                accepted
                        ? FieldPublication.Status.ACCEPTED
                        : FieldPublication.Status.REJECTED_STALE
        );
    }

    /**
     * Explicit position-only composed sampling.
     *
     * <p>Use the full overload at any physics boundary. Position-only
     * sampling supplies zero velocity, game tick {@code 0} and interval
     * {@code 0}; evaluators that need real velocity or interval receive those
     * only through the full overload.</p>
     */
    public static ComposedGravitySample sample(
            Level level,
            Vec3d position
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(position, "position");

        return sample(level, position, Vec3d.ZERO, 0L, 0.0D);
    }

    /**
     * Full physics composed sampling at an arbitrary world position.
     *
     * <p>Composition semantics are exactly the engine's current semantics:
     * if no active OVERRIDE field exists, all active ADDITIVE fields
     * participate; if at least one active OVERRIDE field exists, only active
     * OVERRIDE fields participate. The effective group vector-sums in
     * deterministic structural order and a zero contribution still counts as
     * presence.</p>
     */
    public static ComposedGravitySample sample(
            Level level,
            Vec3d position,
            Vec3d velocity,
            long gameTick,
            double intervalTicks
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(velocity, "velocity");

        if (level.isClientSide()) throw new IllegalStateException("field provider sampling is server-authoritative");
        var evaluation = GravityFieldRuntime.get(level).evaluate(
                new cc.sighs.gravityengine.api.field.GravityFieldQuery(position, velocity, gameTick, intervalTicks));
        return ComposedGravitySample.fromInternal(evaluation.sample(), evaluation.coverage());
    }

    /**
     * Read-only snapshot of an entity's effective and applied gravity.
     *
     * <p>Returns empty when the entity does not carry GravityEngine state
     * (for example a non-entity object or a platform without the entity
     * adapter installed). Never throws for an ordinary {@link Entity}.</p>
     *
     * <p>Effective gravity is read from the active operation's frozen
     * evaluation while a movement operation is open. Outside an operation it
     * is the current tick evaluation only when its complete authority,
     * application and FIELD-registry context still matches committed state.
     * Otherwise this method falls back to persisted/assignment bootstrap
     * state rather than exposing a stale runtime snapshot.</p>
     */
    public static Optional<EntityGravitySnapshot> entityGravity(
            Entity entity
    ) {
        Objects.requireNonNull(entity, "entity");

        if (!(entity instanceof GravityEntityAccess access)) {
            return Optional.empty();
        }

        var component = access.gravityengine$gravityComponent();
        var operationState = component.operationState();
        Optional<GravityEvaluationSnapshot> evaluation =
                operationState.isInMove()
                        ? operationState.activeOperationEvaluation()
                        : GravityEvaluationContexts.validTickEvaluation(
                                component.state(), component.operationState(),
                                GravityFieldRuntime.get(
                                        entity.level()
                                )
                        );

        GravityState effective = evaluation
                .map(GravityEngineApi::physicalState)
                .orElseGet(() -> component.state().assignedState());

        var frame = evaluation.map(GravityEvaluationSnapshot::frame).orElseGet(() ->
                component.state().hasActiveGravityReference()
                        ? cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess.authoritativeFrame(entity)
                        : null);

        return Optional.of(
                new EntityGravitySnapshot(
                        GravityAuthority.fromInternal(
                                component.state().assignedAuthority()
                        ),
                        effective.down(),
                        effective.strength(),
                        component.state().appliedState().down(),
                        component.state().appliedState().strength(),
                        /*
                         * Without a coherent runtime evaluation the reported
                         * evidence is the assignment/bootstrap state itself,
                         * which may be explicitly UNKNOWN while FIELD
                         * reconciliation is unresolved. It is never reported as
                         * confirmed absence.
                         */
                        component.state().fieldPresence(),
                        component.state().applicationEpoch(),
                        Optional.ofNullable(frame)
                                .map(GravityFrameView::fromInternal)
                )
        );
    }

    private static GravityState physicalState(
            GravityEvaluationSnapshot evaluation
    ) {
        Vec3d acceleration = evaluation.effectiveAcceleration();
        double strength = acceleration.length();
        if (strength <= 0.0D) {
            return new GravityState(
                    evaluation.frame().down(),
                    0.0D
            );
        }
        return new GravityState(
                acceleration.multiply(1.0D / strength),
                strength
        );
    }

}
