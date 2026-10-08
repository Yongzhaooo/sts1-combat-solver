package sts1solver;

/** Panel-only sizing: never changes the game's resolution or input coordinates. */
final class PanelSize {
    static float preference(String value) {
        try {
            float scale = Float.parseFloat(value);
            return Float.isNaN(scale) || Float.isInfinite(scale) ? 1 : Math.max(.6f, Math.min(1.6f, scale));
        } catch (RuntimeException invalid) { return 1; }
    }

    static float fit(float preference, float gameScale, float width, float height, float panelWidth, float panelHeight) {
        return Math.min(gameScale * preference, Math.min(width / panelWidth, height / panelHeight));
    }
}
