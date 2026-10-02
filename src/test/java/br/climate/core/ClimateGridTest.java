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
}
