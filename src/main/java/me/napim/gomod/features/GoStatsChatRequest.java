package me.napim.gomod.features;

import java.util.Locale;

/** Parses a locally sent GoStats mention without depending on Minecraft classes. */
final class GoStatsChatRequest {
    final String question;
    final boolean privateReply;

    private GoStatsChatRequest(String question, boolean privateReply) {
        this.question = question;
        this.privateReply = privateReply;
    }

    static GoStatsChatRequest parseOutgoing(String outgoing) {
        if (outgoing == null) return null;
        String message = outgoing.trim();
        if (message.isEmpty() || message.toLowerCase(Locale.ROOT).startsWith("<gostats123>")) return null;

        boolean privateReply = false;
        if (message.startsWith("/")) {
            int commandEnd = message.indexOf(' ');
            String command = (commandEnd < 0 ? message : message.substring(0, commandEnd)).toLowerCase(Locale.ROOT);
            String arguments = commandEnd < 0 ? "" : message.substring(commandEnd + 1).trim();
            switch (command) {
                case "/msg":
                case "/message":
                case "/tell":
                case "/whisper":
                case "/w":
                case "/pm":
                    int recipientEnd = arguments.indexOf(' ');
                    if (recipientEnd < 1) return null;
                    message = arguments.substring(recipientEnd + 1).trim();
                    privateReply = true;
                    break;
                case "/shout":
                    message = arguments;
                    break;
                case "/r":
                case "/reply":
                    message = arguments;
                    privateReply = true;
                    break;
                default:
                    return null;
            }
        }

        String lowerMessage = message.toLowerCase(Locale.ROOT);
        if (lowerMessage.indexOf("@gostats123") < 0) return null;
        String question = message.replaceAll("(?i)@gostats123", "").trim();
        return new GoStatsChatRequest(question, privateReply);
    }
}
