package me.napim.gomod.weave;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Adds Blitz Survival Games to Lunar's native Hypixel Stats module using structural bytecode
 * discovery (no hardcoded obfuscated class, method, or field names).
 */
public final class LunarHypixelBlitzStatsTransform {
    private static final String HELPER = "me/napim/gomod/weave/LunarHypixelBlitzStatsBridge";
    private static final String BLITZ_LABEL = "Blitz Survival Games";
    private static final String TRANSLATION_DESC = "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;";
    private static final String DETAILS_CTOR_DESC =
            "(Ljava/lang/String;Ljava/util/Set;Ljava/util/Set;Ljava/util/Set;ZZ"
                    + "Ljava/util/function/Supplier;Ljava/util/function/Supplier;)V";

    private LunarHypixelBlitzStatsTransform() { }

    /** Fast constant-pool byte prefilter for Lunar classes and test fixtures. */
    public static boolean mayContain(String className, byte[] bytes) {
        if (className == null || bytes == null || bytes.length < 32) return false;
        if (!className.startsWith("com/moonsworth/lunar/") && !className.startsWith("me/napim/")) {
            return false;
        }
        String pool = new String(bytes, StandardCharsets.ISO_8859_1);
        return isHypixelStatsBytes(pool)
                || isAbstractModBytes(pool)
                || isDetailsBuilderBytes(pool)
                || isStatsChildBytes(pool)
                || isStatsWidgetBytes(pool)
                || isStatsOptionGroupBytes(pool)
                || isModelBaseBytes(pool)
                || isBedWarsDataModelBytes(pool)
                || isMessageParserBytes(pool);
    }

    /** Retransform-time filter for classes loaded before this transformer attached. */
    public static boolean isLoadedTarget(Class<?> type) {
        if (type == null) return false;
        String name = type.getName().replace('.', '/');
        if (!name.startsWith("com/moonsworth/lunar/")) return false;
        try (InputStream in = type.getResourceAsStream("/" + name + ".class")) {
            if (in == null) return false;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            return mayContain(name, out.toByteArray());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Backwards-compatible alias for {@link #isLoadedTarget(Class)}. */
    public static boolean isTarget(String className) {
        return false;
    }

    public static byte[] transform(String className, byte[] bytes) {
        if (!mayContain(className, bytes)) return null;
        try {
            String pool = new String(bytes, StandardCharsets.ISO_8859_1);
            ClassReader reader = new ClassReader(bytes);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);

            boolean changed = false;
            if (isHypixelStatsBytes(pool) && isHypixelStatsNode(node)) {
                registerHypixelStatsMetadata(node);
                changed = patchModuleChildren(node) | patchModuleOptions(node);
            } else if (isAbstractModBytes(pool) && isAbstractModNode(node)) {
                changed = patchModuleTranslation(node) | patchBlitzModuleMetadata(node)
                        | patchBlitzModuleDetails(node);
            } else if (isDetailsBuilderBytes(pool) && isDetailsBuilderNode(node)) {
                String setter = extractDetailsNameSetter(node);
                if (setter != null) {
                    LunarHypixelBlitzStatsBridge.registerDetailsNameSetter(setter);
                }
                return null;
            } else if (isStatsChildBytes(pool) && isStatsChildNode(node)) {
                registerStatsChildMetadata(node);
                changed = patchBlitzHudSections(node);
            } else if (isModelBaseBytes(pool) && isModelBaseNode(node)) {
                registerModelBaseMetadata(node);
                changed = patchBlitzGamePresence(node) | patchBlitzNativeGameStart(node);
            } else if (isBedWarsDataModelBytes(pool) && isBedWarsDataModelNode(node)) {
                LunarHypixelBlitzStatsBridge.registerDataModelClass(node.name);
                changed = patchBlitzDataMode(node) | patchBlitzSoloElimination(node)
                        | patchBlitzSkipArmorStart(node) | patchBlitzCoinResults(node);
            } else if (isStatsWidgetBytes(pool) && isStatsWidgetNode(node)) {
                changed = patchBlitzChildLabel(node);
            } else if (isStatsOptionGroupBytes(pool) && isStatsOptionGroupNode(node)) {
                LunarHypixelBlitzStatsBridge.registerStatsOptionGroupClass(node.name);
                changed = patchBlitzStatOptions(node);
            } else if (isMessageParserBytes(pool) && isMessageParserNode(node)) {
                changed = patchBlitzMessageParser(node);
            }

            if (!changed) return null;
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable failure) {
            System.err.println("[gomod] Lunar Hypixel Blitz Stats transform failed for " + className + ": "
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            return null;
        }
    }

    /** Lazy ASM lookup for {@code ModDetails$Builder} name setter if it loaded before retransform. */
    static String discoverDetailsNameSetter(Class<?> builderClass) {
        if (builderClass == null) return null;
        String internal = builderClass.getName().replace('.', '/');
        try (InputStream in = builderClass.getResourceAsStream("/" + internal + ".class")) {
            if (in == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return extractDetailsNameSetter(node);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // --- Fast byte-pool prefilters ---

    private static boolean isHypixelStatsBytes(String pool) {
        return pool.contains("hypixelStatsMigration")
                && pool.contains("BedWars")
                && pool.contains("MurderMystery");
    }

    private static boolean isAbstractModBytes(String pool) {
        return pool.contains("icons/features/")
                && pool.contains("aliases")
                && pool.contains("categories")
                && pool.contains("Apollo broadcast failed");
    }

    private static boolean isDetailsBuilderBytes(String pool) {
        return pool.contains(DETAILS_CTOR_DESC)
                && pool.contains("Ljava/util/function/Supplier;");
    }

    private static boolean isStatsChildBytes(String pool) {
        return pool.contains("sessionClock")
                && pool.contains("sessionScope")
                && pool.contains("dividerColor")
                && pool.contains("headingColor")
                && pool.contains("statColor");
    }

    private static boolean isStatsWidgetBytes(String pool) {
        return !pool.contains("backgroundColor")
                && pool.contains("TOP_RIGHT")
                && pool.contains(" / ")
                && pool.contains("()Ljava/lang/String;");
    }

    private static boolean isStatsOptionGroupBytes(String pool) {
        return pool.contains("registered")
                && pool.contains("isPersistent")
                && pool.contains("()Ljava/util/List;");
    }

    private static boolean isModelBaseBytes(String pool) {
        return pool.contains("leave")
                && pool.contains("()V")
                && pool.contains("Ljava/util/regex/Pattern;");
    }

    private static boolean isBedWarsDataModelBytes(String pool) {
        return pool.contains("You have been eliminated!")
                && pool.contains("^\\+(\\d+) tokens!")
                && pool.contains("BEDWARS");
    }

    private static boolean isMessageParserBytes(String pool) {
        return pool.contains("FINAL KILL!")
                && pool.contains("^BED DESTRUCTION > Your Bed .*");
    }

    // --- Precise ClassNode classifiers ---

    private static boolean isHypixelStatsNode(ClassNode node) {
        return findOptionsMethod(node) != null;
    }

    private static boolean isAbstractModNode(ClassNode node) {
        if ((node.access & Opcodes.ACC_ABSTRACT) == 0) return false;
        boolean hasTranslate = false;
        boolean hasMetadata = false;
        for (MethodNode method : node.methods) {
            if (TRANSLATION_DESC.equals(method.desc)) hasTranslate = true;
            if ("()Lcom/google/gson/JsonElement;".equals(method.desc) && containsString(method, "name")) {
                hasMetadata = true;
            }
        }
        return hasTranslate && hasMetadata;
    }

    private static boolean isDetailsBuilderNode(ClassNode node) {
        return extractDetailsNameSetter(node) != null;
    }

    private static boolean isStatsChildNode(ClassNode node) {
        boolean hasSectionsGetter = false;
        for (MethodNode method : node.methods) {
            if (method.desc.contains("Lcom/google/gson/JsonObject;")) return false;
            if ("()Ljava/util/List;".equals(method.desc)
                    && (method.access & Opcodes.ACC_PUBLIC) != 0) {
                hasSectionsGetter = true;
            }
        }
        return hasSectionsGetter;
    }

    private static boolean isStatsWidgetNode(ClassNode node) {
        return findWidgetChildField(node) != null;
    }

    private static boolean isStatsOptionGroupNode(ClassNode node) {
        if ((node.access & Opcodes.ACC_ABSTRACT) == 0) return false;
        boolean hasIsPersistent = false;
        boolean hasGet = false;
        for (MethodNode method : node.methods) {
            if ("isPersistent".equals(method.name) && "()Z".equals(method.desc)) hasIsPersistent = true;
            if ("get".equals(method.name) && "()Ljava/util/List;".equals(method.desc)
                    && (method.access & Opcodes.ACC_ABSTRACT) == 0) {
                hasGet = true;
            }
        }
        return hasIsPersistent && hasGet;
    }

    private static boolean isModelBaseNode(ClassNode node) {
        if ((node.access & Opcodes.ACC_ABSTRACT) == 0) return false;
        for (MethodNode method : node.methods) {
            if ("leave".equals(method.name) && "()V".equals(method.desc)) return true;
        }
        return false;
    }

    private static boolean isBedWarsDataModelNode(ClassNode node) {
        return findEliminationChatMethod(node) != null;
    }

    private static boolean isMessageParserNode(ClassNode node) {
        return findMessageClassifier(node) != null;
    }

    // --- Runtime metadata registration ---

    private static void registerHypixelStatsMetadata(ClassNode node) {
        String gameIdField = null;
        String inLobbyField = null;
        for (MethodNode method : node.methods) {
            if (!containsString(method, "lobby")) continue;
            boolean sawLobby = false;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof LdcInsnNode && "lobby".equals(((LdcInsnNode) insn).cst)) {
                    sawLobby = true;
                }
                if (insn instanceof FieldInsnNode && insn.getOpcode() == Opcodes.PUTFIELD) {
                    FieldInsnNode put = (FieldInsnNode) insn;
                    if (!node.name.equals(put.owner)) continue;
                    if (gameIdField == null && "Ljava/lang/String;".equals(put.desc)) {
                        gameIdField = put.name;
                    } else if (sawLobby && inLobbyField == null && "Z".equals(put.desc)) {
                        inLobbyField = put.name;
                    }
                }
            }
            if (gameIdField != null || inLobbyField != null) break;
        }
        LunarHypixelBlitzStatsBridge.registerHypixelStatsMetadata(node.name, gameIdField, inLobbyField);
    }

    private static void registerStatsChildMetadata(ClassNode node) {
        String parentDesc = null;
        for (MethodNode method : node.methods) {
            if (!"<init>".equals(method.name)) continue;
            if (!method.desc.endsWith("Ljava/util/function/Function;)V")) continue;
            int semi = method.desc.indexOf(';');
            if (method.desc.startsWith("(L") && semi > 2) {
                parentDesc = method.desc.substring(1, semi + 1);
                break;
            }
        }
        String parentGetter = null;
        if (parentDesc != null) {
            String expectedGetterDesc = "()" + parentDesc;
            for (MethodNode method : node.methods) {
                if ((method.access & Opcodes.ACC_PUBLIC) != 0
                        && (method.access & Opcodes.ACC_STATIC) == 0
                        && expectedGetterDesc.equals(method.desc)) {
                    parentGetter = method.name;
                    break;
                }
            }
        }
        LunarHypixelBlitzStatsBridge.registerStatsChildMetadata(node.name, parentGetter);
    }

    private static void registerModelBaseMetadata(ClassNode node) {
        String modeField = null;
        String childField = null;
        for (MethodNode method : node.methods) {
            if (!"<init>".equals(method.name)) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof FieldInsnNode) || insn.getOpcode() != Opcodes.PUTFIELD) continue;
                FieldInsnNode put = (FieldInsnNode) insn;
                if (!node.name.equals(put.owner)) continue;
                AbstractInsnNode prev = previousReal(put);
                if (modeField == null && "Ljava/lang/String;".equals(put.desc)
                        && prev instanceof LdcInsnNode && "".equals(((LdcInsnNode) prev).cst)) {
                    modeField = put.name;
                } else if (childField == null && prev instanceof VarInsnNode
                        && prev.getOpcode() == Opcodes.ALOAD && ((VarInsnNode) prev).var == 1
                        && put.desc.startsWith("L")
                        && !"Ljava/lang/String;".equals(put.desc)
                        && !"Ljava/util/List;".equals(put.desc)
                        && !put.desc.contains("$")) {
                    childField = put.name;
                }
            }
        }

        String modeGetter = null;
        if (modeField != null) {
            for (MethodNode method : node.methods) {
                if ((method.access & Opcodes.ACC_PUBLIC) == 0 || !"()Ljava/lang/String;".equals(method.desc)) {
                    continue;
                }
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (insn instanceof FieldInsnNode && insn.getOpcode() == Opcodes.GETFIELD) {
                        FieldInsnNode get = (FieldInsnNode) insn;
                        if (node.name.equals(get.owner) && modeField.equals(get.name)) {
                            modeGetter = method.name;
                            break;
                        }
                    }
                }
                if (modeGetter != null) break;
            }
        }

        String gameIdField = null;
        for (MethodNode method : node.methods) {
            if (!"(Ljava/lang/String;)V".equals(method.desc)) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) insn;
                if ("com/google/common/base/Objects".equals(call.owner) && "equal".equals(call.name)) {
                    AbstractInsnNode prev = previousReal(call);
                    if (prev instanceof FieldInsnNode && prev.getOpcode() == Opcodes.GETFIELD) {
                        FieldInsnNode get = (FieldInsnNode) prev;
                        if (node.name.equals(get.owner) && "Ljava/lang/String;".equals(get.desc)) {
                            gameIdField = get.name;
                            break;
                        }
                    }
                }
            }
            if (gameIdField != null) break;
        }

        LunarHypixelBlitzStatsBridge.registerModelBaseMetadata(modeGetter, gameIdField, childField);
    }

    private static String extractDetailsNameSetter(ClassNode node) {
        String nameField = null;
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_STATIC) != 0) continue;
            if (!method.desc.contains(")L") || !method.desc.endsWith(";") || method.desc.contains("$")) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode) || insn.getOpcode() != Opcodes.INVOKESPECIAL) continue;
                MethodInsnNode ctor = (MethodInsnNode) insn;
                if (!"<init>".equals(ctor.name) || !DETAILS_CTOR_DESC.equals(ctor.desc)) continue;
                // Walk backwards to find the second-to-last Supplier GETFIELD (arg #7 = name, arg #8 = description).
                int supplierGetsSeen = 0;
                for (AbstractInsnNode back = previousReal(ctor); back != null; back = previousReal(back)) {
                    if (back instanceof FieldInsnNode && back.getOpcode() == Opcodes.GETFIELD
                            && node.name.equals(((FieldInsnNode) back).owner)
                            && "Ljava/util/function/Supplier;".equals(((FieldInsnNode) back).desc)) {
                        supplierGetsSeen++;
                        if (supplierGetsSeen == 2) {
                            nameField = ((FieldInsnNode) back).name;
                            break;
                        }
                    }
                }
                if (nameField != null) break;
            }
            if (nameField != null) break;
        }
        if (nameField == null) return null;

        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_STATIC) != 0) continue;
            if (!method.desc.startsWith("(Ljava/util/function/Supplier;)")) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof FieldInsnNode && insn.getOpcode() == Opcodes.PUTFIELD) {
                    FieldInsnNode put = (FieldInsnNode) insn;
                    if (node.name.equals(put.owner) && nameField.equals(put.name)) {
                        return method.name;
                    }
                }
            }
        }
        return null;
    }

    // --- Bytecode patchers ---

    private static boolean patchModuleChildren(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (!"<init>".equals(method.name) || !"()V".equals(method.desc)
                    || alreadyCalls(method, "withBlitzChild")) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof FieldInsnNode) || insn.getOpcode() != Opcodes.PUTFIELD) continue;
                FieldInsnNode field = (FieldInsnNode) insn;
                if (!node.name.equals(field.owner) || !"Ljava/util/List;".equals(field.desc)) continue;
                InsnList append = new InsnList();
                append.add(new VarInsnNode(Opcodes.ALOAD, 0));
                append.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "withBlitzChild",
                        "(Ljava/util/List;Ljava/lang/Object;)Ljava/util/List;", false));
                method.instructions.insertBefore(insn, append);
                System.out.println("[gomod] Added Blitz to Lunar Hypixel Stats child list " + node.name);
                return true;
            }
        }
        return false;
    }

    private static MethodNode findOptionsMethod(ClassNode node) {
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_STATIC) != 0 || !method.desc.startsWith("()L")) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof TypeInsnNode && insn.getOpcode() == Opcodes.ANEWARRAY
                        && "java/lang/String".equals(((TypeInsnNode) insn).desc)
                        && (pushedInt(previousReal(insn)) == 5 || containsString(method, BLITZ_LABEL))) {
                    return method;
                }
            }
        }
        return null;
    }

    private static boolean patchModuleOptions(ClassNode node) {
        MethodNode method = findOptionsMethod(node);
        if (method == null || containsString(method, BLITZ_LABEL)) return false;

        AbstractInsnNode stringArray = null;
        AbstractInsnNode arraySize = null;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof TypeInsnNode) || insn.getOpcode() != Opcodes.ANEWARRAY
                    || !"java/lang/String".equals(((TypeInsnNode) insn).desc)) continue;
            AbstractInsnNode previous = previousReal(insn);
            if (pushedInt(previous) != 5) continue;
            stringArray = insn;
            arraySize = previous;
            break;
        }
        if (stringArray == null || arraySize == null) return false;

        MethodInsnNode optionsCall = null;
        for (AbstractInsnNode insn = stringArray.getNext(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode
                    && ((MethodInsnNode) insn).desc.startsWith("([Ljava/lang/String;)L")) {
                optionsCall = (MethodInsnNode) insn;
                break;
            }
        }
        if (optionsCall == null) return false;

        method.instructions.remove(arraySize);
        method.instructions.insertBefore(stringArray, new IntInsnNode(Opcodes.BIPUSH, 6));
        InsnList addOption = new InsnList();
        addOption.add(new InsnNode(Opcodes.DUP));
        addOption.add(new IntInsnNode(Opcodes.BIPUSH, 5));
        addOption.add(new LdcInsnNode(BLITZ_LABEL));
        addOption.add(new InsnNode(Opcodes.AASTORE));
        method.instructions.insertBefore(optionsCall, addOption);
        System.out.println("[gomod] Added Blitz Survival Games to Lunar Hypixel Stats mode selector");
        return true;
    }

    private static boolean patchModuleTranslation(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (!TRANSLATION_DESC.equals(method.desc) || (method.access & Opcodes.ACC_STATIC) != 0
                    || alreadyCalls(method, "translateBlitzLabel")) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn.getOpcode() != Opcodes.ARETURN) continue;
                InsnList label = new InsnList();
                label.add(new VarInsnNode(Opcodes.ALOAD, 0));
                label.add(new VarInsnNode(Opcodes.ALOAD, 1));
                label.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "translateBlitzLabel",
                        "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;", false));
                method.instructions.insertBefore(insn, label);
            }
            return true;
        }
        return false;
    }

    private static boolean patchBlitzModuleMetadata(ClassNode node) {
        boolean changed = false;
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_STATIC) != 0) continue;
            String helper = null;
            String type = null;
            if ("()Lcom/google/gson/JsonElement;".equals(method.desc) && containsString(method, "name")) {
                helper = "blitzModuleMetadata";
                type = "Lcom/google/gson/JsonElement;";
            } else if ("()Ljava/lang/String;".equals(method.desc)
                    && !"toString".equals(method.name) && callsGetName(method)) {
                helper = "blitzModuleName";
                type = "Ljava/lang/String;";
            }
            if (helper == null || alreadyCalls(method, helper)) continue;
            changed |= insertChildReturnFilter(method, helper, type);
        }
        return changed;
    }

    private static boolean callsGetName(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) insn;
                if ("getName".equals(call.name) && "()Ljava/lang/String;".equals(call.desc)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Give the custom Blitz child a native Lunar display-name supplier so Mods/Settings never fall back to "name". */
    private static boolean patchBlitzModuleDetails(ClassNode node) {
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_STATIC) != 0 || (method.access & Opcodes.ACC_ABSTRACT) != 0) continue;
            if (!method.desc.startsWith("()L") || "()Ljava/lang/String;".equals(method.desc)
                    || "()Lcom/google/gson/JsonElement;".equals(method.desc)
                    || method.desc.contains("$")
                    || alreadyCalls(method, "configureBlitzDetailsBuilder")) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode) || insn.getOpcode() != Opcodes.INVOKESTATIC) continue;
                MethodInsnNode call = (MethodInsnNode) insn;
                String returnType = call.desc.startsWith("()L") && call.desc.endsWith(";")
                        ? call.desc.substring(3, call.desc.length() - 1) : null;
                if (returnType == null || !returnType.startsWith(call.owner + "$")) continue;
                InsnList configure = new InsnList();
                configure.add(new VarInsnNode(Opcodes.ALOAD, 0));
                configure.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "configureBlitzDetailsBuilder",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
                configure.add(new TypeInsnNode(Opcodes.CHECKCAST, returnType));
                method.instructions.insert(insn, configure);
                System.out.println("[gomod] Set Lunar's native Blitz module details display name to " + BLITZ_LABEL);
                return true;
            }
        }
        return false;
    }

    private static boolean patchBlitzHudSections(ClassNode node) {
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_PUBLIC) != 0 && "()Ljava/util/List;".equals(method.desc)
                    && !alreadyCalls(method, "blitzHudSections")) {
                return insertChildReturnFilter(method, "blitzHudSections", "Ljava/util/List;");
            }
        }
        return false;
    }

    private static boolean insertChildReturnFilter(MethodNode method, String helper, String type) {
        boolean changed = false;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn.getOpcode() != Opcodes.ARETURN) continue;
            InsnList filter = new InsnList();
            filter.add(new VarInsnNode(Opcodes.ALOAD, 0));
            filter.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, helper,
                    "(" + type + "Ljava/lang/Object;)" + type, false));
            method.instructions.insertBefore(insn, filter);
            changed = true;
        }
        return changed;
    }

    private static FieldNode findWidgetChildField(ClassNode node) {
        FieldNode ctorChildField = null;
        for (MethodNode method : node.methods) {
            if (!"<init>".equals(method.name)) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof FieldInsnNode && insn.getOpcode() == Opcodes.PUTFIELD) {
                    FieldInsnNode put = (FieldInsnNode) insn;
                    AbstractInsnNode prev = previousReal(put);
                    if (node.name.equals(put.owner) && prev instanceof VarInsnNode
                            && prev.getOpcode() == Opcodes.ALOAD && ((VarInsnNode) prev).var == 1
                            && method.desc.equals("(" + put.desc + ")V")) {
                        ctorChildField = findFieldNode(node, put.name, put.desc);
                        break;
                    }
                }
            }
            if (ctorChildField != null) break;
        }
        if (ctorChildField == null) return null;
        for (MethodNode method : node.methods) {
            if (isWidgetLabelMethod(node, method, ctorChildField)) {
                return ctorChildField;
            }
        }
        return null;
    }

    private static boolean isWidgetLabelMethod(ClassNode node, MethodNode method, FieldNode childField) {
        if ((method.access & Opcodes.ACC_STATIC) != 0) return false;
        if (!method.desc.startsWith("(L") || !method.desc.endsWith(";)Ljava/lang/String;")) return false;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof FieldInsnNode && insn.getOpcode() == Opcodes.GETFIELD) {
                FieldInsnNode get = (FieldInsnNode) insn;
                if (node.name.equals(get.owner) && childField.name.equals(get.name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Supplies the custom child title where Lunar turns its child ID into HUD text. */
    private static boolean patchBlitzChildLabel(ClassNode node) {
        FieldNode childField = findWidgetChildField(node);
        if (childField == null) return false;

        for (MethodNode method : node.methods) {
            if (!isWidgetLabelMethod(node, method, childField) || alreadyCalls(method, "fixBlitzHudLabel")) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn.getOpcode() != Opcodes.ARETURN) continue;
                InsnList label = new InsnList();
                label.add(new VarInsnNode(Opcodes.ALOAD, 0));
                label.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, childField.name, childField.desc));
                label.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "fixBlitzHudLabel",
                        "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;", false));
                method.instructions.insertBefore(insn, label);
            }
            System.out.println("[gomod] Set Lunar's native Blitz HUD child label to " + BLITZ_LABEL);
            return true;
        }
        return false;
    }

    /** Filters the marked model's native option groups while leaving Lunar's other modes untouched. */
    private static boolean patchBlitzStatOptions(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (!"get".equals(method.name) || !"()Ljava/util/List;".equals(method.desc)
                    || alreadyCalls(method, "filterBlitzStats")) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn.getOpcode() != Opcodes.ARETURN) continue;
                InsnList filter = new InsnList();
                filter.add(new VarInsnNode(Opcodes.ALOAD, 0));
                filter.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "filterBlitzStats",
                        "(Ljava/util/List;Ljava/lang/Object;)Ljava/util/List;", false));
                method.instructions.insertBefore(insn, filter);
            }
            System.out.println("[gomod] Filtered Bed Wars-only options from the Blitz HUD settings groups");
            return true;
        }
        return false;
    }

    private static boolean patchBlitzDataMode(ClassNode node) {
        boolean changed = false;
        for (MethodNode method : node.methods) {
            if (!"<init>".equals(method.name)) continue;
            if (!alreadyCalls(method, "dataMode")) {
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (!(insn instanceof LdcInsnNode) || !"BEDWARS".equals(((LdcInsnNode) insn).cst)) continue;
                    method.instructions.insert(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "dataMode",
                            "(Ljava/lang/String;)Ljava/lang/String;", false));
                    changed = true;
                }
            }
            if (!alreadyCalls(method, "registerBlitzModel")) {
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (insn.getOpcode() != Opcodes.RETURN) continue;
                    InsnList mark = new InsnList();
                    mark.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    mark.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "registerBlitzModel",
                            "(Ljava/lang/Object;)V", false));
                    method.instructions.insertBefore(insn, mark);
                    changed = true;
                }
            }
        }
        if (changed) System.out.println("[gomod] Routed Lunar Blitz stats to the native HungerGames profile");
        return changed;
    }

    private static MethodNode findEliminationChatMethod(ClassNode node) {
        for (MethodNode method : node.methods) {
            if ("(Ljava/lang/String;)V".equals(method.desc)
                    && containsString(method, "You have been eliminated!")) {
                return method;
            }
        }
        return null;
    }

    /** Use Lunar's ordinary LOSS handler after it increments the local solo death. */
    private static boolean patchBlitzSoloElimination(ClassNode node) {
        MethodNode method = findEliminationChatMethod(node);
        if (method == null || alreadyCalls(method, "takeBlitzSoloLoss")) return false;

        FieldInsnNode parser = null;
        MethodInsnNode elimination = null;
        MethodInsnNode loss = null;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof TableSwitchInsnNode && loss == null) {
                TableSwitchInsnNode ts = (TableSwitchInsnNode) insn;
                if (ts.labels.size() >= 2) {
                    for (AbstractInsnNode arm = ts.labels.get(1); arm != null; arm = arm.getNext()) {
                        if (arm instanceof MethodInsnNode && "()V".equals(((MethodInsnNode) arm).desc)) {
                            loss = (MethodInsnNode) arm;
                            break;
                        }
                        if (arm instanceof JumpInsnNode || arm.getOpcode() == Opcodes.RETURN) break;
                    }
                }
            }
            if (!(insn instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) insn;
            AbstractInsnNode previous = previousReal(call);
            if (parser == null && call.desc.startsWith("(Ljava/lang/String;)L")
                    && call.desc.contains(call.owner + "$")
                    && previous instanceof VarInsnNode
                    && previousReal(previous) instanceof FieldInsnNode) {
                FieldInsnNode candidate = (FieldInsnNode) previousReal(previous);
                if (candidate.getOpcode() == Opcodes.GETFIELD
                        && ("L" + call.owner + ";").equals(candidate.desc)) {
                    parser = candidate;
                }
            } else if ("startsWith".equals(call.name) && previous instanceof LdcInsnNode
                    && "You have been eliminated!".equals(((LdcInsnNode) previous).cst)) {
                elimination = call;
            }
        }
        if (parser == null || elimination == null || loss == null) return false;

        InsnList check = new InsnList();
        check.add(new VarInsnNode(Opcodes.ALOAD, 0));
        check.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "keepNativeElimination",
                "(ZLjava/lang/Object;)Z", false));
        method.instructions.insert(elimination, check);

        AbstractInsnNode tail = method.instructions.getLast();
        while (tail != null && tail.getOpcode() < 0) tail = tail.getPrevious();
        if (tail == null || tail.getOpcode() != Opcodes.RETURN) return false;
        LabelNode resume = new LabelNode();
        InsnList finish = new InsnList();
        finish.add(new VarInsnNode(Opcodes.ALOAD, 0));
        finish.add(new FieldInsnNode(Opcodes.GETFIELD, parser.owner, parser.name, parser.desc));
        finish.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "takeBlitzSoloLoss",
                "(Ljava/lang/Object;)Z", false));
        finish.add(new JumpInsnNode(Opcodes.IFEQ, resume));
        finish.add(new VarInsnNode(Opcodes.ALOAD, 0));
        finish.add(new MethodInsnNode(loss.getOpcode(), loss.owner, loss.name, loss.desc, loss.itf));
        finish.add(resume);
        finish.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.insertBefore(tail, finish);
        return true;
    }

    /** Let the Blitz parser see coin rewards after Lunar applies the normal coin update. */
    private static boolean patchBlitzCoinResults(ClassNode node) {
        MethodNode method = findEliminationChatMethod(node);
        if (method == null || alreadyCalls(method, "isBlitzModel")) return false;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof JumpInsnNode) || insn.getOpcode() != Opcodes.IFEQ) continue;
            AbstractInsnNode check = previousReal(insn);
            if (!(check instanceof MethodInsnNode)) continue;
            MethodInsnNode startsWith = (MethodInsnNode) check;
            AbstractInsnNode prefix = previousReal(startsWith);
            if (!"java/lang/String".equals(startsWith.owner) || !"startsWith".equals(startsWith.name)
                    || !(prefix instanceof LdcInsnNode) || !"+".equals(((LdcInsnNode) prefix).cst)) continue;

            LabelNode dispatch = ((JumpInsnNode) insn).label;
            AbstractInsnNode coinReturn = previousReal(dispatch);
            AbstractInsnNode coinUpdate = coinReturn == null ? null : previousReal(coinReturn);
            if (coinReturn == null || coinReturn.getOpcode() != Opcodes.RETURN
                    || !(coinUpdate instanceof MethodInsnNode)) continue;
            MethodInsnNode update = (MethodInsnNode) coinUpdate;
            if (!node.name.equals(update.owner) || !"(Ljava/lang/String;)V".equals(update.desc)) continue;

            InsnList route = new InsnList();
            route.add(new VarInsnNode(Opcodes.ALOAD, 0));
            route.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "isBlitzModel",
                    "(Ljava/lang/Object;)Z", false));
            route.add(new JumpInsnNode(Opcodes.IFNE, dispatch));
            method.instructions.insertBefore(coinReturn, route);
            return true;
        }
        return false;
    }

    /** Bed Wars starts a game when armor is equipped; Blitz pregame lobbies can wear cosmetic suits before start. */
    private static boolean patchBlitzSkipArmorStart(ClassNode node) {
        String hasArmorMethod = null;
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_STATIC) != 0 || !"()Z".equals(method.desc)) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode
                        && "bridge$getArmorInventory".equals(((MethodInsnNode) insn).name)) {
                    hasArmorMethod = method.name;
                    break;
                }
            }
            if (hasArmorMethod != null) break;
        }
        if (hasArmorMethod == null) return false;

        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_STATIC) != 0 || !"()V".equals(method.desc)
                    || alreadyCalls(method, "isBlitzModel")) continue;
            boolean callsHasArmor = false;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode && node.name.equals(((MethodInsnNode) insn).owner)
                        && hasArmorMethod.equals(((MethodInsnNode) insn).name)
                        && "()Z".equals(((MethodInsnNode) insn).desc)) {
                    callsHasArmor = true;
                    break;
                }
            }
            if (!callsHasArmor) continue;

            LabelNode resume = new LabelNode();
            InsnList guard = new InsnList();
            guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
            guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "isBlitzModel",
                    "(Ljava/lang/Object;)Z", false));
            guard.add(new JumpInsnNode(Opcodes.IFEQ, resume));
            guard.add(new InsnNode(Opcodes.RETURN));
            guard.add(resume);
            guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            method.instructions.insert(guard);
            System.out.println("[gomod] Disabled Bed Wars armor-equipped auto-start for Blitz stats");
            return true;
        }
        return false;
    }

    private static boolean patchBlitzGamePresence(ClassNode node) {
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_PUBLIC) == 0 || (method.access & Opcodes.ACC_STATIC) != 0
                    || !"()Z".equals(method.desc) || alreadyCalls(method, "blitzGamePresence")) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn.getOpcode() != Opcodes.IRETURN) continue;
                InsnList bridge = new InsnList();
                bridge.add(new VarInsnNode(Opcodes.ALOAD, 0));
                bridge.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "blitzGamePresence",
                        "(ZLjava/lang/Object;)Z", false));
                method.instructions.insertBefore(insn, bridge);
            }
            return true;
        }
        return false;
    }

    /** Open the native session before it dispatches a real Blitz event, even without a sidebar game ID. */
    private static boolean patchBlitzNativeGameStart(ClassNode node) {
        MethodNode nativeStart = null;
        FieldInsnNode nativeState = null;
        FieldInsnNode nativeWaiting = null;
        MethodInsnNode nativeTransition = null;

        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_FINAL) == 0 || (method.access & Opcodes.ACC_STATIC) != 0
                    || !"()V".equals(method.desc)) continue;
            FieldInsnNode state = null;
            FieldInsnNode waiting = null;
            MethodInsnNode transition = null;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode) {
                    FieldInsnNode field = (FieldInsnNode) instruction;
                    if (state == null && field.getOpcode() == Opcodes.GETFIELD && node.name.equals(field.owner)
                            && field.desc.startsWith("L")) {
                        state = field;
                    }
                    if (field.getOpcode() == Opcodes.GETSTATIC && "WAITING".equals(field.name)
                            && ("L" + field.owner + ";").equals(field.desc)) {
                        waiting = field;
                    }
                } else if (instruction instanceof MethodInsnNode && waiting != null) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (node.name.equals(call.owner) && ("(" + waiting.desc + ")V").equals(call.desc)) {
                        transition = call;
                    }
                }
            }
            if (state != null && waiting != null && transition != null && state.desc.equals(waiting.desc)) {
                nativeStart = method;
                nativeState = state;
                nativeWaiting = waiting;
                nativeTransition = transition;
                break;
            }
        }
        if (nativeStart == null) return false;

        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_STATIC) != 0 || !"(Ljava/lang/String;)V".equals(method.desc)
                    || alreadyCalls(method, "shouldStartBlitzSession")) continue;
            boolean callsNativeStart = false;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode && node.name.equals(((MethodInsnNode) insn).owner)
                        && nativeStart.name.equals(((MethodInsnNode) insn).name)
                        && "()V".equals(((MethodInsnNode) insn).desc)) {
                    callsNativeStart = true;
                    break;
                }
            }
            if (!callsNativeStart) continue;

            LabelNode resume = new LabelNode();
            InsnList start = new InsnList();
            start.add(new VarInsnNode(Opcodes.ALOAD, 0));
            start.add(new VarInsnNode(Opcodes.ALOAD, 1));
            start.add(new VarInsnNode(Opcodes.ALOAD, 0));
            start.add(new FieldInsnNode(Opcodes.GETFIELD, nativeState.owner, nativeState.name, nativeState.desc));
            start.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "shouldStartBlitzSession",
                    "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Enum;)Z", false));
            start.add(new JumpInsnNode(Opcodes.IFEQ, resume));
            start.add(new VarInsnNode(Opcodes.ALOAD, 0));
            start.add(new FieldInsnNode(Opcodes.GETSTATIC, nativeWaiting.owner, nativeWaiting.name, nativeWaiting.desc));
            start.add(new MethodInsnNode(nativeTransition.getOpcode(), nativeTransition.owner,
                    nativeTransition.name, nativeTransition.desc, nativeTransition.itf));
            start.add(new VarInsnNode(Opcodes.ALOAD, 0));
            start.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, nativeStart.name, nativeStart.desc, false));
            start.add(resume);
            start.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            method.instructions.insert(start);
            return true;
        }
        return false;
    }

    private static MethodNode findMessageClassifier(ClassNode node) {
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_STATIC) == 0
                    && method.desc.startsWith("(Ljava/lang/String;)L" + node.name + "$")
                    && method.desc.endsWith(";")) {
                return method;
            }
        }
        return null;
    }

    private static boolean patchBlitzMessageParser(ClassNode node) {
        MethodNode constructor = null;
        for (MethodNode method : node.methods) {
            if ("<init>".equals(method.name) && "()V".equals(method.desc)) {
                constructor = method;
                break;
            }
        }
        MethodNode classifier = findMessageClassifier(node);
        if (constructor == null || classifier == null) return false;

        String eventEnumInternal = classifier.desc.substring(
                "(Ljava/lang/String;)L".length(), classifier.desc.length() - 1);
        LunarHypixelBlitzStatsBridge.registerEventEnumClass(eventEnumInternal);

        boolean changed = false;
        if (!alreadyCalls(constructor, "registerBlitzParser")) {
            for (AbstractInsnNode insn : constructor.instructions.toArray()) {
                if (insn.getOpcode() != Opcodes.RETURN) continue;
                InsnList mark = new InsnList();
                mark.add(new VarInsnNode(Opcodes.ALOAD, 0));
                mark.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "registerBlitzParser",
                        "(Ljava/lang/Object;)V", false));
                constructor.instructions.insertBefore(insn, mark);
            }
            changed = true;
        }

        if (!alreadyCalls(classifier, "classifyBlitz")) {
            InsnList dispatch = new InsnList();
            dispatch.add(new VarInsnNode(Opcodes.ALOAD, 0));
            dispatch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "isBlitzParser",
                    "(Ljava/lang/Object;)Z", false));
            LabelNode original = new LabelNode();
            dispatch.add(new JumpInsnNode(Opcodes.IFEQ, original));
            dispatch.add(new VarInsnNode(Opcodes.ALOAD, 0));
            dispatch.add(new VarInsnNode(Opcodes.ALOAD, 1));
            dispatch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "classifyBlitz",
                    "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false));
            dispatch.add(new TypeInsnNode(Opcodes.CHECKCAST, eventEnumInternal));
            dispatch.add(new InsnNode(Opcodes.ARETURN));
            dispatch.add(original);
            dispatch.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            classifier.instructions.insert(dispatch);
            changed = true;
        }
        if (changed) System.out.println("[gomod] Added an isolated Blitz parser to Lunar's native session stats");
        return changed;
    }

    private static FieldNode findFieldNode(ClassNode node, String name, String desc) {
        for (FieldNode field : node.fields) {
            if (name.equals(field.name) && desc.equals(field.desc)) return field;
        }
        return null;
    }

    private static boolean containsString(MethodNode method, String expected) {
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof LdcInsnNode && expected.equals(((LdcInsnNode) insn).cst)) return true;
        }
        return false;
    }

    private static int pushedInt(AbstractInsnNode insn) {
        if (insn == null) return Integer.MIN_VALUE;
        int opcode = insn.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) return opcode - Opcodes.ICONST_0;
        if (insn instanceof IntInsnNode) return ((IntInsnNode) insn).operand;
        return Integer.MIN_VALUE;
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
        AbstractInsnNode previous = insn.getPrevious();
        while (previous != null && (previous.getType() == AbstractInsnNode.LABEL
                || previous.getType() == AbstractInsnNode.LINE || previous.getType() == AbstractInsnNode.FRAME)) {
            previous = previous.getPrevious();
        }
        return previous;
    }

    private static boolean alreadyCalls(MethodNode method, String helperName) {
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode && HELPER.equals(((MethodInsnNode) insn).owner)
                    && helperName.equals(((MethodInsnNode) insn).name)) return true;
        }
        return false;
    }
}
