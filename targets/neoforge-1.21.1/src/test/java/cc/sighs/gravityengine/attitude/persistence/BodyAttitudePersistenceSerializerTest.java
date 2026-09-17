package cc.sighs.gravityengine.attitude.persistence;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.math.Quatd;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BodyAttitudePersistenceSerializerTest {
    private static CompoundTag valid() {
        return BodyAttitudePersistenceSerializer.encode(new BodyAttitudePersistentSeed(
                Quatd.IDENTITY, Quatd.IDENTITY, Optional.empty()));
    }

    private static void rejected(CompoundTag tag, String field, String reason) {
        var result = BodyAttitudePersistenceSerializer.decode(tag);
        assertTrue(result.result().isEmpty(), "invalid record must never supply an authoritative seed");
        String message = result.error().orElseThrow().message();
        assertTrue(message.contains(field), message);
        assertTrue(message.contains(reason), message);
    }

    @Test void currentMalformedQuaternionsReportFieldAndReason() {
        var nan = valid();
        nan.putDouble("BodyQX", Double.NaN);
        rejected(nan, "BodyQX", "finite");
        var missing = valid();
        missing.remove("ControllerQW");
        rejected(missing, "ControllerQW", "missing");
        var zero = valid();
        zero.putDouble("BodyQW", 0);
        rejected(zero, "worldFromBody", "non-degenerate");
        var controllerZero = valid();
        controllerZero.putDouble("ControllerQW", 0);
        rejected(controllerZero, "worldFromController", "non-degenerate");
    }

    @Test void emptyPresentRecordAndUnsupportedVersionAreErrors() {
        rejected(new CompoundTag(), "Version", "missing");
        var unknown = valid();
        for (int version : new int[]{5, 999}) {
            unknown.putInt("Version", version);
            rejected(unknown, "Version", "unsupported");
        }
    }

    @Test void malformedOptionalMomentumRejectsTheWholeSeed() {
        var wrongType = valid();
        wrongType.putString("AngularDynamics", "invalid");
        rejected(wrongType, "AngularDynamics", "wrong NBT type");
        var missing = valid();
        missing.put("AngularDynamics", new CompoundTag());
        rejected(missing, "AngularDynamics.AngularMomentumX", "missing");
        var extra = valid();
        extra.putInt("Unexpected", 1);
        rejected(extra, "Unexpected", "unexpected fields");
    }

    @Test void currentKinematicAndDynamicSeedsRoundTripAtDoublePrecision() {
        for (var momentum : java.util.List.of(Optional.<Vec3d>empty(),
                Optional.of(Vec3d.ZERO), Optional.of(new Vec3d(.12345678912345, -2, 3)))) {
            var seed = new BodyAttitudePersistentSeed(Quatd.rotationZYX(.3, -.2, .7),
                    Quatd.rotationZYX(.9, .8, -.5), momentum);
            var result = BodyAttitudePersistenceSerializer.decode(BodyAttitudePersistenceSerializer.encode(seed));
            assertTrue(result.error().isEmpty());
            var restored = result.result().orElseThrow();
            assertEquals(momentum, restored.angularMomentumWorld());
            assertEquals(1, Math.abs(seed.worldFromBody().dot(restored.worldFromBody())), 1e-14);
            assertEquals(1, Math.abs(seed.worldFromController().dot(restored.worldFromController())), 1e-14);
        }
    }
}
