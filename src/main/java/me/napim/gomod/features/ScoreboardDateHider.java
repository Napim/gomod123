package me.napim.gomod.features;

import me.napim.gomod.util.SidebarSnapshot;
import me.napim.gomod.util.GameContext;
import me.napim.gomod.util.MinecraftFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.weavemc.loader.api.event.PacketEvent;
import net.weavemc.loader.api.event.RenderGameOverlayEvent;
import net.weavemc.loader.api.event.SubscribeEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Hides selected Hypixel scoreboard sidebar strings (date/game id and/or kit line).
 *
 * <p>Uses a master toggle plus independent sub-toggles so kit and date/game-ID can be controlled
 * separately (fixes cases where a combined matcher broke date hiding when a kit line was present).
 */
public class ScoreboardDateHider {

    // Blitz date formats vary a bit across lobby/pregame/ingame renders.
    // Support dd/MM/yy(yy) and yyyy-MM-dd, with '/', '-' or '.' separators.
    private static final Pattern DATE_PATTERN = Pattern.compile(
            "\\b(?:\\d{2}[/\\-.]\\d{2}[/\\-.](?:\\d{2}|\\d{4})|\\d{4}[/\\-.]\\d{2}[/\\-.]\\d{2})\\b");
    // Hypixel "game id" tokens are usually in the shape: m<digits><optional suffix>.
    // IMPORTANT: avoid matching generic "digits + letters" like "2x" or words ending in "ed".
    private static final Pattern GAME_ID_TOKEN = Pattern.compile("(?i)^m\\d+[a-z0-9]{0,32}$");
    // Sometimes the game-id appears as <digits><letters> without the leading `m`.
    // Require at least 2 letters to avoid matching multiplier-like patterns such as "2x".
    private static final Pattern GAME_ID_DIGITS_LETTERS_TOKEN = Pattern.compile("(?i)^\\d{2,}[a-z]{2,12}$");
    // Loose evidence pattern for short game-id fragments like "7C" / "9E".
    // We still exclude digit+`x` multiplier tokens inside the matcher to avoid "2x Coins + Maxed".
    private static final Pattern GAME_ID_DIGITS_LETTERS_TOKEN_LOOSE = Pattern.compile("(?i)^\\d{1,}[a-z]{1,12}$");
    // Some formats look like <letters><digits><letters> (e.g. "L17C").
    private static final Pattern GAME_ID_LETTER_DIGITS_LETTERS_TOKEN_LOOSE =
            Pattern.compile("(?i)^[a-z]{1,2}\\d{1,}[a-z]{1,12}$");
    /** Hypixel Blitz "Kit:" / variants; allow trailing empty and non-ASCII kit names after strip. */
    private static final Pattern KIT_LINE_PATTERN = Pattern.compile("(?i)^\\s*kit\\s*:.*");
    /** Lunar/OF sometimes split the kit row across draws; match {@code kit:} as a word (avoids {@code kits:} on "Get Kits"). */
    private static final Pattern KIT_LINE_FRAGMENT_PATTERN = Pattern.compile("(?i)\\bkit\\s*:");
    /**
     * In-match Blitz sidebars sometimes omit {@code Kit:} (e.g. "Your Kit", "Selected Kit") while still drawing the row
     * through the same scoreboard path.
     */
    private static final Pattern KIT_LINE_LOOSE_FRAGMENT_PATTERN = Pattern.compile(
            "(?i)(\\bselected\\s+kit\\b|\\byour\\s+kit\\b)");

    private static final Set<String> blankTeamNames = new HashSet<String>();
    private static final Map<String, String> savedDateGameIdValues = new HashMap<String, String>();
    private static final Map<String, String> savedKitValues = new HashMap<String, String>();
    private static final Map<String, Integer> savedFooterScores = new HashMap<String, Integer>();

    private static long lastFullApplyTime = 0L;
    private static final long FULL_APPLY_INTERVAL_MS = 500L;
    private static boolean wasHideActive = false;
    private static long lastForceRefreshAt = 0L;

    /** Sidebar tweaks are intended for Hypixel only. */
    public static boolean isHypixelSidebarTweaksContext() {
        return isHypixelSidebarTweaksContext(Minecraft.getMinecraft());
    }

    public static boolean isHypixelSidebarTweaksContext(Minecraft mc) {
        return mc != null && GameContext.isOnHypixel(mc);
    }

    @SubscribeEvent
    public void onPacket(PacketEvent.Receive event) {
        // Never rewrite S3E team packets. Lunar consumes them for nametags and TeamView before
        // the sidebar is painted, so even a temporary prefix/member edit leaks into player state.
        if (GomodFixes.isWeaveRuntime()) {
            return;
        }
        if (event == null || event.getPacket() == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (!isHypixelSidebarTweaksContext(mc)) {
            return;
        }
        if (isReplayRenderOnlyMode(mc)) {
            return;
        }
        if (!ConfigManager.isHideScoreboardValuesEnabled()) {
            return;
        }
        if (!ConfigManager.isHideDateGameIdEnabled() && !ConfigManager.isHideKitScoreboardEnabled()) {
            return;
        }
        if (!event.getPacket().getClass().getName().endsWith("S3EPacketTeams")) {
            return;
        }

        Object packet = event.getPacket();
        if (hasDateGameIdField(packet) || hasKitField(packet)) {
            blankTeamStringFields(packet, null);
        }
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        boolean enabled = ConfigManager.isScoreboardPrivacyHideActive() && isHypixelSidebarTweaksContext(mc);
        long now = System.currentTimeMillis();
        boolean replayRenderOnly = isReplayRenderOnlyMode(mc);

        if (enabled) {
            if (replayRenderOnly) {
                if (hasSavedState()) {
                    restoreAll(true);
                    forceScoreboardRefresh();
                }
            } else {
                if (mc != null && mc.theWorld != null && mc.theWorld.getScoreboard() != null) {
                    removeScoreEntries(mc.theWorld.getScoreboard());
                }
                if (now - lastFullApplyTime > FULL_APPLY_INTERVAL_MS) {
                    lastFullApplyTime = now;
                    applyLiveHide();
                }
            }
        } else if (wasHideActive || hasSavedState()) {
            restoreAll(replayRenderOnly);
        }
        wasHideActive = enabled;
    }

    public static void applyInstant() {
        applyLiveHide();
        forceScoreboardRefresh();
        applyLiveHide();
    }

    public static void restoreInstant() {
        restoreAll();
        forceScoreboardRefresh();
    }

    /**
     * Called from the scoreboard hide GUI after any toggle changes.
     */
    public static void onGuiToggleScoreboardHide() {
        lastFullApplyTime = 0L;
        Minecraft mc = Minecraft.getMinecraft();
        boolean replayRenderOnly = isReplayRenderOnlyMode(mc);
        if (ConfigManager.isScoreboardPrivacyHideActive()) {
            if (replayRenderOnly) {
                restoreAll(true);
            } else {
                applyLiveHide();
            }
            forceScoreboardRefresh();
            if (replayRenderOnly) {
                restoreAll(true);
            } else {
                applyLiveHide();
            }
            forceScoreboardRefresh();
            scheduleSidebarResyncBurst(mc, 12, true);
        } else {
            restoreAll(replayRenderOnly);
            forceScoreboardRefresh();
            scheduleSidebarResyncBurst(mc, 10, false);
        }
    }

    /** @deprecated use {@link #onGuiToggleScoreboardHide()} */
    @Deprecated
    public static void onGuiToggleHideDate(boolean enabled) {
        onGuiToggleScoreboardHide();
    }

    private static void scheduleSidebarResyncBurst(Minecraft mc, int ticksLeft, boolean hiding) {
        if (mc == null || ticksLeft <= 0) {
            return;
        }
        mc.addScheduledTask(() -> {
            boolean replayRenderOnly = isReplayRenderOnlyMode(mc);
            if (hiding) {
                if (!ConfigManager.isScoreboardPrivacyHideActive()) {
                    return;
                }
                if (replayRenderOnly) {
                    restoreAll(true);
                } else {
                    applyLiveHide();
                }
            } else {
                if (ConfigManager.isScoreboardPrivacyHideActive()) {
                    return;
                }
                restoreAll(replayRenderOnly);
            }
            forceScoreboardRefresh();
            scheduleSidebarResyncBurst(mc, ticksLeft - 1, hiding);
        });
    }

    public static void applyLiveHide() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) {
            return;
        }
        if (!isHypixelSidebarTweaksContext(mc)) {
            restoreAll();
            return;
        }
        if (isReplayRenderOnlyMode(mc)) {
            restoreAll(true);
            return;
        }
        Scoreboard scoreboard = mc.theWorld.getScoreboard();
        if (scoreboard == null) {
            return;
        }
        if (!ConfigManager.isHideHypixelFooterEffective() && !savedFooterScores.isEmpty()) {
            restoreFooterScores(scoreboard, false);
            savedFooterScores.clear();
        }

        blankTeamNames.clear();
        pruneSavedAgainstLive(scoreboard, savedDateGameIdValues);
        pruneSavedAgainstLive(scoreboard, savedKitValues);

        boolean anyTouched = false;
        try {
            Collection<?> teams = scoreboard.getTeams();
            if (teams != null) {
                for (Object obj : teams) {
                    if (!(obj instanceof ScorePlayerTeam)) {
                        continue;
                    }
                    ScorePlayerTeam team = (ScorePlayerTeam) obj;
                    if (ScoreboardTeamSafety.containsOnlinePlayer(team)) {
                        continue;
                    }
                    String teamName = team.getRegisteredName();
                    if (teamName == null || blankTeamNames.contains(teamName)) {
                        continue;
                    }
                    boolean touched = false;
                    if (ConfigManager.isHideDateGameIdEffective() && hasDateGameIdField(team)) {
                        blankDateGameIdFields(team, teamName);
                        touched = true;
                    }
                    if (ConfigManager.isHideKitScoreboardEffective() && hasKitField(team)) {
                        blankKitFields(team, teamName);
                        touched = true;
                    }
                    if (touched) {
                        blankTeamNames.add(teamName);
                        anyTouched = true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        if (anyTouched) {
            SidebarSnapshot.invalidate(scoreboard);
        }
        if (removeScoreEntries(scoreboard)) {
            anyTouched = true;
        }

        // Lobbies sometimes cache the displayed sidebar lines; forcing a scoreboard refresh here
        // makes the hide take effect immediately (no "switch lobby" workaround needed).
        if (anyTouched) {
            long now = System.currentTimeMillis();
            if (now - lastForceRefreshAt > 1500L) {
                lastForceRefreshAt = now;
                forceScoreboardRefresh();
            }
        }
    }

    private static void pruneSavedAgainstLive(Scoreboard scoreboard, Map<String, String> saved) {
        Iterator<Map.Entry<String, String>> it = saved.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String> entry = it.next();
            String key = entry.getKey();
            int sep = key.lastIndexOf("::");
            if (sep < 0) {
                it.remove();
                continue;
            }
            String teamName = key.substring(0, sep);
            String fieldName = key.substring(sep + 2);
            try {
                ScorePlayerTeam team = (ScorePlayerTeam) scoreboard.getTeam(teamName);
                if (team == null) {
                    it.remove();
                    continue;
                }
                Field f = findField(team.getClass(), fieldName);
                if (f == null) {
                    it.remove();
                    continue;
                }
                f.setAccessible(true);
                Object val = f.get(team);
                if (!"".equals(val)) {
                    it.remove();
                    continue;
                }
                blankTeamNames.add(teamName);
            } catch (Throwable t) {
                it.remove();
            }
        }
    }

    public static void restoreAll() {
        restoreAll(false);
    }

    private static void restoreAll(boolean forceRestore) {
        Minecraft mc = Minecraft.getMinecraft();
        Scoreboard scoreboard = (mc != null && mc.theWorld != null) ? mc.theWorld.getScoreboard() : null;

        if (scoreboard != null) {
            restoreMap(scoreboard, savedDateGameIdValues, ScoreboardDateHider::isDateOrGameIdText, forceRestore);
            restoreMap(scoreboard, savedKitValues, ScoreboardDateHider::isKitText, forceRestore);
            restoreFooterScores(scoreboard, forceRestore);
        }
        savedDateGameIdValues.clear();
        savedKitValues.clear();
        savedFooterScores.clear();
        blankTeamNames.clear();
    }

    private static void restoreMap(Scoreboard scoreboard, Map<String, String> saved, java.util.function.Predicate<String> stillMatchesHiddenKind, boolean forceRestore) {
        if (saved.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : saved.entrySet()) {
            String key = entry.getKey();
            int sep = key.lastIndexOf("::");
            if (sep < 0) {
                continue;
            }
            String teamName = key.substring(0, sep);
            String fieldName = key.substring(sep + 2);
            String original = entry.getValue();
            try {
                ScorePlayerTeam team = (ScorePlayerTeam) scoreboard.getTeam(teamName);
                if (team == null) {
                    continue;
                }
                if (ScoreboardTeamSafety.containsOnlinePlayer(team)) {
                    continue;
                }
                Field f = findField(team.getClass(), fieldName);
                if (f == null) {
                    continue;
                }
                f.setAccessible(true);
                Object cur = f.get(team);
                if (cur instanceof String) {
                    String curStr = (String) cur;
                    if (forceRestore || "".equals(curStr) || stillMatchesHiddenKind.test(curStr)) {
                        f.set(team, original);
                        SidebarSnapshot.invalidate(scoreboard);
                        String ln = fieldName.toLowerCase();
                        if (ln.contains("nameprefix")) {
                            callSetter(team, "setNamePrefix", original);
                        } else if (ln.contains("namesuffix")) {
                            callSetter(team, "setNameSuffix", original);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public static void forceScoreboardRefresh() {
        try {
            if (GomodFixes.isWeaveRuntime()) {
                // Lunar observes objective-slot churn globally. The rewritten team/score values
                // are already live; forcing the sidebar out and back in can retrigger TeamView.
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null) {
                return;
            }
            Scoreboard sb = mc.theWorld.getScoreboard();
            ScoreObjective sidebar = sb.getObjectiveInDisplaySlot(1);
            if (sidebar == null) {
                return;
            }

            String origName = sidebar.getDisplayName();
            sidebar.setDisplayName(origName.isEmpty() ? "\u00A7r" : "");
            sidebar.setDisplayName(origName);

            sb.setObjectiveInDisplaySlot(1, null);
            sb.setObjectiveInDisplaySlot(1, sidebar);
        } catch (Throwable ignored) {
        }
    }

    private static boolean removeScoreEntries(Scoreboard scoreboard) {
        if (scoreboard == null || !ConfigManager.isHideHypixelFooterEffective()) {
            return false;
        }
        ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(1);
        if (objective == null) {
            return false;
        }
        boolean removed = false;
        try {
            SidebarSnapshot sidebar = SidebarSnapshot.get(scoreboard, objective);
            for (Score score : sidebar.scores()) {
                if (score == null || score.getPlayerName() == null || score.getPlayerName().startsWith("#")) {
                    continue;
                }
                if (!isHypixelFooterText(sidebar.formatted(score))) {
                    continue;
                }
                if (!savedFooterScores.containsKey(score.getPlayerName())) {
                    savedFooterScores.put(score.getPlayerName(), score.getScorePoints());
                }
                scoreboard.removeObjectiveFromEntity(score.getPlayerName(), objective);
                removed = true;
            }
        } catch (Throwable ignored) {
        }
        return removed;
    }

    private static void restoreFooterScores(Scoreboard scoreboard, boolean forceRestore) {
        if (scoreboard == null || savedFooterScores.isEmpty()) {
            return;
        }
        if (!forceRestore && ConfigManager.isHideHypixelFooterEffective()) {
            return;
        }
        ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(1);
        if (objective == null) {
            return;
        }
        try {
            for (Map.Entry<String, Integer> entry : savedFooterScores.entrySet()) {
                String playerName = entry.getKey();
                Integer points = entry.getValue();
                if (playerName == null || points == null) {
                    continue;
                }
                scoreboard.getValueFromObjective(playerName, objective).setScorePoints(points);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean hasSavedState() {
        return !blankTeamNames.isEmpty()
                || !savedDateGameIdValues.isEmpty()
                || !savedKitValues.isEmpty()
                || !savedFooterScores.isEmpty();
    }

    private static boolean isReplayRenderOnlyMode(Minecraft mc) {
        return mc != null && GameContext.isHypixelReplayWatching(mc);
    }

    private static boolean hasDateGameIdField(Object target) {
        try {
            for (Class<?> cls = target.getClass(); cls != null; cls = cls.getSuperclass()) {
                for (Field f : cls.getDeclaredFields()) {
                    if (f.getType() != String.class) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object val = f.get(target);
                    if (val instanceof String && isDateOrGameIdText((String) val)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean hasKitField(Object target) {
        try {
            for (Class<?> cls = target.getClass(); cls != null; cls = cls.getSuperclass()) {
                for (Field f : cls.getDeclaredFields()) {
                    if (f.getType() != String.class) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object val = f.get(target);
                    if (val instanceof String && isKitText((String) val)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Blanks string fields on a team or packet object: date/game id and/or kit according to active toggles.
     */
    private static void blankTeamStringFields(Object target, String teamName) {
        try {
            boolean hasGameIdToken = false;
            boolean hasDateToken = false;
            if (ConfigManager.isHideDateGameIdEffective()) {
                for (Class<?> cls = target.getClass(); cls != null && !(hasGameIdToken && hasDateToken); cls = cls.getSuperclass()) {
                    for (Field f : cls.getDeclaredFields()) {
                        if (f.getType() != String.class) {
                            continue;
                        }
                        f.setAccessible(true);
                        Object val = f.get(target);
                        if (!(val instanceof String)) {
                            continue;
                        }
                        String s = (String) val;
                        if (s == null || s.isEmpty()) {
                            continue;
                        }
                        if (!hasDateToken && isDateText(s)) {
                            hasDateToken = true;
                        } else if (!hasGameIdToken && isGameIdText(s)) {
                            hasGameIdToken = true;
                        }
                        if (hasGameIdToken && hasDateToken) {
                            break;
                        }
                    }
                }
            }
            for (Class<?> cls = target.getClass(); cls != null; cls = cls.getSuperclass()) {
                for (Field f : cls.getDeclaredFields()) {
                    if (f.getType() != String.class) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object val = f.get(target);
                    if (!(val instanceof String)) {
                        continue;
                    }
                    String s = (String) val;
                    if (s.isEmpty() || isNonContentString(s)) {
                        continue;
                    }
                    boolean hideD = false;
                    if (ConfigManager.isHideDateGameIdEffective()) {
                        hideD = (hasDateToken && isDateText(s))
                                || isGameIdText(s)
                                || (hasGameIdToken && isGameIdSuffixFragment(s));
                    }
                    boolean hideK = ConfigManager.isHideKitScoreboardEffective() && isKitText(s);
                    if (!hideD && !hideK) {
                        continue;
                    }
                    if (teamName != null) {
                        String key = teamName + "::" + f.getName();
                        if (hideD && !savedDateGameIdValues.containsKey(key)) {
                            savedDateGameIdValues.put(key, s);
                        }
                        if (hideK && !savedKitValues.containsKey(key)) {
                            savedKitValues.put(key, s);
                        }
                    }
                    f.set(target, "");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void blankDateGameIdFields(Object target, String teamName) {
        // Custom blanking: blank date/game-id tokens, and also blank game-id suffix fragments when
        // a real game-id token exists somewhere on the same scoreboard team.
        try {
            boolean hasGameIdToken = false;
            boolean hasDateToken = false;
            if (ConfigManager.isHideDateGameIdEffective()) {
                for (Class<?> cls = target.getClass(); cls != null && !(hasGameIdToken && hasDateToken); cls = cls.getSuperclass()) {
                    for (Field f : cls.getDeclaredFields()) {
                        if (f.getType() != String.class) {
                            continue;
                        }
                        f.setAccessible(true);
                        Object val = f.get(target);
                        if (!(val instanceof String)) {
                            continue;
                        }
                        String s = (String) val;
                        if (s == null || s.isEmpty()) {
                            continue;
                        }
                        if (!hasDateToken && isDateText(s)) {
                            hasDateToken = true;
                        } else if (!hasGameIdToken && isGameIdText(s)) {
                            hasGameIdToken = true;
                        }
                        if (hasGameIdToken && hasDateToken) {
                            break;
                        }
                    }
                }
            }

            for (Class<?> cls = target.getClass(); cls != null; cls = cls.getSuperclass()) {
                for (Field f : cls.getDeclaredFields()) {
                    if (f.getType() != String.class) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object val = f.get(target);
                    if (!(val instanceof String)) {
                        continue;
                    }
                    String s = (String) val;
                    if (s == null || s.isEmpty() || isNonContentString(s)) {
                        continue;
                    }

                    boolean hideD = false;
                    if (ConfigManager.isHideDateGameIdEffective()) {
                        hideD = (hasDateToken && isDateText(s))
                                || isGameIdText(s)
                                || (hasGameIdToken && isGameIdSuffixFragment(s));
                    }
                    if (!hideD) {
                        continue;
                    }

                    if (teamName != null) {
                        String key = teamName + "::" + f.getName();
                        if (!savedDateGameIdValues.containsKey(key)) {
                            savedDateGameIdValues.put(key, s);
                        }
                    }
                    f.set(target, "");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void blankKitFields(Object target, String teamName) {
        blankFieldsMatching(target, teamName, ScoreboardDateHider::isKitText, savedKitValues);
    }

    private static void blankFieldsMatching(Object target, String teamName, java.util.function.Predicate<String> match, Map<String, String> sink) {
        try {
            for (Class<?> cls = target.getClass(); cls != null; cls = cls.getSuperclass()) {
                for (Field f : cls.getDeclaredFields()) {
                    if (f.getType() != String.class) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object val = f.get(target);
                    if (!(val instanceof String)) {
                        continue;
                    }
                    String s = (String) val;
                    if (s.isEmpty() || isNonContentString(s) || !match.test(s)) {
                        continue;
                    }
                    if (teamName != null) {
                        String key = teamName + "::" + f.getName();
                        if (!sink.containsKey(key)) {
                            sink.put(key, s);
                        }
                    }
                    f.set(target, "");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void callSetter(ScorePlayerTeam team, String methodName, String value) {
        for (Class<?> cls = team.getClass(); cls != null; cls = cls.getSuperclass()) {
            try {
                Method m = cls.getDeclaredMethod(methodName, String.class);
                m.setAccessible(true);
                m.invoke(team, value);
                return;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable e) {
                return;
            }
        }
    }

    public static boolean isDateOrGameIdText(String text) {
        return isDateText(text) || isGameIdText(text);
    }

    public static boolean isHypixelFooterText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String clean = HypixelScoreboardMapResolver.normalizeSidebarLineForMatch(text);
        if (clean.isEmpty()) {
            return false;
        }
        StringBuilder collapsed = new StringBuilder(clean.length());
        for (int i = 0; i < clean.length(); ) {
            int cp = clean.codePointAt(i);
            if ((cp >= 'a' && cp <= 'z') || (cp >= '0' && cp <= '9')) {
                collapsed.append((char) cp);
            }
            i += Character.charCount(cp);
        }
        return "wwwhypixelnet".contentEquals(collapsed);
    }

    private static boolean isDateText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        // Hypixel often inserts zero-width / bidi formatting chars that can break token regexes.
        // Normalize by stripping invisible format chars before matching.
        String clean = HypixelScoreboardMapResolver.normalizeSidebarLineForMatch(text);
        if (clean.isEmpty()) {
            return false;
        }
        return DATE_PATTERN.matcher(clean).find();
    }

    private static boolean isGameIdText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        // Normalize to keep game-id matching stable across lobby/pregame render paths.
        String clean = HypixelScoreboardMapResolver.normalizeSidebarLineForMatch(text);
        if (clean.isEmpty()) {
            return false;
        }
        for (String tok : clean.split("[^a-zA-Z0-9]+")) {
            if (tok.isEmpty()) {
                continue;
            }
            // Treat only tokens that look like game-id fragments/evidence.
            if (GAME_ID_TOKEN.matcher(tok).matches() || GAME_ID_DIGITS_LETTERS_TOKEN.matcher(tok).matches()) {
                return true;
            }
            // Loose evidence for short tails like "7C"/"9E". Exclude the multiplier "2x" case.
            if (tok.length() >= 2 && (tok.charAt(tok.length() - 1) == 'x' || tok.charAt(tok.length() - 1) == 'X')) {
                // Exclude multiplier-like "2x"/"12x"/etc.
                boolean allDigits = true;
                for (int i = 0; i < tok.length() - 1; i++) {
                    char c = tok.charAt(i);
                    if (c < '0' || c > '9') {
                        allDigits = false;
                        break;
                    }
                }
                if (allDigits) {
                continue;
            }
            }
            if (GAME_ID_DIGITS_LETTERS_TOKEN_LOOSE.matcher(tok).matches()
                    || GAME_ID_LETTER_DIGITS_LETTERS_TOKEN_LOOSE.matcher(tok).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Short letter-only fragments that can be the tail of a game-id split across scoreboard
     * prefix/suffix fields.
     *
     * <p>We only blank these when the same scoreboard team also contains a real game-id token.</p>
     */
    private static boolean isGameIdSuffixFragment(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String clean = HypixelScoreboardMapResolver.normalizeSidebarLineForMatch(text);
        if (clean.isEmpty()) {
            return false;
        }
        if (clean.length() < 2 || clean.length() > 10) {
            return false;
        }
        // Exclude multiplier-like fragments such as "2x" that appear in real sidebar rows.
        // We only exclude the simple digit+`x` case; other digit+letter fragments can be legitimate game-id tails.
        if (clean.length() >= 2 && (clean.charAt(clean.length() - 1) == 'x' || clean.charAt(clean.length() - 1) == 'X')) {
            // Exclude multiplier-like "2x"/"12x"/etc.
            boolean allDigits = true;
            for (int i = 0; i < clean.length() - 1; i++) {
                char c = clean.charAt(i);
                if (c < '0' || c > '9') {
                    allDigits = false;
                    break;
                }
            }
            if (allDigits) {
                return false;
            }
        }

        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                hasLetter = true;
                continue;
            }
            if (c >= '0' && c <= '9') {
                hasDigit = true;
                continue;
            }
            // Any spaces / punctuation / other chars → not a safe game-id tail fragment.
            return false;
        }

        // Allow pure letters (e.g. "ed") and mixed digit+letter fragments (e.g. "9e").
        return hasLetter;
    }

    public static boolean isKitSidebarText(String text) {
        return text != null && !text.isEmpty() && isKitText(text);
    }

    /**
     * True for a full {@code Kit: …} row or a substring draw that still contains the {@code kit:} token (split rows).
     */
    public static boolean isKitSidebarTextOrFragment(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String plain = MinecraftFormatting.stripSectionCodes(text);
        String clean = plain.toLowerCase(Locale.ROOT);
        if (!clean.contains("kit")) return false;
        return KIT_LINE_PATTERN.matcher(plain.trim()).matches()
                || KIT_LINE_FRAGMENT_PATTERN.matcher(clean).find()
                || KIT_LINE_LOOSE_FRAGMENT_PATTERN.matcher(clean).find();
    }

    private static boolean isKitText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String clean = MinecraftFormatting.stripSectionCodes(text).trim();
        return KIT_LINE_PATTERN.matcher(clean).matches();
    }

    private static boolean isNonContentString(String s) {
        if (s == null || s.isEmpty()) {
            return true;
        }
        if (isDateOrGameIdText(s) || isKitText(s)) {
            return false;
        }
        // Game-id suffix fragments (e.g. "ed" tail bits) must not be skipped as "non content".
        // Otherwise they'd never get blanked even when the same team contains the full game-id.
        if (isGameIdSuffixFragment(s)) {
            return false;
        }
        if ("always".equals(s) || "never".equals(s)
                || "hideForOtherTeams".equals(s) || "hideForOwnTeam".equals(s)
                || "pushOtherTeams".equals(s) || "pushOwnTeam".equals(s)) {
            return true;
        }
        if (s.length() <= 16) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                        || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.')) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static Field findField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals(name)) {
                    return f;
                }
            }
        }
        return null;
    }

    /** For {@code /sdebug}: would date/game-id hide apply to this string? */
    public static boolean debugIsDateGameIdText(String text) {
        return text != null && !text.isEmpty() && isDateOrGameIdText(text);
    }

    /** For {@code /sdebug}: would kit hide apply to this string? */
    public static boolean debugIsKitText(String text) {
        return text != null && !text.isEmpty() && isKitText(text);
    }

    /** For {@code /sdebug}: would the Hypixel footer hide apply to this string? */
    public static boolean debugIsHypixelFooterText(String text) {
        return text != null && !text.isEmpty() && isHypixelFooterText(text);
    }

    /** @deprecated use {@link #debugIsDateGameIdText} / {@link #debugIsKitText} */
    @Deprecated
    public static boolean debugIsHideTargetText(String text) {
        return text != null && !text.isEmpty() && (isDateOrGameIdText(text) || isKitText(text));
    }
}
