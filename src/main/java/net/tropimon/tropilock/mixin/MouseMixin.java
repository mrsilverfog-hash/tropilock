package net.tropimon.tropilock.mixin;

import net.minecraft.client.Mouse;
import net.tropimon.tropilock.TropiLock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public abstract class MouseMixin {

    @Shadow
    private double cursorDeltaX;

    @Inject(method = "updateMouse", at = @At("HEAD"))
    private void tropilock$overrideYawDelta(CallbackInfo ci) {
        if (TropiLock.isAligning()) {
            // Souris du joueur bloquee ; seule l'impulsion d'alignement passe, une fois
            this.cursorDeltaX = TropiLock.consumeAlignPulse();
            return;
        }

        if (!TropiLock.isActive()) {
            return;
        }

        if (TropiLock.isMounted() && TropiLock.usesMouseSteering()) {
            // Mode souris : on pilote la monture en injectant le mouvement voulu.
            this.cursorDeltaX = TropiLock.computeSteeringDelta();
        } else if (TropiLock.isMounted()) {
            // Mode direct : l'orientation est ecrite a chaque tick, la souris est coupee.
            this.cursorDeltaX = 0.0;
        } else {
            // A pied, le yaw est ecrit directement : on coupe juste la souris.
            this.cursorDeltaX = 0.0;
        }
    }
}
