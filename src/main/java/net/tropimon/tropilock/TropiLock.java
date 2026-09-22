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

    /*
     * Deux facons de piloter une monture.
     *
     * DIRECT : on ecrit l'orientation de la monture et du joueur a chaque tick,
     * comme a pied. Pas de dynamique inconnue, donc pas d'oscillation.
     * SOURIS : on simule des mouvements de souris (ancienne methode), utilisee
     * en repli si la monture n'accepte pas l'orientation imposee.
     */
    /*
     * FIXE (defaut) : le cap est simplement fige, souris horizontale bloquee,
     * aucun pilotage. C'est Cobblemon qui garde la monture droite, sans que
     * rien ne vienne se battre avec sa propre rotation.
     */
    public enum Mode { FIXE, DIRECT, SOURIS }
    public static Mode mode = Mode.FIXE;

    /*
     * Alignement automatique, a l'arret.
     *
     * Le pilotage continu vacillait parce que la monture reagit avec retard.
     * Ici on procede par impulsions : un seul mouvement de souris simule, puis
     * on attend que la monture ait fini de tourner avant de mesurer et de
     * corriger le reste. Jamais d'action pendant qu'elle bouge encore, donc
     * rien qui puisse s'emballer. Une fois dans l'axe, le cap est fige.
     */
    private static boolean aligning = false;
    private static double pendingPulse = 0.0;
    private static double lastPulse = 0.0;
    private static boolean pulseOutstanding = false;
    private static float yawBeforePulse = 0.0F;
    private static float lastAlignYaw = 0.0F;
    private static int stableTicks = 0;
    private static int alignTicks = 0;
    private static double alignGain = 0.0;
    private static double pulseBoost = 1.0;

    /** Ticks sans mouvement pour considerer la monture immobile. */
    private static final int SETTLE_TICKS = 4;
    /** Rotation par tick en dessous de laquelle la monture est jugee immobile. */
    private static final float SETTLE_EPS = 0.005F;
    /** Abandon au bout de 15 s si l'alignement n'aboutit pas. */
    private static final int ALIGN_TIMEOUT = 300;
    /** Ecart prevu a l'arrivee juge suffisant, en blocs. */
    private static final double ALIGN_MISS = 0.1;

    /**
     * Avance automatique : tant que le cap est fige, la touche d'avance est
     * consideree comme enfoncee. Coupee a l'arrivee, au deverrouillage, et
     * jamais pendant l'alignement (la monture doit rester immobile).
     */
    public static boolean autoForward = true;

    /**
     * Lock libre : cap fige dans la direction actuelle, sans cible ni ligne ni
     * arrivee. C'est ce que fait la touche quand aucun trajet n'est en cours.
     */
    private static boolean freeLock = false;

    public static boolean isAutoAdvancing() {
        if (!autoForward || !locked || aligning) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        return client != null && client.player != null;
    }

    /** Guidage affiche dans la barre d'action tant qu'une cible est active. */
    private static boolean guiding = false;
    private static int hudTicks = 0;

    /** Rotation maximale par tick en mode direct (4 deg/tick = 80 deg/s). */
    private static final float DIRECT_MAX_STEP = 4.0F;
    /** Fraction de l'ecart comblee par tick en mode direct. */
    private static final float DIRECT_APPROACH = 0.35F;

    // Verification que la monture garde le cap impose (sinon repli sur SOURIS).
    private static float lastSetYaw = 0.0F;
    private static boolean hasSetYaw = false;
    private static int checkTicks = 0;
    private static int overrideTicks = 0;
    private static final int CHECK_WINDOW = 40;
    private static final int OVERRIDE_LIMIT = 15;
    private static final float OVERRIDE_TOLERANCE = 3.0F;

    /** Fraction de l'ecart comblee par frame en mode souris. Reduite pour limiter l'oscillation. */
    private static final float APPROACH = 0.25F;

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

        double theory = theoreticalConversion(client);
        if (!conversionReady) {
            conversion = theory;
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
            // Borne : une monture qui continue de tourner apres le mouvement
            // gonflait la mesure, et les corrections finissaient quasi nulles.
            conversion = MathHelper.clamp(conversion, theory * 0.5, theory * 2.0);
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
        freeLock = false;
        locked = true;
        arrivalBrake = false;
        resetController();
        hasSetYaw = false;
        checkTicks = 0;
        overrideTicks = 0;
    }

    /**
     * Direction reellement suivie : celle de la vitesse quand on avance, sinon
     * l'orientation de la monture. C'est elle qui determine ou l'on arrive.
     */
    private static float travelHeading(ClientPlayerEntity player) {
        Entity v = player.getRootVehicle();
        net.minecraft.util.math.Vec3d vel = v.getVelocity();
        double h = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        if (h > 0.08) {
            return MathHelper.wrapDegrees((float) (MathHelper.atan2(vel.z, vel.x) * 57.2957795) - 90.0F);
        }
        return MathHelper.wrapDegrees(v.getYaw());
    }

    /** Cap exact vers la cible (pas vers un point de la ligne). */
    private static float bearingToTarget(ClientPlayerEntity player) {
        double dx = targetX - player.getX();
        double dz = targetZ - player.getZ();
        return MathHelper.wrapDegrees((float) (MathHelper.atan2(dz, dx) * 57.2957795) - 90.0F);
    }

    /**
     * Barre d'action : decalage angulaire vers la cible et ecart prevu a
     * l'arrivee si le cap actuel est conserve. Sert a s'aligner avant de
     * verrouiller, puis a verifier qu'on reste bon pendant le vol.
     */
    private static void showGuidance(ClientPlayerEntity player) {
        double dx = targetX - player.getX();
        double dz = targetZ - player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        float err = MathHelper.wrapDegrees(bearingToTarget(player) - travelHeading(player));
        double miss = Math.abs(dist * Math.sin(Math.toRadians(err)));
        String side = err > 0 ? "a droite" : "a gauche";

        Formatting color = miss < 0.5 ? Formatting.GREEN : (miss < 3.0 ? Formatting.YELLOW : Formatting.RED);
        String prefix = aligning ? "[Alignement] " : (locked ? "[Lock] " : "[Visee] ");

        String msg = miss < 0.05
                ? String.format("%sPile dans l'axe | reste %.0f blocs", prefix, dist)
                : String.format("%sCible %.3f° %s | ecart prevu %.2f blocs | reste %.0f blocs",
                        prefix, Math.abs(err), side, miss, dist);

        player.sendMessage(Text.literal(msg).formatted(color), true);
    }

    public static boolean isAligning() {
        if (!aligning) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        return client != null && client.player != null && client.currentScreen == null;
    }

    /** Lu une fois par le MouseMixin : l'impulsion n'est injectee qu'une seule fois. */
    public static double consumeAlignPulse() {
        double p = pendingPulse;
        pendingPulse = 0.0;
        return p;
    }

    private static void startFree(ClientPlayerEntity player) {
        stopAlign();
        resetController();
        arrivalBrake = false;
        freeLock = true;
        locked = true;
        player.sendMessage(Text.literal(String.format(autoForward
                        ? "[TropiLock] Cap fige, avance tout droit (%s pour arreter)."
                        : "[TropiLock] Cap fige (%s pour liberer).", toggleKeyName()))
                .formatted(Formatting.GREEN), true);
    }

    /** Lance l'alignement ; a pied, la rotation est simplement ecrite. */
    private static void startAlign(ClientPlayerEntity player) {
        locked = false;
        resetController();
        if (!isMounted()) {
            applyYaw(player, bearingToTarget(player));
            activate(player);
            player.sendMessage(Text.literal("[TropiLock] Aligne, cap fige.")
                    .formatted(Formatting.GREEN), false);
            return;
        }
        aligning = true;
        pendingPulse = 0.0;
        lastPulse = 0.0;
        pulseOutstanding = false;
        stableTicks = 0;
        alignTicks = 0;
        alignGain = theoreticalConversion(MinecraftClient.getInstance());
        pulseBoost = 1.0;
        lastAlignYaw = player.getRootVehicle().getYaw();
    }

    private static void stopAlign() {
        aligning = false;
        pendingPulse = 0.0;
        pulseOutstanding = false;
    }

    private static void tickAlign(ClientPlayerEntity player) {
        Entity vehicle = player.getRootVehicle();
        float yaw = vehicle.getYaw();

        if (Math.abs(MathHelper.wrapDegrees(yaw - lastAlignYaw)) < SETTLE_EPS) {
            stableTicks++;
        } else {
            stableTicks = 0;
        }
        lastAlignYaw = yaw;

        if (++alignTicks > ALIGN_TIMEOUT) {
            stopAlign();
            activate(player);
            player.sendMessage(Text.literal(
                    "[TropiLock] Alignement incomplet, cap fige tel quel : ajuste a la main si besoin.")
                    .formatted(Formatting.GOLD), false);
            return;
        }

        // On attend que la monture ait fini de tourner avant toute mesure
        if (stableTicks < SETTLE_TICKS || pendingPulse != 0.0) {
            return;
        }

        // Mesure de l'effet reel de la derniere impulsion
        if (pulseOutstanding) {
            pulseOutstanding = false;
            double achieved = MathHelper.wrapDegrees(yaw - yawBeforePulse);
            if (Math.abs(achieved) < 0.0005) {
                // Impulsion trop faible pour faire bouger la monture : on insiste
                pulseBoost = Math.min(pulseBoost * 2.0, 16.0);
            } else {
                pulseBoost = 1.0;
                double g = achieved / lastPulse;
                double theory = theoreticalConversion(MinecraftClient.getInstance());
                if (Math.abs(achieved) > 0.005 && g > theory * 0.1 && g < theory * 10.0) {
                    alignGain = g;
                }
            }
        }

        double dx = targetX - player.getX();
        double dz = targetZ - player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        float err = MathHelper.wrapDegrees(bearingToTarget(player) - yaw);
        double miss = Math.abs(dist * Math.sin(Math.toRadians(err)));

        if (miss < ALIGN_MISS || Math.abs(err) < 0.0002F) {
            stopAlign();
            activate(player);
            player.sendMessage(Text.literal(String.format(
                    "[TropiLock] Aligne (ecart prevu %.2f bloc), cap fige.", miss))
                    .formatted(Formatting.GREEN), false);
            return;
        }

        if (alignGain <= 1.0E-6) {
            alignGain = theoreticalConversion(MinecraftClient.getInstance());
        }

        // 80 % de l'ecart par impulsion : on approche sans depasser
        double factor = miss < 1.0 ? 1.0 : 0.8;
        double pulse = (err * factor / alignGain) * pulseBoost;
        yawBeforePulse = yaw;
        lastPulse = pulse;
        pendingPulse = pulse;
        pulseOutstanding = true;
        stableTicks = 0;
    }

    /** Vrai si le pilotage passe par la simulation de souris. */
    public static boolean usesMouseSteering() {
        return mode == Mode.SOURIS && !freeLock;
    }

    /**
     * Mode direct, appele en debut de tick (avant le deplacement) : fait
     * pivoter progressivement monture et joueur vers le cap de la ligne.
     */
    private static void steerDirect(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        Entity vehicle = player.getRootVehicle();

        float current = vehicle.getYaw();

        // Controle : entre deux ticks, personne d'autre ne doit avoir tourne la monture
        if (hasSetYaw && checkTicks < CHECK_WINDOW) {
            checkTicks++;
            if (Math.abs(MathHelper.wrapDegrees(current - lastSetYaw)) > OVERRIDE_TOLERANCE) {
                overrideTicks++;
            }
            if (overrideTicks > OVERRIDE_LIMIT) {
                mode = Mode.SOURIS;
                resetController();
                player.sendMessage(Text.literal(
                        "[TropiLock] La monture refuse le cap impose : passage en mode souris.")
                        .formatted(Formatting.GOLD), false);
                return;
            }
        }

        float error = MathHelper.wrapDegrees(currentBearing(player) - current);
        float step = MathHelper.clamp(error * DIRECT_APPROACH, -DIRECT_MAX_STEP, DIRECT_MAX_STEP);
        float yaw = current + step;

        applyYawSmooth(vehicle, yaw);
        applyYawSmooth(player, yaw);
        lastSetYaw = yaw;
        hasSetYaw = true;
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
                                freeLock = false;
                                arrivalBrake = false;
                                guiding = false;
                                stopAlign();
                                resetController();
                                ctx.getSource().sendFeedback(
                                        Text.literal("[TropiLock] Verrouillage desactive.")
                                                .formatted(Formatting.YELLOW));
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("avance").executes(ctx -> {
                                autoForward = !autoForward;
                                ctx.getSource().sendFeedback(Text.literal(autoForward
                                        ? "[TropiLock] Avance automatique activee."
                                        : "[TropiLock] Avance automatique desactivee.")
                                        .formatted(autoForward ? Formatting.GREEN : Formatting.YELLOW));
                                return 1;
                            }))
                    .then(ClientCommandManager.literal("mode")
                            .then(ClientCommandManager.literal("fixe").executes(ctx -> {
                                mode = Mode.FIXE;
                                resetController();
                                ctx.getSource().sendFeedback(Text.literal(
                                        "[TropiLock] Mode fixe : cap fige, aucun pilotage.")
                                        .formatted(Formatting.GREEN));
                                return 1;
                            }))
                            .then(ClientCommandManager.literal("direct").executes(ctx -> {
                                mode = Mode.DIRECT;
                                resetController();
                                hasSetYaw = false; checkTicks = 0; overrideTicks = 0;
                                ctx.getSource().sendFeedback(Text.literal(
                                        "[TropiLock] Mode direct : orientation imposee a la monture.")
                                        .formatted(Formatting.GREEN));
                                return 1;
                            }))
                            .then(ClientCommandManager.literal("souris").executes(ctx -> {
                                mode = Mode.SOURIS;
                                resetController();
                                ctx.getSource().sendFeedback(Text.literal(
                                        "[TropiLock] Mode souris : pilotage par mouvements simules.")
                                        .formatted(Formatting.GREEN));
                                return 1;
                            })))
                    .then(ClientCommandManager.argument("x", DoubleArgumentType.doubleArg())
                            .then(ClientCommandManager.argument("z", DoubleArgumentType.doubleArg())
                                    .executes(ctx -> {
                                        targetX = DoubleArgumentType.getDouble(ctx, "x");
                                        targetZ = DoubleArgumentType.getDouble(ctx, "z");
                                        hasTarget = true;
                                        guiding = true;
                                        ClientPlayerEntity p = ctx.getSource().getPlayer();
                                        double ddx = targetX - p.getX();
                                        double ddz = targetZ - p.getZ();
                                        ctx.getSource().sendFeedback(
                                                Text.literal(String.format(
                                                        "[TropiLock] Cible %.0f / %.0f, %.0f blocs : alignement... (%s pour annuler).",
                                                        targetX, targetZ, Math.sqrt(ddx * ddx + ddz * ddz), toggleKeyName()))
                                                        .formatted(Formatting.GREEN));
                                        startAlign(p);
                                        return 1;
                                    }))));
        });

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (!locked || client.player == null || client.currentScreen != null) {
                return;
            }
            if (mode == Mode.DIRECT && isMounted() && !freeLock) {
                steerDirect(client);
            }
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
                if (client.player == null) {
                    continue;
                }
                if (aligning) {
                    // Annulation de l'alignement en cours
                    stopAlign();
                    // Desactivation silencieuse : on vide la barre d'action
                    client.player.sendMessage(Text.empty(), true);
                } else if (locked) {
                    // Arret : plus de cap fige, plus d'avance
                    locked = false;
                    freeLock = false;
                    resetController();
                    // Desactivation silencieuse : on vide la barre d'action
                    client.player.sendMessage(Text.empty(), true);
                } else if (guiding && hasTarget) {
                    // Trajet vers une cible en cours : on reprend, alignement compris
                    startAlign(client.player);
                } else {
                    // Aucun trajet : tout droit dans la direction actuelle
                    startFree(client.player);
                }
            }

            if (aligning && client.player != null) {
                if (client.currentScreen == null) {
                    tickAlign(client.player);
                }
            }

            // Indicateur affiche seulement quand le lock travaille : en pause, il disparait
            if (guiding && client.player != null && !arrivalBrake && (locked || aligning)) {
                if (++hudTicks >= 2) {
                    hudTicks = 0;
                    showGuidance(client.player);
                }
            }

            if (!locked || client.player == null) {
                return;
            }

            if (freeLock) {
                return;
            }

            ClientPlayerEntity player = client.player;
            double remaining = lineLength - alongTrack(player);

            if (remaining <= ARRIVAL_DISTANCE) {
                locked = false;
                resetController();
                arrivalBrake = true;
                brakeTicks = 0;
                guiding = false;

                double ex = player.getX() - targetX;
                double ez = player.getZ() - targetZ;
                player.sendMessage(
                        Text.literal(String.format(autoForward
                                        ? "[TropiLock] Arrive en %.1f / %.1f (ecart %.1f bloc)."
                                        : "[TropiLock] Arrive en %.1f / %.1f (ecart %.1f bloc). Relache l'avance pour reprendre la main.",
                                player.getX(), player.getZ(), Math.sqrt(ex * ex + ez * ez)))
                                .formatted(Formatting.GREEN), false);
                return;
            }

            if (!isMounted() && mode != Mode.FIXE) {
                applyYaw(player, currentBearing(player));
            }
        });
    }

    /** Comme applyYaw, mais garde l'ancienne valeur en prev pour une rotation fluide. */
    private static void applyYawSmooth(Entity entity, float yaw) {
        float old = entity.getYaw();
        entity.setYaw(yaw);
        entity.prevYaw = old;
        entity.setHeadYaw(yaw);
        if (entity instanceof LivingEntity living) {
            living.prevHeadYaw = old;
            living.bodyYaw = yaw;
            living.prevBodyYaw = old;
        }
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
