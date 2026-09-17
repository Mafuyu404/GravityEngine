package cc.sighs.gravityengine.gravity.integration.collision;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.collision.provider.ExternalRigidCollisionQuery;
import cc.sighs.gravityengine.gravity.collision.provider.RigidCollisionPublicationRegistry;
import cc.sighs.gravityengine.gravity.collision.RigidPublicationCollector;
import cc.sighs.gravityengine.gravity.integration.MinecraftRigidCollisionPublicationResolver;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.gravity.minecraft.collision.MinecraftCollisionGeometryAdapter;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.*;

/**
 * Live Minecraft capture boundary for one outer collision operation.
 *
 * <p>This is the only place that reads {@code Level}, {@code BlockState},
 * {@code VoxelShape}, the obstacle registry or hard-entity geometry for a
 * collision scene. It materializes one complete {@link CapturedCollisionScene}
 * and discards every Minecraft reading handle before the solver starts.</p>
 */
public final class MinecraftCollisionSceneCapture {
    private MinecraftCollisionSceneCapture() {}

    /**
     * Captures every obstacle in {@code domain} exactly once. No query
     * performed on the returned scene re-enters the Minecraft world.
     */
    public static CapturedCollisionScene capture(
            Entity collisionOwner,
            CollisionCaptureDomain domain,
            long tick,
            long revision,
            KinematicStepContext time,
            CollisionWorkTracker tracker
    ) {
        Objects.requireNonNull(collisionOwner, "collisionOwner");
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(tracker, "tracker");

        Level level = Objects.requireNonNull(
                collisionOwner.level(), "collisionOwner level");
        CollisionContext context = CollisionContext.of(collisionOwner);

        // World border and registry are snapshotted once, before block and
        // entity capture, so the whole scene observes one consistent instant.
        WorldBorderCollisionSnapshot worldBorder =
                captureWorldBorder(level);
        tracker.recordWorldBorderSnapshot();

        CollisionObstacleRegistry.RegisteredSphereQuery spheres =
                CollisionObstacleRegistry.get(level).query(
                        MinecraftCollisionGeometryAdapter.toMinecraft(
                                domain.staticBounds()));
        tracker.recordSourceSphereSnapshot();
        List<SphereObstacle> sphereObstacles =
                new ArrayList<>(spheres.obstacles());
        Set<BlockPos> replacedBlockPositions =
                new HashSet<>(spheres.registeredBlockPositions());

        List<BlockObstacle> blockObstacles = new ArrayList<>();
        Map<CellPos, BlockMovementMaterialSnapshot> movementMaterials =
                new HashMap<>();
        int evaluatedBlockPositions = captureBlocks(
                level,
                context,
                domain.staticBounds(),
                tracker,
                blockObstacles,
                replacedBlockPositions,
                movementMaterials,
                collisionOwner
        );

        /*
         * One dynamic-obstacle builder owns both entity publications and
         * external rigid providers for this operation. External providers are
         * consulted before the scene is frozen, so the solver observes one
         * immutable obstacle set rather than re-reading an optional mod later.
         */
        CollisionSceneBuilder dynamicBuilder =
                new CollisionSceneBuilder(time);
        RigidCollisionPublicationRegistry.capture(
                level,
                new ExternalRigidCollisionQuery(
                        domain.staticBounds(),
                        domain.dynamicEntityBounds(),
                        time
                ),
                dynamicBuilder
        );

        captureDynamicObstacles(
                level,
                collisionOwner,
                domain.dynamicEntityBounds(),
                time,
                tracker,
                dynamicBuilder
        );

        List<DynamicCollisionObstacleSnapshot> dynamicObstacles =
                dynamicBuilder.build().dynamicObstacles();

        /*
         * Pose-fit legality (Player.canPlayerFitWithinBlocksAndEntitiesWhen)
         * treats ordinary entities as strict blockers, unlike the
         * vanilla-soft movement solve. Those ordinary entities are captured
         * here as immutable zero-motion pose-strict snapshots so the later
         * pose query never re-reads the live entity list or live bounding
         * boxes.
         */
        List<EntityObstacle> poseStrictObstacles =
                capturePoseStrictEntities(
                        level,
                        collisionOwner,
                        domain.staticBounds(),
                        tracker
                );

        return new CapturedCollisionScene(
                domain,
                tick,
                revision,
                time,
                tracker,
                worldBorder,
                blockObstacles,
                dynamicObstacles,
                sphereObstacles,
                poseStrictObstacles,
                movementMaterials,
                evaluatedBlockPositions
        );
    }

    /**
     * Capture boundary for a geometry transition outside a gravity operation.
     * The returned scene is strong and immutable; the transition may then use
     * only {@link CollisionScene#query} for legality/recovery.
     */
    public static CapturedCollisionScene captureAround(
            Entity collisionOwner,
            CollisionBody body,
            double maxStepHeight
    ) {
        Objects.requireNonNull(collisionOwner, "collisionOwner");
        Objects.requireNonNull(body, "body");
        CollisionCaptureDomain domain = CollisionCaptureDomain.forTransition(body, maxStepHeight);
        long tick = collisionOwner.level().getGameTime();
        long revision = 0L;
        KinematicStepContext time = KinematicStepContext.fullTick(
                tick, revision);
        CollisionWorkTracker tracker = new CollisionWorkTracker(
                CollisionWorkBudget.defaults());
        return capture(
                collisionOwner,
                domain,
                tick,
                revision,
                time,
                tracker);
    }

    /**
     * Captures only the dynamic rigid geometry relevant to one packet
     * occupancy validation.
     *
     * <p>This deliberately does not materialize block voxels, world-border
     * planes, registered static spheres or pose-strict gameplay entities.
     * Vanilla packet validation already performs its own block/entity
     * collision checks; GravityEngine extends only the geometry it owns:
     * external rigid providers and native moving rigid publications.</p>
     */
    public static List<DynamicCollisionObstacleSnapshot>
    captureRigidOccupancy(
            Entity collisionOwner,
            Aabb3d queryBounds,
            KinematicStepContext time,
            CollisionWorkTracker tracker
    ) {
        return captureRigidOccupancyBounded(
                collisionOwner,
                queryBounds,
                time,
                tracker
        ).obstacles();
    }

    /**
     * Budget-bounded form of {@link #captureRigidOccupancy}.
     *
     * <p>Every external-provider publication and native dynamic primitive is
     * charged against {@code tracker.recordObstacles} before it is accepted
     * into the collector. A provider that would exceed the packet obstacle
     * bound aborts its own accumulation with
     * {@link CollisionComplexityLimitException}; the caller observes
     * {@link RigidOccupancyCapture#budgetExhausted()} and fails closed instead
     * of performing unbounded work.</p>
     */
    public static RigidOccupancyCapture captureRigidOccupancyBounded(
            Entity collisionOwner,
            Aabb3d queryBounds,
            KinematicStepContext time,
            CollisionWorkTracker tracker
    ) {
        Objects.requireNonNull(
                collisionOwner,
                "collisionOwner"
        );
        Objects.requireNonNull(queryBounds, "queryBounds");
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(tracker, "tracker");

        Level level = Objects.requireNonNull(
                collisionOwner.level(),
                "collisionOwner level"
        );
        Aabb3d dynamicBounds =
                DynamicEntityBroadphasePolicy
                        .candidateQueryBounds(queryBounds);
        BudgetedRigidCollector collector =
                new BudgetedRigidCollector(
                        new CollisionSceneBuilder(time),
                        tracker
                );

        try {
            RigidCollisionPublicationRegistry.capture(
                    level,
                    new ExternalRigidCollisionQuery(
                            queryBounds,
                            dynamicBounds,
                            time
                    ),
                    collector
            );
            capturePacketDynamicObstacles(
                    level, collisionOwner, dynamicBounds, time, tracker, collector
            );
            return new RigidOccupancyCapture(
                    collector.build(),
                    tracker.limitExceeded(),
                    tracker
            );
        } catch (CollisionComplexityLimitException | CollisionSceneCoverageException exhausted) {
            /*
             * A pathological provider is an explicit indeterminate capture,
             * not an operation failure: the packet validator fails closed
             * without an uncaught complexity exception escaping
             * handleMovePlayer.
             */
            return new RigidOccupancyCapture(
                    List.of(),
                    true,
                    tracker
            );
        }
    }

    /**
     * Collector that refuses to accumulate more rigid primitives than the
     * operation's obstacle budget allows.
     */
    private static final class BudgetedRigidCollector
            implements RigidPublicationCollector {
        private final CollisionSceneBuilder builder;
        private final CollisionWorkTracker tracker;

        private BudgetedRigidCollector(
                CollisionSceneBuilder builder,
                CollisionWorkTracker tracker
        ) {
            this.builder = builder;
            this.tracker = tracker;
        }

        @Override
        public void addObstacle(
                DynamicCollisionObstacleSnapshot obstacle
        ) {
            if (!tracker.recordObstacles(1)) {
                throw new CollisionComplexityLimitException(
                        "packet rigid occupancy obstacle budget "
                                + "exceeded: " + tracker.limitReason()
                );
            }
            builder.addObstacle(obstacle);
        }

        /** Reserves an entire native publication before any primitive validation. */
        private void addNativePublication(
                Level level,
                CollisionSurfaceMotionProvider.MotionSnapshot publication,
                Aabb3d discovery,
                KinematicStepContext time,
                MinecraftRigidCollisionPublicationResolver resolver
        ) {
            if (!tracker.recordObstacles(publication.primitives().size())) {
                throw new CollisionComplexityLimitException(
                        "packet native publication budget exceeded: " + tracker.limitReason());
            }
            DynamicEntityBroadphasePolicy.validatePublication(publication, discovery, time);
            for (var primitive : publication.primitives()) {
                // Already charged above; do not call this.addObstacle again.
                builder.addObstacle(primitive);
                RigidCollisionPublicationRegistry.recordPublication(level, primitive, resolver);
            }
        }

        private List<DynamicCollisionObstacleSnapshot> build() {
            return builder.build().dynamicObstacles();
        }
    }

    /**
     * Creates the packet path's bounded rigid collector.
     *
     * <p>Exposed for the executable packet-budget verification, which drives a
     * pathological provider through the exact bounded sink used by the packet
     * capture path without needing a live Minecraft level.</p>
     */
    public static RigidPublicationCollector boundedRigidCollector(
            KinematicStepContext time,
            CollisionWorkTracker tracker
    ) {
        return new BudgetedRigidCollector(
                new CollisionSceneBuilder(time),
                tracker
        );
    }

    private static int captureBlocks(
            Level level,
            CollisionContext context,
            Aabb3d searchBox,
            CollisionWorkTracker tracker,
            List<BlockObstacle> blockObstacles,
            Set<BlockPos> replacedBlockPositions,
            Map<CellPos, BlockMovementMaterialSnapshot> movementMaterials,
            Entity collisionOwner
    ) {
        BlockPos minimum = BlockPos.containing(
                searchBox.minX() - 1.0D,
                searchBox.minY() - 1.0D,
                searchBox.minZ() - 1.0D
        );
        BlockPos maximum = BlockPos.containing(
                searchBox.maxX() + 1.0D,
                searchBox.maxY() + 1.0D,
                searchBox.maxZ() + 1.0D
        );
        CollisionWorldQuery.requireBoundedBlockVolume(minimum, maximum);
        int evaluated = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minimumChunkX = SectionPos.blockToSectionCoord(minimum.getX());
        int maximumChunkX = SectionPos.blockToSectionCoord(maximum.getX());
        int minimumChunkZ = SectionPos.blockToSectionCoord(minimum.getZ());
        int maximumChunkZ = SectionPos.blockToSectionCoord(maximum.getZ());

        for (int chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++) {
            int firstX = Math.max(
                    minimum.getX(),
                    SectionPos.sectionToBlockCoord(chunkX));
            int lastX = Math.min(
                    maximum.getX(),
                    SectionPos.sectionToBlockCoord(chunkX, 15));
            for (int chunkZ = minimumChunkZ;
                 chunkZ <= maximumChunkZ; chunkZ++) {
                int firstZ = Math.max(
                        minimum.getZ(),
                        SectionPos.sectionToBlockCoord(chunkZ));
                int lastZ = Math.min(
                        maximum.getZ(),
                        SectionPos.sectionToBlockCoord(chunkZ, 15));
                if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                    continue;
                }
                for (int x = firstX; x <= lastX; x++) {
                    for (int z = firstZ; z <= lastZ; z++) {
                        for (int y = minimum.getY();
                             y <= maximum.getY(); y++) {
                            if (!tracker.recordBlockPosition()) {
                                throw new CollisionComplexityLimitException(
                                        "scene capture block budget exceeded: "
                                                + tracker.limitReason());
                            }
                            cursor.set(x, y, z);
                            BlockPos position = cursor.immutable();
                            evaluated++;
                            if (replacedBlockPositions.contains(position)) {
                                // The registering sphere replaces the voxel.
                                continue;
                            }
                            tracker.recordBlockShapeEvaluated();
                            BlockState state = level.getBlockState(position);
                            if (state.isAir()) {
                                continue;
                            }
                            // Immutable material evidence captured once with
                            // the scene; movement policy later reads only this
                            // snapshot and never re-queries the live
                            // BlockState/Block.
                            movementMaterials.put(
                                    MinecraftMathAdapter.toCellPos(
                                            position
                                    ),
                                    new BlockMovementMaterialSnapshot(
                                            state.getFriction(
                                                    level,
                                                    position,
                                                    collisionOwner),
                                            state.getBlock()
                                                    .getSpeedFactor(),
                                            state.is(Blocks.WATER),
                                            state.is(Blocks.BUBBLE_COLUMN)));
                            VoxelShape shape = state.getCollisionShape(
                                    level, position, context);
                            List<BlockObstacle> built = new ArrayList<>();
                            CollisionWorldQuery.enumerateShapePrimitives(
                                    shape,
                                    tracker,
                                    true,
                                    position,
                                    built);
                            if (tracker.limitExceeded()) {
                                throw new CollisionComplexityLimitException(
                                        "scene capture primitive budget "
                                                + "exceeded: "
                                                + tracker.limitReason());
                            }
                            tracker.recordBlockPrimitives(built.size());
                            blockObstacles.addAll(built);
                        }
                    }
                }
            }
        }
        return evaluated;
    }

    /** Packet-only capture: bounded candidates, then budget-reserved publications. */
    private static void capturePacketDynamicObstacles(
            Level level,
            Entity collisionOwner,
            Aabb3d queryBounds,
            KinematicStepContext time,
            CollisionWorkTracker tracker,
            BudgetedRigidCollector collector
    ) {
        var predicate = EntitySelector.NO_SPECTATORS.and(collisionOwner::canCollideWith);
        List<Entity> candidates = level.getEntities(
                collisionOwner,
                MinecraftCollisionGeometryAdapter.toMinecraft(queryBounds),
                candidate -> {
                    // Charge even rejected soft entities: filtering must not hide
                    // an arbitrarily large visited candidate set.
                    if (!tracker.recordRigidCandidate()) {
                        throw new CollisionComplexityLimitException(
                                "packet native candidate budget exceeded: " + tracker.limitReason());
                    }
                    return !candidate.isRemoved()
                            && predicate.test(candidate)
                            && isHardObstacle(candidate);
                }
        );
        Set<Integer> seen = new HashSet<>();
        for (Entity other : candidates) {
            if (other == collisionOwner || other.isRemoved() || !seen.add(other.getId())) continue;
            var provider = (CollisionSurfaceMotionProvider) other;
            var publication = provider.gravityengine$collisionSurfaceMotionSnapshot();
            if (publication == null) {
                throw new CollisionSceneCoverageException("missing rigid publication");
            }
            collector.addNativePublication(
                    level, publication,
                    MinecraftCollisionGeometryAdapter.toAabb3d(other.getBoundingBox()),
                    time, new MinecraftRigidCollisionPublicationResolver(other));
            tracker.recordDynamicSurfaceSnapshot();
        }
    }

    private static void captureDynamicObstacles(
            Level level,
            Entity collisionOwner,
            Aabb3d queryBounds,
            KinematicStepContext time,
            CollisionWorkTracker tracker,
            RigidPublicationCollector builder
    ) {
        var predicate = EntitySelector.NO_SPECTATORS.and(
                collisionOwner::canCollideWith);
        List<Entity> candidates = level.getEntities(
                collisionOwner,
                MinecraftCollisionGeometryAdapter.toMinecraft(queryBounds),
                predicate.and(candidate ->
                        isHardObstacle(candidate))
        );
        Set<Integer> seen = new HashSet<>();
        for (Entity other : candidates) {
            if (other == collisionOwner || other.isRemoved() || !seen.add(other.getId())) continue;
            // Exactly one completed handoff read. No provider reaches the returned scene.
            CollisionSurfaceMotionProvider provider =
                    (CollisionSurfaceMotionProvider) other;
            var publication = provider
                    .gravityengine$collisionSurfaceMotionSnapshot();
            if (publication == null) throw new CollisionSceneCoverageException("missing rigid publication");
            Aabb3d discovery = MinecraftCollisionGeometryAdapter.toAabb3d(other.getBoundingBox());
            DynamicEntityBroadphasePolicy.validatePublication(publication, discovery, time);
            MinecraftRigidCollisionPublicationResolver resolver =
                    new MinecraftRigidCollisionPublicationResolver(other);
            for (DynamicCollisionObstacleSnapshot primitive
                    : publication.primitives()) {
                RigidCollisionPublicationRegistry.recordPublication(
                        level,
                        primitive,
                        resolver
                );
                builder.addObstacle(primitive);
            }
            tracker.recordDynamicSurfaceSnapshot();
        }
    }

    /**
     * Captures ordinary (vanilla-soft) collidable entities as immutable
     * pose-strict snapshots. They never join the movement obstacle set;
     * {@link CapturedCollisionScene#queryPoseFit} is the only consumer.
     */
    private static List<EntityObstacle> capturePoseStrictEntities(
            Level level,
            Entity collisionOwner,
            Aabb3d queryBounds,
            CollisionWorkTracker tracker
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(collisionOwner, "collisionOwner");
        Objects.requireNonNull(queryBounds, "queryBounds");
        Objects.requireNonNull(tracker, "tracker");
        var predicate = EntitySelector.NO_SPECTATORS.and(
                collisionOwner::canCollideWith).and(candidate ->
                !isHardObstacle(candidate));
        List<Entity> candidates = level.getEntities(
                collisionOwner,
                MinecraftCollisionGeometryAdapter.toMinecraft(queryBounds),
                predicate
        );
        List<EntityObstacle> captured = new ArrayList<>();
        for (Entity other : candidates) {
            if (other == collisionOwner || other.isRemoved()) {
                continue;
            }
            Aabb3d bounds = MinecraftCollisionGeometryAdapter.toAabb3d(
                    other.getBoundingBox());
            CollisionBody exactBody = null;
            if (GravityInfluencePolicy.usesCustomBody(other)) {
                GravityFrame otherFrame =
                        GravityFrameAccess.authoritativeFrame(other);
                exactBody = GravityEntityGeometry.exactBody(
                        other, otherFrame);
            }
            tracker.recordDynamicSurfaceSnapshot();
            captured.add(new EntityObstacle(
                    other.getId(),
                    bounds,
                    exactBody
            ));
        }
        return captured;
    }
    /**
     * Captures the level's current finite border planes exactly once.
     *
     * <p>Do not use {@link WorldBorder#getCollisionShape()} here: Vanilla's
     * shape intentionally represents the exterior with infinite slabs.</p>
     */
    private static WorldBorderCollisionSnapshot captureWorldBorder(Level level) {
        Objects.requireNonNull(level, "level");

        WorldBorder border = level.getWorldBorder();

        return new WorldBorderCollisionSnapshot(
                border.getMinX(),
                border.getMinZ(),
                border.getMaxX(),
                border.getMaxZ()
        );
    }

    /** Explicit opt-in boundary between vanilla-soft entities and hard structures. */
    private static boolean isHardObstacle(Entity entity) {
        return entity != null && isHardObstacleType(entity.getClass());
    }

    static boolean isHardObstacleType(Class<?> entityType) {
        Objects.requireNonNull(entityType, "entityType");
        return CollisionSurfaceMotionProvider.class.isAssignableFrom(entityType);
    }

}
