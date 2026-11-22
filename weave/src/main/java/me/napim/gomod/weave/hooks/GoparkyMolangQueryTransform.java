package me.napim.gomod.weave.hooks;

import me.napim.gomod.features.LunarConnectionDiagnostics;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.Type;

/** Replaces Lunar's hard-coded false q.is_crouching value for Goparky's local taunt. */
public final class GoparkyMolangQueryTransform {
    public static final String TARGET = "com/moonsworth/lunar/client/"
            + "IHHCCCCRCOHRCRROIIIIHHOOHRHRCC/CRRHRIRIHOHRHRCCHRCOICHOOIHOIC/"
            + "ICCRRCCIOOIRICIHHCIOROHHROOHHR/CICOHIIRRHHIIHRROHOICOCRHHHHOO";
    private static final String CROUCH_QUERY_NAME = "query.is_crouching";
    private static final String HELPER_OWNER = "me/napim/gomod/features/GoparkyBlitzReactions";

    private GoparkyMolangQueryTransform() {
    }

    public static boolean isTarget(String className) {
        return TARGET.equals(className);
    }

    /**
     * Lunar may remap the evaluator class and its generated field name. The registered
     * query string is stable, so use it to find the class and resolve the backing field.
     */
    public static boolean mayContainQuery(String className, byte[] classfileBuffer) {
        if (className == null || classfileBuffer == null) return false;
        return TARGET.equals(className)
                || containsAscii(classfileBuffer, CROUCH_QUERY_NAME);
    }

    /** Returns transformed bytes only when the verified Lunar query assignment was found. */
    public static byte[] transform(String className, byte[] classfileBuffer) {
        if (!mayContainQuery(className, classfileBuffer)) return null;

        ClassReader reader = new ClassReader(classfileBuffer);
        ClassNode target = new ClassNode();
        reader.accept(target, 0);
        String crouchVariable = findCrouchVariableField(target);
        int candidateAssignments = 0;
        int actorLocals = 0;
        int patched = 0;
        boolean alreadyPatched = false;

        for (MethodNode method : target.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                 instruction = instruction.getNext()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (HELPER_OWNER.equals(call.owner)
                        && ("shouldGoparkyActorCrouch".equals(call.name)
                        || "shouldGoparkyContextCrouch".equals(call.name))) {
                    alreadyPatched = true;
                    continue;
                }
                // The registered crouch field and adjacent false literal identify this one
                // conversion; Lunar may relocate or rename its Molang utility between builds.
                if (!("(Z)F".equals(call.desc))) continue;

                AbstractInsnNode falseValue = previousOpcode(instruction);
                AbstractInsnNode variableField = previousOpcode(falseValue);
                if (falseValue == null || falseValue.getOpcode() != Opcodes.ICONST_0
                        || !(variableField instanceof FieldInsnNode)) continue;
                FieldInsnNode variable = (FieldInsnNode) variableField;
                if (variable.getOpcode() != Opcodes.GETFIELD || !target.name.equals(variable.owner)
                        || crouchVariable == null || !crouchVariable.equals(variable.name)) continue;

                candidateAssignments++;
                int actorLocal = findCompanionActorLocal(method, falseValue);
                if (actorLocal < 0) continue;
                actorLocals++;
                InsnList query = new InsnList();
                query.add(new VarInsnNode(Opcodes.ALOAD, actorLocal));
                query.add(new VarInsnNode(Opcodes.ALOAD, 0));
                query.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "me/napim/gomod/features/GoparkyTauntQueries", "update",
                        "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
                // Lunar already resolved the real companion actor from its context and stored
                // it in a typed local. Pass that object directly; a reflective context getter
                // used an obsolete obfuscated name and silently returned false every time.
                query.add(new VarInsnNode(Opcodes.ALOAD, actorLocal));
                query.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER_OWNER,
                        "shouldGoparkyActorCrouch", "(Ljava/lang/Object;)Z", false));
                method.instructions.insertBefore(falseValue, query);
                method.instructions.remove(falseValue);
                patched++;
            }
        }

        if (patched == 0) {
            if (TARGET.equals(className) || crouchVariable != null
                    || candidateAssignments > 0 || alreadyPatched) {
                LunarConnectionDiagnostics.recordOnceAfterStart("lunar.goparky_molang_query_transform_skipped",
                        className, "crouchField=" + (crouchVariable == null ? "unresolved" : crouchVariable)
                                + " candidateAssignments=" + candidateAssignments + " actorLocals="
                                + actorLocals + " alreadyPatched=" + alreadyPatched);
            }
            return null;
        }

        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        target.accept(writer);
        LunarConnectionDiagnostics.recordOnceAfterStart("lunar.goparky_molang_query_transform_applied",
                className, "crouchField=" + crouchVariable + " candidateAssignments="
                        + candidateAssignments + " actorLocals="
                        + actorLocals + " crouchAssignments=" + patched + " callback=native-actor liveTauntScalars=true");
        return writer.toByteArray();
    }

    /** Resolve the generated query field from its constructor registration string. */
    private static String findCrouchVariableField(ClassNode target) {
        for (MethodNode method : target.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                 instruction = instruction.getNext()) {
                if (!(instruction instanceof LdcInsnNode)
                        || !CROUCH_QUERY_NAME.equals(((LdcInsnNode) instruction).cst)) continue;
                for (AbstractInsnNode next = instruction.getNext(); next != null; next = next.getNext()) {
                    if (next instanceof FieldInsnNode) {
                        FieldInsnNode field = (FieldInsnNode) next;
                        if (field.getOpcode() == Opcodes.PUTFIELD && target.name.equals(field.owner)) {
                            return field.name;
                        }
                    }
                    if (next.getOpcode() >= 0 && next.getOpcode() != Opcodes.CHECKCAST
                            && next.getOpcode() != Opcodes.DUP && next.getOpcode() != Opcodes.ALOAD
                            && next.getOpcode() != Opcodes.GETFIELD && next.getOpcode() != Opcodes.INVOKEVIRTUAL
                            && next.getOpcode() != Opcodes.INVOKEINTERFACE && next.getOpcode() != Opcodes.INVOKESTATIC) {
                        break;
                    }
                }
            }
        }
        return null;
    }

    private static boolean containsAscii(byte[] bytes, String value) {
        byte[] needle = value.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer: for (int i = 0; i <= bytes.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (bytes[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static int findCompanionActorLocal(MethodNode method, AbstractInsnNode before) {
        int actorLocal = -1;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != before;
             instruction = instruction.getNext()) {
            if (instruction == null) break;
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (!"bridge$getPosX".equals(call.name) || !"()D".equals(call.desc)
                    || !call.owner.startsWith("com/moonsworth/lunar/client/")) continue;
            AbstractInsnNode receiver = previousOpcode(call);
            if (!(receiver instanceof VarInsnNode) || receiver.getOpcode() != Opcodes.ALOAD) continue;
            int local = ((VarInsnNode) receiver).var;
            if (!hasTypedActorStore(method, call, local, call.owner)) continue;
            if (actorLocal >= 0 && actorLocal != local) return -1;
            actorLocal = local;
        }
        return actorLocal;
    }

    private static boolean hasTypedActorStore(MethodNode method, AbstractInsnNode before,
                                              int local, String actorType) {
        boolean typedStore = false;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != before;
             instruction = instruction.getNext()) {
            if (instruction == null) break;
            if (!(instruction instanceof VarInsnNode) || instruction.getOpcode() != Opcodes.ASTORE
                    || ((VarInsnNode) instruction).var != local) continue;
            AbstractInsnNode value = previousOpcode(instruction);
            typedStore = value instanceof MethodInsnNode
                    && Type.getReturnType(((MethodInsnNode) value).desc).getSort() == Type.OBJECT
                    && actorType.equals(Type.getReturnType(((MethodInsnNode) value).desc).getInternalName());
        }
        return typedStore;
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode previous = instruction == null ? null : instruction.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return previous;
    }
}
