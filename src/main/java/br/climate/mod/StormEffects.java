package br.climate.mod;

import br.climate.core.ClimateConfig;
import br.climate.core.ClimateGrid;
import br.climate.core.Storm;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Efeitos dos fenômenos no mundo, a cada tick:
 * tornados puxam, giram e levantam entidades e arrancam folhas e plantas;
 * ventos de furacão empurram jogadores que estão a céu aberto.
 *
 * Escala: um bloco vale 500 m na horizontal, então um tornado real (50 a 500 m) caberia
 * num bloco só. Para ser visível e jogável, o funil tem alguns blocos de raio, crescendo
 * com a categoria EF; a mesma regra é usada no desenho do cliente (TornadoRenderer).
 */
final class StormEffects {
    private StormEffects() {}

    /** Raio visual/de efeito do funil, em blocos, pela categoria EF. */
    static double funnelRadius(int ef) { return 2.5 + 1.2 * ef; }

    static void tick(ServerLevel level, ClimateGrid grid, ClimateConfig cfg, ClimateSettings.Mod opt) {
        if (grid == null) return;
        long time = level.getGameTime();
        for (Storm s : grid.storms.storms) {
            if (s.kind == Storm.Kind.TORNADO) tornado(level, s, opt, time);
        }
        if (opt.strongWindPushesPlayers && time % 5 == 0) strongWind(level, grid);
    }

    private static void tornado(ServerLevel level, Storm t, ClimateSettings.Mod opt, long time) {
        // Posição entre passos da simulação: anda com a velocidade do último passo.
        double x = t.x, z = t.z;
        if (!level.hasChunkAt(BlockPos.containing(x, 0, z))) return;
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
        double r = funnelRadius(t.category());
        double strength = 0.4 + 0.6 * t.intensity();

        if (opt.tornadoPushesEntities) {
            double reach = r * 3;
            AABB box = new AABB(x - reach, ground - 4, z - reach, x + reach, ground + 60, z + reach);
            for (Entity e : level.getEntitiesOfClass(Entity.class, box, Entity::isPushable)) {
                if (e instanceof ServerPlayer p && (p.isCreative() || p.isSpectator())) continue;
                pull(e, x, z, ground, r, reach, strength);
            }
            // Itens, flechas, etc. também voam (não são "empurráveis" para o jogo).
            for (Entity e : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box, e -> true))
                pull(e, x, z, ground, r, reach, strength);
        }

        if (opt.tornadoBreaksBlocks && time % 3 == 0) {
            RandomSource rnd = level.getRandom();
            int tries = 3 + t.category() * 2;
            for (int i = 0; i < tries; i++) {
                double a = rnd.nextDouble() * Math.PI * 2, d = Math.sqrt(rnd.nextDouble()) * r * 1.6;
                int bx = (int) Math.floor(x + Math.cos(a) * d), bz = (int) Math.floor(z + Math.sin(a) * d);
                BlockPos top = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, new BlockPos(bx, 0, bz)).below();
                for (BlockPos pos : new BlockPos[] {top, top.above()}) {
                    if (pos.getY() > ground + 40) continue;
                    BlockState st = level.getBlockState(pos);
                    if (fragile(st)) { level.destroyBlock(pos, false); break; }
                }
            }
        }
    }

    /** Puxa para o centro, gira (anti-horário) e levanta, mais forte perto do funil. */
    private static void pull(Entity e, double x, double z, int ground, double r, double reach, double strength) {
        double dx = x - e.getX(), dz = z - e.getZ();
        double d = Math.max(0.5, Math.hypot(dx, dz));
        if (d > reach) return;
        double near = 1 - d / reach;                               // 0 na borda, 1 no centro
        double inward = 0.06 * strength * near, swirl = 0.18 * strength * near;
        double lift = d < r * 1.3 ? 0.11 * strength * (1 - d / (r * 1.3)) + 0.02 : 0.02 * near;
        if (e.getY() > ground + 30) lift = -0.02;                  // lá em cima, é arremessado para fora
        Vec3 push = new Vec3(dx / d * inward - dz / d * swirl, lift, dz / d * inward + dx / d * swirl);
        e.push(push.x, push.y, push.z);
        e.hurtMarked = true;                                       // sincroniza a velocidade com o cliente
        e.resetFallDistance();
    }

    /** Só blocos naturais e leves: folhas, plantas pequenas, flores e neve. */
    private static boolean fragile(BlockState st) {
        return st.is(BlockTags.LEAVES) || st.is(BlockTags.FLOWERS) || st.is(Blocks.SHORT_GRASS)
                || st.is(Blocks.TALL_GRASS) || st.is(Blocks.FERN) || st.is(Blocks.LARGE_FERN)
                || st.is(Blocks.SNOW) || st.is(Blocks.DEAD_BUSH) || st.is(Blocks.SWEET_BERRY_BUSH);
    }

    /** Vento acima de 25 m/s empurra quem está a céu aberto, na direção do vento. */
    private static void strongWind(ServerLevel level, ClimateGrid grid) {
        for (ServerPlayer p : level.players()) {
            if (p.isCreative() || p.isSpectator() || !grid.contains(p.getX(), p.getZ())) continue;
            if (!level.canSeeSky(p.blockPosition().above())) continue;
            int k = grid.cellIndex(p.getX(), p.getZ());
            double u = grid.u[k], v = grid.v[k], sp = Math.hypot(u, v);
            if (sp < 25) continue;
            double f = Math.min(0.06, 0.02 * (sp - 25) / 15);
            p.push(u / sp * f, 0, -v / sp * f);                    // v aponta para o norte (z negativo)
            p.hurtMarked = true;
        }
    }
}
