package me.napim.gomod.features;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.Main;
import me.napim.gomod.gui.TeamSpamGUI;
import me.napim.gomod.gui.GomodGUI;
import me.napim.gomod.gui.GomodSearchEntry;
import me.napim.gomod.gui.GomodSearchIndex;
import me.napim.gomod.gui.RadialMenuOverlayScreen;
import me.napim.gomod.gui.RadialMenuWheelSlotsGUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.ItemModelMesher;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.event.RenderGameOverlayEvent;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.weavemc.loader.api.event.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Radial Menu — a hold-key pie wheel for quickly toggling features or sending messages.
 *
 * <p>Hold the configured keybind to open the wheel overlay. Move your cursor to select
 * a slice. Release the key to execute the slice's action (or cancel by releasing over
 * the center area).</p>
 *
 * <p>Settings are stored in {@code <.minecraft>/gomod123/radialMenu.json}.</p>
 */
public class RadialMenuWheel {

    // ── constants ─────────────────────────────────────────────────────────────
    public static final int SLOT_COUNT = 8;

    /** Inner dead-zone radius in scaled pixels. Releasing inside = cancel. */
    private static final float INNER_R = 36f;
    /** Outer edge of each slice in scaled pixels. */
    private static final float OUTER_R = 88f;
    /** At the largest wheel radius this keeps the arc error below 0.04 scaled pixels. */
    private static final int ARC_SEGMENTS = 16;
    /** Gap between slices (radians removed from each side). */
    private static final float GAP_ANGLE = 0.028f;
    /** Small, animated lift; also used by the settings layout to reserve caption space. */
    public static final float HOVER_LIFT = 6f;
    private static final int SURFACE = 0x141B27;
    private static final int TEXT = 0xEDF3FA;
    private static final int MUTED = 0x94A3B8;
    private static final int ON_COLOR = 0x45F477;
    private static final int OFF_COLOR = 0xFF5368;
    private static final int ACTION_COLOR = 0xFFD166;
    private static final float[][] SLICE_ARCS = buildSliceArcs(0f);
    private static final float[][] RIM_ARCS = buildSliceArcs(0.025f);
    private static final float[] CENTER_ARC = buildArc(0f, (float) (Math.PI * 2), 96);

    /** Clock-driven lifecycle; selection geometry stays full-sized even during a quick flick. */
    public static final class Transition {
        private static final long OPEN_NS = 160_000_000L;
        private static final long CLOSE_NS = 110_000_000L;
        private boolean opened;
        private boolean closing;
        private long openedAtNs;
        private long closedAtNs;
        private float closeFrom;

        public void open(long nowNs) {
            if (opened) return; // A GUI resize must not restart the opening animation.
            opened = true;
            openedAtNs = nowNs;
        }

        public void close(long nowNs) {
            if (closing) return;
            closeFrom = value(nowNs);
            closedAtNs = nowNs;
            closing = true;
        }

        public boolean isClosing() { return closing; }

        public boolean isFinished(long nowNs) {
            return closing && nowNs - closedAtNs >= CLOSE_NS;
        }

        public float value(long nowNs) {
            if (!opened) return 0f;
            if (closing) {
                float t = Math.max(0f, Math.min(1f, (nowNs - closedAtNs) / (float) CLOSE_NS));
                return closeFrom * (1f - t * t * (3f - 2f * t));
            }
            float t = Math.max(0f, Math.min(1f, (nowNs - openedAtNs) / (float) OPEN_NS));
            float remaining = 1f - t;
            return 1f - remaining * remaining * remaining;
        }
    }

    /** Per-screen state: the editor and the in-game wheel never share animation history. */
    public static final class VisualState {
        private final float[] hover = new float[SLOT_COUNT];
        private long lastFrameNs;

        public void update(int hovered, long nowNs) {
            float elapsed = lastFrameNs == 0L ? 0f
                    : Math.max(0f, Math.min(0.05f, (nowNs - lastFrameNs) / 1_000_000_000f));
            lastFrameNs = nowNs;
            float blend = 1f - (float) Math.exp(-elapsed / 0.045f);
            for (int i = 0; i < SLOT_COUNT; i++) {
                hover[i] += ((i == hovered ? 1f : 0f) - hover[i]) * blend;
            }
        }
    }

    // ── colour palette for defaults ─────────────────────────────────────────
    private static final int[] DEFAULT_COLORS = {
        0xFF4444, // red
        0xFF8844, // orange
        0xFFDD44, // yellow
        0x44DD44, // green
        0x44DDFF, // cyan
        0x4488FF, // blue
        0xAA44FF, // purple
        0xAAAAAA, // gray
    };
    private static final int FONLINE_DEFAULT_COLOR = 0x4488FF;
    private static final int WATER_CLARITY_DEFAULT_COLOR = 0x44DDFF;

    // ── slot data ─────────────────────────────────────────────────────────────
    private static final RadialMenuItem[] slots = new RadialMenuItem[SLOT_COUNT];
    private static boolean enabled = true;

    static {
        resetToDefaults();
    }

    /** Reuse the rendered stacks, including the skull's renderer-resolved profile/NBT. */
    private static final Map<String, ItemStack> ICON_STACKS = buildIconStacks();

    /** True after a successful HUD-time warmup (or after giving up). */
    private static volatile boolean wheelRenderPipelineWarmedUp;
    private static int wheelWarmupMissFrames;

    // ── key tracking ──────────────────────────────────────────────────────────
    private boolean keyWasDown = false;

    // ── Gson ──────────────────────────────────────────────────────────────────
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ── public API ────────────────────────────────────────────────────────────

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean v) { enabled = v; ConfigManager.saveConfig(); }

    public static RadialMenuItem[] getSlots() { return slots; }

    public static RadialMenuItem getSlot(int i) {
        if (i < 0 || i >= SLOT_COUNT) return new RadialMenuItem();
        return slots[i];
    }

    /** True when the slot is empty or disabled and can be assigned/re-enabled from its editor. */
    public static boolean isSlotAvailableForAssignment(int i) {
        return i >= 0 && i < SLOT_COUNT && !isAssigned(slots[i]);
    }

    public static void setSlot(int i, RadialMenuItem item) {
        if (i >= 0 && i < SLOT_COUNT) slots[i] = item;
    }

    /** Settings page label for an assigned wheel module, using the same pages as gomod's settings search. */
    public static String getModuleSettingsSearchLabel(String actionValue) {
        if (actionValue == null) return null;
        switch (actionValue.trim().toUpperCase(Locale.ROOT)) {
            case "BEST_ARMOR": return "BestArmor overlay";
            case "BEST_ARMOR_PERCENTAGE": return "BestArmor percentage";
            case "PLAYER_TARGET":
            case "TEAMMATE_GLOW":
            case "TEAMMATE_TRAIL": return "Teammate Tracker";
            case "TEAM_SPAM":
            case "TEAM_SPAM_REFRESH": return "Teams Nick Finder";
            case "VISIBLE_SPECS": return "Spectator Settings";
            case "SEE_SPECTATORS": return "See spectators while alive";
            case "LOOT_CALL": return "Loot Call enabled";
            case "STAR_TEXT": return "Better BLITZ texts";
            case "WATER_CLARITY": return "Water clarity settings";
            case "MOB_NAMETAGS": return "Nametag Settings";
            case "LOBBY_PING": return "Lobby Ping";
            case "KILL_COUNTER":
            case "TAB_KILLERS": return "TAB QoL";
            case "MOB_HEALTH": return "Mob Health Display";
            case "INVENTORY_HUD": return "Inventory HUD";
            case "PERSPECTIVE": return "Perspective Settings";
            case "CHEST_ROUTES": return "Chestroutes settings";
            case "CAGE_TRACERS": return "Cage tracers";
            case "HIDE_DATE_GAME_ID": return "Hide date & game ID";
            case "SELF_OPACITY": return "Self opacity settings";
            case "BARRIER_VISIBILITY": return "Barrier visibility settings";
            case "ITEM_ESP": return "Useless item overlay";
            case "MOB_DISGUISE_LAST": return "Disguise";
            default: return null;
        }
    }

    /** Open the assigned module's settings page when possible; custom actions fall back to editing that wheel slot. */
    public static boolean openModuleSettings(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= SLOT_COUNT) return false;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return false;

        RadialMenuItem slot = slots[slotIndex];
        if (slot != null && slot.isSlotEnabled() && slot.getActionType() == RadialMenuItem.ActionType.TOGGLE_FEATURE) {
            String label = getModuleSettingsSearchLabel(slot.getActionValue());
            if (label != null) {
                for (GomodSearchEntry entry : GomodSearchIndex.search(label)) {
                    if (entry == null || !label.equalsIgnoreCase(entry.label) || entry.factory == null) continue;
                    net.minecraft.client.gui.GuiScreen settings = entry.factory.create(GomodGUI.forInternalNavigation());
                    if (settings != null) {
                        mc.displayGuiScreen(settings);
                        return true;
                    }
                }
            }
        }

        mc.displayGuiScreen(new RadialMenuWheelSlotsGUI(null, slotIndex));
        return true;
    }

    // ── event: tick ───────────────────────────────────────────────────────────

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (!enabled) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) return;
        // Don't open wheel if already open or another GUI is showing
        if (mc.currentScreen instanceof RadialMenuOverlayScreen) {
            keyWasDown = true;
            return;
        }
        if (mc.currentScreen != null) return;

        int keyCode = ConfigManager.getRadialMenuKeyCode();
        if (keyCode == 0) {
            keyWasDown = false;
            return;
        }

        boolean isDown = isKeyCurrentlyDown(keyCode);
        if (isDown && !keyWasDown) {
            mc.displayGuiScreen(new RadialMenuOverlayScreen());
        }
        keyWasDown = isDown;
    }

    /**
     * Runs once per session during a normal in-world HUD frame (correct GL / overlay matrix), before the player
     * opens the radial wheel, so the first open does not hitch on item rendering and related class loads.
     */
    @SubscribeEvent
    public void onRenderGameOverlayPost(RenderGameOverlayEvent.Post event) {
        if (wheelRenderPipelineWarmedUp) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        if (mc.currentScreen != null) {
            return;
        }
        if (mc.getRenderItem() == null || mc.fontRendererObj == null) {
            if (++wheelWarmupMissFrames > 600) {
                wheelRenderPipelineWarmedUp = true;
            }
            return;
        }
        try {
            warmUpWheelRenderPipeline(mc);
        } catch (Throwable ignored) {
        }
        wheelRenderPipelineWarmedUp = true;
    }

    // ── action execution ──────────────────────────────────────────────────────

    /** Execute the action for the given slot index. Called when the user releases the key. */
    public static void executeSlot(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= SLOT_COUNT) return;
        RadialMenuItem slot = slots[slotIndex];
        if (!slot.isSlotEnabled()) return;
        if (slot.getActionType() == RadialMenuItem.ActionType.NONE) return;
        if (GomodInternalFeatures.isRestrictedRadialSlot(slot)) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;

        // Blocked actions: tell the player in chat instead of failing silently (wheel has no UI).
        if (slot.getActionType() == RadialMenuItem.ActionType.COMMAND
                && "fonline".equalsIgnoreCase(slot.getActionValue().trim())) {
            if (me.napim.gomod.features.FriendListScanner.isCollecting()) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                        + "Friend list scan already in progress. Please wait for it to finish.");
                return;
            }
        }
        if (slot.getActionType() == RadialMenuItem.ActionType.TOGGLE_FEATURE) {
            String key = slot.getActionValue();
            if ("TEAM_SPAM".equals(key)) {
                if (me.napim.gomod.command.TeamSpamCommand.isRunning()) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                            + "Team spam is already running.");
                    return;
                }
            }
            if ("TEAM_SPAM_REFRESH".equals(key)) {
                if (me.napim.gomod.gui.TeamSpamGUI.isRefreshing) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                            + "Player list refresh is already running.");
                    return;
                }
                long waitMs = me.napim.gomod.command.TeamSpamCommand.RefreshCooldownPublic.timeRemainingMs();
                if (waitMs > 0L) {
                    long mins = waitMs / 60000L;
                    long secs = (waitMs % 60000L) / 1000L;
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                            + "Refresh is on cooldown. Try again in " + mins + "m " + secs + "s.");
                    return;
                }
            }
        }

        if (isSlotBusy(slot)) return;

        switch (slot.getActionType()) {
            case TOGGLE_FEATURE:
                toggleFeature(slot.getActionValue(), mc);
                break;
            case CHAT_MESSAGE:
                String msg = slot.getActionValue().trim();
                if (!msg.isEmpty()) mc.thePlayer.sendChatMessage(msg);
                break;
            case COMMAND:
                String cmd = slot.getActionValue().trim();
                if (!cmd.isEmpty() && !GomodInternalFeatures.isRestrictedWheelCommand(cmd)) {
                    mc.thePlayer.sendChatMessage("/" + cmd);
                }
                break;
            default:
                break;
        }
    }

    private static void toggleFeature(String key, Minecraft mc) {
        if (GomodInternalFeatures.isRestrictedToggleFeatureKey(key)) {
            return;
        }
        switch (key) {
            case "VISIBLE_SPECS":
                mc.displayGuiScreen(new me.napim.gomod.gui.VisibleSpectatorsGUI(null));
                break;
            case "SEE_SPECTATORS":
                SpectatorRelay.setEnabled(!SpectatorRelay.isEnabled());
                sendToggleMessage(mc, "See spectators while alive", SpectatorRelay.isEnabled());
                break;
            case "LOOT_CALL":
                LootCall.setEnabled(!LootCall.isEnabled());
                sendToggleMessage(mc, "Loot Call", LootCall.isEnabled());
                break;
            case "TEAM_SPAM":
                mc.thePlayer.sendChatMessage("/tspam");
                break;
            case "STAR_TEXT":
                ConfigManager.setStarTextEnabled(!ConfigManager.isStarTextEnabled());
                sendToggleMessage(mc, "Better BLITZ texts", ConfigManager.isStarTextEnabled());
                break;
            case "TEAM_SPAM_REFRESH":
                TeamSpamGUI.openAndStartRefresh();
                break;
            case "WATER_CLARITY":
                WaterClarity.setEnabled(!WaterClarity.isEnabled());
                WaterClarity.reloadChunkMeshesForSurfaceClarity();
                sendToggleMessage(mc, "Water Clarity", WaterClarity.isEnabled());
                break;
            case "MOB_NAMETAGS":
                MobNametags.setEnabled(!MobNametags.isEnabled());
                sendToggleMessage(mc, "Mob Nametags", MobNametags.isEnabled());
                break;
            case "LOBBY_PING":
                LobbyPingDisplay.setEnabled(!LobbyPingDisplay.isEnabled());
                sendToggleMessage(mc, "Lobby Ping", LobbyPingDisplay.isEnabled());
                break;
            case "BEST_ARMOR":
                BestArmorDisplay.setEnabledSilent(!BestArmorDisplay.isEnabled());
                sendToggleMessage(mc, "BestArmor", BestArmorDisplay.isEnabled());
                break;
            case "KILL_COUNTER":
                ConfigManager.setKillCounterEnabled(!ConfigManager.isKillCounterEnabled());
                sendToggleMessage(mc, "Kill Counter", ConfigManager.isKillCounterEnabled());
                break;
            case "MOB_HEALTH":
                MobHealthDisplay.setEnabled(!MobHealthDisplay.isEnabled());
                sendToggleMessage(mc, "Mob Health", MobHealthDisplay.isEnabled());
                break;
            case "PLAYER_TARGET":
                PlayerTarget.setEnabled(!PlayerTarget.isEnabled());
                sendToggleMessage(mc, "Teammate tracker", PlayerTarget.isEnabled());
                break;
            case "INVENTORY_HUD":
                ConfigManager.setInventoryHUDEnabled(!ConfigManager.isInventoryHUDEnabled());
                sendToggleMessage(mc, "Inv HUD", ConfigManager.isInventoryHUDEnabled());
                break;
            case "PERSPECTIVE":
                ConfigManager.setPerspectiveEnabled(!ConfigManager.isPerspectiveEnabled());
                sendToggleMessage(mc, "Custom perspective", ConfigManager.isPerspectiveEnabled());
                break;
            case "TEAMMATE_GLOW":
                PlayerTarget.cycleTeammateGlowMode();
                if (mc.thePlayer != null) {
                    mc.thePlayer.addChatMessage(new ChatComponentText(
                            Main.CHAT_PREFIX + "Teammate glow: " + EnumChatFormatting.YELLOW
                                    + PlayerTarget.getTeammateGlowMode().getDisplayLabel()));
                }
                break;
            case "TEAMMATE_TRAIL":
                PlayerTarget.setTeammateTrailEnabled(!PlayerTarget.isTeammateTrailEnabled());
                sendToggleMessage(mc, "Teammate trail", PlayerTarget.isTeammateTrailEnabled());
                break;
            case "BEST_ARMOR_PERCENTAGE": {
                int m = ConfigManager.getBestArmorPercentageMode();
                int next = m == ConfigManager.BEST_ARMOR_PERCENTAGE_MINIMAL
                        ? ConfigManager.BEST_ARMOR_PERCENTAGE_DETAILED
                        : m == ConfigManager.BEST_ARMOR_PERCENTAGE_DETAILED
                        ? ConfigManager.BEST_ARMOR_PERCENTAGE_OFF
                        : ConfigManager.BEST_ARMOR_PERCENTAGE_MINIMAL;
                ConfigManager.setBestArmorPercentageMode(next);
                String label = next == ConfigManager.BEST_ARMOR_PERCENTAGE_OFF ? "\u00a7cOFF"
                        : next == ConfigManager.BEST_ARMOR_PERCENTAGE_MINIMAL ? "\u00a7aAverage only" : "\u00a7eDetailed";
                if (mc.thePlayer != null) {
                    mc.thePlayer.addChatMessage(new ChatComponentText(Main.CHAT_PREFIX + "BestArmor %: " + label));
                }
                break;
            }
            case "CHEST_ROUTES":
                ConfigManager.setChestRoutesRenderEnabled(!ConfigManager.isChestRoutesRenderEnabled());
                sendToggleMessage(mc, "Chestroutes", ConfigManager.isChestRoutesRenderEnabled());
                break;
            case "HIDE_DATE_GAME_ID":
                ConfigManager.setHideDateGameIdEnabled(!ConfigManager.isHideDateGameIdEnabled());
                ScoreboardDateHider.onGuiToggleScoreboardHide();
                sendToggleMessage(mc, "Hide date & game ID", ConfigManager.isHideDateGameIdEnabled());
                break;
            case "SELF_OPACITY":
                SelfOpacity.setEnabled(!SelfOpacity.isEnabled());
                break;
            case "BARRIER_VISIBILITY":
                BarrierVisibility.toggleWithRendererReload();
                sendToggleMessage(mc, "Barrier blocks", ConfigManager.isBarrierVisibilityEnabled());
                break;
            case "CAGE_TRACERS":
                PlayerTracers.toggleCagesAuto();
                ConfigManager.setPtracersCagesEnabled(PlayerTracers.isCagesAutoEnabled());
                sendToggleMessage(mc, "Cage tracers", PlayerTracers.isCagesAutoEnabled());
                break;
            case "ITEM_ESP":
                UselessItemOverlay.setEnabled(!UselessItemOverlay.isEnabled());
                sendToggleMessage(mc, "Useless Items Overlay", UselessItemOverlay.isEnabled());
                break;
            case "TAB_KILLERS":
                KillCounter.setTabKillersEnabled(!ConfigManager.isKillCounterTabKillersEnabled(), true);
                break;
            case "MOB_DISGUISE_LAST":
                if (LocalMobDisguise.isActive()) {
                    LocalMobDisguise.clearCustomAndSetPreset(LocalMobDisguise.Kind.OFF);
                } else {
                    String mobId = LocalMobDisguise.getPreferredMobInputValue();
                    if (mobId == null || mobId.trim().isEmpty()) {
                        mobId = "Zombie";
                    }
                    LocalMobDisguise.setCustomEntityId(mobId);
                }
                mc.thePlayer.addChatMessage(new ChatComponentText(
                        Main.CHAT_PREFIX + "Mob disguise: " + LocalMobDisguise.getMobDisguiseStatusLine()));
                break;
            default:
                break;
        }
    }

    private static void sendToggleMessage(Minecraft mc, String feature, boolean on) {
        if (mc.thePlayer == null) return;
        String color = on ? "\u00a7a" : "\u00a7c";
        String state = on ? "ON" : "OFF";
        mc.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText(
            Main.CHAT_PREFIX + feature + ": " + color + state));
    }

    // ── rendering ─────────────────────────────────────────────────────────────

    /**
     * Render the wheel. Called from {@link RadialMenuOverlayScreen#drawScreen}.
     *
     * @param mouseX    scaled mouse X
     * @param mouseY    scaled mouse Y
     * @param hovered   currently hovered slice index, or -1 if cursor is in the dead-zone
     */
    public static void renderWheel(int mouseX, int mouseY, int hovered, long hoveredForMs) {
        renderWheel(mouseX, mouseY, hovered, hoveredForMs, null);
    }

    public static void renderWheel(int mouseX, int mouseY, int hovered, long hoveredForMs, VisualState visual) {
        renderWheel(mouseX, mouseY, hovered, hoveredForMs, visual, 1f);
    }

    public static void renderWheel(int mouseX, int mouseY, int hovered, long hoveredForMs,
                                   VisualState visual, float reveal) {
        reveal = Math.max(0f, Math.min(1f, reveal));
        if (reveal <= 0.001f) return;
        Minecraft mc = Minecraft.getMinecraft();
        ScaledResolution sr = new ScaledResolution(mc);
        int cx = sr.getScaledWidth() / 2;
        int cy = sr.getScaledHeight() / 2;
        float scale = getWheelScale(sr.getScaledWidth(), sr.getScaledHeight()) * reveal;
        drawRect(0, 0, sr.getScaledWidth(), sr.getScaledHeight(), (Math.round(99f * reveal) << 24) | 0x070B13, mc);
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(cx, cy, 0f);
            GlStateManager.scale(scale, scale, 1f);
            GlStateManager.translate(-cx, -cy, 0f);
            renderWheelInternal(mc, hovered, cx, cy, INNER_R, OUTER_R, -1, false, 18, visual);
            drawCentered(mc.fontRendererObj, "MODULE WHEEL", cx, cy - OUTER_R - 24, 0.75f, MUTED);
            RadialMenuItem slot = hovered >= 0 && hovered < SLOT_COUNT ? slots[hovered] : null;
            if (slot != null) {
                String caption = getSlotHoverCaption(slot);
                if (caption.isEmpty()) caption = "Empty slot";
                caption = mc.fontRendererObj.trimStringToWidth(caption, 226);
                drawCentered(mc.fontRendererObj, caption, cx, cy + OUTER_R + 16, 1f, TEXT);
            }
        } finally {
            GlStateManager.popMatrix();
            GlStateManager.color(1f, 1f, 1f, 1f);
        }
    }

    /** Keep the complete wheel and its instructions inside small GUI resolutions. */
    public static float getWheelScale(int width, int height) {
        return Math.max(0.1f, Math.min(1f, Math.min(width / 260f, height / 264f)));
    }

    public static void renderWheelEditorPreview(int mouseX, int mouseY, int cx, int cy, int hovered, long hoveredForMs,
                                                 float innerR, float outerR, int selectedSlice) {
        renderWheelEditorPreview(mouseX, mouseY, cx, cy, hovered, hoveredForMs, innerR, outerR, selectedSlice, null);
    }

    public static void renderWheelEditorPreview(int mouseX, int mouseY, int cx, int cy, int hovered, long hoveredForMs,
                                                 float innerR, float outerR, int selectedSlice, VisualState visual) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) renderWheelInternal(mc, hovered, cx, cy, innerR, outerR, selectedSlice, true, 6, visual);
    }

    public static int sliceCaptionYBelowWheel(int cy, float outerR, boolean rimBulged, int gapBelowRim) {
        return cy + Math.round(outerR + (rimBulged ? HOVER_LIFT : 0f)) + gapBelowRim;
    }

    private static void renderWheelInternal(Minecraft mc, int hovered, int cx, int cy,
                                           float innerR, float outerR, int selectedSlice,
                                           boolean editor, int captionGap, VisualState visual) {
        if (visual != null) visual.update(hovered, System.nanoTime());
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableCull();
        GlStateManager.disableDepth();
        GL11.glShadeModel(GL11.GL_SMOOTH);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < SLOT_COUNT; i++) {
            RadialMenuItem slot = slots[i];
            boolean active = isAssigned(slot) && !GomodInternalFeatures.isRestrictedRadialSlot(slot);
            float hot = visual == null ? (hovered == i ? 1f : 0f) : visual.hover[i];
            boolean selected = i == selectedSlice;
            float edge = outerR + HOVER_LIFT * Math.max(hot, selected ? 1f : 0f);
            float[] arc = SLICE_ARCS[i];
            int accent = getSlotAccent(slot);
            // A soft local shadow, dark surface, and thin colored rim replace the old solid wedges.
            drawBand(buffer, cx, cy, innerR + 1f, edge + 3f, arc, 0x080C14, 0x080C14, 0.26f, 0.08f);
            float stateStrength = getToggleFeatureState(slot) != null ? 0.16f + hot * 0.15f : 0.06f + hot * 0.22f;
            drawBand(buffer, cx, cy, innerR + 2f, edge, arc, SURFACE,
                    mixColor(SURFACE, accent, active ? stateStrength : 0.02f), 0.93f, 0.96f);
            drawBand(buffer, cx, cy, innerR + 2f, innerR + 2.7f, arc, 0x718097, 0x718097, 0.16f, 0.16f);
            drawBand(buffer, cx, cy, edge - 1.2f - hot, edge, RIM_ARCS[i],
                    accent, accent, active ? 0.88f + hot * 0.12f : 0.14f, active ? 0.88f + hot * 0.12f : 0.14f);
            if (hot > 0.01f) {
                drawBand(buffer, cx, cy, edge, edge + 2.5f, arc, accent, accent, hot * 0.12f, 0f);
            }
            if (selected) {
                drawBand(buffer, cx, cy, edge + 2f, edge + 3.5f, arc, 0xDCEAFF, 0xDCEAFF, 0.95f, 0.95f);
            }
        }
        // The center is a safe cancel zone, separated from the wedges by a small breathing gap.
        drawBand(buffer, cx, cy, 0f, innerR - 2f, CENTER_ARC, 0x101722, 0x101722, 0.97f, 0.97f);
        drawBand(buffer, cx, cy, innerR - 2.8f, innerR - 2f, CENTER_ARC, 0x627188, 0x627188, 0.26f, 0.26f);
        tessellator.draw();
        GL11.glShadeModel(GL11.GL_FLAT);
        GlStateManager.enableTexture2D();
        GlStateManager.enableCull();
        GlStateManager.enableDepth();
        GlStateManager.color(1f, 1f, 1f, 1f);

        net.minecraft.client.gui.FontRenderer fr = mc.fontRendererObj;
        for (int i = 0; i < SLOT_COUNT; i++) {
            RadialMenuItem slot = slots[i];
            if (isAssigned(slot)) continue;
            float hot = visual == null ? (hovered == i ? 1f : 0f) : visual.hover[i];
            float textR = innerR + (outerR - innerR) * 0.58f + hot * 2.5f;
            int tx = cx + Math.round(textR * SLICE_ARCS[i][ARC_SEGMENTS]);
            int ty = cy + Math.round(textR * SLICE_ARCS[i][ARC_SEGMENTS + 1]);
            drawCentered(fr, "+", tx, ty - 4, 1f, 0x536176);
        }
        // Set up item lighting once for all eight icons, rather than once per slice.
        RenderHelper.enableGUIStandardItemLighting();
        RenderItem renderItem = mc.getRenderItem();
        float previousZ = renderItem.zLevel;
        try {
            renderItem.zLevel = 200f;
            for (int i = 0; i < SLOT_COUNT; i++) {
                RadialMenuItem slot = slots[i];
                if (!isAssigned(slot)) continue;
                float hot = visual == null ? (hovered == i ? 1f : 0f) : visual.hover[i];
                float iconR = innerR + (outerR - innerR) * 0.58f + hot * 2.5f;
                int tx = cx + Math.round(iconR * SLICE_ARCS[i][ARC_SEGMENTS]);
                int ty = cy + Math.round(iconR * SLICE_ARCS[i][ARC_SEGMENTS + 1]);
                ItemStack icon = getSlotIconStack(slot);
                if (icon != null) drawSlotItemIcon(renderItem, icon, tx, ty,
                        GomodInternalFeatures.isRestrictedRadialSlot(slot), iconExtraTintFor(slot));
            }
        } finally {
            renderItem.zLevel = previousZ;
            RenderHelper.disableStandardItemLighting();
            GlStateManager.color(1f, 1f, 1f, 1f);
        }
        GlStateManager.disableDepth();
        RadialMenuItem focus = hovered >= 0 && hovered < SLOT_COUNT ? slots[hovered] : null;
        if (focus == null) {
            drawCenterFitted(fr, editor ? "Select slot" : "Choose", cx, cy - 3, 0.90f, TEXT, cy, innerR);
        } else {
            drawCenterFitted(fr, getSlotStatusLabel(focus), cx, cy - 16, 0.65f, getSlotAccent(focus), cy, innerR);
            drawCenterName(fr, isAssigned(focus) ? getSlotHoverCaption(focus) : "Empty slot", cx, cy - 5, cy, innerR);
        }
        if (editor && focus != null) {
            String caption = getSlotHoverCaption(focus);
            if (caption.isEmpty()) caption = "Empty slot";
            drawCentered(fr, fr.trimStringToWidth(caption, 220), cx,
                    sliceCaptionYBelowWheel(cy, outerR, true, captionGap), 1f, TEXT);
        }
        GlStateManager.enableDepth();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    private static float[][] buildSliceArcs(float inset) {
        float[][] arcs = new float[SLOT_COUNT][];
        for (int i = 0; i < SLOT_COUNT; i++) {
            arcs[i] = buildArc(sliceStart(i) + GAP_ANGLE + inset, sliceEnd(i) - GAP_ANGLE - inset, ARC_SEGMENTS);
        }
        return arcs;
    }

    private static float[] buildArc(float start, float end, int segments) {
        float[] arc = new float[(segments + 1) * 2];
        for (int j = 0; j <= segments; j++) {
            float angle = start + (end - start) * j / segments;
            arc[j * 2] = (float) Math.cos(angle);
            arc[j * 2 + 1] = (float) Math.sin(angle);
        }
        return arc;
    }

    private static void drawBand(WorldRenderer buffer, float cx, float cy, float inner, float outer, float[] arc,
                                 int innerRgb, int outerRgb, float innerAlpha, float outerAlpha) {
        int ir = (innerRgb >> 16) & 255, ig = (innerRgb >> 8) & 255, ib = innerRgb & 255;
        int or = (outerRgb >> 16) & 255, og = (outerRgb >> 8) & 255, ob = outerRgb & 255;
        int ia = Math.round(innerAlpha * 255f), oa = Math.round(outerAlpha * 255f);
        for (int j = 0; j < arc.length - 2; j += 2) {
            float x = arc[j], y = arc[j + 1], nx = arc[j + 2], ny = arc[j + 3];
            buffer.pos(cx + inner * x, cy + inner * y, 0).color(ir, ig, ib, ia).endVertex();
            buffer.pos(cx + outer * x, cy + outer * y, 0).color(or, og, ob, oa).endVertex();
            buffer.pos(cx + outer * nx, cy + outer * ny, 0).color(or, og, ob, oa).endVertex();
            buffer.pos(cx + inner * nx, cy + inner * ny, 0).color(ir, ig, ib, ia).endVertex();
        }
    }

    private static int mixColor(int from, int to, float amount) {
        int r = Math.round(((from >> 16) & 255) * (1f - amount) + ((to >> 16) & 255) * amount);
        int g = Math.round(((from >> 8) & 255) * (1f - amount) + ((to >> 8) & 255) * amount);
        int b = Math.round((from & 255) * (1f - amount) + (to & 255) * amount);
        return (r << 16) | (g << 8) | b;
    }

    private static boolean isAssigned(RadialMenuItem slot) {
        return slot != null && slot.isSlotEnabled() && slot.getActionType() != RadialMenuItem.ActionType.NONE;
    }

    private static int getSlotAccent(RadialMenuItem slot) {
        if (!isAssigned(slot) || GomodInternalFeatures.isRestrictedRadialSlot(slot)) return 0x65738A;
        if (isSlotBusy(slot)) return MUTED;
        if (isCooldownableSlot(slot)) return ACTION_COLOR;
        Boolean on = getToggleFeatureState(slot);
        if (on != null) return on ? ON_COLOR : OFF_COLOR;
        return isFonlineCommand(slot) ? getFonlinePreferredColor() : slot.getColor();
    }

    public static String getSlotStatusLabel(RadialMenuItem slot) {
        if (slot == null || slot.getActionType() == RadialMenuItem.ActionType.NONE) return "EMPTY";
        if (!slot.isSlotEnabled()) return "DISABLED";
        if (GomodInternalFeatures.isRestrictedRadialSlot(slot)) return "UNAVAILABLE";
        if (isSlotBusy(slot)) return "BUSY";
        Boolean on = getToggleFeatureState(slot);
        return on == null ? "READY" : (on ? "ON" : "OFF");
    }

    private static void drawCentered(net.minecraft.client.gui.FontRenderer fr, String text,
                                     float cx, float y, float scale, int rgb) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(cx, y, 0f);
        GlStateManager.scale(scale, scale, 1f);
        fr.drawStringWithShadow(text, -fr.getStringWidth(text) / 2f, 0f, rgb);
        GlStateManager.popMatrix();
    }

    /** Finds the largest text scale whose glyphs and shadow stay inside the center disk. */
    public static float fitCenterTextScale(int textWidth, int fontHeight, float desiredScale,
                                          float yFromCenter, float innerRadius) {
        if (textWidth <= 0 || fontHeight <= 0 || desiredScale <= 0f || innerRadius <= 0f) return 0f;
        float safeRadius = Math.max(1f, innerRadius - 4f);
        float low = 0f;
        float high = desiredScale;
        for (int i = 0; i < 18; i++) {
            float scale = (low + high) * 0.5f;
            float maxY = Math.max(Math.abs(yFromCenter),
                    Math.abs(yFromCenter + (fontHeight + 1f) * scale));
            float maxX = textWidth * scale * 0.5f + 1f;
            if (maxX * maxX + maxY * maxY <= safeRadius * safeRadius) low = scale;
            else high = scale;
        }
        return low;
    }

    private static void drawCenterFitted(net.minecraft.client.gui.FontRenderer fr, String text,
                                         float cx, float y, float desiredScale, int rgb,
                                         float cy, float innerR) {
        float scale = fitCenterTextScale(fr.getStringWidth(text), fr.FONT_HEIGHT,
                desiredScale, y - cy, innerR);
        if (scale > 0.05f) drawCentered(fr, text, cx, y, scale, rgb);
    }

    private static int maxCenterTextWidth(int fontHeight, float scale, float yFromCenter, float innerR) {
        if (fontHeight <= 0 || scale <= 0f || innerR <= 0f) return 0;
        float safeRadius = Math.max(1f, innerR - 4f);
        float maxY = Math.max(Math.abs(yFromCenter),
                Math.abs(yFromCenter + (fontHeight + 1f) * scale));
        if (maxY >= safeRadius) return 0;
        float maxX = (float) Math.sqrt(safeRadius * safeRadius - maxY * maxY) - 1f;
        return Math.max(0, (int) Math.floor(maxX * 2f / scale));
    }

    private static void drawCenterName(net.minecraft.client.gui.FontRenderer fr, String name,
                                       int cx, int y, int cy, float innerR) {
        if (fitCenterTextScale(fr.getStringWidth(name), fr.FONT_HEIGHT, 0.8f,
                y - cy, innerR) >= 0.62f) {
            drawCenterFitted(fr, name, cx, y, 0.8f, TEXT, cy, innerR);
            return;
        }
        int split = -1;
        for (int i = 1; i < name.length() - 1; i++) {
            if (name.charAt(i) == ' ' && (split < 0 || Math.abs(i - name.length() / 2) < Math.abs(split - name.length() / 2))) split = i;
        }
        if (split < 0) {
            int maxWidth = maxCenterTextWidth(fr.FONT_HEIGHT, 0.8f, y - cy, innerR);
            String shortened = fr.trimStringToWidth(name, Math.max(0, maxWidth - fr.getStringWidth("..."))) + "...";
            drawCenterFitted(fr, shortened, cx, y, 0.8f, TEXT, cy, innerR);
        } else {
            int firstY = y - 4;
            int secondY = y + 5;
            int firstWidth = maxCenterTextWidth(fr.FONT_HEIGHT, 0.8f, firstY - cy, innerR);
            int secondWidth = maxCenterTextWidth(fr.FONT_HEIGHT, 0.8f, secondY - cy, innerR);
            String first = fr.trimStringToWidth(name.substring(0, split), firstWidth);
            String second = fr.trimStringToWidth(name.substring(split + 1), secondWidth);
            drawCenterFitted(fr, first, cx, firstY, 0.8f, TEXT, cy, innerR);
            drawCenterFitted(fr, second, cx, secondY, 0.8f, TEXT, cy, innerR);
        }
    }

    /** Returns the hovered slice index (0-based), or -1 if cursor is in the dead-zone. */
    public static int computeHovered(int mouseX, int mouseY) {
        return computeHovered(mouseX, mouseY, -1);
    }

    public static int computeHovered(int mouseX, int mouseY, int previousSlot) {
        Minecraft mc = Minecraft.getMinecraft();
        ScaledResolution sr = new ScaledResolution(mc);
        int cx = sr.getScaledWidth() / 2;
        int cy = sr.getScaledHeight() / 2;
        float scale = getWheelScale(sr.getScaledWidth(), sr.getScaledHeight());
        return computeHoveredStableAt(mouseX, mouseY, cx, cy, INNER_R * scale, OUTER_R * scale, previousSlot);
    }

    /** Two degrees of boundary tolerance stops flicker; entering the center always cancels immediately. */
    public static int computeHoveredStableAt(int mouseX, int mouseY, int cx, int cy,
                                             float innerR, float outerR, int previousSlot) {
        int candidate = computeHoveredAt(mouseX, mouseY, cx, cy, innerR, outerR);
        if (candidate < 0 || previousSlot < 0 || previousSlot >= SLOT_COUNT || candidate == previousSlot) return candidate;
        double angle = Math.atan2(mouseY - cy, mouseX - cx);
        double delta = Math.atan2(Math.sin(angle - sliceMid(previousSlot)), Math.cos(angle - sliceMid(previousSlot)));
        return Math.abs(delta) < Math.PI / SLOT_COUNT + Math.toRadians(2.0) ? previousSlot : candidate;
    }

    /** Same geometry as {@link #computeHovered} for an arbitrary wheel center and inner radius. */
    public static int computeHoveredAt(int mouseX, int mouseY, int cx, int cy, float innerR, float outerR) {
        return computeHoveredAt(mouseX, mouseY, cx, cy, innerR, outerR, -1f);
    }

    /**
     * Like {@link #computeHoveredAt(int, int, int, int, float, float)} but optionally ignores the cursor
     * when it is farther than {@code maxPickRadius} from the wheel center. Used by the wheel slot editor so
     * moving the mouse over the palette does not keep highlighting a random slice by angle alone.
     *
     * @param maxPickRadius pass {@code < 0} for no outer bound (in-game wheel behaviour)
     */
    public static int computeHoveredAt(int mouseX, int mouseY, int cx, int cy, float innerR, float outerR,
                                       float maxPickRadius) {
        double dx = mouseX - cx;
        double dy = mouseY - cy;
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (maxPickRadius >= 0f && dist > maxPickRadius) {
            return -1;
        }
        if (dist < innerR) {
            return -1;
        }
        double angle = Math.atan2(dy, dx) + Math.PI / 2;
        if (angle < 0) {
            angle += 2 * Math.PI;
        }
        int index = (int) (angle / (2 * Math.PI / SLOT_COUNT));
        return Math.min(index, SLOT_COUNT - 1);
    }

    /** Suggested slice colour when assigning a toggle feature from the editor palette. */
    public static int suggestedColorForToggleKey(String actionKey, int slotIndex) {
        if ("fonline".equalsIgnoreCase(actionKey)) {
            return FONLINE_DEFAULT_COLOR;
        }
        if ("WATER_CLARITY".equals(actionKey)) {
            return WATER_CLARITY_DEFAULT_COLOR;
        }
        return DEFAULT_COLORS[Math.abs(slotIndex) % DEFAULT_COLORS.length];
    }

    // ── persistence ───────────────────────────────────────────────────────────

    private static File getSlotsFile() {
        return new File(ConfigManager.getGomodDataDirectory(), "radialMenu.json");
    }

    public static void loadFromConfig() {
        enabled = ConfigManager.isRadialMenuEnabled();
        File f = getSlotsFile();
        if (!f.exists()) {
            resetToDefaults();
            saveToFile();
            return;
        }
        boolean migrated = false;
        try (FileReader reader = new FileReader(f)) {
            JsonObject root = new JsonParser().parse(reader).getAsJsonObject();
            if (root.has("slots")) {
                JsonArray arr = root.getAsJsonArray("slots");
                for (int i = 0; i < SLOT_COUNT && i < arr.size(); i++) {
                    JsonObject obj = arr.get(i).getAsJsonObject();
                    RadialMenuItem item = new RadialMenuItem();
                    if (obj.has("label"))       item.setLabel(obj.get("label").getAsString());
                    if (obj.has("color"))       item.setColor(obj.get("color").getAsInt());
                    if (obj.has("enabled"))     item.setSlotEnabled(obj.get("enabled").getAsBoolean());
                    if (obj.has("actionType")) {
                        try {
                            item.setActionType(RadialMenuItem.ActionType.valueOf(obj.get("actionType").getAsString()));
                        } catch (IllegalArgumentException ignored) {}
                    }
                    if (obj.has("actionValue")) item.setActionValue(obj.get("actionValue").getAsString());
                    if (migrateLegacySlot(item) || migrateSlotByIndex(i, item)) {
                        migrated = true;
                    }
                    slots[i] = item;
                }
            }
        } catch (Exception e) {
            System.err.println("[gomod] Failed to load radialMenu.json: " + e.getMessage());
            resetToDefaults();
            return;
        }
        if (sanitizePublicRestrictedSlots()) {
            migrated = true;
        }
        if (migrated) {
            saveToFile();
        }
    }

    /** Clears wheel slices that use internal-only actions on public builds. */
    private static boolean sanitizePublicRestrictedSlots() {
        if (GomodInternalFeatures.isEnabled()) {
            return false;
        }
        boolean changed = false;
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (GomodInternalFeatures.isRestrictedRadialSlot(slots[i])) {
                slots[i] = new RadialMenuItem("", DEFAULT_COLORS[i % DEFAULT_COLORS.length], false,
                        RadialMenuItem.ActionType.NONE, "");
                changed = true;
            }
        }
        return changed;
    }

    public static void saveToFile() {
        try {
            File dir = ConfigManager.getGomodDataDirectory();
            if (!dir.isDirectory()) dir.mkdirs();
            File f = getSlotsFile();
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            for (RadialMenuItem slot : slots) {
                JsonObject obj = new JsonObject();
                obj.addProperty("label",       slot.getLabel());
                obj.addProperty("color",       slot.getColor());
                obj.addProperty("enabled",     slot.isSlotEnabled());
                obj.addProperty("actionType",  slot.getActionType().name());
                obj.addProperty("actionValue", slot.getActionValue());
                arr.add(obj);
            }
            root.add("slots", arr);
            try (FileWriter w = new FileWriter(f)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(root, w);
            }
        } catch (Exception e) {
            System.err.println("[gomod] Failed to save radialMenu.json: " + e.getMessage());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static float sliceStart(int i) {
        return (float) (i * 2.0 * Math.PI / SLOT_COUNT) - (float) (Math.PI / 2);
    }

    private static float sliceEnd(int i) {
        return (float) ((i + 1) * 2.0 * Math.PI / SLOT_COUNT) - (float) (Math.PI / 2);
    }

    private static float sliceMid(int i) {
        return (float) ((i + 0.5) * 2.0 * Math.PI / SLOT_COUNT) - (float) (Math.PI / 2);
    }

    private static float findMaxLabelScaleThatFits(int sliceIndex, int cx, int cy, int tx, int tyCenter,
                                                   int labelWidth, int fontHeight,
                                                   float outerEdge, float cap) {
        if (cap <= 0f) return 0f;
        float lo = 0f;
        float hi = cap;
        for (int iter = 0; iter < 14; iter++) {
            float mid = (lo + hi) * 0.5f;
            if (doesLabelFitInSlice(sliceIndex, cx, cy, tx, tyCenter, labelWidth, fontHeight, outerEdge, mid)) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return Math.max(0.1f, lo);
    }

    private static boolean doesLabelFitInSlice(int sliceIndex, int cx, int cy, int tx, int tyCenter,
                                               int labelWidth, int fontHeight, float outerEdge, float scale) {
        float halfW = ((labelWidth + 1f) * scale) * 0.5f;
        float h = (fontHeight + 1f) * scale;

        float left = tx - halfW;
        float right = tx + halfW;
        float top = tyCenter - h * 0.5f;
        float bottom = tyCenter + h * 0.5f;

        float[][] points = new float[][]{
                {left, top}, {right, top}, {left, bottom}, {right, bottom},
                {tx, top}, {tx, bottom}, {left, tyCenter}, {right, tyCenter}
        };

        float start = normalizeAngle(sliceStart(sliceIndex) + GAP_ANGLE);
        float end = normalizeAngle(sliceEnd(sliceIndex) - GAP_ANGLE);
        float minR = INNER_R + 2f;
        float maxR = outerEdge - 3f;

        for (float[] p : points) {
            float dx = p[0] - cx;
            float dy = p[1] - cy;
            float r = (float) Math.sqrt(dx * dx + dy * dy);
            if (r < minR || r > maxR) return false;
            float angle = normalizeAngle((float) Math.atan2(dy, dx));
            if (!isAngleBetween(angle, start, end)) return false;
        }
        return true;
    }

    private static float normalizeAngle(float a) {
        float twoPi = (float) (Math.PI * 2.0);
        while (a < 0f) a += twoPi;
        while (a >= twoPi) a -= twoPi;
        return a;
    }

    private static boolean isAngleBetween(float angle, float start, float end) {
        if (start <= end) {
            return angle >= start && angle <= end;
        }
        return angle >= start || angle <= end;
    }

    private static Map<String, ItemStack> buildIconStacks() {
        Map<String, ItemStack> icons = new LinkedHashMap<String, ItemStack>();
        icons.put("COMMAND", new ItemStack(Items.command_block_minecart));
        icons.put("FONLINE", new ItemStack(Items.clock));
        icons.put("CHAT_MESSAGE", new ItemStack(Items.writable_book));
        icons.put("WATER_CLARITY", new ItemStack(Items.water_bucket));
        icons.put("TEAM_SPAM_REFRESH", new ItemStack(Items.paper));
        icons.put("TEAM_SPAM", createPlayerHead("Alex"));
        icons.put("VISIBLE_SPECS", new ItemStack(Items.armor_stand));
        icons.put("SEE_SPECTATORS", new ItemStack(Items.skull, 1, 1));
        icons.put("BEST_ARMOR", new ItemStack(Items.diamond_chestplate));
        icons.put("TAB_KILLERS", new ItemStack(Items.skull, 1, 0));
        icons.put("MOB_DISGUISE_LAST", new ItemStack(Items.spawn_egg, 1, 50));
        icons.put("MOB_NAMETAGS", new ItemStack(Items.name_tag));
        icons.put("LOBBY_PING", new ItemStack(Items.repeater));
        icons.put("PLAYER_TARGET", new ItemStack(Items.compass));
        icons.put("KILL_COUNTER", new ItemStack(Items.potionitem, 1, 0));
        icons.put("INVENTORY_HUD", new ItemStack(Items.chest_minecart));
        icons.put("PERSPECTIVE", new ItemStack(Items.ender_eye));
        icons.put("TEAMMATE_GLOW", new ItemStack(Items.glowstone_dust));
        icons.put("TEAMMATE_TRAIL", new ItemStack(Items.lead));
        icons.put("BEST_ARMOR_PERCENTAGE", new ItemStack(Items.iron_chestplate));
        icons.put("CHEST_ROUTES", new ItemStack(Blocks.chest));
        icons.put("HIDE_DATE_GAME_ID", new ItemStack(Items.stick));
        icons.put("SELF_OPACITY", new ItemStack(Items.armor_stand));
        icons.put("BARRIER_VISIBILITY", new ItemStack(Blocks.barrier));
        icons.put("CAGE_TRACERS", new ItemStack(Items.blaze_rod));
        icons.put("MOB_HEALTH", new ItemStack(Items.speckled_melon));
        icons.put("ITEM_ESP", new ItemStack(Items.ender_pearl));
        icons.put("DEFAULT", new ItemStack(Items.nether_star));
        return icons;
    }

    private static void warmUpWheelRenderPipeline(Minecraft mc) {
        try {
            Class.forName("me.napim.gomod.gui.RadialMenuOverlayScreen");
        } catch (ClassNotFoundException ignored) {
        }
        new ScaledResolution(mc).getScaledWidth();

        RenderItem ri = mc.getRenderItem();
        ItemModelMesher mesher = ri.getItemModelMesher();
        if (mesher != null) {
            for (ItemStack stack : ICON_STACKS.values()) {
                try {
                    mesher.getItemModel(stack);
                } catch (Throwable ignored) {
                }
            }
        }

        final int offX = -64;
        final int offY = -64;
        RenderHelper.enableGUIStandardItemLighting();
        try {
            ri.zLevel = -150.0F;
            for (ItemStack stack : ICON_STACKS.values()) {
                try {
                    ri.renderItemAndEffectIntoGUI(stack, offX, offY);
                } catch (Throwable ignored) {
                }
            }
        } finally {
            ri.zLevel = 0.0F;
            RenderHelper.disableStandardItemLighting();
        }
    }

    public static ItemStack getSlotIconStack(RadialMenuItem slot) {
        if (slot == null || !slot.isSlotEnabled() || slot.getActionType() == RadialMenuItem.ActionType.NONE) {
            return null;
        }
        if (slot.getActionType() == RadialMenuItem.ActionType.COMMAND) {
            return ICON_STACKS.get("fonline".equalsIgnoreCase(slot.getActionValue().trim()) ? "FONLINE" : "COMMAND");
        }
        if (slot.getActionType() == RadialMenuItem.ActionType.CHAT_MESSAGE) {
            return ICON_STACKS.get("CHAT_MESSAGE");
        }
        ItemStack icon = ICON_STACKS.get(slot.getActionValue());
        return icon != null ? icon : ICON_STACKS.get("DEFAULT");
    }

    private static ItemStack createPlayerHead(String playerName) {
        ItemStack head = new ItemStack(Items.skull, 1, 3);
        net.minecraft.nbt.NBTTagCompound tag = new net.minecraft.nbt.NBTTagCompound();
        tag.setString("SkullOwner", playerName);
        head.setTagCompound(tag);
        return head;
    }

    private static void drawSlotItemIcon(RenderItem renderItem, ItemStack stack, int centerX, int centerY,
                                         boolean dimmed, float extraTint) {
        final float iconScale = 1.35f;
        final float iconSize = 16f * iconScale;
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(centerX - iconSize / 2f, centerY - iconSize / 2f, 0f);
            GlStateManager.scale(iconScale, iconScale, 1f);
            float t = (dimmed ? 0.6f : 1f) * (extraTint <= 0f ? 1f : extraTint);
            GlStateManager.color(t, t, t, 1f);
            renderItem.renderItemAndEffectIntoGUI(stack, 0, 0);
            // Display-only icons have no stack count, durability bar or cooldown overlay.
        } finally {
            GlStateManager.color(1f, 1f, 1f, 1f);
            GlStateManager.popMatrix();
        }
    }

    /**
     * Per-action extra tint multiplier applied on top of any active/disabled dim, so two slots that share
     * the same {@link ItemStack} (Spectator opacity / Self opacity → armor stand) still look different.
     */
    public static float iconExtraTintFor(RadialMenuItem slot) {
        if (slot == null || slot.getActionType() != RadialMenuItem.ActionType.TOGGLE_FEATURE) {
            return 1f;
        }
        if ("VISIBLE_SPECS".equals(slot.getActionValue())) {
            return 0.62f;
        }
        return 1f;
    }

    /** Human-readable name for a slot (wheel hover, editor labels). */
    public static String getSlotHoverCaption(RadialMenuItem slot) {
        if (slot == null || !slot.isSlotEnabled() || slot.getActionType() == RadialMenuItem.ActionType.NONE) {
            return "";
        }
        String label = slot.getLabel() == null ? "" : slot.getLabel().trim();
        if (!label.isEmpty()) {
            return label;
        }
        if (slot.getActionType() == RadialMenuItem.ActionType.TOGGLE_FEATURE) {
            return slot.getActionDisplay();
        }
        if (slot.getActionType() == RadialMenuItem.ActionType.COMMAND) {
            return "/" + slot.getActionValue();
        }
        if (slot.getActionType() == RadialMenuItem.ActionType.CHAT_MESSAGE) {
            return "Chat message";
        }
        return "";
    }

    /** Works for both keyboard keys (keyCode > 0) and mouse buttons (keyCode < 0). */
    private static boolean isKeyCurrentlyDown(int keyCode) {
        if (keyCode > 0)  return Keyboard.isKeyDown(keyCode);
        if (keyCode < 0)  return Mouse.isButtonDown(-(keyCode + 1));
        return false;
    }

    /** Draw a solid-color screen-space rectangle (no texture). */
    private static void drawRect(int x1, int y1, int x2, int y2, int color, Minecraft mc) {
        float a = ((color >> 24) & 0xFF) / 255f;
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;

        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(r, g, b, a);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x1, y2);
        GL11.glVertex2f(x2, y2);
        GL11.glVertex2f(x2, y1);
        GL11.glVertex2f(x1, y1);
        GL11.glEnd();
        GlStateManager.enableTexture2D();
    }

    private static void resetToDefaults() {
        Object[][] defs = {
            {"BestArmor",           "TOGGLE_FEATURE", "BEST_ARMOR",           16729156, true},
            {"Barrier blocks",      "TOGGLE_FEATURE", "BARRIER_VISIBILITY",   16746564, true},
            {"Refresh player list", "TOGGLE_FEATURE", "TEAM_SPAM_REFRESH",    16768324, true},
            {"Team Spam",           "TOGGLE_FEATURE", "TEAM_SPAM",            4513092,  true},
            {"/fonline",            "COMMAND",        "fonline",              4513279,  true},
            {"Water Clarity",       "TOGGLE_FEATURE", "WATER_CLARITY",        4513279,  true},
            {"Tab victims",         "TOGGLE_FEATURE", "TAB_KILLERS",          11158783, true},
            {"",                    "NONE",           "",                     11184810, false},
        };
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (i < defs.length) {
                RadialMenuItem.ActionType type = RadialMenuItem.ActionType.valueOf((String) defs[i][1]);
                String actionValue = (String) defs[i][2];
                int color = (Integer) defs[i][3];
                boolean slotEnabled = (Boolean) defs[i][4];
                RadialMenuItem draft = new RadialMenuItem(
                    (String) defs[i][0],
                    color,
                    slotEnabled,
                    type,
                    actionValue);
                if (GomodInternalFeatures.isRestrictedRadialSlot(draft)) {
                    slots[i] = new RadialMenuItem("", DEFAULT_COLORS[i % DEFAULT_COLORS.length], false,
                            RadialMenuItem.ActionType.NONE, "");
                } else {
                    slots[i] = draft;
                }
            } else {
                slots[i] = new RadialMenuItem("", DEFAULT_COLORS[i % DEFAULT_COLORS.length], false,
                    RadialMenuItem.ActionType.NONE, "");
            }
        }
    }

    /**
     * Upgrade old saved wheel actions to the current defaults.
     * This keeps existing users in sync even when they already have radialMenu.json.
     */
    private static boolean migrateLegacySlot(RadialMenuItem item) {
        if (item == null) return false;
        boolean changed = false;
        if (item.getActionType() == RadialMenuItem.ActionType.TOGGLE_FEATURE) {
            String value = item.getActionValue();
            if ("NO_HIT_DELAY".equals(value)) {
                item.setActionValue("BEST_ARMOR");
                if (isBlank(item.getLabel()) || "No Hit Delay".equalsIgnoreCase(item.getLabel())) {
                    item.setLabel("BestArmor");
                }
                changed = true;
            } else if ("MOB_TARGET".equals(value)
                    || "TRANSPARENT_MOUNTS".equals(value)
                    || "HORSE_DONKEY".equals(value)
                    || "MORE_HUD".equals(value)
                    || "INSTANT_ROD".equals(value)
                    || "TEAM_LOOT".equals(value)) {
                item.setActionType(RadialMenuItem.ActionType.NONE);
                item.setActionValue("");
                item.setLabel("");
                item.setSlotEnabled(false);
                changed = true;
            } else if ("LOOT_CALL".equals(value)) {
                item.setActionValue("TEAM_SPAM");
                if (isBlank(item.getLabel()) || "Loot Call".equalsIgnoreCase(item.getLabel())) {
                    item.setLabel("Team Spam");
                }
                changed = true;
            } else if ("STAR_TEXT".equals(value)) {
                item.setActionValue("TEAM_SPAM_REFRESH");
                if (isBlank(item.getLabel())
                        || "Star Text".equalsIgnoreCase(item.getLabel())
                        || "Better BLITZ texts".equalsIgnoreCase(item.getLabel())
                        || "Refresh Team List".equalsIgnoreCase(item.getLabel())) {
                    item.setLabel("Refresh player list");
                }
                changed = true;
            } else if ("TEAM_SPAM_REFRESH".equals(value)
                    && ("Team Spam Refresh".equalsIgnoreCase(item.getLabel())
                        || "Refresh players list".equalsIgnoreCase(item.getLabel()))) {
                item.setLabel("Refresh player list");
                changed = true;
            } else if ("MOB_DISGUISE_LAST".equals(value)) {
                String lbl = item.getLabel();
                if (isBlank(lbl)
                        || "Mob Disguise (Last)".equalsIgnoreCase(lbl)
                        || "Mob Disguise (last)".equalsIgnoreCase(lbl)
                        || "Last Mob Disguise".equalsIgnoreCase(lbl)
                        || "MobDisguise (Last)".equalsIgnoreCase(lbl)) {
                    item.setLabel("Mob Disguise");
                    changed = true;
                }
            } else if ("VISIBLE_SPECS".equals(value)) {
                if (isBlank(item.getLabel())
                        || "Visible Specs".equalsIgnoreCase(item.getLabel())
                        || "Visible Spectators".equalsIgnoreCase(item.getLabel())
                        || "Spec opacity".equalsIgnoreCase(item.getLabel())
                        || "Spectator opacity".equalsIgnoreCase(item.getLabel())) {
                    item.setLabel("Spectator settings");
                    changed = true;
                }
            } else if ("PLAYER_TARGET".equals(value)
                    && !isBlank(item.getLabel())
                    && "Player Target".equalsIgnoreCase(item.getLabel())) {
                item.setLabel("Teammate tracker");
                changed = true;
            } else if ("BEST_ARMOR".equals(value)
                    && "Best Armor".equalsIgnoreCase(item.getLabel())) {
                item.setLabel("BestArmor");
                changed = true;
            } else if ("BEST_ARMOR_PERCENTAGE".equals(value)
                    && ("Best armor %".equalsIgnoreCase(item.getLabel())
                        || "Best Armor %".equalsIgnoreCase(item.getLabel()))) {
                item.setLabel("BestArmor %");
                changed = true;
            }
        }
        if (GomodInternalFeatures.isEnabled()
                && item.getActionType() == RadialMenuItem.ActionType.CHAT_MESSAGE
                && "gg".equalsIgnoreCase(item.getActionValue().trim())) {
            item.setActionType(RadialMenuItem.ActionType.TOGGLE_FEATURE);
            item.setActionValue("MOB_DISGUISE_LAST");
            if (isBlank(item.getLabel())
                    || "GG".equalsIgnoreCase(item.getLabel())
                    || "Last Mob Disguise".equalsIgnoreCase(item.getLabel())) {
                item.setLabel("Mob Disguise");
            }
            changed = true;
        }
        if (item.getActionType() == RadialMenuItem.ActionType.COMMAND
                && "lobby".equalsIgnoreCase(item.getActionValue().trim())) {
            item.setActionType(RadialMenuItem.ActionType.TOGGLE_FEATURE);
            item.setActionValue("TAB_KILLERS");
            if (isBlank(item.getLabel()) || "/lobby".equalsIgnoreCase(item.getLabel())) {
                item.setLabel("Tab victims");
            }
            changed = true;
        }
        if (item.getActionType() == RadialMenuItem.ActionType.TOGGLE_FEATURE
                && "TAB_KILLERS".equals(item.getActionValue())
                && "Tab Killers".equalsIgnoreCase(item.getLabel())) {
            item.setLabel("Tab victims");
            changed = true;
        }
        return changed;
    }

    private static boolean isFonlineCommand(RadialMenuItem slot) {
        return slot != null
                && slot.getActionType() == RadialMenuItem.ActionType.COMMAND
                && "fonline".equalsIgnoreCase(slot.getActionValue().trim());
    }

    private static int getFonlinePreferredColor() {
        for (RadialMenuItem item : slots) {
            if (item == null || item.getActionType() != RadialMenuItem.ActionType.TOGGLE_FEATURE) continue;
            String key = item.getActionValue();
            if ("TEAM_SPAM_REFRESH".equals(key) || "TEAM_SPAM".equals(key)) {
                return item.getColor();
            }
        }
        return FONLINE_DEFAULT_COLOR;
    }

    /**
     * Move old default slot arrangement:
     * slot 5: Water Clarity -> /fonline
     * slot 6: /fonline -> Water Clarity
     * Only applies when slot content still matches old defaults.
     */
    private static boolean migrateSlotByIndex(int index, RadialMenuItem item) {
        if (item == null) return false;
        boolean changed = false;
        // Swap Team Spam <-> Refresh player list when slot content is still old default.
        if (index == 2
                && item.getActionType() == RadialMenuItem.ActionType.TOGGLE_FEATURE
                && "TEAM_SPAM".equals(item.getActionValue())
                && "Team Spam".equalsIgnoreCase(item.getLabel())) {
            item.setActionValue("TEAM_SPAM_REFRESH");
            item.setLabel("Refresh player list");
            changed = true;
        } else if (index == 3
                && item.getActionType() == RadialMenuItem.ActionType.TOGGLE_FEATURE
                && "TEAM_SPAM_REFRESH".equals(item.getActionValue())
                && ("Refresh player list".equalsIgnoreCase(item.getLabel())
                    || "Refresh players list".equalsIgnoreCase(item.getLabel()))) {
            item.setActionValue("TEAM_SPAM");
            item.setLabel("Team Spam");
            changed = true;
        }
        if (index == 4
                && item.getActionType() == RadialMenuItem.ActionType.TOGGLE_FEATURE
                && "WATER_CLARITY".equals(item.getActionValue())
                && "Water Clarity".equalsIgnoreCase(item.getLabel())) {
            item.setActionType(RadialMenuItem.ActionType.COMMAND);
            item.setActionValue("fonline");
            item.setLabel("/fonline");
            item.setColor(FONLINE_DEFAULT_COLOR);
            return true;
        }
        if (index == 5
                && item.getActionType() == RadialMenuItem.ActionType.COMMAND
                && "fonline".equalsIgnoreCase(item.getActionValue().trim())
                && "/fonline".equalsIgnoreCase(item.getLabel())) {
            item.setActionType(RadialMenuItem.ActionType.TOGGLE_FEATURE);
            item.setActionValue("WATER_CLARITY");
            item.setLabel("Water Clarity");
            item.setColor(WATER_CLARITY_DEFAULT_COLOR);
            return true;
        }
        return changed;
    }

    private static int defaultColorForSlot(int slotIndex, String actionValue) {
        if ("fonline".equalsIgnoreCase(actionValue)) return FONLINE_DEFAULT_COLOR;
        if ("WATER_CLARITY".equals(actionValue)) return WATER_CLARITY_DEFAULT_COLOR;
        return DEFAULT_COLORS[slotIndex % DEFAULT_COLORS.length];
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** Action-like slices should stay neutral gray (not ON/OFF semantic colors). */
    private static boolean isFunctionActionSlot(RadialMenuItem slot) {
        if (slot == null || slot.getActionType() != RadialMenuItem.ActionType.TOGGLE_FEATURE) {
            return false;
        }
        String key = slot.getActionValue();
        return false; // TEAM_SPAM/REFRESH now use dynamic busy state instead
    }

    /**
     * Slots whose default color scheme is "yellow when ready, gray while busy" instead of
     * the regular green/red ON/OFF semantics. These are the one-shot / cooldown-gated
     * actions: /fonline, Team Spam, Refresh player list.
     */
    private static boolean isCooldownableSlot(RadialMenuItem slot) {
        if (slot == null || !slot.isSlotEnabled()) {
            return false;
        }
        if (slot.getActionType() == RadialMenuItem.ActionType.COMMAND
                && slot.getActionValue() != null
                && "fonline".equalsIgnoreCase(slot.getActionValue().trim())) {
            return true;
        }
        if (slot.getActionType() == RadialMenuItem.ActionType.TOGGLE_FEATURE) {
            String key = slot.getActionValue();
            return "TEAM_SPAM".equals(key) || "TEAM_SPAM_REFRESH".equals(key);
        }
        return false;
    }

    /** Returns whether a slot is currently "busy" and should render gray (feature running / cooldown). */
    private static boolean isSlotBusy(RadialMenuItem slot) {
        if (slot == null || !slot.isSlotEnabled()) return false;
        if (slot.getActionType() == RadialMenuItem.ActionType.TOGGLE_FEATURE) {
            String key = slot.getActionValue();
            if ("TEAM_SPAM".equals(key)) {
                return me.napim.gomod.command.TeamSpamCommand.isRunning();
            }
            if ("TEAM_SPAM_REFRESH".equals(key)) {
                if (me.napim.gomod.gui.TeamSpamGUI.isRefreshing) {
                    return true;
                }
                return me.napim.gomod.command.TeamSpamCommand.RefreshCooldownPublic.timeRemainingMs() > 0L;
            }
        }
        if (slot.getActionType() == RadialMenuItem.ActionType.COMMAND
                && "fonline".equalsIgnoreCase(slot.getActionValue().trim())) {
            return me.napim.gomod.features.FriendListScanner.isCollecting();
        }
        return false;
    }

    /** Returns whether a toggle-feature slot is currently ON; null when unsupported/not a toggle slot. */
    private static Boolean getToggleFeatureState(RadialMenuItem slot) {
        if (slot == null || slot.getActionType() != RadialMenuItem.ActionType.TOGGLE_FEATURE) {
            return null;
        }
        String key = slot.getActionValue();
        boolean on;
        switch (key) {
            case "VISIBLE_SPECS":
                return null;
            case "SEE_SPECTATORS":
                on = SpectatorRelay.isEnabled();
                break;
            case "LOOT_CALL":
                on = LootCall.isEnabled();
                break;
            case "STAR_TEXT":
                on = ConfigManager.isStarTextEnabled();
                break;
            case "WATER_CLARITY":
                on = WaterClarity.isEnabled();
                break;
            case "MOB_NAMETAGS":
                on = MobNametags.isEnabled();
                break;
            case "LOBBY_PING":
                on = LobbyPingDisplay.isEnabled();
                break;
            case "BEST_ARMOR":
                on = BestArmorDisplay.isEnabled();
                break;
            case "KILL_COUNTER":
                on = ConfigManager.isKillCounterEnabled();
                break;
            case "MOB_HEALTH":
                on = MobHealthDisplay.isEnabled();
                break;
            case "PLAYER_TARGET":
                on = PlayerTarget.isEnabled();
                break;
            case "INVENTORY_HUD":
                on = ConfigManager.isInventoryHUDEnabled();
                break;
            case "PERSPECTIVE":
                on = ConfigManager.isPerspectiveEnabled();
                break;
            case "TEAMMATE_GLOW":
                on = PlayerTarget.isGlowTeammateEnabled();
                break;
            case "TEAMMATE_TRAIL":
                on = PlayerTarget.isTeammateTrailEnabled();
                break;
            case "BEST_ARMOR_PERCENTAGE":
                on = ConfigManager.getBestArmorPercentageMode() != ConfigManager.BEST_ARMOR_PERCENTAGE_OFF;
                break;
            case "CHEST_ROUTES":
                on = ConfigManager.isChestRoutesRenderEnabled();
                break;
            case "HIDE_DATE_GAME_ID":
                on = ConfigManager.isHideDateGameIdEnabled();
                break;
            case "CAGE_TRACERS":
                on = PlayerTracers.isCagesAutoEnabled();
                break;
            case "ITEM_ESP":
                on = UselessItemOverlay.isEnabled();
                break;
            case "TAB_KILLERS":
                on = ConfigManager.isKillCounterTabKillersEnabled();
                break;
            case "SELF_OPACITY":
                on = ConfigManager.isSelfOpacityEnabled();
                break;
            case "BARRIER_VISIBILITY":
                on = ConfigManager.isBarrierVisibilityEnabled();
                break;
            case "TEAM_SPAM":
            case "TEAM_SPAM_REFRESH":
                return null;
            case "MOB_DISGUISE_LAST":
                on = LocalMobDisguise.isActive();
                break;
            default:
                return null;
        }
        return on;
    }
}
