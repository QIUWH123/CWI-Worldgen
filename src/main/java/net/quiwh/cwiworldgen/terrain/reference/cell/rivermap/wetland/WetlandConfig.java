package net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.wetland;

import net.quiwh.cwiworldgen.terrain.reference.data.preset.settings.RiverSettings;
import net.quiwh.cwiworldgen.terrain.reference.noise.NoiseUtil;
import net.quiwh.cwiworldgen.terrain.reference.util.Variance;

public class WetlandConfig {
    public int skipSize;
    public Variance length;
    public Variance width;
    
    public WetlandConfig(RiverSettings.Wetland settings) {
        this.skipSize = Math.max(1, NoiseUtil.round((1.0F - settings.chance) * 10.0F));
        this.length = Variance.of(settings.sizeMin, settings.sizeMax);
        this.width = Variance.of(50.0F, 150.0F);
    }
}
