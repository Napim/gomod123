package me.napim.gomod.features;

import com.google.gson.*;
import me.napim.gomod.Main;
import me.napim.gomod.util.MinecraftAccountUuid;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Fit-owned cosmetic filters. Drafts stay local; only saved values enter authenticated presence. */
public final class GomodCosmeticAppearance {
    public static final int LIMIT = 256;
    public static final Values DEFAULT = new Values(0, 1, 1);
    private static UUID account;
    private static Map<Long, Values> saved = Collections.emptyMap();
    private static volatile Preview preview;
    private static volatile long previewRevision;
    private static long lastOpenGeneration;
    private static final Map<Long,String> CATEGORIES=new ConcurrentHashMap<>();
    public static void rememberCategory(long id,String category){if(id>0&&category!=null&&!category.equals("unknown")&&(CATEGORIES.size()<8192||CATEGORIES.containsKey(id)))CATEGORIES.putIfAbsent(id,category);}
    public static String sizeSupport(long id){String category=CATEGORIES.get(id);JsonObject row=new JsonObject();row.addProperty("known",category!=null);row.addProperty("sizeAllowed",category!=null&&!"CLOAK".equalsIgnoreCase(category));return row.toString();}
    private static final Map<UUID, Peer> peers = new ConcurrentHashMap<>();
    private static volatile String peerServer = "";
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private GomodCosmeticAppearance() { }

    public static final class Values {
        public final double hue, saturation, brightness, scale;
        public Values(double hue, double saturation, double brightness) {
            this(hue,saturation,brightness,1);
        }
        public Values(double hue, double saturation, double brightness,double scale) {
            if (!Double.isFinite(hue) || hue < -180 || hue > 180
                    || !Double.isFinite(saturation) || saturation < 0 || saturation > 2
                    || !Double.isFinite(brightness) || brightness < 0 || brightness > 2)
                throw new IllegalArgumentException("Appearance values are outside their ranges.");
            if(!Double.isFinite(scale)||scale<.10||scale>1.50)throw new IllegalArgumentException("Size must be between 0.10x and 1.50x.");
            this.hue = hue; this.saturation = saturation; this.brightness = brightness;this.scale=scale;
        }
        public boolean colorsDefault() {return hue==0&&saturation==1&&brightness==1;}
        public boolean isDefault() { return colorsDefault()&&scale==1; }
        JsonObject json(long id) {
            JsonObject row = new JsonObject(); row.addProperty("id", id);
            row.addProperty("hue", hue); row.addProperty("saturation", saturation);
            row.addProperty("brightness", brightness);if(scale!=1)row.addProperty("scale",scale);return row;
        }
    }

    private static UUID localAccount() {
        return MinecraftAccountUuid.sessionProfileId(Minecraft.getMinecraft());
    }

    private static Path file(UUID owner) {
        Minecraft mc = Minecraft.getMinecraft();
        if (owner == null || mc == null || mc.mcDataDir == null)
            throw new IllegalStateException("Minecraft account is unavailable.");
        return mc.mcDataDir.toPath().resolve("gomod123/lunar-cosmetic-appearance/" + owner + ".json");
    }

    private static synchronized void loadAccount() {
        UUID owner = localAccount();
        if (Objects.equals(owner, account)) return;
        account = owner; saved = Collections.emptyMap(); preview = null;
        if (owner == null) return;
        try {
            Path path = file(owner);
            if (!Files.isRegularFile(path) || Files.size(path) > 65536) return;
            JsonObject root = new JsonParser().parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).getAsJsonObject();
            saved = parseRows(root.getAsJsonArray("cosmetics"));
        } catch (Exception error) {
            LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.load", owner.toString(),
                    "result=failed exception=" + error.getClass().getSimpleName());
        }
    }

    private static long id(JsonObject row) {
        double raw = row.get("id").getAsDouble(); long id = (long)raw;
        if (id < 1 || id > Integer.MAX_VALUE || raw != id) throw new IllegalArgumentException("Invalid cosmetic ID.");
        return id;
    }
    private static Values values(JsonObject row) {
        return new Values(row.get("hue").getAsDouble(), row.get("saturation").getAsDouble(), row.get("brightness").getAsDouble(),row.has("scale")?EmoteSpeedProfile.number(row,"scale",.1,1.5):1);
    }
    private static long sequence(JsonObject row) {
        double raw = row.get("sequence").getAsDouble(); long sequence = (long)raw;
        if (sequence < 1 || sequence > 1000000000L || raw != sequence) throw new IllegalArgumentException("Invalid draft sequence.");
        return sequence;
    }
    public static Map<Long, Values> parseRows(JsonArray rows) {
        if (rows == null || rows.size() > LIMIT) throw new IllegalArgumentException("Invalid appearance list.");
        Map<Long, Values> result = new TreeMap<>(); Set<Long> seen = new HashSet<>();
        for (JsonElement element : rows) {
            JsonObject row = element.getAsJsonObject(); long id = id(row); Values value = values(row);
            if (!seen.add(id)) throw new IllegalArgumentException("Duplicate cosmetic ID.");
            if (!value.isDefault()) result.put(id, value);
        }
        return Collections.unmodifiableMap(result);
    }
    private static JsonArray rows(Map<Long, Values> source) {
        JsonArray result = new JsonArray();
        for (Map.Entry<Long, Values> entry : source.entrySet()) result.add(entry.getValue().json(entry.getKey()));
        return result;
    }
    public static synchronized Map<Long, Values> legacySavedValues() { loadAccount(); return saved; }
    private static Map<Long, Values> savedValues() {
        Map<Long, Values> fit = LunarFitAppearance.cosmetics(); return fit == null ? saved : fit;
    }

    /** Each mounted Settings panel owns its draft, preventing delayed callbacks from editing another panel/account. */
    public static synchronized String open(String encodedRequest) {
        GomodStudioSettings.requireConnected();loadAccount();
        if (account == null) throw new IllegalStateException("Minecraft account is unavailable.");
        JsonObject request = payload(encodedRequest); long id = id(request);
        // Settings can open before a wing/other item has rendered. Its catalog type is already in the Locker row.
        if(request.has("category")&&request.get("category").isJsonPrimitive()){
            String category=request.get("category").getAsString().trim().toUpperCase(Locale.ROOT);
            if("CLOAKS".equals(category))category="CLOAK";
            if(category.matches("[A-Z_]{1,32}"))CATEGORIES.putIfAbsent(id,category);
        }
        double raw = request.get("generation").getAsDouble(); long generation = (long)raw;
        if (generation < 1 || generation > 9007199254740991L || raw != generation || generation <= lastOpenGeneration)
            throw new IllegalStateException("This Settings request has been replaced.");
        lastOpenGeneration = generation;
        long scope = LunarFitAppearance.scope(); UUID fitId=requestFit(request);
        if(fitId!=null&&!LunarFitAppearance.hasFit(fitId))throw new IllegalStateException("This Fit is unavailable. Reopen Settings.");
        Map<Long,Values> fitValues=fitId==null?saved:LunarFitAppearance.cosmetics(fitId);
        if(fitId!=null&&fitValues==null)throw new IllegalStateException("This Fit is unavailable. Reopen Settings.");
        Values value=(fitValues==null?saved:fitValues).getOrDefault(id,DEFAULT);boolean recoveredFromOtherFit=false;
        if(fitId!=null&&!fitValues.containsKey(id)) {
            Values recovered=LunarFitAppearance.uniqueCosmeticStyle(id,fitId);
            if(recovered!=null){value=recovered;recoveredFromOtherFit=true;}
        }
        if (scope != LunarFitAppearance.scope()) throw new IllegalStateException("You switched Fits. Reopen Settings.");
        preview = new Preview(account, fitId, id, UUID.randomUUID().toString(), value, 0, scope, ++previewRevision);
        LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.settings_open",generation+":"+fitId+":"+id,
                "id="+id+" fitId="+fitId+" fitIdProvided="+request.has("fitId")
                        +" fitMember="+(fitId!=null&&LunarFitAppearance.hasFit(fitId))+" customized="+!value.isDefault()
                        +" recoveredFromOtherFit="+recoveredFromOtherFit+" hue="+value.hue
                        +" saturation="+value.saturation+" brightness="+value.brightness+" scale="+value.scale);
        JsonObject result = value.json(id); result.addProperty("session", preview.token);
        result.addProperty("sizeAllowed",CATEGORIES.containsKey(id)&&!"CLOAK".equalsIgnoreCase(CATEGORIES.get(id)));
        result.addProperty("recoveredFromOtherFit",recoveredFromOtherFit);
        result.addProperty("fitScoped", fitId!=null);result.addProperty("fitId",fitId==null?null:fitId.toString()); return result.toString();
    }
    private static JsonObject payload(String encoded) {
        if (encoded == null || encoded.length() > 1024) throw new IllegalArgumentException("Invalid appearance payload.");
        return new JsonParser().parse(encoded).getAsJsonObject();
    }
    /** Old cached Locker panels omit fitId and continue to target Lunar's active Fit. */
    private static UUID requestFit(JsonObject row) {
        if(row.has("fitId")&&!row.get("fitId").isJsonNull())return UUID.fromString(row.get("fitId").getAsString());
        return LunarFitAppearance.activeFitId();
    }
    private static Preview ownedDraft(JsonObject row) {
        loadAccount();
        Preview draft = preview;
        if (draft == null || !draft.owner.equals(account) || !draft.token.equals(row.get("session").getAsString())
                || draft.id != id(row) || !Objects.equals(draft.fitId,requestFit(row))
                || draft.scope != LunarFitAppearance.scope()) throw new IllegalStateException("This Settings preview has closed. Reopen it.");
        return draft;
    }
    private static void validateSize(long id,Values value){if(value.scale!=1&&"CLOAK".equalsIgnoreCase(CATEGORIES.get(id)))throw new IllegalArgumentException("Cloaks do not support custom sizes.");}
    public static synchronized boolean preview(String encoded) {
        try { GomodStudioSettings.requireConnected();
            JsonObject row = payload(encoded); Preview draft = ownedDraft(row);validateSize(draft.id,values(row));
            long sequence = sequence(row);
            if (sequence <= draft.sequence) return false;
            Values value=values(row);preview = new Preview(draft.owner, draft.fitId, draft.id, draft.token, value, sequence, draft.scope, ++previewRevision);
            LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.preview_received",draft.token+":"+sequence,
                    "id="+draft.id+" fitId="+draft.fitId+" sequence="+sequence+" hue="+value.hue
                            +" saturation="+value.saturation+" brightness="+value.brightness+" scale="+value.scale);
            return true;
        } catch (Exception rejected) { return false; }
    }
    public static synchronized String apply(String encoded) {
        try { GomodStudioSettings.requireConnected();
            JsonObject row = payload(encoded); Preview draft = ownedDraft(row); Values value = values(row);validateSize(draft.id,value);
            long sequence = sequence(row);
            if (sequence < draft.sequence) throw new IllegalStateException("Save your latest changes.");
            boolean fitScoped=draft.fitId!=null;
            if(fitScoped){
                if(!LunarFitAppearance.save(draft.scope,draft.fitId,draft.id,value,false))throw new IllegalStateException("Fit is unavailable.");
            }else{
                Map<Long, Values> next = new TreeMap<>(saved);
                if (value.isDefault()) next.remove(draft.id); else next.put(draft.id, value);
                if (next.size() > LIMIT) throw new IllegalArgumentException("Too many customized cosmetics.");
                Path path = file(account); Files.createDirectories(path.getParent());
                JsonObject root = new JsonObject(); root.addProperty("version", 1); root.add("cosmetics", rows(next));
                Path temp = Files.createTempFile(path.getParent(), "appearance-", ".tmp");
                try {
                    Files.write(temp, JSON.toJson(root).getBytes(StandardCharsets.UTF_8));
                    try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                    catch (AtomicMoveNotSupportedException unsupported) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
                } finally { Files.deleteIfExists(temp); }
                saved = Collections.unmodifiableMap(next);
            }
            if (sequence >= draft.sequence) preview = new Preview(draft.owner, draft.fitId, draft.id, draft.token, value, sequence, draft.scope, ++previewRevision);
            LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.settings_saved",draft.token+":"+sequence,
                    "id="+draft.id+" fitId="+draft.fitId+" fitScoped="+fitScoped+" sequence="+sequence
                            +" hue="+value.hue+" saturation="+value.saturation+" brightness="+value.brightness+" scale="+value.scale);
            if (Main.getGlobalChatClient() != null) Main.getGlobalChatClient().requestImmediatePoll();
            return fitScoped ? "Saved. These settings belong to this Fit."
                    : "Saved. Other gomod players will see these settings when cosmetic sharing is enabled.";
        } catch (Exception error) {
            LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.settings_save_failed",Integer.toHexString(System.identityHashCode(encoded)),
                    "exception="+error.getClass().getSimpleName());return "Could not save these colors. Reopen Settings and try again.";
        }
    }
    /** IDs with non-default, saved appearance values for the local Locker edit badge. */
    public static synchronized Set<Long> editedCosmeticIds() {
        loadAccount();
        return Collections.unmodifiableSet(new TreeSet<Long>(savedValues().keySet()));
    }

    /** Remove one saved appearance override from the active Fit or account defaults. */
    public static synchronized boolean resetEdited(long id) throws Exception {
        GomodStudioSettings.requireConnected();
        loadAccount();
        if (id < 1 || id > Integer.MAX_VALUE || account == null) throw new IllegalArgumentException("Invalid cosmetic ID.");
        long scope = LunarFitAppearance.scope();
        boolean fitScoped = LunarFitAppearance.save(scope, id, DEFAULT, false);
        if (!fitScoped) {
            Map<Long, Values> next = new TreeMap<>(saved);
            if (next.remove(id) == null) return false;
            Path path = file(account);
            Files.createDirectories(path.getParent());
            JsonObject root = new JsonObject(); root.addProperty("version", 1); root.add("cosmetics", rows(next));
            Path temp = Files.createTempFile(path.getParent(), "appearance-", ".tmp");
            try {
                Files.write(temp, JSON.toJson(root).getBytes(StandardCharsets.UTF_8));
                try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException unsupported) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temp); }
            saved = Collections.unmodifiableMap(next);
        }
        if (preview != null && preview.id == id) {preview = null; ++previewRevision;}
        if (Main.getGlobalChatClient() != null) Main.getGlobalChatClient().requestImmediatePoll();
        return true;
    }

    /** Export only a saved, non-default cosmetic style for an explicit friend gift. */
    public static synchronized JsonObject exportSharedValue(long id) {
        GomodStudioSettings.requireConnected();
        loadAccount();
        if (id < 1 || id > Integer.MAX_VALUE || account == null
                || !account.equals(LunarFitSharing.account())) throw new IllegalStateException("Account changed.");
        Map<Long,Values> selected=preview!=null&&preview.id==id&&preview.fitId!=null
                ?LunarFitAppearance.cosmetics(preview.fitId):savedValues();
        Values value = selected==null?null:selected.get(id);
        if (value == null || value.isDefault()) throw new IllegalArgumentException("Save custom appearance settings first.");
        validateSize(id, value);
        return value.json(id);
    }

    /** Accept an explicit friend gift into the currently selected Fit. */
    public static synchronized void importSharedValue(long expectedId, JsonObject row) throws Exception {
        GomodStudioSettings.requireConnected();
        loadAccount();
        if (account == null || !account.equals(LunarFitSharing.account())) throw new IllegalStateException("Account changed.");
        JsonObject normalized = validateSharedValue(expectedId, row);
        JsonArray rows = new JsonArray(); rows.add(normalized);
        Values value = parseRows(rows).get(expectedId);
        if (value == null) throw new IllegalArgumentException("The shared appearance is empty.");
        validateSize(expectedId, value);
        long scope = LunarFitAppearance.scope();
        if (!LunarFitAppearance.active()) throw new IllegalStateException("Load a Fit before accepting this cosmetic style.");
        if (!LunarFitAppearance.save(scope, expectedId, value, false)) throw new IllegalStateException("Fit is unavailable.");
        if (Main.getGlobalChatClient() != null) Main.getGlobalChatClient().requestImmediatePoll();
    }

    /** Validate one serialized custom style and return its normalized row. */
    public static JsonObject validateSharedValue(long expectedId, JsonObject row) {
        if (expectedId < 1 || expectedId > Integer.MAX_VALUE || row == null || !row.has("id") || id(row) != expectedId)
            throw new IllegalArgumentException("Invalid cosmetic ID.");
        Values value = values(row);
        if (value.isDefault()) throw new IllegalArgumentException("This is not a custom appearance.");
        return value.json(expectedId);
    }
    public static synchronized void close(String encoded) {
        try { ownedDraft(payload(encoded)); preview = null; ++previewRevision; } catch (Exception ignored) { }
    }
    /** Changes only when this account's local appearance draft changes, so active render scopes can refresh it cheaply. */
    public static long previewRevision(UUID owner,long id) {
        Preview draft=preview;
        return draft!=null&&draft.id==id&&(owner==null||owner.equals(draft.owner))?draft.revision:0L;
    }
    public static synchronized Values get(UUID owner, long id) {
        loadAccount();
        // Apply drafts to the exact Fit mannequin being edited, and to the local player
        // only when that Fit is currently active. The Locker detail mannequin can render
        // without an identified Fit scope; its selected appearance panel supplies the Fit.
        boolean draftContext=preview!=null&&(preview.fitId==null
                ?owner!=null||LunarFitAppearance.isActiveFitModelContext()
                :owner!=null?LunarFitAppearance.isActiveFit(preview.fitId)
                        :LunarFitAppearance.isFitModelContext(preview.fitId)
                                ||LunarFitAppearance.isUnscopedFitPreview(preview.fitId));
        if (preview != null && (owner == null || owner.equals(account))
                && GomodLogoColorSync.isFullAccessColorAvailable()
                && preview.id == id && preview.scope == LunarFitAppearance.scope()
                && System.currentTimeMillis() - preview.updated < 1800000L
                && draftContext)
            return preview.value;
        Values model = LunarFitAppearance.preview(id, false); if (model != null) return model;
        if (owner == null || owner.equals(account)) {
            if (preview != null && GomodLogoColorSync.isFullAccessColorAvailable() && preview.fitId == null
                    && preview.id == id && preview.scope == LunarFitAppearance.scope()
                    && System.currentTimeMillis() - preview.updated < 1800000L)
                return preview.value;
            return savedValues().getOrDefault(id, DEFAULT);
        }
        Peer peer = peers.get(owner);
        if (peer == null || System.currentTimeMillis() - peer.received > GomodSocialPresence.PRESENCE_TTL_MS
                || !ConfigManager.isLunarTweaksSeeGomodPlayers()
                || GomodPeerLunarFullAccess.shouldHideLunarCosmetics(owner)) return DEFAULT;
        String server = GomodSocialPresence.resolveLocalServer(Minecraft.getMinecraft());
        if (server == null || !server.equalsIgnoreCase(peerServer)) return DEFAULT;
        return peer.values.getOrDefault(id, DEFAULT);
    }
    public static synchronized void attachLocalState(JsonObject payload) {
        loadAccount();
        boolean enabled = account != null && GomodFixes.isWeaveRuntime() && ConfigManager.isLunarTweaksShowToOtherPlayers();
        payload.addProperty("cosmeticAppearanceStatePresent", enabled);
        payload.add("cosmeticAppearance", enabled ? rows(savedValues()) : new JsonArray());
    }
    public static void acceptPeers(JsonArray snapshot) {
        if (snapshot == null) return;
        String server = GomodSocialPresence.resolveLocalServer(Minecraft.getMinecraft());
        peerServer = server == null ? "" : server;
        Set<UUID> retained = new HashSet<>();
        for (JsonElement element : snapshot) try {
            JsonObject row = element.getAsJsonObject();
            UUID owner = UUID.fromString(row.get("playerUuid").getAsString().replaceFirst(
                    "^(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})$", "$1-$2-$3-$4-$5"));
            if (!row.has("cosmeticAppearanceStatePresent") || !row.get("cosmeticAppearanceStatePresent").getAsBoolean()
                    || !row.get("lunarFullAccessShareEnabled").getAsBoolean()) continue;
            Map<Long, Values> value = parseRows(row.getAsJsonArray("cosmeticAppearance"));
            peers.put(owner, new Peer(value)); retained.add(owner);
        } catch (Exception ignored) { }
        peers.keySet().retainAll(retained);
    }
    public static void refreshPeerTimestamps(long now) {
        for (Peer peer : peers.values()) if (peer != null) peer.received = now;
    }
    private static final class Preview {
        final UUID owner,fitId; final long id, sequence, scope, revision; final String token; final Values value; final long updated = System.currentTimeMillis();
        Preview(UUID owner, UUID fitId, long id, String token, Values value, long sequence, long scope,long revision) { this.owner=owner;this.fitId=fitId;this.id=id;this.token=token;this.value=value;this.sequence=sequence;this.scope=scope;this.revision=revision; }
    }
    private static final class Peer {
        final Map<Long, Values> values; volatile long received = System.currentTimeMillis();
        Peer(Map<Long, Values> values) { this.values=values; }
    }
}
