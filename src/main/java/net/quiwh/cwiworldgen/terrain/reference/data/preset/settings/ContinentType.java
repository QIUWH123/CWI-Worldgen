package net.quiwh.cwiworldgen.terrain.reference.data.preset.settings;

import com.mojang.serialization.Codec;

import net.minecraft.util.StringRepresentable;
import net.quiwh.cwiworldgen.terrain.reference.GeneratorContext;
import net.quiwh.cwiworldgen.terrain.reference.cell.continent.Continent;
import net.quiwh.cwiworldgen.terrain.reference.cell.continent.advanced.AdvancedContinentGenerator;
import net.quiwh.cwiworldgen.terrain.reference.cell.continent.simple.MultiContinentGenerator;
import net.quiwh.cwiworldgen.terrain.reference.cell.continent.simple.SingleContinentGenerator;
import net.quiwh.cwiworldgen.terrain.reference.cell.continent.uplift.UpliftContinentGenerator;
import net.quiwh.cwiworldgen.terrain.reference.util.Seed;

public enum ContinentType implements StringRepresentable {
    MULTI {
        
    	@Override
        public MultiContinentGenerator create(Seed seed, GeneratorContext context) {
            return new MultiContinentGenerator(seed, context);
        }
    }, 
    SINGLE {
        
    	@Override
        public SingleContinentGenerator create(Seed seed, GeneratorContext context) {
            return new SingleContinentGenerator(seed, context);
        }
    }, 
    MULTI_IMPROVED {
        
    	@Override
        public AdvancedContinentGenerator create(Seed seed, GeneratorContext context) {
            return new AdvancedContinentGenerator(seed, context);
        }
    },
    UPLIFT {

        @Override
        public UpliftContinentGenerator create(Seed seed, GeneratorContext context) {
            return new UpliftContinentGenerator(seed, context);
        }
    };
	
	public static final Codec<ContinentType> CODEC = StringRepresentable.fromEnum(ContinentType::values);
    
    public abstract Continent create(Seed seed, GeneratorContext context);

    @Override
	public String getSerializedName() {
		return this.name();
	}
}
