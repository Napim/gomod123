package me.napim.gomod.features;

import me.napim.gomod.Main;
import me.napim.gomod.shareinv.ShareInvManager;
import me.napim.gomod.util.ClientInputHelper;
import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

/**
 * Client-only slot-five control for hiding other players during Blitz pregames.
 *
 * <p>The real server inventory is never clicked: Minecraft's right-click entry
 * point consumes the marked stack before an item-use packet can be sent.</p>
 */
public final class BlitzPregamePlayerToggle {
    private static final int HOTBAR_SLOT = 7;
    private static final String ITEM_MARKER = "gomod_blitz_pregame_players";
    private static final long USE_COOLDOWN_MS = 1000L;
    private static EntityPlayerSP itemOwner;
    private static ItemStack displacedStack;
    private static boolean activePregame;
    private static boolean playersHidden;
    private static long lastSuccessfulUseMs;
    /** True after this feature has consumed the current physical use-item press. */
    private static boolean usePressConsumed;
    private static int lastPublishedHeldMeta = Integer.MIN_VALUE;

    private BlitzPregamePlayerToggle() {
    }

    public static void onClientTick(Minecraft mc) {
        // rightClickMouse is pulsed repeatedly while use-item remains held. Rearm
        // only after the physical key/button is released so one click is one use.
        if (!ClientInputHelper.isUseItemHeld(mc)) {
            usePressConsumed = false;
        }

        if (mc == null || mc.thePlayer == null || mc.theWorld == null
                || !ConfigManager.isBlitzPregamePlayerToggleEnabled()) {
            deactivate();
            return;
        }

        if (!isActualBlitzPregame(mc)) {
            deactivate();
            return;
        }

        EntityPlayerSP player = mc.thePlayer;
        if (itemOwner != player) {
            restoreOwnedSlot();
            itemOwner = player;
            displacedStack = copyUnlessControl(player.inventory.getStackInSlot(HOTBAR_SLOT));
        }

        activePregame = true;
        ItemStack current = player.inventory.getStackInSlot(HOTBAR_SLOT);
        if (!isControlItem(current)) {
            displacedStack = current == null ? null : current.copy();
        }
        player.inventory.setInventorySlotContents(HOTBAR_SLOT, makeControlItem());
        publishHeldStateIfChanged();
    }

    /**
     * Handles the marked dye before vanilla can send a use-item packet.
     *
     * @return true when the click belongs to this feature and must be cancelled
     */
    public static boolean tryHandleRightClick(Minecraft mc) {
        if (mc == null || mc.thePlayer == null || mc.theWorld == null
                || !ConfigManager.isBlitzPregamePlayerToggleEnabled()
                || mc.thePlayer.inventory.currentItem != HOTBAR_SLOT) {
            return false;
        }

        if (!isActualBlitzPregame(mc)
                || !activePregame || itemOwner != mc.thePlayer) {
            return false;
        }

        ItemStack clickedStack = mc.thePlayer.getHeldItem();
        if (!isControlItem(clickedStack)) {
            return false;
        }
        return toggleVisibility(mc, clickedStack);
    }

    /**
     * Handles clicking the client-only control from the player-inventory row of
     * a server-backed GUI (for example, the Blitz kit or kill-effect selector).
     * Vanilla would otherwise send a window-click packet and consume the fake
     * stack without ever reaching the normal right-click path.
     *
     * @return true when the click was handled and the window click must be cancelled
     */
    public static boolean tryHandleContainerClick(Minecraft mc, int slotId, int mouseButtonClicked, int mode) {
        if (mc == null || mc.thePlayer == null || mc.theWorld == null
                || !(mc.currentScreen instanceof GuiContainer)
                || !ConfigManager.isBlitzPregamePlayerToggleEnabled()
                || !isActualBlitzPregame(mc)
                || !activePregame || itemOwner != mc.thePlayer
                || mode != 0
                || (mouseButtonClicked != 0 && mouseButtonClicked != 1)) {
            return false;
        }

        if (mc.thePlayer.openContainer == null
                || slotId < 0 || slotId >= mc.thePlayer.openContainer.inventorySlots.size()) {
            return false;
        }

        Slot clickedSlot = mc.thePlayer.openContainer.getSlot(slotId);
        if (clickedSlot == null
                || clickedSlot.inventory != mc.thePlayer.inventory
                || clickedSlot.getSlotIndex() != HOTBAR_SLOT) {
            return false;
        }

        ItemStack clickedStack = mc.thePlayer.inventory.getStackInSlot(HOTBAR_SLOT);
        if (!isControlItem(clickedStack)) {
            return false;
        }

        return toggleVisibility(mc, clickedStack);
    }

    private static boolean toggleVisibility(Minecraft mc, ItemStack clickedStack) {
        // Always consume duplicate vanilla/Lunar pulses for the same press, but
        // do not toggle again or repeat the cooldown chat message.
        if (usePressConsumed) {
            return true;
        }
        usePressConsumed = true;

        long now = System.currentTimeMillis();
        if (now - lastSuccessfulUseMs < USE_COOLDOWN_MS) {
            mc.thePlayer.addChatMessage(new ChatComponentText(
                    EnumChatFormatting.RED + "You must wait "
                            + EnumChatFormatting.YELLOW + "1s"
                            + EnumChatFormatting.RED + " between uses!"));
            return true;
        }

        if (!isControlItem(clickedStack)) {
            displacedStack = clickedStack == null ? null : clickedStack.copy();
        }
        lastSuccessfulUseMs = now;
        activePregame = true;
        playersHidden = !playersHidden;
        mc.thePlayer.inventory.setInventorySlotContents(HOTBAR_SLOT, makeControlItem());
        mc.thePlayer.addChatMessage(new ChatComponentText(
                (playersHidden ? EnumChatFormatting.RED : EnumChatFormatting.GREEN)
                        + (playersHidden ? "Player visibility disabled!" : "Player visibility enabled!")));
        publishHeldStateIfChanged();
        return true;
    }

    /**
     * Shared by the normal renderer and Replay Mod's direct RenderPlayer path.
     * The local player is deliberately never hidden.
     */
    public static boolean shouldHide(Entity entity) {
        if (!activePregame || !playersHidden || !(entity instanceof EntityPlayer)
                || !ConfigManager.isBlitzPregamePlayerToggleEnabled()) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        return isActualBlitzPregame(mc) && entity != mc.thePlayer;
    }

    /** Coordinate fallback for effects delivered independently of an entity tick. */
    public static boolean isHiddenPlayerNear(double x, double y, double z, double radius) {
        if (!activePregame || !playersHidden) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (!isActualBlitzPregame(mc)) {
            return false;
        }
        double radiusSq = radius * radius;
        for (Object candidate : mc.theWorld.playerEntities) {
            if (!(candidate instanceof EntityPlayer)) {
                continue;
            }
            EntityPlayer player = (EntityPlayer) candidate;
            if (shouldHide(player)) {
                double dx = player.posX - x;
                double dy = player.posY - y;
                double dz = player.posZ - z;
                if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Relay value: 8/10 only while the local player is actually holding the control. */
    public static int getLocalHeldDyeMeta() {
        Minecraft mc = Minecraft.getMinecraft();
        if (!activePregame || !isActualBlitzPregame(mc)
                || itemOwner != mc.thePlayer || mc.thePlayer.inventory.currentItem != HOTBAR_SLOT) {
            return -1;
        }
        return playersHidden ? 8 : 10;
    }

    /** Applies a Miscellaneous-menu setting change without waiting for another tick. */
    public static void onModuleSettingChanged() {
        if (!ConfigManager.isBlitzPregamePlayerToggleEnabled()) {
            deactivate();
        }
    }

    private static ItemStack makeControlItem() {
        ItemStack stack = new ItemStack(Items.dye, 1, playersHidden ? 8 : 10);
        stack.setStackDisplayName(
                EnumChatFormatting.WHITE + "Players: "
                        + (playersHidden
                        ? EnumChatFormatting.RED + "Hidden"
                        : EnumChatFormatting.GREEN + "Visible")
                        + EnumChatFormatting.GRAY + " (Right Click)");
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
        }
        tag.setBoolean(ITEM_MARKER, true);
        stack.setTagCompound(tag);
        return stack;
    }

    private static boolean isControlItem(ItemStack stack) {
        return stack != null && stack.getItem() == Items.dye && stack.hasTagCompound()
                && stack.getTagCompound().getBoolean(ITEM_MARKER);
    }

    private static ItemStack copyUnlessControl(ItemStack stack) {
        return stack == null || isControlItem(stack) ? null : stack.copy();
    }

    /**
     * The control belongs only to the actual Blitz map waiting room. The broad
     * pregame detector also covers the games-hub queue, and the cage detector
     * marks the transition into the match; neither may retain the item/effect.
     */
    private static boolean isActualBlitzPregame(Minecraft mc) {
        return mc != null && mc.thePlayer != null && mc.theWorld != null
                && GameContext.isBlitzPregameLobby(mc)
                && !GameContext.isBlitzHypixelHubQueueSidebar(mc)
                && !GameContext.isBlitzCagePhaseSidebar(mc)
                && !GameContext.isBlitzGameActive(mc);
    }

    private static void deactivate() {
        restoreOwnedSlot();
        activePregame = false;
        playersHidden = false;
        lastSuccessfulUseMs = 0L;
        usePressConsumed = false;
        publishHeldStateIfChanged();
    }

    private static void restoreOwnedSlot() {
        if (itemOwner != null && isControlItem(itemOwner.inventory.getStackInSlot(HOTBAR_SLOT))) {
            itemOwner.inventory.setInventorySlotContents(
                    HOTBAR_SLOT, displacedStack == null ? null : displacedStack.copy());
        }
        itemOwner = null;
        displacedStack = null;
    }

    private static void publishHeldStateIfChanged() {
        int meta = getLocalHeldDyeMeta();
        if (meta == lastPublishedHeldMeta) {
            return;
        }
        lastPublishedHeldMeta = meta;
        try {
            Main.getGlobalChatClient().requestImmediatePoll();
        } catch (Throwable ignored) {
        }
        try {
            ShareInvManager.resendHelloIfConnected();
        } catch (Throwable ignored) {
        }
    }
}
