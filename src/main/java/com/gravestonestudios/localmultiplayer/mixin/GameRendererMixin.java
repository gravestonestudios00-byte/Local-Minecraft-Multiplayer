package com.gravestonestudios.localmultiplayer.mixin;

import com.gravestonestudios.localmultiplayer.Player2AwtWindow;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.Window;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    /**
     * Safety build:
     * Do not call renderWorld a second time. That call is leaking into the main player framebuffer.
     * This version only proves the external Java viewer window can open without touching Minecraft's world renderer.
     */
    private static final boolean ENABLE_PLAYER2_RENDER = false;

    @Shadow
    @Final
    private MinecraftClient client;

    @Inject(method = "render", at = @At("TAIL"))
    private void localmultiplayer$renderSecondPlayerView(float tickDelta, long startTime, boolean tick, CallbackInfo ci) {
        if (client.world == null || client.player == null) {
            return;
        }

        Window window = client.getWindow();
        int targetWidth = Math.min(854, Math.max(1, window.getFramebufferWidth()));
        int targetHeight = Math.min(480, Math.max(1, window.getFramebufferHeight()));

        // Always force the external Java viewer to open first.
        Player2AwtWindow.showWaiting(targetWidth, targetHeight);

        if (!ENABLE_PLAYER2_RENDER) {
            return;
        }
    }
}
