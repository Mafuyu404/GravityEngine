package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PassiveLandingTest {
    private static final OrientedBox BODY = OrientedBox.axisAligned(new Aabb3d(-.49,0,-.49,.49,.98,.49));
    private static final BlockObstacle WALL = new BlockObstacle(new CellPos(2,0,0), new Aabb3d(2,-10,-10,2.0625,10,10));
    private static GravityFrame frame(Vec3d a) {
        return GravityFrame.fromEnvironmentalEvidence(Vec3d.ZERO,a,Vec3d.Y.negate(),null);
    }
    @Test void highSpeedThinWallIsGeometryRegardlessOfEndpointGravity() {
        for(var acceleration:List.of(Vec3d.X,Vec3d.X.negate(),Vec3d.Y,Vec3d.ZERO)) {
            var start=frame(Vec3d.X);
            var end=frame(acceleration);
            var calls=new AtomicInteger();
            var result=CombinedVectorRoute.resolve(BODY,new Vec3d(20,0,0),start,
                    SceneFixtures.scene(1,1,1,List.of(WALL),List.of()),new ObbQueryContext(), body->{
                        calls.incrementAndGet(); assertEquals(1.51,body.center().x(),1e-6); return end;
                    });
            assertFalse(result.indeterminate());
            assertEquals(1,calls.get());
            assertEquals(1.51,result.appliedMovement().x(),1e-6);
            assertSame(start,result.frame()); assertSame(end,result.endpointFrame());
            assertFalse(result.impacts().isEmpty()); assertTrue(result.blockedDown());
            assertEquals(acceleration.equals(Vec3d.X),result.supported());
            assertEquals(0,CombinedVectorRoute.projectVelocity(new Vec3d(20,0,0),result.blockingNormals()).x(),1e-8);
        }
    }
    @Test void obliqueGravityWallPreservesTangentialMotionAndMaterialIdentity() {
        var result=CombinedVectorRoute.resolve(BODY,new Vec3d(3,-1,0),frame(new Vec3d(1,-.4,0)),
                SceneFixtures.scene(1,1,1,List.of(WALL),List.of()),new ObbQueryContext());
        assertFalse(result.indeterminate()); assertTrue(result.supported());
        assertEquals(-1,result.appliedMovement().y(),1e-8);
        var impact=CombinedVectorRoute.materialContact(result.impacts().get(0));
        assertEquals(Vec3d.X.negate(),impact.normal()); assertEquals(WALL.blockPos(),impact.faceIdentity().block());
        assertEquals(new Vec3d(0,-1,0),CombinedVectorRoute.projectVelocity(new Vec3d(3,-1,0),result.blockingNormals()));
    }
    @Test void adjacentFacesConstrainBothComponentsWithoutInventingSupportAtZero() {
        var floor=new BlockObstacle(new CellPos(0,-2,0),new Aabb3d(-10,-2,-10,10,-1.9375,10));
        var result=CombinedVectorRoute.resolve(BODY,new Vec3d(20,-20,0),frame(new Vec3d(1,-1,0)),
                SceneFixtures.scene(1,1,1,List.of(WALL,floor),List.of()),new ObbQueryContext(),body->frame(Vec3d.ZERO));
        assertFalse(result.indeterminate()); assertFalse(result.supported());
        assertEquals(2,result.blockingNormals().size());
        assertEquals(Vec3d.ZERO,CombinedVectorRoute.projectVelocity(new Vec3d(20,-20,0),result.blockingNormals()));
    }

    @Test void terminalMaterialIdentityPersistsAndOutwardBounceRevokesItAtFinalVelocity() {
        for(boolean bounce:List.of(false,true)) {
            var scene=SceneFixtures.scene(1,1,1,List.of(WALL),List.of());
            var reference=frame(Vec3d.X);
            var context=new ObbQueryContext();
            var result=CombinedVectorRoute.resolve(BODY,new Vec3d(3,0,0),reference,scene,context);
            assertTrue(result.endpointContact().orElseThrow().planePreservationEligible());
            var runtime=new cc.sighs.gravityengine.gravity.runtime.GravityOperationState();
            try(var operation=runtime.openMove(reference,1,cc.sighs.gravityengine.gravity.model.GravityOperationType.TRAVEL)) {
                runtime.setCollisionOperation(new cc.sighs.gravityengine.gravity.runtime.GravityOperationState.CollisionOperationContext(
                        reference,scene.time(),scene,context,new CollisionWorkTracker(CollisionWorkBudget.defaults())));
                runtime.beginMovement(cc.sighs.gravityengine.gravity.model.GravityCollisionRoute.PASSIVE_AABB);
                runtime.setCurrentPassiveMoveResult(result);
                assertEquals(bounce,runtime.finalizeSupportVelocity(bounce?Vec3d.X.negate():Vec3d.ZERO));
                assertEquals(bounce,runtime.finalizeSupportVelocity(bounce?Vec3d.X.negate():Vec3d.ZERO));
                assertFalse(runtime.currentPassiveMoveResult().impacts().isEmpty(),"revocation keeps impact receipt");
            }
            assertEquals(!bounce,runtime.completedEndpointGround().orElseThrow().terminalSupported());
            assertEquals(!bounce,runtime.persistentSupportState()!=null);
            if(!bounce) assertEquals(WALL.blockPos(),runtime.persistentSupportState().identity().block());
        }
    }

    @Test void exhaustedWorkCannotBecomeSuccessfulLanding() {
        var context=new ObbQueryContext();
        context.setWorkTracker(new CollisionWorkTracker(new CollisionWorkBudget(100,10,1,1,1)));
        var result=CombinedVectorRoute.resolve(BODY,new Vec3d(20,0,0),frame(Vec3d.X),
                SceneFixtures.scene(1,1,1,List.of(WALL),List.of()),context);
        assertTrue(result.indeterminate()); assertFalse(result.supported()); assertTrue(result.endpointContact().isEmpty());
    }

    @Test void exactPureAxisImpactAdvancesTheMovingWallForTheRemainingInterval() {
        var obstacle=SceneFixtures.dynamicObstacle(1,1,1,new Vec3d(2.5,.49,0),.5,
                new Vec3d(-.1,0,0),Vec3d.ZERO,1,1,1);
        var scene=SceneFixtures.scene(1,1,1,List.of(),List.of(obstacle));
        var result=GravityCharacterRoute.resolve(BODY,new Vec3d(3,0,0),frame(Vec3d.X),scene,new ObbQueryContext());
        assertFalse(result.indeterminate(),result.indeterminateReason().toString());
        assertEquals(1.41,result.resolvedMovement().x(),1e-6,"must reach final obstacle pose, not stop at earlier TOI");
        assertTrue(result.terminalGrounded()); assertTrue(result.movementSupportContact().isPresent());
        assertEquals(-.1,result.supportContact().orElseThrow().surfaceVelocity().x(),1e-9);
    }
}
