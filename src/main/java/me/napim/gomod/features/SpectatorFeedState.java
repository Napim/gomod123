package me.napim.gomod.features;

import java.util.*;

/** Bounded, session-ordered, short-lived spectator observations; never persisted. */
public final class SpectatorFeedState {
    public static final int MAX_PLAYERS = 32;
    public static final long STALE_MS = 2000L;
    private final Map<UUID, Frame> sources = new HashMap<UUID, Frame>();

    public static final class Sample {
        public final UUID uuid;
        public final String name;
        public final double x, y, z;
        public final float yaw, pitch;
        public final SpectatorSkin skin;
        public final SpectatorVisualState visual;
        public Sample(UUID uuid, String name, double x, double y, double z, float yaw, float pitch) {
            this(uuid,name,x,y,z,yaw,pitch,null);
        }
        public Sample(UUID uuid, String name, double x, double y, double z, float yaw, float pitch,SpectatorSkin skin) {
            this(uuid,name,x,y,z,yaw,pitch,skin,null);
        }
        public Sample(UUID uuid, String name, double x, double y, double z, float yaw, float pitch,
                      SpectatorSkin skin, SpectatorVisualState visual) {
            this.uuid = uuid; this.name = name; this.x = x; this.y = y; this.z = z;
            this.yaw = yaw; this.pitch = pitch; this.skin=skin; this.visual=visual;
        }
        public boolean valid() {
            return uuid != null && name != null && name.matches("[A-Za-z0-9_]{1,16}")
                    && bounded(x, 30000000D) && bounded(y, 4096D) && bounded(z, 30000000D)
                    && bounded(yaw, 3600D) && bounded(pitch, 90D) && (skin==null||skin.valid())
                    && (visual==null||visual.valid());
        }
    }

    private static final class Frame {
        final UUID source;
        final String session;
        final long sequence, receivedAt;
        final List<Sample> players;
        Frame(UUID source, String session, long sequence, List<Sample> players, long receivedAt) {
            this.source = source; this.session = session; this.sequence = sequence;
            this.players = players; this.receivedAt = receivedAt;
        }
    }

    public boolean replace(UUID source, String session, long sequence, List<Sample> players, long now) {
        prune(now);
        if (source == null || session == null || !session.matches("[a-fA-F0-9-]{36}")
                || sequence < 1 || players == null || players.size() > MAX_PLAYERS) return false;
        Set<UUID> ids = new HashSet<UUID>();
        for (Sample player : players) {
            if (player == null || !player.valid() || !ids.add(player.uuid)) return false;
        }
        Frame previous = sources.get(source);
        if (previous != null && previous.session.equals(session) && sequence <= previous.sequence) return false;
        if (previous == null && sources.size() >= MAX_PLAYERS) return false;
        sources.put(source, new Frame(source, session, sequence, new ArrayList<Sample>(players), now));
        return true;
    }

    public List<Sample> snapshot(long now) {
        prune(now);
        Map<UUID, Frame> latest = new HashMap<UUID, Frame>();
        Map<UUID, Sample> players = new LinkedHashMap<UUID, Sample>();
        for (Frame frame : sources.values()) {
            for (Sample sample : frame.players) {
                Frame old = latest.get(sample.uuid);
                // A spectator's own fresh position wins over another observer's copy.
                if (old != null && (old.source.equals(sample.uuid)
                        || (!frame.source.equals(sample.uuid) && old.receivedAt >= frame.receivedAt))) continue;
                latest.put(sample.uuid, frame);
                players.put(sample.uuid, sample);
            }
        }
        List<Sample> result = new ArrayList<Sample>(players.values());
        Collections.sort(result, new Comparator<Sample>() {
            @Override public int compare(Sample a, Sample b) { return a.uuid.compareTo(b.uuid); }
        });
        return result.size() <= MAX_PLAYERS ? result : new ArrayList<Sample>(result.subList(0, MAX_PLAYERS));
    }

    public void remove(UUID source) { sources.remove(source); }
    public void clear() { sources.clear(); }
    private void prune(long now) {
        Iterator<Frame> iterator = sources.values().iterator();
        while (iterator.hasNext()) {
            Frame frame = iterator.next();
            if (now < frame.receivedAt || now - frame.receivedAt >= STALE_MS) iterator.remove();
        }
    }
    private static boolean bounded(double value, double max) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && Math.abs(value) <= max;
    }
}
