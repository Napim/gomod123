package me.napim.gomod.gui;

import com.mojang.authlib.GameProfile;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.network.NetworkPlayerInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.item.EntityMinecartEmpty;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.world.World;
import net.minecraft.client.renderer.entity.RenderPlayer;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.Denicker;
import me.napim.gomod.features.DenickerRealSkinCache;
import me.napim.gomod.features.VisibleSpecs;
import me.napim.gomod.mixins.NetworkPlayerInfoAccessor;
import me.napim.gomod.util.GomodClasspathTextures;
import me.napim.gomod.util.PlayerSkinTextures;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.util.ResourceLocation;
import me.napim.gomod.util.MinecraftPrivateAccess;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;

/**
 * Small 3D previews for transparent-mounts GUI (same idea as {@link me.napim.gomod.features.MobHealthDisplay#renderMobInGui}).
 * Order: boat, pig, horse, donkey, minecart (left → right).
 */
public final class MountPreviewHelper {

    /** UUID with even {@link UUID#hashCode()} lsb → default Steve skin (see {@link net.minecraft.client.resources.DefaultPlayerSkin}). */
    private static final UUID STEVE_FALLBACK_UUID = new UUID(0L, 0L);
    private static final UUID CAPE_PREVIEW_UUID = UUID.fromString("00000000-0000-0000-0000-00000000cafe");

    private static EntityOtherPlayerMP previewRider;
    private static EntityOtherPlayerMP capePreviewPlayer;
    private static NetworkPlayerInfo capePreviewSyntheticInfo;
    private static boolean capePreviewInjectedSyntheticInfo;
    private static UUID capePreviewSourceUuid;
    private static int guiPreviewNametagSuppressDepth;
    private static int guiPreviewOpacityBypassDepth;
    private static int guiPreviewAlphaDepth;
    private static float guiPreviewAlpha = 1.0F;
    private static Entity guiPreviewOpacityTarget;
    private static int guiPreviewOpacityPhase;
    private static int guiPreviewSinglePassLayerDepth;

    /** Active entity during {@link #renderCapePreviewPlayer} fade pass. */
    private static EntityOtherPlayerMP capeEditorRenderTarget;
    private static boolean capeEditorSuppressCapeLayer;
    private static boolean capeEditorCapeOnlyPass;
    private static boolean capeEditorBodyOpacityPass;
    private static float capeEditorBodyAlpha = 1.0F;

    private MountPreviewHelper() {
    }

    /**
     * Creates client-only entities for GUI rendering (not added to the world).
     * Index 0–4: boat, pig, horse, donkey, cart. Call {@link #placeLocalPlayerOnPreviewSlot} to attach the player preview.
     */
    public static Entity[] createMountPreviews(World world, Minecraft mc) {
        if (world == null) {
            return new Entity[5];
        }
        discardPreviewRider();
        Entity[] out = new Entity[5];
        try {
            out[0] = new EntityBoat(world);
        } catch (Throwable ignored) {
        }
        try {
            EntityPig pig = new EntityPig(world);
            pig.setGrowingAge(0);
            out[1] = pig;
        } catch (Throwable ignored) {
        }
        try {
            EntityHorse horse = new EntityHorse(world);
            horse.setHorseType(0);
            horse.setGrowingAge(0);
            out[2] = horse;
        } catch (Throwable ignored) {
        }
        try {
            EntityHorse donkey = new EntityHorse(world);
            donkey.setHorseType(1);
            donkey.setGrowingAge(0);
            out[3] = donkey;
        } catch (Throwable ignored) {
        }
        try {
            out[4] = new EntityMinecartEmpty(world);
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * Puts the local player preview on one mount (opacity sliders drive which slot is “active”).
     *
     * @param slotIndex 0 boat, 1 pig, 2 horse, 3 donkey, 4 minecart
     */
    public static void placeLocalPlayerOnPreviewSlot(Entity[] mounts, Minecraft mc, int slotIndex) {
        if (mounts == null || mc == null || mc.thePlayer == null) {
            return;
        }
        World w = mc.theWorld;
        if (w == null) {
            return;
        }
        if (slotIndex < 0 || slotIndex >= mounts.length) {
            return;
        }
        for (Entity e : mounts) {
            if (e != null && e.riddenByEntity != null) {
                e.riddenByEntity.mountEntity(null);
            }
        }
        Entity mount = mounts[slotIndex];
        if (mount == null) {
            return;
        }
        EntityOtherPlayerMP rider = getOrCreatePreviewRider(w, mc);
        freezeCapePreviewPlayer(rider);
        mount.setPosition(0.0, 0.0, 0.0);
        mount.prevPosX = mount.prevPosY = mount.prevPosZ = 0.0;
        mount.lastTickPosX = mount.lastTickPosY = mount.lastTickPosZ = 0.0;
        rider.setPosition(0.0, 0.0, 0.0);
        rider.prevPosX = rider.prevPosY = rider.prevPosZ = 0.0;
        rider.lastTickPosX = rider.lastTickPosY = rider.lastTickPosZ = 0.0;
        mount.rotationYaw = mount.prevRotationYaw = 0.0F;
        mount.rotationPitch = mount.prevRotationPitch = 0.0F;
        rider.rotationYaw = rider.prevRotationYaw = 0.0F;
        rider.rotationPitch = rider.prevRotationPitch = 0.0F;
        if (mount instanceof EntityPig) {
            ((EntityPig) mount).setSaddled(true);
        }
        rider.mountEntity(mount);
        positionRiderOnMountForGui(mount, rider);
        rider.prevPosX = rider.lastTickPosX = rider.posX;
        rider.prevPosY = rider.lastTickPosY = rider.posY;
        rider.prevPosZ = rider.lastTickPosZ = rider.posZ;
        syncCapePhysicsFields(rider);
    }

    public static void discardPreviewRider() {
        if (previewRider != null) {
            try {
                previewRider.mountEntity(null);
            } catch (Throwable ignored) {
            }
        }
        previewRider = null;
    }

    /** Standalone player model for settings GUIs (not added to the world). */
    public static EntityOtherPlayerMP createStandalonePreviewPlayer(World world, Minecraft mc) {
        if (world == null || mc == null || mc.thePlayer == null) {
            return null;
        }
        discardStandalonePreviewPlayer();
        GameProfile use = pickPreviewProfile(mc, mc.thePlayer.getGameProfile());
        standalonePreviewPlayer = new EntityOtherPlayerMP(world, use);
        standalonePreviewCapeVisible = true;
        syncPreviewRiderModelParts(standalonePreviewPlayer, mc);
        ensurePreviewCapeLayerEnabled(standalonePreviewPlayer, mc);
        return standalonePreviewPlayer;
    }

    /** Denicker settings preview — nick skin or real Mojang skin per {@link ConfigManager#isDenickerRevealRealSkinEnabled()}. */
    public static EntityOtherPlayerMP createDenickerPreviewPlayer(World world, Minecraft mc) {
        if (world == null || mc == null) {
            return null;
        }
        discardDenickerPreviewPlayer(mc);
        GameProfile profile = new GameProfile(Denicker.PREVIEW_SKIN_UUID, Denicker.PREVIEW_NICK);
        registerDenickerPreviewPlayerInfo(mc, profile, ConfigManager.isDenickerRevealRealSkinEnabled());
        standalonePreviewPlayer = new EntityOtherPlayerMP(world, profile);
        standalonePreviewCapeVisible = false;
        syncPreviewRiderModelParts(standalonePreviewPlayer, mc);
        disablePreviewCape(standalonePreviewPlayer);
        return standalonePreviewPlayer;
    }

    public static void discardDenickerPreviewPlayer(Minecraft mc) {
        discardStandalonePreviewPlayer();
        removeDenickerSyntheticPlayerInfo(mc);
    }

    /** Spectator settings preview — {@link VisibleSpecs#PREVIEW_SPECTATOR_NAME} with Mojang skin. */
    public static EntityOtherPlayerMP createVisibleSpecsPreviewPlayer(World world, Minecraft mc) {
        if (world == null || mc == null) {
            return null;
        }
        discardVisibleSpecsPreviewPlayer(mc);
        GameProfile profile = new GameProfile(VisibleSpecs.PREVIEW_SPECTATOR_UUID, VisibleSpecs.PREVIEW_SPECTATOR_NAME);
        registerVisibleSpecsPreviewPlayerInfo(mc, profile);
        visibleSpecsPreviewPlayer = new EntityOtherPlayerMP(world, profile);
        freezeCapePreviewPlayer(visibleSpecsPreviewPlayer);
        syncPreviewRiderModelParts(visibleSpecsPreviewPlayer, mc);
        disablePreviewCape(visibleSpecsPreviewPlayer);
        return visibleSpecsPreviewPlayer;
    }

    public static void discardVisibleSpecsPreviewPlayer(Minecraft mc) {
        visibleSpecsPreviewPlayer = null;
        removeVisibleSpecsSyntheticPlayerInfo(mc);
    }

    public static boolean isVisibleSpecsPreviewPlayer(Entity entity) {
        return entity != null && entity == visibleSpecsPreviewPlayer;
    }

    public static void renderVisibleSpecsPreviewPlayer(Minecraft mc, EntityOtherPlayerMP player, int posX, int posY,
                                                       int scale, float alpha) {
        if (mc == null || player == null || alpha < 0.04f) {
            return;
        }
        syncPreviewRiderModelParts(player, mc);
        disablePreviewCape(player);
        renderEntityPreview(mc, player, posX, posY, scale, alpha, 45.0F, alpha < 0.99f);
    }

    public static void refreshVisibleSpecsPreviewSkinIfNeeded(Minecraft mc, EntityOtherPlayerMP previewPlayer) {
        if (mc == null || previewPlayer == null) {
            return;
        }
        if (!visibleSpecsInjectedSyntheticInfo || visibleSpecsSyntheticPlayerInfo == null) {
            return;
        }
        ResourceLocation skin = DenickerRealSkinCache.resolveSkinForMojangUuid(
                VisibleSpecs.PREVIEW_SPECTATOR_UUID, VisibleSpecs.PREVIEW_SPECTATOR_NAME);
        String skinType = DenickerRealSkinCache.resolveSkinTypeForMojangUuid(
                VisibleSpecs.PREVIEW_SPECTATOR_UUID, VisibleSpecs.PREVIEW_SPECTATOR_NAME);
        if (skin == null || !(visibleSpecsSyntheticPlayerInfo instanceof NetworkPlayerInfoAccessor)) {
            return;
        }
        if (skin.equals(visibleSpecsPreviewAppliedSkin)
                && !DenickerRealSkinCache.isDefaultPlayerSkin(skin)) {
            return;
        }
        if (DenickerRealSkinCache.isDefaultPlayerSkin(skin)
                && (DenickerRealSkinCache.isLoadingForMojangUuid(VisibleSpecs.PREVIEW_SPECTATOR_UUID)
                || DenickerRealSkinCache.isDefaultPlayerSkin(visibleSpecsPreviewAppliedSkin))) {
            return;
        }
        NetworkPlayerInfoAccessor accessor = (NetworkPlayerInfoAccessor) visibleSpecsSyntheticPlayerInfo;
        accessor.gomod$setPlayerTexturesLoaded(true);
        accessor.gomod$setLocationSkin(skin);
        accessor.gomod$setSkinType(skinType);
        visibleSpecsPreviewAppliedSkin = skin;
    }

    private static void registerVisibleSpecsPreviewPlayerInfo(Minecraft mc, GameProfile profile) {
        if (mc == null || mc.getNetHandler() == null || profile == null || profile.getId() == null) {
            return;
        }
        UUID uuid = profile.getId();
        NetworkPlayerInfo existing = mc.getNetHandler().getPlayerInfo(uuid);
        if (existing != null) {
            visibleSpecsInjectedSyntheticInfo = false;
            visibleSpecsSyntheticPlayerInfo = null;
            return;
        }
        DenickerRealSkinCache.ensurePrefetchMojangUuid(uuid, VisibleSpecs.PREVIEW_SPECTATOR_NAME);
        NetworkPlayerInfo info = new NetworkPlayerInfo(profile);
        ResourceLocation skin = DenickerRealSkinCache.resolveSkinForMojangUuid(uuid, VisibleSpecs.PREVIEW_SPECTATOR_NAME);
        String skinType = DenickerRealSkinCache.resolveSkinTypeForMojangUuid(uuid, VisibleSpecs.PREVIEW_SPECTATOR_NAME);
        if (info instanceof NetworkPlayerInfoAccessor) {
            NetworkPlayerInfoAccessor accessor = (NetworkPlayerInfoAccessor) info;
            accessor.gomod$setPlayerTexturesLoaded(true);
            accessor.gomod$setLocationSkin(skin);
            accessor.gomod$setSkinType(skinType);
            accessor.gomod$setLocationCape(null);
        }
        if (putPlayerInfoMapEntry(mc, uuid, info)) {
            visibleSpecsSyntheticPlayerInfo = info;
            visibleSpecsInjectedSyntheticInfo = true;
            visibleSpecsPreviewAppliedSkin = skin;
        }
    }

    private static void removeVisibleSpecsSyntheticPlayerInfo(Minecraft mc) {
        if (!visibleSpecsInjectedSyntheticInfo || mc == null || mc.getNetHandler() == null
                || visibleSpecsSyntheticPlayerInfo == null) {
            visibleSpecsInjectedSyntheticInfo = false;
            visibleSpecsSyntheticPlayerInfo = null;
            visibleSpecsPreviewAppliedSkin = null;
            return;
        }
        try {
            java.lang.reflect.Field mapField = MinecraftPrivateAccess.findField(
                    net.minecraft.client.network.NetHandlerPlayClient.class, "playerInfoMap", "field_147310_i");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<UUID, NetworkPlayerInfo> map =
                    (java.util.Map<UUID, NetworkPlayerInfo>) mapField.get(mc.getNetHandler());
            if (map != null && map.get(VisibleSpecs.PREVIEW_SPECTATOR_UUID) == visibleSpecsSyntheticPlayerInfo) {
                map.remove(VisibleSpecs.PREVIEW_SPECTATOR_UUID);
            }
        } catch (Throwable ignored) {
        }
        visibleSpecsInjectedSyntheticInfo = false;
        visibleSpecsSyntheticPlayerInfo = null;
        visibleSpecsPreviewAppliedSkin = null;
    }

    /** Denicker settings inject a client-only {@link NetworkPlayerInfo} for the model preview — never show it in TAB. */
    public static boolean isDenickerSyntheticPlayerInfo(NetworkPlayerInfo info) {
        return denickerInjectedSyntheticInfo && info != null && info == denickerSyntheticPlayerInfo;
    }

    public static boolean isVisibleSpecsSyntheticPlayerInfo(NetworkPlayerInfo info) {
        return visibleSpecsInjectedSyntheticInfo && info != null && info == visibleSpecsSyntheticPlayerInfo;
    }

    /** Hides client-only GUI preview {@link NetworkPlayerInfo} rows from TAB. */
    public static Collection<NetworkPlayerInfo> filterGuiPreviewSyntheticFromTabList(Collection<NetworkPlayerInfo> original) {
        if (original == null || original.isEmpty()) {
            return original;
        }
        if (!denickerInjectedSyntheticInfo && !capePreviewInjectedSyntheticInfo && !visibleSpecsInjectedSyntheticInfo) {
            return original;
        }
        ArrayList<NetworkPlayerInfo> filtered = new ArrayList<NetworkPlayerInfo>(original.size());
        boolean removed = false;
        for (NetworkPlayerInfo info : original) {
            if (isDenickerSyntheticPlayerInfo(info) || isCapePreviewSyntheticPlayerInfo(info)
                    || isVisibleSpecsSyntheticPlayerInfo(info)) {
                removed = true;
                continue;
            }
            filtered.add(info);
        }
        return removed ? filtered : original;
    }

    private static NetworkPlayerInfo denickerSyntheticPlayerInfo;
    private static boolean denickerInjectedSyntheticInfo;
    private static ResourceLocation denickerPreviewAppliedSkin;
    private static boolean standalonePreviewCapeVisible;
    private static EntityOtherPlayerMP visibleSpecsPreviewPlayer;
    private static NetworkPlayerInfo visibleSpecsSyntheticPlayerInfo;
    private static boolean visibleSpecsInjectedSyntheticInfo;
    private static ResourceLocation visibleSpecsPreviewAppliedSkin;

    /** Bundled preview skin for {@link DenickerSettingsGUI} (see assets/gomod123/textures/denicker/nickskin.png). */
    private static final ResourceLocation DENICKER_PREVIEW_SKIN =
            new ResourceLocation("gomod123", "textures/denicker/nickskin.png");

    private static void registerDenickerPreviewPlayerInfo(Minecraft mc, GameProfile profile, boolean revealRealSkin) {
        if (mc == null || mc.getNetHandler() == null || profile == null || profile.getId() == null) {
            return;
        }
        UUID uuid = profile.getId();
        NetworkPlayerInfo existing = mc.getNetHandler().getPlayerInfo(uuid);
        if (existing != null) {
            denickerInjectedSyntheticInfo = false;
            denickerSyntheticPlayerInfo = null;
            return;
        }
        NetworkPlayerInfo info = new NetworkPlayerInfo(profile);
        ResourceLocation skin;
        String skinType;
        if (revealRealSkin) {
            DenickerRealSkinCache.ensurePrefetchMojangUuid(uuid, Denicker.PREVIEW_REAL);
            skin = DenickerRealSkinCache.resolveSkinForMojangUuid(uuid, Denicker.PREVIEW_REAL);
            skinType = DenickerRealSkinCache.resolveSkinTypeForMojangUuid(uuid, Denicker.PREVIEW_REAL);
        } else {
            skin = loadDenickerPreviewSkinTexture(mc, uuid);
            skinType = "default";
        }
        if (info instanceof NetworkPlayerInfoAccessor) {
            NetworkPlayerInfoAccessor accessor = (NetworkPlayerInfoAccessor) info;
            accessor.gomod$setPlayerTexturesLoaded(true);
            accessor.gomod$setLocationSkin(skin);
            accessor.gomod$setSkinType(skinType);
            accessor.gomod$setLocationCape(null);
        }
        if (putPlayerInfoMapEntry(mc, uuid, info)) {
            denickerSyntheticPlayerInfo = info;
            denickerInjectedSyntheticInfo = true;
            denickerPreviewAppliedSkin = skin;
        }
    }

    /** Updates preview model skin after async Mojang fetch (real-skin toggle). */
    public static void refreshDenickerPreviewSkinIfNeeded(Minecraft mc, EntityOtherPlayerMP previewPlayer) {
        if (mc == null || previewPlayer == null || !ConfigManager.isDenickerRevealRealSkinEnabled()) {
            return;
        }
        if (!denickerInjectedSyntheticInfo || denickerSyntheticPlayerInfo == null) {
            return;
        }
        ResourceLocation skin = DenickerRealSkinCache.resolveSkinForMojangUuid(
                Denicker.PREVIEW_SKIN_UUID, Denicker.PREVIEW_REAL);
        String skinType = DenickerRealSkinCache.resolveSkinTypeForMojangUuid(
                Denicker.PREVIEW_SKIN_UUID, Denicker.PREVIEW_REAL);
        if (skin == null || !(denickerSyntheticPlayerInfo instanceof NetworkPlayerInfoAccessor)) {
            return;
        }
        if (skin.equals(denickerPreviewAppliedSkin)
                && !DenickerRealSkinCache.isDefaultPlayerSkin(skin)) {
            return;
        }
        if (DenickerRealSkinCache.isDefaultPlayerSkin(skin)
                && (DenickerRealSkinCache.isLoadingForMojangUuid(Denicker.PREVIEW_SKIN_UUID)
                || DenickerRealSkinCache.isDefaultPlayerSkin(denickerPreviewAppliedSkin))) {
            return;
        }
        NetworkPlayerInfoAccessor accessor = (NetworkPlayerInfoAccessor) denickerSyntheticPlayerInfo;
        accessor.gomod$setPlayerTexturesLoaded(true);
        accessor.gomod$setLocationSkin(skin);
        accessor.gomod$setSkinType(skinType);
        denickerPreviewAppliedSkin = skin;
    }

    private static ResourceLocation denickerPreviewSkinRuntime;

    private static ResourceLocation loadDenickerPreviewSkinTexture(Minecraft mc, UUID uuid) {
        try {
            ResourceLocation loaded = ensureDenickerPreviewSkinLoaded(mc);
            if (loaded != null && mc.getTextureManager().getTexture(loaded) != null) {
                return loaded;
            }
        } catch (Throwable ignored) {
        }
        return DefaultPlayerSkin.getDefaultSkin(uuid);
    }

    private static ResourceLocation ensureDenickerPreviewSkinLoaded(Minecraft mc) {
        if (mc == null) {
            return null;
        }
        if (denickerPreviewSkinRuntime != null
                && mc.getTextureManager().getTexture(denickerPreviewSkinRuntime) != null) {
            return denickerPreviewSkinRuntime;
        }
        BufferedImage img = readDenickerClasspathSkin();
        if (img == null) {
            return GomodClasspathTextures.ensureLoaded(mc, DENICKER_PREVIEW_SKIN);
        }
        BufferedImage upgraded = PlayerSkinTextures.upgradeLegacySkin(img);
        ResourceLocation runtime = new ResourceLocation("gomod123", "runtime/denicker_nickskin");
        try {
            mc.getTextureManager().loadTexture(runtime, new DynamicTexture(upgraded));
            denickerPreviewSkinRuntime = runtime;
            return runtime;
        } catch (Throwable ignored) {
            return GomodClasspathTextures.ensureLoaded(mc, DENICKER_PREVIEW_SKIN);
        }
    }

    private static BufferedImage readDenickerClasspathSkin() {
        String path = "/assets/" + DENICKER_PREVIEW_SKIN.getResourceDomain() + "/"
                + DENICKER_PREVIEW_SKIN.getResourcePath();
        InputStream in = GomodClasspathTextures.class.getResourceAsStream(path);
        if (in == null) {
            in = me.napim.gomod.util.ClasspathGomodResourcePack.class.getResourceAsStream(path);
        }
        if (in == null) {
            return null;
        }
        try {
            return ImageIO.read(in);
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean putPlayerInfoMapEntry(Minecraft mc, UUID uuid, NetworkPlayerInfo info) {
        try {
            java.lang.reflect.Field mapField = MinecraftPrivateAccess.findField(
                    net.minecraft.client.network.NetHandlerPlayClient.class, "playerInfoMap", "field_147310_i");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<UUID, NetworkPlayerInfo> map =
                    (java.util.Map<UUID, NetworkPlayerInfo>) mapField.get(mc.getNetHandler());
            if (map == null) {
                return false;
            }
            map.put(uuid, info);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void removeDenickerSyntheticPlayerInfo(Minecraft mc) {
        if (!denickerInjectedSyntheticInfo || mc == null || mc.getNetHandler() == null
                || denickerSyntheticPlayerInfo == null) {
            denickerInjectedSyntheticInfo = false;
            denickerSyntheticPlayerInfo = null;
            denickerPreviewAppliedSkin = null;
            return;
        }
        try {
            java.lang.reflect.Field mapField = MinecraftPrivateAccess.findField(
                    net.minecraft.client.network.NetHandlerPlayClient.class, "playerInfoMap", "field_147310_i");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<UUID, NetworkPlayerInfo> map =
                    (java.util.Map<UUID, NetworkPlayerInfo>) mapField.get(mc.getNetHandler());
            if (map != null && map.get(Denicker.PREVIEW_SKIN_UUID) == denickerSyntheticPlayerInfo) {
                map.remove(Denicker.PREVIEW_SKIN_UUID);
            }
        } catch (Throwable ignored) {
        }
        denickerInjectedSyntheticInfo = false;
        denickerSyntheticPlayerInfo = null;
        denickerPreviewAppliedSkin = null;
    }

    private static void ensurePreviewCapeLayerEnabled(EntityOtherPlayerMP rider, Minecraft mc) {
        if (rider == null || mc == null || mc.thePlayer == null) {
            return;
        }
        try {
            byte parts = rider.getDataWatcher().getWatchableObjectByte(10);
            parts |= (byte) EnumPlayerModelParts.CAPE.getPartMask();
            rider.getDataWatcher().updateObject(10, Byte.valueOf(parts));
        } catch (Throwable ignored) {
        }
        try {
            rider.setInvisible(false);
            rider.setSneaking(false);
        } catch (Throwable ignored) {
        }
    }

    private static void disablePreviewCape(EntityOtherPlayerMP rider) {
        if (rider == null) {
            return;
        }
        try {
            byte parts = rider.getDataWatcher().getWatchableObjectByte(10);
            parts &= (byte) ~EnumPlayerModelParts.CAPE.getPartMask();
            rider.getDataWatcher().updateObject(10, Byte.valueOf(parts));
        } catch (Throwable ignored) {
        }
        try {
            rider.setInvisible(false);
            rider.setSneaking(false);
        } catch (Throwable ignored) {
        }
    }

    /** Denicker preview: no cape layer (avoids broken GUI cape render). */
    public static void renderDenickerPreviewPlayer(Minecraft mc, EntityOtherPlayerMP player, int posX, int posY,
                                                   int scale, float alpha) {
        if (mc == null || player == null || alpha < 0.04f) {
            return;
        }
        syncPreviewRiderModelParts(player, mc);
        disablePreviewCape(player);
        renderEntityPreview(mc, player, posX, posY, scale, alpha);
    }

    /** Player-only GUI preview with skin layers + cape (Denicker settings, etc.). */
    public static void renderStandalonePlayerPreview(Minecraft mc, EntityOtherPlayerMP player, int posX, int posY,
                                                     int scale, float alpha) {
        if (mc == null || player == null || alpha < 0.04f) {
            return;
        }
        syncPreviewRiderModelParts(player, mc);
        ensurePreviewCapeLayerEnabled(player, mc);
        renderEntityPreview(mc, player, posX, posY, scale, alpha);
    }

    public static void discardStandalonePreviewPlayer() {
        standalonePreviewPlayer = null;
        standalonePreviewCapeVisible = false;
    }

    private static EntityOtherPlayerMP standalonePreviewPlayer;

    /** Used by {@link me.napim.gomod.mixins.RenderPlayerMountPreviewMixin}. */
    public static boolean isGuiPreviewRider(Entity entity) {
        return entity != null && (LunarFitChatPreview.isPreview(entity) || entity == previewRider || entity == standalonePreviewPlayer || entity == capePreviewPlayer
                || entity == visibleSpecsPreviewPlayer);
    }

    public static boolean isMountedPreviewRider(Entity entity) {
        return entity != null && entity == previewRider;
    }

    public static boolean shouldSuppressGuiPreviewNametag(Entity entity) {
        return guiPreviewNametagSuppressDepth > 0 && isGuiPreviewRider(entity);
    }

    /** True while one of gomod's player/mount GUI previews is inside RenderManager. */
    public static boolean isRenderingGuiEntityPreview() {
        return guiPreviewNametagSuppressDepth > 0;
    }

    public static boolean shouldBypassWorldOpacityForGuiPreview(Entity entity) {
        return guiPreviewOpacityBypassDepth > 0 && isGuiPreviewRider(entity);
    }

    public static boolean shouldScaleGuiPreviewAlpha() {
        return guiPreviewAlphaDepth > 0 || guiPreviewSinglePassLayerDepth > 0;
    }

    public static float getGuiPreviewAlpha() {
        return Math.max(0.0F, Math.min(1.0F, guiPreviewAlpha));
    }

    public static int scaleGuiPreviewExternalVertexAlpha(int alpha) {
        if (!shouldScaleGuiPreviewAlpha()) {
            return alpha;
        }
        int clamped = Math.max(0, Math.min(255, alpha));
        return Math.max(0, Math.min(255, Math.round(clamped * getGuiPreviewAlpha())));
    }

    public static float scaleGuiPreviewExternalVertexAlpha(float alpha) {
        if (!shouldScaleGuiPreviewAlpha()) {
            return alpha;
        }
        float clamped = Math.max(0.0F, Math.min(1.0F, alpha));
        return clamped * getGuiPreviewAlpha();
    }

    private static void beginGuiPreviewAlpha(float alpha) {
        guiPreviewAlphaDepth++;
        guiPreviewAlpha = Math.max(0.0F, Math.min(1.0F, alpha));
    }

    private static void endGuiPreviewAlpha() {
        if (guiPreviewAlphaDepth <= 0) {
            guiPreviewAlphaDepth = 0;
            guiPreviewAlpha = 1.0F;
            return;
        }
        if (--guiPreviewAlphaDepth == 0) {
            guiPreviewAlpha = 1.0F;
        }
    }

    public static boolean shouldSkipGuiPreviewLayersInColor(EntityLivingBase entity) {
        return entity instanceof EntityOtherPlayerMP
                && entity == guiPreviewOpacityTarget && guiPreviewOpacityPhase == 2;
    }

    public static boolean beginGuiPreviewSinglePassLayerColor(EntityLivingBase entity) {
        if (!(entity instanceof EntityOtherPlayerMP)
                || entity != guiPreviewOpacityTarget || guiPreviewOpacityPhase != 1) {
            return false;
        }
        if (guiPreviewSinglePassLayerDepth++ == 0) {
            GlStateManager.colorMask(true, true, true, true);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.depthMask(true);
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.003921569F);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }
        return true;
    }

    public static void endGuiPreviewSinglePassLayerColor() {
        if (guiPreviewSinglePassLayerDepth <= 0) {
            guiPreviewSinglePassLayerDepth = 0;
            return;
        }
        if (--guiPreviewSinglePassLayerDepth == 0) {
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.disableBlend();
            GlStateManager.depthMask(true);
            GlStateManager.colorMask(false, false, false, false);
        }
    }

    public static boolean shouldKeepGuiPreviewBlendEnabled() {
        return guiPreviewSinglePassLayerDepth > 0 || guiPreviewOpacityPhase == 2;
    }

    public static boolean isGuiMountColorPass() {
        return guiPreviewOpacityPhase == 2 && isMountPreviewEntity(guiPreviewOpacityTarget);
    }

    public static boolean isGuiMountOpacityPass() {
        return guiPreviewOpacityPhase != 0 && isMountPreviewEntity(guiPreviewOpacityTarget);
    }

    public static float getGuiMountAlphaCutoff() {
        return (guiPreviewOpacityPhase == 2 ? getGuiPreviewAlpha() : 1.0F) / 255.0F;
    }

    private static boolean isMountPreviewEntity(Entity entity) {
        return entity instanceof EntityHorse || entity instanceof EntityPig
                || entity instanceof EntityBoat || entity instanceof EntityMinecartEmpty;
    }

    private static void beginGuiPreviewOpacityPhase(Entity entity, int phase, float alpha) {
        guiPreviewOpacityTarget = entity;
        guiPreviewOpacityPhase = phase;
        guiPreviewAlpha = Math.max(0.0F, Math.min(1.0F, alpha));
    }

    private static void endGuiPreviewOpacityPhase() {
        guiPreviewSinglePassLayerDepth = 0;
        guiPreviewOpacityPhase = 0;
        guiPreviewOpacityTarget = null;
    }

    /** Cape selector preview: current player skin with a temporary cape that is not committed until Apply. */
    public static EntityOtherPlayerMP createCapePreviewPlayer(World world, Minecraft mc, ResourceLocation cape) {
        return createCapePreviewPlayer(world, mc, cape, null);
    }

    /** Social receipt preview: named player's skin when available, with a temporary cape. */
    public static EntityOtherPlayerMP createCapePreviewPlayer(World world, Minecraft mc, ResourceLocation cape,
                                                              String playerName) {
        return createCapePreviewPlayer(world, mc, cape, playerName, null);
    }

    /** Player preview using a known Mojang UUID, so the skin can be loaded even when the player is not in TAB. */
    public static EntityOtherPlayerMP createCapePreviewPlayer(World world, Minecraft mc, ResourceLocation cape,
                                                              String playerName, UUID sourceUuid) {
        if (world == null || mc == null || mc.thePlayer == null) {
            return null;
        }
        discardCapePreviewPlayer(mc);
        String cleanName = playerName == null || playerName.trim().isEmpty() ? "CapePreview" : playerName.trim();
        GameProfile profile = new GameProfile(CAPE_PREVIEW_UUID, cleanName);
        registerCapePreviewPlayerInfo(mc, profile, cape, cleanName, sourceUuid);
        capePreviewSourceUuid = sourceUuid;
        capePreviewPlayer = new EntityOtherPlayerMP(world, profile);
        freezeCapePreviewPlayer(capePreviewPlayer);
        syncPreviewRiderModelParts(capePreviewPlayer, mc);
        if (getCapePreviewCapeLocation() != null) {
            ensurePreviewCapeLayerEnabled(capePreviewPlayer, mc);
        } else {
            disablePreviewCape(capePreviewPlayer);
        }
        return capePreviewPlayer;
    }

    public static void updateCapePreviewPlayer(Minecraft mc, EntityOtherPlayerMP player, ResourceLocation cape) {
        updateCapePreviewPlayer(mc, player, cape, null);
    }

    public static void updateCapePreviewPlayer(Minecraft mc, EntityOtherPlayerMP player, ResourceLocation cape,
                                               String playerName) {
        updateCapePreviewPlayer(mc, player, cape, playerName, capePreviewSourceUuid);
    }

    public static void updateCapePreviewPlayer(Minecraft mc, EntityOtherPlayerMP player, ResourceLocation cape,
                                               String playerName, UUID sourceUuid) {
        if (mc == null || player == null) {
            return;
        }
        String cleanName = playerName == null || playerName.trim().isEmpty() ? "" : playerName.trim();
        if (!capePreviewInjectedSyntheticInfo || capePreviewSyntheticInfo == null) {
            registerCapePreviewPlayerInfo(mc, player.getGameProfile(), cape, cleanName, sourceUuid);
        } else if (capePreviewSyntheticInfo instanceof NetworkPlayerInfoAccessor) {
            NetworkPlayerInfoAccessor accessor = (NetworkPlayerInfoAccessor) capePreviewSyntheticInfo;
            accessor.gomod$setPlayerTexturesLoaded(true);
            accessor.gomod$setLocationSkin(resolvePreviewSkin(mc, cleanName, sourceUuid));
            accessor.gomod$setSkinType(resolvePreviewSkinType(mc, cleanName, sourceUuid));
            ResourceLocation resolvedCape = resolvePreviewCape(mc, cleanName, sourceUuid, cape);
            accessor.gomod$setLocationCape(resolvedCape);
        }
        capePreviewSourceUuid = sourceUuid;
        freezeCapePreviewPlayer(player);
        if (getCapePreviewCapeLocation() != null) {
            ensurePreviewCapeLayerEnabled(player, mc);
        } else {
            disablePreviewCape(player);
        }
    }

    public static void renderCapePreviewPlayer(Minecraft mc, EntityOtherPlayerMP player, int posX, int posY,
                                               int scale, float alpha) {
        renderCapePreviewPlayer(mc, player, posX, posY, scale, alpha, 155.0F);
    }

    public static void renderCapePreviewPlayer(Minecraft mc, EntityOtherPlayerMP player, int posX, int posY,
                                               int scale, float alpha, float guiYawDegrees) {
        renderCapePreviewPlayer(mc, player, posX, posY, scale, alpha, guiYawDegrees, 18.0F, 1.0F);
    }

    /** Cape editor preview: opaque cape first, then the translucent skin over it. */
    public static void renderCapePreviewPlayer(Minecraft mc, EntityOtherPlayerMP player, int posX, int posY,
                                               int scale, float alpha, float guiYawDegrees, float guiPitchDegrees,
                                               float playerBodyAlpha) {
        if (mc == null || player == null || alpha < 0.04f) {
            return;
        }
        freezeCapePreviewPlayer(player);
        syncPreviewRiderModelParts(player, mc);
        float bodyA = Math.max(0.05F, Math.min(1.0F, playerBodyAlpha));
        if (getCapePreviewCapeLocation() != null) {
            ensurePreviewCapeLayerEnabled(player, mc);
        } else {
            disablePreviewCape(player);
        }
        capeEditorRenderTarget = player;
        capeEditorBodyAlpha = bodyA;
        try {
            if (bodyA >= 0.99F) {
                renderEntityPreview(mc, player, posX, posY, scale, bodyA, guiYawDegrees, guiPitchDegrees, false);
                return;
            }
            // The cape must establish depth first. The faded body can then reveal cape pixels
            // behind it while still occluding correctly wherever the cape is in front.
            capeEditorCapeOnlyPass = true;
            capeEditorBodyAlpha = 1.0F;
            try {
                renderEntityPreview(mc, player, posX, posY, scale, 1.0F, guiYawDegrees, guiPitchDegrees, false);
            } finally {
                capeEditorCapeOnlyPass = false;
                capeEditorBodyAlpha = bodyA;
            }

            // Draw only the skin with alpha. Depth writes remain enabled so its own faces sort
            // consistently; the low alpha test rejects fully transparent skin texels.
            capeEditorSuppressCapeLayer = true;
            capeEditorBodyOpacityPass = true;
            GlStateManager.enableDepth();
            GlStateManager.depthMask(true);
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(GL11.GL_GREATER, Math.max(0.0001F, bodyA / 255.0F));
            try {
                renderEntityPreview(mc, player, posX, posY, scale, 1.0F, guiYawDegrees, guiPitchDegrees, false);
            } finally {
                capeEditorBodyOpacityPass = false;
                capeEditorSuppressCapeLayer = false;
                GlStateManager.disableBlend();
                GL11.glAlphaFunc(GL11.GL_GREATER, 0.1F);
                GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            }
        } finally {
            capeEditorSuppressCapeLayer = false;
            capeEditorCapeOnlyPass = false;
            capeEditorBodyOpacityPass = false;
            capeEditorRenderTarget = null;
            capeEditorBodyAlpha = 1.0F;
        }
    }

    public static float getCapeEditorBodyAlpha() {
        return capeEditorBodyAlpha;
    }

    public static boolean isCapeEditorBodyOpacityPass() {
        return capeEditorBodyOpacityPass && capeEditorRenderTarget != null;
    }

    /**
     * Same two-pass sequence as {@link me.napim.gomod.features.SelfOpacity#flushDeferred}:
     * depth prepass then color pass with depth test (no seeing through the model shell).
     */
    private static void renderDepthOpacityEntity(RenderManager rm, Entity entity, float drawAlpha,
                                                 boolean capeEditorPreview) {
        boolean mountPreview = isMountPreviewEntity(entity);
        boolean savedCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean savedAlphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        if (capeEditorPreview) {
            GL11.glEnable(GL11.GL_CULL_FACE);
        }
        try {
            if (mountPreview) prepareMountPreviewLighting();
            GlStateManager.disableBlend();
            GlStateManager.enableDepth();
            GlStateManager.enableAlpha();
            GlStateManager.colorMask(false, false, false, false);
            GlStateManager.depthMask(true);
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            if (mountPreview) {
                GL11.glColorMask(false, false, false, false);
                GL11.glDepthMask(true);
                GL11.glAlphaFunc(GL11.GL_GREATER, 1.0F / 255.0F);
            }
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            beginGuiPreviewOpacityPhase(entity, 1, drawAlpha);
            try {
                rm.renderEntityWithPosYaw(entity, 0.0D, 0.0D, 0.0D, 0.0F, 1.0F);
            } finally {
                endGuiPreviewOpacityPhase();
            }

            if (mountPreview) prepareMountPreviewLighting();
            GlStateManager.colorMask(true, true, true, true);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.depthMask(false);
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GlStateManager.alphaFunc(GL11.GL_GREATER, mountPreview ? drawAlpha / 255.0F : 1.0F / 255.0F);
            if (mountPreview) {
                GL11.glColorMask(true, true, true, true);
                GL11.glDepthMask(false);
                GL11.glAlphaFunc(GL11.GL_GREATER, drawAlpha / 255.0F);
            }
            GlStateManager.color(1.0F, 1.0F, 1.0F, drawAlpha);
            beginGuiPreviewOpacityPhase(entity, 2, drawAlpha);
            beginGuiPreviewAlpha(drawAlpha);
            try {
                rm.renderEntityWithPosYaw(entity, 0.0D, 0.0D, 0.0D, 0.0F, 1.0F);
            } finally {
                endGuiPreviewAlpha();
                endGuiPreviewOpacityPhase();
            }
        } finally {
            if (savedCull) {
                GL11.glEnable(GL11.GL_CULL_FACE);
            } else {
                GL11.glDisable(GL11.GL_CULL_FACE);
            }
            if (savedAlphaTest) {
                GL11.glEnable(GL11.GL_ALPHA_TEST);
                GlStateManager.enableAlpha();
                GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
            } else {
                GL11.glDisable(GL11.GL_ALPHA_TEST);
                GlStateManager.disableAlpha();
            }
        }
    }

    public static boolean isCapeEditorPreviewEntity(Entity entity) {
        return capeEditorRenderTarget != null && entity == capeEditorRenderTarget;
    }

    public static boolean shouldSuppressCapeLayer(Entity entity) {
        return capeEditorSuppressCapeLayer && isCapeEditorPreviewEntity(entity);
    }

    public static ResourceLocation getForcedCapePreviewCape(Entity entity) {
        if (entity == null || entity != capePreviewPlayer) {
            return null;
        }
        return getCapePreviewCapeLocation();
    }

    public static boolean isCapeEditorCapeOnlyPass() {
        return capeEditorCapeOnlyPass;
    }

    public static void discardCapePreviewPlayer(Minecraft mc) {
        capePreviewPlayer = null;
        capePreviewSourceUuid = null;
        if (!capePreviewInjectedSyntheticInfo || mc == null || mc.getNetHandler() == null
                || capePreviewSyntheticInfo == null) {
            capePreviewInjectedSyntheticInfo = false;
            capePreviewSyntheticInfo = null;
            return;
        }
        try {
            java.lang.reflect.Field mapField = MinecraftPrivateAccess.findField(
                    net.minecraft.client.network.NetHandlerPlayClient.class, "playerInfoMap", "field_147310_i");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<UUID, NetworkPlayerInfo> map =
                    (java.util.Map<UUID, NetworkPlayerInfo>) mapField.get(mc.getNetHandler());
            if (map != null && map.get(CAPE_PREVIEW_UUID) == capePreviewSyntheticInfo) {
                map.remove(CAPE_PREVIEW_UUID);
            }
        } catch (Throwable ignored) {
        }
        capePreviewInjectedSyntheticInfo = false;
        capePreviewSyntheticInfo = null;
    }

    private static boolean isCapePreviewSyntheticPlayerInfo(NetworkPlayerInfo info) {
        return capePreviewInjectedSyntheticInfo && info != null && info == capePreviewSyntheticInfo;
    }

    private static void registerCapePreviewPlayerInfo(Minecraft mc, GameProfile profile, ResourceLocation cape) {
        registerCapePreviewPlayerInfo(mc, profile, cape, null);
    }

    private static void registerCapePreviewPlayerInfo(Minecraft mc, GameProfile profile, ResourceLocation cape,
                                                      String playerName) {
        registerCapePreviewPlayerInfo(mc, profile, cape, playerName, null);
    }

    private static void registerCapePreviewPlayerInfo(Minecraft mc, GameProfile profile, ResourceLocation cape,
                                                      String playerName, UUID sourceUuid) {
        if (mc == null || mc.getNetHandler() == null || profile == null || profile.getId() == null) {
            return;
        }
        NetworkPlayerInfo info = new NetworkPlayerInfo(profile);
        if (info instanceof NetworkPlayerInfoAccessor) {
            NetworkPlayerInfoAccessor accessor = (NetworkPlayerInfoAccessor) info;
            accessor.gomod$setPlayerTexturesLoaded(true);
            accessor.gomod$setLocationSkin(resolvePreviewSkin(mc, playerName, sourceUuid));
            accessor.gomod$setSkinType(resolvePreviewSkinType(mc, playerName, sourceUuid));
            accessor.gomod$setLocationCape(resolvePreviewCape(mc, playerName, sourceUuid, cape));
        }
        if (putPlayerInfoMapEntry(mc, profile.getId(), info)) {
            capePreviewSyntheticInfo = info;
            capePreviewInjectedSyntheticInfo = true;
        }
    }

    private static ResourceLocation resolveLocalPreviewSkin(Minecraft mc) {
        try {
            if (mc != null && mc.thePlayer != null && mc.thePlayer.getLocationSkin() != null) {
                return mc.thePlayer.getLocationSkin();
            }
        } catch (Throwable ignored) {
        }
        return DefaultPlayerSkin.getDefaultSkin(STEVE_FALLBACK_UUID);
    }

    private static String resolveLocalPreviewSkinType(Minecraft mc) {
        try {
            if (mc != null && mc.thePlayer != null && mc.thePlayer.getSkinType() != null) {
                return mc.thePlayer.getSkinType();
            }
        } catch (Throwable ignored) {
        }
        return "default";
    }

    private static ResourceLocation resolvePreviewSkin(Minecraft mc, String playerName) {
        return resolvePreviewSkin(mc, playerName, null);
    }

    static ResourceLocation resolvePreviewSkin(Minecraft mc, String playerName, UUID sourceUuid) {
        NetworkPlayerInfo info = findPlayerInfoByName(mc, playerName);
        if (info != null && info.getLocationSkin() != null) {
            return info.getLocationSkin();
        }
        if (sourceUuid != null) {
            DenickerRealSkinCache.ensurePrefetchMojangUuid(sourceUuid, playerName);
            return DenickerRealSkinCache.resolveSkinForMojangUuid(sourceUuid, playerName);
        }
        if (playerName != null && !playerName.trim().isEmpty()) {
            UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName.trim()).getBytes(StandardCharsets.UTF_8));
            return DefaultPlayerSkin.getDefaultSkin(uuid);
        }
        return resolveLocalPreviewSkin(mc);
    }

    private static String resolvePreviewSkinType(Minecraft mc, String playerName) {
        return resolvePreviewSkinType(mc, playerName, null);
    }

    static String resolvePreviewSkinType(Minecraft mc, String playerName, UUID sourceUuid) {
        NetworkPlayerInfo info = findPlayerInfoByName(mc, playerName);
        if (info != null && info.getSkinType() != null) {
            return info.getSkinType();
        }
        if (sourceUuid != null) {
            DenickerRealSkinCache.ensurePrefetchMojangUuid(sourceUuid, playerName);
            return DenickerRealSkinCache.resolveSkinTypeForMojangUuid(sourceUuid, playerName);
        }
        if (playerName != null && !playerName.trim().isEmpty()) {
            return "default";
        }
        return resolveLocalPreviewSkinType(mc);
    }

    private static NetworkPlayerInfo findPlayerInfoByName(Minecraft mc, String playerName) {
        String wanted = playerName == null ? "" : playerName.trim();
        if (wanted.isEmpty() || mc == null || mc.getNetHandler() == null
                || mc.getNetHandler().getPlayerInfoMap() == null) {
            return null;
        }
        for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
            if (info != null && info.getGameProfile() != null
                    && !isCapePreviewSyntheticPlayerInfo(info)
                    && wanted.equalsIgnoreCase(info.getGameProfile().getName())) {
                return info;
            }
        }
        return null;
    }

    private static ResourceLocation findCapeByName(Minecraft mc, String playerName) {
        NetworkPlayerInfo info = findPlayerInfoByName(mc, playerName);
        return info == null ? null : info.getLocationCape();
    }

    private static ResourceLocation resolvePreviewCape(Minecraft mc, String playerName, UUID sourceUuid,
                                                       ResourceLocation requestedCape) {
        if (requestedCape != null) {
            return requestedCape;
        }
        ResourceLocation currentCape = findCapeByName(mc, playerName);
        if (currentCape != null) {
            return currentCape;
        }
        return sourceUuid == null ? null
                : DenickerRealSkinCache.resolveCapeForMojangUuid(sourceUuid, playerName);
    }

    private static ResourceLocation getCapePreviewCapeLocation() {
        if (!(capePreviewSyntheticInfo instanceof NetworkPlayerInfoAccessor)) {
            return null;
        }
        try {
            return capePreviewSyntheticInfo.getLocationCape();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void freezeCapePreviewPlayer(EntityOtherPlayerMP player) {
        if (player == null) {
            return;
        }
        try {
            player.setPosition(0.0D, 0.0D, 0.0D);
            player.prevPosX = player.posX;
            player.prevPosY = player.posY;
            player.prevPosZ = player.posZ;
            player.lastTickPosX = player.posX;
            player.lastTickPosY = player.posY;
            player.lastTickPosZ = player.posZ;
            player.motionX = 0.0D;
            player.motionY = 0.0D;
            player.motionZ = 0.0D;
            player.onGround = true;
            player.isAirBorne = false;
            player.fallDistance = 0.0F;
            player.setSneaking(false);
            player.setSprinting(false);
            player.rotationYaw = 0.0F;
            player.prevRotationYaw = 0.0F;
            player.rotationPitch = 0.0F;
            player.prevRotationPitch = 0.0F;
            player.renderYawOffset = 0.0F;
            player.prevRenderYawOffset = 0.0F;
            player.rotationYawHead = 0.0F;
            player.prevRotationYawHead = 0.0F;
            player.cameraYaw = 0.0F;
            player.prevCameraYaw = 0.0F;
            player.distanceWalkedModified = 0.0F;
            player.prevDistanceWalkedModified = 0.0F;
            player.limbSwing = 0.0F;
            player.limbSwingAmount = 0.0F;
            player.prevLimbSwingAmount = 0.0F;
        } catch (Throwable ignored) {
        }
        syncCapePhysicsFields(player);
    }

    private static void syncCapePhysicsFields(EntityOtherPlayerMP player) {
        setDoubleFieldIfPresent(player, player.posX, "chasingPosX", "capeX", "field_71091_bM");
        setDoubleFieldIfPresent(player, player.posY, "chasingPosY", "capeY", "field_71096_bN");
        setDoubleFieldIfPresent(player, player.posZ, "chasingPosZ", "capeZ", "field_71097_bO");
        setDoubleFieldIfPresent(player, player.posX, "prevChasingPosX", "prevCapeX", "field_71094_bP");
        setDoubleFieldIfPresent(player, player.posY, "prevChasingPosY", "prevCapeY", "field_71095_bQ");
        setDoubleFieldIfPresent(player, player.posZ, "prevChasingPosZ", "prevCapeZ", "field_71085_bR");
    }

    private static void setDoubleFieldIfPresent(Object target, double value, String... names) {
        if (target == null || names == null) {
            return;
        }
        for (String name : names) {
            if (name == null || name.isEmpty()) {
                continue;
            }
            try {
                java.lang.reflect.Field field = findFieldInHierarchy(target.getClass(), name);
                if (field == null) {
                    continue;
                }
                field.setAccessible(true);
                field.setDouble(target, value);
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    private static java.lang.reflect.Field findFieldInHierarchy(Class<?> type, String name) {
        for (Class<?> cls = type; cls != null; cls = cls.getSuperclass()) {
            try {
                return cls.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static EntityOtherPlayerMP getOrCreatePreviewRider(World w, Minecraft mc) {
        GameProfile real = mc.thePlayer.getGameProfile();
        GameProfile use = pickPreviewProfile(mc, real);
        if (previewRider == null || previewRider.worldObj != w || !previewProfileMatches(previewRider, use)) {
            previewRider = new EntityOtherPlayerMP(w, use);
            syncPreviewRiderModelParts(previewRider, mc);
        }
        return previewRider;
    }

    /**
     * Copy skin overlay flags from the local player so {@link RenderPlayer#setModelVisibilities}
     * enables hat/jacket/etc. on the client-only preview entity.
     */
    private static void syncPreviewRiderModelParts(EntityOtherPlayerMP rider, Minecraft mc) {
        if (rider == null || mc == null || mc.thePlayer == null) {
            return;
        }
        try {
            byte parts = mc.thePlayer.getDataWatcher().getWatchableObjectByte(10);
            rider.getDataWatcher().updateObject(10, Byte.valueOf(parts));
        } catch (Throwable ignored) {
            byte all = 0;
            for (EnumPlayerModelParts part : EnumPlayerModelParts.values()) {
                all |= (byte) part.getPartMask();
            }
            try {
                rider.getDataWatcher().updateObject(10, Byte.valueOf(all));
            } catch (Throwable ignored2) {
            }
        }
    }

    private static GameProfile pickPreviewProfile(Minecraft mc, GameProfile session) {
        if (session == null) {
            return new GameProfile(STEVE_FALLBACK_UUID, "Steve");
        }
        if (shouldUseSteveSkinFallback(mc, session)) {
            return new GameProfile(STEVE_FALLBACK_UUID, "Steve");
        }
        return session;
    }

    private static boolean previewProfileMatches(EntityOtherPlayerMP rider, GameProfile want) {
        if (rider == null || want == null) {
            return false;
        }
        GameProfile g = rider.getGameProfile();
        return Objects.equals(g.getId(), want.getId()) && Objects.equals(g.getName(), want.getName());
    }

    private static boolean shouldUseSteveSkinFallback(Minecraft mc, GameProfile profile) {
        if (mc == null || profile.getId() == null) {
            return true;
        }
        if (mc.getNetHandler() == null) {
            return true;
        }
        NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(profile.getId());
        if (info == null) {
            return true;
        }
        try {
            return !info.hasLocationSkin();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static ModelBase getMainModel(RendererLivingEntity renderer) {
        try {
            java.lang.reflect.Field f = MinecraftPrivateAccess.findField(RendererLivingEntity.class, "mainModel", "field_77045_g");
            return (ModelBase) f.get(renderer);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * After {@link RenderPlayer#setModelVisibilities} runs for the GUI preview rider, force all outer
     * skin model pieces visible (preview entity has no local-player {@code GameSettings} path).
     */
    public static void forceSkinOverlayLayersVisible(RenderPlayer renderer) {
        if (renderer == null) {
            return;
        }
        ModelBase base = getMainModel(renderer);
        if (!(base instanceof ModelBiped)) {
            return;
        }
        ModelBiped biped = (ModelBiped) base;
        if (biped.bipedHeadwear != null) {
            biped.bipedHeadwear.showModel = true;
        }
        if (base instanceof ModelPlayer) {
            ModelPlayer mp = (ModelPlayer) base;
            setWearVisible(mp.bipedBodyWear);
            setWearVisible(mp.bipedLeftArmwear);
            setWearVisible(mp.bipedRightArmwear);
            setWearVisible(mp.bipedLeftLegwear);
            setWearVisible(mp.bipedRightLegwear);
        }
    }

    private static void setWearVisible(ModelRenderer part) {
        if (part != null) {
            part.showModel = true;
        }
    }

    /**
     * Called from {@link me.napim.gomod.mixins.RenderPlayerMountPreviewMixin} after
     * {@code setModelVisibilities} so the body stays hidden for the cape-only opacity pass.
     */
    public static void applyCapeEditorCapeOnlyModelVisibility(RenderPlayer renderer) {
        if (renderer == null) {
            return;
        }
        try {
            ModelBase base = getMainModel(renderer);
            if (!(base instanceof ModelPlayer)) {
                return;
            }
            ModelPlayer mp = (ModelPlayer) base;
            setPartVisible(mp.bipedHead, false);
            setPartVisible(mp.bipedHeadwear, false);
            setPartVisible(mp.bipedBody, false);
            setPartVisible(mp.bipedRightArm, false);
            setPartVisible(mp.bipedLeftArm, false);
            setPartVisible(mp.bipedRightLeg, false);
            setPartVisible(mp.bipedLeftLeg, false);
            setPartVisible(mp.bipedBodyWear, false);
            setPartVisible(mp.bipedLeftArmwear, false);
            setPartVisible(mp.bipedRightArmwear, false);
            setPartVisible(mp.bipedLeftLegwear, false);
            setPartVisible(mp.bipedRightLegwear, false);
        } catch (Throwable ignored) {
        }
    }

    private static void setPartVisible(ModelRenderer part, boolean visible) {
        if (part != null) {
            part.showModel = visible;
        }
    }

    private static ResourceLocation stashCapePreviewCapeTexture() {
        ResourceLocation previous = getCapePreviewCapeLocation();
        if (capePreviewSyntheticInfo instanceof NetworkPlayerInfoAccessor) {
            ((NetworkPlayerInfoAccessor) capePreviewSyntheticInfo).gomod$setLocationCape(null);
        }
        return previous;
    }

    private static void restoreCapePreviewCapeTexture(ResourceLocation previous) {
        if (!(capePreviewSyntheticInfo instanceof NetworkPlayerInfoAccessor)) {
            return;
        }
        ((NetworkPlayerInfoAccessor) capePreviewSyntheticInfo).gomod$setLocationCape(previous);
    }

    public static void positionRiderOnMountForGui(Entity mount, Entity rider) {
        if (mount == null || rider == null) {
            return;
        }
        try {
            rider.setPosition(
                    mount.posX,
                    mount.posY + mount.getMountedYOffset() + rider.getYOffset(),
                    mount.posZ);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Renders one mount/boat/cart centered at screen (posX, posY), Z=50 GUI depth.
     */
    public static void renderEntityPreview(Minecraft mc, Entity entity, int posX, int posY, int scale, float alpha) {
        renderEntityPreview(mc, entity, posX, posY, scale, alpha, 45.0F, false);
    }

    public static void renderEntityPreview(Minecraft mc, Entity entity, int posX, int posY, int scale, float alpha,
                                           float guiYawDegrees) {
        renderEntityPreview(mc, entity, posX, posY, scale, alpha, guiYawDegrees, 18.0F, false);
    }

    public static void renderEntityPreview(Minecraft mc, Entity entity, int posX, int posY, int scale, float alpha,
                                           float guiYawDegrees, boolean depthOpacityPass) {
        renderEntityPreview(mc, entity, posX, posY, scale, alpha, guiYawDegrees, 18.0F, depthOpacityPass);
    }

    public static void renderEntityPreview(Minecraft mc, Entity entity, int posX, int posY, int scale, float alpha,
                                           float guiYawDegrees, float guiPitchDegrees, boolean depthOpacityPass) {
        if (mc == null || entity == null || alpha < 0.04f) {
            return;
        }
        boolean matrixPushed = false;
        RenderManager previewRenderManager = null;
        float savedPlayerViewY = 0.0F;
        try {
            GlStateManager.enableColorMaterial();
            GlStateManager.pushMatrix();
            matrixPushed = true;
            GlStateManager.translate(posX, posY, 50.0F);
            GlStateManager.scale(-scale, scale, scale);
            GlStateManager.rotate(180.0F, 0.0F, 0.0F, 1.0F);

            float yaw = entity.rotationYaw;
            float savedPitch = entity.rotationPitch;
            boolean keepArrowOrientation = entity instanceof EntityArrow;
            entity.rotationYaw = keepArrowOrientation ? yaw : 0.0F;
            entity.rotationPitch = keepArrowOrientation ? savedPitch : 0.0F;
            if (entity instanceof net.minecraft.entity.EntityLivingBase) {
                net.minecraft.entity.EntityLivingBase liv = (net.minecraft.entity.EntityLivingBase) entity;
                liv.renderYawOffset = 0.0F;
                liv.rotationYawHead = 0.0F;
                liv.prevRotationYawHead = 0.0F;
            }

            GlStateManager.rotate(135.0F, 0.0F, 1.0F, 0.0F);
            RenderHelper.enableStandardItemLighting();
            GlStateManager.rotate(-135.0F, 0.0F, 1.0F, 0.0F);
            if (entity == capePreviewPlayer) {
                // The profile preview supports a top-down orbit when the user drags down.
                // Keep a little room below vertical so the model never turns upside down.
                float orbitPitch = Math.max(-75.0F, Math.min(75.0F, guiPitchDegrees));
                GlStateManager.rotate(18.0F, 1.0F, 0.0F, 0.0F);
                GlStateManager.translate(0.0F, 1.05F, 0.0F);
                GlStateManager.rotate(-orbitPitch, 1.0F, 0.0F, 0.0F);
                GlStateManager.rotate(guiYawDegrees, 0.0F, 1.0F, 0.0F);
                GlStateManager.translate(0.0F, -1.05F, 0.0F);
            } else {
                float viewPitch;
                if (entity instanceof EntityOtherPlayerMP && isGuiPreviewRider(entity)
                        && entity.riddenByEntity == null) {
                    viewPitch = 12.0F;
                } else {
                    viewPitch = 20.0F;
                }
                GlStateManager.rotate(viewPitch, 1.0F, 0.0F, 0.0F);
                GlStateManager.rotate(guiYawDegrees, 0.0F, 1.0F, 0.0F);
            }
            RenderManager rm = mc.getRenderManager();
            previewRenderManager = rm;
            savedPlayerViewY = rm.playerViewY;
            rm.setPlayerViewY(180.0F);
            rm.setRenderShadow(false);
            boolean oldDebug = rm.isDebugBoundingBox();
            rm.setDebugBoundingBox(false);

            GlStateManager.enableLighting();
            GlStateManager.enableLight(0);
            GlStateManager.enableLight(1);
            GlStateManager.enableColorMaterial();
            GlStateManager.colorMaterial(1032, 5634);

            if (entity instanceof EntityOtherPlayerMP && isGuiPreviewRider(entity)) {
                EntityOtherPlayerMP rider = (EntityOtherPlayerMP) entity;
                syncPreviewRiderModelParts(rider, mc);
                applyPreviewCapeVisibility(rider, mc);
            }

            boolean capeOnlyPass = isCapeEditorCapeOnlyPass() && isCapeEditorPreviewEntity(entity);
            boolean mountPreview = isMountPreviewEntity(entity);
            boolean savedFog = mountPreview && GL11.glIsEnabled(GL11.GL_FOG);
            boolean savedCullFace = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            boolean savedDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            boolean savedAlphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
            boolean savedBlend = GL11.glIsEnabled(GL11.GL_BLEND);
            boolean savedDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            int savedDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
            int savedAlphaFunc = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
            float savedAlphaRef = GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF);
            int savedBlendSrc = GL11.glGetInteger(GL11.GL_BLEND_SRC);
            int savedBlendDst = GL11.glGetInteger(GL11.GL_BLEND_DST);
            float drawAlpha = capeOnlyPass ? 1.0F : Math.min(1.0F, Math.max(0.05F, alpha));
            if (capeOnlyPass) {
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glEnable(GL11.GL_ALPHA_TEST);
                GL11.glAlphaFunc(GL11.GL_GREATER, 0.003921569F);
                GlStateManager.enableDepth();
                GlStateManager.depthMask(true);
                GlStateManager.depthFunc(GL11.GL_LEQUAL);
                GlStateManager.enableBlend();
                GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
                GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            }
            guiPreviewNametagSuppressDepth++;
            guiPreviewOpacityBypassDepth++;
            try {
                if (mountPreview) prepareMountPreviewLighting();
                renderPreviewRider(mc, rm, entity);
                if (depthOpacityPass) {
                    renderDepthOpacityEntity(rm, entity, drawAlpha, isCapeEditorPreviewEntity(entity));
                } else {
                    if (drawAlpha >= 0.99F) {
                        if (mountPreview) prepareMountPreviewLighting();
                        GlStateManager.disableBlend();
                        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
                        rm.renderEntityWithPosYaw(entity, 0.0D, 0.0D, 0.0D, 0.0F, 1.0F);
                    } else {
                        GlStateManager.enableBlend();
                        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
                        GlStateManager.color(1.0F, 1.0F, 1.0F, drawAlpha);
                        beginGuiPreviewAlpha(drawAlpha);
                        try {
                            rm.renderEntityWithPosYaw(entity, 0.0D, 0.0D, 0.0D, 0.0F, 1.0F);
                        } finally {
                            endGuiPreviewAlpha();
                        }
                    }
                }
            } finally {
                guiPreviewNametagSuppressDepth = Math.max(0, guiPreviewNametagSuppressDepth - 1);
                guiPreviewOpacityBypassDepth = Math.max(0, guiPreviewOpacityBypassDepth - 1);
                if (mountPreview) {
                    if (savedFog) GL11.glEnable(GL11.GL_FOG); else GL11.glDisable(GL11.GL_FOG);
                }
                if (savedCullFace) {
                    GL11.glEnable(GL11.GL_CULL_FACE);
                } else {
                    GL11.glDisable(GL11.GL_CULL_FACE);
                }
                if (savedAlphaTest) {
                    GL11.glEnable(GL11.GL_ALPHA_TEST);
                    GL11.glAlphaFunc(savedAlphaFunc, savedAlphaRef);
                } else {
                    GL11.glDisable(GL11.GL_ALPHA_TEST);
                }
                if (savedDepthTest) {
                    GL11.glEnable(GL11.GL_DEPTH_TEST);
                } else {
                    GL11.glDisable(GL11.GL_DEPTH_TEST);
                }
                GL11.glDepthFunc(savedDepthFunc);
                GL11.glDepthMask(savedDepthMask);
                if (savedBlend) {
                    GL11.glEnable(GL11.GL_BLEND);
                } else {
                    GL11.glDisable(GL11.GL_BLEND);
                }
                GL11.glBlendFunc(savedBlendSrc, savedBlendDst);
                GlStateManager.colorMask(true, true, true, true);
                GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            }

            rm.setDebugBoundingBox(oldDebug);
            rm.setRenderShadow(true);

            entity.rotationYaw = yaw;
            entity.rotationPitch = savedPitch;

            GlStateManager.disableBlend();
            GlStateManager.colorMask(true, true, true, true);
            GlStateManager.depthMask(true);
            GlStateManager.enableDepth();
            GlStateManager.depthFunc(GL11.GL_LEQUAL);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.popMatrix();
            matrixPushed = false;
            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableRescaleNormal();
            GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GlStateManager.disableTexture2D();
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            rm.setPlayerViewY(savedPlayerViewY);
        } catch (Throwable ignored) {
            if (matrixPushed) {
                try {
                    GlStateManager.popMatrix();
                } catch (Throwable ignoredPop) {
                }
            }
            if (previewRenderManager != null) {
                try {
                    previewRenderManager.setPlayerViewY(savedPlayerViewY);
                    previewRenderManager.setRenderShadow(true);
                } catch (Throwable ignoredRestore) {
                }
            }
            me.napim.gomod.util.HudGlState.restoreForOverlayText();
        }
    }

    private static void renderPreviewRider(Minecraft mc, RenderManager rm, Entity mount) {
        if (!(mount.riddenByEntity instanceof EntityOtherPlayerMP) || mount.riddenByEntity != previewRider) {
            return;
        }
        EntityOtherPlayerMP rider = (EntityOtherPlayerMP) mount.riddenByEntity;
        syncPreviewRiderModelParts(rider, mc);
        GlStateManager.enableDepth();
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        GlStateManager.depthMask(true);
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        rm.renderEntityWithPosYaw(rider, rider.posX - mount.posX,
                rider.posY - mount.posY, rider.posZ - mount.posZ, 0.0F, 1.0F);
    }

    /** Use the GUI lights for each draw, even if Lunar's rider or saddle changed the lightmap or lighting. */
    private static void prepareMountPreviewLighting() {
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        try {
            GL11.glLoadIdentity();
            RenderHelper.enableGUIStandardItemLighting();
        } finally {
            GlStateManager.popMatrix();
        }
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_LIGHT0);
        GL11.glEnable(GL11.GL_LIGHT1);
        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
        GL11.glDisable(GL11.GL_FOG);
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.disableTexture2D();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.enableTexture2D();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GlStateManager.enableAlpha();
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1F);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDepthMask(true);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void applyPreviewCapeVisibility(EntityOtherPlayerMP player, Minecraft mc) {
        if (player == null) {
            return;
        }
        if (player == capePreviewPlayer) {
            if (getCapePreviewCapeLocation() != null) {
                ensurePreviewCapeLayerEnabled(player, mc);
            } else {
                disablePreviewCape(player);
            }
            return;
        }
        if (player == standalonePreviewPlayer) {
            if (standalonePreviewCapeVisible) {
                ensurePreviewCapeLayerEnabled(player, mc);
            } else {
                disablePreviewCape(player);
            }
            return;
        }
        if (player == visibleSpecsPreviewPlayer) {
            disablePreviewCape(player);
        }
    }
}
