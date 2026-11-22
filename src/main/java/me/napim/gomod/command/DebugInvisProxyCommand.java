package me.napim.gomod.command;

import me.napim.gomod.Main;
import net.weavemc.loader.api.command.Command;
import net.minecraft.util.EnumChatFormatting;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Safe debug command that removes Invisibility from a player using reflection only,
 * to avoid early class loading side effects.
 */
public class DebugInvisProxyCommand extends Command {
    public DebugInvisProxyCommand() {
        super("debuginvis");
    }

    @Override
    public void handle(String[] args) {
        try {
            if (args.length == 0) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Usage: /debuginvis <player>");
                return;
            }

            String targetName = args[0];

            // Minecraft mc = Minecraft.getMinecraft();
            Class<?> mcCls = Class.forName("net.minecraft.client.Minecraft");
            Method getMc = mcCls.getMethod("getMinecraft");
            Object mc = getMc.invoke(null);

            // if (mc.theWorld == null) return;
            Field theWorldF = mcCls.getField("theWorld");
            Object world = theWorldF.get(mc);
            if (world == null) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Not in a world!");
                return;
            }

            // List<EntityPlayer> players = mc.theWorld.playerEntities;
            Field playerEntitiesF = world.getClass().getField("playerEntities");
            Object playersObj = playerEntitiesF.get(world);
            if (!(playersObj instanceof List)) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Unable to access player list");
                return;
            }

            @SuppressWarnings("unchecked")
            List<Object> players = (List<Object>) playersObj;
            Object targetPlayer = null;

            // Find by name via getName()
            for (Object p : players) {
                try {
                    Method getName = p.getClass().getMethod("getName");
                    Object name = getName.invoke(p);
                    if (name instanceof String && ((String) name).equalsIgnoreCase(targetName)) {
                        targetPlayer = p;
                        break;
                    }
                } catch (Throwable ignored) {}
            }

            if (targetPlayer == null) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Player not found: " + targetName);
                return;
            }

            // Debug info: isInvisible(), isInvisibleToPlayer(mc.thePlayer)
            boolean isInvisible = false;
            try {
                Method isInv = targetPlayer.getClass().getMethod("isInvisible");
                Object res = isInv.invoke(targetPlayer);
                isInvisible = (res instanceof Boolean) && (Boolean) res;
            } catch (Throwable ignored) {}

            boolean isInvisibleToViewer = false;
            try {
                Field thePlayerF = mcCls.getField("thePlayer");
                Object viewer = thePlayerF.get(mc);
                Method isInvTo = targetPlayer.getClass().getMethod("isInvisibleToPlayer", Class.forName("net.minecraft.entity.player.EntityPlayer"));
                Object res2 = isInvTo.invoke(targetPlayer, viewer);
                isInvisibleToViewer = (res2 instanceof Boolean) && (Boolean) res2;
            } catch (Throwable ignored) {}

            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Debug info for " + targetName + ":");
            Main.sendMessage(EnumChatFormatting.GRAY + "- isInvisible(): " + isInvisible);
            Main.sendMessage(EnumChatFormatting.GRAY + "- isInvisibleToPlayer(): " + isInvisibleToViewer);

            // Check Potion.invisibility and removePotionEffect(Potion.invisibility.id)
            boolean hadEffect = false;
            try {
                Class<?> potionCls = Class.forName("net.minecraft.potion.Potion");
                Field invisF = potionCls.getField("invisibility");
                Object invisPotion = invisF.get(null);
                Method isPotionActive = targetPlayer.getClass().getMethod("isPotionActive", potionCls);
                hadEffect = (Boolean) isPotionActive.invoke(targetPlayer, invisPotion);

                if (hadEffect) {
                    Field idF = potionCls.getField("id");
                    int invisId = idF.getInt(invisPotion);
                    Method removeEffect = targetPlayer.getClass().getMethod("removePotionEffect", int.class);
                    removeEffect.invoke(targetPlayer, invisId);
                    Main.sendMessage(EnumChatFormatting.GREEN + "Removed invisibility effect!");

                    // Report new state
                    boolean nowInvisible = false;
                    try {
                        Method isInv2 = targetPlayer.getClass().getMethod("isInvisible");
                        Object res3 = isInv2.invoke(targetPlayer);
                        nowInvisible = (res3 instanceof Boolean) && (Boolean) res3;
                    } catch (Throwable ignored) {}
                    Main.sendMessage(EnumChatFormatting.GRAY + "- After removal - isInvisible(): " + nowInvisible);
                } else {
                    Main.sendMessage(EnumChatFormatting.YELLOW + "No invisibility effect found.");
                }
            } catch (Throwable e) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Error: " + e.getClass().getSimpleName());
            }
        } catch (Throwable e) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Internal error running /debuginvis");
        }
    }
}


