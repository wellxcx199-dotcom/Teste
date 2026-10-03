package br.climate.client;

import br.climate.core.CloudType;
import br.climate.core.Storm;
import br.climate.mod.ClimateMod;
import br.climate.mod.MapPayload;
import br.climate.mod.MapRequestPayload;
import br.climate.mod.StormInfo;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Mapa-radar meteorológico (tecla M). Mostra a área simulada inteira com uma camada
 * escolhida: radar (refletividade em dBZ), temperatura, vento, umidade, pressão ou nuvens.
 * Passar o mouse mostra os valores daquele ponto. Norte para cima.
 */
public final class ClimateMapScreen extends Screen {
    enum Layer {
        RADAR("Radar"), TEMPERATURE("Temperatura"), WIND("Vento"), HUMIDITY("Umidade"),
        PRESSURE("Pressão"), CLOUDS("Nuvens");
        final String label;
        Layer(String label) { this.label = label; }
    }

    private static final int PX = 4;                       // pixels da textura por célula
    private static final ResourceLocation TEX = ResourceLocation.fromNamespaceAndPath(ClimateMod.ID, "radar_map");
    private static Layer layer = Layer.RADAR;             // lembra a última camada escolhida

    private DynamicTexture texture;
    private MapPayload builtFor;
    private Layer builtLayer;
    private int ticks;
    private int mapX, mapY, mapSize;

    public ClimateMapScreen() { super(Component.literal("Radar meteorológico")); }

    @Override
    protected void init() {
        mapSize = Math.max(100, Math.min(height - 44, width - 190));
        mapX = 14;
        mapY = 28;
        int bx = mapX + mapSize + 12, by = mapY, bw = Math.min(150, width - bx - 8);
        for (Layer l : Layer.values()) {
            addRenderableWidget(Button.builder(Component.literal(l.label), b -> layer = l)
                    .bounds(bx, by, bw, 18).build());
            by += 21;
        }
        requestMap();
    }

    private static void requestMap() {
        if (ClientPlayNetworking.canSend(MapRequestPayload.TYPE)) ClientPlayNetworking.send(new MapRequestPayload());
    }

    @Override
    public void tick() {
        if (++ticks % 40 == 0) requestMap();                 // atualiza a cada 2 s
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void removed() {
        if (texture != null) {
            minecraft.getTextureManager().release(TEX);
            texture = null;
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        g.drawString(font, "Radar meteorológico — camada: " + layer.label, mapX, 10, 0xFFFFFF);
        MapPayload m = ClientClimate.map();
        g.fill(mapX - 2, mapY - 2, mapX + mapSize + 2, mapY + mapSize + 2, 0xFF101418);
        if (m == null) {
            g.drawCenteredString(font, "Recebendo dados do radar...", mapX + mapSize / 2, mapY + mapSize / 2, 0xFFFFFF);
            return;
        }
        if (m != builtFor || layer != builtLayer || texture == null) rebuild(m);
        g.blit(TEX, mapX, mapY, mapSize, mapSize, 0f, 0f, m.nx() * PX, m.nz() * PX, m.nx() * PX, m.nz() * PX);
        drawOverlays(g, m);
        drawLegend(g, m);
        if (mx >= mapX && my >= mapY && mx < mapX + mapSize && my < mapY + mapSize) hover(g, m, mx, my);
    }

    // ---------- Coordenadas ----------

    private double cellsPerPixel(MapPayload m) { return m.nx() / (double) mapSize; }

    private double screenToWorldX(MapPayload m, double sx) {
        return (m.originCellX() + (sx - mapX) * cellsPerPixel(m)) * m.cellBlocks();
    }

    private double screenToWorldZ(MapPayload m, double sy) {
        return (m.originCellZ() + (sy - mapY) * m.nz() / (double) mapSize) * m.cellBlocks();
    }

    private int worldToScreenX(MapPayload m, double x) {
        return mapX + (int) Math.round((x / m.cellBlocks() - m.originCellX()) / m.nx() * mapSize);
    }

    private int worldToScreenY(MapPayload m, double z) {
        return mapY + (int) Math.round((z / m.cellBlocks() - m.originCellZ()) / m.nz() * mapSize);
    }

    // ---------- Textura ----------

    private void rebuild(MapPayload m) {
        int w = m.nx() * PX, h = m.nz() * PX;
        if (texture == null || texture.getPixels() == null || texture.getPixels().getWidth() != w) {
            if (texture != null) minecraft.getTextureManager().release(TEX);
            texture = new DynamicTexture(w, h, false);
            minecraft.getTextureManager().register(TEX, texture);
        }
        NativeImage img = texture.getPixels();
        for (int py = 0; py < h; py++)
            for (int px = 0; px < w; px++) {
                double cx = (px + 0.5) / PX - 0.5, cz = (py + 0.5) / PX - 0.5;   // posição contínua, em células
                int k = cell(m, (int) Math.floor((px) / (double) PX), (int) Math.floor((py) / (double) PX));
                int base = terrainColor(m, k);
                int over = switch (layer) {
                    case RADAR -> radarColor(dbz(lerp(m, cx, cz, m::rainMmH)));
                    case TEMPERATURE -> ramp(TEMP_STOPS, lerp(m, cx, cz, m::tempC), 170);
                    case HUMIDITY -> ramp(HUM_STOPS, lerp(m, cx, cz, m::humidity), 165);
                    case PRESSURE -> ramp(PRES_STOPS, lerp(m, cx, cz, m::pressureHPa), 165);
                    case WIND -> ramp(WIND_STOPS, Math.hypot(lerp(m, cx, cz, m::windEast), lerp(m, cx, cz, m::windNorth)), 165);
                    case CLOUDS -> cloudColor(m, k);
                };
                img.setPixelRGBA(px, py, toAbgr(blend(base, over)));
            }
        if (layer == Layer.PRESSURE) isobars(m, img);
        if (layer == Layer.WIND) windArrows(m, img);
        texture.upload();
        builtFor = m;
        builtLayer = layer;
    }

    private static int cell(MapPayload m, int i, int j) {
        i = Math.max(0, Math.min(m.nx() - 1, i));
        j = Math.max(0, Math.min(m.nz() - 1, j));
        return j * m.nx() + i;
    }

    /** Interpolação bilinear de um campo nas coordenadas contínuas (cx, cz) da grade. */
    private static double lerp(MapPayload m, double cx, double cz, java.util.function.IntToDoubleFunction f) {
        int i0 = (int) Math.floor(cx), j0 = (int) Math.floor(cz);
        double fx = cx - i0, fz = cz - j0;
        double a = f.applyAsDouble(cell(m, i0, j0)), b = f.applyAsDouble(cell(m, i0 + 1, j0));
        double c = f.applyAsDouble(cell(m, i0, j0 + 1)), d = f.applyAsDouble(cell(m, i0 + 1, j0 + 1));
        return (a + (b - a) * fx) * (1 - fz) + (c + (d - c) * fx) * fz;
    }

    /** Refletividade do radar (dBZ) pela relação de Marshall-Palmer: Z = 200 R^1,6. */
    static double dbz(double mmH) {
        return mmH <= 0.01 ? -10 : 10 * Math.log10(200 * Math.pow(mmH, 1.6));
    }

    // ---------- Cores (ARGB) ----------

    private static final double[][] TEMP_STOPS = {{-30, 0x8000C8}, {-15, 0x0050FF}, {0, 0x00C8FF}, {10, 0x00DC78},
            {20, 0xFFE600}, {30, 0xFF7800}, {40, 0xC80000}};
    private static final double[][] HUM_STOPS = {{0, 0x966428}, {0.5, 0xE6E696}, {0.8, 0x3CB43C}, {1, 0x005AC8}};
    private static final double[][] PRES_STOPS = {{960, 0x7800A0}, {990, 0x0064FF}, {1005, 0xB4DCFF}, {1013, 0xF0F0F0},
            {1020, 0xFFC878}, {1030, 0xFF6400}};
    private static final double[][] WIND_STOPS = {{0, 0x283C5A}, {10, 0x00A0A0}, {20, 0xF0DC00}, {30, 0xFF5000}, {45, 0xC80078}};
    private static final double[][] RADAR_STOPS = {{15, 0x04E9E7}, {20, 0x019FF4}, {25, 0x02FD02}, {30, 0x01C501},
            {35, 0x008E00}, {40, 0xFDF802}, {45, 0xFD9500}, {50, 0xFD0000}, {55, 0xBC0000}, {60, 0xF800FD}, {65, 0x9854C6}};

    private static int ramp(double[][] stops, double v, int alpha) {
        if (v <= stops[0][0]) return (alpha << 24) | (int) stops[0][1];
        for (int i = 1; i < stops.length; i++)
            if (v <= stops[i][0]) {
                double t = (v - stops[i - 1][0]) / (stops[i][0] - stops[i - 1][0]);
                return (alpha << 24) | mix((int) stops[i - 1][1], (int) stops[i][1], t);
            }
        return (alpha << 24) | (int) stops[stops.length - 1][1];
    }

    /** Radar em faixas de 5 dBZ, como os radares reais; abaixo de 15 dBZ não aparece. */
    private static int radarColor(double dbz) {
        if (dbz < 15) return 0;
        int band = (int) Math.min(RADAR_STOPS.length - 1, Math.floor((dbz - 15) / 5));
        return 0xE6000000 | (int) RADAR_STOPS[band][1];
    }

    private static int cloudColor(MapPayload m, int k) {
        CloudType t = CloudType.values()[m.cloudType()[k]];
        int a = (int) ((m.cover()[k] & 0xFF) * 0.85);
        int rgb = switch (t) {
            case CUMULONIMBUS -> 0x6A6E78;
            case NIMBOSTRATUS -> 0x9AA0A8;
            case STRATUS -> 0xD8DCE0;
            case FOG -> 0xE8E4C8;
            case CIRRUS -> 0xFFFFFF;
            default -> 0xFFFFFF;
        };
        if (t == CloudType.CLEAR) return 0;
        if (t == CloudType.CIRRUS) a = 70;
        if (t == CloudType.FOG) a = 150;
        return (a << 24) | rgb;
    }

    private static int terrainColor(MapPayload m, int k) {
        if (m.water(k)) return 0xFF1E3A5C;
        double e = Math.min(1, m.elevationM(k) / 2000);
        return 0xFF000000 | mix(0x48603C, 0x8C826E, e);
    }

    private static int mix(int a, int b, double t) {
        int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static int blend(int base, int over) {
        double a = ((over >>> 24) & 255) / 255.0;
        return 0xFF000000 | mix(base & 0xFFFFFF, over & 0xFFFFFF, a);
    }

    /** NativeImage guarda pixels como ABGR. */
    private static int toAbgr(int argb) {
        return (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16);
    }

    /** Isóbaras a cada 4 hPa: pixel escuro onde a faixa de pressão muda. */
    private static void isobars(MapPayload m, NativeImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] band = new int[w * h];
        for (int py = 0; py < h; py++)
            for (int px = 0; px < w; px++)
                band[py * w + px] = (int) Math.floor(lerp(m, (px + 0.5) / PX - 0.5, (py + 0.5) / PX - 0.5, m::pressureHPa) / 4);
        for (int py = 0; py < h - 1; py++)
            for (int px = 0; px < w - 1; px++) {
                int b = band[py * w + px];
                if (b != band[py * w + px + 1] || b != band[(py + 1) * w + px])
                    img.setPixelRGBA(px, py, toAbgr(0xFF202838));
            }
    }

    /** Setas brancas na direção para onde o vento sopra, a cada 6 células. */
    private static void windArrows(MapPayload m, NativeImage img) {
        int step = 6;
        for (int j = step / 2; j < m.nz(); j += step)
            for (int i = step / 2; i < m.nx(); i += step) {
                int k = cell(m, i, j);
                double u = m.windEast(k), v = m.windNorth(k), sp = Math.hypot(u, v);
                if (sp < 0.5) continue;
                double len = Math.min(step * PX * 0.45, 4 + sp * 0.8);
                double dx = u / sp, dy = -v / sp;                 // norte para cima na imagem
                int cx = i * PX + PX / 2, cy = j * PX + PX / 2;
                double ex = cx + dx * len, ey = cy + dy * len;
                line(img, cx - dx * len * 0.5, cy - dy * len * 0.5, ex, ey);
                double ax = -dy, ay = dx;                          // ponta da seta
                line(img, ex, ey, ex - dx * 4 + ax * 3, ey - dy * 4 + ay * 3);
                line(img, ex, ey, ex - dx * 4 - ax * 3, ey - dy * 4 - ay * 3);
            }
    }

    private static void line(NativeImage img, double x0, double y0, double x1, double y1) {
        int n = (int) Math.ceil(Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0))) + 1;
        for (int s = 0; s <= n; s++) {
            int x = (int) Math.round(x0 + (x1 - x0) * s / n), y = (int) Math.round(y0 + (y1 - y0) * s / n);
            if (x >= 0 && y >= 0 && x < img.getWidth() && y < img.getHeight()) img.setPixelRGBA(x, y, 0xFFFFFFFF);
        }
    }

    // ---------- Sobreposições ----------

    private void drawOverlays(GuiGraphics g, MapPayload m) {
        // Fenômenos
        for (StormInfo s : m.storms()) {
            int sx = worldToScreenX(m, s.x()), sy = worldToScreenY(m, s.z());
            if (sx < mapX || sy < mapY || sx >= mapX + mapSize || sy >= mapY + mapSize) continue;
            String icon;
            int color;
            switch (s.stormKind()) {
                case HURRICANE -> {
                    icon = "@"; color = 0xFFFF3030;
                    int rad = Math.max(3, (int) Math.round(s.radius() / m.cellBlocks() / m.nx() * mapSize));
                    ring(g, sx, sy, rad, 0xC0FF5050);
                }
                case TORNADO -> { icon = "▼"; color = 0xFFFF40FF; }
                default -> { icon = "S"; color = 0xFFFFE040; }
            }
            g.fill(sx - 5, sy - 5, sx + 6, sy + 6, 0xA0000000);
            g.drawCenteredString(font, icon, sx + 1, sy - 3, color);
            g.drawString(font, s.describe(), sx + 8, sy - 4, color);
        }
        // Jogador (seta na direção para onde olha)
        LocalPlayer p = minecraft.player;
        if (p != null) {
            int px = worldToScreenX(m, p.getX()), py = worldToScreenY(m, p.getZ());
            String[] arrows = {"↓", "↙", "←", "↖", "↑", "↗", "→", "↘"};
            int dir = Math.floorMod(Math.round(p.getYRot() / 45f), 8);
            g.fill(px - 4, py - 5, px + 5, py + 5, 0xC0000000);
            g.drawCenteredString(font, arrows[dir], px + 1, py - 4, 0xFFFFFFFF);
        }
        // Norte e escala (100 km)
        g.drawString(font, "N ↑", mapX + mapSize - 22, mapY + 4, 0xFFFFFF);
        double kmPerPixel = m.cellBlocks() * 500.0 / 1000 * m.nx() / mapSize;   // 1 bloco = 500 m
        int barPx = (int) Math.round(100 / kmPerPixel);
        int bx = mapX + 6, by = mapY + mapSize - 10;
        g.fill(bx, by, bx + barPx, by + 2, 0xFFFFFFFF);
        g.drawString(font, "100 km (" + Math.round(100000 / 500.0) + " blocos)", bx, by - 10, 0xFFFFFF);
        // Latitudes nas bordas
        g.drawString(font, String.format("%.1f°", m.latitude(m.originCellZ() * (double) m.cellBlocks())), mapX + 4, mapY + 4, 0xDDDDDD);
        g.drawString(font, String.format("%.1f°", m.latitude((m.originCellZ() + m.nz()) * (double) m.cellBlocks())),
                mapX + 4, mapY + mapSize - 24, 0xDDDDDD);
    }

    private static void ring(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int a = 0; a < 48; a++) {
            double t = a * Math.PI * 2 / 48;
            int x = cx + (int) Math.round(Math.cos(t) * r), y = cy + (int) Math.round(Math.sin(t) * r);
            g.fill(x, y, x + 1, y + 1, color);
        }
    }

    private void drawLegend(GuiGraphics g, MapPayload m) {
        int x = mapX + mapSize + 12, y = mapY + Layer.values().length * 21 + 8, w = Math.min(150, width - x - 8);
        if (w < 40) return;
        double lo, hi;
        String unit;
        double[][] stops;
        switch (layer) {
            case RADAR -> { stops = RADAR_STOPS; lo = 15; hi = 65; unit = "dBZ"; }
            case TEMPERATURE -> { stops = TEMP_STOPS; lo = -30; hi = 40; unit = "°C"; }
            case HUMIDITY -> { stops = HUM_STOPS; lo = 0; hi = 1; unit = "UR"; }
            case PRESSURE -> { stops = PRES_STOPS; lo = 960; hi = 1030; unit = "hPa"; }
            case WIND -> { stops = WIND_STOPS; lo = 0; hi = 45; unit = "m/s"; }
            default -> { stops = null; lo = 0; hi = 1; unit = ""; }
        }
        if (stops != null) {
            for (int i = 0; i < w; i++) {
                double v = lo + (hi - lo) * i / (w - 1);
                int c = layer == Layer.RADAR ? radarColor(v) | 0xFF000000 : ramp(stops, v, 255);
                g.fill(x + i, y, x + i + 1, y + 8, c);
            }
            String l = layer == Layer.HUMIDITY ? "0%" : fmt(lo), r = layer == Layer.HUMIDITY ? "100%" : fmt(hi);
            g.drawString(font, l, x, y + 11, 0xFFFFFF);
            g.drawString(font, r + " " + unit, x + w - font.width(r + " " + unit), y + 11, 0xFFFFFF);
        } else {
            String[] names = {"Cumulonimbo", "Nimbostratos", "Estratos/cúmulos", "Neblina"};
            int[] cols = {0xFF6A6E78, 0xFF9AA0A8, 0xFFE8EAEC, 0xFFE8E4C8};
            for (int i = 0; i < names.length; i++) {
                g.fill(x, y + i * 12, x + 9, y + i * 12 + 9, cols[i]);
                g.drawString(font, names[i], x + 13, y + i * 12 + 1, 0xFFFFFF);
            }
        }
        int ty = y + 60;
        for (String line : new String[] {"Passe o mouse no mapa", "para ver os valores.", "@ furacão  S supercélula", "▼ tornado"}) {
            g.drawString(font, line, x, ty, 0xBBBBBB);
            ty += 11;
        }
    }

    private static String fmt(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.format("%.1f", v); }

    private void hover(GuiGraphics g, MapPayload m, int mx, int my) {
        double wx = screenToWorldX(m, mx), wz = screenToWorldZ(m, my);
        int i = (int) Math.floor(wx / m.cellBlocks()) - m.originCellX(), j = (int) Math.floor(wz / m.cellBlocks()) - m.originCellZ();
        int k = cell(m, i, j);
        double u = m.windEast(k), v = m.windNorth(k), sp = Math.hypot(u, v);
        String[] dirs = {"N", "NE", "L", "SE", "S", "SO", "O", "NO"};
        String from = dirs[(int) Math.round(((Math.toDegrees(Math.atan2(-u, -v)) + 360) % 360) / 45.0) & 7];
        double rain = m.rainMmH(k);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(String.format("x %.0f  z %.0f  (lat %.1f°)", wx, wz, m.latitude(wz))));
        lines.add(Component.literal(String.format("Temperatura: %.1f °C", m.tempC(k))));
        lines.add(Component.literal(String.format("Umidade: %.0f%%", m.humidity(k) * 100)));
        lines.add(Component.literal(String.format("Pressão (nível do mar): %.1f hPa", m.pressureHPa(k))));
        lines.add(Component.literal(String.format("Vento: %.1f m/s de %s", sp, from)));
        lines.add(Component.literal(rain > 0.05 ? String.format("Chuva: %.1f mm/h (%.0f dBZ)", rain, dbz(rain)) : "Sem chuva"));
        lines.add(Component.literal("Nuvens: " + CloudType.values()[m.cloudType()[k]].label));
        lines.add(Component.literal(m.water(k) ? "Água" : String.format("Terra, %.0f m de altitude", m.elevationM(k))));
        for (StormInfo s : m.storms())
            if (Math.hypot(s.x() - wx, s.z() - wz) < Math.max(3 * m.cellBlocks(), s.radius() * 2))
                lines.add(Component.literal(String.format("§c%s — vento máx. %.0f m/s", s.describe(), s.vmax())));
        g.renderComponentTooltip(font, lines, mx, my);
    }
}
