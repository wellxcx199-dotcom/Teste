package br.climate.core;

/** Parâmetros ajustáveis. Todos têm valores padrão calibrados para um planeta "reduzido". */
public final class ClimateConfig {
    /** Lado de uma célula em blocos. */
    public int cellBlocks = 16;
    /** Distância (em blocos de Z) do equador ao polo. Define a escala horizontal. */
    public double halfRangeBlocks = 20000;
    /** Metros reais representados por um bloco na horizontal (derivado: 10.000 km / halfRange). */
    public double metersPerBlockH() { return 10_000_000.0 / halfRangeBlocks; }
    /** Metros reais por bloco na vertical (exagero vertical). */
    public double metersPerBlockV = 15;
    /** Dias de jogo por ano climático. */
    public double daysPerYear = 24;
    /** Distância em células até o oceano em que a continentalidade satura em 1. */
    public double continentalityCells = 40;
    /** Multiplicador da velocidade de transporte pelo vento (1 = físico). */
    public double windSpeedFactor = 0.35;
    public double maxWind = 25;
    /** Tempo (s) de ascensão usado para estimar resfriamento adiabático. */
    public double liftSeconds = 3600;
    /** Tempo (s) equivalente de ascensão para correntes convectivas (sobem mais que o relevo). */
    public double convLiftSeconds = 7200;
    public double condTauH = 1.0;
    public double precipThreshold = 0.5;
    public double landEvapFraction = 0.65;
    public double mmPerUnit = 1.5;
    /** Trovoada: só cumulonimbos com chuva acima deste limiar (mm/h), os mais maduros. */
    public double thunderMinMmH = 1.2;
}
