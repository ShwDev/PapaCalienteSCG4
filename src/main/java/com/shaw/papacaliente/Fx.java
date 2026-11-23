package com.shaw.papacaliente;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/** Utilidades de feedback: títulos, action bar, sonidos y partículas. */
public final class Fx {
    private Fx() {
    }

    public static void title(ServerPlayer p, Component title, Component subtitle) {
        title(p, title, subtitle, 5, 40, 10);
    }

    public static void title(ServerPlayer p, Component title, Component subtitle, int fadeIn, int stay, int fadeOut) {
        p.connection.send(new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut));
        p.connection.send(new ClientboundSetSubtitleTextPacket(subtitle == null ? Component.empty() : subtitle));
        p.connection.send(new ClientboundSetTitleTextPacket(title));
    }

    public static void actionBar(ServerPlayer p, Component text) {
        p.displayClientMessage(text, true);
    }

    public static void sound(ServerLevel level, BlockPos pos, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, sound, SoundSource.PLAYERS, volume, pitch);
    }

    /** Sonido que solo oye un jugador (se reproduce en su posición). */
    public static void soundTo(ServerPlayer p, SoundEvent sound, float volume, float pitch) {
        p.serverLevel().playSound(null, p.getX(), p.getY(), p.getZ(), sound, SoundSource.PLAYERS, volume, pitch);
    }

    /** Partículas visibles para todos los jugadores cercanos. */
    public static void particles(ServerLevel level, ParticleOptions type, double x, double y, double z,
                                 int count, double spread, double speed) {
        level.sendParticles(type, x, y, z, count, spread, spread, spread, speed);
    }

    /** Partícula visible solo para un jugador (vista previa de selección). */
    public static void particleTo(ServerPlayer p, ParticleOptions type, double x, double y, double z) {
        p.serverLevel().sendParticles(p, type, true, x, y, z, 1, 0, 0, 0, 0);
    }
}
