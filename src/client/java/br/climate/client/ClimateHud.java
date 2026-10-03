package br.climate.client;

import br.climate.core.CloudType;
import br.climate.mod.ClimatePayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.level.Level;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** Painel no canto da tela com o tempo local. A tecla K (configurável) liga e desliga. */
public final class ClimateHud {
    private ClimateHud() {}

    private static final CloudType[] TYPES = CloudType.values();
    private static KeyMapping toggleKey, mapKey;
    private static boolean visible = true;

    static void register() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.climamod.hud", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.climamod"));
        mapKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.climamod.map", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, "key.categories.climamod"));
    }

    static void tick() {
        while (toggleKey.consumeClick()) visible = !visible;
        while (mapKey.consumeClick()) {
            Minecraft mc = Minecraft.getInstance();
            if (ClientClimate.active()) mc.setScreen(new ClimateMapScreen());
            else if (mc.player != null)
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "Radar indisponível: o servidor não tem o mod ou você não está na Superfície."), true);
        }
    }

    static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!visible || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) return;
        if (mc.level == null || mc.player == null || mc.level.dimension() != Level.OVERWORLD || !ClientClimate.active()) return;
        ClimatePayload p = ClientClimate.latest();

        List<String> lines = new ArrayList<>();
        lines.add(String.format("%.1f °C   UR %.0f%%", p.tempC(), p.humidity() * 100));
        lines.add(String.format("%.0f hPa   lat %.0f°%s", p.pressureHPa(), Math.abs(p.latitude()),
                p.latitude() >= 0 ? "N" : "S"));
        lines.add(String.format("Vento %.0f m/s de %s", p.windSpeed(), compass(p.windFromDeg())));
        String sky = TYPES[p.cloudType()].label;
        if (p.thunder()) sky += " · trovoada";
        else if (p.precipMmH() > 0.1)
            sky += String.format(" · %s %.1f mm/h", ClientClimate.snowAt((int) p.refY()) ? "neve" : "chuva", p.precipMmH());
        lines.add(sky);
        // Aviso do fenômeno mais próximo (até 1500 blocos).
        br.climate.mod.StormInfo near = null;
        double best = 1500;
        for (br.climate.mod.StormInfo s : p.storms()) {
            double d = Math.hypot(s.x() - mc.player.getX(), s.z() - mc.player.getZ());
            if (d < best) { best = d; near = s; }
        }
        if (near != null) {
            double dx = near.x() - mc.player.getX(), dz = near.z() - mc.player.getZ();
            String dir = compass((Math.toDegrees(Math.atan2(dx, -dz)) + 360) % 360);
            lines.add(String.format("⚠ %s a %.0f blocos (%s)", near.describe(), best, dir));
        }

        Font font = mc.font;
        int w = 0;
        for (String s : lines) w = Math.max(w, font.width(s));
        int x = 4, y = 4, lh = font.lineHeight + 1;
        g.fill(x - 2, y - 2, x + w + 2, y + lines.size() * lh, 0x80000000);
        for (String s : lines) {
            g.drawString(font, s, x, y, s.startsWith("⚠") ? 0xFF6060 : 0xFFFFFF, false);
            y += lh;
        }
    }

    private static String compass(double fromDeg) {
        String[] dirs = {"N", "NE", "L", "SE", "S", "SO", "O", "NO"};
        return dirs[(int) Math.round(fromDeg / 45.0) & 7];
    }
}
