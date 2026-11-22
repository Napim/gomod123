package me.napim.gomod.weave.hooks;

import net.weavemc.api.Hook;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;

/** Native Weave hook for the cloak renderer shipped in Lunar's July 2026 client. */
public class LunarCurrentClothCloakOpacityWeaveHook extends Hook {
    private static final String TARGET =
            "com/moonsworth/lunar/client/ORCOIHHHROORRIIIRCHHRRCIICHOCH/HCOCIICRHHRCRIHIIIRRIOIHIIHCOC/"
                    + "HCOCIICRHHRCRIHIIIRRIOIHIIHCOC/ORCRCHHRCHRIIRHIRCIHRHOHRCHROC";
    private static final String METHOD = "IIOOIIHRIOCCIHHCIRICHIIHIHOHII";
    private static final String LEGACY_PLAYER =
            "Lcom/moonsworth/lunar/COICHHIHCCCOOROCCHHICHRIRCCHRR/ORCRCHHRCHRIIRHIRCIHRHOHRCHROC/"
                    + "RHOOROCCICRCRICICHICIIHRCOIRHC/IIOOIIHRIOCCIHHCIRICHIIHIHOHII;";
    private static final String CURRENT_PLAYER =
            "Lcom/moonsworth/lunar/COICHHIHCCCOOROCCHHICHRIRCCHRR/ORCRCHHRCHRIIRHIRCIHRHOHRCHROC/"
                    + "COCHICRCCIRHIHRRCHCIOOIOOOHHIH/RHOOROCCICRCRICICHICIIHRCOIRHC/"
                    + "COICHHIHCCCOOROCCHHICHRIRCCHRR/RHOOROCCICRCRICICHICIIHRCOIRHC;";
    private static final String RENDER_TYPE_REGISTRY =
            "com/moonsworth/lunar/COICHHIHCCCOOROCCHHICHRIRCCHRR/ORCRCHHRCHRIIRHIRCIHRHOHRCHROC/"
                    + "COCHICRCCIRHIHRRCHCIOOIOOOHHIH/IIOOIIHRIOCCIHHCIRICHIIHIHOHII/"
                    + "HCOCIICRHHRCRIHIIIRRIOIHIIHCOC/ROROCHCIRCCHHCRCCOOOIHIRHOOCOI";
    private static final String RENDER_TYPE_FACTORY_DESC =
            "Lcom/moonsworth/lunar/COICHHIHCCCOOROCCHHICHRIRCCHRR/ORCRCHHRCHRIIRHIRCIHRHOHRCHROC/"
                    + "COCHICRCCIRHIHRRCHCIOOIOOOHHIH/IIOOIIHRIOCCIHHCIRICHIIHIHOHII/"
                    + "HCOCIICRHHRCRIHIIIRRIOIHIIHCOC/RHOOROCCICRCRICICHICIIHRCOIRHC;";
    private static final String NORMAL_CLOAK_RENDER_TYPE = "RHRHCHHOIOCHIHCCCOIHRCIIHRIHIC";
    // Lunar's dedicated translucent cloak type retains cloak-specific texture
    // state. Generic entity translucent types damage cloth-cloak texturing.
    private static final String TRANSLUCENT_CLOAK_RENDER_TYPE = "HRIHIHIICIIOROOCIIHCRIHCORIHIO";

    public static volatile int lastPatchedMethods = -1;
    public static volatile String lastTargetName = TARGET;

    public LunarCurrentClothCloakOpacityWeaveHook() {
        super(TARGET);
    }

    @Override
    public void transform(@NotNull ClassNode node, @NotNull AssemblerConfig cfg) {
        int patched = 0;
        int translucentPatches = 0;
        for (MethodNode method : (List<MethodNode>) node.methods) {
            if (isCloakEntryPoint(method)) {
                patchMethod(method);
                patched++;
            }
            translucentPatches += patchTranslucentRenderType(method);
        }
        lastPatchedMethods = patched;
        if (patched == 0) {
            System.err.println("[gomod] current Lunar cloth cloak hook: renderer found but no entry points matched");
        } else {
            System.out.println("[gomod] current Lunar cloth cloak opacity hook installed (" + patched + " methods)");
        }
        if (translucentPatches == 0) {
            System.err.println("[gomod] current Lunar cloth translucent render hook: normal cloak render type not found");
        } else {
            System.out.println("[gomod] current Lunar cloth translucent render hook installed ("
                    + translucentPatches + " sites)");
        }
    }

    private static boolean isCloakEntryPoint(MethodNode method) {
        if (method == null || !METHOD.equals(method.name) || method.desc == null
                || !method.desc.endsWith(")V") || (method.access & Opcodes.ACC_STATIC) != 0
                || (method.access & Opcodes.ACC_PUBLIC) == 0 || method.instructions == null
                || method.instructions.getFirst() == null) {
            return false;
        }
        // Both genuine cloak entry points have the matrix as arg 1 and a Lunar player bridge as arg 2.
        return method.desc.contains(";" + LEGACY_PLAYER) || method.desc.contains(";" + CURRENT_PLAYER);
    }

    private static void patchMethod(MethodNode method) {
        LabelNode continueRender = new LabelNode();
        InsnList head = new InsnList();
        head.add(new VarInsnNode(Opcodes.ALOAD, 2));
        head.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "me/napim/gomod/features/LunarExternalOpacityBridge",
                "beginLunarCloak",
                "(Ljava/lang/Object;)Z",
                false));
        head.add(new JumpInsnNode(Opcodes.IFEQ, continueRender));
        head.add(new InsnNode(Opcodes.RETURN));
        head.add(continueRender);
        method.instructions.insertBefore(method.instructions.getFirst(), head);

        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.RETURN) {
                InsnList tail = new InsnList();
                tail.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "me/napim/gomod/features/LunarExternalOpacityBridge",
                        "endLunarCloak",
                        "()V",
                        false));
                method.instructions.insertBefore(insn, tail);
            }
        }
    }

    private static int patchTranslucentRenderType(MethodNode method) {
        if (method == null || method.instructions == null) {
            return 0;
        }
        int patched = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof FieldInsnNode)) {
                continue;
            }
            FieldInsnNode field = (FieldInsnNode) insn;
            if (field.getOpcode() != Opcodes.GETSTATIC
                    || !RENDER_TYPE_REGISTRY.equals(field.owner)
                    || !NORMAL_CLOAK_RENDER_TYPE.equals(field.name)
                    || !RENDER_TYPE_FACTORY_DESC.equals(field.desc)) {
                continue;
            }

            LabelNode useNormal = new LabelNode();
            LabelNode selected = new LabelNode();
            InsnList choose = new InsnList();
            choose.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "me/napim/gomod/features/LunarExternalOpacityBridge",
                    "isOpacityActive",
                    "()Z",
                    false));
            choose.add(new JumpInsnNode(Opcodes.IFEQ, useNormal));
            choose.add(new FieldInsnNode(Opcodes.GETSTATIC,
                    RENDER_TYPE_REGISTRY,
                    TRANSLUCENT_CLOAK_RENDER_TYPE,
                    RENDER_TYPE_FACTORY_DESC));
            choose.add(new JumpInsnNode(Opcodes.GOTO, selected));
            choose.add(useNormal);
            method.instructions.insertBefore(field, choose);
            method.instructions.insert(field, selected);
            patched++;
        }
        return patched;
    }
}
