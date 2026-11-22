package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.weavemc.loader.api.event.PacketEvent.Receive;
import net.minecraft.network.play.server.S02PacketChat;
import me.napim.gomod.command.TeamSpamCommand;
import me.napim.gomod.Main;
import me.napim.gomod.features.TabDuplicateNickResolver;
import me.napim.gomod.features.TeamSpamManager.Player;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.lang.ref.WeakReference;
import java.util.Locale;

/**
 * Handles responses to team invite commands, providing customized formatting.
 */
public class TeamInviteResponseHandler {
    // Map real username -> nickname (persist during session)
    public static final java.util.concurrent.ConcurrentHashMap<String, String> REAL_TO_NICK = new java.util.concurrent.ConcurrentHashMap<>();
    public static final java.util.concurrent.ConcurrentHashMap<String, String> REAL_TO_NICK_COLORED = new java.util.concurrent.ConcurrentHashMap<>();

    // The denicker maps above also contain historical/crowd/manual discoveries. A green finder row must
    // describe its latest invite response in this world, including presence-only responses with no known nick.
    private static final java.util.concurrent.ConcurrentHashMap<String, FoundPlayer> FOUND_PLAYERS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long FOUND_HIGHLIGHT_MS = 15L * 60L * 1000L;

    public static final class FoundPlayer {
        public final String nickname;
        public final String coloredNickname;
        private final WeakReference<Object> world;
        private final long foundAt;

        private FoundPlayer(Object world, String nickname, String coloredNickname) {
            this.world = new WeakReference<>(world);
            this.foundAt = System.currentTimeMillis();
            this.nickname = nickname;
            this.coloredNickname = coloredNickname;
        }
    }

    public static FoundPlayer getFoundPlayer(String realName) {
        if (realName == null) return null;
        String key = realName.toLowerCase(Locale.ROOT);
        FoundPlayer found = FOUND_PLAYERS.get(key);
        if (found == null) return null;
        Minecraft mc = Minecraft.getMinecraft();
        long age = System.currentTimeMillis() - found.foundAt;
        if (mc == null || mc.theWorld == null || found.world.get() != mc.theWorld
                || age < 0L || age >= FOUND_HIGHLIGHT_MS) {
            FOUND_PLAYERS.remove(key, found);
            return null;
        }
        return found;
    }

    private static void rememberFoundPlayer(String realName, String nickname, String coloredNickname) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || realName == null || realName.isEmpty()) return;
        if (coloredNickname != null && (nickname == null
                || !nickname.equals(EnumChatFormatting.getTextWithoutFormattingCodes(coloredNickname)))) {
            coloredNickname = null;
        }
        FOUND_PLAYERS.put(realName.toLowerCase(Locale.ROOT), new FoundPlayer(mc.theWorld, nickname, coloredNickname));
    }
    
    // Pattern to match player not found message
    private static final String PLAYER_NOT_FOUND = "Couldn't find a player by that name!";
    
    // Pattern to match player already in game message
    private static final Pattern PLAYER_IN_GAME_PATTERN = Pattern.compile("^([A-Za-z0-9_]{1,16}) is in this game[.!]?$");
    
    // Pattern to match player on full team message
    private static final String PLAYER_ON_FULL_TEAM = "That player is already on a full team!";
    
    // Pattern to match team invite sent message
    private static final Pattern TEAM_INVITE_SENT_PATTERN = Pattern.compile("^Team request sent to ([A-Za-z0-9_]{1,16})!$");
    
    public TeamInviteResponseHandler() {
        // Initialization is now handled by Main.java
        System.out.println("[TeamInviteResponseHandler] Initialized");
    }
    
    @SubscribeEvent
    public void onChat(Receive event) {
        try {
            if (!(event.getPacket() instanceof S02PacketChat)) return;
            S02PacketChat packet=(S02PacketChat)event.getPacket();
            if(packet.getChatComponent()==null || (packet.getType()!=0 && packet.getType()!=1))return;
            String message=packet.getChatComponent().getUnformattedText().trim();
            String formatted=packet.getChatComponent().getFormattedText();
            int kind;String responseName=null;
            if(message.equals(PLAYER_NOT_FOUND))kind=1;
            else if(message.equals(PLAYER_ON_FULL_TEAM))kind=2;
            else if(message.equals("You've already sent that player a team request!"))kind=5;
            else {
                Matcher inGame=PLAYER_IN_GAME_PATTERN.matcher(message);
                Matcher sent=TEAM_INVITE_SENT_PATTERN.matcher(message);
                if(inGame.matches()){kind=3;responseName=inGame.group(1);}
                else if(sent.matches()){kind=4;responseName=sent.group(1);}
                else return;
            }
            final TeamSpamCommand.InviteAttempt attempt=TeamSpamCommand.claimInviteResponse(kind==3?responseName:null);
            if(attempt==null)return;
            if(kind==5)return;
            final int responseKind=kind;
            final String nick=responseName;
            final Minecraft mc=Minecraft.getMinecraft();
            // A same-name acknowledgement consumes its request, but retains the original visible response.
            boolean sameName=kind==4&&attempt.realName.equalsIgnoreCase(nick);
            if(!sameName)event.setCancelled(true);
            mc.addScheduledTask(()->{
                // A manual invite, restart, world/account/connection change invalidates queued discoveries too.
                if(!TeamSpamCommand.isInviteAttemptCurrent(attempt))return;
                String real=attempt.realName;
                if(responseKind==1){FOUND_PLAYERS.remove(real.toLowerCase(Locale.ROOT));sendCustomNotFoundMessage(real);return;}
                if(responseKind==4&&real.equalsIgnoreCase(nick)){FOUND_PLAYERS.remove(real.toLowerCase(Locale.ROOT));return;}
                if(responseKind==4&&TeamSpamManager.findPlayerByName(real)==null)return;
                TeamSpamCommand.lastFoundPlayer=real;
                String colored=responseKind==4?extractColoredSegment(formatted,nick):null;
                rememberFoundPlayer(real,responseKind==4?nick:null,colored);
                TeamSpamManager.updatePlayerTimestamp(real);
                if(responseKind==2)sendCustomFullTeamMessage(real);
                else if(responseKind==3)sendCustomInGameMessage(real);
                else {
                    String key=real.toLowerCase(Locale.ROOT);
                    if(colored!=null&&!colored.isEmpty())REAL_TO_NICK_COLORED.put(key,colored);else REAL_TO_NICK_COLORED.remove(key);
                    sendCustomNickedPlayerMessage(real,nick);
                }
            });
        } catch (Exception e) {
            System.err.println("[TeamInviteResponseHandler] Error: "+e.getClass().getSimpleName());
        }
    }

    // Attempts to extract the colored substring for the given unformatted target
    private String extractColoredSegment(String formatted, String target) {
        if (formatted == null || target == null || target.isEmpty()) return null;
        // Build unformatted from formatted while tracking indices
        StringBuilder plain = new StringBuilder();
        java.util.List<int[]> spans = new java.util.ArrayList<>(); // each int[]{startFmt, endFmt} for each plain char
        for (int i = 0; i < formatted.length(); ) {
            char c = formatted.charAt(i);
            if (c == '\u00A7' && i + 1 < formatted.length()) { // color code
                i += 2; // skip color code
                continue;
            }
            int start = i;
            // advance one visible char (could be surrogate, but MC range is BMP)
            i++;
            plain.append(c);
            spans.add(new int[]{start, i});
        }
        String plainStr = plain.toString();
        int idx = plainStr.lastIndexOf(target);
        if (idx < 0) return null;
        int startFmt = spans.get(idx)[0];
        int endFmt = spans.get(idx + target.length() - 1)[1];
        // Preserve the style active at the start of the nick, rather than borrowing the real account's rank.
        StringBuilder style = new StringBuilder();
        for (int i = 0; i + 1 < startFmt; i++) {
            if (formatted.charAt(i) != '\u00A7') continue;
            char code = Character.toLowerCase(formatted.charAt(++i));
            if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f') || code == 'r') {
                style.setLength(0);
            }
            style.append('\u00A7').append(code);
        }
        return style.toString() + formatted.substring(startFmt, endFmt) + EnumChatFormatting.RESET;
    }
    
    /**
     * Sends a custom formatted message when a player is not found.
     * @param playerName The name of the player not found
     */
    private void sendCustomNotFoundMessage(String playerName) {
        ChatComponentText component = new ChatComponentText(
            Main.CHAT_PREFIX + EnumChatFormatting.DARK_RED + "- " + playerName
        );
        Minecraft.getMinecraft().thePlayer.addChatMessage(component);
    }
    
    /**
     * Sends a custom formatted message when a player is already in the game.
     * @param playerName The name of the player in the game
     */
    private void sendCustomInGameMessage(String playerName) {
        ChatComponentText component = new ChatComponentText(
            Main.CHAT_PREFIX + EnumChatFormatting.GOLD + "- " + playerName + 
            EnumChatFormatting.YELLOW + " is in this game"
        );
        Minecraft.getMinecraft().thePlayer.addChatMessage(component);
    }
    
    /**
     * Sends a custom formatted message when a player is on a full team.
     * @param playerName The name of the player on a full team
     */
    private void sendCustomFullTeamMessage(String playerName) {
        ChatComponentText component = new ChatComponentText(
            Main.CHAT_PREFIX + EnumChatFormatting.GOLD + "- " + playerName + 
            EnumChatFormatting.YELLOW + " is in this game"
        );
        Minecraft.getMinecraft().thePlayer.addChatMessage(component);
    }
    
    /**
     * Sends a custom formatted message when a player is nicked.
     * @param realName The real name of the player
     * @param nickName The nick of the player
     */
    private void sendCustomNickedPlayerMessage(String realName, String nickName) {
        // Remember mapping (case-insensitive by storing lower-case key)
        if (realName != null && !realName.isEmpty() && nickName != null && !nickName.isEmpty()) {
            REAL_TO_NICK.put(realName.toLowerCase(Locale.ROOT), nickName);
            TabDuplicateNickResolver.registerNickMappingFromTeamsFinder(realName, nickName);
        }
        ChatComponentText component = new ChatComponentText(
            Main.CHAT_PREFIX + EnumChatFormatting.GOLD + "- " + realName + 
            EnumChatFormatting.YELLOW + " is nicked as " + nickName
        );
        Minecraft.getMinecraft().thePlayer.addChatMessage(component);
    }
}
