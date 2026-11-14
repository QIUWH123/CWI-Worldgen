package net.quiwh.cwiworldgen.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.quiwh.cwiworldgen.config.CurrentConfig;
import net.quiwh.cwiworldgen.hydrology.current.CurrentPresentation.Kind;
import net.quiwh.cwiworldgen.hydrology.current.RiverCurrents;

/** Vanilla water ambience shared by gentle streams and waterfalls; no additional sound assets. */
public final class RiverWaterSound extends AbstractTickableSoundInstance {
    private final ClientLevel level;
    private final BlockPos crest;
    private final Kind kind;
    public RiverWaterSound(ClientLevel level, BlockPos crest, Kind kind) {
        super(SoundEvents.WATER_AMBIENT, SoundSource.AMBIENT, RandomSource.create());
        this.level = level;
        this.crest = crest.immutable();
        this.kind = kind;
        this.x = crest.getX() + .5;
        this.y = crest.getY() + .5;
        this.z = crest.getZ() + .5;
        this.volume = .01F;
        this.pitch = kind == Kind.STREAM ? 1.05F : .85F;
        this.looping = true;
        this.delay = 0;
    }
    public BlockPos crest() { return crest; }
    public Kind kind() { return kind; }
    public void finish() { stop(); }
    @Override public void tick() {
        var minecraft = Minecraft.getInstance();
        if (minecraft.isPaused()) return;
        int radius = kind == Kind.STREAM ? 20 : 32;
        boolean valid = minecraft.level == level && minecraft.player != null && ClientRiverEffects.audioEnabled(kind)
                && RiverCurrents.clientPolicy().enabled() && ClientRiverEffects.wetSite(level, crest, kind)
                && minecraft.player.distanceToSqr(x, y, z) <= radius * radius;
        float target = valid ? (kind == Kind.STREAM ? CurrentConfig.STREAM_VOLUME : CurrentConfig.VOLUME).get().floatValue() : 0;
        volume += Math.max(-.015F, Math.min(.015F, target - volume));
        if (!valid && volume <= .001F) stop();
    }
}
