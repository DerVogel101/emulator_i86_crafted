package dervogel101.de.emulator_i86_crafted.block;

import dervogel101.de.emulator_i86_crafted.server.ServerMachines;
import dervogel101.de.i86.runtime.Hardware;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class ControllerBlock extends MachineBlock implements EntityBlock {
    public ControllerBlock(Properties properties) { super(properties, Hardware.CONTROLLER); }
    @Override public BlockEntity newBlockEntity(BlockPos position, BlockState state) { return new ControllerBlockEntity(position, state); }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(position) instanceof ControllerBlockEntity controller)
            dervogel101.de.emulator_i86_crafted.server.MachineDialogs.open(serverPlayer, position);
        return InteractionResult.SUCCESS;
    }
    @Override protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos position, boolean moving) {
        ServerMachines.get(level.getServer()).controllerRemoved(level, position);
        super.affectNeighborsAfterRemoval(state, level, position, moving);
    }

    @Override public void setPlacedBy(Level level, BlockPos position, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, position, state, placer, stack);
        if (level instanceof ServerLevel server && level.getBlockEntity(position) instanceof ControllerBlockEntity controller)
            ServerMachines.get(server.getServer()).attach(controller);
    }
}
