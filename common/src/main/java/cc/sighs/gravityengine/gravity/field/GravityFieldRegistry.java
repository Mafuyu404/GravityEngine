package cc.sighs.gravityengine.gravity.field;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.model.GravityFieldId;

import java.util.*;

/**
 * Loader-neutral registration authority for every gravity field inside one
 * world scope.
 *
 * <p>One registry instance represents exactly one world/level scope. The
 * scope is expressed by ownership of this object, never by a stored platform
 * dimension handle, so the domain never needs a loader identity to reason
 * about field membership.</p>
 *
 * <p>This owner holds field identity, revision monotonicity and replacement
 * semantics. Spatial candidate discovery is delegated to
 * {@link GravityFieldIndex}; registration never leaks into the spatial
 * structure and the index never decides revisions.</p>
 */
public final class GravityFieldRegistry {
    private final Map<GravityFieldId, GravityFieldInstance> instances =
            new HashMap<>();

    private final GravityFieldIndex index =
            new GravityFieldIndex();

    /*
     * Monotonic publication generation for this registry.
     *
     * This is deliberately independent from any entity assignment revision:
     * an entity's assignment can remain revision 7 while the field registry
     * changes underneath it and invalidates a cached FIELD evaluation.
     */
    private volatile long publicationRevision;

    /**
     * Cheap, thread-visible publication generation. The value changes whenever
     * the effective registered field set could change.
     */
    public long publicationRevision() {
        return this.publicationRevision;
    }

    /**
     * Registers or replaces one instance.
     *
     * <p>A submission is rejected as stale when an equal or higher revision
     * is already registered under the same id; the previously accepted
     * registration then stays in place.</p>
     */
    public synchronized boolean put(GravityFieldInstance instance) {
        Objects.requireNonNull(instance, "instance");

        GravityFieldInstance current =
                this.instances.get(instance.id());

        if (current != null
                && instance.revision() <= current.revision()) {
            return false;
        }

        if (current != null) {
            this.index.remove(current.id());
        }

        this.instances.put(instance.id(), instance);
        this.index.add(instance.id(), instance.influence());
        advancePublicationRevision();
        return true;
    }

    public synchronized boolean remove(GravityFieldId id) {
        Objects.requireNonNull(id, "id");

        GravityFieldInstance removed =
                this.instances.remove(id);

        if (removed == null) {
            return false;
        }

        this.index.remove(id);
        advancePublicationRevision();
        return true;
    }

    /**
     * Revision-aware removal.
     *
     * <p>The registry accepts a later revision of the same id while a
     * previous registration request may still be unregistered. An
     * unconditional id removal would let that stale owner delete the newer
     * accepted instance. Removal therefore only applies when the currently
     * registered instance still belongs to the revision that requested it.</p>
     */
    public synchronized boolean remove(
            GravityFieldId id,
            long expectedRevision
    ) {
        Objects.requireNonNull(id, "id");

        GravityFieldInstance current =
                this.instances.get(id);

        if (current == null
                || current.revision() != expectedRevision) {
            return false;
        }

        this.instances.remove(id);
        this.index.remove(id);
        advancePublicationRevision();
        return true;
    }

    public synchronized Optional<GravityFieldInstance> get(
            GravityFieldId id
    ) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(this.instances.get(id));
    }

    /**
     * Exact active-field query.
     *
     * <p>Candidate discovery is spatial, but membership is decided by the
     * exact influence predicate. Presence is therefore never inferred from
     * broad buckets or from a numeric resultant.</p>
     */
    public synchronized List<GravityFieldInstance> query(Vec3d position) {
        Objects.requireNonNull(position, "position");

        Set<GravityFieldId> candidates =
                this.index.candidates(position);

        List<GravityFieldInstance> result =
                new ArrayList<>(candidates.size());

        for (GravityFieldId id : candidates) {
            GravityFieldInstance instance =
                    this.instances.get(id);

            if (instance == null) {
                continue;
            }

            if (instance.influence().contains(position)) {
                result.add(instance);
            }
        }

        result.sort(GravityFieldInstance.ACCUMULATION_ORDER);

        return List.copyOf(result);
    }

    public synchronized List<GravityFieldInstance> allInstances() {
        List<GravityFieldInstance> result =
                new ArrayList<>(this.instances.values());

        result.sort(GravityFieldInstance.ACCUMULATION_ORDER);

        return List.copyOf(result);
    }

    public synchronized int size() {
        return this.instances.size();
    }

    public synchronized int globalFieldCount() {
        return this.index.globalFieldCount();
    }

    public synchronized int bucketedFieldCount() {
        return this.index.bucketedFieldCount();
    }

    public synchronized void clear() {
        if (this.instances.isEmpty() && this.index.globalFieldCount() == 0
                && this.index.bucketedFieldCount() == 0) {
            return;
        }
        this.instances.clear();
        this.index.clear();
        advancePublicationRevision();
    }

    private void advancePublicationRevision() {
        if (this.publicationRevision == Long.MAX_VALUE) {
            throw new IllegalStateException(
                    "gravity field publication revision overflow"
            );
        }
        this.publicationRevision++;
    }
}
