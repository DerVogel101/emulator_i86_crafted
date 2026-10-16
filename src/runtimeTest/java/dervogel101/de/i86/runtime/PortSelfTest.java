package dervogel101.de.i86.runtime;

import dervogel101.de.emulator_i86_crafted.server.storage.*;
import java.io.*;
import java.util.Arrays;

final class PortSelfTest {
    static void run() throws Exception {
        directionalBindings();
        var world = new WorldRecords(); var machine = world.allocate();
        var first = new WorldRecords.Location("minecraft:overworld", 1, 64, 1);
        var second = new WorldRecords.Location("minecraft:overworld", 10_000, 64, 1);
        var settings = new PortBindings.Settings(machine.id, 32, "Repeated name", true, RedstoneIrqMode.ANALOG, 3);
        world.ports.put(first, PortBindings.Kind.REDSTONE, settings);
        world.ports.put(second, PortBindings.Kind.REDSTONE, settings);
        assert world.ports.conflicts(first).size() == 2 : "Unloaded assignments must still conflict";
        world.ports.put(second, PortBindings.Kind.SIGN, settings);
        assert world.ports.conflicts(first).isEmpty() : "Sign and redstone slot spaces are independent";
        world.ports.put(second, PortBindings.Kind.REDSTONE, settings);
        machine.redstoneOutputs[127] = 15; machine.signOutputs[32][127] = (byte) 0x80;
        var restored = WorldRecords.load(world.save());
        assert restored.ports.entries().equals(world.ports.entries());
        assert restored.ports.conflicts(first).size() == 2;
        var retained = restored.mainframe(machine.id).orElseThrow();
        assert retained.redstoneOutputs[127] == 15 && retained.signOutputs[32][127] == (byte) 0x80;
        restored.ports.remove(second);
        assert restored.ports.conflicts(first).isEmpty();
        restored.ports.put(second, PortBindings.Kind.REDSTONE, PortBindings.Settings.unbound());
        assert restored.ports.conflicts(second).isEmpty();
        expectInvalid(() -> new PortBindings.Settings(-1, 0, "", true, RedstoneIrqMode.DISABLED, 0));
        expectInvalid(() -> new PortBindings.Settings(1, 128, "", true, RedstoneIrqMode.DISABLED, 0));
        expectInvalid(() -> new PortBindings.Settings(1, 0, "x".repeat(129), true, RedstoneIrqMode.DISABLED, 0));
        var old = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(old)) {
            out.writeInt(0x49383657); out.writeInt(1); out.writeLong(1); out.writeLong(0);
            out.writeInt(0); out.writeInt(0); out.writeInt(0);
        }
        assert WorldRecords.load(old.toByteArray()).ports.entries().isEmpty() : "Existing v1 worlds must remain readable";
        byte[] sign = new byte[128];
        for (int side = 0; side < 2; side++) for (int line = 0; line < 4; line++) {
            Arrays.fill(sign, (byte) 0);
            int offset = side * 64 + line * 16;
            sign[offset] = (byte) 0xE7; sign[offset + 1] = (byte) 0x80; sign[offset + 2] = (byte) 0xE4;
            sign[offset + 4] = 'X';
            assert SignLine.decode(sign, side, line).equals(new SignLine("€ä", 0xE7));
            Arrays.fill(sign, offset + 1, offset + 16, (byte) 'A');
            assert SignLine.decode(sign, side, line).text().equals("A".repeat(15));
        }
        expectInvalid(() -> SignLine.decode(new byte[127], 0, 0));
        expectInvalid(() -> SignLine.decode(sign, 2, 0));
        expectInvalid(() -> SignLine.decode(sign, 0, 4));
        System.out.println("Port checks passed: retained conflicts, independent slots, world v1/v2, cached outputs, Windows-1252 sign lines");
    }
    private static void directionalBindings() throws Exception {
        var world = new WorldRecords();
        long id = world.allocate().id;
        var first = new WorldRecords.Location("minecraft:overworld", 1, 64, 1);
        var second = new WorldRecords.Location("minecraft:overworld", 2, 64, 1);
        var third = new WorldRecords.Location("minecraft:overworld", 3, 64, 1);
        var input = new PortBindings.Settings(id, 0, "Same name", true, RedstoneIrqMode.ANALOG, 0);
        var output = new PortBindings.Settings(id, 0, "Same name", false, RedstoneIrqMode.DISABLED, 0);
        world.ports.put(first, PortBindings.Kind.REDSTONE, input);
        world.ports.put(second, PortBindings.Kind.REDSTONE, output);
        assert world.ports.conflicts(first).isEmpty() && world.ports.conflicts(second).isEmpty()
                : "Input 0 and output 0 have separate slot namespaces";
        world.ports.put(third, PortBindings.Kind.REDSTONE, input);
        world = WorldRecords.load(world.save());
        assert world.ports.conflicts(first).size() == 2 && world.ports.conflicts(third).size() == 2
                : "Same-direction duplicates still conflict after saving/reloading";
        assert world.ports.conflicts(second).isEmpty() : "An input duplicate cannot disable output 0";
        world.ports.put(third, PortBindings.Kind.REDSTONE, output);
        assert world.ports.conflicts(first).isEmpty() : "Changing direction releases the old namespace";
        assert world.ports.conflicts(second).size() == 2 : "Output duplicates still conflict";
        world.ports.remove(third);
        assert world.ports.conflicts(second).isEmpty();
        world.ports.put(first, PortBindings.Kind.SIGN, input);
        world.ports.put(second, PortBindings.Kind.SIGN, output);
        assert world.ports.conflicts(first).size() == 2 : "Sign IDs do not acquire input/output namespaces";
    }

    private static void expectInvalid(Runnable action) {
        try { action.run(); throw new AssertionError("Invalid port/sign value accepted"); }
        catch (IllegalArgumentException expected) { }
    }
}
