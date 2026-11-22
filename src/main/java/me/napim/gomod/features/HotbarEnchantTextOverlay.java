package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.item.ItemStack;
import net.weavemc.loader.api.event.RenderGameOverlayEvent;
import net.weavemc.loader.api.event.TickEvent;
import net.weavemc.loader.api.event.SubscribeEvent;

/**
 * Tracks hotbar slot changes and draws enchant text on {@link RenderGameOverlayEvent.Post},
 * independent of {@link me.napim.gomod.mixins.GuiIngameMixin} (reliable on Weave + Forge).
 */
public final class HotbarEnchantTextOverlay {

    private static final int HIGHLIGHT_TICKS = 40;

    private static int highlightTicks;
    private static ItemStack highlightedStack;

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (!ConfigManager.isHotbarEnchantTextEnabled()) {
            highlightTicks = 0;
            highlightedStack = null;
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.thePlayer.inventory == null) {
            highlightTicks = 0;
            highlightedStack = null;
            return;
        }

        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null) {
            highlightTicks = 0;
            highlightedStack = null;
            return;
        }

        if (highlightedStack == null || !ItemStack.areItemStacksEqual(highlightedStack, held)) {
            highlightedStack = held;
            highlightTicks = HIGHLIGHT_TICKS;
        } else if (highlightTicks > 0) {
            highlightTicks--;
        }
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (!ConfigManager.isHotbarEnchantTextEnabled()) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.currentScreen != null) {
            return;
        }
        if (highlightTicks <= 0 || highlightedStack == null) {
            return;
        }

        ItemStack renderStack = highlightedStack;
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held != null && held.getItem() == highlightedStack.getItem()) {
            renderStack = held;
        }

        HotbarEnchantText.render(new ScaledResolution(mc), highlightTicks, renderStack);
    }
}
