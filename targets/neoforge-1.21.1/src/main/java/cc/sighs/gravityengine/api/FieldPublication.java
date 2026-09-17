package cc.sighs.gravityengine.api;

import cc.sighs.gravityengine.gravity.field.GravityFieldRuntime;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.model.GravityFieldId;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lifecycle-safe handle for one accepted (or rejected) field publication.
 *
 * <p>The handle remembers the exact level key and revision it was accepted
 * for. {@link #close()} removes only that revision:
 * publishing revision 1, publishing revision 2 for the same id, then closing
 * the revision-1 handle leaves revision 2 registered.</p>
 *
 * <p>The handle holds only a {@link WeakReference} to its {@link Level}, so it
 * can never keep an unloaded level alive and closing it after level unload is
 * harmless. Removal never constructs a runtime: it uses the static
 * revision-aware removal path, so a stale handle cannot resurrect an unloaded
 * level's field runtime.</p>
 *
 * <p>{@link #close()} is idempotent. A {@link Status#REJECTED_STALE} handle
 * owns nothing and closing it is a no-op.</p>
 */
public final class FieldPublication implements AutoCloseable {
    public enum Status {
        /** The submitted revision replaced or created the registration. */
        ACCEPTED,
        /** A registration with an equal or higher revision already existed. */
        REJECTED_STALE
    }

    private final WeakReference<Level> level;
    private final GravityFieldId id;
    private final long revision;
    private final Status status;
    private final AtomicBoolean closed;

    FieldPublication(
            Level level,
            GravityFieldId id,
            long revision,
            Status status
    ) {
        this.level = new WeakReference<>(
                Objects.requireNonNull(level, "level")
        );
        this.id = Objects.requireNonNull(id, "id");
        this.revision = revision;
        this.status = Objects.requireNonNull(status, "status");
        this.closed = new AtomicBoolean(status != Status.ACCEPTED);
    }

    public Status status() {
        return status;
    }

    public boolean accepted() {
        return status == Status.ACCEPTED;
    }

    public ResourceLocation fieldId() {
        return MinecraftMathAdapter.toResourceLocation(id);
    }

    /** The revision this handle owns, whether or not it was accepted. */
    public long revision() {
        return revision;
    }

    /** True once this handle no longer owns a registration. */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Removes exactly the revision this handle owns, if it is still the
     * registered one. Safe to call at any time, including after level unload.
     */
    @Override
    public void close() {
        if (!accepted()) {
            return;
        }
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Level bound = level.get();
        if (bound == null) {
            return;
        }
        GravityFieldRuntime.remove(bound, id, revision);
    }
}
