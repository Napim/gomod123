package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.BlitzRektHitboxOverlay;
import me.napim.gomod.features.GomodFixes;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.Vec3;
import net.weavemc.loader.api.command.Command;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Captures nearby #rekt stands, adjacent entities, bounds, targeting, and current render state. */
public final class BlitzRektHitboxDebugCommand extends Command {

    private static final double MAX_STAND_DISTANCE_SQ = 64.0D * 64.0D;
    private static final double NEARBY_ENTITY_DISTANCE_SQ = 4.0D * 4.0D;

    public BlitzRektHitboxDebugCommand() {
        super("rektdebug");
    }

    @Override
    public void handle(String[] args) {
        if (args != null && args.length > 0) {
            String action = args[0].toLowerCase(Locale.ROOT);
            if ("help".equals(action)) {
                sendHelp();
                return;
            }
            if ("status".equals(action)) {
                sendStatus();
                return;
            }
            if ("reset".equals(action)) {
                GomodFixes.resetBlitzRektHitboxPreviewPadding();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                        + "Reset #rekt preview padding. Orange is the base outline; cyan appears after adjustments.");
                return;
            }
            if ("adjust".equals(action)) {
                adjustPadding(args);
                return;
            }
            if (!(args.length == 1 && "near".equals(action))) {
                sendHelp();
                return;
            }
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Join a world, then run /rektdebug near the #rekt hologram.");
            return;
        }

        try {
            StringBuilder dump = new StringBuilder(8192);
            dump.append("Gomod Blitz #rekt hitbox debug\n")
                    .append("Captured: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.ROOT)
                            .format(new Date())).append('\n')
                    .append("Overlay enabled: ").append(GomodFixes.isBlitzRektHitboxOverlayEnabled()).append('\n')
                    .append("Calibration padding (blocks): ").append(formatPaddingStatus()).append('\n')
                    .append("Preview sides: left/right follow player facing; cyan is preview, orange is collision clearance.\n")
                    .append("Cyan width is measured along facing; previewBB below is its world-axis envelope.\n")
                    .append("World loaded entities: ").append(mc.theWorld.loadedEntityList.size()).append('\n')
                    .append("Player position: ").append(formatPosition(mc.thePlayer)).append('\n')
                    .append("Player view: yaw=").append(mc.thePlayer.rotationYaw)
                    .append(" pitch=").append(mc.thePlayer.rotationPitch)
                    .append(" look=").append(formatVector(mc.thePlayer.getLook(1.0F))).append('\n')
                    .append("Crosshair target: ").append(formatTarget(mc)).append('\n')
                    .append("Bounds: targetBB uses the aiming border; fishingHookBB uses 0.3; overlayBB covers both.\n")
                    .append("Hitbox render pass state: ")
                    .append(BlitzRektHitboxOverlay.getDebugRenderStateSummary()).append('\n')
                    .append("OpenGL state at command time: ").append(readGlState()).append("\n\n");

            List<EntityArmorStand> stands = new ArrayList<EntityArmorStand>();
            for (Object value : mc.theWorld.loadedEntityList) {
                if (!(value instanceof EntityArmorStand)) continue;
                EntityArmorStand stand = (EntityArmorStand) value;
                if (!stand.isDead && stand.getDistanceSqToEntity(mc.thePlayer) <= MAX_STAND_DISTANCE_SQ
                        && BlitzRektHitboxOverlay.isRektFinisherStand(stand)) {
                    stands.add(stand);
                }
            }

            if (stands.isEmpty()) {
                dump.append("No exact '<name> has #rekt <name> here' armor-stand label found within 64 blocks.\n")
                        .append("Armor stands within 12 blocks:\n");
                appendNearbyArmorStands(dump, mc);
            } else {
                dump.append("Matched #rekt label stands: ").append(stands.size()).append("\n\n");
                int index = 1;
                for (EntityArmorStand stand : stands) {
                    dump.append("=== #rekt label ").append(index++).append(" ===\n");
                    appendEntity(dump, stand);
                    dump.append("Nearby entities within 4 blocks of this stand:\n");
                    int nearbyCount = 0;
                    for (Object value : mc.theWorld.loadedEntityList) {
                        if (!(value instanceof Entity)) continue;
                        Entity entity = (Entity) value;
                        if (entity == stand || distanceSq(entity, stand) > NEARBY_ENTITY_DISTANCE_SQ) continue;
                        appendEntity(dump, entity);
                        nearbyCount++;
                    }
                    if (nearbyCount == 0) dump.append("  <none>\n");
                    dump.append('\n');
                }
            }

            File debugDir = new File(GomodPaths.getGomod123Dir(), "debug");
            if (!debugDir.isDirectory() && !debugDir.mkdirs() && !debugDir.isDirectory()) {
                throw new IllegalStateException("Could not create " + debugDir.getAbsolutePath());
            }
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
            File output = new File(debugDir, "rekt-hitbox-" + stamp + ".log");
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(output), StandardCharsets.UTF_8)) {
                writer.write(dump.toString());
            }
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Saved #rekt bounds debug: "
                    + EnumChatFormatting.WHITE + output.getAbsolutePath());
        } catch (Throwable failure) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Could not write #rekt debug: "
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            System.err.println("[gomod] Could not write Blitz #rekt hitbox debug");
            failure.printStackTrace();
        }
    }

    private static void appendNearbyArmorStands(StringBuilder dump, Minecraft mc) {
        int count = 0;
        for (Object value : mc.theWorld.loadedEntityList) {
            if (!(value instanceof EntityArmorStand)) continue;
            EntityArmorStand stand = (EntityArmorStand) value;
            if (stand.isDead || stand.getDistanceSqToEntity(mc.thePlayer) > 12.0D * 12.0D) continue;
            appendEntity(dump, stand);
            count++;
        }
        if (count == 0) dump.append("  <none>\n");
    }

    private static void appendEntity(StringBuilder dump, Entity entity) {
        dump.append("  id=").append(entity.getEntityId())
                .append(" class=").append(entity.getClass().getName())
                .append(" name=").append(clean(entity.getName()))
                .append(" customName=").append(clean(entity.hasCustomName() ? entity.getCustomNameTag() : "<none>"))
                .append(" customNameVisible=").append(entity.getAlwaysRenderNameTag())
                .append(" collidable=").append(safeCanCollide(entity))
                .append(" invisible=").append(entity.isInvisible())
                .append(" dead=").append(entity.isDead)
                .append(" size=").append(entity.width).append('x').append(entity.height)
                .append(" collisionBorder=").append(entity.getCollisionBorderSize())
                .append(" pos=").append(formatPosition(entity))
                .append(" entityBB=").append(formatBox(entity.getEntityBoundingBox()))
                .append(" targetBB=").append(formatTargetBox(entity));
        if (entity instanceof EntityArmorStand
                && BlitzRektHitboxOverlay.isRektFinisherStand((EntityArmorStand) entity)) {
            AxisAlignedBB bounds = entity.getEntityBoundingBox();
            double hookBorder = BlitzRektHitboxOverlay.FISHING_HOOK_COLLISION_BORDER;
            dump.append(" fishingHookBB=").append(formatBox(bounds == null ? null
                    : bounds.expand(hookBorder, hookBorder, hookBorder)))
                    .append(" overlayBB=").append(formatBox(BlitzRektHitboxOverlay.expandBlockingBounds(
                            bounds, entity.getCollisionBorderSize())))
                    .append(" previewBB=").append(formatBox(BlitzRektHitboxOverlay.applyPreviewPadding(
                            BlitzRektHitboxOverlay.expandBlockingBounds(bounds, entity.getCollisionBorderSize()),
                            Minecraft.getMinecraft().thePlayer.rotationYaw)))
                    .append(" previewWidthX=").append(String.format(Locale.ROOT, "%.4f",
                            BlitzRektHitboxOverlay.getPreviewFacingWidth(
                                    BlitzRektHitboxOverlay.expandBlockingBounds(bounds, entity.getCollisionBorderSize()),
                                    Minecraft.getMinecraft().thePlayer.rotationYaw)))
                    .append(" previewDepthZ=").append(String.format(Locale.ROOT, "%.4f",
                            BlitzRektHitboxOverlay.getPreviewFacingDepth(
                                    BlitzRektHitboxOverlay.expandBlockingBounds(bounds, entity.getCollisionBorderSize()),
                                    Minecraft.getMinecraft().thePlayer.rotationYaw)));
        }
        dump.append('\n');
    }

    private static boolean safeCanCollide(Entity entity) {
        try {
            return entity.canBeCollidedWith();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static double distanceSq(Entity first, Entity second) {
        double dx = first.posX - second.posX;
        double dy = first.posY - second.posY;
        double dz = first.posZ - second.posZ;
        return dx * dx + dy * dy + dz * dz;
    }

    private static String formatTarget(Minecraft mc) {
        if (mc.objectMouseOver == null) return "<none>";
        Entity target = mc.objectMouseOver.entityHit;
        String hit = " hitVec=" + formatVector(mc.objectMouseOver.hitVec);
        if (target == null) return "type=" + mc.objectMouseOver.typeOfHit + " entity=<none>" + hit;
        return "type=" + mc.objectMouseOver.typeOfHit + " entity=" + target.getClass().getSimpleName()
                + "#" + target.getEntityId() + " name=" + clean(target.getName()) + hit;
    }

    private static String readGlState() {
        try {
            return "activeTexture=0x" + Integer.toHexString(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE))
                    + " texture2D=" + GL11.glIsEnabled(GL11.GL_TEXTURE_2D)
                    + " blend=" + GL11.glIsEnabled(GL11.GL_BLEND)
                    + " depth=" + GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
                    + " depthFunc=0x" + Integer.toHexString(GL11.glGetInteger(GL11.GL_DEPTH_FUNC))
                    + " depthWrite=" + GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
                    + " lineWidth=" + GL11.glGetFloat(GL11.GL_LINE_WIDTH);
        } catch (Throwable failure) {
            return "unavailable: " + failure.getClass().getSimpleName();
        }
    }

    private static String formatPosition(Entity entity) {
        return String.format(Locale.ROOT, "(%.4f, %.4f, %.4f)", entity.posX, entity.posY, entity.posZ);
    }

    private static String formatVector(Vec3 vector) {
        return vector == null ? "<null>" : String.format(Locale.ROOT, "(%.4f, %.4f, %.4f)",
                vector.xCoord, vector.yCoord, vector.zCoord);
    }

    private static String formatBox(AxisAlignedBB box) {
        return box == null ? "<null>" : String.format(Locale.ROOT,
                "(%.4f, %.4f, %.4f)-(%.4f, %.4f, %.4f)",
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private static String formatTargetBox(Entity entity) {
        AxisAlignedBB box = entity.getEntityBoundingBox();
        if (box == null) return "<null>";
        double border = Math.max(0.0D, entity.getCollisionBorderSize());
        return formatBox(box.expand(border, border, border));
    }

    private static String clean(String text) {
        if (text == null) return "<null>";
        return text.replace('\n', ' ').replace('\r', ' ');
    }

    private static void adjustPadding(String[] args) {
        if (args.length != 3) {
            sendHelp();
            return;
        }
        String side = args[1].toLowerCase(Locale.ROOT);
        if (!("left".equals(side) || "right".equals(side) || "up".equals(side) || "down".equals(side)
                || "width".equals(side) || "height".equals(side))) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "Use width, height, left, right, up, or down.");
            return;
        }
        final double delta;
        try {
            delta = Double.parseDouble(args[2]);
            if (Double.isNaN(delta) || Double.isInfinite(delta)) throw new NumberFormatException();
        } catch (NumberFormatException invalid) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "Adjustment must be a finite number of blocks, such as 0.10 or -0.10.");
            return;
        }

        double value = GomodFixes.adjustBlitzRektHitboxPreviewPadding(side, delta);
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA + "#rekt preview " + side + " adjustment from base: "
                + EnumChatFormatting.WHITE + String.format(Locale.ROOT, "%.3f", value) + " blocks"
                + EnumChatFormatting.GRAY + " (cyan preview; positive expands, negative shrinks)");
        if ("width".equals(side) || "height".equals(side)) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                    + "The amount is split equally between both sides; minimum preview size is 0.01 blocks.");
        }
    }

    private static void sendStatus() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA + "#rekt hitbox: "
                + (GomodFixes.isBlitzRektHitboxOverlayEnabled()
                ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF")
                + EnumChatFormatting.GRAY + " | orange=collision clearance, cyan=adjusted preview");
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                + "Preview padding in blocks: " + formatPaddingStatus());
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                + "Left/right follow your facing direction; width stays consistent at diagonal angles.");
    }

    private static void sendHelp() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA
                + "/rektdebug adjust <width|height|left|right|up|down> <blocks>");
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                + "Positive adjustments expand the cyan preview; negative adjustments shrink that side.");
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                + "Examples: /rektdebug adjust width -0.10 or /rektdebug adjust height -0.10.");
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                + "Width/height change the total size equally on both sides. Facing-relative preview stays aligned at diagonal angles.");
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                + "Also: /rektdebug status, reset, or near.");
    }

    private static String formatPaddingStatus() {
        return "left=" + formatPadding("left") + " right=" + formatPadding("right")
                + " up=" + formatPadding("up") + " down=" + formatPadding("down")
                + " | width=" + formatPadding("width") + " height=" + formatPadding("height");
    }

    private static String formatPadding(String side) {
        return String.format(Locale.ROOT, "%.3f", GomodFixes.getBlitzRektHitboxPreviewPadding(side));
    }
}
