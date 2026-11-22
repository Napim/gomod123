package me.napim.gomod.features;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import me.napim.gomod.shareinv.ShareInvManager;
import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings;
import net.weavemc.loader.api.event.RenderWorldEvent;
import net.weavemc.loader.api.event.SubscribeEvent;

import java.util.*;
import java.util.regex.*;

/** Opt-in, same-match spectator-only observations on the authenticated gomod socket. */
public final class SpectatorRelay {
    private static final SpectatorFeedState FEED = new SpectatorFeedState();
    private static final SpectatorRosterState ROSTER = new SpectatorRosterState();
    private static final Map<UUID, Ghost> GHOSTS = new HashMap<UUID, Ghost>();
    private static final Map<UUID, String> REPORTED_SPECTATORS = new HashMap<UUID, String>();
    private static final Map<UUID, SkinMemo> SOURCE_SKINS = new LinkedHashMap<UUID, SkinMemo>();
    private static final Map<UUID, SourceVisual> SOURCE_VISUALS = new HashMap<UUID, SourceVisual>();
    private static World world;
    private static EntityPlayer local;
    private static int lastTick = Integer.MIN_VALUE;
    private static long nextContextAt, nextSendAt, sequence;
    private static long nextRosterAt, rosterRevision;
    private static String game = "", session = UUID.randomUUID().toString(), role = "off";
    private static boolean aliveViewer;
    private static long sourceSent, viewerSent, sendFailed, received, accepted;
    private static String lastGate = "not_ticked", lastReject = "none";
    private static int lastSourceSamples;

    public static boolean isEnabled() { return ConfigManager.isSeeSpectatorsWhileAliveEnabled(); }
    public static boolean isAliveViewer() { return aliveViewer && isEnabled(); }
    public static void setEnabled(boolean value) {
        ConfigManager.setSeeSpectatorsWhileAliveEnabled(value);
        nextContextAt = nextSendAt = 0L;
        if (value) ShareInvManager.requestSpectatorRelayConnection();
        else reset(true);
    }

    public static void clientTick(Minecraft mc) {
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            lastGate = "no_world";
            reset(true); world = null; local = null; lastTick = Integer.MIN_VALUE; return;
        }
        if (world != mc.theWorld || local != mc.thePlayer) {
            reset(true); world = mc.theWorld; local = mc.thePlayer; lastTick = Integer.MIN_VALUE;
        }
        if (lastTick == mc.thePlayer.ticksExisted) return;
        lastTick = mc.thePlayer.ticksExisted;
        boolean active = isEnabled() && !GomodSocialStore.isAppearOffline() && !VisibleSpecs.isReplayContext()
                && GameContext.isOnHypixel(mc)
                && !GameContext.isBlitzGameOverSidebar(mc) && !GameContext.isBlitzMatchMapPregameSidebar(mc)
                && (GameContext.isBlitzGameActive(mc) || GameContext.isBlitzDeathmatchPhase(mc));
        if (!active) { reset(true); lastGate = !isEnabled() ? "disabled" : GomodSocialStore.isAppearOffline()
                ? "appear_offline" : VisibleSpecs.isReplayContext() ? "replay" : "not_live_blitz"; return; }
        long now = System.currentTimeMillis();
        if (now >= nextContextAt) {
            String nextGame = BlitzMatchIdentity.resolve(mc);
            if (!nextGame.equals(game)) {
                reset(true); game = nextGame;
            }
            nextContextAt = now + 1000L;
        }
        boolean spectator = VisibleSpecs.isLocalEligibleForVisibleSpecsOpacity(mc);
        String nextRole = game.isEmpty() ? "off" : spectator ? "source" : "viewer";
        lastGate = game.isEmpty() ? "missing_game_id" : "ready";
        if (!role.equals(nextRole)) {
            FEED.clear(); ROSTER.clear(); GHOSTS.clear(); REPORTED_SPECTATORS.clear(); SOURCE_SKINS.clear(); SOURCE_VISUALS.clear();
            LunarSpectatorCosmetics.clear(); role = nextRole;
            session = UUID.randomUUID().toString(); sequence = 0; nextSendAt = 0;
            nextRosterAt=0;
        }
        // Locally loaded spectators can still be revealed if the sidebar has no match identifier yet.
        aliveViewer = !spectator;
        if (aliveViewer) updateGhosts(mc, now);
        else { FEED.clear(); ROSTER.clear(); GHOSTS.clear(); REPORTED_SPECTATORS.clear(); LunarSpectatorCosmetics.clear(); }
        refreshRosterComposition(now);
        if (spectator && "source".equals(role)) observeSourceVisuals(mc);
        if ("off".equals(role) || now < nextSendAt) return;
        // 200 source frames/minute stays below the existing independent 240/minute relay budget.
        nextSendAt = now + (spectator ? 300L : 500L);
        JsonObject packet = basePacket(role);
        JsonArray players = new JsonArray();
        if (spectator) {
            if(now>=nextRosterAt){
                nextRosterAt=now+1000L;
                JsonArray roster=sourceRoster(mc);
                if(roster!=null)packet.add("roster",roster);
            }
            addPlayer(players, mc.thePlayer);
            for (EntityPlayer player : mc.theWorld.playerEntities) {
                if (player == mc.thePlayer || player instanceof Ghost || !isConfirmedSpectator(player)
                        || player.getDistanceSqToEntity(mc.thePlayer) > 128D * 128D) continue;
                addPlayer(players, player);
                if (players.size() >= SpectatorFeedState.MAX_PLAYERS) break;
            }
        }
        packet.add("players", players);
        lastSourceSamples = players.size();
        if (ShareInvManager.sendSpectatorState(packet)) { if(spectator) sourceSent++; else viewerSent++; }
        else sendFailed++;
    }

    private static JsonObject basePacket(String outgoingRole) {
        JsonObject packet = new JsonObject();
        packet.addProperty("type", "spectator_state");
        packet.addProperty("game", game);
        packet.addProperty("role", outgoingRole);
        packet.addProperty("session", session);
        packet.addProperty("sequence", ++sequence);
        return packet;
    }

    private static void reset(boolean notify) {
        LunarSpectatorCosmetics.clear();
        if ("off".equals(role) && game.isEmpty() && !aliveViewer && GHOSTS.isEmpty()) return;
        if (notify && !"off".equals(role)) {
            JsonObject off = basePacket("off"); off.add("players", new JsonArray());
            ShareInvManager.sendSpectatorState(off);
        }
        FEED.clear(); ROSTER.clear(); GHOSTS.clear(); REPORTED_SPECTATORS.clear(); SOURCE_SKINS.clear(); SOURCE_VISUALS.clear(); aliveViewer = false;
        refreshRosterComposition(System.currentTimeMillis());nextRosterAt=0;
        role = "off"; game = ""; nextContextAt = nextSendAt = 0;
        sequence = 0; session = UUID.randomUUID().toString();
    }

    public static boolean isConfirmedSpectator(EntityPlayer player) {
        if (player instanceof Ghost) return true;
        if (player != null && isAliveViewer()) {
            String reportedName = REPORTED_SPECTATORS.get(player.getUniqueID());
            if (reportedName != null && reportedName.equalsIgnoreCase(player.getName())) return true;
            if(ROSTER.isSpectator(player.getUniqueID(),player.getName(),System.currentTimeMillis()))return true;
        }
        Minecraft mc = Minecraft.getMinecraft();
        return player != null && confirmed(mc, player.getUniqueID(), player.getName());
    }

    private static boolean confirmed(Minecraft mc, UUID uuid, String name) {
        if (KillCounter.isPlayerDead(name) || KillCounter.isPlayerDeadOrSpectatorFromTab(name)) return true;
        NetworkPlayerInfo info = mc == null || mc.getNetHandler() == null ? null : mc.getNetHandler().getPlayerInfo(uuid);
        return info != null && info.getGameType() == WorldSettings.GameType.SPECTATOR;
    }

    /** Capture the native connection roster, never the augmented/disguised TAB overlay collection. */
    private static JsonArray sourceRoster(Minecraft mc) {
        if(mc.getNetHandler()==null)return null;
        Collection<NetworkPlayerInfo> rows=mc.getNetHandler().getPlayerInfoMap();
        if(rows==null||rows.isEmpty()||rows.size()>SpectatorRosterState.MAX_MEMBERS)return null;
        Map<UUID,SpectatorRosterState.Entry> entries=new TreeMap<UUID,SpectatorRosterState.Entry>();
        for(NetworkPlayerInfo row:rows){
            GameProfile profile=RemoteDisguiseManager.getSourceProfile(row);
            if(profile==null||profile.getId()==null)return null;
            SpectatorRosterState.Entry entry=new SpectatorRosterState.Entry(profile.getId(),profile.getName(),
                    KillCounter.isSpectatorRosterTabRow(row));
            if(!entry.valid()||entries.put(entry.uuid,entry)!=null)return null;
        }
        SpectatorRosterState.Entry self=new SpectatorRosterState.Entry(mc.thePlayer.getUniqueID(),mc.thePlayer.getName(),true);
        if(!self.valid())return null;
        entries.put(self.uuid,self);
        if(entries.size()>SpectatorRosterState.MAX_MEMBERS)return null;
        Set<String> names=new HashSet<String>();JsonArray result=new JsonArray();
        for(SpectatorRosterState.Entry entry:entries.values()){
            if(!names.add(entry.name.toLowerCase(Locale.ROOT)))return null;
            JsonObject value=new JsonObject();value.addProperty("uuid",entry.uuid.toString());
            value.addProperty("name",entry.name);value.addProperty("spectator",entry.spectator);result.add(value);
        }
        return result;
    }

    private static void refreshRosterComposition(long now){
        long revision=ROSTER.revision(now);
        if(revision!=rosterRevision){rosterRevision=revision;KillCounter.markTabCompositionDirty();}
    }
    public static int tabPresence(NetworkPlayerInfo row){
        if(!isAliveViewer())return SpectatorRosterState.UNKNOWN;
        GameProfile profile=RemoteDisguiseManager.getSourceProfile(row);
        if(profile==null||profile.getName()==null)return SpectatorRosterState.UNKNOWN;
        UUID id=profile.getId();
        if(id!=null&&id.version()==3){
            UUID synthetic=UUID.nameUUIDFromBytes(("gomod:dead-tab:"+profile.getName().trim().toLowerCase(Locale.ROOT))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if(synthetic.equals(id))id=null;
        }
        return ROSTER.presence(id,profile.getName(),System.currentTimeMillis());
    }
    public static boolean isTabSpectator(NetworkPlayerInfo row){
        if(!isAliveViewer())return false;
        GameProfile profile=RemoteDisguiseManager.getSourceProfile(row);
        return profile!=null&&ROSTER.isSpectator(profile.getId(),profile.getName(),System.currentTimeMillis());
    }
    public static boolean shouldColorNametag(EntityPlayer player){
        return player!=null&&player!=local&&isAliveViewer()&&isConfirmedSpectator(player);
    }
    public static String colorNametag(EntityPlayer player,String label){
        if(label==null||label.isEmpty()||!shouldColorNametag(player))return label;
        return "\u00a79"+net.minecraft.util.EnumChatFormatting.getTextWithoutFormattingCodes(label)+"\u00a7r";
    }

    private static void addPlayer(JsonArray result, EntityPlayer player) {
        String visibleName = TabDuplicateNickResolver.getVisibleNameForSessionUuid(
                player.getUniqueID(), player.getName());
        SpectatorFeedState.Sample sample = new SpectatorFeedState.Sample(player.getUniqueID(), visibleName,
                player.posX, player.posY, player.posZ, player.rotationYaw % 360F, player.rotationPitch);
        if (!sample.valid()) return;
        JsonObject item = new JsonObject();
        item.addProperty("uuid", sample.uuid.toString()); item.addProperty("name", sample.name);
        item.addProperty("x", sample.x); item.addProperty("y", sample.y); item.addProperty("z", sample.z);
        item.addProperty("yaw", sample.yaw); item.addProperty("pitch", sample.pitch);
        SourceVisual observed = SOURCE_VISUALS.get(player.getUniqueID());
        JsonObject visual = new JsonObject();
        visual.addProperty("sneaking", player.isSneaking());
        visual.addProperty("sneakSequence", observed == null ? 0L : observed.sneakSequence);
        if(observed!=null && observed.poseTick>0L){
            visual.addProperty("poseTick",observed.poseTick);
            visual.addProperty("sneakHistory",observed.sneakHistory);
        }
        visual.addProperty("swing", observed == null ? 0L : observed.swing);
        ItemStack held = player.getHeldItem();
        if (held != null && held.getItem() != null) {
            SpectatorVisualState.Held value = new SpectatorVisualState.Held(Item.getIdFromItem(held.getItem()),
                    held.getItemDamage(), Math.min(64, held.stackSize));
            if (value.valid()) {
                JsonObject encoded = new JsonObject(); encoded.addProperty("id", value.id);
                encoded.addProperty("damage", value.damage); encoded.addProperty("count", value.count);
                visual.add("held", encoded);
            }
        }
        if (!visual.has("held")) visual.add("held", JsonNull.INSTANCE);
        item.add("visual", visual);
        SpectatorSkin skin = sourceSkin(player);
        if (skin != null) {
            JsonObject texture = new JsonObject(); texture.addProperty("hash", skin.hash); texture.addProperty("model", skin.model);
            item.add("skin", texture);
        }
        result.add(item);
    }

    private static void observeSourceVisuals(Minecraft mc) {
        Set<UUID> keep = new HashSet<UUID>();
        observeSourcePlayer(mc.thePlayer, keep);
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer || player instanceof Ghost || !isConfirmedSpectator(player)
                    || player.getDistanceSqToEntity(mc.thePlayer) > 128D*128D) continue;
            observeSourcePlayer(player, keep);
            if (keep.size() >= SpectatorFeedState.MAX_PLAYERS) break;
        }
        SOURCE_VISUALS.keySet().retainAll(keep);
    }
    private static void observeSourcePlayer(EntityPlayer player, Set<UUID> keep) {
        keep.add(player.getUniqueID());
        SourceVisual state=SOURCE_VISUALS.get(player.getUniqueID());
        if(state==null){state=new SourceVisual();SOURCE_VISUALS.put(player.getUniqueID(),state);}
        boolean started=player.isSwingInProgress && (!state.swinging || player.swingProgressInt<state.progress);
        // The actual click hook also covers spectator clicks that vanilla does not animate.
        // Native swing state may lag clickMouse by a couple of ticks. Deduplicate for
        // one complete vanilla swing, or one fast hit can be reported twice.
        if(started && player.ticksExisted-state.clickTick>6) state.increment();
        boolean sneaking=player.isSneaking();
        if(!state.sneakObserved){state.sneaking=sneaking;state.sneakObserved=true;}
        else if(state.sneaking!=sneaking){
            state.sneaking=sneaking;
            state.sneakSequence=state.sneakSequence==Integer.MAX_VALUE?0L:state.sneakSequence+1L;
        }
        state.poseTick=state.poseTick==Integer.MAX_VALUE?1L:state.poseTick+1L;
        state.sneakHistory=((state.sneakHistory<<1)|(sneaking?1:0))&65535;
        state.swinging=player.isSwingInProgress; state.progress=player.swingProgressInt;
    }
    public static void onLeftClick(Minecraft mc) {
        if (!isEnabled() || !"source".equals(role) || mc==null || mc.theWorld!=world || mc.thePlayer!=local
                || mc.currentScreen!=null || !VisibleSpecs.isLocalEligibleForVisibleSpecsOpacity(mc)) return;
        SourceVisual state=SOURCE_VISUALS.get(local.getUniqueID());
        if(state==null){state=new SourceVisual();SOURCE_VISUALS.put(local.getUniqueID(),state);}
        if(state.clickTick!=local.ticksExisted){state.increment();state.clickTick=local.ticksExisted;}
    }
    private static final class SourceVisual {
        long swing,sneakSequence,poseTick; boolean swinging,sneaking,sneakObserved;
        int progress,sneakHistory,clickTick=Integer.MIN_VALUE/2;
        void increment(){swing=swing==Integer.MAX_VALUE?0:swing+1;}
    }

    private static SpectatorSkin sourceSkin(EntityPlayer player) {
        String encoded = textureProperty(player.getGameProfile());
        if (encoded.isEmpty()) {
            Minecraft mc = Minecraft.getMinecraft();
            NetworkPlayerInfo info = mc.getNetHandler() == null ? null : mc.getNetHandler().getPlayerInfo(player.getUniqueID());
            if (info != null) encoded = textureProperty(RemoteDisguiseManager.getSourceProfile(info));
        }
        SkinMemo previous = SOURCE_SKINS.get(player.getUniqueID());
        if (previous != null && previous.encoded.equals(encoded)) return previous.skin;
        SpectatorSkin skin = SpectatorSkin.fromProperty(encoded);
        SOURCE_SKINS.put(player.getUniqueID(), new SkinMemo(encoded, skin));
        while (SOURCE_SKINS.size() > SpectatorFeedState.MAX_PLAYERS) SOURCE_SKINS.remove(SOURCE_SKINS.keySet().iterator().next());
        return skin;
    }
    private static String textureProperty(GameProfile profile) {
        if (profile != null) for (Property property : profile.getProperties().get("textures")) {
            String value = property.getValue(); if (value != null && value.length() <= 16384) return value;
        }
        return "";
    }
    private static final class SkinMemo {
        final String encoded; final SpectatorSkin skin;
        SkinMemo(String encoded,SpectatorSkin skin){this.encoded=encoded;this.skin=skin;}
    }

    /** Called on Minecraft's thread, after authenticated relay decoding. */
    public static void receive(JsonObject packet) {
        received++;
        if (!isAliveViewer()) {lastReject="not_alive_viewer";return;}
        try {
            if (!game.equals(packet.get("game").getAsString())) {lastReject="different_game";return;}
            UUID source = parseUuid(packet.get("playerUuid").getAsString());
            if (source.equals(local.getUniqueID())) return;
            String sourceSession=packet.get("session").getAsString();
            List<SpectatorRosterState.Entry> roster=null;
            if(packet.has("roster")){
                JsonArray rows=packet.getAsJsonArray("roster");
                if(rows==null||rows.size()==0||rows.size()>SpectatorRosterState.MAX_MEMBERS)return;
                roster=new ArrayList<SpectatorRosterState.Entry>();
                Set<UUID> ids=new HashSet<UUID>();Set<String> names=new HashSet<String>();boolean self=false;
                for(JsonElement row:rows){
                    JsonObject value=row.getAsJsonObject();
                    if(!value.get("spectator").isJsonPrimitive()||!value.getAsJsonPrimitive("spectator").isBoolean())return;
                    SpectatorRosterState.Entry entry=new SpectatorRosterState.Entry(UUID.fromString(value.get("uuid").getAsString()),
                            value.get("name").getAsString(),value.get("spectator").getAsBoolean());
                    if(!entry.valid()||!ids.add(entry.uuid)||!names.add(entry.name.toLowerCase(Locale.ROOT)))return;
                    self|=entry.uuid.equals(source)&&entry.spectator;roster.add(entry);
                }
                if(!self)return;
            }
            JsonArray items = packet.getAsJsonArray("players");
            if (items == null || items.size() > SpectatorFeedState.MAX_PLAYERS) return;
            List<SpectatorFeedState.Sample> samples = new ArrayList<SpectatorFeedState.Sample>();
            for (JsonElement item : items) {
                JsonObject value = item.getAsJsonObject();
                JsonObject texture = value.has("skin") ? value.getAsJsonObject("skin") : null;
                SpectatorSkin skin = texture == null ? null : new SpectatorSkin(texture.get("hash").getAsString(), texture.get("model").getAsString());
                SpectatorVisualState visual = decodeVisual(value,source.toString()+":"+sourceSession);
                UUID playerUuid = UUID.fromString(value.get("uuid").getAsString());
                String profileName = value.get("name").getAsString();
                String visibleName = TabDuplicateNickResolver.getVisibleNameForSessionUuid(playerUuid, profileName);
                samples.add(new SpectatorFeedState.Sample(playerUuid,
                        visibleName, value.get("x").getAsDouble(), value.get("y").getAsDouble(),
                        value.get("z").getAsDouble(), value.get("yaw").getAsFloat(), value.get("pitch").getAsFloat(), skin, visual));
            }
            long now=System.currentTimeMillis(),frameSequence=exactInteger(packet.get("sequence"));
            if(FEED.replace(source, sourceSession, frameSequence, samples, now)) {
                ROSTER.keepSession(source,sourceSession);
                if(roster!=null)ROSTER.replace(source,sourceSession,frameSequence,roster,now);
                refreshRosterComposition(now);accepted++;lastReject="none";
            }
            else lastReject="stale_or_invalid_frame";
        } catch (RuntimeException ignored) {lastReject="decode_failed";}
    }

    private static SpectatorVisualState decodeVisual(JsonObject value,String stream) {
        if (!value.has("visual")) return null;
        JsonObject visual=value.getAsJsonObject("visual");
        if (!visual.get("sneaking").isJsonPrimitive() || !visual.getAsJsonPrimitive("sneaking").isBoolean())
            throw new IllegalArgumentException("Invalid spectator pose");
        long swing=exactInteger(visual.get("swing"));
        SpectatorVisualState.Held held=null;
        long sneakSequence=visual.has("sneakSequence")?exactInteger(visual.get("sneakSequence")):0L;
        long poseTick=-1L,history=0L;
        if(visual.has("poseTick") || visual.has("sneakHistory")){
            poseTick=exactInteger(visual.get("poseTick"));history=exactInteger(visual.get("sneakHistory"));
            if(poseTick<1L || poseTick>Integer.MAX_VALUE || history<0L || history>65535L)
                throw new IllegalArgumentException("Invalid spectator tick pose");
        }
        if (visual.has("held") && !visual.get("held").isJsonNull()) {
            JsonObject item=visual.getAsJsonObject("held");
            long id=exactInteger(item.get("id")),damage=exactInteger(item.get("damage")),count=exactInteger(item.get("count"));
            if(id<1 || id>=4096 || damage<0 || damage>32767 || count<1 || count>64)
                throw new IllegalArgumentException("Invalid spectator held item");
            held=new SpectatorVisualState.Held((int)id,(int)damage,(int)count);
        }
        SpectatorVisualState result=new SpectatorVisualState(visual.get("sneaking").getAsBoolean(),swing,held,sneakSequence,stream,poseTick,(int)history);
        if(!result.valid())throw new IllegalArgumentException("Invalid spectator action");
        return result;
    }
    private static long exactInteger(JsonElement value) {
        if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Invalid spectator number");
        try {return value.getAsBigDecimal().longValueExact();}
        catch(ArithmeticException error){throw new IllegalArgumentException("Invalid spectator integer",error);}
    }

    public static void removeSource(JsonObject packet) {
        try { if (game.equals(packet.get("game").getAsString())) {
            UUID source=parseUuid(packet.get("playerUuid").getAsString());FEED.remove(source);ROSTER.remove(source);
            refreshRosterComposition(System.currentTimeMillis());
        } }
        catch (RuntimeException ignored) {}
    }

    private static UUID parseUuid(String value) {
        if (value != null && value.matches("[a-fA-F0-9]{32}")) {
            value = value.substring(0, 8) + "-" + value.substring(8, 12) + "-" + value.substring(12, 16)
                    + "-" + value.substring(16, 20) + "-" + value.substring(20);
        }
        return UUID.fromString(value);
    }

    private static void updateGhosts(Minecraft mc, long now) {
        Set<UUID> keep = new HashSet<UUID>();
        REPORTED_SPECTATORS.clear();
        for (SpectatorFeedState.Sample sample : FEED.snapshot(now)) {
            // The source client already limits its feed to spectators confirmed by its local
            // death/TAB state. The alive receiver often cannot see spectator rows in its own
            // TAB (or kill messages), so checking the same fact again here discards the shared view.
            if (sample.uuid.equals(local.getUniqueID()) || local.getDistanceSq(sample.x, sample.y, sample.z) > 128D * 128D) continue;
            REPORTED_SPECTATORS.put(sample.uuid, sample.name);
            if (mc.theWorld.getPlayerEntityByUUID(sample.uuid) != null) continue;
            keep.add(sample.uuid);
            Ghost ghost = GHOSTS.get(sample.uuid);
            if (ghost == null || !ghost.getName().equals(sample.name)) {
                ghost = new Ghost(mc.theWorld, sample); GHOSTS.put(sample.uuid, ghost);
            }
            ghost.accept(sample, now);
            ghost.updateSkin(sample.skin);
            ghost.ticksExisted++;
            ghost.tickVisuals(now);
        }
        GHOSTS.keySet().retainAll(keep);
        // Native world-spawn subscriptions never see these render-only players.
        LunarSpectatorCosmetics.update(mc, keep, now);
    }

    @SubscribeEvent public void onRenderWorld(RenderWorldEvent event) {
        if (!isAliveViewer() || GHOSTS.isEmpty() || RoleplayMode.isEnabled()
                || PlayersPlayMode.getCurrentRenderTarget() != null) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld != world || mc.thePlayer != local) return;
        long now = System.currentTimeMillis();
        for (Ghost ghost : GHOSTS.values()) {
            try {
                ghost.animate(now);
                if (VisibleSpecs.getRenderOpacity() < 0.99F) VisibleSpecs.scheduleDeferred(ghost, event.getPartialTicks());
                else mc.getRenderManager().renderEntitySimple(ghost, event.getPartialTicks());
            } catch (Throwable ignored) {
                // A native cosmetic renderer may not support an unloaded spectator's render-only proxy.
                // Keep that optional model from interrupting the world's other rendering.
            }
        }
        VisibleSpecs.flushDeferred(mc.getRenderManager());
    }

    /** Render-only proxy: never inserted into WorldClient, TAB, targeting, or collision lists. */
    private static final class Ghost extends EntityOtherPlayerMP {
        final SpectatorMotion motion = new SpectatorMotion();
        final SpectatorMotion.Pose pose = new SpectatorMotion.Pose();
        final SpectatorAnimation walking = new SpectatorAnimation();
        SpectatorVisualState.Held heldDescriptor;
        ItemStack heldItem;
        long lastSwing = -1L;
        String lastSwingStream = "";
        boolean pendingSwing;
        final SpectatorSneakTimeline sneaking = new SpectatorSneakTimeline();
        SpectatorFeedState.Sample lastVisualSample;
        ResourceLocation skin;
        final NetworkPlayerInfo cosmeticPlayerInfo;
        String textureKey = "", model = "default";
        Ghost(World world, SpectatorFeedState.Sample sample) {
            super(world, new GameProfile(sample.uuid, sample.name));
            cosmeticPlayerInfo = new NetworkPlayerInfo(getGameProfile());
            setPositionAndRotation(sample.x, sample.y, sample.z, sample.yaw, sample.pitch);
            lastTickPosX = prevPosX = sample.x; lastTickPosY = prevPosY = sample.y; lastTickPosZ = prevPosZ = sample.z;
            setAlwaysRenderNameTag(true);
            // Client-only players receive no server metadata packet; vanilla initializes these flags to zero.
            byte layers = 0;
            for (EnumPlayerModelParts part : EnumPlayerModelParts.values()) layers |= (byte) part.getPartMask();
            getDataWatcher().updateObject(10, Byte.valueOf(layers));
            // Prefetch is asynchronous and deduplicated; it also covers old clients without texture metadata.
            if (sample.skin == null) DenickerRealSkinCache.ensurePrefetchMojangUuid(sample.uuid, sample.name);
        }
        void animate(long now) {
            motion.sample(now, pose); setPosition(pose.x, pose.y, pose.z);
            lastTickPosX = prevPosX = posX; lastTickPosY = prevPosY = posY; lastTickPosZ = prevPosZ = posZ;
            prevRotationYaw = rotationYaw = prevRenderYawOffset = renderYawOffset = prevRotationYawHead = rotationYawHead = pose.yaw;
            prevRotationPitch = rotationPitch = pose.pitch;
        }
        void accept(SpectatorFeedState.Sample sample,long now){
            motion.accept(sample,now);
            if(lastVisualSample!=sample){sneaking.accept(sample.visual,now);lastVisualSample=sample;}
        }
        void tickVisuals(long now) {
            motion.sample(now,pose);
            prevLimbSwingAmount=limbSwingAmount;
            walking.tick(now,pose.x,pose.y,pose.z);
            limbSwingAmount=walking.amount; limbSwing=walking.phase;
            SpectatorVisualState visual=pose.visual;
            setSneaking(sneaking.sample(now));
            SpectatorVisualState.Held item=visual==null?null:visual.held;
            if(!Objects.equals(item,heldDescriptor)){
                Item type=item==null?null:Item.getItemById(item.id);
                heldItem=type==null?null:new ItemStack(type,item.count,item.damage);
                heldDescriptor=item;
            }
            if(visual!=null){
                if(lastSwing>=0 && lastSwingStream.equals(visual.stream) && lastSwing!=visual.swing)pendingSwing=true;
                else if(lastSwingStream.length()>0 && !lastSwingStream.equals(visual.stream))pendingSwing=false;
                lastSwing=visual.swing; lastSwingStream=visual.stream;
            }else {lastSwing=-1L;lastSwingStream="";pendingSwing=false;}
            // Vanilla does this in onEntityUpdate, which a render-only proxy never runs.
            // Without it getSwingProgress(partialTicks) restarts from zero every frame.
            prevSwingProgress=swingProgress;
            updateArmSwingProgress();
            // Do not restart an in-progress animation when delayed hit updates arrive together.
            if(pendingSwing && !isSwingInProgress){swingItem();pendingSwing=false;}
        }
        @Override public ItemStack getHeldItem() { return heldItem; }
        @Override public ItemStack getCurrentEquippedItem() { return heldItem; }
        @Override public NetworkPlayerInfo getPlayerInfo() {
            NetworkPlayerInfo nativeInfo = super.getPlayerInfo();
            return nativeInfo == null ? cosmeticPlayerInfo : nativeInfo;
        }
        @Override public boolean hasPlayerInfo() { return getPlayerInfo() != null; }
        void updateSkin(SpectatorSkin descriptor) {
            if (descriptor == null || !descriptor.valid()) return;
            String key = descriptor.hash + ":" + descriptor.model;
            if (key.equals(textureKey)) return;
            Map<String,String> metadata = new HashMap<String,String>(); metadata.put("model", descriptor.model);
            skin = Minecraft.getMinecraft().getSkinManager().loadSkin(
                    new MinecraftProfileTexture("https://textures.minecraft.net/texture/" + descriptor.hash, metadata), MinecraftProfileTexture.Type.SKIN);
            model = descriptor.model; textureKey = key;
        }
        @Override public ResourceLocation getLocationSkin() {
            LocalDisguise.RemoteDisguiseProfile target = RemoteDisguiseManager.getDisguisedTargetProfile(getGameProfile());
            if (target != null) return target.skinLocation != null ? target.skinLocation : DefaultPlayerSkin.getDefaultSkin(target.uuid);
            if (skin != null) return skin;
            Minecraft mc = Minecraft.getMinecraft();
            NetworkPlayerInfo info = mc.getNetHandler() == null ? null : mc.getNetHandler().getPlayerInfo(getUniqueID());
            return info != null ? info.getLocationSkin() : DenickerRealSkinCache.resolveSkinForMojangUuid(getUniqueID(), getName());
        }
        @Override public String getSkinType() {
            LocalDisguise.RemoteDisguiseProfile target = RemoteDisguiseManager.getDisguisedTargetProfile(getGameProfile());
            if (target != null) return target.skinType == null ? "default" : target.skinType;
            if (skin != null) return model;
            Minecraft mc = Minecraft.getMinecraft();
            NetworkPlayerInfo info = mc.getNetHandler() == null ? null : mc.getNetHandler().getPlayerInfo(getUniqueID());
            return info != null ? info.getSkinType() : DenickerRealSkinCache.resolveSkinTypeForMojangUuid(getUniqueID(), getName());
        }
        @Override public ResourceLocation getLocationCape() {
            LocalDisguise.RemoteDisguiseProfile target = RemoteDisguiseManager.getDisguisedTargetProfile(getGameProfile());
            return target != null ? target.capeLocation : super.getLocationCape();
        }
        @Override public IChatComponent getDisplayName() {
            String original = "\u00a79" + getName();
            String disguised = RemoteDisguiseManager.getDisguisedDisplayNameFor(this, original);
            return new ChatComponentText(disguised == null ? original : disguised);
        }
        @Override public boolean isSpectator() { return false; }
    }
    public static String debugStatus() {
        return "spectators game="+game+" role="+role+" gate="+lastGate+" sourceSent="+sourceSent+" viewerSent="+viewerSent
                +" sendFailed="+sendFailed+" sourceSamples="+lastSourceSamples+" received="+received+" accepted="+accepted
                +" reported="+REPORTED_SPECTATORS.size()+" proxies="+GHOSTS.size()
                +" rosterMembers="+ROSTER.size(System.currentTimeMillis())+" lastReject="+lastReject;
    }
    /** Called only for an explicit diagnostic snapshot; no per-frame logging. */
    public static String debugPoseStatus(){
        StringBuilder out=new StringBuilder("spectator poses\n");
        for(Map.Entry<UUID,SourceVisual> entry:SOURCE_VISUALS.entrySet()){
            SourceVisual value=entry.getValue();
            out.append("source ").append(entry.getKey()).append(" poseTick=").append(value.poseTick)
                    .append(" sneakHistory=").append(value.sneakHistory).append(" sneakSequence=").append(value.sneakSequence)
                    .append(" swinging=").append(value.swinging).append(" swing=").append(value.swing).append('\n');
        }
        for(Ghost value:GHOSTS.values()){
            SpectatorVisualState visual=value.lastVisualSample==null?null:value.lastVisualSample.visual;
            out.append("proxy ").append(value.getName()).append(" poseTick=").append(visual==null?-1:visual.poseTick)
                    .append(" sneaking=").append(value.isSneaking()).append(" pendingSneaks=").append(value.sneaking.pendingCount())
                    .append(" swingProgress=").append(value.prevSwingProgress).append("->").append(value.swingProgress)
                    .append(" swingActive=").append(value.isSwingInProgress).append(" pendingSwing=").append(value.pendingSwing).append('\n');
        }
        if(world!=null)for(EntityPlayer value:world.playerEntities){
            if(value!=local && REPORTED_SPECTATORS.containsKey(value.getUniqueID()))
                out.append("native ").append(value.getName()).append(" sneaking=").append(value.isSneaking())
                        .append(" swingProgress=").append(value.prevSwingProgress).append("->").append(value.swingProgress).append('\n');
        }
        return out.toString();
    }
}
