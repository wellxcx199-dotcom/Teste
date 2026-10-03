package br.climate.mod;

import br.climate.core.Storm;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Resumo de um fenômeno (furacão, supercélula, tornado) enviado ao cliente.
 *
 * @param radius raio característico em blocos (vento máximo do furacão, núcleo da supercélula, funil do tornado)
 * @param vx     deslocamento para leste, em blocos por tick (para o cliente estimar a posição entre pacotes)
 * @param vz     deslocamento para sul, em blocos por tick
 */
public record StormInfo(int id, byte kind, float x, float z, float vmax, float radius, float intensity,
                        byte category, float vx, float vz) {

    public Storm.Kind stormKind() { return Storm.Kind.values()[kind]; }

    public static StormInfo of(Storm s, double metersPerBlock) {
        // horas de jogo -> ticks: 1 hora = 1000 ticks
        return new StormInfo(s.id, (byte) s.kind.ordinal(), (float) s.x, (float) s.z, (float) s.vmax,
                (float) (s.radiusM / metersPerBlock), (float) s.intensity(), (byte) s.category(),
                (float) (s.vx / 1000.0), (float) (s.vz / 1000.0));
    }

    public String describe() {
        return switch (stormKind()) {
            case HURRICANE -> category == 0 ? "Tempestade tropical" : "Furacão categoria " + category;
            case TORNADO -> "Tornado EF" + category;
            case SUPERCELL -> "Supercélula";
        };
    }

    static void writeList(FriendlyByteBuf buf, List<StormInfo> list) {
        buf.writeVarInt(list.size());
        for (StormInfo s : list) {
            buf.writeVarInt(s.id).writeByte(s.kind).writeFloat(s.x).writeFloat(s.z).writeFloat(s.vmax);
            buf.writeFloat(s.radius).writeFloat(s.intensity).writeByte(s.category).writeFloat(s.vx).writeFloat(s.vz);
        }
    }

    static List<StormInfo> readList(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        if (n < 0 || n > 64) throw new IllegalArgumentException("lista de fenômenos inválida: " + n);
        List<StormInfo> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int id = buf.readVarInt();
            byte kind = buf.readByte();
            if (kind < 0 || kind >= Storm.Kind.values().length) kind = 0;
            out.add(new StormInfo(id, kind, buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readFloat(), buf.readByte(), buf.readFloat(), buf.readFloat()));
        }
        return out;
    }
}
