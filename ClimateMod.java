package br.climate.mod;

import br.climate.core.*;
import com.mojang.brigadier.Command;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

public final class ClimateMod implements ModInitializer {
    public static final String ID = "climamod";
    private static final int STEP_TICKS = 200;       // simula a cada 10 s reais
    private static final int GRID = 96;              // 96x96 células de 16 blocos = 1536 blocos de lado

    private static ClimateGrid grid;
    private static ClimateConfig cfg;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            cfg = new ClimateConfig();
            int half = GRID / 2;
            int ox = level.getSharedSpawnPos().getX() / cfg.cellBlocks - half;
            int oz = level.getSharedSpawnPos().getZ() / cfg.cellBlocks - half;
            grid = new ClimateGrid(GRID, GRID, ox, oz, cfg, new MinecraftTerrain(level, cfg));
            grid.initialize(gameHours(level));
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> grid = null);

        ServerTickEvents.END_WORLD_TICK.register(level -> {
            if (grid == null || level.dimension() != Level.OVERWORLD) return;
            if (level.getGameTime() % STEP_TICKS != 0) return;
            followPlayer(level);
            grid.step(STEP_TICKS / 1000.0, gameHours(level)); // 1000 ticks = 1 hora de jogo
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) ->
            dispatcher.register(Commands.literal("clima").executes(ctx -> {
                ServerPlayer p = ctx.getSource().getPlayerOrException();
                if (grid == null) { ctx.getSource().sendFailure(Component.literal("Clima não iniciado.")); return 0; }
                ServerLevel lv = p.serverLevel();
                double elev = (p.getY() - lv.getSeaLevel()) * cfg.metersPerBlockV;
                ClimateSample s = grid.sample(p.getX(), p.getZ(), elev);
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

    /** Se o (primeiro) jogador se afastou do centro, desloca a janela para centralizá-lo. */
    private static void followPlayer(ServerLevel level) {
        if (level.players().isEmpty()) return;
        ServerPlayer p = level.players().get(0);
        int pcx = (int) Math.floor(p.getX() / cfg.cellBlocks);
        int pcz = (int) Math.floor(p.getZ() / cfg.cellBlocks);
        int cx = grid.originCellX + GRID / 2, cz = grid.originCellZ + GRID / 2;
        if (Math.abs(pcx - cx) > GRID / 4 || Math.abs(pcz - cz) > GRID / 4)
            grid.recenter(pcx - GRID / 2, pcz - GRID / 2, gameHours(level));
    }

    /** Hora absoluta de jogo; no Minecraft o tick 0 do dia corresponde às 6h. */
    private static double gameHours(ServerLevel level) {
        return level.getDayTime() / 1000.0 + 6.0;
    }
}
