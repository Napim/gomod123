package me.napim.gomod.features;

import me.napim.gomod.render.VanillaPlayerNametagLayout;
import me.napim.gomod.util.GameContext;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.block.material.Material;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.WorldSettings;
import me.napim.gomod.mixins.RenderAccessor;
import me.napim.gomod.mixins.RendererLivingEntityInvoker;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static me.napim.gomod.Main.sendMessage;
import static me.napim.gomod.Main.CHAT_PREFIX;

/**
 * VisibleSpecs: reveal Blitz spectators the client still tracks (invisible entities).
 * <ul>
 *   <li>Players marked dead by KillCounter are revealed using the configured spectator opacity.</li>
 *   <li>{@code /spectatorsbypass} — during an active Blitz match, any other player who is
 *       invisible (flag or invis potion), including living invis-kit players. That bypass affects
 *       visibility overrides only; {@linkplain #shouldApplyOpacityToEntity ghost opacity} applies
 *       only to players on the KillCounter dead list.</li>
 * </ul>
 */
public class VisibleSpecs {
    public static final String NAMETAG_MODE_ON = "ON";
    public static final String NAMETAG_MODE_ONLY_IGN = "ONLY_IGN";
    public static final String NAMETAG_MODE_OFF = "OFF";

    /** Settings GUI preview: eliminated spectator skin + nametag layout. */
    public static final UUID PREVIEW_SPECTATOR_UUID = UUID.fromString("658a7bff-3f97-4393-aac2-c7ada63d8600");
    public static final String PREVIEW_SPECTATOR_NAME = "TheShamanBilly";
    public static final String PREVIEW_KILLER = "meqa";

    /** Vanilla nameplate background alpha (~25% black), same as {@link me.napim.gomod.gui.DenickerSettingsGUI}. */
    private static final int PREVIEW_NAMETAG_PLATE_BG = 0x40000000;
    private static final double BLINDNESS_FOG_END_BLOCKS = 5.0D;
    private static final double BLINDNESS_NAMETAG_EDGE_PADDING_BLOCKS = 0.5D;

    private enum VisibleSpecsRenderPhase {
        NONE,
        PREPASS,
        COLOR
    }

    /** When true, reveal invisible remote players during active Blitz (see class javadoc). */
    private static boolean spectatorBypassAllInvisible = false;
    /** Internal-build master switch. Public builds always use automatic spectator-only activation. */
    private static boolean internalMasterEnabled = false;

    /** Interval (in ms) between spectator scan chat reports. */
    private static final long SCAN_INTERVAL_MS = 3000L;
    private static long lastScanMs = 0L;
    /** Names already reported this game session so we don't spam repeatedly. */
    private static final Set<String> reportedThisSession = new HashSet<String>();
    /** Last game-active state, used to clear reported set on new game. */
    private static boolean wasGameActive = false;
    private static long lastReplayContextCheckMs = 0L;
    private static boolean lastReplayContext = false;
    private static EntityPlayer runtimeCachePlayer = null;
    private static int runtimeCachePlayerTick = Integer.MIN_VALUE;
    private static boolean runtimeCacheActive = false;

    /**
     * Render-thread decisions for remote players.  RenderManager and the player renderer can ask the same
     * question several times per frame.  The answer is stable for a client tick, so keep it out of the hot
     * per-frame path.  This is especially important on Forge, where Patcher calls the vanilla player renderer
     * directly for every visible player.
     */
    private static EntityPlayer renderDecisionLocalPlayer = null;
    private static int renderDecisionPlayerTick = Integer.MIN_VALUE;
    private static final Map<Integer, Boolean> renderDecisionShowCache = new HashMap<Integer, Boolean>();
    private static final Map<Integer, Boolean> renderDecisionOpacityCache = new HashMap<Integer, Boolean>();
    private static final Map<String, String> nametagRenderDebug = new java.util.LinkedHashMap<String, String>();
    private static final ThreadLocal<StringBuilder> visibleSpecsDebugCapture = new ThreadLocal<StringBuilder>();

    private static int glHooksDepth = 0;
    private static boolean savedAlphaTest = false;
    private static int savedAlphaTestFunc = GL11.GL_GREATER;
    private static float savedAlphaTestRef = 0.1F;
    private static boolean savedBlend = false;
    private static boolean savedCullFace = false;
    private static int externalCosmeticRenderDepth = 0;
    private static boolean externalCosmeticOpenedPhase = false;
    private static boolean externalCosmeticOpenedPrepassColor = false;
    private static boolean externalCosmeticSavedBlend = false;
    private static boolean externalCosmeticSavedAlphaTest = false;
    private static boolean externalCosmeticSavedDepthMask = true;
    private static boolean externalCosmeticLogged = false;
    private static int guiInventoryRenderDepth = 0;
    private static boolean guiInventorySavedBlend = false;
    private static boolean guiInventorySavedAlphaTest = false;
    private static boolean guiInventorySavedCullFace = false;
    private static boolean guiInventorySavedDepthMask = true;
    private static boolean guiInventoryLogged = false;
    private static boolean firstPersonArmActive = false;
    private static final ThreadLocal<VisibleSpecsRenderPhase> renderPhase =
            new ThreadLocal<VisibleSpecsRenderPhase>() {
                @Override
                protected VisibleSpecsRenderPhase initialValue() {
                    return VisibleSpecsRenderPhase.NONE;
                }
            };
    private static final ThreadLocal<EntityPlayer> activeRenderEntity = new ThreadLocal<EntityPlayer>();
    private static final ThreadLocal<Integer> unscaledNameplateDepth = new ThreadLocal<Integer>() {
        @Override
        protected Integer initialValue() {
            return 0;
        }
    };
    private static final List<DeferredVisibleSpecsRender> deferredRenders = new ArrayList<DeferredVisibleSpecsRender>();
    private static final Set<Integer> deferredRenderEntityIds = new HashSet<Integer>();
    private static final List<DeferredVisibleSpecsNametag> deferredNametags = new ArrayList<DeferredVisibleSpecsNametag>();
    private static final List<DeferredPlayerOffsetNametag> deferredPlayerOffsetNametags =
            new ArrayList<DeferredPlayerOffsetNametag>();
    private static final Set<Integer> deferredPlayerOffsetNametagEntityIds = new HashSet<Integer>();
    private static final List<DeferredReplayNameplateStack> deferredReplayNameplates =
            new ArrayList<DeferredReplayNameplateStack>();
    private static boolean flushingDeferred = false;
    private static boolean flushingDeferredNametags = false;
    private static boolean directOpacityRender = false;
    private static final boolean WEAVE_RUNTIME = detectWeaveRuntime();
    private static boolean weaveDeferredLogged = false;
    private static final int NAMETAG_WATER_CACHE_MAX_ENTRIES = 512;
    private static final Map<Long, Boolean> nametagWaterOcclusionCache = new HashMap<Long, Boolean>();
    private static final Map<Long, Boolean> nametagGlassOcclusionCache = new HashMap<Long, Boolean>();
    /**
     * Set when {@code renderBlockLayer(TRANSLUCENT)} runs this {@code renderEntities} pass; used to detect when
     * deferred opacity draws were never flushed (sky-only view, fast graphics, etc.).
     */
    private static boolean translucentBlockLayerEnteredThisEntitiesPass = false;

    private static final class DeferredVisibleSpecsRender {
        private final Entity entity;
        private final float partialTicks;

        private DeferredVisibleSpecsRender(Entity entity, float partialTicks) {
            this.entity = entity;
            this.partialTicks = partialTicks;
        }
    }

    private static final class DeferredVisibleSpecsNametag {
        private final RendererLivingEntity renderer;
        private final EntityLivingBase entity;
        private final double x;
        private final double y;
        private final double z;

        private DeferredVisibleSpecsNametag(RendererLivingEntity renderer, EntityLivingBase entity,
                                            double x, double y, double z) {
            this.renderer = renderer;
            this.entity = entity;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final class DeferredReplayNameplateStack {
        private final RendererLivingEntity renderer;
        private final EntityLivingBase entity;
        private final double x;
        private final double y;
        private final double z;

        private DeferredReplayNameplateStack(RendererLivingEntity renderer, EntityLivingBase entity,
                                             double x, double y, double z) {
            this.renderer = renderer;
            this.entity = entity;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final class DeferredPlayerOffsetNametag {
        private final RenderPlayer renderer;
        private final AbstractClientPlayer player;
        private final double x;
        private final double y;
        private final double z;
        private final String label;
        private final float labelScale;
        private final double distanceSq;

        private DeferredPlayerOffsetNametag(RenderPlayer renderer, AbstractClientPlayer player,
                                            double x, double y, double z, String label,
                                            float labelScale, double distanceSq) {
            this.renderer = renderer;
            this.player = player;
            this.x = x;
            this.y = y;
            this.z = z;
            this.label = label;
            this.labelScale = labelScale;
            this.distanceSq = distanceSq;
        }
    }

    /**
     * Spectator rendering is completely dormant until the local player is actually spectating.
     * This keeps the expensive translucent-player/nameplate pipeline out of normal gameplay.
     */
    private static boolean isSpectatorSettingsRuntimeActive(Minecraft mc) {
        EntityPlayer local = mc == null ? null : mc.thePlayer;
        if (local == null) {
            runtimeCachePlayer = null;
            runtimeCachePlayerTick = Integer.MIN_VALUE;
            runtimeCacheActive = false;
            return false;
        }
        int tick = local.ticksExisted;
        if (runtimeCachePlayer == local && runtimeCachePlayerTick == tick) {
            return runtimeCacheActive;
        }
        runtimeCachePlayer = local;
        runtimeCachePlayerTick = tick;
        runtimeCacheActive = computeSpectatorSettingsRuntimeActive(mc);
        return runtimeCacheActive;
    }

    private static boolean computeSpectatorSettingsRuntimeActive(Minecraft mc) {
        if (SpectatorRelay.isAliveViewer()) {
            return true;
        }
        if (me.napim.gomod.GomodInternalFeatures.isEnabled() && !internalMasterEnabled) {
            return false;
        }
        if (mc == null || !isLocalEligibleForVisibleSpecsOpacity(mc)) {
            return false;
        }
        if (isReplayContext()) {
            return isApplySettingsToReplaysEnabled();
        }
        return isBlitzSpectatorSettingsPhase(mc);
    }

    /** Blitz deathmatch replaces the live-match sidebar fingerprint with "Game End". */
    private static boolean isBlitzSpectatorSettingsPhase(Minecraft mc) {
        return GameContext.isOnHypixel(mc)
                && (GameContext.isBlitzGameActive(mc) || GameContext.isBlitzDeathmatchPhase(mc));
    }

    private static void clearDeferredState() {
        try {
            deferredRenders.clear();
            deferredNametags.clear();
            deferredPlayerOffsetNametags.clear();
            deferredPlayerOffsetNametagEntityIds.clear();
            deferredReplayNameplates.clear();
            flushingDeferred = false;
            flushingDeferredNametags = false;
            directOpacityRender = false;
            glHooksDepth = 0;
            externalCosmeticRenderDepth = 0;
            guiInventoryRenderDepth = 0;
            renderPhase.set(VisibleSpecsRenderPhase.NONE);
            activeRenderEntity.remove();
        } catch (Throwable ignored) {
        }
    }

    private static boolean isSpectatorSettingsRuntimeActive() {
        return isSpectatorSettingsRuntimeActive(Minecraft.getMinecraft());
    }

    private static void prepareRenderDecisionCache(Minecraft mc) {
        EntityPlayer local = mc == null ? null : mc.thePlayer;
        int tick = local == null ? Integer.MIN_VALUE : local.ticksExisted;
        if (renderDecisionLocalPlayer != local || renderDecisionPlayerTick != tick) {
            renderDecisionLocalPlayer = local;
            renderDecisionPlayerTick = tick;
            renderDecisionShowCache.clear();
            renderDecisionOpacityCache.clear();
        }
    }

    private static void clearRenderDecisionCache() {
        renderDecisionLocalPlayer = null;
        renderDecisionPlayerTick = Integer.MIN_VALUE;
        renderDecisionShowCache.clear();
        renderDecisionOpacityCache.clear();
    }

    /** Cached render-only wrapper for the visibility gate used by the renderer hot path. */
    public static boolean shouldShowPlayerRemoteForRender(EntityPlayer subject) {
        if (subject == null) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        prepareRenderDecisionCache(mc);
        Integer id = Integer.valueOf(subject.getEntityId());
        Boolean cached = renderDecisionShowCache.get(id);
        if (cached != null) {
            return cached.booleanValue();
        }
        boolean result = shouldShowPlayerRemote(subject);
        renderDecisionShowCache.put(id, Boolean.valueOf(result));
        return result;
    }

    /**
     * Cached render-only wrapper for opacity decisions.  Local-player opacity is deliberately evaluated through
     * the uncached method because perspective/GUI transitions can affect it immediately; only remote-player
     * decisions use this cache.
     */
    public static boolean shouldApplyToEntityForRender(Entity entity) {
        if (!(entity instanceof EntityPlayer)) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || entity == mc.thePlayer) {
            return shouldApplyOpacityToEntity(entity);
        }
        EntityPlayer player = (EntityPlayer) entity;
        prepareRenderDecisionCache(mc);
        Integer id = Integer.valueOf(player.getEntityId());
        Boolean cached = renderDecisionOpacityCache.get(id);
        if (cached != null) {
            return cached.booleanValue();
        }
        boolean result = shouldApplyOpacityToEntity(player);
        renderDecisionOpacityCache.put(id, Boolean.valueOf(result));
        return result;
    }

    public static boolean isSpectatorSettingsRuntimeActiveForDebug(Minecraft mc) {
        return isSpectatorSettingsRuntimeActive(mc);
    }

    /** Cached once per client tick; intended for renderer hot-path gates. */
    public static boolean isRuntimeActiveForRender() {
        return isSpectatorSettingsRuntimeActive();
    }

    public static boolean isSpectatorBypassAllInvisible() {
        return spectatorBypassAllInvisible;
    }

    /** Dev-build master state used by {@code /visiblespecs}. */
    public static boolean isEnabled() {
        return !me.napim.gomod.GomodInternalFeatures.isEnabled() || internalMasterEnabled;
    }

    /** Toggle the dev-build master without changing public automatic behavior. */
    public static void setEnabled(boolean value) {
        if (!me.napim.gomod.GomodInternalFeatures.isEnabled()) {
            return;
        }
        internalMasterEnabled = value;
        ConfigManager.setVisibleSpecsEnabled(value);
        runtimeCachePlayerTick = Integer.MIN_VALUE;
        clearRenderDecisionCache();
        if (!value) {
            clearDeferredState();
        }
        String status = value ? EnumChatFormatting.GREEN + "enabled" : EnumChatFormatting.RED + "disabled";
        sendMessage(CHAT_PREFIX + "Visible Spectators " + status
                + EnumChatFormatting.GRAY + " (runs only while spectating)");
    }

    public static void setSpectatorBypassAllInvisible(boolean value) {
        spectatorBypassAllInvisible = value;
        ConfigManager.setSpectatorBypassAllInvisible(value);
        clearRenderDecisionCache();
        String status = value ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF";
        sendMessage(CHAT_PREFIX + "Spectator bypass " + status + EnumChatFormatting.GRAY
                + " (reveal invis in Blitz; opacity follows dead/spectator targets)");
    }

    public static float getOpacity() {
        return ConfigManager.getVisibleSpecsOpacity();
    }

    public static float getRenderOpacity() {
        float opacity = getOpacity();
        return Math.max(0.0f, Math.min(1.0f, opacity));
    }

    public static boolean areNametagsEnabled() {
        return ConfigManager.isVisibleSpecsNametagsEnabled();
    }

    public static void setNametagsEnabled(boolean enabled) {
        ConfigManager.setVisibleSpecsNametagsEnabled(enabled);
    }

    public static boolean isApplySettingsToReplaysEnabled() {
        return ConfigManager.isVisibleSpecsApplyToReplaysEnabled();
    }

    public static void setApplySettingsToReplaysEnabled(boolean enabled) {
        ConfigManager.setVisibleSpecsApplyToReplaysEnabled(enabled);
    }

    public static boolean isReplayContext() {
        long now = Minecraft.getSystemTime();
        if (now - lastReplayContextCheckMs < 250L) {
            return lastReplayContext;
        }
        Minecraft mc = Minecraft.getMinecraft();
        lastReplayContext = ReplaySettings.isReplayActive()
                || (mc != null && GameContext.isHypixelReplayWatching(mc));
        lastReplayContextCheckMs = now;
        return lastReplayContext;
    }

    public static boolean shouldApplySettingsInCurrentReplay() {
        return !isReplayContext() || isApplySettingsToReplaysEnabled();
    }

    public static String getNametagMode() {
        return ConfigManager.getVisibleSpecsNametagMode();
    }

    public static void setNametagMode(String mode) {
        ConfigManager.setVisibleSpecsNametagMode(mode);
    }

    public static String cycleNametagMode() {
        String mode = getNametagMode();
        String next;
        if (NAMETAG_MODE_ON.equals(mode)) {
            next = NAMETAG_MODE_ONLY_IGN;
        } else if (NAMETAG_MODE_ONLY_IGN.equals(mode)) {
            next = NAMETAG_MODE_OFF;
        } else {
            next = NAMETAG_MODE_ON;
        }
        setNametagMode(next);
        return next;
    }

    public static void setOpacity(float alpha) {
        ConfigManager.setVisibleSpecsOpacity(alpha);
        clearRenderDecisionCache();
    }

    public static void setOpacityRuntime(float alpha) {
        ConfigManager.setVisibleSpecsOpacityRuntime(alpha);
        clearRenderDecisionCache();
    }

    public static void persistOpacity() {
        ConfigManager.persistVisibleSpecsOpacity();
    }

    public static void loadSetting() {
        spectatorBypassAllInvisible = ConfigManager.isSpectatorBypassAllInvisible();
        if (me.napim.gomod.GomodInternalFeatures.isEnabled()) {
            internalMasterEnabled = ConfigManager.isVisibleSpecsEnabled();
        }
        runtimeCachePlayerTick = Integer.MIN_VALUE;
    }

    public static String formatPreviewSpectatorIgnNametag() {
        return EnumChatFormatting.BLUE + PREVIEW_SPECTATOR_NAME + EnumChatFormatting.RESET;
    }

    public static String formatPreviewKillerNametag() {
        return EnumChatFormatting.GRAY + "Killer: "
                + EnumChatFormatting.RED + PREVIEW_KILLER + EnumChatFormatting.RESET;
    }

    public static boolean shouldDrawPreviewIgnNametag() {
        return !NAMETAG_MODE_OFF.equals(getNametagMode());
    }

    public static boolean shouldDrawPreviewKillerNametag() {
        return ConfigManager.isVisibleSpecsKillerNametagEnabled();
    }

    public static boolean shouldDrawPreviewHealthNametag() {
        return NAMETAG_MODE_ON.equals(getNametagMode());
    }

    private static final String PREVIEW_HEALTH_HEART = "\u2764";

    public static String formatPreviewHealthNametag() {
        return EnumChatFormatting.WHITE + "20"
                + EnumChatFormatting.RED + PREVIEW_HEALTH_HEART + EnumChatFormatting.RESET;
    }

    /** Vertical gap between stacked preview nametag rows (matches ~vanilla 1.15× font height). */
    public static int previewNametagRowSpacingPx() {
        return 14;
    }

    /**
     * Draws preview nametags (2D GUI) at {@code centerX} with foot anchor {@code modelBaseY}.
     * Stack bottom → top (more negative Y = higher on screen): health, then IGN, then killer.
     * {@code stackBaseOffsetY} is the bottom row (hearts when nametags ON, IGN when ONLY_IGN, killer when OFF).
     */
    public static void drawSettingsPreviewNametags(net.minecraft.client.gui.FontRenderer font, int centerX,
                                                   int modelBaseY, int stackBaseOffsetY) {
        if (font == null) {
            return;
        }
        int row = previewNametagRowSpacingPx();
        boolean showIgn = shouldDrawPreviewIgnNametag();
        boolean showKiller = shouldDrawPreviewKillerNametag();
        boolean showHealth = shouldDrawPreviewHealthNametag();
        int baseY = stackBaseOffsetY;
        if (!showIgn && showKiller) {
            drawPreviewNametagPlate(font, centerX, modelBaseY + baseY, formatPreviewKillerNametag());
            return;
        }
        int rowsAboveBase = 0;
        if (showHealth) {
            drawPreviewNametagPlate(font, centerX, modelBaseY + baseY, formatPreviewHealthNametag());
            rowsAboveBase++;
        }
        if (showIgn) {
            drawPreviewNametagPlate(font, centerX, modelBaseY + baseY - rowsAboveBase * row,
                    formatPreviewSpectatorIgnNametag());
            rowsAboveBase++;
        }
        if (showKiller) {
            drawPreviewNametagPlate(font, centerX, modelBaseY + baseY - rowsAboveBase * row,
                    formatPreviewKillerNametag());
        }
    }

    private static void drawPreviewNametagPlate(net.minecraft.client.gui.FontRenderer font, int centerX, int textY,
                                                String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int textW = font.getStringWidth(text);
        int textX = centerX - textW / 2;
        int left = textX - 1;
        int right = textX + textW + 1;
        int top = textY - 1;
        int bottom = textY + 8;
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableTexture2D();
        net.minecraft.client.gui.Gui.drawRect(left, top, right, bottom, PREVIEW_NAMETAG_PLATE_BG);
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        font.drawString(text, textX, textY, 0xFFFFFFFF);
    }

    /**
     * Chat dump for diagnosing missing spectator opacity / invisible reveals.
     * Copy all lines to share. Use {@code includeKillCounterTabDump} to also run
     * {@link KillCounter#dumpTabListDebugToChat()} (same as {@code /gomodlag tab}).
     */
    public static void dumpDebugSnapshotToChat(boolean includeKillCounterTabDump) {
        Minecraft mc = Minecraft.getMinecraft();
        StringBuilder capture = new StringBuilder(8192);
        visibleSpecsDebugCapture.set(capture);
        try {
        if (mc == null || mc.thePlayer == null) {
            sendDebugMessage(CHAT_PREFIX + EnumChatFormatting.RED + "visiblespecsdebug: not in world.");
            return;
        }
        float op = getOpacity();
        sendDebugMessage(CHAT_PREFIX + EnumChatFormatting.AQUA + "=== visiblespecsdebug (copy all) ===");
        sendDebugMessage(EnumChatFormatting.GRAY + "visiblespecs runtimeActive=" + isSpectatorSettingsRuntimeActive(mc)
                + "  opacity=" + String.format(Locale.ROOT, "%.3f", op)
                + (op >= 0.99f ? "  " + EnumChatFormatting.YELLOW + "(>=0.99 → alpha scaling off)" : ""));
        sendDebugMessage(EnumChatFormatting.GRAY + "/spectatorsbypass=" + spectatorBypassAllInvisible
                + EnumChatFormatting.DARK_GRAY + " (reveal only; opacity follows dead/spectator targets)");
        sendDebugMessage(EnumChatFormatting.GRAY + "KillCounter runtime=" + (KillCounter.isEnabled() ? "on" : "off")
                + "  config=" + (ConfigManager.isKillCounterEnabled() ? "on" : "off"));
        sendDebugMessage(EnumChatFormatting.GRAY + "GameContext: hypixel=" + GameContext.isOnHypixel(mc)
                + "  blitzGameActive=" + GameContext.isBlitzGameActive(mc)
                + "  blitzDeathmatch=" + GameContext.isBlitzDeathmatchPhase(mc)
                + "  blitzSpectatorSidebar=" + GameContext.isBlitzSpectator(mc)
                + "  replaySettings=" + ReplaySettings.isReplayActive()
                + "  replayGameContext=" + GameContext.isHypixelReplayWatching(mc)
                + "  replayCombined=" + isReplayContext());
        sendDebugMessage(EnumChatFormatting.GRAY + "Config: mode=" + getNametagMode()
                + "  killer=" + SpectatorKillerNametagOverlay.isEnabled()
                + "  applyReplay=" + isApplySettingsToReplaysEnabled()
                + "  forceReplayHealth=" + GomodFixes.isForceReplayPlayerHealthTagsEnabled());
        sendDebugMessage(EnumChatFormatting.GRAY + "Lunar hook: " + readLunarNametagHookPatchedReturns());
        sendDebugMessage(EnumChatFormatting.GRAY + "Lunar event: "
                + LunarNametagCompatibility.buildDebugSummary());
        sendDebugMessage(EnumChatFormatting.GRAY + "Lunar final labels: "
                + LunarDisguiseDisplayCompatibility.describeVisibleSpecsNativeLabelDebug());

        EntityPlayer lp = mc.thePlayer;
        String localName = lp.getName();
        boolean localKcDead = KillCounter.isPlayerDead(localName);
        sendDebugMessage(EnumChatFormatting.GRAY + "YOU: " + EnumChatFormatting.WHITE + localName
                + EnumChatFormatting.GRAY + "  vanillaSpectator=" + lp.isSpectator()
                + "  killCounterDead=" + localKcDead
                + "  tabElimSpectatorSignals=" + KillCounter.isLocalBlitzEliminatedOrSpectatingTabSignals(mc)
                + "  firstPersonArmOpacity=" + shouldApplyToLocalFirstPersonArm());
        appendNametagLayoutDebug(lp, "YOU");
        sendDebugMessage(EnumChatFormatting.DARK_GRAY + "  Lunar native row Y: "
                + LunarDisguiseDisplayCompatibility.describeVisibleSpecsNativeRowYDebug(localName));

        if (mc.theWorld != null) {
            int shown = 0;
            for (EntityPlayer p : mc.theWorld.playerEntities) {
                if (p == lp) {
                    continue;
                }
                if (shown++ >= 10) {
                    break;
                }
                NetworkPlayerInfo debugInfo = null;
                String debugGameType = "<none>";
                String debugTeam = "<none>";
                String debugTeamFormatted = "<none>";
                try {
                    if (mc.getNetHandler() != null) {
                        debugInfo = mc.getNetHandler().getPlayerInfo(p.getGameProfile().getId());
                    }
                    if (debugInfo != null) {
                        debugGameType = String.valueOf(debugInfo.getGameType());
                        if (debugInfo.getPlayerTeam() != null) {
                            debugTeam = debugInfo.getPlayerTeam().getRegisteredName();
                            debugTeamFormatted = ScorePlayerTeam.formatPlayerName(
                                    debugInfo.getPlayerTeam(), p.getName());
                        }
                    }
                } catch (Throwable ignored) {
                }
                String rawReplayKiller = KillCounter.getReplayRecordedKillerForVictim(p.getName());
                String resolvedKiller = KillCounter.getKillerForVictim(p.getName());
                boolean replayTarget = isReplaySpectatorSettingsTarget(p);
                sendDebugMessage(EnumChatFormatting.WHITE + p.getName()
                        + EnumChatFormatting.GRAY + " showRemote=" + shouldShowPlayerRemote(p)
                        + " kcDead=" + KillCounter.isPlayerDead(p.getName())
                        + " replayDead=" + KillCounter.isReplayRecordedDeadPlayer(p.getName())
                        + " rawInvis=" + isEntityRawInvisible(p)
                        + " potInvis=" + p.isPotionActive(Potion.invisibility)
                        + " tabSpectator=" + isPlayerInSpectatorGamemode(p)
                        + " replayTarget=" + replayTarget
                        + " replayInvisWorld=" + isReplayInvisibleWorldPlayer(p)
                        + " tabDeadSpec=" + KillCounter.isPlayerDeadOrSpectatorFromTab(p.getName())
                        + " suppressName=" + shouldSuppressNametag(p)
                        + " suppressHealth=" + shouldSuppressHealthNametag(p)
                        + " applyOpacity=" + shouldApplyOpacityToEntity(p)
                        + " replayStack=" + ReplayPlayerHealthTagFix.shouldRenderCompleteReplayStack(p)
                        + " replayStackPlan={" + ReplayPlayerHealthTagFix.describeReplayStackPlan(p) + "}");
                appendNametagLayoutDebug(p, "  layout");
                sendDebugMessage(EnumChatFormatting.DARK_GRAY + "  Lunar native row Y: "
                        + LunarDisguiseDisplayCompatibility.describeVisibleSpecsNativeRowYDebug(p.getName()));
                sendDebugMessage(EnumChatFormatting.DARK_GRAY + "  team=" + debugTeam
                        + " gameType=" + debugGameType
                        + " teamFmt=" + debugTeamFormatted
                        + " rawKiller=" + String.valueOf(rawReplayKiller)
                        + " resolvedKiller=" + String.valueOf(resolvedKiller)
                        + " killerOverlay=" + SpectatorKillerNametagOverlay.shouldShowFor(p)
                        + " killerLabel=" + String.valueOf(SpectatorKillerNametagOverlay.buildLabel(p))
                        + " lunarEvent={" + LunarNametagCompatibility.getNativeEventDebugForPlayer(p.getName()) + "}"
                        + " renderLabel={" + String.valueOf(getNametagRenderDebug(p.getName())) + "}");
            }
            int others = mc.theWorld.playerEntities.size() - 1;
            if (others > 10) {
                sendDebugMessage(EnumChatFormatting.DARK_GRAY + "(first 10 other player entities in world; total others=" + others + ")");
            } else if (others == 0) {
                sendDebugMessage(EnumChatFormatting.DARK_GRAY + "(no other player entities loaded — server may not spawn spec bodies)");
            }
        } else {
            sendDebugMessage(EnumChatFormatting.RED + "theWorld=null");
        }

        sendDebugMessage(EnumChatFormatting.DARK_GRAY + "Tip: if killCounterDead=false for you when spectating, death chat/TAB sync did not mark you — try /gomodlag tab");
        if (includeKillCounterTabDump) {
            sendDebugMessage(EnumChatFormatting.DARK_GRAY + "--- KillCounter.dumpTabListDebugToChat (full) ---");
            KillCounter.dumpTabListDebugToChat();
        }
        } finally {
            visibleSpecsDebugCapture.remove();
            saveVisibleSpecsDebugLog(capture);
        }
    }

    private static void sendDebugMessage(String message) {
        sendMessage(message);
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(message);
        String output = plain == null ? message : plain;
        StringBuilder capture = visibleSpecsDebugCapture.get();
        if (capture != null) {
            capture.append(output).append('\n');
        }
        System.out.println("[gomod][VisibleSpecsDebug] " + output);
    }

    /** Add the camera distance and vanilla's close-range health-row decision to the saved debug snapshot. */
    private static void appendNametagLayoutDebug(EntityPlayer player, String prefix) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity camera = mc == null ? null : mc.getRenderViewEntity();
        double distanceSq = player == null || camera == null ? -1.0D : player.getDistanceSqToEntity(camera);
        boolean closeHealthRow = player instanceof AbstractClientPlayer
                && VanillaPlayerNametagLayout.wouldRenderTabHealthNametagRow(
                (AbstractClientPlayer) player, distanceSq);
        boolean target = player != null && shouldSuppressHealthNametag(player);
        boolean nativeCorrectionExpected = NAMETAG_MODE_ONLY_IGN.equals(getNametagMode())
                && isLiveSpectatorSettingsRuntimeActive() && target && closeHealthRow;
        sendDebugMessage(EnumChatFormatting.DARK_GRAY + prefix
                + " camera=" + (camera == null ? "<none>" : camera.getName())
                + " distanceSq=" + String.format(Locale.ROOT, "%.2f", distanceSq)
                + " vanillaCloseHealthRow=" + closeHealthRow
                + " onlyIgnTarget=" + target
                + " lunarNameYCorrectionExpected=" + nativeCorrectionExpected);
    }

    private static void saveVisibleSpecsDebugLog(StringBuilder capture) {
        try {
            File directory = new File(GomodPaths.getGomod123Dir(), "debugall");
            Files.createDirectories(directory.toPath());
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date());
            File output = File.createTempFile("visible_specs_" + stamp + "_", ".log", directory);
            Files.write(output.toPath(), capture.toString().getBytes(StandardCharsets.UTF_8));
            sendMessage(CHAT_PREFIX + EnumChatFormatting.GREEN + "Visible Specs debug saved: "
                    + EnumChatFormatting.WHITE + output.getAbsolutePath());
        } catch (Throwable error) {
            sendMessage(CHAT_PREFIX + EnumChatFormatting.RED + "Could not save Visible Specs debug: "
                    + EnumChatFormatting.WHITE + error.getClass().getSimpleName() + ": "
                    + String.valueOf(error.getMessage()));
        }
    }

    private static String readLunarNametagHookPatchedReturns() {
        try {
            Class<?> hook = Class.forName("me.napim.gomod.weave.hooks.LunarHypixelNametagWeaveHook", false,
                    VisibleSpecs.class.getClassLoader());
            String returns = String.valueOf(hook.getField("lastPatchedReturns").get(null)) + " patched returns";
            try {
                returns += ", Level Head row filter="
                        + String.valueOf(hook.getField("lastLevelHeadRowFilterHooked").get(null));
            } catch (NoSuchFieldException ignored) {
                // Older internal builds do not expose the Level Head row filter status.
            }
            return returns;
        } catch (Throwable error) {
            return "unavailable (" + error.getClass().getSimpleName() + ")";
        }
    }

    /**
     * Called every client tick from ClientTickHub.  Scans world players and reports
     * detected invisible/spectator entities in chat with their coordinates.
     */
    public static void clientTick() {
        if (!spectatorBypassAllInvisible) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        boolean gameActive = isBlitzSpectatorSettingsPhase(mc);
        // Reset reported set when a new game starts
        if (gameActive && !wasGameActive) {
            reportedThisSession.clear();
        }
        wasGameActive = gameActive;
        if (!gameActive) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastScanMs < SCAN_INTERVAL_MS) {
            return;
        }
        lastScanMs = now;

        int found = 0;
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer) continue;
            try {
                boolean rawInvis = isEntityRawInvisible(player);
                boolean potionInvis = player.isPotionActive(Potion.invisibility);
                boolean specGamemode = isPlayerInSpectatorGamemode(player);
                boolean dead = KillCounter.isPlayerDead(player.getName());
                if (!rawInvis && !potionInvis && !specGamemode && !dead) {
                    continue;
                }
                found++;
                String name = player.getName();
                // Only report new detections to avoid spam
                if (reportedThisSession.contains(name)) {
                    continue;
                }
                reportedThisSession.add(name);

                int px = MathHelper.floor_double(player.posX);
                int py = MathHelper.floor_double(player.posY);
                int pz = MathHelper.floor_double(player.posZ);
                String reason;
                if (specGamemode) reason = "SPECTATOR gamemode";
                else if (dead) reason = "KillCounter dead";
                else if (rawInvis && potionInvis) reason = "invis flag + potion";
                else if (rawInvis) reason = "invis flag";
                else reason = "invis potion";

                sendMessage(CHAT_PREFIX + EnumChatFormatting.LIGHT_PURPLE + "[SpecBypass] "
                        + EnumChatFormatting.WHITE + name
                        + EnumChatFormatting.GRAY + " detected at "
                        + EnumChatFormatting.YELLOW + px + " " + py + " " + pz
                        + EnumChatFormatting.DARK_GRAY + " (" + reason + ")");
            } catch (Throwable ignored) {
            }
        }

        // Periodic summary of how many invisible entities are loaded
        if (found > 0 || (now - lastScanMs < SCAN_INTERVAL_MS + 100)) {
            // Print total count every scan cycle for awareness
            // (only when there are any, to avoid noise)
        }
    }

    /**
     * Legacy name-only check (KillCounter dead list + visible-specs toggle).
     */
    public static boolean shouldShowPlayer(String playerName) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!isSpectatorSettingsRuntimeActive(mc) || playerName == null || playerName.isEmpty()) {
            return false;
        }
        if (!shouldApplySettingsInCurrentReplay()) {
            return false;
        }
        if (SpectatorRelay.isAliveViewer()) {
            return KillCounter.isPlayerDeadOrSpectatorFromTab(playerName);
        }
        if (KillCounter.shouldSuppressDeadSpectatorsForLiveLocal(mc)) {
            return false;
        }
        return KillCounter.isPlayerDead(playerName);
    }

    /**
     * Read the invisible flag directly from the entity data watcher (byte 0, bit 5)
     * instead of calling {@link EntityPlayer#isInvisible()}, which is hooked by our
     * {@code EntityPlayerInvisibilityMixin} and would cause infinite recursion.
     */
    private static boolean isEntityRawInvisible(EntityPlayer player) {
        try {
            byte flags = player.getDataWatcher().getWatchableObjectByte(0);
            return (flags & (1 << 5)) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Check if a remote player is in spectator gamemode via the tab list.
     * Hypixel may use vanilla spectator mode for dead players in Blitz.
     */
    private static boolean isPlayerInSpectatorGamemode(EntityPlayer player) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.getNetHandler() == null) return false;
            NetworkPlayerInfo info =
                    mc.getNetHandler().getPlayerInfo(player.getGameProfile().getId());
            return info != null && info.getGameType() == WorldSettings.GameType.SPECTATOR;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Replay reuses invisible/spectator player entities for spectator bodies. Keep ReplaySettings
     * as replay detection only; the VisibleSpecs toggle, opacity slider and nametag mode decide
     * whether those entities are shown and how they are rendered.
     */
    public static boolean isReplaySpectatorSettingsTarget(EntityPlayer player) {
        if (!isSpectatorSettingsRuntimeActive() || player == null || !isReplayContext()
                || !isApplySettingsToReplaysEnabled()) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || player == mc.thePlayer) {
            return false;
        }
        try {
            // Match /allplayers' useful replay signal: a loaded world player rendered as invisible.
            // Do not call player.isInvisible() here because EntityPlayerInvisibilityMixin hooks it and calls back
            // into this class; read the raw entity flag directly instead.
            return isReplayInvisibleWorldPlayer(player);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isReplayInvisibleWorldPlayer(EntityPlayer player) {
        if (player == null || !isEntityRawInvisible(player)) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) {
            return false;
        }
        try {
            if (mc.theWorld.playerEntities != null && mc.theWorld.playerEntities.contains(player)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            return mc.theWorld.getLoadedEntityList() != null
                    && mc.theWorld.getLoadedEntityList().contains(player);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Full visibility check for player entities (used from render/invis mixins).
     * Includes the local player when eliminated: Hypixel keeps invis (+ often night vision) on
     * spectators, so we must override invisibility for {@code mc.thePlayer} the same way as for
     * remotes, otherwise {@link #shouldApplyOpacityToEntity} never gets a visible pass to tint.
     * <p>
     * <b>Important:</b> This method must NOT call {@code subject.isInvisible()} because the
     * {@code EntityPlayerInvisibilityMixin} that hooks {@code isInvisible()} calls this method,
     * which would cause infinite recursion &amp; StackOverflowError.  Instead we read the
     * invisible flag directly from the data watcher via {@link #isEntityRawInvisible}.
     */
    public static boolean shouldShowPlayerRemote(EntityPlayer subject) {
        if (subject == null) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return false;
        }
        if (!shouldApplySettingsInCurrentReplay()) {
            return false;
        }
        boolean runtimeActive = isSpectatorSettingsRuntimeActive(mc);
        if (!runtimeActive) {
            return false;
        }
        if (subject == mc.thePlayer) {
            return !SpectatorRelay.isAliveViewer();
        }
        if (SpectatorRelay.isAliveViewer()) {
            // Never let the spectator-only invisible-player bypass reveal a living opponent.
            return SpectatorRelay.isConfirmedSpectator(subject);
        }
        if (KillCounter.shouldSuppressDeadSpectatorsForLiveLocal(mc)
                && isLiveDeadOrSpectatorTarget(mc, subject)) {
            return false;
        }
        if (KillCounter.isPlayerDead(subject.getName())) {
            return true;
        }
        if (isReplaySpectatorSettingsTarget(subject)) {
            return true;
        }
        if (isTabOrGamemodeSpectatorTarget(mc, subject)) {
            return true;
        }

        if (!spectatorBypassAllInvisible) {
            return false;
        }
        if (!isBlitzSpectatorSettingsPhase(mc)) {
            return false;
        }

        try {
            // Use raw data-watcher read and potion map check to avoid triggering our own
            // isInvisible() mixin (which calls back here → StackOverflow).
            // Also check tab-list gamemode for vanilla spectator mode.
            return isEntityRawInvisible(subject)
                    || subject.isPotionActive(Potion.invisibility)
                    || isPlayerInSpectatorGamemode(subject);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean shouldApplyToLocalFirstPersonArm() {
        if (!isSpectatorSettingsRuntimeActive() || getRenderOpacity() >= 0.99f) {
            return false;
        }
        if (!shouldApplySettingsInCurrentReplay()) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return false;
        }
        return isLocalEligibleForVisibleSpecsOpacity(mc);
    }

    public static void beginFirstPersonArm() {
        firstPersonArmActive = true;
        EntityOpacityGlintBlendTracker.resetGlintBlendDepth();
    }

    public static void endFirstPersonArm() {
        firstPersonArmActive = false;
    }

    /** Used by {@link EntityOpacityGlintBlendTracker}; FP arm path does not cancel {@code disableBlend}. */
    public static boolean isFirstPersonOpacityArmActive() {
        return firstPersonArmActive;
    }

    /** True inside {@code RenderManager.renderEntitySimple} hooks for VisibleSpecs deferred opacity. */
    public static boolean isOpacityGlHooksActive() {
        return glHooksDepth > 0;
    }

    /** True while VisibleSpecs is replaying a deferred spectator-opacity entity draw. */
    public static boolean isDeferredOpacityFlushActive() {
        return flushingDeferred;
    }

    /** True during the depth-only half of a deferred spectator-opacity entity draw. */
    public static boolean isDeferredOpacityPrepassActive() {
        return flushingDeferred && renderPhase.get() == VisibleSpecsRenderPhase.PREPASS;
    }

    public static void renderNameplateWithoutOpacityScaling(Runnable draw) {
        if (draw == null) {
            return;
        }
        int previous = unscaledNameplateDepth.get();
        unscaledNameplateDepth.set(previous + 1);
        try {
            draw.run();
        } finally {
            if (previous == 0) {
                unscaledNameplateDepth.remove();
            } else {
                unscaledNameplateDepth.set(previous);
            }
        }
    }

    public static boolean isRenderingNameplateWithoutOpacityScaling() {
        return unscaledNameplateDepth.get() > 0;
    }

    public static boolean isFlushingDeferredNametags() {
        return flushingDeferredNametags;
    }

    public static boolean shouldSuppressPlayerNametagForBlindness(Entity entity, double x, double y, double z) {
        // Do not hard-cancel player names at the blindness fog radius. Native world fog
        // should fade them; the old 5.5-block cutoff made every farther player label vanish.
        return false;
    }

    public static boolean isBlindnessNametagLimitActive() {
        return currentBlindnessFogEndBlocks() > 0.0D;
    }

    public static boolean isWaterNametagLimitActive() {
        try {
            return WaterClarity.isViewEntitySubmerged(Minecraft.getMinecraft());
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean shouldAllowNametagThroughBlocksNow() {
        return ConfigManager.isNametagThroughBlocksEnabled()
                && !isBlindnessNametagLimitActive()
                && !isWaterNametagLimitActive();
    }

    public static boolean shouldAllowNametagThroughBlocksFor(Entity entity, double x, double y, double z) {
        return shouldAllowNametagThroughBlocksFor(entity, x, y, z, 0.0D);
    }

    public static boolean shouldAllowNametagThroughBlocksFor(Entity entity, double x, double y, double z,
            double footYExtra) {
        return shouldAllowNametagThroughBlocksNow()
                && !isWaterBetweenViewAndNametag(entity, x, y, z, footYExtra)
                && !isFullyOpaqueGlassBetweenViewAndNametag(entity, x, y, z, footYExtra);
    }

    private static boolean isFullyOpaqueGlassBetweenViewAndNametag(Entity entity, double x, double y, double z,
            double footYExtra) {
        if (entity == null || ConfigManager.getBlocksFinderWorldOpacity() < 100) {
            return false;
        }
        double labelY = y + entity.height + 0.5D + footYExtra;
        long key = nametagWaterCacheKey(entity, x, labelY, z);
        Boolean cached = nametagGlassOcclusionCache.get(Long.valueOf(key));
        if (cached != null) {
            return cached.booleanValue();
        }
        long profile = RenderProfiler.begin("NametagOcclusion.glassRayTrace");
        boolean result = false;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            RenderManager rm = mc == null ? null : mc.getRenderManager();
            if (mc != null && mc.theWorld != null && rm != null) {
                Vec3 start = new Vec3(rm.viewerPosX, rm.viewerPosY, rm.viewerPosZ);
                Vec3 end = new Vec3(rm.viewerPosX + x, rm.viewerPosY + labelY, rm.viewerPosZ + z);
                MovingObjectPosition hit = mc.theWorld.rayTraceBlocks(start, end, false, true, false);
                if (hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                        && hit.getBlockPos() != null) {
                    net.minecraft.block.Block block = mc.theWorld.getBlockState(hit.getBlockPos()).getBlock();
                    result = block == Blocks.glass || block == Blocks.stained_glass
                            || block == Blocks.glass_pane || block == Blocks.stained_glass_pane;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            RenderProfiler.end("NametagOcclusion.glassRayTrace", profile);
        }
        if (nametagGlassOcclusionCache.size() > NAMETAG_WATER_CACHE_MAX_ENTRIES) {
            nametagGlassOcclusionCache.clear();
        }
        nametagGlassOcclusionCache.put(Long.valueOf(key), Boolean.valueOf(result));
        return result;
    }

    public static boolean isWaterBetweenViewAndNametag(Entity entity, double x, double y, double z) {
        return isWaterBetweenViewAndNametag(entity, x, y, z, 0.0D);
    }

    public static boolean isWaterBetweenViewAndNametag(Entity entity, double x, double y, double z,
            double footYExtra) {
        if (entity == null) {
            return false;
        }
        double labelY = y + entity.height + 0.5D + footYExtra;
        long key = nametagWaterCacheKey(entity, x, labelY, z);
        Boolean cached = nametagWaterOcclusionCache.get(Long.valueOf(key));
        if (cached != null) {
            return cached.booleanValue();
        }
        long profile = RenderProfiler.begin("NametagOcclusion.waterSegment");
        boolean result;
        try {
            result = isWaterBetweenViewAndRelativePoint(x, labelY, z);
        } finally {
            RenderProfiler.end("NametagOcclusion.waterSegment", profile);
        }
        if (nametagWaterOcclusionCache.size() > NAMETAG_WATER_CACHE_MAX_ENTRIES) {
            nametagWaterOcclusionCache.clear();
        }
        nametagWaterOcclusionCache.put(Long.valueOf(key), Boolean.valueOf(result));
        return result;
    }

    /** Water test for render hooks that already receive the final nametag anchor instead of entity-foot Y. */
    public static boolean isWaterBetweenViewAndLabelPoint(double x, double labelY, double z) {
        return isWaterBetweenViewAndRelativePoint(x, labelY, z);
    }

    /** Water test for a camera-space ray whose start was resolved from the active rendered camera. */
    public static boolean isWaterAlongSegmentForNametag(Vec3 start, Vec3 end) {
        if (start == null || end == null) return false;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            return isWaterAlongSegment(mc, start.xCoord, start.yCoord, start.zCoord,
                    end.xCoord, end.yCoord, end.zCoord);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static long nametagWaterCacheKey(Entity entity, double x, double labelY, double z) {
        long key = 1469598103934665603L;
        key = mixNametagWaterCacheKey(key, entity.getEntityId());
        key = mixNametagWaterCacheKey(key, quantizeNametagWaterCoord(x));
        key = mixNametagWaterCacheKey(key, quantizeNametagWaterCoord(labelY));
        key = mixNametagWaterCacheKey(key, quantizeNametagWaterCoord(z));
        return key;
    }

    private static int quantizeNametagWaterCoord(double value) {
        return MathHelper.floor_double(value * 4.0D);
    }

    private static long mixNametagWaterCacheKey(long key, int value) {
        key ^= ((long) value) & 0xFFFFFFFFL;
        return key * 1099511628211L;
    }

    private static boolean isWaterBetweenViewAndRelativePoint(double x, double y, double z) {
        if (!isFiniteDouble(x) || !isFiniteDouble(y) || !isFiniteDouble(z)) {
            return false;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null || mc.getRenderManager() == null) {
                return false;
            }
            RenderManager rm = mc.getRenderManager();
            return isWaterAlongSegment(mc, rm.viewerPosX, rm.viewerPosY, rm.viewerPosZ,
                    rm.viewerPosX + x, rm.viewerPosY + y, rm.viewerPosZ + z);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isWaterAlongSegment(Minecraft mc, double sx, double sy, double sz,
            double ex, double ey, double ez) {
        if (mc == null || mc.theWorld == null) {
            return false;
        }
        double dx = ex - sx;
        double dy = ey - sy;
        double dz = ez - sz;
        if (!isFiniteDouble(dx) || !isFiniteDouble(dy) || !isFiniteDouble(dz)) {
            return false;
        }
        double longestAxis = Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz));
        if (longestAxis < 0.001D) {
            return false;
        }
        int steps = Math.max(1, Math.min(128, (int) Math.ceil(longestAxis * 2.0D)));
        if (steps == 1) return false;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 1; i < steps; i++) {
            double t = (double) i / (double) steps;
            int x = MathHelper.floor_double(sx + dx * t);
            int y = MathHelper.floor_double(sy + dy * t);
            int z = MathHelper.floor_double(sz + dz * t);
            // Sub-block samples can land in the same voxel. Query each run of identical samples once.
            if (i > 1 && pos.getX() == x && pos.getY() == y && pos.getZ() == z) continue;
            pos.set(x, y, z);
            try {
                if (mc.theWorld.getBlockState(pos).getBlock().getMaterial() == Material.water) {
                    return true;
                }
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    private static boolean isFiniteDouble(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static double currentBlindnessFogEndBlocks() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                return 0.0D;
            }
            Entity view = mc.getRenderViewEntity();
            if (!(view instanceof EntityLivingBase)) {
                return 0.0D;
            }
            EntityLivingBase living = (EntityLivingBase) view;
            if (!living.isPotionActive(Potion.blindness)) {
                return 0.0D;
            }
            PotionEffect effect = living.getActivePotionEffect(Potion.blindness);
            int duration = effect == null ? 20 : effect.getDuration();
            if (duration >= 20) {
                return BLINDNESS_FOG_END_BLOCKS;
            }
            double farPlane = vanillaFarPlaneBlocks(mc);
            double fade = 1.0D - ((double) duration / 20.0D);
            return BLINDNESS_FOG_END_BLOCKS + (farPlane - BLINDNESS_FOG_END_BLOCKS) * fade;
        } catch (Throwable ignored) {
            return 0.0D;
        }
    }

    private static double vanillaFarPlaneBlocks(Minecraft mc) {
        try {
            if (mc != null && mc.gameSettings != null) {
                return Math.max(16.0D, (double) mc.gameSettings.renderDistanceChunks * 16.0D);
            }
        } catch (Throwable ignored) {
        }
        return 128.0D;
    }

    public static boolean shouldApplyOpacityToEntity(Entity entity) {
        if (!(entity instanceof EntityPlayer)) {
            return false;
        }
        if (me.napim.gomod.gui.MountPreviewHelper.shouldBypassWorldOpacityForGuiPreview(entity)) {
            return false;
        }
        if (getRenderOpacity() >= 0.99f || !isSpectatorSettingsRuntimeActive()) {
            return false;
        }
        if (!shouldApplySettingsInCurrentReplay()) {
            return false;
        }
        EntityPlayer p = (EntityPlayer) entity;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && SpectatorRelay.isAliveViewer() && p != mc.thePlayer && SpectatorRelay.isConfirmedSpectator(p)) {
            return true;
        }
        if (mc != null && p == mc.thePlayer) {
            // The inventory mannequin is excluded by its short draw scope. Merely having GuiInventory open must not
            // disable spectator opacity on the real third-person world player rendered behind the screen.
            if (guiInventoryRenderDepth > 0) {
                return false;
            }
            return isLocalEligibleForVisibleSpecsOpacity(mc);
        }
        if (KillCounter.shouldSuppressDeadSpectatorsForLiveLocal(mc)
                && isLiveDeadOrSpectatorTarget(mc, p)) {
            return false;
        }
        if (KillCounter.isPlayerDead(p.getName()) || isReplaySpectatorSettingsTarget(p)) {
            return true;
        }
        return isTabOrGamemodeSpectatorTarget(mc, p);
    }

    private static boolean isLiveDeadOrSpectatorTarget(Minecraft mc, EntityPlayer player) {
        return mc != null
                && player != null
                && isSpectatorSettingsRuntimeActive(mc)
                && (KillCounter.isPlayerDead(player.getName())
                    || isTabOrGamemodeSpectatorTarget(mc, player));
    }

    private static boolean isTabOrGamemodeSpectatorTarget(Minecraft mc, EntityPlayer player) {
        return mc != null
                && player != null
                && isSpectatorSettingsRuntimeActive(mc)
                && (KillCounter.isPlayerDeadOrSpectatorFromTab(player.getName())
                    || isPlayerInSpectatorGamemode(player));
    }

    public static boolean shouldSuppressNametag(Entity entity) {
        if (!NAMETAG_MODE_OFF.equals(getNametagMode())) {
            return false;
        }
        return isVisibleSpecsNametagTarget(entity);
    }

    public static boolean shouldSuppressHealthNametag(Entity entity) {
        String mode = getNametagMode();
        if (!NAMETAG_MODE_ONLY_IGN.equals(mode) && !NAMETAG_MODE_OFF.equals(mode)) {
            return false;
        }
        return isVisibleSpecsNametagTarget(entity);
    }

    /** ONLY_IGN also hides player-owned rows Lunar renders outside vanilla's health-label call. */
    public static boolean shouldSuppressAdditionalNametagRow(Entity entity) {
        return NAMETAG_MODE_ONLY_IGN.equals(getNametagMode())
                && isVisibleSpecsNametagTarget(entity);
    }

    /** Last Render#renderLivingLabel decision per spectator target, included in /visiblespecsdebug. */
    public static void recordNametagRenderDebug(EntityPlayer player, String label, boolean primaryName,
            boolean healthLabel, boolean suppressed, String reason) {
        if (player == null || !isVisibleSpecsNametagTarget(player)) {
            return;
        }
        String cleanLabel = label == null ? "<null>"
                : String.valueOf(EnumChatFormatting.getTextWithoutFormattingCodes(label));
        if (cleanLabel.length() > 100) {
            cleanLabel = cleanLabel.substring(0, 100) + "…";
        }
        String line = "label=\"" + cleanLabel.replace('\r', ' ').replace('\n', ' ') + "\""
                + " primary=" + primaryName + " health=" + healthLabel
                + " mode=" + getNametagMode() + " suppressed=" + suppressed
                + " reason=" + String.valueOf(reason);
        synchronized (nametagRenderDebug) {
            nametagRenderDebug.put(player.getName(), line);
            while (nametagRenderDebug.size() > 40) {
                String oldest = nametagRenderDebug.keySet().iterator().next();
                nametagRenderDebug.remove(oldest);
            }
        }
    }

    private static String getNametagRenderDebug(String playerName) {
        synchronized (nametagRenderDebug) {
            return nametagRenderDebug.get(playerName);
        }
    }

    /**
     * Close range with tab-health layout: vanilla bumps {@code y} before the IGN even when we skip drawing hearts
     * ({@link #NAMETAG_MODE_ONLY_IGN}). In that case the IGN should sit on the hearts-slot foot {@code y}, not one row
     * above the invisible health line.
     */
    public static boolean shouldPlaceIgnAtTabHealthSlotFootY(Entity entity, double distanceSq) {
        if (!(entity instanceof AbstractClientPlayer)) {
            return false;
        }
        return shouldSuppressHealthNametag(entity)
                && VanillaPlayerNametagLayout.wouldRenderTabHealthNametagRow((AbstractClientPlayer) entity, distanceSq);
    }

    /**
     * Undo vanilla's close-range {@code y += lineSpacing} when hearts are suppressed so the main IGN uses the lower slot.
     */
    public static double adjustMainNameFootYForOnlyIgnMode(Render render, Entity entity,
            double footYAfterVanillaTabHealthStep, float labelScale, double distanceSq) {
        if (render == null || !shouldPlaceIgnAtTabHealthSlotFootY(entity, distanceSq)) {
            return footYAfterVanillaTabHealthStep;
        }
        return footYAfterVanillaTabHealthStep
                - VanillaPlayerNametagLayout.lineSpacingWorldY(render, labelScale);
    }

    public static boolean shouldDeferNametagForOpacity(Entity entity) {
        return flushingDeferred
                && !flushingDeferredNametags
                && renderPhase.get() == VisibleSpecsRenderPhase.COLOR
                && !shouldSuppressNametag(entity)
                && (!(entity instanceof EntityLivingBase)
                    || !ReplayPlayerHealthTagFix.shouldRenderCompleteReplayStack((EntityLivingBase) entity))
                && isVisibleSpecsNametagTarget(entity);
    }

    public static boolean shouldDeferReplayNameplateStackForOpacity(Entity entity) {
        return flushingDeferred
                && !flushingDeferredNametags
                && renderPhase.get() == VisibleSpecsRenderPhase.COLOR
                && entity instanceof EntityPlayer
                && ReplayPlayerHealthTagFix.shouldRenderCompleteReplayStack((EntityLivingBase) entity);
    }

    public static void scheduleDeferredNametag(RendererLivingEntity renderer, EntityLivingBase entity,
                                               double x, double y, double z) {
        if (renderer == null || entity == null || !shouldDeferNametagForOpacity(entity)) {
            return;
        }
        if (deferredPlayerOffsetNametagEntityIds.contains(entity.getEntityId())) {
            return;
        }
        deferredNametags.add(new DeferredVisibleSpecsNametag(renderer, entity, x, y, z));
    }

    public static void scheduleDeferredPlayerOffsetNametag(RenderPlayer renderer, AbstractClientPlayer player,
                                                           double x, double y, double z, String label,
                                                           float labelScale, double distanceSq) {
        if (renderer == null || player == null || !shouldDeferNametagForOpacity(player)) {
            return;
        }
        deferredPlayerOffsetNametags.add(new DeferredPlayerOffsetNametag(
                renderer, player, x, y, z, label, labelScale, distanceSq));
        deferredPlayerOffsetNametagEntityIds.add(player.getEntityId());
    }

    public static void scheduleDeferredReplayNameplateStack(RendererLivingEntity renderer, EntityLivingBase entity,
                                                           double x, double y, double z) {
        if (renderer == null || entity == null) {
            return;
        }
        if (isDeferredOpacityFlushActive()) {
            if (isDeferredOpacityPrepassActive()) {
                return;
            }
            deferredReplayNameplates.add(new DeferredReplayNameplateStack(renderer, entity, x, y, z));
            return;
        }
        if (!shouldDeferReplayNameplateStackForOpacity(entity)) {
            return;
        }
        deferredReplayNameplates.add(new DeferredReplayNameplateStack(renderer, entity, x, y, z));
    }

    public static boolean shouldShowReplayHealthNametag(Entity entity) {
        if (!(entity instanceof EntityPlayer)
                || !GomodFixes.isForceReplayPlayerHealthTagsEnabled() || !isReplayContext()) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || entity == mc.thePlayer) {
            return false;
        }
        try {
            if (entity instanceof EntityPlayer
                    && me.napim.gomod.gui.MountPreviewHelper.isVisibleSpecsPreviewPlayer((EntityPlayer) entity)) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        // World settings must remain active while chat or a non-pausing GUI is open; otherwise Lunar's own
        // nameplate flashes back on and menu changes cannot be previewed in real time.
        return true;
    }

    public static String buildReplayHealthNametag(EntityLivingBase entity) {
        if (!shouldShowReplayHealthNametag(entity)) {
            return null;
        }
        int health = Math.max(0, (int) Math.ceil(entity.getHealth()));
        return EnumChatFormatting.WHITE.toString() + health
                + EnumChatFormatting.RED + "\u2764" + EnumChatFormatting.RESET;
    }

    private static boolean isVisibleSpecsNametagTarget(Entity entity) {
        if (!(entity instanceof EntityPlayer)) {
            return false;
        }
        EntityPlayer player = (EntityPlayer) entity;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && player == mc.thePlayer) {
            return isLocalEligibleForVisibleSpecsOpacity(mc);
        }
        return shouldShowPlayerRemote(player);
    }

    /**
     * Local player: dead list, TAB eliminated/spectator styling (KillCounter), or Hypixel ghost spectator(invis+NV).
     */
    public static boolean isLocalEligibleForVisibleSpecsOpacity(Minecraft mc) {
        if (mc == null || mc.thePlayer == null) {
            return false;
        }
        if (isReplayContext() && isApplySettingsToReplaysEnabled()) {
            return true;
        }
        String name = mc.thePlayer.getName();
        if (KillCounter.isPlayerDead(name)) {
            return true;
        }
        if (KillCounter.isLocalBlitzEliminatedOrSpectatingTabSignals(mc)) {
            return true;
        }
        if (hasBlitzSpectatorHotbar(mc)) {
            return true;
        }
        return isLocalProbablyBlitzSpectator(mc);
    }

    /** True only for a non-local dead/spectator target in the live Blitz spectator-settings context. */
    public static boolean isLiveSpectatorSettingsTarget(EntityPlayer player) {
        if (player == null || isReplayContext()) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.thePlayer != null && player != mc.thePlayer
                && isLiveDeadOrSpectatorTarget(mc, player);
    }

    /** Cheap cached gate for native nametag callbacks; excludes replays before resolving the event entity. */
    public static boolean isLiveSpectatorSettingsRuntimeActive() {
        return !isReplayContext() && isSpectatorSettingsRuntimeActive();
    }

    /** Hypixel's post-death sidebar can still look live; these two server-issued tools are the stable state signal. */
    private static boolean hasBlitzSpectatorHotbar(Minecraft mc) {
        try {
            if (mc == null || mc.thePlayer == null || !isBlitzSpectatorSettingsPhase(mc)) {
                return false;
            }
            boolean teleporter = false;
            boolean settings = false;
            for (net.minecraft.item.ItemStack stack : mc.thePlayer.inventory.mainInventory) {
                if (stack == null || !stack.hasDisplayName()) {
                    continue;
                }
                String name = EnumChatFormatting.getTextWithoutFormattingCodes(stack.getDisplayName());
                if (name == null) {
                    continue;
                }
                String lower = name.toLowerCase(Locale.ROOT);
                teleporter |= lower.contains("teleporter") && lower.contains("right click");
                settings |= lower.contains("spectator settings") && lower.contains("right click");
            }
            return teleporter && settings;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Lunar can suppress the vanilla name pass after the local player dies; restore it only in spectator runtime. */
    public static boolean shouldForcePlayerNametagForLocalSpectator(EntityPlayer player) {
        if (player == null) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || player == mc.thePlayer
                || !isSpectatorSettingsRuntimeActive(mc)) {
            return false;
        }
        try {
            return mc.theWorld != null && mc.theWorld.playerEntities.contains(player);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isLocalProbablyBlitzSpectator(Minecraft mc) {
        try {
            if (mc == null || mc.thePlayer == null) {
                return false;
            }
            // Keep this narrow: only during active Blitz on Hypixel, and require BOTH invis + night vision
            // (Hypixel spectator combo) to avoid matching normal invis-kit players.
            if (!isBlitzSpectatorSettingsPhase(mc)) {
                return false;
            }
            EntityPlayer lp = mc.thePlayer;
            return lp.isPotionActive(Potion.invisibility) && lp.isPotionActive(Potion.nightVision);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Called from {@code RenderGlobal.renderEntities} HEAD — same cadence as {@link SelfOpacity#beginEntitiesPass()}. */
    public static void beginEntitiesPass() {
        translucentBlockLayerEnteredThisEntitiesPass = false;
        nametagWaterOcclusionCache.clear();
        nametagGlassOcclusionCache.clear();
        if (!isSpectatorSettingsRuntimeActive()) {
            // Also discard anything queued on the frame where the player returned to a live state.
            clearDeferredState();
        }
    }

    public static void endEntitiesPass() {
    }

    /** Called from {@code RenderGlobal.renderBlockLayer} HEAD when {@code TRANSLUCENT}. */
    public static void noteTranslucentBlockLayerEntered() {
        translucentBlockLayerEnteredThisEntitiesPass = true;
    }

    /**
     * Flush any spectator bodies still queued at renderWorld RETURN. This covers both skipped translucent terrain
     * and Lunar/Weave paths where RenderManager saw the player but RenderGlobal did not run our redirect.
     */
    public static boolean needsRenderWorldFallbackFlush() {
        return hasDeferredRender();
    }

    public static boolean shouldSkipAndDefer(Entity entity) {
        if (flushingDeferred) {
            return false;
        }
        return shouldApplyOpacityToEntity(entity);
    }

    public static void scheduleDeferred(Entity entity, float partialTicks) {
        if (entity == null) {
            return;
        }
        if (!deferredRenderEntityIds.add(entity.getEntityId())) {
            return;
        }
        deferredRenders.add(new DeferredVisibleSpecsRender(entity, partialTicks));
    }

    public static boolean hasDeferredRender() {
        return !deferredRenders.isEmpty();
    }

    public static void flushDeferred(RenderManager rm) {
        if (rm == null || deferredRenders.isEmpty() || flushingDeferred) {
            return;
        }
        List<DeferredVisibleSpecsRender> renders = new ArrayList<DeferredVisibleSpecsRender>(deferredRenders);
        deferredRenders.clear();
        deferredRenderEntityIds.clear();
        // Every spectator gets a depth prepass before its translucent color pass. Minecraft's entity iteration
        // order is not distance-sorted, so drawing the local spectator first can leave an opaque depth mask that
        // completely rejects a spectator behind them. Render far-to-near so nearer translucent bodies blend over
        // the spectator colors already in the framebuffer.
        Collections.sort(renders, new Comparator<DeferredVisibleSpecsRender>() {
            @Override
            public int compare(DeferredVisibleSpecsRender a, DeferredVisibleSpecsRender b) {
                return Double.compare(distanceSqToViewer(b, rm), distanceSqToViewer(a, rm));
            }
        });
        flushingDeferred = true;
        DeferredFlushEntityDrawMatrixGuard.Snap matrixSnap = DeferredFlushEntityDrawMatrixGuard.pushModelViewForEntityDraw();
        DeferredFlushMainFramebufferGuard.Snap fbSnap = DeferredFlushMainFramebufferGuard.enter(Minecraft.getMinecraft());
        DeferredFlushPolygonOffsetGuard.Snap polySnap = DeferredFlushPolygonOffsetGuard.pushClearForEntityDraw();
        boolean savedFog = false;
        boolean savedLighting = false;
        boolean savedFlushingDeferred = flushingDeferred;
        try {
            try {
                savedFog = GL11.glIsEnabled(GL11.GL_FOG);
                savedLighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
                GlStateManager.disableFog();
                GlStateManager.enableLighting();
                RenderHelper.enableStandardItemLighting();
            } catch (Throwable ignored) {
            }
            EntityOpacityGlintBlendTracker.enterDeferredOpacityFlush();
            for (DeferredVisibleSpecsRender render : renders) {
                if (render == null || render.entity == null) {
                    continue;
                }
                if (!shouldApplyOpacityToEntity(render.entity)) {
                    continue;
                }
                renderPhase.set(VisibleSpecsRenderPhase.PREPASS);
                GlStateManager.disableBlend();
                GlStateManager.colorMask(false, false, false, false);
                GlStateManager.depthMask(true);
                GlStateManager.depthFunc(GL11.GL_LEQUAL);
                GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
                rm.renderEntitySimple(render.entity, render.partialTicks);

                renderPhase.set(VisibleSpecsRenderPhase.COLOR);
                GlStateManager.colorMask(true, true, true, true);
                GlStateManager.depthMask(false);
                GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
                rm.renderEntitySimple(render.entity, render.partialTicks);
            }
            flushDeferredReplayNameplates();
            flushDeferredPlayerOffsetNametags();
            flushDeferredNametags();
        } catch (Throwable ignored) {
        } finally {
            DeferredFlushPolygonOffsetGuard.popAfterEntityDraw(polySnap);
            EntityOpacityGlintBlendTracker.exitDeferredOpacityFlush();
            renderPhase.set(VisibleSpecsRenderPhase.NONE);
            glHooksDepth = 0;
            activeRenderEntity.remove();
            deferredNametags.clear();
            deferredRenderEntityIds.clear();
            deferredPlayerOffsetNametags.clear();
            deferredPlayerOffsetNametagEntityIds.clear();
            deferredReplayNameplates.clear();
            flushingDeferredNametags = false;
            GlStateManager.colorMask(true, true, true, true);
            GlStateManager.depthMask(true);
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
            try {
                RenderHelper.disableStandardItemLighting();
                if (savedLighting) {
                    GlStateManager.enableLighting();
                } else {
                    GlStateManager.disableLighting();
                }
                if (savedFog) {
                    GlStateManager.enableFog();
                }
            } catch (Throwable ignored) {
            }
            // Terrain/translucent passes assume vanilla-ish blend + alpha-test defaults.
            GlStateManager.disableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.disableAlpha();
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
            DeferredFlushMainFramebufferGuard.leave(fbSnap);
            DeferredFlushEntityDrawMatrixGuard.popAfterEntityDraw(matrixSnap);
            flushingDeferred = false;
        }
    }

    private static double distanceSqToViewer(DeferredVisibleSpecsRender render, RenderManager rm) {
        if (render == null || render.entity == null || rm == null) {
            return Double.NEGATIVE_INFINITY;
        }
        Entity entity = render.entity;
        float partialTicks = render.partialTicks;
        double x = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks;
        double y = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks;
        double z = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks;
        double dx = x - rm.viewerPosX;
        double dy = y - rm.viewerPosY;
        double dz = z - rm.viewerPosZ;
        return dx * dx + dy * dy + dz * dz;
    }

    private static void flushDeferredNametags() {
        if (deferredNametags.isEmpty()) {
            return;
        }
        List<DeferredVisibleSpecsNametag> nametags =
                new ArrayList<DeferredVisibleSpecsNametag>(deferredNametags);
        deferredNametags.clear();
        flushingDeferredNametags = true;
        VisibleSpecsRenderPhase savedPhase = renderPhase.get();
        int savedGlHooksDepth = glHooksDepth;
        boolean savedFlushingDeferred = flushingDeferred;
        renderPhase.set(VisibleSpecsRenderPhase.NONE);
        glHooksDepth = 0;
        flushingDeferred = false;
        try {
            GlStateManager.colorMask(true, true, true, true);
            GlStateManager.depthMask(true);
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
            GlStateManager.disableBlend();
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
            for (DeferredVisibleSpecsNametag nametag : nametags) {
                if (nametag == null || nametag.renderer == null || nametag.entity == null) {
                    continue;
                }
                if (shouldSuppressNametag(nametag.entity)) {
                    continue;
                }
                if (shouldSuppressPlayerNametagForBlindness(nametag.entity, nametag.x, nametag.y, nametag.z)) {
                    continue;
                }
                if (deferredPlayerOffsetNametagEntityIds.contains(nametag.entity.getEntityId())) {
                    continue;
                }
                try {
                    ((RendererLivingEntityInvoker) nametag.renderer).gomod$invokeRenderName(
                            nametag.entity, nametag.x, nametag.y, nametag.z);
                } catch (Throwable ignored) {
                }
            }
        } finally {
            flushingDeferred = savedFlushingDeferred;
            flushingDeferredNametags = false;
            renderPhase.set(savedPhase);
            glHooksDepth = savedGlHooksDepth;
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        }
    }

    private static void flushDeferredPlayerOffsetNametags() {
        if (deferredPlayerOffsetNametags.isEmpty()) {
            return;
        }
        List<DeferredPlayerOffsetNametag> nametags =
                new ArrayList<DeferredPlayerOffsetNametag>(deferredPlayerOffsetNametags);
        deferredPlayerOffsetNametags.clear();
        flushingDeferredNametags = true;
        VisibleSpecsRenderPhase savedPhase = renderPhase.get();
        int savedGlHooksDepth = glHooksDepth;
        boolean savedFlushingDeferred = flushingDeferred;
        renderPhase.set(VisibleSpecsRenderPhase.NONE);
        glHooksDepth = 0;
        flushingDeferred = false;
        try {
            GlStateManager.colorMask(true, true, true, true);
            GlStateManager.depthMask(true);
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
            GlStateManager.disableBlend();
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
            for (DeferredPlayerOffsetNametag nametag : nametags) {
                if (nametag == null || nametag.renderer == null || nametag.player == null) {
                    continue;
                }
                if (shouldSuppressNametag(nametag.player)) {
                    continue;
                }
                if (shouldSuppressPlayerNametagForBlindness(nametag.player, nametag.x, nametag.y, nametag.z)) {
                    continue;
                }
                try {
                    ((RenderAccessor) nametag.renderer).gomod$invokeRenderOffsetLivingLabel(
                            nametag.player, nametag.x, nametag.y, nametag.z, nametag.label,
                            nametag.labelScale, nametag.distanceSq);
                } catch (Throwable ignored) {
                }
            }
        } finally {
            flushingDeferred = savedFlushingDeferred;
            flushingDeferredNametags = false;
            renderPhase.set(savedPhase);
            glHooksDepth = savedGlHooksDepth;
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        }
    }

    private static void flushDeferredReplayNameplates() {
        if (deferredReplayNameplates.isEmpty()) {
            return;
        }
        List<DeferredReplayNameplateStack> nameplates =
                new ArrayList<DeferredReplayNameplateStack>(deferredReplayNameplates);
        deferredReplayNameplates.clear();
        flushingDeferredNametags = true;
        VisibleSpecsRenderPhase savedPhase = renderPhase.get();
        int savedGlHooksDepth = glHooksDepth;
        boolean savedFlushingDeferred = flushingDeferred;
        renderPhase.set(VisibleSpecsRenderPhase.NONE);
        glHooksDepth = 0;
        flushingDeferred = false;
        try {
            GlStateManager.colorMask(true, true, true, true);
            GlStateManager.depthMask(true);
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
            GlStateManager.disableBlend();
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
            for (DeferredReplayNameplateStack nameplate : nameplates) {
                if (nameplate == null || nameplate.renderer == null || nameplate.entity == null) {
                    continue;
                }
                if (shouldSuppressPlayerNametagForBlindness(nameplate.entity, nameplate.x, nameplate.y, nameplate.z)) {
                    continue;
                }
                try {
                    ReplayPlayerHealthTagFix.renderForcedStackPostOpacity(
                            nameplate.renderer, nameplate.entity, nameplate.x, nameplate.y, nameplate.z);
                } catch (Throwable ignored) {
                }
            }
        } finally {
            flushingDeferred = savedFlushingDeferred;
            flushingDeferredNametags = false;
            renderPhase.set(savedPhase);
            glHooksDepth = savedGlHooksDepth;
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        }
    }

    public static boolean shouldScaleAlphaInCurrentPhase() {
        if (isRenderingNameplateWithoutOpacityScaling()) {
            return false;
        }
        if (firstPersonArmActive) {
            return true;
        }
        if (externalCosmeticRenderDepth > 0) {
            return true;
        }
        return glHooksDepth > 0 && renderPhase.get() != VisibleSpecsRenderPhase.PREPASS;
    }

    /** Allocation-free hot-path gate used by GL mixins during ordinary entity rendering. */
    public static boolean hasAnyGlInterceptionState() {
        return glHooksDepth > 0
                || firstPersonArmActive
                || externalCosmeticRenderDepth > 0
                || guiInventoryRenderDepth > 0
                || directOpacityRender
                || flushingDeferred;
    }

    public static boolean shouldForceWeaveColorAlpha() {
        if (!WEAVE_RUNTIME || isRenderingNameplateWithoutOpacityScaling()) {
            return false;
        }
        if (firstPersonArmActive) {
            return true;
        }
        return (directOpacityRender || flushingDeferred)
                && renderPhase.get() != VisibleSpecsRenderPhase.PREPASS;
    }

    public static boolean isGuiInventoryOpacityRenderActive() {
        return guiInventoryRenderDepth > 0
                && renderPhase.get() == VisibleSpecsRenderPhase.COLOR;
    }

    public static boolean shouldKeepBlendEnabled() {
        // Same as SelfOpacity: never cancel disableBlend during FP arm — vanilla needs it after enchant glint.
        return glHooksDepth > 0 && renderPhase.get() == VisibleSpecsRenderPhase.COLOR;
    }

    public static boolean shouldKeepAlphaTestDisabled() {
        return false;
    }

    public static boolean shouldScaleVertexAlphaOnRenderThread() {
        if (!shouldScaleAlphaInCurrentPhase()) {
            return false;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            return mc != null && mc.isCallingFromMinecraftThread();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean shouldApplyToPlayerBridge(Object playerBridge) {
        if (getRenderOpacity() >= 0.99f || playerBridge == null
                || !isSpectatorSettingsRuntimeActive() || !shouldApplySettingsInCurrentReplay()) {
            return false;
        }
        try {
            EntityPlayer renderedPlayer = resolvePlayerBridgeTarget(playerBridge);
            return renderedPlayer != null && shouldApplyOpacityToEntity(renderedPlayer);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Backward-compatible local-only query for callers that specifically need the local player. */
    public static boolean shouldApplyToLocalPlayerBridge(Object playerBridge) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            return mc != null && mc.thePlayer != null
                    && resolvePlayerBridgeTarget(playerBridge) == mc.thePlayer
                    && shouldApplyOpacityToEntity(mc.thePlayer);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static EntityPlayer resolvePlayerBridgeTarget(Object playerBridge) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || playerBridge == null || isLunarDisplayBridge(playerBridge)) {
            return null;
        }

        java.util.UUID bridgeId = null;
        try {
            Object id = LunarPlayerBridgeMethods.find(playerBridge.getClass(), "bridge$getUniqueID").invoke(playerBridge);
            if (id instanceof java.util.UUID) bridgeId = (java.util.UUID) id;
        } catch (Throwable ignored) {
        }

        // Lunar may flush retained meshes while the target's renderer is still on the stack. This also
        // identifies relay spectators that are render-only proxies and are not registered in WorldClient.
        EntityPlayer contextPlayer = CapeRenderContext.getRenderingPlayer();
        if (contextPlayer != null && (bridgeId == null || bridgeId.equals(contextPlayer.getUniqueID()))) {
            return contextPlayer;
        }
        EntityPlayer activePlayer = activeRenderEntity.get();
        if (activePlayer != null && (bridgeId == null || bridgeId.equals(activePlayer.getUniqueID()))) {
            return activePlayer;
        }

        if (bridgeId == null) {
            if (PlayerStandaloneCape.isLocalPlayerBridge(playerBridge)) return mc.thePlayer;
            return null;
        }
        if (bridgeId.equals(mc.thePlayer.getUniqueID())) return mc.thePlayer;
        try {
            return mc.theWorld == null ? null : mc.theWorld.getPlayerEntityByUUID(bridgeId);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isLunarDisplayBridge(Object playerBridge) {
        try {
            java.lang.reflect.Method getter = LunarPlayerBridgeMethods.find(playerBridge.getClass(), "getDummyPlayerType");
            Object value = getter.invoke(playerBridge);
            if (value instanceof Enum<?>) {
                String kind = ((Enum<?>) value).name();
                return "SELF".equals(kind) || "MANNEQUIN".equals(kind);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static boolean shouldApplyToGuiInventoryEntity(Entity entity) {
        // Inventory player previews must remain opaque; their layered skin/armor render has no opacity
        // depth pre-pass and otherwise shows rear outer-layer geometry through the front of the model.
        return false;
    }

    public static boolean beginGuiInventoryEntityRender(Entity entity) {
        if (!shouldApplyToGuiInventoryEntity(entity)) {
            return false;
        }
        if (guiInventoryRenderDepth++ > 0) {
            return true;
        }
        try {
            if (!guiInventoryLogged) {
                guiInventoryLogged = true;
                System.out.println("[gomod] spectator-opacity inventory preview active (alpha="
                        + getRenderOpacity() + ")");
            }
            guiInventorySavedBlend = GL11.glIsEnabled(GL11.GL_BLEND);
            guiInventorySavedAlphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
            guiInventorySavedCullFace = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            guiInventorySavedDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);

            renderPhase.set(VisibleSpecsRenderPhase.COLOR);
            glHooksDepth++;
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.003921569F);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            // Unlike the world opacity renderer, GuiInventory has no depth-only pre-pass. Disabling writes
            // makes rear skin-overlay geometry show through the player model, so let this preview build its
            // own depth buffer while retaining the configured translucent colour pass.
            GL11.glDepthMask(true);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            return true;
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static void endGuiInventoryEntityRender(Entity entity) {
        if (guiInventoryRenderDepth <= 0) {
            guiInventoryRenderDepth = 0;
            return;
        }
        guiInventoryRenderDepth--;
        if (guiInventoryRenderDepth > 0) {
            return;
        }
        guiInventoryRenderDepth = 0;
        try {
            if (glHooksDepth > 0) {
                glHooksDepth--;
            }
            renderPhase.set(VisibleSpecsRenderPhase.NONE);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            GL11.glDepthMask(guiInventorySavedDepthMask);
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
            if (guiInventorySavedAlphaTest) {
                GL11.glEnable(GL11.GL_ALPHA_TEST);
                GlStateManager.enableAlpha();
            } else {
                GL11.glDisable(GL11.GL_ALPHA_TEST);
                GlStateManager.disableAlpha();
            }
            if (guiInventorySavedCullFace) {
                GL11.glEnable(GL11.GL_CULL_FACE);
            } else {
                GL11.glDisable(GL11.GL_CULL_FACE);
            }
            if (guiInventorySavedBlend) {
                GL11.glEnable(GL11.GL_BLEND);
                GlStateManager.enableBlend();
            } else {
                GL11.glDisable(GL11.GL_BLEND);
                GlStateManager.disableBlend();
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean beginExternalLocalPlayerCosmeticRender(Object playerBridge) {
        return beginExternalCosmeticRender(playerBridge);
    }

    /** Applies spectator opacity to the exact local or remote player represented by a Lunar bridge. */
    public static boolean beginExternalCosmeticRender(Object playerBridge) {
        if (!shouldApplyToPlayerBridge(playerBridge)) {
            return false;
        }
        if (externalCosmeticRenderDepth++ > 0) {
            return true;
        }
        externalCosmeticOpenedPhase = false;
        externalCosmeticOpenedPrepassColor = false;
        try {
            if (!externalCosmeticLogged) {
                externalCosmeticLogged = true;
                System.out.println("[gomod] spectator-opacity Lunar cosmetic render active (alpha="
                        + getRenderOpacity() + ")");
            }
            if (glHooksDepth <= 0) {
                externalCosmeticOpenedPhase = true;
                externalCosmeticSavedBlend = GL11.glIsEnabled(GL11.GL_BLEND);
                externalCosmeticSavedAlphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
                externalCosmeticSavedDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
                renderPhase.set(VisibleSpecsRenderPhase.COLOR);
                glHooksDepth++;
            }
            if (!externalCosmeticOpenedPhase
                    && glHooksDepth > 0
                    && renderPhase.get() == VisibleSpecsRenderPhase.PREPASS) {
                externalCosmeticOpenedPrepassColor = true;
                GlStateManager.colorMask(true, true, true, true);
            }
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.003921569F);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDepthMask(externalCosmeticOpenedPrepassColor);
            // Genesis can skip gomod's GL mixins, so set the real cloak alpha at this native bridge too.
            GL11.glColor4f(1.0F, 1.0F, 1.0F, getRenderOpacity());
        } catch (Throwable ignored) {
        }
        return true;
    }

    public static void endExternalLocalPlayerCosmeticRender() {
        if (externalCosmeticRenderDepth <= 0) {
            externalCosmeticRenderDepth = 0;
            return;
        }
        externalCosmeticRenderDepth--;
        if (externalCosmeticRenderDepth > 0) {
            return;
        }
        try {
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            if (externalCosmeticOpenedPrepassColor) {
                GL11.glDepthMask(true);
                GlStateManager.colorMask(false, false, false, false);
            }
            if (externalCosmeticOpenedPhase) {
                if (glHooksDepth > 0) {
                    glHooksDepth--;
                }
                renderPhase.set(VisibleSpecsRenderPhase.NONE);
                GL11.glDepthMask(externalCosmeticSavedDepthMask);
                GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
                if (externalCosmeticSavedAlphaTest) {
                    GL11.glEnable(GL11.GL_ALPHA_TEST);
                    GlStateManager.enableAlpha();
                } else {
                    GL11.glDisable(GL11.GL_ALPHA_TEST);
                    GlStateManager.disableAlpha();
                }
                if (externalCosmeticSavedBlend) {
                    GL11.glEnable(GL11.GL_BLEND);
                    GlStateManager.enableBlend();
                } else {
                    GL11.glDisable(GL11.GL_BLEND);
                    GlStateManager.disableBlend();
                }
            }
        } catch (Throwable ignored) {
        } finally {
            externalCosmeticOpenedPhase = false;
            externalCosmeticOpenedPrepassColor = false;
        }
    }

    public static boolean shouldForceExternalCosmeticNoDepthWrite() {
        return externalCosmeticRenderDepth > 0
                && !externalCosmeticOpenedPrepassColor
                && getRenderOpacity() < 0.99f;
    }

    /** Same winding caveat as {@link SelfOpacity}: forced cull only in third-person-behind. */
    private static boolean shouldEnableCullFaceForDeferredOpacityPass() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.gameSettings == null) {
                return false;
            }
            return mc.gameSettings.thirdPersonView == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void onRenderEntitySimpleHead(Entity entity) {
        if (!shouldApplyToEntityForRender(entity)) {
            return;
        }
        if (!(entity instanceof EntityPlayer)) {
            return;
        }
        boolean directRender = !flushingDeferred;
        if (glHooksDepth == 0) {
            if (directRender) {
                directOpacityRender = true;
                renderPhase.set(VisibleSpecsRenderPhase.COLOR);
            }
            EntityOpacityGlintBlendTracker.onEntitySimpleHeadOuterEntry();
            activeRenderEntity.set((EntityPlayer) entity);
            savedAlphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
            savedBlend = GL11.glIsEnabled(GL11.GL_BLEND);
            if (savedAlphaTest) {
                try {
                    savedAlphaTestFunc = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
                    savedAlphaTestRef = GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF);
                } catch (Throwable ignored) {
                    savedAlphaTestFunc = GL11.GL_GREATER;
                    savedAlphaTestRef = 0.1F;
                }
            }
            savedCullFace = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            if (renderPhase.get() == VisibleSpecsRenderPhase.COLOR) {
                // Keep alpha-test enabled (low threshold) to preserve cutout/glint masking.
                GL11.glEnable(GL11.GL_ALPHA_TEST);
                GlStateManager.alphaFunc(GL11.GL_GREATER, 0.003921569F);
                GlStateManager.enableBlend();
                GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glDepthMask(false);
                if (shouldForceWeaveColorAlpha()) {
                    GL11.glColor4f(1.0F, 1.0F, 1.0F, getRenderOpacity());
                }
            }
            if (shouldEnableCullFaceForDeferredOpacityPass()) {
                GL11.glEnable(GL11.GL_CULL_FACE);
            }
        }
        glHooksDepth++;
    }

    public static void onRenderEntitySimpleTail(Entity entity) {
        if (glHooksDepth <= 0) {
            return;
        }
        EntityPlayer active = activeRenderEntity.get();
        if (active == null || entity != active) {
            return;
        }
        glHooksDepth--;
        if (glHooksDepth == 0) {
            GL11.glDepthMask(true);
            if (!savedCullFace) {
                GL11.glDisable(GL11.GL_CULL_FACE);
            }
            GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
            // COLOR pass enables alpha-test + blend; must restore previous state. If alpha-test was off,
            // leaving it on breaks later translucent terrain (e.g. water with Water Clarity alpha tuning).
            if (savedAlphaTest) {
                GL11.glEnable(GL11.GL_ALPHA_TEST);
                GlStateManager.enableAlpha();
                GlStateManager.alphaFunc(savedAlphaTestFunc, savedAlphaTestRef);
            } else {
                GL11.glDisable(GL11.GL_ALPHA_TEST);
                GlStateManager.disableAlpha();
            }
            if (savedBlend) {
                GlStateManager.enableBlend();
            } else {
                GlStateManager.disableBlend();
            }
            if (directOpacityRender) {
                directOpacityRender = false;
                renderPhase.set(VisibleSpecsRenderPhase.NONE);
            }
            activeRenderEntity.remove();
        }
    }

    public static Boolean runWeaveDirectTwoPassIfNeeded(RenderManager rm, Entity entity, float partialTicks) {
        if (rm == null || entity == null || !shouldUseWeaveDeferredTwoPass(entity)) {
            return null;
        }
        try {
            scheduleDeferred(entity, partialTicks);
            if (!weaveDeferredLogged) {
                weaveDeferredLogged = true;
                System.out.println("[gomod] Weave spectator-opacity deferred renderer active (alpha=" + getRenderOpacity() + ")");
            }
        } catch (Throwable t) {
            System.err.println("[gomod] Weave spectator-opacity defer: " + t.getMessage());
        }
        return Boolean.FALSE;
    }

    private static boolean shouldUseWeaveDeferredTwoPass(Entity entity) {
        if (!WEAVE_RUNTIME || flushingDeferred || !shouldApplyToEntityForRender(entity)) {
            return false;
        }
        try {
            if (me.napim.gomod.util.OutlineRenderingHelper.isInOutlineMode()
                    || me.napim.gomod.util.OutlineRenderingHelper.shouldSuppressNametagInOutlineFramebuffer()) {
                return false;
            }
            Minecraft mc = Minecraft.getMinecraft();
            return mc != null && mc.theWorld != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean detectWeaveRuntime() {
        try {
            Class.forName("net.weavemc.api.ModInitializer", false, VisibleSpecs.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
