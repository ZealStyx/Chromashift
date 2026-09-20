package com.jjmc.chromashift.environment.interactable;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;

/**
 * Simple transmissive glass segment. Laser passes through but is tinted by the glass color.
 */
public class Glass implements Interactable {
    public final Vector2 start;
    public final Vector2 end;
    public final Color color;
    private final Rectangle bounds;
    // Pixel texture for batch rendering
    private static Texture PIXEL;
    private static void ensurePixel() {
        if (PIXEL == null) {
            Pixmap pm = new Pixmap(1,1, Pixmap.Format.RGBA8888);
            pm.setColor(Color.WHITE);
            pm.fill();
            PIXEL = new Texture(pm);
            pm.dispose();
        }
    }
    
    /**
     * Create glass with fixed 16x16 footprint.
     */
    public Glass(float x, float y, float width, float height, Color color) {
        this(x, y, width, height, color, true, 1f, true);
    }

    public Glass(Vector2 start, Vector2 end, Color color) {
        this(start.x, start.y, end.x - start.x, end.y - start.y, color, true, 1f, true);
    }

    // Optional: whether this glass should tint the laser passing through it
    private boolean tintLaser = false;
    // How strongly the glass tints the laser [0..1] (1 = full override, 0 = no effect)
    private float tintStrength = 1f;

    /**
     * Full constructor allowing control over whether the glass tints the laser and how strongly.
     */
    public Glass(float x, float y, float width, float height, Color color, boolean tintLaser, float tintStrength, boolean rainbow) {
        // Force to fixed 16x16 centered footprint (editor will pass centered coords)
        width = 16f;
        height = 16f;
        
        this.bounds = new Rectangle(x, y, width, height);
        this.start = new Vector2(x, y);
        this.end = new Vector2(x + width, y + height);
        this.color = new Color(color);
        this.tintLaser = tintLaser;
        this.tintStrength = tintStrength;
        this.rainbow = rainbow;
    }

    /**
     * Backwards-compatible constructor: defaults to rainbow=true
     */
    public Glass(float x, float y, float width, float height, Color color, boolean tintLaser, float tintStrength) {
        this(x, y, width, height, color, tintLaser, tintStrength, true);
    }

    // Whether the glass cycles through rainbow colors. If false, the glass uses its base color.
    private boolean rainbow = true;
    // How fast the rainbow moves along the glass (cycles per second)
    private float speed = 1.5f;

    /**
     * Enable or disable rainbow (animated) tint for this glass.
     */
    public void setRainbow(boolean enabled) {
        this.rainbow = enabled;
    }

    public void setSpeed(float s) {
        this.speed = s;
    }

    public float getSpeed() {
        return this.speed;
    }

    /**
     * Returns whether this glass uses animated rainbow tinting.
     */
    public boolean isRainbow() {
        return this.rainbow;
    }

    public boolean doesTintLaser() {
        return tintLaser;
    }

    public float getTintStrength() {
        return tintStrength;
    }

    /**
     * Return a tint color at the given point on the glass.
     * This implements a moving rainbow along the glass: hue varies along the segment and shifts over time.
     * Returns a new Color instance (caller may cache if needed).
     */
    public Color getTintAt(Vector2 hitPoint, float time) {
        // if rainbow animation disabled, return static base color
        if (!rainbow) return new Color(this.color);
        // compute fractional parameter along the segment [0..1]
        float vx = end.x - start.x;
        float vy = end.y - start.y;
        float len2 = vx*vx + vy*vy;
        float t = 0f;
        if (len2 > 1e-6f) {
            t = ((hitPoint.x - start.x) * vx + (hitPoint.y - start.y) * vy) / len2;
            if (t < 0f) t = 0f;
            if (t > 1f) t = 1f;
        }

        // hue moves with position and time using per-glass speed
        float hue = (t + time * this.speed) % 1f;

        // convert HSV (hue, saturation, value) to RGB
        float sat = 1f;
        float val = 1f;

        float h6 = hue * 6f;
        int i = (int)Math.floor(h6) % 6;
        float f = h6 - (float)Math.floor(h6);
        float p = val * (1f - sat);
        float q = val * (1f - sat * f);
        float r=0f, g=0f, b=0f;
        switch (i) {
            case 0: r = val; g = q; b = p; break;
            case 1: r = q; g = val; b = p; break;
            case 2: r = p; g = val; b = q; break;
            case 3: r = p; g = q; b = val; break;
            case 4: r = q; g = p; b = val; break;
            case 5: default: r = val; g = p; b = q; break;
        }

        return new Color(r, g, b, this.color.a);
    }
    
    @Override
    public Rectangle getBounds() {
        return bounds;
    }
    
    @Override
    public void update(float delta) {
        // Static object - no update needed
    }
    
    private float gameTime = 0f;
    @Override
    