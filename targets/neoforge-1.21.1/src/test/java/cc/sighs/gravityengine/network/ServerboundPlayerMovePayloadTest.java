package cc.sighs.gravityengine.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerboundPlayerMovePayloadTest {
    @Test void nativeMovementVariantsRoundTripWithoutClockOrGeometry() {
        var dimension = ResourceLocation.parse("minecraft:overworld");
        for (var move : new ServerboundMovePlayerPacket[]{
                new ServerboundMovePlayerPacket.StatusOnly(true),
                new ServerboundMovePlayerPacket.Rot(123, -42, false),
                new ServerboundMovePlayerPacket.Pos(1.25, -40, 3.75, true),
                new ServerboundMovePlayerPacket.PosRot(1.25, -40, 3.75, 123, -42, false)}) {
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                ServerboundPlayerMovePayload.STREAM_CODEC.encode(buffer, new ServerboundPlayerMovePayload(dimension, 12345, move));
                var decoded = ServerboundPlayerMovePayload.STREAM_CODEC.decode(buffer);
                assertEquals(dimension, decoded.dimension());
                assertEquals(12345, decoded.bodyEpoch());
                var result = decoded.movement();
                assertEquals(move.getClass(), result.getClass());
                assertEquals(move.getX(99), result.getX(99));
                assertEquals(move.getY(99), result.getY(99));
                assertEquals(move.getZ(99), result.getZ(99));
                assertEquals(move.getYRot(99), result.getYRot(99));
                assertEquals(move.getXRot(99), result.getXRot(99));
                assertEquals(move.isOnGround(), result.isOnGround());
                assertFalse(buffer.isReadable());
            } finally { buffer.release(); }
        }
    }
}
