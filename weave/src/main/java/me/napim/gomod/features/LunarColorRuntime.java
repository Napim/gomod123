package me.napim.gomod.features;

import java.io.InputStream;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import net.minecraft.client.Minecraft;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Reads verified native color layouts from the active loader, without initializing bridge utilities. */
public final class LunarColorRuntime {
    private static volatile Object cosmeticSocket;
    private static volatile Object cosmeticService;
    private static volatile Object plusStore;
    private static volatile String plusStoreSetter;
    private static final ClassValue<Field[]> RECORD_FIELDS = new ClassValue<Field[]>() {
        @Override protected Field[] computeValue(Class<?> type) {
            try { return constructorFields(type); }
            catch (Exception error) { throw new IllegalStateException("Unsupported Lunar color record layout", error); }
        }
    };
    private static final ClassValue<Field> PROFILE_MAP = containerFields("java.util.Map", 7);
    private static final ClassValue<Field> TAB_CACHE = containerFields("com.google.common.cache.Cache", 3);
    private static final ThreadPoolExecutor TAB_DISCOVERY = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(64), task -> {
                Thread thread = new Thread(task, "gomod-lunar-tab-discovery"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static final Map<Object, LinkedHashMap<UUID, Long>> TAB_REQUEST_DUE =
            new WeakHashMap<Object, LinkedHashMap<UUID, Long>>();
    private static final ClassValue<Optional<Method>> TAB_REQUEST = new ClassValue<Optional<Method>>() {
        @Override protected Optional<Method> computeValue(Class<?> type) {
            try {
                ClassNode node = readClass(type);
                Map<String, MethodNode> methods = new HashMap<String, MethodNode>();
                for (MethodNode method : node.methods) methods.put(method.name + method.desc, method);
                MethodNode match = null;
                for (MethodNode method : node.methods) {
                    if (!method.desc.equals("(Ljava/util/Set;)V") || (method.access & Opcodes.ACC_PUBLIC) == 0
                            || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0
                            || !reachesTabRequest(node.name, method, methods, new HashSet<String>(), 0)) continue;
                    if (match != null) throw new NoSuchMethodException("Ambiguous Lunar TAB fetch");
                    match = method;
                }
                if (match == null) throw new NoSuchMethodException("Unsupported Lunar TAB fetch");
                Method result = type.getMethod(match.name, Set.class); result.setAccessible(true);
                return Optional.of(result);
            } catch (Exception error) {
                LunarConnectionDiagnostics.recordOnceAfterStart("lunar_color.tab_fetch_unsupported", type.getName(),
                        "errorType=" + error.getClass().getSimpleName());
                return Optional.empty(); // Cache unsupported shapes too; never reread the native JAR every retry.
            }
        }
    };
    private LunarColorRuntime() { }

    public static void captureSocket(Object socket) {
        if (socket == null) return;
        try {
            Field match = null;
            for (Field field : socket.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !field.getType().getName().equals(
                        "com.lunarclient.websocket.cosmetic.v2.CosmeticService$Interface")) continue;
                if (match != null) throw new IllegalStateException("Ambiguous Lunar cosmetics service fields");
                match = field;
            }
            if (match == null) return;
            match.setAccessible(true);
            Object service = match.get(socket);
            if (service == null) return;
            cosmeticSocket = socket;
            cosmeticService = service;
            LunarConnectionDiagnostics.recordOnceAfterStart("lunar_color.runtime_socket", socket.getClass().getName(),
                    "captured=true source=native-cosmetics-service-field");
        } catch (Throwable error) {
            LunarConnectionDiagnostics.recordOnceAfterStart("lunar_color.runtime_socket_failed", socket.getClass().getName(),
                    "errorType=" + error.getClass().getSimpleName());
        }
    }
    public static Object cosmeticService() {
        Object socket = cosmeticSocket;
        if (socket == null || cosmeticService == null) throw new IllegalStateException("Lunar cosmetics connection is not ready");
        try {
            if (!Boolean.TRUE.equals(socket.getClass().getMethod("isOpen").invoke(socket)))
                throw new IllegalStateException("Lunar cosmetics connection is not ready");
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Lunar cosmetics connection state is unavailable", error);
        }
        return cosmeticService;
    }
    public static Object cosmeticSocket() { return cosmeticSocket; }

    /** The call site is the native plus-color read used by the Locker's active/plusColor state. */
    public static void capturePlusStore(Object store) {
        if (store == null || store == plusStore) return;
        try {
            Method setter = uniqueMethod(store.getClass(), Void.TYPE, UUID.class, Integer.TYPE);
            plusStoreSetter = setter.getName();
            plusStore = store;
        } catch (Exception error) {
            LunarConnectionDiagnostics.recordOnceAfterStart("lunar_color.plus_store_failed", store.getClass().getName(),
                    "errorType=" + error.getClass().getSimpleName());
        }
    }
    public static void setAccountPlus(UUID id, int color) throws Exception {
        Object store = plusStore;
        if (store == null) throw new IllegalStateException("Lunar's local Lunar+ preference is not ready");
        store.getClass().getMethod(plusStoreSetter, UUID.class, Integer.TYPE).invoke(store, id, color);
    }

    public static Object profileMap(Object manager) throws Exception { return containerValue(PROFILE_MAP, manager); }
    public static Object tabCache(Object manager) throws Exception { return containerValue(TAB_CACHE, manager); }
    private static Object containerValue(ClassValue<Field> fields, Object owner) throws Exception {
        try { return fields.get(owner.getClass()).get(owner); }
        catch (IllegalStateException error) {
            if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
            throw error;
        }
    }
    private static ClassValue<Field> containerFields(final String typeName, final int recordFields) {
        return new ClassValue<Field>() {
            @Override protected Field computeValue(Class<?> type) {
                try { return typedContainerField(type, typeName, recordFields); }
                catch (Exception error) { throw new IllegalStateException("Unsupported Lunar color cache layout", error); }
            }
        };
    }
    private static Field typedContainerField(Class<?> ownerType, String rawType, int recordFields) throws Exception {
        Field match = null;
        for (Field field : ownerType.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || !field.getType().getName().equals(rawType)) continue;
            if (!(field.getGenericType() instanceof ParameterizedType)) continue;
            java.lang.reflect.Type[] args = ((ParameterizedType) field.getGenericType()).getActualTypeArguments();
            if (args.length != 2 || args[0] != UUID.class || !(args[1] instanceof Class)) continue;
            Class<?> value = (Class<?>) args[1];
            if (!hasRecordShape(value, recordFields)) continue;
            if (match != null) throw new NoSuchFieldException("Ambiguous Lunar color cache layout");
            match = field;
        }
        if (match == null) throw new NoSuchFieldException("No verified Lunar color cache layout");
        match.setAccessible(true);
        return match;
    }
    private static boolean hasRecordShape(Class<?> type, int count) {
        for (Constructor<?> ctor : type.getDeclaredConstructors()) {
            Class<?>[] p = ctor.getParameterTypes();
            if (p.length != count) continue;
            if (count == 7 && p[0] == Float.TYPE && p[1] == Float.TYPE && p[2] == Float.TYPE
                    && p[3] == Boolean.TYPE && p[4] == Integer.TYPE && p[5] == Boolean.TYPE && !p[6].isPrimitive()) return true;
            if (count == 3 && p[0] == Integer.TYPE && p[1] == Integer.TYPE && !p[2].isPrimitive()) return true;
        }
        return false;
    }
    private static Field[] constructorFields(Class<?> type) throws Exception {
        ClassNode node = readClass(type);
        MethodNode ctor = null;
        int count = hasRecordShape(type, 7) ? 7 : hasRecordShape(type, 3) ? 3 : 0;
        if (count == 0) throw new NoSuchMethodException("Unsupported Lunar color record constructor");
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<init>")) continue;
            org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(method.desc);
            if (args.length != count) continue;
            boolean shape = count == 7 ? method.desc.startsWith("(FFFZIZL") : method.desc.startsWith("(IIL");
            if (!shape) continue;
            if (ctor != null) throw new NoSuchMethodException("Ambiguous Lunar color record constructors");
            ctor = method;
        }
        if (ctor == null) throw new NoSuchMethodException("Missing Lunar color record constructor bytecode");
        Field[] fields = new Field[count];
        for (AbstractInsnNode instruction : ctor.instructions) {
            if (!(instruction instanceof FieldInsnNode) || instruction.getOpcode() != Opcodes.PUTFIELD) continue;
            FieldInsnNode write = (FieldInsnNode) instruction;
            AbstractInsnNode value = previous(instruction);
            AbstractInsnNode receiver = previous(value);
            if (!write.owner.equals(node.name) || !(value instanceof VarInsnNode) || !(receiver instanceof VarInsnNode)
                    || receiver.getOpcode() != Opcodes.ALOAD || ((VarInsnNode) receiver).var != 0) continue;
            int index = ((VarInsnNode) value).var - 1;
            if (index < 0 || index >= count || fields[index] != null) throw new NoSuchFieldException("Ambiguous Lunar color field assignment");
            Field field = type.getDeclaredField(write.name);
            if (Modifier.isStatic(field.getModifiers())) throw new NoSuchFieldException("Static Lunar color field");
            field.setAccessible(true);
            fields[index] = field;
        }
        for (Field field : fields) if (field == null) throw new NoSuchFieldException("Incomplete Lunar color constructor assignments");
        return fields;
    }
    public static Object recordValue(Object record, int index, int count) throws Exception {
        Field[] fields = RECORD_FIELDS.get(record.getClass());
        if (fields.length != count) throw new NoSuchFieldException("Unexpected Lunar color record shape");
        return fields[index].get(record);
    }
    public static int profileRgb(Object record) throws Exception {
        int red = Math.round(((Number) recordValue(record, 0, 7)).floatValue() * 255F);
        int green = Math.round(((Number) recordValue(record, 1, 7)).floatValue() * 255F);
        int blue = Math.round(((Number) recordValue(record, 2, 7)).floatValue() * 255F);
        return ((red & 255) << 16) | ((green & 255) << 8) | (blue & 255);
    }
    public static Method uniqueMethod(Class<?> type, Class<?> result, Class<?>... args) throws Exception {
        Method match = null;
        for (Method method : type.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.isBridge() || !Arrays.equals(args, method.getParameterTypes())
                    || method.getReturnType() != result) continue;
            if (match != null) throw new NoSuchMethodException("Ambiguous Lunar method signature");
            match = method;
        }
        if (match == null) throw new NoSuchMethodException("Unsupported Lunar method signature");
        return match;
    }
    public static void applyProfilePush(Object manager, UUID id, Class<?> pushClass, Object push, int badge) throws Exception {
        uniqueMethod(manager.getClass(), Void.TYPE, UUID.class, pushClass, Integer.TYPE).invoke(manager, id, push, badge);
    }
    public static Object tabRecord(Object manager, UUID id) throws Exception {
        Field cache = TAB_CACHE.get(manager.getClass());
        Class<?> recordType = (Class<?>) ((ParameterizedType) cache.getGenericType()).getActualTypeArguments()[1];
        // Lunar's own getter filters its not-loaded sentinel before returning a profile.
        return uniqueMethod(manager.getClass(), recordType, UUID.class).invoke(manager, id);
    }
    public static void requestTabRecord(Object manager, UUID id) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        if (manager == null || id == null || mc == null || mc.getSession() == null) return;
        final String expectedAccount = mc.getSession().getPlayerID();
        final Object expectedWorld = mc.theWorld, expectedLocal = mc.thePlayer, expectedConnection = mc.getNetHandler();
        final Object expectedSocket = cosmeticSocket;
        final long now = System.currentTimeMillis(), deadline = now + 5000L;
        synchronized (TAB_REQUEST_DUE) {
            LinkedHashMap<UUID, Long> due = TAB_REQUEST_DUE.get(manager);
            if (due == null) { due = new LinkedHashMap<UUID, Long>(); TAB_REQUEST_DUE.put(manager, due); }
            Long next = due.get(id);
            if (next != null && now < next.longValue()) return;
            if (due.size() >= 128 && !due.containsKey(id)) due.remove(due.keySet().iterator().next());
            due.put(id, now + 2000L);
        }
        try {
            TAB_DISCOVERY.execute(() -> {
                Optional<Method> fetch = TAB_REQUEST.get(manager.getClass());
                if (!fetch.isPresent()) return;
                mc.addScheduledTask(() -> {
                    if (System.currentTimeMillis() >= deadline || mc.theWorld != expectedWorld || mc.thePlayer != expectedLocal
                            || mc.getNetHandler() != expectedConnection || cosmeticSocket != expectedSocket
                            || mc.getSession() == null || !Objects.equals(expectedAccount, mc.getSession().getPlayerID())) return;
                    try { fetch.get().invoke(manager, Collections.singleton(id)); }
                    catch (Exception error) {
                        LunarConnectionDiagnostics.recordOnceAfterStart("lunar_color.tab_fetch_failed", manager.getClass().getName(),
                                "errorType=" + error.getClass().getSimpleName());
                    }
                });
            });
        } catch (RejectedExecutionException busy) {
            LunarConnectionDiagnostics.recordOnceAfterStart("lunar_color.tab_fetch_busy", "bounded-queue",
                    "dropped=true retry=next-profile-refresh");
        }
    }

    /** Follow only same-class calls/lambda handles to Lunar's stable TAB RPC builder. */
    private static boolean reachesTabRequest(String owner, MethodNode method, Map<String, MethodNode> methods,
                                             Set<String> visited, int depth) {
        if (method == null || depth > 8 || !visited.add(method.name + method.desc)) return false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.owner.equals("com/lunarclient/websocket/cosmetic/v2/LoadTabLogosRequest")
                        || call.owner.equals("com/lunarclient/websocket/cosmetic/v2/CosmeticService$Interface")
                        && call.name.equals("loadTabLogos")) return true;
                if (call.owner.equals(owner) && reachesTabRequest(owner, methods.get(call.name + call.desc), methods, visited, depth + 1)) return true;
            } else if (instruction instanceof InvokeDynamicInsnNode) {
                for (Object argument : ((InvokeDynamicInsnNode) instruction).bsmArgs) if (argument instanceof org.objectweb.asm.Handle) {
                    org.objectweb.asm.Handle handle = (org.objectweb.asm.Handle) argument;
                    if (handle.getOwner().equals(owner) && reachesTabRequest(owner, methods.get(handle.getName() + handle.getDesc()),
                            methods, visited, depth + 1)) return true;
                }
            }
        }
        return false;
    }
    public static ClassNode readClass(Class<?> type) throws Exception {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            if (input == null) throw new ClassNotFoundException("Active Lunar class bytes unavailable");
            ClassNode node = new ClassNode(); new ClassReader(input).accept(node, 0);
            if (!node.name.replace('/', '.').equals(type.getName())) throw new ClassNotFoundException("Active Lunar class resource mismatch");
            return node;
        }
    }
    private static AbstractInsnNode previous(AbstractInsnNode node) {
        if (node == null) return null;
        do { node = node.getPrevious(); } while (node != null && node.getOpcode() < 0);
        return node;
    }
}
