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
        require(I18n.t("Unknown text").equals("Unknown text"), "Unknown fallback");
        I18n.setLanguage("invalid");
        require(I18n.language().equals("zh"), "Invalid config falls back to Chinese");
        System.out.println("PASS: Chinese / English, warnings, backend display and fallback");
    }
}
