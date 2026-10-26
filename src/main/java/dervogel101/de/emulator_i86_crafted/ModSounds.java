package dervogel101.de.emulator_i86_crafted;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;

/** Named/subtitled machine cues backed by generated local audio clips. */
public final class ModSounds {
    public static final SoundEvent FAN = register("fan"), START = register("start"), STOP = register("stop"),
            FAULT = register("fault"), RELAY = register("relay"), DISK = register("disk"),
            TERMINAL = register("terminal"), SIGN = register("sign"), KEY = register("key"),
            CARD_READ = register("card_read"), CARD_WRITE = register("card_write"),
            CARD_FEED = register("card_feed"), PAPER = register("paper"), BURN = register("burn"),
            CLOCK = register("clock"), LINK = register("link");

    private ModSounds() {}
    private static SoundEvent register(String name) {
        var id = ModContent.id("machine." + name);
        return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
    }
    public static void initialize() {}
}
