package br.climate.core;

/** Configuração dos testes: células de 16 blocos (8 km), a geometria para a qual os terrenos de teste foram desenhados. */
final class TestConfig {
    private TestConfig() {}

    static ClimateConfig cells16() {
        ClimateConfig c = new ClimateConfig();
        c.cellBlocks = 16;
        return c;
    }
}
