package cc.sighs.gravityengine.gravity.field;

import cc.sighs.gravityengine.api.field.GravityFieldCompositionMode;
import cc.sighs.gravityengine.api.field.GravityFieldQuery;
import cc.sighs.gravityengine.api.field.GravityFieldSample;
import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.model.GravityContribution;
import cc.sighs.gravityengine.gravity.model.GravitySample;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Authoritative composed-field sampler.
 *
 * <p>Field presence is contribution identity, never resultant magnitude.
 * Fields within the effective composition group are always vector-summed.
 * Ordinary ADDITIVE fields compose together; if one or more active OVERRIDE
 * instances exist, the OVERRIDE group becomes authoritative and ordinary
 * additive fields are excluded.</p>
 *
 * <p>No concrete field evaluator type receives special treatment here.</p>
 *
 * <p>This service is loader-neutral: the world scope is supplied as the
 * owning {@link GravityFieldRegistry}, never as a platform level handle.</p>
 */
public final class GravityFieldService {
    private GravityFieldService() {}

    /** Sample each active publication before global provider composition. */
    public static List<cc.sighs.gravityengine.api.field.GravityContribution> contributions(
            List<GravityFieldInstance> instances, GravityFieldQuery query) {
        return instances.stream().filter(f -> f.influence().contains(query.position()))
                .map(f -> new cc.sighs.gravityengine.api.field.GravityContribution(
                        f.id().value(), f.order().sourceType().value(),
                        f.order().x(), f.order().y(), f.order().z(),
                        f.field().sample(query).acceleration(), f.compositionMode(), f.revision()))
                .toList();
    }

    /** Pure global composition of already sampled provider contributions. */
    public static GravitySample composeContributions(
            List<cc.sighs.gravityengine.api.field.GravityContribution> values,
            GravityFieldQuery query) {
        var ids = new java.util.HashSet<String>();
        for (var value : values) {
            if (!ids.add(value.id())) throw new IllegalArgumentException("duplicate field contribution: " + value.id());
        }
        boolean override = values.stream().anyMatch(c -> c.mode() == GravityFieldCompositionMode.OVERRIDE);
        var selected = values.stream()
                .filter(c -> (c.mode() == GravityFieldCompositionMode.OVERRIDE) == override)
                .sorted(java.util.Comparator.comparing(cc.sighs.gravityengine.api.field.GravityContribution::sourceType)
                        .thenComparingInt(cc.sighs.gravityengine.api.field.GravityContribution::x)
                        .thenComparingInt(cc.sighs.gravityengine.api.field.GravityContribution::y)
                        .thenComparingInt(cc.sighs.gravityengine.api.field.GravityContribution::z)
                        .thenComparing(c -> fieldId(c.id())))
                .toList();
        Vec3d sum = Vec3d.ZERO;
        List<GravityContribution> result = new ArrayList<>(selected.size());
        for (var value : selected) {
            sum = sum.add(value.acceleration());
            result.add(new GravityContribution(fieldId(value.id()), value.acceleration(), value.revision()));
        }
        return new GravitySample(query.position(), sum, result);
    }

    private static cc.sighs.gravityengine.gravity.model.GravityFieldId fieldId(String id) {
        int separator = id.indexOf(':');
        return new cc.sighs.gravityengine.gravity.model.GravityFieldId(id.substring(0, separator), id.substring(separator + 1));
    }

    /**
     * Explicitly position-only query.
     *
     * <p>Use the full overload at any physics boundary.</p>
     */
    public static GravitySample sample(
            GravityFieldRegistry registry,
            Vec3d samplePoint
    ) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(samplePoint, "samplePoint");

        return sample(
                registry,
                samplePoint,
                Vec3d.ZERO,
                0L,
                0.0D
        );
    }

    public static GravitySample sample(
            GravityFieldRegistry registry,
            Vec3d samplePoint,
            Vec3d velocity,
            long gameTick,
            double intervalTicks
    ) {
        Objects.requireNonNull(registry, "registry");
        requireFinite(samplePoint, "samplePoint");
        requireFinite(velocity, "velocity");

        List<GravityFieldInstance> instances =
                registry.query(samplePoint);

        return compose(
                instances,
                samplePoint,
                velocity,
                gameTick,
                intervalTicks
        );
    }

    /**
     * Pure deterministic field-composition seam.
     *
     * <p>Composition occurs in two mutually exclusive groups:</p>
     *
     * <ul>
     *     <li>If no active OVERRIDE instance exists, all ADDITIVE instances
     *     are vector-summed.</li>
     *     <li>If any active OVERRIDE instance exists, only OVERRIDE instances
     *     are vector-summed and ADDITIVE instances do not participate.</li>
     * </ul>
     *
     * <p>There is deliberately no priority ordering between fields inside one
     * group. Multiple fields in the effective group always compose by vector
     * addition in structural source order, independently of caller iteration
     * order.</p>
     *
     * <p>Zero acceleration remains a real contribution. Field presence is
     * contribution identity, not resultant magnitude.</p>
     */
    public static GravitySample compose(
            List<GravityFieldInstance> instances,
            Vec3d samplePoint,
            Vec3d velocity,
            long gameTick,
            double intervalTicks
    ) {
        Objects.requireNonNull(instances, "instances");

        requireFinite(samplePoint, "samplePoint");
        requireFinite(velocity, "velocity");

        instances = instances.stream()
                .sorted(GravityFieldInstance.ACCUMULATION_ORDER)
                .toList();

        GravityFieldQuery query =
                new GravityFieldQuery(
                        samplePoint,
                        velocity,
                        gameTick,
                        intervalTicks
                );

        /*
         * Composition class is selected before evaluation.
         *
         * This avoids field-order-dependent semantics such as:
         *
         *     additive -> override -> additive
         *
         * where a sequential "reset accumulator" implementation would make the
         * final result depend on key/iteration order.
         */
        boolean hasOverride = false;

        for (GravityFieldInstance instance : instances) {
            Objects.requireNonNull(instance, "field instance");

            if (instance.compositionMode()
                    == GravityFieldCompositionMode.OVERRIDE) {
                hasOverride = true;
                break;
            }
        }

        GravityFieldCompositionMode effectiveMode =
                hasOverride
                        ? GravityFieldCompositionMode.OVERRIDE
                        : GravityFieldCompositionMode.ADDITIVE;

        Vec3d totalAcceleration = Vec3d.ZERO;

        List<GravityContribution> contributions =
                new ArrayList<>(instances.size());

        for (GravityFieldInstance instance : instances) {
            if (instance.compositionMode() != effectiveMode) {
                continue;
            }

            GravityFieldSample fieldSample =
                    Objects.requireNonNull(
                            instance.field().sample(query),
                            "field sample for " + instance.id()
                    );

            /* GravityFieldSample owns an immutable Vec3d: no defensive copy. */
            Vec3d contribution = fieldSample.acceleration();

            totalAcceleration = totalAcceleration.add(contribution);

            contributions.add(
                    new GravityContribution(
                            instance.id(),
                            contribution,
                            instance.revision()
                    )
            );
        }

        return new GravitySample(
                samplePoint,
                totalAcceleration,
                contributions
        );
    }

    private static void requireFinite(Vec3d value, String name) {
        Objects.requireNonNull(value, name);

        if (!value.isFinite()) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + value
            );
        }
    }
}
