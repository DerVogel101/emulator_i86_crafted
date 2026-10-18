package dervogel101.de.emulator_i86_crafted.mixin.client;

import dervogel101.de.emulator_i86_crafted.client.ClientKeyboard;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = KeyboardHandler.class, priority = 2000)
abstract class KeyboardCaptureMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void i86$capture(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (ClientKeyboard.key(window, action, event)) ci.cancel();
    }
    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void i86$characters(long window, CharacterEvent event, CallbackInfo ci) { if (ClientKeyboard.active()) ci.cancel(); }
    @Inject(method = "textInput", at = @At("HEAD"), cancellable = true)
    private void i86$text(long window, String text, CallbackInfo ci) { if (ClientKeyboard.active()) ci.cancel(); }
    @Inject(method = "textEditing", at = @At("HEAD"), cancellable = true)
    private void i86$composition(long window, PreeditEvent event, CallbackInfo ci) { if (ClientKeyboard.active()) ci.cancel(); }
}
