package br.climate.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Raios dos cumulonimbos maduros (só no cliente, efeito visual). Numa tempestade de verdade
 * a maior parte das descargas fica dentro da nuvem ou vai de uma nuvem a outra, acendendo a
 * nuvem por dentro; uma em cada três ou quatro desce até o chão, quase sempre na região de
 * chuva sob a torre. Cada descarga costuma ter vários "retornos" em sequência, o que faz o
 * clarão piscar.
 *
 * Os raios ao chão são desenhados aqui mesmo (o raio do Minecraft só aparece a menos de 64
 * blocos): um canal principal em zigue-zague, da base da nuvem ao chão, com ramificações
 * que não chegam ao chão, piscando a cada retorno. O trovão chega com atraso proporcional à
 * distância (o som anda ~40 blocos por segundo no jogo). Tudo é só visual e sonoro: o efeito
 * no mundo continua sendo o das trovoadas do jogo, que o servidor controla.
 */
final class LightningFx {
    private LightningFx() {}

    /** Chance por tick de uma descarga numa tempestade totalmente madura. */
    private static final float RATE = 0.012f;
    /** Até onde as descargas são simuladas, em células de nuvem. */
    private static final double RANGE_CELLS = 19;

    private static float flash, flashO;
    /** Retornos ainda por vir da última descarga (ticks até o próximo e quantos faltam). */
    private static int restrikeIn, restrikes;
    private static float restrikeLevel;

    /** Um raio visível: segmentos (x0,y0,z0,x1,y1,z1,largura) e quando nasceu. */
    private record Bolt(List<float[]> segs, long born, int pattern) {}

    private static final List<Bolt> bolts = new ArrayList<>();
    /** Ticks que um raio fica na tela. */
    private static final int BOLT_TICKS = 10;

    /** Brilho extra das nuvens agora (0 = nada). */
    static float flash(float pt) { return Mth.lerp(pt, flashO, flash); }

    static void tick(Minecraft mc) {
        flashO = flash;
        flash = Math.max(0, flash - 0.25f);
        if (restrikes > 0 && --restrikeIn <= 0) {
            flash = Math.max(flash, restrikeLevel * (0.6f + 0.4f * mc.level.getRandom().nextFloat()));
            restrikes--;
            restrikeIn = 2 + mc.level.getRandom().nextInt(3);
        }
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null || level.dimension() != Level.OVERWORLD || !ClientClimate.cloudsActive()) return;
        double[] off = CloudField.offset(0);
        RandomSource rnd = level.getRandom();
        int cell = ClientClimate.cloudSource() != null ? ClientClimate.cloudSource().cellBlocks() : 48;
        double range = RANGE_CELLS * cell;
        float size = cell / 16f;                       // os raios acompanham a escala das nuvens
        for (double[] st : CloudShapes.matureStorms) {
            float mature = (float) st[5];
            if (mature < 0.35f) continue;
            double x = st[0] + off[0], z = st[1] + off[1];
            double dist = Math.hypot(x - player.getX(), z - player.getZ());
            if (dist > range || rnd.nextFloat() > RATE * mature) continue;
            float near = (float) (1 - dist / range);
            if (rnd.nextFloat() < 0.7f) {
                // Dentro da nuvem: acende a nuvem, pisca algumas vezes e ronca baixo, de longe.
                strike(0.25f + 0.55f * near, rnd);
                if (dist < 0.4 * range) level.setSkyFlashTime(2);
                level.playLocalSound(x, st[3], z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER,
                        14f * size, 0.5f + 0.2f * rnd.nextFloat(), true);
            } else {
                // Da nuvem ao chão, sob a torre (onde chove).
                double a = rnd.nextDouble() * Math.PI * 2, r = st[4] * 0.8 * Math.sqrt(rnd.nextDouble());
                double bx = x + Math.cos(a) * r, bz = z + Math.sin(a) * r;
                int cx = Mth.floor(bx) >> 4, cz = Mth.floor(bz) >> 4;
                if (!level.getChunkSource().hasChunk(cx, cz)) continue;
                int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(bx), Mth.floor(bz));
                if (gy >= st[2] - 4) continue;
                bolts.add(new Bolt(channel(bx, st[2] + 2, bz, gy, size, rnd), level.getGameTime(), rnd.nextInt(16)));
                strike(0.35f + 0.6f * near, rnd);
                if (dist < 0.5 * range) level.setSkyFlashTime(2);
                level.playLocalSound(bx, gy, bz, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER,
                        25f * size, 0.8f + 0.2f * rnd.nextFloat(), true);
            }
        }
    }

    /**
     * Canal do raio: desce da base da nuvem em passos de 3 a 5 blocos, desviando para os lados,
     * com ramificações mais finas que se abrem para baixo e morrem no ar.
     */
    private static List<float[]> channel(double x, double top, double z, double ground, float s, RandomSource rnd) {
        List<float[]> segs = new ArrayList<>();
        double y = top;
        float w = 0.45f * s;
        while (y > ground) {
            double ny = Math.max(ground, y - (3 + 2 * rnd.nextDouble()) * s);
            double nx = x + (rnd.nextDouble() - 0.5) * 5 * s, nz = z + (rnd.nextDouble() - 0.5) * 5 * s;
            segs.add(new float[] {(float) x, (float) y, (float) z, (float) nx, (float) ny, (float) nz, w});
            if (rnd.nextFloat() < 0.22f && y - ground > 12 * s) branch(segs, nx, ny, nz, ground, s, rnd);
            x = nx; y = ny; z = nz;
        }
        return segs;
    }

    private static void branch(List<float[]> segs, double x, double y, double z, double ground, float s, RandomSource rnd) {
        double dx = rnd.nextDouble() - 0.5, dz = rnd.nextDouble() - 0.5;
        int n = 3 + rnd.nextInt(6);
        for (int i = 0; i < n && y > ground + 4 * s; i++) {
            double ny = y - (2 + 2 * rnd.nextDouble()) * s;
            double nx = x + (dx * 6 + (rnd.nextDouble() - 0.5) * 3) * s, nz = z + (dz * 6 + (rnd.nextDouble() - 0.5) * 3) * s;
            segs.add(new float[] {(float) x, (float) y, (float) z, (float) nx, (float) ny, (float) nz, 0.25f * s * (1 - i / (float) n)});
            x = nx; y = ny; z = nz;
        }
    }

    /** Desenha os raios visíveis: um núcleo branco e um halo azulado, somando luz. */
    static void render(WorldRenderContext ctx) {
        if (bolts.isEmpty() || ctx.world() == null) return;
        long now = ctx.world().getGameTime();
        Vec3 cam = ctx.camera().getPosition();
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        boolean any = false;
        for (Iterator<Bolt> it = bolts.iterator(); it.hasNext(); ) {
            Bolt b = it.next();
            long age = now - b.born();
            if (age > BOLT_TICKS || age < 0) { it.remove(); continue; }
            // Cada raio pisca com o seu padrão de retornos: aceso, apagado, aceso de novo.
            boolean lit = age < 2 || ((b.pattern() >> (age % 4)) & 1) == 1;
            float a = lit ? 1f : 0.12f;
            a *= 1 - age / (float) (BOLT_TICKS + 1);
            for (float[] sgm : b.segs()) {
                quad(bb, cam, sgm, sgm[6] * 4, 0.45f, 0.55f, 1f, 0.18f * a);   // halo
                quad(bb, cam, sgm, sgm[6], 0.92f, 0.95f, 1f, a);               // núcleo
                any = true;
            }
        }
        if (!any) return;
        MeshData mesh = bb.build();
        if (mesh == null) return;
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA,
                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        BufferUploader.drawWithShader(mesh);
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    /** Fita virada para a câmera ao longo de um segmento. */
    private static void quad(BufferBuilder bb, Vec3 cam, float[] s, float w, float r, float g, float b, float a) {
        Vector3f p0 = new Vector3f((float) (s[0] - cam.x), (float) (s[1] - cam.y), (float) (s[2] - cam.z));
        Vector3f p1 = new Vector3f((float) (s[3] - cam.x), (float) (s[4] - cam.y), (float) (s[5] - cam.z));
        // Raios distantes entram no alcance de visão junto com as nuvens (FarView).
        float k0 = FarView.scale(p0.x, p0.y, p0.z), k1 = FarView.scale(p1.x, p1.y, p1.z);
        p0.mul(k0); p1.mul(k1); w *= 0.5f * (k0 + k1);
        Vector3f dir = new Vector3f(p1).sub(p0);
        Vector3f view = new Vector3f(p0).add(p1).mul(0.5f);
        Vector3f side = dir.cross(view, new Vector3f());
        if (side.lengthSquared() < 1e-6f) return;
        side.normalize(w);
        bb.addVertex(p0.x - side.x, p0.y - side.y, p0.z - side.z).setColor(r, g, b, a);
        bb.addVertex(p0.x + side.x, p0.y + side.y, p0.z + side.z).setColor(r, g, b, a);
        bb.addVertex(p1.x + side.x, p1.y + side.y, p1.z + side.z).setColor(r, g, b, a);
        bb.addVertex(p1.x - side.x, p1.y - side.y, p1.z - side.z).setColor(r, g, b, a);
    }

    private static void strike(float level, RandomSource rnd) {
        flash = Math.max(flash, level);
        restrikeLevel = level;
        restrikes = rnd.nextInt(4);                  // 0 a 3 retornos
        restrikeIn = 2 + rnd.nextInt(3);
    }
}
