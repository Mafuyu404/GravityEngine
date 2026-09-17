package cc.sighs.gravityengine.attitude;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.math.Quatd;
import java.util.Objects;

/** Pure quaternion/vector operations shared by the body-attitude controller. */
public final class BodyAttitudeMath {
    private BodyAttitudeMath() {}

    /** Shortest-arc world-space rotation vector for desired * inverse(current). */
    public static Vec3d rotationError(
            Quatd desiredWorldFromBody,
            Quatd currentWorldFromBody,
            double epsilon
    ) {
        Quatd desired = normalized(desiredWorldFromBody, epsilon);
        Quatd current = normalized(currentWorldFromBody, epsilon);
        Quatd error = desired
                .multiply(current.conjugate())
                .normalized();
        if (error.w() < 0.0D) {
            error = new Quatd(
                    -error.x(),
                    -error.y(),
                    -error.z(),
                    -error.w()
            );
        }
        double vectorLength = Math.sqrt(error.x() * error.x()
                + error.y() * error.y() + error.z() * error.z());
        if (vectorLength <= epsilon) {
            return Vec3d.ZERO;
        }
        double angle = 2.0D * Math.atan2(vectorLength, clamp(error.w(), 0.0D, 1.0D));
        return new Vec3d(error.x(), error.y(), error.z()).multiply(angle / vectorLength);
    }

    public static Vec3d bodyUp(Quatd worldFromBody) {
        return AttitudeSpaceTransform.bodyUpWorld(worldFromBody);
    }

    public static Vec3d bodyForward(Quatd worldFromBody) {
        return AttitudeSpaceTransform.bodyForwardWorld(worldFromBody);
    }

    static Vec3d elytraDesiredForward(
            Quatd current,
            GravityFrame frame,
            Vec3d lookForwardWorld,
            Vec3d velocityWorld,
            BodyAttitudeConfigSnapshot config
    ) {
        double epsilon = config.vectorEpsilon();

        /*
         * Qbody remains the canonical humanoid/root pose. Elytra dynamics
         * consumes the derived flight longitudinal axis, not humanoid +Z.
         */
        Vec3d bodyForward =
                AttitudeSpaceTransform.elytraForwardWorld(current);

        Vec3d look = normalizedOrNull(lookForwardWorld, epsilon);
        if (look == null) {
            look = bodyForward;
        }

        double speed = magnitude(velocityWorld);
        double velocityWeight =
                config.elytraVelocityAlignment()
                        * smoothstep(
                        config.elytraVelocityAlignStartSpeed(),
                        config.elytraVelocityAlignFullSpeed(),
                        speed
                );

        Vec3d velocityDirection =
                normalizedOrNull(velocityWorld, epsilon);

        Vec3d blended =
                velocityDirection == null
                        ? look
                        : look.multiply(1.0D - velocityWeight)
                        .add(velocityDirection.multiply(velocityWeight));

        Vec3d desiredForward =
                normalizedOrNull(blended, epsilon);

        if (desiredForward == null) {
            desiredForward = normalizedOrNull(look, epsilon);
        }

        if (desiredForward == null) {
            desiredForward = normalizedOrNull(bodyForward, epsilon);
        }

        if (desiredForward == null) {
            desiredForward = normalizeOrFallback(
                    frame.forward(),
                    AttitudeSpaceTransform.BODY_FORWARD,
                    epsilon
            );
        }

        Vec3d down = frame.down();

        Vec3d tangent =
                projectedUnit(desiredForward, down, epsilon);

        if (tangent == null) {
            tangent = projectedUnit(bodyForward, down, epsilon);
        }

        if (tangent == null) {
            tangent = deterministicPerpendicular(down, epsilon);
        }

        /*
         * A retained GravityFrame direction at zero gravity must not invent a
         * physical flight plane. Tangent authority grows smoothly and reaches
         * full strength at Vanilla gravity.
         */
        double gravityAuthority =
                smoothstep(
                        0.0D,
                        cc.sighs.gravityengine.gravity.GravityState.VANILLA_STRENGTH,
                        Math.max(0.0D, frame.strength())
                );

        return normalizeOrFallback(
                desiredForward.multiply(1.0D - gravityAuthority)
                        .add(tangent.multiply(gravityAuthority)),
                desiredForward,
                epsilon
        );
    }

    /** Shortest world-space direction rotation, in radians. At pi choose a stable
     * body-derived perpendicular axis, then a deterministic canonical fallback. */
    static Vec3d directionRotationError(Vec3d from, Vec3d to, Vec3d fallbackAxis, double epsilon) {
        Vec3d a = normalizeOrFallback(from, AttitudeSpaceTransform.BODY_UP, epsilon);
        Vec3d b = normalizeOrFallback(to, a, epsilon);
        Vec3d cross = a.cross(b);
        double sine = cross.length();
        double cosine = clamp(a.dot(b), -1, 1);
        double angle = Math.atan2(sine, cosine);
        if (sine > epsilon) return cross.multiply(angle / sine);
        if (cosine >= 0) return Vec3d.ZERO;
        Vec3d axis = projectedUnit(fallbackAxis, a, epsilon);
        if (axis == null) axis = deterministicPerpendicular(a, epsilon);
        return axis.multiply(angle);
    }

    static Quatd integrateWorldAngularVelocity(
            Quatd current,
            Vec3d angularVelocityWorld,
            double dtSeconds,
            double epsilon
    ) {
        double speed = angularVelocityWorld.length();
        if (speed <= epsilon) {
            return current.normalized();
        }
        double angle = speed * dtSeconds;
        Vec3d axis = angularVelocityWorld.multiply(1.0D / speed);
        double halfAngle = angle * 0.5D;
        double sine = Math.sin(halfAngle);
        Quatd delta = new Quatd(
                axis.x() * sine, axis.y() * sine, axis.z() * sine, Math.cos(halfAngle)
        );
        // Angular velocity is world-space, so the increment pre-multiplies q.
        return delta.multiply(current).normalized();
    }

    static Vec3d clampMagnitude(Vec3d value, double maximum) {
        double length = magnitude(value);
        if (length <= maximum) return value;
        return value.multiply(maximum / length);
    }

    private static Vec3d deterministicPerpendicular(Vec3d normal, double epsilon) {
        Vec3d[] candidates = {AttitudeSpaceTransform.BODY_LEFT, AttitudeSpaceTransform.BODY_UP, AttitudeSpaceTransform.BODY_FORWARD};
        Vec3d best = candidates[0];
        double bestDot = Math.abs(normal.dot(best));
        for (int i = 1; i < candidates.length; i++) {
            double dot = Math.abs(normal.dot(candidates[i]));
            if (dot < bestDot) {
                best = candidates[i];
                bestDot = dot;
            }
        }
        Vec3d projected = projectedUnit(best, normal, epsilon);
        if (projected == null) {
            throw new IllegalArgumentException("cannot construct perpendicular axis");
        }
        return projected;
    }

    private static Vec3d projectedUnit(Vec3d vector, Vec3d planeNormal, double epsilon) {
        return AttitudeSpaceTransform.projectedUnit(
                vector, planeNormal, epsilon);
    }

    private static Vec3d normalizedOrNull(Vec3d vector, double epsilon) {
        if (vector == null || !Double.isFinite(vector.x()) || !Double.isFinite(vector.y())
                || !Double.isFinite(vector.z())) return null;
        double length = magnitude(vector);
        if (!Double.isFinite(length) || length <= epsilon) return null;
        return vector.multiply(1.0D / length);
    }

    private static boolean isUsable(Vec3d vector, double epsilon) {
        return normalizedOrNull(vector, epsilon) != null;
    }

    private static Vec3d normalizeOrFallback(Vec3d value, Vec3d fallback, double epsilon) {
        Vec3d normalized = normalizedOrNull(value, epsilon);
        if (normalized != null) return normalized;
        normalized = normalizedOrNull(fallback, epsilon);
        if (normalized == null) throw new IllegalArgumentException("fallback vector is degenerate");
        return normalized;
    }

    private static Quatd normalized(Quatd value, double epsilon) {
        Objects.requireNonNull(value, "quaternion");
        double lengthSquared = value.lengthSquared();
        if (!Double.isFinite(value.x()) || !Double.isFinite(value.y())
                || !Double.isFinite(value.z()) || !Double.isFinite(value.w())
                || !Double.isFinite(lengthSquared) || lengthSquared <= epsilon * epsilon) {
            throw new IllegalArgumentException("quaternion must be finite and non-degenerate");
        }
        return value.normalized();
    }

    private static Vec3d rotate(Quatd quaternion, Vec3d vector) {
        Quatd q = normalized(quaternion, 1.0E-12D);
        return q.transform(vector);
    }

    private static double smoothstep(double start, double end, double value) {
        double t = clamp((value - start) / (end - start), 0.0D, 1.0D);
        return t * t * (3.0D - 2.0D * t);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double magnitude(Vec3d vector) {
        return Math.hypot(Math.hypot(vector.x(), vector.y()), vector.z());
    }
}
