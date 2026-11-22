package me.napim.gomod.mixins;

import me.napim.gomod.features.BlocksFinderWorldOpacityAtlasHook;
import me.napim.gomod.features.BestArmorContainerRenderStrategy;
import me.napim.gomod.features.BestArmorDisplay;
import me.napim.gomod.features.MixinStatus;
import me.napim.gomod.features.TooltipCaptureHooks;
import me.napim.gomod.features.UselessItemOverlay;
import me.napim.gomod.hooks.BestArmorGuiContainerDrawSlotHeadHook;
import me.napim.gomod.util.HudGlState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inventory-QoL slot underlays and survival-inventory additions.
 */
@Mixin(value = GuiContainer.class, priority = 1100)
public abstract class GuiContainerMixin {

    /**
     * Vanilla normally paints the hovered stack tooltip near the end of {@code drawScreen}. BestArmor's survival
     * inventory panel is painted later, so the old implementation painted the same tooltip a second time to put it
     * back on top. Two translucent tooltip backgrounds compound into a much darker rectangle, and the second pass
     * also ignored vanilla's "no tooltip while carrying a stack" rule.
     *
     * Capture the one vanilla tooltip call instead and replay it once after the QoL panel has finished.
     */
    @Unique
    private ItemStack gomod$deferredInventoryQolTooltip;

    @Unique
    private int gomod$deferredInventoryQolTooltipX;

    @Unique
    private int gomod$deferredInventoryQolTooltipY;

    @Shadow
    protected abstract Slot getSlotAtPosition(int x, int y);

    @Shadow
    public Container inventorySlots;

    @Shadow
    protected int guiLeft;

    @Shadow
    protected int guiTop;

    @Inject(method = "drawScreen", at = @At("HEAD"))
    private void gomod$inventoryQolPrepareFrame(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        gomod$deferredInventoryQolTooltip = null;
        BlocksFinderWorldOpacityAtlasHook.beginHudItemPass();
        try {
            Minecraft mc = Minecraft.getMinecraft();
            BestArmorDisplay.prepareInventoryQolCachesForFrame(mc);
            UselessItemOverlay.beginInventoryGuiEvalSnapshot(mc);
        } catch (Throwable ignored) {
        }
    }

    @Redirect(
        method = "drawScreen",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/inventory/GuiContainer;renderToolTip(Lnet/minecraft/item/ItemStack;II)V"
        ),
        require = 0
    )
    private void gomod$deferInventoryQolTooltip(GuiContainer self, ItemStack stack, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getMinecraft();
        if ((Object) this instanceof net.minecraft.client.gui.inventory.GuiInventory
            && BestArmorDisplay.isOverlayOrPercentageActiveInContext(mc)) {
            gomod$deferredInventoryQolTooltip = stack;
            gomod$deferredInventoryQolTooltipX = mouseX;
            gomod$deferredInventoryQolTooltipY = mouseY;
            return;
        }

        ((GuiScreenInvoker) self).gomod$invokeRenderToolTip(stack, mouseX, mouseY);
    }

    @Inject(method = "drawSlot", at = @At("HEAD"), require = 0)
    private void gomod$drawBestArmorDrawSlotHead(Slot slotIn, CallbackInfo ci) {
        try {
            MixinStatus.guiContainerActive = true;
            // All slot tints are underlays: render them before vanilla draws the item icon. The bytecode hook is the
            // primary path on clients that bake GuiContainer; this mixin is its Forge fallback. Vanilla's
            // translate(guiLeft, guiTop) is already active here, hence the 0,0 origin.
            if (!BestArmorGuiContainerDrawSlotHeadHook.isPatchActive()) {
                HudGlState.restoreForInventoryUnderlay();
                try {
                    BestArmorDisplay.renderSlotBestArmorOverlayIfNeeded(slotIn);
                    UselessItemOverlay.renderSlotOverlayIfNeeded(slotIn, 0, 0);
                    UselessItemOverlay.renderChestPickHintIfNeeded(slotIn, 0, 0);
                } finally {
                    HudGlState.restoreAfterInventoryUnderlay();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Survival inventory % / recommendations run on {@link me.napim.gomod.features.BestArmorWeaveGuiDriver} because
     * Genesis often skips this mixin. Tooltip capture remains here when the mixin applies.
     */
    @Inject(method = "drawScreen", at = @At("RETURN"))
    private void gomod$bestArmorDrawScreenReturn(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        try {
            int mode = BestArmorContainerRenderStrategy.get();
            if ((mode == BestArmorContainerRenderStrategy.MODE_DRAW_SLOT_HEAD
                || mode == BestArmorContainerRenderStrategy.MODE_REDIRECT_DRAW_SCREEN
                || mode == BestArmorContainerRenderStrategy.MODE_BOTH)
                && (Object) this instanceof net.minecraft.client.gui.inventory.GuiInventory) {
                BestArmorDisplay.renderSurvivalInventoryOverlay(
                    (net.minecraft.client.gui.inventory.GuiContainer) (Object) this,
                    this.guiLeft,
                    this.guiTop,
                    mouseX,
                    mouseY
                );
            }
            Slot hovered = this.getSlotAtPosition(mouseX, mouseY);
            if (hovered != null) {
                ItemStack stack = hovered.getStack();
                if (stack != null) {
                    TooltipCaptureHooks.beginItemTooltip(stack, mouseX, mouseY);
                    TooltipCaptureHooks.endItemTooltip();
                }
            }
        } catch (Throwable ignored) {
        } finally {
            ItemStack deferredTooltip = gomod$deferredInventoryQolTooltip;
            gomod$deferredInventoryQolTooltip = null;
            if (deferredTooltip != null) {
                try {
                    ((GuiScreenInvoker) (Object) this).gomod$invokeRenderToolTip(
                        deferredTooltip,
                        gomod$deferredInventoryQolTooltipX,
                        gomod$deferredInventoryQolTooltipY
                    );
                } catch (Throwable ignored) {
                }
            }
            MixinStatus.guiContainerActive = false;
            BlocksFinderWorldOpacityAtlasHook.endHudItemPass();
        }
    }
}
