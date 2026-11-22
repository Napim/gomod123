package me.napim.gomod.coremod;

import me.napim.gomod.hooks.BestArmorGuiContainerBackgroundLayerHook;
import me.napim.gomod.hooks.BestArmorGuiContainerDrawHook;
import me.napim.gomod.hooks.BestArmorGuiContainerDrawSlotHeadHook;
import me.napim.gomod.hooks.MinecraftClickHook;
import me.napim.gomod.hooks.GlStateManagerGuiOpacityHook;
import me.napim.gomod.hooks.RenderManagerGuiOpacityHook;
import me.napim.gomod.hooks.ModelPlayerPeePoseForgeHook;
import me.napim.gomod.hooks.ModelBipedPeePoseForgeHook;
import me.napim.gomod.hooks.RendererLivingPeeStainForgeHook;
import org.spongepowered.asm.mixin.Mixins;
import net.weavemc.loader.api.Hook;
import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.List;

public class GomodTransformer implements IClassTransformer {

    private final List<Hook> hooks = new ArrayList<>();

    public GomodTransformer() {
        // Essential/OneConfig can initialize a newer Mixin environment after IFMLLoadingPlugin.injectData,
        // discarding configs registered in that earlier environment. This constructor runs after the winning
        // MixinTweaker is active but before Minecraft renderer/model classes load.
        try {
            if (!GomodMixinBootstrap.isConfigRegistered()) {
                Mixins.addConfiguration("gomod.mixins.json");
                System.out.println("[gomod] Forge transformer registered gomod.mixins.json in active Mixin environment");
            }
        } catch (Throwable t) {
            System.err.println("[gomod] Forge transformer mixin registration failed: " + t);
        }
        hooks.add(new MinecraftClickHook());
        hooks.add(new BestArmorGuiContainerDrawHook());
        hooks.add(new BestArmorGuiContainerDrawSlotHeadHook());
        hooks.add(new BestArmorGuiContainerBackgroundLayerHook());
        // Native fallbacks are required on Forge installations where another bundled Mixin
        // tweaker wins initialization and gomod.mixins.json is consequently not registered.
        hooks.add(new RenderManagerGuiOpacityHook());
        hooks.add(new GlStateManagerGuiOpacityHook());
        hooks.add(new ModelPlayerPeePoseForgeHook());
        hooks.add(new ModelBipedPeePoseForgeHook());
        hooks.add(new RendererLivingPeeStainForgeHook());
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) {
            return null;
        }

        String nameSlash = name != null ? name.replace('.', '/') : "";
        String transformedSlash = transformedName != null ? transformedName.replace('.', '/') : "";

        boolean needsTransform = false;
        for (Hook hook : hooks) {
            if (matchesTarget(hook, nameSlash, transformedSlash)) {
                needsTransform = true;
                break;
            }
        }
        if (!needsTransform) {
            return basicClass;
        }

        try {
            ClassNode node = new ClassNode();
            ClassReader reader = new ClassReader(basicClass);
            reader.accept(node, 0);

            for (Hook hook : hooks) {
                    if (matchesTarget(hook, nameSlash, transformedSlash)) {
                        hook.transform(node, new Hook.AssemblerConfig());
                    }
            }

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            t.printStackTrace();
            return basicClass;
        }
    }

    private static boolean matchesTarget(Hook hook, String nameSlash, String transformedSlash) {
        String target = hook.getTargetClass();
        if (target.equals(nameSlash) || target.equals(transformedSlash)) {
            return true;
        }
        // This transformer runs on pre-deobfuscation class bytes in crowded 1.8.9 Forge profiles.
        return hook instanceof ModelPlayerPeePoseForgeHook && "bbr".equals(nameSlash)
                || hook instanceof ModelBipedPeePoseForgeHook && "bbj".equals(nameSlash)
                || hook instanceof RendererLivingPeeStainForgeHook && "bjl".equals(nameSlash);
    }
}
