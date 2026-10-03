package br.climate.client;

import br.climate.core.CloudType;
import br.climate.core.Storm;
import br.climate.mod.StormInfo;

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
    /** Opacidade de cada trecho (corpo e bigorna): nuvens surgindo ou se desfazendo. */
    final float[] opA, opB;
    final List<Float> rainOp = new ArrayList<>();
    /** Estado da célula sendo desenhada (vem do CloudField). */
    private float curOp = 1, curAnvilOp = 1, curAnvil = 1, curBoil;
    private int curAnvilTop;
    private final double time;
    /** Colunas com cortina de chuva: índice da coluna e altura de onde a chuva sai. */
    final List<int[]> rain = new ArrayList<>();
    final List<Float> rainTop = new ArrayList<>();

    CloudShapes(CloudInput p) {
        cols = p.size() * COLS_PER_CELL;
        colW = p.cellBlocks() / (double) COLS_PER_CELL;
        originX = p.originX();
        originZ = p.originZ();
        int n = cols * cols;
        y0a = new float[n]; y1a = new float[n]; y0b = new float[n]; y1b = new float[n];
        matA = new byte[n]; matB = new byte[n];
        towerBase = new float[n]; towerTop = new float[n];
        opA = new float[n]; opB = new float[n];
        time = p.time();
        java.util.Arrays.fill(matA, EMPTY);
        java.util.Arrays.fill(matB, EMPTY);
        CloudType[] types = CloudType.values();
        int cellX0 = Math.floorDiv(p.originX(), p.cellBlocks()), cellZ0 = Math.floorDiv(p.originZ(), p.cellBlocks());
        double dl = Math.hypot(p.driftX(), p.driftZ());
        windX = dl > 1e-6 ? p.driftX() / dl : 1;          // direção para onde o vento leva a bigorna
        windZ = dl > 1e-6 ? p.driftZ() / dl : 0;
        for (StormInfo st : p.storms())
            if (st.stormKind() == Storm.Kind.HURRICANE) eyes.add(new double[] {st.x(), st.z(), 0.55 * st.radius()});
        float[] cbScale = clusterLeaders(p, types);
        try { buildCells(p, types, cbScale, cellX0, cellZ0); } finally { wallClouds = List.copyOf(walls); }
    }

    private void buildCells(CloudInput p, CloudType[] types, float[] cbScale, int cellX0, int cellZ0) {
        for (int j = 0; j < p.size(); j++)
            for (int i = 0; i < p.size(); i++) {
                int idx = j * p.size() + i;
                CloudType t = types[p.types()[idx]];
                if (cbScale[idx] != 0) t = CloudType.CUMULONIMBUS;  // torre principal (ou supercélula) é sempre cumulonimbo
                if (t == CloudType.CLEAR || t == CloudType.FOG) continue;
                float cover = (p.cover()[idx] & 0xFF) / 255f;
                int base = p.baseY()[idx], top = p.topY()[idx];
                curOp = cbScale[idx] < 0 ? Math.max(0.6f, p.opacity()[idx]) : p.opacity()[idx];
                curAnvil = cbScale[idx] < 0 ? Math.max(0.6f, p.anvil()[idx]) : p.anvil()[idx];
                curAnvilOp = curOp * Math.min(1, curAnvil * 1.5f);
                curAnvilTop = p.anvilTop()[idx];
                curBoil = p.boil()[idx];
                if (t == CloudType.CUMULONIMBUS && base <= 0) {
                    // Célula sem nuvem ainda (supercélula recém-criada): base pelas vizinhas ou acima do jogador.
                    if (types[p.types()[idx]] == CloudType.CLEAR || base <= 0) base = neighbourBase(p, types, i, j);
                    top = base + 90;
                }
                if (cbScale[idx] < 0) {                       // supercélula: base alta o bastante para o funil aparecer
                    int minBase = Math.round(p.refY()) + 40;
                    if (base < minBase) { top += minBase - base; base = minBase; }
                }
                if (t == CloudType.CUMULONIMBUS && cbScale[idx] == 0) congestus(i, j, cellX0 + i, cellZ0 + j, base, top);
                else if (t == CloudType.CUMULONIMBUS)
                    giantCumulonimbus(i, j, cellX0 + i, cellZ0 + j, base, top, Math.abs(cbScale[idx]), cbScale[idx] < 0);
                else shape(t, i, j, cellX0 + i, cellZ0 + j, base, top, cover);
            }
    }

    private final double windX, windZ;
    /** Nuvens-parede das supercélulas (x, z, Y da base), para o funil do tornado terminar nelas. */
    static volatile List<double[]> wallClouds = List.of();
    private final List<double[]> walls = new ArrayList<>();
    /** Olhos de furacão (x, z, raio em blocos): o ar desce ali e nenhuma bigorna os cobre. */
    private final List<double[]> eyes = new ArrayList<>();

    private boolean inEye(int gx, int gz) {
        double x = originX + (gx + 0.5) * colW, z = originZ + (gz + 0.5) * colW;
        for (double[] e : eyes) if (Math.hypot(x - e[0], z - e[1]) < e[2]) return true;
        return false;
    }

    /**
     * Num aglomerado de células de cumulonimbo, só a mais forte (topo mais alto, depois mais
     * cobertura) vira a torre principal; as outras viram cúmulos congestos ao redor.
     * Retorna, por célula: 0 = não é torre principal; >0 = escala da torre; <0 = supercélula
     * (o valor absoluto é a escala).
     */
    private static float[] clusterLeaders(CloudInput p, CloudType[] types) {
        int size = p.size(), n = size * size, R = 3;
        float[] scale = new float[n];
        for (int j = 0; j < size; j++)
            for (int i = 0; i < size; i++) {
                int idx = j * size + i;
                if (types[p.types()[idx]] != CloudType.CUMULONIMBUS) continue;
                long mine = strength(p, idx);
                boolean leader = true;
                int count = 0;
                for (int b = Math.max(0, j - R); b <= Math.min(size - 1, j + R) && leader; b++)
                    for (int a = Math.max(0, i - R); a <= Math.min(size - 1, i + R); a++) {
                        int o = b * size + a;
                        if (types[p.types()[o]] != CloudType.CUMULONIMBUS) continue;
                        count++;
                        long other = strength(p, o);
                        if (other > mine || (other == mine && o < idx)) { leader = false; break; }
                    }
                if (leader) scale[idx] = Math.min(1.8f, 1 + 0.12f * (count - 1));
            }
        // Supercélulas: a célula onde estão vira a maior torre, com nuvem-parede e topo saliente.
        int cellX0 = Math.floorDiv(p.originX(), p.cellBlocks()), cellZ0 = Math.floorDiv(p.originZ(), p.cellBlocks());
        for (StormInfo s : p.storms()) {
            if (s.stormKind() != Storm.Kind.SUPERCELL) continue;
            int i = Math.floorDiv((int) Math.floor(s.x()), p.cellBlocks()) - cellX0;
            int j = Math.floorDiv((int) Math.floor(s.z()), p.cellBlocks()) - cellZ0;
            if (i < 0 || j < 0 || i >= size || j >= size) continue;
            for (int b = Math.max(0, j - R); b <= Math.min(size - 1, j + R); b++)    // é a única torre principal ali
                for (int a = Math.max(0, i - R); a <= Math.min(size - 1, i + R); a++) scale[b * size + a] = 0;
            scale[j * size + i] = -Math.max(1.7f, 1.3f + 0.5f * s.intensity());
        }
        return scale;
    }

    private static int neighbourBase(CloudInput p, CloudType[] types, int i, int j) {
        int size = p.size(), sum = 0, n = 0;
        for (int b = Math.max(0, j - 2); b <= Math.min(size - 1, j + 2); b++)
            for (int a = Math.max(0, i - 2); a <= Math.min(size - 1, i + 2); a++) {
                int o = b * size + a;
                CloudType t = types[p.types()[o]];
                if (t != CloudType.CLEAR && t != CloudType.FOG && t != CloudType.CIRRUS && p.baseY()[o] > 0) { sum += p.baseY()[o]; n++; }
            }
        return n > 0 ? sum / n : Math.round(p.refY()) + 45;
    }

    private static long strength(CloudInput p, int idx) {
        return (long) p.topY()[idx] * 256 + (p.cover()[idx] & 0xFF);
    }

    /** Cúmulo congesto: torre de tempestade menor, sem bigorna, ao lado da torre principal. */
    private void congestus(int i, int j, int cellX, int cellZ, int base, int top) {
        int gx0 = i * COLS_PER_CELL, gz0 = j * COLS_PER_CELL;
        float h = (top - base) * 0.45f;
        double ccx = 1.5 + (rnd(cellX, cellZ, 70) - 0.5), ccz = 1.5 + (rnd(cellX, cellZ, 71) - 0.5);
        for (int b = 0; b < COLS_PER_CELL; b++)
            for (int a = 0; a < COLS_PER_CELL; a++) {
                double d = Math.hypot(a + 0.5 - ccx, b + 0.5 - ccz) / (1.9 * (0.8 + 0.4 * rnd(cellX * 4 + a, cellZ * 4 + b, 72)));
                if (d >= 1) continue;
                setA(gx0 + a, gz0 + b, base, base + Math.round(h * (1 - 0.6f * (float) (d * d))), CloudType.CUMULUS);
            }
    }

    /**
     * Cumulonimbo principal. Proporções (com a espessura das nuvens comprimida a 100 m por
     * bloco): torre de ~35 a 60 blocos de largura e bigorna de ~60 a 115, espalhada na direção
     * do vento. Na supercélula, a base da torre desce numa "nuvem-parede" e um domo ("topo
     * saliente") fura a bigorna, sinal de corrente ascendente muito forte.
     */
    private void giantCumulonimbus(int i, int j, int cellX, int cellZ, int base, int top, float scale, boolean supercell) {
        CloudType t = CloudType.CUMULONIMBUS;
        int gx0 = i * COLS_PER_CELL, gz0 = j * COLS_PER_CELL;
        float h = Math.max(20, top - base);
        // A bigorna só existe depois que a torre amadurece (curAnvil > 0) e fica no topo que a
        // torre alcançou, mesmo que ela depois desmorone (bigorna "órfã").
        boolean hasAnvil = curAnvil > 0.02f;
        float anvilTopY = hasAnvil ? Math.max(curAnvilTop, base + h) : base + h;
        float anvil = Math.max(4, Math.round((anvilTopY - base) / 7f));
        float towerH = hasAnvil ? Math.max(4, Math.min(h, anvilTopY - anvil - base)) : h;
        double ccx = 1.5 + (rnd(cellX, cellZ, 50) - 0.5), ccz = 1.5 + (rnd(cellX, cellZ, 51) - 0.5);
        double rt = 2.7 * 1.6 * scale, ra = 3.6 * 2.2 * scale;
        int reachT = (int) Math.ceil(rt) + 1;
        for (int b = -reachT; b < COLS_PER_CELL + reachT; b++)
            for (int a = -reachT; a < COLS_PER_CELL + reachT; a++) {
                int wx = cellX * COLS_PER_CELL + a, wz = cellZ * COLS_PER_CELL + b;
                double d = Math.hypot(a + 0.5 - ccx, b + 0.5 - ccz) / (rt * (0.85 + 0.3 * vnoise(wx / 1.5, wz / 1.5, 52)));
                int gx = gx0 + a, gz = gz0 + b;
                if (d >= 1 || gx < 0 || gz < 0 || gx >= cols || gz >= cols) continue;
                // "Ombros" mais baixos nas bordas e bolhas no meio da torre.
                double amp = 4 + 6 * curBoil;                       // bolhas maiores enquanto a torre cresce
                float y1 = base + Math.round(towerH * (1 - 0.5f * (float) (d * d))
                        + (float) (vnoise(wx / 1.6 + time * 0.006, wz / 1.6, 54) * 2 * amp - amp));
                float y0 = base + (d > 0.8 ? 2 : 0);
                if (supercell && d < 0.45) y0 = base - 7 + Math.round((float) (d / 0.45) * 3);   // nuvem-parede
                setA(gx, gz, y0, y1, t);
                int k = index(gx, gz);
                towerBase[k] = base; towerTop[k] = base + towerH;
                if (d < (supercell ? 0.75 : 0.6) && (rnd(wx, wz, 55) < 0.6)) addRain(gx, gz, y0);
            }
        if (supercell) walls.add(new double[] {originX + (gx0 + ccx) * colW, originZ + (gz0 + ccz) * colW, base - 7});
        if (!hasAnvil) return;
        ra *= 0.2 + 0.8 * Math.sqrt(curAnvil);                      // a bigorna se espalha aos poucos
        // Bigorna: larga, com o centro deslocado na direção do vento em altitude.
        double acx = ccx + windX * 0.35 * ra, acz = ccz + windZ * 0.35 * ra;
        int reachA = (int) Math.ceil(ra + 0.35 * ra) + 1;
        for (int b = -reachA; b < COLS_PER_CELL + reachA; b++)
            for (int a = -reachA; a < COLS_PER_CELL + reachA; a++) {
                int wx = cellX * COLS_PER_CELL + a, wz = cellZ * COLS_PER_CELL + b;
                double d = Math.hypot(a + 0.5 - acx, b + 0.5 - acz) / (ra * (0.8 + 0.35 * vnoise(wx / 2.0, wz / 2.0, 53)));
                if (d >= 1) continue;
                float y0 = anvilTopY - anvil + (d > 0.7 ? 1 : 0) + (d > 0.9 ? 1 : 0);
                float y1 = anvilTopY - (d > 0.85 ? 1 : 0);
                double dt = Math.hypot(a + 0.5 - ccx, b + 0.5 - ccz);
                if (supercell && dt < rt * 0.3) y1 += Math.round(6 * (1 - dt / (rt * 0.3)));   // topo saliente
                setB(gx0 + a, gz0 + b, y0, y1);
            }
    }

    int index(int gx, int gz) { return gz * cols + gx; }

    private void setA(int gx, int gz, float y0, float y1, CloudType t) {
        if (gx < 0 || gz < 0 || gx >= cols || gz >= cols || y1 - y0 < 1) return;
        int k = index(gx, gz);
        if (matA[k] != EMPTY && y1a[k] - y0a[k] >= y1 - y0) return;   // fica o trecho maior
        y0a[k] = y0; y1a[k] = y1; matA[k] = (byte) t.ordinal(); opA[k] = curOp;
    }

    private void setB(int gx, int gz, float y0, float y1) {
        if (gx < 0 || gz < 0 || gx >= cols || gz >= cols || y1 - y0 < 1 || inEye(gx, gz)) return;
        int k = index(gx, gz);
        if (matB[k] != EMPTY) return;
        if (matA[k] != EMPTY && y1a[k] > y0) return;                   // não atravessa outra nuvem
        y0b[k] = y0; y1b[k] = y1; matB[k] = (byte) CloudType.CUMULONIMBUS.ordinal(); opB[k] = curAnvilOp;
    }

    private void addRain(int gx, int gz, float top) {
        if (gx < 0 || gz < 0 || gx >= cols || gz >= cols) return;
        rain.add(new int[] {gx, gz});
        rainTop.add(top);
        rainOp.add(curOp);
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
                            // Topo "fervendo" enquanto a nuvem cresce: bolhas que sobem e descem devagar.
                            double bubble = curBoil * (vnoise((cellX * COLS_PER_CELL + a) / 1.3 + time * 0.008,
                                    (cellZ * COLS_PER_CELL + b) / 1.3, 41) * 4 - 2);
                            float hh = Math.round(hq * (1 - d * d) + bubble);
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
            case CUMULONIMBUS -> giantCumulonimbus(i, j, cellX, cellZ, base, top, 1f, false);
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
