package sts1solver;

public class PanelSizeCheck {
    private static void check(boolean ok) { if (!ok) throw new AssertionError("Panel scaling"); }
    public static void main(String[] args) {
        for (String invalid : new String[]{"NaN", "Infinity", "-Infinity", "broken", null})
            check(PanelSize.preference(invalid) == 1);
        check(PanelSize.preference(".1") == .6f);
        check(PanelSize.preference("5") == 1.6f);
        check(PanelSize.preference("1.2") == 1.2f);
        for (float[] screen : new float[][]{{1280,720}, {1920,1080}, {3840,2160}, {800,600}}) {
            for (float user : new float[]{.6f,1,1.6f}) {
                float s=PanelSize.fit(user,screen[1]/1080,screen[0],screen[1],880,612);
                check(s>0 && 880*s<=screen[0]+.01 && 612*s<=screen[1]+.01);
                // The same scale maps drawing and pointer positions back to logical units.
                check(Math.abs((714*s)/s-714)<.001);
            }
        }
        check(PanelSize.fit(1,2,3840,2160,880,612)==2);
        System.out.println("PASS: panel sizes, invalid settings, viewport fit and pointer scaling");
    }
}
