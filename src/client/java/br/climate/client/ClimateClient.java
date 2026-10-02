package br.climate.client;

import br.climate.mod.ClimatePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

/** Parte visual do mod: clima local, nuvens 3D, neblina e painel na tela. Só roda no cliente. */
public final class ClimateClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(ClimatePayload.TYPE, (payload, ctx) -> ClientClimate.accept(payload));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientClimate.reset());
        ClimateHud.register();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClimateHud.tick();
            if (client.level != null && !client.isPaused()) ClientClimate.tick();
        });
        WorldRenderEvents.AFTER_TRANSLUCENT.register(CloudRenderer::render);
        HudRenderCallback.EVENT.register(ClimateHud::render);
    }
}
