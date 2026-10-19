package dervogel101.de.emulator_i86_crafted.mixin.client;

import dervogel101.de.emulator_i86_crafted.client.ClientEmissiveModels;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
abstract class EmissiveBlockUpdatesMixin {
    @Inject(method = "setBlocksDirty", at = @At("TAIL"))
    private void i86$trackEmissiveBlock(BlockPos pos, BlockState previous, BlockState state, CallbackInfo callback) {
        ClientEmissiveModels.blockChanged((ClientLevel) (Object) this, pos, state);
    }
}
