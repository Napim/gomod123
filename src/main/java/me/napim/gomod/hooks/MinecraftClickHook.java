package me.napim.gomod.hooks;

import net.weavemc.loader.api.Hook;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * Diagnostic + Fix hook for NoHitDelay.
 * Logs EVERYTHING about the Minecraft class transformation to understand
 * what Lunar Client + Weave are actually doing.
 */
public class MinecraftClickHook extends Hook {
    public MinecraftClickHook() {
        super("net/minecraft/client/Minecraft");
        System.out.println("[gomod] MinecraftClickHook CONSTRUCTOR called! Hook registered for net/minecraft/client/Minecraft");
    }

    @Override
    public void transform(@NotNull ClassNode node, @NotNull AssemblerConfig cfg) {
        System.out.println("[gomod] ============================================");
        System.out.println("[gomod] MinecraftClickHook.transform() CALLED!");
        System.out.println("[gomod] Class name: " + node.name);
        System.out.println("[gomod] Total methods: " + node.methods.size());

        // Log ALL method names (looking for clickMouse or obfuscated variants)
        System.out.println("[gomod] --- ALL METHODS ---");
        for (MethodNode m : node.methods) {
            String info = "  " + m.name + m.desc;
            // Count BIPUSH instructions in each method
            int bipushCount = 0;
            int bipush10Count = 0;
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() == Opcodes.BIPUSH) {
                    bipushCount++;
                    if (((IntInsnNode) insn).operand == 10) {
                        bipush10Count++;
                    }
                }
            }
            if (bipushCount > 0) {
                info += " [BIPUSH:" + bipushCount + " BIPUSH10:" + bipush10Count + "]";
            }
            // Flag interesting methods
            String nameLower = m.name.toLowerCase();
            if (nameLower.contains("click") || nameLower.contains("attack") ||
                nameLower.contains("tick") || nameLower.contains("mouse") ||
                m.name.equals("func_147116_af") || m.name.equals("func_71407_l")) {
                info += " <== INTERESTING";
            }
            System.out.println("[gomod] " + info);
        }

        // Strategy 1: Find clickMouse by name
        boolean patched = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("clickMouse") || method.name.equals("func_147116_af")) {
                System.out.println("[gomod] FOUND clickMouse method: " + method.name + method.desc);
                int patchCount = 0;
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() == Opcodes.BIPUSH && ((IntInsnNode) insn).operand == 10) {
                        ((IntInsnNode) insn).operand = 0;
                        patchCount++;
                        System.out.println("[gomod] PATCHED BIPUSH 10 -> 0 in " + method.name);
                    }
                }
                if (patchCount == 0) {
                    System.out.println("[gomod] WARNING: clickMouse found but NO BIPUSH 10! Dumping instructions:");
                    int count = 0;
                    for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                        if (insn.getOpcode() == -1) continue;
                        if (count < 50) {
                            System.out.println("[gomod]   " + insnToString(insn));
                        }
                        count++;
                    }
                    System.out.println("[gomod]   (" + count + " total instructions)");
                } else {
                    patched = true;
                }
                break;
            }
        }

        // Strategy 2: If clickMouse not found, search ALL methods for BIPUSH 10 near leftClickCounter
        if (!patched) {
            System.out.println("[gomod] clickMouse not found or had no BIPUSH 10. Searching ALL methods...");
            for (MethodNode method : node.methods) {
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() == Opcodes.BIPUSH && ((IntInsnNode) insn).operand == 10) {
                        // Check if nearby instructions reference leftClickCounter
                        boolean nearClickCounter = false;
                        AbstractInsnNode check = insn;
                        for (int i = 0; i < 5 && check != null; i++) {
                            check = check.getNext();
                            if (check instanceof FieldInsnNode) {
                                FieldInsnNode fin = (FieldInsnNode) check;
                                if (fin.name.equals("leftClickCounter") || fin.name.equals("field_71429_W")) {
                                    nearClickCounter = true;
                                    break;
                                }
                            }
                        }
                        check = insn;
                        for (int i = 0; i < 5 && check != null; i++) {
                            check = check.getPrevious();
                            if (check instanceof FieldInsnNode) {
                                FieldInsnNode fin = (FieldInsnNode) check;
                                if (fin.name.equals("leftClickCounter") || fin.name.equals("field_71429_W")) {
                                    nearClickCounter = true;
                                    break;
                                }
                            }
                        }
                        if (nearClickCounter) {
                            ((IntInsnNode) insn).operand = 0;
                            patched = true;
                            System.out.println("[gomod] PATCHED BIPUSH 10 -> 0 near leftClickCounter in " + method.name);
                        }
                    }
                }
            }
        }

        if (!patched) {
            System.out.println("[gomod] COULD NOT PATCH NoHitDelay — no BIPUSH 10 near leftClickCounter found!");
        }
        System.out.println("[gomod] ============================================");
    }

    private String insnToString(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (insn instanceof FieldInsnNode) {
            FieldInsnNode f = (FieldInsnNode) insn;
            return opName(op) + " " + f.owner.substring(Math.max(0, f.owner.lastIndexOf('/') + 1)) + "." + f.name;
        } else if (insn instanceof MethodInsnNode) {
            MethodInsnNode m = (MethodInsnNode) insn;
            return opName(op) + " " + m.owner.substring(Math.max(0, m.owner.lastIndexOf('/') + 1)) + "." + m.name;
        } else if (insn instanceof IntInsnNode) {
            return opName(op) + " " + ((IntInsnNode) insn).operand;
        } else if (insn instanceof LdcInsnNode) {
            return "LDC " + ((LdcInsnNode) insn).cst;
        } else if (insn instanceof VarInsnNode) {
            return opName(op) + " " + ((VarInsnNode) insn).var;
        } else if (insn instanceof JumpInsnNode) {
            return opName(op) + " -> label";
        } else {
            return opName(op);
        }
    }

    private String opName(int op) {
        switch (op) {
            case Opcodes.ALOAD: return "ALOAD";
            case Opcodes.ILOAD: return "ILOAD";
            case Opcodes.GETFIELD: return "GETFIELD";
            case Opcodes.PUTFIELD: return "PUTFIELD";
            case Opcodes.INVOKEVIRTUAL: return "INVKV";
            case Opcodes.INVOKESPECIAL: return "INVKS";
            case Opcodes.INVOKESTATIC: return "INVKST";
            case Opcodes.BIPUSH: return "BIPUSH";
            case Opcodes.ICONST_0: return "ICONST_0";
            case Opcodes.ICONST_1: return "ICONST_1";
            case Opcodes.IF_ICMPGT: return "IF_ICMPGT";
            case Opcodes.IF_ICMPLE: return "IF_ICMPLE";
            case Opcodes.IFEQ: return "IFEQ";
            case Opcodes.IFNE: return "IFNE";
            case Opcodes.IFLE: return "IFLE";
            case Opcodes.IFGT: return "IFGT";
            case Opcodes.GOTO: return "GOTO";
            case Opcodes.RETURN: return "RETURN";
            case Opcodes.IRETURN: return "IRETURN";
            case Opcodes.IADD: return "IADD";
            case Opcodes.ISUB: return "ISUB";
            default: return "OP" + op;
        }
    }
}
