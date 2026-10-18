package dervogel101.de.i86.runtime;

public final class SoundActivitySelfTest {
    private SoundActivitySelfTest() {}
    public static void run() {
        var sound = new SoundActivity();
        sound.update(IndicatorState.RUNNING);
        assert sound.poll(0, 4, 7) == 0 : "Joining a running machine must not replay boot";
        sound.update(IndicatorState.OFF);
        assert sound.poll(1, 4, 7) == SoundActivity.STOP;
        sound.update(IndicatorState.RUNNING);
        assert sound.poll(2, 4, 7) == 0 : "Power chatter is coalesced";
        sound.update(IndicatorState.OFF);
        assert sound.poll(11, 4, 7) == SoundActivity.STOP : "Only the latest transition survives";
        sound.update(IndicatorState.RUNNING);
        assert sound.poll(21, 4, 7) == SoundActivity.START;
        sound.update(IndicatorState.IDLE);
        assert sound.powered();
        assert sound.poll(31, 4, 7) == 0 : "CPU halt/wait is not another boot";

        var punch = new SoundActivity();
        punch.update(IndicatorState.pack(IndicatorState.RUNNING, 2));
        assert punch.poll(0, 4, 5) == 0 : "Deck presence is silent";
        for (int tick = 1; tick < 4; tick++) {
            punch.update(IndicatorState.pack(IndicatorState.RUNNING, tick == 1 ? 1 : 4));
            assert punch.poll(tick, 4, 5) == 0;
        }
        punch.update(IndicatorState.RUNNING);
        assert punch.poll(4, 4, 5) == 5 : "Short row and feed pulses survive until the next batch";
        assert punch.poll(8, 4, 5) == 0 : "No backlog after the batch";
        int batches = 0;
        for (int tick = 9; tick <= 108; tick++) {
            punch.update(IndicatorState.pack(IndicatorState.RUNNING, 7));
            if (punch.poll(tick, 4, 5) != 0) batches++;
        }
        assert batches == 25 : "Every-tick activity must stay at five batches per second";
        punch.update(IndicatorState.OFF);
        assert punch.poll(109, 4, 5) == SoundActivity.STOP;
        assert punch.poll(112, 4, 5) == 0 : "Shutdown clears pending work";
        assert !punch.powered();

        var terminal = new SoundActivity();
        terminal.update(IndicatorState.pack(IndicatorState.RUNNING, 2));
        assert terminal.poll(0, 6, 5) == 0 : "A viewer is not terminal output";
        terminal.update(IndicatorState.pack(IndicatorState.RUNNING, 1));
        terminal.update(IndicatorState.RUNNING);
        assert terminal.poll(6, 6, 5) == 1 : "Brief terminal output is audible once";
        terminal.update(IndicatorState.FAILURE);
        assert terminal.poll(7, 6, 5) == SoundActivity.FAULT;
        terminal.update(IndicatorState.FAILURE);
        assert terminal.poll(30, 6, 5) == 0 : "A persistent fault does not repeat its alert";

        var sign = new SoundActivity();
        var lamps = new IndicatorState.Lamps();
        int written = IndicatorState.RUNNING | IndicatorState.SIGN_WRITE;
        sign.update(written);
        lamps.update(written, 0);
        assert sign.poll(0, 8, 12) == 8 : "World sign writes have their own sound bit";
        assert lamps.brightness(3, 0) == 0 : "A sign write must not flash the click lamp";
        System.out.println("Sound checks passed: every-tick coalescing, cooldowns, startup/shutdown, terminal/sign pulses, silent joins and held states");
    }
}
