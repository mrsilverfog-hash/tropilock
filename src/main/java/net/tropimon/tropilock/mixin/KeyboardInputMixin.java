package net.tropimon.tropilock.mixin;

import net.minecraft.client.input.KeyboardInput;
import net.minecraft.client.input.Input;
import net.tropimon.tropilock.TropiLock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void tropilock$blockStrafe(CallbackInfo ci) {
        if (!TropiLock.locked) {
            return;
        }
        Input self = (Input) (Object) this;
        self.movementSideways = 0.0F;
        self.pressingLeft = false;
        self.pressingRight = false;
    }
}
