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
        ClientPlayNetworking.registerGlobalReceiver(br.climate.mod.MapPayload.TYPE, (payload, ctx) -> ClientClimate.setMap(payload));
        // O DISCONNECT pode vir da thread de rede; o estado das nuvens só é mexido na thread principal.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(ClientClimate::reset));
        ClimateHud.register();
        ClimateShowcase.register();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClimateHud.tick();
            if (client.level != null && !client.isPaused()) { ClientClimate.tick(); TornadoRenderer.tick(client); }
        });
        WorldRenderEvents.AFTER_TRANSLUCENT.register(CloudRenderer::render);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(TornadoRenderer::render);
        HudRenderCallback.EVENT.register(ClimateHud::render);
    }
}
