package cc.spea.cccc.compat;

import cc.spea.cccc.CCCC;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Sable sub-level integration via reflection.
 *
 * Why reflection?
 * Sable ships its companion library (BoundingBox3d, Pose3dc, etc.) as a
 * JarInJar inside the outer Sable JAR.  When the outer JAR is on the compile
 * classpath the JVM does not automatically expose the nested JAR's classes, so
 * direct imports would fail to compile.  Reflection lets us call the API at
 * runtime once Sable is actually loaded by NeoForge.
 *
 * All method handles are cached on first use; a ClassNotFoundException on init
 * marks Sable as unavailable and every query returns null immediately.
 *
 * Coordinate mapping (from bytecode analysis of EmbeddedPlotLevelAccessor):
 *   getBlockState(pos) = level.getBlockState(pos.offset(center))
 * Pose3dc.transformPositionInverse produces the accessor's local block frame;
 * direct chunk reads must add the plot center before querying LevelChunk data.
 *
 * @author R2bEEaton
 */
public final class SableCompat {

    private static boolean initialized = false;
    private static boolean available   = false;

    // ── Cached reflective handles ─────────────────────────────────────────────
    private static Method       mGetContainer;       // SubLevelContainer.getContainer(Level)
    private static Method       mGetAllSubLevels;    // SubLevelContainer.getAllSubLevels()
    private static Method       mIsRemoved;          // SubLevel.isRemoved()
    private static Method       mLogicalPose;        // SubLevel.logicalPose() → Pose3dc
    private static Method       mRenderPose;         // ClientSubLevel.renderPose(float) → Pose3dc
    private static Method       mTransformPosition;  // Pose3dc.transformPosition(Vec3) → Vec3
    private static Method       mTransformInverse;   // Pose3dc.transformPositionInverse(Vec3) → Vec3
    private static Method       mGetPlot;            // SubLevel.getPlot() → LevelPlot
    private static Method       mGetPlotChunk;       // LevelPlot.getChunk(ChunkPos) → LevelChunk
    private static Method       mGetPlotCenterBlock; // LevelPlot.getCenterBlock() → BlockPos
    private static Method       mPlotToLocalChunk;   // LevelPlot.toLocal(ChunkPos) → ChunkPos
    private static Method       mGetPlotBounds;      // LevelPlot.getBoundingBox() → BoundingBox3ic
    private static Method       mBoundsContains;     // BoundingBox3ic.contains(int, int, int)
    private static Constructor<?> ctorBB3d;          // BoundingBox3d(double × 6)
    private static Method       mQueryIntersecting;  // SubLevelContainer.queryIntersecting(BoundingBox3dc)
    private static Object       sableHelper;         // Sable.HELPER
    private static Method       mRunIncludingSubLevels; // ActiveSableCompanion.runIncludingSubLevels(...)
    private static Class<?>     clsBB3dc;            // interface BoundingBox3dc

    private static final Vec3[] HELPER_SAMPLE_OFFSETS = {
        new Vec3(0.0, 0.0, 0.0),
        new Vec3(0.35, 0.0, 0.0),
        new Vec3(-0.35, 0.0, 0.0),
        new Vec3(0.0, 0.35, 0.0),
        new Vec3(0.0, -0.35, 0.0),
        new Vec3(0.0, 0.0, 0.35),
        new Vec3(0.0, 0.0, -0.35)
    };

    private SableCompat() {}

    // ── Public API ────────────────────────────────────────────────────────────

    public static boolean isAvailable() {
        ensureInit();
        return available;
    }

    /**
     * Returns the first Sable sub-level that has a non-air block at the hook's
     * current position, or {@code null} if none is found or Sable is unavailable.
     *
     * Uses queryIntersecting for efficiency when companion classes are accessible
     * (NeoForge JarInJar extraction makes them available at runtime even though
     * Gradle can't see them at compile time).
     */
    @Nullable
    public static Object findAt(Level level, Vec3 hookPos, AABB hookAABB) {
        Hit hit = findHitAt(level, hookPos, hookAABB);
        return hit == null ? null : hit.subLevel();
    }

    /**
     * Returns the first Sable sub-level whose local plot blocks overlap the
     * hook AABB, plus a local-space surface anchor for the manual pull.
     */
    @Nullable
    public static Hit findHitAt(Level level, Vec3 hookPos, AABB hookAABB) {
        return findHitAt(level, hookPos, hookPos, hookAABB);
    }

    /**
     * Swept variant used by the hook mixin.  Sable can bounce the projectile
     * before the hook center ever settles inside a local block, so using the
     * previous→current motion gives us the entry face instead of a post-bounce
     * final position.
     */
    @Nullable
    public static Hit findHitAt(Level level, Vec3 previousHookPos, Vec3 hookPos, AABB hookAABB) {
        if (!isAvailable()) return null;
        try {
            Object container = mGetContainer.invoke(null, level);
            Set<Object> broadHits = queryBroadHits(container, hookAABB.inflate(0.75));
            Iterable<?> subLevels = (Iterable<?>) mGetAllSubLevels.invoke(container);

            for (Object sl : subLevels) {
                if (isRemoved(sl)) continue;

                // Convert hook world pos → sub-level local frame
                Object pose  = mLogicalPose.invoke(sl);
                Vec3   localPrevious = (Vec3) mTransformInverse.invoke(pose, previousHookPos);
                Vec3   local = (Vec3) mTransformInverse.invoke(pose, hookPos);
                AABB   localAabb = transformAabbInverse(pose, hookAABB);

                Vec3 localAttach = findLocalAttachment(sl, localPrevious, local, localAabb.inflate(0.75));
                if (localAttach != null) {
                    return new Hit(sl, localAttach);
                }

                if (broadHits.contains(sl)) {
                    Vec3 broadAttach = findLocalAttachment(sl, localPrevious, local, localAabb.inflate(2.0), 4.0);
                    if (broadAttach != null) {
                        CCCC.LOGGER.debug("[cccc] Sable broadphase block fallback hit at local {}", broadAttach);
                        return new Hit(sl, broadAttach);
                    }
                }
            }

            Hit helperHit = findHelperAttachment(level, previousHookPos, hookPos);
            if (helperHit != null) {
                return helperHit;
            }
        } catch (Exception e) {
            CCCC.LOGGER.debug("[cccc] SableCompat.findHitAt error: {}", e.toString());
        }
        return null;
    }

    public record Hit(Object subLevel, Vec3 localAttach) {}

    /**
     * Returns true if the sub-level has a non-air block at {@code localPos}.
     *
     * Uses the same local coordinate frame as EmbeddedPlotLevelAccessor:
     *   backingBlock = localBlockPos.offset(plotCenter)
     */
    public static boolean hasBlockAt(Object subLevel, Vec3 localPos) {
        return findLocalAttachment(subLevel, localPos, localPos, new AABB(localPos, localPos)) != null;
    }

    @Nullable
    private static Vec3 findLocalAttachment(Object subLevel, Vec3 localPreviousPos, Vec3 localPos, AABB localAabb) {
        return findLocalAttachment(subLevel, localPreviousPos, localPos, localAabb, Double.POSITIVE_INFINITY);
    }

    @Nullable
    private static Vec3 findLocalAttachment(
        Object subLevel,
        Vec3 localPreviousPos,
        Vec3 localPos,
        AABB localAabb,
        double maxAttachDistanceSqr
    ) {
        if (!isAvailable() || mGetPlot == null || mGetPlotChunk == null) return null;
        try {
            Object plot = mGetPlot.invoke(subLevel);

            final double EPS = 1.0E-6;
            int minX = (int) Math.floor(localAabb.minX - EPS);
            int minY = (int) Math.floor(localAabb.minY - EPS);
            int minZ = (int) Math.floor(localAabb.minZ - EPS);
            int maxX = (int) Math.floor(localAabb.maxX + EPS);
            int maxY = (int) Math.floor(localAabb.maxY + EPS);
            int maxZ = (int) Math.floor(localAabb.maxZ + EPS);

            Vec3 bestAttach = null;
            double bestHitTime = Double.MAX_VALUE;
            double bestDistanceSqr = Double.MAX_VALUE;

            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        BlockPos block = new BlockPos(x, y, z);
                        if (plotBlockState(plot, block).isAir()) continue;

                        double hitTime = sweptHitTime(localPreviousPos, localPos, block);
                        Vec3 attach = pointOnBlockSurface(localPos, block, entryFace(localPreviousPos, localPos, block));
                        double distanceSqr = attach.distanceToSqr(localPos);
                        if (distanceSqr > maxAttachDistanceSqr) continue;
                        if (hitTime < bestHitTime || (hitTime == bestHitTime && distanceSqr < bestDistanceSqr)) {
                            bestHitTime = hitTime;
                            bestDistanceSqr = distanceSqr;
                            bestAttach = attach;
                        }
                    }
                }
            }
            return bestAttach;
        } catch (Exception e) {
            CCCC.LOGGER.debug("[cccc] SableCompat.hasBlockAt error: {}", e.toString());
            return null;
        }
    }

    private static AABB transformAabbInverse(Object pose, AABB worldAabb) throws Exception {
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

        Vec3 first = (Vec3) mTransformInverse.invoke(pose, corners[0]);
        double minX = first.x, minY = first.y, minZ = first.z;
        double maxX = first.x, maxY = first.y, maxZ = first.z;
        for (int i = 1; i < corners.length; i++) {
            Vec3 local = (Vec3) mTransformInverse.invoke(pose, corners[i]);
            minX = Math.min(minX, local.x);
            minY = Math.min(minY, local.y);
            minZ = Math.min(minZ, local.z);
            maxX = Math.max(maxX, local.x);
            maxY = Math.max(maxY, local.y);
            maxZ = Math.max(maxZ, local.z);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static Vec3 pointOnBlockSurface(Vec3 point, BlockPos block, int face) {
        double minX = block.getX();
        double minY = block.getY();
        double minZ = block.getZ();
        double maxX = minX + 1.0;
        double maxY = minY + 1.0;
        double maxZ = minZ + 1.0;

        double x = clamp(point.x, minX, maxX);
        double y = clamp(point.y, minY, maxY);
        double z = clamp(point.z, minZ, maxZ);

        if (face == -1) {
            face = nearestFace(point, block);
        }

        switch (face) {
            case 0 -> x = minX;
            case 1 -> x = maxX;
            case 2 -> y = minY;
            case 3 -> y = maxY;
            case 4 -> z = minZ;
            default -> z = maxZ;
        }

        return new Vec3(x, y, z);
    }

    private static double sweptHitTime(Vec3 previousPoint, Vec3 point, BlockPos block) {
        double result = sweptHit(previousPoint, point, block, false);
        return Double.isNaN(result) ? Double.MAX_VALUE : result;
    }

    private static int entryFace(Vec3 previousPoint, Vec3 point, BlockPos block) {
        double result = sweptHit(previousPoint, point, block, true);
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

    private static double sweptHit(Vec3 previousPoint, Vec3 point, BlockPos block, boolean returnFace) {
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

    private static int nearestFace(Vec3 point, BlockPos block) {
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

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @Nullable
    private static Hit findHelperAttachment(Level level, Vec3 previousHookPos, Vec3 hookPos) throws Exception {
        if (sableHelper == null || mRunIncludingSubLevels == null) return null;

        double length = hookPos.distanceTo(previousHookPos);
        int steps = Math.max(1, Math.min(12, (int) Math.ceil(length / 0.35)));

        for (int step = 0; step <= steps; step++) {
            double t = (double) step / (double) steps;
            Vec3 centerSample = previousHookPos.lerp(hookPos, t);

            for (Vec3 offset : HELPER_SAMPLE_OFFSETS) {
                final Vec3 sample = centerSample.add(offset);
                BiFunction<Object, BlockPos, Object> callback = (access, block) -> {
                    if (access == null || isRemoved(access)) return null;

                    try {
                        Object pose = mLogicalPose.invoke(access);
                        Vec3 localPrevious = (Vec3) mTransformInverse.invoke(pose, previousHookPos);
                        Vec3 localSample = (Vec3) mTransformInverse.invoke(pose, sample);
                        Vec3 attach = findLocalAttachmentNear(access, localPrevious, localSample, block, 1);
                        return attach == null ? null : new Hit(access, attach);
                    } catch (Exception e) {
                        return null;
                    }
                };

                Object result = mRunIncludingSubLevels.invoke(sableHelper, level, sample, false, null, callback);
                if (result instanceof Hit hit) {
                    CCCC.LOGGER.debug("[cccc] Sable helper fallback hit at local {}", hit.localAttach());
                    return hit;
                }
            }
        }

        return null;
    }

    @Nullable
    private static Vec3 findLocalAttachmentNear(
        Object subLevel,
        Vec3 localPreviousPos,
        Vec3 localPos,
        BlockPos centerBlock,
        int radius
    ) {
        AABB localAabb = new AABB(
            centerBlock.getX() - radius,
            centerBlock.getY() - radius,
            centerBlock.getZ() - radius,
            centerBlock.getX() + radius + 1.0,
            centerBlock.getY() + radius + 1.0,
            centerBlock.getZ() + radius + 1.0
        );
        return findLocalAttachment(subLevel, localPreviousPos, localPos, localAabb, 9.0);
    }

    private static BlockState plotBlockState(Object plot, BlockPos localBlock) throws Exception {
        BlockState backingFrameState = plotBlockStateForBackingBlock(plot, localBlock);
        if (!backingFrameState.isAir()) return backingFrameState;

        BlockPos center = (BlockPos) mGetPlotCenterBlock.invoke(plot);
        BlockPos backingBlock = localBlock.offset(center);
        BlockState centeredFrameState = plotBlockStateForBackingBlock(plot, backingBlock);
        if (!centeredFrameState.isAir()) return centeredFrameState;

        LevelChunk localChunk = (LevelChunk) mGetPlotChunk.invoke(plot, new ChunkPos(localBlock));
        if (localChunk != null) {
            BlockState localFrameState = localChunk.getBlockState(localBlock);
            if (!localFrameState.isAir()) return localFrameState;
        }

        return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
    }

    private static BlockState plotBlockStateForBackingBlock(Object plot, BlockPos backingBlock) throws Exception {
        ChunkPos localChunk = (ChunkPos) mPlotToLocalChunk.invoke(plot, new ChunkPos(backingBlock));
        LevelChunk chunk = (LevelChunk) mGetPlotChunk.invoke(plot, localChunk);
        return chunk == null
            ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
            : chunk.getBlockState(backingBlock);
    }

    private static Set<Object> queryBroadHits(Object container, AABB worldAabb) throws Exception {
        if (ctorBB3d == null || mQueryIntersecting == null) return Collections.emptySet();

        Object queryBox = ctorBB3d.newInstance(
            worldAabb.minX, worldAabb.minY, worldAabb.minZ,
            worldAabb.maxX, worldAabb.maxY, worldAabb.maxZ
        );
        Iterable<?> hits = (Iterable<?>) mQueryIntersecting.invoke(container, queryBox);
        Set<Object> result = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object hit : hits) {
            result.add(hit);
        }
        return result;
    }

    private static boolean plotContains(Object plot, BlockPos localBlock) throws Exception {
        if (mGetPlotBounds == null || mBoundsContains == null) return false;
        Object bounds = mGetPlotBounds.invoke(plot);
        return (boolean) mBoundsContains.invoke(
            bounds,
            localBlock.getX(),
            localBlock.getY(),
            localBlock.getZ()
        );
    }

    /** Convert a sub-level-local position back to world space (called each tick). */
    @Nullable
    public static Vec3 toWorldPos(Object subLevel, Vec3 localPos) {
        if (!isAvailable() || subLevel == null) return null;
        try {
            Object pose = mLogicalPose.invoke(subLevel);
            return (Vec3) mTransformPosition.invoke(pose, localPos);
        } catch (Exception e) {
            return null;
        }
    }

    /** Convert a sub-level-local position through the client render pose when available. */
    @Nullable
    public static Vec3 toRenderWorldPos(Object subLevel, Vec3 localPos, float partialTicks) {
        if (!isAvailable() || subLevel == null) return null;
        try {
            Object pose = mRenderPose == null ? mLogicalPose.invoke(subLevel) : mRenderPose.invoke(subLevel, partialTicks);
            return (Vec3) mTransformPosition.invoke(pose, localPos);
        } catch (Exception e) {
            return toWorldPos(subLevel, localPos);
        }
    }

    /** Convert a world position to sub-level-local space (stored as attach point). */
    @Nullable
    public static Vec3 toLocalPos(Object subLevel, Vec3 worldPos) {
        if (!isAvailable() || subLevel == null) return null;
        try {
            Object pose = mLogicalPose.invoke(subLevel);
            return (Vec3) mTransformInverse.invoke(pose, worldPos);
        } catch (Exception e) {
            return null;
        }
    }

    /** Returns true if the sub-level has been removed / disassembled. */
    public static boolean isRemoved(Object subLevel) {
        if (!isAvailable() || subLevel == null) return true;
        try {
            return (boolean) mIsRemoved.invoke(subLevel);
        } catch (Exception e) {
            return true;
        }
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    private static synchronized void ensureInit() {
        if (initialized) return;
        initialized = true;
        try {
            Class<?> containerCls = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
            mGetContainer    = containerCls.getMethod("getContainer", Level.class);
            mGetAllSubLevels = containerCls.getMethod("getAllSubLevels");

            Class<?> subLevelCls = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel");
            mIsRemoved   = subLevelCls.getMethod("isRemoved");
            mLogicalPose = subLevelCls.getMethod("logicalPose");
            mGetPlot     = subLevelCls.getMethod("getPlot");

            try {
                Class<?> clientSubLevelCls = Class.forName("dev.ryanhcode.sable.sublevel.ClientSubLevel");
                mRenderPose = clientSubLevelCls.getMethod("renderPose", float.class);
            } catch (ClassNotFoundException | NoSuchMethodException ignored) {
                mRenderPose = null;
            }

            Class<?> plotCls = Class.forName("dev.ryanhcode.sable.sublevel.plot.LevelPlot");
            mGetPlotChunk = plotCls.getMethod("getChunk", ChunkPos.class);
            mGetPlotCenterBlock = plotCls.getMethod("getCenterBlock");
            mPlotToLocalChunk = plotCls.getMethod("toLocal", ChunkPos.class);
            mGetPlotBounds = plotCls.getMethod("getBoundingBox");

            // Companion classes live in the JarInJar; accessible at runtime via NeoForge
            // JarInJar extraction but not at Gradle compile time.
            try {
                clsBB3dc = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3dc");
                Class<?> subLevelAccessCls = Class.forName("dev.ryanhcode.sable.companion.SubLevelAccess");
                Class<?> bb3iCls = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3ic");
                mBoundsContains = bb3iCls.getMethod("contains", int.class, int.class, int.class);
                Class<?> bb3dCls = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3d");
                ctorBB3d         = bb3dCls.getConstructor(
                    double.class, double.class, double.class, double.class, double.class, double.class);
                mQueryIntersecting = containerCls.getMethod("queryIntersecting", clsBB3dc);

                Class<?> sableCls = Class.forName("dev.ryanhcode.sable.Sable");
                sableHelper = sableCls.getField("HELPER").get(null);
                Class<?> helperCls = Class.forName("dev.ryanhcode.sable.ActiveSableCompanion");
                mRunIncludingSubLevels = helperCls.getMethod(
                    "runIncludingSubLevels",
                    Level.class,
                    Vec3.class,
                    boolean.class,
                    subLevelAccessCls,
                    BiFunction.class
                );
            } catch (ClassNotFoundException ignored) {
                CCCC.LOGGER.debug("[cccc] Sable companion not yet extracted; using getAllSubLevels fallback");
            }

            Class<?> poseCls = Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc");
            mTransformPosition = poseCls.getMethod("transformPosition", Vec3.class);
            mTransformInverse  = poseCls.getMethod("transformPositionInverse", Vec3.class);

            available = true;
            CCCC.LOGGER.info("[cccc] Sable sub-level support active");
        } catch (ClassNotFoundException e) {
            CCCC.LOGGER.info("[cccc] Sable not loaded — sub-level hooks disabled");
        } catch (Exception e) {
            CCCC.LOGGER.warn("[cccc] Sable init failed: {}", e.toString());
        }
    }
}
