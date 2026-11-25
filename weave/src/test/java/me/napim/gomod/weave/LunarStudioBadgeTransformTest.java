package me.napim.gomod.weave;

import me.napim.gomod.features.GomodStudioSharing;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;

/**
 * Verifies structural BadgeManager, ModelContext, ModelRenderer, and Badge record discovery
 * against arbitrarily obfuscated synthetic classes and the active Lunar jar.
 */
public final class LunarStudioBadgeTransformTest {
    private static final String RENDER = "me/napim/gomod/features/LunarCosmeticAppearanceRenderer";
    private static final String SIZE = "me/napim/gomod/features/LunarCosmeticSizeRenderer";
    private static final String FIT_APPEARANCE = "me/napim/gomod/features/LunarFitAppearance";
    private static final File ACTIVE_LUNAR_JAR =
            new File("C:/Users/admin0/.lunarclient/offline/multiver/lunar.jar");

    @Test
    public void structuralBadgeManagerHookMatchesArbitraryNamesAndFailsClosedOnAmbiguity() {
        String mgr = "com/moonsworth/lunar/client/RANDOM_PKG/RANDOM_BADGE_MANAGER";
        String badge = "com/moonsworth/lunar/client/RANDOM_BADGE/RANDOM_BADGE";
        String res = "com/moonsworth/lunar/RANDOM_RES";
        byte[] raw = syntheticBadgeManager(mgr, badge, res, false);
        byte[] patched = LunarStudioTransform.apply(mgr, raw);
        assertNotNull("Structural BadgeManager hook must succeed for arbitrary obfuscated names", patched);

        ClassNode parsed = parseExpanded(patched);
        assertEquals(2, countCalls(parsed, RENDER, "beginBadge"));
        assertEquals(4, countCalls(parsed, RENDER, "end"));
        assertNull("Transform must be idempotent", LunarStudioTransform.apply(mgr, patched));

        byte[] ambiguous = syntheticBadgeManager(mgr, badge, res, true);
        assertNull("Ambiguous third 8-arg renderer must fail closed", LunarStudioTransform.apply(mgr, ambiguous));
    }

    @Test
    public void structuralModelContextAndRendererMatchArbitraryNames() {
        String ctx = "com/moonsworth/lunar/RANDOM_CTX/RANDOM_MATRIX";
        byte[] rawCtx = syntheticModelContext(ctx, false);
        byte[] patchedCtx = LunarStudioTransform.apply(ctx, rawCtx);
        assertNotNull("Structural ModelContext hook must succeed", patchedCtx);
        ClassNode parsedCtx = parseExpanded(patchedCtx);
        assertEquals(1, countCalls(parsedCtx, SIZE, "begin"));
        assertEquals(2, countCalls(parsedCtx, SIZE, "end"));
        assertNull("ModelContext transform must be idempotent", LunarStudioTransform.apply(ctx, patchedCtx));
        assertNull("Ambiguous ModelContext must fail closed",
                LunarStudioTransform.apply(ctx, syntheticModelContext(ctx, true)));

        String renderer = "com/moonsworth/lunar/client/ui/osr/RANDOM_PKG/RANDOM_MODEL_RENDERER";
        String playerModel = "com/moonsworth/lunar/client/ui/osr/RANDOM_PKG/RANDOM_PLAYER_MODEL";
        byte[] rawRenderer = syntheticModelRenderer(renderer, playerModel, false);
        byte[] patchedRenderer = LunarStudioTransform.apply(renderer, rawRenderer);
        assertNotNull("Structural ModelRenderer hook must succeed", patchedRenderer);
        ClassNode parsedRenderer = parseExpanded(patchedRenderer);
        assertEquals(1, countCalls(parsedRenderer, FIT_APPEARANCE, "beginModel"));
        assertEquals(2, countCalls(parsedRenderer, FIT_APPEARANCE, "endModel"));
        assertNull("ModelRenderer transform must be idempotent",
                LunarStudioTransform.apply(renderer, patchedRenderer));
        assertNull("Ambiguous ModelRenderer must fail closed",
                LunarStudioTransform.apply(renderer, syntheticModelRenderer(renderer, playerModel, true)));
    }

    @Test
    public void studioSharingBindsArbitrarilyNamedNativeBadgeRecordStructurally() throws Exception {
        String resPkg = "com.moonsworth.lunar.client.RANDOM_SYNTH.ResObj";
        String badgePkg = "com.moonsworth.lunar.client.RANDOM_SYNTH.BadgeRec";
        String profilePkg = "com.moonsworth.lunar.client.RANDOM_SYNTH.ProfileRec";

        byte[] resBytes = compileSimpleClass(resPkg.replace('.', '/'));
        byte[] badgeBytes = syntheticBadgeRecordClass(badgePkg.replace('.', '/'), resPkg.replace('.', '/'));
        byte[] profileBytes = syntheticProfileRecordClass(profilePkg.replace('.', '/'), badgePkg.replace('.', '/'));

        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes;
                if (resPkg.equals(name)) bytes = resBytes;
                else if (badgePkg.equals(name)) bytes = badgeBytes;
                else if (profilePkg.equals(name)) bytes = profileBytes;
                else return super.findClass(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };

        Object r1 = loader.loadClass(resPkg).getDeclaredConstructor().newInstance();
        Object r2 = loader.loadClass(resPkg).getDeclaredConstructor().newInstance();
        Object catalogBadge = loader.loadClass(badgePkg)
                .getDeclaredConstructor(int.class, String.class, String.class,
                        r1.getClass(), r2.getClass(), Instant.class, boolean.class)
                .newInstance(42, "Synthetic", "Desc", r1, r2, Instant.EPOCH, false);
        Object profile = loader.loadClass(profilePkg)
                .getDeclaredConstructor(int.class, catalogBadge.getClass(), boolean.class)
                .newInstance(1, catalogBadge, true);

        UUID peerUuid = UUID.randomUUID();
        Object rebound = GomodStudioSharing.bindBadgeProfile(profile, peerUuid);
        assertNotNull(rebound);
        assertNotSame(profile, rebound);
        java.lang.reflect.Field badgeField = rebound.getClass().getDeclaredFields()[1];
        badgeField.setAccessible(true);
        Object clonedBadge = badgeField.get(rebound);
        assertNotNull(clonedBadge);
        assertNotSame(catalogBadge, clonedBadge);
        assertEquals(peerUuid, GomodStudioSharing.badgeOwner(clonedBadge));
        assertNull(GomodStudioSharing.badgeOwner(catalogBadge));
    }

    @Test
    public void structuralHooksApplyCleanlyToActiveLunarJar() throws Exception {
        Assume.assumeTrue("Active lunar.jar required for offline verification", ACTIVE_LUNAR_JAR.isFile());
        int badgeManagers = 0;
        int modelContexts = 0;
        int modelRenderers = 0;
        try (ZipFile zip = new ZipFile(ACTIVE_LUNAR_JAR)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (!entryName.endsWith(".class") || !entryName.startsWith("com/moonsworth/lunar/")) continue;
                String internalName = entryName.substring(0, entryName.length() - 6);
                byte[] bytes = readAll(zip.getInputStream(entry));
                byte[] transformed = LunarStudioTransform.apply(internalName, bytes);
                if (transformed == null) continue;
                ClassNode parsed = parseExpanded(transformed);
                int badges = countCalls(parsed, RENDER, "beginBadge");
                int sizes = countCalls(parsed, SIZE, "begin");
                int models = countCalls(parsed, FIT_APPEARANCE, "beginModel");
                if (badges > 0) {
                    assertEquals("Expected 2 BadgeManager render hooks", 2, badges);
                    badgeManagers++;
                }
                if (sizes > 0) {
                    assertEquals("Expected 1 ModelContext size hook", 1, sizes);
                    modelContexts++;
                }
                if (models > 0) {
                    assertEquals("Expected 1 ModelRenderer fit-model hook", 1, models);
                    modelRenderers++;
                }
                assertNull("Transform must be idempotent on " + internalName,
                        LunarStudioTransform.apply(internalName, transformed));
            }
        }
        assertEquals("Expected unique BadgeManager in active lunar.jar", 1, badgeManagers);
        assertEquals("Expected unique ModelContext in active lunar.jar", 1, modelContexts);
        assertEquals("Expected unique ModelRenderer in active lunar.jar", 1, modelRenderers);
    }

    private static ClassNode parseExpanded(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }

    private static int countCalls(ClassNode node, String owner, String name) {
        int count = 0;
        for (MethodNode m : node.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) insn;
                    if (owner.equals(call.owner) && name.equals(call.name)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static byte[] syntheticBadgeManager(String mgr, String badge, String res, boolean extraOverload) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, mgr, null, "java/lang/Object", null);
        MethodVisitor parse = cw.visitMethod(Opcodes.ACC_PUBLIC, "load", "()V", null, null);
        parse.visitCode();
        parse.visitLdcInsn("badges.json");
        parse.visitInsn(Opcodes.POP);
        parse.visitLdcInsn("Failed to parse badge:");
        parse.visitInsn(Opcodes.POP);
        parse.visitTypeInsn(Opcodes.NEW, badge);
        parse.visitInsn(Opcodes.DUP);
        parse.visitInsn(Opcodes.ICONST_1);
        parse.visitLdcInsn("name");
        parse.visitLdcInsn("desc");
        parse.visitInsn(Opcodes.ACONST_NULL);
        parse.visitInsn(Opcodes.ACONST_NULL);
        parse.visitInsn(Opcodes.ACONST_NULL);
        parse.visitInsn(Opcodes.ICONST_0);
        parse.visitMethodInsn(Opcodes.INVOKESPECIAL, badge, "<init>",
                "(ILjava/lang/String;Ljava/lang/String;L" + res + ";L" + res + ";Ljava/time/Instant;Z)V", false);
        parse.visitInsn(Opcodes.POP);
        parse.visitInsn(Opcodes.RETURN);
        parse.visitMaxs(0, 0);
        parse.visitEnd();

        String d1 = "(Ljava/lang/Object;L" + badge + ";FFFFILjava/lang/Object;)V";
        String d2 = "(Ljava/lang/String;L" + badge + ";FFFFILjava/lang/Object;)V";
        List<String> descs = new ArrayList<>();
        descs.add(d1);
        descs.add(d2);
        if (extraOverload) {
            descs.add("(Ljava/lang/Integer;L" + badge + ";FFFFILjava/lang/Object;)V");
        }
        for (int i = 0; i < descs.size(); i++) {
            MethodVisitor draw = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "r" + i, descs.get(i), null, null);
            draw.visitCode();
            draw.visitInsn(Opcodes.RETURN);
            draw.visitMaxs(0, 0);
            draw.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] syntheticModelContext(String ctx, boolean extraConsumerMethod) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, ctx, null, "java/lang/Object", null);
        for (String[] sig : new String[][] {
                {"push", "()V"},
                {"pop", "()V"},
                {"scale", "(FFF)V"},
                {"drawWith", "(Ljava/util/function/Consumer;Ljava/util/function/Consumer;)V"}
        }) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, sig[0], sig[1], null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        if (extraConsumerMethod) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "drawWith2",
                    "(Ljava/util/function/Consumer;Ljava/util/function/Consumer;)V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] syntheticModelRenderer(String renderer, String playerModel, boolean extraRender) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, renderer, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "playerViewX", "F", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "playerViewY", "F", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "render",
                "(Ljava/lang/Object;L" + playerModel + ";Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        if (extraRender) {
            MethodVisitor mv2 = cw.visitMethod(Opcodes.ACC_PUBLIC, "renderAlt",
                    "(Ljava/lang/Object;L" + playerModel + ";Ljava/lang/String;)V", null, null);
            mv2.visitCode();
            mv2.visitInsn(Opcodes.RETURN);
            mv2.visitMaxs(0, 0);
            mv2.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] compileSimpleClass(String internalName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] syntheticBadgeRecordClass(String internalName, String resName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "id", "I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "name", "Ljava/lang/String;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "desc", "Ljava/lang/String;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "r1", "L" + resName + ";", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "r2", "L" + resName + ";", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "inst", "Ljava/time/Instant;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "flag", "Z", null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "(ILjava/lang/String;Ljava/lang/String;L" + resName + ";L" + resName + ";Ljava/time/Instant;Z)V",
                null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        String[] fields = {"id", "name", "desc", "r1", "r2", "inst", "flag"};
        String[] descs = {"I", "Ljava/lang/String;", "Ljava/lang/String;", "L" + resName + ";", "L" + resName + ";", "Ljava/time/Instant;", "Z"};
        int[] ops = {Opcodes.ILOAD, Opcodes.ALOAD, Opcodes.ALOAD, Opcodes.ALOAD, Opcodes.ALOAD, Opcodes.ALOAD, Opcodes.ILOAD};
        for (int i = 0; i < fields.length; i++) {
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitVarInsn(ops[i], i + 1);
            init.visitFieldInsn(Opcodes.PUTFIELD, internalName, fields[i], descs[i]);
        }
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor idGetter = cw.visitMethod(Opcodes.ACC_PUBLIC, "id", "()I", null, null);
        idGetter.visitCode();
        idGetter.visitVarInsn(Opcodes.ALOAD, 0);
        idGetter.visitFieldInsn(Opcodes.GETFIELD, internalName, "id", "I");
        idGetter.visitInsn(Opcodes.IRETURN);
        idGetter.visitMaxs(0, 0);
        idGetter.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] syntheticProfileRecordClass(String internalName, String badgeName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "lvl", "I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "badge", "L" + badgeName + ";", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "lp", "Z", null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "(IL" + badgeName + ";Z)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ILOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, internalName, "lvl", "I");
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 2);
        init.visitFieldInsn(Opcodes.PUTFIELD, internalName, "badge", "L" + badgeName + ";");
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ILOAD, 3);
        init.visitFieldInsn(Opcodes.PUTFIELD, internalName, "lp", "Z");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
