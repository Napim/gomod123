package me.napim.gomod.features;

import me.napim.gomod.Main;
import net.minecraft.network.play.server.S02PacketChat;
import net.weavemc.loader.api.event.PacketEvent.Receive;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.minecraft.util.EnumChatFormatting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses friend list chat output to summarize players by status.
 * Activated by FOnlineCommand.startCollection() and automatically stops after a timeout.
 * 
 * Also tracks friend join/leave messages in real-time to detect "Status offline" players
 * (friends who were online but disappeared from the friend list without leaving the server).
 */
public class FriendListScanner {

	private static volatile boolean collecting = false;
public static boolean isCollecting() { return collecting; }
	private static volatile long endAtMs = 0L;
	private static volatile boolean debug = false;
	private static volatile boolean foundOffline = false; // Flag to stop scanning when offline players are found
	private static volatile int scanningMessageId = -1; // Chat line ID to delete when done

	private static final ConcurrentMap<String, String> blitzGame = new ConcurrentHashMap<>();
	private static final ConcurrentMap<String, String> blitzLobby = new ConcurrentHashMap<>();
	private static final ConcurrentMap<String, String> away = new ConcurrentHashMap<>();
	private static final ConcurrentMap<String, String> busy = new ConcurrentHashMap<>();
	private static final ConcurrentMap<String, String> statusOffline = new ConcurrentHashMap<>();
	private static final java.util.Set<String> allOnline = java.util.concurrent.ConcurrentHashMap.newKeySet(); // Track all online friends
	private static final java.util.Set<String> previousOnline = java.util.concurrent.ConcurrentHashMap.newKeySet(); // Track previous scan's online friends
	private static final java.util.Set<String> friendsWhoLeft = java.util.concurrent.ConcurrentHashMap.newKeySet(); // Track friends who left via chat
	private static final java.util.Set<String> joinedDuringScan = java.util.concurrent.ConcurrentHashMap.newKeySet(); // Track friends who joined during active scan

	// Regex patterns tolerant of ranks and text variants (removed \b before username to support names starting with _)
	private static final Pattern BLITZ_GAME_PATTERN = Pattern.compile(
			"(?i).*?(?:\\[[^\\]]+\\]\\s*)?([A-Za-z0-9_]{1,16})\\s+is\\s+(?:in|playing)\\s+(?:a\\s+)?Blitz\\s+Survival\\s+Games\\s+Game\\b"
	);
	
	private static final Pattern BLITZ_LOBBY_PATTERN = Pattern.compile(
			"(?i).*?(?:\\[[^\\]]+\\]\\s*)?([A-Za-z0-9_]{1,16})\\s+is\\s+(?:in|playing)\\s+(?:a\\s+)?Blitz\\s+Survival\\s+Games\\s+Lobby\\b"
	);

	private static final Pattern STATUS_PATTERN = Pattern.compile(
			"(?i).*?(?:\\[[^\\]]+\\]\\s*)?([A-Za-z0-9_]{1,16})\\s+is\\s+currently\\s+(Away|Busy|Idle|AFK)\\b"
	);
	
	// Pattern to detect offline players (signals end of online friends)
	private static final Pattern OFFLINE_PATTERN = Pattern.compile(
			"(?i).*?(?:\\[[^\\]]+\\]\\s*)?([A-Za-z0-9_]{1,16})\\s+is\\s+currently\\s+offline\\b"
	);
	
	// Pattern to detect any online friend (for counting total online)
	private static final Pattern ONLINE_PATTERN = Pattern.compile(
			"(?i).*?(?:\\[[^\\]]+\\]\\s*)?([A-Za-z0-9_]{1,16})\\s+is\\s+(?:in|playing|currently)\\s+(?!currently\\s+offline).*"
	);
	
	// Patterns to detect friend join/leave messages in chat
	private static final Pattern FRIEND_JOINED_PATTERN = Pattern.compile(
			"^(?:Friend|F)\\s*>\\s*([A-Za-z0-9_]{1,16})\\s+joined\\.$"
	);
	private static final Pattern FRIEND_LEFT_PATTERN = Pattern.compile(
			"^(?:Friend|F)\\s*>\\s*([A-Za-z0-9_]{1,16})\\s+left\\.$"
	);

	public static void startCollection(long windowMs) {
		// Don't clear friendsWhoLeft yet - we need it to detect status offline players in report()
		// Don't clear statusOffline - it should persist until players come back online or leave
		
		collecting = true;
		endAtMs = System.currentTimeMillis() + Math.max(1000, windowMs);
		foundOffline = false; // Reset offline flag
		scanningMessageId = -1; // Reset message ID
		blitzGame.clear();
		blitzLobby.clear();
		away.clear();
		busy.clear();
		allOnline.clear();
		joinedDuringScan.clear(); // Clear list of players who joined during this scan
	}
	
	public static boolean hasFoundOffline() {
		return foundOffline;
	}

	/**
	 * Hypixel/tab IGN currently online that matches a gomod friend (for relay inbox delivery).
	 */
	public static String findOnlineIgnForFriend(String friendDisplayName) {
		if (friendDisplayName == null || friendDisplayName.trim().isEmpty()) {
			return null;
		}
		String want = friendDisplayName.trim();
		for (String online : allOnline) {
			if (online != null && online.equalsIgnoreCase(want)) {
				return online;
			}
		}
		return null;
	}

	/**
	 * Resolve any seen username (tab nick, disguise, etc.) to a gomod friend list name.
	 */
	public static String matchFriendSender(String anyIgn) {
		if (anyIgn == null || anyIgn.trim().isEmpty()) {
			return null;
		}
		String ign = anyIgn.trim();
		if (ConfigManager.isGomodFriend(ign)) {
			return ConfigManager.getGomodFriendDisplayName(ign);
		}
		for (String friend : ConfigManager.getGomodFriends()) {
			if (friend == null) {
				continue;
			}
			if (friend.equalsIgnoreCase(ign)) {
				return ConfigManager.getGomodFriendDisplayName(friend);
			}
			String online = findOnlineIgnForFriend(friend);
			if (online != null && online.equalsIgnoreCase(ign)) {
				return ConfigManager.getGomodFriendDisplayName(friend);
			}
		}
		return null;
	}

	/** True if the last {@code /fonline} scan saw this Hypixel friend as online (case-insensitive). */
	public static boolean isTrackedOnline(String name) {
		if (name == null) {
			return false;
		}
		String n = name.trim();
		if (n.isEmpty()) {
			return false;
		}
		for (String online : allOnline) {
			if (online != null && online.equalsIgnoreCase(n)) {
				return true;
			}
		}
		return false;
	}

	private static void touchLastSeen(String name) {
		if (name != null && !name.trim().isEmpty()) {
			GomodSocialStore.recordLastSeen(name.trim(), System.currentTimeMillis());
		}
	}

	/** Short status label for gomod Social friend rows (relay presence, Hypixel scan, in-world). */
	public static String resolveGomodFriendStatusLabel(String name, net.minecraft.client.Minecraft mc) {
		if (name == null || name.trim().isEmpty()) {
			return EnumChatFormatting.DARK_GRAY + "Unknown";
		}
		String n = name.trim();
		GomodSocialStore.FriendPresence relay = GomodSocialStore.getFriendPresence(n);
		if (relay != null && GomodSocialPresence.isPresenceFresh(relay.ts)) {
			return EnumChatFormatting.GREEN + "Online"
					+ GomodSocialPresence.locationSuffix(relay.server);
		}
		if (mc != null && mc.theWorld != null) {
			net.minecraft.entity.player.EntityPlayer p = mc.theWorld.getPlayerEntityByName(n);
			if (p != null) {
				return EnumChatFormatting.GREEN + "Online";
			}
		}
		if (isTrackedOnline(n)) {
			return EnumChatFormatting.GREEN + "Online";
		}
		if (away.containsKey(n) || away.containsKey(n.toLowerCase(java.util.Locale.ROOT))) {
			return EnumChatFormatting.YELLOW + "Away";
		}
		return EnumChatFormatting.DARK_GRAY + "Offline";
	}

	/** Relay or Hypixel/fonline: friend counts as online in Social dock. */
	public static boolean isSocialOnline(String name, net.minecraft.client.Minecraft mc) {
		if (name == null || name.trim().isEmpty()) {
			return false;
		}
		String n = name.trim();
		GomodSocialStore.FriendPresence relay = GomodSocialStore.getFriendPresence(n);
		if (relay != null && GomodSocialPresence.isPresenceFresh(relay.ts)) {
			return true;
		}
		if (mc != null && mc.theWorld != null) {
			net.minecraft.entity.player.EntityPlayer p = mc.theWorld.getPlayerEntityByName(n);
			if (p != null) {
				return true;
			}
		}
		return isTrackedOnline(n);
	}
	
	public static void setScanningMessageId(int id) {
		scanningMessageId = id;
	}

	public static void finishAfterDelay(long delayMs) {
		new Thread(() -> {
			try {
				Thread.sleep(Math.max(200, delayMs));
			} catch (InterruptedException ignored) {}
			forceFinish();
		}).start();
	}

	public static void setDebug(boolean enabled) {
		debug = enabled;
	}
	
	/**
	 * Clears all tracking data when disconnecting from server.
	 * This is necessary because we can't track friend join/leave messages while offline.
	 */
	public static void clearOnDisconnect() {
		previousOnline.clear();
		friendsWhoLeft.clear();
		statusOffline.clear();
		joinedDuringScan.clear();
		// Also clear other status maps
		blitzGame.clear();
		blitzLobby.clear();
		away.clear();
		busy.clear();
		allOnline.clear();
		if (debug) {
			System.out.println("[FOnline] Cleared tracking data on disconnect");
		}
	}

	private static void finishIfDue() {
		if (!collecting) return;
		if (System.currentTimeMillis() < endAtMs) return;
		collecting = false;
		report();
	}

	private static void forceFinish() {
		if (!collecting) return;
		collecting = false;
		report();
	}

	/**
	 * Always-on listener to track friend join/leave messages.
	 * This runs independently of the /fonline scanning.
	 */
	@SubscribeEvent
	public void onChatForFriendTracking(Receive event) {
		if (!(event.getPacket() instanceof S02PacketChat)) return;
		S02PacketChat pkt = (S02PacketChat) event.getPacket();
		String raw = pkt.getChatComponent().getFormattedText();
		if (raw == null || raw.isEmpty()) return;
		
		String sanitized = sanitize(raw);
		
		// Check for friend joined
		Matcher joinMatcher = FRIEND_JOINED_PATTERN.matcher(sanitized);
		if (joinMatcher.matches()) {
			String friendName = joinMatcher.group(1);
			// Remove from friendsWhoLeft if they rejoin
			friendsWhoLeft.remove(friendName);
			// Remove from status offline if they were there (they're back online now)
			statusOffline.remove(friendName);
			// Add to previousOnline so we can detect if they go status offline later
			previousOnline.add(friendName);
			touchLastSeen(friendName);
			// If this happened during an active scan, track it to prevent false status offline
			if (collecting) {
				joinedDuringScan.add(friendName);
				if (debug) {
					System.out.println("[FOnline][FriendTracking] Friend joined DURING SCAN: " + friendName + " (will exclude from status offline detection)");
				}
			} else if (debug) {
				System.out.println("[FOnline][FriendTracking] Friend joined: " + friendName + " (added to tracking, removed from status offline)");
			}
			return;
		}
		
		// Check for friend left
		Matcher leftMatcher = FRIEND_LEFT_PATTERN.matcher(sanitized);
		if (leftMatcher.matches()) {
			String friendName = leftMatcher.group(1);
			friendsWhoLeft.add(friendName);
			// Remove from previousOnline since they're no longer online
			previousOnline.remove(friendName);
			// Remove from status offline if they were there
			statusOffline.remove(friendName);
			if (debug) {
				System.out.println("[FOnline][FriendTracking] Friend left: " + friendName + " (removed from tracking and status offline)");
			}
			return;
		}
	}
	
	@SubscribeEvent
	public void onChat(Receive event) {
		if (!collecting) return;
		if (!(event.getPacket() instanceof S02PacketChat)) return;
		S02PacketChat pkt = (S02PacketChat) event.getPacket();
		String raw = pkt.getChatComponent().getFormattedText();
		if (raw == null || raw.isEmpty()) return;

		// Check if this is a friend list message - if so, we'll hide it
		boolean isFriendListMessage = false;
		String sanitized = sanitize(raw);
		if (sanitized.contains("Friends (Page") || sanitized.contains("-----")) {
			isFriendListMessage = true;
		}

		// Split by newlines first to handle multi-line friend list pages
		String[] lines = raw.split("\n");
		for (String rawLine : lines) {
			if (rawLine.isEmpty()) continue;
			
			// Hypixel friend list includes colors/ranks; sanitize then regex-match
			String text = sanitize(rawLine);
			if (debug) {
				DebugBuffer.log("raw:", rawLine);
				DebugBuffer.log("san:", text);
				System.out.println("[FOnline][raw] " + rawLine);
				System.out.println("[FOnline][san] " + text);
			}
			parseLine(text, rawLine);  // Pass both sanitized (for matching) and raw (for display)
			
			// If this line matches any of our patterns, it's part of the friend list
			if (!isFriendListMessage) {
				if (text.matches(".*?is\\s+(?:in|playing|currently).*") || 
				    text.contains("APPEARING OFFLINE")) {
					isFriendListMessage = true;
				}
			}
		}
		
		// Cancel the event to hide friend list pages from chat
		if (isFriendListMessage) {
			event.setCancelled(true);
		}

		// Auto-finish when time window passes
		if (System.currentTimeMillis() >= endAtMs) {
			finishIfDue();
		}
	}

	private static void parseLine(String line, String rawLine) {
		if (debug) {
			System.out.println("[FOnline][parseLine] checking (len=" + line.length() + "): '" + line + "'");
			// Print first 50 chars as hex to check for hidden characters
			if (!line.isEmpty()) {
				StringBuilder hex = new StringBuilder();
				for (int i = 0; i < Math.min(50, line.length()); i++) {
					hex.append(String.format("%04x ", (int) line.charAt(i)));
				}
				System.out.println("[FOnline][hex] " + hex.toString());
			}
			
			// Check if line contains the keywords we're looking for
			boolean hasIs = line.toLowerCase().contains("is");
			boolean hasCurrently = line.toLowerCase().contains("currently");
			boolean hasBusy = line.toLowerCase().contains("busy");
			boolean hasAway = line.toLowerCase().contains("away");
			System.out.println("[FOnline][keywords] is=" + hasIs + ", currently=" + hasCurrently + ", busy=" + hasBusy + ", away=" + hasAway);
		}
		
		// Check for Blitz Game
		Matcher mBlitzGame = BLITZ_GAME_PATTERN.matcher(line);
		if (mBlitzGame.find()) {
			String name = mBlitzGame.group(1);
			if (name != null && !name.isEmpty()) {
				blitzGame.put(name, rawLine);  // Store the colored line
				allOnline.add(name);
				touchLastSeen(name);
				if (debug) System.out.println("[FOnline][match] blitz game:" + name);
			}
			return;
		}
		
		// Check for Blitz Lobby
		Matcher mBlitzLobby = BLITZ_LOBBY_PATTERN.matcher(line);
		if (mBlitzLobby.find()) {
			String name = mBlitzLobby.group(1);
			if (name != null && !name.isEmpty()) {
				blitzLobby.put(name, rawLine);  // Store the colored line
				allOnline.add(name);
				touchLastSeen(name);
				if (debug) System.out.println("[FOnline][match] blitz lobby:" + name);
			}
			return;
		}

		// Check for offline players (signals end of online friends)
		Matcher mOffline = OFFLINE_PATTERN.matcher(line);
		if (mOffline.find()) {
			foundOffline = true;
			if (debug) System.out.println("[FOnline][offline detected] Found offline player, signaling to stop scanning");
			return;
		}
		
		// Track all online friends for the count
		Matcher mOnline = ONLINE_PATTERN.matcher(line);
		if (mOnline.find()) {
			String name = mOnline.group(1);
			if (name != null && !name.isEmpty()) {
				allOnline.add(name);
				touchLastSeen(name);
				if (debug) System.out.println("[FOnline][online] Added to count: " + name);
			}
		}
		
		Matcher mStatus = STATUS_PATTERN.matcher(line);
		boolean statusMatches = mStatus.find();
		if (debug) System.out.println("[FOnline][STATUS_PATTERN.find()] = " + statusMatches);
		mStatus.reset(); // Reset after find() to use it again
		
		if (mStatus.find()) {
			String name = mStatus.group(1);
			String status = mStatus.group(2);
			if (debug) System.out.println("[FOnline][extracted] name='" + name + "', status='" + status + "'");
			if (name != null && !name.isEmpty() && status != null) {
				allOnline.add(name);
				touchLastSeen(name);
				if (equalsIgnoreCase(status, "Away") || equalsIgnoreCase(status, "Idle") || equalsIgnoreCase(status, "AFK")) {
					away.put(name, rawLine);  // Store the colored line
					if (debug) System.out.println("[FOnline][match] away:" + name + " (" + status + ")");
				} else if (equalsIgnoreCase(status, "Busy")) {
					busy.put(name, rawLine);  // Store the colored line
					if (debug) System.out.println("[FOnline][match] busy:" + name + ")");
				}
			}
		} else {
			if (debug && line.toLowerCase().contains("busy")) {
				System.out.println("[FOnline][ERROR] Line contains 'busy' but didn't match pattern!");
				System.out.println("[FOnline][ERROR] Regex pattern: " + STATUS_PATTERN.pattern());
			}
		}
	}

	private static void report() {
		// Delete the "Scanning friend list pages..." message
		if (scanningMessageId >= 0) {
			try {
				net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
				if (mc != null && mc.ingameGUI != null && mc.ingameGUI.getChatGUI() != null) {
					mc.ingameGUI.getChatGUI().deleteChatLine(scanningMessageId);
				}
			} catch (Exception e) {
				// Ignore if deletion fails
			}
		}
		
		// Update status offline tracking
		// 1. Remove players who came back online
		java.util.Iterator<String> offlineIterator = statusOffline.keySet().iterator();
		while (offlineIterator.hasNext()) {
			String offlinePlayer = offlineIterator.next();
			if (allOnline.contains(offlinePlayer)) {
				// Player came back online!
				offlineIterator.remove();
				if (debug) {
					System.out.println("[FOnline][StatusOffline] " + offlinePlayer + " came back online (removed from status offline)");
				}
			} else if (friendsWhoLeft.contains(offlinePlayer)) {
				// Player actually left the server
				offlineIterator.remove();
				previousOnline.remove(offlinePlayer);
				if (debug) {
					System.out.println("[FOnline][StatusOffline] " + offlinePlayer + " left the server (removed from status offline and tracking)");
				}
			}
		}
		
		// 2. Detect NEW status offline players
		// These are players who were in previousOnline but are NOT in allOnline, NOT in friendsWhoLeft, 
		// NOT already in statusOffline, and did NOT join during this scan
		if (debug) {
			System.out.println("[FOnline][StatusOffline] Checking for NEW status offline players...");
			System.out.println("[FOnline][StatusOffline] Previous online: " + previousOnline);
			System.out.println("[FOnline][StatusOffline] Current online: " + allOnline);
			System.out.println("[FOnline][StatusOffline] Friends who left: " + friendsWhoLeft);
			System.out.println("[FOnline][StatusOffline] Joined during scan: " + joinedDuringScan);
			System.out.println("[FOnline][StatusOffline] Already status offline: " + statusOffline.keySet());
		}
		for (String previousFriend : previousOnline) {
			if (!allOnline.contains(previousFriend) 
			    && !friendsWhoLeft.contains(previousFriend) 
			    && !statusOffline.containsKey(previousFriend)
			    && !joinedDuringScan.contains(previousFriend)) {
				// This player went status offline (newly detected)
				statusOffline.put(previousFriend, previousFriend);
				if (debug) {
					System.out.println("[FOnline][StatusOffline] NEW status offline detected: " + previousFriend);
				}
			} else if (debug && joinedDuringScan.contains(previousFriend)) {
				System.out.println("[FOnline][StatusOffline] Skipping " + previousFriend + " (joined during scan)");
			}
		}
		
		// Keep status offline players in previousOnline so they continue to be tracked
		
		// Now clear joinedDuringScan - it's no longer needed after this scan
		joinedDuringScan.clear();
		
		// Clear friendsWhoLeft for the next period between scans
		friendsWhoLeft.clear();
		
		// Update previousOnline: ADD scan results to existing tracking (merge, don't replace)
		// This preserves players who joined between scans but haven't appeared in /fl yet
		int beforeSize = previousOnline.size();
		previousOnline.addAll(allOnline);
		int afterSize = previousOnline.size();
		if (debug) {
			System.out.println("[FOnline][Tracking] Updated previousOnline: " + beforeSize + " -> " + afterSize + " (added " + (afterSize - beforeSize) + " new)");
			System.out.println("[FOnline][Tracking] Now tracking: " + previousOnline);
		}
		
		List<String> blitzGameNames = new ArrayList<>(blitzGame.keySet());
		List<String> blitzLobbyNames = new ArrayList<>(blitzLobby.keySet());
		List<String> awayNames = new ArrayList<>(away.keySet());
		List<String> busyNames = new ArrayList<>(busy.keySet());
		List<String> statusOfflineNames = new ArrayList<>(statusOffline.keySet());
		
		// Sort with best friends (bold) first, then alphabetically
		Collections.sort(blitzGameNames, createBestFriendComparator(blitzGame));
		Collections.sort(blitzLobbyNames, createBestFriendComparator(blitzLobby));
		Collections.sort(awayNames, createBestFriendComparator(away));
		Collections.sort(busyNames, createBestFriendComparator(busy));
		Collections.sort(statusOfflineNames, createBestFriendComparator(statusOffline));

		// Show total online friends count at the top
		int totalOnline = allOnline.size();
		Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Online friends: " + EnumChatFormatting.WHITE + totalOnline);
		
		boolean any = false;
		
		// Order: Busy, BSG Game, BSG Lobby, Away
		if (!busyNames.isEmpty()) {
			any = true;
			StringBuilder sb = new StringBuilder();
			sb.append(EnumChatFormatting.DARK_PURPLE).append("Busy: ");
			for (int i = 0; i < busyNames.size(); i++) {
				String name = busyNames.get(i);
				String rawLine = busy.get(name);
				String coloredName = extractColoredUsername(rawLine, name);
				sb.append(coloredName);
				if (i < busyNames.size() - 1) {
					sb.append(EnumChatFormatting.WHITE).append(", ");
				}
			}
			Main.sendMessage(sb.toString());
		}
		
		if (!blitzGameNames.isEmpty()) {
			any = true;
			StringBuilder sb = new StringBuilder();
			sb.append(EnumChatFormatting.YELLOW).append("In a BSG game: ");
			for (int i = 0; i < blitzGameNames.size(); i++) {
				String name = blitzGameNames.get(i);
				String rawLine = blitzGame.get(name);
				String coloredName = extractColoredUsername(rawLine, name);
				sb.append(coloredName);
				if (i < blitzGameNames.size() - 1) {
					sb.append(EnumChatFormatting.WHITE).append(", ");
				}
			}
			Main.sendMessage(sb.toString());
		}
		
		if (!blitzLobbyNames.isEmpty()) {
			any = true;
			StringBuilder sb = new StringBuilder();
			sb.append(EnumChatFormatting.YELLOW).append("In a BSG Lobby: ");
			for (int i = 0; i < blitzLobbyNames.size(); i++) {
				String name = blitzLobbyNames.get(i);
				String rawLine = blitzLobby.get(name);
				String coloredName = extractColoredUsername(rawLine, name);
				sb.append(coloredName);
				if (i < blitzLobbyNames.size() - 1) {
					sb.append(EnumChatFormatting.WHITE).append(", ");
				}
			}
			Main.sendMessage(sb.toString());
		}
		
		if (!awayNames.isEmpty()) {
			any = true;
			StringBuilder sb = new StringBuilder();
			sb.append(EnumChatFormatting.YELLOW).append("Away: ");
			for (int i = 0; i < awayNames.size(); i++) {
				String name = awayNames.get(i);
				String rawLine = away.get(name);
				String coloredName = extractColoredUsername(rawLine, name);
				sb.append(coloredName);
				if (i < awayNames.size() - 1) {
					sb.append(EnumChatFormatting.WHITE).append(", ");
				}
			}
			Main.sendMessage(sb.toString());
		}
		
		// Display Status offline players last, in dark red
		if (!statusOfflineNames.isEmpty()) {
			any = true;
			StringBuilder sb = new StringBuilder();
			sb.append(EnumChatFormatting.DARK_RED).append("Status offline ")
			  .append(EnumChatFormatting.DARK_RED).append(EnumChatFormatting.ITALIC).append("(and offline?)")
			  .append(EnumChatFormatting.RESET).append(EnumChatFormatting.DARK_RED).append(": ");
			for (int i = 0; i < statusOfflineNames.size(); i++) {
				String name = statusOfflineNames.get(i);
				sb.append(EnumChatFormatting.WHITE).append(name);
				if (i < statusOfflineNames.size() - 1) {
					sb.append(EnumChatFormatting.WHITE).append(", ");
				}
			}
			Main.sendMessage(sb.toString());
		}

		if (!any) {
			Main.sendMessage(EnumChatFormatting.GRAY + "None in Blitz/Away/Busy.");
		}

		if (debug) {
			Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "[debug] collected: total=" + totalOnline + ", game=" + blitzGameNames.size() + ", lobby=" + blitzLobbyNames.size() + ", away=" + awayNames.size() + ", busy=" + busyNames.size() + ", statusOffline=" + statusOfflineNames.size());
			DebugBuffer.flushToChat();
			System.out.println("[FOnline][summary] total=" + totalOnline + ", game=" + blitzGameNames.size() + ", lobby=" + blitzLobbyNames.size() + ", away=" + awayNames.size() + ", busy=" + busyNames.size() + ", statusOffline=" + statusOfflineNames.size());
		}
	}
	
	/**
	 * Extracts the username from a raw friend list line, preserving Hypixel's color codes.
	 * Example: "§r§9§6PlayerName §eis currently §5Busy" -> "§r§9§6PlayerName"
	 */
	private static String extractColoredUsername(String rawLine, String plainName) {
		// Find where the plain name appears in the sanitized version
		String sanitized = sanitize(rawLine);
		int nameIndex = sanitized.indexOf(plainName);
		if (nameIndex == -1) return plainName; // Fallback if not found
		
		// Walk through the raw line to find the colored username portion
		int rawIndex = 0;
		int sanitizedIndex = 0;
		int nameStart = -1;
		int nameEnd = -1;
		int colorCodeStart = -1; // Track where color codes before the name start
		
		while (rawIndex < rawLine.length() && sanitizedIndex <= sanitized.length()) {
			char c = rawLine.charAt(rawIndex);
			
			// Check if we're about to hit the username
			if (sanitizedIndex == nameIndex && nameStart == -1) {
				// Look backwards to capture any color codes immediately before the name
				int lookback = rawIndex;
				while (lookback >= 2 && rawLine.charAt(lookback - 2) == '\u00A7') {
					lookback -= 2;
				}
				colorCodeStart = lookback;
				nameStart = rawIndex;
			}
			
			// Skip color codes (§ + next char)
			if (c == '\u00A7' && rawIndex + 1 < rawLine.length()) {
				rawIndex += 2;
				continue;
			}
			
			// Mark where the username ends in the raw line
			if (sanitizedIndex == nameIndex + plainName.length() && nameEnd == -1) {
				nameEnd = rawIndex;
				break;
			}
			
			rawIndex++;
			sanitizedIndex++;
		}
		
		// Use colorCodeStart to include color codes before the name
		if (colorCodeStart >= 0 && nameEnd > colorCodeStart) {
			return rawLine.substring(colorCodeStart, nameEnd);
		}
		
		if (nameStart >= 0 && nameEnd > nameStart) {
			return rawLine.substring(nameStart, nameEnd);
		}
		
		return plainName; // Fallback
	}

	/**
	 * Creates a comparator that sorts best friends (bold) first, then alphabetically.
	 * Best friends are identified by the §l (bold) formatting code in their colored name.
	 */
	private static java.util.Comparator<String> createBestFriendComparator(final ConcurrentMap<String, String> nameToRawLine) {
		return new java.util.Comparator<String>() {
			@Override
			public int compare(String name1, String name2) {
				String raw1 = nameToRawLine.get(name1);
				String raw2 = nameToRawLine.get(name2);
				
				// Check if each player is a best friend (has bold formatting)
				boolean isBold1 = raw1 != null && raw1.contains("\u00A7l");
				boolean isBold2 = raw2 != null && raw2.contains("\u00A7l");
				
				// Best friends (bold) come first
				if (isBold1 && !isBold2) {
					return -1; // name1 comes before name2
				} else if (!isBold1 && isBold2) {
					return 1; // name2 comes before name1
				} else {
					// Both bold or both not bold - sort alphabetically
					return String.CASE_INSENSITIVE_ORDER.compare(name1, name2);
				}
			}
		};
	}
	
	private static int indexOfIgnoreCase(String haystack, String needle) {
		return haystack.toLowerCase().indexOf(needle.toLowerCase());
	}

	private static boolean startsWithIgnoreCase(String text, String prefix) {
		return text.regionMatches(true, 0, prefix, 0, prefix.length());
	}

	private static boolean equalsIgnoreCase(String a, String b) {
		return a != null && a.equalsIgnoreCase(b);
	}

	private static String sanitize(String s) {
		// Strip Minecraft color codes - remove section sign and any character following it
		String noColors = s.replaceAll("\u00A7.", "");
		// Collapse whitespace and trim
		return noColors.replaceAll("\\s+", " ").trim();
	}

	// Simple ring buffer for debug lines
	private static class DebugBuffer {
		private static final int MAX = 200; // Increased to capture all 6 pages
		private static final List<String> LINES = new ArrayList<>();
		static void log(String tag, String line) {
			if (!debug) return;
			String entry = tag + " " + (line.length() > 220 ? line.substring(0, 220) + "..." : line);
			if (LINES.size() >= MAX) {
				LINES.remove(0);
			}
			LINES.add(entry);
		}
		static void flushToChat() {
			if (!debug) return;
			for (String l : LINES) {
				Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "[debug] " + l);
			}
			LINES.clear();
		}
	}
}


