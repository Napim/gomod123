package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.weavemc.loader.api.event.RenderGameOverlayEvent;
import net.weavemc.loader.api.event.SubscribeEvent;

/**
 * Lunar Genesis bakes {@code Minecraft} / {@code GuiContainer} before Weave mixins apply, so
 * {@link me.napim.gomod.mixins.GuiContainerMixin} and {@link me.napim.gomod.mixins.MinecraftRunTickFeaturesMixin}
 * are often no-ops. When {@link me.napim.gomod.hooks.BestArmorGuiContainerDrawHook} applies, this handler does
 * nothing (hook paints after {@code GuiContainer.drawScreen}). Otherwise {@link RenderGameOverlayEvent.Post} is a
 * z-order fallback (may sit under the GUI on some builds).
 */
public final class BestArmorWeaveGuiDriver {

    private static volatile boolean loggedSubscribe;

    public BestArmorWeaveGuiDriver() {
        if (!loggedSubscribe) {
            loggedSubscribe = true;
            System.out.println("[gomod] BestArmor: Weave RenderGameOverlay driver active (Genesis-safe when mixins are skipped)");
        }
    }

    @SubscribeEvent
    public void onRenderOverlayPost(RenderGameOverlayEvent.Post event) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                return;
            }
            BestArmorContainerOverlayDriver.renderContainerOverlaysForWeave(mc);
        } catch (Throwable t) {
            System.err.println("[gomod] BestArmor Weave overlay error: " + t.getMessage());
        }
    }
}
