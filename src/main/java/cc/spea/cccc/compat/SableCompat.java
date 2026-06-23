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
 * So the accessor uses the same local frame that Pose3dc.transformPositionInverse
 * produces — no extra offset needed.
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
    private static Method       mTransformPosition;  // Pose3dc.transformPosition(Vec3) → Vec3
    private static Method       mTransformInverse;   // Pose3dc.transformPositionInverse(Vec3) → Vec3
    private static Method       mGetPlot;            // SubLevel.getPlot() → LevelPlot
    private static Method       mGetPlotChunk;       // LevelPlot.getChunk(ChunkPos) → LevelChunk
    private static Method       mGetPlotBounds;      // LevelPlot.getBoundingBox() → BoundingBox3ic
    private static Method       mBoundsContains;     // BoundingBox3ic.contains(int, int, int)
    private static Constructor<?> ctorBB3d;          // BoundingBox3d(double × 6)
    private static Method       mQueryIntersecting;  // SubLevelContainer.queryIntersecting(BoundingBox3dc)
    private static Class<?>     clsBB3dc;            // interface BoundingBox3dc

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
        if (!isAvailable()) return null;
        try {
            Object container = mGetContainer.invoke(null, level);
            Iterable<?> subLevels;

            if (mQueryIntersecting != null && ctorBB3d != null) {
                // Tight query — only sub-levels whose world bounding box actually
                // overlaps the hook's (already-inflated) AABB.  No extra inflate
                // so we don't pick up distant sub-levels.
                Object query = ctorBB3d.newInstance(
                    hookAABB.minX, hookAABB.minY, hookAABB.minZ,
                    hookAABB.maxX, hookAABB.maxY, hookAABB.maxZ
                );
                subLevels = (Iterable<?>) mQueryIntersecting.invoke(container, query);
            } else {
                subLevels = (Iterable<?>) mGetAllSubLevels.invoke(container);
            }

            for (Object sl : subLevels) {
                if (isRemoved(sl)) continue;

                // Convert hook world pos → sub-level local frame
                Object pose  = mLogicalPose.invoke(sl);
                Vec3   local = (Vec3) mTransformInverse.invoke(pose, hookPos);
                AABB   localAabb = transformAabbInverse(pose, hookAABB);

                Vec3 localAttach = findLocalAttachment(sl, local, localAabb);
                if (localAttach != null) {
                    return new Hit(sl, localAttach);
                }
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
     * Uses EmbeddedPlotLevelAccessor.getBlockState(localBlockPos) which maps as:
     *   level.getBlockState(localBlockPos.offset(plotCenter))
     * i.e. the same local coordinate frame Pose3dc.transformPositionInverse uses.
     */
    public static boolean hasBlockAt(Object subLevel, Vec3 localPos) {
        return findLocalAttachment(subLevel, localPos, new AABB(localPos, localPos)) != null;
    }

    @Nullable
    private static Vec3 findLocalAttachment(Object subLevel, Vec3 localPos, AABB localAabb) {
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
            double bestDistanceSqr = Double.MAX_VALUE;

            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        BlockPos block = new BlockPos(x, y, z);
                        if (!plotContains(plot, block) || plotBlockState(plot, block).isAir()) continue;

                        Vec3 attach = closestPointOnBlockSurface(localPos, block);
                        double distanceSqr = attach.distanceToSqr(localPos);
                        if (distanceSqr < bestDistanceSqr) {
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

    private static Vec3 closestPointOnBlockSurface(Vec3 point, BlockPos block) {
        double minX = block.getX();
        double minY = block.getY();
        double minZ = block.getZ();
        double maxX = minX + 1.0;
        double maxY = minY + 1.0;
        double maxZ = minZ + 1.0;

        double x = clamp(point.x, minX, maxX);
        double y = clamp(point.y, minY, maxY);
        double z = clamp(point.z, minZ, maxZ);

        if (point.x > minX && point.x < maxX
            && point.y > minY && point.y < maxY
            && point.z > minZ && point.z < maxZ) {
            double dxMin = point.x - minX;
            double dxMax = maxX - point.x;
            double dyMin = point.y - minY;
            double dyMax = maxY - point.y;
            double dzMin = point.z - minZ;
            double dzMax = maxZ - point.z;
            double nearest = Math.min(Math.min(Math.min(dxMin, dxMax), Math.min(dyMin, dyMax)), Math.min(dzMin, dzMax));

            if (nearest == dxMin) x = minX;
            else if (nearest == dxMax) x = maxX;
            else if (nearest == dyMin) y = minY;
            else if (nearest == dyMax) y = maxY;
            else if (nearest == dzMin) z = minZ;
            else z = maxZ;
        }

        return new Vec3(x, y, z);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static BlockState plotBlockState(Object plot, BlockPos localBlock) throws Exception {
        ChunkPos localChunk = new ChunkPos(localBlock);
        LevelChunk chunk = (LevelChunk) mGetPlotChunk.invoke(plot, localChunk);
        return chunk == null
            ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
            : chunk.getBlockState(localBlock);
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

            Class<?> plotCls = Class.forName("dev.ryanhcode.sable.sublevel.plot.LevelPlot");
            mGetPlotChunk = plotCls.getMethod("getChunk", ChunkPos.class);
            mGetPlotBounds = plotCls.getMethod("getBoundingBox");

            // Companion classes live in the JarInJar; accessible at runtime via NeoForge
            // JarInJar extraction but not at Gradle compile time.
            try {
                clsBB3dc = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3dc");
                Class<?> bb3iCls = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3ic");
                mBoundsContains = bb3iCls.getMethod("contains", int.class, int.class, int.class);
                Class<?> bb3dCls = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3d");
                ctorBB3d         = bb3dCls.getConstructor(
                    double.class, double.class, double.class, double.class, double.class, double.class);
                mQueryIntersecting = containerCls.getMethod("queryIntersecting", clsBB3dc);
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
