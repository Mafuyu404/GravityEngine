package cc.sighs.gravityengine.api;

import cc.sighs.gravityengine.api.field.GravityFieldProviderResult;
import cc.sighs.gravityengine.api.field.GravityFieldQuery;

/** A server Level session for a producer domain registered during mod initialization.
 * Evaluation runs on the owning Level thread. Capture source state and return
 * every applicable contribution with coverage for that same query. A partial
 * positive result is legal. Do not compose across domains or recover entities.
 * Evaluation must not recursively sample composed gravity or mutate publications.
 * Expected discovery uncertainty returns INCOMPLETE; invalid results and
 * unexpected exceptions fail the query rather than becoming complete-empty.
 * Factories must return a non-null session; unavailable source discovery belongs
 * in that session's INCOMPLETE result, not an omitted provider.
 * Use {@link GravityEngineApi#samplePublications} for provider-owned publications.
 * Sessions are closed on Level unload; close must release any source references. */
@FunctionalInterface
public interface GravityFieldProvider extends AutoCloseable {
    GravityFieldProviderResult evaluate(GravityFieldQuery query);

    /**
     * Called once after every factory has returned a session, in stable provider-ID order.
     * Runs on the owning Level thread, outside engine global locks. May publish this
     * provider's initial sources and close their handles; may not publish for another
     * provider or sample (including samplePublications). Normal evaluation starts only
     * after every onOpen succeeds. Factories must construct only: publish/sample calls
     * from a factory fail explicitly instead of recursively creating sessions.
     * Failure revokes initial publications and closes all acquired sessions in reverse
     * construction order. Resources not yet returned by a factory remain its responsibility.
     */
    default void onOpen() {}

    /** Releases source references on unload or failed initialization, on the Level thread.
     * New publication/sampling is forbidden during cleanup. All sessions receive a cleanup
     * attempt; failures propagate with subsequent failures suppressed. */
    @Override
    default void close() {}
}
