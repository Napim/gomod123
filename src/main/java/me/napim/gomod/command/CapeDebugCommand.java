package me.napim.gomod.command;

import com.mojang.authlib.GameProfile;
import me.napim.gomod.Main;
import me.napim.gomod.GomodVersion;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.GomodFixes;
import me.napim.gomod.features.KillCounter;
import me.napim.gomod.features.LunarExternalOpacityBridge;
import me.napim.gomod.features.LunarOnlineStatus;
import me.napim.gomod.features.PlayerStandaloneCape;
import me.napim.gomod.features.SelfOpacity;
import me.napim.gomod.features.VisibleSpecs;
import me.napim.gomod.util.GameContext;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.WorldSettings;
import net.weavemc.loader.api.command.Command;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Targeted cape/opacity diagnostic for Lunar cloth cloaks, gomod capes, SelfOpacity and spectator opacity.
 */
public final class CapeDebugCommand extends Command {
    public CapeDebugCommand() {
        super("capedebug", "capeopacitydebug");
    }

    @Override
    public void handle(String[] args) {
        boolean full = args.length > 0 && "full".equalsIgnoreCase(args[0]);
        String report = buildReport();
        String saved = writeReport(report);

        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA + "capedebug captured.");
        for (String line : report.split("\\r?\\n")) {
            if (line.startsWith("summary:")
                    || line.startsWith("context:")
                    || line.startsWith("visibleSpecs:")
                    || line.startsWith("selfOpacity:")
                    || line.startsWith("lunarClothBridge:")
                    || line.startsWith("lunarFullAccess:")
                    || line.startsWith("nativeWeaveHooks:")
                    || line.startsWith("capeSource:")
                    || line.startsWith("capeDecision:")
                    || line.startsWith("capeRelay:")) {
                Main.sendMessage(EnumChatFormatting.GRAY + line);
            }
        }
        if (saved != null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Saved cape debug: "
                    + EnumChatFormatting.WHITE + saved);
        } else {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                    + "Could not write cape debug file; copy chat/log lines instead.");
        }

        System.out.println(report);
        if (full) {
            VisibleSpecs.dumpDebugSnapshotToChat(false);
        }
    }

    private static String buildReport() {
        Minecraft mc = Minecraft.getMinecraft();
        StringBuilder sb = new StringBuilder(4096);
        sb.append("gomod capedebug - ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.ROOT).format(new Date()))
                .append('\n');
        sb.append("summary: run this while the bug is visible; compare bridgeBegin/native hook counters before/after looking at the cape\n");

        if (mc == null) {
            sb.append("context: minecraft=null\n");
            return sb.toString();
        }

        EntityPlayer player = mc.thePlayer;
        Entity view = mc.getRenderViewEntity();
        sb.append("context:")
                .append(" screen=").append(mc.currentScreen == null ? "<none>" : mc.currentScreen.getClass().getName())
                .append(" thirdPersonView=").append(mc.gameSettings == null ? "<null>" : mc.gameSettings.thirdPersonView)
                .append(" world=").append(mc.theWorld == null ? "<null>" : mc.theWorld.getClass().getName())
                .append(" player=").append(player == null ? "<null>" : safeName(player))
                .append(" viewEntity=").append(view == null ? "<null>" : view.getClass().getName())
                .append(" viewIsPlayer=").append(view == player)
                .append('\n');

        try {
            net.minecraft.util.Session session = mc.getSession();
            GameProfile profile = player == null ? null : player.getGameProfile();
            sb.append("clientSession:")
                    .append(" username=").append(session == null ? "<none>" : session.getUsername())
                    .append(" uuid=").append(session == null ? "<none>" : session.getPlayerID())
                    .append(" profileName=").append(profile == null ? "<none>" : profile.getName())
                    .append(" profileUuid=").append(profile == null ? "<none>" : profile.getId())
                    .append(" sessionMatchesWorldPlayer=").append(session != null && profile != null
                            && session.getUsername() != null && profile.getName() != null
                            && session.getUsername().equalsIgnoreCase(profile.getName()))
                    .append('\n');
        } catch (Throwable t) {
            sb.append("clientSession: error=").append(t.getClass().getSimpleName()).append('\n');
        }

        if (player != null) {
            sb.append("localPlayer:")
                    .append(" uuid=").append(player.getUniqueID())
                    .append(" spectator=").append(player.isSpectator())
                    .append(" invisible=").append(player.isInvisible())
                    .append(" sneaking=").append(player.isSneaking())
                    .append(" riding=").append(player.isRiding())
                    .append(" pos=").append(fmt(player.posX)).append(',').append(fmt(player.posY)).append(',').append(fmt(player.posZ))
                    .append('\n');
            sb.append("localTab:")
                    .append(tabSnapshot(mc, player))
                    .append('\n');
            if (player instanceof AbstractClientPlayer) {
                AbstractClientPlayer acp = (AbstractClientPlayer) player;
                sb.append("localCapeTexture:")
                        .append(" playerCape=").append(safeResource(acp.getLocationCape()))
                        .append(" hasInfo=").append(acp.hasPlayerInfo())
                        .append('\n');
            }
        }

        sb.append("gameContext:")
                .append(" hypixel=").append(GameContext.isOnHypixel(mc))
                .append(" blitzGameActive=").append(GameContext.isBlitzGameActive(mc))
                .append(" blitzSpectatorSidebar=").append(GameContext.isBlitzSpectator(mc))
                .append(" replayContext=").append(VisibleSpecs.isReplayContext())
                .append(" applyReplay=").append(VisibleSpecs.isApplySettingsToReplaysEnabled())
                .append('\n');

        sb.append("visibleSpecs:")
                .append(" runtimeActive=").append(VisibleSpecs.isSpectatorSettingsRuntimeActiveForDebug(mc))
                .append(" opacity=").append(fmt(VisibleSpecs.getRenderOpacity()))
                .append(" localEligible=").append(VisibleSpecs.isLocalEligibleForVisibleSpecsOpacity(mc))
                .append(" guiInventoryApply=").append(player != null && VisibleSpecs.shouldApplyToGuiInventoryEntity(player))
                .append(" vertexAlpha=").append(VisibleSpecs.shouldScaleVertexAlphaOnRenderThread())
                .append(" killCounterDead=").append(player != null && KillCounter.isPlayerDead(player.getName()))
                .append(" localTabSpectatorSignals=").append(KillCounter.isLocalBlitzEliminatedOrSpectatingTabSignals(mc))
                .append('\n');

        sb.append("selfOpacity:")
                .append(" active=").append(SelfOpacity.isActive())
                .append(" alpha=").append(fmt(SelfOpacity.getAlpha()))
                .append(" guiInventoryApply=").append(player != null && SelfOpacity.shouldApplyToGuiInventoryEntity(player))
                .append(" vertexAlpha=").append(SelfOpacity.shouldScaleVertexAlphaOnRenderThread())
                .append('\n');

        sb.append("capeSource:")
                .append(" weaveRuntime=").append(GomodFixes.isWeaveRuntime())
                .append(" standaloneActive=").append(PlayerStandaloneCape.isActive())
                .append(" preferGomodStandaloneSource=").append(ConfigManager.isPlayerStandaloneCapePreferGomod())
                .append(" shouldUseGomodDisguiseCapeSource=").append(PlayerStandaloneCape.shouldUseGomodCapeSource())
                .append(" shouldApplyLocalOverride=").append(PlayerStandaloneCape.shouldApplyLocalCapeOverride())
                .append(" suppressMinecraftCape=").append(PlayerStandaloneCape.shouldSuppressMinecraftCapeForLocalPlayer())
                .append(" suppressLunarLocal=").append(PlayerStandaloneCape.shouldSuppressLunarCloakForLocalPlayer())
                .append(" option=").append(ConfigManager.getPlayerStandaloneCapeOptionKey())
                .append(" selectedCape=").append(safeResource(PlayerStandaloneCape.getCapeLocation()))
                .append(" manualCape=").append(safeResource(PlayerStandaloneCape.getLocalGomodCapeForManualRender()))
                .append(" accountCape=").append(safeResource(PlayerStandaloneCape.getAccountCapeLocation()))
                .append('\n');

        sb.append("capeDecision:")
                .append(PlayerStandaloneCape.buildLocalDecisionDebug())
                .append('\n');
        sb.append("capeRelay:")
                .append(PlayerStandaloneCape.buildPeerDecisionDebug())
                .append('\n');

        sb.append("lunarClothBridge:")
                .append(LunarExternalOpacityBridge.debugSnapshot())
                .append('\n');

        sb.append("lunarFullAccess:")
                .append(" preferenceEnabled=").append(LunarOnlineStatus.isFullAccessPreferenceEnabled())
                .append(" routeActive=").append(LunarOnlineStatus.isFullAccessRouteActive())
                .append(" state=").append(LunarOnlineStatus.getFullAccessState().name().toLowerCase(Locale.ROOT))
                .append('\n');

        sb.append("nativeWeaveHooks:")
                .append(" apiHook=").append(classExists("net.weavemc.api.Hook"))
                .append(" loaderHook=").append(classExists("net.weavemc.loader.api.Hook"))
                .append(" clothHookClass=").append(classExists("me.napim.gomod.weave.hooks.LunarClothCloakOpacityWeaveHook"))
                .append(" clothPatchedMethods=").append(reflectStatic("me.napim.gomod.weave.hooks.LunarClothCloakOpacityWeaveHook", "lastPatchedMethods"))
                .append(" cosmeticHookClass=").append(classExists("me.napim.gomod.weave.hooks.LunarCosmeticRenderOpacityWeaveHook"))
                .append(" cosmeticPatchedMethods=").append(reflectStatic("me.napim.gomod.weave.hooks.LunarCosmeticRenderOpacityWeaveHook", "lastPatchedMethods"))
                .append(" currentClothHookClass=").append(classExists("me.napim.gomod.weave.hooks.LunarCurrentClothCloakOpacityWeaveHook"))
                .append(" currentClothPatchedMethods=").append(reflectStatic("me.napim.gomod.weave.hooks.LunarCurrentClothCloakOpacityWeaveHook", "lastPatchedMethods"))
                .append(" vertexAlphaHookClass=").append(classExists("me.napim.gomod.weave.hooks.LunarCurrentClothVertexAlphaWeaveHook"))
                .append(" vertexAlphaPatchedCalls=").append(reflectStatic("me.napim.gomod.weave.hooks.LunarCurrentClothVertexAlphaWeaveHook", "lastPatchedColorCalls"))
                .append(" inventoryHookClass=").append(classExists("me.napim.gomod.weave.hooks.RenderManagerGuiOpacityWeaveHook"))
                .append(" inventoryPatchApplied=").append(reflectStatic("me.napim.gomod.weave.hooks.RenderManagerGuiOpacityWeaveHook", "lastPatchApplied"))
                .append(" inventoryPatchedMethod=").append(reflectStatic("me.napim.gomod.weave.hooks.RenderManagerGuiOpacityWeaveHook", "lastPatchedMethod"))
                .append('\n');

        appendGlState(sb);
        appendJarState(sb);
        return sb.toString();
    }

    private static String tabSnapshot(Minecraft mc, EntityPlayer player) {
        try {
            NetworkPlayerInfo info = mc.getNetHandler() == null ? null
                    : mc.getNetHandler().getPlayerInfo(player.getGameProfile().getId());
            if (info == null) {
                return " info=<none>";
            }
            WorldSettings.GameType gt = info.getGameType();
            String display = info.getDisplayName() == null ? "<none>" : info.getDisplayName().getFormattedText();
            return " gameType=" + gt + " display=" + display;
        } catch (Throwable t) {
            return " error=" + t.getClass().getSimpleName() + ":" + t.getMessage();
        }
    }

    private static void appendGlState(StringBuilder sb) {
        try {
            sb.append("glState:")
                    .append(" blend=").append(GL11.glIsEnabled(GL11.GL_BLEND))
                    .append(" alphaTest=").append(GL11.glIsEnabled(GL11.GL_ALPHA_TEST))
                    .append(" cullFace=").append(GL11.glIsEnabled(GL11.GL_CULL_FACE))
                    .append(" depthMask=").append(GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK))
                    .append('\n');
        } catch (Throwable t) {
            sb.append("glState: error=").append(t.getClass().getSimpleName()).append(':').append(t.getMessage()).append('\n');
        }
    }

    private static void appendJarState(StringBuilder sb) {
        try {
            File weaveJar = new File(new File(System.getProperty("user.home", "."), ".weave\\mods"),
                    "gomod123-lunar-" + GomodVersion.get() + ".jar");
            sb.append("installedJar:")
                    .append(" weave=").append(weaveJar.getAbsolutePath())
                    .append(" exists=").append(weaveJar.isFile())
                    .append(" modifiedMs=").append(weaveJar.isFile() ? weaveJar.lastModified() : -1L)
                    .append(" size=").append(weaveJar.isFile() ? weaveJar.length() : -1L)
                    .append('\n');
        } catch (Throwable ignored) {
        }
    }

    private static String writeReport(String report) {
        try {
            File base = GomodPaths.getGomod123Dir();
            File dir = new File(base, "cape_debug");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return null;
            }
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
            File timestamped = new File(dir, "cape_debug_" + stamp + ".txt");
            File latest = new File(base, "cape_debug_latest.txt");
            if (!writeText(timestamped, report)) {
                return null;
            }
            writeText(latest, report);
            return timestamped.getAbsolutePath();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean writeText(File file, String text) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return false;
            }
            OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
            try {
                writer.write(text);
            } finally {
                writer.close();
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, CapeDebugCommand.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String reflectStatic(String className, String fieldName) {
        try {
            Class<?> c = Class.forName(className, false, CapeDebugCommand.class.getClassLoader());
            java.lang.reflect.Field f = c.getDeclaredField(fieldName);
            f.setAccessible(true);
            Object value = f.get(null);
            return String.valueOf(value);
        } catch (Throwable t) {
            return "<" + t.getClass().getSimpleName() + ">";
        }
    }

    private static String safeName(EntityPlayer player) {
        try {
            return player.getName();
        } catch (Throwable ignored) {
            return "<error>";
        }
    }

    private static String safeResource(Object resource) {
        return resource == null ? "<none>" : String.valueOf(resource);
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
