package me.napim.gomod.command;

import me.napim.gomod.features.LootCall;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Command to toggle LootCall - announces iron ingot/diamond pickups in chat.
 * Usage: /lootcall           — toggle on/off
 *        /lootcall everywhere — toggle everywhere mode (works in any game)
 */
public class LootCallCommand extends Command {

    public LootCallCommand() {
        super("lootcall");
    }

    @Override
    public void handle(String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("everywhere")) {
            me.napim.gomod.Main.sendMessage(LootCall.toggleEverywhere());
            return;
        }
        me.napim.gomod.Main.sendMessage(LootCall.toggle());
    }
}
