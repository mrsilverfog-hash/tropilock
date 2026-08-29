package net.tropimon.tropilock.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.tropimon.tropilock.TropiLock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Entity.class)
public abstract class EntityLookMixin {

    @ModifyVariable(method = "changeLookDirection", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private double tropilock$blockYawInput(double cursorDeltaX) {
        if (!TropiLock.locked) {
            return cursorDeltaX;
        }

        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) {
            return cursorDeltaX;
        }

        Object self = this;
        if (self == player || self == player.getRootVehicle() || self == player.getVehicle()) {
            return 0.0;
        }
        return cursorDeltaX;
    }
}
