package br.climate.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Testes do motor climático com um terreno sintético (oceano a oeste, serra no meio). */
class ClimateGridTest {
    /** Oceano em x < 20, planície a 150 m e uma serra de 3000 m centrada em x = 48. */
    static final TerrainSource RIDGE = new TerrainSource() {
        public boolean isWater(int x, int z) { return x < 20; }
        public double elevationMeters(int x, int z) {
            double d = Math.abs(x - 48);
            return x < 20 ? 0 : (d < 8 ? 3000 * (1 - d / 8) : 150);
        }
        public double wetness(int x, int z) { return x > 20 && x < 44 ? 0.9 : 0.4; }
    };

    private static ClimateGrid midLatitude() {
        // Origem em Z = -10000 blocos => latitude ~45°N, faixa dos ventos de oeste.
        return new ClimateGrid(110, 40, 0, -625 - 20, new ClimateConfig(), RIDGE);
    }

    @Test
    void windwardSlopeIsWetterThanRainShadow() {
        ClimateGrid g = midLatitude();
        double h = 0;
        g.initialize(h);
        for (; h < 24 * 24; h++) g.step(1, h);
        java.util.Arrays.fill(g.precipTotal, 0);
        for (int n = 0; n < 24 * 12; n++, h++) g.step(1, h);
        int row = 20 * 110;
        double windward = g.precipTotal[row + 44], lee = g.precipTotal[row + 56];
        assertTrue(windward > 20 * Math.max(lee, 1), "barlavento " + windward + " mm vs sotavento " + lee + " mm");
    }

    @Test
    void continentalInteriorHasLargerDailyRange() {
        ClimateGrid g = midLatitude();
        double h = 0;
        g.initialize(h);
        for (; h < 24 * 24; h++) g.step(1, h);
        int row = 20 * 110, coast = row + 10, inland = row + 100;
        double cMin = 99, cMax = -99, iMin = 99, iMax = -99;
        for (int n = 0; n < 24; n++, h++) {
            g.step(1, h);
            cMin = Math.min(cMin, g.temp[coast]); cMax = Math.max(cMax, g.temp[coast]);
            iMin = Math.min(iMin, g.temp[inland]); iMax = Math.max(iMax, g.temp[inland]);
        }
        assertTrue(iMax - iMin > 3 * (cMax - cMin), "interior " + (iMax - iMin) + " vs costa " + (cMax - cMin));
    }

    @Test
    void relativeHumidityNeverExceedsSaturation() {
        ClimateGrid g = midLatitude();
        double h = 0;
        g.initialize(h);
        for (; h < 24 * 10; h++) {
            g.step(1, h);
            for (int k = 0; k < g.nx * g.nz; k++)
                assertTrue(g.e[k] <= Physics.satPressure(g.temp[k]) * 1.0001, "UR > 100% na célula " + k);
        }
    }

    @Test
    void thunderIsOnlyPartOfCumulonimbus() {
        ClimateGrid g = new ClimateGrid(110, 40, 0, -298, new ClimateConfig(), RIDGE); // ~20°N
        double h = 6 * 24;                                                              // verão boreal
        g.initialize(h);
        int cb = 0, thunder = 0;
        for (int n = 0; n < 24 * 9; n++, h++) {
            g.step(1, h);
            for (int k = 0; k < g.nx * g.nz; k++) {
                if (g.type[k] == CloudType.CUMULONIMBUS) cb++;
                if (g.isThunder(k)) { thunder++; assertEquals(CloudType.CUMULONIMBUS, g.type[k]); }
            }
        }
        assertTrue(cb > 0, "o verão subtropical deveria gerar cumulonimbos");
        assertTrue(thunder > 0 && thunder < cb, "trovoadas " + thunder + " de " + cb + " cumulonimbos");
    }

    @Test
    void savedStateRestoresTheSameWeather() {
        ClimateGrid a = midLatitude();
        double h = 0;
        a.initialize(h);
        for (; h < 24 * 5; h++) a.step(1, h);

        // Simula salvar em float (como ClimateSavedData) e carregar numa grade nova.
        ClimateGrid b = new ClimateGrid(a.nx, a.nz, a.originCellX, a.originCellZ, a.cfg, RIDGE);
        double[][] src = a.persistentFields(), dst = b.persistentFields();
        assertEquals(ClimateGrid.PERSISTENT_NAMES.length, src.length);
        for (int f = 0; f < src.length; f++)
            for (int k = 0; k < src[f].length; k++) dst[f][k] = (float) src[f][k];
        b.refreshDiagnostics(h);

        for (int n = 0; n < 24; n++, h++) { a.step(1, h); b.step(1, h); }
        for (int k = 0; k < a.nx * a.nz; k++) {
            assertEquals(a.temp[k], b.temp[k], 0.05, "temperatura na célula " + k);
            assertEquals(a.pSea[k], b.pSea[k], 0.05, "pressão na célula " + k);
            assertEquals(a.cloud[k], b.cloud[k], 0.02, "nuvem na célula " + k);
        }
    }

    @Test
    void recenterKeepsOverlappingCellsAndStaysFinite() {
        ClimateGrid g = new ClimateGrid(110, 40, 0, -298, new ClimateConfig(), RIDGE);
        double h = 6 * 24;
        g.initialize(h);
        for (int n = 0; n < 24 * 3; n++, h++) g.step(1, h);
        double x = 30 * 16 + 8, z = (-298 + 25) * 16 + 8;
        double before = g.sample(x, z, 0).temperatureC();
        g.recenter(10, -298 + 5, h);
        assertEquals(before, g.sample(x, z, 0).temperatureC(), 1e-9);
        for (int n = 0; n < 48; n++, h++) g.step(1, h);
        for (double t : g.temp) assertTrue(Double.isFinite(t));
    }

    @Test
    void cloudGeometryIsConsistent() {
        ClimateGrid g = new ClimateGrid(110, 40, 0, -298, new ClimateConfig(), RIDGE);
        double h = 6 * 24;
        g.initialize(h);
        for (int n = 0; n < 24 * 3; n++, h++) g.step(1, h);
        for (int k = 0; k < g.nx * g.nz; k++) {
            CloudType t = g.type[k];
            if (t == CloudType.CLEAR) { assertEquals(0, g.coverage(k)); continue; }
            assertTrue(g.cloudTopMeters(k) > g.cloudBaseMeters(k), t + " com topo abaixo da base");
            assertTrue(g.coverage(k) > 0 && g.coverage(k) <= 1);
            if (t == CloudType.CUMULONIMBUS) assertTrue(g.cloudTopMeters(k) >= 11000);
        }
    }

    @Test
    void forecastLeavesRealStateUntouchedAndIsReproducible() {
        ClimateGrid g = midLatitude();
        double h = 0;
        g.initialize(h);
        for (; h < 24 * 3; h++) g.step(1, h);
        double[] tempBefore = g.temp.clone(), cloudBefore = g.cloud.clone();
        double x = 60 * 16 + 8, z = (-645 + 20) * 16 + 8;

        long t0 = System.nanoTime();
        var a = g.forecast(x, z, 150, h, 24, 3, 42);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        var b = g.forecast(x, z, 150, h, 24, 3, 42);

        assertArrayEquals(tempBefore, g.temp, "a previsão não pode alterar a grade real");
        assertArrayEquals(cloudBefore, g.cloud);
        assertEquals(8, a.size());
        for (int i = 0; i < a.size(); i++) assertEquals(a.get(i).temperatureC(), b.get(i).temperatureC(), 1e-12);
        assertTrue(ms < 2000, "previsão levou " + ms + " ms (roda na thread do servidor)");
    }

    @Test
    void forecastStaysCloseToRealityAtShortRange() {
        ClimateGrid g = midLatitude();
        double h = 0;
        g.initialize(h);
        for (; h < 24 * 3; h++) g.step(1, h);
        double x = 60 * 16 + 8, z = (-645 + 20) * 16 + 8;
        var f = g.forecast(x, z, 150, h, 6, 6, 1);
        for (int n = 0; n < 6; n++, h++) g.step(1, h + 1);
        // Com perturbação pequena, 6 h à frente a previsão ainda deve estar perto da realidade.
        assertEquals(g.sample(x, z, 150).temperatureC(), f.get(0).temperatureC(), 2.0);
    }

    /** Oceano sem fim: só a circulação geral atua. */
    static final TerrainSource OCEAN = new TerrainSource() {
        public boolean isWater(int x, int z) { return true; }
        public double elevationMeters(int x, int z) { return 0; }
        public double wetness(int x, int z) { return 1; }
    };

    /** Vento médio (u leste, v norte) numa grade de oceano centrada na latitude dada. */
    private static double[] meanOceanWind(double latDeg) {
        ClimateConfig cfg = new ClimateConfig();
        int oz = (int) Math.round(-latDeg / 90 * cfg.halfRangeBlocks / cfg.cellBlocks) - 5;
        ClimateGrid g = new ClimateGrid(10, 10, 0, oz, cfg, OCEAN);
        double h = 0;
        g.initialize(h);
        for (; h < 48; h++) g.step(1, h);
        double u = 0, v = 0;
        for (int k = 0; k < 100; k++) { u += g.u[k]; v += g.v[k]; }
        return new double[] {u / 100, v / 100};
    }

    @Test
    void generalCirculationHasTradesWesterliesAndDoldrums() {
        double[] trades = meanOceanWind(15), westerlies = meanOceanWind(45), doldrums = meanOceanWind(1);
        assertTrue(trades[0] < -1 && trades[1] < 0, "alísios devem vir de nordeste: " + java.util.Arrays.toString(trades));
        assertTrue(westerlies[0] > 1, "ventos de oeste em 45°: " + java.util.Arrays.toString(westerlies));
        assertTrue(Math.hypot(doldrums[0], doldrums[1]) < 0.5 * Math.hypot(trades[0], trades[1]),
                "calmaria equatorial deve ser mais fraca que os alísios");
    }

    @Test
    void smallLakesDoNotMakeTheInteriorMaritime() {
        TerrainSource lakes = new TerrainSource() {
            public boolean isWater(int x, int z) { return x < 10 || (Math.floorMod(x, 7) == 0 && Math.floorMod(z, 7) == 0); }
            public double elevationMeters(int x, int z) { return isWater(x, z) ? 0 : 100; }
            public double wetness(int x, int z) { return 0.5; }
        };
        ClimateGrid g = new ClimateGrid(80, 20, 0, -100, new ClimateConfig(), lakes);
        int far = 10 * 80 + 75;                       // 65 células do mar, cercada de lagoas
        assertTrue(g.cont[far] > 0.9, "continentalidade longe do mar: " + g.cont[far]);
    }

    @Test
    void seaBreezeByDayLandBreezeByNight() {
        // Mar a oeste (x < 40), terra plana a leste, no equador (sem vento geral para mascarar).
        TerrainSource coast = new TerrainSource() {
            public boolean isWater(int x, int z) { return x < 40; }
            public double elevationMeters(int x, int z) { return x < 40 ? 0 : 50; }
            public double wetness(int x, int z) { return 0.3; }
        };
        ClimateGrid g = new ClimateGrid(80, 10, 0, -5, new ClimateConfig(), coast);
        double h = 0;
        g.initialize(h);
        int shore = 5 * 80 + 42;
        for (; h < 24 * 3 + 6; h += 0.5) g.step(0.5, h);       // 6h: terra mais fria que o mar
        assertTrue(g.u[shore] < -0.5, "de madrugada o terral sopra da terra para o mar: u = " + g.u[shore]);
        for (; h < 24 * 3 + 15; h += 0.5) g.step(0.5, h);      // 15h: terra mais quente
        assertTrue(g.u[shore] > 1, "à tarde a brisa deve soprar do mar (oeste) para a terra: u = " + g.u[shore]);
    }
}
