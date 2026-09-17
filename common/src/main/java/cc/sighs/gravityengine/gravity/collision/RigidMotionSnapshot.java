package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.Quatd;
import java.util.Objects;

/** Immutable rigid publication with constant twist or shortest-arc normalized-linear rotation. No retained mutable vectors or producer callbacks.
 * Times are normalized fractions of intervalTicks from the operation origin. Linear
 * displacement is blocks/interval; angular displacement is a WORLD rotation vector in
 * radians/interval (not a shortest-arc endpoint quaternion). Point velocity is blocks/TICK.
 * Source/body identity is paired with this publication by DynamicCollisionObstacleSnapshot.
 * Revision identifies the publication; continuityEpoch changes on replacement/discontinuity. */
public record RigidMotionSnapshot(cc.sighs.gravityengine.math.geometry.RigidPose start, double dx, double dy, double dz,
                                  double ax, double ay, double az, long tick, long revision, long continuityEpoch,
                                  double intervalTicks, boolean normalizedLinearRotation) {
    public RigidMotionSnapshot(cc.sighs.gravityengine.math.geometry.RigidPose start,
            double dx, double dy, double dz, double ax, double ay, double az,
            long tick, long revision, long continuityEpoch, double intervalTicks) {
        this(start, dx, dy, dz, ax, ay, az, tick, revision, continuityEpoch, intervalTicks, false);
    }

    public RigidMotionSnapshot {
        Objects.requireNonNull(start, "start");
        if (!new Vec3d(dx, dy, dz).isFinite() || !new Vec3d(ax, ay, az).isFinite()
                || !Double.isFinite(new Vec3d(dx, dy, dz).length())
                || !Double.isFinite(new Vec3d(ax, ay, az).length())
                || !Double.isFinite(intervalTicks) || intervalTicks <= 0 || revision < 0 || continuityEpoch < 0
                || !new Vec3d(dx, dy, dz).divide(intervalTicks).isFinite()
                || !new Vec3d(ax, ay, az).divide(intervalTicks).isFinite())
            throw new IllegalArgumentException("invalid rigid publication");
        if (normalizedLinearRotation && new Vec3d(ax, ay, az).length() > Math.PI + 1e-12)
            throw new IllegalArgumentException("normalized linear rotation requires a shortest arc");
    }
    public RigidMotionSnapshot(cc.sighs.gravityengine.math.geometry.RigidPose start, Vec3d displacement, Vec3d angularDisplacement,
                               long tick, long revision, long continuityEpoch, double intervalTicks) {
        this(start, displacement.x(), displacement.y(), displacement.z(), angularDisplacement.x(),
                angularDisplacement.y(), angularDisplacement.z(), tick, revision, continuityEpoch, intervalTicks);
    }
    public Vec3d displacementForInterval() { return new Vec3d(dx, dy, dz); }
    public boolean rotating() { return ax != 0 || ay != 0 || az != 0; }
    public boolean moving() { return rotating() || dx != 0 || dy != 0 || dz != 0; }
    public cc.sighs.gravityengine.math.geometry.RigidPose poseAt(double t) {
        requireTime(t);
        if (t == 0) return start;
        Quatd q = cc.sighs.gravityengine.math.geometry.BodyOrientation3d.quaternion(start.orientation());
        if (rotating()) {
            double angle = new Vec3d(ax, ay, az).length();
            Quatd delta = Quatd.fromAxisAngle(
                    new Vec3d(ax / angle, ay / angle, az / angle),
                    rotationAngleAt(t)
            );
            q = delta.multiply(q);
        }
        return new cc.sighs.gravityengine.math.geometry.RigidPose(start.center().fma(t, displacementForInterval()),
                rotating() ? cc.sighs.gravityengine.math.geometry.BodyOrientation3d.frame(q) : start.orientation());
    }
    public Vec3d velocityAt(Vec3d worldPoint, double t) {
        Vec3d radius = worldPoint.subtract(poseAt(t).center());
        return new Vec3d(ax, ay, az).multiply(angularRateFactor(t)).cross(radius).add(displacementForInterval()).divide(intervalTicks);
    }
    public OrientedBox bodyAt(OrientedBox local, double t) {
        cc.sighs.gravityengine.math.geometry.RigidPose pose = poseAt(t);
        var orientation = cc.sighs.gravityengine.math.geometry.BodyOrientation3d.quaternion(pose.orientation())
                .multiply(cc.sighs.gravityengine.math.geometry.BodyOrientation3d.quaternion(local.orientation()));
        return new OrientedBox(pose.transformPoint(local.center()), local.halfExtents(),
                start.orientation().equals(cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d.IDENTITY) && !rotating()
                        ? local.orientation() : cc.sighs.gravityengine.math.geometry.BodyOrientation3d.frame(orientation));
    }
    /** Exact extrema enclosure for arcs <= pi, spherical enclosure for longer arcs.
     * Refinement narrows the angular interval; no fixed motion substeps are used. */
    public OrientedBox envelope(OrientedBox local, double lo, double hi) {
        return envelope(local, lo, hi, Vec3d.ZERO);
    }
    /** Proof enclosure in a translating observer's coordinates. Subtracting the
     * observer's interval displacement preserves relative-time correlation at
     * initially touching/outward contacts. It does not change the publication. */
    OrientedBox envelope(OrientedBox local, double lo, double hi, Vec3d observerDisplacement) {
        requireTime(lo); requireTime(hi);
        if (hi < lo) throw new IllegalArgumentException("reversed rigid interval");
        OrientedBox first = bodyAt(local, lo);
        if (hi == lo) return first;
        Vec3d translation = displacementForInterval().multiply(hi - lo).subtract(observerDisplacement);
        OrientedBox rotation = first;
        double angle = normalizedLinearRotation
                ? rotationAngleAt(hi) - rotationAngleAt(lo)
                : new Vec3d(ax, ay, az).length() * (hi - lo);
        if (angle > Math.PI) {
            double radius = local.center().length() + local.halfExtents().length();
            rotation = new OrientedBox(poseAt(lo).center(), radius, cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d.IDENTITY);
        } else if (rotating()) {
            Vec3d axis = new Vec3d(ax, ay, az).normalized();
            Quatd target = Quatd.fromAxisAngle(axis, angle)
                    .multiply(cc.sighs.gravityengine.math.geometry.BodyOrientation3d.quaternion(first.orientation()));
            var pivot = ObbAngularTrajectory.Pivot.at(first, poseAt(lo).center(), first.orientation());
            rotation = new ObbAngularTrajectory(first, target, pivot).envelope(0, 1);
        }
        Vec3d halfTranslation = rotation
                .worldVectorToLocal(translation)
                .abs()
                .multiply(.5);
        return new OrientedBox(rotation.center().fma(.5, translation),
                rotation.halfExtents().add(halfTranslation), rotation.orientation());
    }
    public cc.sighs.gravityengine.math.geometry.Aabb3d sweptBounds(OrientedBox local, double lo, double hi) {
        requireTime(lo); requireTime(hi);
        if (hi < lo) throw new IllegalArgumentException("reversed rigid interval");
        if (!rotating()) return bodyAt(local, lo).enclosingAabb()
                .expandTowards(displacementForInterval().multiply(hi - lo));
        return envelope(local, lo, hi).enclosingAabb();
    }
    /** Center translation over an explicit normalized interval. Keeps the
     * translation fast path's multiplication order without subtracting two large world positions. */
    public Vec3d centerDisplacement(double lo, double hi) {
        requireTime(lo); requireTime(hi);
        if (hi < lo) throw new IllegalArgumentException("reversed rigid interval");
        return displacementForInterval().multiply(hi - lo);
    }
    /** Conservative maximum displacement of ANY material point from its initial position. */
    public double maximumPointDisplacement(OrientedBox local) {
        double angle = new Vec3d(ax, ay, az).length();
        return displacementForInterval().length() + (local.center().length() + local.halfExtents().length())
                * (angle >= Math.PI ? 2 : 2 * Math.sin(angle * .5));
    }
    /** Conservative material-point speed in blocks/tick, used to bound CCD brackets. */
    public double maximumPointSpeed(OrientedBox local) {
        return (displacementForInterval().length() + maximumAngularRate()
                * (local.center().length() + local.halfExtents().length())) / intervalTicks;
    }
    /** Angle along the published interpolation profile at normalized time. */
    public double rotationAngleAt(double t) {
        requireTime(t);
        double angle = new Vec3d(ax, ay, az).length();
        if (!normalizedLinearRotation || angle == 0) return angle * t;
        return 2 * Math.atan2(t * Math.sin(angle * .5), 1 - t + t * Math.cos(angle * .5));
    }

    private double angularRateFactor(double t) {
        if (!normalizedLinearRotation) return 1;
        double angle = new Vec3d(ax, ay, az).length();
        if (angle == 0) return 1;
        double sin = Math.sin(angle * .5);
        double x = 1 - t + t * Math.cos(angle * .5);
        double y = t * sin;
        return 2 * sin / (angle * (x * x + y * y));
    }

    /** Conservative angular rate in radians per normalized interval. */
    public double maximumAngularRate() {
        double angle = new Vec3d(ax, ay, az).length();
        return normalizedLinearRotation ? 4 * Math.tan(angle * .25) : angle;
    }

    private static void requireTime(double t) {
        if (!Double.isFinite(t) || t < 0 || t > 1)
            throw new CollisionSceneCoverageException("rigid publication does not cover time " + t);
    }
}
