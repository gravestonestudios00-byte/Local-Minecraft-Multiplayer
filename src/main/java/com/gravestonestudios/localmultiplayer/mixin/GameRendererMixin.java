package com.gravestonestudios.localmultiplayer.mixin;

import com.gravestonestudios.localmultiplayer.LocalMultiplayerClient;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLCapabilities;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.UUID;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    private static final boolean ENABLE_PLAYER2_RENDER = true;

    private static Framebuffer p2Framebuffer;
    private static Framebuffer mainBackupFramebuffer;
    private static ByteBuffer p2Pixels;
    private static int p2PixelWidth;
    private static int p2PixelHeight;

    private static int p2Program;
    private static int p2Vao;
    private static int p2Vbo;
    private static int p2Texture;
    private static int p2TextureWidth;
    private static int p2TextureHeight;

    @Shadow
    @Final
    private MinecraftClient client;

    @Shadow
    private boolean renderHand;

    @Shadow
    private boolean blockOutlineEnabled;

    @Shadow
    public abstract void renderWorld(float tickDelta, long limitTime, MatrixStack matrices);

    @Shadow
    public abstract void render(float tickDelta, long startTime, boolean tick);

    @Inject(method = "render", at = @At("TAIL"))
    private void localmultiplayer$renderSecondPlayerView(float tickDelta, long startTime, boolean tick, CallbackInfo ci) {
        if (client.world == null || client.player == null) {
            return;
        }

        // Full Player2 capture calls GameRenderer.render() recursively.
        // Do absolutely nothing inside the recursive injected tail.
        if (LocalMultiplayerClient.isRenderingSecondView) {
            return;
        }

        long p2Window = LocalMultiplayerClient.getSecondWindowHandle();
        if (p2Window == 0) {
            LocalMultiplayerClient.initSecondWindow(client);
            p2Window = LocalMultiplayerClient.getSecondWindowHandle();
        }

        if (p2Window == 0) {
            return;
        }

        if (!ENABLE_PLAYER2_RENDER) {
            clearSecondWindowBlue(p2Window);
            return;
        }

        Entity playerTwo = findPlayerTwoEntity();
        if (playerTwo == null) {
            clearSecondWindowBlue(p2Window);
            return;
        }

        int[] p2WindowWidth = new int[1];
        int[] p2WindowHeight = new int[1];
        GLFW.glfwGetFramebufferSize(p2Window, p2WindowWidth, p2WindowHeight);
        int targetWidth = Math.max(1, Math.min(854, p2WindowWidth[0]));
        int targetHeight = Math.max(1, Math.min(480, p2WindowHeight[0]));
        int mainWidth = Math.max(1, client.getWindow().getFramebufferWidth());
        int mainHeight = Math.max(1, client.getWindow().getFramebufferHeight());

        try {
            ensureMainBackupFramebuffer(mainWidth, mainHeight);
            copyFramebuffer(client.getFramebuffer(), mainBackupFramebuffer, mainWidth, mainHeight);
        } catch (Throwable ignored) {
            clearSecondWindowRed(p2Window);
            return;
        }

        LocalMultiplayerClient.isRenderingSecondView = true;
        try {
            ensurePlayer2Framebuffer(targetWidth, targetHeight);
            renderPlayerTwoToCpuBuffer(playerTwo, targetWidth, targetHeight, tickDelta, startTime, tick);
        } catch (Throwable ignored) {
            clearSecondWindowRed(p2Window);
            return;
        } finally {
            LocalMultiplayerClient.isRenderingSecondView = false;
            try {
                copyFramebuffer(mainBackupFramebuffer, client.getFramebuffer(), mainWidth, mainHeight);
            } catch (Throwable ignored) {
                // If restore fails, keep the game alive and at least rebind the main target.
            }
            client.getFramebuffer().beginWrite(true);
            RenderSystem.viewport(0, 0, mainWidth, mainHeight);
        }

        try {
            drawCpuBufferToSecondWindow(p2Window, targetWidth, targetHeight);
        } catch (Throwable ignored) {
            clearSecondWindowRed(p2Window);
        } finally {
            GLFW.glfwMakeContextCurrent(client.getWindow().getHandle());
            GLCapabilities mainCaps = LocalMultiplayerClient.getMainCapabilities();
            if (mainCaps != null) {
                GL.setCapabilities(mainCaps);
            }
            client.getFramebuffer().beginWrite(true);
            RenderSystem.viewport(0, 0, mainWidth, mainHeight);
        }
    }

    private void ensurePlayer2Framebuffer(int width, int height) {
        if (p2Framebuffer == null) {
            p2Framebuffer = new SimpleFramebuffer(width, height, true, MinecraftClient.IS_SYSTEM_MAC);
            p2Framebuffer.setClearColor(0f, 0f, 0f, 1f);
            return;
        }

        if (p2Framebuffer.textureWidth != width || p2Framebuffer.textureHeight != height) {
            p2Framebuffer.resize(width, height, MinecraftClient.IS_SYSTEM_MAC);
        }
    }

    private void ensureMainBackupFramebuffer(int width, int height) {
        if (mainBackupFramebuffer == null) {
            mainBackupFramebuffer = new SimpleFramebuffer(width, height, true, MinecraftClient.IS_SYSTEM_MAC);
            mainBackupFramebuffer.setClearColor(0f, 0f, 0f, 1f);
            return;
        }

        if (mainBackupFramebuffer.textureWidth != width || mainBackupFramebuffer.textureHeight != height) {
            mainBackupFramebuffer.resize(width, height, MinecraftClient.IS_SYSTEM_MAC);
        }
    }

    private void copyFramebuffer(Framebuffer source, Framebuffer target, int width, int height) {
        int oldRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int oldDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, ((FramebufferAccessor) source).getFbo());
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, ((FramebufferAccessor) target).getFbo());
        GL30.glBlitFramebuffer(
                0, 0, width, height,
                0, 0, width, height,
                GL11.GL_COLOR_BUFFER_BIT,
                GL11.GL_NEAREST
        );

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldRead);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, oldDraw);
    }

    private void ensureCpuBuffer(int width, int height) {
        int bytes = width * height * 4;
        if (p2Pixels == null || p2Pixels.capacity() < bytes || p2PixelWidth != width || p2PixelHeight != height) {
            p2Pixels = BufferUtils.createByteBuffer(bytes);
            p2PixelWidth = width;
            p2PixelHeight = height;
        }
    }

    private void renderPlayerTwoToCpuBuffer(Entity playerTwo, int width, int height, float tickDelta, long startTime, boolean tick) {
        ensureCpuBuffer(width, height);

        p2Framebuffer.beginWrite(true);

        Entity oldCamera = client.getCameraEntity();
        boolean oldRenderHand = renderHand;
        boolean oldBlockOutline = blockOutlineEnabled;

        client.setCameraEntity(playerTwo);
        renderHand = false;
        blockOutlineEnabled = false;

        try {
            RenderSystem.viewport(0, 0, width, height);
            RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);

            // Use the full GameRenderer path instead of renderWorld only.
            // This should include passes that renderWorld-only capture missed.
            render(tickDelta, startTime, false);

            p2Pixels.clear();
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, ((FramebufferAccessor) p2Framebuffer).getFbo());
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, p2Pixels);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
            p2Pixels.flip();
        } finally {
            renderHand = oldRenderHand;
            blockOutlineEnabled = oldBlockOutline;
            client.setCameraEntity(oldCamera);
            p2Framebuffer.endWrite();
        }
    }

    private void drawCpuBufferToSecondWindow(long p2Window, int width, int height) {
        long mainWindow = client.getWindow().getHandle();
        long previousContext = GLFW.glfwGetCurrentContext();
        GLCapabilities previousCaps = GL.getCapabilities();

        try {
            GLFW.glfwMakeContextCurrent(p2Window);
            GLCapabilities secondCaps = LocalMultiplayerClient.getSecondCapabilities();
            if (secondCaps == null || p2Pixels == null) {
                return;
            }
            GL.setCapabilities(secondCaps);

            int[] windowWidth = new int[1];
            int[] windowHeight = new int[1];
            GLFW.glfwGetFramebufferSize(p2Window, windowWidth, windowHeight);

            GL11.glViewport(0, 0, Math.max(1, windowWidth[0]), Math.max(1, windowHeight[0]));
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glClearColor(0f, 0f, 0f, 1f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            ensureSecondContextBlitResources(width, height);

            p2Pixels.rewind();
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, p2Texture);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, p2Pixels);

            GL20.glUseProgram(p2Program);
            GL20.glUniform1i(GL20.glGetUniformLocation(p2Program, "uTexture"), 0);
            GL30.glBindVertexArray(p2Vao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
            GL30.glBindVertexArray(0);
            GL20.glUseProgram(0);

            GLFW.glfwSwapBuffers(p2Window);
        } finally {
            GLFW.glfwMakeContextCurrent(previousContext == 0 ? mainWindow : previousContext);
            GL.setCapabilities(previousCaps);
            client.getFramebuffer().beginWrite(true);
        }
    }

    private void ensureSecondContextBlitResources(int width, int height) {
        if (p2Program == 0) {
            p2Program = createBlitProgram();
        }

        if (p2Vao == 0 || p2Vbo == 0) {
            p2Vao = GL30.glGenVertexArrays();
            p2Vbo = GL15.glGenBuffers();

            float[] vertices = new float[]{
                    -1f, -1f, 0f, 0f,
                     1f, -1f, 1f, 0f,
                     1f,  1f, 1f, 1f,
                    -1f, -1f, 0f, 0f,
                     1f,  1f, 1f, 1f,
                    -1f,  1f, 0f, 1f
            };
            FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
            vertexBuffer.put(vertices).flip();

            GL30.glBindVertexArray(p2Vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, p2Vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexBuffer, GL15.GL_STATIC_DRAW);

            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 4 * Float.BYTES, 0L);
            GL20.glEnableVertexAttribArray(1);
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 4 * Float.BYTES, 2L * Float.BYTES);

            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GL30.glBindVertexArray(0);
        }

        if (p2Texture == 0 || p2TextureWidth != width || p2TextureHeight != height) {
            if (p2Texture == 0) {
                p2Texture = GL11.glGenTextures();
            }
            p2TextureWidth = width;
            p2TextureHeight = height;

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, p2Texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        }
    }

    private static int createBlitProgram() {
        String vertexSource = "#version 150\n" +
                "in vec2 Position;\n" +
                "in vec2 UV;\n" +
                "out vec2 vUV;\n" +
                "void main() {\n" +
                "    gl_Position = vec4(Position, 0.0, 1.0);\n" +
                "    vUV = UV;\n" +
                "}\n";

        String fragmentSource = "#version 150\n" +
                "uniform sampler2D uTexture;\n" +
                "in vec2 vUV;\n" +
                "out vec4 fragColor;\n" +
                "void main() {\n" +
                "    fragColor = texture(uTexture, vUV);\n" +
                "}\n";

        int vertexShader = compileShader(GL20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = compileShader(GL20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertexShader);
        GL20.glAttachShader(program, fragmentShader);
        GL20.glBindAttribLocation(program, 0, "Position");
        GL20.glBindAttribLocation(program, 1, "UV");
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

    private void clearSecondWindowBlue(long p2Window) {
        clearSecondWindow(p2Window, 0.05f, 0.12f, 0.35f);
    }

    private void clearSecondWindowRed(long p2Window) {
        clearSecondWindow(p2Window, 0.45f, 0.05f, 0.05f);
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
    private int localmultiplayer$adjustHeightForProjection(net.minecraft.client.util.Window instance) {
        if (LocalMultiplayerClient.isRenderingSecondView && p2Framebuffer != null) {
            return Math.max(1, p2Framebuffer.textureHeight);
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
    private int localmultiplayer$adjustWidthForProjection(net.minecraft.client.util.Window instance) {
        if (LocalMultiplayerClient.isRenderingSecondView && p2Framebuffer != null) {
            return Math.max(1, p2Framebuffer.textureWidth);
        }
        return instance.getFramebufferWidth();
    }

    private Entity findPlayerTwoEntity() {
        if (client.world == null) {
            return null;
        }

        UUID playerTwoUuid = LocalMultiplayerClient.getSecondPlayerUuid();
        for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
            if (playerTwoUuid.equals(player.getUuid())) {
                return player;
            }
        }
        return null;
    }
}
