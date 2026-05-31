package com.gravestonestudios.localmultiplayer;

import com.mojang.authlib.GameProfile;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWGamepadState;

import java.util.UUID;

public class LocalMultiplayerClient implements ClientModInitializer {

    private static final int FAKE_PLAYER_ID = 24002002;
    private static final UUID FAKE_PLAYER_UUID = UUID.nameUUIDFromBytes("LocalMultiplayerPlayerTwo".getBytes());
    private static final String FAKE_PLAYER_NAME = "Player2";

    private static final double WALK_SPEED = 0.18;
    private static final double GRAVITY = 0.08;
    private static final double JUMP_VELOCITY = 0.42;
    private static final double TERMINAL_VELOCITY = -3.0;
    private static final float LOOK_SPEED = 4.0f;

    private static OtherClientPlayerEntity playerTwo;
    private static ClientWorld currentWorld;
    private static int activeGamepad = -1;
    private static boolean announcedController = false;
    private static boolean announcedSpawn = false;
    private static double velocityY = 0.0;
    private static boolean onGround = true;
    private static float yaw = 0.0f;
    private static float pitch = 0.0f;
    private static float lastLeftX = 0.0f;
    private static float lastLeftY = 0.0f;
    private static float lastRightX = 0.0f;
    private static float lastRightY = 0.0f;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) {
                resetState();
                return;
            }

            if (currentWorld != client.world) {
                resetState();
                currentWorld = client.world;
            }

            ensurePlayerTwoExists(client);
            updateController(client);
        });

        HudRenderCallback.EVENT.register((context, tickDelta) -> renderDebug(MinecraftClient.getInstance(), context));
    }

    private void ensurePlayerTwoExists(MinecraftClient client) {
        if (client.world == null || client.player == null) {
            return;
        }

        if (playerTwo != null && !playerTwo.isRemoved() && client.world.getEntityById(FAKE_PLAYER_ID) == playerTwo) {
            return;
        }

        GameProfile profile = new GameProfile(FAKE_PLAYER_UUID, FAKE_PLAYER_NAME);
        playerTwo = new OtherClientPlayerEntity(client.world, profile);
        playerTwo.setId(FAKE_PLAYER_ID);

        Vec3d forward = client.player.getRotationVec(1.0f).multiply(3.0);
        Vec3d spawn = client.player.getPos().add(forward.x, 0.0, forward.z);

        yaw = client.player.getYaw();
        pitch = 0.0f;
        velocityY = 0.0;
        onGround = true;

        playerTwo.refreshPositionAndAngles(spawn.x, client.player.getY(), spawn.z, yaw, pitch);
        playerTwo.setHealth(20.0f);
        playerTwo.setOnGround(true);
        playerTwo.setCustomName(Text.literal(FAKE_PLAYER_NAME));
        playerTwo.setCustomNameVisible(true);
        playerTwo.setGlowing(true);

        client.world.addEntity(FAKE_PLAYER_ID, playerTwo);

        if (!announcedSpawn) {
            client.player.sendMessage(Text.literal("Local Multiplayer: fake Player2 spawned. No real login/client used."), false);
            announcedSpawn = true;
        }
    }

    private void updateController(MinecraftClient client) {
        if (playerTwo == null || client.world == null) {
            return;
        }

        int gamepad = getActiveGamepad();
        if (gamepad == -1) {
            return;
        }

        if (!announcedController && client.player != null) {
            String name = GLFW.glfwGetGamepadName(gamepad);
            client.player.sendMessage(Text.literal("Local Multiplayer: controller ready: " + (name == null ? "unknown" : name)), false);
            announcedController = true;
        }

        GLFWGamepadState state = GLFWGamepadState.create();
        if (!GLFW.glfwGetGamepadState(gamepad, state)) {
            activeGamepad = -1;
            announcedController = false;
            return;
        }

        lastLeftX = applyDeadzone(state.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X));
        lastLeftY = applyDeadzone(state.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y));
        lastRightX = applyDeadzone(state.axes(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_X));
        lastRightY = applyDeadzone(state.axes(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_Y));

        boolean jumpPressed = state.buttons(GLFW.GLFW_GAMEPAD_BUTTON_A) == GLFW.GLFW_PRESS;

        yaw = MathHelper.wrapDegrees(yaw + lastRightX * LOOK_SPEED);
        pitch = MathHelper.clamp(pitch + lastRightY * LOOK_SPEED, -80.0f, 80.0f);

        float forwardInput = -lastLeftY;
        float strafeInput = lastLeftX;
        float yawRadians = yaw * ((float) Math.PI / 180.0f);

        double forwardX = -MathHelper.sin(yawRadians);
        double forwardZ = MathHelper.cos(yawRadians);
        double rightX = MathHelper.cos(yawRadians);
        double rightZ = MathHelper.sin(yawRadians);

        Vec3d horizontal = new Vec3d(
                (forwardX * forwardInput + rightX * strafeInput) * WALK_SPEED,
                0.0,
                (forwardZ * forwardInput + rightZ * strafeInput) * WALK_SPEED
        );

        if (horizontal.horizontalLengthSquared() > WALK_SPEED * WALK_SPEED) {
            horizontal = horizontal.normalize().multiply(WALK_SPEED);
        }

        if (jumpPressed && onGround) {
            velocityY = JUMP_VELOCITY;
            onGround = false;
        }

        if (!onGround) {
            velocityY = Math.max(TERMINAL_VELOCITY, velocityY - GRAVITY);
        }

        Vec3d oldPos = playerTwo.getPos();
        Vec3d newPos = oldPos.add(horizontal.x, velocityY, horizontal.z);

        double groundY = client.world.getBottomY();
        if (newPos.y <= groundY) {
            newPos = new Vec3d(newPos.x, groundY, newPos.z);
            velocityY = 0.0;
            onGround = true;
        }

        // Cheap ground clamp for the prototype: do not fall below the block directly under Player2.
        int blockX = MathHelper.floor(newPos.x);
        int blockZ = MathHelper.floor(newPos.z);
        int scanY = MathHelper.floor(newPos.y);
        for (int y = scanY; y >= client.world.getBottomY(); y--) {
            if (!client.world.getBlockState(new net.minecraft.util.math.BlockPos(blockX, y, blockZ)).isAir()) {
                double feetY = y + 1.0;
                if (newPos.y <= feetY) {
                    newPos = new Vec3d(newPos.x, feetY, newPos.z);
                    velocityY = 0.0;
                    onGround = true;
                }
                break;
            }
        }

        playerTwo.setPosition(newPos);
        playerTwo.setYaw(yaw);
        playerTwo.setPitch(pitch);
        playerTwo.prevYaw = yaw;
        playerTwo.bodyYaw = yaw;
        playerTwo.headYaw = yaw;
        playerTwo.setOnGround(onGround);
        playerTwo.updateLimbs(false);
    }

    private int getActiveGamepad() {
        if (activeGamepad != -1 && GLFW.glfwJoystickPresent(activeGamepad) && GLFW.glfwJoystickIsGamepad(activeGamepad)) {
            return activeGamepad;
        }

        for (int joystick = GLFW.GLFW_JOYSTICK_1; joystick <= GLFW.GLFW_JOYSTICK_LAST; joystick++) {
            if (GLFW.glfwJoystickPresent(joystick) && GLFW.glfwJoystickIsGamepad(joystick)) {
                activeGamepad = joystick;
                return joystick;
            }
        }

        return -1;
    }

    private float applyDeadzone(float value) {
        float deadzone = 0.18f;
        return Math.abs(value) < deadzone ? 0.0f : value;
    }

    private void renderDebug(MinecraftClient client, DrawContext context) {
        if (client == null || client.player == null) {
            return;
        }

        int x = 8;
        int y = 8;
        int color = 0xFFFFFF;

        context.drawText(client.textRenderer, Text.literal("Local Multiplayer"), x, y, color, true);
        context.drawText(client.textRenderer, Text.literal("Mode: fake client-side entity"), x, y + 10, color, true);
        context.drawText(client.textRenderer, Text.literal("Player2: " + (playerTwo == null ? "missing" : "spawned")), x, y + 20, color, true);
        context.drawText(client.textRenderer, Text.literal("Controller: " + (getActiveGamepad() == -1 ? "missing" : "ready")), x, y + 30, color, true);
        context.drawText(client.textRenderer, Text.literal(String.format("LX %.2f LY %.2f RX %.2f RY %.2f", lastLeftX, lastLeftY, lastRightX, lastRightY)), x, y + 40, color, true);

        if (playerTwo != null) {
            Vec3d pos = playerTwo.getPos();
            context.drawText(client.textRenderer, Text.literal(String.format("P2 X %.1f Y %.1f Z %.1f", pos.x, pos.y, pos.z)), x, y + 50, color, true);
        }
    }

    private void resetState() {
        if (currentWorld != null && playerTwo != null) {
            currentWorld.removeEntity(FAKE_PLAYER_ID, net.minecraft.entity.Entity.RemovalReason.DISCARDED);
        }

        playerTwo = null;
        currentWorld = null;
        activeGamepad = -1;
        announcedController = false;
        announcedSpawn = false;
        velocityY = 0.0;
        onGround = true;
        yaw = 0.0f;
        pitch = 0.0f;
        lastLeftX = 0.0f;
        lastLeftY = 0.0f;
        lastRightX = 0.0f;
        lastRightY = 0.0f;
    }
}
