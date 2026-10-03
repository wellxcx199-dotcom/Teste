package br.climate.client;

import br.climate.core.CloudType;
import br.climate.mod.ClimatePayload;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.Arrays;

/**
 * Desenha as nuvens simuladas como blocos translúcidos, no estilo das nuvens "fancy" do
 * vanilla, mas com forma, altura e cor que dependem do tipo de nuvem de cada célula:
 * cúmulos são tufos soltos, estratos e nimbostratos são camadas contínuas, cumulonimbos
 * são torres com bigorna no topo e cirros são faixas finas e altas.
 */
public final class CloudRenderer {
    private CloudRenderer() {}

    private static final CloudType[] TYPES = CloudType.values();

    public static void render(WorldRenderContext ctx) {
        ClientClimate.setInsideCloud(false);
        if (!ClientClimate.active()) return;
        ClientLevel level = ctx.world();
        if (level == null || level.dimension() != Level.OVERWORLD) return;
        // Respeita a opção de vídeo "Nuvens: Desligadas".
        if (Minecraft.getInstance().options.getCloudsType() == CloudStatus.OFF) return;
        ClimatePayload p = ClientClimate.latest();

        Vec3 cam = ctx.camera().getPosition();
        float pt = ctx.tickCounter().getGameTimeDeltaPartialTick(false);
        double t = ClientClimate.ticksSince() + pt;
        double offX = p.driftX() * t, offZ = p.driftZ() * t;
        Vec3 tint = level.getCloudColor(pt);          // escurece à noite e com chuva, como o vanilla
        float[] fog = RenderSystem.getShaderFogColor(); // cor do horizonte, para as nuvens distantes

        PoseStack ps = ctx.matrixStack();
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = ps.last().pose();
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        int size = p.size(), cb = p.cellBlocks();
        double radius = (size / 2.0) * cb;
        Frustum frustum = ctx.frustum();
        // Translúcidos precisam ser desenhados do mais longe para o mais perto; senão uma
        // nuvem próxima, desenhada antes, apaga a de trás. Chave = distância² e índice.
        long[] order = new long[size * size];
        int count = 0;
        for (int j = 0; j < size; j++)
            for (int i = 0; i < size; i++) {
                int idx = j * size + i;
                CloudType type = TYPES[p.types()[idx]];
                if (type == CloudType.CLEAR || type == CloudType.FOG) continue; // neblina é feita pelo FogRenderer
                double x0 = p.originX() + i * cb + offX, z0 = p.originZ() + j * cb + offZ;
                double ddx = x0 + cb / 2.0 - cam.x, ddz = z0 + cb / 2.0 - cam.z;
                if (ddx * ddx + ddz * ddz >= radius * radius) continue;
                double grow = cb * 0.45;                                       // bigorna passa da célula
                if (frustum != null && !frustum.isVisible(new AABB(x0 - grow, p.baseY()[idx], z0 - grow,
                        x0 + cb + grow, p.topY()[idx], z0 + cb + grow))) continue;
                double dy = (p.baseY()[idx] + p.topY()[idx]) / 2.0 - cam.y;
                float d2 = (float) (ddx * ddx + ddz * ddz + dy * dy);
                order[count++] = ((long) Float.floatToIntBits(d2) << 32) | idx;
            }
        Arrays.sort(order, 0, count);
        boolean inside = false;

        for (int q = count - 1; q >= 0; q--) {
            int idx = (int) order[q], i = idx % size, j = idx / size;
            CloudType type = TYPES[p.types()[idx]];
            double x0 = p.originX() + i * cb + offX, z0 = p.originZ() + j * cb + offZ;
            double dist = Math.hypot(x0 + cb / 2.0 - cam.x, z0 + cb / 2.0 - cam.z);
            float fade = (float) Math.min(1, Math.max(0, (radius - dist) / (0.25 * radius)));
            // Perto da câmera a nuvem fica quase transparente: de dentro, uma nuvem é neblina,
            // não uma parede. A neblina em si é feita pelo FogRendererMixin (ver insideCloud).
            double near = distanceToBox(cam, x0, p.baseY()[idx], z0, x0 + cb, p.topY()[idx], z0 + cb);
            if (near <= 0) inside = true;
            fade *= (float) Math.min(1, Math.max(0.1, (near - 3) / 30));
            float cover = (p.cover()[idx] & 0xFF) / 255f;
            // Perspectiva aérea: quanto mais longe, mais a nuvem se confunde com o horizonte.
            double dy = p.baseY()[idx] - cam.y;
            float haze = (float) Math.pow(Math.min(1, Math.sqrt(dist * dist + dy * dy) / (radius * 1.1)), 1.5) * 0.85f;
            int cellX = Math.floorDiv(p.originX(), cb) + i, cellZ = Math.floorDiv(p.originZ(), cb) + j;
            cell(bb, m, type, x0, z0, cb, p.baseY()[idx], p.topY()[idx], cover, fade, tint, fog, haze, cellX, cellZ);
        }

        ClientClimate.setInsideCloud(inside);
        MeshData mesh = bb.build();
        if (mesh != null) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();               // visível também de dentro da nuvem
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            BufferUploader.drawWithShader(mesh);
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
        }
        ps.popPose();
    }

    private static void cell(BufferBuilder bb, Matrix4f m, CloudType type, double x0, double z0, int cb,
                             int base, int top, float cover, float fade, Vec3 tint, float[] fog, float haze,
                             int cx, int cz) {
        float r, a;
        switch (type) {
            case CUMULUS -> { r = 1.00f; a = 0.90f; }
            case STRATUS -> { r = 0.85f; a = 0.80f; }
            case NIMBOSTRATUS -> { r = 0.55f; a = 0.92f; }
            case CUMULONIMBUS -> { r = 0.65f; a = 0.95f; }
            case CIRRUS -> { r = 1.00f; a = 0.35f; }
            default -> { return; }
        }
        float cr = Mth.lerp(haze, (float) (r * tint.x), fog[0]);
        float cg = Mth.lerp(haze, (float) (r * tint.y), fog[1]);
        float cbl = Mth.lerp(haze, (float) (r * tint.z), fog[2]);
        a *= fade;
        int h = Math.max(2, top - base);
        switch (type) {
            case CUMULUS -> {
                int puffs = 1 + (int) (cover * 2.99f);
                for (int q = 0; q < puffs; q++) {
                    double s = cb * (0.35 + 0.45 * cover) * (0.7 + 0.3 * rnd(cx, cz, q * 4));
                    double px = x0 + (cb - s) * rnd(cx, cz, q * 4 + 1), pz = z0 + (cb - s) * rnd(cx, cz, q * 4 + 2);
                    double py = base + 2 * rnd(cx, cz, q * 4 + 3);
                    double ph = Math.max(3, h * (0.6 + 0.4 * rnd(cx, cz, q * 4 + 3)));
                    box(bb, m, px, py, pz, px + s, py + ph, pz + s, cr, cg, cbl, a);
                }
            }
            case STRATUS, NIMBOSTRATUS -> {
                double f = 0.6 + 0.4 * cover, s = cb * f, inset = (cb - s) / 2;
                double y = base + 2 * rnd(cx, cz, 0) - 1;
                box(bb, m, x0 + inset, y, z0 + inset, x0 + inset + s, Math.max(y + 2, top), z0 + inset + s, cr, cg, cbl, a);
            }
            case CUMULONIMBUS -> {
                // Torre em três andares de larguras irregulares, com a bigorna clara no topo.
                double anvil = Math.max(3, h / 8.0), tower = h - anvil, y = base;
                for (int q = 0; q < 3; q++) {
                    double s = cb * (1.15 - 0.12 * q) * (0.9 + 0.2 * rnd(cx, cz, 10 + q)), inset = (cb - s) / 2;
                    double y1 = base + tower * (q + 1) / 3.0;
                    box(bb, m, x0 + inset, y, z0 + inset, x0 + inset + s, y1, z0 + inset + s, cr, cg, cbl, a);
                    y = y1;
                }
                double w = cb * 1.8, ai = (cb - w) / 2;
                box(bb, m, x0 + ai, y, z0 + ai, x0 + ai + w, base + h, z0 + ai + w,
                        cr * 1.3f, cg * 1.3f, cbl * 1.3f, a * 0.85f);
            }
            case CIRRUS -> {
                for (int q = 0; q < 2; q++) {
                    double y = base + 6 * rnd(cx, cz, q), zz = z0 + cb * rnd(cx, cz, q + 2);
                    box(bb, m, x0, y, zz, x0 + cb * 0.95, y + 1, zz + 2, cr, cg, cbl, a);
                }
            }
            default -> {}
        }
    }

    /** Distância da câmera até uma caixa (0 se a câmera está dentro dela). */
    private static double distanceToBox(Vec3 c, double x0, double y0, double z0, double x1, double y1, double z1) {
        double dx = Math.max(Math.max(x0 - c.x, 0), c.x - x1);
        double dy = Math.max(Math.max(y0 - c.y, 0), c.y - y1);
        double dz = Math.max(Math.max(z0 - c.z, 0), c.z - z1);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Caixa com sombreamento por face (topo claro, base escura), como as nuvens vanilla. */
    private static void box(BufferBuilder bb, Matrix4f m, double x0, double y0, double z0,
                            double x1, double y1, double z1, float r, float g, float b, float a) {
        float X0 = (float) x0, Y0 = (float) y0, Z0 = (float) z0, X1 = (float) x1, Y1 = (float) y1, Z1 = (float) z1;
        r = Math.min(r, 1); g = Math.min(g, 1); b = Math.min(b, 1);
        quad(bb, m, X0, Y1, Z0, X0, Y1, Z1, X1, Y1, Z1, X1, Y1, Z0, r, g, b, a);             // topo
        float k = 0.7f;
        quad(bb, m, X0, Y0, Z0, X1, Y0, Z0, X1, Y0, Z1, X0, Y0, Z1, r * k, g * k, b * k, a); // base
        k = 0.8f;
        quad(bb, m, X0, Y0, Z0, X0, Y1, Z0, X1, Y1, Z0, X1, Y0, Z0, r * k, g * k, b * k, a); // norte
        quad(bb, m, X0, Y0, Z1, X1, Y0, Z1, X1, Y1, Z1, X0, Y1, Z1, r * k, g * k, b * k, a); // sul
        k = 0.9f;
        quad(bb, m, X0, Y0, Z0, X0, Y0, Z1, X0, Y1, Z1, X0, Y1, Z0, r * k, g * k, b * k, a); // oeste
        quad(bb, m, X1, Y0, Z0, X1, Y1, Z0, X1, Y1, Z1, X1, Y0, Z1, r * k, g * k, b * k, a); // leste
    }

    private static void quad(BufferBuilder bb, Matrix4f m,
                             float x0, float y0, float z0, float x1, float y1, float z1,
                             float x2, float y2, float z2, float x3, float y3, float z3,
                             float r, float g, float b, float a) {
        bb.addVertex(m, x0, y0, z0).setColor(r, g, b, a);
        bb.addVertex(m, x1, y1, z1).setColor(r, g, b, a);
        bb.addVertex(m, x2, y2, z2).setColor(r, g, b, a);
        bb.addVertex(m, x3, y3, z3).setColor(r, g, b, a);
    }

    /** Número pseudoaleatório estável (0 a 1) por célula, para as nuvens não piscarem. */
    private static double rnd(int cx, int cz, int salt) {
        long h = cx * 0x9E3779B97F4A7C15L ^ cz * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
        h ^= h >>> 33; h *= 0xFF51AFD7ED558CCDL; h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }
}
