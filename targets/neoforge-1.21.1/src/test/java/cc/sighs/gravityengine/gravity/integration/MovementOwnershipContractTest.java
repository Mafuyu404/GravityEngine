package cc.sighs.gravityengine.gravity.integration;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Target seams must consume the collision route owned by the outer move. */
class MovementOwnershipContractTest {
    /**
     * Bytecode contract: inner Vanilla seams consume the frozen route and
     * never recompute collision ownership from live policy.
     */
    @Test
    void innerMovementSeamsNeverSelectCollisionOwnershipLive() {
        ClassNode integration = read(
                "gravity.integration.EntityMovementIntegration"
        );
        for (MethodNode method : integration.methods) {
            for (var instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) {
                    continue;
                }
                if (call.owner.endsWith("/GravityInfluencePolicy")
                        && call.name.equals("collisionRoute")) {
                    assertTrue(
                            Set.of(
                                    "selectCollisionRouteForOperation",
                                    "committedCollisionRoute"
                            ).contains(method.name),
                            "live collision-route selection escaped the outer "
                                    + "boundary: " + method.name
                    );
                }
                assertFalse(
                        call.name.equals("movementRoute"),
                        "the combined frozen/live route helper must not exist "
                                + "again: " + method.name
                );
            }
        }

        MethodNode activeRoute = method(
                integration,
                "activeMovementCollisionRoute"
        );
        assertTrue(
                callsTo(activeRoute, "movementCollisionRoute") == 1,
                "the inner-seam route query must read the frozen route"
        );

        for (String owner : List.of(
                "mixin.EntityMixin",
                "mixin.PlayerMixin",
                "gravity.integration.ContactVelocityIntegration",
                "gravity.integration.compat.sable.SableCollisionOwnership",
                "mixin.EntityMovementDebugMixin"
        )) {
            ClassNode node = read(owner);
            for (MethodNode method : node.methods) {
                for (var instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call) {
                        assertFalse(
                                call.name.equals("collisionRoute")
                                        || call.name.equals("movementRoute"),
                                owner + "." + method.name
                                        + " recomputed collision ownership"
                        );
                    }
                }
            }
        }

        assertTrue(
                callsTo(
                        method(
                                read("mixin.EntityMixin"),
                                "gravityengine$collide"
                        ),
                        "activeMovementCollisionRoute"
                ) == 1,
                "Entity.collide must consume the frozen movement route"
        );
        assertTrue(
                callsTo(
                        method(
                                read("mixin.PlayerMixin"),
                                "gravityengine$customGravityOwnsSneakEdge"
                        ),
                        "activeMovementCollisionRoute"
                ) == 1,
                "maybeBackOffFromEdge must consume the frozen movement route"
        );
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream()
                .filter(candidate -> candidate.name.equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        node.name + " has no " + name
                ));
    }

    private static long callsTo(MethodNode method, String name) {
        long count = 0L;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.name.equals(name)) {
                count++;
            }
        }
        return count;
    }

    private static ClassNode read(String name) {
        String resource = (
                "cc.sighs.gravityengine." + name
        ).replace('.', '/') + ".class";
        try (var stream = MovementOwnershipContractTest.class
                .getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(stream, resource);
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            return node;
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }
}
