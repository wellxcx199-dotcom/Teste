package br.climate.client;

import br.climate.core.CloudType;
import br.climate.mod.ClimateMod;
import br.climate.mod.ClimatePayload;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/**
 * Desenha as nuvens simuladas com formas em voxel (CloudShapes) e texturas em pixel art
 * (assets/climamod/textures/environment/clouds_atlas.png).
 *
 * A malha é montada só quando algo muda (novo pacote do servidor, a câmera andou ou passou
 * um tempo) e fica guardada na placa de vídeo; entre uma montagem e outra o vento apenas
 * desloca a malha inteira. O desenho é feito como nas nuvens do Minecraft: uma passada só
 * de profundidade e outra de cor, para que cada nuvem mostre apenas a superfície da frente.
 * Fiapos de cirro, cortinas de chuva e nuvens muito próximas vão numa segunda malha,
 * translúcida.
 */
public final class CloudRenderer {
    private CloudRenderer() {}

    private static final ResourceLocation ATLAS =
            ResourceLocation.fromNamespaceAndPath(ClimateMod.ID, "textures/environment/clouds_atlas.png");
    // Quadros do atlas, na ordem de tools/gerar_texturas_nuvens.py.
    private static final int TILE_TOP = 0, TILE_SIDE = 1, TILE_BOTTOM = 2, TILE_STORM_SIDE = 3,
            TILE_STORM_BOTTOM = 4, TILE_CIRRUS = 5, TILE_RAIN = 6, TILE_ANVIL = 7;
    /** Altura (em blocos) coberta por uma repetição da textura nas laterais. */
    private static final float TILE_BLOCKS = 4f;
    private static final float TU = 1 / 4f, TV = 1 / 2f;   // tamanho de um quadro no atlas (4x2 quadros)
    private static final CloudType[] TYPES = CloudType.values();

    private static VertexBuffer solid, glass;
    private static boolean solidEmpty = true, glassEmpty = true;
    private static ByteBufferBuilder solidBytes, glassBytes;
    private static ClimatePayload builtFor;
    private static double builtCamX, builtCamY, builtCamZ, builtOffX, builtOffZ;
    private static long builtAt = Long.MIN_VALUE;
    private static boolean builtInside;

    public static void render(WorldRenderContext ctx) {
        ClientClimate.setInsideCloud(false);
        ClientLevel level = ctx.world();
        if (level == null || level.dimension() != Level.OVERWORLD || !ClientClimate.cloudsActive()) return;
        // Respeita a opção de vídeo "Nuvens: Desligadas".
        if (Minecraft.getInstance().options.getCloudsType() == CloudStatus.OFF) return;
        ClimatePayload p = ClientClimate.cloudSource();

        Vec3 cam = ctx.camera().getPosition();
        float pt = ctx.tickCounter().getGameTimeDeltaPartialTick(false);
        double t = Math.min(ClientClimate.ticksSince(), 20 * 60) + pt;
        double offX = p.driftX() * t, offZ = p.driftZ() * t;

        long now = level.getGameTime();
        double moved = Math.max(Math.abs(cam.x - builtCamX), Math.max(Math.abs(cam.y - builtCamY), Math.abs(cam.z - builtCamZ)));
        if (p != builtFor || moved > 4 || now - builtAt > 40 || now < builtAt) {
            rebuild(p, level, cam, offX, offZ, pt);
            builtFor = p; builtAt = now;
            builtCamX = cam.x; builtCamY = cam.y; builtCamZ = cam.z; builtOffX = offX; builtOffZ = offZ;
        }
        ClientClimate.setInsideCloud(builtInside);
        if (solidEmpty && glassEmpty) return;

        PoseStack ps = ctx.matrixStack();
        ps.pushPose();
        // A malha foi montada relativa à câmera daquele momento; o vento a desloca desde então.
        ps.translate(builtCamX - cam.x + (offX - builtOffX), builtCamY - cam.y, builtCamZ - cam.z + (offZ - builtOffZ));
        // No 1.21.1 o PoseStack do evento vem sem a rotação da câmera: ela está na matriz
        // model-view do RenderSystem. O VertexBuffer substitui essa matriz pela que receber,
        // então é preciso combinar as duas; senão as nuvens giram junto com a câmera.
        var pose = new org.joml.Matrix4f(RenderSystem.getModelViewMatrix()).mul(ps.last().pose());
        var proj = ctx.projectionMatrix();

        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, ATLAS);
        ShaderInstance shader = GameRenderer.getPositionTexColorShader();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.disableCull();
        if (!solidEmpty) {
            solid.bind();
            RenderSystem.colorMask(false, false, false, false);   // 1ª passada: só profundidade
            RenderSystem.depthMask(true);
            solid.drawWithShader(pose, proj, shader);
            RenderSystem.colorMask(true, true, true, true);       // 2ª passada: só a frente ganha cor
            RenderSystem.depthMask(false);
            solid.drawWithShader(pose, proj, shader);
        }
        if (!glassEmpty) {
            RenderSystem.depthMask(false);
            glass.bind();
            glass.drawWithShader(pose, proj, shader);
        }
        VertexBuffer.unbind();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        ps.popPose();
    }

    // ---------- Montagem da malha ----------

    /** Cor, transparência e destino (malha sólida ou translúcida) de um trecho de nuvem. */
    private record Look(float r, float g, float b, float a, boolean glass) {}

    private static void rebuild(ClimatePayload p, ClientLevel level, Vec3 cam, double offX, double offZ, float pt) {
        if (solid == null) {
            solid = new VertexBuffer(VertexBuffer.Usage.STATIC);
            glass = new VertexBuffer(VertexBuffer.Usage.STATIC);
            solidBytes = new ByteBufferBuilder(1 << 20);
            glassBytes = new ByteBufferBuilder(1 << 18);
        }
        BufferBuilder sb = new BufferBuilder(solidBytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        BufferBuilder gb = new BufferBuilder(glassBytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        builtInside = false;

        CloudShapes s = new CloudShapes(p);
        Vec3 tint = level.getCloudColor(pt);              // escurece à noite e com chuva, como o vanilla
        float[] fog = RenderSystem.getShaderFogColor();   // cor do horizonte, para as nuvens distantes
        double radius = s.cols * s.colW / 2;
        double cx = s.originX + offX, cz = s.originZ + offZ; // canto do recorte já com o vento

        for (int gz = 0; gz < s.cols; gz++)
            for (int gx = 0; gx < s.cols; gx++) {
                int k = s.index(gx, gz);
                for (int layer = 0; layer < 2; layer++) {
                    byte mat = layer == 0 ? s.matA[k] : s.matB[k];
                    if (mat == CloudShapes.EMPTY) continue;
                    float y0 = layer == 0 ? s.y0a[k] : s.y0b[k], y1 = layer == 0 ? s.y1a[k] : s.y1b[k];
                    double x0 = cx + gx * s.colW, z0 = cz + gz * s.colW, x1 = x0 + s.colW, z1 = z0 + s.colW;
                    Look look = look(TYPES[mat], layer == 1, cam, x0, y0, z0, x1, y1, z1, radius, tint, fog);
                    if (look == null) continue;
                    emitColumn(look.glass() ? gb : sb, s, k, gx, gz, layer, TYPES[mat], y0, y1, x0, z0, x1, z1, look, cam);
                }
            }
        // Cortinas de chuva: dois planos cruzados por coluna, sumindo para baixo.
        for (int q = 0; q < s.rain.size(); q++) {
            int gx = s.rain.get(q)[0], gz = s.rain.get(q)[1];
            float top = s.rainTop.get(q);
            double x0 = cx + gx * s.colW, z0 = cz + gz * s.colW, x1 = x0 + s.colW, z1 = z0 + s.colW;
            double dist = Math.hypot((x0 + x1) / 2 - cam.x, (z0 + z1) / 2 - cam.z);
            if (dist > radius * 0.9) continue;
            float a = 0.75f * (float) Math.min(1, (radius * 0.9 - dist) / (0.2 * radius));
            float c = (float) (0.85 * tint.x);
            rainQuad(gb, cam, x0, z0, x1, z1, top, c, a);
            rainQuad(gb, cam, x0, z1, x1, z0, top, c, a);
        }

        solidEmpty = upload(solid, sb);
        glassEmpty = upload(glass, gb);
    }

    /** Envia a malha para a placa de vídeo; retorna true se ela estava vazia. */
    private static boolean upload(VertexBuffer vb, BufferBuilder bb) {
        MeshData mesh = bb.build();
        if (mesh == null) return true;
        vb.bind();
        vb.upload(mesh);                                    // o próprio upload fecha o MeshData
        VertexBuffer.unbind();
        return false;
    }

    private static Look look(CloudType type, boolean anvil, Vec3 cam, double x0, double y0, double z0,
                             double x1, double y1, double z1, double radius, Vec3 tint, float[] fog) {
        float base, a;
        boolean translucent = false;
        switch (type) {
            case CUMULUS -> { base = 1.00f; a = 0.95f; }
            case STRATUS -> { base = 0.93f; a = 0.90f; }
            case NIMBOSTRATUS -> { base = 0.92f; a = 0.96f; }
            case CUMULONIMBUS -> { base = anvil ? 1.0f : 0.96f; a = anvil ? 0.92f : 0.97f; }
            case CIRRUS -> { base = 1.00f; a = 1.00f; translucent = true; }
            default -> { return null; }
        }
        double dist = Math.hypot((x0 + x1) / 2 - cam.x, (z0 + z1) / 2 - cam.z);
        if (dist >= radius) return null;
        float fade = (float) Math.min(1, (radius - dist) / (0.25 * radius));
        // Perto da câmera a nuvem fica quase transparente: de dentro, uma nuvem é neblina,
        // não uma parede. A neblina em si é feita pelo FogRendererMixin.
        double near = distanceToBox(cam, x0, y0, z0, x1, y1, z1);
        if (near <= 0) builtInside = true;
        fade *= (float) Math.min(1, Math.max(0.1, (near - 3) / 30));
        // Perspectiva aérea: quanto mais longe, mais a nuvem se confunde com o horizonte.
        double dy = y0 - cam.y;
        float haze = (float) Math.pow(Math.min(1, Math.sqrt(dist * dist + dy * dy) / (radius * 1.1)), 1.5) * 0.85f;
        float r = Mth.lerp(haze, (float) (base * tint.x), fog[0]);
        float g = Mth.lerp(haze, (float) (base * tint.y), fog[1]);
        float b = Mth.lerp(haze, (float) (base * tint.z), fog[2]);
        return new Look(r, g, b, a * fade, translucent || fade < 0.98f);
    }

    /** Topo, base e as laterais expostas (não cobertas pelas colunas vizinhas) de um trecho. */
    private static void emitColumn(BufferBuilder bb, CloudShapes s, int k, int gx, int gz, int layer, CloudType type,
                                   float y0, float y1, double x0, double z0, double x1, double z1, Look c, Vec3 cam) {
        boolean cirrus = type == CloudType.CIRRUS;
        boolean storm = type == CloudType.NIMBOSTRATUS || (type == CloudType.CUMULONIMBUS && layer == 0);
        int topTile = cirrus ? TILE_CIRRUS : layer == 1 ? TILE_ANVIL : TILE_TOP;
        int bottomTile = cirrus ? TILE_CIRRUS : storm ? TILE_STORM_BOTTOM : TILE_BOTTOM;
        float X0 = (float) (x0 - cam.x), X1 = (float) (x1 - cam.x), Z0 = (float) (z0 - cam.z), Z1 = (float) (z1 - cam.z);
        float Y0 = (float) (y0 - cam.y), Y1 = (float) (y1 - cam.y);

        // Topo e base, exceto onde encostam no outro trecho da mesma coluna (torre e bigorna).
        if (!(layer == 0 && s.matB[k] != CloudShapes.EMPTY && s.y0b[k] <= y1))
            face(bb, topTile, X0, Y1, Z0, X0, Y1, Z1, X1, Y1, Z1, X1, Y1, Z0, c, 1.0f);
        if (!(layer == 1 && s.matA[k] != CloudShapes.EMPTY && s.y1a[k] >= y0))
            face(bb, bottomTile, X0, Y0, Z0, X1, Y0, Z0, X1, Y0, Z1, X0, Y0, Z1, c, 0.7f);

        if (cirrus) return;                                    // fiapos: só topo e base, sem laterais
        int[][] dirs = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};    // norte, sul, oeste, leste
        float[] shade = {0.8f, 0.8f, 0.9f, 0.9f};
        for (int d = 0; d < 4; d++) {
            int nx = gx + dirs[d][0], nz = gz + dirs[d][1];
            List<float[]> parts = new ArrayList<>();
            parts.add(new float[] {y0, y1});
            if (!cirrus && nx >= 0 && nz >= 0 && nx < s.cols && nz < s.cols) {
                int n = s.index(nx, nz);
                // Partes expostas = [y0, y1] menos os trechos do vizinho (cirros não escondem nada).
                if (s.matA[n] != CloudShapes.EMPTY && s.matA[n] != CloudType.CIRRUS.ordinal())
                    parts = subtract(parts, s.y0a[n], s.y1a[n]);
                if (s.matB[n] != CloudShapes.EMPTY) parts = subtract(parts, s.y0b[n], s.y1b[n]);
            }
            for (float[] part : parts)
                sideSegments(bb, s, k, type, storm, d, part[0], part[1], y0, y1, X0, X1, Z0, Z1, c, shade[d], cam);
        }
    }

    private static List<float[]> subtract(List<float[]> segs, float lo, float hi) {
        List<float[]> out = new ArrayList<>();
        for (float[] seg : segs) {
            if (hi <= seg[0] || lo >= seg[1]) { out.add(seg); continue; }
            if (lo > seg[0]) out.add(new float[] {seg[0], lo});
            if (hi < seg[1]) out.add(new float[] {hi, seg[1]});
        }
        return out;
    }

    /** Divide uma lateral em pedaços de TILE_BLOCKS de altura, cada um com a textura inteira. */
    private static void sideSegments(BufferBuilder bb, CloudShapes s, int k, CloudType type, boolean storm,
                                     int dir, float lo, float hi, float y0, float y1,
                                     float X0, float X1, float Z0, float Z1, Look c, float shade, Vec3 cam) {
        float towerB = s.towerBase[k], towerT = s.towerTop[k];
        for (float yt = hi; yt > lo + 0.01f; yt -= TILE_BLOCKS) {
            float yb = Math.max(lo, yt - TILE_BLOCKS);
            // Degradê vertical: a parte de baixo da nuvem é mais escura que o topo.
            float fT = 0.78f + 0.22f * (yt - y0) / Math.max(1, y1 - y0);
            float fB = 0.78f + 0.22f * (yb - y0) / Math.max(1, y1 - y0);
            int tile = TILE_SIDE;
            if (type == CloudType.CIRRUS) tile = TILE_CIRRUS;
            else if (type == CloudType.NIMBOSTRATUS) tile = TILE_STORM_SIDE;
            else if (storm && towerT > towerB && (yt - towerB) / (towerT - towerB) < 0.45f) tile = TILE_STORM_SIDE;
            float vFrac = (yt - yb) / TILE_BLOCKS;             // o último pedaço pode ser menor que o quadro
            float Yt = (float) (yt - cam.y), Yb = (float) (yb - cam.y);
            switch (dir) {
                case 0 -> sideQuad(bb, tile, X1, Z0, X0, Z0, Yb, Yt, vFrac, c, shade * fB, shade * fT);
                case 1 -> sideQuad(bb, tile, X0, Z1, X1, Z1, Yb, Yt, vFrac, c, shade * fB, shade * fT);
                case 2 -> sideQuad(bb, tile, X0, Z0, X0, Z1, Yb, Yt, vFrac, c, shade * fB, shade * fT);
                default -> sideQuad(bb, tile, X1, Z1, X1, Z0, Yb, Yt, vFrac, c, shade * fB, shade * fT);
            }
        }
    }

    // ---------- Vértices ----------

    private static float u0(int tile) { return (tile % 4) * TU; }
    private static float v0(int tile) { return (tile / 4) * TV; }

    private static void face(BufferBuilder bb, int tile, float x0, float y0, float z0, float x1, float y1, float z1,
                             float x2, float y2, float z2, float x3, float y3, float z3, Look c, float shade) {
        float u = u0(tile), v = v0(tile);
        vtx(bb, x0, y0, z0, u, v, c, shade);
        vtx(bb, x1, y1, z1, u, v + TV, c, shade);
        vtx(bb, x2, y2, z2, u + TU, v + TV, c, shade);
        vtx(bb, x3, y3, z3, u + TU, v, c, shade);
    }

    /** Lateral vertical de (xa,za) a (xb,zb); a textura vai do topo (v=0) para baixo. */
    private static void sideQuad(BufferBuilder bb, int tile, float xa, float za, float xb, float zb,
                                 float yb, float yt, float vFrac, Look c, float shadeB, float shadeT) {
        float u = u0(tile), v = v0(tile);
        vtx(bb, xa, yt, za, u, v, c, shadeT);
        vtx(bb, xa, yb, za, u, v + TV * vFrac, c, shadeB);
        vtx(bb, xb, yb, zb, u + TU, v + TV * vFrac, c, shadeB);
        vtx(bb, xb, yt, zb, u + TU, v, c, shadeT);
    }

    private static void vtx(BufferBuilder bb, float x, float y, float z, float u, float v, Look c, float shade) {
        bb.addVertex(x, y, z).setUv(u, v).setColor(Math.min(1, c.r() * shade), Math.min(1, c.g() * shade),
                Math.min(1, c.b() * shade), c.a());
    }

    private static void rainQuad(BufferBuilder bb, Vec3 cam, double xa, double za, double xb, double zb,
                                 float top, float c, float alpha) {
        float Xa = (float) (xa - cam.x), Za = (float) (za - cam.z), Xb = (float) (xb - cam.x), Zb = (float) (zb - cam.z);
        float u = u0(TILE_RAIN), v = v0(TILE_RAIN), cb = Math.min(1, c * 1.06f);
        int segs = (int) (CloudShapes.RAIN_DEPTH / TILE_BLOCKS);
        for (int seg = 0; seg < segs; seg++) {
            float yt = top - seg * TILE_BLOCKS, yb = yt - TILE_BLOCKS;
            float aT = alpha * (1 - seg / (float) segs), aB = alpha * (1 - (seg + 1) / (float) segs);
            float Yt = (float) (yt - cam.y), Yb = (float) (yb - cam.y);
            bb.addVertex(Xa, Yt, Za).setUv(u, v).setColor(c, c, cb, aT);
            bb.addVertex(Xa, Yb, Za).setUv(u, v + TV).setColor(c, c, cb, aB);
            bb.addVertex(Xb, Yb, Zb).setUv(u + TU, v + TV).setColor(c, c, cb, aB);
            bb.addVertex(Xb, Yt, Zb).setUv(u + TU, v).setColor(c, c, cb, aT);
        }
    }

    /** Distância da câmera até uma caixa (0 se a câmera está dentro dela). */
    private static double distanceToBox(Vec3 c, double x0, double y0, double z0, double x1, double y1, double z1) {
        double dx = Math.max(Math.max(x0 - c.x, 0), c.x - x1);
        double dy = Math.max(Math.max(y0 - c.y, 0), c.y - y1);
        double dz = Math.max(Math.max(z0 - c.z, 0), c.z - z1);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
