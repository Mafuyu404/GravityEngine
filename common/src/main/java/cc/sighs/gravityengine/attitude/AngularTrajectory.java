package cc.sighs.gravityengine.attitude;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.Quatd;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable actor-controller angular samples. Dynamic segments integrate angular velocity;
 * kinematic segments preserve angular velocity while changing the view/body joint.
 * These samples are independent from character collision and position. */
public record AngularTrajectory(
        Vec3d initialAngularVelocityWorld,
        List<AngularSegment> segments
) {
    public enum Kind {
        DYNAMIC,
        KINEMATIC_GEOMETRIC
    }

    /** One angular segment with its actual integration duration and endpoint angular velocity. */
    public record AngularSegment(
            Quatd start,
            Quatd end,
            Kind kind,
            double durationSeconds,
            Vec3d endAngularVelocityWorld
    ) {
        public AngularSegment {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(endAngularVelocityWorld,
                    "endAngularVelocityWorld");
            start = normalizedCopy(start, "start");
            end = normalizedCopy(end, "end");
            if (!Double.isFinite(durationSeconds)
                    || durationSeconds < 0.0D) {
                throw new IllegalArgumentException(
                        "durationSeconds must be finite and non-negative");
            }
            if (kind == Kind.KINEMATIC_GEOMETRIC
                    && durationSeconds != 0.0D) {
                throw new IllegalArgumentException(
                        "kinematic geometric segments cannot carry an "
                                + "artificial integration duration");
            }
            requireFinite(endAngularVelocityWorld,
                    "endAngularVelocityWorld");
        }

        @Override
        public Quatd start() {
            return start;
        }

        @Override
        public Quatd end() {
            return end;
        }

        @Override
        public Vec3d endAngularVelocityWorld() {
            return copy(endAngularVelocityWorld);
        }
    }

    public AngularTrajectory {
        Objects.requireNonNull(initialAngularVelocityWorld,
                "initialAngularVelocityWorld");
        requireFinite(initialAngularVelocityWorld,
                "initialAngularVelocityWorld");
        Objects.requireNonNull(segments, "segments");
        if (segments.isEmpty()) {
            throw new IllegalArgumentException(
                    "trajectory requires at least one segment");
        }

        List<AngularSegment> copied = new ArrayList<>(segments.size());
        for (AngularSegment segment : segments) {
            AngularSegment required = Objects.requireNonNull(
                    segment, "segment");
            copied.add(new AngularSegment(
                    required.start(),
                    required.end(),
                    required.kind(),
                    required.durationSeconds(),
                    required.endAngularVelocityWorld()
            ));
        }
        for (int index = 1; index < copied.size(); index++) {
            AngularSegment previous = copied.get(index - 1);
            AngularSegment current = copied.get(index);
            if (!sameRotation(previous.end(), current.start())) {
                throw new IllegalArgumentException(
                        "trajectory segments must be contiguous at index "
                                + index);
            }
        }
        /*
         * Producer-owned segment metadata must stay internally consistent.
         * A KINEMATIC_GEOMETRIC segment is a zero-momentum orientation
         * transition, so its endpoint omega is exactly the omega entering the
         * segment. No collision response or Player translation is derived from this value.
         */
        Vec3d incomingOmega = initialAngularVelocityWorld;
        for (AngularSegment segment : copied) {
            if (segment.kind() == Kind.KINEMATIC_GEOMETRIC
                    && !segment.endAngularVelocityWorld()
                    .equals(incomingOmega)) {
                throw new IllegalArgumentException(
                        "KINEMATIC_GEOMETRIC segments must preserve the "
                                + "incoming angular velocity");
            }
            incomingOmega = segment.endAngularVelocityWorld();
        }
        initialAngularVelocityWorld = copy(initialAngularVelocityWorld);
        segments = List.copyOf(copied);
    }

    @Override
    public List<AngularSegment> segments() {
        return segments.stream()
                .map(segment -> new AngularSegment(
                        segment.start(),
                        segment.end(),
                        segment.kind(),
                        segment.durationSeconds(),
                        segment.endAngularVelocityWorld()
                ))
                .toList();
    }

    public AngularSegment segment(int index) {
        AngularSegment segment = segments.get(index);
        return new AngularSegment(
                segment.start(),
                segment.end(),
                segment.kind(),
                segment.durationSeconds(),
                segment.endAngularVelocityWorld()
        );
    }

    public int segmentCount() {
        return segments.size();
    }

    /** Number of integrated DYNAMIC segments. */
    public int dynamicSegmentCount() {
        int count = 0;
        for (AngularSegment segment : segments) {
            if (segment.kind() == Kind.DYNAMIC) {
                count++;
            }
        }
        return count;
    }

    public Vec3d omegaEnteringSegment(int index) {
        if (index < 0 || index >= segments.size()) {
            throw new IndexOutOfBoundsException(
                    "segment index out of range: " + index);
        }
        return index == 0
                ? copy(initialAngularVelocityWorld)
                : segments.get(index - 1).endAngularVelocityWorld();
    }

    public Vec3d finalAngularVelocityWorld() {
        return segments.get(segments.size() - 1).endAngularVelocityWorld();
    }

    public Quatd sample(int index) {
        if (index < 0 || index > segments.size()) {
            throw new IndexOutOfBoundsException(
                    "sample index out of range: " + index);
        }
        return index == 0
                ? segments.get(0).start()
                : segments.get(index - 1).end();
    }

    public List<Quatd> samples() {
        List<Quatd> samples = new ArrayList<>(segments.size() + 1);
        samples.add(segments.get(0).start());
        for (AngularSegment segment : segments) {
            samples.add(segment.end());
        }
        return List.copyOf(samples);
    }

    /**
     * Appends one zero-momentum geometric orientation correction. The incoming
     * omega is preserved exactly; no duration or angular velocity is
     * fabricated from the quaternion displacement.
     */
    public AngularTrajectory appendKinematic(
            Quatd target,
            double epsilon
    ) {
        Objects.requireNonNull(target, "target");
        Quatd normalized = target.normalized();
        Quatd last = sample(segments.size());
        if (rotationallyEquivalent(last, normalized, epsilon)) {
            return this;
        }
        List<AngularSegment> next = new ArrayList<>(segments.size() + 1);
        for (int index = 0; index < segments.size(); index++) {
            next.add(segment(index));
        }
        next.add(new AngularSegment(
                last,
                normalized,
                Kind.KINEMATIC_GEOMETRIC,
                0.0D,
                finalAngularVelocityWorld()
        ));
        return new AngularTrajectory(
                initialAngularVelocityWorld,
                next
        );
    }

    /**
     * Prepends one zero-momentum geometric entry correction. Used only when an
     * bootstrap actor attitude differs from the displayed bootstrap
     * orientation; the existing incoming omega is preserved exactly.
     */
    public AngularTrajectory prependKinematic(
            Quatd start,
            double epsilon
    ) {
        Objects.requireNonNull(start, "start");
        Quatd normalized = start.normalized();
        Quatd first = sample(0);
        if (rotationallyEquivalent(normalized, first, epsilon)) {
            return this;
        }
        List<AngularSegment> next = new ArrayList<>(segments.size() + 1);
        next.add(new AngularSegment(
                normalized,
                first,
                Kind.KINEMATIC_GEOMETRIC,
                0.0D,
                initialAngularVelocityWorld
        ));
        for (int index = 0; index < segments.size(); index++) {
            next.add(segment(index));
        }
        return new AngularTrajectory(
                initialAngularVelocityWorld,
                next
        );
    }

    /** Defensive copy of this immutable value. */
    public AngularTrajectory copy() {
        return new AngularTrajectory(
                initialAngularVelocityWorld,
                segments
        );
    }

    /** Single integrated dynamic segment. */
    public static AngularTrajectory dynamic(
            Quatd start,
            Quatd end,
            double durationSeconds,
            Vec3d initialAngularVelocityWorld,
            Vec3d endAngularVelocityWorld
    ) {
        return new AngularTrajectory(
                initialAngularVelocityWorld,
                List.of(new AngularSegment(
                        start,
                        end,
                        Kind.DYNAMIC,
                        durationSeconds,
                        endAngularVelocityWorld
                ))
        );
    }

    /** Single zero-momentum geometric correction. */
    public static AngularTrajectory kinematic(
            Quatd start,
            Quatd end,
            Vec3d incomingAngularVelocityWorld
    ) {
        return new AngularTrajectory(
                incomingAngularVelocityWorld,
                List.of(new AngularSegment(
                        start,
                        end,
                        Kind.KINEMATIC_GEOMETRIC,
                        0.0D,
                        incomingAngularVelocityWorld
                ))
        );
    }

    /** One zero-length stationary segment with an explicit angular velocity. */
    public static AngularTrajectory stationary(
            Quatd orientation,
            Vec3d angularVelocityWorld
    ) {
        return kinematic(
                orientation,
                orientation,
                angularVelocityWorld
        );
    }

    private static boolean sameRotation(
            Quatd first,
            Quatd second
    ) {
        return rotationallyEquivalent(first, second, 1.0E-12D);
    }

    private static boolean rotationallyEquivalent(
            Quatd first,
            Quatd second,
            double epsilon
    ) {
        double dot = Math.abs(first.dot(second));
        return 1.0D - Math.min(1.0D, dot) <= epsilon;
    }

    private static Quatd normalizedCopy(
            Quatd value,
            String name
    ) {
        Objects.requireNonNull(value, name);
        if (!Double.isFinite(value.x())
                || !Double.isFinite(value.y())
                || !Double.isFinite(value.z())
                || !Double.isFinite(value.w())) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        double lengthSquared = value.lengthSquared();
        if (!Double.isFinite(lengthSquared)
                || lengthSquared <= 1.0E-24D) {
            throw new IllegalArgumentException(
                    name + " must be non-degenerate");
        }
        return value.normalized();
    }

    private static Vec3d copy(Vec3d value) {
        return new Vec3d(value.x(), value.y(), value.z());
    }

    private static void requireFinite(Vec3d value, String name) {
        Objects.requireNonNull(value, name);
        if (!Double.isFinite(value.x())
                || !Double.isFinite(value.y())
                || !Double.isFinite(value.z())
                || !Double.isFinite(value.lengthSquared())) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + value);
        }
    }
}
