package me.napim.gomod.hooks;

import net.weavemc.loader.api.Hook;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Forge/coremod fallback that reapplies inventory-preview opacity after GlStateManager updates
 * its cached colour. It deliberately does nothing outside the short inventory mannequin scope.
 */
public final class GlStateManagerGuiOpacityHook extends Hook {
    public GlStateManagerGuiOpacityHook() {
        super("net/minecraft/client/renderer/GlStateManager");
    }

    @Override
    public void transform(@NotNull ClassNode node, @NotNull AssemblerConfig cfg) {
        int patchedReturns = 0;
        int patchedBlendReturns = 0;
        for (MethodNode method : node.methods) {
            if (method.instructions == null) {
                continue;
            }
            boolean colorMethod = "(FFFF)V".equals(method.desc);
            boolean blendMethod = "(II)V".equals(method.desc) || "(IIII)V".equals(method.desc);
            if (!colorMethod && !blendMethod) continue;
            // color(float,float,float,float) is the only four-float void method in vanilla
            // GlStateManager. Descriptor fallback also covers its obfuscated Forge name.
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() != Opcodes.RETURN) {
                    continue;
                }
                InsnList tail = new InsnList();
                if (colorMethod) {
                    tail.add(new VarInsnNode(Opcodes.FLOAD, 0));
                    tail.add(new VarInsnNode(Opcodes.FLOAD, 1));
                    tail.add(new VarInsnNode(Opcodes.FLOAD, 2));
                    tail.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "me/napim/gomod/features/LunarExternalOpacityBridge",
                            "forceGuiInventoryColor", "(FFF)V", false));
                    patchedReturns++;
                } else {
                    tail.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "me/napim/gomod/features/SelfOpacity",
                            "forceGuiInventoryBlendState", "()V", false));
                    patchedBlendReturns++;
                }
                method.instructions.insertBefore(insn, tail);
            }
        }
        if (patchedReturns == 0) {
            System.err.println("[gomod] Forge inventory GL-colour fallback: color(FFFF) not found");
        } else {
            System.out.println("[gomod] Forge inventory GL-colour fallback installed ("
                    + patchedReturns + " colour, " + patchedBlendReturns + " blend returns)");
        }
    }
}
