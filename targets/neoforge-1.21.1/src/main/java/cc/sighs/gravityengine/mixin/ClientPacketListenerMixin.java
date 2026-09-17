package cc.sighs.gravityengine.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handlePlayerAbilities", at = @At("RETURN"))
    private void gravityengine$updateLocal(ClientboundPlayerAbilitiesPacket pkt, CallbackInfo ci) {
        var mc = Minecraft.getInstance();
        if (mc.player != null) cc.sighs.gravityengine.gravity.integration.GravityApplicationCoordinator.updateLocalAbilitySuppression(mc.player);
    }

}
