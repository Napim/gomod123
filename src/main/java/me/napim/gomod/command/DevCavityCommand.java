package me.napim.gomod.command;

import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.Main;
import me.napim.gomod.features.FakeBreak;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;
import net.weavemc.loader.api.command.Command;

/**
 * Dev-only cavity helper for permissioned testing.
 *
 * visual: local FakeBreak cavity only.
 * server confirm: sends ordinary server commands; only works where the player is authorized.
 */
public final class DevCavityCommand extends Command {
    private static final int DEFAULT_DEPTH = 2;
    private static final int MAX_DEPTH = 16;

    public DevCavityCommand() {
        super("devcavity");
    }

    @Override
    public void handle(String[] args) {
        if (!GomodInternalFeatures.isEnabled()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "This command is only available in dev/internal builds.");
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Join a world first.");
            return;
        }

        String mode = args != null && args.length > 0 ? args[0].trim().toLowerCase() : "visual";
        if ("help".equals(mode) || "?".equals(mode)) {
            usage();
            return;
        }

        int depth = parseDepth(args, "server".equals(mode) ? 2 : 1);
        if ("visual".equals(mode) || "local".equals(mode)) {
            makeVisualCavity(mc, depth);
            return;
        }

        if ("server".equals(mode)) {
            boolean confirmed = args != null && args.length > 1 && "confirm".equalsIgnoreCase(args[1]);
            if (!confirmed) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                        + "Server mode changes real server blocks and teleports you only if you have permission.");
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                        + "Use " + EnumChatFormatting.WHITE + "/devcavity server confirm [depth]"
                        + EnumChatFormatting.GRAY + " on a server where you are authorized.");
                return;
            }
            sendAuthorizedServerCommands(mc, depth);
            return;
        }

        usage();
    }

    private static void makeVisualCavity(Minecraft mc, int depth) {
        if (!FakeBreak.isEnabled()) {
            FakeBreak.setEnabled(true);
        }

        EntityPlayerSP player = mc.thePlayer;
        World world = mc.theWorld;
        BlockPos feet = new BlockPos(player.posX, player.posY, player.posZ);
        int removed = 0;

        for (int dy = -depth; dy <= 1; dy++) {
            BlockPos pos = feet.add(0, dy, 0);
            if (!world.isBlockLoaded(pos)) {
                continue;
            }
            IBlockState state = world.getBlockState(pos);
            if (state == null || state.getBlock() == null || state.getBlock().getMaterial().isReplaceable()) {
                continue;
            }
            FakeBreak.addBrokenBlock(pos, state);
            world.setBlockToAir(pos);
            removed++;
        }

        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Visual cavity made below you: "
                + EnumChatFormatting.YELLOW + removed + EnumChatFormatting.GREEN
                + " client-side block" + (removed == 1 ? "" : "s") + " hidden.");
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                + "Use " + EnumChatFormatting.WHITE + "/fakebreak clearblocks"
                + EnumChatFormatting.GRAY + " to resync the view.");
    }

    private static void sendAuthorizedServerCommands(Minecraft mc, int depth) {
        EntityPlayerSP player = mc.thePlayer;
        BlockPos feet = new BlockPos(player.posX, player.posY, player.posZ);
        int x = feet.getX();
        int z = feet.getZ();
        int y = feet.getY() - depth;

        String clear = "/fill " + x + " " + y + " " + z + " " + x + " " + (y + 1) + " " + z + " air 0 replace";
        String tp = "/tp " + player.getName() + " " + (x + 0.5D) + " " + y + " " + (z + 0.5D);

        player.sendChatMessage(clear);
        player.sendChatMessage(tp);
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                + "Sent authorized server cavity commands for depth " + depth + ".");
    }

    private static int parseDepth(String[] args, int index) {
        if (args == null || args.length <= index) {
            return DEFAULT_DEPTH;
        }
        try {
            return Math.max(1, Math.min(MAX_DEPTH, Integer.parseInt(args[index])));
        } catch (NumberFormatException ignored) {
            return DEFAULT_DEPTH;
        }
    }

    private static void usage() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Dev cavity helper:");
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                + "/devcavity visual [depth] " + EnumChatFormatting.DARK_GRAY + "- client-side FakeBreak only");
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                + "/devcavity server confirm [depth] " + EnumChatFormatting.DARK_GRAY + "- normal admin commands");
    }
}
