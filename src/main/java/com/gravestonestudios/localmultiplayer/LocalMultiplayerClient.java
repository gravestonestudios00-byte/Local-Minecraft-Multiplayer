package com.gravestonestudios.localmultiplayer;

import com.mojang.authlib.GameProfile;
import io.netty.channel.Channel;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.ChatVisibility;
import net.minecraft.entity.MovementType;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkState;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.listener.ClientLoginPacketListener;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.listener.TickablePacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.handshake.HandshakeC2SPacket;
import net.minecraft.network.packet.c2s.login.LoginHelloC2SPacket;
import net.minecraft.network.packet.c2s.login.LoginQueryResponseC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientSettingsC2SPacket;
import net.minecraft.network.packet.c2s.play.KeepAliveC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayPongC2SPacket;
import net.minecraft.network.packet.c2s.play.TeleportConfirmC2SPacket;
import net.minecraft.network.packet.s2c.login.LoginCompressionS2CPacket;
import net.minecraft.network.packet.s2c.login.LoginDisconnectS2CPacket;
import net.minecraft.network.packet.s2c.login.LoginHelloS2CPacket;
import net.minecraft.network.packet.s2c.login.LoginQueryRequestS2CPacket;
import net.minecraft.network.packet.s2c.login.LoginSuccessS2CPacket;
import net.minecraft.network.packet.s2c.play.DisconnectS2CPacket;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.KeepAliveS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayPingS2CPacket;
import net.minecraft.network.packet.s2c.play.BundleS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerNetworkIo;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Arm;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWGamepadState;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.SocketAddress;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class LocalMultiplayerClient implements ClientModInitializer {

    private static final String SECOND_PLAYER_NAME = "Player2";
    private static final UUID SECOND_PLAYER_UUID = UUID.nameUUIDFromBytes("LocalMultiplayerPlayerTwo".getBytes());
    private static final double WALK_SPEED_PER_TICK = 0.16;
    private static final double SNEAK_SPEED_PER_TICK = 0.07;
    private static final double JUMP_VELOCITY = 0.42;
    private static final double GRAVITY_PER_TICK = 0.08;
    private static final double TERMINAL_VELOCITY = -3.92;
    private static final float LOOK_SPEED_DEGREES = 4.0f;

    private static final HeadlessClientState secondClientState = new HeadlessClientState();

    private static ClientConnection secondClientConnection;
    private static SocketAddress secondClientLocalAddress;
    private static int headlessConnectionTicks = 0;
    private static int reconnectCooldownTicks = 0;
    private static int activeGamepad = -1;
    private static boolean announcedGamepad = false;
    private static boolean announcedConnection = false;

    public static UUID getSecondPlayerUuid() {
        return SECOND_PLAYER_UUID;
    }

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null || client.getServer() == null) {
                cleanupSecondClient(true);
                return;
            }

            if (secondClientConnection != null) {
                if (secondClientConnection.isOpen()) {
                    secondClientConnection.tick();
                    headlessConnectionTicks++;
                    markSecondClientReadyIfServerJoined(client);
                } else {
                    secondClientConnection.handleDisconnection();
                    cleanupSecondClient(false);
                    reconnectCooldownTicks = 60;
                    return;
                }
            }

            if (secondClientConnection == null) {
                if (reconnectCooldownTicks > 0) {
                    reconnectCooldownTicks--;
                    return;
                }

                startSecondClient(client);
            }

            updateControllerMovement(client);
        });

        HudRenderCallback.EVENT.register((context, tickDelta) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            renderSecondPlayerDebug(client, context);
        });
    }

    private void startSecondClient(MinecraftClient client) {
        MinecraftServer server = client.getServer();
        if (server == null || server.getPlayerManager().getPlayer(SECOND_PLAYER_UUID) != null) {
            return;
        }

        ServerNetworkIo networkIo = server.getNetworkIo();
        if (networkIo == null) {
            sendStatusMessage(client, "Local Multiplayer: server network IO unavailable.");
            return;
        }

        try {
            if (secondClientLocalAddress == null) {
                secondClientLocalAddress = networkIo.bindLocal();
            }

            GameProfile profile = new GameProfile(SECOND_PLAYER_UUID, SECOND_PLAYER_NAME);
            secondClientState.reset();
            headlessConnectionTicks = 0;
            secondClientConnection = ClientConnection.connectLocal(secondClientLocalAddress);
            secondClientConnection.setPacketListener(new HeadlessLoginPacketListener(secondClientConnection, profile, secondClientState));
            secondClientConnection.send(new HandshakeC2SPacket("localmultiplayer", 0, NetworkState.LOGIN));
            secondClientConnection.send(new LoginHelloC2SPacket(profile.getName(), Optional.of(profile.getId())));
        } catch (Exception e) {
            cleanupSecondClient(false);
            reconnectCooldownTicks = 60;
            sendStatusMessage(client, "Local Multiplayer: failed to start Player2 client.");
        }
    }

    private void markSecondClientReadyIfServerJoined(MinecraftClient client) {
        if (secondClientConnection == null || secondClientState.playReady) {
            return;
        }

        MinecraftServer server = client.getServer();
        if (server == null) {
            return;
        }

        ServerPlayerEntity player = server.getPlayerManager().getPlayer(SECOND_PLAYER_UUID);
        if (player == null) {
            return;
        }

        if (!secondClientState.sentClientSettings) {
            secondClientState.sentClientSettings = true;
            secondClientConnection.send(new ClientSettingsC2SPacket("en_us", 12, ChatVisibility.FULL, true, 0x7f, Arm.RIGHT, false, true));
        }

        secondClientState.setPosition(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch(), player.isOnGround());
    }

    private void updateControllerMovement(MinecraftClient client) {
        if (secondClientConnection == null || !secondClientConnection.isOpen() || !secondClientState.playReady) {
            return;
        }

        int gamepad = getActiveGamepad();
        if (gamepad == -1) {
            return;
        }

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

        if (!reconcileSecondClientPosition(client)) {
            return;
        }

        float moveX = applyDeadzone(state.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X));
        float moveY = applyDeadzone(state.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y));
        float lookX = applyDeadzone(state.axes(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_X));
        float lookY = applyDeadzone(state.axes(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_Y));
        boolean jump = state.buttons(GLFW.GLFW_GAMEPAD_BUTTON_A) == GLFW.GLFW_PRESS;
        boolean sneak = state.buttons(GLFW.GLFW_GAMEPAD_BUTTON_B) == GLFW.GLFW_PRESS;

        secondClientState.yaw = MathHelper.wrapDegrees(secondClientState.yaw + lookX * LOOK_SPEED_DEGREES);
        secondClientState.pitch = MathHelper.clamp(secondClientState.pitch + lookY * LOOK_SPEED_DEGREES, -80.0f, 80.0f);

        double speed = sneak ? SNEAK_SPEED_PER_TICK : WALK_SPEED_PER_TICK;
        float forward = -moveY;
        float strafe = moveX;
        float yawRadians = secondClientState.yaw * ((float) Math.PI / 180.0f);

        double forwardX = -MathHelper.sin(yawRadians);
        double forwardZ = MathHelper.cos(yawRadians);
        double rightX = MathHelper.cos(yawRadians);
        double rightZ = MathHelper.sin(yawRadians);
        Vec3d horizontal = new Vec3d(
                (forwardX * forward + rightX * strafe) * speed,
                0.0,
                (forwardZ * forward + rightZ * strafe) * speed
        );

        if (horizontal.horizontalLengthSquared() > speed * speed) {
            horizontal = horizontal.normalize().multiply(speed);
        }

        if (jump && secondClientState.onGround) {
            secondClientState.velocityY = JUMP_VELOCITY;
            secondClientState.onGround = false;
        }

        if (!secondClientState.onGround) {
            secondClientState.velocityY = Math.max(TERMINAL_VELOCITY, secondClientState.velocityY - GRAVITY_PER_TICK);
        } else {
            secondClientState.velocityY = 0.0;
        }

        moveSecondPlayerOnServer(client, horizontal);
    }

    private boolean reconcileSecondClientPosition(MinecraftClient client) {
        MinecraftServer server = client.getServer();
        if (server == null) {
            return false;
        }

        ServerPlayerEntity player = server.getPlayerManager().getPlayer(SECOND_PLAYER_UUID);
        if (player == null) {
            return false;
        }

        if (!announcedConnection && client.player != null) {
            client.player.sendMessage(Text.literal("Local Multiplayer: Player2 joined as a real local client."), false);
            announcedConnection = true;
        }

        if (!secondClientState.hasPosition || player.getPos().squaredDistanceTo(secondClientState.x, secondClientState.y, secondClientState.z) > 16.0) {
            secondClientState.setPosition(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch(), player.isOnGround());
            return true;
        }

        secondClientState.syncPosition(player.getX(), player.getY(), player.getZ(), player.isOnGround());
        return true;
    }

    private void moveSecondPlayerOnServer(MinecraftClient client, Vec3d horizontal) {
        MinecraftServer server = client.getServer();
        if (server == null) {
            return;
        }

        ServerPlayerEntity player = server.getPlayerManager().getPlayer(SECOND_PLAYER_UUID);
        if (player == null) {
            return;
        }

        player.setYaw(secondClientState.yaw);
        player.setPitch(secondClientState.pitch);
        player.setHeadYaw(secondClientState.yaw);
        player.setBodyYaw(secondClientState.yaw);

        Vec3d movement = new Vec3d(horizontal.x, secondClientState.velocityY, horizontal.z);
        if (movement.lengthSquared() > 0.0) {
            player.move(MovementType.PLAYER, movement);
        }

        secondClientState.syncPosition(player.getX(), player.getY(), player.getZ(), player.isOnGround());
    }

    private void cleanupSecondClient(boolean clearLocalAddress) {
        ClientConnection connection = secondClientConnection;
        secondClientConnection = null;
        headlessConnectionTicks = 0;
        secondClientState.reset();
        if (clearLocalAddress) {
            secondClientLocalAddress = null;
            reconnectCooldownTicks = 0;
        }
        activeGamepad = -1;
        announcedGamepad = false;
        announcedConnection = false;

        if (connection == null) {
            return;
        }

        closeConnectionNow(connection);
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

    private void sendStatusMessage(MinecraftClient client, String message) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(message), false);
        }
    }

    private void renderSecondPlayerDebug(MinecraftClient client, DrawContext context) {
        if (client == null || client.player == null) {
            return;
        }

        int x = 8;
        int y = 8;
        int color = 0xFFFFFF;

        context.drawText(client.textRenderer, Text.literal("Local Multiplayer"), x, y, color, true);
            String connectionLine = "Client: " + (secondClientConnection != null && secondClientConnection.isOpen() ? "open" : "closed");
        context.drawText(client.textRenderer, Text.literal(connectionLine), x, y + 10, color, true);

        MinecraftServer server = client.getServer();
        ServerPlayerEntity player = server == null ? null : server.getPlayerManager().getPlayer(SECOND_PLAYER_UUID);
        if (player == null) {
            context.drawText(client.textRenderer, Text.literal("Player2: not joined"), x, y + 20, color, true);
        } else {
            Vec3d pos = player.getPos();
            String playerLine = String.format("Player2: joined | X: %.2f Y: %.2f Z: %.2f", pos.x, pos.y, pos.z);
            context.drawText(client.textRenderer, Text.literal(playerLine), x, y + 20, color, true);
        }

        String controllerLine = "Controller: " + (getActiveGamepad() == -1 ? "missing" : "ready");
        context.drawText(client.textRenderer, Text.literal(controllerLine), x, y + 30, color, true);

        String stateLine = String.format(
                "Headless: %s | yaw: %.1f | retry: %d",
                secondClientState.playReady ? "play" : "login",
                secondClientState.yaw,
                reconnectCooldownTicks
        );
        context.drawText(client.textRenderer, Text.literal(stateLine), x, y + 40, color, true);

        if (!secondClientState.lastDisconnectReason.isEmpty()) {
            context.drawText(client.textRenderer, Text.literal("Last: " + secondClientState.lastDisconnectReason), x, y + 50, color, true);
        }
    }

    private static final class HeadlessClientState {
        private boolean playReady;
        private boolean hasPosition;
        private boolean sentClientSettings;
        private String lastDisconnectReason = "";
        private double x;
        private double y;
        private double z;
        private double velocityY;
        private float yaw;
        private float pitch;
        private boolean onGround = true;

        private void reset() {
            playReady = false;
            hasPosition = false;
            sentClientSettings = false;
            x = 0.0;
            y = 0.0;
            z = 0.0;
            velocityY = 0.0;
            yaw = 0.0f;
            pitch = 0.0f;
            onGround = true;
        }

        private void setPosition(double x, double y, double z, float yaw, float pitch, boolean onGround) {
            this.playReady = true;
            this.hasPosition = true;
            this.x = x;
            this.y = y;
            this.z = z;
            this.velocityY = 0.0;
            this.yaw = yaw;
            this.pitch = pitch;
            this.onGround = onGround;
        }

        private void syncPosition(double x, double y, double z, boolean onGround) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.onGround = onGround;
            if (this.onGround && this.velocityY < 0.0) {
                this.velocityY = 0.0;
            }
        }
    }

    private static final class HeadlessLoginPacketListener implements ClientLoginPacketListener {
        private final ClientConnection connection;
        private final GameProfile profile;
        private final HeadlessClientState state;

        private HeadlessLoginPacketListener(ClientConnection connection, GameProfile profile, HeadlessClientState state) {
            this.connection = connection;
            this.profile = profile;
            this.state = state;
        }

        @Override
        public void onHello(LoginHelloS2CPacket packet) {
            closeConnectionNow(connection);
        }

        @Override
        public void onSuccess(LoginSuccessS2CPacket packet) {
            connection.setState(NetworkState.PLAY);
            connection.setPacketListener(createPlayPacketListener(connection, state));
        }

        @Override
        public void onDisconnect(LoginDisconnectS2CPacket packet) {
            state.lastDisconnectReason = packet.getReason().getString();
            state.reset();
            closeConnectionNow(connection);
        }

        @Override
        public void onCompression(LoginCompressionS2CPacket packet) {
            if (!connection.isLocal()) {
                connection.setCompressionThreshold(packet.getCompressionThreshold(), false);
            }
        }

        @Override
        public void onQueryRequest(LoginQueryRequestS2CPacket packet) {
            connection.send(new LoginQueryResponseC2SPacket(packet.getQueryId(), (PacketByteBuf) null));
        }

        @Override
        public void onDisconnected(Text reason) {
            state.reset();
        }

        @Override
        public boolean isConnectionOpen() {
            return connection.isOpen();
        }

        private ClientPlayPacketListener createPlayPacketListener(ClientConnection connection, HeadlessClientState state) {
            InvocationHandler handler = new HeadlessPlayInvocationHandler(connection, state);
            return (ClientPlayPacketListener) Proxy.newProxyInstance(
                    ClientPlayPacketListener.class.getClassLoader(),
                    new Class[]{ClientPlayPacketListener.class, TickablePacketListener.class},
                    handler
            );
        }
    }

    private static final class HeadlessPlayInvocationHandler implements InvocationHandler {
        private final ClientConnection connection;
        private final HeadlessClientState state;

        private HeadlessPlayInvocationHandler(ClientConnection connection, HeadlessClientState state) {
            this.connection = connection;
            this.state = state;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("isConnectionOpen".equals(name)) {
                return connection.isOpen();
            }
            if ("shouldCrashOnException".equals(name)) {
                return false;
            }
            if ("tick".equals(name)) {
                return null;
            }
            if ("onDisconnected".equals(name)) {
                if (args != null && args.length == 1 && args[0] instanceof Text reason) {
                    state.lastDisconnectReason = reason.getString();
                }
                state.reset();
                return null;
            }
            if ("onDisconnect".equals(name)) {
                if (args != null && args.length == 1 && args[0] instanceof DisconnectS2CPacket packet) {
                    state.lastDisconnectReason = packet.getReason().getString();
                }
                state.reset();
                closeConnectionNow(connection);
                return null;
            }
            if ("onGameJoin".equals(name) && args != null && args.length == 1 && args[0] instanceof GameJoinS2CPacket) {
                state.playReady = true;
                if (!state.sentClientSettings) {
                    state.sentClientSettings = true;
                    connection.send(new ClientSettingsC2SPacket("en_us", 12, ChatVisibility.FULL, true, 0x7f, Arm.RIGHT, false, true));
                }
                return null;
            }
            if ("onKeepAlive".equals(name) && args != null && args.length == 1 && args[0] instanceof KeepAliveS2CPacket packet) {
                connection.send(new KeepAliveC2SPacket(packet.getId()));
                return null;
            }
            if ("onPing".equals(name) && args != null && args.length == 1 && args[0] instanceof PlayPingS2CPacket packet) {
                connection.send(new PlayPongC2SPacket(packet.getParameter()));
                return null;
            }
            if ("onBundle".equals(name) && args != null && args.length == 1 && args[0] instanceof BundleS2CPacket packet) {
                for (Packet<ClientPlayPacketListener> bundledPacket : packet.getPackets()) {
                    bundledPacket.apply((ClientPlayPacketListener) proxy);
                }
                return null;
            }
            if ("onPlayerPositionLook".equals(name) && args != null && args.length == 1 && args[0] instanceof PlayerPositionLookS2CPacket packet) {
                applyServerPosition(packet);
                connection.send(new TeleportConfirmC2SPacket(packet.getTeleportId()));
                return null;
            }

            return defaultValue(method.getReturnType());
        }

        private void applyServerPosition(PlayerPositionLookS2CPacket packet) {
            Set<PositionFlag> flags = packet.getFlags();
            double x = flags.contains(PositionFlag.X) && state.hasPosition ? state.x + packet.getX() : packet.getX();
            double y = flags.contains(PositionFlag.Y) && state.hasPosition ? state.y + packet.getY() : packet.getY();
            double z = flags.contains(PositionFlag.Z) && state.hasPosition ? state.z + packet.getZ() : packet.getZ();
            float yaw = flags.contains(PositionFlag.Y_ROT) && state.hasPosition ? state.yaw + packet.getYaw() : packet.getYaw();
            float pitch = flags.contains(PositionFlag.X_ROT) && state.hasPosition ? state.pitch + packet.getPitch() : packet.getPitch();
            state.setPosition(x, y, z, yaw, pitch, true);
        }

        private Object defaultValue(Class<?> returnType) {
            if (returnType == Boolean.TYPE) {
                return false;
            }
            if (returnType == Byte.TYPE) {
                return (byte) 0;
            }
            if (returnType == Short.TYPE) {
                return (short) 0;
            }
            if (returnType == Integer.TYPE) {
                return 0;
            }
            if (returnType == Long.TYPE) {
                return 0L;
            }
            if (returnType == Float.TYPE) {
                return 0.0f;
            }
            if (returnType == Double.TYPE) {
                return 0.0;
            }
            if (returnType == Character.TYPE) {
                return '\0';
            }
            return null;
        }
    }

    private static void closeConnectionNow(ClientConnection connection) {
        if (connection == null) {
            return;
        }

        try {
            Channel channel = connection.channel;
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        } catch (RuntimeException ignored) {
        }
    }
}
