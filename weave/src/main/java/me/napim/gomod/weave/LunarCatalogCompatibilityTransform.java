package me.napim.gomod.weave;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import me.napim.gomod.features.LunarConnectionDiagnostics;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Catalog reload and spray entry hooks discovered from native assets, constructors and JSON operations. */
public final class LunarCatalogCompatibilityTransform {
    private static final String MASCOT = "me/napim/gomod/features/LunarGomodMascotCatalog";
    private static final String SPRAY = "me/napim/gomod/features/LunarCustomSpray";
    private static final String BADGE = "me/napim/gomod/features/LunarCustomBadgeCatalog";
    private static final String LOGO_SYNC = "me/napim/gomod/features/GomodLogoColorSync";
    private LunarCatalogCompatibilityTransform() { }
    public static boolean isLoadedTarget(Class<?> type) {
        String name = type.getName().replace('.', '/');
        if (!name.startsWith("com/moonsworth/lunar/")) return false;
        try (InputStream input = type.getResourceAsStream("/" + name + ".class")) {
            if (input == null) return false;
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read); byte[] bytes = output.toByteArray();
            return mayContain(name, bytes) || LunarPublicLoginTransform.mayContain(name, bytes)
                    || LunarCompanionAndConflictTransform.mayContain(name, bytes);
        } catch (Exception ignored) { return false; }
    }
    public static boolean mayContain(String name, byte[] bytes) {
        if (name == null || bytes == null || !name.startsWith("com/moonsworth/lunar/")) return false;
        String pool = new String(bytes, StandardCharsets.ISO_8859_1);
        return pool.contains("cosmetics.json") || pool.contains("Failed to register sprays from json file")
                || (pool.contains("badges.json") && pool.contains("Failed to parse badge:"))
                || (pool.contains("lunar-jit") && pool.contains("particleColor"));
    }
    public static byte[] transform(String name, byte[] bytes) {
        if (!mayContain(name, bytes)) return null;
        String constants = new String(bytes, StandardCharsets.ISO_8859_1);
        boolean badgeTarget = constants.contains("badges.json")
                && constants.contains("Failed to parse badge:");
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        for (MethodNode method : node.methods) for (AbstractInsnNode insn : method.instructions)
            if (insn instanceof MethodInsnNode && (((MethodInsnNode) insn).owner.equals(MASCOT)
                    || ((MethodInsnNode) insn).owner.equals(SPRAY)
                    || ((MethodInsnNode) insn).owner.equals(BADGE))) return null;
        int catalog = 0, sprays = 0, badges = 0, badgeSelections = 0, entry = 0;
        String discoveredBadgeType = null;
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_STATIC) == 0 && literal(method, "cosmetics.json")) {
                MethodInsnNode parse = null; String field = null;
                for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) insn;
                    if (call.owner.equals("com/google/gson/Gson") && call.name.equals("fromJson")
                            && call.desc.equals("(Ljava/io/Reader;Lcom/google/gson/reflect/TypeToken;)Ljava/lang/Object;")) {
                        if (parse != null) { parse = null; break; } parse = call;
                    }
                }
                for (FieldNode candidate : node.fields) if (candidate.signature != null
                        && candidate.signature.startsWith("Ljava/util/Map<Ljava/lang/Integer;Lcom/moonsworth/lunar/")) {
                    if (field != null) { field = null; break; } field = candidate.name;
                }
                if (parse != null && field != null) {
                    method.instructions.insert(parse, call(MASCOT, "appendToCatalog", "(Ljava/lang/Object;)Ljava/lang/Object;"));
                    for (AbstractInsnNode insn : method.instructions.toArray()) if (insn.getOpcode() == Opcodes.RETURN) {
                        InsnList patch = new InsnList(); patch.add(new VarInsnNode(Opcodes.ALOAD, 0)); patch.add(new LdcInsnNode(field));
                        patch.add(call(MASCOT, "onCatalogLoaded", "(Ljava/lang/Object;Ljava/lang/String;)V")); method.instructions.insertBefore(insn, patch);
                    }
                    catalog++;
                }
            }
            if ((method.access & Opcodes.ACC_STATIC) == 0 && literal(method, "Failed to parse badge:")) {
                MethodInsnNode badgeConstructor = null;
                MethodInsnNode resourceFactory = null;
                String resourceType = null;
                for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode) {
                    MethodInsnNode candidate = (MethodInsnNode) insn;
                    if (candidate.name.equals("<init>")
                            && candidate.desc.startsWith("(ILjava/lang/String;Ljava/lang/String;L")
                            && candidate.desc.endsWith(";Ljava/time/Instant;Z)V")) {
                        Type[] arguments = Type.getArgumentTypes(candidate.desc);
                        if (arguments.length == 7 && arguments[3].equals(arguments[4])) {
                            if (badgeConstructor == null) badgeConstructor = candidate;
                            else if (!badgeConstructor.owner.equals(candidate.owner)
                                    || !badgeConstructor.desc.equals(candidate.desc)) badgeConstructor = null;
                        }
                    }
                }
                if (badgeConstructor != null) {
                    discoveredBadgeType = badgeConstructor.owner;
                    resourceType = Type.getArgumentTypes(badgeConstructor.desc)[3].getInternalName();
                    String factoryDescriptor = "(Ljava/lang/String;Ljava/lang/String;)L" + resourceType + ";";
                    for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode) {
                        MethodInsnNode candidate = (MethodInsnNode) insn;
                        if (candidate.getOpcode() == Opcodes.INVOKESTATIC && candidate.owner.equals(resourceType)
                                && candidate.desc.equals(factoryDescriptor)) {
                            if (resourceFactory == null) resourceFactory = candidate;
                            else if (!resourceFactory.name.equals(candidate.name)) resourceFactory = null;
                        }
                    }
                }
                if (badgeConstructor != null && resourceFactory != null && resourceType != null) {
                    for (AbstractInsnNode insn : method.instructions.toArray()) if (insn.getOpcode() == Opcodes.RETURN) {
                        InsnList patch = new InsnList();
                        patch.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        patch.add(new LdcInsnNode(badgeConstructor.owner.replace('/', '.')));
                        patch.add(new LdcInsnNode(resourceType.replace('/', '.')));
                        patch.add(new LdcInsnNode(resourceFactory.name));
                        patch.add(call(BADGE, "appendToCatalog",
                                "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"));
                        method.instructions.insertBefore(insn, patch);
                        badges++;
                    }
                }
            }
            if ((method.access & Opcodes.ACC_STATIC) == 0 && literal(method, "Failed to register sprays from json file")) {
                MethodInsnNode constructor = null, factory = null; Set<String> fields = new HashSet<String>();
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof FieldInsnNode) {
                        FieldInsnNode f = (FieldInsnNode) insn;
                        if (f.owner.equals(node.name) && f.desc.equals("Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;")) fields.add(f.name);
                    }
                    if (!(insn instanceof MethodInsnNode)) continue; MethodInsnNode c = (MethodInsnNode) insn;
                    if (c.name.equals("<init>") && c.desc.startsWith("(ILjava/lang/String;L") && c.desc.endsWith(";IFFFFZZZI)V")) constructor = c;
                    if (c.getOpcode() == Opcodes.INVOKESTATIC && c.desc.startsWith("(Ljava/lang/String;)Lcom/moonsworth/lunar/")) factory = c;
                }
                if (constructor != null && factory != null && fields.size() == 1
                        && Type.getArgumentTypes(constructor.desc)[2].equals(Type.getReturnType(factory.desc))) {
                    for (AbstractInsnNode insn : method.instructions.toArray()) if (insn.getOpcode() == Opcodes.RETURN) {
                        InsnList patch = new InsnList(); patch.add(new VarInsnNode(Opcodes.ALOAD, 0)); patch.add(new LdcInsnNode(fields.iterator().next()));
                        patch.add(new LdcInsnNode(constructor.owner.replace('/', '.'))); patch.add(new LdcInsnNode(factory.owner.replace('/', '.')));
                        patch.add(new LdcInsnNode(factory.name)); patch.add(call(SPRAY, "register", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"));
                        method.instructions.insertBefore(insn, patch);
                    }
                    sprays++;
                }
            }
            if (method.name.equals("<init>") && method.desc.startsWith("(ILjava/lang/String;L") && method.desc.endsWith(";IFFFFZZZI)V")) {
                for (AbstractInsnNode insn : method.instructions.toArray()) if (insn instanceof MethodInsnNode) {
                    MethodInsnNode c = (MethodInsnNode) insn;
                    if (!c.desc.equals("(Ljava/lang/String;Ljava/lang/String;)L" + c.owner + ";")) continue;
                    InsnList patch = new InsnList(); patch.add(new InsnNode(Opcodes.SWAP));
                    patch.add(call(SPRAY, "rewriteNamespace", "(Ljava/lang/String;)Ljava/lang/String;")); patch.add(new InsnNode(Opcodes.SWAP));
                    method.instructions.insertBefore(insn, patch); entry++;
                }
            }
            if (method.desc.equals("()Lcom/google/gson/JsonElement;") && literal(method, "particleColor")) {
                for (AbstractInsnNode insn : method.instructions.toArray()) if (insn.getOpcode() == Opcodes.ARETURN) {
                    InsnList patch = new InsnList(); patch.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    patch.add(call(SPRAY, "wheelEntry", "(Lcom/google/gson/JsonElement;Ljava/lang/Object;)Lcom/google/gson/JsonElement;"));
                    method.instructions.insertBefore(insn, patch); entry++;
                }
            }
        }
        if (badgeTarget && discoveredBadgeType != null) {
            badgeSelections = hookBadgeSelectionSetter(node, discoveredBadgeType);
            LunarConnectionDiagnostics.recordOnceAfterStart("lunar_badge.structural_hook", name,
                    "selection=" + badgeSelections);
        }
        if (badgeTarget && badges == 0) {
            System.err.println("[gomod] Native badge parser candidate not hooked; parser signature did not match: " + name);
        }
        if (badgeTarget && badgeSelections == 0) {
            System.err.println("[gomod] Native badge selection setter not hooked; setter signature did not match: " + name);
        }
        if (catalog + sprays + badges + badgeSelections + entry == 0) return null;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer);
        System.out.println("[gomod] Native custom catalog hooks: cosmetics=" + catalog
                + " sprays=" + sprays + " badges=" + badges + " badgeSelection=" + badgeSelections
                + " entry=" + entry + " class=" + name);
        return writer.toByteArray();
    }
    /**
     * Structurally locates Lunar's equipped-badge setter (L&lt;Badge&gt;;)V on BadgeManager so
     * local badge capture survives Lunar obfuscation updates without hardcoded mixin targets.
     */
    private static int hookBadgeSelectionSetter(ClassNode node, String badgeType) {
        String expectedDesc = "(L" + badgeType + ";)V";
        List<MethodNode> candidates = new ArrayList<MethodNode>();
        for (MethodNode method : node.methods) {
            if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE
                    | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0) continue;
            if (!expectedDesc.equals(method.desc)) continue;
            boolean updatesProfileMap = false;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) insn;
                    if ("java/util/Map".equals(call.owner) && "computeIfPresent".equals(call.name)) {
                        updatesProfileMap = true;
                        break;
                    }
                }
            }
            if (updatesProfileMap) candidates.add(method);
        }
        if (candidates.size() != 1) return 0;
        MethodNode setter = candidates.get(0);
        for (AbstractInsnNode insn : setter.instructions) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) insn;
                if (LOGO_SYNC.equals(call.owner) && "rememberNativeBadgeSelection".equals(call.name)) {
                    return 0;
                }
            }
        }
        int hookedReturns = 0;
        for (AbstractInsnNode insn : setter.instructions.toArray()) {
            if (insn.getOpcode() != Opcodes.RETURN) continue;
            InsnList patch = new InsnList();
            patch.add(new VarInsnNode(Opcodes.ALOAD, 1));
            patch.add(call(LOGO_SYNC, "rememberNativeBadgeSelection", "(Ljava/lang/Object;)V"));
            setter.instructions.insertBefore(insn, patch);
            hookedReturns++;
        }
        return hookedReturns > 0 ? 1 : 0;
    }
    private static boolean literal(MethodNode method, String value) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof LdcInsnNode && value.equals(((LdcInsnNode) insn).cst)) return true;
            if (insn instanceof InvokeDynamicInsnNode) {
                for (Object argument : ((InvokeDynamicInsnNode) insn).bsmArgs) {
                    if (argument instanceof String) {
                        String recipe = (String) argument;
                        if (recipe.equals(value)) return true;
                        if (recipe.startsWith(value) && recipe.length() > value.length()) {
                            char separator = recipe.charAt(value.length());
                            if (Character.isWhitespace(separator) || separator == '\u0001' || separator == '\u0002') {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }
    private static MethodInsnNode call(String owner, String name, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, owner, name, descriptor, false);
    }
}
