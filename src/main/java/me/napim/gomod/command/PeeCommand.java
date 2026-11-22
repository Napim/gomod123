package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.HypixelTabStatsRankCache;
import me.napim.gomod.features.LocalDisguise;
import me.napim.gomod.features.MojangUsernameResolver;
import me.napim.gomod.features.PeeEffectManager;
import me.napim.gomod.features.TabDuplicateNickResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.util.UUID;

/**
 * Client-side particle gag for several seconds; prints a Hypixel-style taunt line in chat.
 */
public final class PeeCommand extends Command {

    public PeeCommand() {
        super("pee");
    }

    @Override
    public void handle(String[] args) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.thePlayer.getGameProfile() == null
                || mc.thePlayer.getGameProfile().getName() == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "[pee] Not in game.");
            return;
        }

        String nick = mc.thePlayer.getGameProfile().getName().trim();
        if (nick.isEmpty()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "[pee] Not in game.");
            return;
        }

        PeeEffectManager.activate();
        Main.getGlobalChatClient().broadcastPee();

        String disguisedRankAndName = LocalDisguise.formatCosmeticChatRankAndDisplayName();
        if (disguisedRankAndName != null && !disguisedRankAndName.isEmpty()) {
            sendPeeMessage(disguisedRankAndName);
            return;
        }

        String real = TabDuplicateNickResolver.getKnownRealName(nick);
        String realPlain = (real != null && !real.trim().isEmpty()) ? real.trim() : nick;
        if (HypixelTabStatsRankCache.needsHypixelRankLookup(realPlain, nick)) {
            String uuid = mc.thePlayer.getUniqueID().toString().replace("-", "");
            HypixelTabStatsRankCache.fetchHypixelRankIfNeeded(uuid, nick, realPlain,
                    () -> sendPeeMessage(buildRankAndName(nick, realPlain)));
            return;
        }

        sendPeeMessage(buildRankAndName(nick, realPlain));
    }

    private static String buildRankAndName(String nick, String realPlain) {
        String tag = HypixelTabStatsRankCache.getFormattedRankTag(nick);
        if (tag == null || tag.isEmpty()) {
            tag = HypixelTabStatsRankCache.getFormattedRankTag(realPlain);
        }
        String nameColor = HypixelTabStatsRankCache.getUsernameColorFormattingPreferKeys(realPlain, nick);
        if (nameColor == null || nameColor.isEmpty()) {
            nameColor = EnumChatFormatting.GRAY.toString();
        }
        return (tag == null || tag.isEmpty())
                ? nameColor + nick + EnumChatFormatting.RESET
                : tag + " " + nameColor + nick + EnumChatFormatting.RESET;
    }

    /** Prints the same taunt line when another gomod client relays a /pee event. */
    public static void showRemotePeeMessage(String sender, String senderUuid) {
        if (sender == null || sender.trim().isEmpty()) {
            return;
        }
        String nick = sender.trim();
        String uuid = normalizeUuid(senderUuid);
        if (!isRemotePlayerPresent(nick, uuid)) {
            return;
        }
        String real = TabDuplicateNickResolver.getKnownRealName(nick);
        String realPlain = real != null && !real.trim().isEmpty() ? real.trim() : nick;
        if (!HypixelTabStatsRankCache.needsHypixelRankLookup(realPlain, nick)) {
            sendRemotePeeMessageIfPresent(nick, realPlain, uuid);
            return;
        }

        if (uuid.isEmpty()) {
            uuid = findOnlinePlayerUuid(nick, realPlain);
        }
        if (!uuid.isEmpty()) {
            fetchRemoteRankAndSend(uuid, nick, realPlain);
            return;
        }

        final String presenceUuid = uuid;
        MojangUsernameResolver.resolveProfileAsync(realPlain, profile -> {
            if (!isRemotePlayerPresent(nick, presenceUuid)) {
                return;
            }
            String resolvedUuid = profile == null ? "" : normalizeUuid(profile.id);
            fetchRemoteRankAndSend(resolvedUuid, nick, realPlain);
        });
    }

    private static void fetchRemoteRankAndSend(String uuid, String nick, String realPlain) {
        HypixelTabStatsRankCache.fetchHypixelRankIfNeeded(uuid, nick, realPlain,
                () -> sendRemotePeeMessageIfPresent(nick, realPlain, uuid));
    }

    private static void sendRemotePeeMessageIfPresent(String nick, String realPlain, String uuid) {
        if (isRemotePlayerPresent(nick, uuid)) {
            sendPeeMessage(buildRankAndName(nick, realPlain));
        }
    }

    /** Relay events are server-wide, so only render events from players in this loaded world. */
    public static boolean isRemotePlayerPresent(String name) {
        return isRemotePlayerPresent(name, "");
    }

    /** Relay events are server-wide, so only render events from players in this loaded world. */
    public static boolean isRemotePlayerPresent(String name, String uuid) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            return false;
        }
        String expected = name == null ? "" : name.trim();
        String expectedUuid = normalizeUuid(uuid);
        if (expected.isEmpty() && expectedUuid.isEmpty()) {
            return false;
        }
        for (Object value : mc.theWorld.playerEntities) {
            if (!(value instanceof EntityPlayer)) {
                continue;
            }
            EntityPlayer player = (EntityPlayer) value;
            if (player == mc.thePlayer) {
                continue;
            }
            UUID entityUuid = player.getUniqueID();
            if (!expectedUuid.isEmpty() && entityUuid != null
                    && expectedUuid.equals(normalizeUuid(entityUuid.toString()))) {
                return true;
            }
            String entityName = player.getName();
            String profileName = player.getGameProfile() == null ? null : player.getGameProfile().getName();
            if ((entityName != null && entityName.equalsIgnoreCase(expected))
                    || (profileName != null && profileName.equalsIgnoreCase(expected))) {
                return true;
            }
        }
        return false;
    }

    private static String findOnlinePlayerUuid(String nick, String realPlain) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getNetHandler() == null) {
            return "";
        }
        for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
            if (info == null || info.getGameProfile() == null) {
                continue;
            }
            String profileName = info.getGameProfile().getName();
            if (profileName == null || (!profileName.equalsIgnoreCase(nick)
                    && !profileName.equalsIgnoreCase(realPlain))) {
                continue;
            }
            UUID id = info.getGameProfile().getId();
            return id == null ? "" : id.toString().replace("-", "");
        }
        return "";
    }

    private static String normalizeUuid(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().replace("-", "");
        if (normalized.length() != 32) {
            return "";
        }
        for (int i = 0; i < normalized.length(); i++) {
            if (Character.digit(normalized.charAt(i), 16) < 0) {
                return "";
            }
        }
        return normalized;
    }

    private static void sendPeeMessage(String rankAndName) {
        String msg = rankAndName + " " + EnumChatFormatting.YELLOW + "Performed the Pee Dance Taunt!"
                + EnumChatFormatting.RESET;
        Main.sendMessage(msg);
    }
}
