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
    private static KeyMapping toggleKey;
    private static boolean visible = true;

    static void register() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.climamod.hud", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.climamod"));
    }

    static void tick() {
        while (toggleKey.consumeClick()) visible = !visible;
    }

    static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!visible || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) return;
        if (mc.level == null || mc.level.dimension() != Level.OVERWORLD || !ClientClimate.active()) return;
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

        Font font = mc.font;
        int w = 0;
        for (String s : lines) w = Math.max(w, font.width(s));
        int x = 4, y = 4, lh = font.lineHeight + 1;
        g.fill(x - 2, y - 2, x + w + 2, y + lines.size() * lh, 0x80000000);
        for (String s : lines) {
            g.drawString(font, s, x, y, 0xFFFFFF, false);
            y += lh;
        }
    }

    private static String compass(double fromDeg) {
        String[] dirs = {"N", "NE", "L", "SE", "S", "SO", "O", "NO"};
        return dirs[(int) Math.round(fromDeg / 45.0) & 7];
    }
}
