package me.napim.gomod.weave;

import me.napim.gomod.features.LunarConnectionDiagnostics;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Stack-neutral text tap, including renderers loaded before Weave installs its mixins. */
public final class LunarLeaderboardHologramTransform {
    private static final String BASE = "com/moonsworth/lunar/client/RCHORCICHCHOCCCROHOOHICROIOHRI/"
            + "RCHICCCIHRCRCHCIRRIIRRHICORHHO/HRIRRHOHCCRCCRROHIRORORRORICIH/"
            + "OOIOHHHCIICRRRCICOIIIRRRICOCIC/IHIRRHORRRRHICCOHOHORCHIHHHIOO/"
            + "CRRHRIRIHOHRHRCCHRCOICHOOIHOIC/CICOHIIRRHHIIHRROHOICOCRHHHHOO/";
    public static final String TARGET = BASE + "RCHICCCIHRCRCHCIRRIIRRHICORHHO";
    private static final String ENTRY = BASE + "CICOHIIRRHHIIHRROHOICOCRHHHHOO/"
            + "CICOHIIRRHHIIHRROHOICOCRHHHHOO$CRRHRIRIHOHRHRCCHRCOICHOOIHOIC";
    private static final String HELPER = "me/napim/gomod/features/LeaderboardHologramDebugCapture";

    private LunarLeaderboardHologramTransform() { }

    public static byte[] transform(String name, byte[] bytes) {
        if (!TARGET.equals(name) || bytes == null) return null;
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassNode node = new ClassNode(); reader.accept(node, 0);
            int matches = 0;
            MethodNode matchedMethod = null;
            AbstractInsnNode matchedField = null;
            for (MethodNode method : node.methods) {
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof MethodInsnNode && HELPER.equals(((MethodInsnNode) insn).owner)
                            && "rewriteLunarTextHologram".equals(((MethodInsnNode) insn).name)) return null;
                    if (!(insn instanceof FieldInsnNode) || insn.getOpcode() != Opcodes.GETFIELD) continue;
                    FieldInsnNode field = (FieldInsnNode) insn;
                    if (ENTRY.equals(field.owner) && "text".equals(field.name)
                            && "Ljava/lang/String;".equals(field.desc)) {
                        matches++; matchedMethod = method; matchedField = insn;
                    }
                }
            }
            // Fail closed on catalog remaps; do not hook other world labels or alter class schemas.
            if (matches != 1) {
                LunarConnectionDiagnostics.recordOnceAfterStart("disguise.lunar_hologram_native_skipped", name,
                        "textFieldMatches=" + matches);
                return null;
            }
            matchedMethod.instructions.insert(matchedField, new MethodInsnNode(Opcodes.INVOKESTATIC,
                    HELPER, "rewriteLunarTextHologram", "(Ljava/lang/String;)Ljava/lang/String;", false));
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            LunarConnectionDiagnostics.recordOnceAfterStart("disguise.lunar_hologram_native_applied", name,
                    "textFieldMatches=1 schemaChanged=false");
            return writer.toByteArray();
        } catch (Throwable error) {
            LunarConnectionDiagnostics.recordOnceAfterStart("disguise.lunar_hologram_native_failed", name,
                    "exception=" + error.getClass().getSimpleName());
            return null;
        }
    }
}
