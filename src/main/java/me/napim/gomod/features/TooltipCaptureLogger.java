package me.napim.gomod.features;

import me.napim.gomod.Main;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumChatFormatting;
import me.napim.gomod.util.GomodPaths;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class TooltipCaptureLogger {
    private static File tooltipLogsDir() {
        return new File(GomodPaths.getGomod123Dir(), "tooltip_logs");
    }
    private static boolean enabled = false;
    private static String lastLoggedFingerprint = "";
    private static File activeLogFile = null;
    private static ItemStack pendingTooltipStack = null;
    private static int pendingMouseX = 0;
    private static int pendingMouseY = 0;

    public static synchronized void beginItemTooltip(ItemStack stack, int mouseX, int mouseY) {
        if (!enabled) {
            return;
        }

        pendingTooltipStack = copyStack(stack);
        pendingMouseX = mouseX;
        pendingMouseY = mouseY;
        captureFallbackTooltip(stack, mouseX, mouseY);
    }

    public static synchronized void endItemTooltip() {
        pendingTooltipStack = null;
        pendingMouseX = 0;
        pendingMouseY = 0;
    }

    public static synchronized void captureRenderedTooltip(List<String> tooltip, int mouseX, int mouseY) {
        if (!enabled || tooltip == null || tooltip.isEmpty()) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return;
        }

        ItemStack stack = pendingTooltipStack;
        String screenName = mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getSimpleName();
        String containerName = getContainerName(mc);
        String fingerprint = buildFingerprint(screenName, containerName, stack, tooltip);
        if (fingerprint.equals(lastLoggedFingerprint)) {
            return;
        }
        lastLoggedFingerprint = fingerprint;

        ensureLogFile();
        if (activeLogFile == null) {
            return;
        }

        NBTTagCompound serialized = new NBTTagCompound();
        String registryName = "none";
        int metadata = -1;
        int amount = 0;
        String displayName = "";
        if (stack != null) {
            Item item = stack.getItem();
            registryName = item == null || Item.itemRegistry == null
                    ? "unknown"
                    : String.valueOf(Item.itemRegistry.getNameForObject(item));
            metadata = stack.getMetadata();
            amount = stack.stackSize;
            displayName = safe(stack.getDisplayName());
            try {
                stack.writeToNBT(serialized);
            } catch (Throwable ignored) {
            }
        }

        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        try (FileWriter writer = new FileWriter(activeLogFile, true)) {
            writer.write("=== " + timestamp + " ===" + System.lineSeparator());
            writer.write("screen=" + screenName + System.lineSeparator());
            writer.write("container=" + containerName + System.lineSeparator());
            writer.write("mouse=" + mouseX + "," + mouseY + System.lineSeparator());
            writer.write("item_mouse=" + pendingMouseX + "," + pendingMouseY + System.lineSeparator());
            writer.write("stack_present=" + (stack != null) + System.lineSeparator());
            writer.write("item=" + registryName + System.lineSeparator());
            writer.write("meta=" + metadata + System.lineSeparator());
            writer.write("amount=" + amount + System.lineSeparator());
            writer.write("display=" + displayName + System.lineSeparator());
            writer.write("formatted_tooltip:" + System.lineSeparator());
            for (int i = 0; i < tooltip.size(); i++) {
                writer.write((i + 1) + ": " + safe(tooltip.get(i)) + System.lineSeparator());
            }
            writer.write("stripped_tooltip:" + System.lineSeparator());
            for (int i = 0; i < tooltip.size(); i++) {
                writer.write((i + 1) + ": " + stripFormatting(tooltip.get(i)) + System.lineSeparator());
            }
            writer.write("nbt=" + serialized + System.lineSeparator());
            writer.write(System.lineSeparator());
        } catch (IOException ignored) {
        }
    }

    private static synchronized void toggleLogging() {
        enabled = !enabled;
        lastLoggedFingerprint = "";
        endItemTooltip();
        if (enabled) {
            activeLogFile = createSessionLogFile();
            writeSessionBanner("enabled");
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Tooltip logging "
                    + EnumChatFormatting.GREEN + "enabled"
                    + EnumChatFormatting.YELLOW + " -> "
                    + EnumChatFormatting.WHITE + getActiveLogPath());
            return;
        }

        writeSessionBanner("disabled");
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Tooltip logging "
                + EnumChatFormatting.RED + "disabled"
                + EnumChatFormatting.YELLOW + ".");
    }

    public static synchronized boolean toggleFromCommand() {
        toggleLogging();
        return enabled;
    }

    public static synchronized void setEnabled(boolean value) {
        if (enabled == value) {
            return;
        }
        toggleLogging();
    }

    public static synchronized boolean isEnabled() {
        return enabled;
    }

    private static String buildFingerprint(String screenName, String containerName, ItemStack stack, List<String> tooltip) {
        StringBuilder builder = new StringBuilder();
        builder.append(screenName).append('|')
                .append(containerName).append('|')
                .append(stack == null ? "none" : stack.getItem()).append('|')
                .append(stack == null ? -1 : stack.getMetadata()).append('|')
                .append(stack == null ? 0 : stack.stackSize).append('|');
        for (String line : tooltip) {
            builder.append(line).append('\n');
        }
        return builder.toString();
    }

    private static ItemStack copyStack(ItemStack stack) {
        if (stack == null) {
            return null;
        }
        try {
            return stack.copy();
        } catch (Throwable ignored) {
            return stack;
        }
    }

    private static void captureFallbackTooltip(ItemStack stack, int mouseX, int mouseY) {
        if (!enabled || stack == null) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return;
        }

        List<String> tooltip;
        try {
            tooltip = stack.getTooltip(mc.thePlayer, mc.gameSettings != null && mc.gameSettings.advancedItemTooltips);
        } catch (Throwable ignored) {
            return;
        }

        if (tooltip == null || tooltip.isEmpty()) {
            return;
        }

        captureRenderedTooltip(new ArrayList<>(tooltip), mouseX, mouseY);
    }

    private static synchronized void writeSessionBanner(String state) {
        ensureLogFile();
        if (activeLogFile == null) {
            return;
        }

        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        try (FileWriter writer = new FileWriter(activeLogFile, true)) {
            writer.write("=== logger " + state + " @ " + timestamp + " ===" + System.lineSeparator());
            writer.write(System.lineSeparator());
        } catch (IOException ignored) {
        }
    }

    private static String getContainerName(Minecraft mc) {
        try {
            if (!(mc.currentScreen instanceof GuiChest) || !(mc.thePlayer.openContainer instanceof ContainerChest)) {
                return "none";
            }
            IInventory lower = ((ContainerChest) mc.thePlayer.openContainer).getLowerChestInventory();
            if (lower == null || lower.getDisplayName() == null) {
                return "none";
            }
            return safe(lower.getDisplayName().getUnformattedText());
        } catch (Throwable ignored) {
            return "none";
        }
    }

    private static void ensureLogFile() {
        if (activeLogFile == null) {
            activeLogFile = createSessionLogFile();
        }
    }

    private static File createSessionLogFile() {
        File dir = tooltipLogsDir();
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File file = new File(dir, "tooltip_capture_" + timestamp + ".log");
        try {
            if (!file.exists() && !file.createNewFile()) {
                return null;
            }
            return file;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static String getActiveLogPath() {
        return activeLogFile == null ? tooltipLogsDir().getAbsolutePath() : activeLogFile.getAbsolutePath();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String stripFormatting(String value) {
        String stripped = EnumChatFormatting.getTextWithoutFormattingCodes(value);
        return stripped == null ? "" : stripped;
    }
}
