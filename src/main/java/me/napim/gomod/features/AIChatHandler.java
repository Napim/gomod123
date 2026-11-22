package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.weavemc.loader.api.event.PacketEvent;
import net.minecraft.network.play.client.C01PacketChatMessage;
import net.minecraft.network.play.server.S02PacketChat;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Arrays;

public class AIChatHandler {
    private static boolean enabled = true; // Toggle for @gostats123 functionality
    private static boolean initialized = false;
    
    public AIChatHandler() {
        initialize();
    }
    
    /**
     * Initializes the AIChatHandler with saved settings from ConfigManager.
     */
    private static void initialize() {
        if (initialized) {
            return;
        }
        
        // Load setting from ConfigManager
        enabled = ConfigManager.isGoStatsEnabled();
        initialized = true;
    }

    /**
     * Toggles the @gostats123 functionality on or off.
     * 
     * @param value True to enable, false to disable
     * @return The new state
     */
    public static boolean setEnabled(boolean value) {
        if (!initialized) {
            initialize();
        }
        
        enabled = value;
        
        // Save to ConfigManager
        ConfigManager.setGoStatsEnabled(value);
        
        return enabled;
    }
    
    /**
     * Gets the current state of the @gostats123 functionality.
     * 
     * @return True if enabled, false if disabled
     */
    public static boolean isEnabled() {
        if (!initialized) {
            initialize();
        }
        
        return enabled;
    }

    /**
     * Extracts GoStats requests only from the local player's outgoing chat.
     * Received public chat is never a trigger because every viewer sees it.
     */
    @SubscribeEvent
    public void onOutgoingChat(PacketEvent.Send event) {
        try {
            if (!enabled || event == null || !(event.getPacket() instanceof C01PacketChatMessage)) return;
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null) return;

            String outgoing = ((C01PacketChatMessage) event.getPacket()).getMessage();
            GoStatsChatRequest request = GoStatsChatRequest.parseOutgoing(outgoing);
            if (request != null) {
                getChatGPTResponse(request.question, request.privateReply);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SubscribeEvent
    public void onChat(PacketEvent.Receive event) {
        try {
            if (event == null || !(event.getPacket() instanceof S02PacketChat)) return;
            // Check for spectator error and retry without /shout
            if (!enabled) return;
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null) return;
            S02PacketChat packet = (S02PacketChat) event.getPacket();
            String message = packet.getChatComponent().getUnformattedText();
            if (message.contains("You are not allowed to use that command as a spectator!")) {
                String originalMessage = message.replace("You are not allowed to use that command as a spectator!", "").trim();
                if (originalMessage.startsWith("/shout ")) {
                    originalMessage = originalMessage.substring(7).trim();
                    if (!originalMessage.isEmpty()) {
                        Minecraft.getMinecraft().thePlayer.sendChatMessage(originalMessage);
                    }
                }
                return;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void getChatGPTResponse(String message, boolean isPrivateMessage) {
        new Thread(() -> {
            try {
                JsonObject response = GoStatsRelayClient.ask(message, getCurrentPlayerInfo());
                String aiResponse = response.get("reply").getAsString().trim();
                String formattedResponse = "<gostats123> " + aiResponse;
                if (isPrivateMessage) {
                    sendPrivateMessage(EnumChatFormatting.LIGHT_PURPLE + "[AI] " + EnumChatFormatting.WHITE + formattedResponse);
                } else if (formattedResponse.length() > 100) {
                    for (String msgPart : splitResponseIntoMessages(formattedResponse)) {
                        Minecraft mc = Minecraft.getMinecraft();
                        if (mc == null || mc.thePlayer == null) return;
                        mc.thePlayer.sendChatMessage(msgPart);
                        try {
                            Thread.sleep(300L);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                } else {
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc != null && mc.thePlayer != null) mc.thePlayer.sendChatMessage(formattedResponse);
                }
            } catch (Exception e) {
                String error = e instanceof GoStatsRelayClient.UsageException
                        ? e.getMessage() : "gostats123 relay unavailable";
                sendPrivateMessage(me.napim.gomod.Main.CHAT_PREFIX + EnumChatFormatting.RED + error);
            }
        }).start();
    }
    
    private void sendPrivateMessage(String message) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer != null) mc.thePlayer.addChatMessage(new ChatComponentText(message));
    }

    private String getCurrentPlayerInfo() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null) return "No player information available";

            StringBuilder info = new StringBuilder();
            
            // Information about the current player
            info.append("Your player: ").append(mc.thePlayer.getName());
            info.append(", Health: ").append(mc.thePlayer.getHealth()).append("HP");
            info.append(", XP level: ").append(mc.thePlayer.experienceLevel);
            info.append(", Food: ").append(mc.thePlayer.getFoodStats().getFoodLevel()).append("/20");
            
            // Player state information
            if (mc.thePlayer.capabilities.isFlying) info.append(", Flying");
            if (mc.thePlayer.isSprinting()) info.append(", Sprinting");
            if (mc.thePlayer.isSneaking()) info.append(", Sneaking");
            
            // Current equipment - enhanced with full armor details
            info.append(". Your equipment: ");
            
            // Main hand item with count and durability
            if (mc.thePlayer.getCurrentEquippedItem() != null) {
                info.append("Main hand: ").append(formatItemWithDetails(mc.thePlayer.getCurrentEquippedItem()));
            } else {
                info.append("Main hand: empty");
            }
            
            // Armor details
            info.append(". Armor: ");
            String[] armorSlots = {"Helmet", "Chestplate", "Leggings", "Boots"};
            boolean hasArmor = false;
            
            for (int i = 0; i < 4; i++) {
                if (mc.thePlayer.inventory.armorInventory[3-i] != null) {
                    if (hasArmor) info.append(", ");
                    info.append(armorSlots[i]).append(": ")
                        .append(formatItemWithDetails(mc.thePlayer.inventory.armorInventory[3-i]));
                    hasArmor = true;
                }
            }
            
            if (!hasArmor) {
                info.append("none");
            }
            
            // Selected inventory items (count of important items)
            info.append(". Inventory highlights: ");
            Map<String, Integer> importantItems = getImportantItemCounts(mc.thePlayer.inventory);
            if (importantItems.isEmpty()) {
                info.append("no notable items");
            } else {
                List<String> itemStrings = new ArrayList<>();
                for (Map.Entry<String, Integer> entry : importantItems.entrySet()) {
                    itemStrings.add(entry.getKey() + " x" + entry.getValue());
                }
                info.append(String.join(", ", itemStrings));
            }
            
            // Current game mode
            info.append(". Game mode: ").append(mc.thePlayer.capabilities.isCreativeMode ? "Creative" : "Survival");
            
            // Other players information - enhanced with equipment details when possible
            info.append(". Other players: ");
            
            List<String> playerDetails = new ArrayList<>();
            mc.theWorld.playerEntities.forEach(player -> {
                if (player != mc.thePlayer) {  // Skip the current player
                    try {
                        StringBuilder playerInfo = new StringBuilder();
                        playerInfo.append(player.getName());
                        
                        // Calculate distance
                        double distanceSq = mc.thePlayer.getDistanceSqToEntity(player);
                        double distance = Math.sqrt(distanceSq);
                        playerInfo.append(" (").append(Math.round(distance)).append(" blocks away");
                        
                        // Direction information (simplified)
                        double dx = player.posX - mc.thePlayer.posX;
                        double dz = player.posZ - mc.thePlayer.posZ;
                        String direction = getCardinalDirection(dx, dz);
                        playerInfo.append(", ").append(direction).append(" direction");
                        
                        // Add vertical position information (important for Blitz SG)
                        double dy = player.posY - mc.thePlayer.posY;
                        if (Math.abs(dy) > 2) { // Only mention if significant height difference
                            playerInfo.append(", ").append(dy > 0 ? Math.round(dy) + " blocks above" : Math.abs(Math.round(dy)) + " blocks below");
                        }
                        
                        // Health if visible - using HP
                        playerInfo.append(", health: ").append(player.getHealth()).append("HP");
                        
                        // Add their held item if visible
                        net.minecraft.entity.player.EntityPlayer entityPlayer = (net.minecraft.entity.player.EntityPlayer)player;
                        if (entityPlayer.getCurrentEquippedItem() != null) {
                            playerInfo.append(", holding: ")
                                     .append(stripFormatting(entityPlayer.getCurrentEquippedItem().getDisplayName()));
                        }
                        
                        // Armor quality assessment (important for Blitz SG)
                        playerInfo.append(", armor: ").append(assessArmorQuality(entityPlayer));
                        
                        // Check for potion effects (important in Blitz SG)
                        List<String> activeEffects = getActiveEffects(entityPlayer);
                        if (!activeEffects.isEmpty()) {
                            playerInfo.append(", effects: ").append(String.join(", ", activeEffects));
                        }
                        
                        // Check if the player appears to be in combat
                        if (entityPlayer.hurtTime > 0) {
                            playerInfo.append(", in combat");
                        }
                        
                        // Check if player is sneaking/crouching
                        if (entityPlayer.isSneaking()) {
                            playerInfo.append(", sneaking");
                        }
                        
                        playerInfo.append(")");
                        playerDetails.add(playerInfo.toString());
                    } catch (Exception e) {
                        // Skip this player if there's an error
                        playerDetails.add(player.getName() + " (error getting details)");
                    }
                }
            });
            
            if (playerDetails.isEmpty()) {
                info.append("none nearby");
            } else {
                info.append(String.join(", ", playerDetails));
            }
            
            return info.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return "Error getting player information";
        }
    }
    
    /**
     * Formats an item with additional details (count, durability if applicable)
     */
    private String formatItemWithDetails(net.minecraft.item.ItemStack item) {
        if (item == null) return "none";
        
        StringBuilder result = new StringBuilder();
        // Strip any formatting codes from the item name
        result.append(stripFormatting(item.getDisplayName()));
        
        // Add count if more than 1
        if (item.stackSize > 1) {
            result.append(" x").append(item.stackSize);
        }
        
        // Add durability info for tools/weapons/armor
        if (item.isItemStackDamageable() && item.getMaxDamage() > 0) {
            int durabilityPercent = Math.round(100f * (item.getMaxDamage() - item.getItemDamage()) / item.getMaxDamage());
            result.append(" (").append(durabilityPercent).append("% durability)");
        }
        
        // Add enchantment info if applicable
        if (item.isItemEnchanted()) {
            result.append(" [enchanted]");
        }
        
        return result.toString();
    }
    
    /**
     * Gets counts of important items in the player's inventory
     */
    private Map<String, Integer> getImportantItemCounts(net.minecraft.entity.player.InventoryPlayer inventory) {
        Map<String, Integer> itemCounts = new HashMap<>();
        
        // Define important items to track
        List<String> importantItems = Arrays.asList(
            "diamond", "gold", "iron", "ender pearl", "potion", "golden apple",
            "arrow", "bow", "sword", "firework", "tnt", "egg", "bed"
        );
        
        // Count main inventory
        for (int i = 0; i < inventory.mainInventory.length; i++) {
            net.minecraft.item.ItemStack stack = inventory.mainInventory[i];
            if (stack != null) {
                String rawItemName = stack.getDisplayName();
                String itemName = stripFormatting(rawItemName).toLowerCase();
                for (String important : importantItems) {
                    if (itemName.contains(important)) {
                        // Use the stripped name for the map key
                        String cleanName = stripFormatting(rawItemName);
                        itemCounts.put(cleanName, 
                            itemCounts.getOrDefault(cleanName, 0) + stack.stackSize);
                        break;
                    }
                }
            }
        }
        
        return itemCounts;
    }
    
    /**
     * Gets the cardinal direction based on relative x and z coordinates.
     */
    private String getCardinalDirection(double dx, double dz) {
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? "east" : "west";
        } else {
            return dz > 0 ? "south" : "north";
        }
    }
    
    /**
     * Strips Minecraft formatting codes (§ and &) from text
     * This prevents issues with chat colors in AI responses
     */
    private String stripFormatting(String text) {
        if (text == null) return "";
        
        // Remove both § and & formatting codes
        // The pattern is § or & followed by one of the Minecraft format codes (0-9, a-f, k-o, r)
        return text.replaceAll("(?i)[§&][0-9a-fk-or]", "");
    }

    /**
     * Assesses the armor quality of a player based on their armor items
     * This is useful for Blitz SG to quickly determine threat level
     */
    private String assessArmorQuality(net.minecraft.entity.player.EntityPlayer player) {
        try {
            int armorValue = player.getTotalArmorValue();
            
            // Get material counts
            int diamondCount = 0;
            int ironCount = 0;
            int goldCount = 0;
            int chainCount = 0;
            int leatherCount = 0;
            
            for (int i = 0; i < 4; i++) {
                if (player.inventory.armorInventory[i] != null) {
                    String armorName = stripFormatting(player.inventory.armorInventory[i].getDisplayName()).toLowerCase();
                    if (armorName.contains("diamond")) diamondCount++;
                    else if (armorName.contains("iron")) ironCount++;
                    else if (armorName.contains("gold") || armorName.contains("golden")) goldCount++;
                    else if (armorName.contains("chain")) chainCount++;
                    else if (armorName.contains("leather")) leatherCount++;
                }
            }
            
            // Determine the primary armor material
            if (diamondCount >= 2) return diamondCount + " diamond pieces (" + armorValue + " points)";
            else if (diamondCount == 1) return "partial diamond (" + armorValue + " points)";
            else if (ironCount >= 3) return "full iron (" + armorValue + " points)";
            else if (ironCount >= 1) return "partial iron (" + armorValue + " points)";
            else if (chainCount + goldCount + leatherCount > 0) return "weak armor (" + armorValue + " points)";
            else return "no armor";
        } catch (Exception e) {
            return "unknown";
        }
    }
    
    /**
     * Gets a list of active potion effects on a player
     * Very useful in Blitz SG where potions/effects provide major advantages
     */
    private List<String> getActiveEffects(net.minecraft.entity.player.EntityPlayer player) {
        List<String> effects = new ArrayList<>();
        try {
            if (player.isPotionActive(1)) effects.add("speed");
            if (player.isPotionActive(5)) effects.add("strength");
            if (player.isPotionActive(8)) effects.add("jump boost");
            if (player.isPotionActive(10)) effects.add("regeneration");
            if (player.isPotionActive(11)) effects.add("resistance");
            if (player.isPotionActive(12)) effects.add("fire resistance");
            if (player.isPotionActive(14)) effects.add("invisibility");
            if (player.isPotionActive(16)) effects.add("night vision");
            if (player.isPotionActive(22)) effects.add("absorption");
            
            // Negative effects
            if (player.isPotionActive(2)) effects.add("slowness");
            if (player.isPotionActive(4)) effects.add("mining fatigue");
            if (player.isPotionActive(9)) effects.add("nausea");
            if (player.isPotionActive(15)) effects.add("blindness");
            if (player.isPotionActive(18)) effects.add("weakness");
            if (player.isPotionActive(19)) effects.add("poison");
            if (player.isPotionActive(20)) effects.add("wither");
            
            // Check if the player is on fire
            if (player.isBurning()) effects.add("burning");
        } catch (Exception e) {
            // Ignore errors when checking potion effects
        }
        return effects;
    }

    /**
     * Splits a long response into multiple messages strictly under 100 characters each.
     * Splits by words to ensure no cut-offs mid-word.
     * Limits to a maximum of 3 messages.
     */
    private List<String> splitResponseIntoMessages(String response) {
        List<String> messages = new ArrayList<>();
        String prefix = "<gostats123> ";
        int maxLength = 100;
        
        // Remove the prefix for processing if it exists
        String content = response;
        if (content.startsWith(prefix)) {
            content = content.substring(prefix.length());
        }
        
        String[] words = content.split(" ");
        StringBuilder currentMessage = new StringBuilder(prefix);
        
        for (String word : words) {
            // Check if adding the word would exceed the limit (including the space we'll add)
            if (currentMessage.length() + word.length() + 1 > maxLength) {
                // Current message is full
                messages.add(currentMessage.toString().trim());
                
                // Stop if we hit 3 messages
                if (messages.size() >= 3) {
                    return messages;
                }
                
                currentMessage = new StringBuilder(prefix);
                
                // Handle case where a single word is too long (e.g. URL)
                if (currentMessage.length() + word.length() > maxLength) {
                    String remainingWord = word;
                    while (remainingWord.length() > 0) {
                        int spaceLeft = maxLength - currentMessage.length();
                        if (spaceLeft <= 0) {
                            messages.add(currentMessage.toString().trim());
                            if (messages.size() >= 3) return messages;
                            currentMessage = new StringBuilder(prefix);
                            spaceLeft = maxLength - currentMessage.length();
                        }
                        
                        int charsToTake = Math.min(spaceLeft, remainingWord.length());
                        currentMessage.append(remainingWord.substring(0, charsToTake));
                        remainingWord = remainingWord.substring(charsToTake);
                        
                        // If we filled the message, push it
                        if (currentMessage.length() >= maxLength) {
                             messages.add(currentMessage.toString().trim());
                             if (messages.size() >= 3) return messages;
                             currentMessage = new StringBuilder(prefix);
                        }
                    }
                    currentMessage.append(" ");
                } else {
                    currentMessage.append(word).append(" ");
                }
            } else {
                currentMessage.append(word).append(" ");
            }
        }
        
        // Add the last message if not empty and limit not reached
        if (currentMessage.length() > prefix.length() && messages.size() < 3) {
            messages.add(currentMessage.toString().trim());
        }
        
        return messages;
    }
}
