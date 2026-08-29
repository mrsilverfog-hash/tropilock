package net.tropimon.tropilock.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.tropimon.tropilock.TropiLock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Entity.class)
public abstract class EntityLookMixin {

    @ModifyVariable(method = "changeLookDirection", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private double tropilock$blockYawInput(double cursorDeltaX) {
        if (TropiLock.locked && (Object) this == MinecraftClient.getInstance().player) {
            return 0.0;
        }
        return cursorDeltaX;
    }
}
