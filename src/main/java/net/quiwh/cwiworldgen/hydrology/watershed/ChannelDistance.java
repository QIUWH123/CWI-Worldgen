package net.quiwh.cwiworldgen.hydrology.watershed;

/** Signed distance to the finite channel segment, including round endpoint caps. */
public final class ChannelDistance {
    private ChannelDistance() {}
    public static double signed(double x, double z, double ax, double az, double bx, double bz) {
        double dx=bx-ax, dz=bz-az, length2=dx*dx+dz*dz;
        if (length2 < 1E-12) return Math.hypot(x-ax,z-az);
        double t=Math.clamp(((x-ax)*dx+(z-az)*dz)/length2,0,1);
        double px=ax+dx*t, pz=az+dz*t;
        return Math.copySign(Math.hypot(x-px,z-pz),dx*(z-az)-dz*(x-ax));
    }
}
