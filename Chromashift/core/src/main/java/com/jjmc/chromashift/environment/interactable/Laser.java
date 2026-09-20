package com.jjmc.chromashift.environment.interactable;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.jjmc.chromashift.Assets;
import com.jjmc.chromashift.environment.Solid;
import java.util.ArrayList;

public class Laser implements Interactable {
    private static final String TEX_PATH = "environment/laser/laser.png"; // default faces right

    private final Rectangle bounds;
    private float rotation;      // degrees (0=right, 90=up, 180=left, 270=down)
    private float maxLength = 1000f; // laser beam max length (reserved)
    private int maxBounces = 8;

    private LaserRay ray;
    // References to mirrors and glasses for casting (set externally)
    private ArrayList<Mirror> mirrors = new ArrayList<>();
    private ArrayList<Glass> glasses = new ArrayList<>();
    private ArrayList<Solid> solids = new ArrayList<>();
    private ArrayList<Vector2> cachedPoints = new ArrayList<>();

    public ArrayList<Vector2> getCachedPoints() { return cachedPoints; }

    public Laser(float x, float y) {
        // Fixed 32x32 size per spec
        this.bounds = new Rectangle(x, y, 32f, 32f);
        // Default orientation faces right
        this.rotation = 0f;
        // Ray origin at center of bounds
        this.ray = new LaserRay(x + 16f, y + 16f);
        this.ray.setMaxBounces(maxBounces);
    }

    public void setMirrors(ArrayList<Mirror> mirrors) {
        this.mirrors = mirrors;
    }

    public void setGlasses(ArrayList<Glass> glasses) {
        this.glasses = glasses;
    }

    public void setSolids(ArrayList<Solid> solids) {
        this.solids = solids;
    }

    private float gameTime = 0f;
    @Override
    public void update(float delta) {
        gameTime += delta;
        // Update ray position to center of bounds
        ray.position.set(bounds.x + bounds.width / 2f, bounds.y + bounds.height / 2f);
        ray.setRotation(rotation);
        // Cast ray and cache points - use game time instead of system time for determinism
        cachedPoints = ray.cast(mirrors, glasses, solids, gameTime);
    }

    @Override
    public Rectangle getBounds() {
        return bounds;
    }

    @Override
    public void render(SpriteBatch batch) {
        // Draw beam using LaserRay cached points so it's visible without debug mode
        if (cachedPoints != null && cachedPoints.size() >= 2) {
            ensurePixel();
            java.util.ArrayList<com.badlogic.gdx.graphics.Color> cols = ray.getLastSegmentColors();
            com.badlogic.gdx.graphics.Color prev = batch.getColor();
            float pr = prev.r, pg = prev.g, pb = prev.b, pa = prev.a;
            try {
                batch.flush();
                for (int i = 0; i < cachedPoints.size() - 1; i++) {
                    Vector2 a = cachedPoints.get(i);
                    Vector2 b = cachedPoints.get(i + 1);
                    float dx = b.x - a.x;
                    float dy = b.y - a.y;
                    float len = (float) Math.sqrt(dx * dx + dy * dy);
                    float angle = (float) Math.toDegrees(Math.atan2(dy, dx));
                    com.badlogic.gdx.graphics.Color sc = (cols != null && i < cols.size()) ? cols.get(i) : com.badlogic.gdx.graphics.Color.RED;
                    // beam thickness: outer 6px, inner 4px
                    float outerTh = 6f;
                    float innerTh = 4f;
                    float cx = (a.x + b.x) * 0.5f;
                    float cy = (a.y + b.y) * 0.5f;
                    float x = cx - len / 2f;
                    float outerY = cy - outerTh / 2f;
                    // outer border (solid)
                    batch.setColor(sc.r, sc.g, sc.b, 1f);
                    batch.draw(PIXEL, x, outerY, len / 2f, outerTh / 2f, len, outerTh, 1f, 1f, angle, 0, 0, 1, 1, false, false);
                    // inner translucent fill
                    float innerY = cy - innerTh / 2f;
                    float innerAlpha = 0.25f; // slightly transparent inner fill
                    batch.setColor(sc.r, sc.g, sc.b, innerAlpha);
                    batch.draw(PIXEL, x, innerY, len / 2f, innerTh / 2f, len, innerTh, 1f, 1f, angle, 0, 0, 1, 1, false, false);
                }
                batch.flush();
            } finally {
                batch.setColor(pr, pg, pb, pa);
            }
        }

        // Draw the laser base using texture, rotated by rotation (default faces right)
        if (Assets.manager.isLoaded(TEX_PATH, Texture.class)) {
            Texture tex = Assets.manager.get(TEX_PATH, Texture.class);
            float x = bounds.x;
            float y = bounds.y;
            float w = bounds.width;
            float h = bounds.height;
            // draw centered rotation
            batch.draw(tex,
                    x, y,
                    w / 2f, h / 2f, // origin
                    w, h,
                    1f, 1f,
                    rotation,
                    0, 0,
                    tex.getWidth(), tex.getHeight(),
                    false, false);
        }
    }

    private static com.badlogic.gdx.graphics.Texture PIXEL;
    private static void ensurePixel() {
        if (PIXEL == null) {
            com.badlogic.gdx.graphics.Pixmap pm = new com.badlogic.gdx.graphics.Pixmap(1,1, com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888);
            pm.setColor(com.badlogic.gdx.graphics.Color.WHITE);
            pm.fill();
            PIXEL = new com.badlogic.gdx.graphics.Texture(pm);
            pm.dispose();
        }
    }
    public static void disposeStatic() {
        if (PIXEL != null) {
            try { PIXEL.dispose(); } catch (Exception ignored) {}
            PIXEL = null;
        }
    }

    @Override
    public void debugDraw(ShapeRenderer sr) {
        // Optional debug bounds
        sr.setColor(Color.GRAY);
        sr.rect(bounds.x, bounds.y, bounds.width, bounds.height);

        // Draw laser beam with ray system
        if (cachedPoints != null && !cachedPoints.isEmpty()) {
            ray.draw(sr, cachedPoints);
        }
    }

    @Override
    public void interact() {
        // Non-interactable; only LaserRay variant is interactable per spec
    }

    @Override
    public boolean canInteract() {
        return false;
    }

    public void setMaxLength(float len) {
        this.maxLength = len;
    }

    // Snap rotation to 0, 90, 180, 270 so placement/orientation is constrained to U/D/L/R
    public void setRotation(float degrees) {
        int steps = Math.round(degrees / 90f);
        int snapped = ((steps % 4) + 4) % 4; // 0..3
        this.rotation = snapped * 90f;
    }

    public float getRotation() {
        return rotation;
    }

    public void setMaxBounces(int bounces) {
        this.maxBounces = Math.max(0, bounces);
        if (ray != null) ray.setMaxBounces(maxBounces);
    }
}
