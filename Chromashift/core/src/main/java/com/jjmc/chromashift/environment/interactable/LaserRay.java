package com.jjmc.chromashift.environment.interactable;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.chromashift.helper.PerformanceProfiler;
import com.jjmc.chromashift.Assets;
import com.jjmc.chromashift.environment.Solid;

import java.util.ArrayList;

public class LaserRay implements Interactable {
    private static final String TEX_PATH = "environment/laser/laser.png";
    public Vector2 position;
    private float rotation;
    private float maxLength = 1000000f;
    private int maxBounces = 8;
    private ArrayList<Color> segmentColors = null;
    private Rectangle bounds = null;
    private boolean playerNearby = false;
    private float rotateStep = 90f;
    private ArrayList<Mirror> mirrors = new ArrayList<>();
    private ArrayList<Glass> glasses = new ArrayList<>();
    private ArrayList<Solid> solids = new ArrayList<>();
    private ArrayList<Vector2> cachedPoints = new ArrayList<>();
    private final ArrayList<Vector2> pointPool = new ArrayList<>();

    // temps
    private static final Vector2 TMP_ORIGIN = new Vector2();
    private static final Vector2 TMP_DIR = new Vector2();
    private static final Color TMP_COLOR = new Color();

    public LaserRay(float x, float y) { position = new Vector2(x, y); }
    public LaserRay(float x, float y, boolean asEmitter) {
        if (asEmitter) { bounds = new Rectangle(x, y, 32f, 32f); position = new Vector2(x + 16f, y + 16f); }
        else position = new Vector2(x, y);
    }
    public void setMaxBounces(int max) { maxBounces = Math.max(0, max); }
    public int getMaxBounces() { return maxBounces; }
    public void setRotation(float degrees) { rotation = degrees % 360f; }
    public float getRotation() { return rotation; }

    public ArrayList<Vector2> cast(ArrayList<Mirror> mirrors, ArrayList<Glass> glasses, ArrayList<Solid> solids, float time) {
        PerformanceProfiler.start("laser_cast");
        if (segmentColors == null) segmentColors = new ArrayList<>();
        segmentColors.clear();
        cachedPoints.clear();
        TMP_ORIGIN.set(position);
        addPoint(TMP_ORIGIN.x, TMP_ORIGIN.y);
        TMP_DIR.set((float) Math.cos(Math.toRadians(rotation)), (float) Math.sin(Math.toRadians(rotation))).nor();
        Color currentColor = TMP_COLOR.set(Color.RED);
        int bounces = 0;
        float remainingLength = maxLength;
        // Increase skip epsilon to avoid immediate self-reintersection due to FP errors
        final float EPS_SKIP = 0.1f;
        Mirror lastMirrorHit = null;
        while (remainingLength > 0.01f) {
            Vector2 closestHit = null; Mirror hitMirror = null; Glass hitGlass = null; Solid hitSolid = null;
            float closestDist = Float.MAX_VALUE;
            if (mirrors != null) for (Mirror m : mirrors) {
                // Avoid immediately selecting the same mirror we just bounced from
                if (lastMirrorHit != null && m == lastMirrorHit) continue;
                Vector2 hit = intersectLine(TMP_ORIGIN, TMP_DIR, m.start, m.end);
                if (hit != null) {
                    float dist = hit.dst2(TMP_ORIGIN);
                    if (dist < EPS_SKIP*EPS_SKIP) continue;
                    if (dist < closestDist) { closestDist = dist; closestHit = hit; hitMirror = m; hitGlass = null; }
                }
            }
            if (glasses != null) for (Glass g : glasses) { Vector2 hit = intersectLine(TMP_ORIGIN, TMP_DIR, g.start, g.end); if (hit != null) { float dist = hit.dst2(TMP_ORIGIN); if (dist < EPS_SKIP*EPS_SKIP) continue; if (dist < closestDist) { closestDist = dist; closestHit = hit; hitMirror = null; hitGlass = g; hitSolid = null; } } }
            if (solids != null) for (Solid s : solids) { if (s == null || !s.isBlocking()) continue; Rectangle r = s.getCollisionBounds(); if (r == null) continue; float cx = r.x + r.width*0.5f; float cy = r.y + r.height*0.5f; float dxC = cx - TMP_ORIGIN.x; float dyC = cy - TMP_ORIGIN.y; float diag = r.width*r.width + r.height*r.height; float reach = remainingLength*remainingLength + diag; if (dxC*dxC + dyC*dyC > reach) continue; Vector2 a = new Vector2(r.x, r.y); Vector2 b = new Vector2(r.x + r.width, r.y); Vector2 c = new Vector2(r.x + r.width, r.y + r.height); Vector2 d = new Vector2(r.x, r.y + r.height); Vector2[] segA = {a,b,c,d}; Vector2[] segB = {b,c,d,a}; for (int i=0;i<4;i++){ Vector2 hit = intersectLine(TMP_ORIGIN, TMP_DIR, segA[i], segB[i]); if (hit!=null){ float dist = hit.dst2(TMP_ORIGIN); if (dist < EPS_SKIP*EPS_SKIP) continue; boolean accept = true; if (s instanceof Box){ Color bcol = ((Box) s).getColor(); if (bcol!=null){ float eps = 0.03f; if (Math.abs(bcol.r-currentColor.r)>eps || Math.abs(bcol.g-currentColor.g)>eps || Math.abs(bcol.b-currentColor.b)>eps) accept=false; } } if(!accept) continue; if (dist < closestDist){ closestDist = dist; closestHit = hit; hitMirror = null; hitGlass = null; hitSolid = s; } } } }
            if (closestHit != null) {
                float hitDist = (float) Math.sqrt(closestDist);
                if (hitDist > remainingLength) { addPoint(TMP_ORIGIN.x + TMP_DIR.x * remainingLength, TMP_ORIGIN.y + TMP_DIR.y * remainingLength); segmentColors.add(new Color(currentColor)); break; }
                addPoint(closestHit.x, closestHit.y); segmentColors.add(new Color(currentColor));
                if (hitSolid != null) { if (hitSolid instanceof Target) ((Target) hitSolid).onLaserHit(currentColor); break; }
                else if (hitMirror != null) {
                    Vector2 normal = hitMirror.getNormal();
                    // Ensure unit normal
                    if (normal.len2() != 1f) normal.nor();
                    // Reflect incident direction
                    float dot = TMP_DIR.x*normal.x + TMP_DIR.y*normal.y;
                    TMP_DIR.x -= 2f*dot*normal.x; TMP_DIR.y -= 2f*dot*normal.y; TMP_DIR.nor();
                    remainingLength -= hitDist;
                    // Nudge origin forward along new direction to avoid self-hit
                    TMP_ORIGIN.set(closestHit.x + TMP_DIR.x * EPS_SKIP * 2f, closestHit.y + TMP_DIR.y * EPS_SKIP * 2f);
                    lastMirrorHit = hitMirror;
                    if (++bounces > maxBounces) break;
                    continue;
                }
                else if (hitGlass != null) {
                    if (hitGlass.doesTintLaser()) {
                        Color gcol = hitGlass.getTintAt(closestHit, time);
                        float blend = hitGlass.getTintStrength();
                        currentColor.lerp(gcol, Math.max(0f, Math.min(1f, blend)));
                        currentColor.a = 1f;
                    }
                    remainingLength -= hitDist;
                    TMP_ORIGIN.set(closestHit.x + TMP_DIR.x * EPS_SKIP * 2f, closestHit.y + TMP_DIR.y * EPS_SKIP * 2f);
                    // Reset last mirror so future hits on it are allowed
                    lastMirrorHit = null;
                }
            } else { addPoint(TMP_ORIGIN.x + TMP_DIR.x * remainingLength, TMP_ORIGIN.y + TMP_DIR.y * remainingLength); segmentColors.add(new Color(currentColor)); break; }
            if (remainingLength < 5f) break; // early exit tiny remainder
        }
        PerformanceProfiler.stop("laser_cast");
        return cachedPoints;
    }

    public void draw(ShapeRenderer sr, ArrayList<Vector2> points) {
        if (points == null || points.size() < 2)
            return;
        // if segmentColors exists and matches segments, use it; otherwise fallback to
        // red
        boolean useColors = (segmentColors != null && segmentColors.size() == points.size() - 1);
        for (int i = 0; i < points.size() - 1; i++) {
            if (useColors)
                sr.setColor(segmentColors.get(i));
            else
                sr.setColor(Color.RED);
            sr.rectLine(points.get(i), points.get(i + 1), 3f);
        }
    }

    public ArrayList<Vector2> getCachedPoints() { return cachedPoints; }

    // --- Interactable implementation ---
    @Override
    public Rectangle getBounds() {
        // If not used as an emitter, create a small bounds around the position center
        if (bounds == null) {
            return new Rectangle(position.x - 16f, position.y - 16f, 32f, 32f);
        }
        return bounds;
    }

    private float gameTime = 0f;
    @Override public void update(float delta) { gameTime += delta; cachedPoints = cast(mirrors, glasses, solids, gameTime); }

    @Override
    public void render(SpriteBatch batch) {
        // Draw beam using SpriteBatch so it's visible without debug mode
        if (cachedPoints != null && cachedPoints.size() >= 2) {
            ensurePixel(batch);
            java.util.ArrayList<Color> cols = segmentColors;
            Color prev = batch.getColor();
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
                    Color sc = (cols != null && i < cols.size()) ? cols.get(i) : Color.RED;
                    // beam thickness: outer 6px, inner 4px (inner is semi-transparent)
                    float outerTh = 6f;
                    float innerTh = 4f;
                    // center-based draw
                    float cx = (a.x + b.x) * 0.5f;
                    float cy = (a.y + b.y) * 0.5f;
                    float x = cx - len / 2f;
                    float outerY = cy - outerTh / 2f;
                    // outer colored border (solid)
                    batch.setColor(sc.r, sc.g, sc.b, 1f);
                    batch.draw(PIXEL, x, outerY, len / 2f, outerTh / 2f, len, outerTh, 1f, 1f, angle, 0, 0, 1, 1, false,
                            false);
                    // inner translucent fill
                    float innerY = cy - innerTh / 2f;
                    float innerAlpha = 0.25f; // slightly transparent inner fill
                    batch.setColor(sc.r, sc.g, sc.b, innerAlpha);
                    batch.draw(PIXEL, x, innerY, len / 2f, innerTh / 2f, len, innerTh, 1f, 1f, angle, 0, 0, 1, 1, false,
                            false);
                }
                batch.flush();
            } finally {
                batch.setColor(pr, pg, pb, pa);
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
    }

    // Provide access to last computed segment colors for external renderers
    public java.util.ArrayList<Color> getLastSegmentColors() { return segmentColors; }

    // PIXEL for batch drawing (lazy)
    private static com.badlogic.gdx.graphics.Texture PIXEL;

    public static void disposeStatic() { if (PIXEL != null) { try { PIXEL.dispose(); } catch (Exception ignored) {} PIXEL = null; } }
    private static void ensurePixel(SpriteBatch batch) {
        if (PIXEL == null) {
            com.badlogic.gdx.graphics.Pixmap pm = new com.badlogic.gdx.graphics.Pixmap(1, 1,
                    com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888);
            pm.setColor(com.badlogic.gdx.graphics.Color.WHITE);
            pm.fill();
            PIXEL = new com.badlogic.gdx.graphics.Texture(pm);
            pm.dispose();
        }
    }

    @Override
    public void debugDraw(ShapeRenderer shape) {
        // Draw emitter bounds for visualization
        Rectangle b = getBounds();
        shape.setColor(Color.GRAY);
        shape.rect(b.x, b.y, b.width, b.height);
        // Draw beam
        if (cachedPoints != null && !cachedPoints.isEmpty()) {
            draw(shape, cachedPoints);
        }
    }

    @Override
    public void interact() {
        // Rotate by rotateStep on player interaction
        setRotation(((Math.round(rotation / 90f) * 90f) + rotateStep) % 360f);
    }

    @Override
    public void checkInteraction(Rectangle playerHitbox) {
        playerNearby = playerHitbox != null && playerHitbox.overlaps(getBounds());
    }

    @Override
    public boolean canInteract() {
        return playerNearby;
    }

    // Environment wiring
    public void setMirrors(java.util.ArrayList<Mirror> ms) {
        this.mirrors = (ms != null) ? ms : new ArrayList<>();
    }

    public void setGlasses(java.util.ArrayList<Glass> gs) {
        this.glasses = (gs != null) ? gs : new ArrayList<>();
    }

    public void setSolids(java.util.ArrayList<Solid> ss) {
        this.solids = (ss != null) ? ss : new ArrayList<>();
    }

    public void setRotationStep(float step) {
        this.rotateStep = (step == 0f ? 90f : step);
    }

    /** 2D line intersection helper (ray vs segment) */
    private Vector2 intersectLine(Vector2 rayOrigin, Vector2 rayDir, Vector2 segA, Vector2 segB) {
        // Solve r_o + t*r = s_a + u*s where r is rayDir and s = segB-segA
        float rOx = rayOrigin.x;
        float rOy = rayOrigin.y;
        float rDx = rayDir.x;
        float rDy = rayDir.y;

        float sAx = segA.x;
        float sAy = segA.y;
        float sBx = segB.x;
        float sBy = segB.y;

        float sDx = sBx - sAx;
        float sDy = sBy - sAy;

        // 2D cross product
        float denom = rDx * sDy - rDy * sDx; // cross(r, s)
        final float EPS = 1e-6f;
        if (Math.abs(denom) < EPS) {
            // parallel (or nearly parallel) - no reliable intersection
            return null;
        }

        // vector from ray origin to segment start
        float wx = sAx - rOx;
        float wy = sAy - rOy;

        // t = cross(w, s) / denom
        float t = (wx * sDy - wy * sDx) / denom;
        // u = cross(w, r) / denom
        float u = (wx * rDy - wy * rDx) / denom;

        // ray forward (t >= 0) and segment between 0..1 (u in [0,1])
        if (t < 0f)
            return null;
        if (u < 0f || u > 1f)
            return null;

        // since rayDir is expected to be normalized in cast(), t is the distance along
        // ray
        return new Vector2(rOx + rDx * t, rOy + rDy * t);
    }

    private void addPoint(float x, float y) {
        Vector2 v;
        int idx = cachedPoints.size();
        if (idx < pointPool.size()) { v = pointPool.get(idx); v.set(x,y); }
        else { v = new Vector2(x,y); pointPool.add(v); }
        cachedPoints.add(v);
    }
}
