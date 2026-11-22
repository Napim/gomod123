package me.napim.gomod.features;

import me.napim.gomod.Main;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.event.RenderGameOverlayEvent;
import net.weavemc.loader.api.event.SubscribeEvent;

import java.util.Iterator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LootCall — announces inventory milestones for teammates.
 *
 * Diamonds / Iron mention sticks so teammates know whether a sword craft is possible.
 * Gold mentions apples so teammates know whether a golden apple craft is possible.
 *
 * Repeated calls in the same game append {@code now} to help bypass Hypixel's similar-message antispam.
 */
public final class LootCall {

    private static boolean enabled = false;
    /** When true, loot calls fire in ANY game, not just Blitz teams. */
    private static boolean everywhere = false;

    private enum ResourceKind {
        DIAMOND,
        IRON,
        GOLD
    }

    private static final class ResourceAnnouncement {
        private final String queueKey;
        private final EnumSet<ResourceKind> kinds;
        private final String message;

        private ResourceAnnouncement(String queueKey, EnumSet<ResourceKind> kinds, String message) {
            this.queueKey = queueKey;
            this.kinds = kinds;
            this.message = message;
        }
    }

    // ── toggle / query ──────────────────────────────────────────────

    public static String toggle() {
        enabled = !enabled;
        ConfigManager.setLootCallEnabled(enabled);
        String status = enabled
                ? EnumChatFormatting.GREEN + "enabled"
                : EnumChatFormatting.RED + "disabled";
        return Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "LootCall " + status;
    }

    public static String toggleEverywhere() {
        everywhere = !everywhere;
        ConfigManager.setLootCallEverywhere(everywhere);
        String status = everywhere
                ? EnumChatFormatting.GREEN + "enabled"
                : EnumChatFormatting.RED + "disabled";
        return Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "LootCall everywhere mode " + status;
    }

    public static boolean isEnabled() { return enabled; }

    public static void setEnabled(boolean value) { enabled = value; ConfigManager.setLootCallEnabled(value); }

    public static boolean isEverywhere() { return everywhere; }

    public static void setEverywhere(boolean value) { everywhere = value; ConfigManager.setLootCallEverywhere(value); }

    public static boolean isAnnounceDiamond() {
        return ConfigManager.isLootCallAnnounceDiamond();
    }

    public static void setAnnounceDiamond(boolean value) {
        ConfigManager.setLootCallAnnounceDiamond(value);
    }

    public static boolean isAnnounceIron() {
        return ConfigManager.isLootCallAnnounceIron();
    }

    public static void setAnnounceIron(boolean value) {
        ConfigManager.setLootCallAnnounceIron(value);
    }

    public static boolean isAnnounceGold() {
        return ConfigManager.isLootCallAnnounceGold();
    }

    public static void setAnnounceGold(boolean value) {
        ConfigManager.setLootCallAnnounceGold(value);
    }

    /** Called at startup to restore saved state. */
    public static void loadFromConfig() {
        enabled = ConfigManager.isLootCallEnabled();
        everywhere = ConfigManager.isLootCallEverywhere();
    }

    /** Previous tick counts so we only fire on increases */
    private int prevIronCount = -1;
    private int prevDiamondCount = -1;
    private int prevGoldCount = -1;
    private int prevStickCount = -1;
    private int prevAppleCount = -1;

    /** Throttle: only check inventory every ~50 ms (roughly 1 game tick) */
    private long lastCheckMs = 0;
    private static final long CHECK_INTERVAL_MS = 50;

    /** Cooldown between server chat messages to avoid anti-spam */
    private long lastAnnouncedMs = 0;
    private static final long ANNOUNCE_COOLDOWN_MS = 1150;

    /** Replace older unsent messages of the same category with the newest wording/counts. */
    private final LinkedHashMap<String, ResourceAnnouncement> pendingAnnouncements = new LinkedHashMap<String, ResourceAnnouncement>();

    /** Tracks whether each resource type already announced at least once in the current game. */
    private boolean announcedDiamondThisGame = false;
    private boolean announcedIronThisGame = false;
    private boolean announcedGoldThisGame = false;

    /**
     * After a chat line already reflects stick/apple counts for diamond/iron/gold, skip further stick-only or
     * apple-only updates until the corresponding ingot count bumps again.
     */
    private boolean suppressStickOnlyDiamond = false;
    private boolean suppressStickOnlyIron = false;
    private boolean suppressAppleOnlyGold = false;

    private WorldClient lastWorld = null;

    // ── render overlay listener (fires every frame, reliable on Lunar+Weave) ──

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            resetForNewContext(null);
            return;
        }

        if (!enabled) {
            resetForNewContext(mc.theWorld);
            return;
        }

        if (mc.theWorld != lastWorld) {
            resetForNewContext(mc.theWorld);
        }

        // Throttle to roughly once per game tick (~50 ms)
        long now = System.currentTimeMillis();
        if (now - lastCheckMs < CHECK_INTERVAL_MS) {
            flushAnnouncementQueue(mc, now);
            return;
        }
        lastCheckMs = now;

        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        if (!everywhere && !BlitzTeamsDetector.isBlitzTeamsGame(mc)) {
            resetForNewContext(mc.theWorld);
            return;
        }

        InventoryPlayer inv = mc.thePlayer.inventory;
        int ironCount = countItem(inv, Items.iron_ingot);
        int diamondCount = countItem(inv, Items.diamond);
        int goldCount = countItem(inv, Items.gold_ingot);
        int stickCount = countItem(inv, Items.stick);
        int appleCount = countItem(inv, Items.apple);

        // First check after enable / world change — just snapshot, don't announce
        if (prevIronCount == -1) {
            prevIronCount = ironCount;
            prevDiamondCount = diamondCount;
            prevGoldCount = goldCount;
            prevStickCount = stickCount;
            prevAppleCount = appleCount;
            flushAnnouncementQueue(mc, now);
            return;
        }

        boolean diamondIncreased = diamondCount > prevDiamondCount;
        boolean ironIncreased = ironCount > prevIronCount;
        if (diamondIncreased && isAnnounceDiamond()) {
            suppressStickOnlyDiamond = false;
            if (stickCount > 0) {
                suppressStickOnlyDiamond = true;
            }
        }
        if (ironIncreased && isAnnounceIron()) {
            suppressStickOnlyIron = false;
            if (stickCount > 0) {
                suppressStickOnlyIron = true;
            }
        }
        if ((diamondIncreased && isAnnounceDiamond()) || (ironIncreased && isAnnounceIron())) {
            queueAnnouncement(buildSwordAnnouncement(diamondCount, ironCount, stickCount));
        }
        if (goldCount > prevGoldCount && isAnnounceGold()) {
            suppressAppleOnlyGold = false;
            queueAnnouncement(buildGoldAnnouncement(goldCount, appleCount));
            if (appleCount > 0) {
                suppressAppleOnlyGold = true;
            }
        }

        if (stickCount > prevStickCount) {
            if (isAnnounceDiamond() && diamondCount > 0 && announcedDiamondThisGame && !suppressStickOnlyDiamond) {
                queueAnnouncement(buildSwordAnnouncement(diamondCount, ironCount, stickCount));
                suppressStickOnlyDiamond = true;
            }
            if (isAnnounceIron() && ironCount > 0 && announcedIronThisGame && !suppressStickOnlyIron) {
                queueAnnouncement(buildSwordAnnouncement(diamondCount, ironCount, stickCount));
                suppressStickOnlyIron = true;
            }
        }

        if (isAnnounceGold() && appleCount > prevAppleCount && goldCount > 0 && announcedGoldThisGame && !suppressAppleOnlyGold) {
            queueAnnouncement(buildGoldAnnouncement(goldCount, appleCount));
            suppressAppleOnlyGold = true;
        }

        prevIronCount = ironCount;
        prevDiamondCount = diamondCount;
        prevGoldCount = goldCount;
        prevStickCount = stickCount;
        prevAppleCount = appleCount;

        flushAnnouncementQueue(mc, now);
    }

    // ── helpers ─────────────────────────────────────────────────────

    private void resetForNewContext(WorldClient world) {
        prevIronCount = -1;
        prevDiamondCount = -1;
        prevGoldCount = -1;
        prevStickCount = -1;
        prevAppleCount = -1;
        pendingAnnouncements.clear();
        announcedDiamondThisGame = false;
        announcedIronThisGame = false;
        announcedGoldThisGame = false;
        suppressStickOnlyDiamond = false;
        suppressStickOnlyIron = false;
        suppressAppleOnlyGold = false;
        lastWorld = world;
    }

    /**
     * Counts the total number of a specific item across the entire player inventory
     * (main inventory + hotbar + armor slots).
     */
    private static int countItem(InventoryPlayer inv, Item target) {
        int total = 0;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack != null && stack.getItem() == target) {
                total += stack.stackSize;
            }
        }
        return total;
    }

    private void queueAnnouncement(ResourceAnnouncement announcement) {
        if (announcement == null || announcement.message == null || announcement.message.isEmpty()) {
            return;
        }
        pendingAnnouncements.remove(announcement.queueKey);
        pendingAnnouncements.put(announcement.queueKey, announcement);
    }

    private void flushAnnouncementQueue(Minecraft mc, long now) {
        if (mc == null || mc.thePlayer == null) {
            return;
        }
        if (pendingAnnouncements.isEmpty()) {
            return;
        }
        if (now - lastAnnouncedMs < ANNOUNCE_COOLDOWN_MS) {
            return;
        }

        Iterator<Map.Entry<String, ResourceAnnouncement>> it = pendingAnnouncements.entrySet().iterator();
        if (!it.hasNext()) {
            return;
        }
        ResourceAnnouncement next = it.next().getValue();
        it.remove();

        try {
            mc.thePlayer.sendChatMessage(next.message);
            lastAnnouncedMs = now;
            for (ResourceKind kind : next.kinds) {
                markResourceAnnounced(kind);
            }
        } catch (Throwable ignored) {
        }
    }

    private void markResourceAnnounced(ResourceKind kind) {
        if (kind == null) {
            return;
        }
        switch (kind) {
            case DIAMOND:
                announcedDiamondThisGame = true;
                break;
            case IRON:
                announcedIronThisGame = true;
                break;
            case GOLD:
                announcedGoldThisGame = true;
                break;
            default:
                break;
        }
    }

    private boolean hasAnnouncedThisGame(ResourceKind kind) {
        if (kind == null) {
            return false;
        }
        switch (kind) {
            case DIAMOND:
                return announcedDiamondThisGame;
            case IRON:
                return announcedIronThisGame;
            case GOLD:
                return announcedGoldThisGame;
            default:
                return false;
        }
    }

    private ResourceAnnouncement buildSwordAnnouncement(int diamondCount, int ironCount, int stickCount) {
        EnumSet<ResourceKind> kinds = EnumSet.noneOf(ResourceKind.class);
        StringBuilder msg = new StringBuilder();
        msg.append("I have ");
        boolean hasResource = false;
        boolean repeated = false;
        if (diamondCount > 0 && isAnnounceDiamond()) {
            msg.append('x').append(diamondCount).append(' ')
                    .append(pluralize(diamondCount, "Diamond", "Diamonds"));
            kinds.add(ResourceKind.DIAMOND);
            hasResource = true;
            repeated |= hasAnnouncedThisGame(ResourceKind.DIAMOND);
        }
        if (ironCount > 0 && isAnnounceIron()) {
            if (hasResource) msg.append(" and ");
            msg.append('x').append(ironCount).append(' ')
                    .append(pluralize(ironCount, "Iron Ingot", "Iron Ingots"));
            kinds.add(ResourceKind.IRON);
            hasResource = true;
            repeated |= hasAnnouncedThisGame(ResourceKind.IRON);
        }
        if (!hasResource) return null;
        if (stickCount <= 0) {
            msg.append(" but no sticks");
        } else {
            msg.append(" & ").append(stickCount).append(' ')
                    .append(pluralize(stickCount, "stick", "sticks"));
        }
        if (repeated) {
            msg.append(" now");
        }
        return new ResourceAnnouncement("sword-resources", kinds, msg.toString());
    }

    private ResourceAnnouncement buildGoldAnnouncement(int goldCount, int appleCount) {
        StringBuilder msg = new StringBuilder();
        msg.append("I have x").append(goldCount).append(' ')
                .append(pluralize(goldCount, "Gold Ingot", "Gold Ingots"));
        if (appleCount <= 0) {
            msg.append(" but no apples");
        } else {
            msg.append(" & ").append(appleCount).append(' ')
                    .append(pluralize(appleCount, "apple", "apples"));
        }
        if (hasAnnouncedThisGame(ResourceKind.GOLD)) {
            msg.append(" now");
        }
        return new ResourceAnnouncement("gold", EnumSet.of(ResourceKind.GOLD), msg.toString());
    }

    private static String pluralize(int count, String singular, String plural) {
        return count == 1 ? singular : plural;
    }
}
