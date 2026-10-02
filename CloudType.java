package br.climate.core;

public enum CloudType {
    CLEAR("Céu limpo"),
    CIRRUS("Cirros"),
    CUMULUS("Cúmulos"),
    STRATUS("Estratos"),
    NIMBOSTRATUS("Nimbostratos"),
    CUMULONIMBUS("Cumulonimbos"),
    FOG("Neblina");

    public final String label;
    CloudType(String label) { this.label = label; }
}
