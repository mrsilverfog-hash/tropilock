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
    private void tropilock$killYawDelta(CallbackInfo ci) {
        if (TropiLock.shouldBlockMouseYaw()) {
            this.cursorDeltaX = 0.0;
        }
    }

    @Inject(method = "onCursorPos", at = @At("TAIL"), require = 0)
    private void tropilock$killYawAccumulation(CallbackInfo ci) {
        if (TropiLock.shouldBlockMouseYaw()) {
            this.cursorDeltaX = 0.0;
        }
    }
}
