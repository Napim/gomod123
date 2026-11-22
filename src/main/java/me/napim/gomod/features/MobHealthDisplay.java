package me.napim.gomod.features;

import me.napim.gomod.gui.HudModuleTextDraw;
import me.napim.gomod.gui.HudEditorBackgroundMask;
import me.napim.gomod.mixins.RenderAccessor;
import me.napim.gomod.render.VanillaEntityLabelRenderer;
import me.napim.gomod.render.VanillaPlayerNametagLayout;
import me.napim.gomod.util.HudGlState;
import me.napim.gomod.util.OutlineRenderingHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.monster.EntityGhast;
import net.minecraft.entity.monster.EntityWitch;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

import java.util.HashSet;
import java.util.Set;

/**
 * MobHealthDisplay renders a HUD panel showing the mob the player is looking at
 * with health information, heart icons, and a black background. The mob is rendered
 * with its full animation in the UI panel.
 */
public final class MobHealthDisplay {
    private static final int GUI_NAMETAG_PLATE_BG = 0x40000000;
    private static final int OVERSIZED_GHAST_GUI_SCALE = 8;
    private static final int OVERSIZED_DRAGON_GUI_SCALE = 5;
    private static final double WITCH_HAT_LABEL_Y_OFFSET = 0.4D;

    private static boolean enabled = false;
    private static boolean showBackground = true;
    private static boolean showEffects = true;
    private static boolean textShadow = true;
    private static int backgroundOpacity = 120; // 0-255
    private static int backgroundColor = 0;
    private static int panelX = 208;
    private static int panelY = 135;
    private static float panelScale = 1.0f;
    private static double maxDistance = 32.0; // Maximum distance to detect mobs (in blocks)
    private static double persistenceTime = 2.0; // Time in seconds to keep showing UI after looking away (0-10)
    private static String displayMode = "BOTH"; // PANEL | NAMETAG | BOTH | BOTH2
    
    // Persistence: Keep showing the UI for X seconds after looking away
    private static EntityLivingBase lastTargetMob = null;
    private static long lastSeenTime = 0L;
    private static boolean renderingGuiMobPreview = false;
    private static long activeTargetResolveTick = -1L;
    private static EntityLivingBase activeTargetResolved = null;
    private static final Set<Integer> worldHealthNametagsRenderedThisFrame = new HashSet<Integer>();
    private static final Set<Integer> worldHealthNametagsForcedThisFrame = new HashSet<Integer>();
    private static long worldHealthNametagFrameBeginAtMs = 0L;

    /** BOTH split mode: second {@code renderLivingLabel} for the mob name row (not the health row). */
    private static final ThreadLocal<Boolean> RENDERING_STACKED_MOB_NAME_ROW = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };
    /** Split health modes: first {@code renderLivingLabel} already contains the custom health text. */
    private static final ThreadLocal<Boolean> RENDERING_HEALTH_NAMETAG_ROW = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };
    private static final ThreadLocal<Boolean> APPLYING_LABEL_BASE_Y_OFFSET = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };

    private MobHealthDisplay() {}

    public static boolean isRenderingStackedMobNameRow() {
        return Boolean.TRUE.equals(RENDERING_STACKED_MOB_NAME_ROW.get());
    }

    public static void runWithStackedMobNameRow(Runnable draw) {
        RENDERING_STACKED_MOB_NAME_ROW.set(Boolean.TRUE);
        try {
            draw.run();
        } finally {
            RENDERING_STACKED_MOB_NAME_ROW.set(Boolean.FALSE);
        }
    }

    public static boolean isRenderingHealthNametagRow() {
        return Boolean.TRUE.equals(RENDERING_HEALTH_NAMETAG_ROW.get());
    }

    public static void runWithHealthNametagRow(Runnable draw) {
        RENDERING_HEALTH_NAMETAG_ROW.set(Boolean.TRUE);
        try {
            draw.run();
        } finally {
            RENDERING_HEALTH_NAMETAG_ROW.set(Boolean.FALSE);
        }
    }

    public static boolean isApplyingLabelBaseYOffset() {
        return Boolean.TRUE.equals(APPLYING_LABEL_BASE_Y_OFFSET.get());
    }

    public static void runWithLabelBaseYOffsetApplied(Runnable draw) {
        Boolean previous = APPLYING_LABEL_BASE_Y_OFFSET.get();
        APPLYING_LABEL_BASE_Y_OFFSET.set(Boolean.TRUE);
        try {
            draw.run();
        } finally {
            APPLYING_LABEL_BASE_Y_OFFSET.set(previous);
        }
    }

    public static double mobLabelBaseYOffset(Entity entity) {
        return isWitchLike(entity) ? WITCH_HAT_LABEL_Y_OFFSET : 0.0D;
    }

    private static boolean isWitchLike(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (entity instanceof EntityWitch) {
            return true;
        }
        String className = entity.getClass().getSimpleName();
        return className != null && className.contains("Witch");
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        ConfigManager.setMobHealthDisplayEnabled(value);
    }

    public static boolean isEnabled() {
        return enabled;
    }
    
    public static void toggle() {
        setEnabled(!enabled);
    }
    
    public static void setShowBackground(boolean value) {
        showBackground = value;
        ConfigManager.setMobHealthDisplayShowBackground(value);
    }
    
    public static boolean isShowBackground() {
        return getBackgroundOpacity() > 0;
    }
    
    public static void toggleBackground() {
        if (getBackgroundOpacity() > 0) {
            setBackgroundOpacity(0);
        } else {
            setBackgroundOpacity(120);
        }
    }

    public static void setShowEffects(boolean value) {
        showEffects = value;
        ConfigManager.setMobHealthDisplayShowEffects(value);
    }

    public static boolean isShowEffects() {
        return showEffects;
    }

    public static void toggleShowEffects() {
        setShowEffects(!showEffects);
    }

    public static boolean isTextShadow() {
        return textShadow;
    }

    public static void setTextShadow(boolean value) {
        textShadow = value;
        ConfigManager.setMobHealthDisplayTextShadow(value);
    }

    public static void toggleTextShadow() {
        setTextShadow(!textShadow);
    }
    
    public static int getBackgroundOpacity() {
        return backgroundOpacity;
    }
    
    public static void setBackgroundOpacity(int value) {
        backgroundOpacity = Math.max(0, Math.min(255, value));
        ConfigManager.setMobHealthDisplayBackgroundOpacity(backgroundOpacity);
    }

    public static int getBackgroundColor() {
        return backgroundColor;
    }

    public static void setBackgroundColor(int rgb) {
        backgroundColor = rgb & 0xFFFFFF;
        ConfigManager.setMobHealthDisplayBackgroundColor(backgroundColor);
    }
    
    public static double getMaxDistance() {
        return maxDistance;
    }
    
    public static void setMaxDistance(double value) {
        maxDistance = Math.max(1.0, Math.min(48.0, value));
        ConfigManager.setMobHealthDisplayMaxDistance(maxDistance);
    }
    
    public static double getPersistenceTime() {
        return persistenceTime;
    }
    
    public static void setPersistenceTime(double value) {
        persistenceTime = Math.max(0.0, Math.min(10.0, value));
        ConfigManager.setMobHealthDisplayPersistenceTime(persistenceTime);
    }
    
    public static int getPanelX() {
        return panelX;
    }
    
    public static int getPanelY() {
        return panelY;
    }
    
    public static void setCustomPosition(int x, int y) {
        panelX = x;
        panelY = y;
        ConfigManager.setMobHealthDisplayPanelX(x);
        ConfigManager.setMobHealthDisplayPanelY(y);
    }
    
    public static void resetPosition() {
        panelX = 208;
        panelY = 135;
        ConfigManager.setMobHealthDisplayPanelX(208);
        ConfigManager.setMobHealthDisplayPanelY(135);
    }

    public static float getScale() {
        return panelScale;
    }

    public static void setScale(float value) {
        panelScale = Math.max(0.5f, Math.min(4.0f, value));
        ConfigManager.setMobHealthDisplayScale(panelScale);
    }

    public static String getDisplayMode() {
        return displayMode;
    }

    public static void setDisplayMode(String mode) {
        String normalized = mode == null ? "PANEL" : mode.trim().toUpperCase();
        if (!"NAMETAG".equals(normalized) && !"BOTH".equals(normalized) && !"BOTH2".equals(normalized)) {
            normalized = "PANEL";
        }
        displayMode = normalized;
        ConfigManager.setMobHealthDisplayMode(displayMode);
        if ("BOTH2".equals(displayMode)) {
            ensureMobNametagsForCombinedMode();
        }
    }

    public static void cycleDisplayMode() {
        if ("PANEL".equals(displayMode)) {
            setDisplayMode("NAMETAG");
        } else if ("NAMETAG".equals(displayMode)) {
            setDisplayMode("BOTH");
        } else if ("BOTH".equals(displayMode)) {
            setDisplayMode("BOTH2");
        } else {
            setDisplayMode("PANEL");
        }
    }

    public static boolean isPanelMode() {
        return "PANEL".equals(displayMode) || "BOTH".equals(displayMode) || "BOTH2".equals(displayMode);
    }

    /** True when only the HUD panel is shown (no health tags). */
    public static boolean isHudPanelOnlyMode() {
        return "PANEL".equals(displayMode);
    }

    public static boolean isNametagMode() {
        return "NAMETAG".equals(displayMode) || "BOTH".equals(displayMode) || "BOTH2".equals(displayMode);
    }

    /** HUD panel + single in-world tag with mob name and health combined (not stacked). */
    public static boolean isCombinedNametagMode() {
        return "BOTH2".equals(displayMode);
    }

    /** HUD panel + separate health row and mob name row above entities. */
    public static boolean isSplitNametagMode() {
        return "BOTH".equals(displayMode);
    }

    private static void ensureMobNametagsForCombinedMode() {
        if (!MobNametags.isEnabled()) {
            MobNametags.setEnabled(true);
        }
    }

    public static boolean isMobNametagsForcedByCurrentMode() {
        return isCombinedNametagMode();
    }

    public static void enforceMobNametagsForCurrentMode() {
        if (isMobNametagsForcedByCurrentMode()) {
            ensureMobNametagsForCombinedMode();
        }
    }

    /** BOTH2 requires mob nametags even if the standalone toggle is off (mode enables it on select). */
    public static boolean shouldForceMobNametagsForCombinedMode(EntityLivingBase entity) {
        return enabled && isCombinedNametagMode() && isSupportedHealthMob(entity) && !renderingGuiMobPreview;
    }

    /** Forces {@code canRenderName} so vanilla {@code renderName} runs for health / combined rows. */
    public static boolean shouldForceHealthNametagNameRender(EntityLivingBase entity) {
        if (!enabled || !isNametagMode() || renderingGuiMobPreview || entity == null) {
            return false;
        }
        if (!isSupportedHealthMob(entity)) {
            return false;
        }
        if (!isHealthTagEnabledForMob(entity)) {
            return false;
        }
        return true;
    }

    /** When false, health tags and crosshair HUD are skipped for that mob type. */
    public static boolean isHealthTagEnabledForMob(EntityLivingBase entity) {
        if (entity == null) {
            return true;
        }
        int typeId = EntityList.getEntityID(entity);
        if (typeId <= 0) {
            return true;
        }
        return ConfigManager.isMobHealthDisplayHealthTagEnabledForType(typeId);
    }

    public static void toggleHealthTagChicken() {
        ConfigManager.toggleMobHealthDisplayHealthTagForType(93);
    }

    public static void toggleHealthTagOcelot() {
        ConfigManager.toggleMobHealthDisplayHealthTagForType(98);
    }

    public static void toggleHealthTagRabbit() {
        ConfigManager.toggleMobHealthDisplayHealthTagForType(101);
    }
    
    /**
     * Load settings from ConfigManager
     */
    public static void loadSettings() {
        try {
            enabled = ConfigManager.isMobHealthDisplayEnabled();
            showBackground = ConfigManager.isMobHealthDisplayShowBackground();
            showEffects = ConfigManager.isMobHealthDisplayShowEffects();
            textShadow = ConfigManager.isMobHealthDisplayTextShadow();
            backgroundOpacity = ConfigManager.getMobHealthDisplayBackgroundOpacity();
            backgroundColor = ConfigManager.getMobHealthDisplayBackgroundColor();
            maxDistance = ConfigManager.getMobHealthDisplayMaxDistance();
            persistenceTime = ConfigManager.getMobHealthDisplayPersistenceTime();
            panelX = ConfigManager.getMobHealthDisplayPanelX();
            panelY = ConfigManager.getMobHealthDisplayPanelY();
            panelScale = ConfigManager.getMobHealthDisplayScale();
            displayMode = ConfigManager.getMobHealthDisplayMode();
            if ("BOTH2".equals(displayMode)) {
                ensureMobNametagsForCombinedMode();
            }
        } catch (Exception e) {
            System.err.println("[MobHealthDisplay] Error loading settings: " + e.getMessage());
        }
    }

    /**
     * Renders the mob health display HUD panel.
     * Called from GuiIngameForgeMixin or similar HUD rendering location.
     */
    public static void render() {
        if (!enabled) return;
        
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) return;
        
        // Do not render the live HUD while the settings GUI is open; the GUI draws its own preview
        try {
            if (mc.currentScreen != null && mc.currentScreen instanceof me.napim.gomod.gui.MobHealthDisplayGUI) {
                return;
            }
        } catch (Throwable ignored) {}
        
        if (!isPanelMode()) {
            return;
        }

        EntityLivingBase targetMob = resolveActiveTargetMob(mc);
        if (targetMob != null) {
            drawMobPanel(mc, targetMob);
        }
    }

    public static void beginWorldHealthNametagFrame() {
        worldHealthNametagsRenderedThisFrame.clear();
        worldHealthNametagsForcedThisFrame.clear();
        worldHealthNametagFrameBeginAtMs = System.currentTimeMillis();
    }

    public static boolean hasFreshWorldHealthNametagFrame() {
        long age = System.currentTimeMillis() - worldHealthNametagFrameBeginAtMs;
        return age >= 0L && age <= 250L;
    }

    public static void markWorldHealthNametagRendered(EntityLivingBase entity) {
        if (entity != null) {
            worldHealthNametagsRenderedThisFrame.add(Integer.valueOf(entity.getEntityId()));
        }
    }

    private static boolean wasWorldHealthNametagRendered(EntityLivingBase entity) {
        return entity != null && worldHealthNametagsRenderedThisFrame.contains(Integer.valueOf(entity.getEntityId()));
    }

    private static boolean wasWorldHealthNametagForced(EntityLivingBase entity) {
        return entity != null && worldHealthNametagsForcedThisFrame.contains(Integer.valueOf(entity.getEntityId()));
    }

    private static void markWorldHealthNametagForced(EntityLivingBase entity) {
        if (entity != null) {
            worldHealthNametagsForcedThisFrame.add(Integer.valueOf(entity.getEntityId()));
        }
    }

    /**
     * World fallback for clients that skip {@code RendererLivingEntity#renderName}. Normal entity-pass labels mark
     * their entity id first, so this only fills missing tags instead of duplicating working ones.
     */
    public static void renderWorldHealthNametags(float partialTicks) {
        renderWorldHealthNametags(partialTicks, false);
    }

    public static void renderWorldHealthNametags(float partialTicks, boolean forceRedraw) {
        renderWorldHealthNametags(partialTicks, forceRedraw, forceRedraw);
    }

    /** Weave-only late fallback that preserves the normal entity pass's per-frame duplicate markers. */
    public static void renderMissingWorldHealthNametagsThroughChests(float partialTicks) {
        renderWorldHealthNametags(partialTicks, true, false);
    }

    private static void renderWorldHealthNametags(float partialTicks, boolean throughChestsFallback,
            boolean redrawExisting) {
        if (!enabled || isHudPanelOnlyMode() || renderingGuiMobPreview) {
            return;
        }
        if (!throughChestsFallback && VisibleSpecs.shouldAllowNametagThroughBlocksNow()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.getRenderManager() == null) {
            return;
        }
        try {
            if (mc.currentScreen != null) {
                return;
            }
            if (OutlineRenderingHelper.isInOutlineMode()
                    || OutlineRenderingHelper.shouldSuppressNametagInOutlineFramebuffer()) {
                return;
            }
            RenderManager rm = mc.getRenderManager();
            for (Object obj : mc.theWorld.loadedEntityList) {
                if (!(obj instanceof EntityLivingBase)) {
                    continue;
                }
                EntityLivingBase entity = (EntityLivingBase) obj;
                if (!redrawExisting
                        && (wasWorldHealthNametagRendered(entity) || wasWorldHealthNametagForced(entity))) {
                    continue;
                }
                if (redrawExisting && wasWorldHealthNametagForced(entity)) {
                    continue;
                }
                if (!shouldRenderHealthNametagFor(entity)) {
                    continue;
                }
                EntityLivingBase mobProxy = LocalMobDisguise.getProxyEntity();
                if (mobProxy != null && entity == mobProxy) {
                    continue;
                }
                double x = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks - rm.viewerPosX;
                double y = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks - rm.viewerPosY;
                double z = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks - rm.viewerPosZ;
                if (throughChestsFallback && VisibleSpecs.isWaterBetweenViewAndNametag(entity, x,
                        y + mobLabelBaseYOffset(entity), z)) {
                    continue;
                }
                if (drawHealthNametagRows(entity, x, y, z, throughChestsFallback) && throughChestsFallback) {
                    markWorldHealthNametagForced(entity);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean drawHealthNametagRows(EntityLivingBase entity, double x, double y, double z) {
        return drawHealthNametagRows(entity, x, y, z, false);
    }

    private static boolean drawHealthNametagRows(EntityLivingBase entity, double x, double y, double z,
            boolean drawDirectly) {
        try {
            if (renderingGuiMobPreview || isHudPanelOnlyMode()) {
                return false;
            }
            if (!shouldRenderHealthNametagFor(entity)) {
                return false;
            }
            if (VisibleSpecs.shouldSuppressNametag(entity)) {
                return false;
            }
            if (OutlineRenderingHelper.isInOutlineMode()
                    || OutlineRenderingHelper.shouldSuppressNametagInOutlineFramebuffer()) {
                return false;
            }
            EntityLivingBase mobProxy = LocalMobDisguise.getProxyEntity();
            if (mobProxy != null && entity == mobProxy) {
                return false;
            }
            String label = formatLivingLabel(entity);
            if (label == null || label.trim().isEmpty()) {
                return false;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.getRenderManager() == null) {
                return false;
            }
            Render<?> render = mc.getRenderManager().getEntityRenderObject(entity);
            if (!drawDirectly && (!(render instanceof RendererLivingEntity) || !(render instanceof RenderAccessor))) {
                return false;
            }
            final RenderAccessor renderer = render instanceof RenderAccessor ? (RenderAccessor) render : null;
            final EntityLivingBase healthEntity = entity;
            final String healthLabel = label;
            final double healthX = x;
            final double labelBaseY = y + mobLabelBaseYOffset(entity);
            if (drawDirectly && VisibleSpecs.isWaterBetweenViewAndNametag(entity, healthX, labelBaseY, z)) {
                return false;
            }
            final double healthY = labelBaseY;
            final double healthZ = z;
            // The regular vanilla dim pass is separate from the optional chest-specific late replay.
            final boolean healthFadeThroughWalls = true;
            runWithHealthNametagRow(new Runnable() {
                @Override
                public void run() {
                    runWithLabelBaseYOffsetApplied(new Runnable() {
                        @Override
                        public void run() {
                            if (drawDirectly) {
                                VanillaEntityLabelRenderer.render(healthEntity, healthLabel,
                                        healthX, healthY, healthZ, MobNametags.MAX_DISTANCE,
                                        0.0D, 0xFFFFFF, 1.0F, 1.0F, false,
                                        healthFadeThroughWalls);
                            } else if (renderer != null) {
                                renderer.gomod$invokeRenderLivingLabel(healthEntity, healthLabel,
                                        healthX, healthY, healthZ, MobNametags.MAX_DISTANCE);
                            }
                        }
                    });
                }
            });
            if (shouldStackMobNameAboveHealth(entity)) {
                String mobLabel = MobNametags.formatLabelForRender(entity);
                if (mobLabel != null && !mobLabel.trim().isEmpty()) {
                    final double nameY = labelBaseY + mobNametagFootYAboveHealth();
                    final boolean nameFadeThroughWalls = true;
                    final String stackedLabel = mobLabel;
                    runWithStackedMobNameRow(new Runnable() {
                        @Override
                        public void run() {
                            runWithLabelBaseYOffsetApplied(new Runnable() {
                                @Override
                                public void run() {
                                    if (drawDirectly) {
                                        VanillaEntityLabelRenderer.render(healthEntity, stackedLabel,
                                                healthX, nameY, healthZ, MobNametags.MAX_DISTANCE,
                                                0.0D, 0xFFFFFF, 1.0F, 1.0F, false,
                                                nameFadeThroughWalls);
                                    } else if (renderer != null) {
                                        renderer.gomod$invokeRenderLivingLabel(healthEntity, stackedLabel,
                                                healthX, nameY, healthZ, MobNametags.MAX_DISTANCE);
                                    }
                                }
                            });
                        }
                    });
                }
            }
            markWorldHealthNametagRendered(entity);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * In-world health / combined text for {@link me.napim.gomod.mixins.RenderLivingLabelMixin} (entity-pass timing).
     */
    public static String formatLivingLabel(EntityLivingBase entity) {
        if (entity == null) {
            return "";
        }
        float hp = Math.max(0f, entity.getHealth());
        float maxHp = Math.max(1f, entity.getMaxHealth());
        if (isCombinedNametagMode()) {
            return formatCombinedNametagText(entity, hp, maxHp);
        }
        return formatNametagHealthText(hp, maxHp);
    }

    /**
     * True when this mob gets the in-world health row (paired with {@link MobNametags}, not crosshair-only).
     */
    public static boolean shouldRenderHealthNametagFor(EntityLivingBase entity) {
        if (!shouldForceHealthNametagNameRender(entity)) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.getRenderViewEntity() == null) {
            return false;
        }
        return mc.thePlayer == null || mc.thePlayer.ridingEntity != entity;
    }

    /**
     * Plain mob-name cases owned by the entity render tail, avoiding the late world fallback flicker.
     */
    public static boolean shouldRenderPlainMobNameInEntityTail(EntityLivingBase entity) {
        return enabled
                && (isHudPanelOnlyMode() || isNametagMode())
                && !renderingGuiMobPreview
                && entity != null
                && !shouldRenderHealthNametagFor(entity);
    }

    /**
     * Health/combined row is separate from the mob-name row (NAMETAG, BOTH). Not used for BOTH2 (single combined line).
     */
    public static boolean shouldStackMobNameAboveHealth(EntityLivingBase entity) {
        return shouldRenderHealthNametagFor(entity)
                && !isCombinedNametagMode()
                && MobNametags.shouldRenderMobNametag(entity)
                && MobNametags.formatLabelForRender(entity) != null;
    }

    private static boolean isSupportedHealthMob(EntityLivingBase entity) {
        if (entity == null || entity.isDead || !entity.isEntityAlive()) {
            return false;
        }
        if (entity instanceof EntityPlayer || entity instanceof EntityArmorStand) {
            return false;
        }
        return !shouldIgnoreEntity(entity);
    }

    /** World-space step to stack the mob name row above the health row. */
    public static double mobNametagFootYAboveHealth() {
        return nametagRowSpacingWorldY();
    }

    /** One nametag row step in world space (matches vanilla nameplate line gap). */
    public static double nametagRowSpacingWorldY() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.fontRendererObj == null) {
            return 0.0D;
        }
        return mc.fontRendererObj.FONT_HEIGHT
                * VanillaPlayerNametagLayout.VANILLA_NAMETAG_LINE_GAP_FACTOR
                * VanillaEntityLabelRenderer.NAMEPLATE_SCALE;
    }

    /**
     * Nametag rows drawn by mob health display at/above the entity baseline (footY 0).
     * Split modes: health + name = 2; BOTH2: combined tag = 1.
     */
    public static int getRenderedNametagStackHeightRows(EntityLivingBase entity) {
        if (!shouldRenderHealthNametagFor(entity)) {
            return 0;
        }
        return isCombinedNametagMode() ? 1 : 2;
    }

    /**
     * Mob currently shown (crosshair target with persistence). Cached per world tick.
     */
    public static EntityLivingBase resolveActiveTargetMob(Minecraft mc) {
        if (mc == null || mc.theWorld == null) {
            return null;
        }
        long tick = mc.theWorld.getTotalWorldTime();
        if (tick == activeTargetResolveTick) {
            return activeTargetResolved;
        }
        activeTargetResolveTick = tick;
        activeTargetResolved = computeActiveTargetMob(mc);
        return activeTargetResolved;
    }

    private static EntityLivingBase computeActiveTargetMob(Minecraft mc) {
        EntityLivingBase targetMob = findTargetMob(mc);
        if (targetMob != null) {
            lastTargetMob = targetMob;
            lastSeenTime = System.currentTimeMillis();
            return targetMob;
        }

        long currentTime = System.currentTimeMillis();
        long timeSinceLastSeen = currentTime - lastSeenTime;
        long persistenceTimeMs = (long) (persistenceTime * 1000);

        if (lastTargetMob != null && timeSinceLastSeen < persistenceTimeMs) {
            boolean isOurMount = mc.thePlayer != null && mc.thePlayer.ridingEntity == lastTargetMob;
            if (!lastTargetMob.isDead && lastTargetMob.worldObj != null && !shouldIgnoreEntity(lastTargetMob)
                    && isHealthTagEnabledForMob(lastTargetMob)
                    && !isOurMount) {
                return lastTargetMob;
            }
            lastTargetMob = null;
        } else {
            lastTargetMob = null;
        }
        return null;
    }
    
    /**
     * Finds the mob the player is currently looking at (custom raycast with configurable distance)
     */
    private static EntityLivingBase findTargetMob(Minecraft mc) {
        try {
            // Perform custom raycast with configurable distance
            net.minecraft.entity.Entity pointedEntity = getEntityPlayerIsLookingAt(mc, maxDistance);
            
            if (pointedEntity instanceof EntityLiving) {
                EntityLivingBase entity = (EntityLivingBase) pointedEntity;
                
                // Filter out Hypixel lobby entities (invisible withers used for text display)
                if (shouldIgnoreEntity(entity)) {
                    return null;
                }
                if (!isHealthTagEnabledForMob(entity)) {
                    return null;
                }

                // Don't show HP panel for the horse/donkey we're currently riding
                if (mc.thePlayer != null && mc.thePlayer.ridingEntity == entity) {
                    return null;
                }
                
                return entity;
            }
        } catch (Throwable ignored) {}
        return null;
    }
    
    /**
     * Checks if an entity should be ignored by the mob health display.
     * This filters out Hypixel's lobby boss mobs (withers/dragons) that are used as fake boss bars for text display.
     */
    private static boolean shouldIgnoreEntity(EntityLivingBase entity) {
        try {
            // Filter out invisible withers with custom names (Hypixel lobby text displays)
            if (entity instanceof EntityWither) {
                EntityWither wither = (EntityWither) entity;
                
                // Hypixel's lobby withers are:
                // 1. Invisible
                // 2. Have a custom name (the text they want to display)
                // 3. Often positioned high up (Y > 100)
                if (wither.isInvisible() && wither.hasCustomName()) {
                    return true; // Ignore this entity
                }
                
                // Additional check: if wither is above Y=100 and has custom name, likely a display wither
                if (wither.posY > 100.0 && wither.hasCustomName()) {
                    return true;
                }
            }
            
            // Filter out ender dragons used for lobby text displays
            if (entity instanceof EntityDragon) {
                EntityDragon dragon = (EntityDragon) entity;
                
                // Hypixel's lobby dragons are:
                // 1. Invisible
                // 2. Have a custom name (the text they want to display)
                // 3. Often positioned high up (Y > 100)
                if (dragon.isInvisible() && dragon.hasCustomName()) {
                    return true; // Ignore this entity
                }
                
                // Additional check: if dragon is above Y=100 and has custom name, likely a display dragon
                if (dragon.posY > 100.0 && dragon.hasCustomName()) {
                    return true;
                }
            }
            
            // Could add more filters here if needed (e.g., armor stands used for text, etc.)
            
        } catch (Throwable e) {
            // If error occurs, don't filter (show the entity)
        }
        
        return false; // Don't ignore by default
    }
    
    /**
     * Custom raycast to find entity player is looking at within specified distance
     */
    private static net.minecraft.entity.Entity getEntityPlayerIsLookingAt(Minecraft mc, double distance) {
        try {
            net.minecraft.entity.Entity viewer = mc.getRenderViewEntity();
            if (viewer == null || mc.theWorld == null) return null;
            
            // Get player's look vector
            net.minecraft.util.Vec3 eyePos = viewer.getPositionEyes(1.0F);
            net.minecraft.util.Vec3 lookVec = viewer.getLook(1.0F);
            net.minecraft.util.Vec3 reachVec = eyePos.addVector(
                lookVec.xCoord * distance,
                lookVec.yCoord * distance,
                lookVec.zCoord * distance
            );
            
            // Find entities along the raycast path
            net.minecraft.entity.Entity pointedEntity = null;
            double closestDistance = distance;
            
            java.util.List<net.minecraft.entity.Entity> entities = mc.theWorld.getEntitiesWithinAABBExcludingEntity(
                viewer,
                viewer.getEntityBoundingBox()
                    .addCoord(lookVec.xCoord * distance, lookVec.yCoord * distance, lookVec.zCoord * distance)
                    .expand(1.0, 1.0, 1.0)
            );
            
            for (net.minecraft.entity.Entity entity : entities) {
                if (entity == null || entity == viewer) continue;
                if (!(entity instanceof EntityLiving)) continue;
                
                // Check if raycast intersects with entity's bounding box
                net.minecraft.util.AxisAlignedBB boundingBox = entity.getEntityBoundingBox();
                net.minecraft.util.MovingObjectPosition intercept = boundingBox.calculateIntercept(eyePos, reachVec);
                
                if (intercept != null) {
                    double dist = eyePos.distanceTo(intercept.hitVec);
                    if (dist < closestDistance) {
                        closestDistance = dist;
                        pointedEntity = entity;
                    }
                }
            }
            
            return pointedEntity;
        } catch (Throwable e) {
            return null;
        }
    }
    
    /**
     * Draws the mob display panel with animated mob and health
     * Made public so settings GUI can render an accurate preview.
     */
    public static void drawMobPanel(Minecraft mc, EntityLivingBase mob) {
        drawMobPanel(mc, mob, false);
    }

    /**
     * Draws the mob display panel with animated mob and health.
     *
     * @param showNametagPreviewOverModel settings-GUI only: in-world style health / name rows above the 3D mob
     */
    public static void drawMobPanel(Minecraft mc, EntityLivingBase mob, boolean showNametagPreviewOverModel) {
        try {
            // Fixed bottom position - panel will expand UPWARD
            int panelBottomY = panelY;
            int x = panelX;
            
            int mobScale = computeMobGuiRenderScale(mob);
            int mobRenderWidth = computeMobGuiRenderWidth(mob, mobScale);
            int mobRenderHeight = computeMobGuiRenderHeight(mob, mobScale);
            
            int padding = 4; // Smaller padding for smaller mobs
            
            // Get mob name
            String mobName = mob.hasCustomName() ? mob.getCustomNameTag() : mob.getName();
            
            // Get health info
            float hp = Math.max(0f, mob.getHealth());
            float maxHp = Math.max(1f, mob.getMaxHealth());
            String hpText = formatPanelHealthText(hp, maxHp);
            
            int nameWidth = mc.fontRendererObj.getStringWidth(mobName);
            int hpWidth = mc.fontRendererObj.getStringWidth(hpText);
            int textWidth = Math.max(nameWidth, hpWidth);
            
            // Get potion effects (fresh from entity each frame, no caching)
            java.util.List<PotionEffect> effects = new java.util.ArrayList<>();
            if (showEffects) {
                try {
                    for (PotionEffect effect : mob.getActivePotionEffects()) {
                        if (effect != null && effect.getDuration() > 0) {
                            effects.add(effect);
                        }
                    }
                } catch (Exception e) {
                    // Silently handle
                }
            }
            int effectLines = effects.size();
            
            // Calculate max width including effects (with icon space)
            for (PotionEffect effect : effects) {
                String effectText = formatPotionEffect(effect);
                int effectWidth = mc.fontRendererObj.getStringWidth(effectText) + 16; // +16 for smaller icon space
                textWidth = Math.max(textWidth, effectWidth);
            }
            
            // Calculate panel dimensions based on actual render space needed
            // Mob area width should match where the mob actually renders (centered with padding on both sides)
            int mobAreaWidth = (mobRenderWidth) + padding * 3; // Extra padding for centered mob
            int panelWidth = mobAreaWidth + textWidth + padding * 2;
            
            // Calculate text area height (name + health + effects)
            int textAreaHeight = mc.fontRendererObj.FONT_HEIGHT * 2 + 4; // Name + health
            if (effectLines > 0) {
                textAreaHeight += effectLines * (mc.fontRendererObj.FONT_HEIGHT + 2);
            }
            
            // Panel height - use EXACT mob render height (already accounts for rotation)
            int mobAreaHeight = mobRenderHeight + padding * 2; // Tight padding since mobRenderHeight is already calculated correctly
            int panelHeight = Math.max(mobAreaHeight, textAreaHeight + padding * 2);
            
            // Calculate top Y position (panel expands UPWARD from bottom)
            int y = panelBottomY - panelHeight;

            GlStateManager.pushMatrix();
            GlStateManager.translate(x, panelBottomY, 0.0F);
            GlStateManager.scale(panelScale, panelScale, 1.0F);
            GlStateManager.translate(-x, -panelBottomY, 0.0F);
            HudEditorBackgroundMask.pushTransform(x, panelBottomY, panelScale);
            
            if (backgroundOpacity > 0) {
                int bgColor = (backgroundOpacity << 24) | (backgroundColor & 0xFFFFFF);
                HudEditorBackgroundMask.drawRect(x, y, x + panelWidth, y + panelHeight, bgColor);
            }
            
            // Render the mob entity in the panel (with animations)
            // Center mob horizontally in the mob area, position at bottom vertically
            int mobX = x + mobAreaWidth / 2; // Center in mob area
            int mobY = panelBottomY - padding * 2; // Align to bottom with padding
            renderMobInGui(mc, mob, mobX, mobY, mobScale);

            if (showNametagPreviewOverModel && isNametagMode()) {
                if (isCombinedNametagMode()) {
                    drawGuiCombinedNametagPreviewOverMob(mc, mob, mobX, mobY, mobRenderHeight, mobScale);
                } else {
                    drawGuiHealthNametagPreviewOverMob(mc, mob, mobX, mobY, mobRenderHeight, mobScale);
                }
            }
            beginHudForegroundOverMob();
            try {
                // Draw mob name and health text over the 3D model.
                int textX = x + mobAreaWidth + padding;
                int textY = y + padding;
                HudModuleTextDraw.draw(mc.fontRendererObj, mobName, textX, textY, 0xFFFFFF, textShadow);
                HudModuleTextDraw.draw(mc.fontRendererObj, hpText, textX, textY + mc.fontRendererObj.FONT_HEIGHT + 2, 0xFFFFFF,
                        textShadow);
                
                // Draw potion effects below health with icons
                if (!effects.isEmpty()) {
                    int effectY = textY + mc.fontRendererObj.FONT_HEIGHT * 2 + 4;
                    for (PotionEffect effect : effects) {
                        // Draw potion effect icon (smaller size, centered with text)
                        int iconSize = 12; // Smaller icon
                        int iconY = effectY + (mc.fontRendererObj.FONT_HEIGHT - iconSize) / 2; // Center vertically with text
                        drawPotionEffectIcon(mc, effect, textX - 2, iconY, iconSize);
                        
                        // Draw effect text (offset to the right of icon)
                        String effectText = formatPotionEffect(effect);
                        HudModuleTextDraw.draw(mc.fontRendererObj, effectText, textX + iconSize + 2, effectY, 0xFFFFFF,
                                textShadow);
                        effectY += mc.fontRendererObj.FONT_HEIGHT + 2;
                    }
                }
            } finally {
                endHudForegroundOverMob();
            }
            HudEditorBackgroundMask.popTransform();
            GlStateManager.popMatrix();
            
        } catch (Throwable e) {
            // Silently catch errors
        }
    }

    /**
     * Settings / HUD-editor preview for nametag-only mode: mini cow at the configured panel anchor with a scaled
     * health row above it (no side panel, no mob name row).
     */
    public static void drawNametagOnlyGuiPreview(Minecraft mc, EntityLivingBase mob) {
        if (mc == null || mob == null) {
            return;
        }
        try {
            int panelBottomY = panelY;
            int x = panelX;
            int mobScale = computeMobGuiRenderScale(mob);
            int mobRenderWidth = computeMobGuiRenderWidth(mob, mobScale);
            int mobRenderHeight = computeMobGuiRenderHeight(mob, mobScale);
            int padding = 4;
            int mobAreaWidth = mobRenderWidth + padding * 3;
            int mobX = x + mobAreaWidth / 2;
            int mobY = panelBottomY - padding * 2;

            GlStateManager.pushMatrix();
            GlStateManager.translate(x, panelBottomY, 0.0F);
            GlStateManager.scale(panelScale, panelScale, 1.0F);
            GlStateManager.translate(-x, -panelBottomY, 0.0F);

            renderMobInGui(mc, mob, mobX, mobY, mobScale);
            drawGuiHealthNametagPreviewOverMob(mc, mob, mobX, mobY, mobRenderHeight, mobScale);

            GlStateManager.popMatrix();
        } catch (Throwable ignored) {
        }
    }
    
    /**
     * Renders an entity in GUI space with animations
     * Similar to how inventory screens render the player
     */
    public static void renderMobInGui(Minecraft mc, EntityLivingBase entity, int posX, int posY, int scale) {
        try {
            renderingGuiMobPreview = true;
            GlStateManager.enableColorMaterial();
            GlStateManager.pushMatrix();
            GlStateManager.translate(posX, posY, 50.0F);
            GlStateManager.scale(-scale, scale, scale);
            GlStateManager.rotate(180.0F, 0.0F, 0.0F, 1.0F);
            
            float f = entity.renderYawOffset;
            float f1 = entity.rotationYaw;
            float f2 = entity.rotationPitch;
            float f3 = entity.prevRotationYawHead;
            float f4 = entity.rotationYawHead;
            
            // Check if entity has invisibility effect
            boolean hasInvisibility = entity.isInvisible();
            float renderAlpha = 1.0F;
            
            // Store and hide nametag (we show name in UI text instead)
            boolean oldAlwaysRenderNameTag = entity.getAlwaysRenderNameTag();
            boolean hadCustomName = entity.hasCustomName();
            String oldCustomName = hadCustomName ? entity.getCustomNameTag() : "";
            
            // Temporarily clear custom name and disable nametag rendering
            entity.setAlwaysRenderNameTag(false);
            if (hadCustomName) {
                entity.setCustomNameTag(""); // Clear custom name temporarily
            }
            
            // If invisible, temporarily remove invisibility for rendering with opacity
            if (hasInvisibility) {
                renderAlpha = 0.4F; // 40% opacity for invisible entities
                entity.setInvisible(false); // Temporarily disable invisibility
            }
            
            // Calculate red tint when mob is hurt
            float redTint = 1.0F;
            float greenTint = 1.0F;
            float blueTint = 1.0F;
            
            if (entity.hurtTime > 0) {
                // Add red tint based on hurt time (much more intense)
                float hurtIntensity = entity.hurtTime / 10.0F; // 0.0 to 1.0 (full intensity)
                redTint = 1.0F;
                greenTint = 1.0F - (hurtIntensity * 0.8F); // Reduce green significantly (80%)
                blueTint = 1.0F - (hurtIntensity * 0.8F);  // Reduce blue significantly (80%)
            }
            
            // Set up better lighting for entity rendering
            GlStateManager.rotate(135.0F, 0.0F, 1.0F, 0.0F);
            RenderHelper.enableStandardItemLighting();
            GlStateManager.rotate(-135.0F, 0.0F, 1.0F, 0.0F);
            
            // Rotate entity to show from above at a 3D angle
            GlStateManager.rotate(20.0F, 1.0F, 0.0F, 0.0F); // Tilt UP to look from above
            GlStateManager.rotate(45.0F, 0.0F, 1.0F, 0.0F); // Rotate 45 degrees for 3D look
            
            entity.renderYawOffset = 0.0F;
            entity.rotationYaw = 0.0F;
            entity.rotationPitch = 0.0F;
            entity.rotationYawHead = entity.rotationYaw;
            entity.prevRotationYawHead = entity.rotationYaw;
            
            GlStateManager.translate(0.0F, 0.0F, 0.0F);
            
            RenderManager rendermanager = mc.getRenderManager();
            rendermanager.setPlayerViewY(180.0F);
            rendermanager.setRenderShadow(false);
            
            // Store and disable debug rendering (hitboxes)
            boolean oldDebug = mc.getRenderManager().isDebugBoundingBox();
            mc.getRenderManager().setDebugBoundingBox(false);
            
            // Enable proper lighting
            GlStateManager.enableLighting();
            GlStateManager.enableLight(0);
            GlStateManager.enableLight(1);
            GlStateManager.enableAlpha();
            GlStateManager.depthMask(true);
            GlStateManager.enableDepth();
            GlStateManager.enableColorMaterial();
            GlStateManager.colorMaterial(1032, 5634);
            
            // Set up lighting with alpha and red tint for entity (accounting for invisibility and hurt)
            if (renderAlpha < 0.999F) {
                GlStateManager.enableBlend();
                GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            } else {
                GlStateManager.disableBlend();
            }
            GlStateManager.color(redTint, greenTint, blueTint, renderAlpha);
            
            // Render the entity with its animations
            rendermanager.renderEntityWithPosYaw(entity, 0.0D, 0.0D, 0.0D, 0.0F, 1.0F);
            
            // Restore invisibility state
            if (hasInvisibility) {
                entity.setInvisible(true);
            }
            
            // Restore nametag and custom name
            entity.setAlwaysRenderNameTag(oldAlwaysRenderNameTag);
            if (hadCustomName) {
                entity.setCustomNameTag(oldCustomName);
            }
            
            // Restore debug rendering state
            mc.getRenderManager().setDebugBoundingBox(oldDebug);
            
            rendermanager.setRenderShadow(true);
            
            // Restore entity rotation
            entity.renderYawOffset = f;
            entity.rotationYaw = f1;
            entity.rotationPitch = f2;
            entity.prevRotationYawHead = f3;
            entity.rotationYawHead = f4;
            
            GlStateManager.disableBlend();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.popMatrix();
            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableRescaleNormal();
            GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GlStateManager.disableTexture2D();
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);

        } catch (Throwable e) {
            // Silently fail
        } finally {
            renderingGuiMobPreview = false;
            // Entity path enables lighting; without this, following font/HUD draws use lit quads → flat gray.
            HudGlState.restoreForOverlayText();
        }
    }

    public static boolean isRenderingGuiMobPreview() {
        return renderingGuiMobPreview;
    }

    /** Scale used when {@link #renderMobInGui} draws a mob in the HUD panel / settings preview. */
    public static int computeMobGuiRenderScale(EntityLivingBase mob) {
        if (mob instanceof EntityDragon) {
            return OVERSIZED_DRAGON_GUI_SCALE;
        }
        if (mob instanceof EntityGhast) {
            return OVERSIZED_GHAST_GUI_SCALE;
        }
        float mobHeight = mob == null ? 1.4F : mob.height;
        return (int) Math.max(18, Math.min(25, mobHeight * 10));
    }

    public static int computeMobGuiRenderWidth(EntityLivingBase mob, int mobScale) {
        float mobWidth = mob == null ? 0.9F : mob.width;
        float horizontalSize = (float) Math.sqrt(mobWidth * mobWidth + mobWidth * mobWidth);
        int mobRenderWidth = (int) (horizontalSize * mobScale);
        return Math.max(20, Math.min(55, mobRenderWidth));
    }

    /** Vertical screen space occupied by the rotated GUI mob preview. */
    public static int computeMobGuiRenderHeight(EntityLivingBase mob, int mobScale) {
        float mobHeight = mob == null ? 1.4F : mob.height;
        float mobWidth = mob == null ? 0.9F : mob.width;
        float verticalProjection = mobHeight * 1.3F;
        int mobRenderHeight = (int) (verticalProjection * mobScale);
        return Math.max(20, Math.min(70, mobRenderHeight));
    }

    private static void beginHudForegroundOverMob() {
        HudGlState.restoreForOverlayText();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
    }

    private static void endHudForegroundOverMob() {
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        HudGlState.restoreForOverlayText();
    }

    /**
     * Scaled health-only row above a GUI mob preview (settings HUD; no mob name line).
     */
    public static void drawGuiHealthNametagPreviewOverMob(Minecraft mc, EntityLivingBase mob, int mobCenterX,
            int mobFootY, int mobRenderHeight, int mobScale) {
        if (mc == null || mob == null || mc.fontRendererObj == null) {
            return;
        }
        FontRenderer fontRenderer = mc.fontRendererObj;
        int healthY = mobFootY - mobRenderHeight - 3;
        float hp = Math.max(0f, mob.getHealth());
        float maxHp = Math.max(1f, mob.getMaxHealth());
        float tagScale = Math.max(0.5F, Math.min(1.0F, mobScale / 25.0F));
        drawGuiCenteredNametagPlate(fontRenderer, mobCenterX, healthY, formatNametagHealthText(hp, maxHp), tagScale);
    }

    public static void drawGuiCombinedNametagPreviewOverMob(Minecraft mc, EntityLivingBase mob, int mobCenterX,
            int mobFootY, int mobRenderHeight, int mobScale) {
        if (mc == null || mob == null || mc.fontRendererObj == null) {
            return;
        }
        float hp = Math.max(0f, mob.getHealth());
        float maxHp = Math.max(1f, mob.getMaxHealth());
        String label = formatCombinedNametagText(mob, hp, maxHp);
        if (label == null || label.trim().isEmpty()) {
            return;
        }
        int healthY = mobFootY - mobRenderHeight - 3;
        float tagScale = Math.max(0.5F, Math.min(1.0F, mobScale / 25.0F));
        drawGuiCenteredNametagPlate(mc.fontRendererObj, mobCenterX, healthY, label, tagScale);
    }

    private static void drawGuiCenteredNametagPlate(FontRenderer fontRenderer, int centerX, int textY, String text,
            float scale) {
        if (fontRenderer == null || text == null || text.isEmpty()) {
            return;
        }
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        try {
            if (scale < 0.98F) {
                GlStateManager.pushMatrix();
                GlStateManager.translate((float) centerX, (float) textY + 4.0F, 0.0F);
                GlStateManager.scale(scale, scale, 1.0F);
                GlStateManager.translate((float) -centerX, (float) -(textY + 4), 0.0F);
            }
            int textW = fontRenderer.getStringWidth(text);
            int textX = centerX - textW / 2;
            int left = textX - 1;
            int right = textX + textW + 1;
            int top = textY - 1;
            int bottom = textY + 8;

            HudGlState.restoreForOverlayText();
            GlStateManager.disableTexture2D();
            Gui.drawRect(left, top, right, bottom, GUI_NAMETAG_PLATE_BG);
            GlStateManager.enableTexture2D();

            fontRenderer.drawString(text, textX, textY, 0xFFFFFFFF);
            if (scale < 0.98F) {
                GlStateManager.popMatrix();
            }
        } finally {
            GlStateManager.depthMask(true);
            GlStateManager.enableDepth();
        }
    }
    
    /**
     * Short in-world label: colored HP + red heart (e.g. {@code 2 ♥}).
     */
    public static String formatNametagHealthText(float hp, float maxHp) {
        try {
            int currentHp = (int) Math.ceil(hp);
            EnumChatFormatting col;
            float percentage = hp / Math.max(1f, maxHp);
            if (percentage >= 0.75f) {
                col = EnumChatFormatting.GREEN;
            } else if (percentage >= 0.40f) {
                col = EnumChatFormatting.YELLOW;
            } else {
                col = EnumChatFormatting.RED;
            }
            return col.toString() + currentHp + " " + EnumChatFormatting.RED + "\u2764" + EnumChatFormatting.RESET;
        } catch (Throwable e) {
            int currentHp = (int) Math.ceil(hp);
            return "\u00A7c" + currentHp + " \u00A7c\u2764\u00A7r";
        }
    }

    /** Single nametag row: mob name + colored HP heart suffix (BOTH2 mode). */
    public static String formatCombinedNametagText(EntityLivingBase entity, float hp, float maxHp) {
        String name = MobNametags.formatLabelForRender(entity);
        if (name == null || name.trim().isEmpty()) {
            name = MobNametags.resolveNametagName(entity);
            if (name != null && !name.trim().isEmpty()) {
                name = EnumChatFormatting.WHITE + name + EnumChatFormatting.RESET;
            }
        }
        if (name == null || name.trim().isEmpty()) {
            return formatNametagHealthText(hp, maxHp);
        }
        return name + " " + formatNametagHealthText(hp, maxHp);
    }

    /**
     * Formats health text with color and heart icon for the HUD panel
     */
    public static String formatPanelHealthText(float hp, float maxHp) {
        try {
            int currentHp = (int) Math.ceil(hp);
            int maxHpShown = (int) Math.ceil(maxHp);
            EnumChatFormatting col;
            
            // Color based on percentage of max health
            float percentage = hp / maxHp;
            if (percentage >= 0.75f) {
                col = EnumChatFormatting.GREEN;
            } else if (percentage >= 0.40f) {
                col = EnumChatFormatting.YELLOW;
            } else {
                col = EnumChatFormatting.RED;
            }
            
            // Format as current/max with heart icon
            return col.toString() + currentHp + "/" + maxHpShown + " " + EnumChatFormatting.RED + "\u2764" + EnumChatFormatting.RESET;
        } catch (Throwable e) {
            int currentHp = (int) Math.ceil(hp);
            int maxHpShown = (int) Math.ceil(maxHp);
            return currentHp + "/" + maxHpShown + " " + "\u00A7c\u2764\u00A7r";
        }
    }
    
    /**
     * Formats a potion effect with name, level, and duration
     */
    private static String formatPotionEffect(PotionEffect effect) {
        try {
            Potion potion = Potion.potionTypes[effect.getPotionID()];
            String name = potion.getName();
            
            // Remove "potion." prefix if present
            if (name.startsWith("potion.")) {
                name = name.substring(7);
            }
            
            // Convert camelCase to proper display names
            name = formatPotionDisplayName(name);
            
            // Capitalize first letter
            if (name.length() > 0) {
                name = name.substring(0, 1).toUpperCase() + name.substring(1);
            }
            
            // Get level (amplifier + 1) and convert to Roman numerals
            int level = effect.getAmplifier() + 1;
            String levelStr = level > 1 ? " " + intToRoman(level) : "";
            
            // Get duration more accurately - round up partial seconds
            int totalTicks = effect.getDuration();
            String duration;
            
            // Match vanilla 1.8.x "max" duration display (**:**).
            // Hypixel often uses ~32767 ticks (shows as 27:19 when formatted normally).
            if (totalTicks > 32147) {
                duration = "**:**";
            } else {
                int totalSeconds = (totalTicks + 19) / 20; // Round up to nearest second
                int minutes = totalSeconds / 60;
                int remainingSeconds = totalSeconds % 60;
                duration = String.format("%d:%02d", minutes, remainingSeconds);
            }
            
            // Color code based on effect type
            EnumChatFormatting color = potion.isBadEffect() ? 
                EnumChatFormatting.RED : 
                EnumChatFormatting.AQUA;
            
            return color + name + levelStr + EnumChatFormatting.GRAY + " (" + duration + ")";
        } catch (Throwable e) {
            return EnumChatFormatting.GRAY + "Effect";
        }
    }
    
    /**
     * Draws a potion effect icon
     */
    private static void drawPotionEffectIcon(Minecraft mc, PotionEffect effect, int x, int y, int size) {
        try {
            Potion potion = Potion.potionTypes[effect.getPotionID()];
            if (potion == null) {
                return;
            }
            GlStateManager.pushMatrix();
            MoreHudPotionIcons.drawPotionIcon(mc, potion, x, y, size);
            GlStateManager.popMatrix();
        } catch (Throwable ignored) {
        }
    }
    
    /**
     * Converts raw potion names to proper display names
     */
    private static String formatPotionDisplayName(String rawName) {
        // Handle common potion effect name mappings
        switch (rawName.toLowerCase()) {
            case "movespeed":
                return "Speed";
            case "moveslowdown":
                return "Slowness";
            case "digspeed":
                return "Haste";
            case "digslowdown":
                return "Mining Fatigue";
            case "damageboost":
                return "Strength";
            case "heal":
                return "Instant Health";
            case "harm":
                return "Instant Damage";
            case "jump":
                return "Jump Boost";
            case "regeneration":
                return "Regeneration";
            case "resistance":
                return "Resistance";
            case "fireresistance":
                return "Fire Resistance";
            case "waterbreathing":
                return "Water Breathing";
            case "invisibility":
                return "Invisibility";
            case "blindness":
                return "Blindness";
            case "nightvision":
                return "Night Vision";
            case "hunger":
                return "Hunger";
            case "weakness":
                return "Weakness";
            case "poison":
                return "Poison";
            case "wither":
                return "Wither";
            case "healthboost":
                return "Health Boost";
            case "absorption":
                return "Absorption";
            case "saturation":
                return "Saturation";
            case "glowing":
                return "Glowing";
            case "levitation":
                return "Levitation";
            case "luck":
                return "Luck";
            case "unluck":
                return "Bad Luck";
            default:
                // For unknown effects, convert camelCase to proper format
                return convertCamelCaseToDisplay(rawName);
        }
    }
    
    /**
     * Converts camelCase strings to proper display format
     */
    private static String convertCamelCaseToDisplay(String camelCase) {
        if (camelCase == null || camelCase.isEmpty()) {
            return camelCase;
        }
        
        StringBuilder result = new StringBuilder();
        result.append(Character.toUpperCase(camelCase.charAt(0)));
        
        for (int i = 1; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c)) {
                result.append(" ").append(c);
            } else {
                result.append(c);
            }
        }
        
        return result.toString();
    }
    
    /**
     * Converts an integer to Roman numerals
     */
    private static String intToRoman(int num) {
        if (num <= 0 || num > 3999) {
            return String.valueOf(num); // Fallback to regular number for invalid ranges
        }
        
        String[] thousands = {"", "M", "MM", "MMM"};
        String[] hundreds = {"", "C", "CC", "CCC", "CD", "D", "DC", "DCC", "DCCC", "CM"};
        String[] tens = {"", "X", "XX", "XXX", "XL", "L", "LX", "LXX", "LXXX", "XC"};
        String[] ones = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX"};
        
        return thousands[num / 1000] + 
               hundreds[(num % 1000) / 100] + 
               tens[(num % 100) / 10] + 
               ones[num % 10];
    }
    
    /**
     * Draws a textured rectangle with custom texture size (helper method)
     */
    private static void drawTexturedModalRect(int x, int y, int textureX, int textureY, int width, int height, int textureWidth, int textureHeight) {
        try {
            float zLevel = 0.0F;
            float f = 0.00390625F; // 1/256
            float f1 = 0.00390625F;
            Tessellator tessellator = Tessellator.getInstance();
            WorldRenderer worldrenderer = tessellator.getWorldRenderer();
            worldrenderer.begin(7, DefaultVertexFormats.POSITION_TEX);
            worldrenderer.pos((double)(x), (double)(y + height), (double)zLevel)
                .tex((double)((float)(textureX) * f), (double)((float)(textureY + textureHeight) * f1)).endVertex();
            worldrenderer.pos((double)(x + width), (double)(y + height), (double)zLevel)
                .tex((double)((float)(textureX + textureWidth) * f), (double)((float)(textureY + textureHeight) * f1)).endVertex();
            worldrenderer.pos((double)(x + width), (double)(y), (double)zLevel)
                .tex((double)((float)(textureX + textureWidth) * f), (double)((float)(textureY) * f1)).endVertex();
            worldrenderer.pos((double)(x), (double)(y), (double)zLevel)
                .tex((double)((float)(textureX) * f), (double)((float)(textureY) * f1)).endVertex();
            tessellator.draw();
        } catch (Throwable e) {
            // Silently fail
        }
    }
}
