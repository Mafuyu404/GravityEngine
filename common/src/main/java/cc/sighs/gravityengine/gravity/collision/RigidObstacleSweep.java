package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.geometry.GeometryTolerance;
import java.util.ArrayList;
import java.util.List;

/** One-way prescribed OBB CCD. Enclosures only prove clearance; every reported
 * plane comes from an exact pair. Ordered interval refinement preserves the
 * earliest event; inability to prove an interval is explicitly indeterminate. */
final class RigidObstacleSweep {
    private static final int MAX_DEPTH = 28;
    private static final int MAX_TESTS = 1024;
    private final OrientedBox actor;
    private final Vec3d movement;
    private final EntityObstacle obstacle;
    private final double start, duration;
    private final ObbQueryContext context;
    private final SweepInitialState initial;
    private final double relativeTravelBound;
    private int tests;

    private RigidObstacleSweep(OrientedBox actor, Vec3d movement, EntityObstacle obstacle,
            double start, double duration, ObbQueryContext context, SweepInitialState initial) {
        this.actor = actor; this.movement = movement; this.obstacle = obstacle;
        this.start = start; this.duration = duration; this.context = context; this.initial = initial;
        this.relativeTravelBound = movement.length() + duration * obstacle.motion().intervalTicks()
                * obstacle.motion().maximumPointSpeed(obstacle.snapshot().localBody());
    }
    static SweepContactResult sweep(OrientedBox actor, Vec3d movement, EntityObstacle obstacle,
            double start, double duration, ObbQueryContext context) {
        var overlap = cc.sighs.gravityengine.math.geometry.ObbSat.overlap(context.body(actor),
                context.orientedObstacle(obstacle.bodyAt(start), Vec3d.ZERO), context.scratch());
        SweepInitialState initial = switch (overlap.status()) {
            case SEPARATED -> SweepInitialState.SEPARATED;
            case TOUCHING -> SweepInitialState.TOUCHING;
            case OVERLAPPING -> SweepInitialState.OVERLAPPING;
        };
        var solve = new RigidObstacleSweep(actor, movement, obstacle, start, duration, context, initial);
        if (initial == SweepInitialState.OVERLAPPING) {
            // An angular mover cannot use the translation-only baseline exemption
            // to skip an untested deeper intermediate pose.
            return solve.unknown();
        }
        // Rotation preserves projection onto its own world axis. If that axis
        // separates the pair initially and the relative linear motion is not
        // entering, it proves clearance for the WHOLE interval. This includes
        // tangent travel on a yawing floor: a conservative angular envelope's
        // roundoff must not manufacture a blocking zero-TOI floor contact.
        Vec3d axis = new Vec3d(obstacle.motion().ax(), obstacle.motion().ay(), obstacle.motion().az()).normalized();
        var invariantPlane = cc.sighs.gravityengine.math.geometry.ObbSat.overlapAlong(
                context.body(actor), context.orientedObstacle(obstacle.bodyAt(start), Vec3d.ZERO), axis);
        if (invariantPlane.hasNormal() && invariantPlane.penetration() <= GeometryTolerance.TOUCHING
                && movement.subtract(obstacle.motion().displacementForInterval().multiply(duration))
                    .dot(invariantPlane.normal()) >= 0) {
            return new SweepContactResult(initial, List.of());
        }
        if (initial == SweepInitialState.TOUCHING) {
            var contacts = solve.contactsForNormal(0, overlap, overlap.normal(), true);
            if (!contacts.isEmpty()) return new SweepContactResult(initial, contacts);
        }
        return solve.refine(0, 1, 0);
    }
    private SweepContactResult unknown() {
        return new SweepContactResult(SweepInitialState.SEPARATED, List.of(), true);
    }
    private double time(double fraction) { return Math.min(1, start + duration * fraction); }
    private cc.sighs.gravityengine.math.geometry.ObbOverlapResult overlap(OrientedBox a, OrientedBox b) {
        return cc.sighs.gravityengine.math.geometry.ObbSat.overlap(context.body(a), context.orientedObstacle(b, Vec3d.ZERO), context.scratch());
    }
    private SweepContactResult refine(double lo, double hi, int depth) {
        if (++tests > MAX_TESTS || !context.workTracker().recordNarrowPhaseTest()) return unknown();
        Vec3d delta = movement.multiply(hi - lo);
        OrientedBox atLo = actor.move(movement.multiply(lo));
        OrientedBox obstacleEnvelope = obstacle.motion().envelope(
                obstacle.snapshot().localBody(), time(lo), time(hi), delta);
        var enclosure = overlap(atLo, obstacleEnvelope);
        if (enclosure.status() != cc.sighs.gravityengine.math.geometry.ObbOverlapResult.Status.OVERLAPPING)
            return new SweepContactResult(initial, List.of());

        double uncertainty = relativeTravelBound * (hi - lo);
        if (depth >= MAX_DEPTH || hi - lo <= CollisionTolerances.TOI_EPSILON
                && uncertainty <= CollisionTolerances.CONTACT_SKIN) {
            if (uncertainty > CollisionTolerances.CONTACT_SKIN) return unknown();
            if (++tests > MAX_TESTS || !context.workTracker().recordNarrowPhaseTest()) return unknown();
            var exact = overlap(actor.move(movement.multiply(hi)), obstacle.bodyAt(time(hi)));
            if (exact.status() == cc.sighs.gravityengine.math.geometry.ObbOverlapResult.Status.SEPARATED) return unknown();
            var contacts = contactsAt(hi, exact, uncertainty + GeometryTolerance.TOUCHING);
            if (contacts.isEmpty()) return unknown();
            // lo is the last proven legal instant. Exact geometry at the upper
            // contact bracket supplies normals; bracket width is the existing TOI tolerance.
            var stopped = contacts.stream().map(c -> new CollisionContact(c.obstacle(), c.point(),
                    c.normal(), 0, lo, c.surfaceVelocity(), c.obstacleTime())).toList();
            return new SweepContactResult(initial, stopped);
        }
        double mid = (lo + hi) * .5;
        var first = refine(lo, mid, depth + 1);
        if (first.indeterminate() || first.hasBlockingContacts()) return first;
        return refine(mid, hi, depth + 1);
    }

    private List<CollisionContact> contactsAt(double fraction, cc.sighs.gravityengine.math.geometry.ObbOverlapResult exact, double band) {
        OrientedBox a = actor.move(movement.multiply(fraction));
        OrientedBox b = obstacle.bodyAt(time(fraction));
        List<Vec3d> actorAxes = List.of(a.orientation().axisX(),
                a.orientation().axisY(), a.orientation().axisZ());
        List<Vec3d> obstacleAxes = List.of(b.orientation().axisX(),
                b.orientation().axisY(), b.orientation().axisZ());
        List<Vec3d> axes = new ArrayList<>(actorAxes);
        axes.addAll(obstacleAxes);
        for (var x : actorAxes) for (var y : obstacleAxes) axes.add(x.cross(y));
        List<Vec3d> normals = new ArrayList<>();
        normals.add(exact.normal());
        var pureA = context.body(a);
        var pureB = context.orientedObstacle(b, Vec3d.ZERO);
        for (var axis : axes) {
            if (axis.lengthSquared() <= GeometryTolerance.DEGENERATE_AXIS_LENGTH_SQUARED) continue;
            var along = cc.sighs.gravityengine.math.geometry.ObbSat.overlapAlong(pureA, pureB, axis.normalized());
            if (!along.hasNormal() || along.penetration() > exact.penetration() + band) continue;
            var normal = along.normal();
            if (normals.stream().noneMatch(n -> CollisionTolerances.sameConstraintIdentity(n, normal)))
                normals.add(normal);
        }
        List<CollisionContact> result = new ArrayList<>();
        for (var normal : normals) result.addAll(contactsForNormal(fraction, exact, normal, false));
        return result;
    }

    private List<CollisionContact> contactsForNormal(double fraction, cc.sighs.gravityengine.math.geometry.ObbOverlapResult exact,
            Vec3d normal, boolean enteringOnly) {
        OrientedBox a = actor.move(movement.multiply(fraction));
        OrientedBox b = obstacle.bodyAt(time(fraction));
        // Project the actual actor support feature onto the finite obstacle. A
        // rotating face needs the velocity of each contacted material point.
        Vec3d h = a.halfExtents();
        List<CollisionContact> contacts = new ArrayList<>();
        double minimum = Double.POSITIVE_INFINITY;
        for (int bits = 0; bits < 8; bits++) {
            Vec3d point = a.localPointToWorld(new Vec3d(
                    (bits & 1) == 0 ? -h.x() : h.x(),
                    (bits & 2) == 0 ? -h.y() : h.y(),
                    (bits & 4) == 0 ? -h.z() : h.z()));
            minimum = Math.min(minimum, point.dot(normal));
        }
        for (int bits = 0; bits < 8; bits++) {
            Vec3d corner = a.localPointToWorld(new Vec3d(
                    (bits & 1) == 0 ? -h.x() : h.x(),
                    (bits & 2) == 0 ? -h.y() : h.y(),
                    (bits & 4) == 0 ? -h.z() : h.z()));
            if (corner.dot(normal) > minimum + CollisionTolerances.CONTACT_SKIN) continue;
            Vec3d point = b.closestPointTo(corner);
            if (point.distance(corner) > CollisionTolerances.CONTACT_SKIN + exact.penetration()) continue;
            Vec3d velocity = obstacle.velocityAt(point, time(fraction));
            if (enteringOnly && movement.subtract(velocity
                    .multiply(duration * obstacle.motion().intervalTicks())).dot(normal)
                    >= -CollisionTolerances.ENTERING_PLANE_EPSILON) continue;
            if (enteringOnly) {
                // One actual shared material point must enter the finite pair,
                // including every simultaneous touching axis. An inward plane
                // alone cannot turn an outward/tangent edge into a sticky hit.
                var relative = movement.subtract(velocity
                        .multiply(duration * obstacle.motion().intervalTicks()));
                var entry = cc.sighs.gravityengine.math.geometry.ObbSweep.sweep(context.body(a), relative,
                        context.orientedObstacle(b, Vec3d.ZERO), context.scratch());
                if (entry.status() == cc.sighs.gravityengine.math.geometry.ObbSweepResult.Status.NO_HIT) continue;
                for (int axis = 0; axis < entry.activeAxes().size(); axis++) {
                    contacts.add(new CollisionContact(obstacle, point,
                            entry.activeAxes().normal(axis), 0, fraction, velocity, time(fraction)));
                }
                continue;
            }
            contacts.add(new CollisionContact(obstacle, point, normal, 0, fraction, velocity, time(fraction)));
        }
        if (contacts.isEmpty() && !enteringOnly) {
            Vec3d point = b.closestPointTo(a.closestPointTo(b.center()));
            contacts.add(new CollisionContact(obstacle, point, normal, 0, fraction,
                    obstacle.velocityAt(point, time(fraction)), time(fraction)));
        }
        return contacts;
    }
}
