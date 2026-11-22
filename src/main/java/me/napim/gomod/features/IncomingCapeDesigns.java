package me.napim.gomod.features;

import com.google.gson.JsonObject;
import me.napim.gomod.Main;
import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.gui.GomodGuiOpenHelper;
import me.napim.gomod.gui.MinecraftCapeSelectorGUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Incoming gomod cape-design shares, including manual accept/decline state. */
public final class IncomingCapeDesigns {
    private static final Map<String, PendingCape> pending = new LinkedHashMap<String, PendingCape>();
    private static String lastSavedCapeId;

    private IncomingCapeDesigns() {}

    public static void receive(String from, String label, String imageBase64) {
        String sender = from == null ? "" : from.trim();
        String capeLabel = GomodCapeShareHistory.cleanLabel(label);
        if (sender.isEmpty() || imageBase64 == null || imageBase64.trim().isEmpty()) {
            return;
        }
        if (ConfigManager.isAutoAcceptIncomingCapes()) {
            String savedId = save(sender, capeLabel, imageBase64, true);
            if (savedId != null) {
                GomodCapeShareHistory.recordReceived("", sender, capeLabel, imageBase64, true, savedId);
                IncomingCapeDesignOverlay.showAccepted(sender, capeLabel, imageBase64);
                notifySenderAccepted(sender, capeLabel, imageBase64, true);
            } else {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                        + "Failed to save cape design from " + EnumChatFormatting.AQUA + sender + ".");
            }
            return;
        }

        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        synchronized (pending) {
            pending.put(id, new PendingCape(id, sender, capeLabel, imageBase64));
            while (pending.size() > 12) {
                String first = pending.keySet().iterator().next();
                pending.remove(first);
            }
        }
        GomodCapeShareHistory.recordReceived(id, sender, capeLabel, imageBase64, false, "");
        IncomingCapeDesignOverlay.showPending(id, sender, capeLabel, imageBase64);
    }

    public static boolean accept(String id) {
        PendingCape p = remove(id);
        if (p == null) {
            GomodCapeShareHistory.Entry accepted = GomodCapeShareHistory.acceptPending(id);
            if (accepted != null) {
                sendSavedChat(accepted.peer, false);
                notifySenderAccepted(accepted.peer, accepted.label, accepted.imageBase64, false);
                return true;
            }
            return false;
        }
        String savedId = save(p.from, p.label, p.imageBase64, false);
        if (savedId != null) {
            GomodCapeShareHistory.markAccepted(p.id, savedId);
            notifySenderAccepted(p.from, p.label, p.imageBase64, false);
        }
        return savedId != null;
    }

    public static boolean decline(String id) {
        return remove(id) != null;
    }

    public static int pendingCount() {
        synchronized (pending) {
            return pending.size();
        }
    }

    public static String firstPendingSender() {
        synchronized (pending) {
            for (PendingCape p : pending.values()) {
                if (p != null && p.from != null && !p.from.trim().isEmpty()) {
                    return p.from.trim();
                }
            }
        }
        return null;
    }

    public static void openCapeGui() {
        if (!GomodInternalFeatures.isStandaloneCapeEnabled()) {
            return;
        }
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        // Toast clicks are detected from the raw mouse state. Wait for that physical click to be released before
        // opening the selector, otherwise the new GuiScreen can inherit a stuck press and stop receiving clicks/ESC.
        GomodGuiOpenHelper.openCleanAfterMouseRelease(
                mc, new MinecraftCapeSelectorGUI(
                        mc.currentScreen instanceof GuiMainMenu ? mc.currentScreen : null,
                        MinecraftCapeSelectorGUI.Mode.SELF));
    }

    private static PendingCape remove(String id) {
        if (id == null || id.trim().isEmpty()) {
            return null;
        }
        synchronized (pending) {
            return pending.remove(id);
        }
    }

    private static String save(String from, String label, String imageBase64, boolean automatic) {
        String cleanLabel = GomodCapeShareHistory.cleanLabel(label);
        String savedId = PlayerCustomCapes.importSharedFromFriend(from, cleanLabel, imageBase64);
        if (savedId != null) {
            lastSavedCapeId = savedId;
            sendSavedChat(from, automatic);
        }
        return savedId;
    }

    private static void sendSavedChat(String from, boolean automatic) {
        String action = automatic ? "Auto-accepted cape design from " : "Accepted cape design from ";
        ChatComponentText root = new ChatComponentText(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                + action + EnumChatFormatting.AQUA + from + EnumChatFormatting.GREEN + ". ");
        ChatComponentText view = new ChatComponentText(EnumChatFormatting.YELLOW + "[View]");
        ChatStyle style = new ChatStyle();
        style.setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/gomodviewcapes"));
        style.setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ChatComponentText(EnumChatFormatting.YELLOW + "Open Minecraft capes")));
        view.setChatStyle(style);
        root.appendSibling(view);
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(root);
        } else {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                    + action + EnumChatFormatting.AQUA + from + EnumChatFormatting.GREEN + ". "
                    + EnumChatFormatting.YELLOW + "[View]");
        }
    }

    private static void notifySenderAccepted(String from, String label, String imageBase64, boolean automatic) {
        final String peer = from == null ? "" : from.trim();
        if (peer.isEmpty()) {
            return;
        }
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return;
        }
        final String sender = mc.thePlayer.getName();
        if (peer.equalsIgnoreCase(sender)) {
            return;
        }
        final String cleanLabel = GomodCapeShareHistory.cleanLabel(label);
        final String action = automatic ? "Auto-accepted your cape" : "Accepted your cape";
        final String styledMessage = EnumChatFormatting.ITALIC + action + ": "
                + EnumChatFormatting.LIGHT_PURPLE + EnumChatFormatting.ITALIC + cleanLabel;
        new Thread(new Runnable() {
            @Override
            public void run() {
                JsonObject payload = new JsonObject();
                payload.addProperty("type", "cape_accept_receipt");
                payload.addProperty("channel", ConfigManager.getGlobalChatChannel());
                payload.addProperty("sender", sender);
                payload.addProperty("target", peer);
                payload.addProperty("message", styledMessage);
                payload.addProperty("capeLabel", cleanLabel);
                if (imageBase64 != null && !imageBase64.trim().isEmpty()) {
                    payload.addProperty("capeImageBase64", imageBase64.trim());
                }
                payload.addProperty("automatic", automatic);
                GomodSocialIdentity.attachSender(payload);
                try {
                    ScreenshotShareService.postRelayPayload(payload);
                } catch (Exception ignored) {
                }
            }
        }, "GomodCapeAcceptReceipt").start();
    }

    public static String getLastSavedCapeId() {
        return lastSavedCapeId;
    }

    public static final class PendingCape {
        public final String id;
        public final String from;
        public final String label;
        public final String imageBase64;

        PendingCape(String id, String from, String label, String imageBase64) {
            this.id = id;
            this.from = from;
            this.label = label;
            this.imageBase64 = imageBase64;
        }
    }
}
