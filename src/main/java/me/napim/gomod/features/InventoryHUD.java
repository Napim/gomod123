/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.Gui
 *  net.minecraft.client.gui.ScaledResolution
 *  net.minecraft.client.renderer.GlStateManager
 *  net.minecraft.client.renderer.RenderHelper
 *  net.minecraft.item.ItemArmor
 *  net.minecraft.item.ItemStack
 *  net.minecraft.util.EnumChatFormatting
 *  net.minecraft.util.ResourceLocation
 */
package me.napim.gomod.features;

import me.napim.gomod.Main;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.UselessItemOverlay;
import me.napim.gomod.gui.InventoryHUDGUI;
import me.napim.gomod.gui.FriendInventoryHUDGUI;
import me.napim.gomod.gui.HudModuleTextDraw;
import me.napim.gomod.gui.HudEditorBackgroundMask;
import me.napim.gomod.shareinv.ShareInvManager;
import me.napim.gomod.util.HudGlState;
import me.napim.gomod.util.HudGuiItemIcon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Item;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import net.minecraft.client.renderer.ThreadDownloadImageData;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureManager;

import org.lwjgl.input.Mouse;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InventoryHUD {
    /**
     * Friend ShareInv hotbar: extra downward offset for items (× {@code tScale}). Increase to move items down.
     */
    private static final float FRIEND_HOTBAR_ITEM_DY = 1.5f;
    /**
     * Friend held-slot frame: added to item top Y (× {@code tScale}). More negative = frame moves up relative to items.
     */
    private static final float FRIEND_HOTBAR_HELD_FRAME_DY = +1.0f;

    private static final Map<String, ResourceLocation> SHAREINV_MINOTAR_SKINS = new ConcurrentHashMap<>();

    /** Deferred so the tooltip draws after item quads (depth / z) and after other HUD layers in the same frame. */
    private static ItemStack shareInvTooltipStack;
    private static int shareInvTooltipMouseX;
    private static int shareInvTooltipMouseY;
    /** Tooltip content is stable for a remote ShareInv snapshot; don't rebuild it on every hovered frame. */
    private static ItemStack cachedShareInvTooltipStack;
    private static Item cachedShareInvTooltipItem;
    private static EntityPlayer cachedShareInvTooltipPlayer;
    private static int cachedShareInvTooltipStackSize;
    private static int cachedShareInvTooltipDamage;
    private static boolean cachedShareInvTooltipAdvanced;
    private static List<String> cachedShareInvTooltipLines;

    /** Friend HUD preview: fixed UUID so the label/head stay correct if the player renames. */
    private static final UUID FRIEND_HUD_PREVIEW_UUID = UUID.fromString("88e6c095-dc99-4b8a-aa49-c5ace4e18784");
    private static final String FRIEND_HUD_PREVIEW_NAME = "SaturnAndJupiter";

    /** Same preview label/head as friend ShareInv HUD editor. */
    public static String getFriendHudPreviewDisplayName() {
        return FRIEND_HUD_PREVIEW_NAME;
    }

    public static void drawFriendHudPreviewHead(Minecraft mc, int x, int y, int size) {
        if (mc == null || size < 1) {
            return;
        }
        drawTeammatePlayerHead(mc, FRIEND_HUD_PREVIEW_NAME, null, x, y, size, true);
    }

    /** Tab list / Minotar face for incoming screenshot toast (sender may not be in the world). */
    public static void drawIncomingScreenshotSenderHead(Minecraft mc, String playerName, int x, int y, int size) {
        if (mc == null || playerName == null || playerName.isEmpty() || size < 1) {
            return;
        }
        EntityPlayer peer = null;
        try {
            if (mc.theWorld != null && mc.thePlayer != null) {
                for (EntityPlayer p : mc.theWorld.playerEntities) {
                    if (p != mc.thePlayer && p.getName().equalsIgnoreCase(playerName)) {
                        peer = p;
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        drawTeammatePlayerHead(mc, playerName, peer, x, y, size, false);
    }

    private static ResourceLocation inventoryTexture = null;
    private static boolean textureLoaded = false;
    /** When true, {@link #render()} is being called from a settings GUI so it draws above the menu. */
    private static boolean drawingOnSettingsGui = false;
    private static boolean enabled = false;
    private static boolean showEmpty = false;
    private static boolean force = false;
    private static int customX = 362;
    private static int customY = 331;
    private static float scale = 0.8125f;
    private static int backgroundOpacity = 110;
    private static int backgroundColor = 0;

    private static ResourceLocation getInventoryTexture() {
        if (inventoryTexture == null) {
            inventoryTexture = new ResourceLocation("textures/gui/container/inventory.png");
        }
        return inventoryTexture;
    }

    public static void loadSettings() {
        enabled = ConfigManager.isInventoryHUDEnabled();
        showEmpty = ConfigManager.isInventoryHUDShowEmpty();
        force = ConfigManager.isInventoryHUDForce();
        customX = ConfigManager.getInventoryHUDCustomX();
        customY = ConfigManager.getInventoryHUDCustomY();
        scale = ConfigManager.getInventoryHUDScale();
        backgroundOpacity = ConfigManager.getInventoryHUDBackgroundOpacity();
        backgroundColor = ConfigManager.getInventoryHUDBackgroundColor();
    }

    public static void saveSettings() {
        ConfigManager.setInventoryHUDEnabled(enabled);
        ConfigManager.setInventoryHUDShowEmpty(showEmpty);
        ConfigManager.setInventoryHUDForce(force);
        ConfigManager.setInventoryHUDCustomX(customX);
        ConfigManager.setInventoryHUDCustomY(customY);
        ConfigManager.setInventoryHUDScale(scale);
        ConfigManager.setInventoryHUDBackgroundOpacity(backgroundOpacity);
        ConfigManager.setInventoryHUDBackgroundColor(backgroundColor);
    }

    public static void toggle() {
        enabled = !enabled;
        InventoryHUD.saveSettings();
    }

    public static void toggleShowEmpty() {
        showEmpty = !showEmpty;
        InventoryHUD.saveSettings();
    }

    public static void toggleForce() {
        force = !force;
        InventoryHUD.saveSettings();
    }

    public static void setCustomPosition(int x, int y) {
        customX = x;
        customY = y;
        InventoryHUD.saveSettings();
    }

    public static void resetPosition() {
        customX = 362;
        customY = 331;
        InventoryHUD.saveSettings();
    }

    public static void setScale(float newScale) {
        if (newScale >= 0.5f && newScale <= 4.0f) {
            float previousScale = scale;
            if (previousScale <= 0.0f) {
                previousScale = 1.0f;
            }
            int previousBgWidth = (int)(162.0f * previousScale);
            int previousBgHeight = (int)(54.0f * previousScale);
            int newBgWidth = (int)(162.0f * newScale);
            int newBgHeight = (int)(54.0f * newScale);
            int deltaW = newBgWidth - previousBgWidth;
            int deltaH = newBgHeight - previousBgHeight;
            customX -= deltaW / 2;
            customY -= deltaH / 2;
            scale = newScale;
            InventoryHUD.saveSettings();
        } else {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Invalid scale! Use 0.5-4.0 (supports 1.5)");
        }
    }

    public static void setBackgroundOpacity(int newOpacity) {
        if (newOpacity >= 0 && newOpacity <= 255) {
            backgroundOpacity = newOpacity;
            InventoryHUD.saveSettings();
        } else {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Invalid opacity! Use 0-255 (0=transparent, 255=opaque)");
        }
    }

    private static String getPositionName(int pos) {
        switch (pos) {
            case 0: {
                return "top-left";
            }
            case 1: {
                return "top-right";
            }
            case 2: {
                return "bottom-left";
            }
            case 3: {
                return "bottom-right";
            }
        }
        return "unknown";
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static int getCustomX() {
        return customX;
    }

    public static int getCustomY() {
        return customY;
    }

    public static float getScale() {
        return scale;
    }

    /** Screen bounds matching {@link #render()} (main 9×3 inventory strip). */
    public static void getHudScreenBounds(int[] out) {
        if (out == null || out.length < 4) {
            return;
        }
        float textureSlotSize = 18.0f * scale;
        out[0] = customX;
        out[1] = customY;
        out[2] = (int) (9.0f * textureSlotSize);
        out[3] = (int) (3.0f * textureSlotSize);
    }

    /** Padding around {@link #getHudScreenBounds} for HUD editor selection (outside the drawn panel). */
    public static int getHudEditorBoundsPadding() {
        return 1;
    }

    public static int getBackgroundOpacity() {
        return backgroundOpacity;
    }

    public static void setBackgroundColor(int r, int g, int b) {
        backgroundColor = r << 16 | g << 8 | b;
        InventoryHUD.saveSettings();
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Inventory HUD background color set to RGB(" + r + ", " + g + ", " + b + ")");
    }

    public static void resetBackgroundColor() {
        backgroundColor = 0;
        InventoryHUD.saveSettings();
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Inventory HUD background color reset to default (black)");
    }

    public static int getBackgroundColor() {
        return backgroundColor;
    }

    public static boolean isShowEmpty() {
        return showEmpty;
    }

    public static boolean isForce() {
        return force;
    }

    public static String getCurrentPosition() {
        return "(" + customX + ", " + customY + ")";
    }

    private static boolean hasInventoryItems() {
        if (Minecraft.getMinecraft().thePlayer == null || Minecraft.getMinecraft().thePlayer.inventory == null) {
            return false;
        }
        for (int i = 9; i < 36; ++i) {
            ItemStack itemStack = Minecraft.getMinecraft().thePlayer.inventory.getStackInSlot(i);
            if (itemStack == null) continue;
            return true;
        }
        return false;
    }

    /** Draw inventory HUD on top of {@link InventoryHUDGUI} (not behind the dark menu background). */
    public static void renderOnInventorySettingsGui() {
        drawingOnSettingsGui = true;
        try {
            render();
        } finally {
            drawingOnSettingsGui = false;
        }
    }

    /** Draw friend inventory HUD on top of {@link FriendInventoryHUDGUI}. */
    public static void renderOnFriendInventorySettingsGui() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        drawingOnSettingsGui = true;
        try {
            renderTeammateShareInvHud(mc);
        } finally {
            drawingOnSettingsGui = false;
            shareInvTooltipStack = null;
        }
    }

    public static void render() {
        shareInvTooltipStack = null;
        boolean hasPlayerInventory;
        Minecraft mc = Minecraft.getMinecraft();
        boolean onInventorySettingsGui = mc != null && mc.currentScreen instanceof InventoryHUDGUI;
        boolean onFriendSettingsGui = mc != null && mc.currentScreen instanceof FriendInventoryHUDGUI;
        if (onInventorySettingsGui && !drawingOnSettingsGui) {
            return;
        }
        if (onFriendSettingsGui && !drawingOnSettingsGui) {
            return;
        }
        boolean configuring = onInventorySettingsGui;
        boolean configuringTeammate = onFriendSettingsGui;
        boolean configuringHudEditor = mc != null && mc.currentScreen instanceof me.napim.gomod.gui.GomodHudEditorGUI;
        boolean bl = hasPlayerInventory = mc != null && mc.thePlayer != null && mc.thePlayer.inventory != null;
        boolean shareActive = ShareInvManager.isSessionActive();
        if (!(configuring || configuringTeammate || configuringHudEditor || enabled && hasPlayerInventory || shareActive && hasPlayerInventory)) {
            return;
        }
        if (!configuring && !configuringTeammate && !configuringHudEditor && !force && mc.currentScreen != null && !shareActive) {
            return;
        }
        if (!configuring && !configuringTeammate && !configuringHudEditor && !force && !shareActive
                && me.napim.gomod.gui.GomodMenuCloseAnim.isActive()) {
            return;
        }
        if (!(configuring || configuringTeammate || configuringHudEditor || showEmpty || InventoryHUD.hasInventoryItems() || shareActive)) {
            return;
        }
        ScaledResolution scaledResolution = new ScaledResolution(Minecraft.getMinecraft());
        int screenWidth = scaledResolution.getScaledWidth();
        int screenHeight = scaledResolution.getScaledHeight();
        int x = customX;
        int y = customY;
        float slotSize = 16.0f * scale;
        float textureSlotSize = 18.0f * scale;
        int inventoryWidth = (int)(9.0f * textureSlotSize);
        int inventoryHeight = (int)(3.0f * textureSlotSize);
        int borderSize = (int)scale;
        if (configuringHudEditor && backgroundOpacity > 0) {
            HudEditorBackgroundMask.reserveRect((int) x, (int) y,
                    (int) (x + inventoryWidth), (int) (y + inventoryHeight));
        }
        GlStateManager.enableBlend();
        GlStateManager.color((float)1.0f, (float)1.0f, (float)1.0f, (float)((float)backgroundOpacity / 255.0f));
        try {
            Minecraft.getMinecraft().getTextureManager().bindTexture(InventoryHUD.getInventoryTexture());
            int textureStartX = 7;
            int textureStartY = 83;
            int textureWidth = 162;
            int textureHeight = 54;
            Gui.drawScaledCustomSizeModalRect((int)x, (int)y, (float)textureStartX, (float)textureStartY, (int)textureWidth, (int)textureHeight, (int)inventoryWidth, (int)inventoryHeight, (float)256.0f, (float)256.0f);
            textureLoaded = true;
        }
        catch (Exception e) {
            try {
                int backgroundColorWithOpacity = backgroundOpacity << 24 | backgroundColor;
                Gui.drawRect((int)x, (int)y, (int)(x + inventoryWidth), (int)(y + inventoryHeight), (int)backgroundColorWithOpacity);
                textureLoaded = false;
            }
            catch (Exception ignored) {
                textureLoaded = false;
            }
        }
        GlStateManager.color((float)1.0f, (float)1.0f, (float)1.0f, (float)1.0f);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        if (hasPlayerInventory) {
            for (int row = 0; row < 3; ++row) {
                for (int col = 0; col < 9; ++col) {
                    float slotTopLeftX = (float)x + (float)col * textureSlotSize + 1.0f;
                    float slotTopLeftY = (float)y + (float)row * textureSlotSize + 0.5f;
                    int inventorySlot = row * 9 + col + 9;
                    ItemStack itemStack = Minecraft.getMinecraft().thePlayer.inventory.getStackInSlot(inventorySlot);
                    if (itemStack == null) continue;
                    GlStateManager.pushMatrix();
                    try {
                        GlStateManager.translate(slotTopLeftX, slotTopLeftY, 0.0f);
                        GlStateManager.scale(scale, scale, 1.0f);
                        InventoryQolHudUnderlay.renderSlot(inventorySlot, 0, 0, mc.thePlayer,
                                InventoryQolHudUnderlay.SOURCE_INVENTORY_HUD);
                    } finally {
                        GlStateManager.popMatrix();
                    }
                    HudGuiItemIcon.drawInventoryHudSlotWithResolution(mc, itemStack, slotTopLeftX, slotTopLeftY,
                            scale, scaledResolution);
                    GlStateManager.pushMatrix();
                    GlStateManager.translate(slotTopLeftX, slotTopLeftY, 0.0f);
                    GlStateManager.scale(scale, scale, 1.0f);
                    try {
                        Minecraft.getMinecraft().getRenderItem().renderItemOverlayIntoGUI(
                                Minecraft.getMinecraft().fontRendererObj, itemStack, 0, 0, null);
                    } catch (Throwable ignored) {
                    }
                    GlStateManager.popMatrix();
                }
            }
        }

        if (!onFriendSettingsGui || drawingOnSettingsGui) {
            renderTeammateShareInvHud(mc);
        }
    }

    /**
     * Draw ShareInv item tooltip queued by {@link #renderTeammateShareInvHud}. Call once per frame after all other
     * overlay HUDs (see {@link me.napim.gomod.mixins.GuiIngameMixin}).
     */
    public static void renderShareInvTooltipLast(Minecraft mc) {
        if (mc == null) {
            return;
        }
        ItemStack stack = shareInvTooltipStack;
        if (stack == null) {
            return;
        }
        if (mc.currentScreen == null || mc.thePlayer == null) {
            shareInvTooltipStack = null;
            return;
        }
        int mx = shareInvTooltipMouseX;
        int my = shareInvTooltipMouseY;
        shareInvTooltipStack = null;
        drawTeammateShareInvItemTooltip(mc, stack, mx, my);
    }

    private static ItemStack remoteStackAt(ItemStack[] remote, int slot) {
        return remote != null && slot >= 0 && slot < remote.length ? remote[slot] : null;
    }

    private static void renderTeammateShareInvHud(Minecraft mc) {
        FriendInventoryHUD.loadSettings();
        boolean preview = mc.currentScreen instanceof FriendInventoryHUDGUI
                || mc.currentScreen instanceof me.napim.gomod.gui.GomodHudEditorGUI;
        HudGlState.restoreForOverlayText();
        boolean live = ShareInvManager.isSessionActive() && ShareInvManager.getPeerName() != null;
        if (!preview && !live) {
            return;
        }
        // Do not show stale remote inventory from the last session while in the main menu / not in a world.
        if (!preview && (mc.theWorld == null || mc.thePlayer == null)) {
            return;
        }
        if (!FriendInventoryHUD.isEnabled() && !preview) {
            return;
        }
        if (!preview) {
            if (!FriendInventoryHUD.isForce() && mc.currentScreen != null && !ShareInvManager.isSessionActive()) {
                return;
            }
        }
        float tScale = FriendInventoryHUD.getScale();
        int peerX = FriendInventoryHUD.getCustomX();
        int peerY = FriendInventoryHUD.getCustomY();
        int bgOpacity = FriendInventoryHUD.getBackgroundOpacity();
        int bgRgb = FriendInventoryHUD.getBackgroundColor();
        float textureSlotSize = 18.0f * tScale;
        int gap = Math.max(2, (int) (2.0f * tScale));
        int mainGridW = (int) (9.0f * textureSlotSize);

        String peerNamePlain = preview ? FRIEND_HUD_PREVIEW_NAME : ShareInvManager.getPeerHudName();
        EntityPlayer peerEntity = preview ? null : findTeammatePeerInWorld(mc, peerNamePlain);
        int headSize = (int) Math.max(8.0f, Math.min(24.0f, 8.0f * tScale));
        drawTeammatePlayerHead(mc, peerNamePlain, peerEntity, peerX, peerY, headSize, preview);
        int nameY = peerY + (headSize - 8) / 2;
        if (!preview && ShareInvManager.shouldAnnotatePeerMcMenu()) {
            drawFriendHudText(mc, EnumChatFormatting.AQUA + peerNamePlain + EnumChatFormatting.GRAY
                    + " (MC menu)", peerX + headSize + 4, nameY, 0xFFFFFF);
        } else {
            drawFriendHudText(mc, EnumChatFormatting.AQUA + peerNamePlain, peerX + headSize + 4, nameY, 0xFFFFFF);
        }

        int rowY = peerY + Math.max(headSize, 10) + gap;
        rowY = drawTeammateHudStatsAndDirection(mc, peerX, rowY, tScale, preview, peerEntity, mainGridW);

        ItemStack[] remote = ShareInvManager.getRemoteSlots();
        // HUD editor / preview on main menu: don't fill slots from the last in-world ShareInv snapshot.
        if (preview && (mc.theWorld == null || mc.thePlayer == null)) {
            remote = null;
        }
        int armorTotalW = (int) (4.0f * textureSlotSize);
        int armorX = peerX + (mainGridW - armorTotalW) / 2;
        for (int i = 0; i < 4; i++) {
            int slot = 39 - i;
            float sx = armorX + (float) i * textureSlotSize;
            drawTeammateSlotBackground(mc, sx, (float) rowY, textureSlotSize, bgOpacity, bgRgb);
            drawTeammateItemStack(mc, sx + 1.0f, (float) rowY + 1.0f, remoteStackAt(remote, slot), tScale, remote, slot);
        }
        rowY += (int) textureSlotSize + gap;

        int invBgW = (int) (9.0f * textureSlotSize);
        int invBgH = (int) (3.0f * textureSlotSize);
        drawTeammateTexturedRect(mc, peerX, rowY, 7, 83, 162, 54, invBgW, invBgH, bgOpacity, bgRgb);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int inventorySlot = row * 9 + col + 9;
                float slotTopLeftX = (float) peerX + (float) col * textureSlotSize + 1.0f;
                float slotTopLeftY = (float) rowY + (float) row * textureSlotSize + 1.0f;
                drawTeammateItemStack(mc, slotTopLeftX, slotTopLeftY, remoteStackAt(remote, inventorySlot), tScale, remote,
                        inventorySlot);
            }
        }
        rowY += invBgH + gap;

        // Full vanilla hotbar strip is 22px tall in inventory.png (v=140); drawing at 18px cropped the frame.
        int hotbarTop = rowY;
        int hotbarTexV = 140;
        int hotbarTexH = 22;
        int hotbarDrawH = (int) Math.ceil(22.0f * tScale);
        drawTeammateTexturedRect(mc, peerX, hotbarTop, 7, hotbarTexV, 162, hotbarTexH, invBgW, hotbarDrawH, bgOpacity, bgRgb);
        int sel = preview ? 0 : ShareInvManager.getRemoteSelectedHotbarSlot();
        if (sel < 0 || sel > 8) {
            sel = 0;
        }
        float slotYOffset = (float) hotbarTop + 1.0f + FRIEND_HOTBAR_ITEM_DY * tScale;
        for (int col = 0; col < 9; col++) {
            float slotTopLeftX = (float) peerX + (float) col * textureSlotSize + 1.0f;
            drawTeammateItemStack(mc, slotTopLeftX, slotYOffset, remoteStackAt(remote, col), tScale, remote, col);
        }
        if (FriendInventoryHUD.isShowHeldHighlight()) {
            float hx = (float) peerX + (float) sel * textureSlotSize;
            float hy = slotYOffset + FRIEND_HOTBAR_HELD_FRAME_DY * tScale;
            int innerW = (int) (16.0f * tScale);
            int edgePad = 1;
            int ox = (int) (hx + 1.0f) - edgePad;
            int oy = (int) hy - edgePad;
            int ox2 = (int) (hx + 1.0f) + innerW + edgePad;
            int oy2 = (int) hy + innerW + edgePad;
            int br = 0x78000000;
            int t = Math.max(1, (int) tScale);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            Gui.drawRect(ox, oy, ox2, oy + t, br);
            Gui.drawRect(ox, oy2 - t, ox2, oy2, br);
            Gui.drawRect(ox, oy + t, ox + t, oy2 - t, br);
            Gui.drawRect(ox2 - t, oy + t, ox2, oy2 - t, br);
            GlStateManager.disableBlend();
        }
        HudGlState.restoreForOverlayText();

        // Same rule as {@link PlayerTarget}: item tooltips only with a GUI open so gameplay is unaffected.
        if (mc.currentScreen != null && mc.thePlayer != null) {
            ScaledResolution sr = new ScaledResolution(mc);
            int dw = Math.max(1, mc.displayWidth);
            int dh = Math.max(1, mc.displayHeight);
            int mx = Mouse.getX() * sr.getScaledWidth() / dw;
            int my = sr.getScaledHeight() - Mouse.getY() * sr.getScaledHeight() / dh - 1;
            ItemStack hovered = locateTeammateShareInvHoveredStack(mc, mx, my, peerX, peerY, tScale, preview, peerEntity,
                    remote);
            if (hovered != null) {
                shareInvTooltipStack = hovered;
                shareInvTooltipMouseX = mx;
                shareInvTooltipMouseY = my;
            }
        }
    }

    /**
     * Hit-test remote ShareInv slots (armor, main inv, hotbar) in screen space — geometry matches drawing above.
     */
    private static ItemStack locateTeammateShareInvHoveredStack(Minecraft mc, int mx, int my, int peerX, int peerY,
            float tScale, boolean preview, EntityPlayer peerEntity, ItemStack[] remote) {
        float textureSlotSize = 18.0f * tScale;
        int gap = Math.max(2, (int) (2.0f * tScale));
        int mainGridW = (int) (9.0f * textureSlotSize);
        int headSize = (int) Math.max(8.0f, Math.min(24.0f, 8.0f * tScale));
        int rowY = peerY + Math.max(headSize, 10) + gap;
        rowY = computeTeammateHudStatsBlockBottomY(rowY, tScale,
                FriendInventoryHUD.isShowRemoteHealth(), FriendInventoryHUD.isShowRemoteXp(),
                FriendInventoryHUD.isShowDirection());

        int ts = (int) Math.ceil(textureSlotSize);
        int armorTotalW = (int) (4.0f * textureSlotSize);
        int armorX = peerX + (mainGridW - armorTotalW) / 2;
        for (int i = 0; i < 4; i++) {
            int slot = 39 - i;
            int sx = armorX + (int) ((float) i * textureSlotSize);
            if (mx >= sx && mx < sx + ts && my >= rowY && my < rowY + ts) {
                return remoteStackAt(remote, slot);
            }
        }
        rowY += (int) textureSlotSize + gap;

        int invBgH = (int) (3.0f * textureSlotSize);
        int invTop = rowY;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int cellLeft = peerX + (int) ((float) col * textureSlotSize);
                int cellTop = invTop + (int) ((float) row * textureSlotSize);
                if (mx >= cellLeft && mx < cellLeft + ts && my >= cellTop && my < cellTop + ts) {
                    return remoteStackAt(remote, row * 9 + col + 9);
                }
            }
        }
        rowY += invBgH + gap;

        int hotbarTop = rowY;
        int hotbarDrawH = (int) Math.ceil(22.0f * tScale);
        int hotbarHitH = Math.max(ts, hotbarDrawH);
        for (int col = 0; col < 9; col++) {
            int cellLeft = peerX + (int) ((float) col * textureSlotSize);
            if (mx >= cellLeft && mx < cellLeft + ts && my >= hotbarTop && my < hotbarTop + hotbarHitH) {
                return remoteStackAt(remote, col);
            }
        }
        return null;
    }

    /** Vanilla-style tooltip (name, enchants, NBT lore) via {@link GuiScreen#drawHoveringText}. */
    private static void drawTeammateShareInvItemTooltip(Minecraft mc, ItemStack stack, int mouseX, int mouseY) {
        GlStateManager.pushMatrix();
        try {
            try {
                mc.getRenderItem().zLevel = 0.0f;
            } catch (Throwable ignored) {}
            GlStateManager.disableLighting();
            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.enableTexture2D();
            GlStateManager.colorMask(true, true, true, true);
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
            // Item quads wrote depth at GUI z; bring tooltip forward so nothing clips it if depth is re-enabled.
            GlStateManager.translate(0.0f, 0.0f, 400.0f);

            List<String> tooltip = getCachedTeammateShareInvTooltip(mc, stack);
            if (tooltip == null || tooltip.isEmpty()) {
                return;
            }
            boolean fontSup = DisguiseRenamedItemMasker.beginRenamedMaskFontSuppression(stack);
            try {
                ShareInvTooltipBridge.draw(mc, tooltip, mouseX, mouseY);
            } finally {
                DisguiseRenamedItemMasker.endRenamedMaskFontSuppression(fontSup);
            }
        } catch (Throwable ignored) {
        } finally {
            GlStateManager.popMatrix();
            GlStateManager.depthMask(true);
            GlStateManager.enableDepth();
            try {
                mc.getRenderItem().zLevel = 0.0f;
            } catch (Throwable ignored2) {}
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        }
    }

    private static List<String> getCachedTeammateShareInvTooltip(Minecraft mc, ItemStack stack) {
        boolean advanced = mc.gameSettings.advancedItemTooltips;
        Item item = stack.getItem();
        int stackSize = stack.stackSize;
        int damage = stack.getItemDamage();
        if (cachedShareInvTooltipStack == stack
                && cachedShareInvTooltipItem == item
                && cachedShareInvTooltipPlayer == mc.thePlayer
                && cachedShareInvTooltipStackSize == stackSize
                && cachedShareInvTooltipDamage == damage
                && cachedShareInvTooltipAdvanced == advanced
                && cachedShareInvTooltipLines != null) {
            return cachedShareInvTooltipLines;
        }

        @SuppressWarnings("unchecked")
        List<String> tooltip = stack.getTooltip(mc.thePlayer, advanced);
        cachedShareInvTooltipStack = stack;
        cachedShareInvTooltipItem = item;
        cachedShareInvTooltipPlayer = mc.thePlayer;
        cachedShareInvTooltipStackSize = stackSize;
        cachedShareInvTooltipDamage = damage;
        cachedShareInvTooltipAdvanced = advanced;
        cachedShareInvTooltipLines = tooltip == null ? new ArrayList<String>() : new ArrayList<String>(tooltip);
        return cachedShareInvTooltipLines;
    }

    private static final class ShareInvTooltipBridge extends GuiScreen {
        private ShareInvTooltipBridge() {}

        static void draw(Minecraft mc, List<String> lines, int mouseX, int mouseY) {
            ShareInvTooltipBridge g = new ShareInvTooltipBridge();
            g.mc = mc;
            g.fontRendererObj = mc.fontRendererObj;
            ScaledResolution sr = new ScaledResolution(mc);
            g.width = sr.getScaledWidth();
            g.height = sr.getScaledHeight();
            g.drawHoveringText(lines, mouseX, mouseY);
        }
    }

    private static EntityPlayer findTeammatePeerInWorld(Minecraft mc, String name) {
        if (name == null || mc.theWorld == null || mc.thePlayer == null) {
            return null;
        }
        for (EntityPlayer p : mc.theWorld.playerEntities) {
            if (p != mc.thePlayer && p.getName().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    private static ResourceLocation resolveTeammateSkin(Minecraft mc, String name, EntityPlayer peer) {
        ResourceLocation skin = DefaultPlayerSkin.getDefaultSkinLegacy();
        try {
            if (peer != null && mc.getNetHandler() != null) {
                NetworkPlayerInfo byUuid = mc.getNetHandler().getPlayerInfo(peer.getUniqueID());
                if (byUuid != null && byUuid.getLocationSkin() != null) {
                    return byUuid.getLocationSkin();
                }
            }
            if (name != null && mc.getNetHandler() != null) {
                for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                    if (info.getGameProfile().getName().equalsIgnoreCase(name)) {
                        if (info.getLocationSkin() != null) {
                            return info.getLocationSkin();
                        }
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {}
        if (name != null && !name.isEmpty()) {
            return resolvePeerSkinMinotar(mc, name);
        }
        return skin;
    }

    private static ResourceLocation resolvePeerSkinMinotar(Minecraft mc, String name) {
        String key = name.toLowerCase(Locale.ROOT);
        ResourceLocation existing = SHAREINV_MINOTAR_SKINS.get(key);
        if (existing != null) {
            return existing;
        }
        ResourceLocation rl = new ResourceLocation("gomod123", "shareinv_skin/" + Integer.toHexString(key.hashCode()));
        try {
            String enc = URLEncoder.encode(name, "UTF-8");
            String url = "https://minotar.net/avatar/" + enc + "/16";
            TextureManager tm = mc.getTextureManager();
            ITextureObject cur = tm.getTexture(rl);
            if (cur == null) {
                tm.loadTexture(rl, new ThreadDownloadImageData(null, url, null, null));
            }
        } catch (Throwable ignored) {
            return DefaultPlayerSkin.getDefaultSkinLegacy();
        }
        SHAREINV_MINOTAR_SKINS.put(key, rl);
        return rl;
    }

    private static ResourceLocation resolvePeerSkinMinotarByUuid(Minecraft mc, UUID uuid) {
        String key = "u:" + uuid.toString().toLowerCase(Locale.ROOT);
        ResourceLocation existing = SHAREINV_MINOTAR_SKINS.get(key);
        if (existing != null) {
            return existing;
        }
        ResourceLocation rl = new ResourceLocation("gomod123", "shareinv_skin/" + key.replace(':', '_'));
        try {
            String undashed = uuid.toString().replace("-", "");
            String url = "https://minotar.net/avatar/" + undashed + "/16";
            TextureManager tm = mc.getTextureManager();
            ITextureObject cur = tm.getTexture(rl);
            if (cur == null) {
                tm.loadTexture(rl, new ThreadDownloadImageData(null, url, null, null));
            }
        } catch (Throwable ignored) {
            return DefaultPlayerSkin.getDefaultSkinLegacy();
        }
        SHAREINV_MINOTAR_SKINS.put(key, rl);
        return rl;
    }

    private static boolean isShareInvMinotarSkinRl(ResourceLocation skin) {
        return skin != null && "gomod123".equals(skin.getResourceDomain())
                && skin.getResourcePath() != null && skin.getResourcePath().startsWith("shareinv_skin/");
    }

    private static void drawTeammatePlayerHead(Minecraft mc, String playerName, EntityPlayer peer, int x, int y, int size,
            boolean preview) {
        ResourceLocation skin = preview ? resolvePeerSkinMinotarByUuid(mc, FRIEND_HUD_PREVIEW_UUID)
                : resolveTeammateSkin(mc, playerName, peer);
        GlStateManager.enableBlend();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        mc.getTextureManager().bindTexture(skin);
        if (isShareInvMinotarSkinRl(skin)) {
            // Minotar serves a 16×16 face; 64×64 skin UVs would sample wrong and look like a flat color.
            Gui.drawScaledCustomSizeModalRect(x, y, 0.0f, 0.0f, 16, 16, size, size, 16.0f, 16.0f);
        } else {
            Gui.drawScaledCustomSizeModalRect(x, y, 8.0f, 8.0f, 8, 8, size, size, 64.0f, 64.0f);
            Gui.drawScaledCustomSizeModalRect(x, y, 40.0f, 8.0f, 8, 8, size, size, 64.0f, 64.0f);
        }
        GlStateManager.disableBlend();
    }

    /** XP level like vanilla hotbar: green digit(s) with black outline; gray percent suffix with drop shadow. */
    private static void drawTeammateXpLevelLine(Minecraft mc, int x, int y, int level, int pct) {
        String num = Integer.toString(level);
        net.minecraft.client.gui.FontRenderer fr = mc.fontRendererObj;
        drawStringBlackOutline(fr, num, x, y, 0x80FF20);
        HudModuleTextDraw.draw(fr, EnumChatFormatting.GRAY + " [" + pct + "%]", x + fr.getStringWidth(num), y, 0xFFFFFF,
                FriendInventoryHUD.isTextShadow());
    }

    private static void drawStringBlackOutline(net.minecraft.client.gui.FontRenderer fr, String text, int x, int y, int rgb) {
        int outline = 0xFF000000;
        for (int ox = -1; ox <= 1; ox++) {
            for (int oy = -1; oy <= 1; oy++) {
                if (ox == 0 && oy == 0) {
                    continue;
                }
                fr.drawString(text, (float) (x + ox), (float) (y + oy), outline, false);
            }
        }
        fr.drawString(text, (float) x, (float) y, 0xFF000000 | (rgb & 0xFFFFFF), false);
    }

    /**
     * Health + XP stacked on the left; direction cursor and distance sit on their right, vertically between the two
     * stat lines when both are shown.
     */
    private static int drawTeammateHudStatsAndDirection(Minecraft mc, int peerX, int startY, float tScale,
            boolean preview, EntityPlayer peer, int hudBlockWidth) {
        int rowGap = Math.max(2, (int) (2.0f * tScale));
        net.minecraft.client.gui.FontRenderer fr = mc.fontRendererObj;
        int fh = fr.FONT_HEIGHT;
        int y = startY;
        int statColW = 0;
        int healthY = -1;
        int xpY = -1;
        boolean showHp = FriendInventoryHUD.isShowRemoteHealth();
        boolean showXp = FriendInventoryHUD.isShowRemoteXp();
        float h = preview ? 14.5f : ShareInvManager.getRemoteHealth();
        float hMax = preview ? 20.0f : ShareInvManager.getRemoteHealthMax();
        float xpBar = preview ? 0.42f : ShareInvManager.getRemoteXpBar();

        if (showHp) {
            healthY = y;
            String hpLine = PlayerTarget.formatHealthForTeammateHud(h, hMax);
            HudModuleTextDraw.draw(fr, hpLine, peerX, healthY, 0xFFFFFF, FriendInventoryHUD.isTextShadow());
            statColW = Math.max(statColW, fr.getStringWidth(hpLine));
            y += fh + rowGap;
        }
        if (showXp) {
            xpY = y;
            int pct = (int) Math.floor((double) xpBar * 100.0 + 0.5);
            if (pct < 0) {
                pct = 0;
            }
            if (pct > 100) {
                pct = 100;
            }
            int xpLvl = preview ? 3 : ShareInvManager.getRemoteXpLevel();
            drawTeammateXpLevelLine(mc, peerX, xpY, xpLvl, pct);
            statColW = Math.max(statColW, measureTeammateXpLineWidth(fr, xpLvl, pct));
            y += fh + rowGap;
        }

        int blockBottom = y;
        if (!FriendInventoryHUD.isShowDirection()) {
            return blockBottom;
        }
        if (!preview && peer == null && (showHp || showXp)) {
            return blockBottom;
        }

        double distance;
        float angleDeg;
        double verticalDist;
        boolean sneaking;
        if (preview) {
            distance = 42.7;
            verticalDist = 3.2;
            angleDeg = (System.currentTimeMillis() % 3600) / 10.0f;
            sneaking = false;
        } else {
            if (peer == null || mc.thePlayer == null) {
                return blockBottom;
            }
            distance = mc.thePlayer.getDistanceToEntity(peer);
            double dx = peer.posX - mc.thePlayer.posX;
            double dz = peer.posZ - mc.thePlayer.posZ;
            double angleToTargetDeg = Math.toDegrees(Math.atan2(dz, dx)) - 90.0D;
            angleDeg = (float) (angleToTargetDeg - (double) mc.thePlayer.rotationYaw);
            verticalDist = peer.posY - mc.thePlayer.posY;
            sneaking = peer.isSneaking();
        }

        int iconSize = Math.max(12, Math.min(20, (int) (16.0f * Math.sqrt(tScale))));
        int dirGap = Math.max(4, (int) (6.0f * tScale));
        int dirLeft = peerX + statColW + (statColW > 0 ? dirGap : 0);
        int dirCenterY;
        if (healthY >= 0 && xpY >= 0) {
            dirCenterY = (healthY + fh / 2 + xpY + fh / 2) / 2;
        } else if (healthY >= 0) {
            dirCenterY = healthY + fh / 2;
        } else if (xpY >= 0) {
            dirCenterY = xpY + fh / 2;
        } else {
            dirCenterY = startY + iconSize / 2;
        }

        int cursorX = dirLeft + iconSize / 2;
        int cursorTopY = dirCenterY - iconSize / 2;
        PlayerTarget.renderShareInvDirectionCursor(mc, cursorX, cursorTopY, iconSize, angleDeg, sneaking);

        String distText = buildTeammateDirectionDistText(distance, verticalDist);
        int textX = dirLeft + iconSize + Math.max(3, (int) (4.0f * tScale));
        int textY = dirCenterY - fh / 2;
        float textScale = resolveTeammateDirectionTextScale(mc, fr, distText, textX, peerX + hudBlockWidth);
        drawScaledStringWithShadow(mc, distText, textX, textY, 0xAAAAFF, textScale);

        int dirBlockBottom = Math.max(cursorTopY + iconSize, (int) (textY + fh * textScale) + 2);
        return Math.max(blockBottom, dirBlockBottom + 2);
    }

    private static int measureTeammateXpLineWidth(net.minecraft.client.gui.FontRenderer fr, int level, int pct) {
        String num = Integer.toString(level);
        return fr.getStringWidth(num) + fr.getStringWidth(" [" + pct + "%]");
    }

    private static String buildTeammateDirectionDistText(double distance, double verticalDist) {
        if (FriendInventoryHUD.isDirectionVertical()) {
            double absVerticalDist = Math.abs(verticalDist);
            String arrowColor;
            if (absVerticalDist >= 2.0) {
                arrowColor = verticalDist > 0 ? EnumChatFormatting.GREEN.toString() : EnumChatFormatting.RED.toString();
            } else {
                arrowColor = EnumChatFormatting.WHITE.toString();
            }
            String arrow;
            if (absVerticalDist < 0.5) {
                arrow = "\u25CF";
            } else {
                arrow = verticalDist > 0 ? "\u25B2" : "\u25BC";
            }
            return String.format("%.1fm (%s%s" + EnumChatFormatting.RESET + " %.1f)", distance, arrowColor, arrow,
                    absVerticalDist);
        }
        return String.format("%.1fm", distance);
    }

    /** Shrink distance/coords text when it would extend past the HUD block or screen edge. */
    private static float resolveTeammateDirectionTextScale(Minecraft mc, net.minecraft.client.gui.FontRenderer fr,
            String text, int textX, int hudRightX) {
        int textW = fr.getStringWidth(text);
        ScaledResolution sr = new ScaledResolution(mc);
        int maxRight = Math.min(sr.getScaledWidth() - 2, hudRightX);
        int availTextW = maxRight - textX;
        if (textW <= availTextW || availTextW <= 0) {
            return 1.0f;
        }
        return Math.max(0.55f, (float) availTextW / (float) textW);
    }

    /** Layout-only bottom Y for hit-testing (matches {@link #drawTeammateHudStatsAndDirection}). */
    private static int computeTeammateHudStatsBlockBottomY(int startY, float tScale, boolean showHp, boolean showXp,
            boolean showDir) {
        int rowGap = Math.max(2, (int) (2.0f * tScale));
        int fh = 9;
        int y = startY;
        int healthY = -1;
        int xpY = -1;
        if (showHp) {
            healthY = y;
            y += fh + rowGap;
        }
        if (showXp) {
            xpY = y;
            y += fh + rowGap;
        }
        int blockBottom = y;
        if (!showDir) {
            return blockBottom;
        }
        int iconSize = Math.max(12, Math.min(20, (int) (16.0f * Math.sqrt(tScale))));
        int dirCenterY;
        if (healthY >= 0 && xpY >= 0) {
            dirCenterY = (healthY + fh / 2 + xpY + fh / 2) / 2;
        } else if (healthY >= 0) {
            dirCenterY = healthY + fh / 2;
        } else if (xpY >= 0) {
            dirCenterY = xpY + fh / 2;
        } else {
            dirCenterY = startY + iconSize / 2;
        }
        int cursorTopY = dirCenterY - iconSize / 2;
        return Math.max(blockBottom, cursorTopY + iconSize + 2);
    }

    private static void drawFriendHudText(Minecraft mc, String text, int x, int y, int color) {
        HudGlState.restoreForOverlayText();
        HudModuleTextDraw.draw(mc.fontRendererObj, text, x, y, color, FriendInventoryHUD.isTextShadow());
        HudGlState.restoreForOverlayText();
    }

    private static void drawScaledStringWithShadow(Minecraft mc, String text, int x, int y, int color, float scale) {
        boolean shadow = FriendInventoryHUD.isTextShadow();
        HudGlState.restoreForOverlayText();
        if (scale >= 0.999f) {
            HudModuleTextDraw.draw(mc.fontRendererObj, text, x, y, color, shadow);
            HudGlState.restoreForOverlayText();
            return;
        }
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0.0f);
        GlStateManager.scale(scale, scale, 1.0f);
        HudModuleTextDraw.draw(mc.fontRendererObj, text, 0, 0, color, shadow);
        GlStateManager.popMatrix();
        HudGlState.restoreForOverlayText();
    }

    private static void drawTeammateSlotBackground(Minecraft mc, float x, float y, float textureSlotSize, int bgOpacity,
            int bgRgb) {
        int w = (int) textureSlotSize;
        int h = (int) textureSlotSize;
        if (HudEditorBackgroundMask.isActive() && bgOpacity > 0) {
            HudEditorBackgroundMask.reserveRect((int) x, (int) y, (int) x + w, (int) y + h);
        }
        GlStateManager.enableBlend();
        GlStateManager.color(1.0f, 1.0f, 1.0f, (float) bgOpacity / 255.0f);
        try {
            mc.getTextureManager().bindTexture(InventoryHUD.getInventoryTexture());
            Gui.drawScaledCustomSizeModalRect((int) x, (int) y, 7.0f, 83.0f, 18, 18, w, h, 256.0f, 256.0f);
        } catch (Exception e) {
            try {
                int c = bgOpacity << 24 | (bgRgb & 0xFFFFFF);
                Gui.drawRect((int) x, (int) y, (int) x + w, (int) y + h, c);
            } catch (Exception ignored) {}
        }
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GlStateManager.disableBlend();
    }

    private static void drawTeammateTexturedRect(Minecraft mc, int x, int y, int u, int v, int texW, int texH, int drawW,
            int drawH, int bgOpacity, int bgRgb) {
        if (HudEditorBackgroundMask.isActive() && bgOpacity > 0) {
            HudEditorBackgroundMask.reserveRect(x, y, x + drawW, y + drawH);
        }
        GlStateManager.enableBlend();
        GlStateManager.color(1.0f, 1.0f, 1.0f, (float) bgOpacity / 255.0f);
        try {
            mc.getTextureManager().bindTexture(InventoryHUD.getInventoryTexture());
            Gui.drawScaledCustomSizeModalRect(x, y, (float) u, (float) v, texW, texH, drawW, drawH, 256.0f, 256.0f);
        } catch (Exception e) {
            try {
                int c = bgOpacity << 24 | (bgRgb & 0xFFFFFF);
                Gui.drawRect(x, y, x + drawW, y + drawH, c);
            } catch (Exception ignored) {}
        }
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
    }

    /**
     * @param uselessRemote optional ShareInv snapshot; when non-null and {@code uselessSlotIndex >= 0}, useless overlay
     *                      is drawn before the item icon (same as main grid: tint under the stack).
     */
    private static void drawTeammateItemStack(Minecraft mc, float slotTopLeftX, float slotTopLeftY, ItemStack itemStack,
            float tScale, ItemStack[] uselessRemote, int uselessSlotIndex) {
        if (itemStack == null) {
            return;
        }
        GlStateManager.pushMatrix();
        GlStateManager.translate(slotTopLeftX, slotTopLeftY, 0.0f);
        GlStateManager.scale(tScale, tScale, 1.0f);
        if (UselessItemOverlay.isEnabled() && uselessRemote != null && uselessSlotIndex >= 0) {
            UselessItemOverlay.renderRemoteInventoryHudSlotOverlayInCurrentSlotContext(uselessRemote, uselessSlotIndex, mc);
        }
        GlStateManager.popMatrix();
        HudGuiItemIcon.drawInventoryHudSlot(mc, itemStack, slotTopLeftX, slotTopLeftY, tScale);
        GlStateManager.pushMatrix();
        GlStateManager.translate(slotTopLeftX, slotTopLeftY, 0.0f);
        GlStateManager.scale(tScale, tScale, 1.0f);
        try {
            mc.getRenderItem().renderItemOverlayIntoGUI(mc.fontRendererObj, itemStack, 0, 0, null);
        } catch (Throwable ignored) {
        }
        GlStateManager.popMatrix();
    }
}
