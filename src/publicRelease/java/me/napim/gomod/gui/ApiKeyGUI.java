package me.napim.gomod.gui;

import me.napim.gomod.features.ApiKeyManager;
import me.napim.gomod.features.HypixelApiHelper;
import me.napim.gomod.Main;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ChatComponentText;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import java.net.URI;
import java.awt.Desktop;

/** Public-release API key settings. */
public class ApiKeyGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {
    private GuiScreen parent;
    private GuiTextField hypixelField;
    private GuiButton saveButton;
    private GuiButton backButton;
    private GuiButton clearHypixelButton;
    private GuiButton revealHypixelButton;
    private GuiButton hypixelSiteButton;

    private String actualHypixelKey = "";

    private boolean hypixelRevealed = false;
    private boolean hypixelKeyCheckInProgress;
    private boolean keysLoadedFromStorage = false;

    public ApiKeyGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();

        int centerX = width / 2;
        int centerY = height / 2;
        int hypixelY = centerY - 62;
        int hypixelSiteY = centerY + 6;
        int saveY = centerY + 34;
        int backY = centerY + 62;

        if (!keysLoadedFromStorage) {
            String hypixelKey = ApiKeyManager.getApiKey("hypixel");
            actualHypixelKey = hypixelKey != null ? hypixelKey : "";
            keysLoadedFromStorage = true;
        }

        hypixelRevealed = false;

        hypixelField = new GuiTextField(1, fontRendererObj, centerX - 200, hypixelY, 400, 20);
        hypixelField.setMaxStringLength(100);
        hypixelField.setText(actualHypixelKey.isEmpty() ? "" : maskApiKey(actualHypixelKey));

        hypixelSiteButton = new IconCenterButton(8, centerX - 100, hypixelSiteY, 200, 20, "\u00A7b \u27A6", "\u00A7b developer.hypixel.net");
        saveButton = new GuiButton(2, centerX - 100, saveY, 200, 20, "Save API Key");
        backButton = new GuiButton(3, centerX - 100, backY, 200, 20, "Back to Menu");
        clearHypixelButton = new GuiButton(5, centerX + 210, hypixelY, 60, 20,
                EnumChatFormatting.RED + "Clear");
        revealHypixelButton = new GuiButton(7, centerX - 270, hypixelY, 60, 20, "Reveal");

        buttonList.add(hypixelSiteButton);
        buttonList.add(saveButton);
        buttonList.add(backButton);
        buttonList.add(clearHypixelButton);
        buttonList.add(revealHypixelButton);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        int centerX = width / 2;
        int centerY = height / 2;
        int hintBaseY = centerY + 96;

        GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj,
            EnumChatFormatting.BOLD.toString() + EnumChatFormatting.YELLOW.toString() + "API Key Management",
            centerX, centerY - 148, 0xFFFFFF);

        GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj,
            EnumChatFormatting.YELLOW.toString() + "Enter your Hypixel API key below:",
            centerX, centerY - 138, 0xFFFFFF);

        drawString(fontRendererObj,
            EnumChatFormatting.WHITE.toString() + "Hypixel API Key:",
            hypixelField.xPosition, hypixelField.yPosition - 13, 0xFFFFFF);

        drawCenteredString(fontRendererObj,
            EnumChatFormatting.GRAY.toString() + "Hypixel: https://developer.hypixel.net",
            centerX, hintBaseY + 2, 0xFFFFFF);
        drawCenteredString(fontRendererObj,
            EnumChatFormatting.GRAY.toString() + "Hypixel API Development Keys last 48 hours.",
            centerX, hintBaseY + 14, 0xFFFFFF);
        drawCenteredString(fontRendererObj,
            EnumChatFormatting.GRAY.toString() + "Regenerate them after they expire.",
            centerX, hintBaseY + 26, 0xFFFFFF);

        hypixelField.drawTextBox();

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        hypixelField.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        hypixelField.updateCursorCounter();
    }

    @Override
    public void actionPerformed(GuiButton button) {
        if (button == saveButton) {
            syncActualKeysFromFocusedFields();
            ApiKeyManager.setApiKey("hypixel", actualHypixelKey.isEmpty() ? null : actualHypixelKey);

            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "API keys saved successfully!");

            final String hypixelToCheck = actualHypixelKey == null ? "" : actualHypixelKey.trim();
            if (hypixelToCheck.isEmpty()) {
                Minecraft.getMinecraft().displayGuiScreen(parent);
            } else {
                startHypixelKeyValidation(hypixelToCheck);
            }

        } else if (button == backButton) {
            Minecraft.getMinecraft().displayGuiScreen(parent);

        } else if (button == hypixelSiteButton) {
            openHypixelDeveloperSite();

        } else if (button == clearHypixelButton) {
            actualHypixelKey = "";
            hypixelField.setText("");
            hypixelRevealed = false;
            revealHypixelButton.displayString = "Reveal";

        } else if (button == revealHypixelButton) {
            if (hypixelRevealed) {
                hypixelField.setText(actualHypixelKey.isEmpty() ? "" : maskApiKey(actualHypixelKey));
                revealHypixelButton.displayString = "Reveal";
                hypixelRevealed = false;
            } else {
                hypixelField.setText(actualHypixelKey);
                revealHypixelButton.displayString = "Hide";
                hypixelRevealed = true;
            }
        }
    }

    @Override
    public boolean shouldBlockGomodMenuMovementAndDragLook() {
        return GomodMenuKeyboardCaptureGuards.anyTextFieldFocused(hypixelField);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void syncActualKeysFromFocusedFields() {
        if (hypixelField != null && (hypixelRevealed || !hypixelField.getText().contains("****"))) {
            actualHypixelKey = hypixelField.getText();
        }
    }

    private void startHypixelKeyValidation(final String apiKey) {
        if (hypixelKeyCheckInProgress) {
            return;
        }
        final boolean previouslyValidSameKey = ApiKeyManager.wasHypixelKeyPreviouslyValidated(apiKey);
        hypixelKeyCheckInProgress = true;
        if (saveButton != null) {
            saveButton.enabled = false;
            saveButton.displayString = EnumChatFormatting.GRAY + "Checking Hypixel key...";
        }
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Checking Hypixel API key...");

        Thread checker = new Thread(() -> {
            final HypixelApiHelper.HypixelKeyCheck result = HypixelApiHelper.validateHypixelApiKey(apiKey);
            final Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                return;
            }
            mc.addScheduledTask(() -> {
                hypixelKeyCheckInProgress = false;
                if (saveButton != null) {
                    saveButton.enabled = true;
                    saveButton.displayString = "Save API Keys";
                }
                if (result.valid) {
                    ApiKeyManager.rememberHypixelKeyValidated(apiKey);
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                            + "Hypixel API key is valid.");
                } else if (result.checked) {
                    String detail = result.failureDetail == null ? "" : result.failureDetail.trim();
                    if (!detail.isEmpty() && detail.length() > 120) {
                        detail = detail.substring(0, 120) + "...";
                    }
                    if (result.unreachable) {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                                + "Could not verify Hypixel API key (network or Hypixel error)."
                                + (detail.isEmpty() ? "" : EnumChatFormatting.GRAY + " (" + detail + ")"));
                    } else {
                        if (previouslyValidSameKey) {
                            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                                    + "Hypixel API key appears to have expired. "
                                    + EnumChatFormatting.GRAY
                                    + "Hypixel API Development Keys last 48 hours and must be regenerated after they expire."
                                    + (detail.isEmpty() ? "" : " (" + detail + ")"));
                        } else {
                            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                                    + "Hypixel API key is invalid."
                                    + (detail.isEmpty() ? "" : EnumChatFormatting.GRAY + " (" + detail + ")"));
                        }
                    }
                }
                mc.displayGuiScreen(parent);
            });
        }, "gomod-hypixel-key-check");
        checker.setDaemon(true);
        checker.start();
    }

    private String maskApiKey(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }

        if (key.startsWith("sk-")) {
            if (key.length() <= 8) {
                return "sk-****";
            }
            return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
        }

        if (key.contains("-") && key.length() == 36) {
            return key.substring(0, 4) + "****-****-****-****-****" + key.substring(key.length() - 4);
        }

        if (key.length() <= 8) {
            return "****";
        }

        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        super.keyTyped(typedChar, keyCode);

        if (hypixelField.isFocused()) {
            hypixelField.textboxKeyTyped(typedChar, keyCode);
            String currentText = hypixelField.getText();
            if (hypixelRevealed || !currentText.contains("****")) {
                actualHypixelKey = currentText;
            }
        }
    }

    private void openHypixelDeveloperSite() {
        String url = "https://developer.hypixel.net";
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(url));
            } else {
                sendHypixelSiteChatLink(url);
            }
        } catch (Exception e) {
            sendHypixelSiteChatLink(url);
        }
    }

    private void sendHypixelSiteChatLink(String url) {
        ChatComponentText msg = new ChatComponentText(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Click to open Hypixel API site");
        msg.getChatStyle().setChatClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
        msg.getChatStyle().setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText(EnumChatFormatting.AQUA + url)));
        Minecraft.getMinecraft().thePlayer.addChatMessage(msg);
    }
}
