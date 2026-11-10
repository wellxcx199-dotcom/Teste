package br.climate.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Roteiro de teste para gravar demonstrações (só para desenvolvimento). Fica desligado a
 * menos que o jogo seja aberto com -Dclimamod.script=caminho/do/roteiro.txt.
 *
 * Cada linha do roteiro é "segundos ação", contando a partir da entrada no mundo:
 * "/comando" manda um comando ao servidor, "vitrine" liga ou desliga a /climavitrine,
 * "hud off" esconde a interface, "shot nome" salva um print em screenshots/nome.png,
 * "mark nome" escreve uma marca no log (para cortar o vídeo), "wait clima" pausa o roteiro até
 * o servidor mandar o clima e "quit" fecha o jogo.
 */
final class DevScript {
    private DevScript() {}

    private static final Logger LOG = LoggerFactory.getLogger("climamod");

    private record Step(double at, String action) {}

    private static List<Step> steps;
    private static int next;
    private static long ticks = -1;

    static void tick(Minecraft mc) {
        if (steps == null) {
            String path = System.getProperty("climamod.script");
            steps = new ArrayList<>();
            if (path == null) return;
            try {
                for (String line : Files.readAllLines(Path.of(path))) {
                    line = line.strip();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int sp = line.indexOf(' ');
                    steps.add(new Step(Double.parseDouble(line.substring(0, sp)), line.substring(sp + 1).strip()));
                }
            } catch (IOException | RuntimeException e) {
                LOG.error("Roteiro de teste inválido: {}", path, e);
            }
        }
        if (next >= steps.size() || mc.player == null || mc.level == null) return;
        // "wait clima" segura o relógio do roteiro até chegar o primeiro pacote de clima.
        if (steps.get(next).action().equals("wait clima") && ticks + 1 >= steps.get(next).at() * 20 && !ClientClimate.active()) return;
        ticks++;
        while (next < steps.size() && ticks >= steps.get(next).at() * 20) run(mc, steps.get(next++).action());
    }

    private static void run(Minecraft mc, String a) {
        LOG.info("[climatest] t={}s {}", ticks / 20.0, a);
        if (a.startsWith("/")) mc.player.connection.sendCommand(a.substring(1));
        else if (a.equals("vitrine")) ClimateShowcase.toggle(mc.player);
        else if (a.equals("hud off")) mc.options.hideGui = true;
        else if (a.startsWith("shot ")) Screenshot.grab(mc.gameDirectory, a.substring(5) + ".png", mc.getMainRenderTarget(), m -> {});
        else if (a.equals("quit")) mc.stop();
    }
}
