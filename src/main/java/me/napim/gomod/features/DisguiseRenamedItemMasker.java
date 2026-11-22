package me.napim.gomod.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.EnumChatFormatting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites the local player's Blitz kit item title while disguise is active.
 * Modes are handled by {@link LocalDisguise}: reset to default, mirror the disguise target's API rename, or unchanged.
 */
public final class DisguiseRenamedItemMasker {
    private static final Pattern OWNER_LINE_PATTERN = Pattern.compile("^owner:\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRACKED_KIT_PATTERN = Pattern.compile("^(.+?)\\s+([IVX]+|[\\u2B50\\u2605\\u272B\\u2726\\u2606\\u2B51\\?]+)$");
    private static final Pattern KIT_DISPLAY_NAME_PATTERN = Pattern.compile("^(.+?)'s\\s+(.+?)\\s+\\(([^)]+)\\)$");
    private static final int KIT_IRON_SWORD_IMAGE_ID = 251;
    private static final int[] LOCAL_KIT_CAPTURE_DELAYS_MS = new int[] { 0, 150, 350, 700, 1200, 1800 };
    private static final Object LOCAL_CAPTURE_LOCK = new Object();
    private static volatile String localCapturedKitKey = "";
    private static volatile Set<String> localCapturedItemSignatures = Collections.emptySet();
    private static volatile int localCaptureGeneration = 0;
    /** Nested {@link FontRenderer} disguise rewrites must be off while measuring/drawing masked kit item text. */
    private static int fontRewriteMaskSuppressionDepth = 0;

    private DisguiseRenamedItemMasker() {
    }

    /**
     * Hypixel occasionally stores lobby or menu copy under {@code kit_item_rename_*} keys.
     * Those strings are not real kit renames and must be ignored so TARGET mode can fall back
     * to the default masked title and bad keys do not collide with real lookups.
     */
    public static boolean isPlausibleDisguiseRenamedItemApiValue(String value) {
        if (value == null) {
            return false;
        }
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(value);
        if (plain == null) {
            plain = value;
        }
        String t = plain.trim();
        if (t.isEmpty() || t.length() > 200) {
            return false;
        }
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.contains("return to lobby")) {
            return false;
        }
        if (lower.contains("play again")) {
            return false;
        }
        if (lower.contains("spectate") && lower.contains("click")) {
            return false;
        }
        if (lower.matches("(?i).*\\(right\\s*click\\).*") || lower.matches("(?i).*\\(left\\s*click\\).*")) {
            return false;
        }
        if (lower.contains("(right click)") || lower.contains("(left click)")) {
            return false;
        }
        if (lower.contains("right click")
                && (lower.contains("lobby") || lower.contains("return") || lower.contains("queue"))) {
            return false;
        }
        return true;
    }

    /**
     * True when this stack is subject to renamed-item masking (any mode). Used to skip
     * {@link LocalDisguise#replaceRenderedText} in {@link net.minecraft.client.gui.FontRenderer} so bold/formatting
     * widths match the drawn string and the hotbar highlight matches the tooltip title.
     */
    public static boolean appliesRenamedItemMask(ItemStack stack) {
        return buildMaskContext(stack) != null;
    }

    /**
     * @return whether suppression was started (caller must pass the same flag to {@link #endRenamedMaskFontSuppression})
     */
    public static boolean beginRenamedMaskFontSuppression(ItemStack stack) {
        if (!appliesRenamedItemMask(stack)) {
            return false;
        }
        if (fontRewriteMaskSuppressionDepth++ == 0) {
            MixinStatus.skipFontRendererDisguiseReplace = true;
        }
        return true;
    }

    public static void endRenamedMaskFontSuppression(boolean started) {
        if (!started) {
            return;
        }
        if (--fontRewriteMaskSuppressionDepth <= 0) {
            fontRewriteMaskSuppressionDepth = 0;
            MixinStatus.skipFontRendererDisguiseReplace = false;
        }
    }

    public static void clearLocalReceivedKitTracking() {
        synchronized (LOCAL_CAPTURE_LOCK) {
            localCapturedKitKey = "";
            localCapturedItemSignatures = Collections.emptySet();
            ++localCaptureGeneration;
        }
    }

    public static void startTrackingLocalReceivedKitItems(String trackedKit) {
        final KitDescriptor kit = parseTrackedKit(trackedKit);
        if (kit == null) {
            clearLocalReceivedKitTracking();
            return;
        }
        final String kitKey = buildKitDescriptorKey(kit);
        final int generation;
        synchronized (LOCAL_CAPTURE_LOCK) {
            localCapturedKitKey = kitKey;
            localCapturedItemSignatures = Collections.emptySet();
            generation = ++localCaptureGeneration;
        }
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        new Thread(() -> {
            for (int i = 0; i < LOCAL_KIT_CAPTURE_DELAYS_MS.length; ++i) {
                if (i > 0) {
                    try {
                        Thread.sleep(LOCAL_KIT_CAPTURE_DELAYS_MS[i]);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                }
                Minecraft client = Minecraft.getMinecraft();
                if (client == null) {
                    return;
                }
                client.addScheduledTask(() -> captureLocalReceivedKitItemsPass(kit, kitKey, generation));
            }
        }, "gomod-local-kit-item-capture").start();
    }

    public static String getMaskedDisplayName(ItemStack stack) {
        MaskContext context = buildMaskContext(stack);
        if (context == null) {
            return null;
        }
        return context.maskedName;
    }

    public static List<String> buildMaskedTooltip(ItemStack stack, List<String> originalLines, boolean advanced) {
        MaskContext context = buildMaskContext(stack);
        if (context == null || originalLines == null || originalLines.isEmpty()) {
            return originalLines;
        }
        ArrayList<String> masked = new ArrayList<String>();
        masked.add(context.maskedName);
        for (int i = 1; i < originalLines.size(); ++i) {
            String line = originalLines.get(i);
            if (!context.keepOwnerLine && isOwnerLine(line)) {
                continue;
            }
            masked.add(line);
        }
        collapseBlankLines(masked);
        return masked;
    }

    public static List<String> buildDebugLines() {
        ArrayList<String> lines = new ArrayList<String>();
        String g = EnumChatFormatting.GRAY.toString();
        String y = EnumChatFormatting.YELLOW.toString();
        String r = EnumChatFormatting.RED.toString();
        String a = EnumChatFormatting.AQUA.toString();

        Minecraft mc = Minecraft.getMinecraft();
        lines.add(a + "Renamed-items debug");
        lines.add(g + "disguise enabled: " + LocalDisguise.isEnabled());
        lines.add(g + "mode: " + LocalDisguise.getRenamedItemsModeValue());
        lines.add(g + "target: " + safeDebug(LocalDisguise.getTargetName()));
        lines.add(g + "target renamed keys: " + LocalDisguise.getTargetRenamedItemsByKey().size());
        lines.add(g + "tracked kit: " + safeDebug(KillCounter.getLocalPlayerTrackedKit()));

        if (mc == null || mc.thePlayer == null) {
            lines.add(r + "minecraft player unavailable");
            return lines;
        }
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null) {
            lines.add(r + "hold the kit item you want to inspect");
            return lines;
        }

        String trackedKit = KillCounter.getLocalPlayerTrackedKit();
        KitDescriptor kit = parseTrackedKit(trackedKit);
        String owner = getOwnerName(held);
        boolean matchesOwner = owner != null && !owner.isEmpty() && matchesLocalIdentity(owner, mc);
        boolean matchesCapture = kit != null && matchesLocalReceivedKitCapture(held, kit);
        boolean matchesTrackedItem = kit != null && matchesTrackedKitItem(held, kit);
        String extractedKitItemName = kit == null ? null : extractKitStyledItemName(held, kit);
        String rawBaseItemName = held.getItem() == null ? null : held.getItem().getItemStackDisplayName(held);
        rawBaseItemName = stripFormatting(rawBaseItemName);
        String displayBaseItemName = normalizeBaseItemName(
                extractedKitItemName != null && !extractedKitItemName.isEmpty() ? extractedKitItemName : rawBaseItemName);

        lines.add(g + "held display: " + safeDebug(stripFormatting(held.getDisplayName())));
        lines.add(g + "held raw base: " + safeDebug(rawBaseItemName));
        lines.add(g + "held display base: " + safeDebug(displayBaseItemName));
        lines.add(g + "stored display: " + safeDebug(getStoredDisplayName(held)));
        lines.add(g + "owner line: " + safeDebug(owner));
        lines.add(g + "owner match: " + matchesOwner);
        lines.add(g + "received-kit capture match: " + matchesCapture);
        lines.add(g + "tracked kit item match: " + matchesTrackedItem);
        lines.add(g + "extracted kit item: " + safeDebug(extractedKitItemName));
        lines.add(g + "server-side nicked: " + isLocalPlayerServerSideNicked(mc));

        if (kit == null) {
            lines.add(r + "tracked kit could not be parsed");
            return lines;
        }

        lines.add(g + "parsed kit: " + kit.displayBaseName + " / " + kit.levelToken);
        String rename = findTargetRenamedItemValue(kit, rawBaseItemName, displayBaseItemName);
        lines.add(g + "target rename result: " + safeDebug(rename));
        String formattedDefault = formatMaskedName(kit.displayBaseName + "'s " + displayBaseItemName, kit.levelToken);
        lines.add(g + "default rename fallback: " + safeDebug(formattedDefault == null ? null : stripFormatting(formattedDefault)));

        Map<String, String> renamedItems = LocalDisguise.getTargetRenamedItemsByKey();
        for (String kitToken : buildKitRenameTokens(kit.displayBaseName)) {
            for (String itemToken : buildItemRenameTokens(rawBaseItemName, displayBaseItemName)) {
                String key = LocalDisguise.buildDisguiseRenamedItemLookupKey(kitToken, itemToken);
                String value = renamedItems.get(key);
                lines.add(g + "lookup " + key + " -> " + safeDebug(value));
            }
        }

        String maskedDisplay = getMaskedDisplayName(held);
        lines.add(y + "final masked display: " + safeDebug(maskedDisplay == null ? null : stripFormatting(maskedDisplay)));
        return lines;
    }

    private static MaskContext buildMaskContext(ItemStack stack) {
        if (stack == null || !LocalDisguise.shouldApplyRenamedItemsMode()) {
            return null;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || !GameContext.isOnHypixel(mc) || !GameContext.isBlitzGameActive(mc)) {
            return null;
        }
        String trackedKit = KillCounter.getLocalPlayerTrackedKit();
        if (trackedKit == null || trackedKit.isEmpty()) {
            return null;
        }
        KitDescriptor kit = parseTrackedKit(trackedKit);
        if (kit == null || "random".equals(kit.normalizedBaseName)) {
            return null;
        }
        String owner = getOwnerName(stack);
        boolean matchesOwner = owner != null && !owner.isEmpty() && matchesLocalIdentity(owner, mc);
        boolean matchesReceivedKitCapture = matchesLocalReceivedKitCapture(stack, kit);
        boolean matchesTrackedItem = matchesTrackedKitItem(stack, kit);
        // Nicked / stripped kit items often have no owner lore and miss timed capture; kit JSON match is enough.
        if (!matchesOwner && !matchesReceivedKitCapture && !matchesTrackedItem) {
            return null;
        }
        String extractedKitItemName = extractKitStyledItemName(stack, kit);
        if (!matchesTrackedItem && (extractedKitItemName == null || extractedKitItemName.isEmpty())) {
            return null;
        }
        String rawBaseItemName = stack.getItem() == null ? null : stack.getItem().getItemStackDisplayName(stack);
        rawBaseItemName = stripFormatting(rawBaseItemName);
        if ((rawBaseItemName == null || rawBaseItemName.isEmpty()) && extractedKitItemName != null && !extractedKitItemName.isEmpty()) {
            rawBaseItemName = extractedKitItemName;
        }
        String displayBaseItemName = normalizeBaseItemName(extractedKitItemName != null && !extractedKitItemName.isEmpty()
                ? extractedKitItemName
                : rawBaseItemName);
        if (displayBaseItemName == null || displayBaseItemName.isEmpty()) {
            return null;
        }
        String defaultMaskedName = formatMaskedName(kit.displayBaseName + "'s " + displayBaseItemName, kit.levelToken);
        String maskedName = resolveMaskedNameForMode(
                LocalDisguise.getRenamedItemsModeValue(),
                kit,
                rawBaseItemName,
                displayBaseItemName,
                defaultMaskedName
        );
        if (maskedName == null || maskedName.isEmpty()) {
            return null;
        }
        boolean prestige = countPrestigeStars(kit.levelToken) > 0;
        boolean keepOwner = prestige || isLocalPlayerServerSideNicked(mc);
        return new MaskContext(maskedName, keepOwner);
    }

    private static String resolveMaskedNameForMode(String mode, KitDescriptor kit, String rawBaseItemName,
                                                   String displayBaseItemName, String defaultMaskedName) {
        if (!"TARGET".equals(mode)) {
            return defaultMaskedName;
        }
        String targetRename = findTargetRenamedItemValue(kit, rawBaseItemName, displayBaseItemName);
        if (targetRename == null || targetRename.trim().isEmpty()
                || isDefaultRename(targetRename, kit, rawBaseItemName, displayBaseItemName)) {
            return defaultMaskedName;
        }
        return targetRename;
    }

    private static boolean matchesLocalIdentity(String owner, Minecraft mc) {
        String normalizedOwner = owner.trim().toLowerCase(Locale.ROOT);
        if (normalizedOwner.isEmpty()) {
            return false;
        }
        String playerName = mc.thePlayer.getName();
        if (playerName != null && normalizedOwner.equals(playerName.trim().toLowerCase(Locale.ROOT))) {
            return true;
        }
        String disguiseName = LocalDisguise.getTargetName();
        return disguiseName != null && normalizedOwner.equals(disguiseName.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean matchesTrackedKitItem(ItemStack stack, KitDescriptor kit) {
        JsonObject kitData = KitEnchantmentsLoader.getKitData(kit.displayBaseName);
        if (kitData == null) {
            return false;
        }
        for (int level : kit.levelCandidates) {
            JsonElement levelElement = kitData.get(String.valueOf(level));
            if (levelElement == null || !levelElement.isJsonObject()) {
                continue;
            }
            JsonObject levelData = levelElement.getAsJsonObject();
            if (matchesEntryList(levelData.getAsJsonArray("attackslot"), stack)) {
                return true;
            }
            if (matchesEntryList(levelData.getAsJsonArray("items"), stack)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesEntryList(JsonArray entries, ItemStack stack) {
        if (entries == null) {
            return false;
        }
        int itemId = Item.getIdFromItem(stack.getItem());
        int metadata = stack.getMetadata();
        boolean ignoreMetadata = stack.getMaxDamage() > 0;
        for (JsonElement element : entries) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            if (!entry.has("image")) {
                continue;
            }
            int[] imageData = parseImageItem(entry.get("image").getAsString());
            if (imageData == null) {
                continue;
            }
            // The kit data uses 251 for its iron sword image. Match the actual Minecraft item.
            int kitItemId = imageData[0] == KIT_IRON_SWORD_IMAGE_ID
                    ? Item.getIdFromItem(Items.iron_sword) : imageData[0];
            if (kitItemId != itemId) {
                continue;
            }
            if (ignoreMetadata || imageData[1] == metadata) {
                return true;
            }
        }
        return false;
    }

    private static int[] parseImageItem(String image) {
        if (image == null || image.isEmpty()) {
            return null;
        }
        String[] parts = image.split("_");
        if (parts.length < 2) {
            return null;
        }
        try {
            return new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) };
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static KitDescriptor parseTrackedKit(String trackedKit) {
        String clean = stripFormatting(trackedKit);
        if (clean == null) {
            return null;
        }
        clean = clean.replace('\u2019', '\'').replace('\u2018', '\'').trim();
        Matcher matcher = TRACKED_KIT_PATTERN.matcher(clean);
        if (!matcher.matches()) {
            return null;
        }
        String baseName = matcher.group(1).trim();
        String levelToken = matcher.group(2).trim();
        int[] candidates = parseLevelCandidates(levelToken);
        if (baseName.isEmpty() || candidates.length == 0) {
            return null;
        }
        return new KitDescriptor(baseName, levelToken, candidates);
    }

    private static int[] parseLevelCandidates(String token) {
        if (token == null || token.isEmpty()) {
            return new int[0];
        }
        int starCount = countPrestigeStars(token);
        if (starCount >= 2) {
            return new int[] { 12 };
        }
        if (starCount == 1 || token.indexOf('?') >= 0) {
            return new int[] { 11, 12 };
        }
        int romanLevel = romanToInt(token);
        if (romanLevel <= 0) {
            return new int[0];
        }
        return new int[] { romanLevel };
    }

    private static int countPrestigeStars(String token) {
        int count = 0;
        for (int i = 0; i < token.length(); ++i) {
            char c = token.charAt(i);
            if (c == '\u2B50' || c == '\u2605' || c == '\u272B' || c == '\u2726' || c == '\u2606' || c == '\u2B51') {
                ++count;
            }
        }
        return count;
    }

    private static int romanToInt(String roman) {
        String normalized = roman == null ? "" : roman.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("^[IVX]+$")) {
            return 0;
        }
        int result = 0;
        int prev = 0;
        for (int i = normalized.length() - 1; i >= 0; --i) {
            int value;
            switch (normalized.charAt(i)) {
                case 'I':
                    value = 1;
                    break;
                case 'V':
                    value = 5;
                    break;
                case 'X':
                    value = 10;
                    break;
                default:
                    return 0;
            }
            if (value < prev) {
                result -= value;
            } else {
                result += value;
                prev = value;
            }
        }
        return result;
    }

    /**
     * Extracts the player name from a Hypixel Blitz kit item's {@code Owner: <name>} tooltip lore line.
     * Non-kit in-game items (chest loot, Vault items, Blitz Star, skulls, menus) do not carry this tag.
     */
    public static String extractItemOwnerFromLore(ItemStack stack) {
        return getOwnerName(stack);
    }

    private static String getOwnerName(ItemStack stack) {
        if (stack == null) {
            return null;
        }
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null || !tag.hasKey("display", 10)) {
            return null;
        }
        NBTTagCompound display = tag.getCompoundTag("display");
        if (!display.hasKey("Lore", 9)) {
            return null;
        }
        NBTTagList lore = display.getTagList("Lore", 8);
        for (int i = 0; i < lore.tagCount(); ++i) {
            String rawLine = lore.getStringTagAt(i);
            String plainLine = stripFormatting(rawLine);
            if (plainLine == null) {
                continue;
            }
            Matcher matcher = OWNER_LINE_PATTERN.matcher(plainLine.trim());
            if (matcher.matches()) {
                String owner = matcher.group(1).trim();
                if (!owner.isEmpty()) {
                    return owner;
                }
            }
        }
        return null;
    }

    private static void collapseBlankLines(List<String> lines) {
        for (int i = lines.size() - 1; i >= 1; --i) {
            String line = lines.get(i);
            String previous = lines.get(i - 1);
            if (isBlank(line) && isBlank(previous)) {
                lines.remove(i);
            }
        }
        while (lines.size() > 1 && isBlank(lines.get(lines.size() - 1))) {
            lines.remove(lines.size() - 1);
        }
    }

    private static boolean isBlank(String line) {
        String plain = stripFormatting(line);
        return plain == null || plain.trim().isEmpty();
    }

    private static String stripFormatting(String text) {
        return text == null ? null : EnumChatFormatting.getTextWithoutFormattingCodes(text);
    }

    private static boolean isOwnerLine(String line) {
        String plain = stripFormatting(line);
        return plain != null && OWNER_LINE_PATTERN.matcher(plain).matches();
    }

    private static boolean isLocalPlayerServerSideNicked(Minecraft mc) {
        if (mc == null || mc.thePlayer == null || !GameContext.isOnHypixel(mc)) {
            return false;
        }
        String accountName = me.napim.gomod.util.ClientPlayerIdentity.displayName(mc);
        if (accountName == null || accountName.trim().isEmpty()) {
            return false;
        }
        String normalizedAccount = accountName.trim().toLowerCase(Locale.ROOT);

        String localProfileName = null;
        try {
            if (mc.thePlayer.getGameProfile() != null) {
                localProfileName = mc.thePlayer.getGameProfile().getName();
            }
        } catch (Throwable ignored) {
        }
        if (localProfileName != null && !localProfileName.trim().isEmpty()
                && !normalizedAccount.equals(localProfileName.trim().toLowerCase(Locale.ROOT))) {
            return true;
        }

        String localEntityName = null;
        try {
            localEntityName = mc.thePlayer.getName();
        } catch (Throwable ignored) {
        }
        if (localEntityName != null && !localEntityName.trim().isEmpty()
                && !normalizedAccount.equals(localEntityName.trim().toLowerCase(Locale.ROOT))) {
            return true;
        }

        try {
            if (mc.getNetHandler() != null && mc.thePlayer.getGameProfile() != null && mc.thePlayer.getGameProfile().getId() != null) {
                NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getGameProfile().getId());
                String visible = TabDuplicateNickResolver.getVisibleTabUsernamePublic(info);
                if (visible != null && !visible.trim().isEmpty()
                        && !normalizedAccount.equals(visible.trim().toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void captureLocalReceivedKitItemsPass(KitDescriptor kit, String expectedKitKey, int generation) {
        synchronized (LOCAL_CAPTURE_LOCK) {
            if (generation != localCaptureGeneration || !expectedKitKey.equals(localCapturedKitKey)) {
                return;
            }
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.thePlayer.inventory == null) {
            return;
        }
        HashSet<String> found = new HashSet<String>();
        ItemStack[] mainInventory = mc.thePlayer.inventory.mainInventory;
        if (mainInventory != null) {
            for (ItemStack stack : mainInventory) {
                collectCapturedLocalKitStack(found, stack, kit);
            }
        }
        ItemStack[] armorInventory = mc.thePlayer.inventory.armorInventory;
        if (armorInventory != null) {
            for (ItemStack stack : armorInventory) {
                collectCapturedLocalKitStack(found, stack, kit);
            }
        }
        if (found.isEmpty()) {
            return;
        }
        synchronized (LOCAL_CAPTURE_LOCK) {
            if (generation != localCaptureGeneration || !expectedKitKey.equals(localCapturedKitKey)) {
                return;
            }
            HashSet<String> merged = new HashSet<String>(localCapturedItemSignatures);
            merged.addAll(found);
            localCapturedItemSignatures = merged;
        }
    }

    private static void collectCapturedLocalKitStack(Set<String> found, ItemStack stack, KitDescriptor kit) {
        if (stack == null || kit == null) {
            return;
        }
        String extractedKitItemName = extractKitStyledItemName(stack, kit);
        if (!matchesTrackedKitItem(stack, kit) && (extractedKitItemName == null || extractedKitItemName.isEmpty())) {
            return;
        }
        String signature = buildStackSignature(stack);
        if (!signature.isEmpty()) {
            found.add(signature);
        }
    }

    private static boolean matchesLocalReceivedKitCapture(ItemStack stack, KitDescriptor kit) {
        if (stack == null || kit == null) {
            return false;
        }
        String expectedKitKey = buildKitDescriptorKey(kit);
        Set<String> signatures = localCapturedItemSignatures;
        return expectedKitKey.equals(localCapturedKitKey)
                && !signatures.isEmpty()
                && signatures.contains(buildStackSignature(stack));
    }

    private static String buildStackSignature(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return "";
        }
        boolean usesDurability = stack.getMaxDamage() > 0;
        try {
            NBTTagCompound tag = new NBTTagCompound();
            stack.writeToNBT(tag);
            // Damage is durability for tools and armor but identifies variants on items such as spawn eggs.
            if (usesDurability) {
                tag.removeTag("Damage");
            }
            return tag.toString();
        } catch (Throwable ignored) {
            int metadata = usesDurability ? 0 : stack.getMetadata();
            return Item.getIdFromItem(stack.getItem()) + ":" + metadata + ":" + stack.stackSize;
        }
    }

    private static String buildKitDescriptorKey(KitDescriptor kit) {
        if (kit == null) {
            return "";
        }
        return kit.normalizedBaseName + "|" + (kit.levelToken == null ? "" : kit.levelToken.toUpperCase(Locale.ROOT));
    }

    private static String findTargetRenamedItemValue(KitDescriptor kit, String rawBaseItemName, String displayBaseItemName) {
        Map<String, String> renamedItems = LocalDisguise.getTargetRenamedItemsByKey();
        if (renamedItems.isEmpty()) {
            return null;
        }
        for (String kitToken : buildKitRenameTokens(kit.displayBaseName)) {
            for (String itemToken : buildItemRenameTokens(rawBaseItemName, displayBaseItemName)) {
                String value = renamedItems.get(LocalDisguise.buildDisguiseRenamedItemLookupKey(kitToken, itemToken));
                if (value != null && !value.trim().isEmpty() && isPlausibleDisguiseRenamedItemApiValue(value)) {
                    return value;
                }
            }
        }
        return null;
    }

    private static String extractKitStyledItemName(ItemStack stack, KitDescriptor kit) {
        if (stack == null || kit == null) {
            return null;
        }
        String storedDisplayName = getStoredDisplayName(stack);
        if (storedDisplayName == null || storedDisplayName.isEmpty()) {
            return null;
        }
        Matcher matcher = KIT_DISPLAY_NAME_PATTERN.matcher(storedDisplayName.replace('\u2019', '\'').replace('\u2018', '\'').trim());
        if (!matcher.matches()) {
            return null;
        }
        String kitName = matcher.group(1).trim();
        String itemName = matcher.group(2).trim();
        String levelToken = matcher.group(3).trim();
        if (!kit.normalizedBaseName.equals(kitName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""))) {
            return null;
        }
        if (!kit.levelToken.equalsIgnoreCase(levelToken)) {
            return null;
        }
        return normalizeBaseItemName(itemName);
    }

    private static String getStoredDisplayName(ItemStack stack) {
        if (stack == null) {
            return null;
        }
        try {
            NBTTagCompound tag = stack.getTagCompound();
            if (tag != null && tag.hasKey("display", 10)) {
                NBTTagCompound display = tag.getCompoundTag("display");
                if (display.hasKey("Name", 8)) {
                    return stripFormatting(display.getString("Name"));
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Set<String> buildKitRenameTokens(String displayKitName) {
        LinkedHashSet<String> tokens = new LinkedHashSet<String>();
        String cleaned = cleanLookupToken(displayKitName);
        if (cleaned.isEmpty()) {
            return tokens;
        }
        tokens.add(formatApiRenameKitToken(cleaned));
        String apiName = KitLevelsManager.getApiKitName(cleaned);
        if (apiName != null && !apiName.trim().isEmpty()) {
            tokens.add(formatApiRenameKitToken(apiName));
        }
        return tokens;
    }

    private static Set<String> buildItemRenameTokens(String rawBaseItemName, String displayBaseItemName) {
        LinkedHashSet<String> tokens = new LinkedHashSet<String>();
        addItemRenameToken(tokens, rawBaseItemName);
        addItemRenameToken(tokens, displayBaseItemName);
        if ("Leather Tunic".equals(rawBaseItemName) || "Leather Chestplate".equals(displayBaseItemName)) {
            addItemRenameToken(tokens, "Leather Tunic");
            addItemRenameToken(tokens, "Leather Chestplate");
        }
        if ("Leather Pants".equals(rawBaseItemName) || "Leather Leggings".equals(displayBaseItemName)) {
            addItemRenameToken(tokens, "Leather Pants");
            addItemRenameToken(tokens, "Leather Leggings");
        }
        if ("Leather Cap".equals(rawBaseItemName) || "Leather Helmet".equals(displayBaseItemName)) {
            addItemRenameToken(tokens, "Leather Cap");
            addItemRenameToken(tokens, "Leather Helmet");
        }
        return tokens;
    }

    private static void addItemRenameToken(Set<String> tokens, String itemName) {
        String cleaned = cleanLookupToken(itemName);
        if (!cleaned.isEmpty()) {
            tokens.add(cleaned);
        }
    }

    private static String formatApiRenameKitToken(String kitName) {
        String normalized = cleanLookupToken(kitName).toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "reddragon":
                return "RedDragon";
            case "slimeyslime":
                return "SlimeySlime";
            case "shadowknight":
                return "Shadow Knight";
            case "hypetrain":
                return "Hype Train";
            default:
                return toTitleCaseWords(kitName);
        }
    }

    private static String toTitleCaseWords(String text) {
        String cleaned = cleanLookupToken(text);
        if (cleaned.isEmpty()) {
            return "";
        }
        String[] words = cleaned.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                sb.append(word.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return sb.toString();
    }

    private static boolean isDefaultRename(String renameValue, KitDescriptor kit, String rawBaseItemName, String displayBaseItemName) {
        String plain = cleanLookupToken(stripFormatting(renameValue));
        if (plain.isEmpty()) {
            return true;
        }
        for (String itemName : buildItemRenameTokens(rawBaseItemName, displayBaseItemName)) {
            String prefix = cleanLookupToken(kit.displayBaseName + "'s " + itemName);
            if (!plain.regionMatches(true, 0, prefix, 0, prefix.length())) {
                continue;
            }
            if (plain.length() <= prefix.length() + 3 || plain.charAt(prefix.length()) != ' '
                    || plain.charAt(prefix.length() + 1) != '(' || plain.charAt(plain.length() - 1) != ')') {
                continue;
            }
            String levelToken = plain.substring(prefix.length() + 2, plain.length() - 1).trim();
            if (isLikelyKitLevelToken(levelToken)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLikelyKitLevelToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        String trimmed = token.trim().toUpperCase(Locale.ROOT);
        if (trimmed.matches("[IVX]+")) {
            return true;
        }
        int starCount = countPrestigeStars(trimmed);
        return starCount > 0 || trimmed.indexOf('?') >= 0;
    }

    private static String cleanLookupToken(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\u2019', '\'').replace('\u2018', '\'').trim();
    }

    private static String formatMaskedName(String itemNamePrefix, String levelToken) {
        if (itemNamePrefix == null || itemNamePrefix.isEmpty() || levelToken == null || levelToken.isEmpty()) {
            return null;
        }
        if (countPrestigeStars(levelToken) > 0) {
            return EnumChatFormatting.GOLD + itemNamePrefix + " (" + levelToken + ")";
        }
        String nameColor = LocalDisguise.getTargetUsernameColorForRendering();
        if (nameColor == null || nameColor.isEmpty()) {
            nameColor = EnumChatFormatting.GRAY.toString();
        }
        return nameColor + itemNamePrefix + EnumChatFormatting.GRAY + " (" + levelToken + ")";
    }

    private static String normalizeBaseItemName(String baseItemName) {
        if (baseItemName == null || baseItemName.isEmpty()) {
            return baseItemName;
        }
        switch (baseItemName) {
            case "Leather Cap":
                return "Leather Helmet";
            case "Leather Tunic":
                return "Leather Chestplate";
            case "Leather Pants":
                return "Leather Leggings";
            default:
                return baseItemName;
        }
    }

    private static String safeDebug(String value) {
        return value == null || value.isEmpty() ? "(none)" : value;
    }

    private static final class MaskContext {
        private final String maskedName;
        private final boolean keepOwnerLine;

        private MaskContext(String maskedName, boolean keepOwnerLine) {
            this.maskedName = maskedName;
            this.keepOwnerLine = keepOwnerLine;
        }
    }

    private static final class KitDescriptor {
        private final String displayBaseName;
        private final String normalizedBaseName;
        private final String levelToken;
        private final int[] levelCandidates;

        private KitDescriptor(String displayBaseName, String levelToken, int[] levelCandidates) {
            this.displayBaseName = displayBaseName;
            // Must match extractKitStyledItemName: compare to NBT kit segment with spaces/punctuation stripped.
            this.normalizedBaseName = displayBaseName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            this.levelToken = levelToken;
            this.levelCandidates = levelCandidates;
        }
    }
}
