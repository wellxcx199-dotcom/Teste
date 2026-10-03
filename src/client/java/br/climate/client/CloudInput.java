package br.climate.client;

import br.climate.mod.ClimatePayload;
import br.climate.mod.StormInfo;

import java.util.Arrays;
import java.util.List;

/**
 * O que o CloudShapes desenha: um recorte de células com tipo, cobertura e alturas, mais o
 * estado da animação de cada célula (opacidade e progresso da bigorna). Vem do CloudField,
 * que anima as nuvens em direção ao último pacote do servidor.
 *
 * @param opacity   0 a 1 por célula: nuvens surgindo ou se desfazendo ficam translúcidas
 * @param anvil     0 a 1 por célula: quanto a bigorna do cumulonimbo já se espalhou
 * @param anvilTop  altura (Y) do topo da bigorna; continua lá enquanto a torre desmorona
 * @param boil      0 a 1 por célula: atividade convectiva (topos "fervendo" enquanto crescem)
 * @param born      tick (+1) em que a torre começou a crescer; 0 = desconhecido. A mais velha manda
 * @param from      direção da vizinha de onde a célula brotou, codificada como (dx+1)*3 + (dz+1)
 * @param grow      0 a 1: quanto a torre já cresceu em relação ao alvo
 * @param time      tempo de jogo em ticks, para a fervura dos topos
 */
record CloudInput(int originX, int originZ, int cellBlocks, int size,
                  byte[] types, byte[] cover, short[] baseY, short[] topY,
                  float[] opacity, float[] anvil, short[] anvilTop, float[] boil,
                  int[] born, byte[] from, float[] grow, List<StormInfo> storms, float driftX, float driftZ, float refY, double time) {

    /** Sem animação: tudo opaco e com a bigorna completa (usado se não houver CloudField). */
    static CloudInput of(ClimatePayload p) {
        int n = p.size() * p.size();
        float[] one = new float[n];
        Arrays.fill(one, 1f);
        return new CloudInput(p.originX(), p.originZ(), p.cellBlocks(), p.size(), p.types(), p.cover(),
                p.baseY(), p.topY(), one, one, p.topY(), new float[n],
                new int[n], center(n), one, p.storms(), p.driftX(), p.driftZ(), p.refY(), 0);
    }

    private static byte[] center(int n) {
        byte[] b = new byte[n];
        Arrays.fill(b, (byte) 4);
        return b;
    }
}
