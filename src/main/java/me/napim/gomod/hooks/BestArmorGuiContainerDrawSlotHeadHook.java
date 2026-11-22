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

import java.util.List;

/**
 * Injects at the head of {@code GuiContainer.drawSlot(Slot)} and draws Inventory-QoL tints immediately before the
 * slot's item is rendered (z-order safe).
 */
public class BestArmorGuiContainerDrawSlotHeadHook extends Hook {

    public static volatile boolean PATCH_APPLIED;

    public BestArmorGuiContainerDrawSlotHeadHook() {
        super("net/minecraft/client/gui/inventory/GuiContainer");
        System.out.println("[gomod] Inventory QoL: registering GuiContainer.drawSlot HEAD underlay hook");
    }

    public static boolean isPatchActive() {
        return PATCH_APPLIED;
    }

    @Override
    public void transform(@NotNull ClassNode node, @NotNull AssemblerConfig cfg) {
        MethodNode drawSlot = findDrawSlot(node);
        if (drawSlot == null) {
            System.err.println("[gomod] BestArmor: GuiContainer.drawSlot HEAD hook — no matching method");
            return;
        }
        AbstractInsnNode first = drawSlot.instructions.getFirst();
        if (first == null) {
            System.err.println("[gomod] BestArmor: GuiContainer.drawSlot HEAD hook — method has no instructions");
            return;
        }

        InsnList inject = new InsnList();
        // this, slot
        inject.add(new VarInsnNode(Opcodes.ALOAD, 0));
        inject.add(new VarInsnNode(Opcodes.ALOAD, 1));
        inject.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "me/napim/gomod/features/BestArmorContainerOverlayDriver",
            "onGuiContainerDrawSlotHead",
            "(Lnet/minecraft/client/gui/inventory/GuiContainer;Lnet/minecraft/inventory/Slot;)V",
            false
        ));

        drawSlot.instructions.insertBefore(first, inject);
        PATCH_APPLIED = true;
        System.out.println("[gomod] Inventory QoL: GuiContainer.drawSlot HEAD underlay hook installed");
    }

    private static MethodNode findDrawSlot(ClassNode node) {
        for (MethodNode m : (List<MethodNode>) node.methods) {
            if ("(Lnet/minecraft/inventory/Slot;)V".equals(m.desc)) {
                // Prefer drawSlot name when present, but descriptor is the real anchor.
                if ("drawSlot".equals(m.name)) {
                    return m;
                }
                // Keep searching in case there is another descriptor match with a better name.
            }
        }
        for (MethodNode m : (List<MethodNode>) node.methods) {
            if ("(Lnet/minecraft/inventory/Slot;)V".equals(m.desc)) {
                return m;
            }
        }
        return null;
    }
}

