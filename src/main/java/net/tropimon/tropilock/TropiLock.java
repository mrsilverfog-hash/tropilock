package net.tropimon.tropilock;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

public class TropiLock implements ClientModInitializer {

    public static boolean locked = false;
    public static boolean hasTarget = false;
    public static double targetX = 0.0;
    public static double targetZ = 0.0;

    private static KeyBinding toggleKey;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.tropilock.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                "category.tropilock"
        ));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            dispatcher.register(ClientCommandManager.literal("lock")
                    .then(ClientCommandManager.literal("off")
                            .executes(ctx -> {
                                locked = false;
                                ctx.getSource().sendFeedback(
                                        Text.literal("[TropiLock] Verrouillage desactive.")
                                                .formatted(Formatting.YELLOW));
                                return 1;
                            }))
                    .then(ClientCommandManager.argument("x", DoubleArgumentType.doubleArg())
                            .then(ClientCommandManager.argument("z", DoubleArgumentType.doubleArg())
                                    .executes(ctx -> {
                                        targetX = DoubleArgumentType.getDouble(ctx, "x");
                                        targetZ = DoubleArgumentType.getDouble(ctx, "z");
                                        hasTarget = true;
                                        locked = true;
                                        ctx.getSource().sendFeedback(
                                                Text.literal(String.format(
                                                        "[TropiLock] Cap verrouille sur %.0f / %.0f (F8 pour liberer).",
                                                        targetX, targetZ))
                                                        .formatted(Formatting.GREEN));
                                        return 1;
                                    }))));
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                if (!hasTarget) {
                    if (client.player != null) {
                        client.player.sendMessage(
                                Text.literal("[TropiLock] Aucune cible : utilise /lock <x> <z>.")
                                        .formatted(Formatting.RED), false);
                    }
                } else {
                    locked = !locked;
                    if (client.player != null) {
                        client.player.sendMessage(
                                Text.literal(locked
                                        ? "[TropiLock] Verrouillage actif."
                                        : "[TropiLock] Verrouillage desactive.")
                                        .formatted(locked ? Formatting.GREEN : Formatting.YELLOW), false);
                    }
                }
            }

            if (!locked || client.player == null) {
                return;
            }

            ClientPlayerEntity player = client.player;
            double dx = targetX - player.getX();
            double dz = targetZ - player.getZ();
            double distance = Math.sqrt(dx * dx + dz * dz);

            if (distance < 3.0) {
                locked = false;
                player.sendMessage(
                        Text.literal("[TropiLock] Cible atteinte, verrouillage libere.")
                                .formatted(Formatting.GREEN), false);
                return;
            }

            float yaw = MathHelper.wrapDegrees((float) (MathHelper.atan2(dz, dx) * 57.2957795) - 90.0F);
            player.setYaw(yaw);
            player.prevYaw = yaw;
            player.headYaw = yaw;
            player.prevHeadYaw = yaw;
            player.bodyYaw = yaw;
            player.prevBodyYaw = yaw;

            player.sendMessage(
                    Text.literal(String.format("TropiLock >> %.0f blocs -- cible %.0f / %.0f",
                            distance, targetX, targetZ))
                            .formatted(Formatting.AQUA), true);
        });
    }
}
