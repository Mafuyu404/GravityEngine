package cc.sighs.gravityengine.mixin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Structural guard for the packet rigid-capture lifecycle seam.
 *
 * <p>The behavioural rule is: the one rigid snapshot is captured after
 * Vanilla's main-thread hand-off, invalid-value validation and coordinate
 * clamping, and before {@code jumpFromGround}/{@code player.move}. The
 * executable proof for that boundary is that no {@code @Inject} at
 * {@code HEAD} of {@code handleMovePlayer} performs world/provider work, and
 * that the capture is hosted on Vanilla's first
 * {@code ServerPlayer.getBoundingBox()} wrap operation.</p>
 *
 * <p>This reads the compiled mixin bytecode so the assertion decays into a
 * build failure the moment someone reintroduces a {@code HEAD} capture or
 * moves the capture to a different seam.</p>
 */
class PacketRigidCaptureSeamTest {
    private static final String MIXIN_CLASS =
            "cc.sighs.gravityengine.mixin."
                    + "ServerGamePacketListenerImplMixin";
    private static final String HANDLE_MOVE_PLAYER =
            "handleMovePlayer";
    private static final String CAPTURE_HANDLER =
            "gravityengine$captureOldBody";

    @Test
    void predicatesCannotCaptureAndPacketHasExactlyOneCaptureCall() {
        int captures = 0;
        for (MethodNode method : readMixin().methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.endsWith("/RigidOccupancySnapshot") && call.name.equals("capture")) {
                    assertEqualsValue(CAPTURE_HANDLER, method.name);
                    captures++;
                }
                if (instruction instanceof MethodInsnNode call
                        && call.owner.endsWith("/VanillaBodyOccupancy")
                        && (call.name.equals("oldBodyClear") || call.name.equals("hasNewCollision"))) {
                    assertTrue(call.desc.contains("RigidOccupancySnapshot;"), "occupancy requires captured geometry");
                }
            }
        }
        assertEqualsValue(1, captures);
        ClassNode occupancy = readClass("cc.sighs.gravityengine.gravity.integration.vanilla.VanillaBodyOccupancy");
        for (MethodNode method : occupancy.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    assertFalse(call.owner.endsWith("/MinecraftCollisionSceneCapture"));
                    assertFalse(call.owner.endsWith("/RigidOccupancySnapshot") && call.name.equals("capture"));
                }
            }
        }
        ClassNode capture = readClass("cc.sighs.gravityengine.gravity.integration.collision.MinecraftCollisionSceneCapture");
        for (var instruction : method(capture, "captureRigidOccupancyBounded").instructions) {
            if (instruction instanceof MethodInsnNode call) {
                assertFalse(List.of("capture", "captureBlocks", "captureWorldBorder", "capturePoseStrictEntities", "captureAround")
                        .contains(call.name) && call.owner.endsWith("/MinecraftCollisionSceneCapture"),
                        "packet rigid capture must not enumerate static world geometry");
            }
        }
    }

    @Test
    void handleMovePlayerHasNoHeadTimeRigidCapture() {
        ClassNode mixin = readMixin();
        for (MethodNode method : mixin.methods) {
            List<AnnotationNode> injects = annotations(
                    method,
                    "Lorg/spongepowered/asm/mixin/injection/Inject;"
            );
            for (AnnotationNode inject : injects) {
                String target = stringValue(inject, "method");
                if (target == null
                        || !target.startsWith(
                        HANDLE_MOVE_PLAYER)) {
                    continue;
                }
                assertFalse(
                        isHeadAt(inject),
                        "packet capture must not run at handleMovePlayer "
                                + "HEAD: " + method.name
                );
            }
        }
    }

    @Test
    void captureIsHostedOnTheFirstOldBodyBoundsSeam() {
        MethodNode capture = method(readMixin(), CAPTURE_HANDLER);
        assertNotNull(
                capture,
                CAPTURE_HANDLER + " must exist"
        );

        List<AnnotationNode> wraps = annotations(
                capture,
                "Lcom/llamalad7/mixinextras/injector/wrapoperation/"
                        + "WrapOperation;"
        );
        assertFalse(
                wraps.isEmpty(),
                CAPTURE_HANDLER + " must be a WrapOperation"
        );

        for (AnnotationNode wrap : wraps) {
            String target = stringValue(wrap, "method");
            if (!HANDLE_MOVE_PLAYER.equals(target)) {
                continue;
            }
            AnnotationNode at = annotationValue(wrap, "at");
            assertNotNull(at, "@WrapOperation must declare @At");
            assertEqualsValue(
                    "INVOKE",
                    stringValue(at, "value")
            );
            String invokeTarget = stringValue(at, "target");
            assertTrue(
                    invokeTarget != null
                            && invokeTarget.startsWith(
                            "Lnet/minecraft/server/level/ServerPlayer;"
                                    + "getBoundingBox()"),
                    "capture seam must be the ServerPlayer bounds call, "
                            + "was " + invokeTarget
            );
            /*
             * Vanilla's first getBoundingBox inside handleMovePlayer is the
             * only one that is already after the thread hand-off, invalid
             * value check, clamp, teleport/passenger/sleep branches and the
             * moved-too-quickly rejection.
             */
            assertEqualsValue(
                    0,
                    integerValue(at, "ordinal")
            );
            return;
        }
        fail(CAPTURE_HANDLER + " does not target handleMovePlayer");
    }

    private static ClassNode readMixin() {
        return readClass(MIXIN_CLASS);
    }

    private static ClassNode readClass(String name) {
        String resource = name.replace('.', '/') + ".class";
        try (InputStream stream = PacketRigidCaptureSeamTest.class
                .getClassLoader()
                .getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "compiled mixin class not found: " + resource
                );
            }
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(
                    node,
                    ClassReader.SKIP_FRAMES
            );
            return node;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(
                    "cannot read " + resource,
                    failure
            );
        }
    }

    private static MethodNode method(
            ClassNode node,
            String name
    ) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name)) {
                return method;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<AnnotationNode> annotations(
            MethodNode method,
            String descriptor
    ) {
        List<AnnotationNode> result = new ArrayList<>();
        if (method.visibleAnnotations != null) {
            for (AnnotationNode annotation
                    : method.visibleAnnotations) {
                if (descriptor.equals(annotation.desc)) {
                    result.add(annotation);
                }
            }
        }
        if (method.invisibleAnnotations != null) {
            for (AnnotationNode annotation
                    : method.invisibleAnnotations) {
                if (descriptor.equals(annotation.desc)) {
                    result.add(annotation);
                }
            }
        }
        return result;
    }

    private static boolean isHeadAt(AnnotationNode inject) {
        AnnotationNode at = annotationValue(inject, "at");
        if (at == null) {
            return true;
        }
        String value = stringValue(at, "value");
        return value == null || value.equals("HEAD");
    }

    private static Object value(
            AnnotationNode annotation,
            String key
    ) {
        if (annotation.values == null) {
            return null;
        }
        for (int index = 0;
             index + 1 < annotation.values.size();
             index += 2) {
            if (key.equals(annotation.values.get(index))) {
                return annotation.values.get(index + 1);
            }
        }
        return null;
    }

    private static String stringValue(
            AnnotationNode annotation,
            String key
    ) {
        Object value = value(annotation, key);
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof List<?> list
                && list.size() == 1
                && list.get(0) instanceof String text) {
            return text;
        }
        return null;
    }

    private static AnnotationNode annotationValue(
            AnnotationNode annotation,
            String key
    ) {
        Object value = value(annotation, key);
        if (value instanceof AnnotationNode node) {
            return node;
        }
        if (value instanceof List<?> list
                && list.size() == 1
                && list.get(0) instanceof AnnotationNode node) {
            return node;
        }
        return null;
    }

    private static Integer integerValue(
            AnnotationNode annotation,
            String key
    ) {
        Object value = value(annotation, key);
        return value instanceof Integer number ? number : null;
    }

    private static void assertEqualsValue(
            Object expected,
            Object actual
    ) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(
                    "expected " + expected + " but was " + actual
            );
        }
    }

}
