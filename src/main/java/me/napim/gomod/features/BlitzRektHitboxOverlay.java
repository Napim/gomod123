package me.napim.gomod.features;

import me.napim.gomod.util.HudGlState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.event.RenderWorldEvent;
import net.weavemc.loader.api.event.SubscribeEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** Draws aiming/fishing-hook bounds and a configurable visual calibration preview. */
public final class BlitzRektHitboxOverlay {

    private static final Pattern REKT_FINISHER_LABEL = Pattern.compile(
            "^[a-z0-9_]{1,16}\\s+has\\s+#rekt\\s+[a-z0-9_]{1,16}\\s+here$",
            Pattern.CASE_INSENSITIVE);
    private static final double MAX_DISTANCE_SQ = 64.0D * 64.0D;
    // EntityFishHook.onUpdate expands candidate entity bounds by this float before its ray test.
    public static final double FISHING_HOOK_COLLISION_BORDER = (double) 0.3F;
    private static final double OUTLINE_EXPANSION = 0.003D;
    private static final double DASH_LEN = 0.12D;
    private static final double GAP_LEN = 0.08D;
    private static final double MIN_PREVIEW_SIZE = 0.01D;
    private static final float HITBOX_R = 1.0F;
    private static final float HITBOX_G = 0.0F;
    private static final float HITBOX_B = 0.0F;
    private static volatile boolean renderErrorLogged;
    private static volatile boolean forceAllArmorStands;
    private static volatile String lastGlStateBeforeDraw = "<not captured yet>";
    private static volatile String lastGlStateAfterRestore = "<not captured yet>";
    private static volatile long lastRenderTime;
    private static volatile int lastRenderedBoxCount;
    private static volatile String lastRenderFailure = "<none>";

    @SubscribeEvent
    public void onRenderWorld(RenderWorldEvent event) {
        if (!GomodFixes.isBlitzRektHitboxOverlayEnabled() && !forceAllArmorStands) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        Entity camera = mc.getRenderViewEntity();
        if (camera == null) {
            return;
        }

        float partialTicks = event.getPartialTicks();
        double camX = camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * partialTicks;
        double camY = camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * partialTicks;
        double camZ = camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * partialTicks;
        List<RenderBox> boxes = collectRektBoxes(mc, partialTicks, camX, camY, camZ);
        if (boxes.isEmpty()) {
            return;
        }
        lastGlStateBeforeDraw = readGlState();

        FloatBuffer oldLineWidth = null;
        boolean lineWidthRead = false;
        boolean oldLineSmooth = false;
        boolean lineSmoothRead = false;
        try {
            // Keep Minecraft's cached state and OpenGL state in sync. Raw glPushAttrib/glPopAttrib
            // restores the driver state behind GlStateManager's cache and can leave later text
            // rendering with a disabled lightmap/font texture unit.
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GlStateManager.disableTexture2D();
            GlStateManager.disableLighting();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(
                    GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            GlStateManager.enableDepth();
            GlStateManager.depthMask(false);
            GlStateManager.depthFunc(GL11.GL_LEQUAL);

            // LWJGL 2 requires space for 16 values for every glGetFloat buffer query,
            // even when this particular parameter only returns one or two values.
            oldLineWidth = org.lwjgl.BufferUtils.createFloatBuffer(16);
            GL11.glGetFloat(GL11.GL_LINE_WIDTH, oldLineWidth);
            lineWidthRead = true;
            oldLineSmooth = GL11.glIsEnabled(GL11.GL_LINE_SMOOTH);
            lineSmoothRead = true;
            GL11.glEnable(GL11.GL_LINE_SMOOTH);
            Tessellator tess = Tessellator.getInstance();
            WorldRenderer wr = tess.getWorldRenderer();

            // Match Player Outline's dotted edge pattern. No filled faces are drawn, so the
            // red overlay never tints the space inside the hitbox.
            GL11.glLineWidth(getSupportedLineWidth(1.5F));
            wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
            for (RenderBox box : boxes) {
                if (box.preview == null) {
                    addBoxEdges(wr, box.bounds, HITBOX_R, HITBOX_G, HITBOX_B, 1.0F);
                } else {
                    addOrientedBoxEdges(wr, box.preview, HITBOX_R, HITBOX_G, HITBOX_B, 1.0F);
                }
            }
            tess.draw();
            lastRenderTime = System.currentTimeMillis();
            lastRenderedBoxCount = boxes.size();
            lastRenderFailure = "<none>";
        } catch (Throwable failure) {
            lastRenderFailure = failure.toString();
            if (!renderErrorLogged) {
                renderErrorLogged = true;
                System.err.println("[gomod] Blitz #rekt hitbox overlay render failed: " + failure);
                failure.printStackTrace();
            }
        } finally {
            if (lineWidthRead && oldLineWidth != null) {
                try {
                    GL11.glLineWidth(oldLineWidth.get(0));
                } catch (Throwable ignored) {
                }
            }
            if (lineSmoothRead) {
                try {
                    if (oldLineSmooth) GL11.glEnable(GL11.GL_LINE_SMOOTH);
                    else GL11.glDisable(GL11.GL_LINE_SMOOTH);
                } catch (Throwable ignored) {
                }
            }
            // This also restores both texture units and the HUD-friendly state that the subsequent
            // in-world text and first-person hand passes expect.
            HudGlState.restoreBeforeWorldText();
            lastGlStateAfterRestore = readGlState();
        }
    }

    public static String getDebugRenderStateSummary() {
        long age = lastRenderTime <= 0L ? -1L : Math.max(0L, System.currentTimeMillis() - lastRenderTime);
        return "forceAllArmorStands=" + forceAllArmorStands
                + " lastBoxes=" + lastRenderedBoxCount + " lastRenderAgeMs=" + age
                + " boxPadding=max(aiming,0.3) outline=red-dotted-no-fill"
                + " lastFailure={" + lastRenderFailure + "}"
                + " before={" + lastGlStateBeforeDraw + "} afterRestore={" + lastGlStateAfterRestore + "}";
    }

    public static boolean isForceAllArmorStandsEnabled() {
        return forceAllArmorStands;
    }

    public static void setForceAllArmorStandsEnabled(boolean enabled) {
        forceAllArmorStands = enabled;
    }

    private static String readGlState() {
        try {
            return "activeTexture=0x" + Integer.toHexString(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE))
                    + ",texture2D=" + GL11.glIsEnabled(GL11.GL_TEXTURE_2D)
                    + ",blend=" + GL11.glIsEnabled(GL11.GL_BLEND)
                    + ",depth=" + GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
                    + ",depthFunc=0x" + Integer.toHexString(GL11.glGetInteger(GL11.GL_DEPTH_FUNC))
                    + ",depthWrite=" + GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
                    + ",lineWidth=" + GL11.glGetFloat(GL11.GL_LINE_WIDTH);
        } catch (Throwable failure) {
            return "unavailable:" + failure.getClass().getSimpleName();
        }
    }

    private static List<RenderBox> collectRektBoxes(Minecraft mc, float partialTicks,
                                                     double camX, double camY, double camZ) {
        List<RenderBox> boxes = Collections.emptyList();
        for (Object value : mc.theWorld.loadedEntityList) {
            if (!(value instanceof EntityArmorStand)) {
                continue;
            }
            EntityArmorStand stand = (EntityArmorStand) value;
            if (stand.isDead || stand.getDistanceSqToEntity(mc.thePlayer) > MAX_DISTANCE_SQ
                    || (!forceAllArmorStands && !isRektFinisherStand(stand))) {
                continue;
            }

            AxisAlignedBB bounds;
            try {
                bounds = stand.getEntityBoundingBox();
            } catch (Throwable ignored) {
                continue;
            }
            if (bounds == null || bounds.maxX - bounds.minX < 0.01D
                    || bounds.maxY - bounds.minY < 0.01D
                    || bounds.maxZ - bounds.minZ < 0.01D) {
                continue;
            }

            // Aiming uses collisionBorderSize; fishing hooks independently expand the raw
            // entity bounds by 0.3F. Their union shows the clearance needed by both paths.
            AxisAlignedBB blockingBounds = expandBlockingBounds(bounds, stand.getCollisionBorderSize());
            PreviewBox previewBox = createPreviewBox(blockingBounds);

            double entityX = stand.lastTickPosX + (stand.posX - stand.lastTickPosX) * partialTicks;
            double entityY = stand.lastTickPosY + (stand.posY - stand.lastTickPosY) * partialTicks;
            double entityZ = stand.lastTickPosZ + (stand.posZ - stand.lastTickPosZ) * partialTicks;
            double offsetX = entityX - stand.posX - camX;
            double offsetY = entityY - stand.posY - camY;
            double offsetZ = entityZ - stand.posZ - camZ;
            if (boxes.isEmpty()) {
                boxes = new ArrayList<>();
            }
            if (previewBox == null) {
                boxes.add(new RenderBox(blockingBounds.offset(offsetX, offsetY, offsetZ), null));
            } else {
                PreviewBox adjustedBounds = previewBox.offset(offsetX, offsetY, offsetZ);
                boxes.add(new RenderBox(null, adjustedBounds));
            }
        }
        return boxes;
    }

    public static AxisAlignedBB expandBlockingBounds(AxisAlignedBB bounds, double aimingBorder) {
        if (bounds == null) return null;
        double border = Double.isNaN(aimingBorder) || Double.isInfinite(aimingBorder)
                ? FISHING_HOOK_COLLISION_BORDER : Math.max(FISHING_HOOK_COLLISION_BORDER, aimingBorder);
        return bounds.expand(border, border, border);
    }

    public static AxisAlignedBB applyPreviewPadding(AxisAlignedBB bounds, float playerYaw) {
        if (bounds == null) return null;
        PreviewBox preview = createPreviewBox(bounds);
        return preview == null ? bounds : toEnvelope(preview);
    }

    /** Adjusted width along world X; the legacy yaw parameter is ignored. */
    public static double getPreviewFacingWidth(AxisAlignedBB bounds, float playerYaw) {
        PreviewBox preview = createPreviewBox(bounds);
        return preview == null ? (bounds == null ? 0.0D : averageHorizontalExtent(bounds))
                : preview.maxRight - preview.minRight;
    }

    /** Unpadded depth along world Z; the legacy yaw parameter is ignored. */
    public static double getPreviewFacingDepth(AxisAlignedBB bounds, float playerYaw) {
        PreviewBox preview = createPreviewBox(bounds);
        return preview == null ? (bounds == null ? 0.0D : averageHorizontalExtent(bounds))
                : preview.halfDepth * 2.0D;
    }

    private static PreviewBox createPreviewBox(AxisAlignedBB bounds) {
        if (bounds == null) return null;
        double left = GomodFixes.getBlitzRektHitboxPreviewPadding("left");
        double right = GomodFixes.getBlitzRektHitboxPreviewPadding("right");
        double up = GomodFixes.getBlitzRektHitboxPreviewPadding("up");
        double down = GomodFixes.getBlitzRektHitboxPreviewPadding("down");
        if (left == 0.0D && right == 0.0D && up == 0.0D && down == 0.0D) return null;

        // Apply the left/right adjustment to both horizontal axes so the footprint remains
        // square from every compass direction. Keep height independent and pin the prism to
        // world axes so it stays with the armor stand instead of swiveling with the player's view.
        double horizontalExtent = averageHorizontalExtent(bounds);
        double halfWidth = horizontalExtent * 0.5D;
        double appliedLeft = limitPreviewPadding(left, right, horizontalExtent);
        double appliedRight = limitPreviewPadding(right, left, horizontalExtent);
        double minRight = -halfWidth - appliedLeft;
        double maxRight = halfWidth + appliedRight;

        double verticalExtent = bounds.maxY - bounds.minY;
        double appliedDown = limitPreviewPadding(down, up, verticalExtent);
        double appliedUp = limitPreviewPadding(up, down, verticalExtent);
        double minY = bounds.minY - appliedDown;
        double maxY = bounds.maxY + appliedUp;

        return new PreviewBox(
                (bounds.minX + bounds.maxX) * 0.5D,
                (bounds.minZ + bounds.maxZ) * 0.5D,
                1.0D, 0.0D, 0.0D, 1.0D,
                minRight, maxRight, minY, maxY, (maxRight - minRight) * 0.5D);
    }

    private static double averageHorizontalExtent(AxisAlignedBB bounds) {
        return ((bounds.maxX - bounds.minX) + (bounds.maxZ - bounds.minZ)) * 0.5D;
    }

    private static AxisAlignedBB toEnvelope(PreviewBox box) {
        double rightCenter = (box.minRight + box.maxRight) * 0.5D;
        double halfWidth = (box.maxRight - box.minRight) * 0.5D;
        double centerX = box.centerX + box.rightX * rightCenter;
        double centerZ = box.centerZ + box.rightZ * rightCenter;
        double xRadius = Math.abs(box.rightX) * halfWidth + Math.abs(box.forwardX) * box.halfDepth;
        double zRadius = Math.abs(box.rightZ) * halfWidth + Math.abs(box.forwardZ) * box.halfDepth;
        return new AxisAlignedBB(centerX - xRadius, box.minY, centerZ - zRadius,
                centerX + xRadius, box.maxY, centerZ + zRadius);
    }

    /** Scale inward adjustments together so large reductions never invert or reopen the box. */
    private static double limitPreviewPadding(double padding, double opposite, double extent) {
        if (padding >= 0.0D) return padding;
        double shrink = -Math.min(0.0D, padding) - Math.min(0.0D, opposite);
        double available = Math.max(0.0D, extent + Math.max(0.0D, padding)
                + Math.max(0.0D, opposite) - MIN_PREVIEW_SIZE);
        return shrink <= available ? padding : padding * (available / shrink);
    }

    private static float getSupportedLineWidth(float preferred) {
        try {
            FloatBuffer range = org.lwjgl.BufferUtils.createFloatBuffer(16);
            // GL_LINE_WIDTH_RANGE is 0x0B22; LWJGL 2 does not expose its named constant.
            GL11.glGetFloat(0x0B22, range);
            float minimum = Math.max(1.0F, range.get(0));
            float maximum = Math.max(minimum, range.get(1));
            return Math.max(minimum, Math.min(preferred, maximum));
        } catch (Throwable ignored) {
            return preferred;
        }
    }

    public static boolean isRektFinisherStand(EntityArmorStand stand) {
        try {
            if (!stand.hasCustomName()) {
                return false;
            }
            String customName = stand.getCustomNameTag();
            if (customName == null || customName.indexOf('#') < 0) {
                return false;
            }
            String name = EnumChatFormatting.getTextWithoutFormattingCodes(customName);
            return name != null && REKT_FINISHER_LABEL.matcher(name.trim()).matches();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void addBoxEdges(WorldRenderer wr, AxisAlignedBB box,
                                    float red, float green, float blue, float alpha) {
        double x1 = box.minX - OUTLINE_EXPANSION;
        double y1 = box.minY - OUTLINE_EXPANSION;
        double z1 = box.minZ - OUTLINE_EXPANSION;
        double x2 = box.maxX + OUTLINE_EXPANSION;
        double y2 = box.maxY + OUTLINE_EXPANSION;
        double z2 = box.maxZ + OUTLINE_EXPANSION;
        addEdge(wr, x1, y1, z1, x2, y1, z1, red, green, blue, alpha);
        addEdge(wr, x1, y1, z1, x1, y1, z2, red, green, blue, alpha);
        addEdge(wr, x2, y1, z1, x2, y1, z2, red, green, blue, alpha);
        addEdge(wr, x1, y1, z2, x2, y1, z2, red, green, blue, alpha);
        addEdge(wr, x1, y2, z1, x2, y2, z1, red, green, blue, alpha);
        addEdge(wr, x1, y2, z1, x1, y2, z2, red, green, blue, alpha);
        addEdge(wr, x2, y2, z1, x2, y2, z2, red, green, blue, alpha);
        addEdge(wr, x1, y2, z2, x2, y2, z2, red, green, blue, alpha);
        addEdge(wr, x1, y1, z1, x1, y2, z1, red, green, blue, alpha);
        addEdge(wr, x2, y1, z1, x2, y2, z1, red, green, blue, alpha);
        addEdge(wr, x1, y1, z2, x1, y2, z2, red, green, blue, alpha);
        addEdge(wr, x2, y1, z2, x2, y2, z2, red, green, blue, alpha);
    }

    private static void addOrientedBoxEdges(WorldRenderer wr, PreviewBox box,
                                            float red, float green, float blue, float alpha) {
        addOrientedBoxEdges(wr, box, OUTLINE_EXPANSION, red, green, blue, alpha);
    }

    private static void addOrientedBoxEdges(WorldRenderer wr, PreviewBox box, double expansion,
                                            float red, float green, float blue, float alpha) {
        double r1 = box.minRight - expansion, r2 = box.maxRight + expansion;
        double y1 = box.minY - expansion, y2 = box.maxY + expansion;
        double f1 = -box.halfDepth - expansion, f2 = box.halfDepth + expansion;
        addOrientedEdge(wr, box, r1, y1, f1, r2, y1, f1, red, green, blue, alpha);
        addOrientedEdge(wr, box, r1, y1, f1, r1, y1, f2, red, green, blue, alpha);
        addOrientedEdge(wr, box, r2, y1, f1, r2, y1, f2, red, green, blue, alpha);
        addOrientedEdge(wr, box, r1, y1, f2, r2, y1, f2, red, green, blue, alpha);
        addOrientedEdge(wr, box, r1, y2, f1, r2, y2, f1, red, green, blue, alpha);
        addOrientedEdge(wr, box, r1, y2, f1, r1, y2, f2, red, green, blue, alpha);
        addOrientedEdge(wr, box, r2, y2, f1, r2, y2, f2, red, green, blue, alpha);
        addOrientedEdge(wr, box, r1, y2, f2, r2, y2, f2, red, green, blue, alpha);
        addOrientedEdge(wr, box, r1, y1, f1, r1, y2, f1, red, green, blue, alpha);
        addOrientedEdge(wr, box, r2, y1, f1, r2, y2, f1, red, green, blue, alpha);
        addOrientedEdge(wr, box, r1, y1, f2, r1, y2, f2, red, green, blue, alpha);
        addOrientedEdge(wr, box, r2, y1, f2, r2, y2, f2, red, green, blue, alpha);
    }

    private static void addOrientedEdge(WorldRenderer wr, PreviewBox box,
                                         double r1, double y1, double f1, double r2, double y2, double f2,
                                         float red, float green, float blue, float alpha) {
        double x1 = box.centerX + box.rightX * r1 + box.forwardX * f1;
        double z1 = box.centerZ + box.rightZ * r1 + box.forwardZ * f1;
        double x2 = box.centerX + box.rightX * r2 + box.forwardX * f2;
        double z2 = box.centerZ + box.rightZ * r2 + box.forwardZ * f2;
        addDashedEdge(wr, x1, y1, z1, x2, y2, z2, red, green, blue, alpha);
    }

    private static void addEdge(WorldRenderer wr, double x1, double y1, double z1,
                                double x2, double y2, double z2,
                                float red, float green, float blue, float alpha) {
        addDashedEdge(wr, x1, y1, z1, x2, y2, z2, red, green, blue, alpha);
    }

    /** Same 0.12-block dash / 0.08-block gap cadence used by Player Outline. */
    private static void addDashedEdge(WorldRenderer wr,
                                      double x1, double y1, double z1, double x2, double y2, double z2,
                                      float red, float green, float blue, float alpha) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0E-6D) return;

        double ux = dx / length;
        double uy = dy / length;
        double uz = dz / length;
        double pos = 0.0D;
        boolean draw = true;
        while (pos < length - 1.0E-6D) {
            double segment = draw ? DASH_LEN : GAP_LEN;
            double next = Math.min(pos + segment, length);
            if (draw) {
                addVertex(wr, x1 + ux * pos, y1 + uy * pos, z1 + uz * pos, red, green, blue, alpha);
                addVertex(wr, x1 + ux * next, y1 + uy * next, z1 + uz * next, red, green, blue, alpha);
            }
            pos = next;
            draw = !draw;
        }
    }

    private static void addVertex(WorldRenderer wr, double x, double y, double z,
                                  float red, float green, float blue, float alpha) {
        wr.pos(x, y, z).color(red, green, blue, alpha).endVertex();
    }

    private static final class RenderBox {
        private final AxisAlignedBB bounds;
        private final PreviewBox preview;

        private RenderBox(AxisAlignedBB bounds, PreviewBox preview) {
            this.bounds = bounds;
            this.preview = preview;
        }
    }

    private static final class PreviewBox {
        private final double centerX;
        private final double centerZ;
        private final double rightX;
        private final double rightZ;
        private final double forwardX;
        private final double forwardZ;
        private final double minRight;
        private final double maxRight;
        private final double minY;
        private final double maxY;
        private final double halfDepth;

        private PreviewBox(double centerX, double centerZ, double rightX, double rightZ,
                           double forwardX, double forwardZ, double minRight, double maxRight,
                           double minY, double maxY, double halfDepth) {
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.rightX = rightX;
            this.rightZ = rightZ;
            this.forwardX = forwardX;
            this.forwardZ = forwardZ;
            this.minRight = minRight;
            this.maxRight = maxRight;
            this.minY = minY;
            this.maxY = maxY;
            this.halfDepth = halfDepth;
        }

        private PreviewBox offset(double x, double y, double z) {
            return new PreviewBox(centerX + x, centerZ + z, rightX, rightZ,
                    forwardX, forwardZ, minRight, maxRight, minY + y, maxY + y, halfDepth);
        }
    }
}
