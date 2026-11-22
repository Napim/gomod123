package me.napim.gomod.features;

import me.napim.gomod.GomodReleaseBuild;
import me.napim.gomod.util.GameContext;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.BufferUtils;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

/** Bounded, opt-in diagnostics for BestArmor/useless-item tint decisions and HUD GL state. */
public final class InventoryQolDebugCapture {
    private static final long SAMPLE_INTERVAL_MS = 750L;
    private static final int MAX_SAMPLES = 2500;
    private static final List<String> lines = new ArrayList<String>();
    private static final Map<String, Long> lastSampleAt = new TreeMap<String, Long>();
    // LWJGL 2 validates glGetFloat buffers against the largest possible vector size (16), even
    // though GL_CURRENT_COLOR itself only returns four floats.
    private static final FloatBuffer COLOR_BUFFER = BufferUtils.createFloatBuffer(16);
    private static final ByteBuffer PIXEL_BUFFER = BufferUtils.createByteBuffer(4);

    private static volatile boolean capturing;
    private static long startedAtMs;
    private static volatile long stopAtMs;
    private static volatile int sampleCount;
    private static String lastSavedPath;

    private InventoryQolDebugCapture() {}

    public static synchronized boolean start(Minecraft mc, int seconds) {
        if (GomodReleaseBuild.PUBLIC_RELEASE || capturing) {
            return false;
        }
        lines.clear();
        lastSampleAt.clear();
        startedAtMs = System.currentTimeMillis();
        int durationSeconds = Math.max(5, Math.min(60, seconds));
        stopAtMs = startedAtMs + durationSeconds * 1000L;
        sampleCount = 0;
        lastSavedPath = null;
        lines.add("Inventory QoL diagnostic v4");
        lines.add("started=" + new Date(startedAtMs));
        lines.add("duration_seconds=" + durationSeconds + " sample_interval_ms=" + SAMPLE_INTERVAL_MS
                + " sample_limit=" + MAX_SAMPLES);
        lines.add("Note: item NBT contents are not dumped; only NBT keys and a hash are included.");
        appendSnapshot("start", mc);
        capturing = true;
        return true;
    }

    public static synchronized boolean isCapturing() {
        return capturing && System.currentTimeMillis() < stopAtMs && sampleCount < MAX_SAMPLES;
    }

    /** Volatile fast-path for the HUD render loop when a capture is not armed. */
    public static boolean isCaptureArmed() {
        return capturing;
    }

    public static synchronized int getSampleCount() {
        return sampleCount;
    }

    /** Check cheaply before querying live GL state. Called from a HUD slot render on the client thread. */
    public static synchronized boolean shouldSample(String source, int slot, Minecraft mc) {
        if (!capturing || mc == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now >= stopAtMs || sampleCount >= MAX_SAMPLES) {
            capturing = false;
            return false;
        }
        String key = safe(source) + ":" + slot;
        Long last = lastSampleAt.get(key);
        if (last != null && now - last < SAMPLE_INTERVAL_MS) {
            return false;
        }
        lastSampleAt.put(key, now);
        return true;
    }

    /** Capture one actual slot decision and its GL state around the underlay pass. */
    public static synchronized void recordSlot(String source, int slot, int x, int y, Minecraft mc,
            EntityPlayer player, ItemStack stack, boolean bestArmorEnabled, boolean bestArmorActive,
            boolean bestArmorDraw, int tieType, boolean split, boolean uselessEnabled, boolean uselessActive,
            int uselessLevel, String beforeGl, String drawGl, String afterGl,
            String pixelsBefore, String pixelsAfter, String result) {
        if (!capturing || sampleCount >= MAX_SAMPLES) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now >= stopAtMs) {
            capturing = false;
            return;
        }
        sampleCount++;
        String item = describeStack(stack);
        String bestColor = describeBestArmorColor(bestArmorDraw, tieType, split);
        String uselessColor = describeUselessColor(uselessLevel);
        lines.add("sample[" + sampleCount + "] t_ms=" + (now - startedAtMs)
                + " source=" + safe(source) + " frame=" + InventoryQolHudUnderlay.getHudFrameSerial()
                + " hotbarFallbackAllowed=" + InventoryQolHudUnderlay.shouldRenderHotbarFallback()
                + " slot=" + slot + " xy=" + x + "," + y
                + " screen=" + (mc == null || mc.currentScreen == null ? "<none>" : mc.currentScreen.getClass().getName())
                + " item=" + item
                + " bestArmor.enabled=" + bestArmorEnabled + " bestArmor.contextActive=" + bestArmorActive
                + " bestArmor.draw=" + bestArmorDraw + " tieType=" + tieType + " split=" + split
                + " bestArmor.color=" + bestColor
                + " useless.enabled=" + uselessEnabled + " useless.contextActive=" + uselessActive
                + " useless.level=" + uselessLevel + " useless.color=" + uselessColor
                + " selectedSlot=" + (player == null || player.inventory == null ? -1 : player.inventory.currentItem)
                + " result=" + safe(result)
                + " gl.before={" + safe(beforeGl) + "} gl.draw={" + safe(drawGl)
                + "} gl.after={" + safe(afterGl) + "}"
                + " pixels.before={" + safe(pixelsBefore) + "} pixels.after={" + safe(pixelsAfter) + "}");
    }

    public static synchronized String stopAndSave(Minecraft mc) throws IOException {
        if (lines.isEmpty()) {
            return null;
        }
        capturing = false;
        lines.add("stopped=" + new Date() + " elapsed_ms=" + (System.currentTimeMillis() - startedAtMs)
                + " samples=" + sampleCount + " duration_expired=" + (System.currentTimeMillis() >= stopAtMs));
        appendSnapshot("stop", mc);
        File directory = new File(GomodPaths.getGomod123Dir(), "debugall");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("could not create debugall directory");
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date());
        File log = new File(directory, "inventory_qol_" + stamp + "_"
                + Long.toUnsignedString(ThreadLocalRandom.current().nextLong()) + ".log");
        Files.write(log.toPath(), lines, StandardCharsets.UTF_8);
        lastSavedPath = log.getAbsolutePath();
        lines.clear();
        lastSampleAt.clear();
        return lastSavedPath;
    }

    public static synchronized String status() {
        if (!lines.isEmpty() && capturing) {
            long elapsed = System.currentTimeMillis() - startedAtMs;
            long remaining = Math.max(0L, stopAtMs - System.currentTimeMillis());
            return "capturing=" + isCapturing() + " elapsed_ms=" + elapsed + " remaining_ms=" + remaining
                    + " samples=" + sampleCount;
        }
        return "capturing=false samples=" + sampleCount + " lastSaved=" + (lastSavedPath == null ? "<none>" : lastSavedPath);
    }

    /** Actual GL values are only queried while a capture is armed. */
    public static String glState() {
        try {
            COLOR_BUFFER.clear();
            GL11.glGetFloat(GL11.GL_CURRENT_COLOR, COLOR_BUFFER);
            float red = COLOR_BUFFER.get(0);
            float green = COLOR_BUFFER.get(1);
            float blue = COLOR_BUFFER.get(2);
            float alpha = COLOR_BUFFER.get(3);
            return "blend=" + GL11.glIsEnabled(GL11.GL_BLEND)
                    + ",alphaTest=" + GL11.glIsEnabled(GL11.GL_ALPHA_TEST)
                    + ",texture2D=" + GL11.glIsEnabled(GL11.GL_TEXTURE_2D)
                    + ",lighting=" + GL11.glIsEnabled(GL11.GL_LIGHTING)
                    + ",depth=" + GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
                    + ",depthMask=" + GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
                    + ",depthFunc=" + GL11.glGetInteger(GL11.GL_DEPTH_FUNC)
                    + ",blendSrcRgb=" + GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB)
                    + ",blendDstRgb=" + GL11.glGetInteger(GL14.GL_BLEND_DST_RGB)
                    + ",blendSrcAlpha=" + GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA)
                    + ",blendDstAlpha=" + GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA)
                    + ",framebuffer=" + GL11.glGetInteger(org.lwjgl.opengl.EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT)
                    + ",color=" + red + "," + green + "," + blue + "," + alpha;
        } catch (Throwable error) {
            return "error=" + error.getClass().getSimpleName() + ":" + safe(error.getMessage());
        }
    }

    /** Sample four interior slot pixels from the active framebuffer. This is only called during an opt-in capture. */
    public static String framebufferPixels(int x, int y, Minecraft mc) {
        if (mc == null || mc.displayWidth <= 0 || mc.displayHeight <= 0) {
            return "<no_display>";
        }
        try {
            int scale = Math.max(1, new net.minecraft.client.gui.ScaledResolution(mc).getScaleFactor());
            int[][] points = {{3, 3}, {12, 3}, {3, 12}, {12, 12}};
            StringBuilder out = new StringBuilder(80);
            for (int i = 0; i < points.length; i++) {
                int px = Math.max(0, Math.min(mc.displayWidth - 1,
                        (x + points[i][0]) * scale + scale / 2));
                int py = Math.max(0, Math.min(mc.displayHeight - 1,
                        mc.displayHeight - 1 - (y + points[i][1]) * scale - scale / 2));
                PIXEL_BUFFER.clear();
                GL11.glReadPixels(px, py, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, PIXEL_BUFFER);
                if (i > 0) out.append(';');
                out.append('p').append(i).append('@').append(px).append(',').append(py).append('=')
                        .append(PIXEL_BUFFER.get(0) & 255).append(',')
                        .append(PIXEL_BUFFER.get(1) & 255).append(',')
                        .append(PIXEL_BUFFER.get(2) & 255).append(',')
                        .append(PIXEL_BUFFER.get(3) & 255);
            }
            return out.toString();
        } catch (Throwable error) {
            return "error=" + error.getClass().getSimpleName() + ":" + safe(error.getMessage());
        }
    }

    private static void appendSnapshot(String phase, Minecraft mc) {
        lines.add("-- snapshot " + phase + " --");
        lines.add("render.hotbarFallbackAllowed=" + InventoryQolHudUnderlay.shouldRenderHotbarFallback());
        if (mc == null) {
            lines.add("minecraft=<null>");
            return;
        }
        boolean singleplayer;
        try {
            singleplayer = mc.isSingleplayer();
        } catch (Throwable ignored) {
            singleplayer = false;
        }
        lines.add("client.singleplayer=" + singleplayer);
        lines.add("client.worldLoaded=" + (mc.theWorld != null) + " dimension="
                + (mc.thePlayer == null ? "<none>" : mc.thePlayer.dimension)
                + " worldTime=" + (mc.theWorld == null ? -1L : mc.theWorld.getTotalWorldTime())
                + " screen=" + (mc.currentScreen == null ? "<none>" : mc.currentScreen.getClass().getName()));
        try {
            net.minecraft.client.gui.ScaledResolution resolution = new net.minecraft.client.gui.ScaledResolution(mc);
            lines.add("display=" + mc.displayWidth + "x" + mc.displayHeight
                    + " scaled=" + resolution.getScaledWidth() + "x" + resolution.getScaledHeight()
                    + " scaleFactor=" + resolution.getScaleFactor());
        } catch (Throwable ignored) {
        }
        EntityPlayer player = mc.thePlayer;
        if (player == null || player.inventory == null) {
            lines.add("playerInventory=<not loaded>");
            appendSettings(mc);
            return;
        }
        lines.add("playerInventory.currentItem=" + player.inventory.currentItem
                + " cursor=" + describeStack(player.inventory.getItemStack()));
        for (int i = 0; i < player.inventory.mainInventory.length; i++) {
            lines.add("player.main[" + i + "]=" + describeStack(player.inventory.mainInventory[i]));
        }
        String[] armorNames = {"boots", "leggings", "chestplate", "helmet"};
        for (int i = 0; i < player.inventory.armorInventory.length; i++) {
            String name = i < armorNames.length ? armorNames[i] : "slot" + i;
            lines.add("player.armor." + name + "=" + describeStack(player.inventory.armorInventory[i]));
        }
        appendContainer(mc, player);
        lines.add("bestArmor.cache=" + BestArmorDisplay.getArmorCandidateCacheContextForDebug());
        ItemStack[] cachedRecommendations = BestArmorDisplay.getCachedMixedSetForDebug();
        for (int i = 0; i < armorNames.length; i++) {
            lines.add("bestArmor.cachedRecommendation." + armorNames[i] + "="
                    + describeStack(cachedRecommendations == null ? null : cachedRecommendations[i]));
        }
        appendSettings(mc);
    }

    private static void appendSettings(Minecraft mc) {
        boolean bestEnabled = BestArmorDisplay.isEnabled();
        boolean bestActive = BestArmorDisplay.isOverlayActiveInContext(mc);
        boolean uselessEnabled = UselessItemOverlay.isEnabled();
        boolean uselessActive = UselessItemOverlay.isOverlayActiveInContext(mc);
        boolean suppress = GameContext.shouldSuppressInventoryQolOverlays(mc);
        lines.add("settings.bestArmor.enabled=" + bestEnabled
                + " contextActive=" + bestActive
                + " percentageMode=" + ConfigManager.getBestArmorPercentageMode()
                + " containerStrategy=" + BestArmorContainerRenderStrategy.get()
                + " debugPaint=" + ConfigManager.isBestArmorDebugOverlayPaint());
        lines.add("settings.useless.enabled=" + uselessEnabled
                + " contextActive=" + uselessActive
                + " forceEverywhereHypixel=" + ConfigManager.isUselessItemOverlayForceEverywhere()
                + " chestPickHints=" + UselessItemOverlay.isChestPickHintsEnabled()
                + " suppressInventoryQol=" + suppress
                + " onHypixel=" + GameContext.isOnHypixel(mc));
        lines.add("render.inventoryHudEnabled=" + InventoryHUD.isEnabled()
                + " inventoryHudForce=" + InventoryHUD.isForce());
        lines.add("render.gl=" + glState());
    }

    private static void appendContainer(Minecraft mc, EntityPlayer player) {
        Container container = player.openContainer;
        if (container == null) {
            lines.add("openContainer=<null>");
            return;
        }
        lines.add("openContainer.class=" + container.getClass().getName() + " windowId=" + container.windowId
                + " slots=" + (container.inventorySlots == null ? -1 : container.inventorySlots.size()));
        if (container.inventorySlots == null) {
            return;
        }
        for (int i = 0; i < container.inventorySlots.size(); i++) {
            try {
                Slot slot = (Slot) container.inventorySlots.get(i);
                String inventoryName = slot == null || slot.inventory == null ? "<none>" : slot.inventory.getName();
                lines.add("container[" + i + "] inventory=" + safe(inventoryName)
                        + " slotIndex=" + (slot == null ? -1 : slot.getSlotIndex())
                        + " stack=" + describeStack(slot == null ? null : slot.getStack()));
            } catch (Throwable error) {
                lines.add("container[" + i + "]=error:" + error.getClass().getSimpleName());
            }
        }
    }

    static String describeStack(ItemStack stack) {
        if (stack == null || stack.getItem() == null || stack.stackSize <= 0) {
            return "<empty>";
        }
        String id = "<unknown>";
        try {
            ResourceLocation key = (ResourceLocation) Item.itemRegistry.getNameForObject(stack.getItem());
            if (key != null) id = key.toString();
        } catch (Throwable ignored) {
        }
        String name;
        try {
            name = quote(stack.getDisplayName());
        } catch (Throwable ignored) {
            name = "<error>";
        }
        String enchantments = "[]";
        try {
            Map<Integer, Integer> map = EnchantmentHelper.getEnchantments(stack);
            if (map != null && !map.isEmpty()) {
                List<String> entries = new ArrayList<String>();
                for (Map.Entry<Integer, Integer> entry : map.entrySet()) {
                    entries.add(entry.getKey() + ":" + entry.getValue());
                }
                Collections.sort(entries);
                enchantments = entries.toString();
            }
        } catch (Throwable ignored) {
        }
        NBTTagCompound tag = stack.getTagCompound();
        Set<String> keys = tag == null ? Collections.<String>emptySet() : tag.getKeySet();
        String effects = "";
        if (stack.getItem() instanceof net.minecraft.item.ItemPotion) {
            try {
                List<net.minecraft.potion.PotionEffect> potionEffects =
                        ((net.minecraft.item.ItemPotion) stack.getItem()).getEffects(stack);
                effects = ",potionEffects=" + (potionEffects == null ? "[]" : potionEffects.toString());
            } catch (RuntimeException error) {
                effects = ",potionEffects=<error>";
            }
        }
        return "id=" + id + ",count=" + stack.stackSize + ",meta=" + stack.getMetadata()
                + ",damage=" + stack.getItemDamage() + ",maxDamage=" + stack.getMaxDamage()
                + ",enchants=" + enchantments + effects + ",name=" + name
                + ",tagKeys=" + keys + ",tagHash=" + (tag == null ? 0 : tag.hashCode());
    }

    private static String describeBestArmorColor(boolean draw, int tieType, boolean split) {
        if (!draw) return "none";
        if (tieType == -1) return "fill=0x4000FF00 edge=0x8000FF00";
        String tie = "tieFill=" + hex(BestArmorDisplay.tieFillColor(tieType))
                + " tieEdge=" + hex(BestArmorDisplay.tieBorderColor(tieType));
        return split ? "greenFill=0x4000FF00 greenEdge=0x8000FF00 " + tie : tie;
    }

    private static String describeUselessColor(int level) {
        if (level <= 0) return "none";
        return level >= 2 ? "fill=0x45882222 edge=0xD0AA3333" : "fill=0x38FF6B6B edge=0xC0FF8888";
    }

    private static String hex(int value) {
        return String.format(Locale.ROOT, "0x%08X", value);
    }

    private static String quote(String value) {
        if (value == null) return "<null>";
        String text = value.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
        if (text.length() > 160) text = text.substring(0, 160) + "...";
        return "\"" + text + "\"";
    }

    private static String safe(String value) {
        if (value == null) return "<null>";
        return value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
    }

}
