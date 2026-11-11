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
 *
 * Proporções: a espessura das nuvens chega do servidor comprimida (~33 m por bloco com as
 * células padrão de 48 blocos) e a largura das nuvens convectivas usa a mesma escala, para
 * que a relação largura/altura seja a de verdade. Um cúmulo de bom tempo (~1,3 km de largura,
 * ~0,6 km de altura) fica com uns 40×18 blocos; um congesto (~3,5 × 4,5 km), 105×135; a torre
 * de um cumulonimbo (~6 a 12 km de largura e 10 km de altura), 180 a 360 blocos de largura por
 * 300 de altura; e a bigorna (20 a 40 km), 600 a 1200 blocos, esticada para onde o vento em
 * altitude a leva.
 *
 * Todas as medidas em blocos são multiplicadas por {@link #S}, a razão entre o tamanho da
 * célula e 16 blocos (o tamanho para o qual as formas foram desenhadas). Assim, mudar
 * physics.cellBlocks muda o tamanho das nuvens sem mudar a aparência delas.
 */
final class CloudShapes {
    static final int COLS_PER_CELL = 4;
    /** Profundidade das cortinas de chuva abaixo da base, em blocos (na escala de 16 blocos por célula). */
    static final int RAIN_DEPTH = 36;

    /** Material de um trecho: tipo de nuvem, e se é a bigorna do cumulonimbo. */
    static final byte EMPTY = -1;

    final int cols;                  // colunas por lado do recorte
    final double colW;               // largura de uma coluna em blocos
    /** Escala das medidas em blocos: 1 com células de 16 blocos, 3 com as de 48. */
    final float S;
    final int originX, originZ;      // bloco do canto noroeste do recorte
    final float[] y0a, y1a, y0b, y1b;
    final byte[] matA, matB;          // matA: corpo; matB: bigorna (CUMULONIMBUS) ou vazio
    final float[] towerBase, towerTop; // para o degradê escuro das torres
    /** Detalhe de cada trecho, para o renderizador escolher textura e transparência. */
    static final byte DECO_NONE = 0, DECO_MAMMATUS = 1, DECO_PILEUS = 2, DECO_SHELF = 3;
    final byte[] decoA, decoB;
    /** Primeira coluna (no mundo) do recorte: o ruído dos fiapos usa coordenadas do mundo. */
    private final int colX0, colZ0;
    /** Opacidade de cada trecho (corpo e bigorna): nuvens surgindo ou se desfazendo. */
    final float[] opA, opB;
    final List<Float> rainOp = new ArrayList<>();
    /** Estado da célula sendo desenhada (vem do CloudField). */
    private float curOp = 1, curAnvilOp = 1, curAnvil = 1, curBoil, curGrow = 1;
    /** Torre alta sendo desenhada: surge e some por transparência, sem se rasgar em fiapos. */
    private boolean curSolid;
    /** Células com nuvem em camada (estratos, nimbostratos), para afinar as bordas das camadas. */
    private final boolean[] layer;
    private final int size;
    private final List<StormInfo> stormList;
    private final int cellBlocks;
    private int curAnvilTop, curFromX, curFromZ;
    private final double time;
    /** Colunas com cortina de chuva: índice da coluna e altura de onde a chuva sai. */
    final List<int[]> rain = new ArrayList<>();
    final List<Float> rainTop = new ArrayList<>();

    CloudShapes(CloudInput p) {
        cols = p.size() * COLS_PER_CELL;
        colW = p.cellBlocks() / (double) COLS_PER_CELL;
        S = p.cellBlocks() / 16f;
        originX = p.originX();
        originZ = p.originZ();
        int n = cols * cols;
        y0a = new float[n]; y1a = new float[n]; y0b = new float[n]; y1b = new float[n];
        matA = new byte[n]; matB = new byte[n];
        towerBase = new float[n]; towerTop = new float[n];
        opA = new float[n]; opB = new float[n];
        decoA = new byte[n]; decoB = new byte[n];
        colX0 = (int) Math.floor(p.originX() / colW);
        colZ0 = (int) Math.floor(p.originZ() / colW);
        time = p.time();
        size = p.size();
        stormList = p.storms();
        cellBlocks = p.cellBlocks();
        layer = new boolean[size * size];
        for (int q = 0; q < layer.length; q++) {
            int ty = p.types()[q];
            layer[q] = ty == CloudType.STRATUS.ordinal() || ty == CloudType.NIMBOSTRATUS.ordinal();
        }
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
        try { buildCells(p, types, cbScale, cellX0, cellZ0); }
        finally { wallClouds = List.copyOf(walls); matureStorms = List.copyOf(storms); }
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
                // A supercélula vem da lista de fenômenos e já existe inteira (sem fiapos).
                curOp = cbScale[idx] < 0 ? 1f : p.opacity()[idx];
                curAnvil = cbScale[idx] < 0 ? Math.max(0.6f, p.anvil()[idx]) : p.anvil()[idx];
                curAnvilOp = curOp * Math.min(1, curAnvil * 1.5f);
                curAnvilTop = p.anvilTop()[idx];
                curBoil = p.boil()[idx];
                curGrow = p.grow()[idx];
                curFromX = p.from()[idx] / 3 - 1;
                curFromZ = p.from()[idx] % 3 - 1;
                if (t == CloudType.CUMULONIMBUS && base <= 0) {
                    // Célula sem nuvem ainda (supercélula recém-criada): base pelas vizinhas ou acima do jogador.
                    if (types[p.types()[idx]] == CloudType.CLEAR || base <= 0) base = neighbourBase(p, types, i, j);
                    top = base + Math.round(90 * S);
                }
                if (cbScale[idx] < 0) {
                    // Supercélula: base pela média das vizinhas, para a altura não saltar quando
                    // a tempestade passa de uma célula a outra (cada célula tem a sua altitude).
                    int nb = neighbourBase(p, types, i, j);
                    top += nb - base; base = nb;
                }
                if (t == CloudType.CUMULONIMBUS && cbScale[idx] == 0) congestus(i, j, cellX0 + i, cellZ0 + j, base, top, cover);
                else if (t == CloudType.CUMULONIMBUS)
                    giantCumulonimbus(i, j, cellX0 + i, cellZ0 + j, base, top, Math.abs(cbScale[idx]), cbScale[idx] < 0);
                else shape(t, i, j, cellX0 + i, cellZ0 + j, base, top, cover);
            }
    }

    private final double windX, windZ;
    /** Nuvens-parede das supercélulas (x, z, Y da base), para o funil do tornado terminar nelas. */
    static volatile List<double[]> wallClouds = List.of();
    private final List<double[]> walls = new ArrayList<>();
    /**
     * Cumulonimbos com bigorna, para os raios (LightningFx): x, z (blocos, sem o vento desde a
     * montagem), Y da base, Y do topo da torre, raio da torre em blocos e maturidade (0 a 1).
     */
    static volatile List<double[]> matureStorms = List.of();
    private final List<double[]> storms = new ArrayList<>();
    /** Olhos de furacão (x, z, raio em blocos): o ar desce ali e nenhuma bigorna os cobre. */
    private final List<double[]> eyes = new ArrayList<>();

    private boolean inEye(int gx, int gz) {
        double x = originX + (gx + 0.5) * colW, z = originZ + (gz + 0.5) * colW;
        for (double[] e : eyes) if (Math.hypot(x - e[0], z - e[1]) < e[2]) return true;
        return false;
    }

    /**
     * Num aglomerado de células de cumulonimbo, só uma vira a torre principal; as outras viram
     * cúmulos congestos ao redor. Manda a torre mais velha (a que começou a crescer antes), para
     * a torre principal não pular de célula enquanto as vizinhas alcançam a mesma altura; com
     * idades iguais, a de topo mais alto e depois a de mais cobertura.
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
        return n > 0 ? sum / n : Math.round(p.refY() + 45 * p.cellBlocks() / 16f);
    }

    private static long strength(CloudInput p, int idx) {
        long age = p.born()[idx] == 0 ? 0 : Integer.MAX_VALUE - (long) p.born()[idx];
        return (age << 24) + (long) p.topY()[idx] * 256 + (p.cover()[idx] & 0xFF);
    }

    /** Raio (em colunas) de uma torre convectiva de altura h, com proporções reais. */
    private double towerRadius(float h) { return (5 * S + 0.28 * h) / colW; }

    /**
     * Deslocamento (colunas) do centro de uma célula que acabou de brotar: ela nasce colada no
     * flanco da vizinha que a disparou e vai se afastando para o próprio centro enquanto cresce.
     */
    private double sproutX() { return curFromX * 0.55 * COLS_PER_CELL * (1 - curGrow); }
    private double sproutZ() { return curFromZ * 0.55 * COLS_PER_CELL * (1 - curGrow); }

    /**
     * Domo convectivo: base reta e topo em couve-flor, com lados quase verticais nas torres
     * altas. (pcx, pcz) é o centro em colunas relativas à célula, r o raio em colunas e h a altura.
     */
    private void dome(int i, int j, int cellX, int cellZ, double pcx, double pcz, double r, float h, int base,
                      CloudType t, double amp) {
        int gx0 = i * COLS_PER_CELL, gz0 = j * COLS_PER_CELL;
        int reach = (int) Math.ceil(r + Math.max(Math.abs(pcx - 2), Math.abs(pcz - 2))) + 1;
        curSolid = h > 24 * S;
        double sides = Math.min(1, h / (40.0 * S));       // 0: domo achatado; 1: torre de lados retos
        for (int b = -reach; b < COLS_PER_CELL + reach; b++)
            for (int a = -reach; a < COLS_PER_CELL + reach; a++) {
                int wx = cellX * COLS_PER_CELL + a, wz = cellZ * COLS_PER_CELL + b;
                double d = Math.hypot(a + 0.5 - pcx, b + 0.5 - pcz) / (r * (0.85 + 0.3 * vnoise(wx / 1.7, wz / 1.7, 42)));
                if (d >= 1) continue;
                double prof = (1 - sides) * (1 - d * d) + sides * Math.sqrt(Math.max(0, 1 - Math.pow(d, 4)));
                // Topo "fervendo" enquanto a nuvem cresce: bolhas que sobem e descem devagar.
                double bubble = amp * (vnoise(wx / 1.4 + time * 0.007, wz / 1.4, 41) * 2 - 1);
                float hh = Math.round(h * prof + bubble * S * Math.min(1, h / (12.0 * S)));
                if (hh < 2) continue;
                setA(gx0 + a, gz0 + b, base + (d > 0.75 ? Math.round(S) : 0), base + hh, t);
            }
        curSolid = false;
    }

    /**
     * Cúmulo congesto: torre de tempestade menor, sem bigorna, ao lado da torre principal. Até
     * 40 blocos acompanha a altura da célula (continua o cúmulo que estava crescendo ali); acima
     * disso fica em ~60% dela, mais baixa que a torre principal.
     */
    private void congestus(int i, int j, int cellX, int cellZ, int base, int top, float cover) {
        float full = top - base, h = Math.min(full, Math.max(40 * S, 0.6f * full));
        double ccx = 2 + (rnd(cellX, cellZ, 70) - 0.5) + sproutX(), ccz = 2 + (rnd(cellX, cellZ, 71) - 0.5) + sproutZ();
        double r = towerRadius(h) * (0.8 + 0.25 * cover);
        dome(i, j, cellX, cellZ, ccx, ccz, r, h, base, CloudType.CUMULUS, 2 + 5 * curBoil);
        pileus(i * COLS_PER_CELL, j * COLS_PER_CELL, ccx, ccz, r, base + h + (3 + 5 * curBoil) * S);
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
        float h = Math.max(20 * S, top - base);
        curSolid = true;
        // A bigorna só existe depois que a torre amadurece (curAnvil > 0) e fica no topo que a
        // torre alcançou, mesmo que ela depois desmorone (bigorna "órfã").
        boolean hasAnvil = curAnvil > 0.02f;
        float anvilTopY = hasAnvil ? Math.max(curAnvilTop, base + h) : base + h;
        float anvil = Math.max(4 * S, Math.round((anvilTopY - base) / 6f));
        float towerH = hasAnvil ? Math.max(4 * S, Math.min(h, anvilTopY - anvil - base)) : h;
        double ccx = 2 + (rnd(cellX, cellZ, 50) - 0.5) + sproutX(), ccz = 2 + (rnd(cellX, cellZ, 51) - 0.5) + sproutZ();
        if (supercell) {
            // A torre fica exatamente sobre a supercélula e anda com ela, em vez de pular de
            // célula em célula quando a tempestade cruza uma divisa.
            for (StormInfo st : stormList) {
                if (st.stormKind() != Storm.Kind.SUPERCELL) continue;
                if (Math.floorDiv((int) Math.floor(st.x()), cellBlocks) != cellX || Math.floorDiv((int) Math.floor(st.z()), cellBlocks) != cellZ) continue;
                ccx = (st.x() - (double) cellX * cellBlocks) / colW;
                ccz = (st.z() - (double) cellZ * cellBlocks) / colW;
            }
        }
        // A escala do aglomerado entra aos poucos, para a torre não engordar de repente quando
        // o cúmulo que crescia ali vira cumulonimbo.
        float grown = supercell ? 1 : Math.max(0, Math.min(1, (h - 40 * S) / (50f * S)));
        double sc = 1 + (scale - 1) * grown;
        double rt = towerRadius(towerH) * sc, ra = 3.2 * towerRadius(h) * sc;
        int reachT = (int) Math.ceil(rt) + 1;
        for (int b = -reachT; b < COLS_PER_CELL + reachT; b++)
            for (int a = -reachT; a < COLS_PER_CELL + reachT; a++) {
                int wx = cellX * COLS_PER_CELL + a, wz = cellZ * COLS_PER_CELL + b;
                double d = Math.hypot(a + 0.5 - ccx, b + 0.5 - ccz) / (rt * (0.85 + 0.3 * vnoise(wx / 1.5, wz / 1.5, 52)));
                int gx = gx0 + a, gz = gz0 + b;
                if (d >= 1 || gx < 0 || gz < 0 || gx >= cols || gz >= cols) continue;
                // Lados quase verticais (a torre sobe como uma coluna até a bigorna), "ombros"
                // arredondados só na borda e bolhas no topo.
                double amp = (4 + 6 * curBoil) * S;                 // bolhas maiores enquanto a torre cresce
                float y1 = base + Math.round(towerH * (float) Math.sqrt(Math.max(0, 1 - Math.pow(d, 4)))
                        + (float) (vnoise(wx / 1.6 + time * 0.006, wz / 1.6, 54) * 2 * amp - amp));
                float y0 = base + (d > 0.8 ? Math.round(2 * S) : 0);
                if (supercell && d < 0.45) y0 = base - Math.round((7 - (float) (d / 0.45) * 3) * S);   // nuvem-parede
                setA(gx, gz, y0, y1, t);
                int k = index(gx, gz);
                towerBase[k] = base; towerTop[k] = base + towerH;
                if (d < (supercell ? 0.75 : 0.6) && (rnd(wx, wz, 55) < 0.6)) addRain(gx, gz, y0);
            }
        curSolid = false;
        if (supercell) walls.add(new double[] {originX + (gx0 + ccx) * colW, originZ + (gz0 + ccz) * colW, base - Math.round(7 * S)});
        if (!hasAnvil) {
            pileus(gx0, gz0, ccx, ccz, rt, base + towerH + (4 + 6 * curBoil) * S);
            return;
        }
        float mature = curAnvil * curOp;
        storms.add(new double[] {originX + (gx0 + ccx) * colW, originZ + (gz0 + ccz) * colW, base, base + towerH,
                rt * colW, mature});
        if (mature > 0.3f) shelf(gx0, gz0, ccx, ccz, rt, base, (mature - 0.3f) / 0.7f);
        ra *= 0.2 + 0.8 * Math.sqrt(curAnvil);                      // a bigorna se espalha aos poucos
        // Bigorna: o vento em altitude a estica bastante para a frente (sotavento) e pouco para
        // trás. É grossa junto à torre e afina até poucos blocos nas bordas.
        double acx = ccx + windX * 0.25 * ra, acz = ccz + windZ * 0.25 * ra;
        int reachA = (int) Math.ceil(1.6 * ra) + 1;
        for (int b = -reachA; b < COLS_PER_CELL + reachA; b++)
            for (int a = -reachA; a < COLS_PER_CELL + reachA; a++) {
                int wx = cellX * COLS_PER_CELL + a, wz = cellZ * COLS_PER_CELL + b;
                double dx = a + 0.5 - acx, dz = b + 0.5 - acz;
                double along = dx * windX + dz * windZ, across = -dx * windZ + dz * windX;
                along /= along > 0 ? 1.5 : 0.75;
                double d = Math.hypot(along, across) / (ra * (0.8 + 0.35 * vnoise(wx / 2.5, wz / 2.5, 53)));
                if (d >= 1) continue;
                float thick = Math.max(2 * S, Math.round(anvil * (1 - 0.7f * (float) d)
                        + (float) (vnoise(wx / 1.8, wz / 1.8, 56) * 3 - 1.5) * S));
                float y1 = anvilTopY - Math.round(((d > 0.85 ? 1 : 0) + 2 * (float) (d * d)) * S);
                float y0 = y1 - thick;
                double dt = Math.hypot(a + 0.5 - ccx, b + 0.5 - ccz);
                if (supercell && dt < rt * 0.3) y1 += Math.round(6 * S * (1 - dt / (rt * 0.3)));   // topo saliente
                // Mammatus: bolsas de ar frio e úmido que afundam sob a bigorna madura, mais
                // comuns na parte de baixo da bigorna que o vento levou para longe da torre.
                float pouch = 0;
                if (curAnvil > 0.6f && along > 0 && d > 0.3 && d < 0.92 && dt > rt * 1.1) {
                    float m = Math.min(1, (curAnvil - 0.6f) / 0.4f) * (float) (1 - Math.abs(d - 0.6) / 0.33);
                    if (m > 0) pouch = Math.round(4 * S * m * mammatus(wx, wz));
                }
                setB(gx0 + a, gz0 + b, y0 - pouch, y1);
                if (pouch > 0) markB(gx0 + a, gz0 + b, DECO_MAMMATUS);
            }
    }

    /**
     * Pileus: o topo que sobe depressa empurra uma camada úmida e a resfria, formando um
     * capuz liso e efêmero logo acima; a torre o fura em seguida. Aparece no meio do
     * crescimento (curGrow entre 0,3 e 0,85), quando a corrente ascendente é mais forte.
     */
    private void pileus(int gx0, int gz0, double ccx, double ccz, double rt, float capY) {
        if (curBoil < 0.5f || curGrow < 0.3f || curGrow > 0.85f) return;
        float strength = (float) Math.sin(Math.PI * (curGrow - 0.3f) / 0.55f) * curOp;
        if (strength < 0.15f) return;
        double r = rt * 0.8;
        int reach = (int) Math.ceil(r) + 1;
        for (int b = -reach; b <= reach; b++)
            for (int a = -reach; a <= reach; a++) {
                double d = Math.hypot(a + 0.5 - (ccx - Math.floor(ccx)) , b + 0.5 - (ccz - Math.floor(ccz))) / r;
                if (d >= 1) continue;
                int gx = gx0 + (int) Math.floor(ccx) + a, gz = gz0 + (int) Math.floor(ccz) + b;
                float y0 = capY - Math.round(3 * S * (float) (d * d));     // capuz curvado como um chapéu
                float save = curAnvilOp;
                curAnvilOp = strength;
                setB(gx, gz, y0, y0 + Math.round(2 * S));
                curAnvilOp = save;
                markB(gx, gz, DECO_PILEUS);
            }
    }

    /**
     * Nuvem-prateleira (arcus): o ar frio que desce com a chuva se espalha pelo chão à frente
     * da tempestade e levanta o ar quente como uma rampa. Fica no lado para onde a tempestade
     * anda, em arco, abaixo da base; a borda da frente é a mais baixa.
     */
    private void shelf(int gx0, int gz0, double ccx, double ccz, double rt, int base, float m) {
        double inner = rt * 1.0, outer = rt * (1.15 + 0.45 * m);
        int reach = (int) Math.ceil(outer) + 1;
        for (int b = -reach; b < COLS_PER_CELL + reach; b++)
            for (int a = -reach; a < COLS_PER_CELL + reach; a++) {
                double dx = a + 0.5 - ccx, dz = b + 0.5 - ccz, dist = Math.hypot(dx, dz);
                if (dist < inner || dist > outer) continue;
                double along = (dx * windX + dz * windZ) / dist;
                if (along < 0.55) continue;                             // só na frente da tempestade (±56°)
                float edge = (float) ((along - 0.55) / 0.45);           // 1 bem na frente, 0 nas pontas do arco
                double out = inner + (outer - inner) * (0.35 + 0.65 * edge);
                if (dist > out) continue;
                double t = (dist - inner) / (out - inner);              // 0 junto à torre, 1 na borda
                // Cunha: topo liso descendo para a frente e base rasgada, mais baixa na borda.
                float y1 = base + Math.round((2 - 5 * (float) t) * S);
                float y0 = base - Math.round((2 + 7 * (float) t) * (0.4f + 0.6f * edge) * (0.5f + 0.5f * m) * S);
                if (y1 - y0 < 1) continue;
                int gx = gx0 + a, gz = gz0 + b;
                setA(gx, gz, y0, y1, CloudType.CUMULONIMBUS);
                markA(gx, gz, DECO_SHELF);
            }
    }

    /**
     * Profundidade (0 a 1) da bolsa de mammatus sob a coluna: bolsas de tamanhos diferentes,
     * espalhadas sem padrão (cada célula de 3x3 colunas tem a sua, deslocada ao acaso), e
     * algumas células vazias.
     */
    private static float mammatus(int wx, int wz) {
        int cx = Math.floorDiv(wx, 3), cz = Math.floorDiv(wz, 3);
        float best = 0;
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) {
                int ix = cx + dx, iz = cz + dz;
                if (rnd(ix, iz, 57) < 0.3) continue;                    // sem bolsa nesta célula
                double px = ix * 3 + 3 * rnd(ix, iz, 58), pz = iz * 3 + 3 * rnd(ix, iz, 59);
                double size = 1.1 + 1.3 * rnd(ix, iz, 60);
                double r = Math.hypot(wx + 0.5 - px, wz + 0.5 - pz) / size;
                if (r < 1) best = Math.max(best, (float) Math.sqrt(1 - r * r));
            }
        return best;
    }

    private void markA(int gx, int gz, byte d) {
        if (gx >= 0 && gz >= 0 && gx < cols && gz < cols && matA[index(gx, gz)] != EMPTY) decoA[index(gx, gz)] = d;
    }

    private void markB(int gx, int gz, byte d) {
        if (gx >= 0 && gz >= 0 && gx < cols && gz < cols && matB[index(gx, gz)] != EMPTY) decoB[index(gx, gz)] = d;
    }

    /**
     * Nuvens nascendo ou se desfazendo não ficam só transparentes: elas aparecem e somem em
     * fiapos (cúmulos fractus), como as de verdade, que começam como farrapos que se juntam e
     * terminam rasgadas pelo ar seco. Retorna a opacidade da coluna, ou -1 se ela ainda (ou já)
     * não existe; {@code frac} recebe a fração da altura que a coluna mantém.
     */
    private float wisp(int gx, int gz, float op, int salt, float[] frac) {
        frac[0] = 1;
        if (op >= 0.999f) return op;
        int wx = colX0 + gx, wz = colZ0 + gz;
        double n = 0.7 * vnoise(wx / 2.2, wz / 2.2, salt) + 0.3 * rnd(wx, wz, salt + 1);
        float keep = op * 1.4f - (float) n;
        if (keep <= 0) return -1;
        frac[0] = Math.min(1, keep * 1.2f);
        return Math.min(1, 0.45f + keep * 1.6f) * Math.min(1, op * 3);
    }

    private final float[] wispFrac = new float[1];

    int index(int gx, int gz) { return gz * cols + gx; }

    private void setA(int gx, int gz, float y0, float y1, CloudType t) {
        if (gx < 0 || gz < 0 || gx >= cols || gz >= cols || y1 - y0 < 1) return;
        float op = curOp;
        wispFrac[0] = 1;
        if (!curSolid) op = wisp(gx, gz, curOp, 81, wispFrac);
        if (op < 0) return;
        if (wispFrac[0] < 1) {                                       // farrapo: um pedaço no meio da nuvem
            // Achatado: no máximo uma coluna e meia de espessura, como os retalhos das nuvens do jogo.
            float h = y1 - y0, hk = Math.max(1, Math.round(Math.min(h * wispFrac[0] * wispFrac[0], colW * (0.5 + wispFrac[0]))));
            y0 += Math.round((h - hk) * (float) rnd(colX0 + gx, colZ0 + gz, 83) * 0.8f);
            y1 = y0 + hk;
        }
        int k = index(gx, gz);
        if (matA[k] != EMPTY && y1a[k] - y0a[k] >= y1 - y0) return;   // fica o trecho maior
        y0a[k] = y0; y1a[k] = y1; matA[k] = (byte) t.ordinal(); opA[k] = op; decoA[k] = DECO_NONE;
    }

    private void setB(int gx, int gz, float y0, float y1) {
        if (gx < 0 || gz < 0 || gx >= cols || gz >= cols || y1 - y0 < 1 || inEye(gx, gz)) return;
        int k = index(gx, gz);
        if (matB[k] != EMPTY) return;
        if (matA[k] != EMPTY && y1a[k] > y0) return;                   // não atravessa outra nuvem
        float op = wisp(gx, gz, curAnvilOp, 91, wispFrac);             // bigorna órfã se desfaz em fiapos
        if (op < 0) return;
        y0b[k] = y0; y1b[k] = y1; matB[k] = (byte) CloudType.CUMULONIMBUS.ordinal(); opB[k] = op; decoB[k] = DECO_NONE;
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
                // Um domo principal, mais largo quanto mais alto (proporções reais), e até dois
                // tufos menores e mais baixos ao lado; a base é reta, como nos cúmulos de verdade.
                double r0 = towerRadius(h) * (0.75 + 0.35 * cover);
                double ccx = 2 + 1.2 * (rnd(cellX, cellZ, 10) - 0.5) + sproutX();
                double ccz = 2 + 1.2 * (rnd(cellX, cellZ, 20) - 0.5) + sproutZ();
                dome(i, j, cellX, cellZ, ccx, ccz, r0, h, base, t, 1.5 + 3 * curBoil);
                if (cover > 0.9f && h > 30 * S) {                 // cúmulo carregado: pancada de chuva sob o centro
                    for (int b = 1; b <= 2; b++)
                        for (int a = 1; a <= 2; a++)
                            if (rnd(cellX * COLS_PER_CELL + a, cellZ * COLS_PER_CELL + b, 43) < 0.7)
                                addRain(gx0 + (int) Math.round(ccx) - 2 + a, gz0 + (int) Math.round(ccz) - 2 + b, base);
                }
                if (h > 18 * S) pileus(gx0, gz0, ccx, ccz, r0, base + h + (2 + 3 * curBoil) * S);   // cúmulo virando congesto
                int puffs = (cover > 0.5f ? 1 : 0) + (cover > 0.8f ? 1 : 0);
                for (int q = 1; q <= puffs; q++) {
                    double ang = 6.283 * rnd(cellX, cellZ, 30 + q), dist = r0 * (0.7 + 0.3 * rnd(cellX, cellZ, 35 + q));
                    float hq = h * (float) (0.4 + 0.25 * rnd(cellX, cellZ, 40 + q));
                    dome(i, j, cellX, cellZ, ccx + Math.cos(ang) * dist, ccz + Math.sin(ang) * dist,
                            towerRadius(hq) * 0.8, hq, base, t, 1 + 2 * curBoil);
                }
            }
            case STRATUS -> {
                // Camada fina e contínua; o ruído (em coordenadas do mundo) recorta as bordas.
                for (int b = 0; b < COLS_PER_CELL; b++)
                    for (int a = 0; a < COLS_PER_CELL; a++) {
                        int wx = cellX * COLS_PER_CELL + a, wz = cellZ * COLS_PER_CELL + b;
                        if (vnoise(wx / 2.5, wz / 2.5, 11) > 0.35 + 0.65 * cover) continue;
                        float edge = layerEdge(i, j, a, b);
                        if (edge <= 0) continue;
                        float y0 = base + Math.round(vnoise(wx / 3.0, wz / 3.0, 12) * 2 * S);
                        float th = (Math.max(2, h) + Math.round(vnoise(wx / 2.0, wz / 2.0, 13) * 2 * S)) * edge;
                        setA(gx0 + a, gz0 + b, y0, y0 + Math.max(2, Math.round(th)), t);
                    }
            }
            case NIMBOSTRATUS -> {
                // Camada grossa e escura, com a base "rasgada" e cortinas de chuva.
                for (int b = 0; b < COLS_PER_CELL; b++)
                    for (int a = 0; a < COLS_PER_CELL; a++) {
                        int wx = cellX * COLS_PER_CELL + a, wz = cellZ * COLS_PER_CELL + b;
                        if (vnoise(wx / 2.0, wz / 2.0, 21) > 0.45 + 0.45 * cover) continue;
                        double rag = vnoise(wx / 1.5, wz / 1.5, 22);
                        float y0 = base - Math.round((rag > 0.62 ? 3 : rag > 0.45 ? 1 : 0) * S);
                        // Topo encaroçado: o ar ainda sobe em bolhas dentro da camada.
                        float edge = layerEdge(i, j, a, b);
                        if (edge <= 0) continue;
                        float y1 = y0 + Math.max(2, Math.round((top - y0 + (vnoise(wx / 1.6, wz / 1.6, 23) * 8 - 3) * S) * edge));
                        setA(gx0 + a, gz0 + b, y0, y1, t);
                        if (cover > 0.35f && vnoise(wx / 1.2, wz / 1.2, 24) > 0.58) addRain(gx0 + a, gz0 + b, y0);
                    }
            }
            case CUMULONIMBUS -> giantCumulonimbus(i, j, cellX, cellZ, base, top, 1f, false);
            case CIRRUS -> {
                // Duas faixas finas e altas, quase paralelas, cruzando a célula.
                for (int s = 0; s < 2; s++) {
                    double off = 0.6 + 2.6 * rnd(cellX, cellZ, 60 + s), slope = (rnd(cellX, cellZ, 62) - 0.5) * 0.6;
                    float y = base + Math.round(4 * S * rnd(cellX, cellZ, 64 + s));
                    for (int a = -3; a <= COLS_PER_CELL + 2; a++) {     // faixas longas, além da célula
                        int b = (int) Math.floor(off + slope * a);
                        if (b < 0 || b >= COLS_PER_CELL) continue;
                        float thin = Math.max(1, Math.round(0.67f * S));
                        setA(gx0 + a, gz0 + b, y, y + thin, t);
                        if (rnd(cellX * 8 + a, cellZ, 66 + s) < 0.6) setA(gx0 + a, gz0 + b + 1, y, y + thin, t);
                    }
                }
            }
            default -> {}
        }
    }

    /**
     * Espessura relativa (0 a 1) de uma coluna de nuvem em camada: inteira no meio da camada e
     * afinando até sumir nas bordas, onde a célula vizinha não tem camada. Sem isso cada célula
     * de borda terminaria num paredão vertical, e uma célula sozinha pareceria um prédio.
     */
    private float layerEdge(int i, int j, int a, int b) {
        double fx = i + (a + 0.5) / COLS_PER_CELL - 0.5, fz = j + (b + 0.5) / COLS_PER_CELL - 0.5;
        int i0 = (int) Math.floor(fx), j0 = (int) Math.floor(fz);
        double tx = fx - i0, tz = fz - j0;
        double f = (isLayer(i0, j0) * (1 - tx) + isLayer(i0 + 1, j0) * tx) * (1 - tz)
                + (isLayer(i0, j0 + 1) * (1 - tx) + isLayer(i0 + 1, j0 + 1) * tx) * tz;
        double g = Math.max(0, Math.min(1, (f - 0.45) / 0.45));
        return (float) (g * g * (3 - 2 * g));
    }

    /** 1 se a célula tem nuvem em camada; fora do recorte vale 1, para a camada não afinar na borda do que se vê. */
    private int isLayer(int i, int j) {
        if (i < 0 || j < 0 || i >= size || j >= size) return 1;
        return layer[j * size + i] ? 1 : 0;
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
