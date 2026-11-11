package br.climate.core;

import static br.climate.core.Physics.*;

import java.util.ArrayDeque;

/**
 * Modelo climático simplificado em grade. Variáveis de estado por célula:
 * temperatura, pressão reduzida ao nível do mar, pressão de vapor, água de nuvem e vento.
 * Todo o resto (tipo de nuvem, chuva, neblina) é diagnosticado a partir delas.
 *
 * Eixos: +X = leste, +Z = sul (convenção do Minecraft). Hemisfério norte = Z negativo.
 */
public final class ClimateGrid {
    public final int nx, nz;
    public int originCellX, originCellZ;
    private final TerrainSource terrain;
    public final ClimateConfig cfg;
    private final double dx; // metros reais por célula

    // Campos estáticos (recalculados só se o terreno mudar)
    public final double[] elev, wet, cont;
    public final boolean[] water;

    // Estado dinâmico
    public final double[] temp, pSea, e, cloud, u, v;
    // Diagnósticos
    public final double[] wOro, wDiv, wTh, w, pTend, precipRate, precipTotal;
    /** Anomalia de temperatura suavizada (°C), que gera as baixas térmicas e as brisas. */
    public final double[] anom;
    /**
     * Sistemas de tempo que passam: 0 = crista (ar descendo, tempo firme), 0,5 = neutro,
     * 1 = cavado (ar subindo, tempo instável). Diagnóstico, recalculado a cada passo.
     */
    public final double[] sys;
    public final CloudType[] type;
    /** Furacões, supercélulas e tornados em andamento. */
    public final StormSystem storms;

    private final double[] tmpA, tmpB;
    private double curYr, curHours;

    public ClimateGrid(int nx, int nz, int originCellX, int originCellZ,
                       ClimateConfig cfg, TerrainSource terrain) {
        this.nx = nx; this.nz = nz;
        this.originCellX = originCellX; this.originCellZ = originCellZ;
        this.cfg = cfg;
        this.terrain = terrain;
        this.dx = cfg.cellBlocks * cfg.metersPerBlockH();
        int n = nx * nz;
        elev = new double[n]; wet = new double[n]; cont = new double[n]; water = new boolean[n];
        temp = new double[n]; pSea = new double[n]; e = new double[n]; cloud = new double[n];
        u = new double[n]; v = new double[n];
        wOro = new double[n]; wDiv = new double[n]; wTh = new double[n]; w = new double[n];
        pTend = new double[n]; precipRate = new double[n]; precipTotal = new double[n];
        anom = new double[n];
        sys = new double[n];
        type = new CloudType[n];
        storms = new StormSystem(31L * originCellX + originCellZ);
        storms.cellM = dx;
        tmpA = new double[n]; tmpB = new double[n];

        for (int j = 0; j < nz; j++)
            for (int i = 0; i < nx; i++) {
                int k = j * nx + i;
                int cx = originCellX + i, cz = originCellZ + j;
                water[k] = terrain.isWater(cx, cz);
                elev[k] = water[k] ? 0 : Math.max(0, terrain.elevationMeters(cx, cz));
                wet[k] = clamp(terrain.wetness(cx, cz), 0, 1);
            }
        computeContinentality();
    }

    /** Cópia independente (para previsões): mesmo terreno e mesmo estado, matrizes próprias. */
    private ClimateGrid(ClimateGrid o) {
        nx = o.nx; nz = o.nz; originCellX = o.originCellX; originCellZ = o.originCellZ;
        terrain = o.terrain; cfg = o.cfg; dx = o.dx; curYr = o.curYr; curHours = o.curHours;
        elev = o.elev.clone(); wet = o.wet.clone(); cont = o.cont.clone(); water = o.water.clone();
        temp = o.temp.clone(); pSea = o.pSea.clone(); e = o.e.clone(); cloud = o.cloud.clone();
        u = o.u.clone(); v = o.v.clone();
        wOro = o.wOro.clone(); wDiv = o.wDiv.clone(); wTh = o.wTh.clone(); w = o.w.clone();
        pTend = o.pTend.clone(); precipRate = o.precipRate.clone(); precipTotal = o.precipTotal.clone();
        anom = o.anom.clone();
        sys = o.sys.clone();
        storms = o.storms.copy(31L * o.originCellX + o.originCellZ + 7);
        type = o.type.clone();
        tmpA = new double[o.tmpA.length]; tmpB = new double[o.tmpB.length];
    }

    public ClimateGrid copy() { return new ClimateGrid(this); }

    /**
     * Previsão do tempo num ponto: roda uma cópia da grade adiante e anota o clima a cada
     * {@code everyHours}. O modelo é determinístico, então, sem perturbações, a "previsão"
     * acertaria tudo. Para imitar a incerteza real, a temperatura inicial da cópia recebe um
     * ruído pequeno que cresce com o tempo, como nos modelos de previsão por conjunto.
     *
     * @param seed semente do ruído; a mesma semente dá a mesma previsão
     */
    public java.util.List<ClimateSample> forecast(double worldBlockX, double worldBlockZ, double surfaceElevM,
                                                  double gameHours, int hours, int everyHours, long seed) {
        ClimateGrid f = copy();
        java.util.Random rnd = new java.util.Random(seed);
        // Perturbação suave: ruído numa grade grossa (a cada 8 células), interpolado. Ruído
        // independente por célula criaria degraus de temperatura e, com eles, ventos falsos.
        int gx = nx / 8 + 2, gz = nz / 8 + 2;
        double[] coarse = new double[gx * gz];
        for (int q = 0; q < coarse.length; q++) coarse[q] = rnd.nextGaussian() * 0.6;
        double[] noise = new double[nx * nz];
        for (int j = 0; j < nz; j++)
            for (int i = 0; i < nx; i++) {
                double fx = i / 8.0, fz = j / 8.0;
                int x0 = (int) fx, z0 = (int) fz;
                double tx = fx - x0, tz = fz - z0;
                noise[j * nx + i] = lerp(lerp(coarse[z0 * gx + x0], coarse[z0 * gx + x0 + 1], tx),
                                         lerp(coarse[(z0 + 1) * gx + x0], coarse[(z0 + 1) * gx + x0 + 1], tx), tz);
            }
        java.util.List<ClimateSample> out = new java.util.ArrayList<>();
        double h = gameHours;
        for (int step = 1; step <= hours; step++, h++) {
            if (step % 6 == 1) for (int k = 0; k < nx * nz; k++) f.temp[k] += noise[k];
            f.step(1, h + 1);
            if (step % everyHours == 0) out.add(f.sample(worldBlockX, worldBlockZ, surfaceElevM));
        }
        return out;
    }

    // ---------- Geometria ----------

    public double latitudeDeg(int j) {
        double worldZ = (originCellZ + j + 0.5) * cfg.cellBlocks;
        return clamp(-worldZ / cfg.halfRangeBlocks, -1, 1) * 90.0;
    }

    private static double lapse(double elevM) { return LAPSE_ENV * elevM / 1000.0; }

    /** BFS multi-fonte a partir das células de água: distância ao oceano -> continentalidade. */
    private void computeContinentality() {
        int n = nx * nz;
        int[] dist = new int[n];
        java.util.Arrays.fill(dist, Integer.MAX_VALUE);
        ArrayDeque<Integer> q = new ArrayDeque<>();
        boolean[] ocean = oceanMask();
        for (int k = 0; k < n; k++) if (ocean[k]) { dist[k] = 0; q.add(k); }
        int[] di = {1, -1, 0, 0}, dj = {0, 0, 1, -1};
        while (!q.isEmpty()) {
            int k = q.poll(); int i = k % nx, j = k / nx;
            for (int d = 0; d < 4; d++) {
                int a = i + di[d], b = j + dj[d];
                if (a < 0 || b < 0 || a >= nx || b >= nz) continue;
                int m = b * nx + a;
                if (dist[m] == Integer.MAX_VALUE) { dist[m] = dist[k] + 1; q.add(m); }
            }
        }
        for (int k = 0; k < n; k++) {
            // Sem oceano na janela: assume continentalidade máxima.
            double full = Math.max(1, cfg.continentalityBlocks / cfg.cellBlocks);   // em células
            double d = dist[k] == Integer.MAX_VALUE ? full : dist[k];
            cont[k] = clamp(d / full, 0, 1);
        }
    }

    /**
     * Células de "oceano" para a continentalidade: água cercada de bastante água. Rios e
     * lagos pequenos (muito comuns no Minecraft) ficam de fora; eles têm a própria inércia
     * térmica como células de água, mas não tornam toda a região "marítima".
     */
    private boolean[] oceanMask() {
        int n = nx * nz, r = (int) Math.max(1, Math.round(cfg.oceanRadiusBlocks / (double) cfg.cellBlocks));
        boolean[] ocean = new boolean[n];
        for (int j = 0; j < nz; j++)
            for (int i = 0; i < nx; i++) {
                if (!water[j * nx + i]) continue;
                int wc = 0, c = 0;
                for (int b = Math.max(0, j - r); b <= Math.min(nz - 1, j + r); b++)
                    for (int a = Math.max(0, i - r); a <= Math.min(nx - 1, i + r); a++) {
                        c++;
                        if (water[b * nx + a]) wc++;
                    }
                ocean[j * nx + i] = wc >= cfg.oceanMinFraction * c;
            }
        return ocean;
    }

    // ---------- Inicialização ----------

    /** Coloca a grade num estado de equilíbrio plausível antes de começar. */
    public void initialize(double gameHours) {
        double tod = timeOfDay(gameHours), yr = yearFraction(gameHours);
        curYr = yr; curHours = gameHours;
        for (int j = 0; j < nz; j++)
            for (int i = 0; i < nx; i++) {
                int k = j * nx + i;
                temp[k] = targetTemp(k, j, tod, yr);
                e[k] = 0.7 * satPressure(temp[k]);
            }
        computePressure(1);
        computeWind(true);
        computeVertical();
        java.util.Arrays.fill(pTend, 0);
    }

    // ---------- Janela móvel ----------

    private double[] remap(double[] f, int sx, int sz) {
        double[] o = new double[f.length];
        for (int j = 0; j < nz; j++)
            for (int i = 0; i < nx; i++) {
                int a = i + sx, b = j + sz;
                if (a >= 0 && b >= 0 && a < nx && b < nz) o[j * nx + i] = f[b * nx + a];
            }
        return o;
    }

    /**
     * Desloca a janela da grade para uma nova origem, preservando o estado das células
     * que continuam dentro dela. Células novas recebem o equilíbrio local.
     * Chamado pelo mod quando o jogador se aproxima da borda.
     */
    public void recenter(int newOx, int newOz, double gameHours) {
        int sx = newOx - originCellX, sz = newOz - originCellZ;
        if (sx == 0 && sz == 0) return;
        int n = nx * nz;
        boolean[] fresh = new boolean[n];
        for (int j = 0; j < nz; j++)
            for (int i = 0; i < nx; i++) {
                int a = i + sx, b = j + sz;
                fresh[j * nx + i] = !(a >= 0 && b >= 0 && a < nx && b < nz);
            }
        double[][] all = {elev, wet, temp, pSea, e, cloud, u, v, wOro, wDiv, wTh, w, pTend, precipRate, precipTotal};
        for (double[] f : all) System.arraycopy(remap(f, sx, sz), 0, f, 0, n);
        boolean[] ow = water.clone();
        CloudType[] ot = type.clone();
        for (int j = 0; j < nz; j++)
            for (int i = 0; i < nx; i++) {
                int a = i + sx, b = j + sz, k = j * nx + i;
                boolean in = a >= 0 && b >= 0 && a < nx && b < nz;
                water[k] = in && ow[b * nx + a];
                type[k] = in ? ot[b * nx + a] : null;
            }
        originCellX = newOx; originCellZ = newOz;
        for (int j = 0; j < nz; j++)
            for (int i = 0; i < nx; i++) {
                int k = j * nx + i;
                if (!fresh[k]) continue;
                int cx = originCellX + i, cz = originCellZ + j;
                water[k] = terrain.isWater(cx, cz);
                elev[k] = water[k] ? 0 : Math.max(0, terrain.elevationMeters(cx, cz));
                wet[k] = clamp(terrain.wetness(cx, cz), 0, 1);
            }
        computeContinentality();
        double tod = timeOfDay(gameHours), yr = yearFraction(gameHours);
        curYr = yr; curHours = gameHours;
        for (int j = 0; j < nz; j++)
            for (int i = 0; i < nx; i++) {
                int k = j * nx + i;
                if (!fresh[k]) continue;
                temp[k] = targetTemp(k, j, tod, yr);
                e[k] = 0.7 * satPressure(temp[k]);
                cloud[k] = 0; precipTotal[k] = 0;
            }
        computePressure(1);
        for (int k = 0; k < n; k++) if (fresh[k]) pTend[k] = 0;
        computeWind(false);
        computeVertical();
    }

    // ---------- Passo de simulação ----------

    public void step(double dtHours, double gameHours) {
        double tod = timeOfDay(gameHours), yr = yearFraction(gameHours);
        curYr = yr; curHours = gameHours;
        updateTemperature(dtHours, tod, yr);
        advect(dtHours);
        storms.evolve(this, dtHours);      // move os fenômenos antes de aplicá-los à grade
        computePressure(dtHours);
        storms.imposePressure(this);
        computeWind(false);
        storms.imposeWind(this);
        computeVertical();
        computeMoisture(dtHours);
        storms.imposeMoisture(this, dtHours);
        classify();
    }

    // ---------- Apoio aos fenômenos (StormSystem) ----------

    /** Latitude (graus) de uma coordenada Z do mundo. */
    public double latitudeAtZ(double worldZ) {
        return clamp(-worldZ / cfg.halfRangeBlocks, -1, 1) * 90.0;
    }

    /**
     * Vento de condução (m/s, leste e norte): o vento médio da troposfera que leva furacões e
     * tempestades. É quase zonal (leste-oeste) e mais fraco que o geostrófico da superfície:
     * metade dele, com Coriolis mínimo de 5° de latitude para não explodir no equador.
     */
    public double[] steeringWind(double worldZ) {
        double lat = latitudeAtZ(worldZ);
        double s = Math.sin(Math.toRadians(lat));
        if (Math.abs(s) < Math.sin(Math.toRadians(5))) s = Math.copySign(Math.sin(Math.toRadians(5)), s == 0 ? 1 : s);
        double f = 2 * OMEGA * s;
        return new double[] {-0.5 * hadleyGradientAtZ(worldZ) / (RHO * f), 0};
    }

    public double cellCenterX(int k) { return (originCellX + k % nx + 0.5) * cfg.cellBlocks; }

    public double cellCenterZ(int k) { return (originCellZ + k / nx + 0.5) * cfg.cellBlocks; }

    public boolean containsBlock(double x, double z) { return contains(x, z); }

    /** Perto da janela simulada (até uma largura de janela de distância): o fenômeno continua existindo. */
    public boolean nearWindow(double x, double z) {
        double w = nx * cfg.cellBlocks, h = nz * cfg.cellBlocks;
        double x0 = originCellX * (double) cfg.cellBlocks, z0 = originCellZ * (double) cfg.cellBlocks;
        return x > x0 - w && x < x0 + 2 * w && z > z0 - h && z < z0 + 2 * h;
    }

    private double timeOfDay(double h) { return (((h % 24) + 24) % 24) / 24.0; }
    private double yearFraction(double h) { return (h / (24.0 * cfg.daysPerYear)) % 1.0; }

    // ---------- Temperatura ----------

    /** Temperatura média do dia (sem o ciclo dia/noite) para a célula, na latitude e estação. */
    private double dailyMeanTemp(int k, int j, double yr) {
        double lat = latitudeDeg(j);
        double decl = 23.45 * Math.sin(2 * Math.PI * yr);              // declinação solar
        double seasonal = decl * (0.4 + 0.6 * cont[k]);                 // continente sente mais as estações
        double phi = Math.toRadians(lat - 0.6 * seasonal);
        double teq = 29.0 - 48.0 * Math.sin(phi) * Math.sin(phi);       // equilíbrio radiativo por latitude
        return teq - lapse(elev[k]);
    }

    private double targetTemp(int k, int j, double tod, double yr) {
        // Amplitude diária: mínima sobre o mar, moderada no litoral, grande no interior.
        double diurnalAmp = water[k] ? 1.0 : lerp(2.5, 9.0, cont[k]);
        // Nuvem faz sombra de dia e segura o calor à noite: o céu encoberto achata o ciclo diário.
        diurnalAmp *= 1 - cfg.cloudShading * clamp(cloud[k] / 0.6, 0, 1);
        double diurnal = diurnalAmp * Math.sin(2 * Math.PI * (tod - 0.30));
        return dailyMeanTemp(k, j, yr) + diurnal;
    }

    // ---------- Ruído determinístico (sistemas de tempo e manchas de convecção) ----------

    private static double hash(int x, int z, int t, int salt) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ t * 0xD6E8FEB86659FD93L ^ salt * 0x165667B19E3779F9L;
        h ^= h >>> 33; h *= 0xFF51AFD7ED558CCDL; h ^= h >>> 33; h *= 0xC4CEB9FE1A85EC53L; h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Ruído suave de 0 a 1 em (x, z, t): valores sorteados numa grade e interpolados. */
    private static double noise3(double x, double z, double t, int salt) {
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z), t0 = (int) Math.floor(t);
        double fx = x - x0, fz = z - z0, ft = t - t0;
        fx = fx * fx * (3 - 2 * fx); fz = fz * fz * (3 - 2 * fz); ft = ft * ft * (3 - 2 * ft);
        double a = lerp(lerp(hash(x0, z0, t0, salt), hash(x0 + 1, z0, t0, salt), fx),
                        lerp(hash(x0, z0 + 1, t0, salt), hash(x0 + 1, z0 + 1, t0, salt), fx), fz);
        double b = lerp(lerp(hash(x0, z0, t0 + 1, salt), hash(x0 + 1, z0, t0 + 1, salt), fx),
                        lerp(hash(x0, z0 + 1, t0 + 1, salt), hash(x0 + 1, z0 + 1, t0 + 1, salt), fx), fz);
        return lerp(a, b, ft);
    }

    /**
     * Sistema de tempo (0 = crista, 1 = cavado) num ponto do mundo, em blocos. O vento do
     * modelo é quase estacionário, então sem isto o ar subiria sempre nos mesmos lugares e
     * algumas regiões ficariam nubladas para sempre. Na atmosfera real, ondas de grande escala
     * (cristas e cavados) passam a cada poucos dias e alternam tempo firme e tempo instável.
     * Aqui elas são um campo suave que anda para leste e muda devagar; como depende só da
     * posição no mundo e da hora, é reproduzível e a previsão o enxerga.
     */
    public double weatherSystem(double worldBlockX, double worldBlockZ) {
        return weatherSystem(worldBlockX, worldBlockZ, curHours);
    }

    private double weatherSystem(double worldBlockX, double worldBlockZ, double hours) {
        if (cfg.systemStrength <= 0) return 0.5;
        double mpb = cfg.metersPerBlockH();
        double drift = cfg.systemSpeed * cfg.windSpeedFactor * 3600 * hours;      // metros percorridos
        double n = noise3((worldBlockX * mpb - drift) / cfg.systemSizeM, worldBlockZ * mpb / cfg.systemSizeM,
                hours / cfg.systemLifeH, 7);
        return clamp(0.5 + (n - 0.5) * 2.4 * cfg.systemStrength, 0, 1);
    }

    /**
     * Quanto da subida de ar de grande escala (encostas, convergência) vira nuvem agora: nada
     * sob a crista, um quarto em tempo neutro, tudo e mais um pouco sob o cavado. O vento do modelo
     * é quase estacionário; sem este fator choveria sem parar nas mesmas encostas e costas.
     */
    private double liftGate(int k) {
        return cfg.systemStrength <= 0 ? 1 : clamp((sys[k] - 0.4) / 0.4, 0, 1.5);
    }

    /**
     * Mancha de convecção (0 a 1,2): as térmicas não sobem em toda parte ao mesmo tempo. Uma
     * célula favorecida agora deixa de ser em poucas horas, o que espalha os cúmulos em campos
     * irregulares e faz as trovoadas nascerem em pontos e linhas, não numa região inteira.
     */
    private double convectivePatch(int k) {
        double mpb = cfg.metersPerBlockH();
        double n = noise3(cellCenterX(k) * mpb / cfg.patchSizeM, cellCenterZ(k) * mpb / cfg.patchSizeM,
                curHours / cfg.patchLifeH, 11);
        return clamp((n - 0.38) / 0.25, 0, 1.2);
    }

    private void updateTemperature(double dt, double tod, double yr) {
        for (int j = 0; j < nz; j++)
            for (int i = 0; i < nx; i++) {
                int k = j * nx + i;
                double tau = water[k] ? 96.0 : lerp(5.0, 3.0, cont[k]); // inércia térmica (h de jogo)
                double a = 1 - Math.exp(-dt / tau);
                temp[k] += (targetTemp(k, j, tod, yr) - temp[k]) * a;
            }
    }

    // ---------- Advecção (semi-Lagrangiana, estável) ----------

    private double sample(double[] f, double x, double z) {
        x = clamp(x, 0, nx - 1); z = clamp(z, 0, nz - 1);
        int x0 = (int) x, z0 = (int) z;
        int x1 = Math.min(x0 + 1, nx - 1), z1 = Math.min(z0 + 1, nz - 1);
        double fx = x - x0, fz = z - z0;
        double a = lerp(f[z0 * nx + x0], f[z0 * nx + x1], fx);
        double b = lerp(f[z1 * nx + x0], f[z1 * nx + x1], fx);
        return lerp(a, b, fz);
    }

    private void advectField(double[] f, double dt, boolean landOnly) {
        double dtS = dt * 3600.0 * cfg.windSpeedFactor;
        for (int j = 0; j < nz; j++)
            for (int i = 0; i < nx; i++) {
                int k = j * nx + i;
                if (landOnly && water[k]) { tmpB[k] = f[k]; continue; }
                double x = i - u[k] * dtS / dx;
                double z = j + v[k] * dtS / dx; // v é norte; z cresce para o sul
                tmpB[k] = sample(f, x, z);
            }
        System.arraycopy(tmpB, 0, f, 0, f.length);
    }

    private void advect(double dt) {
        int n = nx * nz;
        // Temperatura é advectada reduzida ao nível do mar, para não "carregar" o frio da montanha.
        for (int k = 0; k < n; k++) tmpA[k] = temp[k] + lapse(elev[k]);
        advectField(tmpA, dt, true);
        for (int k = 0; k < n; k++) if (!water[k]) temp[k] = tmpA[k] - lapse(elev[k]);
        advectField(e, dt, false);
        advectField(cloud, dt, false);
    }

    // ---------- Pressão ----------

    private void computePressure(double dt) {
        int n = nx * nz;
        double[] prev = tmpA;
        System.arraycopy(pSea, 0, prev, 0, n);
        double[] t0 = tmpB;
        for (int k = 0; k < n; k++) t0[k] = temp[k] + lapse(elev[k]);
        // Anomalia de temperatura (ar mais quente ou mais frio que o normal para a latitude),
        // suavizada na escala das brisas (dezenas de km): é ela que move a brisa térmica.
        for (int j = 0; j < nz; j++) {
            double mean = refTemp(latitudeDeg(j));
            for (int i = 0; i < nx; i++) anom[j * nx + i] = t0[j * nx + i] - mean;
        }
        int passes = (int) Math.max(1, Math.round(cfg.breezeSmoothBlocks / (double) cfg.cellBlocks));
        for (int q = 0; q < passes; q++) smooth(anom);
        for (int j = 0; j < nz; j++) {
            double hadley = hadleyHPa(latitudeDeg(j));
            for (int i = 0; i < nx; i++) {
                int k = j * nx + i;
                pSea[k] = 1013.25 + hadley - cfg.thermalLowHPaPerC * anom[k]    // ar quente -> baixa pressão
                        + (0.5 - weatherSystem(cellCenterX(k), cellCenterZ(k))) * 2 * cfg.systemHPa;  // crista alta, cavado baixo
            }
        }
        smooth(pSea); smooth(pSea);
        for (int k = 0; k < n; k++) pTend[k] = (pSea[k] - prev[k]) / dt;
    }

    /** Faixas de pressão da circulação geral: baixas no equador e em 60°, altas em 30° e nos polos. */
    private static double hadleyHPa(double lat) {
        return -8.0 * Math.cos(Math.toRadians(6 * lat));
    }

    /** Gradiente norte-sul (Pa/m, positivo se a pressão cresce para o norte) das faixas de Hadley. */
    private double hadleyGradient(int j) {
        return hadleyGradientAtZ((originCellZ + j + 0.5) * cfg.cellBlocks);
    }

    private double hadleyGradientAtZ(double worldZ) {
        if (Math.abs(worldZ) >= cfg.halfRangeBlocks) return 0;                 // além do polo
        double lat = latitudeAtZ(worldZ);
        double dPdLat = 8.0 * 6 * Math.sin(Math.toRadians(6 * lat)) * Math.PI / 180;  // hPa por grau
        double degPerMeter = 90.0 / (cfg.halfRangeBlocks * cfg.metersPerBlockH());
        return dPdLat * degPerMeter * 100.0;
    }

    /** Temperatura de referência (nível do mar) por latitude e estação, independente da janela. */
    private double refTemp(double lat) {
        double decl = 23.45 * Math.sin(2 * Math.PI * curYr);
        double phi = Math.toRadians(lat - 0.42 * decl);
        return 29.0 - 48.0 * Math.sin(phi) * Math.sin(phi);
    }

    private void smooth(double[] f) {
        double[] o = new double[f.length];
        for (int j = 0; j < nz; j++)
            for (int i = 0; i < nx; i++) {
                double s = 0; int c = 0;
                for (int b = -1; b <= 1; b++)
                    for (int a = -1; a <= 1; a++) {
                        int ii = i + a, jj = j + b;
                        if (ii < 0 || jj < 0 || ii >= nx || jj >= nz) continue;
                        s += f[jj * nx + ii]; c++;
                    }
                o[j * nx + i] = s / c;
            }
        System.arraycopy(o, 0, f, 0, f.length);
    }

    // ---------- Vento ----------

    /**
     * O vento é a soma de duas partes.
     *
     * Circulação geral: equilíbrio entre gradiente de pressão (faixas de Hadley), força de
     * Coriolis (f) e atrito com a superfície (k). A solução estacionária de
     *   -f v = -(1/ρ) ∂p/∂x - k u      e      f u = -(1/ρ) ∂p/∂y - k v
     * vale em qualquer latitude: longe do equador (f >> k) dá o vento geostrófico, desviado
     * pelo atrito em direção à baixa pressão; no equador (f = 0) o ar vai direto da alta para
     * a baixa. Daí saem os alísios, os ventos de oeste e as calmarias de 30° e 60°.
     *
     * Brisa térmica: em escalas de poucos km o equilíbrio geostrófico não vale. O ar frio
     * avança sob o quente como uma corrente de gravidade, com velocidade
     *   c ≈ 0,6 √(g h ΔT / T),
     * na direção do ar mais quente (brisa marítima de dia, terral à noite).
     */
    private void computeWind(boolean snap) {
        for (int j = 0; j < nz; j++) {
            double f = 2 * OMEGA * Math.sin(Math.toRadians(latitudeDeg(j)));
            double dpdy = hadleyGradient(j);
            for (int i = 0; i < nx; i++) {
                int k = j * nx + i;
                double kf = water[k] ? cfg.frictionSea : cfg.frictionLand;
                double den = RHO * (kf * kf + f * f);
                double un = -(f * dpdy) / den, vn = -(kf * dpdy) / den;

                int i0 = Math.max(i - 1, 0), i1 = Math.min(i + 1, nx - 1);
                int j0 = Math.max(j - 1, 0), j1 = Math.min(j + 1, nz - 1);
                double gx = (anom[j * nx + i1] - anom[j * nx + i0]) / ((i1 - i0) * dx);       // °C/m, leste
                double gy = -(anom[j1 * nx + i] - anom[j0 * nx + i]) / ((j1 - j0) * dx);      // °C/m, norte
                double gm = Math.hypot(gx, gy);
                if (gm > 0) {
                    double dT = gm * cfg.breezeLengthM;
                    double c = Math.min(cfg.maxBreeze, 0.6 * Math.sqrt(G * cfg.breezeDepthM * dT / (273.15 + temp[k])));
                    un += c * gx / gm;
                    vn += c * gy / gm;
                }
                double sp = Math.hypot(un, vn);
                if (sp > cfg.maxWind) { un *= cfg.maxWind / sp; vn *= cfg.maxWind / sp; }
                if (snap) { u[k] = un; v[k] = vn; }
                else { u[k] = 0.7 * u[k] + 0.3 * un; v[k] = 0.7 * v[k] + 0.3 * vn; } // inércia
            }
        }
    }

    // ---------- Movimento vertical ----------

    private void computeVertical() {
        for (int j = 0; j < nz; j++)
            for (int i = 0; i < nx; i++) {
                int k = j * nx + i;
                int i0 = Math.max(i - 1, 0), i1 = Math.min(i + 1, nx - 1);
                int j0 = Math.max(j - 1, 0), j1 = Math.min(j + 1, nz - 1);
                double dhdx = (elev[j * nx + i1] - elev[j * nx + i0]) / ((i1 - i0) * dx);
                double dhdy = -(elev[j1 * nx + i] - elev[j0 * nx + i]) / ((j1 - j0) * dx);
                wOro[k] = u[k] * dhdx + v[k] * dhdy;                 // vento subindo a encosta
                double dudx = (u[j * nx + i1] - u[j * nx + i0]) / ((i1 - i0) * dx);
                double dvdy = -(v[j1 * nx + i] - v[j0 * nx + i]) / ((j1 - j0) * dx);
                sys[k] = weatherSystem(cellCenterX(k), cellCenterZ(k));
                // Convergência -> ascensão, mais a subida (cavado) ou descida (crista) de grande escala.
                wDiv[k] = -(dudx + dvdy) * 1500.0 * liftGate(k) + (sys[k] - 0.5) * 2 * cfg.systemLift;
                double rh = clamp(e[k] / satPressure(temp[k]), 0, 1);
                // Instabilidade: a superfície precisa estar mais quente que a própria média do dia
                // (o ar acima dela) para vencer a "tampa" de ar estável. Assim a convecção é da
                // tarde, não do dia inteiro, e a crista (ar descendo) reforça a tampa.
                double cap = cfg.convectiveCapC + 5 * clamp((0.5 - sys[k]) * 2, 0, 1);
                double inst = clamp((temp[k] - dailyMeanTemp(k, j, curYr) - cap) / 5.0, 0, 1);
                // Sobre mar quente não há aquecimento diurno, mas o cavado organiza a convecção
                // (ondas de leste, aglomerados tropicais).
                if (water[k]) inst = Math.max(inst, 0.7 * clamp((sys[k] - 0.6) / 0.4, 0, 1) * clamp((temp[k] - 24) / 4, 0, 1));
                wTh[k] = inst * (0.4 + 0.6 * rh) * convectivePatch(k); // convecção precisa de calor E umidade
                w[k] = clamp(wOro[k] + wDiv[k] + wTh[k], -2, 2);
            }
    }

    // ---------- Umidade, condensação, precipitação ----------

    private void computeMoisture(double dt) {
        double kc = 1 - Math.exp(-dt / cfg.condTauH);
        for (int k = 0; k < nx * nz; k++) {
            double es = satPressure(temp[k]);
            if (water[k]) {
                e[k] += (0.85 * es - e[k]) * (1 - Math.exp(-dt / 6.0));
            } else {
                double target = cfg.landEvapFraction * wet[k] * es;
                if (e[k] < target) e[k] += (target - e[k]) * (1 - Math.exp(-dt / 24.0));
            }
            // Crista: o ar que desce da troposfera é seco e vai secando a camada de baixo.
            double sink = clamp((0.5 - sys[k]) * 2, 0, 1);
            if (sink > 0 && e[k] > cfg.ridgeDryRh * es) e[k] += (cfg.ridgeDryRh * es - e[k]) * (1 - Math.exp(-dt * sink / cfg.ridgeDryTauH));
            e[k] = Math.max(e[k], 0);

            // 1) Ascensão de grande escala (encostas, convergência, cavado): o ar resfria a
            //    9,8 °C/km, a pressão cai (o vapor dilui) e o que passa da saturação condensa.
            double oro = wOro[k] > 0 ? wOro[k] * liftGate(k) : wOro[k];   // a descida a sotavento seca sempre
            double dz = clamp((oro + wDiv[k]) * cfg.liftSeconds, -1500, 3000);
            double eL = e[k] * Math.exp(-dz / SCALE_HEIGHT);
            double esL = satPressure(temp[k] - LAPSE_DRY * dz / 1000.0);
            if (eL > esL) {
                double exc = (eL - esL) * kc;
                e[k] -= exc; cloud[k] += exc;
            }
            // 2) Convecção: as térmicas sobem até uma altura proporcional à instabilidade e só
            //    condensam o que passa do nível de condensação. A quantidade de ar que elas
            //    processam cresce com o quadrado da força: convecção fraca dá cúmulos de bom
            //    tempo, que não chovem; só a forte acumula água para virar cumulonimbo.
            if (wTh[k] > 0) {
                double dzc = Math.min(3000, wTh[k] * cfg.convLiftSeconds);
                double ec = e[k] * Math.exp(-dzc / SCALE_HEIGHT);
                double esc = satPressure(temp[k] - LAPSE_DRY * dzc / 1000.0);
                if (ec > esc) {
                    double exc = Math.min(0.5 * e[k], (ec - esc) * kc * wTh[k] * wTh[k]);
                    e[k] -= exc; cloud[k] += exc;
                }
            }
            // 3) Mistura com o ar em volta: a nuvem evapora pelas bordas, tanto mais depressa
            //    quanto mais seco o ar e quanto mais ele desce. É o que desfaz os cúmulos quando
            //    a térmica para e limpa o céu sob as cristas.
            double rhNow = Math.min(1, e[k] / es);
            double life = lerp(cfg.cloudLifeDryH, cfg.cloudLifeMoistH, rhNow * rhNow) / (1 + 3 * clamp(-dz / 400, 0, 1));
            double mix = cloud[k] * (1 - Math.exp(-dt / life));
            cloud[k] -= mix; e[k] += mix;
            // Saturação direta no solo (neblina de radiação/advecção): o excesso condensa
            // todo de uma vez, senão a umidade relativa ficaria acima de 100%.
            if (e[k] > es) {
                double exc = e[k] - es;
                e[k] -= exc; cloud[k] += exc;
            }
            // Precipitação
            // A chuva convectiva cai depressa (pancadas); a de camada, devagar e por mais tempo.
            double pr = Math.max(0, cloud[k] - cfg.precipThreshold) * (1 - Math.exp(-dt / (wTh[k] > 0.25 ? 0.5 : 2.0)));
            cloud[k] -= pr;
            precipRate[k] = pr * cfg.mmPerUnit / dt;
            precipTotal[k] += pr * cfg.mmPerUnit;
            // A chuva esfria o chão (evaporação e ar frio que desce com ela): é o que encerra a
            // trovoada depois de uma ou duas horas e deixa a tarde seguinte mais calma.
            if (!water[k] && pr > 0) {
                temp[k] -= Math.min(1.5, cfg.rainCoolingCPerMm * pr * cfg.mmPerUnit);
                double esCool = satPressure(temp[k]);                 // ar mais frio segura menos vapor
                if (e[k] > esCool) { cloud[k] += e[k] - esCool; e[k] = esCool; }
            }
        }
    }

    // ---------- Classificação de nuvens ----------

    private void classify() {
        int n = nx * nz;
        for (int k = 0; k < n; k++) type[k] = classifyCell(k);
        // Nimbostrato é uma camada extensa. Uma célula chuvosa sozinha, sem camada em volta, é
        // na verdade uma pancada isolada: vira um cúmulo carregado (congesto).
        boolean[] lone = new boolean[n];
        for (int j = 0; j < nz; j++)
            for (int i = 0; i < nx; i++) {
                int k = j * nx + i;
                if (type[k] != CloudType.NIMBOSTRATUS) continue;
                int layer = 0;
                for (int b = Math.max(0, j - 1); b <= Math.min(nz - 1, j + 1); b++)
                    for (int a = Math.max(0, i - 1); a <= Math.min(nx - 1, i + 1); a++) {
                        CloudType t = type[b * nx + a];
                        if ((a != i || b != j) && (t == CloudType.NIMBOSTRATUS || t == CloudType.STRATUS)) layer++;
                    }
                lone[k] = layer < 3;
            }
        for (int k = 0; k < n; k++) if (lone[k]) type[k] = CloudType.CUMULUS;
    }

    private CloudType classifyCell(int k) {
        double t = temp[k], es = satPressure(t), rh = e[k] / es;
        double base = cloudBase(t, dewPoint(e[k]));
        double c = cloud[k], spd = Math.hypot(u[k], v[k]);
        if (base < 60 && spd < 3 && (rh > 0.97 || c > 0.05)) return CloudType.FOG;
        // Convecção profunda: corrente ascendente com muita água condensada. Nos trópicos o
        // combustível é sobretudo a umidade, então não se exige um grande excesso de calor.
        if (c > 0.5 && wTh[k] > 0.25 && t > 15) return CloudType.CUMULONIMBUS;
        if (c > 0.03 && wTh[k] > 0.06) return CloudType.CUMULUS;             // convecção rasa
        if (c > 0.6) return CloudType.NIMBOSTRATUS;                            // ascensão lenta e ampla
        if (c > 0.08) return CloudType.STRATUS;
        // Cirros: a umidade em altitude chega horas antes do cavado (o sistema se aproxima).
        double x = cellCenterX(k), z = cellCenterZ(k);
        if (sys[k] > 0.3 && sys[k] < 0.7 && weatherSystem(x, z, curHours + 5) - sys[k] > 0.06) return CloudType.CIRRUS;
        return CloudType.CLEAR;
    }

    // ---------- Consulta ----------

    public ClimateSample sample(double worldBlockX, double worldBlockZ, double surfaceElevM) {
        int k = cellIndex(worldBlockX, worldBlockZ), j = k / nx;
        double tLocal = temp[k] + lapse(elev[k]) - lapse(Math.max(0, surfaceElevM));
        double p = pressureAtAltitude(pSea[k], Math.max(0, surfaceElevM));
        double es = satPressure(tLocal);
        // Acima da altitude média da célula o ar é mais frio e satura antes: o vapor que
        // excede a saturação já é nuvem, então o ponto de orvalho não passa da temperatura.
        double dew = dewPoint(Math.min(e[k], es));
        double spd = Math.hypot(u[k], v[k]);
        return new ClimateSample(tLocal, p, clamp(e[k] / es, 0, 1), u[k], v[k], spd,
                dew, cloudBase(tLocal, dew), cloud[k], type[k] == null ? CloudType.CLEAR : type[k],
                precipRate[k], tLocal < SNOW_BELOW_C, cont[k], latitudeDeg(j),
                isThunder(k));
    }

    /** Índice da célula que contém o bloco (x, z), limitado às bordas da janela. */
    public int cellIndex(double worldBlockX, double worldBlockZ) {
        int i = (int) clamp(Math.floor(worldBlockX / cfg.cellBlocks) - originCellX, 0, nx - 1);
        int j = (int) clamp(Math.floor(worldBlockZ / cfg.cellBlocks) - originCellZ, 0, nz - 1);
        return j * nx + i;
    }

    /** Verdadeiro se o bloco (x, z) está dentro da janela simulada. */
    public boolean contains(double worldBlockX, double worldBlockZ) {
        int i = (int) Math.floor(worldBlockX / cfg.cellBlocks) - originCellX;
        int j = (int) Math.floor(worldBlockZ / cfg.cellBlocks) - originCellZ;
        return i >= 0 && j >= 0 && i < nx && j < nz;
    }

    /** Trovoada: cumulonimbo maduro, já com chuva intensa. */
    public boolean isThunder(int k) {
        return type[k] == CloudType.CUMULONIMBUS && precipRate[k] > cfg.thunderMinMmH;
    }

    // ---------- Geometria das nuvens (para renderização) ----------

    /** Fração do céu coberta (0 a 1), a partir da água de nuvem. */
    public double coverage(int k) {
        CloudType t = type[k];
        if (t == null || t == CloudType.CLEAR) return 0;
        if (t == CloudType.CIRRUS) return 0.35;
        return clamp(cloud[k] / 0.6, 0.15, 1);
    }

    /** Altitude da base da nuvem, em metros acima do nível do mar. */
    public double cloudBaseMeters(int k) {
        CloudType t = type[k];
        if (t == null || t == CloudType.CLEAR) return 0;
        double lcl = cloudBase(temp[k], dewPoint(e[k]));                 // nível de condensação
        double base = switch (t) {
            case FOG -> 0;
            case CIRRUS -> 8000;
            case STRATUS -> clamp(lcl, 250, 1500);
            case NIMBOSTRATUS -> clamp(lcl, 400, 2000);
            case CUMULONIMBUS -> clamp(lcl, 1200, 2500);                  // base alta: a torre aparece acima do relevo
            default -> clamp(lcl, 600, 2500);                             // cúmulos
        };
        return elev[k] + base;
    }

    /** Altitude do topo da nuvem, em metros acima do nível do mar. */
    public double cloudTopMeters(int k) {
        CloudType t = type[k];
        if (t == null || t == CloudType.CLEAR) return 0;
        double base = cloudBaseMeters(k), c = coverage(k);
        return switch (t) {
            case FOG -> elev[k] + 80;
            case CIRRUS -> base + 400;
            case STRATUS -> base + 300 + 400 * c;
            case NIMBOSTRATUS -> base + 1200 + 800 * c;
            case CUMULUS -> base + 600 + 1800 * c;
            case CUMULONIMBUS -> Math.max(base + 6000, 11000);           // topo na tropopausa
            default -> base;
        };
    }

    // ---------- Persistência ----------

    /**
     * Campos que definem o estado e precisam ser salvos (temperatura, pressão, vapor,
     * nuvem, vento e chuva acumulada). Retorna as próprias matrizes da grade: quem
     * restaura um estado salvo escreve nelas e depois chama {@link #refreshDiagnostics}.
     */
    public double[][] persistentFields() {
        return new double[][] {temp, pSea, e, cloud, u, v, precipTotal};
    }

    /** Nomes estáveis dos campos de {@link #persistentFields()}, na mesma ordem. */
    public static final String[] PERSISTENT_NAMES = {"temp", "pSea", "e", "cloud", "u", "v", "precipTotal"};

    /** Recalcula o que é derivado do estado (movimento vertical, tipo de nuvem) após uma restauração. */
    public void refreshDiagnostics(double gameHours) {
        curYr = yearFraction(gameHours);
        curHours = gameHours;
        java.util.Arrays.fill(pTend, 0);
        java.util.Arrays.fill(precipRate, 0);
        computeVertical();
        classify();
    }
}
