package me.napim.gomod.features;

import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C01PacketChatMessage;
import net.weavemc.loader.api.event.*;

public final class UnbotheredTaunt {
    private static final TauntUseGate GATE=new TauntUseGate();
    private static String account="";
    private static long nextObserve;
    public static void clientTick(Minecraft mc) {
        if(mc==null || mc.thePlayer==null || mc.theWorld==null)return;
        String currentAccount=GomodSocialIdentity.profileUuidUndashed(mc);
        if(!currentAccount.equals(account)){account=currentAccount;GATE.clear();nextObserve=0;}
        long now=System.currentTimeMillis();if(now<nextObserve)return;nextObserve=now+500;
        boolean boundary=GameContext.isOnHypixel(mc) && (GameContext.isBlitzMatchMapPregameSidebar(mc)
                || GameContext.isBlitzGameOverSidebar(mc) || GameContext.isBlitzHypixelHubQueueSidebar(mc));
        GATE.observe(BlitzMatchIdentity.resolve(mc),boundary);
        if(boundary || !GameContext.isOnHypixel(mc)
                || !(GameContext.isBlitzGameActive(mc)||GameContext.isBlitzDeathmatchPhase(mc)))return;
        if(PlayerTarget.hasUsedTaunt(mc.thePlayer.getName()))GATE.markUsed();
        for(String line:HypixelScoreboardMapResolver.collectSidebarTextLines(mc)) {
            if(line.matches("(?i).*taunt\\s*:\\s*(used|wasted).*"))GATE.markUsed();
        }
    }
    /** Observes the gesture without consuming the click; true means one command was sent. */
    public static boolean handleLeftClick(Minecraft mc) {
        if(!ConfigManager.isUnbotheredTauntEnabled() || mc==null || mc.currentScreen!=null || mc.thePlayer==null
                || mc.theWorld==null || !mc.thePlayer.isSneaking() || !GameContext.isOnHypixel(mc)
                || !(GameContext.isBlitzGameActive(mc)||GameContext.isBlitzDeathmatchPhase(mc))
                || GameContext.isBlitzGameOverSidebar(mc) || GameContext.isBlitzMatchMapPregameSidebar(mc)
                || VisibleSpecs.isLocalEligibleForVisibleSpecsOpacity(mc))return false;
        ItemStack held=mc.thePlayer.getHeldItem();if(held==null || held.getItem()!=Items.compass)return false;
        nextObserve=0;
        clientTick(mc);
        if(!GATE.claim())return false;
        mc.thePlayer.sendChatMessage("/taunt");
        return true;
    }
    @SubscribeEvent public void onSend(PacketEvent.Send event) {
        if(!(event.getPacket() instanceof C01PacketChatMessage))return;
        String text=((C01PacketChatMessage)event.getPacket()).getMessage().trim();
        if(!text.equalsIgnoreCase("/taunt"))return;
        Minecraft mc=Minecraft.getMinecraft();
        Runnable mark=()->{if(mc.thePlayer!=null && (GameContext.isBlitzGameActive(mc)||GameContext.isBlitzDeathmatchPhase(mc))){clientTick(mc);GATE.markUsed();}};
        if(mc.isCallingFromMinecraftThread())mark.run();else mc.addScheduledTask(mark);
    }
}
