package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Spawn-egg entries for mobs that can show Mob Health Display health tags. */
public final class MobHealthTagEggCatalog {

    public static final class Entry {
        public final int entityTypeId;
        public final ItemStack icon;
        public final String label;

        Entry(int entityTypeId, ItemStack icon, String label) {
            this.entityTypeId = entityTypeId;
            this.icon = icon;
            this.label = label;
        }
    }

    private static List<Entry> cached;

    /** Clears cached entries (e.g. after label formatting changes). */
    public static void invalidateCache() {
        cached = null;
    }

    private MobHealthTagEggCatalog() {}

    public static List<Entry> getEntries() {
        if (cached == null) {
            cached = buildEntries();
        }
        return cached;
    }

    private static String formatMobLabel(ItemStack stack) {
        String label = stack.getDisplayName();
        if (label == null || label.isEmpty()) {
            return "Mob";
        }
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(label);
        if (plain.regionMatches(true, 0, "Spawn ", 0, 6)) {
            plain = plain.substring(6).trim();
        }
        return plain.isEmpty() ? label : plain;
    }

    private static List<Entry> buildEntries() {
        List<Entry> out = new ArrayList<>();
        try {
            Minecraft mc = Minecraft.getMinecraft();
            World world = mc != null ? mc.theWorld : null;
            List<ItemStack> stacks = new ArrayList<>();
            Items.spawn_egg.getSubItems(Items.spawn_egg, CreativeTabs.tabMisc, stacks);
            for (ItemStack stack : stacks) {
                if (stack == null || stack.getItem() != Items.spawn_egg) {
                    continue;
                }
                int typeId = stack.getMetadata();
                if (!isLivingMobEgg(typeId, world)) {
                    continue;
                }
                String label = formatMobLabel(stack);
                out.add(new Entry(typeId, stack.copy(), label));
            }
        } catch (Throwable ignored) {
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return out;
    }

    private static boolean isLivingMobEgg(int typeId, World world) {
        if (world == null) {
            return typeId > 0 && typeId < 256;
        }
        try {
            Entity entity = EntityList.createEntityByID(typeId, world);
            return entity instanceof EntityLivingBase && !(entity instanceof EntityPlayer);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
