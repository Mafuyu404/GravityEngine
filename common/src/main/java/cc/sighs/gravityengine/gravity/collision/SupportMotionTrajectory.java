package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.geometry.Aabb3d;

import java.util.Objects;

/**
 * Exact rigid trajectory of one persistent support material point.
 *
 * <p>The support anchor is stored in obstacle-local coordinates. Its world
 * position is therefore always derived from the same immutable
 * RigidMotionSnapshot that owns the obstacle motion.</p>
 */
public record SupportMotionTrajectory(
        RigidMotionSnapshot motion,
        Vec3d localAnchor
) {
    /*
     * Maximum geometric sagitta of one linearized material-point segment.
     * This is an approximation error bound, not a collision epsilon.
     */
    public static final double DEFAULT_MAX_SAGITTA =
            1.0E-4D;

    public SupportMotionTrajectory {
        Objects.requireNonNull(
                motion,
                "motion"
        );
        Objects.requireNonNull(
                localAnchor,
                "localAnchor"
        );

        if (!localAnchor.isFinite()) {
            throw new IllegalArgumentException(
                    "localAnchor must be finite: "
                            + localAnchor
            );
        }
    }

    public Vec3d positionAt(
            double normalizedTime
    ) {
        return motion.poseAt(
                normalizedTime
        ).transformPoint(
                localAnchor
        );
    }

    public Vec3d displacement(
            double normalizedStart,
            double normalizedEnd
    ) {
        return positionAt(normalizedEnd)
                .subtract(
                        positionAt(normalizedStart)
                );
    }

    public Vec3d velocityAt(
            double normalizedTime
    ) {
        Vec3d point =
                positionAt(normalizedTime);

        return motion.velocityAt(
                point,
                normalizedTime
        );
    }

    public boolean rotating() {
        return motion.rotating();
    }

    /**
     * Conservative world-space bounds for the complete material-point
     * trajectory over {@code [0,1]}.
     *
     * <p>The translation component is linear, so the path is contained by the
     * endpoint segment inflated by the maximum rotational excursion.</p>
     */
    public Aabb3d bounds() {
        Vec3d start = positionAt(0.0D);
        Aabb3d relative = relativeBounds();
        return new Aabb3d(
                start.x() + relative.minX(),
                start.y() + relative.minY(),
                start.z() + relative.minZ(),
                start.x() + relative.maxX(),
                start.y() + relative.maxY(),
                start.z() + relative.maxZ()
        );
    }

    /**
     * Bounds of {@code positionAt(t) - positionAt(0)} over {@code [0,1]}.
     *
     * <p>This is the capture-domain contribution for a support transport whose
     * actor-relative request is sampled independently from the material-point
     * path.</p>
     */
    public Aabb3d relativeBounds() {
        Vec3d start = positionAt(0.0D);
        Vec3d end = positionAt(1.0D);
        Vec3d displacement = end.subtract(start);
        double excursion = maximumRotationalExcursion();
        return new Aabb3d(
                Math.min(0.0D, displacement.x()) - excursion,
                Math.min(0.0D, displacement.y()) - excursion,
                Math.min(0.0D, displacement.z()) - excursion,
                Math.max(0.0D, displacement.x()) + excursion,
                Math.max(0.0D, displacement.y()) + excursion,
                Math.max(0.0D, displacement.z()) + excursion
        );
    }

    private double maximumRotationalExcursion() {
        if (!motion.rotating()) {
            return 0.0D;
        }
        double radius = localAnchor.length();
        double angle = new Vec3d(
                motion.ax(),
                motion.ay(),
                motion.az()
        ).length();
        if (radius <= 1.0E-12D || angle <= 1.0E-12D) {
            return 0.0D;
        }
        return radius * (angle >= Math.PI
                ? 2.0D
                : 2.0D * Math.sin(angle * 0.5D));
    }

    public int requiredSegments() {
        return requiredSegments(
                DEFAULT_MAX_SAGITTA
        );
    }

    /**
     * Conservative chord subdivision count for the circular component of this
     * rigid material-point trajectory.
     */
    public int requiredSegments(
            double maxSagitta
    ) {
        if (!Double.isFinite(maxSagitta)
                || maxSagitta <= 0.0D) {
            throw new IllegalArgumentException(
                    "maxSagitta must be finite and positive"
            );
        }

        if (!motion.rotating()) {
            return 1;
        }

        double angle =
                new Vec3d(
                        motion.ax(),
                        motion.ay(),
                        motion.az()
                ).length();

        double radius =
                localAnchor.length();

        if (angle <= 1.0E-12D
                || radius <= 1.0E-12D) {
            return 1;
        }

        /*
         * sagitta = r * (1 - cos(theta / 2))
         */
        double ratio =
                Math.min(
                        2.0D,
                        maxSagitta / radius
                );

        double cosHalf =
                Math.max(
                        -1.0D,
                        Math.min(
                                1.0D,
                                1.0D - ratio
                        )
                );

        double maxSegmentAngle =
                2.0D
                        * Math.acos(cosHalf);

        if (!(maxSegmentAngle > 1.0E-12D)) {
            return Integer.MAX_VALUE;
        }

        double required =
                Math.ceil(
                        motion.maximumAngularRate() / maxSegmentAngle
                );

        return required >= Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : Math.max(
                1,
                (int) required
        );
    }
}
