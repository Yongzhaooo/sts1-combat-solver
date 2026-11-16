package sts1solver;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.ScreenUtils;
import com.esotericsoftware.spine.AnimationState;
import com.esotericsoftware.spine.AnimationStateData;
import com.esotericsoftware.spine.Skeleton;
import com.esotericsoftware.spine.SkeletonData;
import com.esotericsoftware.spine.SkeletonJson;
import com.esotericsoftware.spine.SkeletonMeshRenderer;
import com.esotericsoftware.spine.Slot;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * The top-bar heart: the Corrupt Heart as the game draws it in combat, cropped to its body, with no outline (user, 2026-11-16).
 * It is drawn once, at run time, from the game's own skeleton, so no game art ships in the jar.
 */
final class HeartIcon {
    static final int SIZE = 128;
    private static final String ATLAS = "images/npcs/heart/skeleton.atlas", SKELETON = "images/npcs/heart/skeleton.json";
    // The body in skeleton units (idle pose, first frame). The vessels above and to the right are cut and faded out.
    private static final float CROP_LEFT = -235, CROP_BOTTOM = 95, CROP_SIDE = 535, MARGIN = .1f;
    private static final float FADE_TOP = .2f, FADE_RIGHT = .14f;
    private static final int SHADOW = 0x59000000;

    private HeartIcon() {}

    /** Draws the icon with the GL context; call outside any open batch. Throws if the game's skeleton cannot be drawn. */
    static Texture build() {
        TextureAtlas atlas = new TextureAtlas(Gdx.files.internal(ATLAS));
        FrameBuffer frame = null;
        PolygonSpriteBatch batch = null;
        IntBuffer viewport = BufferUtils.newIntBuffer(16);
        Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, viewport);
        FloatBuffer clear = BufferUtils.newFloatBuffer(16);
        Gdx.gl.glGetFloatv(GL20.GL_COLOR_CLEAR_VALUE, clear);
        try {
            SkeletonData data = new SkeletonJson(atlas).readSkeletonData(Gdx.files.internal(SKELETON));
            Skeleton skeleton = new Skeleton(data);
            skeleton.setColor(Color.WHITE);
            AnimationState state = new AnimationState(new AnimationStateData(data));
            state.setAnimation(0, "idle", false);
            state.apply(skeleton);
            Slot shadow = skeleton.findSlot("images/shadow");
            if (shadow != null) shadow.setAttachment(null);
            skeleton.updateWorldTransform();

            float side = CROP_SIDE * (1 + 2 * MARGIN), left = CROP_LEFT - CROP_SIDE * MARGIN, bottom = CROP_BOTTOM - CROP_SIDE * MARGIN;
            frame = new FrameBuffer(Pixmap.Format.RGBA8888, SIZE, SIZE, false);
            batch = new PolygonSpriteBatch();
            SkeletonMeshRenderer renderer = new SkeletonMeshRenderer();
            renderer.setPremultipliedAlpha(true);
            frame.begin();
            byte[] pixels;
            try {
                Gdx.gl.glClearColor(0, 0, 0, 0);
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
                batch.getProjectionMatrix().setToOrtho2D(left, bottom, side, side);
                batch.begin();
                batch.setBlendFunction(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA);
                renderer.draw(batch, skeleton);
                batch.end();
                pixels = ScreenUtils.getFrameBufferPixels(0, 0, SIZE, SIZE, true);
            } finally { frame.end(); }

            int[] argb = new int[SIZE * SIZE];
            for (int i = 0; i < argb.length; i++) {
                int a = pixels[4 * i + 3] & 255;
                argb[i] = a << 24 | straight(pixels[4 * i], a) << 16 | straight(pixels[4 * i + 1], a) << 8 | straight(pixels[4 * i + 2], a);
            }
            int[] icon = decorate(argb, SIZE, MARGIN / (1 + 2 * MARGIN));
            Pixmap pixmap = new Pixmap(SIZE, SIZE, Pixmap.Format.RGBA8888);
            try {
                // Written as bytes: drawPixel would blend each pixel over the empty pixmap and darken the soft edges.
                java.nio.ByteBuffer bytes = pixmap.getPixels();
                for (int i = 0; i < icon.length; i++)
                    bytes.put(4 * i, (byte) (icon[i] >>> 16)).put(4 * i + 1, (byte) (icon[i] >>> 8))
                        .put(4 * i + 2, (byte) icon[i]).put(4 * i + 3, (byte) (icon[i] >>> 24));
                Texture texture = new Texture(pixmap);
                texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
                return texture;
            } finally { pixmap.dispose(); }
        } finally {
            if (batch != null) batch.dispose();
            if (frame != null) frame.dispose();
            atlas.dispose();
            Gdx.gl.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
            Gdx.gl.glClearColor(clear.get(0), clear.get(1), clear.get(2), clear.get(3));
        }
    }

    private static int straight(byte premultiplied, int alpha) {
        return alpha == 0 ? 0 : Math.min(255, (premultiplied & 255) * 255 / alpha);
    }

    /**
     * Fades the cut vessels at the top and right edges of the body crop and lays the drawing over a faint drop shadow, so it
     * sits on the top bar without an outline. {@code argb} is row-major from the top, straight alpha;
     * {@code margin} is the empty border around the crop as a share of the side.
     */
    static int[] decorate(int[] argb, int size, float margin) {
        float[] alpha = new float[argb.length];
        int lo = Math.round(margin * size), hi = size - lo, inner = hi - lo;
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                float fade = Math.min(ramp(y - lo, FADE_TOP * inner), ramp(hi - 1 - x, FADE_RIGHT * inner));
                alpha[y * size + x] = (argb[y * size + x] >>> 24) / 255f * fade;
            }
        float unit = size / 64f;
        float[] shadow = grow(alpha, size, unit);
        int drop = Math.round(2 * unit);
        int[] out = new int[argb.length];
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                int i = y * size + x;
                int pixel = y >= drop ? scaled(SHADOW, shadow[i - drop * size]) : 0;
                out[i] = over(argb[i] & 0xffffff | Math.round(alpha[i] * 255) << 24, pixel);
            }
        return out;
    }

    /** 0 at or before the edge, rising to 1 over {@code width} pixels. */
    private static float ramp(float distance, float width) {
        return Math.max(0, Math.min(1, distance / width));
    }

    /** Each pixel takes the strongest alpha within {@code radius}, with a one-pixel soft edge. */
    static float[] grow(float[] alpha, int size, float radius) {
        float[] out = new float[alpha.length];
        int reach = (int) Math.ceil(radius + 1);
        for (int y = 0; y < size; y++)
            for (int x = 0; x < size; x++) {
                float best = 0;
                for (int dy = -reach; dy <= reach && best < 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= size) continue;
                    for (int dx = -reach; dx <= reach; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= size) continue;
                        float near = Math.max(0, Math.min(1, radius + 1 - (float) Math.sqrt(dx * dx + dy * dy)));
                        best = Math.max(best, alpha[yy * size + xx] * near);
                    }
                }
                out[y * size + x] = best;
            }
        return out;
    }

    private static int scaled(int argb, float factor) {
        return Math.round((argb >>> 24) * factor) << 24 | argb & 0xffffff;
    }

    /** Straight-alpha "source over destination". */
    static int over(int src, int dst) {
        float sa = (src >>> 24) / 255f, da = (dst >>> 24) / 255f, a = sa + da * (1 - sa);
        if (a <= 0) return 0;
        int out = Math.round(a * 255) << 24;
        for (int shift = 0; shift <= 16; shift += 8) {
            float s = (src >>> shift & 255), d = (dst >>> shift & 255);
            out |= Math.round((s * sa + d * da * (1 - sa)) / a) << shift;
        }
        return out;
    }

    /** The icon's size factor while a simulation runs: a double beat (lub-dub) once a second, at rest otherwise. */
    static float beat(long millis) {
        float t = (millis % 1000) / 1000f;
        return 1 + .08f * bump(t, .08f) + .05f * bump(t, .28f);
    }

    private static float bump(float t, float centre) {
        return Math.max(0, 1 - Math.abs(t - centre) / .08f);
    }
}
