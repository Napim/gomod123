package me.napim.gomod.features;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBush;
import net.minecraft.block.BlockCrops;
import net.minecraft.block.BlockDoublePlant;
import net.minecraft.block.BlockFence;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockFlower;
import net.minecraft.block.BlockGrass;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.BlockLog;
import net.minecraft.block.BlockMushroom;
import net.minecraft.block.BlockPane;
import net.minecraft.block.BlockReed;
import net.minecraft.block.BlockSapling;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.BlockStem;
import net.minecraft.block.BlockTallGrass;
import net.minecraft.block.BlockVine;
import net.minecraft.block.BlockWall;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;

/**
 * Which blocks/sprites participate in Block Finder world opacity (barrier-style atlas + translucent).
 */
public final class BlocksFinderWorldOpacityRules {

    private BlocksFinderWorldOpacityRules() {}

    /**
     * Sprites that must stay vanilla (items, fluids, glass, tracked-block icons).
     */
    public static boolean shouldSkipSpriteFade(String iconName) {
        if (iconName == null) {
            return true;
        }
        String n = iconName.toLowerCase();
        if (n.startsWith("items/") || n.contains(":items/") || n.contains("/items/")) {
            return true;
        }
        if (n.contains("water") || n.contains("lava") || n.contains("flowing_")) {
            return true;
        }
        if (n.contains("portal") || n.contains("fire_layer")) {
            return true;
        }
        if (n.contains("glass") || n.contains("pane")) {
            return true;
        }
        if (n.contains("ice") && !n.contains("dirt") && !n.contains("spice")) {
            return true;
        }
        if (n.contains("chest") || n.contains("crafting_table") || n.contains("enchanting_table")) {
            return true;
        }
        return false;
    }

    /**
     * Blocks that must render on {@link net.minecraft.util.EnumWorldBlockLayer#TRANSLUCENT} while the
     * atlas is faded so texture alpha actually blends (SOLID/CUTOUT alpha-test keeps them looking opaque).
     */
    public static boolean shouldForceTranslucentLayer(Block block) {
        if (block == null || block == Blocks.air) {
            return false;
        }
        if (isExcludedFromWorldOpacity(block)) {
            return false;
        }
        if (block.isOpaqueCube()) {
            return true;
        }
        return block instanceof BlockGrass
                || block instanceof BlockTallGrass
                || block instanceof BlockLeaves
                || block instanceof BlockFlower
                || block instanceof BlockDoublePlant
                || block instanceof BlockBush
                || block instanceof BlockMushroom
                || block instanceof BlockSapling
                || block instanceof BlockCrops
                || block instanceof BlockStem
                || block instanceof BlockReed
                || block instanceof BlockVine
                || block instanceof BlockLog
                || block instanceof BlockStairs
                || block instanceof BlockSlab
                || block instanceof BlockFence
                || block instanceof BlockFenceGate
                || block instanceof BlockWall
                || block == Blocks.deadbush
                || block == Blocks.farmland
                || block == Blocks.hay_block
                || block == Blocks.carpet
                || block == Blocks.snow_layer;
    }

    private static boolean isExcludedFromWorldOpacity(Block block) {
        if (block == Blocks.glass
                || block == Blocks.stained_glass
                || block == Blocks.glass_pane
                || block == Blocks.stained_glass_pane
                || block == Blocks.ice
                || block == Blocks.packed_ice
                || block == Blocks.water
                || block == Blocks.flowing_water
                || block == Blocks.lava
                || block == Blocks.flowing_lava
                || block == Blocks.portal
                || block == Blocks.end_portal
                || block == Blocks.end_portal_frame
                || block == Blocks.fire
                || block == Blocks.chest
                || block == Blocks.trapped_chest
                || block == Blocks.ender_chest
                || block == Blocks.crafting_table
                || block == Blocks.enchanting_table) {
            return true;
        }
        if (block instanceof BlockPane) {
            return true;
        }
        try {
            Material mat = block.getMaterial();
            if (mat != null && mat.isLiquid()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
