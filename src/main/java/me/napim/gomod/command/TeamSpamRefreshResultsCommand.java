package me.napim.gomod.command;

import java.util.Collections;
import java.util.List;
import me.napim.gomod.gui.TeamSpamGUI;
import me.napim.gomod.gui.TeamSpamGUI.RefreshChange;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.weavemc.loader.api.command.Command;

/**
 * Opens the team spam refresh results dialog. Triggered by the {@code [Check]} chat link after a refresh.
 */
public final class TeamSpamRefreshResultsCommand extends Command {

    public TeamSpamRefreshResultsCommand() {
        super("teamspamrefresh");
    }

    @Override
    public void handle(String[] args) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        mc.addScheduledTask(() -> {
            List<RefreshChange> changes = TeamSpamGUI.getLastRefreshChanges();
            if (changes == null) {
                changes = Collections.emptyList();
            }
            GuiScreen parent = mc.currentScreen;
            mc.displayGuiScreen(new TeamSpamGUI.RefreshResultsDialog(parent, changes));
        });
    }
}
