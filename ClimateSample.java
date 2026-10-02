package br.climate.core;

/** Fotografia do clima num ponto, para comandos, HUD e renderização. */
public record ClimateSample(
        double temperatureC, double pressureHPa, double humidityRel,
        double windEast, double windNorth, double windSpeed,
        double dewPointC, double cloudBaseM, double cloudWater,
        CloudType cloud, double precipMmH, boolean snow,
        double continentality, double latitudeDeg, boolean thunder) {

    /** Chove de forma perceptível (limiar de 0,1 mm/h). */
    public boolean raining() { return precipMmH > 0.1; }

    /** Direção de ONDE o vento vem, em graus (0 = norte, 90 = leste). */
    public double windFromDeg() {
        double d = Math.toDegrees(Math.atan2(-windEast, -windNorth));
        return (d + 360) % 360;
    }
}
