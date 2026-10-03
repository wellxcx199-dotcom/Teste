package br.climate.client;

import br.climate.core.CloudType;
import br.climate.mod.ClimatePayload;

import java.util.ArrayList;
import java.util.List;

/**
 * Transforma o recorte de nuvens recebido do servidor em formas "voxel" no estilo do
 * Minecraft. Cada célula da grade climática (16 blocos) vira 4x4 colunas de 4 blocos, e
 * cada coluna guarda até dois trechos verticais ("spans"): o corpo da nuvem e, no caso do
 * cumulonimbo, a bigorna, que se espalha por cima das células vizinhas.
 *
 * As formas dependem só das coordenadas do mundo (ruído determinístico), então a mesma
 * nuvem tem sempre a mesma cara e camadas de células vizinhas se emendam sem costura.
 */
final class CloudShapes {
    static final int COLS_PER_CELL = 4;
    /** Profundidade das cortinas de chuva abaixo da base, em blocos. */
    static final int RAIN_DEPTH = 36;

    /** Material de um trecho: tipo de nuvem, e se é a bigorna do cumulonimbo. */
    static final byte EMPTY = -1;

    final int cols;                  // colunas por lado do recorte
    final double colW;               // largura de uma coluna em blocos
    final int originX, originZ;      // bloco do canto noroeste do recorte
    final float[] y0a, y1a, y0b, y1b;
    final byte[] matA, matB;          // matA: corpo; matB: bigorna (CUMULONIMBUS) ou vazio
    final float[] towerBase, towerTop; // para o degradê escuro das torres
    /** Colunas com cortina de chuva: índice da coluna e altura de onde a chuva sai. */
    final List<int[]> rain = new ArrayList<>();
    final List<Float> rainTop = new ArrayList<>();

    CloudShapes(ClimatePayload p) {
        cols = p.size() * COLS_PER_CELL;
        colW = p.cellBlocks() / (double) COLS_PER_CELL;
        originX = p.originX();
        originZ = p.originZ();
        int n = cols * cols;
        y0a = new float[n]; y1a = new float[n]; y0b = new float[n]; y1b = new float[n];
        matA = new byte[n]; matB = new byte[n];
        towerBase = new float[n]; towerTop = new float[n];
        java.util.Arrays.fill(matA, EMPTY);
        java.util.Arrays.fill(matB, EMPTY);
        CloudType[] types = CloudType.values();
        int cellX0 = Math.floorDiv(p.originX(), p.cellBlocks()), cellZ0 = Math.floorDiv(p.originZ(), p.cellBlocks());
        for (int j = 0; j < p.size(); j++)
            for (int i = 0; i < p.size(); i++) {
                int idx = j * p.size() + i;
                CloudType t = types[p.types()[idx]];
                if (t == CloudType.CLEAR || t == CloudType.FOG) continue;
                float cover = (p.cover()[idx] & 0xFF) / 255f;
                shape(t, i, j, cellX0 + i, cellZ0 + j, p.baseY()[idx], p.topY()[idx], cover);
            }
    }

    int index(int gx, int gz) { return gz * cols + gx; }

    private void setA(int gx, int gz, float y0, float y1, CloudType t) {
        if (gx < 0 || gz < 0 || gx >= cols || gz >= cols || y1 - y0 < 1) return;
        int k = index(gx, gz);
        if (matA[k] != EMPTY && y1a[k] - y0a[k] >= y1 - y0) return;   // fica o trecho maior
        y0a[k] = y0; y1a[k] = y1; matA[k] = (byte) t.ordinal();
    }

    private void setB(int gx, int gz, float y0, float y1) {
        if (gx < 0 || gz < 0 || gx >= cols || gz >= cols || y1 - y0 < 1) return;
        int k = index(gx, gz);
        if (matB[k] != EMPTY) return;
        if (matA[k] != EMPTY && y1a[k] > y0) return;                   // não atravessa outra nuvem
        y0b[k] = y0; y1b[k] = y1; matB[k] = (byte) CloudType.CUMULONIMBUS.ordinal();
    }

    private void addRain(int gx, int gz, float top) {
        if (gx < 0 || gz < 0 || gx >= cols || gz >= cols) return;
        rain.add(new int[] {gx, gz});
        rainTop.add(top);
    }

    private void shape(CloudType t, int i, int j, int cellX, int cellZ, int base, int top, float cover) {
        int gx0 = i * COLS_PER_CELL, gz0 = j * COLS_PER_CELL;
        float h = Math.max(2, top - base);
        switch (t) {
            case CUMULUS -> {
                // 1 a 3 tufos em forma de domo; a base é reta, como nos cúmulos de verdade.
                int puffs = 1 + (cover > 0.5f ? 1 : 0) + (cover > 0.8f ? 1 : 0);
                for (int q = 0; q < puffs; q++) {
                    double pcx = 0.8 + 2.4 * rnd(cellX, cellZ, 10 + q), pcz = 0.8 + 2.4 * rnd(cellX, cellZ, 20 + q);
                    double r = (1.3 + 1.5 * cover) * (0.8 + 0.4 * rnd(cellX, cellZ, 30 + q));
                    double hq = h * (0.6 + 0.4 * rnd(cellX, cellZ, 40 + q));
                    for (int b = 0; b < COLS_PER_CELL; b++)
                        for (int a = 0; a < COLS_PER_CELL; a++) {
                            double d = Math.hypot(a + 0.5 - pcx, b + 0.5 - pcz) / r;
                            if (d >= 1) continue;
                            float hh = Math.round(hq * (1 - d * d));
                            if (hh < 2) continue;
                            float y0 = base + (d > 0.7 ? 1 : 0);
                            setA(gx0 + a, gz0 + b, y0, base + hh, t);
                        }
                }
            }
            case STRATUS -> {
                // Camada fina e contínua; o ruído (em coordenadas do mundo) recorta as bordas.
                for (int b = 0; b < COLS_PER_CELL; b++)
                    for (int a = 0; a < COLS_PER_CELL; a++) {
                        int wx = cellX * COLS_PER_CELL + a, wz = cellZ * COLS_PER_CELL + b;
                        if (vnoise(wx / 2.5, wz / 2.5, 11) > 0.35 + 0.65 * cover) continue;
                        float y0 = base + Math.round(vnoise(wx / 3.0, wz / 3.0, 12) * 2);
                        setA(gx0 + a, gz0 + b, y0, y0 + Math.max(2, h) + Math.round(vnoise(wx / 2.0, wz / 2.0, 13) * 2), t);
                    }
            }
            case NIMBOSTRATUS -> {
                // Camada grossa e escura, com a base "rasgada" e cortinas de chuva.
                for (int b = 0; b < COLS_PER_CELL; b++)
                    for (int a = 0; a < COLS_PER_CELL; a++) {
                        int wx = cellX * COLS_PER_CELL + a, wz = cellZ * COLS_PER_CELL + b;
                        if (vnoise(wx / 2.0, wz / 2.0, 21) > 0.45 + 0.45 * cover) continue;
                        double rag = vnoise(wx / 1.5, wz / 1.5, 22);
                        float y0 = base - (rag > 0.62 ? 3 : rag > 0.45 ? 1 : 0);
                        // Topo encaroçado: o ar ainda sobe em bolhas dentro da camada.
                        float y1 = top - 3 + Math.round(vnoise(wx / 1.6, wz / 1.6, 23) * 8);
                        setA(gx0 + a, gz0 + b, y0, y1, t);
                        if (cover > 0.35f && vnoise(wx / 1.2, wz / 1.2, 24) > 0.58) addRain(gx0 + a, gz0 + b, y0);
                    }
            }
            case CUMULONIMBUS -> {
                // Torre com "ombros" mais baixos e a bigorna larga e clara no topo.
                float anvil = Math.max(3, Math.round(h / 8f));
                float towerH = h - anvil;
                double ccx = 1.5 + (rnd(cellX, cellZ, 50) - 0.5), ccz = 1.5 + (rnd(cellX, cellZ, 51) - 0.5);
                double rt = 2.7;
                for (int b = -2; b < COLS_PER_CELL + 2; b++)
                    for (int a = -2; a < COLS_PER_CELL + 2; a++) {
                        int wx = cellX * COLS_PER_CELL + a, wz = cellZ * COLS_PER_CELL + b;
                        double d = Math.hypot(a + 0.5 - ccx, b + 0.5 - ccz) / (rt * (0.85 + 0.3 * vnoise(wx, wz, 52)));
                        int gx = gx0 + a, gz = gz0 + b;
                        if (d < 1 && gx >= 0 && gz >= 0 && gx < cols && gz < cols) {
                            // "Ombros" mais baixos nas bordas e bolhas no meio da torre.
                            float y1 = base + Math.round(towerH * (1 - 0.5f * (float) (d * d))
                                    + (float) (vnoise(wx / 1.3, wz / 1.3, 54) * 6 - 3));
                            setA(gx, gz, base + (d > 0.8 ? 2 : 0), y1, t);
                            int k = index(gx, gz);
                            towerBase[k] = base; towerTop[k] = base + towerH;
                            if (d < 0.6) addRain(gx, gz, base);
                        }
                    }
                // A bigorna passa da célula: até ~1,8 célula de largura.
                for (int b = -3; b < COLS_PER_CELL + 3; b++)
                    for (int a = -3; a < COLS_PER_CELL + 3; a++) {
                        int wx = cellX * COLS_PER_CELL + a, wz = cellZ * COLS_PER_CELL + b;
                        double d = Math.hypot(a + 0.5 - ccx, b + 0.5 - ccz) / (3.6 * (0.8 + 0.35 * vnoise(wx / 1.5, wz / 1.5, 53)));
                        if (d >= 1) continue;
                        float y0 = base + towerH + (d > 0.7 ? 1 : 0);
                        setB(gx0 + a, gz0 + b, y0, base + h - (d > 0.85 ? 1 : 0));
                    }
            }
            case CIRRUS -> {
                // Duas faixas finas e altas, quase paralelas, cruzando a célula.
                for (int s = 0; s < 2; s++) {
                    double off = 0.6 + 2.6 * rnd(cellX, cellZ, 60 + s), slope = (rnd(cellX, cellZ, 62) - 0.5) * 0.6;
                    float y = base + Math.round(4 * rnd(cellX, cellZ, 64 + s));
                    for (int a = -3; a <= COLS_PER_CELL + 2; a++) {     // faixas longas, além da célula
                        int b = (int) Math.floor(off + slope * a);
                        if (b < 0 || b >= COLS_PER_CELL) continue;
                        setA(gx0 + a, gz0 + b, y, y + 1, t);
                        if (rnd(cellX * 8 + a, cellZ, 66 + s) < 0.6) setA(gx0 + a, gz0 + b + 1, y, y + 1, t);
                    }
                }
            }
            default -> {}
        }
    }

    /** Número pseudoaleatório estável (0 a 1). */
    static double rnd(int x, int z, int salt) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
        h ^= h >>> 33; h *= 0xFF51AFD7ED558CCDL; h ^= h >>> 33; h *= 0xC4CEB9FE1A85EC53L; h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Ruído suave (value noise) de 0 a 1: valores aleatórios numa grade, interpolados. */
    static double vnoise(double x, double z, int salt) {
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        double fx = x - x0, fz = z - z0;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        double a = rnd(x0, z0, salt), b = rnd(x0 + 1, z0, salt), c = rnd(x0, z0 + 1, salt), d = rnd(x0 + 1, z0 + 1, salt);
        return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fz;
    }
}
