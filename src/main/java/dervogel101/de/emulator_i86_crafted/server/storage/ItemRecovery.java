package dervogel101.de.emulator_i86_crafted.server.storage;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.world.item.ItemStack;
import java.io.*;

public final class ItemRecovery {
    private ItemRecovery() {}
    public static byte[] save(ItemStack stack, HolderLookup.Provider registries) throws IOException {
        var tag = ItemStack.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), stack).getOrThrow();
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) { NbtIo.write((CompoundTag) tag, out); }
        if (bytes.size() > 1024 * 1024) throw new IOException("Recovery item exceeds 1 MiB");
        return bytes.toByteArray();
    }
    public static ItemStack load(byte[] bytes, HolderLookup.Provider registries) throws IOException {
        if (bytes.length > 1024 * 1024) throw new IOException("Recovery item exceeds 1 MiB");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            var tag = NbtIo.read(in, NbtAccounter.create(1024 * 1024));
            if (tag == null || in.read() != -1) throw new IOException("Invalid recovery item");
            return ItemStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag).getOrThrow();
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid recovery item", invalid); }
    }
}
