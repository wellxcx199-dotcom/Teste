package br.climate.mod;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Cliente -> servidor: "manda o mapa do tempo" (enviado enquanto a tela do radar está aberta). */
public record MapRequestPayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<MapRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ClimateMod.ID, "map_request"));
    public static final StreamCodec<FriendlyByteBuf, MapRequestPayload> CODEC =
            StreamCodec.unit(new MapRequestPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
