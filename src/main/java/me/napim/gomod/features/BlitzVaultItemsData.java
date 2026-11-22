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
 * Loads the bundled {@code /blitz_vault_items.json} resource (rebuilt from logged Blitz survival
 * games vault chest dumps) and turns each entry into a renderable {@link ItemStack}.
 *
 * <p>Only loaded once on demand; {@link BlitzVaultItemsGUI} consumes the {@link Entry} list.</p>
 */
public final class BlitzVaultItemsData {

    private static final String RESOURCE_PATH = "/blitz_vault_items.json";

    public static final String CAT_BOW = "Bows";
    public static final String CAT_SWORD = "Swords";
    public static final String CAT_AXE = "Axes";
    public static final String CAT_PICKAXE = "Pickaxes";
    public static final String CAT_SHOVEL = "Shovels";
    public static final String CAT_HOE = "Hoes";
    public static final String CAT_ROD = "Fishing Rods";
    public static final String CAT_HELMET = "Helmets";
    public static final String CAT_CHESTPLATE = "Chestplates";
    public static final String CAT_LEGGINGS = "Leggings";
    public static final String CAT_BOOTS = "Boots";
    public static final String CAT_OTHER = "Other";

    public static final String TIER_NONE = "";
    public static final String TIER_WOOD = "Wooden";
    public static final String TIER_STONE = "Stone";
    public static final String TIER_IRON = "Iron";
    public static final String TIER_GOLD = "Golden";
    public static final String TIER_DIAMOND = "Diamond";
    public static final String TIER_LEATHER = "Leather";
    public static final String TIER_CHAIN = "Chainmail";

    private static final List<String> CATEGORY_ORDER = new ArrayList<String>();
    static {
        CATEGORY_ORDER.add(CAT_BOW);
        CATEGORY_ORDER.add(CAT_SWORD);
        CATEGORY_ORDER.add(CAT_AXE);
        CATEGORY_ORDER.add(CAT_HELMET);
        CATEGORY_ORDER.add(CAT_CHESTPLATE);
        CATEGORY_ORDER.add(CAT_LEGGINGS);
        CATEGORY_ORDER.add(CAT_BOOTS);
        CATEGORY_ORDER.add(CAT_PICKAXE);
        CATEGORY_ORDER.add(CAT_SHOVEL);
        CATEGORY_ORDER.add(CAT_HOE);
        CATEGORY_ORDER.add(CAT_ROD);
    }

    private static final List<String> TIER_ORDER = new ArrayList<String>();
    static {
        TIER_ORDER.add(TIER_WOOD);
        TIER_ORDER.add(TIER_STONE);
        TIER_ORDER.add(TIER_IRON);
        TIER_ORDER.add(TIER_GOLD);
        TIER_ORDER.add(TIER_DIAMOND);
        TIER_ORDER.add(TIER_LEATHER);
        TIER_ORDER.add(TIER_CHAIN);
        TIER_ORDER.add(TIER_NONE);
    }

    private static final int BUILD_BATCH_BACKGROUND = 350;
    /** Large batch for finishing load quickly when the vault GUI opens. */
    public static final int BUILD_BATCH_GUI = 2200;

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
    private static int sourceUnique = 0;
    private static int observedValidCaptures = 0;
    private static int observedSlotCaptures = 0;
    private static List<SwordPattern> swordPatterns = Collections.emptyList();
    private static List<VaultChest> vaultChests = Collections.emptyList();
    private static List<VaultEnchantChance> vaultEnchantChances = Collections.emptyList();
    private static List<Entry> vaultEnchantChanceSource = Collections.emptyList();
    private static Map<String, Integer> enchantChestHits = Collections.emptyMap();
    private static Map<String, Integer> enchantSlotHits = Collections.emptyMap();
    private static Map<String, Integer> categoryCounts = Collections.emptyMap();
    private static List<String> enchantFilterOptions = Collections.emptyList();
    private static List<String> tiersWithItems = Collections.emptyList();

    private BlitzVaultItemsData() {}

    public static boolean isReady() {
        return loadPhase == LoadPhase.READY;
    }

    public static boolean isLoading() {
        LoadPhase phase = loadPhase;
        return phase == LoadPhase.PARSING_JSON || phase == LoadPhase.BUILDING_ENTRIES;
    }

    /** 0..1 while background / chunked load is in progress. */
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

    /** Parse JSON off-thread, then build {@link ItemStack}s on the client thread in small batches. */
    public static void scheduleWarmup() {
        synchronized (LOAD_LOCK) {
            if (loadPhase != LoadPhase.IDLE || warmupScheduled) {
                return;
            }
            warmupScheduled = true;
            loadPhase = LoadPhase.PARSING_JSON;
        }
        Thread worker = new Thread(BlitzVaultItemsData::parseJsonOnBackgroundThread, "gomod-blitz-vault-json");
        worker.setDaemon(true);
        worker.start();
    }

    /** Finish as many build batches as possible on the main thread (used when opening the GUI). */
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

    public static synchronized int getDeclaredUnique() {
        ensureMetadataReadable();
        return sourceUnique;
    }

    public static synchronized int getObservedValidCaptures() {
        ensureMetadataReadable();
        return observedValidCaptures;
    }

    public static synchronized int getObservedSlotCaptures() {
        ensureMetadataReadable();
        return observedSlotCaptures;
    }

    public static synchronized List<SwordPattern> getSwordPatterns() {
        ensureMetadataReadable();
        return swordPatterns;
    }

    public static synchronized List<VaultChest> getVaultChests() {
        if (loadPhase != LoadPhase.READY) {
            return Collections.emptyList();
        }
        return vaultChests;
    }

    public static synchronized int getVaultChestCount() {
        ensureMetadataReadable();
        if (!vaultChests.isEmpty()) {
            return vaultChests.size();
        }
        return observedValidCaptures;
    }

    /** Chest/slot odds for configured vault enchants (see {@link BlitzVaultEnchantChanceRules}). */
    public static synchronized List<VaultEnchantChance> getVaultEnchantChances() {
        if (loadPhase != LoadPhase.READY) {
            return Collections.emptyList();
        }
        return vaultEnchantChances;
    }

    /** Rebuild enchant odds after {@link ConfigManager} enchant-chance settings change. */
    public static synchronized void rebuildVaultEnchantChances() {
        if (loadPhase != LoadPhase.READY) {
            return;
        }
        vaultEnchantChances = Collections.unmodifiableList(
                BlitzVaultEnchantChanceRules.build(vaultEnchantChanceSource, observedValidCaptures,
                        observedSlotCaptures, enchantChestHits, enchantSlotHits));
    }

    public static synchronized int getEntryCount() {
        if (loadPhase == LoadPhase.READY) {
            return entries.size();
        }
        if (loadPhase == LoadPhase.BUILDING_ENTRIES) {
            return buildingEntries.size();
        }
        return 0;
    }

    public static synchronized int getCategoryCount(String category) {
        if (loadPhase != LoadPhase.READY) {
            return 0;
        }
        Integer count = categoryCounts.get(category);
        return count == null ? 0 : count;
    }

    public static synchronized List<String> getEnchantFilterOptions() {
        if (loadPhase != LoadPhase.READY) {
            return Collections.emptyList();
        }
        return enchantFilterOptions;
    }

    public static synchronized List<String> getTiersWithItems() {
        if (loadPhase != LoadPhase.READY) {
            return Collections.emptyList();
        }
        return tiersWithItems;
    }

    public static List<String> getCategoryOrder() {
        return Collections.unmodifiableList(CATEGORY_ORDER);
    }

    public static List<String> getTierOrder() {
        return Collections.unmodifiableList(TIER_ORDER);
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
        try (InputStream stream = BlitzVaultItemsData.class.getResourceAsStream(RESOURCE_PATH)) {
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
                applyRootMetadata(root);
                buildIndex = 0;
                buildingEntries.clear();
                loadPhase = LoadPhase.BUILDING_ENTRIES;
            }
            queueBuildTick(BUILD_BATCH_BACKGROUND);
        } catch (Throwable t) {
            failLoad(String.valueOf(t));
        }
    }

    private static void applyRootMetadata(JsonObject root) {
        if (root == null) {
            return;
        }
        sourceUnique = optInt(root, "unique_item_variants", 0);
        observedValidCaptures = optInt(root, "observed_valid_captures", 0);
        observedSlotCaptures = optInt(root, "observed_slot_captures", 0);
        swordPatterns = Collections.unmodifiableList(parseSwordPatterns(root));
        enchantChestHits = Collections.unmodifiableMap(parseStringIntMap(root, "enchant_chest_hits"));
        enchantSlotHits = Collections.unmodifiableMap(parseStringIntMap(root, "enchant_slot_hits"));
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
        int resumeBatch = batchSize;
        synchronized (LOAD_LOCK) {
            buildIndex = end;
            finished = buildIndex >= arr.size();
            if (!finished) {
                resumeBatch = batchSize;
            } else {
                List<Entry> built = new ArrayList<Entry>(buildingEntries);
                finalizeLoadedEntries(root, built);
                pendingRoot = null;
                buildingEntries.clear();
                buildIndex = 0;
                loadPhase = LoadPhase.READY;
                BlitzVaultEnchantLevelInsights.invalidate();
                BlitzVaultLootInsights.invalidate();
            }
        }
        if (!finished) {
            queueBuildTick(resumeBatch);
        }
    }

    private static void finalizeLoadedEntries(JsonObject root, List<Entry> built) {
        Collections.sort(built, COMPARATOR);
        entries = Collections.unmodifiableList(built);
        vaultChests = Collections.unmodifiableList(parseVaultChests(root));
        vaultEnchantChanceSource = built;
        vaultEnchantChances = Collections.unmodifiableList(
                BlitzVaultEnchantChanceRules.build(built, observedValidCaptures, observedSlotCaptures,
                        enchantChestHits, enchantSlotHits));
        if (sourceUnique <= 0) {
            sourceUnique = entries.size();
        }
        rebuildLookupCaches(built);
        System.out.println("[gomod] Loaded " + entries.size() + " Blitz vault items from " + RESOURCE_PATH);
    }

    private static void rebuildLookupCaches(List<Entry> built) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (String cat : CATEGORY_ORDER) {
            counts.put(cat, 0);
        }
        Set<String> enchantNames = new LinkedHashSet<String>();
        Set<String> tiers = new LinkedHashSet<String>();
        for (Entry entry : built) {
            if (entry == null) {
                continue;
            }
            Integer prev = counts.get(entry.category);
            counts.put(entry.category, (prev == null ? 0 : prev) + 1);
            if (entry.tier != null && !entry.tier.isEmpty()) {
                tiers.add(entry.tier);
            }
            if (entry.enchantments != null) {
                for (String label : entry.enchantments.keySet()) {
                    String base = stripEnchantBaseName(label);
                    if (!base.isEmpty()) {
                        enchantNames.add(base);
                    }
                }
            }
        }
        categoryCounts = Collections.unmodifiableMap(counts);
        List<String> enchants = new ArrayList<String>(enchantNames);
        Collections.sort(enchants, String.CASE_INSENSITIVE_ORDER);
        enchantFilterOptions = Collections.unmodifiableList(enchants);
        List<String> tierList = new ArrayList<String>();
        for (String tier : TIER_ORDER) {
            if (tiers.contains(tier)) {
                tierList.add(tier);
            }
        }
        tiersWithItems = Collections.unmodifiableList(tierList);
    }

    private static String stripEnchantBaseName(String label) {
        String text = stripFormatting(label).trim();
        int idx = text.lastIndexOf(' ');
        if (idx > 0) {
            String last = text.substring(idx + 1).trim();
            if (isRomanNumeralToken(last)) {
                return text.substring(0, idx).trim();
            }
        }
        return text;
    }

    private static boolean isRomanNumeralToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c != 'I' && c != 'V' && c != 'X') {
                return false;
            }
        }
        return true;
    }

    private static void failLoad(String reason) {
        synchronized (LOAD_LOCK) {
            loadPhase = LoadPhase.FAILED;
            pendingRoot = null;
            buildingEntries.clear();
            entries = Collections.emptyList();
            vaultEnchantChances = Collections.emptyList();
            vaultEnchantChanceSource = Collections.emptyList();
            vaultChests = Collections.emptyList();
            enchantChestHits = Collections.emptyMap();
            enchantSlotHits = Collections.emptyMap();
            categoryCounts = Collections.emptyMap();
            enchantFilterOptions = Collections.emptyList();
            tiersWithItems = Collections.emptyList();
        }
        System.out.println("[gomod] Blitz vault items load failed: " + reason);
    }

    private static Entry parseEntry(JsonObject obj) {
        String id = optString(obj, "id", "");
        int meta = optInt(obj, "meta", 0);
        int count = Math.max(1, optInt(obj, "count", 1));
        String name = optString(obj, "name", "");
        String nbt = optString(obj, "nbt", "");
        List<String> tooltip = new ArrayList<String>();
        if (obj.has("tooltip") && obj.get("tooltip").isJsonArray()) {
            JsonArray a = obj.getAsJsonArray("tooltip");
            for (int i = 0; i < a.size(); i++) {
                JsonElement el = a.get(i);
                if (el != null && !el.isJsonNull()) {
                    tooltip.add(el.getAsString());
                }
            }
        }
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
        String category = classifyCategory(id);
        String tier = classifyTier(id);
        Map<String, Integer> enchants = readEnchantments(stack);
        int sharpnessLevel = getEnchantmentLevel(stack, Enchantment.sharpness);
        int fireAspectLevel = getEnchantmentLevel(stack, Enchantment.fireAspect);
        int knockbackLevel = getEnchantmentLevel(stack, Enchantment.knockback);
        int powerLevel = getEnchantmentLevel(stack, Enchantment.power);
        int flameLevel = getEnchantmentLevel(stack, Enchantment.flame);
        int punchLevel = getEnchantmentLevel(stack, Enchantment.punch);
        int infinityLevel = getEnchantmentLevel(stack, Enchantment.infinity);
        int protectionLevel = getEnchantmentLevel(stack, Enchantment.protection);
        int depthStriderLevel = getEnchantmentLevel(stack, Enchantment.depthStrider);
        int projectileProtectionLevel = getEnchantmentLevel(stack, Enchantment.projectileProtection);
        int fireProtectionLevel = getEnchantmentLevel(stack, Enchantment.fireProtection);
        int blastProtectionLevel = getEnchantmentLevel(stack, Enchantment.blastProtection);
        int featherFallingLevel = getEnchantmentLevel(stack, Enchantment.featherFalling);
        int respirationLevel = getEnchantmentLevel(stack, Enchantment.respiration);
        int aquaAffinityLevel = getEnchantmentLevel(stack, Enchantment.aquaAffinity);
        int thornsLevel = getEnchantmentLevel(stack, Enchantment.thorns);
        int unbreakingLevel = getEnchantmentLevel(stack, Enchantment.unbreaking);
        int observedCount = Math.max(0, optInt(obj, "observed_count", 0));
        int observedSlotCount = Math.max(0, optInt(obj, "observed_slot_count", 0));
        String filterText = buildFilterText(id, name, tooltip, enchants);
        return new Entry(id, meta, count, name, nbt, stack, tooltip, category, tier, enchants,
                sharpnessLevel, fireAspectLevel, knockbackLevel,
                powerLevel, flameLevel, punchLevel, infinityLevel,
                protectionLevel, depthStriderLevel, featherFallingLevel, projectileProtectionLevel,
                fireProtectionLevel, thornsLevel, blastProtectionLevel, respirationLevel,
                aquaAffinityLevel, unbreakingLevel, observedCount, observedSlotCount, filterText);
    }

    private static List<VaultChest> parseVaultChests(JsonObject root) {
        if (root == null || !root.has("vault_chests") || !root.get("vault_chests").isJsonArray()) {
            return Collections.emptyList();
        }
        JsonArray arr = root.getAsJsonArray("vault_chests");
        List<VaultChest> out = new ArrayList<VaultChest>();
        for (int i = 0; i < arr.size(); i++) {
            JsonElement el = arr.get(i);
            if (el == null || !el.isJsonObject()) {
                continue;
            }
            VaultChest chest = parseVaultChest(el.getAsJsonObject(), i + 1);
            if (chest != null && !chest.slots.isEmpty()) {
                out.add(chest);
            }
        }
        return out;
    }

    private static VaultChest parseVaultChest(JsonObject obj, int fallbackIndex) {
        int index = Math.max(1, optInt(obj, "index", fallbackIndex));
        int slotCount = Math.max(0, optInt(obj, "slot_count", 0));
        String capturedAt = optString(obj, "captured_at", "");
        List<VaultSlot> slots = new ArrayList<VaultSlot>();
        if (obj.has("slots") && obj.get("slots").isJsonArray()) {
            JsonArray arr = obj.getAsJsonArray("slots");
            for (int i = 0; i < arr.size(); i++) {
                JsonElement el = arr.get(i);
                if (el == null || !el.isJsonObject()) {
                    continue;
                }
                VaultSlot slot = parseVaultSlot(el.getAsJsonObject());
                if (slot != null) {
                    slots.add(slot);
                    slotCount = Math.max(slotCount, slot.slot + 1);
                }
            }
        }
        Collections.sort(slots, new java.util.Comparator<VaultSlot>() {
            @Override
            public int compare(VaultSlot a, VaultSlot b) {
                return Integer.compare(a.slot, b.slot);
            }
        });
        return new VaultChest(index, Math.max(slotCount, slots.size()), capturedAt, slots);
    }

    private static VaultSlot parseVaultSlot(JsonObject obj) {
        int slot = Math.max(0, optInt(obj, "slot", 0));
        String id = optString(obj, "id", optString(obj, "item", ""));
        int meta = optInt(obj, "meta", 0);
        int count = Math.max(1, optInt(obj, "count", optInt(obj, "amount", 1)));
        String name = optString(obj, "name", optString(obj, "display", ""));
        String nbt = optString(obj, "nbt", "");
        List<String> tooltip = new ArrayList<String>();
        if (obj.has("tooltip") && obj.get("tooltip").isJsonArray()) {
            JsonArray a = obj.getAsJsonArray("tooltip");
            for (int i = 0; i < a.size(); i++) {
                JsonElement el = a.get(i);
                if (el != null && !el.isJsonNull()) {
                    tooltip.add(el.getAsString());
                }
            }
        }
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
        if (tooltip.isEmpty()) {
            tooltip.add(name);
            tooltip.add(id);
        }
        return new VaultSlot(slot, id, meta, count, name, nbt, stack, tooltip);
    }

    private static ItemStack buildStack(String id, int meta, int count, String nbt) {
        ItemStack viaNbt = null;
        if (nbt != null && !nbt.isEmpty()) {
            try {
                NBTTagCompound parsed = JsonToNBT.getTagFromJson(nbt);
                if (parsed != null) {
                    viaNbt = ItemStack.loadItemStackFromNBT(parsed);
                }
            } catch (Throwable t) {
                viaNbt = null;
            }
        }
        if (viaNbt != null && viaNbt.getItem() != null) {
            return viaNbt;
        }
        try {
            Item item = Item.getByNameOrId(id);
            if (item == null) {
                ResourceLocation rl = new ResourceLocation(id);
                item = (Item) Item.itemRegistry.getObject(rl);
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
                int id = entry.getShort("id") & 0xFFFF;
                int lvl = entry.getShort("lvl");
                Enchantment e = Enchantment.getEnchantmentById(id);
                if (e == null) {
                    continue;
                }
                String label;
                try {
                    label = stripFormatting(e.getTranslatedName(lvl));
                } catch (Throwable ignored) {
                    label = e.getName();
                }
                if (label == null || label.isEmpty()) {
                    continue;
                }
                out.put(label, lvl);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static int getEnchantmentLevel(ItemStack stack, Enchantment enchantment) {
        if (stack == null || enchantment == null) {
            return 0;
        }
        try {
            return EnchantmentHelper.getEnchantmentLevel(enchantment.effectId, stack);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String buildFilterText(String id, String displayName, List<String> tooltip,
                                          Map<String, Integer> enchants) {
        StringBuilder sb = new StringBuilder();
        if (id != null) sb.append(id.toLowerCase(Locale.US)).append(' ');
        if (displayName != null) sb.append(displayName.toLowerCase(Locale.US)).append(' ');
        if (tooltip != null) {
            for (String line : tooltip) {
                if (line == null) continue;
                sb.append(stripFormatting(line).toLowerCase(Locale.US)).append(' ');
            }
        }
        if (enchants != null) {
            for (String key : enchants.keySet()) {
                sb.append(key.toLowerCase(Locale.US)).append(' ');
            }
        }
        return sb.toString();
    }

    /** Classifies a Minecraft item id into a vault item category (for chest sorting / filters). */
    public static String classifyItemCategory(String id) {
        return classifyCategory(id);
    }

    /** Classifies a Minecraft item id material tier (for chest sorting / filters). */
    public static String classifyItemTier(String id) {
        return classifyTier(id);
    }

    private static String classifyCategory(String id) {
        if (id == null) return CAT_OTHER;
        String lower = id.toLowerCase(Locale.US);
        if (lower.equals("minecraft:bow")) return CAT_BOW;
        if (lower.equals("minecraft:fishing_rod")) return CAT_ROD;
        if (lower.endsWith("_sword")) return CAT_SWORD;
        if (lower.endsWith("_axe") && !lower.endsWith("_pickaxe")) return CAT_AXE;
        if (lower.endsWith("_pickaxe")) return CAT_PICKAXE;
        if (lower.endsWith("_shovel") || lower.endsWith("_spade")) return CAT_SHOVEL;
        if (lower.endsWith("_hoe")) return CAT_HOE;
        if (lower.endsWith("_helmet")) return CAT_HELMET;
        if (lower.endsWith("_chestplate")) return CAT_CHESTPLATE;
        if (lower.endsWith("_leggings")) return CAT_LEGGINGS;
        if (lower.endsWith("_boots")) return CAT_BOOTS;
        return CAT_OTHER;
    }

    private static String classifyTier(String id) {
        if (id == null) return TIER_NONE;
        String lower = id.toLowerCase(Locale.US);
        if (lower.contains("wooden_") || lower.contains("wood_")) return TIER_WOOD;
        if (lower.contains("stone_")) return TIER_STONE;
        if (lower.contains("iron_")) return TIER_IRON;
        if (lower.contains("golden_") || lower.contains("gold_")) return TIER_GOLD;
        if (lower.contains("diamond_")) return TIER_DIAMOND;
        if (lower.contains("leather_")) return TIER_LEATHER;
        if (lower.contains("chainmail_") || lower.contains("chain_")) return TIER_CHAIN;
        return TIER_NONE;
    }

    private static String stripFormatting(String s) {
        if (s == null) return "";
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

    private static String optString(JsonObject obj, String key, String def) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return def;
        try {
            return obj.get(key).getAsString();
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static int optInt(JsonObject obj, String key, int def) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return def;
        try {
            return obj.get(key).getAsInt();
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static Map<String, Integer> parseStringIntMap(JsonObject root, String key) {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        if (root == null || !root.has(key) || !root.get(key).isJsonObject()) {
            return out;
        }
        JsonObject obj = root.getAsJsonObject(key);
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getValue() == null || entry.getValue().isJsonNull()) {
                continue;
            }
            try {
                out.put(entry.getKey(), Math.max(0, entry.getValue().getAsInt()));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static List<SwordPattern> parseSwordPatterns(JsonObject root) {
        List<SwordPattern> out = new ArrayList<SwordPattern>();
        if (root == null || !root.has("sword_patterns") || !root.get("sword_patterns").isJsonArray()) {
            return out;
        }
        JsonArray arr = root.getAsJsonArray("sword_patterns");
        for (int i = 0; i < arr.size(); i++) {
            JsonElement el = arr.get(i);
            if (el == null || !el.isJsonObject()) {
                continue;
            }
            JsonObject obj = el.getAsJsonObject();
            String label = optString(obj, "label", "");
            int count = Math.max(0, optInt(obj, "count", 0));
            if (label == null || label.isEmpty() || count <= 0) {
                continue;
            }
            out.add(new SwordPattern(label, count));
        }
        return out;
    }

    private static final java.util.Comparator<Entry> COMPARATOR = new java.util.Comparator<Entry>() {
        @Override
        public int compare(Entry a, Entry b) {
            int c = Integer.compare(categoryRank(a.category), categoryRank(b.category));
            if (c != 0) return c;
            int t = Integer.compare(tierRank(a), tierRank(b));
            if (t != 0) return t;
            int enchantPriority = compareEnchantPriority(a, b);
            if (enchantPriority != 0) return enchantPriority;
            int enchantCount = Integer.compare(b.enchantments.size(), a.enchantments.size());
            if (enchantCount != 0) return enchantCount;
            int n = a.id.compareTo(b.id);
            if (n != 0) return n;
            return a.filterText.compareTo(b.filterText);
        }
    };

    private static int categoryRank(String cat) {
        int idx = CATEGORY_ORDER.indexOf(cat);
        return idx < 0 ? CATEGORY_ORDER.size() : idx;
    }

    private static int tierRank(String tier) {
        int idx = TIER_ORDER.indexOf(tier);
        return idx < 0 ? TIER_ORDER.size() : idx;
    }

    private static int tierRank(Entry entry) {
        if (entry == null) {
            return TIER_ORDER.size();
        }
        if (isWeaponOrToolCategory(entry.category)) {
            if (TIER_DIAMOND.equals(entry.tier)) return 0;
            if (TIER_IRON.equals(entry.tier)) return 1;
            if (TIER_STONE.equals(entry.tier)) return 2;
            if (TIER_GOLD.equals(entry.tier)) return 3;
            if (TIER_WOOD.equals(entry.tier)) return 4;
            return 5;
        }
        if (isGearCategory(entry.category)) {
            if (TIER_DIAMOND.equals(entry.tier)) return 0;
            if (TIER_IRON.equals(entry.tier)) return 1;
            if (TIER_CHAIN.equals(entry.tier)) return 2;
            if (TIER_GOLD.equals(entry.tier)) return 3;
            if (TIER_LEATHER.equals(entry.tier)) return 4;
            return 5;
        }
        return tierRank(entry.tier);
    }

    private static boolean isWeaponOrToolCategory(String category) {
        return CAT_SWORD.equals(category)
                || CAT_AXE.equals(category)
                || CAT_PICKAXE.equals(category)
                || CAT_SHOVEL.equals(category)
                || CAT_HOE.equals(category);
    }

    private static boolean isGearCategory(String category) {
        return CAT_HELMET.equals(category)
                || CAT_CHESTPLATE.equals(category)
                || CAT_LEGGINGS.equals(category)
                || CAT_BOOTS.equals(category);
    }

    private static int compareEnchantPriority(Entry a, Entry b) {
        if (a == null || b == null) {
            return 0;
        }
        if (isWeaponOrToolCategory(a.category) && isWeaponOrToolCategory(b.category)) {
            int sharpness = Integer.compare(b.sharpnessLevel, a.sharpnessLevel);
            if (sharpness != 0) return sharpness;
            int fireAspect = Integer.compare(b.fireAspectLevel, a.fireAspectLevel);
            if (fireAspect != 0) return fireAspect;
            int knockback = Integer.compare(b.knockbackLevel, a.knockbackLevel);
            if (knockback != 0) return knockback;
            return 0;
        }
        if (CAT_BOW.equals(a.category) && CAT_BOW.equals(b.category)) {
            int power = Integer.compare(b.powerLevel, a.powerLevel);
            if (power != 0) return power;
            int flame = Integer.compare(b.flameLevel, a.flameLevel);
            if (flame != 0) return flame;
            int punch = Integer.compare(b.punchLevel, a.punchLevel);
            if (punch != 0) return punch;
            int infinity = Integer.compare(b.infinityLevel, a.infinityLevel);
            if (infinity != 0) return infinity;
            return 0;
        }
        if (isGearCategory(a.category) && isGearCategory(b.category)) {
            int protection = Integer.compare(b.protectionLevel, a.protectionLevel);
            if (protection != 0) return protection;
            int depthStrider = Integer.compare(b.depthStriderLevel, a.depthStriderLevel);
            if (depthStrider != 0) return depthStrider;
            int featherFalling = Integer.compare(b.featherFallingLevel, a.featherFallingLevel);
            if (featherFalling != 0) return featherFalling;
            int projectileProtection = Integer.compare(b.projectileProtectionLevel, a.projectileProtectionLevel);
            if (projectileProtection != 0) return projectileProtection;
            int fireProtection = Integer.compare(b.fireProtectionLevel, a.fireProtectionLevel);
            if (fireProtection != 0) return fireProtection;
            int thorns = Integer.compare(b.thornsLevel, a.thornsLevel);
            if (thorns != 0) return thorns;
            int blastProtection = Integer.compare(b.blastProtectionLevel, a.blastProtectionLevel);
            if (blastProtection != 0) return blastProtection;
            int respiration = Integer.compare(b.respirationLevel, a.respirationLevel);
            if (respiration != 0) return respiration;
            int aquaAffinity = Integer.compare(b.aquaAffinityLevel, a.aquaAffinityLevel);
            if (aquaAffinity != 0) return aquaAffinity;
            int unbreaking = Integer.compare(b.unbreakingLevel, a.unbreakingLevel);
            if (unbreaking != 0) return unbreaking;
            return 0;
        }
        return 0;
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
        public final String tier;
        public final Map<String, Integer> enchantments;
        public final int sharpnessLevel;
        public final int fireAspectLevel;
        public final int knockbackLevel;
        public final int powerLevel;
        public final int flameLevel;
        public final int punchLevel;
        public final int infinityLevel;
        public final int protectionLevel;
        public final int depthStriderLevel;
        public final int featherFallingLevel;
        public final int projectileProtectionLevel;
        public final int fireProtectionLevel;
        public final int thornsLevel;
        public final int blastProtectionLevel;
        public final int respirationLevel;
        public final int aquaAffinityLevel;
        public final int unbreakingLevel;
        public final int observedCount;
        public final int observedSlotCount;
        public final String filterText;

        Entry(String id, int meta, int count, String displayName, String nbtRaw, ItemStack stack,
              List<String> fallbackTooltip, String category, String tier,
              Map<String, Integer> enchantments, int sharpnessLevel, int fireAspectLevel,
              int knockbackLevel, int powerLevel, int flameLevel, int punchLevel, int infinityLevel,
              int protectionLevel, int depthStriderLevel, int featherFallingLevel, int projectileProtectionLevel,
              int fireProtectionLevel, int thornsLevel, int blastProtectionLevel, int respirationLevel,
              int aquaAffinityLevel, int unbreakingLevel, int observedCount, int observedSlotCount,
              String filterText) {
            this.id = id;
            this.meta = meta;
            this.count = count;
            this.displayName = displayName;
            this.nbtRaw = nbtRaw == null ? "" : nbtRaw;
            this.stack = stack;
            this.fallbackTooltip = Collections.unmodifiableList(new ArrayList<String>(fallbackTooltip));
            this.category = category;
            this.tier = tier;
            this.enchantments = Collections.unmodifiableMap(new LinkedHashMap<String, Integer>(enchantments));
            this.sharpnessLevel = sharpnessLevel;
            this.fireAspectLevel = fireAspectLevel;
            this.knockbackLevel = knockbackLevel;
            this.powerLevel = powerLevel;
            this.flameLevel = flameLevel;
            this.punchLevel = punchLevel;
            this.infinityLevel = infinityLevel;
            this.protectionLevel = protectionLevel;
            this.depthStriderLevel = depthStriderLevel;
            this.featherFallingLevel = featherFallingLevel;
            this.projectileProtectionLevel = projectileProtectionLevel;
            this.fireProtectionLevel = fireProtectionLevel;
            this.thornsLevel = thornsLevel;
            this.blastProtectionLevel = blastProtectionLevel;
            this.respirationLevel = respirationLevel;
            this.aquaAffinityLevel = aquaAffinityLevel;
            this.unbreakingLevel = unbreakingLevel;
            this.observedCount = observedCount;
            this.observedSlotCount = observedSlotCount;
            this.filterText = filterText;
        }
    }

    public static final class SwordPattern {
        public final String label;
        public final int count;

        SwordPattern(String label, int count) {
            this.label = label;
            this.count = count;
        }
    }

    public static final class VaultChest {
        public final int index;
        public final int slotCount;
        public final String capturedAt;
        public final List<VaultSlot> slots;

        VaultChest(int index, int slotCount, String capturedAt, List<VaultSlot> slots) {
            this.index = Math.max(1, index);
            this.slotCount = Math.max(0, slotCount);
            this.capturedAt = capturedAt == null ? "" : capturedAt;
            this.slots = Collections.unmodifiableList(new ArrayList<VaultSlot>(slots));
        }
    }

    public static final class VaultSlot {
        public final int slot;
        public final String id;
        public final int meta;
        public final int count;
        public final String displayName;
        public final String nbtRaw;
        public final ItemStack stack;
        public final List<String> fallbackTooltip;

        VaultSlot(int slot, String id, int meta, int count, String displayName, String nbtRaw,
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

    /** One row in the vault enchant odds panel: chance a chest / slot has this enchant. */
    public static final class VaultEnchantChance {
        public final String label;
        public final int chestCount;
        public final int slotCount;

        VaultEnchantChance(String label, int chestCount, int slotCount) {
            this.label = label == null ? "" : label;
            this.chestCount = Math.max(0, chestCount);
            this.slotCount = Math.max(0, slotCount);
        }
    }
}
