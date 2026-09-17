package cc.sighs.gravityengine.gravity.field;

import cc.sighs.gravityengine.api.field.GravityFieldBounds;
import cc.sighs.gravityengine.api.field.GravityInfluenceVolume;
import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.model.GravityFieldId;
import cc.sighs.gravityengine.math.geometry.Aabb3d;

import java.util.*;

/**
 * Pure spatial candidate index for one world scope's registered fields.
 *
 * <p>Finite influence volumes are bucketed. Infinite or excessively large
 * finite volumes live in a global candidate set and are filtered by their
 * exact influence predicate at query time.</p>
 *
 * <p>The index owns only geometry-to-candidate mapping. Identity, revision
 * ordering and lifecycle belong to {@link GravityFieldRegistry}, which is the
 * single owner of the registered instances this index describes.</p>
 */
public final class GravityFieldIndex {
    static final int CELL_SIZE = 16;
    static final int MAX_BUCKETED_CELLS = 4096;

    private final Map<Cell, Set<GravityFieldId>> cellMembership =
            new HashMap<>();

    private final Map<GravityFieldId, Set<Cell>> occupiedCells =
            new HashMap<>();

    private final Set<GravityFieldId> globalFields =
            new HashSet<>();

    /** Adds one influence volume to the candidate structure. */
    public synchronized void add(
            GravityFieldId id,
            GravityInfluenceVolume influence
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(influence, "influence");

        addMembership(id, influence);
    }

    /** Removes one id from the candidate structure. */
    public synchronized void remove(GravityFieldId id) {
        Objects.requireNonNull(id, "id");

        this.globalFields.remove(id);

        Set<Cell> membership =
                this.occupiedCells.remove(id);

        if (membership == null) {
            return;
        }

        for (Cell cell : membership) {
            Set<GravityFieldId> ids =
                    this.cellMembership.get(cell);

            if (ids == null) {
                continue;
            }

            ids.remove(id);

            if (ids.isEmpty()) {
                this.cellMembership.remove(cell);
            }
        }
    }

    /**
     * Candidate ids whose bucket contains {@code position}, plus every global
     * candidate. The caller still applies the exact influence predicate.
     */
    public synchronized Set<GravityFieldId> candidates(Vec3d position) {
        Objects.requireNonNull(position, "position");
        requireFinite(position, "position");

        Set<GravityFieldId> candidates =
                new HashSet<>(this.globalFields);

        Set<GravityFieldId> local =
                this.cellMembership.get(
                        Cell.from(position)
                );

        if (local != null) {
            candidates.addAll(local);
        }

        return candidates;
    }

    public synchronized int globalFieldCount() {
        return this.globalFields.size();
    }

    public synchronized int bucketedFieldCount() {
        return this.occupiedCells.size();
    }

    public synchronized void clear() {
        this.cellMembership.clear();
        this.occupiedCells.clear();
        this.globalFields.clear();
    }

    private void addMembership(
            GravityFieldId id,
            GravityInfluenceVolume influence
    ) {
        Optional<GravityFieldBounds> bounds =
                influence.finiteBounds();

        if (bounds.isEmpty()) {
            this.globalFields.add(id);
            return;
        }

        CellRange range =
                CellRange.forBounds(toInternalBounds(bounds.get()));

        if (range.cellCountExceeds(MAX_BUCKETED_CELLS)) {
            this.globalFields.add(id);
            return;
        }

        Set<Cell> membership =
                new HashSet<>((int) range.cellCount());

        for (int x = range.minX(); x <= range.maxX(); x++) {
            for (int y = range.minY(); y <= range.maxY(); y++) {
                for (int z = range.minZ(); z <= range.maxZ(); z++) {
                    Cell cell = new Cell(x, y, z);

                    membership.add(cell);

                    this.cellMembership
                            .computeIfAbsent(
                                    cell,
                                    ignored -> new HashSet<>()
                            )
                            .add(id);
                }
            }
        }

        this.occupiedCells.put(id, Set.copyOf(membership));
    }

    private static void requireFinite(Vec3d vector, String name) {
        if (!vector.isFinite()) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + vector
            );
        }
    }

    /**
     * Converts the public finite-bounds value into the internal geometry type
     * at the indexing boundary. The public API never sees {@link Aabb3d}.
     */
    private static Aabb3d toInternalBounds(GravityFieldBounds bounds) {
        return new Aabb3d(
                bounds.minX(),
                bounds.minY(),
                bounds.minZ(),
                bounds.maxX(),
                bounds.maxY(),
                bounds.maxZ()
        );
    }

    private record Cell(int x, int y, int z) {
        static Cell from(Vec3d point) {
            return new Cell(
                    cellCoordinate(point.x()),
                    cellCoordinate(point.y()),
                    cellCoordinate(point.z())
            );
        }

        private static int cellCoordinate(double value) {
            return (int) Math.floor(value / CELL_SIZE);
        }
    }

    private record CellRange(
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ
    ) {
        static CellRange forBounds(Aabb3d bounds) {
            return new CellRange(
                    Cell.cellCoordinate(bounds.minX()),
                    Cell.cellCoordinate(bounds.minY()),
                    Cell.cellCoordinate(bounds.minZ()),
                    Cell.cellCoordinate(bounds.maxX()),
                    Cell.cellCoordinate(bounds.maxY()),
                    Cell.cellCoordinate(bounds.maxZ())
            );
        }

        long cellCount() {
            return axisCount(minX, maxX)
                    * axisCount(minY, maxY)
                    * axisCount(minZ, maxZ);
        }

        boolean cellCountExceeds(long limit) {
            long x = axisCount(minX, maxX);
            long y = axisCount(minY, maxY);
            long z = axisCount(minZ, maxZ);

            return x > limit
                    || y > limit
                    || z > limit
                    || x * y > limit
                    || x * y * z > limit;
        }

        private static long axisCount(int min, int max) {
            return (long) max - min + 1L;
        }
    }
}
