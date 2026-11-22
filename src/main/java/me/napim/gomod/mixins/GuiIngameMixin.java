package me.napim.gomod.mixins;

import me.napim.gomod.features.BlitzFlameKillEffectTracker;
import me.napim.gomod.features.BlitzRouletteText;
import me.napim.gomod.features.BlitzTeamsDetector;
import me.napim.gomod.features.ForcePigManager;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.DisguiseRenamedItemMasker;
import me.napim.gomod.features.HorseDonkeyTracker;
import me.napim.gomod.features.InventoryHUD;
import me.napim.gomod.features.InventoryQolHudUnderlay;
import me.napim.gomod.features.LocalDisguise;
import me.napim.gomod.features.RemoteDisguiseManager;
import me.napim.gomod.features.ReplayPlayerSelectorDisplay;
import me.napim.gomod.features.DuelPredictor;
import me.napim.gomod.features.BlitzChallenges;
import me.napim.gomod.features.MobTargetDisplay;
import me.napim.gomod.features.RenderProfiler;
import me.napim.gomod.features.PlayerTarget;
import me.napim.gomod.features.MobHealthDisplay;
import me.napim.gomod.features.MountXpOverlay;
import me.napim.gomod.features.PlayersPlayMode;
import me.napim.gomod.features.ScoreboardDateHider;
import me.napim.gomod.features.SidebarBlitzHourReminder;
import me.napim.gomod.features.SidebarScoreboardLayout;
import me.napim.gomod.features.SidebarTeamsLayout;
import me.napim.gomod.features.Selfie;
import me.napim.gomod.features.BlocksFinderWorldOpacityAtlasHook;
import me.napim.gomod.util.HudGlState;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Team;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GuiIngame.class)
public abstract class GuiIngameMixin {

    @Shadow
    protected int remainingHighlightTicks;
    @Shadow
    protected ItemStack highlightingItemStack;
    @Shadow
    private String displayedTitle;

    @Unique private static boolean gomod$rouletteTitleWasLocalSource;
    @Unique private static boolean gomod$rouletteTitleWasRemoteSource;
    @Unique private static String gomod$rouletteTitleStoredValue;
    @Unique private static String gomod$rouletteTitleRemoteNameOnly;
    @Unique private static long gomod$rouletteTitleContextUntil;

    @Unique
    private boolean gomod$renamedMaskFontSuppressSelectedItem;

    /** Rewrite action-bar / record text when Minecraft stores it, not on every frame it is drawn. */
    @ModifyVariable(method = "setRecordPlaying(Ljava/lang/String;Z)V", at = @At("HEAD"),
            argsOnly = true, ordinal = 0, require = 0)
    private String gomod$rewriteDisguiseRecordPlayingSource(String text) {
        return gomod$rewriteHudDisguiseText(text);
    }

    /** Hypixel's action bar uses the component overload; keep component styles while replacing the target IGN. */
    @ModifyVariable(method = "setRecordPlaying(Lnet/minecraft/util/IChatComponent;Z)V", at = @At("HEAD"),
            argsOnly = true, ordinal = 0, require = 0)
    private IChatComponent gomod$rewriteSpectatorTargetActionBar(IChatComponent component) {
        IChatComponent rewritten = ReplayPlayerSelectorDisplay.rewriteLiveSpectatorTargetComponent(component);
        return rewritten == null ? component : rewritten;
    }

    /** Rewrite title and subtitle packet text once when the HUD receives it. */
    @ModifyVariable(method = "displayTitle(Ljava/lang/String;Ljava/lang/String;III)V", at = @At("HEAD"),
            argsOnly = true, ordinal = 0, require = 0)
    private String gomod$rewriteDisguiseTitleSource(String text) {
        String rewritten = gomod$rewriteHudDisguiseText(text);
        if (text != null) {
            gomod$rouletteTitleWasLocalSource = LocalDisguise.isLocalDisguiseSourceNameLabel(text);
            String remoteNameOnly = me.napim.gomod.features.GomodPeerDisguiseDisplay
                    .rewriteBlitzRouletteStandaloneTitle(text);
            gomod$rouletteTitleWasRemoteSource = !text.equals(remoteNameOnly);
            gomod$rouletteTitleRemoteNameOnly = remoteNameOnly;
            gomod$rouletteTitleStoredValue = rewritten;
        }
        return rewritten;
    }

    @ModifyVariable(method = "displayTitle(Ljava/lang/String;Ljava/lang/String;III)V", at = @At("HEAD"),
            argsOnly = true, ordinal = 1, require = 0)
    private String gomod$rewriteDisguiseSubtitleSource(String text) {
        if (text != null && !text.trim().isEmpty()) {
            if (BlitzRouletteText.isRouletteRollOrDeathSubtitle(text)
                    || BlitzRouletteText.isSelectedPlayerWillDieSubtitle(text)) {
                gomod$rouletteTitleContextUntil = System.currentTimeMillis() + 15000L;
            } else {
                gomod$rouletteTitleContextUntil = 0L;
                gomod$rouletteTitleWasLocalSource = false;
                gomod$rouletteTitleWasRemoteSource = false;
            }
        }
        return gomod$rewriteHudDisguiseText(text);
    }

    /**
     * Hypixel sends the spectator "Target: <IGN>..." row through Minecraft's HUD overlay path. Rewrite it when
     * the overlay is stored so Lunar's split FontRenderer calls cannot leave the source IGN visible.
     */
    @Unique
    private static String gomod$rewriteHudDisguiseText(String text) {
        String peer = me.napim.gomod.features.GomodPeerDisguiseDisplay.rewriteBlitzRouletteNameText(text);
        String local = LocalDisguise.rewriteRenderedTextFast(peer);
        String remote = ReplayPlayerSelectorDisplay.rewriteLiveSpectatorTargetText(local);
        return remote == null ? local : remote;
    }

    /** ~60 Hz: catches LMB presses that fall between 20 TPS {@link me.napim.gomod.features.ClientTickHub} samples. */
    @Inject(method = {"renderGameOverlay", "func_175180_a"}, at = @At("HEAD"), require = 0)
    private void gomod$forcePigFramePoll(float partialTicks, CallbackInfo ci) {
        if ((gomod$rouletteTitleWasLocalSource || gomod$rouletteTitleWasRemoteSource)
                && gomod$rouletteTitleContextUntil != 0L) {
            long now = System.currentTimeMillis();
            if (now > gomod$rouletteTitleContextUntil) {
                gomod$rouletteTitleContextUntil = 0L;
                gomod$rouletteTitleWasLocalSource = false;
                gomod$rouletteTitleWasRemoteSource = false;
            } else if (displayedTitle != null && displayedTitle.equals(gomod$rouletteTitleStoredValue)) {
                if (gomod$rouletteTitleWasLocalSource) {
                    String nameOnly = LocalDisguise.rewriteBlitzRouletteTitleNameOnly(displayedTitle);
                    displayedTitle = nameOnly;
                    gomod$rouletteTitleStoredValue = nameOnly;
                } else if (gomod$rouletteTitleRemoteNameOnly != null) {
                    displayedTitle = gomod$rouletteTitleRemoteNameOnly;
                    gomod$rouletteTitleStoredValue = gomod$rouletteTitleRemoteNameOnly;
                }
                gomod$rouletteTitleWasLocalSource = false;
                gomod$rouletteTitleWasRemoteSource = false;
            }
        }
        me.napim.gomod.features.GomodLagHud.onFrameStart();
        MountXpOverlay.beginHudFrame();
        InventoryQolHudUnderlay.beginHudFrame();
        me.napim.gomod.features.MixinStatus.guiIngameOverlayRenderedThisFrame = false;
        me.napim.gomod.features.MixinStatus.guiIngameHookActive = true;
        BlocksFinderWorldOpacityAtlasHook.beginHudItemPass();
        try {
            ForcePigManager.pollAttackEdgeEveryFrame();
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            if (mc != null) {
                me.napim.gomod.features.UselessItemOverlay.clearInventoryGuiEvalSnapshot();
                me.napim.gomod.features.BestArmorDisplay.prepareInventoryQolCachesForFrame(mc);
                me.napim.gomod.features.MoreHudCpsTracker.pollEdges(mc);
            }
        } catch (Throwable ignored) {
        }
    }
    @Shadow
    protected abstract boolean showCrosshair();

    @Shadow
    protected abstract void renderScoreboard(ScoreObjective objective, ScaledResolution scaledResolution);

    @Unique private boolean gomod$xpOverrideActive = false;
    @Unique private int gomod$previousExperienceLevel = 0;
    @Unique private float gomod$previousExperienceProgress = 0.0F;
    @Unique private int gomod$previousExperienceTotal = 0;
    @Unique private boolean gomod$hidingDateEntry = false;
    @Unique private String gomod$keSidebarSpacerPlayerName = null;
    /** Set from {@code renderScoreboard} HEAD — {@link me.napim.gomod.features.SidebarKeOverlay} failsafe layout. */
    @Unique private static int gomod$sidebarScaledWidthForKe = -1;

    /**
     * Renders the inventory HUD after the game overlay is drawn
     */
    @Inject(method = {"renderGameOverlay", "func_175180_a"}, at = @At("TAIL"), require = 0)
    private void renderInventoryHUD(float partialTicks, CallbackInfo ci) {
        long __rpHud = RenderProfiler.begin("GuiIngame.overlayTail.total");
        try {
            try {
            // World pass (mount dim, horse food nametags, etc.) can leave lighting on — flat gray HUD text.
            HudGlState.restoreForOverlayText();
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            if (!PlayersPlayMode.isEnabled()) {
                long t = RenderProfiler.begin("GuiIngame.overlay.InventoryHUD");
                try {
                    InventoryHUD.render();
                } finally {
                    RenderProfiler.end("GuiIngame.overlay.InventoryHUD", t);
                }
            }
            long tPt = RenderProfiler.begin("GuiIngame.overlay.PlayerTarget");
            try {
                PlayerTarget.render();
            } finally {
                RenderProfiler.end("GuiIngame.overlay.PlayerTarget", tPt);
            }
            long tMh = RenderProfiler.begin("GuiIngame.overlay.MobHealthDisplay");
            try {
                MobHealthDisplay.render();
            } finally {
                RenderProfiler.end("GuiIngame.overlay.MobHealthDisplay", tMh);
            }
            long tHd = RenderProfiler.begin("GuiIngame.overlay.HorseDonkeyTracker");
            try {
                HorseDonkeyTracker.render();
            } finally {
                RenderProfiler.end("GuiIngame.overlay.HorseDonkeyTracker", tHd);
            }
            long tMt = RenderProfiler.begin("GuiIngame.overlay.MobTargetDisplay");
            try {
                MobTargetDisplay.renderOverlay();
            } finally {
                RenderProfiler.end("GuiIngame.overlay.MobTargetDisplay", tMt);
            }
            long tDp = RenderProfiler.begin("GuiIngame.overlay.DuelPredictor");
            try {
                DuelPredictor.renderHud();
            } finally {
                RenderProfiler.end("GuiIngame.overlay.DuelPredictor", tDp);
            }
            long tBc = RenderProfiler.begin("GuiIngame.overlay.BlitzChallenges");
            try {
                BlitzChallenges.renderHud();
            } finally {
                RenderProfiler.end("GuiIngame.overlay.BlitzChallenges", tBc);
            }
            long tMore = RenderProfiler.begin("GuiIngame.overlay.MoreHudFeature");
            try {
                me.napim.gomod.features.MoreHudFeature.render(mc);
            } finally {
                RenderProfiler.end("GuiIngame.overlay.MoreHudFeature", tMore);
            }
            long tSelfie = RenderProfiler.begin("GuiIngame.overlay.Selfie");
            try {
                Selfie.renderRecordingIcon();
            } finally {
                RenderProfiler.end("GuiIngame.overlay.Selfie", tSelfie);
            }
            // ShareInv friend-HUD tooltip last: above item depth and other overlays (e.g. teammate tracker).
            try {
                InventoryHUD.renderShareInvTooltipLast(mc);
            } catch (Throwable ignored) {
            }
            // Overlay enforcement for visuals: ensure fake-broken/placed blocks are drawn correctly by last-minute state nudge
            try {
                if (mc != null && mc.theWorld != null && me.napim.gomod.features.FakeBreak.isEnabled()) {
                    // Lightweight: no-op, visual correctness already handled by tick enforcer and packet suppression
                }
            } catch (Throwable t) { System.err.println("[gomod] Error in GuiIngameMixin: " + t.getMessage()); }
            HudGlState.restoreForOverlayText();
            } catch (Exception e) {
                // Prevent exceptions from propagating and causing disconnection
                System.err.println("Error in GuiIngameMixin.renderInventoryHUD: " + e.getMessage());
            }
            me.napim.gomod.features.MixinStatus.guiIngameOverlayRenderedThisFrame = true;
            me.napim.gomod.features.MixinStatus.guiIngameActive = true;
        } finally {
            BlocksFinderWorldOpacityAtlasHook.endHudItemPass();
            RenderProfiler.end("GuiIngame.overlayTail.total", __rpHud);
        }
    }

    @Inject(
            method = {"renderGameOverlay", "func_175180_a"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiIngame;showCrosshair()Z"
            ),
            require = 0
    )
    private void gomod$renderPlayersPlayOverlayBeforeHud(float partialTicks, CallbackInfo ci) {
        try {
            PlayersPlayMode.renderOverlay();
        } catch (Throwable t) {
            System.err.println("[gomod] Error in GuiIngameMixin.playersplayOverlay: " + t.getMessage());
        }
    }

    @Inject(method = "showCrosshair", at = @At("HEAD"), cancellable = true)
    private void gomod$hideVanillaCrosshairDuringPlayersPlay(CallbackInfoReturnable<Boolean> cir) {
        if (PlayersPlayMode.isEnabled()) {
            cir.setReturnValue(false);
        }
    }

    @Redirect(
            method = {"renderGameOverlay", "func_175180_a"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiIngame;renderScoreboard(Lnet/minecraft/scoreboard/ScoreObjective;Lnet/minecraft/client/gui/ScaledResolution;)V"
            ),
            require = 0
    )
    private void gomod$redirectBlitzLobbyScoreboard(GuiIngame instance, ScoreObjective objective, ScaledResolution sr) {
        this.renderScoreboard(objective, sr);
    }

    @Redirect(
            method = {"renderGameOverlay", "func_175180_a"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiIngame;showCrosshair()Z"
            ),
            require = 0
    )
    private boolean gomod$redirectShowCrosshairForPlayersPlay(GuiIngame instance) {
        if (PlayersPlayMode.isEnabled()) {
            return false;
        }
        return this.showCrosshair();
    }

    @Inject(method = "renderTooltip", at = @At("HEAD"), cancellable = true)
    private void gomod$hideVanillaHotbarDuringPlayersPlay(ScaledResolution sr, float partialTicks, CallbackInfo ci) {
        if (PlayersPlayMode.isEnabled()) {
            ci.cancel();
        }
    }

    /**
     * Hotbar item-name highlight must match tooltip text; skip {@link LocalDisguise#replaceRenderedText} so names that
     * contain the target substring keep correct §-formatting and width.
     */
    @Inject(method = "renderSelectedItem", at = @At("HEAD"))
    private void gomod$beginRenamedMaskFontSuppressionSelectedItem(ScaledResolution sr, CallbackInfo ci) {
        gomod$renamedMaskFontSuppressSelectedItem = false;
        if (this.remainingHighlightTicks > 0 && this.highlightingItemStack != null) {
            gomod$renamedMaskFontSuppressSelectedItem = DisguiseRenamedItemMasker.beginRenamedMaskFontSuppression(this.highlightingItemStack);
        }
    }

    @Inject(method = "renderSelectedItem", at = @At("RETURN"))
    private void gomod$endRenamedMaskFontSuppressionSelectedItem(ScaledResolution sr, CallbackInfo ci) {
        DisguiseRenamedItemMasker.endRenamedMaskFontSuppression(gomod$renamedMaskFontSuppressSelectedItem);
        gomod$renamedMaskFontSuppressSelectedItem = false;
    }

    @Inject(method = "renderPlayerStats", at = @At("HEAD"), cancellable = true)
    private void gomod$hideVanillaStatsDuringPlayersPlay(ScaledResolution sr, CallbackInfo ci) {
        if (PlayersPlayMode.isEnabled()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderHorseJumpBar", at = @At("HEAD"), cancellable = true)
    private void gomod$hideVanillaHorseBarDuringPlayersPlay(ScaledResolution sr, int x, CallbackInfo ci) {
        if (PlayersPlayMode.isEnabled()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderHorseJumpBar", at = @At("RETURN"))
    private void gomod$mountXpOverlayAfterHorseBar(ScaledResolution sr, int x, CallbackInfo ci) {
        try {
            MountXpOverlay.renderAfterHud((GuiIngame) (Object) this, sr);
        } catch (Throwable t) {
            System.err.println("[gomod] Error in GuiIngameMixin.mountXpOverlayAfterHorseBar: " + t.getMessage());
        }
    }

    @Inject(method = "renderExpBar", at = @At("HEAD"), cancellable = true)
    private void gomod$hideVanillaXpBarDuringPlayersPlay(ScaledResolution sr, int x, CallbackInfo ci) {
        if (PlayersPlayMode.isEnabled()) {
            ci.cancel();
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return;
        }
        boolean localDisguiseXp = LocalDisguise.shouldOverrideHypixelLobbyXpBar();
        me.napim.gomod.features.LocalDisguise.RemoteDisguiseProfile remoteTarget = localDisguiseXp
                ? null : (RemoteDisguiseManager.shouldOverrideLocalPlayerHypixelLobbyXpBar()
                ? RemoteDisguiseManager.getLocalPlayerNetworkXpTarget() : null);
        if (!localDisguiseXp && remoteTarget == null) return;

        gomod$xpOverrideActive = true;
        gomod$previousExperienceLevel = mc.thePlayer.experienceLevel;
        gomod$previousExperienceProgress = mc.thePlayer.experience;
        gomod$previousExperienceTotal = mc.thePlayer.experienceTotal;
        if (localDisguiseXp) {
            mc.thePlayer.experienceLevel = LocalDisguise.getDisguisedNetworkLevel();
            mc.thePlayer.experience = LocalDisguise.getDisguisedNetworkProgress();
            mc.thePlayer.experienceTotal = LocalDisguise.getDisguisedNetworkExperienceTotal();
            LocalDisguise.noteHypixelLobbyXpBarApplied();
        } else {
            mc.thePlayer.experienceLevel = remoteTarget.networkLevel;
            mc.thePlayer.experience = remoteTarget.networkProgress;
            mc.thePlayer.experienceTotal = remoteTarget.networkExperienceTotal;
        }
    }

    @Inject(method = "renderExpBar", at = @At("RETURN"))
    private void gomod$restoreXpBarAfterLocalDisguise(ScaledResolution sr, int x, CallbackInfo ci) {
        if (!gomod$xpOverrideActive) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer != null) {
            mc.thePlayer.experienceLevel = gomod$previousExperienceLevel;
            mc.thePlayer.experience = gomod$previousExperienceProgress;
            mc.thePlayer.experienceTotal = gomod$previousExperienceTotal;
        }
        gomod$xpOverrideActive = false;
    }

    @Redirect(
            method = {"renderScoreboard", "func_180475_a"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/scoreboard/ScorePlayerTeam;formatPlayerName(Lnet/minecraft/scoreboard/Team;Ljava/lang/String;)Ljava/lang/String;"
            )
    )
    private String gomod$rewriteDisguiseSidebarLine(Team team, String playerName, ScoreObjective objective, ScaledResolution sr) {
        String original = ScorePlayerTeam.formatPlayerName(team, playerName);
        if (gomod$shouldHideSidebarFormattedLine(original)) {
            gomod$hidingDateEntry = true;
            return "\u00A7r";
        }
        gomod$hidingDateEntry = false;
        ScoreObjective obj = objective != null ? objective : gomod$scoreboardObjectiveSlot1();
        String line = LocalDisguise.rewriteSidebarLine(obj, original, playerName);
        if (playerName != null && playerName.equals(gomod$keSidebarSpacerPlayerName)) {
            String keLine = BlitzFlameKillEffectTracker.getFormattedSidebarKillEffectLine();
            if (keLine != null && !keLine.isEmpty()) {
                return keLine;
            }
        }
        if (gomod$keSidebarSpacerPlayerName != null) {
            return line;
        }
        return BlitzFlameKillEffectTracker.mergeKillEffectSuffixIntoSidebarKitLine(line);
    }

    /**
     * Drops hidden sidebar rows from layout so date/game ID (and kit) lines do not leave a blank gap.
     * Also trims any visually blank separator rows left at the top after date rows are removed.
     */
    @Redirect(
            method = {"renderScoreboard", "func_180475_a"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/scoreboard/Scoreboard;getSortedScores(Lnet/minecraft/scoreboard/ScoreObjective;)Ljava/util/Collection;"
            )
    )
    private Collection<Score> gomod$omitHiddenScoreboardRows(Scoreboard board, ScoreObjective objective) {
        if (!ConfigManager.isHideScoreboardValuesEnabled() || !ScoreboardDateHider.isHypixelSidebarTweaksContext()) {
            return board.getSortedScores(objective);
        }
        List<Score> sorted = me.napim.gomod.util.SidebarSnapshot.get(board, objective).scores();
        List<Score> kept = new ArrayList<>(sorted.size());
        for (Score sc : sorted) {
            if (!me.napim.gomod.features.SidebarScoreboardLayout.shouldOmitSidebarRow(board, sc)) {
                kept.add(sc);
            }
        }
        // Trim any visually blank rows (emoji-only separators, or the blanked date row itself)
        // that ended up at the top of the sidebar after date/gameID rows were removed.
        if (ConfigManager.isHideDateGameIdEnabled()) {
            me.napim.gomod.features.SidebarScoreboardLayout.trimTopBlankSeparators(board, kept);
        }
        return kept;
    }

    @Inject(method = {"renderScoreboard", "func_180475_a"}, at = @At("HEAD"), cancellable = true)
    private void gomod$beginDisguiseSidebarRender(ScoreObjective objective, ScaledResolution sr, CallbackInfo ci) {
        BlitzFlameKillEffectTracker.beginSidebarScoreboardRenderPass();
        boolean liveRowMutation = me.napim.gomod.features.SidebarKeScoreboardMutator.isSidebarRowMutationActive();
        gomod$keSidebarSpacerPlayerName = liveRowMutation ? null : gomod$findKeSidebarSpacerPlayerName(objective);
        BlitzFlameKillEffectTracker.setSidebarRealRowRewriteActive(liveRowMutation || gomod$keSidebarSpacerPlayerName != null);
        gomod$sidebarScaledWidthForKe = sr != null ? sr.getScaledWidth() : -1;
        if (LocalDisguise.shouldDeferScoreboardDuringDisguiseLoad()) {
            ci.cancel();
            return;
        }
        LocalDisguise.beginSidebarRender(objective);
        boolean syntheticBlitzHourRow = SidebarBlitzHourReminder.shouldRenderSyntheticSidebarLine(Minecraft.getMinecraft());
        if (((!liveRowMutation && gomod$keSidebarSpacerPlayerName == null) || syntheticBlitzHourRow)
                && gomod$renderSidebarWithInjectedKeRow(objective, sr)) {
            LocalDisguise.endSidebarRender();
            gomod$sidebarScaledWidthForKe = -1;
            gomod$keSidebarSpacerPlayerName = null;
            BlitzFlameKillEffectTracker.setSidebarRealRowRewriteActive(false);
            ci.cancel();
            return;
        }
    }

    @Inject(method = {"renderScoreboard", "func_180475_a"}, at = @At("RETURN"))
    private void gomod$endDisguiseSidebarRender(ScoreObjective objective, ScaledResolution sr, CallbackInfo ci) {
        try {
            BlitzFlameKillEffectTracker.drawSidebarKillEffectAfterVanillaScoreboard(
                    Minecraft.getMinecraft(), objective, sr);
        } catch (Throwable ignored) {
        }
        gomod$keSidebarSpacerPlayerName = null;
        gomod$sidebarScaledWidthForKe = -1;
        BlitzFlameKillEffectTracker.setSidebarRealRowRewriteActive(
                me.napim.gomod.features.SidebarKeScoreboardMutator.isSidebarRowMutationActive());
        LocalDisguise.endSidebarRender();
    }

    @Redirect(
            method = {"renderScoreboard", "func_180475_a"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/scoreboard/Score;getScorePoints()I"
            )
    )
    private int gomod$rewriteDisguiseSidebarScorePoints(Score score, ScoreObjective objective, ScaledResolution sr) {
        int original = score.getScorePoints();
        if (gomod$hidingDateEntry) {
            gomod$hidingDateEntry = false;
            return Integer.MIN_VALUE;
        }
        ScoreObjective obj = objective != null ? objective : gomod$scoreboardObjectiveSlot1();
        return LocalDisguise.rewriteSidebarScorePoints(obj, score, original);
    }

    @Unique
    private static ScoreObjective gomod$scoreboardObjectiveSlot1() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null || mc.theWorld.getScoreboard() == null) {
                return null;
            }
            return mc.theWorld.getScoreboard().getObjectiveInDisplaySlot(1);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Redirect(
            method = {"renderScoreboard", "func_180475_a"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/FontRenderer;drawString(Ljava/lang/String;III)I"
            )
    )
    private int gomod$handleDisguiseSidebarDrawString(FontRenderer fontRenderer, String text, int x, int y, int color) {
        return gomod$sidebarScoreboardTextDraw(fontRenderer, text, x, y, color, false, 0f, 0f, false);
    }

    /**
     * Vanilla 1.8.9 uses {@link FontRenderer#drawString(String, float, float, int, boolean)} for scoreboard text
     * (float x, {@code dropShadow} flag) — not the 4-int overload. Without this, {@code KE:} never hooks.
     */
    @Redirect(
            method = {"renderScoreboard", "func_180475_a"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/FontRenderer;drawString(Ljava/lang/String;FFIZ)I"
            ),
            require = 0
    )
    private int gomod$handleDisguiseSidebarDrawStringFloatBool(FontRenderer fontRenderer, String text, float x, float y, int color,
            boolean dropShadow) {
        boolean kitFragment = ScoreboardDateHider.isKitSidebarTextOrFragment(text);
        boolean hidingKitRow = kitFragment && ConfigManager.isHideKitScoreboardEnabled();
        try {
            BlitzFlameKillEffectTracker.noteSidebarDraw(text, Math.round(y), kitFragment, hidingKitRow);
        } catch (Throwable ignored) {
        }
        if (LocalDisguise.shouldHideSidebarRenderedScoreText(text)) {
            return 0;
        }
        if (gomod$shouldHideSidebarDrawnText(text)) {
            return 0;
        }
        int ret;
        float drawX = gomod$adjustBlitzHourSidebarDrawX(fontRenderer, text, x);
        if (dropShadow) {
            ret = fontRenderer.drawStringWithShadow(text, drawX, y, color);
        } else {
            ret = fontRenderer.drawString(text, drawX, y, color, false);
        }
        BlitzFlameKillEffectTracker.tryDrawSidebarKillEffectInlineAfterKitRow(fontRenderer, text, y);
        return ret;
    }

    /** Lunar / some patches draw the sidebar with float coords + shadow. */
    @Redirect(
            method = {"renderScoreboard", "func_180475_a"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/FontRenderer;drawStringWithShadow(Ljava/lang/String;FFI)I"
            ),
            require = 0
    )
    private int gomod$handleDisguiseSidebarDrawStringFloatShadow(FontRenderer fontRenderer, String text, float x, float y, int color) {
        return gomod$sidebarScoreboardTextDraw(fontRenderer, text, Math.round(x), Math.round(y), color, true, x, y, false);
    }

    @Unique
    private int gomod$sidebarScoreboardTextDraw(FontRenderer fontRenderer, String text, int x, int yRounded, int color,
            boolean floatShadow, float xf, float yf, boolean dropShadow) {
        boolean kitFragment = ScoreboardDateHider.isKitSidebarTextOrFragment(text);
        boolean hidingKitRow = kitFragment && ConfigManager.isHideKitScoreboardEnabled();
        try {
            int yNote = floatShadow ? Math.round(yf) : yRounded;
            BlitzFlameKillEffectTracker.noteSidebarDraw(text, yNote, kitFragment, hidingKitRow);
        } catch (Throwable ignored) {
        }

        if (LocalDisguise.shouldHideSidebarRenderedScoreText(text)) {
            return 0;
        }
        if (gomod$shouldHideSidebarDrawnText(text)) {
            return 0;
        }
        if (floatShadow) {
            xf = gomod$adjustBlitzHourSidebarDrawX(fontRenderer, text, xf);
        } else {
            x = gomod$adjustBlitzHourSidebarDrawX(fontRenderer, text, x);
        }
        int ret;
        if (floatShadow) {
            ret = fontRenderer.drawStringWithShadow(text, xf, yf, color);
            BlitzFlameKillEffectTracker.tryDrawSidebarKillEffectInlineAfterKitRow(fontRenderer, text, yf);
        } else if (dropShadow) {
            ret = fontRenderer.drawString(text, x, yRounded, color, true);
            BlitzFlameKillEffectTracker.tryDrawSidebarKillEffectInlineAfterKitRow(
                    fontRenderer, text, (float) yRounded);
        } else {
            ret = fontRenderer.drawString(text, x, yRounded, color);
            BlitzFlameKillEffectTracker.tryDrawSidebarKillEffectInlineAfterKitRow(
                    fontRenderer, text, (float) yRounded);
        }
        return ret;
    }

    /**
     * Vanilla scoreboard width calculation still measures hidden score-column text. When disguise sidebar rewriting
     * inlines the value into the row and suppresses the separate numeric score, that phantom width pushes the whole
     * sidebar left in Blitz lobby. Treat suppressed score text as zero-width during layout.
     */
    @Redirect(
            method = {"renderScoreboard", "func_180475_a"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/FontRenderer;getStringWidth(Ljava/lang/String;)I"
            ),
            require = 0
    )
    private int gomod$handleSidebarStringWidth(FontRenderer fontRenderer, String text) {
        if (LocalDisguise.shouldHideSidebarRenderedScoreText(text) || gomod$shouldHideSidebarDrawnText(text)) {
            return 0;
        }
        return fontRenderer.getStringWidth(text);
    }

    @Unique
    private static boolean gomod$shouldHideSidebarFormattedLine(String text) {
        if (!ConfigManager.isHideScoreboardValuesEnabled() || !ScoreboardDateHider.isHypixelSidebarTweaksContext()) {
            return false;
        }
        if (ConfigManager.isHideDateGameIdEnabled() && gomod$isDateOrGameIdLine(text)) {
            return true;
        }
        if (ConfigManager.isHideHypixelFooterEnabled() && ScoreboardDateHider.isHypixelFooterText(text)) {
            return true;
        }
        return ConfigManager.isHideKitScoreboardEnabled() && me.napim.gomod.features.ScoreboardDateHider.isKitSidebarText(text);
    }

    @Unique
    private static boolean gomod$shouldHideSidebarDrawnText(String text) {
        return gomod$shouldHideSidebarFormattedLine(text);
    }

    @Unique
    private static boolean gomod$isDateOrGameIdLine(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return ScoreboardDateHider.isDateOrGameIdText(text);
    }

    @Unique
    private String gomod$findKeSidebarSpacerPlayerName(ScoreObjective objective) {
        if (objective == null || !BlitzFlameKillEffectTracker.shouldInjectSidebarKillEffectLine()
                || objective.getScoreboard() == null) {
            return null;
        }
        Scoreboard board = objective.getScoreboard();
        List<Score> visibleScores = SidebarScoreboardLayout.visibleScoresForSidebarLayout(board, objective);
        Score previousScore = null;
        String previousLine = null;
        for (Score score : visibleScores) {
            if (score == null || score.getPlayerName() == null) {
                continue;
            }
            ScorePlayerTeam team = board.getPlayersTeam(score.getPlayerName());
            String originalLine = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
            if (ScoreboardDateHider.isKitSidebarTextOrFragment(originalLine)) {
                if (previousScore != null && gomod$isSidebarSpacerLine(previousLine)) {
                    return previousScore.getPlayerName();
                }
                return null;
            }
            if (SidebarTeamsLayout.isTeamAnchorLine(originalLine)) {
                if (previousScore != null && gomod$isSidebarSpacerLine(previousLine)) {
                    return previousScore.getPlayerName();
                }
                return null;
            }
            previousScore = score;
            previousLine = originalLine;
        }
        return null;
    }

    @Unique
    private boolean gomod$renderSidebarWithInjectedKeRow(ScoreObjective objective, ScaledResolution sr) {
        // Lunar has its own scoreboard renderer in addition to GuiIngame's vanilla path. Painting a complete
        // fallback sidebar here therefore produces two sidebars whenever KE/Blitz Hour has no mutable anchor
        // (most visibly in the Blitz waiting-room countdown). On Weave, keep scoreboard-data mutations only and
        // let Lunar remain the single owner of the actual sidebar paint.
        if (gomod$isWeaveRuntime()) {
            return false;
        }
        if (objective == null || sr == null) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.fontRendererObj == null || objective.getScoreboard() == null) {
            return false;
        }
        boolean wantsKeRow = BlitzFlameKillEffectTracker.shouldInjectSidebarKillEffectLine();
        String keLine = wantsKeRow ? BlitzFlameKillEffectTracker.getFormattedSidebarKillEffectLine() : null;
        if (keLine == null || keLine.isEmpty()) {
            wantsKeRow = false;
        }
        boolean wantsBlitzHourRow = SidebarBlitzHourReminder.shouldRenderSyntheticSidebarLine(mc);
        if (!wantsKeRow && !wantsBlitzHourRow) {
            return false;
        }

        Scoreboard board = objective.getScoreboard();
        List<Score> visibleScores = SidebarScoreboardLayout.visibleScoresForSidebarLayout(board, objective);
        if (visibleScores.isEmpty()) {
            return false;
        }

        FontRenderer fontRenderer = mc.fontRendererObj;
        int valueGap = fontRenderer.getStringWidth(": ");
        int width = fontRenderer.getStringWidth(objective.getDisplayName());
        List<String> renderedLines = new ArrayList<>();
        List<String> renderedScoreTexts = new ArrayList<>();
        boolean insertedKeRow = !wantsKeRow;
        boolean insertedBlitzHour = !wantsBlitzHourRow;
        String blitzHourLine = wantsBlitzHourRow ? SidebarBlitzHourReminder.getFormattedSidebarLine() : null;
        boolean sawKitRow = false;

        for (int i = 0; i < visibleScores.size(); i++) {
            Score score = visibleScores.get(i);
            ScorePlayerTeam team = board.getPlayersTeam(score.getPlayerName());
            String originalLine = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
            if (originalLine == null) {
                originalLine = "";
            }
            String rewrittenLine = LocalDisguise.rewriteSidebarLine(objective, originalLine, score.getPlayerName());
            boolean kitRow = ScoreboardDateHider.isKitSidebarTextOrFragment(rewrittenLine)
                    || ScoreboardDateHider.isKitSidebarTextOrFragment(originalLine)
                    || SidebarTeamsLayout.isTeamAnchorLine(rewrittenLine)
                    || SidebarTeamsLayout.isTeamAnchorLine(originalLine);
            boolean spacerRow = gomod$isSidebarSpacerLine(rewrittenLine);
            if (kitRow) {
                sawKitRow = true;
            }
            boolean nextRowIsKit = false;
            if (i + 1 < visibleScores.size()) {
                Score nextScore = visibleScores.get(i + 1);
                if (nextScore != null && nextScore.getPlayerName() != null) {
                    ScorePlayerTeam nextTeam = board.getPlayersTeam(nextScore.getPlayerName());
                    String nextOriginalLine = ScorePlayerTeam.formatPlayerName(nextTeam, nextScore.getPlayerName());
                    nextRowIsKit = ScoreboardDateHider.isKitSidebarTextOrFragment(nextOriginalLine)
                            || SidebarTeamsLayout.isTeamAnchorLine(nextOriginalLine);
                }
            }

            if (!insertedKeRow && spacerRow && nextRowIsKit) {
                renderedLines.add(keLine);
                renderedScoreTexts.add(null);
                width = Math.max(width, fontRenderer.getStringWidth(keLine));
                insertedKeRow = true;
                continue;
            }

            if (!insertedKeRow && kitRow) {
                renderedLines.add(keLine);
                renderedScoreTexts.add(null);
                width = Math.max(width, fontRenderer.getStringWidth(keLine));
                insertedKeRow = true;
            }

            if (rewrittenLine == null) {
                rewrittenLine = "";
            }
            int rewrittenScore = LocalDisguise.rewriteSidebarScorePoints(objective, score, score.getScorePoints());
            String scoreText = EnumChatFormatting.RED + Integer.toString(rewrittenScore);
            if (LocalDisguise.shouldHideSidebarScoreColumnText(objective, scoreText)
                    || gomod$shouldHideSidebarDrawnText(scoreText)) {
                scoreText = null;
            }

            renderedLines.add(rewrittenLine);
            renderedScoreTexts.add(scoreText);
            int lineWidth = fontRenderer.getStringWidth(rewrittenLine);
            if (scoreText != null && !scoreText.isEmpty()) {
                lineWidth += valueGap + fontRenderer.getStringWidth(scoreText);
            }
            width = Math.max(width, lineWidth);

            if (kitRow && !insertedBlitzHour && blitzHourLine != null) {
                width = gomod$appendBlitzHourBlockWithSpacers(renderedLines, renderedScoreTexts, blitzHourLine,
                        fontRenderer, width);
                insertedBlitzHour = true;
            }

            // Blitz Teams active match: Taunt is the top scoreboard row — inject KE above it at render time.
            if (!insertedKeRow && wantsKeRow && BlitzTeamsDetector.isActiveBlitzTeamsGame(mc)
                    && gomod$isTauntSidebarText(rewrittenLine)) {
                renderedLines.add(keLine);
                renderedScoreTexts.add(null);
                width = Math.max(width, fontRenderer.getStringWidth(keLine));
                insertedKeRow = true;
                if (!insertedBlitzHour && blitzHourLine != null) {
                    width = gomod$appendBlitzHourBlockWithSpacers(renderedLines, renderedScoreTexts, blitzHourLine,
                            fontRenderer, width);
                    insertedBlitzHour = true;
                }
            }

            // If the Kit row is omitted (e.g. Hide Kit), anchor KE off the Taunt row instead.
            // Render list order is bottom-up; add KE *after* Taunt so it draws visually above it.
            if (!insertedKeRow && !sawKitRow && ConfigManager.isHideKitScoreboardEnabled()
                    && gomod$isTauntSidebarText(rewrittenLine)) {
                renderedLines.add(keLine);
                renderedScoreTexts.add(null);
                width = Math.max(width, fontRenderer.getStringWidth(keLine));
                insertedKeRow = true;
                if (!insertedBlitzHour && blitzHourLine != null) {
                    width = gomod$appendBlitzHourBlockWithSpacers(renderedLines, renderedScoreTexts, blitzHourLine,
                            fontRenderer, width);
                    insertedBlitzHour = true;
                }
            } else if (!insertedBlitzHour && blitzHourLine != null && !sawKitRow
                    && ConfigManager.isHideKitScoreboardEnabled()
                    && gomod$isTauntSidebarText(rewrittenLine) && !wantsKeRow) {
                width = gomod$appendBlitzHourBlockWithSpacers(renderedLines, renderedScoreTexts, blitzHourLine,
                        fontRenderer, width);
                insertedBlitzHour = true;
            }
        }
        if (renderedLines.isEmpty() && !wantsBlitzHourRow) {
            return false;
        }

        int totalHeight = renderedLines.size() * fontRenderer.FONT_HEIGHT;
        int bottomY = sr.getScaledHeight() / 2 + totalHeight / 3;
        int padding = 3;
        int left = sr.getScaledWidth() - width - padding;
        int right = sr.getScaledWidth() - padding + 2;

        for (int index = 0; index < renderedLines.size(); index++) {
            int row = index + 1;
            int y = bottomY - row * fontRenderer.FONT_HEIGHT;
            String line = renderedLines.get(index);
            String scoreText = renderedScoreTexts.get(index);
            boolean kitFragment = ScoreboardDateHider.isKitSidebarTextOrFragment(line);
            boolean hidingKitRow = kitFragment && ConfigManager.isHideKitScoreboardEnabled();

            BlitzFlameKillEffectTracker.noteSidebarDraw(line, y, kitFragment, hidingKitRow);
            Gui.drawRect(left - 2, y, right, y + fontRenderer.FONT_HEIGHT, 1342177280);
            if (scoreText != null && !scoreText.isEmpty()) {
                int scoreX = right - fontRenderer.getStringWidth(scoreText);
                fontRenderer.drawString(scoreText, scoreX, y, 553648127);
            }
            fontRenderer.drawString(line, left, y, 553648127);

            if (row == renderedLines.size()) {
                String title = objective.getDisplayName();
                Gui.drawRect(left - 2, y - fontRenderer.FONT_HEIGHT - 1, right, y - 1, 1610612736);
                Gui.drawRect(left - 2, y - 1, right, y, 1342177280);
                fontRenderer.drawString(title, left + width / 2 - fontRenderer.getStringWidth(title) / 2,
                        y - fontRenderer.FONT_HEIGHT, 553648127);
            }
        }

        return true;
    }

    @Unique
    private static boolean gomod$isWeaveRuntime() {
        try {
            Class.forName("net.weavemc.api.ModInitializer", false, GuiIngameMixin.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Unique
    private int gomod$adjustBlitzHourSidebarDrawX(FontRenderer fontRenderer, String text, int defaultX) {
        return Math.round(gomod$adjustBlitzHourSidebarDrawX(fontRenderer, text, (float) defaultX));
    }

    @Unique
    private float gomod$adjustBlitzHourSidebarDrawX(FontRenderer fontRenderer, String text, float defaultX) {
        return SidebarBlitzHourReminder.adjustDrawXIfBlitzHour(fontRenderer, text, defaultX);
    }

    @Unique
    private static int gomod$appendBlitzHourBlockWithSpacers(List<String> lines, List<String> scoreTexts,
            String blitzHourLine, FontRenderer fontRenderer, int currentWidth) {
        lines.add("");
        scoreTexts.add(null);
        lines.add(blitzHourLine);
        scoreTexts.add(null);
        lines.add("");
        scoreTexts.add(null);
        return Math.max(currentWidth, fontRenderer.getStringWidth(blitzHourLine));
    }

    @Unique
    private static boolean gomod$isSidebarSpacerLine(String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(text);
        if (plain == null) {
            plain = text;
        }
        plain = plain.replace('\u00A0', ' ').trim();
        if (plain.isEmpty()) {
            return true;
        }
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                return false;
            }
        }
        return true;
    }

    @Unique
    private static boolean gomod$isTauntSidebarText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(text);
        if (plain == null) {
            plain = text;
        }
        plain = plain.replace('\u00A0', ' ').trim().toLowerCase(java.util.Locale.ROOT);
        return plain.contains("taunt:");
    }

    /**
     * Same order as inventory {@code drawSlot} HEAD: draw tint quads, then vanilla {@code renderItemIntoGUI} paints
     * the icon on top. Hotbar HUD often has depth testing on; underlay quads use a brief {@code disableDepth} /
     * {@code depthMask(false)} window so the item pass composites on top (see {@code docs/HOTBAR_OVERLAY_DEPTH_FIX.md}).
     */
    @Inject(method = "renderHotbarItem",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/RenderItem;renderItemIntoGUI(Lnet/minecraft/item/ItemStack;II)V",
                    shift = At.Shift.BEFORE))
    private void gomod$hotbarOverlaysBeforeItemIcon(int index, int x, int y, float partialTicks, EntityPlayer player, CallbackInfo ci) {
        InventoryQolHudUnderlay.renderSlot(index, x, y, player, InventoryQolHudUnderlay.SOURCE_HOTBAR);
    }
}
