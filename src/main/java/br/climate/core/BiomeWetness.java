package br.climate.core;

/** Converte o nome de um bioma (ex.: "minecraft:jungle") em umidade do solo/vegetação (0 a 1). */
public final class BiomeWetness {
    private BiomeWetness() {}

    public static double of(String biomeId) {
        String b = biomeId.toLowerCase();
        if (b.contains("desert")) return 0.05;
        if (b.contains("badlands")) return 0.12;
        if (b.contains("savanna")) return 0.30;
        if (b.contains("jungle")) return 1.00;
        if (b.contains("swamp") || b.contains("mangrove")) return 0.95;
        if (b.contains("river") || b.contains("beach")) return 0.80;
        if (b.contains("dark_forest")) return 0.80;
        if (b.contains("forest") || b.contains("grove")) return 0.65;
        if (b.contains("taiga")) return 0.60;
        if (b.contains("peaks") || b.contains("slopes") || b.contains("windswept")) return 0.40;
        if (b.contains("snowy") || b.contains("frozen") || b.contains("ice")) return 0.35;
        if (b.contains("plains") || b.contains("meadow")) return 0.45;
        return 0.50;
    }
}
