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
                scene.dynamicObstacle(new RigidObstacleIdentity(RigidObstacleIdentity.NATIVE_PROVIDER_NAMESPACE,
                cc.sighs.gravityengine.gravity.collision.DynamicCollisionObstacleSnapshot.PROVIDER_LOCAL_REGISTRATION_EPOCH,
                SOURCE,
                0L,
                1L)).orElseThrow()
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
        assertTrue(scene.dynamicObstacle(new RigidObstacleIdentity(RigidObstacleIdentity.NATIVE_PROVIDER_NAMESPACE,
                cc.sighs.gravityengine.gravity.collision.DynamicCollisionObstacleSnapshot.PROVIDER_LOCAL_REGISTRATION_EPOCH,
                SOURCE,
                0L,
                1L)).isEmpty());
    }

    @Test
    void missingBlockDataIsLocalAndIncludesShapeAndNeighborDependencies() {
        var original = SceneFixtures.scene(1, 1, 1, List.of(), List.of());
        var gaps = new ArrayList<>(List.of(new Aabb3d(16, -64, -16, 32, 64, 16)));
        var scene = new CapturedCollisionScene(original.domain(), 1, 1, original.time(),
                new CollisionWorkTracker(CollisionWorkBudget.defaults()),
                new WorldBorderCollisionSnapshot(-1000, -1000, 1000, 1000), List.of(), List.of(),
                List.of(), List.of(), java.util.Map.of(), 0, gaps);
        gaps.clear();
        var safe = OrientedBox.axisAligned(new Aabb3d(0, 0, 0, 1, 1, 1));
        assertTrue(scene.query(safe, Vec3d.ZERO, new SweepTimeWindow(0, 0)).isEmpty());
        var edge = OrientedBox.axisAligned(new Aabb3d(14, 0, 0, 15, 1, 1));
        var failure = assertThrows(CollisionSceneCoverageException.class,
                () -> scene.query(edge, Vec3d.ZERO, new SweepTimeWindow(0, 0)));
        assertEquals(CollisionSceneCoverageException.Reason.UNAVAILABLE_BLOCK_DATA, failure.reason());
        assertThrows(CollisionSceneCoverageException.class, () -> scene.querySupport(edge, Vec3d.ZERO, new SweepTimeWindow(0, 0)));
        assertThrows(CollisionSceneCoverageException.class, () -> scene.queryPoseFit(edge));
        assertThrows(CollisionSceneCoverageException.class, () -> scene.movementMaterialAt(new CellPos(16,0,0)));
        assertThrows(CollisionSceneCoverageException.class, () -> scene.blockObstaclesAt(new CellPos(15,0,0)));
        assertThrows(CollisionSceneCoverageException.class, () -> scene.blockObstacleAt(new CellPos(100,0,0)));
        assertTrue(scene.movementMaterialAt(new CellPos(0,0,0)).isEmpty());
    }

    @Test
    void indexedBroadphaseMatchesLinearOracleIncludingLargePrimitivesAndNegativeCoordinates() {
        var random = new java.util.Random(814);
        var blocks = new ArrayList<BlockObstacle>();
        for (int i=0; i<300; i++) blocks.add(SceneFixtures.block(new CellPos(
                random.nextInt(80)-40, random.nextInt(80)-40, random.nextInt(80)-40)));
        blocks.add(new BlockObstacle(new CellPos(0,0,0), new Aabb3d(-50,-1,-50,50,1,50)));
        var index = new CapturedBlockIndex(blocks);
        for (int i=0; i<200; i++) {
            double x=random.nextInt(80)-40, y=random.nextInt(80)-40, z=random.nextInt(80)-40;
            var bounds = new Aabb3d(x,y,z,x+5,y+5,z+5);
            var actual = new ArrayList<CollisionObstacle>(); index.query(bounds, actual);
            actual.sort(CollisionObstacle.STABLE_COMPARATOR);
            var expected = blocks.stream().filter(b -> b.bounds().intersects(bounds))
                    .sorted(CollisionObstacle.STABLE_COMPARATOR).toList();
            assertEquals(expected, actual);
        }
        var scene = SceneFixtures.scene(1,1,1,blocks,List.of());
        var cell = new CellPos(0,0,0);
        assertEquals(blocks.stream().filter(b -> b.blockPos().equals(cell))
                .sorted(CollisionObstacle.STABLE_COMPARATOR).toList(), scene.blockObstaclesAt(cell));
        assertThrows(UnsupportedOperationException.class, () -> scene.blockObstaclesAt(cell).clear());
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
