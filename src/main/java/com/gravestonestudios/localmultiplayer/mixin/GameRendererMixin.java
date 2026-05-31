package com.gravestonestudios.localmultiplayer.mixin;

import com.gravestonestudios.localmultiplayer.LocalMultiplayerClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    /**
     * Safe window-only build:
     * - opens the known-working GLFW Player2 window
     * - clears it dark blue
     * - does not call renderWorld twice
     * - cannot leak Player2 view onto Player1
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

        long p2Window = LocalMultiplayerClient.getSecondWindowHandle();
        if (p2Window == 0) {
            LocalMultiplayerClient.initSecondWindow(client);
            p2Window = LocalMultiplayerClient.getSecondWindowHandle();
        }

        if (p2Window != 0) {
            clearSecondWindowBlue(p2Window);
        }

        if (!ENABLE_PLAYER2_RENDER) {
            return;
        }
    }

    private void clearSecondWindowBlue(long p2Window) {
        long mainWindow = client.getWindow().getHandle();
        long previousContext = GLFW.glfwGetCurrentContext();
        GLCapabilities previousCaps = GL.getCapabilities();

        try {
            GLFW.glfwMakeContextCurrent(p2Window);
            GLCapabilities secondCaps = LocalMultiplayerClient.getSecondCapabilities();
            if (secondCaps == null) {
                return;
            }
            GL.setCapabilities(secondCaps);

            int[] width = new int[1];
            int[] height = new int[1];
            GLFW.glfwGetFramebufferSize(p2Window, width, height);

            GL11.glViewport(0, 0, Math.max(1, width[0]), Math.max(1, height[0]));
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glClearColor(0.05f, 0.12f, 0.35f, 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GLFW.glfwSwapBuffers(p2Window);
        } finally {
            GLFW.glfwMakeContextCurrent(previousContext == 0 ? mainWindow : previousContext);
            GL.setCapabilities(previousCaps);
            client.getFramebuffer().beginWrite(true);
        }
    }
}
