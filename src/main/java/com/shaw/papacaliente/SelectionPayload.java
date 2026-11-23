package com.shaw.papacaliente;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Servidor → cliente: activa o desactiva la vista previa del "Modo Selección de Arena". */
public record SelectionPayload(boolean active) implements CustomPacketPayload {
    public static final Type<SelectionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PapaCaliente.MOD_ID, "selection"));
    public static final StreamCodec<ByteBuf, SelectionPayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.BOOL, SelectionPayload::active, SelectionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
