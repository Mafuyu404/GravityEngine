package cc.sighs.gravityengine.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.util.List;
import java.util.Set;

/** Keeps optional target classes outside absent-mod loading paths. */
public final class OptionalCompatibilityPlugin implements IMixinConfigPlugin {
    public void onLoad(String mixinPackage) {}
    public String getRefMapperConfig() { return null; }
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!cc.sighs.gravityengine.gravity.debug.DebugMixinSelection.shouldApply(
                mixinClassName, cc.sighs.gravityengine.gravity.debug.BootDebugOptions.options())) return false;
        return !mixinClassName.contains(".compat.sable.")
                || getClass().getClassLoader().getResource("dev/ryanhcode/sable/Sable.class") != null;
    }
    public void acceptTargets(Set<String> mine, Set<String> others) {}
    public List<String> getMixins() { return null; }
    public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
