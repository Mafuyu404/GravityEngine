package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.integration.EntityMovementIntegration;
import cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator;
import cc.sighs.gravityengine.gravity.movement.MovementExecutionPlan;
import cc.sighs.gravityengine.gravity.geometry.BodyRepresentation;
import cc.sighs.gravityengine.gravity.integration.geometry.GravityApplicationBarrier;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.model.GravityApplicationPlan;
import cc.sighs.gravityengine.gravity.model.GravityCollisionRoute;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import cc.sighs.gravityengine.gravity.runtime.GravityOperationState;
import cc.sighs.gravityengine.network.GravitySyncService;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real local-client + integrated-server movement-ownership regression.
 *
 * <p>The observed runtime failure was local-client prediction under a tilted
 * custom reference while the client's locomotion mode was
 * {@code NATIVE_FALLBACK} and an exact GravityEngine body was still installed.
 * A mode-based client skipped its GE collision transaction and collided with
 * the platform AABB, the integrated server clipped the same physical move
 * against the installed exact body, and the disagreement surfaced as repeated
 * position corrections and a two-tick anchor oscillation with visible camera
 * flicker.</p>
 *
 * <p>The legal state is: locomotion is Vanilla-owned, collision is
 * engine-owned, and the exact body stays installed until a real geometry
 * handoff commits {@code NATIVE_AABB}. {@code NATIVE_FALLBACK} never means
 * Vanilla collision ownership.</p>
 *
 * <p>The fixture holds {@link GravityApplicationBarrier} on the server for the
 * observation window. That is the supported engine representation of "the
 * receiver could not install the authoritative snapshot yet", so the deferred
 * {@code EXACT_BODY -> NATIVE_AABB} window stays open and deterministic instead
 * of racing the next connection tick.</p>
 *
 * <p>Only present in the opt-in test mod, and only on the client.</p>
 */
final class ClientMovementOwnershipChecks {
    private static final Vec3 TILTED_DOWN =
            new Vec3(-0.0226D, -0.98D, -0.2D).normalize();
    /** Teleport, geometry install, support settle and abilities sync. */
    private static final int SETTLE_TICKS = 60;
    /** Requirement: observe at least ~100 client ticks. */
    private static final int WINDOW_TICKS = 100;
    private static final int FALLBACK_TICKS = 10;
    private static final int CENTER_X = 96;
    private static final double CENTER_Z = 8.5D;
    /** Corridor wall plane the exact lateral bound is measured against. */
    private static final double POSITIVE_WALL = CENTER_Z + 0.5D;

    private static final AtomicInteger corrections = new AtomicInteger();
    private static final Vec3[] window = new Vec3[WINDOW_TICKS];

    private static int phase;
    private static int ticks;
    private static int observed;
    private static int baselineCorrections;
    private static Vec3 windowStart;
    private static long shapeRevision;
    private static CompletableFuture<Void> setup;
    private static CompletableFuture<ServerState> serverRead;
    private static double exactClip;
    private static boolean completed;

    private record ServerState(
            Vec3 position,
            Vec3d down,
            BodyRepresentation representation,
            GravityOperationState.MovementMode mode,
            GravityCollisionRoute route,
            boolean operationRequired
    ) {}

    private ClientMovementOwnershipChecks() {}

    static boolean tick(Minecraft mc) {
        if (completed) {
            return true;
        }
        if (setup == null) {
            installCorrectionCounter(mc);
            UUID id = mc.player.getUUID();
            setup = CompletableFuture.runAsync(
                    () -> buildFixture(mc, id),
                    mc.getSingleplayerServer()
            );
            return false;
        }
        if (!setup.isDone()) {
            return false;
        }
        setup.join();
        var player = mc.player;
        var runtime = GravityEntityAccess.cast(player)
                .gravityengine$gravityComponent().operationState();

        switch (phase) {
            case 0 -> {
                if (++ticks < SETTLE_TICKS) {
                    return false;
                }
                /*
                 * The tilted reference and its exact body must already be
                 * installed before the fallback is requested: that is the
                 * physical state a native handoff would have to drop.
                 */
                check(EntityMovementIntegration.installedRepresentation(player)
                                == BodyRepresentation.EXACT_BODY,
                        "client installed the exact body before the fallback");
                Vec3d installedDown =
                        GravityFrameAccess.authoritativeFrame(player).down();
                check(installedDown.distance(
                                MinecraftMathAdapter.toVec3d(TILTED_DOWN))
                                < 1.0e-10D,
                        "client installed the tilted reference actual="
                                + installedDown);
                UUID id = player.getUUID();
                setup = CompletableFuture.runAsync(
                        () -> requestNativeFallback(mc, id),
                        mc.getSingleplayerServer()
                );
                phase = 1;
                ticks = 0;
                return false;
            }
            case 1 -> {
                if (++ticks < FALLBACK_TICKS) {
                    return false;
                }
                baselineCorrections = corrections.get();
                windowStart = player.position();
                shapeRevision = runtime.bodyShapeRevision();
                phase = 2;
                ticks = 0;
                return false;
            }
            case 2 -> {
                observe(player, runtime);
                if (observed < WINDOW_TICKS) {
                    return false;
                }
                checkNoAlternatingAnchors();
                UUID id = player.getUUID();
                serverRead = CompletableFuture.supplyAsync(
                        () -> serverState(mc, id),
                        mc.getSingleplayerServer()
                );
                phase = 3;
                return false;
            }
            case 3 -> {
                if (!serverRead.isDone()) {
                    return false;
                }
                ServerState server = serverRead.join();
                verifyServerAgreement(player, server);
                exactClip = measureExactLateralClip(player);
                UUID id = player.getUUID();
                setup = CompletableFuture.runAsync(
                        () -> restore(mc, id),
                        mc.getSingleplayerServer()
                );
                phase = 4;
                return false;
            }
            default -> {
                completed = true;
                System.out.println(
                        "CLIENT_MOVEMENT_OWNERSHIP_PASSED windowTicks="
                                + WINDOW_TICKS
                                + " corrections="
                                + (corrections.get() - baselineCorrections)
                                + " anchors=1 exactLateralClip=" + exactClip
                );
                return true;
            }
        }
    }

    /**
     * One observed tick of the legal {@code NATIVE_FALLBACK + EXACT_BODY}
     * state. No input is pressed, so the client must not translate, must not
     * alternate between two anchors and must not disagree with the integrated
     * server about the committed collider.
     */
    private static void observe(
            net.minecraft.client.player.LocalPlayer player,
            GravityOperationState runtime
    ) {
        MovementExecutionPlan plan = EntityMovementIntegration.inspectMovement(player);
        check(plan.movementMode()
                        == GravityOperationState.MovementMode.NATIVE_FALLBACK,
                "client locomotion mode is NATIVE_FALLBACK actual="
                        + plan.movementMode());
        check(plan.installedRepresentation()
                        == BodyRepresentation.EXACT_BODY,
                "client keeps the installed exact body");
        check(plan.operationRequired(),
                "an installed exact body always requires the GE operation");
        check(EntityMovementIntegration.selectCollisionRouteForOperation(player)
                        == GravityCollisionRoute.EXACT_BODY,
                "committed collision route stays engine-owned");
        check(GravityInfluencePolicy.committedPlan(player).kind()
                        == GravityApplicationPlan.Kind.VANILLA,
                "locomotion fallback is the committed Vanilla application plan");
        check(GravityInfluencePolicy.usesCustomBody(player),
                "the exact body is still physically installed");
        check(runtime.bodyShapeRevision() == shapeRevision,
                "no collider churn during the fallback window");
        check(EntityMovementIntegration.activeMovementCollisionRoute(player)
                        == null,
                "no frozen route may be published outside a move");
        check(EntityMovementIntegration.selectCollisionRouteForOperation(player)
                        != GravityCollisionRoute.VANILLA,
                "an installed exact body never resolves to Vanilla ownership");

        Vec3 position = player.position();
        check(position.distanceTo(windowStart) < 1.0e-6D,
                "no unexplained translation under zero input actual="
                        + position + " start=" + windowStart);
        if (observed > 0) {
            check(position.distanceTo(window[observed - 1]) < 1.0e-6D,
                    "client position must not step per tick actual=" + position);
        }
        window[observed++] = position;
        check(corrections.get() == baselineCorrections,
                "no collision-route disagreement correction actual="
                        + (corrections.get() - baselineCorrections));
    }

    /**
     * The previous two-tick oscillation flipped the client between two
     * distinct anchors. A legal state may translate, but it must not alternate.
     */
    private static void checkNoAlternatingAnchors() {
        int alternations = 0;
        for (int i = 0; i + 2 < observed; i++) {
            boolean stepped = window[i].distanceTo(window[i + 1]) > 1.0e-9D;
            boolean returned = window[i].distanceTo(window[i + 2]) < 1.0e-9D;
            if (stepped && returned) {
                alternations++;
            }
        }
        check(alternations < 6,
                "client must not alternate between two anchors, observations="
                        + alternations);
    }

    private static void verifyServerAgreement(
            net.minecraft.client.player.LocalPlayer player,
            ServerState server
    ) {
        check(server.mode() == GravityOperationState.MovementMode.NATIVE_FALLBACK,
                "server locomotion mode is NATIVE_FALLBACK actual="
                        + server.mode());
        check(server.representation() == BodyRepresentation.EXACT_BODY,
                "server keeps the installed exact body actual="
                        + server.representation());
        check(server.operationRequired(),
                "server keeps the GE operation mandatory");
        check(server.route() == GravityCollisionRoute.EXACT_BODY,
                "server resolves engine-owned exact collision actual="
                        + server.route());
        check(server.position().distanceTo(player.position()) < 1.0e-7D,
                "client/server position agreement client=" + player.position()
                        + " server=" + server.position());
        check(server.down().distance(MinecraftMathAdapter.toVec3d(TILTED_DOWN))
                        < 1.0e-10D,
                "client/server collision axis agreement actual=" + server.down());
    }

    /**
     * Behavioural proof that the client's physical move is clipped by the
     * installed exact body instead of executing through the platform AABB.
     *
     * <p>The fixture corridor is one block wide in Z, so the tilted exact
     * capsule has a strictly smaller lateral gap than the 0.6-wide platform
     * AABB. The bound is computed from the installed capsule itself, and the
     * platform-AABB allowance is asserted to be strictly larger, so a client
     * that fell back to Vanilla AABB collision cannot satisfy the bound.</p>
     */
    private static double measureExactLateralClip(
            net.minecraft.client.player.LocalPlayer player
    ) {
        var frame = GravityFrameAccess.authoritativeFrame(player);
        var body = GravityEntityGeometry.exactBody(player);
        double bodyExtentZ = body.enclosingAabb().maxZ()
                - MinecraftMathAdapter.toVec3d(player.position()).z();
        double exactAllowance = POSITIVE_WALL
                - (player.position().z() + bodyExtentZ);
        double platformAllowance = POSITIVE_WALL
                - (player.position().z() + player.getBbWidth() * 0.5D);
        check(exactAllowance > 0.0D,
                "the tilted exact body leaves a bounded lateral gap actual="
                        + exactAllowance);
        check(platformAllowance > exactAllowance + 0.05D,
                "fixture discriminates the exact collider from the platform "
                        + "AABB exact=" + exactAllowance + " platform="
                        + platformAllowance);

        Vec3 before = player.position();
        double clip;
        try {
            /*
             * NATIVE_FALLBACK locomotion must still execute through the
             * engine-owned collision route. A missing operation would throw
             * "No active GravityFrame" here instead of silently clipping.
             */
            player.move(MoverType.SELF, new Vec3(0.0D, 0.0D, 0.3D));
            clip = player.position().distanceTo(before);
        } catch (IllegalStateException failure) {
            throw new AssertionError(
                    "client NATIVE_FALLBACK move must own an active "
                            + "GravityFrame: " + failure,
                    failure
            );
        } finally {
            player.setPos(before);
        }
        check(Math.abs(clip - exactAllowance) < 0.03D,
                "client must clip the move against the installed exact body "
                        + "actual=" + clip + " expected=" + exactAllowance
                        + " platformAabb=" + platformAllowance);
        check(clip < platformAllowance - 0.05D,
                "client must not use platform AABB collision ownership actual="
                        + clip);
        return clip;
    }

    /** Real one-shot fixture: tilted reference, exact body, tight corridor. */
    private static void buildFixture(Minecraft mc, UUID id) {
        ServerPlayer player = mc.getSingleplayerServer().getPlayerList()
                .getPlayer(id);
        var level = player.serverLevel();
        for (int x = -2; x <= 3; x++) {
            for (int z = -2; z <= 3; z++) {
                level.setBlock(
                        new BlockPos(CENTER_X + x, 299, (int) CENTER_Z + z),
                        Blocks.STONE.defaultBlockState(),
                        3
                );
                level.setBlock(
                        new BlockPos(CENTER_X + x, 302, (int) CENTER_Z + z),
                        Blocks.STONE.defaultBlockState(),
                        3
                );
            }
            for (int y = 300; y <= 301; y++) {
                /*
                 * One-block-wide corridor in Z: the free gap is exactly
                 * [CENTER_Z - 0.5, CENTER_Z + 0.5].
                 */
                level.setBlock(
                        new BlockPos(
                                CENTER_X + x,
                                y,
                                (int) CENTER_Z - 1
                        ),
                        Blocks.STONE.defaultBlockState(),
                        3
                );
                level.setBlock(
                        new BlockPos(
                                CENTER_X + x,
                                y,
                                (int) CENTER_Z + 1
                        ),
                        Blocks.STONE.defaultBlockState(),
                        3
                );
            }
        }
        for (int y = 303; y <= 309; y++) {
            for (int x = -2; x <= 3; x++) {
                for (int z = -2; z <= 3; z++) {
                    level.setBlock(
                            new BlockPos(
                                    CENTER_X + x,
                                    y,
                                    (int) CENTER_Z + z
                            ),
                            Blocks.AIR.defaultBlockState(),
                            3
                    );
                }
            }
        }
        player.setDeltaMovement(Vec3.ZERO);
        player.connection.teleport(CENTER_X + 0.5D, 300.0D, CENTER_Z, 0, 0);
        GravityApplicationCoordinator.applyDirectAssignment(
                player,
                new GravityState(
                        MinecraftMathAdapter.toVec3d(TILTED_DOWN),
                        0.08D
                )
        );
        GravitySyncService.syncPlayer(player);
        player.connection.resumeFlushing();
    }

    /**
     * Requests the native fallback and freezes the body/application handoff,
     * so the deferred {@code EXACT_BODY -> NATIVE_AABB} window stays open for
     * the whole observation.
     */
    private static void requestNativeFallback(Minecraft mc, UUID id) {
        ServerPlayer player = mc.getSingleplayerServer().getPlayerList()
                .getPlayer(id);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        GravitySyncService.syncPlayer(player);
        player.connection.resumeFlushing();
        if (heldBarrier != null) {
            heldBarrier.close();
        }
        heldBarrier = GravityApplicationBarrier.hold(player);
    }

    private static GravityApplicationBarrier.Scope heldBarrier;

    private static ServerState serverState(Minecraft mc, UUID id) {
        ServerPlayer player = mc.getSingleplayerServer().getPlayerList()
                .getPlayer(id);
        var runtime = GravityEntityAccess.cast(player)
                .gravityengine$gravityComponent().operationState();
        return new ServerState(
                player.position(),
                GravityFrameAccess.authoritativeFrame(player).down(),
                EntityMovementIntegration.installedRepresentation(player),
                runtime.movementMode(),
                EntityMovementIntegration.selectCollisionRouteForOperation(player),
                EntityMovementIntegration.inspectMovement(player).operationRequired()
        );
    }

    private static void restore(Minecraft mc, UUID id) {
        ServerPlayer player = mc.getSingleplayerServer().getPlayerList()
                .getPlayer(id);
        if (heldBarrier != null) {
            heldBarrier.close();
            heldBarrier = null;
        }
        player.getAbilities().flying = false;
        player.onUpdateAbilities();
        player.setDeltaMovement(Vec3.ZERO);
        player.connection.teleport(8.5D, 300.0D, 8.5D, 0, 0);
        GravityApplicationCoordinator.applyDirectAssignment(
                player,
                GravityState.DEFAULT
        );
        GravitySyncService.syncPlayer(player);
        player.connection.resumeFlushing();
    }

    private static void installCorrectionCounter(Minecraft mc) {
        mc.player.connection.getConnection().channel().pipeline()
                .addBefore(
                        "packet_handler",
                        "movement_ownership_corrections",
                        new ChannelDuplexHandler() {
                            @Override
                            public void channelRead(
                                    ChannelHandlerContext context,
                                    Object message
                            ) throws Exception {
                                if (message
                                        instanceof ClientboundPlayerPositionPacket) {
                                    corrections.incrementAndGet();
                                }
                                super.channelRead(context, message);
                            }
                        }
                );
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("client movement ownership: " + message);
        }
    }
}
