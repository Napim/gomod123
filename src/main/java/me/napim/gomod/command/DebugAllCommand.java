package me.napim.gomod.command;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import me.napim.gomod.GomodReleaseBuild;
import me.napim.gomod.GomodVersion;
import me.napim.gomod.Main;
import me.napim.gomod.features.BlitzTeamsDetector;
import me.napim.gomod.features.ClientTickHub;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.FeatureProfiler;
import me.napim.gomod.features.ReflectionLogger;
import me.napim.gomod.features.RenderProfiler;
import me.napim.gomod.util.GameContext;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.IAnimals;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.PotionEffect;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** Creates one privacy-aware, read-only support snapshot for every release flavor. */
public final class DebugAllCommand extends Command {
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_NBT_CHARS = 3000;
    private static final int MAX_LOG_BYTES = 768 * 1024;
    private static final int MAX_LOG_LINES = 120;

    public DebugAllCommand() {
        super("debugall");
    }

    @Override
    public void handle(String[] args) {
        Minecraft mc = Minecraft.getMinecraft();
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Creating debug snapshot...");
        try {
            File base = GomodPaths.getGomod123Dir();
            File dir = new File(base, "debugall");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("Could not create " + dir.getAbsolutePath());
            }

            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
            File out = new File(dir, "debugall_" + stamp + ".txt");
            File latest = new File(dir, "debugall_latest.txt");
            String report = buildReport(mc);
            byte[] bytes = report.getBytes(StandardCharsets.UTF_8);
            Files.write(out.toPath(), bytes);
            Files.write(latest.toPath(), bytes);

            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Debug report saved ("
                    + humanBytes(bytes.length) + "): " + EnumChatFormatting.WHITE + out.getAbsolutePath());
            Main.sendMessage(EnumChatFormatting.YELLOW
                    + "Review it before sending if it contains elements you don't want to share "
                    + "(=== BUILD AND PLATFORM === section).");
        } catch (Throwable t) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Could not create debug report: "
                    + EnumChatFormatting.WHITE + safe(t.getMessage()));
            t.printStackTrace();
        }
    }

    private static String buildReport(Minecraft mc) {
        StringBuilder sb = new StringBuilder(96 * 1024);
        line(sb, "gomod123 /debugall support snapshot");
        line(sb, "generated_at_local=" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(new Date()));
        line(sb, "generated_at_utc=" + utcNow());
        line(sb, "privacy_notice=Review before sharing. Player name, server, coordinates, scoreboard, inventory and selected log warnings are included. Credentials and social/account collections are redacted.");
        line(sb, "snapshot_behavior=Read-only point-in-time snapshot; no profiler or expensive world scan was started.");

        section(sb, "BUILD AND PLATFORM");
        appendBuildAndPlatform(sb, mc);
        section(sb, "PERFORMANCE SNAPSHOT");
        appendPerformance(sb, mc);
        section(sb, "CONNECTION AND GAME CONTEXT");
        appendGameContext(sb, mc);
        section(sb, "PLAYER STATE");
        appendPlayer(sb, mc);
        section(sb, "INVENTORY");
        appendInventory(sb, mc);
        section(sb, "SCOREBOARD");
        appendScoreboard(sb, mc);
        section(sb, "TAB LIST SUMMARY");
        appendTabList(sb, mc);
        section(sb, "LOADED MODS");
        appendLoadedMods(sb);
        section(sb, "GOMOD RUNTIME / PROFILERS");
        appendRuntime(sb);
        section(sb, "ALL EFFECTIVE GOMOD SETTINGS (SANITIZED)");
        appendSettings(sb);
        section(sb, "RECENT RELEVANT WARNINGS / ERRORS");
        appendRecentLog(sb);
        appendReflectionLog(sb);
        section(sb, "END");
        line(sb, "If the problem is lag, reproduce it first and run /debugall while it is happening.");
        return sb.toString();
    }

    private static void appendBuildAndPlatform(StringBuilder sb, Minecraft mc) {
        kv(sb, "gomod.version", GomodVersion.get());
        kv(sb, "gomod.release_flavor", GomodReleaseBuild.PUBLIC_RELEASE ? "public" : "internal/dev");
        kv(sb, "minecraft.version", safe(Minecraft.getMinecraft().getVersion()));
        kv(sb, "java.version", System.getProperty("java.version"));
        kv(sb, "java.vendor", System.getProperty("java.vendor"));
        kv(sb, "jvm", System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
        kv(sb, "os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        kv(sb, "processors", Runtime.getRuntime().availableProcessors());
        kv(sb, "processor_identifier", System.getenv("PROCESSOR_IDENTIFIER"));
        kv(sb, "jvm_arguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
        try {
            kv(sb, "gpu.vendor", GL11.glGetString(GL11.GL_VENDOR));
            kv(sb, "gpu.renderer", GL11.glGetString(GL11.GL_RENDERER));
            kv(sb, "gpu.opengl_version", GL11.glGetString(GL11.GL_VERSION));
        } catch (Throwable t) { error(sb, "gpu", t); }
        try {
            File gameDir = GomodPaths.getMinecraftGameDir();
            kv(sb, "game_directory_disk", "path=" + gameDir.getAbsolutePath() + " usable=" + humanBytes(gameDir.getUsableSpace())
                    + " total=" + humanBytes(gameDir.getTotalSpace()));
        } catch (Throwable t) { error(sb, "game_directory_disk", t); }
        try { kv(sb, "display", mc.displayWidth + "x" + mc.displayHeight + " fullscreen=" + mc.isFullScreen()); } catch (Throwable t) { error(sb, "display", t); }
        try {
            GameSettings g = mc.gameSettings;
            kv(sb, "graphics", "fancy=" + g.fancyGraphics + " renderDistanceChunks=" + g.renderDistanceChunks
                    + " fpsLimit=" + g.limitFramerate + " vsync=" + g.enableVsync + " guiScale=" + g.guiScale
                    + " particles=" + g.particleSetting + " mipmaps=" + g.mipmapLevels + " clouds=" + g.clouds
                    + " entityShadows=" + g.entityShadows + " language=" + g.language);
            kv(sb, "resource_packs", String.valueOf(g.resourcePacks));
        } catch (Throwable t) { error(sb, "game_settings", t); }
        try { kv(sb, "current_screen", mc.currentScreen == null ? "<none>" : mc.currentScreen.getClass().getName()); } catch (Throwable t) { error(sb, "screen", t); }
    }

    private static void appendPerformance(StringBuilder sb, Minecraft mc) {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        kv(sb, "fps", Minecraft.getDebugFPS());
        kv(sb, "heap", "used=" + humanBytes(used) + " committed=" + humanBytes(rt.totalMemory()) + " max=" + humanBytes(rt.maxMemory()));
        try {
            MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
            kv(sb, "non_heap", "used=" + humanBytes(nonHeap.getUsed()) + " committed=" + humanBytes(nonHeap.getCommitted()));
        } catch (Throwable t) { error(sb, "non_heap", t); }
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            kv(sb, "gc." + gc.getName(), "collections=" + gc.getCollectionCount() + " timeMs=" + gc.getCollectionTime());
        }
        appendThreadSummary(sb);
        if (mc != null && mc.theWorld != null) {
            int players = 0, living = 0, items = 0, hostile = 0, passive = 0;
            List<Entity> entities = mc.theWorld.loadedEntityList;
            for (Entity e : entities) {
                if (e instanceof EntityPlayer) players++;
                if (e instanceof EntityLivingBase) living++;
                if (e instanceof EntityItem) items++;
                if (e instanceof IMob) hostile++;
                if (e instanceof IAnimals) passive++;
            }
            kv(sb, "world.entities", "total=" + entities.size() + " players=" + players + " living=" + living
                    + " droppedItems=" + items + " hostile=" + hostile + " passive=" + passive
                    + " tileEntities=" + mc.theWorld.loadedTileEntityList.size());
            try { kv(sb, "world.loaded_chunks", mc.theWorld.getChunkProvider().getLoadedChunkCount()); } catch (Throwable t) { error(sb, "loaded_chunks", t); }
        } else {
            kv(sb, "world", "<not loaded>");
        }
    }

    private static void appendThreadSummary(StringBuilder sb) {
        try {
            ThreadMXBean tm = ManagementFactory.getThreadMXBean();
            ThreadInfo[] infos = tm.getThreadInfo(tm.getAllThreadIds());
            Map<Thread.State, Integer> states = new LinkedHashMap<>();
            List<String> blocked = new ArrayList<>();
            for (ThreadInfo info : infos) {
                if (info == null) continue;
                Integer n = states.get(info.getThreadState());
                states.put(info.getThreadState(), n == null ? 1 : n + 1);
                if (info.getThreadState() == Thread.State.BLOCKED || info.getThreadState() == Thread.State.WAITING) {
                    blocked.add(info.getThreadName() + "=" + info.getThreadState());
                }
            }
            kv(sb, "threads", "live=" + tm.getThreadCount() + " peak=" + tm.getPeakThreadCount() + " states=" + states);
            if (!blocked.isEmpty()) kv(sb, "blocked_or_waiting_thread_names", blocked);
        } catch (Throwable t) { error(sb, "threads", t); }
    }

    private static void appendGameContext(StringBuilder sb, Minecraft mc) {
        String server = "singleplayer/offline";
        try {
            if (mc != null && mc.getCurrentServerData() != null) server = safe(mc.getCurrentServerData().serverIP);
        } catch (Throwable ignored) { }
        kv(sb, "server", server);
        kv(sb, "connected", mc != null && mc.getNetHandler() != null);
        if (mc == null || mc.theWorld == null) {
            kv(sb, "game_phase", "no_world");
            return;
        }
        kv(sb, "dimension", mc.theWorld.provider.getDimensionId() + " " + safe(mc.theWorld.provider.getDimensionName()));
        kv(sb, "world_time", mc.theWorld.getWorldTime());
        kv(sb, "total_world_time", mc.theWorld.getTotalWorldTime());
        kv(sb, "weather", "raining=" + mc.theWorld.isRaining() + " thundering=" + mc.theWorld.isThundering());
        kv(sb, "on_hypixel", GameContext.isOnHypixel(mc));
        kv(sb, "hypixel_lobby", GameContext.isHypixelLobby(mc));
        kv(sb, "blitz_queue_or_pregame", GameContext.isBlitzPregameLobby(mc));
        kv(sb, "blitz_hub_queue", GameContext.isBlitzHypixelHubQueueSidebar(mc));
        kv(sb, "blitz_cages", GameContext.isBlitzCagePhaseSidebar(mc));
        kv(sb, "blitz_active", GameContext.isBlitzGameActive(mc));
        kv(sb, "blitz_spectator", GameContext.isBlitzSpectator(mc));
        kv(sb, "blitz_deathmatch", GameContext.isBlitzDeathmatchPhase(mc));
        kv(sb, "blitz_waiting_for_kit", GameContext.isBlitzSidebarWaitingForKitsDelivery(mc));
        kv(sb, "blitz_kit_remaining_ms", GameContext.parseBlitzSidebarGetKitRemainingMs(mc));
        kv(sb, "blitz_teams", BlitzTeamsDetector.isBlitzTeamsGame(mc));
        kv(sb, "inferred_phase", inferPhase(mc));
    }

    private static String inferPhase(Minecraft mc) {
        if (mc == null || mc.theWorld == null) return "NO_WORLD";
        if (GameContext.isBlitzDeathmatchPhase(mc)) return "BLITZ_DEATHMATCH";
        if (GameContext.isBlitzSpectator(mc)) return "BLITZ_SPECTATING";
        if (GameContext.isBlitzGameActive(mc)) return GameContext.isBlitzSidebarWaitingForKitsDelivery(mc)
                ? "BLITZ_LIVE_PRE_KIT" : "BLITZ_LIVE";
        if (GameContext.isBlitzCagePhaseSidebar(mc)) return "BLITZ_CAGES";
        if (GameContext.isBlitzHypixelHubQueueSidebar(mc)) return "BLITZ_QUEUE";
        if (GameContext.isBlitzPregameLobby(mc)) return "BLITZ_PREGAME";
        if (GameContext.isHypixelLobby(mc)) return "HYPIXEL_LOBBY";
        if (GameContext.isOnHypixel(mc)) return "HYPIXEL_OTHER";
        return mc.isSingleplayer() ? "SINGLEPLAYER" : "MULTIPLAYER_OTHER";
    }

    private static void appendPlayer(StringBuilder sb, Minecraft mc) {
        if (mc == null || mc.thePlayer == null) {
            kv(sb, "player", "<not loaded>");
            return;
        }
        EntityPlayerSP p = mc.thePlayer;
        kv(sb, "name", p.getName());
        kv(sb, "uuid", String.valueOf(p.getUniqueID()));
        kv(sb, "entity_id", p.getEntityId());
        kv(sb, "position", String.format(Locale.ROOT, "x=%.3f y=%.3f z=%.3f block=%s chunk=%d,%d",
                p.posX, p.posY, p.posZ, new BlockPos(p), p.chunkCoordX, p.chunkCoordZ));
        kv(sb, "rotation", String.format(Locale.ROOT, "yaw=%.2f pitch=%.2f", p.rotationYaw, p.rotationPitch));
        kv(sb, "motion", String.format(Locale.ROOT, "x=%.4f y=%.4f z=%.4f", p.motionX, p.motionY, p.motionZ));
        try { kv(sb, "biome", mc.theWorld.getBiomeGenForCoords(new BlockPos(p)).biomeName); } catch (Throwable t) { error(sb, "biome", t); }
        kv(sb, "health", p.getHealth() + "/" + p.getMaxHealth() + " absorption=" + p.getAbsorptionAmount());
        kv(sb, "food", p.getFoodStats().getFoodLevel() + " saturation=" + p.getFoodStats().getSaturationLevel());
        kv(sb, "armor_value", p.getTotalArmorValue());
        kv(sb, "xp", "level=" + p.experienceLevel + " progress=" + p.experience + " total=" + p.experienceTotal);
        kv(sb, "movement_state", "onGround=" + p.onGround + " sprinting=" + p.isSprinting() + " sneaking=" + p.isSneaking()
                + " burning=" + p.isBurning() + " inWater=" + p.isInWater() + " riding=" + (p.ridingEntity != null));
        kv(sb, "capabilities", "creative=" + p.capabilities.isCreativeMode + " flying=" + p.capabilities.isFlying
                + " allowFlying=" + p.capabilities.allowFlying + " invulnerable=" + p.capabilities.disableDamage);
        kv(sb, "selected_hotbar_slot", p.inventory.currentItem);
        kv(sb, "ticks_existed", p.ticksExisted);
        kv(sb, "using_item", "active=" + p.isUsingItem() + " remainingTicks=" + p.getItemInUseCount()
                + " action=" + (p.getItemInUse() == null ? "<none>" : p.getItemInUse().getItemUseAction()));
        try {
            kv(sb, "crosshair_target", mc.objectMouseOver == null ? "<none>"
                    : "type=" + mc.objectMouseOver.typeOfHit + " block=" + mc.objectMouseOver.getBlockPos()
                    + " entity=" + (mc.objectMouseOver.entityHit == null ? "<none>"
                    : mc.objectMouseOver.entityHit.getClass().getSimpleName() + "#" + mc.objectMouseOver.entityHit.getEntityId()));
        } catch (Throwable t) { error(sb, "crosshair_target", t); }
        try {
            NetworkPlayerInfo info = mc.getNetHandler() == null ? null : mc.getNetHandler().getPlayerInfo(p.getUniqueID());
            kv(sb, "ping_ms", info == null ? "<unknown>" : info.getResponseTime());
            kv(sb, "game_type", info == null || info.getGameType() == null ? "<unknown>" : info.getGameType().getName());
        } catch (Throwable t) { error(sb, "network_player_info", t); }
        List<String> effects = new ArrayList<>();
        for (PotionEffect effect : p.getActivePotionEffects()) {
            effects.add("id=" + effect.getPotionID() + " amplifier=" + effect.getAmplifier() + " durationTicks=" + effect.getDuration()
                    + " ambient=" + effect.getIsAmbient());
        }
        kv(sb, "potion_effects", effects.isEmpty() ? "<none>" : effects);
    }

    private static void appendInventory(StringBuilder sb, Minecraft mc) {
        if (mc == null || mc.thePlayer == null) {
            line(sb, "<player not loaded>");
            return;
        }
        EntityPlayerSP p = mc.thePlayer;
        for (int i = 0; i < p.inventory.mainInventory.length; i++) {
            appendStack(sb, "main[" + i + "]" + (i == p.inventory.currentItem ? " SELECTED" : ""), p.inventory.mainInventory[i]);
        }
        for (int i = 0; i < p.inventory.armorInventory.length; i++) appendStack(sb, "armor[" + i + "]", p.inventory.armorInventory[i]);
        try {
            if (p.openContainer != null) {
                kv(sb, "open_container", p.openContainer.getClass().getName() + " windowId=" + p.openContainer.windowId
                        + " slots=" + p.openContainer.inventorySlots.size());
            }
        } catch (Throwable t) { error(sb, "open_container", t); }
    }

    private static void appendStack(StringBuilder sb, String slot, ItemStack stack) {
        if (stack == null) {
            line(sb, slot + "=<empty>");
            return;
        }
        String id = "<unknown>";
        try {
            Object key = Item.itemRegistry.getNameForObject(stack.getItem());
            if (key != null) id = key.toString();
        } catch (Throwable ignored) { }
        String nbt = stack.hasTagCompound() ? stack.getTagCompound().toString() : "<none>";
        if (nbt.length() > MAX_NBT_CHARS) nbt = nbt.substring(0, MAX_NBT_CHARS) + "...<truncated>";
        line(sb, slot + "=id=" + id + " count=" + stack.stackSize + " damage=" + stack.getItemDamage()
                + " maxDamage=" + stack.getMaxDamage() + " displayName=" + stripFormatting(stack.getDisplayName()) + " nbt=" + nbt);
    }

    private static void appendScoreboard(StringBuilder sb, Minecraft mc) {
        if (mc == null || mc.theWorld == null || mc.theWorld.getScoreboard() == null) {
            line(sb, "<scoreboard not loaded>");
            return;
        }
        try {
            Scoreboard board = mc.theWorld.getScoreboard();
            ScoreObjective obj = board.getObjectiveInDisplaySlot(1);
            if (obj == null) {
                line(sb, "sidebar=<none>");
            } else {
                kv(sb, "sidebar_objective", "name=" + obj.getName() + " display=" + stripFormatting(obj.getDisplayName()));
                Collection<Score> scores = board.getSortedScores(obj);
                for (Score score : scores) {
                    if (score == null || score.getPlayerName() == null || score.getPlayerName().startsWith("#")) continue;
                    ScorePlayerTeam team = board.getPlayersTeam(score.getPlayerName());
                    String formatted = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
                    line(sb, "line score=" + score.getScorePoints() + " raw=" + score.getPlayerName()
                            + " formatted=" + stripFormatting(formatted));
                }
            }
            kv(sb, "scoreboard_teams", board.getTeams().size());
        } catch (Throwable t) { error(sb, "scoreboard", t); }
    }

    private static void appendTabList(StringBuilder sb, Minecraft mc) {
        if (mc == null || mc.getNetHandler() == null) {
            line(sb, "<tab list unavailable>");
            return;
        }
        try {
            Collection<NetworkPlayerInfo> infos = mc.getNetHandler().getPlayerInfoMap();
            kv(sb, "tab_entries", infos.size());
            int minPing = Integer.MAX_VALUE, maxPing = Integer.MIN_VALUE, sum = 0, counted = 0;
            for (NetworkPlayerInfo info : infos) {
                int ping = info.getResponseTime();
                if (ping >= 0) { minPing = Math.min(minPing, ping); maxPing = Math.max(maxPing, ping); sum += ping; counted++; }
            }
            kv(sb, "tab_ping", counted == 0 ? "<unknown>" : "min=" + minPing + " avg=" + (sum / counted) + " max=" + maxPing);
            line(sb, "note=Individual remote player identities are omitted; use /allplayers on internal builds when needed.");
        } catch (Throwable t) { error(sb, "tab_list", t); }
    }

    private static void appendLoadedMods(StringBuilder sb) {
        try {
            Class<?> loaderClass = Class.forName("net.minecraftforge.fml.common.Loader");
            Object loader = loaderClass.getMethod("instance").invoke(null);
            Object raw = loaderClass.getMethod("getActiveModList").invoke(loader);
            if (!(raw instanceof List)) {
                kv(sb, "mods", "<loader returned " + safe(raw) + ">");
                return;
            }
            List<String> mods = new ArrayList<>();
            for (Object mod : (List<?>) raw) {
                try {
                    Class<?> c = mod.getClass();
                    String id = String.valueOf(c.getMethod("getModId").invoke(mod));
                    String name = String.valueOf(c.getMethod("getName").invoke(mod));
                    String version = String.valueOf(c.getMethod("getVersion").invoke(mod));
                    mods.add(id + " | " + name + " | " + version);
                } catch (Throwable t) {
                    mods.add(String.valueOf(mod));
                }
            }
            Collections.sort(mods, String.CASE_INSENSITIVE_ORDER);
            kv(sb, "count", mods.size());
            for (String mod : mods) line(sb, mod);
        } catch (Throwable t) {
            kv(sb, "forge_mod_list", "<unavailable: " + t.getClass().getSimpleName() + ">");
            kv(sb, "runtime_hint", detectLoaderHint());
        }
    }

    private static String detectLoaderHint() {
        List<String> found = new ArrayList<>();
        for (String c : new String[]{"net.weavemc.loader.api.ModInitializer", "net.minecraftforge.fml.common.Loader", "com.moonsworth.lunar.client.LunarClient"}) {
            try { Class.forName(c, false, DebugAllCommand.class.getClassLoader()); found.add(c); } catch (Throwable ignored) { }
        }
        return found.toString();
    }

    private static void appendRuntime(StringBuilder sb) {
        kv(sb, "commands_registered", Main.areCommandsRegistered());
        kv(sb, "pre_init_complete", Main.isPreInitComplete());
        kv(sb, "tick_hub", "masterDisabled=" + ClientTickHub.isAllTickFeaturesDisabled()
                + " aliveWithin2s=" + ClientTickHub.isHubRecentlyAlive(2000L) + " lastRunEpochMs=" + ClientTickHub.getLastRunWallMs()
                + " totalRuns=" + ClientTickHub.getTotalRuns());
        kv(sb, "disabled_tick_features", new ArrayList<>(ClientTickHub.getDisabledFeatures()));
        kv(sb, "tick_profiler", "recording=" + FeatureProfiler.isRecording() + " ticks=" + FeatureProfiler.getTotalTicksRecorded()
                + " elapsedSeconds=" + FeatureProfiler.getElapsedSeconds());
        for (FeatureProfiler.ProfileEntry e : FeatureProfiler.buildReport()) {
            line(sb, String.format(Locale.ROOT, "tick_profile %s avgUs=%.2f lastUs=%.2f totalMs=%.2f samples=%d",
                    e.name, e.avgUs, e.lastUs, e.totalMs, e.samples));
        }
        kv(sb, "render_profiler", "recording=" + RenderProfiler.isRecording() + " frames=" + RenderProfiler.getRenderEntitiesFrames()
                + " elapsedSeconds=" + RenderProfiler.getElapsedSeconds());
        for (RenderProfiler.ProfileEntry e : RenderProfiler.buildReport()) {
            line(sb, String.format(Locale.ROOT, "render_profile %s avgUs=%.2f lastUs=%.2f totalMs=%.2f samples=%d",
                    e.name, e.avgUs, e.lastUs, e.totalMs, e.samples));
        }
        kv(sb, "reflection_log_path", ReflectionLogger.getLogFilePath());
    }

    private static void appendSettings(StringBuilder sb) {
        try {
            JsonObject snapshot = ConfigManager.buildDebugConfigSnapshot();
            JsonElement sanitized = sanitizeJson(snapshot, "config");
            line(sb, PRETTY_GSON.toJson(sanitized));
        } catch (Throwable t) {
            error(sb, "settings_snapshot", t);
        }
    }

    private static JsonElement sanitizeJson(JsonElement value, String path) {
        if (value == null || value instanceof JsonNull || value.isJsonNull()) return JsonNull.INSTANCE;
        String lower = path.toLowerCase(Locale.ROOT);
        if (isSecretPath(lower)) return new JsonPrimitive("<redacted secret>");
        if (isPrivateCollectionPath(lower) && (value.isJsonArray() || value.isJsonObject())) {
            int size = value.isJsonArray() ? value.getAsJsonArray().size() : value.getAsJsonObject().entrySet().size();
            return new JsonPrimitive("<redacted private collection; entries=" + size + ">");
        }
        if (value.isJsonObject()) {
            JsonObject out = new JsonObject();
            List<Map.Entry<String, JsonElement>> entries = new ArrayList<>(value.getAsJsonObject().entrySet());
            entries.sort(Comparator.comparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER));
            for (Map.Entry<String, JsonElement> e : entries) out.add(e.getKey(), sanitizeJson(e.getValue(), path + "." + e.getKey()));
            return out;
        }
        if (value.isJsonArray()) {
            JsonArray out = new JsonArray();
            int i = 0;
            for (JsonElement e : value.getAsJsonArray()) out.add(sanitizeJson(e, path + "[" + (i++) + "]"));
            return out;
        }
        return value;
    }

    private static boolean isSecretPath(String p) {
        return p.contains("apikey") || p.contains("api_key") || p.contains("password") || p.contains("secret")
                || p.contains("accesstoken") || p.contains("access_token") || p.contains("refreshtoken")
                || p.contains("refresh_token") || p.contains("authorization") || p.contains("cookie")
                || p.endsWith(".token") || p.contains("sessiontoken") || p.contains("webhook");
    }

    private static boolean isPrivateCollectionPath(String p) {
        return p.contains("friendsbyaccount") || p.contains("friendsocial") || p.contains("friendrequest")
                || p.contains("incomingdm") || p.contains("outgoingdm") || p.contains("messagehistory")
                || p.contains("pendingmessages") || p.contains("socialids") || p.contains("accountuuid")
                || p.contains("lunartweakshiddencosmeticplayers");
    }

    private static void appendRecentLog(StringBuilder sb) {
        File log = new File(new File(GomodPaths.getMinecraftGameDir(), "logs"), "latest.log");
        kv(sb, "source", log.getAbsolutePath());
        if (!log.isFile()) {
            line(sb, "<latest.log not found>");
            return;
        }
        try {
            List<String> lines = tailLines(log, MAX_LOG_BYTES);
            List<String> relevant = new ArrayList<>();
            for (String raw : lines) {
                String low = raw.toLowerCase(Locale.ROOT);
                if ((low.contains("gomod") || low.contains("mixin") || low.contains("exception") || low.contains(" error")
                        || low.contains(" warn") || low.contains("can't keep up") || low.contains("outofmemory"))) {
                    relevant.add(redactText(raw));
                }
            }
            int from = Math.max(0, relevant.size() - MAX_LOG_LINES);
            kv(sb, "matching_lines", relevant.size() + " (showing last " + (relevant.size() - from) + ")");
            for (int i = from; i < relevant.size(); i++) line(sb, relevant.get(i));
        } catch (Throwable t) { error(sb, "latest_log", t); }
    }

    private static void appendReflectionLog(StringBuilder sb) {
        section(sb, "RECENT REFLECTION FAILURES");
        File log = new File(ReflectionLogger.getLogFilePath());
        kv(sb, "source", log.getAbsolutePath());
        if (!log.isFile() || log.length() == 0L) {
            line(sb, "<none recorded>");
            return;
        }
        try {
            List<String> lines = tailLines(log, 256 * 1024);
            int from = Math.max(0, lines.size() - MAX_LOG_LINES);
            kv(sb, "lines_in_tail", lines.size() + " (showing last " + (lines.size() - from) + ")");
            for (int i = from; i < lines.size(); i++) line(sb, redactText(lines.get(i)));
        } catch (Throwable t) { error(sb, "reflection_log", t); }
    }

    private static List<String> tailLines(File file, int maxBytes) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long start = Math.max(0L, raf.length() - maxBytes);
            raf.seek(start);
            byte[] data = new byte[(int) (raf.length() - start)];
            raf.readFully(data);
            String text = new String(data, StandardCharsets.UTF_8);
            if (start > 0) {
                int firstNl = text.indexOf('\n');
                if (firstNl >= 0) text = text.substring(firstNl + 1);
            }
            String[] split = text.split("\\r?\\n");
            List<String> out = new ArrayList<>(split.length);
            Collections.addAll(out, split);
            return out;
        }
    }

    private static String redactText(String value) {
        if (value == null) return "";
        String out = value.replaceAll("(?i)(api[_ -]?key|access[_ -]?token|refresh[_ -]?token|authorization|password|secret)([=: ]+)[^\\s,;]+", "$1$2<redacted>");
        out = out.replaceAll("(?i)([?&](?:key|token|auth)=)[^&\\s]+", "$1<redacted>");
        return out;
    }

    private static String utcNow() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    private static String humanBytes(long bytes) {
        if (bytes < 0) return "unknown";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.ROOT, "%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static String safe(Object value) { return value == null ? "<null>" : String.valueOf(value).replace('\r', ' ').replace('\n', ' '); }
    private static String stripFormatting(String value) { return value == null ? "" : value.replaceAll("\\u00a7[0-9A-FK-ORa-fk-or]", ""); }
    private static void kv(StringBuilder sb, String key, Object value) { line(sb, key + "=" + safe(value)); }
    private static void error(StringBuilder sb, String key, Throwable t) { kv(sb, key, "<error: " + t.getClass().getSimpleName() + ": " + safe(t.getMessage()) + ">"); }
    private static void section(StringBuilder sb, String title) { sb.append("\r\n=== ").append(title).append(" ===\r\n"); }
    private static void line(StringBuilder sb, String value) {
        sb.append(value == null ? "<null>" : redactWindowsUserHome(value)).append("\r\n");
    }

    /** Replaces the Windows account-name segment in every path emitted by /debugall. */
    private static String redactWindowsUserHome(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        try {
            String home = System.getProperty("user.home");
            if (home == null || home.isEmpty()) {
                return value;
            }
            File homeFile = new File(home).getAbsoluteFile();
            File parent = homeFile.getParentFile();
            String replacement = parent == null
                    ? "%USERNAME%"
                    : new File(parent, "%USERNAME%").getPath();
            return value.replaceAll("(?i)" + java.util.regex.Pattern.quote(homeFile.getPath()),
                    java.util.regex.Matcher.quoteReplacement(replacement));
        } catch (Throwable ignored) {
            return value;
        }
    }
}
