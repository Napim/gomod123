package me.napim.gomod.features;

import com.google.gson.JsonObject;
import me.napim.gomod.shareinv.ShareInvManager;
import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S3EPacketTeams;
import net.minecraft.network.play.server.S3BPacketScoreboardObjective;
import net.minecraft.network.play.server.S45PacketTitle;
import net.minecraft.util.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLongArray;

/** Account-authenticated cross-channel chat, displayed locally without synthesizing game packets. */
public final class ForceGameChat {
    private static final GameChatLedger LEDGER = new GameChatLedger();
    private static Object world, player;
    private static String game="", role="off", session=UUID.randomUUID().toString();
    private static long nextContext, nextState, sequence;
    private static int nextLineId=-2200000;
    private static final AtomicLongArray PACKET_TYPES = new AtomicLongArray(4);
    private static long sentMessages, receivedMessages, unknownSpeakers, wrongChannels, sendFailures, decodeFailures;
    private static String lastGate = "not_ticked";
    private static Object roundHandler;
    private static boolean roundEnded;
    private static String endedGame="", endReason="none";
    private static long nextEndRoomCheck;
    private ForceGameChat(){}
    public static void setEnabled(boolean enabled) {
        ConfigManager.setForceGameChatsEnabled(enabled);
        if(enabled) ShareInvManager.requestSpectatorRelayConnection(); else {stop();LEDGER.clear();}
        nextContext=nextState=0;
    }
    public static void clientTick(Minecraft mc) { refresh(mc); }
    private static void stop() {
        if(!"off".equals(role)) {
            JsonObject packet=new JsonObject();packet.addProperty("type","game_chat_state");packet.addProperty("role","off");
            ShareInvManager.sendGameChatPacket(packet);
        }
        role="off";game="";nextContext=nextState=0;
    }
    private static boolean refresh(Minecraft mc) {
        boolean enabled = ConfigManager.isForceGameChatsEnabled();
        boolean inPregameOrHub = syncRoundBoundary(mc, enabled);
        String blocked = blockedReason(mc, enabled, inPregameOrHub);
        if(!blocked.isEmpty()) {stop();lastGate=blocked;world=player=null;return false;}
        long now=System.currentTimeMillis();
        if(world!=mc.theWorld || player!=mc.thePlayer) {
            stop();LEDGER.clear();world=mc.theWorld;player=mc.thePlayer;
            session=UUID.randomUUID().toString();sequence=0;
        }
        if(now>=nextContext) {
            String current=BlitzMatchIdentity.resolve(mc);
            if(!current.equals(game)){stop();LEDGER.clear();game=current;session=UUID.randomUUID().toString();sequence=0;}
            nextContext=now+1000;
        }
        String currentRole=VisibleSpecs.isLocalEligibleForVisibleSpecsOpacity(mc)?"spectator":"alive";
        if(game.isEmpty()) {lastGate="missing_game_id";return false;}
        lastGate="ready";
        if(!currentRole.equals(role)){role=currentRole;nextState=0;}
        if(now>=nextState) {
            JsonObject state=new JsonObject();state.addProperty("type","game_chat_state");state.addProperty("game",game);
            state.addProperty("role",role);state.addProperty("session",session);
            ShareInvManager.sendGameChatPacket(state);nextState=now+1000;
        }
        return true;
    }
    /** Capture immutable raw server text before display/disguise handlers; never forward a local relay insertion. */
    public static void onIncomingPacket(Packet<?> packet) {
        if(!ConfigManager.isForceGameChatsEnabled())return;
        String terminal="";
        if(packet instanceof S3EPacketTeams) {
            S3EPacketTeams team=(S3EPacketTeams)packet;
            if((team.getAction()==0||team.getAction()==2) && BlitzGameEndSignal.sidebar(team.getPrefix()+team.getSuffix()))terminal="sidebar_packet";
        } else if(packet instanceof S3BPacketScoreboardObjective) {
            S3BPacketScoreboardObjective objective=(S3BPacketScoreboardObjective)packet;
            if(objective.func_149338_e()!=1 && BlitzGameEndSignal.sidebar(objective.func_149337_d()))terminal="objective_packet";
        } else if(packet instanceof S45PacketTitle) {
            S45PacketTitle title=(S45PacketTitle)packet;
            if((title.getType()==S45PacketTitle.Type.TITLE||title.getType()==S45PacketTitle.Type.SUBTITLE)
                    && title.getMessage()!=null && BlitzGameEndSignal.title(title.getMessage().getFormattedText()))terminal="result_title";
        }
        if(!terminal.isEmpty()){queueEnd(terminal);return;}
        if(!(packet instanceof S02PacketChat))return;
        S02PacketChat chat=(S02PacketChat)packet;
        int type = chat.getType(); PACKET_TYPES.incrementAndGet(type >= 0 && type <= 2 ? type : 3);
        if((chat.getType()!=0 && chat.getType()!=1) || chat.getChatComponent()==null)return;
        final String text=chat.getChatComponent().getFormattedText();
        if(BlitzGameEndSignal.chat(text)){queueEnd("result_chat");return;}
        BlitzGameChatLine line = BlitzGameChatLine.parse(text);
        if(line==null)return;
        Minecraft mc=Minecraft.getMinecraft();
        if (mc == null) return;
        if (mc.isCallingFromMinecraftThread()) {
            observe(line);
        } else {
            Object sourceHandler = mc.getNetHandler(), sourceWorld = mc.theWorld;
            mc.addScheduledTask(() -> {
                if (mc.getNetHandler() == sourceHandler && mc.theWorld == sourceWorld) {
                    observe(line);
                }
            });
        }
    }
    private static void observe(BlitzGameChatLine line) {
        Minecraft mc=Minecraft.getMinecraft();boolean active=refresh(mc);
        // A relay copy already displayed just before game end can still have its native copy in flight.
        int delete=LEDGER.natural(line.key,System.currentTimeMillis());
        if(delete!=0 && mc!=null && mc.ingameGUI!=null) mc.ingameGUI.getChatGUI().deleteChatLine(delete);
        if(!active)return;
        if(!knownSpeaker(mc,line.speaker)){unknownSpeakers++;return;}
        if(!line.channel.equals(role)){wrongChannels++;return;}
        JsonObject packet=new JsonObject();packet.addProperty("type","game_chat_message");packet.addProperty("game",game);
        packet.addProperty("session",session);packet.addProperty("sequence",++sequence);packet.addProperty("text",line.formatted);
        if(ShareInvManager.sendGameChatPacket(packet))sentMessages++;else sendFailures++;
    }
    private static boolean knownSpeaker(Minecraft mc,String name) {
        if(mc.getNetHandler()!=null)for(NetworkPlayerInfo info:mc.getNetHandler().getPlayerInfoMap()) {
            if(info.getGameProfile()!=null && name.equalsIgnoreCase(info.getGameProfile().getName()))return true;
        }
        for(EntityPlayer entity:mc.theWorld.playerEntities)if(name.equalsIgnoreCase(entity.getName()))return true;
        return false;
    }
    public static void receive(JsonObject packet) {
        Minecraft mc=Minecraft.getMinecraft();if(!refresh(mc))return;
        try {
            if(!game.equals(packet.get("game").getAsString()))return;
            BlitzGameChatLine line=BlitzGameChatLine.parse(packet.get("text").getAsString());
            if(line==null || line.channel.equals(role) || !line.channel.equals(packet.get("channel").getAsString()))return;
            String id=packet.get("id").getAsString();if(!id.matches("[a-f0-9]{24}"))return;
            int chatId=nextLineId--;if(nextLineId < -2300000)nextLineId=-2200000;
            if(!LEDGER.relay(id,line.key,chatId,System.currentTimeMillis()))return;
            IChatComponent display=GomodPeerDisguiseDisplay.rewriteIncomingChat(
                    LocalDisguise.rewriteIncomingChatComponentForDisplay(new ChatComponentText(line.formatted)));
            if(mc.ingameGUI!=null){mc.ingameGUI.getChatGUI().printChatMessageWithOptionalDeletion(display,chatId);receivedMessages++;}
        }catch(RuntimeException ignored){decodeFailures++;}
    }
    private static String blockedReason(Minecraft mc, boolean enabled, boolean inPregameOrHub) {
        if(mc==null || mc.theWorld==null || mc.thePlayer==null)return "no_world";
        if(!enabled)return "disabled";
        if(GomodSocialStore.isAppearOffline())return "appear_offline";
        if(VisibleSpecs.isReplayContext())return "replay";
        if(!GameContext.isOnHypixel(mc))return "not_hypixel";
        if(inPregameOrHub)return "not_live_blitz";
        if(roundEnded)return "game_ended";
        if(GameContext.isBlitzGameOverSidebar(mc)) {markEnded(mc,"game_over_sidebar");return "game_ended";}
        if(!(GameContext.isBlitzGameActive(mc)||GameContext.isBlitzDeathmatchPhase(mc)))return "not_live_blitz";
        for(String line:HypixelScoreboardMapResolver.collectSidebarTextLines(mc))if(BlitzGameEndSignal.sidebar(line)) {
            markEnded(mc,"terminal_sidebar");return "game_ended";
        }
        return "";
    }
    private static boolean syncRoundBoundary(Minecraft mc, boolean enabled) {
        Object handler=mc==null?null:mc.getNetHandler();
        if(roundHandler!=handler){roundHandler=handler;clearEnd();LEDGER.clear();}
        if(mc==null||mc.theWorld==null||mc.thePlayer==null||(!enabled&&!roundEnded))return false;
        if(GameContext.isBlitzMatchMapPregameSidebar(mc)||GameContext.isBlitzHypixelHubQueueSidebar(mc)) {clearEnd();return true;}
        long now=System.currentTimeMillis();
        if(roundEnded && now>=nextEndRoomCheck) {
            nextEndRoomCheck=now+1000L;
            String current=BlitzMatchIdentity.resolve(mc);
            if(!current.isEmpty()&&!endedGame.isEmpty()&&!current.equals(endedGame)){clearEnd();LEDGER.clear();}
        }
        return false;
    }
    private static void clearEnd(){roundEnded=false;endedGame="";endReason="none";nextEndRoomCheck=0;}
    private static void markEnded(Minecraft mc,String reason) {
        if(!roundEnded){endedGame=game.isEmpty()?BlitzMatchIdentity.resolve(mc):game;endReason=reason;}
        roundEnded=true;stop();lastGate="game_ended";
    }
    private static void queueEnd(final String reason) {
        final Minecraft mc=Minecraft.getMinecraft();if(mc==null)return;
        final Object sourceHandler=mc.getNetHandler(),sourceWorld=mc.theWorld;
        Runnable apply=()->{
            if(mc.getNetHandler()!=sourceHandler||mc.theWorld!=sourceWorld)return;
            if (!ConfigManager.isForceGameChatsEnabled()) return;
            syncRoundBoundary(mc, true);
            if(!VisibleSpecs.isReplayContext()&&GameContext.isOnHypixel(mc)
                    && !GameContext.isBlitzMatchMapPregameSidebar(mc)
                    && (GameContext.isBlitzGameActive(mc)||GameContext.isBlitzDeathmatchPhase(mc)||GameContext.isBlitzGameOverSidebar(mc)))markEnded(mc,reason);
        };
        if(mc.isCallingFromMinecraftThread())apply.run();else mc.addScheduledTask(apply);
    }
    public static String debugStatus() {
        return "chat game="+game+" role="+role+" gate="+lastGate+" rawType0="+PACKET_TYPES.get(0)
                +" rawType1="+PACKET_TYPES.get(1)+" actionbar="+PACKET_TYPES.get(2)+" otherType="+PACKET_TYPES.get(3)
                +" sent="+sentMessages+" displayed="+receivedMessages+" sendFailed="+sendFailures
                +" unknownSpeaker="+unknownSpeakers+" otherChannel="+wrongChannels+" decodeFailed="+decodeFailures
                +" ended="+roundEnded+" endReason="+endReason;
    }
}
