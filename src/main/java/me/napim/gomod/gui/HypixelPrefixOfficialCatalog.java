package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.LocalDisguise;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.util.EnumChatFormatting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Scrollable Hypixel rank catalog shared by prefix settings and nick disguise GUIs. */
public final class HypixelPrefixOfficialCatalog {

    /** Gold/warm first, then aqua/blue, then the rest (official-style colors appear first). */
    public static final String[] MVP_PLUS_COLORS = {
            "GOLD", "YELLOW", "WHITE", "GRAY", "DARK_GRAY",
            "AQUA", "BLUE", "LIGHT_PURPLE", "DARK_PURPLE",
            "GREEN", "DARK_GREEN", "RED", "DARK_RED", "BLACK"
    };

    /** Hypixel API monthly / ++ palette keys (base × sign = full MVP++ style grid). */
    public static final String[] MVP_PLUS_PLUS_COLORS = {
            "GOLD", "DEFAULT", "AQUA", "DARK_AQUA", "DARK_PURPLE", "LIGHT_PURPLE", "RED", "GREEN",
            "YELLOW", "WHITE", "BLUE", "DARK_GREEN", "DARK_RED", "DARK_GRAY", "BLACK", "DARK_BLUE"
    };

    private static final String[] OFFICIAL_SPECIAL_AFTER_VIP_PLUS = {
            "YOUTUBER", "OWNER", "ADMIN", "GM", "MOJANG", "EVENTS", "INNIT", "PIG_PLUS_PLUS_PLUS"
    };

    private static List<Entry> catalogCache;

    private HypixelPrefixOfficialCatalog() {
    }

    public static final class Entry {
        final String preset;
        final String mvpBracketBase;
        final String mvpPlusSignColor;
        final String mvpPpBase;
        final String mvpPpSign;
        final String metaSuffix;

        Entry(String preset, String mvpBracketBase, String mvpPlusSignColor,
              String mvpPpBase, String mvpPpSign, String metaSuffix) {
            this.preset = preset;
            this.mvpBracketBase = mvpBracketBase;
            this.mvpPlusSignColor = mvpPlusSignColor;
            this.mvpPpBase = mvpPpBase;
            this.mvpPpSign = mvpPpSign;
            this.metaSuffix = metaSuffix;
        }

        public void apply() {
            ConfigManager.setDisguiseHypixelRankPreset(preset);
            if ("MVP".equals(preset) && mvpBracketBase != null) {
                ConfigManager.setDisguiseHypixelMvpBracketColor(mvpBracketBase);
            }
            if ("MVP_PLUS".equals(preset)) {
                if (mvpBracketBase != null) {
                    ConfigManager.setDisguiseHypixelMvpPlusBracketBaseColor(mvpBracketBase);
                }
                if (mvpPlusSignColor != null) {
                    ConfigManager.setDisguiseHypixelMvpPlusColor(mvpPlusSignColor);
                }
            }
            if ("MVP_PLUS_PLUS".equals(preset) && mvpPpBase != null && mvpPpSign != null) {
                ConfigManager.setDisguiseHypixelMvpPpBaseColor(mvpPpBase);
                ConfigManager.setDisguiseHypixelMvpPpSignColor(mvpPpSign);
            }
            LocalDisguise.refreshHypixelRankPresetFromConfig();
        }

        boolean matchesSelection() {
            if (!preset.equalsIgnoreCase(ConfigManager.getDisguiseHypixelRankPreset())) {
                return false;
            }
            if ("MVP".equals(preset)) {
                return mvpBracketBase != null && mvpBracketBase.equals(ConfigManager.getDisguiseHypixelMvpBracketColor());
            }
            if ("MVP_PLUS".equals(preset)) {
                return mvpBracketBase != null && mvpPlusSignColor != null
                        && mvpBracketBase.equals(ConfigManager.getDisguiseHypixelMvpPlusBracketBaseColor())
                        && mvpPlusSignColor.equals(ConfigManager.getDisguiseHypixelMvpPlusColor());
            }
            if ("MVP_PLUS_PLUS".equals(preset)) {
                return mvpPpBase != null && mvpPpSign != null
                        && mvpPpBase.equals(ConfigManager.getDisguiseHypixelMvpPpBaseColor())
                        && mvpPpSign.equals(ConfigManager.getDisguiseHypixelMvpPpSignColor());
            }
            return true;
        }

        String buildLine(String displayName) {
            String bracket = LocalDisguise.previewOfficialRankBracketForCatalog(
                    preset, mvpBracketBase, mvpPlusSignColor, mvpPpBase, mvpPpSign);
            String name = EnumChatFormatting.WHITE + displayName + EnumChatFormatting.RESET;
            if (bracket == null || bracket.isEmpty()) {
                return name + EnumChatFormatting.GRAY + metaSuffix;
            }
            return bracket + EnumChatFormatting.RESET + " " + name + EnumChatFormatting.GRAY + metaSuffix;
        }
    }

    public static List<Entry> getEntries() {
        if (catalogCache == null) {
            catalogCache = Collections.unmodifiableList(buildEntries());
        }
        return catalogCache;
    }

    public static void invalidateCache() {
        catalogCache = null;
    }

    private static List<Entry> buildEntries() {
        List<Entry> list = new ArrayList<>();
        list.add(new Entry("NONE", null, null, null, null, ""));
        list.add(new Entry("VIP", null, null, null, null, ""));
        list.add(new Entry("VIP_PLUS", null, null, null, null, ""));
        for (String p : OFFICIAL_SPECIAL_AFTER_VIP_PLUS) {
            list.add(new Entry(p, null, null, null, null, ""));
        }
        for (String base : MVP_PLUS_PLUS_COLORS) {
            list.add(new Entry("MVP", base, null, null, null, "  · " + base));
        }
        for (String base : MVP_PLUS_PLUS_COLORS) {
            for (String plus : MVP_PLUS_COLORS) {
                list.add(new Entry("MVP_PLUS", base, plus, null, null,
                        "  · " + base + "+" + plus));
            }
        }
        String[][] pairs = HypixelMvpPlusPlusStyleListGUI.getUniqueMvpPlusPlusPairs();
        for (String[] pair : pairs) {
            list.add(new Entry("MVP_PLUS_PLUS", null, null, pair[0], pair[1],
                    "  · " + pair[0] + "+" + pair[1]));
        }
        return list;
    }

    /** Mutable scroll panel bounds — layout then draw / click / wheel. */
    public static final class Panel {
        private int scrollOffset;
        private int panelLeft;
        private int panelRight;
        private int panelTop;
        private int panelBottom;
        private int listInnerLeft;
        private int firstRowTop;
        private int rowH;
        private int rowsVisible;
        private int maxScroll;

        public void resetScroll() {
            this.scrollOffset = 0;
        }

        private static final int FOOTER_RESERVE = 12;

        public void layout(int panelLeft, int panelTop, int panelWidth, int panelBottom) {
            this.panelLeft = panelLeft;
            this.panelRight = panelLeft + panelWidth;
            this.panelTop = panelTop;
            this.panelBottom = panelBottom;
            int innerTop = this.panelTop + 6;
            int innerBottom = this.panelBottom - 6 - FOOTER_RESERVE;
            this.listInnerLeft = this.panelLeft + 5;
            this.firstRowTop = innerTop;
            this.rowH = 11;
            int listH = Math.max(0, innerBottom - innerTop);
            this.rowsVisible = Math.max(6, listH / this.rowH);
            int n = getEntries().size();
            this.maxScroll = Math.max(0, n - this.rowsVisible);
            this.scrollOffset = Math.max(0, Math.min(this.scrollOffset, this.maxScroll));
        }

        public void draw(FontRenderer font, int mouseX, int mouseY, String displayName) {
            Gui.drawRect(this.panelLeft, this.panelTop, this.panelRight, this.panelBottom, 0x40FFFFFF);
            Gui.drawRect(this.panelLeft, this.panelTop, this.panelRight, this.panelTop + 1, 0x88A0A0A0);
            Gui.drawRect(this.panelLeft, this.panelBottom - 1, this.panelRight, this.panelBottom, 0x88A0A0A0);
            Gui.drawRect(this.panelLeft, this.panelTop, this.panelLeft + 1, this.panelBottom, 0x88A0A0A0);
            Gui.drawRect(this.panelRight - 1, this.panelTop, this.panelRight, this.panelBottom, 0x88A0A0A0);

            List<Entry> cat = getEntries();
            int end = Math.min(this.scrollOffset + this.rowsVisible, cat.size());
            for (int i = this.scrollOffset; i < end; i++) {
                int rowTop = this.firstRowTop + (i - this.scrollOffset) * this.rowH;
                Entry e = cat.get(i);
                boolean hover = mouseX >= this.panelLeft && mouseX <= this.panelRight
                        && mouseY >= rowTop && mouseY < rowTop + this.rowH;
                if (e.matchesSelection()) {
                    Gui.drawRect(this.panelLeft + 1, rowTop, this.panelRight - 1, rowTop + this.rowH, 0x3388FF88);
                } else if (hover) {
                    Gui.drawRect(this.panelLeft + 1, rowTop, this.panelRight - 1, rowTop + this.rowH, 0x33FFFFFF);
                }
                font.drawStringWithShadow(e.buildLine(displayName), this.listInnerLeft, rowTop + 2, 0xFFFFFF);
            }

            if (this.maxScroll > 0) {
                font.drawStringWithShadow(
                        EnumChatFormatting.DARK_GRAY + "(" + (this.scrollOffset + 1) + "–" + end + " / " + cat.size() + ")",
                        this.listInnerLeft, this.panelBottom - FOOTER_RESERVE + 2, 0x888888);
            }
        }

        public boolean handleClick(int mouseX, int mouseY) {
            List<Entry> cat = getEntries();
            int end = Math.min(this.scrollOffset + this.rowsVisible, cat.size());
            for (int i = this.scrollOffset; i < end; i++) {
                int rowTop = this.firstRowTop + (i - this.scrollOffset) * this.rowH;
                if (mouseY >= rowTop && mouseY < rowTop + this.rowH
                        && mouseX >= this.panelLeft && mouseX <= this.panelRight) {
                    cat.get(i).apply();
                    return true;
                }
            }
            return false;
        }

        public void handleWheel(int wheel) {
            if (wheel == 0) {
                return;
            }
            if (wheel > 0) {
                this.scrollOffset = Math.max(0, this.scrollOffset - 3);
            } else {
                this.scrollOffset = Math.min(this.maxScroll, this.scrollOffset + 3);
            }
        }
    }
}
