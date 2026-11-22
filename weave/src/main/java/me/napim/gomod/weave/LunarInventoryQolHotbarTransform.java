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

import java.util.ArrayList;
import java.util.List;

/**
 * Lunar Genesis can skip GuiIngame mixins. Injects the Inventory-QoL tint into the actual hotbar
 * item pass so it shares the native hotbar framebuffer and ordering instead of the late HUD fallback.
 */
public final class LunarInventoryQolHotbarTransform {
    public static final String TARGET = "net/minecraft/client/gui/GuiIngame";
    private static final String HOOK_OWNER = "me/napim/gomod/features/InventoryQolHudUnderlay";
    private static final String HOOK_NAME = "renderNativeHotbarSlot";
    private static final String HOOK_DESC = "(III)V";

    private LunarInventoryQolHotbarTransform() { }

    public static boolean isTarget(String name) {
        return TARGET.equals(name == null ? null : name.replace('.', '/'));
    }

    public static byte[] transform(String name, byte[] bytes) {
        if (!isTarget(name) || bytes == null) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);

            List<HotbarRenderSite> renderSites = new ArrayList<HotbarRenderSite>();
            boolean alreadyPatched = false;
            for (MethodNode method : node.methods) {
                if (!isHotbarItemMethod(method)) {
                    continue;
                }
                for (AbstractInsnNode instruction : method.instructions) {
                    if (!(instruction instanceof MethodInsnNode)) {
                        continue;
                    }
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (HOOK_OWNER.equals(call.owner) && HOOK_NAME.equals(call.name)) {
                        LunarConnectionDiagnostics.recordOnceAfterStart(
                                "inventory_qol.native_hotbar_hook_already_present", node.name,
                                "method=" + method.name + method.desc);
                        alreadyPatched = true;
                        break;
                    }
                    if (isRenderItemIntoGui(call)) {
                        renderSites.add(new HotbarRenderSite(method, call));
                    }
                }
            }

            if (alreadyPatched) {
                return null;
            }

            // Patch icon draws only. Every actual redraw needs its tint; the numeric/durability
            // overlay is a separate invocation and must not paint the underlay over the icon.
            if (renderSites.isEmpty()) {
                LunarConnectionDiagnostics.recordOnceAfterStart(
                        "inventory_qol.native_hotbar_hook_skipped", node.name,
                        "hotbarRenderCalls=0");
                return null;
            }

            StringBuilder hookPoints = new StringBuilder();
            for (HotbarRenderSite site : renderSites) {
                InsnList hook = new InsnList();
                hook.add(new VarInsnNode(Opcodes.ILOAD, 1)); // hotbar slot
                hook.add(new VarInsnNode(Opcodes.ILOAD, 2)); // native icon x
                hook.add(new VarInsnNode(Opcodes.ILOAD, 3)); // native icon y
                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, HOOK_DESC, false));
                site.method.instructions.insertBefore(site.call, hook);
                if (hookPoints.length() > 0) {
                    hookPoints.append('|');
                }
                hookPoints.append(site.method.name).append(site.method.desc).append("->")
                        .append(site.call.owner).append('.').append(site.call.name).append(site.call.desc);
            }

            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            LunarConnectionDiagnostics.recordOnceAfterStart(
                    "inventory_qol.native_hotbar_hook_applied", node.name,
                    "sites=" + renderSites.size() + " points=" + hookPoints);
            return writer.toByteArray();
        } catch (Throwable error) {
            LunarConnectionDiagnostics.recordOnceAfterStart(
                    "inventory_qol.native_hotbar_hook_failed", TARGET,
                    "exception=" + error.getClass().getSimpleName());
            return null;
        }
    }

    private static boolean isHotbarItemMethod(MethodNode method) {
        if (method == null || method.instructions == null
                || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_STATIC)) != 0) {
            return false;
        }
        String descriptor = method.desc;
        // (int slot, int x, int y, float partialTicks, EntityPlayer player) -> void.
        return descriptor != null && descriptor.startsWith("(IIIFL") && descriptor.endsWith(";)V");
    }

    private static boolean isRenderItemIntoGui(MethodInsnNode call) {
        if (call == null || call.getOpcode() == Opcodes.INVOKESTATIC) {
            return false;
        }
        if (!"(Lnet/minecraft/item/ItemStack;II)V".equals(call.desc)) {
            return false;
        }
        // Both renderItemAndEffectIntoGUI and renderItemIntoGUI take one ItemStack plus x/y.
        // renderItemOverlays also ends in ';II)V', but additionally takes FontRenderer.
        return call.owner != null && (call.owner.endsWith("/RenderItem")
                || "renderItemAndEffectIntoGUI".equals(call.name)
                || "renderItemIntoGUI".equals(call.name)
                || "func_180450_b".equals(call.name)
                || "func_175099_a".equals(call.name));
    }

    private static final class HotbarRenderSite {
        final MethodNode method;
        final MethodInsnNode call;

        HotbarRenderSite(MethodNode method, MethodInsnNode call) {
            this.method = method;
            this.call = call;
        }
    }
}
