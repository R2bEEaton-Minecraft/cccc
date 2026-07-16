package cc.spea.cccc.compat;

import cc.spea.cccc.CCCC;
import io.github.bonsaistudi0s.crittersandcompanions.common.entity.GrapplingHookEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sable-only fallback pull system.
 *
 * A real grappling hook entity can freeze Sable sub-levels if it keeps running
 * normal projectile collision, so Sable attachments keep the hook only as a
 * pinned visual marker.  This tick handler mirrors C&C's pull force against the
 * moving local-space anchor.
 */
public final class SableGrappleHandler {

    private static final Map<UUID, Attachment> ACTIVE = new ConcurrentHashMap<>();
    private static final GrapplingConfig CONFIG = new GrapplingConfig();

    private SableGrappleHandler() {}

    public static void attach(GrapplingHookEntity hook, Player owner, Object subLevel, Vec3 localAttach) {
        ACTIVE.put(owner.getUUID(), new Attachment(
            subLevel,
            localAttach,
            owner.distanceToSqr(hook),
            hook.getItem().copy()
        ));
    }

    public static boolean isAttached(Player owner) {
        return ACTIVE.containsKey(owner.getUUID());
    }

    public static void detach(Player owner) {
        ACTIVE.remove(owner.getUUID());
    }

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide) return;

        Attachment attachment = ACTIVE.get(player.getUUID());
        if (attachment == null) return;

        if (!player.isAlive()
            || SableCompat.isRemoved(attachment.subLevel)
            || !isFocused(player, attachment.focusStack)) {
            ACTIVE.remove(player.getUUID());
            return;
        }

        Vec3 target = SableCompat.toWorldPos(attachment.subLevel, attachment.localAttach);
        if (target == null) {
            ACTIVE.remove(player.getUUID());
            return;
        }

        double distanceSqr = player.position().distanceToSqr(target);
        double maxDistance = CONFIG.maxDistance();
        if (distanceSqr > maxDistance * maxDistance) {
            ACTIVE.remove(player.getUUID());
            return;
        }

        if (distanceSqr > attachment.stickLengthSqr) {
            Vec3 direction = target.subtract(player.position()).normalize();
            double maxSpeed = CONFIG.maxSpeed();
            double speed = Math.min(maxSpeed, 0.01 * Math.sqrt(distanceSqr));
            if (speed >= 0.0) {
                player.setDeltaMovement(player.getDeltaMovement().add(direction.scale(speed)));
                player.hurtMarked = true;
            }
        }
    }

    private static boolean isFocused(Player player, ItemStack focusStack) {
        return ItemStack.isSameItemSameComponents(player.getMainHandItem(), focusStack)
            || ItemStack.isSameItemSameComponents(player.getOffhandItem(), focusStack);
    }

    private record Attachment(
        Object subLevel,
        Vec3 localAttach,
        double stickLengthSqr,
        ItemStack focusStack
    ) {}

    private static final class GrapplingConfig {
        private static final double DEFAULT_MAX_DISTANCE = 16.0D;
        private static final double DEFAULT_MAX_SPEED = 1.5D;
        private static final String CONFIG_CLASS = "io.github.bonsaistudi0s.crittersandcompanions.common.config.CACCommonConfig";

        private boolean initialized;
        private boolean available;
        private Object handler;
        private Method instanceMethod;
        private Field grapplingHookField;
        private Field maxDistanceField;
        private Field maxSpeedField;

        double maxDistance() {
            return readDouble(maxDistanceField, DEFAULT_MAX_DISTANCE);
        }

        double maxSpeed() {
            return readDouble(maxSpeedField, DEFAULT_MAX_SPEED);
        }

        private double readDouble(Field field, double fallback) {
            ensureInit();
            if (!available || field == null) return fallback;

            try {
                Object config = instanceMethod.invoke(handler);
                Object grapplingHook = grapplingHookField.get(config);
                return field.getDouble(grapplingHook);
            } catch (Exception e) {
                CCCC.LOGGER.debug("[cccc] Failed to read C&C grappling config: {}", e.toString());
                available = false;
                return fallback;
            }
        }

        private void ensureInit() {
            if (initialized) return;
            initialized = true;

            try {
                Class<?> configClass = Class.forName(CONFIG_CLASS);
                Field handlerField = configClass.getField("HANDLER");
                handler = handlerField.get(null);
                instanceMethod = handler.getClass().getMethod("instance");
                grapplingHookField = configClass.getField("grapplingHook");

                Class<?> grapplingHookClass = grapplingHookField.getType();
                maxDistanceField = grapplingHookClass.getField("grapplingHookMaxDistance");
                maxSpeedField = grapplingHookClass.getField("grapplingHookMaxSpeed");
                available = true;
            } catch (Exception e) {
                CCCC.LOGGER.debug("[cccc] Failed to initialize C&C grappling config reflection: {}", e.toString());
            }
        }
    }
}
