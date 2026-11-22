package me.napim.gomod.mixins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.network.NetworkPlayerInfo;
import me.napim.gomod.features.ChatMovement;
import me.napim.gomod.util.CommandFallbackDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Mixin(GuiChat.class)
public abstract class GuiChatMixin {
    
    private List<String> tabCompletionCache = new ArrayList<>();
    private int tabCompletionIndex = 0;
    private String tabCompletionPrefix = "";
    private static Field textFieldCache = null;
    private String armorCompletionResult = "";
    private List<String> armorCompletions = new ArrayList<>();
    private int armorCompletionIndex;
    
    /**
     * Intercept key typed events to handle tab completion for player names
     * Works for any command starting with /
     */
    @Inject(method = "keyTyped", at = @At("HEAD"), cancellable = true)
    private void onKeyTyped(char typedChar, int keyCode, CallbackInfo ci) {
        if (ChatMovement.shouldConsumeChatKey(keyCode)) {
            ci.cancel();
            return;
        }
        // Check if TAB key was pressed (keyCode 15)
        if (keyCode == 15) {
            String text = getInputField();
            if (text == null || text.isEmpty()) return;
            String armorCommand = text.split(" ", 2)[0];
            if (me.napim.gomod.GomodInternalFeatures.isEnabled()
                    && (armorCommand.equalsIgnoreCase("/disguisergomodarmor") || armorCommand.equalsIgnoreCase("/disguiserarmor"))) {
                if (!text.equals(armorCompletionResult)) {
                    armorCompletions = me.napim.gomod.command.ArmorDisguiseCommand.complete(text);
                    armorCompletionIndex = 0;
                } else if (!armorCompletions.isEmpty()) armorCompletionIndex = (armorCompletionIndex + 1) % armorCompletions.size();
                if (!armorCompletions.isEmpty()) {
                    armorCompletionResult = armorCompletions.get(armorCompletionIndex);
                    setInputField(armorCompletionResult);
                }
                ci.cancel(); return;
            }
            
            // Enable player name tab completion for any command
            String[] parts = text.split(" ");
            if (parts.length == 0) return;
            
            String command = parts[0].toLowerCase();
            
            // Only enable for commands (starting with /)
            if (!command.startsWith("/")) return;
            
            // Special handling for two-part commands (command + subcommand + player)
            // Examples: /g invite, /f add, /f remove, /party invite, /p invite
            boolean isTwoPartCommand = false;
            String subcommand = "";
            
            if (parts.length >= 2) {
                String cmd = command.toLowerCase();
                String sub = parts[1].toLowerCase();
                
                // Check if this is a recognized two-part command
                if ((cmd.equals("/g") && sub.equals("invite")) ||
                    (cmd.equals("/f") && (sub.equals("add") || sub.equals("remove"))) ||
                    (cmd.equals("/friend") && (sub.equals("add") || sub.equals("remove"))) ||
                    (cmd.equals("/party") && sub.equals("invite")) ||
                    (cmd.equals("/p") && sub.equals("invite"))) {
                    isTwoPartCommand = true;
                    subcommand = parts[1]; // Preserve original case
                }
            }
            
            // Handle tab completion
            if (isTwoPartCommand) {
                // Handle /f add <player>, /g invite <player>, etc.
                if (parts.length == 2 && text.endsWith(" ")) {
                    // First tab after subcommand with trailing space, get all players
                    tabCompletionCache = getOnlinePlayerNames("");
                    tabCompletionIndex = 0;
                    tabCompletionPrefix = "";
                    
                    if (!tabCompletionCache.isEmpty()) {
                        setInputField(command + " " + subcommand + " " + tabCompletionCache.get(0));
                        ci.cancel();
                    }
                } else if (parts.length == 3) {
                    // Tab through player names
                    String currentArg = parts[2];
                    
                    // If this is a new completion or the prefix changed, reset
                    if (!currentArg.equals(tabCompletionPrefix) && !tabCompletionCache.contains(currentArg)) {
                        tabCompletionCache = getOnlinePlayerNames(currentArg);
                        tabCompletionIndex = 0;
                        tabCompletionPrefix = currentArg;
                    }
                    
                    if (!tabCompletionCache.isEmpty()) {
                        // Cycle through completions
                        tabCompletionIndex = (tabCompletionIndex + 1) % tabCompletionCache.size();
                        setInputField(command + " " + subcommand + " " + tabCompletionCache.get(tabCompletionIndex));
                        ci.cancel();
                    }
                }
            } else if (parts.length == 1 && text.endsWith(" ")) {
                // First tab after command with trailing space, get all players
                tabCompletionCache = getOnlinePlayerNames("");
                tabCompletionIndex = 0;
                tabCompletionPrefix = "";
                
                if (!tabCompletionCache.isEmpty()) {
                    setInputField(command + " " + tabCompletionCache.get(0));
                    ci.cancel();
                }
            } else if (parts.length == 2) {
                // Tab through player names
                String currentArg = parts[1];
                
                // If this is a new completion or the prefix changed, reset
                if (!currentArg.equals(tabCompletionPrefix) && !tabCompletionCache.contains(currentArg)) {
                    tabCompletionCache = getOnlinePlayerNames(currentArg);
                    tabCompletionIndex = 0;
                    tabCompletionPrefix = currentArg;
                }
                
                if (!tabCompletionCache.isEmpty()) {
                    // Cycle through completions
                    tabCompletionIndex = (tabCompletionIndex + 1) % tabCompletionCache.size();
                    setInputField(command + " " + tabCompletionCache.get(tabCompletionIndex));
                    ci.cancel();
                }
            }
        } else {
            armorCompletionResult = "";
            armorCompletions.clear();
            // Reset tab completion when any other key is pressed
            tabCompletionCache.clear();
            tabCompletionIndex = 0;
            tabCompletionPrefix = "";
        }
    }

    /**
     * Fallback command dispatcher for environments where ChatSentEvent is not firing.
     */
    // 1.8.9 GuiChat has no (String,Z) overload; optional so this mixin class still applies on Forge.
    @Inject(method = "sendChatMessage(Ljava/lang/String;Z)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void onSendChatMessage(String message, boolean addToChat, CallbackInfo ci) {
        boolean handled = addToChat
                ? CommandFallbackDispatcher.dispatchOutgoingChat(message)
                : CommandFallbackDispatcher.dispatchIfMatched(message);
        if (handled) {
            ci.cancel();
        }
    }
    
    /**
     * Get list of online player names that match the prefix
     */
    private List<String> getOnlinePlayerNames(String prefix) {
        List<String> players = new ArrayList<>();
        Minecraft mc = Minecraft.getMinecraft();
        
        if (mc != null && mc.getNetHandler() != null && mc.getNetHandler().getPlayerInfoMap() != null) {
            String lowerPrefix = prefix.toLowerCase();
            
            for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                if (info != null && info.getGameProfile() != null) {
                    String name = info.getGameProfile().getName();
                    if (name != null && (prefix.isEmpty() || name.toLowerCase().startsWith(lowerPrefix))) {
                        players.add(name);
                    }
                }
            }
        }
        
        Collections.sort(players, String.CASE_INSENSITIVE_ORDER);
        return players;
    }
    
    /**
     * Get the GuiTextField from GuiChat using reflection
     */
    private GuiTextField getTextField() {
        try {
            GuiChat chat = (GuiChat)(Object)this;
            
            // Cache the field for better performance
            if (textFieldCache == null) {
                // Try to find the field - it's usually the only GuiTextField field
                for (Field field : GuiChat.class.getDeclaredFields()) {
                    if (GuiTextField.class.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        textFieldCache = field;
                        break;
                    }
                }
            }
            
            if (textFieldCache != null) {
                return (GuiTextField) textFieldCache.get(chat);
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }
    
    /**
     * Get the current input field text
     */
    private String getInputField() {
        GuiTextField field = getTextField();
        return field != null ? field.getText() : null;
    }
    
    /**
     * Set the input field text
     */
    private void setInputField(String text) {
        GuiTextField field = getTextField();
        if (field != null) {
            field.setText(text);
        }
    }
}
