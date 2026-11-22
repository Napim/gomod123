package me.napim.gomod.gui;

import me.napim.gomod.features.BlitzVaultEnchantChanceRules;
import me.napim.gomod.features.BlitzVaultItemsData;
import me.napim.gomod.features.ConfigManager;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Configure which enchant rows appear in the Blitz Vault Items "Enchant Chances" panel.
 */
public final class BlitzVaultEnchantChancesGUI extends GuiScreen {

    private static final int COL_PANEL_BG = 0xC0121A12;
    private static final int COL_PANEL_BORDER = 0xFF2D6B2D;
    private static final int PAD = 12;
    private static final int ROW_H = 14;
    private static final int BTN_H = 20;
    private static final int FOOTER_H = 58;
    private static final int COMBO_BLOCK_H = 72;

    private final GuiScreen parent;
    private final Set<String> enabledKeys = new LinkedHashSet<String>();
    private final List<String> combos = new ArrayList<String>();

    private int panelLeft;
    private int panelTop;
    private int panelRight;
    private int panelBottom;
    private int ruleListTop;
    private int ruleListBottom;
    private int comboListTop;
    private int comboListBottom;

    private int ruleScroll;
    private int comboScroll;
    private boolean awaitingComboPick;
    private int comboPickA = -1;
    private int comboPickB = -1;

    public BlitzVaultEnchantChancesGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        enabledKeys.clear();
        enabledKeys.addAll(BlitzVaultEnchantChanceRules.resolveEnabledKeys());
        combos.clear();
        combos.addAll(ConfigManager.getBlitzVaultEnchantChanceCombos());

        panelLeft = Math.max(8, width / 2 - 170);
        panelRight = Math.min(width - 8, width / 2 + 170);
        panelTop = 36;
        panelBottom = height - FOOTER_H - 8;
        ruleListTop = panelTop + 52;
        ruleListBottom = panelBottom - COMBO_BLOCK_H;
        comboListTop = ruleListBottom + 28;
        comboListBottom = panelBottom - 6;

        buttonList.clear();
        int btnW = Math.min(156, panelRight - panelLeft - PAD * 2);
        int btnX = panelLeft + PAD;
        int toolbarY = panelTop + 28;
        buttonList.add(new GuiButton(1, btnX, toolbarY, btnW, BTN_H, modeLabel()));
        buttonList.add(new GuiButton(2, btnX + btnW + 8, toolbarY, btnW, BTN_H, "Reset defaults"));
        int footerY = height - FOOTER_H + 10;
        int halfW = (panelRight - panelLeft - PAD * 2 - 8) / 2;
        buttonList.add(new GuiButton(3, btnX, footerY, halfW, BTN_H, awaitingComboPick
                ? EnumChatFormatting.YELLOW + "Pick 2 rows above..."
                : "Add 2-enchant combo"));
        buttonList.add(new GuiButton(0, btnX + halfW + 8, footerY, halfW, BTN_H, "Done"));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawYellowBoldTitleCentered(fontRendererObj, "Enchant Chance Rows", width, 8);

        drawPanel(panelLeft, panelTop, panelRight, panelBottom);
        drawPanel(panelLeft + PAD, ruleListBottom + 4, panelRight - PAD, panelBottom - 4);

        int textX = panelLeft + PAD;
        fontRendererObj.drawStringWithShadow(EnumChatFormatting.GOLD + "Enchant rows", textX, panelTop + 8, 0xFFFFFFFF);
        String modeHint = ConfigManager.isBlitzVaultEnchantChanceExact()
                ? EnumChatFormatting.GRAY + "Exact = only that enchant level on the item."
                : EnumChatFormatting.GRAY + "At least+ = that level or higher (other enchants OK).";
        fontRendererObj.drawString(modeHint, textX, panelTop + 18, 0xFFFFFFFF);

        fontRendererObj.drawStringWithShadow(EnumChatFormatting.YELLOW + "Combos (item must match both)",
                textX + 8, ruleListBottom + 10, 0xFFFFFFFF);

        drawRuleList(mouseX, mouseY);
        drawComboList(mouseX, mouseY);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawPanel(int left, int top, int right, int bottom) {
        Gui.drawRect(left, top, right, bottom, COL_PANEL_BG);
        drawBorder(left, top, right, bottom, COL_PANEL_BORDER);
    }

    private void drawBorder(int left, int top, int right, int bottom, int color) {
        Gui.drawRect(left, top, right, top + 1, color);
        Gui.drawRect(left, bottom - 1, right, bottom, color);
        Gui.drawRect(left, top, left + 1, bottom, color);
        Gui.drawRect(right - 1, top, right, bottom, color);
    }

    private void drawMiniScrollbar(int trackX, int trackY, int trackW, int trackH, int scrollPx, int maxScrollPx) {
        if (maxScrollPx <= 0 || trackH <= 0) {
            return;
        }
        Gui.drawRect(trackX, trackY, trackX + trackW, trackY + trackH, 0x30FFFFFF);
        int thumbH = Math.max(10, (int) ((float) trackH * ((float) trackH / (float) (trackH + maxScrollPx))));
        thumbH = Math.min(thumbH, trackH);
        int travel = Math.max(1, trackH - thumbH);
        int thumbY = trackY + (int) ((float) travel * ((float) scrollPx / (float) maxScrollPx));
        thumbY = Math.min(trackY + trackH - thumbH, Math.max(trackY, thumbY));
        Gui.drawRect(trackX, thumbY, trackX + trackW, thumbY + thumbH, 0xEFFFFFFF);
    }

    private void drawRuleList(int mouseX, int mouseY) {
        List<BlitzVaultEnchantChanceRules.RuleDef> rules = BlitzVaultEnchantChanceRules.getAllRules();
        int maxScroll = Math.max(0, rules.size() * ROW_H - (ruleListBottom - ruleListTop));
        ruleScroll = clamp(ruleScroll, 0, maxScroll);
        int left = panelLeft + PAD + 4;
        int right = panelRight - PAD - 10;
        scissor(panelLeft + PAD, ruleListTop, Math.max(1, panelRight - panelLeft - PAD * 2 - 8),
                ruleListBottom - ruleListTop);
        int y = ruleListTop - ruleScroll;
        for (BlitzVaultEnchantChanceRules.RuleDef rule : rules) {
            if (y + ROW_H >= ruleListTop && y <= ruleListBottom) {
                boolean on = enabledKeys.contains(rule.key);
                boolean hover = mouseX >= left && mouseX <= right && mouseY >= y && mouseY < y + ROW_H;
                int bg = on ? (hover ? 0xE0377A37 : 0xC8265A26) : (hover ? 0xB03A463A : 0x80212821);
                int border = on ? 0xFF9BEE9B : 0xFF556855;
                Gui.drawRect(left, y + 1, right, y + ROW_H - 1, bg);
                drawBorder(left, y + 1, right, y + ROW_H - 1, border);
                String prefix = on ? EnumChatFormatting.GREEN + "On  " : EnumChatFormatting.DARK_GRAY + "Off  ";
                String label = rule.displayLabel(ConfigManager.isBlitzVaultEnchantChanceExact());
                fontRendererObj.drawString(trimToWidth(prefix + EnumChatFormatting.WHITE + label,
                                Math.max(20, right - left - 8)), left + 5, y + 3,
                        on ? 0xFFFFFFFF : 0xFFAAAAAA);
            }
            y += ROW_H;
        }
        unscissor();
        drawMiniScrollbar(panelRight - PAD - 6, ruleListTop, 3, ruleListBottom - ruleListTop, ruleScroll, maxScroll);
    }

    private void drawComboList(int mouseX, int mouseY) {
        int left = panelLeft + PAD + 8;
        int right = panelRight - PAD - 10;
        int maxScroll = Math.max(0, combos.size() * ROW_H - (comboListBottom - comboListTop));
        comboScroll = clamp(comboScroll, 0, maxScroll);
        scissor(panelLeft + PAD, comboListTop, Math.max(1, panelRight - panelLeft - PAD * 2 - 8),
                comboListBottom - comboListTop);
        int y = comboListTop - comboScroll;
        if (combos.isEmpty()) {
            fontRendererObj.drawString(EnumChatFormatting.DARK_GRAY + "No combos — use the button below.",
                    left, comboListTop + 4, 0xFF888888);
        }
        for (int i = 0; i < combos.size(); i++) {
            if (y + ROW_H >= comboListTop && y <= comboListBottom) {
                BlitzVaultEnchantChanceRules.ComboSpec spec = BlitzVaultEnchantChanceRules.ComboSpec.parse(combos.get(i));
                String text = spec == null ? combos.get(i) : spec.label;
                boolean hover = mouseX >= left && mouseX <= right && mouseY >= y && mouseY < y + ROW_H;
                Gui.drawRect(left, y + 1, right, y + ROW_H - 1, hover ? 0xC06A3030 : 0x80302020);
                drawBorder(left, y + 1, right, y + ROW_H - 1, hover ? 0xFFFF9A9A : 0xFF774545);
                String rowText = (hover ? EnumChatFormatting.RED : EnumChatFormatting.GRAY) + "[x] "
                        + EnumChatFormatting.WHITE + text;
                fontRendererObj.drawStringWithShadow(trimToWidth(rowText, Math.max(20, right - left - 8)),
                        left + 5, y + 3, 0xFFFFFFFF);
            }
            y += ROW_H;
        }
        unscissor();
        drawMiniScrollbar(panelRight - PAD - 6, comboListTop, 3, comboListBottom - comboListTop, comboScroll, maxScroll);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton == 0) {
            for (int i = buttonList.size() - 1; i >= 0; i--) {
                GuiButton btn = buttonList.get(i);
                if (btn.mousePressed(mc, mouseX, mouseY)) {
                    btn.playPressSound(mc.getSoundHandler());
                    actionPerformed(btn);
                    return;
                }
            }
            if (handleRuleListClick(mouseX, mouseY)) {
                return;
            }
            if (handleComboListClick(mouseX, mouseY)) {
                return;
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    private boolean handleRuleListClick(int mouseX, int mouseY) {
        if (mouseY < ruleListTop || mouseY > ruleListBottom) {
            return false;
        }
        int left = panelLeft + PAD;
        int right = panelRight - PAD;
        if (mouseX < left || mouseX > right) {
            return false;
        }
        List<BlitzVaultEnchantChanceRules.RuleDef> rules = BlitzVaultEnchantChanceRules.getAllRules();
        int y = ruleListTop - ruleScroll;
        for (BlitzVaultEnchantChanceRules.RuleDef rule : rules) {
            if (mouseY >= y && mouseY < y + ROW_H) {
                if (awaitingComboPick) {
                    handleComboPick(rule.key);
                } else {
                    if (enabledKeys.contains(rule.key)) {
                        enabledKeys.remove(rule.key);
                    } else {
                        enabledKeys.add(rule.key);
                    }
                    persistEnabled();
                }
                return true;
            }
            y += ROW_H;
        }
        return false;
    }

    private boolean handleComboListClick(int mouseX, int mouseY) {
        if (mouseY < comboListTop || mouseY > comboListBottom) {
            return false;
        }
        int left = panelLeft + PAD;
        int right = panelRight - PAD;
        if (mouseX < left || mouseX > right) {
            return false;
        }
        int y = comboListTop - comboScroll;
        for (int i = 0; i < combos.size(); i++) {
            if (mouseY >= y && mouseY < y + ROW_H) {
                combos.remove(i);
                persistCombos();
                return true;
            }
            y += ROW_H;
        }
        return false;
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = org.lwjgl.input.Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        int mx = org.lwjgl.input.Mouse.getEventX() * width / mc.displayWidth;
        int my = height - org.lwjgl.input.Mouse.getEventY() * height / mc.displayHeight - 1;
        int delta = wheel > 0 ? -ROW_H * 3 : ROW_H * 3;
        if (my >= ruleListTop && my <= ruleListBottom) {
            ruleScroll += delta;
        } else if (my >= comboListTop && my <= comboListBottom) {
            comboScroll += delta;
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == null) {
            return;
        }
        switch (button.id) {
            case 0:
                mc.displayGuiScreen(parent);
                return;
            case 1:
                ConfigManager.setBlitzVaultEnchantChanceExact(!ConfigManager.isBlitzVaultEnchantChanceExact());
                initGui();
                return;
            case 2:
                enabledKeys.clear();
                enabledKeys.addAll(BlitzVaultEnchantChanceRules.defaultEnabledKeySet());
                persistEnabled();
                return;
            case 3:
                awaitingComboPick = !awaitingComboPick;
                comboPickA = -1;
                comboPickB = -1;
                initGui();
                return;
            default:
                break;
        }
        super.actionPerformed(button);
    }

    private void handleComboPick(String key) {
        if (comboPickA < 0) {
            comboPickA = indexOfRule(key);
            if (!enabledKeys.contains(key)) {
                enabledKeys.add(key);
                persistEnabled();
            }
            return;
        }
        if (comboPickB < 0 && indexOfRule(key) != comboPickA) {
            comboPickB = indexOfRule(key);
            BlitzVaultEnchantChanceRules.RuleDef a = ruleAt(comboPickA);
            BlitzVaultEnchantChanceRules.RuleDef b = ruleAt(comboPickB);
            if (a != null && b != null) {
                if (!enabledKeys.contains(b.key)) {
                    enabledKeys.add(b.key);
                    persistEnabled();
                }
                combos.add(a.baseLabel + " + " + b.baseLabel + "|" + a.key + "," + b.key);
                persistCombos();
            }
            awaitingComboPick = false;
            comboPickA = -1;
            comboPickB = -1;
            initGui();
        }
    }

    private int indexOfRule(String key) {
        List<BlitzVaultEnchantChanceRules.RuleDef> rules = BlitzVaultEnchantChanceRules.getAllRules();
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).key.equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private BlitzVaultEnchantChanceRules.RuleDef ruleAt(int index) {
        List<BlitzVaultEnchantChanceRules.RuleDef> rules = BlitzVaultEnchantChanceRules.getAllRules();
        if (index < 0 || index >= rules.size()) {
            return null;
        }
        return rules.get(index);
    }

    private void persistEnabled() {
        ConfigManager.setBlitzVaultEnchantChanceEnabledKeys(new ArrayList<String>(enabledKeys));
        BlitzVaultItemsData.rebuildVaultEnchantChances();
    }

    private void persistCombos() {
        ConfigManager.setBlitzVaultEnchantChanceCombos(new ArrayList<String>(combos));
        BlitzVaultItemsData.rebuildVaultEnchantChances();
    }

    private String modeLabel() {
        return ConfigManager.isBlitzVaultEnchantChanceExact()
                ? "Mode: Exact level"
                : "Mode: At least level+";
    }

    private static void scissor(int x, int y, int w, int h) {
        try {
            int scale = new net.minecraft.client.gui.ScaledResolution(
                    net.minecraft.client.Minecraft.getMinecraft()).getScaleFactor();
            int sx = x * scale;
            int sy = net.minecraft.client.Minecraft.getMinecraft().displayHeight - (y + h) * scale;
            org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
            org.lwjgl.opengl.GL11.glScissor(sx, sy, Math.max(0, w * scale), Math.max(0, h * scale));
        } catch (Throwable ignored) {
        }
    }

    private static void unscissor() {
        try {
            org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
        } catch (Throwable ignored) {
        }
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : Math.min(v, max);
    }

    private String trimToWidth(String text, int maxWidth) {
        if (text == null || fontRendererObj.getStringWidth(text) <= maxWidth) {
            return text == null ? "" : text;
        }
        String suffix = "...";
        int suffixW = fontRendererObj.getStringWidth(suffix);
        String plain = text;
        while (!plain.isEmpty() && fontRendererObj.getStringWidth(plain) + suffixW > maxWidth) {
            plain = plain.substring(0, plain.length() - 1);
        }
        return plain + suffix;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
