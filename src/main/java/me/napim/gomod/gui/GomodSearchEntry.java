package me.napim.gomod.gui;

import net.minecraft.client.gui.GuiScreen;

/**
 * One searchable item in the gomod settings search index.
 */
public final class GomodSearchEntry {

    public interface GuiFactory {
        GuiScreen create(GomodGUI gomodGui);
    }

    /** Label shown in the dropdown (plain text, no formatting codes). */
    public final String label;
    /** Breadcrumb, e.g. "Sidebar tweaks". */
    public final String parentLabel;
    /** Extra lowercase terms matched by search (aliases, abbreviations). */
    public final String keywords;
    public final GuiFactory factory;
    public final int highlightButtonId;
    public final Class<? extends GuiScreen> highlightGuiClass;

    GomodSearchEntry(String label, String parentLabel, String keywords, GuiFactory factory,
                     int highlightButtonId, Class<? extends GuiScreen> highlightGuiClass) {
        this.label = label;
        this.parentLabel = parentLabel;
        this.keywords = keywords != null ? keywords : "";
        this.factory = factory;
        this.highlightButtonId = highlightButtonId;
        this.highlightGuiClass = highlightGuiClass;
    }

    String searchableText() {
        return (label + " " + parentLabel + " " + keywords).toLowerCase();
    }
}
