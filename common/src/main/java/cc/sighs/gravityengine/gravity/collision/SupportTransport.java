package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;

import java.util.Objects;
import java.util.Optional;

/**
 * Engine-owned transport of one persistent support over one captured
 * operation interval.
 *
 * <p>The endpoint displacement is sufficient for pure translation. A rotating
 * rigid support additionally carries the exact material-point trajectory so
 * the character route does not collapse the whole support path to one chord.</p>
 */
public record SupportTransport(
        Vec3d displacement,
        Vec3d startSurfaceVelocity,
        Vec3d endSurfaceVelocity,
        Optional<SupportMotionTrajectory> trajectory,
        double intervalTicks,
        long motionRevision,
        long gameTick
) {
    public SupportTransport {
        Objects.requireNonNull(
                displacement,
                "displacement"
        );
        Objects.requireNonNull(
                startSurfaceVelocity,
                "startSurfaceVelocity"
        );
        Objects.requireNonNull(
                endSurfaceVelocity,
                "endSurfaceVelocity"
        );
        Objects.requireNonNull(
                trajectory,
                "trajectory"
        );

        requireFinite(
                displacement,
                "displacement"
        );
        requireFinite(
                startSurfaceVelocity,
                "startSurfaceVelocity"
        );
        requireFinite(
                endSurfaceVelocity,
                "endSurfaceVelocity"
        );

        trajectory =
                trajectory.map(
                        Objects::requireNonNull
                );

        if (!Double.isFinite(intervalTicks)
                || intervalTicks <= 0.0D) {
            throw new IllegalArgumentException(
                    "intervalTicks must be finite and positive: "
                            + intervalTicks
            );
        }

        if (motionRevision < 0L) {
            throw new IllegalArgumentException(
                    "motionRevision must be non-negative: "
                            + motionRevision
            );
        }

        if (gameTick < 0L) {
            throw new IllegalArgumentException(
                    "gameTick must be non-negative: "
                            + gameTick
            );
        }
    }

    /**
     * Compatibility constructor for callers that have a constant surface
     * velocity over the interval.
     */
    public SupportTransport(
            Vec3d displacement,
            Vec3d surfaceVelocity,
            double intervalTicks,
            long motionRevision,
            long gameTick
    ) {
        this(
                displacement,
                surfaceVelocity,
                surfaceVelocity,
                Optional.empty(),
                intervalTicks,
                motionRevision,
                gameTick
        );
    }

    public SupportTransport(
            Vec3d displacement,
            Vec3d startSurfaceVelocity,
            Vec3d endSurfaceVelocity,
            double intervalTicks,
            long motionRevision,
            long gameTick
    ) {
        this(
                displacement,
                startSurfaceVelocity,
                endSurfaceVelocity,
                Optional.empty(),
                intervalTicks,
                motionRevision,
                gameTick
        );
    }

    /**
     * Legacy accessor.
     *
     * <p>The old SupportTransport represented the material-point velocity at
     * the transported/end anchor, so preserve that meaning for source
     * compatibility. New physics code should explicitly choose start or end.</p>
     */
    public Vec3d surfaceVelocity() {
        return endSurfaceVelocity;
    }

    public static SupportTransport resting(
            double intervalTicks,
            long motionRevision,
            long gameTick
    ) {
        return new SupportTransport(
                Vec3d.ZERO,
                Vec3d.ZERO,
                Vec3d.ZERO,
                Optional.empty(),
                intervalTicks,
                motionRevision,
                gameTick
        );
    }

    public boolean moving() {
        return displacement.lengthSquared()
                > 0.0D
                || startSurfaceVelocity.lengthSquared()
                > 0.0D
                || endSurfaceVelocity.lengthSquared()
                > 0.0D;
    }

    public boolean hasRotationalTrajectory() {
        return trajectory
                .map(SupportMotionTrajectory::rotating)
                .orElse(false);
    }

    private static void requireFinite(
            Vec3d value,
            String name
    ) {
        if (!value.isFinite()) {
            throw new IllegalArgumentException(
                    name + " must be finite: "
                            + value
            );
        }
    }
}