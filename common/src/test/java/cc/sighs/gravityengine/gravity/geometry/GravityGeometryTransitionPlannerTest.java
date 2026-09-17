package cc.sighs.gravityengine.gravity.geometry;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.collision.BlockObstacle;
import cc.sighs.gravityengine.gravity.collision.CellPos;
import cc.sighs.gravityengine.gravity.collision.CollisionObstacle;
import cc.sighs.gravityengine.gravity.collision.CollisionScene;
import cc.sighs.gravityengine.gravity.collision.CollisionSceneCoverageException;
import cc.sighs.gravityengine.gravity.collision.CollisionWorkBudget;
import cc.sighs.gravityengine.gravity.collision.CollisionWorkDiagnostics;
import cc.sighs.gravityengine.gravity.collision.CollisionWorkTracker;
import cc.sighs.gravityengine.gravity.collision.GravitySupportContact;
import cc.sighs.gravityengine.gravity.collision.GravityGroundProbe;
import cc.sighs.gravityengine.gravity.collision.StepStartSupportQuery;
import cc.sighs.gravityengine.gravity.collision.ObbQueryContext;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;
import cc.sighs.gravityengine.gravity.kinematic.SweepTimeWindow;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterDimensions;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.runtime.RestingContactSnapshot;
import cc.sighs.gravityengine.math.Quatd;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import cc.sighs.gravityengine.math.geometry.BodyOrientation3d;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loader-neutral coverage of the geometry-transition planner. No Minecraft
 * entity, level, dimension or vector value participates.
 */
class GravityGeometryTransitionPlannerTest {
    private static final long TICK = 100L;
    private static final CharacterDimensions DIMS =
            new CharacterDimensions(0.6D, 1.8D);
    private static final Vec3d ANCHOR =
            new Vec3d(0.5D, 100.0D, 0.5D);
    private static final Vec3d CENTER =
            new Vec3d(0.5D, 100.9D, 0.5D);
    private static final Vec3d CONTACT =
            new Vec3d(0.5D, 100.0D, 0.5D);
    private static final Vec3d UP = new Vec3d(0.0D, 1.0D, 0.0D);
    private static final CellPos SUPPORT_CELL = new CellPos(0, 99, 0);
    private static final Aabb3d FLOOR =
            new Aabb3d(0.0D, 99.0D, 0.0D, 1.0D, 100.0D, 1.0D);
    private static final GravityFrame INSTALLED =
            GravityFrame.fromDown(new Vec3d(0.0D, -1.0D, 0.0D), 0.08D);

    // -----------------------------------------------------------------
    // Deadband and frame delta
    // -----------------------------------------------------------------

    @Test
    void deadbandRetainsInstalledGeometryAndRefreshesEvidence() {
        GravityFrame proposed = tilted(1.0E-6D);

        assertFalse(
                GravityGeometryTransitionPlanner
                        .shouldUpdateCollisionGeometryFrame(
                                INSTALLED,
                                proposed
                        )
        );

        OperationPoseTransition transition =
                GravityGeometryTransitionPlanner.plan(
                        request(
                                proposed,
                                scene(List.of(), List.of()),
                                PositionAuthorityPolicy.OPERATION_MAY_REANCHOR,
                                null,
                                new ObbQueryContext()
                        )
                );

        assertEquals(
                GeometryTransitionKind.UNCHANGED,
                transition.kind()
        );
        assertFalse(transition.installsPose());
        assertNull(transition.candidatePose());
        assertEquals(
                INSTALLED.up(),
                transition.selectedFrame().up()
        );
    }

    @Test
    void justOutsideTheDeadbandEvaluatesTheTransition() {
        assertTrue(
                GravityGeometryTransitionPlanner
                        .shouldUpdateCollisionGeometryFrame(
                                INSTALLED,
                                tilted(1.0E-3D)
                        )
        );
    }

    @Test
    void exactAngularDistanceIsAStableMeasurement() {
        assertEquals(
                0.0D,
                GravityGeometryTransitionPlanner
                        .exactFrameAngularDistanceRadians(
                                INSTALLED,
                                INSTALLED
                        ),
                0.0D
        );
        assertEquals(
                Math.PI * 0.5D,
                GravityGeometryTransitionPlanner
                        .exactFrameAngularDistanceRadians(
                                INSTALLED,
                                GravityFrame.fromDown(
                                        Quatd.rotationZ(Math.PI * 0.5D)
                                                .transform(
                                                        INSTALLED.down()
                                                ),
                                        0.08D
                                )
                        ),
                1.0E-9D
        );

        Quatd quaternion = BodyOrientation3d.quaternion(
                INSTALLED.orientation()
        );
        GravityFrame signEquivalent = new GravityFrame(
                INSTALLED.samplePoint(),
                BodyOrientation3d.frame(
                        new Quatd(
                                -quaternion.x(),
                                -quaternion.y(),
                                -quaternion.z(),
                                -quaternion.w()
                        )
                ),
                INSTALLED.strength()
        );
        assertEquals(
                0.0D,
                GravityGeometryTransitionPlanner
                        .exactFrameAngularDistanceRadians(
                                INSTALLED,
                                signEquivalent
                        ),
                1.0E-9D
        );
    }

    // -----------------------------------------------------------------
    // Direct fit
    // -----------------------------------------------------------------

    @Test
    void legalDirectPoseFitIsAcceptedAtTheAnchor() {
        OperationPoseTransition transition =
                GravityGeometryTransitionPlanner.plan(
                        request(
                                tilted(0.02D),
                                scene(List.of(), List.of()),
                                PositionAuthorityPolicy.OPERATION_MAY_REANCHOR,
                                null,
                                new ObbQueryContext()
                        )
                );

        assertEquals(
                GeometryTransitionKind.DIRECT_AT_ANCHOR,
                transition.kind()
        );
        assertTrue(transition.installsPose());
        assertEquals(
                Vec3d.ZERO,
                transition.positionCorrection()
        );
        assertEquals(
                ANCHOR,
                transition.candidatePose().positionAnchor()
        );
        assertEquals(
                PoseFitStatus.LEGAL,
                transition.diagnostics().directFit()
        );
    }

    @Test
    void sceneCoverageLimitsTheDirectFitToIndeterminate() {
        OperationPoseTransition transition =
                GravityGeometryTransitionPlanner.plan(
                        request(
                                tilted(0.02D),
                                new FrozenScene(
                                        List.of(),
                                        List.of(),
                                        true,
                                        false
                                ),
                                PositionAuthorityPolicy.OPERATION_MAY_REANCHOR,
                                null,
                                new ObbQueryContext()
                        )
                );

        assertEquals(
                GeometryTransitionKind.DEFERRED,
                transition.kind()
        );
        assertEquals(
                GravityGeometryTransitionPlanner.SCENE_COVERAGE_REASON,
                transition.rejectionReason()
        );
        assertEquals(
                PoseFitStatus.INDETERMINATE,
                transition.diagnostics().directFit()
        );
    }

    @Test
    void exhaustedWorkBudgetIsIndeterminateNotCollisionBlocked() {
        ObbQueryContext context = new ObbQueryContext();
        CollisionWorkTracker tracker = new CollisionWorkTracker(
                new CollisionWorkBudget(1, 1, 1, 1, 1)
        );
        context.setWorkTracker(tracker);
        assertTrue(tracker.recordNarrowPhaseTest());
        assertFalse(tracker.recordNarrowPhaseTest());

        OperationPoseTransition transition =
                GravityGeometryTransitionPlanner.plan(
                        request(
                                tilted(0.02D),
                                scene(List.of(), List.of()),
                                PositionAuthorityPolicy.OPERATION_MAY_REANCHOR,
                                null,
                                context
                        )
                );

        assertEquals(
                GeometryTransitionKind.DEFERRED,
                transition.kind()
        );
        assertEquals(
                GravityGeometryTransitionPlanner.WORK_BUDGET_REASON,
                transition.rejectionReason()
        );
        assertEquals(
                PoseFitStatus.INDETERMINATE,
                transition.diagnostics().directFit()
        );
    }

    // -----------------------------------------------------------------
    // Support compatibility and correction bound
    // -----------------------------------------------------------------

    @Test
    void supportCompatibilityUsesTheWalkableSlopeBoundary() {
        assertTrue(
                GravityGeometryTransitionPlanner
                        .supportCompatibleWithFrame(
                                resting(TICK, CONTACT, UP),
                                tilted(0.02D)
                        )
        );
        assertFalse(
                GravityGeometryTransitionPlanner
                        .supportCompatibleWithFrame(
                                resting(TICK, CONTACT, UP),
                                GravityFrame.fromDown(
                                        new Vec3d(0.0D, -0.3D, -0.9539392014169456D),
                                        0.08D
                                )
                        )
        );
    }

    @Test
    void supportCorrectionBeyondTheBoundIsRejected() {
        SupportPreserveOutcome outcome =
                GravityGeometryTransitionPlanner
                        .supportPreservingPoseCandidate(
                                TICK,
                                new CharacterDimensions(0.6D, 3.0D),
                                installedBody(),
                                CENTER,
                                tilted(0.02D),
                                resting(TICK, CONTACT, UP)
                        );

        assertFalse(outcome.preserved());
        assertEquals(
                "CORRECTION_TOO_LARGE",
                outcome.fallbackReason()
        );
    }

    @Test
    void staleRestingSnapshotIsNeverAnchored() {
        SupportPreserveOutcome outcome =
                GravityGeometryTransitionPlanner
                        .supportPreservingPoseCandidate(
                                TICK,
                                DIMS,
                                installedBody(),
                                CENTER,
                                tilted(0.02D),
                                resting(TICK - 5L, CONTACT, UP)
                        );

        assertFalse(outcome.preserved());
        assertEquals(
                "STALE_RESTING_SNAPSHOT",
                outcome.fallbackReason()
        );
    }

    @Test
    void unsupportedAndNonPlanarSnapshotsAreRejected() {
        RestingContactSnapshot moving = new RestingContactSnapshot(
                UP,
                new Vec3d(0.25D, 0.0D, 0.0D),
                CONTACT,
                GravitySupportContact.SupportGeometryKind
                        .TRUSTED_CURRENT_BLOCK_FACE,
                Optional.of(SUPPORT_CELL),
                TICK
        );
        assertEquals(
                "UNSUPPORTED_SNAPSHOT",
                GravityGeometryTransitionPlanner
                        .supportPreservingPoseCandidate(
                                TICK,
                                DIMS,
                                installedBody(),
                                CENTER,
                                tilted(0.02D),
                                moving
                        )
                        .fallbackReason()
        );

        RestingContactSnapshot manifold = new RestingContactSnapshot(
                UP,
                Vec3d.ZERO,
                CONTACT,
                GravitySupportContact.SupportGeometryKind.SWEEP_CONTACT,
                Optional.of(SUPPORT_CELL),
                TICK
        );
        assertEquals(
                "MANIFOLD_SUPPORT",
                GravityGeometryTransitionPlanner
                        .supportPreservingPoseCandidate(
                                TICK,
                                DIMS,
                                installedBody(),
                                CENTER,
                                tilted(0.02D),
                                manifold
                        )
                        .fallbackReason()
        );
    }

    @Test
    void restingGapAndUnwalkableSupportAreRejected() {
        assertEquals(
                "RESTING_GAP",
                GravityGeometryTransitionPlanner
                        .supportPreservingPoseCandidate(
                                TICK,
                                DIMS,
                                installedBody(),
                                CENTER,
                                tilted(0.02D),
                                resting(
                                        TICK,
                                        new Vec3d(0.5D, 99.8D, 0.5D),
                                        UP
                                )
                        )
                        .fallbackReason()
        );
        assertEquals(
                "SUPPORT_NOT_WALKABLE",
                GravityGeometryTransitionPlanner
                        .supportPreservingPoseCandidate(
                                TICK,
                                DIMS,
                                installedBody(),
                                CENTER,
                                GravityFrame.fromDown(
                                        new Vec3d(0.0D, -0.3D, -0.9539392014169456D),
                                        0.08D
                                ),
                                resting(TICK, CONTACT, UP)
                        )
                        .fallbackReason()
        );
    }

    @Test
    void largeFrameDeltaNeverPreservesSupport() {
        OperationPoseTransition transition =
                GravityGeometryTransitionPlanner.plan(
                        request(
                                tilted(Math.toRadians(5.0D)),
                                blockedScene(),
                                PositionAuthorityPolicy.OPERATION_MAY_REANCHOR,
                                resting(TICK, CONTACT, UP),
                                new ObbQueryContext()
                        )
                );

        assertEquals(
                GeometryTransitionKind.DEFERRED,
                transition.kind()
        );
        assertEquals(
                "FRAME_DELTA_TOO_LARGE",
                transition.diagnostics().supportRejectReason()
        );
        assertFalse(
                transition.diagnostics().supportCandidateConstructed()
        );
    }

    // -----------------------------------------------------------------
    // PRESERVE_SUPPORT
    // -----------------------------------------------------------------

    @Test
    void collisionFreeRotationPreservesFreshSupportAfterReplicaInvalidation() {
        // Protocol-28 tick 202: fixed P, decreasing capsule vertical extent.
        CharacterDimensions dimensions = new CharacterDimensions(
                0.6000000238418579D, 1.7999999523162842D);
        Vec3d anchor = new Vec3d(-53.70637027938166D,
                212.97410402809575D, -40.844466434122D);
        GravityFrame installed = GravityFrame.fromDown(new Vec3d(
                -0.23136463849415112D, -0.9568398775870415D,
                0.17586316474374683D), 0.0861978759908749D);
        GravityFrame proposed = GravityFrame.fromDown(new Vec3d(
                -0.23207802083819135D, -0.956803070481487D,
                0.17512189058203667D), installed.strength());
        CollisionBody body = GravityGeometryTransitionPlanner
                .posePreservingPositionAnchor(dimensions, anchor, installed).body();
        BlockObstacle floor = new BlockObstacle(new CellPos(-54, 212, -41),
                new Aabb3d(-55, 212, -42, -52, 213, -39));
        FrozenScene scene = scene(List.of(floor), List.of(floor));
        var support = StepStartSupportQuery.query(body, installed, scene,
                new ObbQueryContext(), Vec3d.ZERO, TICK, null);
        assertTrue(support.support().isPresent());
        CollisionBody direct = GravityGeometryTransitionPlanner
                .posePreservingPositionAnchor(dimensions, anchor, proposed).body();
        assertTrue(direct.enclosingAabb().minY() - 213D > GravityGroundProbe.PROBE_DISTANCE);
        assertTrue(GravityGeometryTransitionPlanner.evaluatePoseFit(
                scene, direct, new ObbQueryContext()).legal());

        var transition = GravityGeometryTransitionPlanner.plan(new GeometryTransitionRequest(
                installed, body, anchor, proposed, dimensions, scene, new ObbQueryContext(),
                PositionAuthorityPolicy.OPERATION_MAY_REANCHOR, support.support().orElseThrow(), TICK));

        assertEquals(GeometryTransitionKind.PRESERVE_SUPPORT, transition.kind());
        assertEquals(PoseFitStatus.LEGAL, transition.diagnostics().directFit());
        assertEquals(body.enclosingAabb().minY(),
                transition.candidatePose().body().enclosingAabb().minY(), 1.0E-12D);
        assertTrue(transition.positionCorrection().y() < 0D);
        // A replica discards the old witness. The installed result must acquire
        // real ground with the ordinary probe, without a continuity hint.
        assertTrue(StepStartSupportQuery.query(transition.candidatePose().body(), proposed,
                scene, new ObbQueryContext(), Vec3d.ZERO, TICK, null).support().isPresent());
    }

    @Test
    void repeatedCollisionFreeRotationsDoNotAccumulateSupportGap() {
        BlockObstacle floor = new BlockObstacle(SUPPORT_CELL, FLOOR);
        FrozenScene scene = scene(List.of(floor), List.of(floor));
        GravityFrame installed = INSTALLED;
        Vec3d anchor = ANCHOR;
        CollisionBody body = installedBody();
        for (int step = 1; step <= 100; step++) {
            var support = StepStartSupportQuery.query(body, installed, scene,
                    new ObbQueryContext(), Vec3d.ZERO, TICK, null).support().orElseThrow();
            GravityFrame proposed = tilted(step * 0.001D);
            var transition = GravityGeometryTransitionPlanner.plan(new GeometryTransitionRequest(
                    installed, body, anchor, proposed, DIMS, scene, new ObbQueryContext(),
                    PositionAuthorityPolicy.OPERATION_MAY_REANCHOR, support, TICK));
            assertEquals(GeometryTransitionKind.PRESERVE_SUPPORT, transition.kind());
            body = transition.candidatePose().body();
            anchor = transition.candidatePose().positionAnchor();
            installed = proposed;
            assertEquals(100D, body.enclosingAabb().minY(), 1.0E-12D);
        }
    }

    @Test
    void collisionFreeSupportedRotationDefersWithoutPositionAuthority() {
        BlockObstacle floor = new BlockObstacle(SUPPORT_CELL, FLOOR);
        var transition = GravityGeometryTransitionPlanner.plan(request(
                tilted(0.02D), scene(List.of(floor), List.of(floor)),
                PositionAuthorityPolicy.EXTERNAL_POSITION_ANCHOR,
                resting(TICK, CONTACT, UP), new ObbQueryContext()));
        assertEquals(PoseFitStatus.LEGAL, transition.diagnostics().directFit());
        assertEquals(GeometryTransitionKind.DEFERRED, transition.kind());
        assertEquals(GravityGeometryTransitionPlanner.POSITION_AUTHORITY_REANCHOR_REASON,
                transition.rejectionReason());
        assertSame(INSTALLED, transition.selectedFrame());
    }

    @Test
    void collisionFreeSupportedRotationDefersWhenFaceCannotBeRevalidated() {
        for (boolean coverageFailure : List.of(false, true)) {
            var transition = GravityGeometryTransitionPlanner.plan(request(
                    tilted(0.02D), new FrozenScene(List.of(), List.of(), false, coverageFailure),
                    PositionAuthorityPolicy.OPERATION_MAY_REANCHOR,
                    resting(TICK, CONTACT, UP), new ObbQueryContext()));
            assertEquals(PoseFitStatus.LEGAL, transition.diagnostics().directFit());
            assertEquals(GeometryTransitionKind.DEFERRED, transition.kind());
            assertEquals(coverageFailure ? GravityGeometryTransitionPlanner.SCENE_COVERAGE_REASON
                    : "SUPPORT_PLANE_MISSING", transition.rejectionReason());
            assertSame(INSTALLED, transition.selectedFrame());
        }
    }

    @Test
    void supportedRotationWithoutCorrectionKeepsExactExternalAnchor() {
        GravityFrame installed = tilted(.02D);
        GravityFrame proposed = tilted(-.02D);
        Vec3d anchor = ANCHOR.add(0D, .6D * (Math.cos(.02D) - 1D), 0D);
        CollisionBody body = GravityGeometryTransitionPlanner
                .posePreservingPositionAnchor(DIMS, anchor, installed).body();
        BlockObstacle floor = new BlockObstacle(SUPPORT_CELL, FLOOR);
        FrozenScene scene = scene(List.of(floor), List.of(floor));
        var support = StepStartSupportQuery.query(body, installed, scene,
                new ObbQueryContext(), Vec3d.ZERO, TICK, null).support().orElseThrow();
        var transition = GravityGeometryTransitionPlanner.plan(new GeometryTransitionRequest(
                installed, body, anchor, proposed, DIMS, scene, new ObbQueryContext(),
                PositionAuthorityPolicy.EXTERNAL_POSITION_ANCHOR, support, TICK));
        assertEquals(GeometryTransitionKind.PRESERVE_SUPPORT, transition.kind());
        assertEquals(anchor, transition.candidatePose().positionAnchor());
        assertEquals(Vec3d.ZERO, transition.positionCorrection());
        assertFalse(transition.diagnostics().positionAuthorityAllowed());
    }

    @Test
    void staleSupportAndDiscontinuousRotationsDoNotAuthorizeReanchoring() {
        BlockObstacle floor = new BlockObstacle(SUPPORT_CELL, FLOOR);
        FrozenScene scene = scene(List.of(floor), List.of(floor));
        for (var request : List.of(
                request(tilted(.02D), scene, PositionAuthorityPolicy.OPERATION_MAY_REANCHOR,
                        resting(TICK - 2, CONTACT, UP), new ObbQueryContext()),
                request(tilted(.2D), scene, PositionAuthorityPolicy.OPERATION_MAY_REANCHOR,
                        resting(TICK, CONTACT, UP), new ObbQueryContext()))) {
            var transition = GravityGeometryTransitionPlanner.plan(request);
            assertEquals(GeometryTransitionKind.DIRECT_AT_ANCHOR, transition.kind());
            assertEquals(ANCHOR, transition.candidatePose().positionAnchor());
        }
    }

    @Test
    void preserveSupportWinsWhenTheDirectPoseIsBlocked() {
        GravityFrame proposed = tilted(Math.toRadians(2.0D));
        RestingContactSnapshot resting = resting(TICK, CONTACT, UP);
        SupportPreserveOutcome candidate =
                GravityGeometryTransitionPlanner
                        .supportPreservingPoseCandidate(
                                TICK,
                                DIMS,
                                installedBody(),
                                CENTER,
                                proposed,
                                resting
                        );
        assertTrue(candidate.preserved());

        FrozenScene scene = preservedScene(proposed, candidate, 0.5D);
        OperationPoseTransition transition =
                GravityGeometryTransitionPlanner.plan(
                        request(
                                proposed,
                                scene,
                                PositionAuthorityPolicy.OPERATION_MAY_REANCHOR,
                                resting,
                                new ObbQueryContext()
                        )
                );

        assertEquals(
                GeometryTransitionKind.PRESERVE_SUPPORT,
                transition.kind()
        );
        assertTrue(transition.installsPose());
        assertEquals(
                PoseFitStatus.ILLEGAL,
                transition.diagnostics().directFit()
        );
        assertNotNull(transition.candidatePose());
    }

    @Test
    void preserveSupportRejectsAnIllegalCandidatePose() {
        GravityFrame proposed = tilted(Math.toRadians(2.0D));
        RestingContactSnapshot resting = resting(TICK, CONTACT, UP);
        SupportPreserveOutcome candidate =
                GravityGeometryTransitionPlanner
                        .supportPreservingPoseCandidate(
                                TICK,
                                DIMS,
                                installedBody(),
                                CENTER,
                                proposed,
                                resting
                        );
        FrozenScene scene = preservedScene(proposed, candidate, -0.5D);

        OperationPoseTransition transition =
                GravityGeometryTransitionPlanner.plan(
                        request(
                                proposed,
                                scene,
                                PositionAuthorityPolicy.OPERATION_MAY_REANCHOR,
                                resting,
                                new ObbQueryContext()
                        )
                );

        assertEquals(
                GeometryTransitionKind.DEFERRED,
                transition.kind()
        );
        assertEquals(
                "POSE_ILLEGAL",
                transition.diagnostics().supportRejectReason()
        );
    }

    @Test
    void indeterminateSupportCoverageIsNotReportedAsCollision() {
        GravityFrame proposed = tilted(Math.toRadians(2.0D));
        RestingContactSnapshot resting = resting(TICK, CONTACT, UP);
        SupportPreserveOutcome candidate =
                GravityGeometryTransitionPlanner
                        .supportPreservingPoseCandidate(
                                TICK,
                                DIMS,
                                installedBody(),
                                CENTER,
                                proposed,
                                resting
                        );
        FrozenScene scene = preservedScene(proposed, candidate, 0.5D);
        FrozenScene withoutSupportCoverage = new FrozenScene(
                scene.poseFitObstacles,
                scene.supportObstacles,
                false,
                true
        );

        OperationPoseTransition transition =
                GravityGeometryTransitionPlanner.plan(
                        request(
                                proposed,
                                withoutSupportCoverage,
                                PositionAuthorityPolicy.OPERATION_MAY_REANCHOR,
                                resting,
                                new ObbQueryContext()
                        )
                );

        assertEquals(
                GeometryTransitionKind.DEFERRED,
                transition.kind()
        );
        assertEquals(
                GravityGeometryTransitionPlanner.SCENE_COVERAGE_REASON,
                transition.diagnostics().supportRejectReason()
        );
    }

    @Test
    void exhaustedSupportBudgetIsReportedAsWorkBudget() {
        GravityFrame proposed = tilted(Math.toRadians(2.0D));
        RestingContactSnapshot resting = resting(TICK, CONTACT, UP);
        SupportPreserveOutcome candidate =
                GravityGeometryTransitionPlanner
                        .supportPreservingPoseCandidate(
                                TICK,
                                DIMS,
                                installedBody(),
                                CENTER,
                                proposed,
                                resting
                        );
        ObbQueryContext context = new ObbQueryContext();
        CollisionWorkTracker supportTracker = new CollisionWorkTracker(
                new CollisionWorkBudget(1, 1, 1, 1, 1)
        );
        context.setSupportWorkTracker(supportTracker);
        assertTrue(supportTracker.recordNarrowPhaseTest());
        assertFalse(supportTracker.recordNarrowPhaseTest());

        OperationPoseTransition transition =
                GravityGeometryTransitionPlanner.plan(
                        request(
                                proposed,
                                preservedScene(proposed, candidate, 0.5D),
                                PositionAuthorityPolicy.OPERATION_MAY_REANCHOR,
                                resting,
                                context
                        )
                );

        assertEquals(
                GeometryTransitionKind.DEFERRED,
                transition.kind()
        );
        assertEquals(
                GravityGeometryTransitionPlanner.WORK_BUDGET_REASON,
                transition.diagnostics().supportRejectReason()
        );
    }

    // -----------------------------------------------------------------
    // Position authority
    // -----------------------------------------------------------------

    @Test
    void externalPositionAnchorForbidsTheReanchorDecision() {
        GravityFrame proposed = tilted(Math.toRadians(2.0D));
        RestingContactSnapshot resting = resting(TICK, CONTACT, UP);
        SupportPreserveOutcome candidate =
                GravityGeometryTransitionPlanner
                        .supportPreservingPoseCandidate(
                                TICK,
                                DIMS,
                                installedBody(),
                                CENTER,
                                proposed,
                                resting
                        );
        FrozenScene scene = preservedScene(proposed, candidate, 0.5D);

        OperationPoseTransition external =
                GravityGeometryTransitionPlanner.plan(
                        request(
                                proposed,
                                scene,
                                PositionAuthorityPolicy.EXTERNAL_POSITION_ANCHOR,
                                resting,
                                new ObbQueryContext()
                        )
                );

        assertEquals(
                GeometryTransitionKind.DEFERRED,
                external.kind()
        );
        assertEquals(
                GravityGeometryTransitionPlanner
                        .POSITION_AUTHORITY_REANCHOR_REASON,
                external.rejectionReason()
        );
        assertFalse(external.installsPose());
        assertFalse(
                external.diagnostics().positionAuthorityAllowed()
        );
        assertFalse(
                external.diagnostics().supportCandidateConstructed()
        );
        assertSame(
                INSTALLED,
                external.selectedFrame()
        );
    }

    @Test
    void directBlockedTransitionWithoutAuthorityNeverQueriesSupport() {
        OperationPoseTransition transition =
                GravityGeometryTransitionPlanner.plan(
                        request(
                                tilted(Math.toRadians(2.0D)),
                                blockedScene(),
                                PositionAuthorityPolicy.EXTERNAL_POSITION_ANCHOR,
                                resting(TICK, CONTACT, UP),
                                new ObbQueryContext()
                        )
                );

        assertEquals(
                "POSITION_AUTHORITY_FORBIDS_REANCHOR",
                transition.rejectionReason()
        );
        assertEquals(
                PoseFitStatus.NOT_ATTEMPTED,
                transition.diagnostics().supportFit()
        );
    }

    // -----------------------------------------------------------------
    // Support helpers
    // -----------------------------------------------------------------

    @Test
    void boundedSupportCorrectionIsDerivedFromTheEnclosingBody() {
        double bound = GravityGeometryTransitionPlanner
                .maxPreserveSupportCorrection(installedBody());
        assertTrue(bound > 0.0D);
        assertTrue(bound < 0.2D);
    }

    @Test
    void legalKinematicPoseAcceptsTouchingAndRejectsOverlap() {
        assertTrue(
                GravityGeometryTransitionPlanner.isLegalKinematicPose(
                        installedBody(),
                        List.of()
                )
        );
        assertFalse(
                GravityGeometryTransitionPlanner.isLegalKinematicPose(
                        installedBody(),
                        List.of(
                                new BlockObstacle(
                                        new CellPos(0, 100, 0),
                                        new Aabb3d(
                                                0.4D, 100.8D, 0.4D,
                                                0.6D, 101.0D, 0.6D
                                        )
                                )
                        )
                )
        );
    }

    // -----------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------

    private static GravityFrame tilted(double radians) {
        return GravityFrame.fromDown(
                Quatd.rotationZ(radians).transform(INSTALLED.down()),
                0.08D
        );
    }

    private static CollisionBody installedBody() {
        return GravityGeometryTransitionPlanner.characterBodyAtCenter(
                DIMS,
                CENTER,
                INSTALLED.up()
        );
    }

    private static RestingContactSnapshot resting(
            long gameTick,
            Vec3d contactPoint,
            Vec3d normal
    ) {
        return new RestingContactSnapshot(
                normal,
                Vec3d.ZERO,
                contactPoint,
                GravitySupportContact.SupportGeometryKind
                        .TRUSTED_CURRENT_BLOCK_FACE,
                Optional.of(SUPPORT_CELL),
                gameTick
        );
    }

    private static GeometryTransitionRequest request(
            GravityFrame proposed,
            CollisionScene scene,
            PositionAuthorityPolicy authority,
            RestingContactSnapshot resting,
            ObbQueryContext context
    ) {
        return new GeometryTransitionRequest(
                INSTALLED,
                installedBody(),
                ANCHOR,
                proposed,
                DIMS,
                scene,
                context,
                authority,
                resting,
                TICK
        );
    }

    private static FrozenScene scene(
            List<CollisionObstacle> poseFit,
            List<CollisionObstacle> support
    ) {
        return new FrozenScene(poseFit, support, false, false);
    }

    /** A hard obstacle at the character's own centre: the direct pose is illegal. */
    private static FrozenScene blockedScene() {
        return scene(
                List.of(
                        new BlockObstacle(
                                new CellPos(0, 100, 0),
                                new Aabb3d(
                                        0.4D, 100.8D, 0.4D,
                                        0.6D, 101.0D, 0.6D
                                )
                        )
                ),
                List.of()
        );
    }

    /**
     * A floor obstacle plus a ceiling slab placed between the direct pose's top
     * and the support-preserving pose's top. {@code bias} 0.5 makes the direct
     * pose penetrate and the corrected pose clear; a negative bias makes both
     * penetrate.
     */
    private static FrozenScene preservedScene(
            GravityFrame proposed,
            SupportPreserveOutcome candidate,
            double bias
    ) {
        double directTop = GravityGeometryTransitionPlanner
                .characterBodyAtCenter(DIMS, CENTER, proposed.up())
                .enclosingAabb()
                .maxY();
        double candidateTop = candidate.pose()
                .body()
                .enclosingAabb()
                .maxY();
        double ceilingBottom = candidateTop
                + bias * (directTop - candidateTop);
        BlockObstacle floor =
                new BlockObstacle(SUPPORT_CELL, FLOOR);
        BlockObstacle ceiling = new BlockObstacle(
                new CellPos(0, 102, 0),
                new Aabb3d(
                        0.0D, ceilingBottom, 0.0D,
                        1.0D, ceilingBottom + 1.0D, 1.0D
                )
        );
        return new FrozenScene(
                List.of(floor, ceiling),
                List.of(floor),
                false,
                false
        );
    }

    private static final class FrozenScene implements CollisionScene {
        private final List<CollisionObstacle> poseFitObstacles;
        private final List<CollisionObstacle> supportObstacles;
        private final boolean poseCoverageFailure;
        private final boolean supportCoverageFailure;

        private FrozenScene(
                List<CollisionObstacle> poseFitObstacles,
                List<CollisionObstacle> supportObstacles,
                boolean poseCoverageFailure,
                boolean supportCoverageFailure
        ) {
            this.poseFitObstacles = List.copyOf(poseFitObstacles);
            this.supportObstacles = List.copyOf(supportObstacles);
            this.poseCoverageFailure = poseCoverageFailure;
            this.supportCoverageFailure = supportCoverageFailure;
        }

        @Override
        public List<CollisionObstacle> query(
                CollisionBody body,
                Vec3d movement,
                SweepTimeWindow window
        ) {
            if (this.poseCoverageFailure) {
                throw new CollisionSceneCoverageException(
                        "pose fit left the captured envelope"
                );
            }
            return this.poseFitObstacles;
        }

        @Override
        public List<CollisionObstacle> querySupport(
                CollisionBody body,
                Vec3d movement,
                SweepTimeWindow window
        ) {
            if (this.supportCoverageFailure) {
                throw new CollisionSceneCoverageException(
                        "support query left the captured envelope"
                );
            }
            return this.supportObstacles;
        }

        @Override
        public long tick() {
            return TICK;
        }

        @Override
        public KinematicStepContext time() {
            return KinematicStepContext.fullTick(TICK, 1L);
        }

        @Override
        public CollisionWorkDiagnostics diagnostics() {
            return CollisionWorkDiagnostics.empty();
        }
    }
}
