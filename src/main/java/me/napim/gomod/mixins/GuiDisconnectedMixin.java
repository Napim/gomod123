package me.napim.gomod.mixins;

import me.napim.gomod.features.ReflectionLogger;
import me.napim.gomod.features.Selfie;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.util.IChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiDisconnected.class)
public abstract class GuiDisconnectedMixin {
    @Shadow private IChatComponent message;

    @Inject(method = "initGui", at = @At("HEAD"))
    private void gomod$logDisconnectGUI(CallbackInfo ci) {
        try {
            // Fallback for disconnect paths that do not reach NetHandler's
            // callback before the disconnected screen is displayed.
            Selfie.resetCameraForReconnect();
            if (message == null) return;
            
            String text = message.getUnformattedText();
            if (text == null || text.isEmpty()) return;
            
            // Log ALL disconnects for debugging (not just reflection errors)
            System.out.println("[gomod] === DISCONNECT DETECTED ===");
            System.out.println("[gomod] Reason: " + text);
            
            // Try to extract more details from the chat component
            try {
                String formatted = message.getFormattedText();
                if (formatted != null && !formatted.equals(text)) {
                    System.out.println("[gomod] Formatted: " + formatted);
                }
            } catch (Throwable t) { System.err.println("[gomod] Error in GuiDisconnectedMixin: " + t.getMessage()); }
            
            // Check if this is a reflection-related disconnect
            if (text.contains("Internal Exception") || text.contains("InvocationTargetException") || text.contains("reflect")) {
                System.err.println("[gomod] WARNING: This disconnect involves reflection/invocation errors!");
                System.err.println("[gomod] This is likely NOT caused by gomod - it's a server-side or network issue.");
                System.err.println("[gomod] Common causes:");
                System.err.println("[gomod]   - Server plugin error");
                System.err.println("[gomod]   - Network packet handling issue");
                System.err.println("[gomod]   - Mod conflict on server");
                
                // Log gomod feature states to help diagnose
                try {
                    System.out.println("[gomod] Active gomod features at disconnect:");
                    System.out.println("[gomod]   - Player Queuer: " + me.napim.gomod.features.PlayerQueuer.isEnabled());
                    System.out.println("[gomod]   - Player Queuer targets: " + me.napim.gomod.features.PlayerQueuer.getTargets().size());
                    System.out.println("[gomod]   - Player Queuer excludes: " + me.napim.gomod.features.PlayerQueuer.getExcludeTargets().size());
                } catch (Throwable t) { System.err.println("[gomod] Error in GuiDisconnectedMixin: " + t.getMessage()); }
            }
            
            System.out.println("[gomod] === END DISCONNECT INFO ===");
            
        } catch (Throwable e) {
            System.err.println("[gomod] Error in disconnect logging: " + e.getMessage());
        }
    }
}


