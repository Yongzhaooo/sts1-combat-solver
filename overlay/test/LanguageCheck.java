package sts1solver;

public class LanguageCheck {
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        I18n.setLanguage("zh");
        require(I18n.t("执行一步").equals("执行一步"), "Chinese default");
        I18n.setLanguage("en");
        require(I18n.t("执行一步").equals("Step"), "English controls");
        require(I18n.t("还没有红钥匙！这是最后一个火堆，请选择「回忆」。").contains("Recall"), "Key warning");
        require(I18n.backend("等效 unknown: 不用药 / 消耗小精灵").equals("等效 unknown: No potions / Uses Fairy"), "Backend display fragments");
        require(I18n.backend("play 1 0; no-potion; Flame Potion").equals("play 1 0; no-potion; Flame Potion"), "Commands and English game names unchanged");
        require(I18n.backend("使用 火焰药水 / 金刚杵").equals("Use  火焰药水 / 金刚杵"), "Chinese game names are not partially translated");
        require(I18n.backend("本轮搜索未找到使用路线").equals("No potion-use route found"), "route search reason");
        require(I18n.backend("药水不改变结果，已并入不用药路线").equals("Potion does not change the result; merged into the no-potion route"), "merged potion route reason");
        require(I18n.t("本轮无可选路线").equals("No selectable route this search"), "route availability is not bottle usability");
        require(I18n.backend("Unexpected solver error: original details").equals("Unexpected solver error: original details"), "unknown route error remains intact");
        String longError = "Failed to validate potion route at turn 12: " + String.join(" ", java.util.Collections.nCopies(30, "original-error-detail"));
        java.util.List<String> reasonLines = PanelSize.wrap(I18n.backend(longError), text -> text.length(), 30);
        require(reasonLines.size() > 6 && String.join("", reasonLines).replace(" ", "").equals(longError.replace(" ", "")), "long reason is scrollable without dropping text");
        for (String line : reasonLines) require(line.length() <= 30, "reason fits the list row");
        require(I18n.t("Unknown text").equals("Unknown text"), "Unknown fallback");
        I18n.setLanguage("invalid");
        require(I18n.language().equals("zh"), "Invalid config falls back to Chinese");
        System.out.println("PASS: Chinese / English, warnings, backend display and fallback");
    }
}
