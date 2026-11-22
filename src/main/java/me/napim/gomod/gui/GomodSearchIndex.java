package me.napim.gomod.gui;

import me.napim.gomod.GomodInternalFeatures;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Searchable index of gomod123 menus and settings (labels match in-game UI text).
 */
public final class GomodSearchIndex {

    private static final List<GomodSearchEntry> ALL = new ArrayList<>();

    static {
        registerMenuEntries();
        registerHudEditorModules();
        registerInventoryHud();
        registerPerspective();
        registerTabQol();
        registerSidebarTweaks();
        registerTeammateTracker();
        registerMobHealth();
        registerRequeue();
        registerMiscellaneous();
        registerBlitzExtendedResults();
        registerSpectatorSettings();
        registerNametagSettings();
        registerFixesSettings();
        registerCageSettings();
        registerLootCall();
        registerSubSettingsPages();
        registerAdditionalSettings();
        registerHorseTracker();
    }

    // ── gomod Menu ─────────────────────────────────────────────────────────────

    private static void registerMenuEntries() {
        add("Inventory HUD", "gomod Menu", "inv hud inventory", g -> new InventoryHUDGUI(), -1, null);
        add("Perspective Settings", "gomod Menu", "perspective zoom fov", g -> new PerspectiveGUI(), -1, null);
        add("gostats123 AI", "gomod Menu", "gostats ai stats", g -> new GoStatsGUI(), -1, null);
        add("API Keys", "gomod Menu",
                GomodInternalFeatures.isEnabled() ? "hypixel discord token" : "hypixel token",
                g -> new ApiKeyGUI(g), -1, null);
        add("Map Queuer", "gomod Menu", "map queue", g -> new MapQueuerGUI(g), -1, null);
        add("Blitz Challenges", "gomod Menu", "blitz challenge tier badge", g -> new BlitzChallengesGUI(g), -1, null);
        add("Player Queuer", "gomod Menu", "player queue", g -> new PlayerQueuerGUI(g), -1, null);
        add("Teams Nick Finder", "gomod Menu", "team spam nick finder", g -> new TeamSpamGUI(g), -1, null);
        add("Disguise", "gomod Menu", "cape capes minecraft custom share", g ->
                GomodInternalFeatures.isEnabled()
                        ? new DisguiseGUI(g)
                        : new MinecraftCapeSelectorGUI(g, MinecraftCapeSelectorGUI.Mode.SELF),
                -1, null);
        add("Requeue Settings", "gomod Menu", "auto queue requeue", g -> new AutoQueueGUI(), -1, null);
        add("TAB QoL", "gomod Menu", "tab stats kill counter tabstats", g -> new KillCounterSettingsGUI(), -1, null);
        add("Sidebar tweaks", "gomod Menu", "scoreboard sidebar", g -> new SidebarTweaksGUI(g), -1, null);
        add("Teammate Tracker", "gomod Menu", "teammate target", g -> new TeammateTrackerGUI(g), -1, null);
        add("Leaderboard", "gomod Menu", "gostats blitz overall kills ranked players database",
                g -> new LeaderboardGUI(g), -1, null);
        add("Miscellaneous", "gomod Menu", "misc", g -> new MiscellaneousGUI(g), -1, null);
        add("HUD Editor", "gomod Menu", "edit hud", g -> new GomodHudEditorGUI(g), -1, null);
    }

    // ── HUD Editor ─────────────────────────────────────────────────────────────

    private static void registerHudEditorModules() {
        add("FPS HUD", "HUD Editor", "fps frames per second more hud", g -> hudPanel(g, MoreHudSettingsGUI.Panel.FPS),
                -1, null);
        addSub("FPS HUD", "FPS HUD", "toggle enabled more hud", g -> hudPanel(g, MoreHudSettingsGUI.Panel.FPS),
                1, MoreHudSettingsGUI.class);
        addSub("Shadow", "FPS HUD", "fps shadow", g -> hudPanel(g, MoreHudSettingsGUI.Panel.FPS),
                2, MoreHudSettingsGUI.class);
        addSub("Label preset", "FPS HUD", "fps label text preset", g -> hudPanel(g, MoreHudSettingsGUI.Panel.FPS),
                4, MoreHudSettingsGUI.class);
        addSub("Custom label", "FPS HUD", "fps custom label text", g -> hudPanel(g, MoreHudSettingsGUI.Panel.FPS),
                15, MoreHudSettingsGUI.class);
        addSub("Color settings", "FPS HUD", "fps colors", g -> hudPanel(g, MoreHudSettingsGUI.Panel.FPS),
                14, MoreHudSettingsGUI.class);

        add("CPS row", "HUD Editor", "cps clicks per second more hud", g -> hudPanel(g, MoreHudSettingsGUI.Panel.CPS),
                -1, null);
        addSub("CPS row", "CPS row", "toggle enabled more hud", g -> hudPanel(g, MoreHudSettingsGUI.Panel.CPS),
                1, MoreHudSettingsGUI.class);
        addSub("CPS source", "CPS row", "lmb rmb cps source", g -> hudPanel(g, MoreHudSettingsGUI.Panel.CPS),
                4, MoreHudSettingsGUI.class);
        addSub("Brackets", "CPS row", "cps brackets", g -> hudPanel(g, MoreHudSettingsGUI.Panel.CPS),
                5, MoreHudSettingsGUI.class);
        addSub("Shadow", "CPS row", "cps shadow", g -> hudPanel(g, MoreHudSettingsGUI.Panel.CPS),
                2, MoreHudSettingsGUI.class);
        addSub("Color settings", "CPS row", "cps colors", g -> hudPanel(g, MoreHudSettingsGUI.Panel.CPS),
                14, MoreHudSettingsGUI.class);

        add("Ping row", "HUD Editor", "ping latency ms more hud", g -> hudPanel(g, MoreHudSettingsGUI.Panel.PING),
                -1, null);
        addSub("Ping row", "Ping row", "toggle enabled more hud", g -> hudPanel(g, MoreHudSettingsGUI.Panel.PING),
                1, MoreHudSettingsGUI.class);
        addSub("Shadow", "Ping row", "ping shadow", g -> hudPanel(g, MoreHudSettingsGUI.Panel.PING),
                2, MoreHudSettingsGUI.class);
        addSub("Label preset", "Ping row", "ping label text preset", g -> hudPanel(g, MoreHudSettingsGUI.Panel.PING),
                4, MoreHudSettingsGUI.class);
        addSub("Custom label", "Ping row", "ping custom label text", g -> hudPanel(g, MoreHudSettingsGUI.Panel.PING),
                17, MoreHudSettingsGUI.class);
        addSub("Color settings", "Ping row", "ping colors", g -> hudPanel(g, MoreHudSettingsGUI.Panel.PING),
                14, MoreHudSettingsGUI.class);

        add("Effects HUD", "HUD Editor", "potions potion effects timers more hud", g -> hudPanel(g, MoreHudSettingsGUI.Panel.POTIONS),
                -1, null);
        addSub("Effects HUD", "Effects HUD", "toggle enabled more hud", g -> hudPanel(g, MoreHudSettingsGUI.Panel.POTIONS),
                1, MoreHudSettingsGUI.class);
        addSub("Shadow", "Effects HUD", "effects shadow potions", g -> hudPanel(g, MoreHudSettingsGUI.Panel.POTIONS),
                2, MoreHudSettingsGUI.class);
        addSub("Effect texts", "Effects HUD", "effect text colors per-effect uniform", g -> hudPanel(g, MoreHudSettingsGUI.Panel.POTIONS),
                4, MoreHudSettingsGUI.class);
        addSub("Show Flaming Arrows KE", "Effects HUD", "flaming arrows ke fire", g -> hudPanel(g, MoreHudSettingsGUI.Panel.POTIONS),
                92, MoreHudSettingsGUI.class);
        addSub("Effects timer", "Effects HUD", "timer detailed vanilla decimals", g -> hudPanel(g, MoreHudSettingsGUI.Panel.POTIONS),
                93, MoreHudSettingsGUI.class);
        addSub("Color settings", "Effects HUD", "effects colors potion", g -> hudPanel(g, MoreHudSettingsGUI.Panel.POTIONS),
                14, MoreHudSettingsGUI.class);

        add("Arrows count", "HUD Editor", "arrows arrow count more hud", g -> hudPanel(g, MoreHudSettingsGUI.Panel.ARROWS),
                -1, null);
        addSub("Arrows count", "Arrows count", "toggle enabled more hud", g -> hudPanel(g, MoreHudSettingsGUI.Panel.ARROWS),
                1, MoreHudSettingsGUI.class);
        addSub("Shadow", "Arrows count", "arrows shadow", g -> hudPanel(g, MoreHudSettingsGUI.Panel.ARROWS),
                2, MoreHudSettingsGUI.class);
        addSub("Color settings", "Arrows count", "arrows colors", g -> hudPanel(g, MoreHudSettingsGUI.Panel.ARROWS),
                14, MoreHudSettingsGUI.class);

        add("Armor status", "HUD Editor", "armor status durability gear held item more hud", g -> hudPanel(g, MoreHudSettingsGUI.Panel.ARMOR_STATUS),
                -1, null);
        addSub("Armor status HUD", "Armor status", "toggle enabled more hud", g -> hudPanel(g, MoreHudSettingsGUI.Panel.ARMOR_STATUS),
                1, MoreHudSettingsGUI.class);
        addSub("Shadow", "Armor status", "armor shadow", g -> hudPanel(g, MoreHudSettingsGUI.Panel.ARMOR_STATUS),
                2, MoreHudSettingsGUI.class);
        addSub("Layout", "Armor status", "vertical horizontal armor layout", g -> hudPanel(g, MoreHudSettingsGUI.Panel.ARMOR_STATUS),
                36, MoreHudSettingsGUI.class);
        addSub("Durability", "Armor status", "armor durability mode percentage compared regular", g -> hudPanel(g, MoreHudSettingsGUI.Panel.ARMOR_STATUS),
                3, MoreHudSettingsGUI.class);
        addSub("Durability bar", "Armor status", "durability bar", g -> hudPanel(g, MoreHudSettingsGUI.Panel.ARMOR_STATUS),
                4, MoreHudSettingsGUI.class);
        addSub("Durability text", "Armor status", "left right below above durability text", g -> hudPanel(g, MoreHudSettingsGUI.Panel.ARMOR_STATUS),
                5, MoreHudSettingsGUI.class);
        addSub("Color settings", "Armor status", "armor colors", g -> hudPanel(g, MoreHudSettingsGUI.Panel.ARMOR_STATUS),
                14, MoreHudSettingsGUI.class);
        addSub("Helmet", "Armor status", "helmet slot armor", g -> hudPanel(g, MoreHudSettingsGUI.Panel.ARMOR_STATUS),
                30, MoreHudSettingsGUI.class);
        addSub("Chestplate", "Armor status", "chest chestplate slot armor", g -> hudPanel(g, MoreHudSettingsGUI.Panel.ARMOR_STATUS),
                31, MoreHudSettingsGUI.class);
        addSub("Leggings", "Armor status", "leggings legs slot armor", g -> hudPanel(g, MoreHudSettingsGUI.Panel.ARMOR_STATUS),
                32, MoreHudSettingsGUI.class);
        addSub("Boots", "Armor status", "boots slot armor", g -> hudPanel(g, MoreHudSettingsGUI.Panel.ARMOR_STATUS),
                33, MoreHudSettingsGUI.class);
        addSub("Held item", "Armor status", "held item sword", g -> hudPanel(g, MoreHudSettingsGUI.Panel.ARMOR_STATUS),
                34, MoreHudSettingsGUI.class);
        addSub("Armor spacing", "Armor status", "armor spacing gap", g -> hudPanel(g, MoreHudSettingsGUI.Panel.ARMOR_STATUS),
                35, MoreHudSettingsGUI.class);
        addSub("Text scale", "Armor status", "armor text size scale", g -> hudPanel(g, MoreHudSettingsGUI.Panel.ARMOR_STATUS),
                37, MoreHudSettingsGUI.class);

        add("Coords HUD", "HUD Editor", "coords coordinates xyz biome compass more hud", g -> hudPanel(g, MoreHudSettingsGUI.Panel.COORDS),
                -1, null);
        addSub("Coords HUD", "Coords HUD", "toggle enabled more hud", g -> hudPanel(g, MoreHudSettingsGUI.Panel.COORDS),
                1, MoreHudSettingsGUI.class);
        addSub("Shadow", "Coords HUD", "coords shadow", g -> hudPanel(g, MoreHudSettingsGUI.Panel.COORDS),
                2, MoreHudSettingsGUI.class);
        addSub("Layout", "Coords HUD", "coords layout compass vertical", g -> hudPanel(g, MoreHudSettingsGUI.Panel.COORDS),
                3, MoreHudSettingsGUI.class);
        addSub("Prefix style", "Coords HUD", "coords prefix style colon parentheses arrow", g -> hudPanel(g, MoreHudSettingsGUI.Panel.COORDS),
                6, MoreHudSettingsGUI.class);
        addSub("Decimals", "Coords HUD", "coords decimals precision", g -> hudPanel(g, MoreHudSettingsGUI.Panel.COORDS),
                7, MoreHudSettingsGUI.class);
        addSub("Direction", "Coords HUD", "coords direction compass", g -> hudPanel(g, MoreHudSettingsGUI.Panel.COORDS),
                5, MoreHudSettingsGUI.class);
        addSub("Show during F3", "Coords HUD", "coords f3 debug screen", g -> hudPanel(g, MoreHudSettingsGUI.Panel.COORDS),
                8, MoreHudSettingsGUI.class);
        addSub("Biome name", "Coords HUD", "coords biome", g -> hudPanel(g, MoreHudSettingsGUI.Panel.COORDS),
                4, MoreHudSettingsGUI.class);
        addSub("Color settings", "Coords HUD", "coords colors", g -> hudPanel(g, MoreHudSettingsGUI.Panel.COORDS),
                14, MoreHudSettingsGUI.class);

        add("Kill effects", "HUD Editor", "kill effects ke more hud", g -> hudPanel(g, MoreHudSettingsGUI.Panel.KILL_EFFECTS),
                -1, null);
        addSub("Kill effects HUD", "Kill effects", "toggle enabled more hud", g -> hudPanel(g, MoreHudSettingsGUI.Panel.KILL_EFFECTS),
                1, MoreHudSettingsGUI.class);
        addSub("Shadow", "Kill effects", "kill effects shadow", g -> hudPanel(g, MoreHudSettingsGUI.Panel.KILL_EFFECTS),
                2, MoreHudSettingsGUI.class);

        add("Flaming arrows", "HUD Editor", "flaming arrows flame fire timer more hud", g -> hudPanel(g, MoreHudSettingsGUI.Panel.FLAME),
                -1, null);
        addSub("Flaming arrows HUD", "Flaming arrows", "toggle enabled more hud", g -> hudPanel(g, MoreHudSettingsGUI.Panel.FLAME),
                1, MoreHudSettingsGUI.class);
        addSub("Display", "Flaming arrows", "effects hud separate hud display", g -> hudPanel(g, MoreHudSettingsGUI.Panel.FLAME),
                4, MoreHudSettingsGUI.class);
        addSub("Timer", "Flaming arrows", "bar seconds timer", g -> hudPanel(g, MoreHudSettingsGUI.Panel.FLAME),
                29, MoreHudSettingsGUI.class);
        addSub("Shadow", "Flaming arrows", "flame shadow", g -> hudPanel(g, MoreHudSettingsGUI.Panel.FLAME),
                2, MoreHudSettingsGUI.class);

        add("Blitz kit timer", "HUD Editor", "blitz kit timer cooldown hud", g -> hudPanel(g, MoreHudSettingsGUI.Panel.BLITZ_KIT_TIMER),
                -1, null);
        addSub("Blitz kit timer HUD", "Blitz kit timer", "toggle enabled", g -> hudPanel(g, MoreHudSettingsGUI.Panel.BLITZ_KIT_TIMER),
                1, MoreHudSettingsGUI.class);
        addSub("Shadow", "Blitz kit timer", "kit timer shadow", g -> hudPanel(g, MoreHudSettingsGUI.Panel.BLITZ_KIT_TIMER),
                91, MoreHudSettingsGUI.class);

        add("Blitz Challenges HUD", "HUD Editor", "blitz challenge tier progress overlay", g -> new BlitzChallengesHUDGUI(g),
                -1, null);
        addSub("Blitz Challenges HUD", "Blitz Challenges HUD", "toggle enabled overlay", g -> new BlitzChallengesHUDGUI(g),
                0, BlitzChallengesHUDGUI.class);
        addSub("Text shadow", "Blitz Challenges HUD", "challenge hud shadow", g -> new BlitzChallengesHUDGUI(g),
                7, BlitzChallengesHUDGUI.class);

        add("Friend screenshots", "HUD Editor", "incoming screenshot overlay screenshots friends", g -> hudPanel(g, MoreHudSettingsGUI.Panel.INCOMING_SCREENSHOT),
                -1, null);
        addSub("Friend screenshots", "Friend screenshots", "toggle enabled overlay", g -> hudPanel(g, MoreHudSettingsGUI.Panel.INCOMING_SCREENSHOT),
                1, MoreHudSettingsGUI.class);
        addSub("Text shadow", "Friend screenshots", "screenshot text shadow", g -> hudPanel(g, MoreHudSettingsGUI.Panel.INCOMING_SCREENSHOT),
                3, MoreHudSettingsGUI.class);

        add("Friend Inventory HUD", "HUD Editor", "friend inventory hud shareinv friends", g -> friendInventoryHud(g),
                -1, null);
    }

    // ── Inventory HUD ──────────────────────────────────────────────────────────

    private static void registerInventoryHud() {
        addSub("Inventory HUD", "Inventory HUD", "toggle enabled", g -> new InventoryHUDGUI(),
                0, InventoryHUDGUI.class);
        addSub("Show when inventory is empty", "Inventory HUD", "empty inv", g -> new InventoryHUDGUI(),
                1, InventoryHUDGUI.class);
        addSub("Show while a GUI is open", "Inventory HUD", "force gui open", g -> new InventoryHUDGUI(),
                2, InventoryHUDGUI.class);
    }

    // ── Perspective ────────────────────────────────────────────────────────────

    private static void registerPerspective() {
        addSub("Advanced perspective", "Perspective Settings", "toggle mod", g -> new PerspectiveGUI(),
                0, PerspectiveGUI.class);
        addSub("Reset zoom when quitting adv. perspective", "Perspective Settings", "reset on exit quit",
                g -> new PerspectiveGUI(), 2, PerspectiveGUI.class);
        addSub("Zoom only while holding the zoom key", "Perspective Settings", "require hold",
                g -> new PerspectiveGUI(), 4, PerspectiveGUI.class);
        addSub("Smooth zoom transitions", "Perspective Settings", "smooth zoom", g -> new PerspectiveGUI(),
                5, PerspectiveGUI.class);
        addSub("Lock hotbar scroll while in adv. perspective", "Perspective Settings", "lock scroll hotbar",
                g -> new PerspectiveGUI(), 7, PerspectiveGUI.class);
        addSub("Custom Perspective FOV", "Perspective Settings", "fov field of view custom fov",
                g -> new PerspectiveGUI(), 8, PerspectiveGUI.class);
        addSub("Perspective FOV amount", "Perspective Settings", "fov field of view degrees slider",
                g -> new PerspectiveGUI(), 100, PerspectiveGUI.class);
        addSub("Scrolling speed", "Perspective Settings", "scroll speed perspective", g -> new PerspectiveGUI(),
                101, PerspectiveGUI.class);
        addSub("Zoom key", "Perspective Settings", "zoom keybind hold", g -> new PerspectiveGUI(),
                6, PerspectiveGUI.class);
        addSub("Reset perspective key", "Perspective Settings", "reset key keybind perspective", g -> new PerspectiveGUI(),
                3, PerspectiveGUI.class);
        addSub("Smooth zoom speed", "Perspective Settings", "smooth speed transition", g -> new PerspectiveGUI(),
                102, PerspectiveGUI.class);
        addSub("Advanced render distance", "Perspective Settings", "render distance key keybind scroll chunks blocks",
                g -> new PerspectiveGUI(), 9, PerspectiveGUI.class);
    }

    // ── TAB QoL ─────────────────────────────────────────────────────────────────

    private static void registerTabQol() {
        addSub("Kill counter", "TAB QoL", "tab stats counter", g -> new KillCounterSettingsGUI(),
                0, KillCounterSettingsGUI.class);
        addSub("TAB list sort (SOLO only)", "TAB QoL", "tab sort solo list", g -> new KillCounterSettingsGUI(),
                9, KillCounterSettingsGUI.class);
        addSub("Sort by DIST (TEAMS)", "TAB QoL", "district teams sort dist", g -> new KillCounterSettingsGUI(),
                11, KillCounterSettingsGUI.class);
        addSub("Stats display", "TAB QoL", "kd wl wins stats", g -> new KillCounterSettingsGUI(),
                30, KillCounterSettingsGUI.class);
        addSub("Kill effect display", "TAB QoL", "kill effect colors colored name tab tags nametag ke", g -> new KillCounterSettingsGUI(),
                2, KillCounterSettingsGUI.class);
        addSub("Show blitz star user", "TAB QoL", "blitz star tab icon", g -> new KillCounterSettingsGUI(),
                12, KillCounterSettingsGUI.class);
        addSub("Players stats scan", "TAB QoL", "tabstats all auto scan cages", g -> new KillCounterSettingsGUI(),
                4, KillCounterSettingsGUI.class);
        addSub("Show Guild Tag", "TAB QoL", "guild tag", g -> new KillCounterSettingsGUI(),
                5, KillCounterSettingsGUI.class);
        addSub("Better player format", "TAB QoL", "tab nametag tags rank prefix lifetime kills chat format", g -> new KillCounterSettingsGUI(),
                33, KillCounterSettingsGUI.class);
        addSub("Show teammate's kit", "TAB QoL", "teammate kit", g -> new KillCounterSettingsGUI(),
                6, KillCounterSettingsGUI.class);
        addSub("Show enemy players' kit", "TAB QoL", "enemy kit", g -> new KillCounterSettingsGUI(),
                7, KillCounterSettingsGUI.class);
        addSub("Show players' victims", "TAB QoL", "tab killers tabkillers killer chain victims", g -> new KillCounterSettingsGUI(),
                13, KillCounterSettingsGUI.class);
        addSub("TAB killers keybind", "TAB QoL", "tabkillers key tab killers victims", g -> new KillCounterSettingsGUI(),
                14, KillCounterSettingsGUI.class);
    }

    // ── Sidebar tweaks ─────────────────────────────────────────────────────────

    private static void registerSidebarTweaks() {
        addSub("Sidebar tweaks", "Sidebar tweaks", "scoreboard master toggle", g -> new SidebarTweaksGUI(g),
                1, SidebarTweaksGUI.class);
        addSub("Hide date & game ID", "Sidebar tweaks", "date game id scoreboard", g -> new SidebarTweaksGUI(g),
                2, SidebarTweaksGUI.class);
        addSub("Show BLITZ HOUR", "Sidebar tweaks", "blitz hour line", g -> new SidebarTweaksGUI(g),
                3, SidebarTweaksGUI.class);
        addSub("Show Kit", "Sidebar tweaks", "kit scoreboard line", g -> new SidebarTweaksGUI(g),
                4, SidebarTweaksGUI.class);
        addSub("Show Kill Effect", "Sidebar tweaks", "kill effect line ke sidebar", g -> new SidebarTweaksGUI(g),
                5, SidebarTweaksGUI.class);
        addSub("Taunt details", "Sidebar tweaks", "taunt wasted used sidebar", g -> new SidebarTweaksGUI(g),
                6, SidebarTweaksGUI.class);
        addSub("Hide www.hypixel.net", "Sidebar tweaks", "hypixel footer", g -> new SidebarTweaksGUI(g),
                7, SidebarTweaksGUI.class);
    }

    // ── Teammate Tracker ───────────────────────────────────────────────────────

    private static void registerTeammateTracker() {
        addSub("Teammate Tracker HUD", "Teammate Tracker", "toggle enabled", g -> new TeammateTrackerGUI(g),
                1, TeammateTrackerGUI.class);
        addSub("Text shadow", "Teammate Tracker", "shadow", g -> new TeammateTrackerGUI(g),
                10, TeammateTrackerGUI.class);
        addSub("Show vertical distance", "Teammate Tracker", "vertical distance", g -> new TeammateTrackerGUI(g),
                3, TeammateTrackerGUI.class);
        addSub("Show taunt info", "Teammate Tracker", "taunt", g -> new TeammateTrackerGUI(g),
                6, TeammateTrackerGUI.class);
        addSub("Skin glow", "Teammate Tracker", "glow teammate", g -> new TeammateTrackerGUI(g),
                7, TeammateTrackerGUI.class);
        addSub("Travel trail", "Teammate Tracker", "trail", g -> new TeammateTrackerGUI(g),
                8, TeammateTrackerGUI.class);
        addSub("Team invite notifier", "Teammate Tracker", "team request sound pling", g -> new TeammateTrackerGUI(g),
                11, TeammateTrackerGUI.class);
    }

    // ── Mob Health ─────────────────────────────────────────────────────────────

    private static void registerMobHealth() {
        addSub("Toggle Mob Health Display", "Mob Health Display", "mob health enabled", g -> new MobHealthDisplayGUI(),
                0, MobHealthDisplayGUI.class);
        addSub("Show effects", "Mob Health Display", "effects potion", g -> new MobHealthDisplayGUI(),
                4, MobHealthDisplayGUI.class);
        addSub("Text shadow", "Mob Health Display", "shadow", g -> new MobHealthDisplayGUI(),
                5, MobHealthDisplayGUI.class);
        addSub("Display mode", "Mob Health Display", "nametag panel hud nametag world", g -> new MobHealthDisplayGUI(),
                6, MobHealthDisplayGUI.class);
        addSub("Mob nametags", "Mob Health Display", "mob nametags animals name tag", g -> new MobHealthDisplayGUI(),
                11, MobHealthDisplayGUI.class);
        addSub("Configure mob filters", "Mob Health Display", "filter animals chicken ocelot rabbit",
                g -> new MobHealthDisplayGUI(), 10, MobHealthDisplayGUI.class);
    }

    // ── Requeue ────────────────────────────────────────────────────────────────

    private static void registerRequeue() {
        addSub("Win Solo Delay", "Requeue Settings", "win solo requeue", g -> new AutoQueueGUI(),
                1, AutoQueueGUI.class);
        addSub("Win Teams Delay", "Requeue Settings", "win teams requeue", g -> new AutoQueueGUI(),
                2, AutoQueueGUI.class);
        addSub("Death Solo Delay", "Requeue Settings", "death solo requeue", g -> new AutoQueueGUI(),
                4, AutoQueueGUI.class);
        addSub("Death Teams Delay", "Requeue Settings", "death teams requeue", g -> new AutoQueueGUI(),
                5, AutoQueueGUI.class);
        addSub("Win Solo requeue", "Requeue Settings", "enable toggle win solo", g -> new AutoQueueGUI(),
                10, AutoQueueGUI.class);
        addSub("Win Teams requeue", "Requeue Settings", "enable toggle win teams", g -> new AutoQueueGUI(),
                11, AutoQueueGUI.class);
        addSub("Death Solo requeue", "Requeue Settings", "enable toggle death solo", g -> new AutoQueueGUI(),
                12, AutoQueueGUI.class);
        addSub("Death Teams requeue", "Requeue Settings", "enable toggle death teams", g -> new AutoQueueGUI(),
                13, AutoQueueGUI.class);
    }

    // ── Miscellaneous ──────────────────────────────────────────────────────────

    private static void registerMiscellaneous() {
        addSub("Better BLITZ texts", "Miscellaneous", "star text blitz stars", g -> new MiscellaneousGUI(g),
                7, MiscellaneousGUI.class);
        addSub("Held item info", "Miscellaneous", "held item enchant potion duration hotbar", g -> new MiscellaneousGUI(g),
                31, MiscellaneousGUI.class);
        addSub("Lobby Ping", "Miscellaneous", "ping lobby", g -> new MiscellaneousGUI(g),
                8, MiscellaneousGUI.class);
        addSub("Self opacity", "Miscellaneous", "self opacity invisible", g -> new MiscellaneousGUI(g),
                25, MiscellaneousGUI.class);
        addSub("Water clarity", "Miscellaneous", "water clear", g -> new MiscellaneousGUI(g),
                19, MiscellaneousGUI.class);
        addSub("Thunder Settings", "Miscellaneous", "thunder lightning", g -> new MiscellaneousGUI(g),
                29, MiscellaneousGUI.class);
        addSub("Placement previews", "Miscellaneous", "tnt chicken pig horse donkey placement preview ghost hypixel", g -> new MiscellaneousGUI(g),
                30, MiscellaneousGUI.class);
        addSub("Horse tracker", "Miscellaneous", "horse donkey distance", g -> horse(g),
                15, MiscellaneousGUI.class);
        addSub("Mount XP overlay", "Miscellaneous", "mount xp bar level horse donkey pig riding",
                g -> new MiscellaneousGUI(g), 36, MiscellaneousGUI.class);
        addSub("Transparent mounts", "Miscellaneous", "mount head dim", g -> new MiscellaneousGUI(g),
                11, MiscellaneousGUI.class);
        addSub("Chestroutes", "Miscellaneous", "chest routes maps", g -> new MiscellaneousGUI(g),
                12, MiscellaneousGUI.class);
        addSub("Wheel modules", "Miscellaneous", "radial menu wheel", g -> new MiscellaneousGUI(g),
                20, MiscellaneousGUI.class);
        addSub("GUI backdrop & blur", "Miscellaneous", "backdrop blur gui", g -> new MiscellaneousGUI(g),
                18, MiscellaneousGUI.class);
        addSub("Barrier visibility", "Miscellaneous", "barrier", g -> new MiscellaneousGUI(g),
                27, MiscellaneousGUI.class);
        if (GomodInternalFeatures.isEnabled()) {
            addSub("DC", "Miscellaneous", "discord mirror dc chat", g -> new MiscellaneousGUI(g),
                    26, MiscellaneousGUI.class);
        }
        addSub("Blitz Vault Items", "Miscellaneous", "blitz vault items chest enchants survival games",
                g -> new MiscellaneousGUI(g), 32, MiscellaneousGUI.class);
        addSub("Blitz Chest Items", "Miscellaneous", "blitz chest items loot wheat apple ingot survival games",
                g -> new MiscellaneousGUI(g), 33, MiscellaneousGUI.class);
        addSub("Denicker Tools", "Miscellaneous", "denick tools nick tab duplicate real ign nametag renamed item search nick",
                g -> new MiscellaneousGUI(g), 34, MiscellaneousGUI.class);
        addSub("Block overlay", "Miscellaneous", "block overlay crosshair highlight selection",
                g -> new MiscellaneousGUI(g), 35, MiscellaneousGUI.class);
        addSub("Fixes", "Miscellaneous", "hypixel tab dupe dead ghost nametag chest idle gui entity render",
                g -> new MiscellaneousGUI(g), 38, MiscellaneousGUI.class);
        addSub("Chat Settings", "Miscellaneous", "chat move movement typing keybind controls drag",
                g -> new MiscellaneousGUI(g), 40, MiscellaneousGUI.class);
        if (GomodInternalFeatures.isEnabled()) {
            addSub("Recent Opponents", "Miscellaneous", "blitz solo teams players wins losses win rate",
                    g -> new MiscellaneousGUI(g), 43, MiscellaneousGUI.class);
        }
        addSub("Mob Health Display", "Miscellaneous", "mob health",
                g -> new MiscellaneousGUI(g), 44, MiscellaneousGUI.class);
        addSub("Pregame visibility item", "Miscellaneous",
                "blitz pregame preview item player visibility hide show players dye lobby",
                g -> new MiscellaneousGUI(g), 41, MiscellaneousGUI.class);
        addSub("Deathmatch timer", "Miscellaneous",
                "deathmatch timer boss bar ender dragon game end countdown 30s blitz",
                g -> new MiscellaneousGUI(g), 45, MiscellaneousGUI.class);
        addSub("Mob Sounds", "Miscellaneous",
                "witch sounds silent mute mob steps footsteps ocelot spawned blitz",
                g -> new MiscellaneousGUI(g), 10, MiscellaneousGUI.class);
        if (me.napim.gomod.features.GomodFixes.isWeaveRuntime()) {
            addSub("Lunar FullAccess", "Miscellaneous",
                    "lunar weave cosmetics emotes cape disguise relay gomod peers multiple pets same type",
                    g -> new MiscellaneousGUI(g), 42, MiscellaneousGUI.class);
        }

        add("Blitz Vault Items browser", "Miscellaneous", "blitz vault items browser enchants",
                g -> new BlitzVaultItemsGUI(misc(g)), -1, null);
        add("Enchant level odds", "Miscellaneous", "blitz vault enchant level unbreaking protection sharpness",
                g -> new BlitzVaultEnchantLevelGUI(new BlitzVaultItemsGUI(misc(g))), -1, null);
        add("Blitz Chest Items browser", "Miscellaneous", "blitz chest items browser loot food materials",
                g -> new BlitzChestItemsGUI(misc(g)), -1, null);
        add("Denicker Tools", "Miscellaneous", "denick tools nick tag rank parentheses mojang hypixel api renamed item search nick",
                g -> new DenickerSettingsGUI(misc(g)), -1, null);
        add("Block overlay settings", "Miscellaneous", "block overlay crosshair highlight color side full barrier cages player overlay dashed",
                g -> new GomodBlockOverlaySettingsGUI(misc(g)), -1, null);
        addSub("Player overlay", "Block overlay settings", "player outline dashed crosshair hit reach skin",
                g -> new GomodBlockOverlaySettingsGUI(misc(g)), 14, GomodBlockOverlaySettingsGUI.class);
        addSub("Denicker enabled", "Denicker Tools", "denick toggle",
                g -> new DenickerSettingsGUI(misc(g)), 1, DenickerSettingsGUI.class);
        addSub("Nick nametag display", "Denicker Tools", "parentheses rank mvp tag",
                g -> new DenickerSettingsGUI(misc(g)), 2, DenickerSettingsGUI.class);
        add("Loot Call", "Miscellaneous", "loot call diamond iron gold", g -> new LootCallSettingsGUI(misc(g)), -1, null);
        add("Ask for Loot", "Miscellaneous", "ask loot", g -> new AskForLootSettingsGUI(misc(g)), -1, null);
        add("Player caller", "Miscellaneous", "caller", g -> new PlayerCallerSettingsGUI(misc(g)), -1, null);
        add("Mob Sounds", "Miscellaneous",
                "witch sounds silent mute mob steps footsteps ocelot spawned blitz",
                g -> new WitchSoundsSettingsGUI(misc(g)), -1, null);
        add("Transparent mounts settings", "Miscellaneous", "mount head", g -> new TransparentMountsGUI(misc(g)), -1, null);
        add("Chestroutes settings", "Miscellaneous", "chest routes", g -> new ChestRoutesMapsGUI(misc(g)), -1, null);
        add("Inventory QoL", "Miscellaneous", "best armor useless item qol", g -> new InventoryQolSettingsGUI(misc(g)), -1, null);
        add("Spectator Settings", "Miscellaneous", "visible specs spectators killer nametag opacity",
                g -> new VisibleSpectatorsGUI(misc(g)), -1, null);
        add("Nametag Settings", "Miscellaneous", "nametags mob loot chest horse",
                g -> new NametagsSettingsGUI(misc(g)), -1, null);
        add("Fixes", "Miscellaneous", "hypixel tab dupe dead ghost nametag chest idle gui entity render fix",
                g -> new FixesSettingsGUI(misc(g)), -1, null);
        add("Cage features", "Miscellaneous", "cage", g -> new CageSettingsGUI(misc(g)), -1, null);
        add("gomod Service", "Miscellaneous", "service", g -> new GomodServiceGUI(misc(g)), -1, null);
        add("gomod Menu Settings", "Miscellaneous", "menu settings profiles", g -> new GomodMenuSettingsGUI(misc(g)), -1, null);
        add("Blocks Finder", "Miscellaneous", "blocks finder", g -> new BlocksFinderGUI(misc(g)), -1, null);
        add("Thunder Settings", "Miscellaneous", "thunder bolt color speed volume", g -> new LightningSettingsGUI(misc(g)), -1, null);
        add("Radial Wheel Modules", "Miscellaneous", "radial wheel slots", g -> new RadialMenuWheelSlotsGUI(misc(g)), -1, null);
        if (GomodInternalFeatures.isEnabled()) {
            add("DC Mirror settings", "Miscellaneous", "discord mirror",
                    g -> new DcMirrorSettingsGUI(misc(g)), -1, null);
        }
        add("Barrier visibility settings", "Miscellaneous", "barrier outline", g -> new BarrierVisibilitySettingsGUI(misc(g)), -1, null);
        add("GUI backdrop settings", "Miscellaneous", "backdrop blur", g -> new GuiBackdropSettingsGUI(misc(g)), -1, null);
        add("Water clarity settings", "Miscellaneous", "", g -> new WaterClaritySettingsGUI(misc(g)), -1, null);
        add("Mount XP overlay settings", "Miscellaneous", "mount xp bar level horse donkey pig riding",
                g -> new MountXpOverlaySettingsGUI(misc(g)), -1, null);
        add("Self opacity settings", "Miscellaneous", "", g -> new SelfOpacityGUI(misc(g)), -1, null);
        add("Detailed end-game score", "Miscellaneous",
                "blitz extended results end game summary positions star deathmatch messages duration map player count",
                g -> new BlitzEndGameScoreGUI(misc(g)), -1, null);
        add("Deathmatch Timer", "Miscellaneous",
                "deathmatch timer boss bar ender dragon game end countdown 30s blitz",
                g -> new DeathmatchTimerSettingsGUI(misc(g)), -1, null);
        add("Main Menu Queue", "Miscellaneous",
                "main menu queue solo teams buttons title screen quick join hypixel blitz",
                GomodSearchIndex::misc, 46, MiscellaneousGUI.class);

        // Inventory QoL sub-settings
        addSub("BestArmor overlay", "Inventory QoL", "best armor display", g -> new InventoryQolSettingsGUI(misc(g)),
                1, InventoryQolSettingsGUI.class);
        addSub("BestArmor percentage", "Inventory QoL", "armor percent average detailed", g -> new InventoryQolSettingsGUI(misc(g)),
                5, InventoryQolSettingsGUI.class);
        addSub("Useless item overlay", "Inventory QoL", "useless items", g -> new InventoryQolSettingsGUI(misc(g)),
                2, InventoryQolSettingsGUI.class);
        addSub("Chest pick hints", "Inventory QoL", "chest pick", g -> new InventoryQolSettingsGUI(misc(g)),
                3, InventoryQolSettingsGUI.class);
        addSub("Useless item options", "Inventory QoL", "useless advanced bowl cake", g -> new InventoryQolSettingsGUI(misc(g)),
                4, InventoryQolSettingsGUI.class);
    }

    // ── Spectator Settings ─────────────────────────────────────────────────────

    private static void registerBlitzExtendedResults() {
        addSub("Detailed end-game score", "Detailed end-game score", "blitz extended results enabled toggle",
                g -> new BlitzEndGameScoreGUI(misc(g)), 0, BlitzEndGameScoreGUI.class);
        addSub("Map & Player count", "Detailed end-game score", "blitz end game map players",
                g -> new BlitzEndGameScoreGUI(misc(g)), 6, BlitzEndGameScoreGUI.class);
        addSub("Game duration", "Detailed end-game score", "blitz end game time length",
                g -> new BlitzEndGameScoreGUI(misc(g)), 5, BlitzEndGameScoreGUI.class);
        addSub("More positions", "Detailed end-game score", "blitz placement ranking",
                g -> new BlitzEndGameScoreGUI(misc(g)), 1, BlitzEndGameScoreGUI.class);
        addSub("Blitz Star infos", "Detailed end-game score", "blitz star information",
                g -> new BlitzEndGameScoreGUI(misc(g)), 2, BlitzEndGameScoreGUI.class);
        addSub("Deathmatch players", "Detailed end-game score", "blitz dm players",
                g -> new BlitzEndGameScoreGUI(misc(g)), 3, BlitzEndGameScoreGUI.class);
        addSub("Messages count", "Detailed end-game score", "blitz chat messages",
                g -> new BlitzEndGameScoreGUI(misc(g)), 4, BlitzEndGameScoreGUI.class);
    }

    private static void registerSpectatorSettings() {
        addSub("Spectator nametags", "Spectator Settings", "spectator nametag visible specs ign only",
                g -> new VisibleSpectatorsGUI(misc(g)), 3, VisibleSpectatorsGUI.class);
        addSub("Killer nametag", "Spectator Settings", "killer spectator nametag overlay",
                g -> new VisibleSpectatorsGUI(misc(g)), 4, VisibleSpectatorsGUI.class);
        addSub("Spectator opacity", "Spectator Settings", "spectator transparency alpha visible specs",
                g -> new VisibleSpectatorsGUI(misc(g)), 2, VisibleSpectatorsGUI.class);
        addSub("Apply spectator settings to Replays", "Spectator Settings", "replay spectator settings opacity nametag",
                g -> new VisibleSpectatorsGUI(misc(g)), 5, VisibleSpectatorsGUI.class);
        addSub("See spectators while alive", "Spectator Settings", "spectator relay share dead ghost visible blitz",
                g -> new VisibleSpectatorsGUI(misc(g)), 6, VisibleSpectatorsGUI.class);
        addSub("Force game chats", "Spectator Settings", "blitz alive spectator chat relay messages",
                g -> new VisibleSpectatorsGUI(misc(g)), 7, VisibleSpectatorsGUI.class);
    }

    // ── Nametag Settings ───────────────────────────────────────────────────────

    private static void registerNametagSettings() {
        addSub("Teammate LootESP", "Nametag Settings", "team loot esp item label",
                g -> new NametagsSettingsGUI(misc(g)), 1, NametagsSettingsGUI.class);
        addSub("Mob nametags", "Nametag Settings", "mob nametag animals",
                g -> new NametagsSettingsGUI(misc(g)), 2, NametagsSettingsGUI.class);
        addSub("Friendly mob color", "Nametag Settings", "green own spawned mobs nametag color",
                g -> new NametagsSettingsGUI(misc(g)), 5, NametagsSettingsGUI.class);
        addSub("Mob target display", "Nametag Settings", "mob target",
                g -> new NametagsSettingsGUI(misc(g)), 3, NametagsSettingsGUI.class);
        addSub("Horse food nametag", "Nametag Settings", "horse donkey food",
                g -> new NametagsSettingsGUI(misc(g)), 4, NametagsSettingsGUI.class);
    }

    // ── Fixes ──────────────────────────────────────────────────────────────────

    private static void registerFixesSettings() {
        addSub("Hide TAB duplicate names", "Fixes", "hypixel tab dupe nick duplicate profile hide",
                g -> new FixesSettingsGUI(misc(g)), 1, FixesSettingsGUI.class);
        addSub("Force-clean dead players", "Fixes", "hypixel tab dead quit ghost stale row remove",
                g -> new FixesSettingsGUI(misc(g)), 2, FixesSettingsGUI.class);
        addSub("Nametags through chests", "Fixes", "nametag chest blocks through tile entity",
                g -> new FixesSettingsGUI(misc(g)), 3, FixesSettingsGUI.class);
        addSub("Fix thunder render", "Fixes", "thunder lightning bolt render players visible flash",
                g -> new FixesSettingsGUI(misc(g)), 4, FixesSettingsGUI.class);
        addSub("Unbothered taunt", "Fixes", "blitz sneak compass left click taunt player",
                g -> new FixesSettingsGUI(misc(g)), 8, FixesSettingsGUI.class);
        if (me.napim.gomod.features.GomodFixes.isWeaveRuntime()) {
            addSub("Force player health tags in replay", "Fixes", "lunar weave replay health nametag fix",
                    g -> new FixesSettingsGUI(misc(g)), 5, FixesSettingsGUI.class);
        }
        addSub("Blitz #rekt hitbox", "Fixes", "blitz rekt hologram armor stand hitbox rod hit obstruction",
                g -> new FixesSettingsGUI(misc(g)), 9, FixesSettingsGUI.class);
    }

    // ── Cage features ──────────────────────────────────────────────────────────

    private static void registerCageSettings() {
        addSub("Cage tracers", "Cage features", "tracer cage ptracers",
                g -> new CageSettingsGUI(misc(g)), 1, CageSettingsGUI.class);
        addSub("Tracer target nametag", "Cage features", "target nametag tracer cage",
                g -> new CageSettingsGUI(misc(g)), 3, CageSettingsGUI.class);
        addSub("Chest overlays", "Cage features", "cage chest overlay opened after cages deathmatch blitz star spawning",
                g -> new CageSettingsGUI(misc(g)), 2, CageSettingsGUI.class);
        addSub("Cages opening sound", "Cage features", "sound cage open",
                g -> new CageSettingsGUI(misc(g)), 4, CageSettingsGUI.class);
        addSub("BLITZ HOUR cage SFX", "Cage features", "blitz hour sound cage",
                g -> new CageSettingsGUI(misc(g)), 5, CageSettingsGUI.class);
        addSub("Barrier blocks in cages", "Cage features", "barrier cage blocks shown hidden",
                g -> new CageSettingsGUI(misc(g)), 6, CageSettingsGUI.class);
        addSub("Game teleporter sound", "Cage features", "teleporter compass teleport sound",
                g -> new CageSettingsGUI(misc(g)), 8, CageSettingsGUI.class);
        if (GomodInternalFeatures.isEnabled()) {
            addSub("Middle loot estimates", "Cage features", "cage middle chest loot estimate nametag",
                    g -> new CageSettingsGUI(misc(g)), 7, CageSettingsGUI.class);
        }
    }

    // ── Loot Call ──────────────────────────────────────────────────────────────

    private static void registerLootCall() {
        addSub("Loot Call enabled", "Loot Call", "loot call toggle master",
                g -> new LootCallSettingsGUI(misc(g)), 1, LootCallSettingsGUI.class);
        addSub("Loot Call diamond", "Loot Call", "diamond ingot announce",
                g -> new LootCallSettingsGUI(misc(g)), 3, LootCallSettingsGUI.class);
        addSub("Loot Call iron", "Loot Call", "iron ingot announce",
                g -> new LootCallSettingsGUI(misc(g)), 4, LootCallSettingsGUI.class);
        addSub("Loot Call gold", "Loot Call", "gold ingot announce",
                g -> new LootCallSettingsGUI(misc(g)), 5, LootCallSettingsGUI.class);
    }

    // ── Sub-settings pages (miscellaneous children) ────────────────────────────

    private static void registerSubSettingsPages() {
        addSub("Ask for Loot enabled", "Ask for Loot", "ask loot toggle",
                g -> new AskForLootSettingsGUI(misc(g)), 1, AskForLootSettingsGUI.class);
        addSub("Ask for Loot key", "Ask for Loot", "ask loot keybind",
                g -> new AskForLootSettingsGUI(misc(g)), 2, AskForLootSettingsGUI.class);

        addSub("Player caller enabled", "Player caller", "caller toggle",
                g -> new PlayerCallerSettingsGUI(misc(g)), 1, PlayerCallerSettingsGUI.class);
        addSub("Player caller key", "Player caller", "caller keybind",
                g -> new PlayerCallerSettingsGUI(misc(g)), 2, PlayerCallerSettingsGUI.class);

        addSub("Movement chat key", "Chat Settings", "chat move typing keybind preserve movement",
                g -> new ChatMovementSettingsGUI(misc(g)), 2, ChatMovementSettingsGUI.class);
        addSub("Right-click drag", "Chat Settings", "chat move mouse right click camera rotate",
                g -> new ChatMovementSettingsGUI(misc(g)), 3, ChatMovementSettingsGUI.class);
        addSub("Chat controls modifier", "Chat Settings", "chat move passthrough control wasd sneak perspective tab",
                g -> new ChatMovementSettingsGUI(misc(g)), 4, ChatMovementSettingsGUI.class);
        addSub("Force game chats", "Chat Settings", "blitz alive spectator chat relay messages",
                g -> new ChatMovementSettingsGUI(misc(g)), 5, ChatMovementSettingsGUI.class);

        addSub("Witch sounds enabled", "Mob Sounds", "witch sfx toggle",
                g -> new WitchSoundsSettingsGUI(misc(g)), 1, WitchSoundsSettingsGUI.class);

        addSub("Silent mob steps enabled", "Mob Sounds", "silent mute mob footsteps toggle ocelot witch blitz",
                g -> new WitchSoundsSettingsGUI(misc(g)), 201, WitchSoundsSettingsGUI.class);
        addSub("Mute Ocelot steps", "Mob Sounds", "silent mute ocelot cat footsteps spawned blitz",
                g -> new WitchSoundsSettingsGUI(misc(g)), 202, WitchSoundsSettingsGUI.class);
        addSub("Mute Witch steps", "Mob Sounds", "silent mute witch footsteps spawned blitz",
                g -> new WitchSoundsSettingsGUI(misc(g)), 203, WitchSoundsSettingsGUI.class);

        addSub("Transparent mounts enabled", "Transparent mounts settings", "mount head dim opacity",
                g -> new TransparentMountsGUI(misc(g)), 1, TransparentMountsGUI.class);

        addSub("Self opacity enabled", "Self opacity settings", "self invisible translucent",
                g -> new SelfOpacityGUI(misc(g)), 1, SelfOpacityGUI.class);
        addSub("Disable self opacity during blindness", "Self opacity settings", "blindness nametag skin",
                g -> new SelfOpacityGUI(misc(g)), 3, SelfOpacityGUI.class);

        addSub("Water clarity enabled", "Water clarity settings", "water clear transparent",
                g -> new WaterClaritySettingsGUI(misc(g)), 1, WaterClaritySettingsGUI.class);

        addSub("Shorten thunder", "Thunder Settings", "thunder nerf shorten",
                g -> new LightningSettingsGUI(misc(g)), 1, LightningSettingsGUI.class);
        addSub("Fix thunder renders", "Thunder Settings", "thunder render players visible through lightning",
                g -> new LightningSettingsGUI(misc(g)), 12, LightningSettingsGUI.class);
        addSub("Thunder color", "Thunder Settings", "thunder color tint",
                g -> new LightningSettingsGUI(misc(g)), 6, LightningSettingsGUI.class);
        addSub("Test thunder", "Thunder Settings", "thunder sound speed volume test",
                g -> new LightningSettingsGUI(misc(g)), 8, LightningSettingsGUI.class);
        addSub("Early game thunder", "Thunder Settings", "cages open post cage thunder speed volume",
                g -> new EarlyGameThunderSettingsGUI(new LightningSettingsGUI(misc(g))), 1, EarlyGameThunderSettingsGUI.class);
        addSub("After cages open", "Early game thunder", "duration seconds post cage",
                g -> new EarlyGameThunderSettingsGUI(new LightningSettingsGUI(misc(g))), 10, EarlyGameThunderSettingsGUI.class);
        addSub("Test early thunder", "Early game thunder", "test thunder sound early game",
                g -> new EarlyGameThunderSettingsGUI(new LightningSettingsGUI(misc(g))), 2, EarlyGameThunderSettingsGUI.class);

        addSub("GUI backdrop mode", "GUI backdrop settings", "backdrop blur gui background",
                g -> new GuiBackdropSettingsGUI(misc(g)), 1, GuiBackdropSettingsGUI.class);

        addSub("Barrier visibility enabled", "Barrier visibility settings", "barrier blocks outline show",
                g -> new BarrierVisibilitySettingsGUI(misc(g)), 1, BarrierVisibilitySettingsGUI.class);
        addSub("Barrier visibility keybind", "Barrier visibility settings", "barrier key keybind",
                g -> new BarrierVisibilitySettingsGUI(misc(g)), 2, BarrierVisibilitySettingsGUI.class);

        addSub("Deathmatch timer enabled", "Deathmatch Timer", "deathmatch boss bar ender dragon toggle",
                g -> new DeathmatchTimerSettingsGUI(misc(g)), 1, DeathmatchTimerSettingsGUI.class);
        addSub("Deathmatch timer threshold", "Deathmatch Timer", "show at seconds 30s countdown",
                g -> new DeathmatchTimerSettingsGUI(misc(g)), 101, DeathmatchTimerSettingsGUI.class);

        addSub("Blocks Finder enabled", "Blocks Finder", "block esp finder toggle",
                g -> new BlocksFinderGUI(misc(g)), 1, BlocksFinderGUI.class);
        addSub("Blocks Finder manage blocks", "Blocks Finder", "block list manage",
                g -> new BlocksFinderGUI(misc(g)), 2, BlocksFinderGUI.class);
        addSub("Blocks Finder mode", "Blocks Finder", "esp mode outline fill",
                g -> new BlocksFinderGUI(misc(g)), 3, BlocksFinderGUI.class);
        addSub("Blocks Finder keybind", "Blocks Finder", "blocks finder key",
                g -> new BlocksFinderGUI(misc(g)), 12, BlocksFinderGUI.class);

        addSub("Radial wheel enabled", "Radial Wheel Modules", "radial menu wheel toggle",
                g -> new RadialMenuWheelSlotsGUI(misc(g)), 31, RadialMenuWheelSlotsGUI.class);
        addSub("Radial wheel keybind", "Radial Wheel Modules", "radial menu wheel key",
                g -> new RadialMenuWheelSlotsGUI(misc(g)), 32, RadialMenuWheelSlotsGUI.class);

        addSub("Config profiles", "gomod Menu Settings", "profiles backup config",
                g -> new GomodMenuSettingsGUI(misc(g)), 10, GomodMenuSettingsGUI.class);
        addSub("Move while in gomod menus", "gomod Menu Settings", "move menu gomod",
                g -> new GomodMenuSettingsGUI(misc(g)), 1, GomodMenuSettingsGUI.class);
        addSub("Move while in MC menus", "gomod Menu Settings", "move minecraft mc menu",
                g -> new GomodMenuSettingsGUI(misc(g)), 4, GomodMenuSettingsGUI.class);
        if (me.napim.gomod.features.GomodFixes.isWeaveRuntime()) {
            addSub("Move while in Lunar menus", "gomod Menu Settings", "move lunar menu typing search",
                    g -> new GomodMenuSettingsGUI(misc(g)), 5, GomodMenuSettingsGUI.class);
        }
        addSub("Drag to look", "gomod Menu Settings", "drag look menu gomod minecraft mc",
                g -> new GomodMenuSettingsGUI(misc(g)), 2, GomodMenuSettingsGUI.class);
        addSub("GUI scale", "gomod Menu Settings", "gomod menus scale size interface",
                g -> new GomodMenuSettingsGUI(misc(g)), 3, GomodMenuSettingsGUI.class);

        if (GomodInternalFeatures.isEnabled()) {
            addSub("DC Mirror enabled", "DC Mirror settings", "discord mirror chat toggle",
                    g -> new DcMirrorSettingsGUI(misc(g)), 1, DcMirrorSettingsGUI.class);
        }
    }

    /**
     * Controls on secondary settings pages that were easy to miss in the original hand-maintained index.
     * Keep IDs aligned with the owning screen so selecting a result opens the page and pulses that control.
     */
    private static void registerAdditionalSettings() {
        if (me.napim.gomod.features.GomodFixes.isWeaveRuntime()) {
            addSub("Lunar FullAccess enabled", "Lunar FullAccess", "lunar cosmetics relay toggle",
                    g -> new LunarTweaksGUI(misc(g)), 1, LunarTweaksGUI.class);
            addSub("FullAccess sharing", "Lunar FullAccess", "audience privacy who sees my lunar cosmetics",
                    g -> new LunarTweaksGUI(misc(g)), 2, LunarTweaksGUI.class);
            addSub("See other Gomod players' Lunar cosmetics", "Lunar FullAccess", "peer friend lunar cosmetic display",
                    g -> new LunarTweaksGUI(misc(g)), 3, LunarTweaksGUI.class);
            addSub("Allow multiple cosmetics of the same type", "Lunar FullAccess", "duplicate pets emotes sprays",
                    g -> new LunarTweaksGUI(misc(g)), 4, LunarTweaksGUI.class);
            addSub("Manage hidden cosmetic players", "Lunar FullAccess", "hide ignore blacklist player cosmetics visibility",
                    g -> new LunarTweaksGUI(misc(g)), 5, LunarTweaksGUI.class);
            addSub("Unlock limited or expired cosmetics", "Lunar FullAccess", "limited expired unlock cosmetics",
                    g -> new LunarTweaksGUI(misc(g)), 6, LunarTweaksGUI.class);
        }

        addSub("gostats123 AI enabled", "gostats123 AI", "toggle stats AI",
                g -> new GoStatsGUI(), 0, GoStatsGUI.class);

        add("Lunar Logo Color", "Miscellaneous", "lunar plus logo color picker rgb hex custom color",
                g -> new LunarLogoColorGUI(misc(g)), -1, null);
        addSub("Choose Lunar Logo Color", "Lunar Logo Color", "picker hue saturation brightness rgb hex",
                g -> new LunarLogoColorGUI(misc(g)), -1, null);
        addSub("Apply Lunar Logo Color", "Lunar Logo Color", "save set custom logo color",
                g -> new LunarLogoColorGUI(misc(g)), 1, LunarLogoColorGUI.class);
        addSub("Use API default logo color", "Lunar Logo Color", "reset default lunar logo",
                g -> new LunarLogoColorGUI(misc(g)), 2, LunarLogoColorGUI.class);
        if (me.napim.gomod.features.GomodFixes.isWeaveRuntime()) {
            add("Manage hidden Lunar cosmetics", "Lunar FullAccess", "ignore blacklist hide other players cosmetics",
                    g -> new LunarCosmeticIgnoreGUI(new LunarTweaksGUI(misc(g))), -1, null);
        }

        addSub("Player Queuer enabled", "Player Queuer", "queue targets toggle auto queue",
                g -> new PlayerQueuerGUI(misc(g)), 0, PlayerQueuerGUI.class);
        addSub("Player Queuer start delay", "Player Queuer", "queue pre-start delay seconds slider",
                g -> new PlayerQueuerGUI(misc(g)), 14, PlayerQueuerGUI.class);
        addSub("Queue match mode", "Player Queuer", "include queues any all mode",
                g -> new PlayerQueuerGUI(misc(g)), 5, PlayerQueuerGUI.class);
        addSub("Dodge match mode", "Player Queuer", "exclude dodge any all mode",
                g -> new PlayerQueuerGUI(misc(g)), 10, PlayerQueuerGUI.class);
        addSub("Queue rules", "Player Queuer", "include nick wlr threshold",
                g -> new PlayerQueuerGUI(misc(g)), 13, PlayerQueuerGUI.class);
        addSub("Dodge rules", "Player Queuer", "exclude nick wlr threshold",
                g -> new PlayerQueuerGUI(misc(g)), 15, PlayerQueuerGUI.class);
        addSub("Queue nick rule", "Queue rules", "include all nicks nicked players",
                g -> new PlayerQueuerIncludeRulesGUI(new PlayerQueuerGUI(misc(g)), false), 1, PlayerQueuerIncludeRulesGUI.class);
        addSub("Queue WLR rule and threshold", "Queue rules", "include win loss ratio value range",
                g -> new PlayerQueuerIncludeRulesGUI(new PlayerQueuerGUI(misc(g)), false), 2, PlayerQueuerIncludeRulesGUI.class);
        addSub("Queue player scan mode", "Queue rules", "include stats scan when players join",
                g -> new PlayerQueuerIncludeRulesGUI(new PlayerQueuerGUI(misc(g)), false), 3, PlayerQueuerIncludeRulesGUI.class);
        addSub("Dodge nick rule", "Dodge rules", "exclude all nicks nicked players",
                g -> new PlayerQueuerIncludeRulesGUI(new PlayerQueuerGUI(misc(g)), true), 1, PlayerQueuerIncludeRulesGUI.class);
        addSub("Dodge WLR rule and threshold", "Dodge rules", "exclude win loss ratio value range",
                g -> new PlayerQueuerIncludeRulesGUI(new PlayerQueuerGUI(misc(g)), true), 2, PlayerQueuerIncludeRulesGUI.class);
        addSub("Dodge player scan mode", "Dodge rules", "exclude stats scan when players join",
                g -> new PlayerQueuerIncludeRulesGUI(new PlayerQueuerGUI(misc(g)), true), 3, PlayerQueuerIncludeRulesGUI.class);
        addSub("Hypixel API key for Player Queuer", "Queue rules", "API key stats lookup credentials",
                g -> new PlayerQueuerIncludeRulesGUI(new PlayerQueuerGUI(misc(g)), false), 4, PlayerQueuerIncludeRulesGUI.class);

        add("Social hotkey settings", "gomod Service", "social open keybind ctrl modifier hotkey",
                g -> new SocialKeybindSettingsGUI(new GomodServiceGUI(misc(g))), -1, null);
        addSub("Social open key", "Social hotkey settings", "keybind shortcut keyboard",
                g -> new SocialKeybindSettingsGUI(new GomodServiceGUI(misc(g))), 1, SocialKeybindSettingsGUI.class);
        addSub("Social Ctrl modifier", "Social hotkey settings", "keybind modifier tab player list",
                g -> new SocialKeybindSettingsGUI(new GomodServiceGUI(misc(g))), 2, SocialKeybindSettingsGUI.class);

        add("Radial wheel slot assignments", "Radial Wheel Modules", "radial menu edit slots actions items colors",
                g -> new RadialMenuWheelSlotsGUI(misc(g)), -1, null);
        addSub("Blitz Challenges HUD position", "Blitz Challenges HUD", "challenge overlay drag position scale",
                g -> new BlitzChallengesHUDGUI(g), 4, BlitzChallengesHUDGUI.class);
        addSub("Reset Blitz Challenges HUD position", "Blitz Challenges HUD", "challenge overlay position scale reset",
                g -> new BlitzChallengesHUDGUI(g), 3, BlitzChallengesHUDGUI.class);

        addSub("Background dim for Gomod menus", "gomod Menu Settings", "dim overlay darken",
                g -> new GomodMenuSettingsGUI(misc(g)), 101, GomodMenuSettingsGUI.class);
        addSub("Background blur for Gomod menus", "gomod Menu Settings", "blur overlay",
                g -> new GomodMenuSettingsGUI(misc(g)), 102, GomodMenuSettingsGUI.class);

        addSub("Barrier visibility style", "Barrier visibility settings", "outline texture texture style",
                g -> new BarrierVisibilitySettingsGUI(misc(g)), 3, BarrierVisibilitySettingsGUI.class);
        addSub("Barrier visibility hue", "Barrier visibility settings", "color rainbow hue",
                g -> new BarrierVisibilitySettingsGUI(misc(g)), 4, BarrierVisibilitySettingsGUI.class);
        addSub("Show barrier at cursor", "Barrier visibility settings", "crosshair cursor target",
                g -> new BarrierVisibilitySettingsGUI(misc(g)), 5, BarrierVisibilitySettingsGUI.class);
        addSub("Barrier visibility range", "Barrier visibility settings", "distance blocks range",
                g -> new BarrierVisibilitySettingsGUI(misc(g)), 6, BarrierVisibilitySettingsGUI.class);
        addSub("Barrier visibility opacity", "Barrier visibility settings", "alpha transparency",
                g -> new BarrierVisibilitySettingsGUI(misc(g)), 7, BarrierVisibilitySettingsGUI.class);
        addSub("Show barriers during cages", "Barrier visibility settings", "cage pregame barriers",
                g -> new BarrierVisibilitySettingsGUI(misc(g)), 8, BarrierVisibilitySettingsGUI.class);

        addSub("Blocks Finder fill opacity", "Blocks Finder", "block esp fill alpha transparency",
                g -> new BlocksFinderGUI(misc(g)), 5, BlocksFinderGUI.class);
        addSub("Blocks Finder world opacity", "Blocks Finder", "block esp world alpha transparency",
                g -> new BlocksFinderGUI(misc(g)), 6, BlocksFinderGUI.class);

        addSub("Self opacity amount", "Self opacity settings", "alpha transparency amount",
                g -> new SelfOpacityGUI(misc(g)), 2, SelfOpacityGUI.class);
        addSub("Self opacity in first person", "Self opacity settings", "first-person camera view",
                g -> new SelfOpacityGUI(misc(g)), 4, SelfOpacityGUI.class);
        addSub("Self opacity keybind", "Self opacity settings", "key toggle bind",
                g -> new SelfOpacityGUI(misc(g)), 5, SelfOpacityGUI.class);
        addSub("Self opacity in third-person front", "Self opacity settings", "front camera f5",
                g -> new SelfOpacityGUI(misc(g)), 6, SelfOpacityGUI.class);
        addSub("Self opacity in third-person back", "Self opacity settings", "back camera f5",
                g -> new SelfOpacityGUI(misc(g)), 7, SelfOpacityGUI.class);

        addSub("Water clarity amount", "Water clarity settings", "water opacity transparency slider",
                g -> new WaterClaritySettingsGUI(misc(g)), 101, WaterClaritySettingsGUI.class);

        addSub("Horse opacity", "Transparent mounts settings", "mount transparency horse alpha",
                g -> new TransparentMountsGUI(misc(g)), 101, TransparentMountsGUI.class);
        addSub("Donkey opacity", "Transparent mounts settings", "mount transparency donkey alpha",
                g -> new TransparentMountsGUI(misc(g)), 102, TransparentMountsGUI.class);
        addSub("Pig opacity", "Transparent mounts settings", "mount transparency pig alpha",
                g -> new TransparentMountsGUI(misc(g)), 103, TransparentMountsGUI.class);
        addSub("Boat opacity", "Transparent mounts settings", "mount transparency boat alpha",
                g -> new TransparentMountsGUI(misc(g)), 104, TransparentMountsGUI.class);
        addSub("Minecart opacity", "Transparent mounts settings", "mount transparency wagon alpha",
                g -> new TransparentMountsGUI(misc(g)), 105, TransparentMountsGUI.class);

        addSub("Mount XP overlay enabled", "Mount XP overlay settings", "horse donkey pig level experience bar toggle",
                g -> new MountXpOverlaySettingsGUI(misc(g)), 1, MountXpOverlaySettingsGUI.class);
        addSub("Mount XP overlay opacity", "Mount XP overlay settings", "experience bar transparency alpha",
                g -> new MountXpOverlaySettingsGUI(misc(g)), 101, MountXpOverlaySettingsGUI.class);

        addSub("Witch idle sound volume", "Mob Sounds", "witch sfx idle",
                g -> new WitchSoundsSettingsGUI(misc(g)), 101, WitchSoundsSettingsGUI.class);
        addSub("Witch hurt sound volume", "Mob Sounds", "witch sfx hurt",
                g -> new WitchSoundsSettingsGUI(misc(g)), 102, WitchSoundsSettingsGUI.class);
        addSub("Witch death sound volume", "Mob Sounds", "witch sfx death",
                g -> new WitchSoundsSettingsGUI(misc(g)), 103, WitchSoundsSettingsGUI.class);
        addSub("Witch throw sound volume", "Mob Sounds", "witch sfx potion throw",
                g -> new WitchSoundsSettingsGUI(misc(g)), 104, WitchSoundsSettingsGUI.class);
        addSub("Witch drink sound volume", "Mob Sounds", "witch sfx potion drink",
                g -> new WitchSoundsSettingsGUI(misc(g)), 105, WitchSoundsSettingsGUI.class);
        addSub("Witch celebrate sound volume", "Mob Sounds", "witch sfx victory",
                g -> new WitchSoundsSettingsGUI(misc(g)), 106, WitchSoundsSettingsGUI.class);

        addSub("Gomod menus backdrop dim", "GUI backdrop settings", "background darken opacity",
                g -> new GuiBackdropSettingsGUI(misc(g)), 101, GuiBackdropSettingsGUI.class);
        addSub("Gomod menus backdrop blur", "GUI backdrop settings", "background blur",
                g -> new GuiBackdropSettingsGUI(misc(g)), 102, GuiBackdropSettingsGUI.class);
        addSub("Other GUIs backdrop dim", "GUI backdrop settings", "minecraft background darken opacity",
                g -> new GuiBackdropSettingsGUI(misc(g)), 103, GuiBackdropSettingsGUI.class);
        addSub("Other GUIs backdrop blur", "GUI backdrop settings", "minecraft background blur",
                g -> new GuiBackdropSettingsGUI(misc(g)), 104, GuiBackdropSettingsGUI.class);

        addSub("Thunder block count", "Thunder Settings", "lightning blocks number",
                g -> new LightningSettingsGUI(misc(g)), 2, LightningSettingsGUI.class);
        addSub("Thunder speed", "Thunder Settings", "lightning bolt animation speed",
                g -> new LightningSettingsGUI(misc(g)), 4, LightningSettingsGUI.class);
        addSub("Thunder sound volume", "Thunder Settings", "lightning audio sound volume",
                g -> new LightningSettingsGUI(misc(g)), 10, LightningSettingsGUI.class);
        addSub("Thunder opacity", "Thunder Settings", "lightning render alpha transparency",
                g -> new LightningSettingsGUI(misc(g)), 13, LightningSettingsGUI.class);
        addSub("Random thunder color", "Thunder Settings", "lightning randomize color tint",
                g -> new LightningSettingsGUI(misc(g)), 14, LightningSettingsGUI.class);
        addSub("Early game thunder speed", "Early game thunder", "cage opening lightning speed",
                g -> new EarlyGameThunderSettingsGUI(new LightningSettingsGUI(misc(g))), 12, EarlyGameThunderSettingsGUI.class);
        addSub("Early game thunder volume", "Early game thunder", "cage opening lightning sound volume",
                g -> new EarlyGameThunderSettingsGUI(new LightningSettingsGUI(misc(g))), 13, EarlyGameThunderSettingsGUI.class);
        addSub("Reset early game thunder settings", "Early game thunder", "default reset",
                g -> new EarlyGameThunderSettingsGUI(new LightningSettingsGUI(misc(g))), 3, EarlyGameThunderSettingsGUI.class);

        add("Held item info settings", "Miscellaneous", "enchant potion hotbar item name text",
                g -> new HeldItemInfoSettingsGUI(misc(g)), -1, null);
        addSub("Held item info enabled", "Held item info settings", "toggle hotbar enchant display",
                g -> new HeldItemInfoSettingsGUI(misc(g)), 1, HeldItemInfoSettingsGUI.class);
        addSub("Held item info shadow", "Held item info settings", "enchant text shadow",
                g -> new HeldItemInfoSettingsGUI(misc(g)), 2, HeldItemInfoSettingsGUI.class);
        addSub("Potion text style", "Held item info settings", "potion duration text icon",
                g -> new HeldItemInfoSettingsGUI(misc(g)), 4, HeldItemInfoSettingsGUI.class);
        addSub("Gap above item name", "Held item info settings", "enchant info spacing gap",
                g -> new HeldItemInfoSettingsGUI(misc(g)), 5, HeldItemInfoSettingsGUI.class);
        addSub("Enchant text color", "Held item info settings", "color picker hue rgb",
                g -> new HeldItemInfoSettingsGUI(misc(g)), 200, HeldItemInfoSettingsGUI.class);
        addSub("Reset held item info position", "Held item info settings", "hud position scale reset",
                g -> new HeldItemInfoSettingsGUI(misc(g)), 101, HeldItemInfoSettingsGUI.class);

        addSub("Bowl overlay rule", "Useless item options", "bowl useless item inventory",
                g -> new UselessItemSettingsGUI(new InventoryQolSettingsGUI(misc(g))), 1, UselessItemSettingsGUI.class);
        addSub("Cake overlay rule", "Useless item options", "cake useless item inventory",
                g -> new UselessItemSettingsGUI(new InventoryQolSettingsGUI(misc(g))), 2, UselessItemSettingsGUI.class);
        addSub("KB 1 Rose overlay rule", "Useless item options", "knockback rose useless inventory",
                g -> new UselessItemSettingsGUI(new InventoryQolSettingsGUI(misc(g))), 3, UselessItemSettingsGUI.class);
        addSub("Storage minecart overlay rule", "Useless item options", "chest minecart useless inventory",
                g -> new UselessItemSettingsGUI(new InventoryQolSettingsGUI(misc(g))), 4, UselessItemSettingsGUI.class);
        addSub("Minecart overlay rule", "Useless item options", "minecart useless inventory",
                g -> new UselessItemSettingsGUI(new InventoryQolSettingsGUI(misc(g))), 5, UselessItemSettingsGUI.class);
        addSub("Rails overlay rule", "Useless item options", "rail useless inventory",
                g -> new UselessItemSettingsGUI(new InventoryQolSettingsGUI(misc(g))), 6, UselessItemSettingsGUI.class);
        addSub("Iron no-sharp rule", "Useless item options", "sword iron sharpness no sharpness compare",
                g -> new UselessItemSettingsGUI(new InventoryQolSettingsGUI(misc(g))), 7, UselessItemSettingsGUI.class);
        addSub("Stone Sharp I rule", "Useless item options", "sword stone sharpness compare",
                g -> new UselessItemSettingsGUI(new InventoryQolSettingsGUI(misc(g))), 8, UselessItemSettingsGUI.class);
        addSub("Diamond no-sharp rule", "Useless item options", "sword diamond sharpness compare",
                g -> new UselessItemSettingsGUI(new InventoryQolSettingsGUI(misc(g))), 9, UselessItemSettingsGUI.class);
        addSub("Iron Sharp I rule", "Useless item options", "sword iron sharpness compare",
                g -> new UselessItemSettingsGUI(new InventoryQolSettingsGUI(misc(g))), 10, UselessItemSettingsGUI.class);
        addSub("Stone no-sharp rule", "Useless item options", "sword stone sharpness compare",
                g -> new UselessItemSettingsGUI(new InventoryQolSettingsGUI(misc(g))), 11, UselessItemSettingsGUI.class);
        addSub("Gold/Wood Sharp I rule", "Useless item options", "sword gold wooden sharpness compare",
                g -> new UselessItemSettingsGUI(new InventoryQolSettingsGUI(misc(g))), 12, UselessItemSettingsGUI.class);
        addSub("Wheat overlay rule", "Useless item options", "wheat useless item inventory",
                g -> new UselessItemSettingsGUI(new InventoryQolSettingsGUI(misc(g))), 13, UselessItemSettingsGUI.class);
        addSub("Sharpness I fish rule", "Useless item options", "fish sword sharpness useless inventory",
                g -> new UselessItemSettingsGUI(new InventoryQolSettingsGUI(misc(g))), 14, UselessItemSettingsGUI.class);

        addSub("Apples overlay rule", "Useless item options", "apple apples chest useless item inventory",
                g -> new UselessItemSettingsGUI(new InventoryQolSettingsGUI(misc(g))), 15, UselessItemSettingsGUI.class);
        addSub("Sticks overlay rule", "Useless item options", "stick sticks chest useless item inventory",
                g -> new UselessItemSettingsGUI(new InventoryQolSettingsGUI(misc(g))), 16, UselessItemSettingsGUI.class);

        add("Placement preview settings", "Miscellaneous", "ghost placement tnt chicken pig horse donkey",
                g -> new PlacementPreviewSettingsGUI(misc(g)), -1, null);
        addSub("TNT placement preview", "Placement preview settings", "ghost tnt placement",
                g -> new PlacementPreviewSettingsGUI(misc(g)), 1, PlacementPreviewSettingsGUI.class);
        addSub("Chicken placement preview", "Placement preview settings", "ghost chicken placement",
                g -> new PlacementPreviewSettingsGUI(misc(g)), 2, PlacementPreviewSettingsGUI.class);
        addSub("Pig placement preview", "Placement preview settings", "ghost pig placement",
                g -> new PlacementPreviewSettingsGUI(misc(g)), 3, PlacementPreviewSettingsGUI.class);
        addSub("Horse placement preview", "Placement preview settings", "ghost horse placement",
                g -> new PlacementPreviewSettingsGUI(misc(g)), 4, PlacementPreviewSettingsGUI.class);
        addSub("Donkey placement preview", "Placement preview settings", "ghost donkey placement",
                g -> new PlacementPreviewSettingsGUI(misc(g)), 5, PlacementPreviewSettingsGUI.class);

        addSub("Block overlay enabled", "Block overlay settings", "crosshair highlight toggle",
                g -> new GomodBlockOverlaySettingsGUI(misc(g)), 1, GomodBlockOverlaySettingsGUI.class);
        addSub("Block overlay mode", "Block overlay settings", "outline fill block selection",
                g -> new GomodBlockOverlaySettingsGUI(misc(g)), 2, GomodBlockOverlaySettingsGUI.class);
        addSub("Block overlay shape aware", "Block overlay settings", "block outline shape",
                g -> new GomodBlockOverlaySettingsGUI(misc(g)), 12, GomodBlockOverlaySettingsGUI.class);
        addSub("Hide barriers in cages", "Block overlay settings", "barrier pregame cage",
                g -> new GomodBlockOverlaySettingsGUI(misc(g)), 13, GomodBlockOverlaySettingsGUI.class);
        addSub("Block overlay fill", "Block overlay settings", "filled overlay transparency alpha",
                g -> new GomodBlockOverlaySettingsGUI(misc(g)), 3, GomodBlockOverlaySettingsGUI.class);
        addSub("Block overlay line placement", "Block overlay settings", "inside outside border",
                g -> new GomodBlockOverlaySettingsGUI(misc(g)), 4, GomodBlockOverlaySettingsGUI.class);
        addSub("Block overlay color and opacity", "Block overlay settings", "color picker hue rgb alpha transparency",
                g -> new GomodBlockOverlaySettingsGUI(misc(g)), -1, null);
        addSub("Block overlay line width", "Block overlay settings", "thickness width outline",
                g -> new GomodBlockOverlaySettingsGUI(misc(g)), 10, GomodBlockOverlaySettingsGUI.class);

        addSub("Friend Inventory HUD enabled", "Friend Inventory HUD", "shareinv toggle",
                g -> friendInventoryHud(g), 1, FriendInventoryHUDGUI.class);
        addSub("Force Friend Inventory HUD", "Friend Inventory HUD", "show always gui force",
                g -> friendInventoryHud(g), 2, FriendInventoryHUDGUI.class);
        addSub("Friend health", "Friend Inventory HUD", "shareinv remote health hearts",
                g -> friendInventoryHud(g), 20, FriendInventoryHUDGUI.class);
        addSub("Friend XP", "Friend Inventory HUD", "shareinv remote experience level",
                g -> friendInventoryHud(g), 22, FriendInventoryHUDGUI.class);
        addSub("Friend direction", "Friend Inventory HUD", "shareinv compass vertical distance",
                g -> friendInventoryHud(g), 26, FriendInventoryHUDGUI.class);
        addSub("Friend direction vertical", "Friend Inventory HUD", "shareinv height y distance",
                g -> friendInventoryHud(g), 27, FriendInventoryHUDGUI.class);
        addSub("Friend held slot highlight", "Friend Inventory HUD", "shareinv selected item slot border",
                g -> friendInventoryHud(g), 25, FriendInventoryHUDGUI.class);
        addSub("Friend Inventory HUD text shadow", "Friend Inventory HUD", "shareinv shadow text",
                g -> friendInventoryHud(g), 31, FriendInventoryHUDGUI.class);
        addSub("Friend Inventory HUD position", "Friend Inventory HUD", "shareinv x y position coordinates",
                g -> friendInventoryHud(g), -1, null);
        addSub("Friend Inventory HUD scale", "Friend Inventory HUD", "shareinv size zoom",
                g -> friendInventoryHud(g), -1, null);
        addSub("Friend Inventory HUD background", "Friend Inventory HUD", "shareinv opacity color transparency",
                g -> friendInventoryHud(g), -1, null);

        addSub("Denicker real skin", "Denicker Tools", "nick skin mojang profile",
                g -> new DenickerSettingsGUI(misc(g)), 4, DenickerSettingsGUI.class);
        addSub("Show real name in TAB", "Denicker Tools", "nick actual ign tab list",
                g -> new DenickerSettingsGUI(misc(g)), 5, DenickerSettingsGUI.class);
        addSub("Nick Renamed Items", "Denicker Tools", "nick renamed item hand armor sword gostats db check",
                g -> new DenickerSettingsGUI(misc(g)), 6, DenickerSettingsGUI.class);
        addSub("Death [SEARCH NICK]", "Denicker Tools", "search nick death kill count gostats leaderboard link",
                g -> new DenickerSettingsGUI(misc(g)), 7, DenickerSettingsGUI.class);

        if (GomodInternalFeatures.isEnabled()) {
            addSub("Incoming DM filter", "DC Mirror settings", "discord direct messages incoming allow block",
                    g -> new DcMirrorSettingsGUI(misc(g)), 521, DcMirrorSettingsGUI.class);
            addSub("Import Gomod friends", "DC Mirror settings", "discord recipients friend list import",
                    g -> new DcMirrorSettingsGUI(misc(g)), 525, DcMirrorSettingsGUI.class);
            addSub("Manage DM Mirror recipients", "DC Mirror settings", "discord add userid allowlist",
                    g -> new DcMirrorSettingsGUI(misc(g)), 2, DcMirrorSettingsGUI.class);
        }

        registerHudLayoutAndBackground(MoreHudSettingsGUI.Panel.FPS, "FPS HUD", "fps");
        registerHudLayoutAndBackground(MoreHudSettingsGUI.Panel.CPS, "CPS row", "cps clicks per second");
        registerHudLayoutAndBackground(MoreHudSettingsGUI.Panel.PING, "Ping row", "ping latency");
        registerHudLayoutAndBackground(MoreHudSettingsGUI.Panel.POTIONS, "Effects HUD", "effects potion timers");
        registerHudLayoutAndBackground(MoreHudSettingsGUI.Panel.ARROWS, "Arrows count", "arrows count");
        registerHudLayoutAndBackground(MoreHudSettingsGUI.Panel.ARMOR_STATUS, "Armor status", "armor durability");
        registerHudLayoutAndBackground(MoreHudSettingsGUI.Panel.KILL_EFFECTS, "Kill effects", "kill effect ke");
        registerHudLayoutAndBackground(MoreHudSettingsGUI.Panel.FLAME, "Flaming arrows", "flame fire");
        registerHudLayoutAndBackground(MoreHudSettingsGUI.Panel.BLITZ_KIT_TIMER, "Blitz kit timer", "blitz kit cooldown");
        registerHudLayoutAndBackground(MoreHudSettingsGUI.Panel.INCOMING_SCREENSHOT, "Friend screenshots", "incoming screenshot");
        registerHudLayoutAndBackground(MoreHudSettingsGUI.Panel.COORDS, "Coords HUD", "coordinates xyz");
    }

    private static void registerHudLayoutAndBackground(MoreHudSettingsGUI.Panel panel, String parentLabel,
                                                        String keywords) {
        addSub("Position and scale", parentLabel, keywords + " hud position drag scale size",
                g -> hudPanel(g, panel), -1, null);
        addSub("Reset position and scale", parentLabel, keywords + " hud reset layout",
                g -> hudPanel(g, panel), 90, MoreHudSettingsGUI.class);
        if (panel != MoreHudSettingsGUI.Panel.FLAME
                && panel != MoreHudSettingsGUI.Panel.INCOMING_SCREENSHOT) {
            addSub("Background opacity and color", parentLabel, keywords + " hud background alpha fill color rgb",
                    g -> hudPanel(g, panel), -1, null);
        }
    }

    // ── Horse tracker ──────────────────────────────────────────────────────────

    private static void registerHorseTracker() {
        addSub("Distance HUD", "Horse tracker", "horse distance hud", g -> horse(g),
                0, HorseDonkeySettingsGUI.class);
        addSub("Teleportation sound", "Horse tracker", "teleport sound horse", g -> horse(g),
                1, HorseDonkeySettingsGUI.class);
        addSub("Required food nametag", "Horse tracker", "food nametag horse", g -> horse(g),
                5, HorseDonkeySettingsGUI.class);
        addSub("Text shadow", "Horse tracker", "horse shadow", g -> horse(g),
                7, HorseDonkeySettingsGUI.class);
        addSub("Color settings", "Horse tracker", "horse colors", g -> horse(g),
                6, HorseDonkeySettingsGUI.class);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static void add(String label, String parentLabel, String keywords, GomodSearchEntry.GuiFactory factory,
                            int highlightId, Class<?> highlightGui) {
        ALL.add(new GomodSearchEntry(label, parentLabel, keywords, factory, highlightId,
                (Class<? extends net.minecraft.client.gui.GuiScreen>) highlightGui));
    }

    private static void addSub(String label, String parentLabel, String keywords, GomodSearchEntry.GuiFactory factory,
                               int highlightId, Class<? extends net.minecraft.client.gui.GuiScreen> guiClass) {
        ALL.add(new GomodSearchEntry(label, parentLabel, keywords, factory, highlightId, guiClass));
    }

    private static MiscellaneousGUI misc(GomodGUI g) {
        return new MiscellaneousGUI(g);
    }

    private static GomodHudEditorGUI hudEditor(GomodGUI g) {
        return new GomodHudEditorGUI(g);
    }

    private static MoreHudSettingsGUI hudPanel(GomodGUI g, MoreHudSettingsGUI.Panel panel) {
        return new MoreHudSettingsGUI(hudEditor(g), panel);
    }

    private static FriendInventoryHUDGUI friendInventoryHud(GomodGUI g) {
        return new FriendInventoryHUDGUI(hudEditor(g));
    }

    private static HorseDonkeySettingsGUI horse(GomodGUI g) {
        return new HorseDonkeySettingsGUI(misc(g));
    }

    public static List<GomodSearchEntry> search(String query) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String lq = query.trim().toLowerCase();

        List<GomodSearchEntry> prefix = new ArrayList<>();
        List<GomodSearchEntry> labelContains = new ArrayList<>();
        List<GomodSearchEntry> other = new ArrayList<>();

        for (GomodSearchEntry e : ALL) {
            if (GomodInternalFeatures.isRestrictedSearchEntry(e.label)) {
                continue;
            }
            String hay = e.searchableText();
            if (!hay.contains(lq)) {
                continue;
            }
            String ll = e.label.toLowerCase();
            if (ll.startsWith(lq)) {
                prefix.add(e);
            } else if (ll.contains(lq)) {
                labelContains.add(e);
            } else {
                other.add(e);
            }
        }

        List<GomodSearchEntry> results = new ArrayList<>(prefix.size() + labelContains.size() + other.size());
        results.addAll(prefix);
        results.addAll(labelContains);
        results.addAll(other);
        return results;
    }

    private GomodSearchIndex() {}
}
