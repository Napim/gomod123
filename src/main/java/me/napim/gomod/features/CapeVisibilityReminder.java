package me.napim.gomod.features;

import me.napim.gomod.Main;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import java.util.HashMap;
import java.util.Map;

/** Reminds players when a selected cape conflicts with their local or peer visibility settings. */
public final class CapeVisibilityReminder {
    private static final long NOTICE_COOLDOWN_MS = 5L * 60L * 1000L;
    private static final Map<String, Long> LAST_NOTICE_MS = new HashMap<String, Long>();

    private CapeVisibilityReminder() {
    }

    /** Called when a Lunar Locker selection resolver adds a cloak while its UI is open. */
    public static void onLunarCapeSelected() {
        if (!GomodFixes.isWeaveRuntime()) {
            return;
        }

        boolean localGomodCapeOverridesLunar = PlayerStandaloneCape.shouldApplyLocalCapeOverride()
                && PlayerStandaloneCape.getCapeLocation() != null;
        boolean peerGomodCapeOverridesLunar = !GomodPeerDisguise.isSharingLocalDisguise()
                && PlayerStandaloneCape.isActive()
                && ConfigManager.isPlayerStandaloneCapeBroadcastToPeers()
                && PlayerStandaloneCape.getCapeLocationForPeerSharing() != null;
        boolean lunarSharingUnavailable = !ConfigManager.isLunarTweaksEnabled()
                || !ConfigManager.isLunarTweaksShowToOtherPlayers();

        if (!localGomodCapeOverridesLunar && !peerGomodCapeOverridesLunar && !lunarSharingUnavailable) {
            return;
        }

        StringBuilder message = new StringBuilder("Lunar cape selected. ");
        if (localGomodCapeOverridesLunar) {
            message.append("In Disguise cape options, set \"You see\" to Default cape to see it locally. ");
        }
        if (peerGomodCapeOverridesLunar) {
            message.append("Set \"gomod players see\" to Default cape so gomod players see your Lunar cape. ");
        }
        if (lunarSharingUnavailable) {
            message.append("Enable Lunar FullAccess sharing with gomod players in Lunar Tweaks.");
        }
        sendReminder("lunar-cape", message.toString());
    }

    /** Called after a cape from the standalone Gomod picker is selected. */
    public static void onStandaloneGomodCapeSelected(LocalDisguise.CapeOptionInfo option) {
        if (option == null || option.createEntry || PlayerStandaloneCape.isNonCapeCatalogKey(option.optionKey)) {
            return;
        }

        boolean localDefault = !ConfigManager.isPlayerStandaloneCapePreferGomod();
        boolean peersDefault = !ConfigManager.isPlayerStandaloneCapeBroadcastToPeers();
        if (!localDefault && !peersDefault) {
            return;
        }

        String message;
        if (localDefault && peersDefault) {
            message = "Reminder: In Disguise > Cape options, your settings are "
                    + "\"You see: Default cape\", \"gomod players see: Default cape\".";
        } else if (localDefault) {
            message = "Reminder: In Disguise > Cape options, your setting is \"You see: Default cape\".";
        } else {
            message = "Reminder: In Disguise > Cape options, your setting is \"gomod players see: Default cape\".";
        }
        sendReminder("standalone-gomod-cape", message);
    }

    /** A cape selected for a local-only disguise is not advertised to Gomod peers. */
    public static void onLocalDisguiseCapeSelected(LocalDisguise.CapeOptionInfo option) {
        if (option == null || option.createEntry || "gomod:none".equals(option.optionKey)
                || LocalDisguise.isNoCapeSelected() || GomodPeerDisguise.isSharingLocalDisguise()) {
            return;
        }
        sendReminder("local-disguise-cape",
                "This cape is selected for your local disguise. Use /disguisegomod or /disguisefullgomod "
                        + "if you want gomod players to see the disguise.");
    }

    private static void sendReminder(String key, String message) {
        long now = System.currentTimeMillis();
        synchronized (LAST_NOTICE_MS) {
            Long last = LAST_NOTICE_MS.get(key);
            if (last != null && now - last.longValue() < NOTICE_COOLDOWN_MS) {
                return;
            }
            LAST_NOTICE_MS.put(key, Long.valueOf(now));
        }
        IChatComponent component = new ChatComponentText(Main.CHAT_PREFIX);
        ChatComponentText reminder = new ChatComponentText(message);
        reminder.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.YELLOW));
        component.appendSibling(reminder);
        Main.sendChatMessage(component);
    }
}
