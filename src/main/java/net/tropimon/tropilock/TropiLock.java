package net.tropimon.tropilock;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

public class TropiLock implements ClientModInitializer {

    public static boolean locked = false;
    public static boolean hasTarget = false;
    public static double targetX = 0.0;
    public static double targetZ = 0.0;

    /** Distance a la cible (en blocs) a laquelle le verrouillage se libere tout seul. */
    private static final double RELEASE_RADIUS = 20.0;

    /** Gain proportionnel : nervosite du virage. */
    private static final float GAIN_P = 4.0F;

    /** Gain derive : freinage anticipe. Monter si ca depasse le cap. */
    private static final float GAIN_D = 1.2F;

    /** Vitesse de rotation maximale autorisee, en degres par seconde. */
    private static final float MAX_TURN_RATE = 90.0F;

    /** En dessous de cet ecart, on considere qu'on est aligne. */
    private static final float DEADZONE_DEGREES = 0.4F;

    private static KeyBinding toggleKey;

    // Etat du correcteur, remis a zero a chaque activation.
    private static float lastYaw = 0.0F;
    private static long lastTimeNanos = 0L;
    private static boolean hasHistory = false;

    public static boolean isActive() {
        if (!locked) {
            return false;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        return client != null && client.player != null && client.currentScreen == null;
    }

    public static boolean isMounted() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            return false;
        }
        Entity vehicle = client.player.getRootVehicle();
        return vehicle != null && vehicle != client.player;
    }

    public static float currentBearing(ClientPlayerEntity player) {
        double dx = targetX - player.getX();
        double dz = targetZ - player.getZ();
        return MathHelper.wrapDegrees((float) (MathHelper.atan2(dz, dx) * 57.2957795) - 90.0F);
    }

    private static void resetController() {
        hasHistory = false;
        lastTimeNanos = 0L;
        lastYaw = 0.0F;
    }

    /**
     * Correcteur proportionnel-derive.
     * P pousse vers le cap, D freine quand la monture tourne deja vite.
     * Tout est exprime en degres par seconde puis ramene au temps ecoule depuis
     * la frame precedente, donc le comportement ne depend pas du framerate.
     */
    public static double computeSteeringDelta() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.options == null) {
            return 0.0;
        }

        ClientPlayerEntity player = client.player;
        float yaw = player.getYaw();
        long now = System.nanoTime();

        if (!hasHistory) {
            lastYaw = yaw;
            lastTimeNanos = now;
            hasHistory = true;
            return 0.0;
        }

        float dt = (float) ((now - lastTimeNanos) / 1_000_000_000.0);
        dt = MathHelper.clamp(dt, 0.001F, 0.1F);

        float yawRate = MathHelper.wrapDegrees(yaw - lastYaw) / dt;

        lastYaw = yaw;
        lastTimeNanos = now;

        float error = MathHelper.wrapDegrees(currentBearing(player) - yaw);

        if (Math.abs(error) < DEADZONE_DEGREES && Math.abs(yawRate) < 1.0F) {
            return 0.0;
        }

        float commandedRate = MathHelper.clamp(
                GAIN_P * error - GAIN_D * yawRate,
                -MAX_TURN_RATE,
                MAX_TURN_RATE);

        float step = commandedRate * dt;

        double sensitivity = client.options.getMouseSensitivity().getValue();
        double base = sensitivity * 0.6 + 0.2;
        double factor = base * base * base * 8.0;

        if (factor <= 0.0) {
            return 0.0;
        }

        return step / (factor * 0.15);
    }

    private static String toggleKeyName() {
        if (toggleKey == null) {
            return "touche non definie";
        }
        if (toggleKey.isUnbound()) {
            return "touche non assignee";
        }
        return toggleKey.getBoundKeyLocalizedText().getString();
    }

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
                                resetController();
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
                                        resetController();
                                        ctx.getSource().sendFeedback(
                                                Text.literal(String.format(
                                                        "[TropiLock] Cap verrouille sur %.0f / %.0f (%s pour liberer).",
                                                        targetX, targetZ, toggleKeyName()))
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
                    resetController();
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

            if (distance < RELEASE_RADIUS) {
                locked = false;
                resetController();
                player.sendMessage(
                        Text.literal(String.format(
                                "[TropiLock] Cible a moins de %.0f blocs, verrouillage libere.",
                                RELEASE_RADIUS))
                                .formatted(Formatting.GREEN), false);
                return;
            }

            if (!isMounted()) {
                applyYaw(player, currentBearing(player));
            }
        });
    }

    private static void applyYaw(Entity entity, float yaw) {
        entity.setYaw(yaw);
        entity.prevYaw = yaw;
        entity.setHeadYaw(yaw);
        if (entity instanceof LivingEntity living) {
            living.prevHeadYaw = yaw;
            living.bodyYaw = yaw;
            living.prevBodyYaw = yaw;
        }
    }
}
