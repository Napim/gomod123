package me.napim.gomod.features;

import net.minecraft.util.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Shared ordering, naming, and dedupe for disguise and self cape pickers. */
public final class CapePickerCatalog {

    private CapePickerCatalog() {
    }

    public static boolean isUtilityRow(String optionKey, boolean createEntry) {
        if (createEntry || PlayerCustomCapes.isCreateOptionKey(optionKey)) {
            return true;
        }
        return PlayerStandaloneCape.isNonCapeCatalogKey(optionKey);
    }

    public static boolean shouldHighlightOwnedRow(String optionKey, boolean createEntry) {
        return !isUtilityRow(optionKey, createEntry);
    }

    public static String resolveDisplayLabel(String imageUrl, String fallback) {
        return MinecraftCapeCatalog.resolveDisplayLabel(imageUrl, fallback);
    }

    public static String resolveDisplayLabel(ResourceLocation location, String fallback) {
        if (location != null) {
            String fromLoc = MinecraftCapeCatalog.labelForResourceLocation(location);
            if (fromLoc != null && !fromLoc.isEmpty()) {
                return fromLoc;
            }
        }
        return fallback == null || fallback.isEmpty() ? "Cape" : fallback;
    }

    /**
     * Resolves the display label for an owned/account cape row. Prefers catalog names from a known
     * texture hash so generic Mojang history labels ({@code Mojang 3}, etc.) do not become plain {@code Cape}.
     */
    public static String resolveOwnedCapeDisplayLabel(String storedLabel, String imageUrl,
            ResourceLocation location, String textureHash) {
        if (MinecraftCapeCatalog.isPreservedExternalHistoryLabel(storedLabel)) {
            return storedLabel;
        }
        String hash = textureHash;
        if (hash == null || hash.isEmpty()) {
            hash = MinecraftCapeCatalog.textureHashFromUrl(imageUrl);
        }
        if (hash == null || hash.isEmpty()) {
            hash = MinecraftCapeCatalog.lookupCachedTextureHashForImageUrl(imageUrl);
        }
        if ((hash == null || hash.isEmpty()) && imageUrl != null && !imageUrl.isEmpty()) {
            MinecraftCapeCatalog.ResolvedCapeMeta meta =
                    MinecraftCapeCatalog.resolveForLoadedCape(imageUrl, location);
            if (meta.textureHash != null && !meta.textureHash.isEmpty()) {
                hash = meta.textureHash;
            }
        }
        if ((hash == null || hash.isEmpty()) && location != null) {
            hash = MinecraftCapeCatalog.textureHashFromResourceLocation(location);
        }
        if (hash != null && !hash.isEmpty()) {
            String catalogLabel = MinecraftCapeCatalog.labelForTextureHash(hash);
            if (catalogLabel != null && !catalogLabel.isEmpty()) {
                return catalogLabel;
            }
        }
        if (storedLabel != null && !storedLabel.isEmpty()
                && !MinecraftCapeCatalog.isGenericMojangHistoryLabel(storedLabel)
                && !"Cape".equalsIgnoreCase(storedLabel.trim())) {
            return storedLabel;
        }
        if (imageUrl != null && !imageUrl.isEmpty()) {
            MinecraftCapeCatalog.ResolvedCapeMeta meta =
                    MinecraftCapeCatalog.resolveForLoadedCape(imageUrl, location);
            if (meta.label != null && !meta.label.isEmpty()) {
                return meta.label;
            }
            String fallback = MinecraftCapeCatalog.isGenericMojangHistoryLabel(storedLabel) ? "Cape" : storedLabel;
            return resolveDisplayLabel(imageUrl, fallback);
        }
        return resolveDisplayLabel(location, storedLabel);
    }

    public static List<LocalDisguise.CapeOptionInfo> buildExpandedGuiOptions(List<OwnedRow> ownedRows) {
        return buildExpandedGuiOptions(ownedRows, false);
    }

    public static List<LocalDisguise.CapeOptionInfo> buildExpandedGuiOptions(List<OwnedRow> ownedRows, boolean catalogSelectable) {
        if (ownedRows == null) {
            ownedRows = Collections.emptyList();
        }
        ArrayList<LocalDisguise.CapeOptionInfo> out = new ArrayList<LocalDisguise.CapeOptionInfo>(
                ownedRows.size() + MinecraftCapeCatalog.OFFICIAL_MOJANG_CAPES.length);
        Set<String> ownedHashes = new HashSet<String>();
        Set<String> ownedKeys = new HashSet<String>();
        Set<String> ownedCatalogLabels = new HashSet<String>();

        for (OwnedRow row : ownedRows) {
            if (row == null) {
                continue;
            }
            String label = row.label;
            if (label == null || label.isEmpty()
                    || "Cape".equalsIgnoreCase(label.trim())
                    || MinecraftCapeCatalog.isGenericMojangHistoryLabel(label)) {
                label = CapePickerCatalog.resolveOwnedCapeDisplayLabel(
                        row.label, row.imageUrl, row.location, row.textureHash);
            }
            boolean createEntry = row.createEntry;
            boolean customUserCape = row.customUserCape;
            boolean ownedHighlight = row.ownedHighlight;
            boolean selectable = row.selectable;
            out.add(LocalDisguise.CapeOptionInfo.createEntry(
                    label, row.location, row.optionKey, customUserCape, createEntry, ownedHighlight, selectable,
                    row.sharedFrom, row.imageUrl));

            if (row.optionKey != null) {
                ownedKeys.add(row.optionKey);
            }
            if (row.location != null) {
                ownedKeys.add(row.location.toString());
            }
            String hash = row.textureHash;
            if ((hash == null || hash.isEmpty()) && row.imageUrl != null && !row.imageUrl.isEmpty()) {
                MinecraftCapeCatalog.ResolvedCapeMeta meta = MinecraftCapeCatalog.resolveForLoadedCape(row.imageUrl, row.location);
                hash = meta.textureHash;
            }
            if (hash == null || hash.isEmpty()) {
                hash = row.imageUrl != null
                        ? MinecraftCapeCatalog.textureHashFromUrl(row.imageUrl)
                        : MinecraftCapeCatalog.textureHashFromResourceLocation(row.location);
            }
            if ((hash == null || hash.isEmpty()) && row.imageUrl != null) {
                hash = MinecraftCapeCatalog.lookupCachedTextureHashForImageUrl(row.imageUrl);
            }
            if (hash == null && label != null && !label.isEmpty()) {
                String hashFromLabel = MinecraftCapeCatalog.textureHashForCatalogLabel(label);
                if (hashFromLabel != null) {
                    hash = hashFromLabel;
                }
            }
            if (hash != null) {
                ownedHashes.add(hash.toLowerCase(Locale.ROOT));
            }
            if (label != null && !label.isEmpty()) {
                ownedCatalogLabels.add(label.toLowerCase(Locale.ROOT));
            }
        }

        for (MinecraftCapeCatalog.Entry entry : MinecraftCapeCatalog.OFFICIAL_MOJANG_CAPES) {
            if (entry == null || entry.textureId == null || entry.textureId.isEmpty()) {
                continue;
            }
            String textureId = entry.textureId.toLowerCase(Locale.ROOT);
            if (ownedHashes.contains(textureId)) {
                continue;
            }
            if (entry.label != null && ownedCatalogLabels.contains(entry.label.toLowerCase(Locale.ROOT))) {
                continue;
            }
            ResourceLocation rl = CapeTextureLoader.loadExternalCapeTexture(entry.textureUrl());
            if (rl == null) {
                continue;
            }
            String key = MinecraftCapeCatalog.catalogOptionKey(textureId);
            if (ownedKeys.contains(key) || ownedKeys.contains(rl.toString())) {
                continue;
            }
            out.add(LocalDisguise.CapeOptionInfo.createEntry(
                    entry.label, rl, key, false, false, false, catalogSelectable, "", entry.textureUrl()));
        }
        return out;
    }

    public static final class OwnedRow {
        public final String label;
        public final ResourceLocation location;
        public final String optionKey;
        public final String imageUrl;
        public final String textureHash;
        public final boolean customUserCape;
        public final boolean createEntry;
        public final boolean selectable;
        /** When true the row is drawn with the green owned highlight (account / target capes). */
        public final boolean ownedHighlight;
        /** Sender for gomod-shared custom capes. */
        public final String sharedFrom;

        public OwnedRow(String label, ResourceLocation location, String optionKey, String imageUrl, String textureHash,
                        boolean customUserCape, boolean createEntry, boolean selectable) {
            this(label, location, optionKey, imageUrl, textureHash, customUserCape, createEntry, selectable,
                    shouldHighlightOwnedRow(optionKey, createEntry));
        }

        public OwnedRow(String label, ResourceLocation location, String optionKey, String imageUrl, String textureHash,
                        boolean customUserCape, boolean createEntry, boolean selectable, boolean ownedHighlight) {
            this(label, location, optionKey, imageUrl, textureHash, customUserCape, createEntry, selectable,
                    ownedHighlight, "");
        }

        public OwnedRow(String label, ResourceLocation location, String optionKey, String imageUrl, String textureHash,
                        boolean customUserCape, boolean createEntry, boolean selectable, boolean ownedHighlight,
                        String sharedFrom) {
            this.label = label;
            this.location = location;
            this.optionKey = optionKey;
            this.imageUrl = imageUrl;
            this.textureHash = textureHash;
            this.customUserCape = customUserCape;
            this.createEntry = createEntry;
            this.selectable = selectable;
            this.ownedHighlight = ownedHighlight;
            this.sharedFrom = sharedFrom == null ? "" : sharedFrom;
        }
    }
}
