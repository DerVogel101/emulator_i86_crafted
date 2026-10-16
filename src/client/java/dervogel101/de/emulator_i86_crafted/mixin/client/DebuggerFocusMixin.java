package dervogel101.de.emulator_i86_crafted.mixin.client;

import dervogel101.de.emulator_i86_crafted.client.ClientDebugger;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class DebuggerFocusMixin {
    @Inject(method = "pauseIfInactive", at = @At("HEAD"), cancellable = true)
    private void i86$keepDebuggerResponsive(CallbackInfo ci) {
        if (ClientDebugger.isOpen()) ci.cancel();
    }
}
