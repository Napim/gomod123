package me.napim.gomod.util;

import me.napim.gomod.features.BarrierVisibilityStyles;
import me.napim.gomod.features.ConfigManager;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.data.IMetadataSection;
import net.minecraft.client.resources.data.IMetadataSerializer;
import net.minecraft.client.resources.data.PackMetadataSection;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ResourceLocation;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Minimal {@link IResourcePack} backed by the mod JAR classpath.
 *
 * <ul>
 *     <li>Serves {@code gomod:...} and {@code gomod123:...} from {@code /assets/&lt;domain&gt;/...}
 *         (HUD icons, kill effects, sounds, barrier styles, etc.).</li>
 *     <li>Serves gomod's {@code minecraft:...} barrier blockstate/model/texture resources on Weave,
 *         where Forge's automatic mod resource-pack mounting is not available.</li>
 *     <li>When a non-{@link BarrierVisibilityStyles#FULL} barrier style is selected, overrides
 *         {@code minecraft:textures/blocks/barrier.png} with the matching
 *         {@code /assets/gomod/textures/barrier_style_<id>.png} so a full {@code refreshResources()}
 *         still shows the right art (the live GPU swap path is separate).</li>
 * </ul>
 */
public final class ClasspathGomodResourcePack implements IResourcePack {

    private static final String DOMAIN_GOMOD = "gomod";
    private static final String DOMAIN_GOMOD123 = "gomod123";
    private static final String DOMAIN_MINECRAFT = "minecraft";
    private static final Set<String> DOMAINS = Collections.unmodifiableSet(new HashSet<String>(
            Arrays.asList(DOMAIN_GOMOD, DOMAIN_GOMOD123, DOMAIN_MINECRAFT)));

    private static final String BARRIER_TEXTURE_PATH = "textures/blocks/barrier.png";

    private static final Class<?> ANCHOR = ClasspathGomodResourcePack.class;

    private static String currentStyleOverrideClasspath() {
        try {
            String cp = BarrierVisibilityStyles.classpathOverridePng(ConfigManager.getBarrierVisibilityStyle());
            if (cp != null && ANCHOR.getResource(cp) != null) {
                return cp;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Override
    public InputStream getInputStream(ResourceLocation location) throws IOException {
        InputStream in = getStreamUnchecked(location);
        if (in == null) {
            throw new IOException("Missing gomod resource: " + location);
        }
        return in;
    }

    private static InputStream getStreamUnchecked(ResourceLocation location) {
        String domain = location.getResourceDomain();
        String path = location.getResourcePath();
        if (DOMAIN_GOMOD.equals(domain) || DOMAIN_GOMOD123.equals(domain)) {
            return ANCHOR.getResourceAsStream("/assets/" + domain + "/" + path);
        }
        if (DOMAIN_MINECRAFT.equals(domain) && BARRIER_TEXTURE_PATH.equals(path)) {
            String cp = currentStyleOverrideClasspath();
            if (cp != null) {
                return ANCHOR.getResourceAsStream(cp);
            }
        }
        if (DOMAIN_MINECRAFT.equals(domain)) {
            return ANCHOR.getResourceAsStream("/assets/" + domain + "/" + path);
        }
        return null;
    }

    @Override
    public boolean resourceExists(ResourceLocation location) {
        String domain = location.getResourceDomain();
        if (!DOMAIN_GOMOD.equals(domain) && !DOMAIN_GOMOD123.equals(domain)
                && !DOMAIN_MINECRAFT.equals(domain)) {
            return false;
        }
        InputStream in = getStreamUnchecked(location);
        if (in == null) {
            return false;
        }
        try {
            in.close();
        } catch (IOException ignored) {
        }
        return true;
    }

    @Override
    public Set<String> getResourceDomains() {
        return DOMAINS;
    }

    @Override
    public <T extends IMetadataSection> T getPackMetadata(IMetadataSerializer metadataSerializer, String metadataSectionName) {
        if (!"pack".equals(metadataSectionName)) {
            return null;
        }
        try {
            return (T) new PackMetadataSection(new ChatComponentText("gomod_builtin_assets"), 1);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public BufferedImage getPackImage() throws IOException {
        return new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    }

    @Override
    public String getPackName() {
        return "gomod_builtin_assets";
    }
}
