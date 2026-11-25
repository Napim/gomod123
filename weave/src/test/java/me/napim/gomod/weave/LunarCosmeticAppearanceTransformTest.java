package me.napim.gomod.weave;

import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;

/**
 * Tests structural discovery of Lunar's deferred ModelDraw classes and their factory
 * capture hooks across obfuscation updates, including multi-interface player/cosmetic callsites.
 */
public final class LunarCosmeticAppearanceTransformTest {
    private static final String HELPER = "me/napim/gomod/features/LunarCosmeticAppearanceRenderer";
    private static final File ACTIVE_LUNAR_JAR =
            new File("C:/Users/admin0/.lunarclient/offline/multiver/lunar.jar");

    @Test
    public void structuralDeferredDrawAndFactoryHooksMatchArbitraryObfuscatedNames() {
        String pkg = "com/moonsworth/lunar/client/RANDOM_DEFERRED/";
        String matrix = "com/moonsworth/lunar/RANDOM_CTX/MatrixCtx";
        String def = pkg + "Def";
        String cosmetic = pkg + "EquippedCosmetic";
        String compiledModel = pkg + "CompiledModel";
        String entityPlayer = "com/moonsworth/lunar/RANDOM_BRIDGE/EntityPlayerBridge";
        String abstractPlayer = "com/moonsworth/lunar/RANDOM_BRIDGE/AbstractPlayerBridge";
        String modelDraw = pkg + "ObfuscatedModelDraw";
        String factory = pkg + "ObfuscatedCosmeticRenderer";
        String previewFactory = pkg + "ObfuscatedPreviewFactory";

        Map<String, byte[]> classes = new HashMap<>();
        classes.put(matrix, buildMatrixContext(matrix));
        classes.put(def, buildDefinition(def));
        classes.put(cosmetic, buildEquippedCosmetic(cosmetic, def));
        classes.put(compiledModel, buildCompiledModel(compiledModel, def));
        classes.put(entityPlayer, buildEntityPlayerBridge(entityPlayer));
        classes.put(abstractPlayer, buildAbstractPlayerBridge(abstractPlayer, entityPlayer));
        classes.put(modelDraw, buildModelDraw(modelDraw, matrix, def));
        classes.put(factory, buildFactory(factory, modelDraw, abstractPlayer, entityPlayer, cosmetic, compiledModel));
        classes.put(previewFactory, buildPreviewFactory(previewFactory, modelDraw, entityPlayer, cosmetic));

        ClassLoader loader = new MemoryClassLoader(classes);
        LunarCosmeticAppearanceTransform transformer = new LunarCosmeticAppearanceTransform(new File("nonexistent.jar"));

        byte[] transformedDraw = transformer.apply(modelDraw, classes.get(modelDraw), loader);
        assertNotNull("Arbitrarily obfuscated ModelDraw class must transform structurally", transformedDraw);
        ClassNode drawNode = parseExpanded(transformedDraw);
        assertEquals(1, countCalls(drawNode, HELPER, "beginCurrentDeferredDraw"));
        assertTrue(countCalls(drawNode, HELPER, "end") >= 2);
        assertNull("ModelDraw transform must be idempotent", transformer.apply(modelDraw, transformedDraw, loader));

        byte[] transformedFactory = transformer.apply(factory, classes.get(factory), loader);
        assertNotNull("Multi-player/multi-cosmetic factory must transform structurally", transformedFactory);
        ClassNode factoryNode = parseExpanded(transformedFactory);
        List<MethodInsnNode> remembers = findCalls(factoryNode, HELPER, "rememberDraw");
        assertEquals("Factory must capture ModelDraw construction", 1, remembers.size());
        // Verify ALOAD 2 (entityPlayer) and ALOAD 3 (equippedCosmetic) precede rememberDraw.
        MethodInsnNode call = remembers.get(0);
        AbstractInsnNode playerLoad = previousReal(call);
        AbstractInsnNode cosmeticLoad = previousReal(playerLoad);
        assertTrue(playerLoad instanceof VarInsnNode && ((VarInsnNode) playerLoad).var == 2);
        assertTrue(cosmeticLoad instanceof VarInsnNode && ((VarInsnNode) cosmeticLoad).var == 3);
        assertNull("Factory transform must be idempotent", transformer.apply(factory, transformedFactory, loader));

        byte[] transformedPreview = transformer.apply(previewFactory, classes.get(previewFactory), loader);
        assertNotNull("Preview factory must transform structurally", transformedPreview);
        ClassNode previewNode = parseExpanded(transformedPreview);
        assertEquals(1, countCalls(previewNode, HELPER, "rememberPreviewDraw"));
        assertNull("Preview factory transform must be idempotent", transformer.apply(previewFactory, transformedPreview, loader));
    }

    @Test
    public void ambiguousMultiPlayerOrMultiCosmeticFactoryFailsClosed() {
        String pkg = "com/moonsworth/lunar/client/RANDOM_AMBIG/";
        String matrix = "com/moonsworth/lunar/RANDOM_AMBIG_CTX/MatrixCtx";
        String def = pkg + "Def";
        String cosmetic = pkg + "EquippedCosmetic";
        String entityPlayer = "com/moonsworth/lunar/RANDOM_AMBIG_BRIDGE/EntityPlayerBridge";
        String modelDraw = pkg + "ModelDraw";
        String ambigPlayers = pkg + "AmbigPlayersFactory";
        String ambigCosmetics = pkg + "AmbigCosmeticsFactory";

        Map<String, byte[]> classes = new HashMap<>();
        classes.put(matrix, buildMatrixContext(matrix));
        classes.put(def, buildDefinition(def));
        classes.put(cosmetic, buildEquippedCosmetic(cosmetic, def));
        classes.put(entityPlayer, buildEntityPlayerBridge(entityPlayer));
        classes.put(modelDraw, buildModelDraw(modelDraw, matrix, def));
        classes.put(ambigPlayers, buildFactory(ambigPlayers, modelDraw, entityPlayer, entityPlayer, cosmetic, null));
        classes.put(ambigCosmetics, buildFactory(ambigCosmetics, modelDraw, null, entityPlayer, cosmetic, cosmetic));

        ClassLoader loader = new MemoryClassLoader(classes);
        LunarCosmeticAppearanceTransform transformer = new LunarCosmeticAppearanceTransform(new File("nonexistent.jar"));

        byte[] resPlayers = transformer.apply(ambigPlayers, classes.get(ambigPlayers), loader);
        if (resPlayers != null) {
            assertEquals("Ambiguous dual direct players must fail closed",
                    0, countCalls(parseExpanded(resPlayers), HELPER, "rememberDraw"));
        }
        byte[] resCosmetics = transformer.apply(ambigCosmetics, classes.get(ambigCosmetics), loader);
        if (resCosmetics != null) {
            assertEquals("Ambiguous dual equipped cosmetics must fail closed",
                    0, countCalls(parseExpanded(resCosmetics), HELPER, "rememberDraw"));
        }
    }

    @Test
    public void realLunarJarStructuralDeferredDrawHooksTransformCleanly() throws Exception {
        Assume.assumeTrue("Active lunar.jar required", ACTIVE_LUNAR_JAR.isFile());
        LunarCosmeticAppearanceTransform transformer = new LunarCosmeticAppearanceTransform(ACTIVE_LUNAR_JAR);
        ClassLoader loader = LunarCosmeticAppearanceTransformTest.class.getClassLoader();

        int deferredDrawsTotal = 0;
        int deferredCapturesTotal = 0;
        int previewCapturesTotal = 0;
        int multiHatWithRemember = 0;
        List<String> drawClasses = new ArrayList<>();
        List<String> captureClasses = new ArrayList<>();

        try (ZipFile zip = new ZipFile(ACTIVE_LUNAR_JAR)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (!entryName.endsWith(".class") || !entryName.startsWith("com/moonsworth/lunar/client/")) continue;
                String className = entryName.substring(0, entryName.length() - ".class".length());
                byte[] original = readAll(zip.getInputStream(entry));
                byte[] patched = transformer.apply(className, original, loader);
                if (patched == null) continue;

                // Validate stack map frames via EXPAND_FRAMES.
                ClassNode node = parseExpanded(patched);
                int draws = countCalls(node, HELPER, "beginCurrentDeferredDraw")
                        + countCalls(node, HELPER, "beginDeferredDraw");
                int remembers = countCalls(node, HELPER, "rememberDraw");
                int previewRemembers = countCalls(node, HELPER, "rememberPreviewDraw");
                int multiHat = countCalls(node, HELPER, "wrapMultiSameTypeHatConsumer");
                if (multiHat > 0 && remembers > 0) multiHatWithRemember++;
                if (draws > 0) {
                    deferredDrawsTotal += draws;
                    drawClasses.add(className);
                }
                if (remembers > 0 || previewRemembers > 0) {
                    deferredCapturesTotal += remembers;
                    previewCapturesTotal += previewRemembers;
                    captureClasses.add(className + "(r=" + remembers + ",p=" + previewRemembers + ")");
                }

                // Verify second application does not duplicate deferred hooks.
                byte[] second = transformer.apply(className, patched, loader);
                if (second != null) {
                    ClassNode secondNode = parseExpanded(second);
                    assertEquals("Deferred draw hooks must not duplicate on " + className,
                            draws, countCalls(secondNode, HELPER, "beginCurrentDeferredDraw")
                                    + countCalls(secondNode, HELPER, "beginDeferredDraw"));
                    assertEquals("rememberDraw hooks must not duplicate on " + className,
                            remembers, countCalls(secondNode, HELPER, "rememberDraw"));
                    assertEquals("rememberPreviewDraw hooks must not duplicate on " + className,
                            previewRemembers, countCalls(secondNode, HELPER, "rememberPreviewDraw"));
                }
            }
        }

        assertEquals("Expected all 3 ModelDraw classes in lunar.jar to be discovered structurally: " + drawClasses,
                3, deferredDrawsTotal);
        assertTrue("Expected multi-hat cosmetic renderer to capture rememberDraw (" + captureClasses + ")",
                multiHatWithRemember >= 1);
        assertTrue("Expected all 8+ deferred factory captures across lunar.jar, got " + deferredCapturesTotal + ": " + captureClasses,
                deferredCapturesTotal >= 8);
        assertTrue("Expected at least 1 preview factory capture in lunar.jar, got " + previewCapturesTotal,
                previewCapturesTotal >= 1);
    }

    private static ClassNode parseExpanded(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }

    private static int countCalls(ClassNode node, String owner, String name) {
        return findCalls(node, owner, name).size();
    }

    private static List<MethodInsnNode> findCalls(ClassNode node, String owner, String name) {
        List<MethodInsnNode> found = new ArrayList<>();
        for (MethodNode m : node.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode) {
                    MethodInsnNode c = (MethodInsnNode) insn;
                    if (owner.equals(c.owner) && name.equals(c.name)) found.add(c);
                }
            }
        }
        return found;
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
        AbstractInsnNode cur = insn == null ? null : insn.getPrevious();
        while (cur != null && cur.getOpcode() < 0) cur = cur.getPrevious();
        return cur;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = stream.read(buf)) >= 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static byte[] buildMatrixContext(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        for (String[] m : new String[][]{{"push", "()V"}, {"pop", "()V"}, {"scale", "(FFF)V"}, {"translate", "(DDD)V"}}) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, m[0], m[1], null, null);
            mv.visitCode(); mv.visitInsn(Opcodes.RETURN); mv.visitMaxs(0, 0); mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildDefinition(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        String desc = "(JLjava/lang/String;Ljava/lang/Object;Ljava/lang/Enum;ZJLjava/time/Instant;"
                + "Ljava/util/List;Ljava/util/List;ZLjava/lang/Object;Ljava/lang/Object;Z)V";
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildEquippedCosmetic(String name, String def) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "def", "L" + def + ";", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "owner", "Ljava/util/UUID;", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildCompiledModel(String name, String def) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "def", "L" + def + ";", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildEntityPlayerBridge(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, name, null, "java/lang/Object", null);
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "bridge$getUniqueID", "()Ljava/util/UUID;", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildAbstractPlayerBridge(String name, String entityPlayer) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                name, null, "java/lang/Object", new String[]{entityPlayer});
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildModelDraw(String name, String matrix, String def) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "entry", "L" + def + ";", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(L" + def + ";)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor render = cw.visitMethod(Opcodes.ACC_PUBLIC, "render", "(L" + matrix + ";ZI)V", null, null);
        render.visitCode();
        render.visitInsn(Opcodes.RETURN);
        render.visitMaxs(0, 0);
        render.visitEnd();

        MethodVisitor translucent = cw.visitMethod(Opcodes.ACC_PUBLIC, "translucent", "()Z", null, null);
        translucent.visitCode();
        translucent.visitInsn(Opcodes.ICONST_0);
        translucent.visitInsn(Opcodes.IRETURN);
        translucent.visitMaxs(0, 0);
        translucent.visitEnd();

        MethodVisitor hl = cw.visitMethod(Opcodes.ACC_PUBLIC, "highlightEntry", "()L" + def + ";", null, null);
        hl.visitCode();
        hl.visitVarInsn(Opcodes.ALOAD, 0);
        hl.visitFieldInsn(Opcodes.GETFIELD, name, "entry", "L" + def + ";");
        hl.visitInsn(Opcodes.ARETURN);
        hl.visitMaxs(0, 0);
        hl.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildFactory(String name, String modelDraw, String p1, String p2, String c1, String c2) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        StringBuilder desc = new StringBuilder("(");
        if (p1 != null) desc.append("L").append(p1).append(";");
        desc.append("L").append(p2).append(";");
        desc.append("L").append(c1).append(";");
        if (c2 != null) desc.append("L").append(c2).append(";");
        desc.append(")V");
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "submit", desc.toString(), null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, modelDraw);
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, modelDraw, "<init>",
                "(L" + c1.substring(0, c1.lastIndexOf('/') + 1) + "Def;)V", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildPreviewFactory(String name, String modelDraw, String entityPlayer, String cosmetic) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", new String[]{"java/lang/AutoCloseable"});
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "cosmetic", "L" + cosmetic + ";", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(L" + cosmetic + ";)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "create", "(L" + entityPlayer + ";)V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, modelDraw);
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, modelDraw, "<init>",
                "(L" + cosmetic.substring(0, cosmetic.lastIndexOf('/') + 1) + "Def;)V", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        MethodVisitor close = cw.visitMethod(Opcodes.ACC_PUBLIC, "close", "()V", null, null);
        close.visitCode(); close.visitInsn(Opcodes.RETURN); close.visitMaxs(0, 0); close.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static final class MemoryClassLoader extends ClassLoader {
        private final Map<String, byte[]> classes;
        MemoryClassLoader(Map<String, byte[]> classes) {
            super(LunarCosmeticAppearanceTransformTest.class.getClassLoader());
            this.classes = classes;
        }
        @Override
        public InputStream getResourceAsStream(String name) {
            if (name != null && name.endsWith(".class")) {
                byte[] bytes = classes.get(name.substring(0, name.length() - ".class".length()));
                if (bytes != null) return new ByteArrayInputStream(bytes);
            }
            return super.getResourceAsStream(name);
        }
    }
}
