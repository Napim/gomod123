package me.napim.gomod.weave;

import me.napim.gomod.GomodReleaseBuild;
import me.napim.gomod.features.LunarConnectionDiagnostics;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Internal diagnostic taps only: no return cancellation, field writes, or native calls. */
public final class LunarArmDebugTransform {
    private static final String MODEL = "net/minecraft/client/model/ModelRenderer";
    private static final String ITEM = "net/minecraft/client/renderer/ItemRenderer";
    private static final String TEXTURE = "net/minecraft/client/renderer/texture/TextureManager";
    private static final String GL_STATE = "net/minecraft/client/renderer/GlStateManager";
    private static final String HELPER = "me/napim/gomod/features/LunarArmDebugCapture";

    private LunarArmDebugTransform() { }

    public static boolean isTarget(String name) {
        return MODEL.equals(name) || ITEM.equals(name) || TEXTURE.equals(name) || GL_STATE.equals(name);
    }

    public static byte[] transform(String className, byte[] bytes) {
        if (GomodReleaseBuild.PUBLIC_RELEASE || !isTarget(className) || bytes == null) return null;
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassNode node = new ClassNode(); reader.accept(node, 0);
            int patched = 0;
            for (MethodNode method : node.methods) {
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || alreadyPatched(method)) continue;
                if (ITEM.equals(className) && "renderItemInFirstPerson".equals(method.name) && "(F)V".equals(method.desc)
                        && (method.access & Opcodes.ACC_STATIC) == 0) {
                    method.instructions.insert(call("beginFirstPersonFrame", "()V"));
                    for (AbstractInsnNode instruction : method.instructions.toArray()) {
                        if (instruction.getOpcode() == Opcodes.RETURN) method.instructions.insertBefore(instruction, call("endFirstPersonFrame", "()V"));
                    }
                    patched++;
                } else if (MODEL.equals(className) && modelDraw(method)) {
                    InsnList tap = new InsnList(); tap.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    tap.add(new LdcInsnNode(method.name)); tap.add(call("recordModelDraw", "(Ljava/lang/Object;Ljava/lang/String;)V"));
                    method.instructions.insert(tap); method.maxStack = Math.max(2, method.maxStack); patched++;
                } else if (TEXTURE.equals(className) && "bindTexture".equals(method.name)
                        && "(Lnet/minecraft/util/ResourceLocation;)V".equals(method.desc) && (method.access & Opcodes.ACC_STATIC) == 0) {
                    for (AbstractInsnNode instruction : method.instructions.toArray()) {
                        if (instruction.getOpcode() == Opcodes.RETURN) {
                            InsnList completed = new InsnList(); completed.add(new VarInsnNode(Opcodes.ALOAD, 1));
                            completed.add(call("recordResourceTexture", "(Ljava/lang/Object;)V"));
                            method.instructions.insertBefore(instruction, completed);
                        }
                    }
                    method.maxStack = Math.max(1, method.maxStack); patched++;
                } else if (GL_STATE.equals(className) && "bindTexture".equals(method.name)
                        && "(I)V".equals(method.desc) && (method.access & Opcodes.ACC_STATIC) != 0) {
                    InsnList tap = new InsnList(); tap.add(new VarInsnNode(Opcodes.ILOAD, 0));
                    tap.add(call("recordTextureId", "(I)V"));
                    method.instructions.insert(tap); method.maxStack = Math.max(1, method.maxStack); patched++;
                }
            }
            LunarConnectionDiagnostics.recordOnceAfterStart("lunar.arm_debug.transform", className,
                    "target=" + className + " tappedMethods=" + patched + " diagnosticOnly=true schemaPreserved=true");
            if (patched == 0) return null;
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
        } catch (Throwable error) {
            LunarConnectionDiagnostics.recordOnceAfterStart("lunar.arm_debug.transform_failed", className,
                    "exception=" + error.getClass().getSimpleName());
            return null;
        }
    }

    private static boolean modelDraw(MethodNode method) {
        if ((method.access & Opcodes.ACC_STATIC) != 0 || !Type.getReturnType(method.desc).equals(Type.VOID_TYPE)) return false;
        if (("render".equals(method.name) || "renderWithRotation".equals(method.name)) && "(F)V".equals(method.desc)) return true;
        Type[] args = Type.getArgumentTypes(method.desc);
        return "bridge$render".equals(method.name) && args.length == 2 && args[0].equals(Type.FLOAT_TYPE)
                && args[1].getSort() == Type.OBJECT;
    }

    private static MethodInsnNode call(String name, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, name, descriptor, false);
    }

    private static boolean alreadyPatched(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode && HELPER.equals(((MethodInsnNode) instruction).owner)) return true;
        }
        return false;
    }
}
