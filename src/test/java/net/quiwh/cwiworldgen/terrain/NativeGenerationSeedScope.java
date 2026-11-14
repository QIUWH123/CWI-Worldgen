package net.quiwh.cwiworldgen.terrain;

import java.lang.reflect.Field;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.PrimaryLevelData;

/** Test-only seed substitution for real ChunkStatus pipelines in a fresh disposable server per seed. */
public final class NativeGenerationSeedScope implements AutoCloseable {
    private final ServerLevel level;
    private final Field options,state,structures,managerOptions;
    private final Object oldOptions,oldState,oldStructures,oldManagerOptions;
    private final RandomState replacement;

    public NativeGenerationSeedScope(ServerLevel level,NoiseBasedChunkGenerator generator,long seed) throws Exception {
        if(!(level.getServer() instanceof GameTestServer))
            throw new IllegalArgumentException("Seed replacement is restricted to a disposable GameTestServer");
        this.level=level;
        options=field(PrimaryLevelData.class,"worldOptions");state=field(ChunkMap.class,"randomState");
        structures=field(ChunkMap.class,"chunkGeneratorState");managerOptions=field(StructureManager.class,"worldOptions");
        Object data=level.getServer().getWorldData(),map=level.getChunkSource().chunkMap;
        oldOptions=options.get(data);oldState=state.get(map);oldStructures=structures.get(map);
        oldManagerOptions=managerOptions.get(level.structureManager());
        replacement=RandomState.create(generator.generatorSettings().value(),level.registryAccess().lookupOrThrow(Registries.NOISE),seed);
        var newStructures=generator.createState(level.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET),replacement,seed);
        var worldOptions=new WorldOptions(seed,true,false);
        try {
            options.set(data,worldOptions);managerOptions.set(level.structureManager(),worldOptions);
            state.set(map,replacement);structures.set(map,newStructures);
        } catch(Exception failure) {
            restore();closeTerrain();throw failure;
        }
        if(level.getSeed()!=seed || level.getChunkSource().randomState()!=replacement) {
            restore();closeTerrain();throw new AssertionError("Native pipeline seed replacement was not applied");
        }
    }
    private static Field field(Class<?> owner,String name) throws Exception {
        var field=owner.getDeclaredField(name);field.setAccessible(true);return field;
    }
    private void restore() throws Exception {
        Object map=level.getChunkSource().chunkMap;
        structures.set(map,oldStructures);state.set(map,oldState);managerOptions.set(level.structureManager(),oldManagerOptions);
        options.set(level.getServer().getWorldData(),oldOptions);
    }
    private void closeTerrain() {
        var terrain=((TerrainRandomState)(Object)replacement).cwiWorldgen$terrain();if(terrain!=null)terrain.close();
    }
    @Override public void close() throws Exception { try { restore(); } finally { closeTerrain(); } }
}
