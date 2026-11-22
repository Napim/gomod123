package me.napim.gomod.features;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Writes live taunt and emote scalars into Lunar's GEK animation evaluator. */
public final class GoparkyTauntQueries {
    private static final Map<Class<?>, Writer> WRITERS = new ConcurrentHashMap<Class<?>, Writer>();
    private static final String[] LUNAR_EMOTE_VARIABLES = GoparkyLunarEmoteLibrary.queryVariableNames();
    private static long writes;
    private static long failures;
    private static boolean reported;

    private GoparkyTauntQueries() {}

    public static void update(Object actor, Object evaluator) {
        if (actor == null || evaluator == null) return;
        try {
            Writer writer = WRITERS.get(evaluator.getClass());
            if (writer == null) {
                writer = new Writer(evaluator.getClass());
                WRITERS.put(evaluator.getClass(), writer);
            }
            double[] pose = GoparkyBlitzReactions.getTauntPose(actor);
            writer.set.invoke(evaluator, writer.query, "gomod_crouch", pose[0]);
            writer.set.invoke(evaluator, writer.query, "gomod_head_pitch", pose[1]);
            writer.set.invoke(evaluator, writer.query, "gomod_swing", pose[2]);
            writer.set.invoke(evaluator, writer.query, "gomod_run",
                    GoparkyBlitzReactions.getTauntTravelRunAmount(actor));
            writer.set.invoke(evaluator, writer.query, "gomod_travel_speed",
                    GoparkyTauntSettings.get().travelBlocksPerSecond / 20.0D);
            double emote = GoparkyBlitzReactions.getGoparkyEmoteQuery(actor);
            writer.set.invoke(evaluator, writer.query, "gomod_emote", emote);
            if (emote > 0.0D) {
                double[] emotePose = GoparkyBlitzReactions.getGoparkyLunarEmotePose(actor);
                if (emotePose != null && emotePose.length == LUNAR_EMOTE_VARIABLES.length) {
                    for (int index = 0; index < emotePose.length; index++) {
                        writer.set.invoke(evaluator, writer.query, LUNAR_EMOTE_VARIABLES[index], emotePose[index]);
                    }
                }
            }
            writes++;
        } catch (Throwable error) {
            failures++;
            if (!reported) {
                reported = true;
                System.err.println("[gomod] Goparky native taunt queries failed: " + error.getClass().getSimpleName());
            }
        }
    }

    public static long writes() { return writes; }
    public static long failures() { return failures; }

    private static final class Writer {
        final Method set;
        final Object query;

        Writer(Class<?> evaluator) throws ReflectiveOperationException {
            Method found = null;
            for (Method candidate : evaluator.getMethods()) {
                Class<?>[] args = candidate.getParameterTypes();
                if (args.length == 3 && args[1] == String.class && args[2] == double.class
                        && "com.eliotlash.molang.variables.VariableFlavor".equals(args[0].getName())) {
                    found = candidate;
                    break;
                }
            }
            if (found == null) throw new NoSuchMethodException("Native Molang variable setter");
            set = found;
            query = found.getParameterTypes()[0].getField("QUERY").get(null);
        }
    }
}
