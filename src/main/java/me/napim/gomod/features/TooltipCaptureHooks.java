package me.napim.gomod.features;

import net.minecraft.item.ItemStack;

import java.lang.reflect.Method;
import java.util.List;

public final class TooltipCaptureHooks {
    private static boolean resolved = false;
    private static Method beginItemTooltipMethod;
    private static Method endItemTooltipMethod;
    private static Method captureRenderedTooltipMethod;

    private TooltipCaptureHooks() {
    }

    public static void beginItemTooltip(ItemStack stack, int mouseX, int mouseY) {
        Method method = resolveMethods() ? beginItemTooltipMethod : null;
        if (method == null) {
            return;
        }
        try {
            method.invoke(null, stack, mouseX, mouseY);
        } catch (Throwable ignored) {
        }
    }

    public static void endItemTooltip() {
        Method method = resolveMethods() ? endItemTooltipMethod : null;
        if (method == null) {
            return;
        }
        try {
            method.invoke(null);
        } catch (Throwable ignored) {
        }
    }

    public static void captureRenderedTooltip(List<String> textLines, int mouseX, int mouseY) {
        Method method = resolveMethods() ? captureRenderedTooltipMethod : null;
        if (method == null) {
            return;
        }
        try {
            method.invoke(null, textLines, mouseX, mouseY);
        } catch (Throwable ignored) {
        }
    }

    private static boolean resolveMethods() {
        if (resolved) {
            return beginItemTooltipMethod != null
                    && endItemTooltipMethod != null
                    && captureRenderedTooltipMethod != null;
        }

        resolved = true;
        try {
            Class<?> loggerClass = Class.forName("me.napim.gomod.features.TooltipCaptureLogger");
            beginItemTooltipMethod = loggerClass.getMethod("beginItemTooltip", ItemStack.class, int.class, int.class);
            endItemTooltipMethod = loggerClass.getMethod("endItemTooltip");
            captureRenderedTooltipMethod = loggerClass.getMethod("captureRenderedTooltip", List.class, int.class, int.class);
        } catch (Throwable ignored) {
            beginItemTooltipMethod = null;
            endItemTooltipMethod = null;
            captureRenderedTooltipMethod = null;
        }

        return beginItemTooltipMethod != null
                && endItemTooltipMethod != null
                && captureRenderedTooltipMethod != null;
    }
}
