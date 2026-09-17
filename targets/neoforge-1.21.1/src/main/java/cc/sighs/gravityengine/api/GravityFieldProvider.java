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

    @Override
    default void close() {}
}
