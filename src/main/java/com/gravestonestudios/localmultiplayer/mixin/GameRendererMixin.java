package com.gravestonestudios.localmultiplayer.mixin;

import com.gravestonestudios.localmultiplayer.LocalMultiplayerClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.Window;
import net.minecraft.entity.Entity;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    /**
     * Diagnostic safety switch.
     *
     * This must stay false until the second-window context creation is proven stable.
     * Current goal: verify that opening a second GLFW window alone does not close Minecraft.
     */
    private static final boolean ENABLE_PLAYER2_VIEW_RENDER = false;

    @Shadow
    @Final
    private MinecraftClient client;

    @Inject(method = "render", at = @At("TAIL"))
    private void localmultiplayer$renderSecondPlayerView(float tickDelta, long startTime, boolean tick, CallbackInfo ci) {
        if (client.world == null || client.player == null) return;

        long p2Window = LocalMultiplayerClient.getSecondWindowHandle();
        if (p2Window == 0) {
            LocalMultiplayerClient.initSecondWindow(client);
        }

        // Do not render/copy the Player2 view yet. The previous crash happens during the second
        // OpenGL context path, so this isolates window creation from rendering.
        if (!ENABLE_PLAYER2_VIEW_RENDER) {
            return;
        }
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/WorldRenderer;drawEntityOutlinesFramebuffer()V"
            )
    )
    private void localmultiplayer$skipEntityOutlines(net.minecraft.client.render.WorldRenderer instance) {
        if (LocalMultiplayerClient.isRenderingSecondView) {
            return;
        }
        instance.drawEntityOutlinesFramebuffer();
    }

    @Redirect(
            method = {"getBasicProjectionMatrix", "getFov", "render"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/util/Window;getFramebufferHeight()I"
            )
    )
    private int localmultiplayer$adjustHeightForProjection(Window instance) {
        if (LocalMultiplayerClient.isRenderingSecondView) {
            Framebuffer fb = LocalMultiplayerClient.getSecondPlayerFramebuffer();
            if (fb != null) return Math.max(1, fb.textureHeight);
        }
        return instance.getFramebufferHeight();
    }

    @Redirect(
            method = {"getBasicProjectionMatrix", "getFov", "render"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/util/Window;getFramebufferWidth()I"
            )
    )
    private int localmultiplayer$adjustWidthForProjection(Window instance) {
        if (LocalMultiplayerClient.isRenderingSecondView) {
            Framebuffer fb = LocalMultiplayerClient.getSecondPlayerFramebuffer();
            if (fb != null) return Math.max(1, fb.textureWidth);
        }
        return instance.getFramebufferWidth();
    }

    private Entity localmultiplayer$getPlayerTwoEntity() {
        if (client.world == null) return null;
        UUID playerTwoUuid = LocalMultiplayerClient.getSecondPlayerUuid();
        for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
            if (playerTwoUuid.equals(player.getUuid())) {
                return player;
            }
        }

        return null;
    }
}
