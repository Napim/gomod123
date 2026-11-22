package me.napim.gomod.hooks;

import me.napim.gomod.features.BestArmorContainerOverlayDriver;
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
 * Injects at the end of {@code GuiContainer.drawScreen} so best-armor draws <strong>after</strong> slots/items.
 * {@link RenderGameOverlayEvent} runs before the inventory GUI on many clients, so overlays looked buried.
 */
public class BestArmorGuiContainerDrawHook extends Hook {

    public static volatile boolean PATCH_APPLIED;

    public BestArmorGuiContainerDrawHook() {
        super("net/minecraft/client/gui/inventory/GuiContainer");
        System.out.println("[gomod] BestArmor: registering GuiContainer.drawScreen tail hook (z-order fix)");
    }

    public static boolean isPatchActive() {
        return PATCH_APPLIED;
    }

    @Override
    public void transform(@NotNull ClassNode node, @NotNull AssemblerConfig cfg) {
        MethodNode draw = findDrawScreen(node);
        if (draw == null) {
            System.err.println("[gomod] BestArmor: GuiContainer drawScreen hook — no matching method, using overlay fallback");
            return;
        }
        AbstractInsnNode ret = findLastReturn(draw);
        if (ret == null) {
            System.err.println("[gomod] BestArmor: GuiContainer drawScreen hook — no RETURN opcode");
            return;
        }
        InsnList inject = new InsnList();
        inject.add(new VarInsnNode(Opcodes.ALOAD, 0));
        inject.add(new VarInsnNode(Opcodes.ILOAD, 1));
        inject.add(new VarInsnNode(Opcodes.ILOAD, 2));
        inject.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "me/napim/gomod/features/BestArmorContainerOverlayDriver",
            "onGuiContainerDrawScreenReturn",
            "(Lnet/minecraft/client/gui/inventory/GuiContainer;II)V",
            false
        ));
        draw.instructions.insertBefore(ret, inject);
        PATCH_APPLIED = true;
        System.out.println("[gomod] BestArmor: GuiContainer.drawScreen tail hook installed (z-order after items)");
    }

    private static MethodNode findDrawScreen(ClassNode node) {
        MethodNode descMatch = null;
        for (MethodNode m : node.methods) {
            if (!"(IIF)V".equals(m.desc)) {
                continue;
            }
            if (descMatch == null) {
                descMatch = m; // fallback if method names differ on Lunar
            }
            if ("drawScreen".equals(m.name) || "func_146976_a".equals(m.name)) {
                return m;
            }
        }
        // Lunar/Genesis may rename drawScreen; descriptor match is usually enough here.
        return descMatch;
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
