package me.napim.gomod.features;

/** Small policy for identifying Lunar's custom TAB font-render window. */
public final class TabOverlayRenderScope {
    private TabOverlayRenderScope() {
    }

    /**
     * Vanilla opens an explicit overlay scope. Lunar's Weave path may instead render rows directly while the
     * player-list key is held, so use that key only as a Weave fallback and never while laying out chat text.
     */
    public static boolean isActive(boolean weaveRuntime, int renderDepth,
            boolean playerListKeyDown, boolean chatFontWorkActive) {
        if (chatFontWorkActive) {
            return false;
        }
        return renderDepth > 0 || weaveRuntime && playerListKeyDown;
    }

    /**
     * Name-only row matching is safe only while the TAB renderer itself is running. A held key also overlaps
     * world rendering (for example, the local player's F5 nametag), where a matching name is not proof that the
     * font draw belongs to a TAB row. If Lunar supplied a specific current row, callers can use that identity
     * directly without this fallback.
     */
    public static boolean canUseUnscopedTextFallback(boolean weaveRuntime, int renderDepth,
            boolean chatFontWorkActive) {
        return weaveRuntime && renderDepth > 0 && !chatFontWorkActive;
    }
}
