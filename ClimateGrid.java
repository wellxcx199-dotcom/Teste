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
    public final CloudType[] type;

    private final double[] tmpA, tmpB;
    private double curYr;

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
        type = new CloudType[n];
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
        for (int k = 0; k < n; k++) if (water[k]) { dist[k] = 0; q.add(k); }
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
            double d = dist[k] == Integer.MAX_VALUE ? cfg.continentalityCells : dist[k];
            cont[k] = clamp(d / cfg.continentalityCells, 0, 1);
        }
    }

    // ---------- Inicialização ----------

    /** Coloca a grade num estado de equilíbrio plausível antes de começar. */
    public void initialize(double gameHours) {
        double tod = timeOfDay(gameHours), yr = yearFraction(gameHours);
        curYr = yr;
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

    private double[] remap(double[] f, int sx, int sz, boolean[] fresh) {
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
        for (double[] f : all) System.arraycopy(remap(f, sx, sz, fresh), 0, f, 0, n);
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
        curYr = yr;
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
        curYr = yr;
        updateTemperature(dtHours, tod, yr);
        advect(dtHours);
        computePressure(dtHours);
        computeWind(false);
        computeVertical();
        computeMoisture(dtHours);
        classify();
    }

    private double timeOfDay(double h) { return (((h % 24) + 24) % 24) / 24.0; }
    private double yearFraction(double h) { return (h / (24.0 * cfg.daysPerYear)) % 1.0; }

    // ---------- Temperatura ----------

    private double targetTemp(int k, int j, double tod, double yr) {
        double lat = latitudeDeg(j);
        double decl = 23.45 * Math.sin(2 * Math.PI * yr);              // declinação solar
        double seasonal = decl * (0.4 + 0.6 * cont[k]);                 // continente sente mais as estações
        double phi = Math.toRadians(lat - 0.6 * seasonal);
        double teq = 29.0 - 48.0 * Math.sin(phi) * Math.sin(phi);       // equilíbrio radiativo por latitude
        double diurnalAmp = lerp(1.0, 9.0, cont[k]);                    // amplitude diária: mar pequena, interior grande
        double diurnal = diurnalAmp * Math.sin(2 * Math.PI * (tod - 0.30));
        return teq + diurnal - lapse(elev[k]);
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
        for (int j = 0; j < nz; j++) {
            double lat = latitudeDeg(j);
            double mean = refTemp(lat);
            double hadley = -8.0 * Math.cos(Math.toRadians(6 * lat)); // baixas no equador e 60°, altas em 30° e polos
            for (int i = 0; i < nx; i++) {
                int k = j * nx + i;
                pSea[k] = 1013.25 + hadley - 0.8 * (t0[k] - mean);   // ar quente -> baixa pressão
            }
        }
        smooth(pSea); smooth(pSea);
        for (int k = 0; k < n; k++) pTend[k] = (pSea[k] - prev[k]) / dt;
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

    private void computeWind(boolean snap) {
        for (int j = 0; j < nz; j++) {
            double lat = latitudeDeg(j);
            double sinLat = Math.sin(Math.toRadians(lat));
            if (Math.abs(sinLat) < 0.25) sinLat = Math.copySign(0.25, sinLat);
            double f = 2 * OMEGA * sinLat;
            for (int i = 0; i < nx; i++) {
                int k = j * nx + i;
                int i0 = Math.max(i - 1, 0), i1 = Math.min(i + 1, nx - 1);
                int j0 = Math.max(j - 1, 0), j1 = Math.min(j + 1, nz - 1);
                double dpdx = (pSea[j * nx + i1] - pSea[j * nx + i0]) / ((i1 - i0) * dx) * 100.0; // Pa/m
                double dpdy = -(pSea[j1 * nx + i] - pSea[j0 * nx + i]) / ((j1 - j0) * dx) * 100.0; // norte
                double ug = -dpdy / (RHO * f), vg = dpdx / (RHO * f); // vento geostrófico
                double ang = Math.toRadians(water[k] ? 15 : 30) * Math.signum(sinLat); // atrito desvia p/ baixa pressão
                double fr = water[k] ? 0.8 : 0.6;
                double un = fr * (ug * Math.cos(ang) - vg * Math.sin(ang));
                double vn = fr * (ug * Math.sin(ang) + vg * Math.cos(ang));
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
                wDiv[k] = -(dudx + dvdy) * 1500.0;                    // convergência -> ascensão
                double rh = clamp(e[k] / satPressure(temp[k]), 0, 1);
                // Instabilidade: superfície mais quente que o ar de referência daquela latitude/altitude.
                double excess = temp[k] - (refTemp(latitudeDeg(j)) - lapse(elev[k])) - 1.0;
                wTh[k] = clamp(excess / 8.0, 0, 1) * rh;               // convecção precisa de calor E umidade
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
            e[k] = Math.max(e[k], 0);

            // Condensação por ascensão: resfria a 9,8 °C/km e a pressão cai (vapor dilui).
            double dz = clamp((wOro[k] + wDiv[k]) * cfg.liftSeconds + wTh[k] * cfg.convLiftSeconds, -1500, 3000);
            double eL = e[k] * Math.exp(-dz / SCALE_HEIGHT);
            double tL = temp[k] - LAPSE_DRY * dz / 1000.0;
            double esL = satPressure(tL);
            if (eL > esL) {
                double exc = (eL - esL) * kc;
                e[k] -= exc; cloud[k] += exc;
            } else {
                double ev = Math.min(cloud[k], (esL - eL) * 0.5 * kc);   // subsidência seca a nuvem
                cloud[k] -= ev; e[k] += ev;
            }
            // Saturação direta no solo (neblina de radiação/advecção)
            if (e[k] > es) {
                double exc = (e[k] - es) * kc;
                e[k] -= exc; cloud[k] += exc;
            }
            // Precipitação
            double pr = Math.max(0, cloud[k] - cfg.precipThreshold) * (1 - Math.exp(-dt / 2.0));
            cloud[k] -= pr;
            precipRate[k] = pr * cfg.mmPerUnit / dt;
            precipTotal[k] += pr * cfg.mmPerUnit;
            cloud[k] *= Math.exp(-dt / 48.0); // dissipação lenta
        }
    }

    // ---------- Classificação de nuvens ----------

    private void classify() {
        for (int k = 0; k < nx * nz; k++) type[k] = classifyCell(k);
    }

    private CloudType classifyCell(int k) {
        double t = temp[k], es = satPressure(t), rh = e[k] / es;
        double base = cloudBase(t, dewPoint(e[k]));
        double c = cloud[k], spd = Math.hypot(u[k], v[k]);
        if (base < 60 && spd < 3 && (rh > 0.97 || c > 0.05)) return CloudType.FOG;
        if (c > 0.4 && wTh[k] > 0.2 && t > 15) return CloudType.CUMULONIMBUS; // convecção profunda
        if (c > 0.05 && wTh[k] > 0.1) return CloudType.CUMULUS;              // convecção rasa
        if (c > 0.6) return CloudType.NIMBOSTRATUS;                            // ascensão lenta e ampla
        if (c > 0.08) return CloudType.STRATUS;
        if (pTend[k] < -0.25 && rh > 0.45) return CloudType.CIRRUS; // queda de pressão: sistema se aproxima
        return CloudType.CLEAR;
    }

    // ---------- Consulta ----------

    public ClimateSample sample(double worldBlockX, double worldBlockZ, double surfaceElevM) {
        int i = (int) clamp(Math.floor(worldBlockX / cfg.cellBlocks) - originCellX, 0, nx - 1);
        int j = (int) clamp(Math.floor(worldBlockZ / cfg.cellBlocks) - originCellZ, 0, nz - 1);
        int k = j * nx + i;
        double tLocal = temp[k] + lapse(elev[k]) - lapse(Math.max(0, surfaceElevM));
        double p = pressureAtAltitude(pSea[k], Math.max(0, surfaceElevM));
        double es = satPressure(tLocal);
        double dew = dewPoint(e[k]);
        double spd = Math.hypot(u[k], v[k]);
        return new ClimateSample(tLocal, p, clamp(e[k] / es, 0, 1), u[k], v[k], spd,
                dew, cloudBase(tLocal, dew), cloud[k], type[k] == null ? CloudType.CLEAR : type[k],
                precipRate[k], tLocal < 0.5, cont[k], latitudeDeg(j),
                type[k] == CloudType.CUMULONIMBUS && precipRate[k] > 0.3);
    }
}
