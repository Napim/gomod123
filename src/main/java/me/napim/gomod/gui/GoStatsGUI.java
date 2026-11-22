package me.napim.gomod.gui;

import me.napim.gomod.features.AIChatHandler;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.GoStatsRelayClient;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import java.text.NumberFormat;
import java.util.Locale;

/** Settings and personal daily quota display for gostats123 AI. */
public class GoStatsGUI extends GuiScreen {
    private GuiButton toggleButton;
    private GuiButton helpButton;
    private GuiButton backButton;
    private boolean goStatsEnabled;
    private final ModuleSettingsEnabledOverlays enabledOverlays = new ModuleSettingsEnabledOverlays();

    @Override
    public void initGui() {
        super.initGui();
        this.goStatsEnabled = ConfigManager.isGoStatsEnabled();
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int buttonWidth = 200;
        int buttonHeight = 20;
        this.toggleButton = new GuiButton(0, centerX - buttonWidth / 2, centerY - 62, buttonWidth, buttonHeight,
                "gostats123 AI: " + (this.goStatsEnabled ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled"));
        this.helpButton = new GuiButton(2, centerX - buttonWidth / 2, centerY - 37, buttonWidth, buttonHeight,
                EnumChatFormatting.YELLOW + "Help & Usage");
        this.backButton = new GuiButton(3, centerX - buttonWidth / 2, centerY - 12, buttonWidth, buttonHeight,
                EnumChatFormatting.GRAY + "Back to Menu");
        this.buttonList.add(this.toggleButton);
        this.buttonList.add(this.helpButton);
        this.buttonList.add(this.backButton);
        enabledOverlays.trackModuleToggle("main", this.toggleButton, new ModuleSettingsEnabledOverlays.EnabledCheck() {
            @Override
            public boolean isOn() {
                return goStatsEnabled;
            }
        });
        GoStatsRelayClient.refreshUsageAsync(true);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        GuiHeader.drawColoredBoldTitleCentered(this.fontRendererObj, "gostats123 AI Settings",
                EnumChatFormatting.DARK_GREEN, this.width, GuiHeader.STANDARD_TITLE_Y);
        GuiHeader.drawScreenCenteredText(this.fontRendererObj,
                EnumChatFormatting.GRAY + "Uses the authenticated gomod relay", this.width, GuiHeader.STANDARD_SUBTITLE_Y, 0xFFFFFF);

        drawUsagePanel();

        super.drawScreen(mouseX, mouseY, partialTicks);
        enabledOverlays.drawAll();
        GomodSearchHighlight.draw(buttonList, GoStatsGUI.class);
    }

    private void drawUsagePanel() {
        int centerX = this.width / 2;
        int panelTop = Math.min(this.height - 145, this.height / 2 + 24);
        int panelBottom = Math.min(this.height - 48, panelTop + 116);
        int panelLeft = centerX - Math.min(190, this.width / 2 - 12);
        int panelRight = centerX + Math.min(190, this.width / 2 - 12);
        Gui.drawRect(panelLeft, panelTop, panelRight, panelBottom, 0xB010151C);
        Gui.drawRect(panelLeft, panelTop, panelRight, panelTop + 1, 0xFF2B7C58);

        GoStatsRelayClient.Usage usage = GoStatsRelayClient.getUsage();
        if (usage == null) {
            String error = GoStatsRelayClient.getLastUsageError();
            if (error != null && !error.isEmpty()) {
                drawCentered(EnumChatFormatting.RED + error, panelTop + 17, 0xFFFFFF);
                GoStatsRelayClient.refreshUsageAsync(false);
            } else if (GoStatsRelayClient.isUsageRefreshInFlight()) {
                drawCentered(EnumChatFormatting.YELLOW + "Checking daily allowance…", panelTop + 17, 0xFFFFFF);
            } else {
                drawCentered(EnumChatFormatting.YELLOW + "Allowance unavailable", panelTop + 17, 0xFFFFFF);
                GoStatsRelayClient.refreshUsageAsync(false);
            }
            return;
        }
        if (!usage.available || usage.quota.dailyLimitTokens <= 0) {
            String message = GoStatsRelayClient.getLastUsageError();
            if (message == null || message.isEmpty()) message = "Connect to gomod Social to check gostats123 usage";
            drawCentered(EnumChatFormatting.YELLOW + message, panelTop + 17, 0xFFFFFF);
            return;
        }

        GoStatsRelayClient.Quota quota = usage.quota;
        double ratio = quota.dailyLimitTokens <= 0L ? 0.0D
                : Math.max(0.0D, Math.min(1.0D, (double) quota.remainingTokens / (double) quota.dailyLimitTokens));
        int accent = quotaColor(ratio);
        String percent = (int) Math.round(ratio * 100.0D) + "% left";
        this.fontRendererObj.drawStringWithShadow(EnumChatFormatting.GRAY + "PERSONAL DAILY ALLOWANCE",
                panelLeft + 16, panelTop + 11, 0xFFFFFF);
        this.fontRendererObj.drawStringWithShadow(quotaFormatting(ratio) + percent,
                panelRight - 16 - this.fontRendererObj.getStringWidth(percent), panelTop + 11, 0xFFFFFF);

        String remaining = EnumChatFormatting.BOLD + formatTokens(quota.remainingTokens)
                + EnumChatFormatting.RESET + EnumChatFormatting.GRAY + " tokens remaining";
        drawCentered(remaining, panelTop + 26, accent);

        int barWidth = Math.max(8, panelRight - panelLeft - 32);
        drawBar(centerX - barWidth / 2, panelTop + 46, barWidth, 13,
                quota.remainingTokens, quota.dailyLimitTokens);
        long used = Math.max(0L, quota.dailyLimitTokens - Math.min(quota.dailyLimitTokens, quota.remainingTokens));
        String usedText = formatTokens(used) + " used";
        String limitText = "Daily limit " + formatTokens(quota.dailyLimitTokens);
        this.fontRendererObj.drawStringWithShadow(EnumChatFormatting.DARK_GRAY + usedText,
                panelLeft + 16, panelTop + 64, 0xFFFFFF);
        this.fontRendererObj.drawStringWithShadow(EnumChatFormatting.DARK_GRAY + limitText,
                panelRight - 16 - this.fontRendererObj.getStringWidth(limitText), panelTop + 64, 0xFFFFFF);

        drawCentered(EnumChatFormatting.AQUA + resetText(quota.resetAt), panelTop + 82, 0xFFFFFF);
        GoStatsRelayClient.refreshUsageAsync(false);
    }

    private void drawBar(int x, int y, int width, int height, long remaining, long limit) {
        Gui.drawRect(x - 1, y - 1, x + width + 1, y + height + 1, 0xFF11161D);
        Gui.drawRect(x, y, x + width, y + height, 0xFF29323B);
        if (limit <= 0L || width <= 4 || height <= 4) return;
        double ratio = Math.max(0.0D, Math.min(1.0D, (double) remaining / (double) limit));
        int innerWidth = width - 4;
        int fill = (int) Math.round(innerWidth * ratio);
        if (fill <= 0) return;
        int color = quotaColor(ratio);
        Gui.drawRect(x + 2, y + 2, x + 2 + fill, y + height - 2, color);
        Gui.drawRect(x + 2, y + 2, x + 2 + fill, y + 3, brighten(color));
    }

    private static int quotaColor(double ratio) {
        if (ratio > 0.5D) return 0xFF38D985;
        if (ratio > 0.15D) return 0xFFFFC04D;
        return 0xFFFF6268;
    }

    private static String quotaFormatting(double ratio) {
        if (ratio > 0.5D) return EnumChatFormatting.GREEN.toString();
        if (ratio > 0.15D) return EnumChatFormatting.YELLOW.toString();
        return EnumChatFormatting.RED.toString();
    }

    private static int brighten(int color) {
        int red = Math.min(255, ((color >> 16) & 0xFF) + 30);
        int green = Math.min(255, ((color >> 8) & 0xFF) + 30);
        int blue = Math.min(255, (color & 0xFF) + 30);
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }

    private void drawCentered(String text, int y, int color) {
        this.fontRendererObj.drawStringWithShadow(text,
                this.width / 2 - this.fontRendererObj.getStringWidth(text) / 2, y, color);
    }

    private static String formatTokens(long tokens) {
        return NumberFormat.getIntegerInstance(Locale.US).format(Math.max(0L, tokens));
    }

    private static String resetText(long resetAt) {
        if (resetAt <= 0L) return "Daily reset: 00:00 UTC";
        long minutes = Math.max(0L, (resetAt - System.currentTimeMillis()) / 60_000L);
        long hours = minutes / 60L;
        long remainder = minutes % 60L;
        return "Daily reset: 00:00 UTC (in " + hours + "h " + remainder + "m)";
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 0:
                toggleGoStats();
                break;
            case 2:
                showHelp();
                break;
            case 3:
                GomodMenuTransitions.openMainMenuWithoutAnimation(this.mc);
                break;
            default:
                break;
        }
    }

    private void toggleGoStats() {
        this.goStatsEnabled = !this.goStatsEnabled;
        ConfigManager.setGoStatsEnabled(this.goStatsEnabled);
        AIChatHandler.setEnabled(this.goStatsEnabled);
        this.toggleButton.displayString = "gostats123 AI: " + (this.goStatsEnabled
                ? EnumChatFormatting.GREEN + "Enabled" : EnumChatFormatting.RED + "Disabled");
        enabledOverlays.onToggled("main", this.goStatsEnabled);
    }

    private void showHelp() {
        String[] messages = new String[]{
                EnumChatFormatting.BLUE + "=== gostats123 AI Help ===",
                EnumChatFormatting.YELLOW + "Ask game questions by including @gostats123 in chat.",
                EnumChatFormatting.WHITE + "Example: @gostats123 should I rush or wait?",
                EnumChatFormatting.WHITE + "Nearby player positions, health, and equipment can be included as game context.",
                EnumChatFormatting.YELLOW + "Daily token usage resets at 00:00 UTC."
        };
        if (this.mc != null && this.mc.thePlayer != null) {
            for (String message : messages) {
                this.mc.thePlayer.addChatMessage((IChatComponent) new ChatComponentText(message));
            }
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
