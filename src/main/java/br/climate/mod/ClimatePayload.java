package br.climate.mod;

import br.climate.core.CloudType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Pacote servidor -> cliente com o clima local do jogador e um recorte da grade de nuvens
 * ao redor dele. Só é enviado a clientes que têm o mod; clientes vanilla continuam vendo
 * apenas o clima global do mundo.
 *
 * @param tempC        temperatura na posição do jogador (°C)
 * @param refY         altura (Y) em que a temperatura foi medida, para estimar outras alturas
 * @param lapsePerBlock queda de temperatura (°C) por bloco de altura
 * @param humidity     umidade relativa (0 a 1)
 * @param pressureHPa  pressão na altura do jogador (hPa)
 * @param windSpeed    velocidade do vento (m/s)
 * @param windFromDeg  direção de onde o vento vem (0 = norte, 90 = leste)
 * @param precipMmH    chuva na célula do jogador (mm/h)
 * @param cloudType    tipo de nuvem na célula do jogador ({@code CloudType.ordinal()})
 * @param thunder      trovoada na célula do jogador
 * @param latitude     latitude da posição (graus, positiva ao norte)
 * @param driftX       deslocamento das nuvens para leste, em blocos por tick
 * @param driftZ       deslocamento das nuvens para sul, em blocos por tick
 * @param originX      bloco X do canto noroeste do recorte
 * @param originZ      bloco Z do canto noroeste do recorte
 * @param cellBlocks   lado de cada célula em blocos
 * @param size         número de células por lado do recorte
 * @param types        tipo de nuvem por célula ({@code CloudType.ordinal()})
 * @param cover        cobertura por célula, 0 a 255
 * @param baseY        altura (Y do mundo) da base da nuvem
 * @param topY         altura (Y do mundo) do topo da nuvem
 */
public record ClimatePayload(
        float tempC, float refY, float lapsePerBlock, float humidity, float pressureHPa, float windSpeed, float windFromDeg,
        float precipMmH, byte cloudType, boolean thunder, float latitude,
        float driftX, float driftZ, int originX, int originZ, int cellBlocks, int size,
        byte[] types, byte[] cover, short[] baseY, short[] topY) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClimatePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ClimateMod.ID, "climate"));

    public static final StreamCodec<FriendlyByteBuf, ClimatePayload> CODEC =
            CustomPacketPayload.codec(ClimatePayload::write, ClimatePayload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeFloat(tempC).writeFloat(refY).writeFloat(lapsePerBlock).writeFloat(humidity).writeFloat(pressureHPa);
        buf.writeFloat(windSpeed).writeFloat(windFromDeg).writeFloat(precipMmH);
        buf.writeByte(cloudType).writeBoolean(thunder).writeFloat(latitude);
        buf.writeFloat(driftX).writeFloat(driftZ);
        buf.writeInt(originX).writeInt(originZ).writeVarInt(cellBlocks).writeVarInt(size);
        buf.writeBytes(types).writeBytes(cover);
        for (short s : baseY) buf.writeShort(s);
        for (short s : topY) buf.writeShort(s);
    }

    private static ClimatePayload read(FriendlyByteBuf buf) {
        float t = buf.readFloat(), refY = buf.readFloat(), lapse = buf.readFloat(), rh = buf.readFloat(), p = buf.readFloat();
        float ws = buf.readFloat(), wd = buf.readFloat(), pr = buf.readFloat();
        byte ct = buf.readByte();
        boolean th = buf.readBoolean();
        float lat = buf.readFloat();
        float dx = buf.readFloat(), dz = buf.readFloat();
        int ox = buf.readInt(), oz = buf.readInt(), cb = buf.readVarInt(), size = buf.readVarInt();
        if (size < 0 || size > 129) throw new IllegalArgumentException("recorte de nuvens inválido: " + size);
        int n = size * size;
        byte[] types = new byte[n], cover = new byte[n];
        buf.readBytes(types);
        buf.readBytes(cover);
        short[] base = new short[n], top = new short[n];
        for (int k = 0; k < n; k++) base[k] = buf.readShort();
        for (int k = 0; k < n; k++) top[k] = buf.readShort();
        // Um servidor com outra versão do mod poderia mandar tipos desconhecidos: vira céu limpo.
        int nTypes = CloudType.values().length;
        for (int k = 0; k < n; k++) if (types[k] < 0 || types[k] >= nTypes) types[k] = 0;
        if (ct < 0 || ct >= nTypes) ct = 0;
        return new ClimatePayload(t, refY, lapse, rh, p, ws, wd, pr, ct, th, lat, dx, dz, ox, oz, cb, size,
                types, cover, base, top);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
