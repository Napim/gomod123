package me.napim.gomod.gui;

import net.minecraft.client.gui.GuiButton;

import java.util.ArrayList;
import java.util.List;

/**
 * Green overlay on toggles in module settings GUIs (not Map Queuer map picks).
 * Use {@link #trackModuleToggle} for master on/off; use {@link #trackFeatureToggle} for independent features (FOV, glow, trail).
 */
public final class ModuleSettingsEnabledOverlays {

    private static final class Entry {
        final String key;
        final GuiButton button;
        final EnabledCheck check;

        Entry(String key, GuiButton button, EnabledCheck check) {
            this.key = key;
            this.button = button;
            this.check = check;
        }
    }

    public interface EnabledCheck {
        boolean isOn();
    }

    private final GomodEnabledButtonOverlay overlay = new GomodEnabledButtonOverlay();
    private final List<Entry> entries = new ArrayList<Entry>();

    /** Master module toggle (e.g. Inventory HUD "Toggle mod", Perspective "Advanced perspective"). */
    public void trackModuleToggle(String key, GuiButton button, EnabledCheck enabled) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            if ((key == null && entry.key == null) || (key != null && key.equals(entry.key))) {
                entries.remove(i);
            }
        }
        entries.add(new Entry(key, button, enabled));
    }

    /** Independent feature toggle (e.g. Custom FOV, skin glow) — same overlay when that setting is on. */
    public void trackFeatureToggle(String key, GuiButton button, EnabledCheck enabled) {
        trackModuleToggle(key, button, enabled);
    }

    /** Clears tracked toggles (call before re-registering in {@code initGui}). */
    public void clear() {
        entries.clear();
    }

    public void drawAll() {
        for (Entry entry : entries) {
            overlay.draw(entry.key, entry.button, entry.check.isOn());
        }
    }

    public void drawAllFaded(int visibleTop, int visibleBottom, int scrollOffset, int maxScroll) {
        if (maxScroll <= 0) {
            drawAll();
            return;
        }
        for (Entry entry : entries) {
            GuiButton button = entry.button;
            if (button == null || !button.visible
                    || !GuiScrollFade.intersectsVisible(button.yPosition, button.height, visibleTop, visibleBottom)) {
                continue;
            }
            float alpha = GuiScrollFade.alphaForY(button.yPosition + button.height / 2,
                    visibleTop, visibleBottom, scrollOffset, maxScroll);
            overlay.draw(entry.key, button, entry.check.isOn(), alpha);
        }
    }

    public void onToggled(String key, boolean nowOn) {
        if (nowOn) {
            overlay.startAnimation(key);
        } else {
            overlay.clearAnimation(key);
        }
    }
}
