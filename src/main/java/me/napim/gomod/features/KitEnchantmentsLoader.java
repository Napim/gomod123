package me.napim.gomod.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads kit enchantment data from kitsjson directory
 */
public class KitEnchantmentsLoader {
    private static final Map<String, JsonObject> kitDataCache = new HashMap<>();
    
    /**
     * Gets enchantments for a specific slot in a kit at a given level
     * @param kitName The kit name (e.g., "Knight", "Scout")
     * @param kitLevel The kit level (1-12, or prestige)
     * @param slotType The slot type: "hand", "helmet", "chestplate", "leggings", "boots"
     * @return List of enchantments like ["Protection IV", "Unbreaking III"], or null if not found
     */
    public static List<String> getEnchantmentsForSlot(String kitName, String kitLevel, String slotType) {
        try {
            System.out.println("[KitEnchantmentsLoader] Input - kitName: '" + kitName + "', kitLevel: '" + kitLevel + "', slot: '" + slotType + "'");
            
            // Normalize kit name (lowercase, remove spaces/special chars)
            String normalizedKit = kitName.toLowerCase()
                .replaceAll("\\s+", "")
                .replaceAll("[^a-z0-9]", "");
            
            System.out.println("[KitEnchantmentsLoader] Normalized kit: '" + normalizedKit + "'");
            
            // Parse level (extract number from "Knight VI" -> "6")
            // Returns -1 for uncertain prestige (single star due to Hypixel bug)
            int level = parseKitLevel(kitLevel);
            System.out.println("[KitEnchantmentsLoader] Parsed level: " + level);
            
            if (level == 0) {
                System.out.println("[KitEnchantmentsLoader] Invalid level, returning null");
                return null;
            }
            
            // Load kit JSON
            JsonObject kitData = loadKitData(normalizedKit);
            if (kitData == null) {
                System.out.println("[KitEnchantmentsLoader] Failed to load kit data for: " + normalizedKit);
                return null;
            }
            System.out.println("[KitEnchantmentsLoader] Loaded kit data successfully");
            
            // Handle uncertain prestige (level == -1)
            // Due to Hypixel bug, single star could be Prestige I or II
            if (level == -1) {
                System.out.println("[KitEnchantmentsLoader] Uncertain prestige detected, comparing levels 11 and 12");
                return getEnchantsForUncertainPrestige(kitData, slotType);
            }
            
            // Get the level object
            JsonElement levelElement = kitData.get(String.valueOf(level));
            if (levelElement == null || !levelElement.isJsonObject()) {
                System.out.println("[KitEnchantmentsLoader] No data found for level: " + level);
                return null;
            }
            
            System.out.println("[KitEnchantmentsLoader] Found level data for level: " + level);
            JsonObject levelData = levelElement.getAsJsonObject();
            
            // Search in items array or attackslot array based on slot type
            List<String> enchantments = new ArrayList<>();
            
            if (slotType.equals("hand")) {
                // Search in attackslot
                System.out.println("[KitEnchantmentsLoader] Searching attackslot for hand item");
                if (levelData.has("attackslot")) {
                    JsonArray attackslot = levelData.getAsJsonArray("attackslot");
                    if (attackslot.size() > 0 && attackslot.get(0).isJsonObject()) {
                        JsonObject item = attackslot.get(0).getAsJsonObject();
                        enchantments = extractEnchantments(item);
                        System.out.println("[KitEnchantmentsLoader] Found " + enchantments.size() + " enchantments in attackslot");
                    }
                }
            } else {
                // Search in items array for armor pieces
                System.out.println("[KitEnchantmentsLoader] Searching items array for " + slotType);
                if (levelData.has("items")) {
                    JsonArray items = levelData.getAsJsonArray("items");
                    System.out.println("[KitEnchantmentsLoader] Items array has " + items.size() + " items");
                    for (JsonElement itemElement : items) {
                        if (itemElement.isJsonObject()) {
                            JsonObject item = itemElement.getAsJsonObject();
                            if (item.has("type")) {
                                String itemType = item.get("type").getAsString();
                                System.out.println("[KitEnchantmentsLoader] Found item with type: " + itemType);
                                if (itemType.equals(slotType)) {
                                    enchantments = extractEnchantments(item);
                                    System.out.println("[KitEnchantmentsLoader] Match! Found " + enchantments.size() + " enchantments");
                                    break;
                                }
                            }
                        }
                    }
                }
            }
            
            System.out.println("[KitEnchantmentsLoader] Returning " + (enchantments.isEmpty() ? "null" : enchantments.size() + " enchantments"));
            return enchantments.isEmpty() ? null : enchantments;
            
        } catch (Exception e) {
            System.out.println("[KitEnchantmentsLoader] Error getting enchantments: " + e.getMessage());
            return null;
        }
    }

    public static JsonObject getKitData(String kitName) {
        try {
            if (kitName == null || kitName.trim().isEmpty()) {
                return null;
            }
            return loadKitData(kitName.trim());
        } catch (Exception ignored) {
            return null;
        }
    }
    
    /**
     * Extracts enchantments array from an item JSON object
     */
    private static List<String> extractEnchantments(JsonObject item) {
        List<String> enchantments = new ArrayList<>();
        if (item.has("enchantments")) {
            JsonArray enchArray = item.getAsJsonArray("enchantments");
            for (JsonElement ench : enchArray) {
                enchantments.add(ench.getAsString());
            }
        }
        return enchantments;
    }
    
    /**
     * Compares Prestige I (level 11) and Prestige II (level 12) enchantments for a slot.
     * Due to Hypixel bug, single star can be either prestige.
     * If enchantments are the same, return them. Otherwise return null (show ???).
     */
    private static List<String> getEnchantsForUncertainPrestige(JsonObject kitData, String slotType) {
        try {
            // Get enchantments for both prestige levels
            List<String> prestige1Enchants = getEnchantsFromLevel(kitData, "11", slotType);
            List<String> prestige2Enchants = getEnchantsFromLevel(kitData, "12", slotType);
            
            System.out.println("[KitEnchantmentsLoader] Prestige I enchants: " + prestige1Enchants);
            System.out.println("[KitEnchantmentsLoader] Prestige II enchants: " + prestige2Enchants);
            
            // If both are null or empty, return null
            if ((prestige1Enchants == null || prestige1Enchants.isEmpty()) && 
                (prestige2Enchants == null || prestige2Enchants.isEmpty())) {
                System.out.println("[KitEnchantmentsLoader] Both prestige levels have no enchants");
                return null;
            }
            
            // If one is null/empty, use the other
            if (prestige1Enchants == null || prestige1Enchants.isEmpty()) {
                return prestige2Enchants;
            }
            if (prestige2Enchants == null || prestige2Enchants.isEmpty()) {
                return prestige1Enchants;
            }
            
            // Compare enchantments - if they're identical, it's safe to show them
            if (prestige1Enchants.size() == prestige2Enchants.size() && 
                prestige1Enchants.containsAll(prestige2Enchants)) {
                System.out.println("[KitEnchantmentsLoader] Enchants are identical between prestige levels - showing them");
                return prestige1Enchants;
            }
            
            // Enchantments differ - return null to show ???
            System.out.println("[KitEnchantmentsLoader] Enchants differ between prestige levels - returning null for ???");
            return null;
            
        } catch (Exception e) {
            System.out.println("[KitEnchantmentsLoader] Error comparing prestige enchants: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Helper method to get enchantments from a specific level in kit data
     */
    private static List<String> getEnchantsFromLevel(JsonObject kitData, String level, String slotType) {
        try {
            JsonElement levelElement = kitData.get(level);
            if (levelElement == null || !levelElement.isJsonObject()) {
                return null;
            }
            
            JsonObject levelData = levelElement.getAsJsonObject();
            
            if (slotType.equals("hand")) {
                if (levelData.has("attackslot")) {
                    JsonArray attackslot = levelData.getAsJsonArray("attackslot");
                    if (attackslot.size() > 0 && attackslot.get(0).isJsonObject()) {
                        return extractEnchantments(attackslot.get(0).getAsJsonObject());
                    }
                }
            } else {
                if (levelData.has("items")) {
                    JsonArray items = levelData.getAsJsonArray("items");
                    for (JsonElement itemElement : items) {
                        if (itemElement.isJsonObject()) {
                            JsonObject item = itemElement.getAsJsonObject();
                            if (item.has("type") && item.get("type").getAsString().equals(slotType)) {
                                return extractEnchantments(item);
                            }
                        }
                    }
                }
            }
            
            return null;
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Parses kit level from various formats:
     * "VI" -> 6
     * "Scout VII" -> 7
     * "Knight prestige" -> 12 (prestige)
     */
    private static int parseKitLevel(String kitLevel) {
        try {
            // If the input is already just Roman numerals, don't modify it
            String levelStr = kitLevel.trim();
            
            // Only remove kit name prefix if it contains non-Roman numeral letters
            // Roman numerals only use: I, V, X (and stars for prestige)
            // So if it starts with other letters, remove them
            levelStr = levelStr.replaceAll("^[A-Za-z&&[^IVX]]+\\s*", "");
            
            System.out.println("[KitEnchantmentsLoader] Checking for prestige in: '" + levelStr + "'");
            for (int i = 0; i < levelStr.length(); i++) {
                System.out.println("[KitEnchantmentsLoader] Char[" + i + "]: '" + levelStr.charAt(i) + "' (Unicode: " + ((int)levelStr.charAt(i)) + ")");
            }
            
            // Handle "P1" and "P2" format from formatKitLevel
            // P1 means single star - uncertain due to Hypixel bug
            if (levelStr.equalsIgnoreCase("P1") || levelStr.equalsIgnoreCase("Prestige1") || 
                levelStr.equalsIgnoreCase("Prestige I") || levelStr.equalsIgnoreCase("PrestigeI")) {
                System.out.println("[KitEnchantmentsLoader] Detected P1 format -> uncertain prestige");
                return -1; // Uncertain due to Hypixel bug
            }
            // P2 means double star - certain Prestige II
            if (levelStr.equalsIgnoreCase("P2") || levelStr.equalsIgnoreCase("Prestige2") || 
                levelStr.equalsIgnoreCase("Prestige II") || levelStr.equalsIgnoreCase("PrestigeII")) {
                System.out.println("[KitEnchantmentsLoader] Detected P2 format -> level 12 (certain)");
                return 12; // Certain Prestige II
            }
            
            // Count star characters (support multiple Unicode star symbols)
            // \u2B50 \u2605 \u272B \u2726 \u2606 \u2B51
            int starCount = 0;
            starCount += countOccurrences(levelStr, "\u2B50");
            starCount += countOccurrences(levelStr, "\u2605");
            starCount += countOccurrences(levelStr, "\u272B");
            starCount += countOccurrences(levelStr, "\u2726");
            starCount += countOccurrences(levelStr, "\u2606");
            starCount += countOccurrences(levelStr, "\u2B51");
            
            System.out.println("[KitEnchantmentsLoader] Star count: " + starCount);
            
            if (starCount >= 2) {
                System.out.println("[KitEnchantmentsLoader] Detected Prestige II (level 12) - double star");
                return 12; // Double prestige - certain
            } else if (starCount >= 1) {
                System.out.println("[KitEnchantmentsLoader] Detected single star - uncertain prestige (Hypixel bug)");
                return -1; // Single prestige - uncertain due to Hypixel bug
            }
            
            // Parse roman numerals
            return romanToInt(levelStr);
        } catch (Exception e) {
            return 0;
        }
    }
    
    /**
     * Helper method to count occurrences of a string
     */
    private static int countOccurrences(String text, String search) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(search, index)) != -1) {
            count++;
            index += search.length();
        }
        return count;
    }
    
    /**
     * Converts roman numerals to integers
     */
    private static int romanToInt(String roman) {
        roman = roman.trim().toUpperCase();
        Map<Character, Integer> romanMap = new HashMap<>();
        romanMap.put('I', 1);
        romanMap.put('V', 5);
        romanMap.put('X', 10);
        
        int result = 0;
        int prevValue = 0;
        
        for (int i = roman.length() - 1; i >= 0; i--) {
            char c = roman.charAt(i);
            if (!romanMap.containsKey(c)) {
                return 0; // Invalid roman numeral
            }
            
            int value = romanMap.get(c);
            if (value < prevValue) {
                result -= value;
            } else {
                result += value;
            }
            prevValue = value;
        }
        
        return result;
    }
    
    /**
     * Loads kit data from JSON file
     */
    private static JsonObject loadKitData(String kitName) {
        // Check cache first
        if (kitDataCache.containsKey(kitName)) {
            return kitDataCache.get(kitName);
        }
        
        try {
            // Try different naming conventions
            String[] possibleNames = {
                kitName,
                kitName.replace(" ", ""),
                kitName.toLowerCase()
            };
            
            for (String name : possibleNames) {
                InputStream stream = KitEnchantmentsLoader.class.getResourceAsStream("/kitsjson/" + name + ".json");
                if (stream != null) {
                    JsonParser parser = new JsonParser();
                    JsonObject data = parser.parse(new InputStreamReader(stream)).getAsJsonObject();
                    kitDataCache.put(kitName, data);
                    stream.close();
                    return data;
                }
            }
        } catch (Exception e) {
            System.out.println("[KitEnchantmentsLoader] Error loading kit data for '" + kitName + "': " + e.getMessage());
        }
        
        return null;
    }
}
