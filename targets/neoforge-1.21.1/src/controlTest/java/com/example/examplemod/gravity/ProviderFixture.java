package com.example.examplemod.gravity;

import cc.sighs.gravityengine.api.GravityEngineApi;
import cc.sighs.gravityengine.api.GravityFieldProvider;
import cc.sighs.gravityengine.api.field.FieldCoverage;
import cc.sighs.gravityengine.api.field.GravityFieldProviderResult;
import cc.sighs.gravityengine.api.field.GravityFieldQuery;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import java.util.IdentityHashMap;
import java.util.Map;

/** Explicit test source-domain owner, registered by the test mod before Levels.
 * Tests control query discovery independently of publication membership.
 * Production consumers replace the query predicate with their own source index. */
public final class ProviderFixture {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("examplemod", "sources");
    public static final ResourceLocation SECOND_ID = ResourceLocation.fromNamespaceAndPath("examplemod", "other_sources");
    private static final Map<Level, Session> SESSIONS = new IdentityHashMap<>();
    private static final Map<Level, Session> SECOND_SESSIONS = new IdentityHashMap<>();
    private static Level missingSessionLevel;

    public static void register() {
        GravityEngineApi.registerFieldProvider(ID, level -> create(level, ID, SESSIONS));
        GravityEngineApi.registerFieldProvider(SECOND_ID, level -> create(level, SECOND_ID, SECOND_SESSIONS));
        boolean duplicateRejected = false;
        try { GravityEngineApi.registerFieldProvider(ID, level -> create(level, ID, SESSIONS)); }
        catch (IllegalArgumentException expected) { duplicateRejected = true; }
        if (!duplicateRejected) throw new AssertionError("duplicate provider registration accepted");
    }

    public static boolean hasSessions(Level level) {
        return SESSIONS.containsKey(level) && SECOND_SESSIONS.containsKey(level);
    }

    public static int sessionCount(Level level) {
        return (SESSIONS.containsKey(level) ? 1 : 0) + (SECOND_SESSIONS.containsKey(level) ? 1 : 0);
    }

    public static void missingSession(Level level) { missingSessionLevel = level; }

    private static Session create(ServerLevel level, ResourceLocation id, Map<Level, Session> owner) {
        if (level == missingSessionLevel && id.equals(ID)) return null;
        var session = new Session(level, id, owner);
        owner.put(level, session);
        return session;
    }

    public static void coverage(Level level, FieldCoverage coverage) {
        SESSIONS.get(level).coverage = query -> coverage;
    }

    public static void secondCoverage(Level level, java.util.function.Function<GravityFieldQuery, FieldCoverage> coverage) {
        SECOND_SESSIONS.get(level).coverage = coverage;
    }

    private static final class Session implements GravityFieldProvider {
        private final ServerLevel level;
        private final ResourceLocation id;
        private final Map<Level, Session> owner;
        private java.util.function.Function<GravityFieldQuery, FieldCoverage> coverage = query -> FieldCoverage.COMPLETE;

        private Session(ServerLevel level, ResourceLocation id, Map<Level, Session> owner) {
            this.level = level;
            this.id = id;
            this.owner = owner;
        }

        public GravityFieldProviderResult evaluate(GravityFieldQuery query) {
            return new GravityFieldProviderResult(coverage.apply(query),
                    GravityEngineApi.samplePublications(level, id, query));
        }

        public void close() { owner.remove(level, this); }
    }
}
