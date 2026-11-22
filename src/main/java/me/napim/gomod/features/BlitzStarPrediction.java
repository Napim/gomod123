package me.napim.gomod.features;

import com.mojang.authlib.GameProfile;
import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class BlitzStarPrediction {
    private static WeakReference<ContainerChest> menu = new WeakReference<>(null);
    private static UUID roulettePick;
    private static UUID switcherooPick;

    private BlitzStarPrediction() {
    }

    public static List<String> addToTooltip(GuiScreen screen, ItemStack stack, List<String> tooltip) {
        if (!(screen instanceof GuiChest) || !(((GuiChest) screen).inventorySlots instanceof ContainerChest)) {
            return tooltip;
        }
        boolean roulette = stack.getItem() == Items.diamond;
        if (!roulette && stack.getItem() != Items.leather_boots) {
            return tooltip;
        }
        String name = EnumChatFormatting.getTextWithoutFormattingCodes(stack.getDisplayName());
        if (roulette ? !"Roulette".equalsIgnoreCase(name)
                : !("Switcheroo".equalsIgnoreCase(name) || "Suitcheroo".equalsIgnoreCase(name))) {
            return tooltip;
        }

        Minecraft mc = Minecraft.getMinecraft();
        ContainerChest chest = (ContainerChest) ((GuiChest) screen).inventorySlots;
        if (screen != mc.currentScreen || mc.thePlayer == null || mc.theWorld == null || mc.getNetHandler() == null
                || !GameContext.isBlitzGameActive(mc) || !containsStack(chest.getLowerChestInventory(), stack)) {
            return tooltip;
        }
        // Recognize the activation menu by its actionable power tooltip, including when its title varies.
        int clickLine = -1;
        boolean instant = false;
        for (int i = 1; i < tooltip.size(); i++) {
            String line = EnumChatFormatting.getTextWithoutFormattingCodes(tooltip.get(i));
            if ("Activates instantly!".equals(line)) {
                instant = true;
            } else if ("Click to use!".equals(line)) {
                clickLine = i;
            }
        }
        if (!instant || clickLine < 0) {
            return tooltip;
        }

        // Each server open creates a new container, even when it reuses the same window ID.
        if (menu.get() != chest) {
            menu = new WeakReference<>(chest);
            roulettePick = null;
            switcherooPick = null;
        }
        UUID pick = roulette ? roulettePick : switcherooPick;
        GameProfile target = pick == null ? null : eligibleProfile(mc, mc.getNetHandler().getPlayerInfo(pick), roulette);
        if (target == null) {
            target = pickRandomPlayer(mc, roulette);
            if (roulette) {
                roulettePick = target == null ? null : target.getId();
            } else {
                switcherooPick = target == null ? null : target.getId();
            }
        }
        if (target == null) {
            return tooltip;
        }
        List<String> highlighted = new ArrayList<>(tooltip);
        highlighted.add(clickLine, EnumChatFormatting.GRAY + "Random pick "
                + EnumChatFormatting.YELLOW + EnumChatFormatting.BOLD + target.getName());
        return highlighted;
    }

    private static boolean containsStack(IInventory inventory, ItemStack stack) {
        for (int i = 0; i < inventory.getSizeInventory(); i++) {
            if (inventory.getStackInSlot(i) == stack) {
                return true;
            }
        }
        return false;
    }

    private static GameProfile pickRandomPlayer(Minecraft mc, boolean roulette) {
        List<GameProfile> candidates = new ArrayList<>();
        for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
            GameProfile candidate = eligibleProfile(mc, info, roulette);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        return candidates.isEmpty() ? null : candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    private static GameProfile eligibleProfile(Minecraft mc, NetworkPlayerInfo info, boolean roulette) {
        if (info == null || info.getGameType() == null || KillCounter.isSpectatorRosterTabRow(info)) {
            return null;
        }
        GameProfile profile = RemoteDisguiseManager.getSourceProfile(info);
        if (profile == null || profile.getId() == null || profile.getName() == null || profile.getName().isEmpty()) {
            return null;
        }
        if (!roulette && (profile.getId().equals(mc.thePlayer.getUniqueID())
                || profile.getName().equalsIgnoreCase(mc.thePlayer.getName()))) {
            return null;
        }
        EntityPlayer entity = mc.theWorld.getPlayerEntityByUUID(profile.getId());
        return entity != null && (entity.isDead || entity.getHealth() <= 0 || entity.isSpectator()) ? null : profile;
    }
}
