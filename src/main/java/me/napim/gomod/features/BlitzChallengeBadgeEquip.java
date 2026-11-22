package me.napim.gomod.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.napim.gomod.gui.MountPreviewHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import me.napim.gomod.util.GomodClasspathTextures;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.util.Locale;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Client-side equipped Blitz challenge badge (unlocked when all tiers of a challenge are complete).
 * Badges attach to individual skin limbs (head, torso, arms, legs) and render flush on the surface.
 */
public final class BlitzChallengeBadgeEquip {

    public enum BadgeAttachPart {
        HEAD("head"),
        BODY("body"),
        RIGHT_ARM("right_arm"),
        LEFT_ARM("left_arm"),
        RIGHT_LEG("right_leg"),
        LEFT_LEG("left_leg");

        private final String key;

        BadgeAttachPart(String key) {
            this.key = key;
        }

        public String getKey() {
            return key;
        }

        public String getDisplayName() {
            switch (this) {
                case HEAD:
                    return "Head";
                case BODY:
                    return "Body";
                case RIGHT_ARM:
                    return "Right arm";
                case LEFT_ARM:
                    return "Left arm";
                case RIGHT_LEG:
                    return "Right leg";
                case LEFT_LEG:
                    return "Left leg";
                default:
                    return key;
            }
        }

        public static BadgeAttachPart fromKey(String raw) {
            if (raw == null) {
                return BODY;
            }
            String k = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            for (BadgeAttachPart part : values()) {
                if (part.key.equals(k)) {
                    return part;
                }
            }
            return BODY;
        }
    }

    public enum BadgeSkinLayer {
        INNER("inner", "Inner skin"),
        OUTER("outer", "Outer layer");

        private final String key;
        private final String displayName;

        BadgeSkinLayer(String key, String displayName) {
            this.key = key;
            this.displayName = displayName;
        }

        public String getKey() {
            return key;
        }

        public String getDisplayName() {
            return displayName;
        }

        public static BadgeSkinLayer fromKey(String raw) {
            return raw != null && "outer".equalsIgnoreCase(raw.trim()) ? OUTER : INNER;
        }
    }

    private static final float MIN_OFFSET_X = -0.55f;
    private static final float MAX_OFFSET_X = 0.55f;
    private static final float MIN_OFFSET_Y = -0.55f;
    private static final float MAX_OFFSET_Y = 0.55f;
    private static final float MIN_OFFSET_Z = 0.0f;
    private static final float MAX_OFFSET_Z = 0.03f;
    private static final float MIN_SCALE = 0.35f;
    private static final float MAX_SCALE = 1.85f;
    private static final float SKIN_SURFACE_Z = 0.0015f;
    private static final float BADGE_THICKNESS = 0.012f;

    private static boolean enabled;
    private static String equippedKey = "";
    private static BadgeAttachPart attachPart = BadgeAttachPart.BODY;
    private static float offsetX;
    private static float offsetY;
    private static float offsetZ;
    private static float badgeScale = 1.0f;
    private static BadgeSkinLayer skinLayer = BadgeSkinLayer.INNER;

    private static final List<BadgePlacement> placements = new ArrayList<BadgePlacement>();

    private static boolean guiDraftActive;
    private static boolean draftEnabled;
    private static String draftKey = "";
    private static BadgeAttachPart draftAttachPart = BadgeAttachPart.BODY;
    private static float draftOffsetX;
    private static float draftOffsetY;
    private static float draftOffsetZ;
    private static float draftBadgeScale = 1.0f;
    private static BadgeSkinLayer draftSkinLayer = BadgeSkinLayer.INNER;
    private static final List<BadgePlacement> draftPlacements = new ArrayList<BadgePlacement>();

    private static boolean badgeGuiOpen;
    private static float renderAlpha = 1.0f;

    private BlitzChallengeBadgeEquip() {
    }

    private static final class BadgePlacement {
        String key;
        BadgeAttachPart part;
        BadgeSkinLayer layer;
        float x;
        float y;
        float z;
        float scale;

        BadgePlacement(String key, BadgeAttachPart part, BadgeSkinLayer layer,
                       float x, float y, float z, float scale) {
            this.key = safeKey(key);
            this.part = part == null ? BadgeAttachPart.BODY : part;
            this.layer = layer == null ? BadgeSkinLayer.INNER : layer;
            this.x = clamp(x, MIN_OFFSET_X, MAX_OFFSET_X);
            this.y = clamp(y, MIN_OFFSET_Y, MAX_OFFSET_Y);
            this.z = clamp(z, MIN_OFFSET_Z, MAX_OFFSET_Z);
            this.scale = clamp(scale, MIN_SCALE, MAX_SCALE);
        }

        BadgePlacement copy() {
            return new BadgePlacement(key, part, layer, x, y, z, scale);
        }

        JsonObject toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("key", key);
            out.addProperty("part", part.getKey());
            out.addProperty("layer", layer.getKey());
            out.addProperty("x", x);
            out.addProperty("y", y);
            out.addProperty("z", z);
            out.addProperty("scale", scale);
            return out;
        }

        static BadgePlacement fromJson(JsonObject in) {
            if (in == null || !in.has("key")) return null;
            try {
                return new BadgePlacement(
                        in.get("key").getAsString(),
                        BadgeAttachPart.fromKey(in.has("part") ? in.get("part").getAsString() : "body"),
                        BadgeSkinLayer.fromKey(in.has("layer") ? in.get("layer").getAsString() : "inner"),
                        in.has("x") ? in.get("x").getAsFloat() : 0.0F,
                        in.has("y") ? in.get("y").getAsFloat() : 0.0F,
                        in.has("z") ? in.get("z").getAsFloat() : 0.0F,
                        in.has("scale") ? in.get("scale").getAsFloat() : 1.0F);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    public static float getRenderAlpha() {
        return renderAlpha;
    }

    public static void setRenderAlpha(float alpha) {
        renderAlpha = Math.max(0.08f, Math.min(1.0f, alpha));
    }

    public static void resetRenderAlpha() {
        renderAlpha = 1.0f;
    }

    public static void loadSetting() {
        enabled = ConfigManager.isBlitzChallengeBadgeEnabled();
        equippedKey = safeKey(ConfigManager.getBlitzChallengeBadgeEquippedKey());
        attachPart = BadgeAttachPart.fromKey(ConfigManager.getBlitzChallengeBadgeAttachPart());
        offsetX = ConfigManager.getBlitzChallengeBadgeOffsetX();
        offsetY = ConfigManager.getBlitzChallengeBadgeOffsetY();
        offsetZ = normalizeSurfaceOffsetZ(ConfigManager.getBlitzChallengeBadgeOffsetZ());
        badgeScale = clamp(ConfigManager.getBlitzChallengeBadgeScale(), MIN_SCALE, MAX_SCALE);
        skinLayer = BadgeSkinLayer.INNER;

        placements.clear();
        JsonArray saved = ConfigManager.getBlitzChallengeBadgePlacements();
        for (JsonElement element : saved) {
            if (element == null || !element.isJsonObject()) continue;
            BadgePlacement placement = BadgePlacement.fromJson(element.getAsJsonObject());
            if (placement != null && !placement.key.isEmpty() && findPlacement(placements, placement.key) == null) {
                placements.add(placement);
            }
        }

        boolean migratedLegacy = false;
        if (placements.isEmpty() && enabled && !equippedKey.isEmpty()) {
            placements.add(new BadgePlacement(equippedKey, attachPart, BadgeSkinLayer.INNER,
                    offsetX, offsetY, offsetZ, badgeScale));
            migratedLegacy = true;
        }
        removeLockedOrInvalidPlacements(placements);
        enabled = !placements.isEmpty();
        if (!placements.isEmpty()) {
            loadCommittedFields(placements.get(0));
        } else {
            equippedKey = "";
        }
        if (migratedLegacy) {
            persist();
        }
    }

    public static void setBadgeGuiOpen(boolean open) {
        badgeGuiOpen = open;
    }

    public static boolean isBadgeGuiOpen() {
        return badgeGuiOpen;
    }

    public static void beginGuiDraft() {
        copyPlacements(placements, draftPlacements);
        BadgePlacement selected = !equippedKey.isEmpty() ? findPlacement(draftPlacements, equippedKey) : null;
        if (selected == null && !draftPlacements.isEmpty()) selected = draftPlacements.get(0);
        if (selected != null) {
            loadDraftFields(selected);
        } else {
            draftEnabled = false;
            draftKey = "";
            loadDraftDefaults();
        }
        guiDraftActive = true;
        badgeGuiOpen = true;
    }

    public static void endGuiDraft(boolean apply) {
        if (apply) {
            commitDraft();
        }
        guiDraftActive = false;
        badgeGuiOpen = false;
        resetRenderAlpha();
    }

    public static void commitDraft() {
        syncCurrentDraftPlacement();
        removeLockedOrInvalidPlacements(draftPlacements);
        copyPlacements(draftPlacements, placements);
        enabled = !placements.isEmpty();
        BadgePlacement selected = findPlacement(placements, draftKey);
        if (selected == null && !placements.isEmpty()) selected = placements.get(0);
        if (selected != null) {
            loadCommittedFields(selected);
        } else {
            equippedKey = "";
        }
        persist();
    }

    public static boolean isEnabled() {
        return guiDraftActive ? draftEnabled && findPlacement(draftPlacements, draftKey) != null : !placements.isEmpty();
    }

    public static void setEnabled(boolean value) {
        if (guiDraftActive) {
            draftEnabled = value;
            if (value) {
                ensureCurrentDraftPlacement();
            } else {
                removePlacement(draftPlacements, draftKey);
            }
        } else {
            enabled = value;
            if (!value) {
                placements.clear();
                equippedKey = "";
            } else if (!equippedKey.isEmpty() && findPlacement(placements, equippedKey) == null) {
                placements.add(currentCommittedPlacement());
            }
            persist();
        }
    }

    public static String getEquippedKey() {
        return guiDraftActive ? safeKey(draftKey) : safeKey(equippedKey);
    }

    public static void setEquippedKey(String key) {
        String k = safeKey(key);
        if (guiDraftActive) {
            selectBadgeForEditing(k, true);
        } else {
            equippedKey = k;
            if (!k.isEmpty()) {
                enabled = true;
                BadgePlacement existing = findPlacement(placements, k);
                if (existing != null) {
                    loadCommittedFields(existing);
                } else {
                    placements.add(currentCommittedPlacement());
                }
            } else {
                enabled = !placements.isEmpty();
            }
            persist();
        }
    }

    public static BadgeAttachPart getAttachPart() {
        return guiDraftActive ? draftAttachPart : attachPart;
    }

    public static void setAttachPart(BadgeAttachPart part) {
        if (part == null) {
            part = BadgeAttachPart.BODY;
        }
        if (guiDraftActive) {
            draftAttachPart = part;
            syncCurrentDraftPlacement();
        } else {
            attachPart = part;
            syncCurrentCommittedPlacement();
            persist();
        }
    }

    public static float getOffsetX() {
        return guiDraftActive ? draftOffsetX : offsetX;
    }

    public static float getOffsetY() {
        return guiDraftActive ? draftOffsetY : offsetY;
    }

    public static float getOffsetZ() {
        return guiDraftActive ? draftOffsetZ : offsetZ;
    }

    public static float getBadgeScale() {
        return guiDraftActive ? draftBadgeScale : badgeScale;
    }

    public static BadgeSkinLayer getSkinLayer() {
        return guiDraftActive ? draftSkinLayer : skinLayer;
    }

    public static void setSkinLayer(BadgeSkinLayer layer) {
        if (layer == null) layer = BadgeSkinLayer.INNER;
        if (guiDraftActive) {
            draftSkinLayer = layer;
            syncCurrentDraftPlacement();
        } else {
            skinLayer = layer;
            syncCurrentCommittedPlacement();
            persist();
        }
    }

    /** Selects a badge in the editor without necessarily adding it to the equipped placement list. */
    public static void selectBadgeForEditing(String key, boolean equipIfMissing) {
        if (!guiDraftActive) return;
        syncCurrentDraftPlacement();
        String k = safeKey(key);
        draftKey = k;
        BadgePlacement existing = findPlacement(draftPlacements, k);
        if (existing != null) {
            loadDraftFields(existing);
            return;
        }
        loadDraftDefaults();
        draftKey = k;
        draftEnabled = equipIfMissing && !k.isEmpty();
        if (draftEnabled) ensureCurrentDraftPlacement();
    }

    public static void toggleSkinLayer() {
        setSkinLayer(getSkinLayer() == BadgeSkinLayer.INNER ? BadgeSkinLayer.OUTER : BadgeSkinLayer.INNER);
    }

    public static void setOffsetX(float value) {
        float v = clamp(value, MIN_OFFSET_X, MAX_OFFSET_X);
        if (guiDraftActive) {
            draftOffsetX = v;
            syncCurrentDraftPlacement();
        } else {
            offsetX = v;
            syncCurrentCommittedPlacement();
            persist();
        }
    }

    public static void setOffsetY(float value) {
        float v = clamp(value, MIN_OFFSET_Y, MAX_OFFSET_Y);
        if (guiDraftActive) {
            draftOffsetY = v;
            syncCurrentDraftPlacement();
        } else {
            offsetY = v;
            syncCurrentCommittedPlacement();
            persist();
        }
    }

    public static void setOffsetZ(float value) {
        float v = clamp(value, MIN_OFFSET_Z, MAX_OFFSET_Z);
        if (guiDraftActive) {
            draftOffsetZ = v;
            syncCurrentDraftPlacement();
        } else {
            offsetZ = v;
            syncCurrentCommittedPlacement();
            persist();
        }
    }

    public static void setBadgeScale(float value) {
        float v = clamp(value, MIN_SCALE, MAX_SCALE);
        if (guiDraftActive) {
            draftBadgeScale = v;
            syncCurrentDraftPlacement();
        } else {
            badgeScale = v;
            syncCurrentCommittedPlacement();
            persist();
        }
    }

    public static void setPlacementFromSkinDrag(BadgeAttachPart part, float skinX, float skinY) {
        setAttachPart(part);
        setOffsetX(skinX);
        setOffsetY(skinY);
    }

    public static void nudgeOffset(float dx, float dy) {
        setOffsetX(getOffsetX() + dx);
        setOffsetY(getOffsetY() + dy);
    }

    public static void nudgeScale(float delta) {
        setBadgeScale(getBadgeScale() + delta);
    }

    public static boolean isEquipped(String key) {
        String k = safeKey(key);
        return findPlacement(guiDraftActive ? draftPlacements : placements, k) != null;
    }

    public static int getEquippedBadgeCount() {
        return (guiDraftActive ? draftPlacements : placements).size();
    }

    public static void removeBadge(String key) {
        String k = safeKey(key);
        if (guiDraftActive) {
            removePlacement(draftPlacements, k);
            if (k.equals(draftKey)) draftEnabled = false;
        } else {
            removePlacement(placements, k);
            enabled = !placements.isEmpty();
            if (k.equals(equippedKey)) {
                if (!placements.isEmpty()) loadCommittedFields(placements.get(0));
                else equippedKey = "";
            }
            persist();
        }
    }

    public static void clearAllBadges() {
        if (guiDraftActive) {
            draftPlacements.clear();
            draftEnabled = false;
        } else {
            placements.clear();
            enabled = false;
            equippedKey = "";
            persist();
        }
    }

    public static boolean isUnlocked(BlitzChallenges.ChallengeId id) {
        return id != null && BlitzChallenges.isFullyCompleted(id);
    }

    private static void loadDraftDefaults() {
        draftAttachPart = BadgeAttachPart.BODY;
        draftSkinLayer = BadgeSkinLayer.INNER;
        draftOffsetX = 0.0F;
        draftOffsetY = 0.0F;
        draftOffsetZ = 0.0F;
        draftBadgeScale = 1.0F;
    }

    private static void loadDraftFields(BadgePlacement placement) {
        if (placement == null) return;
        draftEnabled = true;
        draftKey = placement.key;
        draftAttachPart = placement.part;
        draftSkinLayer = placement.layer;
        draftOffsetX = placement.x;
        draftOffsetY = placement.y;
        draftOffsetZ = placement.z;
        draftBadgeScale = placement.scale;
    }

    private static void loadCommittedFields(BadgePlacement placement) {
        if (placement == null) return;
        enabled = true;
        equippedKey = placement.key;
        attachPart = placement.part;
        skinLayer = placement.layer;
        offsetX = placement.x;
        offsetY = placement.y;
        offsetZ = placement.z;
        badgeScale = placement.scale;
    }

    private static BadgePlacement currentDraftPlacement() {
        return new BadgePlacement(draftKey, draftAttachPart, draftSkinLayer,
                draftOffsetX, draftOffsetY, draftOffsetZ, draftBadgeScale);
    }

    private static BadgePlacement currentCommittedPlacement() {
        return new BadgePlacement(equippedKey, attachPart, skinLayer,
                offsetX, offsetY, offsetZ, badgeScale);
    }

    private static void ensureCurrentDraftPlacement() {
        if (draftKey == null || draftKey.isEmpty()) return;
        BadgePlacement existing = findPlacement(draftPlacements, draftKey);
        if (existing == null) draftPlacements.add(currentDraftPlacement());
        else copyPlacementValues(currentDraftPlacement(), existing);
    }

    private static void syncCurrentDraftPlacement() {
        if (!guiDraftActive || !draftEnabled || draftKey == null || draftKey.isEmpty()) return;
        ensureCurrentDraftPlacement();
    }

    private static void syncCurrentCommittedPlacement() {
        if (equippedKey == null || equippedKey.isEmpty()) return;
        BadgePlacement existing = findPlacement(placements, equippedKey);
        if (existing != null) copyPlacementValues(currentCommittedPlacement(), existing);
    }

    private static void copyPlacementValues(BadgePlacement source, BadgePlacement target) {
        if (source == null || target == null) return;
        target.part = source.part;
        target.layer = source.layer;
        target.x = source.x;
        target.y = source.y;
        target.z = source.z;
        target.scale = source.scale;
    }

    private static BadgePlacement findPlacement(List<BadgePlacement> source, String key) {
        String k = safeKey(key);
        if (k.isEmpty()) return null;
        for (BadgePlacement placement : source) {
            if (placement != null && k.equals(placement.key)) return placement;
        }
        return null;
    }

    private static void removePlacement(List<BadgePlacement> source, String key) {
        String k = safeKey(key);
        for (Iterator<BadgePlacement> it = source.iterator(); it.hasNext();) {
            BadgePlacement placement = it.next();
            if (placement != null && k.equals(placement.key)) it.remove();
        }
    }

    private static void copyPlacements(List<BadgePlacement> source, List<BadgePlacement> target) {
        target.clear();
        for (BadgePlacement placement : source) {
            if (placement != null) target.add(placement.copy());
        }
    }

    private static void removeLockedOrInvalidPlacements(List<BadgePlacement> source) {
        for (Iterator<BadgePlacement> it = source.iterator(); it.hasNext();) {
            BadgePlacement placement = it.next();
            BlitzChallenges.ChallengeId id = placement == null
                    ? null : BlitzChallenges.ChallengeId.fromKey(placement.key);
            if (id == null || !isUnlocked(id)) it.remove();
        }
    }

    public static int getUnlockedBadgeCount() {
        int n = 0;
        for (BlitzChallenges.ChallengeId id : BlitzChallenges.ChallengeId.values()) {
            if (isUnlocked(id)) {
                n++;
            }
        }
        return n;
    }

    public static boolean shouldRenderOn(AbstractClientPlayer player) {
        if (getRenderPlacements().isEmpty()) return false;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return false;
        if (player == mc.thePlayer) {
            return true;
        }
        return badgeGuiOpen && MountPreviewHelper.isGuiPreviewRider(player);
    }

    public static void renderOnPlayer(RenderPlayer renderPlayer, AbstractClientPlayer player, float scale) {
        if (renderPlayer == null || player == null || !shouldRenderOn(player)) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        ModelPlayer model = (ModelPlayer) renderPlayer.getMainModel();
        for (BadgePlacement placement : getRenderPlacements()) {
            BlitzChallenges.ChallengeId id = BlitzChallenges.ChallengeId.fromKey(placement.key);
            if (id == null || !isUnlocked(id)) continue;
            ResourceLocation packLoc = BlitzChallengeBadgeTextures.textureFor(id);
            if (packLoc == null || !BlitzChallengeBadgeTextures.ensureTextureLoaded(mc, id)) continue;
            ModelRenderer limb = resolveLimb(model, placement.part, placement.layer);
            if (limb == null) continue;

            GlStateManager.pushMatrix();
            if (player.isSneaking()) GlStateManager.translate(0.0F, 0.2F, 0.0F);
            limb.postRender(0.0625F);
            applySkinSurfaceTransform(placement.part, placement.layer,
                    placement.x, placement.y, placement.z);

            float size = placement.scale * 0.34F;
            GlStateManager.scale(size, size, size);
            if (placement.part != BadgeAttachPart.RIGHT_ARM && placement.part != BadgeAttachPart.LEFT_ARM) {
                GlStateManager.rotate(180.0F, 0.0F, 1.0F, 0.0F);
            }

            int light = player.getBrightnessForRender(scale);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit,
                    (light % 65536) / 1.0F, (light / 65536) / 1.0F);
            GlStateManager.enablePolygonOffset();
            GlStateManager.doPolygonOffset(-1.0F, -1.0F);
            GlStateManager.disableCull();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GomodClasspathTextures.bindTexture(mc, packLoc);
            drawBadgeTexturedFace(getRenderAlpha(), placement.part);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.enableCull();
            GlStateManager.disableBlend();
            GlStateManager.disablePolygonOffset();
            GlStateManager.popMatrix();
        }
    }

    private static List<BadgePlacement> getRenderPlacements() {
        return guiDraftActive ? draftPlacements : placements;
    }

    /** Textured quad on the limb's outward skin face — respects PNG alpha. */
    private static void drawBadgeTexturedFace(float alpha, BadgeAttachPart part) {
        float lift = BADGE_THICKNESS * 0.35F;
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        GlStateManager.color(1.0F, 1.0F, 1.0F, alpha);
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        switch (part) {
            case RIGHT_ARM:
                wr.pos(lift, -0.5D, 0.5D).tex(0.0D, 0.0D).endVertex();
                wr.pos(lift, -0.5D, -0.5D).tex(1.0D, 0.0D).endVertex();
                wr.pos(lift, 0.5D, -0.5D).tex(1.0D, 1.0D).endVertex();
                wr.pos(lift, 0.5D, 0.5D).tex(0.0D, 1.0D).endVertex();
                break;
            case LEFT_ARM:
                wr.pos(-lift, -0.5D, -0.5D).tex(0.0D, 0.0D).endVertex();
                wr.pos(-lift, -0.5D, 0.5D).tex(1.0D, 0.0D).endVertex();
                wr.pos(-lift, 0.5D, 0.5D).tex(1.0D, 1.0D).endVertex();
                wr.pos(-lift, 0.5D, -0.5D).tex(0.0D, 1.0D).endVertex();
                break;
            default:
                // Outward chest / head / leg front (−Z); 180° Y applied before draw.
                wr.pos(-0.5D, -0.5D, lift).tex(0.0D, 0.0D).endVertex();
                wr.pos(0.5D, -0.5D, lift).tex(1.0D, 0.0D).endVertex();
                wr.pos(0.5D, 0.5D, lift).tex(1.0D, 1.0D).endVertex();
                wr.pos(-0.5D, 0.5D, lift).tex(0.0D, 1.0D).endVertex();
                break;
        }
        tessellator.draw();
    }

    private static ModelRenderer resolveLimb(ModelPlayer model, BadgeAttachPart part, BadgeSkinLayer layer) {
        if (model == null || part == null) {
            return null;
        }
        boolean outer = layer == BadgeSkinLayer.OUTER;
        switch (part) {
            case HEAD:
                return outer ? model.bipedHeadwear : model.bipedHead;
            case BODY:
                return outer ? model.bipedBodyWear : model.bipedBody;
            case RIGHT_ARM:
                return outer ? model.bipedRightArmwear : model.bipedRightArm;
            case LEFT_ARM:
                return outer ? model.bipedLeftArmwear : model.bipedLeftArm;
            case RIGHT_LEG:
                return outer ? model.bipedRightLegwear : model.bipedRightLeg;
            case LEFT_LEG:
                return outer ? model.bipedLeftLegwear : model.bipedLeftLeg;
            default:
                return outer ? model.bipedBodyWear : model.bipedBody;
        }
    }

    /**
     * Places the badge flush on the outward skin face of the selected limb.
     * offsetY: positive when the cursor is below the anchor in the GUI (screen down).
     */
    private static void applySkinSurfaceTransform(BadgeAttachPart part, BadgeSkinLayer layer,
                                                  float ox, float oy, float surfaceExtra) {
        float ty = oy;
        float lift = SKIN_SURFACE_Z + surfaceExtra;
        float outer = layer == BadgeSkinLayer.OUTER
                ? (part == BadgeAttachPart.HEAD ? 0.03125F : 0.015625F)
                : 0.0F;
        switch (part) {
            case HEAD: {
                float hw = 0.25F + outer;
                float hh = 0.25F + outer;
                GlStateManager.translate(ox * hw * 2.0F, ty * hh * 2.0F, -hw - lift);
                break;
            }
            case BODY: {
                float hw = 0.25F + outer;
                float hh = 0.375F + outer;
                GlStateManager.translate(ox * hw * 2.0F, ty * hh * 2.0F, -0.125F - outer - lift);
                break;
            }
            case RIGHT_ARM: {
                float hw = 0.125F + outer;
                float hh = 0.375F + outer;
                GlStateManager.translate(hw + lift, ty * hh * 2.0F, ox * hw * 2.0F);
                break;
            }
            case LEFT_ARM: {
                float hw = 0.125F + outer;
                float hh = 0.375F + outer;
                GlStateManager.translate(-hw - lift, ty * hh * 2.0F, -ox * hw * 2.0F);
                break;
            }
            case RIGHT_LEG:
            case LEFT_LEG: {
                float hw = 0.125F + outer;
                float hh = 0.375F + outer;
                GlStateManager.translate(ox * hw * 2.0F, ty * hh * 2.0F, -hw - lift);
                break;
            }
            default:
                break;
        }
    }

    private static float normalizeSurfaceOffsetZ(float saved) {
        if (saved > MAX_OFFSET_Z) {
            return 0.0f;
        }
        return clamp(saved, MIN_OFFSET_Z, MAX_OFFSET_Z);
    }

    public static String getStatusLabel() {
        int count = getEquippedBadgeCount();
        if (count <= 0) {
            return EnumChatFormatting.GRAY + "No badge equipped";
        }
        if (count > 1) {
            return EnumChatFormatting.GREEN + String.valueOf(count) + " badges equipped";
        }
        BlitzChallenges.ChallengeId id = BlitzChallenges.ChallengeId.fromKey(getEquippedKey());
        if (id == null) {
            return EnumChatFormatting.GRAY + "No badge equipped";
        }
        return EnumChatFormatting.GREEN + id.getDisplayName()
                + EnumChatFormatting.GRAY + " (" + getAttachPart().getDisplayName()
                + ", " + getSkinLayer().getDisplayName() + ")";
    }

    private static void persist() {
        ConfigManager.setBlitzChallengeBadgeEnabled(enabled);
        ConfigManager.setBlitzChallengeBadgeEquippedKey(equippedKey);
        ConfigManager.setBlitzChallengeBadgeAttachPart(attachPart.getKey());
        ConfigManager.setBlitzChallengeBadgeOffsetX(offsetX);
        ConfigManager.setBlitzChallengeBadgeOffsetY(offsetY);
        ConfigManager.setBlitzChallengeBadgeOffsetZ(offsetZ);
        ConfigManager.setBlitzChallengeBadgeScale(badgeScale);
        JsonArray saved = new JsonArray();
        for (BadgePlacement placement : placements) {
            if (placement != null && !placement.key.isEmpty()) saved.add(placement.toJson());
        }
        ConfigManager.setBlitzChallengeBadgePlacements(saved);
    }

    private static String safeKey(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
