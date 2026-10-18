package dervogel101.de.emulator_i86_crafted;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import dervogel101.de.state.IoStorageDevice;
import net.minecraft.core.Registry;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import java.util.UUID;
import java.nio.ByteBuffer;

public final class ModComponents {
    public static final DataComponentType<Boolean> CRUDE = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
            ModContent.id("crude"), DataComponentType.<Boolean>builder().persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL).build());
    public static final Codec<ByteBuffer> DISK_CODEC = Codec.BYTE_BUFFER.validate(bytes -> bytes.remaining() == IoStorageDevice.SIZE
            ? DataResult.success(bytes) : DataResult.error(() -> "Disk must be exactly 128 KiB"));
    public static final DataComponentType<ByteBuffer> DISK_CONTENTS = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
            ModContent.id("disk_contents"), DataComponentType.<ByteBuffer>builder().persistent(DISK_CODEC)
                    .networkSynchronized(ByteBufCodecs.byteArray(IoStorageDevice.SIZE).map(bytes -> {
                        if (bytes.length != IoStorageDevice.SIZE) throw new IllegalArgumentException("Disk must be exactly 128 KiB");
                        return ByteBuffer.wrap(bytes).asReadOnlyBuffer();
                    }, ModComponents::copyBytes)).build());
    public static final DataComponentType<Long> MAINFRAME_ID = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
            ModContent.id("mainframe_id"), DataComponentType.<Long>builder().persistent(Codec.LONG).networkSynchronized(ByteBufCodecs.VAR_LONG).build());
    public static final DataComponentType<UUID> ROM_ID = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
            ModContent.id("rom_id"), DataComponentType.<UUID>builder().persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC).build());
    public static final DataComponentType<Boolean> ROM_INVALID = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
            ModContent.id("rom_invalid"), DataComponentType.<Boolean>builder().persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL).build());
    public static final DataComponentType<UUID> DISK_ID = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
            ModContent.id("disk_id"), DataComponentType.<UUID>builder().persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC).build());
    public static final DataComponentType<String> MEMORY_ID = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
            ModContent.id("memory_id"), DataComponentType.<String>builder().persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8).build());
    private ModComponents() {}
    public static byte[] copyBytes(ByteBuffer buffer) { byte[] bytes = new byte[buffer.remaining()]; buffer.duplicate().get(bytes); return bytes; }
    public static void initialize() { }
}
