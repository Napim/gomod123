package me.napim.gomod.command;

import me.napim.gomod.Main;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Items;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import net.weavemc.loader.api.command.Command;

import java.util.ArrayList;
import java.util.List;

/**
 * /gogive &lt;minecraft:item&gt; [count] [meta]
 * Optional legacy: /gogive @p &lt;id&gt; [count] [meta] — target is always the local player.
 */
public class GogiveCommand extends Command {
    public GogiveCommand() {
        super("gogive");
    }

    @Override
    public void handle(String[] args) {
        try {
            if (Minecraft.getMinecraft().thePlayer == null) return;

            if (args.length < 1) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                        + "Usage: /gogive <minecraft:item>|potion:... [count] [meta]");
                return;
            }

            String id;
            int count = 64;
            int meta = 0;
            if (args.length >= 2 && ("@p".equalsIgnoreCase(args[0]) || "@s".equalsIgnoreCase(args[0]))) {
                id = args[1];
                if (args.length >= 3) {
                    try { count = Math.max(1, Math.min(64, Integer.parseInt(args[2]))); } catch (Exception ignored) {}
                }
                if (args.length >= 4) {
                    try { meta = Math.max(0, Integer.parseInt(args[3])); } catch (Exception ignored) {}
                }
            } else {
                id = args[0];
                if (args.length >= 2) {
                    try { count = Math.max(1, Math.min(64, Integer.parseInt(args[1]))); } catch (Exception ignored) {}
                }
                if (args.length >= 3) {
                    try { meta = Math.max(0, Integer.parseInt(args[2])); } catch (Exception ignored) {}
                }
            }

            ItemStack stack;
            if (id.toLowerCase().startsWith("potion:")) {
                stack = createPotionFromSpec(id, count);
            } else {
                stack = createStackFromId(id, count, meta);
            }
            if (stack == null || stack.getItem() == null) {
                List<String> suggestions = suggestIds(id);
                if (suggestions.isEmpty()) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Unknown item: " + id);
                } else {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Unknown item: " + id + EnumChatFormatting.YELLOW + ". Did you mean:");
                    StringBuilder line = new StringBuilder();
                    int shown = 0;
                    for (String s : suggestions) {
                        if (shown++ >= 8) break;
                        if (line.length() > 0) line.append(EnumChatFormatting.GRAY).append(", ");
                        line.append(EnumChatFormatting.AQUA).append(s);
                    }
                    Main.sendMessage(Main.CHAT_PREFIX + line.toString());
                }
                return;
            }

            try {
                if (stack.getTagCompound() == null) stack.setTagCompound(new net.minecraft.nbt.NBTTagCompound());
                stack.getTagCompound().setBoolean("gomod_client", true);
            } catch (Throwable ignored) {}
            Minecraft.getMinecraft().thePlayer.inventory.addItemStackToInventory(stack);
        } catch (Throwable t) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Error: " + t.getMessage());
        }
    }

    private static ItemStack createStackFromId(String id, int count, int meta) {
        if (id == null || id.trim().isEmpty()) return null;
        String cleaned = id.trim();

        Item item = Item.getByNameOrId(cleaned);
        if (item != null) {
            return new ItemStack(item, count, meta);
        }

        Block block = Block.getBlockFromName(cleaned);
        if (block != null) {
            Item blockItem = Item.getItemFromBlock(block);
            if (blockItem != null) {
                return new ItemStack(blockItem, count, meta);
            }
        }

        if (!cleaned.contains(":")) {
            return createStackFromId("minecraft:" + cleaned, count, meta);
        }
        return null;
    }

    private static List<String> suggestIds(String prefix) {
        List<String> out = new ArrayList<>();
        if (prefix == null) return out;
        String pfx = prefix.toLowerCase();
        try {
            for (Object keyObj : Item.itemRegistry.getKeys()) {
                ResourceLocation rl = (ResourceLocation) keyObj;
                String sid = rl.toString();
                if (sid.toLowerCase().startsWith(pfx)) out.add(sid);
            }
            for (Object keyObj : Block.blockRegistry.getKeys()) {
                ResourceLocation rl = (ResourceLocation) keyObj;
                String sid = rl.toString();
                if (sid.toLowerCase().startsWith(pfx) && !out.contains(sid)) out.add(sid);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static ItemStack createPotionFromSpec(String spec, int count) {
        try {
            String[] parts = spec.split(":");
            if (parts.length < 4) return null;
            String effectName = parts[1].toLowerCase();
            int duration = Math.max(1, Integer.parseInt(parts[2])) * 20;
            int amplifier = Math.max(0, Integer.parseInt(parts[3]));

            boolean splash = false;
            if (effectName.startsWith("splash.")) {
                splash = true;
                effectName = effectName.substring("splash.".length());
            }

            int potionMeta = splash ? 16384 : 0;
            ItemStack potion = new ItemStack(Items.potionitem, Math.max(1, count), potionMeta);

            net.minecraft.potion.Potion effect = null;
            for (net.minecraft.potion.Potion p : net.minecraft.potion.Potion.potionTypes) {
                if (p == null) continue;
                String key = p.getName();
                if (key != null) {
                    String simple = key.toLowerCase().replace("potion.", "");
                    if (simple.equals(effectName) || simple.contains(effectName)) { effect = p; break; }
                }
            }
            if (effect == null) {
                String e = effectName;
                if (e.equals("speed")) effect = net.minecraft.potion.Potion.moveSpeed;
                else if (e.equals("slowness")) effect = net.minecraft.potion.Potion.moveSlowdown;
                else if (e.equals("haste")) effect = net.minecraft.potion.Potion.digSpeed;
                else if (e.equals("mining_fatigue") || e.equals("fatigue")) effect = net.minecraft.potion.Potion.digSlowdown;
                else if (e.equals("strength")) effect = net.minecraft.potion.Potion.damageBoost;
                else if (e.equals("instant_health") || e.equals("heal")) effect = net.minecraft.potion.Potion.heal;
                else if (e.equals("instant_damage") || e.equals("harm")) effect = net.minecraft.potion.Potion.harm;
                else if (e.equals("jump_boost") || e.equals("jump")) effect = net.minecraft.potion.Potion.jump;
                else if (e.equals("regeneration") || e.equals("regen")) effect = net.minecraft.potion.Potion.regeneration;
                else if (e.equals("resistance")) effect = net.minecraft.potion.Potion.resistance;
                else if (e.equals("fire_resistance") || e.equals("fire_res")) effect = net.minecraft.potion.Potion.fireResistance;
                else if (e.equals("water_breathing") || e.equals("waterbreathing")) effect = net.minecraft.potion.Potion.waterBreathing;
                else if (e.equals("invisibility") || e.equals("invis")) effect = net.minecraft.potion.Potion.invisibility;
                else if (e.equals("blindness")) effect = net.minecraft.potion.Potion.blindness;
                else if (e.equals("night_vision") || e.equals("nightvision")) effect = net.minecraft.potion.Potion.nightVision;
                else if (e.equals("hunger")) effect = net.minecraft.potion.Potion.hunger;
                else if (e.equals("weakness")) effect = net.minecraft.potion.Potion.weakness;
                else if (e.equals("poison")) effect = net.minecraft.potion.Potion.poison;
                else if (e.equals("wither")) effect = net.minecraft.potion.Potion.wither;
                else if (e.equals("health_boost")) effect = net.minecraft.potion.Potion.healthBoost;
                else if (e.equals("absorption")) effect = net.minecraft.potion.Potion.absorption;
                else if (e.equals("saturation")) effect = net.minecraft.potion.Potion.saturation;
            }
            if (effect == null) return null;

            NBTTagCompound tag = new NBTTagCompound();
            NBTTagList list = new NBTTagList();
            NBTTagCompound eff = new NBTTagCompound();
            eff.setByte("Id", (byte) effect.getId());
            eff.setByte("Amplifier", (byte) amplifier);
            eff.setInteger("Duration", duration);
            eff.setBoolean("Ambient", false);
            eff.setBoolean("ShowParticles", true);
            list.appendTag(eff);
            tag.setTag("CustomPotionEffects", list);
            potion.setTagCompound(tag);
            return potion;
        } catch (Throwable t) {
            return null;
        }
    }
}
