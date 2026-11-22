package me.napim.gomod.features;

import static me.napim.gomod.Main.sendMessage;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumChatFormatting;
import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.weavemc.loader.api.event.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.block.state.IBlockState;
import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.Entity;
import java.util.List;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import java.util.ArrayList;
import net.minecraft.util.Vec3;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.AxisAlignedBB;

/**
 * FakeBreak feature - Makes blocks appear to break client-side even on protected servers
 * When you break a block, it will visually disappear and drop an item on your client only
 */
public class FakeBreak {
    private static boolean enabled = false;
    private static float fixedBreakTime = -1.0f; // if > 0, use fixed time in seconds instead of hardness
    private static final Set<BlockPos> brokenBlocks = new HashSet<>();
    private static final Map<BlockPos, IBlockState> brokenBlockStates = new HashMap<>();
    private static final Map<BlockPos, IBlockState> placedBlocks = new HashMap<>();
    private static final Map<BlockPos, IBlockState> placedOriginalStates = new HashMap<>();
    private static final Map<BlockPos, Integer> sustainTicks = new HashMap<>();
    private static final Set<BlockPos> restoreOnNextServerUpdate = new HashSet<>();
    private static final java.util.Set<Integer> clientEntityIds = new java.util.HashSet<>();
    private static final Map<BlockPos, Float> damageProgress = new HashMap<>();
    private static final Set<Integer> clientItemEntityIds = new HashSet<>();
    private static final List<ItemStack> collectedClientItems = new ArrayList<>();
    private static FakeBreak eventInstance = null;
    private static boolean eventSubscribed = false;
    // Ghost freecam
    private static Integer ghostEntityId = null;
    private static int ghostTicks = 0;
    private static final Map<Integer, Integer> pearlWatchTicks = new HashMap<>();
    private static final Map<Integer, Vec3> pearlLastPos = new HashMap<>();
    
    /**
     * Checks if FakeBreak is enabled.
     * @return true if FakeBreak is enabled, false otherwise
     */
    public static boolean isEnabled() {
        return enabled;
    }
    
    /**
     * Sets whether FakeBreak is enabled and notifies the player.
     * @param value true to enable FakeBreak, false to disable
     */
    public static void setEnabled(boolean value) {
        // Ensure initialized before changing state
        ensureInitialized();
        
        enabled = value;
        ConfigManager.setFakeBreakEnabled(value);
        
        // Clear broken blocks when disabling
        if (!enabled) {
            clearBrokenBlocks();
        }
        
        String status = enabled ? 
            EnumChatFormatting.GREEN + "enabled" : 
            EnumChatFormatting.RED + "disabled";
        
        sendMessage(me.napim.gomod.Main.CHAT_PREFIX + 
            EnumChatFormatting.YELLOW + "FakeBreak has been " + status);
        
        if (enabled) {
            sendMessage(me.napim.gomod.Main.CHAT_PREFIX + 
                EnumChatFormatting.GRAY + "Blocks will appear to break client-side and drop items");
        } else {
            sendMessage(me.napim.gomod.Main.CHAT_PREFIX + 
                EnumChatFormatting.GRAY + "Block breaking returned to normal");
        }
    }
    
    /**
     * Loads FakeBreak settings from config.
     * Called lazily on first use to avoid early class loading.
     */
    private static void loadSetting() {
        enabled = ConfigManager.isFakeBreakEnabled();
        System.out.println("[FakeBreak] Loaded setting: " + (enabled ? "enabled" : "disabled"));
    }
    
    /**
     * Lazy initialization - loads config and subscribes to EventBus
     */
    private static void ensureInitialized() {
        if (eventSubscribed) return;
        eventSubscribed = true;
        
        // Load config
        loadSetting();
        
        // Subscribe to EventBus
        try {
            if (eventInstance == null) {
                eventInstance = new FakeBreak();
            }
            net.weavemc.loader.api.event.EventBus.subscribe(eventInstance);
            System.out.println("[FakeBreak] Initialized and subscribed to EventBus");
        } catch (Throwable t) {
            System.err.println("[FakeBreak] Failed to initialize: " + t.getMessage());
        }
    }
    
    /**
     * Adds a block position to the list of fake-broken blocks
     */
    public static void addBrokenBlock(BlockPos pos) {
        // Create a new BlockPos to ensure it's immutable
        if (pos == null) return;
        BlockPos immutable = new BlockPos(pos);
        brokenBlocks.add(immutable);
        if (!brokenBlockStates.containsKey(immutable)) {
            try {
                Minecraft mc = Minecraft.getMinecraft();
                if (mc != null && mc.theWorld != null) {
                    IBlockState state = mc.theWorld.getBlockState(pos);
                    if (state != null && state.getBlock() != null && !state.getBlock().getMaterial().isReplaceable()) {
                        brokenBlockStates.put(immutable, state);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public static void addBrokenBlock(BlockPos pos, IBlockState state) {
        if (pos == null) return;
        BlockPos immutable = new BlockPos(pos);
        brokenBlocks.add(immutable);
        if (state != null && state.getBlock() != null && !state.getBlock().getMaterial().isReplaceable()) {
            brokenBlockStates.put(immutable, state);
        }
    }
    
    /**
     * Checks if a block position has been fake-broken
     */
    public static boolean isBrokenBlock(BlockPos pos) {
        return enabled && brokenBlocks.contains(pos);
    }
    
    /**
     * Gets the set of broken blocks (for mixin access)
     */
    public static Set<BlockPos> getBrokenBlocks() {
        return brokenBlocks;
    }
    
    /**
     * Gets the map of placed blocks (for mixin access)
     */
    public static Map<BlockPos, IBlockState> getPlacedBlocks() {
        return placedBlocks;
    }
    
    /**
     * Removes a block from the broken blocks list (e.g., if player places a block there)
     */
    public static void removeBrokenBlock(BlockPos pos) {
        brokenBlocks.remove(pos);
        brokenBlockStates.remove(pos);
        restoreOnNextServerUpdate.remove(pos);
    }
    
    /**
     * Clears all broken blocks (e.g., on world change or disconnect)
     */
    public static void clearBrokenBlocks() {
        brokenBlocks.clear();
        brokenBlockStates.clear();
        placedBlocks.clear();
        placedOriginalStates.clear();
        damageProgress.clear();
        restoreOnNextServerUpdate.clear();
    }
    
    /**
     * Restores all fake-broken blocks by clearing the tracking and requesting server sync
     */
    public static void restoreBrokenBlocks() {
        ensureInitialized();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) return;
        
        int blockCount = brokenBlocks.size() + placedBlocks.size();
        int itemCount = 0;
        
        // Remove all fake dropped items from the world
        try {
            List<Entity> toRemove = new ArrayList<>();
            for (Entity e : new ArrayList<>((List<Entity>) mc.theWorld.loadedEntityList)) {
                if (!(e instanceof EntityItem)) continue;
                if (clientItemEntityIds.contains(e.getEntityId())) {
                    toRemove.add(e);
                    itemCount++;
                }
            }
            // Remove the entities
            for (Entity e : toRemove) {
                e.setDead();
            }
        } catch (Throwable t) {
            System.err.println("[FakeBreak] Error removing fake items: " + t.getMessage());
        }
        
        Map<BlockPos, IBlockState> statesToRestore = new HashMap<>(brokenBlockStates);
        statesToRestore.putAll(placedOriginalStates);
        Set<BlockPos> brokenWithoutState = new HashSet<>(brokenBlocks);
        brokenWithoutState.removeAll(statesToRestore.keySet());
        Set<BlockPos> fakePlacedToClear = new HashSet<>(placedBlocks.keySet());
        fakePlacedToClear.removeAll(statesToRestore.keySet());

        // Clear tracking first so our WorldClient mixin and tick loop do not fight the restore.
        brokenBlocks.clear();
        brokenBlockStates.clear();
        placedBlocks.clear();
        placedOriginalStates.clear();
        damageProgress.clear();
        sustainTicks.clear();
        clientItemEntityIds.clear();
        collectedClientItems.clear();
        restoreOnNextServerUpdate.clear();

        int restoredBlocks = 0;
        int clearedFakePlacements = 0;
        try {
            for (Map.Entry<BlockPos, IBlockState> entry : statesToRestore.entrySet()) {
                BlockPos pos = entry.getKey();
                IBlockState state = entry.getValue();
                if (state == null || state.getBlock() == null) {
                    continue;
                }
                if (mc.theWorld.setBlockState(pos, state, 3)) {
                    mc.theWorld.markBlockRangeForRenderUpdate(pos, pos);
                    restoredBlocks++;
                }
            }

            for (BlockPos pos : fakePlacedToClear) {
                mc.theWorld.setBlockToAir(pos);
                mc.theWorld.markBlockRangeForRenderUpdate(pos, pos);
                clearedFakePlacements++;
            }

            for (BlockPos pos : brokenWithoutState) {
                mc.theWorld.markBlockRangeForRenderUpdate(pos, pos);
            }

            try {
                mc.renderGlobal.loadRenderers();
            } catch (Throwable ignored) {
            }

            sendMessage(me.napim.gomod.Main.CHAT_PREFIX
                + EnumChatFormatting.GREEN + "Cleared " + blockCount
                + " fake-broken/placed blocks and " + itemCount + " fake dropped items.");
            sendMessage(me.napim.gomod.Main.CHAT_PREFIX
                + EnumChatFormatting.GRAY + "Restored " + restoredBlocks
                + " saved server blocks and removed " + clearedFakePlacements
                + " fake placed blocks.");
        } catch (Throwable t) {
            System.err.println("[FakeBreak] Error restoring fake blocks: " + t.getMessage());
            t.printStackTrace();
        }
    }

    /**
     * Replace blocks within a radius with random blocks (client-side visual only)
     */
    public static void replaceBlocksInRadius(int radius) {
        ensureInitialized();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) return;
        
        BlockPos playerPos = mc.thePlayer.getPosition();
        java.util.Random random = new java.util.Random();
        
        // List of interesting blocks to randomly place
        int[] blockIds = {
            1,   // Stone
            2,   // Grass
            3,   // Dirt
            4,   // Cobblestone
            5,   // Planks
            7,   // Bedrock
            12,  // Sand
            13,  // Gravel
            14,  // Gold Ore
            15,  // Iron Ore
            16,  // Coal Ore
            17,  // Log
            18,  // Leaves
            19,  // Sponge
            20,  // Glass
            21,  // Lapis Ore
            22,  // Lapis Block
            24,  // Sandstone
            35,  // Wool
            41,  // Gold Block
            42,  // Iron Block
            45,  // Bricks
            46,  // TNT
            48,  // Mossy Cobblestone
            49,  // Obsidian
            56,  // Diamond Ore
            57,  // Diamond Block
            79,  // Ice
            80,  // Snow Block
            82,  // Clay
            84,  // Jukebox
            86,  // Pumpkin
            87,  // Netherrack
            88,  // Soul Sand
            89,  // Glowstone
            91,  // Jack O'Lantern
            95,  // Stained Glass
            98,  // Stone Brick
            103, // Melon
            110, // Mycelium
            121, // End Stone
            129, // Emerald Ore
            133, // Emerald Block
            152, // Redstone Block
            155, // Quartz Block
            159, // Stained Clay
            168, // Prismarine
            169, // Sea Lantern
            172, // Hardened Clay
            174  // Packed Ice
        };
        
        // Slab/half-block variants
        int[] slabIds = {44, 126, 182}; // Stone slab, Wood slab, Red sandstone slab
        int[] slabMetas = {0, 1, 2, 3, 4, 5, 6, 7}; // Different slab types
        
        int blocksReplaced = 0;
        int blocksScanned = 0;
        
        try {
            // Scan in a cube around the player
            for (int x = -radius; x <= radius; x++) {
                for (int y = -radius; y <= radius; y++) {
                    for (int z = -radius; z <= radius; z++) {
                        // Check if within sphere
                        double distance = Math.sqrt(x*x + y*y + z*z);
                        if (distance > radius) continue;
                        
                        BlockPos pos = playerPos.add(x, y, z);
                        blocksScanned++;
                        
                        try {
                            IBlockState currentState = mc.theWorld.getBlockState(pos);
                            Block currentBlock = currentState.getBlock();
                            
                            // Skip air blocks
                            if (currentBlock == null || currentBlock.getMaterial().isReplaceable()) {
                                continue;
                            }
                            
                            // Check if this is a slab/half block
                            boolean isHalfBlock = currentBlock.getUnlocalizedName().contains("slab") || 
                                                  currentBlock.getUnlocalizedName().contains("half");
                            
                            IBlockState randomState;
                            
                            if (isHalfBlock) {
                                // Replace slab with random slab
                                int randomSlabId = slabIds[random.nextInt(slabIds.length)];
                                Block randomSlab = Block.getBlockById(randomSlabId);
                                if (randomSlab != null) {
                                    int randomMeta = slabMetas[random.nextInt(slabMetas.length)];
                                    randomState = randomSlab.getStateFromMeta(randomMeta);
                                } else {
                                    continue;
                                }
                            } else {
                                // Replace full block with random full block
                                int randomBlockId = blockIds[random.nextInt(blockIds.length)];
                                Block randomBlock = Block.getBlockById(randomBlockId);
                                
                                if (randomBlock != null) {
                                    randomState = randomBlock.getDefaultState();
                                    
                                    // For wool and stained glass/clay, randomize the color
                                    if (randomBlockId == 35 || randomBlockId == 95 || randomBlockId == 159) {
                                        try {
                                            randomState = randomBlock.getStateFromMeta(random.nextInt(16));
                                        } catch (Throwable ignored) {}
                                    }
                                } else {
                                    continue;
                                }
                            }
                            
                            // Place the random block client-side
                            mc.theWorld.setBlockState(pos, randomState, 3);
                            mc.theWorld.markBlockRangeForRenderUpdate(pos, pos);
                            blocksReplaced++;
                        } catch (Throwable t) {
                            // Skip problematic blocks
                        }
                    }
                }
            }
            
            sendMessage(me.napim.gomod.Main.CHAT_PREFIX + 
                EnumChatFormatting.LIGHT_PURPLE + "Replaced " + blocksReplaced + " blocks within " + radius + " block radius with random blocks!");
            sendMessage(me.napim.gomod.Main.CHAT_PREFIX + 
                EnumChatFormatting.GRAY + "Rejoin or move away to restore real blocks from server");
        } catch (Throwable t) {
            System.err.println("[FakeBreak] Error replacing blocks: " + t.getMessage());
        }
    }
    
    /**
     * Every tick, enforce that fake-broken blocks remain air on the client.
     */
    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (!ClientTickHub.isFeatureEnabled("FakeBreak")) return;
        long _profT = FeatureProfiler.begin("FakeBreak");
        try {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) return;

        try {
            if (enabled) {
                // Copy to avoid concurrent modification if we remove invalid ones
                Set<BlockPos> snapshot = new HashSet<>(brokenBlocks);
                for (BlockPos pos : snapshot) {
                    IBlockState state = mc.theWorld.getBlockState(pos);
                    Block block = state.getBlock();
                    if (block != null && !block.getMaterial().isReplaceable()) {
                        // Force it to air client-side
                        mc.theWorld.setBlockToAir(pos);
                    }
                }

                // Enforce client-placed blocks
                if (!placedBlocks.isEmpty()) {
                    for (Map.Entry<BlockPos, IBlockState> e : placedBlocks.entrySet()) {
                        BlockPos pos = e.getKey();
                        IBlockState desired = e.getValue();
                        IBlockState current = mc.theWorld.getBlockState(pos);
                        if (current == null || current.getBlock() != desired.getBlock() || !current.equals(desired)) {
                            try {
                                // Set without neighbor updates to reduce flicker
                                mc.theWorld.setBlockState(pos, desired, 2);
                                mc.theWorld.markBlockRangeForRenderUpdate(pos, pos);
                            } catch (Throwable ignored) {
                                // Fallback
                                mc.theWorld.setBlockToAir(pos);
                                mc.theWorld.setBlockState(pos, desired, 3);
                            }
                        }
                        // Sustain for a few ticks if requested
                        Integer left = sustainTicks.get(pos);
                        if (left != null) {
                            if (left <= 0) {
                                sustainTicks.remove(pos);
                            } else {
                                sustainTicks.put(pos, left - 1);
                            }
                        }
                    }
                }
            }

            // Handle ghost freecam timeout
            if (ghostTicks > 0) {
                ghostTicks--;
                if (ghostTicks == 0) {
                    try {
                        // Restore camera to player and remove ghost
                        if (mc.thePlayer != null) {
                            mc.setRenderViewEntity(mc.thePlayer);
                        }
                        if (ghostEntityId != null) {
                            Entity ge = mc.theWorld.getEntityByID(ghostEntityId);
                            if (ge != null) {
                                ge.setDead();
                            }
                            clientEntityIds.remove(ghostEntityId);
                        }
                    } catch (Throwable ignored) {}
                    ghostEntityId = null;
                }
            }

            // Watch pearls: trigger ghost view when onGround or after timeout
            if (!pearlWatchTicks.isEmpty()) {
                java.util.Iterator<Map.Entry<Integer, Integer>> it = pearlWatchTicks.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<Integer, Integer> entry = it.next();
                    int id = entry.getKey();
                    int ticks = entry.getValue() + 1;
                    Entity e = mc.theWorld.getEntityByID(id);
                    boolean trigger = false;
                    if (e == null) {
                        trigger = true;
                    } else {
                        try {
                            // Raytrace from current to next position to detect collision
                            Vec3 start = new Vec3(e.posX, e.posY, e.posZ);
                            Vec3 end = new Vec3(e.posX + e.motionX, e.posY + e.motionY, e.posZ + e.motionZ);
                            MovingObjectPosition hit = Minecraft.getMinecraft().theWorld.rayTraceBlocks(start, end, false, true, false);
                            trigger = (hit != null) || e.onGround || e.posY <= 1.0D || (Math.abs(e.motionY) < 0.03D && ticks > 10) || ticks > 40;
                            pearlLastPos.put(id, end);
                        } catch (Throwable t) {
                            trigger = ticks > 40;
                        }
                    }
                    if (trigger) {
                        double gx = e != null ? e.posX : mc.thePlayer.posX;
                        double gy = e != null ? Math.max(1.0, e.posY) : mc.thePlayer.posY;
                        double gz = e != null ? e.posZ : mc.thePlayer.posZ;
                        try {
                            EntityOtherPlayerMP ghost = new EntityOtherPlayerMP(mc.theWorld, mc.thePlayer.getGameProfile());
                            ghost.copyLocationAndAnglesFrom(mc.thePlayer);
                            ghost.setPositionAndRotation(gx, gy, gz, mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch);
                            ghost.onGround = true;
                            mc.theWorld.spawnEntityInWorld(ghost);
                            registerClientEntity(ghost);
                            startGhostView(ghost, 100);
                        } catch (Throwable ignored) {}
                        if (e != null) {
                            try { e.setDead(); } catch (Throwable ignored) {}
                        }
                        pearlLastPos.remove(id);
                        it.remove();
                    } else {
                        entry.setValue(ticks);
                    }
                }
            }

            // Handle pickup of client-only item entities we spawned
            if (!clientItemEntityIds.isEmpty() && mc.thePlayer != null) {
                List<Integer> toRemove = new ArrayList<>();
                try {
                    for (Entity e : new ArrayList<>((List<Entity>) mc.theWorld.loadedEntityList)) {
                        if (!(e instanceof EntityItem)) continue;
                        if (!clientItemEntityIds.contains(e.getEntityId())) continue;
                        EntityItem ei = (EntityItem) e;
                        
                        // Check distance for pickup (vanilla uses 1.0 range)
                        double dx = e.posX - mc.thePlayer.posX;
                        double dy = e.posY - mc.thePlayer.posY;
                        double dz = e.posZ - mc.thePlayer.posZ;
                        double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
                        
                        if (dist <= 2.0D) {
                            try {
                                ItemStack is = ei.getEntityItem();
                                if (is != null && is.stackSize > 0) {
                                    // Add to inventory and record
                                    ItemStack copy = is.copy();
                                    boolean added = mc.thePlayer.inventory.addItemStackToInventory(copy);
                                    if (added) {
                                        System.out.println("[FakeBreak] Picked up client item: " + is.getDisplayName() + " x" + is.stackSize);
                                        recordCollected(is);
                                        // Play pickup sound
                                        mc.thePlayer.playSound("random.pop", 0.2F, ((mc.thePlayer.getRNG().nextFloat() - mc.thePlayer.getRNG().nextFloat()) * 0.7F + 1.0F) * 2.0F);
                                        // Remove the entity client-side
                                        ei.setDead();
                                        toRemove.add(e.getEntityId());
                                    }
                                }
                            } catch (Throwable t) {
                                System.err.println("[FakeBreak] Pickup error: " + t.getMessage());
                            }
                        }
                    }
                } catch (Throwable t) {
                    System.err.println("[FakeBreak] Pickup loop error: " + t.getMessage());
                }
                if (!toRemove.isEmpty()) {
                    clientItemEntityIds.removeAll(toRemove);
                }
            }
        } catch (Throwable ignored) {}
        } finally { FeatureProfiler.end("FakeBreak", _profT); }
    }

    // ---------------- Client-side placement helpers ----------------
    public static void addPlacedBlock(BlockPos pos, IBlockState state) {
        BlockPos immutable = new BlockPos(pos);
        IBlockState original = brokenBlockStates.get(immutable);
        if (original != null && original.getBlock() != null && !original.getBlock().getMaterial().isReplaceable()) {
            placedOriginalStates.put(immutable, original);
        }
        placedBlocks.put(immutable, state);
        // Also not broken anymore
        brokenBlocks.remove(pos);
        brokenBlockStates.remove(pos);
        restoreOnNextServerUpdate.remove(pos);
        // Sustain for 5 ticks to defeat immediate server refresh
        sustainTicks.put(immutable, 60);
    }

    public static boolean tryRequestRestoreOnRightClick(BlockPos pos, ItemStack held) {
        if (!enabled || pos == null || !brokenBlocks.contains(pos)) return false;
        if (held != null && (isClientProvidedStack(held) || isCollectedClientItem(held))) return false;
        return restoreBrokenBlockClientSide(pos);
    }

    public static boolean tryRestoreTargetedBrokenBlock(Minecraft mc) {
        if (!enabled || mc == null || mc.theWorld == null || mc.thePlayer == null || brokenBlocks.isEmpty()) {
            return false;
        }
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held != null && (isClientProvidedStack(held) || isCollectedClientItem(held))) {
            return false;
        }
        BlockPos pos = findTargetedBrokenBlock(mc);
        return pos != null && restoreBrokenBlockClientSide(pos);
    }

    public static boolean shouldAllowRestoreUpdate(BlockPos pos) {
        if (pos == null) return false;
        if (!restoreOnNextServerUpdate.remove(pos)) return false;
        brokenBlocks.remove(pos);
        brokenBlockStates.remove(pos);
        damageProgress.remove(pos);
        return true;
    }

    private static boolean restoreBrokenBlockClientSide(BlockPos pos) {
        if (pos == null) return false;
        IBlockState state = brokenBlockStates.get(pos);
        if (state == null || state.getBlock() == null || state.getBlock().getMaterial().isReplaceable()) {
            restoreOnNextServerUpdate.add(new BlockPos(pos));
            return true;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null) return false;
            brokenBlocks.remove(pos);
            brokenBlockStates.remove(pos);
            damageProgress.remove(pos);
            restoreOnNextServerUpdate.remove(pos);
            mc.theWorld.setBlockState(pos, state, 3);
            mc.theWorld.markBlockRangeForRenderUpdate(pos, pos);
            return true;
        } catch (Throwable t) {
            System.err.println("[FakeBreak] Restore error: " + t.getMessage());
            return false;
        }
    }

    private static BlockPos findTargetedBrokenBlock(Minecraft mc) {
        Vec3 start = mc.thePlayer.getPositionEyes(1.0F);
        Vec3 look = mc.thePlayer.getLook(1.0F);
        double reach = 5.0D;
        try {
            if (mc.playerController != null) {
                reach = Math.max(3.0D, mc.playerController.getBlockReachDistance());
            }
        } catch (Throwable ignored) {
        }
        Vec3 end = start.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach);
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (BlockPos pos : new HashSet<BlockPos>(brokenBlocks)) {
            AxisAlignedBB box = new AxisAlignedBB(pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX() + 1.0D, pos.getY() + 1.0D, pos.getZ() + 1.0D).expand(0.002D, 0.002D, 0.002D);
            MovingObjectPosition hit = box.calculateIntercept(start, end);
            if (hit == null || hit.hitVec == null) continue;
            double distSq = start.squareDistanceTo(hit.hitVec);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = pos;
            }
        }
        return best;
    }

    public static void registerClientEntity(net.minecraft.entity.Entity e) {
        if (e != null) clientEntityIds.add(e.getEntityId());
    }

    public static boolean isClientEntity(int id) {
        return clientEntityIds.contains(id);
    }

    public static void unregisterClientEntity(int id) {
        clientEntityIds.remove(id);
    }

    public static void startGhostView(net.minecraft.entity.Entity ghost, int ticks) {
        ensureInitialized();
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null) return;
            registerClientEntity(ghost);
            ghostEntityId = ghost.getEntityId();
            ghostTicks = Math.max(1, ticks);
            mc.setRenderViewEntity(ghost);
        } catch (Throwable ignored) {}
    }

    public static void watchPearl(int entityId) {
        pearlWatchTicks.put(entityId, 0);
        pearlLastPos.remove(entityId);
    }

    public static boolean isPlacedBlock(BlockPos pos) {
        return enabled && placedBlocks.containsKey(pos);
    }

    public static void removePlacedBlock(BlockPos pos) {
        placedBlocks.remove(pos);
        placedOriginalStates.remove(pos);
    }

    public static IBlockState getPlacedState(BlockPos pos) {
        return placedBlocks.get(pos);
    }

    // ---------------- Breaking progression ----------------
    /**
     * Increments break progress by hardness amount (0..1 per full break).
     * Returns true if finished (>=1.0).
     */
    public static boolean incrementBreakProgress(BlockPos pos, float hardnessIncrement) {
        if (!enabled) return false;
        float current = damageProgress.getOrDefault(pos, 0.0f);
        float next = current + Math.max(0f, hardnessIncrement);
        if (next >= 1.0f) {
            damageProgress.remove(pos);
            return true;
        }
        damageProgress.put(new BlockPos(pos), next);
        return false;
    }

    /**
     * 0..9 visual break stage for current progress
     */
    public static int getBreakStage(BlockPos pos) {
        float p = damageProgress.getOrDefault(pos, 0.0f);
        int stage = (int)Math.floor(p * 10.0f) - 1; // 0..9
        if (stage < 0) stage = 0;
        if (stage > 9) stage = 9;
        return stage;
    }

    // ---------------- Client item tracking ----------------
    public static void registerClientDrop(EntityItem entityItem) {
        if (entityItem == null) return;
        clientItemEntityIds.add(entityItem.getEntityId());
        System.out.println("[FakeBreak] Registered client item: EntityID " + entityItem.getEntityId());
    }
    
    public static boolean isClientItem(int entityId) {
        return clientItemEntityIds.contains(entityId);
    }
    
    public static void unregisterClientItem(int entityId) {
        clientItemEntityIds.remove(entityId);
    }
    
    public static void recordCollected(ItemStack stack) {
        if (stack == null) return;
        // Merge by item + damage
        for (ItemStack s : collectedClientItems) {
            if (itemsEqual(s, stack)) {
                s.stackSize += stack.stackSize;
                return;
            }
        }
        collectedClientItems.add(stack.copy());
    }

    public static boolean isCollectedClientItem(ItemStack stack) {
        if (stack == null) return false;
        for (ItemStack s : collectedClientItems) {
            if (itemsEqual(s, stack)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isClientProvidedStack(ItemStack stack) {
        return stack != null
            && stack.hasTagCompound()
            && stack.getTagCompound().getBoolean("gomod_client");
    }

    private static boolean itemsEqual(ItemStack a, ItemStack b) {
        if (a == null || b == null) return false;
        if (a.getItem() != b.getItem()) return false;
        return a.getMetadata() == b.getMetadata();
    }

    // ---------------- Fixed break time ----------------
    public static void setFixedBreakTime(float seconds) {
        ensureInitialized();
        fixedBreakTime = seconds;
    }

    public static float getFixedBreakTime() {
        return fixedBreakTime;
    }
    
    public static void clearCollectedFromInventory() {
        ensureInitialized();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;
        try {
            for (ItemStack target : new ArrayList<>(collectedClientItems)) {
                int remaining = target.stackSize;
                // Iterate main inventory
                for (int i = 0; i < mc.thePlayer.inventory.mainInventory.length && remaining > 0; i++) {
                    ItemStack slot = mc.thePlayer.inventory.mainInventory[i];
                    if (slot == null) continue;
                    if (!itemsEqual(slot, target)) continue;
                    int remove = Math.min(remaining, slot.stackSize);
                    slot.stackSize -= remove;
                    remaining -= remove;
                    if (slot.stackSize <= 0) {
                        mc.thePlayer.inventory.mainInventory[i] = null;
                    }
                }
            }
        } catch (Throwable ignored) {}
        collectedClientItems.clear();
        sendMessage(me.napim.gomod.Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Cleared client-side fake drops from inventory");
    }
}

