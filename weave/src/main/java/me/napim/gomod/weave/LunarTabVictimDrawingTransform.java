package me.napim.gomod.weave;

import me.napim.gomod.features.LunarConnectionDiagnostics;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Patches Lunar's 1.8.9 TAB mixin before Lunar merges its row helpers into GuiPlayerTabOverlay. */
public final class LunarTabVictimDrawingTransform {
    public static final String TARGET = "com/moonsworth/lunar/legacy/"
            + "CICOHIIRRHHIIHRROHOICOCRHHHHOO/"
            + "IHHHICHHRICRCIOHCIOHHOHIOIORRH/"
            + "IHHHICHHRICRCIOHCIOHHOHIOIORRH/"
            + "IHHHICHHRICRCIOHCIOHHOHIOIORRH/"
            + "IHHCCCCRCOHRCRROIIIIHHOOHRHRCC";
    private static final String TARGET_MAPPED = "com/moonsworth/lunar/"
            + "ORROOHICIROOHCRROHOIIOIOICRCHR/"
            + "HRROCOOIHHOCHOHHHOOICHCIOCOROI/"
            + "CHCRIRHOHCOHIHIOHIROCHCOROHROO/"
            + "HRROCOOIHHOCHOHHHOOICHCIOCOROI/"
            + "HRROCOOIHHOCHOHHHOOICHCIOCOROI/"
            + "RIHOROCRCCOICHCICCIHCHOIOHCCCO";

    private static final String KILL_COUNTER = "me/napim/gomod/features/KillCounter";
    private static final String PLAYER_INFO = "Lnet/minecraft/client/network/NetworkPlayerInfo;";
    private static final String DRAW_DESC = "(Lnet/minecraft/client/gui/FontRenderer;Ljava/lang/String;FFI)I";
    // Current Lunar carries an Adventure component and its original text alongside the legacy name.
    // The first five arguments keep the same local slots as the older helper.
    private static final String COMPONENT_DRAW_DESC = "(Lnet/minecraft/client/gui/FontRenderer;Ljava/lang/String;FFI"
            + "Lcom/llamalad7/mixinextras/sugar/ref/LocalRef;"
            + "Lcom/llamalad7/mixinextras/sugar/ref/LocalRef;)I";
    private static final String WIDTH_DESC = "(Lnet/minecraft/client/gui/FontRenderer;Ljava/lang/String;"
            + "Lcom/llamalad7/mixinextras/sugar/ref/LocalBooleanRef;)I";

    private LunarTabVictimDrawingTransform() { }

    public static boolean isTarget(String name) {
        return TARGET.equals(name) || TARGET_MAPPED.equals(name);
    }

    /** Lunar changes these obfuscated owner names between rebuilds; the injected method names remain discoverable. */
    public static boolean mayContainRenderer(String name, byte[] bytes) {
        if (bytes == null || (!isTarget(name) && !isRendererOwner(name))) return false;
        return contains(bytes, "lunar$onDrawString")
                || contains(bytes, "impl$onDrawString")
                || contains(bytes, "lunar$onGetPlayerName")
                || contains(bytes, "lunar$render$getPlayerInfo");
    }

    /** Check already-loaded Lunar classes so startup timing does not hide the active renderer from the probe. */
    public static boolean isLoadedRenderer(Class<?> type) {
        if (type == null) return false;
        String name = type.getName().replace('.', '/');
        if (!isTarget(name) && !isRendererOwner(name)) return false;
        InputStream stream = null;
        try {
            stream = type.getResourceAsStream("/" + name + ".class");
            if (stream == null) return false;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(16 * 1024);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (bytes.size() + read > 2 * 1024 * 1024) return false;
                bytes.write(buffer, 0, read);
            }
            return mayContainRenderer(name, bytes.toByteArray());
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (stream != null) {
                try { stream.close(); } catch (Throwable ignored) { }
            }
        }
    }

    public static byte[] transform(String name, byte[] bytes) {
        if (!mayContainRenderer(name, bytes)) return null;
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            String methods = describeRendererMethods(node);
            publishDiscovery(name + " " + methods);
            LunarConnectionDiagnostics.recordOnceAfterStart("tab_victims.lunar_renderer_candidate", name, methods);
            int rows = patchRowCapture(node);
            int draws = patchDraw(node);
            int widths = patchWidths(node);
            int lists = patchLists(node);
            if (rows + draws + widths + lists == 0) {
                LunarConnectionDiagnostics.recordOnceAfterStart("tab_victims.lunar_source_renderer_skipped", name,
                        "rowMethods=" + rows + " drawMethods=" + draws + " widthMethods=" + widths
                                + " listMethods=" + lists + " " + methods);
                return null;
            }
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            LunarConnectionDiagnostics.recordOnceAfterStart("tab_victims.lunar_source_renderer_hooks", name,
                    "row_capture=" + rows + " draw=" + draws + " width=" + widths + " shared_rows=" + lists);
            return writer.toByteArray();
        } catch (Throwable error) {
            LunarConnectionDiagnostics.recordOnceAfterStart("tab_victims.lunar_source_renderer_failed", name,
                    "exception=" + error.getClass().getSimpleName());
            return null;
        }
    }

    private static boolean isRendererOwner(String name) {
        return name != null && (name.startsWith("com/moonsworth/lunar/")
                || "net/minecraft/client/gui/GuiPlayerTabOverlay".equals(name));
    }

    private static boolean contains(byte[] bytes, String value) {
        byte[] needle = value.getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i <= bytes.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (bytes[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static String describeRendererMethods(ClassNode node) {
        StringBuilder summary = new StringBuilder("class=").append(node.name).append(" methods=");
        int count = 0;
        for (MethodNode method : node.methods) {
            if (!(method.name.contains("onDrawString") || method.name.contains("GetPlayerName")
                    || method.name.contains("getPlayerName") || method.name.contains("GetStringWidth")
                    || method.name.contains("getStringWidth") || method.name.contains("getPlayerInfo"))) continue;
            if (count++ > 0) summary.append('|');
            summary.append(method.name).append(method.desc);
            if (summary.length() > 2400) {
                summary.append("...truncated");
                break;
            }
        }
        if (count == 0) summary.append("<marker-only>");
        return summary.toString();
    }

    private static void publishDiscovery(String detail) {
        try {
            System.setProperty("gomod.qa.tab_victims.renderer_discovery", detail.replace('\n', ' '));
        } catch (Throwable ignored) { }
    }

    private static int patchRowCapture(ClassNode node) {
        int patched = 0;
        for (MethodNode method : node.methods) {
            if (!(method.name.endsWith("lunar$onGetPlayerName")
                    || method.name.endsWith("lunar$onGetPlayerName$0"))
                    || !method.desc.startsWith("(Lnet/minecraft/client/gui/GuiPlayerTabOverlay;" + PLAYER_INFO)) {
                continue;
            }
            if (containsCall(method, "setCurrentTabOverlayRow")) continue;
            InsnList capture = new InsnList();
            capture.add(new VarInsnNode(Opcodes.ALOAD, 2));
            capture.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KILL_COUNTER, "setCurrentTabOverlayRow",
                    "(" + PLAYER_INFO + ")V", false));
            method.instructions.insert(capture);
            patched++;
        }
        return patched;
    }

    private static int patchDraw(ClassNode node) {
        int patched = 0;
        for (MethodNode method : node.methods) {
            if (!(method.name.endsWith("lunar$onDrawString") || method.name.endsWith("impl$onDrawString"))
                    || !(DRAW_DESC.equals(method.desc) || COMPONENT_DRAW_DESC.equals(method.desc))
                    || (method.access & Opcodes.ACC_STATIC) != 0
                    || containsCall(method, "colorCurrentTabVictimText")) {
                continue;
            }
            InsnList prepare = new InsnList();
            prepare.add(new VarInsnNode(Opcodes.ALOAD, 2));
            prepare.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KILL_COUNTER, "prepareCurrentTabVictimText",
                    "(Ljava/lang/String;)Ljava/lang/String;", false));
            prepare.add(new VarInsnNode(Opcodes.ASTORE, 2));
            prepare.add(new VarInsnNode(Opcodes.FLOAD, 3));
            prepare.add(new VarInsnNode(Opcodes.ALOAD, 2));
            prepare.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KILL_COUNTER, "offsetCurrentTabVictimTextX",
                    "(FLjava/lang/String;)F", false));
            prepare.add(new VarInsnNode(Opcodes.FSTORE, 3));
            prepare.add(new VarInsnNode(Opcodes.ILOAD, 5));
            prepare.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KILL_COUNTER, "colorCurrentTabVictimText",
                    "(I)I", false));
            prepare.add(new VarInsnNode(Opcodes.ISTORE, 5));
            method.instructions.insert(prepare);
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction.getOpcode() == Opcodes.IRETURN) {
                    method.instructions.insertBefore(instruction, new MethodInsnNode(Opcodes.INVOKESTATIC,
                            KILL_COUNTER, "clearCurrentTabOverlayRow", "()V", false));
                }
            }
            patched++;
        }
        return patched;
    }

    private static int patchWidths(ClassNode node) {
        int patched = 0;
        for (MethodNode method : node.methods) {
            if (!method.name.endsWith("lunar$onGetStringWidth") || !WIDTH_DESC.equals(method.desc)
                    || containsCall(method, "reserveCurrentTabVictimWidth")) {
                continue;
            }
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction.getOpcode() == Opcodes.IRETURN) {
                    InsnList reserve = new InsnList();
                    reserve.add(new VarInsnNode(Opcodes.ALOAD, 2));
                    reserve.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KILL_COUNTER,
                            "reserveCurrentTabVictimWidth", "(ILjava/lang/String;)I", false));
                    method.instructions.insertBefore(instruction, reserve);
                }
            }
            patched++;
        }
        return patched;
    }

    private static int patchLists(ClassNode node) {
        int patched = 0;
        for (MethodNode method : node.methods) {
            if (!method.name.endsWith("lunar$render$getPlayerInfo")
                    || !method.desc.startsWith("(Lcom/google/common/collect/Ordering;Ljava/lang/Iterable;")
                    || !method.desc.endsWith(")Ljava/util/List;")
                    || containsCall(method, "getTabOverlayRenderedSubList")) {
                continue;
            }
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if ("java/util/List".equals(call.owner) && "subList".equals(call.name)
                            && "(II)Ljava/util/List;".equals(call.desc)) {
                        method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, KILL_COUNTER,
                                "getTabOverlayRenderedSubList", "(Ljava/util/List;II)Ljava/util/List;", false));
                    }
                } else if (instruction.getOpcode() == Opcodes.ARETURN) {
                    method.instructions.insertBefore(instruction, new MethodInsnNode(Opcodes.INVOKESTATIC,
                            KILL_COUNTER, "applyTabOverlayRendererListSortFallback",
                            "(Ljava/util/List;)Ljava/util/List;", false));
                }
            }
            patched++;
        }
        return patched;
    }

    private static boolean containsCall(MethodNode method, String name) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (KILL_COUNTER.equals(call.owner) && name.equals(call.name)) return true;
            }
        }
        return false;
    }
}
