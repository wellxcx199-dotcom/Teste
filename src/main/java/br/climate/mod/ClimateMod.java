package br.climate.mod;

import br.climate.core.*;
import com.mojang.brigadier.Command;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class ClimateMod implements ModInitializer {
    public static final String ID = "climamod";
    private static final Logger LOG = LoggerFactory.getLogger(ID);
    private static final int STEP_TICKS = 200;       // simula a cada 10 s reais
    private static final int GRID = 96;              // 96x96 células de 16 blocos = 1536 blocos de lado
    /** Raio (em células) do recorte de nuvens enviado a cada jogador: 24 células = 384 blocos. */
    private static final int CLOUD_RADIUS = 24;
    /** Acima desta altura as nuvens são "comprimidas", para caberem na distância de renderização. */
    private static final int CLOUD_SOFT_CEILING = 220;
    /**
     * Metros de espessura de nuvem por bloco desenhado. Na horizontal um bloco vale 500 m e
     * na vertical 15 m; desenhada nessa escala vertical, uma nuvem de 10 km de altura e 8 km
     * de largura viraria um pilar fino de 700 blocos. Com 100 m/bloco a forma fica parecida
     * com a real (cumulonimbo com ~90 blocos, cúmulo com 6 a 24).
     */
    private static final double CLOUD_THICKNESS_M_PER_BLOCK = 100;

    private static ClimateGrid grid;
    private static ClimateConfig cfg;
    private static MinecraftTerrain terrain;
    /** Leitura do terreno em andamento (criação da grade ou deslocamento da janela), e para onde. */
    private static CompletableFuture<Void> pendingTerrain;
    private static int[] pendingOrigin;

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playS2C().register(ClimatePayload.TYPE, ClimatePayload.CODEC);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            cfg = new ClimateConfig();
            terrain = new MinecraftTerrain(level, cfg);
            int[] origin = savedData(level).savedOrigin(GRID, GRID, cfg);
            if (origin == null) {
                int half = GRID / 2;
                origin = new int[] {Math.floorDiv(level.getSharedSpawnPos().getX(), cfg.cellBlocks) - half,
                                    Math.floorDiv(level.getSharedSpawnPos().getZ(), cfg.cellBlocks) - half};
            }
            // O relevo é lido em segundo plano; a grade nasce no primeiro tick depois disso.
            pendingOrigin = origin;
            pendingTerrain = terrain.prefetch(origin[0], origin[1], GRID, GRID);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            grid = null; terrain = null; pendingTerrain = null; pendingOrigin = null;
        });

        ServerTickEvents.END_WORLD_TICK.register(level -> {
            if (cfg == null || level.dimension() != Level.OVERWORLD) return;
            if (grid == null) { createGridWhenReady(level); return; }
            if (level.getGameTime() % STEP_TICKS != 0) return;
            followPlayer(level);
            grid.step(STEP_TICKS / 1000.0, gameHours(level)); // 1000 ticks = 1 hora de jogo
            savedData(level).setDirty();
            applyVanillaWeather(level);
            for (ServerPlayer p : level.players()) sendClimate(p);
        });

        // Quem acaba de entrar recebe o clima na hora, sem esperar o próximo passo.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                server.execute(() -> sendClimate(handler.getPlayer())));

        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) ->
            dispatcher.register(Commands.literal("clima").executes(ctx -> {
                ServerPlayer p = ctx.getSource().getPlayerOrException();
                if (grid == null) { ctx.getSource().sendFailure(Component.literal("Clima não iniciado.")); return 0; }
                ClimateSample s = sampleAt(p);
                String txt = String.format(
                    "Lat %.1f° | T %.1f °C | P %.1f hPa | UR %.0f%% | Orvalho %.1f °C%n"
                  + "Vento %.1f m/s de %.0f° | Nuvens: %s (base %.0f m) | Chuva %.2f mm/h%s%n"
                  + "Continentalidade %.2f%s",
                    s.latitudeDeg(), s.temperatureC(), s.pressureHPa(), s.humidityRel() * 100, s.dewPointC(),
                    s.windSpeed(), s.windFromDeg(), s.cloud().label, s.cloudBaseM(), s.precipMmH(),
                    s.snow() && s.precipMmH() > 0 ? " (neve)" : "", s.continentality(),
                    s.thunder() ? " | TROVOADA" : "");
                ctx.getSource().sendSuccess(() -> Component.literal(txt), false);
                return Command.SINGLE_SUCCESS;
            })));
    }

    private static ClimateSample sampleAt(ServerPlayer p) {
        double elev = (p.getY() - p.serverLevel().getSeaLevel()) * cfg.metersPerBlockV;
        return grid.sample(p.getX(), p.getZ(), elev);
    }

    private static ClimateSavedData savedData(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(ClimateSavedData.factory(), ClimateSavedData.NAME);
    }

    /** True quando a leitura do terreno em segundo plano terminou (bem ou mal). */
    private static boolean terrainReady() {
        if (pendingTerrain == null || !pendingTerrain.isDone()) return false;
        if (pendingTerrain.isCompletedExceptionally()) {
            // Sem cache, o que faltar é lido na própria thread do servidor: mais lento, mas funciona.
            LOG.error("Falha ao ler o terreno em segundo plano", pendingTerrain.exceptionNow());
        }
        return true;
    }

    private static void createGridWhenReady(ServerLevel level) {
        if (!terrainReady()) return;
        ClimateSavedData data = savedData(level);
        grid = data.restore(GRID, GRID, cfg, terrain, gameHours(level));
        if (grid == null) {
            grid = new ClimateGrid(GRID, GRID, pendingOrigin[0], pendingOrigin[1], cfg, terrain);
            grid.initialize(gameHours(level));
            LOG.info("Clima iniciado do equilíbrio, janela centrada no spawn.");
        } else {
            LOG.info("Clima restaurado do mundo salvo (origem da janela: célula {}, {}).",
                    grid.originCellX, grid.originCellZ);
        }
        data.attach(grid);
        pendingTerrain = null;
        pendingOrigin = null;
    }

    /**
     * Se o (primeiro) jogador se afastou do centro, desloca a janela para centralizá-lo.
     * O deslocamento acontece em duas fases: primeiro o relevo das células novas é lido em
     * segundo plano; quando fica pronto (alguns passos depois), a janela é de fato movida.
     */
    private static void followPlayer(ServerLevel level) {
        if (pendingTerrain != null) {
            if (!terrainReady()) return;
            grid.recenter(pendingOrigin[0], pendingOrigin[1], gameHours(level));
            terrain.retain(grid.originCellX, grid.originCellZ, GRID, GRID, GRID / 2);
            pendingTerrain = null;
            pendingOrigin = null;
            return;
        }
        if (level.players().isEmpty()) return;
        ServerPlayer p = level.players().get(0);
        int pcx = (int) Math.floor(p.getX() / cfg.cellBlocks);
        int pcz = (int) Math.floor(p.getZ() / cfg.cellBlocks);
        int cx = grid.originCellX + GRID / 2, cz = grid.originCellZ + GRID / 2;
        if (Math.abs(pcx - cx) > GRID / 4 || Math.abs(pcz - cz) > GRID / 4) {
            pendingOrigin = new int[] {pcx - GRID / 2, pcz - GRID / 2};
            pendingTerrain = terrain.prefetch(pendingOrigin[0], pendingOrigin[1], GRID, GRID);
        }
    }

    /**
     * Liga o modelo ao clima vanilla. No Minecraft a chuva é uma só para o mundo inteiro, então
     * vale a maioria: chove se a maior parte dos jogadores está sob chuva no modelo. Assim
     * plantações, mobs, raios e o fogo reagem ao clima simulado. Clientes com o mod ainda
     * recebem o clima local de cada um (ver ClimatePayload).
     *
     * Com a regra doWeatherCycle desligada, o mod não mexe no clima vanilla, o que deixa o
     * comando /weather funcionar normalmente.
     */
    private static void applyVanillaWeather(ServerLevel level) {
        if (!level.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE)) return;
        List<ServerPlayer> players = level.players();
        int rain = 0, thunder = 0, n = 0;
        for (ServerPlayer p : players) {
            if (!grid.contains(p.getX(), p.getZ())) continue;
            ClimateSample s = sampleAt(p);
            n++;
            if (s.raining()) rain++;
            if (s.thunder()) thunder++;
        }
        boolean raining, thundering;
        if (n == 0) { // ninguém na janela: usa o centro dela
            int k = (GRID / 2) * GRID + GRID / 2;
            raining = grid.precipRate[k] > 0.1;
            thundering = grid.isThunder(k);
        } else {
            raining = rain * 2 >= n && rain > 0;
            thundering = raining && thunder * 2 >= n && thunder > 0;
        }
        // A duração cobre alguns passos de folga; ela é renovada a cada passo.
        int dur = STEP_TICKS * 3;
        level.setWeatherParameters(raining ? 0 : dur, raining ? dur : 0, raining, thundering);
    }

    /** Envia ao jogador o clima local e o recorte de nuvens ao redor dele. */
    private static void sendClimate(ServerPlayer p) {
        if (grid == null || p.level().dimension() != Level.OVERWORLD) return;
        if (!ServerPlayNetworking.canSend(p, ClimatePayload.TYPE)) return;
        ServerLevel level = p.serverLevel();
        ClimateSample s = sampleAt(p);
        int cb = cfg.cellBlocks, size = 2 * CLOUD_RADIUS + 1, n = size * size;
        int pcx = Math.floorDiv(p.getBlockX(), cb), pcz = Math.floorDiv(p.getBlockZ(), cb);
        int ocx = pcx - CLOUD_RADIUS, ocz = pcz - CLOUD_RADIUS;
        byte[] types = new byte[n], cover = new byte[n];
        short[] base = new short[n], top = new short[n];
        int sea = level.getSeaLevel();
        for (int j = 0; j < size; j++)
            for (int i = 0; i < size; i++) {
                int idx = j * size + i;
                double bx = (ocx + i) * cb + cb / 2.0, bz = (ocz + j) * cb + cb / 2.0;
                if (!grid.contains(bx, bz)) { types[idx] = (byte) CloudType.CLEAR.ordinal(); continue; }
                int k = grid.cellIndex(bx, bz);
                CloudType t = grid.type[k] == null ? CloudType.CLEAR : grid.type[k];
                types[idx] = (byte) t.ordinal();
                cover[idx] = (byte) Math.round(grid.coverage(k) * 255);
                base[idx] = toBlockY(grid.cloudBaseMeters(k), sea);
                double thickness = (grid.cloudTopMeters(k) - grid.cloudBaseMeters(k)) / CLOUD_THICKNESS_M_PER_BLOCK;
                top[idx] = (short) (base[idx] + Math.max(2, Math.round(thickness)));
            }
        // Velocidade do transporte em blocos por tick: 1 tick = 3,6 s de jogo, reduzidos
        // pelo mesmo fator usado na advecção da grade.
        double perTick = 3.6 * cfg.windSpeedFactor / cfg.metersPerBlockH();
        ServerPlayNetworking.send(p, new ClimatePayload(
                (float) s.temperatureC(), (float) s.precipMmH(), s.thunder(), s.cloud() == CloudType.FOG,
                (float) (s.windEast() * perTick), (float) (-s.windNorth() * perTick),
                ocx * cb, ocz * cb, cb, size, types, cover, base, top));
    }

    /** Converte altitude (m acima do mar) em Y do mundo, comprimindo o que fica muito alto. */
    private static short toBlockY(double meters, int seaLevel) {
        double y = seaLevel + meters / cfg.metersPerBlockV;
        if (y > CLOUD_SOFT_CEILING) y = CLOUD_SOFT_CEILING + (y - CLOUD_SOFT_CEILING) * 0.25;
        return (short) Math.round(Math.min(y, 1000));
    }

    /** Hora absoluta de jogo; no Minecraft o tick 0 do dia corresponde às 6h. */
    private static double gameHours(ServerLevel level) {
        return level.getDayTime() / 1000.0 + 6.0;
    }
}
