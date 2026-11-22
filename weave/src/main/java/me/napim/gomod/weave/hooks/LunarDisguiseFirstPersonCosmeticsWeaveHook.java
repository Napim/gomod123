package me.napim.gomod.weave.hooks;

import net.weavemc.api.Hook;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/** Guards Lunar's arm-cosmetic helper and its separate arm-model draw without cancelling vanilla arms. */
public final class LunarDisguiseFirstPersonCosmeticsWeaveHook extends Hook {
    private static final String TARGET =
            "com/moonsworth/lunar/client/HRCIHRROICRHRHOHIRORRCCHOICRRI/"
                    + "CIIHHRHHHIOIIHHRROOHRHICIHOOOC/HCRIRCCRRHHHHOOCICIHOCOOICCIRC/"
                    + "IHCCRCCOHRCCIORHHCROORROCICRHI";
    private static final String METHOD = "IOHOHIHHHHORRCCRHCRCHCRIOIHRHH";
    private static final String BRIDGE_PREFIX = "com/moonsworth/lunar/IHCCRCCOHRCCIORHHCROORROCICRHI/"
            + "CIIHHRHHHIOIIHHRROOHRHICIHOOOC/";
    private static final String MATRIX = BRIDGE_PREFIX + "COHCCRCCIOORIRIIRIRCIHHCHIRCOH/IOIRIIRHHCCRIHCOOHHHIIIRCICCRI";
    private static final String COSMETIC_RENDER_MATRIX = BRIDGE_PREFIX
            + "COHCCRCCIOORIRIIRIRCIHHCHIRCOH/CCCCROIRORCRRHCCOICRIHRHIIROOR";
    private static final String COSMETIC_RENDER_CONTEXT = BRIDGE_PREFIX
            + "COHCCRCCIOORIRIIRIRCIHHCHIRCOH/IHCIICRRIRHOHOICHOOHHIIHICRCHO";
    private static final String ARM_MODEL = BRIDGE_PREFIX + "IHCCRCCOHRCCIORHHCROORROCICRHI/ROOCCIHRCCHORHCCOIIRIHHHIIHHHO";
    private static final String PLAYER = BRIDGE_PREFIX + "COHCCRCCIOORIRIIRIRCIHHCHIRCOH/"
            + "OCCRIICCOCOIORRORHIRHCHCOHHHHC/IHCCRCCOHRCCIORHHCROORROCICRHI/OCCRIICCOCOIORRORHIRHCHCOHHHHC";
    private static final String GUARD = "me/napim/gomod/features/LunarDisguiseCosmeticVisibility";

    public LunarDisguiseFirstPersonCosmeticsWeaveHook() {
        super(TARGET);
    }

    @Override
    public void transform(@NotNull ClassNode node, @NotNull AssemblerConfig cfg) {
        if (!TARGET.equals(node.name)) return;
        int patchedHelper = 0;
        int patchedRenderer = 0;
        for (MethodNode method : node.methods) {
            if (alreadyPatched(method)) continue;
            if (matchesHelper(method)) {
                insertGuard(method, 4, "shouldHideFirstPersonCosmetics");
                patchedHelper++;
            } else if (matchesCosmeticRenderer(method)) {
                // This overload draws the arm cosmetic model itself; suppressing only its helper
                // still lets this caller continue into Lunar's model/texture render calls.
                insertGuard(method, 5, "shouldHideFirstPersonCosmeticRenderer");
                patchedRenderer++;
            }
        }
        if (patchedHelper > 0 || patchedRenderer > 0) {
            cfg.computeFrames();
            System.out.println("[gomod] Lunar disguise first-person guards installed (helper="
                    + patchedHelper + " renderer=" + patchedRenderer + ")");
        }
    }

    private static void insertGuard(MethodNode method, int playerSlot, String predicate) {
        LabelNode renderCosmetic = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ALOAD, playerSlot));
        guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GUARD,
                predicate, "(Ljava/lang/Object;)Z", false));
        guard.add(new JumpInsnNode(Opcodes.IFEQ, renderCosmetic));
        guard.add(new InsnNode(Opcodes.RETURN));
        guard.add(renderCosmetic);
        method.instructions.insert(guard);
    }

    private static boolean matchesHelper(MethodNode method) {
        if (!METHOD.equals(method.name) || (method.access & Opcodes.ACC_STATIC) == 0
                || !Type.getReturnType(method.desc).equals(Type.VOID_TYPE)) return false;
        Type[] parameters = Type.getArgumentTypes(method.desc);
        return parameters.length == 7
                && parameters[0].equals(Type.getObjectType(MATRIX))
                && parameters[1].equals(Type.FLOAT_TYPE)
                && parameters[2].equals(Type.getObjectType(ARM_MODEL))
                && parameters[3].equals(Type.BOOLEAN_TYPE)
                && parameters[4].equals(Type.getObjectType(PLAYER))
                && parameters[5].equals(Type.BOOLEAN_TYPE)
                && parameters[6].equals(Type.BOOLEAN_TYPE);
    }

    private static boolean matchesCosmeticRenderer(MethodNode method) {
        if (!METHOD.equals(method.name) || (method.access & Opcodes.ACC_STATIC) == 0
                || !Type.getReturnType(method.desc).equals(Type.VOID_TYPE)) return false;
        Type[] parameters = Type.getArgumentTypes(method.desc);
        return parameters.length == 8
                && parameters[0].equals(Type.getObjectType(COSMETIC_RENDER_MATRIX))
                && parameters[1].equals(Type.getObjectType(COSMETIC_RENDER_CONTEXT))
                && parameters[2].equals(Type.INT_TYPE)
                && parameters[3].equals(Type.getObjectType(ARM_MODEL))
                && parameters[4].equals(Type.BOOLEAN_TYPE)
                && parameters[5].equals(Type.getObjectType(PLAYER))
                && parameters[6].equals(Type.BOOLEAN_TYPE)
                && parameters[7].equals(Type.BOOLEAN_TYPE);
    }

    private static boolean alreadyPatched(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode && GUARD.equals(((MethodInsnNode) instruction).owner)) {
                return true;
            }
        }
        return false;
    }
}
