package cc.sighs.gravityengine.gravity.integration.geometry;

import cc.sighs.gravityengine.gravity.geometry.BodyRepresentation;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.model.BodyTransitionDecision;
import cc.sighs.gravityengine.gravity.model.GravityAccelerationMode;
import cc.sighs.gravityengine.gravity.model.GravityApplicationPlan;
import cc.sighs.gravityengine.gravity.model.GravityCollisionRoute;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ownership coverage of {@link BodyHandoffState}: application semantics,
 * collision routing, installed body geometry and the kinematic anchor are four
 * independent dimensions, and each comparison helper compares only its own.
 */
class BodyHandoffStateTest {
    private static final GravityApplicationPlan CHARACTER_DIRECT =
            GravityApplicationPlan.character(GravityAccelerationMode.DIRECT);
    private static final GravityApplicationPlan CHARACTER_FIELD =
            GravityApplicationPlan.character(GravityAccelerationMode.FIELD);
    private static final Vec3 ORIGIN = new Vec3(0.5, 64.0, 0.5);
    private static final Vec3 FALLING = new Vec3(-0.499, -0.839, -0.135);

    /** The platform AABB is installed: no exact axis, no exact collider. */
    private static final InstalledBodySnapshot NATIVE_BODY =
            new InstalledBodySnapshot(
                    BodyRepresentation.NATIVE_AABB, 7L, Pose.STANDING,
                    0.6D, 1.8D, null);

    /** An exact rotated body with the *same* extents and pose as the AABB. */
    private static final InstalledBodySnapshot EXACT_BODY =
            new InstalledBodySnapshot(
                    BodyRepresentation.EXACT_BODY, 7L, Pose.STANDING,
                    0.6D, 1.8D, new Vec3d(1.0D, 0.0D, 0.0D));

    /** Case 1: application-only change over unchanged installed geometry. */
    @Test
    void applicationOnlyChangeIsSynchronizationOnly() {
        BodyHandoffState before = state(
                CHARACTER_DIRECT, 3L, false, GravityCollisionRoute.VANILLA,
                NATIVE_BODY, ORIGIN);
        BodyHandoffState after = state(
                CHARACTER_FIELD, 4L, false, GravityCollisionRoute.VANILLA,
                NATIVE_BODY, ORIGIN);

        assertTrue(after.applicationDiffersFrom(before));
        assertFalse(after.routingDiffersFrom(before));
        assertFalse(after.installedGeometryDiffersFrom(before));
        assertFalse(after.positionAnchorDiffersFrom(before));

        BodyTransitionDecision decision =
                PlayerBodyHandoff.transitionDecision(before, after, false);
        assertEquals(BodyTransitionDecision.SYNC_ONLY, decision);
        assertNotEquals(BodyTransitionDecision.GEOMETRY_REPRESENTATION_CHANGE, decision);
        assertNotEquals(BodyTransitionDecision.PHYSICAL_RELOCATION, decision);
    }

    /**
     * Case 2: the movement/collision route changes while the installed body
     * stays exactly the same. Routing is a movement-path ownership decision, so
     * it is allowed to change on its own and must not be read as installed
     * geometry.
     */
    @Test
    void collisionRouteOnlyChangeIsNotInstalledGeometry() {
        BodyHandoffState before = state(
                CHARACTER_DIRECT, 3L, false, GravityCollisionRoute.VANILLA,
                NATIVE_BODY, ORIGIN);
        BodyHandoffState after = state(
                CHARACTER_DIRECT, 3L, false, GravityCollisionRoute.EXACT_BODY,
                NATIVE_BODY, ORIGIN);

        assertFalse(after.applicationDiffersFrom(before));
        assertTrue(after.routingDiffersFrom(before));
        assertFalse(after.installedGeometryDiffersFrom(before));
        assertFalse(after.positionAnchorDiffersFrom(before));

        BodyTransitionDecision decision =
                PlayerBodyHandoff.transitionDecision(before, after, false);
        assertEquals(BodyTransitionDecision.SYNC_ONLY, decision);
    }

    /**
     * Case 3: only the installed body/collider shape generation advanced.
     * The application plan, the routing and the anchor are unchanged.
     */
    @Test
    void installedShapeRevisionChangeIsRepresentationChange() {
        BodyHandoffState before = state(
                CHARACTER_DIRECT, 3L, false, GravityCollisionRoute.EXACT_BODY,
                EXACT_BODY, ORIGIN);
        InstalledBodySnapshot nextGeneration = new InstalledBodySnapshot(
                BodyRepresentation.EXACT_BODY, 8L, Pose.STANDING,
                0.6D, 1.8D, new Vec3d(1.0D, 0.0D, 0.0D));
        BodyHandoffState after = state(
                CHARACTER_DIRECT, 3L, false, GravityCollisionRoute.EXACT_BODY,
                nextGeneration, ORIGIN);

        assertFalse(after.applicationDiffersFrom(before));
        assertFalse(after.routingDiffersFrom(before));
        assertFalse(after.positionAnchorDiffersFrom(before));
        assertTrue(after.installedGeometryDiffersFrom(before));
        assertEquals(
                BodyTransitionDecision.GEOMETRY_REPRESENTATION_CHANGE,
                PlayerBodyHandoff.transitionDecision(before, after, false)
        );
    }

    /**
     * Case 4: the installed representation changes from the platform AABB to an
     * exact rotated body whose enclosing box and pose are identical.
     *
     * <p>Equal extents are not equal installed geometry: the representation and
     * the installed axis are the identity, never the world-space box.</p>
     */
    @Test
    void installedRepresentationChangeIsRepresentationChange() {
        BodyHandoffState before = state(
                CHARACTER_DIRECT, 3L, false, GravityCollisionRoute.EXACT_BODY,
                NATIVE_BODY, ORIGIN);
        BodyHandoffState after = state(
                CHARACTER_DIRECT, 3L, false, GravityCollisionRoute.EXACT_BODY,
                EXACT_BODY, ORIGIN);

        assertFalse(after.applicationDiffersFrom(before));
        assertFalse(after.routingDiffersFrom(before));
        assertTrue(after.installedGeometryDiffersFrom(before));
        assertEquals(
                BodyTransitionDecision.GEOMETRY_REPRESENTATION_CHANGE,
                PlayerBodyHandoff.transitionDecision(before, after, false)
        );
    }

    /** An installed-orientation change is a body change even within one enum
     * value: the same exact-representation kind may carry a different axis. */
    @Test
    void installedAxisChangeIsRepresentationChange() {
        InstalledBodySnapshot otherAxis = new InstalledBodySnapshot(
                BodyRepresentation.EXACT_BODY, 7L, Pose.STANDING,
                0.6D, 1.8D, new Vec3d(0.0D, 1.0D, 0.0D));
        BodyHandoffState before = state(
                CHARACTER_DIRECT, 3L, false, GravityCollisionRoute.EXACT_BODY,
                EXACT_BODY, ORIGIN);
        BodyHandoffState after = state(
                CHARACTER_DIRECT, 3L, false, GravityCollisionRoute.EXACT_BODY,
                otherAxis, ORIGIN);
        assertTrue(after.installedGeometryDiffersFrom(before));
    }

    /** Local extents are installed geometry; world-space translation is not. */
    @Test
    void localExtentsChangeIsGeometryAndTranslationIsNot() {
        InstalledBodySnapshot crouched = new InstalledBodySnapshot(
                BodyRepresentation.NATIVE_AABB, 8L, Pose.CROUCHING,
                0.6D, 1.5D, null);
        BodyHandoffState upright = state(
                CHARACTER_DIRECT, 3L, false, GravityCollisionRoute.VANILLA,
                NATIVE_BODY, ORIGIN);
        BodyHandoffState crouch = state(
                CHARACTER_DIRECT, 3L, false, GravityCollisionRoute.VANILLA,
                crouched, ORIGIN);
        BodyHandoffState translated = state(
                CHARACTER_DIRECT, 3L, false, GravityCollisionRoute.VANILLA,
                NATIVE_BODY, ORIGIN.add(12.0D, -7.5D, 3.25D));

        assertTrue(crouch.installedGeometryDiffersFrom(upright));
        assertFalse(translated.installedGeometryDiffersFrom(upright));
        assertTrue(translated.positionAnchorDiffersFrom(upright));
        assertEquals(
                BodyTransitionDecision.PHYSICAL_RELOCATION,
                PlayerBodyHandoff.transitionDecision(upright, translated, true)
        );
    }

    /** A resynchronization request over unchanged state stays synchronization
     * only and never becomes geometry or relocation. */
    @Test
    void resyncRequestIsSynchronizationOnly() {
        BodyHandoffState before = state(
                CHARACTER_DIRECT, 3L, false, GravityCollisionRoute.VANILLA,
                NATIVE_BODY, ORIGIN);
        BodyHandoffState after = state(
                CHARACTER_DIRECT, 3L, false, GravityCollisionRoute.VANILLA,
                NATIVE_BODY, ORIGIN);
        assertEquals(
                BodyTransitionDecision.SYNC_ONLY,
                PlayerBodyHandoff.transitionDecision(before, after, true)
        );
    }

    private static BodyHandoffState state(
            GravityApplicationPlan plan,
            long epoch,
            boolean nativeApplicationCommit,
            GravityCollisionRoute route,
            InstalledBodySnapshot installedBody,
            Vec3 positionAnchor
    ) {
        return new BodyHandoffState(
                new BodyHandoffState.ApplicationSnapshot(
                        plan, epoch, nativeApplicationCommit),
                route,
                installedBody,
                new BodyHandoffState.BodyAnchorSnapshot(positionAnchor, FALLING)
        );
    }
}
