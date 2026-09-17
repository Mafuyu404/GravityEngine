package cc.sighs.gravityengine.attitude.runtime;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.Quatd;
import java.util.Objects;

/** Explicit displayed-orientation sample used to initialize attitude state. */
public final class BodyAttitudeBootstrap {
    private static final double MIN_LENGTH_SQUARED = 1.0E-24D;

    private final Quatd worldFromBody;
    private final Vec3d angularVelocityWorld;

    public BodyAttitudeBootstrap(
            Quatd worldFromBody,
            Vec3d angularVelocityWorld
    ) {
        Objects.requireNonNull(worldFromBody, "worldFromBody");
        Objects.requireNonNull(angularVelocityWorld, "angularVelocityWorld");
        double lengthSquared = worldFromBody.lengthSquared();
        if (!worldFromBody.isFinite()
                || !Double.isFinite(lengthSquared)
                || lengthSquared <= MIN_LENGTH_SQUARED) {
            throw new IllegalArgumentException(
                    "worldFromBody must be finite and non-degenerate");
        }
        if (!angularVelocityWorld.isFinite()
                || !Double.isFinite(
                        angularVelocityWorld.lengthSquared())) {
            throw new IllegalArgumentException(
                    "angularVelocityWorld must be finite");
        }
        this.worldFromBody = new Quatd(worldFromBody).normalized();
        this.angularVelocityWorld = angularVelocityWorld;
    }

    /** Immutable GravityEngine-owned orientation value. */
    public Quatd worldFromBody() {
        return this.worldFromBody;
    }

    public Vec3d angularVelocityWorld() {
        return this.angularVelocityWorld;
    }

    public static BodyAttitudeBootstrap stationary(Quatd worldFromBody) {
        return new BodyAttitudeBootstrap(worldFromBody, Vec3d.ZERO);
    }
}
