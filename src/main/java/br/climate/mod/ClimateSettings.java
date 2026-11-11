package br.climate.mod;

import br.climate.core.ClimateConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuração em config/climamod.json. O arquivo é criado com os valores padrão na
 * primeira vez e lido sempre que o servidor (ou o mundo, no singleplayer) inicia.
 * A seção "physics" são os parâmetros do modelo ({@link ClimateConfig}); "mod" controla
 * a integração com o jogo.
 */
public final class ClimateSettings {
    /** Versão do formato deste arquivo; ver {@link #CURRENT_VERSION}. */
    public int configVersion = CURRENT_VERSION;
    public ClimateConfig physics = new ClimateConfig();
    public Mod mod = new Mod();

    /**
     * Versão 2 (mod 0.9): nuvens e fenômenos três vezes maiores (células de 48 blocos) e novos
     * parâmetros do modelo. Arquivos mais antigos traziam a escala anterior escrita por extenso,
     * então os valores de escala são trocados pelos novos padrões uma única vez.
     */
    public static final int CURRENT_VERSION = 2;

    public static final class Mod {
        /** Células por lado da grade simulada (cada célula tem physics.cellBlocks blocos). */
        public int gridCells = 96;
        /** A simulação avança a cada este número de ticks (200 = 10 s reais). */
        public int stepTicks = 200;
        /** Raio, em células, das nuvens enviadas a cada jogador (20 células de 48 blocos = 960 blocos). */
        public int cloudRadiusCells = 20;
        /** Metros de espessura de nuvem por bloco desenhado (só visual); 1600 / physics.cellBlocks mantém as proporções. */
        public double cloudThicknessMetersPerBlock = 33;
        /** Acima desta altura (Y), as nuvens são comprimidas para caber na distância de visão. */
        public int cloudSoftCeilingY = 220;
        /** Chuva e trovoada do Minecraft seguem o modelo (respeita a gamerule doWeatherCycle). */
        public boolean syncVanillaWeather = true;
        /** Neve no chão e gelo na água dependem da temperatura simulada, não do bioma. */
        public boolean snowFromModel = true;
        /** Neve e gelo de lagos derretem quando a temperatura simulada passa de meltAboveC. */
        public boolean meltSnow = true;
        public double meltAboveC = 2.0;
        /** Tornados empurram e levantam jogadores, mobs e itens. */
        public boolean tornadoPushesEntities = true;
        /** Tornados arrancam folhas, plantas e neve (só blocos naturais e leves). */
        public boolean tornadoBreaksBlocks = true;
        /** Ventos muito fortes (furacões) empurram jogadores a céu aberto. */
        public boolean strongWindPushesPlayers = true;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static ClimateSettings load(Path file, Logger log) {
        ClimateSettings s = null;
        if (Files.exists(file)) {
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                s = GSON.fromJson(text, ClimateSettings.class);
                JsonObject raw = JsonParser.parseString(text).getAsJsonObject();
                int version = raw.has("configVersion") ? raw.get("configVersion").getAsInt() : 1;
                if (s != null && version < 2) s.migrateToV2(log);
            } catch (IOException | JsonParseException | IllegalStateException e) {
                // Não sobrescreve: o jogador pode ter só esquecido uma vírgula.
                log.error("config/climamod.json inválido, usando os valores padrão: {}", e.getMessage());
                return new ClimateSettings().validated(log);
            }
        }
        if (s == null) s = new ClimateSettings();
        if (s.physics == null) s.physics = new ClimateConfig();
        if (s.mod == null) s.mod = new Mod();
        s.validated(log);
        try {
            // Regrava para incluir opções novas de versões futuras e corrigir valores fora da faixa.
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(s), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Não foi possível gravar {}: {}", file, e.getMessage());
        }
        return s;
    }

    /** Arquivo de antes da versão 0.9: adota a nova escala das nuvens (uma vez só). */
    private void migrateToV2(Logger log) {
        ClimateConfig d = new ClimateConfig();
        Mod m = new Mod();
        if (physics != null) physics.cellBlocks = d.cellBlocks;
        if (mod != null) {
            mod.cloudRadiusCells = m.cloudRadiusCells;
            mod.cloudThicknessMetersPerBlock = m.cloudThicknessMetersPerBlock;
        }
        configVersion = CURRENT_VERSION;
        log.info("config/climamod.json atualizado para a nova escala das nuvens (células de {} blocos). "
                + "Para voltar à escala antiga, use physics.cellBlocks = 16, mod.cloudRadiusCells = 24 e "
                + "mod.cloudThicknessMetersPerBlock = 100.", d.cellBlocks);
    }

    /** Coloca cada valor numa faixa segura, avisando no log o que foi alterado. */
    private ClimateSettings validated(Logger log) {
        configVersion = CURRENT_VERSION;
        mod.gridCells = clamp(log, "mod.gridCells", mod.gridCells, 32, 256);
        mod.stepTicks = clamp(log, "mod.stepTicks", mod.stepTicks, 20, 2400);
        mod.cloudRadiusCells = clamp(log, "mod.cloudRadiusCells", mod.cloudRadiusCells, 4, 64);
        mod.cloudSoftCeilingY = clamp(log, "mod.cloudSoftCeilingY", mod.cloudSoftCeilingY, 100, 600);
        if (!(mod.cloudThicknessMetersPerBlock >= 15)) mod.cloudThicknessMetersPerBlock = 33;
        physics.cellBlocks = clamp(log, "physics.cellBlocks", physics.cellBlocks, 4, 64);
        if (!(physics.halfRangeBlocks >= 1000)) physics.halfRangeBlocks = 20000;
        if (!(physics.metersPerBlockV > 0)) physics.metersPerBlockV = 15;
        if (!(physics.daysPerYear >= 1)) physics.daysPerYear = 24;
        return this;
    }

    private static int clamp(Logger log, String name, int v, int lo, int hi) {
        int c = Math.max(lo, Math.min(hi, v));
        if (c != v) log.warn("{} = {} fora da faixa [{}, {}]; usando {}", name, v, lo, hi, c);
        return c;
    }
}
