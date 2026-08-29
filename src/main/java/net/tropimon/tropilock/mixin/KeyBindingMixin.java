package net.tropimon.tropilock.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.tropimon.tropilock.TropiLock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(KeyBinding.class)
public abstract class KeyBindingMixin {

    @Inject(method = "isPressed", at = @At("HEAD"), cancellable = true)
    private void tropilock$blockStrafe(CallbackInfoReturnable<Boolean> cir) {
        if (!TropiLock.locked) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null || client.player == null) {
            return;
        }

        Object self = this;
        if (self == client.options.leftKey || self == client.options.rightKey) {
            cir.setReturnValue(false);
        }
    }
}
