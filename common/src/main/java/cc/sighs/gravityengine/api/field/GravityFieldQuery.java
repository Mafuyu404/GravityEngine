package cc.sighs.gravityengine.api.field;

import cc.sighs.gravityengine.api.math.Vec3d;
import java.util.Objects;

/**
 * Pure immutable input snapshot for one field evaluation.
 *
 * <p>{@code intervalTicks} may be zero only for position-only sampling;
 * physics callers supply the actual movement interval when the field needs
 * it.</p>
 *
 * <p>{@link Vec3d} is immutable, so the snapshot stores the exact value
 * supplied by the caller. Callers cannot mutate query state through either
 * accessor.</p>
 */
public record GravityFieldQuery(
        Vec3d position,
        Vec3d velocity,
        long gameTick,
        double intervalTicks
) {
    public GravityFieldQuery {
        Objects.requireNonNull(
                position,
                "position"
        );

        Objects.requireNonNull(
                velocity,
                "velocity"
        );

        requireFinite(
                position,
                "position"
        );

        requireFinite(
                velocity,
                "velocity"
        );

        if (gameTick < 0L) {
            throw new IllegalArgumentException(
                    "gameTick must be non-negative: "
                            + gameTick
            );
        }

        if (!Double.isFinite(intervalTicks)
                || intervalTicks < 0.0D) {

            throw new IllegalArgumentException(
                    "intervalTicks must be finite and non-negative: "
                            + intervalTicks
            );
        }
    }

    @Override
    public Vec3d position() {
        return position;
    }

    @Override
    public Vec3d velocity() {
        return velocity;
    }

    public static GravityFieldQuery at(
            Vec3d position
    ) {
        Objects.requireNonNull(position, "position");

        return new GravityFieldQuery(
                position,
                Vec3d.ZERO,
                0L,
                0.0D
        );
    }

    private static void requireFinite(
            Vec3d vector,
            String name
    ) {
        if (!vector.isFinite()) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + vector
            );
        }
    }
}
