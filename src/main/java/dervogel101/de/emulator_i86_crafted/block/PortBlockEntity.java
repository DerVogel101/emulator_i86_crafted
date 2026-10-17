package dervogel101.de.emulator_i86_crafted.block;

import dervogel101.de.emulator_i86_crafted.*;
import dervogel101.de.emulator_i86_crafted.server.*;
import dervogel101.de.emulator_i86_crafted.server.storage.PortBindings;
import dervogel101.de.i86.runtime.RedstoneIrqMode;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.*;

public final class PortBlockEntity extends BlockEntity {
    private PortBindings.Settings settings = PortBindings.Settings.unbound();
    private int signal;
    public String status = "Unbound";
    public boolean online;
    public Object sampledBridge, signTarget;
    public int sampledInput;
    public RedstoneIrqMode appliedIrq;
    public int appliedSignSides = -1;
    public long signVersion = Long.MIN_VALUE;
    public long activityUntil;

    public PortBlockEntity(BlockPos pos, BlockState state) { super(ModBlockEntities.PORT, pos, state); }
    public PortBindings.Kind kind() { return ((PortBlock) getBlockState().getBlock()).kind(); }
    public PortBindings.Settings settings() { return settings; }
    public void settings(PortBindings.Settings next) {
        applySettings(next);
        if (level instanceof ServerLevel server) ServerMachines.get(server.getServer()).ports.changed(this);
    }
    public void applySettings(PortBindings.Settings next) {
        if (settings.equals(next)) return;
        if (settings.mainframe() != next.mainframe() || settings.slot() != next.slot() || settings.input() != next.input()) {
            sampledBridge = null; signTarget = null; signVersion = Long.MIN_VALUE; activityUntil = 0;
        }
        settings = next;
        setChanged();
    }
    public int signal() { return signal; }
    public void signal(int value) {
        if (signal == value) return;
        signal = value;
        if (level instanceof ServerLevel server) {
            var block = getBlockState().getBlock();
            server.updateNeighborsAt(worldPosition, block, null);
            server.updateNeighborsAt(worldPosition.relative(getBlockState().getValue(PortBlock.PORT_FACING)), block, null);
        }
    }
    @Override protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        try {
            settings = new PortBindings.Settings(input.getLongOr("mainframe_id", 0), input.getIntOr("slot", 0),
                    input.getStringOr("port_name", ""), input.getBooleanOr("input", true),
                    RedstoneIrqMode.valueOf(input.getStringOr("irq_mode", "DISABLED")), input.getIntOr("sign_sides", 0));
        } catch (IllegalArgumentException invalid) { settings = PortBindings.Settings.unbound(); status = "Invalid saved binding"; }
    }
    @Override protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putLong("mainframe_id", settings.mainframe()); output.putInt("slot", settings.slot());
        output.putString("port_name", settings.name()); output.putBoolean("input", settings.input());
        output.putString("irq_mode", settings.irq().name()); output.putInt("sign_sides", settings.signSides());
    }
}
