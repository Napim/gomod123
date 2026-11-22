package me.napim.gomod.hooks;

import net.weavemc.loader.api.Hook;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Forge ASM fallback for animation mods that render players through ModelBiped instead of ModelPlayer. */
public final class ModelBipedPeePoseForgeHook extends Hook {
    public ModelBipedPeePoseForgeHook() {
        super("net/minecraft/client/model/ModelBiped");
    }

    @Override
    public void transform(ClassNode node, AssemblerConfig cfg) {
        int patched = 0;
        for (MethodNode method : node.methods) {
            if (!isRotationAngles(method.desc)) continue;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; ) {
                AbstractInsnNode next = insn.getNext();
                if (insn.getOpcode() == Opcodes.RETURN) {
                    InsnList tail = new InsnList();
                    tail.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    tail.add(new VarInsnNode(Opcodes.FLOAD, 3));
                    tail.add(new VarInsnNode(Opcodes.ALOAD, 7));
                    tail.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "me/napim/gomod/coremod/PeeForgeFallback", "applyPlayerPose",
                            "(Ljava/lang/Object;FLjava/lang/Object;)V", false));
                    method.instructions.insertBefore(insn, tail);
                    patched++;
                }
                insn = next;
            }
        }
        System.out.println("[gomod] Forge /pee ModelBiped pose fallback installed (" + patched + " returns)");
    }

    private static boolean isRotationAngles(String desc) {
        return desc != null && desc.matches("\\(FFFFFFL[^;]+;\\)V");
    }
}
