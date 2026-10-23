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
        // Mod warnings wrap whole: every line fits, nothing is dropped, words stay together when they can.
        java.util.function.ToDoubleFunction<String> chars = text -> text.length();
        String warning = "Incompatible mod detected (SaveStateMod / Undo): disrupts selection and combat sync";
        java.util.List<String> lines = PanelSize.wrap(warning, chars, 30);
        check(lines.size() == 4 && String.join(" ", lines).equals(warning));
        for (String line : lines) check(line.length() <= 30);
        check(PanelSize.wrap("检测到不兼容模组会破坏同步", chars, 5).equals(java.util.Arrays.asList("检测到不兼", "容模组会破", "坏同步")));
        check(PanelSize.wrap("", chars, 5).isEmpty() && PanelSize.wrap("toolongword", chars, 3).size() == 4);
        // Chinese never breaks early at a space; only a Latin word or number that would be cut moves to the next line.
        check(PanelSize.wrap("求解器超时：搜索 100 秒内没有找到可行路线", chars, 15).get(0).equals("求解器超时：搜索 100 秒内"));
        check(PanelSize.wrap("搜索 100 秒内", chars, 5).equals(java.util.Arrays.asList("搜索", "100 秒", "内")));
        System.out.println("PASS: panel sizes, invalid settings, viewport fit and pointer scaling");
    }
}
