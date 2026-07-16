package cc.spea.cccc.compat;

import cc.spea.cccc.CCCC;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.lang.reflect.Method;

/**
 * Sable sub-level integration via reflection.
 *
 * ── Coordinate frames ─────────────────────────────────────────────────────────
 *
 * There are three frames in play:
 *
 *   1. Visible-world frame  – where the player and hook actually are in game.
 *      This is what all Minecraft entity positions use.
 *
 *   2. Backing-level frame  – where Sable physically stores its blocks in the
 *      real MC level (typically at large XZ coordinates like ±20 million).
 *      Pose3dc.transformPositionInverse(visiblePos)  → backing pos.
 *      Pose3dc.transformPosition(backingPos)         → visible pos.
 *
 *   3. Plot-local frame     – the coordinate system used by LevelPlot's own
 *      chunk/block storage.
 *      plot-local = backing − center
 *      where center = LevelPlot.getCenterBlock() (a BlockPos in backing frame).
 *      LevelPlot.getChunk(ChunkPos) and LevelChunk.getBlockState(BlockPos)
 *      both use plot-local coordinates.
 *
 * All attach anchors stored in {@link Hit} are in PLOT-LOCAL frame.
 * {@link #toWorldPos} and {@link #toRenderWorldPos} add center before
 * calling transformPosition to convert back to visible-world frame.
 *
 * @author R2bEEaton
 */
public final class SableCompat {

    private static boolean initialized = false;
    private static boolean available   = false;

    // ── Cached reflective handles ─────────────────────────────────────────────
    private static Method mGetContainer;       // SubLevelContainer.getContainer(Level)
    private static Method mGetAllSubLevels;   // SubLevelContainer.getAllSubLevels()
    private static Method mIsRemoved;         // SubLevel.isRemoved()
    private static Method mLogicalPose;       // SubLevel.logicalPose() → Pose3dc
    private static Method mRenderPose;        // ClientSubLevel.renderPose(float) → Pose3dc
    private static Method mTransformPosition; // Pose3dc.transformPosition(Vec3) → Vec3
    private static Method mTransformInverse;  // Pose3dc.transformPositionInverse(Vec3) → Vec3
    private static Method mGetPlot;           // SubLevel.getPlot() → LevelPlot
    private static Method mGetPlotChunk;      // LevelPlot.getChunk(ChunkPos) → LevelChunk (takes LOCAL chunk pos)
    private static Method mGetPlotCenter;     // LevelPlot.getCenterBlock() → BlockPos  (in BACKING frame)
    private static Method mPlotToLocalChunk;  // LevelPlot.toLocal(ChunkPos) → ChunkPos (backing → local chunk)

    private SableCompat() {}

    // ── Public API ────────────────────────────────────────────────────────────

    public static boolean isAvailable() {
        ensureInit();
        return available;
    }

    @Nullable
    public static Object findAt(Level level, Vec3 hookPos, AABB hookAABB) {
        Hit hit = findHitAt(level, hookPos, hookAABB);
        return hit == null ? null : hit.subLevel();
    }

    @Nullable
    public static Hit findHitAt(Level level, Vec3 hookPos, AABB hookAABB) {
        return findHitAt(level, hookPos, hookPos, hookAABB);
    }

    /**
     * Swept variant used by the hook mixin.  All returned anchor positions are
     * in plot-local frame.
     */
    @Nullable
    public static Hit findHitAt(Level level, Vec3 previousHookPos, Vec3 hookPos, AABB hookAABB) {
        if (!isAvailable()) return null;
        try {
            Object container  = mGetContainer.invoke(null, level);
            Iterable<?> subLevels = (Iterable<?>) mGetAllSubLevels.invoke(container);

            int slCount = 0;
            for (Object sl : subLevels) {
                slCount++;
                if (isRemoved(sl)) {
                    CCCC.LOGGER.debug("[cccc] findHitAt: sub-level {} removed", slCount);
                    continue;
                }

                Object pose   = mLogicalPose.invoke(sl);
                Object plot   = mGetPlot.invoke(sl);
                Vec3   center = plotCenter(plot);

                // visible-world → backing frame
                Vec3 backingPrev = (Vec3) mTransformInverse.invoke(pose, previousHookPos);
                Vec3 backing     = (Vec3) mTransformInverse.invoke(pose, hookPos);
                AABB backingAabb = transformAabbInverse(pose, hookAABB);

                // backing → plot-local frame
                Vec3 localPrev = backingPrev.subtract(center);
                Vec3 local     = backing.subtract(center);
                AABB localAabb = backingAabb.move(-center.x, -center.y, -center.z);

                CCCC.LOGGER.debug("[cccc] findHitAt: sl{} localPrev={} local={} localAabb={}",
                    slCount, localPrev, local, localAabb);

                Vec3 localAttach = findLocalAttachment(plot, localPrev, local, localAabb.inflate(0.1));
                CCCC.LOGGER.debug("[cccc] findHitAt: sl{} localAttach={}", slCount, localAttach);
                if (localAttach != null) {
                    return new Hit(sl, localAttach);
                }
            }
            if (slCount == 0) {
                CCCC.LOGGER.debug("[cccc] findHitAt: no sub-levels in container");
            }
        } catch (Exception e) {
            CCCC.LOGGER.debug("[cccc] SableCompat.findHitAt error: {}", e.toString());
        }
        return null;
    }

    /**
     * Returns the Sable sub-level nearest to {@code worldPos} (within 64 blocks
     * of the plot-local origin), using the hook's position converted to plot-local
     * space as the anchor.
     *
     * Primary attachment trigger: Sable registers its blocks through the real-world
     * collision system, so C&C's own {@code isStick} fires on contact.  No block-data
     * reads needed here — just pose transforms to find the right sub-level.
     */
    @Nullable
    public static Hit findSubLevelNear(Level level, Vec3 worldPos) {
        if (!isAvailable()) return null;
        try {
            Object      container = mGetContainer.invoke(null, level);
            Iterable<?> subLevels = (Iterable<?>) mGetAllSubLevels.invoke(container);

            Object bestSl    = null;
            Vec3   bestLocal = null;
            double bestDist  = 64.0;
            int    total     = 0;

            for (Object sl : subLevels) {
                total++;
                if (isRemoved(sl)) {
                    CCCC.LOGGER.debug("[cccc] findSubLevelNear: sl{} removed", total);
                    continue;
                }

                Object pose    = mLogicalPose.invoke(sl);
                Object plot    = mGetPlot.invoke(sl);
                Vec3   center  = plotCenter(plot);

                // visible-world → plot-local
                Vec3   backing  = (Vec3) mTransformInverse.invoke(pose, worldPos);
                Vec3   local    = backing.subtract(center);
                double dist     = local.length();

                CCCC.LOGGER.debug("[cccc] findSubLevelNear: sl{} backing={} local={} dist={}",
                    total, backing, local, dist);

                if (dist < bestDist) {
                    bestDist  = dist;
                    bestSl    = sl;
                    bestLocal = local;
                }
            }

            CCCC.LOGGER.debug("[cccc] findSubLevelNear: scanned {} sl, best dist={}", total, bestDist);
            return bestSl != null ? new Hit(bestSl, bestLocal) : null;
        } catch (Exception e) {
            CCCC.LOGGER.debug("[cccc] SableCompat.findSubLevelNear error: {}", e.toString());
            return null;
        }
    }

    public record Hit(Object subLevel, Vec3 localAttach) {}

    public static boolean hasBlockAt(Object subLevel, Vec3 localPos) {
        try {
            Object plot = mGetPlot.invoke(subLevel);
            return findLocalAttachment(plot, localPos, localPos, new AABB(localPos, localPos)) != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Nullable
    private static Vec3 findLocalAttachment(Object plot, Vec3 localPrev, Vec3 localPos, AABB localAabb) {
        if (!isAvailable() || mGetPlot == null || mGetPlotChunk == null) return null;
        try {
            BlockPos center = (BlockPos) mGetPlotCenter.invoke(plot);

            final double EPS = 1.0E-6;
            int minX = (int) Math.floor(localAabb.minX - EPS);
            int minY = (int) Math.floor(localAabb.minY - EPS);
            int minZ = (int) Math.floor(localAabb.minZ - EPS);
            int maxX = (int) Math.floor(localAabb.maxX + EPS);
            int maxY = (int) Math.floor(localAabb.maxY + EPS);
            int maxZ = (int) Math.floor(localAabb.maxZ + EPS);

            Vec3   bestAttach      = null;
            double bestHitTime     = Double.MAX_VALUE;
            double bestDistanceSqr = Double.MAX_VALUE;

            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        BlockPos   block = new BlockPos(x, y, z);
                        BlockState state = plotBlockState(plot, center, block);
                        if (state.isAir()) continue;

                        VoxelShape shape = state.getCollisionShape(
                            EmptyBlockGetter.INSTANCE, block, CollisionContext.empty());
                        if (shape.isEmpty()) continue;
                        AABB shapeBounds = shape.bounds().move(x, y, z);

                        double hitTime = sweptHitTime(localPrev, localPos, shapeBounds);
                        int    face    = entryFace(localPrev, localPos, shapeBounds);
                        Vec3   attach  = pointOnBlockSurface(localPos, shapeBounds, face);
                        double distSqr = attach.distanceToSqr(localPos);

                        if (hitTime < bestHitTime
                                || (hitTime == bestHitTime && distSqr < bestDistanceSqr)) {
                            bestHitTime     = hitTime;
                            bestDistanceSqr = distSqr;
                            bestAttach      = attach;
                        }
                    }
                }
            }
            return bestAttach;
        } catch (Exception e) {
            CCCC.LOGGER.debug("[cccc] SableCompat.findLocalAttachment error: {}", e.toString());
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
            minX = Math.min(minX, local.x); minY = Math.min(minY, local.y); minZ = Math.min(minZ, local.z);
            maxX = Math.max(maxX, local.x); maxY = Math.max(maxY, local.y); maxZ = Math.max(maxZ, local.z);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static Vec3 pointOnBlockSurface(Vec3 point, AABB box, int face) {
        double x = clamp(point.x, box.minX, box.maxX);
        double y = clamp(point.y, box.minY, box.maxY);
        double z = clamp(point.z, box.minZ, box.maxZ);
        if (face == -1) face = nearestFace(point, box);
        switch (face) {
            case 0 -> x = box.minX;
            case 1 -> x = box.maxX;
            case 2 -> y = box.minY;
            case 3 -> y = box.maxY;
            case 4 -> z = box.minZ;
            default -> z = box.maxZ;
        }
        return new Vec3(x, y, z);
    }

    private static double sweptHitTime(Vec3 prevPoint, Vec3 point, AABB box) {
        double result = sweptHit(prevPoint, point, box, false);
        return Double.isNaN(result) ? Double.MAX_VALUE : result;
    }

    private static int entryFace(Vec3 prevPoint, Vec3 point, AABB box) {
        double result = sweptHit(prevPoint, point, box, true);
        if (!Double.isNaN(result) && result >= 0) return (int) result;
        Vec3   m  = point.subtract(prevPoint);
        double ax = Math.abs(m.x), ay = Math.abs(m.y), az = Math.abs(m.z);
        if (ax < 1e-7 && ay < 1e-7 && az < 1e-7) return -1;
        if (ax >= ay && ax >= az) return m.x >= 0 ? 0 : 1;
        if (ay >= ax && ay >= az) return m.y >= 0 ? 2 : 3;
        return m.z >= 0 ? 4 : 5;
    }

    private static double sweptHit(Vec3 prev, Vec3 point, AABB box, boolean returnFace) {
        Vec3 delta = point.subtract(prev);
        double tMin = 0.0, tMax = 1.0;
        int face = -1;
        double[] p = { prev.x, prev.y, prev.z };
        double[] d = { delta.x, delta.y, delta.z };
        double[] lo = { box.minX, box.minY, box.minZ };
        double[] hi = { box.maxX, box.maxY, box.maxZ };
        int[] loF = { 0, 2, 4 }, hiF = { 1, 3, 5 };
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-7) {
                if (p[i] < lo[i] || p[i] > hi[i]) return Double.NaN;
                continue;
            }
            double t1 = (lo[i] - p[i]) / d[i];
            double t2 = (hi[i] - p[i]) / d[i];
            int ef = d[i] > 0 ? loF[i] : hiF[i];
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            if (t1 > tMin) { tMin = t1; face = ef; }
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return Double.NaN;
        }
        if (tMax < 0.0 || tMin > 1.0) return Double.NaN;
        return returnFace ? face : Math.max(0.0, tMin);
    }

    private static int nearestFace(Vec3 point, AABB box) {
        double dxMin = Math.abs(point.x - box.minX), dxMax = Math.abs(point.x - box.maxX);
        double dyMin = Math.abs(point.y - box.minY), dyMax = Math.abs(point.y - box.maxY);
        double dzMin = Math.abs(point.z - box.minZ), dzMax = Math.abs(point.z - box.maxZ);
        double n = Math.min(Math.min(Math.min(dxMin, dxMax), Math.min(dyMin, dyMax)), Math.min(dzMin, dzMax));
        if (n == dxMin) return 0; if (n == dxMax) return 1;
        if (n == dyMin) return 2; if (n == dyMax) return 3;
        if (n == dzMin) return 4; return 5;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // Reads a block state for the given PLOT-LOCAL block position.
    //
    // Coordinate notes:
    //   plot-local → + center → backing frame (large XZ, e.g. ±20M)
    //   LevelPlot.getChunk() takes LOCAL chunk pos (via toLocal conversion)
    //   LevelChunk.getBlockState() uses BACKING block coordinates for its
    //   within-chunk index (backingBlock.x & 15 etc.), because the chunk data
    //   was originally populated from the backing level.
    private static BlockState plotBlockState(Object plot, BlockPos center, BlockPos plotLocalBlock) throws Exception {
        BlockPos  backingBlock    = plotLocalBlock.offset(center);
        ChunkPos  backingChunkPos = new ChunkPos(backingBlock);
        ChunkPos  localChunkPos   = mPlotToLocalChunk != null
            ? (ChunkPos) mPlotToLocalChunk.invoke(plot, backingChunkPos)
            : backingChunkPos;
        LevelChunk chunk = (LevelChunk) mGetPlotChunk.invoke(plot, localChunkPos);
        return chunk != null ? chunk.getBlockState(backingBlock) : Blocks.AIR.defaultBlockState();
    }

    // Returns the plot center as a Vec3 (backing-level block coords of local origin).
    private static Vec3 plotCenter(Object plot) throws Exception {
        BlockPos c = (BlockPos) mGetPlotCenter.invoke(plot);
        return new Vec3(c.getX(), c.getY(), c.getZ());
    }

    // ── Coordinate conversions ────────────────────────────────────────────────

    /**
     * Converts a plot-local anchor back to visible-world position.
     * plotLocal → backingPos (add center) → visibleWorld (transformPosition).
     */
    @Nullable
    public static Vec3 toWorldPos(Object subLevel, Vec3 plotLocalPos) {
        if (!isAvailable() || subLevel == null) return null;
        try {
            Object pose   = mLogicalPose.invoke(subLevel);
            Object plot   = mGetPlot.invoke(subLevel);
            Vec3   center = plotCenter(plot);
            Vec3   backing = plotLocalPos.add(center);
            return (Vec3) mTransformPosition.invoke(pose, backing);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Same as {@link #toWorldPos} but uses the client render pose for smooth interpolation.
     */
    @Nullable
    public static Vec3 toRenderWorldPos(Object subLevel, Vec3 plotLocalPos, float partialTicks) {
        if (!isAvailable() || subLevel == null) return null;
        try {
            Object plot   = mGetPlot.invoke(subLevel);
            Vec3   center = plotCenter(plot);
            Vec3   backing = plotLocalPos.add(center);
            Object pose = mRenderPose == null
                ? mLogicalPose.invoke(subLevel)
                : mRenderPose.invoke(subLevel, partialTicks);
            return (Vec3) mTransformPosition.invoke(pose, backing);
        } catch (Exception e) {
            return toWorldPos(subLevel, plotLocalPos);
        }
    }

    /**
     * Converts a visible-world position to plot-local space (stored as anchor).
     * visibleWorld → backingPos (transformPositionInverse) → plotLocal (subtract center).
     */
    @Nullable
    public static Vec3 toLocalPos(Object subLevel, Vec3 worldPos) {
        if (!isAvailable() || subLevel == null) return null;
        try {
            Object pose   = mLogicalPose.invoke(subLevel);
            Object plot   = mGetPlot.invoke(subLevel);
            Vec3   center = plotCenter(plot);
            Vec3   backing = (Vec3) mTransformInverse.invoke(pose, worldPos);
            return backing.subtract(center);
        } catch (Exception e) {
            return null;
        }
    }

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
            mGetPlotChunk      = plotCls.getMethod("getChunk", ChunkPos.class);
            mGetPlotCenter     = plotCls.getMethod("getCenterBlock");
            mPlotToLocalChunk  = plotCls.getMethod("toLocal", ChunkPos.class);

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
