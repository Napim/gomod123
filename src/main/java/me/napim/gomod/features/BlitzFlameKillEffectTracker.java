package me.napim.gomod.features;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.napim.gomod.util.GameContext;
import me.napim.gomod.util.MinecraftFormatting;
import me.napim.gomod.util.SidebarSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumChatFormatting;

/**
 * Blitz SG kill effect: chat/API inference for Flaming Arrows proc HUD, plus equipped effect name for sidebar
 * {@code KE: …} line (same Hypixel line as flame detection: {@code You changed your Kill Effect to …}).
 */
public final class BlitzFlameKillEffectTracker {

    private static final Pattern STRIP_FORMATTING = Pattern.compile("\u00A7[0-9A-FK-ORa-fk-or]");
    private static final long PROC_DURATION_MS = 20_000L;
    private static final long SPEED_DURATION_MS = 15_000L;
    private static final long RESISTANCE_DURATION_MS = 15_000L;
    private static final long REGEN_DURATION_MS = 10_000L;
    private static final int ASSIST_MIN_PERCENT = 41;

    /**
     * Reliable in-match signal: after a kill, Hypixel applies the kit kill effect (Speed, Regeneration, etc.).
     * Takes priority over API/tabstats for {@link #resolveSidebarKillEffectDisplay()} and TAB kill-effect color.
     */
    private static final Pattern KILL_GAINED_FOR_KILLING_PLAYER = Pattern.compile(
            "You gained (.+?) for killing (?:a|another) player\\s*[!\\.]*", Pattern.CASE_INSENSITIVE);
    /**
     * Random kill effect rolls a concrete effect per kit; Hypixel announces it this way instead of
     * {@link #KILL_GAINED_FOR_KILLING_PLAYER}.
     */
    private static final Pattern KILL_EFFECT_GIVEN_RANDOM_STYLE = Pattern.compile(
            "You were given the Kill Effect:\\s*(.+?)(?:!|\\.|$)", Pattern.CASE_INSENSITIVE);
    /** Allow Hypixel prefixes (e.g. +coins, rank) before the sentence. */
    private static final Pattern KILL_EFFECT_LINE = Pattern.compile(
            "You changed your Kill Effect to ([^!\r\n]+)!", Pattern.CASE_INSENSITIVE);
    private static final Pattern ASSIST_LINE = Pattern.compile(
            "You damaged .+ for (\\d+)% before .+ killed them!", Pattern.CASE_INSENSITIVE);
    /** Hypixel sometimes omits the finisher name: {@code … before killing them!} */
    private static final Pattern ASSIST_LINE_NO_FINISHER = Pattern.compile(
            "You damaged .+ for (\\d+)% before killing them!", Pattern.CASE_INSENSITIVE);
    /**
     * Third-person kill feed when the first-person line ({@code You killed …}) is missing. Example:
     * {@code Rexuspoxy was killed by Nyamih (Kill #21,717)}. Prefix text (coins, lobby) allowed before the match.
     */
    private static final Pattern BROADCAST_KILL_LINE = Pattern.compile(
            ".+ was (?:killed|slain|shot|eliminated|defeated) by (.+?)(?:\\s*\\(|$)", Pattern.CASE_INSENSITIVE);

    private static final String[] SELF_KILL_VERBS = {
            "killed ", "shot ", "slew ", "slain ", "blew up ", "burned to death ", "frozen to death ",
            "electrocuted ", "eliminated ", "defeated "
    };

    /** -1 = unknown (fall back to API), 0 = chat picked a non-flame effect, 1 = chat picked Flaming Arrows. */
    private static volatile byte chatEquipped = -1;
    private static volatile boolean apiSaysFlame;
    private static volatile long flameProcEndMs;
    private static volatile long otherKillEffectProcEndMs;
    private static volatile String otherKillEffectProcCode;
    private static volatile boolean resistanceUnlocked;
    /** Display name for scoreboard {@code KE:} line (e.g. {@code Regeneration}); set from chat or API. */
    private static volatile String sidebarKillEffectDisplay;
    private static final long SELF_EFFECT_REFRESH_COOLDOWN_MS = 30_000L;
    private static final long SELF_EFFECT_RETRY_BACKOFF_MS = 10_000L;
    private static volatile long selfEffectRefreshEarliestAtMs;
    private static volatile boolean selfEffectRefreshInFlight;
    /**
     * After {@link #KILL_GAINED_FOR_KILLING_PLAYER} or {@link #KILL_EFFECT_GIVEN_RANDOM_STYLE} fires, defer overwriting
     * the local player's kill effect from Hypixel tabstats/API briefly so chat stays authoritative for this match.
     */
    private static volatile long liveGameKillEffectChatPriorityWallMs;

    // Sidebar render debug (captured from GuiIngameMixin draw calls)
    private static final int SIDEBAR_DEBUG_RING_SIZE = 18;
    private static final String[] sidebarRecentDraws = new String[SIDEBAR_DEBUG_RING_SIZE];
    private static volatile int sidebarRecentDrawIndex = 0;
    private static volatile String sidebarLastKitAnchorRaw;
    private static volatile boolean sidebarLastKitAnchorWasHidden;
    /**
     * Last vanilla Y (screen coords) where the Kit row text was drawn this scoreboard pass; reset in
     * {@link #beginSidebarScoreboardRenderPass()}. Used by {@link SidebarKeOverlay} to align {@code KE:} without
     * guessing layout when omit/hide rules differ from Hypixel.
     */
    private static volatile int sidebarLastKitAnchorDrawY = -1;
    /**
     * Set when {@link #drawSidebarKillEffectAfterVanillaScoreboard} paints in {@code renderScoreboard} RETURN;
     * {@link SidebarKeOverlay} skips so we don't double-draw after the same pass.
     */
    private static volatile boolean sidebarKeInjectedThisScoreboardPass;
    /**
     * Set when {@link #tryDrawSidebarKillEffectInlineAfterKitRow} paints during a kit row FontRenderer draw in
     * {@code renderScoreboard}; avoids duplicate paint at RETURN / Post.
     */
    private static volatile boolean sidebarKeInlineDrawnThisScoreboardPass;
    /** True when GuiIngameMixin found a real writable spacer row, so overlay fallbacks must stay off. */
    private static volatile boolean sidebarRealRowRewriteActive;

    private BlitzFlameKillEffectTracker() {
    }

    /**
     * When true, {@link me.napim.gomod.command.KillEffectCommand} should not overwrite this client's kill effect from
     * the Hypixel API (tabstats) — live game chat was more recent.
     */
    public static boolean shouldDeferHypixelApiKillEffectForLocalPlayer() {
        return System.currentTimeMillis() - liveGameKillEffectChatPriorityWallMs < 180_000L;
    }

    private static void noteLiveGameKillEffectChatPriority() {
        liveGameKillEffectChatPriorityWallMs = System.currentTimeMillis();
    }

    /**
     * Call from {@code GuiIngame#renderScoreboard} HEAD so each pass starts without a stale kit Y or draw ring.
     */
    public static void beginSidebarScoreboardRenderPass() {
        sidebarLastKitAnchorDrawY = -1;
        sidebarKeInjectedThisScoreboardPass = false;
        sidebarKeInlineDrawnThisScoreboardPass = false;
        sidebarRealRowRewriteActive = false;
        try {
            for (int i = 0; i < SIDEBAR_DEBUG_RING_SIZE; i++) {
                sidebarRecentDraws[i] = null;
            }
            sidebarRecentDrawIndex = 0;
            sidebarLastKitAnchorRaw = null;
            sidebarLastKitAnchorWasHidden = false;
        } catch (Throwable ignored) {
        }
    }

    public static boolean wasSidebarKeInjectedThisScoreboardPass() {
        return sidebarKeInjectedThisScoreboardPass;
    }

    public static void setSidebarRealRowRewriteActive(boolean active) {
        sidebarRealRowRewriteActive = active;
    }

    public static boolean isSidebarRealRowRewriteActive() {
        return sidebarRealRowRewriteActive;
    }

    /**
     * True if kit row already inlines {@code KE:} (merge path) or recent draw buffer shows it — avoid duplicate paint.
     */
    public static boolean shouldSkipSidebarKeBecauseAlreadyPainted() {
        if (hasSidebarKeRowBelowKit()) {
            return true;
        }
        String lastKitAnchor = getSidebarLastKitAnchorRaw();
        if (lastKitAnchor != null) {
            String cleanAnchor = MinecraftFormatting.stripSectionCodes(lastKitAnchor).toLowerCase(Locale.ROOT);
            if (cleanAnchor.contains("ke:")) {
                return true;
            }
        }
        for (String raw : sidebarRecentDraws) {
            if (raw != null && MinecraftFormatting.stripSectionCodes(raw).toLowerCase(Locale.ROOT).contains("ke:")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSidebarKeRowBelowKit() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null || mc.theWorld.getScoreboard() == null) {
                return false;
            }
            Scoreboard sb = mc.theWorld.getScoreboard();
            ScoreObjective objective = sb.getObjectiveInDisplaySlot(1);
            if (objective == null) {
                return false;
            }
            SidebarSnapshot snapshot = SidebarSnapshot.get(sb, objective);
            List<Score> visible = SidebarScoreboardLayout.visibleScoresForSidebarLayout(sb, objective);
            boolean previousRowIsKillEffect = false;
            for (Score sc : visible) {
                if (sc == null || sc.getPlayerName() == null) {
                    continue;
                }
                String line = snapshot.formatted(sc);
                if (line == null) {
                    line = ScorePlayerTeam.formatPlayerName(sb.getPlayersTeam(sc.getPlayerName()), sc.getPlayerName());
                }
                if (ScoreboardDateHider.isKitSidebarTextOrFragment(line)) {
                    return previousRowIsKillEffect;
                }
                String plain = MinecraftFormatting.stripSectionCodes(line).trim();
                previousRowIsKillEffect = plain.regionMatches(true, 0, "ke:", 0, 3);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Paint {@code KE: …} on the Kit row using the same geometry as vanilla 1.8.9 {@code GuiIngame#renderScoreboard}
     * (sorted lines, drop {@code #}, last 15, {@link Collections#reverse}, then {@code y = m - (n - j) * fontHeight}).
     * Called from mixin RETURN so layout matches CustomScoreboard-style "own the column" without replacing vanilla.
     */
    public static void drawSidebarKillEffectAfterVanillaScoreboard(Minecraft mc, ScoreObjective objective, ScaledResolution sr) {
        try {
            if (sidebarKeInlineDrawnThisScoreboardPass) {
                return;
            }
            if (sidebarRealRowRewriteActive) {
                return;
            }
            if (!shouldInjectSidebarKillEffectLine() || mc == null || sr == null || objective == null || mc.theWorld == null) {
                return;
            }
            String keDisp = resolveSidebarKillEffectDisplay();
            if (keDisp == null || keDisp.isEmpty()) {
                return;
            }
            if (shouldSkipSidebarKeBecauseAlreadyPainted()) {
                return;
            }
            Scoreboard sb = mc.theWorld.getScoreboard();
            if (sb == null) {
                return;
            }
            List<Score> visible = SidebarScoreboardLayout.visibleScoresForSidebarLayout(sb, objective);
            if (visible.isEmpty()) {
                return;
            }
            FontRenderer fr = mc.fontRendererObj;
            if (fr == null) {
                return;
            }

            ArrayList<Score> drawOrder = new ArrayList<>(visible);
            Collections.reverse(drawOrder);

            int kitJ = -1;
            int width = fr.getStringWidth(objective.getDisplayName());
            for (int j = 0; j < drawOrder.size(); j++) {
                Score sc = drawOrder.get(j);
                ScorePlayerTeam team = sb.getPlayersTeam(sc.getPlayerName());
                String line = ScorePlayerTeam.formatPlayerName(team, sc.getPlayerName());
                if (line == null) {
                    line = "";
                }
                width = Math.max(width, fr.getStringWidth(line));
                if (ScoreboardDateHider.isKitSidebarTextOrFragment(line)) {
                    kitJ = j;
                }
            }
            int n = drawOrder.size();
            int fh = fr.FONT_HEIGHT;
            int m = sr.getScaledHeight() / 2 + n * fh / 3;
            int right = sr.getScaledWidth() - 2;
            float yKe;
            if (kitJ >= 0) {
                yKe = m - (n - kitJ) * fh + fh;
            } else {
                int trackedY = sidebarLastKitAnchorDrawY;
                if (trackedY < 0 || sidebarLastKitAnchorWasHidden) {
                    return;
                }
                yKe = trackedY + fh;
            }

            int padding = 3;
            float left = sr.getScaledWidth() - width - padding;
            drawSidebarKillEffectLineAt(fr, left, yKe, right);
            sidebarKeInjectedThisScoreboardPass = true;
        } catch (Throwable ignored) {
        }
    }

    /**
     * Call immediately after the scoreboard paints a visible kit line so {@code KE:} shares the same FontRenderer /
     * GL state as the sidebar. One shot per {@link #beginSidebarScoreboardRenderPass()}.
     */
    public static void tryDrawSidebarKillEffectInlineAfterKitRow(FontRenderer fr, String drawnText, float y) {
        try {
            if (sidebarKeInlineDrawnThisScoreboardPass || fr == null || drawnText == null || drawnText.isEmpty()) {
                return;
            }
            if (sidebarRealRowRewriteActive) {
                return;
            }
            if (!shouldInjectSidebarKillEffectLine()) {
                return;
            }
            if (!ScoreboardDateHider.isKitSidebarTextOrFragment(drawnText)) {
                return;
            }
            String plain = EnumChatFormatting.getTextWithoutFormattingCodes(drawnText);
            if (plain != null && plain.toLowerCase(Locale.ROOT).contains("ke:")) {
                return;
            }
            if (shouldSkipSidebarKeBecauseAlreadyPainted()) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                return;
            }
            ScaledResolution sr = new ScaledResolution(mc);
            drawSidebarKillEffectLineAt(fr, 0f, y + fr.FONT_HEIGHT, sr.getScaledWidth() - 2);
            sidebarKeInlineDrawnThisScoreboardPass = true;
            sidebarKeInjectedThisScoreboardPass = true;
        } catch (Throwable ignored) {
        }
    }

    /** Screen-space Y of the Kit line drawn this frame, or {@code -1}. */
    public static int getSidebarLastKitAnchorDrawY() {
        return sidebarLastKitAnchorDrawY;
    }

    public static void noteSidebarDraw(String rawText, int anchorDrawY, boolean kitFragment, boolean kitHiddenByConfig) {
        if (rawText == null || rawText.isEmpty()) {
            return;
        }
        try {
            int i = sidebarRecentDrawIndex;
            sidebarRecentDraws[i % SIDEBAR_DEBUG_RING_SIZE] = rawText;
            sidebarRecentDrawIndex = i + 1;
            if (kitFragment) {
                sidebarLastKitAnchorRaw = rawText;
                sidebarLastKitAnchorWasHidden = kitHiddenByConfig;
                if (anchorDrawY >= 0) {
                    sidebarLastKitAnchorDrawY = anchorDrawY;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static String[] getSidebarRecentDrawsSnapshot() {
        try {
            String[] out = new String[SIDEBAR_DEBUG_RING_SIZE];
            int end = sidebarRecentDrawIndex;
            for (int k = 0; k < SIDEBAR_DEBUG_RING_SIZE; k++) {
                int src = (end - SIDEBAR_DEBUG_RING_SIZE + k);
                if (src < 0) {
                    out[k] = null;
                } else {
                    out[k] = sidebarRecentDraws[src % SIDEBAR_DEBUG_RING_SIZE];
                }
            }
            return out;
        } catch (Throwable ignored) {
            return new String[0];
        }
    }

    public static String getSidebarLastKitAnchorRaw() {
        return sidebarLastKitAnchorRaw;
    }

    public static boolean wasSidebarLastKitAnchorHidden() {
        return sidebarLastKitAnchorWasHidden;
    }

    /** Matches KillCounter's loose Blitz Star 2-minute line (Hypixel wording/punctuation changes). */
    private static boolean isBlitzStarTwoMinuteReleaseMessage(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        String m = message.replaceAll("\\s+", " ").trim();
        if (m.contains("The Blitz Star will be released in 2 minutes!")) {
            return true;
        }
        if (!m.toLowerCase(Locale.ROOT).contains("blitz star")) {
            return false;
        }
        String lower = m.toLowerCase(Locale.ROOT);
        if (!lower.contains("2 minute")) {
            return false;
        }
        return lower.contains("released") || lower.contains("release") || lower.contains("releas");
    }

    public static void onChatMessage(String raw, int chatType) {
        // Hypixel may use chat, system, or action-bar S02 types for its own notices. A type-only
        // check dropped real kill/effect notices; validate the recognized message and reject the
        // visible speaker prefix used for player chat instead. Type 0/1/2 are the 1.8.9 S02 types.
        if (chatType < 0 || chatType > 2 || raw == null) {
            return;
        }
        String msg = raw.replaceAll("\\s+", " ").trim();
        if (msg.isEmpty()) {
            return;
        }

        Matcher gainedKill = KILL_GAINED_FOR_KILLING_PLAYER.matcher(msg);
        if (gainedKill.find()) {
            if (hasChatSpeakerPrefixBeforeMatch(msg, gainedKill.start())) {
                return;
            }
            String rawEffect = gainedKill.group(1).trim();
            String display = normalizeKillEffectDisplayFromChat(rawEffect);
            if (display == null || display.isEmpty()) {
                display = rawEffect;
            }
            sidebarKillEffectDisplay = display;
            String nl = rawEffect.toLowerCase(Locale.ROOT);
            boolean flame = nl.contains("flaming arrows") || nl.contains("flaming arrow");
            chatEquipped = (byte) (flame ? 1 : 0);
            persistLocalAccountKillEffectFromDisplay(sidebarKillEffectDisplay);
            String code = effectCodeUpperFromSidebarDisplay(sidebarKillEffectDisplay);
            if (code != null) {
                me.napim.gomod.command.KillEffectCommand.applySelfKillEffectFromLiveGameChat(code);
            }
            noteLiveGameKillEffectChatPriority();
            startOrRefreshEquippedProc();
            return;
        }

        Matcher givenKe = KILL_EFFECT_GIVEN_RANDOM_STYLE.matcher(msg);
        if (givenKe.find()) {
            if (hasChatSpeakerPrefixBeforeMatch(msg, givenKe.start())) {
                return;
            }
            sidebarKillEffectDisplay = "Random";
            chatEquipped = 0;
            persistLocalAccountKillEffectFromDisplay("Random");
            me.napim.gomod.command.KillEffectCommand.applySelfKillEffectFromLiveGameChat("RANDOM");
            noteLiveGameKillEffectChatPriority();
            startOrRefreshEquippedProc();
            return;
        }

        Matcher ke = KILL_EFFECT_LINE.matcher(msg);
        if (ke.find()) {
            if (hasChatSpeakerPrefixBeforeMatch(msg, ke.start())) {
                return;
            }
            String name = ke.group(1).trim();
            sidebarKillEffectDisplay = normalizeKillEffectDisplayFromChat(name);
            String nl = name.toLowerCase(Locale.ROOT);
            boolean flame = nl.contains("flaming arrows") || nl.contains("flaming arrow");
            chatEquipped = (byte) (flame ? 1 : 0);
            persistLocalAccountKillEffectFromDisplay(sidebarKillEffectDisplay);
            String code = effectCodeUpperFromSidebarDisplay(sidebarKillEffectDisplay);
            if (code != null) {
                me.napim.gomod.command.KillEffectCommand.applySelfKillEffectFromLiveGameChat(code);
            }
            noteLiveGameKillEffectChatPriority();
            return;
        }

        if (isBlitzStarTwoMinuteReleaseMessage(msg)) {
            int blitzStarIndex = msg.toLowerCase(Locale.ROOT).indexOf("blitz star");
            if (hasChatSpeakerPrefixBeforeMatch(msg, blitzStarIndex)) {
                return;
            }
            resistanceUnlocked = true;
            return;
        }

        Matcher as = ASSIST_LINE.matcher(msg);
        if (as.find()) {
            if (hasChatSpeakerPrefixBeforeMatch(msg, as.start())) {
                return;
            }
            try {
                int pct = Integer.parseInt(as.group(1));
                if (pct >= ASSIST_MIN_PERCENT) {
                    startOrRefreshEquippedProc();
                }
            } catch (NumberFormatException ignored) {
            }
            return;
        }
        Matcher as2 = ASSIST_LINE_NO_FINISHER.matcher(msg);
        if (as2.find()) {
            if (hasChatSpeakerPrefixBeforeMatch(msg, as2.start())) {
                return;
            }
            try {
                int pct = Integer.parseInt(as2.group(1));
                if (pct >= ASSIST_MIN_PERCENT) {
                    startOrRefreshEquippedProc();
                }
            } catch (NumberFormatException ignored) {
            }
            return;
        }

        if (tryMatchSelfKill(msg) || tryMatchBroadcastKillAsKiller(msg)) {
            startOrRefreshEquippedProc();
        }
    }

    /**
     * Hypixel often prefixes kill lines (+XP, lobby name); match {@code you killed …} anywhere after trim.
     */
    private static boolean tryMatchSelfKill(String msg) {
        String lower = msg.toLowerCase(Locale.ROOT);
        for (String verb : SELF_KILL_VERBS) {
            if (containsSelfKillPhrase(lower, "you " + verb)) {
                return true;
            }
            if (containsSelfKillPhrase(lower, "you have " + verb)) {
                return true;
            }
        }
        return false;
    }

    /** True if this chat line credits the local player as killer in third-person form. */
    private static boolean tryMatchBroadcastKillAsKiller(String msg) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return false;
        }
        String self = mc.thePlayer.getName();
        if (self == null || self.isEmpty()) {
            return false;
        }
        Matcher m = BROADCAST_KILL_LINE.matcher(msg);
        if (!m.find()) {
            return false;
        }
        int deathPhrase = msg.toLowerCase(Locale.ROOT).indexOf(" was ");
        if (hasChatSpeakerPrefixBeforeMatch(msg, deathPhrase)) {
            return false;
        }
        String killer = m.group(1).trim();
        return killerNameCreditsSelf(killer, self);
    }

    /** Full match or last token (e.g. {@code [MVP++] Nyamih} → {@code Nyamih}). */
    private static boolean killerNameCreditsSelf(String killerRaw, String self) {
        if (killerRaw.equalsIgnoreCase(self)) {
            return true;
        }
        int sp = killerRaw.lastIndexOf(' ');
        if (sp >= 0 && sp + 1 < killerRaw.length()) {
            return killerRaw.substring(sp + 1).equalsIgnoreCase(self);
        }
        return false;
    }

    private static boolean hasChatSpeakerPrefixBeforeMatch(String msg, int matchStart) {
        if (msg == null || matchStart <= 0) {
            return false;
        }
        String prefix = msg.substring(0, Math.min(matchStart, msg.length())).trim();
        if (prefix.isEmpty()) {
            return false;
        }
        if (prefix.indexOf(':') >= 0) {
            return true;
        }
        String lower = prefix.toLowerCase(Locale.ROOT);
        return lower.endsWith(" whispers")
                || lower.endsWith(" whispers to you")
                || lower.endsWith(" says")
                || lower.endsWith(" shouts");
    }

    private static boolean containsSelfKillPhrase(String lowerFull, String phrase) {
        int i = lowerFull.indexOf(phrase);
        if (i < 0) {
            return false;
        }
        int after = i + phrase.length();
        if (after >= lowerFull.length()) {
            return false;
        }
        char c = lowerFull.charAt(after);
        return (c == ' ' || Character.isLetter(c)) && !hasChatSpeakerPrefixBeforeMatch(lowerFull, i);
    }

    private static void startOrRefreshProc() {
        flameProcEndMs = System.currentTimeMillis() + PROC_DURATION_MS;
    }

    private static void startOrRefreshOtherKillEffectProc(String effectCode, long durationMs) {
        if (effectCode == null || durationMs <= 0L) {
            return;
        }
        otherKillEffectProcCode = effectCode;
        otherKillEffectProcEndMs = System.currentTimeMillis() + durationMs;
    }

    private static void startOrRefreshEquippedProc() {
        String effectCode = getEquippedKillEffectCode();
        if (effectCode == null) {
            return;
        }
        switch (effectCode) {
            case "FLAME":
            case "RAPID_FIRE":
                if (hasEquippedFlamingArrows()) {
                    startOrRefreshProc();
                }
                break;
            case "SPEED":
                startOrRefreshOtherKillEffectProc("SPEED", SPEED_DURATION_MS);
                break;
            case "REGENERATION":
                startOrRefreshOtherKillEffectProc("REGENERATION", REGEN_DURATION_MS);
                break;
            case "RESISTANCE":
                if (resistanceUnlocked) {
                    startOrRefreshOtherKillEffectProc("RESISTANCE", RESISTANCE_DURATION_MS);
                }
                break;
            default:
                break;
        }
    }

    /** World leave / game boundary: proc does not persist across matches. */
    public static void clearActiveProcTimer() {
        flameProcEndMs = 0L;
        otherKillEffectProcEndMs = 0L;
        otherKillEffectProcCode = null;
        resistanceUnlocked = false;
    }

    public static void onTabstatsCachesCleared() {
        apiSaysFlame = false;
    }

    /**
     * Call only when Hypixel returned an explicit {@code HungerGames.afterkill} for this profile.
     */
    public static void onHypixelAfterkillForProfile(String tabProfileName, String effectCode) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || tabProfileName == null || effectCode == null) {
            return;
        }
        if (!tabProfileName.equalsIgnoreCase(mc.thePlayer.getName())) {
            return;
        }
        String u = effectCode.toUpperCase(Locale.ROOT);
        apiSaysFlame = "FLAME".equals(u) || "RAPID_FIRE".equals(u);
    }

    private static String normalizeKillEffectDisplayFromChat(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        String l = t.toLowerCase(Locale.ROOT);
        if (l.contains("flaming arrow")) {
            return "Flaming Arrows";
        }
        if (l.contains("regeneration")) {
            return "Regeneration";
        }
        if (l.contains("resistance")) {
            return "Resistance";
        }
        if (l.contains("speed")) {
            return "Speed";
        }
        if (l.contains("level up") || l.replace(" ", "").equals("levelup")) {
            return "Level Up";
        }
        if (l.contains("gravedigger") || l.contains("grave digger")) {
            return "Gravedigger";
        }
        return t;
    }

    private static String killEffectDisplayFromApiCode(String codeUc) {
        if (codeUc == null) {
            return null;
        }
        switch (codeUc) {
            case "NONE":
                return null;
            case "SPEED":
                return "Speed";
            case "REGENERATION":
                return "Regeneration";
            case "RESISTANCE":
                return "Resistance";
            case "FLAME":
            case "RAPID_FIRE":
                return "Flaming Arrows";
            case "LEVEL_UP":
            case "LEVELUP":
                return "Level Up";
            case "GRAVEDIGGER":
                return "Gravedigger";
            case "RANDOM":
                return "Random";
            default:
                return null;
        }
    }

    /**
     * In-game chat lines take precedence ({@link #KILL_GAINED_FOR_KILLING_PLAYER}, {@link #KILL_EFFECT_GIVEN_RANDOM_STYLE},
     * {@link #KILL_EFFECT_LINE}), then persisted account data, proc inference, then /tabstats API refresh.
     */
    public static String resolveSidebarKillEffectDisplay() {
        String d = sidebarKillEffectDisplay;
        if (d != null && !d.trim().isEmpty()) {
            return d.trim();
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return null;
        }
        String persisted = resolvePersistedLocalAccountKillEffectDisplay(mc);
        if (persisted != null && !persisted.isEmpty()) {
            sidebarKillEffectDisplay = persisted;
            return persisted;
        }
        String inferred = inferSidebarKillEffectDisplayFromActiveProc();
        if (inferred != null && !inferred.isEmpty()) {
            return inferred;
        }
        requestSelfSidebarKillEffectRefreshAsync(mc);
        return null;
    }

    private static String inferSidebarKillEffectDisplayFromActiveProc() {
        if (otherKillEffectProcCode != null && System.currentTimeMillis() < otherKillEffectProcEndMs) {
            String fromProc = killEffectDisplayFromApiCode(otherKillEffectProcCode.trim().toUpperCase(Locale.ROOT));
            if (fromProc != null && !fromProc.isEmpty()) {
                return fromProc;
            }
        }
        if (System.currentTimeMillis() < flameProcEndMs && hasEquippedFlamingArrows()) {
            return "Flaming Arrows";
        }
        return null;
    }

    private static void requestSelfSidebarKillEffectRefreshAsync(Minecraft mc) {
        if (mc == null || mc.thePlayer == null || selfEffectRefreshInFlight) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < selfEffectRefreshEarliestAtMs) {
            return;
        }
        String apiKey = ApiKeyManager.getApiKey("hypixel");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            selfEffectRefreshEarliestAtMs = now + SELF_EFFECT_REFRESH_COOLDOWN_MS;
            return;
        }
        String uuid = mc.thePlayer.getUniqueID() == null ? null : mc.thePlayer.getUniqueID().toString().replace("-", "");
        if (uuid == null || uuid.isEmpty()) {
            selfEffectRefreshEarliestAtMs = now + SELF_EFFECT_RETRY_BACKOFF_MS;
            return;
        }
        selfEffectRefreshInFlight = true;
        final Object requestedWorld = mc.theWorld;
        selfEffectRefreshEarliestAtMs = now + SELF_EFFECT_REFRESH_COOLDOWN_MS;
        new Thread(() -> {
            try {
                if (shouldDeferHypixelApiKillEffectForLocalPlayer()) {
                    return;
                }
                JsonObject response = HypixelApiHelper.fetchJsonFresh("https://api.hypixel.net/player?key=" + apiKey + "&uuid=" + uuid);
                if (mc.theWorld != requestedWorld || mc.thePlayer == null
                        || !uuid.equals(mc.thePlayer.getUniqueID().toString().replace("-", ""))
                        || shouldDeferHypixelApiKillEffectForLocalPlayer()) return;
                if (response == null || !response.has("success") || !response.get("success").getAsBoolean()
                        || !response.has("player") || response.get("player").isJsonNull()
                        || !response.get("player").isJsonObject()) {
                    return;
                }
                JsonObject player = response.getAsJsonObject("player");
                if (!player.has("stats") || !player.get("stats").isJsonObject()) {
                    sidebarKillEffectDisplay = null;
                    chatEquipped = -1;
                    return;
                }
                JsonObject stats = player.getAsJsonObject("stats");
                if (!stats.has("HungerGames") || !stats.get("HungerGames").isJsonObject()) {
                    sidebarKillEffectDisplay = null;
                    chatEquipped = -1;
                    return;
                }
                JsonObject blitzStats = stats.getAsJsonObject("HungerGames");
                String effectCode = "SPEED";
                if (blitzStats.has("afterkill") && !blitzStats.get("afterkill").isJsonNull()) {
                    String raw = blitzStats.get("afterkill").getAsString();
                    if (raw != null && !raw.trim().isEmpty()) {
                        effectCode = raw.trim().toUpperCase(Locale.ROOT);
                    }
                }
                onHypixelAfterkillForProfile(mc.thePlayer.getName(), effectCode);
                String fromApi = killEffectDisplayFromApiCode(effectCode);
                if (fromApi != null && !fromApi.isEmpty()) {
                    sidebarKillEffectDisplay = fromApi;
                    persistLocalAccountKillEffectCode(effectCode);
                }
            } catch (Throwable ignored) {
                selfEffectRefreshEarliestAtMs = System.currentTimeMillis() + SELF_EFFECT_RETRY_BACKOFF_MS;
            } finally {
                selfEffectRefreshInFlight = false;
            }
        }, "gomod-self-ke-refresh").start();
    }

    public static String getEquippedKillEffectCode() {
        String display = resolveSidebarKillEffectDisplay();
        if (display == null || display.trim().isEmpty()) {
            return null;
        }
        String code = effectCodeUpperFromSidebarDisplay(display);
        if (code != null) {
            return code;
        }
        return null;
    }

    private static EnumChatFormatting colorForSidebarKillEffect(String display) {
        if (display == null) {
            return EnumChatFormatting.GRAY;
        }
        String l = display.toLowerCase(Locale.ROOT);
        if ("speed".equals(l)) {
            return EnumChatFormatting.AQUA;
        }
        if ("resistance".equals(l)) {
            return EnumChatFormatting.GOLD;
        }
        if ("regeneration".equals(l)) {
            return EnumChatFormatting.LIGHT_PURPLE;
        }
        if ("flaming arrows".equals(l)) {
            return EnumChatFormatting.DARK_RED;
        }
        if ("level up".equals(l)) {
            return EnumChatFormatting.YELLOW;
        }
        if ("gravedigger".equals(l)) {
            return EnumChatFormatting.GREEN;
        }
        if ("random".equals(l)) {
            return EnumChatFormatting.DARK_BLUE;
        }
        return EnumChatFormatting.GRAY;
    }

    /**
     * ARGB-style {@code 0xFFRRGGBB} tint for the effect name when drawing without § codes (sidebar row uses vanilla
     * {@link net.minecraft.client.gui.FontRenderer} int color, which ignores formatting on the whole string).
     */
    public static int getSidebarKillEffectNameColorRgb(String display) {
        if (display == null) {
            return 0xAAAAAA;
        }
        String l = STRIP_FORMATTING.matcher(display).replaceAll("").trim().toLowerCase(Locale.ROOT);
        if ("speed".equals(l)) {
            return 0x55FFFF;
        }
        if ("regeneration".equals(l)) {
            return 0xFF55FF;
        }
        if ("resistance".equals(l)) {
            return 0xFFAA00;
        }
        if ("flaming arrows".equals(l)) {
            return 0xAA0000;
        }
        if ("level up".equals(l)) {
            return 0xFFFF55;
        }
        if ("gravedigger".equals(l)) {
            return 0x55FF55;
        }
        if ("random".equals(l)) {
            return 0x0000AA;
        }
        return 0xAAAAAA;
    }

    /**
     * Draws {@code KE:} in white and the effect name with {@link #getSidebarKillEffectNameColorRgb(String)}.
     * No drop shadow (matches unshadowed kit line on many Hypixel layouts).
     */
    public static void drawSidebarKillEffectLineAt(FontRenderer fr, float anchorLeftX, float y, int rightEdgeExclusive) {
        if (fr == null) {
            return;
        }
        String display = resolveSidebarKillEffectDisplay();
        if (display == null || display.isEmpty()) {
            return;
        }
        String prefix = "KE: ";
        int prefixW = fr.getStringWidth(prefix);
        int nameRgb = 0xFF000000 | (getSidebarKillEffectNameColorRgb(display) & 0xFFFFFF);
        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.enableAlpha();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        try {
            // Right-align to the scoreboard edge; long names extend left (no ellipsis). Never clamp x forward (e.g. to
            // 2f) — that pinned the left edge and clipped the name on the right.
            int nameW = fr.getStringWidth(display);
            int totalW = prefixW + nameW;
            float xDraw = rightEdgeExclusive - 2f - totalW;
            fr.drawString(prefix, xDraw, y, 0xFFFFFFFF, false);
            fr.drawString(display, xDraw + prefixW, y, nameRgb, false);
        } finally {
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.popMatrix();
        }
    }

    /**
     * Extra scoreboard row under {@code Kit:}, only on Blitz sidebar when we know an effect.
     */
    public static boolean shouldInjectSidebarKillEffectLine() {
        if (!ConfigManager.isHideScoreboardValuesEnabled()) {
            return false;
        }
        if (!ConfigManager.isSidebarKillEffectLineEnabled()) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            return false;
        }
        if (!GameContext.isOnHypixel(mc)) {
            return false;
        }
        boolean blitzSidebar = LocalDisguise.isBlitzSgSidebarActive()
                || GameContext.isBlitzGameActive(mc)
                || GameContext.isBlitzPregameLobby(mc)
                || GameContext.isBlitzCagePhaseSidebar(mc);
        if (!blitzSidebar) {
            return false;
        }
        String d = resolveSidebarKillEffectDisplay();
        return d != null && !d.isEmpty();
    }

    /** {@code §fKE: §?Name} with {@code KE:} unstyled white and name tinted; {@code null} if unknown or disabled in settings. */
    public static String getFormattedSidebarKillEffectLine() {
        if (!ConfigManager.isHideScoreboardValuesEnabled()) {
            return null;
        }
        if (!ConfigManager.isSidebarKillEffectLineEnabled()) {
            return null;
        }
        String d = resolveSidebarKillEffectDisplay();
        if (d == null || d.isEmpty()) {
            return null;
        }
        EnumChatFormatting c = colorForSidebarKillEffect(d);
        return EnumChatFormatting.WHITE + "KE: " + c + d + EnumChatFormatting.RESET;
    }

    /**
     * Inlines kill effect into the same sidebar row as {@code Kit:} (same idea as disguise rewriting stat lines),
     * so KE participates in vanilla scoreboard layout instead of a second painted line.
     */
    public static String mergeKillEffectSuffixIntoSidebarKitLine(String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }
        if (!shouldInjectSidebarKillEffectLine()) {
            return line;
        }
        if (!ScoreboardDateHider.isKitSidebarTextOrFragment(line)) {
            return line;
        }
        String ke = getFormattedSidebarKillEffectLine();
        if (ke == null || ke.isEmpty()) {
            return line;
        }
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(line);
        if (plain != null) {
            String p = plain.toLowerCase(Locale.ROOT);
            if (p.contains("ke:")) {
                return line;
            }
        }
        return line + EnumChatFormatting.GRAY + "  " + ke;
    }

    public static boolean hasEquippedFlamingArrows() {
        if (chatEquipped == 0) {
            return false;
        }
        if (chatEquipped == 1) {
            return true;
        }
        return apiSaysFlame;
    }

    public static boolean shouldShowMoreHudFlameTimer(boolean moreHudMaster) {
        if (!moreHudMaster) {
            return false;
        }
        if (!hasEquippedFlamingArrows()) {
            return false;
        }
        return System.currentTimeMillis() < flameProcEndMs;
    }

    public static boolean shouldShowMoreHudOtherKillEffectTimer(boolean moreHudMaster) {
        if (!moreHudMaster) {
            return false;
        }
        String effectCode = getEquippedKillEffectCode();
        if (effectCode == null || "FLAME".equals(effectCode) || "RAPID_FIRE".equals(effectCode)) {
            return false;
        }
        if (!effectCode.equalsIgnoreCase(otherKillEffectProcCode == null ? "" : otherKillEffectProcCode)) {
            return false;
        }
        return System.currentTimeMillis() < otherKillEffectProcEndMs;
    }

    public static String getActiveOtherKillEffectCode() {
        if (otherKillEffectProcCode == null || System.currentTimeMillis() >= otherKillEffectProcEndMs) {
            return null;
        }
        return otherKillEffectProcCode;
    }

    public static long getOtherKillEffectRemainingMs() {
        long rem = otherKillEffectProcEndMs - System.currentTimeMillis();
        return rem > 0 ? rem : 0L;
    }

    public static String formatOtherKillEffectTimerPlain() {
        return formatRemainingSeconds(getOtherKillEffectRemainingMs());
    }

    public static String formatOtherKillEffectSecondsPlain() {
        return formatRemainingSeconds(getOtherKillEffectRemainingMs());
    }

    public static String previewOtherKillEffectTimerPlain() {
        return String.format(Locale.US, "%.1f", REGEN_DURATION_MS / 1000.0);
    }

    public static String previewOtherKillEffectSecondsPlain() {
        return String.format(Locale.US, "%.1f", REGEN_DURATION_MS / 1000.0);
    }

    public static String previewOtherKillEffectDisplayName() {
        return "Regeneration";
    }

    public static String previewOtherKillEffectCode() {
        return "REGENERATION";
    }

    private static String formatKillEffectDisplayName(String effectCode) {
        if (effectCode == null) {
            return "Regeneration";
        }
        switch (effectCode) {
            case "SPEED":
                return "Speed";
            case "RESISTANCE":
                return "Resistance";
            case "REGENERATION":
                return "Regeneration";
            case "FLAME":
            case "RAPID_FIRE":
            default:
                return "Flaming Arrows";
        }
    }

    private static String formatRemainingSeconds(long remainingMs) {
        long safe = remainingMs > 0L ? remainingMs : 0L;
        double sec = safe / 1000.0;
        return String.format(Locale.US, "%.1f", sec);
    }

    /** Maps sidebar / chat display to Hypixel-style afterkill codes for TAB + {@link me.napim.gomod.command.KillEffectCommand}. */
    private static String effectCodeUpperFromSidebarDisplay(String display) {
        if (display == null) {
            return null;
        }
        String lower = display.trim().toLowerCase(Locale.ROOT);
        if (lower.contains("flaming arrow")) {
            return "FLAME";
        }
        if ("speed".equals(lower) || lower.startsWith("speed ")) {
            return "SPEED";
        }
        if (lower.contains("resistance")) {
            return "RESISTANCE";
        }
        if (lower.contains("regeneration")) {
            return "REGENERATION";
        }
        if (lower.contains("level up") || lower.replace(" ", "").equals("levelup")) {
            return "LEVEL_UP";
        }
        if (lower.contains("gravedigger") || lower.contains("grave digger")) {
            return "GRAVEDIGGER";
        }
        if ("random".equals(lower)) {
            return "RANDOM";
        }
        return null;
    }

    private static void persistLocalAccountKillEffectFromDisplay(String display) {
        if (display == null || display.trim().isEmpty()) {
            return;
        }
        String code = null;
        String lower = display.trim().toLowerCase(Locale.ROOT);
        if (lower.contains("flaming arrow")) {
            code = "FLAME";
        } else if ("speed".equals(lower)) {
            code = "SPEED";
        } else if ("resistance".equals(lower)) {
            code = "RESISTANCE";
        } else if ("regeneration".equals(lower)) {
            code = "REGENERATION";
        } else if ("level up".equals(lower)) {
            code = "LEVEL_UP";
        } else if ("gravedigger".equals(lower)) {
            code = "GRAVEDIGGER";
        } else if ("random".equals(lower)) {
            code = "RANDOM";
        }
        persistLocalAccountKillEffectCode(code);
    }

    private static void persistLocalAccountKillEffectCode(String effectCode) {
        String uuid = getLocalAccountUuidKey();
        if (uuid.isEmpty() || effectCode == null || effectCode.trim().isEmpty()) {
            return;
        }
        ConfigManager.setBlitzKillEffectForAccountUuid(uuid, effectCode);
    }

    private static String resolvePersistedLocalAccountKillEffectDisplay(Minecraft mc) {
        String uuid = getLocalAccountUuidKey();
        if (uuid.isEmpty()) {
            return null;
        }
        String code = ConfigManager.getBlitzKillEffectForAccountUuid(uuid);
        if (code == null || code.trim().isEmpty()) {
            return null;
        }
        String display = killEffectDisplayFromApiCode(code.trim().toUpperCase(Locale.ROOT));
        if (display == null || display.isEmpty()) {
            return null;
        }
        String u = code.trim().toUpperCase(Locale.ROOT);
        apiSaysFlame = "FLAME".equals(u) || "RAPID_FIRE".equals(u);
        if (!apiSaysFlame) {
            chatEquipped = 0;
        }
        return display;
    }

    private static String getLocalAccountUuidKey() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                return "";
            }
            if (mc.thePlayer != null && mc.thePlayer.getUniqueID() != null) {
                return mc.thePlayer.getUniqueID().toString().replace("-", "").toLowerCase(Locale.ROOT);
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** Plain text line (no §); caller applies {@link net.minecraft.util.EnumChatFormatting} via config color. */
    public static String formatFlameTimerPlain() {
        long rem = flameProcEndMs - System.currentTimeMillis();
        if (rem < 0) {
            rem = 0;
        }
        double sec = rem / 1000.0;
        return "Flaming Arrows " + String.format(Locale.US, "%.1f", sec);
    }

    /** Timer only, e.g. {@code 20.0s} (compact HUD). */
    public static String formatFlameSecondsPlain() {
        long rem = flameProcEndMs - System.currentTimeMillis();
        if (rem < 0) {
            rem = 0;
        }
        double sec = rem / 1000.0;
        return String.format(Locale.US, "%.1f", sec);
    }

    /** HUD Editor preview when the row is enabled. */
    public static String previewFlameTimerPlain() {
        return "Flaming Arrows " + String.format(Locale.US, "%.1f", 20.0);
    }

    public static String previewFlameSecondsPlain() {
        return String.format(Locale.US, "%.1f", 20.0);
    }

    /** -1 unknown, 0 non-flame from chat, 1 flame from chat. */
    public static int getChatEquippedState() {
        return chatEquipped;
    }

    public static boolean getApiSaysFlame() {
        return apiSaysFlame;
    }

    public static long getFlameProcEndWallMs() {
        return flameProcEndMs;
    }

    public static long getProcRemainingMs() {
        long rem = flameProcEndMs - System.currentTimeMillis();
        return rem > 0 ? rem : 0L;
    }

    /** 1 = full proc remaining, 0 = empty (bar drains as time runs out). */
    public static float getProcRemainingFraction() {
        long rem = getProcRemainingMs();
        if (rem <= 0L) {
            return 0f;
        }
        return Math.min(1f, rem / (float) PROC_DURATION_MS);
    }

    /** Debug: start 20s proc if equipped (no chat kill required). */
    public static boolean debugSimulateProc() {
        if (!hasEquippedFlamingArrows()) {
            return false;
        }
        startOrRefreshProc();
        return true;
    }
}
