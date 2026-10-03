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
    /** Água conta como oceano (para a continentalidade) se esta fração do entorno for água... */
    public double oceanMinFraction = 0.6;
    /** ...num quadrado de raio (em células) igual a este. */
    public int oceanRadiusCells = 3;
    /** Atrito com a superfície (1/s) sobre o mar e sobre a terra: desacelera e desvia o vento. */
    public double frictionSea = 4e-5;
    public double frictionLand = 8e-5;
    /** Queda de pressão (hPa) por °C de ar mais quente que o normal (baixa térmica). */
    public double thermalLowHPaPerC = 0.8;
    /** Passadas de suavização da anomalia de temperatura (cada uma alarga ~1 célula). */
    public int anomalySmoothPasses = 3;
    /** Brisa: espessura da camada de ar frio (m), distância em que o contraste age (m) e teto (m/s). */
    public double breezeDepthM = 500;
    public double breezeLengthM = 20000;
    public double maxBreeze = 10;
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
    /** Fenômenos: fator de velocidade de deslocamento (como windSpeedFactor) e frequências. */
    public double stormSpeedFactor = 0.7;
    public int maxHurricanes = 1;
    /** Chance de nascer um furacão por dia de jogo, quando a região tem bastante oceano quente. */
    public double hurricanesPerDay = 0.5;
    public int maxSupercells = 3;
    /** Chance por hora de jogo de um cumulonimbo forte virar supercélula. */
    public double supercellsPerHour = 0.08;
    /** Chance por hora de jogo de uma supercélula madura (base baixa) gerar um tornado. */
    public double tornadoPerHour = 0.6;
    /** Trovoada: só cumulonimbos com chuva acima deste limiar (mm/h), os mais maduros. */
    public double thunderMinMmH = 1.2;
}
