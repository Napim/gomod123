package me.napim.gomod.features;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Reads Lunar's installed BOBJ emote tracks and retargets their exact keyframes to GoParky's rig. */
public final class GoparkyLunarEmoteLibrary {
    private static final String CATALOG_RESOURCE = "lunar:emotes/emotes.json";
    private static final String[] TARGET_BONES = {
            "mini_me", "torso", "lowerbody", "head",
            "left_arm", "left_forearm", "right_arm", "right_forearm",
            "left_leg", "left_calf", "right_leg", "right_calf"
    };
    private static final String[] CHANNELS = {"rotation", "location", "scale"};
    private static final String[] AXES = {"x", "y", "z"};
    private static final double POSITION_SCALE = 16.0D;
    private static final long LOOP_PREVIEW_DURATION_MS = 5_500L;
    private static final int VALUES_PER_BONE = 9;
    private static final int POSE_VALUE_COUNT = TARGET_BONES.length * VALUES_PER_BONE;
    private static final String[] QUERY_VARIABLES = createQueryVariables();
    private static final Object LOAD_LOCK = new Object();
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "gomod-goparky-lunar-emotes");
        thread.setDaemon(true);
        return thread;
    });
    private static final ThreadLocal<double[]> POSE_BUFFER = ThreadLocal.withInitial(() -> new double[POSE_VALUE_COUNT]);

    private static volatile Library library;
    private static volatile boolean loading;
    private static volatile long lastFailureAtMs;
    private static volatile String loadFailure = "";

    private GoparkyLunarEmoteLibrary() {}

    /** Starts a one-time background read through Lunar's existing resource manager. */
    public static void requestLoad(Minecraft minecraft) {
        if (minecraft == null || library != null || loading) return;
        final IResourceManager resources;
        try {
            resources = minecraft.getResourceManager();
        } catch (Throwable unavailable) {
            return;
        }
        if (resources == null) return;
        synchronized (LOAD_LOCK) {
            if (library != null || loading) return;
            if (lastFailureAtMs != 0L && System.currentTimeMillis() - lastFailureAtMs < 5_000L) return;
            loading = true;
            loadFailure = "";
            LOADER.execute(() -> {
                try {
                    library = load(resources);
                    System.out.println("[gomod] Loaded " + library.playable.size()
                            + " body-only Lunar emote clips for GoParky");
                } catch (Throwable error) {
                    loadFailure = error.getClass().getSimpleName()
                            + (error.getMessage() == null ? "" : ": " + error.getMessage());
                    lastFailureAtMs = System.currentTimeMillis();
                    System.err.println("[gomod] Could not load Lunar emote assets: " + loadFailure);
                } finally {
                    loading = false;
                }
            });
        }
    }

    public static boolean isReady() {
        return library != null;
    }

    public static boolean isLoading() {
        return loading;
    }

    public static String loadFailure() {
        return loadFailure;
    }

    public static String[] queryVariableNames() {
        return QUERY_VARIABLES.clone();
    }

    public static Emote find(String nameOrId) {
        Library current = library;
        if (current == null || nameOrId == null) return null;
        String requested = nameOrId.trim().toLowerCase(Locale.ROOT);
        if (requested.startsWith("emote_")) requested = requested.substring("emote_".length());
        try {
            Emote emote = current.byId.get(Integer.parseInt(requested));
            return emote != null && emote.playable ? emote : null;
        } catch (NumberFormatException ignored) {
            Emote emote = current.byName.get(requested);
            return emote != null && emote.playable ? emote : null;
        }
    }

    public static Emote random(Random random, int previousId) {
        Library current = library;
        if (current == null || current.playable.isEmpty()) return null;
        int index = random.nextInt(current.playable.size());
        Emote selected = current.playable.get(index);
        if (current.playable.size() > 1 && selected.id == previousId) {
            selected = current.playable.get((index + 1 + random.nextInt(current.playable.size() - 1))
                    % current.playable.size());
        }
        return selected;
    }

    public static String nameForId(int id) {
        Library current = library;
        Emote emote = current == null ? null : current.byId.get(id);
        return emote == null ? "unknown" : emote.name;
    }

    public static String[] playableNames() {
        Library current = library;
        if (current == null) return new String[0];
        String[] names = new String[current.playable.size()];
        for (int i = 0; i < names.length; i++) names[i] = current.playable.get(i).name;
        return names;
    }

    public static long playbackDurationMs(Emote emote) {
        if (emote == null) return 0L;
        if (emote.looping) return LOOP_PREVIEW_DURATION_MS;
        return Math.max(500L, emote.durationFrames * 50L);
    }

    /** Returns the sampled rotation, position and scale vectors in the fixed TARGET_BONES order. */
    public static double[] samplePose(int emoteId, long elapsedMs) {
        double[] pose = POSE_BUFFER.get();
        Arrays.fill(pose, 0.0D);
        for (int bone = 0; bone < TARGET_BONES.length; bone++) {
            int scaleOffset = bone * VALUES_PER_BONE + 6;
            pose[scaleOffset] = 1.0D;
            pose[scaleOffset + 1] = 1.0D;
            pose[scaleOffset + 2] = 1.0D;
        }

        Library current = library;
        Emote emote = current == null ? null : current.byId.get(emoteId);
        if (emote == null || emote.clip == null) return pose;
        double frame = Math.max(0L, elapsedMs) / 50.0D;
        if (emote.durationFrames > 0) {
            frame = emote.looping ? frame % emote.durationFrames : Math.min(frame, emote.durationFrames);
        }
        emote.clip.sample(frame, pose);
        return pose;
    }

    private static Library load(IResourceManager resources) throws IOException {
        JsonObject catalog;
        try (BufferedReader reader = openReader(resources, CATALOG_RESOURCE)) {
            JsonElement parsed = new JsonParser().parse(reader);
            if (!parsed.isJsonObject()) throw new IOException("Lunar emote catalog is not an object");
            catalog = parsed.getAsJsonObject();
        }

        Map<String, ClipBuilder> parsedClips = new HashMap<String, ClipBuilder>();
        JsonArray actionFiles = catalog.getAsJsonArray("actions");
        if (actionFiles == null || actionFiles.size() == 0) throw new IOException("Lunar emote action list is empty");
        for (JsonElement file : actionFiles) {
            parseActionFile(resources, file.getAsString(), parsedClips);
        }

        JsonArray emoteRows = catalog.getAsJsonArray("emotes");
        if (emoteRows == null) throw new IOException("Lunar emote catalog has no emotes");
        Map<Integer, Emote> byId = new HashMap<Integer, Emote>();
        Map<String, Emote> byName = new HashMap<String, Emote>();
        List<Emote> playable = new ArrayList<Emote>();
        for (JsonElement row : emoteRows) {
            if (!row.isJsonObject()) continue;
            JsonObject metadata = row.getAsJsonObject();
            int id = intValue(metadata, "id", -1);
            String name = stringValue(metadata, "name", "");
            if (id < 0 || name.isEmpty()) continue;
            int duration = Math.max(1, intValue(metadata, "duration", 1));
            boolean looping = booleanValue(metadata, "looping", false);
            boolean hasMorph = metadata.has("morph") && !metadata.get("morph").isJsonNull();
            JsonArray props = metadata.getAsJsonArray("meshes");
            boolean hasProps = props != null && props.size() > 0;
            ClipBuilder clipBuilder = parsedClips.get("emote_" + name);
            EmoteClip clip = clipBuilder == null ? null : clipBuilder.build();
            if (clip == null || !clip.hasMascotBones()) continue;

            Emote emote = new Emote(id, name, duration, looping, clip, !hasMorph && !hasProps);
            byId.put(id, emote);
            byName.put(name.toLowerCase(Locale.ROOT), emote);
            if (emote.playable) playable.add(emote);
        }
        if (playable.isEmpty()) throw new IOException("No body-only Lunar emote clips match GoParky's rig");
        Collections.sort(playable, Comparator.comparingInt(emote -> emote.id));
        return new Library(byId, byName, playable);
    }

    private static void parseActionFile(IResourceManager resources, String resourceName,
                                        Map<String, ClipBuilder> clips) throws IOException {
        try (BufferedReader reader = openReader(resources, resourceName)) {
            String clipName = null;
            String boneName = null;
            String channel = null;
            int axis = -1;
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.charAt(0) == '#') continue;
                String[] parts = trimmed.split("\\s+");
                if (parts.length < 2) continue;
                if ("an".equals(parts[0])) {
                    clipName = parts[1];
                    clips.computeIfAbsent(clipName, ignored -> new ClipBuilder());
                    boneName = null;
                    channel = null;
                    axis = -1;
                } else if (clipName != null && "ao".equals(parts[0])) {
                    boneName = parts[1];
                    channel = null;
                    axis = -1;
                } else if (clipName != null && "ag".equals(parts[0]) && parts.length >= 3) {
                    channel = parts[1].toLowerCase(Locale.ROOT);
                    try {
                        axis = Integer.parseInt(parts[2]);
                    } catch (NumberFormatException invalidAxis) {
                        axis = -1;
                    }
                } else if (clipName != null && boneName != null && channel != null
                        && axis >= 0 && axis < 3 && "kf".equals(parts[0]) && parts.length >= 4) {
                    Keyframe keyframe = parseKeyframe(parts);
                    if (keyframe != null) {
                        clips.get(clipName).add(boneName, channel, axis, keyframe);
                    }
                }
            }
        }
    }

    private static Keyframe parseKeyframe(String[] parts) {
        try {
            double frame = Double.parseDouble(parts[1]);
            double value = Double.parseDouble(parts[2]);
            String interpolation = parts[3].toUpperCase(Locale.ROOT);
            boolean hasHandles = "BEZIER".equals(interpolation) && parts.length >= 8;
            return new Keyframe(frame, value, interpolation,
                    hasHandles ? Double.parseDouble(parts[4]) : frame,
                    hasHandles ? Double.parseDouble(parts[5]) : value,
                    hasHandles ? Double.parseDouble(parts[6]) : frame,
                    hasHandles ? Double.parseDouble(parts[7]) : value,
                    hasHandles);
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    private static BufferedReader openReader(IResourceManager resources, String name) throws IOException {
        IResource resource = resources.getResource(new ResourceLocation(name));
        return new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8));
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        try { return object.has(key) ? object.get(key).getAsInt() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static String stringValue(JsonObject object, String key, String fallback) {
        try { return object.has(key) ? object.get(key).getAsString() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
        try { return object.has(key) ? object.get(key).getAsBoolean() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static String[] createQueryVariables() {
        String[] names = new String[POSE_VALUE_COUNT];
        for (int bone = 0; bone < TARGET_BONES.length; bone++) {
            for (int channel = 0; channel < CHANNELS.length; channel++) {
                for (int axis = 0; axis < AXES.length; axis++) {
                    int index = bone * VALUES_PER_BONE + channel * 3 + axis;
                    names[index] = "gomod_lunar_" + TARGET_BONES[bone] + "_"
                            + CHANNELS[channel] + "_" + AXES[axis];
                }
            }
        }
        return names;
    }

    private static int targetBoneIndex(String sourceBone) {
        String target;
        switch (sourceBone) {
            case "anchor": target = "mini_me"; break;
            case "body": target = "torso"; break;
            case "low_body": target = "lowerbody"; break;
            case "low_left_arm": target = "left_forearm"; break;
            case "low_right_arm": target = "right_forearm"; break;
            case "low_left_leg": target = "left_calf"; break;
            case "low_leg_right": target = "right_calf"; break;
            default: target = sourceBone;
        }
        for (int i = 0; i < TARGET_BONES.length; i++) {
            if (TARGET_BONES[i].equals(target)) return i;
        }
        return -1;
    }

    private static final class Library {
        final Map<Integer, Emote> byId;
        final Map<String, Emote> byName;
        final List<Emote> playable;

        Library(Map<Integer, Emote> byId, Map<String, Emote> byName, List<Emote> playable) {
            this.byId = Collections.unmodifiableMap(byId);
            this.byName = Collections.unmodifiableMap(byName);
            this.playable = Collections.unmodifiableList(playable);
        }
    }

    public static final class Emote {
        public final int id;
        public final String name;
        public final int durationFrames;
        public final boolean looping;
        final EmoteClip clip;
        final boolean playable;

        Emote(int id, String name, int durationFrames, boolean looping, EmoteClip clip, boolean playable) {
            this.id = id;
            this.name = name;
            this.durationFrames = durationFrames;
            this.looping = looping;
            this.clip = clip;
            this.playable = playable;
        }
    }

    private static final class ClipBuilder {
        final Map<String, BoneBuilder> bones = new HashMap<String, BoneBuilder>();

        void add(String bone, String channel, int axis, Keyframe keyframe) {
            String normalized = channel.toLowerCase(Locale.ROOT);
            if (!"rotation".equals(normalized) && !"location".equals(normalized) && !"scale".equals(normalized)) {
                return;
            }
            bones.computeIfAbsent(bone, ignored -> new BoneBuilder()).add(normalized, axis, keyframe);
        }

        EmoteClip build() {
            Map<String, BoneTrack> built = new HashMap<String, BoneTrack>();
            for (Map.Entry<String, BoneBuilder> entry : bones.entrySet()) {
                built.put(entry.getKey(), entry.getValue().build());
            }
            return new EmoteClip(built);
        }
    }

    private static final class BoneBuilder {
        final TrackBuilder[][] channels = new TrackBuilder[3][3];

        void add(String channel, int axis, Keyframe keyframe) {
            int channelIndex = "rotation".equals(channel) ? 0 : "location".equals(channel) ? 1 : 2;
            if (channels[channelIndex][axis] == null) channels[channelIndex][axis] = new TrackBuilder();
            channels[channelIndex][axis].keys.add(keyframe);
        }

        BoneTrack build() {
            Track[][] tracks = new Track[3][3];
            for (int channel = 0; channel < 3; channel++) {
                for (int axis = 0; axis < 3; axis++) {
                    if (channels[channel][axis] != null) tracks[channel][axis] = channels[channel][axis].build();
                }
            }
            return new BoneTrack(tracks);
        }
    }

    private static final class TrackBuilder {
        final List<Keyframe> keys = new ArrayList<Keyframe>();

        Track build() {
            Collections.sort(keys, Comparator.comparingDouble(key -> key.frame));
            return new Track(keys.toArray(new Keyframe[0]));
        }
    }

    private static final class EmoteClip {
        final Map<String, BoneTrack> bones;

        EmoteClip(Map<String, BoneTrack> bones) {
            this.bones = bones;
        }

        boolean hasMascotBones() {
            for (String bone : bones.keySet()) {
                if (!"anchor".equals(bone) && targetBoneIndex(bone) >= 0) return true;
            }
            return false;
        }

        void sample(double frame, double[] pose) {
            for (Map.Entry<String, BoneTrack> entry : bones.entrySet()) {
                int targetBone = targetBoneIndex(entry.getKey());
                if (targetBone < 0) continue;
                BoneTrack bone = entry.getValue();
                for (int channel = 0; channel < 3; channel++) {
                    for (int axis = 0; axis < 3; axis++) {
                        Track track = bone.channels[channel][axis];
                        if (track == null) continue;
                        double value = track.sample(frame);
                        int index = targetBone * VALUES_PER_BONE + channel * 3 + axis;
                        if (channel == 0) {
                            pose[index] += Math.toDegrees(value);
                        } else if (channel == 1) {
                            pose[index] += value * POSITION_SCALE;
                        } else {
                            pose[index] *= value;
                        }
                    }
                }
            }
        }
    }

    private static final class BoneTrack {
        final Track[][] channels;
        BoneTrack(Track[][] channels) { this.channels = channels; }
    }

    private static final class Track {
        final Keyframe[] keys;

        Track(Keyframe[] keys) { this.keys = keys; }

        double sample(double frame) {
            if (keys.length == 0) return 0.0D;
            if (frame <= keys[0].frame) return keys[0].value;
            if (frame >= keys[keys.length - 1].frame) return keys[keys.length - 1].value;
            for (int i = 0; i < keys.length - 1; i++) {
                Keyframe left = keys[i];
                Keyframe right = keys[i + 1];
                if (frame <= right.frame) return left.interpolate(right, frame);
            }
            return keys[keys.length - 1].value;
        }
    }

    private static final class Keyframe {
        final double frame;
        final double value;
        final String interpolation;
        final double leftFrame;
        final double leftValue;
        final double rightFrame;
        final double rightValue;
        final boolean hasHandles;

        Keyframe(double frame, double value, String interpolation, double leftFrame, double leftValue,
                 double rightFrame, double rightValue, boolean hasHandles) {
            this.frame = frame;
            this.value = value;
            this.interpolation = interpolation;
            this.leftFrame = leftFrame;
            this.leftValue = leftValue;
            this.rightFrame = rightFrame;
            this.rightValue = rightValue;
            this.hasHandles = hasHandles;
        }

        double interpolate(Keyframe next, double requestedFrame) {
            if ("CONSTANT".equals(interpolation)) return value;
            if ("LINEAR".equals(interpolation) || !hasHandles || !next.hasHandles) {
                double fraction = (requestedFrame - frame) / Math.max(1.0E-9D, next.frame - frame);
                return value + (next.value - value) * fraction;
            }
            double low = 0.0D;
            double high = 1.0D;
            for (int i = 0; i < 24; i++) {
                double middle = (low + high) * 0.5D;
                double x = cubic(frame, rightFrame, next.leftFrame, next.frame, middle);
                if (x < requestedFrame) low = middle;
                else high = middle;
            }
            return cubic(value, rightValue, next.leftValue, next.value, (low + high) * 0.5D);
        }
    }

    private static double cubic(double p0, double p1, double p2, double p3, double t) {
        double inverse = 1.0D - t;
        return inverse * inverse * inverse * p0
                + 3.0D * inverse * inverse * t * p1
                + 3.0D * inverse * t * t * p2
                + t * t * t * p3;
    }
}
