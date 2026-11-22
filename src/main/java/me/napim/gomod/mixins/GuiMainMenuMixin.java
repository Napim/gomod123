package me.napim.gomod.mixins;

import me.napim.gomod.features.GomodKeybinds;
import me.napim.gomod.features.GomodSocialMainMenuNotifications;
import me.napim.gomod.features.MainMenuBlitzQueue;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds Blitz Solo/Teams quick-queue buttons and gomod Social notifications to the
 * vanilla/Forge (and Lunar Vanilla Home) title screen.
 */
@Mixin(GuiMainMenu.class)
public class GuiMainMenuMixin extends GuiScreen {
    @Unique
    private static final int GOMOD_QUEUE_SOLO_ID = 41201;
    @Unique
    private static final int GOMOD_QUEUE_TEAMS_ID = 41202;
    @Unique
    private static final int GOMOD_QUEUE_BUTTON_GAP = 4;
    @Unique
    private static final int GOMOD_QUEUE_BUTTON_HEIGHT = 20;

    @Unique
    private GuiButton gomod$queueSoloButton;
    @Unique
    private GuiButton gomod$queueTeamsButton;

    @Inject(method = {"initGui", "func_73866_w_"}, at = @At("RETURN"), require = 0)
    private void gomod$addBlitzQueueButtons(CallbackInfo ci) {
        try {
            if (this.buttonList == null) {
                return;
            }
            this.buttonList.removeIf(btn -> btn != null
                    && (btn.id == GOMOD_QUEUE_SOLO_ID || btn.id == GOMOD_QUEUE_TEAMS_ID));
            this.gomod$queueSoloButton = null;
            this.gomod$queueTeamsButton = null;
            if (!MainMenuBlitzQueue.isEnabled()) {
                return;
            }
            MainMenuBlitzQueue.ensureFreshCounts();

            MainMenuBlitzQueue.CountSnapshot snap = MainMenuBlitzQueue.getSnapshot();
            String soloLabel = snap.soloLabel();
            String teamsLabel = snap.teamsLabel();
            int buttonWidth = gomod$computeQueueButtonWidth(soloLabel, teamsLabel);
            int buttonY = gomod$computeQueueButtonY();
            int leftX = this.width / 2 - buttonWidth - GOMOD_QUEUE_BUTTON_GAP / 2;
            int rightX = this.width / 2 + GOMOD_QUEUE_BUTTON_GAP / 2;

            this.gomod$queueSoloButton = new GuiButton(
                    GOMOD_QUEUE_SOLO_ID, leftX, buttonY, buttonWidth, GOMOD_QUEUE_BUTTON_HEIGHT, soloLabel);
            this.gomod$queueTeamsButton = new GuiButton(
                    GOMOD_QUEUE_TEAMS_ID, rightX, buttonY, buttonWidth, GOMOD_QUEUE_BUTTON_HEIGHT, teamsLabel);
            this.buttonList.add(this.gomod$queueSoloButton);
            this.buttonList.add(this.gomod$queueTeamsButton);
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = {"actionPerformed", "func_146284_a"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$handleBlitzQueueClick(GuiButton button, CallbackInfo ci) {
        if (button == null) {
            return;
        }
        if (button.id == GOMOD_QUEUE_SOLO_ID) {
            MainMenuBlitzQueue.triggerQuickQueue(MainMenuBlitzQueue.Mode.SOLO);
            ci.cancel();
        } else if (button.id == GOMOD_QUEUE_TEAMS_ID) {
            MainMenuBlitzQueue.triggerQuickQueue(MainMenuBlitzQueue.Mode.TEAMS);
            ci.cancel();
        }
    }

    @Inject(method = {"keyTyped", "func_73869_a"}, at = @At("HEAD"), require = 0)
    private void gomod$openSocialHotkey(char typedChar, int keyCode, CallbackInfo ci) {
        try {
            GomodKeybinds.handleSocialKeyTyped(keyCode);
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = {"drawScreen", "func_73863_a"}, at = @At("HEAD"), require = 0)
    private void gomod$refreshBlitzQueueLabels(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        try {
            boolean enabled = MainMenuBlitzQueue.isEnabled();
            if (this.gomod$queueSoloButton != null) {
                this.gomod$queueSoloButton.visible = enabled;
                this.gomod$queueSoloButton.enabled = enabled;
            }
            if (this.gomod$queueTeamsButton != null) {
                this.gomod$queueTeamsButton.visible = enabled;
                this.gomod$queueTeamsButton.enabled = enabled;
            }
            if (!enabled) {
                return;
            }
            MainMenuBlitzQueue.ensureFreshCounts();
            if (this.gomod$queueSoloButton == null || this.gomod$queueTeamsButton == null) {
                return;
            }
            MainMenuBlitzQueue.CountSnapshot snap = MainMenuBlitzQueue.getSnapshot();
            String soloLabel = snap.soloLabel();
            String teamsLabel = snap.teamsLabel();
            if (!soloLabel.equals(this.gomod$queueSoloButton.displayString)
                    || !teamsLabel.equals(this.gomod$queueTeamsButton.displayString)) {
                this.gomod$queueSoloButton.displayString = soloLabel;
                this.gomod$queueTeamsButton.displayString = teamsLabel;
                int buttonWidth = gomod$computeQueueButtonWidth(soloLabel, teamsLabel);
                this.gomod$queueSoloButton.width = buttonWidth;
                this.gomod$queueTeamsButton.width = buttonWidth;
                this.gomod$queueSoloButton.xPosition = this.width / 2 - buttonWidth - GOMOD_QUEUE_BUTTON_GAP / 2;
                this.gomod$queueTeamsButton.xPosition = this.width / 2 + GOMOD_QUEUE_BUTTON_GAP / 2;
            }
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = {"drawScreen", "func_73863_a"}, at = @At("RETURN"), require = 0)
    private void gomod$renderSocialNotifications(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        try {
            GomodSocialMainMenuNotifications.renderIfMainMenu((GuiMainMenu) (Object) this);
        } catch (Throwable ignored) {
        }
    }

    @Unique
    private int gomod$computeQueueButtonWidth(String soloLabel, String teamsLabel) {
        int maxTextWidth = 84;
        if (this.fontRendererObj != null) {
            maxTextWidth = Math.max(
                    this.fontRendererObj.getStringWidth(soloLabel),
                    this.fontRendererObj.getStringWidth(teamsLabel));
        }
        int desired = Math.max(98, maxTextWidth + 14);
        int maxAllowed = Math.max(98, (this.width - 24 - GOMOD_QUEUE_BUTTON_GAP) / 2);
        return Math.min(desired, maxAllowed);
    }

    @Unique
    private int gomod$computeQueueButtonY() {
        int maxBottom = this.height / 4 + 48 + 72 + 12 + 20;
        if (this.buttonList != null) {
            for (GuiButton btn : this.buttonList) {
                if (btn == null || btn.id == GOMOD_QUEUE_SOLO_ID || btn.id == GOMOD_QUEUE_TEAMS_ID) {
                    continue;
                }
                int bottom = btn.yPosition + btn.height;
                if (bottom > maxBottom && bottom < this.height - 20) {
                    maxBottom = bottom;
                }
            }
        }
        int candidate = maxBottom + 4;
        int maxY = Math.max(8, this.height - GOMOD_QUEUE_BUTTON_HEIGHT - 16);
        return Math.min(candidate, maxY);
    }
}
