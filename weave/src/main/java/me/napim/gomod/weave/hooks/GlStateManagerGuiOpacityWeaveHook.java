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

/** Enforces inventory-preview opacity after Lunar/vanilla resets the GL colour. */
public class GlStateManagerGuiOpacityWeaveHook extends Hook {
    public GlStateManagerGuiOpacityWeaveHook() {
        super("net/minecraft/client/renderer/GlStateManager");
    }

    @Override
    public void transform(@NotNull ClassNode node, @NotNull AssemblerConfig cfg) {
        int patchedReturns = 0;
        int patchedBlendReturns = 0;
        for (MethodNode method : (List<MethodNode>) node.methods) {
            if (method.instructions == null) {
                continue;
            }
            boolean colorMethod = "(FFFF)V".equals(method.desc)
                    && ("color".equals(method.name) || "func_179131_c".equals(method.name));
            boolean blendMethod = "(II)V".equals(method.desc) || "(IIII)V".equals(method.desc);
            boolean textureMethod="(I)V".equals(method.desc)&&("bindTexture".equals(method.name)||"func_179144_i".equals(method.name));
            if (!colorMethod && !blendMethod&&!textureMethod) continue;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() != Opcodes.RETURN) {
                    continue;
                }
                InsnList tail = new InsnList();
                if(textureMethod){
                    tail.add(new VarInsnNode(Opcodes.ILOAD,0));
                    tail.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"me/napim/gomod/features/LunarCosmeticAppearanceRenderer","afterMinecraftTextureBind","(I)V",false));
                }else if (colorMethod) {
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
            System.err.println("[gomod] native Weave inventory GL-colour hook: color(FFFF) not found");
        } else {
            System.out.println("[gomod] native Weave inventory GL-colour hook installed ("
                    + patchedReturns + " colour, " + patchedBlendReturns + " blend returns)");
        }
    }
}
