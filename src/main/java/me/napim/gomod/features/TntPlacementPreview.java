package me.napim.gomod.features;

import me.napim.gomod.Main;
import me.napim.gomod.mixins.RenderManagerAccessor;
import me.napim.gomod.util.GameContext;
import me.napim.gomod.util.HudGlState;
import net.minecraft.block.Block;
import net.minecraft.block.BlockFence;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityTNTPrimed;
import net.minecraft.entity.passive.EntityChicken;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.BiomeGenBase;
import org.lwjgl.opengl.GL11;

import java.util.HashSet;
import java.util.Set;

/**
 * Ghost preview for Hypixel-style TNT placement (offset from block origin, not vanilla center).
 */
public final class TntPlacementPreview {

    /** {@link net.minecraft.entity.item.EntityTNTPrimed} width/height in 1.8. */
    private static final double TNT_ENTITY_SIZE = 0.98D;
    private static final float DEFAULT_GHOST_ALPHA = 0.7f;
    private static final IBlockState TNT_STATE = Blocks.tnt.getDefaultState();
    private static final BlockPos RENDER_ORIGIN = new BlockPos(0, 0, 0);

    /** Ghost opacity 0–1; tune with {@code /tntplacementdebug opacity}. */
    private static float ghostAlpha = DEFAULT_GHOST_ALPHA;

    /** Hypixel placement offset from {@code placePos} block origin (see {@code /tntplacementdebug offset}). */
    public static final double DEFAULT_OFFSET_X = -0.5D;
    public static final double DEFAULT_OFFSET_Y = 0.0D;
    public static final double DEFAULT_OFFSET_Z = -0.5D;

    /** {@code /tntplacementdebug} — client-place primed TNT like Hypixel (singleplayer / any world). */
    private static boolean debugMode = false;
    private static boolean previewForcedOnByDebug = false;
    private static double cornerOffsetX = DEFAULT_OFFSET_X;
    private static double cornerOffsetY = DEFAULT_OFFSET_Y;
    private static double cornerOffsetZ = DEFAULT_OFFSET_Z;
    private static EntityLivingBase previewMob;
    private static World previewMobWorld;
    private static MobPreviewKind previewMobKind;
    private static boolean renderingMobGhost;

    private TntPlacementPreview() {}

    public static boolean isDebugMode() {
        return debugMode;
    }

    public static void setDebugMode(boolean enabled) {
        if (enabled == debugMode) {
            return;
        }
        debugMode = enabled;
        if (enabled) {
            if (!isEnabled()) {
                setEnabled(true);
                previewForcedOnByDebug = true;
            }
        } else if (previewForcedOnByDebug) {
            setEnabled(false);
            previewForcedOnByDebug = false;
        }
    }

    public static double getCornerOffsetX() {
        return cornerOffsetX;
    }

    public static double getCornerOffsetY() {
        return cornerOffsetY;
    }

    public static double getCornerOffsetZ() {
        return cornerOffsetZ;
    }

    public static void setCornerOffsets(double x, double y, double z) {
        cornerOffsetX = x;
        cornerOffsetY = y;
        cornerOffsetZ = z;
    }

    public static void resetCornerOffsets() {
        cornerOffsetX = DEFAULT_OFFSET_X;
        cornerOffsetY = DEFAULT_OFFSET_Y;
        cornerOffsetZ = DEFAULT_OFFSET_Z;
    }

    public static float getGhostAlpha() {
        return ghostAlpha;
    }

    public static void setGhostAlpha(float alpha) {
        ghostAlpha = Math.max(0.05f, Math.min(1.0f, alpha));
    }

    public static void resetGhostAlpha() {
        ghostAlpha = DEFAULT_GHOST_ALPHA;
    }

    /**
     * Client-only Hypixel-style primed TNT when debug mode is on. Returns true if handled.
     */
    public static boolean tryDebugPlace(EntityPlayer player, WorldClient world, ItemStack held,
                                        BlockPos clicked, EnumFacing side) {
        if (!debugMode || player == null || world == null || held == null) {
            return false;
        }
        // /gogive stacks are handled separately; debug place is for plain TNT from /tntplacementdebug tnt.
        if (isGogiveClientStack(held)) {
            return false;
        }
        if (!isHoldingTnt(held)) {
            return false;
        }
        if (clicked == null || side == null) {
            return false;
        }
        Placement placement = placementForBlockPos(player, clicked.offset(side), ignoreForClicked(clicked));
        if (placement == null) {
            return false;
        }
        spawnPrimedTnt(world, player, placement);
        if (!player.capabilities.isCreativeMode) {
            held.stackSize--;
            if (held.stackSize <= 0) {
                player.inventory.setInventorySlotContents(player.inventory.currentItem, null);
            }
        }
        sendPlacementChat(placement, true);
        return true;
    }

    public static void printLookInfo(EntityPlayer player) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || player == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Not in world.");
            return;
        }
        Placement placement = resolvePlacement(mc, player);
        if (placement == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "No valid TNT placement at crosshair.");
            return;
        }
        sendPlacementChat(placement, false);
    }

    private static void spawnPrimedTnt(WorldClient world, EntityPlayer player, Placement placement) {
        EntityTNTPrimed primed = new EntityTNTPrimed(world, placement.centerX, placement.centerY, placement.centerZ, player);
        primed.fuse = 80;
        world.spawnEntityInWorld(primed);
        player.playSound("game.tnt.primed", 1.0F, 1.0F);
    }

    private static void sendPlacementChat(Placement placement, boolean placed) {
        String verb = placed ? "Placed" : "Preview";
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + verb + EnumChatFormatting.GRAY
                + " — min " + EnumChatFormatting.WHITE + formatVec(placement.minX, placement.minY, placement.minZ)
                + EnumChatFormatting.GRAY + " | center " + EnumChatFormatting.WHITE
                + formatVec(placement.centerX, placement.centerY, placement.centerZ)
                + EnumChatFormatting.GRAY + " | offset " + EnumChatFormatting.WHITE
                + formatVec(cornerOffsetX, cornerOffsetY, cornerOffsetZ));
    }

    private static String formatVec(double x, double y, double z) {
        return String.format("%.3f, %.3f, %.3f", x, y, z);
    }

    public static boolean isEnabled() {
        return ConfigManager.isTntPlacementPreviewEnabled();
    }

    public static void setEnabled(boolean enabled) {
        ConfigManager.setTntPlacementPreviewEnabled(enabled);
    }

    /** Placement overlays: Hypixel only, unless {@link #debugMode} is on for local tuning. */
    private static boolean shouldRenderPreview(Minecraft mc) {
        return debugMode || GameContext.isOnHypixel(mc);
    }

    public static void render(float partialTicks) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!shouldRenderPreview(mc)) {
            return;
        }
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        if (mc.currentScreen != null) {
            return;
        }
        ItemStack held = mc.thePlayer.getHeldItem();
        if (!isHoldingTnt(held)) {
            renderMobPreview(mc, held, partialTicks);
            return;
        }
        if (!isEnabled()) {
            return;
        }
        Placement placement = resolvePlacement(mc, mc.thePlayer);
        if (placement == null) {
            return;
        }
        Entity cam = mc.getRenderViewEntity();
        if (cam == null) {
            return;
        }
        BlockRendererDispatcher dispatcher = mc.getBlockRendererDispatcher();
        if (dispatcher == null) {
            return;
        }

        double camX = cam.lastTickPosX + (cam.posX - cam.lastTickPosX) * partialTicks;
        double camY = cam.lastTickPosY + (cam.posY - cam.lastTickPosY) * partialTicks;
        double camZ = cam.lastTickPosZ + (cam.posZ - cam.lastTickPosZ) * partialTicks;

        float alpha = ghostAlpha;
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();

        // Lunar/Weave can leave the lightmap texture unit active before late-world overlays.
        // The TNT atlas hook binds/uploads immediately, so make sure it touches the block atlas.
        try {
            HudGlState.restoreBeforeWorldText();
        } catch (Throwable ignored) {
        }

        GlStateManager.pushMatrix();
        boolean attribPushed = false;
        TntPlacementPreviewAtlasHook.begin(alpha);
        TntPlacementPreviewGhostVertex.begin(alpha);
        try {
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            attribPushed = true;
            GlStateManager.disableLighting();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.enableDepth();
            GlStateManager.depthMask(true);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GlStateManager.enableTexture2D();
            GlStateManager.disableAlpha();
            GlStateManager.enableCull();
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

            GlStateManager.pushMatrix();
            try {
                GlStateManager.translate(-camX, -camY, -camZ);
                GlStateManager.translate(placement.minX, placement.minY, placement.minZ);
                GlStateManager.scale((float) TNT_ENTITY_SIZE, (float) TNT_ENTITY_SIZE, (float) TNT_ENTITY_SIZE);
                wr.setTranslation(0.0, 0.0, 0.0);
                wr.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
                try {
                    GhostBlockAccess ghostWorld = new GhostBlockAccess(mc.theWorld, placement);
                    IBakedModel model = dispatcher.getModelFromBlockState(TNT_STATE, ghostWorld, RENDER_ORIGIN);
                    if (model != null) {
                        dispatcher.getBlockModelRenderer().renderModel(
                                ghostWorld, model, TNT_STATE, RENDER_ORIGIN, wr, false);
                    } else {
                        dispatcher.renderBlock(TNT_STATE, RENDER_ORIGIN, ghostWorld, wr);
                    }
                } finally {
                    tess.draw();
                }
            } finally {
                GlStateManager.popMatrix();
            }
        } finally {
            TntPlacementPreviewGhostVertex.end();
            TntPlacementPreviewAtlasHook.end();
            restoreGlAfterPreview(attribPushed);
            GlStateManager.popMatrix();
            if (mc.getFramebuffer() != null) {
                mc.getFramebuffer().bindFramebuffer(false);
            }
        }
    }

    private static void renderMobPreview(Minecraft mc, ItemStack held, float partialTicks) {
        MobPreviewKind kind = resolveHeldMobPreviewKind(held);
        if (kind == null || !kind.isEnabled()) {
            return;
        }
        MobPlacement placement = resolveMobPlacement(mc);
        if (placement == null) {
            return;
        }
        EntityLivingBase mob = getOrCreatePreviewMob(mc.theWorld, kind);
        RenderManager rm = mc.getRenderManager();
        if (mob == null || rm == null) {
            return;
        }

        float yaw = mc.thePlayer.rotationYaw;
        mob.setLocationAndAngles(placement.x, placement.y, placement.z, yaw, 0.0F);
        mob.prevPosX = mob.lastTickPosX = placement.x;
        mob.prevPosY = mob.lastTickPosY = placement.y;
        mob.prevPosZ = mob.lastTickPosZ = placement.z;
        mob.prevRotationYaw = mob.rotationYaw = yaw;
        mob.prevRotationPitch = mob.rotationPitch = 0.0F;
        mob.prevRenderYawOffset = mob.renderYawOffset = yaw;
        mob.prevRotationYawHead = mob.rotationYawHead = yaw;

        double renderX;
        double renderY;
        double renderZ;
        try {
            RenderManagerAccessor accessor = (RenderManagerAccessor) rm;
            renderX = placement.x - accessor.gomod$getRenderPosX();
            renderY = placement.y - accessor.gomod$getRenderPosY();
            renderZ = placement.z - accessor.gomod$getRenderPosZ();
        } catch (Throwable ignored) {
            Entity camera = mc.getRenderViewEntity();
            if (camera == null) {
                return;
            }
            double camX = camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * partialTicks;
            double camY = camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * partialTicks;
            double camZ = camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * partialTicks;
            renderX = placement.x - camX;
            renderY = placement.y - camY;
            renderZ = placement.z - camZ;
        }

        GlStateManager.pushMatrix();
        boolean attribPushed = false;
        boolean lightmapEnabled = false;
        try {
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            attribPushed = true;
            renderingMobGhost = true;
            rm.setRenderShadow(false);
            EntityRenderer entityRenderer = mc.entityRenderer;
            if (entityRenderer != null) {
                entityRenderer.enableLightmap();
                lightmapEnabled = true;
            }
            GlStateManager.enableDepth();
            GlStateManager.depthMask(false);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.enableTexture2D();
            // GlStateManagerMixin applies ghostAlpha to every vanilla model/layer color reset in this scope.
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            rm.renderEntityWithPosYaw(mob, renderX, renderY, renderZ, yaw, partialTicks);
        } finally {
            renderingMobGhost = false;
            rm.setRenderShadow(true);
            if (lightmapEnabled && mc.entityRenderer != null) {
                mc.entityRenderer.disableLightmap();
            }
            restoreGlAfterPreview(attribPushed);
            GlStateManager.popMatrix();
            if (mc.getFramebuffer() != null) {
                mc.getFramebuffer().bindFramebuffer(false);
            }
        }
    }

    public static boolean isRenderingMobGhost() {
        return renderingMobGhost;
    }

    private static MobPlacement resolveMobPlacement(Minecraft mc) {
        MovingObjectPosition hit = mc == null ? null : mc.objectMouseOver;
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || hit.getBlockPos() == null || hit.sideHit == null || mc.theWorld == null) {
            return null;
        }
        BlockPos clicked = hit.getBlockPos();
        BlockPos spawn = clicked.offset(hit.sideHit);
        double yOffset = 0.0D;
        Block clickedBlock = mc.theWorld.getBlockState(clicked).getBlock();
        if (hit.sideHit == EnumFacing.UP && clickedBlock instanceof BlockFence) {
            yOffset = 0.5D;
        }
        // Hypixel anchors these at the same northwest position as primed TNT. Entity X/Z are centers,
        // so use the center of TNT's offset 0.98 footprint while keeping Y at the entity's feet.
        Placement tntAnchor = buildPlacement(spawn);
        return new MobPlacement(tntAnchor.centerX, tntAnchor.minY + yOffset, tntAnchor.centerZ);
    }

    private static EntityLivingBase getOrCreatePreviewMob(World world, MobPreviewKind kind) {
        if (world == null || kind == null) {
            return null;
        }
        if (previewMob != null && previewMobWorld == world && previewMobKind == kind) {
            return previewMob;
        }
        previewMobWorld = world;
        previewMobKind = kind;
        try {
            switch (kind) {
                case CHICKEN:
                    EntityChicken chicken = new EntityChicken(world);
                    chicken.setGrowingAge(0);
                    previewMob = chicken;
                    break;
                case PIG:
                    EntityPig pig = new EntityPig(world);
                    pig.setGrowingAge(0);
                    previewMob = pig;
                    break;
                case DONKEY:
                    EntityHorse donkey = new EntityHorse(world);
                    donkey.setHorseType(1);
                    donkey.setGrowingAge(0);
                    previewMob = donkey;
                    break;
                case HORSE:
                default:
                    EntityHorse horse = new EntityHorse(world);
                    horse.setHorseType(0);
                    horse.setGrowingAge(0);
                    previewMob = horse;
                    break;
            }
        } catch (Throwable ignored) {
            previewMob = null;
        }
        return previewMob;
    }

    private static MobPreviewKind resolveHeldMobPreviewKind(ItemStack stack) {
        if (stack == null || stack.getItem() != Items.spawn_egg) {
            return null;
        }
        int entityId = stack.getMetadata();
        String displayName = stack.getDisplayName();
        String plainName = EnumChatFormatting.getTextWithoutFormattingCodes(displayName);
        String lowerName = plainName == null ? "" : plainName.toLowerCase(java.util.Locale.ROOT);
        if (entityId == 93 || lowerName.contains("chicken")) {
            return MobPreviewKind.CHICKEN;
        }
        if (entityId == 90 || lowerName.contains("pig") && !lowerName.contains("pigman")
                && !lowerName.contains("zombie")) {
            return MobPreviewKind.PIG;
        }
        boolean donkeyData = false;
        try {
            NBTTagCompound root = stack.getTagCompound();
            if (root != null && root.hasKey("EntityTag", 10)) {
                donkeyData = root.getCompoundTag("EntityTag").getInteger("Type") == 1;
            }
        } catch (Throwable ignored) {
        }
        if (donkeyData || lowerName.contains("donkey")) {
            return MobPreviewKind.DONKEY;
        }
        if (entityId == 100 || lowerName.contains("horse")) {
            return MobPreviewKind.HORSE;
        }
        return null;
    }

    private enum MobPreviewKind {
        CHICKEN,
        PIG,
        HORSE,
        DONKEY;

        boolean isEnabled() {
            switch (this) {
                case CHICKEN:
                    return ConfigManager.isChickenPlacementPreviewEnabled();
                case PIG:
                    return ConfigManager.isPigPlacementPreviewEnabled();
                case DONKEY:
                    return ConfigManager.isDonkeyPlacementPreviewEnabled();
                case HORSE:
                default:
                    return ConfigManager.isHorsePlacementPreviewEnabled();
            }
        }
    }

    private static final class MobPlacement {
        final double x;
        final double y;
        final double z;

        MobPlacement(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static void restoreGlAfterPreview(boolean attribPopped) {
        if (attribPopped) {
            GL11.glPopAttrib();
        }
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
        GlStateManager.enableLighting();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        RenderHelper.disableStandardItemLighting();
    }

    static Placement resolvePlacement(Minecraft mc, EntityPlayer player) {
        MovingObjectPosition mop = mc.objectMouseOver;
        if (mop == null) {
            return null;
        }
        if (mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            BlockPos clicked = mop.getBlockPos();
            EnumFacing side = mop.sideHit;
            if (clicked == null || side == null) {
                return null;
            }
            Set<BlockPos> ignore = ignoreForClicked(clicked);
            BlockPos placePos = clicked.offset(side);
            Placement placement = placementForBlockPos(player, placePos, ignore);
            if (placement != null) {
                return placement;
            }
            // Grazing angles on TNT: vanilla sideHit can disagree with where the ray actually hit.
            if (mop.hitVec != null) {
                EnumFacing fromHit = facingFromBlockHit(clicked, mop.hitVec);
                if (fromHit != null && fromHit != side) {
                    placement = placementForBlockPos(player, clicked.offset(fromHit), ignore);
                    if (placement != null) {
                        return placement;
                    }
                }
                // Aiming at an air cell beside TNT: vanilla uses the solid neighbor as placePos (into the TNT).
                BlockPos hitCell = new BlockPos(mop.hitVec.xCoord, mop.hitVec.yCoord, mop.hitVec.zCoord);
                if (!hitCell.equals(placePos)) {
                    Set<BlockPos> hitIgnore = ignoreForClicked(clicked);
                    hitIgnore.add(clicked);
                    placement = placementForBlockPos(player, hitCell, hitIgnore);
                    if (placement != null) {
                        return placement;
                    }
                }
            }
            return null;
        }
        if (mop.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY && mop.entityHit != null) {
            return placementFromEntityHit(player, mop);
        }
        return null;
    }

    private static Set<BlockPos> ignoreForClicked(BlockPos clicked) {
        Set<BlockPos> ignore = new HashSet<BlockPos>();
        ignore.add(clicked);
        return ignore;
    }

    private static EnumFacing facingFromBlockHit(BlockPos block, Vec3 hit) {
        if (block == null || hit == null) {
            return null;
        }
        double lx = hit.xCoord - (block.getX() + 0.5D);
        double ly = hit.yCoord - (block.getY() + 0.5D);
        double lz = hit.zCoord - (block.getZ() + 0.5D);
        return facingFromDelta(lx, ly, lz);
    }

    /** Primed TNT / other entities: place in the air cell on the struck face. */
    private static Placement placementFromEntityHit(EntityPlayer player, MovingObjectPosition mop) {
        Entity hit = mop.entityHit;
        if (hit == null || mop.hitVec == null) {
            return null;
        }
        Set<BlockPos> ignore = blocksIntersecting(hit.getEntityBoundingBox());
        BlockPos faceBlock = new BlockPos(mop.hitVec);
        EnumFacing side = facingFromBlockHit(faceBlock, mop.hitVec);
        if (side == null) {
            double dx = mop.hitVec.xCoord - hit.posX;
            double dy = mop.hitVec.yCoord - (hit.posY + hit.height * 0.5D);
            double dz = mop.hitVec.zCoord - hit.posZ;
            side = facingFromDelta(dx, dy, dz);
        }
        if (side == null) {
            side = EnumFacing.UP;
        }
        ignore.add(faceBlock);
        return placementForBlockPos(player, faceBlock.offset(side), ignore);
    }

    private static Set<BlockPos> blocksIntersecting(AxisAlignedBB bb) {
        Set<BlockPos> out = new HashSet<BlockPos>();
        if (bb == null) {
            return out;
        }
        int x0 = MathHelper.floor_double(bb.minX);
        int y0 = MathHelper.floor_double(bb.minY);
        int z0 = MathHelper.floor_double(bb.minZ);
        int x1 = MathHelper.floor_double(bb.maxX);
        int y1 = MathHelper.floor_double(bb.maxY);
        int z1 = MathHelper.floor_double(bb.maxZ);
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    out.add(new BlockPos(x, y, z));
                }
            }
        }
        return out;
    }

    private static EnumFacing facingFromDelta(double dx, double dy, double dz) {
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);
        if (ay >= ax && ay >= az) {
            return dy > 0.0D ? EnumFacing.UP : EnumFacing.DOWN;
        }
        if (ax >= az) {
            return dx > 0.0D ? EnumFacing.EAST : EnumFacing.WEST;
        }
        return dz > 0.0D ? EnumFacing.SOUTH : EnumFacing.NORTH;
    }

    /** Hypixel-style placement for a target air cell (used by /gogive client TNT). */
    public static Placement placementForBlockPos(EntityPlayer player, BlockPos placePos, Set<BlockPos> ignoreBlocks) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || placePos == null) {
            return null;
        }
        if (!canPlaceAt(mc.theWorld, placePos, ignoreBlocks)) {
            return null;
        }
        return buildPlacement(placePos);
    }

    /** Spawn center for client-side /gogive primed TNT at the clicked face. */
    public static double[] gogivePrimedTntCenter(EntityPlayer player, BlockPos clicked, BlockPos placePos) {
        Set<BlockPos> ignore = new HashSet<BlockPos>();
        if (clicked != null) {
            ignore.add(clicked);
        }
        Placement placement = placementForBlockPos(player, placePos, ignore);
        if (placement != null) {
            return new double[] { placement.centerX, placement.centerY, placement.centerZ };
        }
        if (placePos == null) {
            return null;
        }
        return new double[] { placePos.getX() + 0.5D, placePos.getY() + 0.5D, placePos.getZ() + 0.5D };
    }

    private static Placement buildPlacement(BlockPos placePos) {
        double minX = placePos.getX() + cornerOffsetX;
        double minY = placePos.getY() + cornerOffsetY;
        double minZ = placePos.getZ() + cornerOffsetZ;
        double maxX = minX + TNT_ENTITY_SIZE;
        double maxY = minY + TNT_ENTITY_SIZE;
        double maxZ = minZ + TNT_ENTITY_SIZE;
        AxisAlignedBB box = new AxisAlignedBB(minX, minY, minZ, maxX, maxY, maxZ);
        double centerX = minX + TNT_ENTITY_SIZE * 0.5D;
        double centerY = minY + TNT_ENTITY_SIZE * 0.5D;
        double centerZ = minZ + TNT_ENTITY_SIZE * 0.5D;
        return new Placement(minX, minY, minZ, centerX, centerY, centerZ, box);
    }

    /**
     * Preview: target cell must be air/replaceable. The 0.98 corner box may overlap face-adjacent
     * support blocks (stone, TNT, etc.) — same as Hypixel placement against a block face.
     */
    private static boolean canPlaceAt(World world, BlockPos placePos, Set<BlockPos> ignoreBlocks) {
        if (world == null) {
            return false;
        }
        IBlockState atTarget = world.getBlockState(placePos);
        Block block = atTarget.getBlock();
        if (block == null) {
            return false;
        }
        if (!block.isReplaceable(world, placePos) && !world.isAirBlock(placePos)) {
            return false;
        }
        double minX = placePos.getX() + cornerOffsetX;
        double minY = placePos.getY() + cornerOffsetY;
        double minZ = placePos.getZ() + cornerOffsetZ;
        AxisAlignedBB box = new AxisAlignedBB(
                minX, minY, minZ,
                minX + TNT_ENTITY_SIZE, minY + TNT_ENTITY_SIZE, minZ + TNT_ENTITY_SIZE);
        Set<BlockPos> expandedIgnore = new HashSet<BlockPos>();
        if (ignoreBlocks != null) {
            expandedIgnore.addAll(ignoreBlocks);
        }
        addHypixelCornerSupportIgnores(placePos, expandedIgnore);
        return isBlockSpaceClear(world, placePos, box, expandedIgnore);
    }

    /**
     * Blocks the NW-corner offset can overlap: floor below and neighbors on the offset side(s).
     */
    private static void addHypixelCornerSupportIgnores(BlockPos placePos, Set<BlockPos> ignore) {
        if (placePos == null || ignore == null) {
            return;
        }
        ignore.add(placePos.down());
        if (cornerOffsetX < -0.001D) {
            ignore.add(placePos.west());
            if (cornerOffsetZ < -0.001D) {
                ignore.add(placePos.north().west());
            }
            if (cornerOffsetZ > 0.001D) {
                ignore.add(placePos.south().west());
            }
        } else if (cornerOffsetX > 0.001D) {
            ignore.add(placePos.east());
            if (cornerOffsetZ < -0.001D) {
                ignore.add(placePos.north().east());
            }
            if (cornerOffsetZ > 0.001D) {
                ignore.add(placePos.south().east());
            }
        }
        if (Math.abs(cornerOffsetX) <= 0.001D) {
            if (cornerOffsetZ < -0.001D) {
                ignore.add(placePos.north());
            }
            if (cornerOffsetZ > 0.001D) {
                ignore.add(placePos.south());
            }
        }
    }

    private static boolean isBlockSpaceClear(World world, BlockPos placePos, AxisAlignedBB box,
                                             Set<BlockPos> ignoreBlocks) {
        int x0 = MathHelper.floor_double(box.minX);
        int y0 = MathHelper.floor_double(box.minY);
        int z0 = MathHelper.floor_double(box.minZ);
        int x1 = MathHelper.floor_double(box.maxX);
        int y1 = MathHelper.floor_double(box.maxY);
        int z1 = MathHelper.floor_double(box.maxZ);
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    probe.set(x, y, z);
                    if (ignoreBlocks != null && ignoreBlocks.contains(probe)) {
                        continue;
                    }
                    if (isHypixelAdjacentOverlap(placePos, probe)) {
                        continue;
                    }
                    IBlockState state = world.getBlockState(probe);
                    Block block = state.getBlock();
                    if (block == null || block == Blocks.air) {
                        continue;
                    }
                    Material mat = block.getMaterial();
                    if (mat == null || !mat.isSolid()) {
                        continue;
                    }
                    AxisAlignedBB blockBox = block.getCollisionBoundingBox(world, probe, state);
                    if (blockBox != null && blockBox.intersectsWith(box)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Allow 0.98 box to overlap immediate neighbors of the target air cell (support / clicked face). */
    private static boolean isHypixelAdjacentOverlap(BlockPos placePos, BlockPos probe) {
        if (placePos == null || probe == null || placePos.equals(probe)) {
            return false;
        }
        int dx = Math.abs(probe.getX() - placePos.getX());
        int dy = Math.abs(probe.getY() - placePos.getY());
        int dz = Math.abs(probe.getZ() - placePos.getZ());
        return dx <= 1 && dy <= 1 && dz <= 1 && (dx + dy + dz) > 0;
    }

    private static boolean isHoldingTnt(ItemStack stack) {
        if (stack == null) {
            return false;
        }
        Item item = stack.getItem();
        return item != null && item == Item.getItemFromBlock(Blocks.tnt);
    }

    public static boolean isGogiveClientStack(ItemStack stack) {
        return stack != null && stack.hasTagCompound() && stack.getTagCompound().getBoolean("gomod_client");
    }

    private static final class GhostBlockAccess implements IBlockAccess {
        private final World world;
        private final BlockPos lightAnchor;

        GhostBlockAccess(World world, Placement placement) {
            this.world = world;
            this.lightAnchor = new BlockPos(placement.centerX, placement.centerY, placement.centerZ);
        }

        @Override
        public TileEntity getTileEntity(BlockPos pos) {
            return null;
        }

        @Override
        public int getCombinedLight(BlockPos pos, int lightValue) {
            if (world == null) {
                return 15728880;
            }
            try {
                return world.getCombinedLight(realPos(pos), lightValue);
            } catch (Throwable ignored) {
                return 15728880;
            }
        }

        @Override
        public IBlockState getBlockState(BlockPos pos) {
            return RENDER_ORIGIN.equals(pos) ? TNT_STATE : Blocks.air.getDefaultState();
        }

        @Override
        public boolean isAirBlock(BlockPos pos) {
            return !RENDER_ORIGIN.equals(pos);
        }

        @Override
        public BiomeGenBase getBiomeGenForCoords(BlockPos pos) {
            if (world == null) {
                return null;
            }
            try {
                return world.getBiomeGenForCoords(realPos(pos));
            } catch (Throwable ignored) {
                return null;
            }
        }

        @Override
        public boolean extendedLevelsInChunkCache() {
            try {
                return world != null && world.extendedLevelsInChunkCache();
            } catch (Throwable ignored) {
                return false;
            }
        }

        @Override
        public int getStrongPower(BlockPos pos, EnumFacing direction) {
            return 0;
        }

        @Override
        public WorldType getWorldType() {
            try {
                return world == null ? WorldType.DEFAULT : world.getWorldType();
            } catch (Throwable ignored) {
                return WorldType.DEFAULT;
            }
        }

        public boolean isSideSolid(BlockPos pos, EnumFacing side, boolean _default) {
            return false;
        }

        private BlockPos realPos(BlockPos local) {
            if (local == null) {
                return lightAnchor;
            }
            return lightAnchor.add(local.getX(), local.getY(), local.getZ());
        }
    }

    public static final class Placement {
        final double minX;
        final double minY;
        final double minZ;
        final double centerX;
        final double centerY;
        final double centerZ;
        final AxisAlignedBB box;

        Placement(double minX, double minY, double minZ, double centerX, double centerY, double centerZ,
                  AxisAlignedBB box) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
            this.box = box;
        }
    }
}
