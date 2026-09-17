package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.geometry.*;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterDimensions;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState;
import cc.sighs.gravityengine.gravity.runtime.RestingContactSnapshot;
import cc.sighs.gravityengine.math.Quatd;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SoftPositionSupportTest {
    private static final long TICK = 40;
    private static final CharacterDimensions DIMS = new CharacterDimensions(.6, 1.8);
    private static final BlockObstacle FLOOR = SceneFixtures.block(new CellPos(0, 0, 0));
    private static final GravityFrame FRAME = GravityFrame.fromDown(new Vec3d(-.5, -Math.sqrt(.75), 0), .08);
    private static final Vec3d ANCHOR = new Vec3d(.5, 1 + .3 + .6 * Math.sqrt(.75) - .9, .5);

    @Test
    void verifiedSoftWitnessCanOwnTheNextGeometryTransitionButNeverPacketP() {
        var candidate = trusted();
        var runtime = new GravityOperationState();
        runtime.setRestingContactSnapshot(candidate);
        runtime.invalidateMovementContinuity();
        runtime.stageSoftPositionSupportRevalidation(candidate);
        var hint = runtime.consumeSoftPositionSupportRevalidation().orElseThrow();
        assertTrue(runtime.consumeSoftPositionSupportRevalidation().isEmpty());
        assertNull(runtime.restingContactSnapshot());
        var anchor = ANCHOR.add(0, 0, 5e-5);
        var scene = scene(TICK + 1, List.of(FLOOR));
        var verified = query(anchor, scene, Vec3d.ZERO, hint).support().orElseThrow();
        assertTrue(verified.planePreservationEligible());
        assertEquals(TICK + 1, verified.gameTick());
        assertEquals(hint.faceIdentity(), verified.faceIdentity());

        assertEquals("NO_RESTING_SUPPORT", plan(anchor, scene, null,
                PositionAuthorityPolicy.OPERATION_MAY_REANCHOR).rejectionReason());
        assertEquals(GeometryTransitionKind.PRESERVE_SUPPORT, plan(anchor, scene, verified,
                PositionAuthorityPolicy.OPERATION_MAY_REANCHOR).kind());
        var packet = plan(anchor, scene, verified, PositionAuthorityPolicy.EXTERNAL_POSITION_ANCHOR);
        assertEquals(GeometryTransitionKind.DEFERRED, packet.kind());
        assertEquals(GravityGeometryTransitionPlanner.POSITION_AUTHORITY_REANCHOR_REASON, packet.rejectionReason());
        assertEquals(Vec3d.ZERO, packet.positionCorrection());
        assertNull(runtime.restingContactSnapshot(), "verification never restores old endpoint authority");
    }

    @Test
    void movedRemovedOrReplacedSupportCannotBeReused() {
        var hint = trusted();
        assertRejected(ANCHOR.add(0, .1, 0), scene(TICK + 1, List.of(FLOOR)), hint);
        assertRejected(ANCHOR.add(2, 0, 0), scene(TICK + 1, List.of(FLOOR)), hint);
        assertRejected(ANCHOR, scene(TICK + 1, List.of()), hint);
        var shortened = new BlockObstacle(FLOOR.blockPos(), new Aabb3d(0, 0, 0, 1, .9, 1));
        assertRejected(ANCHOR, scene(TICK + 1, List.of(shortened)), hint);
        assertTrue(query(ANCHOR, scene(TICK + 1, List.of(FLOOR)), Vec3d.Y, hint).support().isEmpty());
    }

    @Test
    void staleHintCannotWidenFreshAcquisition() {
        // Inside the soft band, but outside the ordinary strict ground probe.
        var anchor = ANCHOR.add(0, 5e-5, 0);
        assertTrue(query(anchor, scene(TICK + 1, List.of(FLOOR)), Vec3d.ZERO, trusted()).support().isPresent());
        assertRejected(anchor, scene(TICK + 2, List.of(FLOOR)), trusted());
    }

    @Test
    void discontinuityAndOwnershipBoundariesEraseTheOneShotHint() {
        for (int boundary = 0; boundary < 6; boundary++) {
            var runtime = new GravityOperationState();
            runtime.stageSoftPositionSupportRevalidation(trusted());
            switch (boundary) {
                case 0 -> runtime.invalidateMovementContinuity(); // teleport/dimension position owner
                case 1 -> runtime.clearInfluenceTransientState(); // application/ownership handoff
                case 2 -> runtime.clearFrameContinuity(); // discontinuous reference
                case 3 -> runtime.markBodyDimensionsChanged();
                case 4 -> runtime.setInstalledCollisionAxis(FRAME.up());
                case 5 -> runtime.clearInstalledCollisionAxis();
            }
            assertTrue(runtime.consumeSoftPositionSupportRevalidation().isEmpty());
        }
    }

    private static void assertRejected(Vec3d anchor, CollisionScene scene, RestingContactSnapshot hint) {
        var result = query(anchor, scene, Vec3d.ZERO, hint);
        assertFalse(result.indeterminate());
        assertTrue(result.support().isEmpty());
        assertNotEquals(GeometryTransitionKind.PRESERVE_SUPPORT, plan(anchor, scene,
                result.support().orElse(null), PositionAuthorityPolicy.OPERATION_MAY_REANCHOR).kind());
    }

    private static RestingContactSnapshot trusted() {
        var result = query(ANCHOR, scene(TICK, List.of(FLOOR)), Vec3d.ZERO, null);
        return result.support().orElseThrow();
    }

    private static CollisionBody body(Vec3d anchor) {
        return GravityGeometryTransitionPlanner.posePreservingPositionAnchor(DIMS, anchor, FRAME).body();
    }

    private static StepStartSupportQuery.Result query(Vec3d anchor, CollisionScene scene,
                                                     Vec3d velocity, RestingContactSnapshot hint) {
        return StepStartSupportQuery.query(body(anchor), FRAME, scene, new ObbQueryContext(), velocity, scene.tick(), hint);
    }

    private static OperationPoseTransition plan(Vec3d anchor, CollisionScene scene,
                                                RestingContactSnapshot support, PositionAuthorityPolicy authority) {
        var proposed = GravityFrame.fromAcceleration(Vec3d.ZERO,
                Quatd.rotationZ(Math.toRadians(.5)).transform(FRAME.down()).multiply(.08), FRAME.down(), FRAME);
        return GravityGeometryTransitionPlanner.plan(new GeometryTransitionRequest(FRAME, body(anchor), anchor,
                proposed, DIMS, scene, new ObbQueryContext(), authority, support, scene.tick()));
    }

    private static CapturedCollisionScene scene(long tick, List<BlockObstacle> blocks) {
        return SceneFixtures.scene(tick, tick, 1, blocks, List.of());
    }
}
