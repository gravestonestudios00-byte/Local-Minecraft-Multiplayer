package com.gravestonestudios.localmultiplayer.mixin;

import com.gravestonestudios.localmultiplayer.LocalMultiplayerClient;
import com.gravestonestudios.localmultiplayer.Player2AwtWindow;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.Window;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.util.UUID;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    private static ByteBuffer p2PixelBuffer;
    private static int p2PixelWidth = 0;
    private static int p2PixelHeight = 0;

    @Shadow
    @Final
    private MinecraftClient client;

    @Shadow
    private boolean renderHand;

    @Shadow
    private boolean blockOutlineEnabled;

    @Shadow
    public abstract void renderWorld(float tickDelta, long limitTime, MatrixStack matrices);

    @Inject(method = "render", at = @At("TAIL"))
    private void localmultiplayer$renderSecondPlayerView(float tickDelta, long startTime, boolean tick, CallbackInfo ci) {
        if (client.world == null || client.player == null) return;

        Entity playerTwo = localmultiplayer$getPlayerTwoEntity();
        Framebuffer p2Framebuffer = LocalMultiplayerClient.getSecondPlayerFramebuffer();

        if (playerTwo == null || p2Framebuffer == null) {
            return;
        }

        if (LocalMultiplayerClient.isRenderingSecondView) {
            return;
        }

        Window window = client.getWindow();
        LocalMultiplayerClient.isRenderingSecondView = true;

        try {
            int targetWidth = Math.min(854, Math.max(1, window.getFramebufferWidth()));
            int targetHeight = Math.min(480, Math.max(1, window.getFramebufferHeight()));

            if (p2Framebuffer.textureWidth != targetWidth || p2Framebuffer.textureHeight != targetHeight) {
                p2Framebuffer.resize(targetWidth, targetHeight, MinecraftClient.IS_SYSTEM_MAC);
            }

            p2Framebuffer.beginWrite(true);

            Entity oldCamera = client.getCameraEntity();
            boolean oldRenderHand = renderHand;
            boolean oldBlockOutline = blockOutlineEnabled;

            client.setCameraEntity(playerTwo);
            renderHand = false;
            blockOutlineEnabled = false;

            try {
                RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);
                renderWorld(tickDelta, startTime, new MatrixStack());
                readBoundPlayerFramebufferToCpu(targetWidth, targetHeight);
            } finally {
                renderHand = oldRenderHand;
                blockOutlineEnabled = oldBlockOutline;
                client.setCameraEntity(oldCamera);
                p2Framebuffer.endWrite();
            }

            Player2AwtWindow.update(p2PixelBuffer, targetWidth, targetHeight);
        } catch (Throwable ignored) {
            // Keep Minecraft alive even if the external viewer has a frame problem.
        } finally {
            LocalMultiplayerClient.isRenderingSecondView = false;
            client.getFramebuffer().beginWrite(true);
            RenderSystem.viewport(0, 0, client.getWindow().getFramebufferWidth(), client.getWindow().getFramebufferHeight());
        }
    }

    private static void ensurePixelBuffer(int width, int height) {
        int needed = width * height * 4;
        if (p2PixelBuffer == null || p2PixelBuffer.capacity() < needed || p2PixelWidth != width || p2PixelHeight != height) {
            p2PixelBuffer = BufferUtils.createByteBuffer(needed);
            p2PixelWidth = width;
            p2PixelHeight = height;
        }
    }

    private void readBoundPlayerFramebufferToCpu(int width, int height) {
        ensurePixelBuffer(width, height);
        p2PixelBuffer.clear();
        GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, p2PixelBuffer);
        p2PixelBuffer.flip();
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
