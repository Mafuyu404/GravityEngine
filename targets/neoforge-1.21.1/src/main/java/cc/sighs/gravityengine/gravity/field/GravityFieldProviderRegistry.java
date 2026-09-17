package cc.sighs.gravityengine.gravity.field;

import cc.sighs.gravityengine.api.GravityFieldProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import java.util.Map;
import java.util.TreeMap;
import java.util.Objects;
import java.util.function.Function;

/** Mod-initialization definitions. Frozen before the first server Level session. */
public final class GravityFieldProviderRegistry {
    private static final Map<ResourceLocation, Function<ServerLevel, GravityFieldProvider>> DEFINITIONS =
            new TreeMap<>();
    private static boolean frozen;

    private GravityFieldProviderRegistry() {}

    public static synchronized void register(ResourceLocation id, Function<ServerLevel, GravityFieldProvider> factory) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(factory, "factory");
        if (frozen) throw new IllegalStateException("field providers must register before Level creation");
        if (DEFINITIONS.putIfAbsent(id, factory) != null) throw new IllegalArgumentException("duplicate field provider: " + id);
    }

    public static synchronized Map<ResourceLocation, GravityFieldProvider> createSessions(ServerLevel level) {
        frozen = true;
        Map<ResourceLocation, GravityFieldProvider> sessions = new TreeMap<>();
        try {
            for (var definition : DEFINITIONS.entrySet()) {
                sessions.put(definition.getKey(), Objects.requireNonNull(definition.getValue().apply(level),
                        "missing field provider session: " + definition.getKey()));
            }
            return java.util.Collections.unmodifiableMap(sessions);
        } catch (RuntimeException | Error failure) {
            for (var session : sessions.values()) {
                try { session.close(); } catch (RuntimeException | Error closing) { failure.addSuppressed(closing); }
            }
            throw failure;
        }
    }
}
