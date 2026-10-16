package dervogel101.de.emulator_i86_crafted.block;

import dervogel101.de.i86.runtime.Hardware;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class ArrayBlock extends MachineBlock implements EntityBlock {
    public ArrayBlock(Properties properties, Hardware hardware) { super(properties, hardware); }
    @Override public BlockEntity newBlockEntity(BlockPos position, BlockState state) { return new ArrayBlockEntity(position, state); }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit) {
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)
            dervogel101.de.emulator_i86_crafted.server.MachineDialogs.open(serverPlayer, position);
        return InteractionResult.SUCCESS;
    }
}
