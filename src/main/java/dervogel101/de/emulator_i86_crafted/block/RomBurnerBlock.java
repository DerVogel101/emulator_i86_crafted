package dervogel101.de.emulator_i86_crafted.block;

import dervogel101.de.emulator_i86_crafted.server.ServerMachines;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class RomBurnerBlock extends MachineBlock {
    public RomBurnerBlock(Properties properties) { super(properties, null); }
    @Override protected void tick(BlockState state, net.minecraft.server.level.ServerLevel level, BlockPos pos, net.minecraft.util.RandomSource random) {
        ServerMachines.get(level.getServer()).burner.refresh(level, pos);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer) ServerMachines.get(serverPlayer.level().getServer()).burner.open(serverPlayer, pos);
        return InteractionResult.SUCCESS;
    }
}
