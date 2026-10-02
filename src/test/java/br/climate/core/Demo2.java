package br.climate.core;

import java.util.*;

/** Verifica: (1) verão em latitude subtropical gera convecção; (2) recentrar a janela preserva o estado. */
public final class Demo2 {
    static final TerrainSource T = new TerrainSource() {
        public boolean isWater(int x, int z) { return x < 20; }
        public double elevationMeters(int x, int z) { double d = Math.abs(x - 48); return x < 20 ? 0 : (d < 8 ? 3000 * (1 - d / 8) : 150); }
        public double wetness(int x, int z) { return x > 20 && x < 44 ? 0.9 : 0.4; } // faixa úmida (tipo floresta) a barlavento
    };

    public static void main(String[] a) {
        ClimateConfig cfg = new ClimateConfig();
        // 20° N: z = -4444 blocos => célula -278. Janela vai de -278-20 a +20
        ClimateGrid g = new ClimateGrid(110, 40, 0, -298, cfg, T);
        double h = 6 * 24; // início do verão boreal (declinação máxima)
        g.initialize(h);
        for (int n = 0; n < 24 * 6; n++, h++) g.step(1, h);
        Map<CloudType, Integer> cnt = new EnumMap<>(CloudType.class);
        int thunder = 0; double tmax = -99;
        for (int n = 0; n < 24 * 3; n++, h++) {
            g.step(1, h);
            for (int k = 0; k < g.nx * g.nz; k++) tmax = Math.max(tmax, g.temp[k]);
        }
        for (int k = 0; k < g.nx * g.nz; k++) {
            cnt.merge(g.type[k], 1, Integer::sum);
            if (g.isThunder(k)) thunder++;
        }
        System.out.println("VERÃO ~20°N  Tmax=" + String.format("%.1f", tmax) + "  nuvens=" + cnt + "  células com trovoada=" + thunder);

        // Teste da janela móvel
        ClimateSample before = g.sample(30 * 16 + 8, (-298 + 25) * 16 + 8, 0);
        g.recenter(10, -298 + 5, h);
        ClimateSample after = g.sample(30 * 16 + 8, (-298 + 25) * 16 + 8, 0);
        System.out.printf("Recentrar: T antes=%.3f depois=%.3f | P antes=%.3f depois=%.3f | nuvem %s -> %s%n",
                before.temperatureC(), after.temperatureC(), before.pressureHPa(), after.pressureHPa(), before.cloud(), after.cloud());
        for (int n = 0; n < 48; n++, h++) g.step(1, h);
        int nan = 0; for (double x : g.temp) if (Double.isNaN(x)) nan++;
        System.out.println("Após recentrar + 48h: células NaN = " + nan);
    }
}
