package cc.sighs.gravityengine.mixin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.Opcodes;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Reads compiled classes without loading Minecraft or applying Mixins. */
class DebugInstrumentationSeamTest {
    private static final String ROOT = "cc.sighs.gravityengine.";

    @Test void registeredMixinsExistAndClientTargetsStayInClientSection() throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream("gravityengine.mixins.json")) {
            assertNotNull(stream);
            var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(stream,
                    java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(ROOT + "mixin.OptionalCompatibilityPlugin", json.get("plugin").getAsString());
            for (String section : List.of("mixins", "client")) {
                for (var entry : json.getAsJsonArray(section)) {
                    var node = read("mixin." + entry.getAsString());
                    if (section.equals("mixins")) {
                        for (var method : node.methods) for (var instruction : method.instructions) {
                            if (instruction instanceof MethodInsnNode call)
                                assertFalse(call.owner.startsWith("net/minecraft/client/"), node.name);
                        }
                    }
                }
            }
        }
    }

    @Test void productionInputAndDiscontinuityCaptureSurviveWithNoDebugDependency() {
        var input = read("mixin.ClientEntityLocalLookInputMixin");
        assertEquals(1, calls(input, "accumulateRawLook"));
        assertEquals(0, calls(read("mixin.ClientEntityLocalLookDebugMixin"), "accumulateRawLook"));
        var entity = read("mixin.EntityMixin");
        assertEquals(1, calls(entity, "recordPostDiscontinuityVelocity"));
        for (var node : List.of(input, entity, read("mixin.PlayerMixin"), read("mixin.CameraMixin"),
                read("mixin.LocalPlayerMixin"), read("mixin.ClientPacketListenerMixin"))) {
            for (var method : node.methods) for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call)
                    assertFalse(call.owner.contains("/gravity/debug/"), node.name + ": " + call.name);
            }
        }
    }

    @Test void diagnosticsCannotExecutePhysicsOrResolveAnotherCameraSample() {
        for (String name : List.of("gravity.integration.diagnostics.MovementCollisionDiagnostics",
                "gravity.integration.diagnostics.MovementCollisionDiagnostics$Span",
                "mixin.CameraViewDebugMixin", "mixin.CameraGravityDebugMixin")) {
            for (var method : read(name).methods) for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    assertFalse(call.name.startsWith("resolveContactVelocity"), call.toString());
                    assertFalse(call.owner.endsWith("/ContactConstraintProjector"));
                    assertFalse(call.owner.endsWith("/GravityCollisionEngine"));
                    assertFalse(call.owner.endsWith("/CurrentContactQuery"));
                    assertFalse(call.owner.endsWith("/BodyRenderPoseResolver"));
                    assertFalse(call.owner.endsWith("/ClientGravityFrameSampler"));
                    assertFalse(call.owner.endsWith("/GravityPresentationIntegration"));
                }
            }
        }
        MethodNode commit = read("gravity.integration.ContactVelocityIntegration").methods.stream()
                .filter(method -> method.name.equals("commitMoveContactVelocity")).findFirst().orElseThrow();
        boolean velocityWritten = false;
        int observations = 0;
        for (var instruction : commit.instructions) if (instruction instanceof MethodInsnNode call) {
            if (call.name.equals("setDeltaMovement")) velocityWritten = true;
            if (call.name.equals("recordVelocityResolution")) {
                observations++;
                assertTrue(velocityWritten, "observe only after the real velocity commit");
                assertTrue(call.desc.contains("ContactVelocityResolution;"));
            }
        }
        assertEquals(1, observations);
    }

    @Test void bootAndPluginSelectionHaveNoGameOrClientLinkage() {
        for (String name : List.of("gravity.debug.BootDebugOptions", "gravity.debug.BootDebugOptions$Options",
                "gravity.debug.DebugMixinSelection", "mixin.OptionalCompatibilityPlugin")) {
            for (var method : read(name).methods) for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    assertFalse(call.owner.startsWith("net/minecraft/"));
                    assertFalse(call.owner.startsWith("cc/sighs/gravityengine/client/"));
                }
            }
        }
    }

    @Test void ordinaryAndUnpublishedSolvesCarryExplicitDiagnosticOwnership() {
        var engine = read("gravity.integration.collision.GravityCollisionEngine");
        var auxiliary = engine.methods.stream().filter(m -> m.name.equals("resolveUnpublished"))
                .findFirst().orElseThrow();
        int auxiliarySolves = 0;
        for (var instruction : auxiliary.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals("resolve")) {
                auxiliarySolves++;
                assertTrue(call.desc.contains("MovementCollisionDiagnostics$Span;"));
                assertEquals(Opcodes.ACONST_NULL, call.getPrevious().getOpcode(),
                        "unpublished solve must not acquire the enclosing move's observation");
            }
        }
        assertEquals(1, auxiliarySolves);
        var solve = engine.methods.stream().filter(m -> m.name.equals("resolve"))
                .findFirst().orElseThrow();
        int observations = 0;
        for (var instruction : solve.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.endsWith("/MovementCollisionDiagnostics")) {
                assertTrue(call.name.equals("input") || call.name.equals("result"));
                assertTrue(call.desc.contains("MovementCollisionDiagnostics$Span;"));
                observations++;
            }
        }
        assertEquals(2, observations);
        var vanilla = read("mixin.EntityMovementDebugMixin");
        assertEquals(1, calls(vanilla, "capture"));
        assertEquals(1, calls(vanilla, "vanillaInput"));
        assertEquals(1, calls(vanilla, "vanillaResult"));
        assertEquals(1, calls(vanilla, "call"), "observe exactly one real Vanilla solve");
    }

    private static long calls(ClassNode node, String name) {
        long count = 0;
        for (var method : node.methods) for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && call.name.equals(name)) count++;
        return count;
    }

    private static ClassNode read(String name) {
        try (var stream = DebugInstrumentationSeamTest.class.getClassLoader()
                .getResourceAsStream((ROOT + name).replace('.', '/') + ".class")) {
            assertNotNull(stream, name);
            var node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            return node;
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }
}
