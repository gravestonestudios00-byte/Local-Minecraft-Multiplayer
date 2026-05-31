package com.gravestonestudios.localmultiplayer.mixin;

import com.gravestonestudios.localmultiplayer.LocalMultiplayerClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
    @Inject(method = "getFramebuffer", at = @At("HEAD"), cancellable = true)
    private void localmultiplayer$redirectGlobalFramebuffer(CallbackInfoReturnable<Framebuffer> cir) {
        if (LocalMultiplayerClient.isRenderingSecondView && LocalMultiplayerClient.getSecondPlayerFramebuffer() != null) {
            cir.setReturnValue(LocalMultiplayerClient.getSecondPlayerFramebuffer());
        }
    }
}