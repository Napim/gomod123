package me.napim.gomod.mixins;

import me.napim.gomod.features.BlitzTeamsDetector;
import me.napim.gomod.features.ItemESP;
import me.napim.gomod.features.PlayerTarget;
import me.napim.gomod.render.VanillaEntityLabelRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RenderEntityItem;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Item labels: vanilla-scale nameplate with a <b>no-depth pass</b> so labels stay visible within range even when the
 * item is behind blocks. Teammate tagging uses {@link EntityItem#getThrower()} and a short proximity fallback.
 */
@Mixin(RenderEntityItem.class)
public class RenderEntityItemMixin {

    @Unique private static final int gomod$ITEM_STACK_WATCHER_ID = 10;
    private static final int MAX_LABEL_DISTANCE = 64;
    /** Teammate-loot labels and full {@code /itemesp} names only render for this many ticks after the item spawns (20 tps). */
    private static final int LABEL_VISIBLE_MAX_AGE_TICKS = 80;
    /** Last this many ticks use a quick shrink (and fade) instead of a hard cut-off. */
    private static final int LABEL_SHRINK_OUT_TICKS = 10;
    private static final double PROXIMITY_HORIZ_BLOCKS = 3.25;
    private static final double PROXIMITY_VERT_BLOCKS = 2.75;

    /**
     * EntityItem#getEntityItem can log and substitute a stone stack when the watched stack is absent.
     * Check the tracked value before calling the renderer so a missing stack does not produce a placeholder.
     */
    @Inject(method = "doRender", at = @At("HEAD"), cancellable = true)
    private void gomod$skipEmptyItem(EntityItem entity, double x, double y, double z, float entityYaw,
            float partialTicks, CallbackInfo ci) {
        if (entity == null || entity.getDataWatcher().getWatchableObjectItemStack(gomod$ITEM_STACK_WATCHER_ID) == null) {
            ci.cancel();
        }
    }

    @Inject(method = "doRender", at = @At("TAIL"))
    private void gomod$renderItemName(EntityItem entity, double x, double y, double z, float entityYaw,
            float partialTicks, CallbackInfo ci) {
        boolean fullEsp = ItemESP.isEnabled();
        if (!fullEsp && !ItemESP.isTeamLootEnabled()) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null || mc.getRenderManager() == null) {
            return;
        }

        try {
            int itemAge = ((EntityItemAccessor) (Object) entity).gomod$getItemAge();
            float ageF = itemAge + partialTicks;
            if (ageF >= LABEL_VISIBLE_MAX_AGE_TICKS) {
                return;
            }

            boolean teammateItem = false;
            try {
                teammateItem = BlitzTeamsDetector.isActiveBlitzTeamsGame(mc) && isItemFromTeammate(mc, entity);
            } catch (Throwable t) {
                System.err.println("[gomod] teammate ItemESP gate: " + t.getMessage());
            }
            if (!fullEsp && !teammateItem) {
                return;
            }

            ItemStack stack = entity.getDataWatcher().getWatchableObjectItemStack(gomod$ITEM_STACK_WATCHER_ID);
            if (stack == null) {
                return;
            }

            float shrinkStart = LABEL_VISIBLE_MAX_AGE_TICKS - LABEL_SHRINK_OUT_TICKS;
            float contentScale = 1.0F;
            float contentAlpha = 1.0F;
            if (ageF > shrinkStart) {
                float t = (ageF - shrinkStart) / LABEL_SHRINK_OUT_TICKS;
                if (t > 1.0F) {
                    t = 1.0F;
                }
                float u = 1.0F - t;
                // Ease: shrink quickly toward center (quadratic + matching fade).
                contentScale = u * u;
                contentAlpha = u;
            }

            String label = buildItemLabel(stack, teammateItem);
            VanillaEntityLabelRenderer.render(entity, label, x, y, z, MAX_LABEL_DISTANCE,
                    ItemESP.getLabelExtraYOffset(), 0xFFFFFF, contentScale, contentAlpha);
        } catch (Throwable t) {
            System.err.println("[gomod] RenderEntityItemMixin label: " + t.getMessage());
        }
    }

    private static String buildItemLabel(ItemStack stack, boolean teammateItem) {
        String raw = stack.getDisplayName();
        if (raw == null || raw.isEmpty()) {
            raw = stack.getItem().getItemStackDisplayName(stack);
        }
        if (raw == null) {
            raw = "";
        }
        raw = EnumChatFormatting.getTextWithoutFormattingCodes(raw);
        if (raw == null) {
            raw = "";
        }
        String itemCore = raw;
        String countSuffix = "";
        if (stack.stackSize > 1) {
            countSuffix = EnumChatFormatting.GRAY + " x" + stack.stackSize;
        }
        if (teammateItem) {
            // White tag, green item name; stack count stays §7 via countSuffix when stackSize > 1.
            return EnumChatFormatting.WHITE + "[TEAM] " + EnumChatFormatting.GREEN + itemCore + countSuffix;
        }
        // Full /itemesp (non-teammate drops): same colors as teammate loot labels — green name, gray ×count — no [TEAM].
        return EnumChatFormatting.GREEN + itemCore + countSuffix;
    }

    /**
     * <ol>
     *   <li><b>Thrower name:</b> if the server set {@link EntityItem#getThrower()} and it matches your current
     *       teammate IGN from {@link PlayerTarget#getCurrentTeammateName()}, the drop counts as theirs.</li>
     *   <li><b>Proximity fallback:</b> for drops in the label window with no thrower match,
     *       if the stack is within {@value #PROXIMITY_HORIZ_BLOCKS} blocks horizontally and
     *       {@value #PROXIMITY_VERT_BLOCKS} vertically of the teammate player entity, it counts (covers some servers
     *       that omit thrower).</li>
     * </ol>
     */
    private static boolean isItemFromTeammate(Minecraft mc, EntityItem item) {
        String teammateName = PlayerTarget.getCurrentTeammateName();
        if (teammateName == null || teammateName.isEmpty()) {
            return false;
        }

        String thrower = item.getThrower();
        if (thrower != null && !thrower.isEmpty() && thrower.equalsIgnoreCase(teammateName)) {
            return true;
        }

        net.minecraft.entity.player.EntityPlayer teammate = PlayerTarget.getCurrentTeammateEntityForLoot(mc);
        if (teammate == null) {
            return false;
        }

        double dx = item.posX - teammate.posX;
        double dy = item.posY - teammate.posY;
        double dz = item.posZ - teammate.posZ;
        double h = Math.sqrt(dx * dx + dz * dz);
        return h <= PROXIMITY_HORIZ_BLOCKS && Math.abs(dy) <= PROXIMITY_VERT_BLOCKS;
    }
}
