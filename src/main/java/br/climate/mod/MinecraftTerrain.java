package br.climate.mod;

import br.climate.core.BiomeWetness;
import br.climate.core.ClimateConfig;
import br.climate.core.TerrainSource;
import net.minecraft.Util;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

/**
 * Lê o relevo e o bioma do gerador de mundo SEM carregar chunks (usa o ruído de base).
 *
 * Consultar o gerador é caro (cerca de 2 ms por célula), e a grade tem milhares de células.
 * Feito na thread do servidor, isso congelava o jogo por dezenas de segundos ao ligar.
 * Por isso as células são lidas antes, em segundo plano e em paralelo ({@link #prefetch}),
 * e guardadas num cache; a grade só é criada ou deslocada quando os dados já estão prontos.
 * O gerador de terreno do Minecraft já é usado por várias threads na geração de chunks,
 * então consultá-lo fora da thread principal é seguro.
 *
 * ATENÇÃO: nomes e assinaturas das classes do Minecraft mudam entre versões;
 * se algum método não compilar, é aqui que o ajuste costuma ser necessário.
 */
public final class MinecraftTerrain implements TerrainSource {
    private record Cell(boolean water, double elevationM, double wetness) {}

    private final ServerLevel level;
    private final ChunkGenerator gen;
    private final ClimateConfig cfg;
    private final int sea;
    private final Map<Long, Cell> cache = new ConcurrentHashMap<>();

    public MinecraftTerrain(ServerLevel level, ClimateConfig cfg) {
        this.level = level;
        this.gen = level.getChunkSource().getGenerator();
        this.cfg = cfg;
        this.sea = gen.getSeaLevel();
    }

    /** Lê em segundo plano todas as células do retângulo que ainda não estão no cache. */
    public CompletableFuture<Void> prefetch(int ox, int oz, int nx, int nz) {
        return CompletableFuture.runAsync(() ->
                IntStream.range(0, nx * nz).parallel().forEach(k -> cell(ox + k % nx, oz + k / nx)),
                Util.backgroundExecutor());
    }

    /** Descarta células longe da janela atual, para o cache não crescer sem limite. */
    public void retain(int ox, int oz, int nx, int nz, int margin) {
        cache.keySet().removeIf(key -> {
            int cx = (int) (key >> 32), cz = (int) (long) key;
            return cx < ox - margin || cz < oz - margin || cx >= ox + nx + margin || cz >= oz + nz + margin;
        });
    }

    private Cell cell(int cx, int cz) {
        long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        Cell c = cache.get(key);
        if (c == null) {
            c = compute(cx, cz);
            cache.put(key, c);
        }
        return c;
    }

    private Cell compute(int cx, int cz) {
        int x = cx * cfg.cellBlocks + cfg.cellBlocks / 2, z = cz * cfg.cellBlocks + cfg.cellBlocks / 2;
        var random = level.getChunkSource().randomState();
        int floorY = gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random);
        var biome = gen.getBiomeSource().getNoiseBiome(x >> 2, sea >> 2, z >> 2, random.sampler());
        return new Cell(floorY < sea - 1, (floorY - sea) * cfg.metersPerBlockV,
                BiomeWetness.of(biome.getRegisteredName()));
    }

    @Override public boolean isWater(int cx, int cz) { return cell(cx, cz).water(); }

    @Override public double elevationMeters(int cx, int cz) { return cell(cx, cz).elevationM(); }

    /** Umidade do solo a partir do bioma gerado (sem carregar chunks). */
    @Override public double wetness(int cx, int cz) { return cell(cx, cz).wetness(); }
}
