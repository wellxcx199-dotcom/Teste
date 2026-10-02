package br.climate.core;

/** Fórmulas termodinâmicas básicas (Magnus, ponto de orvalho, barométrica). */
public final class Physics {
    private Physics() {}

    public static final double LAPSE_ENV = 6.5;      // °C/km, gradiente médio da troposfera
    public static final double LAPSE_DRY = 9.8;      // °C/km, adiabática seca
    public static final double SCALE_HEIGHT = 8400;  // m, altura de escala da pressão
    public static final double OMEGA = 7.292e-5;     // rad/s, rotação da Terra
    public static final double RHO = 1.2;            // kg/m³, densidade do ar junto ao solo

    /** Pressão de saturação do vapor (hPa) pela fórmula de Magnus, T em °C. */
    public static double satPressure(double tC) {
        return 6.112 * Math.exp(17.62 * tC / (243.12 + tC));
    }

    /** Ponto de orvalho (°C) a partir da pressão de vapor (hPa): inversa de Magnus. */
    public static double dewPoint(double eHPa) {
        double ln = Math.log(Math.max(eHPa, 1e-4) / 6.112);
        return 243.12 * ln / (17.62 - ln);
    }

    /** Altura aproximada da base das nuvens (m acima do solo). */
    public static double cloudBase(double tC, double dewC) {
        return Math.max(0, 125.0 * (tC - dewC));
    }

    /** Pressão numa altitude z (m) dada a pressão ao nível do mar (hPa). */
    public static double pressureAtAltitude(double pSeaHPa, double zMeters) {
        return pSeaHPa * Math.exp(-zMeters / SCALE_HEIGHT);
    }

    public static double clamp(double x, double lo, double hi) {
        return x < lo ? lo : (x > hi ? hi : x);
    }

    public static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
