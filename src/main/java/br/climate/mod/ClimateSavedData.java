package br.climate.mod;

import br.climate.core.ClimateConfig;
import br.climate.core.ClimateGrid;
import br.climate.core.TerrainSource;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Guarda o estado da grade climática no mundo (arquivo data/climamod_climate.dat), para que
 * o clima continue de onde parou quando o servidor reinicia.
 *
 * Os campos são gravados como float (int[] com os bits), o que reduz o arquivo à metade
 * sem perda perceptível: a simulação não precisa de mais que 7 algarismos significativos.
 */
public final class ClimateSavedData extends SavedData {
    public static final String NAME = "climamod_climate";
    private static final int FORMAT = 1;

    /** Estado lido do disco, à espera de ser aplicado a uma grade. */
    private CompoundTag pending;
    /** Grade ativa; quando presente, é ela que é gravada. */
    private ClimateGrid grid;

    public static SavedData.Factory<ClimateSavedData> factory() {
        // O terceiro argumento (DataFixTypes) só é usado pelo jogo vanilla; mods passam null.
        return new SavedData.Factory<>(ClimateSavedData::new, ClimateSavedData::load, null);
    }

    private static ClimateSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        ClimateSavedData d = new ClimateSavedData();
        d.pending = tag;
        return d;
    }

    /**
     * Reconstrói a grade a partir do que foi salvo. Retorna null se não há nada salvo ou se
     * o formato/tamanho mudou (por exemplo, depois de alterar a configuração do mod).
     */
    public ClimateGrid restore(int nx, int nz, ClimateConfig cfg, TerrainSource terrain, double gameHours) {
        CompoundTag t = pending;
        pending = null;
        if (!compatible(t, nx, nz, cfg)) return null;
        ClimateGrid g = new ClimateGrid(nx, nz, t.getInt("originX"), t.getInt("originZ"), cfg, terrain);
        double[][] fields = g.persistentFields();
        for (int f = 0; f < fields.length; f++) {
            int[] bits = t.getIntArray(ClimateGrid.PERSISTENT_NAMES[f]);
            if (bits.length != nx * nz) return null;
            for (int k = 0; k < bits.length; k++) fields[f][k] = Float.intBitsToFloat(bits[k]);
        }
        // Fenômenos (furacões, supercélulas, tornados): uma lista de números por fenômeno.
        java.util.List<double[]> storms = new java.util.ArrayList<>();
        net.minecraft.nbt.ListTag list = t.getList("storms", net.minecraft.nbt.Tag.TAG_LIST);
        for (int i = 0; i < list.size(); i++) {
            net.minecraft.nbt.ListTag one = (net.minecraft.nbt.ListTag) list.get(i);
            double[] d = new double[one.size()];
            for (int q = 0; q < d.length; q++) d[q] = one.getDouble(q);
            if (d.length >= 13) storms.add(d);
        }
        g.storms.unpack(storms);
        g.refreshDiagnostics(gameHours);
        return g;
    }

    /** Origem (em células) da janela salva, para ler o terreno antes de restaurar; null se não há. */
    public int[] savedOrigin(int nx, int nz, ClimateConfig cfg) {
        return compatible(pending, nx, nz, cfg) ? new int[] {pending.getInt("originX"), pending.getInt("originZ")} : null;
    }

    private static boolean compatible(CompoundTag t, int nx, int nz, ClimateConfig cfg) {
        return t != null && t.getInt("format") == FORMAT && t.getInt("nx") == nx && t.getInt("nz") == nz
                && t.getInt("cellBlocks") == cfg.cellBlocks;
    }

    public void attach(ClimateGrid grid) {
        this.grid = grid;
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (grid == null) return pending != null ? pending.copy() : tag;
        tag.putInt("format", FORMAT);
        tag.putInt("nx", grid.nx);
        tag.putInt("nz", grid.nz);
        tag.putInt("cellBlocks", grid.cfg.cellBlocks);
        tag.putInt("originX", grid.originCellX);
        tag.putInt("originZ", grid.originCellZ);
        net.minecraft.nbt.ListTag storms = new net.minecraft.nbt.ListTag();
        for (double[] d : grid.storms.pack()) {
            net.minecraft.nbt.ListTag one = new net.minecraft.nbt.ListTag();
            for (double v : d) one.add(net.minecraft.nbt.DoubleTag.valueOf(v));
            storms.add(one);
        }
        tag.put("storms", storms);
        double[][] fields = grid.persistentFields();
        for (int f = 0; f < fields.length; f++) {
            int[] bits = new int[fields[f].length];
            for (int k = 0; k < bits.length; k++) bits[k] = Float.floatToIntBits((float) fields[f][k]);
            tag.putIntArray(ClimateGrid.PERSISTENT_NAMES[f], bits);
        }
        return tag;
    }
}
