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
 * <p>{@code referenceFrame} is environmental/control evidence, including its
 * complete tangent orientation. It is not the installed collision orientation:
 * a blocked collider rotation can leave the two up axes different. A coherent
 * evaluation supplies the frame; otherwise an active committed reference does.
 * No active reference or evaluation means the optional frame is empty.</p>
 *
 * <p>{@code fieldPresence} is contribution identity, not magnitude: it is
 * {@link FieldPresence#PRESENT} while FIELD authority has an active field
 * contribution, including a contribution that sums to a zero vector. It is
 * {@link FieldPresence#UNKNOWN} while FIELD reconciliation is unresolved, and
 * {@link FieldPresence#ABSENT} only when FIELD absence is authoritative.
 * {@link #fieldContributionPresent()} is the binary compatibility view and is
 * true only for {@link FieldPresence#PRESENT}.</p>
 */
public record EntityGravitySnapshot(
        GravityAuthority authority,
        Vec3d effectiveDirection,
        double effectiveStrength,
        Vec3d appliedDirection,
        double appliedStrength,
        FieldPresence fieldPresence,
        long applicationEpoch,
        Optional<GravityFrameView> referenceFrame
) {
    public EntityGravitySnapshot {
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(effectiveDirection, "effectiveDirection");
        Objects.requireNonNull(appliedDirection, "appliedDirection");
        Objects.requireNonNull(fieldPresence, "fieldPresence");
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
     * Source-compatible constructor for the pre-{@link FieldPresence} API.
     *
     * <p>The previous public record component was the boolean
     * {@code fieldContributionPresent}. External consumers may instantiate the
     * snapshot directly, so changing the canonical component alone would be a
     * source/binary API break. This overload maps the old binary contract
     * deliberately: {@code true} was confirmed presence and {@code false} was
     * confirmed absence. It never maps {@code false} to
     * {@link FieldPresence#UNKNOWN}, which did not exist in that contract.</p>
     */
    public EntityGravitySnapshot(
            GravityAuthority authority,
            Vec3d effectiveDirection,
            double effectiveStrength,
            Vec3d appliedDirection,
            double appliedStrength,
            boolean fieldContributionPresent,
            long applicationEpoch,
            Optional<GravityFrameView> referenceFrame
    ) {
        this(
                authority,
                effectiveDirection,
                effectiveStrength,
                appliedDirection,
                appliedStrength,
                fieldContributionPresent
                        ? FieldPresence.PRESENT
                        : FieldPresence.ABSENT,
                applicationEpoch,
                referenceFrame
        );
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

    /**
     * Binary compatibility view of {@link #fieldPresence()}.
     *
     * <p>{@code true} only for {@link FieldPresence#PRESENT}. An unresolved
     * reconciliation reports {@code false} here and must not be read as
     * confirmed absence; use {@link #fieldPresence()} for that distinction.</p>
     */
    public boolean fieldContributionPresent() {
        return fieldPresence == FieldPresence.PRESENT;
    }

    private static void requireFinite(Vec3d value, String name) {
        if (!value.isFinite()) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + value
            );
        }
    }
}
