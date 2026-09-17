package cc.sighs.gravityengine.gravity.field;

import cc.sighs.gravityengine.gravity.model.GravityFieldId;
import net.minecraft.world.level.Level;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Minecraft-side level-scope owner of the loader-neutral
 * {@link GravityFieldRegistry}.
 *
 * <p>This type owns exactly one Minecraft concern: the identity of the
 * {@link Level} that a registry belongs to, plus the level lifecycle that
 * creates and destroys it. Registration, revision monotonicity, spatial
 * indexing and composition all belong to the common registry; nothing about
 * field mathematics or field lifecycle policy lives here.</p>
 *
 * <p>The level/dimension handle is therefore a lifecycle key in this target
 * map and is never stored inside the common domain state.</p>
 */
public final class GravityFieldRuntime {
    private static final Map<Level, GravityFieldRuntime> INSTANCES =
            new IdentityHashMap<>();

    private final GravityFieldRegistry registry =
            new GravityFieldRegistry();

    private GravityFieldRuntime() {
    }

    public static GravityFieldRuntime get(Level level) {
        Objects.requireNonNull(level, "level");
        synchronized (INSTANCES) {
            return INSTANCES.computeIfAbsent(
                    level,
                    ignored -> new GravityFieldRuntime()
            );
        }
    }

    /**
     * Removes a runtime without constructing one during unload.
     */
    public static void remove(Level level) {
        Objects.requireNonNull(level, "level");

        GravityFieldRuntime removed;
        synchronized (INSTANCES) {
            removed = INSTANCES.remove(level);
        }

        if (removed != null) {
            removed.clear();
        }
    }

    public static int loadedLevelCount() {
        synchronized (INSTANCES) {
            return INSTANCES.size();
        }
    }

    /** Removes one field without constructing a runtime during unload. */
    public static boolean remove(Level level, GravityFieldId id) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        GravityFieldRuntime runtime;
        synchronized (INSTANCES) {
            runtime = INSTANCES.get(level);
        }
        return runtime != null && runtime.registry.remove(id);
    }

    /**
     * Revision-aware removal without constructing a runtime during unload.
     *
     * <p>Used by the publication lease so a stale owner cannot remove a newer
     * accepted instance of the same id.</p>
     */
    public static boolean remove(
            Level level,
            GravityFieldId id,
            long expectedRevision
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        GravityFieldRuntime runtime;
        synchronized (INSTANCES) {
            runtime = INSTANCES.get(level);
        }
        return runtime != null
                && runtime.registry.remove(id, expectedRevision);
    }

    /**
     * The loader-neutral registration authority for this level.
     *
     * <p>Minecraft-facing callers convert their platform values at this
     * boundary and then use only the common registry; physics and composition
     * code never receives a {@link Level}.</p>
     */
    public GravityFieldRegistry registry() {
        return this.registry;
    }

    private void clear() {
        this.registry.clear();
    }
}
