package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.GomodReleaseBuild;
import me.napim.gomod.GomodVersion;
import me.napim.gomod.features.*;
import me.napim.gomod.shareinv.ShareInvManager;
import me.napim.gomod.util.GameContext;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Snapshot routing gates/counters, never chat bodies, credentials or position history. */
public final class BlitzRelayDebugCommand extends Command {
    public BlitzRelayDebugCommand() { super("blitzrelaydebug"); }
    @Override public void handle(String[] args) {
        final Minecraft mc = Minecraft.getMinecraft();
        final String report = "gomod="+GomodVersion.get()+" public="+GomodReleaseBuild.PUBLIC_RELEASE+"\n"
                +"player="+GomodSocialPresence.sessionPlayerName(mc)+" chatEnabled="+ConfigManager.isForceGameChatsEnabled()
                +" spectatorsEnabled="+ConfigManager.isSeeSpectatorsWhileAliveEnabled()+" appearOffline="+GomodSocialStore.isAppearOffline()+"\n"
                +GameContext.explainDetection(mc)+" replay="+VisibleSpecs.isReplayContext()+"\n"
                +BlitzMatchIdentity.debugStatus(mc)+"\n"+ShareInvManager.relayFeatureDebugStatus()+"\n"
                +ForceGameChat.debugStatus()+"\n"+SpectatorRelay.debugStatus()+"\n"+SpectatorRelay.debugPoseStatus();
        Thread writer = new Thread(() -> {
            try {
                File directory = new File(GomodPaths.getGomod123Dir(), "debugall");
                Files.createDirectories(directory.toPath());
                File file = Files.createTempFile(directory.toPath(), "blitz_relay_", ".txt").toFile();
                Files.write(file.toPath(), report.getBytes(StandardCharsets.UTF_8));
                mc.addScheduledTask(() -> Main.sendMessage(Main.CHAT_PREFIX+EnumChatFormatting.GREEN+"Blitz relay debug saved: "+file.getAbsolutePath()));
            } catch(Exception failure) {
                mc.addScheduledTask(() -> Main.sendMessage(Main.CHAT_PREFIX+EnumChatFormatting.RED+"Could not save Blitz relay debug: "+failure.getMessage()));
            }
        }, "gomod-blitz-relay-debug-writer");
        writer.setDaemon(true); writer.start();
    }
}
