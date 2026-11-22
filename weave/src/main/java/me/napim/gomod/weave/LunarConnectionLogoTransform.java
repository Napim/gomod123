package me.napim.gomod.weave;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import me.napim.gomod.features.LunarConnectionDiagnostics;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Finds native icon snapshots and textured draws by their signatures, without Lunar initialization. */
public final class LunarConnectionLogoTransform {
    private static final String HELPER = "me/napim/gomod/features/LunarConnectionLogo";
    private LunarConnectionLogoTransform() { }

    public static boolean mayContain(String name, byte[] bytes) {
        if (name == null || bytes == null || !name.startsWith("com/moonsworth/lunar/")) return false;
        String pool = new String(bytes, StandardCharsets.ISO_8859_1);
        return pool.contains("cosmetic/v2/LoadTabLogosResponse")
                || pool.contains("cosmetic/v2/PlayerCosmeticsPushV2") || pool.contains(";FFFFI)V");
    }

    public static boolean isLoadedTarget(Class<?> type) {
        String name = type.getName().replace('.', '/');
        if (!name.startsWith("com/moonsworth/lunar/")) return false;
        try (InputStream input = type.getResourceAsStream("/" + name + ".class")) {
            if (input == null) return false;
            return mayContain(name, readAll(input));
        } catch (Exception unavailable) { return false; }
    }

    public static byte[] transform(ClassLoader loader, String name, byte[] bytes) {
        if (!mayContain(name, bytes)) return null;
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassNode node = new ClassNode(); reader.accept(node, 0);
            int snapshots = 0, draws = 0, svgDispatches = 0;
            for (MethodNode method : node.methods) {
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || hooked(method)) continue;
                Type[] args = Type.getArgumentTypes(method.desc);
                Type result = Type.getReturnType(method.desc);
                if ((method.access & Opcodes.ACC_STATIC) == 0 && args.length == 1
                        && args[0].getDescriptor().equals("Ljava/util/UUID;")
                        && result.getSort() == Type.OBJECT && iconRecord(loader, result.getInternalName())
                        && readsIconCache(method)) {
                    for (AbstractInsnNode instruction : method.instructions.toArray()) {
                        if (instruction.getOpcode() != Opcodes.ARETURN) continue;
                        InsnList filter = new InsnList();
                        filter.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        filter.add(new LdcInsnNode(result));
                        filter.add(call("snapshot", "(Ljava/lang/Object;Ljava/util/UUID;Ljava/lang/Class;)Ljava/lang/Object;"));
                        filter.add(new TypeInsnNode(Opcodes.CHECKCAST, result.getInternalName()));
                        method.instructions.insertBefore(instruction, filter); snapshots++;
                    }
                }
                if ((method.access & Opcodes.ACC_STATIC) != 0 && args.length == 7
                        && args[0].getSort() == Type.OBJECT && args[1].getSort() == Type.OBJECT
                        && args[2].equals(Type.FLOAT_TYPE) && args[3].equals(Type.FLOAT_TYPE)
                        && args[4].equals(Type.FLOAT_TYPE) && args[5].equals(Type.FLOAT_TYPE)
                        && args[6].equals(Type.INT_TYPE) && result.equals(Type.VOID_TYPE)
                        && resourceType(loader, args[1].getInternalName())) {
                    hookRotation(method, false);
                    hookOfflineTexture(method, 1, 6, args[1].getInternalName());
                    draws++;
                }
                // Lunar's icon dispatcher returns early for .svg resources, before the bitmap
                // drawer can replace them. Swap the marked local Plus resource before that check;
                // it then follows Lunar's normal textured-icon path with its original geometry.
                if ((method.access & Opcodes.ACC_STATIC) != 0 && args.length == 7
                        && args[0].getSort() == Type.OBJECT && args[1].getSort() == Type.OBJECT
                        && args[2].equals(Type.FLOAT_TYPE) && args[3].equals(Type.FLOAT_TYPE)
                        && args[4].equals(Type.INT_TYPE) && args[5].getSort() == Type.OBJECT
                        && args[5].getInternalName().startsWith("com/lunarclient/apollo/common/icon/")
                        && args[6].equals(Type.INT_TYPE) && result.equals(Type.VOID_TYPE)
                        && resourceType(loader, args[1].getInternalName())) {
                    hookRotation(method, true);
                    hookOfflineTexture(method, 1, 6, args[1].getInternalName());
                    svgDispatches++;
                }
            }
            if (snapshots == 0 && draws == 0 && svgDispatches == 0) return null;
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS); node.accept(writer);
            LunarConnectionDiagnostics.recordOnceAfterStart("lunar.connection_logo_hooks", name,
                    "snapshotReturns=" + snapshots + " texturedDraws=" + draws
                            + " svgIconDispatches=" + svgDispatches + " offlineResourceAndTintHooks=true"
                            + " nativePoseRotation=true replacementTexturePreserved=true");
            return writer.toByteArray();
        } catch (Exception unsupported) {
            LunarConnectionDiagnostics.recordOnceAfterStart("lunar.connection_logo_hooks_failed", name,
                    "errorType=" + unsupported.getClass().getSimpleName());
            return null;
        }
    }

    private static boolean hooked(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions)
            if (instruction instanceof MethodInsnNode && HELPER.equals(((MethodInsnNode) instruction).owner)) return true;
        return false;
    }

    private static boolean readsIconCache(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.owner.equals("java/util/Map") && call.name.equals("get")
                    || call.owner.equals("com/google/common/cache/Cache") && call.name.equals("getIfPresent")) return true;
        }
        return false;
    }

    private static boolean iconRecord(ClassLoader loader, String type) throws Exception {
        if (!type.startsWith("com/moonsworth/lunar/")) return false;
        ClassNode node = resource(loader, type);
        if (node == null) return false;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<init>")) continue;
            int count = Type.getArgumentTypes(method.desc).length;
            if (count == 3 && method.desc.startsWith("(IIL") || count == 7 && method.desc.startsWith("(FFFZIZL")) return true;
        }
        return false;
    }

    private static boolean resourceType(ClassLoader loader, String type) throws Exception {
        if (!type.startsWith("com/moonsworth/lunar/")) return false;
        ClassNode node = resource(loader, type);
        if (node == null) return false;
        boolean domain = false, path = false;
        for (MethodNode method : node.methods) {
            domain |= method.name.equals("bridge$getDomain") && method.desc.equals("()Ljava/lang/String;");
            path |= method.name.equals("bridge$getPath") && method.desc.equals("()Ljava/lang/String;");
        }
        return domain && path;
    }

    private static ClassNode resource(ClassLoader loader, String name) throws Exception {
        if (loader == null) return null;
        try (InputStream input = loader.getResourceAsStream(name + ".class")) {
            if (input == null) return null;
            ClassNode node = new ClassNode(); new ClassReader(input).accept(node, ClassReader.SKIP_CODE);
            return node;
        }
    }

    private static byte[] readAll(InputStream input) throws java.io.IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static MethodInsnNode call(String name, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, name, descriptor, false);
    }

    private static void hookOfflineTexture(MethodNode method, int resourceSlot, int colorSlot,
                                           String resourceType) {
        InsnList head = new InsnList();
        head.add(new VarInsnNode(Opcodes.ALOAD, resourceSlot));
        head.add(new VarInsnNode(Opcodes.ILOAD, colorSlot));
        head.add(new LdcInsnNode(Type.getObjectType(resourceType)));
        head.add(call("texture", "(Ljava/lang/Object;ILjava/lang/Class;)Ljava/lang/Object;"));
        head.add(new TypeInsnNode(Opcodes.CHECKCAST, resourceType));
        head.add(new VarInsnNode(Opcodes.ASTORE, resourceSlot));
        method.instructions.insert(head);
    }

    private static void hookRotation(MethodNode method, boolean squareSize) {
        AbstractInsnNode[] original=method.instructions.toArray();
        LabelNode start=new LabelNode(),end=new LabelNode(),handler=new LabelNode();
        InsnList head=new InsnList();
        head.add(new VarInsnNode(Opcodes.ALOAD,0));
        head.add(new VarInsnNode(Opcodes.FLOAD,2));head.add(new VarInsnNode(Opcodes.FLOAD,3));
        for(int slot=4;slot<=5;slot++){
            head.add(new VarInsnNode(squareSize?Opcodes.ILOAD:Opcodes.FLOAD,squareSize?4:slot));
            if(squareSize)head.add(new InsnNode(Opcodes.I2F));
        }
        head.add(new VarInsnNode(Opcodes.ALOAD,1));
        head.add(new VarInsnNode(Opcodes.ILOAD,6));
        head.add(call("beginRotation","(Ljava/lang/Object;FFFFLjava/lang/Object;I)I"));
        head.add(new VarInsnNode(Opcodes.ISTORE,6));head.add(start);
        method.instructions.insert(head);
        for(AbstractInsnNode instruction:original)if(instruction.getOpcode()==Opcodes.RETURN)
            method.instructions.insertBefore(instruction,call("endRotation","()V"));
        method.instructions.add(end);method.instructions.add(handler);
        method.instructions.add(new FrameNode(Opcodes.F_FULL,0,new Object[0],1,new Object[]{"java/lang/Throwable"}));
        method.instructions.add(call("endRotation","()V"));method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start,end,handler,null));
    }
}
