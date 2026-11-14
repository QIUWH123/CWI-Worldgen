package net.quiwh.cwiworldgen.hydrology.watershed;

/** Frozen accepted-route controls, interpolated before local bank shape and material exposure. */
public record RiverBankContext(double relief, double grade, double cohesion, double curvature,
                               double leftControl, double rightControl, double gorgeWeight) {
    public RiverBankContext {
        if (!Double.isFinite(relief + grade + cohesion + curvature + leftControl + rightControl + gorgeWeight))
            throw new IllegalArgumentException("Non-finite bank context");
        relief = Math.max(0, relief);
        grade = Math.max(0, grade);
        cohesion = Math.clamp(cohesion, 0, 1);
        curvature = Math.clamp(curvature, -1, 1);
        leftControl = Math.clamp(leftControl, 0, 1);
        rightControl = Math.clamp(rightControl, 0, 1);
        gorgeWeight = Math.clamp(gorgeWeight, 0, 1);
    }

    public double sideControl(double signedDistance) { return signedDistance < 0 ? rightControl : leftControl; }

    public double rockiness() {
        return Math.clamp(.45 * smooth(10, 64, relief) + .30 * smooth(.015, .14, grade)
                + .25 * smooth(.3, .85, cohesion), 0, 1);
    }

    public RiverBankContext withHydraulics(double localCohesion, double bend) {
        return new RiverBankContext(relief, grade, localCohesion, bend, leftControl, rightControl, gorgeWeight);
    }

    public static RiverBankContext interpolate(RiverBankContext a, RiverBankContext b, double alpha) {
        double t = Math.clamp(alpha, 0, 1);
        return new RiverBankContext(lerp(a.relief,b.relief,t), lerp(a.grade,b.grade,t),
                lerp(a.cohesion,b.cohesion,t), lerp(a.curvature,b.curvature,t),
                lerp(a.leftControl,b.leftControl,t), lerp(a.rightControl,b.rightControl,t),
                lerp(a.gorgeWeight,b.gorgeWeight,t));
    }

    private static double lerp(double a,double b,double t) { return a+(b-a)*t; }
    private static double smooth(double lo,double hi,double value) {
        double t=Math.clamp((value-lo)/(hi-lo),0,1); return t*t*(3-2*t);
    }
}
