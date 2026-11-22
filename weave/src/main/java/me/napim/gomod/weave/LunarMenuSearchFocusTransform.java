package me.napim.gomod.weave;

import me.napim.gomod.features.LunarConnectionDiagnostics;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Skips only Mods/Staff's type-to-focus fallback, after normal widget dispatch. */
public final class LunarMenuSearchFocusTransform {
    private static final String LEGACY_ROOT = "com/moonsworth/lunar/client/ORIIOCOORHRRCRCIROICOOICRIRIRO/";
    private static final String LEGACY_MENUS = LEGACY_ROOT
            + "IOIRIIRHHCCRIHCOOHHHIIIRCICCRI/IOHOHIHHHHORRCCRHCRCHCRIOIHRHH/";
    public static final String LEGACY_MODS = LEGACY_MENUS + "OIRIRHRCCIHOHRROCOHICIRIHIIHCH";
    public static final String LEGACY_STAFF = LEGACY_MENUS + "HOICRCCCCRCRRHOIOIIRCCIRRHRHRH";
    private static final String LEGACY_TEXT = LEGACY_ROOT
            + "HHICICROOCIIROOHOORRCIHOICHOIH/IOCIHHIRIROCOIROOCORCCIOOIIIRR/HHCHIRROHRIRRIHCCHHIOCHIIOHRHO";
    private static final String LEGACY_KEY_DESCRIPTOR = "(CLcom/moonsworth/lunar/IHCCRCCOHRCCIORHHCROORROCICRHI/"
            + "IROCHCCHCRCCOCOIIHRRHORIIIICCI/IOCIHHIRIROCOIROOCORCCIOOIIIRR;)V";
    private static final String LEGACY_KEY_METHOD = "IOHOHIHHHHORRCCRHCRCHCRIOIHRHH";

    private static final String CURRENT_ROOT = "com/moonsworth/lunar/client/IIHOCCORIIHOORIOHCCRHRRCCCHIHR/";
    private static final String CURRENT_MENUS = CURRENT_ROOT
            + "HHRCOOOCROHCIRHCOCRRHHIOICHIHO/CRRHRIRIHOHRHRCCHRCOICHOOIHOIC/";
    /** Both current Lunar 2.23.1-2641 Mods/Staff handlers, found by their unique key fallback. */
    public static final String CURRENT_PAGE_A = CURRENT_MENUS + "IHHCCCCRCOHRCRROIIIIHHOOHRHRCC";
    public static final String CURRENT_PAGE_B = CURRENT_MENUS + "OOIOHHHCIICRRRCICOIIIRRRICOCIC";
    private static final String CURRENT_TEXT = CURRENT_ROOT
            + "IHIRRHORRRRHICCOHOHORCHIHHHIOO/CICOHIIRRHHIIHRROHOICOCRHHHHOO/OHCHICOOCCRHOOHIRCHRCCCCRRHRHI";
    private static final String CURRENT_KEY_DESCRIPTOR = "(CLcom/moonsworth/lunar/IHHHICHHRICRCIOHCIOHHOHIOIORRH/"
            + "RCHOIOIIRCHIRRHIIHHRHOORCCIHCH/CICOHIIRRHHIIHRROHOICOCRHHHHOO;)V";
    private static final String CURRENT_KEY_METHOD = "CRRHRIRIHOHRHRCCHRCOICHOOIHOIC";
    private static final String HELPER = "me/napim/gomod/features/LunarJavaMenuFocus";

    private static final Mapping LEGACY = new Mapping("2.22.x",
            new String[]{LEGACY_MODS, LEGACY_STAFF}, LEGACY_TEXT,
            LEGACY_KEY_METHOD, LEGACY_KEY_DESCRIPTOR,
            "RCIHRCCOHIICHICRHCHOHORHIOCOCO", "HROOOOROCCICCRRRICCCOOCRCHHCHH",
            "HRHOCCRCCRHRCCRIIRROHOIIHIIOOC");
    private static final Mapping CURRENT = new Mapping("2.23.1-2641",
            new String[]{CURRENT_PAGE_A, CURRENT_PAGE_B}, CURRENT_TEXT,
            CURRENT_KEY_METHOD, CURRENT_KEY_DESCRIPTOR,
            "ROIRRRICOIHRRCHRIIHIICOORRRORC", "IHIIRCOHIORCHCICCHRICICOCOCOII",
            "CORHHROIHOIHCIIHRROCHCROCCCCCH");

    private static final class Mapping {
        final String version;
        final String[] targets;
        final String text;
        final String keyMethod;
        final String keyDescriptor;
        final String searchField;
        final String editingGetter;
        final String focusMethod;

        Mapping(String version, String[] targets, String text, String keyMethod,
                String keyDescriptor, String searchField, String editingGetter,
                String focusMethod) {
            this.version = version;
            this.targets = targets;
            this.text = text;
            this.keyMethod = keyMethod;
            this.keyDescriptor = keyDescriptor;
            this.searchField = searchField;
            this.editingGetter = editingGetter;
            this.focusMethod = focusMethod;
        }
    }

    private static final String CLIENT_PREFIX = "com/moonsworth/lunar/client/";
    private static final byte[] KEY_METHOD_DESC_MARKER = "(CLcom/moonsworth/lunar/".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private LunarMenuSearchFocusTransform() { }

    private static Mapping mappingFor(String name) {
        for (String target : CURRENT.targets) if (target.equals(name)) return CURRENT;
        for (String target : LEGACY.targets) if (target.equals(name)) return LEGACY;
        return null;
    }

    public static boolean isTarget(String name) { return mappingFor(name) != null; }

    public static boolean isLoadedTarget(Class<?> loaded) {
        if (loaded == null || !loaded.getName().startsWith("com.moonsworth.lunar.client.")) {
            return false;
        }
        String internalName = loaded.getName().replace('.', '/');
        if (isTarget(internalName)) {
            return true;
        }
        try {
            for (java.lang.reflect.Method method : loaded.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isAbstract(method.getModifiers())
                        || java.lang.reflect.Modifier.isNative(method.getModifiers())
                        || java.lang.reflect.Modifier.isStatic(method.getModifiers())
                        || method.getReturnType() != void.class) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 2 && params[0] == char.class
                        && params[1].getName().startsWith("com.moonsworth.lunar.")) {
                    return true;
                }
            }
        } catch (LinkageError ignored) {
            return false;
        }
        return false;
    }

    public static boolean mayContainCandidate(String name, byte[] bytes) {
        if (name == null || bytes == null) {
            return false;
        }
        if (isTarget(name)) {
            return true;
        }
        return name.startsWith(CLIENT_PREFIX) && indexOfBytes(bytes, KEY_METHOD_DESC_MARKER) >= 0;
    }

    public static byte[] transform(String name, byte[] bytes) {
        Mapping mapping = mappingFor(name);
        if (!mayContainCandidate(name, bytes)) return null;
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            MethodInsnNode focusCheck = null;
            MethodNode keyboard = null;
            int matches = 0;
            for (MethodNode method : node.methods) {
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_STATIC)) != 0) {
                    continue;
                }
                if (mapping != null) {
                    if (!mapping.keyMethod.equals(method.name) || !mapping.keyDescriptor.equals(method.desc)) {
                        continue;
                    }
                } else if (!method.desc.startsWith("(CLcom/moonsworth/lunar/") || !method.desc.endsWith(";)V")) {
                    continue;
                }
                for (AbstractInsnNode instruction : method.instructions) {
                    if (!(instruction instanceof MethodInsnNode) || instruction.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (HELPER.equals(call.owner)) return null;
                    if (!"()Z".equals(call.desc)) continue;
                    if (mapping != null && (!mapping.text.equals(call.owner) || !mapping.editingGetter.equals(call.name))) {
                        continue;
                    }
                    AbstractInsnNode jump = next(call);
                    if (!(jump instanceof JumpInsnNode) || jump.getOpcode() != Opcodes.IFNE) continue;
                    // Match the complete fallback: if not editing, focus true and deliver this key.
                    AbstractInsnNode a = next(jump), b = next(a), c = next(b), d = next(c);
                    AbstractInsnNode e = next(d), f = next(e), g = next(f), h = next(g), i = next(h);
                    if (!(a instanceof VarInsnNode) || a.getOpcode() != Opcodes.ALOAD || ((VarInsnNode) a).var != 0
                            || !searchField(b, name, call.owner, mapping) || c == null || c.getOpcode() != Opcodes.ICONST_1
                            || !invokeFocus(d, call.owner, mapping)
                            || !(e instanceof VarInsnNode) || e.getOpcode() != Opcodes.ALOAD || ((VarInsnNode) e).var != 0
                            || !sameField(b, f)
                            || !(g instanceof VarInsnNode) || g.getOpcode() != Opcodes.ILOAD || ((VarInsnNode) g).var != 1
                            || !(h instanceof VarInsnNode) || h.getOpcode() != Opcodes.ALOAD || ((VarInsnNode) h).var != 2
                            // Current Lunar dispatches the key back to the search widget itself;
                            // older builds dispatched it through the page. Accept both exact
                            // owners while keeping the surrounding fallback shape constrained.
                            || !invokeKey(i, call.owner, name, method.name, method.desc)
                            || next(i) != next(((JumpInsnNode) jump).label)
                            || next(i) == null || next(i).getOpcode() != Opcodes.RETURN) {
                        continue;
                    }
                    focusCheck = call;
                    keyboard = method;
                    matches++;
                }
            }
            if (matches == 0 && mapping == null) {
                return null;
            }
            LunarConnectionDiagnostics.recordOnceAfterStart("lunar.menu_search_focus.transform", name,
                    "target=" + name + " lunar=" + (mapping != null ? mapping.version : "structural")
                            + " fallbackMatches=" + matches + " expected=1 javaOnly=true");
            if (matches != 1) return null;
            // (Z)Z keeps the existing branch, frames, schema and click/normal key dispatch intact.
            keyboard.instructions.insert(focusCheck, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER,
                    "shouldSkipAutomaticSearch", "(Z)Z", false));
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable error) {
            LunarConnectionDiagnostics.recordOnceAfterStart("lunar.menu_search_focus.transform_failed", name,
                    "exception=" + error.getClass().getSimpleName());
            return null;
        }
    }

    private static boolean searchField(AbstractInsnNode n, String owner, String textOwner, Mapping mapping) {
        if (!(n instanceof FieldInsnNode) || n.getOpcode() != Opcodes.GETFIELD) return false;
        FieldInsnNode field = (FieldInsnNode) n;
        if (!owner.equals(field.owner) || !("L" + textOwner + ";").equals(field.desc)) return false;
        return mapping == null || mapping.searchField.equals(field.name);
    }

    private static boolean sameField(AbstractInsnNode first, AbstractInsnNode second) {
        if (!(first instanceof FieldInsnNode) || !(second instanceof FieldInsnNode)
                || second.getOpcode() != Opcodes.GETFIELD) return false;
        FieldInsnNode f1 = (FieldInsnNode) first;
        FieldInsnNode f2 = (FieldInsnNode) second;
        return f1.owner.equals(f2.owner) && f1.name.equals(f2.name) && f1.desc.equals(f2.desc);
    }

    private static boolean invokeFocus(AbstractInsnNode n, String textOwner, Mapping mapping) {
        if (!(n instanceof MethodInsnNode) || n.getOpcode() != Opcodes.INVOKEVIRTUAL) return false;
        MethodInsnNode call = (MethodInsnNode) n;
        if (!textOwner.equals(call.owner) || !"(Z)V".equals(call.desc)) return false;
        return mapping == null || mapping.focusMethod.equals(call.name);
    }

    private static boolean invokeKey(AbstractInsnNode n, String textOwner, String pageOwner,
                                     String methodName, String methodDesc) {
        if (!(n instanceof MethodInsnNode) || n.getOpcode() != Opcodes.INVOKEVIRTUAL) return false;
        MethodInsnNode call = (MethodInsnNode) n;
        return (textOwner.equals(call.owner) || pageOwner.equals(call.owner))
                && methodName.equals(call.name) && methodDesc.equals(call.desc);
    }

    private static int indexOfBytes(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static AbstractInsnNode next(AbstractInsnNode n) {
        if (n == null) return null;
        n = n.getNext();
        while (n != null && n.getOpcode() < 0) n = n.getNext();
        return n;
    }
}
