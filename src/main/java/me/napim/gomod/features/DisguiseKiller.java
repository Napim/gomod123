package me.napim.gomod.features;

import com.mojang.authlib.GameProfile;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.command.DisguiseCommand;
import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.weavemc.loader.api.event.PacketEvent;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.weavemc.loader.api.event.TickEvent;

/** Observes original server kill credit, independently of the optional TAB kill counter. */
public final class DisguiseKiller {
    private static final String PLAYER_LABEL = "(?:\\[[^\\]\\r\\n]{1,48}\\]\\s*)*([A-Za-z0-9_]{1,16})"
            + "(?:\\s+\\[[^\\]\\r\\n]{1,48}\\])*";
    private static final Pattern BROADCAST = Pattern.compile("^" + PLAYER_LABEL
            + "\\s+(?:was|were)\\s+(?:killed|slain|shot|blown up|burned to death|frozen to death|electrocuted|eliminated|defeated)"
            + "\\s+by\\s+" + PLAYER_LABEL + "(?=$|[\\s!.,(])", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELF_CONFIRMATION = Pattern.compile(
            "^You\\s+(?:killed|shot|slew|slain|blew up|burned to death|frozen to death|electrocuted|eliminated|defeated)\\s+"
                    + PLAYER_LABEL + "(?=$|[\\s!.,(])", Pattern.CASE_INSENSITIVE);

    // Only the packet-entry toggle/epoch are read off the client thread. All remaining state is client-thread owned.
    private static volatile boolean enabled;
    private static volatile long modeEpoch;
    private static Object world;
    private static long pendingRequest = -1L;
    private static int lastProfileTick = Integer.MIN_VALUE;
    private static final Set<String> copiedVictims = new HashSet<>();
    private static final Map<String, GameProfile> seenProfiles = new HashMap<>();

    public static boolean isEnabled() {
        return enabled && GomodInternalFeatures.isEnabled();
    }

    public static void setEnabled(boolean value) {
        boolean selected = value && GomodInternalFeatures.isEnabled();
        if (enabled == selected) return;
        enabled = selected;
        modeEpoch++;
        if (!selected) cancelOwnedRequest();
        Minecraft mc = Minecraft.getMinecraft();
        syncWorld(mc == null ? null : mc.theWorld);
        if (selected && mc != null) rememberProfiles(mc);
    }

    private static void cancelOwnedRequest() {
        synchronized (LocalDisguise.class) {
            if (pendingRequest >= 0L && LocalDisguise.isEnableRequestCurrent(pendingRequest)) {
                LocalDisguise.cancelPendingEnableRequests();
            }
        }
        pendingRequest = -1L;
    }

    private static void syncWorld(Object current) {
        if (world == current) return;
        cancelOwnedRequest();
        world = current;
        copiedVictims.clear();
        seenProfiles.clear();
        lastProfileTick = Integer.MIN_VALUE;
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        syncWorld(mc == null ? null : mc.theWorld);
        if (!isEnabled() || mc == null || mc.thePlayer == null || mc.theWorld == null
                || !GameContext.isOnHypixel(mc) || ReplaySettings.isReplayActive()
                || GameContext.isHypixelReplayWatching(mc)) return;
        int tick = mc.thePlayer.ticksExisted;
        if (lastProfileTick != Integer.MIN_VALUE && tick >= lastProfileTick && tick - lastProfileTick < 20) return;
        lastProfileTick = tick;
        rememberProfiles(mc);
    }

    @SubscribeEvent
    public void onPacket(PacketEvent.Receive event) {
        if (!isEnabled() || !(event.getPacket() instanceof S02PacketChat)) return;
        S02PacketChat packet = (S02PacketChat) event.getPacket();
        if (packet.getType() == 2) return;
        IChatComponent component = packet.getChatComponent();
        if (component == null) return;
        KillCredit parsed = parseCredit(component.getUnformattedText());
        if (parsed == null) parsed = parseCredit(component.getFormattedText());
        if (parsed == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) return;
        final KillCredit credit = parsed;
        final Object packetWorld = mc.theWorld;
        final long epoch = modeEpoch;
        mc.addScheduledTask(() -> acceptCredit(mc, packetWorld, epoch, credit));
    }

    private static KillCredit parseCredit(String text) {
        if (text == null) return null;
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(text).trim();
        Matcher confirmation = SELF_CONFIRMATION.matcher(plain);
        if (confirmation.find()) return new KillCredit(confirmation.group(1), "you");
        Matcher broadcast = BROADCAST.matcher(plain);
        return broadcast.find() ? new KillCredit(broadcast.group(1), broadcast.group(2)) : null;
    }

    private static void acceptCredit(Minecraft mc, Object packetWorld, long epoch, KillCredit credit) {
        if (!isEnabled() || epoch != modeEpoch || mc.theWorld != packetWorld
                || mc.thePlayer == null || mc.getNetHandler() == null) return;
        syncWorld(mc.theWorld);
        if (!GameContext.isOnHypixel(mc) || ReplaySettings.isReplayActive()
                || GameContext.isHypixelReplayWatching(mc)
                || !hasBlitzSidebarTitle(mc)
                || !(GameContext.isBlitzGameActive(mc) || GameContext.isBlitzDeathmatchPhase(mc)
                || GameContext.isBlitzGameOverSidebar(mc))) return;
        if (!isLocalName(mc, credit.killer) || isLocalName(mc, credit.victim)) return;
        // A public broadcast and a second-person confirmation describe the same single-life Blitz elimination.
        if (!copiedVictims.add(credit.victim.toLowerCase(Locale.ROOT))) return;
        GameProfile profile = findVictimProfile(mc, credit.victim);
        pendingRequest = DisguiseCommand.enableKilledPlayerDisguiseAsync(credit.victim, profile);
    }

    private static boolean hasBlitzSidebarTitle(Minecraft mc) {
        ScoreObjective objective = mc.theWorld.getScoreboard().getObjectiveInDisplaySlot(1);
        if (objective == null) return false;
        String title = EnumChatFormatting.getTextWithoutFormattingCodes(objective.getDisplayName())
                .toLowerCase(Locale.ROOT);
        String internal = objective.getName().toLowerCase(Locale.ROOT);
        return title.contains("blitz") || title.replace(" ", "").equals("bsg")
                || internal.equals("bsg") || internal.contains("blitz");
    }

    private static boolean isLocalName(Minecraft mc, String name) {
        if ("you".equalsIgnoreCase(name)) return true;
        if (name == null || mc.thePlayer == null) return false;
        if (mc.getSession() != null && name.equalsIgnoreCase(mc.getSession().getUsername())) return true;
        if (LocalDisguise.isEnabled() && name.equalsIgnoreCase(LocalDisguise.getRealName())) return true;
        NetworkPlayerInfo localRow = mc.getNetHandler() == null ? null
                : mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
        GameProfile source = localRow == null ? mc.thePlayer.getGameProfile()
                : RemoteDisguiseManager.getSourceProfile(localRow);
        // Never use the active disguise target as a local alias: that account may also be in the game.
        return source != null && name.equalsIgnoreCase(source.getName());
    }

    private static void rememberProfiles(Minecraft mc) {
        if (mc.theWorld == null || mc.getNetHandler() == null) return;
        for (NetworkPlayerInfo row : mc.getNetHandler().getPlayerInfoMap()) {
            GameProfile source = RemoteDisguiseManager.getSourceProfile(row);
            if (source != null && source.getName() != null && seenProfiles.size() < 256) {
                seenProfiles.put(source.getName().toLowerCase(Locale.ROOT), copyProfile(source));
            }
        }
    }

    private static GameProfile findVictimProfile(Minecraft mc, String victim) {
        rememberProfiles(mc);
        GameProfile profile = seenProfiles.get(victim.toLowerCase(Locale.ROOT));
        if (profile == null) {
            for (EntityPlayer player : mc.theWorld.playerEntities) {
                if (victim.equalsIgnoreCase(player.getGameProfile().getName())) {
                    profile = player.getGameProfile();
                    break;
                }
            }
        }
        return copyProfile(profile);
    }

    private static GameProfile copyProfile(GameProfile source) {
        if (source == null || source.getId() == null || source.getName() == null) return null;
        GameProfile copy = new GameProfile(source.getId(), source.getName());
        copy.getProperties().putAll(source.getProperties());
        return copy;
    }

    private static final class KillCredit {
        final String victim, killer;
        KillCredit(String victim, String killer) {
            this.victim = victim;
            this.killer = killer;
        }
    }
}
