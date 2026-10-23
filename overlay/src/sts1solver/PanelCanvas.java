package sts1solver;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import com.megacrit.cardcrawl.helpers.input.InputHelper;

/**
 * Where the panel sits on screen, how large it is drawn, its palette and font, and the drawing primitives.
 * Drawing coordinates are panel units measured from the panel's top-left corner.
 */
final class PanelCanvas {
    // The largest panel (Settings with mod warnings) must fit the screen at the chosen scale.
    static final float PANEL_WIDTH = 420, PANEL_HEIGHT = 480;
    // shadcn/ui-style neutral palette; the teal stays the mod's primary colour.
    static final Color BORDER = new Color(.165f,.165f,.188f,1);
    static final Color BACKGROUND = new Color(.047f,.047f,.059f,.96f);
    static final Color SURFACE = new Color(.094f,.094f,.106f,1);
    static final Color SECONDARY = new Color(.149f,.149f,.169f,1);
    static final Color ACCENT = new Color(.37f,.83f,.72f,1);
    static final Color ACCENT_SOFT = new Color(.09f,.23f,.20f,1);
    static final Color MUTED = new Color(.63f,.63f,.67f,1);
    static final Color DANGER = new Color(.97f,.44f,.44f,1);
    static final Color DANGER_SOFT = new Color(.23f,.09f,.10f,1);

    float x = -1, y = -1;
    float uiScale = 1;
    boolean dragging;
    boolean hovered;  // pointer over the panel, which is drawn after the game cursor
    boolean dry;      // measuring pass: drawing and click regions are skipped, sizes are still computed
    private final java.util.List<float[]> hitBoxes = new java.util.ArrayList<>();
    private final java.util.List<Runnable> hitActions = new java.util.ArrayList<>();
    private float dx, dy, fontScale;
    private com.badlogic.gdx.graphics.g2d.NinePatch rounded;
    private BitmapFont uiFont;
    private FreeTypeFontGenerator fontGenerator;
    private final GlyphLayout measure = new GlyphLayout();
    private final Color lit = new Color();

    float scale() {
        return PanelSize.fit(uiScale, Settings.scale, Settings.WIDTH, Settings.HEIGHT, PANEL_WIDTH, PANEL_HEIGHT);
    }

    /** Places the panel on first use and keeps a panel of this size fully on screen. */
    void layout(float width, float height) {
        float s = scale();
        if (x < 0) { x = 20*s; y = Settings.HEIGHT - 180*s; }
        x = Math.max(0, Math.min(x, Settings.WIDTH - width*s));
        y = Math.max(height*s, Math.min(y, Settings.HEIGHT));
    }

    static boolean inside(float mx, float my, float rx, float ry, float w, float h) {
        return mx >= rx && mx <= rx+w && my >= ry && my <= ry+h;
    }

    void startDrag(float mx, float my) { dragging=true; dx=mx-x; dy=my-y; }

    /** Follows the pointer while the left button is held; the caller re-runs layout. */
    void drag(float mx, float my) {
        if (!dragging) return;
        if (Gdx.input.isButtonPressed(Input.Buttons.LEFT)) { x=mx-dx; y=my-dy; }
        else dragging=false;
    }

    void box(SpriteBatch sb, float left, float top, float width, float height, Color color) {
        if (dry) return;
        if (rounded == null) {
            com.badlogic.gdx.graphics.Pixmap pixmap = new com.badlogic.gdx.graphics.Pixmap(24,24,com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888);
            pixmap.setColor(Color.WHITE);
            pixmap.fillRectangle(6,0,12,24); pixmap.fillRectangle(0,6,24,12);
            for (int cx : new int[]{6,17}) for (int cy : new int[]{6,17}) pixmap.fillCircle(cx,cy,6);
            com.badlogic.gdx.graphics.Texture texture = new com.badlogic.gdx.graphics.Texture(pixmap);
            texture.setFilter(com.badlogic.gdx.graphics.Texture.TextureFilter.Linear,com.badlogic.gdx.graphics.Texture.TextureFilter.Linear);
            pixmap.dispose();
            rounded = new com.badlogic.gdx.graphics.g2d.NinePatch(texture,8,8,8,8);
        }
        float s=scale();
        sb.setColor(Color.WHITE);
        rounded.setColor(color);
        rounded.draw(sb,x+left*s,y-(top+height)*s,width*s,height*s);
    }

    /** A flat rectangle, for bars too thin for the rounded corners. */
    void bar(SpriteBatch sb, float left, float top, float width, float height, Color color) {
        if (dry || width <= 0) return;
        sb.setColor(color);
        sb.draw(ImageMaster.WHITE_SQUARE_IMG,x+left*scale(),y-(top+height)*scale(),width*scale(),height*scale());
        sb.setColor(Color.WHITE);
    }

    /** The pointer is over this panel rectangle. */
    boolean pointing(float left, float top, float width, float height) {
        float s=scale();
        return inside(InputHelper.mX, InputHelper.mY, x+left*s, y-(top+height)*s, width*s, height*s);
    }

    /** A clickable box: lighter while the pointer is over it. */
    void button(SpriteBatch sb, float left, float top, float width, float height, Color color) {
        box(sb,left,top,width,height,pointing(left,top,width,height)?lit.set(color).lerp(Color.WHITE,.12f):color);
    }

    void line(SpriteBatch sb, float left, float top, float width) {
        if (dry) return;
        sb.setColor(BORDER);
        sb.draw(ImageMaster.WHITE_SQUARE_IMG,x+left*scale(),y-top*scale(),width*scale(),scale());
        sb.setColor(Color.WHITE);
    }

    BitmapFont font() {
        if (uiFont == null) {
            fontGenerator = new FreeTypeFontGenerator(Gdx.files.internal("font/zhs/NotoSansMonoCJKsc-Regular.otf"));
            FreeTypeFontGenerator.FreeTypeFontParameter parameter = new FreeTypeFontGenerator.FreeTypeFontParameter();
            parameter.size = Math.max(12, Math.round(19*Settings.scale));
            fontScale = parameter.size / 19f;
            parameter.incremental = true;
            parameter.minFilter = com.badlogic.gdx.graphics.Texture.TextureFilter.Linear;
            parameter.magFilter = com.badlogic.gdx.graphics.Texture.TextureFilter.Linear;
            uiFont = fontGenerator.generateFont(parameter);
        }
        return uiFont;
    }

    /** One line of translated text, cut with an ellipsis to fit the width. */
    void label(SpriteBatch sb, String value, float left, float top, float width, float size, Color color) {
        if (dry) return;
        BitmapFont font=font();
        float oldX=font.getData().scaleX, oldY=font.getData().scaleY;
        try {
            font.getData().setScale(scale()/fontScale*size);
            String text=I18n.t(value);
            measure.setText(font,text);
            while(text.length()>1 && measure.width>width*scale()) {
                text=text.substring(0,text.length()-2)+"…";
                measure.setText(font,text);
            }
            FontHelper.renderFontLeftTopAligned(sb,font,text,x+left*scale(),y-top*scale(),color);
        } finally { font.getData().setScale(oldX,oldY); }
    }

    /** Width of translated text in panel units at this size. */
    float textWidth(String value, float size) {
        BitmapFont font=font();
        float oldX=font.getData().scaleX, oldY=font.getData().scaleY;
        try {
            font.getData().setScale(scale()/fontScale*size);
            measure.setText(font,I18n.t(value));
            return measure.width/scale();
        } finally { font.getData().setScale(oldX,oldY); }
    }

    /** Click regions are those drawn in the last frame, so drawing and clicking share one geometry. */
    void clearHits() { hitBoxes.clear(); hitActions.clear(); }

    void hit(float left, float top, float width, float height, Runnable action) {
        if (dry || action == null) return;
        hitBoxes.add(new float[]{left, top, width, height});
        hitActions.add(action);
    }

    /** Runs the action under panel point (lx, ly); false when the point is on no control. */
    boolean click(float lx, float ly) {
        for (int i = hitBoxes.size()-1; i >= 0; i--) {
            float[] b = hitBoxes.get(i);
            if (lx >= b[0] && lx < b[0]+b[2] && ly >= b[1] && ly < b[1]+b[3]) { hitActions.get(i).run(); return true; }
        }
        return false;
    }

    /** A labelled button that runs {@code action} on click; a null {@code fill} is a ghost button, filled only under the pointer. */
    void button(SpriteBatch sb, float left, float top, float width, float height, Color fill, String value, float size, Color text, Runnable action) {
        hit(left, top, width, height, action);
        if (dry) return;
        boolean over = action != null && pointing(left, top, width, height);
        if (fill != null || over) box(sb, left, top, width, height, lit.set(fill == null ? SECONDARY : fill).lerp(Color.WHITE, over ? .12f : 0));
        if (value == null || value.isEmpty()) return;
        float w = textWidth(value, size);
        label(sb, value, left+Math.max(6, (width-w)/2), top+(height-19*size)/2-2, width-12, size, text);
    }

    /** A small rounded tag: status or mode. Returns its width. */
    float badge(SpriteBatch sb, String value, float left, float top, float maxWidth, Color fill, Color text) {
        float w = Math.min(maxWidth, textWidth(value, .68f)+16);
        box(sb, left, top, w, 20, fill);
        label(sb, value, left+8, top+2, w-16, .68f, text);
        return w;
    }

    /** Splits text into lines that label() draws whole at this width and size. */
    java.util.List<String> wrap(String value, float width, float size) {
        BitmapFont font=font();
        float oldX=font.getData().scaleX, oldY=font.getData().scaleY;
        try {
            font.getData().setScale(scale()/fontScale*size);
            return PanelSize.wrap(I18n.t(value), text -> { measure.setText(font,text); return measure.width/scale(); }, width);
        } finally { font.getData().setScale(oldX,oldY); }
    }

    /** Shadowed text at screen coordinates, for captions on the map and the deck. */
    void caption(SpriteBatch sb, String value, float left, float baseline, Color color) {
        BitmapFont font=font();
        float oldX=font.getData().scaleX, oldY=font.getData().scaleY;
        try {
            font.getData().setScale(Settings.scale/fontScale*.85f);
            font.setColor(Color.BLACK); font.draw(sb,value,left+2*Settings.scale,baseline-2*Settings.scale);
            font.setColor(color); font.draw(sb,value,left,baseline);
        } finally { font.getData().setScale(oldX,oldY); font.setColor(Color.WHITE); }
    }

    // The game draws its cursor before post-render overlays; repeat it above the panel.
    void cursorOnTop(SpriteBatch sb) {
        if (hovered && !com.megacrit.cardcrawl.core.GameCursor.hidden) com.megacrit.cardcrawl.core.CardCrawlGame.cursor.render(sb);
    }
}
