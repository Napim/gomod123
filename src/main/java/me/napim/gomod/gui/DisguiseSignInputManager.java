/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.block.Block
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.GuiScreen
 *  net.minecraft.client.gui.inventory.GuiEditSign
 *  net.minecraft.entity.player.EntityPlayer
 *  net.minecraft.init.Blocks
 *  net.minecraft.tileentity.TileEntitySign
 *  net.minecraft.util.BlockPos
 *  net.minecraft.util.ChatComponentText
 *  net.minecraft.util.IChatComponent
 *  net.minecraft.world.World
 *  org.lwjgl.input.Keyboard
 */
package me.napim.gomod.gui;

import me.napim.gomod.command.DisguiseCommand;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiEditSign;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntitySign;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;
import org.lwjgl.input.Keyboard;

public final class DisguiseSignInputManager {
    private static final Object LOCK = new Object();
    private static TileEntitySign activeSign = null;
    private static GuiScreen returnScreen = null;
    private static boolean submitted = false;

    private DisguiseSignInputManager() {
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static void open(GuiScreen returnTo, String initialValue) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        if (mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        FakeDisguiseSignTileEntity sign = new FakeDisguiseSignTileEntity();
        sign.setWorldObj((World)mc.theWorld);
        sign.setPos(new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ));
        sign.setEditable(true);
        sign.setPlayer((EntityPlayer)mc.thePlayer);
        sign.signText[0] = new ChatComponentText(DisguiseSignInputManager.sanitize(initialValue));
        sign.signText[1] = new ChatComponentText("");
        sign.signText[2] = new ChatComponentText("");
        sign.signText[3] = new ChatComponentText("");
        Object object = LOCK;
        synchronized (object) {
            activeSign = sign;
            returnScreen = returnTo;
            submitted = false;
        }
        mc.displayGuiScreen((GuiScreen)new GuiEditSign((TileEntitySign)sign));
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static boolean handles(TileEntitySign sign) {
        Object object = LOCK;
        synchronized (object) {
            return activeSign != null && activeSign == sign;
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static void markSubmitted(TileEntitySign sign) {
        Object object = LOCK;
        synchronized (object) {
            if (activeSign == sign) {
                submitted = true;
            }
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static void finish(TileEntitySign sign, boolean applyDisguise) {
        String requestedName;
        GuiScreen nextScreen;
        Object object = LOCK;
        synchronized (object) {
            if (activeSign != sign) {
                return;
            }
            nextScreen = returnScreen;
            requestedName = DisguiseSignInputManager.sanitize(DisguiseSignInputManager.readAllLines(sign));
            activeSign = null;
            returnScreen = null;
            submitted = false;
        }
        sign.setEditable(false);
        Keyboard.enableRepeatEvents((boolean)false);
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        Runnable task = () -> {
            if (nextScreen != null) {
                mc.displayGuiScreen(nextScreen);
            } else {
                GomodMenuTransitions.closeToGame(mc);
            }
            if (applyDisguise && !requestedName.isEmpty()) {
                DisguiseCommand.enableDisguiseAsync(requestedName);
            }
        };
        if (mc.isCallingFromMinecraftThread()) {
            task.run();
        } else {
            mc.addScheduledTask(task);
        }
    }

    public static void handleClosed(TileEntitySign sign) {
        DisguiseSignInputManager.finish(sign, submitted);
    }

    private static String readAllLines(TileEntitySign sign) {
        if (sign == null || sign.signText == null) {
            return "";
        }
        StringBuilder combined = new StringBuilder();
        for (IChatComponent component : sign.signText) {
            String line;
            if (component == null || (line = component.getUnformattedText()) == null || line.isEmpty()) continue;
            combined.append(line.trim());
        }
        return combined.toString();
    }

    private static String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder();
        for (int i = 0; i < raw.length() && cleaned.length() < 16; ++i) {
            char c = raw.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') && c != '_') continue;
            cleaned.append(c);
        }
        return cleaned.toString();
    }

    private static final class FakeDisguiseSignTileEntity
    extends TileEntitySign {
        private FakeDisguiseSignTileEntity() {
        }

        public Block getBlockType() {
            return Blocks.standing_sign;
        }

        public int getBlockMetadata() {
            return 0;
        }
    }
}
