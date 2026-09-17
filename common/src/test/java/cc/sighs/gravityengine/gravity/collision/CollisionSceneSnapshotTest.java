package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.kinematic.SweepTimeWindow;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One operation owns one immutable captured collision scene. Geometry
 * preparation, the solve and terminal support must all observe the same
 * frozen revision, and no later world read may leak into it.
 */
class CollisionSceneSnapshotTest {
    private static final long SOURCE = 11L;

    @Test
    void capturedSceneCopiesItsInputsAndFreezesTheRevision() {
        DynamicCollisionObstacleSnapshot obstacle =
                SceneFixtures.dynamicObstacle(
                        SOURCE, 0L, 1L, Vec3d.ZERO, 2.0D,
                        new Vec3d(0.5D, 0.0D, 0.0D), Vec3d.ZERO,
                        50L, 3L, 1.0D
                );
        List<DynamicCollisionObstacleSnapshot> mutable =
                new ArrayList<>();
        mutable.add(obstacle);

        CapturedCollisionScene scene = SceneFixtures.scene(
                50L, 9L, 1.0D, List.of(), mutable
        );

        /* Mutating the producer's list after capture cannot change the scene. */
        mutable.clear();

        assertEquals(1, scene.dynamicObstacles().size());
        assertSame(
                obstacle,
                scene.dynamicObstacle(new RigidObstacleIdentity(SOURCE, 0L, 1L)).orElseThrow()
        );
        assertEquals(50L, scene.tick());
        assertEquals(9L, scene.revision());
        assertEquals(9L, scene.time().sceneRevision());
        assertEquals(1.0D, scene.time().intervalTicks());
        assertEquals(
                50L,
                obstacle.motion().tick()
        );
        assertThrows(
                UnsupportedOperationException.class,
                () -> scene.dynamicObstacles().add(obstacle)
        );
    }

    @Test
    void obstacleAndBlockLookupsAreExactAndImmutable() {
        CellPos cell = new CellPos(3, 4, 5);
        BlockObstacle block = SceneFixtures.block(cell);
        CapturedCollisionScene scene = SceneFixtures.scene(
                1L,
                1L,
                1.0D,
                List.of(block),
                List.of()
        );

        assertSame(block, scene.blockObstacleAt(cell).orElseThrow());
        assertTrue(scene.blockObstacleAt(new CellPos(0, 0, 0)).isEmpty());
        assertTrue(scene.dynamicObstacle(new RigidObstacleIdentity(SOURCE, 0L, 1L)).isEmpty());
    }

    @Test
    void repeatedQueriesFilterTheSameCapturedGeometry() {
        BlockObstacle block = SceneFixtures.block(new CellPos(0, 0, 0));
        CapturedCollisionScene scene = SceneFixtures.scene(
                1L,
                1L,
                1.0D,
                List.of(block),
                List.of()
        );
        OrientedBox body = OrientedBox.axisAligned(
                new Aabb3d(
                        0.25D, 0.25D, 0.25D,
                        0.75D, 0.75D, 0.75D
                )
        );

        List<CollisionObstacle> first = scene.query(
                body, Vec3d.ZERO, new SweepTimeWindow(0.0D, 0.0D)
        );
        List<CollisionObstacle> second = scene.query(
                body, Vec3d.ZERO, new SweepTimeWindow(0.0D, 0.0D)
        );

        assertEquals(first, second);
        assertEquals(1, first.size());
        assertSame(block, first.get(0));
    }
}
