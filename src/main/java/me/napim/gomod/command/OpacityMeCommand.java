package me.napim.gomod.command;

import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.features.SelfOpacity;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import static me.napim.gomod.Main.CHAT_PREFIX;
import static me.napim.gomod.Main.sendMessage;

/**
 * /opacityme [value]
 *
 * Without arguments: toggles self-opacity ON / OFF.
 * With a number:
 *   0.0–1.0  → treated as a direct alpha value
 *   1–100    → treated as a percentage (e.g. 30 → 0.30)
 *
 * Debug (enchant / blend issues):
 *   /opacityme debug           → toggle live chat + stderr trace
 *   /opacityme debug on|off   → set trace
 *   /opacityme debug snapshot → one-shot status (chat + stderr)
 *
 * Deferred flush (wrong F5 / “giant feet” while self-opacity draws):
 *   /opacityme flushdebug      → toggle stderr {@code [gomod][flushDiag]} lines each flush (site + GL snapshot)
 *   /opacityme flushdebug on|off
 *   /opacityme inventorydebug [on|off|snapshot] → inventory mannequin GL diagnostics
 *   JVM: {@code -Dgomod.debugSelfOpacityFlush=true} (always on; no chat toggle needed)
 *
 * Examples:
 *   /opacityme          → toggle
 *   /opacityme 0.3      → 30 % opacity
 *   /opacityme 30       → 30 % opacity
 */
public class OpacityMeCommand extends Command {

    public OpacityMeCommand() {
        super("opacityme");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            SelfOpacity.setEnabled(!SelfOpacity.isEnabled());
            return;
        }

        if (args.length >= 1 && "flushdebug".equalsIgnoreCase(args[0])) {
            if (!GomodInternalFeatures.requireInternalCommand("opacityme flushdebug")) return;
            if (args.length >= 2 && "on".equalsIgnoreCase(args[1])) {
                SelfOpacity.setFlushDiagnosticDebugEnabled(true);
                sendMessage(CHAT_PREFIX + EnumChatFormatting.GREEN
                        + "Self opacity flush diagnostics ON → check latest.log for [gomod][flushDiag]");
                return;
            }
            if (args.length >= 2 && "off".equalsIgnoreCase(args[1])) {
                SelfOpacity.setFlushDiagnosticDebugEnabled(false);
                sendMessage(CHAT_PREFIX + EnumChatFormatting.RED + "Self opacity flush diagnostics OFF.");
                return;
            }
            boolean on = SelfOpacity.toggleFlushDiagnosticDebugEnabled();
            sendMessage(CHAT_PREFIX + "Self opacity flush diagnostics "
                    + (on ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF")
                    + EnumChatFormatting.GRAY + " — latest.log lines prefixed [gomod][flushDiag]");
            return;
        }

        if (args.length >= 1 && "inventorydebug".equalsIgnoreCase(args[0])) {
            if (!GomodInternalFeatures.requireInternalCommand("opacityme inventorydebug")) return;
            if (args.length >= 2 && "snapshot".equalsIgnoreCase(args[1])) {
                SelfOpacity.sendGuiInventoryDiagnosticSnapshot();
                return;
            }
            if (args.length >= 2 && "on".equalsIgnoreCase(args[1])) {
                SelfOpacity.setGuiInventoryDiagnosticEnabled(true);
            } else if (args.length >= 2 && "off".equalsIgnoreCase(args[1])) {
                SelfOpacity.setGuiInventoryDiagnosticEnabled(false);
            } else {
                SelfOpacity.toggleGuiInventoryDiagnosticEnabled();
            }
            boolean on = SelfOpacity.isGuiInventoryDiagnosticEnabled();
            sendMessage(CHAT_PREFIX + "Inventory opacity diagnostics "
                    + (on ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF")
                    + (on ? EnumChatFormatting.GRAY + " — open inventory for 2 seconds, then close it."
                          : EnumChatFormatting.GRAY + "."));
            return;
        }

        if (args.length >= 1 && "debug".equalsIgnoreCase(args[0])) {
            if (!GomodInternalFeatures.requireInternalCommand("opacityme debug")) return;
            if (args.length >= 2 && "snapshot".equalsIgnoreCase(args[1])) {
                SelfOpacity.sendSelfOpacityDebugSnapshot();
                sendMessage(CHAT_PREFIX + EnumChatFormatting.GRAY + "Also check latest.log for [gomod][opacityDbg] lines.");
                return;
            }
            if (args.length >= 2 && "on".equalsIgnoreCase(args[1])) {
                SelfOpacity.setSelfOpacityDebugEnabled(true);
                sendMessage(CHAT_PREFIX + EnumChatFormatting.GREEN + "Self opacity debug ON (chat + log). /opacityme debug off to stop.");
                return;
            }
            if (args.length >= 2 && "off".equalsIgnoreCase(args[1])) {
                SelfOpacity.setSelfOpacityDebugEnabled(false);
                sendMessage(CHAT_PREFIX + EnumChatFormatting.RED + "Self opacity debug OFF.");
                return;
            }
            boolean on = SelfOpacity.toggleSelfOpacityDebugEnabled();
            sendMessage(CHAT_PREFIX + "Self opacity debug "
                    + (on ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF")
                    + EnumChatFormatting.GRAY + " — /opacityme debug snapshot for one-shot");
            return;
        }

        try {
            float value = Float.parseFloat(args[0]);

            // Values above 1 are interpreted as a 0–100 percentage
            if (value > 1.0f) {
                value = value / 100.0f;
            }

            // Clamp to the supported range (0.05 avoids fully invisible)
            value = Math.max(0.05f, Math.min(1.0f, value));

            SelfOpacity.setAlpha(value);

            // Auto-enable when a value is given
            if (!SelfOpacity.isEnabled()) {
                SelfOpacity.setEnabled(true);
            } else {
                int pct = Math.round(value * 100);
                sendMessage(CHAT_PREFIX + "Self opacity: "
                        + EnumChatFormatting.YELLOW + pct + "%"
                        + EnumChatFormatting.GRAY + " (alpha "
                        + EnumChatFormatting.WHITE + String.format("%.2f", value)
                        + EnumChatFormatting.GRAY + ")");
            }
        } catch (NumberFormatException e) {
            String usage = GomodInternalFeatures.isEnabled()
                    ? "Usage: /opacityme [0.0–1.0 or 5–100] | debug | flushdebug | inventorydebug [on|off|snapshot]"
                    : "Usage: /opacityme [0.0–1.0 or 5–100]";
            sendMessage(CHAT_PREFIX + EnumChatFormatting.RED + usage);
        }
    }
}
