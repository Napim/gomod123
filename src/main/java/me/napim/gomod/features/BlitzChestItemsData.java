package me.napim.gomod.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Loads bundled {@code /blitz_chest_items.json} (merged from {@code blitz_chest_dump_*.txt} logs)
 * for the Blitz Chest Items browser GUI.
 */
public final class BlitzChestItemsData {

    private static final String RESOURCE_PATH = "/blitz_chest_items.json";

    public static final String CAT_FOOD = "Food";
    public static final String CAT_MATERIALS = "Materials";
    public static final String CAT_WEAPONS = "Weapons";
    public static final String CAT_ARMOR = "Armor";
    public static final String CAT_FISHING_RODS = "Fishing Rods";
    public static final String CAT_POTIONS = "Potions";
    public static final String CAT_OTHER = "Other";

    private static final List<String> CATEGORY_ORDER = new ArrayList<String>();
    static {
        CATEGORY_ORDER.add(CAT_FOOD);
        CATEGORY_ORDER.add(CAT_MATERIALS);
        CATEGORY_ORDER.add(CAT_WEAPONS);
        CATEGORY_ORDER.add(CAT_ARMOR);
        CATEGORY_ORDER.add(CAT_FISHING_RODS);
        CATEGORY_ORDER.add(CAT_POTIONS);
        CATEGORY_ORDER.add(CAT_OTHER);
    }

    public static final int BUILD_BATCH_GUI = 2200;
    private static final int BUILD_BATCH_BACKGROUND = 350;

    private enum LoadPhase {
        IDLE, PARSING_JSON, BUILDING_ENTRIES, READY, FAILED
    }

    private static final Object LOAD_LOCK = new Object();
    private static volatile LoadPhase loadPhase = LoadPhase.IDLE;
    private static volatile boolean warmupScheduled = false;
    private static volatile JsonObject pendingRoot;
    private static volatile int buildIndex;
    private static final List<Entry> buildingEntries = new ArrayList<Entry>();

    private static List<Entry> entries = Collections.emptyList();
    /** {@link #variantGroupKey(Entry)} keys with more than one catalog row (name/NBT variants). */
    private static Set<String> multiVariantGroupKeys = Collections.emptySet();
    private static int observedValidCaptures = 0;
    private static int observedSlotCaptures = 0;
    private static List<LoggedChest> loggedChests = Collections.emptyList();
    private static Map<String, Integer> categoryCounts = Collections.emptyMap();

    private BlitzChestItemsData() {}

    public static boolean isReady() {
        return loadPhase == LoadPhase.READY;
    }

    public static float getLoadProgress() {
        LoadPhase phase = loadPhase;
        if (phase == LoadPhase.READY) {
            return 1f;
        }
        if (phase == LoadPhase.PARSING_JSON) {
            return 0.04f;
        }
        if (phase == LoadPhase.BUILDING_ENTRIES) {
            JsonObject root = pendingRoot;
            if (root != null && root.has("items") && root.get("items").isJsonArray()) {
                int total = root.getAsJsonArray("items").size();
                if (total > 0) {
                    return 0.08f + 0.92f * Math.min(1f, buildIndex / (float) total);
                }
            }
            return 0.08f;
        }
        return 0f;
    }

    public static void scheduleWarmup() {
        synchronized (LOAD_LOCK) {
            if (loadPhase != LoadPhase.IDLE || warmupScheduled) {
                return;
            }
            warmupScheduled = true;
            loadPhase = LoadPhase.PARSING_JSON;
        }
        Thread worker = new Thread(BlitzChestItemsData::parseJsonOnBackgroundThread, "gomod-blitz-chest-json");
        worker.setDaemon(true);
        worker.start();
    }

    public static void pumpBuildBatch(int batchSize) {
        if (loadPhase == LoadPhase.READY || loadPhase == LoadPhase.FAILED) {
            return;
        }
        if (loadPhase == LoadPhase.IDLE) {
            scheduleWarmup();
            return;
        }
        if (loadPhase == LoadPhase.PARSING_JSON) {
            return;
        }
        tickBuildEntries(Math.max(1, batchSize));
    }

    public static synchronized List<Entry> getAll() {
        if (loadPhase == LoadPhase.READY) {
            return entries;
        }
        if (loadPhase == LoadPhase.BUILDING_ENTRIES && !buildingEntries.isEmpty()) {
            return Collections.unmodifiableList(new ArrayList<Entry>(buildingEntries));
        }
        return Collections.emptyList();
    }

    public static synchronized int getObservedValidCaptures() {
        ensureMetadataReadable();
        return observedValidCaptures;
    }

    public static synchronized int getObservedSlotCaptures() {
        ensureMetadataReadable();
        return observedSlotCaptures;
    }

    public static synchronized List<LoggedChest> getLoggedChests() {
        if (loadPhase != LoadPhase.READY) {
            return Collections.emptyList();
        }
        return loggedChests;
    }

    public static synchronized int getLoggedChestCount() {
        ensureMetadataReadable();
        if (!loggedChests.isEmpty()) {
            return loggedChests.size();
        }
        return observedValidCaptures;
    }

    public static synchronized int getCategoryCount(String category) {
        if (loadPhase != LoadPhase.READY) {
            return 0;
        }
        Integer count = categoryCounts.get(category);
        return count == null ? 0 : count;
    }

    public static List<String> getCategoryOrder() {
        return Collections.unmodifiableList(CATEGORY_ORDER);
    }

    private static void ensureMetadataReadable() {
        if (loadPhase == LoadPhase.READY || loadPhase == LoadPhase.BUILDING_ENTRIES || loadPhase == LoadPhase.FAILED) {
            return;
        }
        if (loadPhase == LoadPhase.IDLE) {
            scheduleWarmup();
        }
    }

    private static void parseJsonOnBackgroundThread() {
        try (InputStream stream = BlitzChestItemsData.class.getResourceAsStream(RESOURCE_PATH)) {
            if (stream == null) {
                failLoad("resource missing: " + RESOURCE_PATH);
                return;
            }
            JsonObject root;
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                root = new JsonParser().parse(reader).getAsJsonObject();
            }
            synchronized (LOAD_LOCK) {
                if (loadPhase != LoadPhase.PARSING_JSON) {
                    return;
                }
                pendingRoot = root;
                observedValidCaptures = optInt(root, "observed_valid_captures", 0);
                observedSlotCaptures = optInt(root, "observed_slot_captures", 0);
                buildIndex = 0;
                buildingEntries.clear();
                loadPhase = LoadPhase.BUILDING_ENTRIES;
            }
            queueBuildTick(BUILD_BATCH_BACKGROUND);
        } catch (Throwable t) {
            failLoad(String.valueOf(t));
        }
    }

    private static void queueBuildTick(final int batchSize) {
        Minecraft mc;
        try {
            mc = Minecraft.getMinecraft();
        } catch (Throwable t) {
            mc = null;
        }
        if (mc == null) {
            return;
        }
        Runnable task = new Runnable() {
            @Override
            public void run() {
                tickBuildEntries(batchSize);
            }
        };
        try {
            mc.addScheduledTask(task);
        } catch (Throwable t) {
            task.run();
        }
    }

    private static void tickBuildEntries(int batchSize) {
        JsonObject root;
        JsonArray arr;
        int start;
        synchronized (LOAD_LOCK) {
            if (loadPhase != LoadPhase.BUILDING_ENTRIES || pendingRoot == null) {
                return;
            }
            root = pendingRoot;
            arr = root.has("items") && root.get("items").isJsonArray()
                    ? root.getAsJsonArray("items") : new JsonArray();
            start = buildIndex;
        }
        int end = Math.min(start + batchSize, arr.size());
        for (int i = start; i < end; i++) {
            JsonElement el = arr.get(i);
            if (el == null || !el.isJsonObject()) {
                continue;
            }
            Entry e = parseEntry(el.getAsJsonObject());
            if (e != null) {
                synchronized (LOAD_LOCK) {
                    buildingEntries.add(e);
                }
            }
        }
        boolean finished;
        synchronized (LOAD_LOCK) {
            buildIndex = end;
            finished = buildIndex >= arr.size();
            if (finished) {
                List<Entry> built = new ArrayList<Entry>(buildingEntries);
                finalizeLoadedEntries(root, built);
                pendingRoot = null;
                buildingEntries.clear();
                buildIndex = 0;
                loadPhase = LoadPhase.READY;
            }
        }
        if (!finished) {
            queueBuildTick(batchSize);
        }
    }

    private static void finalizeLoadedEntries(JsonObject root, List<Entry> built) {
        Collections.sort(built, COMPARATOR);
        entries = Collections.unmodifiableList(built);
        loggedChests = Collections.unmodifiableList(parseLoggedChests(root));
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (String cat : CATEGORY_ORDER) {
            counts.put(cat, 0);
        }
        for (Entry entry : built) {
            if (entry == null) {
                continue;
            }
            Integer prev = counts.get(entry.category);
            counts.put(entry.category, (prev == null ? 0 : prev) + 1);
        }
        categoryCounts = Collections.unmodifiableMap(counts);
        multiVariantGroupKeys = buildMultiVariantGroupKeys(built);
        System.out.println("[gomod] Loaded " + entries.size() + " Blitz chest items from " + RESOURCE_PATH);
    }

    private static Set<String> buildMultiVariantGroupKeys(List<Entry> built) {
        if (built == null || built.isEmpty()) {
            return Collections.emptySet();
        }
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (Entry entry : built) {
            if (entry == null) {
                continue;
            }
            String key = variantGroupKey(entry);
            Integer prev = counts.get(key);
            counts.put(key, (prev == null ? 0 : prev) + 1);
        }
        Set<String> out = new LinkedHashSet<String>();
        for (Map.Entry<String, Integer> row : counts.entrySet()) {
            if (row.getValue() != null && row.getValue() > 1) {
                out.add(row.getKey());
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /** True when this row is merged variants or shares id+meta+enchants with other catalog rows. */
    public static boolean hasCatalogVariants(Entry entry) {
        if (entry == null) {
            return false;
        }
        if (entry.mergedVariants || entry.mergedCombatTier) {
            return true;
        }
        return multiVariantGroupKeys.contains(variantGroupKey(entry));
    }

    public static boolean hasCatalogVariants(ChestSlot slot) {
        if (slot == null) {
            return false;
        }
        return multiVariantGroupKeys.contains(variantGroupKey(slot.id, slot.meta, slot.stack));
    }

    private static void failLoad(String reason) {
        synchronized (LOAD_LOCK) {
            loadPhase = LoadPhase.FAILED;
            pendingRoot = null;
            buildingEntries.clear();
            entries = Collections.emptyList();
            loggedChests = Collections.emptyList();
            categoryCounts = Collections.emptyMap();
            multiVariantGroupKeys = Collections.emptySet();
        }
        System.out.println("[gomod] Blitz chest items load failed: " + reason);
    }

    private static Entry parseEntry(JsonObject obj) {
        String id = optString(obj, "id", "");
        int meta = optInt(obj, "meta", 0);
        int count = Math.max(1, optInt(obj, "count", 1));
        String name = optString(obj, "name", "");
        String nbt = optString(obj, "nbt", "");
        List<String> tooltip = readStringArray(obj, "tooltip");
        if (id == null || id.isEmpty()) {
            return null;
        }
        ItemStack stack = buildStack(id, meta, count, nbt);
        if (stack == null || stack.getItem() == null) {
            return null;
        }
        if (name == null || name.isEmpty()) {
            try {
                name = stack.getDisplayName();
            } catch (Throwable ignored) {
                name = id;
            }
        }
        name = normalizeFormatting(name);
        String category = classifyCategory(id);
        Map<String, Integer> enchants = readEnchantments(stack);
        int observedCount = Math.max(0, optInt(obj, "observed_count", 0));
        int observedSlotCount = Math.max(0, optInt(obj, "observed_slot_count", 0));
        String filterText = buildFilterText(id, name, tooltip, enchants);
        return new Entry(id, meta, count, name, nbt, stack, tooltip, category, enchants,
                observedCount, observedSlotCount, filterText);
    }

    private static List<LoggedChest> parseLoggedChests(JsonObject root) {
        if (root == null || !root.has("blitz_chests") || !root.get("blitz_chests").isJsonArray()) {
            return Collections.emptyList();
        }
        JsonArray arr = root.getAsJsonArray("blitz_chests");
        List<LoggedChest> out = new ArrayList<LoggedChest>();
        for (int i = 0; i < arr.size(); i++) {
            JsonElement el = arr.get(i);
            if (el == null || !el.isJsonObject()) {
                continue;
            }
            LoggedChest chest = parseLoggedChest(el.getAsJsonObject(), i + 1);
            if (chest != null && !chest.slots.isEmpty()) {
                out.add(chest);
            }
        }
        return out;
    }

    private static LoggedChest parseLoggedChest(JsonObject obj, int fallbackIndex) {
        int index = Math.max(1, optInt(obj, "index", fallbackIndex));
        int slotCount = Math.max(0, optInt(obj, "slot_count", 0));
        String capturedAt = optString(obj, "captured_at", "");
        List<ChestSlot> slots = new ArrayList<ChestSlot>();
        if (obj.has("slots") && obj.get("slots").isJsonArray()) {
            JsonArray arr = obj.getAsJsonArray("slots");
            for (int i = 0; i < arr.size(); i++) {
                JsonElement el = arr.get(i);
                if (el == null || !el.isJsonObject()) {
                    continue;
                }
                ChestSlot slot = parseChestSlot(el.getAsJsonObject());
                if (slot != null) {
                    slots.add(slot);
                    slotCount = Math.max(slotCount, slot.slot + 1);
                }
            }
        }
        Collections.sort(slots, new java.util.Comparator<ChestSlot>() {
            @Override
            public int compare(ChestSlot a, ChestSlot b) {
                return Integer.compare(a.slot, b.slot);
            }
        });
        return new LoggedChest(index, Math.max(slotCount, slots.size()), capturedAt, slots);
    }

    private static ChestSlot parseChestSlot(JsonObject obj) {
        int slot = Math.max(0, optInt(obj, "slot", 0));
        String id = optString(obj, "id", optString(obj, "item", ""));
        int meta = optInt(obj, "meta", 0);
        int count = Math.max(1, optInt(obj, "count", optInt(obj, "amount", 1)));
        String name = optString(obj, "name", optString(obj, "display", ""));
        String nbt = optString(obj, "nbt", "");
        List<String> tooltip = readStringArray(obj, "tooltip");
        if (id == null || id.isEmpty()) {
            return null;
        }
        ItemStack stack = buildStack(id, meta, count, nbt);
        if (stack == null || stack.getItem() == null) {
            return null;
        }
        if (name == null || name.isEmpty()) {
            try {
                name = stack.getDisplayName();
            } catch (Throwable ignored) {
                name = id;
            }
        }
        name = normalizeFormatting(name);
        if (tooltip.isEmpty()) {
            tooltip.add(name);
            tooltip.add(id);
        }
        return new ChestSlot(slot, id, meta, count, name, nbt, stack, tooltip);
    }

    private static ItemStack buildStack(String id, int meta, int count, String nbt) {
        if (nbt != null && !nbt.isEmpty()) {
            try {
                NBTTagCompound parsed = JsonToNBT.getTagFromJson(nbt);
                if (parsed != null) {
                    ItemStack viaNbt = ItemStack.loadItemStackFromNBT(parsed);
                    if (viaNbt != null && viaNbt.getItem() != null) {
                        return viaNbt;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            Item item = Item.getByNameOrId(id);
            if (item == null) {
                item = (Item) Item.itemRegistry.getObject(new ResourceLocation(id));
            }
            if (item != null) {
                return new ItemStack(item, count, meta);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Map<String, Integer> readEnchantments(ItemStack stack) {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        if (stack == null) {
            return out;
        }
        try {
            NBTTagList list = stack.getEnchantmentTagList();
            if (list == null || list.tagCount() == 0) {
                return out;
            }
            for (int i = 0; i < list.tagCount(); i++) {
                NBTTagCompound entry = list.getCompoundTagAt(i);
                int eid = entry.getShort("id") & 0xFFFF;
                int lvl = entry.getShort("lvl");
                Enchantment e = Enchantment.getEnchantmentById(eid);
                if (e == null) {
                    continue;
                }
                String label;
                try {
                    label = stripFormatting(e.getTranslatedName(lvl));
                } catch (Throwable ignored) {
                    label = e.getName();
                }
                if (label != null && !label.isEmpty()) {
                    out.put(label, lvl);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String buildFilterText(String id, String displayName, List<String> tooltip,
                                          Map<String, Integer> enchants) {
        StringBuilder sb = new StringBuilder();
        if (id != null) {
            sb.append(id.toLowerCase(Locale.US)).append(' ');
        }
        if (displayName != null) {
            sb.append(displayName.toLowerCase(Locale.US)).append(' ');
        }
        if (tooltip != null) {
            for (String line : tooltip) {
                if (line != null) {
                    sb.append(stripFormatting(line).toLowerCase(Locale.US)).append(' ');
                }
            }
        }
        if (enchants != null) {
            for (String key : enchants.keySet()) {
                sb.append(key.toLowerCase(Locale.US)).append(' ');
            }
        }
        return sb.toString();
    }

    /** Loot-chest categories aligned with Inventory QoL / chest pick hints. */
    public static String classifyCategory(String id) {
        if (id == null) {
            return CAT_OTHER;
        }
        String lower = id.toLowerCase(Locale.US);
        if (lower.contains("potion")) {
            return CAT_POTIONS;
        }
        if (isFoodId(lower)) {
            return CAT_FOOD;
        }
        if (isMaterialId(lower)) {
            return CAT_MATERIALS;
        }
        if (lower.endsWith("_sword") || lower.equals("minecraft:bow")
                || (lower.endsWith("_axe") && !lower.endsWith("_pickaxe"))) {
            return CAT_WEAPONS;
        }
        if (lower.endsWith("_helmet") || lower.endsWith("_chestplate")
                || lower.endsWith("_leggings") || lower.endsWith("_boots")) {
            return CAT_ARMOR;
        }
        if (lower.equals("minecraft:fishing_rod")) {
            return CAT_FISHING_RODS;
        }
        return CAT_OTHER;
    }

    private static boolean isFoodId(String lower) {
        return lower.equals("minecraft:apple")
                || lower.equals("minecraft:golden_apple")
                || lower.equals("minecraft:golden_carrot")
                || lower.equals("minecraft:bread")
                || lower.equals("minecraft:carrot")
                || lower.equals("minecraft:potato")
                || lower.equals("minecraft:baked_potato")
                || lower.equals("minecraft:poisonous_potato")
                || lower.equals("minecraft:melon")
                || lower.equals("minecraft:pumpkin_pie")
                || lower.equals("minecraft:cookie")
                || lower.equals("minecraft:mushroom_stew")
                || lower.equals("minecraft:rabbit_stew")
                || lower.equals("minecraft:beetroot")
                || lower.equals("minecraft:beetroot_soup")
                || lower.equals("minecraft:beef")
                || lower.equals("minecraft:cooked_beef")
                || lower.equals("minecraft:porkchop")
                || lower.equals("minecraft:cooked_porkchop")
                || lower.equals("minecraft:chicken")
                || lower.equals("minecraft:cooked_chicken")
                || lower.equals("minecraft:mutton")
                || lower.equals("minecraft:cooked_mutton")
                || lower.equals("minecraft:rabbit")
                || lower.equals("minecraft:cooked_rabbit")
                || lower.equals("minecraft:fish")
                || lower.equals("minecraft:cooked_fish")
                || lower.equals("minecraft:rotten_flesh")
                || lower.equals("minecraft:spider_eye")
                || lower.equals("minecraft:chorus_fruit")
                || lower.equals("minecraft:clownfish")
                || lower.equals("minecraft:salmon")
                || lower.equals("minecraft:cooked_salmon");
    }

    private static boolean isMaterialId(String lower) {
        return lower.equals("minecraft:diamond")
                || lower.equals("minecraft:iron_ingot")
                || lower.equals("minecraft:gold_ingot")
                || lower.equals("minecraft:gold_nugget")
                || lower.equals("minecraft:stick")
                || lower.equals("minecraft:string")
                || lower.equals("minecraft:flint")
                || lower.equals("minecraft:leather")
                || lower.equals("minecraft:feather")
                || lower.equals("minecraft:bone")
                || lower.equals("minecraft:gunpowder")
                || lower.equals("minecraft:blaze_rod")
                || lower.equals("minecraft:ender_pearl")
                || lower.equals("minecraft:slime_ball")
                || lower.equals("minecraft:ghast_tear")
                || lower.equals("minecraft:spider_eye")
                || lower.equals("minecraft:fermented_spider_eye")
                || lower.equals("minecraft:glowstone_dust")
                || lower.equals("minecraft:redstone")
                || lower.equals("minecraft:sugar")
                || lower.equals("minecraft:paper")
                || lower.equals("minecraft:book")
                || lower.equals("minecraft:coal")
                || lower.equals("minecraft:charcoal")
                || lower.equals("minecraft:emerald")
                || lower.equals("minecraft:prismarine_shard")
                || lower.equals("minecraft:prismarine_crystals")
                || lower.equals("minecraft:rabbit_hide")
                || lower.equals("minecraft:rabbit_foot")
                || lower.equals("minecraft:nether_wart")
                || lower.equals("minecraft:magma_cream");
    }

    private static List<String> readStringArray(JsonObject obj, String key) {
        List<String> out = new ArrayList<String>();
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonArray()) {
            return out;
        }
        JsonArray a = obj.getAsJsonArray(key);
        for (int i = 0; i < a.size(); i++) {
            JsonElement el = a.get(i);
            if (el != null && !el.isJsonNull()) {
                out.add(el.getAsString());
            }
        }
        return out;
    }

    private static String stripFormatting(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u00A7' && i + 1 < s.length()) {
                i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Restore § codes corrupted as U+FFFD + format char in bundled JSON. */
    public static String normalizeFormatting(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\uFFFD' && i + 1 < text.length()) {
                char next = Character.toLowerCase(text.charAt(i + 1));
                if ("0123456789abcdefklmnor".indexOf(next) >= 0) {
                    sb.append('\u00A7').append(text.charAt(i + 1));
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Vanilla hides leather dye hex when advanced tooltips are off ({@code "Dyed"} only).
     * Prefer the logged {@code Color: #RRGGBB} line, else read {@code display.color} from the stack.
     */
    public static void replaceDyedWithLeatherColorLine(List<String> tooltip, ItemStack stack,
                                                       List<String> fallbackTooltip) {
        if (tooltip == null || tooltip.isEmpty()) {
            return;
        }
        String colorLine = findTooltipColorLine(fallbackTooltip);
        if (colorLine == null) {
            colorLine = leatherColorLineFromStack(stack);
        }
        if (colorLine == null) {
            return;
        }
        for (int i = 0; i < tooltip.size(); i++) {
            if ("Dyed".equalsIgnoreCase(stripFormatting(tooltip.get(i)).trim())) {
                tooltip.set(i, colorLine);
            }
        }
    }

    private static String findTooltipColorLine(List<String> lines) {
        if (lines == null) {
            return null;
        }
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String plain = stripFormatting(line).trim();
            if (plain.regionMatches(true, 0, "Color:", 0, 6)) {
                return plain;
            }
        }
        return null;
    }

    private static String leatherColorLineFromStack(ItemStack stack) {
        if (stack == null || !stack.hasTagCompound()) {
            return null;
        }
        try {
            NBTTagCompound tag = stack.getTagCompound();
            if (tag == null || !tag.hasKey("display", 10)) {
                return null;
            }
            NBTTagCompound display = tag.getCompoundTag("display");
            if (display == null || !display.hasKey("color")) {
                return null;
            }
            int rgb = display.getInteger("color") & 0xFFFFFF;
            return String.format(Locale.US, "Color: #%06X", rgb);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String optString(JsonObject obj, String key, String def) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return def;
        }
        try {
            return obj.get(key).getAsString();
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static int optInt(JsonObject obj, String key, int def) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return def;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static final java.util.Comparator<Entry> COMPARATOR = new java.util.Comparator<Entry>() {
        @Override
        public int compare(Entry a, Entry b) {
            int c = Integer.compare(categoryRank(a.category), categoryRank(b.category));
            if (c != 0) {
                return c;
            }
            int obs = Integer.compare(b.observedCount, a.observedCount);
            if (obs != 0) {
                return obs;
            }
            return a.id.compareTo(b.id);
        }
    };

    public static int categoryRank(String cat) {
        int idx = CATEGORY_ORDER.indexOf(cat);
        return idx < 0 ? CATEGORY_ORDER.size() : idx;
    }

    /**
     * Groups variants that share id + meta + enchantments.
     * Custom display names and stack count (e.g. XP bottles x1 vs x2) still merge; different enchants do not.
     */
    public static String variantGroupKey(Entry entry) {
        if (entry == null) {
            return "";
        }
        return variantGroupKey(entry.id, entry.meta, entry.stack, entry.enchantments);
    }

    public static String variantGroupKey(String id, int meta, ItemStack stack) {
        return variantGroupKey(id, meta, stack, null);
    }

    private static String variantGroupKey(String id, int meta, ItemStack stack, Map<String, Integer> enchantments) {
        return (id == null ? "" : id) + '\0' + meta + '\0' + enchantSignature(stack, enchantments);
    }

    private static String enchantSignature(Entry entry) {
        if (entry == null) {
            return "";
        }
        return enchantSignature(entry.stack, entry.enchantments);
    }

    private static String enchantSignature(ItemStack stack, Map<String, Integer> enchantments) {
        String fromStack = enchantSignatureFromStack(stack);
        if (!fromStack.isEmpty()) {
            return fromStack;
        }
        return enchantSignatureFromMap(enchantments);
    }

    private static String enchantSignatureFromStack(ItemStack stack) {
        if (stack == null) {
            return "";
        }
        try {
            NBTTagList list = stack.getEnchantmentTagList();
            if (list == null || list.tagCount() == 0) {
                return "";
            }
            List<String> parts = new ArrayList<String>();
            for (int i = 0; i < list.tagCount(); i++) {
                NBTTagCompound ench = list.getCompoundTagAt(i);
                int eid = ench.getShort("id") & 0xFFFF;
                int lvl = ench.getShort("lvl") & 0xFFFF;
                parts.add(eid + ":" + lvl);
            }
            Collections.sort(parts);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) {
                    sb.append(';');
                }
                sb.append(parts.get(i));
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String enchantSignatureFromMap(Map<String, Integer> enchants) {
        if (enchants == null || enchants.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<String>();
        for (Map.Entry<String, Integer> row : enchants.entrySet()) {
            if (row.getKey() == null || row.getKey().isEmpty()) {
                continue;
            }
            int lvl = row.getValue() == null ? 0 : row.getValue();
            parts.add(row.getKey().toLowerCase(Locale.US) + ':' + lvl);
        }
        Collections.sort(parts);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(';');
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    public static java.util.Comparator<Entry> getCategorySortComparator() {
        return COMPARATOR;
    }

    /** Sum observed counts across name/NBT variants with the same id + meta + enchantments. */
    public static List<Entry> aggregateByItemType(List<Entry> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashMap<String, Entry> representative = new LinkedHashMap<String, Entry>();
        Map<String, Integer> chestCounts = new LinkedHashMap<String, Integer>();
        Map<String, Integer> slotCounts = new LinkedHashMap<String, Integer>();
        Map<String, Integer> variantCounts = new LinkedHashMap<String, Integer>();
        for (Entry entry : source) {
            if (entry == null) {
                continue;
            }
            String key = variantGroupKey(entry);
            Integer prevVariants = variantCounts.get(key);
            variantCounts.put(key, (prevVariants == null ? 0 : prevVariants) + 1);
            Integer prevChest = chestCounts.get(key);
            chestCounts.put(key, (prevChest == null ? 0 : prevChest) + entry.observedCount);
            Integer prevSlot = slotCounts.get(key);
            slotCounts.put(key, (prevSlot == null ? 0 : prevSlot) + entry.observedSlotCount);
            Entry prevRep = representative.get(key);
            if (prevRep == null || entry.observedCount > prevRep.observedCount) {
                representative.put(key, entry);
            }
        }
        List<Entry> out = new ArrayList<Entry>(representative.size());
        for (Map.Entry<String, Entry> row : representative.entrySet()) {
            Entry rep = row.getValue();
            String key = row.getKey();
            Integer chest = chestCounts.get(key);
            Integer slots = slotCounts.get(key);
            Integer variants = variantCounts.get(key);
            boolean merged = variants != null && variants > 1;
            String displayName = merged
                    ? formatAggregatedVariantDisplayName(rep.id, rep.meta)
                    : rep.displayName;
            out.add(new Entry(rep.id, rep.meta, rep.count, displayName, rep.nbtRaw, rep.stack,
                    rep.fallbackTooltip, rep.category, rep.enchantments,
                    chest == null ? 0 : chest, slots == null ? 0 : slots, rep.filterText, merged));
        }
        return out;
    }

    /**
     * Merges armor pieces with the same {@link ItemArmor#damageReduceAmount} on the same slot,
     * and swords with the same attack damage (e.g. wood + gold sword).
     */
    public static List<Entry> aggregateByCombatTier(List<Entry> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        List<Entry> passThrough = new ArrayList<Entry>();
        LinkedHashMap<String, List<Entry>> groups = new LinkedHashMap<String, List<Entry>>();
        for (Entry entry : source) {
            if (entry == null) {
                continue;
            }
            String key = BlitzChestItemsCombatTier.combatTierGroupKey(entry);
            if (key == null) {
                passThrough.add(entry);
            } else {
                List<Entry> bucket = groups.get(key);
                if (bucket == null) {
                    bucket = new ArrayList<Entry>();
                    groups.put(key, bucket);
                }
                bucket.add(entry);
            }
        }
        List<Entry> out = new ArrayList<Entry>(passThrough.size() + groups.size());
        out.addAll(passThrough);
        for (Map.Entry<String, List<Entry>> row : groups.entrySet()) {
            String tierKey = row.getKey();
            List<Entry> group = row.getValue();
            if (group == null || group.isEmpty()) {
                continue;
            }
            if (group.size() == 1) {
                out.add(group.get(0));
                continue;
            }
            int chestTotal = 0;
            int slotTotal = 0;
            Entry rep = group.get(0);
            LinkedHashMap<String, ItemStack> compositeById = new LinkedHashMap<String, ItemStack>();
            for (Entry entry : group) {
                chestTotal += entry.observedCount;
                slotTotal += entry.observedSlotCount;
                if (entry.observedCount > rep.observedCount) {
                    rep = entry;
                }
                String idKey = entry.id + '\0' + entry.meta;
                if (!compositeById.containsKey(idKey)) {
                    ItemStack icon = buildStack(entry.id, entry.meta, 1, null);
                    if (icon != null) {
                        compositeById.put(idKey, icon);
                    }
                }
            }
            List<ItemStack> composite = new ArrayList<ItemStack>(compositeById.values());
            Collections.sort(composite, new java.util.Comparator<ItemStack>() {
                @Override
                public int compare(ItemStack a, ItemStack b) {
                    String idA = a == null || a.getItem() == null ? "" : Item.itemRegistry.getNameForObject(a.getItem()).toString();
                    String idB = b == null || b.getItem() == null ? "" : Item.itemRegistry.getNameForObject(b.getItem()).toString();
                    return idA.compareTo(idB);
                }
            });
            String displayName = BlitzChestItemsCombatTier.formatAggregatedDisplayName(tierKey);
            out.add(new Entry(rep.id, rep.meta, rep.count, displayName, rep.nbtRaw, rep.stack,
                    rep.fallbackTooltip, rep.category, rep.enchantments,
                    chestTotal, slotTotal, rep.filterText, false, true, tierKey, composite));
        }
        return out;
    }

    /** Italic + underlined label for Combine tier rows (e.g. Helmet combinations). */
    public static String formatCombinationLabel(String baseName) {
        String base = baseName == null ? "" : baseName.trim();
        if (base.isEmpty()) {
            base = "Item";
        }
        return "\u00a7o\u00a7n" + base + " combinations";
    }

    /** Italic label when Combine variants merges name/NBT rows (no underline). */
    public static String formatAggregatedVariantDisplayName(String id, int meta) {
        return "\u00a7o" + vanillaItemTypeDisplayName(id, meta) + " variants";
    }

    /** Tooltip lines for the Combine variants control pill hover. */
    public static String[] aggregateVariantsControlPillTooltipLines() {
        return new String[] {
                "Combines items of the same type,",
                "mostly same gear that are named differently."
        };
    }

    /** Minecraft item name without custom NBT display (e.g. Bottle o' Enchanting, Chainmail Leggings). */
    public static String vanillaItemTypeDisplayName(String id, int meta) {
        try {
            ItemStack stack = buildStack(id, meta, 1, null);
            if (stack != null) {
                String name = stack.getDisplayName();
                if (name != null && !name.isEmpty()) {
                    return stripFormatting(name);
                }
            }
        } catch (Throwable ignored) {
        }
        return friendlyItemTypeName(id);
    }


    private static String friendlyItemTypeName(String id) {
        if (id == null || id.isEmpty()) {
            return "Item";
        }
        int slash = id.lastIndexOf(':');
        String path = slash >= 0 ? id.substring(slash + 1) : id;
        String[] parts = path.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                sb.append(part.substring(1));
            }
        }
        return sb.length() > 0 ? sb.toString() : "Item";
    }

    public static final class Entry {
        public final String id;
        public final int meta;
        public final int count;
        public final String displayName;
        public final String nbtRaw;
        public final ItemStack stack;
        public final List<String> fallbackTooltip;
        public final String category;
        public final Map<String, Integer> enchantments;
        public final int observedCount;
        public final int observedSlotCount;
        public final String filterText;
        /** True when {@link #aggregateByItemType} merged two or more catalog rows. */
        public final boolean mergedVariants;
        /** True when {@link #aggregateByCombatTier} merged two or more rows by protection / sword damage. */
        public final boolean mergedCombatTier;
        public final String combatTierKey;
        public final List<ItemStack> tierCompositeStacks;

        Entry(String id, int meta, int count, String displayName, String nbtRaw, ItemStack stack,
              List<String> fallbackTooltip, String category, Map<String, Integer> enchantments,
              int observedCount, int observedSlotCount, String filterText) {
            this(id, meta, count, displayName, nbtRaw, stack, fallbackTooltip, category, enchantments,
                    observedCount, observedSlotCount, filterText, false, false, "", Collections.<ItemStack>emptyList());
        }

        Entry(String id, int meta, int count, String displayName, String nbtRaw, ItemStack stack,
              List<String> fallbackTooltip, String category, Map<String, Integer> enchantments,
              int observedCount, int observedSlotCount, String filterText, boolean mergedVariants) {
            this(id, meta, count, displayName, nbtRaw, stack, fallbackTooltip, category, enchantments,
                    observedCount, observedSlotCount, filterText, mergedVariants, false, "",
                    Collections.<ItemStack>emptyList());
        }

        Entry(String id, int meta, int count, String displayName, String nbtRaw, ItemStack stack,
              List<String> fallbackTooltip, String category, Map<String, Integer> enchantments,
              int observedCount, int observedSlotCount, String filterText, boolean mergedVariants,
              boolean mergedCombatTier, String combatTierKey, List<ItemStack> tierCompositeStacks) {
            this.id = id;
            this.meta = meta;
            this.count = count;
            this.displayName = displayName;
            this.nbtRaw = nbtRaw == null ? "" : nbtRaw;
            this.stack = stack;
            this.fallbackTooltip = Collections.unmodifiableList(new ArrayList<String>(fallbackTooltip));
            this.category = category;
            this.enchantments = Collections.unmodifiableMap(new LinkedHashMap<String, Integer>(enchantments));
            this.observedCount = observedCount;
            this.observedSlotCount = observedSlotCount;
            this.filterText = filterText;
            this.mergedVariants = mergedVariants;
            this.mergedCombatTier = mergedCombatTier;
            this.combatTierKey = combatTierKey == null ? "" : combatTierKey;
            this.tierCompositeStacks = Collections.unmodifiableList(
                    new ArrayList<ItemStack>(tierCompositeStacks == null ? Collections.<ItemStack>emptyList() : tierCompositeStacks));
        }
    }

    public static final class LoggedChest {
        public final int index;
        public final int slotCount;
        public final String capturedAt;
        public final List<ChestSlot> slots;

        LoggedChest(int index, int slotCount, String capturedAt, List<ChestSlot> slots) {
            this.index = Math.max(1, index);
            this.slotCount = Math.max(0, slotCount);
            this.capturedAt = capturedAt == null ? "" : capturedAt;
            this.slots = Collections.unmodifiableList(new ArrayList<ChestSlot>(slots));
        }
    }

    public static final class ChestSlot {
        public final int slot;
        public final String id;
        public final int meta;
        public final int count;
        public final String displayName;
        public final String nbtRaw;
        public final ItemStack stack;
        public final List<String> fallbackTooltip;

        ChestSlot(int slot, String id, int meta, int count, String displayName, String nbtRaw,
                  ItemStack stack, List<String> fallbackTooltip) {
            this.slot = Math.max(0, slot);
            this.id = id == null ? "" : id;
            this.meta = meta;
            this.count = count;
            this.displayName = displayName == null ? "" : displayName;
            this.nbtRaw = nbtRaw == null ? "" : nbtRaw;
            this.stack = stack;
            this.fallbackTooltip = Collections.unmodifiableList(new ArrayList<String>(fallbackTooltip));
        }
    }
}
