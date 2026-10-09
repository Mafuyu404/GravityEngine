package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import cc.sighs.gravityengine.math.geometry.RigidPose;

/** Internal immutable motion evidence. Times are normalized to one captured
 * interval; velocities are blocks/tick. No implementation owns live physics.
 * A nonrotating trajectory must have linear translation for the solver fast path. */
public sealed interface RigidTrajectory permits RigidMotionSnapshot, ComposedRigidTrajectory, SampledRigidTrajectory {
    long tick();
    long revision();
    long continuityEpoch();
    double intervalTicks();
    boolean rotating();
    boolean moving();
    RigidPose poseAt(double time);
    default RigidPose start() { return poseAt(0); }
    Vec3d velocityAt(Vec3d worldPoint, double time);
    OrientedBox bodyAt(OrientedBox local, double time);
    OrientedBox envelope(OrientedBox local, double lo, double hi, Vec3d observerDisplacement);
    default OrientedBox envelope(OrientedBox local, double lo, double hi) {
        return envelope(local, lo, hi, Vec3d.ZERO);
    }
    default Aabb3d sweptBounds(OrientedBox local, double lo, double hi) {
        return envelope(local, lo, hi).enclosingAabb();
    }
    Vec3d centerDisplacement(double lo, double hi);
    default Vec3d displacementForInterval() { return centerDisplacement(0, 1); }
    double maximumPointDisplacement(OrientedBox local);
    double maximumPointSpeed(OrientedBox local);
    /** Radians per normalized interval. */
    double maximumAngularRate();
    /** Bound on the second derivative of any material point with respect to
     * normalized time, in blocks/interval squared. Used for chord error proofs. */
    double maximumPointAcceleration(OrientedBox local);
    /** Optional proof of one invariant world rotation axis plus linear pivot
     * translation. Static coordinate rebasing preserves this proof. */
    default RigidMotionSnapshot invariantAxisMotion() { return null; }
    /** Uniform subdivision must include these captured velocity discontinuities. */
    default int subdivisionMultiple() { return 1; }
}
