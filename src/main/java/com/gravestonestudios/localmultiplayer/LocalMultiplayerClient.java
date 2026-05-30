package com.gravestonestudios.localmultiplayer;

import com.mojang.authlib.GameProfile;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWGamepadState;

import java.util.UUID;

public class LocalMultiplayerClient implements ClientModInitializer {

    private static final int FAKE_PLAYER_ENTITY_ID = -42069;

    private static OtherClientPlayerEntity fakePlayer;
    private static int activeGamepad = -1;
    private static boolean announcedGamepad = false;
    private static boolean announcedSpawn = false;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) {
                fakePlayer = null;
                activeGamepad = -1;
                announcedGamepad = false;
                announcedSpawn = false;
                return;
            }

            if (fakePlayer == null || fakePlayer.isRemoved()) {
                spawnFakePlayer(client);
            }

            updateControllerMovement(client);
        });
    }

    private void spawnFakePlayer(MinecraftClient client) {
        ClientWorld world = client.world;

        GameProfile playerTwoProfile = new GameProfile(
                UUID.nameUUIDFromBytes("LocalMultiplayerPlayerTwo".getBytes()),
                "Player2"
        );

        fakePlayer = new OtherClientPlayerEntity(world, playerTwoProfile);
        fakePlayer.setId(FAKE_PLAYER_ENTITY_ID);

        Vec3d forward = client.player.getRotationVec(1.0f).multiply(3.0);
        Vec3d pos = client.player.getPos().add(forward.x, 0.0, forward.z);

        fakePlayer.refreshPositionAndAngles(pos.x, client.player.getY(), pos.z, client.player.getYaw(), 0.0f);
        fakePlayer.setHealth(20.0f);
        fakePlayer.setOnGround(true);

        world.addEntity(FAKE_PLAYER_ENTITY_ID, fakePlayer);

        if (!announcedSpawn && client.player != null) {
            client.player.sendMessage(Text.literal("Local Multiplayer: Player2 spawned in front of you."), false);
            announcedSpawn = true;
        }
    }

    private void updateControllerMovement(MinecraftClient client) {
        if (fakePlayer == null) return;

        int gamepad = getActiveGamepad();
        if (gamepad == -1) return;

        if (!announcedGamepad && client.player != null) {
            String name = GLFW.glfwGetGamepadName(gamepad);
            client.player.sendMessage(Text.literal("Local Multiplayer: controller found: " + (name == null ? "unknown" : name)), false);
            announcedGamepad = true;
        }

        GLFWGamepadState state = GLFWGamepadState.create();

        if (!GLFW.glfwGetGamepadState(gamepad, state)) {
            activeGamepad = -1;
            announcedGamepad = false;
            return;
        }

        float leftX = applyDeadzone(state.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X));
        float leftY = applyDeadzone(state.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y));

        double speed = 0.15;

        Vec3d movement = new Vec3d(leftX * speed, 0.0, leftY * speed);

        fakePlayer.setPosition(fakePlayer.getPos().add(movement));
        fakePlayer.setYaw((float) Math.toDegrees(Math.atan2(-movement.x, movement.z)));
        fakePlayer.prevYaw = fakePlayer.getYaw();
        fakePlayer.bodyYaw = fakePlayer.getYaw();
        fakePlayer.headYaw = fakePlayer.getYaw();

        if (state.buttons(GLFW.GLFW_GAMEPAD_BUTTON_A) == GLFW.GLFW_PRESS) {
            fakePlayer.setVelocity(fakePlayer.getVelocity().x, 0.42f, fakePlayer.getVelocity().z);
        }
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
}
