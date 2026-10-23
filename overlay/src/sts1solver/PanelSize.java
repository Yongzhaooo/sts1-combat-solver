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

    /** Greedy line breaking: after the last space that fits, or between characters when a line has none. */
    static java.util.List<String> wrap(String text, java.util.function.ToDoubleFunction<String> width, float max) {
        java.util.List<String> lines=new java.util.ArrayList<>();
        String rest=text.trim();
        while(!rest.isEmpty()) {
            int end=1;
            while(end<rest.length() && width.applyAsDouble(rest.substring(0,end+1))<=max) end++;
            if(end<rest.length()) { int space=rest.lastIndexOf(' ',end); if(space>0) end=space; }
            lines.add(rest.substring(0,end).trim());
            rest=rest.substring(end).trim();
        }
        return lines;
    }
}
