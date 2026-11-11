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
 * Mais ao norte fica uma linha de instabilidade: uma fileira de células de cumulonimbo que
 * nascem uma a partir da outra. Rodar de novo desliga a vitrine e volta às nuvens simuladas.
 */
final class ClimateShowcase {
    private ClimateShowcase() {}

    static final CloudType[] ORDER = {CloudType.CUMULUS, CloudType.STRATUS, CloudType.NIMBOSTRATUS,
            CloudType.CUMULONIMBUS, CloudType.CIRRUS};
    /** Espaçamento entre os grupos e distância ao norte, em células de 16 blocos. */
    static final int SPACING = 5, NORTH = 4, RADIUS = 24;
    /** Linha de instabilidade: distância ao norte e meio comprimento, em células. */
    static final int LINE_NORTH = 15, LINE_HALF = 7;
    /** Ticks de céu limpando antes de a vitrine começar. */
    static final int CLEAR_TICKS = 240;

    static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(ClientCommandManager.literal("climavitrine").executes(ctx -> {
                    ctx.getSource().sendFeedback(Component.literal(toggle(ctx.getSource().getPlayer())));
                    return Command.SINGLE_SUCCESS;
                })));
    }

    /** Liga ou desliga a vitrine; retorna a mensagem para o jogador. */
    static String toggle(LocalPlayer player) {
        if (ClientClimate.showcase() != null) {
            ClientClimate.setShowcase(null, null);
            return "Vitrine de nuvens desligada.";
        }
        ClientClimate.setShowcase(build(player, true), build(player, false));
        return "Vitrine ao norte: cúmulos, estratos, nimbostratos, cumulonimbo e cirros (oeste → leste); "
                + "mais longe, uma linha de instabilidade se formando.";
    }

    /** Recorte da vitrine centrado no jogador; com clouds = false, o mesmo recorte de céu limpo. */
    static ClimatePayload build(LocalPlayer player, boolean clouds) {
        // Mesma célula das nuvens simuladas, para a vitrine ter a escala do mundo.
        ClimatePayload live = ClientClimate.latest();
        int cb = live != null ? live.cellBlocks() : 48, size = 2 * RADIUS + 1, n = size * size;
        float sc = cb / 16f;
        int pcx = Math.floorDiv(player.getBlockX(), cb), pcz = Math.floorDiv(player.getBlockZ(), cb);
        int ocx = pcx - RADIUS, ocz = pcz - RADIUS;
        byte[] types = new byte[n], cover = new byte[n];
        short[] base = new short[n], top = new short[n];
        int y = player.getBlockY();
        for (int g = 0; g < (clouds ? ORDER.length : 0); g++) {
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
                    base[idx] = (short) (y + Math.round(b * sc));
                    top[idx] = (short) (y + Math.round((b + h) * sc));
                }
        }
        // Linha de instabilidade levemente inclinada (sudoeste → nordeste).
        for (int d = -LINE_HALF; d <= (clouds ? LINE_HALF : -LINE_HALF - 1); d++) {
            int idx = (RADIUS - LINE_NORTH - Math.floorDiv(d, 3)) * size + RADIUS + d;
            types[idx] = (byte) CloudType.CUMULONIMBUS.ordinal();
            cover[idx] = (byte) 230;
            base[idx] = (short) (y + Math.round(42 * sc));
            top[idx] = (short) (y + Math.round((42 + 92) * sc));
        }
        ClimatePayload real = ClientClimate.latest();
        return new ClimatePayload(real != null ? real.tempC() : 20, y, 0.0975f, real != null ? real.humidity() : 0.6f,
                real != null ? real.pressureHPa() : 1013, 0, 0, 0, (byte) 0, false, 0, 0, 0,
                ocx * cb, ocz * cb, cb, size, types, cover, base, top, java.util.List.of());
    }
}
