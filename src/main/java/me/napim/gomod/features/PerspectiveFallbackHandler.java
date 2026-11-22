package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.weavemc.loader.api.event.RenderWorldEvent;
import net.weavemc.loader.api.event.SubscribeEvent;
import org.lwjgl.input.Keyboard;

/**
 * Event-based fallback for perspective distance controls when EntityRenderer mixins are skipped.
 * Scroll wheel zoom is handled via Forge MouseEvent in ForgeEventBridge.
 */
public class PerspectiveFallbackHandler {
    private int prevThirdPersonView = 0;
    private float currentDistance = 4.0f;
    private float targetDistance = 4.0f;
    private float originalFOV = 70.0f;

    private boolean reflectionAttempted = false;
    private boolean reflectionSuccessful = false;
    private java.lang.reflect.Field thirdPersonDistanceField = null;
    private java.lang.reflect.Field thirdPersonDistancePrevField = null;

    private boolean guiFocusCheckAttempted = false;
    private boolean guiFocusCheckSuccessful = false;
    private java.lang.reflect.Field focusedField = null;

    private static java.lang.reflect.Field resolveField(Class<?> clazz, String srgName, String mcpName) {
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

    @SubscribeEvent
    public void onRenderWorld(RenderWorldEvent event) {
        if (MixinStatus.perspectiveRendererActive) {
            return;
        }
        applyPerspectiveDistance();
    }

    private void applyPerspectiveDistance() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.gameSettings == null) {
                return;
            }

            int currentView = mc.gameSettings.thirdPersonView;

            if (prevThirdPersonView == 0 && currentView != 0) {
                try {
                    float configDistance = PerspectiveDistance.getDistance();
                    currentDistance = configDistance;
                    targetDistance = configDistance;
                    originalFOV = mc.gameSettings.fovSetting;

                    if (reflectionSuccessful && thirdPersonDistanceField != null) {
                        try {
                            thirdPersonDistanceField.setFloat(mc.entityRenderer, configDistance);
                            if (thirdPersonDistancePrevField != null) {
                                thirdPersonDistancePrevField.setFloat(mc.entityRenderer, configDistance);
                            }
                        } catch (Throwable e) {
                            System.err.println("[gomod] PerspectiveFallback: reflection set on enter failed: " + e.getMessage());
                        }
                    }
                } catch (Throwable t) {
                    System.err.println("[gomod] PerspectiveFallback: enter third-person error: " + t.getMessage());
                }
            }

            if (prevThirdPersonView != 0 && currentView == 0 && PerspectiveDistance.isResetOnExit()) {
                try {
                    PerspectiveDistance.setDistance(4.0f);
                    currentDistance = 4.0f;
                    targetDistance = 4.0f;

                    if (reflectionSuccessful && thirdPersonDistanceField != null) {
                        try {
                            thirdPersonDistanceField.setFloat(mc.entityRenderer, 4.0f);
                            if (thirdPersonDistancePrevField != null) {
                                thirdPersonDistancePrevField.setFloat(mc.entityRenderer, 4.0f);
                            }
                        } catch (Throwable e) {
                            System.err.println("[gomod] PerspectiveFallback: reflection reset on exit failed: " + e.getMessage());
                        }
                    }
                } catch (Throwable t) {
                    System.err.println("[gomod] PerspectiveFallback: exit third-person error: " + t.getMessage());
                }
            }

            if (prevThirdPersonView != 0 && currentView == 0) {
                try {
                    mc.gameSettings.fovSetting = originalFOV;
                } catch (Throwable t) {
                    System.err.println("[gomod] PerspectiveFallback: FOV restore error: " + t.getMessage());
                }
            }

            prevThirdPersonView = currentView;

            if (currentView == 0) {
                return;
            }

            boolean perspectiveEnabled = PerspectiveDistance.isEnabled();
            boolean advancedAllowed = PerspectiveDistance.isAdvancedPerspectiveAllowed(mc);

            if (!perspectiveEnabled || !advancedAllowed) {
                try {
                    if (perspectiveEnabled && PerspectiveDistance.isFOVEnabled()) {
                        mc.gameSettings.fovSetting = PerspectiveDistance.getFOV();
                    }
                } catch (Throwable t) {
                    System.err.println("[gomod] PerspectiveFallback: FOV apply error: " + t.getMessage());
                }
                return;
            }

            try {
                int resetKey = PerspectiveDistance.getResetKeyCode();
                if (resetKey > 0 && Keyboard.isKeyDown(resetKey)) {
                    if (mc.currentScreen != null) {
                        if (mc.currentScreen instanceof net.minecraft.client.gui.GuiChat ||
                            mc.currentScreen instanceof net.minecraft.client.gui.GuiCommandBlock ||
                            mc.currentScreen instanceof net.minecraft.client.gui.GuiRepair) {
                            return;
                        }

                        if (!guiFocusCheckAttempted || !guiFocusCheckSuccessful) {
                            try {
                                java.lang.reflect.Field focused = mc.currentScreen.getClass().getDeclaredField("field_146209_f");
                                if (focused != null) {
                                    focused.setAccessible(true);
                                    focusedField = focused;
                                    guiFocusCheckSuccessful = true;
                                }
                            } catch (Exception e) {
                                if (e.getClass().getSimpleName().equals("NoSuchFieldException")) {
                                    guiFocusCheckSuccessful = false;
                                } else {
                                    System.err.println("[gomod] PerspectiveFallback: focus check error: " + e.getMessage());
                                    guiFocusCheckSuccessful = false;
                                }
                            } catch (Throwable e) {
                                System.err.println("[gomod] PerspectiveFallback: focus check error: " + e.getMessage());
                                guiFocusCheckSuccessful = false;
                            }
                            guiFocusCheckAttempted = true;
                        }

                        if (guiFocusCheckSuccessful && focusedField != null) {
                            try {
                                Object focused = focusedField.get(mc.currentScreen);
                                if (focused != null) {
                                    return;
                                }
                            } catch (Throwable e) {
                                guiFocusCheckSuccessful = false;
                                System.err.println("[gomod] PerspectiveFallback: focus field access failed: " + e.getMessage());
                            }
                        }
                    }

                    PerspectiveDistance.setDistance(4.0f);
                    targetDistance = 4.0f;
                    currentDistance = 4.0f;
                }
            } catch (Throwable t) {
                System.err.println("[gomod] PerspectiveFallback: reset key error: " + t.getMessage());
            }

            targetDistance = PerspectiveDistance.getDistance();

            if (PerspectiveDistance.isSmoothZoom()) {
                float smoothSpeed = PerspectiveDistance.getSmoothSpeed();
                float diff = targetDistance - currentDistance;
                if (Math.abs(diff) > 0.001f) {
                    currentDistance += diff * smoothSpeed;
                } else {
                    currentDistance = targetDistance;
                }
            } else {
                currentDistance = targetDistance;
            }

            float dist = currentDistance;

            // SRG: field_78490_B = thirdPersonDistance, field_78491_C = thirdPersonDistancePrev
            if (!reflectionAttempted) {
                try {
                    EntityRenderer renderer = mc.entityRenderer;
                    java.lang.reflect.Field f = resolveField(renderer.getClass(), "field_78490_B", "thirdPersonDistance");
                    if (f != null) {
                        f.setFloat(renderer, dist);
                        thirdPersonDistanceField = f;

                        java.lang.reflect.Field f2 = resolveField(renderer.getClass(), "field_78491_C", "thirdPersonDistancePrev");
                        if (f2 != null) {
                            f2.setFloat(renderer, dist);
                            thirdPersonDistancePrevField = f2;
                        }

                        reflectionSuccessful = true;
                    } else {
                        ReflectionLogger.logReflectionFailure("PerspectiveFallback: field_78490_B / thirdPersonDistance not found", new Exception("field not found"));
                        reflectionSuccessful = false;
                    }
                } catch (Exception e) {
                    ReflectionLogger.logReflectionFailure("PerspectiveFallback: set distance fields", e);
                    reflectionSuccessful = false;
                } catch (Throwable e) {
                    ReflectionLogger.logReflectionFailure("PerspectiveFallback: set distance fields (throwable)", e);
                    reflectionSuccessful = false;
                }
                reflectionAttempted = true;
            }

            if (reflectionSuccessful && thirdPersonDistanceField != null) {
                try {
                    thirdPersonDistanceField.setFloat(mc.entityRenderer, dist);
                    if (thirdPersonDistancePrevField != null) {
                        thirdPersonDistancePrevField.setFloat(mc.entityRenderer, dist);
                    }
                } catch (Throwable e) {
                    reflectionSuccessful = false;
                    ReflectionLogger.logReflectionFailure("PerspectiveFallback: write cached fields", e);
                }
            }

            try {
                if (PerspectiveDistance.isFOVEnabled()) {
                    mc.gameSettings.fovSetting = PerspectiveDistance.getFOV();
                }
            } catch (Throwable t) {
                System.err.println("[gomod] PerspectiveFallback: FOV apply error: " + t.getMessage());
            }
        } catch (Throwable e) {
            ReflectionLogger.logReflectionFailure("PerspectiveFallback: update", e);
        }
    }
}
