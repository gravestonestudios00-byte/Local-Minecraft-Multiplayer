package com.gravestonestudios.localmultiplayer.mixin;

import com.gravestonestudios.localmultiplayer.LocalMultiplayerClient;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.Window;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;

import java.util.UUID;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow
    @Final
    private static Logger LOGGER;

    @Shadow
    @Final
    private MinecraftClient client;

    @Shadow
    private boolean renderHand;

    @Shadow
    private boolean blockOutlineEnabled;

    @Shadow
    public abstract void renderWorld(float tickDelta, long limitTime, MatrixStack matrices);

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/GameRenderer;renderWorld(FJLnet/minecraft/client/util/math/MatrixStack;)V",
                    ordinal = 0,
                    shift = At.Shift.AFTER
            )
    )
    private void localmultiplayer$renderSecondPlayerView(float tickDelta, long startTime, boolean tick, CallbackInfo ci) {
        Entity playerTwo = localmultiplayer$getPlayerTwoEntity();
        long p2Window = LocalMultiplayerClient.getSecondWindowHandle();
        Framebuffer p2Framebuffer = LocalMultiplayerClient.getSecondPlayerFramebuffer();
        long mainWindow = client.getWindow().getHandle();

        if (p2Window == 0) return;

        // 1. DEBUG/WAITING SCREEN: If P2 isn't ready, show blue in Window 2
        if (playerTwo == null || p2Framebuffer == null || client.world == null) {
            try { LOGGER.info("[LocalMultiplayer] Drawing P2 waiting screen: playerTwoNull={} p2FramebufferNull={}", playerTwo == null, p2Framebuffer == null); } catch (Throwable t) {}
            try {
                GLFW.glfwMakeContextCurrent(p2Window);
                int[] w = new int[1], h = new int[1];
                GLFW.glfwGetFramebufferSize(p2Window, w, h);
                RenderSystem.viewport(0, 0, w[0], h[0]);
                RenderSystem.clearColor(0.1f, 0.4f, 0.6f, 1.0f);
                RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);
                GLFW.glfwSwapBuffers(p2Window);
            } finally {
                GLFW.glfwMakeContextCurrent(mainWindow);
            }
            return;
        }

        if (LocalMultiplayerClient.isRenderingSecondView) {
            return;
        }

        Window window = client.getWindow();
        int width = window.getFramebufferWidth();
        int height = window.getFramebufferHeight();
        Entity originalCamera = client.getCameraEntity();
        boolean originalRenderHand = renderHand;
        boolean originalBlockOutline = blockOutlineEnabled;

        // 2. PREPARE DIMENSIONS: Cap resolution for Player 2's offscreen buffer
        int targetWidth = Math.min(width, 1280);
        int targetHeight = Math.min(height, 720);

        LocalMultiplayerClient.isRenderingSecondView = true;

        try {
            if (p2Framebuffer.textureWidth != targetWidth || p2Framebuffer.textureHeight != targetHeight) {
                try { LOGGER.info("[LocalMultiplayer] Resizing P2 framebuffer from {}x{} to {}x{}", p2Framebuffer.textureWidth, p2Framebuffer.textureHeight, targetWidth, targetHeight); } catch (Throwable t) {}
                p2Framebuffer.resize(targetWidth, targetHeight, MinecraftClient.IS_SYSTEM_MAC);
            }

            // 3. RENDER P2 WORLD (In Main Context, isolated to offscreen buffer)
            p2Framebuffer.beginWrite(true);
            
            Entity oldCamera = client.getCameraEntity();
            client.setCameraEntity(playerTwo);
            boolean oldHand = renderHand;
            boolean oldOutline = blockOutlineEnabled;
            renderHand = true;
            blockOutlineEnabled = true;
            
            try {
                RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);
                renderWorld(tickDelta, startTime, new MatrixStack());
            } finally {
                renderHand = oldHand;
                blockOutlineEnabled = oldOutline;
                client.setCameraEntity(oldCamera);
                p2Framebuffer.endWrite();
            }

            // 3. ISOLATION FIX: Reset the flag immediately after P2's world render finishes.
            // This ensures subsequent calls to client.getFramebuffer() for P1's HUD
            // return the correct main framebuffer.
            LocalMultiplayerClient.isRenderingSecondView = false;

            // Explicitly re-bind the main framebuffer to the main context
            client.getFramebuffer().beginWrite(true);
            RenderSystem.viewport(0, 0, window.getFramebufferWidth(), window.getFramebufferHeight());

            // 4. DISPLAY IN WINDOW 2: Switch context to "blit" the result texture.
            GLFW.glfwMakeContextCurrent(p2Window);
            int[] p2w = new int[1], p2h = new int[1];
            GLFW.glfwGetFramebufferSize(p2Window, p2w, p2h);
            
            // Hardware blit from P2's offscreen FBO to the P2 Window's backbuffer
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, ((com.gravestonestudios.localmultiplayer.mixin.FramebufferAccessor)p2Framebuffer).getFbo());
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
            GL30.glBlitFramebuffer(0, 0, p2Framebuffer.textureWidth, p2Framebuffer.textureHeight,
                                   0, 0, p2w[0], p2h[0],
                                   GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);

            GLFW.glfwSwapBuffers(p2Window);
        } catch (Throwable exception) {
            LOGGER.error("P2 Render Crash", exception);
        } finally {
            // Safety net restoration
            GLFW.glfwMakeContextCurrent(mainWindow);
            LocalMultiplayerClient.isRenderingSecondView = false;
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
            return; // Skip outline pass in split-screen to avoid visual drift/weirdness
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
        // If we are currently rendering P2's view, use P2's window height for calculations
        if (LocalMultiplayerClient.isRenderingSecondView) {
            long p2Window = LocalMultiplayerClient.getSecondWindowHandle();
            if (p2Window != 0) {
                int[] width = new int[1], height = new int[1];
                GLFW.glfwGetFramebufferSize(p2Window, width, height);
                return Math.max(1, height[0]);
            }
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
        // If we are currently rendering P2's view, use P2's window width for calculations
        if (LocalMultiplayerClient.isRenderingSecondView) {
            long p2Window = LocalMultiplayerClient.getSecondWindowHandle();
            if (p2Window != 0) {
                int[] width = new int[1], height = new int[1];
                GLFW.glfwGetFramebufferSize(p2Window, width, height);
                return Math.max(1, width[0]);
            }
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
