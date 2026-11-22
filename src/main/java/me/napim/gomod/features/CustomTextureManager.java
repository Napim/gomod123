package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;
import net.minecraft.client.renderer.texture.ITextureObject;

/**
 * Custom texture manager for gomod mod textures.
 */
public class CustomTextureManager {
    private static final ResourceLocation HALF_HEART_TEXTURE = new ResourceLocation("gomod123", "textures/halfheart.png");
    private static final ResourceLocation HALF_HEART_TEXTURE_RUNTIME = new ResourceLocation("gomod123:runtime/halfheart");
    private static boolean halfHeartTextureLoaded = false;
    private static boolean usingRuntimeTexture = false;
    private static int halfHeartTextureWidth = 16;
    private static int halfHeartTextureHeight = 16;
    
    /**
     * Loads the half heart texture if not already loaded.
     * @return true if texture is available, false otherwise
     */
    public static boolean loadHalfHeartTexture() {
        if (halfHeartTextureLoaded) {
            return true;
        }
        
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.getTextureManager() == null) {
                return false;
            }
            
            net.minecraft.client.renderer.texture.TextureManager textureManager = mc.getTextureManager();
            
            // Try to load from resource manager first (standard way)
            try {
                if (mc.getResourceManager() != null) {
                    mc.getResourceManager().getResource(HALF_HEART_TEXTURE);
                    halfHeartTextureLoaded = true;
                    usingRuntimeTexture = false;
                    System.out.println("[gomod] Half heart texture loaded from resources");
                    return true;
                }
            } catch (Exception ignored) {}
            
            // Fallback: load from classpath using DynamicTexture
            java.io.InputStream stream = null;
            try {
                stream = CustomTextureManager.class.getResourceAsStream("/assets/gomod123/textures/halfheart.png");
                if (stream == null) {
                    stream = CustomTextureManager.class.getResourceAsStream("/assets/gomod/textures/halfheart.png");
                }
                if (stream != null) {
                    java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(stream);
                    if (img != null) {
                        halfHeartTextureWidth = img.getWidth();
                        halfHeartTextureHeight = img.getHeight();
                        net.minecraft.client.renderer.texture.DynamicTexture dyn = new net.minecraft.client.renderer.texture.DynamicTexture(img);
                        textureManager.loadTexture(HALF_HEART_TEXTURE_RUNTIME, dyn);
                        halfHeartTextureLoaded = true;
                        usingRuntimeTexture = true;
                        System.out.println("[gomod] Half heart texture loaded as runtime texture " + halfHeartTextureWidth + "x" + halfHeartTextureHeight);
                        stream.close();
                        return true;
                    }
                }
            } catch (Exception e) {
                System.out.println("[gomod] DynamicTexture load failed: " + e.getMessage());
            } finally {
                if (stream != null) try { stream.close(); } catch (Exception ignored) {}
            }
            
        } catch (Exception e) {
            System.out.println("[gomod] Failed to load half heart texture: " + e.getMessage());
        }
        return false;
    }
    
    /**
     * Binds the half heart texture for rendering.
     * @return true if texture was bound successfully, false otherwise
     */
    public static boolean bindHalfHeartTexture() {
        try {
            if (!halfHeartTextureLoaded) {
                loadHalfHeartTexture();
            }
            if (!halfHeartTextureLoaded) {
                return false;
            }
            
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.getTextureManager() != null) {
                // Use the correct texture location based on how it was loaded
                ResourceLocation texToUse = usingRuntimeTexture ? HALF_HEART_TEXTURE_RUNTIME : HALF_HEART_TEXTURE;
                mc.getTextureManager().bindTexture(texToUse);
                return true;
            }
        } catch (Exception e) {
            // Silently fail
        }
        return false;
    }
    
    /**
     * Gets the resource location for the half heart texture.
     * @return ResourceLocation for the half heart texture
     */
    public static ResourceLocation getHalfHeartTexture() {
        return HALF_HEART_TEXTURE;
    }
    
    public static int getHalfHeartTextureWidth() {
        return halfHeartTextureWidth;
    }
    
    public static int getHalfHeartTextureHeight() {
        return halfHeartTextureHeight;
    }
    
    /**
     * Checks if the half heart texture is available.
     * @return true if texture is available, false otherwise
     */
    public static boolean isHalfHeartTextureAvailable() {
        return loadHalfHeartTexture();
    }
}
