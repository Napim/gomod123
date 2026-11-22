package me.napim.gomod.features;

import me.napim.gomod.Main;
import me.napim.gomod.gui.HudModuleTextDraw;
import me.napim.gomod.gui.HudEditorBackgroundMask;
import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.event.PacketEvent;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.minecraft.network.play.server.S02PacketChat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Opt-in Blitz SG self-challenges with tiered goals, dodge detection, and HUD progress.
 */
public final class BlitzChallenges {

    public enum ModeRestriction {
        BOTH,
        SOLO,
        TEAMS
    }

    public enum ChallengeId {
        BLOOD_HOUR("blood_hour", "Blood Hour", true, ModeRestriction.BOTH,
                new int[]{10, 20, 30}, TimeUnit.HOURS.toMillis(1),
                "Get %d kills within 1 hour. Timer starts when cages open after you start. "
                        + "Dodging a queue fails this challenge."),
        MASSACRE("massacre", "Massacre", false, ModeRestriction.BOTH,
                new int[]{5, 10, 15}, 0L,
                "Get %d kills in a single game."),
        RAMPAGE("rampage", "Deathmatch Beast", false, ModeRestriction.BOTH,
                new int[]{2, 3, 5}, 0L,
                "Get %d kills during deathmatch in a single game."),
        FIRST_BLOOD("first_blood", "First Blood Striker", true, ModeRestriction.BOTH,
                new int[]{2, 4, 7}, 0L,
                "Get %d kills before you receive your kit."),
        NO_MERCY("no_mercy", "No Mercy", true, ModeRestriction.BOTH,
                new int[]{5, 10, 20}, TimeUnit.MINUTES.toMillis(30),
                "Get %d kills across up to 2 games within 30 minutes. Timer starts when cages open. "
                        + "Dodging a queue fails this challenge."),
        KILL_STREAK("kill_streak", "Kill Streaker", true, ModeRestriction.BOTH,
                new int[]{5, 10, 20, 35}, 0L,
                "Get %d kills without dying."),
        HOT_STREAK("hot_streak", "Win Streaker", true, ModeRestriction.BOTH,
                new int[]{2, 3, 4, 5, 7}, 0L,
                "Win %d Blitz games in a row.");

        private final String key;
        private final String displayName;
        private final boolean dodgeSensitive;
        private final ModeRestriction modeRestriction;
        private final int[] tierTargets;
        private final long timeLimitMs;
        private final String descriptionTemplate;

        ChallengeId(String key, String displayName, boolean dodgeSensitive, ModeRestriction modeRestriction,
                    int[] tierTargets, long timeLimitMs, String descriptionTemplate) {
            this.key = key;
            this.displayName = displayName;
            this.dodgeSensitive = dodgeSensitive;
            this.modeRestriction = modeRestriction;
            this.tierTargets = tierTargets;
            this.timeLimitMs = timeLimitMs;
            this.descriptionTemplate = descriptionTemplate;
        }

        public String getKey() {
            return key;
        }

        public String getDisplayName() {
            return displayName;
        }

        public boolean isDodgeSensitive() {
            return dodgeSensitive;
        }

        public ModeRestriction getModeRestriction() {
            return modeRestriction;
        }

        public int[] getTierTargets() {
            return tierTargets;
        }

        public int getTierCount() {
            return tierTargets.length;
        }

        public int getTargetForTier(int tierIndex) {
            if (tierIndex < 0 || tierIndex >= tierTargets.length) {
                return -1;
            }
            return tierTargets[tierIndex];
        }

        public long getTimeLimitMs() {
            return timeLimitMs;
        }

        public String formatDescription(int tierIndex) {
            return String.format(descriptionTemplate, getTargetForTier(tierIndex));
        }

        /** GUI / list icon for this challenge type. */
        public ItemStack createIconStack() {
            switch (this) {
                case BLOOD_HOUR:
                    return new ItemStack(Items.clock);
                case MASSACRE:
                    return new ItemStack(Items.diamond_sword);
                case RAMPAGE:
                    return new ItemStack(Items.nether_star);
                case FIRST_BLOOD: {
                    ItemStack sword = new ItemStack(Items.wooden_sword);
                    sword.addEnchantment(Enchantment.sharpness, 1);
                    return sword;
                }
                case NO_MERCY:
                    return new ItemStack(Items.iron_axe);
                case HOT_STREAK:
                    return new ItemStack(Items.cake);
                case KILL_STREAK:
                    return new ItemStack(Items.redstone);
                default:
                    return new ItemStack(Items.paper);
            }
        }

        /** Short tier label for GUI rows, e.g. {@code "10 kills"} or {@code "3 wins"}. */
        public String formatTierTargetLabel(int tierIndex) {
            int n = getTargetForTier(tierIndex);
            if (n < 0) {
                return "";
            }
            switch (this) {
                case HOT_STREAK:
                    return n + (n == 1 ? " win" : " wins");
                case RAMPAGE:
                    return n + (n == 1 ? " DM kill" : " DM kills");
                default:
                    return n + (n == 1 ? " kill" : " kills");
            }
        }

        /** {@code RED} for kill goals, {@code YELLOW} for win goals. */
        public String formatTierTargetLabelColored(int tierIndex) {
            EnumChatFormatting color = this == HOT_STREAK ? EnumChatFormatting.YELLOW : EnumChatFormatting.RED;
            return color + formatTierTargetLabel(tierIndex);
        }

        /** Human-readable time limit, or {@code null} if none. */
        public String formatTimeLimitLabel() {
            if (timeLimitMs <= 0L) {
                return null;
            }
            long minutes = timeLimitMs / 60000L;
            if (minutes >= 60L && minutes % 60L == 0L) {
                long hours = minutes / 60L;
                return hours + (hours == 1L ? " hour" : " hours");
            }
            if (minutes > 0L) {
                return minutes + " min";
            }
            return null;
        }

        public static ChallengeId fromKey(String key) {
            if (key == null) {
                return null;
            }
            String k = key.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            for (ChallengeId id : values()) {
                if (id.key.equals(k)) {
                    return id;
                }
            }
            return null;
        }
    }

    private static final Pattern LOCAL_KIT_RECEIVE_PATTERN = Pattern.compile(
            "^You will receive your\\s+(.+?)\\s+kit in\\s+\\d+\\s+second(?:s)?!$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCAL_KIT_GRANTED_PATTERN = Pattern.compile(
            "^You(?:\\s+have)?\\s+received your\\s+(.+?)\\s+kit!?$",
            Pattern.CASE_INSENSITIVE);

    private static boolean hudEnabled = true;
    private static int hudPosX = 4;
    private static int hudPosY = 4;
    private static float hudScale = 1.0f;
    private static boolean hudTextShadow = true;
    private static int hudBackgroundOpacity = 144;
    private static int hudBackgroundColor = 0;
    private static final Set<String> completedTierKeys = new HashSet<>();

    private static ChallengeId activeChallenge;
    private static int activeTierIndex = -1;
    private static boolean waitingForFirstCageOpen;

    private static long timerStartMs;
    private static long timerDeadlineMs;

    private static int sessionTotalKills;
    private static int currentGameKills;
    private static int deathmatchKillsThisGame;
    private static int firstBloodKillsThisGame;
    private static boolean firstBloodWindowOpen;
    private static int gamesCompletedThisSession;
    private static int winStreak;
    private static boolean winBonusThisGame;
    private static int crossGameKillStreak;

    private static boolean lastOnHypixel;
    private static boolean lastCagePhase;
    private static boolean lastGameActive;
    private static boolean lastGameOver;
    private static boolean lastMatchPregame;
    private static String lockedPregameGameId;
    private static boolean inMatchPregame;
    private static long pendingHotStreakCheckMs;
    /** Hypixel game id from scoreboard — detects lobby switches while a challenge is active. */
    private static String trackedBlitzGameId;

    public BlitzChallenges() {
    }

    public static void loadSettings() {
        hudEnabled = ConfigManager.isBlitzChallengesHudEnabled();
        hudPosX = ConfigManager.getBlitzChallengesHudPosX();
        hudPosY = ConfigManager.getBlitzChallengesHudPosY();
        hudScale = ConfigManager.getBlitzChallengesHudScale();
        hudTextShadow = ConfigManager.isBlitzChallengesHudTextShadow();
        hudBackgroundOpacity = ConfigManager.getBlitzChallengesHudBg();
        hudBackgroundColor = ConfigManager.getBlitzChallengesHudBgColor();
        completedTierKeys.clear();
        completedTierKeys.addAll(ConfigManager.getBlitzChallengesCompletedTiers());
        BlitzChallengeBadgeEquip.loadSetting();
    }

    public static boolean isHudEnabled() {
        return hudEnabled;
    }

    public static void setHudEnabled(boolean enabled) {
        hudEnabled = enabled;
        ConfigManager.setBlitzChallengesHudEnabled(enabled);
    }

    public static int getHudPosX() {
        return hudPosX;
    }

    public static int getHudPosY() {
        return hudPosY;
    }

    public static float getHudScale() {
        return hudScale;
    }

    public static void setHudScale(float scale) {
        hudScale = Math.max(0.5f, Math.min(4.0f, scale));
        ConfigManager.setBlitzChallengesHudScale(hudScale);
    }

    public static void setCustomPosition(int x, int y) {
        hudPosX = x;
        hudPosY = y;
        ConfigManager.setBlitzChallengesHudPosX(x);
        ConfigManager.setBlitzChallengesHudPosY(y);
    }

    public static void resetHudLayout() {
        ConfigManager.resetBlitzChallengesHudLayout();
        hudPosX = ConfigManager.getBlitzChallengesHudPosX();
        hudPosY = ConfigManager.getBlitzChallengesHudPosY();
        hudScale = ConfigManager.getBlitzChallengesHudScale();
        hudTextShadow = ConfigManager.isBlitzChallengesHudTextShadow();
        hudBackgroundOpacity = ConfigManager.getBlitzChallengesHudBg();
        hudBackgroundColor = ConfigManager.getBlitzChallengesHudBgColor();
    }

    public static boolean isHudTextShadow() {
        return hudTextShadow;
    }

    public static void setHudTextShadow(boolean value) {
        hudTextShadow = value;
        ConfigManager.setBlitzChallengesHudTextShadow(value);
    }

    public static void toggleHudTextShadow() {
        setHudTextShadow(!hudTextShadow);
    }

    public static int getBackgroundOpacity() {
        return hudBackgroundOpacity;
    }

    public static void setBackgroundOpacity(int value) {
        hudBackgroundOpacity = Math.max(0, Math.min(255, value));
        ConfigManager.setBlitzChallengesHudBg(hudBackgroundOpacity);
    }

    public static int getBackgroundColor() {
        return hudBackgroundColor;
    }

    public static void setBackgroundColor(int rgb) {
        hudBackgroundColor = rgb & 0xFFFFFF;
        ConfigManager.setBlitzChallengesHudBgColor(hudBackgroundColor);
    }

    public static boolean hasActiveChallenge() {
        return activeChallenge != null;
    }

    public static ChallengeId getActiveChallenge() {
        return activeChallenge;
    }

    public static int getActiveTierIndex() {
        return activeTierIndex;
    }

    public static Set<String> getCompletedTierKeys() {
        return Collections.unmodifiableSet(completedTierKeys);
    }

    public static int getHighestCompletedTierIndex(ChallengeId id) {
        int best = -1;
        for (int i = 0; i < id.getTierCount(); i++) {
            if (completedTierKeys.contains(tierKey(id, i))) {
                best = i;
            }
        }
        return best;
    }

    public static int getNextAvailableTierIndex(ChallengeId id) {
        return getHighestCompletedTierIndex(id) + 1;
    }

    public static boolean isTierCompleted(ChallengeId id, int tierIndex) {
        return completedTierKeys.contains(tierKey(id, tierIndex));
    }

    private static String tierKey(ChallengeId id, int tierIndex) {
        return id.getKey() + ":" + tierIndex;
    }

    /** 0-based tier index → {@code I}, {@code II}, {@code III}, … */
    public static String formatTierRoman(int tierIndex) {
        if (tierIndex < 0) {
            return "?";
        }
        return intToRoman(tierIndex + 1);
    }

    private static String intToRoman(int num) {
        if (num <= 0 || num > 3999) {
            return String.valueOf(num);
        }
        String[] thousands = {"", "M", "MM", "MMM"};
        String[] hundreds = {"", "C", "CC", "CCC", "CD", "D", "DC", "DCC", "DCCC", "CM"};
        String[] tens = {"", "X", "XX", "XXX", "XL", "L", "LX", "LXX", "LXXX", "XC"};
        String[] ones = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX"};
        return thousands[num / 1000]
                + hundreds[(num % 1000) / 100]
                + tens[(num % 100) / 10]
                + ones[num % 10];
    }

    public static boolean startChallenge(ChallengeId id, int tierIndex) {
        if (id == null) {
            return false;
        }
        if (tierIndex < 0 || tierIndex >= id.getTierCount()) {
            return false;
        }
        if (activeChallenge != null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "You already have an active challenge! Use /blitzchallenges stop first.");
            return false;
        }
        if (isTierCompleted(id, tierIndex)) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "You already completed that tier.");
            return false;
        }
        int expected = getNextAvailableTierIndex(id);
        if (tierIndex != expected) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                    + "Complete " + formatTierRoman(expected) + " first.");
            return false;
        }

        activeChallenge = id;
        activeTierIndex = tierIndex;
        resetSessionCounters();
        waitingForFirstCageOpen = true;
        timerStartMs = 0L;
        timerDeadlineMs = id.getTimeLimitMs() > 0L ? 0L : 0L;
        lockedPregameGameId = null;
        inMatchPregame = false;

        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Started "
                + EnumChatFormatting.GOLD + id.getDisplayName()
                + EnumChatFormatting.GREEN + " — " + formatTierRoman(tierIndex)
                + EnumChatFormatting.GRAY + " (" + id.formatTierTargetLabel(tierIndex) + ")");
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + id.formatDescription(tierIndex));
        if (id.isDodgeSensitive()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "[!] Dodging a queue (requeuing another pregame lobby before the game starts) fails this challenge.");
        }
        return true;
    }

    public static void stopChallenge() {
        if (activeChallenge == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "No active challenge.");
            return;
        }
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Cancelled "
                + activeChallenge.getDisplayName() + " " + formatTierRoman(activeTierIndex) + ".");
        clearActiveChallenge();
    }

    private static void resetSessionCounters() {
        sessionTotalKills = 0;
        resetPerGameCounters();
        gamesCompletedThisSession = 0;
        winStreak = 0;
        crossGameKillStreak = 0;
    }

    /** Resets counters that belong to the current Blitz match only. */
    private static void resetPerGameCounters() {
        currentGameKills = 0;
        deathmatchKillsThisGame = 0;
        firstBloodKillsThisGame = 0;
        firstBloodWindowOpen = false;
        winBonusThisGame = false;
    }

    private static void clearActiveChallenge() {
        activeChallenge = null;
        activeTierIndex = -1;
        waitingForFirstCageOpen = false;
        timerStartMs = 0L;
        timerDeadlineMs = 0L;
        lockedPregameGameId = null;
        inMatchPregame = false;
        pendingHotStreakCheckMs = 0L;
        trackedBlitzGameId = null;
        resetSessionCounters();
    }

    @SubscribeEvent
    public void onChat(PacketEvent.Receive event) {
        try {
            if (!(event.getPacket() instanceof S02PacketChat)) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || !GameContext.isOnHypixel(mc)) {
                return;
            }
            S02PacketChat packet = (S02PacketChat) event.getPacket();
            String unformatted = packet.getChatComponent().getUnformattedText();
            if (unformatted == null) {
                return;
            }
            String message = unformatted.replaceAll("\\s+", " ").trim();

            boolean winBonus = message.contains("(Win Bonus)") && message.startsWith("+");
            if (winBonus) {
                GoparkyBlitzReactions.onLocalWin(mc);
                if (activeChallenge != null) {
                    onWinBonus();
                }
            }

            if (activeChallenge == null) {
                return;
            }

            if (isLocalKitReceiveMessage(message)) {
                onLocalKitReceived();
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isLocalKitReceiveMessage(String message) {
        return LOCAL_KIT_RECEIVE_PATTERN.matcher(message).matches()
                || LOCAL_KIT_GRANTED_PATTERN.matcher(message).matches();
    }

    public static void clientTick() {
        if (activeChallenge == null) {
            lastOnHypixel = false;
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        boolean onHypixel = GameContext.isOnHypixel(mc);
        if (lastOnHypixel && !onHypixel) {
            failChallenge("You left Hypixel.");
            return;
        }
        lastOnHypixel = onHypixel;
        if (!onHypixel) {
            return;
        }

        if (activeChallenge.getTimeLimitMs() > 0L && timerStartMs > 0L && timerDeadlineMs > 0L) {
            if (System.currentTimeMillis() >= timerDeadlineMs) {
                failChallenge("Time expired.");
                return;
            }
        }

        if (pendingHotStreakCheckMs > 0L && System.currentTimeMillis() >= pendingHotStreakCheckMs) {
            pendingHotStreakCheckMs = 0L;
            if (activeChallenge == ChallengeId.HOT_STREAK && !winBonusThisGame) {
                failChallenge("You did not win that game.");
                return;
            }
        }

        boolean cage = GameContext.isBlitzCagePhaseSidebar(mc);
        boolean active = GameContext.isBlitzGameActive(mc);
        boolean gameOver = GameContext.isBlitzGameOverSidebar(mc);
        boolean matchPregame = GameContext.isBlitzMatchMapPregameSidebar(mc);

        if (lastCagePhase && !cage && (active || !matchPregame)) {
            onCagesOpened(mc);
        }

        if (lastGameActive && !active && !cage && (gameOver || matchPregame)) {
            onGameEnded(mc);
        }

        if (activeChallenge.isDodgeSensitive()) {
            handleDodgeDetection(mc, matchPregame, cage, active);
        }

        handleBlitzGameIdTracking(mc, matchPregame, cage, active);

        lastCagePhase = cage;
        lastGameActive = active;
        lastGameOver = gameOver;
        lastMatchPregame = matchPregame;
    }

    private static void handleBlitzGameIdTracking(Minecraft mc, boolean matchPregame, boolean cage, boolean active) {
        String gameId = MapQueuer.getCurrentGameId();
        if (gameId != null) {
            if (trackedBlitzGameId != null && !trackedBlitzGameId.equals(gameId)) {
                onBlitzGameIdChanged(trackedBlitzGameId, gameId);
            }
            trackedBlitzGameId = gameId;
        } else if (!matchPregame && !cage && !active) {
            trackedBlitzGameId = null;
        }
    }

    /** Player joined a different Blitz lobby while a challenge was in progress. */
    private static void onBlitzGameIdChanged(String oldId, String newId) {
        if (activeChallenge == null) {
            return;
        }
        switch (activeChallenge) {
            case MASSACRE:
                if (currentGameKills > 0 && currentGameKills < getActiveTarget()) {
                    failChallenge("Switched games — got " + currentGameKills + "/"
                            + getActiveTarget() + " kills (one game only).");
                } else {
                    resetPerGameCounters();
                }
                break;
            case RAMPAGE:
                if (deathmatchKillsThisGame > 0 && deathmatchKillsThisGame < getActiveTarget()) {
                    failChallenge("Switched games — got " + deathmatchKillsThisGame + "/"
                            + getActiveTarget() + " DM kills (one game only).");
                } else {
                    resetPerGameCounters();
                }
                break;
            default:
                resetPerGameCounters();
                break;
        }
    }

    private static void handleDodgeDetection(Minecraft mc, boolean matchPregame, boolean cage, boolean active) {
        if (matchPregame && !cage && !active && !GameContext.isBlitzGameOverSidebar(mc)) {
            String gameId = MapQueuer.getCurrentGameId();
            if (!inMatchPregame) {
                inMatchPregame = true;
                lockedPregameGameId = gameId;
            } else if (lockedPregameGameId != null && gameId != null
                    && !lockedPregameGameId.equals(gameId)) {
                failChallenge("You dodged a queue.");
            }
        } else if (active || cage) {
            inMatchPregame = false;
            lockedPregameGameId = null;
        }
    }

    private static void onCagesOpened(Minecraft mc) {
        if (activeChallenge == null) {
            return;
        }
        if (!isModeAllowed(mc)) {
            return;
        }

        resetPerGameCounters();

        if (activeChallenge == ChallengeId.FIRST_BLOOD) {
            firstBloodWindowOpen = true;
        }

        if (waitingForFirstCageOpen || timerStartMs == 0L) {
            if (activeChallenge.getTimeLimitMs() > 0L) {
                timerStartMs = System.currentTimeMillis();
                timerDeadlineMs = timerStartMs + activeChallenge.getTimeLimitMs();
            } else {
                timerStartMs = System.currentTimeMillis();
            }
            waitingForFirstCageOpen = false;
        }
    }

    private static void onGameEnded(Minecraft mc) {
        if (activeChallenge == null) {
            return;
        }

        gamesCompletedThisSession++;

        switch (activeChallenge) {
            case MASSACRE:
                if (currentGameKills >= getActiveTarget()) {
                    completeChallenge();
                } else {
                    failChallenge("Got " + currentGameKills + "/" + getActiveTarget() + " kills that game.");
                }
                break;
            case RAMPAGE:
                if (deathmatchKillsThisGame >= getActiveTarget()) {
                    completeChallenge();
                } else {
                    failChallenge("Got " + deathmatchKillsThisGame + "/"
                            + getActiveTarget() + " deathmatch kills that game.");
                }
                break;
            case NO_MERCY:
                if (sessionTotalKills >= getActiveTarget()) {
                    completeChallenge();
                } else if (gamesCompletedThisSession >= 2) {
                    failChallenge("Only 2 games allowed — needed " + getActiveTarget() + " kills.");
                } else {
                    resetPerGameCounters();
                }
                break;
            case HOT_STREAK:
                pendingHotStreakCheckMs = System.currentTimeMillis() + 5000L;
                resetPerGameCounters();
                break;
            default:
                resetPerGameCounters();
                break;
        }
    }

    private static void onLocalKitReceived() {
        if (activeChallenge != ChallengeId.FIRST_BLOOD || !firstBloodWindowOpen) {
            return;
        }
        firstBloodWindowOpen = false;
        if (firstBloodKillsThisGame >= getActiveTarget()) {
            completeChallenge();
        }
    }

    private static void onWinBonus() {
        winBonusThisGame = true;
        pendingHotStreakCheckMs = 0L;
        if (activeChallenge != ChallengeId.HOT_STREAK) {
            return;
        }
        winStreak++;
        if (winStreak >= getActiveTarget()) {
            completeChallenge();
        } else {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA + "Win Streaker: "
                    + winStreak + "/" + getActiveTarget() + " wins.");
        }
    }

    public static void onLocalKill(Minecraft mc) {
        if (activeChallenge == null || mc == null) {
            return;
        }
        if (!GameContext.isBlitzGameActive(mc) && !GameContext.isBlitzDeathmatchPhase(mc)) {
            return;
        }
        if (!isModeAllowed(mc)) {
            return;
        }

        currentGameKills++;
        sessionTotalKills++;
        crossGameKillStreak++;

        if (GameContext.isBlitzDeathmatchPhase(mc)) {
            deathmatchKillsThisGame++;
        }

        if (firstBloodWindowOpen) {
            firstBloodKillsThisGame++;
        }

        switch (activeChallenge) {
            case BLOOD_HOUR:
            case NO_MERCY:
                checkNumericProgress(sessionTotalKills);
                break;
            case MASSACRE:
                checkNumericProgress(currentGameKills);
                break;
            case RAMPAGE:
                if (GameContext.isBlitzDeathmatchPhase(mc)) {
                    checkNumericProgress(deathmatchKillsThisGame);
                }
                break;
            case FIRST_BLOOD:
                if (firstBloodWindowOpen) {
                    checkNumericProgress(firstBloodKillsThisGame);
                }
                break;
            case KILL_STREAK:
                checkNumericProgress(crossGameKillStreak);
                break;
            default:
                break;
        }
    }

    public static void onLocalDeath() {
        if (activeChallenge == null) {
            return;
        }
        if (activeChallenge == ChallengeId.KILL_STREAK) {
            failChallenge("You died.");
        }
    }

    private static void checkNumericProgress(int current) {
        int target = getActiveTarget();
        if (current >= target) {
            completeChallenge();
        }
    }

    private static int getActiveTarget() {
        if (activeChallenge == null || activeTierIndex < 0) {
            return 0;
        }
        return activeChallenge.getTargetForTier(activeTierIndex);
    }

    private static boolean isModeAllowed(Minecraft mc) {
        ModeRestriction restriction = activeChallenge.getModeRestriction();
        if (restriction == ModeRestriction.BOTH) {
            return true;
        }
        boolean teams = BlitzTeamsDetector.isBlitzTeamsGame(mc)
                || isTeamModeFromScoreboard(mc);
        if (restriction == ModeRestriction.TEAMS) {
            return teams;
        }
        return !teams;
    }

    private static boolean isTeamModeFromScoreboard(Minecraft mc) {
        try {
            if (mc.theWorld == null || mc.theWorld.getScoreboard() == null) {
                return false;
            }
            net.minecraft.scoreboard.ScoreObjective objective =
                    mc.theWorld.getScoreboard().getObjectiveInDisplaySlot(1);
            if (objective == null) {
                return false;
            }
            String name = objective.getDisplayName();
            if (name == null) {
                return false;
            }
            String clean = name.replaceAll("\u00A7.", "").toLowerCase(Locale.ROOT);
            return clean.contains("team");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void completeChallenge() {
        if (activeChallenge == null) {
            return;
        }
        String key = tierKey(activeChallenge, activeTierIndex);
        completedTierKeys.add(key);
        ConfigManager.addBlitzChallengeCompletedTier(key);

        boolean challengeFullyComplete = activeTierIndex >= activeChallenge.getTierCount() - 1;
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Challenge complete! "
                + EnumChatFormatting.GOLD + activeChallenge.getDisplayName()
                + EnumChatFormatting.GREEN + " " + formatTierRoman(activeTierIndex));
        if (challengeFullyComplete) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GOLD + "Badge unlocked: "
                    + EnumChatFormatting.YELLOW + activeChallenge.getDisplayName()
                    + EnumChatFormatting.GRAY + " — open Blitz Challenges to equip it.");
        }
        try {
            Minecraft.getMinecraft().thePlayer.playSound("random.levelup", 1.0f, 1.2f);
        } catch (Throwable ignored) {
        }
        clearActiveChallenge();
    }

    private static void failChallenge(String reason) {
        if (activeChallenge == null) {
            return;
        }
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Challenge failed: "
                + activeChallenge.getDisplayName() + " " + formatTierRoman(activeTierIndex)
                + EnumChatFormatting.GRAY + " — " + reason);
        try {
            Minecraft.getMinecraft().thePlayer.playSound("random.break", 1.0f, 0.8f);
        } catch (Throwable ignored) {
        }
        clearActiveChallenge();
    }

    public static void renderHud() {
        renderHud(false);
    }

    /** @param editorPreview when true, draws sample lines in HUD Editor / module settings even without an active challenge */
    public static void renderHud(boolean editorPreview) {
        if (!hudEnabled && !editorPreview) {
            return;
        }
        if (!editorPreview && (activeChallenge == null || shouldHideInGameHud())) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.fontRendererObj == null) {
            return;
        }
        List<String> lines = editorPreview && activeChallenge == null
                ? buildPreviewHudLines()
                : buildHudLines();
        if (lines.isEmpty()) {
            return;
        }
        drawHudPanel(mc, lines);
    }

    public static void drawPreviewPanel(Minecraft mc) {
        if (mc == null || mc.fontRendererObj == null) {
            return;
        }
        drawHudPanel(mc, buildPreviewHudLines());
    }

    public static void getHudBounds(Minecraft mc, boolean preview, int[] outBounds) {
        if (outBounds == null || outBounds.length < 4 || mc == null || mc.fontRendererObj == null) {
            return;
        }
        List<String> lines = preview || activeChallenge != null
                ? (activeChallenge != null ? buildHudLines() : buildPreviewHudLines())
                : buildPreviewHudLines();
        if (lines.isEmpty()) {
            lines = buildPreviewHudLines();
        }
        int[] size = measurePanelSize(mc, lines);
        pivotRectToScreen(hudPosX, hudPosY, hudScale, hudPosX, hudPosY,
                hudPosX + size[0], hudPosY + size[1], outBounds);
    }

    private static boolean shouldHideInGameHud() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.currentScreen != null) {
                String screenName = mc.currentScreen.getClass().getName();
                if ("me.napim.gomod.gui.GomodHudEditorGUI".equals(screenName)
                        || "me.napim.gomod.gui.BlitzChallengesHUDGUI".equals(screenName)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static List<String> buildPreviewHudLines() {
        List<String> lines = new ArrayList<>();
        lines.add("Blood Hour I");
        lines.add("Kills: 7/10");
        lines.add("42:15 remaining");
        return lines;
    }

    /** Unscaled panel width/height for the given lines. */
    public static int[] measurePanelSize(Minecraft mc, List<String> lines) {
        if (mc == null || mc.fontRendererObj == null || lines == null || lines.isEmpty()) {
            return new int[] { 80, 20 };
        }
        FontRenderer fr = mc.fontRendererObj;
        int pad = 3;
        int lineGap = 1;
        int maxWidth = 0;
        for (String line : lines) {
            maxWidth = Math.max(maxWidth, fr.getStringWidth(line));
        }
        int boxW = maxWidth + pad * 2;
        int boxH = lines.size() * (fr.FONT_HEIGHT + lineGap) + pad * 2;
        return new int[] { boxW, boxH };
    }

    private static void drawHudPanel(Minecraft mc, List<String> lines) {
        FontRenderer fr = mc.fontRendererObj;
        int pad = 3;
        int lineGap = 1;
        int[] size = measurePanelSize(mc, lines);
        int boxW = size[0];
        int boxH = size[1];

        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.translate(hudPosX, hudPosY, 0.0F);
        GlStateManager.scale(hudScale, hudScale, 1.0F);
        GlStateManager.translate(-hudPosX, -hudPosY, 0.0F);
        HudEditorBackgroundMask.pushTransform(hudPosX, hudPosY, hudScale);

        if (hudBackgroundOpacity > 0) {
            HudEditorBackgroundMask.drawRect(hudPosX, hudPosY, hudPosX + boxW, hudPosY + boxH,
                    (hudBackgroundOpacity << 24) | (hudBackgroundColor & 0xFFFFFF));
        }

        int ty = hudPosY + pad;
        for (int i = 0; i < lines.size(); i++) {
            String plain = lines.get(i);
            int color = 0xFFFFFF;
            if (i == 0) {
                color = 0xFFAA00;
            } else if (plain.contains("remaining")) {
                color = 0x55FFFF;
            }
            HudModuleTextDraw.draw(fr, plain, hudPosX + pad, ty, color, hudTextShadow);
            ty += fr.FONT_HEIGHT + lineGap;
        }

        GlStateManager.disableBlend();
        HudEditorBackgroundMask.popTransform();
        GlStateManager.popMatrix();
    }

    private static void pivotRectToScreen(int pivotX, int pivotY, float scale,
                                           int uLeft, int uTop, int uRight, int uBottom, int[] out) {
        int sxL = (int) Math.round(pivotX + (uLeft - (double) pivotX) * (double) scale);
        int sxR = (int) Math.round(pivotX + (uRight - (double) pivotX) * (double) scale);
        int syT = (int) Math.round(pivotY + (uTop - (double) pivotY) * (double) scale);
        int syB = (int) Math.round(pivotY + (uBottom - (double) pivotY) * (double) scale);
        out[0] = Math.min(sxL, sxR);
        out[1] = Math.min(syT, syB);
        out[2] = Math.max(1, Math.abs(sxR - sxL));
        out[3] = Math.max(1, Math.abs(syB - syT));
    }

    public static List<String> buildHudLines() {
        List<String> lines = new ArrayList<>();
        if (activeChallenge == null) {
            return lines;
        }

        int target = getActiveTarget();
        lines.add(activeChallenge.getDisplayName() + " " + formatTierRoman(activeTierIndex));

        switch (activeChallenge) {
            case BLOOD_HOUR:
            case NO_MERCY:
                lines.add("Kills: " + sessionTotalKills + "/" + target);
                if (activeChallenge == ChallengeId.NO_MERCY) {
                    lines.add("Games: " + gamesCompletedThisSession + "/2");
                }
                break;
            case MASSACRE:
                if (waitingForFirstCageOpen) {
                    lines.add("Kills this game: 0/" + target);
                } else {
                    lines.add("Kills this game: " + currentGameKills + "/" + target);
                }
                break;
            case RAMPAGE:
                if (waitingForFirstCageOpen) {
                    lines.add("DM kills: 0/" + target);
                } else {
                    lines.add("DM kills: " + deathmatchKillsThisGame + "/" + target);
                }
                break;
            case FIRST_BLOOD:
                if (firstBloodWindowOpen) {
                    lines.add("Pre-kit kills: " + firstBloodKillsThisGame + "/" + target);
                } else if (waitingForFirstCageOpen) {
                    lines.add("Waiting for cages...");
                } else {
                    lines.add("Pre-kit kills: " + firstBloodKillsThisGame + "/" + target);
                }
                break;
            case HOT_STREAK:
                lines.add("Win streak: " + winStreak + "/" + target);
                break;
            case KILL_STREAK:
                lines.add("Kills (no death): " + crossGameKillStreak + "/" + target);
                break;
            default:
                break;
        }

        if (waitingForFirstCageOpen) {
            lines.add("Waiting for cages to open...");
        } else if (activeChallenge.getTimeLimitMs() > 0L && timerDeadlineMs > 0L) {
            long remaining = Math.max(0L, timerDeadlineMs - System.currentTimeMillis());
            lines.add(formatRemaining(remaining) + " remaining");
        }

        return lines;
    }

    private static String formatRemaining(long ms) {
        long totalSec = ms / 1000L;
        long hours = totalSec / 3600L;
        long minutes = (totalSec % 3600L) / 60L;
        long seconds = totalSec % 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    public static List<String> buildListLines() {
        List<String> out = new ArrayList<>();
        for (ChallengeId id : ChallengeId.values()) {
            StringBuilder sb = new StringBuilder();
            sb.append(EnumChatFormatting.GOLD).append(id.getDisplayName()).append(EnumChatFormatting.RESET);
            int next = getNextAvailableTierIndex(id);
            if (next >= id.getTierCount()) {
                sb.append(EnumChatFormatting.GREEN).append(" [COMPLETE]");
            } else {
                sb.append(EnumChatFormatting.YELLOW).append(" — next: ")
                        .append(formatTierRoman(next))
                        .append(" (").append(id.formatTierTargetLabel(next)).append(")");
            }
            if (id.isDodgeSensitive()) {
                sb.append(EnumChatFormatting.RED).append(" *");
            }
            out.add(sb.toString());
        }
        return out;
    }

    public static int getTotalBadgeCount() {
        return completedTierKeys.size();
    }

    public static int getCompletedTierCount(ChallengeId id) {
        int n = 0;
        for (int i = 0; i < id.getTierCount(); i++) {
            if (isTierCompleted(id, i)) {
                n++;
            }
        }
        return n;
    }

    public static boolean isFullyCompleted(ChallengeId id) {
        return getNextAvailableTierIndex(id) >= id.getTierCount();
    }

    public static String formatRemainingPublic(long ms) {
        return formatRemaining(ms);
    }

    public static long getTimerDeadlineMs() {
        return timerDeadlineMs;
    }

    public static boolean isWaitingForFirstCageOpen() {
        return waitingForFirstCageOpen;
    }

    public static int getSessionTotalKills() {
        return sessionTotalKills;
    }

    public static int getCurrentGameKills() {
        return currentGameKills;
    }

    public static int getDeathmatchKillsThisGame() {
        return deathmatchKillsThisGame;
    }

    public static int getFirstBloodKillsThisGame() {
        return firstBloodKillsThisGame;
    }

    public static boolean isFirstBloodWindowOpen() {
        return firstBloodWindowOpen;
    }

    public static int getGamesCompletedThisSession() {
        return gamesCompletedThisSession;
    }

    public static int getWinStreak() {
        return winStreak;
    }

    public static int getCrossGameKillStreak() {
        return crossGameKillStreak;
    }

    public static String getBadgeSummary() {
        if (completedTierKeys.isEmpty()) {
            return "No badges yet.";
        }
        StringBuilder sb = new StringBuilder();
        for (ChallengeId id : ChallengeId.values()) {
            int done = 0;
            for (int i = 0; i < id.getTierCount(); i++) {
                if (isTierCompleted(id, i)) {
                    done++;
                }
            }
            if (done > 0) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(id.getDisplayName()).append(" (").append(done).append("/")
                        .append(id.getTierCount()).append(")");
            }
        }
        return sb.toString();
    }

    /** Dev-only: mark challenge tiers complete for badge testing. */
    public static int debugCompleteChallenge(ChallengeId id, boolean allTiers) {
        if (id == null) {
            return 0;
        }
        int added = 0;
        int end = allTiers ? id.getTierCount() : getNextAvailableTierIndex(id) + 1;
        for (int i = 0; i < end && i < id.getTierCount(); i++) {
            String key = tierKey(id, i);
            if (!completedTierKeys.contains(key)) {
                completedTierKeys.add(key);
                ConfigManager.addBlitzChallengeCompletedTier(key);
                added++;
            }
        }
        if (added > 0 && isFullyCompleted(id)) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GOLD + "Badge unlocked: "
                    + EnumChatFormatting.YELLOW + id.getDisplayName());
        }
        return added;
    }

    /** Dev-only: remove completed tiers for a challenge. */
    public static int debugClearChallenge(ChallengeId id, boolean allTiers) {
        if (id == null) {
            return 0;
        }
        int removed = 0;
        int start = allTiers ? 0 : Math.max(0, id.getTierCount() - 1);
        for (int i = start; i < id.getTierCount(); i++) {
            String key = tierKey(id, i);
            if (completedTierKeys.remove(key)) {
                ConfigManager.removeBlitzChallengeCompletedTier(key);
                removed++;
            }
        }
        if (removed > 0) {
            BlitzChallengeBadgeEquip.removeBadge(id.getKey());
        }
        return removed;
    }

    /** Dev-only: wipe all completed challenge tiers. */
    public static void debugClearAllChallenges() {
        completedTierKeys.clear();
        ConfigManager.clearBlitzChallengesCompletedTiers();
        BlitzChallengeBadgeEquip.clearAllBadges();
    }
}
