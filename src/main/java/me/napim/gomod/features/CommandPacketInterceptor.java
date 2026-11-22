package me.napim.gomod.features;

import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.InternalDcCommands;
import me.napim.gomod.command.NoHitDelayCommand;
import me.napim.gomod.command.NoHitDelayDebugCommand;
import me.napim.gomod.command.NoHitDelayHUDCommand;
import me.napim.gomod.util.CommandFallbackDispatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.weavemc.loader.api.event.PacketEvent;
import net.weavemc.loader.api.event.SubscribeEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Intercepts outgoing chat packets and dispatches gomod commands client-side.
 * This is a fallback when ChatSentEvent hooks are not firing.
 */
public class CommandPacketInterceptor {
    @SubscribeEvent
    public void onPacketSend(PacketEvent.Send event) {
        if (event == null || event.isCancelled()) {
            return;
        }
        Packet<?> packet = event.getPacket();
        if (packet == null) {
            return;
        }
        String className = packet.getClass().getName();
        // MCP + optional mapping variants; obfuscated jars may not end with this exact suffix.
        if (!isOutgoingChatPacket(className)) {
            return;
        }

        String message = extractMessage(packet);
        if (message == null || message.isEmpty()) {
            return;
        }

        if (CommandFallbackDispatcher.dispatchOutgoingChat(message) || dispatchBuiltinFallback(message)) {
            event.setCancelled(true);
            return;
        }

        if (!event.isCancelled()) {
            String trimmed = message.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("/") && DcMirrorBridge.shouldRedirectPlainChatOffServer()) {
                DcMirrorBridge.onOutgoingChat(message);
                event.setCancelled(true);
                try {
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc != null && mc.ingameGUI != null && mc.ingameGUI.getChatGUI() != null) {
                        mc.ingameGUI.getChatGUI().addToSentMessages(message);
                    }
                } catch (Throwable ignored) {}
                DcMirrorBridge.echoRedirectedLine(trimmed);
            }
        }
    }

    private static boolean isOutgoingChatPacket(String className) {
        if (className.endsWith("C01PacketChatMessage")) {
            return true;
        }
        if (className.contains("PacketChatMessage")) {
            return true;
        }
        // Some builds keep a short obfuscated simple name but remain in play.client
        if (className.contains("network.play.client") && className.contains("Chat")) {
            return true;
        }
        return false;
    }

    private String extractMessage(Object packet) {
        try {
            Method getter = packet.getClass().getDeclaredMethod("getMessage");
            getter.setAccessible(true);
            Object value = getter.invoke(packet);
            if (value instanceof String) {
                return (String) value;
            }
        } catch (Throwable ignored) {}

        // Try known field names (deobf/obf)
        String[] fields = new String[] { "message", "field_149440_a" };
        for (String fieldName : fields) {
            try {
                Field field = packet.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(packet);
                if (value instanceof String) {
                    return (String) value;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private boolean dispatchBuiltinFallback(String message) {
        if (message == null || message.isEmpty() || message.charAt(0) != '/') {
            return false;
        }
        String[] parts = message.substring(1).split("\\s+");
        if (parts.length == 0) {
            return false;
        }

        String commandName = parts[0].toLowerCase(Locale.ROOT);
        String[] args = new String[Math.max(0, parts.length - 1)];
        if (args.length > 0) {
            System.arraycopy(parts, 1, args, 0, args.length);
        }

        if (GomodInternalFeatures.isEnabled() && InternalDcCommands.dispatch(commandName, args)) {
            return true;
        }

        switch (commandName) {
            case "nohitdelay":
            case "nhd":
                new NoHitDelayCommand().handle(args);
                return true;
            case "nohitdelayhud":
            case "nhdui":
            case "nohitui":
                new NoHitDelayHUDCommand().handle(args);
                return true;
            case "nohitdelaydebug":
                new NoHitDelayDebugCommand().handle(args);
                return true;
            default:
                return false;
        }
    }
}
