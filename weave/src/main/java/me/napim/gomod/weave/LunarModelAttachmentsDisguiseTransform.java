package me.napim.gomod.weave;

import me.napim.gomod.features.LunarConnectionDiagnostics;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Guards the attachment callback after Genesis has merged it into the final model renderer. */
public final class LunarModelAttachmentsDisguiseTransform {
    public static final String TARGET = "net/minecraft/client/model/ModelRenderer";
    private static final String HELPER = "me/napim/gomod/features/LunarDisguiseCosmeticVisibility";
    private static final String GUARD = "shouldHideFirstPersonModelAttachments";

    private LunarModelAttachmentsDisguiseTransform() { }

    public static byte[] transform(String className, byte[] bytes) {
        if (!TARGET.equals(className) || bytes == null) return null;
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            int matched = 0, patched = 0;
            for (MethodNode method : node.methods) {
                if (!matchesAttachmentCallback(method)) continue;
                matched++;
                if (alreadyPatched(method)) continue;
                InsnList gate = new InsnList();
                LabelNode nativeDraw = new LabelNode();
                gate.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, GUARD, "()Z", false));
                gate.add(new JumpInsnNode(Opcodes.IFEQ, nativeDraw));
                gate.add(new InsnNode(Opcodes.RETURN));
                gate.add(nativeDraw);
                gate.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                method.instructions.insert(gate);
                method.maxStack = Math.max(1, method.maxStack);
                patched++;
            }
            LunarConnectionDiagnostics.recordOnceAfterStart("disguise.lunar_attachments_patch", className,
                    "stage=class-definition attachmentHandlers=" + matched + " patched=" + patched
                            + " expected=1 baseDrawUntouched=true");
            if (patched == 0) return null;
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable error) {
            LunarConnectionDiagnostics.recordOnceAfterStart("disguise.lunar_attachments_patch_failed", className,
                    "exception=" + error.getClass().getSimpleName());
            return null;
        }
    }

    private static boolean matchesAttachmentCallback(MethodNode method) {
        if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                || !method.name.endsWith("$lunar$render")
                || !"(FLorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V".equals(method.desc)) return false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                if (field.getOpcode() == Opcodes.GETFIELD && TARGET.equals(field.owner)
                        && "lunar$attachedRenderers".equals(field.name)
                        && "Ljava/util/List;".equals(field.desc)) return true;
            }
        }
        return false;
    }

    private static boolean alreadyPatched(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (HELPER.equals(call.owner) && GUARD.equals(call.name) && "()Z".equals(call.desc)) return true;
            }
        }
        return false;
    }
}
