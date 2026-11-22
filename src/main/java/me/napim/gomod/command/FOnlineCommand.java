package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.util.GameContext;
import me.napim.gomod.features.FriendListScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Command to scan friends' statuses via /fl pages and summarize Blitz/Away/Busy.
 * Usage: /fonline [debug]
 */
public class FOnlineCommand extends Command {

	public FOnlineCommand() {
		super("fonline");
	}

	@Override
	public void handle(String[] args) {
		boolean debug = args.length > 0 && "debug".equalsIgnoreCase(args[0]);
		if (debug && !GomodInternalFeatures.requireInternalCommand("fonline debug")) return;
		if (Minecraft.getMinecraft().thePlayer == null) {
			Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Player not initialized yet.");
			return;
		}

		// Hypixel check
		if (!GameContext.isOnHypixel(Minecraft.getMinecraft())) {
			Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "/fonline only works on Hypixel.");
			return;
		}
		
		// Prevent overlapping scans (wheel spam can trigger this very easily).
		if (FriendListScanner.isCollecting()) {
			Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
					+ "Friend list scan already in progress. Please wait for it to finish.");
			return;
		}

		// Check for the dev-only debug flag before collecting friend-list data.
		FriendListScanner.setDebug(debug);
		
		// Send scanning message with a unique ID so we can delete it later
		int messageId = 745893; // Unique ID for FOnline scanning message
		Minecraft mc = Minecraft.getMinecraft();
		if (mc != null && mc.ingameGUI != null) {
			String msg = Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + (debug ? "[debug] " : "") + "Scanning friend list pages...";
			mc.ingameGUI.getChatGUI().printChatMessageWithOptionalDeletion(
				new net.minecraft.util.ChatComponentText(msg), 
				messageId
			);
			FriendListScanner.setScanningMessageId(messageId);
		}
		
		new Thread(() -> {
			try {
				// Start collection before sending any commands
				FriendListScanner.startCollection(30000); // Allow up to 30 seconds for scanning
				
				int page = 1;
				final int MAX_PAGES = 20; // Safety limit to prevent infinite loops
				
				while (page <= MAX_PAGES) {
					Minecraft.getMinecraft().thePlayer.sendChatMessage("/fl " + page);
					Thread.sleep(300); // 0.3 seconds to allow response
					
					// Check if we found offline players (end of online friends)
					if (FriendListScanner.hasFoundOffline()) {
						if (debug) {
							System.out.println("[FOnline] Stopped at page " + page + " - found offline players");
						}
						break;
					}
					
					page++;
				}
				
				// Wait a bit for the last page to be processed, then finish
				FriendListScanner.finishAfterDelay(1000);
			} catch (InterruptedException ignored) {}
		}).start();
	}
}


