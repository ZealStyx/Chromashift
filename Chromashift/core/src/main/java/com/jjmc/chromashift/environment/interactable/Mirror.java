package com.jjmc.chromashift.environment.interactable;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;

public class Mirror implements Interactable {
    public Vector2 start;
    public Vector2 end;
    private final Rectangle bounds;
    private float angleDeg = 45f; // default diagonal
    private boolean playerNearby = false;

    private static final float SIZE = 32f;

    /**
     * Create a 32x32 mirror. Rotation can be changed via interact() in 45-degree steps.
     */
    public Mirror(float x, float y, float width, float height) {
        // Force fixed 32x32 size
        this.bounds = new Rectangle(x, y, SIZE, SIZE);
        // Initialize as diagonal from bottom-left to top-right (45deg)
        updateLineFromAngle();
    }

    /** Convenience ctor keeping signature; clamps to 32x32 footprint between points. */
    public Mirror(Vector2 a, Vector2 b) {
        float minX = Math.min(a.x, b.x);
        float minY = Math.min(a.y, b.y);
        this.bounds = new Rectangle(minX, minY, SIZE, SIZE);
        updateLineFromAngle();
    }

    /** Returns the normal vector of the mirror */
    public Vector2 getNormal() {
        Vector2 dir = new Vector2(end).sub(start);
        return new Vector2(-dir.y, dir.x).nor(); // perpendicular
    }

    @Override
    public Rectangle getBounds() {
        return bounds;
    }

    @Override
    public void update(float delta) {
        // Static aside from interaction-based rotation
    }

    // Simple 1x1 pixel for batch shape drawing
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

    @Override
    public void render(SpriteBatch batch) {
        ensurePixel();
        // Draw mirror bar (32 length, 6 thickness) using rotation around center
        float length = 32f;
        float thickness = 6f;
        float cx = bounds.x + bounds.width/2f;
        float cy = bounds.y + bounds.height/2f;
        // position so that origin is center
        float x = cx - length/2f;
        float y = cy - thickness/2f;
        Color prev = batch.getColor();
        float pr = prev.r, pg = prev.g, pb = prev.b, pa = prev.a;
        try {
            batch.flush();
            batch.setColor(Color.CYAN);
            // angleDeg currently represents direction of bar; draw with rotation
            batch.draw(PIXEL, x, y, length/2f, thickness/2f, length, thickness, 1f, 1f, angleDeg,
                    0,0,1,1,false,false);
            batch.flush();
        } finally {
            batch.setColor(pr, pg, pb, pa);
        }
    }

    public static void disposeStatic() {
        if (PIXEL != null) { try { PIXEL.dispose(); } catch (Exception ignored) {} PIXEL = null; }
    }
    @Override
    public void debugDraw(ShapeRenderer sr) {
        // Draw bounds
        sr.setColor(Color.DARK_GRAY);
        sr.rect(bounds.x, bounds.y, bounds.width, bounds.height);
        sr.setColor(Color.CYAN);
        sr.rectLine(start, end, 6f);
    }

    @Override
    public void interact() {
        // Rotate by 45 degrees per interaction
        angleDeg = (angleDeg + 45f) % 360f;
        updateLineFromAngle();
    }

    @Override
    public void checkInteraction(Rectangle playerHitbox) {
        playerNearby = playerHitbox != null && playerHitbox.overlaps(bounds);
    }

    @Override
    public boolean canInteract() {
        return playerNearby;
    }

    private void updateLineFromAngle() {
        // Compute line endpoints within the 32x32 square based on angleDeg.
        // Use the center and extend to half-length of 16 so total length is 32.
        float cx = bounds.x + bounds.width * 0.5f;
        float cy = bounds.y + bounds.height * 0.5f;
        // Half-length for total length 32
        float half = 16f;
        float rad = (float)Math.toRadians(angleDeg);
        float dx = (float)Math.cos(rad) * half;
        float dy = (float)Math.sin(rad) * half;
        this.start = new Vector2(cx - dx, cy - dy);
        this.end   = new Vector2(cx + dx, cy + dy);
    }

    public void setAngleDegrees(float angle) {
        this.angleDeg = angle % 360f;
        updateLineFromAngle();
    }
}
