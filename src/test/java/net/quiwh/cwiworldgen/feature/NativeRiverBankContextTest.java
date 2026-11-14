package net.quiwh.cwiworldgen.feature;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.hydrology.DirectedReach;
import net.quiwh.cwiworldgen.hydrology.watershed.NaturalRiverNetwork;
import net.quiwh.cwiworldgen.hydrology.watershed.WatershedPlan;
import net.quiwh.cwiworldgen.hydrology.watershed.WatershedSample;
import net.quiwh.cwiworldgen.terrain.ReferenceWeathering;
import net.quiwh.cwiworldgen.terrain.RiverBankProfiles;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;
import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;

/** Real accepted routes and the public sample/material bridge; no Minecraft chunk generation or writes. */
@GameTestHolder("cwi_worldgen")
@PrefixGameTestTemplate(false)
public final class NativeRiverBankContextTest {
    private static final long SEED=42L;
    private static final int MAX_REACHES=48;

    @GameTest(template="empty",templateNamespace="cwi_worldgen",timeoutTicks=12000)
    public static void acceptedNativeRiverContextReachesMaterialsOnlyInProfile12(GameTestHelper helper){
        unchangedNetworkGeometry(helper);
        int observed=0;
        for(int profile:new int[]{11,12})try(var terrain=new ReferenceTerrain(SEED,profile)){
            var topology=terrain.hydrologySnapshot(0,0);
            var fixtures=acceptedSamples(terrain,topology.reaches());
            helper.assertTrue(!fixtures.isEmpty(),"No accepted native wet river in bounded profile"+profile+" fixture");
            boolean materialPositive=false;
            for(var fixture:fixtures){
                var sample=fixture.sample();
                var points=fixture.reach().points();
                var first=points.getFirst();var last=points.getLast();
                int ax=(int)Math.round(first.x()),az=(int)Math.round(first.z());
                int bx=(int)Math.round(last.x()),bz=(int)Math.round(last.z());
                var start=terrain.watershedSample(ax,az);var end=terrain.watershedSample(bx,bz);
                helper.assertTrue((sample.bankContext()!=null)==(profile==12),
                        "Accepted native river context has the wrong historical gate for profile"+profile);
                if(profile==12){
                    var context=sample.bankContext();
                    helper.assertTrue(Double.isFinite(context.relief()+context.grade()+context.cohesion()
                                    +context.curvature()+context.leftControl()+context.rightControl()+context.gorgeWeight()),
                            "Native interpolated bank context is non-finite");
                    materialPositive|=materialConsumesContext(helper,terrain,fixture);
                }
                // Both end coordinates and every sample component, including water, are immutable.
                helper.assertTrue(sample.equals(terrain.watershedSample(fixture.x(),fixture.z()))
                                &&start.equals(terrain.watershedSample(ax,az))&&end.equals(terrain.watershedSample(bx,bz)),
                        "Context/material queries changed accepted route or endpoint water samples");
                observed++;
            }
            helper.assertTrue(topology.equals(terrain.hydrologySnapshot(0,0)),
                    "Context/material queries changed accepted points, connections or route endpoints");
            if(profile==12)helper.assertTrue(materialPositive,
                    "No genuine native bank context reached a positive material decision in bounded fixture");
            System.out.println("CWI_NATIVE_BANK_CONTEXT=seed:"+SEED+",profile:"+profile+",accepted_samples:"
                    +fixtures.size()+",context_present:"+(profile==12)+",material_positive:"+materialPositive
                    +",topology_and_endpoint_water_preserved:true,chunk_writes:0");
        }
        helper.assertTrue(observed>=2,"Both current and historical native contexts were not exercised");
        helper.succeed();
    }

    /** Hold terrain/plan inputs constant to isolate context construction from changed blend inputs. */
    private static void unchangedNetworkGeometry(GameTestHelper helper){
        var plan=new WatershedPlan(0,0,17,80,62,(x,z)->
                new WatershedPlan.Ground(x==0?60:84,x==0,true),3,8,11);
        var historical=new NaturalRiverNetwork(plan,plan.edges(),SEED,(x,z)->84,62,11);
        var current=new NaturalRiverNetwork(plan,plan.edges(),SEED,(x,z)->84,62,12);
        int wet=0;
        for(var edge:plan.edges()){
            int from=edge.from();
            helper.assertTrue(historical.wet(from)==current.wet(from)
                            &&historical.points(from).equals(current.points(from)),
                    "Bank context construction changed route acceptance, points or water endpoints");
            for(double progress:new double[]{0,.5,1})helper.assertTrue(
                    Double.doubleToRawLongBits(historical.width(from,progress))==Double.doubleToRawLongBits(current.width(from,progress))
                            &&Double.doubleToRawLongBits(historical.depthScale(from,progress))==Double.doubleToRawLongBits(current.depthScale(from,progress)),
                    "Bank context construction changed accepted width or depth controls");
            if(!current.wet(from))continue;
            wet++;
            var first=current.bankContext(from,1,0);var last=current.bankContext(from,1,1);
            var middle=current.bankContext(from,1,.37);
            helper.assertTrue(historical.bankContext(from,1,.37)==null&&first!=null&&last!=null&&middle!=null,
                    "Accepted-route context controls have the wrong profile gate");
            helper.assertTrue(Math.abs(middle.relief()-(first.relief()+(last.relief()-first.relief())*.37))<1E-10
                            &&Math.abs(middle.leftControl()-(first.leftControl()+(last.leftControl()-first.leftControl())*.37))<1E-10
                            &&Math.abs(middle.rightControl()-(first.rightControl()+(last.rightControl()-first.rightControl())*.37))<1E-10,
                    "Native network did not interpolate adjacent frozen bank controls");
        }
        helper.assertTrue(wet>0,"Controlled context-isolation fixture has no accepted wet route");
        System.out.println("CWI_BANK_CONTEXT_ROUTE_CONTROLS=accepted_wet:"+wet
                +",profile11_context:null,profile12_interpolated:true,geometry_water_width_depth_exact:true");
    }

    private static List<Fixture> acceptedSamples(ReferenceTerrain terrain,List<DirectedReach> reaches){
        var fixtures=new ArrayList<Fixture>();
        for(var reach:reaches.stream().sorted(Comparator.comparingLong(DirectedReach::id)).limit(MAX_REACHES).toList()){
            var points=reach.points();if(points.size()<2)continue;
            for(double fraction:new double[]{.25,.5,.75}){
                double along=(points.size()-1)*fraction;
                int index=Math.min(points.size()-2,(int)Math.floor(along));double t=along-index;
                var a=points.get(index);var b=points.get(index+1);
                int x=(int)Math.round(a.x()+(b.x()-a.x())*t),z=(int)Math.round(a.z()+(b.z()-a.z())*t);
                var sample=terrain.watershedSample(x,z);
                if(sample.valid()&&sample.wet()&&!sample.dry()&&!sample.basin()&&!sample.island()
                        &&sample.reachId()==reach.id()&&Math.abs(sample.distance())<=sample.width()
                        // Auxiliary lake connectors need not own projected main-route controls.
                        &&(terrain.generationVersion()<12||sample.bankContext()!=null))
                    fixtures.add(new Fixture(reach,x,z,sample));
            }
        }
        return List.copyOf(fixtures);
    }

    /**
     * Keep the actual accepted river's context. Controlled exposed-bank slope/lateral inputs
     * exercise the material consumer without generating/editing terrain or requiring rare cover.
     */
    private static boolean materialConsumesContext(GameTestHelper helper,ReferenceTerrain terrain,Fixture fixture){
        var nativeSample=fixture.sample();
        for(int side:new int[]{-1,1})for(float slope:new float[]{.08F,.30F}){
            var cell=new Cell();cell.gradient=slope;
            var bank=bankPolicySample(nativeSample,side*nativeSample.width()*1.35,true);
            int surface=(int)Math.ceil(bank.waterY())+2;
            // Weathering uses the real production field; only its seed varies in this policy fixture.
            for(long weatherSeed=0;weatherSeed<32;weatherSeed++){
                var expected=RiverBankProfiles.choose(true,false,bank.distance(),bank.width(),bank.bankContext(),
                        slope,surface-bank.waterY(),ReferenceWeathering.sample(weatherSeed,fixture.x(),fixture.z()));
                var actual=ReferenceSurfaceExposureFeature.bankAction(terrain,bank,cell,
                        fixture.x(),fixture.z(),surface,weatherSeed);
                helper.assertTrue(actual==expected,"Material feature diverged from the actual river's shared bank context");
                if(actual!=RiverBankProfiles.Action.NONE){
                    var withoutContext=bankPolicySample(bank,bank.distance(),false);
                    helper.assertTrue(ReferenceSurfaceExposureFeature.bankAction(terrain,withoutContext,cell,
                                    fixture.x(),fixture.z(),surface,weatherSeed)==RiverBankProfiles.Action.NONE,
                            "Material feature silently substituted legacy bend sampling when native context was absent");
                    return true;
                }
            }
        }
        return false;
    }

    private static WatershedSample bankPolicySample(WatershedSample sample,double distance,boolean keepContext){
        return new WatershedSample(sample.valid(),sample.reachId(),distance,sample.width(),sample.tangentX(),sample.tangentZ(),
                sample.wet(),sample.dry(),sample.basin(),sample.bedY(),sample.waterY(),sample.originalGroundY(),
                sample.island(),sample.drop(),sample.progress(),sample.shoal(),sample.dryCrest(),sample.plungePool(),
                sample.gorge(),keepContext?sample.bankContext():null);
    }
    private record Fixture(DirectedReach reach,int x,int z,WatershedSample sample){}
}
