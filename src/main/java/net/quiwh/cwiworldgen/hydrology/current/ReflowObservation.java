package net.quiwh.cwiworldgen.hydrology.current;

/** Observed dry water and an unobserved column have different replacement semantics. */
public final class ReflowObservation {
    private ReflowObservation() {}
    public static void preserveUnobserved(long[][] next,CurrentChunk previous,boolean[] observed) {
        if(next.length!=2 || observed.length!=256)throw new IllegalArgumentException("Invalid reflow observation mask");
        if(previous==null)return;
        if(previous.layerCount()>2)throw new IllegalArgumentException("Invalid inferred layer count");
        for(int i=0;i<256;i++)if(!observed[i])for(int layer=0;layer<previous.layerCount();layer++)
            next[layer][i]=previous.packed(layer,i);
    }
}
