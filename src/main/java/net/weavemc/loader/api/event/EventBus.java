package net.weavemc.loader.api.event;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class EventBus {
    private static final Map<Class<?>, CopyOnWriteArrayList<Handler>> HANDLERS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Handler[]> DISPATCH_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_HANDLER_ERROR_LOG = new ConcurrentHashMap<>();
    private static final long HANDLER_ERROR_LOG_INTERVAL_MS = 5000L;

    private EventBus() {
    }

    public static void subscribe(Object listener) {
        if (listener == null) {
            return;
        }
        Class<?> cls = listener.getClass();
        for (Method method : cls.getMethods()) {
            if (!method.isAnnotationPresent(SubscribeEvent.class)) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1) {
                continue;
            }
            Class<?> eventType = params[0];
            method.setAccessible(true);
            Handler handler = Handler.create(listener, method);
            if (handler != null) {
                HANDLERS.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                        .add(handler);
                DISPATCH_CACHE.clear();
            }
        }
    }

    public static <T extends Event> T post(T event) {
        if (event == null) {
            return null;
        }
        dispatch(event.getClass(), event);
        return event;
    }

    public static void unsubscribe(Object listener) {
        if (listener == null) {
            return;
        }
        boolean changed = false;
        for (CopyOnWriteArrayList<Handler> handlers : HANDLERS.values()) {
            if (handlers == null) {
                continue;
            }
            if (handlers.removeIf(h -> h != null && h.target == listener)) {
                changed = true;
            }
        }
        if (changed) {
            DISPATCH_CACHE.clear();
        }
    }

    private static void dispatch(Class<?> eventClass, Event event) {
        Handler[] handlers = DISPATCH_CACHE.computeIfAbsent(eventClass, EventBus::resolveHandlers);
        for (Handler handler : handlers) {
            try {
                handler.invoke(event);
            } catch (Throwable t) {
                logHandlerError(handler, t);
            }
        }
    }

    private static Handler[] resolveHandlers(Class<?> eventClass) {
        List<Handler> result = new ArrayList<>();
        for (Map.Entry<Class<?>, CopyOnWriteArrayList<Handler>> entry : HANDLERS.entrySet()) {
            if (!entry.getKey().isAssignableFrom(eventClass)) {
                continue;
            }
            CopyOnWriteArrayList<Handler> handlers = entry.getValue();
            if (handlers == null) {
                continue;
            }
            result.addAll(handlers);
        }
        return result.toArray(new Handler[result.size()]);
    }

    private static void logHandlerError(Handler handler, Throwable t) {
        String key = handler.id;
        long now = System.currentTimeMillis();
        Long last = LAST_HANDLER_ERROR_LOG.get(key);
        if (last != null && now - last < HANDLER_ERROR_LOG_INTERVAL_MS) {
            return;
        }
        LAST_HANDLER_ERROR_LOG.put(key, now);
        Throwable cause = t.getCause() != null ? t.getCause() : t;
        System.err.println("[gomod] Event handler failed: " + key + " -> "
                + cause.getClass().getSimpleName() + ": " + String.valueOf(cause.getMessage()));
    }

    private static final class Handler {
        private final Object target;
        private final MethodHandle invoker;
        private final String id;

        private Handler(Object target, MethodHandle invoker, Method method) {
            this.target = target;
            this.invoker = invoker;
            this.id = method.getDeclaringClass().getName() + "#" + method.getName()
                    + "(" + method.getParameterTypes()[0].getName() + ")";
        }

        private static Handler create(Object target, Method method) {
            try {
                MethodHandle invoker = MethodHandles.lookup().unreflect(method).bindTo(target);
                return new Handler(target, invoker, method);
            } catch (Throwable t) {
                System.err.println("[gomod] Could not subscribe event handler "
                        + method.getDeclaringClass().getName() + "#" + method.getName()
                        + ": " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
                return null;
            }
        }

        private void invoke(Event event) throws Throwable {
            invoker.invoke(event);
        }
    }
}
