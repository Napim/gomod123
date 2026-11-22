package me.napim.gomod.mixins;

import me.napim.gomod.features.LunarConnectionDiagnostics;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.TypeInsnNode;
import java.util.List;
import java.util.Set;

public class GomodMixinPlugin implements IMixinConfigPlugin {

    private static final String PATCHER_ENTITY_RENDERING =
            "club.sk1er.patcher.util.world.render.entity.EntityRendering";
    private static final String LUNAR_COMPANION_RENDERER_22243 =
            "com.moonsworth.lunar.client.CRICRCRHRRICCICRHICIIIOHRIRHRH."
                    + "HHICICROOCIIROOHOORRCIHOICHOIH.OCCRIICCOCOIORRORHIRHCHCOHHHHC";
    private static final String LUNAR_COMPANION_SPAWN_METHOD_22243 =
            "HHCHIRROHRIRRIHCCHHIOCHIIOHRHO";
    private static final String LUNAR_COMPANION_SPAWN_DESCRIPTOR_22243 =
            "(Lcom/moonsworth/lunar/IHCCRCCOHRCCIORHHCROORROCICRHI/"
                    + "OIRIRHRCCIHOHRROCOHICIRIHIIHCH/HHICICROOCIIROOHOORRCIHOICHOIH/"
                    + "IHCCRCCOHRCCIORHHCROORROCICRHI;)V";
    private static final String LUNAR_COMPANION_RENDERER_2231 =
            "com.moonsworth.lunar.client.IHCHHIIIHROCIROHROCHRCIROHOICH."
                    + "IHIRRHORRRRHICCOHOHORCHIHHHIOO.ICCRRCCIOOIRICIHHCIOROHHROOHHR";
    private static final String LUNAR_COMPANION_SPAWN_METHOD_2231 =
            "OHCHICOOCCRHOOHIRCHRCCCCRRHRHI";
    private static final String LUNAR_COMPANION_SPAWN_DESCRIPTOR_2231 =
            "(Lcom/moonsworth/lunar/IHHHICHHRICRCIOHCIOHHOHIOIORRH/"
                    + "OOIOHHHCIICRRRCICOIIIRRRICOCIC/IHIRRHORRRRHICCOHOHORCHIHHHIOO/"
                    + "IHHHICHHRICRCIOHCIOHHOHIOIORRH;)V";
    private static final String LUNAR_HAND_CALLBACK_MIXIN =
            "me.napim.gomod.mixins.WeaveLunarFirstPersonHandCallbackMixin";
    private static final String LOCAL_CHAT_DISPLAY_MIXIN =
            "me.napim.gomod.mixins.WeaveLocalDisguiseChatDisplayMixin";
    private static final String LOCAL_NAMETAG_DISPLAY_MIXIN =
            "me.napim.gomod.mixins.WeaveLocalDisguiseNametagMixin";
    private static final String PLAYER_LABEL_MIXIN =
            "me.napim.gomod.mixins.RenderPlayerMixin";
    private static final String ANTI_SHUFFLE_DISPLAY_MIXIN =
            "me.napim.gomod.mixins.AntiShuffleDisplayIntegrationMixin";
    private static final String LUNAR_TEXT_HOLOGRAM_MIXIN =
            "me.napim.gomod.mixins.LunarTextHologramDisguiseMixin";
    private static final String LUNAR_MULTI_SAME_TYPE_2231_MIXIN =
            "me.napim.gomod.mixins.LunarMultiSameTypeCosmetics2231Mixin";
    private static final String LUNAR_COMPANION_RENDERER_2231_MIXIN =
            "me.napim.gomod.mixins.LunarCompanionMultiTypeRenderer2231Mixin";
    private static final String GOPARKY_COMPANION_ACTOR_MIXIN =
            "me.napim.gomod.mixins.GoparkyCompanionActorMixin";
    private static final String GOPARKY_RENDERER_2231_MIXIN =
            "me.napim.gomod.mixins.LunarGoparkyRenderer2231Mixin";
    private static final String GOPARKY_TEXTURE_BINDER_2231_MIXIN =
            "me.napim.gomod.mixins.LunarGoparkyTextureBinder2231Mixin";
    private static final String GOPARKY_RENDERER_2231 =
            "com.moonsworth.lunar.client.IHCHHIIIHROCIROHROCHRCIROHOICH."
                    + "IHIRRHORRRRHICCOHOHORCHIHHHIOO.ICCRRCCIOOIRICIHHCIOROHHROOHHR";
    private static final String GOPARKY_FRAME_TYPE =
            "com/moonsworth/lunar/client/IHCHHIIIHROCIROHROCHRCIROHOICH/"
                    + "IHIRRHORRRRHICCOHOHORCHIHHHIOO/ICCRRCCIOOIRICIHHCIOROHHROOHHR$"
                    + "CRRHRIRIHOHRHRCCHRCOICHOOIHOIC";
    private static final String GOPARKY_FRAME_CTOR = "(Lcom/moonsworth/lunar/client/"
            + "IHHCCCCRCOHRCRROIIIIHHOOHRHRCC/CRRHRIRIHOHRHRCCHRCOICHOOIHOIC/"
            + "IHHHICHHRICRCIOHCIOHHOHIOIORRH/CICOHIIRRHHIIHRROHOICOCRHHHHOO;"
            + "Lcom/moonsworth/lunar/client/IHCHHIIIHROCIROHROCHRCIROHOICH/"
            + "IHIRRHORRRRHICCOHOHORCHIHHHIOO/CICOHIIRRHHIIHRROHOICOCRHHHHOO/"
            + "CRRHRIRIHOHRHRCCHRCOICHOOIHOIC/CICOHIIRRHHIIHRROHOICOCRHHHHOO;DDDFD)V";
    private static final String GOPARKY_DRAW_MODEL_OWNER =
            "com/moonsworth/lunar/client/HIRCHOCCIROORHOHOOHOIOHOOIHRCR/"
                    + "CICOHIIRRHHIIHRROHOICOCRHHHHOO/IHHHICHHRICRCIOHCIOHHOHIOIORRH/"
                    + "HHRCOOOCROHCIRHCOCRRHHIOICHIHO";
    private static final String GOPARKY_DRAW_MODEL_DESC = "ICCRRCCIOOIRICIHHCIOROHHROOHHR("
            + "Lcom/moonsworth/lunar/client/HIRCHOCCIROORHOHOOHOIOHOOIHRCR/"
            + "CICOHIIRRHHIIHRROHOICOCRHHHHOO/IHHHICHHRICRCIOHCIOHHOHIOIORRH/"
            + "ICCRRCCIOOIRICIHHCIOROHHROOHHR;)V";
    private static final String GOPARKY_TEXTURE_BINDER_2231 =
            "com/moonsworth/lunar/client/HIRCHOCCIROORHOHOOHOIOHOOIHRCR/"
                    + "CICOHIIRRHHIIHRROHOICOCRHHHHOO/IHHHICHHRICRCIOHCIOHHOHIOIORRH/"
                    + "HHRCOOOCROHCIRHCOCRRHHIOICHIHO";
    private static final String GOPARKY_TEXTURE_BIND_METHOD_DESC = "(Lcom/moonsworth/lunar/client/"
            + "HIRCHOCCIROORHOHOOHOIOHOOIHRCR/CICOHIIRRHHIIHRROHOICOCRHHHHOO/"
            + "IHHHICHHRICRCIOHCIOHHOHIOIORRH/ICCRRCCIOOIRICIHHCIOROHHROOHHR;"
            + "Lcom/moonsworth/lunar/IHHHICHHRICRCIOHCIOHHOHIOIORRH/"
            + "RCHICCCIHRCRCHCIRRIIRRHICORHHO/ROHIOOCICRORHRCIHHHHHHRICCCRRC/"
            + "HHRCOOOCROHCIRHCOCRRHHIOICHIHO;)V";
    private static final String GOPARKY_TEXTURE_MANAGER_BRIDGE =
            "com/moonsworth/lunar/IHHHICHHRICRCIOHCIOHHOHIOIORRH/"
                    + "RCHICCCIHRCRCHCIRRIIRRHICORHHO/ROHIOOCICRORHRCIHHHHHHRICCCRRC/"
                    + "RCHOIOIIRCHIRRHIIHHRHOORCCIHCH/HIRCHOCCIROORHOHOOHOIOHOOIHRCR";
    private static final String GOPARKY_TEXTURE_BIND_CALL_DESC = "(Lcom/moonsworth/lunar/"
            + "IHHHICHHRICRCIOHCIOHHOHIOIORRH/OIORRCRHOIHCIORCOOOORICHCIRHHI/"
            + "OIORRCRHOIHCIORCOOOORICHCIRHHI;)V";
    private static final String GOPARKY_DRAW_METHOD_DESC = "(Lcom/moonsworth/lunar/client/"
            + "IHHCCCCRCOHRCRROIIIIHHOOHRHRCC/CRRHRIRIHOHRHRCCHRCOICHOOIHOIC/"
            + "IHHHICHHRICRCIOHCIOHHOHIOIORRH/CICOHIIRRHHIIHRROHOICOCRHHHHOO;"
            + "Lcom/moonsworth/lunar/client/IHCHHIIIHROCIROHROCHRCIROHOICH/"
            + "IHIRRHORRRRHICCOHOHORCHIHHHIOO/CICOHIIRRHHIIHRROHOICOCRHHHHOO/"
            + "CRRHRIRIHOHRHRCCHRCOICHOOIHOIC/CICOHIIRRHHIIHRROHOICOCRHHHHOO;"
            + "Lcom/moonsworth/lunar/IHHHICHHRICRCIOHCIOHHOHIOIORRH/"
            + "RCHICCCIHRCRCHCIRRIIRRHICORHHO/ROHIOOCICRORHRCIHHHHHHRICCCRRC/"
            + "RCHOIOIIRCHIRRHIIHHRHOORCCIHCH;F)V";
    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (LUNAR_HAND_CALLBACK_MIXIN.equals(mixinClassName)) {
            LunarConnectionDiagnostics.recordOnceAfterStart(
                    "disguise.lunar_hand_callback_mixin_selected", targetClassName,
                    "target=" + targetClassName + " selected=true");
        }
        // The same mixin list is used by Forge and native Weave. Forge-only targets are
        // intentionally absent on Lunar Client and must be skipped before Mixin resolves them.
        if (targetClassName != null && targetClassName.startsWith("net.minecraftforge.")) {
            return isClassPresent(targetClassName);
        }
        if ("me.napim.gomod.mixins.LunarCustomSprayCatalogMixin".equals(mixinClassName)
                || "me.napim.gomod.mixins.LunarCustomSprayEntryMixin".equals(mixinClassName)) {
            // These bindings are for the currently inspected Lunar spray classes only.
            // Keep the shared mixin config safe on Forge and other Lunar versions.
            return isClassPresent(targetClassName);
        }
        if ("me.napim.gomod.mixins.LunarGomodMascotCatalogMixin".equals(mixinClassName)) {
            // SandboxedMixinLoader can fail resource lookups for Lunar's obfuscated classes,
            // even when the classes are present in the active lunar.jar. Let @Pseudo resolve it.
            LunarConnectionDiagnostics.recordOnceAfterStart(
                    "lunar.gomod_mascot_catalog_mixin_selected", targetClassName,
                    "target=" + targetClassName + " selected=true pseudoTarget=true");
            return true;
        }
        if (LUNAR_MULTI_SAME_TYPE_2231_MIXIN.equals(mixinClassName)) {
            LunarConnectionDiagnostics.recordOnceAfterStart(
                    "cosmetics.multi_same_type_mixin_selected", targetClassName,
                    "target=" + targetClassName + " selected=true pseudoTarget=true");
            return true;
        }
        if (LUNAR_COMPANION_RENDERER_2231_MIXIN.equals(mixinClassName)) {
            LunarConnectionDiagnostics.recordOnceAfterStart(
                    "cosmetics.multi_companion_mixin_selected", targetClassName,
                    "target=" + targetClassName + " selected=true pseudoTarget=true");
            return true;
        }
        if (GOPARKY_COMPANION_ACTOR_MIXIN.equals(mixinClassName)) {
            LunarConnectionDiagnostics.recordOnceAfterStart(
                    "lunar.goparky_actor_mixin_selected", targetClassName,
                    "target=" + targetClassName + " selected=true pseudoTarget=true");
            return true;
        }
        if (GOPARKY_RENDERER_2231_MIXIN.equals(mixinClassName)) {
            LunarConnectionDiagnostics.recordOnceAfterStart(
                    "lunar.goparky_renderer_mixin_selected", targetClassName,
                    "target=" + targetClassName + " selected=true pseudoTarget=true");
            return true;
        }
        if (GOPARKY_TEXTURE_BINDER_2231_MIXIN.equals(mixinClassName)) {
            LunarConnectionDiagnostics.recordOnceAfterStart(
                    "lunar.goparky_texture_binder_mixin_selected", targetClassName,
                    "target=" + targetClassName + " selected=true pseudoTarget=true");
            return true;
        }
        if (LUNAR_TEXT_HOLOGRAM_MIXIN.equals(mixinClassName)) {
            // The target is an @Pseudo Lunar renderer. Under Weave, SandboxedMixinLoader's resource lookup can
            // miss classes that are nevertheless visible to Mixin from Lunar's own archive; probing here silently
            // skipped the hook. Let @Pseudo handle non-Lunar clients and record selection for runtime diagnosis.
            LunarConnectionDiagnostics.recordOnceAfterStart(
                    "disguise.lunar_text_hologram_mixin_selected", targetClassName,
                    "target=" + targetClassName + " selected=true pseudoTarget=true");
            return true;
        }
        if ("me.napim.gomod.mixins.PatcherEntityRenderingCompatMixin".equals(mixinClassName)) {
            return isClassPresent(PATCHER_ENTITY_RENDERING);
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {
        if (LUNAR_COMPANION_RENDERER_22243.equals(targetClassName)) {
            patchLunarCompanionLoop(targetClass, LUNAR_COMPANION_SPAWN_METHOD_22243,
                    LUNAR_COMPANION_SPAWN_DESCRIPTOR_22243, "2.22.43");
        } else if (LUNAR_COMPANION_RENDERER_2231.equals(targetClassName)) {
            patchLunarCompanionLoop(targetClass, LUNAR_COMPANION_SPAWN_METHOD_2231,
                    LUNAR_COMPANION_SPAWN_DESCRIPTOR_2231, "2.23.1");
        }
        if (LUNAR_COMPANION_RENDERER_2231_MIXIN.equals(mixinClassName)) {
            reportMergedHandler(targetClass, "cosmetics.multi_companion_renderer_hook_applied",
                    "gomod$recordRendererInvocation");
        }
        if (GOPARKY_COMPANION_ACTOR_MIXIN.equals(mixinClassName)) {
            reportMergedHandler(targetClass, "lunar.goparky_actor_constructor_hook_applied",
                    "gomod$rememberGoparkyActor");
            patchGoparkyCrouchQuery(targetClass);
        }
        if (GOPARKY_RENDERER_2231_MIXIN.equals(mixinClassName)) {
            patchGoparkyDrawPath(targetClass);
        }
        if (GOPARKY_TEXTURE_BINDER_2231_MIXIN.equals(mixinClassName)
                && GOPARKY_TEXTURE_BINDER_2231.equals(targetClassName.replace('.', '/'))) {
            patchGoparkyTextureBindPath(targetClass);
        }
        if (LUNAR_HAND_CALLBACK_MIXIN.equals(mixinClassName)) {
            reportMergedHandler(targetClass, "disguise.lunar_arm_scope_mixin_applied",
                    "gomod$beginHiddenDisguiseArmScope");
        }
        if (LOCAL_CHAT_DISPLAY_MIXIN.equals(mixinClassName)) {
            reportMergedHandler(targetClass, "disguise.local_chat_mixin_applied",
                    "gomod$rewriteDisguiseChatForDisplay");
        }
        if (LOCAL_NAMETAG_DISPLAY_MIXIN.equals(mixinClassName)) {
            reportMergedHandler(targetClass, "disguise.local_nametag_mixin_applied",
                    "gomod$rewriteLocalPlayerDisplayName");
        }
        if (PLAYER_LABEL_MIXIN.equals(mixinClassName)) {
            reportMergedHandler(targetClass, "disguise.player_label_mixin_applied",
                    "gomod$modifyPlayerNameTag");
        }
        if (ANTI_SHUFFLE_DISPLAY_MIXIN.equals(mixinClassName)) {
            reportMergedHandler(targetClass, "disguise.antishuffle_name_cleanup_hook_applied",
                    "gomod$rewriteDisguiseAfterAntiShuffle");
        }
        if (LUNAR_TEXT_HOLOGRAM_MIXIN.equals(mixinClassName)) {
            reportMergedHandler(targetClass, "disguise.lunar_text_hologram_hook_applied",
                    "gomod$rewriteLunarLeaderboardHologram");
        }
        if ("me.napim.gomod.mixins.LunarGomodMascotCatalogMixin".equals(mixinClassName)) {
            reportMergedHandler(targetClass, "lunar.gomod_mascot_catalog_hook_applied",
                    "gomod$appendCompanionToCatalog");
            reportMergedHandler(targetClass, "lunar.gomod_mascot_registration_hook_applied",
                    "gomod$verifyCompanionCatalog");
            reportMergedHandler(targetClass, "lunar.gomod_mascot_locker_hook_applied",
                    "gomod$observeCompanionLocker");
        }
        if (LUNAR_MULTI_SAME_TYPE_2231_MIXIN.equals(mixinClassName)) {
            reportMergedHandler(targetClass, "cosmetics.multi_same_type_resolver_hook_applied",
                    "gomod$beginEquipResolution");
            reportMergedHandler(targetClass, "cosmetics.multi_same_type_removal_hook_applied",
                    "gomod$preserveEquippedSameCategory");
            reportMergedHandler(targetClass, "cosmetics.multi_same_type_filter_hook_applied",
                    "gomod$filterReturnedConflicts");
        }
        if ("me.napim.gomod.mixins.PlayerListMixin".equals(mixinClassName)) {
            reportTabRendererPostApply(targetClass);
            patchLunarTabVictimDrawing(targetClass);
        }
    }

    private static void reportTabRendererPostApply(org.objectweb.asm.tree.ClassNode targetClass) {
        StringBuilder detail = new StringBuilder("target=").append(targetClass.name).append(" methods=");
        int count = 0;
        for (MethodNode method : targetClass.methods) {
            if (!(method.name.contains("onDrawString") || method.name.contains("GetPlayerName")
                    || method.name.contains("getPlayerName") || method.name.contains("GetStringWidth")
                    || method.name.contains("getStringWidth") || method.name.contains("getPlayerInfo"))) continue;
            if (count++ > 0) detail.append('|');
            detail.append(method.name).append(method.desc);
            if (detail.length() > 900) {
                detail.append("...truncated");
                break;
            }
        }
        if (count == 0) detail.append("<none>");
        LunarConnectionDiagnostics.recordOnceAfterStart("tab_victims.playerlist_mixin_post_apply",
                targetClass.name, detail.toString());
        try {
            System.setProperty("gomod.qa.tab_victims.renderer_discovery", detail.toString());
        } catch (Throwable ignored) { }
    }

    /** Patch Lunar's GEK frame and color arguments without Mixin's Args helper classes. */
    private static void patchGoparkyDrawPath(org.objectweb.asm.tree.ClassNode targetClass) {
        MethodNode frameMethod = null;
        MethodNode drawMethod = null;
        for (MethodNode method : targetClass.methods) {
            if ("IIIRCHHOHHIIHCOORRHIIRIIIIIIOH".equals(method.name)
                    && "(F)V".equals(method.desc)) frameMethod = method;
            if ("CRRHRIRIHOHRHRCCHRCOICHOOIHOIC".equals(method.name)
                    && GOPARKY_DRAW_METHOD_DESC.equals(method.desc)) {
                drawMethod = method;
            }
        }
        int frameCalls = 0;
        // Companion frame replacement is installed by semantic native discovery. Records
        // cannot be mutated reflectively, and this old alias/local-slot hook is retired.
        int drawCalls = 0;
        int compassCalls = 0;
        if (drawMethod != null) {
            int modelLocal = -1;
            for (AbstractInsnNode insn : drawMethod.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode)
                        || !"getAnimationProcessor".equals(((MethodInsnNode) insn).name)) continue;
                AbstractInsnNode receiver = insn.getPrevious();
                while (receiver != null && receiver.getOpcode() < 0) receiver = receiver.getPrevious();
                if (receiver instanceof VarInsnNode && receiver.getOpcode() == Opcodes.ALOAD) {
                    modelLocal = ((VarInsnNode) receiver).var;
                }
            }
            for (AbstractInsnNode insn = drawMethod.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) insn;
                if (call.getOpcode() != Opcodes.INVOKESTATIC || !GOPARKY_DRAW_MODEL_OWNER.equals(call.owner)
                        || !GOPARKY_DRAW_MODEL_DESC.equals(call.name + call.desc)) continue;
                InsnList tint = new InsnList();
                tint.add(new VarInsnNode(Opcodes.ALOAD, 1));
                tint.add(new InsnNode(Opcodes.SWAP));
                tint.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "me/napim/gomod/features/GoparkyBlitzReactions", "tintRenderContext",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
                // CHECKCAST takes an internal class name, while the method descriptor wraps it in L...;.
                tint.add(new TypeInsnNode(Opcodes.CHECKCAST,
                        org.objectweb.asm.Type.getArgumentTypes(call.desc)[0].getInternalName()));
                drawMethod.instructions.insertBefore(call, tint);
                drawCalls++;
                if (modelLocal >= 0) {
                    InsnList compass = new InsnList();
                    compass.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    compass.add(new VarInsnNode(Opcodes.ALOAD, modelLocal));
                    compass.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "me/napim/gomod/features/GoparkyCompassRenderer", "render",
                            "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
                    // The native world/yaw matrix is still active after this draw call;
                    // using a RETURN callback would run after Lunar has already popped it.
                    drawMethod.instructions.insert(call, compass);
                    compassCalls++;
                }
            }
        }
        if (frameCalls == 1 && drawCalls == 1) {
            LunarConnectionDiagnostics.recordOnceAfterStart("lunar.goparky_render_recoil_transform_hook_applied",
                    "lunar-2.23.1", "inserted=true frameConstructor=" + frameCalls);
            LunarConnectionDiagnostics.recordOnceAfterStart("lunar.goparky_draw_color_hook_applied",
                    "lunar-2.23.1", "inserted=true nativeDrawCall=" + drawCalls);
            LunarConnectionDiagnostics.recordOnceAfterStart("lunar.goparky_compass_hook_applied",
                    "lunar-2.23.1", "inserted=" + (compassCalls == 1) + " nativeDrawCall=" + compassCalls);
        } else {
            LunarConnectionDiagnostics.record("lunar.goparky_renderer_transform",
                    "result=skipped frameCalls=" + frameCalls + " drawCalls=" + drawCalls);
        }
    }

    /** Route Lunar's GEK skin binding through the local red-flash/taunt texture selector. */
    private static void patchGoparkyTextureBindPath(org.objectweb.asm.tree.ClassNode targetClass) {
        int bindCalls = 0;
        for (MethodNode method : targetClass.methods) {
            if (!"CICOHIIRRHHIIHRROHOICOCRHHHHOO".equals(method.name)
                    || !GOPARKY_TEXTURE_BIND_METHOD_DESC.equals(method.desc)) continue;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) insn;
                if (call.getOpcode() != Opcodes.INVOKEINTERFACE
                        || !GOPARKY_TEXTURE_MANAGER_BRIDGE.equals(call.owner)
                        || !"bridge$bindTexture".equals(call.name)
                        || !GOPARKY_TEXTURE_BIND_CALL_DESC.equals(call.desc)) continue;
                method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "me/napim/gomod/features/GoparkyBlitzReactions", "bindLunarCompanionTexture",
                        "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
                bindCalls++;
            }
        }
        if (bindCalls == 1) {
            LunarConnectionDiagnostics.recordOnceAfterStart("lunar.goparky_texture_bind_redirect_hook_applied",
                    "lunar-2.23.1", "inserted=true nativeTextureBind=" + bindCalls);
        } else {
            LunarConnectionDiagnostics.record("lunar.goparky_texture_bind_redirect",
                    "result=skipped bindCalls=" + bindCalls);
        }
    }

    /** Replace Lunar's hard-coded false sneaking query so the GEK controller runs its pose animation. */
    private static void patchGoparkyCrouchQuery(org.objectweb.asm.tree.ClassNode targetClass) {
        int patched = 0;
        for (MethodNode method : targetClass.methods) {
            if (!"bridge$isVisiblyCrouching".equals(method.name) || !"()Z".equals(method.desc)) continue;
            method.instructions.clear();
            method.tryCatchBlocks.clear();
            method.localVariables = null;
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "me/napim/gomod/features/GoparkyBlitzReactions", "shouldGoparkyActorCrouch",
                    "(Ljava/lang/Object;)Z", false));
            method.instructions.add(new InsnNode(Opcodes.IRETURN));
            method.maxStack = 1;
            method.maxLocals = 1;
            patched++;
        }
        LunarConnectionDiagnostics.recordOnceAfterStart("lunar.goparky_crouch_query_hook_applied",
                targetClass.name, "method=bridge$isVisiblyCrouching()Z patched=" + patched);
    }

    /**
     * Lunar merges these helpers into the real TAB overlay before Weave applies its mixins. Patching the
     * merged helpers reaches the active renderer, including its Adventure draw branch; a mixin on Lunar's
     * source mixin class would never run. Descriptors and original call sites gate each insertion.
     */
    private static void patchLunarTabVictimDrawing(org.objectweb.asm.tree.ClassNode targetClass) {
        String helper = "me/napim/gomod/features/KillCounter";
        int draws = 0, widths = 0, lists = 0;
        for (MethodNode method : targetClass.methods) {
            boolean alreadyPatched = false;
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                 instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (helper.equals(call.owner) && (call.name.equals("prepareCurrentTabVictimText")
                            || call.name.equals("reserveCurrentTabVictimWidth")
                            || call.name.equals("getTabOverlayRenderedSubList"))) {
                        alreadyPatched = true;
                        break;
                    }
                }
            }
            if (alreadyPatched) continue;
            if ((method.name.endsWith("lunar$onDrawString") || method.name.endsWith("impl$onDrawString"))
                    && (method.access & Opcodes.ACC_STATIC) == 0
                    && (method.desc.equals("(Lnet/minecraft/client/gui/FontRenderer;Ljava/lang/String;FFI)I")
                        || method.desc.equals("(Lnet/minecraft/client/gui/FontRenderer;Ljava/lang/String;FFI"
                            + "Lcom/llamalad7/mixinextras/sugar/ref/LocalRef;"
                            + "Lcom/llamalad7/mixinextras/sugar/ref/LocalRef;)I"))) {
                InsnList start = new InsnList();
                start.add(new VarInsnNode(Opcodes.ALOAD, 2));
                start.add(new MethodInsnNode(Opcodes.INVOKESTATIC, helper, "prepareCurrentTabVictimText",
                        "(Ljava/lang/String;)Ljava/lang/String;", false));
                start.add(new VarInsnNode(Opcodes.ASTORE, 2));
                start.add(new VarInsnNode(Opcodes.FLOAD, 3));
                start.add(new VarInsnNode(Opcodes.ALOAD, 2));
                start.add(new MethodInsnNode(Opcodes.INVOKESTATIC, helper, "offsetCurrentTabVictimTextX",
                        "(FLjava/lang/String;)F", false));
                start.add(new VarInsnNode(Opcodes.FSTORE, 3));
                start.add(new VarInsnNode(Opcodes.ILOAD, 5));
                start.add(new MethodInsnNode(Opcodes.INVOKESTATIC, helper, "colorCurrentTabVictimText", "(I)I", false));
                start.add(new VarInsnNode(Opcodes.ISTORE, 5));
                method.instructions.insert(start);
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (instruction.getOpcode() == Opcodes.IRETURN) {
                        method.instructions.insertBefore(instruction,
                                new MethodInsnNode(Opcodes.INVOKESTATIC, helper,
                                        "clearCurrentTabOverlayRow", "()V", false));
                    }
                }
                draws++;
            } else if (method.name.endsWith("lunar$onGetStringWidth")
                    && method.desc.startsWith("(Lnet/minecraft/client/gui/FontRenderer;Ljava/lang/String;")
                    && method.desc.endsWith(")I")) {
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (instruction.getOpcode() == Opcodes.IRETURN) {
                        InsnList reserve = new InsnList();
                        reserve.add(new VarInsnNode(Opcodes.ALOAD, 2));
                        reserve.add(new MethodInsnNode(Opcodes.INVOKESTATIC, helper, "reserveCurrentTabVictimWidth",
                                "(ILjava/lang/String;)I", false));
                        method.instructions.insertBefore(instruction, reserve);
                    }
                }
                method.maxStack = Math.max(method.maxStack, 2);
                widths++;
            } else if (method.name.endsWith("lunar$render$getPlayerInfo")
                    && method.desc.startsWith("(Lcom/google/common/collect/Ordering;Ljava/lang/Iterable;")
                    && method.desc.endsWith(")Ljava/util/List;")) {
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (instruction instanceof MethodInsnNode) {
                        MethodInsnNode call = (MethodInsnNode) instruction;
                        if (call.owner.equals("java/util/List") && call.name.equals("subList")
                                && call.desc.equals("(II)Ljava/util/List;")) {
                            method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, helper,
                                    "getTabOverlayRenderedSubList", "(Ljava/util/List;II)Ljava/util/List;", false));
                        }
                    } else if (instruction.getOpcode() == Opcodes.ARETURN) {
                        method.instructions.insertBefore(instruction, new MethodInsnNode(Opcodes.INVOKESTATIC,
                                helper, "applyTabOverlayRendererListSortFallback",
                                "(Ljava/util/List;)Ljava/util/List;", false));
                    }
                }
                lists++;
            }
        }
        if (draws + widths + lists > 0) {
            LunarConnectionDiagnostics.recordOnceAfterStart("tab_victims.lunar_renderer_hooks", targetClass.name,
                    "draw=" + draws + " width=" + widths + " shared_rows=" + lists);
        }
    }

    private static void reportMergedHandler(org.objectweb.asm.tree.ClassNode targetClass,
                                            String event, String handlerName) {
        boolean handlerMerged = false;
        boolean handlerCalled = false;
        for (MethodNode method : targetClass.methods) {
            if (method.name.endsWith(handlerName)) {
                handlerMerged = true;
            }
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                 instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode
                        && ((MethodInsnNode) instruction).name.endsWith(handlerName)) {
                    handlerCalled = true;
                }
            }
        }
        LunarConnectionDiagnostics.recordOnceAfterStart(event, targetClass.name,
                "target=" + targetClass.name + " handlerMerged=" + handlerMerged
                        + " handlerCalled=" + handlerCalled);
    }

    /**
     * Supported Lunar renderers return from their player-companion scan after the first match.
     * For Gomod's multi-same-type option, turn only that return into a loop continue.
     */
    private static void patchLunarCompanionLoop(org.objectweb.asm.tree.ClassNode targetClass,
                                                String spawnMethod, String spawnDescriptor,
                                                String lunarVersion) {
        MethodNode method = null;
        for (MethodNode candidate : targetClass.methods) {
            if (spawnMethod.equals(candidate.name)
                    && spawnDescriptor.equals(candidate.desc)) {
                method = candidate;
                break;
            }
        }
        if (method == null) {
            System.err.println("[gomod] Lunar multi-Companion patch skipped: "
                    + lunarVersion + " spawn method signature was not found.");
            LunarConnectionDiagnostics.recordOnceAfterStart(
                    "cosmetics.multi_companion_renderer_loop_patch", targetClass.name,
                    "target=" + targetClass.name + " version=" + lunarVersion
                            + " result=skipped reason=spawn-method-missing");
            return;
        }

        AbstractInsnNode targetReturn = null;
        LabelNode loopHead = null;
        int matches = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (instruction.getOpcode() != Opcodes.RETURN) {
                continue;
            }
            AbstractInsnNode next = nextExecutableInstruction(instruction);
            if (!(next instanceof JumpInsnNode) || next.getOpcode() != Opcodes.GOTO) {
                continue;
            }
            JumpInsnNode backEdge = (JumpInsnNode) next;
            if (method.instructions.indexOf(backEdge.label) >= method.instructions.indexOf(instruction)) {
                continue;
            }
            targetReturn = instruction;
            loopHead = backEdge.label;
            matches++;
        }

        if (matches != 1 || targetReturn == null || loopHead == null) {
            System.err.println("[gomod] Lunar multi-Companion patch skipped: expected one "
                    + "first-match return, found " + matches + ".");
            LunarConnectionDiagnostics.recordOnceAfterStart(
                    "cosmetics.multi_companion_renderer_loop_patch", targetClass.name,
                    "target=" + targetClass.name + " version=" + lunarVersion
                            + " method=" + method.name + " result=skipped returnMatches=" + matches);
            return;
        }

        // Avoid inserting the gate twice if a Lunar integration invokes postApply more than once.
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode
                    && "me/napim/gomod/mixins/LunarCompanionMultiTypeSupport".equals(
                            ((MethodInsnNode) instruction).owner)
                    && "isMultiCompanionRenderingEnabled".equals(((MethodInsnNode) instruction).name)) {
                return;
            }
        }

        InsnList gate = new InsnList();
        gate.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "me/napim/gomod/mixins/LunarCompanionMultiTypeSupport",
                "isMultiCompanionRenderingEnabled", "()Z", false));
        gate.add(new JumpInsnNode(Opcodes.IFNE, loopHead));
        method.instructions.insertBefore(targetReturn, gate);
        System.out.println("[gomod] Lunar multi-Companion renderer loop patched for "
                + "FullAccess profiles (independent of the viewer's equip setting), Lunar "
                + lunarVersion + ".");
        LunarConnectionDiagnostics.recordOnceAfterStart(
                "cosmetics.multi_companion_renderer_loop_patch", targetClass.name,
                "target=" + targetClass.name + " version=" + lunarVersion
                        + " method=" + method.name + " result=patched firstMatchReturn=loop-continue");
    }

    private static AbstractInsnNode nextExecutableInstruction(AbstractInsnNode instruction) {
        AbstractInsnNode next = instruction.getNext();
        while (next != null && next.getOpcode() < 0) {
            next = next.getNext();
        }
        return next;
    }

    private static boolean isClassPresent(String name) {
        String resourceName = name.replace('.', '/') + ".class";
        return hasClassResource(Thread.currentThread().getContextClassLoader(), resourceName)
                || hasClassResource(GomodMixinPlugin.class.getClassLoader(), resourceName);
    }

    private static boolean hasClassResource(ClassLoader loader, String resourceName) {
        if (loader == null) {
            return false;
        }
        try {
            return loader.getResource(resourceName) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
