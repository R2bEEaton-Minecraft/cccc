package cc.spea.cccc.compat;

import com.github.eterdelta.crittersandcompanions.entity.GrapplingHookEntity;
import com.github.eterdelta.crittersandcompanions.platform.Services;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sable-only fallback pull system.
 *
 * A real grappling hook entity can freeze Sable sub-levels even with RETAIN and
 * noPhysics, so Sable attachments discard the physical hook and keep only a
 * local-space anchor.  This tick handler mirrors C&C's pull force against that
 * moving anchor without giving Sable an entity to collide with.
 */
public final class SableGrappleHandler {

    private static final Map<UUID, Attachment> ACTIVE = new ConcurrentHashMap<>();

    private SableGrappleHandler() {}

    public static void attach(GrapplingHookEntity hook, Player owner, Object subLevel, Vec3 localAttach) {
        ACTIVE.put(owner.getUUID(), new Attachment(
            subLevel,
            localAttach,
            owner.distanceToSqr(hook),
            hook.getItem().copy()
        ));
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
        double maxDistance = Services.CONFIGS.common().grapplingHookMaxDistance.get();
        if (distanceSqr > maxDistance * maxDistance) {
            ACTIVE.remove(player.getUUID());
            return;
        }

        if (distanceSqr > attachment.stickLengthSqr) {
            Vec3 direction = target.subtract(player.position()).normalize();
            double maxSpeed = Services.CONFIGS.common().grapplingHookMaxSpeed.get();
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
}
