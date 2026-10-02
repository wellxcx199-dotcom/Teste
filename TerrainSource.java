package br.climate.core;

/**
 * Interface entre o motor climático e o mundo. No mod, é implementada consultando o
 * gerador de terreno; nos testes, é uma função matemática.
 * Índices (cx, cz) são coordenadas de célula da grade climática.
 */
public interface TerrainSource {
    /** Altitude em metros acima do nível do mar (já com exagero vertical aplicado). */
    double elevationMeters(int cx, int cz);

    /** Verdadeiro se a célula é predominantemente oceano/lago grande. */
    boolean isWater(int cx, int cz);

    /** Umidade do solo/vegetação de 0 (deserto) a 1 (floresta tropical). */
    double wetness(int cx, int cz);
}
