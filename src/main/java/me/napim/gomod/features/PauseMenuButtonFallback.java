package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.event.MouseEvent;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.weavemc.loader.api.event.TickEvent;
import me.napim.gomod.util.GuiOpener;
import org.lwjgl.input.Mouse;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;

/**
 * Event-based fallback for adding the gomod button to the pause menu
 * when GuiIngameMenu mixins are skipped by Genesis.
 */
public class PauseMenuButtonFallback {
    private static final int GOMOD_BUTTON_ID = 999;

    private GuiButton gomodButton = null;
    private int lastScreenIdentity = 0;
    private Field buttonListField = null;
    private boolean buttonListFieldTried = false;
    private boolean lastLeftDown = false;
    private long lastOpenMs = 0L;

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (!ClientTickHub.isFeatureEnabled("PauseMenuButtonFallback")) return;
        long _profT = FeatureProfiler.begin("PauseMenuButtonFallback");
        try { refreshPauseMenuButton(); } finally { FeatureProfiler.end("PauseMenuButtonFallback", _profT); }
    }

    @SubscribeEvent
    public void onMouse(MouseEvent event) {
        if (event == null || !event.getButtonState() || event.getButton() != 0) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.currentScreen == null) {
            return;
        }

        GuiScreen screen = (GuiScreen) mc.currentScreen;
        List<GuiButton> buttons = getButtonList(screen);
        if (!isLikelyPauseMenu(screen, buttons)) {
            return;
        }

        ensureButton(screen, mc, buttons);

        if (gomodButton == null) {
            return;
        }

        ScaledResolution sr = new ScaledResolution(mc);
        int mouseX = event.getX() * sr.getScaledWidth() / Math.max(1, mc.displayWidth);
        int mouseY = sr.getScaledHeight() - (event.getY() * sr.getScaledHeight() / Math.max(1, mc.displayHeight)) - 1;

        if (gomodButton.mousePressed(mc, mouseX, mouseY)) {
            try {
                gomodButton.playPressSound(mc.getSoundHandler());
            } catch (Throwable ignored) {
            }
            tryOpenGomodGui();
            event.setCancelled(true);
        }
    }

    private void refreshPauseMenuButton() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.currentScreen == null) {
            gomodButton = null;
            lastScreenIdentity = 0;
            lastLeftDown = false;
            return;
        }

        GuiScreen screen = (GuiScreen) mc.currentScreen;
        List<GuiButton> buttons = getButtonList(screen);
        if (!isLikelyPauseMenu(screen, buttons)) {
            gomodButton = null;
            lastScreenIdentity = 0;
            lastLeftDown = false;
            return;
        }

        ensureButton(screen, mc, buttons);
        handleManualClick(mc);
    }

    private void ensureButton(GuiScreen screen, Minecraft mc, List<GuiButton> buttons) {
        if (screen == null) {
            return;
        }

        int identity = System.identityHashCode(screen);
        if (identity != lastScreenIdentity) {
            gomodButton = null;
            lastScreenIdentity = identity;
            buttonListField = null;
            buttonListFieldTried = false;
        }

        if (buttons == null) {
            buttons = getButtonList(screen);
        }
        if (buttons == null) {
            return;
        }

        for (GuiButton button : buttons) {
            if (button == null) {
                continue;
            }
            if (button.id == GOMOD_BUTTON_ID) {
                gomodButton = button;
                return;
            }
            if (button.displayString != null && button.displayString.toLowerCase().contains("gomod")) {
                gomodButton = button;
                return;
            }
        }

        if (gomodButton == null) {
            ScaledResolution sr = new ScaledResolution(mc);
            int buttonWidth = 120;
            int buttonHeight = 20;
            int centerX = sr.getScaledWidth() / 2;
            int buttonY = findButtonY(buttons, buttonHeight, sr.getScaledHeight());

            gomodButton = new GuiButton(
                    GOMOD_BUTTON_ID,
                    centerX - buttonWidth / 2,
                    buttonY,
                    buttonWidth,
                    buttonHeight,
                    EnumChatFormatting.DARK_GREEN + "gomod Menu"
            );
            buttons.add(gomodButton);
        }
    }

    private int findButtonY(List<GuiButton> buttons, int buttonHeight, int screenHeight) {
        int fallbackY = screenHeight / 4 + 120 + 24;
        if (buttons == null || buttons.isEmpty()) {
            return clampButtonY(fallbackY, buttonHeight, screenHeight);
        }

        for (GuiButton button : buttons) {
            if (button == null || button.displayString == null) {
                continue;
            }
            String label = stripFormatting(button.displayString).toLowerCase(Locale.ROOT);
            if (label.contains("save and quit to title") || label.contains("disconnect")
                    || label.contains("return to menu") || label.contains("return to title")) {
                int candidate = button.yPosition + button.height + 8;
                return clampButtonY(candidate, buttonHeight, screenHeight);
            }
        }

        for (GuiButton button : buttons) {
            if (button == null || button.displayString == null) {
                continue;
            }
            String label = stripFormatting(button.displayString).toLowerCase(Locale.ROOT);
            if (label.contains("return to game") || label.contains("back to game") || label.contains("resume game")) {
                int candidate = button.yPosition + button.height + 4;
                return clampButtonY(candidate, buttonHeight, screenHeight);
            }
        }

        int maxY = 0;
        for (GuiButton button : buttons) {
            if (button == null) {
                continue;
            }
            maxY = Math.max(maxY, button.yPosition + button.height);
        }
        return clampButtonY(maxY + 4, buttonHeight, screenHeight);
    }

    private int clampButtonY(int y, int buttonHeight, int screenHeight) {
        int minY = 8;
        int maxY = Math.max(minY, screenHeight - buttonHeight - 8);
        if (y < minY) {
            return minY;
        }
        if (y > maxY) {
            return maxY;
        }
        return y;
    }

    private void handleManualClick(Minecraft mc) {
        if (mc == null || gomodButton == null) {
            lastLeftDown = Mouse.isButtonDown(0);
            return;
        }

        boolean down = Mouse.isButtonDown(0);
        if (down && !lastLeftDown) {
            ScaledResolution sr = new ScaledResolution(mc);
            int mouseX = Mouse.getX() * sr.getScaledWidth() / Math.max(1, mc.displayWidth);
            int mouseY = sr.getScaledHeight() - (Mouse.getY() * sr.getScaledHeight() / Math.max(1, mc.displayHeight)) - 1;

            if (gomodButton.mousePressed(mc, mouseX, mouseY)) {
                try {
                    gomodButton.playPressSound(mc.getSoundHandler());
                } catch (Throwable ignored) {
                }
                tryOpenGomodGui();
            }
        }
        lastLeftDown = down;
    }

    private void tryOpenGomodGui() {
        long now = System.currentTimeMillis();
        if (now - lastOpenMs < 250L) {
            return;
        }
        lastOpenMs = now;
        GuiOpener.open("me.napim.gomod.gui.GomodGUI");
    }

    @SuppressWarnings("unchecked")
    private List<GuiButton> getButtonList(GuiScreen screen) {
        try {
            Field field = getButtonListField(screen);
            if (field == null) {
                return null;
            }
            Object value = field.get(screen);
            if (value instanceof List) {
                return (List<GuiButton>) value;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Field getButtonListField(GuiScreen screen) {
        if (buttonListFieldTried) {
            return buttonListField;
        }
        buttonListFieldTried = true;
        Field field = tryGetButtonListField(GuiScreen.class);
        if (field != null) {
            buttonListField = field;
            return field;
        }
        if (screen != null) {
            for (Class<?> cls = screen.getClass(); cls != null && cls != Object.class; cls = cls.getSuperclass()) {
                field = tryGetButtonListField(cls);
                if (field != null) {
                    buttonListField = field;
                    return field;
                }
                field = tryFindButtonListField(screen, cls);
                if (field != null) {
                    buttonListField = field;
                    return field;
                }
            }
        }
        return null;
    }

    private Field tryFindButtonListField(GuiScreen screen, Class<?> cls) {
        for (Field candidate : cls.getDeclaredFields()) {
            if (!List.class.isAssignableFrom(candidate.getType())) {
                continue;
            }
            candidate.setAccessible(true);
            try {
                Object value = candidate.get(screen);
                if (!(value instanceof List)) {
                    continue;
                }
                List<?> list = (List<?>) value;
                if (list.isEmpty()) {
                    return candidate;
                }
                for (Object item : list) {
                    if (item instanceof GuiButton) {
                        return candidate;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private Field tryGetButtonListField(Class<?> cls) {
        if (cls == null) {
            return null;
        }
        String[] names = new String[] { "buttonList", "field_146292_n" };
        for (String name : names) {
            try {
                Field field = cls.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private boolean isLikelyPauseMenu(GuiScreen screen, List<GuiButton> buttons) {
        if (screen == null) {
            return false;
        }
        if (screen instanceof GuiIngameMenu) {
            return true;
        }
        String className = screen.getClass().getName().toLowerCase(Locale.ROOT);
        if (className.contains("ingamemenu") || className.contains("pause")) {
            return true;
        }
        if (buttons != null) {
            for (GuiButton button : buttons) {
                if (button == null || button.displayString == null) {
                    continue;
                }
                String label = stripFormatting(button.displayString).toLowerCase(Locale.ROOT);
                if (label.contains("return to game") || label.contains("back to game")) {
                    return true;
                }
            }
        }
        return false;
    }

    private String stripFormatting(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String stripped = EnumChatFormatting.getTextWithoutFormattingCodes(text);
        return stripped == null ? "" : stripped;
    }
}
