package me.napim.gomod.hooks;

import net.weavemc.loader.api.Hook;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Forge ASM fallback for the player /pee pose when gomod's mixin config loses the tweaker race. */
public final class ModelPlayerPeePoseForgeHook extends Hook {
    public ModelPlayerPeePoseForgeHook() {
        super("net/minecraft/client/model/ModelPlayer");
    }

    @Override
    public void transform(ClassNode node, AssemblerConfig cfg) {
        MethodNode target = null;
        for (MethodNode method : node.methods) {
            if (isRotationAngles(method.desc)
                    && ("setRotationAngles".equals(method.name) || "func_78087_a".equals(method.name))) {
                target = method;
                break;
            }
        }
        if (target == null) {
            for (MethodNode method : node.methods) {
                if (isRotationAngles(method.desc)) {
                    target = method;
                    break;
                }
            }
        }
        if (target == null) return;

        for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; ) {
            AbstractInsnNode next = insn.getNext();
            if (insn.getOpcode() == Opcodes.RETURN) {
                InsnList tail = new InsnList();
                tail.add(new VarInsnNode(Opcodes.ALOAD, 0));
                tail.add(new VarInsnNode(Opcodes.FLOAD, 3));
                tail.add(new VarInsnNode(Opcodes.ALOAD, 7));
                tail.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "me/napim/gomod/coremod/PeeForgeFallback",
                        "applyPlayerPose",
                        "(Ljava/lang/Object;FLjava/lang/Object;)V", false));
                target.instructions.insertBefore(insn, tail);
            }
            insn = next;
        }
        System.out.println("[gomod] Forge /pee ModelPlayer pose fallback installed");
    }

    private static boolean isRotationAngles(String desc) {
        return desc != null && desc.matches("\\(FFFFFFL[^;]+;\\)V");
    }
}
