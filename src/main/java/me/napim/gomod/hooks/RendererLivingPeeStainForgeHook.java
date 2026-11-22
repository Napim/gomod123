package me.napim.gomod.hooks;

import net.weavemc.loader.api.Hook;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Forge ASM fallback that draws player stains after every normal player render layer. */
public final class RendererLivingPeeStainForgeHook extends Hook {
    public RendererLivingPeeStainForgeHook() {
        super("net/minecraft/client/renderer/entity/RendererLivingEntity");
    }

    @Override
    public void transform(ClassNode node, AssemblerConfig cfg) {
        int callerPatched = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; ) {
                AbstractInsnNode next = insn.getNext();
                if (insn instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) insn;
                    if (node.name.equals(call.owner) && isRenderLayers(call.desc)) {
                        InsnList afterLayers = new InsnList();
                        afterLayers.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        afterLayers.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        afterLayers.add(new LdcInsnNode(Float.valueOf(0.0625F)));
                        afterLayers.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                "me/napim/gomod/coremod/PeeForgeFallback", "renderPlayerStains",
                                "(Ljava/lang/Object;Ljava/lang/Object;F)V", false));
                        method.instructions.insert(insn, afterLayers);
                        callerPatched++;
                    }
                }
                insn = next;
            }
        }

        MethodNode target = null;
        for (MethodNode method : node.methods) {
            if (isRenderLayers(method.desc)
                    && ("renderLayers".equals(method.name) || "func_177093_a".equals(method.name))) {
                target = method;
                break;
            }
        }
        if (target == null) {
            for (MethodNode method : node.methods) {
                if (isRenderLayers(method.desc)) {
                    target = method;
                    break;
                }
            }
        }
        if (target == null) {
            System.out.println("[gomod] Forge /pee post-layer caller fallback installed ("
                    + callerPatched + " calls; renderLayers body unavailable)");
            return;
        }

        for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; ) {
            AbstractInsnNode next = insn.getNext();
            if (insn.getOpcode() == Opcodes.RETURN) {
                InsnList tail = new InsnList();
                tail.add(new VarInsnNode(Opcodes.ALOAD, 0));
                tail.add(new VarInsnNode(Opcodes.ALOAD, 1));
                tail.add(new VarInsnNode(Opcodes.FLOAD, 8));
                tail.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "me/napim/gomod/coremod/PeeForgeFallback",
                        "renderPlayerStains",
                        "(Ljava/lang/Object;Ljava/lang/Object;F)V", false));
                target.instructions.insertBefore(insn, tail);
            }
            insn = next;
        }
        System.out.println("[gomod] Forge /pee player-stain fallback installed ("
                + callerPatched + " post-layer calls)");
    }

    private static boolean isRenderLayers(String desc) {
        return desc != null && desc.matches("\\(L[^;]+;FFFFFFF\\)V");
    }
}
