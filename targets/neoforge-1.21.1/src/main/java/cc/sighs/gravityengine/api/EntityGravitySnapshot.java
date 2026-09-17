package cc.sighs.gravityengine.api;

import cc.sighs.gravityengine.api.math.Vec3d;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable read-only snapshot of one entity's gravity.
 *
 * <p>Two distinct pairs are reported:</p>
 * <ul>
 *     <li>{@code effective*} - the latest coherent runtime physical
 *     evaluation for the current committed application context. While an
 *     operation is active this is that operation's frozen evaluation; outside
 *     an operation it is the current tick evaluation only when the full
 *     authority/application/registry context still matches.</li>
 *     <li>{@code applied*} - the committed application state backing
 *     movement/body integration.</li>
 * </ul>
 *
 * <p>When no coherent runtime evaluation exists, {@code effective*} falls
 * back to persisted/assignment bootstrap state. That fallback is not a second
 * canonical runtime physical truth and may be replaced by the next coherent
 * tick evaluation.</p>
 *
 * <p>All vector values are immutable GravityEngine {@link Vec3d} values taken
 * at the moment the snapshot was created; the snapshot never observes later
 * engine state. It deliberately contains no setters, no pending-application
 * queue, no bootstrap flags, no NBT view, no collision transient state and no
 * synchronization internals.</p>
 *
 * <p>{@code fieldContributionPresent} is contribution identity, not
 * magnitude: it is true while FIELD authority has an active field
 * contribution, including a contribution that sums to a zero vector.</p>
 */
public record EntityGravitySnapshot(
        GravityAuthority authority,
        Vec3d effectiveDirection,
        double effectiveStrength,
        Vec3d appliedDirection,
        double appliedStrength,
        boolean fieldContributionPresent,
        long applicationEpoch,
        Optional<GravityFrameView> referenceFrame
) {
    public EntityGravitySnapshot {
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(effectiveDirection, "effectiveDirection");
        Objects.requireNonNull(appliedDirection, "appliedDirection");
        Objects.requireNonNull(referenceFrame, "referenceFrame");
        requireFinite(effectiveDirection, "effectiveDirection");
        requireFinite(appliedDirection, "appliedDirection");
        if (!Double.isFinite(effectiveStrength)) {
            throw new IllegalArgumentException(
                    "effectiveStrength must be finite: "
                            + effectiveStrength
            );
        }
        if (!Double.isFinite(appliedStrength)) {
            throw new IllegalArgumentException(
                    "appliedStrength must be finite: "
                            + appliedStrength
            );
        }
        if (applicationEpoch < 0L) {
            throw new IllegalArgumentException(
                    "applicationEpoch must be non-negative: "
                            + applicationEpoch
            );
        }
    }

    /**
     * Latest coherent evaluated physical gravity acceleration
     * ({@code effectiveDirection * effectiveStrength}).
     */
    public Vec3d effectiveAcceleration() {
        return effectiveDirection.multiply(effectiveStrength);
    }

    /**
     * Currently committed/applied gravity acceleration
     * ({@code appliedDirection * appliedStrength}).
     */
    public Vec3d appliedAcceleration() {
        return appliedDirection.multiply(appliedStrength);
    }

    private static void requireFinite(Vec3d value, String name) {
        if (!value.isFinite()) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + value
            );
        }
    }
}
