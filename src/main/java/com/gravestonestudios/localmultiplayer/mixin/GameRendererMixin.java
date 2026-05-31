package com.gravestonestudios.localmultiplayer.mixin;

import com.gravestonestudios.localmultiplayer.LocalMultiplayerClient;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.Window;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLCapabilities;
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
     * Stage 4:
     * Render Player2's camera into the offscreen framebuffer, then draw that framebuffer's shared
     * color texture into the second GLFW window using raw OpenGL only.
     */
    private static final boolean ENABLE_PLAYER2_TEXTURE_COPY = true;
    private static int p2BlitProgram = 0;

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

        if (!ENABLE_PLAYER2_TEXTURE_COPY || playerTwo == null || p2Framebuffer == null) {
            clearSecondWindow(p2Window, 0.05f, 0.12f, 0.35f); // blue = waiting / not ready
            return;
        }

        if (LocalMultiplayerClient.isRenderingSecondView) {
            return;
        }

        Window window = client.getWindow();
        LocalMultiplayerClient.isRenderingSecondView = true;

        try {
            int[] p2Width = new int[1];
            int[] p2Height = new int[1];
            GLFW.glfwGetFramebufferSize(p2Window, p2Width, p2Height);
            int targetWidth = Math.max(1, p2Width[0]);
            int targetHeight = Math.max(1, p2Height[0]);

            if (p2Framebuffer.textureWidth != targetWidth || p2Framebuffer.textureHeight != targetHeight) {
                p2Framebuffer.resize(targetWidth, targetHeight, MinecraftClient.IS_SYSTEM_MAC);
            }

            p2Framebuffer.beginWrite(true);

            Entity oldCamera = client.getCameraEntity();
            boolean oldRenderHand = renderHand;
            boolean oldBlockOutline = blockOutlineEnabled;

            client.setCameraEntity(playerTwo);
            renderHand = true;
            blockOutlineEnabled = true;

            try {
                RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);
                renderWorld(tickDelta, startTime, new MatrixStack());
            } finally {
                renderHand = oldRenderHand;
                blockOutlineEnabled = oldBlockOutline;
                client.setCameraEntity(oldCamera);
                p2Framebuffer.endWrite();
            }

            LocalMultiplayerClient.isRenderingSecondView = false;
            client.getFramebuffer().beginWrite(true);
            RenderSystem.viewport(0, 0, window.getFramebufferWidth(), window.getFramebufferHeight());

            drawFramebufferTextureToSecondWindow(p2Window, p2Framebuffer.getColorAttachment());
        } catch (Throwable throwable) {
            LocalMultiplayerClient.isRenderingSecondView = false;
            clearSecondWindow(p2Window, 0.45f, 0.05f, 0.05f); // red = copy/render threw
        } finally {
            LocalMultiplayerClient.isRenderingSecondView = false;
            client.getFramebuffer().beginWrite(true);
            RenderSystem.viewport(0, 0, client.getWindow().getFramebufferWidth(), client.getWindow().getFramebufferHeight());
        }
    }

    private void clearSecondWindow(long p2Window, float red, float green, float blue) {
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
            GL11.glClearColor(red, green, blue, 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GLFW.glfwSwapBuffers(p2Window);
        } finally {
            GLFW.glfwMakeContextCurrent(previousContext == 0 ? mainWindow : previousContext);
            GL.setCapabilities(previousCaps);
            client.getFramebuffer().beginWrite(true);
        }
    }

    private void drawFramebufferTextureToSecondWindow(long p2Window, int textureId) {
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
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glClearColor(0f, 0f, 0f, 1f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            if (p2BlitProgram == 0) {
                p2BlitProgram = createP2BlitProgram();
            }

            GL20.glUseProgram(p2BlitProgram);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            GL20.glUniform1i(GL20.glGetUniformLocation(p2BlitProgram, "uTexture"), 0);

            GL11.glBegin(GL11.GL_TRIANGLES);
            GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(-1f, 1f);
            GL11.glTexCoord2f(1f, 0f); GL11.glVertex2f(1f, 1f);
            GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f(1f, -1f);
            GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(-1f, 1f);
            GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f(1f, -1f);
            GL11.glTexCoord2f(0f, 1f); GL11.glVertex2f(-1f, -1f);
            GL11.glEnd();

            GL20.glUseProgram(0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

            GLFW.glfwSwapBuffers(p2Window);
        } finally {
            GLFW.glfwMakeContextCurrent(previousContext == 0 ? mainWindow : previousContext);
            GL.setCapabilities(previousCaps);
            client.getFramebuffer().beginWrite(true);
        }
    }

    private static int createP2BlitProgram() {
        String vertexSource = "#version 120\n" +
                "varying vec2 vTex;\n" +
                "void main() {\n" +
                "    gl_Position = gl_Vertex;\n" +
                "    vTex = gl_MultiTexCoord0.xy;\n" +
                "}\n";

        String fragmentSource = "#version 120\n" +
                "uniform sampler2D uTexture;\n" +
                "varying vec2 vTex;\n" +
                "void main() {\n" +
                "    gl_FragColor = texture2D(uTexture, vTex);\n" +
                "}\n";

        int vertexShader = compileShader(GL20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = compileShader(GL20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertexShader);
        GL20.glAttachShader(program, fragmentShader);
        GL20.glLinkProgram(program);

        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);
        return program;
    }

    private static int compileShader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        return shader;
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
