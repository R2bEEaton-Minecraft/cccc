package cc.spea.cccc.mixin;

import cc.spea.cccc.CCCC;
import cc.spea.cccc.compat.CreateHookRenderAttachment;
import cc.spea.cccc.compat.SableCompat;
import cc.spea.cccc.compat.SableGrappleHandler;
import com.github.eterdelta.crittersandcompanions.entity.GrapplingHookEntity;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Core mixin for the CCCC compatibility mod.
 *
 * Handles two distinct moving-block systems:
 *   • Create contraptions  (pin the real hook entity to contraption-local space)
 *   • Sable sub-levels     (discard the real hook before Sable can collide with
 *                           it, then pull via SableGrappleHandler)
 *
 * ── Why the hook bounces ─────────────────────────────────────────────────────
 * GrapplingHookEntity.tick() (bytecode ~103) calls
 *   Level.getBlockCollisions(this, inflatedAABB)
 * and sets  isStick = (anyShapeIntersects)  at bytecode ~202 every tick.
 * Neither Create contraption blocks nor Sable sub-level blocks exist in the
 * real world → getBlockCollisions returns nothing → isStick = false always.
 *
 * ── The fix ───────────────────────────────────────────────────────────────────
 * @Redirect getBlockCollisions:
 *   • Detect first entry into a contraption/sub-level block space.
 *   • While attached, return a fake non-empty VoxelShape offset to the hook's
 *     current world position → C&C's intersection test passes naturally
 *     → isStick = true → pull logic runs without touching C&C internals.
 *
 * @Inject tick HEAD:
 *   • For Sable, predict a sub-level hit before projectile movement, register a
 *     local-space anchor, discard the physical hook, and cancel the hook tick.
 *   • For Create, push the hook to the contraption's current world transform
 *     every tick so it follows bearings, pistons, etc.
 *
 * Sable integration is done entirely through SableCompat (reflection) so that
 * no Sable imports appear in this file — avoiding compile failures caused by
 * the companion JarInJar.
 *
 * @author R2bEEaton
 */
@Mixin(GrapplingHookEntity.class)
public abstract class GrapplingHookEntityMixin extends ThrowableItemProjectile implements CreateHookRenderAttachment {

    // ── Shadowed C&C field ───────────────────────────────────────────────────
    // Verified: javap -classpath critters-and-companions-*.jar -p
    //   com.github.eterdelta.crittersandcompanions.entity.GrapplingHookEntity
    @Shadow(remap = false) protected boolean isStick;

    // ── Create contraption attachment ─────────────────────────────────────────
    @Unique @Nullable private AbstractContraptionEntity cccc_contraption;
    @Unique @Nullable private Vec3                      cccc_localAttach;

    protected GrapplingHookEntityMixin(EntityType<? extends ThrowableItemProjectile> type, Level level) {
        super(type, level);
    }

    // ── 1a. Tick HEAD — pin position BEFORE super.tick() runs ────────────────
    // super.tick() (ThrowableItemProjectile) adds gravity (-0.03/tick) and air
    // drag to deltaMovement then calls Entity.move().  Pinning position + velocity
    // here and again at TAIL (1b) sandwiches super.tick() so the hook never drifts
    // from its attach point, eliminating the jerk-every-tick artefact.
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void cccc_tickHead(CallbackInfo ci) {
        if (cccc_tryStartSableFallback()) {
            ci.cancel();
            return;
        }
        cccc_pinToAttach();
    }

    // ── 1b. Tick TAIL — re-pin position AFTER super.tick() has run ───────────
    // super.tick() can still move the entity (gravity, drag) between HEAD and
    // TAIL.  Correcting here ensures the FINAL position sent to clients is exact.
    @Inject(method = "tick", at = @At("TAIL"))
    private void cccc_tickTail(CallbackInfo ci) {
        cccc_pinToAttach();
    }

    // ── 2. Redirect getBlockCollisions — the key fix ──────────────────────────
    @Redirect(
        method = "tick",
        at = @At(
            value  = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockCollisions(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/lang/Iterable;"
        )
    )
    private Iterable<VoxelShape> cccc_redirectBlockCollisions(Level level, Entity entity, AABB hookAABB) {
        Iterable<VoxelShape> real = level.getBlockCollisions(entity, hookAABB);

        // ── Already attached — handle stale refs and fake solid ───────────────
        if (cccc_contraption != null) {
            if (!cccc_contraption.isAlive()) { cccc_clearContraptionAttach(); return real; }
            return cccc_fakeSolid();
        }

        Vec3 hookPos = this.position();
        Vec3 previousHookPos = new Vec3(this.xo, this.yo, this.zo);
        AABB sweptHookAABB = hookAABB.minmax(hookAABB.move(previousHookPos.subtract(hookPos)));

        // ── Detect first entry: Create contraption ────────────────────────────
        // hookAABB already has 0.25 inflate from C&C.  Sweep it back to the
        // previous tick position so fast hooks don't choose the wrong face after
        // their center has already passed into/through a contraption block.
        for (AbstractContraptionEntity c : level.getEntitiesOfClass(
                AbstractContraptionEntity.class, sweptHookAABB)) {
            Vec3 localAttach = cccc_findCreateAttach(c, previousHookPos, hookPos, sweptHookAABB);
            if (localAttach != null) {
                cccc_contraption = c;
                cccc_localAttach  = localAttach;
                cccc_pinToAttach();
                CCCC.LOGGER.debug("[cccc] Hook #{} → Create contraption #{}", getId(), c.getId());
                return cccc_fakeSolid();
            }
        }

        // ── Detect first entry: Sable sub-level ───────────────────────────────
        SableCompat.Hit hit = level.isClientSide ? null : SableCompat.findHitAt(level, hookPos, hookAABB);
        if (hit != null) {
            cccc_startSableFallback(hit);
            return real;
        }

        return real;
    }

    // ── 3. Entity-hit fallback ───────────────────────────────────────────────
    @Inject(method = "onHitEntity", at = @At("HEAD"), cancellable = true, require = 0)
    private void cccc_onHitEntity(EntityHitResult result, CallbackInfo ci) {
        if (this.level().isClientSide) return;
        if (!(result.getEntity() instanceof AbstractContraptionEntity c)) return;

        Vec3 hitVec = result.getLocation();
        Vec3 previousHookPos = new Vec3(this.xo, this.yo, this.zo);
        AABB hookAABB = this.getBoundingBox().inflate(0.25);
        AABB sweptHookAABB = hookAABB.minmax(hookAABB.move(previousHookPos.subtract(this.position())));
        Vec3 local = cccc_findCreateAttach(c, previousHookPos, hitVec, sweptHookAABB);
        if (local == null) local = c.toLocalVector(hitVec, 0f);
        cccc_contraption = c;
        cccc_localAttach  = local;
        this.isStick      = true;
        cccc_pinToAttach();
        ci.cancel();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Called at both tick HEAD and TAIL to sandwich super.tick().
     *
     * For Create contraptions we convert the stored local attach point back to
     * world space, then zero velocity + disable gravity so super.tick() cannot
     * drift the hook.  Sable uses SableGrappleHandler instead of this real-entity
     * pinning path.
     */
    @Unique
    private void cccc_pinToAttach() {
        // ── Create contraption ────────────────────────────────────────────────
        if (cccc_contraption != null) {
            if (!cccc_contraption.isAlive()) {
                cccc_clearContraptionAttach();
            } else {
                Vec3 previous = cccc_contraption.toGlobalVector(cccc_localAttach, 0f, true);
                Vec3 world = cccc_contraption.toGlobalVector(cccc_localAttach, 1f);
                this.xo = previous.x;
                this.yo = previous.y;
                this.zo = previous.z;
                this.xOld = previous.x;
                this.yOld = previous.y;
                this.zOld = previous.z;
                this.setPos(world.x, world.y, world.z);
                this.setDeltaMovement(Vec3.ZERO);
                this.setNoGravity(true);
            }

        }
    }

    @Override
    @Nullable
    public Vec3 cccc$getCreateHookRenderPosition(float partialTicks) {
        if (cccc_contraption == null || cccc_localAttach == null || !cccc_contraption.isAlive()) return null;

        Vec3 previousAnchor = cccc_contraption.getPrevAnchorVec();
        Vec3 currentAnchor = cccc_contraption.getAnchorVec();
        Vec3 anchor = new Vec3(
            Mth.lerp(partialTicks, previousAnchor.x, currentAnchor.x),
            Mth.lerp(partialTicks, previousAnchor.y, currentAnchor.y),
            Mth.lerp(partialTicks, previousAnchor.z, currentAnchor.z)
        );

        Vec3 rotationOffset = Vec3.atCenterOf(BlockPos.ZERO);
        return cccc_contraption
            .applyRotation(cccc_localAttach.subtract(rotationOffset), partialTicks)
            .add(rotationOffset)
            .add(anchor);
    }

    @Unique
    private boolean cccc_tryStartSableFallback() {
        if (this.level().isClientSide || cccc_contraption != null) return false;
        if (!(this.getOwner() instanceof Player)) return false;

        AABB swept = this.getBoundingBox()
            .minmax(this.getBoundingBox().move(this.getDeltaMovement()))
            .inflate(0.25);
        Vec3 predictedHookPos = this.position().add(this.getDeltaMovement());
        SableCompat.Hit hit = SableCompat.findHitAt(this.level(), predictedHookPos, swept);
        return hit != null && cccc_startSableFallback(hit);
    }

    @Unique
    private boolean cccc_startSableFallback(SableCompat.Hit hit) {
        if (!(this.getOwner() instanceof Player player)) return false;

        SableGrappleHandler.attach((GrapplingHookEntity) (Object) this, player, hit.subLevel(), hit.localAttach());
        this.setDeltaMovement(Vec3.ZERO);
        this.isStick = false;
        this.discard();
        CCCC.LOGGER.debug("[cccc] Hook #{} → Sable sub-level fallback pull (surface local {})", getId(), hit.localAttach());
        return true;
    }

    @Unique
    private void cccc_clearContraptionAttach() {
        cccc_contraption = null;
        cccc_localAttach = null;
        this.setNoGravity(false);
    }

    @Unique
    private Iterable<VoxelShape> cccc_fakeSolid() {
        Vec3 p = this.position();
        return List.of(Shapes.block().move(p.x - 0.5, p.y - 0.5, p.z - 0.5));
    }

    @Unique
    @Nullable
    private static Vec3 cccc_findCreateAttach(AbstractContraptionEntity c, Vec3 previousHookPos, Vec3 hookPos, AABB hookAABB) {
        Vec3 localPreviousHook = c.toLocalVector(previousHookPos, 0f);
        Vec3 localHook = c.toLocalVector(hookPos, 0f);
        AABB localAabb = cccc_toCreateLocalAabb(c, hookAABB);

        final double EPS = 1.0E-6;
        int minX = (int) Math.floor(localAabb.minX - EPS);
        int minY = (int) Math.floor(localAabb.minY - EPS);
        int minZ = (int) Math.floor(localAabb.minZ - EPS);
        int maxX = (int) Math.floor(localAabb.maxX + EPS);
        int maxY = (int) Math.floor(localAabb.maxY + EPS);
        int maxZ = (int) Math.floor(localAabb.maxZ + EPS);

        var blocks = c.getContraption().getBlocks();
        Vec3 bestAttach = null;
        double bestHitTime = Double.MAX_VALUE;
        double bestDistanceSqr = Double.MAX_VALUE;

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos block = new BlockPos(x, y, z);
                    if (!blocks.containsKey(block)) continue;

                    double hitTime = cccc_sweptHitTime(localPreviousHook, localHook, block);
                    Vec3 attach = cccc_offsetPointOnBlockSurface(localHook, block, localPreviousHook);
                    double distanceSqr = attach.distanceToSqr(localHook);
                    if (hitTime < bestHitTime || (hitTime == bestHitTime && distanceSqr < bestDistanceSqr)) {
                        bestHitTime = hitTime;
                        bestDistanceSqr = distanceSqr;
                        bestAttach = attach;
                    }
                }
            }
        }

        return bestAttach;
    }

    @Unique
    private static AABB cccc_toCreateLocalAabb(AbstractContraptionEntity c, AABB worldAabb) {
        Vec3[] corners = {
            new Vec3(worldAabb.minX, worldAabb.minY, worldAabb.minZ),
            new Vec3(worldAabb.minX, worldAabb.minY, worldAabb.maxZ),
            new Vec3(worldAabb.minX, worldAabb.maxY, worldAabb.minZ),
            new Vec3(worldAabb.minX, worldAabb.maxY, worldAabb.maxZ),
            new Vec3(worldAabb.maxX, worldAabb.minY, worldAabb.minZ),
            new Vec3(worldAabb.maxX, worldAabb.minY, worldAabb.maxZ),
            new Vec3(worldAabb.maxX, worldAabb.maxY, worldAabb.minZ),
            new Vec3(worldAabb.maxX, worldAabb.maxY, worldAabb.maxZ)
        };

        Vec3 first = c.toLocalVector(corners[0], 0f);
        double minX = first.x, minY = first.y, minZ = first.z;
        double maxX = first.x, maxY = first.y, maxZ = first.z;
        for (int i = 1; i < corners.length; i++) {
            Vec3 local = c.toLocalVector(corners[i], 0f);
            minX = Math.min(minX, local.x);
            minY = Math.min(minY, local.y);
            minZ = Math.min(minZ, local.z);
            maxX = Math.max(maxX, local.x);
            maxY = Math.max(maxY, local.y);
            maxZ = Math.max(maxZ, local.z);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Unique
    private static Vec3 cccc_offsetPointOnBlockSurface(Vec3 point, BlockPos block, @Nullable Vec3 previousPoint) {
        final double OUT = 0.15;

        double minX = block.getX();
        double minY = block.getY();
        double minZ = block.getZ();
        double maxX = minX + 1.0;
        double maxY = minY + 1.0;
        double maxZ = minZ + 1.0;

        double x = cccc_clamp(point.x, minX, maxX);
        double y = cccc_clamp(point.y, minY, maxY);
        double z = cccc_clamp(point.z, minZ, maxZ);

        int face = previousPoint == null ? -1 : cccc_entryFace(previousPoint, point, block);

        if (face == -1) {
            face = cccc_nearestFace(point, block);
        }

        switch (face) {
            case 0 -> x = minX - OUT;
            case 1 -> x = maxX + OUT;
            case 2 -> y = minY - OUT;
            case 3 -> y = maxY + OUT;
            case 4 -> z = minZ - OUT;
            default -> z = maxZ + OUT;
        }

        return new Vec3(x, y, z);
    }

    @Unique
    private static double cccc_sweptHitTime(Vec3 previousPoint, Vec3 point, BlockPos block) {
        double result = cccc_sweptHit(previousPoint, point, block, false);
        return Double.isNaN(result) ? Double.MAX_VALUE : result;
    }

    @Unique
    private static int cccc_entryFace(Vec3 previousPoint, Vec3 point, BlockPos block) {
        double result = cccc_sweptHit(previousPoint, point, block, true);
        if (!Double.isNaN(result) && result >= 0) return (int) result;

        Vec3 movement = point.subtract(previousPoint);
        double ax = Math.abs(movement.x);
        double ay = Math.abs(movement.y);
        double az = Math.abs(movement.z);
        if (ax < 1.0E-7 && ay < 1.0E-7 && az < 1.0E-7) return -1;
        if (ax >= ay && ax >= az) return movement.x >= 0 ? 0 : 1;
        if (ay >= ax && ay >= az) return movement.y >= 0 ? 2 : 3;
        return movement.z >= 0 ? 4 : 5;
    }

    @Unique
    private static double cccc_sweptHit(Vec3 previousPoint, Vec3 point, BlockPos block, boolean returnFace) {
        double minX = block.getX();
        double minY = block.getY();
        double minZ = block.getZ();
        double maxX = minX + 1.0;
        double maxY = minY + 1.0;
        double maxZ = minZ + 1.0;

        Vec3 delta = point.subtract(previousPoint);
        double tMin = 0.0;
        double tMax = 1.0;
        int face = -1;

        double[] previous = { previousPoint.x, previousPoint.y, previousPoint.z };
        double[] motion = { delta.x, delta.y, delta.z };
        double[] mins = { minX, minY, minZ };
        double[] maxs = { maxX, maxY, maxZ };
        int[] minFaces = { 0, 2, 4 };
        int[] maxFaces = { 1, 3, 5 };

        for (int i = 0; i < 3; i++) {
            double d = motion[i];
            if (Math.abs(d) < 1.0E-7) {
                if (previous[i] < mins[i] || previous[i] > maxs[i]) return Double.NaN;
                continue;
            }

            double t1 = (mins[i] - previous[i]) / d;
            double t2 = (maxs[i] - previous[i]) / d;
            int enterFace = d > 0 ? minFaces[i] : maxFaces[i];

            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }

            if (t1 > tMin) {
                tMin = t1;
                face = enterFace;
            }
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return Double.NaN;
        }

        if (tMax < 0.0 || tMin > 1.0) return Double.NaN;
        return returnFace ? face : Math.max(0.0, tMin);
    }

    @Unique
    private static int cccc_nearestFace(Vec3 point, BlockPos block) {
        double minX = block.getX();
        double minY = block.getY();
        double minZ = block.getZ();
        double maxX = minX + 1.0;
        double maxY = minY + 1.0;
        double maxZ = minZ + 1.0;

        double dxMin = Math.abs(point.x - minX);
        double dxMax = Math.abs(point.x - maxX);
        double dyMin = Math.abs(point.y - minY);
        double dyMax = Math.abs(point.y - maxY);
        double dzMin = Math.abs(point.z - minZ);
        double dzMax = Math.abs(point.z - maxZ);
        double nearest = Math.min(Math.min(Math.min(dxMin, dxMax), Math.min(dyMin, dyMax)), Math.min(dzMin, dzMax));

        if (nearest == dxMin) return 0;
        if (nearest == dxMax) return 1;
        if (nearest == dyMin) return 2;
        if (nearest == dyMax) return 3;
        if (nearest == dzMin) return 4;
        return 5;
    }

    @Unique
    private static double cccc_clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
