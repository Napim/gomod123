package me.napim.gomod.features;

import me.napim.gomod.Main;
import me.napim.gomod.util.ClientGuiHelper;
import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import org.lwjgl.input.Keyboard;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.event.RenderGameOverlayEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AskForLoot {
    private static final double MIN_IMPROVEMENT = 1.0E-9D;
    private static final long TRIGGER_COOLDOWN_MS = 500L;
    private static final int SLOT_BOOTS = 1;
    private static final int SLOT_LEGGINGS = 2;
    private static final int SLOT_CHESTPLATE = 3;
    private static final int SLOT_HELMET = 4;

    private static boolean enabled = false;
    private static boolean allTime = false;
    private static int keyCode = 0;
    private static boolean configLoaded = false;
    private static long lastAnnouncedMs = 0L;
    private static boolean wasKeyDown = false;
    private static boolean ignoreBoundKeyUntilRelease = false;

    private static final class ArmorOption {
        private final Item item;
        private final String label;
        private final int rank;
        private final int tiePriority;

        private ArmorOption(Item item, String label, int rank, int tiePriority) {
            this.item = item;
            this.label = label;
            this.rank = rank;
            this.tiePriority = tiePriority;
        }
    }

    /**
     * Blitz SG chests: iron max for helm / chest / boots; chainmail max for leggings.
     * We detect ALL tiers (diamond from kits/PvP) so we never ask for chest iron when already in diamond.
     * We only REQUEST tiers that actually drop from chests.
     */
    private static final int BLITZ_SATISFIED_HELM = 2;
    private static final int BLITZ_SATISFIED_CHEST = 2;
    private static final int BLITZ_SATISFIED_BOOTS = 2;
    /** Chainmail tier (rank 2) or better — chests never give iron leggings. */
    private static final int BLITZ_SATISFIED_LEGGINGS = 2;

    /** Includes diamond etc. for "what you already have" */
    private static final ArmorOption[] HELMET_DETECT = new ArmorOption[]{
            new ArmorOption(Items.leather_helmet, "Leather Helmet", 0, 0),
            new ArmorOption(Items.chainmail_helmet, "Chainmail Helmet", 1, 2),
            new ArmorOption(Items.golden_helmet, "Gold Helmet", 1, 3),
            new ArmorOption(Items.iron_helmet, "Iron Helmet", 2, 4),
            new ArmorOption(Items.diamond_helmet, "Diamond Helmet", 3, 5)
    };

    private static final ArmorOption[] HELMET_REQUEST = new ArmorOption[]{
            new ArmorOption(Items.leather_helmet, "Leather Helmet", 0, 0),
            new ArmorOption(Items.chainmail_helmet, "Chainmail Helmet", 1, 2),
            new ArmorOption(Items.golden_helmet, "Gold Helmet", 1, 3),
            new ArmorOption(Items.iron_helmet, "Iron Helmet", 2, 4)
    };

    private static final ArmorOption[] CHEST_DETECT = new ArmorOption[]{
            new ArmorOption(Items.leather_chestplate, "Leather cp", 0, 0),
            new ArmorOption(Items.chainmail_chestplate, "Chainmail cp", 1, 2),
            new ArmorOption(Items.golden_chestplate, "Gold cp", 1, 3),
            new ArmorOption(Items.iron_chestplate, "Iron cp", 2, 4),
            new ArmorOption(Items.diamond_chestplate, "Diamond cp", 3, 5)
    };

    private static final ArmorOption[] CHEST_REQUEST = new ArmorOption[]{
            new ArmorOption(Items.leather_chestplate, "Leather cp", 0, 0),
            new ArmorOption(Items.chainmail_chestplate, "Chainmail cp", 1, 2),
            new ArmorOption(Items.golden_chestplate, "Gold cp", 1, 3),
            new ArmorOption(Items.iron_chestplate, "Iron cp", 2, 4)
    };

    /** Ranks: gold 1, chain 2 (chest cap), iron 3, diamond 4 — so chain+ counts as "geared" for chest loot. */
    private static final ArmorOption[] LEGGINGS_DETECT = new ArmorOption[]{
            new ArmorOption(Items.leather_leggings, "Leather Leggings", 0, 0),
            new ArmorOption(Items.golden_leggings, "Gold Leggings", 1, 3),
            new ArmorOption(Items.chainmail_leggings, "Chainmail Leggings", 2, 4),
            new ArmorOption(Items.iron_leggings, "Iron Leggings", 3, 5),
            new ArmorOption(Items.diamond_leggings, "Diamond Leggings", 4, 6)
    };

    private static final ArmorOption[] LEGGINGS_REQUEST = new ArmorOption[]{
            new ArmorOption(Items.leather_leggings, "Leather Leggings", 0, 0),
            new ArmorOption(Items.golden_leggings, "Gold Leggings", 1, 3),
            new ArmorOption(Items.chainmail_leggings, "Chainmail Leggings", 2, 4)
    };

    private static final ArmorOption[] BOOTS_DETECT = new ArmorOption[]{
            new ArmorOption(Items.leather_boots, "Leather Boots", 0, 0),
            new ArmorOption(Items.chainmail_boots, "Chainmail Boots", 1, 2),
            new ArmorOption(Items.golden_boots, "Gold Boots", 1, 3),
            new ArmorOption(Items.iron_boots, "Iron Boots", 2, 4),
            new ArmorOption(Items.diamond_boots, "Diamond Boots", 3, 5)
    };

    private static final ArmorOption[] BOOTS_REQUEST = new ArmorOption[]{
            new ArmorOption(Items.leather_boots, "Leather Boots", 0, 0),
            new ArmorOption(Items.chainmail_boots, "Chainmail Boots", 1, 2),
            new ArmorOption(Items.golden_boots, "Gold Boots", 1, 3),
            new ArmorOption(Items.iron_boots, "Iron Boots", 2, 4)
    };

    public static boolean isEnabled() {
        ensureInitialized();
        return enabled;
    }

    public static void setEnabled(boolean value) {
        ensureInitialized();
        enabled = value;
        ConfigManager.setAskForLootEnabled(value);
        ConfigManager.saveConfigNow();
    }

    public static boolean isAllTimeEnabled() {
        ensureInitialized();
        return allTime;
    }

    public static void setAllTimeEnabled(boolean value) {
        ensureInitialized();
        allTime = value;
        ConfigManager.setAskForLootAllTime(value);
        if (value && !enabled) {
            enabled = true;
            ConfigManager.setAskForLootEnabled(true);
        }
        ConfigManager.saveConfigNow();
    }

    public static String toggleAllTime() {
        setAllTimeEnabled(!isAllTimeEnabled());
        return Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Ask for Loot all-time mode "
                + (allTime ? EnumChatFormatting.GREEN + "on" : EnumChatFormatting.RED + "off")
                + EnumChatFormatting.GRAY + " — key works outside Blitz Teams (e.g. singleplayer); sends chat to your session.";
    }

    public static void loadFromConfig() {
        enabled = ConfigManager.isAskForLootEnabled();
        allTime = ConfigManager.isAskForLootAllTime();
        keyCode = ConfigManager.getAskForLootKeyCode();
        configLoaded = true;
    }

    public static void ensureInitialized() {
        if (!configLoaded) {
            loadFromConfig();
        }
    }

    public static int getKeyCode() {
        ensureInitialized();
        return keyCode;
    }

    public static void setKeyCode(int value) {
        ensureInitialized();
        keyCode = value;
        ignoreBoundKeyUntilRelease = value > 0;
        ConfigManager.setAskForLootKeyCode(value);
        ConfigManager.saveConfigNow();
    }

    /**
     * When {@link #allTime} is off, the key bind only runs in this context: active Blitz SG (Taunt on sidebar),
     * on Hypixel, not lobby/pregame/replay. When all-time is on, this check is skipped (see {@link #handleRenderOverlay}).
     */
    private static boolean isAskLootAllowedContext(Minecraft mc) {
        if (mc == null || mc.theWorld == null) {
            return false;
        }
        if (!GameContext.isOnHypixel(mc)) {
            return false;
        }
        if (GameContext.isHypixelReplayWatching(mc)) {
            return false;
        }
        if (GameContext.isHypixelLobby(mc)) {
            return false;
        }
        if (GameContext.isBlitzPregameLobby(mc)) {
            return false;
        }
        return GameContext.isBlitzGameActive(mc);
    }

    public static void handleRenderOverlay(RenderGameOverlayEvent event) {
        ensureInitialized();
        if (!enabled) {
            wasKeyDown = false;
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            wasKeyDown = false;
            return;
        }
        if (ClientGuiHelper.shouldSuppressWorldKeybinds(mc)) {
            wasKeyDown = false;
            return;
        }

        if (keyCode <= 0) {
            wasKeyDown = false;
            return;
        }

        boolean isKeyDown = Keyboard.isKeyDown(keyCode);
        if (ignoreBoundKeyUntilRelease) {
            if (isKeyDown) {
                wasKeyDown = true;
                return;
            }
            ignoreBoundKeyUntilRelease = false;
            wasKeyDown = false;
            return;
        }
        if (!isKeyDown) {
            wasKeyDown = false;
            return;
        }

        if (wasKeyDown) {
            return;
        }
        wasKeyDown = true;

        if (!allTime) {
            // Hypixel Blitz Teams live match only — never solo BSG, lobby, pregame, replay, or singleplayer.
            if (!isAskLootAllowedContext(mc) || !BlitzTeamsDetector.isBlitzTeamsMatchLive(mc)) {
                return;
            }
        }

        long now = System.currentTimeMillis();
        if (now - lastAnnouncedMs < TRIGGER_COOLDOWN_MS) {
            return;
        }

        InventoryPlayer inv = mc.thePlayer.inventory;
        List<String> needs = collectNeeds(inv);
        String lvlXp = formatLevelXpSuffix(mc);
        String carrying = formatCarryingSuffix(inv);
        if (needs.isEmpty()) {
            String line = "I don't need anything" + carrying + lvlXp;
            try {
                mc.thePlayer.sendChatMessage(line);
                lastAnnouncedMs = now;
            } catch (Throwable ignored) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + line);
                lastAnnouncedMs = now;
            }
            return;
        }

        String needList = joinNeedPhrases(needs);
        String request = "I need " + needList + "." + carrying + lvlXp;
        try {
            mc.thePlayer.sendChatMessage(request);
            lastAnnouncedMs = now;
        } catch (Throwable ignored) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + request);
            lastAnnouncedMs = now;
        }
    }

    /** Hotbar XP bar: level + fill % (matches Hypixel network bar when disguised / in lobby). */
    private static String formatLevelXpSuffix(Minecraft mc) {
        if (mc == null || mc.thePlayer == null) {
            return "";
        }
        int lvl = mc.thePlayer.experienceLevel;
        int expBottleCount = countItem(mc.thePlayer.inventory, Items.experience_bottle);
        float bar = mc.thePlayer.experience;
        if (Float.isNaN(bar) || Float.isInfinite(bar)) {
            bar = 0f;
        }
        int pct = Math.round(bar * 100f);
        if (pct < 0) {
            pct = 0;
        }
        if (pct > 100) {
            pct = 100;
        }
        StringBuilder suffix = new StringBuilder();
        suffix.append(" [LVL ").append(lvl).append(" XP ").append(pct).append("%");
        if (expBottleCount > 0) {
            suffix.append(" / x").append(expBottleCount).append(" EXP_BOTTLES");
        }
        suffix.append(']');
        return suffix.toString();
    }

    private static int countItem(InventoryPlayer inventory, Item item) {
        if (inventory == null || item == null) {
            return 0;
        }
        int total = 0;
        if (inventory.mainInventory != null) {
            for (ItemStack stack : inventory.mainInventory) {
                if (stack != null && stack.getItem() == item) {
                    total += stack.stackSize;
                }
            }
        }
        if (inventory.armorInventory != null) {
            for (ItemStack stack : inventory.armorInventory) {
                if (stack != null && stack.getItem() == item) {
                    total += stack.stackSize;
                }
            }
        }
        return total;
    }

    /** Combat enchants first, then looting / unbreaking — matches typical Blitz weapon readout order. */
    private static final int[] WEAPON_ENCHANT_DISPLAY_ORDER = new int[]{
            Enchantment.sharpness.effectId,
            Enchantment.smite.effectId,
            Enchantment.baneOfArthropods.effectId,
            Enchantment.knockback.effectId,
            Enchantment.fireAspect.effectId,
            Enchantment.looting.effectId,
            Enchantment.unbreaking.effectId,
    };

    private static String joinNeedPhrases(List<String> rawNeeds) {
        List<String> phrases = new ArrayList<>(rawNeeds.size());
        for (String raw : rawNeeds) {
            phrases.add(articleForNeed(raw));
        }
        if (phrases.size() == 1) {
            return phrases.get(0);
        }
        if (phrases.size() == 2) {
            return phrases.get(0) + " and " + phrases.get(1);
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < phrases.size(); i++) {
            if (i > 0) {
                b.append(i == phrases.size() - 1 ? ", and " : ", ");
            }
            b.append(phrases.get(i));
        }
        return b.toString();
    }

    /**
     * {@code rod} → {@code a rod}; {@code any helmet} unchanged; named armor → {@code a/an …}.
     */
    private static String articleForNeed(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        if ("rod".equalsIgnoreCase(raw)) {
            return "a rod";
        }
        if (raw.regionMatches(true, 0, "any ", 0, 4)) {
            return raw;
        }
        char c = Character.toLowerCase(raw.charAt(0));
        if (c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u') {
            return "an " + raw;
        }
        return "a " + raw;
    }

    /**
     * Best sword or axe by material tier then combat enchant score (same weighting as {@link UselessItemOverlay}).
     */
    private static String formatCarryingSuffix(InventoryPlayer inventory) {
        StringBuilder b = new StringBuilder();
        long[] rankHolder = new long[]{-1L};
        ItemStack best = findBestMeleeWeapon(inventory, rankHolder);
        long bestRank = rankHolder[0];
        if (best == null || bestRank < 0L) {
            b.append(" I have no sword or axe");
        } else {
            int n = countMeleeWeaponsTiedWithBest(inventory, best, bestRank);
            b.append(' ').append(formatIHaveWeaponLine(n, best));
        }
        int rods = countItem(inventory, Items.fishing_rod);
        if (rods > 0) {
            b.append(rods == 1 ? " (1 rod)" : " (").append(rods).append(" rods)");
        }
        return b.toString();
    }

    /** @param rankOut if non-null and length ≥ 1, filled with best rank or {@code -1} */
    private static ItemStack findBestMeleeWeapon(InventoryPlayer inventory, long[] rankOut) {
        if (inventory == null) {
            if (rankOut != null && rankOut.length > 0) {
                rankOut[0] = -1L;
            }
            return null;
        }
        ItemStack best = null;
        long bestRank = -1L;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack == null || stack.getItem() == null) {
                continue;
            }
            Item item = stack.getItem();
            if (!(item instanceof ItemSword) && !(item instanceof ItemAxe)) {
                continue;
            }
            int tier = meleeMaterialTier(item);
            if (tier < 0) {
                continue;
            }
            long rank = (long) tier * 100000L + (long) weaponCombatEnchantScore(stack);
            if (rank > bestRank) {
                bestRank = rank;
                best = stack;
            }
        }
        if (rankOut != null && rankOut.length > 0) {
            rankOut[0] = bestRank;
        }
        return best;
    }

    /** Total item count across hotbar+inv for stacks that tie the best (same item + same combat rank). */
    private static int countMeleeWeaponsTiedWithBest(InventoryPlayer inventory, ItemStack best, long bestRank) {
        if (inventory == null || best == null || best.getItem() == null || bestRank < 0L) {
            return 0;
        }
        Item bestItem = best.getItem();
        if (!(bestItem instanceof ItemSword) && !(bestItem instanceof ItemAxe)) {
            return 0;
        }
        int tier = meleeMaterialTier(bestItem);
        if (tier < 0) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inventory.getStackInSlot(i);
            if (s == null || s.getItem() != bestItem) {
                continue;
            }
            long rank = (long) tier * 100000L + (long) weaponCombatEnchantScore(s);
            if (rank == bestRank) {
                total += s.stackSize;
            }
        }
        return Math.max(total, 1);
    }

    private static String formatIHaveWeaponLine(int count, ItemStack best) {
        String desc = describeWeaponForLootCall(best);
        String casual = desc.toLowerCase(Locale.ROOT);
        if (count <= 1) {
            return "I have a " + casual;
        }
        return "I have " + count + " " + pluralizeWeaponLootPhrase(casual);
    }

    /** {@code casual} is already lower-case; ends with {@code sword} or {@code axe}. */
    private static String pluralizeWeaponLootPhrase(String casual) {
        if (casual.endsWith(" sword")) {
            return casual.substring(0, casual.length() - " sword".length()) + " swords";
        }
        if (casual.endsWith(" axe")) {
            return casual.substring(0, casual.length() - " axe".length()) + " axes";
        }
        return casual + "s";
    }

    /** Vanilla Blitz melee tiers: wood and gold share tier 0, then stone, iron, diamond. */
    private static int meleeMaterialTier(Item item) {
        if (item == null) {
            return -1;
        }
        if (item == Items.wooden_sword || item == Items.golden_sword
                || item == Items.wooden_axe || item == Items.golden_axe) {
            return 0;
        }
        if (item == Items.stone_sword || item == Items.stone_axe) {
            return 1;
        }
        if (item == Items.iron_sword || item == Items.iron_axe) {
            return 2;
        }
        if (item == Items.diamond_sword || item == Items.diamond_axe) {
            return 3;
        }
        return -1;
    }

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

    /**
     * Lower-case material + sword/axe only — never {@link ItemStack#getDisplayName()} so Hypixel / anvil renames
     * (e.g. "mega-cool stone sword") are not broadcast to team chat.
     */
    private static String vanillaMeleeLootBaseName(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return "weapon";
        }
        Item item = stack.getItem();
        if (item == Items.wooden_sword) {
            return "wooden sword";
        }
        if (item == Items.golden_sword) {
            return "golden sword";
        }
        if (item == Items.stone_sword) {
            return "stone sword";
        }
        if (item == Items.iron_sword) {
            return "iron sword";
        }
        if (item == Items.diamond_sword) {
            return "diamond sword";
        }
        if (item == Items.wooden_axe) {
            return "wooden axe";
        }
        if (item == Items.golden_axe) {
            return "golden axe";
        }
        if (item == Items.stone_axe) {
            return "stone axe";
        }
        if (item == Items.iron_axe) {
            return "iron axe";
        }
        if (item == Items.diamond_axe) {
            return "diamond axe";
        }
        return "weapon";
    }

    /** e.g. {@code sharpness i stone sword} after {@link #formatIHaveWeaponLine} lower-casing (enchants + vanilla type only). */
    private static String describeWeaponForLootCall(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return "fists";
        }
        List<String> enchantBits = new ArrayList<>();
        for (int eid : WEAPON_ENCHANT_DISPLAY_ORDER) {
            int lvl = EnchantmentHelper.getEnchantmentLevel(eid, stack);
            if (lvl <= 0) {
                continue;
            }
            Enchantment e = Enchantment.getEnchantmentById(eid);
            if (e != null) {
                String bit = EnumChatFormatting.getTextWithoutFormattingCodes(e.getTranslatedName(lvl));
                if (bit != null && !bit.isEmpty()) {
                    enchantBits.add(bit.trim());
                }
            }
        }
        String plainName = vanillaMeleeLootBaseName(stack);
        if (enchantBits.isEmpty()) {
            return plainName;
        }
        return String.join(" ", enchantBits) + " " + plainName;
    }

    private static List<String> collectNeeds(InventoryPlayer inventory) {
        List<String> needs = new ArrayList<>();
        if (!hasItem(inventory, Items.fishing_rod)) {
            needs.add("rod");
        }

        ItemStack[] currentSet = (ItemStack[]) inventory.armorInventory.clone();
        maybeAddNextArmorNeed(needs, inventory, currentSet, SLOT_HELMET, HELMET_DETECT, HELMET_REQUEST, BLITZ_SATISFIED_HELM, "any helmet");
        maybeAddNextArmorNeed(needs, inventory, currentSet, SLOT_CHESTPLATE, CHEST_DETECT, CHEST_REQUEST, BLITZ_SATISFIED_CHEST, "any chestplate");
        maybeAddNextArmorNeed(needs, inventory, currentSet, SLOT_LEGGINGS, LEGGINGS_DETECT, LEGGINGS_REQUEST, BLITZ_SATISFIED_LEGGINGS, "any leggings");
        maybeAddNextArmorNeed(needs, inventory, currentSet, SLOT_BOOTS, BOOTS_DETECT, BOOTS_REQUEST, BLITZ_SATISFIED_BOOTS, "any boots");
        return needs;
    }

    private static void maybeAddNextArmorNeed(
            List<String> needs,
            InventoryPlayer inventory,
            ItemStack[] currentSet,
            int slot,
            ArmorOption[] detectOptions,
            ArmorOption[] requestOptions,
            int satisfiedAtOrAboveRank,
            String anyLabel
    ) {
        int bestOwnedRank = getBestOwnedRank(inventory, slot, detectOptions);
        if (bestOwnedRank < 0) {
            needs.add(anyLabel);
            return;
        }
        // Already at chest cap or better (e.g. iron/diamond helm, chain+/iron/diamond leggings from kit or kills).
        if (bestOwnedRank >= satisfiedAtOrAboveRank) {
            return;
        }

        ArmorOption nextWanted = getPreferredOptionForRank(requestOptions, bestOwnedRank + 1);
        if (nextWanted == null) {
            return;
        }

        double delta = BestArmorDisplay.getProtectionDeltaForArmorSet(currentSet, new ItemStack(nextWanted.item));
        if (delta > MIN_IMPROVEMENT) {
            needs.add(nextWanted.label);
        }
    }

    private static int getBestOwnedRank(InventoryPlayer inventory, int slot, ArmorOption[] options) {
        if (inventory == null) {
            return -1;
        }

        int bestRank = -1;
        int bestTiePriority = -1;

        if (inventory.mainInventory != null) {
            for (ItemStack stack : inventory.mainInventory) {
                int[] result = considerOwnedArmorStack(stack, slot, options, bestRank, bestTiePriority);
                bestRank = result[0];
                bestTiePriority = result[1];
            }
        }

        if (inventory.armorInventory != null) {
            for (ItemStack stack : inventory.armorInventory) {
                int[] result = considerOwnedArmorStack(stack, slot, options, bestRank, bestTiePriority);
                bestRank = result[0];
                bestTiePriority = result[1];
            }
        }

        return bestRank;
    }

    private static int[] considerOwnedArmorStack(
            ItemStack stack,
            int slot,
            ArmorOption[] options,
            int currentBestRank,
            int currentBestTiePriority
    ) {
        if (stack == null || !(stack.getItem() instanceof ItemArmor)) {
            return new int[]{currentBestRank, currentBestTiePriority};
        }

        ItemArmor armor = (ItemArmor) stack.getItem();
        int armorSlot = 4 - armor.armorType;
        if (armorSlot != slot) {
            return new int[]{currentBestRank, currentBestTiePriority};
        }

        ArmorOption option = getOptionForItem(options, stack.getItem());
        if (option == null) {
            return new int[]{currentBestRank, currentBestTiePriority};
        }

        if (option.rank > currentBestRank) {
            return new int[]{option.rank, option.tiePriority};
        }

        if (option.rank == currentBestRank && option.tiePriority > currentBestTiePriority) {
            return new int[]{option.rank, option.tiePriority};
        }

        return new int[]{currentBestRank, currentBestTiePriority};
    }

    private static ArmorOption getPreferredOptionForRank(ArmorOption[] options, int rank) {
        ArmorOption best = null;
        for (ArmorOption option : options) {
            if (option.rank != rank) {
                continue;
            }
            if (best == null || option.tiePriority > best.tiePriority) {
                best = option;
            }
        }
        return best;
    }

    private static ArmorOption getOptionForItem(ArmorOption[] options, Item item) {
        if (item == null) {
            return null;
        }
        for (ArmorOption option : options) {
            if (option.item == item) {
                return option;
            }
        }
        return null;
    }

    private static boolean hasItem(InventoryPlayer inventory, Item item) {
        for (int i = 0; i < inventory.getSizeInventory(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack != null && stack.getItem() == item) {
                return true;
            }
        }
        return false;
    }
}
