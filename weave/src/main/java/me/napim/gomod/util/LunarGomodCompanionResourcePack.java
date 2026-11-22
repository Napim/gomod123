package me.napim.gomod.util;

import me.napim.gomod.features.LunarConnectionDiagnostics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.data.IMetadataSection;
import net.minecraft.client.resources.data.IMetadataSerializer;
import net.minecraft.util.ResourceLocation;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Mounts only the companion's assets in the live resource manager, without a global reload. */
public final class LunarGomodCompanionResourcePack implements IResourcePack {
    // Keep the path versioned so Lunar's persistent JIT asset cache cannot serve an older
    // preview/texture after the packaged mascot model changes.
    private static final String PREFIX = "cosmetics/models/gek/companions/gomod_mascot_v14/";
    private static final Set<String> DOMAINS = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("lunar", "lunar-jit", "gomod")));
    private static final LunarGomodCompanionResourcePack PACK = new LunarGomodCompanionResourcePack();

    public static void mount() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) return;
            if (!mc.isCallingFromMinecraftThread()) {
                mc.addScheduledTask(LunarGomodCompanionResourcePack::mount);
                return;
            }
            // A live-manager-only mount disappears the next time Lunar reloads resources.
            // Keep this pack in Minecraft's default pack list as well, so the Gek assets
            // survive F3+T, skin/resource refreshes, and the Gomod pack's deferred reload.
            boolean retainedForReload = retainForReload(mc);
            IResourceManager manager = mc.getResourceManager();
            if (manager == null) {
                reportMount("no-resource-manager", retainedForReload, false, false);
                return;
            }
            if (hasAllCompanionResources(manager)) {
                reportMount("already-available", retainedForReload, true, false);
                return;
            }
            boolean attachedToLiveManager = false;
            for (Class<?> type = manager.getClass(); type != null; type = type.getSuperclass()) {
                for (Method method : type.getDeclaredMethods()) {
                    if (method.getParameterTypes().length != 1
                            || method.getParameterTypes()[0] != IResourcePack.class) continue;
                    method.setAccessible(true);
                    method.invoke(manager, PACK);
                    attachedToLiveManager = true;
                    break;
                }
                if (attachedToLiveManager) break;
            }
            boolean resourcesAvailable = hasAllCompanionResources(manager);
            reportMount(attachedToLiveManager ? "attached" : "mount-method-missing",
                    retainedForReload, resourcesAvailable, attachedToLiveManager);
        } catch (Throwable error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            LunarConnectionDiagnostics.recordOnce("lunar.gomod_mascot_assets", cause.getClass().getName(),
                    "result=failed exception=" + cause.getClass().getSimpleName());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean retainForReload(Minecraft mc) {
        try {
            List<IResourcePack> defaults = MinecraftPrivateAccess.getDefaultResourcePacks(mc);
            if (defaults == null) return false;
            for (IResourcePack pack : defaults) {
                if (pack == PACK || pack instanceof LunarGomodCompanionResourcePack) return true;
            }
            ((List) defaults).add(PACK);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasAllCompanionResources(IResourceManager manager) {
        // Lunar reconstructs the descriptor in the "lunar" namespace and its child Gek
        // assets in "lunar-jit". Custom hurt/taunt skins are checked under "lunar" because
        // the native texture manager resolves the descriptor's namespace.
        return hasResource(manager, "lunar", PREFIX + "gomod_mascot.gek.json")
                && hasResource(manager, "lunar", PREFIX + "gomod_mascot.geo.json")
                && hasResource(manager, "lunar", PREFIX + "gomod_mascot.anim.json")
                && hasResource(manager, "lunar", PREFIX + "gomodmascot.png")
                && hasResource(manager, "lunar-jit", PREFIX + "gomod_mascot.geo.json")
                && hasResource(manager, "lunar-jit", PREFIX + "gomod_mascot.anim.json")
                && hasResource(manager, "lunar-jit", PREFIX + "gomodmascot.png")
                && hasResource(manager, "lunar", PREFIX + "gomodmascot_taunt.png")
                && hasResource(manager, "lunar", PREFIX + "gomodmascot_hit.png")
                && hasResource(manager, "lunar", PREFIX + "gomodmascot_hit_medium.png")
                && hasResource(manager, "lunar", PREFIX + "gomodmascot_hit_light.png")
                && hasResource(manager, "lunar", PREFIX + "gomodmascot_hit_faint.png");
    }

    private static void reportMount(String result, boolean retainedForReload,
                                    boolean resourcesAvailable, boolean attachedToLiveManager) {
        LunarConnectionDiagnostics.recordOnce("lunar.gomod_mascot_assets",
                result + ":" + retainedForReload + ":" + resourcesAvailable + ":" + attachedToLiveManager,
                "result=" + result + " domains=lunar,lunar-jit,gomod scope=companion-only"
                        + " retainedForReload=" + retainedForReload
                        + " liveManagerAttached=" + attachedToLiveManager
                        + " resourcesAvailable=" + resourcesAvailable);
    }

    private static boolean hasResource(IResourceManager manager, String domain, String path) {
        try (InputStream existing = manager.getResource(new ResourceLocation(domain, path)).getInputStream()) {
            return existing != null;
        } catch (Throwable missing) {
            return false;
        }
    }

    @Override
    public InputStream getInputStream(ResourceLocation location) throws IOException {
        if (!isCompanionResource(location)) throw new IOException("Outside goparky resource pack");
        InputStream stream = getClass().getResourceAsStream("/assets/gomod/" + location.getResourcePath());
        if (stream == null) throw new IOException("Missing goparky asset: " + location.getResourcePath());
        LunarConnectionDiagnostics.recordOnce("lunar.gomod_mascot_asset_read",
                location.getResourceDomain() + ":" + location.getResourcePath(),
                "domain=" + location.getResourceDomain() + " path="
                        + location.getResourcePath() + " result=served");
        return stream;
    }

    @Override
    public boolean resourceExists(ResourceLocation location) {
        return isCompanionResource(location)
                && getClass().getResource("/assets/gomod/" + location.getResourcePath()) != null;
    }

    private static boolean isCompanionResource(ResourceLocation location) {
        return location != null && DOMAINS.contains(location.getResourceDomain())
                && location.getResourcePath().startsWith(PREFIX)
                && !location.getResourcePath().contains("..");
    }

    @Override
    public Set<String> getResourceDomains() {
        return DOMAINS;
    }

    @Override
    public <T extends IMetadataSection> T getPackMetadata(IMetadataSerializer serializer, String section) {
        return null;
    }

    @Override
    public BufferedImage getPackImage() {
        return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    }

    @Override
    public String getPackName() {
        return "gomod_goparky_assets";
    }
}

