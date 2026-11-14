package net.quiwh.cwiworldgen.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.Blocks;
import net.quiwh.cwiworldgen.hydrology.current.CurrentMotion;
import net.quiwh.cwiworldgen.hydrology.current.BoatCurrentSurface;
import net.quiwh.cwiworldgen.hydrology.current.RuntimeCurrentSampler;
import net.quiwh.cwiworldgen.config.CurrentConfig;
import net.quiwh.cwiworldgen.hydrology.current.RiverCurrents;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** One motion-owner path for current and buoyancy; clients receive bounds, never the terrain seed. */
@Mixin(Boat.class)
public abstract class TerrainBoatMixin {
    @Unique private static final EntityDataAccessor<Boolean> CWI_WORLDGEN_RIVER =
            SynchedEntityData.defineId(Boat.class, EntityDataSerializers.BOOLEAN);
    @Unique private static final EntityDataAccessor<Integer> CWI_WORLDGEN_GENERATION =
            SynchedEntityData.defineId(Boat.class, EntityDataSerializers.INT);
    @Unique private static final EntityDataAccessor<Integer> CWI_WORLDGEN_WATER_MIN =
            SynchedEntityData.defineId(Boat.class, EntityDataSerializers.INT);
    @Unique private static final EntityDataAccessor<Integer> CWI_WORLDGEN_WATER_MAX =
            SynchedEntityData.defineId(Boat.class, EntityDataSerializers.INT);
    @Unique private ReferenceTerrain cwiWorldgen$lastTerrain;
    @Unique private long cwiWorldgen$lastColumn = Long.MIN_VALUE;
    @Shadow private Boat.Status status;

    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void cwiWorldgen$defineRiverFlag(SynchedEntityData.Builder builder, CallbackInfo ci) {
        builder.define(CWI_WORLDGEN_RIVER, false);
        builder.define(CWI_WORLDGEN_GENERATION, 0);
        builder.define(CWI_WORLDGEN_WATER_MIN, 1);
        builder.define(CWI_WORLDGEN_WATER_MAX, 0);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void cwiWorldgen$syncRiverFlag(CallbackInfo ci) {
        Boat boat = (Boat) (Object) this;
        if (!(boat.level() instanceof ServerLevel level)) return;
        ReferenceTerrain terrain = ((TerrainRandomState) (Object) level.getChunkSource().randomState()).cwiWorldgen$terrain();
        int generation = terrain == null ? 0 : terrain.generationVersion();
        boat.getEntityData().set(CWI_WORLDGEN_GENERATION, generation);
        int x = boat.getBlockX(), z = boat.getBlockZ();
        long key = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
        if (generation >= 2) {
            var field = net.quiwh.cwiworldgen.hydrology.current.ServerCurrentFields.fieldIfLoaded(level, x >> 4, z >> 4);
            int y = net.minecraft.util.Mth.floor(boat.getBoundingBox().minY + .05);
            var column = field == null ? net.quiwh.cwiworldgen.hydrology.current.CurrentColumn.EMPTY : field.column(x & 15, y, z & 15);
            boat.getEntityData().set(CWI_WORLDGEN_WATER_MIN, column.plannedWater() ? column.minY() : 1);
            boat.getEntityData().set(CWI_WORLDGEN_WATER_MAX, column.plannedWater() ? column.maxY() : 0);
        } else if (terrain != cwiWorldgen$lastTerrain || key != cwiWorldgen$lastColumn) {
            // Existing worlds retain the source's two-dimensional buoyancy decision.
            boat.getEntityData().set(CWI_WORLDGEN_RIVER, terrain != null && terrain.sample(x, z).terrain.isRiver());
            cwiWorldgen$lastTerrain = terrain;
            cwiWorldgen$lastColumn = key;
        }
        if (generation >= 2) boat.getEntityData().set(CWI_WORLDGEN_RIVER, cwiWorldgen$insidePlannedWater(boat));
    }

    @Unique private boolean cwiWorldgen$insidePlannedWater(Boat boat) {
        int y = net.minecraft.util.Mth.floor(boat.getBoundingBox().minY + .05);
        int min = boat.getEntityData().get(CWI_WORLDGEN_WATER_MIN);
        int max = boat.getEntityData().get(CWI_WORLDGEN_WATER_MAX);
        if (y < min || y > max) return false;
        var chunk = RuntimeCurrentSampler.loadedChunk(boat.level(), boat.getBlockX() >> 4, boat.getBlockZ() >> 4);
        return chunk != null && chunk.getBlockState(new BlockPos(boat.getBlockX(), y, boat.getBlockZ())).is(Blocks.WATER);
    }

    @ModifyArg(method = "tick", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/entity/vehicle/Boat;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"), index = 1)
    private net.minecraft.world.phys.Vec3 cwiWorldgen$applyOwnedRiverMotion(net.minecraft.world.phys.Vec3 motion) {
        // The invoke occurs only within vanilla's isControlledByLocalInstance branch.
        // Modify the actual argument: setting delta at an INVOKE injection leaves an old Vec3 on the stack.
        Boat boat = (Boat) (Object) this;
        boolean modern = boat.getEntityData().get(CWI_WORLDGEN_GENERATION) >= 2;
        var current = modern ? RuntimeCurrentSampler.sample(boat)
                : net.quiwh.cwiworldgen.hydrology.current.CurrentColumn.EMPTY;
        if (modern) {
            var horizontal = CurrentMotion.push(motion.x, motion.z, current, boat.level().isClientSide
                    ? RiverCurrents.clientPolicy().boatStrength() : CurrentConfig.BOAT_STRENGTH.get());
            motion = new net.minecraft.world.phys.Vec3(horizontal.x(), motion.y, horizontal.z());
            if (boat.level().isClientSide ? RiverCurrents.clientPolicy().boatSurface() : CurrentConfig.BOAT_SURFACE.get()) {
                double target = BoatCurrentSurface.target(boat);
                motion = new net.minecraft.world.phys.Vec3(motion.x,
                        CurrentMotion.surfaceVelocity(boat.getY(), motion.y, target), motion.z);
            }
        }
        if (!modern && boat.getEntityData().get(CWI_WORLDGEN_RIVER)
                && (status == Boat.Status.UNDER_WATER || status == Boat.Status.UNDER_FLOWING_WATER)) {
            motion = new net.minecraft.world.phys.Vec3(motion.x,
                    boat.getControllingPassenger() == null ? .2 : .3, motion.z);
        }
        boat.setDeltaMovement(motion);
        return motion;
    }
}
