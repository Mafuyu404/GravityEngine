package cc.sighs.gravityengine.mixin;

import cc.sighs.gravityengine.gravity.integration.CharacterPlacementValidation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.DismountHelper;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

/** NeoForge 21.1.256 native candidate clearance, including pose overload callers. */
@Mixin(DismountHelper.class)
public abstract class DismountGeometryMixin {
    @WrapMethod(method="canDismountTo(Lnet/minecraft/world/level/CollisionGetter;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/phys/AABB;)Z",require=1)
    private static boolean gravityengine$installedDismount(CollisionGetter level,LivingEntity actor,AABB candidate,
                                                          Operation<Boolean> original) {
        return CharacterPlacementValidation.owns(actor)
                ? CharacterPlacementValidation.clearAt(actor,new Vec3((candidate.minX+candidate.maxX)*.5,
                        candidate.minY,(candidate.minZ+candidate.maxZ)*.5))
                :original.call(level,actor,candidate);
    }
}
