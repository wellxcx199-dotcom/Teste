package br.climate.client;

import br.climate.core.CloudType;
import br.climate.mod.ClimatePayload;
import com.mojang.brigadier.Command;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * Comando /climavitrine (só no cliente): mostra lado a lado, ao norte do jogador, um
 * exemplar de cada tipo de nuvem, para ver os assets sem esperar o tempo certo.
 * Da esquerda (oeste) para a direita: cúmulos, estratos, nimbostratos, cumulonimbo e cirros.
 * Rodar de novo desliga a vitrine e volta às nuvens simuladas.
 */
final class ClimateShowcase {
    private ClimateShowcase() {}

    static final CloudType[] ORDER = {CloudType.CUMULUS, CloudType.STRATUS, CloudType.NIMBOSTRATUS,
            CloudType.CUMULONIMBUS, CloudType.CIRRUS};
    /** Espaçamento entre os grupos e distância ao norte, em células de 16 blocos. */
    static final int SPACING = 5, NORTH = 4, RADIUS = 24;

    static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(ClientCommandManager.literal("climavitrine").executes(ctx -> {
                    if (ClientClimate.showcase() != null) {
                        ClientClimate.setShowcase(null);
                        ctx.getSource().sendFeedback(Component.literal("Vitrine de nuvens desligada."));
                    } else {
                        ClientClimate.setShowcase(build(ctx.getSource().getPlayer()));
                        ctx.getSource().sendFeedback(Component.literal(
                                "Vitrine ao norte: cúmulos, estratos, nimbostratos, cumulonimbo e cirros (oeste → leste)."));
                    }
                    return Command.SINGLE_SUCCESS;
                })));
    }

    static ClimatePayload build(LocalPlayer player) {
        int cb = 16, size = 2 * RADIUS + 1, n = size * size;
        int pcx = Math.floorDiv(player.getBlockX(), cb), pcz = Math.floorDiv(player.getBlockZ(), cb);
        int ocx = pcx - RADIUS, ocz = pcz - RADIUS;
        byte[] types = new byte[n], cover = new byte[n];
        short[] base = new short[n], top = new short[n];
        int y = player.getBlockY();
        for (int g = 0; g < ORDER.length; g++) {
            CloudType t = ORDER[g];
            int ci = RADIUS + (g - ORDER.length / 2) * SPACING, cj = RADIUS - NORTH;
            // Camadas (estratos, nimbostratos) ocupam 3x3 células; as outras, uma só.
            int spread = t == CloudType.STRATUS || t == CloudType.NIMBOSTRATUS ? 1 : 0;
            int b, h;
            switch (t) {
                case CUMULUS -> { b = 48; h = 18; }
                case STRATUS -> { b = 42; h = 4; }
                case NIMBOSTRATUS -> { b = 38; h = 26; }
                case CUMULONIMBUS -> { b = 40; h = 96; }
                default -> { b = 95; h = 1; }
            }
            for (int dj = -spread; dj <= spread; dj++)
                for (int di = -spread; di <= spread; di++) {
                    int idx = (cj + dj) * size + ci + di;
                    types[idx] = (byte) t.ordinal();
                    cover[idx] = (byte) 220;
                    base[idx] = (short) (y + b);
                    top[idx] = (short) (y + b + h);
                }
        }
        ClimatePayload real = ClientClimate.latest();
        return new ClimatePayload(real != null ? real.tempC() : 20, y, 0.0975f, real != null ? real.humidity() : 0.6f,
                real != null ? real.pressureHPa() : 1013, 0, 0, 0, (byte) 0, false, 0, 0, 0,
                ocx * cb, ocz * cb, cb, size, types, cover, base, top, java.util.List.of());
    }
}
