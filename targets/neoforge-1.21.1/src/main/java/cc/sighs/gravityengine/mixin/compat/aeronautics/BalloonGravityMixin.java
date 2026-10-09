package cc.sighs.gravityengine.mixin.compat.aeronautics;

import cc.sighs.gravityengine.gravity.integration.compat.sable.SableGravityBridge;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.world.level.Level;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.ServerBalloon", remap = false)
public abstract class BalloonGravityMixin {
    @WrapOperation(method = "applyForces", at = @At(value = "INVOKE", target =
            "Ldev/ryanhcode/sable/physics/config/dimension_physics/DimensionPhysicsData;getGravity(Lnet/minecraft/world/level/Level;Lorg/joml/Vector3dc;Lorg/joml/Vector3d;)Lorg/joml/Vector3d;"), require = 1)
    private Vector3d gravityengine$lift(Level level, Vector3dc point, Vector3d destination,
                                       Operation<Vector3d> original, @Local(index = 4) ServerSubLevel body,
                                       @Local(argsOnly = true) double seconds) {
        return SableGravityBridge.liftGravity(body, point, original.call(level, point, destination), seconds);
    }
}
