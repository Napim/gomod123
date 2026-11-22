package me.napim.gomod.mixins;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DIAGNOSTIC: Ultra-minimal mixin to test if Minecraft class can be mixined AT ALL.
 * NO @Shadow, NO @ModifyConstant — just bare @Inject calls with logging.
 * If NONE of these fire, Minecraft class is untouchable by mixins on LC.
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {

    /**
     * Test 1: Inject into runTick HEAD (MCP name).
     */
    @Inject(method = "runTick", at = @At("HEAD"))
    private void gomod$testRunTick(CallbackInfo ci) {
        System.out.println("[gomod] MIXIN-TEST: runTick FIRED!");
    }

    /**
     * Test 2: Inject into clickMouse HEAD (MCP name).
     */
    @Inject(method = "clickMouse", at = @At("HEAD"))
    private void gomod$testClickMouse(CallbackInfo ci) {
        System.out.println("[gomod] MIXIN-TEST: clickMouse FIRED!");
    }

    /**
     * Test 3: Inject into the constructor (always exists).
     * If this fires but the others don't, it's a method name mapping issue.
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void gomod$testConstructor(CallbackInfo ci) {
        System.out.println("[gomod] MIXIN-TEST: Minecraft <init> FIRED! Constructor injection WORKS!");
    }
}
