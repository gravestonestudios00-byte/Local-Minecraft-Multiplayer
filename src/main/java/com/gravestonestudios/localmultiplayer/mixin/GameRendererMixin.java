package com.gravestonestudios.localmultiplayer.mixin;

import com.gravestonestudios.localmultiplayer.LocalMultiplayerClient;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.Window;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;
import org.spongepowered.asm.mixin.Final;
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

    @Inject(method = "render", at = @At("TAIL"))
    private void localmultiplayer$renderSecondPlayerView(float tickDelta, long startTime, boolean tick, CallbackInfo ci) {
        if (client.world == null || client.player == null) return;

        long p2Window = LocalMultiplayerClient.getSecondWindowHandle();
        if (p2Window == 0) {
            LocalMultiplayerClient.initSecondWindow(client);
            p2Window = LocalMultiplayerClient.getSecondWindowHandle();
        }

        if (p2Window == 0) return;

        Entity playerTwo = localmultiplayer$getPlayerTwoEntity();
        Framebuffer p2Framebuffer = LocalMultiplayerClient.getSecondPlayerFramebuffer();
        long mainWindow = client.getWindow().getHandle();

        // Stability rule: do not touch the second OpenGL context until Player2 and the framebuffer exist.
        // The previous version switched contexts even for the waiting screen, which can hard-close Minecraft on join.
        if (playerTwo == null || p2Framebuffer == null || client.world == null) {
            return;
        }

        if (LocalMultiplayerClient.isRenderingSecondView) {
            return;
        }

        LocalMultiplayerClient.isRenderingSecondView = true;
        Window window = client.getWindow();

        try {
            int[] p2winW = new int[1];
            int[] p2winH = new int[1];
            GLFW.glfwGetFramebufferSize(p2Window, p2winW, p2winH);
            int targetWidth = Math.max(1, p2winW[0]);
            int targetHeight = Math.max(1, p2winH[0]);

            if (p2Framebuffer.textureWidth != targetWidth || p2Framebuffer.textureHeight != targetHeight) {
                p2Framebuffer.resize(targetWidth, targetHeight, MinecraftClient.IS_SYSTEM_MAC);
            }

            p2Framebuffer.beginWrite(true);

            Entity oldCamera = client.getCameraEntity();
            boolean oldHand = renderHand;
            boolean oldOutline = blockOutlineEnabled;

            client.setCameraEntity(playerTwo);
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

            LocalMultiplayerClient.isRenderingSecondView = false;
            client.getFramebuffer().beginWrite(true);
            RenderSystem.viewport(0, 0, window.getFramebufferWidth(), window.getFramebufferHeight());

            long previousContext = GLFW.glfwGetCurrentContext();
            GLCapabilities previousCaps = GL.getCapabilities();

            try {
                GLFW.glfwMakeContextCurrent(p2Window);
                GLCapabilities secondCaps = LocalMultiplayerClient.getSecondCapabilities();
                if (secondCaps == null) {
                    return;
                }
                GL.setCapabilities(secondCaps);

                int[] p2w = new int[1];
                int[] p2h = new int[1];
                GLFW.glfwGetFramebufferSize(p2Window, p2w, p2h);

                GL11.glViewport(0, 0, p2w[0], p2h[0]);
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glClearColor(0f, 0f, 0f, 1f);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

                RenderSystem.setShader(GameRenderer::getPositionTexProgram);
                RenderSystem.setShaderTexture(0, p2Framebuffer.getColorAttachment());
                RenderSystem.disableDepthTest();

                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPushMatrix();
                GL11.glLoadIdentity();
                GL11.glOrtho(0, 1, 0, 1, -1, 1);

                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPushMatrix();
                GL11.glLoadIdentity();

                Tessellator tessellator = Tessellator.getInstance();
                BufferBuilder bufferBuilder = tessellator.getBuffer();
                bufferBuilder.begin(net.minecraft.client.render.VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);
                bufferBuilder.vertex(0.0, 1.0, 0.0).texture(0.0f, 0.0f).next();
                bufferBuilder.vertex(1.0, 1.0, 0.0).texture(1.0f, 0.0f).next();
                bufferBuilder.vertex(1.0, 0.0, 0.0).texture(1.0f, 1.0f).next();
                bufferBuilder.vertex(0.0, 0.0, 0.0).texture(0.0f, 1.0f).next();
                tessellator.draw();

                GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_MODELVIEW);

                GLFW.glfwSwapBuffers(p2Window);
            } finally {
                GLFW.glfwMakeContextCurrent(previousContext);
                GL.setCapabilities(previousCaps);
            }
        } catch (Throwable exception) {
            LOGGER.error("P2 Render Crash", exception);
        } finally {
            GLFW.glfwMakeContextCurrent(mainWindow);
            GLCapabilities mainCaps = LocalMultiplayerClient.getMainCapabilities();
            if (mainCaps != null) {
                GL.setCapabilities(mainCaps);
            }
            client.getFramebuffer().beginWrite(true);
            RenderSystem.viewport(0, 0, client.getWindow().getFramebufferWidth(), client.getWindow().getFramebufferHeight());
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
