package me.napim.gomod.weave;

import me.napim.gomod.features.LunarConnectionDiagnostics;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/** Current Lunar TAB display cache; native nametags have a separate profile lookup. */
public final class LunarDisguiserBadgeTransform {
    public static final String TARGET = "com/moonsworth/lunar/client/CRICRCRHRRICCICRHICIIIOHRIRHRH/"
            + "HHICICROOCIIROOHOORRCIHOICHOIH/CRICHRCCORIIORCCCORCHCOCIROHHC";
    public static final String DISPLAY_PROFILE = TARGET + "$IOHOHIHHHHORRCCRHCRCHCRIOIHRHH";
    private static final String NATIVE_PROFILE = "com/moonsworth/lunar/client/CRICRCRHRRICCICRHICIIIOHRIRHRH/"
            + "HHICICROOCIIROOHOORRCIHOICHOIH/OIRIRHRCCIHOHRROCOHICIRIHIIHCH$IOHOHIHHHHORRCCRHCRCHCRIOIHRHH";
    // Current Lunar TAB-logo manager and compact display record, verified in lunar.jar.
    public static final String CURRENT_TARGET =
            "com/moonsworth/lunar/client/OCOHHCCOROOHIHHRRIIHRHCIRIHIRI/"
                    + "OIHCRCHCIHORRCCIHOCIOHHHHCCRCH/HOCCCIHCOIHOHHIRROHOOHORHHHRHH";
    private static final String CURRENT_DISPLAY_PROFILE = CURRENT_TARGET
            + "$CRHOIHOORHICOCOCCCCRRIRICCIRCR";
    private static final String CURRENT_NATIVE_PROFILE =
            "com/moonsworth/lunar/client/OCOHHCCOROOHIHHRRIIHRHCIRIHIRI/"
                    + "OIHCRCHCIHORRCCIHOCIOHHHHCCRCH/IORRHRCOOIOROOIIOROHHCIHRCCOIC"
                    + "$CRHOIHOORHICOCOCCCCRRIRICCIRCR";
    private static final String CURRENT_LOOKUP = "RRHCCOCOHHOCHHOHIHROHRCCRHROHH";
    private static final String CURRENT_PRESENCE = "RIORRHCORICCIOICRHROHHRCHROOCO";
    private static final String HELPER = "me/napim/gomod/features/LunarDisguiserBadge";
    private LunarDisguiserBadgeTransform() { }

    public static boolean isTarget(String className) {
        return TARGET.equals(className) || CURRENT_TARGET.equals(className);
    }

    public static byte[] transform(String className, byte[] bytes) {
        boolean current = CURRENT_TARGET.equals(className);
        if ((!current && !TARGET.equals(className)) || bytes == null) return null;
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassNode node = new ClassNode(); reader.accept(node, 0);
            MethodNode lookup = null;
            for (MethodNode method : node.methods) {
                String profile = current ? CURRENT_DISPLAY_PROFILE : DISPLAY_PROFILE;
                if ((current ? CURRENT_LOOKUP.equals(method.name)
                        : "RROIORIIRHIOOOIHIOHCCIOIHHROOH".equals(method.name))
                        && ("(Ljava/util/UUID;)L" + profile + ";").equals(method.desc)
                        && (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) == 0) {
                    lookup = method;
                }
            }
            if (lookup == null) return null;
            boolean nativeProfile = false, cachedProfile = false, nativeGet = false, cachedGet = false;
            int returns = 0;
            for (AbstractInsnNode instruction : lookup.instructions) {
                if (instruction.getOpcode() == Opcodes.ARETURN) returns++;
                if (instruction instanceof TypeInsnNode && instruction.getOpcode() == Opcodes.CHECKCAST) {
                    String type = ((TypeInsnNode) instruction).desc;
                    nativeProfile |= (current ? CURRENT_NATIVE_PROFILE : NATIVE_PROFILE).equals(type);
                    cachedProfile |= (current ? CURRENT_DISPLAY_PROFILE : DISPLAY_PROFILE).equals(type);
                }
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (HELPER.equals(call.owner)) return null;
                    nativeGet |= "java/util/Map".equals(call.owner) && "get".equals(call.name);
                    cachedGet |= "com/google/common/cache/Cache".equals(call.owner) && "getIfPresent".equals(call.name);
                }
            }
            boolean recognized = nativeProfile && cachedProfile && nativeGet && cachedGet && returns == 3;
            if (!recognized) {
                LunarConnectionDiagnostics.recordOnceAfterStart("disguiser.lunar_badge_patch", className,
                        "displayLookupMatches=0 reason=unknown-layout returns=" + returns
                                + " nativeMapGet=" + nativeGet + " cacheGet=" + cachedGet);
                return null;
            }
            String displayProfile = current ? CURRENT_DISPLAY_PROFILE : DISPLAY_PROFILE;
            int presenceHooks = 0;
            if (current) {
                MethodNode presence = null;
                for (MethodNode method : node.methods) {
                    if (CURRENT_PRESENCE.equals(method.name)
                            && "(Ljava/util/UUID;)Z".equals(method.desc)) presence = method;
                }
                // Lunar TAB gates its logo draw/width on this separate cached-presence query.
                if (presence == null) return null;
                int booleanReturns = 0;
                boolean nativePresence = false, cachedPresence = false;
                for (AbstractInsnNode instruction : presence.instructions) {
                    if (instruction.getOpcode() == Opcodes.IRETURN) booleanReturns++;
                    if (!(instruction instanceof MethodInsnNode)) continue;
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    nativePresence |= "java/util/Map".equals(call.owner) && "get".equals(call.name);
                    cachedPresence |= "com/google/common/cache/Cache".equals(call.owner) && "getIfPresent".equals(call.name);
                    if (HELPER.equals(call.owner)) return null;
                }
                if (booleanReturns != 3 || !nativePresence || !cachedPresence) return null;
                for (AbstractInsnNode instruction : presence.instructions.toArray()) {
                    if (instruction.getOpcode() != Opcodes.IRETURN) continue;
                    InsnList filter = new InsnList();
                    filter.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    filter.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "filterLogoPresence",
                            "(ZLjava/util/UUID;)Z", false));
                    presence.instructions.insertBefore(instruction, filter);
                    presenceHooks++;
                }
            }
            for (AbstractInsnNode instruction : lookup.instructions.toArray()) {
                if (instruction.getOpcode() != Opcodes.ARETURN) continue;
                InsnList filter = new InsnList();
                filter.add(new VarInsnNode(Opcodes.ALOAD, 1));
                filter.add(new LdcInsnNode(Type.getObjectType(displayProfile)));
                filter.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "filterLogoForDisplay",
                        "(Ljava/lang/Object;Ljava/util/UUID;Ljava/lang/Class;)Ljava/lang/Object;", false));
                filter.add(new TypeInsnNode(Opcodes.CHECKCAST, displayProfile));
                lookup.instructions.insertBefore(instruction, filter);
            }
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            LunarConnectionDiagnostics.recordOnceAfterStart("disguiser.lunar_badge_patch", className,
                    "displayLookupMatches=1 expected=1 returnHooks=" + returns
                            + " presenceHooks=" + presenceHooks + " basicLogoPreserved=true currentMapping=" + current);
            return writer.toByteArray();
        } catch (Throwable error) {
            LunarConnectionDiagnostics.recordOnceAfterStart("disguiser.lunar_badge_patch_failed", className,
                    "exception=" + error.getClass().getSimpleName());
            return null;
        }
    }
}
