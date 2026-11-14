package net.quiwh.cwiworldgen.terrain;

import net.quiwh.cwiworldgen.hydrology.watershed.RiverBankContext;

/** Selective bank weathering from the directed channel geometry; never a uniform border. */
public final class RiverBankProfiles {
    private RiverBankProfiles() {}

    public enum Action { NONE, EXPOSE_HOST, DEPLETED_SEDIMENT }

    /** Profile-twelve finishing uses the same frozen controls as the exposed bank geometry. */
    public static Action choose(boolean wet, boolean dry, double signedDistance, double width,
            RiverBankContext context, double slope, double heightAboveWater, double weathering) {
        if (dry) return choose(wet,true,signedDistance,width,context==null?0:context.curvature(),slope,heightAboveWater,weathering);
        if (context == null) return Action.NONE;
        if (!wet || !Double.isFinite(signedDistance+width+slope+heightAboveWater+weathering)
                || width<=0 || heightAboveWater<0 || heightAboveWater>9) return Action.NONE;
        double lateral=Math.abs(signedDistance)/width;
        if(lateral<1.02 || lateral>1.85) return Action.NONE;
        double rockiness=context.rockiness();
        double bend=context.curvature()*Math.signum(signedDistance);
        double side=context.sideControl(signedDistance);
        double exposure=.30*rockiness+.18*context.gorgeWeight()+.12*side+Math.max(0,-bend)*.25;
        if(slope>.13 && weathering+exposure>.94 && (bend<-.025 || rockiness>.65))
            return Action.EXPOSE_HOST;
        if(bend>.025 && rockiness<.55 && slope<.18 && heightAboveWater<=4
                && weathering>.54+.10*side) return Action.DEPLETED_SEDIMENT;
        return Action.NONE;
    }

    public static Action choose(boolean wet, boolean dry, double signedDistance, double width,
            double curvature, double slope, double heightAboveWater, double weathering) {
        if (!Double.isFinite(signedDistance + width + curvature + slope + heightAboveWater + weathering)
                || width <= 0 || (!wet && !dry) || heightAboveWater < 0 || heightAboveWater > 9) return Action.NONE;
        double lateral = Math.abs(signedDistance) / width;
        if (dry) return lateral < .8 && slope > .16 && weathering > .56 ? Action.EXPOSE_HOST : Action.NONE;
        if (lateral < 1.02 || lateral > 1.85 || Math.abs(curvature) < .025) return Action.NONE;
        double bend = curvature * Math.signum(signedDistance);
        if (bend < -.025 && slope > .14 && weathering > .45) return Action.EXPOSE_HOST;
        if (bend > .025 && slope < .18 && heightAboveWater <= 4 && weathering > .52)
            return Action.DEPLETED_SEDIMENT;
        return Action.NONE;
    }
}
