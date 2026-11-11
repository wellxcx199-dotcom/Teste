package br.climate.core;

/** Parâmetros ajustáveis. Todos têm valores padrão calibrados para um planeta "reduzido". */
public final class ClimateConfig {
    /**
     * Lado de uma célula em blocos. Define também a escala das nuvens: cada célula abriga uma
     * nuvem, então células maiores dão nuvens maiores e mais espaçadas (com 48, as colunas das
     * nuvens têm 12 blocos, como os blocos das nuvens do próprio Minecraft). Com 16, as nuvens
     * ficam com um terço do tamanho.
     */
    public int cellBlocks = 48;
    /** Distância (em blocos de Z) do equador ao polo. Define a escala horizontal. */
    public double halfRangeBlocks = 20000;
    /** Metros reais representados por um bloco na horizontal (derivado: 10.000 km / halfRange). */
    public double metersPerBlockH() { return 10_000_000.0 / halfRangeBlocks; }
    /** Metros reais por bloco na vertical (exagero vertical). */
    public double metersPerBlockV = 15;
    /** Dias de jogo por ano climático. */
    public double daysPerYear = 24;
    /** Distância em blocos até o oceano em que a continentalidade satura em 1. */
    public double continentalityBlocks = 640;
    /** Água conta como oceano (para a continentalidade) se esta fração do entorno for água... */
    public double oceanMinFraction = 0.6;
    /** ...num quadrado de raio (em blocos) igual a este. */
    public int oceanRadiusBlocks = 48;
    /** Atrito com a superfície (1/s) sobre o mar e sobre a terra: desacelera e desvia o vento. */
    public double frictionSea = 4e-5;
    public double frictionLand = 8e-5;
    /** Queda de pressão (hPa) por °C de ar mais quente que o normal (baixa térmica). */
    public double thermalLowHPaPerC = 0.8;
    /** Escala (blocos) em que a anomalia de temperatura é suavizada antes de gerar as brisas. */
    public int breezeSmoothBlocks = 48;
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
    /** "Tampa" de ar estável: a superfície precisa passar a média do dia por tantos °C para haver convecção. */
    public double convectiveCapC = 1.5;
    /** Quanto o céu encoberto reduz o ciclo diário de temperatura (0 = nada, 1 = anula). */
    public double cloudShading = 0.55;
    /** Resfriamento do chão pela chuva (°C por mm). */
    public double rainCoolingCPerMm = 1.2;
    /** Vida da água de nuvem antes de evaporar por mistura (h de jogo): em ar seco e em ar saturado. */
    public double cloudLifeDryH = 0.4;
    public double cloudLifeMoistH = 4.0;
    /**
     * Sistemas de tempo (cristas e cavados que passam): intensidade (0 desliga), tamanho (m),
     * duração (h de jogo), velocidade para leste (m/s), subida/descida do ar (m/s) e sinal na
     * pressão (hPa).
     */
    public double systemStrength = 1.0;
    public double systemSizeM = 300_000;
    public double systemLifeH = 40;
    public double systemSpeed = 12;
    public double systemLift = 0.10;
    public double systemHPa = 5;
    /** Secagem sob as cristas: umidade relativa para a qual o ar tende e tempo característico (h). */
    public double ridgeDryRh = 0.45;
    public double ridgeDryTauH = 8;
    /** Manchas de convecção: tamanho (m) e duração (h de jogo). */
    public double patchSizeM = 50_000;
    public double patchLifeH = 3;
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
