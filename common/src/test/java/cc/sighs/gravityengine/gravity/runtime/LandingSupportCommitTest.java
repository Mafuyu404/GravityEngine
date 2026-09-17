package cc.sighs.gravityengine.gravity.runtime;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterCapsule;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.gravity.model.*;
import cc.sighs.gravityengine.math.geometry.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LandingSupportCommitTest {
    private static final KinematicStepContext TIME = KinematicStepContext.fullTick(42, 1);
    private static CollisionScene scene(Vec3d surface) {
        var builder = new CollisionSceneBuilder(TIME);
        builder.addObstacle(new DynamicCollisionObstacleSnapshot(1, 0,
                new OrientedBox(new Vec3d(0,-.5,0), new Vec3d(3,.5,3), OrthonormalFrame3d.IDENTITY),
                new RigidMotionSnapshot(new RigidPose(Vec3d.ZERO, OrthonormalFrame3d.IDENTITY),
                        surface, Vec3d.ZERO, 42, 1, 1, 1)));
        return new CapturedCollisionScene(CollisionCaptureDomain.around(new Aabb3d(-16,-16,-16,16,16,16),8),
                42,1,TIME,new CollisionWorkTracker(CollisionWorkBudget.defaults()),
                new WorldBorderCollisionSnapshot(-100,-100,100,100),java.util.List.of(),builder.obstacles(),
                java.util.List.of(),java.util.List.of(),java.util.Map.of(),0);
    }
    private static GravityMoveResult install(GravityOperationState state, Vec3d surface) {
        var scene = state.collisionOperation() == null ? scene(surface) : state.collisionOperation().scene();
        var context = new ObbQueryContext();
        if (state.collisionOperation() == null) state.setCollisionOperation(new GravityOperationState.CollisionOperationContext(GravityFrame.DEFAULT,
                TIME, scene, context, new CollisionWorkTracker(CollisionWorkBudget.defaults())));
        var result = GravityCharacterRoute.resolve(new CharacterCapsule(new Vec3d(0,1.1,0), Vec3d.Y,.3,.6),
                new Vec3d(0,-.5,0), GravityFrame.DEFAULT, scene, context);
        assertTrue(result.terminalGrounded(), result.toString());
        state.setCurrentMoveResult(result);
        return result;
    }
    @Test void normalResponseReceivesNoSecondSurfaceIncrementAndTangentialCarryRemains() {
        var state = new GravityOperationState();
        try (var scope = state.openMove(GravityFrame.DEFAULT, 42, GravityOperationType.MOVE)) {
            var result = install(state, new Vec3d(.2,.1,0));
            state.recordLandingVelocityResponse(result, result.movementSupportContact().orElseThrow());
            var missing = state.claimSupportVelocity(result);
            assertEquals(.2, missing.x(), 1e-12);
            assertEquals(0, missing.y(), 1e-12);
            assertEquals(Vec3d.ZERO, state.claimSupportVelocity(result));
            state.finalizeSupportVelocity(new Vec3d(.2,.7,0));
        }
        assertNull(state.persistentSupportState());
        assertNull(state.restingContactSnapshot());
        assertEquals(Vec3d.ZERO, state.supportVelocityContribution());
    }
    @Test void creditedPlatformAccelerationAndTurnReplaceOnlyMissingMomentum() {
        var state = new GravityOperationState();
        try (var scope = state.openMove(GravityFrame.DEFAULT,42,GravityOperationType.MOVE)) {
            var result = install(state,new Vec3d(-.2,.15,.1));
            state.setSupportVelocityContribution(new Vec3d(.3,.1,0));
            state.recordLandingVelocityResponse(result,result.movementSupportContact().orElseThrow());
            var missing = state.claimSupportVelocity(result);
            assertEquals(-.5,missing.x(),1e-12);
            assertEquals(0,missing.y(),1e-12,"native response owns the new normal speed");
            assertEquals(.1,missing.z(),1e-12);
            assertEquals(Vec3d.ZERO,state.claimSupportVelocity(result));
        }
    }
    @Test void finalVelocityRevokesOldTerminalEvidenceForMoveAndTravel() {
        for (var type : new GravityOperationType[]{GravityOperationType.MOVE, GravityOperationType.TRAVEL}) {
            var state = new GravityOperationState();
            try (var scope = state.openMove(GravityFrame.DEFAULT,42,type)) {
                var result = install(state,Vec3d.ZERO);
                state.updateRestingContactSnapshot(42,result);
                assertNotNull(state.restingContactSnapshot());
                assertTrue(state.finalizeSupportVelocity(new Vec3d(0,.5,0)));
                assertSame(result,state.currentMoveResult(), "keep instantaneous collision evidence");
                assertTrue(state.finalizeSupportVelocity(Vec3d.ZERO), "revocation cannot resurrect stale evidence");
            }
            assertNull(state.restingContactSnapshot());
            assertNull(state.persistentSupportState());
            assertFalse(state.completedEndpointGround().orElseThrow().terminalSupported());
        }
    }
    @Test void tangentMotionAndNumericalNoiseKeepSupport() {
        var state = new GravityOperationState();
        try (var scope = state.openMove(GravityFrame.DEFAULT,42,GravityOperationType.TRAVEL)) {
            install(state, new Vec3d(0,.1,0));
            assertFalse(state.finalizeSupportVelocity(new Vec3d(.3,.1+CollisionTolerances.ENTERING_PLANE_EPSILON*.5,.2)));
        }
        assertNotNull(state.restingContactSnapshot());
        assertNotNull(state.persistentSupportState());
    }
    @Test void nestedFailureCannotConsumeParentResponseOrLeakReceiptIntoNextOperation() {
        var state = new GravityOperationState();
        try (var parent = state.openMove(GravityFrame.DEFAULT,42,GravityOperationType.MOVE)) {
            state.beginMovement(GravityCollisionRoute.EXACT_BODY);
            var first = install(state,new Vec3d(0,.1,0));
            state.recordLandingVelocityResponse(first,first.movementSupportContact().orElseThrow());
            assertThrows(IllegalArgumentException.class, () -> {
                try (var child = state.openMove(GravityFrame.DEFAULT,42,GravityOperationType.MOVE)) {
                    state.beginMovement(GravityCollisionRoute.EXACT_BODY);
                    var second = install(state,new Vec3d(0,.2,0));
                    state.recordLandingVelocityResponse(first,first.movementSupportContact().orElseThrow());
                    assertEquals(.1,state.claimSupportVelocity(second).y(),1e-12);
                    throw new IllegalArgumentException("callback failed");
                }
            });
            assertSame(first,state.currentMoveResult());
            assertEquals(0,state.claimSupportVelocity(first).y(),1e-12);
        }
        state.clearPersistentSupportState();
        try (var next = state.openMove(GravityFrame.DEFAULT,43,GravityOperationType.MOVE)) {
            var result = install(state,new Vec3d(0,.1,0));
            assertEquals(.1,state.claimSupportVelocity(result).y(),1e-12);
        }
    }
}
