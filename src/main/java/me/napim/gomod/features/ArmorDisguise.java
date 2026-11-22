package me.napim.gomod.features;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import me.napim.gomod.Main;
import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.weavemc.loader.api.event.*;
import java.util.*;
import java.util.concurrent.*;

/** Ephemeral client-thread armor appearance; networking never writes inventory or player equipment. */
public final class ArmorDisguise {
    private static final ArmorDisguiseState SHARED = new ArmorDisguiseState();
    private static final Set<String> DEATHS = new LinkedHashSet<>();
    private static final Set<String> DEAD_NAMES = new LinkedHashSet<>();
    private static final Set<String> DEAD_UUIDS = new LinkedHashSet<>();
    private static final Map<String, ItemStack[]> STACKS = new HashMap<>();
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(32), task -> {
                Thread thread = new Thread(task, "gomod-armor-disguise"); thread.setDaemon(true); return thread;
            });
    private static Object handler;
    private static String room = "", selfUuid = "", accountUuid = "";
    private static volatile long generation;
    private static long nextPoll, routeSeenAt, lastTick;
    private static boolean polling, sharing;
    private static ItemStack[] local;
    private static String localName = "";
    private static long serial, lastAppliedSerial;

    public static boolean needsMatchLocation() {
        // Every public client must be able to receive even if it has no spectator/chat opt-in.
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.theWorld != null && !GomodSocialStore.isAppearOffline();
    }

    @SubscribeEvent public void onTick(TickEvent event) { tick(); }

    public static void tick() {
        long now = System.currentTimeMillis();
        if (now - lastTick < 25L) return;
        lastTick = now;
        Minecraft mc = Minecraft.getMinecraft();
        Object currentHandler = mc == null ? null : mc.getNetHandler();
        String uuid = mc == null || mc.thePlayer == null ? "" : mc.thePlayer.getUniqueID().toString();
        String account = GomodSocialIdentity.profileUuidUndashed(mc);
        if (handler != currentHandler || (!uuid.isEmpty() && !selfUuid.equals(uuid)) || !accountUuid.equals(account)) {
            reset(); handler = currentHandler; selfUuid = uuid; accountUuid = account;
        }
        if (mc == null || mc.theWorld == null || mc.thePlayer == null || currentHandler == null) return;
        if (VisibleSpecs.isReplayContext()) { reset(); return; }
        if (GameContext.isOnHypixel(mc) && (GameContext.isBlitzGameOverSidebar(mc) || GameContext.isHypixelLobby(mc))
                && !room.isEmpty()) { reset(); return; }
        if (local != null && (mc.thePlayer.getHealth() <= 0 || mc.thePlayer.deathTime > 0)) local = null;
        String next = resolveRoom(mc);
        if (next.isEmpty()) {
            // Preserve the exact match briefly while the deathmatch world/scoreboard is rebuilding.
            if (!room.isEmpty() && now - routeSeenAt > 10000L) reset();
            return;
        }
        if (!next.equals(room)) { reset(); room = next; }
        routeSeenAt = now;
        for (Object value : mc.theWorld.playerEntities) {
            if (!(value instanceof EntityPlayer)) continue;
            EntityPlayer player = (EntityPlayer) value;
            if (player.getHealth() <= 0 || player.deathTime > 0)
                died(player.getUniqueID().toString(), null);
        }
        if (GomodSocialStore.isAppearOffline()) {
            if (sharing) {
                final JsonObject leave = request("armor_leave", room);
                generation++; sharing = false; polling = false;
                submit(() -> { try { DisguiserRelay.request(leave); } catch (Exception ignored) {} });
            }
            SHARED.clear(); STACKS.clear(); DEATHS.clear(); return;
        }
        if (GomodSocialPresence.SERVER_SINGLEPLAYER.equals(room)) return;
        if (now >= nextPoll && !polling) poll();
    }

    private static String resolveRoom(Minecraft mc) {
        if (GameContext.isOnHypixel(mc)) {
            if (GameContext.isBlitzGameOverSidebar(mc) || GameContext.isHypixelLobby(mc)) return "";
            return BlitzMatchIdentity.resolve(mc);
        }
        String value = GomodSocialPresence.resolveLocalServer(mc);
        return value == null || value.equalsIgnoreCase(GomodSocialPresence.SERVER_MAIN_MENU)
                ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static void reset() {
        final String previous = room;
        final JsonObject leave = request("armor_leave", previous);
        generation++; room = ""; local = null; localName = "";
        SHARED.clear(); STACKS.clear(); DEATHS.clear(); DEAD_NAMES.clear(); DEAD_UUIDS.clear(); polling = false; sharing = false; nextPoll = 0L;
        if (!previous.isEmpty() && !previous.equals(GomodSocialPresence.SERVER_SINGLEPLAYER)) {
            submit(() -> { try { DisguiserRelay.request(leave); } catch (Exception ignored) {} });
        }
    }

    private static JsonObject request(String type, String server) {
        JsonObject json = new JsonObject(); json.addProperty("type", type); json.addProperty("server", server);
        json.addProperty("expectedOwnerUuid", accountUuid); return json;
    }

    private static boolean submit(Runnable task) {
        try { IO.execute(task); return true; } catch (RejectedExecutionException full) { return false; }
    }

    private static void poll() {
        final long epoch = generation, requestSerial = ++serial;
        final String requestedRoom = room;
        final JsonObject body = request("armor_poll", requestedRoom);
        JsonArray deaths = new JsonArray();
        for (String id : DEATHS) deaths.add(new JsonPrimitive(id));
        body.add("deaths", deaths);
        polling = true; sharing = true; nextPoll = System.currentTimeMillis() + 1500L;
        if (!submit(() -> {
            JsonObject response = null;
            try { if (epoch == generation) response = DisguiserRelay.request(body); } catch (Exception ignored) {}
            final JsonObject result = response;
            Minecraft.getMinecraft().addScheduledTask(() -> {
                if (epoch != generation || !room.equals(requestedRoom)) return;
                polling = false;
                if (result == null) { nextPoll = System.currentTimeMillis() + 3000L; return; }
                applySnapshot(result, requestSerial);
            });
        })) polling = false;
    }

    private static void applySnapshot(JsonObject response, long requestSerial) {
        try {
            if (GomodSocialStore.isAppearOffline() || requestSerial <= lastAppliedSerial) return;
            if (!room.equals(response.get("server").getAsString())) return;
            JsonArray rows = response.getAsJsonArray("armorDisguises");
            if (rows == null || rows.size() > 128) return;
            List<ArmorDisguiseState.Entry> parsed = new ArrayList<>();
            Map<String, ItemStack[]> stacks = new HashMap<>();
            Set<String> uuids = new HashSet<>();
            for (JsonElement value : rows) {
                JsonObject row = value.getAsJsonObject();
                String id = UUID.fromString(row.get("id").getAsString()).toString();
                String uuid = UUID.fromString(row.get("sourceUuid").getAsString()).toString();
                String name = row.get("sourceName").getAsString();
                if (!name.matches("[A-Za-z0-9_]{1,16}") || !uuids.add(uuid)) return;
                JsonArray tokens = row.getAsJsonArray("slots");
                if (tokens.size() != 4) return;
                String[] strings = new String[4]; for (int i = 0; i < 4; i++) strings[i] = tokens.get(i).getAsString();
                ArmorDisguiseSpec spec = ArmorDisguiseSpec.parse(strings, 0);
                parsed.add(new ArmorDisguiseState.Entry(id, uuid, name, spec));
                stacks.put(id, STACKS.containsKey(id) ? STACKS.get(id) : buildStacks(spec));
            }
            SHARED.replace(requestSerial, parsed, System.currentTimeMillis());
            lastAppliedSerial = requestSerial;
            STACKS.clear(); STACKS.putAll(stacks);
            // All pending revision-specific death reports in this successful request are now acknowledged.
            Set<String> live = new HashSet<>(); for (ArmorDisguiseState.Entry entry : parsed) live.add(entry.id);
            DEATHS.retainAll(live);
            // A death broadcast can precede this client's first snapshot or the set acknowledgement.
            for (ArmorDisguiseState.Entry entry : parsed)
                if (DEAD_UUIDS.contains(entry.uuid) || DEAD_NAMES.contains(entry.name.toLowerCase(Locale.ROOT))
                        || KillCounter.isPlayerDead(entry.name)) died(entry.uuid, null);
        } catch (RuntimeException malformed) { /* Leave last good snapshot to expire. */ }
    }

    public static ItemStack renderArmor(EntityLivingBase entity, int armorIndex, ItemStack original) {
        if (!(entity instanceof EntityPlayer) || armorIndex < 0 || armorIndex > 3) return original;
        if (local == null && STACKS.isEmpty()) return original;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.getNetHandler() != handler || entity.getHealth() <= 0
                || entity.deathTime > 0) return original;
        int index = 3 - armorIndex; // native boots->helmet to command helmet->boots
        if (entity == mc.thePlayer || selfUuid.equals(entity.getUniqueID().toString())) return select(local, index, original);
        if (GomodSocialStore.isAppearOffline()) return original;
        ArmorDisguiseState.Entry entry = SHARED.find(entity.getUniqueID().toString(), System.currentTimeMillis());
        return entry == null ? original : select(STACKS.get(entry.id), index, original);
    }

    // Null array means no disguise; a sentinel stack means keep, and a null slot means naked.
    private static final ItemStack KEEP = new ItemStack(Items.stick);
    private static ItemStack select(ItemStack[] stacks, int index, ItemStack original) {
        return stacks == null || stacks[index] == KEEP ? original : stacks[index];
    }
    private static ItemStack[] buildStacks(ArmorDisguiseSpec spec) {
        Item[][] materials = {
                {Items.diamond_helmet, Items.diamond_chestplate, Items.diamond_leggings, Items.diamond_boots},
                {Items.iron_helmet, Items.iron_chestplate, Items.iron_leggings, Items.iron_boots},
                {Items.chainmail_helmet, Items.chainmail_chestplate, Items.chainmail_leggings, Items.chainmail_boots},
                {Items.golden_helmet, Items.golden_chestplate, Items.golden_leggings, Items.golden_boots},
                {Items.leather_helmet, Items.leather_chestplate, Items.leather_leggings, Items.leather_boots}};
        List<String> names = Arrays.asList("diamond", "iron", "chain", "gold", "leather");
        ItemStack[] result = new ItemStack[4];
        for (int i = 0; i < 4; i++) {
            String token = spec.slot(i);
            if (token.equals("keep")) { result[i] = KEEP; continue; }
            if (token.equals("none")) continue;
            boolean glint = token.endsWith("+");
            result[i] = new ItemStack(materials[names.indexOf(token.replace("+", ""))][i]);
            if (glint) { NBTTagCompound tag = new NBTTagCompound(); tag.setTag("ench", new NBTTagList()); result[i].setTagCompound(tag); }
        }
        return result;
    }

    public static void setLocal(ArmorDisguiseSpec spec) {
        tick(); Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) throw new IllegalArgumentException("Join a world first.");
        if (mc.thePlayer.getHealth() <= 0 || mc.thePlayer.deathTime > 0) throw new IllegalArgumentException("You must be alive.");
        local = spec == null ? null : buildStacks(spec);
        localName = mc.thePlayer.getGameProfile().getName();
    }

    public static void setShared(String name, ArmorDisguiseSpec spec) throws Exception {
        tick(); Minecraft mc = Minecraft.getMinecraft();
        if (room.isEmpty() || room.equals(GomodSocialPresence.SERVER_SINGLEPLAYER))
            throw new IllegalArgumentException("Join a match first; if its identity is loading, try again in a moment.");
        if (GomodSocialStore.isAppearOffline()) throw new IllegalArgumentException("Disable Appear Offline to share armor.");
        final String requestedRoom = room;
        final long epoch = generation;
        final JsonObject body = request(spec == null ? "armor_clear" : "armor_set", requestedRoom);
        if (name != null) {
            if (!name.matches("[A-Za-z0-9_]{1,16}")) throw new IllegalArgumentException("Enter a valid player name.");
            GameProfile profile = DisguiserRelay.sourceProfile(name);
            boolean known = mc.thePlayer.getUniqueID().equals(profile.getId());
            for (net.minecraft.client.network.NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap())
                if (profile.getId().equals(info.getGameProfile().getId())) known = true;
            for (Object value : mc.theWorld.playerEntities) if (value instanceof EntityPlayer) {
                EntityPlayer player = (EntityPlayer) value;
                if (!profile.getId().equals(player.getUniqueID())) continue;
                known = true;
                if (spec != null && (player.getHealth() <= 0 || player.deathTime > 0))
                    throw new IllegalArgumentException("That player is already dead.");
            }
            if (spec != null && (!known || DEAD_UUIDS.contains(profile.getId().toString())
                    || DEAD_NAMES.contains(name.toLowerCase(Locale.ROOT)) || KillCounter.isPlayerDead(name)))
                throw new IllegalArgumentException("Choose a living player from the current match TAB list.");
            body.addProperty("sourceName", profile.getName()); body.addProperty("sourceUuid", profile.getId().toString());
        }
        if (spec != null) { JsonArray slots = new JsonArray(); for (String token : spec.slots()) slots.add(new JsonPrimitive(token)); body.add("slots", slots); }
        if (!submit(() -> {
            if (epoch != generation) return;
            try {
                JsonObject result = DisguiserRelay.request(body);
                Minecraft.getMinecraft().addScheduledTask(() -> {
                    if (epoch != generation || !room.equals(requestedRoom)) return;
                    applySnapshot(result, ++serial); nextPoll = 0;
                });
            } catch (Exception failure) {
                Minecraft.getMinecraft().addScheduledTask(() -> {
                    if (epoch == generation) Main.sendMessage(Main.CHAT_PREFIX + "Armor disguise failed: " + failure.getMessage());
                });
            }
        })) throw new IllegalArgumentException("Armor relay is busy; try again shortly.");
    }

    public static void onConfirmedDeath(String rawName) {
        if (rawName == null || rawName.isEmpty()) return;
        final long epoch = generation;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) {
            final Object world = mc.theWorld, connection = mc.getNetHandler();
            mc.addScheduledTask(() -> {
                if (epoch == generation && mc.theWorld == world && mc.getNetHandler() == connection) died(null, rawName);
            });
        }
    }
    private static void died(String uuid, String name) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getNetHandler() != handler || VisibleSpecs.isReplayContext()) return;
        if (room.startsWith("hypixel:blitz:")) {
            if (uuid != null && DEAD_UUIDS.size() < 128) DEAD_UUIDS.add(uuid);
            if (name != null && DEAD_NAMES.size() < 128) DEAD_NAMES.add(name.toLowerCase(Locale.ROOT));
            for (ArmorDisguiseState.Entry entry : SHARED.entries())
                if (entry.uuid.equals(uuid) && DEAD_NAMES.size() < 128) DEAD_NAMES.add(entry.name.toLowerCase(Locale.ROOT));
        }
        if (selfUuid.equals(uuid) || (!localName.isEmpty() && localName.equalsIgnoreCase(name))) local = null;
        List<String> ids = SHARED.died(uuid, name);
        if (!ids.isEmpty()) { DEATHS.addAll(ids); for (String id : ids) STACKS.remove(id); nextPoll = 0; }
    }

    @SubscribeEvent public void onPacket(PacketEvent.Receive event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) return;
        final Object world = mc.theWorld, connection = mc.getNetHandler();
        final long epoch = generation;
        final net.minecraft.network.Packet<?> packet = event.getPacket();
        if (packet instanceof S02PacketChat) {
            S02PacketChat chat = (S02PacketChat) packet;
            if ((chat.getType() != 0 && chat.getType() != 1) || chat.getChatComponent() == null) return;
            final String victim = ArmorDisguiseDeathSignal.victim(chat.getChatComponent().getFormattedText());
            if (victim != null) mc.addScheduledTask(() -> {
                if (epoch == generation && mc.theWorld == world && mc.getNetHandler() == connection
                        && mc.thePlayer != null && room.startsWith("hypixel:blitz:") && GameContext.isOnHypixel(mc))
                    died(null, victim.equalsIgnoreCase("You") ? mc.thePlayer.getGameProfile().getName() : victim);
            });
            return;
        }
        if (!(packet instanceof S19PacketEntityStatus) && !(packet instanceof S06PacketUpdateHealth)) return;
        mc.addScheduledTask(() -> {
            if (epoch != generation || mc.theWorld != world || mc.getNetHandler() != connection) return;
            if (packet instanceof S19PacketEntityStatus) {
                S19PacketEntityStatus status = (S19PacketEntityStatus) packet;
                if (status.getOpCode() != 3) return;
                Entity entity = status.getEntity(mc.theWorld);
                if (entity instanceof EntityPlayer) died(entity.getUniqueID().toString(), null);
            } else if (((S06PacketUpdateHealth) packet).getHealth() <= 0) died(selfUuid, null);
        });
    }
}
