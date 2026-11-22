package me.napim.gomod.gui;

import me.napim.gomod.features.ApiKeyManager;
import me.napim.gomod.features.KillCounter;
import me.napim.gomod.features.PlayerQueuer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.EnumChatFormatting;

import java.io.IOException;
import java.util.Locale;

/** Optional dynamic queue/dodge rules for Player Queuer. */
public final class PlayerQueuerIncludeRulesGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {
    private final GuiScreen parent;
    private final boolean excludeRules;
    private GuiButton nicksButton;
    private GuiButton wlrButton;
    private GuiButton scanModeButton;
    private GuiButton apiKeysButton;
    private GuiTextField wlrThresholdField;
    private boolean wlrFieldFormatted;
    private boolean drawingDisabledWlrField;
    private int controlsTop;

    public PlayerQueuerIncludeRulesGUI(GuiScreen parent, boolean excludeRules) {
        this.parent = parent;
        this.excludeRules = excludeRules;
    }

    @Override
    public void initGui() {
        int cx = width / 2;
        int backY = GuiHeader.backButtonY(height, 20);
        int availableTop = 42;
        int controlsHeight = 152;
        int availableHeight = Math.max(controlsHeight, backY - 12 - availableTop);
        controlsTop = availableTop + Math.max(0, (availableHeight - controlsHeight) / 2);
        wlrThresholdField = new WlrThresholdField(20, cx - 46, controlsTop + 64, 92, 20);
        wlrThresholdField.setMaxStringLength(12);
        wlrThresholdField.setText(formatWlr(excludeRules
                ? PlayerQueuer.getExcludeWlrThreshold() : PlayerQueuer.getIncludeWlrThreshold()));
        setWlrFieldFormatted(true);

        buttonList.clear();
        nicksButton = new GuiButton(1, cx - 110, controlsTop, 220, 20, "");
        wlrButton = new GuiButton(2, cx - 110, controlsTop + 24, 220, 20, "");
        scanModeButton = new GuiButton(3, cx - 110, controlsTop + 108, 220, 20, "");
        apiKeysButton = new GuiButton(4, cx - 110, controlsTop + 132, 220, 20, "");
        buttonList.add(nicksButton);
        buttonList.add(wlrButton);
        buttonList.add(scanModeButton);
        buttonList.add(apiKeysButton);
        buttonList.add(new GuiButton(0, cx - 60, backY, 120, 20,
                EnumChatFormatting.GRAY + "Save & Back"));
        updateLabels();
    }

    private void updateLabels() {
        boolean allNicks = excludeRules ? PlayerQueuer.isExcludeAllNicks() : PlayerQueuer.isIncludeAllNicks();
        boolean wlrOn = excludeRules ? PlayerQueuer.isExcludeWlrRange() : PlayerQueuer.isIncludeWlrRange();
        nicksButton.displayString = (excludeRules ? "Dodge" : "Queue") + " nicks: " + onOff(allNicks);
        wlrButton.displayString = (excludeRules ? "Dodge" : "Queue") + " WLR value: " + onOff(wlrOn);
        KillCounter.AutoScanMode mode = KillCounter.getAutoScanMode();
        scanModeButton.displayString = "Player stats scan: "
                + (mode == KillCounter.AutoScanMode.PLAYERS_JOIN
                ? EnumChatFormatting.GREEN + "When players join"
                : EnumChatFormatting.RED + mode.name().replace('_', ' '));
        apiKeysButton.displayString = "Hypixel API key: "
                + (ApiKeyManager.hasApiKey("hypixel")
                ? EnumChatFormatting.GREEN + "Set"
                : EnumChatFormatting.RED + "Missing (click to configure)");
        wlrThresholdField.setEnabled(true);
    }

    private static String onOff(boolean on) {
        return on ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF";
    }

    private boolean isWlrRuleOn() {
        return excludeRules ? PlayerQueuer.isExcludeWlrRange() : PlayerQueuer.isIncludeWlrRange();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj,
                (excludeRules ? EnumChatFormatting.DARK_RED : EnumChatFormatting.AQUA)
                        + "Player Queuer " + (excludeRules ? "Dodging" : "Queue") + " Rules", width,
                GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        drawCenteredString(fontRendererObj,
                EnumChatFormatting.GRAY + (excludeRules ? "Dodge starting from" : "Queue starting from"),
                width / 2, controlsTop + 54, 0xFFFFFF);
        drawingDisabledWlrField = !isWlrRuleOn();
        wlrThresholdField.drawTextBox();
        drawingDisabledWlrField = false;
        super.drawScreen(mouseX, mouseY, partialTicks);
        GomodSearchHighlight.draw(buttonList, PlayerQueuerIncludeRulesGUI.class);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == 0) {
            saveRange();
            mc.displayGuiScreen(parent);
        } else if (button.id == 1) {
            if (excludeRules) {
                PlayerQueuer.setExcludeAllNicksSilent(!PlayerQueuer.isExcludeAllNicks());
            } else {
                PlayerQueuer.setIncludeAllNicksSilent(!PlayerQueuer.isIncludeAllNicks());
            }
            updateLabels();
        } else if (button.id == 2) {
            boolean enabled = !(excludeRules ? PlayerQueuer.isExcludeWlrRange() : PlayerQueuer.isIncludeWlrRange());
            if (enabled) {
                KillCounter.setAutoScanMode(KillCounter.AutoScanMode.PLAYERS_JOIN);
            }
            if (excludeRules) {
                PlayerQueuer.setExcludeWlrThresholdSilent(enabled,
                        parseWlr(wlrThresholdField, PlayerQueuer.getExcludeWlrThreshold()));
            } else {
                PlayerQueuer.setIncludeWlrThresholdSilent(enabled,
                        parseWlr(wlrThresholdField, PlayerQueuer.getIncludeWlrThreshold()));
            }
            updateLabels();
        } else if (button.id == 3) {
            KillCounter.setAutoScanMode(KillCounter.AutoScanMode.PLAYERS_JOIN);
            updateLabels();
        } else if (button.id == 4) {
            saveRange();
            mc.displayGuiScreen(new ApiKeyGUI(this));
        }
    }

    private void saveRange() {
        setWlrFieldFormatted(false);
        if (excludeRules) {
            PlayerQueuer.setExcludeWlrThresholdSilent(PlayerQueuer.isExcludeWlrRange(),
                    parseWlr(wlrThresholdField, PlayerQueuer.getExcludeWlrThreshold()));
        } else {
            PlayerQueuer.setIncludeWlrThresholdSilent(PlayerQueuer.isIncludeWlrRange(),
                    parseWlr(wlrThresholdField, PlayerQueuer.getIncludeWlrThreshold()));
        }
        setWlrFieldFormatted(!wlrThresholdField.isFocused());
    }

    private static double parseWlr(GuiTextField field, double fallback) {
        try {
            return Double.parseDouble(cleanWlrFieldText(field.getText()));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String cleanWlrFieldText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("WLR", "").replace("wlr", "")
                .replace(">=", "").trim();
    }

    private void setWlrFieldFormatted(boolean formatted) {
        if (wlrThresholdField == null) {
            return;
        }
        String value = cleanWlrFieldText(wlrThresholdField.getText());
        if (value.isEmpty()) {
            value = formatWlr(excludeRules
                    ? PlayerQueuer.getExcludeWlrThreshold() : PlayerQueuer.getIncludeWlrThreshold());
        }
        wlrThresholdField.setText(formatted ? ">=" + value + " WLR" : value);
        wlrThresholdField.setCursorPosition(wlrThresholdField.getText().length());
        wlrFieldFormatted = formatted;
    }

    private static String formatWlr(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) {
            saveRange();
            mc.displayGuiScreen(parent);
            return;
        }
        if (!wlrThresholdField.isFocused()) {
            super.keyTyped(typedChar, keyCode);
            return;
        }
        if (wlrFieldFormatted) {
            setWlrFieldFormatted(false);
        }
        wlrThresholdField.textboxKeyTyped(typedChar, keyCode);
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        boolean wasFocused = wlrThresholdField.isFocused();
        super.mouseClicked(mouseX, mouseY, mouseButton);
        wlrThresholdField.mouseClicked(mouseX, mouseY, mouseButton);
        boolean focused = wlrThresholdField.isFocused();
        if (!wasFocused && focused) {
            setWlrFieldFormatted(false);
        } else if (wasFocused && !focused) {
            saveRange();
        }
    }

    @Override
    public boolean shouldBlockGomodMenuMovementAndDragLook() {
        return GomodMenuKeyboardCaptureGuards.anyTextFieldFocused(wlrThresholdField);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private final class WlrThresholdField extends GuiTextField {
        private WlrThresholdField(int componentId, int x, int y, int width, int height) {
            super(componentId, PlayerQueuerIncludeRulesGUI.this.fontRendererObj, x, y, width, height);
        }

        @Override
        public void drawTextBox() {
            if (!drawingDisabledWlrField) {
                this.setTextColor(0xE0E0E0);
                super.drawTextBox();
                return;
            }
            this.setTextColor(0xA0A0A0);
            super.drawTextBox();
        }
    }
}
