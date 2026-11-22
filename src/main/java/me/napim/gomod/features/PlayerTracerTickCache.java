package me.napim.gomod.features;

import java.util.IdentityHashMap;
import java.util.Map;

/** Ray results belong to one world, simulation tick and range; render frames reuse them. */
public final class PlayerTracerTickCache<T> {
    private final Map<Object, T> values = new IdentityHashMap<Object, T>();
    private Object world;
    private long tick = Long.MIN_VALUE;
    private double range;

    public void begin(Object currentWorld, long currentTick, double currentRange) {
        if (world != currentWorld || tick != currentTick || Double.compare(range, currentRange) != 0) {
            values.clear();
            world = currentWorld;
            tick = currentTick;
            range = currentRange;
        }
    }

    public T get(Object player) { return values.get(player); }
    public void put(Object player, T result) { values.put(player, result); }

    public void clear() {
        values.clear();
        world = null;
        tick = Long.MIN_VALUE;
    }
}
