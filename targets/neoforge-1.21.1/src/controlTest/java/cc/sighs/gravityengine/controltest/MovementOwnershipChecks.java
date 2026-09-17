package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.integration.EntityMovementIntegration;
import cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator;
import cc.sighs.gravityengine.gravity.movement.MovementExecutionPlan;
import cc.sighs.gravityengine.gravity.geometry.BodyRepresentation;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.model.GravityCollisionRoute;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Real-entity movement-ownership matrix.
 *
 * <p>A physical {@code Entity.move} selects collision ownership once at the
 * outer boundary. {@code MovementMode.NATIVE_FALLBACK} is locomotion policy:
 * while an exact GE body is installed, the move still runs a GE operation with
 * a frozen {@code EXACT_BODY} route.</p>
 */
final class MovementOwnershipChecks {
    private static final Vec3d TILTED_DOWN =
            new Vec3d(0.0D, -1.0D, 0.01D);

    /**
     * Deferred-handoff fixture anchor. The floor top is
     * {@code anchor.y + 0.6}, exactly the lowest point of a lying 1.8-tall
     * capsule whose body center stays on world-up {@code anchor.y + 0.9}, so
     * the installed exact body rests on the floor while the proposed
     * default-axis collider overlaps that same floor at the anchor.
     */
    private static final Vec3 DEFERRED_ORIGIN =
            new Vec3(40.5D, 299.4D, 40.5D);

    private static int assertions;

    private MovementOwnershipChecks() {}

    static void run(ServerLevel level) {
        nativeGroundAir(level);
        nativeFallback(level);
        exactBodyGroundAir(level);
        exactBodyNativeFallback(level);
        handoffRetainsExactCollisionUntilCommit(level);
        deferredNativeHandoffKeepsExactCollision(level);
        System.out.println(
                "MOVEMENT_OWNERSHIP_CHECKS_PASSED state-matrix handoff assertions="
                        + assertions
        );
    }

    /** A: normal locomotion + native representation -> no GE operation. */
    private static void nativeGroundAir(ServerLevel level) {
        var actor = actor(level, "MovementOwnerNativeGroundAir");
        actor.getAbilities().flying = false;
        move(actor);
        check(actor.innerSeams == 1, "case A inner seam ran");
        check(actor.observedRoute == null,
                "normal + native has no frozen GE route");
        check(!actor.observedInMove,
                "normal + native does not open a GE operation");
        check(actor.observedMode == GravityOperationState.MovementMode.GROUND_AIR,
                "case A keeps GROUND_AIR locomotion");
        actor.discard();
    }

    /** C: native fallback + native representation -> pure Vanilla. */
    private static void nativeFallback(ServerLevel level) {
        var actor = actor(level, "MovementOwnerNativeFallback");
        actor.getAbilities().flying = true;
        move(actor);
        check(actor.innerSeams == 1, "case C inner seam ran");
        check(actor.observedRoute == null,
                "native fallback over a native representation stays Vanilla");
        check(!actor.observedInMove,
                "native fallback over a native representation opens no "
                        + "operation");
        check(actor.observedMode
                        == GravityOperationState.MovementMode.NATIVE_FALLBACK,
                "case C keeps NATIVE_FALLBACK locomotion");
        actor.discard();
    }

    /** B: normal locomotion + installed exact body -> GE EXACT_BODY. */
    private static void exactBodyGroundAir(ServerLevel level) {
        var actor = actor(level, "MovementOwnerExactGroundAir");
        assign(actor, TILTED_DOWN);
        actor.getAbilities().flying = false;
        check(GravityInfluencePolicy.usesCustomBody(actor),
                "case B installs an exact body");
        move(actor);
        check(actor.innerSeams == 1, "case B inner seam ran");
        check(actor.observedRoute == GravityCollisionRoute.EXACT_BODY,
                "normal + exact body freezes EXACT_BODY");
        check(actor.observedInMove
                        && actor.observedFrame
                        && actor.observedCollisionOperation,
                "normal + exact body owns a frame and collision scene");
        actor.discard();
    }

    /**
     * D: Vanilla locomotion semantics + installed exact body.
     *
     * <p>The observed regression: this state must not be reinterpreted as
     * Vanilla AABB collision merely because locomotion fell back.</p>
     */
    private static void exactBodyNativeFallback(ServerLevel level) {
        var actor = actor(level, "MovementOwnerExactFallback");
        assign(actor, TILTED_DOWN);
        check(GravityInfluencePolicy.usesCustomBody(actor),
                "case D installs an exact body before fallback");
        actor.getAbilities().flying = true;
        var plan = EntityMovementIntegration.inspectMovement(actor);
        check(plan.installedRepresentation() == BodyRepresentation.EXACT_BODY
                        && plan.operationRequired(),
                "case D inspection is physically engine-owned");
        move(actor);
        check(actor.innerSeams == 1, "case D inner seam ran");
        check(actor.observedMode
                        == GravityOperationState.MovementMode.NATIVE_FALLBACK,
                "case D locomotion falls back to Vanilla");
        check(actor.observedRoute == GravityCollisionRoute.EXACT_BODY,
                "case D collision stays EXACT_BODY");
        check(actor.observedRepresentation == BodyRepresentation.EXACT_BODY,
                "case D execution representation is the installed exact body");
        check(actor.observedInMove
                        && actor.observedFrame
                        && actor.observedCollisionOperation,
                "case D owns an operation frame and collision scene");
        check(GravityInfluencePolicy.usesCustomBody(actor),
                "case D keeps the installed exact body");
        check(EntityMovementIntegration.inspectMovement(actor).operationRequired(),
                "case D keeps the pure Vanilla fast path forbidden");
        check(EntityMovementIntegration.selectCollisionRouteForOperation(actor)
                        == GravityCollisionRoute.EXACT_BODY,
                "case D resolves its route to engine-owned exact collision");
        // A second consecutive physical move keeps the same coherent decision.
        move(actor);
        check(actor.observedRoute == GravityCollisionRoute.EXACT_BODY
                        && actor.observedRepresentation
                        == BodyRepresentation.EXACT_BODY
                        && actor.observedMode
                        == GravityOperationState.MovementMode.NATIVE_FALLBACK,
                "case D stays coherent across consecutive moves");
        actor.discard();
    }

    /**
     * The deferred native handoff keeps exact collision until the native
     * representation has actually committed; once it has, collision ownership
     * returns to Vanilla.
     */
    private static void handoffRetainsExactCollisionUntilCommit(ServerLevel level) {
        var actor = actor(level, "MovementOwnerHandoff");
        assign(actor, TILTED_DOWN);
        check(GravityInfluencePolicy.usesCustomBody(actor),
                "handoff starts from an installed exact body");

        // A native-equivalent reference is requested but the client/server
        // representation commit has not happened yet.
        assign(actor, new Vec3d(0.0D, -1.0D, 0.0D));
        check(GravityInfluencePolicy.usesCustomBody(actor),
                "a requested handoff is not an installed representation");

        MovementExecutionPlan prep = EntityMovementIntegration.inspectMovement(actor);
        check(prep.installedRepresentation() == BodyRepresentation.EXACT_BODY
                        && prep.operationRequired(),
                "preparation starts from an installed exact body");

        move(actor);
        check(actor.observedInMove && actor.observedCollisionOperation,
                "the handoff move still runs the GE operation");
        check(actor.observedRoute == GravityCollisionRoute.EXACT_BODY
                        && actor.observedRepresentation == BodyRepresentation.EXACT_BODY,
                "movement retains the installed exact body before the server handoff");
        cc.sighs.gravityengine.gravity.integration.geometry.PlayerBodyHandoff.afterConnectionTick(actor, false);
        check(!GravityInfluencePolicy.usesCustomBody(actor),
                "the native representation is installed after the handoff");
        check(EntityMovementIntegration.inspectMovement(actor).installedRepresentation()
                        == BodyRepresentation.NATIVE_AABB,
                "the committed representation is the platform AABB");
        check(EntityMovementIntegration.selectCollisionRouteForOperation(actor)
                        == GravityCollisionRoute.VANILLA,
                "the committed native representation owns Vanilla collision");

        /*
         * The mixed snapshot (pre-preparation representation + post-preparation
         * route) is exactly what the outer boundary must reject, so this case
         * documents that the coherence validation is load-bearing.
         */
        checkThrows(
                () -> prep.resolveExecution(
                        BodyRepresentation.EXACT_BODY,
                        GravityCollisionRoute.VANILLA
                ),
                "the mixed pre/post-preparation snapshot is rejected"
        );

        // A second physical move resolves against the committed native body.
        move(actor);
        check(actor.observedRoute == GravityCollisionRoute.VANILLA,
                "post-commit moves use Vanilla collision");

        /*
         * Once no committed application or external provider requires the
         * engine operation, the committed native representation makes the
         * pure Vanilla fast path legal.
         */
        actor.getAbilities().flying = true; // controlled flight commits Vanilla
        updateBody(actor);
        check(!GravityInfluencePolicy.committedPlan(actor)
                        .needsGravityOperation(),
                "the fixture commits a Vanilla application");
        check(!EntityMovementIntegration.inspectMovement(actor).operationRequired(),
                "a committed native representation permits the Vanilla fast path");
        move(actor);
        check(actor.observedRoute == null,
                "the pure Vanilla fast path opens no GE movement transaction");
        actor.discard();
    }

    /**
     * The regression boundary itself: a requested native handoff that cannot
     * legally commit. The installed exact body remains the collider, so the
     * frozen movement route stays engine-owned, the operation stays mandatory
     * and neither side may fall back to Vanilla AABB collision.
     */
    private static void deferredNativeHandoffKeepsExactCollision(
            ServerLevel level
    ) {
        var actor = actor(level, "MovementOwnerDeferredHandoff");
        var placed = new ArrayList<BlockPos>();
        try {
            clearFootprint(level, DEFERRED_ORIGIN);
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    place(
                            level,
                            placed,
                            BlockPos.containing(
                                    DEFERRED_ORIGIN.x + x,
                                    DEFERRED_ORIGIN.y - 0.4D,
                                    DEFERRED_ORIGIN.z + z
                            )
                    );
                }
            }

            actor.setPos(DEFERRED_ORIGIN);
            actor.setDeltaMovement(Vec3.ZERO);
            assign(actor, Vec3d.X);
            check(GravityInfluencePolicy.usesCustomBody(actor),
                    "deferred handoff installs the lying exact body");
            check(EntityMovementIntegration.installedRepresentation(actor)
                            == BodyRepresentation.EXACT_BODY,
                    "deferred handoff fixture installs an exact representation");

            // The requested native reference cannot be installed at this
            // anchor: the platform AABB overlaps the support the exact body
            // rests on, and no bounded recovery position is legal either.
            assign(actor, new Vec3d(0.0D, -1.0D, 0.0D));
            check(GravityInfluencePolicy.usesCustomBody(actor),
                    "the requested native handoff did not commit");
            actor.getAbilities().flying = true;

            for (int moveIndex = 0; moveIndex < 4; moveIndex++) {
                double beforeY = actor.getY();
                moveAt(
                        actor,
                        DEFERRED_ORIGIN.y,
                        MoverType.SELF,
                        new Vec3(0.0D, -0.05D, 0.0D)
                );
                check(actor.innerSeams == 1,
                        "deferred handoff inner seam ran");
                check(actor.observedMode
                                == GravityOperationState.MovementMode
                                .NATIVE_FALLBACK,
                        "deferred handoff keeps Vanilla locomotion semantics");
                check(actor.observedRoute == GravityCollisionRoute.EXACT_BODY,
                        "deferred handoff keeps the frozen route engine-owned");
                check(actor.observedRepresentation
                                == BodyRepresentation.EXACT_BODY,
                        "deferred handoff keeps the execution representation exact");
                check(actor.observedInMove
                                && actor.observedFrame
                                && actor.observedCollisionOperation,
                        "deferred handoff owns a frame and collision scene");
                check(Math.abs(actor.getY() - beforeY) < 1.0e-6D,
                        "the installed exact body clips the gravity-down request");
                check(GravityInfluencePolicy.usesCustomBody(actor),
                        "the deferred handoff keeps the installed exact body");
            }

            MovementExecutionPlan plan = EntityMovementIntegration.inspectMovement(actor);
            check(plan.installedRepresentation()
                            == BodyRepresentation.EXACT_BODY,
                    "deferred handoff never installs the native representation");
            check(plan.operationRequired(),
                    "deferred handoff forbids the pure Vanilla fast path");
            checkThrows(
                    () -> MovementExecutionPlan.requireCoherentExecution(
                            BodyRepresentation.EXACT_BODY,
                            GravityCollisionRoute.VANILLA
                    ),
                    "an installed exact body rejects Vanilla collision ownership"
            );
        } finally {
            for (BlockPos pos : placed) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            }
            actor.discard();
        }
    }

    private static void clearFootprint(ServerLevel level, Vec3 origin) {
        BlockPos center = BlockPos.containing(origin);
        for (int x = -4; x <= 4; x++) {
            for (int y = -2; y <= 3; y++) {
                for (int z = -4; z <= 4; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    private static void place(
            ServerLevel level,
            List<BlockPos> placed,
            BlockPos pos
    ) {
        level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
        placed.add(pos);
    }

    private static void move(Actor actor) {
        moveAt(actor, 300.0D, MoverType.SELF, new Vec3(0.0D, -0.05D, 0.0D));
    }

    private static void moveAt(
            Actor actor,
            double y,
            MoverType moverType,
            Vec3 movement
    ) {
        actor.observedRoute = null;
        actor.observedRepresentation = null;
        actor.observedInMove = false;
        actor.observedFrame = false;
        actor.observedCollisionOperation = false;
        actor.innerSeams = 0;
        actor.setPos(actor.getX(), y, actor.getZ());
        actor.setDeltaMovement(Vec3.ZERO);
        actor.move(moverType, movement);
    }

    private static Actor actor(ServerLevel level, String name) {
        var profile = new GameProfile(UUID.randomUUID(), name);
        var actor = new Actor(level, profile);
        var connection = new Connection(PacketFlow.SERVERBOUND) {
            @Override public void send(Packet<?> packet) {}
            @Override public void send(
                    Packet<?> packet,
                    PacketSendListener listener
            ) {}
        };
        actor.connection = new ServerGamePacketListenerImpl(
                level.getServer(),
                connection,
                actor,
                CommonListenerCookie.createInitial(profile, false)
        ) {
            @Override public void send(Packet<?> packet) { }
        };
        level.addNewPlayer(actor);
        actor.setPos(8.5D, 300.0D, 8.5D);
        return actor;
    }

    private static void assign(Actor actor, Vec3d down) {
        var connection = actor.connection;
        actor.connection = null;
        try {
            GravityApplicationCoordinator.applyDirectAssignment(
                    actor,
                    new GravityState(down.normalized(), 0.08D)
            );
        } finally {
            actor.connection = connection;
        }
    }

    /** Authoritative application re-derivation without the connection gate. */
    private static void updateBody(Actor actor) {
        var connection = actor.connection;
        actor.connection = null;
        try {
            GravityApplicationCoordinator.updateBody(actor);
        } finally {
            actor.connection = connection;
        }
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void checkThrows(Runnable action, String message) {
        assertions++;
        try {
            action.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    /**
     * Records what the real inner {@code Entity.move} seam observed while the
     * move was executing.
     */
    private static final class Actor extends ServerPlayer {
        GravityCollisionRoute observedRoute;
        BodyRepresentation observedRepresentation;
        GravityOperationState.MovementMode observedMode;
        boolean observedInMove;
        boolean observedFrame;
        boolean observedCollisionOperation;
        int innerSeams;

        Actor(ServerLevel level, GameProfile profile) {
            super(
                    level.getServer(),
                    level,
                    profile,
                    net.minecraft.server.level.ClientInformation
                            .createDefault()
            );
        }

        @Override
        protected void checkFallDamage(
                double vertical,
                boolean grounded,
                BlockState state,
                BlockPos pos
        ) {
            var runtime = GravityEntityAccess.cast(this)
                    .gravityengine$gravityComponent()
                    .operationState();
            observedRoute =
                    EntityMovementIntegration.activeMovementCollisionRoute(
                            this
                    );
            observedRepresentation =
                    EntityMovementIntegration.installedRepresentation(this);
            observedMode = runtime.movementMode();
            observedInMove = runtime.isInMove();
            observedFrame = runtime.isInMove()
                    && runtime.activeFrame() != null;
            observedCollisionOperation =
                    runtime.collisionOperation() != null;
            innerSeams++;
            super.checkFallDamage(vertical, grounded, state, pos);
        }
    }
}
