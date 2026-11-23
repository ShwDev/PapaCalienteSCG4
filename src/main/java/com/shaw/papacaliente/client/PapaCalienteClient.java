package com.shaw.papacaliente.client;

import com.shaw.papacaliente.ModParticles;
import com.shaw.papacaliente.SelectionPayload;
import net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

public class PapaCalienteClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(SelectionPayload.TYPE,
                (payload, context) -> SelectionRenderer.setActive(payload.active()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> SelectionRenderer.setActive(false));
        WorldRenderEvents.AFTER_TRANSLUCENT.register(SelectionRenderer::render);
        ParticleFactoryRegistry.getInstance().register(ModParticles.DIRT_CLOUD, DirtCloudParticle.Provider::new);
    }
}
