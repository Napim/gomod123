package me.napim.gomod.gui;

import me.napim.gomod.command.DisguiseCommand;
import me.napim.gomod.features.LocalDisguise;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;

import java.io.IOException;

/**
 * Shown when nick disguise uses a name that exists on Mojang — full player disguise or offline UUID with the skin choice from nick setup.
 */
public class RealPlayerNickConflictGUI extends GuiScreen {
    private final GuiScreen disguiseMenuParent;
    /** What the user typed in nick setup (preserves casing / color codes). */
    private final String requestedNickRaw;
    private final String mojangName;
    private final String uuidUndashed;
    private final LocalDisguise.NickSkinMode pendingSkinMode;
    private final String pendingSkinRef;

    public RealPlayerNickConflictGUI(
            GuiScreen disguiseMenuParent,
            String requestedNickRaw,
            String mojangName,
            String uuidUndashed,
            LocalDisguise.NickSkinMode pendingSkinMode,
            String pendingSkinRef) {
        this.disguiseMenuParent = disguiseMenuParent;
        this.requestedNickRaw = requestedNickRaw == null ? "" : requestedNickRaw;
        this.mojangName = mojangName == null ? "" : mojangName;
        this.uuidUndashed = uuidUndashed == null ? "" : uuidUndashed.replace("-", "");
        this.pendingSkinMode = pendingSkinMode == null ? LocalDisguise.NickSkinMode.STEVE : pendingSkinMode;
        this.pendingSkinRef = pendingSkinRef == null ? "" : pendingSkinRef;
    }

    private String offlineOptionButtonLabel() {
        String ref = this.pendingSkinRef.trim();
        switch (this.pendingSkinMode) {
            case PLAYER_SKIN:
                if (!ref.isEmpty()) {
                    String shortRef = ref.length() > 10 ? ref.substring(0, 7) + "..." : ref;
                    return EnumChatFormatting.AQUA + "Name + " + shortRef + " skin";
                }
                break;
            case SKIN_FILE:
                if (!ref.isEmpty()) {
                    String shortName = ref;
                    int slash = Math.max(shortName.lastIndexOf('/'), shortName.lastIndexOf('\\'));
                    if (slash >= 0 && slash + 1 < shortName.length()) {
                        shortName = shortName.substring(slash + 1);
                    }
                    if (shortName.length() > 14) {
                        shortName = shortName.substring(0, 11) + "...";
                    }
                    return EnumChatFormatting.AQUA + "Name + " + shortName;
                }
                break;
            default:
                break;
        }
        return EnumChatFormatting.AQUA + "Name + default skin";
    }

    private String offlineOptionExplanationLine() {
        String ref = this.pendingSkinRef.trim();
        switch (this.pendingSkinMode) {
            case PLAYER_SKIN:
                if (!ref.isEmpty()) {
                    return EnumChatFormatting.DARK_GRAY + "Name + skin — that name offline with " + ref + "'s skin textures.";
                }
                break;
            case SKIN_FILE:
                if (!ref.isEmpty()) {
                    return EnumChatFormatting.DARK_GRAY + "Name + skin — that name offline with your chosen PNG.";
                }
                break;
            default:
                break;
        }
        return EnumChatFormatting.DARK_GRAY + "Name + default skin — that name with Steve skin (offline UUID).";
    }

    private LocalDisguise.NickSkinMode effectiveOfflineSkinMode() {
        switch (this.pendingSkinMode) {
            case PLAYER_SKIN:
                return this.pendingSkinRef.trim().isEmpty()
                        ? LocalDisguise.NickSkinMode.STEVE
                        : LocalDisguise.NickSkinMode.PLAYER_SKIN;
            case SKIN_FILE:
                return this.pendingSkinRef.trim().isEmpty()
                        ? LocalDisguise.NickSkinMode.STEVE
                        : LocalDisguise.NickSkinMode.SKIN_FILE;
            default:
                return LocalDisguise.NickSkinMode.STEVE;
        }
    }

    private String effectiveOfflineSkinRef() {
        LocalDisguise.NickSkinMode mode = effectiveOfflineSkinMode();
        if (mode == LocalDisguise.NickSkinMode.STEVE) {
            return "";
        }
        return this.pendingSkinRef.trim();
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        int cx = this.width / 2;
        int cy = this.height / 2;
        int bw = 130;
        this.buttonList.add(new GuiButton(1, cx - bw - 6, cy + 32, bw, 20, EnumChatFormatting.GREEN + "As player"));
        this.buttonList.add(new GuiButton(2, cx + 6, cy + 32, bw, 20, offlineOptionButtonLabel()));
        this.buttonList.add(new GuiButton(0, cx - 50, cy + 60, 100, 20, EnumChatFormatting.GRAY + "Cancel"));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        int cx = this.width / 2;
        int cy = this.height / 2;
        GuiHeader.drawTitleCenteredAtWithShadow(this.fontRendererObj,
                EnumChatFormatting.LIGHT_PURPLE.toString() + EnumChatFormatting.BOLD + "Real Mojang account",
                cx, cy - 86, 0xFFFFFF);
        GuiHeader.drawTitleCenteredAtWithShadow(this.fontRendererObj,
                EnumChatFormatting.GRAY + "This username is used by a player. Pick an option:",
                cx, cy - 72, 0xCCCCCC);
        String nameLine = EnumChatFormatting.WHITE + EnumChatFormatting.BOLD.toString() + this.mojangName;
        GuiHeader.drawTitleCenteredAtWithShadow(this.fontRendererObj, nameLine, cx, cy - 56, 0xFFFFFF);
        int head = 48;
        int hx = cx - head / 2;
        int hy = cy - 46;
        PlayerHeadTextureUtil.drawMinotarHead(this.uuidUndashed, hx, hy, head);
        this.drawCenteredString(this.fontRendererObj,
                EnumChatFormatting.DARK_GRAY + "As player — full disguise (skin + Hypixel data)." + EnumChatFormatting.RESET,
                cx, cy + 10, 0x888888);
        this.drawCenteredString(this.fontRendererObj, offlineOptionExplanationLine() + EnumChatFormatting.RESET,
                cx, cy + 22, 0x888888);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == null) {
            return;
        }
        if (button.id == 0) {
            GuiScreen back = this.disguiseMenuParent != null ? this.disguiseMenuParent : this.mc.currentScreen;
            this.mc.displayGuiScreen(new NickDisguiseSetupGUI(back));
            return;
        }
        if (button.id == 1) {
            DisguiseCommand.enableDisguiseAsync(this.mojangName.trim());
            this.mc.displayGuiScreen(this.disguiseMenuParent);
            return;
        }
        if (button.id == 2) {
            String nickInput = this.requestedNickRaw.trim();
            if (nickInput.isEmpty()) {
                nickInput = this.mojangName.trim();
            }
            DisguiseCommand.enableNickDisguiseAllowingTakenAsync(
                    nickInput,
                    effectiveOfflineSkinMode(),
                    effectiveOfflineSkinRef());
            this.mc.displayGuiScreen(this.disguiseMenuParent);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
