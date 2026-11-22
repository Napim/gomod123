package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.BufferUtils;

import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Draws the real Minecraft compass on Goparky's live animated right hand. */
public final class GoparkyCompassRenderer {
    private static final String[] HAND_CHAIN = {"companion_root", "mini_me", "torso", "bust", "right_arm"};
    private static final Map<Class<?>, BoneAccess> BONES = new ConcurrentHashMap<Class<?>, BoneAccess>();
    private static final Map<Class<?>, Method> PROCESSORS = new ConcurrentHashMap<Class<?>, Method>();
    private static final Map<Class<?>, Method> FIND_BONES = new ConcurrentHashMap<Class<?>, Method>();
    private static ItemStack compass;
    private static final FloatBuffer COLOR = BufferUtils.createFloatBuffer(16);
    private static long draws;
    private static long failures;
    private static boolean reported;

    private GoparkyCompassRenderer() {}

    /** Called immediately after the native mesh draw, before its world/yaw matrix is popped. */
    public static void render(Object actor, Object model) {
        if (model == null || GoparkyBlitzReactions.getTauntRemaining(actor) <= 0L) return;
        double[] pose = GoparkyBlitzReactions.getTauntPose(actor);
        float click = pose != null && pose.length > 2
                ? (float) Math.max(0.0D, Math.min(1.0D, pose[2])) : 0.0F;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getRenderItem() == null) return;
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        boolean lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean rescale = GL11.glIsEnabled(GL12.GL_RESCALE_NORMAL);
        boolean alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        int alphaFunction = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
        float alphaReference = GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF);
        int sourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int destinationRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int sourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int destinationAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        COLOR.clear();
        GL11.glGetFloat(GL11.GL_CURRENT_COLOR, COLOR);
        float red = COLOR.get(0), green = COLOR.get(1), blue = COLOR.get(2), opacity = COLOR.get(3);
        GlStateManager.pushMatrix();
        try {
            Method getter = PROCESSORS.get(model.getClass());
            if (getter == null) {
                getter = model.getClass().getMethod("getAnimationProcessor");
                PROCESSORS.put(model.getClass(), getter);
            }
            Object processor = getter.invoke(model);
            Method find = FIND_BONES.get(processor.getClass());
            if (find == null) {
                find = processor.getClass().getMethod("getBone", String.class);
                FIND_BONES.put(processor.getClass(), find);
            }
            // Use the animated bones, including the crouched torso and attack swing;
            // a guessed world-space hand point would drift when Goparky turns or moves.
            for (String name : HAND_CHAIN) {
                Object bone = find.invoke(processor, name);
                if (bone == null) throw new IllegalStateException("Missing hand bone " + name);
                BoneAccess access = BONES.get(bone.getClass());
                if (access == null) {
                    access = new BoneAccess(bone.getClass());
                    BONES.put(bone.getClass(), access);
                }
                access.apply(bone);
            }
            // Lunar's model loader mirrors Bedrock X coordinates (including bone pivots).
            GlStateManager.translate(3.82D / 16.0D, 4.4D / 16.0D, -1.15D / 16.0D);
            GlStateManager.rotate(-40.0F, 1.0F, 0.0F, 0.0F);
            // Give the real item a visible press motion as well as following the animated arm.
            GlStateManager.translate(0.0D, -0.22D * click, 0.08D * click);
            GlStateManager.scale(0.24F, 0.24F, 0.24F);
            GlStateManager.rotate(18.0F * click, 0.0F, 0.0F, 1.0F);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            if (compass == null) compass = new ItemStack(Items.compass);
            mc.getRenderItem().renderItem(compass, ItemCameraTransforms.TransformType.NONE);
            draws++;
        } catch (Throwable error) {
            failures++;
            if (!reported) {
                reported = true;
                System.err.println("[gomod] Goparky compass attachment failed: " + error.getClass().getSimpleName());
            }
        } finally {
            GlStateManager.popMatrix();
            GlStateManager.bindTexture(previousTexture);
            if (lighting) GlStateManager.enableLighting(); else GlStateManager.disableLighting();
            if (cull) GlStateManager.enableCull(); else GlStateManager.disableCull();
            if (blend) GlStateManager.enableBlend(); else GlStateManager.disableBlend();
            if (rescale) GlStateManager.enableRescaleNormal(); else GlStateManager.disableRescaleNormal();
            if (alpha) GlStateManager.enableAlpha(); else GlStateManager.disableAlpha();
            GlStateManager.alphaFunc(alphaFunction, alphaReference);
            GlStateManager.tryBlendFuncSeparate(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha);
            GlStateManager.color(red, green, blue, opacity);
        }
    }

    public static long draws() { return draws; }
    public static long failures() { return failures; }

    private static final class BoneAccess {
        final Method[] position = new Method[3], pivot = new Method[3], rotation = new Method[3], scale = new Method[3];

        BoneAccess(Class<?> bone) throws NoSuchMethodException {
            String[] axes = {"X", "Y", "Z"};
            for (int i = 0; i < axes.length; i++) {
                position[i] = bone.getMethod("getPosition" + axes[i]);
                pivot[i] = bone.getMethod("getPivot" + axes[i]);
                rotation[i] = bone.getMethod("getRotation" + axes[i]);
                scale[i] = bone.getMethod("getScale" + axes[i]);
            }
        }

        void apply(Object bone) throws ReflectiveOperationException {
            double px = number(pivot[0], bone) / 16D, py = number(pivot[1], bone) / 16D, pz = number(pivot[2], bone) / 16D;
            GlStateManager.translate(-number(position[0], bone) / 16D, number(position[1], bone) / 16D, number(position[2], bone) / 16D);
            GlStateManager.translate(px, py, pz);
            GlStateManager.rotate((float) Math.toDegrees(number(rotation[2], bone)), 0F, 0F, 1F);
            GlStateManager.rotate((float) Math.toDegrees(number(rotation[1], bone)), 0F, 1F, 0F);
            GlStateManager.rotate((float) Math.toDegrees(number(rotation[0], bone)), 1F, 0F, 0F);
            GlStateManager.scale(number(scale[0], bone), number(scale[1], bone), number(scale[2], bone));
            GlStateManager.translate(-px, -py, -pz);
        }

        double number(Method getter, Object bone) throws ReflectiveOperationException {
            return ((Number) getter.invoke(bone)).doubleValue();
        }
    }
}
