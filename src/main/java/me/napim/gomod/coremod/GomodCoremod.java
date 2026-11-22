package me.napim.gomod.coremod;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.Mixins;

import java.util.Map;

/**
 * Forge coremod entry for BestArmor ASM hooks. Mixin is started by
 * {@code org.spongepowered.asm.launch.MixinTweaker} (see jar manifest {@code TweakClass}) and
 * {@code MixinConfigs: gomod.mixins.json} — do not call {@code MixinBootstrap.init()} here; that
 * races FML's legacy mixin platform and crashes with {@code NoClassDefFoundError: Proxy}.
 */
@IFMLLoadingPlugin.Name("gomod123-core")
@IFMLLoadingPlugin.MCVersion("1.8.9")
@IFMLLoadingPlugin.SortingIndex(10002)
@IFMLLoadingPlugin.TransformerExclusions({
        "me.napim.gomod.coremod.",
        "me.napim.gomod.hooks."
})
public class GomodCoremod implements IFMLLoadingPlugin {

    static {
        // SK1er/Essential owns the winning Mixin environment in crowded 1.8.9 profiles. Register while FML is
        // loading this coremod: Essential has initialized Mixin, but target classes/config preparation have not run.
        try {
            MixinBootstrap.init();
            if (!GomodMixinBootstrap.isConfigRegistered()) {
                Mixins.addConfiguration("gomod.mixins.json");
            }
            System.out.println("[gomod] Forge coremod statically registered gomod.mixins.json");
        } catch (Throwable t) {
            System.err.println("[gomod] Forge coremod static mixin registration failed: "
                    + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    @Override
    public void injectData(Map<String, Object> data) {
        try {
            if (!GomodMixinBootstrap.isConfigRegistered()) {
                MixinBootstrap.init();
                Mixins.addConfiguration("gomod.mixins.json");
                System.out.println("[gomod] Forge coremod registered gomod.mixins.json fallback");
            }
        } catch (Throwable t) {
            System.err.println("[gomod] Forge coremod mixin fallback failed: " + t.getClass().getName()
                    + ": " + t.getMessage());
        }
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[] { "me.napim.gomod.coremod.GomodTransformer" };
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
