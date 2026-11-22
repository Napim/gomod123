package me.napim.gomod.features;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemSkull;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S02PacketChat;
import me.napim.gomod.util.GameContext;

import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Display-only disguise aliases for player-name/head rows in Replay and live Blitz spectator GUIs. */
public final class ReplayPlayerSelectorDisplay {
    private static final Pattern TELEPORT_CONFIRMATION = Pattern.compile(
            "^\\s*You\\s+(?:were|have(?:\\s+been)?)\\s+teleported\\s+to\\s+"
                    + "((?:\\[[^\\]\\r\\n]{1,32}\\]\\s*)*)([A-Za-z0-9_]{1,16})"
                    + "(?:\\s+(\\[[^\\]\\r\\n]{1,32}\\]))?\\s*[.!]?\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SPECTATING_TARGET_LABEL = Pattern.compile(
            "^\\s*Spectating\\s+([A-Za-z0-9_]{1,16})(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HOTBAR_TARGET_LABEL = Pattern.compile(
            "^\\s*Target:\\s*([A-Za-z0-9_]{1,16})(.*)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SELECTOR_PLAYER_LABEL = Pattern.compile(
            "^\\s*(?:\\[[^\\]\\r\\n]{1,32}\\]\\s*)*([A-Za-z0-9_]{1,16})"
                    + "(?:\\s+\\[[^\\]\\r\\n]{1,32}\\])?"
                    + "(\\s+\\(#[0-9]{1,6}/[0-9]{1,3}\\))?\\s*$");
    private static final Pattern SKULL_OWNER_PLAYER_NAME = Pattern.compile(
            "^(?:\\[[^\\]\\r\\n]{1,32}\\]\\s*)*([A-Za-z0-9_]{1,16})$");
    private static volatile Field actionBarComponentField;
    private static volatile String lastSpectatorTargetPacket = "no Target action-bar packet observed";
    private static volatile String lastSpectatorTargetActionBar = "no Target action-bar component observed";
    private static final long OPEN_CONTEXT_CACHE_NANOS = 50_000_000L;
    private static final long TELEPORTER_KIT_SCAN_INTERVAL_NANOS = 200_000_000L;
    private static volatile OpenContextSnapshot openContextSnapshot;
    private static Object lastTeleporterKitContainer;
    private static long lastTeleporterKitScanNanos;
    private static volatile String lastTeleporterKitScanSummary = "not scanned";

    private static final class OpenContextSnapshot {
        final Minecraft minecraft;
        final Object screen;
        final Object world;
        final EntityPlayer player;
        final Object container;
        final long checkedAtNanos;
        final boolean open;

        OpenContextSnapshot(Minecraft minecraft, Object screen, Object world, EntityPlayer player,
                Object container, long checkedAtNanos, boolean open) {
            this.minecraft = minecraft;
            this.screen = screen;
            this.world = world;
            this.player = player;
            this.container = container;
            this.checkedAtNanos = checkedAtNanos;
            this.open = open;
        }

        boolean matches(Minecraft minecraft, Object screen, Object world, EntityPlayer player, Object container) {
            return this.minecraft == minecraft && this.screen == screen && this.world == world
                    && this.player == player && this.container == container;
        }
    }

    private ReplayPlayerSelectorDisplay() { }

    /** Lunar can consume action bars before GuiIngame/FontRenderer. Rewrite the incoming display packet itself. */
    public static void rewriteIncomingSpectatorHudPacket(Packet<?> packet) {
        if (!(packet instanceof S02PacketChat)) return;
        S02PacketChat chat = (S02PacketChat) packet;
        if (chat.getChatComponent() == null) return;
        IChatComponent original = chat.getChatComponent();
        String source = lastTargetSource(original);
        if ("unknown".equals(source)) {
            if (LocalDisguiseDebugCapture.isActive()) {
                String visible = visibleComponentText(original);
                if (visible != null && visible.toLowerCase(java.util.Locale.ROOT).contains("target:")) {
                    LocalDisguiseDebugCapture.record("spectator-target-packet-scan", "<unmatched>",
                            "type=" + chat.getType() + " visible=" + visible
                                    + " input=" + original.getFormattedText());
                }
            }
            return;
        }
        String target = targetNameOnly(source);
        // PacketEvent and NetHandlerPlayClient both see S02. Once the first hook has rewritten the component,
        // the second sees the target name; skip it rather than replacing the useful trace with no-active-disguise.
        if (target == null || target.isEmpty() || target.equalsIgnoreCase(source)) {
            if (LocalDisguiseDebugCapture.isActive()) {
                LocalDisguiseDebugCapture.record("spectator-target-packet", source,
                        "type=" + chat.getType() + " target=" + target + " result=no-active-disguise"
                                + " input=" + original.getFormattedText());
            }
            return;
        }
        IChatComponent rewritten = rewriteLiveSpectatorTargetComponent(original);
        if (rewritten == original) return;
        String result = "rewritten";
        try {
            if (rewritten != original) actionBarComponentField().set(chat, rewritten);
        } catch (Throwable failure) {
            result = "failed:" + failure.getClass().getSimpleName();
        }
        lastSpectatorTargetPacket = "type=2 source=" + source + " target=" + target + " result=" + result
                + " input=" + original.getFormattedText() + " output=" + chat.getChatComponent().getFormattedText();
        if (LocalDisguiseDebugCapture.isActive()) {
            LocalDisguiseDebugCapture.record("spectator-target-packet", source, lastSpectatorTargetPacket);
        }
    }

    /**
     * Hypixel sends the live spectator action bar as an IChatComponent to GuiIngame. This catches that overload
     * directly (Lunar does not always expose the matching S02 type-2 packet to our packet listener).
     */
    public static IChatComponent rewriteLiveSpectatorTargetComponent(IChatComponent original) {
        if (original == null) return null;
        String formatted = original.getFormattedText();
        String visible = visibleComponentText(original);
        Matcher targetLine = visible == null ? null : HOTBAR_TARGET_LABEL.matcher(visible);
        if (targetLine == null || !targetLine.matches()) return original;

        String source = targetLine.group(1);
        String target = targetNameOnly(source);
        String result = "no-active-disguise";
        IChatComponent rewritten = original;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || LocalDisguise.isHypixelReplayContextCached(mc) || !isLiveBlitzGameContext(mc)) {
            result = "outside-live-blitz-context";
        } else if (target != null && !target.isEmpty() && !target.equalsIgnoreCase(source)) {
            rewritten = GomodPeerDisguiseDisplay.replaceVisibleNameRange(original,
                    targetLine.start(1), targetLine.end(1), target);
            result = rewritten == original ? "rewrite-failed" : "rewritten";
        }
        String output = rewritten.getFormattedText();
        lastSpectatorTargetActionBar = "source=" + source + " target=" + target + " result=" + result
                + " input=" + formatted + " output=" + output;
        if (LocalDisguiseDebugCapture.isActive()) {
            LocalDisguiseDebugCapture.record("spectator-target-actionbar", source, lastSpectatorTargetActionBar);
        }
        return rewritten;
    }

    private static String lastTargetSource(IChatComponent component) {
        if (component == null) return "unknown";
        String visible = visibleComponentText(component);
        Matcher targetLine = visible == null ? null : HOTBAR_TARGET_LABEL.matcher(visible);
        return targetLine != null && targetLine.matches() ? targetLine.group(1) : "unknown";
    }

    /**
     * Lunar/Hypixel action-bar components can retain formatting in their unformatted payload. Parse the same
     * text path that the client displays, then strip legacy formatting before matching the HUD label.
     */
    private static String visibleComponentText(IChatComponent component) {
        if (component == null) return null;
        String formatted = component.getFormattedText();
        String visible = stripLegacyFormattingCodes(formatted);
        if (visible != null) return visible;
        return stripLegacyFormattingCodes(component.getUnformattedText());
    }

    /**
     * Strip section-sign formatting pairs directly. Some Lunar action-bar components expose literal §l/§a codes
     * even after EnumChatFormatting's helper, which made the HUD regex treat formatting as part of the player IGN.
     */
    private static String stripLegacyFormattingCodes(String formatted) {
        if (formatted == null) return null;
        if (formatted.indexOf('\u00a7') < 0) return formatted;
        StringBuilder visible = new StringBuilder(formatted.length());
        for (int i = 0; i < formatted.length(); i++) {
            char c = formatted.charAt(i);
            if (c == '\u00a7' && i + 1 < formatted.length()) {
                i++;
                continue;
            }
            visible.append(c);
        }
        return visible.toString();
    }

    private static Field actionBarComponentField() throws ReflectiveOperationException {
        Field field = actionBarComponentField;
        if (field != null) return field;
        Field found = null;
        for (Field candidate : S02PacketChat.class.getDeclaredFields()) {
            if (!Modifier.isStatic(candidate.getModifiers())
                    && IChatComponent.class.isAssignableFrom(candidate.getType())) {
                if (found != null) throw new NoSuchFieldException("ambiguous S02 chat component");
                found = candidate;
            }
        }
        if (found == null) throw new NoSuchFieldException("S02 chat component");
        found.setAccessible(true);
        actionBarComponentField = found;
        return found;
    }

    public static String describeLastSpectatorTargetPacket() {
        return lastSpectatorTargetPacket;
    }

    public static String describeLastSpectatorTargetActionBar() {
        return lastSpectatorTargetActionBar;
    }

    public static boolean isOpen() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return false;
        Object screen = mc.currentScreen;
        EntityPlayer player = mc.thePlayer;
        if (screen == null || player == null) return false;
        Object world = mc.theWorld;
        Object container = player.openContainer;
        long now = System.nanoTime();
        OpenContextSnapshot cached = openContextSnapshot;
        if (cached != null && cached.matches(mc, screen, world, player, container)
                && now - cached.checkedAtNanos < OPEN_CONTEXT_CACHE_NANOS) {
            return cached.open;
        }

        // Replay's right-click player actions menu is a different screen from the live "Teleporter" chest.
        // Identify both selector chests directly: Hypixel can briefly clear its game/spectator scoreboard state
        // while opening a selector, so phase detection must not disable its display-only disguise hooks.
        boolean open = LocalDisguise.isHypixelReplayContextCached(mc)
                || (isTeleportSelectorChestOpen(mc) && GameContext.isOnHypixel(mc))
                || isLiveBlitzSpectator(mc);
        openContextSnapshot = new OpenContextSnapshot(mc, screen, world, player, container, now, open);
        return open;
    }

    /** Replay uses "Players"; the live spectator compass uses "Teleporter". Both show the target's rank. */
    private static boolean isTeleportSelectorChestOpen() {
        Minecraft mc = Minecraft.getMinecraft();
        return isTeleportSelectorChestOpen(mc);
    }

    private static boolean isTeleportSelectorChestOpen(Minecraft mc) {
        if (mc == null || !(mc.currentScreen instanceof GuiChest) || mc.thePlayer == null
                || !(mc.thePlayer.openContainer instanceof ContainerChest)) return false;
        try {
            IInventory lower = ((ContainerChest) mc.thePlayer.openContainer).getLowerChestInventory();
            if (lower == null || lower.getDisplayName() == null) return false;
            String title = EnumChatFormatting.getTextWithoutFormattingCodes(
                    lower.getDisplayName().getUnformattedText());
            if (title == null) return false;
            title = title.trim();
            return "Players".equalsIgnoreCase(title) || "Teleporter".equalsIgnoreCase(title);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isLiveBlitzSpectator(Minecraft mc) {
        return isLiveBlitzGameContext(mc)
                && (GameContext.isBlitzSpectator(mc) || mc.thePlayer.isSpectator());
    }

    /**
     * The live Blitz Teleporter head rows remain available to spectators after their player entities stop
     * rendering. Read their server-owned lore as a second kit source and feed the existing TAB cache.
     */
    private static void captureTeleporterEnemyKits(Minecraft mc) {
        if (!KillCounter.isShowEnemyKits()) {
            lastTeleporterKitContainer = null;
            lastTeleporterKitScanSummary = "not scanned (Show enemy players' kits is disabled)";
            return;
        }
        if (mc == null || mc.thePlayer == null || mc.thePlayer.openContainer == null) {
            lastTeleporterKitContainer = null;
            return;
        }

        Object container = mc.thePlayer.openContainer;
        long now = System.nanoTime();
        if (container == lastTeleporterKitContainer
                && now - lastTeleporterKitScanNanos < TELEPORTER_KIT_SCAN_INTERVAL_NANOS) {
            return;
        }
        IInventory lower = getLiveTeleporterInventory(mc);
        if (lower == null) {
            lastTeleporterKitContainer = null;
            lastTeleporterKitScanSummary = "not a live Hypixel Teleporter chest";
            return;
        }
        lastTeleporterKitContainer = container;
        lastTeleporterKitScanNanos = now;

        int skullRows = 0;
        int kitRows = 0;
        for (int slot = 0; slot < lower.getSizeInventory(); slot++) {
            ItemStack row = lower.getStackInSlot(slot);
            if (row == null || !(row.getItem() instanceof ItemSkull) || row.getMetadata() != 3) continue;
            skullRows++;
            String displayName = getStoredDisplayName(row);
            String sourceName = findSourceNameInDisplay(displayName);
            // Nicked heads can have an owner profile with only a synthetic UUID/name. The chest's visible
            // row still contains the actual nick used by TAB, so retain that name before falling back to NBT.
            if (sourceName == null || sourceName.isEmpty()) sourceName = findPlayerNameInDisplay(displayName);
            if (sourceName == null || sourceName.isEmpty()) sourceName = getSkullOwnerName(row);
            if (sourceName == null || sourceName.isEmpty()) continue;
            if (KillCounter.observeEnemyKitFromTeleporter(sourceName, getTeleporterKitText(row, mc))) kitRows++;
        }
        lastTeleporterKitScanSummary = "title=Teleporter skullRows=" + skullRows + " kitRows=" + kitRows;
    }

    private static IInventory getLiveTeleporterInventory(Minecraft mc) {
        if (mc == null || !(mc.currentScreen instanceof GuiChest) || mc.thePlayer == null
                || !(mc.thePlayer.openContainer instanceof ContainerChest) || !GameContext.isOnHypixel(mc)) {
            return null;
        }
        try {
            IInventory lower = ((ContainerChest) mc.thePlayer.openContainer).getLowerChestInventory();
            if (lower == null || lower.getDisplayName() == null) return null;
            String title = EnumChatFormatting.getTextWithoutFormattingCodes(
                    lower.getDisplayName().getUnformattedText());
            return title != null && "Teleporter".equalsIgnoreCase(title.trim()) ? lower : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String[] getTeleporterKitText(ItemStack stack, Minecraft mc) {
        if (stack == null) return new String[0];
        ArrayList<String> values = new ArrayList<String>();
        String name = getStoredDisplayName(stack);
        if (name != null && !name.trim().isEmpty()) values.add(name);
        try {
            NBTTagCompound root = stack.getTagCompound();
            if (root != null && root.hasKey("display", 10)) {
                NBTTagCompound display = root.getCompoundTag("display");
                if (display.hasKey("Lore", 9)) {
                    NBTTagList lore = display.getTagList("Lore", 8);
                    for (int i = 0; i < lore.tagCount(); i++) {
                        String line = lore.getStringTagAt(i);
                        if (line != null && !line.trim().isEmpty()) values.add(line);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (mc != null && mc.thePlayer != null) {
                List<String> tooltip = stack.getTooltip(mc.thePlayer, false);
                if (tooltip != null) {
                    for (String line : tooltip) {
                        if (line != null && !line.trim().isEmpty() && !values.contains(line)) values.add(line);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return values.toArray(new String[values.size()]);
    }

    public static String describeLastTeleporterKitScan() {
        return lastTeleporterKitScanSummary;
    }

    private static boolean isLiveBlitzGameContext(Minecraft mc) {
        boolean blitzPhase = mc != null && (GameContext.isBlitzGameActive(mc)
                || GameContext.isBlitzDeathmatchPhase(mc)
                || GameContext.isBlitzGameOverSidebar(mc));
        return mc != null && mc.thePlayer != null && GameContext.isOnHypixel(mc) && blitzPhase;
    }

    /** Returns a replacement only for a player-head row in the active Replay/live spectator selector. */
    public static String rewriteStackDisplayName(ItemStack stack, String original) {
        if (!isOpen() || stack == null || original == null || !(stack.getItem() instanceof ItemSkull)
                || stack.getMetadata() != 3) return null;
        String sourceName = getSkullOwnerName(stack);
        return rewritePlayerSelectorLabel(original, sourceName);
    }

    /** The hover tooltip should use the target's rank styling; the screen title itself stays a bare IGN. */
    public static List<String> rewriteStackTooltip(ItemStack stack, List<String> original) {
        if (!isOpen() || stack == null || original == null || original.isEmpty()
                || !(stack.getItem() instanceof ItemSkull) || stack.getMetadata() != 3) return null;
        String sourceName = findSourceNameInDisplay(getStoredDisplayName(stack));
        if (sourceName == null) sourceName = getSkullOwnerName(stack);
        if (sourceName == null || sourceName.isEmpty()) return null;

        String targetDisplay = targetDisplayName(sourceName);
        if (targetDisplay == null || targetDisplay.isEmpty()) return null;
        targetDisplay = KillCounter.stripGuildTagFromDisguiseDisplay(targetDisplay);
        String suffix = selectorSuffix(original.get(0));
        if (suffix.isEmpty()) suffix = selectorSuffix(getStoredDisplayName(stack));
        if (!suffix.isEmpty() && !targetDisplay.endsWith(suffix)) targetDisplay += suffix;
        if (targetDisplay == null || targetDisplay.isEmpty() || targetDisplay.equals(original.get(0))) return null;

        List<String> rewritten = new ArrayList<String>(original);
        rewritten.set(0, targetDisplay);
        if (LocalDisguiseDebugCapture.isActive()) {
            LocalDisguiseDebugCapture.record("player-selector-tooltip", sourceName,
                    "target=" + targetNameOnly(sourceName) + " input=" + original.get(0)
                            + " output=" + targetDisplay);
        }
        return rewritten;
    }

    /** Keeps the optional advanced-item-debug suffix attached to the player name in selector tooltips. */
    private static String selectorSuffix(String formatted) {
        if (formatted == null || formatted.isEmpty()) return "";
        String visible = EnumChatFormatting.getTextWithoutFormattingCodes(formatted);
        if (visible == null) visible = formatted;
        Matcher row = SELECTOR_PLAYER_LABEL.matcher(visible);
        if (!row.matches() || row.start(2) < 0) return "";
        int rawStart = rawIndexForVisibleIndex(formatted, row.start(2));
        return rawStart < 0 || rawStart > formatted.length() ? "" : formatted.substring(rawStart);
    }

    /** Font fallback for selector rows which draw a name without asking ItemStack for it. */
    public static String rewriteText(String original) {
        if (!isOpen() || original == null || original.isEmpty()) return null;
        return rewritePlayerSelectorLabel(original, null);
    }

    /** Rewrites the in-game spectator overlay and hotbar target row without altering their server color scheme. */
    public static String rewriteLiveSpectatorTargetText(String original) {
        if (!SpectatorHudTextFilter.mayBeTargetText(original)) return null;
        String visible = stripLegacyFormattingCodes(original);
        Matcher spectating = SpectatorHudTextFilter.hasVisiblePrefix(visible, "Spectating")
                ? SPECTATING_TARGET_LABEL.matcher(visible) : null;
        boolean spectatingOverlay = spectating != null && spectating.matches();
        Matcher targetLine = !spectatingOverlay && SpectatorHudTextFilter.hasVisiblePrefix(visible, "Target:")
                ? HOTBAR_TARGET_LABEL.matcher(visible) : null;
        boolean completeTargetLine = targetLine != null && targetLine.matches();

        // An ordinary TAB label must not run match/scoreboard detection on every width and draw call.
        if (!spectatingOverlay && !completeTargetLine
                && !SpectatorHudTextFilter.isBoldStandalonePlayerName(original)) return null;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || LocalDisguise.isHypixelReplayContextCached(mc) || !isLiveBlitzGameContext(mc)) return null;
        String sourceName;
        boolean splitDisguisedName = false;
        if (spectatingOverlay) {
            sourceName = spectating.group(1);
        } else if (completeTargetLine) {
            sourceName = targetLine.group(1);
        } else {
            // Lunar can render the hotbar row as separate FontRenderer calls. The camera-name fallback handles
            // the usual case; Hypixel can also leave a different target in the HUD, so accept an exact bold IGN
            // only when it has an active remote disguise mapping.
            String drawnName = visible.trim();
            String cameraName = getLiveSpectatorCameraPlayerName(mc);
            if (cameraName != null && drawnName.equalsIgnoreCase(cameraName)) {
                sourceName = cameraName;
            } else {
                if (!RemoteDisguiseManager.hasEntries()) return null;
                sourceName = drawnName;
            }
            splitDisguisedName = true;
        }
        String targetName = targetNameOnly(sourceName);
        String kind = spectatingOverlay ? "spectating-overlay"
                : completeTargetLine ? "hotbar-target" : "hotbar-target-name";
        if (targetName == null || targetName.isEmpty()) {
            if (LocalDisguiseDebugCapture.isActive()) {
                LocalDisguiseDebugCapture.record("spectator-target", sourceName,
                        "kind=" + kind + " no-active-disguise input=" + original);
            }
            return null;
        }

        String replacement = spectatingOverlay ? rankColoredBareTargetName(sourceName, targetName) : targetName;
        String rewritten = splitDisguisedName
                ? replaceStandaloneNamePreservingFormatting(original, visible, sourceName, replacement)
                : RemoteDisguiseManager.replaceNameInFormatted(original, sourceName, replacement);
        if (rewritten == null || rewritten.equals(original)) return null;
        if (LocalDisguiseDebugCapture.isActive()) {
            LocalDisguiseDebugCapture.record("spectator-target", sourceName,
                    "kind=" + kind + " target=" + targetName + " input=" + original + " output=" + rewritten);
        }
        return rewritten;
    }

    /** Replace an isolated split HUD name while keeping leading bold/color codes and trailing resets intact. */
    private static String replaceStandaloneNamePreservingFormatting(String formatted, String visible,
            String sourceName, String targetName) {
        if (formatted == null || visible == null || sourceName == null || targetName == null) return null;
        String trimmed = visible.trim();
        if (!trimmed.equalsIgnoreCase(sourceName)) return null;
        int visibleStart = visible.indexOf(trimmed);
        if (visibleStart < 0) return null;
        int rawStart = rawStartForVisibleIndex(formatted, visibleStart);
        int rawEnd = rawIndexForVisibleIndex(formatted, visibleStart + trimmed.length());
        if (rawStart < 0 || rawEnd < rawStart || rawEnd > formatted.length()) return null;
        return formatted.substring(0, rawStart) + targetName + formatted.substring(rawEnd);
    }

    /** Unlike the generic replacement helper, skip formatting at the start so it remains active on the new name. */
    private static int rawStartForVisibleIndex(String formatted, int visibleIndex) {
        if (formatted == null || visibleIndex < 0) return -1;
        int visible = 0;
        for (int i = 0; i < formatted.length(); i++) {
            char c = formatted.charAt(i);
            if (c == '\u00a7' && i + 1 < formatted.length()) {
                i++;
                continue;
            }
            if (visible == visibleIndex) return i;
            visible++;
        }
        return visible == visibleIndex ? formatted.length() : -1;
    }

    private static String getLiveSpectatorCameraPlayerName(Minecraft mc) {
        try {
            if (mc == null || !(mc.getRenderViewEntity() instanceof EntityPlayer)) return null;
            GameProfile profile = ((EntityPlayer) mc.getRenderViewEntity()).getGameProfile();
            return profile == null ? null : normalizeSkullOwnerName(profile.getName());
        } catch (Throwable ignored) {
            return null;
        }
    }


    /** The large Spectating overlay has no prefix, but follows the target account's rank color. */
    private static String rankColoredBareTargetName(String sourceName, String targetName) {
        String targetDisplay = targetDisplayName(sourceName);
        if (targetDisplay == null || targetDisplay.isEmpty()) return targetName;
        String visibleDisplay = EnumChatFormatting.getTextWithoutFormattingCodes(targetDisplay);
        if (visibleDisplay == null) visibleDisplay = targetDisplay;
        String plainTarget = EnumChatFormatting.getTextWithoutFormattingCodes(targetName);
        if (plainTarget == null) plainTarget = targetName;
        int nameIndex = visibleDisplay.toLowerCase(java.util.Locale.ROOT)
                .lastIndexOf(plainTarget.toLowerCase(java.util.Locale.ROOT));
        if (nameIndex < 0) return targetName;
        String targetColor = colorCodeBeforeVisibleIndex(targetDisplay, nameIndex);
        return targetColor.isEmpty() ? targetName : targetColor + targetName;
    }

    private static String colorCodeBeforeVisibleIndex(String formatted, int visibleIndex) {
        if (formatted == null || visibleIndex < 0) return "";
        String activeColor = "";
        int visible = 0;
        for (int i = 0; i < formatted.length();) {
            char c = formatted.charAt(i);
            if (c == '\u00a7' && i + 1 < formatted.length()) {
                char code = Character.toLowerCase(formatted.charAt(i + 1));
                if (isLegacyColor(code)) activeColor = "\u00a7" + code;
                else if (code == 'r') activeColor = "";
                i += 2;
                continue;
            }
            if (visible >= visibleIndex) break;
            visible++;
            i++;
        }
        return activeColor;
    }

    /** Replaces only a complete selector player row, preserving the source row's suffix (e.g. F3+H item data). */
    private static String rewritePlayerSelectorLabel(String original, String sourceNameHint) {
        // This is a FontRenderer fallback and can run for every string in an open spectator screen.
        // Avoid EnumChatFormatting's regex-based stripping in that hot path.
        String visible = stripLegacyFormattingCodes(original);
        if (visible == null) visible = original;
        Matcher row = SELECTOR_PLAYER_LABEL.matcher(visible);
        if (!row.matches()) return null;
        String rowName = row.group(1);
        String sourceName = normalizeSkullOwnerName(sourceNameHint);
        if (sourceName == null || !sourceName.equalsIgnoreCase(rowName)) {
            sourceName = findSourceNameInDisplay(visible);
        }
        if ((sourceName == null || !sourceName.equalsIgnoreCase(rowName))
                && targetDisplayName(rowName) != null) {
            sourceName = rowName;
        }
        if (sourceName == null || !sourceName.equalsIgnoreCase(rowName)) return null;

        boolean teleportSelectorChest = isTeleportSelectorChestOpen();
        String replacement = teleportSelectorChest ? targetDisplayName(sourceName) : targetNameOnly(sourceName);
        if (replacement == null || replacement.isEmpty()) return null;
        int suffixVisibleStart = row.start(2) >= 0 ? row.start(2) : visible.length();
        int rawSuffixStart = rawIndexForVisibleIndex(original, suffixVisibleStart);
        String suffix = rawSuffixStart < 0 || rawSuffixStart > original.length()
                ? "" : original.substring(rawSuffixStart);
        String rewritten = teleportSelectorChest
                ? replacement + suffix
                : formattingStateBeforeVisibleIndex(original, row.start(1)) + replacement + suffix;
        if (rewritten.equals(original)) return null;
        recordRewrite(sourceName, original, rewritten);
        return rewritten;
    }

    /** Returns the active legacy style at a visible-text boundary, dropping any source rank text. */
    private static String formattingStateBeforeVisibleIndex(String formatted, int visibleIndex) {
        if (formatted == null || visibleIndex < 0) return "";
        StringBuilder state = new StringBuilder();
        int visible = 0;
        for (int i = 0; i < formatted.length();) {
            char c = formatted.charAt(i);
            if (c == '\u00a7' && i + 1 < formatted.length()) {
                char code = Character.toLowerCase(formatted.charAt(i + 1));
                if (isLegacyColor(code) || code == 'r') state.setLength(0);
                if (code != 'r') state.append('\u00a7').append(code);
                i += 2;
                continue;
            }
            if (visible >= visibleIndex) break;
            visible++;
            i++;
        }
        return state.toString();
    }

    private static boolean isLegacyColor(char code) {
        return code >= '0' && code <= '9' || code >= 'a' && code <= 'f';
    }

    private static int rawIndexForVisibleIndex(String formatted, int visibleIndex) {
        if (formatted == null || visibleIndex < 0) return -1;
        int visible = 0;
        for (int i = 0; i < formatted.length(); i++) {
            if (visible == visibleIndex) return i;
            char c = formatted.charAt(i);
            if (c == '\u00a7' && i + 1 < formatted.length()) {
                i++;
                continue;
            }
            visible++;
        }
        return visible == visibleIndex ? formatted.length() : -1;
    }

    /**
     * Render a copied skull stack with the disguise target's profile. The slot's real stack stays untouched, so
     * Hypixel still receives the original player/slot when the spectator clicks to teleport.
     */
    public static ItemStack copyStackForSelectorRender(ItemStack stack) {
        if (!isOpen()) return stack;
        captureTeleporterEnemyKits(Minecraft.getMinecraft());
        if (stack == null || !(stack.getItem() instanceof ItemSkull)
                || stack.getMetadata() != 3 || stack.getTagCompound() == null) return stack;
        GameProfile ownerProfile = getSkullOwnerProfile(stack);
        // In live spectator menus, SkullOwner may already carry an aliased/UUID profile while the stored item name
        // still identifies the real Hypixel player. Prefer that source display when it matches a known disguise.
        String sourceName = findSourceNameInDisplay(getStoredDisplayName(stack));
        if (sourceName == null || sourceName.isEmpty()) sourceName = getSkullOwnerName(stack, ownerProfile);
        GameProfile target = targetProfile(sourceName, ownerProfile);
        if (target == null || target.getName() == null || target.getName().isEmpty()) {
            recordHeadRewrite(sourceName, null, false, "no-active-target", ownerProfile, stack);
            return stack;
        }
        try {
            ItemStack renderCopy = stack.copy();
            NBTTagCompound root = renderCopy.getTagCompound();
            if (root == null) {
                recordHeadRewrite(sourceName, target.getName(), false, "copy-had-no-tag", ownerProfile, stack);
                return stack;
            }
            root.setTag("SkullOwner", NBTUtil.writeGameProfile(new NBTTagCompound(), target));
            renderCopy.setTagCompound(root);
            recordHeadRewrite(sourceName, target.getName(), true, "copied-target-profile-for-render", ownerProfile, stack);
            return renderCopy;
        } catch (Throwable ignored) {
            recordHeadRewrite(sourceName, target.getName(), false, "profile-copy-failed", ownerProfile, stack);
            return stack;
        }
    }

    /** Rewrites Hypixel Blitz's exact teleport confirmation, including rank-prefixed names. */
    public static String rewriteTeleportConfirmationText(String original) {
        if (original == null || original.isEmpty() || !containsIgnoreCase(original, "teleported to")) return null;
        String visible = EnumChatFormatting.getTextWithoutFormattingCodes(original);
        if (visible == null) visible = original;
        Matcher message = TELEPORT_CONFIRMATION.matcher(visible);
        if (!message.matches()) return null;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || LocalDisguise.isHypixelReplayContextCached(mc) || !isLiveBlitzGameContext(mc)) return null;
        String sourceName = message.group(2);
        String targetName = targetNameOnly(sourceName);
        if (targetName == null || targetName.isEmpty()) {
            if (LocalDisguiseDebugCapture.isActive()) {
                LocalDisguiseDebugCapture.record("teleport-confirmation", sourceName,
                        "no-active-disguise input=" + original);
            }
            return null;
        }

        boolean hasRankPrefix = message.group(1) != null && !message.group(1).isEmpty();
        boolean hasTrailingLabel = message.group(3) != null && !message.group(3).isEmpty();
        String rewritten;
        if (hasRankPrefix || hasTrailingLabel) {
            // When the server includes a rank/guild-style label, show the disguise target's own rank styling.
            // A bare confirmation stays bare and keeps the server's existing name color.
            String targetDisplay = targetDisplayName(sourceName);
            if (targetDisplay == null || targetDisplay.isEmpty()) targetDisplay = targetName;
            int visibleStart = hasRankPrefix ? message.start(1) : message.start(2);
            int visibleEnd = hasTrailingLabel ? message.end(3) : message.end(2);
            int rawStart = rawIndexForVisibleIndex(original, visibleStart);
            int rawEnd = rawIndexForVisibleIndex(original, visibleEnd);
            if (rawStart < 0 || rawEnd < rawStart || rawEnd > original.length()) return null;
            rewritten = original.substring(0, rawStart) + targetDisplay + original.substring(rawEnd);
        } else {
            if (targetName.equalsIgnoreCase(sourceName)) return null;
            rewritten = RemoteDisguiseManager.replaceNameInFormatted(original, sourceName, targetName);
        }
        if (original.equals(rewritten)) return null;
        if (LocalDisguiseDebugCapture.isActive()) {
            LocalDisguiseDebugCapture.record("teleport-confirmation", sourceName,
                    "target=" + targetName + " input=" + original + " output=" + rewritten);
        }
        return rewritten;
    }

    private static boolean containsIgnoreCase(String text, String token) {
        if (text == null || token == null || token.isEmpty() || text.length() < token.length()) return false;
        int last = text.length() - token.length();
        for (int i = 0; i <= last; i++) {
            if (text.regionMatches(true, i, token, 0, token.length())) return true;
        }
        return false;
    }

    private static String targetDisplayName(String sourceName) {
        if (sourceName == null || sourceName.isEmpty()) return null;
        String displayName;
        String localSource = LocalDisguise.normalizeReplayProfileName(LocalDisguise.getRealName());
        if (LocalDisguise.isEnabled() && !localSource.isEmpty() && localSource.equalsIgnoreCase(sourceName)) {
            displayName = LocalDisguise.getReplaySelectorTargetDisplayName();
        } else if (LocalDisguise.isReplayLocalDisguiseSource(sourceName)) {
            displayName = LocalDisguise.getReplaySelectorTargetDisplayName();
        } else {
            displayName = RemoteDisguiseManager.getReplaySelectorTargetDisplayNameForSourceName(sourceName);
        }
        return displayName == null ? null : KillCounter.stripGuildTagFromDisguiseDisplay(displayName);
    }

    private static GameProfile targetProfile(String sourceName) {
        if (sourceName == null || sourceName.isEmpty()) return null;
        String localSource = LocalDisguise.normalizeReplayProfileName(LocalDisguise.getRealName());
        if (LocalDisguise.isEnabled() && !localSource.isEmpty() && localSource.equalsIgnoreCase(sourceName)) {
            return LocalDisguise.getTargetProfile();
        }
        if (LocalDisguise.isReplayLocalDisguiseSource(sourceName)) return LocalDisguise.getTargetProfile();
        LocalDisguise.RemoteDisguiseProfile remote =
                RemoteDisguiseManager.getDisguisedTargetProfileForSourceName(sourceName);
        return remote == null ? null : remote.profile;
    }

    private static GameProfile targetProfile(String sourceName, GameProfile ownerProfile) {
        GameProfile target = targetProfile(sourceName);
        if (target != null || ownerProfile == null) return target;
        Minecraft mc = Minecraft.getMinecraft();
        if (LocalDisguise.isEnabled() && mc != null && mc.thePlayer != null
                && ownerProfile.getId() != null && ownerProfile.getId().equals(mc.thePlayer.getUniqueID())) {
            return LocalDisguise.getTargetProfile();
        }
        LocalDisguise.RemoteDisguiseProfile remote = RemoteDisguiseManager.getDisguisedTargetProfile(ownerProfile);
        if (remote != null) return remote.profile;
        String resolvedSource = resolvePlayerName(ownerProfile.getId());
        return resolvedSource == null ? null : targetProfile(resolvedSource);
    }

    private static String targetNameOnly(String sourceName) {
        if (sourceName == null || sourceName.isEmpty()) return null;
        String localSource = LocalDisguise.normalizeReplayProfileName(LocalDisguise.getRealName());
        if (LocalDisguise.isEnabled() && !localSource.isEmpty() && localSource.equalsIgnoreCase(sourceName)) {
            String localTarget = LocalDisguise.getTargetTabChatDisplayNamePlain();
            return localTarget.isEmpty() ? LocalDisguise.fastTargetNameText : localTarget;
        }
        if (LocalDisguise.isReplayLocalDisguiseSource(sourceName)) {
            String localTarget = LocalDisguise.getTargetTabChatDisplayNamePlain();
            return localTarget.isEmpty() ? LocalDisguise.fastTargetNameText : localTarget;
        }
        return RemoteDisguiseManager.getDisguiseTargetNameForSourceName(sourceName);
    }

    private static String findSourceNameInDisplay(String formatted) {
        String visible = EnumChatFormatting.getTextWithoutFormattingCodes(formatted);
        if (visible == null) visible = formatted;
        String localName = LocalDisguise.normalizeReplayProfileName(LocalDisguise.getRealName());
        if (LocalDisguise.isEnabled() && containsNameToken(visible, localName)) {
            return localName;
        }
        localName = LocalDisguise.normalizeReplayProfileName(LocalDisguise.fastRealNameText);
        if (LocalDisguise.isReplayLocalDisguiseSource(localName) && containsNameToken(visible, localName)) return localName;
        return RemoteDisguiseManager.findDisguiseSourceNameInText(visible, 0);
    }

    private static String findPlayerNameInDisplay(String formatted) {
        if (formatted == null || formatted.isEmpty()) return null;
        String visible = EnumChatFormatting.getTextWithoutFormattingCodes(formatted);
        if (visible == null) visible = formatted;
        Matcher row = SELECTOR_PLAYER_LABEL.matcher(visible);
        return row.matches() ? normalizeSkullOwnerName(row.group(1)) : null;
    }

    private static boolean containsNameToken(String text, String name) {
        if (text == null || name == null || name.isEmpty()) return false;
        String lowerText = text.toLowerCase(java.util.Locale.ROOT);
        String lowerName = name.toLowerCase(java.util.Locale.ROOT);
        int at = lowerText.indexOf(lowerName);
        while (at >= 0) {
            int end = at + name.length();
            boolean left = at == 0 || !isNameCharacter(text.charAt(at - 1));
            boolean right = end == text.length() || !isNameCharacter(text.charAt(end));
            if (left && right) return true;
            at = lowerText.indexOf(lowerName, at + 1);
        }
        return false;
    }

    private static boolean isNameCharacter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_';
    }

    private static String getSkullOwnerName(ItemStack stack) {
        return getSkullOwnerName(stack, getSkullOwnerProfile(stack));
    }

    private static String getSkullOwnerName(ItemStack stack, GameProfile ownerProfile) {
        if (ownerProfile != null) {
            String normalized = normalizeSkullOwnerName(ownerProfile.getName());
            if (normalized != null) return normalized;
            String resolved = resolvePlayerName(ownerProfile.getId());
            if (resolved != null) return resolved;
            UUID idFromName = parseUuid(ownerProfile.getName());
            resolved = resolvePlayerName(idFromName);
            if (resolved != null) return resolved;
        }
        String raw = getRawSkullOwnerName(stack);
        String normalized = normalizeSkullOwnerName(raw);
        if (normalized != null) return normalized;
        return resolvePlayerName(parseUuid(raw));
    }

    private static GameProfile getSkullOwnerProfile(ItemStack stack) {
        NBTTagCompound root = stack.getTagCompound();
        if (root == null) return null;
        try {
            NBTTagCompound owner = root.hasKey("SkullOwner", 10) ? root.getCompoundTag("SkullOwner")
                    : root.hasKey("Owner", 10) ? root.getCompoundTag("Owner") : null;
            if (owner != null) {
                GameProfile profile = NBTUtil.readGameProfileFromNBT(owner);
                if (profile != null && (profile.getId() != null || profile.getName() != null)) return profile;
                String name = owner.getString("Name");
                if (!name.isEmpty()) return new GameProfile(parseUuid(name), name);
            }
            String name = root.hasKey("SkullOwner", 8) ? root.getString("SkullOwner")
                    : root.hasKey("Owner", 8) ? root.getString("Owner") : null;
            if (name != null && !name.isEmpty()) return new GameProfile(parseUuid(name), name);
        } catch (Throwable ignored) { }
        return null;
    }

    private static String getRawSkullOwnerName(ItemStack stack) {
        NBTTagCompound root = stack == null ? null : stack.getTagCompound();
        if (root == null) return null;
        try {
            if (root.hasKey("SkullOwner", 8)) return root.getString("SkullOwner");
            if (root.hasKey("SkullOwner", 10)) return root.getCompoundTag("SkullOwner").getString("Name");
            if (root.hasKey("Owner", 8)) return root.getString("Owner");
            if (root.hasKey("Owner", 10)) return root.getCompoundTag("Owner").getString("Name");
        } catch (Throwable ignored) { }
        return null;
    }

    private static String getStoredDisplayName(ItemStack stack) {
        try {
            NBTTagCompound root = stack == null ? null : stack.getTagCompound();
            if (root == null || !root.hasKey("display", 10)) return null;
            NBTTagCompound display = root.getCompoundTag("display");
            return display.hasKey("Name", 8) ? display.getString("Name") : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String normalizeSkullOwnerName(String rawName) {
        if (rawName == null || rawName.isEmpty()) return null;
        String visible = EnumChatFormatting.getTextWithoutFormattingCodes(rawName);
        if (visible == null) visible = rawName;
        Matcher matcher = SKULL_OWNER_PLAYER_NAME.matcher(visible.trim());
        return matcher.matches() ? matcher.group(1) : null;
    }

    private static String resolvePlayerName(UUID uuid) {
        if (uuid == null) return null;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return null;
        try {
            if (mc.getNetHandler() != null) {
                NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(uuid);
                if (info != null && info.getGameProfile() != null) {
                    String name = normalizeSkullOwnerName(info.getGameProfile().getName());
                    if (name != null) return name;
                }
            }
            if (mc.theWorld != null) {
                EntityPlayer player = mc.theWorld.getPlayerEntityByUUID(uuid);
                if (player != null && player.getGameProfile() != null) {
                    String name = normalizeSkullOwnerName(player.getGameProfile().getName());
                    if (name != null) return name;
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isEmpty()) return null;
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(value);
        if (plain == null) plain = value;
        try {
            return UUID.fromString(plain.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void recordRewrite(String sourceName, String input, String output) {
        if (!LocalDisguiseDebugCapture.isActive()) return;
        String localSource = LocalDisguise.normalizeReplayProfileName(LocalDisguise.getRealName());
        String target = LocalDisguise.isEnabled() && localSource.equalsIgnoreCase(sourceName)
                ? LocalDisguise.fastTargetNameText
                : LocalDisguise.isReplayLocalDisguiseSource(sourceName)
                ? LocalDisguise.fastTargetNameText
                : RemoteDisguiseManager.getDisguiseTargetNameForSourceName(sourceName);
        LocalDisguiseDebugCapture.record("player-selector", sourceName,
                "target=" + target + " input=" + input + " output=" + output);
    }

    private static void recordHeadRewrite(String sourceName, String targetName, boolean copied, String reason,
            GameProfile ownerProfile, ItemStack stack) {
        if (!LocalDisguiseDebugCapture.isActive()) return;
        String ownerName = ownerProfile == null ? null : ownerProfile.getName();
        UUID ownerId = ownerProfile == null ? null : ownerProfile.getId();
        LocalDisguiseDebugCapture.record("player-selector-head", sourceName,
                "target=" + targetName + " copied=" + copied + " result=" + reason
                        + " ownerName=" + ownerName + " ownerUuid=" + ownerId
                        + " itemName=" + getStoredDisplayName(stack));
    }
}
