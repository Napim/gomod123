package me.napim.gomod.mixins;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.BlitzSoloExtendedResults;
import me.napim.gomod.features.BlitzChatNameDisplay;
import me.napim.gomod.features.GomodFixes;
import me.napim.gomod.features.KillCounter;
import me.napim.gomod.features.LobbyPingDisplay;
import me.napim.gomod.features.LocalDisguise;
import me.napim.gomod.features.LocalDisguiseDebugCapture;
import me.napim.gomod.features.LeaderboardHologramDebugCapture;
import me.napim.gomod.features.RemoteDisguiseManager;
import me.napim.gomod.features.ReplayPlayerSelectorDisplay;
import me.napim.gomod.features.RenderProfiler;
import me.napim.gomod.features.TabPerformanceDebug;
import me.napim.gomod.features.LunarCosmeticRenderContext;
import me.napim.gomod.features.NametagRenderDebugCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FontRenderer.class)
public abstract class FontRendererMixin {
    @Inject(method = "onResourceManagerReload", at = @At("RETURN"))
    private void gomod$invalidatePotionLabels(CallbackInfo ci) {
        me.napim.gomod.features.PotionHudTextCache.onFontReload();
        LobbyPingDisplay.onFontReload();
    }

    private static boolean gomod$hasLoadedWorld() {
        try {
            Minecraft minecraft = Minecraft.getMinecraft();
            return minecraft != null && minecraft.theWorld != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Lunar's enhanced TAB module bypasses GuiPlayerTabOverlay's normal FontRenderer call sites and invokes
     * renderString through its bridge. Intercepting the common render entry keeps the pixel-aligned stats column
     * working for that path too. The KillCounter guard prevents recursion for the two replacement draws.
     */
    @Inject(method = "renderString", at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$rightAlignLunarTabStats(String text, float x, float y, int color, boolean dropShadow,
            CallbackInfoReturnable<Integer> cir) {
        if (!gomod$hasLoadedWorld()) return;
        long timer = TabPerformanceDebug.begin();
        long renderTimer = RenderProfiler.begin("FontRenderer.gomodTabStatsAlignment");
        try {
            Integer result = KillCounter.drawTabTextWithRightAlignedStatsFallback(
                    (FontRenderer) (Object) this, text, x, y, color, dropShadow);
            if (result != null) cir.setReturnValue(result);
        } finally {
            TabPerformanceDebug.end(TabPerformanceDebug.Metric.FONT_TAB_STATS_ALIGN, timer);
            RenderProfiler.end("FontRenderer.gomodTabStatsAlignment", renderTimer);
        }
    }

    @Inject(method = "renderString", at = @At("HEAD"), require = 0)
    private void gomod$captureEntityFontDraw(String text, float x, float y, int color, boolean dropShadow,
            CallbackInfoReturnable<Integer> cir) {
        if (!NametagRenderDebugCapture.isActive()) return;
        NametagRenderDebugCapture.recordGlobalFontDraw(text, x, y, color, dropShadow);
        EntityLivingBase entity = LunarCosmeticRenderContext.currentEntity();
        if (entity != null) {
            NametagRenderDebugCapture.recordEntityFontDraw(entity, text, x, y, color, dropShadow);
        }
    }

    private String gomod$rewriteDisguiseText(String text, String stage) {
        if (!gomod$hasLoadedWorld()) return text;
        long timer = TabPerformanceDebug.begin();
        String section = "font-width".equals(stage) ? "FontRenderer.gomodWidthRewrite" : "FontRenderer.gomodDrawRewrite";
        long renderTimer = RenderProfiler.begin(section);
        try { return gomod$rewriteDisguiseTextTimed(text, stage); }
        finally {
            TabPerformanceDebug.end(TabPerformanceDebug.Metric.FONT_REWRITE, timer);
            TabPerformanceDebug.end("font-width".equals(stage) ? TabPerformanceDebug.Metric.FONT_WIDTH_REWRITE
                    : TabPerformanceDebug.Metric.FONT_DRAW_REWRITE, timer);
            RenderProfiler.end(section, renderTimer);
        }
    }

    private String gomod$rewriteDisguiseTextTimed(String text, String stage) {
        // Chat already received its semantic disguise rewrite. A held TAB key or a retained row must not
        // reinterpret chat components/fragments as player-list labels during either measurement or painting.
        if (KillCounter.isChatFontRenderActive()) return text;
        if (BlitzChatNameDisplay.shouldPreserveRenderedPlayerText(text)) {
            if (LocalDisguiseDebugCapture.isActive()) {
                LocalDisguiseDebugCapture.recordFinalPlayerLabel(stage + "-final-player-format", text, text);
            }
            return text;
        }
        // Extended-result rows already contain their final disguise-aware names. Skip every render/width rewrite
        // for these exact strings so centering is measured against the same text that is drawn.
        if (BlitzSoloExtendedResults.isPreservedDisguiseResultText(text)) {
            return text;
        }
        long stageTimer = TabPerformanceDebug.begin();
        String spectatorTarget = ReplayPlayerSelectorDisplay.rewriteLiveSpectatorTargetText(text);
        TabPerformanceDebug.end(TabPerformanceDebug.Metric.FONT_HUD_NAMES, stageTimer);
        if (spectatorTarget != null) {
            return spectatorTarget;
        }
        stageTimer = TabPerformanceDebug.begin();
        String teleportConfirmation = ReplayPlayerSelectorDisplay.rewriteTeleportConfirmationText(text);
        TabPerformanceDebug.end(TabPerformanceDebug.Metric.FONT_HUD_NAMES, stageTimer);
        if (teleportConfirmation != null) {
            return teleportConfirmation;
        }
        stageTimer = TabPerformanceDebug.begin();
        if (ReplayPlayerSelectorDisplay.isOpen()) {
            String selectorText = ReplayPlayerSelectorDisplay.rewriteText(text);
            TabPerformanceDebug.end(TabPerformanceDebug.Metric.FONT_SELECTOR, stageTimer);
            if (selectorText != null) return selectorText;
        } else TabPerformanceDebug.end(TabPerformanceDebug.Metric.FONT_SELECTOR, stageTimer);
        // Some Hypixel #rekt holograms do not reach the normal armor-stand label hook on every client.
        // Their exact phrase is distinctive, so repeat the targeted disguise rewrite at the last text draw.
        if (text != null && text.indexOf('#') >= 0 && RemoteDisguiseManager.hasEntries()) {
            text = RemoteDisguiseManager.rewriteLeaderboardHologramText(text);
        }
        me.napim.gomod.features.MixinStatus.fontRendererActive = true;
        stageTimer = TabPerformanceDebug.begin();
        text = KillCounter.decorateTabRenderedText(text);
        TabPerformanceDebug.end(TabPerformanceDebug.Metric.FONT_TAB_DECORATE, stageTimer);
        stageTimer = TabPerformanceDebug.begin();
        String replayText = LocalDisguise.rewriteReplayPlayerFontText(text);
        TabPerformanceDebug.end(TabPerformanceDebug.Metric.FONT_REPLAY, stageTimer);
        if (replayText != null) {
            if (LocalDisguiseDebugCapture.isActive()) {
                LocalDisguiseDebugCapture.recordFinalPlayerLabel(stage + "-replay", text, replayText);
            }
            return replayText;
        }
        if (me.napim.gomod.features.MixinStatus.skipFontRendererDisguiseReplace) {
            return text;
        }
        // Lunar's enhanced TAB renderer can bypass GuiPlayerTabOverlay and NetworkPlayerInfo entirely, then send
        // the raw profile name through the common FontRenderer entry. Keep this Weave-only fallback lock-free and
        // targeted by the disguise prefilter so the local real IGN cannot flash during a teleport/cache rebuild.
        String tabInput = text;
        boolean localRewriteAttempted = false;
        stageTimer = TabPerformanceDebug.begin();
        if (GomodFixes.isWeaveRuntime() && LocalDisguise.fastEnabledFlag) {
            localRewriteAttempted = LocalDisguise.shouldAttemptRenderRewriteFast(tabInput);
            if (localRewriteAttempted) {
                text = LocalDisguise.rewriteRenderedTextFast(tabInput);
            }
        }
        TabPerformanceDebug.end(TabPerformanceDebug.Metric.FONT_LOCAL_DISGUISE, stageTimer);
        if (LeaderboardHologramDebugCapture.isActive()) {
            LeaderboardHologramDebugCapture.recordFontRendererText(tabInput, text);
        }
        if (GomodFixes.isWeaveRuntime()) {
            stageTimer = TabPerformanceDebug.begin();
            text = RemoteDisguiseManager.rewriteManualDisguiseTabRenderedText(text);
            TabPerformanceDebug.end(TabPerformanceDebug.Metric.FONT_REMOTE_DISGUISE, stageTimer);
        }
        // Disguises are rewritten once at their semantic source (chat, tab/display name, scoreboard,
        // world label, title/action bar, boss bar). Keep only the standalone custom-kill fallback here.
        if (!LocalDisguise.fastEnabledFlag && ConfigManager.isDisguiseCustomKillCountEnabled()) {
            return LocalDisguise.rewriteRenderedTextFast(text);
        }
        if (LocalDisguiseDebugCapture.isActive()) {
            LocalDisguiseDebugCapture.recordFinalPlayerLabel(stage, tabInput, text);
        }
        return text;
    }

    @ModifyVariable(method = "renderString", at = @At("HEAD"), argsOnly = true, require = 0)
    private String gomod$replaceDisguisedNameInRenderString(String text) {
        return gomod$rewriteDisguiseText(text, "font-draw");
    }

    @ModifyVariable(method = "getStringWidth", at = @At("HEAD"), argsOnly = true, require = 0)
    private String gomod$replaceDisguisedNameInGetStringWidth(String text) {
        return gomod$rewriteDisguiseText(text, "font-width");
    }

}
