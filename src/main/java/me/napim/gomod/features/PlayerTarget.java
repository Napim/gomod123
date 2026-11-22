package me.napim.gomod.features;

import me.napim.gomod.gui.HudModuleTextDraw;
import me.napim.gomod.util.HudGlState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.weavemc.loader.api.event.ChatReceivedEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static me.napim.gomod.Main.sendMessage;

/**
 * PlayerTarget renders a rotating triangular cursor that points towards a target player
 * and shows the distance to that player. Use the `/pt` command to control it.
 */
public final class PlayerTarget {
    // Lazy-loaded ResourceLocations to avoid early initialization issues
    private static ResourceLocation CURSOR_TEXTURE = null;
    private static ResourceLocation CURSOR_TEXTURE_RT = null;
    private static ResourceLocation CURSOR_SHIFT_TEXTURE = null;
    private static ResourceLocation CURSOR_SHIFT_TEXTURE_RT = null;
    private static boolean cursorTried = false;
    private static boolean cursorLoaded = false;
    private static boolean cursorUsingFallback = false;
    private static boolean cursorShiftTried = false;
    private static boolean cursorShiftLoaded = false;
    private static boolean cursorShiftUsingFallback = false;
    private static int cursorW = 16;
    private static int cursorH = 16;
    private static int cursorShiftW = 16;
    private static int cursorShiftH = 16;
    // Removed INSTANCE to prevent early Gui instantiation during class loading

    // SEPARATE tracking systems: GUI teammate tracker vs /pt debug command
    private static boolean enabled = false; // GUI teammate tracker enabled state
    private static boolean enabledInitialized = false; // Track if enabled was loaded from config
    private static boolean ptCommandEnabled = false; // SEPARATE: /pt command enabled state (debug - nearest player)
    /** Forced tint when teammate glow mode is Green (chameleon shell + this RGB), same idea as {@code /glowplayer color}. */
    private static final int GLOW_TEAMMATE_COLOR_RGB = 0x47FF6E;

    /** Teammate tracker outline style (mirrors /glowplayer vs /glowplayer2). */
    public enum TeammateGlowMode {
        DISABLED,
        /** Chameleon silhouette + {@link #getGlowTeammateColorRgb()} forced tint (same pipeline as {@code /glowplayer}). */
        GREEN,
        /** Team/skin chameleon only (same pipeline as {@code /glowplayer2}). */
        CHAMELEON;

        public String getDisplayLabel() {
            switch (this) {
                case GREEN: return "Green";
                case CHAMELEON: return "Chameleon";
                default: return "Disabled";
            }
        }

        public static TeammateGlowMode fromConfig(String raw) {
            if (raw == null || raw.isEmpty()) return DISABLED;
            try {
                return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return DISABLED;
            }
        }

        public TeammateGlowMode next() {
            switch (this) {
                case DISABLED: return GREEN;
                case GREEN: return CHAMELEON;
                default: return DISABLED;
            }
        }
    }
    private static boolean userSetViaGUI = false; // Track if user explicitly toggled via GUI (prevents auto-toggle)
    private static String targetPlayerName = null; // For /pt <player> command
    private static String lastKnownTeammate = null; // Track last known teammate for when they die

    /** Per world-tick cache: {@link #findTeamPlayerFromScoreboardImpl} is too heavy to run every render for each item. */
    private static World findTeamCacheWorld;
    private static long findTeamCacheTime = Long.MIN_VALUE;
    private static EntityPlayer findTeamCacheResult;

    /** Per world-tick: teammate entity for item proximity (many {@code EntityItem} renders per frame). */
    private static World teammateLootEntityCacheWorld;
    private static long teammateLootEntityCacheTime = Long.MIN_VALUE;
    private static EntityPlayer teammateLootEntityCache;
    private static final double MAX_TRACKING_DISTANCE = 1000.0D; // Increased from 250 to 1000 blocks
    private static final int CURSOR_SIZE = 16;
    private static long gameStartTime = 0L; // Track when "Survive while eliminating" message appears
    private static final long AUTO_ENABLE_DELAY = 1000L; // 1 second delay before auto-enabling
    
    // Cache for formatted display names (to preserve formatting when player dies)
    private static final java.util.Map<String, String> cachedDisplayNames = new java.util.concurrent.ConcurrentHashMap<>();
    
    // Cache for player skins (to preserve skin when player dies)
    private static final java.util.Map<String, ResourceLocation> cachedPlayerSkins = new java.util.concurrent.ConcurrentHashMap<>();
    
    // Taunt tracking (player name -> has used taunt)
    private static final java.util.Map<String, Boolean> playerTauntsUsed = new java.util.concurrent.ConcurrentHashMap<>();
    
    // Taunt animation tracking (player name -> animation start time)
    private static final java.util.Map<String, Long> tauntAnimationStartTimes = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long TAUNT_ANIMATION_DURATION = 1000L; // 1 second animation

    /** Pre-compiled pattern for kit detection from item display names (e.g. "Knight's Iron Helmet (VI)"). */
    private static final java.util.regex.Pattern KIT_ITEM_PATTERN = java.util.regex.Pattern.compile(
            "^(.+?)'s\\s+(?:Iron|Leather|Gold|Chain(?:mail)?|Diamond)?\\s*(?:Helmet|Chestplate|Leggings|Boots|Sword|Axe)\\s*\\(([IVX\u2B50\u2605\u272B\u2726\u2606\u2B51]+)\\)");
    private static final java.util.regex.Pattern SECTION_CODE_PATTERN = java.util.regex.Pattern.compile("\u00A7.");
    private static final java.util.regex.Pattern NON_ASCII_PATTERN = java.util.regex.Pattern.compile("[^\\x00-\\x7F]");
    /** After our taunt, Hypixel coin lines include Taunt Bonus within this window if the taunt scored a kill. */
    private static final long TAUNT_BONUS_WINDOW_MS = 17_000L;
    private static volatile boolean localPlayerTauntHadBonus = false;
    /** Wall-clock start of our last taunt (chat-detected); used when coin lines don't match map keys (e.g. nick). */
    private static volatile long lastOwnTauntWallclockMs = 0L;

    // Settings
    private static boolean showTauntInfo = false;
    private static boolean showVerticalDistance = false;
    private static boolean showBackground = true;
    private static boolean textShadow = true;
    private static int backgroundOpacity = 120; // Background opacity (0-255)
    private static int backgroundColor = 0;
    private static float scale = 0.76250005f; // UI scale (0.5-4.0)
    private static Position position = Position.CUSTOM;
    private static int customX = 3;
    private static int customY = 83;
    
    public enum Position {
        TOP_LEFT("Top Left"),
        TOP_RIGHT("Top Right"),
        BOTTOM_LEFT("Bottom Left"),
        BOTTOM_RIGHT("Bottom Right"),
        CUSTOM("Custom");
        
        private final String name;
        
        Position(String name) {
            this.name = name;
        }
        
        public String getName() {
            return name;
        }
    }

    // Private constructor to prevent instantiation - this class only uses static methods
    private PlayerTarget() {}
    
    /**
     * Initialize ResourceLocations lazily to avoid breaking command registration
     */
    private static void initResourceLocations() {
        if (CURSOR_TEXTURE == null) {
            CURSOR_TEXTURE = new ResourceLocation("gomod123:textures/cursor.png");
            CURSOR_TEXTURE_RT = new ResourceLocation("gomod123:runtime/cursor");
            CURSOR_SHIFT_TEXTURE = new ResourceLocation("gomod123:textures/cursorshift.png");
            CURSOR_SHIFT_TEXTURE_RT = new ResourceLocation("gomod123:runtime/cursorshift");
        }
    }

    /**
     * Sets enabled state for /pt command ONLY (debug - tracks nearest player)
     * This does NOT affect the GUI teammate tracker at all!
     */
    public static void setEnabled(boolean value) {
        ptCommandEnabled = value;
        // Do NOT touch 'enabled' or ConfigManager - /pt is separate from GUI!
        System.out.println("[PlayerTarget] /pt command: ptCommandEnabled=" + value + " (tracks nearest player)");
        System.out.println("[PlayerTarget] GUI teammate tracker: enabled=" + enabled + " (unchanged)");
    }
    
    /**
     * Sets enabled state from GUI toggle ONLY (teammate tracker)
     * This does NOT affect /pt command at all!
     */
    public static void setEnabledFromGUI(boolean value) {
        enabled = value;
        userSetViaGUI = true; // Mark as user-controlled, prevent auto-toggle
        ConfigManager.setPlayerTargetEnabled(value);
        // Do NOT touch ptCommandEnabled - they are completely separate!
        System.out.println("[PlayerTarget] GUI toggle: enabled=" + value + " (teammate tracker)");
        System.out.println("[PlayerTarget] /pt command: ptCommandEnabled=" + ptCommandEnabled + " (unchanged)");
    }
    
    /**
     * Resets tracking state (called when joining new world/game)
     */
    public static void reset() {
        gameStartTime = 0L;
        // Do NOT reset userSetViaGUI - respect user's manual setting across games
        targetPlayerName = null;
        lastKnownTeammate = null;
        enabled = ConfigManager.isPlayerTargetEnabled(); // Load GUI tracker state from config
        enabledInitialized = true; // Mark as initialized
        ptCommandEnabled = false; // Reset /pt command state (it's debug only)
        cachedDisplayNames.clear();
        cachedPlayerSkins.clear();
        clearTauntStateForNewMatch();
    }

    /** Clears taunt HUD/chat state between Blitz matches (called from {@link KillCounter}). */
    public static void clearTauntStateForNewMatch() {
        playerTauntsUsed.clear();
        tauntAnimationStartTimes.clear();
        localPlayerTauntHadBonus = false;
        lastOwnTauntWallclockMs = 0L;
        try {
            SidebarTauntWastedMutator.forceRestore();
        } catch (Throwable ignored) {
        }
    }

    private static String stripChatColors(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("(?i)[\\u00A7&][0-9a-fk-or]", "");
    }

    private static boolean chatNameIsLocalPlayer(String chatExtractedName, Minecraft mc) {
        if (mc.thePlayer == null || chatExtractedName == null || chatExtractedName.isEmpty()) {
            return false;
        }
        String a = chatExtractedName.trim();
        return a.equalsIgnoreCase(mc.thePlayer.getName())
                || a.equalsIgnoreCase(mc.thePlayer.getGameProfile().getName());
    }

    private static boolean targetNameIsLocalPlayer(String playerName, Minecraft mc) {
        if (mc.thePlayer == null || playerName == null) {
            return false;
        }
        return playerName.equalsIgnoreCase(mc.thePlayer.getName())
                || playerName.equalsIgnoreCase(mc.thePlayer.getGameProfile().getName());
    }

    private static boolean messageHintsTauntBonus(String message) {
        if (message == null) {
            return false;
        }
        return message.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT).contains("taunt bonus");
    }

    private static int indexOfIgnoreCase(String haystack, String needle) {
        if (haystack == null || needle == null) {
            return -1;
        }
        return haystack.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
    }

    /**
     * Full visible text from a chat component (Hypixel coin lines often live only in formatted JSON / siblings).
     */
    public static void processTauntChatFromPacket(IChatComponent component) {
        processTauntChatPlain(flattenChatToPlain(component));
    }

    private static String flattenChatToPlain(IChatComponent root) {
        if (root == null) {
            return "";
        }
        try {
            String formatted = root.getFormattedText();
            if (formatted != null && !formatted.trim().isEmpty()) {
                return stripChatColors(formatted);
            }
        } catch (Throwable ignored) {
        }
        StringBuilder sb = new StringBuilder();
        appendPlainRecursive(root, sb);
        return stripChatColors(sb.toString());
    }

    private static void appendPlainRecursive(IChatComponent c, StringBuilder sb) {
        if (c == null) {
            return;
        }
        try {
            String u = c.getUnformattedText();
            if (u != null) {
                sb.append(u);
            }
            for (IChatComponent s : c.getSiblings()) {
                appendPlainRecursive(s, sb);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Mirror taunt timing to profile / entity name so HUD {@code target.getName()} matches chat nick. */
    private static void mirrorLocalTauntKeys(Minecraft mc, String chatPlayerName, long tauntStartMs) {
        if (mc.thePlayer == null) {
            return;
        }
        String gn = mc.thePlayer.getGameProfile().getName();
        String en = mc.thePlayer.getName();
        for (String alias : new String[] { gn, en }) {
            if (alias != null && !alias.equalsIgnoreCase(chatPlayerName)) {
                tauntAnimationStartTimes.put(alias, tauntStartMs);
                playerTauntsUsed.put(alias, true);
            }
        }
    }

    public static boolean isEnabled() {
        // Load from config on first access if not initialized yet
        if (!enabledInitialized) {
            enabled = ConfigManager.isPlayerTargetEnabled();
            enabledInitialized = true;
        }
        // Return true if EITHER GUI tracker OR /pt command is enabled
        return enabled || ptCommandEnabled;
    }

    public static void setTargetPlayerName(String value) {
        targetPlayerName = value;
    }

    public static String getTargetPlayerName() {
        return targetPlayerName;
    }
    
    // Settings getters and setters
    public static boolean isShowVerticalDistance() {
        return showVerticalDistance;
    }
    
    public static void setShowVerticalDistance(boolean value) {
        showVerticalDistance = value;
        ConfigManager.setPlayerTargetShowVerticalDistance(value);
    }
    
    public static boolean isShowBackground() {
        return showBackground;
    }
    
    public static void setShowBackground(boolean value) {
        showBackground = value;
        ConfigManager.setPlayerTargetShowBackground(value);
    }
    
    public static int getBackgroundOpacity() {
        return backgroundOpacity;
    }
    
    public static void setBackgroundOpacity(int value) {
        backgroundOpacity = Math.max(0, Math.min(255, value));
        ConfigManager.setPlayerTargetBackgroundOpacity(backgroundOpacity);
    }

    public static int getBackgroundColor() {
        return backgroundColor;
    }

    public static void setBackgroundColor(int rgb) {
        backgroundColor = rgb & 0xFFFFFF;
        ConfigManager.setPlayerTargetBackgroundColor(backgroundColor);
    }
    
    public static Position getPosition() {
        return position;
    }
    
    public static void setPosition(Position value) {
        position = value;
        ConfigManager.setPlayerTargetPosition(value.name());
    }
    
    public static int getCustomX() {
        return customX;
    }
    
    public static int getCustomY() {
        return customY;
    }
    
    public static void setCustomPosition(int x, int y) {
        customX = x;
        customY = y;
        position = Position.CUSTOM;
        ConfigManager.setPlayerTargetCustomX(x);
        ConfigManager.setPlayerTargetCustomY(y);
        ConfigManager.setPlayerTargetPosition(Position.CUSTOM.name());
    }
    
    public static void resetToDefaultPosition() {
        customX = 3;
        customY = 83;
        position = Position.CUSTOM;
        ConfigManager.setPlayerTargetCustomX(3);
        ConfigManager.setPlayerTargetCustomY(83);
        ConfigManager.setPlayerTargetPosition(Position.CUSTOM.name());
    }
    
    public static String getPositionName() {
        return position.getName();
    }
    
    public static boolean isShowTauntInfo() {
        return showTauntInfo;
    }
    
    public static void setShowTauntInfo(boolean value) {
        showTauntInfo = value;
        ConfigManager.setPlayerTargetShowTauntInfo(value);
    }

    public static boolean isTextShadow() {
        return textShadow;
    }

    public static void setTextShadow(boolean value) {
        textShadow = value;
        ConfigManager.setPlayerTargetTextShadow(value);
    }

    public static void toggleTextShadow() {
        setTextShadow(!textShadow);
    }

    private static void drawHudText(FontRenderer fr, String text, int x, int y, int color) {
        HudModuleTextDraw.draw(fr, text, x, y, color, textShadow);
    }
    
    public static float getScale() {
        return scale;
    }
    
    public static void setScale(float value) {
        scale = Math.max(0.5f, Math.min(4.0f, value));
        ConfigManager.setPlayerTargetScale(scale);
    }
    
    public static void markTauntUsed(String playerName) {
        // Only start animation if this is a new taunt use
        if (!playerTauntsUsed.getOrDefault(playerName, false)) {
            long t = System.currentTimeMillis();
            tauntAnimationStartTimes.put(playerName, t);
            Minecraft mc = Minecraft.getMinecraft();
            if (chatNameIsLocalPlayer(playerName, mc)) {
                try {
                    SidebarTauntWastedMutator.forceRestore();
                } catch (Throwable ignored) {
                }
                localPlayerTauntHadBonus = false;
                lastOwnTauntWallclockMs = t;
                mirrorLocalTauntKeys(mc, playerName, t);
            }
        }
        playerTauntsUsed.put(playerName, true);
    }
    
    public static boolean hasUsedTaunt(String playerName) {
        return playerTauntsUsed.getOrDefault(playerName, false);
    }

    /**
     * Local player's last taunt did not earn a Taunt Bonus within the configured window (sidebar mutator + HUD).
     */
    public static boolean isLocalTauntWastedState() {
        if (lastOwnTauntWallclockMs <= 0L) {
            return false;
        }
        if (localPlayerTauntHadBonus) {
            return false;
        }
        return (System.currentTimeMillis() - lastOwnTauntWallclockMs) >= TAUNT_BONUS_WINDOW_MS;
    }

    /**
     * Taunt scored a kill (Taunt Bonus seen in chat); sidebar may show {@code USED} in light green until match reset.
     */
    public static boolean isLocalTauntSuccessSidebarState() {
        return lastOwnTauntWallclockMs > 0L && localPlayerTauntHadBonus;
    }

    /** Debug snapshot of local taunt tracking state (used by {@code /sdebug}). */
    public static long debugLastOwnTauntWallclockMs() {
        return lastOwnTauntWallclockMs;
    }

    public static boolean debugLocalTauntHadBonus() {
        return localPlayerTauntHadBonus;
    }

    public static long debugTauntBonusWindowMs() {
        return TAUNT_BONUS_WINDOW_MS;
    }
    
    /**
     * Parses taunt broadcast and Taunt Bonus coin lines from plain chat text.
     * Prefer {@link #processTauntChatFromPacket} on {@link IChatComponent} so formatted JSON is not dropped.
     */
    public static void processTauntChatPlain(String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            String norm = message.replace('\n', ' ').replace('\r', ' ');
            if (norm.replaceAll("\\s+", " ").trim().equals("Survive while eliminating")) {
                clearTauntStateForNewMatch();
            }
            String lower = norm.toLowerCase(Locale.ROOT);
            if (lower.contains("performed the") && lower.contains("taunt!")) {
                int performedIndex = indexOfIgnoreCase(norm, "performed the");
                if (performedIndex > 0) {
                    String beforePerformed = norm.substring(0, performedIndex).trim();
                    String playerName = beforePerformed;
                    if (beforePerformed.startsWith("[")) {
                        int closeBracket = beforePerformed.indexOf("]");
                        if (closeBracket > 0 && closeBracket < beforePerformed.length() - 1) {
                            playerName = beforePerformed.substring(closeBracket + 1).trim();
                        }
                    }
                    if (!playerName.isEmpty()) {
                        markTauntUsed(playerName);
                    }
                }
            }

            if (mc.thePlayer != null && messageHintsTauntBonus(message) && lastOwnTauntWallclockMs > 0L) {
                long elapsed = System.currentTimeMillis() - lastOwnTauntWallclockMs;
                if (elapsed >= 0L && elapsed <= TAUNT_BONUS_WINDOW_MS) {
                    localPlayerTauntHadBonus = true;
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Listens for chat messages to detect taunt usage
     * This must be called from Main.java's chat event handler
     */
    public static void onChatReceived(ChatReceivedEvent event) {
        try {
            processTauntChatFromPacket(event != null ? event.getMessage() : null);
        } catch (Exception e) {
            // Silently fail
        }
    }
    
    private static boolean configLoaded = false;
    
    /**
     * Load settings from ConfigManager
     */
    public static void loadFromConfig() {
        if (configLoaded) return; // Only load once
        configLoaded = true;
        
        enabled = ConfigManager.isPlayerTargetEnabled();
        showVerticalDistance = ConfigManager.isPlayerTargetShowVerticalDistance();
        showBackground = ConfigManager.isPlayerTargetShowBackground();
        backgroundOpacity = ConfigManager.getPlayerTargetBackgroundOpacity();
        backgroundColor = ConfigManager.getPlayerTargetBackgroundColor();
        customX = ConfigManager.getPlayerTargetCustomX();
        customY = ConfigManager.getPlayerTargetCustomY();
        showTauntInfo = ConfigManager.isPlayerTargetShowTauntInfo();
        textShadow = ConfigManager.isPlayerTargetTextShadow();
        scale = ConfigManager.getPlayerTargetScale();
        
        // Load position enum
        try {
            String posStr = ConfigManager.getPlayerTargetPosition();
            position = Position.valueOf(posStr);
        } catch (Exception e) {
            position = Position.TOP_LEFT;
        }
    }

    /**
     * Called from mixin after HUD; draws the pointer and distance if active.
     */
    public static void render() {
        loadFromConfig(); // Lazy-load config on first render
        HudGlState.restoreForOverlayText();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) return;
        
        // Don't render if the settings GUI or HUD editor is open (preview draws separately)
        if (mc.currentScreen instanceof me.napim.gomod.gui.TeammateTrackerGUI
                || mc.currentScreen instanceof me.napim.gomod.gui.GomodHudEditorGUI) {
            return;
        }
        
        // Removed auto-toggle - user has full manual control via settings
        // checkScoreboardAndToggle();
        
        boolean hudOn = isEnabled();
        boolean visualsOn = isGlowTeammateEnabled() || isTeammateTrailEnabled();
        if (!hudOn && !visualsOn) {
            return;
        }

        EntityPlayer target = findTargetForTeammateLock(mc);

        // Lock teammate for glow/trail even when the HUD panel is off.
        if (target != null) {
            applyTeammateLockFromEntity(mc, target);
        }

        if (!hudOn) {
            return;
        }

        // If target not found, try to show last known teammate (they might be dead)
        if (target == null) {
            String nameToShow = targetPlayerName != null ? targetPlayerName : lastKnownTeammate;
            if (nameToShow != null && !nameToShow.isEmpty()) {
                // Only show panel if player is actually dead, not just not loaded
                if (KillCounter.isPlayerDead(nameToShow)) {
                    drawTargetNotFoundPanel(mc, nameToShow);
                }
            }
            return;
        }

        double distance = mc.thePlayer.getDistanceToEntity(target);
        // Only apply distance limit for auto-tracking, not manual targets
        if (distance > MAX_TRACKING_DISTANCE && targetPlayerName == null) return;

        // Compute angle difference between player yaw and direction to target (in degrees)
        double dx = target.posX - mc.thePlayer.posX;
        double dz = target.posZ - mc.thePlayer.posZ;
        double angleToTargetDeg = Math.toDegrees(Math.atan2(dz, dx)) - 90.0D;
        float playerYaw = mc.thePlayer.rotationYaw;
        float angle = (float)(angleToTargetDeg - playerYaw);

        ScaledResolution sr = new ScaledResolution(mc);
        int cx = sr.getScaledWidth() / 2;
        int cy = sr.getScaledHeight() / 2;
        
        // Get mouse position for tooltip detection (scaled to GUI coordinates)
        int mouseX = org.lwjgl.input.Mouse.getX() * sr.getScaledWidth() / mc.displayWidth;
        int mouseY = sr.getScaledHeight() - org.lwjgl.input.Mouse.getY() * sr.getScaledHeight() / mc.displayHeight - 1;

        // Ensure texture is available (resource or runtime fallback)
        ensureCursorTextureLoaded();

        // Render compact info panel at top-left: head, name, HP, and rotating cursor with distance
        drawTopLeftPanel(mc, target, distance, angle, mouseX, mouseY);
    }

    public static TeammateGlowMode getTeammateGlowMode() {
        return TeammateGlowMode.fromConfig(ConfigManager.getPlayerTargetGlowTeammateMode());
    }

    public static void setTeammateGlowMode(TeammateGlowMode mode) {
        if (mode == null) mode = TeammateGlowMode.DISABLED;
        ConfigManager.setPlayerTargetGlowTeammateMode(mode.name());
    }

    /** Cycles Disabled → Green → Chameleon → Disabled (Teammate Tracker button). */
    public static void cycleTeammateGlowMode() {
        setTeammateGlowMode(getTeammateGlowMode().next());
    }

    /** True when teammate outline is Green or Chameleon (not Disabled). */
    public static boolean isGlowTeammateEnabled() {
        return getTeammateGlowMode() != TeammateGlowMode.DISABLED;
    }

    /** Chameleon mode: vanilla team-tinted textured outline (same pipeline as {@code /glowplayer2}). */
    public static boolean isTeammateChameleonOutlineMode() {
        return getTeammateGlowMode() == TeammateGlowMode.CHAMELEON;
    }

    public static boolean isTeammateTrailEnabled() {
        return ConfigManager.isPlayerTargetTeammateTrailEnabled();
    }

    public static void setTeammateTrailEnabled(boolean value) {
        ConfigManager.setPlayerTargetTeammateTrailEnabled(value);
    }

    public static int getGlowTeammateColorRgb() {
        return GLOW_TEAMMATE_COLOR_RGB;
    }

    private static String normalizeTeammateDisplayName(String displayName, String playerName) {
        if (displayName == null || displayName.isEmpty() || playerName == null || playerName.isEmpty()) {
            return displayName;
        }
        String lowerDisplay = displayName.toLowerCase(java.util.Locale.ROOT);
        String lowerName = playerName.toLowerCase(java.util.Locale.ROOT);
        int idx = lowerDisplay.lastIndexOf(lowerName);
        if (idx <= 0) {
            return displayName;
        }
        String beforeName = displayName.substring(0, idx);
        if (beforeName.endsWith(EnumChatFormatting.WHITE.toString())
                || beforeName.endsWith(EnumChatFormatting.RESET.toString())
                || beforeName.endsWith(EnumChatFormatting.GREEN.toString())
                || beforeName.endsWith(EnumChatFormatting.RED.toString())
                || beforeName.endsWith(EnumChatFormatting.YELLOW.toString())
                || beforeName.endsWith(EnumChatFormatting.AQUA.toString())
                || beforeName.endsWith(EnumChatFormatting.GOLD.toString())
                || beforeName.endsWith(EnumChatFormatting.BLUE.toString())
                || beforeName.endsWith(EnumChatFormatting.DARK_AQUA.toString())
                || beforeName.endsWith(EnumChatFormatting.DARK_GREEN.toString())
                || beforeName.endsWith(EnumChatFormatting.LIGHT_PURPLE.toString())) {
            return displayName;
        }
        return beforeName + EnumChatFormatting.WHITE + displayName.substring(idx);
    }

    public static String getCurrentTeammateName() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null || mc.theWorld == null) return null;
            // findTeamPlayerFromScoreboard now handles the locked-teammate fast path
            // internally — if lastKnownTeammate is set and alive, it returns that player.
            EntityPlayer p = findTeamPlayerFromScoreboard(mc);
            if (p != null) return p.getName();
            // Entity not loaded but lastKnownTeammate might still be valid (alive, just
            // out of render distance).  Return the locked name directly so glow logic
            // can still match by name.
            if (lastKnownTeammate != null && !lastKnownTeammate.isEmpty()) {
                if (!me.napim.gomod.features.KillCounter.isPlayerDead(lastKnownTeammate)) {
                    return lastKnownTeammate;
                }
                // Teammate died — clear the lock
                lastKnownTeammate = null;
            }
            return null;
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Resolves the teammate {@link EntityPlayer} at most once per world tick — used by item-label proximity checks
     * when many drops render in one frame.
     */
    public static EntityPlayer getCurrentTeammateEntityForLoot(Minecraft mc) {
        try {
            if (mc == null || mc.theWorld == null) {
                return null;
            }
            World w = mc.theWorld;
            long t = w.getTotalWorldTime();
            if (teammateLootEntityCacheWorld == w && teammateLootEntityCacheTime == t) {
                return teammateLootEntityCache;
            }
            teammateLootEntityCacheWorld = w;
            teammateLootEntityCacheTime = t;
            EntityPlayer fromFind = findTeamPlayerFromScoreboard(mc);
            if (fromFind != null) {
                teammateLootEntityCache = fromFind;
                return teammateLootEntityCache;
            }
            teammateLootEntityCache = null;
            String name = getCurrentTeammateName();
            if (name != null) {
                for (EntityPlayer p : mc.theWorld.playerEntities) {
                    if (p != null && p.getName() != null && name.equalsIgnoreCase(p.getName())) {
                        teammateLootEntityCache = p;
                        break;
                    }
                }
            }
            return teammateLootEntityCache;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean isNameCurrentTeammate(String name) {
        if (name == null || name.isEmpty()) return false;
        String current = getCurrentTeammateName();
        return current != null && current.equalsIgnoreCase(name);
    }

    /**
     * True if this loaded player is the tracker-resolved teammate (outline + trail).
     * Uses the same entity resolution as loot proximity ({@link #getCurrentTeammateEntityForLoot})
     * plus {@link EntityPlayer#getName()} and {@link com.mojang.authlib.GameProfile#getName()} so
     * teammate glow follows the full silhouette like {@code /glowplayer} when tab vs profile names differ.
     */
    public static boolean isEntityCurrentTeammate(EntityPlayer ep) {
        if (ep == null) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return false;
        }
        EntityPlayer resolved = getCurrentTeammateEntityForLoot(mc);
        if (resolved != null && resolved.getEntityId() == ep.getEntityId()) {
            return true;
        }
        if (isNameCurrentTeammate(ep.getName())) {
            return true;
        }
        try {
            com.mojang.authlib.GameProfile gp = ep.getGameProfile();
            if (gp != null && gp.getName() != null && isNameCurrentTeammate(gp.getName())) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Gets detailed debug information about why a player might be detected as a teammate.
     * Used by /glowplayer why <player> command.
     */
    public static String getTeammateDetectionDebug(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return "Invalid player name";
        }
        
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
                return "Minecraft not initialized";
            }
            
            StringBuilder info = new StringBuilder();
            info.append(EnumChatFormatting.YELLOW).append("=== Teammate Detection Debug for: ").append(EnumChatFormatting.WHITE).append(playerName).append(EnumChatFormatting.RESET).append("\n");
            
            TeammateGlowMode glowMode = getTeammateGlowMode();
            info.append("Teammate glow: ").append(EnumChatFormatting.WHITE).append(glowMode.getDisplayLabel()).append(EnumChatFormatting.RESET).append("\n");
            
            // Get current teammate name
            String currentTeammate = getCurrentTeammateName();
            boolean isDetectedAsTeammate = currentTeammate != null && currentTeammate.equalsIgnoreCase(playerName);
            info.append("Current Detected Teammate: ").append(currentTeammate != null ? EnumChatFormatting.WHITE + currentTeammate : EnumChatFormatting.GRAY + "None").append(EnumChatFormatting.RESET).append("\n");
            info.append("Is This Player Detected as Teammate: ").append(isDetectedAsTeammate ? EnumChatFormatting.GREEN + "Yes" : EnumChatFormatting.RED + "No").append(EnumChatFormatting.RESET).append("\n");
            
            // Check if game is active (not in lobby)
            boolean gameActive = isGameActive();
            info.append("Is Game Active (not lobby): ").append(gameActive ? EnumChatFormatting.GREEN + "Yes" : EnumChatFormatting.RED + "No").append(EnumChatFormatting.RESET).append("\n");
            if (!gameActive) {
                info.append(EnumChatFormatting.YELLOW).append("⚠ WARNING: Not in active game - teammate detection disabled in lobby!").append(EnumChatFormatting.RESET).append("\n");
            }
            
            // Check scoreboard information
            net.minecraft.scoreboard.Scoreboard scoreboard = mc.theWorld.getScoreboard();
            if (scoreboard != null) {
                net.minecraft.scoreboard.ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(1);
                if (objective != null) {
                    // Check if it's a team game
                    boolean isTeamGame = false;
                    boolean hasTaunt = false;
                    for (net.minecraft.scoreboard.Score score : scoreboard.getSortedScores(objective)) {
                        net.minecraft.scoreboard.ScorePlayerTeam team = scoreboard.getPlayersTeam(score.getPlayerName());
                        String raw = net.minecraft.scoreboard.ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
                        if (raw != null) {
                            String clean = raw.replaceAll("§[0-9A-FK-ORa-fk-or]", "").toLowerCase();
                            if (clean.contains("team")) {
                                isTeamGame = true;
                            }
                            if (clean.contains("taunt")) {
                                hasTaunt = true;
                            }
                        }
                    }
                    info.append("Is Team Game (from scoreboard): ").append(isTeamGame ? EnumChatFormatting.GREEN + "Yes" : EnumChatFormatting.RED + "No").append(EnumChatFormatting.RESET).append("\n");
                    info.append("Has Taunt (active game): ").append(hasTaunt ? EnumChatFormatting.GREEN + "Yes" : EnumChatFormatting.RED + "No").append(EnumChatFormatting.RESET).append("\n");
                    
                    // Get team information
                    net.minecraft.scoreboard.ScorePlayerTeam myTeam = scoreboard.getPlayersTeam(mc.thePlayer.getName());
                    net.minecraft.scoreboard.ScorePlayerTeam playerTeam = scoreboard.getPlayersTeam(playerName);
                    
                    String myTeamName = myTeam != null ? myTeam.getRegisteredName() : null;
                    String playerTeamName = playerTeam != null ? playerTeam.getRegisteredName() : null;
                    
                    info.append("My Team: ").append(myTeamName != null ? EnumChatFormatting.WHITE + myTeamName : EnumChatFormatting.GRAY + "None").append(EnumChatFormatting.RESET);
                    if (myTeamName != null) {
                        boolean myTeamValid = isValidTeamName(myTeamName);
                        info.append(" (").append(myTeamValid ? EnumChatFormatting.GREEN + "Valid" : EnumChatFormatting.RED + "Invalid").append(EnumChatFormatting.RESET).append(")");
                        if (!myTeamValid) {
                            info.append(EnumChatFormatting.YELLOW).append(" ⚠ Looks like lobby rank team!").append(EnumChatFormatting.RESET);
                        }
                    }
                    info.append("\n");
                    
                    info.append("Player's Team: ").append(playerTeamName != null ? EnumChatFormatting.WHITE + playerTeamName : EnumChatFormatting.GRAY + "None").append(EnumChatFormatting.RESET);
                    if (playerTeamName != null) {
                        boolean playerTeamValid = isValidTeamName(playerTeamName);
                        info.append(" (").append(playerTeamValid ? EnumChatFormatting.GREEN + "Valid" : EnumChatFormatting.RED + "Invalid").append(EnumChatFormatting.RESET).append(")");
                        if (!playerTeamValid) {
                            info.append(EnumChatFormatting.YELLOW).append(" ⚠ Looks like lobby rank team!").append(EnumChatFormatting.RESET);
                        }
                    }
                    info.append("\n");
                    
                    if (myTeam != null && playerTeam != null) {
                        boolean sameTeam = myTeam.isSameTeam(playerTeam);
                        info.append("Same Team: ").append(sameTeam ? EnumChatFormatting.GREEN + "Yes" : EnumChatFormatting.RED + "No").append(EnumChatFormatting.RESET).append("\n");
                        
                        // Show validation warning if invalid teams match
                        if (sameTeam && myTeamName != null && !isValidTeamName(myTeamName)) {
                            info.append(EnumChatFormatting.RED).append("⚠ WARNING: Teams match but have invalid team names (likely lobby rank teams)!").append(EnumChatFormatting.RESET).append("\n");
                        }
                    }
                } else {
                    info.append("Scoreboard Objective: ").append(EnumChatFormatting.GRAY + "None").append(EnumChatFormatting.RESET).append("\n");
                }
            } else {
                info.append("Scoreboard: ").append(EnumChatFormatting.GRAY + "Not available").append(EnumChatFormatting.RESET).append("\n");
            }
            
            // Check lastKnownTeammate
            info.append("Last Known Teammate: ").append(lastKnownTeammate != null ? EnumChatFormatting.WHITE + lastKnownTeammate : EnumChatFormatting.GRAY + "None").append(EnumChatFormatting.RESET).append("\n");
            if (lastKnownTeammate != null && lastKnownTeammate.equalsIgnoreCase(playerName)) {
                info.append(EnumChatFormatting.YELLOW).append("⚠ WARNING: Player matched via lastKnownTeammate fallback (may be from previous game!)").append(EnumChatFormatting.RESET).append("\n");
            }
            
            // Check if player is in world
            boolean playerInWorld = false;
            for (EntityPlayer p : mc.theWorld.playerEntities) {
                if (p.getName().equalsIgnoreCase(playerName)) {
                    playerInWorld = true;
                    double distance = mc.thePlayer.getDistanceToEntity(p);
                    info.append("Player Distance: ").append(EnumChatFormatting.WHITE).append(String.format("%.2f", distance)).append(" blocks").append(EnumChatFormatting.RESET).append("\n");
                    break;
                }
            }
            if (!playerInWorld) {
                info.append("Player in World: ").append(EnumChatFormatting.RED + "No").append(EnumChatFormatting.RESET).append("\n");
            }
            
            return info.toString();
        } catch (Throwable e) {
            return "Error getting debug info: " + e.getMessage();
        }
    }

    private static void ensureCursorTextureLoaded() {
        initResourceLocations(); // Lazy-load ResourceLocations
        if (cursorLoaded) return; // Already loaded successfully
        if (cursorTried) return; // Already tried and failed
        cursorTried = true;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            if (mc != null && mc.getResourceManager() != null) {
                try {
                    mc.getResourceManager().getResource(CURSOR_TEXTURE);
                    cursorLoaded = true;
                    cursorUsingFallback = false;
                    cursorW = 32; // Default dimensions
                    cursorH = 32;
                    System.out.println("[PlayerTarget] Cursor texture loaded from resource pack");
                    return;
                } catch (Exception e) {
                    System.out.println("[PlayerTarget] Could not load cursor from resource pack: " + e.getMessage());
                }
            }
            // Fallback: load from classpath and register runtime texture
            try {
                java.io.InputStream s = PlayerTarget.class.getResourceAsStream("/assets/gomod123/textures/cursor.png");
                if (s != null && mc != null && mc.getTextureManager() != null) {
                    java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(s);
                    if (img != null) {
                        cursorW = img.getWidth();
                        cursorH = img.getHeight();
                        net.minecraft.client.renderer.texture.DynamicTexture dyn = new net.minecraft.client.renderer.texture.DynamicTexture(img);
                        mc.getTextureManager().loadTexture(CURSOR_TEXTURE_RT, dyn);
                        cursorLoaded = true;
                        cursorUsingFallback = true;
                        System.out.println("[PlayerTarget] Cursor texture loaded from fallback (runtime) - " + cursorW + "x" + cursorH);
                        try { s.close(); } catch (Exception ignored) {}
                        return;
                    }
                }
                System.out.println("[PlayerTarget] Failed to load cursor texture from classpath");
            } catch (Throwable e) {
                System.out.println("[PlayerTarget] Error loading cursor texture: " + e.getMessage());
                e.printStackTrace();
            }
        } catch (Throwable e) {
            System.out.println("[PlayerTarget] Unexpected error in ensureCursorTextureLoaded: " + e.getMessage());
            e.printStackTrace();
        }
        cursorLoaded = false;
    }
    
    private static void ensureCursorShiftTextureLoaded() {
        initResourceLocations(); // Lazy-load ResourceLocations
        if (cursorShiftLoaded) return; // Already loaded successfully
        if (cursorShiftTried) return; // Already tried and failed
        cursorShiftTried = true;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            if (mc != null && mc.getResourceManager() != null) {
                try {
                    mc.getResourceManager().getResource(CURSOR_SHIFT_TEXTURE);
                    cursorShiftLoaded = true;
                    cursorShiftUsingFallback = false;
                    cursorShiftW = 32; // Default dimensions
                    cursorShiftH = 32;
                    System.out.println("[PlayerTarget] Cursor shift texture loaded from resource pack");
                    return;
                } catch (Exception e) {
                    System.out.println("[PlayerTarget] Could not load cursor shift from resource pack: " + e.getMessage());
                }
            }
            // Fallback: load from classpath and register runtime texture
            try {
                java.io.InputStream s = PlayerTarget.class.getResourceAsStream("/assets/gomod123/textures/cursorshift.png");
                if (s != null && mc != null && mc.getTextureManager() != null) {
                    java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(s);
                    if (img != null) {
                        cursorShiftW = img.getWidth();
                        cursorShiftH = img.getHeight();
                        net.minecraft.client.renderer.texture.DynamicTexture dyn = new net.minecraft.client.renderer.texture.DynamicTexture(img);
                        mc.getTextureManager().loadTexture(CURSOR_SHIFT_TEXTURE_RT, dyn);
                        cursorShiftLoaded = true;
                        cursorShiftUsingFallback = true;
                        System.out.println("[PlayerTarget] Cursor shift texture loaded from fallback (runtime) - " + cursorShiftW + "x" + cursorShiftH);
                        try { s.close(); } catch (Exception ignored) {}
                        return;
                    }
                }
                System.out.println("[PlayerTarget] Failed to load cursor shift texture from classpath");
            } catch (Throwable e) {
                System.out.println("[PlayerTarget] Error loading cursor shift texture: " + e.getMessage());
                e.printStackTrace();
            }
        } catch (Throwable e) {
            System.out.println("[PlayerTarget] Unexpected error in ensureCursorShiftTextureLoaded: " + e.getMessage());
            e.printStackTrace();
        }
        cursorShiftLoaded = false;
    }
    
    /**
     * Public method to get the cursor texture for GUI previews
     * @param isShift If true, returns shift cursor texture, otherwise normal cursor
     * @return The cursor texture ResourceLocation, or null if not loaded
     */
    public static ResourceLocation getCursorTexture(boolean isShift) {
        if (isShift) {
            ensureCursorShiftTextureLoaded();
            if (!cursorShiftLoaded) return null;
            return cursorShiftUsingFallback ? CURSOR_SHIFT_TEXTURE_RT : CURSOR_SHIFT_TEXTURE;
        } else {
            ensureCursorTextureLoaded();
            if (!cursorLoaded) return null;
            return cursorUsingFallback ? CURSOR_TEXTURE_RT : CURSOR_TEXTURE;
        }
    }
    
    /**
     * Public method to get the cursor texture for GUI previews (normal cursor)
     * @return The cursor texture ResourceLocation, or null if not loaded
     */
    public static ResourceLocation getCursorTexture() {
        return getCursorTexture(false);
    }
    
    /**
     * Get cursor dimensions
     */
    public static int getCursorWidth(boolean isShift) {
        if (isShift) {
            ensureCursorShiftTextureLoaded();
            return cursorShiftW;
        } else {
            ensureCursorTextureLoaded();
            return cursorW;
        }
    }
    
    public static int getCursorWidth() {
        return getCursorWidth(false);
    }
    
    public static int getCursorHeight(boolean isShift) {
        if (isShift) {
            ensureCursorShiftTextureLoaded();
            return cursorShiftH;
        } else {
            ensureCursorTextureLoaded();
            return cursorH;
        }
    }
    
    public static int getCursorHeight() {
        return getCursorHeight(false);
    }
    
    /**
     * Get the current target player (for GUI previews)
     * @return The currently tracked player, or null if none
     */
    public static EntityPlayer getCurrentTarget() {
        if (!enabled) return null;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) return null;
        return findTarget(mc);
    }

    /**
     * Draws a panel when target is not loaded (dead/spectating/far away)
     */
    private static void drawTargetNotFoundPanel(Minecraft mc, String playerName) {
        try {
            HudGlState.restoreForOverlayText();
            ScaledResolution sr = new ScaledResolution(mc);
            int screenWidth = sr.getScaledWidth();
            int screenHeight = sr.getScaledHeight();
            
            // Calculate position based on settings
            int x, y;
            switch (position) {
                case TOP_RIGHT:
                    x = screenWidth - 200;
                    y = 50;
                    break;
                case BOTTOM_LEFT:
                    x = 6;
                    y = screenHeight - 60;
                    break;
                case BOTTOM_RIGHT:
                    x = screenWidth - 200;
                    y = screenHeight - 60;
                    break;
                case CUSTOM:
                    x = customX;
                    y = customY;
                    break;
                default: // TOP_LEFT
                    x = 6;
                    y = 50;
                    break;
            }
            
            // Apply scale transformation
            GlStateManager.pushMatrix();
            GlStateManager.translate(x, y, 0);
            GlStateManager.scale(scale, scale, 1.0f);
            GlStateManager.translate(-x, -y, 0);
            
            int headSize = 20;
            int padding = 4;
            String displayName = playerName;
            ResourceLocation skin = net.minecraft.client.resources.DefaultPlayerSkin.getDefaultSkinLegacy();
            
            // Try to get cached display name first (preserves formatting even when dead)
            if (cachedDisplayNames.containsKey(playerName)) {
                displayName = normalizeTeammateDisplayName(cachedDisplayNames.get(playerName), playerName);
            }
            
            // Try to get cached skin first (preserves skin even when dead)
            if (cachedPlayerSkins.containsKey(playerName)) {
                skin = cachedPlayerSkins.get(playerName);
            }
            
            // Try to get player info from tab for better formatting and skin
            if (mc.getNetHandler() != null) {
                for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                    if (info.getGameProfile().getName().equalsIgnoreCase(playerName)) {
                        displayName = normalizeTeammateDisplayName(
                            net.minecraft.scoreboard.ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(), playerName),
                            playerName);
                        // Update cache with latest formatting
                        cachedDisplayNames.put(playerName, displayName);
                        if (info.getLocationSkin() != null) {
                            skin = info.getLocationSkin();
                            // Update cached skin
                            cachedPlayerSkins.put(playerName, skin);
                        }
                        break;
                    }
                }
            }
            
            // Check if player is actually dead (from KillCounter) or just not loaded
            boolean isDead = KillCounter.isPlayerDead(playerName);
            String statusText = isDead ? 
                EnumChatFormatting.DARK_RED + "DEAD" : 
                EnumChatFormatting.GRAY + "Not loaded";
            int textX = x + headSize + 6;
            
            int nameW = mc.fontRendererObj.getStringWidth(displayName);
            int statusW = mc.fontRendererObj.getStringWidth(statusText);
            int contentW = Math.max(nameW, statusW);
            
            // Background
            int left = x - padding;
            int right = textX + contentW + padding + 2;
            int bottom = y + headSize + padding;
            int top = y - padding;
            
            if (showBackground) {
                Gui.drawRect(left, top, right, bottom, (backgroundOpacity << 24) | (backgroundColor & 0xFFFFFF));
            }
            
            GlStateManager.color(1f, 1f, 1f, 1f);
            
            // Draw player head
            mc.getTextureManager().bindTexture(skin);
            GlStateManager.color(1f, 1f, 1f, 1f);
            // Face layer
            net.minecraft.client.gui.Gui.drawScaledCustomSizeModalRect(x, y, 8, 8, 8, 8, headSize, headSize, 64, 64);
            // Hat layer
            net.minecraft.client.gui.Gui.drawScaledCustomSizeModalRect(x, y, 40, 8, 8, 8, headSize, headSize, 64, 64);
            
            // Draw text
            drawHudText(mc.fontRendererObj, displayName, textX, y + 2, 0xFFFFFF);
            drawHudText(mc.fontRendererObj, statusText, textX, y + 2 + mc.fontRendererObj.FONT_HEIGHT + 1, 0xFFFFFF);
            
            GlStateManager.popMatrix();
            HudGlState.restoreForOverlayText();
        } catch (Throwable ignored) {}
    }
    
    private static void drawTopLeftPanel(Minecraft mc, EntityPlayer target, double distance, float angleDeg, int mouseX, int mouseY) {
        try {
            HudGlState.restoreForOverlayText();
            ScaledResolution sr = new ScaledResolution(mc);
            int screenWidth = sr.getScaledWidth();
            int screenHeight = sr.getScaledHeight();
            
            // Calculate position based on settings
            int x, y;
            switch (position) {
                case TOP_RIGHT:
                    x = screenWidth - 200; // Will be adjusted by content width
                    y = 50;
                    break;
                case BOTTOM_LEFT:
                    x = 6;
                    y = screenHeight - 100; // Will be adjusted by content height
                    break;
                case BOTTOM_RIGHT:
                    x = screenWidth - 200; // Will be adjusted by content width
                    y = screenHeight - 100; // Will be adjusted by content height
                    break;
                case CUSTOM:
                    x = customX;
                    y = customY;
                    break;
                default: // TOP_LEFT
                    x = 6;
                    y = 50;
                    break;
            }
            
            // Apply scale transformation
            GlStateManager.pushMatrix();
            GlStateManager.translate(x, y, 0);
            GlStateManager.scale(scale, scale, 1.0f);
            GlStateManager.translate(-x, -y, 0);
            
            // Transform mouse coordinates to account for scale (needed for hover detection)
            int scaledMouseX = (int)(x + (mouseX - x) / scale);
            int scaledMouseY = (int)(y + (mouseY - y) / scale);
            
            int headSize = 20;
            int padding = 4;

            // Pre-compute text widths to size background to include cursor+distance
            String displayName = getPlayerDisplayName(mc, target);
            float hp = Math.max(0f, target.getHealth());
            float maxHp = Math.max(1f, target.getMaxHealth());
            String hpText = formatHealthText(hp, maxHp);
            int textX = x + headSize + 6;
            int iconSize = 16;
            
            // Check if target is sneaking to use different cursor
            boolean isSneaking = target.isSneaking();
            
            String distOnlyTmp;
            if (showVerticalDistance) {
                double verticalDist = target.posY - mc.thePlayer.posY;
                double absVerticalDist = Math.abs(verticalDist);
                // Only color if vertical distance is 2 blocks or more
                String arrowColor;
                if (absVerticalDist >= 2.0) {
                    arrowColor = verticalDist > 0 ? EnumChatFormatting.GREEN.toString() : EnumChatFormatting.RED.toString();
                } else {
                    arrowColor = EnumChatFormatting.WHITE.toString();
                }
                // Use circle when at same height, otherwise use arrows
                String arrow;
                if (absVerticalDist < 0.5) {
                    arrow = "\u25CF"; // Circle (at same height)
                } else {
                    arrow = verticalDist > 0 ? "\u25B2" : "\u25BC"; // Up or Down
                }
                distOnlyTmp = String.format("%.1fm (%s%s" + EnumChatFormatting.RESET + " %.1f)", distance, arrowColor, arrow, absVerticalDist);
            } else {
                distOnlyTmp = String.format("%.1fm", distance);
            }
            int nameW = mc.fontRendererObj.getStringWidth(displayName);
            int hpW = mc.fontRendererObj.getStringWidth(hpText);
            int distW = mc.fontRendererObj.getStringWidth(distOnlyTmp) + iconSize + 4;
            int contentW = Math.max(nameW, Math.max(hpW, distW));
            int iconLineY = y + headSize + 4; // under player head, not under health

            // Calculate equipment width (5 items horizontally)
            int equipmentWidth = (5 * 18) + (4 * 2); // 5 items of 18px + 4 gaps of 2px
            int equipmentHeight = 18 + 4; // Item size + spacing below cursor
            
            // Add taunt info height if enabled
            if (showTauntInfo) {
                equipmentHeight += mc.fontRendererObj.FONT_HEIGHT + 4; // Font height + spacing
            }
            
            // Expand contentW to include equipment if it's wider
            contentW = Math.max(contentW, equipmentWidth);

            // Compute dynamic background bounds
            int left = x - padding;
            int right = textX + contentW + padding + 2; // small extra pad
            int bottom = Math.max(y + headSize, iconLineY + iconSize) + equipmentHeight + padding;
            int top = y - padding;

            // Background (semi-transparent black) and reset color to white for textures/text
            if (showBackground) {
                net.minecraft.client.gui.Gui.drawRect(left, top, right, bottom,
                        (backgroundOpacity << 24) | (backgroundColor & 0xFFFFFF));
            }
            GlStateManager.color(1f, 1f, 1f, 1f);

            // Draw player head (skin + hat layer)
            ResourceLocation skin = net.minecraft.client.resources.DefaultPlayerSkin.getDefaultSkinLegacy();
            try {
                if (mc.getNetHandler() != null) {
                    NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(target.getUniqueID());
                    if (info != null) skin = info.getLocationSkin();
                }
            } catch (Throwable ignored) {}
            
            // Setup proper rendering state for skin
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.color(1f, 1f, 1f, 1f);
            
            mc.getTextureManager().bindTexture(skin);
            // Face layer (base skin)
            net.minecraft.client.gui.Gui.drawScaledCustomSizeModalRect(x, y, 8, 8, 8, 8, headSize, headSize, 64, 64);
            // Hat layer (outer layer - with alpha support)
            net.minecraft.client.gui.Gui.drawScaledCustomSizeModalRect(x, y, 40, 8, 8, 8, headSize, headSize, 64, 64);
            
            GlStateManager.disableBlend();
            
            // Red overlay when player is hurt (hurtTime counts down from 10)
            if (target.hurtTime > 0) {
                // Calculate opacity based on hurt time (10 = max red, 0 = no red)
                int redAlpha = (int)(target.hurtTime / 10.0f * 100); // 0-100 opacity
                int redColor = (redAlpha << 24) | 0xFF0000; // Red with variable alpha
                Gui.drawRect(x, y, x + headSize, y + headSize, redColor);
            }

            // Text: name (TAB format), HP (or DEAD if health <= 0)
            drawHudText(mc.fontRendererObj, displayName, textX, y + 2, 0xFFFFFF);
            if (hp <= 0) {
                drawHudText(mc.fontRendererObj, EnumChatFormatting.DARK_RED + "DEAD", textX, y + 2 + mc.fontRendererObj.FONT_HEIGHT + 1, 0xFFFFFF);
            } else {
                drawHudText(mc.fontRendererObj, hpText, textX, y + 2 + mc.fontRendererObj.FONT_HEIGHT + 1, 0xFFFFFF);
            }

            // Draw rotating cursor icon directly under player head (same X as head)
            // Use different cursor texture when target is sneaking
            if (isSneaking) {
                ensureCursorShiftTextureLoaded();
            } else {
                ensureCursorTextureLoaded();
            }
            
            int cursorY = y + headSize + 4; // directly under player head
            int cursorX = x + (headSize / 2); // center of the head horizontally
            GlStateManager.pushMatrix();
            GlStateManager.enableBlend();
            // Translate to exact pixel center to avoid rounding jitter while rotating
            GlStateManager.translate(cursorX, cursorY + (iconSize / 2.0f), 0.0f);
            GlStateManager.rotate(angleDeg, 0, 0, 1);
            
            try {
                if (isSneaking && cursorShiftLoaded) {
                    // Use shift cursor
                    mc.getTextureManager().bindTexture(cursorShiftUsingFallback ? CURSOR_SHIFT_TEXTURE_RT : CURSOR_SHIFT_TEXTURE);
                    net.minecraft.client.gui.Gui.drawScaledCustomSizeModalRect(
                        -(iconSize / 2), -(iconSize / 2),
                        0, 0,
                        cursorShiftW, cursorShiftH,
                        iconSize, iconSize,
                        cursorShiftW, cursorShiftH
                    );
                } else if (cursorLoaded) {
                    // Use normal cursor (only if loaded)
                    mc.getTextureManager().bindTexture(cursorUsingFallback ? CURSOR_TEXTURE_RT : CURSOR_TEXTURE);
                    net.minecraft.client.gui.Gui.drawScaledCustomSizeModalRect(
                        -(iconSize / 2), -(iconSize / 2),
                        0, 0,
                        cursorW, cursorH,
                        iconSize, iconSize,
                        cursorW, cursorH
                    );
                }
            } catch (Throwable ignored) {}
            
            GlStateManager.disableBlend();
            GlStateManager.popMatrix();

            // Distance numeric (no label) to the right of the icon
            String distText;
            if (showVerticalDistance) {
                double verticalDist = target.posY - mc.thePlayer.posY;
                double absVerticalDist = Math.abs(verticalDist);
                // Only color if vertical distance is 2 blocks or more
                String arrowColor;
                if (absVerticalDist >= 2.0) {
                    arrowColor = verticalDist > 0 ? EnumChatFormatting.GREEN.toString() : EnumChatFormatting.RED.toString();
                } else {
                    arrowColor = EnumChatFormatting.WHITE.toString();
                }
                // Use circle when at same height, otherwise use arrows
                String arrow;
                if (absVerticalDist < 0.5) {
                    arrow = "\u25CF"; // Circle (at same height)
                } else {
                    arrow = verticalDist > 0 ? "\u25B2" : "\u25BC"; // Up or Down
                }
                distText = String.format("%.1fm (%s%s" + EnumChatFormatting.RESET + " %.1f)", distance, arrowColor, arrow, absVerticalDist);
            } else {
                distText = String.format("%.1fm", distance);
            }
            drawHudText(mc.fontRendererObj, distText, cursorX + iconSize + 4, cursorY + 2, 0xAAAAFF);
            
            // Draw player equipment (hand, helmet, chestplate, leggings, boots)
            // First item centered under cursor, rest to the right
            int equipmentStartX = cursorX - (18 / 2); // Center first item under cursor
            int equipmentY = cursorY + iconSize + 4; // Below the cursor
            
            EquipmentRenderResult equipResult = drawPlayerEquipment(mc, target, equipmentStartX, equipmentY, scaledMouseX, scaledMouseY);
            
            // Draw taunt status below equipment if enabled
            if (showTauntInfo) {
                int tauntY = equipmentY + 18 + 4; // Below equipment (item size + spacing)
                drawTauntStatus(mc, target.getName(), equipmentStartX, tauntY);
            }
            
            GlStateManager.popMatrix();
            
            // Draw tooltips LAST (after popMatrix) to ensure they render in screen space on top of everything
            // Only show tooltips when in a GUI (chat, inventory, etc) to avoid interfering with gameplay
            if (mc.currentScreen != null) {
                // Check if hovering over cursor icon and show coordinates tooltip
                if (scaledMouseX >= cursorX - iconSize / 2 && scaledMouseX <= cursorX + iconSize / 2 &&
                    scaledMouseY >= cursorY && scaledMouseY <= cursorY + iconSize) {
                    // Mouse is hovering over cursor icon - show coordinates tooltip
                    drawCursorCoordinatesTooltip(mc, target, mouseX, mouseY);
                    // Reset color after tooltip rendering
                    GlStateManager.color(1f, 1f, 1f, 1f);
                }
                
                // Show item tooltips when hovering over equipment
                if (equipResult.hoveredItem != null) {
                    // Detect kit and level directly from the item name
                    String itemName;
                    try {
                        itemName = equipResult.hoveredItem.getDisplayName();
                    } catch (Throwable e) {
                        itemName = "ERROR_GETTING_NAME";
                    }
                    
                    String playerKit = detectKitFromItem(itemName);
                    
                    // Check for special items and override kit detection
                    String cleanItemName = SECTION_CODE_PATTERN.matcher(itemName).replaceAll("").trim();
                    
                    if (cleanItemName.equals("Big Boy Pants") && equipResult.hoveredSlotType != null && equipResult.hoveredSlotType.equals("leggings")) {
                        playerKit = "CUSTOM_BIG_BOY_PANTS";
                    } else if (cleanItemName.equals("Cinderella's Slippers") && equipResult.hoveredSlotType != null && equipResult.hoveredSlotType.equals("boots")) {
                        playerKit = "CUSTOM_CINDERELLAS_SLIPPERS";
                    }
                    
                    renderItemTooltip(mc, equipResult.hoveredItem, mouseX, mouseY, playerKit, equipResult.hoveredSlotType);
                }
            }
            HudGlState.restoreForOverlayText();
        } catch (Throwable ignored) {}
    }
    
    /**
     * Calculates the height needed to display all equipment
     */
    private static int calculateEquipmentHeight(EntityPlayer target) {
        // Equipment is now horizontal, so no extra height needed
        return 0;
    }
    
    /**
     * Draws the player's equipment (hand item and armor)
     * @return The hovered ItemStack if any, null otherwise
     */
    /**
     * Container for equipment render result
     */
    private static class EquipmentRenderResult {
        ItemStack hoveredItem;
        String hoveredSlotType;
    }
    
    private static EquipmentRenderResult drawPlayerEquipment(Minecraft mc, EntityPlayer target, int startX, int y, int mouseX, int mouseY) {
        EquipmentRenderResult result = new EquipmentRenderResult();
        int itemSize = 18;
        int spacing = 2;
        int currentX = startX;
        
        // Get all equipment
        ItemStack[] equipment = {
            target.getCurrentEquippedItem(),
            target.getEquipmentInSlot(4), // Helmet
            target.getEquipmentInSlot(3), // Chestplate
            target.getEquipmentInSlot(2), // Leggings
            target.getEquipmentInSlot(1)  // Boots
        };
        
        // Track which item is being hovered and its slot type
        int hoveredX = 0;
        
        // Render each slot (even if empty) - HORIZONTALLY
        int[] slotTypes = {0, 4, 3, 2, 1}; // Hand (0), Helmet (4), Chestplate (3), Leggings (2), Boots (1)
        String[] slotNames = {"hand", "helmet", "chestplate", "leggings", "boots"};
        
        // First pass: Draw all slot backgrounds without lighting
        GlStateManager.disableLighting();
        for (int i = 0; i < equipment.length; i++) {
            int slotX = startX + (i * (itemSize + spacing));
            drawEmptySlot(mc, slotX, y, itemSize);
        }
        
        // Second pass: Render items with lighting
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableDepth();
        GlStateManager.color(1f, 1f, 1f, 1f);
        
        for (int i = 0; i < equipment.length; i++) {
            ItemStack item = equipment[i];
            int slot = slotTypes[i];
            String slotName = slotNames[i];
            
            // Draw item if it exists
            if (item != null) {
                // Set zLevel to ensure proper layering
                mc.getRenderItem().zLevel = 200.0F;
                
                // Center 16x16 item in 18x18 slot (1 pixel offset)
                mc.getRenderItem().renderItemIntoGUI(item, currentX + 1, y + 1);
                // Render durability bar and stack count (same as InventoryHUD)
                mc.getRenderItem().renderItemOverlayIntoGUI(mc.fontRendererObj, item, currentX + 1, y + 1, null);
                
                // Reset zLevel
                mc.getRenderItem().zLevel = 0.0F;
                
                // Check if mouse is hovering over this item
                if (mouseX >= currentX && mouseX <= currentX + itemSize && mouseY >= y && mouseY <= y + itemSize) {
                    result.hoveredItem = item;
                    result.hoveredSlotType = slotName;
                    hoveredX = currentX;
                }
            }
            
            currentX += itemSize + spacing;
        }
        
        // Cleanup rendering state
        GlStateManager.disableDepth();
        GlStateManager.disableRescaleNormal();
        GlStateManager.disableBlend();
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableLighting();
        
        // Reset color to white to prevent affecting other rendering
        GlStateManager.color(1f, 1f, 1f, 1f);
        
        return result;
    }
    
    /**
     * Detects kit name from item display name
     * Example: "Knight's Helmet (VI)" -> "Knight VI"
     */
    private static String detectKitFromItem(String itemName) {
        try {
            // Remove color codes manually - strip §x patterns
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < itemName.length(); i++) {
                char c = itemName.charAt(i);
                // If we find a § (section sign, \u00A7), skip it and the next character
                if (c == '\u00A7') {
                    i++; // Skip the next character too (the color code)
                    continue;
                }
                sb.append(c);
            }
            String cleanName = sb.toString().trim();
            
            java.util.regex.Matcher matcher = KIT_ITEM_PATTERN.matcher(cleanName);
            
            if (matcher.find()) {
                String kitName = matcher.group(1).trim();
                String level = matcher.group(2).trim();
                return kitName + " " + level;
            }
        } catch (Exception e) {
            // Silently fail - kit detection is non-critical
        }
        return "";
    }
    
    /**
     * Renders an item tooltip at the mouse position
     */
    private static void renderItemTooltip(Minecraft mc, ItemStack item, int x, int y, String playerKit, String slotType) {
        try {
            @SuppressWarnings("unchecked")
            List<String> fullTooltip = item.getTooltip(mc.thePlayer, mc.gameSettings.advancedItemTooltips);
            
            if (fullTooltip == null || fullTooltip.isEmpty()) return;

            // Advanced item tooltips (F3+H): full vanilla lines; only Stone Sword name is recolored.
            if (mc.gameSettings.advancedItemTooltips) {
                List<String> tooltip = new ArrayList<String>(fullTooltip);
                if (item.getItem() == net.minecraft.init.Items.stone_sword && !tooltip.isEmpty()) {
                    me.napim.gomod.gui.HudItemTooltipHelper.applyStoneSwordFirstLineColor(
                            tooltip, item, mc.gameSettings.advancedItemTooltips);
                }
                drawPlayerTargetTooltip(mc, tooltip, x, y);
                return;
            }
            
            // Build corrected tooltip
            List<String> tooltip = new ArrayList<>();
            
            // Add item name (first line)
            if (item.getItem() == net.minecraft.init.Items.stone_sword) {
                tooltip.add(me.napim.gomod.gui.HudItemTooltipHelper.formatStoneSwordFirstLine(
                        fullTooltip.isEmpty() ? null : fullTooltip.get(0), item, false));
            } else if (fullTooltip.size() > 0) {
                tooltip.add(fullTooltip.get(0));
            }
            
            // Try to get correct enchantments from kit JSON
            List<String> correctEnchants = null;
            
            // Special handling for specific custom items
            String itemDisplayName = fullTooltip.get(0);
            String cleanItemName = SECTION_CODE_PATTERN.matcher(itemDisplayName).replaceAll("").trim();
            
            // Check for custom kit markers first
            if (playerKit != null && playerKit.equals("CUSTOM_BIG_BOY_PANTS")) {
                correctEnchants = new ArrayList<>();
                correctEnchants.add("Aqua Affinity X");
            } else if (playerKit != null && playerKit.equals("CUSTOM_CINDERELLAS_SLIPPERS")) {
                correctEnchants = new ArrayList<>();
                correctEnchants.add("Unbreaking X");
            } else if (cleanItemName.equals("Big Boy Pants") && slotType != null && slotType.equals("leggings")) {
                correctEnchants = new ArrayList<>();
                correctEnchants.add("Aqua Affinity X");
            } else if (cleanItemName.equals("Cinderella's Slippers") && slotType != null && slotType.equals("boots")) {
                correctEnchants = new ArrayList<>();
                correctEnchants.add("Unbreaking X");
            } else if (playerKit != null && !playerKit.isEmpty() && slotType != null) {
                // Normal kit JSON lookup
                
                // Split playerKit into name and level (e.g., "Phoenix IX" -> "Phoenix" and "IX")
                String kitName = playerKit;
                String kitLevel = playerKit;
                int lastSpace = playerKit.lastIndexOf(' ');
                if (lastSpace > 0) {
                    kitName = playerKit.substring(0, lastSpace).trim();
                    kitLevel = playerKit.substring(lastSpace + 1).trim();
                }
                
                correctEnchants = KitEnchantmentsLoader.getEnchantmentsForSlot(kitName, kitLevel, slotType);
            }
            
            // HARDCODED FIX: Check for specific items by name and force their enchantments
            if (cleanItemName.contains("Big Boy Pants")) {
                tooltip.add("\u00A77Aqua Affinity X");
            } else if (cleanItemName.contains("Cinderella's Slippers") || cleanItemName.contains("Cinderellas Slippers")) {
                tooltip.add("\u00A77Unbreaking X");
            } else if (cleanItemName.contains("One Pound Fish")) {
                tooltip.add("\u00A77Sharpness I");
            } else if (correctEnchants != null && !correctEnchants.isEmpty()) {
                // Add correct enchantments from kit JSON with gray color (§7)
                for (String enchant : correctEnchants) {
                    // Clean any weird Unicode characters and add gray color
                    String cleanEnchant = NON_ASCII_PATTERN.matcher(enchant).replaceAll("").trim();
                    tooltip.add("\u00A77" + cleanEnchant);
                }
            } else {
                // No kit data available, show ??? for enchantments
                boolean hasEnchants = false;
                for (int i = 1; i < fullTooltip.size(); i++) {
                    String line = fullTooltip.get(i);
                    String lowerLine = SECTION_CODE_PATTERN.matcher(line.toLowerCase()).replaceAll("");
                    // Check if line is an enchantment
                    if (lowerLine.contains("protection") || lowerLine.contains("sharpness") ||
                        lowerLine.contains("unbreaking") || lowerLine.contains("respiration")) {
                        hasEnchants = true;
                        break;
                    }
                }
                if (hasEnchants) {
                    tooltip.add("\u00A77???");
                }
            }
            
            // Add non-enchantment lines (durability, lore, etc.)
            for (int i = 1; i < fullTooltip.size(); i++) {
                String line = fullTooltip.get(i);
                String lowerLine = line.toLowerCase().replaceAll("§[0-9a-fk-or]", "");
                // Skip enchantment lines, add everything else
                boolean isEnchant = lowerLine.contains("protection") || lowerLine.contains("sharpness") ||
                    lowerLine.contains("unbreaking") || lowerLine.contains("thorns") ||
                    lowerLine.contains("knockback") || lowerLine.contains("respiration");
                if (!isEnchant) {
                    tooltip.add(line);
                }
            }
            
            if (tooltip.isEmpty()) return;
            drawPlayerTargetTooltip(mc, tooltip, x, y);
        } catch (Throwable ignored) {}
    }

    private static void drawPlayerTargetTooltip(Minecraft mc, List<String> tooltip, int x, int y) {
        try {
            if (tooltip == null || tooltip.isEmpty()) {
                return;
            }

            int tooltipWidth = 0;
            for (String line : tooltip) {
                int lineWidth = mc.fontRendererObj.getStringWidth(line);
                if (lineWidth > tooltipWidth) {
                    tooltipWidth = lineWidth;
                }
            }

            int tooltipHeight = 8;
            if (tooltip.size() > 1) {
                tooltipHeight += 2 + (tooltip.size() - 1) * 10;
            }

            x += 12;

            ScaledResolution sr = new ScaledResolution(mc);
            if (x + tooltipWidth + 10 > sr.getScaledWidth()) {
                x = sr.getScaledWidth() - tooltipWidth - 10;
            }
            if (y + tooltipHeight + 10 > sr.getScaledHeight()) {
                y = sr.getScaledHeight() - tooltipHeight - 10;
            }

            int backgroundColor = 0xF0100010;
            int borderColorStart = 0x505000FF;
            int borderColorEnd = (borderColorStart & 0xFEFEFE) >> 1 | borderColorStart & 0xFF000000;

            GlStateManager.disableRescaleNormal();
            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableLighting();
            GlStateManager.disableDepth();

            int padding = 3;
            int xStart = x;
            int yStart = y - 3;

            drawTooltipGradientRect(xStart - padding, yStart - 4, xStart + tooltipWidth + padding, yStart - 3, backgroundColor, backgroundColor);
            drawTooltipGradientRect(xStart - padding, yStart + tooltipHeight + 3, xStart + tooltipWidth + padding, yStart + tooltipHeight + 4, backgroundColor, backgroundColor);
            drawTooltipGradientRect(xStart - padding, yStart - 3, xStart + tooltipWidth + padding, yStart + tooltipHeight + 3, backgroundColor, backgroundColor);
            drawTooltipGradientRect(xStart - padding - 1, yStart - 3, xStart - padding, yStart + tooltipHeight + 3, backgroundColor, backgroundColor);
            drawTooltipGradientRect(xStart + tooltipWidth + padding, yStart - 3, xStart + tooltipWidth + padding + 1, yStart + tooltipHeight + 3, backgroundColor, backgroundColor);

            drawTooltipGradientRect(xStart - padding, yStart - 3, xStart + tooltipWidth + padding, yStart - 3 + 1, borderColorStart, borderColorStart);
            drawTooltipGradientRect(xStart - padding, yStart + tooltipHeight + 2, xStart + tooltipWidth + padding, yStart + tooltipHeight + 3, borderColorEnd, borderColorEnd);

            for (int i = 0; i < tooltip.size(); i++) {
                String line = tooltip.get(i);
                drawHudText(mc.fontRendererObj, line, xStart, yStart, -1);

                if (i == 0) {
                    yStart += 2;
                }
                yStart += 10;
            }

            GlStateManager.enableLighting();
            GlStateManager.enableDepth();
            RenderHelper.enableStandardItemLighting();
            GlStateManager.enableRescaleNormal();
        } catch (Throwable ignored) {}
    }
    
    /**
     * Draws a gradient rectangle (helper for tooltip)
     */
    private static void drawTooltipGradientRect(int left, int top, int right, int bottom, int startColor, int endColor) {
        float startAlpha = (float)(startColor >> 24 & 255) / 255.0F;
        float startRed = (float)(startColor >> 16 & 255) / 255.0F;
        float startGreen = (float)(startColor >> 8 & 255) / 255.0F;
        float startBlue = (float)(startColor & 255) / 255.0F;
        float endAlpha = (float)(endColor >> 24 & 255) / 255.0F;
        float endRed = (float)(endColor >> 16 & 255) / 255.0F;
        float endGreen = (float)(endColor >> 8 & 255) / 255.0F;
        float endBlue = (float)(endColor & 255) / 255.0F;
        
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.shadeModel(7425);
        
        net.minecraft.client.renderer.Tessellator tessellator = net.minecraft.client.renderer.Tessellator.getInstance();
        net.minecraft.client.renderer.WorldRenderer worldrenderer = tessellator.getWorldRenderer();
        worldrenderer.begin(7, net.minecraft.client.renderer.vertex.DefaultVertexFormats.POSITION_COLOR);
        worldrenderer.pos(right, top, 0.0D).color(startRed, startGreen, startBlue, startAlpha).endVertex();
        worldrenderer.pos(left, top, 0.0D).color(startRed, startGreen, startBlue, startAlpha).endVertex();
        worldrenderer.pos(left, bottom, 0.0D).color(endRed, endGreen, endBlue, endAlpha).endVertex();
        worldrenderer.pos(right, bottom, 0.0D).color(endRed, endGreen, endBlue, endAlpha).endVertex();
        tessellator.draw();
        
        GlStateManager.shadeModel(7424);
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
    }
    
    /**
     * Draws an empty slot background (dark, non-white)
     */
    private static void drawEmptySlot(Minecraft mc, int x, int y, int size) {
        try {
            // Draw semi-transparent dark background for slot (can see through a bit)
            int bgColor = 0xC01A1A1A; // Semi-transparent dark gray (75% opacity)
            Gui.drawRect(x, y, x + size, y + size, bgColor);
            
            // Draw subtle border for slot
            int borderColor = 0x80373737; // Semi-transparent dark gray border
            // Top
            Gui.drawRect(x, y, x + size, y + 1, borderColor);
            // Bottom
            Gui.drawRect(x, y + size - 1, x + size, y + size, borderColor);
            // Left
            Gui.drawRect(x, y, x + 1, y + size, borderColor);
            // Right
            Gui.drawRect(x + size - 1, y, x + size, y + size, borderColor);
        } catch (Throwable ignored) {}
    }
    
    private static String formatHealthText(float hp, float maxHp) {
        try {
            int shown = (int)Math.ceil(hp);
            EnumChatFormatting col;
            if (shown >= 15) {
                col = EnumChatFormatting.GREEN;        // 15+ HP = green
            } else if (shown >= 9) {
                col = EnumChatFormatting.YELLOW;       // 9-14 HP = yellow
            } else {
                col = EnumChatFormatting.RED;          // 0-8 HP = red
            }
            return col + Integer.toString(shown) + " " + EnumChatFormatting.RED + "\u2764" + EnumChatFormatting.RESET;
        } catch (Throwable ignored) {}
        int shown = (int)Math.ceil(hp);
        return Integer.toString(shown) + " " + "\u00A7c\u2764\u00A7r";
    }

    /** ShareInv teammate HUD: same coloring + heart as the teammate tracker. */
    public static String formatHealthForTeammateHud(float hp, float maxHp) {
        if (hp < 0f || Float.isNaN(hp)) {
            return EnumChatFormatting.GRAY + "…" + EnumChatFormatting.RESET;
        }
        if (hp <= 0f) {
            return EnumChatFormatting.DARK_RED + "DEAD" + EnumChatFormatting.RESET;
        }
        return formatHealthText(hp, maxHp);
    }

    /**
     * Rotating direction arrow (same asset as teammate tracker) for ShareInv HUD.
     */
    public static void renderShareInvDirectionCursor(Minecraft mc, int centerX, int centerY, int iconSize, float angleDeg,
            boolean sneaking) {
        try {
            if (sneaking) {
                ensureCursorShiftTextureLoaded();
            } else {
                ensureCursorTextureLoaded();
            }
            GlStateManager.pushMatrix();
            GlStateManager.enableBlend();
            GlStateManager.translate(centerX, centerY + (iconSize / 2.0f), 0.0f);
            GlStateManager.rotate(angleDeg, 0, 0, 1);
            if (sneaking && cursorShiftLoaded) {
                mc.getTextureManager().bindTexture(cursorShiftUsingFallback ? CURSOR_SHIFT_TEXTURE_RT : CURSOR_SHIFT_TEXTURE);
                Gui.drawScaledCustomSizeModalRect(
                        -(iconSize / 2), -(iconSize / 2),
                        0, 0,
                        cursorShiftW, cursorShiftH,
                        iconSize, iconSize,
                        cursorShiftW, cursorShiftH
                );
            } else if (cursorLoaded) {
                mc.getTextureManager().bindTexture(cursorUsingFallback ? CURSOR_TEXTURE_RT : CURSOR_TEXTURE);
                Gui.drawScaledCustomSizeModalRect(
                        -(iconSize / 2), -(iconSize / 2),
                        0, 0,
                        cursorW, cursorH,
                        iconSize, iconSize,
                        cursorW, cursorH
                );
            }
            GlStateManager.disableBlend();
            GlStateManager.popMatrix();
        } catch (Throwable ignored) {}
    }

    /**
     * Scoreboard teammate for HUD lock, glow, and trail — independent of {@link #isEnabled()}.
     */
    private static EntityPlayer findTargetForTeammateLock(Minecraft mc) {
        if (targetPlayerName != null && !targetPlayerName.isEmpty()) {
            for (EntityPlayer p : mc.theWorld.playerEntities) {
                if (p == mc.thePlayer) {
                    continue;
                }
                if (p.getName().equalsIgnoreCase(targetPlayerName)) {
                    return p;
                }
            }
            return null;
        }
        if (ptCommandEnabled) {
            return null;
        }
        if (!isGameActive()) {
            return null;
        }
        return findTeamPlayerFromScoreboard(mc);
    }

    private static void applyTeammateLockFromEntity(Minecraft mc, EntityPlayer target) {
        String targetName = target.getName();
        if (targetName != null && !KillCounter.isPlayerDead(targetName)) {
            if (lastKnownTeammate == null || lastKnownTeammate.equalsIgnoreCase(targetName)) {
                lastKnownTeammate = targetName;
            }
        }
        String formattedName = getPlayerDisplayName(mc, target);
        cachedDisplayNames.put(targetName, formattedName);
        try {
            if (mc.getNetHandler() != null) {
                NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(target.getUniqueID());
                if (info != null && info.getLocationSkin() != null) {
                    cachedPlayerSkins.put(target.getName(), info.getLocationSkin());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static EntityPlayer findTarget(Minecraft mc) {
        EntityPlayer result = null;

        // /pt <player> command - track specific player
        if (targetPlayerName != null && !targetPlayerName.isEmpty()) {
            for (EntityPlayer p : mc.theWorld.playerEntities) {
                if (p == mc.thePlayer) continue;
                if (p.getName().equalsIgnoreCase(targetPlayerName)) {
                    result = p;
                    break;
                }
            }
            
            if (result == null && mc.getNetHandler() != null) {
                for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                    if (info.getGameProfile().getName().equalsIgnoreCase(targetPlayerName)) {
                        for (EntityPlayer p : mc.theWorld.playerEntities) {
                            if (p.getName().equalsIgnoreCase(targetPlayerName)) {
                                result = p;
                                break;
                            }
                        }
                        break;
                    }
                }
            }
            return result;
        }

        // GUI teammate tracker mode (enabled from settings)
        if (enabled && !ptCommandEnabled) {
            // Only track if game has started (check for "Taunt" in scoreboard)
            if (!isGameActive()) {
                return null; // Don't track in lobby/pre-game
            }
            
            // findTeamPlayerFromScoreboard handles the locked-teammate fast path:
            // if lastKnownTeammate is set and alive it returns that player directly,
            // regardless of the local player's current scoreboard team.
            result = findTeamPlayerFromScoreboard(mc);
            
            return result;
        }

        // /pt command mode (no specific player) - track nearest player (debug)
        if (ptCommandEnabled) {
            double min = Double.MAX_VALUE;
            for (EntityPlayer p : mc.theWorld.playerEntities) {
                if (p == mc.thePlayer) continue;
                if (p.isInvisible()) continue;
                double d = mc.thePlayer.getDistanceToEntity(p);
                if (d < min) {
                    min = d;
                    result = p;
                }
            }
            return result;
        }

        return null;
    }

    public static void notifyEnabledMessage() {
        sendMessage(EnumChatFormatting.GOLD + "[gomod] " + EnumChatFormatting.RESET +
                EnumChatFormatting.YELLOW + "PlayerTarget enabled" + EnumChatFormatting.RESET);
    }

    public static void notifyDisabledMessage() {
        sendMessage(EnumChatFormatting.GOLD + "[gomod] " + EnumChatFormatting.RESET +
                EnumChatFormatting.YELLOW + "PlayerTarget disabled" + EnumChatFormatting.RESET);
    }

    private static String getPlayerDisplayName(Minecraft mc, EntityPlayer player) {
        try {
            if (mc.getNetHandler() != null) {
                NetworkPlayerInfo playerInfo = mc.getNetHandler().getPlayerInfo(player.getUniqueID());
                if (playerInfo != null) {
                    IChatComponent displayComponent = playerInfo.getDisplayName();
                    if (displayComponent != null) {
                        return normalizeTeammateDisplayName(displayComponent.getFormattedText(), player.getName());
                    }
                    return normalizeTeammateDisplayName(
                            net.minecraft.scoreboard.ScorePlayerTeam.formatPlayerName(playerInfo.getPlayerTeam(), player.getName()),
                            player.getName());
                }
            }
        } catch (Throwable ignored) {}
        // Fallback to regular name if TAB info not available
        return normalizeTeammateDisplayName(player.getName(), player.getName());
    }
    
    /**
     * Draws a tooltip showing the target's coordinates when hovering over the cursor
     */
    private static void drawCursorCoordinatesTooltip(Minecraft mc, EntityPlayer target, int mouseX, int mouseY) {
        try {
            // Format coordinates
            String coords = String.format("X: %.1f  Y: %.1f  Z: %.1f", 
                target.posX, target.posY, target.posZ);
            
            // Calculate tooltip dimensions
            int tooltipWidth = mc.fontRendererObj.getStringWidth(coords) + 6;
            int tooltipHeight = 18;
            
            // Position tooltip near mouse cursor
            int tooltipX = mouseX + 12;
            int tooltipY = mouseY - 12;
            
            // Keep tooltip on screen
            ScaledResolution sr = new ScaledResolution(mc);
            if (tooltipX + tooltipWidth > sr.getScaledWidth()) {
                tooltipX = sr.getScaledWidth() - tooltipWidth - 4;
            }
            if (tooltipY + tooltipHeight > sr.getScaledHeight()) {
                tooltipY = sr.getScaledHeight() - tooltipHeight - 4;
            }
            if (tooltipY < 4) {
                tooltipY = 4;
            }
            
            // Draw background
            int backgroundColor = 0xF0100010;
            int borderColor = 0x505000FF;
            
            GlStateManager.disableRescaleNormal();
            GlStateManager.disableLighting();
            GlStateManager.disableDepth();
            
            // Background
            Gui.drawRect(tooltipX - 3, tooltipY - 4, tooltipX + tooltipWidth + 3, tooltipY + tooltipHeight - 4, backgroundColor);
            // Top border
            Gui.drawRect(tooltipX - 3, tooltipY - 4, tooltipX + tooltipWidth + 3, tooltipY - 3, borderColor);
            // Bottom border
            Gui.drawRect(tooltipX - 3, tooltipY + tooltipHeight - 5, tooltipX + tooltipWidth + 3, tooltipY + tooltipHeight - 4, borderColor);
            // Left border
            Gui.drawRect(tooltipX - 4, tooltipY - 3, tooltipX - 3, tooltipY + tooltipHeight - 4, borderColor);
            // Right border
            Gui.drawRect(tooltipX + tooltipWidth + 3, tooltipY - 3, tooltipX + tooltipWidth + 4, tooltipY + tooltipHeight - 4, borderColor);
            
            // Draw text
            drawHudText(mc.fontRendererObj, coords, tooltipX, tooltipY, 0xFFFFFFFF);
            
            GlStateManager.enableLighting();
            GlStateManager.enableDepth();
            GlStateManager.enableRescaleNormal();
            // Reset color to white after drawing tooltip
            GlStateManager.color(1f, 1f, 1f, 1f);
        } catch (Throwable ignored) {}
    }
    
    /**
     * Draws taunt status: READY, USED (taunt spent or kill credited via Taunt Bonus in chat),
     * or WASTED for the local player after 17s with no Taunt Bonus.
     * Other players stay USED — their coin lines are not shown on this client.
     */
    private static void drawTauntStatus(Minecraft mc, String playerName, int x, int y) {
        try {
            int labelW = mc.fontRendererObj.getStringWidth("Taunt: ");
            drawHudText(mc.fontRendererObj, EnumChatFormatting.WHITE + "Taunt: ", x, y, 0xFFFFFF);
            int statusX = x + labelW;

            String status;
            if (hasUsedTaunt(playerName)) {
                Long animStartTime = tauntAnimationStartTimes.get(playerName);
                long currentTime = System.currentTimeMillis();
                boolean isLocalPlayer = targetNameIsLocalPlayer(playerName, mc);

                if (animStartTime != null && (currentTime - animStartTime) < TAUNT_ANIMATION_DURATION) {
                    long elapsed = currentTime - animStartTime;
                    float progress = (float)elapsed / TAUNT_ANIMATION_DURATION;

                    String targetText = "USED";
                    int charsRevealed = (int)(progress * targetText.length());

                    StringBuilder animatedText = new StringBuilder();
                    for (int i = 0; i < targetText.length(); i++) {
                        if (i < charsRevealed) {
                            animatedText.append(EnumChatFormatting.RESET)
                                    .append(EnumChatFormatting.RED)
                                    .append(targetText.charAt(i));
                        } else {
                            animatedText.append(EnumChatFormatting.RESET)
                                    .append(EnumChatFormatting.GREEN)
                                    .append(EnumChatFormatting.OBFUSCATED)
                                    .append(targetText.charAt(i));
                        }
                    }
                    status = animatedText.toString();
                } else {
                    boolean wasted = isLocalPlayer && isLocalTauntWastedState();
                    if (wasted) {
                        status = EnumChatFormatting.RED + "WASTED";
                    } else if (isLocalPlayer && isLocalTauntSuccessSidebarState()) {
                        status = EnumChatFormatting.GREEN + "USED";
                    } else {
                        status = EnumChatFormatting.RED + "USED";
                    }
                }
            } else {
                status = EnumChatFormatting.GREEN + "READY";
            }

            drawHudText(mc.fontRendererObj, status, statusX, y, 0xFFFFFF);
        } catch (Throwable ignored) {}
    }

    private static void checkScoreboardAndToggle() {
        // Don't auto-toggle if /pt command is active OR user set it via GUI
        if (ptCommandEnabled || userSetViaGUI) return;
        
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null || mc.theWorld.getScoreboard() == null) return;
            
            // Don't check scoreboard until 1 second after game start
            // This prevents premature disabling when scoreboard is still loading
            if (gameStartTime > 0L && System.currentTimeMillis() - gameStartTime < AUTO_ENABLE_DELAY) {
                return; // Too early, don't check yet
            }
            
            net.minecraft.scoreboard.Scoreboard scoreboard = mc.theWorld.getScoreboard();
            net.minecraft.scoreboard.ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(1);
            if (objective == null) return;
            
            boolean foundTeam = false;
            boolean foundTaunt = false;
            List<String> scoreboardLines = new ArrayList<>();
            for (net.minecraft.scoreboard.Score score : scoreboard.getSortedScores(objective)) {
                net.minecraft.scoreboard.ScorePlayerTeam team = scoreboard.getPlayersTeam(score.getPlayerName());
                String raw = net.minecraft.scoreboard.ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
                if (raw == null) continue;
                String clean = raw.replaceAll("§[0-9A-FK-ORa-fk-or]", "").toLowerCase();
                scoreboardLines.add(clean);
                if (clean.contains("team")) {
                    foundTeam = true;
                }
                if (clean.contains("taunt")) {
                    foundTaunt = true;
                }
            }
            
            // Auto-enable/disable ONLY if user hasn't explicitly set it via GUI
            if (!userSetViaGUI) {
                // Auto-enable when "team" is found AND "taunt" is present (game is active)
                if (foundTeam && foundTaunt && !enabled) {
                    enabled = true;
                    ConfigManager.setPlayerTargetEnabled(true);
                    System.out.println("[PlayerTarget] ✓ Auto-enabled: 'team' and 'taunt' found in scoreboard");
                    System.out.println("[PlayerTarget] Scoreboard lines: " + scoreboardLines);
                } else if ((!foundTeam || !foundTaunt) && enabled) {
                    // Auto-disable when either "team" OR "taunt" is missing (game ended or left team mode)
                    enabled = false;
                    ConfigManager.setPlayerTargetEnabled(false);
                    System.out.println("[PlayerTarget] ✗ Auto-disabled: 'team' or 'taunt' not found in scoreboard");
                    System.out.println("[PlayerTarget] Scoreboard lines: " + scoreboardLines);
                }
            }
        } catch (Throwable ignored) {}
    }
    
    /**
     * Called when "Survive while eliminating" message is detected
     * Sets the game start time for delayed auto-enable
     */
    public static void onGameStart() {
        gameStartTime = System.currentTimeMillis();
        System.out.println("[PlayerTarget] Game start detected, will allow auto-enable in 1 second");
    }

    private static boolean isAutoEnabledByScoreboard() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null || mc.theWorld.getScoreboard() == null) return false;
            
            net.minecraft.scoreboard.Scoreboard scoreboard = mc.theWorld.getScoreboard();
            net.minecraft.scoreboard.ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(1);
            if (objective == null) return false;
            
            for (net.minecraft.scoreboard.Score score : scoreboard.getSortedScores(objective)) {
                net.minecraft.scoreboard.ScorePlayerTeam team = scoreboard.getPlayersTeam(score.getPlayerName());
                String raw = net.minecraft.scoreboard.ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
                if (raw == null) continue;
                String clean = raw.replaceAll("§[0-9A-FK-ORa-fk-or]", "").toLowerCase();
                if (clean.contains("team")) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }
    
    /**
     * Checks if the game is active (not in lobby) by looking for "Taunt" in scoreboard
     */
    private static boolean isGameActive() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            return BlitzTeamsDetector.sidebarHasTaunt(mc);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static EntityPlayer findTeamPlayerFromScoreboard(Minecraft mc) {
        if (mc == null || mc.theWorld == null || mc.getNetHandler() == null) {
            return null;
        }
        World w = mc.theWorld;
        long t = w.getTotalWorldTime();
        if (findTeamCacheWorld == w && findTeamCacheTime == t) {
            return findTeamCacheResult;
        }
        findTeamCacheWorld = w;
        findTeamCacheTime = t;
        findTeamCacheResult = findTeamPlayerFromScoreboardImpl(mc);
        return findTeamCacheResult;
    }

    private static EntityPlayer findTeamPlayerFromScoreboardImpl(Minecraft mc) {
        try {
            if (mc.theWorld == null || mc.getNetHandler() == null) return null;
            
            // CRITICAL: Don't track teammates in lobby/pre-game - only in active games
            if (!BlitzTeamsDetector.sidebarHasTaunt(mc)) {
                // Clear last known teammate when not in active game
                lastKnownTeammate = null;
                return null;
            }
            
            net.minecraft.scoreboard.Scoreboard scoreboard = mc.theWorld.getScoreboard();
            if (scoreboard == null) return null;
            
            // Require both "Team" text AND "Taunt" (active game indicator) to consider it a team game
            // This prevents lobby scoreboards from being detected as team games
            if (!BlitzTeamsDetector.sidebarHasTeamAndTaunt(mc)) {
                // Clear last known teammate since we're not in a team game
                lastKnownTeammate = null;
                return null;
            }
            
            // ── LOCKED-TEAMMATE FAST PATH ──────────────────────────────────────
            // Once lastKnownTeammate is set it is the definitive teammate for the
            // remainder of the game.  We ALWAYS return that player regardless of
            // what scoreboard team the local player is currently on.  This prevents
            // Hypixel's post-death team reassignment from tricking us into glowing
            // a different player.
            if (lastKnownTeammate != null && !lastKnownTeammate.isEmpty()) {
                // If our locked teammate died → clear the lock
                if (me.napim.gomod.features.KillCounter.isPlayerDead(lastKnownTeammate)) {
                    lastKnownTeammate = null;
                    // fall through to normal scoreboard discovery below
                } else {
                    // Teammate is alive — find their entity in the world
                    for (EntityPlayer player : mc.theWorld.playerEntities) {
                        if (player == mc.thePlayer) continue;
                        String name = player.getName();
                        if (name != null && name.equalsIgnoreCase(lastKnownTeammate)) {
                            return player;
                        }
                    }
                    // Entity not loaded in the world right now — caller's fallback
                    // (getCurrentTeammateName) will still return lastKnownTeammate by name.
                    return null;
                }
            }

            // ── FIRST-TIME DISCOVERY via scoreboard ─────────────────────────────
            // We only reach here when lastKnownTeammate is null (game just started,
            // or teammate died and the lock was cleared).

            // Get local player's team
            net.minecraft.scoreboard.ScorePlayerTeam myTeam = scoreboard.getPlayersTeam(mc.thePlayer.getName());

            // Validate team name — reject random hex strings that Hypixel uses for ranks/lobby.
            if (myTeam != null && !isValidTeamName(myTeam.getRegisteredName())) {
                return null; // Not on a real team yet
            }

            // No team → can't discover teammate
            if (myTeam == null) {
                return null;
            }

            // We're on a team — find the nearest TEAMMATE (player on same team)
            EntityPlayer nearestTeammate = null;
            double minDistance = Double.MAX_VALUE;
            List<String> teammates = new ArrayList<>();
            List<String> enemies = new ArrayList<>();

            boolean hypixelTeamsBlitzTabTag = BlitzTeamsDetector.isActiveBlitzTeamsGame(mc) && mc.getNetHandler() != null;
            
            // Check all players in world
            for (EntityPlayer player : mc.theWorld.playerEntities) {
                if (player == mc.thePlayer) continue; // Skip self
                
                String name = player.getName();
                
                // Check if this is a real player name (not NPC)
                if (!isRealPlayerName(name)) {
                    continue; // Skip NPCs
                }
                
                // Check if player is on the same team
                net.minecraft.scoreboard.ScorePlayerTeam playerTeam = scoreboard.getPlayersTeam(name);
                
                // Validate player's team name too
                if (playerTeam != null && !isValidTeamName(playerTeam.getRegisteredName())) {
                    continue; // Skip players with invalid team names (likely rank-based teams from lobby)
                }
                
                boolean isTeammate = false;
                // Teams Blitz: scoreboard teams often group whole districts; real duo is marked with [TEAM] on TAB.
                if (hypixelTeamsBlitzTabTag) {
                    NetworkPlayerInfo npi = mc.getNetHandler().getPlayerInfo(player.getUniqueID());
                    if (npi != null && KillCounter.tabRowShowsHypixelTeamTag(npi)) {
                        isTeammate = true;
                    }
                } else if (playerTeam != null && myTeam.isSameTeam(playerTeam)) {
                    isTeammate = true;
                }
                
                if (isTeammate) {
                    // Only track alive teammates (not dead/spectating)
                    if (!me.napim.gomod.features.KillCounter.isPlayerDead(name)) {
                        teammates.add(name);
                        double distance = mc.thePlayer.getDistanceToEntity(player);
                        if (distance < minDistance) {
                            minDistance = distance;
                            nearestTeammate = player;
                        }
                    }
                } else {
                    enemies.add(name);
                }
            }
            
            return nearestTeammate;
        } catch (Throwable e) {
            System.out.println("[PlayerTarget] Error finding teammate: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }
    
    /**
     * Validates that a team name is a legitimate team name, not a random hex string
     * used by Hypixel for ranks in lobbies (like "a908203421f7").
     * 
     * Valid team names typically:
     * - Are short (under 20 chars usually)
     * - Contain letters (often color names or team identifiers)
     * - Don't look like hex strings (long strings of hex digits)
     * 
     * Invalid team names:
     * - Long hex strings like "a908203421f7" (14 chars, all hex)
     * - Pure hex strings longer than 10 chars
     */
    private static boolean isValidTeamName(String teamName) {
        if (teamName == null || teamName.isEmpty()) {
            return false;
        }
        
        // Very short names (1-3 chars) might be valid (like "Red", "Blue")
        if (teamName.length() <= 3) {
            return true;
        }
        
        // Long team names (over 20 chars) are probably invalid
        if (teamName.length() > 20) {
            return false;
        }
        
        // Check if it's a pure hex string (all hexadecimal characters)
        // Team names longer than 10 chars that are pure hex are likely rank-based teams from lobby
        if (teamName.length() > 10 && teamName.matches("^[0-9a-fA-F]+$")) {
            // This looks like a hex string - probably a rank-based team from lobby
            return false;
        }
        
        // If it contains at least one letter (non-hex), it's probably a real team name
        if (teamName.matches(".*[g-zG-Z].*")) {
            return true; // Contains letters beyond hex range, likely a real team name
        }
        
        // If it's all hex but short (<=10), might be valid (like color codes)
        // But if it's long and all hex, reject it
        if (teamName.matches("^[0-9a-fA-F]+$")) {
            return teamName.length() <= 10;
        }
        
        // Default: allow it if we can't determine it's invalid
        return true;
    }
    
    /**
     * Checks if a name is a real player (not NPC)
     * Real players: 3-16 chars, only letters, numbers, underscore
     * NPCs: Random strings like "9lv5qpr1j8", all lowercase, start with digit
     */
    private static boolean isRealPlayerName(String name) {
        if (name == null || name.length() < 3 || name.length() > 16) return false;
        
        // Must match Minecraft username pattern
        if (!name.matches("^[a-zA-Z0-9_]+$")) return false;
        
        // NPCs often start with a digit and are all lowercase
        // Real players usually have mixed case or start with a letter
        if (name.matches("^[0-9][a-z0-9]{9}$")) {
            return false; // Likely an NPC (10 chars, starts with digit, all lowercase)
        }
        
        return true;
    }
    
    private static List<String> getPlayerNamesInWorld(Minecraft mc) {
        List<String> names = new ArrayList<>();
        for (EntityPlayer p : mc.theWorld.playerEntities) {
            names.add(p.getName());
        }
        return names;
    }
    
    private static boolean isPlayerName(String name) {
        if (name == null || name.isEmpty()) return false;
        
        // Player names typically:
        // - Don't contain spaces
        // - Are not too long (usually under 20 chars to account for special characters)
        // - Don't contain common team/game words
        // - Are not purely numbers
        
        if (name.contains(" ")) return false;
        if (name.length() > 20) return false;
        if (name.length() < 2) return false;
        
        // Exclude common team/game words
        String lower = name.toLowerCase();
        if (lower.contains("team") || lower.contains("score") || lower.contains("kills") || 
            lower.contains("deaths") || lower.contains("game") || lower.contains("time") ||
            lower.contains("map") || lower.contains("players") || lower.contains("alive") ||
            lower.contains("additional") || lower.contains("allow")) {
            return false;
        }
        
        // Exclude if it's purely numbers
        try {
            Integer.parseInt(name);
            return false;
        } catch (NumberFormatException ignored) {}
        
        // Exclude single character entries (like just "⚽")
        if (name.length() == 1) return false;
        
        return true;
    }
}

 
