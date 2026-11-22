package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.player.EntityPlayer;
import me.napim.gomod.render.VanillaPlayerNametagLayout;
import me.napim.gomod.util.GameContext;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Display changes after Lunar's label decorators for local and opted-in peer disguises. */
public final class LunarDisguiseDisplayCompatibility {
    private static final Pattern LEVEL_HEAD_LABEL = Pattern.compile("(?i)^Level:\\s*[0-9][0-9,]*$");
    private static volatile NativeTextBindings bindings;
    private static volatile long nativeSpectatorLabelChecks;
    private static volatile long nativeSpectatorLabelsSuppressed;
    private static volatile long nativeSpectatorLabelErrors;
    private static volatile String lastSuppressedSpectatorLabel = "none";
    private static volatile String lastSpectatorLabelError = "none";
    private static volatile long nativeSpectatorNameYAdjustments;
    private static volatile String lastNativeSpectatorNameYAdjustment = "none";
    private static final Map<String, String> NATIVE_SPECTATOR_ROW_Y_DEBUG = new LinkedHashMap<String, String>();
    private static final Object NON_PLAYER_LABEL = new Object();
    private static final class NametagFontScopes {
        final ArrayDeque<Object> owners = new ArrayDeque<>();
        final ArrayDeque<Boolean> finalized = new ArrayDeque<>();
    }
    private static final ThreadLocal<NametagFontScopes> FONT_SCOPES = new ThreadLocal<NametagFontScopes>() {
        @Override protected NametagFontScopes initialValue() { return new NametagFontScopes(); }
    };

    private LunarDisguiseDisplayCompatibility() { }

    /** Preserve the entity identity while Lunar draws a component as several plain font fragments. */
    public static void beginNametagFontScope(Object entity) {
        NametagFontScopes scopes = FONT_SCOPES.get();
        scopes.owners.push(entity instanceof EntityPlayer ? entity : NON_PLAYER_LABEL);
        scopes.finalized.push(Boolean.FALSE);
        if (entity instanceof EntityPlayer && LocalDisguiseDebugCapture.isActive()
                && GameContext.isHypixelReplayWatching(Minecraft.getMinecraft())) {
            EntityPlayer player = (EntityPlayer) entity;
            LocalDisguiseDebugCapture.record("replay-nametag-owner", String.valueOf(player.getUniqueID()),
                    "source=" + player.getName() + " localViewer=" + (Minecraft.getMinecraft().thePlayer == entity)
                            + " target=" + (LocalDisguise.isReplayLocalDisguiseSource(player.getName())
                            ? LocalDisguise.fastTargetNameText : RemoteDisguiseManager.getDisguisedNameFor(player)));
        }
    }

    public static void endNametagFontScope() {
        NametagFontScopes scopes = FONT_SCOPES.get();
        if (!scopes.owners.isEmpty()) scopes.owners.pop();
        if (!scopes.finalized.isEmpty()) scopes.finalized.pop();
    }

    public static EntityPlayer currentNametagFontPlayer() {
        Object owner = FONT_SCOPES.get().owners.peek();
        return owner instanceof EntityPlayer ? (EntityPlayer) owner : null;
    }

    public static boolean isFinalizedNametagFontScope() {
        return Boolean.TRUE.equals(FONT_SCOPES.get().finalized.peek());
    }

    private static void markFinalizedNametagFontScope(EntityPlayer player) {
        NametagFontScopes scopes = FONT_SCOPES.get();
        if (scopes.owners.peek() == player && !scopes.finalized.isEmpty()) {
            scopes.finalized.pop();
            scopes.finalized.push(Boolean.TRUE);
        }
    }

    /** Enforces the live spectator row policy where Lunar actually draws Adventure labels. */
    public static boolean shouldSuppressVisibleSpecsNativeLabel(Object entity, Object component) {
        if (!(entity instanceof EntityPlayer) || component == null) return false;
        String mode = VisibleSpecs.getNametagMode();
        if ((!VisibleSpecs.NAMETAG_MODE_ONLY_IGN.equals(mode) && !VisibleSpecs.NAMETAG_MODE_OFF.equals(mode))
                || !VisibleSpecs.isLiveSpectatorSettingsRuntimeActive()) return false;
        EntityPlayer player = (EntityPlayer) entity;
        if (!VisibleSpecs.shouldSuppressHealthNametag(player)) return false;
        nativeSpectatorLabelChecks++;
        try {
            NativeTextBindings text = bindings;
            if (text == null || !text.component.isInstance(component)) {
                text = new NativeTextBindings(component.getClass().getClassLoader());
                bindings = text;
            }
            String label = (String) text.serialize.invoke(text.serializer, component);
            String clean = label == null ? "" : net.minecraft.util.EnumChatFormatting.getTextWithoutFormattingCodes(label);
            clean = clean == null ? (label == null ? "" : label.trim()) : clean.trim();
            String killerLabel = SpectatorKillerNametagOverlay.shouldShowFor(player)
                    ? SpectatorKillerNametagOverlay.buildLabel(player) : null;
            String cleanKiller = killerLabel == null ? ""
                    : net.minecraft.util.EnumChatFormatting.getTextWithoutFormattingCodes(killerLabel).trim();
            boolean killer = GomodPlayerNametagLabels.isRenderingKillerLabel()
                    || !cleanKiller.isEmpty() && cleanKiller.equals(clean);
            boolean primary = !killer && GomodPlayerNametagLabels.isPrimaryPlayerNameLabel(player, label);
            boolean levelHead = !killer && !primary
                    && VisibleSpecs.NAMETAG_MODE_ONLY_IGN.equals(mode)
                    && LunarNametagCompatibility.ONLY_IGN_SHOW_LEVEL_HEAD
                    && LEVEL_HEAD_LABEL.matcher(clean).matches();
            boolean suppress = !killer && (VisibleSpecs.NAMETAG_MODE_OFF.equals(mode) || !primary && !levelHead);
            String decision = suppress ? "lunar-final-label-blocked"
                    : killer ? "lunar-final-killer-kept"
                    : levelHead ? "lunar-onlyign-levelhead-kept" : "lunar-final-ign-kept";
            VisibleSpecs.recordNametagRenderDebug(player, label, primary, !primary && !killer, suppress, decision);
            if (suppress) {
                nativeSpectatorLabelsSuppressed++;
                if (clean.length() > 100) clean = clean.substring(0, 100);
                lastSuppressedSpectatorLabel = player.getName() + " text=" + clean;
            }
            return suppress;
        } catch (Throwable error) {
            nativeSpectatorLabelErrors++;
            lastSpectatorLabelError = error.getClass().getSimpleName();
            return false;
        }
    }

    /**
     * Lunar bypasses RenderPlayer's vanilla tab-health row layout. When Only IGN suppresses that close-range row,
     * move the primary IGN back into its slot, matching the correction already used by Forge's RenderPlayer path.
     */
    public static double adjustVisibleSpecsNativeNameLabelY(Object entity, Object component, double y) {
        if (!(entity instanceof AbstractClientPlayer) || component == null) return y;
        String mode = VisibleSpecs.getNametagMode();
        if (!VisibleSpecs.NAMETAG_MODE_ONLY_IGN.equals(mode)
                || !VisibleSpecs.isLiveSpectatorSettingsRuntimeActive()
                || !VisibleSpecs.shouldSuppressHealthNametag((Entity) entity)) return y;
        EntityPlayer player = (EntityPlayer) entity;
        Minecraft mc = Minecraft.getMinecraft();
        Entity view = mc == null ? null : mc.getRenderViewEntity();
        double distanceSq = view == null ? -1.0D : player.getDistanceSqToEntity(view);
        boolean closeHealthRow = view != null && VanillaPlayerNametagLayout.wouldRenderTabHealthNametagRow(
                (AbstractClientPlayer) player, distanceSq);
        try {
            NativeTextBindings text = bindings;
            if (text == null || !text.component.isInstance(component)) {
                text = new NativeTextBindings(component.getClass().getClassLoader());
                bindings = text;
            }
            String label = (String) text.serialize.invoke(text.serializer, component);
            String clean = net.minecraft.util.EnumChatFormatting.getTextWithoutFormattingCodes(label);
            clean = clean == null ? label.trim() : clean.trim();
            boolean primary = GomodPlayerNametagLabels.isPrimaryPlayerNameLabel(player, label);
            boolean levelHead = LEVEL_HEAD_LABEL.matcher(clean).matches();
            String killerLabel = SpectatorKillerNametagOverlay.shouldShowFor(player)
                    ? SpectatorKillerNametagOverlay.buildLabel(player) : null;
            String cleanKiller = killerLabel == null ? ""
                    : net.minecraft.util.EnumChatFormatting.getTextWithoutFormattingCodes(killerLabel);
            boolean killer = GomodPlayerNametagLabels.isRenderingKillerLabel()
                    || cleanKiller != null && cleanKiller.trim().equals(clean);
            String kind = primary ? "IGN" : levelHead ? "LevelHead" : killer ? "Killer" : "other";

            // A method can delegate to the other patched Lunar label method. Only rebase at the outer call so a
            // delegated draw does not apply the same one-row correction twice.
            ArrayDeque<Object> owners = FONT_SCOPES.get().owners;
            boolean outerDraw = owners.size() == 1 && owners.peek() == player;
            double adjusted = y;
            double correction = 0.0D;
            if (primary && closeHealthRow && outerDraw && mc != null) {
                int fontHeight = mc.fontRendererObj == null ? 9 : mc.fontRendererObj.FONT_HEIGHT;
                correction = fontHeight * VanillaPlayerNametagLayout.VANILLA_NAMETAG_LINE_GAP_FACTOR
                        * VanillaPlayerNametagLayout.VANILLA_WORLD_LABEL_SCALE;
                adjusted = y - correction;
                nativeSpectatorNameYAdjustments++;
                lastNativeSpectatorNameYAdjustment = player.getName() + " text=" + clean
                        + " distanceSq=" + String.format(java.util.Locale.ROOT, "%.2f", distanceSq)
                        + " y=" + String.format(java.util.Locale.ROOT, "%.5f", y)
                        + "->" + String.format(java.util.Locale.ROOT, "%.5f", adjusted)
                        + " step=" + String.format(java.util.Locale.ROOT, "%.5f", correction);
            }
            recordNativeSpectatorRowY(player, kind, clean, distanceSq, closeHealthRow, y, adjusted);
            return adjusted;
        } catch (Throwable error) {
            lastSpectatorLabelError = "name-y:" + error.getClass().getSimpleName();
            return y;
        }
    }

    public static String describeVisibleSpecsNativeLabelDebug() {
        return "checks=" + nativeSpectatorLabelChecks + " suppressed=" + nativeSpectatorLabelsSuppressed
                + " errors=" + nativeSpectatorLabelErrors + " nameYAdjustments=" + nativeSpectatorNameYAdjustments
                + " lastNameYAdjustment={" + lastNativeSpectatorNameYAdjustment + "} lastBlocked={"
                + lastSuppressedSpectatorLabel + "} lastError=" + lastSpectatorLabelError;
    }

    public static String describeVisibleSpecsNativeRowYDebug(String playerName) {
        if (playerName == null) return "none";
        StringBuilder result = new StringBuilder();
        synchronized (NATIVE_SPECTATOR_ROW_Y_DEBUG) {
            for (Map.Entry<String, String> row : NATIVE_SPECTATOR_ROW_Y_DEBUG.entrySet()) {
                if (!row.getKey().startsWith(playerName + "|")) continue;
                if (result.length() > 0) result.append("; ");
                result.append(row.getValue());
            }
        }
        return result.length() == 0 ? "none" : result.toString();
    }

    private static void recordNativeSpectatorRowY(EntityPlayer player, String kind, String label,
            double distanceSq, boolean closeHealthRow, double before, double after) {
        String shownLabel = label == null ? "<null>" : label.replace('\r', ' ').replace('\n', ' ');
        if (shownLabel.length() > 36) shownLabel = shownLabel.substring(0, 36);
        String key = player.getName() + "|" + kind;
        String value = kind + "[" + shownLabel + "] d2=" + String.format(java.util.Locale.ROOT, "%.2f", distanceSq)
                + " vanillaHealthRow=" + closeHealthRow
                + " y=" + String.format(java.util.Locale.ROOT, "%.5f", before)
                + "->" + String.format(java.util.Locale.ROOT, "%.5f", after);
        synchronized (NATIVE_SPECTATOR_ROW_Y_DEBUG) {
            NATIVE_SPECTATOR_ROW_Y_DEBUG.put(key, value);
            while (NATIVE_SPECTATOR_ROW_Y_DEBUG.size() > 80) {
                String oldest = NATIVE_SPECTATOR_ROW_Y_DEBUG.keySet().iterator().next();
                NATIVE_SPECTATOR_ROW_Y_DEBUG.remove(oldest);
            }
        }
    }

    private static final Map<java.util.UUID, ChatNameComponentCache> CHAT_NAME_COMPONENTS = new LinkedHashMap<>();
    private static Object chatNameComponentWorld;

    private static final class ChatNameComponentCache {
        Object input, output;
        String inputText, wanted, outputText;
        boolean finalized;
    }

    public static Object rewriteNametagComponent(Object entity, Object original) {
        Object rewritten = rewriteNametagComponentInternal(entity, original);
        if (!(entity instanceof EntityPlayer) || rewritten == null) return rewritten;
        EntityPlayer player = (EntityPlayer) entity;
        boolean sharedSpectator=SpectatorRelay.shouldColorNametag(player);
        String killEffectColor = sharedSpectator ? null : GomodPlayerNametagLabels.getKillEffectNametagColor(player);
        String wanted = sharedSpectator ? "\u00a79" : BlitzChatNameDisplay.getNametagName(player);
        if (wanted == null && killEffectColor != null) {
            wanted = killEffectColor + ":" + LocalDisguise.getDisplayRevision();
        }
        if (wanted == null) return rewritten;
        try {
            Object world = Minecraft.getMinecraft().theWorld;
            if (chatNameComponentWorld != world) {
                CHAT_NAME_COMPONENTS.clear();
                chatNameComponentWorld = world;
            }
            ChatNameComponentCache cache = CHAT_NAME_COMPONENTS.get(player.getUniqueID());
            if (cache != null && cache.input == rewritten && wanted.equals(cache.wanted)) {
                if (cache.finalized) markFinalizedNametagFontScope(player);
                return cache.output;
            }
            NativeTextBindings text = bindings;
            if (text == null || !text.component.isInstance(rewritten)) {
                text = new NativeTextBindings(rewritten.getClass().getClassLoader());
                bindings = text;
            }
            String before = cache != null && cache.input == rewritten ? cache.inputText
                    : (String) text.serialize.invoke(text.serializer, rewritten);
            boolean sharedName=sharedSpectator&&GomodPlayerNametagLabels.isPrimaryPlayerNameLabel(player,before);
            String after = sharedSpectator
                    ? sharedName ? SpectatorRelay.colorNametag(player,before) : before
                    : killEffectColor != null ? GomodPlayerNametagLabels.applyKillEffectNametagColor(player, before)
                    : BlitzChatNameDisplay.decorateNametag(player, before);
            Object output = before.equals(after) ? rewritten
                    : cache != null && after.equals(cache.outputText) ? cache.output
                    : text.deserialize.invoke(text.serializer, after);
            if (cache == null) {
                if (CHAT_NAME_COMPONENTS.size() >= 256) CHAT_NAME_COMPONENTS.clear();
                cache = new ChatNameComponentCache();
                CHAT_NAME_COMPONENTS.put(player.getUniqueID(), cache);
            }
            cache.input = rewritten;
            cache.inputText = before;
            cache.wanted = wanted;
            cache.outputText = after;
            cache.output = output;
            cache.finalized = sharedName || killEffectColor != null && !before.equals(after)
                    || BlitzChatNameDisplay.shouldPreserveRenderedPlayerText(after);
            if (cache.finalized) markFinalizedNametagFontScope(player);
            return output;
        } catch (Throwable failure) {
            LunarConnectionDiagnostics.recordOnce("tab.chat_style_nametag_error",
                    failure.getClass().getName(), "type=" + failure.getClass().getSimpleName());
            return rewritten;
        }
    }

    private static Object rewriteNametagComponentInternal(Object entity, Object original) {
        if (original == null || (!(entity instanceof EntityPlayer) && !(entity instanceof EntityArmorStand))) return original;
        if (entity instanceof EntityArmorStand && !LocalDisguise.fastEnabledFlag
                && !RemoteDisguiseManager.hasEntries()
                && !LeaderboardHologramDebugCapture.isActive()) return original;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return original;
        boolean localPlayer = mc.thePlayer == entity;
        try {
            NativeTextBindings text = bindings;
            if (text == null || !text.component.isInstance(original)) {
                text = new NativeTextBindings(original.getClass().getClassLoader());
                bindings = text;
            }
            // Lunar's normal entity labels are Adventure components, bypassing Render.renderLivingLabel
            // and the String FontRenderer hooks. Hypixel leaderboard stands use this same native path.
            if (entity instanceof EntityArmorStand) {
                String before = (String) text.serialize.invoke(text.serializer, original);
                String after = LocalDisguise.rewriteLunarLeaderboardTextHologram(before);
                // Apply peer disguises after the local rewrite too: a #rekt line can contain a local
                // killer and a separately disguised remote victim (or the reverse).
                after = RemoteDisguiseManager.rewriteLeaderboardHologramText(after);
                LeaderboardHologramDebugCapture.recordRenderLabelEntry((Entity) entity, before);
                LeaderboardHologramDebugCapture.recordArmorStandLabel((Entity) entity, before, after,
                        LocalDisguise.fastEnabledFlag || RemoteDisguiseManager.hasEntries());
                return before.equals(after) ? original : text.deserialize.invoke(text.serializer, after);
            }
            boolean replay = GameContext.isHypixelReplayWatching(mc);
            if (replay) {
                EntityPlayer replayPlayer = (EntityPlayer) entity;
                String username = replayPlayer.getGameProfile() == null
                        ? replayPlayer.getName()
                        : LocalDisguise.normalizeReplayProfileName(replayPlayer.getGameProfile().getName());
                String before = (String) text.serialize.invoke(text.serializer, original);
                // The recorded copy of the local account is not mc.thePlayer. Always apply its
                // own disguise before considering unrelated /disguiser entries for other actors.
                if (LocalDisguise.isReplayLocalDisguiseSource(username)) {
                    String after = LocalDisguise.rewriteReplayNametagForRender(before, username, localPlayer);
                    return before.equals(after) ? original : text.deserialize.invoke(text.serializer, after);
                }
                String alias = LocalDisguise.isReplayLocalDisguiseSource(username)
                        ? LocalDisguise.fastTargetNameText : RemoteDisguiseManager.getDisguisedNameFor(replayPlayer);
                if (!LocalDisguise.isNameLabelForDebug(before, username)
                        && !LocalDisguise.isNameLabelForDebug(before, alias)) return original;
                String replayViewer = localPlayer
                        ? LocalDisguise.rewriteReplayViewerLabelForRender(before, username) : null;
                if (replayViewer != null) {
                    return before.equals(replayViewer) ? original : text.deserialize.invoke(text.serializer, replayViewer);
                }
                if (!localPlayer && alias != null && !alias.isEmpty()) {
                    // Replay disguises identify the recorded actor by the target IGN only. Avoid routing
                    // through the lobby formatter, which would reintroduce the target's rank and guild.
                    String replayDisguise = "\u00a7f" + alias;
                    return before.equals(replayDisguise)
                            ? original : text.deserialize.invoke(text.serializer, replayDisguise);
                }
                if (LocalDisguise.isReplayLocalDisguiseSource(username) || !RemoteDisguiseManager.hasEntries()) {
                    String after = LocalDisguise.rewriteReplayNametagForRender(before, username, localPlayer);
                    return before.equals(after) ? original : text.deserialize.invoke(text.serializer, after);
                }
            }
            boolean hypixel = GameContext.isOnHypixel(mc);
            String real;
            String replacement;
            Object rewritten;
            if (localPlayer && LocalDisguise.fastEnabledFlag && LocalDisguise.shouldApplyTabDisplayOverride()) {
                real = LocalDisguise.fastRealNameText;
                if (real == null || real.isEmpty()) return original;
                if (hypixel) {
                    String before = (String) text.serialize.invoke(text.serializer, original);
                    String after = LocalDisguise.buildNametagDisplayName(before, real);
                    rewritten = before.equals(after) ? original : text.deserialize.invoke(text.serializer, after);
                    if (LocalDisguiseDebugCapture.isActive()) {
                        LocalDisguiseDebugCapture.recordFinalPlayerLabel("lunar-native-label", before,
                                (String) text.serialize.invoke(text.serializer, rewritten));
                    }
                } else {
                    replacement = LocalDisguise.buildNametagDisplayName(real, real);
                    rewritten = text.replace(original, real, replacement);
                }
            } else {
                if (!RemoteDisguiseManager.hasEntries()) return original;
                EntityPlayer player = (EntityPlayer) entity;
                real = player.getName();
                replacement = RemoteDisguiseManager.getDisguisedNameFor(player);
                if (real == null || real.isEmpty() || replacement == null || replacement.isEmpty()) return original;
                if (hypixel) {
                    String before = (String) text.serialize.invoke(text.serializer, original);
                    String sourceWithoutGuild = KillCounter.stripGuildTagFromDisguiseDisplay(before);
                    String ranked = RemoteDisguiseManager.getDisguisedDisplayNameFor(player, sourceWithoutGuild);
                    if (ranked == null || ranked.isEmpty()) ranked = "\u00a77" + replacement;
                    // Lunar's source component can already end in the source player's guild. The target row
                    // returned by RemoteDisguiseManager includes the target guild, so keep the source suffix
                    // from being left behind after replacing the rank/name with that complete target row.
                    String after = replaceHypixelRankedName(sourceWithoutGuild, real, ranked);
                    if (sourceWithoutGuild.equals(after)) {
                        after = replaceHypixelRankedName(sourceWithoutGuild, replacement, ranked);
                    }
                    if (sourceWithoutGuild.equals(after)
                            && !containsNameToken(sourceWithoutGuild, real)
                            && !containsNameToken(sourceWithoutGuild, replacement)) {
                        // This was not the primary name row; leave other Lunar label text alone.
                        after = before;
                    }
                    rewritten = before.equals(after) ? original : text.deserialize.invoke(text.serializer, after);
                } else {
                    rewritten = text.replace(original, real, replacement);
                }
            }
            Entity player = (Entity) entity;
            if (replay) {
                String before = (String) text.serialize.invoke(text.serializer, rewritten);
                String replayViewer = localPlayer
                        ? LocalDisguise.rewriteReplayViewerLabelForRender(before, player.getName()) : null;
                String after = replayViewer == null
                        ? LocalDisguise.forceReplayTextWhite(before) : replayViewer;
                if (!before.equals(after)) rewritten = text.deserialize.invoke(text.serializer, after);
            }
            boolean changed = !original.equals(rewritten);
            LunarConnectionDiagnostics.recordOnce("disguise.lunar_native_nametag",
                    "local=" + localPlayer + " sneaking=" + player.isSneaking() + " changed=" + changed,
                    "nativeLabelReached=true localPlayer=" + localPlayer + " sneaking=" + player.isSneaking()
                            + " changed=" + changed + " hypixelRankRewritten=" + hypixel);
            return rewritten;
        } catch (Throwable failure) {
            LunarConnectionDiagnostics.recordOnce("disguise.lunar_native_nametag_error",
                    failure.getClass().getName(), "type=" + failure.getClass().getSimpleName());
            return original;
        }
    }

    /** Replaces the source rank and IGN together, even when native text splits them across components. */
    public static String replaceHypixelRankedName(String formatted, String real, String replacement) {
        if (formatted == null || real == null || real.isEmpty() || replacement == null || replacement.isEmpty()) {
            return formatted;
        }
        StringBuilder visible = new StringBuilder();
        int[] positions = new int[formatted.length()];
        for (int i = 0; i < formatted.length(); i++) {
            if (formatted.charAt(i) == '\u00a7' && i + 1 < formatted.length()) { i++; continue; }
            positions[visible.length()] = i;
            visible.append(formatted.charAt(i));
        }
        Matcher name = Pattern.compile("(?i)(?<![A-Za-z0-9_])" + Pattern.quote(real)
                + "(?![A-Za-z0-9_])").matcher(visible);
        if (!name.find()) {
            // Hypixel masks Blitz nametags as §kXXXX<IGN>. AntiShuffle's FontRenderer hook removes §k and
            // XXXX late in the draw path, so a disguise applied only after that stage lets the real IGN flash
            // in the cages. Resolve the exact mask here, before Lunar creates/draws its split text fragments.
            int maskedStart = findPlainHypixelMask(visible.toString(), real);
            if (maskedStart < 0) return formatted;
            int maskedEnd = maskedStart + 4 + real.length();
            int replacementStart = maskedStart;
            Matcher rank = Pattern.compile("(?i)\\[(?:VIP\\+?|MVP\\+{0,2}|YOUTUBE|YOUTUBER|ADMIN|MOD|HELPER|"
                    + "GM|GAME MASTER|OWNER|STAFF|PIG\\+{0,3}|EVENTS|INNIT|\\u12de)\\]\\s*$")
                    .matcher(visible.substring(0, maskedStart));
            if (rank.find()) replacementStart = rank.start();
            int rawStart = positions[replacementStart];
            while (rawStart >= 2 && formatted.charAt(rawStart - 2) == '§') rawStart -= 2;
            int rawEnd = positions[maskedEnd - 1] + 1;
            return formatted.substring(0, rawStart) + "§r" + replacement + "§r"
                    + formatted.substring(rawEnd);
        }
        int start = name.start();
        Matcher rank = Pattern.compile("(?i)\\[(?:VIP\\+?|MVP\\+{0,2}|YOUTUBE|YOUTUBER|ADMIN|MOD|HELPER|"
                + "GM|GAME MASTER|OWNER|STAFF|PIG\\+{0,3}|EVENTS|INNIT|\\u12de)\\]\\s*$")
                .matcher(visible.substring(0, start));
        if (rank.find()) start = rank.start();
        int rawStart = positions[start];
        while (rawStart >= 2 && formatted.charAt(rawStart - 2) == '\u00a7') rawStart -= 2;
        int rawEnd = positions[name.end() - 1] + 1;
        return formatted.substring(0, rawStart) + "\u00a7r" + replacement + "\u00a7r" + formatted.substring(rawEnd);
    }

    /**
     * Rewrites Hypixel's obfuscated local-name placeholder, emitted as {@code \u00a7kXXXX<IGN>} in Blitz matches.
     * The placeholder makes the normal standalone-IGN matcher reject the real name because it is preceded by Xs.
     */
    public static String replaceObfuscatedHypixelName(String formatted, String real, String replacement) {
        if (formatted == null || real == null || real.isEmpty() || replacement == null || replacement.isEmpty()) {
            return formatted;
        }
        for (int format = formatted.indexOf('\u00a7'); format >= 0 && format + 1 < formatted.length();
             format = formatted.indexOf('\u00a7', format + 2)) {
            if (Character.toLowerCase(formatted.charAt(format + 1)) != 'k') {
                continue;
            }
            int runStart = format + 2;
            int runEnd = formatted.indexOf('\u00a7', runStart);
            if (runEnd < 0) {
                runEnd = formatted.length();
            }
            String run = formatted.substring(runStart, runEnd);
            int prefixLength = run.length() - real.length();
            if (prefixLength != 4 || !run.regionMatches(true, prefixLength, real, 0, real.length())) {
                continue;
            }
            boolean placeholder = true;
            for (int i = 0; i < prefixLength; i++) {
                if (Character.toLowerCase(run.charAt(i)) != 'x') {
                    placeholder = false;
                    break;
                }
            }
            if (placeholder) {
                return formatted.substring(0, format) + replacement + formatted.substring(runEnd);
            }
        }
        // AntiShuffle's cleanup can strip both §k and the formatting control before Lunar reaches FontRenderer,
        // leaving the literal mask in plain text. Keep this exact four-X form targeted so ordinary names that
        // merely contain Xs are never rewritten.
        int masked = findPlainHypixelMask(formatted, real);
        if (masked >= 0) {
            int end = masked + 4 + real.length();
            return formatted.substring(0, masked) + replacement + formatted.substring(end);
        }
        return formatted;
    }

    /** True for Hypixel's exact §kXXXX<IGN> form or AntiShuffle's plain XXXX<IGN> form. */
    public static boolean containsObfuscatedHypixelName(String formatted, String real) {
        if (formatted == null || real == null || real.isEmpty()) return false;
        // Most rendered strings never contain the four-character Hypixel mask; avoid scanning every style code
        // in FontRenderer's hot path for those unrelated calls.
        if (!hasHypixelMaskMarker(formatted)) return false;
        for (int format = formatted.indexOf('\u00a7'); format >= 0 && format + 1 < formatted.length();
             format = formatted.indexOf('\u00a7', format + 2)) {
            if (Character.toLowerCase(formatted.charAt(format + 1)) != 'k') continue;
            int runStart = format + 2;
            int runEnd = formatted.indexOf('\u00a7', runStart);
            if (runEnd < 0) runEnd = formatted.length();
            if (isExactHypixelMask(formatted.substring(runStart, runEnd), real)) return true;
        }
        return findPlainHypixelMask(formatted, real) >= 0;
    }

    public static boolean hasHypixelMaskMarker(String text) {
        if (text == null) return false;
        for (int at = 0; at + 4 <= text.length(); at++) {
            if (text.regionMatches(true, at, "XXXX", 0, 4)) return true;
        }
        return false;
    }

    private static int findPlainHypixelMask(String text, String real) {
        if (text == null || real == null || real.isEmpty()) return -1;
        for (int at = 0; at + 4 + real.length() <= text.length(); at++) {
            if (!text.regionMatches(true, at, "XXXX", 0, 4)) continue;
            int end = at + 4 + real.length();
            int boundary = at;
            while (boundary >= 2 && text.charAt(boundary - 2) == '\u00a7') boundary -= 2;
            if ((boundary == 0 || !isUsernameChar(text.charAt(boundary - 1)))
                    && end <= text.length()
                    && text.regionMatches(true, at + 4, real, 0, real.length())
                    && (end == text.length() || !isUsernameChar(text.charAt(end)))) {
                return at;
            }
        }
        return -1;
    }

    private static boolean isExactHypixelMask(String run, String real) {
        if (run == null || real == null || run.length() != real.length() + 4
                || !run.regionMatches(true, 4, real, 0, real.length())) return false;
        for (int i = 0; i < 4; i++) {
            if (Character.toLowerCase(run.charAt(i)) != 'x') return false;
        }
        return true;
    }

    private static boolean isUsernameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_';
    }

    private static boolean containsNameToken(String formatted, String name) {
        if (formatted == null || name == null || name.isEmpty()) return false;
        String visible = net.minecraft.util.EnumChatFormatting.getTextWithoutFormattingCodes(formatted);
        return visible != null && Pattern.compile("(?i)(?<![A-Za-z0-9_])" + Pattern.quote(name)
                + "(?![A-Za-z0-9_])").matcher(visible).find();
    }

    private static final class NativeTextBindings {
        final Class<?> component;
        final Object serializer;
        final Method deserialize, serialize, builder, match, replacement, build, replaceText;

        NativeTextBindings(ClassLoader loader) throws Exception {
            String root = "com.moonsworth.lunar.lib.adventure.text.";
            component = Class.forName(root + "Component", false, loader);
            Class<?> config = Class.forName(root + "TextReplacementConfig", false, loader);
            Class<?> configBuilder = Class.forName(root + "TextReplacementConfig$Builder", false, loader);
            Class<?> componentLike = Class.forName(root + "ComponentLike", false, loader);
            Class<?> serializerType = Class.forName(root + "serializer.legacy.LegacyComponentSerializer", false, loader);
            serializer = serializerType.getMethod("legacySection").invoke(null);
            deserialize = serializerType.getMethod("deserialize", String.class);
            serialize = serializerType.getMethod("serialize", component);
            builder = config.getMethod("builder");
            match = configBuilder.getMethod("match", Pattern.class);
            replacement = configBuilder.getMethod("replacement", componentLike);
            build = configBuilder.getMethod("build");
            replaceText = component.getMethod("replaceText", config);
        }

        Object replace(Object original, String real, String replacementText) throws Exception {
            Object configBuilder = builder.invoke(null);
            match.invoke(configBuilder, Pattern.compile("(?i)(?<![A-Za-z0-9_])" + Pattern.quote(real)
                    + "(?![A-Za-z0-9_])"));
            replacement.invoke(configBuilder, deserialize.invoke(serializer, replacementText));
            return replaceText.invoke(original, build.invoke(configBuilder));
        }
    }
}
