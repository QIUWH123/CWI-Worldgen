package net.quiwh.cwiworldgen.hydrology.current;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

/** Server-to-watcher metadata only: no seed, topology, chunk generation or client request.
 * The generation header selects hydrology protocol 2; transport 2 adds bounded vertical layers. */
public record CurrentFieldPayload(ResourceLocation dimension, int generation, long revision,
                                 long chunkPos, CurrentChunk field) implements CustomPacketPayload {
    public static final String PROTOCOL = "3";
    public static final ResourceLocation PROFILE = ResourceLocation.fromNamespaceAndPath("cwi_worldgen", "river_currents_v3");
    public static final Type<CurrentFieldPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("cwi_worldgen", "current_field"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CurrentFieldPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> payload.write(buffer), CurrentFieldPayload::read);

    public CurrentFieldPayload {
        if (dimension == null || generation != 2 || revision <= 0
                || Math.abs((long) ChunkPos.getX(chunkPos)) > 1875000
                || Math.abs((long) ChunkPos.getZ(chunkPos)) > 1875000
                || (field != null && field.chunkPos() != chunkPos)) {
            throw new IllegalArgumentException("Invalid current field header");
        }
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeResourceLocation(PROFILE);
        buffer.writeResourceLocation(dimension);
        buffer.writeVarInt(generation);
        buffer.writeVarLong(revision);
        buffer.writeLong(chunkPos);
        buffer.writeShort(field == null ? 0 : field.layerCount() * CurrentChunk.COLUMNS);
        if (field != null) for (int layer = 0; layer < field.layerCount(); layer++)
            for (int index = 0; index < CurrentChunk.COLUMNS; index++) buffer.writeLong(field.packed(layer, index));
    }

    private static CurrentFieldPayload read(RegistryFriendlyByteBuf buffer) {
        if (!PROFILE.equals(buffer.readResourceLocation())) throw new IllegalArgumentException("Unknown current generation profile");
        var dimension = buffer.readResourceLocation();
        int generation = buffer.readVarInt();
        long revision = buffer.readVarLong(), chunkPos = buffer.readLong();
        int length = buffer.readUnsignedShort();
        if (length > CurrentChunk.COLUMNS * CurrentChunk.MAX_LAYERS || length % CurrentChunk.COLUMNS != 0)
            throw new IllegalArgumentException("Current field must contain one to four complete layers");
        long[][] data = length == 0 ? null : new long[length / CurrentChunk.COLUMNS][CurrentChunk.COLUMNS];
        if (data != null) for (long[] layer : data)
            for (int index = 0; index < layer.length; index++) layer[index] = buffer.readLong();
        return new CurrentFieldPayload(dimension, generation, revision, chunkPos,
                data == null ? null : new CurrentChunk(chunkPos, data));
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
