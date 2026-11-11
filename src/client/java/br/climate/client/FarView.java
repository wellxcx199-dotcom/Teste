package br.climate.client;

import net.minecraft.client.Minecraft;

/**
 * Nuvens além do alcance de visão do jogo. O Minecraft corta tudo o que passa de 4 vezes a
 * distância de renderização (com 12 chunks, 768 blocos), e um cumulonimbo com a bigorna pode
 * ter mais de mil blocos. Em vez de mexer na projeção (o que quebraria a ordem de profundidade
 * com o terreno), os vértices distantes são "puxados" para perto ao longo da linha de visada:
 * a posição na tela não muda, só a profundidade, que continua crescente com a distância.
 * Até metade do alcance nada é alterado, então o terreno (que nunca passa disso) e as nuvens
 * próximas mantêm a profundidade exata.
 */
final class FarView {
    private FarView() {}

    private static float d0 = Float.MAX_VALUE, slope = 1;

    /** Chamado uma vez por quadro: {@code reach} é a maior distância (blocos) de algo a desenhar. */
    static void update(double reach) {
        float far = Minecraft.getInstance().gameRenderer.getDepthFar();
        float end = 0.92f * far;
        if (reach <= end) { d0 = Float.MAX_VALUE; slope = 1; return; }
        d0 = 0.5f * far;
        slope = (float) ((end - d0) / (reach - d0));
    }

    /** Fator pelo qual multiplicar uma posição relativa à câmera. */
    static float scale(double x, double y, double z) {
        double d = Math.sqrt(x * x + y * y + z * z);
        if (d <= d0) return 1;
        return (float) ((d0 + (d - d0) * slope) / d);
    }
}
