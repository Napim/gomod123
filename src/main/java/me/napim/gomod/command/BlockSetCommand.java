package me.napim.gomod.command;

import me.napim.gomod.Main;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import net.weavemc.loader.api.command.Command;

import java.util.ArrayList;
import java.util.List;

/**
 * /blockset <block> [meta]
 * Places the named block at the two positions the player occupies (feet + head), client-side.
 * Use /blockset air to clear those two positions.
 */
public class BlockSetCommand extends Command {
    public BlockSetCommand() {
        super("blockset");
    }

    @Override
    public void handle(String[] args) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) return;

        if (args.length < 1) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Usage: /blockset <block> [meta]");
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Accepts block names (stone, minecraft:glass, 35:4) or 'air' to clear.");
            return;
        }

        String name = args[0];
        int meta = 0;
        if (args.length >= 2) {
            try { meta = Math.max(0, Math.min(15, Integer.parseInt(args[1]))); } catch (Exception ignored) {}
        }

        // Also accept "id:meta" shorthand in args[0], e.g. /blockset 35:4
        if (name.contains(":") && !name.contains("/") && !name.startsWith("minecraft") && !name.contains(".")) {
            String[] parts = name.split(":", 2);
            try {
                meta = Math.max(0, Math.min(15, Integer.parseInt(parts[1])));
                name = parts[0];
            } catch (NumberFormatException ignored) {}
        }

        Block block = resolveBlock(name);
        if (block == null) {
            List<String> suggestions = suggestBlockIds(name);
            if (suggestions.isEmpty()) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Unknown block: " + name);
            } else {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Unknown block: " + name
                        + EnumChatFormatting.YELLOW + ". Did you mean:");
                StringBuilder sb = new StringBuilder();
                int shown = 0;
                for (String s : suggestions) {
                    if (shown++ >= 6) break;
                    if (sb.length() > 0) sb.append(EnumChatFormatting.GRAY).append(", ");
                    sb.append(EnumChatFormatting.AQUA).append(s);
                }
                Main.sendMessage(Main.CHAT_PREFIX + sb.toString());
            }
            return;
        }

        IBlockState state;
        try {
            state = block.getStateFromMeta(meta);
        } catch (Throwable t) {
            state = block.getDefaultState();
        }

        BlockPos feet = mc.thePlayer.getPosition();
        BlockPos head = feet.up();

        placeBlock(mc, feet, state);
        placeBlock(mc, head, state);

        String blockId = getBlockId(block);
        String metaStr = (meta > 0) ? ":" + meta : "";
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Set " + EnumChatFormatting.AQUA + blockId + metaStr
                + EnumChatFormatting.GREEN + " at your feet and head" + EnumChatFormatting.GRAY + " (client-side)");
    }

    private static void placeBlock(Minecraft mc, BlockPos pos, IBlockState state) {
        try {
            mc.theWorld.setBlockState(pos, state, 3);
            mc.theWorld.markBlockRangeForRenderUpdate(pos, pos);
        } catch (Throwable t) {
            System.err.println("[BlockSet] Failed to set block at " + pos + ": " + t.getMessage());
        }
    }

    private static Block resolveBlock(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        String s = name.trim();

        // Try by registry name
        Block block = Block.getBlockFromName(s);
        if (block != null) return block;

        // Try by numeric ID
        try {
            int id = Integer.parseInt(s);
            Block b = Block.getBlockById(id);
            if (b != null) return b;
        } catch (NumberFormatException ignored) {}

        // Auto-prefix minecraft: and retry
        if (!s.contains(":")) {
            return resolveBlock("minecraft:" + s);
        }
        return null;
    }

    private static List<String> suggestBlockIds(String prefix) {
        List<String> out = new ArrayList<>();
        if (prefix == null) return out;
        String pfx = prefix.toLowerCase();
        try {
            for (Object keyObj : Block.blockRegistry.getKeys()) {
                ResourceLocation rl = (ResourceLocation) keyObj;
                String id = rl.toString();
                String simple = rl.getResourcePath();
                if (id.toLowerCase().startsWith(pfx) || simple.startsWith(pfx)) {
                    out.add(id);
                    if (out.size() >= 8) break;
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static String getBlockId(Block block) {
        try {
            ResourceLocation rl = (ResourceLocation) Block.blockRegistry.getNameForObject(block);
            if (rl != null) return rl.toString();
        } catch (Throwable ignored) {}
        return block.getUnlocalizedName();
    }
}
