package cc.sighs.gravityengine.gravity.component;

import cc.sighs.gravityengine.gravity.model.GravityAuthorityMode;
import cc.sighs.gravityengine.gravity.model.GravityEntityState;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState;
import cc.sighs.gravityengine.gravity.runtime.MinecraftMoveInteropState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;

import java.util.Objects;
import java.util.Optional;

/**
 * Minecraft entity attachment for GravityEngine.
 *
 * <p>This type owns only Minecraft concerns:</p>
 *
 * <ul>
 *   <li>the {@link Entity} owner and attachment acquisition;</li>
 *   <li>ownership of the loader-neutral
 *       {@link GravityEntityState} domain state;</li>
 *   <li>ownership of the loader-neutral {@link GravityOperationState}
 *       operation/runtime state;</li>
 *   <li>ownership of the target-only {@link MinecraftMoveInteropState};</li>
 *   <li>entity NBT read/write.</li>
 * </ul>
 *
 * <p>It deliberately exposes no domain forwarding surface. Callers that need
 * assignment, authority, suppression, application, pending-target or snapshot
 * behaviour use {@link #state()} directly; callers that need operation,
 * movement or presentation state use {@link #operationState()}; callers that
 * need vanilla collision projection or third-party sub-level evidence use
 * {@link #moveInterop()}. Diagnostics for a rejected remote snapshot belong to
 * the network/application caller, not to this attachment.</p>
 */
public final class EntityGravityComponent {
    static final String NBT_TAG = "GravityEngineGravity",
            NBT_VERSION = "FormatVersion",
            NBT_DOWN_X = "DownX",
            NBT_DOWN_Y = "DownY",
            NBT_DOWN_Z = "DownZ",
            NBT_STRENGTH = "Strength",
            NBT_ASSIGNMENT_REVISION = "AssignmentRev",
            NBT_AUTHORITY = "Authority";
    static final int CURRENT_FORMAT_VERSION = 4;

    private final Entity entity;
    private final GravityEntityState state = new GravityEntityState();
    private final GravityOperationState operationState =
            new GravityOperationState();
    private final MinecraftMoveInteropState moveInterop =
            new MinecraftMoveInteropState(operationState);

    public EntityGravityComponent(Entity entity) {
        this.entity = Objects.requireNonNull(entity);
    }

    public Entity entity() {
        return entity;
    }

    /** The loader-neutral domain state this attachment adapts. */
    public GravityEntityState state() {
        return state;
    }

    /** The loader-neutral operation/movement/runtime state. */
    public GravityOperationState operationState() {
        return operationState;
    }

    /** The target-only movement interoperability state. */
    public MinecraftMoveInteropState moveInterop() {
        return moveInterop;
    }

    // -----------------------------------------------------------------
    // NBT
    // -----------------------------------------------------------------

    public void readFromNbt(CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");

        state.resetForLoad();
        clearWorldDerivedState();

        if (!tag.contains(NBT_TAG, CompoundTag.TAG_COMPOUND)) {
            return;
        }

        CompoundTag root = tag.getCompound(NBT_TAG);

        Optional<GravityEntityState.StoredAssignment> decoded =
                decodeCurrentAssignment(root);

        if (decoded.isEmpty()) {
            return;
        }

        state.restoreDurableAssignment(decoded.get());
    }

    /** Player replacement transfers durable assignment, never the old world's derived state. */
    public void copyDurableAssignmentFrom(EntityGravityComponent source) {
        Objects.requireNonNull(source, "source");
        state.copyDurableAssignmentFrom(source.state);
        clearWorldDerivedState();
    }

    /**
     * Drops every derived value that belongs to the world the entity came
     * from: operation transients, the installed collision axis and any
     * third-party movement evidence.
     */
    private void clearWorldDerivedState() {
        this.operationState.clearInfluenceTransientState();
        this.operationState.clearInstalledCollisionAxis();
        this.moveInterop.clear();
    }

    static Optional<GravityEntityState.StoredAssignment> decodeCurrentAssignment(
            CompoundTag root
    ) {
        Objects.requireNonNull(root, "root");

        if (!root.contains(NBT_VERSION, CompoundTag.TAG_INT)
                || root.getInt(NBT_VERSION) != CURRENT_FORMAT_VERSION) {
            return Optional.empty();
        }

        if (!root.contains(NBT_DOWN_X, CompoundTag.TAG_DOUBLE)
                || !root.contains(NBT_DOWN_Y, CompoundTag.TAG_DOUBLE)
                || !root.contains(NBT_DOWN_Z, CompoundTag.TAG_DOUBLE)
                || !root.contains(NBT_STRENGTH, CompoundTag.TAG_DOUBLE)
                || !root.contains(
                NBT_ASSIGNMENT_REVISION,
                CompoundTag.TAG_LONG
        )
                || !root.contains(NBT_AUTHORITY, CompoundTag.TAG_INT)) {
            return Optional.empty();
        }

        return GravityEntityState.decodeStoredAssignment(
                root.getDouble(NBT_DOWN_X),
                root.getDouble(NBT_DOWN_Y),
                root.getDouble(NBT_DOWN_Z),
                root.getDouble(NBT_STRENGTH),
                root.getLong(NBT_ASSIGNMENT_REVISION),
                root.getInt(NBT_AUTHORITY)
        );
    }

    public void writeToNbt(CompoundTag tag) {
        Objects.requireNonNull(tag);
        var assignedState = state.assignedState();
        var assignedAuthority = state.assignedAuthority();
        if (assignedState.isDefault()
                && assignedAuthority == GravityAuthorityMode.FIELD
                && state.assignmentRevision() == 0L) {
            tag.remove(NBT_TAG);
            return;
        }
        CompoundTag r = new CompoundTag();
        r.putInt(NBT_VERSION, CURRENT_FORMAT_VERSION);
        r.putDouble(NBT_DOWN_X, assignedState.down().x());
        r.putDouble(NBT_DOWN_Y, assignedState.down().y());
        r.putDouble(NBT_DOWN_Z, assignedState.down().z());
        r.putDouble(NBT_STRENGTH, assignedState.strength());
        r.putLong(NBT_ASSIGNMENT_REVISION, state.assignmentRevision());
        r.putInt(NBT_AUTHORITY, assignedAuthority.networkId());
        tag.put(NBT_TAG, r);
    }
}
