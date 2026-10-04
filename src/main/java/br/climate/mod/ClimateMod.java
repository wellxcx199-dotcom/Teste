package br.climate.mod;

import br.climate.core.*;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class ClimateMod implements ModInitializer {
    public static final String ID = "climamod";
    private static final Logger LOG = LoggerFactory.getLogger(ID);
    /** Horas de previsão mostradas por /clima previsao, e o intervalo entre as linhas. */
    private static final int FORECAST_HOURS = 24, FORECAST_EVERY = 3;
    /** Colunas sorteadas por jogador, a cada segundo, para checar se a neve ou o gelo derretem. */
    private static final int MELT_TRIES = 24, MELT_RANGE = 96;

    private static ClimateSettings.Mod opt;
    private static ClimateConfig cfg;
    private static ClimateGrid grid;
    private static MinecraftTerrain terrain;
    /** Leitura do terreno em andamento (criação da grade ou deslocamento da janela), e para onde. */
    private static CompletableFuture<Void> pendingTerrain;
    private static int[] pendingOrigin;
    /** Mundo cuja precipitação está sendo processada agora (ver ServerLevelPrecipitationMixin). */
    private static ServerLevel precipitationLevel;

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playS2C().register(ClimatePayload.TYPE, ClimatePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MapPayload.TYPE, MapPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(MapRequestPayload.TYPE, MapRequestPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(MapRequestPayload.TYPE, (payload, ctx) -> sendMap(ctx.player()));

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ClimateSettings settings = ClimateSettings.load(
                    FabricLoader.getInstance().getConfigDir().resolve("climamod.json"), LOG);
            opt = settings.mod;
            cfg = settings.physics;
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            terrain = new MinecraftTerrain(level, cfg);
            int n = opt.gridCells;
            int[] origin = savedData(level).savedOrigin(n, n, cfg);
            if (origin == null) {
                origin = new int[] {Math.floorDiv(level.getSharedSpawnPos().getX(), cfg.cellBlocks) - n / 2,
                                    Math.floorDiv(level.getSharedSpawnPos().getZ(), cfg.cellBlocks) - n / 2};
            }
            // O relevo é lido em segundo plano; a grade nasce no primeiro tick depois disso.
            pendingOrigin = origin;
            pendingTerrain = terrain.prefetch(origin[0], origin[1], n, n);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            grid = null; terrain = null; pendingTerrain = null; pendingOrigin = null; precipitationLevel = null;
        });

        ServerTickEvents.END_WORLD_TICK.register(level -> {
            if (cfg == null || level.dimension() != Level.OVERWORLD) return;
            if (grid == null) { createGridWhenReady(level); return; }
            if (opt.meltSnow && level.getGameTime() % 20 == 0) meltSnowAndIce(level);
            StormEffects.tick(level, grid, cfg, opt);
            if (level.getGameTime() % opt.stepTicks != 0) return;
            followPlayers(level);
            grid.step(opt.stepTicks / 1000.0, gameHours(level)); // 1000 ticks = 1 hora de jogo
            savedData(level).setDirty();
            if (opt.syncVanillaWeather) applyVanillaWeather(level);
            for (ServerPlayer p : level.players()) sendClimate(p);
        });

        // Quem acaba de entrar recebe o clima na hora, sem esperar o próximo passo.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                server.execute(() -> sendClimate(handler.getPlayer())));

        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) ->
            dispatcher.register(Commands.literal("clima")
                    .executes(ClimateMod::commandNow)
                    .then(Commands.literal("previsao").executes(ClimateMod::commandForecast))
                    .then(Commands.literal("fenomenos").executes(ClimateMod::commandStorms))
                    .then(Commands.literal("fenomeno").requires(src -> src.hasPermission(2))
                            .then(Commands.literal("furacao").executes(c -> commandSpawn(c, Storm.Kind.HURRICANE)))
                            .then(Commands.literal("supercelula").executes(c -> commandSpawn(c, Storm.Kind.SUPERCELL)))
                            .then(Commands.literal("tornado").executes(c -> commandSpawn(c, Storm.Kind.TORNADO))))));
    }

    // ---------- Comandos ----------

    /** Jogador que executou o comando, se o clima está disponível onde ele está; senão, avisa. */
    private static ServerPlayer climatePlayer(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = ctx.getSource().getPlayerOrException();
        if (grid == null) {
            ctx.getSource().sendFailure(Component.literal("O clima ainda está sendo preparado; tente em alguns segundos."));
            return null;
        }
        if (p.level().dimension() != Level.OVERWORLD) {
            ctx.getSource().sendFailure(Component.literal("O clima só é simulado na Superfície (Overworld)."));
            return null;
        }
        if (!grid.contains(p.getX(), p.getZ())) {
            ctx.getSource().sendFailure(Component.literal("Você está fora da área simulada; ela se moverá até você em instantes."));
            return null;
        }
        return p;
    }

    private static int commandNow(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = climatePlayer(ctx);
        if (p == null) return 0;
        ClimateSample s = sampleAt(p);
        String txt = String.format(
            "Lat %.1f° | T %.1f °C | P %.1f hPa | UR %.0f%% | Orvalho %.1f °C%n"
          + "Vento %.1f m/s de %s (%.0f°) | Nuvens: %s (base %.0f m) | Chuva %.2f mm/h%s%n"
          + "Continentalidade %.2f%s",
            s.latitudeDeg(), s.temperatureC(), s.pressureHPa(), s.humidityRel() * 100, s.dewPointC(),
            s.windSpeed(), compass(s.windFromDeg()), s.windFromDeg(), s.cloud().label, s.cloudBaseM(),
            s.precipMmH(), s.snow() && s.raining() ? " (neve)" : "", s.continentality(),
            s.thunder() ? " | TROVOADA" : "");
        ctx.getSource().sendSuccess(() -> Component.literal(txt), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Previsão para as próximas horas no local do jogador. Roda uma cópia da grade adiante
     * (alguns milissegundos), com uma pequena perturbação para imitar a incerteza real.
     */
    private static int commandForecast(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = climatePlayer(ctx);
        if (p == null) return 0;
        ServerLevel level = p.serverLevel();
        double now = gameHours(level);
        // A semente muda a cada passo de simulação: pedir de novo no mesmo instante dá a mesma previsão.
        long seed = level.getSeed() ^ (level.getGameTime() / opt.stepTicks);
        List<ClimateSample> f = grid.forecast(p.getX(), p.getZ(), elevationOf(p), now, FORECAST_HOURS, FORECAST_EVERY, seed);
        StringBuilder sb = new StringBuilder("Previsão para as próximas " + FORECAST_HOURS + " h:");
        for (int i = 0; i < f.size(); i++) {
            ClimateSample s = f.get(i);
            int hour = (int) Math.floor(now + (i + 1) * FORECAST_EVERY) % 24;
            String sky = s.thunder() ? "Trovoada" : s.raining() ? (s.snow() ? "Neve" : "Chuva") : s.cloud().label;
            sb.append(String.format("%n%02dh  %5.1f °C  %s%s  vento %.0f m/s de %s",
                    hour, s.temperatureC(), sky,
                    s.raining() ? String.format(" %.1f mm/h", s.precipMmH()) : "",
                    s.windSpeed(), compass(s.windFromDeg())));
        }
        String txt = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(txt), false);
        return Command.SINGLE_SUCCESS;
    }

    /** Lista os fenômenos em andamento, com distância e direção a partir do jogador. */
    private static int commandStorms(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer p = climatePlayer(ctx);
        if (p == null) return 0;
        if (grid.storms.storms.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("Nenhum furacão, supercélula ou tornado ativo."), false);
            return Command.SINGLE_SUCCESS;
        }
        StringBuilder sb = new StringBuilder("Fenômenos ativos:");
        for (Storm s : grid.storms.storms) {
            double dx = s.x - p.getX(), dz = s.z - p.getZ();
            double dist = Math.hypot(dx, dz);
            double bearing = (Math.toDegrees(Math.atan2(dx, -dz)) + 360) % 360;   // 0 = norte
            sb.append(String.format("%n%s — vento %.0f m/s — %.0f blocos a %s (x %.0f, z %.0f)",
                    s.describe(), s.vmax, dist, compass(bearing), s.x, s.z));
        }
        String txt = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(txt), false);
        return Command.SINGLE_SUCCESS;
    }

    /** Cria um fenômeno perto do jogador (para testar e demonstrar). */
    private static int commandSpawn(CommandContext<CommandSourceStack> ctx, Storm.Kind kind) throws CommandSyntaxException {
        ServerPlayer p = climatePlayer(ctx);
        if (p == null) return 0;
        Storm s;
        switch (kind) {
            case HURRICANE -> s = grid.storms.spawnHurricane(p.getX() + 600, p.getZ(), 50);       // a leste: os alísios o trazem
            // Os criados por comando duram mais que os naturais, para dar tempo de observar.
            case SUPERCELL -> s = grid.storms.spawnSupercell(p.getX(), p.getZ() - 120, 6);
            default -> {
                Storm sc = grid.storms.spawnSupercell(p.getX(), p.getZ() - 90, 6);
                sc.ageH = sc.lifeH * 0.4;                                                          // já madura
                s = grid.storms.spawnTornado(sc, 2, 3.0, cfg.cellBlocks);
            }
        }
        double dist = Math.hypot(s.x - p.getX(), s.z - p.getZ());
        String txt = String.format("%s criado a %.0f blocos (x %.0f, z %.0f).", s.describe(), dist, s.x, s.z);
        ctx.getSource().sendSuccess(() -> Component.literal(txt), true);
        for (ServerPlayer pl : p.serverLevel().players()) sendClimate(pl);
        return Command.SINGLE_SUCCESS;
    }

    private static String compass(double fromDeg) {
        String[] dirs = {"N", "NE", "L", "SE", "S", "SO", "O", "NO"};
        return dirs[(int) Math.round(fromDeg / 45.0) & 7];
    }

    // ---------- Ciclo de vida da grade ----------

    private static double elevationOf(ServerPlayer p) {
        return (p.getY() - p.serverLevel().getSeaLevel()) * cfg.metersPerBlockV;
    }

    private static ClimateSample sampleAt(ServerPlayer p) {
        return grid.sample(p.getX(), p.getZ(), elevationOf(p));
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
        int n = opt.gridCells;
        ClimateSavedData data = savedData(level);
        grid = data.restore(n, n, cfg, terrain, gameHours(level));
        if (grid == null) {
            grid = new ClimateGrid(n, n, pendingOrigin[0], pendingOrigin[1], cfg, terrain);
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
     * Mantém os jogadores dentro da janela simulada. Se todos cabem nela, a janela segue o
     * centro do grupo; se estão espalhados demais, segue o primeiro jogador.
     *
     * O deslocamento acontece em duas fases: primeiro o relevo das células novas é lido em
     * segundo plano; quando fica pronto (alguns passos depois), a janela é de fato movida.
     */
    private static void followPlayers(ServerLevel level) {
        int n = opt.gridCells;
        if (pendingTerrain != null) {
            if (!terrainReady()) return;
            grid.recenter(pendingOrigin[0], pendingOrigin[1], gameHours(level));
            terrain.retain(grid.originCellX, grid.originCellZ, n, n, n / 2);
            pendingTerrain = null;
            pendingOrigin = null;
            return;
        }
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;
        double sx = 0, sz = 0;
        for (ServerPlayer p : players) { sx += p.getX(); sz += p.getZ(); }
        double cxB = sx / players.size(), czB = sz / players.size();
        double half = (n / 2.0 - 4) * cfg.cellBlocks;
        for (ServerPlayer p : players)
            if (Math.abs(p.getX() - cxB) > half || Math.abs(p.getZ() - czB) > half) {
                cxB = players.get(0).getX(); czB = players.get(0).getZ();
                break;
            }
        int tcx = (int) Math.floor(cxB / cfg.cellBlocks), tcz = (int) Math.floor(czB / cfg.cellBlocks);
        int cx = grid.originCellX + n / 2, cz = grid.originCellZ + n / 2;
        if (Math.abs(tcx - cx) > n / 4 || Math.abs(tcz - cz) > n / 4) {
            pendingOrigin = new int[] {tcx - n / 2, tcz - n / 2};
            pendingTerrain = terrain.prefetch(pendingOrigin[0], pendingOrigin[1], n, n);
        }
    }

    // ---------- Clima vanilla ----------

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
        int rain = 0, thunder = 0, n = 0;
        for (ServerPlayer p : level.players()) {
            if (!grid.contains(p.getX(), p.getZ())) continue;
            ClimateSample s = sampleAt(p);
            n++;
            if (s.raining()) rain++;
            if (s.thunder()) thunder++;
        }
        boolean raining, thundering;
        if (n == 0) { // ninguém na janela: usa o centro dela
            int k = (grid.nz / 2) * grid.nx + grid.nx / 2;
            raining = grid.precipRate[k] > 0.1;
            thundering = grid.isThunder(k);
        } else {
            raining = rain * 2 >= n && rain > 0;
            thundering = raining && thunder * 2 >= n && thunder > 0;
        }
        // A duração cobre alguns passos de folga; ela é renovada a cada passo.
        int dur = opt.stepTicks * 3;
        level.setWeatherParameters(raining ? 0 : dur, raining ? dur : 0, raining, thundering);
    }

    // ---------- Neve e gelo pelo modelo ----------

    /** Chamado pelo mixin ao entrar/sair de ServerLevel.tickPrecipitation. */
    public static void beginPrecipitation(ServerLevel level) {
        precipitationLevel = grid != null && opt.snowFromModel && level.dimension() == Level.OVERWORLD ? level : null;
    }

    public static void endPrecipitation() { precipitationLevel = null; }

    /**
     * Mundo em que a neve/gelo devem seguir o modelo neste instante, ou null. Só é não nulo
     * dentro de tickPrecipitation do Overworld; assim a geração de mundo (que roda em outras
     * threads e também consulta os biomas) não é afetada.
     */
    public static ServerLevel precipitationLevel() { return precipitationLevel; }

    /** Temperatura simulada (°C) num bloco, ou NaN se o bloco está fora da área simulada. */
    public static double modelTemperatureAt(ServerLevel level, BlockPos pos) {
        if (grid == null || !grid.contains(pos.getX() + 0.5, pos.getZ() + 0.5)) return Double.NaN;
        double elev = (pos.getY() - level.getSeaLevel()) * cfg.metersPerBlockV;
        return grid.sample(pos.getX() + 0.5, pos.getZ() + 0.5, elev).temperatureC();
    }

    /** Se está chovendo/nevando no modelo sobre o bloco (false fora da área simulada). */
    public static boolean modelPrecipitatingAt(BlockPos pos) {
        return grid != null && grid.contains(pos.getX() + 0.5, pos.getZ() + 0.5)
                && grid.precipRate[grid.cellIndex(pos.getX() + 0.5, pos.getZ() + 0.5)] > 0.1;
    }

    /**
     * No vanilla a neve nunca derrete sozinha (só perto de tochas). Aqui, colunas sorteadas
     * perto dos jogadores perdem uma camada de neve, e o gelo sobre a água volta a ser água,
     * quando a temperatura simulada passa de meltAboveC. Assim a neve do inverno some na primavera.
     */
    private static void meltSnowAndIce(ServerLevel level) {
        RandomSource rnd = level.getRandom();
        for (ServerPlayer p : level.players()) {
            for (int t = 0; t < MELT_TRIES; t++) {
                int x = p.getBlockX() + rnd.nextInt(2 * MELT_RANGE + 1) - MELT_RANGE;
                int z = p.getBlockZ() + rnd.nextInt(2 * MELT_RANGE + 1) - MELT_RANGE;
                if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) continue;
                BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, new BlockPos(x, 0, z));
                double temp = modelTemperatureAt(level, top);
                if (Double.isNaN(temp) || temp <= opt.meltAboveC) continue;
                // Camadas finas de neve não bloqueiam movimento e ficam no próprio "top";
                // as grossas bloqueiam e ficam logo abaixo dele.
                for (BlockPos pos : new BlockPos[] {top, top.below()}) {
                    BlockState st = level.getBlockState(pos);
                    if (st.is(Blocks.SNOW)) {
                        int layers = st.getValue(SnowLayerBlock.LAYERS);
                        level.setBlockAndUpdate(pos, layers > 1
                                ? st.setValue(SnowLayerBlock.LAYERS, layers - 1) : Blocks.AIR.defaultBlockState());
                        break;
                    }
                    if (st.is(Blocks.ICE) && level.getFluidState(pos.below()).is(FluidTags.WATER)) {
                        level.setBlockAndUpdate(pos, Blocks.WATER.defaultBlockState());
                        break;
                    }
                }
            }
        }
    }

    // ---------- Rede ----------

    /** Envia ao jogador o clima local e o recorte de nuvens ao redor dele. */
    private static void sendClimate(ServerPlayer p) {
        if (grid == null || p.level().dimension() != Level.OVERWORLD) return;
        if (!ServerPlayNetworking.canSend(p, ClimatePayload.TYPE)) return;
        ServerLevel level = p.serverLevel();
        ClimateSample s = sampleAt(p);
        int cb = cfg.cellBlocks, r = opt.cloudRadiusCells, size = 2 * r + 1, n = size * size;
        int ocx = Math.floorDiv(p.getBlockX(), cb) - r, ocz = Math.floorDiv(p.getBlockZ(), cb) - r;
        byte[] types = new byte[n], cover = new byte[n];
        short[] base = new short[n], top = new short[n];
        int sea = level.getSeaLevel();
        for (int j = 0; j < size; j++)
            for (int i = 0; i < size; i++) {
                int idx = j * size + i;
                double bx = (ocx + i) * cb + cb / 2.0, bz = (ocz + j) * cb + cb / 2.0;
                if (!grid.contains(bx, bz)) continue;                 // fora da grade: céu limpo (tipo 0)
                int k = grid.cellIndex(bx, bz);
                CloudType t = grid.type[k] == null ? CloudType.CLEAR : grid.type[k];
                types[idx] = (byte) t.ordinal();
                cover[idx] = (byte) Math.round(grid.coverage(k) * 255);
                base[idx] = toBlockY(grid.cloudBaseMeters(k), sea);
                double thickness = (grid.cloudTopMeters(k) - grid.cloudBaseMeters(k)) / opt.cloudThicknessMetersPerBlock;
                top[idx] = (short) (base[idx] + Math.max(2, Math.round(thickness)));
            }
        // Velocidade do transporte em blocos por tick: 1 tick = 3,6 s de jogo, reduzidos
        // pelo mesmo fator usado na advecção da grade.
        double perTick = 3.6 * cfg.windSpeedFactor / cfg.metersPerBlockH();
        ServerPlayNetworking.send(p, new ClimatePayload(
                (float) s.temperatureC(), (float) p.getY(),
                (float) (Physics.LAPSE_ENV / 1000.0 * cfg.metersPerBlockV), (float) s.humidityRel(), (float) s.pressureHPa(),
                (float) s.windSpeed(), (float) s.windFromDeg(), (float) s.precipMmH(),
                (byte) s.cloud().ordinal(), s.thunder(), (float) s.latitudeDeg(),
                (float) (s.windEast() * perTick), (float) (-s.windNorth() * perTick),
                ocx * cb, ocz * cb, cb, size, types, cover, base, top,
                stormsNear(p.getX(), p.getZ(), 1500, ticksSinceStep(level))));
    }

    /** Ticks desde o último passo da simulação (os passos caem nos múltiplos de stepTicks). */
    static long ticksSinceStep(ServerLevel level) { return Math.floorMod(level.getGameTime(), opt.stepTicks); }

    private static List<StormInfo> stormsNear(double x, double z, double range, long ahead) {
        List<StormInfo> out = new java.util.ArrayList<>();
        for (Storm s : grid.storms.storms)
            if (Math.hypot(s.x - x, s.z - z) < range + s.radiusM / cfg.metersPerBlockH() * 6 && out.size() < 64)
                out.add(StormInfo.of(s, cfg.metersPerBlockH(), ahead));
        return out;
    }

    private static final java.util.Map<java.util.UUID, Long> lastMap = new java.util.HashMap<>();

    /** Envia a grade inteira para o mapa-radar (no máximo uma vez por segundo por jogador). */
    private static void sendMap(ServerPlayer p) {
        if (grid == null || p.level().dimension() != Level.OVERWORLD) return;
        long now = p.serverLevel().getGameTime();
        Long last = lastMap.get(p.getUUID());
        if (last != null && now - last < 20 && now >= last) return;
        lastMap.put(p.getUUID(), now);
        int n = grid.nx * grid.nz;
        byte[] t = new byte[n], pr = new byte[n], h = new byte[n], r = new byte[n], u = new byte[n], v = new byte[n],
                ct = new byte[n], cv = new byte[n], ter = new byte[n];
        for (int k = 0; k < n; k++) {
            t[k] = MapPayload.encTemp(grid.temp[k]);
            pr[k] = MapPayload.encPres(grid.pSea[k]);
            h[k] = MapPayload.encHum(Math.min(1, grid.e[k] / Physics.satPressure(grid.temp[k])));
            r[k] = MapPayload.encRain(grid.precipRate[k]);
            u[k] = MapPayload.encWind(grid.u[k]);
            v[k] = MapPayload.encWind(grid.v[k]);
            ct[k] = (byte) (grid.type[k] == null ? 0 : grid.type[k].ordinal());
            cv[k] = (byte) Math.round(grid.coverage(k) * 255);
            ter[k] = MapPayload.encTerrain(grid.water[k], grid.elev[k]);
        }
        List<StormInfo> storms = new java.util.ArrayList<>();
        long ahead = ticksSinceStep(p.serverLevel());
        for (Storm s : grid.storms.storms) if (storms.size() < 64) storms.add(StormInfo.of(s, cfg.metersPerBlockH(), ahead));
        ServerPlayNetworking.send(p, new MapPayload(grid.originCellX, grid.originCellZ, grid.nx, grid.nz, cfg.cellBlocks,
                (float) cfg.halfRangeBlocks, t, pr, h, r, u, v, ct, cv, ter, storms));
    }

    /** Converte altitude (m acima do mar) em Y do mundo, comprimindo o que fica muito alto. */
    private static short toBlockY(double meters, int seaLevel) {
        double y = seaLevel + meters / cfg.metersPerBlockV, ceil = opt.cloudSoftCeilingY;
        if (y > ceil) y = ceil + (y - ceil) * 0.25;
        return (short) Math.round(Math.min(y, 1000));
    }

    /** Hora absoluta de jogo; no Minecraft o tick 0 do dia corresponde às 6h. */
    private static double gameHours(ServerLevel level) {
        return level.getDayTime() / 1000.0 + 6.0;
    }
}
