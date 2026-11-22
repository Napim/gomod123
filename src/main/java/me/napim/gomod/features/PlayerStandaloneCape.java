package me.napim.gomod.features;

import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.gui.MinecraftCapeSelectorGUI;
import me.napim.gomod.Main;
import me.napim.gomod.shareinv.ShareInvManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Wear a cape on the local player without an active disguise.
 * The cape picker reuses the same catalog as a player disguise (your account's capes + catalog preview).
 */
public final class PlayerStandaloneCape {

    private static final String OFF_OPTION_KEY = "gomod:standalone_cape_off";
    private static final String NO_CAPE_OPTION_KEY = "gomod:none";
    private static final String[] BRIDGE_NAME_GETTERS = {"bridge$getName", "getName", "bridge$getUsername", "getUsername"};

    private static ResourceLocation activeCapeLocation;
    private static String activeCapeOptionKey = "";
    private static String lastAutoRelaySignature = "";
    private static long lastAutoRelayRefreshMs;
    private static boolean reportedBridgeSelfSuppression;
    private static boolean reportedBridgeUuidSuppression;
    private static boolean reportedBridgeContextSuppression;
    private static boolean reportedRemoteBridgeSuppression;

    private PlayerStandaloneCape() {
    }

    public static boolean isNonCapeCatalogKey(String optionKey) {
        return OFF_OPTION_KEY.equals(optionKey) || NO_CAPE_OPTION_KEY.equals(optionKey);
    }

    public static synchronized void invalidateCatalog() {
        LocalDisguise.invalidateLocalSelfCapeCatalog();
    }

    public static synchronized void refreshCapeListSortOrder() {
        // Likes are local metadata; do not invalidate/refetch the player's cape catalog just to repaint hearts.
    }

    /** Official Mojang cape textures for {@link OfficialCapePalette} sampling. */
    public static synchronized List<ResourceLocation> getOfficialCapeTextureLocationsForPalette() {
        ArrayList<ResourceLocation> out = new ArrayList<ResourceLocation>();
        for (LocalDisguise.CapeOptionInfo info : LocalDisguise.getLocalSelfCapeOptionsForGui()) {
            if (info == null || info.location == null || !info.selectable) {
                continue;
            }
            if (info.createEntry || PlayerCustomCapes.isCustomOptionKey(info.optionKey)
                    || isNonCapeCatalogKey(info.optionKey)) {
                continue;
            }
            out.add(info.location);
        }
        for (MinecraftCapeCatalog.Entry entry : MinecraftCapeCatalog.OFFICIAL_MOJANG_CAPES) {
            ResourceLocation rl = CapeTextureLoader.loadExternalCapeTexture(entry.textureUrl());
            if (rl != null) {
                out.add(rl);
            }
        }
        return out;
    }

    public static void loadSetting() {
        synchronized (PlayerStandaloneCape.class) {
            applySavedSelectionLocked();
        }
    }

    public static synchronized void ensureCatalogLoaded() {
        LocalDisguise.warmLocalSelfCapeCatalog();
    }

    public static synchronized boolean isActive() {
        return GomodInternalFeatures.isStandaloneCapeEnabled() && ConfigManager.isPlayerStandaloneCapeEnabled();
    }

    /** Whether a local disguise cape override replaces Lunar's local cloak; Forge always uses the Gomod override. */
    public static boolean shouldUseGomodCapeSource() {
        return !GomodFixes.isWeaveRuntime() || ConfigManager.isPlayerStandaloneCapePreferGomodOnWeave();
    }

    /** Whether the standalone cape choice replaces the client's native/default cape. */
    public static boolean shouldUseGomodStandaloneCapeSource() {
        return ConfigManager.isPlayerStandaloneCapePreferGomod();
    }

    public static boolean shouldApplyLocalCapeOverride() {
        return isActive() && shouldUseGomodStandaloneCapeSource();
    }

    /**
     * Lunar source mode lets Lunar render its equipped cloak, while suppressing the separate vanilla
     * Mojang cape layer that would otherwise remain visible over it.
     */
    public static boolean shouldSuppressMinecraftCapeForLocalPlayer() {
        if (!GomodFixes.isWeaveRuntime() || !ConfigManager.isLunarTweaksEnabled()
                || ConfigManager.isPlayerStandaloneCapePreferGomodOnWeave()) {
            return false;
        }
        // An explicit local disguise cape is governed by the disguise's own cape/cosmetic settings.
        return !(GomodInternalFeatures.isEnabled() && LocalDisguise.isEnabled()
                && LocalDisguise.shouldApplyCapeOverride());
    }

    /** Called from the optional Lunar cloak mixin; never affects other players. */
    public static boolean shouldSuppressLunarCloakForCurrentRender() {
        if (!CapeRenderContext.isRenderingLocalPlayer()) {
            return false;
        }
        return shouldSuppressLunarCloakForLocalPlayer();
    }

    /** Source-selection predicate used when Lunar supplies its player bridge directly to the cloak renderer. */
    public static boolean shouldSuppressLunarCloakForLocalPlayer() {
        if (!GomodFixes.isWeaveRuntime()) {
            return false;
        }
        // Disguise cosmetic hiding is a separate Lunar-only setting. Standalone cape source selection remains
        // controlled by playerStandaloneCapePreferGomodOnWeave below.
        if (GomodInternalFeatures.isEnabled() && LocalDisguise.isEnabled()) {
            return ConfigManager.isDisguiseHideLunarCosmeticsOnWeave();
        }
        if (!ConfigManager.isPlayerStandaloneCapePreferGomod()) {
            return false;
        }
        return shouldApplyLocalCapeOverride()
                || (GomodInternalFeatures.isEnabled()
                && LocalDisguise.isEnabled()
                && LocalDisguise.shouldApplyCapeOverride());
    }

    /** Identifies Lunar's player bridge directly because cloak rendering can run outside the vanilla layer context. */
    public static boolean shouldSuppressLunarCloakForBridge(Object playerBridge) {
        if (!GomodFixes.isWeaveRuntime()) {
            return false;
        }
        if (LunarRemoteDisguiseCosmetics.shouldHideBridge(playerBridge)) return true;
        if (bridgeIdentifiesSelf(playerBridge)) {
            return shouldSuppressLunarCloakForLocalPlayer();
        }
        UUID bridgeUuid = getBridgeUniqueId(playerBridge);
        if (isLocalPlayerBridgeUuid(bridgeUuid) || isLocalPlayerRenderContext()) {
            return shouldSuppressLunarCloakForLocalPlayer();
        }
        if (bridgeUuid == null) {
            // Lunar bridge method names vary by release. Its cloak call still happens inside the owning vanilla
            // player's living-layer render, which gives us an exact UUID without depending on obfuscated methods.
            bridgeUuid = CapeRenderContext.getRenderingPlayerUuid();
        }
        boolean suppress = GomodPeerStandaloneCape.shouldSuppressLunarCloakForUuid(bridgeUuid);
        if (!suppress) {
            String bridgeName = getBridgePlayerName(playerBridge);
            if (bridgeName == null) {
                bridgeName = CapeRenderContext.getRenderingPlayerName();
            }
            suppress = GomodPeerStandaloneCape.shouldSuppressLunarCloakForName(bridgeName);
        }
        if (suppress && !reportedRemoteBridgeSuppression) {
            reportedRemoteBridgeSuppression = true;
            System.out.println("[gomod][cape-trace] suppressed Lunar cloak for remote gomod cape peer");
        }
        return suppress;
    }

    public static boolean isLocalPlayerBridge(Object playerBridge) {
        return bridgeIdentifiesSelf(playerBridge)
                || isLocalPlayerBridgeUuid(getBridgeUniqueId(playerBridge))
                || isLocalPlayerRenderContext();
    }

    private static boolean bridgeIdentifiesSelf(Object playerBridge) {
        if (playerBridge == null) {
            return false;
        }
        try {
            Method isSelf = LunarPlayerBridgeMethods.find(playerBridge.getClass(), "bridge$isSelf");
            if (isSelf != null && Boolean.TRUE.equals(isSelf.invoke(playerBridge))) {
                if (!reportedBridgeSelfSuppression) {
                    reportedBridgeSelfSuppression = true;
                    System.out.println("[gomod][cape-trace] identified local Lunar player bridge via bridge$isSelf");
                }
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isLocalPlayerBridgeUuid(UUID bridgeUuid) {
        if (bridgeUuid == null) {
            return false;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.thePlayer != null && bridgeUuid.equals(mc.thePlayer.getUniqueID())) {
                if (!reportedBridgeUuidSuppression) {
                    reportedBridgeUuidSuppression = true;
                    System.out.println("[gomod][cape-trace] identified local Lunar player bridge via UUID");
                }
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static UUID getBridgeUniqueId(Object playerBridge) {
        if (playerBridge == null) {
            return null;
        }
        try {
            Method getUniqueId = LunarPlayerBridgeMethods.find(playerBridge.getClass(), "bridge$getUniqueID");
            if (getUniqueId == null) {
                return null;
            }
            Object value = getUniqueId.invoke(playerBridge);
            return value instanceof UUID ? (UUID) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String getBridgePlayerName(Object playerBridge) {
        if (playerBridge == null) {
            return null;
        }
        Class<?> playerType = playerBridge.getClass();
        for (String methodName : BRIDGE_NAME_GETTERS) {
            try {
                Method method = LunarPlayerBridgeMethods.find(playerType, methodName);
                if (method == null) {
                    continue;
                }
                Object value = method.invoke(playerBridge);
                if (value instanceof String) {
                    String name = ((String) value).trim();
                    if (!name.isEmpty()) {
                        return name;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean isLocalPlayerRenderContext() {
        boolean local = CapeRenderContext.isRenderingLocalPlayer();
        if (local && !reportedBridgeContextSuppression) {
            reportedBridgeContextSuppression = true;
            System.out.println("[gomod][cape-trace] identified local Lunar player bridge via render context");
        }
        return local;
    }

    /** Texture for gomod's dedicated local cape pass on Lunar, or {@code null} when it should not draw. */
    public static ResourceLocation getLocalGomodCapeForManualRender() {
        if (!GomodFixes.isWeaveRuntime() || !shouldSuppressLunarCloakForLocalPlayer()) {
            return null;
        }
        if (GomodInternalFeatures.isEnabled() && LocalDisguise.isEnabled()
                && LocalDisguise.shouldApplyCapeOverride()) {
            return LocalDisguise.isNoCapeSelected() ? null : LocalDisguise.getCapeLocation();
        }
        if (!shouldApplyLocalCapeOverride() || isNoCapeSelected()) {
            return null;
        }
        return getCapeLocation();
    }

    public static synchronized ResourceLocation getCapeLocation() {
        ensureSavedSelectionAppliedLocked();
        if (!isActive()) {
            return null;
        }
        String key = ConfigManager.getPlayerStandaloneCapeOptionKey();
        if (PlayerCustomCapes.isCustomOptionKey(key)) {
            ResourceLocation ready = PlayerCustomCapes.getTextureForOptionKey(key);
            if (ready != null) {
                activeCapeLocation = ready;
            }
        }
        return activeCapeLocation;
    }

    public static synchronized boolean isNoCapeSelected() {
        return isActive() && NO_CAPE_OPTION_KEY.equals(ConfigManager.getPlayerStandaloneCapeOptionKey());
    }

    /** The peer preference also applies when /disguiser targets this account. */
    public static synchronized boolean shouldUseGomodCapeForPeerSharing() {
        return getSharedDisguiseCape() != null
                || (isActive() && ConfigManager.isPlayerStandaloneCapeBroadcastToPeers());
    }

    /** Uses the published selection, independently of the local "You see" preference. */
    public static synchronized ResourceLocation getCapeLocationForPeerSharing() {
        LocalDisguise.CapeOptionInfo shared = getSharedDisguiseCape();
        if (shared != null) return shared.location;
        return shouldUseGomodCapeForPeerSharing() ? getCapeLocation() : null;
    }

    public static synchronized ResourceLocation getAccountCapeLocation() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null || mc.getNetHandler() == null
                    || mc.thePlayer.getGameProfile() == null || mc.thePlayer.getGameProfile().getId() == null) {
                return null;
            }
            NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getGameProfile().getId());
            return info == null ? null : info.getLocationCape();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static synchronized String getActiveOptionKeyForRelay() {
        LocalDisguise.CapeOptionInfo shared = getSharedDisguiseCape();
        if (shared != null) {
            return relayOptionKeyForWear(shared.optionKey, shared.location);
        }
        ensureSavedSelectionAppliedLocked();
        if (!isActive()) {
            return "";
        }
        String key = ConfigManager.getPlayerStandaloneCapeOptionKey();
        if (key == null || key.isEmpty()) {
            return "";
        }
        return relayOptionKeyForWear(key, activeCapeLocation);
    }

    /** True when this picker row is the cape currently advertised to other gomod clients. */
    public static synchronized boolean isCapeOptionShownToPeers(LocalDisguise.CapeOptionInfo option) {
        if (option == null || option.createEntry) {
            return false;
        }
        LocalDisguise.CapeOptionInfo shared = getSharedDisguiseCape();
        if (shared != null) {
            return (shared.optionKey != null && shared.optionKey.equals(option.optionKey))
                    || relayOptionKeyForWear(shared.optionKey, shared.location)
                            .equals(relayOptionKeyForWear(option.optionKey, option.location));
        }
        if (!isActive()) {
            return false;
        }
        String storedKey = ConfigManager.getPlayerStandaloneCapeOptionKey();
        String optionKey = option.optionKey;
        if (storedKey != null && storedKey.equals(optionKey)) {
            return true;
        }
        String activeRelayKey = getActiveOptionKeyForRelay();
        if (activeRelayKey == null || activeRelayKey.isEmpty()) {
            return false;
        }
        String optionRelayKey = relayOptionKeyForWear(optionKey, option.location);
        return activeRelayKey.equals(optionRelayKey);
    }

    public static synchronized ResourceLocation resolveLocationForOptionKey(String optionKey) {
        if (optionKey == null || optionKey.isEmpty()
                || OFF_OPTION_KEY.equals(optionKey) || NO_CAPE_OPTION_KEY.equals(optionKey)) {
            return null;
        }
        ResourceLocation owned = LocalDisguise.getLocalSelfCapeLocationForWearKey(optionKey);
        if (owned != null) {
            return owned;
        }
        String relayKey = relayOptionKeyForWear(optionKey, null);
        String catalogHash = MinecraftCapeCatalog.textureHashFromCatalogOptionKey(relayKey);
        if (catalogHash == null) {
            catalogHash = MinecraftCapeCatalog.textureHashFromCatalogOptionKey(optionKey);
        }
        if (catalogHash != null) {
            // Older builds rewrote third-party API hashes as gomod:catalog keys. Only resolve a catalog key
            // immediately when it is a known Mojang cape; otherwise wait for owned history to identify it.
            if (MinecraftCapeCatalog.labelForTextureHash(catalogHash) == null
                    && !LocalDisguise.isLocalSelfCapeCatalogReady()) {
                LocalDisguise.warmLocalSelfCapeCatalog();
                return null;
            }
            ResourceLocation catalog = MinecraftCapeCatalog.textureLocationForCatalogHash(catalogHash);
            if (catalog != null) {
                return catalog;
            }
        }
        for (LocalDisguise.CapeOptionInfo info : LocalDisguise.getLocalSelfCapeOptionsForGui()) {
            if (info != null && optionKey.equals(info.optionKey)) {
                return info.location;
            }
        }
        return PlayerCustomCapes.getTextureForOptionKey(optionKey);
    }

    public static synchronized String encodeActiveCustomWearBase64() {
        LocalDisguise.CapeOptionInfo shared = getSharedDisguiseCape();
        if (shared != null) {
            return PlayerCustomCapes.isCustomOptionKey(shared.optionKey)
                    ? PlayerCustomCapes.encodeOptionKeyPngBase64(shared.optionKey) : "";
        }
        if (!isActive()) {
            return "";
        }
        String key = ConfigManager.getPlayerStandaloneCapeOptionKey();
        if (!PlayerCustomCapes.isCustomOptionKey(key)) {
            return "";
        }
        return PlayerCustomCapes.encodeOptionKeyPngBase64(key);
    }

    private static synchronized String encodeActiveOwnedWearPngBase64(String storedKey, String relayKey) {
        if (PlayerCustomCapes.isCustomOptionKey(storedKey) || isNonCapeCatalogKey(storedKey)) {
            return "";
        }
        if (activeCapeLocation == null) {
            return "";
        }
        return PlayerCustomCapes.encodeLocationPngBase64(activeCapeLocation);
    }

    public static void attachRelayWearFields(com.google.gson.JsonObject payload) {
        if (payload == null) {
            return;
        }
        payload.addProperty("standaloneCapeStatePresent", true);
        LocalDisguise.CapeOptionInfo shared = getSharedDisguiseCape();
        if (shared != null) {
            // The Gomod disguise commands explicitly share the selected disguise cape, even when
            // the ordinary standalone picker advertises Lunar or has no cape selection of its own.
            payload.addProperty("standaloneCapeLunarOnWeave", false);
            payload.addProperty("standaloneCapeOptionKey", relayOptionKeyForWear(shared.optionKey, shared.location));
            payload.addProperty("standaloneCapeImageUrl", shared.imageUrl);
            String image = PlayerCustomCapes.isCustomOptionKey(shared.optionKey)
                    ? PlayerCustomCapes.encodeOptionKeyPngBase64(shared.optionKey)
                    : PlayerCustomCapes.encodeLocationPngBase64(shared.location);
            payload.addProperty("standaloneCapeImageBase64", image == null ? "" : image);
            return;
        }
        boolean defaultCapeForPeers = !ConfigManager.isPlayerStandaloneCapeBroadcastToPeers();
        // Preserve the existing relay field for compatibility. True means no Gomod override,
        // so each receiving client keeps its normal cape rendering.
        payload.addProperty("standaloneCapeLunarOnWeave", defaultCapeForPeers);
        if (defaultCapeForPeers) {
            payload.addProperty("standaloneCapeOptionKey", "");
            payload.addProperty("standaloneCapeImageUrl", "");
            payload.addProperty("standaloneCapeImageBase64", "");
            return;
        }
        if (!isActive()) {
            payload.addProperty("standaloneCapeOptionKey", "");
            return;
        }
        String storedKey = ConfigManager.getPlayerStandaloneCapeOptionKey();
        if (storedKey == null || storedKey.isEmpty()) {
            payload.addProperty("standaloneCapeOptionKey", "");
            return;
        }
        String key = getActiveOptionKeyForRelay();
        payload.addProperty("standaloneCapeOptionKey", key);
        String imageUrl = LocalDisguise.getLocalSelfCapeImageUrlForWearKey(storedKey);
        if (imageUrl != null && !imageUrl.isEmpty()) {
            payload.addProperty("standaloneCapeImageUrl", imageUrl);
        }
        String image = encodeActiveCustomWearBase64();
        if (image == null || image.isEmpty()) {
            image = encodeActiveOwnedWearPngBase64(storedKey, key);
        }
        if (image != null && !image.isEmpty()) {
            payload.addProperty("standaloneCapeImageBase64", image);
        }
    }

    private static LocalDisguise.CapeOptionInfo getSharedDisguiseCape() {
        return GomodPeerDisguise.isSharingLocalDisguise() ? LocalDisguise.getSelectedCapeForPeerSharing() : null;
    }

    /** Push standalone cape wear to relay peers immediately after selection or broadcast toggle. */
    public static void notifyWearChanged() {
        Main.getGlobalChatClient().requestImmediatePoll();
        ShareInvManager.resendHelloIfConnected();
    }

    /** Repairs startup/catalog races and periodically refreshes durable peer cape presence. */
    public static void refreshRelayWearIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastAutoRelayRefreshMs < 1000L) {
            return;
        }
        lastAutoRelayRefreshMs = now;
        String signature;
        synchronized (PlayerStandaloneCape.class) {
            ensureSavedSelectionAppliedLocked();
            String key = ConfigManager.getPlayerStandaloneCapeOptionKey();
            boolean imageReady = PlayerCustomCapes.isCustomOptionKey(key)
                    || PlayerCustomCapes.hasReadableCapeImage(activeCapeLocation);
            signature = ConfigManager.isPlayerStandaloneCapeBroadcastToPeers()
                    + "|" + safeDebug(key)
                    + "|" + safeDebug(activeCapeLocation)
                    + "|imageReady=" + imageReady
                    + "|ready=" + LocalDisguise.isLocalSelfCapeCatalogReady();
            LocalDisguise.CapeOptionInfo shared = getSharedDisguiseCape();
            if (shared != null) {
                signature += "|sharedDisguise=" + safeDebug(shared.optionKey)
                        + "|texture=" + safeDebug(shared.location)
                        + "|imageReady=" + PlayerCustomCapes.hasReadableCapeImage(shared.location);
            }
        }
        boolean changed = !signature.equals(lastAutoRelaySignature);
        boolean periodic = now - lastAutoRelayRefreshMsForPresence >= 30_000L;
        if (!changed && !periodic) {
            return;
        }
        lastAutoRelaySignature = signature;
        lastAutoRelayRefreshMsForPresence = now;
        notifyWearChanged();
    }

    private static long lastAutoRelayRefreshMsForPresence;

    /** Human-readable source decision for /capedebug. */
    public static synchronized String buildLocalDecisionDebug() {
        ensureSavedSelectionAppliedLocked();
        String storedKey = ConfigManager.getPlayerStandaloneCapeOptionKey();
        String effective;
        String reason;
        if (GomodInternalFeatures.isEnabled() && LocalDisguise.isEnabled()
                && LocalDisguise.shouldApplyCapeOverride()) {
            effective = LocalDisguise.isNoCapeSelected() ? "none" : "disguise gomod cape";
            reason = "active disguise overrides standalone cape";
        } else if (!ConfigManager.isPlayerStandaloneCapePreferGomod()) {
            effective = GomodFixes.isWeaveRuntime() ? "Lunar cloak" : "Default cape";
            reason = GomodFixes.isWeaveRuntime()
                    ? "You see is set to Lunar cape; vanilla Minecraft cape layer is suppressed"
                    : "You see is set to Default cape; the native Forge cape is left visible";
        } else if (!isActive()) {
            effective = "vanilla/account cape";
            reason = storedKey == null || storedKey.isEmpty() ? "no standalone gomod cape selected" : "feature unavailable";
        } else if (NO_CAPE_OPTION_KEY.equals(storedKey)) {
            effective = "none";
            reason = "No Cape selected";
        } else if (activeCapeLocation == null) {
            effective = "unresolved";
            reason = LocalDisguise.isLocalSelfCapeCatalogLoading()
                    ? "owned cape catalog is still loading" : "saved key was not found in the loaded catalog";
        } else {
            effective = "gomod cape";
            reason = "You see is set to gomod cape and selected texture resolved";
        }
        return "youSeeSetting=" + (ConfigManager.isPlayerStandaloneCapePreferGomod()
                ? "gomod cape" : GomodFixes.isWeaveRuntime() ? "Lunar cape" : "Default cape")
                + " effective=" + effective
                + " reason=" + reason
                + " key=" + safeDebug(storedKey)
                + " canonicalKey=" + safeDebug(activeCapeOptionKey)
                + " label=" + safeDebug(getSelectedLabelLocked())
                + " texture=" + safeDebug(activeCapeLocation)
                + " accountCape=" + safeDebug(getAccountCapeLocation())
                + " catalogReady=" + LocalDisguise.isLocalSelfCapeCatalogReady()
                + " catalogLoading=" + LocalDisguise.isLocalSelfCapeCatalogLoading()
                + " lunarCloakDetected=" + LunarExternalOpacityBridge.hasObservedLunarCloak()
                + " lunarLastAgeMs=" + LunarExternalOpacityBridge.lastLunarCloakAgeMs()
                + " lunarSuppressed=" + LunarExternalOpacityBridge.suppressedLunarCloakCount();
    }

    /** Exact peer advertisement decision and payload availability for /capedebug. */
    public static synchronized String buildPeerDecisionDebug() {
        LocalDisguise.CapeOptionInfo shared = getSharedDisguiseCape();
        if (shared != null) {
            com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
            attachRelayWearFields(payload);
            return "gomodPlayersSeeSetting=shared_disguise effective="
                    + (NO_CAPE_OPTION_KEY.equals(shared.optionKey) ? "none" : "disguise_cape")
                    + " reason=Gomod_disguise_shares_selected_cape"
                    + " relayKey=" + safeDebug(payload.get("standaloneCapeOptionKey").getAsString())
                    + " imageBase64Chars=" + payload.get("standaloneCapeImageBase64").getAsString().length();
        }
        ensureSavedSelectionAppliedLocked();
        boolean broadcast = ConfigManager.isPlayerStandaloneCapeBroadcastToPeers();
        String storedKey = ConfigManager.getPlayerStandaloneCapeOptionKey();
        String relayKey = getActiveOptionKeyForRelay();
        String imageUrl = storedKey == null ? "" : LocalDisguise.getLocalSelfCapeImageUrlForWearKey(storedKey);
        String customImage = encodeActiveCustomWearBase64();
        String ownedImage = customImage == null || customImage.isEmpty()
                ? encodeActiveOwnedWearPngBase64(storedKey, relayKey) : "";
        int imageChars = customImage != null && !customImage.isEmpty()
                ? customImage.length() : (ownedImage == null ? 0 : ownedImage.length());
        String effective;
        String reason;
        if (!broadcast) {
            effective = "no gomod override";
            reason = GomodFixes.isWeaveRuntime()
                    ? "gomod players see is set to Lunar cloak"
                    : "gomod players see is set to Default cape";
        } else if (!isActive() || relayKey == null || relayKey.isEmpty()) {
            effective = "no gomod override";
            reason = "no active standalone cape to advertise";
        } else if (activeCapeLocation == null) {
            effective = "unresolved";
            reason = "selected texture has not resolved yet";
        } else {
            effective = "gomod cape";
            reason = imageChars > 0
                    ? "relay has the selected key and encoded texture" : "relay has only the selected key";
        }
        return "gomodPlayersSeeSetting=" + (broadcast ? "gomod cape"
                : GomodFixes.isWeaveRuntime() ? "Lunar cloak" : "Default cape")
                + " effective=" + effective
                + " reason=" + reason
                + " relayKey=" + safeDebug(relayKey)
                + " sourceUrl=" + safeDebug(imageUrl)
                + " imageBase64Chars=" + imageChars
                + " imageStats=" + PlayerCustomCapes.debugCapeImageStats(activeCapeLocation);
    }

    private static String safeDebug(Object value) {
        if (value == null || String.valueOf(value).trim().isEmpty()) {
            return "<none>";
        }
        return String.valueOf(value).replace(' ', '_');
    }

    public static synchronized int getOptionCount() {
        return LocalDisguise.getLocalSelfCapeOptionsForGui().size();
    }

    public static synchronized String getButtonLabel() {
        if (!isActive()) {
            return EnumChatFormatting.AQUA + "Choose cape";
        }
        if (LocalDisguise.isLocalSelfCapeCatalogLoading()) {
            return EnumChatFormatting.YELLOW + "Loading capes...";
        }
        if (!LocalDisguise.isLocalSelfCapeCatalogReady()) {
            return EnumChatFormatting.AQUA + "Choose cape";
        }
        String label = getSelectedLabelLocked();
        if (label == null || label.isEmpty()) {
            return EnumChatFormatting.AQUA + "Choose cape";
        }
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(label);
        EnumChatFormatting color = plain != null && plain.endsWith(" Cape") ? EnumChatFormatting.WHITE : EnumChatFormatting.AQUA;
        return color + label;
    }

    public static synchronized List<LocalDisguise.CapeOptionInfo> getCapeOptionsForGui() {
        return LocalDisguise.getLocalSelfCapeOptionsForGui();
    }

    public static synchronized int getSelectedCapeIndex() {
        return LocalDisguise.getLocalSelfCapeGuiIndex();
    }

    public static synchronized void setCapeSelectionIndex(int index) {
        LocalDisguise.setLocalSelfCapeGuiIndex(index);
    }

    public static synchronized void applyGuiSelection(LocalDisguise.CapeOptionInfo picked) {
        if (!GomodInternalFeatures.isStandaloneCapeEnabled()) {
            return;
        }
        if (picked == null || picked.createEntry || !picked.selectable) {
            return;
        }
        if (NO_CAPE_OPTION_KEY.equals(picked.optionKey)) {
            setWearKey(NO_CAPE_OPTION_KEY, null);
            return;
        }
        if (picked.customUserCape && picked.optionKey != null) {
            setWearKey(picked.optionKey, picked.location);
            return;
        }
        String key = picked.optionKey != null ? picked.optionKey : (picked.location != null ? picked.location.toString() : "");
        ResourceLocation wearLocation = picked.location;
        if (wearLocation == null) {
            String catalogHash = MinecraftCapeCatalog.textureHashFromCatalogOptionKey(key);
            wearLocation = MinecraftCapeCatalog.textureLocationForCatalogHash(catalogHash);
        }
        if (wearLocation == null) {
            setWearKey(OFF_OPTION_KEY, null);
            return;
        }
        setWearKey(relayOptionKeyForWear(key, wearLocation), wearLocation);
    }

    /** Maps owned-row ResourceLocation keys to {@code gomod:catalog:<hash>} for cross-client relay resolution. */
    static String relayOptionKeyForWear(String optionKey, ResourceLocation location) {
        if (PlayerCustomCapes.isCustomOptionKey(optionKey)) {
            return optionKey;
        }
        if (optionKey != null && optionKey.startsWith("gomod:cape-history:")) {
            return optionKey;
        }
        if (optionKey != null && optionKey.startsWith(MinecraftCapeCatalog.CATALOG_OPTION_KEY_PREFIX)) {
            return optionKey;
        }
        if (isNonCapeCatalogKey(optionKey)) {
            return optionKey == null ? "" : optionKey;
        }
        String hash = MinecraftCapeCatalog.textureHashFromCatalogOptionKey(optionKey);
        if (hash == null) {
            hash = LocalDisguise.getLocalSelfCapeTextureHashForWearKey(optionKey);
        }
        if (hash == null && location != null) {
            hash = MinecraftCapeCatalog.textureHashFromResourceLocation(location);
        }
        if (hash != null && !hash.isEmpty()) {
            String catalogKey = MinecraftCapeCatalog.catalogOptionKey(hash);
            if (catalogKey != null) {
                return catalogKey;
            }
        }
        if (optionKey != null && !optionKey.isEmpty()) {
            return optionKey;
        }
        return location != null ? location.toString() : "";
    }

    public static MinecraftCapeSelectorGUI.CapeListSource asCapeListSource() {
        return new MinecraftCapeSelectorGUI.CapeListSource() {
            @Override
            public List<LocalDisguise.CapeOptionInfo> getOptions() {
                return PlayerStandaloneCape.getCapeOptionsForGui();
            }

            @Override
            public int getSelectedIndex() {
                return PlayerStandaloneCape.getSelectedCapeIndex();
            }

            @Override
            public void setSelectedIndex(int index) {
                PlayerStandaloneCape.setCapeSelectionIndex(index);
            }
        };
    }

    private static void applySavedSelectionLocked() {
        String key = ConfigManager.getPlayerStandaloneCapeOptionKey();
        if (!GomodInternalFeatures.isStandaloneCapeEnabled() || key == null || key.isEmpty()) {
            activeCapeLocation = null;
            activeCapeOptionKey = "";
            return;
        }
        if (key == null || key.isEmpty() || OFF_OPTION_KEY.equals(key)) {
            ConfigManager.setPlayerStandaloneCapeSelection("");
            activeCapeLocation = null;
            activeCapeOptionKey = "";
            return;
        }
        activeCapeLocation = resolveLocationForOptionKey(key);
        String canonicalKey = LocalDisguise.getLocalSelfCapeCanonicalWearKey(key);
        if (canonicalKey.startsWith("gomod:cape-history:") && !canonicalKey.equals(key)) {
            ConfigManager.setPlayerStandaloneCapeSelection(canonicalKey);
            key = canonicalKey;
        }
        // The owned-cape catalog loads asynchronously. Keep an unresolved saved key and retry instead of erasing it.
        activeCapeOptionKey = key;
    }

    private static void ensureSavedSelectionAppliedLocked() {
        String key = ConfigManager.getPlayerStandaloneCapeOptionKey();
        if (!GomodInternalFeatures.isStandaloneCapeEnabled() || key == null || key.isEmpty()) {
            activeCapeLocation = null;
            activeCapeOptionKey = "";
            return;
        }
        if (key.equals(activeCapeOptionKey)
                && (NO_CAPE_OPTION_KEY.equals(key) || activeCapeLocation != null)) {
            return;
        }
        applySavedSelectionLocked();
    }

    private static String getSelectedLabelLocked() {
        if (!isActive()) {
            return null;
        }
        String key = ConfigManager.getPlayerStandaloneCapeOptionKey();
        if (NO_CAPE_OPTION_KEY.equals(key)) {
            return "No Cape";
        }
        if (PlayerCustomCapes.isCustomOptionKey(key)) {
            PlayerCustomCapes.ensureLoaded();
            for (LocalDisguise.CapeOptionInfo custom : PlayerCustomCapes.buildGuiEntries()) {
                if (custom != null && !custom.createEntry && key.equals(custom.optionKey)) {
                    return EnumChatFormatting.getTextWithoutFormattingCodes(custom.label);
                }
            }
        }
        String catalogHash = MinecraftCapeCatalog.textureHashFromCatalogOptionKey(key);
        if (catalogHash != null) {
            String catalogLabel = MinecraftCapeCatalog.labelForTextureHash(catalogHash);
            if (catalogLabel != null && !catalogLabel.isEmpty()) {
                return catalogLabel;
            }
        }
        if (LocalDisguise.isLocalSelfCapeCatalogReady()) {
            String ownedLabel = LocalDisguise.getLocalSelfCapeOptionLabelForKey(key);
            if (ownedLabel != null && !ownedLabel.isEmpty()) {
                return EnumChatFormatting.getTextWithoutFormattingCodes(ownedLabel);
            }
        }
        return null;
    }

    private static void setWearKey(String key, ResourceLocation location) {
        if (OFF_OPTION_KEY.equals(key)) {
            ConfigManager.setPlayerStandaloneCapeSelection("");
            activeCapeLocation = null;
            activeCapeOptionKey = "";
            notifyWearChanged();
            return;
        }
        ConfigManager.setPlayerStandaloneCapeSelection(key);
        activeCapeLocation = location;
        activeCapeOptionKey = ConfigManager.getPlayerStandaloneCapeOptionKey();
        notifyWearChanged();
    }
}
