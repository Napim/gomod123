package me.napim.gomod.features;

import net.minecraft.client.gui.FontRenderer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shared sizing/wrapping for gomod Social top-right message toasts. */
public final class SocialMessageToastLayout {

    public static final int PANEL_W = 200;
    public static final int PAD = 5;
    public static final int HEAD_SIZE = 24;
    public static final int FOOTER_H = 16;
    public static final int FOOTER_RAISE_PX = 3;
    public static final int BASE_PANEL_H = 52;
    /** Horizontal slide-in distance for top-right social toasts (px). */
    public static final int SLIDE_INTRO_PX = PANEL_W + 12;
    private static final int MAX_PREVIEW_LINES = 8;

    private SocialMessageToastLayout() {}

    public static int textAreaWidth() {
        return PANEL_W - (PAD + HEAD_SIZE + 6) - PAD;
    }

    public static String normalizePreview(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\n', ' ').replace('\r', ' ').trim();
    }

    public static List<String> wrapPreview(FontRenderer fr, String preview) {
        if (fr == null || preview == null || preview.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> wrapped = fr.listFormattedStringToWidth(preview, textAreaWidth());
        if (wrapped == null || wrapped.isEmpty()) {
            return Collections.emptyList();
        }
        if (wrapped.size() <= MAX_PREVIEW_LINES) {
            return wrapped;
        }
        List<String> capped = new ArrayList<String>(MAX_PREVIEW_LINES);
        for (int i = 0; i < MAX_PREVIEW_LINES - 1; i++) {
            capped.add(wrapped.get(i));
        }
        String last = wrapped.get(MAX_PREVIEW_LINES - 1);
        capped.add(trimWithEllipsis(fr, last));
        return capped;
    }

    public static int panelHeight(FontRenderer fr, String preview) {
        if (fr == null) {
            return BASE_PANEL_H;
        }
        List<String> lines = wrapPreview(fr, preview);
        int previewLines = Math.max(1, lines.isEmpty() ? 1 : lines.size());
        return BASE_PANEL_H + Math.max(0, previewLines - 1) * fr.FONT_HEIGHT;
    }

    private static String trimWithEllipsis(FontRenderer fr, String line) {
        String ellipsis = "\u2026";
        String plain = line == null ? "" : line;
        int maxW = textAreaWidth();
        if (fr.getStringWidth(plain) <= maxW) {
            return plain;
        }
        while (plain.length() > 1 && fr.getStringWidth(plain + ellipsis) > maxW) {
            plain = plain.substring(0, plain.length() - 1);
        }
        return plain + ellipsis;
    }
}
