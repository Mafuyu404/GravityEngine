package cc.sighs.gravityengine.attitude.persistence;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.attitude.BodyAttitudeState;
import cc.sighs.gravityengine.attitude.BodyRelativeViewState;
import cc.sighs.gravityengine.attitude.runtime.BodyAttitudeComponent;
import cc.sighs.gravityengine.math.Quatd;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable disk persistence seed for one player body-attitude state.
 *
 * <p>The seed stores only canonical actor/controller continuity that cannot
 * be reconstructed without loss: absolute {@code worldFromBody} and {@code worldFromController}. Angular
 * velocity is isolated to saved Elytra alignment dynamics. It is not a
 * live {@link BodyAttitudeComponent}, carries no tick/revision/stream
 * chronology and is never a network authority.</p>
 */
public record BodyAttitudePersistentSeed(
        Quatd worldFromBody,
        Quatd worldFromController,
        Optional<ElytraDynamicsSeed> elytraDynamics) {
    private static final double MIN_QUATERNION_LENGTH_SQUARED = 1.0E-24D;

    public BodyAttitudePersistentSeed {
        worldFromBody = normalized(worldFromBody, "worldFromBody");
        worldFromController = normalized(worldFromController, "worldFromController");
        Objects.requireNonNull(elytraDynamics, "elytraDynamics");

    }

    /**
     * Captures a durable seed from one already-initialized component
     * snapshot. Uninitialized state or view produces an empty seed so disk
     * saves never fabricate a pose the live component never owned.
     */
    public static Optional<BodyAttitudePersistentSeed> capture(
            BodyAttitudeComponent.Snapshot snapshot
    ) {
        Objects.requireNonNull(snapshot, "snapshot");

        BodyAttitudeState state = snapshot.state();
        BodyRelativeViewState view = snapshot.view();

        if (snapshot.ownership() != cc.sighs.gravityengine.attitude.runtime.BodyAttitudeOwnership.ACTIVE
                || !state.initialized() || !view.initialized()) {
            return Optional.empty();
        }

        return Optional.of(
                new BodyAttitudePersistentSeed(
                        state.currentWorldFromBody(),
                        view.semantic(state.currentWorldFromBody()).worldFromController(),
                        snapshot.decision().profile().constraint() == cc.sighs.gravityengine.attitude.BodyAttitudeConstraintKind.ELYTRA_ALIGNED
                                ? Optional.of(new ElytraDynamicsSeed(state.angularVelocityWorld()))
                                : Optional.empty())
        );
    }

    @Override
    public Quatd worldFromBody() {
        return worldFromBody;
    }

    @Override public Quatd worldFromController() { return worldFromController; }

    /** Angular momentum has one conditional lifetime: Elytra dynamics. */
    public record ElytraDynamicsSeed(Vec3d angularVelocityWorld) {
        public ElytraDynamicsSeed {
            Objects.requireNonNull(angularVelocityWorld, "angularVelocityWorld");
            requireFinite(angularVelocityWorld, "angularVelocityWorld");
        }

    }

    private static Quatd normalized(
            Quatd value,
            String name
    ) {
        Objects.requireNonNull(value, name);

        double lengthSquared = value.lengthSquared();

        if (!Double.isFinite(value.x())
                || !Double.isFinite(value.y())
                || !Double.isFinite(value.z())
                || !Double.isFinite(value.w())
                || !Double.isFinite(lengthSquared)
                || lengthSquared <= MIN_QUATERNION_LENGTH_SQUARED) {
            throw new IllegalArgumentException(
                    name + " must be finite and non-degenerate"
            );
        }

        return value.normalized();
    }

    private static void requireFinite(
            Vec3d value,
            String name
    ) {
        if (!Double.isFinite(value.x())
                || !Double.isFinite(value.y())
                || !Double.isFinite(value.z())
                || !Double.isFinite(value.lengthSquared())) {
            throw new IllegalArgumentException(
                    name + " must be finite"
            );
        }
    }
}
