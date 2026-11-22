package me.napim.gomod.mixins;

import com.google.common.collect.Ordering;
import me.napim.gomod.features.AntiShuffleCompat;
import me.napim.gomod.features.KillCounter;
import me.napim.gomod.features.DeathmatchTabCleaner;
import me.napim.gomod.features.GomodFixes;
import me.napim.gomod.features.LocalDisguise;
import me.napim.gomod.features.GomodPeerDisguiseDisplay;
import me.napim.gomod.features.LobbyPingDisplay;
import me.napim.gomod.features.RenderProfiler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mixin to color player names in the tab list based on kill rankings.
 */
@Mixin(GuiPlayerTabOverlay.class)
public class PlayerListMixin {
    @Unique
    private static final ThreadLocal<NetworkPlayerInfo> GOMOD_TAB_KE_ROW = new ThreadLocal<>();
    @Unique
    private static final ThreadLocal<List> GOMOD_TAB_SOURCE_ROWS = new ThreadLocal<>();
    @Unique
    private static final ThreadLocal<List> GOMOD_TAB_FILTERED_ROWS = new ThreadLocal<>();
    @Unique
    private long gomod$tabOverlayProfileStart;
    @Unique
    private long gomod$tabLagStart;
    /**
     * Forge asks for the same row name more than once during one TAB pass (getPlayerName, then FontRenderer).
     * Keep the render-only result for that pass so the formatting/disguise pipeline is not repeated per callback.
     */
    @Unique
    private static final Map<NetworkPlayerInfo, String> gomod$tabDecoratedInputByRow =
            new IdentityHashMap<>();
    @Unique
    private static final Map<NetworkPlayerInfo, String> gomod$tabDecoratedOutputByRow =
            new IdentityHashMap<>();

    @Inject(method = {"renderPlayerlist", "func_175249_a"}, at = @At("HEAD"), require = 0)
    private void gomod$resetTabGlState(CallbackInfo ci) {
        me.napim.gomod.features.TabPerformanceDebug.onTabOverlay();
        gomod$tabLagStart = me.napim.gomod.features.TabPerformanceDebug.begin();
        gomod$tabOverlayProfileStart = RenderProfiler.begin("TabOverlay.renderPlayerlist.total");
        GOMOD_TAB_SOURCE_ROWS.remove();
        GOMOD_TAB_FILTERED_ROWS.remove();
        GOMOD_TAB_KE_ROW.remove();
        gomod$tabDecoratedInputByRow.clear();
        gomod$tabDecoratedOutputByRow.clear();
        long compositionStart = RenderProfiler.begin("TabOverlay.composition");
        try {
            KillCounter.applyTabCompositionForOverlayRender();
        } finally {
            RenderProfiler.end("TabOverlay.composition", compositionStart);
        }
        KillCounter.beginTabOverlayRender();
        // Defensive reset: some HUD overlays can leave GL state dirty (blend/alpha/texture/color mask),
        // which makes the tab list render gray or glitched when Patcher toggles it persistently.
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.disableLighting();
    }

    @Inject(method = {"renderPlayerlist", "func_175249_a"}, at = @At("RETURN"), require = 0)
    private void gomod$clearTabKeRow(CallbackInfo ci) {
        me.napim.gomod.features.TabPerformanceDebug.end(
                me.napim.gomod.features.TabPerformanceDebug.Metric.VANILLA_OVERLAY, gomod$tabLagStart);
        gomod$tabLagStart = 0L;
        GOMOD_TAB_KE_ROW.remove();
        KillCounter.clearCurrentTabOverlayRow();
        GOMOD_TAB_SOURCE_ROWS.remove();
        GOMOD_TAB_FILTERED_ROWS.remove();
        KillCounter.endTabOverlayRender();
        RenderProfiler.end("TabOverlay.renderPlayerlist.total", gomod$tabOverlayProfileStart);
        gomod$tabOverlayProfileStart = 0L;
    }

    @Redirect(
            method = {"renderPlayerlist", "func_175249_a"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/NetHandlerPlayClient;getPlayerInfoMap()Ljava/util/Collection;"),
            require = 0)
    private Collection<NetworkPlayerInfo> gomod$tabPlayerRowsFromConnection(NetHandlerPlayClient connection) {
        long start = RenderProfiler.beginDeep("TabOverlay.finalizeRows");
        try {
            return KillCounter.finalizeTabListForOverlayRender(connection.getPlayerInfoMap());
        } finally {
            RenderProfiler.endDeep("TabOverlay.finalizeRows", start);
        }
    }

    /**
     * 1.8.9 TAB builds the row list via {@code Ordering.from(PlayerComparator).sortedCopy(getPlayerInfoMap())},
     * which undoes {@link KillCounter#finalizeTabListForOverlayRender} (wired through the getPlayerInfoMap redirect).
     * When custom sort is enabled, copy the iterable in encounter order instead of re-sorting.
     */
    @Redirect(
            method = {"renderPlayerlist", "func_175249_a"},
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/common/collect/Ordering;sortedCopy(Ljava/lang/Iterable;)Ljava/util/List;",
                    remap = false),
            require = 0)
    @SuppressWarnings({"rawtypes", "unchecked"})
    private List gomod$tabPreserveCustomOrderSortedCopy(Ordering ordering, Iterable iterable) {
        Collection<NetworkPlayerInfo> filtered = null;
        if (iterable instanceof Collection) {
            filtered = DeathmatchTabCleaner.filterPlayerInfoCollection((Collection<NetworkPlayerInfo>) iterable);
            iterable = filtered;
        }
        if (me.napim.gomod.util.GameContext.isHypixelReplayWatching(Minecraft.getMinecraft())) {
            List<NetworkPlayerInfo> sorted = ordering.sortedCopy(iterable);
            return LocalDisguise.prioritizeReplayViewerTabRow(sorted);
        }
        if (!KillCounter.isBlitzTabListCustomSortContext()) {
            // Still apply /tabkillers grouping during live Blitz when sidebar context is false (e.g. brief world/null
            // edge cases); applyTabKillersGroupingIfEnabled no-ops when off or not in-game.
            @SuppressWarnings("unchecked")
            List<NetworkPlayerInfo> vanilla = ordering.sortedCopy(iterable);
            return KillCounter.applyTabKillersGroupingIfEnabled(new ArrayList<>(vanilla));
        }
        if (KillCounter.getTabListSortModeForCurrentGame() != KillCounter.TabListSortMode.OFF
                || KillCounter.isDistrictTabListSortEnabled()) {
            ArrayList copy;
            if (iterable instanceof Collection) {
                copy = new ArrayList((Collection) iterable);
            } else {
                copy = new ArrayList();
                for (Object o : iterable) {
                    copy.add(o);
                }
            }
            return KillCounter.applyTabKillersGroupingIfEnabled(copy);
        }
        List vanilla = ordering.sortedCopy(iterable);
        List withDead = KillCounter.applyBlitzTabAliveBeforeDeadOrdering(
                new ArrayList<>((Collection<NetworkPlayerInfo>) vanilla));
        return KillCounter.applyTabKillersGroupingIfEnabled(withDead);
    }

    /**
     * Last-resort ordering guard for Lunar clients that sort the local list again after creation.
     * The vanilla method only calls List#get for player rows, so this keeps the rendered row order aligned.
     */
    @Redirect(
            method = {"renderPlayerlist", "func_175249_a"},
            at = @At(value = "INVOKE", target = "Ljava/util/List;get(I)Ljava/lang/Object;"),
            require = 0)
    @SuppressWarnings("rawtypes")
    private Object gomod$tabGetSortedRendererRow(List rows, int index) {
        List source = GOMOD_TAB_SOURCE_ROWS.get();
        List filtered = GOMOD_TAB_FILTERED_ROWS.get();
        if (rows == source && filtered != null && index >= 0 && index < filtered.size()) {
            return filtered.get(index);
        }
        return KillCounter.getTabOverlayRenderedRowFallback(rows, index);
    }

    /**
     * Lunar may bypass getPlayerInfoMap/sortedCopy redirects but still uses List#size and List#get. Cache one
     * filtered snapshot so layout count and row lookup agree; previously get substituted rows without reducing
     * the count, causing the final visible row to be drawn twice.
     */
    @Redirect(
            method = {"renderPlayerlist", "func_175249_a"},
            at = @At(value = "INVOKE", target = "Ljava/util/List;size()I"),
            require = 0)
    @SuppressWarnings({"rawtypes", "unchecked"})
    private int gomod$tabSizeFromFilteredRendererRows(List rows) {
        int sourceSize = rows.size();
        // Forge/Patcher calls the vanilla TAB renderer directly.  Its subList() still operates on the
        // original renderer list, so a render-only filtered list (especially one with synthetic dead rows)
        // must never report a larger size than that original list.  The fallback is only needed for Lunar's
        // renderer path, which can substitute the row lookup after the layout size has been calculated.
        if (!GomodFixes.isWeaveRuntime()) {
            return sourceSize;
        }
        try {
            if (!rows.isEmpty() && rows.get(0) instanceof NetworkPlayerInfo) {
                List filtered = KillCounter.applyTabOverlayRendererListSortFallback(rows);
                GOMOD_TAB_SOURCE_ROWS.set(rows);
                GOMOD_TAB_FILTERED_ROWS.set(filtered);
                // Never let vanilla's subsequent subList(0, reportedSize) exceed the real source list.
                return filtered == null ? sourceSize : Math.min(sourceSize, Math.max(0, filtered.size()));
            }
        } catch (Throwable ignored) {
        }
        return sourceSize;
    }

    @Redirect(
            method = {"renderPlayerlist", "func_175249_a"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/NetworkPlayerInfo;getLocationSkin()Lnet/minecraft/util/ResourceLocation;"),
            require = 0)
    private ResourceLocation gomod$captureTabRowForKeIcon(NetworkPlayerInfo info) {
        GOMOD_TAB_KE_ROW.set(info);
        KillCounter.setCurrentTabOverlayRow(info);
        return info.getLocationSkin();
    }

    @Redirect(method = {"renderPlayerlist", "func_175249_a"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/FontRenderer;getStringWidth(Ljava/lang/String;)I"),
            require = 0)
    private int gomod$reserveVictimIndentWidth(FontRenderer font, String text) {
        return KillCounter.reserveCurrentTabVictimWidth(font.getStringWidth(text), text);
    }

    @Redirect(
            method = {"renderPlayerlist", "func_175249_a"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;drawScaledCustomSizeModalRect(IIFFIIIIFF)V"),
            require = 0)
    private void gomod$tabScaledRectWithKeIcon(int x, int y, float u, float v, int uWidth, int vHeight, int width,
            int height, float tileWidth, float tileHeight) {
        NetworkPlayerInfo p = GOMOD_TAB_KE_ROW.get();
        int adjustedX = x;
        if (p != null) {
            adjustedX += KillCounter.getTabKillersVictimHeadOffsetPixels(p);
        }
        if (u == 8.0f && v == 8.0f && uWidth == 8 && vHeight == 8) {
            if (p != null) {
                KillCounter.recordTabVictimHeadDraw(p, x, adjustedX);
            }
            if (p != null) {
                long prefixStart = RenderProfiler.beginDeep("TabOverlay.prefixDecorations");
                try {
                    KillCounter.renderTabPrefixDecorations(Minecraft.getMinecraft(), p, adjustedX, y, width, height);
                } finally {
                    RenderProfiler.endDeep("TabOverlay.prefixDecorations", prefixStart);
                }
                // Prefix path can bind gomod textures — re-bind skin before drawing the head layer.
                Minecraft.getMinecraft().getTextureManager().bindTexture(p.getLocationSkin());
            }
        }
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        boolean victimHeadLayer = (u == 8.0F || u == 40.0F) && v == 8.0F && uWidth == 8 && vHeight == 8;
        float alpha = victimHeadLayer && p != null ? KillCounter.getTabKillersVictimHeadAlpha(p) : 1.0F;
        GlStateManager.color(1.0F, 1.0F, 1.0F, alpha);
        try {
            Gui.drawScaledCustomSizeModalRect(adjustedX, y, u, v, uWidth, vHeight, width, height, tileWidth, tileHeight);
        } finally {
            // Do not let the victim fade leak into subsequent TAB icons or text.
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    @Redirect(
            method = {"renderPlayerlist", "func_175249_a"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/FontRenderer;drawStringWithShadow(Ljava/lang/String;FFI)I"),
            require = 0)
    private int gomod$drawDecoratedTabRowText(FontRenderer fontRenderer, String text, float x, float y, int color) {
        NetworkPlayerInfo row = GOMOD_TAB_KE_ROW.get();
        if (row != null) {
            try {
                text = gomod$decorateTabRowName(row, text);
                x = KillCounter.offsetCurrentTabVictimTextX(x, text);
                color = KillCounter.colorCurrentTabVictimText(color);
                Integer aligned = gomod$drawTabRowWithRightAlignedStats(
                        fontRenderer, row, text, x, y, color, true);
                if (aligned != null) {
                    return aligned;
                }
            } finally {
                GOMOD_TAB_KE_ROW.remove();
            }
        }
        return fontRenderer.drawStringWithShadow(text, x, y, color);
    }

    @Redirect(
            method = {"renderPlayerlist", "func_175249_a"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/FontRenderer;drawString(Ljava/lang/String;FFIZ)I"),
            require = 0)
    private int gomod$drawDecoratedTabRowTextFloat(FontRenderer fontRenderer, String text, float x, float y,
            int color, boolean dropShadow) {
        NetworkPlayerInfo row = GOMOD_TAB_KE_ROW.get();
        if (row != null) {
            try {
                text = gomod$decorateTabRowName(row, text);
                x = KillCounter.offsetCurrentTabVictimTextX(x, text);
                color = KillCounter.colorCurrentTabVictimText(color);
                Integer aligned = gomod$drawTabRowWithRightAlignedStats(
                        fontRenderer, row, text, x, y, color, dropShadow);
                if (aligned != null) {
                    return aligned;
                }
            } finally {
                GOMOD_TAB_KE_ROW.remove();
            }
        }
        return fontRenderer.drawString(text, x, y, color, dropShadow);
    }

    @Redirect(
            method = {"renderPlayerlist", "func_175249_a"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/FontRenderer;drawString(Ljava/lang/String;III)I"),
            require = 0)
    private int gomod$drawDecoratedTabRowTextNoShadow(FontRenderer fontRenderer, String text, int x, int y, int color) {
        NetworkPlayerInfo row = GOMOD_TAB_KE_ROW.get();
        if (row != null) {
            try {
                text = gomod$decorateTabRowName(row, text);
                x = Math.round(KillCounter.offsetCurrentTabVictimTextX(x, text));
                color = KillCounter.colorCurrentTabVictimText(color);
                Integer aligned = gomod$drawTabRowWithRightAlignedStats(
                        fontRenderer, row, text, x, y, color, false);
                if (aligned != null) {
                    return aligned;
                }
            } finally {
                GOMOD_TAB_KE_ROW.remove();
            }
        }
        return fontRenderer.drawString(text, x, y, color);
    }

    @Inject(method = {"drawPing", "func_175245_a"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$replaceTabPingIcons(int offset, int xPosition, int yPosition, NetworkPlayerInfo info,
            CallbackInfo ci) {
        if (LobbyPingDisplay.drawTabPingAtIcon(Minecraft.getMinecraft(), offset, xPosition, yPosition, info)) {
            ci.cancel();
        }
    }

    @Inject(method = {"getPlayerName", "func_175243_a"}, at = @At("RETURN"), cancellable = true, require = 0)
    private void gomod$colorPlayerName(NetworkPlayerInfo networkPlayerInfo, CallbackInfoReturnable<String> cir) {
        // Some Lunar/Weave renderers do not ask for the skin before drawing the row. Capture the row here too:
        // renderPlayerlist calls getPlayerName immediately before the corresponding FontRenderer draw call.
        GOMOD_TAB_KE_ROW.set(networkPlayerInfo);
        KillCounter.setCurrentTabOverlayRow(networkPlayerInfo);
        cir.setReturnValue(gomod$decorateTabRowName(networkPlayerInfo, cir.getReturnValue()));
    }

    private Integer gomod$drawTabRowWithRightAlignedStats(FontRenderer fontRenderer, NetworkPlayerInfo row,
            String text, float x, float y, int color, boolean dropShadow) {
        long start = RenderProfiler.beginDeep("TabOverlay.alignedStats");
        try {
            return KillCounter.drawTabRowWithRightAlignedStats(
                    fontRenderer, row, text, x, y, color, dropShadow);
        } finally {
            RenderProfiler.endDeep("TabOverlay.alignedStats", start);
        }
    }

    private String gomod$decorateTabRowName(NetworkPlayerInfo networkPlayerInfo, String originalName) {
        if (!GomodFixes.isWeaveRuntime() && networkPlayerInfo != null
                && gomod$tabDecoratedInputByRow.containsKey(networkPlayerInfo)
                && java.util.Objects.equals(gomod$tabDecoratedInputByRow.get(networkPlayerInfo), originalName)) {
            return gomod$tabDecoratedOutputByRow.get(networkPlayerInfo);
        }
        long start = RenderProfiler.beginDeep("TabOverlay.decorateRow");
        try {
            String decorated = gomod$decorateTabRowNameImpl(networkPlayerInfo, originalName);
            decorated = me.napim.gomod.features.BlitzChatNameDisplay.decorateTab(networkPlayerInfo, decorated);
            if (!GomodFixes.isWeaveRuntime() && networkPlayerInfo != null) {
                gomod$tabDecoratedInputByRow.put(networkPlayerInfo, originalName);
                gomod$tabDecoratedOutputByRow.put(networkPlayerInfo, decorated);
            }
            return decorated;
        } finally {
            RenderProfiler.endDeep("TabOverlay.decorateRow", start);
        }
    }

    private String gomod$decorateTabRowNameImpl(NetworkPlayerInfo networkPlayerInfo, String originalName) {
        if (networkPlayerInfo == null || me.napim.gomod.features.RemoteDisguiseManager.getSourceProfile(networkPlayerInfo) == null) {
            return originalName;
        }
        String baseUsername = me.napim.gomod.features.RemoteDisguiseManager.getSourceProfile(networkPlayerInfo).getName();
        if (me.napim.gomod.util.GameContext.isHypixelReplayWatching(Minecraft.getMinecraft())) {
            com.mojang.authlib.GameProfile profile = networkPlayerInfo instanceof NetworkPlayerInfoAccessor
                    ? ((NetworkPlayerInfoAccessor) networkPlayerInfo).gomod$getGameProfile()
                    : me.napim.gomod.features.RemoteDisguiseManager.getSourceProfile(networkPlayerInfo);
            IChatComponent display = networkPlayerInfo.getDisplayName();
            return LocalDisguise.rewriteReplayTabRowForRender(
                    display == null ? originalName : display.getFormattedText(), networkPlayerInfo);
        }
        String cachedRendered = KillCounter.getRenderedTabLineForInfo(networkPlayerInfo);
        if (cachedRendered != null && !cachedRendered.isEmpty()) {
            // The render-only cache can briefly contain the raw local IGN while Lunar rebuilds the player list.
            // Repair this row before returning the cache so the disguise does not flash during teleport.
            if (LocalDisguise.shouldApplyTabDisplayOverride()
                    && LocalDisguise.matchesLocalProfileFast(me.napim.gomod.features.RemoteDisguiseManager.getSourceProfile(networkPlayerInfo))) {
                String disguised = LocalDisguise.buildTabDisplayName(cachedRendered, baseUsername);
                if (disguised != null && !disguised.isEmpty()) {
                    return KillCounter.applyTabKillersStyleToRenderedName(networkPlayerInfo, disguised);
                }
            }
            return KillCounter.applyTabKillersStyleToRenderedName(networkPlayerInfo, cachedRendered);
        }
        String updated = originalName;
        try {
            IChatComponent display = networkPlayerInfo.getDisplayName();
            String displayText = display == null ? null : display.getFormattedText();
            if (displayText != null && !displayText.isEmpty()) {
                updated = displayText;
            }
        } catch (Throwable ignored) {
        }
        if (updated == null || updated.isEmpty()) {
            return originalName;
        }
        // Only append [kills] when both config and runtime tracking are on; otherwise KillCounter.compose
        // owns TAB (stats/guild) and config-on/runtime-off was leaving null displayName + no stats until a GUI toggle.
        if (networkPlayerInfo.getDisplayName() == null
                && KillCounter.shouldRenderKillCounterAfterName()) {
            int kills = KillCounter.getKillCount(baseUsername);
            if (kills >= 1) {
                boolean isTop = KillCounter.getPlayerRank(baseUsername) == 1;
                String style = (isTop ? EnumChatFormatting.BOLD.toString() : "") + EnumChatFormatting.DARK_RED;
                updated = updated + " " + style + "[" + kills + "]" + EnumChatFormatting.RESET;
            }
        }

        updated = KillCounter.applyTabKillersStyleToRenderedName(networkPlayerInfo, updated);
        // Final local-row guard for clients where the NetworkPlayerInfo display-name mixin is unavailable/skipped.
        if (LocalDisguise.matchesLocalProfileFast(me.napim.gomod.features.RemoteDisguiseManager.getSourceProfile(networkPlayerInfo))) {
            updated = LocalDisguise.buildTabDisplayName(updated, baseUsername);
        }
        updated = GomodPeerDisguiseDisplay.rewriteTabName(me.napim.gomod.features.RemoteDisguiseManager.getSourceProfile(networkPlayerInfo), updated);


        return AntiShuffleCompat.cleanTabTextIfExternalAntiShuffleEnabled(
                LobbyPingDisplay.appendTabPingIfEnabled(networkPlayerInfo, updated));
    }
}
