package cc.sighs.gravityengine.gravity.integration.compat.sable;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.collision.provider.*;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.Quatd;
import cc.sighs.gravityengine.math.geometry.*;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.lang.ref.WeakReference;
import java.util.*;

/** Internal, exact-version adapter. Bounds hooks maintain a spatial index;
 * capture never walks Sable's global sublevel list. No platform object enters common. */
public final class SableRigidCollisionProvider implements ExternalRigidCollisionProvider {
    public static final String ID = "gravityengine:sable";
    private static final Map<Level, SableRigidCollisionProvider> LEVELS = Collections.synchronizedMap(new WeakHashMap<>());
    private final WeakReference<Level> level;
    private final Map<UUID, Entry> entries = new HashMap<>();
    private final Map<Long, Entry> sources = new HashMap<>();
    private final Map<Cell, Set<Entry>> buckets = new HashMap<>();
    private final Set<Entry> large = new HashSet<>();
    private long nextSource = 1;
    private record Cell(int x, int y, int z) {}
    private record PrimitiveKey(BlockPos position, int box) {}
    private static final class Entry {
        final WeakReference<SubLevel> body;
        final long source;
        long revision;
        long epoch = 1;
        long nextPrimitive;
        final Map<PrimitiveKey, Long> ids = new HashMap<>();
        final Map<Long, PrimitiveKey> keys = new HashMap<>();
        final Map<Long, OrientedBox> shapes = new HashMap<>();
        List<Cell> cells = List.of();
        Aabb3d bounds;
        Entry(SubLevel body, long source) { this.body = new WeakReference<>(body); this.source = source; }
    }

    private SableRigidCollisionProvider(Level level) { this.level = new WeakReference<>(level); }

    /** Sable calls this on its Level owner thread after updating global bounds. */
    public static void update(SubLevel body) {
        Level level = body.getLevel();
        var provider = LEVELS.computeIfAbsent(level, SableRigidCollisionProvider::new);
        RigidCollisionPublicationRegistry.register(level, provider);
        var entry = provider.entries.get(body.getUniqueId());
        if (entry == null || entry.body.get() != body) {
            if (entry != null) provider.removeEntry(body.getUniqueId(), entry);
            entry = new Entry(body, provider.nextSource++);
            provider.entries.put(body.getUniqueId(), entry);
            provider.sources.put(entry.source, entry);
        }
        provider.unindex(entry);
        entry.revision++;
        var b = body.boundingBox();
        // Any material point may move at most the engine discovery reach.
        // Expansion also contains the start/intermediate pose of relevant bodies.
        entry.bounds = new Aabb3d(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ())
                .inflate(DynamicEntityBroadphasePolicy.MAX_RIGID_SWEPT_REACH);
        entry.cells = cells(entry.bounds);
        if (entry.cells.isEmpty()) provider.large.add(entry);
        else for (var cell : entry.cells) provider.buckets.computeIfAbsent(cell, ignored -> new HashSet<>()).add(entry);
    }

    /** Geometry edits invalidate saved material anchors, including remove/re-add
     * of an identical block between two character steps. */
    public static void geometryChanged(SubLevel body) {
        var provider = LEVELS.get(body.getLevel());
        if (provider == null) return;
        var entry = provider.entries.get(body.getUniqueId());
        if (entry == null || entry.body.get() != body) return;
        entry.epoch++;
        entry.revision++;
        entry.shapes.clear();
    }

    public static void remove(SubLevel body) {
        var provider = LEVELS.get(body.getLevel());
        if (provider == null) return;
        var entry = provider.entries.get(body.getUniqueId());
        if (entry != null && entry.body.get() == body) provider.removeEntry(body.getUniqueId(), entry);
    }

    public static void unload(Level level) { LEVELS.remove(level); }
    private void removeEntry(UUID uuid, Entry entry) { unindex(entry); entries.remove(uuid); sources.remove(entry.source); }
    private void unindex(Entry entry) {
        large.remove(entry);
        for (var cell : entry.cells) {
            var values = buckets.get(cell);
            if (values != null) { values.remove(entry); if (values.isEmpty()) buckets.remove(cell); }
        }
    }

    private static List<Cell> cells(Aabb3d b) {
        int x0 = (int)Math.floor(b.minX()/32), x1 = (int)Math.floor(b.maxX()/32);
        int y0 = (int)Math.floor(b.minY()/32), y1 = (int)Math.floor(b.maxY()/32);
        int z0 = (int)Math.floor(b.minZ()/32), z1 = (int)Math.floor(b.maxZ()/32);
        long nx = (long)x1-x0+1, ny = (long)y1-y0+1, nz = (long)z1-z0+1;
        if (nx > 4096 || ny > 4096 || nz > 4096 || nx*ny*nz > 4096
                || x1 == Integer.MAX_VALUE || y1 == Integer.MAX_VALUE || z1 == Integer.MAX_VALUE)
            return List.of();
        var result = new ArrayList<Cell>();
        for (int x=x0; x<=x1; x++) for (int y=y0; y<=y1; y++) for (int z=z0; z<=z1; z++) result.add(new Cell(x,y,z));
        return result;
    }

    private static void selectBounded(Set<Entry> selected, Set<Entry> candidates) {
        for (var entry : candidates) {
            selected.add(entry);
            if (selected.size() > CollisionWorkBudget.defaults().maxObstaclePrimitives())
                throw new CollisionComplexityLimitException("Sable candidate budget");
        }
    }

    @Override public String id() { return ID; }
    @Override public boolean isLive() { return level.get() != null; }
    @Override public boolean isSourceLive(long source, KinematicStepContext time) {
        var entry = sources.get(source);
        return entry != null && entry.body.get() != null && !entry.body.get().isRemoved();
    }

    @Override public void capture(ExternalRigidCollisionQuery query, RigidPublicationCollector output) {
        Level world = Objects.requireNonNull(level.get(), "unloaded Sable level");
        var selected = new HashSet<Entry>();
        selectBounded(selected, large);
        var queryCells = cells(query.dynamicBounds());
        if (queryCells.isEmpty()) throw new CollisionComplexityLimitException("Sable discovery cell budget");
        for (var cell : queryCells) selectBounded(selected, buckets.getOrDefault(cell, Set.of()));
        long checkedBlocks = 0;
        int checkedBodies = 0;
        for (var entry : selected.stream().sorted(Comparator.comparingLong(e -> e.source)).toList()) {
            if (++checkedBodies > CollisionWorkBudget.defaults().maxObstaclePrimitives())
                throw new CollisionComplexityLimitException("Sable candidate budget");
            SubLevel body = entry.body.get();
            if (body == null || body.isRemoved() || !entry.bounds.intersects(query.dynamicBounds())) continue;
            if (body.getPlot() instanceof dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot serverPlot
                    && !serverPlot.getContraptions().isEmpty())
                throw new CollisionSceneCoverageException("Sable kinematic contraptions need a separate motion publication");
            var start = start(body);
            var motion = motion(entry, body, query.time());
            // Transform the expanded discovery region into plot coordinates.
            // The reach inflation proves coverage without replacing primitive geometry.
            Aabb3d local = inverseBounds(start, query.dynamicBounds());
            var plot = body.getPlot().getBoundingBox();
            int x0 = Math.max(plot.minX(), (int)Math.floor(local.minX())-1);
            int y0 = Math.max(plot.minY(), (int)Math.floor(local.minY())-1);
            int z0 = Math.max(plot.minZ(), (int)Math.floor(local.minZ())-1);
            int x1 = Math.min(plot.maxX(), (int)Math.floor(local.maxX())+1);
            int y1 = Math.min(plot.maxY(), (int)Math.floor(local.maxY())+1);
            int z1 = Math.min(plot.maxZ(), (int)Math.floor(local.maxZ())+1);
            for (int x=x0; x<=x1; x++) for (int y=y0; y<=y1; y++) for (int z=z0; z<=z1; z++) {
                if (++checkedBlocks > CollisionWorkBudget.defaults().maxBlockPositions())
                    throw new CollisionComplexityLimitException("Sable block discovery budget");
                var pos = new BlockPos(x,y,z);
                var state = world.getBlockState(pos);
                if (state.isAir()) continue;
                if (state.getBlock() instanceof net.minecraft.world.level.block.ScaffoldingBlock)
                    throw new CollisionSceneCoverageException("Sable scaffolding requires actor-specific collision context");
                var boxes = state.getCollisionShape(world, pos).toAabbs();
                for (int i=0; i<boxes.size(); i++) {
                    var key = new PrimitiveKey(pos, i);
                    long id = entry.ids.computeIfAbsent(key, ignored -> {
                        long allocated = entry.nextPrimitive++; entry.keys.put(allocated, key); return allocated;
                    });
                    var shape = shape(start, pos, boxes.get(i));
                    var old = entry.shapes.put(id, shape);
                    if (old != null && !old.equals(shape)) {
                        entry.epoch++;
                        throw new CollisionSceneCoverageException("Sable primitive changed during continuity; retry with new epoch");
                    }
                    var primitive = new DynamicCollisionObstacleSnapshot(entry.source, id, shape, motion);
                    if (primitive.operationSweptBounds().intersects(query.dynamicBounds())) output.addObstacle(primitive);
                }
            }
        }
    }

    @Override public Optional<DynamicCollisionObstacleSnapshot> resolve(RigidObstacleIdentity identity, KinematicStepContext time) {
        var entry = sources.get(identity.sourceId());
        if (entry == null || !isSourceLive(entry.source, time)) return Optional.empty();
        var key = entry.keys.get(identity.primitiveId());
        if (key == null) return Optional.empty();
        var body = entry.body.get();
        Level world = level.get();
        if (world == null || body == null) return Optional.empty();
        var boxes = world.getBlockState(key.position()).getCollisionShape(world, key.position()).toAabbs();
        if (key.box() >= boxes.size()) return Optional.empty();
        var shape = shape(start(body), key.position(), boxes.get(key.box()));
        if (!shape.equals(entry.shapes.get(identity.primitiveId()))) return Optional.empty();
        return Optional.of(new DynamicCollisionObstacleSnapshot(entry.source, identity.primitiveId(), shape, motion(entry, body, time)));
    }

    private static Pose3d start(SubLevel body) {
        var start = new Pose3d(body.lastPose());
        if (start.rotationPoint().lengthSquared() == 0) start.rotationPoint().set(body.logicalPose().rotationPoint());
        return start;
    }
    private static RigidMotionSnapshot motion(Entry entry, SubLevel body, KinematicStepContext time) {
        if (time.intervalTicks() != 1) throw new CollisionSceneCoverageException("Sable collision publication covers one tick");
        var a = start(body);
        var b = body.logicalPose();
        if (!a.rotationPoint().equals(b.rotationPoint()) || !a.scale().equals(b.scale())
                || a.scale().x <= 0 || a.scale().y <= 0 || a.scale().z <= 0)
            throw new CollisionSceneCoverageException("Sable changing pivot/scale is not a continuous rigid publication");
        var delta = new Quaterniond(b.orientation()).mul(new Quaterniond(a.orientation()).conjugate()).normalize();
        if (delta.w < 0) delta.mul(-1);
        double length = Math.sqrt(delta.x*delta.x + delta.y*delta.y + delta.z*delta.z);
        double angle = 2*Math.atan2(length, delta.w);
        Vec3d angular = length == 0 ? Vec3d.ZERO : new Vec3d(delta.x,delta.y,delta.z).multiply(angle/length);
        Vec3d displacement = vec(b.position()).subtract(vec(a.position()));
        return new RigidMotionSnapshot(new RigidPose(vec(a.position()), BodyOrientation3d.frame(
                new Quatd(a.orientation().x, a.orientation().y, a.orientation().z, a.orientation().w))),
                displacement.x(), displacement.y(), displacement.z(), angular.x(), angular.y(), angular.z(),
                time.gameTick(), entry.revision, entry.epoch, 1, true);
    }
    private static OrientedBox shape(Pose3d pose, BlockPos pos, net.minecraft.world.phys.AABB box) {
        var center = new Vector3d((box.minX+box.maxX)*.5+pos.getX(), (box.minY+box.maxY)*.5+pos.getY(),
                (box.minZ+box.maxZ)*.5+pos.getZ()).sub(pose.rotationPoint()).mul(pose.scale());
        var half = new Vector3d(box.getXsize()*.5, box.getYsize()*.5, box.getZsize()*.5).mul(pose.scale());
        return new OrientedBox(vec(center), vec(half), OrthonormalFrame3d.IDENTITY);
    }
    private static Aabb3d inverseBounds(Pose3d pose, Aabb3d bounds) {
        Vector3d min = new Vector3d(Double.POSITIVE_INFINITY), max = new Vector3d(Double.NEGATIVE_INFINITY);
        for (int i=0;i<8;i++) {
            var v = pose.transformPositionInverse(new Vector3d((i&1)==0?bounds.minX():bounds.maxX(),
                    (i&2)==0?bounds.minY():bounds.maxY(), (i&4)==0?bounds.minZ():bounds.maxZ()));
            min.min(v); max.max(v);
        }
        return new Aabb3d(min.x,min.y,min.z,max.x,max.y,max.z);
    }
    private static Vec3d vec(Vector3dc v) { return new Vec3d(v.x(),v.y(),v.z()); }
}
