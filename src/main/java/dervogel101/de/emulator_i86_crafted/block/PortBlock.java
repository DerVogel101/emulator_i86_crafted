package dervogel101.de.emulator_i86_crafted.block;

import dervogel101.de.emulator_i86_crafted.server.*;
import dervogel101.de.emulator_i86_crafted.server.storage.PortBindings;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.BlockHitResult;

public final class PortBlock extends MachineBlock implements EntityBlock {
    public static final EnumProperty<Direction> PORT_FACING = BlockStateProperties.FACING;
    public static final BooleanProperty INPUT = BooleanProperty.create("input");
    private final PortBindings.Kind kind;
    public PortBlock(Properties properties, PortBindings.Kind kind) {
        super(properties, null); this.kind = kind;
        registerDefaultState(defaultBlockState().setValue(INPUT, true));
    }
    public PortBindings.Kind kind() { return kind; }
    @Override protected EnumProperty<Direction> facingProperty() { return PORT_FACING; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder); builder.add(INPUT);
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(PORT_FACING, context.getNearestLookingDirection().getOpposite());
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new PortBlockEntity(pos, state); }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof PortBlockEntity port) {
            if (kind == PortBindings.Kind.KEYBOARD && !player.isShiftKeyDown())
                ServerMachines.get(serverPlayer.level().getServer()).keyboards.capture(serverPlayer, port);
            else PortDialogs.open(serverPlayer, port);
        }
        return InteractionResult.SUCCESS;
    }
    @Override protected boolean isSignalSource(BlockState state) { return kind == PortBindings.Kind.REDSTONE; }
    @Override protected boolean shouldRedstoneWireConnectTo(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return kind == PortBindings.Kind.REDSTONE && direction == state.getValue(PORT_FACING).getOpposite();
    }
    @Override protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return kind == PortBindings.Kind.REDSTONE && direction == state.getValue(PORT_FACING).getOpposite()
                && level.getBlockEntity(pos) instanceof PortBlockEntity port ? port.signal() : 0;
    }
    @Override protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return getSignal(state, level, pos, direction);
    }
    @Override protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean moving) {
        ServerMachines.get(level.getServer()).ports.remove(level, pos);
        super.affectNeighborsAfterRemoval(state, level, pos, moving);
    }
}
