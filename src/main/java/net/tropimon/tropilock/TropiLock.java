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

    /** Fraction de l'ecart comblee par frame. Sous 1.0 : approche sans depassement. */
    private static final float APPROACH = 0.5F;

    /** Vitesse de rotation maximale autorisee, en degres par seconde. */
    private static final float MAX_TURN_RATE = 120.0F;

    /** En dessous de cet ecart, on ne touche plus a rien. */
    private static final float DEADZONE_DEGREES = 0.05F;

    /** Vitesse d'apprentissage du facteur de conversion (0 = fige, 1 = brutal). */
    private static final double CALIBRATION_RATE = 0.15;

    private static KeyBinding toggleKey;

    // Etat du correcteur, remis a zero a chaque activation.
    private static long lastTimeNanos = 0L;
    private static boolean hasHistory = false;

    // Auto-calibration : degres de pivotement obtenus par unite de delta souris.
    private static double conversion = 0.0;
    private static boolean conversionReady = false;
    private static double lastInjectedDelta = 0.0;
    private static float yawAtInjection = 0.0F;

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

    /** Estimation theorique de depart, affinee ensuite par la mesure. */
    private static double theoreticalConversion(MinecraftClient client) {
        double sensitivity = client.options.getMouseSensitivity().getValue();
        double base = sensitivity * 0.6 + 0.2;
        return base * base * base * 8.0 * 0.15;
    }

    private static void resetController() {
        hasHistory = false;
        lastTimeNanos = 0L;
        lastInjectedDelta = 0.0;
        yawAtInjection = 0.0F;
        conversionReady = false;
        conversion = 0.0;
    }

    /**
     * Asservissement direct avec auto-calibration.
     *
     * A chaque frame on regarde de combien la monture a reellement pivote suite au
     * delta injecte la frame precedente, ce qui donne le facteur de conversion reel
     * degres/delta. On s'en sert pour injecter exactement le mouvement qui comble
     * une fraction APPROACH de l'ecart restant. Aucun terme derive, donc rien qui
     * puisse depasser la consigne et rebondir.
     */
    public static double computeSteeringDelta() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.options == null) {
            return 0.0;
        }

        ClientPlayerEntity player = client.player;
        float yaw = player.getYaw();
        long now = System.nanoTime();

        if (!conversionReady) {
            conversion = theoreticalConversion(client);
            conversionReady = conversion > 0.0;
            if (!conversionReady) {
                return 0.0;
            }
        }

        if (!hasHistory) {
            hasHistory = true;
            lastTimeNanos = now;
            yawAtInjection = yaw;
            lastInjectedDelta = 0.0;
            return 0.0;
        }

        float dt = (float) ((now - lastTimeNanos) / 1_000_000_000.0);
        dt = MathHelper.clamp(dt, 0.001F, 0.1F);
        lastTimeNanos = now;

        // Mesure : le pivotement obtenu depuis la derniere injection.
        if (Math.abs(lastInjectedDelta) > 0.5) {
            double achieved = MathHelper.wrapDegrees(yaw - yawAtInjection);
            double observed = achieved / lastInjectedDelta;

            // On ne retient que les mesures plausibles : meme sens, ordre de grandeur sain.
            if (observed > 0.0 && observed < conversion * 20.0) {
                conversion = conversion * (1.0 - CALIBRATION_RATE) + observed * CALIBRATION_RATE;
            }
        }

        float error = MathHelper.wrapDegrees(currentBearing(player) - yaw);

        if (Math.abs(error) < DEADZONE_DEGREES) {
            yawAtInjection = yaw;
            lastInjectedDelta = 0.0;
            return 0.0;
        }

        float maxStep = MAX_TURN_RATE * dt;
        float step = MathHelper.clamp(error * APPROACH, -maxStep, maxStep);

        if (conversion <= 0.0001) {
            return 0.0;
        }

        double delta = step / conversion;

        yawAtInjection = yaw;
        lastInjectedDelta = delta;

        return delta;
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
