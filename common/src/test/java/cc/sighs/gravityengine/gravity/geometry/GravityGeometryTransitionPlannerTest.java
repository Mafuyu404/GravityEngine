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
