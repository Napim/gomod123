package me.napim.gomod.features;

import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.mixins.RenderManagerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.monster.EntitySkeleton;
import net.minecraft.entity.monster.EntitySpider;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.Map;

/**
 * Third-person mob model for the local player. Rendered after the world pass (Forge {@link net.minecraftforge.client.event.RenderWorldLastEvent}).
 */
public final class LocalMobDisguise {

    public enum Kind {
        OFF,
        ZOMBIE,
        SKELETON,
        CREEPER,
        COW,
        PIG,
        SPIDER
    }

    /** Exposed for mixins (mob proxy light + nametag). Render thread only. */
    public static EntityLivingBase getProxyEntity() {
        return proxy;
    }

    private static Kind presetKind = Kind.OFF;
    /** When non-null, use {@link EntityList#createEntityByName(String, World)} instead of {@link #presetKind}. */
    private static String customEntityId = null;
    /** Last mob id/name entered/used by the player (used as input box default). */
    private static String lastUsedEntityId = "Zombie";
    private static EntityLivingBase proxy;
    private static World proxyWorld;
    /** Cache key: preset name or "c:"+customId */
    private static String proxySpecKey = "";

    private LocalMobDisguise() {
    }

    public static void loadFromConfig() {
        if (!GomodInternalFeatures.isEnabled()) {
            clearCustomAndSetPreset(Kind.OFF);
            return;
        }
        parseStoredVisualSpec(ConfigManager.getDisguiseMobVisualKindName());
        String savedLast = sanitizeEntityId(ConfigManager.getDisguiseMobVisualLastUsedName());
        if (!savedLast.isEmpty() && !"OFF".equalsIgnoreCase(savedLast)) {
            lastUsedEntityId = savedLast;
        }
        disposeProxy();
    }

    public static Kind getPresetKind() {
        return presetKind;
    }

    public static String getCustomEntityId() {
        return customEntityId;
    }

    public static void setCustomEntityId(String rawId) {
        if (!GomodInternalFeatures.isEnabled()) {
            return;
        }
        if (rawId == null || rawId.trim().isEmpty()) {
            clearCustomAndSetPreset(Kind.OFF);
            return;
        }
        String id = sanitizeEntityId(rawId.trim());
        if (id.isEmpty()) {
            clearCustomAndSetPreset(Kind.OFF);
            return;
        }
        presetKind = Kind.OFF;
        customEntityId = id;
        ConfigManager.setDisguiseMobVisualKindName(id);
        setLastUsedEntityId(id);
        disposeProxy();
    }

    public static void clearCustomAndSetPreset(Kind k) {
        if (!GomodInternalFeatures.isEnabled()) {
            k = Kind.OFF;
        }
        customEntityId = null;
        presetKind = k != null ? k : Kind.OFF;
        ConfigManager.setDisguiseMobVisualKindName(presetKind == Kind.OFF ? "OFF" : presetKind.name());
        if (presetKind != Kind.OFF) {
            setLastUsedEntityId(titleCase(presetKind.name()));
        }
        disposeProxy();
    }

    public static Kind getKind() {
        return presetKind;
    }

    public static void setKind(Kind k) {
        clearCustomAndSetPreset(k);
    }

    public static void cycleKind() {
        customEntityId = null;
        Kind[] v = Kind.values();
        Kind next = v[(presetKind.ordinal() + 1) % v.length];
        presetKind = next;
        ConfigManager.setDisguiseMobVisualKindName(next.name());
        disposeProxy();
    }

    public static String getGuiButtonLabel() {
        return getMobDisguiseStatusLine();
    }

    /** One-line status for the disguise screen (shown next to "Mob disguise"). */
    public static String getMobDisguiseStatusLine() {
        if (!isActive()) {
            return net.minecraft.util.EnumChatFormatting.AQUA + "Choose mob";
        }
        if (customEntityId != null && !customEntityId.isEmpty()) {
            return net.minecraft.util.EnumChatFormatting.GREEN + customEntityId;
        }
        return net.minecraft.util.EnumChatFormatting.GREEN + titleCase(presetKind.name());
    }

    private static String titleCase(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return s.charAt(0) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    public static boolean isActive() {
        if (!GomodInternalFeatures.isEnabled()) {
            return false;
        }
        if (customEntityId != null && !customEntityId.isEmpty()) {
            return true;
        }
        return presetKind != Kind.OFF;
    }

    public static boolean shouldHideLocalPlayerModel() {
        if (!isActive()) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.gameSettings != null && mc.gameSettings.thirdPersonView != 0;
    }

    public static void clientTick(Minecraft mc) {
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            disposeProxy();
            return;
        }
        if (!isActive()) {
            disposeProxy();
            return;
        }
        ensureProxy(mc.theWorld);
        if (proxy != null) {
            syncProxyState(mc.thePlayer);
        }
    }

    /**
     * Draw the mob model in world space after vanilla entities (third person only).
     * Called once from the Forge or Weave world render bridge.
     */
    public static void renderAfterWorld(Minecraft mc, float partialTicks) {
        if (!isActive() || mc == null || mc.thePlayer == null || mc.theWorld == null || mc.getRenderManager() == null) {
            return;
        }
        if (mc.gameSettings == null || mc.gameSettings.thirdPersonView == 0) {
            return;
        }
        ensureProxy(mc.theWorld);
        if (proxy == null) {
            return;
        }
        syncProxyState(mc.thePlayer);
        try {
            net.minecraft.client.renderer.entity.RenderManager rm = mc.getRenderManager();
            RenderManagerAccessor rma = (RenderManagerAccessor) rm;
            double rx = rma.gomod$getRenderPosX();
            double ry = rma.gomod$getRenderPosY();
            double rz = rma.gomod$getRenderPosZ();
            double px = mc.thePlayer.lastTickPosX + (mc.thePlayer.posX - mc.thePlayer.lastTickPosX) * partialTicks;
            double py = mc.thePlayer.lastTickPosY + (mc.thePlayer.posY - mc.thePlayer.lastTickPosY) * partialTicks + 0.02D;
            double pz = mc.thePlayer.lastTickPosZ + (mc.thePlayer.posZ - mc.thePlayer.lastTickPosZ) * partialTicks;
            float yaw = mc.thePlayer.prevRotationYaw
                    + (mc.thePlayer.rotationYaw - mc.thePlayer.prevRotationYaw) * partialTicks;

            GlStateManager.pushMatrix();
            GlStateManager.enableDepth();
            GlStateManager.depthMask(true);
            GlStateManager.enablePolygonOffset();
            GlStateManager.doPolygonOffset(0.25F, -0.5F);
            // World-last pass often runs with the lightmap off; without it the mob draws flat / “full bright”.
            net.minecraft.client.renderer.EntityRenderer er = mc.entityRenderer;
            boolean lightPushed = false;
            try {
                if (er != null) {
                    er.enableLightmap();
                    lightPushed = true;
                }
                // renderEntityWithPosYaw skips the brightness and color setup done by renderEntityStatic.
                // Use the proxy's own eye-height light sample, just like a normal mob at this position.
                int packedLight = proxy.isBurning() ? 15728880 : proxy.getBrightnessForRender(partialTicks);
                OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit,
                        (float) (packedLight & 0xFFFF), (float) ((packedLight >>> 16) & 0xFFFF));
                GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
                rm.renderEntityWithPosYaw(proxy, px - rx, py - ry, pz - rz, yaw, partialTicks);
            } finally {
                if (lightPushed && er != null) {
                    er.disableLightmap();
                }
                GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
                GlStateManager.doPolygonOffset(0.0F, 0.0F);
                GlStateManager.disablePolygonOffset();
                GlStateManager.popMatrix();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void parseStoredVisualSpec(String stored) {
        presetKind = Kind.OFF;
        customEntityId = null;
        if (stored == null || stored.trim().isEmpty()) {
            return;
        }
        String t = stored.trim();
        if ("OFF".equalsIgnoreCase(t)) {
            return;
        }
        try {
            presetKind = Kind.valueOf(t.toUpperCase(Locale.ROOT));
            return;
        } catch (IllegalArgumentException ignored) {
        }
        String id = sanitizeEntityId(t);
        if (!id.isEmpty()) {
            customEntityId = id;
        }
    }

    private static String sanitizeEntityId(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(Math.min(s.length(), 48));
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_') {
                b.append(c);
            }
        }
        return b.toString();
    }

    /**
     * Preferred value for mob disguise text inputs:
     * current active mob (custom or preset), otherwise last used, otherwise Zombie.
     */
    public static String getPreferredMobInputValue() {
        if (customEntityId != null && !customEntityId.isEmpty()) {
            return customEntityId;
        }
        if (presetKind != Kind.OFF) {
            return titleCase(presetKind.name());
        }
        if (lastUsedEntityId != null && !lastUsedEntityId.isEmpty()) {
            return lastUsedEntityId;
        }
        return "Zombie";
    }

    private static void setLastUsedEntityId(String raw) {
        String sanitized = sanitizeEntityId(raw);
        if (sanitized.isEmpty() || "OFF".equalsIgnoreCase(sanitized)) {
            return;
        }
        lastUsedEntityId = sanitized;
        ConfigManager.setDisguiseMobVisualLastUsedName(sanitized);
    }

    private static void disposeProxy() {
        proxy = null;
        proxyWorld = null;
        proxySpecKey = "";
    }

    private static String currentSpecKey() {
        if (customEntityId != null && !customEntityId.isEmpty()) {
            return "c:" + customEntityId;
        }
        return "p:" + presetKind.name();
    }

    private static void ensureProxy(World world) {
        if (world == null || !isActive()) {
            disposeProxy();
            return;
        }
        String key = currentSpecKey();
        if (proxy != null && (world != proxyWorld || !key.equals(proxySpecKey))) {
            proxy = null;
        }
        if (proxy == null) {
            proxy = createProxy(world);
            proxyWorld = world;
            proxySpecKey = key;
        }
    }

    private static EntityLivingBase createProxy(World world) {
        if (customEntityId != null && !customEntityId.isEmpty()) {
            EntityLivingBase fromList = tryCreateLivingFromEntityList(world, customEntityId);
            if (fromList != null) {
                return fromList;
            }
        }
        if (presetKind != Kind.OFF) {
            return createForPreset(world, presetKind);
        }
        return null;
    }

    private static EntityLivingBase tryCreateLivingFromEntityList(World world, String id) {
        Entity e = tryCreateEntity(world, id);
        if (e instanceof EntityLivingBase) {
            return (EntityLivingBase) e;
        }
        return null;
    }

    private static Entity tryCreateEntity(World world, String id) {
        if (world == null || id == null || id.isEmpty()) {
            return null;
        }
        String[] attempts = buildEntityNameAttempts(id);
        for (String name : attempts) {
            try {
                Entity e = EntityList.createEntityByName(name, world);
                if (e != null) {
                    return e;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String[] buildEntityNameAttempts(String id) {
        if (id == null || id.isEmpty()) {
            return new String[0];
        }
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        set.add(id);
        addEntityListKeysMatchingIgnoreCase(id, set);
        if (!id.isEmpty()) {
            set.add(id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1).toLowerCase(Locale.ROOT));
        }
        StringBuilder title = new StringBuilder();
        boolean capNext = true;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c == '_') {
                capNext = true;
                continue;
            }
            if (capNext && Character.isLetter(c)) {
                title.append(Character.toUpperCase(c));
                capNext = false;
            } else {
                title.append(Character.toLowerCase(c));
            }
        }
        if (title.length() > 0) {
            set.add(title.toString());
        }
        return set.toArray(new String[0]);
    }

    /**
     * Resolves typed ids like {@code enderdragon} to registry keys like {@code EnderDragon} by scanning
     * {@link EntityList} static string maps (case-insensitive key match).
     */
    private static void addEntityListKeysMatchingIgnoreCase(String id, java.util.LinkedHashSet<String> set) {
        try {
            for (Field f : EntityList.class.getDeclaredFields()) {
                if ((f.getModifiers() & Modifier.STATIC) == 0 || !Map.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                f.setAccessible(true);
                Object o = f.get(null);
                if (!(o instanceof Map)) {
                    continue;
                }
                for (Object k : ((Map<?, ?>) o).keySet()) {
                    if (k instanceof String && ((String) k).equalsIgnoreCase(id)) {
                        set.add((String) k);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static EntityLivingBase createForPreset(World world, Kind k) {
        try {
            switch (k) {
                case ZOMBIE: {
                    EntityZombie z = new EntityZombie(world);
                    z.setChild(false);
                    return z;
                }
                case SKELETON:
                    return new EntitySkeleton(world);
                case CREEPER:
                    return new EntityCreeper(world);
                case COW:
                    return new EntityCow(world);
                case PIG:
                    return new EntityPig(world);
                case SPIDER:
                    return new EntitySpider(world);
                default:
                    return null;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void syncProxyState(AbstractClientPlayer p) {
        if (proxy == null || p == null) {
            return;
        }
        World w = p.worldObj;
        proxy.worldObj = w;
        proxy.dimension = p.dimension;
        proxy.posX = p.posX;
        proxy.posY = p.posY;
        proxy.posZ = p.posZ;
        proxy.prevPosX = p.prevPosX;
        proxy.prevPosY = p.prevPosY;
        proxy.prevPosZ = p.prevPosZ;
        proxy.lastTickPosX = p.lastTickPosX;
        proxy.lastTickPosY = p.lastTickPosY;
        proxy.lastTickPosZ = p.lastTickPosZ;
        proxy.motionX = 0.0D;
        proxy.motionY = 0.0D;
        proxy.motionZ = 0.0D;
        proxy.rotationYaw = p.rotationYaw;
        proxy.rotationPitch = p.rotationPitch;
        proxy.prevRotationYaw = p.prevRotationYaw;
        proxy.prevRotationPitch = p.prevRotationPitch;
        proxy.rotationYawHead = p.rotationYawHead;
        proxy.prevRotationYawHead = p.prevRotationYawHead;
        proxy.renderYawOffset = p.renderYawOffset;
        proxy.prevRenderYawOffset = p.prevRenderYawOffset;
        proxy.limbSwing = p.limbSwing;
        proxy.limbSwingAmount = p.limbSwingAmount;
        if (proxy instanceof EntityDragon && w != null) {
            int t = (int) (w.getTotalWorldTime() % 240000L);
            proxy.ticksExisted = t;
        } else {
            proxy.ticksExisted = p.ticksExisted;
        }
        proxy.isDead = false;
        proxy.hurtTime = 0;
        proxy.deathTime = 0;
        try {
            proxy.setAlwaysRenderNameTag(false);
            proxy.setInvisible(false);
        } catch (Throwable ignored) {
        }
    }
}
