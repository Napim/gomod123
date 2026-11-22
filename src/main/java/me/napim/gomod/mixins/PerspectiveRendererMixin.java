package me.napim.gomod.mixins;

import me.napim.gomod.features.PerspectiveDistance;
import me.napim.gomod.features.ReflectionLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Handles third-person perspective distance controls: smooth interpolation,
 * reset key, and FOV override.
 * Scroll wheel zoom is handled via Forge MouseEvent in ForgeEventBridge.
 * Uses reflection with SRG field names for production compatibility.
 */
@Mixin(EntityRenderer.class)
public abstract class PerspectiveRendererMixin {

    private int gomod$prevThirdPersonView = 0;
    private float gomod$currentDistance = 4.0f;
    private float gomod$targetDistance = 4.0f;
    private float gomod$originalFOV = 70.0f;

    private boolean gomod$reflectionAttempted = false;
    private boolean gomod$reflectionSuccessful = false;
    private java.lang.reflect.Field gomod$thirdPersonDistanceField = null;
    private java.lang.reflect.Field gomod$thirdPersonDistancePrevField = null;

    private boolean gomod$guiFocusCheckAttempted = false;
    private boolean gomod$guiFocusCheckSuccessful = false;
    private java.lang.reflect.Field gomod$focusedField = null;

    /**
     * Resolve a field by trying the SRG name first (production), then MCP name (dev environment).
     */
    private static java.lang.reflect.Field gomod$resolveField(Class<?> clazz, String srgName, String mcpName) {
        try {
            java.lang.reflect.Field f = clazz.getDeclaredField(srgName);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException ignored) {}
        try {
            java.lang.reflect.Field f = clazz.getDeclaredField(mcpName);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException ignored) {}
        return null;
    }

    @Inject(method = "updateCameraAndRender", at = @At("HEAD"))
    private void gomod$applyPerspectiveDistance(float partialTicks, long nanoTime, CallbackInfo ci) {
        me.napim.gomod.features.MixinStatus.perspectiveRendererActive = false;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) return;

            int currentView = mc.gameSettings.thirdPersonView;

            if (gomod$prevThirdPersonView == 0 && currentView != 0) {
                try {
                    float configDistance = PerspectiveDistance.getDistance();
                    gomod$currentDistance = configDistance;
                    gomod$targetDistance = configDistance;
                    gomod$originalFOV = mc.gameSettings.fovSetting;

                    if (gomod$reflectionSuccessful && gomod$thirdPersonDistanceField != null) {
                        try {
                            gomod$thirdPersonDistanceField.setFloat(this, configDistance);
                            if (gomod$thirdPersonDistancePrevField != null) {
                                gomod$thirdPersonDistancePrevField.setFloat(this, configDistance);
                            }
                        } catch (Throwable e) {
                            System.err.println("[gomod] PerspectiveMixin: reflection set on enter failed: " + e.getMessage());
                        }
                    }
                } catch (Throwable t) {
                    System.err.println("[gomod] PerspectiveMixin: enter third-person error: " + t.getMessage());
                }
            }

            if (gomod$prevThirdPersonView != 0 && currentView == 0 && PerspectiveDistance.isResetOnExit()) {
                try {
                    PerspectiveDistance.setDistance(4.0f);
                    gomod$currentDistance = 4.0f;
                    gomod$targetDistance = 4.0f;

                    if (gomod$reflectionSuccessful && gomod$thirdPersonDistanceField != null) {
                        try {
                            gomod$thirdPersonDistanceField.setFloat(this, 4.0f);
                            if (gomod$thirdPersonDistancePrevField != null) {
                                gomod$thirdPersonDistancePrevField.setFloat(this, 4.0f);
                            }
                        } catch (Throwable e) {
                            System.err.println("[gomod] PerspectiveMixin: reflection reset on exit failed: " + e.getMessage());
                        }
                    }
                } catch (Throwable t) {
                    System.err.println("[gomod] PerspectiveMixin: exit third-person error: " + t.getMessage());
                }
            }

            if (gomod$prevThirdPersonView != 0 && currentView == 0) {
                try {
                    mc.gameSettings.fovSetting = gomod$originalFOV;
                } catch (Throwable t) {
                    System.err.println("[gomod] PerspectiveMixin: FOV restore error: " + t.getMessage());
                }
            }

            gomod$prevThirdPersonView = currentView;

            if (currentView == 0) return;

            boolean perspectiveEnabled = PerspectiveDistance.isEnabled();
            boolean advancedAllowed = PerspectiveDistance.isAdvancedPerspectiveAllowed(mc);

            if (perspectiveEnabled && advancedAllowed) {
                // Handle reset key
                try {
                    int resetKey = PerspectiveDistance.getResetKeyCode();
                    if (resetKey > 0 && org.lwjgl.input.Keyboard.isKeyDown(resetKey)) {
                        if (mc.currentScreen != null) {
                            if (mc.currentScreen instanceof net.minecraft.client.gui.GuiChat ||
                                mc.currentScreen instanceof net.minecraft.client.gui.GuiCommandBlock ||
                                mc.currentScreen instanceof net.minecraft.client.gui.GuiRepair) {
                                return;
                            }

                            if (!gomod$guiFocusCheckAttempted || !gomod$guiFocusCheckSuccessful) {
                                try {
                                    java.lang.reflect.Field focusedField = mc.currentScreen.getClass().getDeclaredField("field_146209_f");
                                    if (focusedField != null) {
                                        focusedField.setAccessible(true);
                                        gomod$focusedField = focusedField;
                                        gomod$guiFocusCheckSuccessful = true;
                                    }
                                } catch (Exception e) {
                                    if (e.getClass().getSimpleName().equals("NoSuchFieldException")) {
                                        gomod$guiFocusCheckSuccessful = false;
                                    } else {
                                        System.err.println("[gomod] Unexpected error checking text field focus: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                                        gomod$guiFocusCheckSuccessful = false;
                                    }
                                } catch (Throwable e) {
                                    System.err.println("[gomod] Unexpected error checking text field focus: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                                    gomod$guiFocusCheckSuccessful = false;
                                }
                                gomod$guiFocusCheckAttempted = true;
                            }

                            if (gomod$guiFocusCheckSuccessful && gomod$focusedField != null) {
                                try {
                                    Object focused = gomod$focusedField.get(mc.currentScreen);
                                    if (focused != null) {
                                        return;
                                    }
                                } catch (Throwable e) {
                                    gomod$guiFocusCheckSuccessful = false;
                                    System.err.println("[gomod] Field access failed for GUI focus check: " + e.getMessage());
                                }
                            }
                        }
                        PerspectiveDistance.setDistance(4.0f);
                        gomod$targetDistance = 4.0f;
                        gomod$currentDistance = 4.0f;
                    }
                } catch (Throwable t) {
                    System.err.println("[gomod] PerspectiveMixin: reset key error: " + t.getMessage());
                }

                gomod$targetDistance = PerspectiveDistance.getDistance();

                if (PerspectiveDistance.isSmoothZoom()) {
                    float smoothSpeed = PerspectiveDistance.getSmoothSpeed();
                    float diff = gomod$targetDistance - gomod$currentDistance;
                    if (Math.abs(diff) > 0.001f) {
                        gomod$currentDistance += diff * smoothSpeed;
                    } else {
                        gomod$currentDistance = gomod$targetDistance;
                    }
                } else {
                    gomod$currentDistance = gomod$targetDistance;
                }

                float dist = gomod$currentDistance;

                // SRG: field_78490_B = thirdPersonDistance, field_78491_C = thirdPersonDistancePrev
                if (!gomod$reflectionAttempted) {
                    try {
                        java.lang.reflect.Field f = gomod$resolveField(EntityRenderer.class, "field_78490_B", "thirdPersonDistance");
                        if (f != null) {
                            f.setFloat(this, dist);
                            gomod$thirdPersonDistanceField = f;

                            java.lang.reflect.Field f2 = gomod$resolveField(EntityRenderer.class, "field_78491_C", "thirdPersonDistancePrev");
                            if (f2 != null) {
                                f2.setFloat(this, dist);
                                gomod$thirdPersonDistancePrevField = f2;
                            }

                            gomod$reflectionSuccessful = true;
                        } else {
                            ReflectionLogger.logReflectionFailure("PerspectiveRendererMixin: field_78490_B / thirdPersonDistance not found", new Exception("field not found"));
                            gomod$reflectionSuccessful = false;
                        }
                    } catch (Exception e) {
                        ReflectionLogger.logReflectionFailure("PerspectiveRendererMixin: set distance fields", e);
                        gomod$reflectionSuccessful = false;
                    } catch (Throwable e) {
                        ReflectionLogger.logReflectionFailure("PerspectiveRendererMixin: set distance fields (throwable)", e);
                        gomod$reflectionSuccessful = false;
                    }
                    gomod$reflectionAttempted = true;
                }

                if (gomod$reflectionSuccessful && gomod$thirdPersonDistanceField != null) {
                    try {
                        gomod$thirdPersonDistanceField.setFloat(this, dist);
                        if (gomod$thirdPersonDistancePrevField != null) {
                            gomod$thirdPersonDistancePrevField.setFloat(this, dist);
                        }
                    } catch (Throwable e) {
                        gomod$reflectionSuccessful = false;
                        ReflectionLogger.logReflectionFailure("PerspectiveRendererMixin: write cached fields", e);
                    }
                }
            }

            try {
                if (PerspectiveDistance.isFOVEnabled()) {
                    float perspectiveFOV = PerspectiveDistance.getFOV();
                    mc.gameSettings.fovSetting = perspectiveFOV;
                }
            } catch (Throwable t) {
                System.err.println("[gomod] PerspectiveMixin: FOV apply error: " + t.getMessage());
            }

            me.napim.gomod.features.MixinStatus.perspectiveRendererActive = perspectiveEnabled && advancedAllowed && gomod$reflectionSuccessful;
        } catch (Exception e) {
            ReflectionLogger.logReflectionFailure("PerspectiveRendererMixin: updateCameraAndRender", e);
        }
    }

    @Inject(method = "getFOVModifier", at = @At("HEAD"), cancellable = true)
    private void gomod$forcePerspectiveFOV(float partialTicks, boolean useFOVSetting, CallbackInfoReturnable<Float> cir) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.gameSettings == null) return;
            if (mc.gameSettings.thirdPersonView == 0) return;
            if (!PerspectiveDistance.isEnabled()) return;
            if (!PerspectiveDistance.isFOVEnabled()) return;

            float perspectiveFOV = PerspectiveDistance.getFOV();
            cir.setReturnValue(perspectiveFOV);
        } catch (Throwable t) {
            System.err.println("[gomod] PerspectiveMixin: getFOVModifier error: " + t.getMessage());
        }
    }
}
