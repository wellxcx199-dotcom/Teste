package br.climate.core;

public final class Demo {
    public static void main(String[] a) {
        ClimateConfig cfg = new ClimateConfig();
        TerrainSource t = new TerrainSource() {
            public boolean isWater(int x, int z) { return x < 20; }
            public double elevationMeters(int x, int z) {
                if (x < 20) return 0;
                double d = Math.abs(x - 48);
                return d < 8 ? 3000 * (1 - d / 8) : 150;
            }
            public double wetness(int x, int z) { return 0.5; }
        };
        // origem em Z = -10000 blocos => latitude ~45°N
        ClimateGrid g = new ClimateGrid(110, 40, 0, -625 - 20, cfg, t);
        double h = 0;
        g.initialize(h);
        int row = 20;
        for (; h < 24 * 24; h += 1) g.step(1, h);            // aquecimento (1 ano)
        java.util.Arrays.fill(g.precipTotal, 0);
        double[][] tmin = new double[110][1], tmax = new double[110][1];
        for (int i = 0; i < 110; i++) { tmin[i][0] = 99; tmax[i][0] = -99; }
        for (int n = 0; n < 24 * 12; n++, h += 1) {           // 12 dias medidos
            g.step(1, h);
            for (int i = 0; i < 110; i++) {
                double T = g.temp[row * 110 + i];
                tmin[i][0] = Math.min(tmin[i][0], T); tmax[i][0] = Math.max(tmax[i][0], T);
            }
        }
        System.out.println("  x  elev(m) cont  Tmin  Tmax  amplit  P(hPa)  vento(m/s)  UR   chuva(mm/12d)");
        for (int i = 5; i < 110; i += 6) {
            int k = row * 110 + i;
            System.out.printf("%3d %7.0f %5.2f %5.1f %5.1f %6.1f %8.1f %8.1f   %4.2f %7.0f%n",
                    i, g.elev[k], g.cont[k], tmin[i][0], tmax[i][0], tmax[i][0] - tmin[i][0],
                    g.pSea[k], Math.hypot(g.u[k], g.v[k]), g.e[k] / Physics.satPressure(g.temp[k]), g.precipTotal[k]);
        }
        System.out.println("Vento u(leste) em x=30: " + g.u[row*110+30] + "  v(norte): " + g.v[row*110+30]);
        java.util.Map<CloudType,Integer> cnt = new java.util.EnumMap<>(CloudType.class);
        for (CloudType c : g.type) if (c != null) cnt.merge(c, 1, Integer::sum);
        System.out.println("Tipos de nuvem na grade: " + cnt);
    }
}
