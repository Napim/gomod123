package me.napim.gomod.features;

import me.napim.gomod.Main;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * PlayersPlayMode renders up to 4 nearest players as simultaneous POV feeds.
 */
public final class PlayersPlayMode {
    private PlayersPlayMode() {}
    private static final ResourceLocation WIDGETS_TEXTURE = new ResourceLocation("textures/gui/widgets.png");

    private static boolean enabled = false;
    /** When true, {@link #notifyEnabled} skips chat (set by /playershow before enable; cleared in {@link #disable()}). */
    private static boolean chatMutedThisSession = false;
    private static boolean stayMode = false;
    private static int originalThirdPersonView = 0;
    private static final String[] renderedPlayerNames = new String[4];
    private static final EntityPlayer[] renderedTargets = new EntityPlayer[4];
    private static final String[] pinnedPlayerNames = new String[4];
    private static boolean renderingFirstPersonArm = false;
    private static EntityPlayer currentRenderTarget = null;

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean isStayMode() {
        return stayMode;
    }

    public static void setChatMutedThisSession(boolean muted) {
        chatMutedThisSession = muted;
    }

    public static boolean isChatMutedThisSession() {
        return chatMutedThisSession;
    }

    public static boolean enable(List<EntityPlayer> initialPlayers, boolean stay) {
        return enable(initialPlayers, null, stay);
    }

    public static boolean enable(List<EntityPlayer> initialPlayers, List<String> pinnedNames, boolean stay) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null || mc.theWorld == null) return false;
            boolean wasEnabled = enabled;

            if (!wasEnabled && RoleplayMode.isEnabled()) {
                RoleplayMode.disable();
            }
            if (!wasEnabled && Selfie.isEnabled()) {
                Selfie.setEnabled(false);
            }
            if (!wasEnabled) {
                originalThirdPersonView = mc.gameSettings.thirdPersonView;
            }
            stayMode = stay;
            enabled = true;
            clearPinnedPlayers();
            clearRenderedPlayers();
            if (stay) {
                if (pinnedNames != null && !pinnedNames.isEmpty()) {
                    pinPlayerNames(pinnedNames);
                } else {
                    pinPlayers(initialPlayers);
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void disable() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (enabled && mc != null) {
                mc.gameSettings.thirdPersonView = originalThirdPersonView;
                if (mc.thePlayer != null) {
                    mc.setRenderViewEntity(mc.thePlayer);
                }
            }
        } catch (Throwable ignored) {}

        enabled = false;
        stayMode = false;
        chatMutedThisSession = false;
        renderingFirstPersonArm = false;
        currentRenderTarget = null;
        clearPinnedPlayers();
        clearRenderedPlayers();
    }

    public static List<EntityPlayer> findNearestPlayers(int maxCount) {
        List<EntityPlayer> result = new ArrayList<EntityPlayer>();
        if (maxCount <= 0) return result;

        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null || mc.theWorld == null) return result;
            Set<Integer> seenEntityIds = new HashSet<Integer>();

            for (Object obj : mc.theWorld.playerEntities) {
                if (!(obj instanceof EntityPlayer)) continue;
                EntityPlayer player = (EntityPlayer) obj;
                if (player == mc.thePlayer) continue;
                if (!gomod$isAutoSelectablePlayer(player)) continue;
                if (!seenEntityIds.add(player.getEntityId())) continue;
                result.add(player);
            }

            // Fallback for builds/world states where playerEntities is incomplete.
            if (result.isEmpty()) {
                for (Entity entity : (List<Entity>) mc.theWorld.getLoadedEntityList()) {
                    if (!(entity instanceof EntityPlayer)) continue;
                    EntityPlayer player = (EntityPlayer) entity;
                    if (player == mc.thePlayer) continue;
                    if (!gomod$isAutoSelectablePlayer(player)) continue;
                    if (!seenEntityIds.add(player.getEntityId())) continue;
                    result.add(player);
                }
            }

            final EntityPlayer self = mc.thePlayer;
            result.sort(new Comparator<EntityPlayer>() {
                @Override
                public int compare(EntityPlayer a, EntityPlayer b) {
                    double da = self.getDistanceSqToEntity(a);
                    double db = self.getDistanceSqToEntity(b);
                    return Double.compare(da, db);
                }
            });

            if (result.size() > maxCount) {
                return new ArrayList<EntityPlayer>(result.subList(0, maxCount));
            }
            return result;
        } catch (Throwable t) {
            return result;
        }
    }

    public static void setRenderedPlayers(List<EntityPlayer> players) {
        clearRenderedPlayers();
        if (players == null) return;
        int count = Math.min(4, players.size());
        for (int i = 0; i < count; i++) {
            EntityPlayer p = players.get(i);
            renderedPlayerNames[i] = p != null ? p.getName() : null;
            renderedTargets[i] = p;
        }
    }

    private static void pinPlayers(List<EntityPlayer> players) {
        clearPinnedPlayers();
        if (players == null) return;
        int count = Math.min(4, players.size());
        for (int i = 0; i < count; i++) {
            EntityPlayer player = players.get(i);
            if (player == null) continue;
            pinnedPlayerNames[i] = player.getName();
            renderedPlayerNames[i] = player.getName();
        }
    }

    private static void pinPlayerNames(List<String> playerNames) {
        clearPinnedPlayers();
        if (playerNames == null) return;
        int count = Math.min(4, playerNames.size());
        for (int i = 0; i < count; i++) {
            String playerName = playerNames.get(i);
            if (playerName == null || playerName.trim().isEmpty()) continue;
            pinnedPlayerNames[i] = playerName.trim();
            renderedPlayerNames[i] = pinnedPlayerNames[i];
        }
    }

    private static void clearPinnedPlayers() {
        for (int i = 0; i < pinnedPlayerNames.length; i++) {
            pinnedPlayerNames[i] = null;
        }
    }

    private static void clearRenderedPlayers() {
        for (int i = 0; i < renderedPlayerNames.length; i++) {
            renderedPlayerNames[i] = null;
            renderedTargets[i] = null;
        }
    }

    public static EntityPlayer[] getActiveTargets() {
        EntityPlayer[] targets = new EntityPlayer[4];
        if (!enabled) return targets;

        if (!stayMode) {
            List<EntityPlayer> nearestPlayers = findNearestPlayers(4);
            setRenderedPlayers(nearestPlayers);
            for (int i = 0; i < nearestPlayers.size() && i < 4; i++) {
                targets[i] = nearestPlayers.get(i);
            }
            storeRenderedTargets(targets);
            return targets;
        }

        clearRenderedPlayers();
        for (int i = 0; i < pinnedPlayerNames.length; i++) {
            String pinnedName = pinnedPlayerNames[i];
            if (pinnedName == null) continue;
            renderedPlayerNames[i] = pinnedName;
            targets[i] = findPlayerByName(pinnedName);
        }
        storeRenderedTargets(targets);
        return targets;
    }

    private static void storeRenderedTargets(EntityPlayer[] targets) {
        for (int i = 0; i < renderedTargets.length; i++) {
            renderedTargets[i] = targets != null && i < targets.length ? targets[i] : null;
        }
    }

    private static boolean gomod$isAutoSelectablePlayer(EntityPlayer player) {
        return player != null && player.isEntityAlive() && !player.isSpectator();
    }

    public static EntityPlayer[] getRenderedTargetsSnapshot() {
        EntityPlayer[] snapshot = new EntityPlayer[4];
        for (int i = 0; i < renderedTargets.length; i++) {
            snapshot[i] = renderedTargets[i];
        }
        return snapshot;
    }

    public static EntityPlayer getCurrentRenderTarget() {
        return currentRenderTarget;
    }

    public static void setCurrentRenderTarget(EntityPlayer player) {
        currentRenderTarget = player;
    }

    public static boolean isRenderingFirstPersonArm() {
        return renderingFirstPersonArm;
    }

    public static void setRenderingFirstPersonArm(boolean value) {
        renderingFirstPersonArm = value;
    }

    public static EntityPlayer findPlayerByName(String playerName) {
        if (playerName == null) return null;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null) return null;
            for (Object obj : mc.theWorld.playerEntities) {
                if (!(obj instanceof EntityPlayer)) continue;
                EntityPlayer player = (EntityPlayer) obj;
                if (playerName.equalsIgnoreCase(player.getName())) {
                    return player;
                }
            }
            for (Entity entity : (List<Entity>) mc.theWorld.getLoadedEntityList()) {
                if (!(entity instanceof EntityPlayer)) continue;
                EntityPlayer player = (EntityPlayer) entity;
                if (playerName.equalsIgnoreCase(player.getName())) {
                    return player;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static void renderOverlay() {
        if (!enabled) return;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.fontRendererObj == null) return;
            EntityPlayer[] targets = getRenderedTargetsSnapshot();
            boolean needsRefresh = true;
            for (int i = 0; i < targets.length; i++) {
                if (targets[i] != null || renderedPlayerNames[i] != null) {
                    needsRefresh = false;
                    break;
                }
            }
            if (needsRefresh) {
                targets = getActiveTargets();
            }

            ScaledResolution sr = new ScaledResolution(mc);
            int screenW = sr.getScaledWidth();
            int screenH = sr.getScaledHeight();
            int midX = screenW / 2;
            int midY = screenH / 2;

            int[][] quadrants = buildQuadrants(screenW, screenH);
            int crosshairSize = Math.max(8, Math.round(16.0F * Math.min(
                    (quadrants[0][2] - quadrants[0][0]) / (float) screenW,
                    (quadrants[0][3] - quadrants[0][1]) / (float) screenH
            )));

            for (int i = 0; i < 4; i++) {
                if (targets[i] != null) continue;
                int[] q = quadrants[i];
                Gui.drawRect(q[0], q[1], q[2], q[3], 0xDD000000);
            }

            Gui.drawRect(midX - 1, 0, midX + 1, screenH, 0xAA000000);
            Gui.drawRect(0, midY - 1, screenW, midY + 1, 0xAA000000);

            for (int i = 0; i < 4; i++) {
                String label = renderedPlayerNames[i] != null
                        ? renderedPlayerNames[i]
                        : EnumChatFormatting.DARK_GRAY + "No player";
                int x = (i % 2 == 0) ? 4 : (midX + 4);
                int y = (i < 2) ? 4 : (midY + 4);
                int textColor = renderedPlayerNames[i] != null ? 0xFFFFFF : 0xAAAAAA;
                int boxRight = x + mc.fontRendererObj.getStringWidth(label) + 4;
                Gui.drawRect(x - 2, y - 2, boxRight, y + 10, 0x66000000);
                mc.fontRendererObj.drawStringWithShadow(label, x, y, textColor);
            }

            renderPaneCrosshairs(mc, quadrants, targets, crosshairSize);

            for (int i = 0; i < 4; i++) {
                renderPaneHud(mc, quadrants[i], targets[i]);
            }
        } catch (Throwable t) {
            System.err.println("[gomod] PlayersPlay overlay error: " + t.getMessage());
        }
    }

    private static int[][] buildQuadrants(int screenW, int screenH) {
        int midX = screenW / 2;
        int midY = screenH / 2;
        return new int[][]{
                {0, 0, midX, midY},
                {midX, 0, screenW, midY},
                {0, midY, midX, screenH},
                {midX, midY, screenW, screenH}
        };
    }

    private static void renderPaneCrosshairs(Minecraft mc, int[][] quadrants, EntityPlayer[] targets, int crosshairSize) {
        if (quadrants == null || targets == null) return;

        GlStateManager.pushMatrix();
        try {
            mc.getTextureManager().bindTexture(Gui.icons);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(775, 769, 1, 0);
            GlStateManager.enableAlpha();

            for (int i = 0; i < quadrants.length && i < targets.length; i++) {
                if (targets[i] == null) continue;
                int[] quadrant = quadrants[i];
                int centerX = (quadrant[0] + quadrant[2]) / 2;
                int centerY = (quadrant[1] + quadrant[3]) / 2;
                int drawX = centerX - crosshairSize / 2;
                int drawY = centerY - crosshairSize / 2;
                Gui.drawScaledCustomSizeModalRect(drawX, drawY, 0.0F, 0.0F, 16, 16, crosshairSize, crosshairSize, 256.0F, 256.0F);
            }
        } finally {
            GlStateManager.disableBlend();
        }
        GlStateManager.popMatrix();
    }

    private static void renderPaneHud(Minecraft mc, int[] quadrant, EntityPlayer target) {
        if (quadrant == null || target == null) {
            return;
        }

        int paneCenterX = (quadrant[0] + quadrant[2]) / 2;
        int quadrantWidth = quadrant[2] - quadrant[0];
        int iconSize = Math.max(5, Math.min(9, (quadrantWidth - 24) / 10));
        int rowSpacing = Math.max(1, iconSize / 5);
        int slotWidth = 24;
        int slotHeight = 22;
        int slotX = paneCenterX - slotWidth / 2;
        int slotY = quadrant[3] - slotHeight - 3;
        int heartY = slotY - iconSize - rowSpacing;
        int armorValue = MathHelper.clamp_int(target.getTotalArmorValue(), 0, 20);
        int armorY = heartY - iconSize - rowSpacing;

        mc.getTextureManager().bindTexture(WIDGETS_TEXTURE);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.enableAlpha();
        Gui.drawScaledCustomSizeModalRect(slotX, slotY, 0.0F, 22.0F, 24, 22, slotWidth, slotHeight, 256.0F, 256.0F);

        ItemStack heldItem = target.getHeldItem();
        int slotItemX = paneCenterX - 8;
        int slotItemY = slotY + (slotHeight - 16) / 2;

        mc.getTextureManager().bindTexture(Gui.icons);
        if (armorValue > 0) {
            drawStatusRow(paneCenterX, armorY, iconSize, 16, 34, 25, 9, armorValue);
        }
        drawStatusRow(paneCenterX, heartY, iconSize, 16, 52, 61, 0, MathHelper.clamp_int(MathHelper.ceiling_float_int(target.getHealth()), 0, 20));

        if (heldItem != null) {
            GlStateManager.pushMatrix();
            try {
                GlStateManager.enableRescaleNormal();
                GlStateManager.enableBlend();
                GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
                GlStateManager.enableDepth();
                GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
                RenderHelper.enableGUIStandardItemLighting();
                mc.getRenderItem().zLevel = 200.0F;
                mc.getRenderItem().renderItemAndEffectIntoGUI(heldItem, slotItemX, slotItemY);
                mc.getRenderItem().renderItemOverlayIntoGUI(mc.fontRendererObj, heldItem, slotItemX, slotItemY, null);
            } finally {
                mc.getRenderItem().zLevel = 0.0F;
                GlStateManager.disableDepth();
                GlStateManager.disableBlend();
                GlStateManager.disableRescaleNormal();
                RenderHelper.disableStandardItemLighting();
                GlStateManager.popMatrix();
            }
        }

        GlStateManager.disableBlend();
    }

    private static void drawStatusRow(int centerX, int y, int iconSize, int emptyU, int fullU, int halfU, int textureV, int filledValue) {
        int startX = centerX - (iconSize * 10) / 2;
        for (int i = 0; i < 10; i++) {
            int x = startX + i * iconSize;
            Gui.drawScaledCustomSizeModalRect(x, y, emptyU, textureV, 9, 9, iconSize, iconSize, 256.0F, 256.0F);
            int iconValue = filledValue - i * 2;
            if (iconValue >= 2) {
                Gui.drawScaledCustomSizeModalRect(x, y, fullU, textureV, 9, 9, iconSize, iconSize, 256.0F, 256.0F);
            } else if (iconValue == 1) {
                Gui.drawScaledCustomSizeModalRect(x, y, halfU, textureV, 9, 9, iconSize, iconSize, 256.0F, 256.0F);
            }
        }
    }

    public static void notifyEnabled(int count) {
        if (chatMutedThisSession) {
            return;
        }
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "PlayersPlay "
                + (stayMode ? "stay mode enabled. " : "enabled. ")
                + EnumChatFormatting.YELLOW + (stayMode ? "Locked onto " : "Watching nearest ")
                + count + " player"
                + (count == 1 ? "" : "s")
                + EnumChatFormatting.GRAY + " (/playersplay, /playershow, or sneak to exit)");
    }

    /**
     * @param silentSession pass {@link #isChatMutedThisSession()} captured <strong>before</strong> {@link #disable()}.
     */
    public static void notifyDisabled(boolean silentSession) {
        if (silentSession) {
            return;
        }
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "PlayersPlay disabled.");
    }
}
