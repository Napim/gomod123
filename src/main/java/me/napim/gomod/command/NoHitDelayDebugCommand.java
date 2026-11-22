/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.util.EnumChatFormatting
 *  net.weavemc.loader.api.command.Command
 *  org.objectweb.asm.ClassReader
 *  org.objectweb.asm.ClassVisitor
 *  org.objectweb.asm.tree.AbstractInsnNode
 *  org.objectweb.asm.tree.ClassNode
 *  org.objectweb.asm.tree.FieldInsnNode
 *  org.objectweb.asm.tree.IntInsnNode
 *  org.objectweb.asm.tree.JumpInsnNode
 *  org.objectweb.asm.tree.LdcInsnNode
 *  org.objectweb.asm.tree.MethodInsnNode
 *  org.objectweb.asm.tree.MethodNode
 *  org.objectweb.asm.tree.TypeInsnNode
 *  org.objectweb.asm.tree.VarInsnNode
 */
package me.napim.gomod.command;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import me.napim.gomod.Main;
import me.napim.gomod.command.NoHitDelayState;
import me.napim.gomod.features.CpsLimiter;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public class NoHitDelayDebugCommand
extends Command {
    private Map<String, Integer> lastSnapshot = null;

    public NoHitDelayDebugCommand() {
        super("nohitdebug", new String[0]);
    }

    public void handle(String[] args) {
        try {
            String sub;
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                this.msg(EnumChatFormatting.RED + "Minecraft instance is null");
                return;
            }
            switch (sub = args.length > 0 ? args[0].toLowerCase() : "status") {
                case "bytecode": {
                    this.dumpBytecode(mc);
                    break;
                }
                case "dump": {
                    this.dumpAllFields(mc);
                    break;
                }
                case "set": {
                    this.manualSet(mc, args);
                    break;
                }
                case "find": {
                    this.findHitDelayField(mc);
                    break;
                }
                case "methods": {
                    this.listMethods(mc);
                    break;
                }
                case "diff": {
                    this.diffFields(mc);
                    break;
                }
                default: {
                    this.showStatus(mc);
                    break;
                }
            }
        }
        catch (Throwable e) {
            this.msg(EnumChatFormatting.RED + "Error: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void showStatus(Minecraft mc) {
        this.msg(EnumChatFormatting.GOLD + "=== NoHitDelay Debug ===");
        try {
            Field f = Minecraft.class.getDeclaredField("leftClickCounter");
            f.setAccessible(true);
            int val = f.getInt(mc);
            this.msg(EnumChatFormatting.GREEN + "leftClickCounter = " + val);
        }
        catch (Exception e) {
            this.msg(EnumChatFormatting.RED + "leftClickCounter: " + e.getMessage());
        }
        this.msg(EnumChatFormatting.AQUA + "Mixin resets: " + NoHitDelayState.resetCount + " | Overwrites: " + NoHitDelayState.overwriteCount + " | Last before=" + NoHitDelayState.lastBefore + " after=" + NoHitDelayState.lastAfter);
        double cpsLimit = CpsLimiter.getCpsLimit();
        if (cpsLimit <= 0.0) {
            this.msg(EnumChatFormatting.YELLOW + "CPS limiter: " + EnumChatFormatting.RED + "disabled");
        } else {
            this.msg(EnumChatFormatting.YELLOW + "CPS limiter: " + EnumChatFormatting.GREEN + this.trimDouble(cpsLimit) + EnumChatFormatting.YELLOW + " (" + EnumChatFormatting.WHITE + this.trimDouble(CpsLimiter.getMinimumIntervalMs()) + "ms" + EnumChatFormatting.YELLOW + " between attacks)");
            this.msg(EnumChatFormatting.AQUA + "CPS allowed: " + CpsLimiter.getAllowedAttackCount() + " | blocked: " + CpsLimiter.getBlockedAttackCount());
            this.msg(EnumChatFormatting.AQUA + "Last allowed: " + this.formatAge(CpsLimiter.getLastAllowedAttackAtMs()) + " | Last blocked: " + this.formatAge(CpsLimiter.getLastBlockedAttackAtMs()));
            if (CpsLimiter.getBlockedAttackCount() > 0) {
                this.msg(EnumChatFormatting.RED + "Last blocked remaining window: " + this.trimDouble(CpsLimiter.getLastBlockedRemainingMs()) + "ms");
            }
            this.msg(EnumChatFormatting.RED + "Note: CPS limiter blocks attackEntity separately from leftClickCounter.");
        }
        this.msg(EnumChatFormatting.AQUA + "Minecraft class: " + mc.getClass().getName());
        this.msg(EnumChatFormatting.YELLOW + "Subcommands: bytecode, dump, find, methods, diff, set");
    }

    private void dumpBytecode(Minecraft mc) {
        this.both(EnumChatFormatting.GOLD + "=== Dumping runtime bytecode ===");
        byte[] classBytes = null;
        try {
            String resName = mc.getClass().getName().replace('.', '/') + ".class";
            InputStream is = mc.getClass().getClassLoader().getResourceAsStream(resName);
            if (is != null) {
                classBytes = this.readAllBytes(is);
                is.close();
                this.both(EnumChatFormatting.GREEN + "Read " + classBytes.length + " bytes via getResourceAsStream");
            } else {
                this.both(EnumChatFormatting.YELLOW + "getResourceAsStream returned null (baked class)");
            }
        }
        catch (Exception e) {
            this.both(EnumChatFormatting.RED + "Strategy 1 failed: " + e.getMessage());
        }
        if (classBytes == null) {
            this.both(EnumChatFormatting.AQUA + "Trying Instrumentation approach...");
            classBytes = this.captureViaInstrumentation(mc.getClass());
        }
        if (classBytes == null) {
            this.both(EnumChatFormatting.RED + "Could not read class bytes. Falling back to reflection.");
            this.listMethods(mc);
            return;
        }
        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassNode classNode = new ClassNode();
            reader.accept((ClassVisitor)classNode, 0);
            this.both(EnumChatFormatting.GREEN + "Parsed " + classNode.methods.size() + " methods from " + classNode.name);
            String[] targetExact = new String[]{"clickMouse", "func_147116_af", "runTick", "func_71407_l"};
            HashSet<String> exactSet = new HashSet<String>(Arrays.asList(targetExact));
            for (Object method : classNode.methods) {
                boolean match = exactSet.contains(((MethodNode)method).name) || ((MethodNode)method).name.toLowerCase().contains("click") || ((MethodNode)method).name.toLowerCase().contains("attack");
                if (!match) continue;
                this.dumpMethod((MethodNode)method, 80);
            }
            this.both(EnumChatFormatting.GOLD + "--- Methods referencing leftClickCounter ---");
            int refCount = 0;
            block5: for (MethodNode method : classNode.methods) {
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof FieldInsnNode)) continue;
                    FieldInsnNode fin = (FieldInsnNode)insn;
                    if (!fin.name.equals("leftClickCounter") && !fin.name.equals("field_71429_W")) continue;
                    this.both(EnumChatFormatting.AQUA + "  " + method.name + method.desc + " " + (fin.getOpcode() == 180 ? "READS" : "WRITES") + " " + fin.name);
                    ++refCount;
                    continue block5;
                }
            }
            if (refCount == 0) {
                this.both(EnumChatFormatting.RED + "  NONE found!");
            }
            this.both(EnumChatFormatting.GOLD + "--- All BIPUSH 10 locations ---");
            int bipush10 = 0;
            for (MethodNode method : classNode.methods) {
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() != 16 || ((IntInsnNode)insn).operand != 10) continue;
                    ++bipush10;
                    this.both(EnumChatFormatting.YELLOW + "  BIPUSH 10 in " + method.name + method.desc);
                }
            }
            if (bipush10 == 0) {
                this.both(EnumChatFormatting.RED + "  NO BIPUSH 10 in Minecraft class!");
            }
            this.both(EnumChatFormatting.GREEN + "=== BYTECODE DUMP COMPLETE ===");
        }
        catch (Exception e) {
            this.both(EnumChatFormatting.RED + "Parse failed: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            e.printStackTrace();
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private byte[] captureViaInstrumentation(Class<?> targetClass) {
        Instrumentation inst = this.findInstrumentation();
        if (inst == null) {
            this.both(EnumChatFormatting.RED + "No Instrumentation found in any known Weave class");
            return null;
        }
        this.both(EnumChatFormatting.GREEN + "Found Instrumentation! retransform supported: " + inst.isRetransformClassesSupported());
        if (!inst.isRetransformClassesSupported()) {
            this.both(EnumChatFormatting.RED + "Retransform not supported");
            return null;
        }
        final byte[][] captured = new byte[1][];
        final String targetName = targetClass.getName().replace('.', '/');
        ClassFileTransformer capturer = new ClassFileTransformer(){

            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                if (targetName.equals(className)) {
                    captured[0] = (byte[])classfileBuffer.clone();
                    NoHitDelayDebugCommand.this.both(EnumChatFormatting.GREEN + "Captured " + classfileBuffer.length + " bytes from retransform!");
                }
                return null;
            }
        };
        try {
            inst.addTransformer(capturer, true);
            inst.retransformClasses(targetClass);
        }
        catch (Exception e) {
            this.both(EnumChatFormatting.RED + "retransformClasses failed: " + e.getMessage());
            e.printStackTrace();
        }
        finally {
            inst.removeTransformer(capturer);
        }
        return captured[0];
    }

    private Instrumentation findInstrumentation() {
        String[] candidates;
        for (String className : candidates = new String[]{"net.weavemc.loader.bootstrap.Agent", "net.weavemc.loader.bootstrap.AgentMain", "net.weavemc.loader.WeaveLoader", "net.weavemc.internals.InstrumentationAccess", "net.weavemc.loader.bootstrap.Bootstrap", "net.weavemc.loader.bootstrap.transformer.TransformerManager", "net.weavemc.loader.Loader"}) {
            try {
                Class<?> c = Class.forName(className);
                for (Field f : c.getDeclaredFields()) {
                    if (!Instrumentation.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Instrumentation inst = (Instrumentation)f.get(null);
                    if (inst == null) continue;
                    this.both(EnumChatFormatting.GREEN + "Found Instrumentation in " + className + "." + f.getName());
                    return inst;
                }
            }
            catch (ClassNotFoundException c) {
            }
            catch (Exception e) {
                this.both(EnumChatFormatting.GRAY + "Error scanning " + className + ": " + e.getMessage());
            }
        }
        this.both(EnumChatFormatting.YELLOW + "Scanning all loaded classes for Instrumentation field...");
        try {
            ClassLoader cl = NoHitDelayDebugCommand.class.getClassLoader();
            Class[] loaders = new Class[]{};
            Method findLoadedClass = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
            findLoadedClass.setAccessible(true);
            for (String tryName : new String[]{"net.weavemc.loader.WeaveLoader", "net.weavemc.loader.api.Hook", "net.weavemc.loader.bootstrap.Agent"}) {
                try {
                    Class loaded = (Class)findLoadedClass.invoke(cl, tryName);
                    if (loaded == null) continue;
                    this.both(EnumChatFormatting.GRAY + "Found loaded: " + tryName);
                    for (Field f : loaded.getDeclaredFields()) {
                        if (!Instrumentation.class.isAssignableFrom(f.getType())) continue;
                        f.setAccessible(true);
                        Instrumentation inst = (Instrumentation)f.get(null);
                        if (inst == null) continue;
                        return inst;
                    }
                }
                catch (Exception exception) {
                    // empty catch block
                }
            }
        }
        catch (Exception e) {
            this.both(EnumChatFormatting.GRAY + "Brute force scan failed: " + e.getMessage());
        }
        return null;
    }

    private byte[] readAllBytes(InputStream is) throws Exception {
        int n;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    private void dumpMethod(MethodNode method, int maxInsns) {
        this.both(EnumChatFormatting.GOLD + "--- " + method.name + method.desc + " ---");
        int count = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == -1) continue;
            if (count >= maxInsns) {
                this.both(EnumChatFormatting.GRAY + "  ... (" + method.instructions.size() + " total instructions)");
                break;
            }
            this.both(EnumChatFormatting.GRAY + "  " + this.insnToString(insn));
            ++count;
        }
    }

    private String insnToString(AbstractInsnNode insn) {
        String opName;
        int op = insn.getOpcode();
        String string = opName = op >= 0 ? this.opcodeToName(op) : "???";
        if (insn instanceof FieldInsnNode) {
            FieldInsnNode fin = (FieldInsnNode)insn;
            return opName + " " + this.shortClass(fin.owner) + "." + fin.name + ":" + this.shortDesc(fin.desc);
        }
        if (insn instanceof MethodInsnNode) {
            MethodInsnNode min = (MethodInsnNode)insn;
            return opName + " " + this.shortClass(min.owner) + "." + min.name + this.shortDesc(min.desc);
        }
        if (insn instanceof IntInsnNode) {
            return opName + " " + ((IntInsnNode)insn).operand;
        }
        if (insn instanceof LdcInsnNode) {
            return "LDC " + ((LdcInsnNode)insn).cst;
        }
        if (insn instanceof VarInsnNode) {
            return opName + " " + ((VarInsnNode)insn).var;
        }
        if (insn instanceof JumpInsnNode) {
            return opName + " -> label";
        }
        if (insn instanceof TypeInsnNode) {
            return opName + " " + this.shortClass(((TypeInsnNode)insn).desc);
        }
        return opName;
    }

    private String shortClass(String name) {
        if (name == null) {
            return "?";
        }
        int idx = name.lastIndexOf(47);
        return idx >= 0 ? name.substring(idx + 1) : name;
    }

    private String shortDesc(String desc) {
        if (desc == null) {
            return "";
        }
        return desc.length() > 60 ? desc.substring(0, 57) + "..." : desc;
    }

    private String opcodeToName(int op) {
        switch (op) {
            case 25: {
                return "ALOAD";
            }
            case 21: {
                return "ILOAD";
            }
            case 23: {
                return "FLOAD";
            }
            case 24: {
                return "DLOAD";
            }
            case 22: {
                return "LLOAD";
            }
            case 58: {
                return "ASTORE";
            }
            case 54: {
                return "ISTORE";
            }
            case 180: {
                return "GETFIELD";
            }
            case 181: {
                return "PUTFIELD";
            }
            case 178: {
                return "GETSTATIC";
            }
            case 179: {
                return "PUTSTATIC";
            }
            case 182: {
                return "INVOKEVIRTUAL";
            }
            case 183: {
                return "INVOKESPECIAL";
            }
            case 184: {
                return "INVOKESTATIC";
            }
            case 185: {
                return "INVOKEINTERFACE";
            }
            case 186: {
                return "INVOKEDYNAMIC";
            }
            case 187: {
                return "NEW";
            }
            case 177: {
                return "RETURN";
            }
            case 172: {
                return "IRETURN";
            }
            case 176: {
                return "ARETURN";
            }
            case 16: {
                return "BIPUSH";
            }
            case 17: {
                return "SIPUSH";
            }
            case 3: {
                return "ICONST_0";
            }
            case 4: {
                return "ICONST_1";
            }
            case 5: {
                return "ICONST_2";
            }
            case 6: {
                return "ICONST_3";
            }
            case 7: {
                return "ICONST_4";
            }
            case 8: {
                return "ICONST_5";
            }
            case 2: {
                return "ICONST_M1";
            }
            case 163: {
                return "IF_ICMPGT";
            }
            case 162: {
                return "IF_ICMPGE";
            }
            case 161: {
                return "IF_ICMPLT";
            }
            case 164: {
                return "IF_ICMPLE";
            }
            case 159: {
                return "IF_ICMPEQ";
            }
            case 160: {
                return "IF_ICMPNE";
            }
            case 153: {
                return "IFEQ";
            }
            case 154: {
                return "IFNE";
            }
            case 157: {
                return "IFGT";
            }
            case 156: {
                return "IFGE";
            }
            case 155: {
                return "IFLT";
            }
            case 158: {
                return "IFLE";
            }
            case 198: {
                return "IFNULL";
            }
            case 199: {
                return "IFNONNULL";
            }
            case 167: {
                return "GOTO";
            }
            case 96: {
                return "IADD";
            }
            case 100: {
                return "ISUB";
            }
            case 104: {
                return "IMUL";
            }
            case 89: {
                return "DUP";
            }
            case 87: {
                return "POP";
            }
            case 192: {
                return "CHECKCAST";
            }
            case 193: {
                return "INSTANCEOF";
            }
            case 191: {
                return "ATHROW";
            }
            case 1: {
                return "ACONST_NULL";
            }
            case 190: {
                return "ARRAYLENGTH";
            }
            case 83: {
                return "AASTORE";
            }
            case 50: {
                return "AALOAD";
            }
            case 51: {
                return "BALOAD";
            }
            case 84: {
                return "BASTORE";
            }
            case 189: {
                return "ANEWARRAY";
            }
            case 188: {
                return "NEWARRAY";
            }
            case 148: {
                return "LCMP";
            }
            case 150: {
                return "FCMPG";
            }
            case 149: {
                return "FCMPL";
            }
            case 152: {
                return "DCMPG";
            }
            case 151: {
                return "DCMPL";
            }
            case 133: {
                return "I2L";
            }
            case 134: {
                return "I2F";
            }
            case 135: {
                return "I2D";
            }
            case 136: {
                return "L2I";
            }
            case 139: {
                return "F2I";
            }
            case 142: {
                return "D2I";
            }
        }
        return "OP_" + op;
    }

    private void listMethods(Minecraft mc) {
        this.msg(EnumChatFormatting.GOLD + "=== Click/Tick/Attack methods in Minecraft ===");
        Method[] methods = mc.getClass().getDeclaredMethods();
        int count = 0;
        for (Method m : methods) {
            String name = m.getName();
            if (!name.contains("click") && !name.contains("Click") && !name.contains("attack") && !name.contains("Attack") && !name.contains("tick") && !name.contains("Tick") && !name.contains("run") && !name.contains("Run") && !name.contains("mouse") && !name.contains("Mouse") && !name.contains("delay") && !name.contains("Delay") && !name.contains("cool") && !name.contains("Cool") && !name.contains("hit") && !name.contains("Hit") && !name.contains("func_147116") && !name.contains("func_71407") && !name.contains("wrap") && !name.contains("Wrap")) continue;
            this.msg(EnumChatFormatting.AQUA + "  " + name + "(" + m.getParameterCount() + " params)");
            ++count;
        }
        this.msg(EnumChatFormatting.GREEN + "Listed " + count + " matching methods (total: " + methods.length + ")");
    }

    private void diffFields(Minecraft mc) {
        LinkedHashMap<String, Integer> current = new LinkedHashMap<String, Integer>();
        for (Class<?> c = mc.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != Integer.TYPE || Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    current.put(c.getSimpleName() + "." + f.getName(), f.getInt(mc));
                }
                catch (Exception exception) {
                    // empty catch block
                }
            }
        }
        if (this.lastSnapshot == null) {
            this.lastSnapshot = current;
            this.msg(EnumChatFormatting.GOLD + "Snapshot taken (" + current.size() + " int fields).");
            this.msg(EnumChatFormatting.YELLOW + "Now click/attack, then run /nohitdebug diff again to see changes.");
        } else {
            this.msg(EnumChatFormatting.GOLD + "=== Field Changes ===");
            int changes = 0;
            for (Map.Entry entry : current.entrySet()) {
                Integer old = this.lastSnapshot.get(entry.getKey());
                if (old == null || old.equals(entry.getValue())) continue;
                this.msg(EnumChatFormatting.GREEN + "  " + (String)entry.getKey() + ": " + old + " -> " + entry.getValue());
                ++changes;
            }
            if (changes == 0) {
                this.msg(EnumChatFormatting.RED + "No int field changes detected.");
            } else {
                this.msg(EnumChatFormatting.GREEN.toString() + changes + " fields changed.");
            }
            this.lastSnapshot = current;
        }
    }

    private void dumpAllFields(Minecraft mc) {
        this.msg(EnumChatFormatting.GOLD + "=== ALL Minecraft int fields ===");
        int count = 0;
        for (Class<?> c = mc.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            this.msg(EnumChatFormatting.AQUA + "--- " + c.getSimpleName() + " ---");
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != Integer.TYPE) continue;
                try {
                    f.setAccessible(true);
                    int val = f.getInt(mc);
                    String mods = Modifier.isStatic(f.getModifiers()) ? " [static]" : "";
                    String color = val >= 5 && val <= 15 ? EnumChatFormatting.YELLOW.toString() : EnumChatFormatting.GRAY.toString();
                    this.msg(color + "  " + f.getName() + " = " + val + mods);
                    ++count;
                }
                catch (Exception e) {
                    this.msg(EnumChatFormatting.RED + "  " + f.getName() + " = ERROR");
                }
            }
        }
        this.msg(EnumChatFormatting.GREEN + "Listed " + count + " int fields");
    }

    private void findHitDelayField(Minecraft mc) {
        this.msg(EnumChatFormatting.GOLD + "=== Finding hit delay field (value 1-15) ===");
        ArrayList<String> found = new ArrayList<String>();
        for (Class<?> c = mc.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != Integer.TYPE || Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    int val = f.getInt(mc);
                    if (val < 1 || val > 15) continue;
                    this.msg(EnumChatFormatting.GREEN + "  CANDIDATE: " + c.getSimpleName() + "." + f.getName() + " = " + val);
                    found.add(f.getName());
                }
                catch (Exception exception) {
                    // empty catch block
                }
            }
        }
        if (found.isEmpty()) {
            this.msg(EnumChatFormatting.RED + "No int fields with value 1-15 found.");
        }
    }

    private void manualSet(Minecraft mc, String[] args) {
        if (args.length < 2) {
            this.msg(EnumChatFormatting.RED + "Usage: /nohitdebug set <fieldName> [value]");
            return;
        }
        String fieldName = args[1];
        int value = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        try {
            Field f = null;
            for (Class<?> c = mc.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    f = c.getDeclaredField(fieldName);
                    break;
                }
                catch (NoSuchFieldException noSuchFieldException) {
                    continue;
                }
            }
            if (f == null) {
                this.msg(EnumChatFormatting.RED + "Field not found: " + fieldName);
                return;
            }
            f.setAccessible(true);
            int old = f.getInt(mc);
            f.setInt(mc, value);
            this.msg(EnumChatFormatting.GREEN + "Set " + fieldName + ": " + old + " -> " + value);
        }
        catch (Exception e) {
            this.msg(EnumChatFormatting.RED + "Error: " + e.getMessage());
        }
    }

    private void msg(String text) {
        Main.sendMessage(Main.CHAT_PREFIX + text);
    }

    private String formatAge(long timestampMs) {
        if (timestampMs <= 0L) {
            return "n/a";
        }
        return System.currentTimeMillis() - timestampMs + "ms ago";
    }

    private String trimDouble(double value) {
        if (Math.abs(value - Math.rint(value)) < 1.0E-9) {
            return String.format(Locale.US, "%.0f", value);
        }
        return String.format(Locale.US, "%.3f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private void both(String text) {
        Main.sendMessage(Main.CHAT_PREFIX + text);
        System.out.println("[gomod] " + text.replaceAll("§.", ""));
    }
}
