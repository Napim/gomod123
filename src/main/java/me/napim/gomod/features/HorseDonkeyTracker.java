package me.napim.gomod.features;

import me.napim.gomod.util.HudGlState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.init.Items;
import net.minecraft.init.Blocks;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;
import me.napim.gomod.gui.HudModuleTextDraw;
import me.napim.gomod.gui.HudEditorBackgroundMask;
import me.napim.gomod.render.VanillaEntityLabelRenderer;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/**
 * Tracks the player's horse/donkey across dismounts, renders a distance HUD,
 * and plays a sound when the mount visibly snaps back near the player.
 */
public final class HorseDonkeyTracker {

    private static final double TELEPORT_FAR_DISTANCE = 10.0D;
    private static final double TELEPORT_MIN_SNAP_DISTANCE = 8.0D;
    private static final double TELEPORT_MIN_DISTANCE_GAIN = 8.0D;
    private static final long TELEPORT_SOUND_COOLDOWN_MS = 1200L;

    private static boolean distanceHudEnabled = false;
    private static boolean showBackground = true;
    private static boolean teleportSoundEnabled = true;
    private static int backgroundOpacity = 120;
    private static int backgroundColor = 0;
    private static int panelX = 583;
    private static int panelY = 453;
    private static float scale = 0.82530123f;
    private static boolean foodNametagEnabled = false;
    private static boolean textShadow = true;

    private static UUID trackedMountUuid = null;
    private static int trackedEntityId = Integer.MIN_VALUE;
    private static World trackedWorld = null;
    private static String trackedMountLabel = null;
    private static double trackedDistance = -1.0D;

    private static UUID lastSampleMountUuid = null;
    private static double lastDistanceToPlayer = -1.0D;
    private static double lastMountX = 0.0D;
    private static double lastMountY = 0.0D;
    private static double lastMountZ = 0.0D;
    private static long lastTeleportSoundMs = 0L;
    /** False until we have stored at least one real mount position; avoids false teleports when lastMount was never initialized. */
    private static boolean hasSealedLastMountSample = false;

    private HorseDonkeyTracker() {
    }

    public static void loadSettings() {
        try {
            distanceHudEnabled = ConfigManager.isHorseDonkeyDistanceHudEnabled();
            showBackground = ConfigManager.isHorseDonkeyShowBackground();
            teleportSoundEnabled = ConfigManager.isHorseDonkeyTeleportSoundEnabled();
            foodNametagEnabled = ConfigManager.isHorseFoodNametagEnabled();
            backgroundOpacity = ConfigManager.getHorseDonkeyBackgroundOpacity();
            backgroundColor = ConfigManager.getHorseDonkeyBackgroundColor();
            panelX = ConfigManager.getHorseDonkeyPanelX();
            panelY = ConfigManager.getHorseDonkeyPanelY();
            scale = ConfigManager.getHorseDonkeyScale();
            textShadow = ConfigManager.isHorseDonkeyTextShadow();
        } catch (Throwable t) {
            System.err.println("[HorseDonkeyTracker] Error loading settings: " + t.getMessage());
        }
    }

    public static boolean isDistanceHudEnabled() {
        return distanceHudEnabled;
    }

    public static void beginFoodNametagFrame() {
    }

    public static void setDistanceHudEnabled(boolean enabled) {
        distanceHudEnabled = enabled;
        ConfigManager.setHorseDonkeyDistanceHudEnabled(enabled);
    }

    public static boolean isShowBackground() {
        return showBackground;
    }

    public static void setShowBackground(boolean value) {
        showBackground = value;
        ConfigManager.setHorseDonkeyShowBackground(value);
    }

    public static boolean isTeleportSoundEnabled() {
        return teleportSoundEnabled;
    }

    public static void setTeleportSoundEnabled(boolean value) {
        teleportSoundEnabled = value;
        ConfigManager.setHorseDonkeyTeleportSoundEnabled(value);
    }

    public static int getBackgroundOpacity() {
        return backgroundOpacity;
    }

    public static void setBackgroundOpacity(int value) {
        backgroundOpacity = Math.max(0, Math.min(255, value));
        ConfigManager.setHorseDonkeyBackgroundOpacity(backgroundOpacity);
    }

    public static int getBackgroundColor() {
        return backgroundColor;
    }

    public static void setBackgroundColor(int rgb) {
        backgroundColor = rgb & 0xFFFFFF;
        ConfigManager.setHorseDonkeyBackgroundColor(backgroundColor);
    }

    public static int getPanelX() {
        return panelX;
    }

    public static int getPanelY() {
        return panelY;
    }

    public static float getScale() {
        return scale;
    }

    public static void setScale(float value) {
        scale = Math.max(0.5f, Math.min(4.0f, value));
        ConfigManager.setHorseDonkeyScale(scale);
    }

    public static void setCustomPosition(int x, int y) {
        panelX = x;
        panelY = y;
        ConfigManager.setHorseDonkeyPanelX(x);
        ConfigManager.setHorseDonkeyPanelY(y);
    }

    public static void resetPosition() {
        panelX = 583;
        panelY = 453;
        ConfigManager.setHorseDonkeyPanelX(panelX);
        ConfigManager.setHorseDonkeyPanelY(panelY);
    }

    public static boolean isAnyFeatureEnabled() {
        return distanceHudEnabled || teleportSoundEnabled || foodNametagEnabled;
    }

    public static String getTrackedMountLabel() {
        return trackedMountLabel;
    }

    public static double getTrackedDistance() {
        return trackedDistance;
    }

    public static void clientTick() {
        if (!isAnyFeatureEnabled()) {
            clearRuntimeState();
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            clearRuntimeState();
            return;
        }

        if (trackedWorld != mc.theWorld) {
            clearTrackingReference();
            trackedWorld = mc.theWorld;
        }

        EntityHorse mount = findRelevantMount(mc);
        if (mount == null) {
            trackedMountLabel = null;
            trackedDistance = -1.0D;
            lastSampleMountUuid = null;
            lastDistanceToPlayer = -1.0D;
            return;
        }

        rememberTrackedMount(mount);
        trackedMountLabel = getMountLabel(mount);
        trackedDistance = mount == mc.thePlayer.ridingEntity ? 0.0D : mc.thePlayer.getDistanceToEntity(mount);

        if (teleportSoundEnabled) {
            maybePlayTeleportSound(mc, mount, trackedDistance);
        }

        lastSampleMountUuid = mount.getUniqueID();
        lastDistanceToPlayer = trackedDistance;
        lastMountX = mount.posX;
        lastMountY = mount.posY;
        lastMountZ = mount.posZ;
        hasSealedLastMountSample = true;
    }

    public static void render() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        if (!distanceHudEnabled || trackedMountLabel == null || trackedDistance < 0.0D) {
            return;
        }

        if (trackedDistance <= 0.05D) {
            return;
        }

        try {
            if (mc.currentScreen instanceof me.napim.gomod.gui.HorseDonkeySettingsGUI) {
                return;
            }
        } catch (Throwable ignored) {
        }

        drawDistancePanel(mc, trackedMountLabel, trackedDistance, resolveTrackedMountAngle(mc));
    }

    public static void drawPreviewPanel(Minecraft mc) {
        String label = trackedMountLabel != null ? trackedMountLabel : "Horse";
        double distance = trackedDistance >= 0.0D ? trackedDistance : 17.4D;
        float previewAngle = (System.currentTimeMillis() % 3600L) / 10.0f;
        drawDistancePanel(mc, label, distance, previewAngle);
    }

    /** Unscaled panel size for {@link #drawDistancePanel} (width, height). */
    public static int[] measureDistancePanelSize(Minecraft mc, String mountLabel, double distance) {
        if (mc == null || mc.fontRendererObj == null || mountLabel == null) {
            return new int[] { 0, 0 };
        }
        String title = mountLabel + " distance";
        String valueText = formatDistance(distance);
        String blocksText = " blocks";
        int padding = 4;
        int titleWidth = mc.fontRendererObj.getStringWidth(title);
        int iconSize = Math.max(14, mc.fontRendererObj.FONT_HEIGHT + 5);
        int rowGap = 3;
        int distanceWidth = mc.fontRendererObj.getStringWidth(valueText)
                + mc.fontRendererObj.getStringWidth(blocksText);
        int rowWidth = iconSize + rowGap + distanceWidth;
        int rowHeight = Math.max(iconSize, mc.fontRendererObj.FONT_HEIGHT);
        int width = Math.max(titleWidth, rowWidth) + padding * 2;
        int height = padding * 2 + mc.fontRendererObj.FONT_HEIGHT + 2 + rowHeight;
        return new int[] { width, height };
    }

    public static void drawDistancePanel(Minecraft mc, String mountLabel, double distance, Float cursorAngleDeg) {
        if (mc == null || mc.fontRendererObj == null || mountLabel == null) {
            return;
        }

        String title = mountLabel + " distance";
        String valueText = formatDistance(distance);
        String blocksText = " blocks";
        int titleColor = hudTextColor(ConfigManager.getHorseDonkeyHudTitleColor());
        int valueColor = hudTextColor(ConfigManager.getHorseDonkeyHudValueColor());
        int blocksColor = hudTextColor(ConfigManager.getHorseDonkeyHudBlocksColor());

        int[] size = measureDistancePanelSize(mc, mountLabel, distance);
        int width = size[0];
        int height = size[1];
        int padding = 4;
        int titleWidth = mc.fontRendererObj.getStringWidth(title);
        int iconSize = Math.max(14, mc.fontRendererObj.FONT_HEIGHT + 5);
        int rowGap = 3;
        int distanceWidth = mc.fontRendererObj.getStringWidth(valueText)
                + mc.fontRendererObj.getStringWidth(blocksText);
        int rowWidth = iconSize + rowGap + distanceWidth;
        int rowHeight = Math.max(iconSize, mc.fontRendererObj.FONT_HEIGHT);

        GlStateManager.pushMatrix();
        GlStateManager.translate(panelX, panelY, 0.0F);
        GlStateManager.scale(scale, scale, 1.0F);
        GlStateManager.translate(-panelX, -panelY, 0.0F);
        HudEditorBackgroundMask.pushTransform(panelX, panelY, scale);

        if (showBackground) {
            HudEditorBackgroundMask.drawRect(panelX, panelY, panelX + width, panelY + height,
                    (backgroundOpacity << 24) | (backgroundColor & 0xFFFFFF));
        }

        int titleX = panelX + (width - titleWidth) / 2;
        int titleY = panelY + padding;
        int rowTop = titleY + mc.fontRendererObj.FONT_HEIGHT + 2;
        int rowLeft = panelX + (width - rowWidth) / 2;
        int cursorTopY = rowTop + (rowHeight - iconSize) / 2;
        int cursorCenterX = rowLeft + iconSize / 2;
        int distanceX = rowLeft + iconSize + rowGap;
        int distanceY = rowTop + (rowHeight - mc.fontRendererObj.FONT_HEIGHT) / 2;

        HudGlState.restoreForOverlayText();
        HudModuleTextDraw.draw(mc.fontRendererObj, title, titleX, titleY, titleColor, textShadow);
        if (cursorAngleDeg != null) {
            PlayerTarget.renderShareInvDirectionCursor(mc, cursorCenterX, cursorTopY, iconSize, cursorAngleDeg, false);
            HudGlState.restoreForOverlayText();
        }
        HudModuleTextDraw.draw(mc.fontRendererObj, valueText, distanceX, distanceY, valueColor, textShadow);
        HudModuleTextDraw.draw(mc.fontRendererObj, blocksText,
                distanceX + mc.fontRendererObj.getStringWidth(valueText), distanceY, blocksColor, textShadow);
        HudEditorBackgroundMask.popTransform();
        GlStateManager.popMatrix();
        HudGlState.restoreForOverlayText();
    }

    private static Float resolveTrackedMountAngle(Minecraft mc) {
        if (mc == null || mc.thePlayer == null) {
            return null;
        }

        EntityHorse mount = findRelevantMount(mc);
        if (mount != null && mount != mc.thePlayer.ridingEntity) {
            return computeAngleDeg(mc, mount.posX, mount.posZ);
        }

        if (lastSampleMountUuid != null && trackedDistance > 0.05D) {
            return computeAngleDeg(mc, lastMountX, lastMountZ);
        }

        return null;
    }

    private static float computeAngleDeg(Minecraft mc, double targetX, double targetZ) {
        double dx = targetX - mc.thePlayer.posX;
        double dz = targetZ - mc.thePlayer.posZ;
        double angleToTargetDeg = Math.toDegrees(Math.atan2(dz, dx)) - 90.0D;
        return (float) (angleToTargetDeg - (double) mc.thePlayer.rotationYaw);
    }

    private static void maybePlayTeleportSound(Minecraft mc, EntityHorse mount, double currentDistance) {
        if (mc.thePlayer == null || mount == mc.thePlayer.ridingEntity) {
            return;
        }

        UUID mountUuid = mount.getUniqueID();
        if (mountUuid == null) {
            return;
        }
        boolean sameMountAsLastClientTick = mountUuid.equals(lastSampleMountUuid);
        boolean resumedSameTrackedMount = lastSampleMountUuid == null
                && hasSealedLastMountSample
                && trackedMountUuid != null
                && mountUuid.equals(trackedMountUuid);
        if (!sameMountAsLastClientTick && !resumedSameTrackedMount) {
            return;
        }

        boolean closingGap;
        if (lastDistanceToPlayer >= 0.0D) {
            closingGap = lastDistanceToPlayer >= TELEPORT_FAR_DISTANCE
                    && (lastDistanceToPlayer - currentDistance) >= TELEPORT_MIN_DISTANCE_GAIN;
        } else {
            // Mount missed a tick (e.g. unloaded); we lost lastDistance but still have last world position.
            closingGap = currentDistance < TELEPORT_FAR_DISTANCE;
        }
        if (!closingGap) {
            return;
        }

        // Require a big per-tick world delta only when we lack a previous distance sample. Otherwise the
        // client often applies recall as several smaller position steps (or updates the mount unevenly when
        // it is not the focused entity), so moved stays under the threshold even though distance collapsed in one tick.
        if (lastDistanceToPlayer < 0.0D) {
            double moved = distance3d(mount.posX - lastMountX, mount.posY - lastMountY, mount.posZ - lastMountZ);
            if (moved < TELEPORT_MIN_SNAP_DISTANCE) {
                return;
            }
        }

        long now = System.currentTimeMillis();
        if (now - lastTeleportSoundMs < TELEPORT_SOUND_COOLDOWN_MS) {
            return;
        }

        mc.thePlayer.playSound("mob.horse.leather", 1.0F, 0.5F);
        lastTeleportSoundMs = now;
    }

    private static EntityHorse findRelevantMount(Minecraft mc) {
        if (mc.thePlayer.ridingEntity instanceof EntityHorse) {
            EntityHorse mount = (EntityHorse) mc.thePlayer.ridingEntity;
            if (isSupportedMount(mount)) {
                return mount;
            }
        }

        EntityHorse trackedMount = resolveTrackedMount(mc.theWorld);
        if (trackedMount != null && isOwnedByPlayer(mc, trackedMount)) {
            return trackedMount;
        }

        EntityHorse ownedMount = findOwnedMount(mc);
        if (ownedMount != null) {
            return ownedMount;
        }

        return trackedMount;
    }

    private static EntityHorse resolveTrackedMount(World world) {
        if (world == null) {
            return null;
        }

        if (trackedEntityId != Integer.MIN_VALUE) {
            Entity byId = world.getEntityByID(trackedEntityId);
            if (byId instanceof EntityHorse && isSupportedMount((EntityHorse) byId) && !byId.isDead) {
                return (EntityHorse) byId;
            }
        }

        if (trackedMountUuid == null) {
            return null;
        }

        for (Entity entity : (List<Entity>) world.getLoadedEntityList()) {
            if (!(entity instanceof EntityHorse) || entity.isDead) {
                continue;
            }

            EntityHorse horse = (EntityHorse) entity;
            if (isSupportedMount(horse) && trackedMountUuid.equals(horse.getUniqueID())) {
                return horse;
            }
        }

        return null;
    }

    private static EntityHorse findOwnedMount(Minecraft mc) {
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            return null;
        }

        EntityHorse closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (Entity entity : (List<Entity>) mc.theWorld.getLoadedEntityList()) {
            if (!(entity instanceof EntityHorse) || entity.isDead) {
                continue;
            }

            EntityHorse horse = (EntityHorse) entity;
            if (!isSupportedMount(horse) || !isOwnedByPlayer(mc, horse)) {
                continue;
            }

            if (trackedMountUuid != null && trackedMountUuid.equals(horse.getUniqueID())) {
                return horse;
            }

            double distance = mc.thePlayer.getDistanceToEntity(horse);
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = horse;
            }
        }
        return closest;
    }

    private static boolean isOwnedByPlayer(Minecraft mc, EntityHorse horse) {
        if (mc == null || mc.thePlayer == null || horse == null) {
            return false;
        }

        try {
            if (!horse.isTame()) {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }

        Object ownerValue = invokeNoArg(horse, "getOwnerUniqueId");
        if (!(ownerValue instanceof UUID)) {
            ownerValue = invokeNoArg(horse, "func_184780_dh");
        }
        if (ownerValue instanceof UUID) {
            return ownerValue.equals(mc.thePlayer.getUniqueID());
        }

        if (!(ownerValue instanceof String)) {
            ownerValue = invokeNoArg(horse, "getOwnerId");
        }
        if (!(ownerValue instanceof String)) {
            ownerValue = invokeNoArg(horse, "getOwner");
        }
        if (!(ownerValue instanceof String)) {
            ownerValue = invokeNoArg(horse, "func_145798_i");
        }
        if (ownerValue instanceof String) {
            String ownerText = ((String) ownerValue).trim();
            return ownerText.equalsIgnoreCase(mc.thePlayer.getName())
                    || ownerText.equalsIgnoreCase(mc.thePlayer.getUniqueID().toString());
        }

        return false;
    }

    private static boolean isSupportedMount(EntityHorse horse) {
        if (horse == null) {
            return false;
        }

        int type = 0;
        try {
            type = horse.getHorseType();
        } catch (Throwable ignored) {
        }
        return type == 0 || type == 1 || type == 2;
    }

    private static String getMountLabel(EntityHorse horse) {
        int type = 0;
        try {
            type = horse.getHorseType();
        } catch (Throwable ignored) {
        }

        if (type == 1) {
            return "Donkey";
        }
        if (type == 2) {
            return "Mule";
        }
        return "Horse";
    }

    private static int hudTextColor(int rgb) {
        return 0xFF000000 | (rgb & 0xFFFFFF);
    }

    private static String formatDistance(double distance) {
        return String.format("%.1f", Math.max(0.0D, distance));
    }

    private static void rememberTrackedMount(EntityHorse mount) {
        trackedMountUuid = mount.getUniqueID();
        trackedEntityId = mount.getEntityId();
        trackedWorld = mount.worldObj;
    }

    private static void clearTrackingReference() {
        trackedMountUuid = null;
        trackedEntityId = Integer.MIN_VALUE;
        trackedWorld = null;
        trackedMountLabel = null;
        trackedDistance = -1.0D;
        lastSampleMountUuid = null;
        lastDistanceToPlayer = -1.0D;
        lastMountX = 0.0D;
        lastMountY = 0.0D;
        lastMountZ = 0.0D;
        hasSealedLastMountSample = false;
    }

    private static void clearRuntimeState() {
        clearTrackingReference();
        lastTeleportSoundMs = 0L;
    }

    private static Object invokeNoArg(EntityHorse horse, String methodName) {
        try {
            Method method = horse.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(horse);
        } catch (Throwable ignored) {
            return null;
        }
    }


    public static boolean isFoodNametagEnabled() {
        return foodNametagEnabled;
    }

    public static void setFoodNametagEnabled(boolean enabled) {
        foodNametagEnabled = enabled;
        ConfigManager.setHorseFoodNametagEnabled(enabled);
    }

    public static boolean isTextShadow() {
        return textShadow;
    }

    public static void setTextShadow(boolean shadow) {
        textShadow = shadow;
        ConfigManager.setHorseDonkeyTextShadow(shadow);
    }

    public static void toggleTextShadow() {
        setTextShadow(!textShadow);
    }

    /**
     * True when this mount should show the food hint: feature on, damaged horse/donkey/mule,
     * not mob-preview GUI.
     */
    public static boolean eligibleForFoodNametagOverlay(EntityLivingBase entity) {
        if (!foodNametagEnabled) {
            return false;
        }
        if (MobHealthDisplay.isRenderingGuiMobPreview()) {
            return false;
        }
        if (entity == null || !(entity instanceof EntityHorse)) {
            return false;
        }
        EntityHorse horse = (EntityHorse) entity;
        if (!isSupportedMount(horse)) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return false;
        }
        // Don't show food nametag while riding — the horse HUD already covers it
        if (mc.thePlayer.ridingEntity == horse) {
            return false;
        }
        // Allow if it's our horse (tracked UUID or owner match)
        boolean isOurs = (trackedMountUuid != null && trackedMountUuid.equals(horse.getUniqueID()))
                || isOwnedByPlayer(mc, horse);
        if (!isOurs) {
            return false;
        }
        return horse.getHealth() < horse.getMaxHealth() - 1.0e-4F;
    }

    /**
     * The stable implementation renders from the world-label pass. Entity-pass hooks are intentionally no-op:
     * some clients call them with depth state that makes this row flicker or lose the through-wall pass.
     */
    public static void renderFoodNametag(EntityLivingBase entity, double x, double y, double z) {
    }

    public static void renderFoodNametagFallbackLabel(EntityLivingBase entity, double x, double y, double z) {
    }

    public static void renderWorldFoodNametags(float partialTicks) {
        if (!foodNametagEnabled) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.getRenderViewEntity() == null) {
            return;
        }
        Entity cam = mc.getRenderViewEntity();
        double cx = cam.lastTickPosX + (cam.posX - cam.lastTickPosX) * partialTicks;
        double cy = cam.lastTickPosY + (cam.posY - cam.lastTickPosY) * partialTicks;
        double cz = cam.lastTickPosZ + (cam.posZ - cam.lastTickPosZ) * partialTicks;

        for (Entity entity : (List<Entity>) mc.theWorld.getLoadedEntityList()) {
            if (!(entity instanceof EntityHorse) || entity.isDead) {
                continue;
            }
            EntityHorse horse = (EntityHorse) entity;
            if (!isSupportedMount(horse)) {
                continue;
            }

            double x = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks - cx;
            double y = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks - cy;
            double z = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks - cz;

            EntityLivingBase living = horse;
            if (!eligibleForFoodNametagOverlay(living)) {
                continue;
            }
            FoodRenderData data = buildFoodRenderData(horse);
            renderHorseFoodWorldLabel(horse, data, x, y, z);
        }
    }

    private static void renderHorseFoodWorldLabel(EntityHorse horse, FoodRenderData data, double x, double y, double z) {
        if (data == null || data.summaryText == null || data.summaryText.isEmpty()) {
            return;
        }
        VanillaEntityLabelRenderer.render(horse, data.summaryText, x, y, z, 96,
                foodNametagFootYExtra(horse), data.summaryColor, 1.0F, 1.0F, false, true);
    }

    private static double foodNametagFootYExtra(EntityHorse horse) {
        double footY = MobHealthDisplay.mobLabelBaseYOffset(horse);
        int rowsBelow = MobHealthDisplay.getRenderedNametagStackHeightRows(horse);
        if (rowsBelow <= 0 && shouldReserveHorseNameRow(horse)) {
            rowsBelow = 1;
        }
        return footY + rowsBelow * MobHealthDisplay.nametagRowSpacingWorldY();
    }

    private static boolean shouldReserveHorseNameRow(EntityHorse horse) {
        if (horse == null) {
            return false;
        }
        if (MobNametags.shouldRenderMobNametag(horse)) {
            return true;
        }
        try {
            return horse.hasCustomName();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static FoodRenderData buildFoodRenderData(EntityHorse horse) {
        Minecraft mc = Minecraft.getMinecraft();
        float missing = Math.max(0.0F, horse.getMaxHealth() - horse.getHealth());
        int[] playerCounts = getFoodCounts(mc);
        int[] hotbarCounts = getHotbarFoodCounts(mc);
        int[] heals = { 1, 2, 3, 3, 4, 10, 20 };
        Item[] items = {
            Items.sugar,
            Items.wheat,
            Items.apple,
            Items.bread,
            Items.golden_carrot,
            Items.golden_apple,
            Item.getItemFromBlock(Blocks.hay_block)
        };
        String[] shortNames = {
            "Sugar",
            "Wheat",
            "Apple",
            "Bread",
            "GCarrot",
            "GApple",
            "Hay"
        };

        java.util.List<ItemStack> iconList = new java.util.ArrayList<ItemStack>();
        java.util.List<Integer> neededList = new java.util.ArrayList<Integer>();
        java.util.List<Integer> haveList = new java.util.ArrayList<Integer>();

        int bestIndex = -1;
        int bestSourcePriority = Integer.MIN_VALUE;
        int bestNeeded = Integer.MAX_VALUE;
        boolean bestEnough = false;
        int bestHave = -1;

        for (int i = 0; i < items.length; i++) {
            int have = playerCounts[i];
            if (have <= 0) continue;
            Item item = items[i];
            if (item == null) continue;
            int needed = (int) Math.ceil(missing / heals[i]);
            int hotbarHave = hotbarCounts[i];
            int nonHotbarHave = Math.max(0, have - hotbarHave);
            iconList.add(new ItemStack(item, 1));
            neededList.add(Integer.valueOf(needed));
            haveList.add(Integer.valueOf(have));

            boolean enough = have >= needed;
            boolean avoidsHotbar = nonHotbarHave >= needed;
            boolean hasNonHotbar = nonHotbarHave > 0;
            boolean hasHotbar = hotbarHave > 0;
            int sourcePriority = hasHotbar ? 3 : (avoidsHotbar ? 2 : (hasNonHotbar ? 1 : 0));
            if (item == Items.golden_apple) {
                // Keep golden apples as a last resort whenever there is other food to feed the mount.
                sourcePriority = -1;
            }
            if (bestIndex < 0
                    || sourcePriority > bestSourcePriority
                    || (sourcePriority == bestSourcePriority && enough && !bestEnough)
                    || (sourcePriority == bestSourcePriority && enough == bestEnough && needed < bestNeeded)
                    || (sourcePriority == bestSourcePriority && enough == bestEnough
                        && needed == bestNeeded && have > bestHave)) {
                bestIndex = i;
                bestSourcePriority = sourcePriority;
                bestNeeded = needed;
                bestEnough = enough;
                bestHave = have;
            }
        }

        ItemStack[] icons = iconList.toArray(new ItemStack[iconList.size()]);
        int[] needed = new int[neededList.size()];
        int[] have = new int[haveList.size()];
        for (int i = 0; i < needed.length; i++) {
            needed[i] = neededList.get(i).intValue();
            have[i] = haveList.get(i).intValue();
        }

        String summaryText;
        int summaryColor;
        if (bestIndex >= 0) {
            int haveBest = playerCounts[bestIndex];
            summaryText = getFoodNameFormatting(bestIndex) + shortNames[bestIndex]
                    + EnumChatFormatting.WHITE + " x" + bestNeeded + " (" + haveBest + ")";
            summaryColor = 0xFFFFFF;
        } else {
            summaryText = EnumChatFormatting.RED + "No food";
            summaryColor = 0xFF5555;
        }
        return new FoodRenderData(icons, needed, have, summaryText, summaryColor);
    }

    private static String getFoodNameFormatting(int index) {
        switch (index) {
            case 1:
                return EnumChatFormatting.YELLOW.toString();
            case 2:
                return EnumChatFormatting.RED.toString();
            default:
                return EnumChatFormatting.WHITE.toString();
        }
    }

    private static int[] getFoodCounts(Minecraft mc) {
        int[] counts = new int[7];
        if (mc == null || mc.thePlayer == null) return counts;
        countFoodStacks(mc, counts, 0, mc.thePlayer.inventory.mainInventory.length);
        return counts;
    }

    private static int[] getHotbarFoodCounts(Minecraft mc) {
        int[] counts = new int[7];
        if (mc == null || mc.thePlayer == null) return counts;
        countFoodStacks(mc, counts, 0, Math.min(9, mc.thePlayer.inventory.mainInventory.length));
        return counts;
    }

    private static void countFoodStacks(Minecraft mc, int[] counts, int startInclusive, int endExclusive) {
        Item hayItem;
        try { hayItem = Item.getItemFromBlock(Blocks.hay_block); } catch (Throwable t) { hayItem = null; }
        Item[] targets = {
            Items.sugar, Items.wheat, Items.apple, Items.bread,
            Items.golden_carrot, Items.golden_apple, hayItem
        };
        ItemStack[] inventory = mc.thePlayer.inventory.mainInventory;
        int start = Math.max(0, startInclusive);
        int end = Math.min(endExclusive, inventory.length);
        for (int slot = start; slot < end; slot++) {
            ItemStack stack = inventory[slot];
            if (stack == null) continue;
            Item item = stack.getItem();
            for (int i = 0; i < targets.length; i++) {
                if (targets[i] != null && item == targets[i]) {
                    counts[i] += stack.stackSize;
                }
            }
        }
    }

    private static final class FoodRenderData {
        final ItemStack[] icons;
        final int[] needed;
        final int[] have;
        final String summaryText;
        final int summaryColor;

        FoodRenderData(ItemStack[] icons, int[] needed, int[] have, String summaryText, int summaryColor) {
            this.icons = icons;
            this.needed = needed;
            this.have = have;
            this.summaryText = summaryText;
            this.summaryColor = summaryColor;
        }
    }

    private static double distance3d(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }
}
