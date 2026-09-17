package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.collision.*;
import cc.sighs.gravityengine.gravity.integration.BlockContactResolver;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.math.geometry.Aabb3d;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Real target behavior tests, invoked by the transformed dedicated-server gate. */
final class ContactBehaviorChecks {
    static final class Actor extends net.minecraft.world.entity.player.Player {
        int mode;
        BlockContactResolver.Resolved landing;
        GravityMoveResult observedMove;
        net.minecraft.world.phys.Vec3 afterCallbacks;
        Actor(ServerLevel level) {
            super(level, BlockPos.ZERO, 0, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "contact-mode"));
        }
        @Override public boolean isSpectator() { return false; }
        @Override public boolean isCreative() { return false; }
        @Override public boolean isControlledByLocalInstance() { return true; }
        @Override public boolean isInWater() { return mode == 1; }
        @Override public boolean onClimbable() { return mode == 2; }
        @Override public boolean isPassenger() { return mode == 3; }
        @Override public boolean isSleeping() { return mode == 4; }
        @Override public boolean isAutoSpinAttack() { return mode == 6; }
        @Override public boolean isFallFlying() { return mode == 7; }
        @Override protected MovementEmission getMovementEmission() {
            var runtime = GravityEntityAccess.cast(this).gravityengine$gravityComponent().operationState();
            if (runtime.currentMoveResult() != null) {
                observedMove = runtime.currentMoveResult();
                landing = BlockContactResolver.landingBlock(this).orElse(null);
                afterCallbacks = getDeltaMovement();
            }
            return super.getMovementEmission();
        }
    }
    static final class RecordingBlock extends Block {
        int falls, responses, steps;
        RecordingBlock() { super(BlockBehaviour.Properties.of().dynamicShape()); }
        void reset() { falls = responses = steps = 0; }
        @Override public void fallOn(Level level, BlockState state, BlockPos pos, Entity actor, float distance) {
            falls++;
            super.fallOn(level, state, pos, actor, distance);
        }
        @Override public void updateEntityAfterFallOn(BlockGetter level, Entity actor) {
            responses++;
            super.updateEntityAfterFallOn(level, actor);
        }
        @Override public void stepOn(Level level, BlockPos pos, BlockState state, Entity actor) { steps++; }
        @Override public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return context.isDescending() ? Shapes.box(0, 0, 0, 1, .5, 1) : Shapes.block();
        }
    }

    static void staticMaterial(ServerLevel level, Entity actor) {
        var pos = new BlockPos(7, 297, 7);
        var old = level.getBlockState(pos);
        try {
            var identity = new SupportFaceIdentity(new CellPos(7, 297, 7), new Aabb3d(7,297,7,8,298,8), 0, 3);
            var contact = new GravitySupportContact(Vec3d.Y, Vec3d.ZERO, new Vec3d(7.5,298,7.5),
                    GravitySupportContact.SupportGeometryKind.TRUSTED_CURRENT_BLOCK_FACE, identity);
            level.setBlock(pos, Blocks.ICE.defaultBlockState(), 18);
            var resolved = BlockContactResolver.resolve(actor, contact).orElseThrow();
            check(resolved.state().is(Blocks.ICE) && resolved.material(actor).friction() == .98F, "static ice material");
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), 18);
            check(BlockContactResolver.resolve(actor, contact).orElseThrow().state().is(Blocks.STONE), "fresh static material");
            level.setBlock(pos, Blocks.STONE_SLAB.defaultBlockState(), 18);
            check(BlockContactResolver.resolve(actor, contact).isEmpty(), "static changed geometry rejects old contact");
        } finally { level.setBlock(pos, old, 18); }
    }

    static GravitySupportContact retainedContact(Entity actor) {
        var support = GravityEntityAccess.cast(actor).gravityengine$gravityComponent().operationState().persistentSupportState();
        if (support == null) throw new AssertionError("fixture must establish support");
        return new GravitySupportContact(support.normal(), Vec3d.ZERO,
                support.obstaclePoseAtCapture().transformPoint(support.localAnchor()), support.geometryKind(), support.identity());
    }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
