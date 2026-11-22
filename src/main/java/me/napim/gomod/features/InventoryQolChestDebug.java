package me.napim.gomod.features;

import me.napim.gomod.GomodReleaseBuild;
import me.napim.gomod.Main;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.weavemc.loader.api.event.TickEvent;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Internal, opt-in whole-container policy recording. Does not read pixels or change item rules. */
public final class InventoryQolChestDebug {
    private static final long SAMPLE_INTERVAL_MS = 250L;
    private static final int MAX_SNAPSHOTS = 240;
    private static final int MAX_CHARACTERS = 8 * 1024 * 1024;
    private static volatile boolean armed;
    private static BufferedWriter writer;
    private static String path;
    private static String previousInputs;
    private static Object previousWorld;
    private static Object previousContainer;
    private static Object previousScreen;
    private static long startedAt;
    private static long stopAt;
    private static long nextSampleAt;
    private static int snapshots;
    private static int characters;

    public static boolean isArmed() {
        return armed;
    }

    public static boolean start(int seconds) throws IOException {
        if (GomodReleaseBuild.PUBLIC_RELEASE || armed || InventoryQolDebugCapture.isCaptureArmed()) return false;
        File directory = new File(GomodPaths.getGomod123Dir(), "debugall");
        Files.createDirectories(directory.toPath());
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date());
        File file = File.createTempFile("inventory_qol_chests_" + stamp + "_", ".log", directory);
        writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8);
        path = file.getAbsolutePath();
        startedAt = System.currentTimeMillis();
        stopAt = startedAt + Math.max(30, Math.min(900, seconds)) * 1000L;
        nextSampleAt = 0L;
        snapshots = 0;
        characters = 0;
        previousInputs = null;
        previousWorld = previousContainer = previousScreen = null;
        try {
            write("Inventory QoL chest decisions v2\nstarted=" + new Date(startedAt)
                    + "\nduration_seconds=" + ((stopAt - startedAt) / 1000L)
                    + " sample_interval_ms=" + SAMPLE_INTERVAL_MS + " snapshot_limit=" + MAX_SNAPSHOTS
                    + " character_limit=" + MAX_CHARACTERS
                    + "\nTAKE=blue chest hint; SKIP=yellow chest hint; RED=useless inventory tint; KEEP=no useless tint."
                    + "\nNONE means empty, a rendering/context gate, or BestArmor owns the slot highlight."
                    + "\nDecisions are recomputed by the current policy, not a measurement of rendered pixels."
                    + "\nChest-hint solo debug override=" + UselessItemOverlay.isChestPickHintDebugForceEnabled()
                    + "\nOnly changed open-container inputs are recorded; identical views are deduplicated."
                    + "\nItems include names, counts, durability, enchantments, NBT keys/hash, not full NBT."
                    + "\nNotes refer to the most recent snapshot; no chat messages or credentials are captured.\n");
            writer.flush();
            armed = true;
            return true;
        } catch (IOException error) {
            try { writer.close(); } catch (IOException ignored) { }
            writer = null;
            throw error;
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (!armed) return;
        Minecraft mc = Minecraft.getMinecraft();
        long now = System.currentTimeMillis();
        if (now >= stopAt) {
            finishAutomatically("duration_expired");
            return;
        }
        if (now < nextSampleAt && mc != null && mc.currentScreen == previousScreen
                && mc.theWorld == previousWorld) return;
        nextSampleAt = now + SAMPLE_INTERVAL_MS;
        captureSafely(mc);
    }

    /** Capture the old screen before it closes, including views opened for less than one tick. */
    public static void onDisplayGuiScreen(Minecraft mc, GuiScreen nextScreen) {
        if (armed && mc != null && mc.currentScreen != nextScreen) captureSafely(mc);
    }

    private static void captureSafely(Minecraft mc) {
        if (!armed) return;
        try {
            capture(mc);
            if (snapshots >= MAX_SNAPSHOTS || characters >= MAX_CHARACTERS) finishAutomatically("capture_limit");
        } catch (IOException error) {
            armed = false;
            if (writer != null) try { writer.close(); } catch (IOException ignored) { }
            writer = null;
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "Inventory QoL chest debug stopped: " + error.getClass().getSimpleName()
                    + ". Partial log: " + path);
        } catch (RuntimeException error) {
            // Keep a malformed slot from interrupting the game; retain evidence and finish the session.
            try { write("capture_error=" + error.getClass().getName() + "\n"); } catch (IOException ignored) { }
            finishAutomatically("capture_error");
        }
    }

    private static void capture(Minecraft mc) throws IOException {
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            previousWorld = previousContainer = previousScreen = null;
            previousInputs = null;
            return;
        }
        if (!(mc.currentScreen instanceof GuiContainer)) {
            previousScreen = null;
            return;
        }
        Container container = ((GuiContainer) mc.currentScreen).inventorySlots;
        if (container == null || container.inventorySlots == null) return;
        InventoryPlayer inventory = mc.thePlayer.inventory;
        String settings = settings();
        String context = UselessItemOverlay.contextForDebug(mc);
        StringBuilder inputs = new StringBuilder(settings).append('\n').append(context)
                .append("\nwindowId=").append(container.windowId)
                .append("\nselected=").append(inventory.currentItem)
                .append("\ncursor=").append(InventoryQolDebugCapture.describeStack(inventory.getItemStack()));
        // Player contents are independent of the chest's slot layout (including equipped armor).
        for (int i = 0; i < 40; i++) {
            inputs.append("\nplayer[").append(i).append("]=")
                    .append(InventoryQolDebugCapture.describeStack(inventory.getStackInSlot(i)));
        }
        for (int i = 0; i < container.inventorySlots.size(); i++) {
            Slot slot = (Slot) container.inventorySlots.get(i);
            inputs.append("\ncontainer[").append(i).append("] index=")
                    .append(slot == null ? -1 : slot.getSlotIndex()).append(" stack=")
                    .append(InventoryQolDebugCapture.describeStack(slot == null ? null : slot.getStack()));
        }
        String fingerprint = inputs.toString();
        if (previousWorld == mc.theWorld && previousContainer == container && previousScreen == mc.currentScreen
                && fingerprint.equals(previousInputs)) return;
        previousInputs = fingerprint;
        previousWorld = mc.theWorld;
        previousContainer = container;
        previousScreen = mc.currentScreen;
        snapshots++;
        write("\n=== snapshot " + snapshots + " elapsed_ms=" + (System.currentTimeMillis() - startedAt)
                + " world=" + System.identityHashCode(mc.theWorld) + " dimension=" + mc.thePlayer.dimension
                + " block=" + (int) Math.floor(mc.thePlayer.posX) + "," + (int) Math.floor(mc.thePlayer.posY)
                + "," + (int) Math.floor(mc.thePlayer.posZ) + " ===\n"
                + "screen=" + mc.currentScreen.getClass().getName() + " container=" + container.getClass().getName()
                + " windowId=" + container.windowId + " slots=" + container.inventorySlots.size() + "\n"
                + fingerprint + "\n");
        boolean bestActive = BestArmorDisplay.isEnabled() && BestArmorDisplay.isOverlayActiveInContext(mc)
                && !BestArmorDisplay.shouldExcludeCurrentContainer();
        BestArmorDisplay.BestArmorUnderlayIntent intent = new BestArmorDisplay.BestArmorUnderlayIntent();
        for (int i = 0; i < container.inventorySlots.size(); i++) {
            Slot slot = (Slot) container.inventorySlots.get(i);
            if (slot == null) continue;
            ItemStack stack = slot.getStack();
            String policy;
            String best = "none";
            try {
                policy = UselessItemOverlay.explainSlotForDebug(slot, mc);
                if (bestActive && stack != null) {
                    BestArmorDisplay.computeBestArmorUnderlayIntentCached(stack, inventory, slot, intent);
                    if (intent.draw) best = "draw,tie=" + intent.tieType + ",split=" + intent.split;
                }
            } catch (RuntimeException error) {
                policy = "ERROR " + error.getClass().getSimpleName();
            }
            write("decision[" + i + "] inventory=" + clean(slot.inventory == null ? null : slot.inventory.getName())
                    + " owner=" + (slot.inventory instanceof InventoryPlayer ? "player" : "container")
                    + " index=" + slot.getSlotIndex() + " guiXY=" + slot.xDisplayPosition + "," + slot.yDisplayPosition
                    + " " + policy + " bestArmor=" + best + "\n");
        }
        write("bestArmor.cache=" + BestArmorDisplay.getArmorCandidateCacheContextForDebug() + "\n");
        writer.flush();
    }

    public static boolean mark(String note) throws IOException {
        if (!armed) return false;
        write("\nNOTE after_snapshot=" + snapshots + " elapsed_ms=" + (System.currentTimeMillis() - startedAt)
                + " text=" + clean(note) + "\n");
        writer.flush();
        return true;
    }

    public static String stopAndSave() throws IOException {
        if (writer == null) return path;
        armed = false;
        try {
            write("\nfinished=" + new Date() + " snapshots=" + snapshots + "\n");
            writer.close();
            writer = null;
            previousInputs = null;
            previousWorld = previousContainer = previousScreen = null;
            return path;
        } catch (IOException error) {
            try { writer.close(); } catch (IOException ignored) { }
            writer = null;
            throw error;
        } finally {
            previousInputs = null;
            previousWorld = previousContainer = previousScreen = null;
        }
    }

    private static void finishAutomatically(String reason) {
        armed = false;
        try {
            if (writer != null) write("\nauto_stop=" + reason + "\n");
            String saved = stopAndSave();
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Inventory QoL chest debug saved: " + saved);
        } catch (IOException error) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "Could not finish Inventory QoL chest debug: " + error.getClass().getSimpleName());
        } finally {
            if (writer != null) try { writer.close(); } catch (IOException ignored) { }
            writer = null;
            previousInputs = null;
            previousWorld = previousContainer = previousScreen = null;
        }
    }

    public static String status() {
        return armed ? "Chest decisions recording: " + snapshots + " snapshots, "
                + Math.max(0L, (stopAt - System.currentTimeMillis()) / 1000L) + "s remaining."
                : (path == null ? "No chest decision capture." : "Last chest decision log: " + path);
    }

    private static void write(String text) throws IOException {
        writer.write(text);
        characters += text.length();
    }

    private static String clean(String text) {
        if (text == null) return "<none>";
        text = text.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        return text.length() > 400 ? text.substring(0, 400) : text;
    }

    private static String settings() {
        return "settings enabled=" + UselessItemOverlay.isEnabled()
                + " chestHints=" + UselessItemOverlay.isChestPickHintsEnabled()
                + " forceHypixel=" + ConfigManager.isUselessItemOverlayForceEverywhere()
                + " bestArmor=" + BestArmorDisplay.isEnabled()
                + " bestArmorPercentage=" + ConfigManager.getBestArmorPercentageMode()
                + "\nalwaysRed bowl=" + ConfigManager.isUselessAlwaysRedBowl()
                + " cake=" + ConfigManager.isUselessAlwaysRedCake()
                + " chestMinecart=" + ConfigManager.isUselessAlwaysRedChestMinecart()
                + " minecart=" + ConfigManager.isUselessAlwaysRedMinecart()
                + " rail=" + ConfigManager.isUselessAlwaysRedRail()
                + " rose=" + ConfigManager.isUselessAlwaysRedRose() + " wheat=" + ConfigManager.isUselessAlwaysRedWheat()
                + " sharpnessFish=" + ConfigManager.isUselessAlwaysRedSharpnessFish()
                + " apple=" + ConfigManager.isUselessAlwaysRedApple()
                + " stick=" + ConfigManager.isUselessAlwaysRedStick()
                + "\nmelee ironVsSharpStone=" + ConfigManager.getUselessMeleeIronVsStoneSharpRed()
                + " diamondVsSharpIron=" + ConfigManager.getUselessMeleeDiamondVsIronSharpRed()
                + " stoneVsSharpGoldWood=" + ConfigManager.getUselessMeleeStoneVsGoldWoodSharpRed()
                + "\nhintColors blueFill=" + Integer.toHexString(ConfigManager.getUselessChestHintBlueFillArgb())
                + " blueEdge=" + Integer.toHexString(ConfigManager.getUselessChestHintBlueEdgeArgb())
                + " yellowFill=" + Integer.toHexString(ConfigManager.getUselessChestHintYellowFillArgb())
                + " yellowEdge=" + Integer.toHexString(ConfigManager.getUselessChestHintYellowEdgeArgb());
    }
}
