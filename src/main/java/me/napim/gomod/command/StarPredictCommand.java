package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.ConfigManager;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

public final class StarPredictCommand extends Command {
    public StarPredictCommand() {
        super("starpredict");
    }

    @Override
    public void handle(String[] args) {
        boolean enabled = !ConfigManager.isStarPredictEnabled();
        ConfigManager.setStarPredictEnabled(enabled);
        Main.sendMessage(Main.CHAT_PREFIX + "Blitz Star random picks "
                + (enabled ? EnumChatFormatting.GREEN + "enabled" : EnumChatFormatting.RED + "disabled"));
    }
}
