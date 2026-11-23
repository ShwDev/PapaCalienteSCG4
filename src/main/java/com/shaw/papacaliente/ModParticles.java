package com.shaw.papacaliente;

import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/** Partículas propias del mod. */
public final class ModParticles {
    /** Nube grande y suave de polvo de tierra (transición entre rondas). */
    public static final SimpleParticleType DIRT_CLOUD = Registry.register(BuiltInRegistries.PARTICLE_TYPE,
            ResourceLocation.fromNamespaceAndPath(PapaCaliente.MOD_ID, "dirt_cloud"), FabricParticleTypes.simple(true));

    private ModParticles() {
    }

    public static void init() {
    }
}
