package com.gravestonestudios.localmultiplayer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWGamepadState;

public class LocalMultiplayerClient implements ClientModInitializer {

    private static OtherClientPlayerEntity fakePlayer;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            if (fakePlayer == null) {
                spawnFakePlayer(client);
            }

            updateControllerMovement(client);
        });
    }

    private void spawnFakePlayer(MinecraftClient client) {
        ClientWorld world = client.world;

        fakePlayer = new OtherClientPlayerEntity(
                world,
                client.player.getGameProfile()
        );

        Vec3d pos = client.player.getPos().add(2, 0, 0);

        fakePlayer.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0, 0);

        world.addEntity(fakePlayer.getId(), fakePlayer);
    }

    private void updateControllerMovement(MinecraftClient client) {
        if (fakePlayer == null) return;

        if (!GLFW.glfwJoystickPresent(GLFW.GLFW_JOYSTICK_1)) {
            return;
        }

        GLFWGamepadState state = GLFWGamepadState.create();

        if (!GLFW.glfwGetGamepadState(GLFW.GLFW_JOYSTICK_1, state)) {
            return;
        }

        float leftX = state.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X);
        float leftY = state.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y);

        double speed = 0.15;

        Vec3d movement = new Vec3d(leftX * speed, 0, leftY * speed);

        fakePlayer.setPosition(fakePlayer.getPos().add(movement));

        if (state.buttons(GLFW.GLFW_GAMEPAD_BUTTON_A) == GLFW.GLFW_PRESS) {
            fakePlayer.setVelocity(fakePlayer.getVelocity().x, 0.42f, fakePlayer.getVelocity().z);
        }
    }
}
