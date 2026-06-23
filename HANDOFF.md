# CCCC – Handoff Notes

**Mod:** Create x Critters & Companions Compat (`cccc`)  
**Status:** Create contraptions attach/pull and have a new interpolation fix for smoother rendering. Sable sub-levels use a new untested fallback that discards the physical hook before Sable movement and applies pull from player tick state.  
**Author:** R2bEEaton  

---

## What the mod does

Allows the C&C grappling hook (`crittersandcompanions:grappling_hook`) to attach to:

1. **Create `AbstractContraptionEntity`** – pistons, bearings, rope pulleys, etc.  
2. **Sable `SubLevel`** – physics sub-levels used by Create Aeronautics and Create: Offroad on swivel bearings, etc.

---

## How C&C's grappling hook works (bytecode-verified)

`GrapplingHookEntity` extends `ThrowableItemProjectile`. Key facts (from `javap -c`):

- **Does NOT override `onHitBlock` or `onHitEntity`** – collision is detected entirely inside `tick()`.
- Each tick calls `super.tick()` first (standard projectile physics), then:
  1. Gets `hookAABB = entity.getBoundingBox().inflate(0.25)` (instr 88–97).
  2. Calls `level.getBlockCollisions(this, hookAABB)` (instr 103–109) against the real world.
  3. Iterates VoxelShapes: if any non-empty shape intersects `hookAABB` → `hitSomething = true`.
  4. Sets `isStick = hitSomething` **every tick** (instr 202) — not latched, reset each tick.
  5. If `isStick && owner != null && distanceSq > stickLength` → pull player toward hook.
- **Discard check** (instr 65–86, runs before collision check): discards if `!isFocused()` or player too far.
- Fields: `protected boolean isStick`, `protected double stickLength`. No `stuckPos` or similar.

---

## Core approach

For Create, we `@Redirect` the `Level.getBlockCollisions` call in `tick()`. When the hook overlaps a moving contraption:

1. Transform the hook's inflated AABB into contraption-local space.
2. Scan overlapping contraption blocks and choose the nearest block-face surface anchor, offset slightly outside the block.
3. Return a fake `VoxelShape` offset to the hook's world position so C&C's intersection test passes → `isStick = true` naturally.
4. Each tick (`@Inject HEAD + TAIL`): call `setPos(worldAttachPoint)` + `setDeltaMovement(ZERO)` + `setNoGravity(true)` to pin the hook.

Create notes: the hook attaches, the player is pulled, and the hook follows the moving contraption. Recent tuning moved Create detection away from a center-block test because the hook could attach unreliably or pin inside the block when the projectile center had already penetrated. Latest tuning also lets the client establish the Create attachment and sets the hook's previous/current render positions from Create's previous/current transforms to reduce visible 20 TPS snapping.

Latest Create face-selection fix: sometimes the hook still clipped through the intended face and latched to a bottom/side face on first throw. The Create attach scan now sweeps the hook AABB from previous tick position to current tick position, scores overlapping blocks by swept hit time, and chooses the entry face from previous→current local motion. Nearest-face snapping is only a fallback when no motion/entry face can be inferred.

For Sable, the real hook entity cannot remain in or near the sub-level without stopping the swivel. The current fallback predicts a Sable hit at hook tick HEAD, records a local-space surface anchor, discards the real hook, cancels that hook tick, then applies the C&C-style pull from `PlayerTickEvent.Post`.

---

## Sable-specific problem

### Symptom
When the hook lands on a Sable sub-level (e.g., a spinning bearing):
- The **sub-level stops rotating immediately**.
- The player is **permanently pulled** toward that stopped position on every subsequent hook throw.

### Latest continuation

`javap` confirmed that `sable:retain_in_sub_level` only affects `EntitySubLevelUtil.shouldKick(Entity)`.
It suppresses `kickEntity`, but Sable's `SubLevelEntityCollision.collide(...)` still builds collision bounds and processes sub-level collision for retained entities.

The `noPhysics` patch was tested in-game and the swivel still stopped. Current code now avoids keeping a real hook entity attached to Sable at all:

1. At hook tick HEAD, before `ThrowableItemProjectile.tick()` can move the hook, build a swept AABB from the current bounding box and delta movement.
2. Query Sable at the predicted hook position.
3. If that predicted position hits a Sable block, snap the local anchor to the nearest block face, store it in `SableGrappleHandler`, discard the hook, and cancel the hook tick.
4. On `PlayerTickEvent.Post`, transform the stored local anchor back to world space and apply the same pull formula C&C uses:
   `speed = min(grapplingHookMaxSpeed, 0.01 * sqrt(distanceSq))`.
5. Clear the attachment when the player stops holding the same hook item, the sub-level is removed, or the player exceeds C&C's max hook distance.

Files changed:

- `src/main/java/cc/spea/cccc/mixin/GrapplingHookEntityMixin.java`
  - Sable detection can now happen at tick HEAD and cancel before projectile movement.
  - Sable redirect path now delegates to the fallback instead of pinning the real hook.
- `src/main/java/cc/spea/cccc/compat/SableCompat.java`
  - Still handles Sable reflection and sub-level local/world transforms.
- `src/main/java/cc/spea/cccc/compat/SableGrappleHandler.java`
  - New player tick pull manager for Sable attachments.
- `src/main/java/cc/spea/cccc/CCCC.java`
  - Registers `SableGrappleHandler::onPlayerTick` on `NeoForge.EVENT_BUS`.

Verification run:

- `.\gradlew.bat compileJava` ✅

Still needs a real client/server test on a Sable bearing. Expected tradeoff: Sable pull should work without a visible hook entity stuck to the block; a visual-only display entity can be added after the physics issue is solved.

### Latest log diagnosis

After testing the fallback, `run/logs/latest.log` showed a server fatal:

- `ReportedException: Exception generating new chunk`
- Stack path: `EmbeddedPlotLevelAccessor.getBlockState(...)` → `SableCompat.hasBlockAt(...)` → `SableCompat.findAt(...)` → `cccc_tryStartSableFallback(...)`
- Root exception: `ArrayIndexOutOfBoundsException` in vanilla aquifer worldgen

Cause: `SableCompat.hasBlockAt()` was asking Sable's embedded plot accessor for local block states during the early Sable-hit prediction. The accessor maps through `level.getBlockState(pos.offset(center))`, so probes that land outside loaded plot chunks can accidentally force ordinary world chunk generation. A plot-bounds-only guard was not enough; the log still crashed at `EmbeddedPlotLevelAccessor.getBlockState(...)` from `SableCompat.hasBlockAt(...)`.

Follow-up symptom: after adding a backing-world `EmbeddedPlotLevelAccessor.hasChunk(localChunkX, localChunkZ)` guard, crashes stopped, but hooks slid off Sable blocks. Likely reason: `hasChunk` asks the real backing level whether the offset chunk exists, which can be false even though Sable's `LevelPlot` has an in-memory `PlotChunkHolder` for the local block.

Second follow-up symptom: after switching to direct `LevelPlot.getChunk(...)` reads, hooks visibly collided with Sable blocks but still did not stick. Likely reason: the fallback was still testing only the hook center (`hookPos`) in local space. During normal collision, the hook AABB can touch a block while the center remains outside, so `hasBlockAt(center)` returned false and the fallback never attached.

Current fix:

- `SableCompat` now caches `LevelPlot.getBoundingBox()`, `BoundingBox3ic.contains(int, int, int)`, and `LevelPlot.getChunk(ChunkPos)`.
- Sable block probes read from Sable's own `LevelChunk` via `LevelPlot.getChunk(localChunk)` and `LevelChunk.getBlockState(localBlock)`, returning air when the plot chunk is missing. They no longer call `EmbeddedPlotLevelAccessor.getBlockState(...)`, so the hit probe should not trigger backing-world chunk generation.
- New `SableCompat.findHitAt(...)` transforms all 8 corners of the hook's swept/inflated world AABB into Sable local space, scans overlapping local plot blocks, and returns a local-space surface anchor for the nearest non-air block.
- `GrapplingHookEntityMixin` now starts the Sable fallback from that `Hit` result directly, instead of recomputing/snapping from the hook center.
- `hasBlockAt()` now fails closed (`false`) when reflection/probing fails, instead of attaching anyway.

Verification run after the fix:

- `.\gradlew.bat compileJava` ✅
- `.\gradlew.bat build` ✅

### What we know about Sable internals (from `javap`)

| Class | Key facts |
|---|---|
| `SubLevelContainer` | `getContainer(Level)` static factory; `queryIntersecting(BoundingBox3dc)` for spatial query; `getAllSubLevels()` |
| `SubLevel` | `logicalPose()` → `Pose3dc`; `lastPose()` → `Pose3dc`; `boundingBox()` → `BoundingBox3dc`; `getPlot()` → `LevelPlot`; `isRemoved()` |
| `Pose3dc` | `transformPosition(Vec3)` (local→world); `transformPositionInverse(Vec3)` (world→local) |
| `EmbeddedPlotLevelAccessor` | Implements `ServerLevelAccessor`; `getBlockState(BlockPos pos)` = `level.getBlockState(pos.offset(center))` — same local coordinate frame as `transformPositionInverse` |
| `EntitySubLevelUtil` | `shouldKick(Entity)` = `!type.is(SableTags.RETAIN_IN_SUB_LEVEL)`; `kickEntity(SubLevel, Entity)` |
| `SableTags.RETAIN_IN_SUB_LEVEL` | Tag key `sable:retain_in_sub_level` for entity types that should NOT be kicked from sub-levels |

### Things we've tried that didn't work

#### Attempt 1 – pin inside block + no tag
Hook placed via `setPos` inside the Sable block. Sable detected penetration, applied kick forces. Feedback loop (kick → repin → kick) froze the sub-level.

#### Attempt 2 – RETAIN tag, no `setPos`
Added `crittersandcompanions:grappling_hook` to `sable:retain_in_sub_level` tag (file: `data/sable/tags/entity_type/retain_in_sub_level.json`). Removed `setPos` for Sable, assuming Sable's own retain-tracking would carry the hook. **Sub-level still stopped.**

#### Attempt 3 – RETAIN tag + surface snap + `setPos`
On first attachment, snap the stored local attach point to the **nearest block face surface** (0.15 units outside) using `cccc_snapToBlockSurface()`. Then `setPos` to that world position each tick so the hook is never inside the rigid body. **Sub-level still stopped.**

#### Attempt 4 – RETAIN tag + surface snap + `setPos` + `Entity.noPhysics`
Enabled `noPhysics` before Sable entry and while pinned to the sub-level. **Sub-level still stopped.**

### Previous hypothesis

The surface snap (0.15 units outside the block) is probably not enough clearance. The hook entity has a non-zero AABB (it's a `ThrowableItemProjectile`), so even with the center 0.15 units outside the block, the entity's bounding box still overlaps the block's volume. Sable's physics solver detects overlap → applies reaction forces → sub-level stops.

Additionally, it's unclear whether `RETAIN_IN_SUB_LEVEL` actually suppresses contact-force computation in Sable's physics pipeline, or only suppresses the "kick" velocity impulse. If Sable's Rapier-based solver still computes contact manifolds for retained entities, the RETAIN tag won't help.

Update: the hook hitbox is `0.2 x 0.2`, so the old 0.15 face offset should clear a simple AABB. The stronger finding is that RETAIN only suppresses kicking, while retained entities still go through Sable's sub-level entity collision path.

### What to investigate next

1. **In-game test the fallback Sable pull.**  
   Try a C&C hook against a rotating Sable bearing / Aeronautics sub-level. Confirm whether the sub-level keeps rotating and whether the player pull follows the rotating attach point.

2. **Optional visual-only hook for the fallback approach.**  
   Replace the hook entity with a `TextDisplayEntity` (or `ItemDisplayEntity`) that has no physics hitbox. Sable would not compute contacts with it. The display entity follows the local attach point visually while our event handler applies the pull to the player.

---

## Relevant files

| File | Purpose |
|---|---|
| `src/main/java/cc/spea/cccc/mixin/GrapplingHookEntityMixin.java` | Core mixin: redirect, snap, pin, fake-solid |
| `src/main/java/cc/spea/cccc/compat/SableCompat.java` | All Sable API calls via reflection (companion JarInJar not on compile classpath) |
| `src/main/java/cc/spea/cccc/compat/SableGrappleHandler.java` | Sable fallback pull state + player tick handler |
| `src/main/resources/data/sable/tags/entity_type/retain_in_sub_level.json` | RETAIN tag so Sable doesn't kick the hook |
| `src/main/resources/cccc.mixins.json` | Mixin config |
| `gradle.properties` | Dependency version IDs |
| `build.gradle` | Maven repos + dep declarations |

---

## Create contraptions

The `AbstractContraptionEntity` path is working but still needs in-game verification after latest tuning:
- Hook detects the contraption via `getEntitiesOfClass` + local-space AABB overlap against `Contraption.getBlocks()`.
- Latest change stores a surface anchor offset 0.15 blocks outside the nearest overlapped contraption block, instead of storing the hook center.
- Face choice now prefers the swept entry face from the hook's previous-to-current motion, reducing cases where a fast hook clips through and attaches to a bottom/side face.
- Create detection now runs on both server and client; Sable fallback remains server-only.
- Position tracked each tick via `toGlobalVector(localAttach, 1f)` for current position and `toGlobalVector(localAttach, 0f, true)` for previous render position (`xo/yo/zo` and `xOld/yOld/zOld`).
- Player is pulled smoothly toward the moving/rotating contraption block.

---

## Dependency versions

| Mod | Version | Maven coordinate |
|---|---|---|
| Create | 6.0.10 (build 281) | `com.simibubi.create:create-1.21.1:6.0.10-281` @ `maven.createmod.net` |
| Critters & Companions | 1.21.1-2.3.4 | `maven.modrinth:critters-and-companions:a8zCwdaO` |
| Create Aeronautics (bundled) | 1.2.1 | `maven.modrinth:create-aeronautics:YhZLrAFC` |
| Sable | 1.2.2 | `maven.modrinth:sable:3FMsUjO4` |
| GeckoLib | 4.9 | `maven.modrinth:geckolib:F3JBWthV` |
| NeoForge | 21.1.234 | — |
| Minecraft | 1.21.1 | — |
