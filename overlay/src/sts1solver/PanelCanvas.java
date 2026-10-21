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

/**
 * Where the panel sits on screen, how large it is drawn, its palette and font, and the drawing primitives.
 * Drawing coordinates are panel units measured from the panel's top-left corner.
 */
final class PanelCanvas {
    static final float PANEL_WIDTH = 640, PANEL_HEIGHT = 346;
    static final Color BORDER = new Color(.20f,.26f,.34f,1);
    static final Color BACKGROUND = new Color(.055f,.070f,.105f,.96f);
    static final Color SURFACE = new Color(.10f,.13f,.19f,1);
    static final Color ACCENT = new Color(.38f,.83f,.72f,1);
    static final Color MUTED = new Color(.62f,.69f,.78f,1);
    static final Color DANGER = new Color(1,.48f,.44f,1);

    float x = -1, y = -1;
    float uiScale = 1;
    boolean dragging;
    boolean hovered;  // pointer over the panel, which is drawn after the game cursor
    private float dx, dy, fontScale;
    private com.badlogic.gdx.graphics.g2d.NinePatch rounded;
    private BitmapFont uiFont;
    private FreeTypeFontGenerator fontGenerator;
    private final GlyphLayout measure = new GlyphLayout();

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

    void line(SpriteBatch sb, float left, float top, float width) {
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
