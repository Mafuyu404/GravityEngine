package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.collision.GravityMoveResult;
import cc.sighs.gravityengine.gravity.collision.MovementIndeterminateReason;
import cc.sighs.gravityengine.gravity.model.GravityCollisionRoute;
import cc.sighs.gravityengine.gravity.model.GravityOperationType;
import cc.sighs.gravityengine.gravity.runtime.ExternalSubLevelMoveEvidence;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState;
import cc.sighs.gravityengine.gravity.runtime.MinecraftMoveInteropState;
import cc.sighs.gravityengine.gravity.runtime.VanillaCollisionState;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;

/**
 * Publication-scoped SubLevel/Sable interop regression.
 *
 * <p>The former entity-wide single evidence slot let a nested movement
 * publication overwrite the enclosing publication's evidence; when the nested
 * scope closed, the parent's SubLevel/Sable collision facts were gone. This
 * check drives the real nested publication lifecycle and asserts that the
 * parent evidence and its projected collision state are restored.</p>
 */
final class MoveInteropScopeChecks {
    private static int assertions;

    private MoveInteropScopeChecks() {}

    static void run() {
        GravityOperationState state = new GravityOperationState();
        MinecraftMoveInteropState interop =
                new MinecraftMoveInteropState(state);

        check(
                interop.subLevelMoveEvidence() == null,
                "no evidence exists outside a movement publication"
        );

        GravityOperationState.MoveScope parent = state.openMove(
                GravityFrame.DEFAULT,
                11L,
                GravityOperationType.MOVE
        );
        state.beginMovement(GravityCollisionRoute.EXACT_BODY);
        state.setCurrentMoveResult(groundedResult());

        ExternalSubLevelMoveEvidence parentEvidence =
                evidence(true, false, false, false);
        interop.setSubLevelMoveEvidence(parentEvidence);

        check(
                interop.subLevelMoveEvidence() == parentEvidence,
                "parent publication owns its evidence"
        );
        VanillaCollisionState parentState =
                interop.currentCollisionState();
        check(
                parentState != null
                        && parentState.onGround()
                        && !parentState.horizontalCollision()
                        && !parentState.minorHorizontalCollision(),
                "parent SubLevel/Sable collision facts project"
        );

        GravityOperationState.MoveScope nested = state.openMove(
                GravityFrame.DEFAULT,
                11L,
                GravityOperationType.MOVE
        );
        state.beginMovement(GravityCollisionRoute.EXACT_BODY);
        state.setCurrentMoveResult(groundedResult());

        ExternalSubLevelMoveEvidence nestedEvidence =
                evidence(false, true, true, true);
        interop.setSubLevelMoveEvidence(nestedEvidence);

        check(
                interop.subLevelMoveEvidence() == nestedEvidence,
                "nested publication owns its own evidence"
        );
        VanillaCollisionState nestedState =
                interop.currentCollisionState();
        check(
                nestedState != null
                        && nestedState.horizontalCollision()
                        && nestedState.verticalCollision()
                        && nestedState.minorHorizontalCollision(),
                "nested SubLevel/Sable collision facts project"
        );

        nested.close();

        check(
                interop.subLevelMoveEvidence() == parentEvidence,
                "nested close restores the parent evidence"
        );
        check(
                interop.currentCollisionState().equals(parentState),
                "nested close restores the parent collision facts"
        );

        parent.close();

        check(
                interop.subLevelMoveEvidence() == null,
                "no evidence exists after the outer publication closes"
        );
        check(
                interop.currentCollisionState() == null,
                "no collision projection exists after the outer close"
        );

        GravityOperationState clearedState = new GravityOperationState();
        MinecraftMoveInteropState clearedInterop =
                new MinecraftMoveInteropState(clearedState);
        GravityOperationState.MoveScope clearedScope =
                clearedState.openMove(
                        GravityFrame.DEFAULT,
                        11L,
                        GravityOperationType.MOVE
                );
        clearedState.beginMovement(GravityCollisionRoute.EXACT_BODY);
        clearedInterop.setSubLevelMoveEvidence(
                evidence(true, true, true, true)
        );
        check(
                clearedInterop.subLevelMoveEvidence() != null,
                "publication evidence is recorded before cleanup"
        );

        clearedInterop.clear();
        check(
                clearedInterop.subLevelMoveEvidence() == null,
                "discontinuity cleanup drops per-publication evidence"
        );
        clearedScope.close();

        System.out.println(
                "MOVE_INTEROP_SCOPE_CHECKS_PASSED assertions=" + assertions
        );
    }

    private static ExternalSubLevelMoveEvidence evidence(
            boolean sableCompatibilityGround,
            boolean subLevelHorizontalCollision,
            boolean subLevelVerticalCollision,
            boolean subLevelMinorHorizontalCollision
    ) {
        return new ExternalSubLevelMoveEvidence(
                Vec3.ZERO,
                Vec3.ZERO,
                sableCompatibilityGround,
                subLevelHorizontalCollision,
                subLevelVerticalCollision,
                subLevelVerticalCollision,
                subLevelMinorHorizontalCollision,
                true,
                false,
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
        );
    }

    private static GravityMoveResult groundedResult() {
        return new GravityMoveResult(
                Vec3d.ZERO,
                Vec3d.ZERO,
                Vec3d.ZERO,
                Vec3d.ZERO,
                true,
                false,
                false,
                true,
                Optional.empty(),
                true,
                true,
                Optional.empty(),
                0.0D,
                false,
                List.of(),
                0.0D,
                GravityFrame.DEFAULT,
                Optional.empty(),
                List.of(),
                MovementIndeterminateReason.NONE
        );
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
