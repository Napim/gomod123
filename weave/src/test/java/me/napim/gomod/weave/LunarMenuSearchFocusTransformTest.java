package me.napim.gomod.weave;

import me.napim.gomod.features.LunarJavaMenuFocus;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Enumeration;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class LunarMenuSearchFocusTransformTest {

    @Test
    public void transformsSyntheticArbitraryMenuClassAndIsIdempotent() {
        String page = "com/moonsworth/lunar/client/synth/ModPage";
        String text = "com/moonsworth/lunar/client/synth/SearchInput";
        String key = "com/moonsworth/lunar/synth/KeyArg";
        byte[] orig = syntheticPageBytes(page, text, key, false);

        assertTrue(LunarMenuSearchFocusTransform.mayContainCandidate(page, orig));
        byte[] patched = LunarMenuSearchFocusTransform.transform(page, orig);
        assertNotNull("Expected structural match on arbitrary obfuscated names", patched);

        ClassNode node = new ClassNode();
        new ClassReader(patched).accept(node, ClassReader.EXPAND_FRAMES);
        int hooks = countFocusHooks(node);
        assertEquals(1, hooks);

        assertNull("Expected idempotent second pass to return null",
                LunarMenuSearchFocusTransform.transform(page, patched));
    }

    @Test
    public void failsClosedOnAmbiguousFallbackMatches() {
        String page = "com/moonsworth/lunar/client/synth/AmbiguousPage";
        String text = "com/moonsworth/lunar/client/synth/SearchInput";
        String key = "com/moonsworth/lunar/synth/KeyArg";
        byte[] orig = syntheticPageBytes(page, text, key, true);

        assertNull("Ambiguous fallback must fail closed",
                LunarMenuSearchFocusTransform.transform(page, orig));
    }

    @Test
    public void javaMenuFocusDetectsArbitraryLunarWrapperBridgeMethod() throws Exception {
        String wrapperInternal = "com/moonsworth/lunar/legacy/wrapper/synth/RemappedScreen";
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, wrapperInternal, null, "java/lang/Object", null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        MethodVisitor focus = cw.visitMethod(Opcodes.ACC_PUBLIC, "bridge$hasTextFieldFocused", "()Z", null, null);
        focus.visitCode();
        focus.visitInsn(Opcodes.ICONST_1);
        focus.visitInsn(Opcodes.IRETURN);
        focus.visitMaxs(1, 1);
        focus.visitEnd();
        cw.visitEnd();

        final byte[] bytes = cw.toByteArray();
        Class<?> cls = new ClassLoader(LunarMenuSearchFocusTransformTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass(wrapperInternal.replace('/', '.'), bytes, 0, bytes.length);
            }
        }.define();
        Object instance = cls.getConstructor().newInstance();

        Field accField = LunarJavaMenuFocus.class.getDeclaredField("ACCESSORS");
        accField.setAccessible(true);
        ClassValue<?> cv = (ClassValue<?>) accField.get(null);
        Object optObj = cv.get(cls);
        assertTrue(optObj instanceof Optional && ((Optional<?>) optObj).isPresent());
        Method m = (Method) ((Optional<?>) optObj).get();
        assertEquals(Boolean.TRUE, m.invoke(instance));

        Object nonLunarOpt = cv.get(String.class);
        assertFalse(((Optional<?>) nonLunarOpt).isPresent());
    }

    @Test
    public void transformsActiveLunarJarModsAndStaffPages() throws Exception {
        File lunarJar = new File("C:/Users/admin0/.lunarclient/offline/multiver/lunar.jar");
        if (!lunarJar.isFile()) {
            return;
        }
        int matched = 0;
        try (ZipFile zip = new ZipFile(lunarJar)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (!entryName.endsWith(".class") || !entryName.startsWith("com/moonsworth/lunar/client/")) {
                    continue;
                }
                String internalName = entryName.substring(0, entryName.length() - 6);
                byte[] orig = readAll(zip.getInputStream(entry));
                if (!LunarMenuSearchFocusTransform.mayContainCandidate(internalName, orig)) {
                    continue;
                }
                byte[] patched = LunarMenuSearchFocusTransform.transform(internalName, orig);
                if (patched == null) {
                    continue;
                }
                ClassNode node = new ClassNode();
                new ClassReader(patched).accept(node, ClassReader.EXPAND_FRAMES);
                assertEquals(1, countFocusHooks(node));
                assertNull("Expected idempotent second pass for " + internalName,
                        LunarMenuSearchFocusTransform.transform(internalName, patched));
                matched++;
            }
        }
        assertTrue("Expected Mods and Staff search focus classes in lunar.jar, matched=" + matched, matched >= 2);
    }

    private static int countFocusHooks(ClassNode node) {
        int count = 0;
        for (MethodNode m : node.methods) {
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (insn instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) insn;
                    if ("me/napim/gomod/features/LunarJavaMenuFocus".equals(call.owner)
                            && "shouldSkipAutomaticSearch".equals(call.name)
                            && "(Z)Z".equals(call.desc)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static byte[] syntheticPageBytes(String page, String text, String key, boolean duplicateFallback) {
        String keyMethodDesc = "(CL" + key + ";)V";
        String textDesc = "L" + text + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, page, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE, "box", textDesc, null, null).visitEnd();

        emitKeyboardMethod(cw, page, text, textDesc, "onChar", keyMethodDesc);
        if (duplicateFallback) {
            emitKeyboardMethod(cw, page, text, textDesc, "onCharExtra", keyMethodDesc);
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void emitKeyboardMethod(ClassWriter cw, String page, String text, String textDesc,
                                           String methodName, String desc) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, desc, null, null);
        mv.visitCode();
        Label skip = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, page, "box", textDesc);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, text, "isActive", "()Z", false);
        mv.visitJumpInsn(Opcodes.IFNE, skip);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, page, "box", textDesc);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, text, "focusNow", "(Z)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, page, "box", textDesc);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, text, methodName, desc, false);
        mv.visitLabel(skip);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(3, 3);
        mv.visitEnd();
    }

    private static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try (InputStream in = is) {
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
        }
        return out.toByteArray();
    }
}
