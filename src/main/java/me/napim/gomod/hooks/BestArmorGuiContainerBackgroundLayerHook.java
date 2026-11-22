package me.napim.gomod.hooks;

import me.napim.gomod.features.BestArmorContainerOverlayDriver;
import net.weavemc.loader.api.Hook;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Mode 8 hook: injects at the end of {@code GuiContainer.drawGuiContainerBackgroundLayer(float,int,int)}
 * and draws ONLY the survival % panel (no tooltip), to avoid tooltips being covered by later slot rendering.
 */
public class BestArmorGuiContainerBackgroundLayerHook extends Hook {

    public static volatile boolean PATCH_APPLIED;

    public BestArmorGuiContainerBackgroundLayerHook() {
        super("net/minecraft/client/gui/inventory/GuiContainer");
        System.out.println("[gomod] BestArmor: registering GuiContainer.drawGuiContainerBackgroundLayer tail hook (mode 8)");
    }

    public static boolean isPatchActive() {
        return PATCH_APPLIED;
    }

    @Override
    public void transform(@NotNull ClassNode node, @NotNull AssemblerConfig cfg) {
        MethodNode bg = findBackgroundLayer(node);
        if (bg == null) {
            System.err.println("[gomod] BestArmor: GuiContainer background-layer hook — no matching method");
            return;
        }
        AbstractInsnNode ret = findLastReturn(bg);
        if (ret == null) {
            System.err.println("[gomod] BestArmor: GuiContainer background-layer hook — no RETURN opcode");
            return;
        }

        InsnList inject = new InsnList();
        // this, mouseX, mouseY
        // signature: (FII)V => locals: this=0, float=1, mouseX=2, mouseY=3
        inject.add(new VarInsnNode(Opcodes.ALOAD, 0));
        inject.add(new VarInsnNode(Opcodes.ILOAD, 2));
        inject.add(new VarInsnNode(Opcodes.ILOAD, 3));
        inject.add(new org.objectweb.asm.tree.MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "me/napim/gomod/features/BestArmorContainerOverlayDriver",
            "onGuiContainerBackgroundLayerReturn",
            "(Lnet/minecraft/client/gui/inventory/GuiContainer;II)V",
            false
        ));

        bg.instructions.insertBefore(ret, inject);
        PATCH_APPLIED = true;
        System.out.println("[gomod] BestArmor: GuiContainer background-layer hook installed (mode 8)");
    }

    private static MethodNode findBackgroundLayer(ClassNode node) {
        for (MethodNode m : node.methods) {
            if ("(FII)V".equals(m.desc) && "drawGuiContainerBackgroundLayer".equals(m.name)) {
                return m;
            }
        }
        for (MethodNode m : node.methods) {
            if ("(FII)V".equals(m.desc)) {
                return m;
            }
        }
        return null;
    }

    private static AbstractInsnNode findLastReturn(MethodNode method) {
        AbstractInsnNode found = null;
        for (AbstractInsnNode insn = method.instructions.getLast(); insn != null; insn = insn.getPrevious()) {
            int op = insn.getOpcode();
            if (op == Opcodes.RETURN || op == Opcodes.IRETURN) {
                found = insn;
                break;
            }
        }
        return found;
    }
}

