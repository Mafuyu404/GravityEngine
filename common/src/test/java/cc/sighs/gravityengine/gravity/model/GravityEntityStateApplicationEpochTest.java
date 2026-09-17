package cc.sighs.gravityengine.gravity.model;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class GravityEntityStateApplicationEpochTest {
    private static final GravityState CUSTOM_STATE =
            new GravityState(new Vec3d(1.0, 0.0, 0.0), 0.08);
    private static final CommittedGravityApplication APPLICATION =
            new CommittedGravityApplication(
                    CUSTOM_STATE,
                    GravitySuppressionReason.NONE,
                    GravityApplicationPlan.character(
                            GravityAccelerationMode.FIELD));

    @Test
    void serverCommitAdvancesOnlyWhenApplicationChanges() {
        GravityEntityState state = new GravityEntityState();

        state.commitAuthoritativeApplication(APPLICATION);
        assertEquals(1L, state.applicationEpoch());
        assertEquals(APPLICATION, state.committedApplication());

        state.commitAuthoritativeApplication(APPLICATION);
        assertEquals(1L, state.applicationEpoch());
    }

    @Test
    void remoteInstallAcceptsServerEpochAndRejectsStaleRevision() {
        GravityEntityState state = new GravityEntityState();

        assertEquals(
                GravityEntityState.RemoteApplicationAcceptance.ACCEPTED,
                state.acceptRemoteApplication(APPLICATION, 7L));
        assertEquals(7L, state.applicationEpoch());
        assertEquals(APPLICATION, state.committedApplication());

        assertEquals(
                GravityEntityState.RemoteApplicationAcceptance.STALE,
                state.acceptRemoteApplication(APPLICATION, 6L));
        assertEquals(7L, state.applicationEpoch());
    }

    @Test
    void equalEpochConflictDoesNotSilentlyReplaceAuthoritativeState() {
        GravityEntityState state = new GravityEntityState();
        state.acceptRemoteApplication(APPLICATION, 3L);
        CommittedGravityApplication conflicting =
                new CommittedGravityApplication(
                        CUSTOM_STATE,
                        GravitySuppressionReason.NONE,
                        GravityApplicationPlan.vanilla());

        assertEquals(
                GravityEntityState.RemoteApplicationAcceptance.CONFLICT,
                state.acceptRemoteApplication(conflicting, 3L));
        assertEquals(3L, state.applicationEpoch());
        assertSame(APPLICATION, state.committedApplication());
    }

    @Test
    void replicaInstallDoesNotManufactureAServerRevision() {
        GravityEntityState state = new GravityEntityState();

        state.installReplicaApplication(APPLICATION);

        assertEquals(0L, state.applicationEpoch());
        assertEquals(APPLICATION, state.committedApplication());
    }

    @Test
    void replicatedAssignmentAcceptanceDoesNotCommitApplication() {
        GravityEntityState state = new GravityEntityState();

        assertEquals(
                GravityEntityState.SnapshotAcceptance.ACCEPTED,
                state.acceptRemoteAssignment(
                        CUSTOM_STATE,
                        GravityAuthorityMode.DIRECT,
                        false,
                        4L));

        assertEquals(0L, state.applicationEpoch());
        assertEquals(
                CommittedGravityApplication.vanillaNoAssignment(),
                state.committedApplication());
    }
}
