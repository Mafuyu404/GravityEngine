package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.api.GravityEngineApi;
import cc.sighs.gravityengine.api.GravityFieldDefinition;
import cc.sighs.gravityengine.api.field.GravityFieldCompositionMode;
import cc.sighs.gravityengine.api.field.GravityFields;
import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.geometry.BodyRepresentation;
import cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.model.GravityAuthorityMode;
import cc.sighs.gravityengine.network.ClientboundPlayerBodyCommitPayload;
import cc.sighs.gravityengine.network.ServerboundPlayerMovePayload;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real client collision/prediction across alternating FIELD domains with delayed movement packets. */
final class ClientJumpAcceptanceChecks {
    private static final AtomicInteger corrections = new AtomicInteger(), delayed = new AtomicInteger();
    private static CompletableFuture<Void> setup;
    private static Runnable closePublication;
    private static volatile boolean latency;
    private static boolean complete, rising;
    private static int ticks, baselineCorrections, jumps, transitions;
    private static BodyRepresentation previousRepresentation;
    private static Vec3 start;

    static boolean tick(Minecraft mc) {
        if (complete) return true;
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyShift.setDown(false);
        mc.options.keySprint.setDown(false);
        if (setup == null) {
            mc.player.connection.getConnection().channel().pipeline().addBefore("packet_handler", "jump_acceptance",
                    new ChannelDuplexHandler() {
                        @Override public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
                            if (message instanceof ClientboundPlayerPositionPacket
                                    || message instanceof ClientboundCustomPayloadPacket custom
                                    && custom.payload() instanceof ClientboundPlayerBodyCommitPayload body
                                    && body.correction() != null) corrections.incrementAndGet();
                            super.channelRead(context, message);
                        }
                        @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                            if (latency && message instanceof ServerboundCustomPayloadPacket custom
                                    && custom.payload() instanceof ServerboundPlayerMovePayload) {
                                delayed.incrementAndGet();
                                context.executor().schedule(() -> context.writeAndFlush(message, promise), 100, TimeUnit.MILLISECONDS);
                            } else super.write(context, message, promise);
                        }
                    });
            var id = mc.player.getUUID();
            setup = CompletableFuture.runAsync(() -> {
                var actor = mc.getSingleplayerServer().getPlayerList().getPlayer(id);
                var level = actor.serverLevel();
                for (int x = 520; x <= 600; x++) for (int z = -24; z <= 40; z++)
                    level.setBlock(new BlockPos(x, 299, z), Blocks.STONE.defaultBlockState(), 3);
                for (int x : new int[] { 548, 574 }) for (int z = -24; z <= 40; z++) for (int y = 300; y <= 304; y++)
                    level.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), 3);
                var lease = GravityEngineApi.publish(level, com.example.examplemod.gravity.ProviderFixture.ID,
                        GravityFieldDefinition.block(ResourceLocation.parse("gravityengine:jump_acceptance"), BlockPos.ZERO,
                                query -> new cc.sighs.gravityengine.api.field.GravityFieldSample(
                                        (((int) Math.floor(query.position().x() / 2)) & 1) == 0
                                        ? new Vec3d(0, -.0784, -.016) : new Vec3d(0, -.08, 0)),
                                GravityFields.sphereInfluence(new Vec3d(560, 300, 8), 60), GravityFieldCompositionMode.OVERRIDE, 1));
                closePublication = lease::close;
                actor.getAbilities().flying = false;
                actor.getAbilities().mayfly = false;
                actor.onUpdateAbilities();
                GravityEntityAccess.cast(actor).gravityengine$gravityComponent().state()
                        .setAssigned(GravityState.DEFAULT, GravityAuthorityMode.FIELD, false);
                actor.setDeltaMovement(Vec3.ZERO);
                actor.connection.teleport(560.5, 303, 8.5, 0, 0);
                GravityApplicationCoordinator.reconcileServerInfluence(actor);
                actor.connection.resumeFlushing();
            }, mc.getSingleplayerServer());
            return false;
        }
        if (!setup.isDone()) return false;
        setup.join();
        ++ticks;
        mc.player.setYRot(0);
        mc.player.setXRot(0);
        if (ticks < 80) return false;
        var runtime = GravityEntityAccess.cast(mc.player).gravityengine$gravityComponent().operationState();
        var representation = BodyRepresentation.ofAxis(runtime.installedCollisionUp());
        if (ticks == 80) {
            check(mc.player.onGround(), "settled before repeated jumps");
            start = mc.player.position();
            baselineCorrections = corrections.get();
            previousRepresentation = representation;
            latency = true;
        }
        check(mc.player.getBoundingBox().minX >= 549 - 1e-6 && mc.player.getBoundingBox().maxX <= 574 + 1e-6,
                "ordinary client wall collision remains active");
        if (representation != previousRepresentation) transitions++;
        previousRepresentation = representation;
        boolean nowRising = mc.player.getDeltaMovement().y > .1;
        if (nowRising && !rising) jumps++;
        rising = nowRising;
        mc.options.keyLeft.setDown(ticks < 200);
        mc.options.keyJump.setDown(ticks < 200);
        if (ticks < 220) return false;
        check(jumps >= 3, "multiple client jumps: " + jumps);
        check(transitions >= 3, "crossed exact/native FIELD domains: " + transitions);
        check(mc.player.position().distanceTo(start) > 5, "client traversed field boundaries");
        check(delayed.get() >= 30, "GE movement envelopes actually delayed");
        check(corrections.get() == baselineCorrections, "physical corrections=" + (corrections.get() - baselineCorrections));
        latency = false;
        mc.player.connection.getConnection().channel().pipeline().remove("jump_acceptance");
        mc.getSingleplayerServer().execute(closePublication);
        complete = true;
        System.out.println("CLIENT_JUMP_ACCEPTANCE_PASSED jumps=" + jumps + " transitions=" + transitions
                + " delayedPackets=" + delayed.get() + " physicalCorrections=0 visualSmoothness=not-measured");
        return true;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("jump acceptance tick=" + ticks + " " + message);
    }
}
