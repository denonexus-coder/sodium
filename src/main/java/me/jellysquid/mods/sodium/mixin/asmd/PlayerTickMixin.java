package me.jellysquid.mods.sodium.mixin.asmd;

import me.jellysquid.mods.sodium.client.render.asmd.ASMDJavaBridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ASMD — cinemática 6D do jogador, câmera e distância de visão.
 *
 * <p>Alvo: {@code MinecraftClient.tick()V} (method_1574) no RETURN. É o ponto
 * em que {@code world.tick()} já rodou — ou seja, {@code player.tick()} já
 * aplicou o movimento — então a posição lida aqui é a resolvida do tick. O
 * menu não tem jogador, e aí simplesmente não há nada a ler.
 *
 * <p>Escreve: posição (16..24), velocidade (28..36), speed (40), elytra (44),
 * yaw/pitch da câmera (48/52), velocidade angular (56/60), aceleração angular
 * (64), distância de visão (68), standstill (92) e sprinting (96).
 *
 * <p>Por que o yaw/pitch do jogador é o da câmera: {@code Camera.update()}
 * copia {@code entity.getYaw()}/{@code getPitch()} direto da entidade focada.
 * Em primeira, terceira e spectator pessoa o valor é o mesmo — a câmera só
 * desloca a posição, nunca a rotação.
 */
@Mixin(MinecraftClient.class)
public abstract class PlayerTickMixin {
    @Unique
    private float asmd$prevX;
    @Unique
    private float asmd$prevY;
    @Unique
    private float asmd$prevZ;
    @Unique
    private float asmd$prevYaw;
    @Unique
    private float asmd$prevPitch;
    @Unique
    private float asmd$prevYawVel;
    @Unique
    private long asmd$prevTimeNs;
    @Unique
    private boolean asmd$primed;

    @Inject(method = "tick()V", at = @At("RETURN"))
    private void asmd$onTickReturn(CallbackInfo ci) {
        MinecraftClient self = (MinecraftClient) (Object) this;
        ClientPlayerEntity player = self.player;

        if (player == null) {
            return;
        }

        long now = System.nanoTime();

        float x = (float) player.getX();
        float y = (float) player.getY();
        float z = (float) player.getZ();
        float yaw = player.getYaw();
        float pitch = player.getPitch();

        // No primeiro tick não há delta: só semeamos as referências, senão a
        // diferença contra o zero inicial viraria uma velocidade absurda.
        float vx = 0.0f, vy = 0.0f, vz = 0.0f, speed = 0.0f;
        float yawVel = 0.0f, pitchVel = 0.0f, angAccel = 0.0f;

        if (asmd$primed) {
            float dt = (now - asmd$prevTimeNs) / 1_000_000_000.0f;
            if (dt <= 0.0f) {
                dt = 0.05f; // fallback 20 TPS
            }

            vx = (x - asmd$prevX) / dt;
            vy = (y - asmd$prevY) / dt;
            vz = (z - asmd$prevZ) / dt;
            speed = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);

            yawVel = (yaw - asmd$prevYaw) / dt;
            pitchVel = (pitch - asmd$prevPitch) / dt;
            angAccel = (yawVel - asmd$prevYawVel) / dt;
        }

        ASMDJavaBridge.setPlayerPos(x, y, z);
        ASMDJavaBridge.setPlayerVel(vx, vy, vz);
        ASMDJavaBridge.setPlayerSpeed(speed);
        ASMDJavaBridge.setElytraFlying(player.isFallFlying() ? 1 : 0);
        ASMDJavaBridge.setSprinting(player.isSprinting() ? 1 : 0);

        ASMDJavaBridge.setCameraYawPitch(yaw, pitch);
        ASMDJavaBridge.setYawVelocity(yawVel);
        ASMDJavaBridge.setPitchVelocity(pitchVel);
        ASMDJavaBridge.setAngularAccel(angAccel);

        // Standstill: parado E sem girar a câmera.
        ASMDJavaBridge.setStandstill(
                (speed < 0.05f && Math.abs(yawVel) < 0.01f && Math.abs(pitchVel) < 0.01f) ? 1 : 0);

        // getViewDistance() devolve SimpleOption<Integer>; o valor só muda quando
        // o jogador mexe no slider, mas ler custa um unbox e mantém o offset vivo.
        Object viewDistance = self.options.getViewDistance().getValue();
        if (viewDistance instanceof Integer chunks) {
            ASMDJavaBridge.setViewDistanceChunks(chunks);
        }

        asmd$prevX = x;
        asmd$prevY = y;
        asmd$prevZ = z;
        asmd$prevYaw = yaw;
        asmd$prevPitch = pitch;
        asmd$prevYawVel = yawVel;
        asmd$prevTimeNs = now;
        asmd$primed = true;
    }
}