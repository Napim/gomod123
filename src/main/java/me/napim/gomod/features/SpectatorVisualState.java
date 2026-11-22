package me.napim.gomod.features;

/** Visible spectator pose only; no inventory, custom NBT or persistent history. */
public final class SpectatorVisualState {
    public final boolean sneaking;
    /** Count of observed sneak edges so short presses between relay frames are retained. */
    public final long sneakSequence;
    /** Optional last sixteen tick states, newest in bit zero, on this observer's clock. */
    public final long poseTick;
    public final int sneakHistory;
    public final long swing;
    public final Held held;
    /** Receiver-derived observer/session identity; never accepted from a player payload. */
    public final String stream;
    public SpectatorVisualState(boolean sneaking, long swing, Held held) {
        this(sneaking,swing,held,0L,"");
    }
    public SpectatorVisualState(boolean sneaking, long swing, Held held, String stream) {
        this(sneaking,swing,held,0L,stream);
    }
    public SpectatorVisualState(boolean sneaking, long swing, Held held, long sneakSequence, String stream) {
        this(sneaking,swing,held,sneakSequence,stream,-1L,0);
    }
    public SpectatorVisualState(boolean sneaking, long swing, Held held, long sneakSequence, String stream,
                               long poseTick, int sneakHistory) {
        this.sneaking = sneaking; this.swing = swing; this.held = held;
        this.sneakSequence = sneakSequence; this.stream=stream;
        this.poseTick=poseTick; this.sneakHistory=sneakHistory;
    }
    public boolean valid() { return swing >= 0 && swing <= Integer.MAX_VALUE
            && sneakSequence >= 0 && sneakSequence <= Integer.MAX_VALUE && stream!=null && stream.length()<=80
            && (poseTick == -1L || (poseTick >= 1L && poseTick <= Integer.MAX_VALUE))
            && sneakHistory >= 0 && sneakHistory <= 65535
            && (poseTick == -1L || ((sneakHistory & 1) != 0) == sneaking)
            && (held == null || held.valid()); }
    public static final class Held {
        public final int id, damage, count;
        public Held(int id, int damage, int count) { this.id=id; this.damage=damage; this.count=count; }
        public boolean valid() { return id > 0 && id < 4096 && damage >= 0 && damage <= 32767 && count >= 1 && count <= 64; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Held)) return false;
            Held h=(Held)other; return id==h.id && damage==h.damage && count==h.count;
        }
        @Override public int hashCode() { return (id*31+damage)*31+count; }
    }
}
