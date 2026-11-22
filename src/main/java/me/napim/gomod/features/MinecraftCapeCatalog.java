package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mojang-hosted official cape textures used by the disguise and standalone cape pickers.
 */
public final class MinecraftCapeCatalog {
    private static final String TEXTURE_BASE_URL = "https://textures.minecraft.net/texture/";
    private static final Pattern TEXTURE_HASH_PATTERN = Pattern.compile("(?:^|/)([a-fA-F0-9]{64})(?:[/?#]|$)");
    private static final Map<String, String> LABEL_BY_TEXTURE_HASH = new HashMap<String, String>();
    private static final ConcurrentHashMap<String, String> TEXTURE_HASH_BY_IMAGE_URL = new ConcurrentHashMap<String, String>();
    private static final ConcurrentHashMap<String, BufferedImage> OFFICIAL_NORMALIZED_CACHE = new ConcurrentHashMap<String, BufferedImage>();

    public static final class ResolvedCapeMeta {
        public final String textureHash;
        public final String label;

        public ResolvedCapeMeta(String textureHash, String label) {
            this.textureHash = textureHash;
            this.label = label;
        }
    }

    public static final Entry[] OFFICIAL_MOJANG_CAPES = {
            new Entry("15th Anniversary", "cd9d82ab17fd92022dbd4a86cde4c382a7540e117fae7b9a2853658505a80625"),
            new Entry("Aurora Cape", "7c418dfbd37412a55e9f1425c9807a591ad10cf71b1edb576a587be9114c277f"),
            new Entry("Birthday Cape", "2056f2eebd759cce93460907186ef44e9192954ae12b227d817eb4b55627a7fc"),
            new Entry("Builder Cape", "2c579968c64c1719740fd8c2a451461879b238002574fce48f7d1a7c36a1c7d4"),
            new Entry("Cherry Blossom", "afd553b39358a24edfe3b8a9a939fa5fa4faa4d9a9c3d6af8eafb377fa05c2bb"),
            new Entry("Cobalt Cape", "ca35c56efe71ed290385f4ab5346a1826b546a54d519e6a3ff01efa01acce81"),
            new Entry("Common Cape", "5ec930cdd2629c8771655c60eebeb867b4b6559b0e6d3bc71c40c96347fa03f0"),
            new Entry("Copper Cape", "5e6f3193e74cd16cdd6637d9bae5484e3a37ff2a14c2d157c659a07810b1bdca"),
            new Entry("Crafter Cape", "479eacefa3cdd7aca94207f36c0dd449653ddf259daf40544a5866baf05eee22"),
            new Entry("Document Cape", "23917a9b79ac95602bba0027c2d3b4a67f22ca537e8c984400c48f8056c904eb"),
            new Entry("Follower's Cape", "569b7f2a1d00d26f30efe3f9ab9ac817b1e6d35f4f3cfb0324ef2d328223d350"),
            new Entry("Founder's Cape", "99aba02ef05ec6aa4d42db8ee43796d6cd50e4b2954ab29f0caeb85f96bf52a1"),
            new Entry("Hero Cape", "bb384a1079b9a6f2811520c7991b6e8150d02e5a96457e44509e30822f72f38e"),
            new Entry("Home Cape", "1de21419009db483900da6298a1e6cbf9f1bc1523a0dcdc16263fab150693edd"),
            new Entry("JulianClark Cape", "23ec737f18bfe4b547c95935fc297dd767bb84ee55bfd855144d279ac9bfd9fe"),
            new Entry("MCC 15th Year", "56c35628fe1c4d59dd52561a3d03bfa4e1a76d397c8b9c476c2f77cb6aebb1df"),
            new Entry("MINECON 2011", "953cac8b779fe41383e675ee2b86071a71658f2180f56fbce8aa315ea70e2ed6"),
            new Entry("MINECON 2012", "a2e8d97ec79100e90a75d369d1b3ba81273c4f82bc1b737e934eed4a854be1b6"),
            new Entry("MINECON 2013", "153b1a0dfcbae953cdeb6f2c2bf6bf79943239b1372780da44bcbb29273131da"),
            new Entry("MINECON 2015", "b0cc08840700447322d953a02b965f1d65a13a603bf64b17c803c21446fe1635"),
            new Entry("MINECON 2016", "e7dfea16dc83c97df01a12fabbd1216359c0cd0ea42f9999b6e97c584963e980"),
            new Entry("MapMaker Cape", "17912790ff164b93196f08ba71d0e62129304776d0f347334f8a6eae509f8a56"),
            new Entry("Menace Cape", "dbc21e222528e30dc88445314f7be6ff12d3aeebc3c192054fba7e3b3f8c77b1"),
            new Entry("Migrator Cape", "2340c0e03dd24a11b15a8b33c2a7e9e32abb2051b2481d0ba7defd635ca7a933"),
            new Entry("Millionth Sale Cape", "70efffaf86fe5bc089608d3cb297d3e276b9eb7a8f9f2fe6659c23a2d8b18edf"),
            new Entry("Minecraft Experience", "7658c5025c77cfac7574aab3af94a46a8886e3b7722a895255fbf22ab8652434"),
            new Entry("Moderator Cape", "ae677f7d98ac70a533713518416df4452fe5700365c09cf45d0d156ea9396551"),
            new Entry("Mojang Cape", "9e507afc56359978a3eb3e32367042b853cddd0995d17d0da995662913fb00f7"),
            new Entry("Mojang Cape (Classic)", "8f120319222a9f4a104e2f5cb97b2cda93199a2ee9e1585cb8d09d6f687cb761"),
            new Entry("Mojang Office", "5c29410057e32abec02d870ecb52ec25fb45ea81e785a7854ae8429d7236ca26"),
            new Entry("Mojang Studios Cape", "5786fe99be377dfb6858859f926c4dbc995751e91cee373468c5fbf4865e7151"),
            new Entry("Moonlight Trail", "fe8a02dfe9e390e44ff33d69feef9d3943f76d3901015bbd50f0b67722d288bd"),
            new Entry("MrMessiah Cape", "2e002d5e1758e79ba51d08d92a0f3a95119f2f435ae7704916507b6c565a7da8"),
            new Entry("One Vanilla Cape", "f9a76537647989f9a0b6d001e320dac591c359e9e61a31f4ce11c88f207f0ad4"),
            new Entry("Oxeye Cape", "7706b5f5fc90329691e59277dcc66ba20572219fa8e5da472afd5235fad12cc8"),
            new Entry("Pan Cape", "28de4a81688ad18b49e735a273e086c18f1e3966956123ccb574034c06f5d336"),
            new Entry("Prismarine Cape", "d8f8d13a1adf9636a16c31d47f3ecc9bb8d8533108aa5ad2a01b13b1a0c55eac"),
            new Entry("Purple Heart", "cb40a92e32b57fd732a00fc325e7afb00a7ca74936ad50d8e860152e482cfbde"),
            new Entry("Scrolls Champion Cape", "3efadf6510961830f9fcc077f19b4daf286d502b5f5aafbd807c7bbffcaca245"),
            new Entry("Translator (Chinese)", "2262fb1d24912209490586ecae98aca8500df3eff91f2a07da37ee524e7e3cb6"),
            new Entry("Translator (Japanese)", "ca29f5dd9e94fb1748203b92e36b66fda80750c87ebc18d6eafdb0e28cc1d05f"),
            new Entry("Translator Cape", "1bf91499701404e21bd46b0191d63239a4ef76ebde88d27e4d430ac211df681e"),
            new Entry("Turtle Cape", "5048ea61566353397247d2b7d946034de926b997d5e66c86483dfb1e031aee95"),
            new Entry("Twisted Cape", "24aafc451aa2cc34ddc7265211678585c0ef4da4d32edb75ecec1bd8b5408381"),
            new Entry("Valentine Cape", "6a7cf0eb5cfe7e7c508b364e32916dfd28d164e7bf6d92c6ea7811b82451e760"),
            new Entry("Yearn Cape", "308b32a9e303155a0b4262f9e5483ad4a22e3412e84fe8385a0bdd73dc41fa89"),
            new Entry("Zombie Horse", "a3f6e4f14801f3ea55e3d95b9b4ef3b5e8802d947f669de93d6ec4b9354a436b"),
    };

    static {
        for (Entry entry : OFFICIAL_MOJANG_CAPES) {
            if (entry != null && entry.textureId != null) {
                LABEL_BY_TEXTURE_HASH.put(entry.textureId.toLowerCase(Locale.ROOT), entry.label);
            }
        }
    }

    private static final Map<String, String> TEXTURE_HASH_BY_CATALOG_LABEL = new HashMap<String, String>();

    static {
        for (Entry entry : OFFICIAL_MOJANG_CAPES) {
            if (entry != null && entry.textureId != null && entry.label != null) {
                TEXTURE_HASH_BY_CATALOG_LABEL.put(entry.label.toLowerCase(Locale.ROOT), entry.textureId.toLowerCase(Locale.ROOT));
            }
        }
    }

    private MinecraftCapeCatalog() {
    }

    public static String textureHashFromUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty()) {
            return null;
        }
        Matcher matcher = TEXTURE_HASH_PATTERN.matcher(imageUrl);
        if (matcher.find()) {
            return matcher.group(1).toLowerCase(Locale.ROOT);
        }
        return null;
    }

    public static String textureHashFromResourceLocation(ResourceLocation location) {
        if (location == null) {
            return null;
        }
        return textureHashFromUrl(location.getResourcePath());
    }

    public static String labelForImageUrl(String imageUrl) {
        String hash = textureHashFromUrl(imageUrl);
        if (hash == null) {
            return null;
        }
        return LABEL_BY_TEXTURE_HASH.get(hash);
    }

    public static String labelForResourceLocation(ResourceLocation location) {
        return labelForImageUrl(location == null ? null : location.getResourcePath());
    }

    public static final String CATALOG_OPTION_KEY_PREFIX = "gomod:catalog:";

    public static String catalogOptionKey(String textureId) {
        if (textureId == null || textureId.isEmpty()) {
            return null;
        }
        return CATALOG_OPTION_KEY_PREFIX + textureId.toLowerCase(Locale.ROOT);
    }

    public static String textureHashFromCatalogOptionKey(String optionKey) {
        if (optionKey == null || !optionKey.startsWith(CATALOG_OPTION_KEY_PREFIX)) {
            return null;
        }
        return optionKey.substring(CATALOG_OPTION_KEY_PREFIX.length());
    }

    public static ResourceLocation textureLocationForCatalogHash(String textureHash) {
        if (textureHash == null || textureHash.isEmpty()) {
            return null;
        }
        for (Entry entry : OFFICIAL_MOJANG_CAPES) {
            if (entry != null && textureHash.equalsIgnoreCase(entry.textureId)) {
                return CapeTextureLoader.loadExternalCapeTexture(entry.textureUrl());
            }
        }
        return CapeTextureLoader.loadExternalCapeTexture(TEXTURE_BASE_URL + textureHash);
    }

    public static String labelForImageUrlOrDefault(String imageUrl, String fallback) {
        String label = labelForImageUrl(imageUrl);
        return label != null ? label : fallback;
    }

    public static String labelForTextureHash(String textureHash) {
        if (textureHash == null || textureHash.isEmpty()) {
            return null;
        }
        return LABEL_BY_TEXTURE_HASH.get(textureHash.toLowerCase(Locale.ROOT));
    }

    /** Cached SHA-256 from a prior {@link #resolveFromImageUrl(String)} download on a background thread. */
    public static String lookupCachedTextureHashForImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty()) {
            return null;
        }
        return TEXTURE_HASH_BY_IMAGE_URL.get(imageUrl);
    }

    /** SHA-256 of cape PNG bytes — Mojang's texture id for official capes. */
    public static String textureHashFromImageBytes(byte[] pngBytes) {
        if (pngBytes == null || pngBytes.length == 0) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(pngBytes);
            StringBuilder sb = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                sb.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Resolves Mojang texture hash + catalog label. URLs from capes.dev do not embed the hash;
     * we download the PNG once and hash it (cached per URL).
     */
    public static ResolvedCapeMeta resolveFromImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty()) {
            return new ResolvedCapeMeta(null, null);
        }
        String hash = textureHashFromUrl(imageUrl);
        if (hash == null) {
            hash = TEXTURE_HASH_BY_IMAGE_URL.get(imageUrl);
        }
        if (hash == null) {
            if (!shouldFetchCapeBytesOnCurrentThread()) {
                return new ResolvedCapeMeta(null, null);
            }
            try {
                byte[] bytes = HypixelApiHelper.fetchCapeImageBytes(imageUrl);
                hash = textureHashFromImageBytes(bytes);
                if (hash != null) {
                    TEXTURE_HASH_BY_IMAGE_URL.putIfAbsent(imageUrl, hash);
                }
            } catch (Throwable ignored) {
            }
        }
        String label = hash == null ? null : labelForTextureHash(hash);
        return new ResolvedCapeMeta(hash, label);
    }

    /**
     * Full metadata resolution for account-cape catalog building. Downloads the PNG when needed and
     * falls back to visual catalog matching when the raw byte hash is not in our Mojang catalog
     * (common for capes.dev CDN URLs). Must not be called from the Minecraft client thread.
     */
    public static ResolvedCapeMeta resolveCapeMetaFromUrlBlocking(String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty() || isNonMojangExternalCapeUrl(imageUrl)) {
            return new ResolvedCapeMeta(null, null);
        }
        if (isMinecraftClientThread()) {
            ResolvedCapeMeta cached = resolveFromImageUrl(imageUrl);
            if (cached.textureHash != null && cached.label != null && !cached.label.isEmpty()) {
                return cached;
            }
            String hash = cached.textureHash;
            if (hash == null || hash.isEmpty()) {
                hash = lookupCachedTextureHashForImageUrl(imageUrl);
            }
            if (hash != null && !hash.isEmpty()) {
                String label = labelForTextureHash(hash);
                if (label != null && !label.isEmpty()) {
                    return new ResolvedCapeMeta(hash, label);
                }
            }
            return cached;
        }

        String hash = textureHashFromUrl(imageUrl);
        if (hash == null || hash.isEmpty()) {
            hash = TEXTURE_HASH_BY_IMAGE_URL.get(imageUrl);
        }
        String label = hash == null ? null : labelForTextureHash(hash);
        if (hash != null && label != null && !label.isEmpty()) {
            return new ResolvedCapeMeta(hash, label);
        }

        try {
            byte[] bytes = HypixelApiHelper.fetchCapeImageBytes(imageUrl);
            if (bytes == null || bytes.length == 0) {
                return new ResolvedCapeMeta(hash, label);
            }
            if (hash == null || hash.isEmpty()) {
                hash = textureHashFromImageBytes(bytes);
            }
            if (hash != null && !hash.isEmpty()) {
                label = labelForTextureHash(hash);
            }
            if (label == null || label.isEmpty()) {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
                if (img != null) {
                    String visualHash = matchCatalogHashByImage(img);
                    if (visualHash != null && !visualHash.isEmpty()) {
                        hash = visualHash;
                        label = labelForTextureHash(visualHash);
                    }
                }
            }
            if (hash != null && !hash.isEmpty()) {
                cacheImageUrlHash(imageUrl, hash);
            }
            return new ResolvedCapeMeta(hash, label);
        } catch (Throwable ignored) {
            if (hash != null && (label == null || label.isEmpty())) {
                label = labelForTextureHash(hash);
            }
            return new ResolvedCapeMeta(hash, label);
        }
    }

    /** Tries several cape CDN URLs (still frame first) until a catalog match is found. */
    public static ResolvedCapeMeta resolveFromImageUrls(String... imageUrls) {
        ResolvedCapeMeta best = new ResolvedCapeMeta(null, null);
        if (imageUrls == null) {
            return best;
        }
        for (String imageUrl : imageUrls) {
            if (imageUrl == null || imageUrl.isEmpty()) {
                continue;
            }
            ResolvedCapeMeta meta = resolveFromImageUrl(imageUrl);
            if (meta.textureHash != null && meta.label != null && !meta.label.isEmpty()) {
                return meta;
            }
            if (meta.textureHash != null && best.textureHash == null) {
                best = meta;
            }
        }
        if (best.textureHash != null && best.label == null) {
            return new ResolvedCapeMeta(best.textureHash, labelForTextureHash(best.textureHash));
        }
        return best;
    }

    /**
     * Resolves hash + label using the image URL and, when available, pixels from the loaded cape texture.
     * Must be called on the Minecraft client thread when {@code location} is provided.
     */
    public static ResolvedCapeMeta resolveForLoadedCape(String imageUrl, ResourceLocation location) {
        if (isNonMojangExternalCapeUrl(imageUrl)) {
            return new ResolvedCapeMeta(null, null);
        }
        ResolvedCapeMeta meta = resolveFromImageUrl(imageUrl);
        if (meta.textureHash != null && meta.label != null && !meta.label.isEmpty()) {
            return meta;
        }

        if (isMinecraftClientThread()) {
            if (meta.textureHash != null) {
                String label = meta.label != null ? meta.label : labelForTextureHash(meta.textureHash);
                return new ResolvedCapeMeta(meta.textureHash, label);
            }
            if (location != null) {
                BufferedImage capeImg = PlayerCustomCapes.loadImageFromCapeTexture(location);
                if (capeImg != null) {
                    String hash = textureHashFromNormalizedImage(capeImg);
                    if (hash != null) {
                        String label = labelForTextureHash(hash);
                        if (label != null) {
                            cacheImageUrlHash(imageUrl, hash);
                            return new ResolvedCapeMeta(hash, label);
                        }
                    }
                }
            }
            return meta;
        }

        BufferedImage capeImg = location == null ? null : waitForCapeTextureImage(location, 4000L);
        if (capeImg != null) {
            String hash = matchCatalogHashByImage(capeImg);
            if (hash != null) {
                cacheImageUrlHash(imageUrl, hash);
                return new ResolvedCapeMeta(hash, labelForTextureHash(hash));
            }
            hash = textureHashFromNormalizedImage(capeImg);
            if (hash != null) {
                String label = labelForTextureHash(hash);
                if (label != null) {
                    cacheImageUrlHash(imageUrl, hash);
                    return new ResolvedCapeMeta(hash, label);
                }
            }
        }

        if (meta.textureHash != null) {
            String label = meta.label != null ? meta.label : labelForTextureHash(meta.textureHash);
            return new ResolvedCapeMeta(meta.textureHash, label);
        }
        return meta;
    }

    public static boolean isGenericMojangHistoryLabel(String label) {
        if (label == null || label.isEmpty()) {
            return true;
        }
        String trimmed = label.trim();
        if ("Mojang".equalsIgnoreCase(trimmed)
                || "Mojang Cape".equalsIgnoreCase(trimmed)
                || "Mojang Current".equalsIgnoreCase(trimmed)) {
            return true;
        }
        return trimmed.matches("Mojang \\d+");
    }

    /** Session-equipped cape from minecraft.net profile — not renamed to catalog label. */
    public static final String MINECRAFT_NET_EQUIPPED_LABEL = "Minecraft.net equipped";

    /** OptiFine / MinecraftCapes history rows keep their numbered labels instead of Mojang catalog names. */
    public static boolean isPreservedExternalHistoryLabel(String label) {
        if (label == null || label.isEmpty()) {
            return false;
        }
        String trimmed = label.trim();
        if (MINECRAFT_NET_EQUIPPED_LABEL.equalsIgnoreCase(trimmed)
                || "OptiFine Cape".equalsIgnoreCase(trimmed)
                || "OptiFine Current".equalsIgnoreCase(trimmed)
                || "MinecraftCapes Cape".equalsIgnoreCase(trimmed)
                || "MinecraftCapes Current".equalsIgnoreCase(trimmed)) {
            return true;
        }
        return trimmed.matches("OptiFine \\d+")
                || trimmed.matches("MinecraftCapes \\d+");
    }

    /** OptiFine / MinecraftCapes account history — not Mojang catalog capes (dedupe by API id, not PNG hash). */
    public static boolean isThirdPartyAccountCapeSourceType(String sourceType) {
        if (sourceType == null || sourceType.isEmpty()) {
            return false;
        }
        switch (sourceType.toLowerCase(Locale.ROOT)) {
            case "optifine":
            case "minecraftcapes":
                return true;
            default:
                return false;
        }
    }

    public static boolean isNonMojangExternalCapeUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty()) {
            return false;
        }
        String lower = imageUrl.toLowerCase(Locale.ROOT);
        // capes.dev hosts Mojang + OptiFine history; source type from the API decides handling — not the URL.
        return lower.contains("optifine.net")
                || lower.contains("optifine.co/")
                || lower.contains("minecraftcapes.net")
                || lower.contains("minecraftcapes.co");
    }

    /** Mojang catalog hashes can be parsed from these URLs; OptiFine/MinecraftCapes URLs cannot. */
    public static boolean isMojangStyleCapeImageUrl(String imageUrl) {
        return imageUrl != null && !imageUrl.isEmpty() && !isNonMojangExternalCapeUrl(imageUrl);
    }

    public static String textureHashForCatalogLabel(String label) {
        if (label == null || label.isEmpty()) {
            return null;
        }
        return TEXTURE_HASH_BY_CATALOG_LABEL.get(label.trim().toLowerCase(Locale.ROOT));
    }

    private static void cacheImageUrlHash(String imageUrl, String hash) {
        if (imageUrl != null && !imageUrl.isEmpty() && hash != null) {
            TEXTURE_HASH_BY_IMAGE_URL.putIfAbsent(imageUrl, hash);
        }
    }

    private static boolean shouldFetchCapeBytesOnCurrentThread() {
        return !isMinecraftClientThread();
    }

    private static boolean isMinecraftClientThread() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            return mc != null && mc.isCallingFromMinecraftThread();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Preloads official cape PNGs on a background thread so GUI open never blocks on HTTP. */
    public static void prewarmOfficialNormalizedCache() {
        for (Entry entry : OFFICIAL_MOJANG_CAPES) {
            if (entry != null && entry.textureId != null && !entry.textureId.isEmpty()) {
                getOfficialNormalizedImage(entry.textureId);
            }
        }
    }

    private static BufferedImage waitForCapeTextureImage(ResourceLocation location, long timeoutMs) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.isCallingFromMinecraftThread()) {
                return PlayerCustomCapes.loadImageFromCapeTexture(location);
            }
        } catch (Throwable ignored) {
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        BufferedImage last = null;
        while (System.currentTimeMillis() < deadline) {
            BufferedImage img = PlayerCustomCapes.loadImageFromCapeTexture(location);
            if (img != null) {
                last = img;
                if (hasVisibleCapePixels(img)) {
                    return img;
                }
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException ignored) {
                break;
            }
        }
        return last;
    }

    private static boolean hasVisibleCapePixels(BufferedImage img) {
        int w = Math.min(PlayerCustomCapes.capeWidth(), img.getWidth());
        int h = Math.min(PlayerCustomCapes.capeHeight(), img.getHeight());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (((img.getRGB(x, y) >>> 24) & 0xFF) > 8) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String matchCatalogHashByImage(BufferedImage capeImg) {
        BufferedImage norm = PlayerCustomCapes.normalizeCapeImage(capeImg);
        for (Entry entry : OFFICIAL_MOJANG_CAPES) {
            if (entry == null || entry.textureId == null) {
                continue;
            }
            BufferedImage official = getOfficialNormalizedImage(entry.textureId);
            if (official != null && capesVisuallyEqual(norm, official)) {
                return entry.textureId;
            }
        }
        return null;
    }

    private static String textureHashFromNormalizedImage(BufferedImage capeImg) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            ImageIO.write(PlayerCustomCapes.normalizeCapeImage(capeImg), "png", baos);
            return textureHashFromImageBytes(baos.toByteArray());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static BufferedImage getOfficialNormalizedImage(String textureId) {
        if (textureId == null || textureId.isEmpty()) {
            return null;
        }
        BufferedImage cached = OFFICIAL_NORMALIZED_CACHE.get(textureId);
        if (cached != null) {
            return cached;
        }
        if (isMinecraftClientThread()) {
            return null;
        }
        try {
            byte[] bytes = HypixelApiHelper.fetchCapeImageBytes(TEXTURE_BASE_URL + textureId);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) {
                return null;
            }
            BufferedImage norm = PlayerCustomCapes.normalizeCapeImage(img);
            OFFICIAL_NORMALIZED_CACHE.putIfAbsent(textureId, norm);
            return norm;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean capesVisuallyEqual(BufferedImage a, BufferedImage b) {
        if (a == null || b == null) {
            return false;
        }
        int w = PlayerCustomCapes.capeWidth();
        int h = PlayerCustomCapes.capeHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (normalizeComparePixel(pixelAt(a, x, y)) != normalizeComparePixel(pixelAt(b, x, y))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int pixelAt(BufferedImage img, int x, int y) {
        if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) {
            return 0;
        }
        return img.getRGB(x, y);
    }

    private static int normalizeComparePixel(int argb) {
        int a = (argb >>> 24) & 0xFF;
        if (a < 8) {
            return 0;
        }
        return (argb | 0xFF000000) & 0xFFFFFFFF;
    }

    public static String resolveDisplayLabel(String imageUrl, String fallback) {
        ResolvedCapeMeta meta = resolveFromImageUrl(imageUrl);
        if (meta.label != null && !meta.label.isEmpty()) {
            return meta.label;
        }
        if (fallback != null && !fallback.isEmpty() && !isGenericMojangHistoryLabel(fallback)) {
            return fallback;
        }
        return fallback == null || fallback.isEmpty() ? "Cape" : fallback;
    }

    public static final class Entry {
        public final String label;
        public final String textureId;

        private Entry(String label, String textureId) {
            this.label = label;
            this.textureId = textureId;
        }

        public String textureUrl() {
            return TEXTURE_BASE_URL + textureId;
        }
    }
}
