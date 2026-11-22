package me.napim.gomod.features;

import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class LunarCosmeticTextureResolutionTest {
    private static final String BASE = "com/moonsworth/lunar/client/testres/";
    private static final String RES_BRIDGE_INTERNAL = BASE + "ResBridge";
    private static final String RES_BRIDGE_DESC = "L" + RES_BRIDGE_INTERNAL + ";";
    private static final String SYNTH_RES_INTERNAL = BASE + "SynthResource";
    private static final String DEF_INTERNAL = BASE + "Def";
    private static final String GEK_DEF_INTERNAL = BASE + "GekDef";
    private static final String GEK_MODEL_INTERNAL = BASE + "GekModel";
    private static final String WRAPPED_INTERNAL = BASE + "Equipped";
    private static final String CAT_INTERNAL = BASE + "Category";
    private static final String MODEL_DATA_INTERNAL = BASE + "CompiledModelData";
    private static final String DATA_SUPPLIER_INTERNAL = BASE + "DataSupplier";
    private static final String MODEL_DRAW_IF_INTERNAL = BASE + "ModelDrawIf";
    private static final String DRAW1_INTERNAL = BASE + "ModelDrawCompiled";
    private static final String DRAW2_INTERNAL = BASE + "ModelDrawDirect";
    private static final String DRAW3_INTERNAL = BASE + "ModelDrawWrapped";

    @Test
    public void geckoDefinitionAndWrapperResolveModelTextureFromFieldAndGetter() throws Exception {
        BytecodeLoader loader = new BytecodeLoader();
        Class<?> resBridgeClass = loader.define(RES_BRIDGE_INTERNAL, resBridgeInterface());
        Class<?> resClass = loader.define(SYNTH_RES_INTERNAL, synthResourceClass());
        Class<?> categoryClass = loader.define(CAT_INTERNAL, enumClass(CAT_INTERNAL, "HAT"));
        loader.define(DEF_INTERNAL, cosmeticDefinitionClass());
        Class<?> gekModelClass = loader.define(GEK_MODEL_INTERNAL, geckoModelClass());
        Class<?> gekDefClass = loader.define(GEK_DEF_INTERNAL, geckoDefinitionClass());
        Class<?> equippedClass = loader.define(WRAPPED_INTERNAL, equippedWrapperClass(gekDefClass));

        Object hatEnum = Enum.valueOf(categoryClass.asSubclass(Enum.class), "HAT");
        Constructor<?> resCtor = resClass.getConstructor(String.class, String.class);
        Object gekJson = resCtor.newInstance("lunar", "cosmetics/models/gek/hats/crown/white.gek.json");
        Object webpTexture = resCtor.newInstance("lunar", "cosmetics/models/gek/hats/crown/white.webp");

        Object model = gekModelClass.getConstructor(resBridgeClass).newInstance(webpTexture);
        Constructor<?> gekCtor = gekDefClass.getConstructor(long.class, String.class, resBridgeClass,
                categoryClass, boolean.class, long.class, Instant.class, List.class, List.class,
                boolean.class, Object.class, Object.class, boolean.class, gekModelClass, Object.class);

        // Case 1: volatile field populated directly.
        Object gekWithField = gekCtor.newInstance(5117L, "Crown", gekJson, hatEnum, true, -1L, null,
                Collections.emptyList(), Collections.emptyList(), false, null, null, false, model, null);
        Object wrappedField = equippedClass.getConstructor(gekDefClass).newInstance(gekWithField);
        assertEquals(gekJson, LunarCosmeticRuntime.resource(wrappedField, null));
        assertEquals(webpTexture, LunarCosmeticRuntime.modelTextureResource(wrappedField));

        // Case 2: volatile field still null; fallback zero-arg getter supplies model from JIT handle.
        Object gekWithGetter = gekCtor.newInstance(5117L, "Crown", gekJson, hatEnum, true, -1L, null,
                Collections.emptyList(), Collections.emptyList(), false, null, null, false, null, model);
        assertEquals(webpTexture, LunarCosmeticRuntime.modelTextureResource(gekWithGetter));
    }

    @Test
    public void drawTextureResourceHandlesAllThreeModelDrawShapes() throws Exception {
        BytecodeLoader loader = new BytecodeLoader();
        Class<?> resBridgeClass = loader.define(RES_BRIDGE_INTERNAL, resBridgeInterface());
        Class<?> resClass = loader.define(SYNTH_RES_INTERNAL, synthResourceClass());
        Class<?> categoryClass = loader.define(CAT_INTERNAL, enumClass(CAT_INTERNAL, "HAT"));
        loader.define(DEF_INTERNAL, cosmeticDefinitionClass());
        Class<?> dataClass = loader.define(MODEL_DATA_INTERNAL, compiledModelDataClass());
        Class<?> supplierIfClass = loader.define(DATA_SUPPLIER_INTERNAL, dataSupplierInterface());
        loader.define(MODEL_DRAW_IF_INTERNAL, modelDrawInterface(DEF_INTERNAL));
        Class<?> draw1Class = loader.define(DRAW1_INTERNAL, modelDrawCompiledClass(supplierIfClass));
        Class<?> draw2Class = loader.define(DRAW2_INTERNAL, modelDrawDirectClass(dataClass));
        Class<?> draw3Class = loader.define(DRAW3_INTERNAL, modelDrawWrappedClass());

        Object categoryHat = Enum.valueOf(categoryClass.asSubclass(Enum.class), "HAT");
        Object gekJson = resClass.getConstructor(String.class, String.class)
                .newInstance("lunar", "cosmetics/models/gek/hats/crown/white.gek.json");
        Class<?> defClass = loader.loadClass(DEF_INTERNAL.replace('/', '.'));
        Object highlightDef = defClass.getConstructor(long.class, String.class, resBridgeClass,
                        categoryClass, boolean.class, long.class, Instant.class, List.class, List.class,
                        boolean.class, Object.class, Object.class, boolean.class)
                .newInstance(5117L, "Crown", gekJson, categoryHat, true, -1L, null,
                        Collections.emptyList(), Collections.emptyList(), false, null, null, false);

        Object compiledTex = resClass.getConstructor(String.class, String.class)
                .newInstance("lunar-jit", "cosmetics/models/gek/hats/crown/white.webp");
        Object modelData = dataClass.getConstructor(resBridgeClass).newInstance(compiledTex);
        Supplier<Object> matrixSupplier = () -> new float[16];
        Object dataSupplier = java.lang.reflect.Proxy.newProxyInstance(
                loader, new Class<?>[]{supplierIfClass}, (proxy, method, args) -> modelData);

        Object draw1 = draw1Class.getConstructor(Supplier.class, supplierIfClass).newInstance(matrixSupplier, dataSupplier);
        assertEquals(compiledTex, LunarCosmeticRuntime.drawTextureResource(draw1, resClass));

        Object draw2 = draw2Class.getConstructor(dataClass, defClass).newInstance(modelData, highlightDef);
        assertEquals(compiledTex, LunarCosmeticRuntime.drawTextureResource(draw2, resClass));

        Object draw3 = draw3Class.getConstructor(loader.loadClass(MODEL_DRAW_IF_INTERNAL.replace('/', '.'))).newInstance(draw1);
        assertEquals(compiledTex, LunarCosmeticRuntime.drawTextureResource(draw3, resClass));
    }

    @Test
    public void jitAssetPathRewritesOnlyNonDevLunarLocations() {
        assertEquals(
                "cosmetics/models/gek/hats/elemental_crown_2/textures/white.webp",
                LunarCosmeticRuntime.jitAssetPath(
                        "lunar", "cosmetics/models/gek/hats/elemental_crown_2/textures/white.webp"));
        assertNull(LunarCosmeticRuntime.jitAssetPath("lunar", "dev_cosmetics/crown/white.webp"));
        assertNull(LunarCosmeticRuntime.jitAssetPath("lunar-jit", "cosmetics/cloaks/test.webp"));
        assertNull(LunarCosmeticRuntime.jitAssetPath("lunar", null));
        assertNull(LunarCosmeticRuntime.jitAssetPath(null, "cosmetics/cloaks/test.webp"));
    }

    private static byte[] resBridgeInterface() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                RES_BRIDGE_INTERNAL, null, "java/lang/Object", null);
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "bridge$getDomain", "()Ljava/lang/String;", null, null).visitEnd();
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "bridge$getPath", "()Ljava/lang/String;", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] synthResourceClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, SYNTH_RES_INTERNAL, null, "java/lang/Object",
                new String[]{RES_BRIDGE_INTERNAL});
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "domain", "Ljava/lang/String;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "path", "Ljava/lang/String;", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, SYNTH_RES_INTERNAL, "domain", "Ljava/lang/String;");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitFieldInsn(Opcodes.PUTFIELD, SYNTH_RES_INTERNAL, "path", "Ljava/lang/String;");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 3);
        mv.visitEnd();
        addGetter(cw, SYNTH_RES_INTERNAL, "bridge$getDomain", "domain", "Ljava/lang/String;");
        addGetter(cw, SYNTH_RES_INTERNAL, "bridge$getPath", "path", "Ljava/lang/String;");
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void addGetter(ClassWriter cw, String owner, String name, String field, String desc) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, "()" + desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, field, desc);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    private static byte[] enumClass(String internalName, String constant) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM,
                internalName, null, "java/lang/Enum", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM,
                constant, "L" + internalName + ";", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                "$VALUES", "[L" + internalName + ";", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "(Ljava/lang/String;I)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitVarInsn(Opcodes.ILOAD, 2);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Enum", "<init>", "(Ljava/lang/String;I)V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(3, 3);
        init.visitEnd();
        MethodVisitor values = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "values",
                "()[L" + internalName + ";", null, null);
        values.visitCode();
        values.visitFieldInsn(Opcodes.GETSTATIC, internalName, "$VALUES", "[L" + internalName + ";");
        values.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "[L" + internalName + ";", "clone", "()Ljava/lang/Object;", false);
        values.visitTypeInsn(Opcodes.CHECKCAST, "[L" + internalName + ";");
        values.visitInsn(Opcodes.ARETURN);
        values.visitMaxs(1, 0);
        values.visitEnd();
        MethodVisitor valueOf = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "valueOf",
                "(Ljava/lang/String;)L" + internalName + ";", null, null);
        valueOf.visitCode();
        valueOf.visitLdcInsn(org.objectweb.asm.Type.getObjectType(internalName));
        valueOf.visitVarInsn(Opcodes.ALOAD, 0);
        valueOf.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Enum", "valueOf",
                "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;", false);
        valueOf.visitTypeInsn(Opcodes.CHECKCAST, internalName);
        valueOf.visitInsn(Opcodes.ARETURN);
        valueOf.visitMaxs(2, 1);
        valueOf.visitEnd();
        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, internalName);
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitLdcInsn(constant);
        clinit.visitInsn(Opcodes.ICONST_0);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, internalName, "<init>", "(Ljava/lang/String;I)V", false);
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, internalName, constant, "L" + internalName + ";");
        clinit.visitInsn(Opcodes.ICONST_1);
        clinit.visitTypeInsn(Opcodes.ANEWARRAY, internalName);
        clinit.visitInsn(Opcodes.DUP_X1);
        clinit.visitInsn(Opcodes.SWAP);
        clinit.visitInsn(Opcodes.ICONST_0);
        clinit.visitInsn(Opcodes.SWAP);
        clinit.visitInsn(Opcodes.AASTORE);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, internalName, "$VALUES", "[L" + internalName + ";");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(5, 0);
        clinit.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] cosmeticDefinitionClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, DEF_INTERNAL, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "f0", "J", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "f1", "Ljava/lang/String;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "f2", RES_BRIDGE_DESC, null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "f3", "L" + CAT_INTERNAL + ";", null, null).visitEnd();
        String ctorDesc = "(JLjava/lang/String;" + RES_BRIDGE_DESC + "L" + CAT_INTERNAL + ";ZJLjava/time/Instant;Ljava/util/List;Ljava/util/List;ZLjava/lang/Object;Ljava/lang/Object;Z)V";
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", ctorDesc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.LLOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, DEF_INTERNAL, "f0", "J");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitFieldInsn(Opcodes.PUTFIELD, DEF_INTERNAL, "f1", "Ljava/lang/String;");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitFieldInsn(Opcodes.PUTFIELD, DEF_INTERNAL, "f2", RES_BRIDGE_DESC);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitFieldInsn(Opcodes.PUTFIELD, DEF_INTERNAL, "f3", "L" + CAT_INTERNAL + ";");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(3, 17);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] geckoModelClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, GEK_MODEL_INTERNAL, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "tex", RES_BRIDGE_DESC, null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + RES_BRIDGE_DESC + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, GEK_MODEL_INTERNAL, "tex", RES_BRIDGE_DESC);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] geckoDefinitionClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, GEK_DEF_INTERNAL, null, DEF_INTERNAL, null);
        cw.visitField(Opcodes.ACC_PRIVATE, "loadedModel", "L" + GEK_MODEL_INTERNAL + ";", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "jitHandle", "Ljava/lang/Object;", null, null).visitEnd();
        String superCtor = "(JLjava/lang/String;" + RES_BRIDGE_DESC + "L" + CAT_INTERNAL + ";ZJLjava/time/Instant;Ljava/util/List;Ljava/util/List;ZLjava/lang/Object;Ljava/lang/Object;Z)V";
        String ctor = "(JLjava/lang/String;" + RES_BRIDGE_DESC + "L" + CAT_INTERNAL + ";ZJLjava/time/Instant;Ljava/util/List;Ljava/util/List;ZLjava/lang/Object;Ljava/lang/Object;ZL" + GEK_MODEL_INTERNAL + ";Ljava/lang/Object;)V";
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", ctor, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.LLOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        mv.visitVarInsn(Opcodes.LLOAD, 7);
        mv.visitVarInsn(Opcodes.ALOAD, 9);
        mv.visitVarInsn(Opcodes.ALOAD, 10);
        mv.visitVarInsn(Opcodes.ALOAD, 11);
        mv.visitVarInsn(Opcodes.ILOAD, 12);
        mv.visitVarInsn(Opcodes.ALOAD, 13);
        mv.visitVarInsn(Opcodes.ALOAD, 14);
        mv.visitVarInsn(Opcodes.ILOAD, 15);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, DEF_INTERNAL, "<init>", superCtor, false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 16);
        mv.visitFieldInsn(Opcodes.PUTFIELD, GEK_DEF_INTERNAL, "loadedModel", "L" + GEK_MODEL_INTERNAL + ";");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 17);
        mv.visitFieldInsn(Opcodes.PUTFIELD, GEK_DEF_INTERNAL, "jitHandle", "Ljava/lang/Object;");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(18, 18);
        mv.visitEnd();

        MethodVisitor getter = cw.visitMethod(Opcodes.ACC_PUBLIC, "getLoaded", "()L" + GEK_MODEL_INTERNAL + ";", null, null);
        getter.visitCode();
        getter.visitVarInsn(Opcodes.ALOAD, 0);
        getter.visitFieldInsn(Opcodes.GETFIELD, GEK_DEF_INTERNAL, "jitHandle", "Ljava/lang/Object;");
        getter.visitTypeInsn(Opcodes.CHECKCAST, GEK_MODEL_INTERNAL);
        getter.visitInsn(Opcodes.ARETURN);
        getter.visitMaxs(1, 1);
        getter.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] equippedWrapperClass(Class<?> defClass) {
        String defDesc = "L" + defClass.getName().replace('.', '/') + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, WRAPPED_INTERNAL, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "def", defDesc, null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + defDesc + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, WRAPPED_INTERNAL, "def", defDesc);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] compiledModelDataClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, MODEL_DATA_INTERNAL, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "tex", RES_BRIDGE_DESC, null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + RES_BRIDGE_DESC + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, MODEL_DATA_INTERNAL, "tex", RES_BRIDGE_DESC);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] dataSupplierInterface() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                DATA_SUPPLIER_INTERNAL, null, "java/lang/Object", null);
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "get", "()L" + MODEL_DATA_INTERNAL + ";", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] modelDrawInterface(String defInternal) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                MODEL_DRAW_IF_INTERNAL, null, "java/lang/Object", null);
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "translucent", "()Z", null, null).visitEnd();
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "highlightEntry", "()L" + defInternal + ";", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] modelDrawCompiledClass(Class<?> supplierIfClass) {
        String supDesc = "L" + supplierIfClass.getName().replace('.', '/') + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, DRAW1_INTERNAL, null, "java/lang/Object", new String[]{MODEL_DRAW_IF_INTERNAL});
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "matrix", "Ljava/util/function/Supplier;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "data", supDesc, null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/util/function/Supplier;" + supDesc + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, DRAW1_INTERNAL, "matrix", "Ljava/util/function/Supplier;");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitFieldInsn(Opcodes.PUTFIELD, DRAW1_INTERNAL, "data", supDesc);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 3);
        mv.visitEnd();
        addModelDrawStubs(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] modelDrawDirectClass(Class<?> dataClass) {
        String dataDesc = "L" + dataClass.getName().replace('.', '/') + ";";
        String defDesc = "L" + DEF_INTERNAL + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, DRAW2_INTERNAL, null, "java/lang/Object", new String[]{MODEL_DRAW_IF_INTERNAL});
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "model", dataDesc, null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "highlightEntry", defDesc, null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + dataDesc + defDesc + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, DRAW2_INTERNAL, "model", dataDesc);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitFieldInsn(Opcodes.PUTFIELD, DRAW2_INTERNAL, "highlightEntry", defDesc);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 3);
        mv.visitEnd();
        addModelDrawStubs(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] modelDrawWrappedClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, DRAW3_INTERNAL, null, "java/lang/Object", new String[]{MODEL_DRAW_IF_INTERNAL});
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "delegate", "L" + MODEL_DRAW_IF_INTERNAL + ";", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(L" + MODEL_DRAW_IF_INTERNAL + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, DRAW3_INTERNAL, "delegate", "L" + MODEL_DRAW_IF_INTERNAL + ";");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
        addModelDrawStubs(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void addModelDrawStubs(ClassWriter cw) {
        MethodVisitor t = cw.visitMethod(Opcodes.ACC_PUBLIC, "translucent", "()Z", null, null);
        t.visitCode();
        t.visitInsn(Opcodes.ICONST_0);
        t.visitInsn(Opcodes.IRETURN);
        t.visitMaxs(1, 1);
        t.visitEnd();
        MethodVisitor h = cw.visitMethod(Opcodes.ACC_PUBLIC, "highlightEntry", "()L" + DEF_INTERNAL + ";", null, null);
        h.visitCode();
        h.visitInsn(Opcodes.ACONST_NULL);
        h.visitInsn(Opcodes.ARETURN);
        h.visitMaxs(1, 1);
        h.visitEnd();
    }

    private static final class BytecodeLoader extends ClassLoader {
        private final Map<String, byte[]> classBytes = new HashMap<>();

        BytecodeLoader() {
            super(LunarCosmeticTextureResolutionTest.class.getClassLoader());
        }

        Class<?> define(String internalName, byte[] bytes) {
            classBytes.put(internalName + ".class", bytes);
            String binaryName = internalName.replace('/', '.');
            return defineClass(binaryName, bytes, 0, bytes.length);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            byte[] bytes = classBytes.get(name);
            return bytes != null ? new ByteArrayInputStream(bytes) : super.getResourceAsStream(name);
        }
    }
}
