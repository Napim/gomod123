package me.napim.gomod.features;

import me.napim.gomod.util.GameContext;
import me.napim.gomod.util.HudGlState;
import me.napim.gomod.util.InventoryQolOverlay;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EnumCreatureAttribute;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemPickaxe;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemHoe;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemRecord;
import net.minecraft.item.ItemSkull;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumChatFormatting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Highlights inventory slots the player can usually throw away (Blitz SG heuristics). Works next to
 * {@link BestArmorDisplay}: never draws red on armor that already has a green / tie overlay.
 * <p>
 * <b>Sticks:</b> unenchanted sticks are Category A (deathmatch / Gulch map only). Enchanted sticks are not.
 * <p>
 * <b>Swords, axes & hoes:</b> swords and hoes use same-shape rankings; axes are compared by attack damage against
 * swords and axes, with swords winning ties because they can block. Same-tier duplicates with identical combat enchants
 * keep the least-Unbreaking copy when neither has sharp/smite/etc. (cleaner for enchanting-table rolls), and the
 * most-Unbreaking copy when combat enchants tie. Equal-damage chest options with the same Fire/Knockback levels use
 * remaining durability as their final tie-break.
 * <p>
 * Informal {@code F} (weapon-vs-food): enchanted {@code minecraft:fish} is treated as a weapon for food-tier logic
 * (not worst-tier food). There is no separate Category F bucket in code — only A/B/D-style rules plus melee + fish.
 * <p>
 * Informal {@code O} (utility melee): {@code O-fire} = sword/axe with Fire Aspect; {@code O-knock} = sword/axe with
 * Knockback. Neither is tinted useless while there is no strictly higher metal sword/axe of the same shape in the grid
 * (same-tier utility sidegrades are kept; upgrading to diamond still allows the worse metal to go red).
 */
public final class UselessItemOverlay {

    private static final int LIGHT_FILL = 0x38FF6B6B;
    private static final int LIGHT_EDGE = 0xC0FF8888;
    private static final int DARK_FILL = 0x45882222;
    private static final int DARK_EDGE = 0xD0AA3333;

    /** Filled from config in {@link #refreshChestPickHintColorsFromConfig()} (see /chestpickcolors). */
    private static int chestHintPickFillArgb = 0x404488FF;
    private static int chestHintPickEdgeArgb = 0xD066AAFF;
    private static int chestHintSkipFillArgb = 0x40FFCC44;
    private static int chestHintSkipEdgeArgb = 0xD0FFDD66;

    /** Worn duplicate-armor red tint only applies if the equipped piece has lost at least this fraction of durability. */
    private static final double WORN_ARMOR_DUPLICATE_RED_MIN_LOST_FRACTION = 0.4;

    /**
     * {@link #levelForChestPickHint} runs once per visible chest slot per frame; shallow-fill + at most one
     * {@link ItemStack#copy} per slot avoids allocating 40 stacks × slot count.
     */
    private static final ItemStack[] CHEST_PICK_SIM_VIEW = new ItemStack[40];
    private static Object chestMeleeCacheContainer;
    private static long chestMeleeCacheTick = Long.MIN_VALUE;
    private static List<ChestMeleeCacheEntry> chestMeleeCacheEntries = new ArrayList<>();
    private static final BestArmorDisplay.BestArmorUnderlayIntent CHEST_PICK_ARMOR_INTENT =
            new BestArmorDisplay.BestArmorUnderlayIntent();

    private static boolean enabled;
    private static boolean chestPickHintsEnabled;
    /** Session-only diagnostic override for testing chest pick hints outside Hypixel/Blitz. */
    private static volatile boolean chestPickHintDebugForce;

    // --- Pre-deathmatch crafting warning (distance-to-crafting-table) ---
    private static final int DEATHMATCH_CRAFT_WARNING_WINDOW_SECONDS = 45;
    private static final int CRAFT_TABLE_SCAN_RADIUS_BLOCKS = 32;
    private static final int CRAFT_TABLE_SCAN_Y_RADIUS = 5;

    // Approximate walk speed in blocks/sec (Blitz uses normal player movement; sprint will be faster, but
    // this conservative speed avoids false "you can make it" results).
    private static final double WALK_SPEED_BLOCKS_PER_SECOND = 4.3;
    private static final double CRAFT_ACTION_BUFFER_SECONDS = 2.0; // time for interaction / minor detours

    private static final Pattern DEATHMATCH_COUNTDOWN_PATTERN =
            Pattern.compile("deathmatch\\s*:?\\s*(\\d{1,2})\\s*:\\s*(\\d{2})", Pattern.CASE_INSENSITIVE);

    private static final Pattern SECTION_FORMAT_PATTERN =
            Pattern.compile("\u00A7[0-9A-FK-ORa-fk-or]");

    private static final long CACHED_DEATHMATCH_SECONDS_INTERVAL_MS = 1000L;
    private static long lastDeathmatchCountdownCacheAt = 0L;
    private static int cachedDeathmatchCountdownSeconds = -1;

    private static final long CACHED_NEAREST_CRAFT_TABLE_DIST_INTERVAL_MS = 1500L;
    private static final double CRAFT_TABLE_CACHE_MOVE_RESCAN_SQ = 4.0;
    private static long lastNearestCraftTableDistCacheAt = 0L;
    private static boolean hasCachedNearestCraftTableDist = false;
    private static Object cachedCraftTableWorld = null;
    private static double cachedCraftTablePlayerX = 0.0;
    private static double cachedCraftTablePlayerY = 0.0;
    private static double cachedCraftTablePlayerZ = 0.0;
    private static double cachedNearestCraftTableDistSq = Double.NaN;

    /**
     * Hypixel Blitz logic reads the sidebar often; {@link #evaluate} runs per inventory slot per frame. Snapshot once per
     * {@link net.minecraft.client.gui.inventory.GuiContainer#drawScreen} (see mixin) so multiplayer GUIs do not reparse
     * the scoreboard dozens of times per frame. Cleared from {@link net.minecraft.client.gui.GuiIngame#renderGameOverlay}
     * HEAD so hotbar/HUD paths keep live {@link GameContext} reads.
     */
    private static boolean invGuiEvalSnapshotActive = false;
    private static boolean invGuiSnapSuppressInvQol;
    private static boolean invGuiSnapBlitzTabSortCtx;
    private static boolean invGuiSnapDeathmatchPhase;
    private static boolean invGuiSnapGulchMap;
    private static boolean invGuiSnapCitadelMap;
    private static boolean invGuiSnapTrollPreKitPendingWitchEgg;

    public static void clearInventoryGuiEvalSnapshot() {
        invGuiEvalSnapshotActive = false;
    }

    public static void beginInventoryGuiEvalSnapshot(Minecraft mc) {
        invGuiEvalSnapshotActive = true;
        if (mc == null) {
            invGuiSnapSuppressInvQol = false;
            invGuiSnapBlitzTabSortCtx = false;
            invGuiSnapDeathmatchPhase = false;
            invGuiSnapGulchMap = false;
            invGuiSnapCitadelMap = false;
            invGuiSnapTrollPreKitPendingWitchEgg = false;
            return;
        }
        invGuiSnapSuppressInvQol = GameContext.shouldSuppressInventoryQolOverlays(mc);
        invGuiSnapBlitzTabSortCtx = GameContext.isBlitzTabListCustomSortContext(mc);
        invGuiSnapDeathmatchPhase = GameContext.isBlitzDeathmatchPhase(mc);
        invGuiSnapGulchMap = GameContext.isBlitzGulchMap(mc);
        invGuiSnapCitadelMap = GameContext.isBlitzCitadelMap(mc);
        invGuiSnapTrollPreKitPendingWitchEgg = GameContext.isBlitzTrollPreKitPendingWitchEgg(mc);
    }

    private static Item itemFromBlock(Block block) {
        if (block == null) {
            return null;
        }
        return Item.getItemFromBlock(block);
    }

    private static final class FoodEntry {
        final Item item;
        /** {@code -1} = any metadata */
        final int meta;

        FoodEntry(Item item, int meta) {
            this.item = item;
            this.meta = meta;
        }

        boolean matches(ItemStack s) {
            if (s == null || s.getItem() != item) {
                return false;
            }
            return meta < 0 || s.getMetadata() == meta;
        }
    }

    /**
     * Best-first (lower index = keep over higher in the overlay; not the same as total saturation).
     * Vanilla 1.8.9 hunger / saturation per item (wiki; used for documentation only):
     * <pre>
     * tier  item                     hunger  saturation
     * 0     golden_carrot             6      14.4
     * 1     cooked_beef               8      12.8
     * 2     cooked_porkchop           8      12.8
     * 3     cooked_fish (cod)         5       6.0
     * 4     cooked_chicken            6       7.2
     * 5     pumpkin_pie               8       4.8
     * 6     bread                     5       6.0
     * 7     carrot                    4       4.8
     * 8     apple                     4       2.4
     * 9     beef (raw)                3       1.8   } same tier stats: overlay tie-break by total count when both in inv
     * 10    porkchop (raw)            3       1.8   }
     * 11    fish meta 1 (salmon)      2       0.4
     * 12    fish meta 0 (cod)         2       0.4
     * 13    fish meta 2 (clownfish)   1       0.2
     * 14    melon                     2       1.2
     * 15    potato (raw)              1       0.6
     * </pre>
     */
    private static final FoodEntry[] FOOD_PRIORITY = new FoodEntry[]{
            new FoodEntry(Items.golden_carrot, -1),
            new FoodEntry(Items.cooked_beef, -1),
            new FoodEntry(Items.cooked_porkchop, -1),
            new FoodEntry(Items.cooked_fish, -1),
            new FoodEntry(Items.cooked_chicken, -1),
            new FoodEntry(Items.pumpkin_pie, -1),
            new FoodEntry(Items.bread, -1),
            new FoodEntry(Items.carrot, -1),
            new FoodEntry(Items.apple, -1),
            new FoodEntry(Items.beef, -1),
            new FoodEntry(Items.porkchop, -1),
            new FoodEntry(Items.fish, 1),
            new FoodEntry(Items.fish, 0),
            new FoodEntry(Items.fish, 2),
            new FoodEntry(Items.melon, -1),
            new FoodEntry(Items.potato, -1),
    };

    private UselessItemOverlay() {
    }

    public static void loadSettings() {
        enabled = ConfigManager.isUselessItemOverlayEnabled();
        chestPickHintsEnabled = ConfigManager.isUselessItemOverlayChestPickHintsEnabled();
        refreshChestPickHintColorsFromConfig();
    }

    /**
     * Reloads ARGB tints for chest pick / skip overlays from {@link ConfigManager}. Call after /chestpickcolors changes.
     */
    public static void refreshChestPickHintColorsFromConfig() {
        chestHintPickFillArgb = ConfigManager.getUselessChestHintBlueFillArgb();
        chestHintPickEdgeArgb = ConfigManager.getUselessChestHintBlueEdgeArgb();
        chestHintSkipFillArgb = ConfigManager.getUselessChestHintYellowFillArgb();
        chestHintSkipEdgeArgb = ConfigManager.getUselessChestHintYellowEdgeArgb();
    }

    /** Edge tint RGB ({@code 0xRRGGBB}) for chest-pick hint tooltips (matches /chestpickcolors edge). */
    public static int getChestPickHintEdgeColorRgb(boolean pickBlue) {
        return (pickBlue ? chestHintPickEdgeArgb : chestHintSkipEdgeArgb) & 0xFFFFFF;
    }

    public static List<String> getChestPickHintsTooltipLines() {
        return Arrays.asList(
                EnumChatFormatting.AQUA + "Chest pick hints",
                formatChestPickHintLegendLine(true),
                formatChestPickHintLegendLine(false)
        );
    }

    private static String formatChestPickHintLegendLine(boolean pickBlue) {
        String label = pickBlue ? "Blue" : "Yellow";
        String tail = pickBlue ? " = item is a good pickup" : " = not worth looting";
        return chatFormattingForChestHintEdge(pickBlue) + label + EnumChatFormatting.GRAY + tail;
    }

    /** Nearest {@link EnumChatFormatting} to the configured chest-hint edge tint. */
    private static String chatFormattingForChestHintEdge(boolean pickBlue) {
        int rgb = getChestPickHintEdgeColorRgb(pickBlue);
        EnumChatFormatting best = pickBlue ? EnumChatFormatting.AQUA : EnumChatFormatting.YELLOW;
        int bestDist = Integer.MAX_VALUE;
        for (EnumChatFormatting fmt : EnumChatFormatting.values()) {
            if (!fmt.isColor()) {
                continue;
            }
            Integer c = vanillaChatFormattingRgb(fmt);
            if (c == null) {
                continue;
            }
            int dr = ((c >> 16) & 0xFF) - ((rgb >> 16) & 0xFF);
            int dg = ((c >> 8) & 0xFF) - ((rgb >> 8) & 0xFF);
            int db = (c & 0xFF) - (rgb & 0xFF);
            int dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) {
                bestDist = dist;
                best = fmt;
            }
        }
        return best.toString();
    }

    /** Vanilla 1.8.9 chat RGB for {@link EnumChatFormatting#isColor()} entries. */
    private static Integer vanillaChatFormattingRgb(EnumChatFormatting fmt) {
        switch (fmt) {
            case BLACK: return 0x000000;
            case DARK_BLUE: return 0x0000AA;
            case DARK_GREEN: return 0x00AA00;
            case DARK_AQUA: return 0x00AAAA;
            case DARK_RED: return 0xAA0000;
            case DARK_PURPLE: return 0xAA00AA;
            case GOLD: return 0xFFAA00;
            case GRAY: return 0xAAAAAA;
            case DARK_GRAY: return 0x555555;
            case BLUE: return 0x5555FF;
            case GREEN: return 0x55FF55;
            case AQUA: return 0x55FFFF;
            case RED: return 0xFF5555;
            case LIGHT_PURPLE: return 0xFF55FF;
            case YELLOW: return 0xFFFF55;
            case WHITE: return 0xFFFFFF;
            default: return null;
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Whether the useless-item tint can draw in the current world/HUD context. */
    public static boolean isOverlayActiveInContext(Minecraft mc) {
        return enabled
                && isBlitzUselessOverlayContext(mc)
                && (!shouldSuppressInventoryQolOverlaysForUseless(mc)
                    || ConfigManager.isUselessItemOverlayForceEverywhere());
    }

    /** Whether the chest-pick tint can own a chest slot in the current GUI context. */
    public static boolean isChestPickHintActiveInContext(Minecraft mc) {
        boolean debugForce = chestPickHintDebugForce;
        if ((!enabled || !chestPickHintsEnabled) && !debugForce) return false;
        if (mc == null || mc.thePlayer == null || !(mc.currentScreen instanceof GuiChest)) return false;
        if (BestArmorDisplay.shouldExcludeCurrentContainer()) return false;
        if (shouldSuppressInventoryQolOverlaysForUseless(mc)
                && !ConfigManager.isUselessItemOverlayForceEverywhere() && !debugForce) return false;
        return debugForce || isBlitzUselessOverlayContext(mc);
    }

    public static void setEnabled(boolean on) {
        enabled = on;
        ConfigManager.setUselessItemOverlayEnabled(on);
    }

    public static void setEnabledSilent(boolean on) {
        enabled = on;
        ConfigManager.setUselessItemOverlayEnabledNoSave(on);
    }

    public static boolean isChestPickHintsEnabled() {
        return chestPickHintsEnabled;
    }

    public static boolean isChestPickHintDebugForceEnabled() {
        return chestPickHintDebugForce;
    }

    public static void setChestPickHintDebugForceEnabled(boolean force) {
        chestPickHintDebugForce = force;
    }

    public static void setChestPickHintsEnabled(boolean on) {
        chestPickHintsEnabled = on;
        ConfigManager.setUselessItemOverlayChestPickHintsEnabled(on);
    }

    /**
     * @param hotbarIndex 0–8
     */
    public static void renderHotbarSlotOverlay(int hotbarIndex, int x, int y, EntityPlayer player) {
        if (!enabled || player == null || player.worldObj == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (!isBlitzUselessOverlayContext(mc)) {
            return;
        }
        ItemStack stack = player.inventory.getStackInSlot(hotbarIndex);
        int level = levelFor(stack, hotbarIndex, player, mc);
        if (level == 0) {
            return;
        }
        pushHotbarUnderlayGlState();
        try {
            drawSlotOverlay(x, y, level);
        } finally {
            popHotbarUnderlayGlState();
        }
    }

    /**
     * Geometry only — caller already applied {@link #pushHotbarUnderlayGlState()} (e.g. combined with BestArmor in
     * {@code GuiIngameMixin} right before {@link net.minecraft.client.renderer.entity.RenderItem#renderItemIntoGUI}).
     */
    public static void renderHotbarSlotOverlayGeometry(int hotbarIndex, int x, int y, EntityPlayer player) {
        if (!enabled || player == null || player.worldObj == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (!isBlitzUselessOverlayContext(mc)) {
            return;
        }
        ItemStack stack = player.inventory.getStackInSlot(hotbarIndex);
        int level = levelFor(stack, hotbarIndex, player, mc);
        if (level == 0) {
            return;
        }
        drawSlotOverlay(x, y, level);
    }

    /** Hotbar and InventoryHUD geometry, in the caller's translated/scaled slot space. */
    public static int renderInventoryHudSlotOverlayGeometry(int inventorySlot, int x, int y,
            EntityPlayer player, Minecraft mc) {
        if (!isOverlayActiveInContext(mc) || player == null || player.worldObj == null
                || inventorySlot < 0 || inventorySlot > 35) {
            return 0;
        }
        int level = levelFor(player.inventory.getStackInSlot(inventorySlot), inventorySlot, player, mc);
        if (level != 0) {
            drawSlotOverlay(x, y, level);
        }
        return level;
    }

    /**
     * Match inventory-slot overlays: depth test off + no depth writes so {@link net.minecraft.client.renderer.entity.RenderItem}
     * is not occluded by the underlay quads when depth is re-enabled for the item icon.
     */
    public static void pushHotbarUnderlayGlState() {
        GlStateManager.pushMatrix();
        HudGlState.restoreForInventoryUnderlay();
    }

    public static void popHotbarUnderlayGlState() {
        HudGlState.restoreAfterInventoryUnderlay();
        GlStateManager.popMatrix();
    }

    /** Main inventory HUD: slot index 9–35, coordinates already include scale/translate from caller? Uses screen-space quads at x,y 16×16. */
    public static void renderInventoryHudSlotOverlay(int inventorySlot, float slotTopLeftX, float slotTopLeftY, float scale) {
        if (!enabled) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || inventorySlot < 9 || inventorySlot > 35) {
            return;
        }
        ItemStack stack = mc.thePlayer.inventory.getStackInSlot(inventorySlot);
        int level = levelFor(stack, inventorySlot, mc.thePlayer, mc);
        if (level == 0) {
            return;
        }
        pushHotbarUnderlayGlState();
        try {
            GlStateManager.translate(slotTopLeftX, slotTopLeftY, 0f);
            GlStateManager.scale(scale, scale, 1f);
            drawSlotOverlay(0, 0, level);
        } finally {
            popHotbarUnderlayGlState();
        }
    }

    /**
     * {@link InventoryHUD} path: call while the GL stack is already translated to the slot’s top-left and scaled by
     * HUD {@code scale} (same space as {@link net.minecraft.client.renderer.entity.RenderItem#renderItemIntoGUI} at 0,0).
     * Avoids double translate/scale from {@link #renderInventoryHudSlotOverlay(int, float, float, float)}.
     */
    public static void renderInventoryHudSlotOverlayInCurrentSlotContext(int inventorySlot, EntityPlayer player, Minecraft mc) {
        if (!enabled || player == null || mc == null || inventorySlot < 9 || inventorySlot > 35) {
            return;
        }
        ItemStack stack = player.inventory.getStackInSlot(inventorySlot);
        int level = levelFor(stack, inventorySlot, player, mc);
        if (level == 0) {
            return;
        }
        pushHotbarUnderlayGlState();
        try {
            drawSlotOverlay(0, 0, level);
        } finally {
            popHotbarUnderlayGlState();
        }
    }

    /**
     * Same coordinate contract as {@link #renderInventoryHudSlotOverlayInCurrentSlotContext} for ShareInv snapshots.
     */
    public static void renderRemoteInventoryHudSlotOverlayInCurrentSlotContext(ItemStack[] remoteLike, int slotIndex, Minecraft mc) {
        if (!enabled || remoteLike == null || slotIndex < 0 || slotIndex >= remoteLike.length) {
            return;
        }
        if (mc == null || mc.thePlayer == null) {
            return;
        }
        ItemStack stack = remoteLike[slotIndex];
        int level = levelForRemote(stack, slotIndex, remoteLike, mc);
        if (level == 0) {
            return;
        }
        pushHotbarUnderlayGlState();
        try {
            drawSlotOverlay(0, 0, level);
        } finally {
            popHotbarUnderlayGlState();
        }
    }

    /** ShareInv / remote preview: same slot indexing as {@link net.minecraft.entity.player.EntityPlayer#inventory}. */
    public static void renderRemoteInventoryHudSlotOverlay(ItemStack[] remoteLike, int slotIndex, float slotTopLeftX,
            float slotTopLeftY, float scale) {
        if (!enabled || remoteLike == null || slotIndex < 0 || slotIndex >= remoteLike.length) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return;
        }
        ItemStack stack = remoteLike[slotIndex];
        int level = levelForRemote(stack, slotIndex, remoteLike, mc);
        if (level == 0) {
            return;
        }
        pushHotbarUnderlayGlState();
        try {
            GlStateManager.translate(slotTopLeftX, slotTopLeftY, 0f);
            GlStateManager.scale(scale, scale, 1f);
            drawSlotOverlay(0, 0, level);
        } finally {
            popHotbarUnderlayGlState();
        }
    }

    public static void renderSlotOverlayIfNeeded(Slot slot, int guiOriginX, int guiOriginY) {
        if (!enabled) {
            return;
        }
        if (BestArmorDisplay.shouldExcludeCurrentContainer()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || slot == null) {
            return;
        }
        if (!isBlitzUselessOverlayContext(mc)) {
            return;
        }
        if (!(slot.inventory instanceof InventoryPlayer)) {
            return;
        }
        ItemStack stack = slot.getStack();
        int slotIndex = slot.getSlotIndex();
        int level = levelFor(stack, slotIndex, mc.thePlayer, mc);
        if (level == 0) {
            return;
        }
        int itemX = guiOriginX + slot.xDisplayPosition + 1;
        int itemY = guiOriginY + slot.yDisplayPosition + 1;
        drawSlotOverlay(itemX - 1, itemY - 1, level);
    }

    /**
     * In {@link GuiChest}, tints container slots: blue = worth picking (would not be useless in your inv),
     * yellow = skip (would be useless). Player inventory slots are unchanged.
     */
    public static void renderChestPickHintIfNeeded(Slot slot, int guiOriginX, int guiOriginY) {
        boolean debugForce = chestPickHintDebugForce;
        if ((!enabled || !chestPickHintsEnabled) && !debugForce || slot == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return;
        }
        if (!(mc.currentScreen instanceof GuiChest)) {
            return;
        }
        if (BestArmorDisplay.shouldExcludeCurrentContainer()) {
            return;
        }
        if (slot.inventory instanceof InventoryPlayer) {
            return;
        }
        ItemStack stack = slot.getStack();
        if (stack == null || stack.getItem() == null) {
            return;
        }
        if (bestArmorOwnsChestSlot(slot, mc)) {
            return;
        }
        if (mc != null && shouldSuppressInventoryQolOverlaysForUseless(mc)
                && !ConfigManager.isUselessItemOverlayForceEverywhere() && !debugForce) {
            return;
        }
        if (!debugForce && !isBlitzUselessOverlayContext(mc)) {
            return;
        }
        int hint = levelForChestPickHint(stack, mc, mc.thePlayer, null, debugForce, slot);
        if (hint == 0) {
            return;
        }
        int itemX = guiOriginX + slot.xDisplayPosition + 1;
        int itemY = guiOriginY + slot.yDisplayPosition + 1;
        drawChestPickHintOverlay(itemX - 1, itemY - 1, hint);
    }

    /** A selected armor upgrade keeps its BestArmor green/tie color instead of receiving a second slot tint. */
    private static boolean bestArmorOwnsChestSlot(Slot slot, Minecraft mc) {
        if (!BestArmorDisplay.isOverlayActiveInContext(mc)) return false;
        BestArmorDisplay.computeBestArmorUnderlayIntentCached(slot.getStack(), mc.thePlayer.inventory,
                slot, CHEST_PICK_ARMOR_INTENT);
        return CHEST_PICK_ARMOR_INTENT.draw;
    }

    private static void drawChestPickHintOverlay(int x, int y, int hintKind) {
        int fill = hintKind == 1 ? chestHintPickFillArgb : chestHintSkipFillArgb;
        int edge = hintKind == 1 ? chestHintPickEdgeArgb : chestHintSkipEdgeArgb;
        InventoryQolOverlay.drawRect(x + 1, y + 1, x + 15, y + 15, fill);
        InventoryQolOverlay.drawRect(x, y, x + 16, y + 1, edge);
        InventoryQolOverlay.drawRect(x, y + 15, x + 16, y + 16, edge);
        InventoryQolOverlay.drawRect(x, y + 1, x + 1, y + 15, edge);
        InventoryQolOverlay.drawRect(x + 15, y + 1, x + 16, y + 15, edge);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * @return 0 = no overlay, 1 = pick (blue), 2 = skip (yellow)
     */
    private static int levelForChestPickHint(ItemStack chestStack, Minecraft mc, EntityPlayer player) {
        return levelForChestPickHint(chestStack, mc, player, null, false, null);
    }

    private static int levelForChestPickHint(ItemStack chestStack, Minecraft mc, EntityPlayer player, DecisionTrace trace) {
        return levelForChestPickHint(chestStack, mc, player, trace, false, null);
    }

    private static int levelForChestPickHint(ItemStack chestStack, Minecraft mc, EntityPlayer player,
            DecisionTrace trace, boolean debugForce, Slot chestSlot) {
        if (chestStack == null || player == null || player.inventory == null) {
            return decision(trace, 0, "empty_or_no_inventory");
        }
        if (isDuplicateOfEquippedArmor(chestStack, player.inventory)) {
            return decision(trace, 2, "duplicate_of_equipped_armor");
        }
        if (isMeleeCandidateDominatedByChestOption(chestStack, mc, player, chestSlot)) {
            return decision(trace, 2, "better_damage_fire_melee_available_in_chest");
        }
        int simSlot = findSimulatedMainInvSlotForPickup(player.inventory, chestStack);
        if (trace != null) trace.simulatedSlot = simSlot;
        if (simSlot < 0) {
            return decision(trace, 0, "inventory_full_no_pickup_simulation");
        }
        if (BestArmorDisplay.isWearableArmorPiece(chestStack)) {
            // The optimizer's duplicate keeper is an actual inventory/chest reference. A simulated copy cannot
            // be that keeper, and a free pickup slot is not the armor's real inventory slot.
            boolean keeper = BestArmorDisplay.isBestArmorDuplicateKeeperStack(chestStack, player.inventory);
            return decision(trace, keeper ? 1 : 2,
                    keeper ? "best_armor_chest_keeper" : "armor_not_best_or_surplus_duplicate");
        }
        fillChestPickSimViewShallow(player.inventory, chestStack, simSlot);
        try {
            ItemStack simStack = CHEST_PICK_SIM_VIEW[simSlot];
            if (simStack == null) {
                return decision(trace, 0, "pickup_simulation_empty");
            }
            int useless = evaluate(simStack, simSlot, CHEST_PICK_SIM_VIEW, mc, player.inventory,
                    trace, debugForce, true);
            return useless > 0 ? 2 : 1;
        } finally {
            CHEST_PICK_SIM_VIEW[simSlot] = player.inventory.getStackInSlot(simSlot);
        }
    }

    /**
     * Each chest candidate is otherwise simulated against the carried inventory alone. Compare it with the other
     * visible chest weapons too, so the best available Fire Aspect/damage option can suppress inferior choices.
     */
    private static boolean isMeleeCandidateDominatedByChestOption(ItemStack candidate, Minecraft mc,
            EntityPlayer player, Slot candidateChestSlot) {
        if (candidate == null || candidate.getItem() == null || player == null || player.inventory == null
                || mc == null || !(mc.currentScreen instanceof GuiChest) || player.openContainer == null
                || candidateChestSlot == null) {
            return false;
        }
        long tick = mc.theWorld == null ? Long.MIN_VALUE : mc.theWorld.getTotalWorldTime();
        if (chestMeleeCacheContainer != player.openContainer || chestMeleeCacheTick != tick) {
            rebuildChestMeleeCache(player);
            chestMeleeCacheContainer = player.openContainer;
            chestMeleeCacheTick = tick;
        }
        for (ChestMeleeCacheEntry entry : chestMeleeCacheEntries) {
            if (entry.slot == candidateChestSlot) {
                return entry.dominated;
            }
        }
        return false;
    }

    private static void rebuildChestMeleeCache(EntityPlayer player) {
        List<ChestMeleeCacheEntry> entries = new ArrayList<>();
        for (Slot slot : player.openContainer.inventorySlots) {
            if (slot == null || slot.inventory instanceof InventoryPlayer) {
                continue;
            }
            ItemStack stack = slot.getStack();
            if (stack == null || stack.getItem() == null) {
                continue;
            }
            boolean sword = stack.getItem() instanceof ItemSword;
            boolean axe = stack.getItem() instanceof ItemAxe || stack.getItem() instanceof ItemPickaxe;
            if (sword || axe) {
                entries.add(new ChestMeleeCacheEntry(slot, stack, sword, axe));
            }
        }
        for (ChestMeleeCacheEntry candidate : entries) {
            for (ChestMeleeCacheEntry other : entries) {
                if (candidate != other && chestMeleeOptionDominates(candidate, other)) {
                    candidate.dominated = true;
                    break;
                }
            }
        }
        chestMeleeCacheEntries = entries;
    }

    private static boolean chestMeleeOptionDominates(ChestMeleeCacheEntry candidate, ChestMeleeCacheEntry other) {
        if (candidate.sword && !other.sword && !other.pickaxe || candidate.axe && !other.sword && !other.axe) {
            return false;
        }
        return UselessItemPolicy.shouldHighlightMeleeWeaponAsDominated(
                candidate.damage, candidate.fire, candidate.knockback, candidate.axe, candidate.remainingUses,
                other.damage, other.fire, other.knockback, other.sword, other.remainingUses);
    }

    private static final class ChestMeleeCacheEntry {
        final Slot slot;
        final boolean sword;
        final boolean axe;
        final boolean pickaxe;
        final double damage;
        final int fire;
        final int knockback;
        final int remainingUses;
        boolean dominated;

        ChestMeleeCacheEntry(Slot slot, ItemStack stack, boolean sword, boolean axe) {
            this.slot = slot;
            this.sword = sword;
            this.axe = axe;
            this.pickaxe = stack.getItem() instanceof ItemPickaxe;
            this.damage = meleeAttackDamage(stack);
            this.fire = EnchantmentHelper.getEnchantmentLevel(Enchantment.fireAspect.effectId, stack);
            this.knockback = EnchantmentHelper.getEnchantmentLevel(Enchantment.knockback.effectId, stack);
            this.remainingUses = meleeRemainingUses(stack);
        }
    }

    private static boolean isDuplicateOfEquippedArmor(ItemStack stack, InventoryPlayer inv) {
        if (stack == null || inv == null || !BestArmorDisplay.isWearableArmorPiece(stack)) {
            return false;
        }
        try {
            int armorSlot = BestArmorDisplay.getArmorSlotForItem(stack);
            if (armorSlot < 1 || armorSlot > 4) {
                return false;
            }
            ItemStack equipped = inv.armorInventory[armorSlot - 1];
            return equipped != null && BestArmorDisplay.isArmorLogicallyEquivalent(equipped, stack);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int findSimulatedMainInvSlotForPickup(InventoryPlayer inv, ItemStack toAdd) {
        if (inv == null || toAdd == null) {
            return -1;
        }
        ItemStack addCopy = toAdd.copy();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s != null && s.isItemEqual(addCopy) && ItemStack.areItemStackTagsEqual(s, addCopy)) {
                int space = s.getMaxStackSize() - s.stackSize;
                if (space > 0) {
                    return i;
                }
            }
        }
        for (int i = 0; i < 36; i++) {
            if (inv.getStackInSlot(i) == null) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Fills {@link #CHEST_PICK_SIM_VIEW} with live inventory references (read-only for {@link #evaluate}) and
     * simulates picking {@code chestStack} into {@code simSlot} using at most one extra stack copy.
     */
    private static void fillChestPickSimViewShallow(InventoryPlayer inv, ItemStack chestStack, int simSlot) {
        for (int i = 0; i < 40; i++) {
            CHEST_PICK_SIM_VIEW[i] = inv.getStackInSlot(i);
        }
        ItemStack add = chestStack;
        ItemStack cur = CHEST_PICK_SIM_VIEW[simSlot];
        if (cur != null && cur.isItemEqual(add) && ItemStack.areItemStackTagsEqual(cur, add)) {
            ItemStack merged = cur.copy();
            int max = merged.getMaxStackSize();
            merged.stackSize = Math.min(max, merged.stackSize + add.stackSize);
            CHEST_PICK_SIM_VIEW[simSlot] = merged;
        } else {
            CHEST_PICK_SIM_VIEW[simSlot] = add.copy();
        }
    }

    /** {@code true} if at least {@code minLostFraction} of max durability is consumed ({@code itemDamage / maxDamage}). */
    private static boolean armorHasLostAtLeastDurabilityFraction(ItemStack stack, double minLostFraction) {
        if (stack == null || !(stack.getItem() instanceof ItemArmor)) {
            return false;
        }
        int maxD = stack.getMaxDamage();
        if (maxD <= 0) {
            return false;
        }
        return (double) stack.getItemDamage() / (double) maxD >= minLostFraction - 1e-9;
    }

    private static void drawSlotOverlay(int x, int y, int level) {
        int fill = level >= 2 ? DARK_FILL : LIGHT_FILL;
        int edge = level >= 2 ? DARK_EDGE : LIGHT_EDGE;
        InventoryQolOverlay.drawRect(x + 1, y + 1, x + 15, y + 15, fill);
        InventoryQolOverlay.drawRect(x, y, x + 16, y + 1, edge);
        InventoryQolOverlay.drawRect(x, y + 15, x + 16, y + 16, edge);
        InventoryQolOverlay.drawRect(x, y + 1, x + 1, y + 15, edge);
        InventoryQolOverlay.drawRect(x + 15, y + 1, x + 16, y + 15, edge);
        // Gui.drawRect leaves its ARGB color active. A translucent red color can tint the carried stack or the next
        // textured GUI draw when this helper runs from a slot-HEAD hook.
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static int levelFor(ItemStack stack, int slotIndex, EntityPlayer player, Minecraft mc) {
        return levelFor(stack, slotIndex, player, mc, null);
    }

    private static int levelFor(ItemStack stack, int slotIndex, EntityPlayer player, Minecraft mc, DecisionTrace trace) {
        if (stack == null || stack.getItem() == null) {
            return decision(trace, 0, "empty");
        }
        if (shouldSkipForBestArmor(stack, slotIndex, player.inventory)) {
            return decision(trace, 0, "protected_by_best_armor_policy");
        }
        BestArmorDisplay.prepareInventoryQolCachesForFrame(mc);
        ItemStack[] view = BestArmorDisplay.getPreparedPlayerInventoryView40();
        if (mc.thePlayer == null || player.inventory != mc.thePlayer.inventory) {
            view = new ItemStack[40];
            for (int i = 0; i < 40; i++) {
                view[i] = player.inventory.getStackInSlot(i);
            }
        }
        return evaluate(stack, slotIndex, view, mc, player.inventory, trace);
    }

    private static int levelForRemote(ItemStack stack, int slotIndex, ItemStack[] remote, Minecraft mc) {
        if (stack == null || stack.getItem() == null) {
            return 0;
        }
        if (!isBlitzUselessOverlayContext(mc)) {
            return 0;
        }
        ItemStack[] view = new ItemStack[40];
        System.arraycopy(remote, 0, view, 0, Math.min(40, remote.length));
        return evaluate(stack, slotIndex, view, mc, null);
    }

    /** Vanilla player GUI: {@link InventoryPlayer#getStackInSlot} 36–39 = boots → helmet. */
    private static boolean isArmorEquipSlotIndex(int slotIndex, int armorSlot) {
        if (slotIndex < 36 || slotIndex > 39 || armorSlot < 1 || armorSlot > 4) {
            return false;
        }
        return slotIndex == 36 + (armorSlot - 1);
    }

    /**
     * Durability-keeper sits in main/hotbar but you're wearing a logically identical, more-damaged copy — still run
     * useless-item evaluation on the bag stack (e.g. toss spare) even though {@link BestArmorDisplay#isSlotBetterArmor}
     * is true for that slot.
     */
    private static boolean isFresherBagDuplicateTwinWhileWearingMoreDamaged(ItemStack stack, int slotIndex,
            InventoryPlayer inv) {
        if (inv == null || stack == null || slotIndex < 0 || slotIndex >= 36) {
            return false;
        }
        if (!(stack.getItem() instanceof ItemArmor) || !BestArmorDisplay.isBestArmorDuplicateKeeperStack(stack, inv)) {
            return false;
        }
        ItemArmor ia = (ItemArmor) stack.getItem();
        int armorSlot = 4 - ia.armorType;
        ItemStack equipped = inv.armorInventory[armorSlot - 1];
        if (equipped == null || !BestArmorDisplay.isArmorLogicallyEquivalent(equipped, stack)) {
            return false;
        }
        return equipped.getItemDamage() > stack.getItemDamage();
    }

    private static boolean stackMatchesBestSetPiece(ItemStack desired, ItemStack stack) {
        if (desired == null || stack == null) {
            return false;
        }
        return desired.getItem() instanceof ItemArmor && stack.getItem() instanceof ItemArmor
                ? BestArmorDisplay.isArmorLogicallyEquivalent(desired, stack)
                : areItemStacksEquivalentForOverlay(desired, stack);
    }

    private static boolean shouldSkipForBestArmor(ItemStack stack, int slotIndex, InventoryPlayer inv) {
        if (!BestArmorDisplay.isEnabled() || inv == null) {
            return false;
        }
        if (!BestArmorDisplay.isWearableArmorPiece(stack)) {
            return false;
        }
        if (BestArmorDisplay.isSlotBetterArmor(inv, slotIndex)) {
            if (!isFresherBagDuplicateTwinWhileWearingMoreDamaged(stack, slotIndex, inv)) {
                return true;
            }
        }
        try {
            int armorSlot = BestArmorDisplay.getArmorSlotForItem(stack);
            if (armorSlot < 1 || armorSlot > 4) {
                return false;
            }
            ItemStack equipped = inv.armorInventory[armorSlot - 1];
            boolean renderingArmorEquipColumn = isArmorEquipSlotIndex(slotIndex, armorSlot);
            ItemStack[] best = BestArmorDisplay.getBestMixedSet(inv);
            if (best != null) {
                ItemStack desired = best[armorSlot - 1];
                // Match BestArmor identity: for armor use logical equivalence (durability ignored), not strict overlay
                // equality — otherwise chest duplicates with different damage read as "must pick" (blue).
                if (desired != null) {
                    boolean sameAsDesired = stackMatchesBestSetPiece(desired, stack);
                    if (sameAsDesired) {
                        // Match green overlay: only the durability-keeper (most durability left; then slot tie-break)
                        // suppresses useless red; surplus logical duplicates may still tint red.
                        if (!BestArmorDisplay.isBestArmorDuplicateKeeperStack(stack, inv)) {
                            // Worn copy is often not the keeper when a fresher duplicate sits in the bag — still do not
                            // tint the equipped piece useless until it has lost at least 40% max durability (then both
                            // swap hint and throw-away hints can apply).
                            if (equipped != null
                                    && (equipped == stack || renderingArmorEquipColumn)
                                    && BestArmorDisplay.isArmorLogicallyEquivalent(equipped, stack)
                                    && !armorHasLostAtLeastDurabilityFraction(equipped,
                                            WORN_ARMOR_DUPLICATE_RED_MIN_LOST_FRACTION)) {
                                return true;
                            }
                            return false;
                        }
                        if (equipped == null || !BestArmorDisplay.isArmorLogicallyEquivalent(equipped, stack)) {
                            return true;
                        }
                        // Same piece as best and also worn: fall through to durability gate below.
                    }
                }
            }
            // Wearing the same armor identity: while worn is still healthy, skip useless on most inv duplicates —
            // except the durability-keeper in main/hotbar (fresher spare you may still want to trash).
            if (equipped != null && BestArmorDisplay.isArmorLogicallyEquivalent(equipped, stack)) {
                if (!armorHasLostAtLeastDurabilityFraction(equipped, WORN_ARMOR_DUPLICATE_RED_MIN_LOST_FRACTION)) {
                    boolean keeperInMainInv = BestArmorDisplay.isBestArmorDuplicateKeeperStack(stack, inv)
                            && slotIndex >= 0 && slotIndex < 36;
                    if (!keeperInMainInv) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * When BestArmor overlay is off, still tint surplus / non-loadout gear red (mirrors {@link #shouldSkipForBestArmor}).
     */
    private static boolean isSurplusOrNonBestWearableArmor(ItemStack stack, int slotIndex, InventoryPlayer inv) {
        if (stack == null || inv == null || !BestArmorDisplay.isWearableArmorPiece(stack)) {
            return false;
        }
        try {
            int armorSlot = BestArmorDisplay.getArmorSlotForItem(stack);
            if (armorSlot < 1 || armorSlot > 4) {
                return false;
            }
            ItemStack[] best = BestArmorDisplay.getBestMixedSet(inv);
            if (best == null) {
                return false;
            }
            ItemStack desired = best[armorSlot - 1];
            if (desired == null) {
                return false;
            }
            if (!stackMatchesBestSetPiece(desired, stack)) {
                return true;
            }
            ItemStack equipped = inv.armorInventory[armorSlot - 1];
            if (!BestArmorDisplay.isBestArmorDuplicateKeeperStack(stack, inv)) {
                boolean renderingArmorEquipColumn = isArmorEquipSlotIndex(slotIndex, armorSlot);
                if (equipped != null
                        && (equipped == stack || renderingArmorEquipColumn)
                        && BestArmorDisplay.isArmorLogicallyEquivalent(equipped, stack)
                        && !armorHasLostAtLeastDurabilityFraction(equipped,
                                WORN_ARMOR_DUPLICATE_RED_MIN_LOST_FRACTION)) {
                    return false;
                }
                return true;
            }
            if (equipped != null && BestArmorDisplay.isArmorLogicallyEquivalent(equipped, stack)) {
                if (!armorHasLostAtLeastDurabilityFraction(equipped, WORN_ARMOR_DUPLICATE_RED_MIN_LOST_FRACTION)) {
                    boolean keeperInMainInv = BestArmorDisplay.isBestArmorDuplicateKeeperStack(stack, inv)
                            && slotIndex >= 0 && slotIndex < 36;
                    if (!keeperInMainInv) {
                        return false;
                    }
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean areItemStacksEquivalentForOverlay(ItemStack a, ItemStack b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a.getItem() != b.getItem()) {
            return false;
        }
        if (a.getItemDamage() != b.getItemDamage()) {
            return false;
        }
        // Includes enchantments and any custom NBT-based properties relevant to armor.
        return ItemStack.areItemStackTagsEqual(a, b);
    }

    private static boolean shouldSuppressInventoryQolOverlaysForUseless(Minecraft mc) {
        return invGuiEvalSnapshotActive ? invGuiSnapSuppressInvQol : GameContext.shouldSuppressInventoryQolOverlays(mc);
    }

    private static boolean isBlitzDeathmatchPhaseForUseless(Minecraft mc) {
        return invGuiEvalSnapshotActive ? invGuiSnapDeathmatchPhase : GameContext.isBlitzDeathmatchPhase(mc);
    }

    private static boolean isBlitzGulchMapForUseless(Minecraft mc) {
        return invGuiEvalSnapshotActive ? invGuiSnapGulchMap : GameContext.isBlitzGulchMap(mc);
    }

    private static boolean isBlitzCitadelMapForUseless(Minecraft mc) {
        return invGuiEvalSnapshotActive ? invGuiSnapCitadelMap : GameContext.isBlitzCitadelMap(mc);
    }

    private static boolean isBlitzTrollPreKitPendingWitchEggForUseless(Minecraft mc) {
        return invGuiEvalSnapshotActive ? invGuiSnapTrollPreKitPendingWitchEgg : GameContext.isBlitzTrollPreKitPendingWitchEgg(mc);
    }

    private static boolean isBlitzUselessOverlayContext(Minecraft mc) {
        if (mc == null || mc.theWorld == null || mc.thePlayer == null || !GameContext.isOnHypixel(mc)) {
            return false;
        }
        if (ConfigManager.isUselessItemOverlayForceEverywhere()) {
            return true;
        }
        return invGuiEvalSnapshotActive ? invGuiSnapBlitzTabSortCtx : GameContext.isBlitzTabListCustomSortContext(mc);
    }

    private static int evaluate(ItemStack stack, int slotIndex, ItemStack[] v40, Minecraft mc, InventoryPlayer armorProbe) {
        return evaluate(stack, slotIndex, v40, mc, armorProbe, null);
    }

    /** The normal policy, with an optional explanation collected only by an armed debug session. */
    private static int evaluate(ItemStack stack, int slotIndex, ItemStack[] v40, Minecraft mc,
            InventoryPlayer armorProbe, DecisionTrace trace) {
        return evaluate(stack, slotIndex, v40, mc, armorProbe, trace, false);
    }

    /** The final flag bypasses only overlay context gates for the explicit chest-hint debug command. */
    private static int evaluate(ItemStack stack, int slotIndex, ItemStack[] v40, Minecraft mc,
            InventoryPlayer armorProbe, DecisionTrace trace, boolean debugForce) {
        return evaluate(stack, slotIndex, v40, mc, armorProbe, trace, debugForce, false);
    }

    /** The final flags distinguish the chest candidate simulation from the carried-inventory policy. */
    private static int evaluate(ItemStack stack, int slotIndex, ItemStack[] v40, Minecraft mc,
            InventoryPlayer armorProbe, DecisionTrace trace, boolean debugForce, boolean chestPickSimulation) {
        if (mc != null && shouldSuppressInventoryQolOverlaysForUseless(mc)
                && !ConfigManager.isUselessItemOverlayForceEverywhere() && !debugForce) {
            return decision(trace, 0, "inventory_qol_suppressed");
        }
        if (!debugForce && !isBlitzUselessOverlayContext(mc)) {
            return decision(trace, 0, "outside_useless_overlay_context");
        }
        boolean dm = isBlitzDeathmatchPhaseForUseless(mc);
        boolean gulch = isBlitzGulchMapForUseless(mc);

        if (stack.getItem() == Items.wheat) {
            boolean otherFood = hasOtherFoodOption(v40);
            boolean mountKit = KillCounter.isLocalPlayerHorsetamerOrDonkeytamerKit();
            boolean hasBridgeBlock = hasUsableBridgeBlock(v40);
            boolean extraChestWheat = chestPickSimulation && armorProbe != null
                    && UselessItemPolicy.shouldHighlightExtraChestWheat(
                            ConfigManager.isUselessAlwaysRedWheat(), countItem(Items.wheat, armorProbe));
            if (hasBridgeBlock) {
                return decision(trace, 1, "wheat_with_existing_bridge_block");
            }
            if (extraChestWheat) {
                return decision(trace, 1, "extra_chest_wheat_after_hay_bale_threshold");
            }
            boolean red = UselessItemPolicy.shouldHighlightWheat(
                    ConfigManager.isUselessAlwaysRedWheat(), otherFood, mountKit, false);
            if (red) {
                return decision(trace, 1, otherFood ? "wheat_without_horse_or_donkey" : "wheat_only_food_option");
            }
            return decision(trace, 0, "wheat_kept_for_food_or_mount");
        }

        if (isSharpnessFish(stack)) {
            boolean anotherFood = hasOtherFoodOption(v40);
            boolean red = UselessItemPolicy.shouldHighlightSharpnessFish(
                    ConfigManager.isUselessAlwaysRedSharpnessFish(), anotherFood);
            return decision(trace, red ? 1 : 0,
                    red ? "sharpness_fish_with_other_food" : "sharpness_fish_only_food_or_disabled");
        }

        if (stack.getItem() == Items.apple
                && chestPickSimulation && armorProbe != null
                && UselessItemPolicy.shouldHighlightExtraChestApple(
                        ConfigManager.isUselessAlwaysRedApple(), countItem(Items.apple, armorProbe))) {
            return decision(trace, 1, "extra_chest_apple_after_threshold");
        }

        if (stack.getItem() == Items.stick && !stack.isItemEnchanted()
                && chestPickSimulation && armorProbe != null
                && UselessItemPolicy.shouldHighlightExtraChestStick(
                        ConfigManager.isUselessAlwaysRedStick(),
                        countUnenchantedItem(Items.stick, armorProbe))) {
            return decision(trace, 1, "extra_chest_stick_after_threshold");
        }

        if (isNeverThrowCategoryD(stack)) {
            return decision(trace, 0, "protected_never_throw_category");
        }

        if (isOptionalConfiguredAlwaysRed(stack)) {
            return decision(trace, 1, "configured_always_red");
        }

        int selectedHotbarSlot = (mc != null && mc.thePlayer != null)
                ? mc.thePlayer.inventory.currentItem
                : -1;

        // Pre-deathmatch warning: if we are too far from a crafting table when Hypixel's deathmatch is
        // about to start, crafting ingredients like sticks / iron / diamonds become effectively unusable.
        if (!dm && !gulch && armorProbe != null
                && isDeathmatchCraftIngredient(stack)
                && shouldRedCraftIngredientsDueToUpcomingDeathmatchDistance(mc)
                && !isMountTamerKeepRedExempt(stack, mc)) {
            return decision(trace, 1, "crafting_table_unreachable_before_deathmatch");
        }

        if (isCategoryBTrash(stack)) {
            return decision(trace, 1, "trash_category");
        }

        if (stack.getItem() == Items.experience_bottle && (dm || isBlitzCitadelMapForUseless(mc))) {
            return decision(trace, 1, "experience_bottle_in_deathmatch_or_citadel");
        }

        if (isCategoryARefinement(stack, dm, gulch, v40)) {
            return decision(trace, 1, "crafting_refinement_rule");
        }

        if (stack.getItem() == Items.bow) {
            return decision(trace, isSurplusDuplicateBow(stack, slotIndex, v40, selectedHotbarSlot,
                    chestPickSimulation) ? 1 : 0,
                    "bow_duplicate_ranking");
        }

        if (stack.getItem() == Items.fishing_rod) {
            return decision(trace, isSurplusExtraFishingRod(stack, slotIndex, v40, selectedHotbarSlot) ? 1 : 0,
                    "fishing_rod_surplus_ranking");
        }

        if (stack.getItem() == Items.ghast_tear) {
            return decision(trace, isSurplusDuplicateGhastTear(stack, slotIndex, v40, selectedHotbarSlot) ? 1 : 0,
                    "ghast_tear_duplicate_ranking");
        }

        int food = foodTier(stack);
        if (food >= 0) {
            if (isMountTamerKeepRedExempt(stack, mc)) {
                return decision(trace, 0, "mount_healing_food");
            }
            int beefCount = countItem(Items.beef, v40);
            int porkCount = countItem(Items.porkchop, v40);
            int best;
            if (stack.getItem() == Items.beef && beefCount > 0 && porkCount > 0) {
                if (beefCount < porkCount) {
                    return decision(trace, 1, "less_raw_beef_than_raw_pork");
                }
                best = bestFoodTierPresentExcluding(v40, Items.porkchop);
            } else if (stack.getItem() == Items.porkchop && beefCount > 0 && porkCount > 0) {
                if (porkCount < beefCount) {
                    return decision(trace, 1, "less_raw_pork_than_raw_beef");
                }
                best = bestFoodTierPresentExcluding(v40, Items.beef);
            } else {
                best = bestFoodTierPresent(v40);
            }
            if (best >= 0 && food > best) {
                if (stack.getItem() == Items.apple && !dm && !gulch) {
                    return decision(trace, 0, "apple_kept_before_deathmatch");
                }
                return decision(trace, 1, "better_food_already_available");
            }
        }

        if (stack.getItem() == Items.rotten_flesh && countItem(Items.cooked_beef, v40) >= 32) {
            return decision(trace, 1, "enough_cooked_beef_for_rotten_flesh");
        }

        if (stack.getItem() == Items.string) {
            if (gulch || dm) {
                return decision(trace, 1, "string_in_gulch_or_deathmatch");
            }
            if (hasItem(Items.fishing_rod, v40)) {
                return decision(trace, 1, "string_with_fishing_rod_available");
            }
        }

        if (isSurplusDuplicateMeleeWeapon(stack, slotIndex, v40, chestPickSimulation)) {
            return decision(trace, 1, "surplus_duplicate_melee_weapon");
        }

        // Informal utility melee ("O") is fire-aspect / knockback melee. We keep the item if it is the
        // best for at least one of its utility enchant levels, and shade red only if it is dominated for
        // every utility it actually has (fixes "highest KB/fire aspect" expectations).
        if (isInformalCategoryOUtilityMelee(stack)) {
            return decision(trace, isInformalCategoryOUtilityMeleeAllDominated(stack, v40) ? 1 : 0,
                    "fire_aspect_knockback_utility_ranking");
        }

        if (isDominatedMeleeWithPickaxe(stack, v40)) {
            return decision(trace, 1, "pickaxe_melee_damage_and_utility_ranking");
        }

        if (!(stack.getItem() instanceof ItemPickaxe) && isStrictlyWorseMeleeWeapon(stack, v40)) {
            return decision(trace, applyCrossTierSharpMeleePreference(stack, v40, 1),
                    "weaker_melee_with_configured_sharpness_preference");
        }

        if (isInvisPotionUseless(stack, v40, mc)) {
            return decision(trace, 1, "invisibility_potion_rule");
        }

        if (isSaddleUseless(stack, v40, dm)) {
            return decision(trace, 1, "saddle_rule");
        }

        int blockPrio = blockJPriority(stack);
        if (blockPrio >= 0) {
            int bestP = bestPlaceableBlockPriority(v40);
            if (bestP >= 0 && blockPrio > bestP) {
                return decision(trace, 1, "preferred_placeable_block_available");
            }
        }

        if (stack.getItem() == Items.leather && shouldRedLeather(v40)) {
            return decision(trace, 1, "leather_crafting_rule");
        }

        // Armor / prot skulls: {@link #levelFor} already skipped when {@link #shouldSkipForBestArmor} (avoids double
        // {@link BestArmorDisplay#isSlotBetterArmor} per slot). Remote path uses {@code armorProbe == null} and never hits this.
        if (armorProbe != null && BestArmorDisplay.isWearableArmorPiece(stack)) {
            if (BestArmorDisplay.isEnabled()) {
                return decision(trace, 1, "armor_not_protected_by_best_armor");
            }
            return decision(trace, isSurplusOrNonBestWearableArmor(stack, slotIndex, armorProbe) ? 1 : 0,
                    "armor_surplus_or_non_best_ranking");
        }

        // Default behavior: keep items neutral unless explicitly matched by a red/throwable rule.
        return decision(trace, 0, "no_useless_rule_matched");
    }

    private static final class DecisionTrace {
        String reason = "not_evaluated";
        int simulatedSlot = -1;
    }

    private static int decision(DecisionTrace trace, int level, String reason) {
        if (trace != null) trace.reason = reason;
        return level;
    }

    /** Recomputes the same numeric policy without rendering; no trace allocation in ordinary renders. */
    static String explainSlotForDebug(Slot slot, Minecraft mc) {
        if (slot == null || mc == null || mc.thePlayer == null) return "NONE reason=no_player_or_slot";
        ItemStack stack = slot.getStack();
        boolean chest = !(slot.inventory instanceof InventoryPlayer);
        boolean debugForce = chestPickHintDebugForce && chest && mc.currentScreen instanceof GuiChest;
        String kind = chest ? "chestHint=" : "useless=";
        if (stack == null || stack.getItem() == null) return kind + "NONE reason=empty";
        if (!enabled && !debugForce) return kind + "NONE reason=module_disabled";
        if (BestArmorDisplay.shouldExcludeCurrentContainer()) return kind + "NONE reason=excluded_container";
        if (chest && (!(mc.currentScreen instanceof GuiChest) || !chestPickHintsEnabled && !debugForce)) {
            return kind + "NONE reason=chest_hints_disabled_or_not_chest";
        }
        if (!debugForce && !isBlitzUselessOverlayContext(mc)) return kind + "NONE reason=outside_useless_overlay_context";
        if (shouldSuppressInventoryQolOverlaysForUseless(mc) && !ConfigManager.isUselessItemOverlayForceEverywhere()
                && !debugForce) {
            return kind + "NONE reason=inventory_qol_suppressed";
        }
        if (chest && bestArmorOwnsChestSlot(slot, mc)) {
            return kind + "NONE reason=best_armor_highlight_owns_slot";
        }
        DecisionTrace trace = new DecisionTrace();
        int level = chest ? levelForChestPickHint(stack, mc, mc.thePlayer, trace, debugForce, slot)
                : levelFor(stack, slot.getSlotIndex(), mc.thePlayer, mc, trace);
        String label = chest ? (level == 1 ? "TAKE" : level == 2 ? "SKIP" : "NONE")
                : (level > 0 ? "RED" : "KEEP");
        return kind + label + " level=" + level + " reason=" + trace.reason
                + (chest ? " simulatedInventorySlot=" + trace.simulatedSlot : "");
    }

    static String contextForDebug(Minecraft mc) {
        return "onHypixel=" + GameContext.isOnHypixel(mc)
                + " chestHintDebugForce=" + chestPickHintDebugForce
                + " active=" + isBlitzUselessOverlayContext(mc)
                + " suppressed=" + shouldSuppressInventoryQolOverlaysForUseless(mc)
                + " deathmatch=" + isBlitzDeathmatchPhaseForUseless(mc)
                + " gulch=" + isBlitzGulchMapForUseless(mc) + " citadel=" + isBlitzCitadelMapForUseless(mc)
                + " trollPreKit=" + isBlitzTrollPreKitPendingWitchEggForUseless(mc)
                + " mountHealingKit=" + KillCounter.isLocalPlayerHorsetamerOrDonkeytamerKit()
                + " craftingWarning=" + shouldRedCraftIngredientsDueToUpcomingDeathmatchDistance(mc);
    }

    /**
     * Horsetamer / Donkeytamer: apples heal mounts. Wheat has its own food / mount / hay-bale rule above.
     */
    private static boolean isMountTamerKeepRedExempt(ItemStack stack, Minecraft mc) {
        if (stack == null) {
            return false;
        }
        if (!KillCounter.isLocalPlayerHorsetamerOrDonkeytamerKit()) {
            return false;
        }
        Item i = stack.getItem();
        return i == Items.apple;
    }

    private static boolean isDeathmatchCraftIngredient(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return false;
        }
        Item i = stack.getItem();
        Minecraft mc = Minecraft.getMinecraft();
        if (isMountTamerKeepRedExempt(stack, mc)) {
            return false;
        }
        if (i == Items.diamond || i == Items.iron_ingot || i == Items.gold_ingot || i == Items.gold_nugget) {
            return true;
        }
        // Crafting ingredients: unenchanted sticks.
        return i == Items.stick && !stack.isItemEnchanted();
    }

    private static boolean shouldRedCraftIngredientsDueToUpcomingDeathmatchDistance(Minecraft mc) {
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            return false;
        }
        int secondsLeft = getCachedBlitzDeathmatchCountdownSeconds(mc);
        if (secondsLeft <= 0) {
            return false;
        }
        if (secondsLeft > DEATHMATCH_CRAFT_WARNING_WINDOW_SECONDS) {
            return false;
        }

        double distSq = getCachedNearestCraftingTableDistanceSq(mc, mc.thePlayer);
        if (Double.isNaN(distSq)) {
            // Can't find a crafting table nearby: assume effectively out of reach for this countdown window.
            return true;
        }

        double dist = Math.sqrt(distSq);
        double timeToReach = dist / WALK_SPEED_BLOCKS_PER_SECOND;

        // If we can't reach and open the crafting table UI before deathmatch starts, mark ingredients red early.
        return timeToReach > (double) secondsLeft - CRAFT_ACTION_BUFFER_SECONDS;
    }

    private static int getCachedBlitzDeathmatchCountdownSeconds(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (now - lastDeathmatchCountdownCacheAt < CACHED_DEATHMATCH_SECONDS_INTERVAL_MS) {
            return cachedDeathmatchCountdownSeconds;
        }
        lastDeathmatchCountdownCacheAt = now;
        cachedDeathmatchCountdownSeconds = -1;

        try {
            Scoreboard sb = mc.theWorld == null ? null : mc.theWorld.getScoreboard();
            if (sb == null) {
                return -1;
            }
            ScoreObjective obj = sb.getObjectiveInDisplaySlot(1);
            if (obj == null) {
                return -1;
            }
            for (Score score : sb.getSortedScores(obj)) {
                ScorePlayerTeam team = sb.getPlayersTeam(score.getPlayerName());
                String raw = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
                if (raw == null) {
                    continue;
                }

                // Keep consistent with GameContext: remove § formatting codes, then lowercase.
                String clean = SECTION_FORMAT_PATTERN.matcher(raw).replaceAll("").toLowerCase(Locale.ROOT);
                Matcher m = DEATHMATCH_COUNTDOWN_PATTERN.matcher(clean);
                if (m.find()) {
                    int mins = Integer.parseInt(m.group(1));
                    int secs = Integer.parseInt(m.group(2));
                    cachedDeathmatchCountdownSeconds = mins * 60 + secs;
                    return cachedDeathmatchCountdownSeconds;
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static double getCachedNearestCraftingTableDistanceSq(Minecraft mc, EntityPlayer player) {
        long now = System.currentTimeMillis();
        double movedDx = player.posX - cachedCraftTablePlayerX;
        double movedDy = player.posY - cachedCraftTablePlayerY;
        double movedDz = player.posZ - cachedCraftTablePlayerZ;
        double movedSq = movedDx * movedDx + movedDy * movedDy + movedDz * movedDz;
        if (hasCachedNearestCraftTableDist
                && cachedCraftTableWorld == mc.theWorld
                && movedSq <= CRAFT_TABLE_CACHE_MOVE_RESCAN_SQ
                && now - lastNearestCraftTableDistCacheAt < CACHED_NEAREST_CRAFT_TABLE_DIST_INTERVAL_MS) {
            return cachedNearestCraftTableDistSq;
        }
        lastNearestCraftTableDistCacheAt = now;
        hasCachedNearestCraftTableDist = true;
        cachedCraftTableWorld = mc.theWorld;
        cachedCraftTablePlayerX = player.posX;
        cachedCraftTablePlayerY = player.posY;
        cachedCraftTablePlayerZ = player.posZ;
        cachedNearestCraftTableDistSq = Double.NaN;

        try {
            int px = (int) Math.floor(player.posX);
            int py = (int) Math.floor(player.posY);
            int pz = (int) Math.floor(player.posZ);

            int r = CRAFT_TABLE_SCAN_RADIUS_BLOCKS;
            int yMin = py - CRAFT_TABLE_SCAN_Y_RADIUS;
            int yMax = py + CRAFT_TABLE_SCAN_Y_RADIUS;
            double bestSq = Double.NaN;
            BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

            for (int x = px - r; x <= px + r; x++) {
                for (int z = pz - r; z <= pz + r; z++) {
                    // Cheap pruning by horizontal distance to reduce average work.
                    double dxh = (x + 0.5) - player.posX;
                    double dzh = (z + 0.5) - player.posZ;
                    double horizSq = dxh * dxh + dzh * dzh;
                    if (!Double.isNaN(bestSq) && horizSq > bestSq) {
                        continue;
                    }
                    for (int y = yMin; y <= yMax; y++) {
                        mutablePos.set(x, y, z);
                        if (mc.theWorld.getBlockState(mutablePos).getBlock() != Blocks.crafting_table) {
                            continue;
                        }
                        double dy = (y + 0.5) - player.posY;
                        double dsq = horizSq + dy * dy;
                        if (Double.isNaN(bestSq) || dsq < bestSq) {
                            bestSq = dsq;
                        }
                        if (bestSq <= 0.0) {
                            break;
                        }
                    }
                }
            }
            cachedNearestCraftTableDistSq = bestSq;
        } catch (Throwable ignored) {
        }

        return cachedNearestCraftTableDistSq;
    }

    private static boolean isNeverThrowCategoryD(ItemStack s) {
        Item i = s.getItem();
        if (i == Items.compass || i == Items.arrow || i == Items.golden_apple || i == Items.egg
                || i == Items.fire_charge || i == Items.snowball || i == Items.flint_and_steel || i == Items.boat) {
            return true;
        }
        if (i == Items.cake && !ConfigManager.isUselessAlwaysRedCake()) {
            return true;
        }
        if (i == Items.tnt_minecart) {
            return true;
        }
        if (i == Items.minecart && !ConfigManager.isUselessAlwaysRedMinecart()) {
            return true;
        }
        if (i == Items.chest_minecart && !ConfigManager.isUselessAlwaysRedChestMinecart()) {
            return true;
        }
        if (i == Items.spawn_egg) {
            return true;
        }
        if (i == Item.getItemFromBlock(Blocks.rail) && !ConfigManager.isUselessAlwaysRedRail()) {
            return true;
        }
        if (i == Items.milk_bucket) {
            return true;
        }
        if (i == Items.reeds) {
            return true;
        }
        return false;
    }

    private static boolean isOptionalConfiguredAlwaysRed(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return false;
        }
        Item i = stack.getItem();
        if (i == Items.bowl && ConfigManager.isUselessAlwaysRedBowl()) {
            return true;
        }
        if (i == Items.cake && ConfigManager.isUselessAlwaysRedCake()) {
            return true;
        }
        if (i == Items.chest_minecart && ConfigManager.isUselessAlwaysRedChestMinecart()) {
            return true;
        }
        if (i == Items.minecart && ConfigManager.isUselessAlwaysRedMinecart()) {
            return true;
        }
        if (i == Item.getItemFromBlock(Blocks.rail) && ConfigManager.isUselessAlwaysRedRail()) {
            return true;
        }
        if (i == Item.getItemFromBlock(Blocks.red_flower) && ConfigManager.isUselessAlwaysRedRose()) {
            return true;
        }
        return false;
    }

    private static int sharpnessLevel(ItemStack s) {
        if (s == null) {
            return 0;
        }
        return EnchantmentHelper.getEnchantmentLevel(Enchantment.sharpness.effectId, s);
    }

    private static boolean ironPlainSword(ItemStack s) {
        return s != null && s.getItem() == Items.iron_sword && sharpnessLevel(s) == 0;
    }

    private static boolean stoneSharpSword(ItemStack s) {
        return s != null && s.getItem() == Items.stone_sword && sharpnessLevel(s) >= 1;
    }

    private static boolean diamondPlainSword(ItemStack s) {
        return s != null && s.getItem() == Items.diamond_sword && sharpnessLevel(s) == 0;
    }

    private static boolean ironSharpSword(ItemStack s) {
        return s != null && s.getItem() == Items.iron_sword && sharpnessLevel(s) >= 1;
    }

    private static boolean stonePlainSword(ItemStack s) {
        return s != null && s.getItem() == Items.stone_sword && sharpnessLevel(s) == 0;
    }

    private static boolean goldOrWoodSharpSword(ItemStack s) {
        if (s == null) {
            return false;
        }
        Item it = s.getItem();
        if (!(it == Items.golden_sword || it == Items.wooden_sword)) {
            return false;
        }
        return sharpnessLevel(s) >= 1;
    }

    private static boolean invHasIronPlainSword(ItemStack[] v40) {
        for (ItemStack s : v40) {
            if (ironPlainSword(s)) {
                return true;
            }
        }
        return false;
    }

    private static boolean invHasStoneSharpSword(ItemStack[] v40) {
        for (ItemStack s : v40) {
            if (stoneSharpSword(s)) {
                return true;
            }
        }
        return false;
    }

    private static boolean invHasDiamondPlainSword(ItemStack[] v40) {
        for (ItemStack s : v40) {
            if (diamondPlainSword(s)) {
                return true;
            }
        }
        return false;
    }

    private static boolean invHasIronSharpSword(ItemStack[] v40) {
        for (ItemStack s : v40) {
            if (ironSharpSword(s)) {
                return true;
            }
        }
        return false;
    }

    private static boolean invHasStonePlainSword(ItemStack[] v40) {
        for (ItemStack s : v40) {
            if (stonePlainSword(s)) {
                return true;
            }
        }
        return false;
    }

    private static boolean invHasGoldWoodSharpSword(ItemStack[] v40) {
        for (ItemStack s : v40) {
            if (goldOrWoodSharpSword(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * When two swords disagree (material vs Sharp I), config picks which stack gets the useless tint.
     */
    private static int applyCrossTierSharpMeleePreference(ItemStack stack, ItemStack[] v40, int strictLevel) {
        if (stack == null || !(stack.getItem() instanceof ItemSword) || v40 == null) {
            return strictLevel;
        }
        if (invHasIronPlainSword(v40) && invHasStoneSharpSword(v40)) {
            boolean preferIronRed = "IRON".equals(ConfigManager.getUselessMeleeIronVsStoneSharpRed());
            if (ironPlainSword(stack)) {
                return preferIronRed ? 1 : 0;
            }
            if (stoneSharpSword(stack)) {
                return preferIronRed ? 0 : strictLevel;
            }
        }
        if (invHasDiamondPlainSword(v40) && invHasIronSharpSword(v40)) {
            boolean preferDiamondRed = "DIAMOND".equals(ConfigManager.getUselessMeleeDiamondVsIronSharpRed());
            if (diamondPlainSword(stack)) {
                return preferDiamondRed ? 1 : 0;
            }
            if (ironSharpSword(stack)) {
                return preferDiamondRed ? 0 : strictLevel;
            }
        }
        if (invHasStonePlainSword(v40) && invHasGoldWoodSharpSword(v40)) {
            boolean preferStoneRed = "STONE".equals(ConfigManager.getUselessMeleeStoneVsGoldWoodSharpRed());
            if (stonePlainSword(stack)) {
                return preferStoneRed ? 1 : 0;
            }
            if (goldOrWoodSharpSword(stack)) {
                return preferStoneRed ? 0 : strictLevel;
            }
        }
        return strictLevel;
    }

    private static boolean isCategoryBTrash(ItemStack s) {
        Item i = s.getItem();
        if (i == Items.skull) {
            return !s.isItemEnchanted();
        }
        if (i == Items.bowl || i == Items.mushroom_stew || i == Items.glass_bottle || i == Items.sugar || i == Items.spider_eye
                || i == Items.glowstone_dust || i == Items.redstone || i == Items.gunpowder
                || i == Items.enchanted_book || i == Items.name_tag
                || i == itemFromBlock(Blocks.waterlily)) {
            return true;
        }
        if (i == Items.bone && !s.isItemEnchanted()) {
            return true;
        }
        if (i == Item.getItemFromBlock(Blocks.golden_rail) || i == Item.getItemFromBlock(Blocks.redstone_torch)) {
            return true;
        }
        if (i == Items.potionitem && isWaterBottle(s)) {
            return true;
        }
        if (i == Items.fish && s.getMetadata() == 3) {
            return true;
        }
        if (i instanceof ItemRecord) {
            return true;
        }
        if (i == itemFromBlock(Blocks.tripwire_hook)) {
            return true;
        }
        if (i == Items.dye && s.getMetadata() == 0) {
            return true;
        }
        return false;
    }

    private static boolean isWaterBottle(ItemStack s) {
        if (s == null || s.getItem() != Items.potionitem) {
            return false;
        }
        try {
            for (PotionEffect e : Items.potionitem.getEffects(s)) {
                if (e != null) {
                    return false;
                }
            }
        } catch (Throwable ignored) {
        }
        String name = s.getDisplayName();
        if (name != null) {
            String l = name.toLowerCase(Locale.ROOT);
            if (l.contains("water") && !l.contains("fire")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCategoryARefinement(ItemStack stack, boolean dm, boolean gulch, ItemStack[] v40) {
        Item i = stack.getItem();
        if (!(dm || gulch)) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (isMountTamerKeepRedExempt(stack, mc)) {
            return false;
        }
        if (i == Items.diamond || i == Items.gold_ingot || i == Items.gold_nugget || i == Items.iron_ingot) {
            return true;
        }
        if (i == Items.stick && !stack.isItemEnchanted()) {
            return true;
        }
        if (i == Items.apple) {
            int ft = foodTier(stack);
            int best = bestFoodTierPresent(v40);
            return ft >= 0 && best >= 0 && ft > best;
        }
        return false;
    }

    private static int foodTier(ItemStack s) {
        if (s == null) {
            return -1;
        }
        // Sharpness (etc.) on raw fish is a weapon spoof, not lowest-tier food.
        if (s.getItem() == Items.fish && s.isItemEnchanted()) {
            return -1;
        }
        for (int t = 0; t < FOOD_PRIORITY.length; t++) {
            if (FOOD_PRIORITY[t].matches(s)) {
                return t;
            }
        }
        return -1;
    }

    private static boolean isSharpnessFish(ItemStack stack) {
        return stack != null && stack.getItem() == Items.fish && sharpnessLevel(stack) >= 1;
    }

    private static boolean hasOtherFoodOption(ItemStack[] inventory) {
        if (inventory == null) {
            return false;
        }
        for (ItemStack candidate : inventory) {
            if (candidate == null || candidate.getItem() == null) {
                continue;
            }
            // Enchanted raw fish are handled as weapon-like items, not as edible backup food.
            if (candidate.getItem() == Items.fish && candidate.isItemEnchanted()) {
                continue;
            }
            if (candidate.getItem() instanceof ItemFood) {
                return true;
            }
        }
        return false;
    }

    private static int bestFoodTierPresent(ItemStack[] v40) {
        int best = -1;
        for (ItemStack s : v40) {
            int t = foodTier(s);
            if (t >= 0 && (best < 0 || t < best)) {
                best = t;
            }
        }
        return best;
    }

    /**
     * Same as {@link #bestFoodTierPresent} but ignores stacks of {@code excludeItem} (see raw beef vs raw pork count rule).
     */
    private static int bestFoodTierPresentExcluding(ItemStack[] v40, Item excludeItem) {
        int best = -1;
        for (ItemStack s : v40) {
            if (s == null || s.getItem() == excludeItem) {
                continue;
            }
            int t = foodTier(s);
            if (t >= 0 && (best < 0 || t < best)) {
                best = t;
            }
        }
        return best;
    }

    private static int countItem(Item item, ItemStack[] v40) {
        int c = 0;
        for (ItemStack s : v40) {
            if (s != null && s.getItem() == item) {
                c += s.stackSize;
            }
        }
        return c;
    }

    private static int countItem(Item item, InventoryPlayer inventory) {
        if (inventory == null) {
            return 0;
        }
        int count = 0;
        for (int slot = 0; slot < 40; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack != null && stack.getItem() == item) {
                count += stack.stackSize;
            }
        }
        return count;
    }

    private static int countUnenchantedItem(Item item, InventoryPlayer inventory) {
        if (inventory == null) {
            return 0;
        }
        int count = 0;
        for (int slot = 0; slot < 40; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack != null && stack.getItem() == item && !stack.isItemEnchanted()) {
                count += stack.stackSize;
            }
        }
        return count;
    }

    private static boolean hasItem(Item item, ItemStack[] v40) {
        for (ItemStack s : v40) {
            if (s != null && s.getItem() == item) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInvisPotionUseless(ItemStack stack, ItemStack[] v40, Minecraft mc) {
        if (stack == null || !(stack.getItem() instanceof ItemPotion)) {
            return false;
        }
        if (!hasInvisibility(stack)) {
            return false;
        }
        if (countPotionEffectsFromStack(stack) >= 2) {
            return false;
        }
        if (isBlitzTrollPreKitPendingWitchEggForUseless(mc)) {
            return false;
        }
        return !hasWitchEgg(v40);
    }

    /** Non-null effects from NBT; splash pots can have multiple (e.g. invis + speed). */
    private static int countPotionEffectsFromStack(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof ItemPotion)) {
            return 0;
        }
        int c = 0;
        try {
            for (PotionEffect e : Items.potionitem.getEffects(stack)) {
                if (e != null) {
                    c++;
                }
            }
        } catch (Throwable ignored) {
        }
        return c;
    }

    private static boolean hasInvisibility(ItemStack stack) {
        try {
            for (PotionEffect e : Items.potionitem.getEffects(stack)) {
                if (e != null && e.getPotionID() == Potion.invisibility.id) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        String n = stack.getDisplayName();
        return n != null && n.toLowerCase(Locale.ROOT).contains("invis");
    }

    private static boolean hasWitchEgg(ItemStack[] v40) {
        for (ItemStack s : v40) {
            if (s != null && s.getItem() == Items.spawn_egg) {
                String n = s.getDisplayName();
                if (n != null && n.toLowerCase(Locale.ROOT).contains("witch")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isSaddleUseless(ItemStack stack, ItemStack[] v40, boolean dm) {
        if (stack == null || stack.getItem() != Items.saddle || !dm) {
            return false;
        }
        return !(horseOrDonkeyEggPresent(v40));
    }

    private static boolean horseOrDonkeyEggPresent(ItemStack[] v40) {
        for (ItemStack s : v40) {
            if (s == null || s.getItem() != Items.spawn_egg) {
                continue;
            }
            String n = s.getDisplayName();
            if (n == null) {
                continue;
            }
            String l = n.toLowerCase(Locale.ROOT);
            if (l.contains("horse") || l.contains("donkey")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Informal O category: utility melee = sword/axe with Fire Aspect and/or Knockback.
     */
    private static boolean isInformalCategoryOUtilityMelee(ItemStack stack) {
        return isInformalCategoryOFireMelee(stack) || isInformalCategoryOKnockMelee(stack);
    }

    /**
     * Shade red only when {@code stack} is dominated for every utility enchant it has
     * (Fire Aspect and/or Knockback).
     *
     * A utility weapon is only dominated by another option with at least as much weapon damage and the same
     * utility. Fire Aspect is treated as a required utility, so a stronger plain weapon cannot make the only
     * fire weapon useless. Swords still win equal-damage ties against axes because they can block.
     */
    private static boolean isInformalCategoryOUtilityMeleeAllDominated(ItemStack stack, ItemStack[] v40) {
        if (stack == null || v40 == null) {
            return false;
        }
        boolean fire = isInformalCategoryOFireMelee(stack);
        boolean knock = isInformalCategoryOKnockMelee(stack);
        if (!fire && !knock) {
            return false;
        }
        boolean fireDominated = fire && hasDominatingFireUtilityMelee(stack, v40);
        boolean knockDominated = knock && hasDominatingKnockUtilityMelee(stack, v40);
        return (fire ? fireDominated : true) && (knock ? knockDominated : true);
    }

    private static boolean hasDominatingFireUtilityMelee(ItemStack stack, ItemStack[] options) {
        if (stack == null || options == null) return false;
        int candidateFire = EnchantmentHelper.getEnchantmentLevel(Enchantment.fireAspect.effectId, stack);
        if (candidateFire <= 0) return false;
        double candidateDamage = meleeAttackDamage(stack);
        boolean candidateAxe = stack.getItem() instanceof ItemAxe || stack.getItem() instanceof ItemPickaxe;
        for (ItemStack other : options) {
            if (other == null || other == stack || other.getItem() == null
                    || !isCombatMelee(other.getItem())) {
                continue;
            }
            int otherFire = EnchantmentHelper.getEnchantmentLevel(Enchantment.fireAspect.effectId, other);
            if (otherFire < candidateFire) continue;
            if (UselessItemPolicy.shouldHighlightMeleeWeaponAsDominated(candidateDamage, candidateFire,
                    0, candidateAxe, meleeRemainingUses(stack), meleeAttackDamage(other), otherFire, 0,
                    other.getItem() instanceof ItemSword, meleeRemainingUses(other))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDominatingKnockUtilityMelee(ItemStack stack, ItemStack[] options) {
        if (stack == null || options == null) return false;
        int candidateKnockback = EnchantmentHelper.getEnchantmentLevel(Enchantment.knockback.effectId, stack);
        if (candidateKnockback <= 0) return false;
        double candidateDamage = meleeAttackDamage(stack);
        boolean candidateAxe = stack.getItem() instanceof ItemAxe || stack.getItem() instanceof ItemPickaxe;
        for (ItemStack other : options) {
            if (other == null || other == stack || other.getItem() == null
                    || !isCombatMelee(other.getItem())) {
                continue;
            }
            int otherKnockback = EnchantmentHelper.getEnchantmentLevel(Enchantment.knockback.effectId, other);
            if (otherKnockback < candidateKnockback) continue;
            if (UselessItemPolicy.shouldHighlightMeleeWeaponAsDominated(candidateDamage, 0,
                    candidateKnockback, candidateAxe, meleeRemainingUses(stack), meleeAttackDamage(other), 0,
                    otherKnockback, other.getItem() instanceof ItemSword, meleeRemainingUses(other))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Informal duplicate bow removal:
     * - if you have multiple bows, keep only one and shade the rest red
     * - keep the currently selected hotbar bow when it is among the best ties
     */
    private static boolean isSurplusDuplicateBow(ItemStack stack, int slotIndex, ItemStack[] v40,
            int selectedHotbarSlot, boolean chestPickSimulation) {
        if (stack == null || v40 == null || slotIndex < 0 || slotIndex >= v40.length) {
            return false;
        }
        if (stack.getItem() != Items.bow) {
            return false;
        }

        int myScore = bowUsefulnessScore(stack);
        if (chestPickSimulation) {
            for (int i = 0; i < v40.length; i++) {
                ItemStack owned = v40[i];
                if (i == slotIndex || owned == null || owned.getItem() != Items.bow) {
                    continue;
                }
                if (UselessItemPolicy.shouldHighlightChestBow(myScore, bowUsefulnessScore(owned))) {
                    return true;
                }
            }
            return false;
        }

        int bestScore = Integer.MIN_VALUE;
        // Track which slots qualify for "best" so we can keep hotbar-selected in ties.
        int keeperSelectedHotbar = -1;
        int keeperAny = -1;

        for (int i = 0; i < v40.length; i++) {
            ItemStack o = v40[i];
            if (o == null || o.getItem() != Items.bow) {
                continue;
            }
            int s = bowUsefulnessScore(o);
            if (s > bestScore) {
                bestScore = s;
                keeperAny = i;
                keeperSelectedHotbar = (i == selectedHotbarSlot) ? i : -1;
            } else if (s == bestScore) {
                // Tie: prefer the currently selected hotbar slot.
                if (selectedHotbarSlot >= 0 && i == selectedHotbarSlot) {
                    keeperSelectedHotbar = i;
                }
                // Otherwise keep the earlier slot as a stable fallback.
                if (keeperAny < 0 || i < keeperAny) {
                    keeperAny = i;
                }
            }
        }

        if (bestScore == Integer.MIN_VALUE) {
            return false;
        }

        int keeper = keeperSelectedHotbar >= 0 ? keeperSelectedHotbar : keeperAny;
        if (keeper < 0) {
            return false;
        }

        return myScore < bestScore || (myScore == bestScore && slotIndex != keeper);
    }

    /**
     * Ghast tears as Blitz melee stand-ins: keep the best {@link #weaponCombatEnchantScore} (sharpness-weighted);
     * extras or strictly worse copies tint red. Sticks/bones stay out of this path on purpose.
     */
    private static boolean isSurplusDuplicateGhastTear(ItemStack stack, int slotIndex, ItemStack[] v40,
            int selectedHotbarSlot) {
        if (stack == null || v40 == null || slotIndex < 0 || slotIndex >= v40.length) {
            return false;
        }
        if (stack.getItem() != Items.ghast_tear) {
            return false;
        }

        int myScore = weaponCombatEnchantScore(stack);
        int bestScore = Integer.MIN_VALUE;
        int keeperSelectedHotbar = -1;
        int keeperAny = -1;

        for (int i = 0; i < v40.length; i++) {
            ItemStack o = v40[i];
            if (o == null || o.getItem() != Items.ghast_tear) {
                continue;
            }
            int s = weaponCombatEnchantScore(o);
            if (s > bestScore) {
                bestScore = s;
                keeperAny = i;
                keeperSelectedHotbar = (i == selectedHotbarSlot) ? i : -1;
            } else if (s == bestScore) {
                if (selectedHotbarSlot >= 0 && i == selectedHotbarSlot) {
                    keeperSelectedHotbar = i;
                }
                if (keeperAny < 0 || i < keeperAny) {
                    keeperAny = i;
                }
            }
        }

        if (bestScore == Integer.MIN_VALUE) {
            return false;
        }

        int keeper = keeperSelectedHotbar >= 0 ? keeperSelectedHotbar : keeperAny;
        if (keeper < 0) {
            return false;
        }

        return myScore < bestScore || (myScore == bestScore && slotIndex != keeper);
    }

    private static int bowCombatEnchantScore(ItemStack s) {
        if (s == null) {
            return 0;
        }
        int power = EnchantmentHelper.getEnchantmentLevel(Enchantment.power.effectId, s);
        int punch = EnchantmentHelper.getEnchantmentLevel(Enchantment.punch.effectId, s);
        int flame = EnchantmentHelper.getEnchantmentLevel(Enchantment.flame.effectId, s);
        int infinity = EnchantmentHelper.getEnchantmentLevel(Enchantment.infinity.effectId, s);
        // Weighted: Power dominates. Punch + Flame + Infinity are additive bonuses.
        return power * 100 + punch * 25 + flame * 30 + infinity * 20;
    }

    private static int bowUsefulnessScore(ItemStack stack) {
        if (stack == null) {
            return 0;
        }
        int maxUses = stack.getMaxDamage();
        int remainingUses = maxUses > 0 ? maxUses - stack.getItemDamage() : 0;
        return UselessItemPolicy.bowUsefulnessScore(bowCombatEnchantScore(stack), remainingUses, maxUses);
    }

    /**
     * Fishing rods: keep exactly 1 essential rod; mark extras red.
     * Prefer the selected hotbar rod as the keeper so you never lose the one you are holding.
     */
    private static boolean isSurplusExtraFishingRod(ItemStack stack, int slotIndex, ItemStack[] v40, int selectedHotbarSlot) {
        if (stack == null || v40 == null || slotIndex < 0 || slotIndex >= v40.length) {
            return false;
        }
        if (stack.getItem() != Items.fishing_rod) {
            return false;
        }

        // Choose keeper: prefer selected hotbar rod if it exists among rods.
        int keeperSelectedHotbar = -1;
        int keeperBest = -1;
        int bestScore = Integer.MIN_VALUE;

        for (int i = 0; i < v40.length; i++) {
            ItemStack o = v40[i];
            if (o == null || o.getItem() != Items.fishing_rod) {
                continue;
            }
            int score = fishingRodCombatEnchantScore(o);
            if (keeperSelectedHotbar < 0 && selectedHotbarSlot >= 0 && i == selectedHotbarSlot) {
                keeperSelectedHotbar = i;
            }
            if (score > bestScore) {
                bestScore = score;
                keeperBest = i;
            } else if (score == bestScore && keeperBest >= 0 && i < keeperBest) {
                // stable tie-break
                keeperBest = i;
            }
        }

        int keeper = keeperSelectedHotbar >= 0 ? keeperSelectedHotbar : keeperBest;
        if (keeper < 0) {
            return false;
        }

        return slotIndex != keeper;
    }

    private static int fishingRodCombatEnchantScore(ItemStack s) {
        if (s == null) {
            return 0;
        }
        int lure = EnchantmentHelper.getEnchantmentLevel(Enchantment.lure.effectId, s);
        int unbreaking = EnchantmentHelper.getEnchantmentLevel(Enchantment.unbreaking.effectId, s);
        // Weighted for "best rod" sense. (Luck-of-the-sea constant name differs across mappings,
        // so we only rank by Lure + Unbreaking for reliability.)
        return lure * 100 + unbreaking * 10;
    }

    private static boolean hasStrictlyBetterFireUtilityMeleeSameShape(ItemStack stack, ItemStack[] v40) {
        if (stack == null || v40 == null) {
            return false;
        }
        Item myItem = stack.getItem();
        boolean sword = myItem instanceof ItemSword;
        boolean axe = myItem instanceof ItemAxe;
        if (!sword && !axe) {
            return false;
        }
        int myTier = meleeMaterialTier(myItem);
        int myFire = EnchantmentHelper.getEnchantmentLevel(Enchantment.fireAspect.effectId, stack);
        if (myTier < 0 || myFire <= 0) {
            return false;
        }
        for (ItemStack o : v40) {
            if (o == null || o.getItem() == null) {
                continue;
            }
            if (sword && !(o.getItem() instanceof ItemSword)) {
                continue;
            }
            if (axe && !(o.getItem() instanceof ItemAxe)) {
                continue;
            }
            int otherTier = meleeMaterialTier(o.getItem());
            if (otherTier < 0) {
                continue;
            }
            int otherFire = EnchantmentHelper.getEnchantmentLevel(Enchantment.fireAspect.effectId, o);
            if (otherFire <= 0) {
                continue;
            }
            if (otherFire > myFire) {
                return true;
            }
            if (otherFire == myFire && otherTier > myTier) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasStrictlyBetterKnockUtilityMeleeSameShape(ItemStack stack, ItemStack[] v40) {
        if (stack == null || v40 == null) {
            return false;
        }
        Item myItem = stack.getItem();
        boolean sword = myItem instanceof ItemSword;
        boolean axe = myItem instanceof ItemAxe;
        if (!sword && !axe) {
            return false;
        }
        int myTier = meleeMaterialTier(myItem);
        int myKb = EnchantmentHelper.getEnchantmentLevel(Enchantment.knockback.effectId, stack);
        if (myTier < 0 || myKb <= 0) {
            return false;
        }
        for (ItemStack o : v40) {
            if (o == null || o.getItem() == null) {
                continue;
            }
            if (sword && !(o.getItem() instanceof ItemSword)) {
                continue;
            }
            if (axe && !(o.getItem() instanceof ItemAxe)) {
                continue;
            }
            int otherTier = meleeMaterialTier(o.getItem());
            if (otherTier < 0) {
                continue;
            }
            int otherKb = EnchantmentHelper.getEnchantmentLevel(Enchantment.knockback.effectId, o);
            if (otherKb <= 0) {
                continue;
            }
            if (otherKb > myKb) {
                return true;
            }
            if (otherKb == myKb && otherTier > myTier) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code O-fire} / {@code O-knock}: keep utility melee at the current best material tier for sword vs sword or axe vs axe.
     */
    private static boolean isInformalCategoryOUtilityMeleeProtected(ItemStack stack, ItemStack[] v40) {
        if (stack == null) {
            return false;
        }
        boolean fire = isInformalCategoryOFireMelee(stack);
        boolean knock = isInformalCategoryOKnockMelee(stack);
        if (!fire && !knock) {
            return false;
        }
        return !hasStrictlyHigherMaterialMeleeSameShape(stack, v40);
    }

    private static boolean isInformalCategoryOFireMelee(ItemStack s) {
        if (s == null) {
            return false;
        }
        Item i = s.getItem();
        if (!isCombatMelee(i)) {
            return false;
        }
        return EnchantmentHelper.getEnchantmentLevel(Enchantment.fireAspect.effectId, s) > 0;
    }

    private static boolean isInformalCategoryOKnockMelee(ItemStack s) {
        if (s == null) {
            return false;
        }
        Item i = s.getItem();
        if (!isCombatMelee(i)) {
            return false;
        }
        return EnchantmentHelper.getEnchantmentLevel(Enchantment.knockback.effectId, s) > 0;
    }

    /** {@code true} if another sword (resp. axe, hoe) in {@code v40} uses a strictly higher {@link #meleeMaterialTier}. */
    private static boolean hasStrictlyHigherMaterialMeleeSameShape(ItemStack stack, ItemStack[] v40) {
        if (stack == null || v40 == null) {
            return false;
        }
        Item item = stack.getItem();
        int shape = blitzMeleeShapeOrdinal(item);
        if (shape < 0) {
            return false;
        }
        int myTier = meleeMaterialTier(item);
        if (myTier < 0) {
            return false;
        }
        for (ItemStack o : v40) {
            if (o == null || o.getItem() == null) {
                continue;
            }
            if (blitzMeleeShapeOrdinal(o.getItem()) != shape) {
                continue;
            }
            int t = meleeMaterialTier(o.getItem());
            if (t > myTier) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code 0}=sword, {@code 1}=axe, {@code 2}=hoe. Sticks/bones are intentionally excluded (situational smite/bane).
     */
    private static int blitzMeleeShapeOrdinal(Item item) {
        if (item instanceof ItemPickaxe) return 3;
        if (item instanceof ItemSword) {
            return 0;
        }
        if (item instanceof ItemAxe) {
            return 1;
        }
        if (item instanceof ItemHoe) {
            return 2;
        }
        return -1;
    }

    /**
     * Vanilla Blitz melee tiers: wood and gold swords/axes/hoes share the same base damage (tier 0), then stone, iron, diamond.
     */
    private static int meleeMaterialTier(Item item) {
        if (item == null) {
            return -1;
        }
        if (item == Items.wooden_sword || item == Items.golden_sword
                || item == Items.wooden_axe || item == Items.golden_axe
                || item == Items.wooden_hoe || item == Items.golden_hoe
                || item == Items.wooden_pickaxe || item == Items.golden_pickaxe) {
            return 0;
        }
        if (item == Items.stone_sword || item == Items.stone_axe || item == Items.stone_hoe || item == Items.stone_pickaxe) {
            return 1;
        }
        if (item == Items.iron_sword || item == Items.iron_axe || item == Items.iron_hoe || item == Items.iron_pickaxe) {
            return 2;
        }
        if (item == Items.diamond_sword || item == Items.diamond_axe || item == Items.diamond_hoe || item == Items.diamond_pickaxe) {
            return 3;
        }
        return -1;
    }

    /**
     * Same-tier sword/axe with identical combat-enchant score: mark all but one as useless.
     * Keeper tie-break: with no combat enchants (sharp/smite/bane/kb/fire), prefer the copy with less Unbreaking so a
     * clean sword can still be rolled at an enchanting table; with combat enchants, prefer more Unbreaking (durability).
     * Slot index is the final tie-break (hotbar / selected slot still preferred when equally good).
     */
    private static boolean isSurplusDuplicateMeleeWeapon(ItemStack stack, int slotIndex, ItemStack[] v40,
            boolean chestPickSimulation) {
        if (stack == null || v40 == null || slotIndex < 0 || slotIndex >= v40.length) {
            return false;
        }
        Item item = stack.getItem();
        int shape = blitzMeleeShapeOrdinal(item);
        if (shape < 0) {
            return false;
        }
        int myTier = meleeMaterialTier(item);
        if (myTier < 0) {
            return false;
        }
        int myScore = weaponCombatEnchantScore(stack);
        if (chestPickSimulation) {
            int candidateUnbreaking = EnchantmentHelper.getEnchantmentLevel(Enchantment.unbreaking.effectId, stack);
            for (int i = 0; i < v40.length; i++) {
                if (i == slotIndex) {
                    continue;
                }
                ItemStack owned = v40[i];
                if (owned == null || owned.getItem() == null) {
                    continue;
                }
                int ownedShape = blitzMeleeShapeOrdinal(owned.getItem());
                int ownedTier = meleeMaterialTier(owned.getItem());
                int ownedScore = weaponCombatEnchantScore(owned);
                int ownedUnbreaking = EnchantmentHelper.getEnchantmentLevel(Enchantment.unbreaking.effectId, owned);
                if (shape == 3 && ownedShape == shape && ownedTier == myTier && ownedScore == myScore
                        && candidateUnbreaking == ownedUnbreaking && meleeRemainingUses(stack) > meleeRemainingUses(owned)) continue;
                if (UselessItemPolicy.shouldHighlightChestMeleeDuplicate(
                        ownedShape == shape && ownedTier == myTier && ownedScore == myScore,
                        candidateUnbreaking, ownedUnbreaking, myScore != 0)) {
                    return true;
                }
            }
            return false;
        }

        int keeper = -1;
        for (int i = 0; i < v40.length; i++) {
            ItemStack o = v40[i];
            if (!isSameMeleeSurplusGroup(shape, myTier, myScore, o)) {
                continue;
            }
            if (keeper < 0 || isBetterSurplusMeleeKeeperSlot(o, i, v40[keeper], keeper, myScore)) {
                keeper = i;
            }
        }
        return keeper >= 0 && slotIndex != keeper;
    }

    /**
     * {@code true} if {@code (cand, candSlot)} should be the duplicate-keeper over {@code (best, bestSlot)}.
     * {@code sharedCombatScore} is the group's {@link #weaponCombatEnchantScore} (identical for both stacks).
     */
    private static boolean isBetterSurplusMeleeKeeperSlot(ItemStack cand, int candSlot, ItemStack best, int bestSlot,
            int sharedCombatScore) {
        if (best == null) {
            return true;
        }
        int cUb = EnchantmentHelper.getEnchantmentLevel(Enchantment.unbreaking.effectId, cand);
        int bUb = EnchantmentHelper.getEnchantmentLevel(Enchantment.unbreaking.effectId, best);
        if (cand.getItem() instanceof ItemPickaxe && cUb == bUb && meleeRemainingUses(cand) != meleeRemainingUses(best)) {
            return meleeRemainingUses(cand) > meleeRemainingUses(best);
        }
        if (sharedCombatScore == 0) {
            if (cUb != bUb) {
                return cUb < bUb;
            }
        } else {
            if (cUb != bUb) {
                return cUb > bUb;
            }
        }
        return candSlot < bestSlot;
    }

    private static boolean isSameMeleeSurplusGroup(int shapeOrdinal, int tier, int combatScore, ItemStack o) {
        if (o == null || o.getItem() == null) {
            return false;
        }
        if (blitzMeleeShapeOrdinal(o.getItem()) != shapeOrdinal) {
            return false;
        }
        if (meleeMaterialTier(o.getItem()) != tier) {
            return false;
        }
        return weaponCombatEnchantScore(o) == combatScore;
    }

    /** Weighted sum so sharpness dominates; KB / fire still beat a clean duplicate of the same metal. */
    private static int weaponCombatEnchantScore(ItemStack s) {
        if (s == null || !s.isItemEnchanted()) {
            return 0;
        }
        int sharp = EnchantmentHelper.getEnchantmentLevel(Enchantment.sharpness.effectId, s);
        int smite = EnchantmentHelper.getEnchantmentLevel(Enchantment.smite.effectId, s);
        int bane = EnchantmentHelper.getEnchantmentLevel(Enchantment.baneOfArthropods.effectId, s);
        int kb = EnchantmentHelper.getEnchantmentLevel(Enchantment.knockback.effectId, s);
        int fire = EnchantmentHelper.getEnchantmentLevel(Enchantment.fireAspect.effectId, s);
        return sharp * 100 + Math.max(smite, bane) * 45 + kb * 15 + fire * 50;
    }

    private static boolean isStrictlyWorseMeleeWeapon(ItemStack stack, ItemStack[] v40) {
        if (stack == null) {
            return false;
        }
        Item item = stack.getItem();
        int shape = blitzMeleeShapeOrdinal(item);
        if (shape < 0) {
            return false;
        }
        int myTier = meleeMaterialTier(item);
        if (myTier < 0) {
            return false;
        }
        if (item instanceof ItemAxe) {
            return isAxeWorseThanAnySwordOrAxe(stack, v40);
        }
        int myScore = weaponCombatEnchantScore(stack);
        int bestTier = -1;
        for (ItemStack o : v40) {
            if (o == null || o.getItem() == null) {
                continue;
            }
            if (blitzMeleeShapeOrdinal(o.getItem()) != shape) {
                continue;
            }
            int t = meleeMaterialTier(o.getItem());
            if (t > bestTier) {
                bestTier = t;
            }
        }
        if (bestTier < 0) {
            return false;
        }
        if (myTier < bestTier) {
            return true;
        }
        if (myTier > bestTier) {
            return false;
        }
        int bestScoreAtMyTier = 0;
        for (ItemStack o : v40) {
            if (o == null || o.getItem() == null) {
                continue;
            }
            if (blitzMeleeShapeOrdinal(o.getItem()) != shape) {
                continue;
            }
            if (meleeMaterialTier(o.getItem()) != myTier) {
                continue;
            }
            bestScoreAtMyTier = Math.max(bestScoreAtMyTier, weaponCombatEnchantScore(o));
        }
        return myScore < bestScoreAtMyTier;
    }

    private static boolean isAxeWorseThanAnySwordOrAxe(ItemStack axe, ItemStack[] inventory) {
        double axeDamage = meleeAttackDamage(axe);
        if (inventory == null) {
            return false;
        }
        for (ItemStack other : inventory) {
            if (other == null || other == axe || other.getItem() == null) {
                continue;
            }
            boolean sword = other.getItem() instanceof ItemSword;
            if (!sword && !(other.getItem() instanceof ItemAxe)) {
                continue;
            }
            if (UselessItemPolicy.shouldHighlightAxeAsWorse(
                    axeDamage, meleeAttackDamage(other), sword)) {
                return true;
            }
        }
        return false;
    }

    /** Reads vanilla's attack-damage modifier, then adds Sharpness and applicable creature damage. */
    private static boolean isCombatMelee(Item item) {
        return item instanceof ItemSword || item instanceof ItemAxe || item instanceof ItemPickaxe;
    }

    /** Speleologist tools participate by actual damage, rather than by sword material tiers. */
    private static boolean isDominatedMeleeWithPickaxe(ItemStack candidate, ItemStack[] inventory) {
        if (candidate == null || !isCombatMelee(candidate.getItem()) || inventory == null) return false;
        boolean pickaxe = candidate.getItem() instanceof ItemPickaxe;
        for (ItemStack other : inventory) {
            if (other == null || other == candidate || !isCombatMelee(other.getItem())
                    || !pickaxe && !(other.getItem() instanceof ItemPickaxe)) continue;
            if (UselessItemPolicy.shouldHighlightMeleeWeaponAsDominated(
                    meleeAttackDamage(candidate), EnchantmentHelper.getEnchantmentLevel(Enchantment.fireAspect.effectId, candidate),
                    EnchantmentHelper.getEnchantmentLevel(Enchantment.knockback.effectId, candidate),
                    !(candidate.getItem() instanceof ItemSword), meleeRemainingUses(candidate),
                    meleeAttackDamage(other), EnchantmentHelper.getEnchantmentLevel(Enchantment.fireAspect.effectId, other),
                    EnchantmentHelper.getEnchantmentLevel(Enchantment.knockback.effectId, other),
                    other.getItem() instanceof ItemSword, meleeRemainingUses(other))) return true;
        }
        return false;
    }

    private static double meleeAttackDamage(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return 0.0D;
        }
        double damage = 0.0D;
        try {
            Collection<AttributeModifier> modifiers = stack.getAttributeModifiers()
                    .get(SharedMonsterAttributes.attackDamage.getAttributeUnlocalizedName());
            if (modifiers != null) {
                for (AttributeModifier modifier : modifiers) {
                    if (modifier.getOperation() == 0) {
                        damage += modifier.getAmount();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return damage + EnchantmentHelper.getModifierForCreature(stack, EnumCreatureAttribute.UNDEFINED);
    }

    /** Number of melee swings remaining before the item's durability is exhausted. */
    private static int meleeRemainingUses(ItemStack stack) {
        if (stack == null || stack.getItem() == null || !stack.isItemStackDamageable()) {
            return 0;
        }
        return Math.max(0, stack.getMaxDamage() - stack.getItemDamage());
    }

    private static final Item[] J_BLOCKS = new Item[]{
            Item.getItemFromBlock(Blocks.diamond_block),
            Item.getItemFromBlock(Blocks.iron_block),
            Item.getItemFromBlock(Blocks.gold_block),
            Item.getItemFromBlock(Blocks.dirt),
            Item.getItemFromBlock(Blocks.melon_block),
            Item.getItemFromBlock(Blocks.snow),
            Item.getItemFromBlock(Blocks.hay_block),
            Item.getItemFromBlock(Blocks.chest),
    };

    private static int blockJPriority(ItemStack s) {
        if (s == null) {
            return -1;
        }
        Item i = s.getItem();
        for (int p = 0; p < J_BLOCKS.length; p++) {
            if (J_BLOCKS[p] == i) {
                return p;
            }
        }
        return -1;
    }

    /** A carried placeable block makes wheat unnecessary as a hay-bale bridging block; chests do not count. */
    private static boolean hasUsableBridgeBlock(ItemStack[] inventory) {
        if (inventory == null) {
            return false;
        }
        for (ItemStack stack : inventory) {
            if (stack == null || !(stack.getItem() instanceof ItemBlock)) {
                continue;
            }
            Block block = ((ItemBlock) stack.getItem()).getBlock();
            if (block != null && block != Blocks.chest && block != Blocks.trapped_chest
                    && block != Blocks.ender_chest) {
                return true;
            }
        }
        return false;
    }

    private static int bestPlaceableBlockPriority(ItemStack[] v40) {
        int best = -1;
        for (ItemStack s : v40) {
            int p = blockJPriority(s);
            if (p >= 0 && (best < 0 || p < best)) {
                best = p;
            }
        }
        return best;
    }

    /**
     * Boots=36 .. helmet=39. If a slot is empty and there is no corresponding armor/skull anywhere in the grid,
     * leather might be needed to craft — do not mark leather red.
     */
    private static boolean shouldRedLeather(ItemStack[] v40) {
        if (!hasItem(Items.leather, v40)) {
            return false;
        }
        for (int slot = 36; slot <= 39; slot++) {
            ItemStack worn = v40[slot];
            if (worn != null && worn.getItem() != null) {
                continue;
            }
            int armorType = 39 - slot;
            if (!hasArmorOfTypeInGrid(v40, armorType)) {
                return false;
            }
        }
        return true;
    }

    /** {@code armorType} same as {@link ItemArmor#armorType} (0 helmet .. 3 boots). */
    private static boolean hasArmorOfTypeInGrid(ItemStack[] v40, int armorType) {
        for (ItemStack s : v40) {
            if (s == null || s.getItem() == null) {
                continue;
            }
            if (s.getItem() instanceof ItemArmor) {
                if (((ItemArmor) s.getItem()).armorType == armorType) {
                    return true;
                }
            }
            if (armorType == 0 && s.getItem() instanceof ItemSkull) {
                return true;
            }
        }
        return false;
    }
}
