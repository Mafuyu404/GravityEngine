package cc.sighs.gravityengine.gravity.collision;

/**
 * Minimal sink for engine-owned rigid-obstacle publications.
 *
 * <p>{@link CollisionSceneBuilder} is the ordinary unbounded collector used by
 * gameplay scene capture. Bounded callers (for example the packet rigid
 * occupancy path) provide their own collector so an external provider cannot
 * make one operation accumulate an unbounded obstacle list before the work
 * budget is consulted.</p>
 *
 * <p>A collector may impose its own bound and fail closed by throwing
 * {@link CollisionComplexityLimitException}; the owning operation boundary
 * converts that into an explicit indeterminate result.</p>
 */
public interface RigidPublicationCollector {

    /** Adds one immutable rigid-obstacle publication. */
    void addObstacle(DynamicCollisionObstacleSnapshot obstacle);
}
