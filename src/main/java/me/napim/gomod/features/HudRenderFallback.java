package me.napim.gomod.features;

import me.napim.gomod.util.HudGlState;
import me.napim.gomod.util.HudGuiItemIcon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.weavemc.loader.api.event.RenderGameOverlayEvent;
import net.weavemc.loader.api.event.SubscribeEvent;

/**
 * Second-pass HUD when {@link me.napim.gomod.mixins.GuiIngameMixin} does not run. Forge's overlay Post event
 * fires before the mixin TAIL, so the per-frame rendered flag is still false inside this listener even when
 * the mixin will draw the HUD moments later. The sticky HEAD flag distinguishes a skipped mixin from that ordering.
 */
public class HudRenderFallback {

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (MixinStatus.guiIngameHookActive || MixinStatus.guiIngameOverlayRenderedThisFrame) {
            return;
        }

        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                return;
            }
            HudGlState.restoreForOverlayText();
            if (!PlayersPlayMode.isEnabled()) {
                InventoryHUD.render();
            }
            PlayerTarget.render();
            MobHealthDisplay.render();
            MobTargetDisplay.renderOverlay();
            DuelPredictor.renderHud();
            BlitzChallenges.renderHud();
            HorseDonkeyTracker.render();
            MoreHudFeature.render(mc);
            Selfie.renderRecordingIcon();
            try {
                InventoryHUD.renderShareInvTooltipLast(mc);
            } catch (Throwable ignored) {
            }
            MountXpOverlay.renderFallbackAfterOverlay();
            renderBestArmorHotbarOverlay();
        } catch (Throwable t) {
            // Keep HUD rendering resilient; never propagate errors to the client loop.
        }
    }

    /**
     * Fallback hotbar overlay when GuiIngame hotbar hooks do not run: BestArmor tint and/or useless-item tint.
     * Must not require BestArmor to be on — useless-only matches primary {@link me.napim.gomod.mixins.GuiIngameMixin}
     * behavior (otherwise useless overlays disappear on shim builds whenever BestArmor is disabled).
     */
    private void renderBestArmorHotbarOverlay() {
        if (!InventoryQolHudUnderlay.shouldRenderHotbarFallback()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        boolean bestArmorHud = BestArmorDisplay.isOverlayActiveInContext(mc);
        boolean uselessHud = UselessItemOverlay.isOverlayActiveInContext(mc);
        if (!bestArmorHud && !uselessHud) {
            return;
        }

        if (mc.thePlayer == null || mc.thePlayer.inventory == null) {
            return;
        }

        // Chat leaves the in-world hotbar visible; container/menu screens have their own slots.
        if (mc.currentScreen != null && !(mc.currentScreen instanceof GuiChat)) {
            return;
        }

        try {
            EntityPlayer player = mc.thePlayer;
            ScaledResolution scaledResolution = new ScaledResolution(mc);
            int screenWidth = scaledResolution.getScaledWidth();
            int screenHeight = scaledResolution.getScaledHeight();

            // Hotbar position (centered at bottom of screen)
            int hotbarWidth = 182;
            int hotbarX = (screenWidth / 2) - (hotbarWidth / 2);
            int hotbarY = screenHeight - 22;

            int slotSize = 20;
            int slotItemOffset = 3;

            RenderItem ri = mc.getRenderItem();
            for (int i = 0; i < 9; i++) {
                ItemStack stack = player.inventory.getStackInSlot(i);
                int slotX = hotbarX + (i * slotSize) + slotItemOffset;
                int slotY = hotbarY + slotItemOffset;
                if (stack != null && !InventoryQolHudUnderlay.wasHotbarSlotRendered(i)) {
                    InventoryQolHudUnderlay.renderSlot(i, slotX, slotY, player,
                            InventoryQolHudUnderlay.SOURCE_HOTBAR_FALLBACK);
                    // This fallback runs after the original hotbar. Redraw the icon as well as its count;
                    // otherwise the tint is painted over the item instead of underneath it.
                    HudGuiItemIcon.drawInventoryHudSlot(mc, stack, slotX, slotY);
                    RenderHelper.enableGUIStandardItemLighting();
                    ri.zLevel = 200.0f;
                    ri.renderItemOverlayIntoGUI(mc.fontRendererObj, stack, slotX, slotY, null);
                    ri.zLevel = 0.0f;
                    RenderHelper.disableStandardItemLighting();
                }
            }
        } catch (Throwable t) {
            System.err.println("[gomod] BestArmor hotbar fallback error: " + t.getMessage());
        }
    }
}
