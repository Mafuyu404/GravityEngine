/**
 * Supported Minecraft-aware GravityEngine API for the NeoForge 1.21.1 target.
 *
 * <p>This package is part of GravityEngine's supported public API together
 * with the loader-neutral
 * {@code cc.sighs.gravityengine.api.field} and
 * {@code cc.sighs.gravityengine.api.math} packages in {@code common/}.
 * Consumers should depend only on these supported GravityEngine API
 * namespaces, plus normal Minecraft and NeoForge types.</p>
 *
 * <p>GravityEngine-owned vectors in this target API are immutable
 * {@code cc.sighs.gravityengine.api.math.Vec3d} values. Minecraft
 * {@code Vec3} is reserved for actual Minecraft-facing call boundaries.</p>
 *
 * <p>Supported entry points:</p>
 *
 * <ul>
 *     <li>{@link cc.sighs.gravityengine.api.GravityEngineApi#publish} -
 *     publish or replace a field in one {@code Level}.</li>
 *     <li>{@link cc.sighs.gravityengine.api.GravityEngineApi#sample} -
 *     composed sampling, position-only or full physics.</li>
 *     <li>{@link cc.sighs.gravityengine.api.GravityEngineApi#entityGravity} -
 *     read-only entity gravity snapshot.</li>
 *     <li>{@link cc.sighs.gravityengine.api.GravityFieldDefinition} -
 *     publication descriptor with {@code named} and {@code block}
 *     factories.</li>
 *     <li>{@link cc.sighs.gravityengine.api.GravityFieldIds} - deterministic
 *     per-instance publication identity for block-backed fields.</li>
 *     <li>{@link cc.sighs.gravityengine.api.FieldPublication} - revision-safe
 *     lifecycle lease returned by publication.</li>
 *     <li>{@link cc.sighs.gravityengine.api.field.GravityFields} - built-in
 *     field and influence factories.</li>
 * </ul>
 *
 * <p>Deliberately not part of this API version: DIRECT assignment and its
 * release back to FIELD authority, body-attitude transactions, collision and
 * movement integration, networking, client and rendering, spatial-index or
 * runtime mutation. Those remain implementation details with unstable
 * contracts. See {@code docs/API_BOUNDARY.md}.</p>
 */
package cc.sighs.gravityengine.api;
