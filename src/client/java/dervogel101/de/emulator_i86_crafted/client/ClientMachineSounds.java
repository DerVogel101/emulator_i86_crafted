package dervogel101.de.emulator_i86_crafted.client;

import dervogel101.de.emulator_i86_crafted.ModContent;
import dervogel101.de.emulator_i86_crafted.ModSounds;
import dervogel101.de.emulator_i86_crafted.block.MachineBlock;
import dervogel101.de.i86.runtime.IndicatorState;
import dervogel101.de.i86.runtime.SoundActivity;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import java.util.*;

/** Positional audio derived entirely from the existing batched indicator packets. */
public final class ClientMachineSounds {
    private static final int MAX_FANS = 3, MAX_ONE_SHOTS = 6;
    private static final Map<MachineBlock, Kind> KINDS = new HashMap<>();
    private static final Map<BlockPos, SoundActivity> STATES = new HashMap<>();
    private static final Map<BlockPos, Fan> FANS = new HashMap<>();
    private static ClientLevel level;
    private static long ticks;
    private static int remaining;

    private enum Kind {
        CONTROLLER("mainframe_controller", 0, 10, .020f), CLOCK("atomic_clock", 6, 30, 0),
        INTERRUPTS("interrupt_controller", 6, 16, 0), TERMINAL("access_terminal", 5, 6, 0),
        GPU("gpu_array", 4, 12, .012f), GPIO("gpio_controller", 6, 12, 0),
        DISK("harddisk_array", 7, 6, .018f), SHARED("ender_tunnel_array", 7, 16, .012f),
        NVRAM("integrated_storage", 4, 20, 0), POWER("power_supply", 0, 10, .025f),
        RESET("reset_port", 6, 10, 0), NMI("nmi_port", 6, 10, 0), TEST("test_port", 4, 12, 0),
        KEYBOARD_CONTROLLER("keyboard_controller", 2, 12, 0), PUNCH_CONTROLLER("punchcard_controller", 4, 12, 0),
        KEYBOARD("keyboard", 1, 3, 0), REDSTONE("redstone_port", 4, 6, 0), SIGN("sign_port", 12, 8, 0),
        READER("punchcard_reader", 5, 4, 0), WRITER("punchcard_writer", 5, 4, 0), BURNER("rom_burner", 7, 12, 0);

        final String id;
        final int mask, interval;
        final float fan;
        Kind(String id, int mask, int interval, float fan) {
            this.id = id; this.mask = mask; this.interval = interval; this.fan = fan;
        }
    }

    private record Voice(BlockPos position, MachineBlock block, Kind kind, SoundActivity state, int cues, double distance) {}

    private ClientMachineSounds() {}
    public static void initialize() {
        for (var kind : Kind.values()) KINDS.put(ModContent.BLOCKS.get(kind.id), kind);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        ClientTickEvents.END_CLIENT_TICK.register(ClientMachineSounds::tick);
    }

    public static void update(ClientLevel current, BlockPos position, int flags) {
        if (level != current) { clear(); level = current; }
        if (flags == IndicatorState.REMOVE) {
            STATES.remove(position);
            stopFan(position);
        } else STATES.computeIfAbsent(position.immutable(), ignored -> new SoundActivity()).update(flags);
    }

    private static void clear() {
        for (var position : List.copyOf(FANS.keySet())) stopFan(position);
        STATES.clear();
        level = null;
        ticks = 0;
    }

    private static void stopFan(BlockPos position) {
        var fan = FANS.remove(position);
        if (fan != null) {
            fan.finish();
            Minecraft.getInstance().getSoundManager().stop(fan);
        }
    }

    private static void tick(Minecraft client) {
        if (client.level != level) { clear(); level = client.level; }
        if (level == null || client.player == null || client.isPaused()) return;
        ticks++;
        remaining = MAX_ONE_SHOTS;
        var nearby = new ArrayList<Voice>();
        for (var entry : STATES.entrySet()) {
            var position = entry.getKey();
            if (!level.hasChunkAt(position)) continue;
            var block = level.getBlockState(position).getBlock();
            var kind = KINDS.get(block);
            if (kind == null) continue;
            int cues = level.tickRateManager().runsNormally() ? entry.getValue().poll(ticks, kind.interval, kind.mask) : 0;
            double distance = client.player.distanceToSqr(position.getX() + .5, position.getY() + .5, position.getZ() + .5);
            // Drain distant activity too: approaching a rack must not replay its backlog.
            if (distance <= 16 * 16) nearby.add(new Voice(position, (MachineBlock) block, kind, entry.getValue(), cues, distance));
        }
        nearby.sort(Comparator.comparingDouble(Voice::distance).thenComparingLong(voice -> voice.position().asLong()));
        for (var voice : nearby) if (voice.kind() == Kind.CONTROLLER) transitions(voice);
        for (var voice : nearby) if (voice.kind() != Kind.CONTROLLER) transitions(voice);
        // Rotate priority so a busy bank of devices cannot monopolize the local sound budget.
        for (int i = 0; i < nearby.size() && remaining > 0; i++) activity(nearby.get((int) ((ticks + i) % nearby.size())));

        var wanted = new HashSet<BlockPos>();
        if (client.options.getFinalSoundSourceVolume(SoundSource.BLOCKS) > 0) {
            for (var voice : nearby) if (voice.kind().fan > 0 && voice.state().powered() && wanted.size() < MAX_FANS) {
                wanted.add(voice.position());
                var fan = FANS.get(voice.position());
                if (fan != null && (fan.isStopped() || ticks % 20 == 0 && !client.getSoundManager().isActive(fan))) {
                    stopFan(voice.position());
                    fan = null;
                }
                if (fan == null) {
                    fan = new Fan(voice);
                    FANS.put(voice.position(), fan);
                    client.getSoundManager().play(fan);
                }
            }
        }
        for (var position : List.copyOf(FANS.keySet())) if (!wanted.contains(position)) stopFan(position);
    }

    private static void transitions(Voice voice) {
        int cues = voice.cues();
        if (voice.kind() == Kind.CONTROLLER) {
            if ((cues & SoundActivity.START) != 0) {
                play(voice, ModSounds.RELAY, .12f, .8f);
                play(voice, ModSounds.START, .12f, 1.2f);
            } else if ((cues & SoundActivity.STOP) != 0) play(voice, ModSounds.STOP, .10f, .7f);
        } else if (voice.kind() == Kind.POWER && (cues & (SoundActivity.START | SoundActivity.STOP)) != 0) {
            play(voice, ModSounds.RELAY, .07f, (cues & SoundActivity.START) != 0 ? .8f : .6f);
        }
        if ((cues & SoundActivity.FAULT) != 0 && (voice.kind() == Kind.CONTROLLER || voice.kind() == Kind.BURNER
                || voice.kind() == Kind.READER || voice.kind() == Kind.WRITER || voice.kind() == Kind.REDSTONE
                || voice.kind() == Kind.SIGN || voice.kind() == Kind.KEYBOARD)) play(voice, ModSounds.FAULT, .09f, .8f);
    }

    private static void activity(Voice voice) {
        if ((voice.cues() & 15) == 0) return;
        switch (voice.kind()) {
            case CONTROLLER, POWER -> { }
            case READER, WRITER -> {
                if ((voice.cues() & 4) != 0) {
                    play(voice, ModSounds.CARD_FEED, .10f, voice.kind() == Kind.WRITER ? .85f : 1.1f);
                    play(voice, ModSounds.PAPER, .08f, 1.3f);
                } else {
                    play(voice, voice.kind() == Kind.WRITER ? ModSounds.CARD_WRITE : ModSounds.CARD_READ,
                            voice.kind() == Kind.WRITER ? .07f : .055f, 1.5f);
                    play(voice, ModSounds.RELAY, .025f, 1.7f);
                }
            }
            case DISK -> play(voice, ModSounds.DISK, .055f, (voice.cues() & 2) != 0 ? .8f : 1.3f);
            case TERMINAL -> play(voice, (voice.cues() & 4) != 0 ? ModSounds.FAULT : ModSounds.TERMINAL, .035f, 1.4f);
            case GPU -> play(voice, ModSounds.SIGN, .03f, 1.3f);
            case SIGN -> play(voice, (voice.cues() & 8) != 0 ? ModSounds.SIGN : ModSounds.RELAY, .03f, 1.3f);
            case KEYBOARD -> play(voice, ModSounds.KEY, .035f, 1.5f);
            case CLOCK -> play(voice, ModSounds.CLOCK, .018f, (voice.cues() & 4) != 0 ? 1.2f : .9f);
            case SHARED -> play(voice, ModSounds.LINK, .025f, 1.6f);
            case BURNER -> play(voice, (voice.cues() & 4) != 0 ? ModSounds.BURN : ModSounds.RELAY, .06f, 1.2f);
            default -> play(voice, ModSounds.RELAY, .035f, (voice.cues() & 4) != 0 ? .8f : 1.2f);
        }
    }

    private static void play(Voice voice, SoundEvent event, float volume, float pitch) {
        if (remaining == 0) return;
        remaining--;
        Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(event, SoundSource.BLOCKS,
                volume * 1.2f, pitch, RandomSource.create(), voice.position()));
    }

    private static final class Fan extends AbstractTickableSoundInstance {
        private final ClientLevel world = level;
        private final BlockPos position;
        private final MachineBlock block;
        private final float target;
        Fan(Voice voice) {
            super(ModSounds.FAN, SoundSource.BLOCKS, RandomSource.create());
            position = voice.position(); block = voice.block(); target = voice.kind().fan * 1.05f;
            x = position.getX() + .5; y = position.getY() + .5; z = position.getZ() + .5;
            looping = true;
            volume = .001f * 1.05f;
            pitch = .775f + (position.hashCode() & 3) * .025f;
        }
        @Override public void tick() {
            var state = STATES.get(position);
            if (level != world || state == null || !state.powered() || !world.hasChunkAt(position)
                    || !world.getBlockState(position).is(block)) stop();
            else volume = Math.min(target, volume + .0015f * 1.05f);
        }
        void finish() { stop(); }
    }
}
