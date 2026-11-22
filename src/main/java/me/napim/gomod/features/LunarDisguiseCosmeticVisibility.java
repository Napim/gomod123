package me.napim.gomod.features;

import me.napim.gomod.GomodInternalFeatures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/** The first-person cosmetic pass does not pass through vanilla living-entity layers. */
public final class LunarDisguiseCosmeticVisibility {
    private static boolean reportedHidden;
    private static boolean reportedCosmeticRendererHidden;
    private static boolean reportedThirdPersonArmPass;
    private static final Set<Integer> REPORTED_BODY_MASKS = new HashSet<Integer>();
    private static final Set<String> REPORTED_COSMETIC_RENDERER_EVALUATIONS = new HashSet<String>();
    private static final Set<String> REPORTED_HAND_REPLACEMENT_EVALUATIONS = new HashSet<String>();
    private static final ThreadLocal<Deque<Boolean>> FIRST_PERSON_ARM_SCOPES =
            ThreadLocal.withInitial(ArrayDeque::new);
    // Empty stacks retain no models and are reused across player draws.
    private static final ThreadLocal<Deque<ModelVisibilitySnapshot>> ACTIVE_MODEL_RENDERS =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Deque<ModelVisibilitySnapshot>> MODEL_SNAPSHOT_POOL =
            ThreadLocal.withInitial(ArrayDeque::new);

    private LunarDisguiseCosmeticVisibility() {
    }

    public static void beginFirstPersonArmRender(Object player) {
        FIRST_PERSON_ARM_SCOPES.get().addLast(shouldSkipFirstPersonHandReplacement(player));
    }

    public static void endFirstPersonArmRender() {
        Deque<Boolean> scopes = FIRST_PERSON_ARM_SCOPES.get();
        if (!scopes.isEmpty()) scopes.removeLast();
        if (scopes.isEmpty()) FIRST_PERSON_ARM_SCOPES.remove();
    }

    /** Suppresses the attached Lunar geometry, never the base arm/sleeve or the whole model draw. */
    public static boolean shouldHideFirstPersonModelAttachments() {
        if (LunarRemoteDisguiseCosmetics.shouldHideCurrentPlayer()) return true;
        Deque<Boolean> scopes = FIRST_PERSON_ARM_SCOPES.get();
        boolean hide = !scopes.isEmpty() && Boolean.TRUE.equals(scopes.peekLast());
        if (hide) {
            LunarConnectionDiagnostics.recordOnce("disguise.lunar_arm_attachments", "hidden-local-arm",
                    "attachmentDrawReached=true suppressed=true baseArmPreserved=true");
        }
        return hide;
    }

    public static boolean shouldHideFirstPersonCosmetics(Object playerBridge) {
        LunarArmDebugCapture.recordCosmeticCall("helper", playerBridge);
        if (!GomodFixes.isWeaveRuntime() || !GomodInternalFeatures.isEnabled()
                || !LocalDisguise.fastEnabledFlag
                || !ConfigManager.isDisguiseHideLunarCosmeticsOnWeave()) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.gameSettings == null) {
            return false;
        }
        if (mc.gameSettings.thirdPersonView != 0) {
            if (!reportedThirdPersonArmPass) {
                reportedThirdPersonArmPass = true;
                LunarConnectionDiagnostics.record("disguise.lunar_arm_pass_preserved",
                        "camera=third-person; reason=first-person-only-guard");
            }
            return false;
        }
        // Lunar's GEK hand-cosmetic path can pass a wrapper whose self/UUID lookup is not
        // available in every client build. In first person that path can only render the
        // local view player's hand, so camera + active local disguise are the reliable scope.
        if (!reportedHidden) {
            reportedHidden = true;
            LunarConnectionDiagnostics.record("disguise.lunar_first_person",
                    "cosmeticPassSuppressed=true camera=first-person bridgeIdentity="
                            + (playerBridge != null && PlayerStandaloneCape.isLocalPlayerBridge(playerBridge)));
        }
        return true;
    }

    /** Guards Lunar's separate arm-model renderer, which continues after the helper above returns. */
    public static boolean shouldHideFirstPersonCosmeticRenderer(Object playerBridge) {
        LunarArmDebugCapture.recordCosmeticCall("arm-renderer", playerBridge);
        boolean shouldHide = shouldHideFirstPersonCosmetics(playerBridge);
        reportCosmeticRendererEvaluation(playerBridge, shouldHide);
        if (!shouldHide) {
            return false;
        }
        if (!reportedCosmeticRendererHidden) {
            reportedCosmeticRendererHidden = true;
            LunarConnectionDiagnostics.record("disguise.lunar_first_person_renderer",
                    "cosmeticRendererSuppressed=true camera=first-person");
        }
        return true;
    }

    /** GEK suits draw through a separate first-person event before the vanilla arm callback. */
    public static boolean shouldHideFirstPersonGekCosmetics(Object playerBridge) {
        LunarArmDebugCapture.recordCosmeticCall("gek-hand", playerBridge);
        boolean hide = shouldHideFirstPersonCosmetics(playerBridge);
        if (hide) {
            LunarConnectionDiagnostics.recordOnce("disguise.lunar_gek_first_person", "hidden-local-hand",
                    "gekPassReached=true suppressed=true vanillaHandAndHeldItemPreserved=true");
        }
        return hide;
    }

    /** Determines whether this local arm draw needs its attached suit geometry hidden. */
    public static boolean shouldSkipFirstPersonHandReplacement(Object entity) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            boolean localPlayer = entity instanceof EntityPlayer && mc != null && mc.thePlayer == entity;
            boolean weaveRuntime = GomodFixes.isWeaveRuntime();
            boolean internalFeatures = GomodInternalFeatures.isEnabled();
            boolean disguiseActive = LocalDisguise.fastEnabledFlag;
            boolean hiddenSetting = ConfigManager.isDisguiseHideLunarCosmeticsOnWeave();
            int camera = mc == null || mc.gameSettings == null ? -1 : mc.gameSettings.thirdPersonView;
            boolean shouldSkip = localPlayer && weaveRuntime && internalFeatures && disguiseActive
                    && hiddenSetting && camera == 0;
            String state = "armScopeReached=true hideAttachments=" + shouldSkip
                    + " localPlayer=" + localPlayer
                    + " weave=" + weaveRuntime
                    + " internalFeatures=" + internalFeatures
                    + " disguise=" + disguiseActive
                    + " hiddenSetting=" + hiddenSetting
                    + " camera=" + camera;
            synchronized (REPORTED_HAND_REPLACEMENT_EVALUATIONS) {
                if (REPORTED_HAND_REPLACEMENT_EVALUATIONS.add(state)) {
                    LunarConnectionDiagnostics.record("disguise.lunar_first_person_hand_replacement_check", state);
                }
            }
            return shouldSkip;
        } catch (Throwable ignored) {
            // Keep a diagnostic/compatibility guard from interrupting Minecraft's hand renderer.
            return false;
        }
    }

    /** Records whether Lunar reached this overload and, if so, which guard input declined it. */
    private static void reportCosmeticRendererEvaluation(Object playerBridge, boolean shouldHide) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            boolean localBridge = playerBridge != null && PlayerStandaloneCape.isLocalPlayerBridge(playerBridge);
            boolean weaveRuntime = GomodFixes.isWeaveRuntime();
            boolean internalFeatures = GomodInternalFeatures.isEnabled();
            boolean disguiseActive = LocalDisguise.fastEnabledFlag;
            boolean hiddenSetting = ConfigManager.isDisguiseHideLunarCosmeticsOnWeave();
            int camera = mc == null || mc.gameSettings == null ? -1 : mc.gameSettings.thirdPersonView;
            String state = "rendererReached=true suppressed=" + shouldHide
                    + " bridgePresent=" + (playerBridge != null)
                    + " localBridge=" + localBridge
                    + " weave=" + weaveRuntime
                    + " internalFeatures=" + internalFeatures
                    + " disguise=" + disguiseActive
                    + " hiddenSetting=" + hiddenSetting
                    + " camera=" + camera;
            synchronized (REPORTED_COSMETIC_RENDERER_EVALUATIONS) {
                if (REPORTED_COSMETIC_RENDERER_EVALUATIONS.add(state)) {
                    LunarConnectionDiagnostics.record("disguise.lunar_first_person_renderer_check", state);
                }
            }
        } catch (Throwable ignored) {
            // Diagnostics must never interfere with Lunar's render path.
        }
    }

    /** Brackets vanilla's main player-model draw and restores suit-hidden body parts only for this render. */
    public static void beginPlayerModelRender(Object entity, Object model) {
        Deque<ModelVisibilitySnapshot> pool = MODEL_SNAPSHOT_POOL.get();
        ModelVisibilitySnapshot snapshot = pool.pollFirst();
        if (snapshot == null) {
            snapshot = new ModelVisibilitySnapshot();
        }
        snapshot.captureAndReveal(entity, model);
        ACTIVE_MODEL_RENDERS.get().addLast(snapshot);
    }

    public static void endPlayerModelRender() {
        Deque<ModelVisibilitySnapshot> active = ACTIVE_MODEL_RENDERS.get();
        if (active.isEmpty()) {
            return;
        }
        ModelVisibilitySnapshot snapshot = active.removeLast();
        snapshot.restore();
        MODEL_SNAPSHOT_POOL.get().addFirst(snapshot);
    }

    private static boolean shouldRestoreBaseSkin(Object entity, Object model) {
        if (!(entity instanceof EntityPlayer) || !(model instanceof ModelBiped)
                || !GomodFixes.isWeaveRuntime() || !GomodInternalFeatures.isEnabled()
                || !LocalDisguise.fastEnabledFlag || !ConfigManager.isDisguiseHideLunarCosmeticsOnWeave()) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.thePlayer == entity;
    }

    private static final class ModelVisibilitySnapshot {
        private final ModelRenderer[] parts = new ModelRenderer[5];
        private final boolean[] oldShowModel = new boolean[5];
        private final boolean[] oldHidden = new boolean[5];
        private int count;

        private void captureAndReveal(Object entity, Object model) {
            count = 0;
            if (!shouldRestoreBaseSkin(entity, model)) {
                return;
            }
            ModelBiped biped = (ModelBiped) model;
            int mask = 0;
            mask |= capture(biped.bipedBody, 1);
            mask |= capture(biped.bipedRightArm, 2);
            mask |= capture(biped.bipedLeftArm, 4);
            mask |= capture(biped.bipedRightLeg, 8);
            mask |= capture(biped.bipedLeftLeg, 16);
            synchronized (REPORTED_BODY_MASKS) {
                if (REPORTED_BODY_MASKS.add(Integer.valueOf(mask))) {
                    Minecraft mc = Minecraft.getMinecraft();
                    LunarConnectionDiagnostics.record("disguise.lunar_model_visibility",
                            "camera=" + (mc == null || mc.gameSettings == null ? -1 : mc.gameSettings.thirdPersonView)
                                    + " hiddenBasePartMask=" + mask + " restoredParts=" + count);
                }
            }
        }

        private int capture(ModelRenderer part, int bit) {
            if (part == null || (part.showModel && !part.isHidden)) {
                return 0;
            }
            parts[count] = part;
            oldShowModel[count] = part.showModel;
            oldHidden[count] = part.isHidden;
            count++;
            part.showModel = true;
            part.isHidden = false;
            return bit;
        }

        private void restore() {
            for (int i = count - 1; i >= 0; i--) {
                ModelRenderer part = parts[i];
                if (part != null) {
                    part.showModel = oldShowModel[i];
                    part.isHidden = oldHidden[i];
                    parts[i] = null;
                }
            }
            count = 0;
        }
    }
}
