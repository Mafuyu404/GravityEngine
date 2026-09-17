package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.kinematic.KinematicMoveRequest;
import cc.sighs.gravityengine.gravity.kinematic.OwnedMotion;
import cc.sighs.gravityengine.gravity.kinematic.SweepTimeWindow;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterCapsule;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrdinaryStraightPathRegressionTest {
    private static final GravityFrame FRAME = GravityFrame.downOnly(new Vec3d(.6, -.8, 0));
    private static final Vec3d REQUEST = new Vec3d(.01625 / (.6 * .8), 0, 0);
    private static final BlockObstacle FLOOR = new BlockObstacle(new CellPos(0, -1, 0),
            new Aabb3d(-100, -1, -100, 100, 0, 100));

    @Test void tiltedFloorWallContactPreservesCancellationAndSupport() {
        assertCorner(new Vec3d(.5, -.8, Math.sqrt(.11)), 0);
    }

    @Test void tiltedFloorWallWithRealGapDoesNotInventRiseOrSupport() {
        assertCorner(new Vec3d(.6, -.8, 0), .00006);
    }

    private static void assertCorner(Vec3d down, double gap) {
        var frame = GravityFrame.downOnly(down);
        var body = new CharacterCapsule(new Vec3d(0, .78 + gap, 0), frame.up(), .3, .6);
        var wall = new BlockObstacle(new CellPos(0, 0, 1), new Aabb3d(
                -100, -10, .3 + .6 * Math.abs(down.z()) + .0005, 100, 10, 1.3));
        var request = new Vec3d(.016428, 0, .016186);
        for (double step : new double[]{0, .6}) {
            var scene = scene(FLOOR, wall);
            var result = GravityCharacterRoute.resolve(body,
                    new KinematicMoveRequest(request, OwnedMotion.ZERO, KinematicMoveRequest.Channel.SELF),
                    frame, scene, new ObbQueryContext(), new StepUpIntent(step));
            assertFalse(result.indeterminate(), result.indeterminateReason().toString());
            assertEquals(0, result.resolvedMovement().y(), CollisionTolerances.CONTACT_SLOP,
                    "wall response must not separate the capsule from the actual floor");
            assertEquals(request.x(), result.resolvedMovement().x(), CollisionTolerances.CONTACT_SLOP);
            assertTrue(result.resolvedMovement().z() >= 0);
            assertTrue(result.resolvedMovement().z() <= .0005 + CollisionTolerances.CONTACT_SLOP);
            assertEquals(0, result.stepHeight());
            assertEquals(0, result.supportFollowRise());
            assertEquals(Vec3d.ZERO, result.recoveryMovement());
            var probe = GravityGroundProbe.probe(body.move(result.resolvedMovement()), frame, scene, 1,
                    new ObbQueryContext());
            assertEquals(probe.stableGround(), result.terminalGrounded());
            assertEquals(gap == 0, result.terminalGrounded());
            assertClearRequest(GravityCharacterRoute.resolve(body,
                    new KinematicMoveRequest(request, OwnedMotion.ZERO, KinematicMoveRequest.Channel.SELF),
                    frame, scene(FLOOR), new ObbQueryContext(), new StepUpIntent(step)), request);
        }
    }

    @Test void completeLegalRequestDoesNotAcquireTheSplitLegRise() {
        for (double step : new double[]{0, .6}) {
            var body = body(.00006);
            var result = resolve(body, REQUEST, scene(FLOOR), step, new ObbQueryContext());
            assertClearRequest(result, REQUEST);
            assertEquals(0, result.resolvedMovement().y(), 1e-12);
            assertFalse(result.supportingContactDuringMove());
            assertTrue(result.movementSupportContact().isEmpty());
        }
    }

    @Test void touchingAndSmallSignedNormalComponentsRemainWhole() {
        for (double gap : new double[]{0, .00006}) {
            for (double y : new double[]{0, .000001, -.000001}) {
                if (gap == 0 && y < 0) continue; // This path really enters the floor.
                var request = REQUEST.add(new Vec3d(0, y, 0));
                assertClearRequest(resolve(body(gap), request, scene(FLOOR), 0,
                        new ObbQueryContext()), request);
            }
        }
    }

    @Test void signedWorldAxesUseTheSamePathContract() {
        Vec3d[] axes = {Vec3d.X, Vec3d.Y, Vec3d.Z};
        for (int axis = 0; axis < 3; axis++) {
            for (double sign : new double[]{-1, 1}) {
                Vec3d normal = axes[axis].multiply(sign);
                Vec3d tangent = axes[(axis + 1) % 3];
                Vec3d up = normal.multiply(.8).add(tangent.multiply(-.6));
                var frame = GravityFrame.downOnly(up.negate());
                double[] min = {-100, -100, -100};
                double[] max = {100, 100, 100};
                min[axis] = sign > 0 ? -1 : 0;
                max[axis] = sign > 0 ? 0 : 1;
                var floor = new BlockObstacle(new CellPos(0, 0, 0),
                        new Aabb3d(min[0], min[1], min[2], max[0], max[1], max[2]));
                var capsule = new CharacterCapsule(normal.multiply(.3 + .6 * .8 + .00006),
                        frame.up(), .3, .6);
                Vec3d request = tangent.multiply(REQUEST.x());
                var result = GravityCharacterRoute.resolve(capsule,
                        new KinematicMoveRequest(request, OwnedMotion.ZERO, KinematicMoveRequest.Channel.SELF),
                        frame, scene(floor), new ObbQueryContext(), StepUpIntent.disabled());
                assertClearRequest(result, request);
            }
        }
    }

    @Test void repeatedHorizontalRequestsDoNotAccumulateHeightOrInventSupport() {
        for (double gap : new double[]{0, .00006}) {
            CollisionBody body = body(gap);
            for (int tick = 0; tick < 64; tick++) {
                var scene = scene(FLOOR);
                var result = resolve(body, REQUEST, scene, .6, new ObbQueryContext());
                assertClearRequest(result, REQUEST);
                body = body.move(result.resolvedMovement());
                var expectedSupport = GravityGroundProbe.probe(body, FRAME, scene, 1, new ObbQueryContext());
                assertEquals(expectedSupport.stableGround(), result.terminalGrounded());
                assertEquals(expectedSupport.supportContact(), result.supportContact());
                if (gap == 0) assertTrue(result.terminalGrounded());
            }
            assertEquals(.3 + .6 * .8 + gap, body.center().y(), 1e-12);
        }
    }

    @Test void motionOwnershipDoesNotReplaceTheActualRequest() {
        for (var channel : KinematicMoveRequest.Channel.values()) {
            var result = GravityCharacterRoute.resolve(body(.00006),
                    new KinematicMoveRequest(REQUEST, OwnedMotion.ZERO, channel), FRAME,
                    scene(FLOOR), new ObbQueryContext(), StepUpIntent.disabled());
            assertClearRequest(result, REQUEST);
        }
    }

    @Test void externalEndpointDoesNotReceiveAnAdditionalSelfFloorSnap() {
        var request = new Vec3d(.016428, .004, 0);
        assertTrue(request.dot(FRAME.up()) < 0, "separating from world floor while gravity-down");
        for (var channel : KinematicMoveRequest.Channel.values()) {
            if (channel == KinematicMoveRequest.Channel.SELF) continue;
            var result = GravityCharacterRoute.resolve(body(0),
                    new KinematicMoveRequest(request, OwnedMotion.ZERO, channel), FRAME,
                    scene(FLOOR), new ObbQueryContext(), new StepUpIntent(.6));
            assertClearRequest(result, request);
            assertFalse(result.terminalGrounded());
        }
    }

    @Test void aRealWallStillBlocksAndCannotBeAcceptedByEndpointOnlyChecking() {
        var wall = new BlockObstacle(new CellPos(1, 0, 0), new Aabb3d(1, -10, -10, 1.01, 10, 10));
        var context = new ObbQueryContext();
        context.beginCollisionTrace();
        var result = resolve(body(.00006), new Vec3d(3, 0, 0), scene(FLOOR, wall), 0, context);
        assertTrue(result.resolvedMovement().x() < 1);
        assertTrue(context.collisionTrace().sweeps().stream()
                .anyMatch(s -> s.phase().equals("ORDINARY_STRAIGHT") && !s.contacts().isEmpty()));
    }

    @Test void initialOverlapCanEscapeButCannotBeDeepened() {
        var overlapping = body(-.001);
        var outward = REQUEST.add(new Vec3d(0, .01, 0));
        assertClearRequest(resolve(overlapping, outward, scene(FLOOR), 0, new ObbQueryContext()), outward);
        var inward = REQUEST.add(new Vec3d(0, -.02, 0));
        var result = resolve(overlapping, inward, scene(FLOOR), 0, new ObbQueryContext());
        assertTrue(result.resolvedMovement().y() >= -CollisionTolerances.CONTACT_SLOP);
    }

    @Test void subwindowIsUsedExactlyOnceForTheAcceptedStraightPath() {
        var context = new ObbQueryContext();
        context.beginCollisionTrace();
        var window = new SweepTimeWindow(.25, .5);
        var result = GravityCharacterRoute.resolveWindow(body(.00006),
                new KinematicMoveRequest(REQUEST, OwnedMotion.ZERO, KinematicMoveRequest.Channel.SELF),
                FRAME, scene(FLOOR), context, StepUpIntent.disabled(), window);
        assertClearRequest(result, REQUEST);
        var sweeps = context.collisionTrace().sweeps();
        assertEquals(1, sweeps.size());
        assertEquals("ORDINARY_STRAIGHT", sweeps.get(0).phase());
        assertEquals(window.startTicks(), sweeps.get(0).windowStartTicks());
        assertEquals(window.durationTicks(), sweeps.get(0).windowDurationTicks());
    }

    @Test void dynamicSweepsPreventTunnellingThroughAnIntermediateObstacle() {
        var crossing = SceneFixtures.dynamicObstacle(51, 0, 1,
                new Vec3d(2, .78, 0), .1, new Vec3d(-4, 0, 0), Vec3d.ZERO,
                1, 1, 1);
        var scene = SceneFixtures.scene(1, 1, 1, List.of(), List.of(crossing));
        var context = new ObbQueryContext();
        context.beginCollisionTrace();
        var result = resolve(body(.00006), REQUEST, scene, 0, context);
        assertTrue(context.collisionTrace().sweeps().stream()
                .anyMatch(s -> s.phase().equals("ORDINARY_STRAIGHT") && !s.contacts().isEmpty()));
        assertFalse(result.indeterminate(), result.indeterminateReason().toString());
        assertTrue(result.resolvedMovement().x() < 0,
                "the crossing wall must displace the capsule instead of tunnelling through it");
        var endpoint = body(.00006).move(result.resolvedMovement());
        var contacts = CurrentContactQuery.contacts(endpoint, scene, 1, new ObbQueryContext());
        assertFalse(contacts.indeterminate());
        assertTrue(contacts.contacts().stream().allMatch(c -> c.penetration() <= CollisionTolerances.PENETRATION_EPSILON));
        double consumed = 0;
        for (var sweep : context.collisionTrace().sweeps()) {
            if (!sweep.phase().equals("ORDINARY_STRAIGHT")) continue;
            assertTrue(sweep.windowStartTicks() >= consumed - CollisionTolerances.TOI_EPSILON);
            assertEquals(1, sweep.windowStartTicks() + sweep.windowDurationTicks(), CollisionTolerances.TOI_EPSILON);
            consumed = sweep.windowStartTicks() + sweep.windowDurationTicks() * sweep.earliestTimeOfImpact();
        }
    }

    @Test void aFailedStraightQueryDoesNotRetryOrGrantMovement() {
        CollisionScene failed = new CollisionScene() {
            @Override public List<CollisionObstacle> query(CollisionBody body, Vec3d movement, SweepTimeWindow window) {
                if (window.durationTicks() > 0 && movement.lengthSquared() > 0) {
                    throw new CollisionComplexityLimitException("fixture budget");
                }
                return List.of();
            }
        };
        var result = resolve(body(.00006), REQUEST, failed, 0, new ObbQueryContext());
        assertTrue(result.indeterminate());
        assertEquals(MovementIndeterminateReason.COLLISION_QUERY_BUDGET, result.indeterminateReason());
        assertEquals(Vec3d.ZERO, result.resolvedMovement());
        assertTrue(result.supportContact().isEmpty());
    }

    @Test void contactResponseIsCovariantAcrossSignedAxesAndTilts() {
        for (double floorDot : new double[]{.8, .9}) {
            double tilt = Math.sqrt(1 - floorDot * floorDot - .11);
            for (int axis = 0; axis < 3; axis++) for (double sign : new double[]{-1, 1}) {
                var down = permute(new Vec3d(tilt, -floorDot, Math.sqrt(.11)), axis, sign);
                var frame = GravityFrame.downOnly(down);
                var body = new CharacterCapsule(permute(new Vec3d(0, .3 + .6 * floorDot, 0), axis, sign),
                        frame.up(), .3, .6);
                var floor = transform(FLOOR.bounds(), axis, sign, 0);
                var wall = transform(new Aabb3d(-100, -10, .3 + .6 * Math.sqrt(.11) + .0005,
                        100, 10, 1.3), axis, sign, 1);
                var request = permute(new Vec3d(.016428, 0, .016186), axis, sign);
                var result = GravityCharacterRoute.resolve(body,
                        new KinematicMoveRequest(request, OwnedMotion.ZERO, KinematicMoveRequest.Channel.SELF),
                        frame, scene(floor, wall), new ObbQueryContext(), new StepUpIntent(.6));
                assertFalse(result.indeterminate(), result.indeterminateReason().toString());
                assertEquals(0, result.resolvedMovement().dot(permute(Vec3d.Y, axis, sign)), CollisionTolerances.CONTACT_SLOP);
                assertEquals(.016428, result.resolvedMovement().dot(permute(Vec3d.X, axis, sign)), CollisionTolerances.CONTACT_SLOP);
                assertTrue(result.terminalGrounded());
                assertEquals(0, result.stepHeight());
            }
        }
    }

    @Test void twoHundredWallMovesKeepRealFloorSupportWithoutVelocityOrHeightDrift() {
        var frame = GravityFrame.downOnly(new Vec3d(.5, -.8, Math.sqrt(.11)));
        CollisionBody body = new CharacterCapsule(new Vec3d(0, .78, 0), frame.up(), .3, .6);
        var wall = new BlockObstacle(new CellPos(0, 0, 1), new Aabb3d(-100, -10,
                .3 + .6 * Math.sqrt(.11) + .0005, 100, 10, 1.3));
        var scene = scene(FLOOR, wall);
        var request = new Vec3d(.016428, 0, .016186);
        for (int tick = 0; tick < 200; tick++) {
            var result = GravityCharacterRoute.resolve(body,
                    new KinematicMoveRequest(request, OwnedMotion.ZERO, KinematicMoveRequest.Channel.SELF),
                    frame, scene, new ObbQueryContext(), new StepUpIntent(.6));
            assertFalse(result.indeterminate(), "tick=" + tick + " " + result.indeterminateReason());
            assertTrue(result.terminalGrounded(), "tick=" + tick);
            assertEquals(0, result.supportFollowRise());
            assertEquals(0, result.stepHeight());
            assertEquals(Vec3d.ZERO, result.recoveryMovement());
            body = body.move(result.resolvedMovement());
            assertEquals(.78, body.center().y(), CollisionTolerances.CONTACT_SLOP);
            var velocity = ContactConstraintProjector.project(request, result.contactVelocityConstraints()).requireProjectedVector();
            assertEquals(request.x(), velocity.x(), CollisionTolerances.CONTACT_SLOP);
            assertEquals(0, velocity.y(), CollisionTolerances.CONTACT_SLOP);
            assertEquals(0, velocity.z(), CollisionTolerances.CONTACT_SLOP);
        }
        assertEquals(200 * request.x(), body.center().x(), CollisionTolerances.CONTACT_SLOP);
    }

    @Test void finiteFloorAndOldWallPlanesAreReleased() {
        var frame = GravityFrame.downOnly(new Vec3d(.5, -.8, Math.sqrt(.11)));
        var body = new CharacterCapsule(new Vec3d(0, .78, 0), frame.up(), .3, .6);
        var floor = new BlockObstacle(new CellPos(0, -1, 0), new Aabb3d(-2, -1, -2, .4, 0, 2));
        var wall = new BlockObstacle(new CellPos(0, 0, 1), new Aabb3d(-2, -10,
                .3 + .6 * Math.sqrt(.11) + .0005, .4, 10, 1.3));
        var scene = scene(floor, wall);
        assertTrue(GravityGroundProbe.probe(body, frame, scene, 0, new ObbQueryContext()).stableGround());
        var request = new Vec3d(2, 0, .02);
        var result = GravityCharacterRoute.resolve(body,
                new KinematicMoveRequest(request, OwnedMotion.ZERO, KinematicMoveRequest.Channel.SELF),
                frame, scene, new ObbQueryContext(), StepUpIntent.disabled());
        assertFalse(result.indeterminate());
        assertFalse(result.terminalGrounded());
        assertTrue(result.supportContact().isEmpty());
        assertTrue(result.contactVelocityConstraints().isEmpty(), "a departed finite wall cannot constrain velocity");
        var next = GravityCharacterRoute.resolve(body.move(result.resolvedMovement()),
                new KinematicMoveRequest(request, OwnedMotion.ZERO, KinematicMoveRequest.Channel.SELF),
                frame, scene, new ObbQueryContext(), StepUpIntent.disabled());
        assertClearRequest(next, request);
    }

    @Test void threeFacesRetainOnlyLegalMotion() {
        var body = body(0);
        var wallZ = new BlockObstacle(new CellPos(0, 0, 1), new Aabb3d(-100, -10, .3005, 100, 10, 1.3));
        var wallX = new BlockObstacle(new CellPos(1, 0, 0), new Aabb3d(.3 + .6 * .6 + .001, -10, -100, 2, 10, 100));
        var request = new Vec3d(.016428, 0, .016186);
        var result = resolve(body, request, scene(FLOOR, wallZ, wallX), .6, new ObbQueryContext());
        assertFalse(result.indeterminate());
        assertEquals(0, result.resolvedMovement().y(), CollisionTolerances.CONTACT_SLOP);
        assertTrue(result.resolvedMovement().x() > 0 && result.resolvedMovement().x() <= .001 + CollisionTolerances.CONTACT_SLOP);
        assertTrue(result.resolvedMovement().z() > 0 && result.resolvedMovement().z() <= .0005 + CollisionTolerances.CONTACT_SLOP);
        assertTrue(result.terminalGrounded());
        assertEquals(Vec3d.ZERO, ContactConstraintProjector.project(request, result.contactVelocityConstraints()).requireProjectedVector());
    }

    @Test void voxelWallSeamsCannotStopSidewaysGravitySliding() {
        var frame = GravityFrame.downOnly(Vec3d.Z);
        double radius = (double) .6F / 2;
        double halfHeight = (double) 1.8F / 2;
        CollisionBody body = new CharacterCapsule(new Vec3d(8, 300 + halfHeight, 8), frame.up(), radius, halfHeight - radius);
        var obstacles = new java.util.ArrayList<CollisionObstacle>();
        for (int y = 298; y <= 303; y++) for (int z = 5; z <= 17; z++) {
            obstacles.add(new BlockObstacle(new CellPos(10, y, z), new Aabb3d(10, y, z, 11, y + 1, z + 1)));
        }
        for (int tick = 0; tick < 200; tick++) {
            var result = GravityCharacterRoute.resolve(body,
                    new KinematicMoveRequest(new Vec3d(.15, 0, .03), OwnedMotion.ZERO, KinematicMoveRequest.Channel.SELF),
                    frame, scene(obstacles.toArray(CollisionObstacle[]::new)), new ObbQueryContext(), new StepUpIntent(.6));
            assertFalse(result.indeterminate(), "tick=" + tick + " " + result.indeterminateReason());
            assertEquals(.03, result.resolvedMovement().z(), CollisionTolerances.CONTACT_SLOP, "tick=" + tick);
            body = body.move(result.resolvedMovement());
        }
    }

    @Test void aVoxelWallAtTheSlopeThresholdStillReportsItsTangentBlock() {
        var wall = new BlockObstacle(new CellPos(1, 0, 0), new Aabb3d(.3 + .6 * .6 + .001, -10, -100, 2, 10, 100));
        var request = new Vec3d(.016428, 0, .016186);
        var result = resolve(body(0), request, scene(FLOOR, wall), .6, new ObbQueryContext());
        assertFalse(result.indeterminate());
        assertTrue(result.blockedTangent());
        assertEquals(0, result.resolvedMovement().y(), CollisionTolerances.CONTACT_SLOP);
        assertEquals(request.z(), result.resolvedMovement().z(), CollisionTolerances.CONTACT_SLOP);
        assertTrue(result.resolvedMovement().x() <= .001 + CollisionTolerances.CONTACT_SLOP);
        assertEquals(0, result.stepHeight());
        assertEquals(0, result.supportFollowRise());
        assertTrue(result.terminalGrounded());
        assertFalse(result.supportingContactDuringMove(), "an unrelated ground probe cannot turn a wall impact into a landing");
    }

    @Test void onlySelfWalkCanChooseTheExplicitStepAlternative() {
        var frame = GravityFrame.DEFAULT;
        var body = cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox.axisAligned(
                new Aabb3d(-.3, 0, -.3, .3, 1.8, .3));
        var stair = new BlockObstacle(new CellPos(1, 0, 0), new Aabb3d(.5, 0, -2, 3, .3, 2));
        var request = new Vec3d(.8, -.08, 0);
        var walk = new Vec3d(.8, 0, 0);
        var owners = List.of(OwnedMotion.ZERO, new OwnedMotion(Vec3d.ZERO, walk, Vec3d.ZERO),
                new OwnedMotion(Vec3d.ZERO, Vec3d.ZERO, Vec3d.ZERO, walk),
                new OwnedMotion(walk, Vec3d.ZERO, Vec3d.ZERO));
        for (var owner : owners) {
            var result = GravityCharacterRoute.resolve(body,
                    new KinematicMoveRequest(request, owner, KinematicMoveRequest.Channel.SELF),
                    frame, scene(FLOOR, stair), new ObbQueryContext(), new StepUpIntent(.6));
            assertFalse(result.indeterminate(), result.indeterminateReason().toString());
            if (owner.selfWalk().lengthSquared() > 0) {
                assertTrue(result.stepHeight() > .29);
                assertTrue(result.resolvedMovement().x() > .7);
                assertTrue(result.terminalGrounded());
            } else {
                assertEquals(0, result.stepHeight());
                assertTrue(result.resolvedMovement().x() <= .2 + CollisionTolerances.CONTACT_SLOP);
            }
        }
    }

    @Test void realJumpAndLowCeilingKeepHorizontalProgress() {
        var frame = GravityFrame.DEFAULT;
        var body = new CharacterCapsule(new Vec3d(0, .9, 0), frame.up(), .3, .6);
        var request = new Vec3d(.2, .4, 0);
        assertClearRequest(GravityCharacterRoute.resolve(body,
                new KinematicMoveRequest(request, OwnedMotion.ZERO, KinematicMoveRequest.Channel.SELF),
                frame, scene(FLOOR), new ObbQueryContext(), new StepUpIntent(.6)), request);
        var ceiling = new BlockObstacle(new CellPos(0, 2, 0), new Aabb3d(-10, 2, -10, 10, 3, 10));
        var result = GravityCharacterRoute.resolve(body,
                new KinematicMoveRequest(request, OwnedMotion.ZERO, KinematicMoveRequest.Channel.SELF),
                frame, scene(FLOOR, ceiling), new ObbQueryContext(), new StepUpIntent(.6));
        assertFalse(result.indeterminate());
        assertTrue(result.blockedUp());
        assertFalse(result.terminalGrounded());
        assertEquals(.2, result.resolvedMovement().x(), CollisionTolerances.CONTACT_SLOP);
        assertEquals(.2, result.resolvedMovement().y(), CollisionTolerances.CONTACT_SLOP * 2);
        var velocity = ContactConstraintProjector.project(request, result.contactVelocityConstraints()).requireProjectedVector();
        assertEquals(0, velocity.y(), CollisionTolerances.CONTACT_SLOP);
    }

    @Test void budgetInterruptionRetainsTheProvedPrecontactSegment() {
        var wall = new BlockObstacle(new CellPos(0, 0, 1), new Aabb3d(-100, -10, .3005, 100, 10, 1.3));
        CollisionScene bounded = new CollisionScene() {
            @Override public List<CollisionObstacle> query(CollisionBody body, Vec3d move, SweepTimeWindow window) {
                if (window.startTicks() > 0) throw new CollisionComplexityLimitException("after first TOI");
                return List.of(FLOOR, wall);
            }
        };
        var result = resolve(body(0), new Vec3d(.016428, 0, .016186), bounded, 0, new ObbQueryContext());
        assertTrue(result.indeterminate());
        assertEquals(MovementIndeterminateReason.COLLISION_QUERY_BUDGET, result.indeterminateReason());
        assertTrue(result.resolvedMovement().x() > 0);
        assertTrue(result.resolvedMovement().z() > 0);
        assertEquals(0, result.resolvedMovement().y(), CollisionTolerances.CONTACT_SLOP);
        assertFalse(result.terminalGrounded());
        assertTrue(result.contactVelocityConstraints().isEmpty());
    }

    @Test void movingWallConsumesOnlyTheRemainingSubwindowAndMayRedirectTowardGravityUp() {
        var crossing = new DynamicCollisionObstacleSnapshot(51, 0,
                new cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox(Vec3d.ZERO,
                        new Vec3d(.1, 10, 10), cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d.IDENTITY),
                new RigidMotionSnapshot(new cc.sighs.gravityengine.math.geometry.RigidPose(
                        new Vec3d(2, .78, 0), cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d.IDENTITY),
                        new Vec3d(-4, 0, 0), Vec3d.ZERO, 1, 1, 1, 1));
        var scene = SceneFixtures.scene(1, 1, 1, List.of(), List.of(crossing));
        var context = new ObbQueryContext();
        context.beginCollisionTrace();
        var window = new SweepTimeWindow(.25, .5);
        var result = GravityCharacterRoute.resolveWindow(body(.00006),
                new KinematicMoveRequest(REQUEST, OwnedMotion.ZERO, KinematicMoveRequest.Channel.SELF),
                FRAME, scene, context, StepUpIntent.disabled(), window);
        assertFalse(result.indeterminate(), result.indeterminateReason().toString());
        assertTrue(result.resolvedMovement().dot(FRAME.up()) > 0,
                "a real moving normal may redirect motion upward; this is not an authored step");
        assertEquals(0, result.stepHeight());
        assertEquals(0, result.supportFollowRise());
        double consumed = window.startTicks();
        int count = 0;
        for (var sweep : context.collisionTrace().sweeps()) {
            if (!sweep.phase().equals("ORDINARY_STRAIGHT")) continue;
            count++;
            assertEquals(consumed, sweep.windowStartTicks(), CollisionTolerances.TOI_EPSILON);
            assertEquals(window.endTicks(), sweep.windowStartTicks() + sweep.windowDurationTicks(), CollisionTolerances.TOI_EPSILON);
            consumed = sweep.windowStartTicks() + sweep.windowDurationTicks() * sweep.earliestTimeOfImpact();
        }
        assertTrue(count >= 2);
        var contacts = CurrentContactQuery.contacts(body(.00006).move(result.resolvedMovement()), scene,
                window.endTicks(), new ObbQueryContext());
        assertFalse(contacts.indeterminate());
        assertTrue(contacts.contacts().stream().allMatch(c -> c.penetration() <= CollisionTolerances.PENETRATION_EPSILON));
        var velocity = ContactConstraintProjector.project(REQUEST, result.contactVelocityConstraints()).requireProjectedVector();
        assertEquals(-4, velocity.x(), CollisionTolerances.CONTACT_SLOP);
    }

    @Test void continuousNonVoxelSlopeKeepsItsSeparateSupportFollowPolicy() {
        var rotation = new cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d(
                new Vec3d(.8, .6, 0), new Vec3d(-.6, .8, 0), Vec3d.Z);
        var local = new cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox(Vec3d.ZERO,
                new Vec3d(10, .5, 10), cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d.IDENTITY);
        var motion = new RigidMotionSnapshot(new cc.sighs.gravityengine.math.geometry.RigidPose(
                new Vec3d(.3, -.4, 0), rotation), Vec3d.ZERO, Vec3d.ZERO, 1, 1, 1, 1);
        var slope = new DynamicCollisionObstacleSnapshot(61, 0, local, motion);
        var scene = SceneFixtures.scene(1, 1, 1, List.of(), List.of(slope));
        var body = new CharacterCapsule(new Vec3d(0, .6 + .3 / .8, 0), Vec3d.Y, .3, .6);
        var request = new Vec3d(.2, -.04, 0);
        var result = GravityCharacterRoute.resolve(body,
                new KinematicMoveRequest(request, new OwnedMotion(new Vec3d(.2, 0, 0), Vec3d.ZERO, Vec3d.ZERO),
                        KinematicMoveRequest.Channel.SELF), GravityFrame.DEFAULT, scene, new ObbQueryContext(), StepUpIntent.disabled());
        assertFalse(result.indeterminate(), result.indeterminateReason().toString());
        assertEquals(.2, result.resolvedMovement().x(), CollisionTolerances.CONTACT_SLOP);
        assertEquals(.15, result.resolvedMovement().y(), CollisionTolerances.CONTACT_SLOP);
        assertEquals(.15, result.supportFollowRise(), CollisionTolerances.CONTACT_SLOP);
        assertEquals(0, result.stepHeight());
        assertTrue(result.terminalGrounded());
    }

    @Test void landingTractionConsumesOnlyTheUnspentNewPassiveDelta() {
        var body = new CharacterCapsule(new Vec3d(0, .95, 0), Vec3d.Y, .3, .6);
        var request = new Vec3d(.5, -.2, 0);
        var owner = new OwnedMotion(new Vec3d(.04, 0, 0), new Vec3d(.07, 0, 0), new Vec3d(.1, 0, 0));
        for (var channel : KinematicMoveRequest.Channel.values()) {
            var result = GravityCharacterRoute.resolve(body, new KinematicMoveRequest(request, owner, channel),
                    GravityFrame.DEFAULT, scene(FLOOR), new ObbQueryContext(), StepUpIntent.disabled());
            assertFalse(result.indeterminate());
            assertEquals(channel == KinematicMoveRequest.Channel.SELF ? .425 : .5,
                    result.resolvedMovement().x(), CollisionTolerances.CONTACT_SLOP);
            assertTrue(result.blockedDown());
            assertTrue(result.supportingContactDuringMove());
            assertTrue(result.terminalGrounded());
        }
    }

    @Test void landingImpactSurvivesWalkingOffTheFaceButTerminalSupportDoesNot() {
        var body = cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox.axisAligned(
                new Aabb3d(-.3, .05, -.3, .3, 1.85, .3));
        var floor = new BlockObstacle(new CellPos(0, -1, 0), new Aabb3d(-2, -1, -2, .3, 0, 2));
        var result = GravityCharacterRoute.resolve(body,
                new KinematicMoveRequest(new Vec3d(2, -.8, 0), OwnedMotion.ZERO, KinematicMoveRequest.Channel.SELF),
                GravityFrame.DEFAULT, scene(floor), new ObbQueryContext(), StepUpIntent.disabled());
        assertFalse(result.indeterminate());
        assertTrue(result.blockedDown());
        assertTrue(result.supportingContactDuringMove());
        assertTrue(result.movementSupportContact().isPresent());
        assertFalse(result.terminalGrounded());
        assertTrue(result.supportContact().isEmpty());
        assertTrue(result.contactVelocityConstraints().isEmpty());
        assertEquals(2, result.resolvedMovement().x(), CollisionTolerances.CONTACT_SLOP);
    }

    @Test void supportedSelfMovementStillFollowsARealStepDown() {
        var body = cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox.axisAligned(
                new Aabb3d(-.7, 0, -.3, -.1, 1.8, .3));
        var high = new BlockObstacle(new CellPos(-1, -1, 0), new Aabb3d(-2, -1, -2, 0, 0, 2));
        var low = new BlockObstacle(new CellPos(0, -1, 0), new Aabb3d(0, -1, -2, 2, -.2, 2));
        var request = new Vec3d(.8, 0, 0);
        var result = GravityCharacterRoute.resolve(body,
                new KinematicMoveRequest(request, new OwnedMotion(request, Vec3d.ZERO, Vec3d.ZERO), KinematicMoveRequest.Channel.SELF),
                GravityFrame.DEFAULT, scene(high, low), new ObbQueryContext(), new StepUpIntent(.6));
        assertFalse(result.indeterminate());
        assertEquals(.8, result.resolvedMovement().x(), CollisionTolerances.CONTACT_SLOP);
        assertEquals(-.2, result.resolvedMovement().y(), CollisionTolerances.CONTACT_SLOP * 2);
        assertTrue(result.terminalGrounded());
        assertEquals(0, result.stepHeight());
        assertEquals(0, result.supportFollowRise());
    }

    @Test void wallProjectionMustReactivateTheInitiallyTangentFloor() {
        var normal = new Vec3d(-.8, -.6, 0);
        var rotation = new cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d(
                new Vec3d(-.6, .8, 0), normal, Vec3d.Z);
        var body = new CharacterCapsule(new Vec3d(0, .9, 0), Vec3d.Y, .3, .6);
        var wall = new DynamicCollisionObstacleSnapshot(71, 0,
                new cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox(Vec3d.ZERO,
                        new Vec3d(10, .5, 10), cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d.IDENTITY),
                new RigidMotionSnapshot(new cc.sighs.gravityengine.math.geometry.RigidPose(
                        body.center().subtract(normal.multiply(.3 + .6 * .6 + .0005 + .5)), rotation),
                        Vec3d.ZERO, Vec3d.ZERO, 1, 1, 1, 1));
        var scene = SceneFixtures.scene(1, 1, 1, List.of(FLOOR), List.of(wall));
        var request = new Vec3d(.016428, 0, .016186);
        var result = GravityCharacterRoute.resolve(body,
                new KinematicMoveRequest(request, OwnedMotion.ZERO, KinematicMoveRequest.Channel.SELF),
                GravityFrame.DEFAULT, scene, new ObbQueryContext(), StepUpIntent.disabled());
        assertFalse(result.indeterminate(), result.indeterminateReason().toString());
        assertEquals(0, result.resolvedMovement().y(), CollisionTolerances.CONTACT_SLOP);
        assertEquals(request.z(), result.resolvedMovement().z(), CollisionTolerances.CONTACT_SLOP);
        assertTrue(result.resolvedMovement().x() > 0 && result.resolvedMovement().x() < .001);
        assertTrue(result.terminalGrounded());
        var velocity = ContactConstraintProjector.project(request, result.contactVelocityConstraints()).requireProjectedVector();
        assertEquals(0, velocity.x(), CollisionTolerances.CONTACT_SLOP);
        assertEquals(0, velocity.y(), CollisionTolerances.CONTACT_SLOP);
        assertEquals(request.z(), velocity.z(), CollisionTolerances.CONTACT_SLOP);
    }

    private static Vec3d permute(Vec3d v, int axis, double sign) {
        return switch (axis) {
            case 0 -> new Vec3d(v.x(), sign * v.y(), v.z());
            case 1 -> new Vec3d(v.z(), v.x(), sign * v.y());
            default -> new Vec3d(sign * v.y(), v.z(), v.x());
        };
    }

    private static BlockObstacle transform(Aabb3d box, int axis, double sign, int id) {
        var a = permute(new Vec3d(box.minX(), box.minY(), box.minZ()), axis, sign);
        var b = permute(new Vec3d(box.maxX(), box.maxY(), box.maxZ()), axis, sign);
        return new BlockObstacle(new CellPos(id, 0, 0), new Aabb3d(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()),
                Math.min(a.z(), b.z()), Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z())));
    }

    private static CharacterCapsule body(double gap) {
        return new CharacterCapsule(new Vec3d(0, .3 + .6 * .8 + gap, 0), FRAME.up(), .3, .6);
    }

    private static CollisionScene scene(CollisionObstacle... obstacles) {
        return new CollisionScene() {
            @Override public List<CollisionObstacle> query(CollisionBody body, Vec3d movement, SweepTimeWindow window) {
                return List.of(obstacles);
            }
        };
    }

    private static GravityMoveResult resolve(CollisionBody body, Vec3d request, CollisionScene scene,
                                            double step, ObbQueryContext context) {
        return GravityCharacterRoute.resolve(body,
                new KinematicMoveRequest(request, OwnedMotion.ZERO, KinematicMoveRequest.Channel.SELF),
                FRAME, scene, context, new StepUpIntent(step));
    }

    private static void assertClearRequest(GravityMoveResult result, Vec3d request) {
        assertFalse(result.indeterminate(), result.indeterminateReason().toString());
        assertEquals(request.x(), result.resolvedMovement().x(), 1e-10);
        assertEquals(request.y(), result.resolvedMovement().y(), 1e-10);
        assertEquals(request.z(), result.resolvedMovement().z(), 1e-10);
        assertEquals(Vec3d.ZERO, result.recoveryMovement());
        assertEquals(0, result.supportFollowRise());
        assertEquals(0, result.stepHeight());
        assertFalse(result.blockedDown());
        assertFalse(result.blockedUp());
        assertFalse(result.blockedTangent());
    }
}
