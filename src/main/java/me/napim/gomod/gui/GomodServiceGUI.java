package me.napim.gomod.gui;

import me.napim.gomod.GomodVersion;
import me.napim.gomod.Main;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.GomodKeybinds;
import me.napim.gomod.features.GomodFixes;
import me.napim.gomod.features.LunarConnectionDiagnostics;
import me.napim.gomod.features.LunarOnlineStatus;
import me.napim.gomod.shareinv.ShareInvConstants;
import me.napim.gomod.shareinv.ShareInvManager;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Relay, ShareInv help, Friend Inventory HUD, and gomod Social (friends/screenshots) — entry from {@link MiscellaneousGUI}.
 */
public class GomodServiceGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {
    private static final int ID_BACK = 0;
    private static final int ID_CONNECT = 1;
    private static final int ID_FRIEND_HUD = 2;
    private static final int ID_FRIENDS = 3;
    private static final int ID_SOCIAL_KEYS = 4;

    private static final int BTN_W = 200;
    private static final int BTN_H = 20;
    private static final int BTN_PAIR_GAP = GuiHeader.HUD_POSITION_BUTTON_PAIR_GAP_PX;

    private final GuiScreen parent;
    private GuiButton connectButton;
    private GuiButton friendHudButton;
    private GuiButton socialKeysButton;
    private boolean awaitingSocialKey;
    private final Set<Integer> capturedSocialKeys = new LinkedHashSet<Integer>();

    public GomodServiceGUI(GuiScreen parent) {
        this.parent = parent;
    }

    void refreshFriendHudRowText() {
        if (this.friendHudButton != null) {
            this.friendHudButton.displayString = getFriendHudButtonText();
        }
    }

    @Override
    public void initGui() {
        super.initGui();
        int cx = this.width / 2;
        int sh = this.height;
        int gapSmall = 6;
        int gapBeforeBack = 10;
        int stackH = BTN_H * 4 + gapSmall * 2 + gapBeforeBack;
        int headerBottom = 100;
        int y = headerBottom + Math.max(0, (sh - headerBottom - stackH) / 2);
        int rowLeft = cx - BTN_W / 2;
        int pairHalfW = (BTN_W - BTN_PAIR_GAP) / 2;
        this.buttonList.clear();
        this.connectButton = new GuiButton(ID_CONNECT, rowLeft, y, BTN_W, BTN_H, "");
        syncConnectButtonState();
        this.buttonList.add(this.connectButton);
        y += BTN_H + gapSmall;
        this.friendHudButton = new GuiButton(ID_FRIEND_HUD, rowLeft, y, BTN_W, BTN_H, getFriendHudButtonText());
        this.buttonList.add(this.friendHudButton);
        y += BTN_H + gapSmall;
        this.buttonList.add(new GuiButton(ID_FRIENDS, rowLeft, y, pairHalfW, BTN_H,
                EnumChatFormatting.AQUA + "gomod Social"));
        this.socialKeysButton = new GuiButton(ID_SOCIAL_KEYS, rowLeft + pairHalfW + BTN_PAIR_GAP, y, pairHalfW, BTN_H, "");
        this.buttonList.add(this.socialKeysButton);
        y += BTN_H + gapBeforeBack;
        this.buttonList.add(new GuiButton(ID_BACK, rowLeft, y, BTN_W, BTN_H,
                EnumChatFormatting.GRAY + "Back"));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        GuiHeader.drawTitleCenteredWithShadow(this.fontRendererObj,
                EnumChatFormatting.WHITE + "" + EnumChatFormatting.BOLD + "gomod Service",
                this.width, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        GuiHeader.drawTitleCenteredWithShadow(this.fontRendererObj,
                EnumChatFormatting.DARK_GRAY + "ShareInv relay, friends, screenshots"
                        + EnumChatFormatting.GRAY + " · " + GomodVersion.get(),
                this.width, GuiHeader.STANDARD_SUBTITLE_Y, 0xAAAAAA);

        boolean gomodOnline = ShareInvManager.isRelayOpen();
        LunarOnlineStatus.ConnectionState lunarState = LunarOnlineStatus.getLunarOnlineState();
        boolean fullAccessEnabled = ConfigManager.isLunarTweaksEnabled();
        LunarOnlineStatus.ConnectionState fullAccessState = LunarOnlineStatus.getFullAccessState();
        LunarConnectionDiagnostics.recordServiceSnapshot(gomodOnline,
                lunarState.name().toLowerCase(java.util.Locale.ROOT), fullAccessEnabled,
                fullAccessState.name().toLowerCase(java.util.Locale.ROOT));
        drawOnlineStatus("gomod Online", gomodOnline, 53);
        if (GomodFixes.isWeaveRuntime()) {
            drawLunarOnlineStatus(lunarState, 66);
            drawFullAccessStatus(fullAccessEnabled, fullAccessState, 79);
        }

        syncConnectButtonState();
        pollSocialChordCapture();

        super.drawScreen(mouseX, mouseY, partialTicks);

        if (this.socialKeysButton != null) {
            String label = awaitingSocialKey
                    ? EnumChatFormatting.YELLOW + getSocialCaptureLabel()
                    : getSocialKeysLabelText();
            GuiMarqueeButtonLabel.draw(this.fontRendererObj, this.socialKeysButton, mouseX, mouseY, label, false);
        }
        if (GomodFixes.isWeaveRuntime()) {
            drawLunarOnlineTooltip(mouseX, mouseY, lunarState, 66);
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case ID_BACK:
                awaitingSocialKey = false;
                this.mc.displayGuiScreen(this.parent);
                break;
            case ID_CONNECT: {
                if (button != null && !button.enabled) {
                    break;
                }
                String relay = ShareInvConstants.publicRelayTrimmed();
                if (relay.isEmpty()) {
                    relay = ShareInvConstants.GOMOD_HOSTED_RELAY_WSS;
                }
                ConfigManager.setShareInvRelayUrl(relay);
                ShareInvManager.setWantConnected(true);
                ShareInvManager.connect();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "ShareInv: saved relay and connecting…");
                break;
            }
            case ID_FRIEND_HUD:
                this.mc.displayGuiScreen(new FriendInventoryHUDGUI(this, false));
                break;
            case ID_FRIENDS:
                this.mc.displayGuiScreen(new GomodFriendsGUI(this));
                break;
            case ID_SOCIAL_KEYS:
                awaitingSocialKey = true;
                capturedSocialKeys.clear();
                break;
            default:
                break;
        }
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) throws IOException {
        if (awaitingSocialKey) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                awaitingSocialKey = false;
                capturedSocialKeys.clear();
                GomodKeybinds.applyGomodFriendsListKeyChord(0, 0);
            }
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public boolean shouldBlockGomodMenuMovementAndDragLook() {
        return awaitingSocialKey;
    }

    private void syncConnectButtonState() {
        if (this.connectButton == null) {
            return;
        }
        this.connectButton.enabled = !ShareInvManager.isRelayOpen() && !ShareInvManager.isWantConnected();
        this.connectButton.displayString = EnumChatFormatting.AQUA + "Connect to gomod Service";
    }

    private void drawOnlineStatus(String label, boolean connected, int y) {
        String state = connected
                ? EnumChatFormatting.GREEN + "Connected"
                : EnumChatFormatting.RED + "Disconnected";
        GomodSocialPresenceIconTextures.drawCenteredConnectionStatus(this.fontRendererObj,
                EnumChatFormatting.GRAY + label + ": " + state, this.width, y, connected);
    }

    private void drawLunarOnlineStatus(LunarOnlineStatus.ConnectionState connectionState, int y) {
        String state = connectionState == LunarOnlineStatus.ConnectionState.CONNECTED
                ? EnumChatFormatting.GREEN + "Connected"
                : connectionState == LunarOnlineStatus.ConnectionState.CONNECTING
                ? EnumChatFormatting.GOLD + "Connecting..."
                : EnumChatFormatting.RED + "Disconnected";
        String line = EnumChatFormatting.GRAY + "Lunar Online: " + state;
        if (connectionState == LunarOnlineStatus.ConnectionState.CONNECTED
                || connectionState == LunarOnlineStatus.ConnectionState.DISCONNECTED) {
            GomodSocialPresenceIconTextures.drawCenteredConnectionStatus(this.fontRendererObj,
                    line, this.width, y,
                    connectionState == LunarOnlineStatus.ConnectionState.CONNECTED);
        } else {
            GuiHeader.drawTitleCenteredWithShadow(this.fontRendererObj, line, this.width, y, 0xCCCCCC);
        }
    }

    private void drawLunarOnlineTooltip(int mouseX, int mouseY,
                                        LunarOnlineStatus.ConnectionState connectionState, int y) {
        if (connectionState != LunarOnlineStatus.ConnectionState.CONNECTING
                || mouseY < y - 5 || mouseY > y + 7) {
            return;
        }
        String label = "Lunar Online: Connecting...";
        int halfWidth = this.fontRendererObj.getStringWidth(label) / 2;
        if (mouseX < this.width / 2 - halfWidth || mouseX > this.width / 2 + halfWidth) {
            return;
        }
        drawHoveringText(Arrays.asList(
                EnumChatFormatting.AQUA + "Lunar Online is connecting",
                EnumChatFormatting.GRAY + LunarOnlineStatus.getLunarOnlineStatusMessage()),
                mouseX, Math.max(0, mouseY - 12));
    }

    private void drawFullAccessStatus(boolean enabled, LunarOnlineStatus.ConnectionState connectionState, int y) {
        String state;
        Boolean connected = null;
        if (!enabled) {
            state = EnumChatFormatting.GRAY + "Disabled";
        } else if (connectionState == LunarOnlineStatus.ConnectionState.DISCONNECTED) {
            state = EnumChatFormatting.RED + "Disconnected";
            connected = Boolean.FALSE;
        } else if (connectionState == LunarOnlineStatus.ConnectionState.CONNECTING) {
            state = EnumChatFormatting.GOLD + "Connecting...";
        } else {
            state = EnumChatFormatting.GREEN + "Connected";
            connected = Boolean.TRUE;
        }
        String line = EnumChatFormatting.GRAY + "Lunar FullAccess: " + state;
        if (connected != null) {
            GomodSocialPresenceIconTextures.drawCenteredConnectionStatus(this.fontRendererObj,
                    line, this.width, y, connected.booleanValue());
        } else {
            GuiHeader.drawTitleCenteredWithShadow(this.fontRendererObj, line, this.width, y, 0xCCCCCC);
        }
    }

    private String getFriendHudButtonText() {
        return EnumChatFormatting.GOLD + "Friend Inventory HUD";
    }

    private String getSocialKeysLabelText() {
        String binding = GomodKeybinds.formatSocialEffectiveBinding();
        if ("Unbound".equalsIgnoreCase(binding)) {
            binding = "NONE";
        }
        return EnumChatFormatting.WHITE + "Key(s): " + binding;
    }

    private void pollSocialChordCapture() {
        if (!awaitingSocialKey) {
            return;
        }
        for (int code = 1; code < Keyboard.KEYBOARD_SIZE && capturedSocialKeys.size() < 2; code++) {
            if (code != Keyboard.KEY_ESCAPE && Keyboard.isKeyDown(code)) {
                capturedSocialKeys.add(code);
            }
        }
        if (capturedSocialKeys.isEmpty()) {
            return;
        }
        for (Integer code : capturedSocialKeys) {
            if (code != null && Keyboard.isKeyDown(code.intValue())) {
                return;
            }
        }
        Integer[] keys = capturedSocialKeys.toArray(new Integer[0]);
        int first = keys.length > 0 ? keys[0].intValue() : 0;
        int second = keys.length > 1 ? keys[1].intValue() : 0;
        GomodKeybinds.applyGomodFriendsListKeyChord(first, second);
        capturedSocialKeys.clear();
        awaitingSocialKey = false;
    }

    private String getSocialCaptureLabel() {
        if (capturedSocialKeys.isEmpty()) {
            return "Hold 1 or 2 keys...";
        }
        StringBuilder label = new StringBuilder();
        for (Integer code : capturedSocialKeys) {
            if (label.length() > 0) {
                label.append(" + ");
            }
            label.append(GomodKeybinds.formatKeyName(code.intValue()).toUpperCase(java.util.Locale.ROOT));
        }
        return label.append(" (release to save)").toString();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
