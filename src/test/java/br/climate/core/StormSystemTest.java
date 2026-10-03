package br.climate.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Testes dos fenômenos: furacões, supercélulas e tornados. */
class StormSystemTest {
    static final TerrainSource OCEAN = new TerrainSource() {
        public boolean isWater(int x, int z) { return true; }
        public double elevationMeters(int x, int z) { return 0; }
        public double wetness(int x, int z) { return 1; }
    };
    static final TerrainSource LAND = new TerrainSource() {
        public boolean isWater(int x, int z) { return false; }
        public double elevationMeters(int x, int z) { return 100; }
        public double wetness(int x, int z) { return 0.8; }
    };

    /** Grade de 128x64 células em ~15°N (o leste da grade em x = 0), no auge do verão boreal. */
    private static ClimateGrid tropical(TerrainSource t, ClimateConfig cfg) {
        int cz = (int) Math.round(-15.0 / 90 * cfg.halfRangeBlocks / cfg.cellBlocks);
        return new ClimateGrid(128, 64, -112, cz - 32, cfg, t);
    }

    private static double summer() { return 6 * 24; }

    private static int centerCell(ClimateGrid g, Storm s) { return g.cellIndex(s.x, s.z); }

    @Test
    void hurricaneHasEyeEyewallLowPressureAndCyclonicWinds() {
        ClimateConfig cfg = new ClimateConfig();
        cfg.hurricanesPerDay = 0;
        ClimateGrid g = tropical(OCEAN, cfg);
        double h = summer();
        g.initialize(h);
        for (int n = 0; n < 24; n++, h++) g.step(1, h);
        double x = 0, z = (g.originCellZ + 32) * cfg.cellBlocks;
        Storm s = g.storms.spawnHurricane(x, z, 45);
        double p0 = g.pSea[g.cellIndex(x - 400, z)];
        for (int n = 0; n < 6; n++, h++) g.step(1, h);

        int c = centerCell(g, s);
        double rBlocks = s.radiusM / cfg.metersPerBlockH();
        int east = g.cellIndex(s.x + rBlocks, s.z);
        assertTrue(g.pSea[c] < p0 - 20, "pressão central " + g.pSea[c] + " vs ambiente " + p0);
        assertEquals(0, g.cloud[c], 1e-9, "o olho deve estar sem nuvens");
        assertTrue(g.precipRate[east] > 5, "chuva forte na parede do olho: " + g.precipRate[east]);
        assertTrue(g.v[east] > 10, "a leste do centro o vento sopra para o norte (anti-horário): v = " + g.v[east]);
        assertEquals(CloudType.CUMULONIMBUS, g.type[east]);
    }

    @Test
    void hurricaneIntensifiesOverWarmSeaAndMovesWest() {
        ClimateConfig cfg = new ClimateConfig();
        cfg.hurricanesPerDay = 0;
        ClimateGrid g = tropical(OCEAN, cfg);
        double h = summer();
        g.initialize(h);
        for (int n = 0; n < 24; n++, h++) g.step(1, h);
        double z = (g.originCellZ + 32) * cfg.cellBlocks;
        Storm s = g.storms.spawnHurricane(0, z, 20);
        double sst = g.temp[g.cellIndex(0, z)];
        assertTrue(sst > 26.5, "mar tropical no verão deveria passar de 26,5 °C: " + sst);
        for (int n = 0; n < 12; n++, h++) g.step(1, h);
        assertTrue(s.vmax > 25, "deveria intensificar sobre o mar quente (" + sst + " °C): vmax = " + s.vmax);
        double[] st = g.steeringWind(s.z);
        assertTrue(st[0] < -4 && st[0] > -12, "alísios em altitude de 4 a 12 m/s para oeste: " + st[0]);
        assertTrue(s.x < -20, "os alísios levam o furacão para oeste: x = " + s.x);
        assertTrue(s.z < z, "a deriva beta o leva para o norte (z menor): z = " + s.z + " vs " + z);
    }

    @Test
    void hurricaneWeakensOverLand() {
        ClimateConfig cfg = new ClimateConfig();
        cfg.hurricanesPerDay = 0;
        ClimateGrid g = tropical(LAND, cfg);
        double h = summer();
        g.initialize(h);
        Storm s = g.storms.spawnHurricane(0, (g.originCellZ + 32) * cfg.cellBlocks, 60);
        for (int n = 0; n < 12; n++, h++) g.step(1, h);
        assertTrue(s.vmax < 60 * 0.6, "sobre terra deve perder força: vmax = " + s.vmax);
    }

    @Test
    void hurricanesFormNaturallyOverWarmOcean() {
        ClimateConfig cfg = new ClimateConfig();
        cfg.hurricanesPerDay = 6;                 // frequência alta só para o teste ser rápido
        ClimateGrid g = tropical(OCEAN, cfg);
        double h = summer();
        g.initialize(h);
        boolean formed = false;
        for (int n = 0; n < 24 * 4 && !formed; n++, h++) {
            g.step(1, h);
            for (Storm s : g.storms.storms) formed |= s.kind == Storm.Kind.HURRICANE;
        }
        assertTrue(formed, "com o mar a ~28 °C, um furacão deveria se formar");
    }

    @Test
    void supercellSpawnsTornadoThatFollowsItAndDies() {
        ClimateConfig cfg = new ClimateConfig();
        cfg.tornadoPerHour = 50;                  // garante o tornado no teste
        cfg.supercellsPerHour = 0;
        ClimateGrid g = tropical(LAND, cfg);
        double h = summer() + 12;
        g.initialize(h);
        for (int n = 0; n < 12; n++, h++) g.step(1, h);
        Storm sc = g.storms.spawnSupercell(0, (g.originCellZ + 32) * cfg.cellBlocks, 4);
        Storm tornado = null;
        for (int n = 0; n < 16 && tornado == null; n++, h += 0.25) {
            g.step(0.25, h);
            for (Storm s : g.storms.storms) if (s.kind == Storm.Kind.TORNADO) tornado = s;
        }
        assertNotNull(tornado, "supercélula madura com base baixa deveria gerar tornado");
        assertEquals(sc.id, tornado.parent);
        assertTrue(Math.hypot(tornado.x - sc.x, tornado.z - sc.z) < 2 * cfg.cellBlocks, "tornado acompanha a supercélula");
        for (int n = 0; n < 40; n++, h += 0.25) g.step(0.25, h);
        assertNull(g.storms.find(tornado.id), "tornados duram menos de uma hora e meia");
    }

    @Test
    void stormsSurviveSaveAndForecastDoesNotTouchThem() {
        ClimateConfig cfg = new ClimateConfig();
        cfg.hurricanesPerDay = 0;
        ClimateGrid g = tropical(OCEAN, cfg);
        g.initialize(summer());
        Storm s = g.storms.spawnHurricane(10, (g.originCellZ + 32) * cfg.cellBlocks, 40);
        double x = s.x, v = s.vmax;
        g.forecast(0, s.z, 0, summer(), 12, 3, 1);
        assertEquals(x, s.x, 1e-12, "a previsão roda numa cópia");
        assertEquals(v, s.vmax, 1e-12);

        StormSystem other = new StormSystem(1);
        other.unpack(g.storms.pack());
        Storm r = other.find(s.id);
        assertNotNull(r);
        assertEquals(s.vmax, r.vmax, 1e-12);
        assertEquals(Storm.Kind.HURRICANE, r.kind);
        assertEquals(s.describe(), r.describe());
    }
}
