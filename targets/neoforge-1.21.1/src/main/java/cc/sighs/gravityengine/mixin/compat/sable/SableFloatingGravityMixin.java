package cc.sighs.gravityengine.mixin.compat.sable;

import cc.sighs.gravityengine.gravity.integration.compat.sable.SableGravityBridge;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.physics.floating_block.FloatingBlockController;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.world.level.Level;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = FloatingBlockController.class, remap = false)
public abstract class SableFloatingGravityMixin {
    @Shadow @Final private ServerSubLevel subLevel;

    @WrapOperation(method = "physicsTick", at = @At(value = "INVOKE", target =
            "Ldev/ryanhcode/sable/physics/config/dimension_physics/DimensionPhysicsData;getGravity(Lnet/minecraft/world/level/Level;Lorg/joml/Vector3dc;)Lorg/joml/Vector3d;"), require = 1)
    private Vector3d gravityengine$lift(Level level, Vector3dc point, Operation<Vector3d> original,
                                       @Local(argsOnly = true, ordinal = 1) double seconds) {
        return SableGravityBridge.liftGravity(subLevel, point, original.call(level, point), seconds);
    }
}
