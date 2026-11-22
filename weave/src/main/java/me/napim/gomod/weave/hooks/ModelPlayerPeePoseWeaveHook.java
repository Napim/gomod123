package me.napim.gomod.weave.hooks;

import net.weavemc.api.Hook;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;

/** Native Weave fallback for the /pee player-arm pose, including the empty first-person hand. */
public final class ModelPlayerPeePoseWeaveHook extends Hook {
    public ModelPlayerPeePoseWeaveHook() {
        super("net/minecraft/client/model/ModelPlayer");
    }

    @Override
    public void transform(@NotNull ClassNode node, @NotNull AssemblerConfig cfg) {
        MethodNode target = null;
        for (MethodNode method : (List<MethodNode>) node.methods) {
            if (("setRotationAngles".equals(method.name) || "func_78087_a".equals(method.name))
                    && isRotationAngles(method.desc)) {
                target = method;
                break;
            }
        }
        if (target == null) {
            for (MethodNode method : (List<MethodNode>) node.methods) {
                if (isRotationAngles(method.desc)) {
                    target = method;
                    break;
                }
            }
        }
        if (target == null || target.instructions == null) {
            System.err.println("[gomod] native Weave /pee arm hook: setRotationAngles not found");
            return;
        }

        int patched = 0;
        for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; ) {
            AbstractInsnNode next = insn.getNext();
            if (insn.getOpcode() == Opcodes.RETURN) {
                InsnList tail = new InsnList();
                tail.add(new VarInsnNode(Opcodes.ALOAD, 0));
                tail.add(new VarInsnNode(Opcodes.FLOAD, 3));
                tail.add(new VarInsnNode(Opcodes.ALOAD, 7));
                tail.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "me/napim/gomod/features/PeeEffectManager",
                        "applyPlayerModelPoseIfActive",
                        "(Lnet/minecraft/client/model/ModelBiped;FLnet/minecraft/entity/Entity;)V", false));
                target.instructions.insertBefore(insn, tail);
                patched++;
            }
            insn = next;
        }
        System.out.println("[gomod] native Weave /pee arm hook installed (" + patched + " returns)");
    }

    private static boolean isRotationAngles(String desc) {
        return desc != null && desc.matches("\\(FFFFFFL[^;]+;\\)V");
    }
}
