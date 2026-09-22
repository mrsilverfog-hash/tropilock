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

    /*
     * Suivi de ligne.
     *
     * Viser la cible directement fait virer la monture en fin de trajet : un
     * meme ecart lateral represente un angle de plus en plus grand a mesure
     * qu'on approche. On trace donc, au verrouillage, la droite depart -> cible,
     * et on vise un point de cette droite situe LOOKAHEAD blocs devant soi.
     * Le cap reste celui de la droite ; seul un correctif proportionnel a
     * l'ecart lateral ramene la monture dessus.
     */
    private static double startX = 0.0;
    private static double startZ = 0.0;
    private static double lineUX = 0.0;
    private static double lineUZ = 1.0;
    private static double lineLength = 0.0;

    /** Distance du point vise devant soi sur la ligne. Plus grand = plus doux, plus lent a corriger. */
    private static final double LOOKAHEAD = 12.0;

    /** Distance restante (le long de la ligne) a laquelle on considere la cible atteinte. */
    private static final double ARRIVAL_DISTANCE = 1.0;

    /**
     * Frein d'arrivee : une fois la cible atteinte, la touche d'avance est
     * neutralisee jusqu'a ce que le joueur la relache, pour que la monture
     * s'arrete sur place au lieu de continuer tout droit.
     */
    public static boolean arrivalBrake = false;
    private static int brakeTicks = 0;
    private static final int BRAKE_MAX_TICKS = 100; // 5 s de securite

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

    /** Trace la ligne depuis la position actuelle du joueur jusqu'a la cible. */
    private static void setupLine(ClientPlayerEntity player) {
        startX = player.getX();
        startZ = player.getZ();
        double dx = targetX - startX;
        double dz = targetZ - startZ;
        lineLength = Math.sqrt(dx * dx + dz * dz);
        if (lineLength < 1.0E-6) {
            lineUX = 0.0;
            lineUZ = 1.0;
        } else {
            lineUX = dx / lineLength;
            lineUZ = dz / lineLength;
        }
    }

    /** Progression le long de la ligne, en blocs depuis le depart. */
    private static double alongTrack(ClientPlayerEntity player) {
        return (player.getX() - startX) * lineUX + (player.getZ() - startZ) * lineUZ;
    }

    /** Ecart lateral signe par rapport a la ligne, en blocs. */
    private static double crossTrack(ClientPlayerEntity player) {
        return (player.getX() - startX) * lineUZ - (player.getZ() - startZ) * lineUX;
    }

    /** Cap vers le point de la ligne situe LOOKAHEAD blocs devant soi. */
    public static float currentBearing(ClientPlayerEntity player) {
        double aim = alongTrack(player) + LOOKAHEAD;
        double aimX = startX + lineUX * aim;
        double aimZ = startZ + lineUZ * aim;
        double dx = aimX - player.getX();
        double dz = aimZ - player.getZ();
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

    /** Etat physique de la touche d'avance, independant de KeyBinding.isPressed (neutralise). */
    private static boolean isForwardPhysicallyHeld(MinecraftClient client) {
        InputUtil.Key key = KeyBindingHelper.getBoundKeyOf(client.options.forwardKey);
        if (key == null || key.getCategory() != InputUtil.Type.KEYSYM) {
            return false;
        }
        return InputUtil.isKeyPressed(client.getWindow().getHandle(), key.getCode());
    }

    private static void activate(ClientPlayerEntity player) {
        setupLine(player);
        locked = true;
        arrivalBrake = false;
        resetController();
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
                                arrivalBrake = false;
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
                                        activate(ctx.getSource().getPlayer());
                                        ctx.getSource().sendFeedback(
                                                Text.literal(String.format(
                                                        "[TropiLock] Cap verrouille sur %.0f / %.0f, %.0f blocs (%s pour liberer).",
                                                        targetX, targetZ, lineLength, toggleKeyName()))
                                                        .formatted(Formatting.GREEN));
                                        return 1;
                                    }))));
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Frein d'arrivee : leve des que le joueur relache l'avance.
            if (arrivalBrake) {
                brakeTicks++;
                if (!isForwardPhysicallyHeld(client) || brakeTicks > BRAKE_MAX_TICKS) {
                    arrivalBrake = false;
                }
            }

            while (toggleKey.wasPressed()) {
                if (!hasTarget) {
                    if (client.player != null) {
                        client.player.sendMessage(
                                Text.literal("[TropiLock] Aucune cible : utilise /lock <x> <z>.")
                                        .formatted(Formatting.RED), false);
                    }
                } else if (client.player != null) {
                    if (locked) {
                        locked = false;
                        resetController();
                    } else {
                        // Nouvelle ligne depuis la position actuelle
                        activate(client.player);
                    }
                    client.player.sendMessage(
                            Text.literal(locked
                                    ? "[TropiLock] Verrouillage actif."
                                    : "[TropiLock] Verrouillage desactive.")
                                    .formatted(locked ? Formatting.GREEN : Formatting.YELLOW), false);
                }
            }

            if (!locked || client.player == null) {
                return;
            }

            ClientPlayerEntity player = client.player;
            double remaining = lineLength - alongTrack(player);

            if (remaining <= ARRIVAL_DISTANCE) {
                locked = false;
                resetController();
                arrivalBrake = true;
                brakeTicks = 0;

                double ex = player.getX() - targetX;
                double ez = player.getZ() - targetZ;
                player.sendMessage(
                        Text.literal(String.format(
                                "[TropiLock] Arrive en %.1f / %.1f (ecart %.1f bloc). Relache l'avance pour reprendre la main.",
                                player.getX(), player.getZ(), Math.sqrt(ex * ex + ez * ez)))
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
