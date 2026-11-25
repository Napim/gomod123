package me.napim.gomod;

import me.napim.gomod.command.*;
import me.napim.gomod.commands.GomodCommand;
import me.napim.gomod.features.*;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.ModInitializer;
import net.weavemc.loader.api.command.Command;
import net.weavemc.loader.api.command.CommandBus;
import net.weavemc.loader.api.event.ChatReceivedEvent;
import net.weavemc.loader.api.event.EventBus;
import net.weavemc.loader.api.event.SubscribeEvent;

public class Main implements ModInitializer {
    private final AutoQueue autoQueue = new AutoQueue();
    private final StarText starText = new StarText();
    private final AIChatHandler aiChatHandler = new AIChatHandler();
    private final TeamInviteResponseHandler teamInviteResponseHandler = new TeamInviteResponseHandler();
    private final TeamInviteNotifier teamInviteNotifier = new TeamInviteNotifier();
    private final KillCounter killCounter = new KillCounter();
    private final BlitzSoloExtendedResults blitzSoloExtendedResults = new BlitzSoloExtendedResults();
    private final BlitzChallenges blitzChallenges = new BlitzChallenges();
    private final PlayerQueuer playerQueuer = new PlayerQueuer();
    private final TabDuplicateNickResolver tabDuplicateNickResolver = new TabDuplicateNickResolver();
    private final DeathmatchTabCleaner deathmatchTabCleaner = new DeathmatchTabCleaner();
    private final LocalDisguiseSync localDisguiseSync = new LocalDisguiseSync();
    private final RemoteDisguiseSync remoteDisguiseSync = new RemoteDisguiseSync();
    private final PartySpamCommand partySpamCommand = new PartySpamCommand();
    private static final GlobalChatClient GLOBAL_CHAT_CLIENT = new GlobalChatClient();
    public static final String CHAT_PREFIX;
    private static boolean commandsRegistered = false;
    private static volatile boolean preInitComplete = false;
    private static volatile boolean commandRegistrationFailureDetailLogged = false;

    public static boolean isPreInitComplete() {
        return preInitComplete;
    }

    public void preInit() {
        if (preInitComplete) {
            return;
        }
        synchronized (Main.class) {
            if (preInitComplete) {
                return;
            }
        System.out.println("Initializing gomod mod!");
        try { ReflectionLogger.installGlobalHandler(); } catch (Throwable var2) { }
        ConfigManager.loadConfig();
        me.napim.gomod.features.GomodAutoUpdater.cleanupStaleCache();
        GomodSocialChat.init();
        ApiKeyManager.loadApiKeys();
        GomodKeybinds.register();
        me.napim.gomod.features.GomodSettingsReloader.reloadAllAfterConfigLoaded();
        me.napim.gomod.features.Denicker.applyRuntime();
        me.napim.gomod.util.GomodResourcePackInjector.ensureInstalled();
        me.napim.gomod.features.ChestRouteManager.init();
        System.out.println("Initial settings - AutoQueue: " + (AutoQueue.isEnabled() ? "enabled" : "disabled"));
        EventBus.subscribe(this);
        EventBus.subscribe(this.autoQueue);
        EventBus.subscribe(this.starText);
        EventBus.subscribe(this.aiChatHandler);
        EventBus.subscribe(this.teamInviteResponseHandler);
        EventBus.subscribe(this.teamInviteNotifier);
        if (GomodInternalFeatures.isEnabled()) EventBus.subscribe(new DisguiseKiller());
        EventBus.subscribe(this.killCounter);
        EventBus.subscribe(this.blitzSoloExtendedResults);
        EventBus.subscribe(this.blitzChallenges);
        if (GomodInternalFeatures.isEnabled()) EventBus.subscribe(new RecentOpponents());
        if (GomodInternalFeatures.isEnabled()) EventBus.subscribe(new me.napim.gomod.features.InventoryQolChestDebug());
        EventBus.subscribe(this.playerQueuer);
        EventBus.subscribe(this.tabDuplicateNickResolver);
        EventBus.subscribe(this.deathmatchTabCleaner);
        EventBus.subscribe(this.localDisguiseSync);
        EventBus.subscribe(this.remoteDisguiseSync);
        EventBus.subscribe(this.partySpamCommand);
        EventBus.subscribe(new UnknownNickFinder());
        EventBus.subscribe(new AskForLootOverlayProxy());
        EventBus.subscribe(new KillEffectCommand());
        EventBus.subscribe(DeathmatchOppZone.INSTANCE);
        EventBus.subscribe(new BlitzRektHitboxOverlay());
        EventBus.subscribe(new HotbarScrollBlocker());
        EventBus.subscribe(new FriendListScanner());
        EventBus.subscribe(new me.napim.gomod.features.LootCall());
        EventBus.subscribe(new me.napim.gomod.features.PlayerCaller());
        EventBus.subscribe(new me.napim.gomod.features.NoHitDelay());
        EventBus.subscribe(new me.napim.gomod.features.DebugHits());
        EventBus.subscribe(new me.napim.gomod.features.MapQueuerTickHandler());
        EventBus.subscribe(new me.napim.gomod.features.ChestRouteTickHandler());
        EventBus.subscribe(new me.napim.gomod.features.WitchHurtPacketHandler());
        EventBus.subscribe(new me.napim.gomod.features.WitchSoundPacketHandler());
        EventBus.subscribe(new me.napim.gomod.features.CageTracerChestOpenListener());
        EventBus.subscribe(new me.napim.gomod.features.ScreenshotChatPrompt());
        EventBus.subscribe(new HudRenderFallback());
        EventBus.subscribe(new HotbarEnchantTextOverlay());
        EventBus.subscribe(new me.napim.gomod.features.HudEditorCenterGuides());
        EventBus.subscribe(new BlitzKitTimerHUD());
        EventBus.subscribe(new DeathmatchTimerBossBar());
        EventBus.subscribe(new IncomingScreenshotOverlay());
        EventBus.subscribe(new IncomingFriendRequestOverlay());
        EventBus.subscribe(new IncomingFriendDmOverlay());
        EventBus.subscribe(new IncomingPublicPingOverlay());
        EventBus.subscribe(new IncomingCapeDesignOverlay());
        EventBus.subscribe(new GomodSocialMsgIconHud());
        EventBus.subscribe(new BestArmorWeaveGuiDriver());
        EventBus.subscribe(new NametagThroughChestsWorldFallback());
        EventBus.subscribe(new me.napim.gomod.features.SpectatorRelay());
        EventBus.subscribe(new me.napim.gomod.features.ArmorDisguise());
        EventBus.subscribe(new me.napim.gomod.features.UnbotheredTaunt());
        EventBus.subscribe(new me.napim.gomod.features.MobHealthWorldTagFallback());
        EventBus.subscribe(new PauseMenuButtonFallback());
        EventBus.subscribe(new PerspectiveFallbackHandler());
        EventBus.subscribe(new DeferredCommandRegistrar());
        EventBus.subscribe(new CommandPacketInterceptor());
        EventBus.subscribe(new me.napim.gomod.features.ScoreboardDateHider());
        EventBus.subscribe(new me.napim.gomod.features.SidebarBlitzHourReminder());
        EventBus.subscribe(new me.napim.gomod.features.DisguiseSidebarScoreboardMutator());
        EventBus.subscribe(new me.napim.gomod.features.SidebarKeScoreboardMutator());
        EventBus.subscribe(new me.napim.gomod.features.SidebarTauntWastedMutator());
        EventBus.subscribe(new me.napim.gomod.features.SidebarKeOverlay());
        EventBus.subscribe(new me.napim.gomod.features.GomodPerformanceWatch());
        EventBus.subscribe(new me.napim.gomod.features.GomodLagHud());
        EventBus.subscribe(new me.napim.gomod.features.RadialMenuWheel());
        EventBus.subscribe(new me.napim.gomod.features.ChestDumpLogger());
        EventBus.subscribe(GLOBAL_CHAT_CLIENT);
        EventBus.subscribe(new me.napim.gomod.shareinv.ShareInvTickHandler());
        int registeredEarly = registerCommands();
        if (registeredEarly == 0) {
            System.err.println("[gomod] Early command registration failed; will retry later.");
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { me.napim.gomod.features.ConfigManager.flushConfigForShutdown(); } catch (Exception ignored) { }
            try { me.napim.gomod.features.KitLevelsManager.saveCaches(); } catch (Exception var1) { }
        }));
            preInitComplete = true;
        }
    }

    public static int registerCommands() {
        if (commandsRegistered) {
            return 0;
        }
        int count = 0;
        count += registerCommand("gomod", new GomodCommand());
        count += registerCommand("killeffect", new KillEffectCommand());
        count += registerCommand("hypixelapidebug", new HypixelApiDebugCommand());
        count += registerCommand("lunarconnectiondebug", new LunarConnectionDebugCommand());
        count += registerCommand("partyspam", new PartySpamCommand());
        count += registerCommand("queuestatus", new QueueStatusCommand());
        count += registerCommand("playerinfo", new PlayerInfoCommand());
        count += registerCommand("nickitemdb", new NickRenamedItemCommand());
        count += registerCommand("gostatslbsearch", new GoStatsLeaderboardSearchCommand());
        count += registerCommand("mapqueuer", new MapQueuerCommand());
        count += registerCommand("teamspam", new TeamSpamCommand());
        count += registerCommand("teamspamrefresh", new TeamSpamRefreshResultsCommand());
        count += registerCommand("kitlevels", new KitLevelsCommand());
        count += registerCommand("fonline", new FOnlineCommand());
        count += registerCommand("bv", new BarrierVisibilityCommand());
        count += registerCommand("playerqueuer", new PlayerQueuerCommand());
        count += registerCommand("playertarget", new PlayerTargetCommand());
        count += registerCommand("mobtarget", new MobTargetDisplayCommand());
        count += registerCommand("selfie", new SelfieCommand());
        count += registerCommand("opacityme", new OpacityMeCommand());
        count += registerCommand("servercommands", new ServerCommandsCommand());
        if (!GomodReleaseBuild.PUBLIC_RELEASE) {
            count += registerCommand("starpredict", new StarPredictCommand());
            count += registerCommand("rektdebug", new me.napim.gomod.command.BlitzRektHitboxDebugCommand());
            count += registerCommand("rekthologramforce", new RektHologramForceCommand());
        }
        if (GomodInternalFeatures.isEnabled()) {
            count += registerCommand("visiblespecsdebug", new VisibleSpecsDebugCommand());
            count += registerCommand("capedebug", new CapeDebugCommand());
            count += registerCommand("debugall", new DebugAllCommand());
            count += registerCommand("tabaligndebug", new TabAlignDebugCommand());
            count += registerCommand("tabvictimsdebug", new TabVictimsDebugCommand());
            count += registerCommand("bsgresultsdebug", new BlitzResultsDebugCommand());
            count += registerCommand("tntplacementdebug", new me.napim.gomod.command.TntPlacementDebugCommand());
            count += registerCommand("apikeyexpireddebug", new me.napim.gomod.command.ApiKeyExpiredDebugCommand());
            count += registerCommand("forcepig", new ForcePigCommand());
            count += registerCommand("forceres", new me.napim.gomod.command.ForceResCommand());
            count += registerCommand("npcskin", new me.napim.gomod.command.NpcSkinDebugCommand());
            count += registerCommand("forceblitzhour", new me.napim.gomod.command.ForceBlitzHourCommand());
            count += registerCommand("visiblespecs", new VisibleSpectatorCommand());
            if (GomodFixes.isWeaveRuntime()) {
                count += registerCommand("tabstatslayout", new TabStatsLayoutCommand());
                count += registerCommand("lunarlogodebug", new LunarNametagDebugCommand());
                count += registerCommand("lunararmdebug", new LunarArmDebugCommand());
                count += registerCommand("barrierdebug", new BarrierVisibilityDebugCommand());
                count += registerCommand("lunarmenudebug", new LunarMenuDebugCommand());
            }
            count += registerCommand("blitzchallenges", new BlitzChallengesCommand());
            count += registerDebugCommands();
            count += registerCommand("roleplay", new RoleplayCommand());
            count += registerCommand("disguise", new DisguiseCommand());
            count += registerCommand("disguisekiller", new DisguiseKillerCommand());
            count += registerCommand("disguisegomod", new DisguiseGomodCommand());
            if (GomodFixes.isWeaveRuntime()) {
                count += registerCommand("disguisefull", new DisguiseFullCommand());
                count += registerCommand("disguisefullgomod", new DisguiseFullGomodCommand());
                count += registerCommand("disguiserfull", new DisguiserFullCommand());
                count += registerCommand("disguiserfullgomod", new DisguiserFullGomodCommand());
                count += registerCommand("server", new me.napim.gomod.command.ServerPresenceCommand());
            }
            count += registerCommand("mobdisguise", new MobDisguiseCommand());
            count += registerCommand("playersplay", new PlayersPlayCommand());
            count += registerCommand("playershow", new PlayersShowCommand());
            count += registerCommand("gogive", new GogiveCommand());
            count += InternalDcCommands.registerAll();
            count += registerCommand("disguiser", new DisguiserCommand());
            count += registerCommand("disguisergomodarmor", new ArmorDisguiseCommand(true));
            count += registerCommand("disguiserarmor", new ArmorDisguiseCommand(false));
            count += registerCommand("disguiserdebug", new DisguiserDebugCommand());
            count += registerCommand("disguisergomod", new me.napim.gomod.command.DisguiserGomodCommand());
            count += registerCommand("disguisesidebardebug", new DisguiseSidebarDebugCommand());
            count += registerCommand("disguiserenameddebug", new DisguiseRenamedItemsDebugCommand());
            count += registerCommand("kitspreview", new KitsPreviewCommand());
        }
        count += registerCommand("fakebreak", new FakeBreakCommand());
        count += registerCommand("reloadblocks", new ReloadBlocksCommand());
        count += registerCommand("bload", new BloadCommand());
        count += registerCommand("fixbestarmor", new FixBestArmorCommand());
        count += registerCommand("schizo", new SchizoCommand());
        count += registerCommand("tabreload", new TabReloadCommand());
        count += registerCommand("noshuffleteam", new NoShuffleTeamCommand());
        count += registerCommand("glowplayer", new GlowPlayerCommand());
        count += registerCommand("glowplayer2", new me.napim.gomod.command.GlowPlayer2Command());
        count += registerCommand("glowdm", new GlowDMCommand());
        count += registerCommand("glowoppdm", new GlowOppDmCommand());
        count += registerCommand("itemesp", new ItemESPCommand());
        count += registerCommand("lootcall", new LootCallCommand());
        count += registerCommand("asklootalltime", new AskLootAllTimeCommand());
        count += registerCommand("cps", new CpsCommand());
        count += registerCommand("nohitdelay", new NoHitDelayCommand());
        count += registerCommand("nohitdelayhud", new NoHitDelayHUDCommand());
        count += registerCommand("blockset", new BlockSetCommand());
        count += registerCommand("ce", new me.napim.gomod.command.ChestEditCommand());
        count += registerCommand("cro", new me.napim.gomod.command.ChestRouteCommand());
        count += registerCommand("fix", new FixCommand());
        count += registerCommand("fixwitch", new FixWitchCommand());
        count += registerCommand("witchsound", new WitchSoundCommand());
        count += registerCommand("shareinv", new ShareInvCommand());
        count += registerCommand("gssend", new me.napim.gomod.command.ScreenshotQuickSendCommand());
        count += registerCommand("gfriend", new me.napim.gomod.command.GomodFriendCommand());
        count += registerCommand("gsedit", new me.napim.gomod.command.GsEditCommand());
        count += registerCommand("gcopenimg", new me.napim.gomod.command.GcOpenIncomingScreenshotCommand());
        count += registerCommand("gomodviewcapes", new me.napim.gomod.command.GomodViewCapesCommand());
        count += registerCommand("gchat", new GlobalChatCommand(GLOBAL_CHAT_CLIENT));
        count += registerCommand("chestpickcolors", new ChestPickColorsCommand());
        count += registerCommand("tabstatssettings", new TabStatsSettingsCommand());
        count += registerCommand("tss", new TssCommand());
        count += registerCommand("lightningnerf", new me.napim.gomod.command.LightningNerfCommand());
        count += registerCommand("pee", new PeeCommand());
        count += registerCommand("ptracers", new PTracersCommand());
        count += registerCommand("ptracerscages", new me.napim.gomod.command.PTracersCagesCommand());
        count += registerCommand("cagetracerdebug", new me.napim.gomod.command.CageTracerDebugCommand());
        count += registerCommand("roulettedebug", new me.napim.gomod.command.RouletteDebugCommand());
        count += registerCommand("blitzrelaydebug", new me.napim.gomod.command.BlitzRelayDebugCommand());
        count += registerCommand("duelpred", new me.napim.gomod.command.DuelPredictorCommand());
        if (count > 0) {
            commandsRegistered = true;
        }
        return count;
    }

    private static int registerDebugCommands() {
        int count = 0;
        count += registerCommand("hudeditorlines", new me.napim.gomod.command.HudEditorLinesCommand());
        count += registerCommand("coordscompassdot", new me.napim.gomod.command.CoordsCompassDepthDebugCommand.Dot());
        count += registerCommand("coordscompassring", new me.napim.gomod.command.CoordsCompassDepthDebugCommand.Ring());
        count += registerCommand("coordscompasscard", new me.napim.gomod.command.CoordsCompassDepthDebugCommand.Cardinals());
        count += registerCommand("scoreboarddebug", new ScoreboardDebugCommand());
        count += registerCommand("scoreboardteams", new ScoreboardTeamsCommand());
        count += registerCommand("tablistdebug", new TabListDebugCommand());
        count += registerCommand("tablagdebug", new TabLagDebugCommand());
        count += registerCommand("reflog", new ReflectionLogCommand());
        count += registerCommand("debuginvis", new me.napim.gomod.command.DebugInvisProxyCommand());
        count += registerCommand("allplayers", new AllPlayersCommand());
        count += registerCommand("tabuuid", new TabUuidCommand());
        count += registerCommand("tabcolorwhy", new TabColorWhyCommand());
        count += registerCommand("tooltipdebug", new TooltipDebugProxyCommand());
        count += registerCommand("chestdumpdebug", new ChestDumpDebugCommand());
        count += registerCommand("chestpickdebug", new me.napim.gomod.command.ChestPickDebugCommand());
        count += registerCommand("blitzchestdumpdebug", new BlitzChestDumpDebugCommand());
        count += registerCommand("nohitdelaydebug", new NoHitDelayDebugCommand());
        count += registerCommand("kesidebardebug", new KeSidebarDebugCommand());
        count += registerCommand("gdebug", new GomodDebugCommand());
        count += registerCommand("debugmessages", new DebugMessagesCommand());
        count += registerCommand("debughits", new DebugHitsCommand());
        count += registerCommand("spawnedmobdebug", new SpawnedMobTrackerDebugCommand());
        count += registerCommand("tabstatsfulldebug", new TabstatsFullDebugCommand());
        count += registerCommand("tabstatsnetdebug", new TabStatsNetDebugCommand());
        count += registerCommand("guildtagdebug", new GuildTagDebugCommand());
        count += registerCommand("tabstatsresets", new TabstatsResetsCommand());
        count += registerCommand("flamekedebug", new me.napim.gomod.command.FlameKeDebugCommand());
        count += registerCommand("inventoryqoldebug", new me.napim.gomod.command.InventoryQolDebugCommand());
        count += registerCommand("uselessitemsforce", new UselessItemsForceCommand());
        count += registerCommand("gomodlag", new GomodLagCommand());
        count += registerCommand("devcavity", new DevCavityCommand());
        count += registerCommand("disguisefps", new DisguiseFpsDebugCommand());
        count += registerCommand("hudopacitybox", new me.napim.gomod.command.HudOpacityBoxDebugCommand());
        count += registerCommand("helditempreview", new me.napim.gomod.command.HeldItemInfoPreviewDebugCommand());
        count += registerCommand("morehudpotioncolor", new me.napim.gomod.command.MoreHudPotionColorCommand());
        count += registerCommand("morehudkecolor", new me.napim.gomod.command.MoreHudKillEffectColorCommand());
        count += registerCommand("nametagnick", new NametagNickCommand());
        count += registerCommand("bchdebug", new me.napim.gomod.command.BlitzChallengesDebugCommand());
        count += registerCommand("debuggoparktaunt", new me.napim.gomod.command.GoparkyTauntDebugCommand());
        count += registerCommand("debuggoparkyemote", new me.napim.gomod.command.GoparkyEmoteDebugCommand());
        count += registerCommand("debuggoparky", new me.napim.gomod.command.GoparkyStatusDebugCommand());
        count += registerCommand("goparkytaunt", new me.napim.gomod.command.GoparkyTauntCommand());
        count += registerCommand("hideplayerdebug", new HidePlayerDebugCommand());
        count += registerCommand("tagdebug", new PlayerTagDebugCommand());
        count += registerCommand("nametagrenderdebug", new NametagRenderDebugCommand());
        count += registerCommand("mobstepsdebug", new MobStepsDebugCommand());
        return count;
    }

    static int registerCommand(String name, Command command) {
        try {
            CommandBus.register(command);
            System.out.println("[gomod] Registered /" + name);
            return 1;
        } catch (Throwable t) {
            System.err.println("[gomod] Failed to register /" + name + ": " + t.getMessage());
            if (!commandRegistrationFailureDetailLogged) {
                commandRegistrationFailureDetailLogged = true;
                t.printStackTrace();
            }
            return 0;
        }
    }

    public static boolean areCommandsRegistered() {
        return commandsRegistered;
    }

    @SubscribeEvent
    public void onChatReceived(ChatReceivedEvent event) {
        PlayerTarget.onChatReceived(event);
        try {
            if (event != null && event.getMessage() != null) {
                String unformatted = event.getMessage().getUnformattedText();
                CageChestOverlay.onChatMessage(unformatted);
                me.napim.gomod.features.NickRenamedItemScanner.onChatMessage(unformatted);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void sendMessage(String message) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && !mc.isCallingFromMinecraftThread()) {
                mc.addScheduledTask(() -> sendMessage(message));
                return;
            }
            if (mc.thePlayer != null) {
                mc.thePlayer.addChatMessage(new ChatComponentText(message));
                return;
            }
            if (mc.ingameGUI != null && mc.ingameGUI.getChatGUI() != null) {
                mc.ingameGUI.getChatGUI().printChatMessage(new ChatComponentText(message));
                return;
            }
        } catch (Exception var2) {
            // fall through
        }
        System.out.println("[gomod] " + message);
    }

    /** Rich chat (clickable commands, hover) — use for ShareInv invites etc. */
    public static void sendChatMessage(IChatComponent component) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && !mc.isCallingFromMinecraftThread()) {
                mc.addScheduledTask(() -> sendChatMessage(component));
                return;
            }
            if (mc.thePlayer != null) {
                mc.thePlayer.addChatMessage(component);
                return;
            }
            if (mc.ingameGUI != null && mc.ingameGUI.getChatGUI() != null) {
                mc.ingameGUI.getChatGUI().printChatMessage(component);
                return;
            }
        } catch (Exception ignored) {}
        System.out.println("[gomod] " + component.getUnformattedText());
    }

    public static GlobalChatClient getGlobalChatClient() {
        return GLOBAL_CHAT_CLIENT;
    }

    static {
        String var10000 = EnumChatFormatting.DARK_GRAY.toString();
        CHAT_PREFIX = var10000 + EnumChatFormatting.BOLD + "[" + EnumChatFormatting.DARK_GREEN + EnumChatFormatting.BOLD + "gomod" + EnumChatFormatting.DARK_GRAY + EnumChatFormatting.BOLD + "] " + EnumChatFormatting.RESET;
    }
}
