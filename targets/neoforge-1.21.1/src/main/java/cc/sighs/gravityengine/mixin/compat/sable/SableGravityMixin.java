package cc.sighs.gravityengine.mixin.compat.sable;

import cc.sighs.gravityengine.gravity.integration.compat.sable.SableGravityBridge;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ServerSubLevel.class, remap = false)
public abstract class SableGravityMixin {
    @Inject(method = "applyQueuedForces", at = @At("RETURN"), require = 1)
    private void gravityengine$gravity(SubLevelPhysicsSystem system, RigidBodyHandle handle, double seconds, CallbackInfo ci) {
        SableGravityBridge.apply((ServerSubLevel)(Object)this, system, handle, seconds);
    }
}
