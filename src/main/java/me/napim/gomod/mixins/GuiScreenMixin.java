package me.napim.gomod.mixins;

import me.napim.gomod.GomodReleaseBuild;
import me.napim.gomod.features.BlitzStarPrediction;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.HudEditorCenterGuides;
import me.napim.gomod.features.DisguiseRenamedItemMasker;
import me.napim.gomod.features.GomodMenuSettings;
import me.napim.gomod.features.GomodSocialMainMenuNotifications;
import me.napim.gomod.features.TooltipCaptureHooks;
import me.napim.gomod.gui.GomodMenuCloseAnim;
import me.napim.gomod.gui.GomodMenuTransitions;
import me.napim.gomod.gui.GomodSocialGUI;
import me.napim.gomod.render.GuiBackdropBlur;
import me.napim.gomod.render.GuiBackdropChatReserve;
import me.napim.gomod.render.GuiBackdropRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(GuiScreen.class)
public class GuiScreenMixin {

    @Unique
    private boolean gomod$renamedMaskFontSuppressActive;

    @Shadow
    public int width;

    @Shadow
    public int height;

    @Shadow
    protected List<GuiButton> buttonList;

    @Inject(method = {"handleMouseInput", "func_146274_d"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$giftNotificationInput(CallbackInfo ci) {
        if(me.napim.gomod.features.LunarFitNotifications.handleMouseEvent(org.lwjgl.input.Mouse.getEventX(),
                org.lwjgl.input.Mouse.getEventY(),org.lwjgl.input.Mouse.getEventButton(),org.lwjgl.input.Mouse.getEventButtonState()))ci.cancel();
    }

    /** Right-click belongs to drag-look in enabled MC menus, not to the button under the cursor. */
    @Inject(method = {"mouseClicked", "func_73864_a"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$preventMcMenuRightClickButtons(int mouseX, int mouseY, int mouseButton, CallbackInfo ci) {
        if (mouseButton != 1 || !GomodMenuSettings.isDragLookEnabled()
                || !GomodMenuSettings.isMinecraftMenuControlsEnabled()) {
            return;
        }
        GuiScreen self = (GuiScreen) (Object) this;
        if (!GomodMenuSettings.isSafeMinecraftMenuScreen(self) || buttonList == null) {
            return;
        }
        for (GuiButton button : buttonList) {
            if (button != null && button.visible && button.enabled
                    && mouseX >= button.xPosition && mouseX < button.xPosition + button.width
                    && mouseY >= button.yPosition && mouseY < button.yPosition + button.height) {
                ci.cancel();
                return;
            }
        }
    }

    /**
     * Intercepts ESC on any gomod sub-GUI: triggers the HUD zoom-out overlay,
     * then immediately closes to null so the player can move right away.
     * GomodGUI handles its own ESC and is excluded here (it doesn't call super for ESC).
     */
    @Inject(method = {"keyTyped", "func_73869_a"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$animateCloseOnEsc(char typedChar, int keyCode, CallbackInfo ci) {
        if (keyCode != 1) return; // not ESC
        GuiScreen self = (GuiScreen) (Object) this;
        if (!GomodMenuTransitions.isGomodScreen(self)) return;
        // GomodGUI / Social handle their own ESC (collapse workspace, return to parent, etc.)
        if (self.getClass().getName().equals("me.napim.gomod.gui.GomodGUI")) return;
        if (self instanceof GomodSocialGUI) return;
        try {
            GomodMenuTransitions.closeToGame(Minecraft.getMinecraft());
        } catch (Throwable ignored) {
        }
        ci.cancel();
    }
    @Inject(method = "renderToolTip", at = @At("HEAD"))
    private void gomod$beginTooltipCapture(ItemStack stack, int x, int y, CallbackInfo ci) {
        TooltipCaptureHooks.beginItemTooltip(stack, x, y);
        gomod$renamedMaskFontSuppressActive = DisguiseRenamedItemMasker.beginRenamedMaskFontSuppression(stack);
    }

    @Redirect(method = "renderToolTip", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/item/ItemStack;getTooltip(Lnet/minecraft/entity/player/EntityPlayer;Z)Ljava/util/List;"))
    private List<String> gomod$addBlitzStarPrediction(ItemStack stack, EntityPlayer player, boolean advanced) {
        List<String> tooltip = stack.getTooltip(player, advanced);
        if (!GomodReleaseBuild.PUBLIC_RELEASE && ConfigManager.isStarPredictEnabled()) {
            return BlitzStarPrediction.addToTooltip((GuiScreen) (Object) this, stack, tooltip);
        }
        return tooltip;
    }

    @Inject(method = "renderToolTip", at = @At("RETURN"))
    private void gomod$endTooltipCapture(ItemStack stack, int x, int y, CallbackInfo ci) {
        DisguiseRenamedItemMasker.endRenamedMaskFontSuppression(gomod$renamedMaskFontSuppressActive);
        gomod$renamedMaskFontSuppressActive = false;
        TooltipCaptureHooks.endItemTooltip();
        gomod$restorePostTooltipGuiState();
    }

    @Inject(method = "drawHoveringText", at = @At("HEAD"))
    private void gomod$captureRenderedTooltip(List<String> textLines, int x, int y, CallbackInfo ci) {
        TooltipCaptureHooks.captureRenderedTooltip(textLines, x, y);
    }

    @Inject(method = "drawHoveringText", at = @At("RETURN"))
    private void gomod$restoreRenderedTooltipState(List<String> textLines, int x, int y, CallbackInfo ci) {
        gomod$restorePostTooltipGuiState();
    }

    /**
     * Some tooltip/overlay paths temporarily enable item lighting or alter color masks. If that state leaks into the
     * next GUI draw while hovering an inventory slot, item stacks and the background can look noticeably darker.
     */
    @Unique
    private static void gomod$restorePostTooltipGuiState() {
        try {
            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableLighting();
            GlStateManager.colorMask(true, true, true, true);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.enableTexture2D();
            GlStateManager.enableDepth();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Optional Gaussian blur plus dim on the fullscreen gradient when backdrop is enabled ({@code ALL}).
     * Dim/blur are separate for {@code me.napim.gomod.gui.*} vs other screens.
     */
    @Inject(method = {"drawWorldBackground", "func_146270_b"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$customGuiBackdrop(int ignoredTint, CallbackInfo ci) {
        try {
            if ("VANILLA".equals(ConfigManager.getGuiBackdropMode())) {
                return;
            }
            GuiScreen self = (GuiScreen) (Object) this;
            if (self.getClass().getName().equals("me.napim.gomod.gui.GomodBlockOverlaySettingsGUI")) {
                ci.cancel();
                return;
            }
            if (GomodMenuCloseAnim.isRenderingCapturedScreen()) {
                ci.cancel();
                return;
            }
            if (GuiBackdropRenderer.renderCustomBackdrop(self, width, height)) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
        }
    }

    /** Center guides on top of every GUI when {@code /hudeditorlines} is enabled. */
    @Inject(method = {"drawScreen", "func_73863_a"}, at = @At("RETURN"), require = 0)
    private void gomod$hudEditorCenterGuides(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        try {
            HudEditorCenterGuides.drawOnGui(width, height);
        } catch (Throwable ignored) {
        }
        try {
            GomodSocialMainMenuNotifications.renderIfMenu((GuiScreen) (Object) this);
        } catch (Throwable ignored) {
        }
    }

}
