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
public final class GravityFieldRegistry implements GravityFieldEvaluationSource {
    @Override
    public cc.sighs.gravityengine.gravity.acceleration.GravityFieldEvaluation evaluate(
            cc.sighs.gravityengine.api.field.GravityFieldQuery query) {
        return new cc.sighs.gravityengine.gravity.acceleration.GravityFieldEvaluation(
                query, GravityFieldService.sample(this, query.position(), query.velocity(),
                query.gameTick(), query.intervalTicks()),
                cc.sighs.gravityengine.api.field.FieldCoverage.COMPLETE, publicationRevision());
    }
    private final Map<GravityFieldId, RegisteredField> instances =
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

    /** Opaque, non-reusable identity. It deliberately retains no consumer resources. */
    public static final class PublicationToken {
        private PublicationToken() {}
    }

    private record RegisteredField(GravityFieldInstance instance, String owner, PublicationToken token) {}
    private boolean preparing;

    /**
     * Atomically installs an instance, spatial membership and optional producer owner.
     * Null means stale rejection. Revision orders definitions; only the token owns release.
     * Validation/callback failures leave the old registration and generation intact.
     */
    public synchronized PublicationToken publish(GravityFieldInstance instance, String owner) {
        requireWritable();
        Objects.requireNonNull(instance, "instance");
        RegisteredField current = instances.get(instance.id());
        if (current != null && !Objects.equals(current.owner(), owner)) {
            throw new IllegalArgumentException("field identity belongs to another provider: " + instance.id());
        }
        if (current != null && instance.revision() <= current.instance().revision()) return null;
        preparing = true;
        try {
            var membership = GravityFieldIndex.prepare(instance.influence());
            requireRevisionCapacity();
            var token = new PublicationToken();
            var registered = new RegisteredField(instance, owner, token);
            index.replace(instance.id(), membership);
            instances.put(instance.id(), registered);
            publicationRevision++;
            return token;
        } finally {
            preparing = false;
        }
    }

    public synchronized boolean isOwnedBy(GravityFieldId id, String owner) {
        var current = instances.get(id);
        return current != null && Objects.equals(current.owner(), owner);
    }

    /** Removal implementation reachable only after token ownership validation. */
    private boolean remove(GravityFieldId id) {
        requireWritable();
        requireRevisionCapacity();
        index.remove(id);
        instances.remove(id);
        publicationRevision++;
        return true;
    }

    /** Removes only the exact successful publication, including across ID/revision reuse. */
    public synchronized boolean removeIfOwned(GravityFieldId id, PublicationToken token) {
        requireWritable();
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(token, "token");
        var current = instances.get(id);
        return current != null && current.token() == token && remove(id);
    }

    public synchronized Optional<GravityFieldInstance> get(GravityFieldId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(instances.get(id)).map(RegisteredField::instance);
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
            GravityFieldInstance instance = get(id).orElse(null);

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
                new ArrayList<>(this.instances.values().stream().map(RegisteredField::instance).toList());

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
        requireWritable();
        requireRevisionCapacity();
        this.instances.clear();
        this.index.clear();
        publicationRevision++;
    }

    private void requireWritable() {
        if (preparing) throw new IllegalStateException("reentrant mutation during publication preparation");
    }

    private void requireRevisionCapacity() {
        if (this.publicationRevision == Long.MAX_VALUE) {
            throw new IllegalStateException(
                    "gravity field publication revision overflow"
            );
        }
    }
}
