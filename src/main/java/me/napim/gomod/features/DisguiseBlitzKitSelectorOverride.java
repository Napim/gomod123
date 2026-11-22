/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.google.gson.JsonArray
 *  com.google.gson.JsonElement
 *  com.google.gson.JsonObject
 *  com.google.gson.JsonParser
 *  net.minecraft.block.Block
 *  net.minecraft.client.Minecraft
 *  net.minecraft.entity.player.EntityPlayer
 *  net.minecraft.init.Blocks
 *  net.minecraft.init.Items
 *  net.minecraft.inventory.Container
 *  net.minecraft.inventory.ContainerChest
 *  net.minecraft.inventory.IInventory
 *  net.minecraft.inventory.Slot
 *  net.minecraft.item.Item
 *  net.minecraft.item.ItemArmor
 *  net.minecraft.item.ItemFood
 *  net.minecraft.item.ItemStack
 *  net.minecraft.nbt.NBTBase
 *  net.minecraft.nbt.NBTTagCompound
 *  net.minecraft.nbt.NBTTagList
 *  net.minecraft.nbt.NBTTagString
 *  net.minecraft.util.EnumChatFormatting
 */
package me.napim.gomod.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.napim.gomod.features.BlitzTeamsDetector;
import me.napim.gomod.features.KitEnchantmentsLoader;
import me.napim.gomod.features.KitLevelsManager;
import me.napim.gomod.features.LocalDisguise;
import me.napim.gomod.features.MixinStatus;
import me.napim.gomod.util.GameContext;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.EnumChatFormatting;

public final class DisguiseBlitzKitSelectorOverride {
    private static final int HIDE_ATTRIBUTES_FLAG = 2;
    private static final int HIDE_ALL_TOOLTIP_FLAGS = 255;
    private static final String SELECTOR_TOOLTIP_TAG = "gomodSelectorTooltip";
    private static final String LORE_BULLET = "\u25AA";
    private static final String PRESTIGE_ONE_SUFFIX = " \u272B";
    private static final String PRESTIGE_TWO_SUFFIX = " \u272B\u272B";
    private static final String REQUIREMENT_MET_PREFIX = "\u2714 ";
    private static final String REQUIREMENT_MISSING_PREFIX = "\u2718 ";
    private static final String PRESTIGE_STAR = "\u272B";
    private static final String PRESTIGE_REQUIREMENT_MARK = "\u2714";
    private static final String PRESTIGE_REQUIREMENT_MISSING_MARK = "\u2718";
    private static final String LORE_LINE_PATTERN = "^(\\s*)(?:\\u25AA|\\?)\\s+(.+)$";
    private static final String INDENTED_LORE_BULLET = "  " + LORE_BULLET + " ";
    private static final int PRESTIGE_TWO_REQUIRED_WINS = 500;
    private static final int PRESTIGE_TWO_REQUIRED_KILLS = 5000;
    private static final String BLITZ_SELECTOR_CAPTURES_RESOURCE = "/blitz_selector_captures.json";
    private static final String SHARK_HEAD_TEXTURE_VALUE = "eyJ0aW1lc3RhbXAiOjE0NzkyNDk3ODM4MjMsInByb2ZpbGVJZCI6Ijg3N2I3YzdkNTJkYjQxNDg4ZGVmN2NjYWY2ODQxOGRiIiwicHJvZmlsZU5hbWUiOiJTaHJha2V5Iiwic2lnbmF0dXJlUmVxdWlyZWQiOnRydWUsInRleHR1cmVzIjp7IlNLSU4iOnsidXJsIjoiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS81MWUwNTcyN2Q2NGZmNjk3ZTI3YTEzZjY5ZDg4MTMxZjRlN2Q5YjgxZDE4ZTFjMjU4MWIxY2RjNDQ5MmU0MCJ9fX0=";
    private static final String SHARK_SKIN_RESOURCE = "/assets/gomod123/textures/disguise/sharkskin.png";
    private static final String SHADOW_KNIGHT_SKIN_RESOURCE = "/assets/gomod123/textures/disguise/shadowknightskin.png";
    private static final List<String> KNOWN_KITS;
    private static final List<String> SOLO_CHEST_KIT_ORDER;
    private static final List<String> TEAMS_CHEST_KIT_ORDER;
    private static final Map<String, String> DISPLAY_NAME_OVERRIDES;
    private static int activeContainerIdentity;
    private static IInventory activeLowerInventory;
    private static ChestRewriteContext activeChestContext;
    private static String activeTargetSignature;
    private static final Map<Integer, ItemStack> originalStacks;
    private static final Map<Integer, ItemStack> replacementStacks;
    private static final Set<Integer> hiddenReplacementSlots;
    private static final Map<Integer, SlotRewriteSpec> slotRewriteSpecs;
    private static final Map<String, ItemStack> rewrittenStackCache;
    private static final Map<String, Map<Integer, List<String>>> CAPTURED_LEVEL_LORE;
    private static int activeClientClickDepth;
    private static boolean pendingReplacementRefresh;
    private static boolean directOverridesActive;
    private static final int ITEM_CATEGORY_WEAPON = 0;
    private static final int ITEM_CATEGORY_GEAR = 1;
    private static final int ITEM_CATEGORY_EGG = 2;
    private static final int ITEM_CATEGORY_FOOD = 3;
    private static final int ITEM_CATEGORY_REST = 4;

    private DisguiseBlitzKitSelectorOverride() {
    }

    public static void onTick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (!DisguiseBlitzKitSelectorOverride.isApplicable(mc)) {
            DisguiseBlitzKitSelectorOverride.restoreAndClear();
            return;
        }
        DisguiseBlitzKitSelectorOverride.ensureActiveState(mc);
        DisguiseBlitzKitSelectorOverride.flushPendingReplacementRefresh(mc);
        DisguiseBlitzKitSelectorOverride.applyDirectInventoryOverridesIfNeeded(mc);
    }

    public static ItemStack getGuiReplacementStack(IInventory slotInventory, int slotNumber) {
        if (!DisguiseBlitzKitSelectorOverride.isGuiReplacementSlot(slotInventory, slotNumber)) {
            return null;
        }
        DisguiseBlitzKitSelectorOverride.ensureActiveState(Minecraft.getMinecraft());
        if (hiddenReplacementSlots.contains(slotNumber)) {
            return null;
        }
        ItemStack replacement = replacementStacks.get(slotNumber);
        if (replacement == null || replacement.stackSize <= 0) {
            replacementStacks.remove(slotNumber);
            return null;
        }
        return replacement.copy();
    }

    public static boolean isGuiReplacementSlotHidden(IInventory slotInventory, int slotNumber) {
        if (!DisguiseBlitzKitSelectorOverride.isGuiReplacementSlot(slotInventory, slotNumber)) {
            return false;
        }
        DisguiseBlitzKitSelectorOverride.ensureActiveState(Minecraft.getMinecraft());
        ItemStack replacement = replacementStacks.get(slotNumber);
        if (replacement != null && replacement.stackSize <= 0) {
            DisguiseBlitzKitSelectorOverride.hideReplacementSlot(slotNumber);
        }
        return hiddenReplacementSlots.contains(slotNumber);
    }

    public static boolean hasGuiReplacementStack(IInventory slotInventory, int slotNumber) {
        if (!DisguiseBlitzKitSelectorOverride.isGuiReplacementSlot(slotInventory, slotNumber)) {
            return false;
        }
        if (hiddenReplacementSlots.contains(slotNumber)) {
            return false;
        }
        ItemStack replacement = replacementStacks.get(slotNumber);
        if (replacement == null || replacement.stackSize <= 0) {
            replacementStacks.remove(slotNumber);
            return false;
        }
        return true;
    }

    public static boolean isGuiReplacementSlot(IInventory slotInventory, int slotNumber) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!DisguiseBlitzKitSelectorOverride.isApplicableSlot(mc, slotInventory, slotNumber)) {
            return false;
        }
        DisguiseBlitzKitSelectorOverride.ensureActiveState(mc);
        return slotRewriteSpecs.containsKey(slotNumber);
    }

    public static ItemStack decrGuiReplacementStack(IInventory slotInventory, int slotNumber, int amount) {
        if (!DisguiseBlitzKitSelectorOverride.shouldShadowSlotMutations(slotInventory, slotNumber) || amount <= 0) {
            return null;
        }
        ItemStack replacement = replacementStacks.get(slotNumber);
        if (replacement == null || replacement.stackSize <= 0) {
            replacementStacks.remove(slotNumber);
            return null;
        }
        if (replacement.stackSize <= amount) {
            ItemStack removed = replacement.copy();
            DisguiseBlitzKitSelectorOverride.hideReplacementSlot(slotNumber);
            return removed;
        }
        hiddenReplacementSlots.remove(slotNumber);
        return replacement.splitStack(amount);
    }

    public static boolean setGuiReplacementStack(IInventory slotInventory, int slotNumber, ItemStack stack) {
        if (!DisguiseBlitzKitSelectorOverride.shouldShadowSlotMutations(slotInventory, slotNumber)) {
            return false;
        }
        if (stack == null || stack.stackSize <= 0) {
            DisguiseBlitzKitSelectorOverride.hideReplacementSlot(slotNumber);
        } else {
            hiddenReplacementSlots.remove(slotNumber);
            replacementStacks.put(slotNumber, stack.copy());
        }
        return true;
    }

    public static boolean shouldShadowSlotMutations(IInventory slotInventory, int slotNumber) {
        return activeClientClickDepth > 0 && DisguiseBlitzKitSelectorOverride.isGuiReplacementSlot(slotInventory, slotNumber);
    }

    public static void beginClientWindowClick(int slotId) {
        if (slotId < 0) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (!DisguiseBlitzKitSelectorOverride.isApplicable(mc)) {
            return;
        }
        DisguiseBlitzKitSelectorOverride.ensureActiveState(mc);
        if (!slotRewriteSpecs.containsKey(slotId)) {
            return;
        }
        ++activeClientClickDepth;
    }

    public static void endClientWindowClick() {
        if (activeClientClickDepth <= 0) {
            return;
        }
        if (--activeClientClickDepth == 0) {
            pendingReplacementRefresh = true;
        }
    }

    public static List<String> getPreviewKitOrder() {
        return new ArrayList<String>(DisguiseBlitzKitSelectorOverride.getStaticChestKitOrder(Minecraft.getMinecraft()));
    }

    public static ItemStack buildPreviewStack(String kitName, int previewLevel) {
        String displayKitName = DisguiseBlitzKitSelectorOverride.getSelectorDisplayName(kitName);
        if (displayKitName.isEmpty()) {
            return null;
        }
        int clampedLevel = Math.max(1, Math.min(12, previewLevel));
        boolean prestiged = clampedLevel >= 11;
        int prestige = prestiged ? clampedLevel - 10 : 0;
        int level = prestiged ? 10 : clampedLevel;
        return DisguiseBlitzKitSelectorOverride.buildUnlockedKitStack(displayKitName, displayKitName, level, prestige, prestiged, null, null);
    }

    public static boolean hasCustomSelectorTooltip(ItemStack stack) {
        if (stack == null) {
            return false;
        }
        NBTTagCompound tag = stack.getTagCompound();
        return tag != null && tag.getBoolean(SELECTOR_TOOLTIP_TAG);
    }

    public static List<String> getCustomSelectorTooltipLines(ItemStack stack, boolean advanced) {
        NBTTagCompound tag;
        if (!DisguiseBlitzKitSelectorOverride.hasCustomSelectorTooltip(stack)) {
            return Collections.emptyList();
        }
        List<String> basicVanilla = null;
        List<String> advVanilla = null;
        if (advanced) {
            basicVanilla = DisguiseBlitzKitSelectorOverride.getVanillaTooltipLinesWithoutSelectorOverride(stack, false);
            advVanilla = DisguiseBlitzKitSelectorOverride.getVanillaTooltipLinesWithoutSelectorOverride(stack, true);
        }
        ArrayList<String> tooltip = new ArrayList<String>();
        String displayName = stack.getDisplayName();
        if (advanced && advVanilla != null && !advVanilla.isEmpty()) {
            tooltip.add(advVanilla.get(0));
        } else if (displayName != null && !displayName.isEmpty()) {
            tooltip.add(displayName);
        }
        if ((tag = stack.getTagCompound()) == null || !tag.hasKey("display", 10)) {
            if (advanced && basicVanilla != null && advVanilla != null) {
                DisguiseBlitzKitSelectorOverride.appendAdvancedTooltipTail(tooltip, basicVanilla, advVanilla);
            }
            return tooltip;
        }
        NBTTagCompound display = tag.getCompoundTag("display");
        if (!display.hasKey("Lore", 9)) {
            if (advanced && basicVanilla != null && advVanilla != null) {
                DisguiseBlitzKitSelectorOverride.appendAdvancedTooltipTail(tooltip, basicVanilla, advVanilla);
            }
            return tooltip;
        }
        NBTTagList loreTag = display.getTagList("Lore", 8);
        for (int i = 0; i < loreTag.tagCount(); ++i) {
            tooltip.add(loreTag.getStringTagAt(i));
        }
        if (advanced && basicVanilla != null && advVanilla != null) {
            DisguiseBlitzKitSelectorOverride.appendAdvancedTooltipTail(tooltip, basicVanilla, advVanilla);
        }
        return tooltip;
    }

    private static boolean isApplicable(Minecraft mc) {
        if (mc == null || mc.thePlayer == null || mc.currentScreen == null) {
            return false;
        }
        if (mc.thePlayer.openContainer == null) {
            return false;
        }
        if (!LocalDisguise.shouldOverrideBlitzKitSelector()) {
            return false;
        }
        if (!DisguiseBlitzKitSelectorOverride.isOnHypixelServer(mc)) {
            return false;
        }
        IInventory lower = DisguiseBlitzKitSelectorOverride.resolveLowerInventory(mc);
        if (lower == null || lower.getDisplayName() == null) {
            return false;
        }
        return DisguiseBlitzKitSelectorOverride.detectChestContext(mc, lower) != null;
    }

    private static boolean isApplicableSlot(Minecraft mc, IInventory slotInventory, int slotNumber) {
        if (!DisguiseBlitzKitSelectorOverride.isApplicable(mc)) {
            return false;
        }
        IInventory lower = DisguiseBlitzKitSelectorOverride.resolveLowerInventory(mc);
        return lower != null && slotInventory == lower && slotNumber >= 0 && slotNumber < lower.getSizeInventory();
    }

    private static ChestRewriteContext detectChestContext(Minecraft mc, IInventory lower) {
        if (mc == null || lower == null || lower.getDisplayName() == null) {
            return null;
        }
        String chestName = DisguiseBlitzKitSelectorOverride.cleanText(lower.getDisplayName().getUnformattedText());
        if (chestName.isEmpty()) {
            return null;
        }
        String lowerName = chestName.toLowerCase(Locale.ROOT);
        if (lowerName.equals("kit selector") || lowerName.contains("kit selector")) {
            return new ChestRewriteContext(ChestMode.SELECTOR, null);
        }
        if (lowerName.equals("basic kits") || lowerName.contains("basic kits")) {
            return new ChestRewriteContext(ChestMode.BASIC_KITS, null);
        }
        if (lowerName.equals("advanced kits") || lowerName.contains("advanced kits")) {
            return new ChestRewriteContext(ChestMode.ADVANCED_KITS, null);
        }
        if (lowerName.equals("ultimate kits") || lowerName.contains("ultimate kits")) {
            return new ChestRewriteContext(ChestMode.ULTIMATE_KITS, null);
        }
        if (lowerName.endsWith(" upgrades") || lowerName.contains(" upgrades")) {
            String base = chestName;
            if (lowerName.endsWith(" upgrades")) {
                base = chestName.substring(0, chestName.length() - " upgrades".length());
            } else {
                int idx = lowerName.indexOf(" upgrades");
                if (idx > 0) {
                    base = chestName.substring(0, idx);
                }
            }
            String upgradeKit = DisguiseBlitzKitSelectorOverride.identifyKitNameFromText(base);
            if (upgradeKit != null) {
                return new ChestRewriteContext(ChestMode.KIT_UPGRADES, DisguiseBlitzKitSelectorOverride.getSelectorDisplayName(upgradeKit));
            }
        }
        return null;
    }

    private static List<String> getStaticChestKitOrder(Minecraft mc) {
        return BlitzTeamsDetector.isBlitzTeamsGame(mc) ? TEAMS_CHEST_KIT_ORDER : SOLO_CHEST_KIT_ORDER;
    }

    private static void captureOriginalState(int containerIdentity, IInventory lower, Minecraft mc) {
        ChestRewriteContext context = DisguiseBlitzKitSelectorOverride.detectChestContext(mc, lower);
        if (context == null) {
            DisguiseBlitzKitSelectorOverride.restoreAndClear();
            return;
        }
        activeContainerIdentity = containerIdentity;
        activeLowerInventory = lower;
        activeChestContext = context;
        activeTargetSignature = "";
        originalStacks.clear();
        replacementStacks.clear();
        hiddenReplacementSlots.clear();
        slotRewriteSpecs.clear();
        for (int slot = 0; slot < lower.getSizeInventory(); ++slot) {
            ItemStack stack = lower.getStackInSlot(slot);
            originalStacks.put(slot, stack == null ? null : stack.copy());
        }
        switch (context.mode) {
            case SELECTOR: {
                DisguiseBlitzKitSelectorOverride.captureSelectorSlots(lower, mc);
                break;
            }
            case BASIC_KITS: 
            case ADVANCED_KITS: 
            case ULTIMATE_KITS: {
                DisguiseBlitzKitSelectorOverride.captureCategorySlots(lower, mc);
                break;
            }
            case KIT_UPGRADES: {
                DisguiseBlitzKitSelectorOverride.captureUpgradeSlots(lower, context.displayKitName);
                break;
            }
        }
    }

    private static void restoreAndClear() {
        if (directOverridesActive && activeLowerInventory != null) {
            for (int slot = 0; slot < activeLowerInventory.getSizeInventory(); ++slot) {
                ItemStack original = originalStacks.get(slot);
                activeLowerInventory.setInventorySlotContents(slot, original == null ? null : original.copy());
            }
        }
        activeContainerIdentity = 0;
        activeLowerInventory = null;
        activeChestContext = null;
        activeTargetSignature = "";
        originalStacks.clear();
        replacementStacks.clear();
        hiddenReplacementSlots.clear();
        slotRewriteSpecs.clear();
        pendingReplacementRefresh = false;
        directOverridesActive = false;
    }

    private static void applyOverride(Map<String, LocalDisguise.DisguiseKitInfo> targetKits) {
        replacementStacks.clear();
        hiddenReplacementSlots.clear();
        if (slotRewriteSpecs.isEmpty()) {
            return;
        }
        for (Map.Entry<Integer, SlotRewriteSpec> entry : slotRewriteSpecs.entrySet()) {
            ItemStack rewritten;
            int slot = entry.getKey();
            SlotRewriteSpec spec = entry.getValue();
            ItemStack original = originalStacks.get(slot);
            LocalDisguise.DisguiseKitInfo info = DisguiseBlitzKitSelectorOverride.findKitInfo(targetKits, spec == null ? null : spec.kitName);
            if (DisguiseBlitzKitSelectorOverride.shouldHideRewriteSlot(spec, info)) {
                hiddenReplacementSlots.add(slot);
                continue;
            }
            String cacheKey = DisguiseBlitzKitSelectorOverride.buildRewriteCacheKey(activeTargetSignature, slot, spec, info, original);
            ItemStack cached = rewrittenStackCache.get(cacheKey);
            if (cached != null) {
                rewritten = cached.copy();
            } else {
                try {
                    rewritten = DisguiseBlitzKitSelectorOverride.buildReplacementStack(spec, info, original);
                }
                catch (Throwable ignored) {
                    ItemStack itemStack = rewritten = original == null ? null : original.copy();
                }
                if (rewritten != null) {
                    rewrittenStackCache.put(cacheKey, rewritten.copy());
                }
            }
            if (rewritten == null) continue;
            replacementStacks.put(slot, rewritten.copy());
        }
    }

    private static void ensureActiveState(Minecraft mc) {
        Map<String, LocalDisguise.DisguiseKitInfo> targetKits;
        String targetSignature;
        if (!DisguiseBlitzKitSelectorOverride.isApplicable(mc)) {
            DisguiseBlitzKitSelectorOverride.restoreAndClear();
            return;
        }
        Container container = mc.thePlayer.openContainer;
        IInventory lower = DisguiseBlitzKitSelectorOverride.resolveLowerInventory(mc);
        int containerIdentity = System.identityHashCode(container);
        if (containerIdentity != activeContainerIdentity || activeLowerInventory != lower) {
            DisguiseBlitzKitSelectorOverride.restoreAndClear();
            if (lower != null) {
                DisguiseBlitzKitSelectorOverride.captureOriginalState(containerIdentity, lower, mc);
            }
        }
        if (!(targetSignature = DisguiseBlitzKitSelectorOverride.buildTargetKitSignature(targetKits = LocalDisguise.getTargetKitInfoByName())).equals(activeTargetSignature)) {
            DisguiseBlitzKitSelectorOverride.applyOverride(targetKits);
            activeTargetSignature = targetSignature;
        }
    }

    private static void refreshReplacementStacks() {
        if (activeContainerIdentity == 0 || activeLowerInventory == null) {
            return;
        }
        DisguiseBlitzKitSelectorOverride.applyOverride(LocalDisguise.getTargetKitInfoByName());
    }

    private static void hideReplacementSlot(int slotNumber) {
        replacementStacks.remove(slotNumber);
        hiddenReplacementSlots.add(slotNumber);
    }

    private static void flushPendingReplacementRefresh(Minecraft mc) {
        if (!pendingReplacementRefresh || mc == null || mc.thePlayer == null) {
            return;
        }
        if (mc.thePlayer.inventory != null && mc.thePlayer.inventory.getItemStack() != null) {
            return;
        }
        DisguiseBlitzKitSelectorOverride.refreshReplacementStacks();
        pendingReplacementRefresh = false;
    }

    private static void applyDirectInventoryOverridesIfNeeded(Minecraft mc) {
        if (mc == null || mc.thePlayer == null) {
            return;
        }
        if (MixinStatus.guiContainerActive && MixinStatus.slotMixinActive) {
            return;
        }
        if (mc.thePlayer.inventory != null && mc.thePlayer.inventory.getItemStack() != null) {
            return;
        }
        if (activeLowerInventory == null || replacementStacks.isEmpty() && hiddenReplacementSlots.isEmpty()) {
            return;
        }
        for (int slot = 0; slot < activeLowerInventory.getSizeInventory(); ++slot) {
            ItemStack desired = null;
            if (hiddenReplacementSlots.contains(slot)) {
                desired = null;
            } else {
                ItemStack replacement = replacementStacks.get(slot);
                if (replacement == null || replacement.stackSize <= 0) continue;
                desired = replacement.copy();
            }
            ItemStack current = activeLowerInventory.getStackInSlot(slot);
            if (DisguiseBlitzKitSelectorOverride.areStacksEqual(current, desired)) continue;
            activeLowerInventory.setInventorySlotContents(slot, desired);
            directOverridesActive = true;
        }
    }

    private static boolean areStacksEqual(ItemStack a, ItemStack b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a.getItem() != b.getItem()) {
            return false;
        }
        if (a.stackSize != b.stackSize) {
            return false;
        }
        if (a.getItemDamage() != b.getItemDamage()) {
            return false;
        }
        if (a.getTagCompound() == null && b.getTagCompound() == null) {
            return true;
        }
        if (a.getTagCompound() == null || b.getTagCompound() == null) {
            return false;
        }
        return a.getTagCompound().equals((Object)b.getTagCompound());
    }

    private static String buildTargetKitSignature(Map<String, LocalDisguise.DisguiseKitInfo> targetKits) {
        if (targetKits == null || targetKits.isEmpty()) {
            return "";
        }
        ArrayList<String> keys = new ArrayList<String>(targetKits.keySet());
        Collections.sort(keys);
        StringBuilder signature = new StringBuilder();
        for (String key : keys) {
            LocalDisguise.DisguiseKitInfo info = targetKits.get(key);
            if (info == null) continue;
            signature.append(key).append(':').append(info.level).append(':').append(info.prestige).append(':').append(info.prestiged).append(':').append(info.kitKills).append(':').append(info.kitSoloWins).append(':').append(info.kitTeamWins).append(':').append(info.levelDisplay == null ? "" : info.levelDisplay).append('|');
        }
        Integer disguisedCoins = LocalDisguise.getDisguisedBlitzCoins();
        signature.append("coins:").append(disguisedCoins == null ? "" : disguisedCoins);
        return signature.toString();
    }

    private static void captureSelectorSlots(IInventory lower, Minecraft mc) {
        List<String> fallbackOrder = DisguiseBlitzKitSelectorOverride.getStaticChestKitOrder(mc);
        for (int slot = 0; slot < lower.getSizeInventory(); ++slot) {
            ItemStack stack = lower.getStackInSlot(slot);
            String kitName = DisguiseBlitzKitSelectorOverride.identifyKitName(stack, mc);
            if (kitName == null && slot < fallbackOrder.size()) {
                kitName = fallbackOrder.get(slot);
            }
            if (kitName == null) continue;
            slotRewriteSpecs.put(slot, new SlotRewriteSpec(SlotKind.SELECTOR_KIT, DisguiseBlitzKitSelectorOverride.getSelectorDisplayName(kitName), 0));
        }
    }

    private static IInventory resolveLowerInventory(Minecraft mc) {
        if (mc == null || mc.thePlayer == null || mc.thePlayer.openContainer == null) {
            return null;
        }
        if (mc.thePlayer.openContainer instanceof ContainerChest) {
            ContainerChest chest = (ContainerChest)mc.thePlayer.openContainer;
            return chest.getLowerChestInventory();
        }
        Container container = mc.thePlayer.openContainer;
        if (container.inventorySlots == null || container.inventorySlots.isEmpty()) {
            return null;
        }
        IInventory best = null;
        int bestSize = -1;
        for (Object slotObj : container.inventorySlots) {
            int size;
            String name;
            if (!(slotObj instanceof Slot)) continue;
            Slot slot = (Slot)slotObj;
            IInventory inv = slot.inventory;
            if (inv == null || inv.getDisplayName() == null || !DisguiseBlitzKitSelectorOverride.isPotentialKitInventoryName(name = DisguiseBlitzKitSelectorOverride.cleanText(inv.getDisplayName().getUnformattedText()).toLowerCase(Locale.ROOT)) || (size = inv.getSizeInventory()) <= bestSize) continue;
            best = inv;
            bestSize = size;
        }
        return best;
    }

    private static boolean isPotentialKitInventoryName(String lowerName) {
        if (lowerName == null || lowerName.isEmpty()) {
            return false;
        }
        return lowerName.contains("kit") || lowerName.contains("selector") || lowerName.contains("basic") || lowerName.contains("advanced") || lowerName.contains("ultimate") || lowerName.contains("upgrades");
    }

    private static void captureCategorySlots(IInventory lower, Minecraft mc) {
        for (int slot = 0; slot < lower.getSizeInventory(); ++slot) {
            ItemStack stack = lower.getStackInSlot(slot);
            String kitName = DisguiseBlitzKitSelectorOverride.identifyKitName(stack, mc);
            if (kitName == null) continue;
            slotRewriteSpecs.put(slot, new SlotRewriteSpec(SlotKind.CATEGORY_KIT, DisguiseBlitzKitSelectorOverride.getSelectorDisplayName(kitName), 0));
        }
    }

    private static void captureUpgradeSlots(IInventory lower, String displayKitName) {
        if (displayKitName == null || displayKitName.trim().isEmpty()) {
            return;
        }
        for (int slot = 0; slot < lower.getSizeInventory(); ++slot) {
            ItemStack stack = lower.getStackInSlot(slot);
            SlotRewriteSpec spec = DisguiseBlitzKitSelectorOverride.identifyUpgradeSlotSpec(stack, displayKitName);
            if (spec == null) continue;
            slotRewriteSpecs.put(slot, spec);
        }
    }

    private static SlotRewriteSpec identifyUpgradeSlotSpec(ItemStack stack, String displayKitName) {
        if (stack == null || displayKitName == null || displayKitName.trim().isEmpty()) {
            return null;
        }
        String cleanName = DisguiseBlitzKitSelectorOverride.cleanText(stack.getDisplayName());
        if (cleanName.isEmpty() || !cleanName.toLowerCase(Locale.ROOT).startsWith(displayKitName.toLowerCase(Locale.ROOT))) {
            return null;
        }
        String suffix = cleanName.substring(displayKitName.length()).trim();
        if (suffix.isEmpty()) {
            return null;
        }
        if ("Unlock".equalsIgnoreCase(suffix)) {
            return new SlotRewriteSpec(SlotKind.UPGRADE_UNLOCK, displayKitName, 0);
        }
        if (suffix.toLowerCase(Locale.ROOT).startsWith("Prestige")) {
            int prestigeLevel = DisguiseBlitzKitSelectorOverride.parseLeadingRomanNumeral(suffix.substring("Prestige".length()).trim());
            return new SlotRewriteSpec(SlotKind.UPGRADE_PRESTIGE, displayKitName, prestigeLevel <= 0 ? 1 : prestigeLevel);
        }
        int level = DisguiseBlitzKitSelectorOverride.parseLeadingRomanNumeral(suffix);
        if (level > 0) {
            return new SlotRewriteSpec(SlotKind.UPGRADE_LEVEL, displayKitName, level);
        }
        return null;
    }

    private static ItemStack buildReplacementStack(SlotRewriteSpec spec, LocalDisguise.DisguiseKitInfo info, ItemStack original) {
        if (spec == null) {
            return original == null ? null : original.copy();
        }
        switch (spec.kind) {
            case SELECTOR_KIT: {
                return info != null ? DisguiseBlitzKitSelectorOverride.buildUnlockedKitStack(spec.kitName, info, original) : DisguiseBlitzKitSelectorOverride.buildLockedKitStack(spec.kitName, original);
            }
            case CATEGORY_KIT: {
                return DisguiseBlitzKitSelectorOverride.buildShopCategoryKitStack(spec.kitName, info, original);
            }
            case UPGRADE_LEVEL: {
                return DisguiseBlitzKitSelectorOverride.buildShopUpgradeLevelStack(spec.kitName, spec.level, info, original);
            }
            case UPGRADE_UNLOCK: {
                return DisguiseBlitzKitSelectorOverride.buildShopUpgradeUnlockStack(spec.kitName, original);
            }
            case UPGRADE_PRESTIGE: {
                return DisguiseBlitzKitSelectorOverride.buildShopPrestigeStack(spec.kitName, spec.level, info, original);
            }
        }
        return original == null ? null : original.copy();
    }

    private static boolean shouldHideRewriteSlot(SlotRewriteSpec spec, LocalDisguise.DisguiseKitInfo info) {
        return spec != null && spec.kind == SlotKind.UPGRADE_UNLOCK && info != null;
    }

    private static String identifyKitName(ItemStack stack, Minecraft mc) {
        if (stack == null || mc == null || mc.thePlayer == null) {
            return null;
        }
        ArrayList<String> texts = new ArrayList<String>();
        String displayName = DisguiseBlitzKitSelectorOverride.cleanText(stack.getDisplayName());
        if (!displayName.isEmpty()) {
            texts.add(displayName);
        }
        for (String text : texts) {
            String identified = DisguiseBlitzKitSelectorOverride.identifyKitNameFromText(text);
            if (identified == null) continue;
            return identified;
        }
        return null;
    }

    private static String identifyKitNameFromText(String text) {
        String normalizedText = DisguiseBlitzKitSelectorOverride.normalizeKitKey(text);
        if (normalizedText.isEmpty()) {
            return null;
        }
        for (String kitName : KNOWN_KITS) {
            String normalizedKit = DisguiseBlitzKitSelectorOverride.normalizeKitKey(kitName);
            if (!normalizedText.contains(normalizedKit)) continue;
            return kitName;
        }
        return null;
    }

    private static LocalDisguise.DisguiseKitInfo findKitInfo(Map<String, LocalDisguise.DisguiseKitInfo> targetKits, String kitName) {
        if (targetKits == null || targetKits.isEmpty() || kitName == null || kitName.trim().isEmpty()) {
            return null;
        }
        String normalizedKitName = DisguiseBlitzKitSelectorOverride.normalizeKitKey(kitName);
        LocalDisguise.DisguiseKitInfo direct = targetKits.get(normalizedKitName);
        if (direct != null) {
            return direct;
        }
        for (LocalDisguise.DisguiseKitInfo info : targetKits.values()) {
            if (info == null || !normalizedKitName.equals(DisguiseBlitzKitSelectorOverride.normalizeKitKey(info.displayName)) && !normalizedKitName.equals(DisguiseBlitzKitSelectorOverride.normalizeKitKey(info.apiName)) && !normalizedKitName.equals(DisguiseBlitzKitSelectorOverride.normalizeKitKey(DisguiseBlitzKitSelectorOverride.getSelectorDisplayName(info.displayName)))) continue;
            return info;
        }
        return null;
    }

    private static int parseLeadingRomanNumeral(String text) {
        String normalized = DisguiseBlitzKitSelectorOverride.cleanText(text).toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return 0;
        }
        for (int level = 10; level >= 1; --level) {
            String roman = DisguiseBlitzKitSelectorOverride.toRomanNumeral(level);
            if (!roman.equals(normalized) && !normalized.startsWith(roman + " ")) continue;
            return level;
        }
        return 0;
    }

    private static ItemStack buildUnlockedKitStack(String displayKitName, LocalDisguise.DisguiseKitInfo info, ItemStack original) {
        if (info == null) {
            return DisguiseBlitzKitSelectorOverride.buildLockedKitStack(displayKitName, original);
        }
        return DisguiseBlitzKitSelectorOverride.buildUnlockedKitStack(displayKitName, info.apiName, info.level, info.prestige, info.prestiged, info.levelDisplay, original);
    }

    private static ItemStack buildUnlockedKitStack(String displayKitName, String sourceName, int level, int prestige, boolean prestiged, String levelDisplay, ItemStack original) {
        JsonObject levelData = DisguiseBlitzKitSelectorOverride.getLevelData(sourceName, level, prestige, prestiged);
        ItemStack icon = DisguiseBlitzKitSelectorOverride.buildPreservedOriginalIcon(displayKitName, original);
        if (icon == null) {
            icon = DisguiseBlitzKitSelectorOverride.buildIconStack(displayKitName, levelData);
        }
        if (icon == null && original != null) {
            icon = original.copy();
        }
        if (icon == null) {
            return DisguiseBlitzKitSelectorOverride.buildLockedKitStack(displayKitName, original);
        }
        String levelSuffix = DisguiseBlitzKitSelectorOverride.formatLevelSuffix(level, prestige, prestiged, levelDisplay);
        EnumChatFormatting titleColor = prestiged ? EnumChatFormatting.GOLD : EnumChatFormatting.GREEN;
        DisguiseBlitzKitSelectorOverride.setDisplay(icon, titleColor + displayKitName + levelSuffix, DisguiseBlitzKitSelectorOverride.buildUnlockedLore(displayKitName, level, prestige, prestiged, levelData, original));
        DisguiseBlitzKitSelectorOverride.hideAttributeModifiers(icon);
        return icon;
    }

    private static ItemStack buildPreservedOriginalIcon(String displayKitName, ItemStack original) {
        if (original == null) {
            return null;
        }
        if ("guardian".equals(DisguiseBlitzKitSelectorOverride.normalizeKitKey(displayKitName)) && original.getItem() == Items.potionitem) {
            return original.copy();
        }
        return null;
    }

    private static ItemStack buildLockedKitStack(String displayKitName, ItemStack original) {
        ItemStack locked = new ItemStack(Item.getItemFromBlock((Block)Blocks.stained_glass_pane), 1, 14);
        JsonObject levelData = DisguiseBlitzKitSelectorOverride.getLevelData(displayKitName, 1, 0, false);
        List<String> lore = DisguiseBlitzKitSelectorOverride.buildLockedKitLore(displayKitName, levelData);
        DisguiseBlitzKitSelectorOverride.setDisplay(locked, EnumChatFormatting.RED + displayKitName, lore);
        DisguiseBlitzKitSelectorOverride.hideAttributeModifiers(locked);
        return locked;
    }

    private static List<String> buildLockedKitLore(String displayKitName, JsonObject levelData) {
        ArrayList<String> lore = new ArrayList<String>();
        List<String> header = DisguiseBlitzKitSelectorOverride.buildLockedKitDescriptionLines(displayKitName, levelData);
        if (header != null && !header.isEmpty()) {
            lore.addAll(header);
        }
        lore.add(EnumChatFormatting.RED + "Unlock in the shop!");
        DisguiseBlitzKitSelectorOverride.trimTrailingEmptyLines(lore);
        return lore;
    }

    private static List<String> buildLockedKitDescriptionLines(String displayKitName, JsonObject levelData) {
        List<String> custom = DisguiseBlitzKitSelectorOverride.buildCustomHeaderLore(displayKitName);
        if (!custom.isEmpty()) {
            return new ArrayList<String>(custom);
        }
        CapturedLevelLore captured = DisguiseBlitzKitSelectorOverride.getCapturedLevelLore(displayKitName, 1, false);
        if (captured != null && captured.headerLines != null && !captured.headerLines.isEmpty()) {
            return new ArrayList<String>(captured.headerLines);
        }
        ArrayList<String> fallback = new ArrayList<String>();
        DisguiseBlitzKitSelectorOverride.appendFormattedDescriptionLines(levelData, fallback);
        DisguiseBlitzKitSelectorOverride.trimTrailingEmptyLines(fallback);
        return fallback;
    }

    private static ItemStack buildShopCategoryKitStack(String displayKitName, LocalDisguise.DisguiseKitInfo info, ItemStack original) {
        if (info == null) {
            return DisguiseBlitzKitSelectorOverride.buildShopCategoryLockedKitStack(displayKitName, original);
        }
        JsonObject levelData = DisguiseBlitzKitSelectorOverride.getLevelData(info);
        ItemStack icon = DisguiseBlitzKitSelectorOverride.buildShopIconStack(displayKitName, levelData, original);
        if (icon == null) {
            return DisguiseBlitzKitSelectorOverride.buildShopCategoryLockedKitStack(displayKitName, original);
        }
        String title = (info.prestiged ? EnumChatFormatting.GOLD : EnumChatFormatting.GREEN) + displayKitName + DisguiseBlitzKitSelectorOverride.formatShopLevelSuffix(info.level, info.prestige, info.prestiged, info.levelDisplay);
        List<String> lore = DisguiseBlitzKitSelectorOverride.buildKitShopLore(displayKitName, levelData, original, info.level, info.prestiged, Collections.singletonList(EnumChatFormatting.YELLOW + "Click to view upgrades!"));
        DisguiseBlitzKitSelectorOverride.setDisplay(icon, title, lore);
        DisguiseBlitzKitSelectorOverride.hideAttributeModifiers(icon);
        return icon;
    }

    private static ItemStack buildShopCategoryLockedKitStack(String displayKitName, ItemStack original) {
        JsonObject levelData = DisguiseBlitzKitSelectorOverride.getLevelData(displayKitName, 1, 0, false);
        ItemStack icon = DisguiseBlitzKitSelectorOverride.buildShopIconStack(displayKitName, levelData, original);
        if (icon == null) {
            return DisguiseBlitzKitSelectorOverride.buildLockedKitStack(displayKitName, original);
        }
        EnumChatFormatting titleColor = activeChestContext != null && DisguiseBlitzKitSelectorOverride.activeChestContext.mode == ChestMode.ULTIMATE_KITS ? EnumChatFormatting.RED : EnumChatFormatting.YELLOW;
        List<String> lore = DisguiseBlitzKitSelectorOverride.buildKitShopLore(displayKitName, levelData, original, 1, false, Collections.singletonList(EnumChatFormatting.YELLOW + "Click to view upgrades!"));
        DisguiseBlitzKitSelectorOverride.setDisplay(icon, titleColor + displayKitName, lore);
        DisguiseBlitzKitSelectorOverride.hideAttributeModifiers(icon);
        return icon;
    }

    private static ItemStack buildShopUpgradeLevelStack(String displayKitName, int level, LocalDisguise.DisguiseKitInfo info, ItemStack original) {
        JsonObject levelData = DisguiseBlitzKitSelectorOverride.getLevelDataByJsonLevel(DisguiseBlitzKitSelectorOverride.resolveSourceKitName(displayKitName, info), level);
        boolean unlocked = DisguiseBlitzKitSelectorOverride.isUpgradeLevelUnlocked(info, level);
        ItemStack stack = DisguiseBlitzKitSelectorOverride.buildUpgradeDisplayStack(original, level, unlocked);
        if (stack == null) {
            return null;
        }
        List<String> lore = DisguiseBlitzKitSelectorOverride.buildKitShopLore(displayKitName, levelData, original, level, false, DisguiseBlitzKitSelectorOverride.buildUpgradeLevelFooter(displayKitName, level, info, levelData));
        DisguiseBlitzKitSelectorOverride.setDisplay(stack, (unlocked ? EnumChatFormatting.GREEN : EnumChatFormatting.RED) + displayKitName + " " + DisguiseBlitzKitSelectorOverride.toRomanNumeral(level), lore);
        return stack;
    }

    private static ItemStack buildShopUpgradeUnlockStack(String displayKitName, ItemStack original) {
        JsonObject levelData = DisguiseBlitzKitSelectorOverride.getLevelDataByJsonLevel(displayKitName, 1);
        ItemStack icon = DisguiseBlitzKitSelectorOverride.buildShopIconStack(displayKitName, levelData, original);
        if (icon == null) {
            return null;
        }
        List<String> lore = DisguiseBlitzKitSelectorOverride.buildKitShopLore(displayKitName, levelData, original, 1, false, DisguiseBlitzKitSelectorOverride.buildUpgradeUnlockFooter(levelData));
        DisguiseBlitzKitSelectorOverride.setDisplay(icon, EnumChatFormatting.YELLOW + displayKitName + " Unlock", lore);
        DisguiseBlitzKitSelectorOverride.hideAttributeModifiers(icon);
        return icon;
    }

    private static ItemStack buildShopPrestigeStack(String displayKitName, int prestigeLevel, LocalDisguise.DisguiseKitInfo info, ItemStack original) {
        ItemStack stack = original == null ? new ItemStack(Items.golden_apple) : original.copy();
        String sourceName = DisguiseBlitzKitSelectorOverride.resolveSourceKitName(displayKitName, info);
        JsonObject levelData = DisguiseBlitzKitSelectorOverride.getLevelDataByJsonLevel(sourceName, 10 + Math.max(1, prestigeLevel));
        List<String> lore = DisguiseBlitzKitSelectorOverride.buildPrestigeLore(displayKitName, prestigeLevel, info, levelData, original);
        EnumChatFormatting titleColor = DisguiseBlitzKitSelectorOverride.getPrestigeTitleColor(displayKitName, prestigeLevel, info, levelData);
        DisguiseBlitzKitSelectorOverride.setDisplay(stack, titleColor + displayKitName + " Prestige " + DisguiseBlitzKitSelectorOverride.toRomanNumeral(Math.max(1, prestigeLevel)), lore);
        DisguiseBlitzKitSelectorOverride.hideAttributeModifiers(stack);
        return stack;
    }

    private static ItemStack buildShopIconStack(String displayKitName, JsonObject levelData, ItemStack original) {
        ItemStack icon = DisguiseBlitzKitSelectorOverride.buildPreservedOriginalIcon(displayKitName, original);
        if (icon == null && original != null) {
            icon = original.copy();
        }
        if (icon == null) {
            icon = DisguiseBlitzKitSelectorOverride.buildIconStack(displayKitName, levelData);
        }
        return icon;
    }

    private static ItemStack buildUpgradeDisplayStack(ItemStack original, int level, boolean unlocked) {
        int meta;
        int n = meta = unlocked ? 5 : 14;
        if (original != null) {
            ItemStack copy = original.copy();
            copy.stackSize = 1;
            copy.setItemDamage(meta);
            return copy;
        }
        Item item = Item.getItemFromBlock((Block)(level >= 10 ? Blocks.stained_glass : Blocks.stained_glass_pane));
        return item == null ? null : new ItemStack(item, 1, meta);
    }

    private static List<String> buildKitShopLore(String displayKitName, JsonObject levelData, ItemStack original, int level, boolean prestiged, List<String> footerLines) {
        List<String> itemLines;
        CapturedLevelLore captured = prestiged ? null : DisguiseBlitzKitSelectorOverride.getCapturedLevelLore(displayKitName, level, false);
        List<String> arrayList = itemLines = captured != null && !captured.itemLines.isEmpty() ? new ArrayList<String>(captured.itemLines) : DisguiseBlitzKitSelectorOverride.buildItemLines(displayKitName, levelData);
        List<String> headerFallback = prestiged ? DisguiseBlitzKitSelectorOverride.getPrestigeHeaderFallback(displayKitName, true) : (captured == null ? Collections.emptyList() : captured.headerLines);
        return DisguiseBlitzKitSelectorOverride.buildShopLore(displayKitName, levelData, original, headerFallback, itemLines, footerLines);
    }

    private static List<String> buildShopLore(String displayKitName, JsonObject levelData, ItemStack original, List<String> headerFallback, List<String> itemLines, List<String> footerLines) {
        List<String> lore = DisguiseBlitzKitSelectorOverride.buildHeaderLore(displayKitName, original, levelData, itemLines, headerFallback);
        if (!lore.isEmpty() && itemLines != null && !itemLines.isEmpty()) {
            lore.add("");
        }
        if (itemLines != null && !itemLines.isEmpty()) {
            lore.addAll(itemLines);
        }
        DisguiseBlitzKitSelectorOverride.trimTrailingEmptyLines(lore);
        if (footerLines != null && !footerLines.isEmpty()) {
            if (!lore.isEmpty()) {
                lore.add("");
            }
            lore.addAll(footerLines);
        }
        DisguiseBlitzKitSelectorOverride.trimTrailingEmptyLines(lore);
        return lore;
    }

    private static List<String> buildUpgradeLevelFooter(String displayKitName, int level, LocalDisguise.DisguiseKitInfo info, JsonObject levelData) {
        ArrayList<String> footer = new ArrayList<String>();
        if (DisguiseBlitzKitSelectorOverride.isUpgradeLevelUnlocked(info, level)) {
            footer.add(EnumChatFormatting.GREEN + "UNLOCKED");
            return footer;
        }
        String costLine = DisguiseBlitzKitSelectorOverride.getUpgradeLevelCostLine(level, levelData);
        if (!costLine.isEmpty()) {
            footer.add(EnumChatFormatting.GRAY + costLine);
            footer.add("");
        }
        if (info == null) {
            footer.add(EnumChatFormatting.RED + "You have to unlock the kit first!");
        } else if (DisguiseBlitzKitSelectorOverride.isUpgradeLevelNextUnlockable(info, level)) {
            footer.add(EnumChatFormatting.YELLOW + "Click to unlock!");
        } else {
            footer.add(EnumChatFormatting.RED + "You have to unlock " + displayKitName + " " + DisguiseBlitzKitSelectorOverride.toRomanNumeral(Math.max(1, level - 1)) + " first!");
        }
        DisguiseBlitzKitSelectorOverride.trimTrailingEmptyLines(footer);
        return footer;
    }

    private static List<String> buildUpgradeUnlockFooter(JsonObject levelData) {
        ArrayList<String> footer = new ArrayList<String>();
        String costLine = DisguiseBlitzKitSelectorOverride.getUnlockCostLine(levelData);
        if (!costLine.isEmpty()) {
            footer.add(EnumChatFormatting.GRAY + costLine);
            footer.add("");
        }
        footer.add(EnumChatFormatting.YELLOW + "Click to unlock!");
        return footer;
    }

    private static List<String> buildPrestigeLore(String displayKitName, int prestigeLevel, LocalDisguise.DisguiseKitInfo info, JsonObject levelData, ItemStack original) {
        List<String> footer;
        List<String> requirementLines;
        String costLine;
        if (levelData == null) {
            List<String> fallback = DisguiseBlitzKitSelectorOverride.buildPrestigeBaseLore(original);
            List<String> footer2 = DisguiseBlitzKitSelectorOverride.buildPrestigeFooter(displayKitName, prestigeLevel, info, null);
            if (!footer2.isEmpty()) {
                DisguiseBlitzKitSelectorOverride.trimTrailingEmptyLines(fallback);
                if (!fallback.isEmpty()) {
                    fallback.add("");
                }
                fallback.addAll(footer2);
            }
            DisguiseBlitzKitSelectorOverride.trimTrailingEmptyLines(fallback);
            return fallback;
        }
        ArrayList<String> lore = new ArrayList<String>();
        if (!DisguiseBlitzKitSelectorOverride.isPrestigeUnlocked(info, prestigeLevel) && !(costLine = DisguiseBlitzKitSelectorOverride.getPrestigeCostLine(levelData)).isEmpty()) {
            lore.add(EnumChatFormatting.GRAY + costLine);
            lore.add("");
        }
        lore.add(DisguiseBlitzKitSelectorOverride.buildPrestigePerkLine());
        List<String> itemLines = DisguiseBlitzKitSelectorOverride.buildItemLines(displayKitName, levelData);
        if (!itemLines.isEmpty()) {
            lore.add("");
            lore.addAll(itemLines);
        }
        if (!(requirementLines = DisguiseBlitzKitSelectorOverride.buildPrestigeRequirementLines(prestigeLevel, info)).isEmpty()) {
            lore.add("");
            lore.addAll(requirementLines);
        }
        if (!(footer = DisguiseBlitzKitSelectorOverride.buildPrestigeFooter(displayKitName, prestigeLevel, info, levelData)).isEmpty()) {
            lore.add("");
            lore.addAll(footer);
        }
        DisguiseBlitzKitSelectorOverride.trimTrailingEmptyLines(lore);
        return lore;
    }

    private static String buildPrestigePerkLine() {
        return EnumChatFormatting.GREEN + " + " + EnumChatFormatting.GOLD + "+1 coin " + EnumChatFormatting.GRAY + "per kill";
    }

    private static List<String> buildPrestigeRequirementLines(int prestigeLevel, LocalDisguise.DisguiseKitInfo info) {
        if (prestigeLevel <= 1) {
            return Collections.emptyList();
        }
        boolean prestigeRequirementMet = DisguiseBlitzKitSelectorOverride.hasPreviousPrestigeRequirement(info, prestigeLevel);
        boolean statRequirementMet = DisguiseBlitzKitSelectorOverride.hasPrestigeStatRequirement(info, prestigeLevel);
        ArrayList<String> lines = new ArrayList<String>();
        lines.add(DisguiseBlitzKitSelectorOverride.formatPrestigeRequirementLine("Requires Prestige I", prestigeRequirementMet));
        lines.add(DisguiseBlitzKitSelectorOverride.formatPrestigeRequirementLine("Requires 5,000 kills + 500 wins with this kit", statRequirementMet));
        return lines;
    }

    private static String formatPrestigeRequirementLine(String text, boolean satisfied) {
        if (satisfied) {
            return EnumChatFormatting.GREEN + REQUIREMENT_MET_PREFIX + text;
        }
        return EnumChatFormatting.RED + REQUIREMENT_MISSING_PREFIX + text;
    }

    private static EnumChatFormatting getPrestigeTitleColor(String displayKitName, int prestigeLevel, LocalDisguise.DisguiseKitInfo info, JsonObject levelData) {
        if (DisguiseBlitzKitSelectorOverride.isPrestigeUnlocked(info, prestigeLevel)) {
            return EnumChatFormatting.GREEN;
        }
        if (info == null) {
            return EnumChatFormatting.RED;
        }
        if (prestigeLevel <= 1 ? !DisguiseBlitzKitSelectorOverride.hasUpgradeLevelUnlocked(info, 10) : !DisguiseBlitzKitSelectorOverride.hasPreviousPrestigeRequirement(info, prestigeLevel) || !DisguiseBlitzKitSelectorOverride.hasPrestigeStatRequirement(info, prestigeLevel)) {
            return EnumChatFormatting.RED;
        }
        return DisguiseBlitzKitSelectorOverride.hasKnownPrestigeCoinShortage(levelData) ? EnumChatFormatting.RED : EnumChatFormatting.YELLOW;
    }

    private static List<String> buildPrestigeFooter(String displayKitName, int prestigeLevel, LocalDisguise.DisguiseKitInfo info, JsonObject levelData) {
        ArrayList<String> footer = new ArrayList<String>();
        if (DisguiseBlitzKitSelectorOverride.isPrestigeUnlocked(info, prestigeLevel)) {
            footer.add(EnumChatFormatting.GREEN + "UNLOCKED");
            return footer;
        }
        if (info == null) {
            footer.add(EnumChatFormatting.RED + "You have to unlock the kit first!");
            return footer;
        }
        if (prestigeLevel <= 1) {
            if (!DisguiseBlitzKitSelectorOverride.hasUpgradeLevelUnlocked(info, 10)) {
                footer.add(EnumChatFormatting.RED + "Requires " + displayKitName + " X!");
            } else if (DisguiseBlitzKitSelectorOverride.hasKnownPrestigeCoinShortage(levelData)) {
                footer.add(EnumChatFormatting.RED + "You don't have enough coins!");
            } else {
                footer.add(EnumChatFormatting.YELLOW + "Click to unlock!");
            }
            return footer;
        }
        if (!DisguiseBlitzKitSelectorOverride.hasPreviousPrestigeRequirement(info, prestigeLevel) || !DisguiseBlitzKitSelectorOverride.hasPrestigeStatRequirement(info, prestigeLevel)) {
            return footer;
        }
        if (DisguiseBlitzKitSelectorOverride.hasKnownPrestigeCoinShortage(levelData)) {
            footer.add(EnumChatFormatting.RED + "You don't have enough coins!");
        } else {
            footer.add(EnumChatFormatting.YELLOW + "Click to unlock!");
        }
        return footer;
    }

    private static String getPrestigeCostLine(JsonObject levelData) {
        String line = DisguiseBlitzKitSelectorOverride.getDescriptionLineWithPrefix(levelData, "Cost:");
        if (line.isEmpty()) {
            return "";
        }
        int separator = line.indexOf(124);
        if (separator >= 0) {
            line = line.substring(0, separator).trim();
        }
        return line.replaceFirst("(?i)\\s+coins?$", "").trim();
    }

    private static boolean hasKnownPrestigeCoinShortage(JsonObject levelData) {
        int cost = DisguiseBlitzKitSelectorOverride.getPrestigeCost(levelData);
        Integer disguisedCoins = LocalDisguise.getDisguisedBlitzCoins();
        return cost > 0 && disguisedCoins != null && disguisedCoins < cost;
    }

    private static int getPrestigeCost(JsonObject levelData) {
        String costLine = DisguiseBlitzKitSelectorOverride.getPrestigeCostLine(levelData);
        if (costLine.isEmpty()) {
            return -1;
        }
        int colon = costLine.indexOf(58);
        String value = colon >= 0 ? costLine.substring(colon + 1).trim() : costLine.trim();
        if ((value = value.replaceAll("[^0-9]", "")).isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(value);
        }
        catch (Exception ignored) {
            return -1;
        }
    }

    private static List<String> buildPrestigeBaseLore(ItemStack original) {
        List<String> rawLines = DisguiseBlitzKitSelectorOverride.getOriginalLoreLines(original);
        if (rawLines.isEmpty()) {
            rawLines = DisguiseBlitzKitSelectorOverride.getOriginalTooltipLines(original);
        }
        ArrayList<String> lore = new ArrayList<String>();
        for (String rawLine : rawLines) {
            String text = DisguiseBlitzKitSelectorOverride.cleanText(rawLine);
            if (text.isEmpty()) {
                if (lore.isEmpty()) continue;
                lore.add("");
                continue;
            }
            if (DisguiseBlitzKitSelectorOverride.isPrestigeStatusLine(text)) continue;
            lore.add(rawLine == null ? "" : rawLine);
        }
        DisguiseBlitzKitSelectorOverride.trimTrailingEmptyLines(lore);
        return lore;
    }

    private static boolean isPrestigeStatusLine(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return "unlocked".equalsIgnoreCase(text) || "click to unlock!".equalsIgnoreCase(text) || "you don't have enough coins!".equalsIgnoreCase(text) || "you have to unlock the kit first!".equalsIgnoreCase(text) || lower.startsWith("requires ");
    }

    private static boolean isUpgradeLevelUnlocked(LocalDisguise.DisguiseKitInfo info, int level) {
        return info != null && (info.prestiged || info.level >= level);
    }

    private static boolean hasUpgradeLevelUnlocked(LocalDisguise.DisguiseKitInfo info, int level) {
        return DisguiseBlitzKitSelectorOverride.isUpgradeLevelUnlocked(info, level);
    }

    private static boolean isUpgradeLevelNextUnlockable(LocalDisguise.DisguiseKitInfo info, int level) {
        return info != null && !info.prestiged && info.level + 1 == level;
    }

    private static boolean isPrestigeUnlocked(LocalDisguise.DisguiseKitInfo info, int prestigeLevel) {
        return info != null && info.prestige >= prestigeLevel;
    }

    private static boolean hasPreviousPrestigeRequirement(LocalDisguise.DisguiseKitInfo info, int prestigeLevel) {
        return info != null && info.prestige >= Math.max(0, prestigeLevel - 1);
    }

    private static boolean hasPrestigeStatRequirement(LocalDisguise.DisguiseKitInfo info, int prestigeLevel) {
        if (prestigeLevel <= 1) {
            return true;
        }
        if (DisguiseBlitzKitSelectorOverride.isPrestigeUnlocked(info, prestigeLevel)) {
            return true;
        }
        return info != null && info.kitKills >= 5000 && info.kitTotalWins >= 500;
    }

    private static JsonObject getLevelDataByJsonLevel(String sourceName, int jsonLevel) {
        if (sourceName == null || sourceName.trim().isEmpty()) {
            return null;
        }
        JsonObject kitData = KitEnchantmentsLoader.getKitData(sourceName);
        if (kitData == null) {
            return null;
        }
        JsonElement levelElement = kitData.get(String.valueOf(jsonLevel));
        return levelElement != null && levelElement.isJsonObject() ? levelElement.getAsJsonObject() : null;
    }

    private static String resolveSourceKitName(String displayKitName, LocalDisguise.DisguiseKitInfo info) {
        if (info != null && info.apiName != null && !info.apiName.trim().isEmpty()) {
            return info.apiName;
        }
        return displayKitName;
    }

    private static String getUpgradeLevelCostLine(int level, JsonObject levelData) {
        if (level <= 1) {
            return "Cost: 0";
        }
        return DisguiseBlitzKitSelectorOverride.getDescriptionLineWithPrefix(levelData, "Cost:");
    }

    private static String getUnlockCostLine(JsonObject levelData) {
        String line = DisguiseBlitzKitSelectorOverride.getDescriptionLineWithPrefix(levelData, "Unlock:");
        if (line.isEmpty()) {
            return "";
        }
        String value = line.substring("Unlock:".length()).trim();
        int separator = value.indexOf(124);
        if (separator >= 0) {
            value = value.substring(0, separator).trim();
        }
        return (value = value.replaceAll("(?i)\\s+coins?$", "").trim()).isEmpty() ? "" : "Cost: " + value;
    }

    private static String getDescriptionLineWithPrefix(JsonObject levelData, String prefix) {
        if (levelData == null || prefix == null || !levelData.has("description") || !levelData.get("description").isJsonArray()) {
            return "";
        }
        for (JsonElement element : levelData.getAsJsonArray("description")) {
            String text;
            if (element == null || element.isJsonNull() || (text = DisguiseBlitzKitSelectorOverride.cleanText(element.getAsString())).isEmpty() || !text.regionMatches(true, 0, prefix, 0, prefix.length())) continue;
            return text;
        }
        return "";
    }

    private static String formatShopLevelSuffix(int level, int prestige, boolean prestiged, String levelDisplay) {
        if (prestiged) {
            return prestige >= 2 ? PRESTIGE_TWO_SUFFIX : PRESTIGE_ONE_SUFFIX;
        }
        if (level >= 1 && level <= 10) {
            return " " + DisguiseBlitzKitSelectorOverride.toRomanNumeral(level);
        }
        if (levelDisplay == null || levelDisplay.trim().isEmpty()) {
            return "";
        }
        return " " + levelDisplay.trim();
    }

    private static String toRomanNumeral(int level) {
        String[] romanNumerals = new String[]{"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return level >= 1 && level <= romanNumerals.length ? romanNumerals[level - 1] : String.valueOf(level);
    }

    private static JsonObject getLevelData(LocalDisguise.DisguiseKitInfo info) {
        if (info == null) {
            return null;
        }
        String sourceName = info.apiName != null && !info.apiName.trim().isEmpty() ? info.apiName : info.displayName;
        return DisguiseBlitzKitSelectorOverride.getLevelData(sourceName, info.level, info.prestige, info.prestiged);
    }

    private static JsonObject getLevelData(String sourceName, int level, int prestige, boolean prestiged) {
        int jsonLevel;
        if (sourceName == null || sourceName.trim().isEmpty()) {
            return null;
        }
        JsonObject kitData = KitEnchantmentsLoader.getKitData(sourceName);
        if (kitData == null) {
            return null;
        }
        int n = jsonLevel = prestiged ? Math.min(12, 10 + Math.max(1, prestige)) : Math.max(1, Math.min(10, level));
        if (!kitData.has(String.valueOf(jsonLevel)) || !kitData.get(String.valueOf(jsonLevel)).isJsonObject()) {
            return null;
        }
        return kitData.getAsJsonObject(String.valueOf(jsonLevel));
    }

    private static ItemStack buildIconStack(String displayKitName, JsonObject levelData) {
        ItemStack stack;
        ItemStack configured = DisguiseBlitzKitSelectorOverride.buildConfiguredIconStack(displayKitName, levelData);
        if (configured != null) {
            return configured;
        }
        if (levelData == null) {
            return null;
        }
        if (levelData.has("attackslot") && levelData.get("attackslot").isJsonArray()) {
            JsonArray attack = levelData.getAsJsonArray("attackslot");
            for (JsonElement element : attack) {
                if (element == null || !element.isJsonObject() || (stack = DisguiseBlitzKitSelectorOverride.buildStackFromJson(element.getAsJsonObject(), 1)) == null) continue;
                return stack;
            }
        }
        if (levelData.has("items") && levelData.get("items").isJsonArray()) {
            JsonArray items = levelData.getAsJsonArray("items");
            for (JsonElement element : items) {
                if (element == null || !element.isJsonObject() || (stack = DisguiseBlitzKitSelectorOverride.buildStackFromJson(element.getAsJsonObject(), 1)) == null) continue;
                return stack;
            }
        }
        return null;
    }

    private static ItemStack buildConfiguredIconStack(String displayKitName, JsonObject levelData) {
        String normalized;
        switch (normalized = DisguiseBlitzKitSelectorOverride.normalizeKitKey(displayKitName)) {
            case "horsetamer": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("329_0", 1);
            }
            case "ranger": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("262_0", 1);
            }
            case "archer": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("261_0", 1);
            }
            case "astronaut": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("309_0", 1);
            }
            case "troll": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("383_66", 1);
            }
            case "meatmaster": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("364_0", 1);
            }
            case "reaper": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("292_0", 1);
            }
            case "shark": {
                return DisguiseBlitzKitSelectorOverride.buildTextureValueSkullIcon(SHARK_HEAD_TEXTURE_VALUE, "Shark", "shark-selector-head");
            }
            case "reddragon": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("259_0", 1);
            }
            case "toxicologist": {
                return DisguiseBlitzKitSelectorOverride.buildPotionIconFromLevelData(levelData, 2, null);
            }
            case "donkeytamer": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("54_0", 1);
            }
            case "rogue": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("268_0", 1);
            }
            case "warlock": {
                return DisguiseBlitzKitSelectorOverride.buildPotionIconFromLevelData(levelData, 1, Boolean.TRUE);
            }
            case "slimeyslime": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("341_0", 1);
            }
            case "jockey": {
                return DisguiseBlitzKitSelectorOverride.buildSpawnEggIconFromLevelData(levelData);
            }
            case "golem": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("322_0", 1);
            }
            case "viking": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("333_0", 1);
            }
            case "speleologist": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("257_0", 1);
            }
            case "shadowknight": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("397_1", 1);
            }
            case "baker": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("354_0", 1);
            }
            case "knight": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("283_0", 1);
            }
            case "pigman": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("320_0", 1);
            }
            case "guardian": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("373_3", 1);
            }
            case "phoenix": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("288_0", 1);
            }
            case "paladin": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("307_0", 1);
            }
            case "necromancer": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("367_0", 1);
            }
            case "scout": {
                return DisguiseBlitzKitSelectorOverride.buildPotionIconFromLevelData(levelData, 2, Boolean.FALSE);
            }
            case "hunter": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("31_1", 1);
            }
            case "warrior": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("272_0", 1);
            }
            case "hypetrain": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("342_0", 1);
            }
            case "fisherman": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("346_0", 1);
            }
            case "milkman": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("335_0", 1);
            }
            case "florist": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("81_0", 1);
            }
            case "diver": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("313_0", 1);
            }
            case "arachnologist": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("383_52", 1);
            }
            case "blaze": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("369_0", 1);
            }
            case "wolftamer": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("352_0", 1);
            }
            case "tim": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("384_0", 3);
            }
            case "snowman": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("332_0", 1);
            }
            case "rambo": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("280_0", 1);
            }
            case "farmer": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("344_0", 1);
            }
            case "armorer": {
                return DisguiseBlitzKitSelectorOverride.buildColoredLeatherArmorIcon(299, 32768);
            }
            case "creepertamer": {
                return DisguiseBlitzKitSelectorOverride.buildStackFromImage("46_0", 1);
            }
        }
        return null;
    }

    private static ItemStack buildColoredLeatherArmorIcon(int itemId, int color) {
        ItemStack stack = DisguiseBlitzKitSelectorOverride.buildStackFromImage(itemId + "_0", 1);
        if (stack == null || !(stack.getItem() instanceof ItemArmor)) {
            return stack;
        }
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            stack.setTagCompound(tag);
        }
        NBTTagCompound display = tag.hasKey("display", 10) ? tag.getCompoundTag("display") : new NBTTagCompound();
        display.setInteger("color", color);
        tag.setTag("display", (NBTBase)display);
        return stack;
    }

    private static ItemStack buildCustomSkullIcon(String resourcePath, String fallbackOwner, String profileKey) {
        URL resourceUrl = DisguiseBlitzKitSelectorOverride.class.getResource(resourcePath);
        if (resourceUrl != null) {
            ItemStack skull = DisguiseBlitzKitSelectorOverride.buildStackFromImage("397_3", 1);
            if (skull == null) {
                return null;
            }
            DisguiseBlitzKitSelectorOverride.applySkullTexture(skull, resourceUrl.toString(), profileKey);
            return skull;
        }
        if (fallbackOwner == null || fallbackOwner.trim().isEmpty()) {
            return null;
        }
        ItemStack skull = DisguiseBlitzKitSelectorOverride.buildStackFromImage("397_3", 1);
        if (skull == null) {
            return null;
        }
        NBTTagCompound tag = skull.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            skull.setTagCompound(tag);
        }
        tag.setString("SkullOwner", fallbackOwner);
        return skull;
    }

    private static ItemStack buildFallbackOwnerSkullIcon(String owner) {
        if (owner == null || owner.trim().isEmpty()) {
            return null;
        }
        ItemStack skull = DisguiseBlitzKitSelectorOverride.buildStackFromImage("397_3", 1);
        if (skull == null) {
            return null;
        }
        NBTTagCompound tag = skull.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            skull.setTagCompound(tag);
        }
        tag.setString("SkullOwner", owner);
        return skull;
    }

    private static ItemStack buildTextureValueSkullIcon(String textureValue, String ownerName, String profileKey) {
        if (textureValue == null || textureValue.trim().isEmpty()) {
            return null;
        }
        ItemStack skull = DisguiseBlitzKitSelectorOverride.buildStackFromImage("397_3", 1);
        if (skull == null) {
            return null;
        }
        NBTTagCompound tag = skull.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            skull.setTagCompound(tag);
        }
        NBTTagCompound skullOwner = new NBTTagCompound();
        UUID profileId = UUID.nameUUIDFromBytes(("gomod123:" + profileKey).getBytes(StandardCharsets.UTF_8));
        skullOwner.setString("Id", profileId.toString());
        skullOwner.setString("Name", ownerName == null || ownerName.trim().isEmpty() ? "custom-head" : ownerName);
        NBTTagCompound properties = new NBTTagCompound();
        NBTTagList textures = new NBTTagList();
        NBTTagCompound texture = new NBTTagCompound();
        texture.setString("Value", textureValue);
        textures.appendTag((NBTBase)texture);
        properties.setTag("textures", (NBTBase)textures);
        skullOwner.setTag("Properties", (NBTBase)properties);
        tag.setTag("SkullOwner", (NBTBase)skullOwner);
        return skull;
    }

    private static void applySkullTexture(ItemStack skull, String skinUrl, String profileKey) {
        if (skull == null || skinUrl == null || skinUrl.trim().isEmpty()) {
            return;
        }
        NBTTagCompound tag = skull.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            skull.setTagCompound(tag);
        }
        NBTTagCompound skullOwner = new NBTTagCompound();
        UUID profileId = UUID.nameUUIDFromBytes(("gomod123:" + profileKey).getBytes(StandardCharsets.UTF_8));
        skullOwner.setString("Id", profileId.toString());
        skullOwner.setString("Name", profileKey == null ? "custom-head" : profileKey);
        NBTTagCompound properties = new NBTTagCompound();
        NBTTagList textures = new NBTTagList();
        NBTTagCompound texture = new NBTTagCompound();
        texture.setString("Value", DisguiseBlitzKitSelectorOverride.encodeSkinUrl(skinUrl));
        textures.appendTag((NBTBase)texture);
        properties.setTag("textures", (NBTBase)textures);
        skullOwner.setTag("Properties", (NBTBase)properties);
        tag.setTag("SkullOwner", (NBTBase)skullOwner);
    }

    private static String encodeSkinUrl(String skinUrl) {
        String escapedUrl = skinUrl.replace("\\", "\\\\").replace("\"", "\\\"");
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + escapedUrl + "\"}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static ItemStack buildPotionIconFromLevelData(JsonObject levelData, int amountOverride, Boolean splashOnly) {
        return DisguiseBlitzKitSelectorOverride.buildPotionIconFromLevelData(levelData, amountOverride, splashOnly, null);
    }

    private static ItemStack buildPotionIconFromLevelData(JsonObject levelData, int amountOverride, Boolean splashOnly, String preferredPotionKeyword) {
        JsonObject potionData = DisguiseBlitzKitSelectorOverride.findFirstPotionData(levelData, splashOnly, preferredPotionKeyword);
        if (potionData == null) {
            return null;
        }
        ItemStack potion = DisguiseBlitzKitSelectorOverride.buildStackFromJson(potionData, amountOverride);
        if (potion == null) {
            return null;
        }
        potion.stackSize = Math.max(1, amountOverride);
        return potion;
    }

    private static JsonObject findFirstPotionData(JsonObject levelData, Boolean splashOnly, String preferredPotionKeyword) {
        if (levelData == null || !levelData.has("items") || !levelData.get("items").isJsonArray()) {
            return null;
        }
        String preferredKeyword = preferredPotionKeyword == null ? "" : preferredPotionKeyword.trim().toLowerCase(Locale.ROOT);
        JsonObject firstFallback = null;
        JsonArray items = levelData.getAsJsonArray("items");
        for (JsonElement element : items) {
            boolean splash;
            ImageParts imageParts;
            JsonObject itemData;
            if (element == null || !element.isJsonObject() || !(itemData = element.getAsJsonObject()).has("potion") || itemData.get("potion").isJsonNull() || (imageParts = DisguiseBlitzKitSelectorOverride.parseImageParts(itemData.has("image") && !itemData.get("image").isJsonNull() ? itemData.get("image").getAsString() : null)) == null || imageParts.itemId != 373) continue;
            boolean bl = splash = (imageParts.meta & 0x4000) != 0;
            if (splashOnly != null && splashOnly != splash) continue;
            if (firstFallback == null) {
                firstFallback = itemData;
            }
            if (!preferredKeyword.isEmpty() && !DisguiseBlitzKitSelectorOverride.potionDataContainsKeyword(itemData, preferredKeyword)) continue;
            return itemData;
        }
        return firstFallback;
    }

    private static boolean potionDataContainsKeyword(JsonObject itemData, String keyword) {
        if (itemData == null || keyword == null || keyword.isEmpty() || !itemData.has("potion") || itemData.get("potion").isJsonNull()) {
            return false;
        }
        if (itemData.get("potion").isJsonArray()) {
            for (JsonElement potionElement : itemData.getAsJsonArray("potion")) {
                if (potionElement == null || potionElement.isJsonNull() || !DisguiseBlitzKitSelectorOverride.cleanText(potionElement.getAsString()).toLowerCase(Locale.ROOT).contains(keyword)) continue;
                return true;
            }
            return false;
        }
        return DisguiseBlitzKitSelectorOverride.cleanText(itemData.get("potion").getAsString()).toLowerCase(Locale.ROOT).contains(keyword);
    }

    private static ItemStack buildSpawnEggIconFromLevelData(JsonObject levelData) {
        if (levelData == null || !levelData.has("items") || !levelData.get("items").isJsonArray()) {
            return null;
        }
        JsonArray items = levelData.getAsJsonArray("items");
        for (JsonElement element : items) {
            JsonObject itemData;
            ImageParts imageParts;
            if (element == null || !element.isJsonObject() || (imageParts = DisguiseBlitzKitSelectorOverride.parseImageParts((itemData = element.getAsJsonObject()).has("image") && !itemData.get("image").isJsonNull() ? itemData.get("image").getAsString() : null)) == null || imageParts.itemId != 383) continue;
            return DisguiseBlitzKitSelectorOverride.buildStackFromImage(itemData.get("image").getAsString(), 1);
        }
        return null;
    }

    private static ItemStack buildStackFromImage(String image, int amount) {
        ImageParts parts = DisguiseBlitzKitSelectorOverride.parseImageParts(image);
        if (parts == null) {
            return null;
        }
        Item item = Item.getItemById((int)parts.itemId);
        Block block = Block.getBlockById((int)parts.itemId);
        if (item == null && block != null) {
            item = Item.getItemFromBlock((Block)block);
        }
        if (item == null) {
            return null;
        }
        return new ItemStack(item, Math.max(1, amount), parts.meta);
    }

    private static ItemStack buildStackFromJson(JsonObject itemData, int fallbackAmount) {
        if (itemData == null || !itemData.has("image") || itemData.get("image").isJsonNull()) {
            return null;
        }
        String image = itemData.get("image").getAsString();
        if (image == null || image.trim().isEmpty() || "empty".equalsIgnoreCase(image.trim())) {
            return null;
        }
        int amount = fallbackAmount;
        if (itemData.has("amount") && !itemData.get("amount").isJsonNull()) {
            try {
                amount = Integer.parseInt(itemData.get("amount").getAsString());
            }
            catch (Exception exception) {
                // empty catch block
            }
        }
        return DisguiseBlitzKitSelectorOverride.buildStackFromImage(image, amount);
    }

    private static ImageParts parseImageParts(String image) {
        if (image == null) {
            return null;
        }
        String trimmed = image.trim();
        if (trimmed.isEmpty() || "empty".equalsIgnoreCase(trimmed)) {
            return null;
        }
        String[] parts = trimmed.split("_");
        if (parts.length < 2) {
            return null;
        }
        try {
            return new ImageParts(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        }
        catch (Exception ignored) {
            return null;
        }
    }

    private static List<String> buildUnlockedLore(String displayKitName, int level, int prestige, boolean prestiged, JsonObject levelData, ItemStack original) {
        List<String> capturedHeaderLines;
        CapturedLevelLore captured = DisguiseBlitzKitSelectorOverride.getCapturedLevelLore(displayKitName, level, prestiged);
        List<String> itemLines = captured != null && !captured.itemLines.isEmpty() ? new ArrayList<String>(captured.itemLines) : DisguiseBlitzKitSelectorOverride.buildItemLines(displayKitName, levelData);
        List<String> lore = DisguiseBlitzKitSelectorOverride.buildHeaderLore(displayKitName, original, levelData, itemLines, capturedHeaderLines = captured == null ? DisguiseBlitzKitSelectorOverride.getPrestigeHeaderFallback(displayKitName, prestiged) : captured.headerLines);
        if (!lore.isEmpty() && !itemLines.isEmpty()) {
            lore.add("");
        }
        lore.addAll(itemLines);
        if (!lore.isEmpty()) {
            lore.add("");
        }
        lore.add(EnumChatFormatting.YELLOW + "Click to select!");
        return lore;
    }

    private static String formatLevelSuffix(LocalDisguise.DisguiseKitInfo info) {
        if (info == null) {
            return "";
        }
        return DisguiseBlitzKitSelectorOverride.formatLevelSuffix(info.level, info.prestige, info.prestiged, info.levelDisplay);
    }

    private static String formatLevelSuffix(int level, int prestige, boolean prestiged, String levelDisplay) {
        if (prestiged) {
            return prestige >= 2 ? PRESTIGE_TWO_SUFFIX : PRESTIGE_ONE_SUFFIX;
        }
        if (level <= 1) {
            return "";
        }
        if (level >= 1 && level <= 10) {
            String[] romanNumerals = new String[]{"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
            return " " + romanNumerals[level - 1];
        }
        if (levelDisplay == null || levelDisplay.trim().isEmpty()) {
            return "";
        }
        return " " + levelDisplay.trim();
    }

    private static List<String> buildHeaderLore(String displayKitName, ItemStack original, JsonObject levelData, List<String> itemLines, List<String> capturedHeaderLines) {
        List<String> custom = DisguiseBlitzKitSelectorOverride.buildCustomHeaderLore(displayKitName);
        if (!custom.isEmpty()) {
            return custom;
        }
        if (capturedHeaderLines != null && !capturedHeaderLines.isEmpty()) {
            return new ArrayList<String>(capturedHeaderLines);
        }
        List<String> lore = DisguiseBlitzKitSelectorOverride.extractHeaderLore(DisguiseBlitzKitSelectorOverride.getOriginalTooltipLines(original), itemLines);
        if (!lore.isEmpty()) {
            return lore;
        }
        lore = DisguiseBlitzKitSelectorOverride.extractHeaderLore(DisguiseBlitzKitSelectorOverride.getOriginalLoreLines(original), itemLines);
        if (!lore.isEmpty()) {
            return lore;
        }
        ArrayList<String> fallback = new ArrayList<String>();
        DisguiseBlitzKitSelectorOverride.appendFormattedDescriptionLines(levelData, fallback);
        return fallback;
    }

    private static List<String> buildCustomHeaderLore(String displayKitName) {
        String normalized;
        switch (normalized = DisguiseBlitzKitSelectorOverride.normalizeKitKey(displayKitName)) {
            case "guardian": {
                return DisguiseBlitzKitSelectorOverride.colorizeHeaderLines("Health Boost (1:00)", "", "When Applied:", "+4 Max Health", "Guarding for eternity.");
            }
            case "rogue": {
                return DisguiseBlitzKitSelectorOverride.colorizeHeaderLines("Sneak attack!");
            }
            case "meatmaster": {
                return DisguiseBlitzKitSelectorOverride.colorizeHeaderLines("Harvest those mobs!");
            }
            case "archer": {
                return DisguiseBlitzKitSelectorOverride.colorizeHeaderLines("Ranged attacks.");
            }
            case "speleologist": {
                return DisguiseBlitzKitSelectorOverride.colorizeHeaderLines("It means you like caves.");
            }
        }
        return Collections.emptyList();
    }

    private static List<String> colorizeHeaderLines(String ... rawLines) {
        if (rawLines == null || rawLines.length == 0) {
            return Collections.emptyList();
        }
        ArrayList<String> lines = new ArrayList<String>();
        for (String rawLine : rawLines) {
            if (rawLine == null || rawLine.isEmpty()) {
                lines.add("");
                continue;
            }
            lines.add(DisguiseBlitzKitSelectorOverride.colorizeHeaderLine(rawLine));
        }
        DisguiseBlitzKitSelectorOverride.trimTrailingEmptyLines(lines);
        return lines;
    }

    private static Map<String, Map<Integer, List<String>>> loadCapturedLevelLore() {
        HashMap hashMap;
        InputStream stream = DisguiseBlitzKitSelectorOverride.class.getResourceAsStream(BLITZ_SELECTOR_CAPTURES_RESOURCE);
        if (stream == null) {
            return Collections.emptyMap();
        }
        InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
        try {
            JsonObject root = new JsonParser().parse((Reader)reader).getAsJsonObject();
            HashMap result = new HashMap();
            for (Map.Entry kitEntry : root.entrySet()) {
                String normalizedKit;
                if (kitEntry.getValue() == null || !((JsonElement)kitEntry.getValue()).isJsonObject() || (normalizedKit = DisguiseBlitzKitSelectorOverride.normalizeKitKey((String)kitEntry.getKey())).isEmpty() || normalizedKit.endsWith("prestige")) continue;
                HashMap levels = new HashMap();
                JsonObject levelObject = ((JsonElement)kitEntry.getValue()).getAsJsonObject();
                for (Map.Entry levelEntry : levelObject.entrySet()) {
                    int parsedLevel;
                    if (levelEntry.getValue() == null || !((JsonElement)levelEntry.getValue()).isJsonArray()) continue;
                    try {
                        parsedLevel = Integer.parseInt((String)levelEntry.getKey());
                    }
                    catch (Exception ignored) {
                        continue;
                    }
                    ArrayList<String> rawLines = new ArrayList<String>();
                    for (JsonElement lineElement : ((JsonElement)levelEntry.getValue()).getAsJsonArray()) {
                        if (lineElement == null || lineElement.isJsonNull()) continue;
                        rawLines.add(lineElement.getAsString());
                    }
                    if (rawLines.isEmpty()) continue;
                    levels.put(parsedLevel, rawLines);
                }
                if (levels.isEmpty()) continue;
                result.put(normalizedKit, levels);
            }
            hashMap = result;
        }
        catch (Throwable throwable) {
            try {
                try {
                    reader.close();
                }
                catch (Throwable throwable2) {
                    throwable.addSuppressed(throwable2);
                }
                throw throwable;
            }
            catch (Exception ignored) {
                return Collections.emptyMap();
            }
        }
        try {
            reader.close();
        }
        catch (Exception ignored) {
        }
        return hashMap;
    }

    private static CapturedLevelLore getCapturedLevelLore(String displayKitName, int level, boolean prestiged) {
        if (prestiged) {
            return null;
        }
        Map<Integer, List<String>> levels = CAPTURED_LEVEL_LORE.get(DisguiseBlitzKitSelectorOverride.normalizeKitKey(displayKitName));
        if (levels == null || levels.isEmpty()) {
            return null;
        }
        List<String> rawLines = levels.get(level);
        if (rawLines == null || rawLines.isEmpty()) {
            return null;
        }
        return DisguiseBlitzKitSelectorOverride.buildCapturedLevelLore(rawLines);
    }

    private static List<String> getPrestigeHeaderFallback(String displayKitName, boolean prestiged) {
        if (!prestiged) {
            return Collections.emptyList();
        }
        Map<Integer, List<String>> levels = CAPTURED_LEVEL_LORE.get(DisguiseBlitzKitSelectorOverride.normalizeKitKey(displayKitName));
        if (levels == null || levels.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> rawLines = levels.get(10);
        if (rawLines == null || rawLines.isEmpty()) {
            return Collections.emptyList();
        }
        CapturedLevelLore captured = DisguiseBlitzKitSelectorOverride.buildCapturedLevelLore(rawLines);
        if (captured == null || captured.headerLines.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<String>(captured.headerLines);
    }

    private static CapturedLevelLore buildCapturedLevelLore(List<String> rawLines) {
        String rawLine;
        String text;
        if (rawLines == null || rawLines.isEmpty()) {
            return null;
        }
        ArrayList<String> sanitized = new ArrayList<String>();
        Iterator<String> iterator = rawLines.iterator();
        while (iterator.hasNext() && !DisguiseBlitzKitSelectorOverride.isCapturedFooterLine(text = DisguiseBlitzKitSelectorOverride.cleanText(rawLine = iterator.next()))) {
            if (text.isEmpty()) {
                if (sanitized.isEmpty()) continue;
                sanitized.add("");
                continue;
            }
            sanitized.add(rawLine == null ? "" : rawLine);
        }
        DisguiseBlitzKitSelectorOverride.trimTrailingEmptyLines(sanitized);
        if (sanitized.isEmpty()) {
            return null;
        }
        int firstItemIndex = -1;
        for (int i = 0; i < sanitized.size(); ++i) {
            if (!DisguiseBlitzKitSelectorOverride.isCapturedBulletLine((String)sanitized.get(i))) continue;
            firstItemIndex = i;
            break;
        }
        ArrayList<String> headerLines = new ArrayList<String>();
        ArrayList<String> itemLines = new ArrayList<String>();
        if (firstItemIndex < 0) {
            for (String rawLine2 : sanitized) {
                if (rawLine2 == null || DisguiseBlitzKitSelectorOverride.cleanText(rawLine2).isEmpty()) {
                    headerLines.add("");
                    continue;
                }
                headerLines.add(DisguiseBlitzKitSelectorOverride.colorizeHeaderLine(DisguiseBlitzKitSelectorOverride.cleanText(rawLine2)));
            }
        } else {
            String rawLine3;
            int i;
            for (i = 0; i < firstItemIndex; ++i) {
                rawLine3 = (String)sanitized.get(i);
                if (rawLine3 == null || DisguiseBlitzKitSelectorOverride.cleanText(rawLine3).isEmpty()) {
                    headerLines.add("");
                    continue;
                }
                headerLines.add(DisguiseBlitzKitSelectorOverride.colorizeHeaderLine(DisguiseBlitzKitSelectorOverride.cleanText(rawLine3)));
            }
            for (i = firstItemIndex; i < sanitized.size(); ++i) {
                rawLine3 = (String)sanitized.get(i);
                if (rawLine3 == null || DisguiseBlitzKitSelectorOverride.cleanText(rawLine3).isEmpty()) {
                    if (itemLines.isEmpty()) continue;
                    itemLines.add("");
                    continue;
                }
                String formatted = DisguiseBlitzKitSelectorOverride.formatCapturedItemLine(rawLine3);
                if (formatted.isEmpty()) continue;
                itemLines.add(formatted);
            }
        }
        DisguiseBlitzKitSelectorOverride.trimTrailingEmptyLines(headerLines);
        DisguiseBlitzKitSelectorOverride.trimTrailingEmptyLines(itemLines);
        return new CapturedLevelLore(headerLines, itemLines);
    }

    private static boolean isCapturedFooterLine(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if ("unlocked".equalsIgnoreCase(text) || "click to unlock!".equalsIgnoreCase(text) || "click to view upgrades!".equalsIgnoreCase(text) || "you don't have enough coins!".equalsIgnoreCase(text)) {
            return true;
        }
        if (lower.startsWith("cost:") || lower.startsWith("requires ")) {
            return true;
        }
        if (lower.startsWith("you have to unlock ") && lower.endsWith(" first!")) {
            return true;
        }
        return lower.startsWith("+ unlocks ") && lower.contains(" prestige ");
    }

    private static boolean isCapturedBulletLine(String rawLine) {
        if (rawLine == null) {
            return false;
        }
        return rawLine.matches("^\\s*(?:\\u25AA|\\?)\\s+.+$");
    }

    private static String formatCapturedItemLine(String rawLine) {
        String content;
        if (rawLine == null) {
            return "";
        }
        Matcher matcher = Pattern.compile(LORE_LINE_PATTERN).matcher(rawLine);
        if (!matcher.matches()) {
            return "";
        }
        String indentation = matcher.group(1) == null ? "" : matcher.group(1);
        String string = content = matcher.group(2) == null ? "" : matcher.group(2).trim();
        if (content.isEmpty()) {
            return "";
        }
        if (indentation.length() >= 3) {
            return EnumChatFormatting.DARK_GRAY + INDENTED_LORE_BULLET + content;
        }
        Matcher amountMatcher = Pattern.compile("^(.*?)(?: x(\\d+))$").matcher(content);
        String baseName = content;
        String amountSuffix = "";
        if (amountMatcher.matches()) {
            baseName = amountMatcher.group(1).trim();
            amountSuffix = amountMatcher.group(2);
        }
        StringBuilder line = new StringBuilder();
        line.append(DisguiseBlitzKitSelectorOverride.getCapturedItemColor(baseName)).append(LORE_BULLET).append(" ").append(baseName);
        if (!amountSuffix.isEmpty()) {
            line.append(EnumChatFormatting.DARK_GRAY).append(" x").append(amountSuffix);
        }
        return line.toString();
    }

    private static EnumChatFormatting getCapturedItemColor(String itemName) {
        String lower;
        String string = lower = itemName == null ? "" : itemName.toLowerCase(Locale.ROOT);
        if ("wither skeleton skull".equals(lower) || "skull item".equals(lower)) {
            return EnumChatFormatting.GRAY;
        }
        if ("egg".equals(lower)) {
            return EnumChatFormatting.GRAY;
        }
        if (lower.startsWith("potion of ") || lower.startsWith("splash potion of ") || lower.startsWith("bottle o' enchanting")) {
            return EnumChatFormatting.GRAY;
        }
        if (lower.endsWith(" egg") || lower.contains(" spawn egg")) {
            return EnumChatFormatting.LIGHT_PURPLE;
        }
        if (DisguiseBlitzKitSelectorOverride.isWeaponOrTool(itemName)) {
            return EnumChatFormatting.YELLOW;
        }
        if (DisguiseBlitzKitSelectorOverride.isGear(null, itemName)) {
            return EnumChatFormatting.BLUE;
        }
        return EnumChatFormatting.GRAY;
    }

    private static List<String> getOriginalTooltipLines(ItemStack stack) {
        Minecraft mc = Minecraft.getMinecraft();
        if (stack == null || mc == null || mc.thePlayer == null) {
            return Collections.emptyList();
        }
        try {
            List tooltip = stack.getTooltip((EntityPlayer)mc.thePlayer, false);
            if (tooltip == null || tooltip.size() <= 1) {
                return Collections.emptyList();
            }
            ArrayList<String> lines = new ArrayList<String>();
            for (int i = 1; i < tooltip.size(); ++i) {
                lines.add((String)tooltip.get(i));
            }
            return lines;
        }
        catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private static void appendAdvancedTooltipTail(List<String> tooltip, List<String> basicVanilla, List<String> advVanilla) {
        if (advVanilla == null || advVanilla.isEmpty() || basicVanilla == null || basicVanilla.isEmpty()) {
            return;
        }
        int start = basicVanilla.size();
        if (start > advVanilla.size()) {
            return;
        }
        for (int i = start; i < advVanilla.size(); ++i) {
            String line = advVanilla.get(i);
            if (line == null || line.isEmpty()) {
                continue;
            }
            tooltip.add(line);
        }
    }

    private static List<String> getVanillaTooltipLinesWithoutSelectorOverride(ItemStack stack, boolean advanced) {
        Minecraft mc = Minecraft.getMinecraft();
        if (stack == null || mc == null || mc.thePlayer == null) {
            return Collections.emptyList();
        }
        try {
            ItemStack copy = stack.copy();
            NBTTagCompound tag = copy.getTagCompound();
            if (tag != null) {
                tag.setBoolean(SELECTOR_TOOLTIP_TAG, false);
            }
            List tooltip = copy.getTooltip((EntityPlayer) mc.thePlayer, advanced);
            if (tooltip == null || tooltip.isEmpty()) {
                return Collections.emptyList();
            }
            ArrayList<String> lines = new ArrayList<String>();
            for (Object line : tooltip) {
                if (line instanceof String) {
                    lines.add((String) line);
                }
            }
            return lines;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private static List<String> getOriginalLoreLines(ItemStack stack) {
        if (stack == null) {
            return Collections.emptyList();
        }
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null || !tag.hasKey("display", 10)) {
            return Collections.emptyList();
        }
        NBTTagCompound display = tag.getCompoundTag("display");
        if (!display.hasKey("Lore", 9)) {
            return Collections.emptyList();
        }
        NBTTagList loreTag = display.getTagList("Lore", 8);
        if (loreTag == null || loreTag.tagCount() <= 0) {
            return Collections.emptyList();
        }
        ArrayList<String> lines = new ArrayList<String>();
        for (int i = 0; i < loreTag.tagCount(); ++i) {
            lines.add(loreTag.getStringTagAt(i));
        }
        return lines;
    }

    private static List<String> extractHeaderLore(List<String> rawLore, List<String> itemLines) {
        if (rawLore == null || rawLore.isEmpty()) {
            return Collections.emptyList();
        }
        HashSet<String> itemLineKeys = new HashSet<String>();
        if (itemLines != null) {
            for (String itemLine : itemLines) {
                String key = DisguiseBlitzKitSelectorOverride.normalizeLoreComparisonText(itemLine);
                if (key.isEmpty()) continue;
                itemLineKeys.add(key);
            }
        }
        List<String> lore = new ArrayList<String>();
        boolean pendingBlank = false;
        int blankSections = 0;
        int nonEmptyLines = 0;
        for (String rawLine : rawLore) {
            String text = DisguiseBlitzKitSelectorOverride.cleanText(rawLine);
            if (text.isEmpty()) {
                if (lore.isEmpty()) continue;
                pendingBlank = true;
                continue;
            }
            if ("Click to select!".equalsIgnoreCase(text)) break;
            if (DisguiseBlitzKitSelectorOverride.shouldHideHeaderLine(text)) continue;
            if (DisguiseBlitzKitSelectorOverride.isLikelyItemLine(text, itemLineKeys)) break;
            if (pendingBlank) {
                if (++blankSections >= 2) break;
                if (!lore.isEmpty() && !lore.get(lore.size() - 1).isEmpty()) {
                    lore.add("");
                }
                pendingBlank = false;
            }
            if (nonEmptyLines >= 6) break;
            lore.add(DisguiseBlitzKitSelectorOverride.colorizeHeaderLine(text));
            ++nonEmptyLines;
        }
        lore = DisguiseBlitzKitSelectorOverride.filterLeadingPotionEffectLines(lore);
        DisguiseBlitzKitSelectorOverride.trimTrailingEmptyLines(lore);
        return lore;
    }

    private static List<String> filterLeadingPotionEffectLines(List<String> lore) {
        if (lore == null || lore.isEmpty()) {
            return lore;
        }
        int whenAppliedIndex = -1;
        for (int i = 0; i < lore.size(); ++i) {
            if (!"When Applied:".equalsIgnoreCase(DisguiseBlitzKitSelectorOverride.cleanText(lore.get(i)))) continue;
            whenAppliedIndex = i;
            break;
        }
        if (whenAppliedIndex <= 0) {
            return lore;
        }
        ArrayList<Integer> effectIndexes = new ArrayList<Integer>();
        for (int cursor = 0; cursor < whenAppliedIndex; ++cursor) {
            String text = DisguiseBlitzKitSelectorOverride.cleanText(lore.get(cursor));
            if (text.isEmpty()) {
                ++cursor;
                break;
            }
            if (!DisguiseBlitzKitSelectorOverride.isPotionEffectHeaderLine(text)) {
                return lore;
            }
            effectIndexes.add(cursor);
        }
        if (effectIndexes.size() <= 1) {
            return lore;
        }
        ArrayList<String> filtered = new ArrayList<String>();
        for (int i = 0; i < lore.size(); ++i) {
            if (effectIndexes.contains(i)) continue;
            filtered.add(lore.get(i));
        }
        while (!filtered.isEmpty() && ((String)filtered.get(0)).isEmpty()) {
            filtered.remove(0);
        }
        return filtered;
    }

    private static boolean isPotionEffectHeaderLine(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return text.matches("^[A-Za-z][A-Za-z '\\-]+ \\(\\d+:\\d{2}\\)$");
    }

    private static void appendFormattedDescriptionLines(JsonObject levelData, List<String> lore) {
        if (levelData == null || lore == null || !levelData.has("description") || !levelData.get("description").isJsonArray()) {
            return;
        }
        JsonArray description = levelData.getAsJsonArray("description");
        for (JsonElement element : description) {
            String text;
            if (element == null || element.isJsonNull() || (text = DisguiseBlitzKitSelectorOverride.cleanText(element.getAsString())).isEmpty() || DisguiseBlitzKitSelectorOverride.shouldHideHeaderLine(text)) continue;
            lore.add(DisguiseBlitzKitSelectorOverride.colorizeHeaderLine(text));
        }
        DisguiseBlitzKitSelectorOverride.trimTrailingEmptyLines(lore);
    }

    private static boolean shouldHideHeaderLine(String text) {
        if (text == null) {
            return true;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("attack damage") || lower.contains("when in main hand")) {
            return true;
        }
        if ("unlock in the shop!".equals(lower) || "locked".equals(lower) || lower.contains("does not own this kit")) {
            return true;
        }
        if (lower.startsWith("cost:") || lower.startsWith("unlock:") || "unlocked by default".equals(lower)) {
            return true;
        }
        if (lower.contains(" kit exp") || lower.contains("ultimate kit experience")) {
            return true;
        }
        if (lower.matches(".*\\b\\d[\\d,\\.]*\\s*xp\\b.*") || lower.matches(".*\\bkit\\s*exp\\b.*")) {
            return true;
        }
        return lower.contains("requires ") && (lower.contains(" level") || lower.contains(" wins") || lower.contains(" kills") || lower.contains(" xp") || lower.contains(" coins"));
    }

    private static String colorizeHeaderLine(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.endsWith(":")) {
            return EnumChatFormatting.LIGHT_PURPLE + text;
        }
        if (text.startsWith("+") || text.startsWith("-")) {
            return EnumChatFormatting.BLUE + text;
        }
        return EnumChatFormatting.GRAY + text;
    }

    private static boolean isLikelyItemLine(String text, Set<String> itemLineKeys) {
        String normalized = DisguiseBlitzKitSelectorOverride.normalizeLoreComparisonText(text);
        if (normalized.isEmpty()) {
            return false;
        }
        if (normalized.matches("x\\d+") || normalized.matches("\\d+:\\d{2}")) {
            return true;
        }
        if (itemLineKeys == null || itemLineKeys.isEmpty()) {
            return false;
        }
        if (itemLineKeys.contains(normalized)) {
            return true;
        }
        for (String key : itemLineKeys) {
            if (!normalized.startsWith(key + " ") && !normalized.endsWith(" " + key) && !normalized.contains(key + " x")) continue;
            return true;
        }
        return false;
    }

    private static String normalizeLoreComparisonText(String text) {
        String cleaned = DisguiseBlitzKitSelectorOverride.cleanText(text);
        if (cleaned.isEmpty()) {
            return "";
        }
        cleaned = cleaned.replaceFirst("^[\\-\\u2022\\u2219\\u00B7\\s]+", "");
        return cleaned.toLowerCase(Locale.ROOT).trim();
    }

    private static void trimTrailingEmptyLines(List<String> lore) {
        if (lore == null) {
            return;
        }
        while (!lore.isEmpty() && lore.get(lore.size() - 1).isEmpty()) {
            lore.remove(lore.size() - 1);
        }
    }

    private static void appendDescriptionLines(JsonObject levelData, List<String> lore, boolean includeCosts) {
        if (levelData == null || lore == null || !levelData.has("description") || !levelData.get("description").isJsonArray()) {
            return;
        }
        JsonArray description = levelData.getAsJsonArray("description");
        for (JsonElement element : description) {
            String text;
            if (element == null || element.isJsonNull() || (text = DisguiseBlitzKitSelectorOverride.cleanText(element.getAsString())).isEmpty()) continue;
            boolean isCost = text.toLowerCase(Locale.ROOT).startsWith("cost:");
            if (!includeCosts && (isCost || "Unlocked by default".equalsIgnoreCase(text))) continue;
            lore.add(EnumChatFormatting.LIGHT_PURPLE + text);
        }
    }

    private static List<String> buildItemLines(String displayKitName, JsonObject levelData) {
        JsonArray items;
        if (levelData == null) {
            return Collections.emptyList();
        }
        ArrayList<ItemLineGroup> groups = new ArrayList<ItemLineGroup>();
        int[] orderCounter = new int[]{0};
        if (levelData.has("attackslot") && levelData.get("attackslot").isJsonArray()) {
            DisguiseBlitzKitSelectorOverride.appendItemArray(levelData.getAsJsonArray("attackslot"), groups, orderCounter);
        }
        if (levelData.has("items") && levelData.get("items").isJsonArray() && !DisguiseBlitzKitSelectorOverride.appendGuardianPrestigeTwoItemLines(displayKitName, items = levelData.getAsJsonArray("items"), groups, orderCounter)) {
            DisguiseBlitzKitSelectorOverride.appendItemArray(items, groups, orderCounter);
        }
        Collections.sort(groups, Comparator.comparingInt(rec$ -> ((ItemLineGroup)rec$).getCategory()).thenComparingInt(rec$ -> ((ItemLineGroup)rec$).getOrder()));
        ArrayList<String> lines = new ArrayList<String>();
        for (ItemLineGroup group : groups) {
            lines.addAll(group.lines);
        }
        return lines;
    }

    private static void appendItemArray(JsonArray array, List<ItemLineGroup> groups, int[] orderCounter) {
        if (array == null || groups == null || orderCounter == null || orderCounter.length == 0) {
            return;
        }
        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject itemData = element.getAsJsonObject();
            int n = orderCounter[0];
            orderCounter[0] = n + 1;
            ItemLineGroup group = DisguiseBlitzKitSelectorOverride.buildItemLineGroup(itemData, n);
            if (group == null) continue;
            groups.add(group);
        }
    }

    private static ItemLineGroup buildItemLineGroup(JsonObject itemData, int order) {
        List<String> lines = DisguiseBlitzKitSelectorOverride.buildLinesForItem(itemData);
        if (lines.isEmpty()) {
            return null;
        }
        return new ItemLineGroup(lines, DisguiseBlitzKitSelectorOverride.classifyItemCategory(itemData), order);
    }

    private static List<String> buildLinesForItem(JsonObject itemData) {
        String customName;
        String name;
        ArrayList<String> lines = new ArrayList<String>();
        if (itemData == null) {
            return lines;
        }
        int amount = DisguiseBlitzKitSelectorOverride.getItemAmount(itemData);
        if (itemData.has("potion") && !itemData.get("potion").isJsonNull()) {
            boolean appendedAmount = false;
            if (itemData.get("potion").isJsonArray()) {
                for (JsonElement potionElement : itemData.getAsJsonArray("potion")) {
                    PotionLoreEntry potionEntry;
                    if (potionElement == null || potionElement.isJsonNull() || (potionEntry = DisguiseBlitzKitSelectorOverride.parsePotionLoreEntry(potionElement.getAsString())) == null) continue;
                    String line = EnumChatFormatting.GRAY + LORE_BULLET + " " + potionEntry.name;
                    if (!appendedAmount && amount > 1) {
                        line = line + EnumChatFormatting.DARK_GRAY + " x" + amount;
                        appendedAmount = true;
                    }
                    lines.add(line);
                    if (potionEntry.duration == null || potionEntry.duration.isEmpty()) continue;
                    lines.add(EnumChatFormatting.DARK_GRAY + INDENTED_LORE_BULLET + potionEntry.duration);
                }
            } else {
                PotionLoreEntry potionEntry = DisguiseBlitzKitSelectorOverride.parsePotionLoreEntry(itemData.get("potion").getAsString());
                if (potionEntry != null) {
                    String line = EnumChatFormatting.GRAY + LORE_BULLET + " " + potionEntry.name;
                    if (amount > 1) {
                        line = line + EnumChatFormatting.DARK_GRAY + " x" + amount;
                    }
                    lines.add(line);
                    if (potionEntry.duration != null && !potionEntry.duration.isEmpty()) {
                        lines.add(EnumChatFormatting.DARK_GRAY + INDENTED_LORE_BULLET + potionEntry.duration);
                    }
                }
            }
            return lines;
        }
        ItemStack stack = DisguiseBlitzKitSelectorOverride.buildStackFromJson(itemData, amount);
        String string = name = stack == null ? "" : DisguiseBlitzKitSelectorOverride.cleanText(stack.getDisplayName());
        if (itemData.has("name") && !itemData.get("name").isJsonNull() && !(customName = DisguiseBlitzKitSelectorOverride.cleanText(itemData.get("name").getAsString())).isEmpty()) {
            name = customName;
        }
        if (name.isEmpty()) {
            return lines;
        }
        ArrayList<String> enchants = new ArrayList<String>();
        if (itemData.has("enchantments") && itemData.get("enchantments").isJsonArray()) {
            JsonArray enchArray = itemData.getAsJsonArray("enchantments");
            for (JsonElement enchantment : enchArray) {
                String text;
                if (enchantment == null || enchantment.isJsonNull() || (text = DisguiseBlitzKitSelectorOverride.cleanText(enchantment.getAsString())).isEmpty()) continue;
                enchants.add(text);
            }
        }
        StringBuilder line = new StringBuilder();
        line.append(DisguiseBlitzKitSelectorOverride.getItemColor(stack, name)).append(LORE_BULLET).append(" ");
        line.append(name);
        if (amount > 1) {
            line.append(EnumChatFormatting.DARK_GRAY).append(" x").append(amount);
        }
        lines.add(line.toString());
        for (String enchant : enchants) {
            lines.add(EnumChatFormatting.DARK_GRAY + INDENTED_LORE_BULLET + enchant);
        }
        return lines;
    }

    private static boolean appendGuardianPrestigeTwoItemLines(String displayKitName, JsonArray items, List<ItemLineGroup> groups, int[] orderCounter) {
        if (!"guardian".equals(DisguiseBlitzKitSelectorOverride.normalizeKitKey(displayKitName)) || items == null || groups == null || orderCounter == null || orderCounter.length == 0) {
            return false;
        }
        JsonObject combinedPotionItem = null;
        JsonObject healthBoostItem = null;
        for (JsonElement element : items) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject itemData = element.getAsJsonObject();
            if (DisguiseBlitzKitSelectorOverride.isGuardianPrestigeTwoCombinedPotionItem(itemData)) {
                combinedPotionItem = itemData;
                continue;
            }
            if (DisguiseBlitzKitSelectorOverride.isGuardianPrestigeTwoHealthBoostItem(itemData)) {
                healthBoostItem = itemData;
                continue;
            }
            int n = orderCounter[0];
            orderCounter[0] = n + 1;
            ItemLineGroup group = DisguiseBlitzKitSelectorOverride.buildItemLineGroup(itemData, n);
            if (group == null) continue;
            groups.add(group);
        }
        if (combinedPotionItem == null || healthBoostItem == null) {
            return false;
        }
        int n = orderCounter[0];
        orderCounter[0] = n + 1;
        ItemLineGroup healthGroup = DisguiseBlitzKitSelectorOverride.buildItemLineGroup(healthBoostItem, n);
        if (healthGroup != null) {
            groups.add(healthGroup);
        }
        int n2 = orderCounter[0];
        orderCounter[0] = n2 + 1;
        groups.add(new ItemLineGroup(DisguiseBlitzKitSelectorOverride.buildGuardianPrestigeTwoPotionLines(combinedPotionItem), 4, n2));
        return true;
    }

    private static boolean isGuardianPrestigeTwoCombinedPotionItem(JsonObject itemData) {
        List<PotionLoreEntry> entries = DisguiseBlitzKitSelectorOverride.getPotionLoreEntries(itemData);
        if (entries.size() != 2 || DisguiseBlitzKitSelectorOverride.getItemAmount(itemData) != 3) {
            return false;
        }
        boolean hasHeal = false;
        boolean hasResistance = false;
        for (PotionLoreEntry entry : entries) {
            String name = DisguiseBlitzKitSelectorOverride.cleanText(entry.name);
            if ("Potion of Heal II".equalsIgnoreCase(name)) {
                hasHeal = true;
                continue;
            }
            if (!"Potion of Resistance II".equalsIgnoreCase(name)) continue;
            hasResistance = true;
        }
        return hasHeal && hasResistance;
    }

    private static boolean isGuardianPrestigeTwoHealthBoostItem(JsonObject itemData) {
        List<PotionLoreEntry> entries = DisguiseBlitzKitSelectorOverride.getPotionLoreEntries(itemData);
        if (entries.size() != 1) {
            return false;
        }
        PotionLoreEntry entry = entries.get(0);
        return "Potion of Health Boost I".equalsIgnoreCase(DisguiseBlitzKitSelectorOverride.cleanText(entry.name)) && "20:00".equals(entry.duration);
    }

    private static List<String> buildGuardianPrestigeTwoPotionLines(JsonObject itemData) {
        List<PotionLoreEntry> entries = DisguiseBlitzKitSelectorOverride.getPotionLoreEntries(itemData);
        if (entries.size() != 2) {
            return DisguiseBlitzKitSelectorOverride.buildLinesForItem(itemData);
        }
        ArrayList<String> names = new ArrayList<String>();
        String duration = null;
        for (PotionLoreEntry entry : entries) {
            String name = DisguiseBlitzKitSelectorOverride.cleanText(entry.name);
            if (!name.isEmpty()) {
                names.add(name);
            }
            if (duration != null || entry.duration == null || entry.duration.isEmpty()) continue;
            duration = entry.duration;
        }
        if (names.size() != 2) {
            return DisguiseBlitzKitSelectorOverride.buildLinesForItem(itemData);
        }
        ArrayList<String> lines = new ArrayList<String>();
        StringBuilder line = new StringBuilder();
        line.append(EnumChatFormatting.GRAY).append(LORE_BULLET).append(" ").append((String)names.get(0)).append(" and ").append((String)names.get(1));
        int amount = DisguiseBlitzKitSelectorOverride.getItemAmount(itemData);
        if (amount > 1) {
            line.append(EnumChatFormatting.DARK_GRAY).append(" x").append(amount);
        }
        lines.add(line.toString());
        if (duration != null && !duration.isEmpty()) {
            lines.add(EnumChatFormatting.DARK_GRAY + INDENTED_LORE_BULLET + duration);
        }
        return lines;
    }

    private static List<PotionLoreEntry> getPotionLoreEntries(JsonObject itemData) {
        ArrayList<PotionLoreEntry> entries = new ArrayList<PotionLoreEntry>();
        if (itemData == null || !itemData.has("potion") || itemData.get("potion").isJsonNull()) {
            return entries;
        }
        if (itemData.get("potion").isJsonArray()) {
            for (JsonElement potionElement : itemData.getAsJsonArray("potion")) {
                PotionLoreEntry entry;
                if (potionElement == null || potionElement.isJsonNull() || (entry = DisguiseBlitzKitSelectorOverride.parsePotionLoreEntry(potionElement.getAsString())) == null) continue;
                entries.add(entry);
            }
            return entries;
        }
        PotionLoreEntry entry = DisguiseBlitzKitSelectorOverride.parsePotionLoreEntry(itemData.get("potion").getAsString());
        if (entry != null) {
            entries.add(entry);
        }
        return entries;
    }

    private static EnumChatFormatting getItemColor(ItemStack stack, String name) {
        String lower;
        if (stack != null && stack.getItem() == Items.spawn_egg) {
            return EnumChatFormatting.LIGHT_PURPLE;
        }
        String string = lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if ("egg".equals(lower)) {
            return EnumChatFormatting.GRAY;
        }
        if ("wither skeleton skull".equals(lower) || "skull item".equals(lower)) {
            return EnumChatFormatting.GRAY;
        }
        if (lower.contains("sword") || lower.contains("axe") || lower.contains("bow") || lower.contains("rod") || lower.contains("pickaxe") || lower.contains("shovel") || lower.contains("spade") || lower.contains("hoe")) {
            return EnumChatFormatting.YELLOW;
        }
        if (DisguiseBlitzKitSelectorOverride.isGear(stack, name)) {
            return EnumChatFormatting.BLUE;
        }
        return EnumChatFormatting.GRAY;
    }

    private static int classifyItemCategory(JsonObject itemData) {
        String customName;
        String name;
        if (itemData != null && itemData.has("potion") && !itemData.get("potion").isJsonNull()) {
            return 4;
        }
        int amount = DisguiseBlitzKitSelectorOverride.getItemAmount(itemData);
        ItemStack stack = DisguiseBlitzKitSelectorOverride.buildStackFromJson(itemData, amount);
        String string = name = stack == null ? "" : DisguiseBlitzKitSelectorOverride.cleanText(stack.getDisplayName());
        if (itemData != null && itemData.has("name") && !itemData.get("name").isJsonNull() && !(customName = DisguiseBlitzKitSelectorOverride.cleanText(itemData.get("name").getAsString())).isEmpty()) {
            name = customName;
        }
        if (stack != null && stack.getItem() == Items.spawn_egg) {
            return 2;
        }
        if (DisguiseBlitzKitSelectorOverride.isWeaponOrTool(name)) {
            return 0;
        }
        if (DisguiseBlitzKitSelectorOverride.isGear(stack, name)) {
            return 1;
        }
        if (stack != null && stack.getItem() instanceof ItemFood) {
            return 3;
        }
        return 4;
    }

    private static boolean isWeaponOrTool(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.contains("sword") || lower.contains("axe") || lower.contains("bow") || lower.contains("rod") || lower.contains("pickaxe") || lower.contains("shovel") || lower.contains("spade") || lower.contains("hoe");
    }

    private static boolean isGear(ItemStack stack, String name) {
        if (stack != null && stack.getItem() instanceof ItemArmor) {
            return true;
        }
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.contains("helmet") || lower.contains("cap") || lower.contains("chestplate") || lower.contains("tunic") || lower.contains("leggings") || lower.contains("pants") || lower.contains("boots") || lower.contains("skull") || lower.contains("head");
    }

    private static int getItemAmount(JsonObject itemData) {
        int amount = 1;
        if (itemData == null || !itemData.has("amount") || itemData.get("amount").isJsonNull()) {
            return amount;
        }
        try {
            amount = Integer.parseInt(itemData.get("amount").getAsString());
        }
        catch (Exception exception) {
            // empty catch block
        }
        return Math.max(1, amount);
    }

    private static PotionLoreEntry parsePotionLoreEntry(String potionText) {
        String candidate;
        Object text = DisguiseBlitzKitSelectorOverride.cleanText(potionText);
        if (((String)text).isEmpty()) {
            return null;
        }
        if (((String)text).toLowerCase(Locale.ROOT).contains("abortion")) {
            text = ((String)text).replace("Abortion", "Absorption").replace("abortion", "Absorption");
        }
        String duration = null;
        int durationStart = ((String)text).lastIndexOf(" (");
        if (durationStart > 0 && ((String)text).endsWith(")") && (candidate = ((String)text).substring(durationStart + 2, ((String)text).length() - 1).trim()).matches("\\d+:\\d{2}")) {
            duration = candidate;
            text = ((String)text).substring(0, durationStart).trim();
        }
        if (!((String)text).toLowerCase(Locale.ROOT).startsWith("potion of ")) {
            if (((String)text).toLowerCase(Locale.ROOT).startsWith("instant ")) {
                text = ((String)text).substring("instant ".length());
            }
            if (!DisguiseBlitzKitSelectorOverride.endsWithRomanNumeral((String)text) && !((String)text).toLowerCase(Locale.ROOT).contains("milk")) {
                text = (String)text + " I";
            }
            text = "Potion of " + (String)text;
        }
        return new PotionLoreEntry((String)text, duration);
    }

    private static boolean endsWithRomanNumeral(String text) {
        if (text == null) {
            return false;
        }
        return text.matches(".*\\b(?:I|II|III|IV|V|VI|VII|VIII|IX|X)\\b$");
    }

    private static void hideAttributeModifiers(ItemStack stack) {
        int hideFlags;
        if (stack == null) {
            return;
        }
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            stack.setTagCompound(tag);
        }
        int n = hideFlags = tag.hasKey("HideFlags", 99) ? tag.getInteger("HideFlags") : 0;
        if (stack.getItem() == Items.potionitem) {
            tag.setInteger("HideFlags", 255);
            return;
        }
        tag.setInteger("HideFlags", hideFlags | 2);
    }

    private static void setDisplay(ItemStack stack, String name, List<String> lore) {
        if (stack == null) {
            return;
        }
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            stack.setTagCompound(tag);
        }
        NBTTagCompound display = tag.getCompoundTag("display");
        display.setString("Name", name == null ? "" : name);
        NBTTagList loreTag = new NBTTagList();
        if (lore != null) {
            for (String line : lore) {
                loreTag.appendTag((NBTBase)new NBTTagString(line == null ? "" : line));
            }
        }
        display.setTag("Lore", (NBTBase)loreTag);
        tag.setTag("display", (NBTBase)display);
        tag.setBoolean(SELECTOR_TOOLTIP_TAG, true);
    }

    private static String cleanText(String text) {
        if (text == null) {
            return "";
        }
        String stripped = EnumChatFormatting.getTextWithoutFormattingCodes((String)text);
        return stripped == null ? "" : stripped.trim();
    }

    private static boolean isOnHypixelServer(Minecraft mc) {
        try {
            return GameContext.isOnHypixel(mc);
        }
        catch (Throwable ignored) {
            return false;
        }
    }

    private static String normalizeKitKey(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String buildRewriteCacheKey(String targetSignature, int slot, SlotRewriteSpec spec, LocalDisguise.DisguiseKitInfo info, ItemStack original) {
        StringBuilder key = new StringBuilder();
        key.append(targetSignature == null ? "" : targetSignature).append('|').append(slot).append('|').append(activeChestContext == null ? "" : DisguiseBlitzKitSelectorOverride.activeChestContext.mode.name()).append('|').append(spec == null ? "" : spec.kind.name()).append('|').append(spec == null ? "" : DisguiseBlitzKitSelectorOverride.normalizeKitKey(spec.kitName)).append('|').append(spec == null ? 0 : spec.level);
        if (info != null) {
            key.append('|').append(info.apiName == null ? "" : DisguiseBlitzKitSelectorOverride.normalizeKitKey(info.apiName)).append('|').append(info.level).append('|').append(info.prestige).append('|').append(info.prestiged);
        } else {
            key.append("|locked");
        }
        if (original != null) {
            key.append('|').append(DisguiseBlitzKitSelectorOverride.normalizeKitKey(DisguiseBlitzKitSelectorOverride.cleanText(original.getDisplayName()))).append('|').append(original.getMetadata());
        }
        return key.toString();
    }

    private static String getSelectorDisplayName(String kitName) {
        if (kitName == null || kitName.trim().isEmpty()) {
            return "";
        }
        String override = DISPLAY_NAME_OVERRIDES.get(DisguiseBlitzKitSelectorOverride.normalizeKitKey(kitName));
        return override == null || override.trim().isEmpty() ? kitName.trim() : override;
    }

    private static Map<String, String> createDisplayNameOverrides() {
        HashMap<String, String> overrides = new HashMap<String, String>();
        overrides.put("horsetamer", "Horsetamer");
        overrides.put("meatmaster", "Meatmaster");
        overrides.put("reddragon", "RedDragon");
        overrides.put("donkeytamer", "Donkeytamer");
        overrides.put("slimeyslime", "SlimeySlime");
        overrides.put("wolftamer", "Wolftamer");
        overrides.put("creepertamer", "Creepertamer");
        return overrides;
    }

    static {
        SOLO_CHEST_KIT_ORDER = Collections.unmodifiableList(Arrays.asList("Horsetamer", "Ranger", "Archer", "Astronaut", "Troll", "Meatmaster", "Reaper", "Shark", "RedDragon", "Toxicologist", "Donkeytamer", "Rogue", "Warlock", "SlimeySlime", "Jockey", "Golem", "Viking", "Speleologist", "Shadow Knight", "Baker", "Knight", "Pigman", "Guardian", "Phoenix", "Paladin", "Necromancer", "Scout", "Hunter", "Warrior", "Hype Train", "Fisherman", "Milkman", "Florist", "Diver", "Arachnologist", "Blaze", "Wolftamer", "Tim", "Snowman", "Rambo", "Farmer", "Armorer", "Creepertamer"));
        TEAMS_CHEST_KIT_ORDER = Collections.unmodifiableList(Arrays.asList("Horsetamer", "Ranger", "Archer", "Astronaut", "Troll", "Meatmaster", "Reaper", "Shark", "RedDragon", "Toxicologist", "Donkeytamer", "Rogue", "Warlock", "SlimeySlime", "Jockey", "Golem", "Viking", "Speleologist", "Shadow Knight", "Baker", "Knight", "Pigman", "Guardian", "Phoenix", "Paladin", "Necromancer", "Scout", "Hunter", "Warrior", "Hype Train", "Fisherman", "Milkman", "Florist", "Diver", "Arachnologist", "Blaze", "Wolftamer", "Tim", "Snowman", "Farmer", "Armorer", "Creepertamer"));
        DISPLAY_NAME_OVERRIDES = DisguiseBlitzKitSelectorOverride.createDisplayNameOverrides();
        ArrayList<String> kits = new ArrayList<String>(KitLevelsManager.getAvailableKits());
        kits.removeIf(name -> name == null || "Random".equalsIgnoreCase((String)name));
        kits.sort(Comparator.comparingInt(String::length).reversed());
        KNOWN_KITS = Collections.unmodifiableList(kits);
        activeContainerIdentity = 0;
        activeLowerInventory = null;
        activeChestContext = null;
        activeTargetSignature = "";
        originalStacks = new HashMap<Integer, ItemStack>();
        replacementStacks = new HashMap<Integer, ItemStack>();
        hiddenReplacementSlots = new HashSet<Integer>();
        slotRewriteSpecs = new HashMap<Integer, SlotRewriteSpec>();
        rewrittenStackCache = new HashMap<String, ItemStack>();
        CAPTURED_LEVEL_LORE = DisguiseBlitzKitSelectorOverride.loadCapturedLevelLore();
        activeClientClickDepth = 0;
        pendingReplacementRefresh = false;
        directOverridesActive = false;
    }

    private static final class ChestRewriteContext {
        private final ChestMode mode;
        private final String displayKitName;

        private ChestRewriteContext(ChestMode mode, String displayKitName) {
            this.mode = mode;
            this.displayKitName = displayKitName;
        }
    }

    private static enum ChestMode {
        SELECTOR,
        BASIC_KITS,
        ADVANCED_KITS,
        ULTIMATE_KITS,
        KIT_UPGRADES;

    }

    private static final class SlotRewriteSpec {
        private final SlotKind kind;
        private final String kitName;
        private final int level;

        private SlotRewriteSpec(SlotKind kind, String kitName, int level) {
            this.kind = kind;
            this.kitName = kitName == null ? "" : kitName;
            this.level = level;
        }
    }

    private static enum SlotKind {
        SELECTOR_KIT,
        CATEGORY_KIT,
        UPGRADE_LEVEL,
        UPGRADE_UNLOCK,
        UPGRADE_PRESTIGE;

    }

    private static final class CapturedLevelLore {
        private final List<String> headerLines;
        private final List<String> itemLines;

        private CapturedLevelLore(List<String> headerLines, List<String> itemLines) {
            this.headerLines = headerLines == null ? Collections.emptyList() : headerLines;
            this.itemLines = itemLines == null ? Collections.emptyList() : itemLines;
        }
    }

    private static final class ImageParts {
        private final int itemId;
        private final int meta;

        private ImageParts(int itemId, int meta) {
            this.itemId = itemId;
            this.meta = meta;
        }
    }

    private static final class ItemLineGroup {
        private final List<String> lines;
        private final int category;
        private final int order;

        private ItemLineGroup(List<String> lines, int category, int order) {
            this.lines = lines == null ? Collections.emptyList() : new ArrayList<String>(lines);
            this.category = category;
            this.order = order;
        }

        private int getCategory() {
            return this.category;
        }

        private int getOrder() {
            return this.order;
        }
    }

    private static final class PotionLoreEntry {
        private final String name;
        private final String duration;

        private PotionLoreEntry(String name, String duration) {
            this.name = name == null ? "" : name;
            this.duration = duration;
        }
    }
}
