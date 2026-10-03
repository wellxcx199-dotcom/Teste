package br.climate.client;

import br.climate.core.Storm;
import br.climate.mod.ClimateMod;
import br.climate.mod.ClimatePayload;
import br.climate.mod.StormInfo;
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
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Funil do tornado: anéis de "voxels" de nuvem girando, finos junto ao chão e largos perto
 * da base da nuvem, com poeira na parte de baixo. A geometria muda a cada quadro (rotação),
 * então é desenhada direto, sem malha guardada. Partículas de poeira e fumaça sobem em espiral.
 */
public final class TornadoRenderer {
    private TornadoRenderer() {}

    private static final ResourceLocation ATLAS =
            ResourceLocation.fromNamespaceAndPath(ClimateMod.ID, "textures/environment/clouds_atlas.png");
    private static final float TU = 1 / 4f, TV = 1 / 2f;
    private static final int TILE_STORM_SIDE = 3;

    /** Mesmo raio usado pelo servidor para os efeitos (StormEffects.funnelRadius). */
    static double funnelRadius(int ef) { return 2.5 + 1.2 * ef; }

    /** Posição estimada agora: a do último pacote mais o deslocamento desde então. */
    static Vec3 position(StormInfo s, float pt) {
        double t = Math.min(ClientClimate.ticksSince(), 400) + pt;
        return new Vec3(s.x() + s.vx() * t, 0, s.z() + s.vz() * t);
    }

    public static void render(WorldRenderContext ctx) {
        ClientLevel level = ctx.world();
        if (level == null || level.dimension() != Level.OVERWORLD || !ClientClimate.active()) return;
        ClimatePayload p = ClientClimate.latest();
        Vec3 cam = ctx.camera().getPosition();
        float pt = ctx.tickCounter().getGameTimeDeltaPartialTick(false);
        double time = level.getGameTime() + pt;
        Vec3 tint = level.getCloudColor(pt);
        var ps = ctx.matrixStack();
        Matrix4f m = ps.last().pose();

        BufferBuilder bb = null;
        for (StormInfo s : p.storms()) {
            if (s.stormKind() != Storm.Kind.TORNADO) continue;
            Vec3 c = position(s, pt);
            if (Math.hypot(c.x - cam.x, c.z - cam.z) > 400) continue;
            if (bb == null) bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            funnel(bb, m, level, s, c, cam, time, tint);
        }
        if (bb == null) return;
        MeshData mesh = bb.build();
        if (mesh == null) return;
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, ATLAS);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        BufferUploader.drawWithShader(mesh);
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    private static void funnel(BufferBuilder bb, Matrix4f m, ClientLevel level, StormInfo s, Vec3 c, Vec3 cam, double time, Vec3 tint) {
        int ef = s.category();
        // Só o MOTION_BLOCKING (e o WORLD_SURFACE) chegam ao cliente; os outros mapas de altura vêm vazios.
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(c.x), (int) Math.floor(c.z));
        // O funil vai do chão até a base da nuvem-parede da supercélula (ou ~50 blocos, sem ela).
        float height = 44 + 5 * ef;
        for (double[] w : CloudShapes.wallClouds)
            if (Math.hypot(w[0] - c.x, w[1] - c.z) < 64) height = (float) Math.max(12, w[2] - ground + 2);
        double r0 = funnelRadius(ef) * 0.45, r1 = funnelRadius(ef) * 2.6;
        float fade = Math.min(1, s.intensity() * 3 + 0.35f);
        for (float y = 0; y < height; y += 2.5f) {
            float t = y / height;
            double radius = r0 + (r1 - r0) * Math.pow(t, 1.7);
            // "Corda": o funil se inclina e ondula com a altura.
            double ox = Math.sin(time * 0.013 + t * 2.8) * t * 4, oz = Math.cos(time * 0.011 + t * 2.2) * t * 3;
            int n = 7 + (int) Math.round(radius * 1.6);
            double spin = time * (0.22 - 0.1 * t) + y * 0.21;
            float size = (float) (1.3 + 1.6 * t);
            // Cor: poeira marrom embaixo, cinza-chumbo em cima.
            float r = lerp(0.55f, 0.60f, t), g = lerp(0.48f, 0.62f, t), b = lerp(0.40f, 0.66f, t);
            for (int q = 0; q < n; q++) {
                double a = spin + q * Math.PI * 2 / n;
                double jitter = 0.85 + 0.3 * Math.sin(q * 12.9898 + y * 78.233);
                double x = c.x + ox + Math.cos(a) * radius * jitter, z = c.z + oz + Math.sin(a) * radius * jitter;
                cube(bb, m, cam, x, ground + y + Math.sin(a * 3 + time * 0.1) * 0.6, z, size,
                        (float) (r * tint.x), (float) (g * tint.y), (float) (b * tint.z), 0.82f * fade);
            }
        }
        // Nuvem de detritos junto ao chão.
        int nd = 12 + ef * 3;
        double rd = funnelRadius(ef) * 1.8;
        for (int q = 0; q < nd; q++) {
            double a = -time * 0.15 + q * Math.PI * 2 / nd;
            double x = c.x + Math.cos(a) * rd * (0.7 + 0.5 * Math.sin(q * 3.7)), z = c.z + Math.sin(a) * rd * (0.7 + 0.5 * Math.cos(q * 2.3));
            cube(bb, m, cam, x, ground + 0.5 + Math.abs(Math.sin(q * 1.7 + time * 0.07)) * 3, z, 2.2f,
                    (float) (0.52 * tint.x), (float) (0.46 * tint.y), (float) (0.38 * tint.z), 0.5f * fade);
        }
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private static void cube(BufferBuilder bb, Matrix4f m, Vec3 cam, double cx, double cy, double cz, float s,
                             float r, float g, float b, float a) {
        float x0 = (float) (cx - cam.x - s / 2), y0 = (float) (cy - cam.y - s / 2), z0 = (float) (cz - cam.z - s / 2);
        float x1 = x0 + s, y1 = y0 + s, z1 = z0 + s;
        quad(bb, m, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, r, g, b, a);                    // topo
        quad(bb, m, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r * .7f, g * .7f, b * .7f, a);  // base
        quad(bb, m, x0, y1, z0, x0, y0, z0, x1, y0, z0, x1, y1, z0, r * .8f, g * .8f, b * .8f, a);
        quad(bb, m, x1, y1, z1, x1, y0, z1, x0, y0, z1, x0, y1, z1, r * .8f, g * .8f, b * .8f, a);
        quad(bb, m, x0, y1, z1, x0, y0, z1, x0, y0, z0, x0, y1, z0, r * .9f, g * .9f, b * .9f, a);
        quad(bb, m, x1, y1, z0, x1, y0, z0, x1, y0, z1, x1, y1, z1, r * .9f, g * .9f, b * .9f, a);
    }

    private static void quad(BufferBuilder bb, Matrix4f m, float x0, float y0, float z0, float x1, float y1, float z1,
                             float x2, float y2, float z2, float x3, float y3, float z3, float r, float g, float b, float a) {
        float u = (TILE_STORM_SIDE % 4) * TU, v = (TILE_STORM_SIDE / 4) * TV;
        bb.addVertex(m, x0, y0, z0).setUv(u, v).setColor(r, g, b, a);
        bb.addVertex(m, x1, y1, z1).setUv(u, v + TV).setColor(r, g, b, a);
        bb.addVertex(m, x2, y2, z2).setUv(u + TU, v + TV).setColor(r, g, b, a);
        bb.addVertex(m, x3, y3, z3).setUv(u + TU, v).setColor(r, g, b, a);
    }

    /** Poeira e fumaça subindo em espiral ao redor dos tornados próximos. */
    public static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || level.dimension() != Level.OVERWORLD || !ClientClimate.active()) return;
        RandomSource rnd = level.getRandom();
        for (StormInfo s : ClientClimate.latest().storms()) {
            if (s.stormKind() != Storm.Kind.TORNADO) continue;
            Vec3 c = position(s, 0);
            if (Math.hypot(c.x - mc.player.getX(), c.z - mc.player.getZ()) > 160) continue;
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(c.x), (int) Math.floor(c.z));
            double r = funnelRadius(s.category());
            for (int i = 0; i < 6 + s.category() * 2; i++) {
                double a = rnd.nextDouble() * Math.PI * 2, d = r * (0.8 + rnd.nextDouble() * 1.5);
                double x = c.x + Math.cos(a) * d, z = c.z + Math.sin(a) * d;
                double vx = -Math.sin(a) * 0.5 - Math.cos(a) * 0.1, vz = Math.cos(a) * 0.5 - Math.sin(a) * 0.1;
                level.addParticle(i % 3 == 0 ? ParticleTypes.CAMPFIRE_COSY_SMOKE : ParticleTypes.POOF,
                        x, ground + 0.5 + rnd.nextDouble() * 2, z, vx, 0.25 + rnd.nextDouble() * 0.3, vz);
            }
        }
    }
}
