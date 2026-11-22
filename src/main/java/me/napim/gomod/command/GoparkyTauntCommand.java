package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.GoparkyBlitzReactions;
import me.napim.gomod.features.GoparkyTauntSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/** Live tuning and preview of Goparky's existing kill taunt. */
public final class GoparkyTauntCommand extends Command {
    public GoparkyTauntCommand() { super("goparkytaunt"); }

    @Override
    public void handle(String[] args) {
        try {
            GoparkyTauntSettings.Values values = GoparkyTauntSettings.get();
            if (args.length == 0 || (args.length == 1 && "show".equalsIgnoreCase(args[0]))) {
                show(values);
                message("Crouch speed is larger = faster. Min/max gap is the random pause between crouches in milliseconds.");
                message("Travel speed is blocks/second (native walking route is capped at 30 seconds); radius is blocks from the death spot.");
                message("Random player interactions: " + (values.randomPlayerInteractions ? "ON" : "OFF")
                        + " (visual arm/crouch gestures only).");
                message("/goparkytaunt set <crouchSpeed> <minGapMs> <maxGapMs> [travelBlocksPerSecond] [radiusBlocks]");
                message("Or: speed <value> | random <min> <max> | travel <blocksPerSecond> | radius <blocks> | interactions <on|off> | preview | reset");
            } else if (args.length == 1 && "preview".equalsIgnoreCase(args[0])) {
                if (GoparkyBlitzReactions.debugTaunt(Minecraft.getMinecraft(), "")) {
                    message("Taunt preview started for 5 seconds. " + values.fullCommand());
                } else message("Equip goparky and join a world first.");
            } else if (args.length == 1 && "reset".equalsIgnoreCase(args[0])) {
                GoparkyTauntSettings.Values defaults = GoparkyTauntSettings.DEFAULTS;
                show(GoparkyTauntSettings.save(defaults.speed, defaults.minimumGapMs, defaults.maximumGapMs,
                        defaults.travelBlocksPerSecond, defaults.tauntRadiusBlocks,
                        defaults.randomPlayerInteractions));
            } else if (args.length == 2 && "interactions".equalsIgnoreCase(args[0])) {
                String value = args[1].toLowerCase(java.util.Locale.ROOT);
                if (!"on".equals(value) && !"off".equals(value)) {
                    message("Use: /goparkytaunt interactions <on|off>");
                    return;
                }
                show(GoparkyTauntSettings.save(values.speed, values.minimumGapMs,
                        values.maximumGapMs, values.travelBlocksPerSecond,
                        values.tauntRadiusBlocks, "on".equals(value)));
            } else if ((args.length == 4 || args.length == 5 || args.length == 6)
                    && "set".equalsIgnoreCase(args[0])) {
                double travelSpeed = args.length >= 5 ? Double.parseDouble(args[4]) : values.travelBlocksPerSecond;
                double radius = args.length >= 6 ? Double.parseDouble(args[5]) : values.tauntRadiusBlocks;
                show(GoparkyTauntSettings.save(Double.parseDouble(args[1]),
                        Integer.parseInt(args[2]), Integer.parseInt(args[3]), travelSpeed, radius));
            } else if (args.length == 2 && "speed".equalsIgnoreCase(args[0])) {
                show(GoparkyTauntSettings.save(Double.parseDouble(args[1]), values.minimumGapMs,
                        values.maximumGapMs, values.travelBlocksPerSecond, values.tauntRadiusBlocks));
            } else if (args.length == 3 && "random".equalsIgnoreCase(args[0])) {
                show(GoparkyTauntSettings.save(values.speed, Integer.parseInt(args[1]),
                        Integer.parseInt(args[2]), values.travelBlocksPerSecond, values.tauntRadiusBlocks));
            } else if (args.length == 2 && "travel".equalsIgnoreCase(args[0])) {
                show(GoparkyTauntSettings.save(values.speed, values.minimumGapMs, values.maximumGapMs,
                        Double.parseDouble(args[1]), values.tauntRadiusBlocks));
            } else if (args.length == 2 && "radius".equalsIgnoreCase(args[0])) {
                show(GoparkyTauntSettings.save(values.speed, values.minimumGapMs, values.maximumGapMs,
                        values.travelBlocksPerSecond, Double.parseDouble(args[1])));
            } else message("Usage: /goparkytaunt [show|reset|preview|speed <value>|random <min> <max>|travel <blocksPerSecond>|radius <blocks>|interactions <on|off>|set ...]");
        } catch (IllegalArgumentException invalid) {
            message("Use crouch speed 0.25-4, gaps 0-2000ms (min <= max), travel speed 0.5-12 blocks/s, radius 0.25-4 blocks.");
        } catch (java.io.IOException error) {
            message("Could not save Goparky's taunt settings; previous settings kept.");
        }
    }

    private static void show(GoparkyTauntSettings.Values values) {
        message("Current configuration: " + values.fullCommand() + "; random player interactions="
                + (values.randomPlayerInteractions ? "ON" : "OFF") + " (takes effect on the next action)");
    }

    private static void message(String text) {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + text);
    }
}
