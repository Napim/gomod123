package me.napim.gomod.weave;

import me.napim.gomod.features.LunarConnectionDiagnostics;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Patches Lunar's final labels after Genesis has added the native renderer methods. */
public final class LunarNametagDisguiseTransform {
    public static final String TARGET = "net/minecraft/client/renderer/entity/RendererLivingEntity";
    private static final String HELPER = "me/napim/gomod/features/LunarDisguiseDisplayCompatibility";
    private static final String TEXT = "Lcom/moonsworth/lunar/lib/adventure/text/TextComponent;";

    private LunarNametagDisguiseTransform() { }

    public static byte[] transform(String className, byte[] bytes) {
        if (!TARGET.equals(className) || bytes == null) return null;
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            int matched = 0;
            int patched = 0;
            int spectatorDrawGuards = 0;
            for (MethodNode method : node.methods) {
                if (!matchesLabel(method)) continue;
                matched++;
                AbstractInsnNode[] originalInstructions = method.instructions.toArray();
                boolean insertedGuard = !containsHelper(method, "shouldSuppressVisibleSpecsNativeLabel");
                if (insertedGuard) {
                    LabelNode drawAllowed = new LabelNode();
                    InsnList guard = new InsnList();
                    guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    guard.add(new VarInsnNode(Opcodes.ALOAD, 2));
                    guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER,
                            "shouldSuppressVisibleSpecsNativeLabel", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false));
                    guard.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFEQ, drawAllowed));
                    if (Type.getReturnType(method.desc).getSort() == Type.DOUBLE) {
                        // A skipped row must not reserve vertical space in the native stack.
                        guard.add(new VarInsnNode(Opcodes.DLOAD, 5));
                        guard.add(new InsnNode(Opcodes.DRETURN));
                    } else {
                        guard.add(new InsnNode(Opcodes.RETURN));
                    }
                    guard.add(drawAllowed);
                    guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                    method.instructions.insert(guard);
                    spectatorDrawGuards++;
                }
                if (containsHelper(method, "beginNametagFontScope")) {
                    if (insertedGuard) patched++;
                    continue;
                }
                InsnList rewrite = new InsnList();
                rewrite.add(new VarInsnNode(Opcodes.ALOAD, 1));
                rewrite.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER,
                        "beginNametagFontScope", "(Ljava/lang/Object;)V", false));
                rewrite.add(new VarInsnNode(Opcodes.ALOAD, 1));
                rewrite.add(new VarInsnNode(Opcodes.ALOAD, 2));
                rewrite.add(new VarInsnNode(Opcodes.DLOAD, 5));
                rewrite.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER,
                        "adjustVisibleSpecsNativeNameLabelY", "(Ljava/lang/Object;Ljava/lang/Object;D)D", false));
                rewrite.add(new VarInsnNode(Opcodes.DSTORE, 5));
                LabelNode start = new LabelNode();
                rewrite.add(start);
                if (!containsHelper(method, "rewriteNametagComponent")) {
                    rewrite.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    rewrite.add(new VarInsnNode(Opcodes.ALOAD, 2));
                    rewrite.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER,
                            "rewriteNametagComponent", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
                    rewrite.add(new TypeInsnNode(Opcodes.CHECKCAST, Type.getArgumentTypes(method.desc)[1].getInternalName()));
                    rewrite.add(new VarInsnNode(Opcodes.ASTORE, 2));
                }
                for (AbstractInsnNode instruction : originalInstructions) {
                    if (instruction.getOpcode() == Opcodes.DRETURN || instruction.getOpcode() == Opcodes.RETURN) {
                        method.instructions.insertBefore(instruction, new MethodInsnNode(Opcodes.INVOKESTATIC,
                                HELPER, "endNametagFontScope", "()V", false));
                    }
                }
                // Keep the suppression guard outside the font scope and its finally handler.
                if (insertedGuard) {
                    AbstractInsnNode guardFrame = method.instructions.getFirst();
                    while (!(guardFrame instanceof FrameNode)) guardFrame = guardFrame.getNext();
                    method.instructions.insert(guardFrame, rewrite);
                } else {
                    method.instructions.insert(rewrite);
                }
                LabelNode end = new LabelNode();
                LabelNode handler = new LabelNode();
                method.instructions.add(end);
                method.instructions.add(handler);
                // No new locals or changed native frames. The finally handler does not read any native locals.
                method.instructions.add(new FrameNode(Opcodes.F_FULL, 0, new Object[0],
                        1, new Object[]{"java/lang/Throwable"}));
                method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER,
                        "endNametagFontScope", "()V", false));
                method.instructions.add(new InsnNode(Opcodes.ATHROW));
                method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
                method.maxStack = Math.max(2, method.maxStack);
                patched++;
            }
            LunarConnectionDiagnostics.recordOnceAfterStart("disguise.lunar_native_labels_patch", className,
                    "stage=class-definition nativeLabelMethods=" + matched + " patched=" + patched
                            + " spectatorDrawGuards=" + spectatorDrawGuards
                            + " expected=2 logoFlagsPreserved=true");
            if (patched == 0) return null;
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable error) {
            LunarConnectionDiagnostics.recordOnceAfterStart("disguise.lunar_native_labels_patch_failed", className,
                    "exception=" + error.getClass().getSimpleName());
            return null;
        }
    }

    private static boolean matchesLabel(MethodNode method) {
        if (!("impl$drawLabel".equals(method.name) || "impl$renderLabelSneaking".equals(method.name))
                || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return false;
        Type[] args = Type.getArgumentTypes(method.desc);
        if (args.length != 6 || !TEXT.equals(args[1].getDescriptor())
                || !("Lnet/minecraft/entity/Entity;".equals(args[0].getDescriptor())
                || "Lnet/minecraft/entity/EntityLivingBase;".equals(args[0].getDescriptor()))
                || args[2].getSort() != Type.DOUBLE || args[3].getSort() != Type.DOUBLE
                || args[4].getSort() != Type.DOUBLE || args[5].getSort() != Type.BOOLEAN) return false;
        Type result = Type.getReturnType(method.desc);
        return "impl$drawLabel".equals(method.name)
                ? result.getSort() == Type.DOUBLE : result.getSort() == Type.VOID;
    }

    private static boolean containsHelper(MethodNode method, String name) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (HELPER.equals(call.owner) && name.equals(call.name)) return true;
            }
        }
        return false;
    }
}
