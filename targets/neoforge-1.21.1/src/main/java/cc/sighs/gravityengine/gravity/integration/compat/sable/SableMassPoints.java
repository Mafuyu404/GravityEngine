package cc.sighs.gravityengine.gravity.integration.compat.sable;

import cc.sighs.gravityengine.api.math.Vec3d;
import net.minecraft.core.BlockPos;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Transient evidence attached to the native mass tracker. Native build/edit
 * calls are the only producers; no second world scan or mass authority exists. */
public final class SableMassPoints {
    public static final int MAX_POINTS = 65_536;
    public interface Access {
        SableMassPoints gravityengine$massPoints();
        void gravityengine$massPoints(SableMassPoints points);
    }
    public record Point(Vec3d position, double mass) {}
    private final Map<BlockPos, Point> points = new HashMap<>();
    private List<Point> snapshot;
    private boolean complete = true;

    public void add(BlockPos position, Vec3d center, double deltaMass) {
        snapshot = null;
        if (!complete) return;
        if (!center.isFinite() || !Double.isFinite(deltaMass)) { incomplete(); return; }
        if (deltaMass == 0) return;
        var previous = points.get(position);
        double mass = (previous == null ? 0 : previous.mass()) + deltaMass;
        if (!Double.isFinite(mass) || mass < 0
                || previous != null && deltaMass < 0 && previous.position().distanceSquared(center) > 1e-16) {
            incomplete(); return;
        }
        if (mass == 0) { points.remove(position); return; }
        if (previous == null && points.size() >= MAX_POINTS) { incomplete(); return; }
        // Native edits remove the old state before adding the replacement.
        if (previous != null && previous.position().distanceSquared(center) > 1e-16) {
            incomplete(); return;
        }
        points.put(position.immutable(), new Point(center, mass));
    }
    private void incomplete() { complete = false; points.clear(); }
    /** Null is explicitly incomplete evidence, never an empty successful capture. */
    public List<Point> snapshot() {
        if (!complete) return null;
        if (snapshot == null) snapshot = points.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue).toList();
        return snapshot;
    }
}
