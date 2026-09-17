package cc.sighs.gravityengine.gravity.debug;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class BootDebugOptionsTest {
    static BootDebugOptions.Options options(Map<String, String> properties) {
        return BootDebugOptions.parse(properties::get, message -> fail(message));
    }

    @Test void defaultsAreOffWithNoStacksOrFilter() {
        var options = options(Map.of());
        assertFalse(options.anyTextEnabled());
        assertEquals(DebugTraceLevel.OFF, options.gravity());
        assertEquals(DebugTraceLevel.OFF, options.movement());
        assertEquals(DebugTraceLevel.OFF, options.view());
        assertFalse(options.viewStacks());
        assertFalse(options.failOnInvariant());
        assertNull(options.entity());
        assertEquals(DebugSide.BOTH, options.side());
        assertEquals(0.05, options.velocityThreshold());
    }

    @Test void legacyFlagsSelectFullAndPreserveSpatialPrecedence() {
        var gravity = options(Map.of("gravityengine.debugGravity", "true"));
        assertEquals(DebugTraceLevel.FULL, gravity.gravity());
        assertTrue(gravity.spatialState());
        assertFalse(options(Map.of("gravityengine.debugGravity", "true",
                "gravityengine.debugSpatialState", "false")).spatialState());
        var movement = options(Map.of("gravityengine.debugMovement", "true",
                "gravityengine.debugSpatialState", "false"));
        assertEquals(DebugTraceLevel.FULL, movement.movement());
        assertTrue(movement.spatialState());
        assertEquals(DebugTraceLevel.FULL, options(Map.of("gravityengine.debugView", "true")).view());
        assertFalse(options(Map.of("gravityengine.debugView", "true")).viewStacks());
        assertTrue(options(Map.of("gravityengine.debugViewStacks", "true")).viewStacks());
    }

    @Test void malformedConfigurationWarnsOncePerSettingWithoutEchoingInput() {
        var warnings = new ArrayList<String>();
        var parsed = BootDebugOptions.parse(Map.of("gravityengine.debugEntity", "secret-invalid-uuid",
                "gravityengine.debugSide", "invalid", "gravityengine.debugGravityVelocityThreshold", "NaN")::get,
                warnings::add);
        assertEquals(3, warnings.size());
        assertTrue(warnings.stream().noneMatch(message -> message.contains("secret-invalid-uuid")));
        assertNull(parsed.entity());
        assertEquals(DebugSide.BOTH, parsed.side());
        assertEquals(0.05, parsed.velocityThreshold());
        // Java UUID.fromString accepts shortened groups; configuration requires canonical UUIDs.
        warnings.clear();
        assertNull(BootDebugOptions.parse(Map.of("gravityengine.debugEntity", "1-1-1-1-1")::get,
                warnings::add).entity());
        assertEquals(1, warnings.size());
        warnings.clear();
        assertNull(BootDebugOptions.parse(Map.of("gravityengine.debugEntity", "")::get,
                warnings::add).entity());
        assertEquals(1, warnings.size());
    }

    @Test void entityAndLogicalSideSelectionCompose() {
        UUID actor = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
        UUID other = UUID.fromString("11234567-89ab-cdef-0123-456789abcdef");
        var defaults = options(Map.of());
        assertTrue(defaults.matches(actor, true));
        assertTrue(defaults.matches(other, false));
        for (String side : new String[]{"client", "server", "both"}) {
            var selected = options(Map.of("gravityengine.debugEntity", actor.toString(), "gravityengine.debugSide", side));
            assertEquals(!side.equals("server"), selected.matches(actor, true));
            assertEquals(!side.equals("client"), selected.matches(actor, false));
            assertFalse(selected.matches(other, true));
            assertFalse(selected.matches(other, false));
        }
    }

    @Test void pureMixinSelectionNeverDisablesProductionInputOrCamera() {
        String prefix = "cc.sighs.gravityengine.mixin.";
        var off = options(Map.of());
        var view = options(Map.of("gravityengine.debugView", "true"));
        var gravity = options(Map.of("gravityengine.debugGravity", "true"));
        for (String name : new String[]{"PlayerViewWriteTraceMixin", "PlayerViewTickDebugMixin",
                "CameraViewDebugMixin", "ClientPacketViewDebugMixin", "ServerPlayerViewDebugMixin"}) {
            assertFalse(DebugMixinSelection.shouldApply(prefix + name, off));
            assertFalse(DebugMixinSelection.shouldApply(prefix + name, gravity));
            assertTrue(DebugMixinSelection.shouldApply(prefix + name, view));
        }
        for (String name : new String[]{"ClientEntityLocalLookInputMixin", "CameraMixin", "PlayerMixin",
                "EntityMixin", "ClientPacketListenerMixin", "LocalPlayerMixin"}) {
            assertTrue(DebugMixinSelection.shouldApply(prefix + name, off));
        }
        assertFalse(DebugMixinSelection.shouldApply(prefix + "ClientEntityLocalLookDebugMixin", off));
        assertTrue(DebugMixinSelection.shouldApply(prefix + "ClientEntityLocalLookDebugMixin", gravity));
        assertFalse(DebugMixinSelection.shouldApply(prefix + "CameraGravityDebugMixin", view));
        assertTrue(DebugMixinSelection.shouldApply(prefix + "SomeFutureDebugName", off));
    }
}
