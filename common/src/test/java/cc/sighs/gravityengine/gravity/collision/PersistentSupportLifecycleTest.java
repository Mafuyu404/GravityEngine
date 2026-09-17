package cc.sighs.gravityengine.gravity.collision;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.model.GravityCollisionRoute;
import cc.sighs.gravityengine.gravity.model.GravityOperationType;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * End-to-end engine-owned lifecycle: a real terminal endpoint result publishes
 * persistent support identity, and the next operation transports that support
 * from the engine's own rigid-motion publication.
 */
class PersistentSupportLifecycleTest {
    private static final Vec3d UP = new Vec3d(0.0D, 1.0D, 0.0D);
    private static final long SOURCE = 21L;
    private static final long EPOCH = 3L;

    @Test
    void terminalEndpointPublishesSupportThatTheNextOperationTransports() {
        GravityOperationState state = new GravityOperationState();
        CapturedCollisionScene first = scene(100L, 5L, 0.0D, 1L);

        GravityOperationState.MoveScope scope = state.openMove(
                GravityFrame.DEFAULT,
                100L,
                GravityOperationType.MOVE
        );
        state.beginMovement(GravityCollisionRoute.EXACT_BODY);
        state.setCollisionOperation(context(first));
        state.setCurrentMoveResult(
                result(support(), true, false)
        );
        scope.close();

        assertNotNull(state.persistentSupportState());
        assertEquals(
                new Vec3d(0.5D, 1.0D, 0.5D),
                state.persistentSupportState().localAnchor()
        );

        CapturedCollisionScene next = scene(101L, 6L, 1.0D, 2L);
        SupportTransport transport =
                state.resolveSupportTransport(next).orElseThrow();

        assertEquals(new Vec3d(1.0D, 0.0D, 0.0D), transport.displacement());
        assertEquals(
                new Vec3d(1.0D, 0.0D, 0.0D),
                transport.surfaceVelocity()
        );
        assertEquals(2L, transport.motionRevision());
    }

    @Test
    void pathContactEvidenceNeverPublishesPersistentSupport() {
        GravityOperationState state = new GravityOperationState();
        CapturedCollisionScene scene = scene(100L, 5L, 0.0D, 1L);

        GravityOperationState.MoveScope scope = state.openMove(
                GravityFrame.DEFAULT,
                100L,
                GravityOperationType.MOVE
        );
        state.beginMovement(GravityCollisionRoute.EXACT_BODY);
        state.setCollisionOperation(context(scene));
        state.setCurrentMoveResult(
                result(support(), false, true)
        );
        scope.close();

        assertNull(state.persistentSupportState());
    }

    private static GravityOperationState.CollisionOperationContext context(
            CapturedCollisionScene scene
    ) {
        return new GravityOperationState.CollisionOperationContext(
                GravityFrame.DEFAULT,
                scene.time(),
                scene,
                new ObbQueryContext(),
                new CollisionWorkTracker(CollisionWorkBudget.defaults())
        );
    }

    private static GravitySupportContact support() {
        return new GravitySupportContact(
                UP,
                Vec3d.ZERO,
                new Vec3d(0.5D, 1.0D, 0.5D),
                GravitySupportContact.SupportGeometryKind.REAL_OBSTACLE_FACE,
                SupportFaceIdentity.dynamic(
                        SOURCE,
                        0L,
                        EPOCH,
                        GravitySupportContact.blockFaceIndex(UP)
                )
        );
    }

    private static GravityMoveResult result(
            GravitySupportContact contact,
            boolean terminalGrounded,
            boolean pathOnly
    ) {
        return new GravityMoveResult(
                Vec3d.ZERO,
                Vec3d.ZERO,
                Vec3d.ZERO,
                Vec3d.ZERO,
                true,
                false,
                false,
                pathOnly,
                pathOnly ? Optional.of(contact) : Optional.empty(),
                terminalGrounded,
                terminalGrounded,
                Optional.empty(),
                0.0D,
                false,
                List.of(),
                0.0D,
                GravityFrame.DEFAULT,
                pathOnly ? Optional.empty() : Optional.of(contact),
                List.of(),
                MovementIndeterminateReason.NONE
        );
    }

    private static CapturedCollisionScene scene(
            long tick,
            long revision,
            double displacementX,
            long motionRevision
    ) {
        return SceneFixtures.scene(
                tick,
                revision,
                1.0D,
                List.of(),
                List.of(SceneFixtures.dynamicObstacle(
                        SOURCE,
                        0L,
                        EPOCH,
                        Vec3d.ZERO,
                        3.0D,
                        new Vec3d(displacementX, 0.0D, 0.0D),
                        Vec3d.ZERO,
                        tick,
                        motionRevision,
                        1.0D
                ))
        );
    }
}
