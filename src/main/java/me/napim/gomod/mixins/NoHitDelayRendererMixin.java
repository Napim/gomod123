/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.renderer.EntityRenderer
 *  org.spongepowered.asm.mixin.Mixin
 *  org.spongepowered.asm.mixin.injection.At
 *  org.spongepowered.asm.mixin.injection.Inject
 *  org.spongepowered.asm.mixin.injection.callback.CallbackInfo
 */
package me.napim.gomod.mixins;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.napim.gomod.command.NoHitDelayState;
import me.napim.gomod.features.NoHitDelay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value={EntityRenderer.class})
public abstract class NoHitDelayRendererMixin {
    private static boolean gomod$nhd_init = false;
    private static Field gomod$nhd_field = null;
    private static boolean gomod$nhd_failed = false;
    private static List<Field> gomod$nhd_allIntFields = null;
    private static Map<String, Integer> gomod$nhd_snapshot = null;

    @Inject(method={"updateCameraAndRender"}, at={@At(value="HEAD")})
    private void gomod$noHitDelayTick(float partialTicks, long nanoTime, CallbackInfo ci) {
        if (!NoHitDelay.isEnabled()) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                return;
            }
            NoHitDelayRendererMixin.gomod$noHitDelayReset(mc);
        }
        catch (Throwable t) {
            System.err.println("[gomod] NoHitDelayRendererMixin tick error: " + t.getMessage());
        }
    }

    private static void gomod$noHitDelayReset(Object mc) {
        if (gomod$nhd_failed) {
            return;
        }
        if (!gomod$nhd_init) {
            gomod$nhd_init = true;
            try {
                Class<?> mcClass = mc.getClass();
                String[] candidates = new String[]{"leftClickCounter", "field_71429_W", "field_71429_w"};
                for (String name : candidates) {
                    try {
                        Field f = mcClass.getDeclaredField(name);
                        f.setAccessible(true);
                        gomod$nhd_field = f;
                        System.out.println("[gomod] NoHitDelay: active (field: " + name + ")");
                        return;
                    }
                    catch (NoSuchFieldException f) {
                    }
                }
                gomod$nhd_allIntFields = new ArrayList<Field>();
                gomod$nhd_snapshot = new HashMap<String, Integer>();
                for (Field f : mcClass.getDeclaredFields()) {
                    if (f.getType() != Integer.TYPE) continue;
                    try {
                        f.setAccessible(true);
                        int val = f.getInt(mc);
                        gomod$nhd_allIntFields.add(f);
                        gomod$nhd_snapshot.put(f.getName(), val);
                    }
                    catch (Exception exception) {
                        // empty catch block
                    }
                }
                System.out.println("[gomod] NoHitDelay: field name not found, using behavioral detection (" + gomod$nhd_allIntFields.size() + " candidates)");
            }
            catch (Exception e) {
                gomod$nhd_failed = true;
                System.out.println("[gomod] NoHitDelay: INIT FAILED: " + e);
            }
            return;
        }
        if (gomod$nhd_field != null) {
            try {
                int before = gomod$nhd_field.getInt(mc);
                if (before != 0) {
                    gomod$nhd_field.setInt(mc, 0);
                    NoHitDelayState.recordReset(before, 0);
                }
            }
            catch (Exception exception) {
                // empty catch block
            }
            return;
        }
        if (gomod$nhd_allIntFields != null) {
            try {
                for (Field f : gomod$nhd_allIntFields) {
                    int current = f.getInt(mc);
                    Integer prev = gomod$nhd_snapshot.get(f.getName());
                    if (prev != null && current == 10 && prev != 10) {
                        gomod$nhd_field = f;
                        gomod$nhd_allIntFields = null;
                        gomod$nhd_snapshot = null;
                        f.setInt(mc, 0);
                        NoHitDelayState.recordReset(current, 0);
                        System.out.println("[gomod] NoHitDelay: active (detected field: " + f.getName() + ")");
                        return;
                    }
                    gomod$nhd_snapshot.put(f.getName(), current);
                }
            }
            catch (Exception exception) {
                // empty catch block
            }
        }
    }
}

