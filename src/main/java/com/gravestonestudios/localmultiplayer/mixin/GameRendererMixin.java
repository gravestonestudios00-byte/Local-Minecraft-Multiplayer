package com.gravestonestudios.localmultiplayer.mixin;

import com.gravestonestudios.localmultiplayer.LocalMultiplayerClient;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.Window;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow
    @Final
    private MinecraftClient client;

    @Shadow
    private boolean renderHand;

    @Shadow
    private boolean blockOutlineEnabled;

    private boolean localmultiplayer$renderingSecondView;
    private boolean localmultiplayer$secondViewDisabled;
    private int localmultiplayer$frameCounter;

    @Shadow
    public abstract void renderWorld(float tickDelta, long limitTime, MatrixStack matrices);

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/GameRenderer;renderWorld(FJLnet/minecraft/client/util/math/MatrixStack;)V",
                    ordinal = 0,
                    shift = At.Shift.AFTER
            )
    )
    private void localmultiplayer$renderSecondPlayerView(float tickDelta, long startTime, boolean tick, CallbackInfo ci) {
        if (localmultiplayer$secondViewDisabled || localmultiplayer$renderingSecondView || client.world == null || client.player == null) {
            return;
        }

        Entity playerTwo = localmultiplayer$getPlayerTwoEntity();
        if (playerTwo == null) {
            return;
        }

        localmultiplayer$frameCounter++;
        if ((localmultiplayer$frameCounter & 1) == 1) {
            return;
        }

        Window window = client.getWindow();
        int width = window.getFramebufferWidth();
        int height = window.getFramebufferHeight();
        int secondHeight = Math.max(1, height / 2);
        Entity originalCamera = client.getCameraEntity();
        boolean originalRenderHand = renderHand;
        boolean originalBlockOutline = blockOutlineEnabled;

        localmultiplayer$renderingSecondView = true;
        try {
            client.setCameraEntity(playerTwo);
            renderHand = false;
            blockOutlineEnabled = false;

            RenderSystem.viewport(0, 0, width, secondHeight);
            RenderSystem.enableScissor(0, 0, width, secondHeight);
            renderWorld(tickDelta, startTime, new MatrixStack());
            RenderSystem.disableScissor();
            RenderSystem.viewport(0, 0, width, height);
        } catch (RuntimeException | LinkageError exception) {
            localmultiplayer$secondViewDisabled = true;
        } finally {
            client.setCameraEntity(originalCamera == null ? client.player : originalCamera);
            renderHand = originalRenderHand;
            blockOutlineEnabled = originalBlockOutline;
            RenderSystem.disableScissor();
            RenderSystem.viewport(0, 0, width, height);
            localmultiplayer$renderingSecondView = false;
        }
    }

    private Entity localmultiplayer$getPlayerTwoEntity() {
        UUID playerTwoUuid = LocalMultiplayerClient.getSecondPlayerUuid();
        for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
            if (playerTwoUuid.equals(player.getUuid())) {
                return player;
            }
        }

        return null;
    }
}
