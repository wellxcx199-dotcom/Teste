package br.climate.core;

/**
 * Um fenômeno meteorológico com identidade própria, que nasce, se desloca, muda de
 * intensidade e morre: furacão, supercélula ou tornado. Posições em blocos do mundo.
 */
public final class Storm {
    public enum Kind {
        HURRICANE("Furacão"), SUPERCELL("Supercélula"), TORNADO("Tornado");
        public final String label;
        Kind(String label) { this.label = label; }
    }

    public final int id;
    public final Kind kind;
    /** Posição (blocos). */
    public double x, z;
    /** Vento máximo sustentado (m/s). */
    public double vmax;
    /** Raio característico (m): raio de vento máximo do furacão, núcleo da supercélula, largura do tornado. */
    public double radiusM;
    /** Idade e duração prevista (horas de jogo); duração infinita para furacões, que morrem por enfraquecimento. */
    public double ageH, lifeH;
    /** Tornado: supercélula de origem e deslocamento em relação a ela (blocos). */
    public int parent = -1;
    public double offX, offZ;
    /** Último deslocamento (blocos por hora de jogo), para estimar a posição entre atualizações. */
    public double vx, vz;

    public Storm(int id, Kind kind, double x, double z, double vmax, double radiusM, double lifeH) {
        this.id = id; this.kind = kind; this.x = x; this.z = z;
        this.vmax = vmax; this.radiusM = radiusM; this.lifeH = lifeH;
    }

    Storm copy() {
        Storm s = new Storm(id, kind, x, z, vmax, radiusM, lifeH);
        s.ageH = ageH; s.parent = parent; s.offX = offX; s.offZ = offZ; s.vx = vx; s.vz = vz;
        return s;
    }

    /** Intensidade relativa (0 a 1) para efeitos visuais e de jogo. */
    public double intensity() {
        return switch (kind) {
            case HURRICANE -> Math.min(1, Math.max(0, (vmax - 17) / 60));
            case SUPERCELL -> Math.sin(Math.PI * Math.min(1, ageH / lifeH));
            case TORNADO -> Math.min(1, Math.max(0, (vmax - 29) / 72));
        };
    }

    /**
     * Categoria na escala usada para cada fenômeno: Saffir-Simpson para furacões (0 = tempestade
     * tropical, 1 a 5) e Fujita aprimorada para tornados (EF0 a EF5). Supercélulas: 0.
     */
    public int category() {
        return switch (kind) {
            case HURRICANE -> vmax < 33 ? 0 : vmax < 43 ? 1 : vmax < 50 ? 2 : vmax < 58 ? 3 : vmax < 70 ? 4 : 5;
            case TORNADO -> vmax < 38 ? 0 : vmax < 49 ? 1 : vmax < 61 ? 2 : vmax < 74 ? 3 : vmax < 89 ? 4 : 5;
            case SUPERCELL -> 0;
        };
    }

    /** Nome com categoria, para comandos e para o mapa. */
    public String describe() {
        return switch (kind) {
            case HURRICANE -> category() == 0 ? "Tempestade tropical" : "Furacão categoria " + category();
            case TORNADO -> "Tornado EF" + category();
            case SUPERCELL -> "Supercélula";
        };
    }

    // Serialização compacta (para salvar no mundo).
    public double[] pack() {
        return new double[] {kind.ordinal(), id, x, z, vmax, radiusM, ageH, lifeH, parent, offX, offZ, vx, vz};
    }

    public static Storm unpack(double[] d) {
        Storm s = new Storm((int) d[1], Kind.values()[(int) d[0]], d[2], d[3], d[4], d[5], d[7]);
        s.ageH = d[6]; s.parent = (int) d[8]; s.offX = d[9]; s.offZ = d[10]; s.vx = d[11]; s.vz = d[12];
        return s;
    }
}
