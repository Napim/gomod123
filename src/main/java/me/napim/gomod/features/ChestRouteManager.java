package me.napim.gomod.features;

import me.napim.gomod.Main;
import me.napim.gomod.util.ClientGuiHelper;
import me.napim.gomod.util.ClientInputHelper;
import me.napim.gomod.util.ChestRoutePathSmoother;
import me.napim.gomod.util.GlowTrailRenderer;
import me.napim.gomod.util.MinecraftPrivateAccess;
import me.napim.gomod.util.RouteGroundSnap;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Block checkpoints (exact client hit point on the block), saved per map and path name;
 * smoothed 3D line + labels; spawn start captured after "Cages Open:" once you’re teleported to the cage.
 */
public final class ChestRouteManager {
    private static volatile boolean editMode = false;
    private static final List<Vec3> draft = new ArrayList<>();
    private static final int DRAFT_UNDO_LIMIT = 64;
    private static final List<List<Vec3>> draftUndo = new ArrayList<>();

    /**
     * Frozen feet position for draft glow smoothing (set when /ce on). Avoids a trail stalking the player
     * every time {@link #refreshEditModeVisuals()} runs.
     */
    private static Vec3 draftGlowAnchorFeet = null;

    /** Snapshot of checkpoint world pos when drag mode starts; restored if RMB released without sneak. */
    private static Vec3 editDragSnapshot = null;

    /** Sneak + RMB on draft checkpoint = delete; sneak + hold = drag (release RMB to drop). */
    private static boolean editRmbDown;
    private static int editRmbDownTicks;
    private static int editRmbTargetIndex = -1;
    private static boolean editDraggingCheckpoint;
    private static boolean editDragNoticeSent;
    /** True when the current delete/drag gesture was started from LMB (no-sneak flow). */
    private static boolean editInteractionFromLeft;
    private static final int EDIT_LONG_PRESS_TICKS = 11;
    private static final double EDIT_CHECKPOINT_PICK_RADIUS = 2.9;
    /**
     * Rising-edge LMB detection in {@link #clientTickEditInteraction} when {@code Minecraft.clickMouse} mixins do not run
     * (e.g. some Lunar builds).
     */
    private static boolean ceFallbackPrevLmbTick;

    /** Client-only: ray length to pick draft checkpoints for delete/drag (does not change vanilla block reach / AC). */
    private static final double EDIT_CHECKPOINT_PICK_MAX_REACH = 4096.0;
    /** Client-only ray reach for placing/drag-preview blocks while editing. */
    private static final double EDIT_LONG_RAY_REACH = 4096.0;

    /**
     * Lunar: RMB polling is often dead; each vanilla {@code rightClickMouse} bumps this so "hold" still works
     * while the game keeps calling right-click (usually every tick while using item / block).
     */
    private static int editRmbSynthUntilTick = Integer.MIN_VALUE;
    /** Last player tick where vanilla RMB pulse was seen. */
    private static int editRmbLastPulseTick = Integer.MIN_VALUE;
    private static boolean editFeetKeyWasDown;
    private static boolean editToggleRenderKeyWasDown;
    private static boolean editUndoKeyWasDown;

    /** Live drag target from long ray trace; updated every render frame (draft position commits on RMB release). */
    private static Vec3 editDragPreviewPos = null;
    /** When the ray briefly misses (air/leaves), keep showing/placing the last good hit so labels/glow do not blink. */
    private static Vec3 editDragPreviewLastValid = null;
    /** Vertical nudge applied during CE drag-preview so we can raise/lower while holding a checkpoint. */
    private static double editDragVerticalOffset = 0.0;
    /** Fixed trail timestamp while dragging so {@link GlowTrailRenderer} pulse does not reset every frame. */
    private static long editDraftGlowStableTimeMs = -1L;

    private static final List<DraftLabelAnim> DRAFT_LABEL_ANIMS = new ArrayList<>();

    /** Fading world labels after delete (red) or after move (green). */
    private static final class DraftLabelAnim {
        final double x;
        final double y;
        final double z;
        final String text;
        /** 0 = red delete, 1 = green placed */
        final int mode;
        int ticksLeft;
        final int ticksTotal;

        DraftLabelAnim(double x, double y, double z, String text, int mode, int ticksTotal) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.text = text;
            this.mode = mode;
            this.ticksTotal = ticksTotal;
            this.ticksLeft = ticksTotal;
        }
    }

    /** When true, first "Cages Open" in a session loads every path for the current map. */
    private static volatile boolean autoloadOnCagesOpen = true;

    /**
     * When true (after cages-open autoload), numbered checkpoint world labels are hidden; start "S" still shows.
     * Cleared on manual /cro load or unload.
     */
    private static volatile boolean suppressNumberedCheckpointLabels = false;

    private static World sessionWorld = null;
    private static boolean cagesOpenCapturedThisSession = false;
    private static Vec3 cagesSpawnFeet = null;
    /**
     * After "Cages Open" appears, wait this many ticks before locking feet — Hypixel teleports you to the
     * cage shortly after; capturing too early used lobby / wrong YZ.
     */
    private static final int CAGES_CAPTURE_DELAY_TICKS = 40;
    /** After the cages line disappears this long, allow a new capture next match (same world). */
    private static final int CAGES_LINE_GONE_TICKS_TO_RESET = 45;

    private static boolean cagesAutoloadPending = false;
    private static int cagesAutoloadWaitTicks = 0;
    private static int cagesOpenAbsentStreak = 0;

    /**
     * Last map slug read from {@code Map:} / {@code Arena:} while the sidebar still shows it (pregame lobby).
     * Hypixel removes that line once you are in cages, so autoload uses this when live resolution fails.
     */
    private static String lastRememberedLobbyMapSlug = null;

    private static final List<ActiveOverlay> activeOverlays = new ArrayList<>();

    public static final class CheckpointLabel {
        public final double x, y, z;
        public final String text;
        public final int colorArgb;

        public CheckpointLabel(double x, double y, double z, String text, int colorArgb) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.text = text;
            this.colorArgb = colorArgb;
        }
    }

    private static final class ActiveOverlay {
        final String pathName;
        final int colorRgb;
        final Vec3 start;
        final List<Vec3> checkpoints;

        ActiveOverlay(String pathName, int colorRgb, Vec3 start, List<Vec3> checkpoints) {
            this.pathName = pathName;
            this.colorRgb = colorRgb;
            this.start = start;
            this.checkpoints = checkpoints;
        }
    }

    private ChestRouteManager() {
    }

    public static void init() {
        ChestRouteStorage.load();
    }

    /** After editing {@code chest_routes.json} on disk (e.g. GUI delete map). */
    public static void reloadStorageAndRefreshGlow() {
        ChestRouteStorage.load();
        rebuildGlowFromOverlays();
    }

    public static boolean isAutoloadOnCagesOpen() {
        return autoloadOnCagesOpen;
    }

    public static void setAutoloadOnCagesOpen(boolean v) {
        autoloadOnCagesOpen = v;
    }

    public static void setSuppressNumberedCheckpointLabels(boolean v) {
        suppressNumberedCheckpointLabels = v;
    }

    public static boolean isSuppressingNumberedCheckpointLabels() {
        return suppressNumberedCheckpointLabels;
    }

    public static boolean hasCagesSpawnCaptured() {
        return cagesSpawnFeet != null;
    }

    /** Feet position captured on first scoreboard "Cages Open:" this session; null until then. */
    public static Vec3 getCagesSpawnFeet() {
        return cagesSpawnFeet;
    }

    public static void clientTick() {
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc != null ? mc.theWorld : null;
        if (world != sessionWorld) {
            sessionWorld = world;
            resetSessionForWorldChange();
        }
        if (world == null || mc.thePlayer == null) {
            return;
        }

        rememberMapSlugFromSidebarWhenPresent(mc);

        boolean cagesLine = HypixelScoreboardMapResolver.hasCagesOpenLine(mc);
        if (!cagesLine) {
            if (cagesOpenCapturedThisSession) {
                cagesOpenAbsentStreak++;
                if (cagesOpenAbsentStreak >= CAGES_LINE_GONE_TICKS_TO_RESET) {
                    cagesOpenCapturedThisSession = false;
                    cagesSpawnFeet = null;
                    cagesOpenAbsentStreak = 0;
                }
            }
            // Important: do NOT clear cagesAutoloadPending when the line disappears — Hypixel often removes
            // "Cages Open" from the sidebar before the delay elapses; we still want to capture feet + autoload.
        } else {
            cagesOpenAbsentStreak = 0;
        }

        if (cagesOpenCapturedThisSession) {
            return;
        }

        if (cagesLine && !cagesAutoloadPending) {
            cagesAutoloadPending = true;
            cagesAutoloadWaitTicks = CAGES_CAPTURE_DELAY_TICKS;
        }

        if (!cagesAutoloadPending) {
            return;
        }

        if (cagesAutoloadWaitTicks > 0) {
            cagesAutoloadWaitTicks--;
        }
        if (cagesAutoloadWaitTicks > 0) {
            return;
        }

        EntityPlayerSP p = mc.thePlayer;
        cagesSpawnFeet = new Vec3(p.posX, p.posY, p.posZ);
        cagesOpenCapturedThisSession = true;
        cagesAutoloadPending = false;
        cagesAutoloadWaitTicks = 0;

        if (autoloadOnCagesOpen) {
            String mapKey = resolveMapKeyForAutoload(mc);
            if (mapKey != null && ChestRouteStorage.hasRoute(mapKey)) {
                String err = loadAllPathsForMapInternal(mapKey, cagesSpawnFeet, true);
                if (err == null) {
                    int n = ChestRouteStorage.listPathNames(mapKey).size();
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Cages open — start locked. Auto-loaded "
                            + n + " route(s) for " + mapKey + ".");
                } else {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Cages autoload failed: " + err
                            + EnumChatFormatting.GRAY + " — try /cro loadall");
                }
            } else if (mapKey == null) {
                // Map unknown at cages-open (sidebar often cleared); autoload skipped — no chat spam.
            } else {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Cages open — no saved routes for map \""
                        + mapKey + "\"." + EnumChatFormatting.GRAY + " /ce save or /cro loadall <other_key>");
            }
        }
    }

    /**
     * While {@code Map:} / {@code Arena:} is visible (pregame), remember the slug. In cages that line is gone.
     */
    private static void rememberMapSlugFromSidebarWhenPresent(Minecraft mc) {
        if (mc == null) {
            return;
        }
        String k = HypixelScoreboardMapResolver.tryResolveCurrentMapKey(mc);
        if (k != null && !"unknown".equals(k)) {
            lastRememberedLobbyMapSlug = k;
            return;
        }
        for (String line : HypixelScoreboardMapResolver.collectSidebarTextLines(mc)) {
            String s = HypixelScoreboardMapResolver.extractMapSlugFromLine(line);
            if (s != null && !"unknown".equals(s)) {
                lastRememberedLobbyMapSlug = s;
                return;
            }
        }
    }

    /**
     * At cages-open time the sidebar often no longer shows {@code Map:} — use {@link #lastRememberedLobbyMapSlug} then.
     */
    private static String resolveMapKeyForAutoload(Minecraft mc) {
        if (mc == null) {
            return null;
        }
        String k = HypixelScoreboardMapResolver.tryResolveCurrentMapKey(mc);
        if (k != null && !"unknown".equals(k) && ChestRouteStorage.hasRoute(k)) {
            return k;
        }
        List<String> lines = HypixelScoreboardMapResolver.collectSidebarTextLines(mc);
        for (String line : lines) {
            String slug = HypixelScoreboardMapResolver.extractMapSlugFromLine(line);
            if (slug != null && !"unknown".equals(slug) && ChestRouteStorage.hasRoute(slug)) {
                return slug;
            }
        }
        if (lastRememberedLobbyMapSlug != null && !"unknown".equals(lastRememberedLobbyMapSlug)
                && ChestRouteStorage.hasRoute(lastRememberedLobbyMapSlug)) {
            return lastRememberedLobbyMapSlug;
        }
        List<String> keys = ChestRouteStorage.listMapKeys();
        String best = null;
        int bestLen = 0;
        for (String stored : keys) {
            if (stored == null || stored.isEmpty() || "unknown".equals(stored)) {
                continue;
            }
            if (!ChestRouteStorage.hasRoute(stored)) {
                continue;
            }
            String spaced = stored.replace('_', ' ');
            for (String line : lines) {
                String low = HypixelScoreboardMapResolver.stripFormatting(line).toLowerCase(Locale.ROOT);
                if (low.contains(stored) || (!spaced.equals(stored) && low.contains(spaced))) {
                    if (stored.length() > bestLen) {
                        bestLen = stored.length();
                        best = stored;
                    }
                }
            }
        }
        if (best != null) {
            return best;
        }
        // Do NOT guess the only saved map when resolution fails — that loads the wrong routes on other arenas.
        return null;
    }

    private static void resetSessionForWorldChange() {
        cagesOpenCapturedThisSession = false;
        cagesSpawnFeet = null;
        cagesAutoloadPending = false;
        cagesAutoloadWaitTicks = 0;
        cagesOpenAbsentStreak = 0;
        lastRememberedLobbyMapSlug = null;
        suppressNumberedCheckpointLabels = false;
        DRAFT_LABEL_ANIMS.clear();
        unloadRoute();
    }

    public static boolean isEditMode() {
        return editMode;
    }

    public static String debugEditState() {
        Minecraft mc = Minecraft.getMinecraft();
        boolean sneakVanilla = mc != null && mc.thePlayer != null && mc.thePlayer.isSneaking();
        boolean sneakHeld = GlobalFixMode.sneakActive(mc);
        boolean useHeld = GlobalFixMode.editUseDown(mc, false);
        int te = mc != null && mc.thePlayer != null ? mc.thePlayer.ticksExisted : -1;
        boolean synthRmb = te >= 0 && te <= editRmbSynthUntilTick;
        return "fixMode=" + GlobalFixMode.getMode()
                + " editMode=" + editMode + " draft=" + draft.size()
                + " sneakVanilla=" + sneakVanilla + " sneakHeld=" + sneakHeld + " useHeld=" + useHeld
                + " rmbSynth=" + synthRmb
                + " rmbDown=" + editRmbDown + " target=" + editRmbTargetIndex
                + " dragging=" + editDraggingCheckpoint;
    }

    public static void signalVanillaRightClick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return;
        }
        int t = mc.thePlayer.ticksExisted;
        // Lunar: polling often misses held RMB — keep synthetic down alive while pulses continue.
        editRmbSynthUntilTick = Math.max(editRmbSynthUntilTick, t + 20);
        editRmbLastPulseTick = t;
    }

    public static void setEditMode(boolean on) {
        if (!on) {
            resetCheckpointEditMouseState();
            editMode = false;
            draftGlowAnchorFeet = null;
            rebuildGlowFromOverlays();
            return;
        }
        editMode = true;
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP p = mc != null ? mc.thePlayer : null;
        if (p != null) {
            if (cagesSpawnFeet != null) {
                draftGlowAnchorFeet = new Vec3(cagesSpawnFeet.xCoord, cagesSpawnFeet.yCoord, cagesSpawnFeet.zCoord);
            } else {
                draftGlowAnchorFeet = new Vec3(p.posX, p.posY, p.posZ);
            }
        }
        refreshEditModeVisuals();
    }

    /**
     * While editing, the draft is the source of truth: hide overlay checkpoint numbers (they "ghost" after deletes)
     * and draw glow from the draft when it has enough points; otherwise fall back to loaded overlays.
     */
    private static void refreshEditModeVisuals() {
        if (!editMode) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        World w = mc != null ? mc.theWorld : null;
        EntityPlayerSP p = mc != null ? mc.thePlayer : null;
        if (w == null || p == null) {
            return;
        }
        if (draft.size() >= 2) {
            List<Vec3> cps = new ArrayList<>(draft.size());
            for (int i = 0; i < draft.size(); i++) {
                Vec3 v = draft.get(i);
                Vec3 dragShow = getDragPreviewForRender();
                if (editDraggingCheckpoint && editRmbTargetIndex == i && dragShow != null) {
                    cps.add(new Vec3(dragShow.xCoord, dragShow.yCoord, dragShow.zCoord));
                } else {
                    cps.add(new Vec3(v.xCoord, v.yCoord, v.zCoord));
                }
            }
            int colorRgb = 0xFFDDCC55;
            // Draft preview: trail runs checkpoint 1 → 2 → … (not from /ce on stand position).
            List<Vec3> poly = buildSmoothedPolylineCheckpointsOnly(w, cps);
            List<GlowTrailRenderer.RouteSpec> specs = new ArrayList<>(1);
            long stable = (editRmbDown && editRmbTargetIndex >= 0 && editDraftGlowStableTimeMs >= 0L)
                    ? editDraftGlowStableTimeMs
                    : -1L;
            specs.add(new GlowTrailRenderer.RouteSpec("_ce_draft", colorRgb, poly, stable));
            GlowTrailRenderer.setStaticChestRoutes(specs);
        } else {
            rebuildGlowFromOverlays();
        }
    }

    public static void clientTickEditInteraction() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) {
            resetCheckpointEditMouseState();
            return;
        }
        handleToggleRenderKey(mc);
        if (!editMode) {
            resetCheckpointEditMouseState();
            return;
        }
        if (mc.thePlayer == null) {
            return;
        }
        handleUndoKey(mc);
        handlePlaceFeetKey(mc);
        tickDraftLabelAnimations();

        EntityPlayerSP player = mc.thePlayer;
        boolean lmbNow = isLeftMousePhysicallyDown(mc);
        if (lmbNow && !ceFallbackPrevLmbTick && (mc.currentScreen == null || !mc.currentScreen.doesGuiPauseGame())) {
            try {
                tryLeftClickCheckpointInteraction(mc);
            } catch (Throwable ignored) {
            }
        }
        ceFallbackPrevLmbTick = lmbNow;
        boolean sneaking = isEditSneakActive(mc);
        int tEx = player.ticksExisted;
        boolean synth = tEx <= editRmbSynthUntilTick;
        boolean rawDown = isRightMousePhysicallyDown(mc);
        boolean keyDown = GlobalFixMode.editUseDown(mc, synth);
        boolean down = rawDown || keyDown;
        if (editInteractionFromLeft) {
            down = isLeftMousePhysicallyDown(mc);
        }
        if (editRmbDown && (mc.currentScreen == null || !mc.currentScreen.doesGuiPauseGame())) {
            down = editInteractionFromLeft ? isLeftMousePhysicallyDown(mc) : (rawDown || keyDown);
            // If raw/key are stale, use pulse continuity while held; release shortly after pulses stop.
            if (!down && !editInteractionFromLeft && tEx <= editRmbLastPulseTick + 1) {
                down = true;
            } else if (!editInteractionFromLeft && !rawDown && !keyDown && tEx > editRmbLastPulseTick + 2) {
                down = false;
            }
        }

        // RMB release finishes the gesture.
        if (!down) {
            if (editRmbDown) {
                finishEditRmbInteraction(mc, sneaking, editInteractionFromLeft);
            }
            return;
        }

        // Delete / drag checkpoints with LMB only. (Sneak+RMB used to own this flow — Shift made accidental
        // deletes far too common while sneaking for movement / edges.)
        if (!editInteractionFromLeft) {
            return;
        }

        if (!editRmbDown) {
            editRmbDown = true;
            editRmbDownTicks = 0;
            editRmbTargetIndex = pickDraftAlongLook(player, EDIT_CHECKPOINT_PICK_MAX_REACH);
            if (editRmbTargetIndex >= 0) {
                editDraftGlowStableTimeMs = System.currentTimeMillis();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                        + "[CE] Targeted checkpoint #" + (editRmbTargetIndex + 1)
                        + " — release LMB for delete, keep holding LMB for drag.");
            }
        }
        editRmbDownTicks++;
        // Preview only while dragging (see updateEditDragPreview); mutating draft every tick caused glow/label blink.
        if (!editDraggingCheckpoint && editRmbTargetIndex >= 0
                && editRmbDownTicks >= EDIT_LONG_PRESS_TICKS) {
            editDraggingCheckpoint = true;
            editDragVerticalOffset = 0.0;
            editDraftGlowStableTimeMs = System.currentTimeMillis();
            editDragPreviewLastValid = null;
            Vec3 cur = draft.get(editRmbTargetIndex);
            editDragSnapshot = new Vec3(cur.xCoord, cur.yCoord, cur.zCoord);
            if (!editDragNoticeSent) {
                editDragNoticeSent = true;
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA + "Dragging checkpoint #"
                        + (editRmbTargetIndex + 1) + EnumChatFormatting.GRAY
                        + " — release LMB to place; use mouse wheel to raise/lower.");
            }
        }
        // While holding LMB on a checkpoint, wheel nudges checkpoint height up/down.
        if (editInteractionFromLeft && editRmbTargetIndex >= 0 && editRmbTargetIndex < draft.size()) {
            int dWheel = 0;
            int wv;
            while ((wv = org.lwjgl.input.Mouse.getDWheel()) != 0) {
                dWheel += wv;
            }
            if (dWheel != 0) {
                // Wheel counts as "editing the checkpoint", so ensure drag mode is active so release commits.
                if (!editDraggingCheckpoint) {
                    editDraggingCheckpoint = true;
                    editDragVerticalOffset = 0.0;
                    editDraftGlowStableTimeMs = System.currentTimeMillis();
                    editDragPreviewLastValid = null;
                    Vec3 cur = draft.get(editRmbTargetIndex);
                    editDragSnapshot = new Vec3(cur.xCoord, cur.yCoord, cur.zCoord);
                    if (!editDragNoticeSent) {
                        editDragNoticeSent = true;
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA + "Dragging checkpoint #"
                                + (editRmbTargetIndex + 1) + EnumChatFormatting.GRAY
                                + " — release LMB to place; use mouse wheel to raise/lower.");
                    }
                }
                int notches = (int) Math.signum(dWheel) * Math.max(1, Math.abs(dWheel) / 120);
                double dy = notches * 0.20;
                editDragVerticalOffset += dy;

                // Update preview immediately so release commits the correct Y even if render didn't run yet.
                Vec3 base = getDragPreviewForRender();
                if (base == null) {
                    base = draft.get(editRmbTargetIndex);
                }
                if (base != null) {
                    Vec3 updated = new Vec3(base.xCoord, base.yCoord + dy, base.zCoord);
                    editDragPreviewPos = updated;
                    editDragPreviewLastValid = updated;
                }
                refreshEditModeVisuals();
            }
        }
    }

    private static void handlePlaceFeetKey(Minecraft mc) {
        int key = ConfigManager.getChestRoutesPlaceFeetKeyCode();
        if (key <= 0) {
            editFeetKeyWasDown = false;
            return;
        }
        if (ClientGuiHelper.shouldSuppressWorldKeybinds(mc)) {
            editFeetKeyWasDown = false;
            return;
        }
        boolean down;
        try {
            down = org.lwjgl.input.Keyboard.isKeyDown(key);
        } catch (Throwable t) {
            down = false;
        }
        if (!down) {
            editFeetKeyWasDown = false;
            return;
        }
        if (editFeetKeyWasDown) {
            return;
        }
        editFeetKeyWasDown = true;
        if (!editMode || mc.thePlayer == null) {
            return;
        }
        pushDraftUndoSnapshot();
        draft.add(new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ));
        refreshEditModeVisuals();
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Checkpoint #" + draft.size()
                + EnumChatFormatting.GRAY + " at your feet.");
    }

    private static void handleUndoKey(Minecraft mc) {
        int key = ConfigManager.getChestRoutesUndoKeyCode();
        if (key <= 0) {
            editUndoKeyWasDown = false;
            return;
        }
        if (ClientGuiHelper.shouldSuppressWorldKeybinds(mc)) {
            editUndoKeyWasDown = false;
            return;
        }
        boolean down;
        try {
            down = org.lwjgl.input.Keyboard.isKeyDown(key);
        } catch (Throwable t) {
            down = false;
        }
        if (!down) {
            editUndoKeyWasDown = false;
            return;
        }
        if (editUndoKeyWasDown) {
            return;
        }
        editUndoKeyWasDown = true;
        if (!editMode) {
            return;
        }
        String err = undoDraft();
        if (err != null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + err);
        } else {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Undid last draft edit.");
        }
    }

    private static void handleToggleRenderKey(Minecraft mc) {
        int key = ConfigManager.getChestRoutesToggleKeyCode();
        if (key <= 0) {
            editToggleRenderKeyWasDown = false;
            return;
        }
        if (ClientGuiHelper.shouldSuppressWorldKeybinds(mc)) {
            editToggleRenderKeyWasDown = false;
            return;
        }
        boolean down;
        try {
            down = org.lwjgl.input.Keyboard.isKeyDown(key);
        } catch (Throwable t) {
            down = false;
        }
        if (!down) {
            editToggleRenderKeyWasDown = false;
            return;
        }
        if (editToggleRenderKeyWasDown) {
            return;
        }
        editToggleRenderKeyWasDown = true;
        boolean next = !ConfigManager.isChestRoutesRenderEnabled();
        ConfigManager.setChestRoutesRenderEnabled(next);
        rebuildGlowFromOverlays();
        Main.sendMessage(Main.CHAT_PREFIX + "Chest routes rendering: "
                + (next ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF"));
    }

    private static boolean isRightMousePhysicallyDown(Minecraft mc) {
        if (mc == null) {
            return false;
        }
        if (mc.currentScreen != null && mc.currentScreen.doesGuiPauseGame()) {
            return false;
        }
        try {
            return org.lwjgl.input.Mouse.isButtonDown(1);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isLeftMousePhysicallyDown(Minecraft mc) {
        if (mc == null) {
            return false;
        }
        if (mc.currentScreen != null && mc.currentScreen.doesGuiPauseGame()) {
            return false;
        }
        try {
            return org.lwjgl.input.Mouse.isButtonDown(0);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isEditSneakActive(Minecraft mc) {
        if (mc == null || mc.thePlayer == null) {
            return false;
        }
        if (ClientInputHelper.isLocalSneakHeld(mc)) {
            return true;
        }
        return false;
    }

    private static int pickDraftNearPoint(Vec3 point, double maxDist) {
        if (point == null || draft.isEmpty()) {
            return -1;
        }
        double maxSq = maxDist * maxDist;
        int best = -1;
        double bestSq = Double.MAX_VALUE;
        for (int i = 0; i < draft.size(); i++) {
            Vec3 p = draft.get(i);
            double dx = p.xCoord - point.xCoord;
            double dy = p.yCoord - point.yCoord;
            double dz = p.zCoord - point.zCoord;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 <= maxSq && d2 < bestSq) {
                bestSq = d2;
                best = i;
            }
        }
        return best;
    }

    private static void tickDraftLabelAnimations() {
        if (DRAFT_LABEL_ANIMS.isEmpty()) {
            return;
        }
        DRAFT_LABEL_ANIMS.removeIf(a -> {
            a.ticksLeft--;
            return a.ticksLeft <= 0;
        });
    }

    private static void finishEditRmbInteraction(Minecraft mc, boolean sneakAtRelease, boolean fromLeft) {
        if (!editRmbDown) {
            return;
        }
        boolean commit = fromLeft || sneakAtRelease;
        if (editDraggingCheckpoint) {
            int idx = editRmbTargetIndex;
            if (commit && idx >= 0 && idx < draft.size()) {
                Vec3 place = editDragPreviewPos != null ? editDragPreviewPos : editDragPreviewLastValid;
                if (place != null) {
                    double py = place.yCoord;
                    Minecraft mc2 = Minecraft.getMinecraft();
                    WorldClient world2 = mc2 != null ? mc2.theWorld : null;
                    if (world2 != null && Math.abs(editDragVerticalOffset) < 0.0001) {
                        py = RouteGroundSnap.snapRouteTopY(world2, place.xCoord, place.zCoord, py);
                    }
                    draft.set(idx, new Vec3(place.xCoord, py, place.zCoord));
                }
                Vec3 v = draft.get(idx);
                addDraftPlaceAnim(v.xCoord, v.yCoord, v.zCoord, String.valueOf(idx + 1));
            } else if (!commit && editDragSnapshot != null && idx >= 0 && idx < draft.size()) {
                draft.set(idx, new Vec3(editDragSnapshot.xCoord, editDragSnapshot.yCoord, editDragSnapshot.zCoord));
                refreshEditModeVisuals();
                if (!fromLeft) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "[CE] Drag cancelled (not sneaking on release).");
                }
            }
            editDraggingCheckpoint = false;
            editDragSnapshot = null;
            editDragPreviewPos = null;
            editDragPreviewLastValid = null;
            editDraftGlowStableTimeMs = -1L;
        } else if (commit && editRmbTargetIndex >= 0 && editRmbDownTicks < EDIT_LONG_PRESS_TICKS) {
            int oneBased = editRmbTargetIndex + 1;
            if (oneBased >= 1 && oneBased <= draft.size()) {
                // No fading "deleted #" label — it looked like a ghost number next to renumbered draft.
                String err = removeDraftCheckpoint(oneBased);
                if (err == null) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Removed checkpoint #" + oneBased + ".");
                }
            }
        }
        editRmbDown = false;
        editRmbTargetIndex = -1;
        editRmbDownTicks = 0;
        editDragNoticeSent = false;
        editDragPreviewPos = null;
        editDragPreviewLastValid = null;
        editDraftGlowStableTimeMs = -1L;
        editInteractionFromLeft = false;
    }

    private static void addDraftPlaceAnim(double x, double y, double z, String text) {
        DRAFT_LABEL_ANIMS.add(new DraftLabelAnim(x, y, z, text, 1, 45));
    }

    private static int argbForDraftAnim(DraftLabelAnim a) {
        float t = a.ticksTotal <= 0 ? 0f : (a.ticksLeft / (float) a.ticksTotal);
        t = Math.max(0f, Math.min(1f, t));
        int alpha = (int) (255 * t);
        if (a.mode == 0) {
            int r = 255;
            int g = (int) (40 + 80 * (1f - t));
            int b = (int) (40 + 80 * (1f - t));
            return (alpha << 24) | (r << 16) | (g << 8) | b;
        }
        int r = (int) (50 + 60 * (1f - t));
        int g = (int) (200 + 55 * t);
        int b = (int) (90 + 80 * (1f - t));
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }

    private static void resetCheckpointEditMouseState() {
        editRmbDown = false;
        editRmbDownTicks = 0;
        editRmbTargetIndex = -1;
        editDraggingCheckpoint = false;
        editDragNoticeSent = false;
        editDragSnapshot = null;
        editRmbSynthUntilTick = Integer.MIN_VALUE;
        editRmbLastPulseTick = Integer.MIN_VALUE;
        editDragPreviewPos = null;
        editDragPreviewLastValid = null;
        editDragVerticalOffset = 0.0;
        editDraftGlowStableTimeMs = -1L;
        editInteractionFromLeft = false;
        editUndoKeyWasDown = false;
        ceFallbackPrevLmbTick = false;
    }

    /**
     * /ce ON: LMB a checkpoint to delete, hold LMB to drag.
     * Returns true when the click should be consumed (prevent vanilla attack/break).
     */
    public static boolean tryLeftClickCheckpointInteraction(Minecraft mc) {
        if (!editMode || mc == null || mc.theWorld == null || mc.thePlayer == null) {
            return false;
        }
        if (mc.currentScreen != null && mc.currentScreen.doesGuiPauseGame()) {
            return false;
        }
        int idx = pickDraftAlongLook(mc.thePlayer, EDIT_CHECKPOINT_PICK_MAX_REACH);
        if (idx < 0) {
            return false;
        }
        if (!editRmbDown) {
            editRmbDown = true;
            editRmbDownTicks = 0;
            editRmbTargetIndex = idx;
            editDraggingCheckpoint = false;
            editDragNoticeSent = false;
            editInteractionFromLeft = true;
            editDraftGlowStableTimeMs = System.currentTimeMillis();
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                    + "[CE] Targeted checkpoint #" + (editRmbTargetIndex + 1)
                    + " — release LMB to delete, hold LMB to drag.");
        }
        return true;
    }

    /** True while CE drag consumes mouse wheel for vertical checkpoint nudge. */
    public static boolean isDraggingCheckpointNow() {
        return editMode && editDraggingCheckpoint && editRmbTargetIndex >= 0;
    }

    /**
     * True while player is holding LMB on a checkpoint in CE mode.
     * Used to suppress Perspective distance mouse-wheel behavior so it doesn't fight checkpoint elevation.
     */
    public static boolean isCeCheckpointEditGestureActive() {
        return editMode && editInteractionFromLeft && editRmbDown && editRmbTargetIndex >= 0;
    }

    private static Vec3 getDragPreviewForRender() {
        return editDragPreviewPos != null ? editDragPreviewPos : editDragPreviewLastValid;
    }

    /**
     * Called from {@link me.napim.gomod.features.ChestCheckpointLabels#render} so preview follows look every frame.
     */
    public static void updateEditDragPreview(float partialTicks) {
        if (!editMode || !editDraggingCheckpoint || editRmbTargetIndex < 0) {
            editDragPreviewPos = null;
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc != null ? mc.thePlayer : null;
        WorldClient w = mc != null ? mc.theWorld : null;
        if (player == null || w == null) {
            editDragPreviewPos = null;
            return;
        }
        MovingObjectPosition mop = rayTraceEditAlongLook(player, w, EDIT_LONG_RAY_REACH, partialTicks);
        if (mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK && mop.hitVec != null) {
            BlockPos bp = mop.getBlockPos();
            if (!w.isAirBlock(bp)) {
                // Apply vertical nudge while user scrolls during a drag gesture.
                Vec3 next = new Vec3(
                        mop.hitVec.xCoord,
                        mop.hitVec.yCoord + editDragVerticalOffset,
                        mop.hitVec.zCoord);
                editDragPreviewPos = next;
                editDragPreviewLastValid = next;
                // Avoid full trail rebuild every frame unless the hit moves (stops green pulse flicker).
                refreshEditModeVisuals();
                return;
            }
        }
        editDragPreviewPos = null;
        refreshEditModeVisuals();
    }

    private static MovingObjectPosition rayTraceEditAlongLook(EntityPlayerSP player, World world, double reach, float partialTicks) {
        Vec3 eye = player.getPositionEyes(partialTicks);
        Vec3 look = player.getLook(partialTicks);
        Vec3 end = eye.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach);
        // Don't return "last uncollidable" (often air/liquid), otherwise right-click/long-ray can cancel without placing.
        return world.rayTraceBlocks(eye, end, false, false, false);
    }

    private static long nextLongRayCheckpointAllowedMs;

    private static double distSqEyeToHit(Vec3 eye, Vec3 hit) {
        double dx = hit.xCoord - eye.xCoord;
        double dy = hit.yCoord - eye.yCoord;
        double dz = hit.zCoord - eye.zCoord;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Client-only: place a checkpoint on a distant block without changing {@link net.minecraft.client.multiplayer.PlayerControllerMP#getBlockReachDistance()}
     * (avoids "infinite reach" style behavior). Called from {@code Minecraft.rightClickMouse} HEAD; returns true if this click was consumed.
     */
    public static boolean tryLongRayCheckpointOnRightClick(Minecraft mc) {
        if (!editMode || mc == null || mc.theWorld == null || mc.thePlayer == null) {
            return false;
        }
        if (mc.currentScreen != null && mc.currentScreen.doesGuiPauseGame()) {
            return false;
        }
        // Mouse is processed before the client tick in some builds. Finish LMB CE gestures as soon as the
        // button is released so RMB placement is not blocked by stale edit state.
        if (editInteractionFromLeft && editRmbDown && !isLeftMousePhysicallyDown(mc)) {
            finishEditRmbInteraction(mc, isEditSneakActive(mc), true);
        }
        if (editDraggingCheckpoint) {
            EntityPlayerSP player = mc.thePlayer;
            WorldClient world = mc.theWorld;
            net.minecraft.util.Timer timer = MinecraftPrivateAccess.getTimer(mc);
            float pt = timer != null ? timer.renderPartialTicks : 1.0f;
            MovingObjectPosition far = rayTraceEditAlongLook(player, world, EDIT_LONG_RAY_REACH, pt);
            if (far == null || far.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK || far.hitVec == null) {
                return true;
            }
            BlockPos bp = far.getBlockPos();
            if (bp == null || world.isAirBlock(bp)) {
                return true;
            }
            finishDragAtHit(world, bp, far.hitVec);
            return true;
        }
        // Legacy RMB-started edit gestures are disabled (LMB-only). Do not finish LMB drags here.
        // While RMB is down for an existing checkpoint (delete/drag), block — but allow long-ray placement
        // when RMB is down with no checkpoint targeted (pick index -1).
        if (editDraggingCheckpoint || (editRmbDown && editRmbTargetIndex >= 0)) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now < nextLongRayCheckpointAllowedMs) {
            return false;
        }
        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;
        net.minecraft.util.Timer timer = MinecraftPrivateAccess.getTimer(mc);
        float pt = timer != null ? timer.renderPartialTicks : 1.0f;

        MovingObjectPosition far = rayTraceEditAlongLook(player, world, EDIT_LONG_RAY_REACH, pt);
        if (far == null || far.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK || far.hitVec == null) {
            return false;
        }
        BlockPos bp = far.getBlockPos();
        if (world.isAirBlock(bp)) {
            return false;
        }

        // Sneak + RMB no longer starts delete/drag; left click handles both.

        nextLongRayCheckpointAllowedMs = now + 280L;
        onRightClickBlock(world, bp, far.hitVec, player);
        return true;
    }

    private static int pickDraftAlongLook(EntityPlayerSP player, double maxReach) {
        if (draft.isEmpty()) {
            return -1;
        }
        Vec3 eye = player.getPositionEyes(1.0F);
        Vec3 look = player.getLook(1.0F);
        double maxPickSq = EDIT_CHECKPOINT_PICK_RADIUS * EDIT_CHECKPOINT_PICK_RADIUS;
        double bestD2 = Double.MAX_VALUE;
        int best = -1;
        for (int i = 0; i < draft.size(); i++) {
            Vec3 p = draft.get(i);
            double d2 = distPointToRaySegmentSq(eye, look, maxReach, p);
            if (d2 <= maxPickSq && d2 < bestD2) {
                bestD2 = d2;
                best = i;
            }
        }
        return best;
    }

    private static double distPointToRaySegmentSq(Vec3 origin, Vec3 dirUnit, double maxT, Vec3 point) {
        double ox = origin.xCoord;
        double oy = origin.yCoord;
        double oz = origin.zCoord;
        double dx = dirUnit.xCoord;
        double dy = dirUnit.yCoord;
        double dz = dirUnit.zCoord;
        double px = point.xCoord - ox;
        double py = point.yCoord - oy;
        double pz = point.zCoord - oz;
        double t = px * dx + py * dy + pz * dz;
        if (t < 0.0) {
            t = 0.0;
        } else if (t > maxT) {
            t = maxT;
        }
        double qx = ox + dx * t - point.xCoord;
        double qy = oy + dy * t - point.yCoord;
        double qz = oz + dz * t - point.zCoord;
        return qx * qx + qy * qy + qz * qz;
    }

    private static void applyDraftDragFromLook(EntityPlayerSP player, World world) {
        if (editRmbTargetIndex < 0 || editRmbTargetIndex >= draft.size()) {
            return;
        }
        MovingObjectPosition mop = player.rayTrace(6.0D, 1.0F);
        if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK || mop.hitVec == null) {
            return;
        }
        BlockPos bp = mop.getBlockPos();
        if (world.isAirBlock(bp)) {
            return;
        }
        Vec3 v = new Vec3(mop.hitVec.xCoord, mop.hitVec.yCoord, mop.hitVec.zCoord);
        draft.set(editRmbTargetIndex, v);
        refreshEditModeVisuals();
    }

    public static int getDraftSize() {
        return draft.size();
    }

    public static void clearDraft() {
        pushDraftUndoSnapshot();
        draft.clear();
        DRAFT_LABEL_ANIMS.clear();
        resetCheckpointEditMouseState();
        if (editMode) {
            refreshEditModeVisuals();
        }
    }

    public static String removeDraftCheckpoint(int oneBased) {
        if (oneBased < 1 || oneBased > draft.size()) {
            return "No checkpoint #" + oneBased + " (draft has " + draft.size() + ").";
        }
        pushDraftUndoSnapshot();
        draft.remove(oneBased - 1);
        if (editMode) {
            refreshEditModeVisuals();
        }
        return null;
    }

    public static String swapDraftCheckpoints(int i, int j) {
        if (i < 1 || j < 1 || i > draft.size() || j > draft.size()) {
            return "Invalid index (draft has " + draft.size() + " checkpoints).";
        }
        pushDraftUndoSnapshot();
        Collections.swap(draft, i - 1, j - 1);
        if (editMode) {
            refreshEditModeVisuals();
        }
        return null;
    }

    public static void printDraftCheckpoints() {
        if (draft.isEmpty()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Draft is empty.");
            return;
        }
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA + "Draft checkpoints:");
        for (int k = 0; k < draft.size(); k++) {
            Vec3 v = draft.get(k);
            Main.sendMessage(EnumChatFormatting.YELLOW + "  #" + (k + 1) + EnumChatFormatting.WHITE + "  "
                    + String.format("%.2f %.2f %.2f", v.xCoord, v.yCoord, v.zCoord));
        }
    }

    public static String commandDragCheckpoint(int oneBased) {
        if (!editMode) {
            return "Edit mode is OFF. Use /ce on first.";
        }
        if (oneBased < 1 || oneBased > draft.size()) {
            return "No checkpoint #" + oneBased + " (draft has " + draft.size() + ").";
        }
        int idx = oneBased - 1;
        editRmbDown = true;
        editRmbDownTicks = EDIT_LONG_PRESS_TICKS;
        editRmbTargetIndex = idx;
        editDraggingCheckpoint = true;
        editDragNoticeSent = true;
        editDragPreviewPos = null;
        editDragPreviewLastValid = null;
        Vec3 cur = draft.get(idx);
        editDragSnapshot = new Vec3(cur.xCoord, cur.yCoord, cur.zCoord);
        editDraftGlowStableTimeMs = System.currentTimeMillis();
        refreshEditModeVisuals();
        return null;
    }

    public static String commandDropDrag() {
        if (!editMode) {
            return "Edit mode is OFF. Use /ce on first.";
        }
        if (!editDraggingCheckpoint || editRmbTargetIndex < 0 || editRmbTargetIndex >= draft.size()) {
            return "No checkpoint is currently being dragged.";
        }
        Vec3 place = editDragPreviewPos != null ? editDragPreviewPos : editDragPreviewLastValid;
        if (place == null) {
            return "No valid block in sight to drop the checkpoint.";
        }
        pushDraftUndoSnapshot();
        draft.set(editRmbTargetIndex, new Vec3(place.xCoord, place.yCoord, place.zCoord));
        addDraftPlaceAnim(place.xCoord, place.yCoord, place.zCoord, String.valueOf(editRmbTargetIndex + 1));
        editRmbDown = false;
        editRmbTargetIndex = -1;
        editRmbDownTicks = 0;
        editDraggingCheckpoint = false;
        editDragSnapshot = null;
        editDragPreviewPos = null;
        editDragPreviewLastValid = null;
        editDragNoticeSent = false;
        editDraftGlowStableTimeMs = -1L;
        refreshEditModeVisuals();
        return null;
    }

    public static String commandAddCheckpointFromLook() {
        if (!editMode) {
            return "Edit mode is OFF. Use /ce on first.";
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            return "World not ready.";
        }
        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;
        net.minecraft.util.Timer timer = MinecraftPrivateAccess.getTimer(mc);
        float pt = timer != null ? timer.renderPartialTicks : 1.0f;
        MovingObjectPosition mop = rayTraceEditAlongLook(player, world, EDIT_LONG_RAY_REACH, pt);
        if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK || mop.hitVec == null) {
            return "No block in sight.";
        }
        BlockPos bp = mop.getBlockPos();
        if (bp == null || world.isAirBlock(bp)) {
            return "No solid block in sight.";
        }
        return addCheckpointAtHit(world, bp, mop.hitVec);
    }

    private static String addCheckpointAtHit(WorldClient world, BlockPos usePos, Vec3 useHit) {
        if (world == null || usePos == null || world.isAirBlock(usePos)) {
            return "No valid block in sight.";
        }
        Block b = world.getBlockState(usePos).getBlock();
        Material mat = b.getMaterial();
        if (mat == Material.air || world.isAirBlock(usePos)) {
            return "No valid block in sight.";
        }
        double px;
        double py;
        double pz;
        if (useHit != null) {
            px = useHit.xCoord;
            py = useHit.yCoord;
            pz = useHit.zCoord;
        } else {
            px = usePos.getX() + 0.5;
            py = usePos.getY() + 1.0 + 0.0625;
            pz = usePos.getZ() + 0.5;
        }
        // Normal checkpoint placement should stick to walkable top.
        py = RouteGroundSnap.snapRouteTopY(world, px, pz, py);
        Vec3 cp = new Vec3(px, py, pz);
        if (!draft.isEmpty()) {
            Vec3 prev = draft.get(draft.size() - 1);
            double dx = cp.xCoord - prev.xCoord;
            double dy = cp.yCoord - prev.yCoord;
            double dz = cp.zCoord - prev.zCoord;
            if (dx * dx + dy * dy + dz * dz < 0.0004) {
                return "Checkpoint too close to the previous one.";
            }
        }
        pushDraftUndoSnapshot();
        draft.add(cp);
        refreshEditModeVisuals();
        return "Checkpoint #" + draft.size() + " at "
                + String.format(Locale.ROOT, "%.3f %.3f %.3f", px, py, pz);
    }

    public static List<CheckpointLabel> collectCheckpointLabels() {
        List<CheckpointLabel> out = new ArrayList<>();
        boolean multi = activeOverlays.size() > 1;
        if (ConfigManager.isChestRoutesRenderEnabled() && !activeOverlays.isEmpty()) {
            Vec3 first = activeOverlays.get(0).start;
            boolean sameStart = true;
            for (ActiveOverlay o : activeOverlays) {
                if (o.start.squareDistanceTo(first) > 0.15) {
                    sameStart = false;
                    break;
                }
            }
            if (sameStart) {
                out.add(new CheckpointLabel(first.xCoord, first.yCoord, first.zCoord, "S", 0xFF55FF55));
            } else {
                for (ActiveOverlay o : activeOverlays) {
                    int tint = 0xFF000000 | (o.colorRgb & 0xFFFFFF);
                    out.add(new CheckpointLabel(o.start.xCoord, o.start.yCoord, o.start.zCoord, "S:" + o.pathName, tint));
                }
            }
            // In edit mode, only draft numbers are shown — overlay indices would stay after draft deletes ("ghost").
            if (!editMode && !suppressNumberedCheckpointLabels) {
                for (ActiveOverlay o : activeOverlays) {
                    int argb = 0xFF000000 | (o.colorRgb & 0xFFFFFF);
                    for (int i = 0; i < o.checkpoints.size(); i++) {
                        Vec3 v = o.checkpoints.get(i);
                        String label = multi ? (o.pathName + ":" + (i + 1)) : String.valueOf(i + 1);
                        out.add(new CheckpointLabel(v.xCoord, v.yCoord, v.zCoord, label, argb));
                    }
                }
            }
        }
        if (editMode && !draft.isEmpty()) {
            for (int i = 0; i < draft.size(); i++) {
                Vec3 v = draft.get(i);
                if (editDraggingCheckpoint && editRmbTargetIndex == i) {
                    Vec3 show = getDragPreviewForRender();
                    if (show != null) {
                        out.add(new CheckpointLabel(show.xCoord, show.yCoord, show.zCoord,
                                String.valueOf(i + 1), 0xFF22FF66));
                    } else {
                        out.add(new CheckpointLabel(v.xCoord, v.yCoord, v.zCoord, String.valueOf(i + 1), 0xFFFFFF55));
                    }
                } else {
                    out.add(new CheckpointLabel(v.xCoord, v.yCoord, v.zCoord, String.valueOf(i + 1), 0xFFFFFF55));
                }
            }
        }
        for (DraftLabelAnim a : DRAFT_LABEL_ANIMS) {
            out.add(new CheckpointLabel(a.x, a.y, a.z, a.text, argbForDraftAnim(a)));
        }
        return out;
    }

    /**
     * @param hitVec world-space ray hit from {@code PlayerControllerMP.onPlayerRightClick} (exact click point); may be null on some paths
     */
    public static void onRightClickBlock(WorldClient world, BlockPos pos, Vec3 hitVec, EntityPlayerSP player) {
        if (!editMode || world == null || player == null) {
            return;
        }
        if (editDraggingCheckpoint) {
            if (pos == null && hitVec == null) {
                return;
            }
            Vec3 pick = hitVec;
            if (pick == null && pos != null) {
                pick = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0 + 0.0625, pos.getZ() + 0.5);
            }
            if (pos != null && !world.isAirBlock(pos)) {
                finishDragAtHit(world, pos, pick);
            }
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        net.minecraft.util.Timer timer = MinecraftPrivateAccess.getTimer(mc);
        float pt = timer != null ? timer.renderPartialTicks : 1.0f;

        // RMB no longer targets checkpoint delete/drag (LMB only).

        BlockPos usePos = pos;
        Vec3 useHit = hitVec;
        MovingObjectPosition aim = rayTraceEditAlongLook(player, world, EDIT_LONG_RAY_REACH, pt);
        if (aim != null && aim.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK && aim.hitVec != null) {
            BlockPos ap = aim.getBlockPos();
            if (!world.isAirBlock(ap)) {
                usePos = ap;
                useHit = aim.hitVec;
            }
        }
        if (usePos == null || world.isAirBlock(usePos)) {
            return;
        }

        Block b = world.getBlockState(usePos).getBlock();
        Material mat = b.getMaterial();
        if (mat == Material.air || world.isAirBlock(usePos)) {
            return;
        }
        double px;
        double py;
        double pz;
        if (useHit != null) {
            px = useHit.xCoord;
            py = useHit.yCoord;
            pz = useHit.zCoord;
        } else {
            px = usePos.getX() + 0.5;
            py = usePos.getY() + 1.0 + 0.0625;
            pz = usePos.getZ() + 0.5;
        }
        // Default right-click checkpoints should be grounded; "air path" comes from feet-in-air or wheel elevation.
        py = RouteGroundSnap.snapRouteTopY(world, px, pz, py);
        Vec3 cp = new Vec3(px, py, pz);
        if (!draft.isEmpty()) {
            Vec3 prev = draft.get(draft.size() - 1);
            double dx = cp.xCoord - prev.xCoord;
            double dy = cp.yCoord - prev.yCoord;
            double dz = cp.zCoord - prev.zCoord;
            if (dx * dx + dy * dy + dz * dz < 0.0004) {
                return;
            }
        }
        draft.add(cp);
        refreshEditModeVisuals();
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Checkpoint #" + draft.size()
                + EnumChatFormatting.GRAY + " at " + EnumChatFormatting.WHITE
                + String.format("%.3f %.3f %.3f", px, py, pz));
    }

    private static void finishDragAtHit(WorldClient world, BlockPos usePos, Vec3 useHit) {
        if (!editDraggingCheckpoint || editRmbTargetIndex < 0 || editRmbTargetIndex >= draft.size()) {
            return;
        }
        if (world == null || usePos == null || world.isAirBlock(usePos)) {
            return;
        }
        Block b = world.getBlockState(usePos).getBlock();
        Material mat = b.getMaterial();
        if (mat == Material.air || world.isAirBlock(usePos)) {
            return;
        }
        double px;
        double py;
        double pz;
        if (useHit != null) {
            px = useHit.xCoord;
            py = useHit.yCoord;
            pz = useHit.zCoord;
        } else {
            px = usePos.getX() + 0.5;
            py = usePos.getY() + 1.0 + 0.0625;
            pz = usePos.getZ() + 0.5;
        }
        // If player didn't wheel-elevate, keep dragged checkpoint on walkable top.
        if (Math.abs(editDragVerticalOffset) < 0.0001) {
            py = RouteGroundSnap.snapRouteTopY(world, px, pz, py);
        }
        pushDraftUndoSnapshot();
        draft.set(editRmbTargetIndex, new Vec3(px, py, pz));
        addDraftPlaceAnim(px, py, pz, String.valueOf(editRmbTargetIndex + 1));
        editRmbDown = false;
        editRmbTargetIndex = -1;
        editRmbDownTicks = 0;
        editDraggingCheckpoint = false;
        editDragSnapshot = null;
        editDragPreviewPos = null;
        editDragPreviewLastValid = null;
        editDragNoticeSent = false;
        editDraftGlowStableTimeMs = -1L;
        refreshEditModeVisuals();
    }

    /**
     * @param explicitMapKeyOrNull map override from scoreboard if null
     * @param pathName             required route name (slugified for storage)
     */
    public static String saveDraft(String explicitMapKeyOrNull, String pathName) {
        if (draft.size() < 2) {
            return "Need at least 2 checkpoints (you have " + draft.size() + ").";
        }
        if (pathName == null || pathName.trim().isEmpty()) {
            return "Give your route a name: /ce save <name> [map_key]";
        }
        String p = HypixelScoreboardMapResolver.slugify(pathName);
        if (p.isEmpty() || "unknown".equals(p)) {
            return "Invalid route name — use letters/numbers (e.g. speed1, north_route).";
        }
        String key = explicitMapKeyOrNull;
        if (key == null || key.isEmpty()) {
            Minecraft mc = Minecraft.getMinecraft();
            key = HypixelScoreboardMapResolver.tryResolveCurrentMapKey(mc);
        } else {
            key = HypixelScoreboardMapResolver.slugify(explicitMapKeyOrNull);
        }
        if (key == null || key.isEmpty() || "unknown".equals(key)) {
            return "Could not read map from scoreboard. Use: /ce save <name> <map_key>";
        }
        List<Vec3> copy = new ArrayList<>(draft.size());
        for (Vec3 v : draft) {
            copy.add(new Vec3(v.xCoord, v.yCoord, v.zCoord));
        }
        ChestRouteStorage.putPath(key, p, copy);
        draft.clear();
        DRAFT_LABEL_ANIMS.clear();
        setEditMode(false);
        return null;
    }

    /** @deprecated use {@link #saveDraft(String, String)} with path */
    @Deprecated
    public static String saveDraft(String explicitMapKeyOrNull) {
        return saveDraft(explicitMapKeyOrNull, "main");
    }

    private static int colorIndexForPath(String mapKey, String pathName) {
        List<String> names = new ArrayList<>(ChestRouteStorage.listPathNames(mapKey));
        int idx = names.indexOf(pathName);
        if (idx < 0) {
            idx = 0;
        }
        return idx;
    }

    /**
     * Inserts corners so each leg goes fully horizontal (constant Y) then vertical at the target column,
     * instead of diagonally ramping Y across the whole span (fixes “floating up slowly” between checkpoints).
     */
    private static List<Vec3> expandHorizontalThenVertical(List<Vec3> keyframes) {
        if (keyframes == null || keyframes.size() < 2) {
            return keyframes;
        }
        final double epsXZ = 0.04;
        final double epsY = 0.06;
        List<Vec3> out = new ArrayList<>(keyframes.size() * 2);
        out.add(new Vec3(
                keyframes.get(0).xCoord, keyframes.get(0).yCoord, keyframes.get(0).zCoord));
        for (int i = 0; i < keyframes.size() - 1; i++) {
            Vec3 a = keyframes.get(i);
            Vec3 b = keyframes.get(i + 1);
            double dx = b.xCoord - a.xCoord;
            double dz = b.zCoord - a.zCoord;
            double horiz = Math.sqrt(dx * dx + dz * dz);
            double dy = b.yCoord - a.yCoord;
            Vec3 corner = new Vec3(b.xCoord, a.yCoord, b.zCoord);
            if (horiz > epsXZ && Math.abs(dy) > epsY) {
                if (corner.squareDistanceTo(a) > epsXZ * epsXZ) {
                    out.add(corner);
                }
                Vec3 last = out.get(out.size() - 1);
                if (b.squareDistanceTo(last) > epsXZ * epsXZ) {
                    out.add(new Vec3(b.xCoord, b.yCoord, b.zCoord));
                }
            } else {
                if (b.squareDistanceTo(a) > epsXZ * epsXZ) {
                    out.add(new Vec3(b.xCoord, b.yCoord, b.zCoord));
                }
            }
        }
        return out;
    }

    private static List<Vec3> buildSmoothedPolyline(World world, Vec3 startFeet, List<Vec3> storedCheckpoints) {
        List<Vec3> keyframes = new ArrayList<>(storedCheckpoints.size() + 1);
        keyframes.add(new Vec3(startFeet.xCoord, startFeet.yCoord, startFeet.zCoord));
        for (Vec3 v : storedCheckpoints) {
            keyframes.add(new Vec3(v.xCoord, v.yCoord, v.zCoord));
        }
        keyframes = expandHorizontalThenVertical(keyframes);
        List<Vec3> smoothed = ChestRoutePathSmoother.smooth(world, keyframes);
        if (smoothed.size() < 2) {
            smoothed = keyframes;
        }
        List<Vec3> onGround = densifyAlongWalkableSurface(world, smoothed, 0.18);
        onGround = enforceHorizontalVerticalCorners(world, onGround);
        onGround = smoothRouteHeights(world, onGround);
        return onGround.size() >= 2 ? onGround : smoothed;
    }

    /** Same as {@link #buildSmoothedPolyline} but without a spawn/start feet prefix — for /ce draft preview only. */
    private static List<Vec3> buildSmoothedPolylineCheckpointsOnly(World world, List<Vec3> storedCheckpoints) {
        if (world == null || storedCheckpoints == null || storedCheckpoints.size() < 2) {
            return storedCheckpoints == null ? new ArrayList<Vec3>() : new ArrayList<Vec3>(storedCheckpoints);
        }
        List<Vec3> keyframes = new ArrayList<Vec3>(storedCheckpoints.size());
        for (Vec3 v : storedCheckpoints) {
            keyframes.add(new Vec3(v.xCoord, v.yCoord, v.zCoord));
        }
        keyframes = expandHorizontalThenVertical(keyframes);
        List<Vec3> smoothed = ChestRoutePathSmoother.smooth(world, keyframes);
        if (smoothed.size() < 2) {
            smoothed = keyframes;
        }
        List<Vec3> onGround = densifyAlongWalkableSurface(world, smoothed, 0.18);
        onGround = enforceHorizontalVerticalCorners(world, onGround);
        onGround = smoothRouteHeights(world, onGround);
        return onGround.size() >= 2 ? onGround : smoothed;
    }

    private static boolean isCheckpointAirborne(World world, Vec3 p) {
        if (world == null || p == null) return false;
        // Treat as "airborne" only when the checkpoint is *above* the walkable top by a meaningful margin.
        // (Using signed difference avoids misclassifying normal grounded points when snapRouteTopY adds epsilons.)
        double snapped = RouteGroundSnap.snapRouteTopY(world, p.xCoord, p.zCoord, p.yCoord);
        return p.yCoord - snapped > 0.40;
    }

    /**
     * Samples every {@code step} blocks along the polyline and snaps each sample to the walkable
     * block top at that (x,z), so the tracer follows terrain between checkpoints instead of a straight
     * line through the air.
     */
    private static List<Vec3> densifyAlongWalkableSurface(World world, List<Vec3> path, double step) {
        if (world == null || path == null || path.size() < 2) {
            return path;
        }
        List<Vec3> out = new ArrayList<>();
        for (int i = 0; i < path.size() - 1; i++) {
            Vec3 a = path.get(i);
            Vec3 b = path.get(i + 1);
            boolean ignoreSnapForSegment = isCheckpointAirborne(world, a) || isCheckpointAirborne(world, b);
            double dx = b.xCoord - a.xCoord;
            double dy = b.yCoord - a.yCoord;
            double dz = b.zCoord - a.zCoord;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            int n = Math.max(1, (int) Math.ceil(len / step));
            for (int s = 0; s <= n; s++) {
                if (i > 0 && s == 0) {
                    continue;
                }
                double t = s / (double) n;
                double x = a.xCoord + dx * t;
                double y = a.yCoord + dy * t;
                double z = a.zCoord + dz * t;
                if (ignoreSnapForSegment) {
                    out.add(new Vec3(x, y, z));
                } else {
                    double ny = RouteGroundSnap.snapRouteTopY(world, x, z, y);
                    out.add(new Vec3(x, ny, z));
                }
            }
        }
        return out;
    }

    /**
     * After ground snap, diagonal legs can cut through block corners. Insert horizontal-then-vertical
     * corners at (target XZ, previous Y) so the line runs along tops before stepping.
     */
    private static List<Vec3> enforceHorizontalVerticalCorners(World world, List<Vec3> path) {
        if (world == null || path == null || path.size() < 2) {
            return path;
        }
        final double epsH = 0.1;
        final double epsV = 0.22;
        List<Vec3> out = new ArrayList<>();
        out.add(path.get(0));
        for (int i = 1; i < path.size(); i++) {
            Vec3 prev = out.get(out.size() - 1);
            Vec3 cur = path.get(i);
            boolean ignoreSnap = isCheckpointAirborne(world, prev) || isCheckpointAirborne(world, cur);
            double dx = cur.xCoord - prev.xCoord;
            double dz = cur.zCoord - prev.zCoord;
            double dh = Math.sqrt(dx * dx + dz * dz);
            double dv = Math.abs(cur.yCoord - prev.yCoord);
            if (dh > epsH && dv > epsV) {
                Vec3 corner = new Vec3(cur.xCoord, prev.yCoord, cur.zCoord);
                if (!ignoreSnap) {
                    double cy = RouteGroundSnap.snapRouteTopY(world, corner.xCoord, corner.zCoord, prev.yCoord);
                    corner = new Vec3(corner.xCoord, cy, corner.zCoord);
                }
                if (corner.squareDistanceTo(prev) > 1e-5) {
                    out.add(corner);
                }
            }
            if (cur.squareDistanceTo(out.get(out.size() - 1)) > 1e-5) {
                out.add(cur);
            }
        }
        return out;
    }

    /** Light vertical smoothing + resnap so the line is less jagged without leaving the floor. */
    private static List<Vec3> smoothRouteHeights(World world, List<Vec3> path) {
        if (world == null || path == null || path.size() < 3) {
            return path;
        }
        final double sharpY = 0.38;
        List<Vec3> pass = new ArrayList<>(path.size());
        pass.add(path.get(0));
        for (int i = 1; i < path.size() - 1; i++) {
            Vec3 a = path.get(i - 1);
            Vec3 b = path.get(i);
            Vec3 c = path.get(i + 1);
            // Avoid averaging across significant steps — especially stair-step descents — or the path
            // collapses into one diagonal/vertical drop at the last block instead of following tops.
            double dIn = Math.abs(b.yCoord - a.yCoord);
            double dOut = Math.abs(c.yCoord - b.yCoord);
            if (dIn > sharpY || dOut > sharpY) {
                pass.add(new Vec3(b.xCoord, b.yCoord, b.zCoord));
                continue;
            }
            double ny = (a.yCoord + b.yCoord + c.yCoord) / 3.0;
            boolean ignoreSnap = isCheckpointAirborne(world, a) || isCheckpointAirborne(world, b) || isCheckpointAirborne(world, c);
            if (ignoreSnap) {
                pass.add(new Vec3(b.xCoord, ny, b.zCoord));
            } else {
                double sy = RouteGroundSnap.snapRouteTopY(world, b.xCoord, b.zCoord, ny);
                pass.add(new Vec3(b.xCoord, sy, b.zCoord));
            }
        }
        pass.add(path.get(path.size() - 1));
        return pass;
    }

    /** Chat listing of all saved maps/paths (for /cro list). */
    public static void printSavedRoutesChat() {
        List<String> keys = ChestRouteStorage.listMapKeys();
        if (keys.isEmpty()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "No saved routes.");
            return;
        }
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA + "Saved maps & paths:");
        for (String k : keys) {
            List<String> paths = ChestRouteStorage.listPathNames(k);
            Main.sendMessage(EnumChatFormatting.YELLOW + "  " + k
                    + EnumChatFormatting.GRAY + " → "
                    + EnumChatFormatting.WHITE + String.join(", ", paths));
        }
    }

    private static void pushOverlay(String pathName, int colorRgb, Vec3 start, List<Vec3> checkpoints) {
        activeOverlays.removeIf(o -> o.pathName.equals(pathName));
        activeOverlays.add(new ActiveOverlay(pathName, colorRgb, start, checkpoints));
    }

    private static void removeOverlay(String pathName) {
        activeOverlays.removeIf(o -> o.pathName.equals(pathName));
    }

    private static void rebuildGlowFromOverlays() {
        if (!ConfigManager.isChestRoutesRenderEnabled()) {
            GlowTrailRenderer.clearStaticChestRoute();
            return;
        }
        List<GlowTrailRenderer.RouteSpec> specs = new ArrayList<>();
        for (ActiveOverlay o : activeOverlays) {
            World w = Minecraft.getMinecraft() != null ? Minecraft.getMinecraft().theWorld : null;
            List<Vec3> poly = buildSmoothedPolyline(w, o.start, o.checkpoints);
            specs.add(new GlowTrailRenderer.RouteSpec(o.pathName, o.colorRgb, poly));
        }
        GlowTrailRenderer.setStaticChestRoutes(specs);
    }

    /**
     * Load one path. Start from cages capture, or explicit XYZ.
     */
    public static String loadRouteWithStart(Vec3 startOrNull, double startX, double startY, double startZ,
                                            boolean useExplicitStart, String explicitMapKeyOrNull, String pathName) {
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient world = mc != null ? mc.theWorld : null;
        String key = explicitMapKeyOrNull;
        if (key == null || key.isEmpty()) {
            key = HypixelScoreboardMapResolver.tryResolveCurrentMapKey(mc);
        } else {
            key = HypixelScoreboardMapResolver.slugify(explicitMapKeyOrNull);
        }
        if (key == null || "unknown".equals(key)) {
            return "Could not detect map. Use /cro map or pass map_key.";
        }
        String p;
        if (pathName != null && !pathName.isEmpty()) {
            p = HypixelScoreboardMapResolver.slugify(pathName);
        } else {
            p = ChestRouteStorage.getEffectiveMainPath(key);
        }
        if (p == null || p.isEmpty()) {
            return "No saved paths for \"" + key + "\". /ce save <name>";
        }
        List<Vec3> stored = ChestRouteStorage.getPath(key, p);
        if (stored == null || stored.size() < 2) {
            return "No saved path \"" + p + "\" for \"" + key + "\". /cro list";
        }

        Vec3 start;
        if (useExplicitStart) {
            start = new Vec3(startX, startY, startZ);
        } else if (startOrNull != null) {
            start = new Vec3(startOrNull.xCoord, startOrNull.yCoord, startOrNull.zCoord);
        } else if (mc != null && mc.thePlayer != null) {
            // No cages-open capture yet: start the route from where you’re standing (manual / late load)
            EntityPlayerSP player = mc.thePlayer;
            start = new Vec3(player.posX, player.posY, player.posZ);
        } else {
            return "Could not get start position (not in world?).";
        }

        List<Vec3> cps = new ArrayList<>(stored.size());
        for (Vec3 v : stored) {
            cps.add(new Vec3(v.xCoord, v.yCoord, v.zCoord));
        }

        int colorRgb = ChestRoutePalette.rgbForIndex(colorIndexForPath(key, p));
        suppressNumberedCheckpointLabels = false;
        pushOverlay(p, colorRgb, start, cps);
        rebuildGlowFromOverlays();

        return null;
    }

    /** Legacy: explicit block coords + main path */
    public static String loadRouteWithStart(double startX, double startY, double startZ, String explicitMapKeyOrNull) {
        return loadRouteWithStart(null, startX, startY, startZ, true, explicitMapKeyOrNull, null);
    }

    public static String loadRouteCagesOrExplicit(Vec3 cagesOrNull, String mapKeyOrNull, String pathName) {
        return loadRouteWithStart(cagesOrNull, 0, 0, 0, false, mapKeyOrNull, pathName);
    }

    public static String loadAllPathsForMapInternal(String mapKey, Vec3 startFeet, boolean fromCagesAutoload) {
        if (mapKey == null || startFeet == null) {
            return "Bad args";
        }
        List<String> paths = ChestRouteStorage.listPathNames(mapKey);
        if (paths.isEmpty()) {
            return "No paths";
        }
        suppressNumberedCheckpointLabels = fromCagesAutoload;
        activeOverlays.clear();
        for (String p : paths) {
            List<Vec3> stored = ChestRouteStorage.getPath(mapKey, p);
            if (stored == null || stored.size() < 2) {
                continue;
            }
            List<Vec3> cps = new ArrayList<>();
            for (Vec3 v : stored) {
                cps.add(new Vec3(v.xCoord, v.yCoord, v.zCoord));
            }
            int colorRgb = ChestRoutePalette.rgbForIndex(paths.indexOf(p));
            pushOverlay(p, colorRgb, new Vec3(startFeet.xCoord, startFeet.yCoord, startFeet.zCoord), cps);
        }
        rebuildGlowFromOverlays();
        return null;
    }

    public static String loadAllPathsForMap(String mapKeyOrNull, Vec3 startFeetOrNull) {
        Minecraft mc = Minecraft.getMinecraft();
        String key = mapKeyOrNull;
        if (key == null || key.isEmpty()) {
            key = HypixelScoreboardMapResolver.tryResolveCurrentMapKey(mc);
        } else {
            key = HypixelScoreboardMapResolver.slugify(mapKeyOrNull);
        }
        if (key == null || "unknown".equals(key)) {
            return "Could not detect map.";
        }
        Vec3 start = startFeetOrNull != null ? startFeetOrNull : cagesSpawnFeet;
        if (start == null && mc != null && mc.thePlayer != null) {
            EntityPlayerSP player = mc.thePlayer;
            start = new Vec3(player.posX, player.posY, player.posZ);
        }
        if (start == null) {
            return "Could not get start position (not in world?).";
        }
        return loadAllPathsForMapInternal(key, start, false);
    }

    public static void unloadRoute() {
        activeOverlays.clear();
        suppressNumberedCheckpointLabels = false;
        GlowTrailRenderer.clearStaticChestRoute();
    }

    public static String unloadPath(String pathName) {
        if (pathName == null || pathName.isEmpty()) {
            return "Need path name.";
        }
        String p = HypixelScoreboardMapResolver.slugify(pathName);
        removeOverlay(p);
        if (activeOverlays.isEmpty()) {
            GlowTrailRenderer.clearStaticChestRoute();
        } else {
            rebuildGlowFromOverlays();
        }
        return null;
    }

    public static boolean isPathLoaded(String pathName) {
        if (pathName == null || pathName.isEmpty()) {
            return false;
        }
        String p = HypixelScoreboardMapResolver.slugify(pathName);
        for (ActiveOverlay o : activeOverlays) {
            if (o.pathName.equals(p)) {
                return true;
            }
        }
        return false;
    }

    public static String getDefaultPathForMap(String mapKey) {
        return ChestRouteStorage.getEffectiveMainPath(mapKey);
    }

    public static String setMainPathForMap(String mapKey, String pathName) {
        if (mapKey == null || mapKey.isEmpty()) {
            return "Invalid map key.";
        }
        String p = HypixelScoreboardMapResolver.slugify(pathName);
        if (p.isEmpty() || "unknown".equals(p)) {
            return "Invalid path name.";
        }
        if (!ChestRouteStorage.setMainPath(mapKey, p)) {
            return "Could not set main path.";
        }
        return null;
    }

    public static String undoDraft() {
        if (draftUndo.isEmpty()) {
            return "Nothing to undo.";
        }
        List<Vec3> prev = draftUndo.remove(draftUndo.size() - 1);
        draft.clear();
        for (Vec3 v : prev) {
            draft.add(new Vec3(v.xCoord, v.yCoord, v.zCoord));
        }
        if (editMode) {
            refreshEditModeVisuals();
        }
        return null;
    }

    private static void pushDraftUndoSnapshot() {
        List<Vec3> snap = new ArrayList<>(draft.size());
        for (Vec3 v : draft) {
            snap.add(new Vec3(v.xCoord, v.yCoord, v.zCoord));
        }
        draftUndo.add(snap);
        if (draftUndo.size() > DRAFT_UNDO_LIMIT) {
            draftUndo.remove(0);
        }
    }

    /**
     * Delete a saved path from disk (and hide it if loaded).
     * @param explicitMapKeyOrNull scoreboard map if null
     */
    public static String deleteSavedPath(String pathName, String explicitMapKeyOrNull) {
        if (pathName == null || pathName.trim().isEmpty()) {
            return "Usage: /cro delete <path> [map_key]";
        }
        String p = HypixelScoreboardMapResolver.slugify(pathName);
        if (p.isEmpty() || "unknown".equals(p)) {
            return "Invalid path name.";
        }
        Minecraft mc = Minecraft.getMinecraft();
        String key = explicitMapKeyOrNull;
        if (key == null || key.isEmpty()) {
            key = HypixelScoreboardMapResolver.tryResolveCurrentMapKey(mc);
        } else {
            key = HypixelScoreboardMapResolver.slugify(explicitMapKeyOrNull);
        }
        if (key == null || "unknown".equals(key)) {
            return "Could not detect map. Use: /cro delete <path> <map_key>";
        }
        if (!ChestRouteStorage.hasPath(key, p)) {
            return "No saved path \"" + p + "\" for map \"" + key + "\". /cro list";
        }
        ChestRouteStorage.deletePath(key, p);
        unloadPath(p);
        return null;
    }
}
