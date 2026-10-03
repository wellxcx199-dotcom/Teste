package br.climate.client;

import br.climate.core.CloudType;
import br.climate.mod.ClimatePayload;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Nuvens animadas no cliente. O servidor manda o estado das nuvens a cada passo da simulação
 * (10 s); em vez de trocar tudo de uma vez, cada célula evolui até esse alvo com o ritmo da
 * física de cada tipo:
 *
 * - convectivas (cúmulo → cúmulo congesto → cumulonimbo) crescem para cima a ~1,2 bloco/s;
 *   a bigorna só se espalha quando a torre chega perto do topo e, na dissipação, a torre
 *   desmorona antes e a bigorna fica flutuando mais um tempo ("bigorna órfã");
 * - camadas (estratos, nimbostratos) e cirros surgem e somem por transparência, engrossando
 *   ou afinando aos poucos;
 * - se o tipo muda de família (por exemplo, estrato vira cúmulo), a nuvem antiga se desfaz
 *   antes de a nova começar a se formar;
 * - nuvens convectivas novas "brotam" das vizinhas: uma célula que deve virar cúmulo espera
 *   até uma vizinha ter uma torre de verdade e então nasce colada no flanco dela (como na
 *   linha de instabilidade, em que a frente de rajada de cada célula dispara a próxima). Só
 *   células isoladas nascem sozinhas, depois de uma espera curta.
 *
 * O deslocamento com o vento é contínuo: quando chega um pacote novo (com as nuvens já
 * transportadas pelo servidor), o deslocamento acumulado não volta a zero de repente, ele é
 * "devolvido" aos poucos enquanto as células se ajustam ao novo estado.
 */
final class CloudField {
    private CloudField() {}

    /** Velocidade de crescimento vertical das nuvens convectivas e de desmoronamento (blocos/tick). */
    private static final float GROW = 0.06f, COLLAPSE = 0.03f;
    /** Ticks para surgir (opacidade 0→1) por família e para se desfazer: camadas se condensam devagar. */
    private static final float FADE_CONVECTIVE = 160, FADE_LAYER = 400, FADE_CIRRUS = 300, FADE_OUT = 200;
    /** Ticks para a bigorna se espalhar e para sumir depois que a torre morre. */
    private static final float ANVIL_IN = 400, ANVIL_OUT = 900;
    /** Ticks em que o deslocamento antigo é "devolvido" após um pacote novo. */
    private static final float CARRY = 160;

    /** Altura (blocos) que a torre vizinha precisa ter para disparar uma célula nova; menor a sotavento. */
    private static final float SPARK = 13, SPARK_DOWNWIND = 8;

    private static final class Cell {
        final int cx, cz;
        CloudType shown = CloudType.CLEAR, target = CloudType.CLEAR;
        float cover, targetCover, base, targetBase, height, targetHeight;
        float opacity, anvil, anvilTop, boil;
        boolean inPatch;
        /** Ticks até a célula nascer sozinha (-1: não está esperando). */
        int wait = -1;
        /** Tick em que a torre começou a crescer: a mais velha de um aglomerado é a principal. */
        long born;
        /** Direção da vizinha de onde a célula brotou (-1, 0 ou 1 em x e z). */
        int fromX, fromZ;

        Cell(int cx, int cz) { this.cx = cx; this.cz = cz; }
    }

    private static final Map<Long, Cell> cells = new HashMap<>();
    private static ClimatePayload target;
    private static long sinceTarget, time;
    private static double carryX, carryZ;
    private static boolean animating;

    /** Alvo agendado (a vitrine espera o céu limpar) e ticks que faltam para aplicá-lo. */
    private static ClimatePayload later;
    private static int laterIn;

    static void setTargetLater(ClimatePayload p, int ticks) { later = p; laterIn = ticks; }

    static void clear() {
        cells.clear();
        target = null;
        later = null;
        carryX = carryZ = 0;
    }

    static boolean hasTarget() { return target != null; }

    /** Há nuvens mudando visivelmente (o renderizador refaz a malha com mais frequência). */
    static boolean animating() { return animating; }

    private static long key(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }

    private enum Family { NONE, CONVECTIVE, LAYER, CIRRUS }

    private static Family family(CloudType t) {
        return switch (t) {
            case CUMULUS, CUMULONIMBUS -> Family.CONVECTIVE;
            case STRATUS, NIMBOSTRATUS -> Family.LAYER;
            case CIRRUS -> Family.CIRRUS;
            default -> Family.NONE;                         // céu limpo e neblina (a neblina não é desenhada)
        };
    }

    /** Novo alvo (pacote do servidor ou vitrine). */
    static void setTarget(ClimatePayload p) {
        later = null;
        if (p == null) return;
        boolean instant = cells.isEmpty();
        if (target != null) {
            double[] off = offset(0);
            carryX = off[0];
            carryZ = off[1];
            // Teleporte ou mudança grande de lugar: as células novas já aparecem prontas.
            int moved = Math.max(Math.abs(p.originX() - target.originX()), Math.abs(p.originZ() - target.originZ())) / p.cellBlocks();
            if (moved > p.size() / 3) { instant = true; carryX = carryZ = 0; }
        }
        target = p;
        sinceTarget = 0;
        for (Cell c : cells.values()) { c.inPatch = false; c.target = CloudType.CLEAR; c.targetCover = 0; }
        int cx0 = Math.floorDiv(p.originX(), p.cellBlocks()), cz0 = Math.floorDiv(p.originZ(), p.cellBlocks());
        CloudType[] types = CloudType.values();
        for (int j = 0; j < p.size(); j++)
            for (int i = 0; i < p.size(); i++) {
                int idx = j * p.size() + i;
                CloudType t = types[p.types()[idx]];
                if (t == CloudType.FOG) t = CloudType.CLEAR;
                long k = key(cx0 + i, cz0 + j);
                Cell c = cells.get(k);
                if (c == null) {
                    if (t == CloudType.CLEAR) continue;           // nada a animar
                    c = new Cell(cx0 + i, cz0 + j);
                    cells.put(k, c);
                    if (instant) { c.shown = t; c.opacity = 1; c.anvil = t == CloudType.CUMULONIMBUS ? 1 : 0; c.born = time; }
                }
                if (family(t) != Family.CONVECTIVE) c.wait = -1;
                c.inPatch = true;
                c.target = t;
                c.targetCover = (p.cover()[idx] & 0xFF) / 255f;
                c.targetBase = p.baseY()[idx];
                c.targetHeight = Math.max(1, p.topY()[idx] - p.baseY()[idx]);
                if (c.shown == t && instant) {
                    c.cover = c.targetCover; c.base = c.targetBase; c.height = c.targetHeight;
                    c.anvilTop = c.base + c.height;
                }
            }
        if (!instant) scheduleSprouts();
    }

    /**
     * Células que vão virar cúmulo ganham um prazo para nascer sozinhas. Quem tem vizinhas
     * convectivas dos dois lados espera bastante, porque o normal é ser disparada por elas antes
     * disso; as pontas de uma linha (uma vizinha só) esperam pouco e são as primeiras sementes.
     */
    private static void scheduleSprouts() {
        for (Cell c : cells.values()) {
            if (c.wait >= 0 || family(c.target) != Family.CONVECTIVE || family(c.shown) == Family.CONVECTIVE) continue;
            int n = 0;
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) continue;
                    Cell o = cells.get(key(c.cx + dx, c.cz + dz));
                    if (o != null && family(o.target) == Family.CONVECTIVE) n++;
                }
            double r = CloudShapes.rnd(c.cx, c.cz, (int) (time / 200));
            c.wait = (int) (n == 0 ? 40 + 160 * r : n == 1 ? 60 + 140 * r : 400 + 800 * r);
        }
    }

    /** Vizinha com torre alta o bastante para disparar a célula, ou null. */
    private static Cell parent(Cell c) {
        Cell best = null;
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) continue;
                Cell o = cells.get(key(c.cx + dx, c.cz + dz));
                if (o == null || family(o.shown) != Family.CONVECTIVE || o.opacity < 0.4f) continue;
                // A frente de rajada avança com o vento: a sotavento a célula nasce mais cedo.
                boolean downwind = -dx * target.driftX() - dz * target.driftZ() > 0;
                if (o.height >= (downwind ? SPARK_DOWNWIND : SPARK) && (best == null || o.height > best.height)) best = o;
            }
        return best;
    }

    /** Avança a animação um tick. */
    static void tick() {
        if (later != null && --laterIn <= 0) setTarget(later);
        time++;
        sinceTarget++;
        boolean moving = false;
        for (Iterator<Cell> it = cells.values().iterator(); it.hasNext(); ) {
            Cell c = it.next();
            moving |= animate(c);
            if (c.shown == CloudType.CLEAR && c.target == CloudType.CLEAR) it.remove();
        }
        animating = moving;
    }

    /** Retorna true se a célula mudou de forma visível neste tick. */
    private static boolean animate(Cell c) {
        Family have = family(c.shown), want = family(c.target);
        float before = c.opacity + c.height * 0.05f + c.anvil;
        if (have != want && have != Family.NONE && c.opacity > 0.03f) {
            // Família diferente: primeiro a nuvem atual se desfaz. No cumulonimbo a torre
            // desaba antes e a bigorna, já sem alimentação, fica flutuando e se desfaz devagar.
            boolean orphan = have == Family.CONVECTIVE && c.anvil > 0.2f;
            c.opacity = Math.max(0, c.opacity - 1 / (orphan ? FADE_OUT * 3 : FADE_OUT));
            if (have == Family.CONVECTIVE) c.height = Math.max(1, c.height - 2 * COLLAPSE);
            c.anvil = Math.max(0, c.anvil - 1 / ANVIL_IN);
            c.boil = approach(c.boil, 0, 0.01f);
        } else if (have != want) {
            // Convectivas brotam de uma vizinha já desenvolvida (ou nascem sozinhas no fim da espera).
            Cell from = null;
            if (want == Family.CONVECTIVE && c.wait >= 0) {
                from = parent(c);
                if (from == null && --c.wait > 0) return false;
                c.wait = -1;
            }
            // Começa a nova nuvem do zero: convectivas nascem como um cúmulo achatado.
            c.shown = c.target;
            c.opacity = 0;
            c.cover = c.targetCover;
            c.base = c.targetBase;
            c.height = want == Family.CONVECTIVE ? 3 : Math.max(1, c.targetHeight * 0.5f);
            c.anvil = 0;
            c.anvilTop = c.base + c.height;
            c.born = time;
            c.fromX = c.fromZ = 0;
            if (from != null) {
                // Nasce no nível de condensação da vizinha, pequena e colada no flanco dela.
                c.base = from.base;
                c.cover = Math.min(c.targetCover, 0.3f);
                c.fromX = Integer.signum(from.cx - c.cx);
                c.fromZ = Integer.signum(from.cz - c.cz);
            }
            if (want == Family.NONE) c.shown = CloudType.CLEAR;
        } else if (want != Family.NONE) {
            float fadeIn = want == Family.CONVECTIVE ? FADE_CONVECTIVE : want == Family.LAYER ? FADE_LAYER : FADE_CIRRUS;
            c.opacity = Math.min(1, c.opacity + 1 / fadeIn);
            c.cover += (c.targetCover - c.cover) * 0.008f;
            c.base += (c.targetBase - c.base) * 0.01f;
            switch (want) {
                case CONVECTIVE -> convective(c);
                case LAYER -> { c.shown = c.target; c.height += (c.targetHeight - c.height) * 0.007f; }
                default -> c.height = c.targetHeight;
            }
        }
        float after = c.opacity + c.height * 0.05f + c.anvil;
        return Math.abs(after - before) > 1e-4f;
    }

    /** Ciclo de vida das nuvens de desenvolvimento vertical. */
    private static void convective(Cell c) {
        boolean wantCb = c.target == CloudType.CUMULONIMBUS;
        float goal = c.targetHeight;
        boolean growing = c.height < goal - 0.5f;
        if (growing) c.height = Math.min(goal, c.height + GROW);
        else if (c.height > goal) c.height = Math.max(goal, c.height - COLLAPSE);
        c.boil = approach(c.boil, growing ? 1 : 0.25f, 0.01f);

        if (wantCb && c.height >= 0.85f * goal) {
            // Torre madura: o topo bate na "tampa" (tropopausa) e a bigorna se espalha.
            c.anvil = Math.min(1, c.anvil + 1 / ANVIL_IN);
            c.anvilTop = Math.max(c.anvilTop, c.base + c.height);
        } else if (!wantCb) {
            c.anvil = Math.max(0, c.anvil - 1 / ANVIL_OUT);         // bigorna órfã some devagar
        }
        if (c.anvilTop < c.base + 4) c.anvilTop = c.base + c.height;
        // O que se vê: cúmulo enquanto a torre é baixa; cumulonimbo quando ela fica alta
        // ou enquanto ainda resta bigorna.
        boolean tall = c.height >= 40;
        c.shown = (wantCb && tall) || c.anvil > 0.05f ? CloudType.CUMULONIMBUS : CloudType.CUMULUS;
    }

    private static float approach(float v, float t, float step) {
        return v < t ? Math.min(t, v + step) : Math.max(t, v - step);
    }

    /** Deslocamento atual das nuvens pelo vento (blocos), contínuo entre pacotes. */
    static double[] offset(float pt) {
        if (target == null) return new double[] {0, 0};
        double t = Math.min(sinceTarget, 20 * 60) + pt;
        double f = Math.max(0, 1 - t / CARRY);
        return new double[] {target.driftX() * t + carryX * f, target.driftZ() * t + carryZ * f};
    }

    /** Fotografia do estado atual, no formato que o CloudShapes desenha. */
    static CloudInput snapshot() {
        ClimatePayload p = target;
        if (p == null) return null;
        int size = p.size(), n = size * size;
        byte[] types = new byte[n], cover = new byte[n];
        short[] base = new short[n], top = new short[n], anvilTop = new short[n];
        float[] opacity = new float[n], anvil = new float[n], boil = new float[n], grow = new float[n];
        int[] born = new int[n];
        byte[] from = new byte[n];
        int cx0 = Math.floorDiv(p.originX(), p.cellBlocks()), cz0 = Math.floorDiv(p.originZ(), p.cellBlocks());
        for (int j = 0; j < size; j++)
            for (int i = 0; i < size; i++) {
                int idx = j * size + i;
                Cell c = cells.get(key(cx0 + i, cz0 + j));
                if (c == null || c.shown == CloudType.CLEAR || c.opacity <= 0.01f) continue;
                types[idx] = (byte) c.shown.ordinal();
                cover[idx] = (byte) Math.round(Math.max(0, Math.min(1, c.cover)) * 255);
                base[idx] = (short) Math.round(c.base);
                top[idx] = (short) Math.round(c.base + Math.max(1, c.height));
                anvilTop[idx] = (short) Math.round(c.anvilTop);
                opacity[idx] = smooth(c.opacity);
                anvil[idx] = c.anvil;
                boil[idx] = c.boil;
                born[idx] = (int) c.born + 1;
                from[idx] = (byte) ((c.fromX + 1) * 3 + c.fromZ + 1);
                grow[idx] = Math.min(1, c.height / Math.max(1, c.targetHeight));
            }
        return new CloudInput(p.originX(), p.originZ(), p.cellBlocks(), size, types, cover, base, top,
                opacity, anvil, anvilTop, boil, born, from, grow, p.storms(), p.driftX(), p.driftZ(), p.refY(), time);
    }

    private static float smooth(float x) { return x * x * (3 - 2 * x); }
}
