package br.climate.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static br.climate.core.Physics.*;

/**
 * Fenômenos com identidade própria, sobrepostos à grade climática.
 *
 * Furacão: nasce sobre oceano quente (≥ 26,5 °C) entre 5° e 25° de latitude, onde a força de
 * Coriolis já é suficiente para organizar a rotação. Intensifica sobre água quente em direção
 * a uma "intensidade potencial" que cresce com a temperatura do mar, enfraquece sobre terra
 * ou água fria e é levado pelos ventos de grande escala (alísios) mais uma deriva para
 * noroeste (deriva beta). A pressão segue o perfil de Holland (1980) e o vento é ciclônico,
 * com olho calmo e sem nuvens, parede do olho e bandas de chuva em espiral.
 *
 * Supercélula: tempestade com corrente ascendente em rotação (mesociclone). Nasce de
 * cumulonimbos fortes sobre terra quando há cisalhamento do vento, dura algumas horas e se
 * desloca à direita do vento médio no hemisfério norte ("right mover").
 *
 * Tornado: nasce de supercélulas maduras com base de nuvem baixa (ar úmido junto ao solo),
 * acompanha a supercélula e dura menos de uma hora de jogo.
 */
public final class StormSystem {
    public final List<Storm> storms = new ArrayList<>();
    private int nextId = 1;
    private Random rnd;

    public StormSystem(long seed) { rnd = new Random(seed); }

    StormSystem copy(long seed) {
        StormSystem s = new StormSystem(seed);
        for (Storm st : storms) s.storms.add(st.copy());
        s.nextId = nextId;
        return s;
    }

    public Storm find(int id) {
        for (Storm s : storms) if (s.id == id) return s;
        return null;
    }

    // ---------- Criação (natural ou por comando) ----------

    public Storm spawnHurricane(double x, double z, double vmax) {
        Storm s = new Storm(nextId++, Storm.Kind.HURRICANE, x, z, vmax, hurricaneRmax(vmax), Double.POSITIVE_INFINITY);
        storms.add(s);
        return s;
    }

    public Storm spawnSupercell(double x, double z, double lifeH) {
        Storm s = new Storm(nextId++, Storm.Kind.SUPERCELL, x, z, 25, 12000, lifeH);
        storms.add(s);
        return s;
    }

    /** Tornado na retaguarda direita da supercélula, onde ele costuma se formar. */
    public Storm spawnTornado(Storm parent, int ef, double lifeH, double cellBlocks) {
        Storm t = makeTornado(parent, ef, lifeH, cellBlocks);
        storms.add(t);
        return t;
    }

    private Storm makeTornado(Storm parent, int ef, double lifeH, double cellBlocks) {
        double[] vmaxByEf = {33, 44, 55, 67, 81, 95};
        ef = Math.max(0, Math.min(5, ef));
        Storm t = new Storm(nextId++, Storm.Kind.TORNADO, parent.x, parent.z, vmaxByEf[ef], 60 + 70 * ef, lifeH);
        t.parent = parent.id;
        t.offX = -0.4 * cellBlocks;
        t.offZ = 0.5 * cellBlocks;
        t.x = parent.x + t.offX;
        t.z = parent.z + t.offZ;
        return t;
    }

    private static double hurricaneRmax(double vmax) {
        return clamp(60000 - 600 * (vmax - 18), 18000, 60000);   // furacões fortes têm olho menor
    }

    /** Queda de pressão central (hPa) pelo perfil de Holland: Δp = ρ e V² / B. */
    private static double hurricaneDeficit(double vmax) {
        return RHO * Math.E * vmax * vmax / HOLLAND_B / 100.0;
    }

    private static final double HOLLAND_B = 1.6;

    // ---------- Efeitos sobre a grade ----------

    /** Baixas de pressão dos furacões e mesociclones. */
    void imposePressure(ClimateGrid g) {
        for (Storm s : storms) {
            if (s.kind == Storm.Kind.TORNADO) continue;
            double reach = s.kind == Storm.Kind.HURRICANE ? 10 * s.radiusM : 2.5 * s.radiusM;
            forCells(g, s, reach, (k, rM, dxE, dyN) -> {
                if (s.kind == Storm.Kind.HURRICANE) {
                    double a = Math.pow(s.radiusM / Math.max(rM, 1), HOLLAND_B);
                    g.pSea[k] -= hurricaneDeficit(s.vmax) * (1 - Math.exp(-a));
                } else {
                    g.pSea[k] -= 4 * s.intensity() * Math.exp(-Math.pow(rM / s.radiusM, 2));
                }
            });
        }
    }

    /** Vento ciclônico do furacão (perfil de Holland), somado ao seu deslocamento. */
    void imposeWind(ClimateGrid g) {
        for (Storm s : storms) {
            if (s.kind != Storm.Kind.HURRICANE) continue;
            double hemi = Math.signum(g.latitudeAtZ(s.z));
            if (hemi == 0) hemi = 1;
            double tu = s.vx * g.cfg.metersPerBlockH() / (3600 * g.cfg.stormSpeedFactor);   // deslocamento em m/s
            double tv = -s.vz * g.cfg.metersPerBlockH() / (3600 * g.cfg.stormSpeedFactor);
            double h = hemi, inflow = Math.toRadians(20);
            forCells(g, s, 8 * s.radiusM, (k, rM, dxE, dyN) -> {
                double r = Math.max(rM, 1);
                double a = Math.pow(s.radiusM / r, HOLLAND_B);
                double v = s.vmax * Math.sqrt(a * Math.exp(1 - a));
                double rx = dxE / r, ry = dyN / r;                 // unitário para fora do centro
                double txv = -ry * h, tyv = rx * h;                // tangente: anti-horário no hemisfério norte
                double un = v * (Math.cos(inflow) * txv - Math.sin(inflow) * rx) + 0.5 * tu;
                double vn = v * (Math.cos(inflow) * tyv - Math.sin(inflow) * ry) + 0.5 * tv;
                double w = Math.exp(-Math.pow(rM / (5 * s.radiusM), 2));
                g.u[k] = (1 - w) * g.u[k] + w * un;
                g.v[k] = (1 - w) * g.v[k] + w * vn;
            });
        }
    }

    /** Nuvens e chuva: olho, parede do olho e bandas espirais; núcleo das supercélulas. */
    void imposeMoisture(ClimateGrid g, double dt) {
        for (Storm s : storms) {
            if (s.kind == Storm.Kind.HURRICANE) {
                double hemi = Math.signum(g.latitudeAtZ(s.z));
                double cotPitch = 1 / Math.tan(Math.toRadians(16));   // bandas abertas ~16°
                double strong = s.intensity();
                forCells(g, s, 7 * s.radiusM, (k, rM, dxE, dyN) -> {
                    double x = rM / s.radiusM;
                    double cloud = 0, rain = 0, conv = 0;
                    if (x < 0.45) {                                     // olho: ar descendente, céu limpo
                        g.cloud[k] = 0; g.precipRate[k] = 0; g.wTh[k] = 0;
                        return;
                    } else if (x < 1.6) {                               // parede do olho
                        double ring = Math.exp(-Math.pow((x - 1.0) / 0.35, 2));
                        cloud = (1.2 + strong) * ring; rain = (6 + 0.3 * s.vmax) * ring; conv = 0.5 * ring;
                    }
                    if (x >= 1.2) {                                     // bandas espirais
                        double theta = Math.atan2(dyN, dxE) * (hemi < 0 ? -1 : 1);
                        double arm = Math.cos(2 * (theta - Math.log(x) * cotPitch));
                        double band = clamp((arm - 0.35) / 0.65, 0, 1) * clamp(1.15 - x / 7, 0, 1);
                        cloud = Math.max(cloud, (0.7 + 0.6 * strong) * band);
                        rain = Math.max(rain, (2 + 6 * strong) * band);
                        conv = Math.max(conv, 0.3 * band);
                    }
                    saturateRain(g, k, cloud, rain, conv, dt);
                });
            } else if (s.kind == Storm.Kind.SUPERCELL) {
                double i = s.intensity();
                forCells(g, s, 1.8 * s.radiusM, (k, rM, dxE, dyN) -> {
                    double w = Math.exp(-Math.pow(rM / s.radiusM, 2));
                    saturateRain(g, k, (1.0 + i) * w, (3 + 12 * i) * w, 0.35 * w, dt);
                });
            }
        }
    }

    private static void saturateRain(ClimateGrid g, int k, double cloud, double rain, double conv, double dt) {
        if (cloud <= 0) return;
        g.cloud[k] = Math.max(g.cloud[k], cloud);
        g.wTh[k] = Math.max(g.wTh[k], conv);
        g.e[k] = Math.max(g.e[k], satPressure(g.temp[k]));
        if (rain > g.precipRate[k]) {
            g.precipTotal[k] += (rain - g.precipRate[k]) * dt;
            g.precipRate[k] = rain;
        }
    }

    // ---------- Evolução ----------

    void evolve(ClimateGrid g, double dt) {
        ClimateConfig c = g.cfg;
        double toBlocks = 3600 * c.stormSpeedFactor / c.metersPerBlockH();   // m/s -> blocos por hora
        List<Storm> dead = new ArrayList<>(), born = new ArrayList<>();
        for (Storm s : storms) {
            s.ageH += dt;
            switch (s.kind) {
                case HURRICANE -> {
                    int k = g.containsBlock(s.x, s.z) ? g.cellIndex(s.x, s.z) : -1;
                    boolean water = k >= 0 && g.water[k];
                    double sst = k >= 0 ? g.temp[k] : 0;
                    if (k < 0) {
                        // Fora da área simulada não sabemos o que há embaixo: mantém a intensidade.
                    } else if (water && sst >= 26) {
                        // Intensidade potencial: cresce ~16 m/s por grau acima de 26 °C (mar a 28 °C ≈ 52 m/s).
                        double vpot = clamp(20 + 16 * (sst - 26), 20, 85);
                        s.vmax += (vpot - s.vmax) * (1 - Math.exp(-dt / 24));
                    } else if (water) {
                        s.vmax *= Math.exp(-dt / 30);
                    } else {
                        s.vmax *= Math.exp(-dt / 14);                       // sobre terra perde o "combustível"
                    }
                    s.radiusM = hurricaneRmax(s.vmax);
                    double[] st = g.steeringWind(s.z);
                    double hemi = Math.signum(g.latitudeAtZ(s.z));
                    double u = st[0] - 1.4, v = st[1] + 1.4 * (hemi == 0 ? 1 : hemi); // deriva beta para o polo/oeste
                    move(s, u * toBlocks, -v * toBlocks, dt);
                    if (s.vmax < 15 || !g.nearWindow(s.x, s.z)) dead.add(s);
                }
                case SUPERCELL -> {
                    s.vmax = 20 + 30 * s.intensity();
                    double[] st = g.steeringWind(s.z);
                    double hemi = Math.signum(g.latitudeAtZ(s.z));
                    double ang = Math.toRadians(-30) * (hemi == 0 ? 1 : hemi);  // desvio à direita no hemisfério norte
                    double u = st[0], v = st[1];
                    double sp = Math.hypot(u, v);
                    if (sp < 6) { u = sp > 0.5 ? u / sp * 6 : 6; v = sp > 0.5 ? v / sp * 6 : 0; }
                    double ur = 0.75 * (u * Math.cos(ang) - v * Math.sin(ang));
                    double vr = 0.75 * (u * Math.sin(ang) + v * Math.cos(ang));
                    move(s, ur * toBlocks, -vr * toBlocks, dt);
                    if (s.ageH >= s.lifeH || !g.nearWindow(s.x, s.z)) dead.add(s);
                    else maybeTornado(g, s, dt, born);
                }
                case TORNADO -> {
                    Storm p = find(s.parent);
                    if (p == null || p.kind != Storm.Kind.SUPERCELL) { s.lifeH = Math.min(s.lifeH, s.ageH + 0.1); }
                    else {
                        // Gira devagar em torno da posição de origem dentro da supercélula.
                        double a = 0.3 * dt;
                        double ox = s.offX * Math.cos(a) - s.offZ * Math.sin(a), oz = s.offX * Math.sin(a) + s.offZ * Math.cos(a);
                        s.offX = ox; s.offZ = oz;
                        double nx = p.x + s.offX, nz = p.z + s.offZ;
                        s.vx = (nx - s.x) / dt; s.vz = (nz - s.z) / dt;
                        s.x = nx; s.z = nz;
                    }
                    if (s.ageH >= s.lifeH) dead.add(s);
                }
            }
        }
        storms.removeAll(dead);
        storms.addAll(born);       // criados depois do laço: não se altera a lista enquanto ela é percorrida
        genesis(g, dt);
    }

    private static void move(Storm s, double vxBlocksH, double vzBlocksH, double dt) {
        s.vx = vxBlocksH; s.vz = vzBlocksH;
        s.x += vxBlocksH * dt; s.z += vzBlocksH * dt;
    }

    private void maybeTornado(ClimateGrid g, Storm sc, double dt, List<Storm> born) {
        if (sc.intensity() < 0.55 || !g.containsBlock(sc.x, sc.z)) return;
        for (Storm t : storms) if (t.kind == Storm.Kind.TORNADO && t.parent == sc.id) return;
        int k = g.cellIndex(sc.x, sc.z);
        double base = cloudBase(g.temp[k], dewPoint(g.e[k]));
        if (base > 1200) return;                                   // base alta: ar seco embaixo, sem tornado
        if (rnd.nextDouble() >= g.cfg.tornadoPerHour * dt) return;
        double r = rnd.nextDouble();                               // distribuição real: maioria fraca
        int ef = r < 0.35 ? 0 : r < 0.65 ? 1 : r < 0.85 ? 2 : r < 0.95 ? 3 : r < 0.99 ? 4 : 5;
        born.add(makeTornado(sc, ef, 0.4 + 0.8 * rnd.nextDouble(), g.cfg.cellBlocks));
    }

    private void genesis(ClimateGrid g, double dt) {
        ClimateConfig c = g.cfg;
        int n = g.nx * g.nz, hurricanes = 0, supercells = 0;
        for (Storm s : storms) {
            if (s.kind == Storm.Kind.HURRICANE) hurricanes++;
            if (s.kind == Storm.Kind.SUPERCELL) supercells++;
        }
        // Furacão: precisa de bastante oceano quente na região.
        if (hurricanes < c.maxHurricanes) {
            List<Integer> warm = new ArrayList<>();
            for (int k = 0; k < n; k++) {
                double lat = Math.abs(g.latitudeDeg(k / g.nx));
                if (g.water[k] && g.temp[k] >= 26.5 && lat >= 5 && lat <= 25
                        && g.e[k] / satPressure(g.temp[k]) > 0.7) warm.add(k);
            }
            double frac = warm.size() / (double) n;
            if (frac > 0.15 && rnd.nextDouble() < c.hurricanesPerDay * dt / 24 * Math.min(1, frac / 0.4)) {
                int k = warm.get(rnd.nextInt(warm.size()));
                spawnHurricane(g.cellCenterX(k), g.cellCenterZ(k), 18);
            }
        }
        // Supercélula: cumulonimbo forte, sobre terra, com cisalhamento (vento de grande escala).
        if (supercells < c.maxSupercells) {
            List<Integer> cand = new ArrayList<>();
            for (int k = 0; k < n; k++) {
                if (g.type[k] != CloudType.CUMULONIMBUS || g.water[k] || g.precipRate[k] < 1.5 || g.wTh[k] < 0.15) continue;
                double[] st = g.steeringWind(g.cellCenterZ(k));
                double lat = Math.abs(g.latitudeDeg(k / g.nx));
                if (Math.hypot(st[0], st[1]) < 3 && lat < 20) continue;
                cand.add(k);
            }
            if (!cand.isEmpty() && rnd.nextDouble() < c.supercellsPerHour * dt) {
                int k = cand.get(rnd.nextInt(cand.size()));
                double x = g.cellCenterX(k), z = g.cellCenterZ(k);
                boolean tooClose = false;
                for (Storm s : storms)
                    if (s.kind == Storm.Kind.SUPERCELL && Math.hypot(s.x - x, s.z - z) < 5 * c.cellBlocks) tooClose = true;
                if (!tooClose) spawnSupercell(x, z, 3 + 3 * rnd.nextDouble());
            }
        }
    }

    // ---------- Utilidades ----------

    private interface CellFn { void apply(int k, double rM, double dxEastM, double dyNorthM); }

    /** Percorre as células a menos de {@code reachM} metros do fenômeno. */
    private static void forCells(ClimateGrid g, Storm s, double reachM, CellFn fn) {
        double mpb = g.cfg.metersPerBlockH();
        double reachBlocks = reachM / mpb;
        int cb = g.cfg.cellBlocks;
        int i0 = (int) Math.floor((s.x - reachBlocks) / cb) - g.originCellX, i1 = (int) Math.floor((s.x + reachBlocks) / cb) - g.originCellX;
        int j0 = (int) Math.floor((s.z - reachBlocks) / cb) - g.originCellZ, j1 = (int) Math.floor((s.z + reachBlocks) / cb) - g.originCellZ;
        for (int j = Math.max(0, j0); j <= Math.min(g.nz - 1, j1); j++)
            for (int i = Math.max(0, i0); i <= Math.min(g.nx - 1, i1); i++) {
                int k = j * g.nx + i;
                double dxE = (g.cellCenterX(k) - s.x) * mpb, dyN = -(g.cellCenterZ(k) - s.z) * mpb;
                double r = Math.hypot(dxE, dyN);
                if (r <= reachM) fn.apply(k, r, dxE, dyN);
            }
    }

    public List<double[]> pack() {
        List<double[]> out = new ArrayList<>();
        for (Storm s : storms) out.add(s.pack());
        return out;
    }

    public void unpack(List<double[]> data) {
        storms.clear();
        for (double[] d : data) {
            Storm s = Storm.unpack(d);
            storms.add(s);
            nextId = Math.max(nextId, s.id + 1);
        }
    }
}
