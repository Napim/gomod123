package me.napim.gomod.weave;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;

/** Tests structural bytecode matching, dirty-marking, and save/load round-trips for Quickplay keybinds. */
public final class LunarQuickplayPersistenceTransformTest {
    private static final String HELPER = "me/napim/gomod/weave/LunarQuickplayPersistenceBridge";
    private static final String PKG = "com/moonsworth/lunar/client/synthetic/qp/";
    private static final String MOD_BASE = PKG + "FakeModBase";
    private static final String OPTION_CLS = PKG + "FakeOption";
    private static final String CONFIG_CLS = PKG + "FakeConfigFile";
    private static final String QP_CLS = PKG + "FakeQuickplay";
    private static final String SM_CLS = PKG + "FakeSettingsManager";

    @Before
    public void reset() {
        LunarQuickplayPersistenceBridge.resetTestState();
    }

    @Test
    public void realLunarJarQuickplayAndSettingsManagerTransformCleanly() throws Exception {
        File lunarJar = new File("C:/Users/admin0/.lunarclient/offline/multiver/lunar.jar");
        if (!lunarJar.isFile()) return;

        List<String> transformed = new ArrayList<>();
        final boolean[] sawPersist = {false};
        final boolean[] sawDirtyHook = {false};

        try (ZipFile zip = new ZipFile(lunarJar)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (!entryName.endsWith(".class") || !entryName.startsWith("com/moonsworth/lunar/")) continue;
                String className = entryName.substring(0, entryName.length() - ".class".length());
                byte[] original = readAll(zip.getInputStream(entry));
                if (!LunarQuickplayPersistenceTransform.mayContain(className, original)) continue;

                byte[] patched = LunarQuickplayPersistenceTransform.transform(className, original);
                if (patched == null) continue;
                transformed.add(className);

                new ClassReader(patched).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                            String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String methodName,
                                    String methodDesc, boolean isInterface) {
                                if (HELPER.equals(owner) && "persistExtraQuickplayState".equals(methodName)) {
                                    sawPersist[0] = true;
                                }
                                if (HELPER.equals(owner) && "onModOptionDirty".equals(methodName)) {
                                    sawDirtyHook[0] = true;
                                }
                            }
                        };
                    }
                }, ClassReader.EXPAND_FRAMES);
            }
        }

        assertEquals("Expected Quickplay and SettingsManager to match uniquely in lunar.jar: " + transformed,
                2, transformed.size());
        assertTrue("Quickplay save method must call persistExtraQuickplayState", sawPersist[0]);
        assertTrue("SettingsManager recursive dirty check must call onModOptionDirty", sawDirtyHook[0]);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void keybindDirtyMarkingAndSaveLoadWithoutGameListRoundTrips() throws Exception {
        byte[] modBaseBytes = buildModBaseBytes();
        byte[] optionBytes = buildOptionBytes();
        byte[] configBytes = buildConfigBytes();
        byte[] qpBytes = LunarQuickplayPersistenceTransform.transform(QP_CLS, buildQuickplayBytes());
        byte[] smBytes = LunarQuickplayPersistenceTransform.transform(SM_CLS, buildSettingsManagerBytes());
        assertNotNull("Synthetic Quickplay must transform", qpBytes);
        assertNotNull("Synthetic SettingsManager must transform", smBytes);

        // Ensure transformed classes survive EXPAND_FRAMES validation.
        new ClassReader(qpBytes).accept(new ClassVisitor(Opcodes.ASM9) { }, ClassReader.EXPAND_FRAMES);
        new ClassReader(smBytes).accept(new ClassVisitor(Opcodes.ASM9) { }, ClassReader.EXPAND_FRAMES);

        Map<String, byte[]> classes = new HashMap<>();
        classes.put(MOD_BASE.replace('/', '.'), modBaseBytes);
        classes.put(OPTION_CLS.replace('/', '.'), optionBytes);
        classes.put(CONFIG_CLS.replace('/', '.'), configBytes);
        classes.put(QP_CLS.replace('/', '.'), qpBytes);
        classes.put(SM_CLS.replace('/', '.'), smBytes);

        ClassLoader loader = new ClassLoader(LunarQuickplayPersistenceTransformTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] b = classes.get(name);
                if (b != null) return defineClass(name, b, 0, b.length);
                return super.findClass(name);
            }
        };

        Class<?> optionClass = loader.loadClass(OPTION_CLS.replace('/', '.'));
        Class<?> configClass = loader.loadClass(CONFIG_CLS.replace('/', '.'));
        Class<?> modClass = loader.loadClass(MOD_BASE.replace('/', '.'));
        Class<?> qpClass = loader.loadClass(QP_CLS.replace('/', '.'));
        Class<?> smClass = loader.loadClass(SM_CLS.replace('/', '.'));

        Object config = configClass.getDeclaredConstructor().newInstance();
        Object qp = qpClass.getDeclaredConstructor().newInstance();
        Object sm = smClass.getDeclaredConstructor().newInstance();

        // Populate keybinds map and favorites while gameList remains null/empty.
        Object blitzBind = optionClass.getDeclaredConstructor(String.class).newInstance("KEY_G");
        Map<String, Object> keybinds = (Map<String, Object>) qpClass.getDeclaredField("keybinds").get(qp);
        keybinds.put("play blitz_solo_normal", blitzBind);
        Set<String> favorites = (Set<String>) qpClass.getDeclaredField("favorites").get(qp);
        favorites.add("play blitz_solo_normal");

        // Editing a per-game KeybindOption must mark mods config dirty.
        Method checkDirty = smClass.getDeclaredMethod("checkDirty", modClass, optionClass, configClass);
        boolean dirty = (Boolean) checkDirty.invoke(sm, qp, blitzBind, config);
        assertTrue("SettingsManager must recognize Quickplay per-game keybind as belonging to Quickplay", dirty);
        assertEquals("mods.json config save/dirty method must be called once", 1,
                configClass.getDeclaredField("dirtyCount").getInt(config));

        // Saving before gameList has loaded from Hypixel API must still emit qpKeyBinds & qpFavorites.
        JsonObject saved = new JsonObject();
        qpClass.getDeclaredMethod("save", JsonObject.class).invoke(qp, saved);
        assertTrue("qpKeyBinds must be saved even when gameList is empty", saved.has("qpKeyBinds"));
        assertEquals("KEY_G", saved.getAsJsonObject("qpKeyBinds")
                .getAsJsonObject("play blitz_solo_normal").get("key").getAsString());
        assertTrue("qpFavorites must be saved even when gameList is empty", saved.has("qpFavorites"));
        assertEquals("play blitz_solo_normal", saved.getAsJsonArray("qpFavorites").get(0).getAsString());

        // Reload in a fresh Quickplay instance, update keybind, trigger dirty, and re-save with gameList == null.
        Object qpReloaded = qpClass.getDeclaredConstructor().newInstance();
        qpClass.getDeclaredMethod("load", JsonObject.class).invoke(qpReloaded, saved);
        Map<String, Object> reloadedMap = (Map<String, Object>) qpClass.getDeclaredField("keybinds").get(qpReloaded);
        Object reloadedBind = reloadedMap.get("play blitz_solo_normal");
        assertNotNull("Loaded qpKeyBinds must register option in reloaded map", reloadedBind);
        assertEquals("KEY_G", optionClass.getDeclaredField("key").get(reloadedBind));

        optionClass.getDeclaredField("key").set(reloadedBind, "KEY_H");
        assertTrue((Boolean) checkDirty.invoke(sm, qpReloaded, reloadedBind, config));
        assertEquals(2, configClass.getDeclaredField("dirtyCount").getInt(config));

        JsonObject resaved = new JsonObject();
        qpClass.getDeclaredMethod("save", JsonObject.class).invoke(qpReloaded, resaved);
        assertEquals("KEY_H", resaved.getAsJsonObject("qpKeyBinds")
                .getAsJsonObject("play blitz_solo_normal").get("key").getAsString());
    }

    private static byte[] buildModBaseBytes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, MOD_BASE, null, "java/lang/Object", null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
        MethodVisitor save = cw.visitMethod(Opcodes.ACC_PUBLIC, "save", "(Lcom/google/gson/JsonObject;)V", null, null);
        save.visitCode();
        save.visitInsn(Opcodes.RETURN);
        save.visitMaxs(0, 2);
        save.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildOptionBytes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, OPTION_CLS, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC, "key", "Ljava/lang/String;", null, null);

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, OPTION_CLS, "key", "Ljava/lang/String;");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(2, 2);
        init.visitEnd();

        MethodVisitor save = cw.visitMethod(Opcodes.ACC_PUBLIC, "persistOption",
                "(Lcom/google/gson/JsonObject;)V", null, null);
        save.visitCode();
        save.visitVarInsn(Opcodes.ALOAD, 1);
        save.visitLdcInsn("key");
        save.visitVarInsn(Opcodes.ALOAD, 0);
        save.visitFieldInsn(Opcodes.GETFIELD, OPTION_CLS, "key", "Ljava/lang/String;");
        save.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/google/gson/JsonObject", "addProperty",
                "(Ljava/lang/String;Ljava/lang/String;)V", false);
        save.visitInsn(Opcodes.RETURN);
        save.visitMaxs(3, 2);
        save.visitEnd();

        MethodVisitor load = cw.visitMethod(Opcodes.ACC_PUBLIC, "load",
                "(Lcom/google/gson/JsonObject;)V", null, null);
        load.visitCode();
        load.visitVarInsn(Opcodes.ALOAD, 0);
        load.visitVarInsn(Opcodes.ALOAD, 1);
        load.visitLdcInsn("key");
        load.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/google/gson/JsonObject", "get",
                "(Ljava/lang/String;)Lcom/google/gson/JsonElement;", false);
        load.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/google/gson/JsonElement", "getAsString",
                "()Ljava/lang/String;", false);
        load.visitFieldInsn(Opcodes.PUTFIELD, OPTION_CLS, "key", "Ljava/lang/String;");
        load.visitInsn(Opcodes.RETURN);
        load.visitMaxs(3, 2);
        load.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildConfigBytes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, CONFIG_CLS, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC, "dirtyCount", "I", null, null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
        MethodVisitor markDirty = cw.visitMethod(Opcodes.ACC_PUBLIC, "markDirty", "()V", null, null);
        markDirty.visitCode();
        markDirty.visitVarInsn(Opcodes.ALOAD, 0);
        markDirty.visitInsn(Opcodes.DUP);
        markDirty.visitFieldInsn(Opcodes.GETFIELD, CONFIG_CLS, "dirtyCount", "I");
        markDirty.visitInsn(Opcodes.ICONST_1);
        markDirty.visitInsn(Opcodes.IADD);
        markDirty.visitFieldInsn(Opcodes.PUTFIELD, CONFIG_CLS, "dirtyCount", "I");
        markDirty.visitInsn(Opcodes.RETURN);
        markDirty.visitMaxs(3, 1);
        markDirty.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildQuickplayBytes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, QP_CLS, null, MOD_BASE, null);
        cw.visitField(Opcodes.ACC_PUBLIC, "keybinds", "Ljava/util/Map;", null, null);
        cw.visitField(Opcodes.ACC_PUBLIC, "favorites", "Ljava/util/Set;", null, null);
        cw.visitField(Opcodes.ACC_PRIVATE, "gameList", "Ljava/util/List;", null, null);

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, MOD_BASE, "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitTypeInsn(Opcodes.NEW, "java/util/HashMap");
        init.visitInsn(Opcodes.DUP);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);
        init.visitFieldInsn(Opcodes.PUTFIELD, QP_CLS, "keybinds", "Ljava/util/Map;");
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitTypeInsn(Opcodes.NEW, "java/util/HashSet");
        init.visitInsn(Opcodes.DUP);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashSet", "<init>", "()V", false);
        init.visitFieldInsn(Opcodes.PUTFIELD, QP_CLS, "favorites", "Ljava/util/Set;");
        init.visitLdcInsn("quickplayUIKeybind");
        init.visitInsn(Opcodes.POP);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(3, 1);
        init.visitEnd();

        // Simulate Lunar's load(JsonObject) restoring qpKeyBinds & qpFavorites directly via Map/Set.
        MethodVisitor load = cw.visitMethod(Opcodes.ACC_PUBLIC, "load", "(Lcom/google/gson/JsonObject;)V", null, null);
        load.visitCode();
        load.visitVarInsn(Opcodes.ALOAD, 0);
        load.visitVarInsn(Opcodes.ALOAD, 1);
        load.visitMethodInsn(Opcodes.INVOKESTATIC, QuickplayLoadHelper.class.getName().replace('.', '/'),
                "apply", "(Ljava/lang/Object;Lcom/google/gson/JsonObject;)V", false);
        load.visitInsn(Opcodes.RETURN);
        load.visitMaxs(2, 2);
        load.visitEnd();

        // Simulate Lunar's broken save(JsonObject) skipping qpKeyBinds & qpFavorites when gameList is empty.
        MethodVisitor save = cw.visitMethod(Opcodes.ACC_PUBLIC, "save", "(Lcom/google/gson/JsonObject;)V", null, null);
        save.visitCode();
        Label skipSave = new Label();
        save.visitVarInsn(Opcodes.ALOAD, 0);
        save.visitFieldInsn(Opcodes.GETFIELD, QP_CLS, "gameList", "Ljava/util/List;");
        save.visitJumpInsn(Opcodes.IFNULL, skipSave);
        save.visitLdcInsn("qpKeyBinds");
        save.visitLdcInsn("qpFavorites");
        save.visitInsn(Opcodes.POP2);
        save.visitLabel(skipSave);
        save.visitInsn(Opcodes.RETURN);
        save.visitMaxs(2, 2);
        save.visitEnd();

        // Helper matching Option.persistOption(JsonObject) so QuickplayTransform discovers the save method.
        MethodVisitor writeBind = cw.visitMethod(Opcodes.ACC_PRIVATE, "writeBind",
                "(L" + OPTION_CLS + ";Lcom/google/gson/JsonObject;)V", null, null);
        writeBind.visitCode();
        writeBind.visitVarInsn(Opcodes.ALOAD, 1);
        writeBind.visitVarInsn(Opcodes.ALOAD, 2);
        writeBind.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OPTION_CLS, "persistOption",
                "(Lcom/google/gson/JsonObject;)V", false);
        writeBind.visitInsn(Opcodes.RETURN);
        writeBind.visitMaxs(2, 3);
        writeBind.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildSettingsManagerBytes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, SM_CLS, null, "java/lang/Object", null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitLdcInsn("controls");
        init.visitLdcInsn("general");
        init.visitLdcInsn("performance");
        init.visitInsn(Opcodes.POP2);
        init.visitInsn(Opcodes.POP);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(3, 1);
        init.visitEnd();

        MethodVisitor checkDirty = cw.visitMethod(Opcodes.ACC_PUBLIC, "checkDirty",
                "(L" + MOD_BASE + ";L" + OPTION_CLS + ";L" + CONFIG_CLS + ";)Z", null, null);
        checkDirty.visitCode();
        Label notDirect = new Label();
        checkDirty.visitInsn(Opcodes.ICONST_0);
        checkDirty.visitJumpInsn(Opcodes.IFEQ, notDirect);
        checkDirty.visitVarInsn(Opcodes.ALOAD, 3);
        checkDirty.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CONFIG_CLS, "markDirty", "()V", false);
        checkDirty.visitInsn(Opcodes.ICONST_1);
        checkDirty.visitInsn(Opcodes.IRETURN);
        checkDirty.visitLabel(notDirect);
        // Recursive self-call signature check (children branch in real SettingsManager)
        checkDirty.visitInsn(Opcodes.ICONST_0);
        Label done = new Label();
        checkDirty.visitJumpInsn(Opcodes.IFEQ, done);
        checkDirty.visitVarInsn(Opcodes.ALOAD, 0);
        checkDirty.visitVarInsn(Opcodes.ALOAD, 1);
        checkDirty.visitVarInsn(Opcodes.ALOAD, 2);
        checkDirty.visitVarInsn(Opcodes.ALOAD, 3);
        checkDirty.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SM_CLS, "checkDirty",
                "(L" + MOD_BASE + ";L" + OPTION_CLS + ";L" + CONFIG_CLS + ";)Z", false);
        checkDirty.visitInsn(Opcodes.IRETURN);
        checkDirty.visitLabel(done);
        checkDirty.visitInsn(Opcodes.ICONST_0);
        checkDirty.visitInsn(Opcodes.IRETURN);
        checkDirty.visitMaxs(4, 4);
        checkDirty.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    public static final class QuickplayLoadHelper {
        @SuppressWarnings("unchecked")
        public static void apply(Object qp, JsonObject json) throws Exception {
            Map<String, Object> map = (Map<String, Object>) qp.getClass().getDeclaredField("keybinds").get(qp);
            Set<String> favs = (Set<String>) qp.getClass().getDeclaredField("favorites").get(qp);
            Class<?> optCls = qp.getClass().getClassLoader().loadClass(OPTION_CLS.replace('/', '.'));
            if (json.has("qpKeyBinds")) {
                for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("qpKeyBinds").entrySet()) {
                    Object opt = optCls.getDeclaredConstructor(String.class).newInstance("KEY_NONE");
                    optCls.getDeclaredMethod("load", JsonObject.class).invoke(opt, e.getValue().getAsJsonObject());
                    map.put(e.getKey(), opt);
                }
            }
            if (json.has("qpFavorites")) {
                JsonArray arr = json.getAsJsonArray("qpFavorites");
                for (JsonElement el : arr) {
                    favs.add(el.getAsString());
                }
            }
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
