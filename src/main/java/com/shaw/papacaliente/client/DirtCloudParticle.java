package com.shaw.papacaliente.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;

/**
 * Nube de polvo de tierra: un "copo" grande, suave y translúcido que aparece rápido,
 * se mantiene un momento a la deriva y se desvanece lentamente.
 */
public class DirtCloudParticle extends TextureSheetParticle {
    private final float maxAlpha;
    private final float rotSpeed;

    protected DirtCloudParticle(ClientLevel level, double x, double y, double z,
                                double vx, double vy, double vz, SpriteSet sprites) {
        super(level, x, y, z);
        this.xd = vx;
        this.yd = vy - 0.01;
        this.zd = vz;
        this.lifetime = 45 + this.random.nextInt(30);
        this.quadSize = 1.1f + this.random.nextFloat() * 1.0f;
        this.hasPhysics = false;
        this.gravity = 0.0f;
        this.friction = 0.92f;
        float shade = 0.80f + this.random.nextFloat() * 0.35f;
        this.rCol = 0.40f * shade;
        this.gCol = 0.27f * shade;
        this.bCol = 0.17f * shade;
        this.maxAlpha = 0.75f + this.random.nextFloat() * 0.2f;
        this.alpha = 0.0f;
        this.roll = this.random.nextFloat() * (float) (Math.PI * 2);
        this.oRoll = this.roll;
        this.rotSpeed = (this.random.nextFloat() - 0.5f) * 0.03f;
        this.pickSprite(sprites);
    }

    @Override
    public void tick() {
        super.tick();
        this.oRoll = this.roll;
        this.roll += this.rotSpeed;
        float t = this.age / (float) this.lifetime;
        if (t < 0.12f) {
            this.alpha = this.maxAlpha * (t / 0.12f);
        } else if (t > 0.55f) {
            this.alpha = this.maxAlpha * Math.max(0f, (1f - t) / 0.45f);
        } else {
            this.alpha = this.maxAlpha;
        }
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    public static class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
                                       double vx, double vy, double vz) {
            return new DirtCloudParticle(level, x, y, z, vx, vy, vz, sprites);
        }
    }
}
