package me.napim.gomod.gui;

import me.napim.gomod.features.GomodProfileManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Mouse;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A dedicated profile-management GUI: browse, switch, save and delete named gomod profiles.
 * API keys (OpenAI / Hypixel / Discord) are included in the saved snapshot.
 */
public class ProfilesGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {

    // ── layout constants ──────────────────────────────────────────────────────
    private static final int PANEL_W      = 320;
    private static final int ROW_H        = 20;
    private static final int VISIBLE_ROWS = 6;
    private static final int LIST_H       = VISIBLE_ROWS * ROW_H;   // 120 px
    private static final long DOUBLE_CLICK_MS = 350L;
    /*
     * Vertical layout inside the panel (all relative to panelY):
     *  + 0   top accent
     *  + 8   title
     *  + 20  header divider
     *  + 24  "Active: ..." label
     *  + 36  list starts  (LIST_H = 120)
     *  + 156 list ends
     *  + 160 input-area divider
     *  + 164 "Profile name:" label
     *  + 174 text field  (h=20)
     *  + 198 button row  (h=18)
     *  + 220 back button (h=18)
     *  + 238 feedback text
     *  + 248 panel bottom
     */
    private static final int PANEL_H = 290;

    // ── state ─────────────────────────────────────────────────────────────────
    private final GuiScreen parent;
    private List<String> profiles;
    private String activeProfile;
    private int selectedIndex = -1;
    private int scrollOffset  = 0;

    private GuiTextField newNameField;
    private GuiButton    switchBtn;
    private GuiButton    saveBtn;
    private GuiButton    deleteBtn;
    private GuiButton    newBtn;
    private GuiButton    duplicateBtn;
    private GuiButton    renameBtn;
    private GuiButton    backBtn;

    private String feedbackMsg   = "";
    private int    feedbackTicks = 0;
    private int    lastClickedProfileIndex = -1;
    private long   lastProfileClickMs = 0L;

    // absolute bounds of the profile list (set in initGui)
    private int listX, listY, listW;

    // ── constructor ───────────────────────────────────────────────────────────
    public ProfilesGUI(GuiScreen parent) {
        this.parent = parent;
    }

    // ── helpers ───────────────────────────────────────────────────────────────
    private void refreshProfiles() {
        profiles      = GomodProfileManager.listProfileNames();
        activeProfile = GomodProfileManager.getActiveProfileName();
        if (selectedIndex >= profiles.size()) selectedIndex = profiles.size() - 1;
    }

    private void switchToSelectedProfile() {
        if (selectedIndex < 0 || selectedIndex >= profiles.size()) {
            setFeedback(EnumChatFormatting.YELLOW + "Select a profile from the list first.", 120);
            return;
        }
        try {
            String name = profiles.get(selectedIndex);
            GomodProfileManager.switchToProfile(name);
            setFeedback(EnumChatFormatting.GREEN + "Switched to \u00AB" + name + "\u00BB", 160);
            refreshProfiles();
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Switch failed.";
            setFeedback(EnumChatFormatting.RED + msg, 180);
            GomodProfileManager.notifySwitchFailure(msg);
        }
    }

    // ── init ──────────────────────────────────────────────────────────────────
    @Override
    public void initGui() {
        super.initGui();
        refreshProfiles();

        ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
        int px = (sr.getScaledWidth()  - PANEL_W) / 2;
        int py = (sr.getScaledHeight() - PANEL_H) / 2;

        listX = px + 8;
        listY = py + 36;
        listW = PANEL_W - 16;

        int inputLabelY = listY + LIST_H + 8;
        int inputY      = inputLabelY + 12;   // text field starts below label
        int btnRow1Y    = inputY + 26;
        int btnRow2Y    = btnRow1Y + 24;
        int backY       = btnRow2Y + 26;
        int cx      = px + PANEL_W / 2;

        int nameW = Math.min(190, listW);
        newNameField = new GuiTextField(0, fontRendererObj, cx - nameW / 2, inputY, nameW, 20);
        newNameField.setMaxStringLength(40);
        newNameField.setCanLoseFocus(true);

        int bW       = 92;
        int bH       = 20;
        int gap      = 4;
        int totalW   = bW * 3 + gap * 2;
        int bStartX  = cx - totalW / 2;
        switchBtn = new GuiButton(1, bStartX,                btnRow1Y, bW, bH,
                EnumChatFormatting.AQUA  + "Switch");
        duplicateBtn = new GuiButton(4, bStartX + bW + gap,      btnRow1Y, bW, bH,
                EnumChatFormatting.YELLOW + "Duplicate");
        renameBtn = new GuiButton(5, bStartX + bW*2 + gap*2,  btnRow1Y, bW, bH,
                EnumChatFormatting.AQUA   + "Rename");

        newBtn    = new GuiButton(6, bStartX,                btnRow2Y, bW, bH,
                EnumChatFormatting.GREEN + "New");
        saveBtn   = new GuiButton(2, bStartX + bW + gap,      btnRow2Y, bW, bH,
                EnumChatFormatting.GREEN + "Save");
        deleteBtn = new GuiButton(3, bStartX + bW*2 + gap*2,  btnRow2Y, bW, bH,
                EnumChatFormatting.RED   + "Delete");
        backBtn   = new GuiButton(0, cx - 44, backY, 88, bH,
                EnumChatFormatting.GRAY  + "Back");

        buttonList.add(switchBtn);
        buttonList.add(duplicateBtn);
        buttonList.add(renameBtn);
        buttonList.add(newBtn);
        buttonList.add(saveBtn);
        buttonList.add(deleteBtn);
        buttonList.add(backBtn);
    }

    // ── draw ──────────────────────────────────────────────────────────────────
    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);

        ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
        int px = (sr.getScaledWidth()  - PANEL_W) / 2;
        int py = (sr.getScaledHeight() - PANEL_H) / 2;
        int cx = px + PANEL_W / 2;

        // keep hitboxes in sync with current centering
        listX = px + 8;
        listY = py + 36;
        listW = PANEL_W - 16;

        // keep controls in sync with current centering
        int inputLabelY = listY + LIST_H + 8;
        int inputY      = inputLabelY + 12;
        int btnRow1Y    = inputY + 26;
        int btnRow2Y    = btnRow1Y + 24;
        int backY       = btnRow2Y + 26;

        if (newNameField != null) {
            int nameW = newNameField.width;
            newNameField.xPosition = cx - nameW / 2;
            newNameField.yPosition = inputY;
        }

        int bW = 92;
        int gap = 4;
        int totalW = bW * 3 + gap * 2;
        int bStartX = cx - totalW / 2;
        if (switchBtn != null)   { switchBtn.xPosition = bStartX;                 switchBtn.yPosition = btnRow1Y; }
        if (duplicateBtn != null){ duplicateBtn.xPosition = bStartX + bW + gap;   duplicateBtn.yPosition = btnRow1Y; }
        if (renameBtn != null)   { renameBtn.xPosition = bStartX + bW*2 + gap*2;  renameBtn.yPosition = btnRow1Y; }
        if (newBtn != null)      { newBtn.xPosition = bStartX;                    newBtn.yPosition = btnRow2Y; }
        if (saveBtn != null)     { saveBtn.xPosition = bStartX + bW + gap;        saveBtn.yPosition = btnRow2Y; }
        if (deleteBtn != null)   { deleteBtn.xPosition = bStartX + bW*2 + gap*2;  deleteBtn.yPosition = btnRow2Y; }
        if (backBtn != null)     { backBtn.xPosition = cx - backBtn.width / 2;    backBtn.yPosition = backY; }

        // ── panel ─────────────────────────────────────────────────────────────
        drawRect(px, py, px + PANEL_W, py + PANEL_H, 0xD0101018);
        // subtle border (no bright blue accents)
        drawRect(px, py,               px + PANEL_W, py + 1,           0xFF2A2A35);
        drawRect(px, py + PANEL_H - 1, px + PANEL_W, py + PANEL_H,     0xFF2A2A35);
        drawRect(px,               py, px + 1,        py + PANEL_H,     0xFF2A2A35);
        drawRect(px + PANEL_W - 1, py, px + PANEL_W,  py + PANEL_H,    0xFF2A2A35);

        // ── header (screen-centered like TAB QoL Settings / other module GUIs) ─
        int screenW = sr.getScaledWidth();
        String title = EnumChatFormatting.AQUA + "gomod" + EnumChatFormatting.WHITE + " Profiles";
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, screenW, py + 8, 0xFFFFFF);
        drawRect(px + 8, py + 20, px + PANEL_W - 8, py + 21, 0xFF2A2A35);
        String activeLine = EnumChatFormatting.GRAY + "Active: "
                + EnumChatFormatting.GREEN + EnumChatFormatting.BOLD + activeProfile;
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, activeLine, screenW, py + 24, 0xFFFFFF);

        // ── list border ───────────────────────────────────────────────────────
        drawRect(listX, listY, listX + listW, listY + LIST_H, 0xFF080810);
        drawRect(listX, listY,              listX + listW, listY + 1,      0xFF2A2A35);
        drawRect(listX, listY + LIST_H - 1, listX + listW, listY + LIST_H, 0xFF2A2A35);

        // ── profile rows ──────────────────────────────────────────────────────
        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int idx = i + scrollOffset;
            if (idx >= profiles.size()) break;

            String  name       = profiles.get(idx);
            int     rowY       = listY + i * ROW_H;
            boolean isActive   = name.equals(activeProfile);
            boolean isSelected = idx == selectedIndex;
            boolean hovered    = mouseX >= listX && mouseX < listX + listW
                                 && mouseY >= rowY && mouseY < rowY + ROW_H;

            int bg;
            if      (isSelected && isActive) bg = 0xFF154A20;
            else if (isSelected)             bg = 0xFF1A2C50;
            else if (hovered && isActive)    bg = 0xFF1A3A1A;
            else if (hovered)                bg = 0xFF202030;
            else if (isActive)               bg = 0xFF0F2A14;
            else                             bg = (i % 2 == 0) ? 0xFF111118 : 0xFF0E0E16;

            drawRect(listX, rowY, listX + listW, rowY + ROW_H,     bg);
            drawRect(listX, rowY + ROW_H - 1, listX + listW, rowY + ROW_H, 0xFF1A1A2A);

            String nameColor = isActive  ? EnumChatFormatting.GREEN + "" + EnumChatFormatting.BOLD
                             : isSelected ? EnumChatFormatting.WHITE + ""
                             : EnumChatFormatting.GRAY + "";
            fontRendererObj.drawStringWithShadow(nameColor + name,
                    listX + 5, rowY + (ROW_H - 8) / 2, 0xFFFFFF);

            if (isActive) {
                String tag  = EnumChatFormatting.DARK_GREEN + "[active]";
                int    tagW = fontRendererObj.getStringWidth(tag);
                fontRendererObj.drawStringWithShadow(tag,
                        listX + listW - tagW - 5, rowY + (ROW_H - 8) / 2, 0xFFFFFF);
            }
        }

        // ── scrollbar ─────────────────────────────────────────────────────────
        if (profiles.size() > VISIBLE_ROWS) {
            int sbX   = listX + listW - 4;
            int max   = profiles.size() - VISIBLE_ROWS;
            int thumbH = Math.max(12, LIST_H * VISIBLE_ROWS / profiles.size());
            int thumbY = listY + (LIST_H - thumbH) * scrollOffset / Math.max(1, max);
            drawRect(sbX, listY,   sbX + 4, listY + LIST_H, 0xFF111118);
            drawRect(sbX, thumbY,  sbX + 4, thumbY + thumbH, 0xFF3A3A46);
        }

        // ── empty state ───────────────────────────────────────────────────────
        if (profiles.isEmpty()) {
            drawCenteredString(fontRendererObj,
                    EnumChatFormatting.DARK_GRAY + "No profiles yet \u2014 type a name below and Save.",
                    cx, listY + LIST_H / 2 - 4, 0xFFFFFF);
        }

        // ── input area ────────────────────────────────────────────────────────
        drawCenteredString(fontRendererObj,
                EnumChatFormatting.DARK_GRAY + "Profile name (for Save):",
                cx, inputLabelY, 0xFFFFFF);

        newNameField.drawTextBox();

        super.drawScreen(mouseX, mouseY, partialTicks);

        // ── feedback (draw last so buttons never cover it) ────────────────────
        if (feedbackTicks > 0 && feedbackMsg != null && !feedbackMsg.isEmpty()) {
            int y = backBtn != null ? (backBtn.yPosition + backBtn.height + 4) : (py + PANEL_H - 12);
            int maxY = py + PANEL_H - 12;
            if (y > maxY) y = maxY;

            int maxWidth = PANEL_W - 16;
            List<String> lines;
            try {
                lines = fontRendererObj.listFormattedStringToWidth(feedbackMsg, maxWidth);
            } catch (Throwable t) {
                lines = new ArrayList<String>();
                lines.add(feedbackMsg);
            }
            if (lines == null) lines = Collections.singletonList(feedbackMsg);

            // clamp to at most 2 lines to avoid pushing off-screen
            int count = Math.min(2, lines.size());
            for (int i = 0; i < count; i++) {
                String line = lines.get(i);
                if (line == null) continue;
                int x = cx - fontRendererObj.getStringWidth(line) / 2;
                fontRendererObj.drawStringWithShadow(line, x, y + i * 10, 0xFFFFFF);
            }
        }
    }

    // ── input ─────────────────────────────────────────────────────────────────
    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        newNameField.mouseClicked(mouseX, mouseY, mouseButton);

        // row click
        if (mouseX >= listX && mouseX < listX + listW
                && mouseY >= listY && mouseY < listY + LIST_H) {
            int row = (mouseY - listY) / ROW_H;
            int idx = row + scrollOffset;
            if (idx >= 0 && idx < profiles.size()) {
                long now = System.currentTimeMillis();
                boolean doubleClicked = mouseButton == 0
                        && idx == lastClickedProfileIndex
                        && now - lastProfileClickMs <= DOUBLE_CLICK_MS;
                selectedIndex = idx;
                newNameField.setText(profiles.get(idx));
                lastClickedProfileIndex = idx;
                lastProfileClickMs = now;
                if (doubleClicked) {
                    switchToSelectedProfile();
                }
            }
        }
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            int max = Math.max(0, profiles.size() - VISIBLE_ROWS);
            scrollOffset = wheel > 0
                    ? Math.max(0,   scrollOffset - 1)
                    : Math.min(max, scrollOffset + 1);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        if (newNameField.isFocused()) {
            newNameField.textboxKeyTyped(typedChar, keyCode);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        newNameField.updateCursorCounter();
        if (feedbackTicks > 0) feedbackTicks--;
    }

    // ── button actions ────────────────────────────────────────────────────────
    @Override
    protected void actionPerformed(GuiButton button) {
        String rawName = newNameField.getText().trim();
        switch (button.id) {

            case 0: // Back
                mc.displayGuiScreen(parent);
                break;

            case 1: // Switch to selected
                switchToSelectedProfile();
                break;

            case 2: // Save / Create
                if (rawName.isEmpty()) {
                    setFeedback(EnumChatFormatting.YELLOW + "Type a profile name first.", 120);
                    break;
                }
                try {
                    String sn = GomodProfileManager.sanitizeProfileName(rawName);
                    if (sn == null) {
                        setFeedback(EnumChatFormatting.RED + "Invalid name. Use letters (A–Z, a–z), numbers, spaces.", 180);
                        break;
                    }
                    GomodProfileManager.saveCurrentConfigIntoProfile(rawName);
                    newNameField.setText(sn);
                    setFeedback(EnumChatFormatting.GREEN + "Saved \u00AB" + sn + "\u00BB (API keys included).", 200);
                    refreshProfiles();
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : "Save failed.";
                    setFeedback(EnumChatFormatting.RED + msg, 180);
                }
                break;

            case 3: // Delete selected
                if (selectedIndex < 0 || selectedIndex >= profiles.size()) {
                    setFeedback(EnumChatFormatting.YELLOW + "Select a profile to delete.", 120);
                    break;
                }
                String toDelete = profiles.get(selectedIndex);
                if (profiles.size() <= 1) {
                    setFeedback(EnumChatFormatting.RED + "Cannot delete the last profile.", 160);
                    break;
                }
                if (toDelete.equals(activeProfile)) {
                    setFeedback(EnumChatFormatting.RED + "Cannot delete the active profile.", 160);
                    break;
                }
                try {
                    File f = GomodProfileManager.profileFile(toDelete);
                    if (f.isFile()) f.delete();
                    setFeedback(EnumChatFormatting.GREEN + "Deleted \u00AB" + toDelete + "\u00BB.", 160);
                    selectedIndex = -1;
                    newNameField.setText("");
                    refreshProfiles();
                } catch (Exception e) {
                    setFeedback(EnumChatFormatting.RED + "Delete failed: " + e.getMessage(), 160);
                }
                break;

            case 6: // New (defaults, empty keys)
                if (rawName.isEmpty()) {
                    setFeedback(EnumChatFormatting.YELLOW + "Type a profile name first.", 120);
                    break;
                }
                try {
                    GomodProfileManager.createNewProfileFromCurrent(rawName);
                    String sn = GomodProfileManager.sanitizeProfileName(rawName);
                    if (sn == null) {
                        setFeedback(EnumChatFormatting.RED + "Invalid name after create.", 180);
                        break;
                    }
                    newNameField.setText(sn);
                    try {
                        GomodProfileManager.switchToProfile(sn);
                        setFeedback(EnumChatFormatting.GREEN + "Created \u00AB" + sn + "\u00BB and switched.", 200);
                        refreshProfiles();
                        int idx = profiles.indexOf(sn);
                        if (idx >= 0) {
                            selectedIndex = idx;
                            int maxScroll = Math.max(0, profiles.size() - VISIBLE_ROWS);
                            if (idx < scrollOffset) scrollOffset = idx;
                            else if (idx >= scrollOffset + VISIBLE_ROWS) scrollOffset = Math.min(maxScroll, idx - VISIBLE_ROWS + 1);
                        }
                    } catch (Exception switchEx) {
                        String sm = switchEx.getMessage() != null ? switchEx.getMessage() : "Switch failed.";
                        setFeedback(EnumChatFormatting.RED + "Created \u00AB" + sn + "\u00BB but could not switch: " + sm, 220);
                        GomodProfileManager.notifySwitchFailure(sm);
                        refreshProfiles();
                    }
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : "Create failed.";
                    setFeedback(EnumChatFormatting.RED + msg, 180);
                }
                break;

            case 4: // Duplicate selected → name in field
                if (selectedIndex < 0 || selectedIndex >= profiles.size()) {
                    setFeedback(EnumChatFormatting.YELLOW + "Select a profile to duplicate.", 120);
                    break;
                }
                if (rawName.isEmpty()) {
                    setFeedback(EnumChatFormatting.YELLOW + "Type the new profile name first.", 140);
                    break;
                }
                try {
                    String src = profiles.get(selectedIndex);
                    GomodProfileManager.duplicateProfile(src, rawName);
                    String sn = GomodProfileManager.sanitizeProfileName(rawName);
                    if (sn == null) {
                        setFeedback(EnumChatFormatting.RED + "Invalid name after duplicate.", 180);
                        break;
                    }
                    newNameField.setText(sn);
                    try {
                        GomodProfileManager.switchToProfile(sn);
                        setFeedback(EnumChatFormatting.GREEN + "Duplicated \"" + src + "\" → \u00AB" + sn + "\u00BB and switched.", 200);
                        refreshProfiles();
                        int idx = profiles.indexOf(sn);
                        if (idx >= 0) {
                            selectedIndex = idx;
                            int maxScroll = Math.max(0, profiles.size() - VISIBLE_ROWS);
                            if (idx < scrollOffset) scrollOffset = idx;
                            else if (idx >= scrollOffset + VISIBLE_ROWS) scrollOffset = Math.min(maxScroll, idx - VISIBLE_ROWS + 1);
                        }
                    } catch (Exception switchEx) {
                        String sm = switchEx.getMessage() != null ? switchEx.getMessage() : "Switch failed.";
                        setFeedback(EnumChatFormatting.RED + "Duplicated to \u00AB" + sn + "\u00BB but could not switch: " + sm, 220);
                        GomodProfileManager.notifySwitchFailure(sm);
                        refreshProfiles();
                    }
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : "Duplicate failed.";
                    setFeedback(EnumChatFormatting.RED + msg, 180);
                }
                break;

            case 5: // Rename selected → name in field
                if (selectedIndex < 0 || selectedIndex >= profiles.size()) {
                    setFeedback(EnumChatFormatting.YELLOW + "Select a profile to rename.", 120);
                    break;
                }
                if (rawName.isEmpty()) {
                    setFeedback(EnumChatFormatting.YELLOW + "Type the new name first.", 120);
                    break;
                }
                try {
                    String old = profiles.get(selectedIndex);
                    GomodProfileManager.renameProfile(old, rawName);
                    String sn = GomodProfileManager.sanitizeProfileName(rawName);
                    if (sn != null) newNameField.setText(sn);
                    setFeedback(EnumChatFormatting.GREEN + "Renamed \"" + old + "\" → \"" + (sn != null ? sn : rawName) + "\"", 200);
                    selectedIndex = -1;
                    refreshProfiles();
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : "Rename failed.";
                    setFeedback(EnumChatFormatting.RED + msg, 180);
                }
                break;
        }
    }

    private void setFeedback(String msg, int ticks) {
        feedbackMsg   = msg;
        feedbackTicks = ticks;
    }

    @Override
    public boolean shouldBlockGomodMenuMovementAndDragLook() {
        return GomodMenuKeyboardCaptureGuards.anyTextFieldFocused(newNameField);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
