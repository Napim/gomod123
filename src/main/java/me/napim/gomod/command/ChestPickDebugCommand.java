package me.napim.gomod.command;

import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.Main;
import me.napim.gomod.features.UselessItemOverlay;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/** Session-only switch for testing chest pick hints in singleplayer and other non-Blitz worlds. */
public final class ChestPickDebugCommand extends Command {
    public ChestPickDebugCommand() {
        super("chestpickdebug");
    }

    @Override
    public void handle(String[] args) {
        if (!GomodInternalFeatures.requireInternalCommand("chestpickdebug")) return;
        boolean next = !UselessItemOverlay.isChestPickHintDebugForceEnabled();
        UselessItemOverlay.setChestPickHintDebugForceEnabled(next);
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Chest pick hint solo debug: "
                + (next ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF")
                + EnumChatFormatting.GRAY + (next
                ? " — hints appear while a chest is open; no settings were changed."
                : " — normal Hypixel/Blitz gates restored."));
    }
}
