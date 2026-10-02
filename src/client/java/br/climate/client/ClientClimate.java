package br.climate.client;

import br.climate.mod.ClimatePayload;
import net.minecraft.util.Mth;

/**
 * Clima local recebido do servidor, do ponto de vista do jogador deste cliente.
 * As intensidades (chuva, trovoada, neblina) mudam aos poucos, como no vanilla,
 * para que o tempo não "vire" de um tick para o outro.
 */
public final class ClientClimate {
    private ClientClimate() {}

    /** Após este tempo sem notícias do servidor, o cliente volta ao clima vanilla. */
    private static final long STALE_TICKS = 20 * 60;
    /** Gradiente de temperatura por bloco de altura: 6,5 °C/km com 15 m por bloco. */
    private static final double LAPSE_PER_BLOCK = 6.5 * 15 / 1000.0;

    private static ClimatePayload latest;
    private static double playerY;
    private static long ticksSince = Long.MAX_VALUE;

    private static float rain, rainO, thunder, thunderO, fog, fogO;

    public static void accept(ClimatePayload p, double y) {
        latest = p;
        playerY = y;
        ticksSince = 0;
    }

    public static void reset() {
        latest = null;
        ticksSince = Long.MAX_VALUE;
        rain = rainO = thunder = thunderO = fog = fogO = 0;
    }

    public static boolean active() {
        return latest != null && ticksSince < STALE_TICKS;
    }

    public static ClimatePayload latest() { return latest; }

    /** Ticks desde o último pacote, para o deslocamento das nuvens com o vento. */
    public static long ticksSince() { return ticksSince; }

    public static void tick() {
        if (latest == null) return;
        if (ticksSince != Long.MAX_VALUE) ticksSince++;
        rainO = rain; thunderO = thunder; fogO = fog;
        // 2 mm/h já é chuva forte; abaixo de 0,1 mm/h não chove.
        float rainTarget = latest.precipMmH() > 0.1f ? Mth.clamp(0.25f + latest.precipMmH() / 2.5f, 0, 1) : 0;
        rain = approach(rain, rainTarget, 0.01f);
        thunder = approach(thunder, latest.thunder() ? 1 : 0, 0.01f);
        fog = approach(fog, latest.fog() ? 1 : 0, 0.005f);
    }

    private static float approach(float v, float target, float step) {
        return v < target ? Math.min(target, v + step) : Math.max(target, v - step);
    }

    public static float rainLevel(float pt) { return Mth.lerp(pt, rainO, rain); }

    public static float thunderLevel(float pt) { return Mth.lerp(pt, thunderO, thunder) * rainLevel(pt); }

    public static float fogLevel(float pt) { return Mth.lerp(pt, fogO, fog); }

    /** Neve ou chuva numa altura Y, a partir da temperatura medida na altura do jogador. */
    public static boolean snowAt(int y) {
        return latest.tempC() - (y - playerY) * LAPSE_PER_BLOCK < 0.5;
    }
}
