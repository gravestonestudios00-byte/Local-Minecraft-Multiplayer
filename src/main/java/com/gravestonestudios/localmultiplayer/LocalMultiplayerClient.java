package com.gravestonestudios.localmultiplayer;

import com.mojang.authlib.GameProfile;
import io.netty.channel.Channel;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
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
import net.minecraft.util.Uuids;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.glfw.GLFWGamepadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class LocalMultiplayerClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("LocalMultiplayer");

    private static final String SECOND_PLAYER_NAME = "Player2";
    // The integrated server ignores the UUID the client sends in LoginHello: for a local
    // connection it skips auth and builds the profile from the name only, assigning the offline
    // UUID. We must look the player up by that same UUID or getPlayer(...) always returns null.
    private static final UUID SECOND_PLAYER_UUID = Uuids.getOfflinePlayerUuid(SECOND_PLAYER_NAME);
    private static final double WALK_SPEED_PER_TICK = 0.16;
    private static final double SNEAK_SPEED_PER_TICK = 0.07;
    private static final double JUMP_VELOCITY = 0.42;
    private static final double GRAVITY_PER_TICK = 0.08;
    private static final double TERMINAL_VELOCITY = -3.92;
    private static final float LOOK_SPEED_DEGREES = 4.0f;

    private static final HeadlessClientState secondClientState = new HeadlessClientState();
    public static boolean isRenderingSecondView = false;

    private static ClientConnection secondClientConnection;
    private static SocketAddress secondClientLocalAddress;
    private static int headlessConnectionTicks = 0;
    // Task 1: once a single failure occurs we refuse to auto-reconnect for the rest of the
    // session. It is only re-enabled by a full reset (leaving the world / server going away).
    private static boolean autoReconnectDisabled = false;
    private static int activeGamepad = -1;
    private static boolean announcedGamepad = false;
    private static boolean announcedConnection = false;

    private static final int MAX_SECOND_FRAMEBUFFER_WIDTH = 1280;
    private static final int MAX_SECOND_FRAMEBUFFER_HEIGHT = 720;

    private static long secondWindowHandle = 0;
    private static boolean secondWindowCapabilitiesInitialized = false;
    private static Framebuffer secondPlayerFramebuffer;

    public static Framebuffer getSecondPlayerFramebuffer() {
        return secondPlayerFramebuffer;
    }

    public static long getSecondWindowHandle() {
        return secondWindowHandle;
    }

    public static boolean areCapabilitiesInitialized() {
        return secondWindowCapabilitiesInitialized;
    }

    public static UUID getSecondPlayerUuid() {
        return SECOND_PLAYER_UUID;
    }

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (secondWindowHandle == 0 && client.getWindow() != null) {
                initSecondWindow(client);
            }

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
                    // The connection dropped while we still had a live session: treat it as a failure.
                    secondClientConnection.handleDisconnection();
                    printDisconnectReason("connection-closed", secondClientState.lastDisconnectReason);
                    disableAutoReconnect("connection closed unexpectedly");
                    cleanupSecondClient(false);
                    return;
                }
            }

            if (secondClientConnection == null) {
                // Task 1: never auto-reconnect once a failure disabled it this session.
                if (autoReconnectDisabled) {
                    return;
                }

                // Task 6: only (re)connect once Player2 has been fully removed from the
                // server player manager. Connecting while the old entity still exists causes
                // a duplicate-login failure.
                MinecraftServer server = client.getServer();
                if (server == null || server.getPlayerManager().getPlayer(SECOND_PLAYER_UUID) != null) {
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

    private void initSecondWindow(MinecraftClient client) {
        long mainWindow = client.getWindow().getHandle();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        // Share context with main window to reuse textures/models
        secondWindowHandle = GLFW.glfwCreateWindow(800, 600, "Player 2 - Local Multiplayer", 0, mainWindow);
        
        if (secondWindowHandle != 0) {
            // Temporarily switch context to initialize GL capabilities for this window
            GLFW.glfwMakeContextCurrent(secondWindowHandle);
            GL.createCapabilities();
            secondWindowCapabilitiesInitialized = true;
            
            // Switch back to main window
            GLFW.glfwMakeContextCurrent(mainWindow);
            GLFW.glfwShowWindow(secondWindowHandle);
            LOGGER.info("[LocalMultiplayer] Player 2 window initialized.");

            // Create the off-screen buffer in the main context
            if (secondPlayerFramebuffer != null) {
                secondPlayerFramebuffer.delete();
            }
            int fbWidth = Math.min(client.getWindow().getFramebufferWidth(), MAX_SECOND_FRAMEBUFFER_WIDTH);
            int fbHeight = Math.min(client.getWindow().getFramebufferHeight(), MAX_SECOND_FRAMEBUFFER_HEIGHT);
            secondPlayerFramebuffer = new SimpleFramebuffer(fbWidth, fbHeight, true, MinecraftClient.IS_SYSTEM_MAC);
            secondPlayerFramebuffer.setClearColor(0f, 0f, 0f, 1f);
            // Debug: report framebuffer dimensions and handles
            try {
                LOGGER.info("[LocalMultiplayer] Player2 framebuffer created: windowHandle={} size={}x{} mainWindowHandle={}", secondWindowHandle, secondPlayerFramebuffer.textureWidth, secondPlayerFramebuffer.textureHeight, mainWindow);
            } catch (Throwable t) {
                LOGGER.warn("[LocalMultiplayer] Failed to log Player2 framebuffer info", t);
            }
        }
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
            LOGGER.info("[LocalMultiplayer] Started Player2 local client.");
        } catch (Exception e) {
            LOGGER.error("[LocalMultiplayer] Failed to start Player2 client.", e);
            cleanupSecondClient(false);
            disableAutoReconnect("exception while starting Player2 client");
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
            // A full reset (leaving the world / server gone) re-enables auto-reconnect for the
            // next session. A failure-driven cleanup leaves auto-reconnect disabled (Task 1).
            autoReconnectDisabled = false;
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
                "Headless: %s | yaw: %.1f | reconnect: %s",
                secondClientState.playReady ? "play" : "login",
                secondClientState.yaw,
                autoReconnectDisabled ? "disabled" : "enabled"
        );
        context.drawText(client.textRenderer, Text.literal(stateLine), x, y + 40, color, true);

        if (!secondClientState.lastDisconnectReason.isEmpty()) {
            context.drawText(client.textRenderer, Text.literal("Last: " + secondClientState.lastDisconnectReason), x, y + 50, color, true);
        }
    }

    // Task 2: print the exact disconnect reason every time the headless client is told to leave.
    private static void printDisconnectReason(String context, String reason) {
        if (reason == null || reason.isEmpty()) {
            LOGGER.warn("[LocalMultiplayer] {} - Player2 disconnect reason: <none provided>", context);
        } else {
            LOGGER.warn("[LocalMultiplayer] {} - Player2 disconnect reason: {}", context, reason);
        }
    }

    // Task 1: latch auto-reconnect off after the first failure and say why.
    private static void disableAutoReconnect(String cause) {
        if (!autoReconnectDisabled) {
            autoReconnectDisabled = true;
            LOGGER.warn("[LocalMultiplayer] Auto-reconnect disabled after failure ({}). Player2 will not reconnect this session.", cause);
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
            // Task 3: only close on a LoginHello when we are 100% certain encryption must never
            // happen. The server only sends an encryption request on non-local connections, so on
            // a local connection encryption can never legitimately occur and a Hello here is an
            // error we can safely close. On any non-local connection we are NOT certain encryption
            // should never happen, so we must not silently close the connection - log loudly instead.
            if (connection.isLocal()) {
                LOGGER.warn("[LocalMultiplayer] Unexpected LoginHello on a local connection; encryption must never happen here. Closing.");
                state.lastDisconnectReason = "Unexpected encryption request on local connection";
                disableAutoReconnect("unexpected encryption request on local connection");
                closeConnectionNow(connection);
            } else {
                LOGGER.error("[LocalMultiplayer] Server requested encryption (LoginHello) on a non-local connection, but encryption is not implemented. Leaving the connection OPEN rather than assuming it should never happen.");
            }
        }

        @Override
        public void onSuccess(LoginSuccessS2CPacket packet) {
            connection.setState(NetworkState.PLAY);
            connection.setPacketListener(new HeadlessPlayPacketListener(connection, state));
        }

        @Override
        public void onDisconnect(LoginDisconnectS2CPacket packet) {
            String reason = packet.getReason().getString();
            state.lastDisconnectReason = reason;
            printDisconnectReason("login/onDisconnect", reason);
            state.reset();
            disableAutoReconnect("login disconnect");
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
            if (reason != null && !reason.getString().isEmpty()) {
                state.lastDisconnectReason = reason.getString();
            }
            printDisconnectReason("login/onDisconnected", state.lastDisconnectReason);
            state.reset();
        }

        @Override
        public boolean isConnectionOpen() {
            return connection.isOpen();
        }
    }

    // Task 4: a concrete ClientPlayPacketListener implementation that replaces the reflection
    // Proxy. Packets we actually care about are handled explicitly; every other packet method
    // is overridden to call logUnhandled (Task 5) so nothing is silently dropped.
    private static final class HeadlessPlayPacketListener implements ClientPlayPacketListener, TickablePacketListener {
        private final ClientConnection connection;
        private final HeadlessClientState state;

        private HeadlessPlayPacketListener(ClientConnection connection, HeadlessClientState state) {
            this.connection = connection;
            this.state = state;
        }

        // ---- listener lifecycle ----

        @Override
        public boolean isConnectionOpen() {
            return connection.isOpen();
        }

        @Override
        public boolean shouldCrashOnException() {
            return false;
        }

        @Override
        public void tick() {
        }

        @Override
        public void onDisconnected(Text reason) {
            if (reason != null && !reason.getString().isEmpty()) {
                state.lastDisconnectReason = reason.getString();
            }
            printDisconnectReason("play/onDisconnected", state.lastDisconnectReason);
            state.reset();
        }

        @Override
        public void onDisconnect(DisconnectS2CPacket packet) {
            String reason = packet.getReason().getString();
            state.lastDisconnectReason = reason;
            printDisconnectReason("play/onDisconnect", reason);
            state.reset();
            disableAutoReconnect("server disconnect packet");
            closeConnectionNow(connection);
        }

        // ---- handled play packets ----

        @Override
        public void onGameJoin(GameJoinS2CPacket packet) {
            state.playReady = true;
            if (!state.sentClientSettings) {
                state.sentClientSettings = true;
                connection.send(new ClientSettingsC2SPacket("en_us", 12, ChatVisibility.FULL, true, 0x7f, Arm.RIGHT, false, true));
            }
        }

        @Override
        public void onKeepAlive(KeepAliveS2CPacket packet) {
            connection.send(new KeepAliveC2SPacket(packet.getId()));
        }

        @Override
        public void onPing(PlayPingS2CPacket packet) {
            connection.send(new PlayPongC2SPacket(packet.getParameter()));
        }

        @Override
        public void onBundle(BundleS2CPacket packet) {
            for (Packet<ClientPlayPacketListener> bundledPacket : packet.getPackets()) {
                bundledPacket.apply(this);
            }
        }

        @Override
        public void onPlayerPositionLook(PlayerPositionLookS2CPacket packet) {
            applyServerPosition(packet);
            connection.send(new TeleportConfirmC2SPacket(packet.getTeleportId()));
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

        // Task 5: log every packet method we do not explicitly handle.
        private static void logUnhandled(String method) {
            LOGGER.debug("[LocalMultiplayer] Unhandled play packet method: {}", method);
        }

        // ---- unhandled play packets (logged only) ----

        @Override public void onEntitySpawn(net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket packet) { logUnhandled("onEntitySpawn"); }
        @Override public void onExperienceOrbSpawn(net.minecraft.network.packet.s2c.play.ExperienceOrbSpawnS2CPacket packet) { logUnhandled("onExperienceOrbSpawn"); }
        @Override public void onEntityVelocityUpdate(net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket packet) { logUnhandled("onEntityVelocityUpdate"); }
        @Override public void onEntityTrackerUpdate(net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket packet) { logUnhandled("onEntityTrackerUpdate"); }
        @Override public void onPlayerSpawn(net.minecraft.network.packet.s2c.play.PlayerSpawnS2CPacket packet) { logUnhandled("onPlayerSpawn"); }
        @Override public void onEntityPosition(net.minecraft.network.packet.s2c.play.EntityPositionS2CPacket packet) { logUnhandled("onEntityPosition"); }
        @Override public void onUpdateSelectedSlot(net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket packet) { logUnhandled("onUpdateSelectedSlot"); }
        @Override public void onEntity(net.minecraft.network.packet.s2c.play.EntityS2CPacket packet) { logUnhandled("onEntity"); }
        @Override public void onEntitySetHeadYaw(net.minecraft.network.packet.s2c.play.EntitySetHeadYawS2CPacket packet) { logUnhandled("onEntitySetHeadYaw"); }
        @Override public void onEntitiesDestroy(net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket packet) { logUnhandled("onEntitiesDestroy"); }
        @Override public void onChunkDeltaUpdate(net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket packet) { logUnhandled("onChunkDeltaUpdate"); }
        @Override public void onChunkData(net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket packet) { logUnhandled("onChunkData"); }
        @Override public void onChunkBiomeData(net.minecraft.network.packet.s2c.play.ChunkBiomeDataS2CPacket packet) { logUnhandled("onChunkBiomeData"); }
        @Override public void onUnloadChunk(net.minecraft.network.packet.s2c.play.UnloadChunkS2CPacket packet) { logUnhandled("onUnloadChunk"); }
        @Override public void onBlockUpdate(net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket packet) { logUnhandled("onBlockUpdate"); }
        @Override public void onItemPickupAnimation(net.minecraft.network.packet.s2c.play.ItemPickupAnimationS2CPacket packet) { logUnhandled("onItemPickupAnimation"); }
        @Override public void onGameMessage(net.minecraft.network.packet.s2c.play.GameMessageS2CPacket packet) { logUnhandled("onGameMessage"); }
        @Override public void onChatMessage(net.minecraft.network.packet.s2c.play.ChatMessageS2CPacket packet) { logUnhandled("onChatMessage"); }
        @Override public void onProfilelessChatMessage(net.minecraft.network.packet.s2c.play.ProfilelessChatMessageS2CPacket packet) { logUnhandled("onProfilelessChatMessage"); }
        @Override public void onRemoveMessage(net.minecraft.network.packet.s2c.play.RemoveMessageS2CPacket packet) { logUnhandled("onRemoveMessage"); }
        @Override public void onEntityAnimation(net.minecraft.network.packet.s2c.play.EntityAnimationS2CPacket packet) { logUnhandled("onEntityAnimation"); }
        @Override public void onDamageTilt(net.minecraft.network.packet.s2c.play.DamageTiltS2CPacket packet) { logUnhandled("onDamageTilt"); }
        @Override public void onWorldTimeUpdate(net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket packet) { logUnhandled("onWorldTimeUpdate"); }
        @Override public void onPlayerSpawnPosition(net.minecraft.network.packet.s2c.play.PlayerSpawnPositionS2CPacket packet) { logUnhandled("onPlayerSpawnPosition"); }
        @Override public void onEntityPassengersSet(net.minecraft.network.packet.s2c.play.EntityPassengersSetS2CPacket packet) { logUnhandled("onEntityPassengersSet"); }
        @Override public void onEntityAttach(net.minecraft.network.packet.s2c.play.EntityAttachS2CPacket packet) { logUnhandled("onEntityAttach"); }
        @Override public void onEntityStatus(net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket packet) { logUnhandled("onEntityStatus"); }
        @Override public void onEntityDamage(net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket packet) { logUnhandled("onEntityDamage"); }
        @Override public void onHealthUpdate(net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket packet) { logUnhandled("onHealthUpdate"); }
        @Override public void onExperienceBarUpdate(net.minecraft.network.packet.s2c.play.ExperienceBarUpdateS2CPacket packet) { logUnhandled("onExperienceBarUpdate"); }
        @Override public void onPlayerRespawn(net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket packet) { logUnhandled("onPlayerRespawn"); }
        @Override public void onExplosion(net.minecraft.network.packet.s2c.play.ExplosionS2CPacket packet) { logUnhandled("onExplosion"); }
        @Override public void onOpenHorseScreen(net.minecraft.network.packet.s2c.play.OpenHorseScreenS2CPacket packet) { logUnhandled("onOpenHorseScreen"); }
        @Override public void onOpenScreen(net.minecraft.network.packet.s2c.play.OpenScreenS2CPacket packet) { logUnhandled("onOpenScreen"); }
        @Override public void onScreenHandlerSlotUpdate(net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket packet) { logUnhandled("onScreenHandlerSlotUpdate"); }
        @Override public void onInventory(net.minecraft.network.packet.s2c.play.InventoryS2CPacket packet) { logUnhandled("onInventory"); }
        @Override public void onSignEditorOpen(net.minecraft.network.packet.s2c.play.SignEditorOpenS2CPacket packet) { logUnhandled("onSignEditorOpen"); }
        @Override public void onBlockEntityUpdate(net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket packet) { logUnhandled("onBlockEntityUpdate"); }
        @Override public void onScreenHandlerPropertyUpdate(net.minecraft.network.packet.s2c.play.ScreenHandlerPropertyUpdateS2CPacket packet) { logUnhandled("onScreenHandlerPropertyUpdate"); }
        @Override public void onEntityEquipmentUpdate(net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket packet) { logUnhandled("onEntityEquipmentUpdate"); }
        @Override public void onCloseScreen(net.minecraft.network.packet.s2c.play.CloseScreenS2CPacket packet) { logUnhandled("onCloseScreen"); }
        @Override public void onBlockEvent(net.minecraft.network.packet.s2c.play.BlockEventS2CPacket packet) { logUnhandled("onBlockEvent"); }
        @Override public void onBlockBreakingProgress(net.minecraft.network.packet.s2c.play.BlockBreakingProgressS2CPacket packet) { logUnhandled("onBlockBreakingProgress"); }
        @Override public void onGameStateChange(net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket packet) { logUnhandled("onGameStateChange"); }
        @Override public void onMapUpdate(net.minecraft.network.packet.s2c.play.MapUpdateS2CPacket packet) { logUnhandled("onMapUpdate"); }
        @Override public void onWorldEvent(net.minecraft.network.packet.s2c.play.WorldEventS2CPacket packet) { logUnhandled("onWorldEvent"); }
        @Override public void onAdvancements(net.minecraft.network.packet.s2c.play.AdvancementUpdateS2CPacket packet) { logUnhandled("onAdvancements"); }
        @Override public void onSelectAdvancementTab(net.minecraft.network.packet.s2c.play.SelectAdvancementTabS2CPacket packet) { logUnhandled("onSelectAdvancementTab"); }
        @Override public void onCommandTree(net.minecraft.network.packet.s2c.play.CommandTreeS2CPacket packet) { logUnhandled("onCommandTree"); }
        @Override public void onStopSound(net.minecraft.network.packet.s2c.play.StopSoundS2CPacket packet) { logUnhandled("onStopSound"); }
        @Override public void onCommandSuggestions(net.minecraft.network.packet.s2c.play.CommandSuggestionsS2CPacket packet) { logUnhandled("onCommandSuggestions"); }
        @Override public void onSynchronizeRecipes(net.minecraft.network.packet.s2c.play.SynchronizeRecipesS2CPacket packet) { logUnhandled("onSynchronizeRecipes"); }
        @Override public void onLookAt(net.minecraft.network.packet.s2c.play.LookAtS2CPacket packet) { logUnhandled("onLookAt"); }
        @Override public void onNbtQueryResponse(net.minecraft.network.packet.s2c.play.NbtQueryResponseS2CPacket packet) { logUnhandled("onNbtQueryResponse"); }
        @Override public void onStatistics(net.minecraft.network.packet.s2c.play.StatisticsS2CPacket packet) { logUnhandled("onStatistics"); }
        @Override public void onUnlockRecipes(net.minecraft.network.packet.s2c.play.UnlockRecipesS2CPacket packet) { logUnhandled("onUnlockRecipes"); }
        @Override public void onEntityStatusEffect(net.minecraft.network.packet.s2c.play.EntityStatusEffectS2CPacket packet) { logUnhandled("onEntityStatusEffect"); }
        @Override public void onSynchronizeTags(net.minecraft.network.packet.s2c.play.SynchronizeTagsS2CPacket packet) { logUnhandled("onSynchronizeTags"); }
        @Override public void onFeatures(net.minecraft.network.packet.s2c.play.FeaturesS2CPacket packet) { logUnhandled("onFeatures"); }
        @Override public void onEndCombat(net.minecraft.network.packet.s2c.play.EndCombatS2CPacket packet) { logUnhandled("onEndCombat"); }
        @Override public void onEnterCombat(net.minecraft.network.packet.s2c.play.EnterCombatS2CPacket packet) { logUnhandled("onEnterCombat"); }
        @Override public void onDeathMessage(net.minecraft.network.packet.s2c.play.DeathMessageS2CPacket packet) { logUnhandled("onDeathMessage"); }
        @Override public void onDifficulty(net.minecraft.network.packet.s2c.play.DifficultyS2CPacket packet) { logUnhandled("onDifficulty"); }
        @Override public void onSetCameraEntity(net.minecraft.network.packet.s2c.play.SetCameraEntityS2CPacket packet) { logUnhandled("onSetCameraEntity"); }
        @Override public void onWorldBorderInitialize(net.minecraft.network.packet.s2c.play.WorldBorderInitializeS2CPacket packet) { logUnhandled("onWorldBorderInitialize"); }
        @Override public void onWorldBorderCenterChanged(net.minecraft.network.packet.s2c.play.WorldBorderCenterChangedS2CPacket packet) { logUnhandled("onWorldBorderCenterChanged"); }
        @Override public void onWorldBorderInterpolateSize(net.minecraft.network.packet.s2c.play.WorldBorderInterpolateSizeS2CPacket packet) { logUnhandled("onWorldBorderInterpolateSize"); }
        @Override public void onWorldBorderSizeChanged(net.minecraft.network.packet.s2c.play.WorldBorderSizeChangedS2CPacket packet) { logUnhandled("onWorldBorderSizeChanged"); }
        @Override public void onWorldBorderWarningBlocksChanged(net.minecraft.network.packet.s2c.play.WorldBorderWarningBlocksChangedS2CPacket packet) { logUnhandled("onWorldBorderWarningBlocksChanged"); }
        @Override public void onWorldBorderWarningTimeChanged(net.minecraft.network.packet.s2c.play.WorldBorderWarningTimeChangedS2CPacket packet) { logUnhandled("onWorldBorderWarningTimeChanged"); }
        @Override public void onTitleClear(net.minecraft.network.packet.s2c.play.ClearTitleS2CPacket packet) { logUnhandled("onTitleClear"); }
        @Override public void onServerMetadata(net.minecraft.network.packet.s2c.play.ServerMetadataS2CPacket packet) { logUnhandled("onServerMetadata"); }
        @Override public void onChatSuggestions(net.minecraft.network.packet.s2c.play.ChatSuggestionsS2CPacket packet) { logUnhandled("onChatSuggestions"); }
        @Override public void onOverlayMessage(net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket packet) { logUnhandled("onOverlayMessage"); }
        @Override public void onTitle(net.minecraft.network.packet.s2c.play.TitleS2CPacket packet) { logUnhandled("onTitle"); }
        @Override public void onSubtitle(net.minecraft.network.packet.s2c.play.SubtitleS2CPacket packet) { logUnhandled("onSubtitle"); }
        @Override public void onTitleFade(net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket packet) { logUnhandled("onTitleFade"); }
        @Override public void onPlayerListHeader(net.minecraft.network.packet.s2c.play.PlayerListHeaderS2CPacket packet) { logUnhandled("onPlayerListHeader"); }
        @Override public void onRemoveEntityStatusEffect(net.minecraft.network.packet.s2c.play.RemoveEntityStatusEffectS2CPacket packet) { logUnhandled("onRemoveEntityStatusEffect"); }
        @Override public void onPlayerRemove(net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket packet) { logUnhandled("onPlayerRemove"); }
        @Override public void onPlayerList(net.minecraft.network.packet.s2c.play.PlayerListS2CPacket packet) { logUnhandled("onPlayerList"); }
        @Override public void onPlayerAbilities(net.minecraft.network.packet.s2c.play.PlayerAbilitiesS2CPacket packet) { logUnhandled("onPlayerAbilities"); }
        @Override public void onPlaySound(net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket packet) { logUnhandled("onPlaySound"); }
        @Override public void onPlaySoundFromEntity(net.minecraft.network.packet.s2c.play.PlaySoundFromEntityS2CPacket packet) { logUnhandled("onPlaySoundFromEntity"); }
        @Override public void onResourcePackSend(net.minecraft.network.packet.s2c.play.ResourcePackSendS2CPacket packet) { logUnhandled("onResourcePackSend"); }
        @Override public void onBossBar(net.minecraft.network.packet.s2c.play.BossBarS2CPacket packet) { logUnhandled("onBossBar"); }
        @Override public void onCooldownUpdate(net.minecraft.network.packet.s2c.play.CooldownUpdateS2CPacket packet) { logUnhandled("onCooldownUpdate"); }
        @Override public void onVehicleMove(net.minecraft.network.packet.s2c.play.VehicleMoveS2CPacket packet) { logUnhandled("onVehicleMove"); }
        @Override public void onOpenWrittenBook(net.minecraft.network.packet.s2c.play.OpenWrittenBookS2CPacket packet) { logUnhandled("onOpenWrittenBook"); }
        @Override public void onCustomPayload(net.minecraft.network.packet.s2c.play.CustomPayloadS2CPacket packet) { logUnhandled("onCustomPayload"); }
        @Override public void onScoreboardObjectiveUpdate(net.minecraft.network.packet.s2c.play.ScoreboardObjectiveUpdateS2CPacket packet) { logUnhandled("onScoreboardObjectiveUpdate"); }
        @Override public void onScoreboardPlayerUpdate(net.minecraft.network.packet.s2c.play.ScoreboardPlayerUpdateS2CPacket packet) { logUnhandled("onScoreboardPlayerUpdate"); }
        @Override public void onScoreboardDisplay(net.minecraft.network.packet.s2c.play.ScoreboardDisplayS2CPacket packet) { logUnhandled("onScoreboardDisplay"); }
        @Override public void onTeam(net.minecraft.network.packet.s2c.play.TeamS2CPacket packet) { logUnhandled("onTeam"); }
        @Override public void onParticle(net.minecraft.network.packet.s2c.play.ParticleS2CPacket packet) { logUnhandled("onParticle"); }
        @Override public void onEntityAttributes(net.minecraft.network.packet.s2c.play.EntityAttributesS2CPacket packet) { logUnhandled("onEntityAttributes"); }
        @Override public void onCraftFailedResponse(net.minecraft.network.packet.s2c.play.CraftFailedResponseS2CPacket packet) { logUnhandled("onCraftFailedResponse"); }
        @Override public void onLightUpdate(net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket packet) { logUnhandled("onLightUpdate"); }
        @Override public void onSetTradeOffers(net.minecraft.network.packet.s2c.play.SetTradeOffersS2CPacket packet) { logUnhandled("onSetTradeOffers"); }
        @Override public void onChunkLoadDistance(net.minecraft.network.packet.s2c.play.ChunkLoadDistanceS2CPacket packet) { logUnhandled("onChunkLoadDistance"); }
        @Override public void onSimulationDistance(net.minecraft.network.packet.s2c.play.SimulationDistanceS2CPacket packet) { logUnhandled("onSimulationDistance"); }
        @Override public void onChunkRenderDistanceCenter(net.minecraft.network.packet.s2c.play.ChunkRenderDistanceCenterS2CPacket packet) { logUnhandled("onChunkRenderDistanceCenter"); }
        @Override public void onPlayerActionResponse(net.minecraft.network.packet.s2c.play.PlayerActionResponseS2CPacket packet) { logUnhandled("onPlayerActionResponse"); }
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
