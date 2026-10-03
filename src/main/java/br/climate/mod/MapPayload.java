package br.climate.mod;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Servidor -> cliente: a grade climática inteira, para o mapa-radar. Cada campo vai em um
 * byte por célula (quantizado), o que dá ~80 KB para 96x96 células; só é enviado a pedido.
 * Os métodos de leitura fazem a conversão de volta para unidades físicas.
 */
public record MapPayload(int originCellX, int originCellZ, int nx, int nz, int cellBlocks, float halfRangeBlocks,
                         byte[] temp, byte[] pres, byte[] hum, byte[] rain, byte[] windU, byte[] windV,
                         byte[] cloudType, byte[] cover, byte[] terrain, List<StormInfo> storms)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MapPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ClimateMod.ID, "map"));
    public static final StreamCodec<FriendlyByteBuf, MapPayload> CODEC =
            CustomPacketPayload.codec(MapPayload::write, MapPayload::read);

    // ---------- Quantização (servidor) ----------
    public static byte encTemp(double c) { return (byte) clamp(Math.round((c + 50) * 2), 0, 255); }
    public static byte encPres(double hPa) { return (byte) clamp(Math.round((hPa - 900) * 1.5), 0, 255); }
    public static byte encHum(double rh) { return (byte) clamp(Math.round(rh * 255), 0, 255); }
    public static byte encRain(double mmH) { return (byte) clamp(Math.round(Math.sqrt(Math.max(0, mmH)) * 50), 0, 255); }
    public static byte encWind(double ms) { return (byte) clamp(Math.round(ms * 3), -127, 127); }
    public static byte encTerrain(boolean water, double elevM) { return (byte) (water ? 0 : clamp(1 + Math.round(elevM / 12), 1, 255)); }

    // ---------- Leitura (cliente) ----------
    public double tempC(int k) { return (temp[k] & 0xFF) / 2.0 - 50; }
    public double pressureHPa(int k) { return (pres[k] & 0xFF) / 1.5 + 900; }
    public double humidity(int k) { return (hum[k] & 0xFF) / 255.0; }
    public double rainMmH(int k) { double r = (rain[k] & 0xFF) / 50.0; return r * r; }
    public double windEast(int k) { return windU[k] / 3.0; }
    public double windNorth(int k) { return windV[k] / 3.0; }
    public boolean water(int k) { return terrain[k] == 0; }
    public double elevationM(int k) { return water(k) ? 0 : ((terrain[k] & 0xFF) - 1) * 12.0; }
    public double latitude(double worldZ) { return Math.max(-1, Math.min(1, -worldZ / halfRangeBlocks)) * 90; }

    private static long clamp(long v, long lo, long hi) { return Math.max(lo, Math.min(hi, v)); }

    private void write(FriendlyByteBuf buf) {
        buf.writeInt(originCellX).writeInt(originCellZ).writeVarInt(nx).writeVarInt(nz).writeVarInt(cellBlocks);
        buf.writeFloat(halfRangeBlocks);
        for (byte[] f : new byte[][] {temp, pres, hum, rain, windU, windV, cloudType, cover, terrain}) buf.writeBytes(f);
        StormInfo.writeList(buf, storms);
    }

    private static MapPayload read(FriendlyByteBuf buf) {
        int ox = buf.readInt(), oz = buf.readInt(), nx = buf.readVarInt(), nz = buf.readVarInt(), cb = buf.readVarInt();
        float half = buf.readFloat();
        if (nx <= 0 || nz <= 0 || nx > 512 || nz > 512) throw new IllegalArgumentException("mapa inválido");
        byte[][] f = new byte[9][nx * nz];
        for (byte[] a : f) buf.readBytes(a);
        return new MapPayload(ox, oz, nx, nz, cb, half, f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7], f[8],
                StormInfo.readList(buf));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
