package cc.sighs.gravityengine.network;

import cc.sighs.gravityengine.api.FieldPresence;
import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.model.*;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class FieldEvidenceWireTest {
    @Test
    void unknownRoundTripsAndEvidenceOnlyUpdateIsAcceptedWithoutDurableMutation() {
        var state = new GravityEntityState();
        var id = UUID.randomUUID();
        long revision = 7;
        for (var evidence : new FieldPresence[]{FieldPresence.PRESENT, FieldPresence.UNKNOWN, FieldPresence.ABSENT}) {
            var payload = new SyncGravityStatePayload(1, id, ResourceLocation.parse("minecraft:overworld"),
                    revision++, new Vec3d(0, -1, 0), .08, 0, GravitySuppressionReason.NONE,
                    GravityAuthorityMode.FIELD, evidence, 3, CommittedGravityApplication.vanillaNoAssignment());
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                SyncGravityStatePayload.encode(buffer, payload);
                var decoded = SyncGravityStatePayload.decode(buffer);
                assertEquals(payload, decoded);
                assertEquals(GravityEntityState.SnapshotAcceptance.ACCEPTED,
                        state.acceptRemoteAssignment(decoded.toState(), decoded.authorityMode(),
                                decoded.fieldPresence(), decoded.assignmentRevision()));
                assertEquals(evidence, state.fieldPresence());
                assertEquals(0, state.assignmentRevision(), "client evidence does not mutate durable revision");
                assertEquals(GravityEntityState.SnapshotAcceptance.STALE,
                        state.acceptRemoteAssignment(GravityState.DEFAULT, GravityAuthorityMode.FIELD,
                                evidence, decoded.assignmentRevision() - 1));
            } finally { buffer.release(); }
        }
    }
}
