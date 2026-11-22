package me.napim.gomod.features;

import me.napim.gomod.Main;
import me.napim.gomod.gui.GomodSocialGUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

/**
 * Registers gomod keybinds in vanilla Controls and keeps them synchronized with legacy config-backed GUI settings.
 */
public final class GomodKeybinds {
    private static final String CATEGORY = "key.categories.gomod123";

    private static final KeyBinding ASK_FOR_LOOT = new KeyBinding("key.gomod123.ask_for_loot", 0, CATEGORY);
    private static final KeyBinding PLAYER_CALLER = new KeyBinding("key.gomod123.player_caller", 0, CATEGORY);
    private static final KeyBinding CHEST_ROUTES_PLACE_FEET = new KeyBinding("key.gomod123.chest_routes.place_feet", 0, CATEGORY);
    private static final KeyBinding CHEST_ROUTES_TOGGLE = new KeyBinding("key.gomod123.chest_routes.toggle_render", 0, CATEGORY);
    private static final KeyBinding CHEST_ROUTES_UNDO = new KeyBinding("key.gomod123.chest_routes.undo", 0, CATEGORY);
    private static final KeyBinding TAB_KILLERS_TOGGLE = new KeyBinding("key.gomod123.tab_killers.toggle", 0, CATEGORY);
    /** Hold + scroll for block-precise render distance (synced with {@link AdvancedRenderDistance}). */
    private static final KeyBinding PERSPECTIVE_RESET = new KeyBinding("key.gomod123.perspective.reset_distance", 0, CATEGORY);
    private static final KeyBinding PERSPECTIVE_ZOOM = new KeyBinding("key.gomod123.perspective.zoom_hold", 0, CATEGORY);
    private static final KeyBinding RADIAL_MENU = new KeyBinding("key.gomod123.radial_menu", 0, CATEGORY);
    private static final KeyBinding CHAT_MOVEMENT = new KeyBinding("key.gomod123.chat_movement.open", 0, CATEGORY);
    /** Social hotkey is config-only ({@link #handleGomodFriendsListOpen}) — not in vanilla Controls (Tab conflict). */
    private static final KeyBinding BARRIER_VISIBILITY_TOGGLE = new KeyBinding("key.gomod123.barrier_visibility.toggle", 0, CATEGORY);
    private static final KeyBinding SELF_OPACITY_TOGGLE = new KeyBinding("key.gomod123.self_opacity.toggle", 0, CATEGORY);
    private static final KeyBinding BLOCKS_FINDER_TOGGLE = new KeyBinding("key.gomod123.blocks_finder.toggle", 0, CATEGORY);
    /** Hold + scroll wheel to adjust block-finder world opacity in-game. */
    private static final KeyBinding BLOCKS_FINDER_WORLD_OPACITY = new KeyBinding("key.gomod123.blocks_finder.world_opacity", 0, CATEGORY);

    private static boolean registered;
    private static int lastAskForLootCode = Integer.MIN_VALUE;
    private static int lastPlayerCallerCode = Integer.MIN_VALUE;
    private static int lastChestRoutesPlaceFeetCode = Integer.MIN_VALUE;
    private static int lastChestRoutesToggleCode = Integer.MIN_VALUE;
    private static int lastChestRoutesUndoCode = Integer.MIN_VALUE;
    private static int lastTabKillersToggleCode = Integer.MIN_VALUE;
    private static int lastPerspectiveResetCode = Integer.MIN_VALUE;
    private static int lastPerspectiveZoomCode = Integer.MIN_VALUE;
    private static int lastRadialMenuCode = Integer.MIN_VALUE;
    private static int lastChatMovementCode = Integer.MIN_VALUE;
    private static int lastBarrierVisibilityToggleCode = Integer.MIN_VALUE;
    private static int lastSelfOpacityToggleCode = Integer.MIN_VALUE;
    private static int lastBlocksFinderToggleCode = Integer.MIN_VALUE;
    private static int lastBlocksFinderWorldOpacityCode = Integer.MIN_VALUE;
    private static boolean socialKeyWasDown;
    private static boolean selfOpacityKeyWasDown;
    private static final Map<String, String> LOCALIZED_KEYS = new HashMap<String, String>();
    private static boolean translationsLoaded;
    private static boolean localeTranslationsApplied;

    private GomodKeybinds() {
    }

    static {
        registerDefaultTranslations();
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        loadTranslationsFromClasspath();
        applyTranslationsToClientLocale();
        registerKeyBinding(ASK_FOR_LOOT);
        registerKeyBinding(PLAYER_CALLER);
        registerKeyBinding(CHEST_ROUTES_PLACE_FEET);
        registerKeyBinding(CHEST_ROUTES_TOGGLE);
        registerKeyBinding(CHEST_ROUTES_UNDO);
        registerKeyBinding(TAB_KILLERS_TOGGLE);
        registerKeyBinding(PERSPECTIVE_RESET);
        registerKeyBinding(PERSPECTIVE_ZOOM);
        registerKeyBinding(RADIAL_MENU);
        registerKeyBinding(CHAT_MOVEMENT);
        registerKeyBinding(BARRIER_VISIBILITY_TOGGLE);
        registerKeyBinding(SELF_OPACITY_TOGGLE);
        registerKeyBinding(BLOCKS_FINDER_TOGGLE);
        registerKeyBinding(BLOCKS_FINDER_WORLD_OPACITY);
        syncBindingsFromConfig();
        rememberSyncedCodes();
    }

    /** After profile switch: config-backed key codes may change without re-registering bindings. */
    public static void resyncFromConfigAfterProfileChange() {
        if (!registered) {
            return;
        }
        syncBindingsFromConfig();
        rememberSyncedCodes();
    }

    /** Resolve a gomod Controls label; falls back to the key when unknown. */
    public static String localizeKey(String translationKey) {
        if (translationKey == null) {
            return "";
        }
        String direct = LOCALIZED_KEYS.get(translationKey);
        if (direct != null) {
            return direct;
        }
        try {
            String viaI18n = net.minecraft.client.resources.I18n.format(translationKey, new Object[0]);
            if (viaI18n != null && !viaI18n.equals(translationKey)) {
                return viaI18n;
            }
        } catch (Throwable ignored) {
        }
        return translationKey;
    }

    /** Re-inject lang entries once the client {@link net.minecraft.client.resources.Locale} exists. */
    public static void applyTranslationsToClientLocale() {
        if (localeTranslationsApplied) {
            return;
        }
        if (!translationsLoaded) {
            loadTranslationsFromClasspath();
        }
        if (!injectIntoClientLocaleBatch(LOCALIZED_KEYS)) {
            return;
        }
        localeTranslationsApplied = true;
    }

    public static void onClientTick() {
        applyTranslationsToClientLocale();
        if (!registered) {
            return;
        }
        syncAskForLoot();
        syncPlayerCaller();
        syncChestRoutesPlaceFeet();
        syncChestRoutesToggle();
        syncChestRoutesUndo();
        syncTabKillersToggle();
        syncPerspectiveAdvancedRenderDistance();
        syncPerspectiveZoom();
        syncRadialMenu();
        syncChatMovement();
        syncBarrierVisibilityToggle();
        syncSelfOpacityToggle();
        syncBlocksFinderToggle();
        syncBlocksFinderWorldOpacity();
        handleGomodFriendsListOpen();
        handleBarrierVisibilityToggle();
        handleSelfOpacityToggle();
        ChatMovement.onClientTickEnd();
    }

    /**
     * Vanilla runTick fallback for binds that use hardware edge detection.
     * Safe to call in addition to platform tick events.
     */
    public static void pollGuaranteedRuntimeKeybinds() {
        if (!registered) {
            return;
        }
        handleGomodFriendsListOpen();
        syncSelfOpacityToggle();
        handleSelfOpacityToggle();
    }

    /**
     * Push current config-backed key codes into vanilla {@link KeyBinding}s.
     *
     * <p>Do not call {@code GameSettings.saveOptions()} here. On OptiFine/Lunar that method serializes
     * every client option, not only keybinds. Calling it during gomod startup can therefore persist an
     * incompletely initialized value for unrelated settings such as Lazy Chunk Loading. Gomod key codes
     * are already persisted in gomod's own config.</p>
     */
    public static void applyConfigBindings() {
        if (!registered) {
            return;
        }
        applyKeyCode(ASK_FOR_LOOT, ConfigManager.getAskForLootKeyCode());
        applyKeyCode(PLAYER_CALLER, ConfigManager.getPlayerCallerKeyCode());
        applyKeyCode(CHEST_ROUTES_PLACE_FEET, ConfigManager.getChestRoutesPlaceFeetKeyCode());
        applyKeyCode(CHEST_ROUTES_TOGGLE, ConfigManager.getChestRoutesToggleKeyCode());
        applyKeyCode(CHEST_ROUTES_UNDO, ConfigManager.getChestRoutesUndoKeyCode());
        applyKeyCode(TAB_KILLERS_TOGGLE, ConfigManager.getKillCounterTabKillersKeyCode());
        applyKeyCode(PERSPECTIVE_RESET, AdvancedRenderDistance.getKeyCode());
        applyKeyCode(PERSPECTIVE_ZOOM, PerspectiveDistance.getZoomKeyCode());
        applyKeyCode(RADIAL_MENU, ConfigManager.getRadialMenuKeyCode());
        applyKeyCode(CHAT_MOVEMENT, ConfigManager.getChatMovementKeyCode());
        applyKeyCode(BARRIER_VISIBILITY_TOGGLE, ConfigManager.getBarrierVisibilityKeyCode());
        applyKeyCode(SELF_OPACITY_TOGGLE, ConfigManager.getSelfOpacityKeyCode());
        applyKeyCode(BLOCKS_FINDER_TOGGLE, ConfigManager.getBlocksFinderKeyCode());
        applyKeyCode(BLOCKS_FINDER_WORLD_OPACITY, ConfigManager.getBlocksFinderWorldOpacityKeyCode());
        rememberSyncedCodes();
    }

    private static void syncAskForLoot() {
        int bindingCode = ASK_FOR_LOOT.getKeyCode();
        if (bindingCode != lastAskForLootCode) {
            AskForLoot.setKeyCode(bindingCode);
            lastAskForLootCode = bindingCode;
            return;
        }
        int configCode = ConfigManager.getAskForLootKeyCode();
        if (configCode != lastAskForLootCode) {
            applyKeyCode(ASK_FOR_LOOT, configCode);
            lastAskForLootCode = configCode;
        }
    }

    private static void syncPlayerCaller() {
        int bindingCode = PLAYER_CALLER.getKeyCode();
        if (bindingCode != lastPlayerCallerCode) {
            PlayerCaller.setKeyCode(bindingCode);
            lastPlayerCallerCode = bindingCode;
            return;
        }
        int configCode = ConfigManager.getPlayerCallerKeyCode();
        if (configCode != lastPlayerCallerCode) {
            applyKeyCode(PLAYER_CALLER, configCode);
            lastPlayerCallerCode = configCode;
        }
    }

    private static void syncChestRoutesPlaceFeet() {
        int bindingCode = CHEST_ROUTES_PLACE_FEET.getKeyCode();
        if (bindingCode != lastChestRoutesPlaceFeetCode) {
            ConfigManager.setChestRoutesPlaceFeetKeyCode(bindingCode);
            lastChestRoutesPlaceFeetCode = bindingCode;
            return;
        }
        int configCode = ConfigManager.getChestRoutesPlaceFeetKeyCode();
        if (configCode != lastChestRoutesPlaceFeetCode) {
            applyKeyCode(CHEST_ROUTES_PLACE_FEET, configCode);
            lastChestRoutesPlaceFeetCode = configCode;
        }
    }

    private static void syncChestRoutesToggle() {
        int bindingCode = CHEST_ROUTES_TOGGLE.getKeyCode();
        if (bindingCode != lastChestRoutesToggleCode) {
            ConfigManager.setChestRoutesToggleKeyCode(bindingCode);
            lastChestRoutesToggleCode = bindingCode;
            return;
        }
        int configCode = ConfigManager.getChestRoutesToggleKeyCode();
        if (configCode != lastChestRoutesToggleCode) {
            applyKeyCode(CHEST_ROUTES_TOGGLE, configCode);
            lastChestRoutesToggleCode = configCode;
        }
    }

    private static void syncChestRoutesUndo() {
        int bindingCode = CHEST_ROUTES_UNDO.getKeyCode();
        if (bindingCode != lastChestRoutesUndoCode) {
            ConfigManager.setChestRoutesUndoKeyCode(bindingCode);
            lastChestRoutesUndoCode = bindingCode;
            return;
        }
        int configCode = ConfigManager.getChestRoutesUndoKeyCode();
        if (configCode != lastChestRoutesUndoCode) {
            applyKeyCode(CHEST_ROUTES_UNDO, configCode);
            lastChestRoutesUndoCode = configCode;
        }
    }

    private static void syncTabKillersToggle() {
        int bindingCode = TAB_KILLERS_TOGGLE.getKeyCode();
        if (bindingCode != lastTabKillersToggleCode) {
            ConfigManager.setKillCounterTabKillersKeyCode(bindingCode);
            lastTabKillersToggleCode = bindingCode;
            return;
        }
        int configCode = ConfigManager.getKillCounterTabKillersKeyCode();
        if (configCode != lastTabKillersToggleCode) {
            applyKeyCode(TAB_KILLERS_TOGGLE, configCode);
            lastTabKillersToggleCode = configCode;
        }
    }

    private static void syncPerspectiveAdvancedRenderDistance() {
        int bindingCode = PERSPECTIVE_RESET.getKeyCode();
        if (bindingCode != lastPerspectiveResetCode) {
            AdvancedRenderDistance.setKeyCode(bindingCode);
            lastPerspectiveResetCode = bindingCode;
            return;
        }
        int configCode = AdvancedRenderDistance.getKeyCode();
        if (configCode != lastPerspectiveResetCode) {
            applyKeyCode(PERSPECTIVE_RESET, configCode);
            lastPerspectiveResetCode = configCode;
        }
    }

    /** Called from {@link me.napim.gomod.gui.PerspectiveGUI} when capturing the advanced render distance key. */
    public static void applyAdvancedRenderDistanceKeyCode(int code) {
        if (!registered) {
            return;
        }
        AdvancedRenderDistance.setKeyCode(code);
        applyKeyCode(PERSPECTIVE_RESET, code);
        lastPerspectiveResetCode = code;
    }

    public static KeyBinding getAdvancedRenderDistanceKeyBinding() {
        return registered ? PERSPECTIVE_RESET : null;
    }

    public static boolean isAdvancedRenderDistanceModifierHeld(Minecraft mc) {
        if (mc == null) {
            return false;
        }
        KeyBinding bind = getAdvancedRenderDistanceKeyBinding();
        if (bind != null && bind.getKeyCode() > 0) {
            return me.napim.gomod.util.ClientInputHelper.isKeyBindingHeld(mc, bind);
        }
        int code = AdvancedRenderDistance.getKeyCode();
        return code > 0 && org.lwjgl.input.Keyboard.isKeyDown(code);
    }

    private static void syncPerspectiveZoom() {
        int bindingCode = PERSPECTIVE_ZOOM.getKeyCode();
        if (bindingCode != lastPerspectiveZoomCode) {
            PerspectiveDistance.setZoomKeyCode(bindingCode);
            lastPerspectiveZoomCode = bindingCode;
            return;
        }
        int configCode = PerspectiveDistance.getZoomKeyCode();
        if (configCode != lastPerspectiveZoomCode) {
            applyKeyCode(PERSPECTIVE_ZOOM, configCode);
            lastPerspectiveZoomCode = configCode;
        }
    }

    private static void syncRadialMenu() {
        int bindingCode = RADIAL_MENU.getKeyCode();
        if (bindingCode != lastRadialMenuCode) {
            ConfigManager.setRadialMenuKeyCode(bindingCode);
            lastRadialMenuCode = bindingCode;
            return;
        }
        int configCode = ConfigManager.getRadialMenuKeyCode();
        if (configCode != lastRadialMenuCode) {
            applyKeyCode(RADIAL_MENU, configCode);
            lastRadialMenuCode = configCode;
        }
    }

    /** Called by {@link me.napim.gomod.gui.RadialMenuWheelSlotsGUI} to push a newly-captured code. */
    public static void applyRadialMenuKeyCode(int code) {
        if (!registered) return;
        ConfigManager.setRadialMenuKeyCode(code);
        applyKeyCode(RADIAL_MENU, code);
        lastRadialMenuCode = code;
    }

    /** Vanilla registration path shared by Forge and native Weave/Lunar. */
    private static void registerKeyBinding(KeyBinding binding) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameSettings == null || binding == null) {
            return;
        }
        KeyBinding[] current = mc.gameSettings.keyBindings;
        if (current == null) {
            mc.gameSettings.keyBindings = new KeyBinding[] { binding };
            return;
        }
        for (KeyBinding existing : current) {
            if (existing == binding) {
                return;
            }
        }
        KeyBinding[] expanded = Arrays.copyOf(current, current.length + 1);
        expanded[current.length] = binding;
        mc.gameSettings.keyBindings = expanded;
        KeyBinding.resetKeyBindingArrayAndHash();
    }

    private static void syncChatMovement() {
        int bindingCode = CHAT_MOVEMENT.getKeyCode();
        if (bindingCode != lastChatMovementCode) {
            ConfigManager.setChatMovementKeyCode(bindingCode);
            lastChatMovementCode = bindingCode;
            return;
        }
        int configCode = ConfigManager.getChatMovementKeyCode();
        if (configCode != lastChatMovementCode) {
            applyKeyCode(CHAT_MOVEMENT, configCode);
            lastChatMovementCode = configCode;
        }
    }

    public static KeyBinding getChatMovementKeyBinding() {
        return registered ? CHAT_MOVEMENT : null;
    }

    /** Called by Chat Settings after capturing the movement-chat key. */
    public static void applyChatMovementKeyCode(int code) {
        ConfigManager.setChatMovementKeyCode(code);
        if (!registered) {
            return;
        }
        applyKeyCode(CHAT_MOVEMENT, code);
        lastChatMovementCode = code;
    }

    /** Called from {@link me.napim.gomod.gui.BarrierVisibilitySettingsGUI} when capturing a new key. */
    public static void applyBarrierVisibilityKeyCode(int code) {
        if (!registered) {
            return;
        }
        ConfigManager.setBarrierVisibilityKeyCode(code);
        applyKeyCode(BARRIER_VISIBILITY_TOGGLE, code);
        lastBarrierVisibilityToggleCode = code;
    }

    /** Called from Self Opacity settings after capturing a new key. */
    public static void applySelfOpacityKeyCode(int code) {
        ConfigManager.setSelfOpacityKeyCode(code);
        if (!registered) {
            return;
        }
        applyKeyCode(SELF_OPACITY_TOGGLE, code);
        lastSelfOpacityToggleCode = code;
    }

    /** Called from {@link me.napim.gomod.gui.BlocksFinderGUI} when capturing a new key. */
    public static void applyBlocksFinderKeyCode(int code) {
        if (!registered) {
            return;
        }
        ConfigManager.setBlocksFinderKeyCode(code);
        applyKeyCode(BLOCKS_FINDER_TOGGLE, code);
        lastBlocksFinderToggleCode = code;
    }

    public static void applyBlocksFinderWorldOpacityKeyCode(int code) {
        if (!registered) {
            return;
        }
        ConfigManager.setBlocksFinderWorldOpacityKeyCode(code);
        applyKeyCode(BLOCKS_FINDER_WORLD_OPACITY, code);
        lastBlocksFinderWorldOpacityCode = code;
    }

    /** Key name for Block Finder settings label ({@code V + SCROLL} when bound). */
    public static String formatBlocksFinderWorldOpacityBinding() {
        int code = ConfigManager.getBlocksFinderWorldOpacityKeyCode();
        if (code <= 0) {
            return "NONE";
        }
        String keyName = formatKeyName(code);
        return keyName == null || keyName.trim().isEmpty()
                ? "UNKNOWN"
                : keyName.toUpperCase(Locale.ROOT);
    }

    private static void syncBlocksFinderToggle() {
        int bindingCode = BLOCKS_FINDER_TOGGLE.getKeyCode();
        if (bindingCode != lastBlocksFinderToggleCode) {
            ConfigManager.setBlocksFinderKeyCode(bindingCode);
            lastBlocksFinderToggleCode = bindingCode;
            return;
        }
        int configCode = ConfigManager.getBlocksFinderKeyCode();
        if (configCode != lastBlocksFinderToggleCode) {
            applyKeyCode(BLOCKS_FINDER_TOGGLE, configCode);
            lastBlocksFinderToggleCode = configCode;
        }
    }

    private static void syncBlocksFinderWorldOpacity() {
        int bindingCode = BLOCKS_FINDER_WORLD_OPACITY.getKeyCode();
        if (bindingCode != lastBlocksFinderWorldOpacityCode) {
            ConfigManager.setBlocksFinderWorldOpacityKeyCode(bindingCode);
            lastBlocksFinderWorldOpacityCode = bindingCode;
            return;
        }
        int configCode = ConfigManager.getBlocksFinderWorldOpacityKeyCode();
        if (configCode != lastBlocksFinderWorldOpacityCode) {
            applyKeyCode(BLOCKS_FINDER_WORLD_OPACITY, configCode);
            lastBlocksFinderWorldOpacityCode = configCode;
        }
    }

    private static void syncBindingsFromConfig() {
        applyConfigBindings();
    }

    private static void rememberSyncedCodes() {
        lastAskForLootCode = ASK_FOR_LOOT.getKeyCode();
        lastPlayerCallerCode = PLAYER_CALLER.getKeyCode();
        lastChestRoutesPlaceFeetCode = CHEST_ROUTES_PLACE_FEET.getKeyCode();
        lastChestRoutesToggleCode = CHEST_ROUTES_TOGGLE.getKeyCode();
        lastChestRoutesUndoCode = CHEST_ROUTES_UNDO.getKeyCode();
        lastTabKillersToggleCode = TAB_KILLERS_TOGGLE.getKeyCode();
        lastPerspectiveResetCode = PERSPECTIVE_RESET.getKeyCode();
        lastPerspectiveZoomCode = PERSPECTIVE_ZOOM.getKeyCode();
        lastRadialMenuCode = RADIAL_MENU.getKeyCode();
        lastChatMovementCode = CHAT_MOVEMENT.getKeyCode();
        lastBarrierVisibilityToggleCode = BARRIER_VISIBILITY_TOGGLE.getKeyCode();
        lastSelfOpacityToggleCode = SELF_OPACITY_TOGGLE.getKeyCode();
        lastBlocksFinderToggleCode = BLOCKS_FINDER_TOGGLE.getKeyCode();
        lastBlocksFinderWorldOpacityCode = BLOCKS_FINDER_WORLD_OPACITY.getKeyCode();
    }

    private static void syncBarrierVisibilityToggle() {
        int bindingCode = BARRIER_VISIBILITY_TOGGLE.getKeyCode();
        if (bindingCode != lastBarrierVisibilityToggleCode) {
            ConfigManager.setBarrierVisibilityKeyCode(bindingCode);
            lastBarrierVisibilityToggleCode = bindingCode;
            return;
        }
        int configCode = ConfigManager.getBarrierVisibilityKeyCode();
        if (configCode != lastBarrierVisibilityToggleCode) {
            applyKeyCode(BARRIER_VISIBILITY_TOGGLE, configCode);
            lastBarrierVisibilityToggleCode = configCode;
        }
    }

    private static void syncSelfOpacityToggle() {
        int bindingCode = SELF_OPACITY_TOGGLE.getKeyCode();
        if (bindingCode != lastSelfOpacityToggleCode) {
            ConfigManager.setSelfOpacityKeyCode(bindingCode);
            lastSelfOpacityToggleCode = bindingCode;
            return;
        }
        int configCode = ConfigManager.getSelfOpacityKeyCode();
        if (configCode != lastSelfOpacityToggleCode) {
            applyKeyCode(SELF_OPACITY_TOGGLE, configCode);
            lastSelfOpacityToggleCode = configCode;
        }
    }

    public static void applyGomodFriendsListKeyCode(int keyCode) {
        ConfigManager.setGomodFriendsListKeyCodes(keyCode, 0);
    }

    public static void applyGomodFriendsListKeyChord(int firstKeyCode, int secondKeyCode) {
        ConfigManager.setGomodFriendsListKeyCodes(firstKeyCode, secondKeyCode);
        ConfigManager.saveConfig();
    }


    public static String formatKeyName(int keyCode) {
        if (keyCode <= 0) {
            return "Unbound";
        }
        String name = Keyboard.getKeyName(keyCode);
        return name == null || name.trim().isEmpty() ? "Key " + keyCode : name;
    }

    /** Tab and the vanilla player-list key always need Ctrl so the tab overlay still works. */
    public static boolean isSocialKeyAlwaysCtrlModifier(int keyCode) {
        if (keyCode <= 0) {
            return false;
        }
        if (keyCode == Keyboard.KEY_TAB) {
            return true;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.gameSettings != null && mc.gameSettings.keyBindPlayerList != null) {
                return keyCode == mc.gameSettings.keyBindPlayerList.getKeyCode();
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static String formatSocialEffectiveBinding() {
        int first = ConfigManager.getGomodFriendsListKeyCode();
        int second = ConfigManager.getGomodFriendsListSecondKeyCode();
        if (first <= 0) {
            return "Unbound";
        }
        String firstName = formatKeyName(first).toUpperCase(Locale.ROOT);
        if (second > 0) {
            return firstName + " + " + formatKeyName(second).toUpperCase(Locale.ROOT);
        }
        return firstName;
    }

    private static void handleGomodFriendsListOpen() {
        int first = ConfigManager.getGomodFriendsListKeyCode();
        int second = ConfigManager.getGomodFriendsListSecondKeyCode();
        if (first == 0) {
            socialKeyWasDown = false;
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            socialKeyWasDown = false;
            return;
        }
        GuiScreen current = mc.currentScreen;
        boolean onMainMenu = isMainMenuContext(mc, current);
        if (current instanceof GomodSocialGUI) {
            socialKeyWasDown = false;
            return;
        }
        if (mc.thePlayer == null && !onMainMenu) {
            socialKeyWasDown = false;
            return;
        }
        if (current != null && !onMainMenu) {
            socialKeyWasDown = false;
            return;
        }
        boolean down = Keyboard.isKeyDown(first) && (second <= 0 || Keyboard.isKeyDown(second));
        boolean pressed = down && !socialKeyWasDown;
        socialKeyWasDown = down;
        if (pressed) {
            mc.displayGuiScreen(new GomodSocialGUI(onMainMenu ? current : null));
        }
    }

    /** Direct title-screen key event fallback for launchers whose menu tick is not delivered reliably. */
    public static void handleSocialKeyTyped(int keyCode) {
        int first = ConfigManager.getGomodFriendsListKeyCode();
        int second = ConfigManager.getGomodFriendsListSecondKeyCode();
        if (first <= 0 || (keyCode != first && keyCode != second)) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        GuiScreen current = mc.currentScreen;
        boolean onMainMenu = isMainMenuContext(mc, current);
        if (!onMainMenu || current instanceof GomodSocialGUI) {
            return;
        }
        boolean down = Keyboard.isKeyDown(first) && (second <= 0 || Keyboard.isKeyDown(second));
        if (!down || socialKeyWasDown) {
            return;
        }
        socialKeyWasDown = true;
        mc.displayGuiScreen(new GomodSocialGUI(current));
    }

    /** Supports launcher title screens without stealing the hotkey from gomod's own settings GUIs. */
    private static boolean isMainMenuContext(Minecraft mc, GuiScreen current) {
        if (current instanceof GuiMainMenu) {
            return true;
        }
        if (mc == null || mc.theWorld != null || mc.thePlayer != null) {
            return false;
        }
        return current == null || !current.getClass().getName().startsWith("me.napim.gomod.gui.");
    }

    private static void handleBarrierVisibilityToggle() {
        if (BARRIER_VISIBILITY_TOGGLE.getKeyCode() == 0) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return;
        }
        if (mc.currentScreen != null) {
            return;
        }
        if (BARRIER_VISIBILITY_TOGGLE.isPressed()) {
            BarrierVisibility.toggleWithRendererReload();
            boolean on = ConfigManager.isBarrierVisibilityEnabled();
            String state = on ? "\u00a7aON" : "\u00a7cOFF";
            Main.sendMessage(Main.CHAT_PREFIX + "Barrier blocks: " + state);
        }
    }

    private static void handleSelfOpacityToggle() {
        int code = ConfigManager.getSelfOpacityKeyCode();
        if (code == 0) {
            selfOpacityKeyWasDown = false;
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            selfOpacityKeyWasDown = false;
            return;
        }
        int mouseButton = code <= -100 ? code + 100 : -(code + 1);
        boolean down = code > 0 ? Keyboard.isKeyDown(code) : Mouse.isButtonDown(mouseButton);
        boolean pressed = down && !selfOpacityKeyWasDown;
        selfOpacityKeyWasDown = down;
        if (mc.currentScreen != null) {
            return;
        }
        if (pressed) {
            SelfOpacity.setEnabled(!SelfOpacity.isEnabled());
        }
    }

    private static boolean applyKeyCode(KeyBinding binding, int keyCode) {
        if (binding.getKeyCode() == keyCode) {
            return false;
        }
        binding.setKeyCode(keyCode);
        KeyBinding.resetKeyBindingArrayAndHash();
        return true;
    }

    private static void registerDefaultTranslations() {
        putTranslation("key.categories.gomod123", "gomod123");
        putTranslation("key.gomod123.radial_menu", "Wheel modules");
        putTranslation("key.gomod123.chat_movement.open", "Movement chat");
        putTranslation("key.gomod123.tab_killers.toggle", "Tab victims");
        putTranslation("key.gomod123.ask_for_loot", "Ask for Loot");
        putTranslation("key.gomod123.player_caller", "Player Caller");
        putTranslation("key.gomod123.chest_routes.place_feet", "Chest Routes: Place checkpoint at feet");
        putTranslation("key.gomod123.chest_routes.toggle_render", "Chest Routes: Toggle rendering");
        putTranslation("key.gomod123.chest_routes.undo", "Chest Routes: Undo draft");
        putTranslation("key.gomod123.perspective.reset_distance", "Adv. render distance");
        putTranslation("key.gomod123.perspective.zoom_hold", "Adv. perspective: Zoom hold key");
        putTranslation("key.gomod123.barrier_visibility.toggle", "Barrier blocks");
        putTranslation("key.gomod123.self_opacity.toggle", "Self opacity");
        putTranslation("key.gomod123.blocks_finder.toggle", "Blocks finder");
        putTranslation("key.gomod123.blocks_finder.world_opacity", "Blocks finder: World opacity");
    }

    private static void putTranslation(String key, String value) {
        if (key == null || value == null) {
            return;
        }
        LOCALIZED_KEYS.put(key, value);
    }

    /**
     * Forge/weave often does not load {@code assets/gomod123/lang/en_US.lang} into the client locale before
     * Controls opens — register strings explicitly so keys show human names, not {@code key.gomod123.*}.
     */
    private static void loadTranslationsFromClasspath() {
        if (translationsLoaded) {
            return;
        }
        translationsLoaded = true;
        InputStream in = openLangStream();
        if (in == null) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                putTranslation(key, value);
            }
        } catch (Throwable ignored) {
        }
    }

    private static InputStream openLangStream() {
        String[] paths = {
                "/assets/gomod123/lang/en_US.lang",
                "assets/gomod123/lang/en_US.lang"
        };
        Class<?>[] anchors = { GomodKeybinds.class, Main.class };
        for (Class<?> anchor : anchors) {
            for (String path : paths) {
                try {
                    InputStream in = anchor.getResourceAsStream(path);
                    if (in != null) {
                        return in;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        try {
            ClassLoader cl = GomodKeybinds.class.getClassLoader();
            if (cl != null) {
                InputStream in = cl.getResourceAsStream("assets/gomod123/lang/en_US.lang");
                if (in != null) {
                    return in;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean injectIntoClientLocaleBatch(Map<String, String> entries) {
        try {
            Field localeField = net.minecraft.client.resources.I18n.class.getDeclaredField("field_135054_a");
            localeField.setAccessible(true);
            Object locale = localeField.get(null);
            if (locale == null) {
                return false;
            }
            Field propsField = locale.getClass().getDeclaredField("field_135032_a");
            propsField.setAccessible(true);
            Object propsObj = propsField.get(locale);
            if (!(propsObj instanceof Map)) {
                return false;
            }
            @SuppressWarnings("unchecked")
            Map<String, String> props = (Map<String, String>) propsObj;
            props.putAll(entries);
            return true;
        } catch (Throwable ignored) {
        }
        try {
            Field localeField = net.minecraft.client.resources.I18n.class.getDeclaredField("i18nLocale");
            localeField.setAccessible(true);
            Object locale = localeField.get(null);
            if (locale == null) {
                return false;
            }
            Field propsField = locale.getClass().getDeclaredField("properties");
            propsField.setAccessible(true);
            Object propsObj = propsField.get(locale);
            if (!(propsObj instanceof Map)) {
                return false;
            }
            @SuppressWarnings("unchecked")
            Map<String, String> props = (Map<String, String>) propsObj;
            props.putAll(entries);
            return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void injectIntoClientLocale(String key, String value) {
        Map<String, String> single = new HashMap<String, String>(1);
        single.put(key, value);
        injectIntoClientLocaleBatch(single);
        // Forge's LanguageRegistry is optional. The locale map above is the authoritative
        // path and also works on native Weave/Lunar.
        try {
            Class<?> mapClass = Class.forName("net.minecraft.util.text.translation.LanguageMap");
            Object instance = mapClass.getMethod("func_74808_a").invoke(null);
            Map<String, String> singleLegacy = new HashMap<String, String>(1);
            singleLegacy.put(key, value);
            mapClass.getMethod("func_135063_a", Map.class).invoke(instance, singleLegacy);
        } catch (Throwable ignored) {
        }
    }
}
