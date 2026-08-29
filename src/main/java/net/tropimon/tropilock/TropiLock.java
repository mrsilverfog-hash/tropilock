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

    /** Point de depart de la droite, capture au moment du verrouillage. */
    private static double originX = 0.0;
    private static double originZ = 0.0;
    private static boolean hasOrigin = false;

    /** Distance a la cible (en blocs) a laquelle le verrouillage se libere tout seul. */
    private static final double RELEASE_RADIUS = 20.0;

    /** Degres d'interception par bloc d'ecart a la droite. */
    private static final float CROSS_GAIN = 2.5F;

    /** Angle d'interception maximal, en degres. */
    private static final float MAX_INTERCEPT = 35.0F;

    /** Mettre a -1.0F si le mod s'eloigne de la ligne au lieu d'y revenir. */
    private static final float CROSS_SIGN = 1.0F;

    /** Gain proportionnel : nervosite du virage. */
    private static final float GAIN_P = 4.0F;

    /** Gain derive : freinage anticipe. Monter si ca depasse le cap. */
    private static final float GAIN_D = 1.2F;

    /** Vitesse de rotation maximale autorisee, en degres par seconde. */
    private static final float MAX_TURN_RATE = 90.0F;

    /** En dessous de cet ecart, on considere qu'on est aligne. */
    private static final float DEADZONE_DEGREES = 0.15F;

    private static KeyBinding toggleKey;
    private static boolean debug = false;

    // Etat du correcteur, remis a zero a chaque activation.
    private static float lastYaw = 0.0F;
    private static long lastTimeNanos = 0L;
    private static boolean hasHistory = false;
    private static float lastCross = 0.0F;

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

    /** Ecart lateral signe a la droite depart-cible, en blocs. */
    private static float crossTrackError(ClientPlayerEntity player) {
        if (!hasOrigin) {
            return 0.0F;
        }

        double lx = targetX - originX;
        double lz = targetZ - originZ;
        double length = Math.sqrt(lx * lx + lz * lz);

        if (length < 0.001) {
            return 0.0F;
        }

        double ux = lx / length;
        double uz = lz / length;
        double px = player.getX() - originX;
        double pz = player.getZ() - originZ;

        return (float) (ux * pz - uz * px);
    }

    /**
     * Cap voulu : direction de la droite, corrigee d'un angle d'interception
     * proportionnel a l'ecart lateral. C'est ce qui fait la difference entre
     * suivre une ligne et poursuivre un point.
     */
    public static float desiredHeading(ClientPlayerEntity player) {
        double dx = targetX - player.getX();
        double dz = targetZ - player.getZ();
        float bearingToTarget =
                MathHelper.wrapDegrees((float) (MathHelper.atan2(dz, dx) * 57.2957795) - 90.0F);

        if (!hasOrigin) {
            return bearingToTarget;
        }

        double lx = targetX - originX;
        double lz = targetZ - originZ;
        double length = Math.sqrt(lx * lx + lz * lz);

        if (length < 0.001) {
            return bearingToTarget;
        }

        // Une fois la cible depassee, on revise directement dessus.
        double ux = lx / length;
        double uz = lz / length;
        double along = (player.getX() - originX) * ux + (player.getZ() - originZ) * uz;
        if (along > length) {
            return bearingToTarget;
        }

        float lineBearing =
                MathHelper.wrapDegrees((float) (MathHelper.atan2(lz, lx) * 57.2957795) - 90.0F);

        float cross = crossTrackError(player);
        lastCross = cross;

        float intercept = MathHelper.clamp(
                CROSS_SIGN * CROSS_GAIN * cross,
                -MAX_INTERCEPT,
                MAX_INTERCEPT);

        return MathHelper.wrapDegrees(lineBearing + intercept);
    }

    private static void resetController() {
        hasHistory = false;
        lastTimeNanos = 0L;
        lastYaw = 0.0F;
        lastCross = 0.0F;
    }

    private static void captureOrigin() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            hasOrigin = false;
            return;
        }
        originX = client.player.getX();
        originZ = client.player.getZ();
        hasOrigin = true;
    }

    /** Correcteur proportionnel-derive sur le cap voulu, independant du framerate. */
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

        float error = MathHelper.wrapDegrees(desiredHeading(player) - yaw);

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
                    .then(ClientCommandManager.literal("debug")
                            .executes(ctx -> {
                                debug = !debug;
                                ctx.getSource().sendFeedback(
                                        Text.literal("[TropiLock] Debug " + (debug ? "actif." : "coupe."))
                                                .formatted(Formatting.GRAY));
                                return 1;
                            }))
                    .then(ClientCommandManager.argument("x", DoubleArgumentType.doubleArg())
                            .then(ClientCommandManager.argument("z", DoubleArgumentType.doubleArg())
                                    .executes(ctx -> {
                                        targetX = DoubleArgumentType.getDouble(ctx, "x");
                                        targetZ = DoubleArgumentType.getDouble(ctx, "z");
                                        hasTarget = true;
                                        locked = true;
                                        captureOrigin();
                                        resetController();
                                        ctx.getSource().sendFeedback(
                                                Text.literal(String.format(
                                                        "[TropiLock] Rail trace vers %.0f / %.0f (%s pour liberer).",
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
                    if (locked) {
                        captureOrigin();
                    }
                    if (client.player != null) {
                        client.player.sendMessage(
                                Text.literal(locked
                                        ? "[TropiLock] Rail retrace depuis ta position."
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
                applyYaw(player, desiredHeading(player));
            }

            if (debug) {
                player.sendMessage(
                        Text.literal(String.format(
                                "TropiLock >> %.0f blocs -- ecart lateral %.1f",
                                distance, lastCross))
                                .formatted(Formatting.AQUA), true);
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
