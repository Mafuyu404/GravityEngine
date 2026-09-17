package cc.sighs.gravityengine.gravity.field;

import cc.sighs.gravityengine.gravity.model.GravityFieldId;
import net.minecraft.world.level.Level;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/** Level session owner and sole provider aggregation boundary. Common owns
 * pure composition and publication indexing. Query coverage is never cached
 * as readiness; unload closes every provider and discards all live sources. */
public final class GravityFieldRuntime implements GravityFieldEvaluationSource {
    private boolean closed;
    private boolean evaluating;
    private final Level level;
    private final Map<net.minecraft.resources.ResourceLocation, cc.sighs.gravityengine.api.GravityFieldProvider> providers;
    // Publication-domain membership is independent of source structural order.
    private final Map<GravityFieldId, net.minecraft.resources.ResourceLocation> publicationOwners = new java.util.HashMap<>();

    @Override
    public boolean revisionCoversEvaluation() { return false; }

    @Override
    public long publicationRevision() { return registry.publicationRevision(); }

    @Override
    public cc.sighs.gravityengine.gravity.acceleration.GravityFieldEvaluation evaluate(
            cc.sighs.gravityengine.api.field.GravityFieldQuery query) {
        requireOpenThread();
        Objects.requireNonNull(query, "query");
        if (evaluating) throw new IllegalStateException("recursive field provider evaluation");
        evaluating = true;
        try {
            var coverage = level.isClientSide()
                    ? cc.sighs.gravityengine.api.field.FieldCoverage.INCOMPLETE
                    : cc.sighs.gravityengine.api.field.FieldCoverage.COMPLETE;
            var contributions = new java.util.ArrayList<cc.sighs.gravityengine.api.field.GravityContribution>();
            for (var provider : providers.values()) {
                var result = Objects.requireNonNull(provider.evaluate(query), "field provider result");
                if (result.coverage() == cc.sighs.gravityengine.api.field.FieldCoverage.INCOMPLETE) coverage = result.coverage();
                contributions.addAll(result.contributions());
            }
            return new cc.sighs.gravityengine.gravity.acceleration.GravityFieldEvaluation(query,
                    GravityFieldService.composeContributions(contributions, query), coverage, publicationRevision());
        } finally {
            evaluating = false;
        }
    }

    public boolean publish(net.minecraft.resources.ResourceLocation provider, GravityFieldInstance instance) {
        requireOpenThread();
        if (evaluating) throw new IllegalStateException("publication mutation during field evaluation");
        requireProvider(provider);
        var owner = publicationOwners.get(instance.id());
        if (owner != null && !owner.equals(provider)) throw new IllegalArgumentException("field identity belongs to another provider: " + instance.id());
        boolean accepted = registry.put(instance);
        if (accepted) publicationOwners.put(instance.id(), provider);
        return accepted;
    }

    public java.util.List<cc.sighs.gravityengine.api.field.GravityContribution> samplePublications(
            net.minecraft.resources.ResourceLocation provider, cc.sighs.gravityengine.api.field.GravityFieldQuery query) {
        requireOpenThread();
        requireProvider(provider);
        return GravityFieldService.contributions(registry.query(query.position()).stream()
                .filter(f -> provider.equals(publicationOwners.get(f.id()))).toList(), query);
    }

    private void requireProvider(net.minecraft.resources.ResourceLocation provider) {
        if (!providers.containsKey(provider)) throw new IllegalArgumentException("unregistered field provider: " + provider);
    }

    private void requireOpenThread() {
        if (closed) throw new IllegalStateException("field runtime is closed");
        if (level instanceof net.minecraft.server.level.ServerLevel server && !server.getServer().isSameThread()) {
            throw new IllegalStateException("field operation must run on the owning Level thread");
        }
    }

    private static final Map<Level, GravityFieldRuntime> INSTANCES =
            new IdentityHashMap<>();

    private final GravityFieldRegistry registry =
            new GravityFieldRegistry();

    private final cc.sighs.gravityengine.gravity.integration.FallingBlockRechecks fallingBlocks =
            new cc.sighs.gravityengine.gravity.integration.FallingBlockRechecks();

    public cc.sighs.gravityengine.gravity.integration.FallingBlockRechecks fallingBlocks() {
        return fallingBlocks;
    }

    private GravityFieldRuntime(Level level) {
        this.level = level;
        this.providers = level instanceof net.minecraft.server.level.ServerLevel server
                ? GravityFieldProviderRegistry.createSessions(server) : Map.of();
    }

    public static GravityFieldRuntime get(Level level) {
        Objects.requireNonNull(level, "level");
        synchronized (INSTANCES) {
            return INSTANCES.computeIfAbsent(
                    level,
                    ignored -> new GravityFieldRuntime(level)
            );
        }
    }

    /** Non-creating access for world lifecycle and high-frequency native hooks. */
    public static GravityFieldRuntime getIfPresent(Level level) {
        synchronized (INSTANCES) { return INSTANCES.get(level); }
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

    public static void blockChanged(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos) {
        synchronized (INSTANCES) {
            var runtime = INSTANCES.get(level);
            if (runtime != null) runtime.fallingBlocks.changed(level,pos);
        }
    }

    /** Cleanup callbacks must never recreate a runtime already removed by level unload. */
    public static void chunkUnloaded(Level level, net.minecraft.world.level.ChunkPos pos) {
        synchronized (INSTANCES) {
            var runtime = INSTANCES.get(level);
            if (runtime != null) {
                runtime.fallingBlocks.unloaded(pos);
            }
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
        if (runtime == null) return false;
        runtime.requireOpenThread();
        if (runtime.evaluating) throw new IllegalStateException("publication mutation during field evaluation");
        boolean removed = runtime.registry.remove(id);
        if (removed) runtime.publicationOwners.remove(id);
        return removed;
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
        if (runtime == null) return false;
        runtime.requireOpenThread();
        if (runtime.evaluating) throw new IllegalStateException("publication mutation during field evaluation");
        boolean removed = runtime.registry.remove(id, expectedRevision);
        if (removed) runtime.publicationOwners.remove(id);
        return removed;
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
        this.closed = true;
        this.publicationOwners.clear();
        this.fallingBlocks.clear();
        this.registry.clear();
        RuntimeException failure = null;
        for (var provider : providers.values()) {
            try { provider.close(); }
            catch (RuntimeException exception) {
                if (failure == null) failure = exception; else failure.addSuppressed(exception);
            }
        }
        if (failure != null) throw failure;
    }
}
