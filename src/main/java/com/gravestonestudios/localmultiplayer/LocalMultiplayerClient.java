package com.gravestonestudios.localmultiplayer;

import com.mojang.authlib.GameProfile;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.MovementType;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWGamepadState;

import java.net.SocketAddress;
import java.util.UUID;

public class LocalMultiplayerClient implements ClientModInitializer {

    private static final String PLAYER_TWO_NAME = "Player2";
    private static final UUID PLAYER_TWO_UUID = UUID.nameUUIDFromBytes("LocalMultiplayerPlayerTwo".getBytes());

    private static final double WALK_SPEED_PER_TICK = 0.18;
    private static final double JUMP_VELOCITY = 0.42;
    private static final float LOOK_SPEED_DEGREES = 4.0f;

    private static MinecraftServer activeServer;
    private static ServerPlayerEntity playerTwo;
    private static FakeClientConnection playerTwoConnection;
    private static boolean spawnAttempted = false;

    private static int activeGamepad = -1;
    private static boolean announcedController = false;
    private static boolean announcedSpawn = false;

    private static float yaw = 0.0f;
    private static float pitch = 0.0f;
    private static float lastLeftX = 0.0f;
    private static float lastLeftY = 0.0f;
    private static float lastRightX = 0.0f;
    private static float lastRightY = 0.0f;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            MinecraftServer server = client.getServer();

            if (client.player == null || client.world == null || server == null) {
                resetLocalState();
                return;
            }

            if (activeServer != server) {
                removePlayerTwo(activeServer);
                resetLocalState();
                activeServer = server;
            }

            ensureServerPlayerTwo(client, server);
            readControllerAndMovePlayerTwo(client, server);
        });

        HudRenderCallback.EVENT.register((context, tickDelta) -> renderDebug(MinecraftClient.getInstance(), context));
    }

    private void ensureServerPlayerTwo(MinecraftClient client, MinecraftServer server) {
        if (spawnAttempted) {
            playerTwo = server.getPlayerManager().getPlayer(PLAYER_TWO_UUID);
            return;
        }

        spawnAttempted = true;

        ServerWorld serverWorld = server.getWorld(client.world.getRegistryKey());
        if (serverWorld == null || client.player == null) {
            return;
        }

        Vec3d forward = client.player.getRotationVec(1.0f).multiply(3.0);
        Vec3d spawnPos = client.player.getPos().add(forward.x, 0.0, forward.z);

        yaw = client.player.getYaw();
        pitch = 0.0f;

        server.execute(() -> {
            ServerPlayerEntity existing = server.getPlayerManager().getPlayer(PLAYER_TWO_UUID);
            if (existing != null) {
                playerTwo = existing;
                return;
            }

            GameProfile profile = new GameProfile(PLAYER_TWO_UUID, PLAYER_TWO_NAME);
            ServerPlayerEntity fake = new ServerPlayerEntity(server, serverWorld, profile);
            fake.refreshPositionAndAngles(spawnPos.x, spawnPos.y, spawnPos.z, yaw, pitch);
            fake.setHealth(20.0f);

            FakeClientConnection connection = new FakeClientConnection();
            playerTwoConnection = connection;

            server.getPlayerManager().onPlayerConnect(connection, fake);
            fake.refreshPositionAndAngles(spawnPos.x, spawnPos.y, spawnPos.z, yaw, pitch);
            fake.setYaw(yaw);
            fake.setPitch(pitch);
            fake.setHeadYaw(yaw);
            fake.setBodyYaw(yaw);

            playerTwo = fake;
        });

        if (!announcedSpawn) {
            client.player.sendMessage(Text.literal("Local Multiplayer: server-spawned Player2 requested."), false);
            announcedSpawn = true;
        }
    }

    private void readControllerAndMovePlayerTwo(MinecraftClient client, MinecraftServer server) {
        ServerPlayerEntity serverPlayer = server.getPlayerManager().getPlayer(PLAYER_TWO_UUID);
        if (serverPlayer == null) {
            playerTwo = null;
            return;
        }

        playerTwo = serverPlayer;

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

        float inputLeftX = lastLeftX;
        float inputLeftY = lastLeftY;
        float inputRightX = lastRightX;
        float inputRightY = lastRightY;

        server.execute(() -> movePlayerTwoOnServer(server, inputLeftX, inputLeftY, inputRightX, inputRightY, jumpPressed));
    }

    private void movePlayerTwoOnServer(MinecraftServer server, float leftX, float leftY, float rightXInput, float rightYInput, boolean jumpPressed) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(PLAYER_TWO_UUID);
        if (player == null) {
            playerTwo = null;
            return;
        }

        yaw = MathHelper.wrapDegrees(yaw + rightXInput * LOOK_SPEED_DEGREES);
        pitch = MathHelper.clamp(pitch + rightYInput * LOOK_SPEED_DEGREES, -80.0f, 80.0f);

        float forwardInput = -leftY;
        float strafeInput = leftX;
        float yawRadians = yaw * ((float) Math.PI / 180.0f);

        double forwardX = -MathHelper.sin(yawRadians);
        double forwardZ = MathHelper.cos(yawRadians);
        double rightX = MathHelper.cos(yawRadians);
        double rightZ = MathHelper.sin(yawRadians);

        Vec3d horizontal = new Vec3d(
                (forwardX * forwardInput + rightX * strafeInput) * WALK_SPEED_PER_TICK,
                0.0,
                (forwardZ * forwardInput + rightZ * strafeInput) * WALK_SPEED_PER_TICK
        );

        if (horizontal.horizontalLengthSquared() > WALK_SPEED_PER_TICK * WALK_SPEED_PER_TICK) {
            horizontal = horizontal.normalize().multiply(WALK_SPEED_PER_TICK);
        }

        player.setYaw(yaw);
        player.setPitch(pitch);
        player.setHeadYaw(yaw);
        player.setBodyYaw(yaw);

        if (jumpPressed && player.isOnGround()) {
            Vec3d velocity = player.getVelocity();
            player.setVelocity(velocity.x, JUMP_VELOCITY, velocity.z);
        }

        if (horizontal.lengthSquared() > 0.0) {
            player.move(MovementType.PLAYER, horizontal);
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

    private void renderDebug(MinecraftClient client, DrawContext context) {
        if (client == null || client.player == null) {
            return;
        }

        int x = 8;
        int y = 8;
        int color = 0xFFFFFF;

        context.drawText(client.textRenderer, Text.literal("Local Multiplayer"), x, y, color, true);
        context.drawText(client.textRenderer, Text.literal("Mode: server-spawned fake player"), x, y + 10, color, true);
        context.drawText(client.textRenderer, Text.literal("Player2: " + (playerTwo == null ? "missing" : "server entity")), x, y + 20, color, true);
        context.drawText(client.textRenderer, Text.literal("Controller: " + (getActiveGamepad() == -1 ? "missing" : "ready")), x, y + 30, color, true);
        context.drawText(client.textRenderer, Text.literal(String.format("LX %.2f LY %.2f RX %.2f RY %.2f", lastLeftX, lastLeftY, lastRightX, lastRightY)), x, y + 40, color, true);

        if (playerTwo != null) {
            Vec3d pos = playerTwo.getPos();
            context.drawText(client.textRenderer, Text.literal(String.format("P2 X %.1f Y %.1f Z %.1f", pos.x, pos.y, pos.z)), x, y + 50, color, true);
        }
    }

    private void removePlayerTwo(MinecraftServer server) {
        if (server == null) {
            return;
        }

        server.execute(() -> {
            ServerPlayerEntity existing = server.getPlayerManager().getPlayer(PLAYER_TWO_UUID);
            if (existing != null) {
                server.getPlayerManager().remove(existing);
            }
        });
    }

    private void resetLocalState() {
        activeServer = null;
        playerTwo = null;
        playerTwoConnection = null;
        spawnAttempted = false;
        activeGamepad = -1;
        announcedController = false;
        announcedSpawn = false;
        yaw = 0.0f;
        pitch = 0.0f;
        lastLeftX = 0.0f;
        lastLeftY = 0.0f;
        lastRightX = 0.0f;
        lastRightY = 0.0f;
    }

    private static final class FakeClientConnection extends ClientConnection {
        private static final SocketAddress LOCAL_FAKE_ADDRESS = new SocketAddress() { };
        private boolean open = true;

        private FakeClientConnection() {
            super(NetworkSide.SERVERBOUND);
        }

        @Override
        public void send(Packet<?> packet) {
            // Server-spawned fake player has no real remote client to receive packets.
        }

        @Override
        public void send(Packet<?> packet, PacketCallbacks callbacks) {
            // Server-spawned fake player has no real remote client to receive packets.
        }

        @Override
        public void disconnect(Text disconnectReason) {
            open = false;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public boolean isLocal() {
            return true;
        }

        @Override
        public SocketAddress getAddress() {
            return LOCAL_FAKE_ADDRESS;
        }

        @Override
        public void disableAutoRead() {
        }

        @Override
        public void handleDisconnection() {
            open = false;
        }
    }
}
