package br.climate.mod;

import br.climate.core.BiomeWetness;
import br.climate.core.ClimateConfig;
import br.climate.core.TerrainSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.ChunkGenerator;

/**
 * Lê o relevo do gerador de mundo SEM carregar chunks (usa o ruído de base).
 * ATENÇÃO: nomes e assinaturas das classes do Minecraft mudam entre versões;
 * se algum método não compilar, é aqui que o ajuste costuma ser necessário.
 */
public final class MinecraftTerrain implements TerrainSource {
    private final ServerLevel level;
    private final ChunkGenerator gen;
    private final ClimateConfig cfg;
    private final int sea;

    public MinecraftTerrain(ServerLevel level, ClimateConfig cfg) {
        this.level = level;
        this.gen = level.getChunkSource().getGenerator();
        this.cfg = cfg;
        this.sea = gen.getSeaLevel();
    }

    private int floorY(int cx, int cz) {
        int x = cx * cfg.cellBlocks + cfg.cellBlocks / 2, z = cz * cfg.cellBlocks + cfg.cellBlocks / 2;
        return gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level,
                level.getChunkSource().randomState());
    }

    @Override public boolean isWater(int cx, int cz) { return floorY(cx, cz) < sea - 1; }

    @Override public double elevationMeters(int cx, int cz) {
        return (floorY(cx, cz) - sea) * cfg.metersPerBlockV;
    }

    /** Umidade do solo a partir do bioma gerado (sem carregar chunks). */
    @Override public double wetness(int cx, int cz) {
        int x = cx * cfg.cellBlocks + cfg.cellBlocks / 2, z = cz * cfg.cellBlocks + cfg.cellBlocks / 2;
        var holder = gen.getBiomeSource().getNoiseBiome(x >> 2, sea >> 2, z >> 2,
                level.getChunkSource().randomState().sampler());
        return BiomeWetness.of(holder.getRegisteredName());
    }
}
