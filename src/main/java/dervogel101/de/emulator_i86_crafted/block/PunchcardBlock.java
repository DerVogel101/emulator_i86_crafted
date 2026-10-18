package dervogel101.de.emulator_i86_crafted.block;

import dervogel101.de.emulator_i86_crafted.server.*;
import dervogel101.de.emulator_i86_crafted.server.storage.PortBindings;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

public final class PunchcardBlock extends PortBlock {
    public static final net.minecraft.world.level.block.state.properties.BooleanProperty LOADED = net.minecraft.world.level.block.state.properties.BooleanProperty.create("loaded");
    public static final net.minecraft.world.level.block.state.properties.BooleanProperty OUTPUT = net.minecraft.world.level.block.state.properties.BooleanProperty.create("output");
    public PunchcardBlock(Properties properties, boolean writer) {
        super(properties, writer ? PortBindings.Kind.PUNCHCARD_WRITER : PortBindings.Kind.PUNCHCARD_READER);
        registerDefaultState(defaultBlockState().setValue(LOADED,false).setValue(OUTPUT,false));
    }
    @Override protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<net.minecraft.world.level.block.Block,BlockState> builder) {
        super.createBlockStateDefinition(builder); builder.add(LOADED,OUTPUT);
    }
    @Override protected EnumProperty<Direction> facingProperty() { return MachineBlock.FACING; }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return defaultBlockState().setValue(MachineBlock.FACING, context.getHorizontalDirection().getOpposite()); }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new PunchcardBlockEntity(pos,state); }
    @Override protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (player.isShiftKeyDown() || stack.isEmpty()) return useWithoutItem(state,level,pos,player,hit);
        if (level.getBlockEntity(pos) instanceof PunchcardBlockEntity device && stack.getItem() instanceof PunchcardDeckItem) {
            if (!level.isClientSide() && device.insert(stack)) stack.shrink(1);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.getBlockEntity(pos) instanceof PunchcardBlockEntity device && player instanceof ServerPlayer server) {
            if (player.isShiftKeyDown()) PortDialogs.open(server,device);
            else { var stack = device.pickup(); if (!stack.isEmpty() && !player.getInventory().add(stack)) player.drop(stack,false,net.minecraft.util.Prediction.SERVER_ONLY); }
        }
        return InteractionResult.SUCCESS;
    }
    @Override protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean moving) {
        if (level.getBlockEntity(pos) instanceof PunchcardBlockEntity device) device.dropContents();
        super.affectNeighborsAfterRemoval(state,level,pos,moving);
    }
}
