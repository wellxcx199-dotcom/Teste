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
 * @param time      tempo de jogo em ticks, para a fervura dos topos
 */
record CloudInput(int originX, int originZ, int cellBlocks, int size,
                  byte[] types, byte[] cover, short[] baseY, short[] topY,
                  float[] opacity, float[] anvil, short[] anvilTop, float[] boil,
                  List<StormInfo> storms, float driftX, float driftZ, float refY, double time) {

    /** Sem animação: tudo opaco e com a bigorna completa (usado se não houver CloudField). */
    static CloudInput of(ClimatePayload p) {
        int n = p.size() * p.size();
        float[] one = new float[n];
        Arrays.fill(one, 1f);
        return new CloudInput(p.originX(), p.originZ(), p.cellBlocks(), p.size(), p.types(), p.cover(),
                p.baseY(), p.topY(), one, one, p.topY(), new float[n], p.storms(), p.driftX(), p.driftZ(), p.refY(), 0);
    }
}
