package com.xander.localmultiplayer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWGamepadState;

/**
 * Prototype local multiplayer.
 * P1 = normal Minecraft controls.
 * P2 = controller controls a fake armor-stand body.
 * This is NOT full split-screen yet. It is the correct first step.
 */
public class LocalMultiplayerClient implements ClientModInitializer {
    private static ArmorStandEntity playerTwo;
    private static boolean spawnedMessageShown = false;
    private static final int CONTROLLER_ID = GLFW.GLFW_JOYSTICK_1;
    private static final float DEADZONE = 0.18f;
    private static final double SPEED = 0.18;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick(client));
    }

    private static void tick(MinecraftClient client) {
        if (client.world == null || client.player == null) {
            playerTwo = null;
            spawnedMessageShown = false;
            return;
        }

        if (client.currentScreen != null) return;

        ensurePlayerTwo(client);
        updateControllerMovement(client);
    }

    private static void ensurePlayerTwo(MinecraftClient client) {
        World world = client.world;
        ClientPlayerEntity p1 = client.player;

        if (playerTwo == null || playerTwo.isRemoved()) {
            playerTwo = new ArmorStandEntity(EntityType.ARMOR_STAND, world);
            playerTwo.setCustomName(Text.literal("Player 2"));
            playerTwo.setCustomNameVisible(true);
            playerTwo.setShowArms(true);
            playerTwo.setNoGravity(false);
            playerTwo.setPosition(p1.getX() + 2, p1.getY(), p1.getZ());
            world.addEntity(playerTwo.getId(), playerTwo);

            if (!spawnedMessageShown) {
                p1.sendMessage(Text.literal("Local Multiplayer: Player 2 spawned. Use controller left stick to move."), false);
                spawnedMessageShown = true;
            }
        }
    }

    private static void updateControllerMovement(MinecraftClient client) {
        if (playerTwo == null) return;

        if (!GLFW.glfwJoystickIsGamepad(CONTROLLER_ID)) {
            return;
        }

        try (GLFWGamepadState state = GLFWGamepadState.calloc()) {
            if (!GLFW.glfwGetGamepadState(CONTROLLER_ID, state)) return;

            float lx = state.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X);
            float ly = state.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y);

            if (Math.abs(lx) < DEADZONE) lx = 0;
            if (Math.abs(ly) < DEADZONE) ly = 0;

            ClientPlayerEntity p1 = client.player;
            float yawRad = (float)Math.toRadians(p1.getYaw());

            Vec3d forward = new Vec3d(-Math.sin(yawRad), 0, Math.cos(yawRad));
            Vec3d right = new Vec3d(Math.cos(yawRad), 0, Math.sin(yawRad));

            Vec3d move = right.multiply(lx).add(forward.multiply(-ly));
            if (move.lengthSquared() > 0.001) {
                move = move.normalize().multiply(SPEED);
                playerTwo.updatePosition(
                        playerTwo.getX() + move.x,
                        playerTwo.getY(),
                        playerTwo.getZ() + move.z
                );
                playerTwo.setYaw((float)Math.toDegrees(Math.atan2(-move.x, move.z)));
            }

            if (state.buttons(GLFW.GLFW_GAMEPAD_BUTTON_A) == GLFW.GLFW_PRESS && playerTwo.isOnGround()) {
                playerTwo.setVelocity(playerTwo.getVelocity().x, 0.42, playerTwo.getVelocity().z);
            }
        }
    }
}
