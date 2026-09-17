package cc.sighs.gravityengine.mixin.compat.sable;

import cc.sighs.gravityengine.gravity.integration.compat.sable.SableRigidCollisionProvider;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Both client and server plot edits revalidate only the published local geometry. */
@Mixin(value = LevelPlot.class, remap = false)
public abstract class SablePlotGeometryMixin {
    @Inject(method = "onBlockChange", at = @At("HEAD"), require = 1)
    private void gravityengine$geometryChanged(BlockPos pos, BlockState state, CallbackInfo ci) {
        SableRigidCollisionProvider.geometryChanged(((LevelPlot)(Object)this).getSubLevel(), pos, state);
    }
}
