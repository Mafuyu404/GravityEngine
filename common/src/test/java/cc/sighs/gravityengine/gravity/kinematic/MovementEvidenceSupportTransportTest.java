package cc.sighs.gravityengine.gravity.kinematic;

import cc.sighs.gravityengine.api.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MovementEvidenceSupportTransportTest {
    @Test
    void supportTransportIsAttributedSeparatelyFromActorInput() {
        Vec3d actorIntent =
                new Vec3d(0.2D, 0.0D, 0.0D);
        Vec3d transport =
                new Vec3d(1.0D, 0.0D, 0.0D);
        MovementEvidence evidence = MovementEvidence.capture(
                KinematicMoveRequest.Channel.SELF,
                actorIntent,
                new OwnedMotion(
                        actorIntent,
                        Vec3d.ZERO,
                        Vec3d.ZERO
                )
        );

        KinematicMoveRequest request = evidence.reconcile(
                actorIntent.add(transport),
                transport
        );

        assertEquals(
                actorIntent.add(transport),
                request.actualMovement()
        );
        assertEquals(
                actorIntent,
                request.ownership().selfWalk()
        );
        assertEquals(
                transport,
                request.ownership().supportMotion()
        );
        assertEquals(
                Vec3d.ZERO,
                request.ownership().externalPush()
        );
    }
}
