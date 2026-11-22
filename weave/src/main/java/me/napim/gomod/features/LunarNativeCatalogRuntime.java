package me.napim.gomod.features;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Reads verified constructor argument roles instead of renamed native accessors. */
public final class LunarNativeCatalogRuntime {
    private LunarNativeCatalogRuntime() { }
    public static Constructor<?> companionConstructor(Class<?> type) throws Exception {
        Constructor<?> match = null;
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            Class<?>[] p = constructor.getParameterTypes();
            if (p.length != 16 || p[0] != int.class || p[1] != String.class || p[2] != String.class
                    || p[3] != String.class || p[4] != String.class || p[5] != String.class || p[6] != Integer.class
                    || p[7] != boolean.class || p[8] != boolean.class || p[9] != boolean.class || !p[10].isEnum()
                    || p[11] != boolean.class || p[12] != List.class || p[13] != List.class
                    || p[14] != Date.class || p[15] != boolean.class) continue;
            if (match != null) throw new NoSuchMethodException("Ambiguous native cosmetic constructor");
            match = constructor;
        }
        if (match == null) throw new NoSuchMethodException("Unsupported native cosmetic constructor");
        match.setAccessible(true); return match;
    }
    private static final ClassValue<Map<Integer, Field>> ROLES = new ClassValue<Map<Integer, Field>>() {
        protected Map<Integer, Field> computeValue(Class<?> type) {
            try {
                ClassNode node = LunarColorRuntime.readClass(type); MethodNode constructor = null;
                for (MethodNode method : node.methods) {
                    if (!method.name.equals("<init>")) continue;
                    Type[] args = Type.getArgumentTypes(method.desc);
                    boolean cosmetic = args.length == 16 && args[0].equals(Type.INT_TYPE) && args[1].getDescriptor().equals("Ljava/lang/String;");
                    boolean spray = args.length == 12 && method.desc.endsWith(";IFFFFZZZI)V");
                    if (!cosmetic && !spray) continue;
                    if (constructor != null) throw new IllegalStateException("Ambiguous catalog constructor"); constructor = method;
                }
                if (constructor == null) throw new IllegalStateException("Unsupported catalog constructor");
                Type[] args = Type.getArgumentTypes(constructor.desc); Map<Integer, Integer> slots = new HashMap<Integer, Integer>();
                for (int i = 0, slot = 1; i < args.length; i++) { slots.put(Integer.valueOf(slot), Integer.valueOf(i)); slot += args[i].getSize(); }
                Map<Integer, Field> result = new HashMap<Integer, Field>();
                for (AbstractInsnNode insn : constructor.instructions) if (insn instanceof FieldInsnNode && insn.getOpcode() == Opcodes.PUTFIELD) {
                    FieldInsnNode put = (FieldInsnNode) insn; AbstractInsnNode value = previous(insn), receiver = previous(value);
                    if (!put.owner.equals(node.name) || !(value instanceof VarInsnNode) || !(receiver instanceof VarInsnNode)
                            || receiver.getOpcode() != Opcodes.ALOAD || ((VarInsnNode) receiver).var != 0) continue;
                    Integer role = slots.get(Integer.valueOf(((VarInsnNode) value).var));
                    if (role == null || !put.desc.equals(args[role.intValue()].getDescriptor())) continue;
                    Field field = type.getDeclaredField(put.name); field.setAccessible(true);
                    if (result.put(role, field) != null) throw new IllegalStateException("Ambiguous catalog argument assignment");
                }
                if (!result.containsKey(0) || !result.containsKey(1)
                        || (args.length == 16 && !result.containsKey(2))) throw new IllegalStateException("Missing catalog argument roles");
                return result;
            } catch (Exception error) { throw new IllegalStateException("Native catalog layout unavailable", error); }
        }
    };
    public static Object value(Object item, int role) throws Exception {
        Field field = ROLES.get(item.getClass()).get(Integer.valueOf(role));
        if (field == null) throw new NoSuchFieldException("Native catalog role " + role);
        return field.get(item);
    }
    private static AbstractInsnNode previous(AbstractInsnNode insn) {
        if (insn == null) return null;
        do { insn = insn.getPrevious(); } while (insn != null && insn.getOpcode() < 0); return insn;
    }
}
