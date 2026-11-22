package me.napim.gomod.mixins;

import me.napim.gomod.features.GomodPlayerNametagLabels;
import me.napim.gomod.features.LocalDisguise;
import me.napim.gomod.features.LunarConnectionDiagnostics;
import me.napim.gomod.features.ReplayPlayerHealthTagFix;
import me.napim.gomod.features.VisibleSpecs;
import me.napim.gomod.features.RenderProfiler;
import me.napim.gomod.features.NametagRenderDebugCapture;
import me.napim.gomod.gui.MountPreviewHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Render.class)
public class RenderPlayerMixin {

    /**
     * RenderPlayer asks RenderLivingLabel for the same label and then immediately
     * runs the ModifyVariable hook for that label. Keep the classification between
     * those callbacks instead of stripping/lowercasing the text twice.
     */
    @Unique
    private EntityPlayer gomod$cachedLabelPlayer;
    @Unique
    private String gomod$cachedLabelText;
    @Unique
    private boolean gomod$cachedLabelIsPrimary;
    @Unique
    private boolean gomod$cachedLabelIsHealth;

    @Unique
    private void gomod$cacheLabelClassification(EntityPlayer player, String label) {
        if (player == gomod$cachedLabelPlayer
                && (label == gomod$cachedLabelText
                || (label != null && label.equals(gomod$cachedLabelText)))) {
            return;
        }
        gomod$cachedLabelPlayer = player;
        gomod$cachedLabelText = label;
        long profile = RenderProfiler.beginDeep("DeepPlayer.RenderPlayer.labelClassification");
        try {
            gomod$cachedLabelIsPrimary = gomod$isPrimaryPlayerNameLabel(player, label);
            // A primary name cannot also be a health row because the health classifier
            // explicitly excludes the player's own/display name.
            gomod$cachedLabelIsHealth = !gomod$cachedLabelIsPrimary
                    && gomod$isLikelyHealthLabel(player, label);
        } finally {
            RenderProfiler.endDeep("DeepPlayer.RenderPlayer.labelClassification", profile);
        }
    }

    @Inject(method = "renderLivingLabel", at = @At("HEAD"), cancellable = true)
    private void gomod$visibleSpecsLabelGate(Entity entity, String label, double x, double y, double z,
            int maxDistance, CallbackInfo ci) {
        try {
            if (!(entity instanceof EntityPlayer)) {
                return;
            }
            if (RenderProfiler.shouldBypassPlayerHooks()) return;
            EntityPlayer player = (EntityPlayer) entity;
            gomod$cacheLabelClassification(player, label);
            boolean customLabel = GomodPlayerNametagLabels.isRenderingCustomLabel();
            boolean explicitlyPrimaryName = customLabel
                    && GomodPlayerNametagLabels.isRenderingPrimaryNameLabel();
            boolean primaryNameLabel = gomod$cachedLabelIsPrimary || explicitlyPrimaryName;
            boolean healthLabel = gomod$cachedLabelIsHealth;

            // Lunar's Hypixel module adds independent player-bound rows (for example its
            // network/Bed Wars level line) through the same Render#renderLivingLabel method.
            // Those are not the vanilla IGN or tab-health row, so gomod must not rewrite,
            // or enqueue them as though they owned the player's whole nametag. ONLY_IGN is
            // the exception: for a spectator target, any non-IGN player row must be hidden.
            if (!primaryNameLabel && !healthLabel) {
                boolean killerLabel = customLabel && GomodPlayerNametagLabels.isRenderingKillerLabel();
                boolean suppressAdditional = !killerLabel
                        && VisibleSpecs.shouldSuppressAdditionalNametagRow(player);
                VisibleSpecs.recordNametagRenderDebug(player, label, false, false,
                        suppressAdditional, killerLabel ? "gomod-killer-row-pass" :
                                suppressAdditional ? (customLabel ? "only-ign-custom-row" : "only-ign-additional-row")
                                        : customLabel ? "gomod-custom-row" : "unclassified-row-pass");
                if (suppressAdditional) {
                    NametagRenderDebugCapture.recordLabelCancelled(entity, label, "visible-specs-additional-row");
                    ci.cancel();
                }
                return;
            }
            if (explicitlyPrimaryName) {
                VisibleSpecs.recordNametagRenderDebug(player, label, true, false, false,
                        "gomod-primary-row-pass");
            }
            if (!primaryNameLabel) {
                VisibleSpecs.recordNametagRenderDebug(player, label, false, healthLabel,
                        healthLabel && VisibleSpecs.shouldSuppressHealthNametag(player),
                        healthLabel && VisibleSpecs.shouldSuppressHealthNametag(player)
                                ? "spectator-health-row" : "health-row-pass");
            }
            if (MountPreviewHelper.shouldSuppressGuiPreviewNametag(entity)) {
                NametagRenderDebugCapture.recordLabelCancelled(entity, label, "mount-preview");
                ci.cancel();
                return;
            }
            if (VisibleSpecs.shouldSuppressPlayerNametagForBlindness(entity, x, y, z)) {
                NametagRenderDebugCapture.recordLabelCancelled(entity, label, "blindness-occlusion");
                ci.cancel();
                return;
            }
            if (customLabel) {
                return;
            }
            if (entity instanceof EntityLivingBase
                    && ReplayPlayerHealthTagFix.shouldRenderCompleteReplayStack((EntityLivingBase) entity)) {
                NametagRenderDebugCapture.recordLabelCancelled(entity, label, "replay-complete-stack");
                ci.cancel();
                return;
            }
            if (VisibleSpecs.shouldSuppressNametag(entity)) {
                NametagRenderDebugCapture.recordLabelCancelled(entity, label, "visible-specs-policy");
                ci.cancel();
                return;
            }

            if (healthLabel && VisibleSpecs.shouldSuppressHealthNametag(entity)) {
                NametagRenderDebugCapture.recordLabelCancelled(entity, label, "health-row-policy");
                ci.cancel();
                return;
            }

            if (VisibleSpecs.shouldDeferNametagForOpacity(entity)) {
                if (!healthLabel && (Object) this instanceof RendererLivingEntity && entity instanceof EntityLivingBase) {
                    VisibleSpecs.scheduleDeferredNametag((RendererLivingEntity) (Object) this,
                            (EntityLivingBase) entity, x, y, z);
                }
                NametagRenderDebugCapture.recordLabelCancelled(entity, label, "deferred-for-opacity");
                ci.cancel();
            }
        } catch (Throwable ignored) {
        }
    }

    // Lunar/Genesis does not retain a usable local-variable table for Render#renderLivingLabel.
    // Modify the String argument directly at HEAD so Mixin never attempts LVT generation.
    @ModifyVariable(
            method = {"renderLivingLabel", "func_147906_a"},
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0,
            require = 0
    )
    private String gomod$modifyPlayerNameTag(String originalName, Entity entity, String name, double x, double y, double z, int maxDistance) {
        if (!(entity instanceof EntityPlayer)) {
            return originalName;
        }
        if (RenderProfiler.shouldBypassPlayerHooks()) return originalName;
        if (GomodPlayerNametagLabels.isRenderingCustomLabel()) {
            return originalName;
        }

        Minecraft mc = Minecraft.getMinecraft();
        boolean localDisguise = mc != null && mc.thePlayer == entity && LocalDisguise.fastEnabledFlag;
        if (localDisguise) {
            String selfLabel = LocalDisguise.buildNametagDisplayName(
                    originalName, ((EntityPlayer) entity).getName());
            if (selfLabel != null && !selfLabel.equals(originalName)) {
                recordSelfDisguiseNametag((EntityPlayer) entity,
                        "path=direct-local-label primaryClassified=pending changed=true");
                return me.napim.gomod.features.BlitzChatNameDisplay.decorateNametag((EntityPlayer) entity,
                        GomodPlayerNametagLabels.applyKillEffectNametagColor((EntityPlayer) entity, selfLabel));
            }
        }

        gomod$cacheLabelClassification((EntityPlayer) entity, originalName);
        if (!gomod$cachedLabelIsPrimary) {
            if (localDisguise) {
                recordSelfDisguiseNametag((EntityPlayer) entity,
                        "path=render-label primaryClassified=false changed=false");
            }
            return originalName;
        }
        long profile = RenderProfiler.beginDeep("DeepPlayer.RenderPlayer.labelResolver");
        try {
            String resolved = GomodPlayerNametagLabels.resolveDisplayedLabel((EntityPlayer) entity);
            if (localDisguise) {
                recordSelfDisguiseNametag((EntityPlayer) entity,
                        "path=shared-resolver primaryClassified=true changed="
                                + (resolved != null && !resolved.equals(originalName)));
            }
            return resolved;
        } finally {
            RenderProfiler.endDeep("DeepPlayer.RenderPlayer.labelResolver", profile);
        }
    }

    private static void recordSelfDisguiseNametag(EntityPlayer player, String result) {
        Minecraft mc = Minecraft.getMinecraft();
        boolean sneaking = player != null && player.isSneaking();
        int view = mc == null || mc.gameSettings == null ? -1 : mc.gameSettings.thirdPersonView;
        LunarConnectionDiagnostics.recordOnce("disguise.self_nametag_render_label",
                "sneaking=" + sneaking + ",view=" + view,
                result + " sneaking=" + sneaking + " thirdPersonView=" + view);
    }

    /**
     * Only the actual player IGN row may be decorated/replaced by gomod. Lunar and other
     * clients legitimately render additional text rows while retaining the player entity
     * as the label owner, so "not a health label" is not enough to identify the IGN.
     */
    private static boolean gomod$isPrimaryPlayerNameLabel(EntityPlayer player, String label) {
        return GomodPlayerNametagLabels.isPrimaryPlayerNameLabel(player, label);
    }

    private static boolean gomod$containsNameToken(String text, String name) {
        if (text == null || name == null || name.isEmpty()) {
            return false;
        }
        String lowerText = text.toLowerCase(java.util.Locale.ROOT);
        String lowerName = name.toLowerCase(java.util.Locale.ROOT);
        int from = 0;
        while (from <= lowerText.length() - lowerName.length()) {
            int at = lowerText.indexOf(lowerName, from);
            if (at < 0) {
                return false;
            }
            int end = at + lowerName.length();
            boolean leftBoundary = at == 0 || !gomod$isUsernameChar(lowerText.charAt(at - 1));
            boolean rightBoundary = end == lowerText.length() || !gomod$isUsernameChar(lowerText.charAt(end));
            if (leftBoundary && rightBoundary) {
                return true;
            }
            from = at + 1;
        }
        return false;
    }

    private static boolean gomod$isUsernameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
    }

    private static boolean gomod$isLikelyHealthLabel(Entity entity, String label) {
        String clean = gomod$stripFormatting(label).trim();
        if (clean.isEmpty()) {
            return false;
        }
        String entityName = entity == null ? "" : gomod$stripFormatting(entity.getName()).trim();
        String displayName = "";
        try {
            displayName = entity.getDisplayName() == null
                    ? ""
                    : gomod$stripFormatting(entity.getDisplayName().getFormattedText()).trim();
        } catch (Throwable ignored) {
        }
        if ((!entityName.isEmpty() && clean.equalsIgnoreCase(entityName))
                || (!displayName.isEmpty() && clean.equalsIgnoreCase(displayName))) {
            return false;
        }
        String lower = clean.toLowerCase(java.util.Locale.ROOT);
        return clean.indexOf('\u2764') >= 0
                || clean.indexOf('\u2765') >= 0
                || lower.contains("health")
                || lower.contains("hp")
                || gomod$hasNumericHealthPrefix(clean);
    }

    /** Equivalent to the old numeric-label regex without compiling a Pattern per label. */
    private static boolean gomod$hasNumericHealthPrefix(String clean) {
        int length = clean.length();
        int index = 0;
        while (index < length && clean.charAt(index) >= '0' && clean.charAt(index) <= '9') {
            index++;
        }
        if (index == 0) {
            return false;
        }
        if (index + 1 < length && clean.charAt(index) == '.'
                && clean.charAt(index + 1) >= '0' && clean.charAt(index + 1) <= '9') {
            index++;
            while (index < length && clean.charAt(index) >= '0' && clean.charAt(index) <= '9') {
                index++;
            }
        }
        return index == length || !Character.isLetterOrDigit(clean.charAt(index)) && clean.charAt(index) != '_';
    }

    private static String gomod$stripFormatting(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == '\u00a7' || c == '&') && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')
                        || code == 'k' || code == 'l' || code == 'm'
                        || code == 'n' || code == 'o' || code == 'r') {
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }
}
