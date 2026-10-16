package dervogel101.de.emulator_i86_crafted.block;

import dervogel101.de.i86.runtime.Hardware;
import dervogel101.de.emulator_i86_crafted.server.ServerMachines;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;

public class MachineBlock extends Block {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
    private final Hardware hardware;

    public MachineBlock(Properties properties, Hardware hardware) {
        super(properties);
        this.hardware = hardware;
        registerDefaultState(stateDefinition.any().setValue(facingProperty(), Direction.NORTH).setValue(POWERED, false));
    }

    /** Null denotes a non-hardware block (filler, external port, or ROM burner). */
    public Hardware hardware() {
        return hardware;
    }

    protected EnumProperty<Direction> facingProperty() { return FACING; }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(facingProperty(), context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(facingProperty(), POWERED);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(facingProperty(), rotation.rotate(state.getValue(facingProperty())));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(facingProperty())));
    }

    @Override protected void onPlace(BlockState state, Level level, BlockPos position, BlockState previous, boolean moving) {
        super.onPlace(state, level, position, previous, moving);
        if (level instanceof ServerLevel server && (!previous.is(this)
                || previous.hasProperty(facingProperty()) && previous.getValue(facingProperty()) != state.getValue(facingProperty())))
            ServerMachines.get(server.getServer()).structureChanged(server, position);
    }
    @Override protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos position, boolean moving) {
        ServerMachines.get(level.getServer()).structureChanged(level, position);
        super.affectNeighborsAfterRemoval(state, level, position, moving);
    }
    @Override protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
            net.minecraft.world.entity.player.Player player, net.minecraft.world.phys.BlockHitResult hit) {
        if (hardware == null) return net.minecraft.world.InteractionResult.PASS;
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)
            dervogel101.de.emulator_i86_crafted.server.MachineDialogs.open(serverPlayer, pos);
        return net.minecraft.world.InteractionResult.SUCCESS;
    }
    @Override protected boolean isSignalSource(BlockState state) {
        return hardware == Hardware.POWER || hardware == Hardware.RESET || hardware == Hardware.NMI || hardware == Hardware.TEST;
    }

    @Override protected void neighborChanged(BlockState state, Level level, BlockPos position, Block source, Orientation orientation, boolean moving) {
        super.neighborChanged(state, level, position, source, orientation, moving);
        if (level instanceof ServerLevel server) ServerMachines.get(server.getServer()).inputsChanged(server, position);
    }
}
