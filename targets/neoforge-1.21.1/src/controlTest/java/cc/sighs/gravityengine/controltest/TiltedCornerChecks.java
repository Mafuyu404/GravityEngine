package cc.sighs.gravityengine.controltest;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.collision.CollisionTolerances;
import cc.sighs.gravityengine.gravity.collision.GravityMoveResult;
import cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Real Entity.move/velocity/support commits on 200 distinct dedicated-server ticks. */
final class TiltedCornerChecks implements AutoCloseable {
    private static final Vec3d DOWN = new Vec3d(.5, -.8, Math.sqrt(.11));
    private static final Vec3 REQUEST = new Vec3(.016428, 0, .016186);
    private static final double EPSILON = CollisionTolerances.CONTACT_SKIN;
    private final ServerLevel level;
    private final Map<BlockPos, BlockState> saved = new HashMap<>();
    private final Actor self;
    private final Actor replay;
    private final Vec3 start;
    private int ticks;
    private long previousGameTime = Long.MIN_VALUE;

    TiltedCornerChecks(ServerLevel level) {
        this.level = level;
        for (int x = 6; x <= 14; x++) for (int z = 5; z <= 9; z++) set(new BlockPos(x, 299, z));
        for (int x = 6; x <= 14; x++) for (int y = 300; y <= 302; y++) set(new BlockPos(x, y, 8));
        self = new Actor(level);
        replay = new Actor(level);
        double radius = (double) self.getBbWidth() / 2;
        double halfHeight = (double) self.getBbHeight() / 2;
        double halfSegment = halfHeight - radius;
        start = new Vec3(8, 300 + radius + halfSegment * .8 - halfHeight,
                8 - radius - halfSegment * Math.sqrt(.11) - .0005);
        self.setPos(start);
        replay.setPos(start);
    }

    private void set(BlockPos pos) {
        saved.putIfAbsent(pos, level.getBlockState(pos));
        level.setBlock(pos, Blocks.STONE.defaultBlockState(), 18);
    }

    boolean tick() {
        check(level.getGameTime() != previousGameTime, "distinct server tick");
        previousGameTime = level.getGameTime();
        self.tickCount++;
        replay.tickCount++;
        self.setDeltaMovement(REQUEST);
        self.move(MoverType.SELF, REQUEST);
        check(self.lastMove != null && !self.lastMove.indeterminate(), "determinate SELF " + self.lastMove);
        check(self.lastMove.terminalGrounded() && self.onGround(), "stable endpoint support");
        check(self.lastMove.stepHeight() == 0 && self.lastMove.supportFollowRise() == 0
                && self.lastMove.recoveryMovement().lengthSquared() == 0, "no synthetic terrain/recovery rise");
        check(Math.abs(self.getY() - start.y) <= EPSILON, "no floor separation");
        check(Math.abs(self.lastMove.resolvedMovement().x() - REQUEST.x) <= EPSILON, "retained wall slide");
        check(self.getBoundingBox().maxZ <= 8 + EPSILON, "wall penetration");
        check(Math.abs(self.getDeltaMovement().y) <= EPSILON && Math.abs(self.getDeltaMovement().z) <= EPSILON,
                "no accumulated normal velocity " + self.getDeltaMovement());

        // A separate actor replays the accepted endpoint via the real PLAYER
        // collision channel. This is server operand parity, not a running client.
        replay.move(MoverType.PLAYER, self.position().subtract(replay.position()));
        check(replay.position().distanceTo(self.position()) <= EPSILON, "SELF/PLAYER endpoint parity");
        check(replay.lastMove != null && !replay.lastMove.indeterminate() && replay.lastMove.terminalGrounded(),
                "replayed endpoint support");
        for (var actor : new Actor[]{self, replay}) {
            var runtime = GravityEntityAccess.cast(actor).gravityengine$gravityComponent().operationState();
            check(!runtime.isInMove() && runtime.collisionOperation() == null, "operation closes");
            check(runtime.persistentSupportState() != null, "persistent support retained by existing lifecycle");
        }
        ticks++;
        if (ticks == 200) {
            check(Math.abs(self.getX() - start.x - 200 * REQUEST.x) <= EPSILON, "200 tick progress");
            System.out.println("TILTED_CORNER_200_SERVER_TICKS_PASSED SELF PLAYER support velocity closure");
            return true;
        }
        return false;
    }

    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError("tilted corner tick=" + ticks + " " + message);
    }

    @Override public void close() {
        self.discard();
        replay.discard();
        saved.forEach((pos, state) -> level.setBlock(pos, state, 18));
    }

    private static final class Actor extends Player {
        GravityMoveResult lastMove;
        Actor(ServerLevel level) {
            super(level, BlockPos.ZERO, 0, new GameProfile(UUID.randomUUID(), "tilted-corner"));
            setPos(20, 310, 20);
            GravityApplicationCoordinator.applyDirectAssignment(this, new GravityState(DOWN, .08));
        }
        @Override public boolean isSpectator() { return false; }
        @Override public boolean isCreative() { return false; }
        @Override protected void checkFallDamage(double movement, boolean grounded, BlockState state, BlockPos pos) {
            lastMove = GravityEntityAccess.cast(this).gravityengine$gravityComponent().operationState().currentMoveResult();
            super.checkFallDamage(movement, grounded, state, pos);
        }
    }
}
