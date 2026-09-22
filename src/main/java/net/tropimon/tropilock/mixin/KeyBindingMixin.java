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
    private void tropilock$blockKeys(CallbackInfoReturnable<Boolean> cir) {
        if (!TropiLock.locked && !TropiLock.arrivalBrake) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null || client.player == null) {
            return;
        }

        Object self = this;

        // Arrivee : l'avance est coupee pour que la monture s'arrete sur la cible
        if (TropiLock.arrivalBrake && self == client.options.forwardKey) {
            cir.setReturnValue(false);
            return;
        }

        // Verrouillage : pas de deplacement lateral
        if (TropiLock.locked && (self == client.options.leftKey || self == client.options.rightKey)) {
            cir.setReturnValue(false);
        }
    }
}
