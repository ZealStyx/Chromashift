package com.jjmc.chromashift.environment.enemy;

import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.chromashift.helper.PerformanceProfiler;
import com.chromashift.helper.VisibilityCuller;

/**
 * Flexible spring-driven tentacle; tip follows mouse.
 */
public class Tentacle implements Enemy {
    // Hit-based system
    private int maxHits = 6;                    // hits to kill
    private int hitPointsRemaining = 6;         // remaining
    private int captureHitCounter = 0;          // hits during capture (release after 3)
    private boolean dead = false;               // dead?
    private boolean staticMode = false;         // static render (no physics)
    private com.badlogic.gdx.utils.Array<com.jjmc.chromashift.environment.collectible.Collectible> dropTarget; // diamond spawn
    private final Rectangle bounds = new Rectangle();
    private String uniqueId;                    // stable unique ID for save/load
    // Segment config
    private final int segments;
    private final float segmentLength = 12f;

    // State arrays (allocated once)
    private final Vector2 anchor; // root
    private final Vector2[] pos; // segment positions
    private final Vector2[] vel; // velocities
    private final float[] thickness; // visual: thick → thin
    private final float[] suctionScale; // suction cup size

    // Physics params
    private float springStiffness = 10f; // spring constant (higher = stiffer)
    private float springDamping = 8f; // damping (higher = less bouncy)
    private float globalDamping = 0.992f; // energy bleed (<1.0 dissipates)
    private float targetAttract = 100f; // tip->mouse force
    private float maxVelocity = 800f; // velocity clamp

    // Animation timing
    private float time = 0f;
    private float suctionWaveSpeed = 3f; // suction oscillation
    private float suctionWaveOffset = 0.35f; // phase per segment (wave)
    // Idle wave (motion when cursor still)
    private float waveBaseAmplitude = 200f; // root amplitude
    private float waveTipFactor = 3f; // tip amplitude (0 = none, 1 = same)
    private float idleWaveFrequency = 1.4f; // Hz
    private float idleWavePropagation = 0.25f; // phase advance
    // Tip smoothing (lerp per frame)
    private float tipFollowLerp = 0.18f; // snappier = higher
    // Mouse blend after constraints
    private float mouseInfluence = 0.3f; // 0 = ignore, 1 = full
    private float mouseDistanceThreshold = 140f; // ramp distance
    // Life-like motion
    private float lifeTwitchAmplitude = 8f; // twitch size
    private float lifeTwitchFrequency = 2.2f; // twitch Hz
    private float stiffnessModAmplitude = 0.12f; // stiffness mod depth
    private float stiffnessModFrequency = 0.5f; // stiffness mod Hz
    private float neighborVelocityBlend = 0.08f; // neighbor blend
    private final Vector2 prevMouse = new Vector2();
    private boolean sleeping = false; // sleep when far offscreen

    // Reusable temp vectors to avoid allocations in update()
    private final Vector2 tempDiff = new Vector2();
    private final Vector2 tempDir = new Vector2();
    private final Vector2 tempForce = new Vector2();
    private final Vector2 tempTarget = new Vector2();

    /**
     * Tentacle at (x, y) with default segments (30).
     */
    public Tentacle(float x, float y) {
        this(x, y, 30);
    }

    /**
     * Tentacle at (x, y) with custom segment count (10-50).
     */
    // Fast sin LUT
    private static final int SIN_LUT_SIZE = 2048;
    private static final float TWO_PI = (float) (Math.PI * 2.0);
    private static final float INV_TWO_PI = 1f / TWO_PI;
    private static final float[] SIN_LUT = new float[SIN_LUT_SIZE];
    static {
        for (int i = 0; i < SIN_LUT_SIZE; i++) {
            SIN_LUT[i] = (float) Math.sin(TWO_PI * i / SIN_LUT_SIZE);
        }
    }
    private static float fastSin(float angle) {
        float a = angle * INV_TWO_PI; // normalize
        a -= (int) a;
        if (a < 0) a += 1f;
        int idx = (int) (a * SIN_LUT_SIZE) & (SIN_LUT_SIZE - 1);
        return SIN_LUT[idx];
    }

    public Tentacle(float x, float y, int segmentCount) {
        this.segments = Math.max(10, Math.min(50, segmentCount)); // Clamp between 10-50
        anchor = new Vector2(x, y);
        // Generate stable unique ID based on position
        this.uniqueId = String.format("Tentacle_%.0f_%.0f_%d", x, y, (int)(x*31 + y*17) % 10000);

        pos = new Vector2[segments];
        vel = new Vector2[segments];
        thickness = new float[segments];
        suctionScale = new float[segments];

        // Initialize all segments hanging downward
        segmentHitboxes = new com.badlogic.gdx.math.Circle[segments];
        for (int i = 0; i < segments; i++) {
            pos[i] = new Vector2(anchor.x, anchor.y - i * segmentLength);
            vel[i] = new Vector2();

            // Thickness: thick at base (i=0) → thin at tip (i=segments-1)
            float t = 1f - (i / (float) segments);
            thickness[i] = 6f + t * 16f;

            suctionScale[i] = 1f; // default scale
            
            // Create hitbox for each segment
            segmentHitboxes[i] = new com.badlogic.gdx.math.Circle(pos[i].x, pos[i].y, thickness[i] / 2f);
        }
    }

    /**
     * Update: spring forces, tip attraction, velocity integration.
     */
    // Capture state
    private boolean isPlayerCaptured = false;
    private float curlRadius = 60f; // curl trap radius
    private boolean hasCapturedThisCurl = false; // no re-capture
    private boolean wasCurledLastFrame = false; // curl tracking
    
    // Segment hitboxes
    private final com.badlogic.gdx.math.Circle[] segmentHitboxes; // per-segment
    
    // Curl detection
    private final Vector2 curlCenter = new Vector2();
    private boolean isCurledCached = false;
    private boolean hasFullCurlCached = false;
    private float curlDetectionThreshold = 0.6f; // loop closed (0-1)

    /**
     * Update physics: spring forces, tip attraction, velocity integration,
     * constraint pass.
     */
    public void update(float delta, float targetX, float targetY) {
        if (dead || staticMode) return;
        PerformanceProfiler.start("tentacle_update");
        // visibility based sleep
        boolean visible = VisibilityCuller.isVisible(getBounds(), 96f);
        if (!visible) sleeping = true; else sleeping = false;
        if (sleeping) { time += delta; prevMouse.set(targetX, targetY); PerformanceProfiler.stop("tentacle_update"); return; }
        time += delta;

        // Set target to provided coordinates (Player position)
        tempTarget.set(targetX, targetY);

        // ===== Pin root segment =====
        pos[0].set(anchor);
        vel[0].setZero();

        // ===== Apply spring forces between consecutive segments + idle lateral wave
        // =====
        for (int i = 1; i < segments; i++) {
            Vector2 pPrev = pos[i - 1];
            Vector2 p = pos[i];

            // Difference vector: from previous to current
            tempDiff.set(p).sub(pPrev);
            float dist = tempDiff.len();
            if (dist < 1e-6f)
                continue; // avoid division by zero

            // Normalized direction
            tempDir.set(tempDiff).scl(1f / dist);

            // Stretch = how much the spring is extended beyond rest length
            float stretch = dist - segmentLength;

            // Relative velocity along spring direction (for damping term)
            float relVelAlong = vel[i].dot(tempDir) - vel[i - 1].dot(tempDir);

            // Local stiffness modulation (gives breathing / alive feeling)
                float localStiff = springStiffness * (1f + stiffnessModAmplitude * fastSin(time * stiffnessModFrequency + i * 0.1f));

            // Hooke's law + damping: F = -k*x - c*v_rel
            float forceMag = -localStiff * stretch - springDamping * relVelAlong;

            // Apply force to current segment (assuming unit mass)
            tempForce.set(tempDir).scl(forceMag * delta);
            vel[i].add(tempForce);

            // ---- Idle lateral wave force (perpendicular to spring direction) ----
            // Compute perpendicular (rotate +90 deg): (x,y)->(-y,x)
            float perpX = -tempDir.y;
            float perpY = tempDir.x;
            float wavePhase = time * idleWaveFrequency * (float) Math.PI * 2f + i * idleWavePropagation;
            // Amplitude taper: root -> tip using linear interpolation to waveTipFactor
            float normalized = i / (float) (segments - 1); // 0 at root, ~1 at tip
            float amp = waveBaseAmplitude * ((1f - normalized) + waveTipFactor * normalized);
            float waveForceMag = amp * fastSin(wavePhase);
            vel[i].x += perpX * waveForceMag * delta;
            vel[i].y += perpY * waveForceMag * delta;

            // ---- Small life twitch (along perpendicular + slight along-axis), smaller
            // toward tip ----
            float twitchPhase = time * lifeTwitchFrequency + i * 0.27f;
            float twitch = lifeTwitchAmplitude * (1f - normalized) * fastSin(twitchPhase);
            // Add along-perp twitch
            vel[i].x += perpX * twitch * delta;
            vel[i].y += perpY * twitch * delta;
            // Add tiny along-axis twitch
            vel[i].x += tempDir.x * (twitch * 0.12f) * delta;
            vel[i].y += tempDir.y * (twitch * 0.12f) * delta;
        }

        // Defer mouse attraction to after constraints so wave motion has priority.
        Vector2 tip = pos[segments - 1];
        tempDiff.set(tempTarget).sub(tip);
        float distToTarget = tempDiff.len();

        // ===== Integrate positions & apply global damping =====
        for (int i = 1; i < segments; i++) {
            // Clamp velocity to prevent instability
            float vLen = vel[i].len();
            if (vLen > maxVelocity) {
                vel[i].scl(maxVelocity / vLen);
            }

            // Euler integration: position += velocity * dt
            pos[i].x += vel[i].x * delta;
            pos[i].y += vel[i].y * delta;

            // Apply global damping (energy bleed)
            vel[i].scl(globalDamping);
        }

        // ===== Mouse quick-move impulse (gives reactive life when player moves fast)
        // =====
        // reuse tempDiff: mouse delta = currentMouse - prevMouse
        tempDiff.set(tempTarget).sub(prevMouse);
        float mouseDeltaLen = tempDiff.len();
        if (mouseDeltaLen > 12f) {
            // small impulse proportional to mouse movement, mostly perpendicular to tip
            // direction
            tempDir.set(tempDiff);
            if (tempDir.len() > 1e-6f)
                tempDir.nor();
            float px = -tempDir.y, py = tempDir.x;
            vel[segments - 1].x += px * (mouseDeltaLen * 0.06f);
            vel[segments - 1].y += py * (mouseDeltaLen * 0.06f);
            // small forward kick
            vel[segments - 1].x += tempDir.x * (mouseDeltaLen * 0.02f);
            vel[segments - 1].y += tempDir.y * (mouseDeltaLen * 0.02f);
        }

        // ===== Tip positional lerp (direct positional attraction) =====
        // This ensures visible pursuit even if velocity force small.
        if (distToTarget > 1e-3f) {
            // Lerp the tip directly toward target (not modifying velocity yet)
            tip.lerp(tempTarget, tipFollowLerp);
        }

        // ===== FABRIK-style constraint passes =====
        // Backward pass: keep distances starting from tip going to root
        for (int i = segments - 2; i >= 0; i--) {
            if (i == 0)
                break; // leave root for forward anchoring
            tempDiff.set(pos[i]).sub(pos[i + 1]);
            float d = tempDiff.len();
            if (d > 1e-6f) {
                pos[i].set(pos[i + 1]).add(tempDiff.scl(segmentLength / d));
            }
        }
        // Anchor root explicitly
        pos[0].set(anchor);
        // Forward pass: rebuild chain outward from root to tip maintaining lengths
        for (int i = 1; i < segments; i++) {
            tempDiff.set(pos[i]).sub(pos[i - 1]);
            float d = tempDiff.len();
            if (d > 1e-6f) {
                pos[i].set(pos[i - 1]).add(tempDiff.scl(segmentLength / d));
            }
        }

        // ===== Mouse attraction AFTER wave + constraint =====
        if (distToTarget > 1e-3f) {
            // Influence scales up beyond threshold distance
            float influenceScale = mouseInfluence;
            if (distToTarget < mouseDistanceThreshold) {
                influenceScale *= (distToTarget / mouseDistanceThreshold);
            }
            tempDir.set(tempDiff).nor();
            // Velocity nudge (subtle compared to wave)
            tempForce.set(tempDir).scl(targetAttract * influenceScale * delta);
            vel[segments - 1].add(tempForce);
            // Positional blend for immediate visual response
            tip.lerp(tempTarget, tipFollowLerp * influenceScale);
        }
        // ===== Recompute velocities from positional changes (optional for coherence)
        // =====
        // We approximate new velocities after positional constraints to avoid jitter.
        // (Could store previous positions; here we damp to avoid large snaps.)
        for (int i = 1; i < segments; i++) {
            vel[i].scl(globalDamping); // additional light damping post solve
        }

        // ===== Neighbor velocity blending for smooth, organic motion =====
        for (int i = 1; i < segments - 1; i++) {
            float avgX = (vel[i - 1].x + vel[i + 1].x) * 0.5f;
            float avgY = (vel[i - 1].y + vel[i + 1].y) * 0.5f;
            vel[i].x += (avgX - vel[i].x) * neighborVelocityBlend;
            vel[i].y += (avgY - vel[i].y) * neighborVelocityBlend;
        }

        // ===== Update suction cup animation (traveling sine wave) =====
        for (int i = 1; i < segments; i++) {
            suctionScale[i] = 0.8f + 0.2f * fastSin(time * suctionWaveSpeed + i * suctionWaveOffset);
        }
        // store mouse for next-frame delta
        prevMouse.set(tempTarget);
        
        // ===== Update segment hitboxes =====
        for (int i = 0; i < segments; i++) {
            segmentHitboxes[i].setPosition(pos[i].x, pos[i].y);
            segmentHitboxes[i].setRadius(thickness[i] / 2f);
        }
        
        // ===== Update curl detection =====
        updateCurlDetection();
        
        // ===== Handle curl state changes (reset capture flag when uncurling) =====
        boolean nowCurled = isCurled();
        if (wasCurledLastFrame && !nowCurled) {
            hasCapturedThisCurl = false; // allow capture again after uncurl
        }
        wasCurledLastFrame = nowCurled;
        PerformanceProfiler.stop("tentacle_update");
    }

    /**
     * Render the tentacle using ShapeRenderer.
     */
    public void draw(ShapeRenderer sr) {
        if (sleeping) return; // cull render
        // 1. Draw Outline (Darker, slightly thicker)
        sr.begin(ShapeRenderer.ShapeType.Filled);
        sr.setColor(0.1f, 0.05f, 0.15f, 1f); // Dark purple/black outline
        for (int i = 0; i < segments - 1; i++) {
            float lineThickness = thickness[i + 1] + 4f; // Outline thickness
            sr.rectLine(pos[i], pos[i + 1], lineThickness);
        }
        sr.end();

        // 2. Draw Main Body (Gradient)
        sr.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < segments - 1; i++) {
            float t = i / (float) segments;
            // Gradient from dark purple to lighter magenta
            sr.setColor(0.3f + t * 0.4f, 0.1f, 0.4f + t * 0.2f, 1f);

            float lineThickness = thickness[i + 1];
            sr.rectLine(pos[i], pos[i + 1], lineThickness);

            // Draw suction cup circle at segment i
            // Make cups slightly lighter
            sr.setColor(0.5f + t * 0.3f, 0.2f, 0.6f + t * 0.2f, 1f);
            float cupSize = lineThickness * 0.4f * suctionScale[i];
            sr.circle(pos[i].x, pos[i].y, cupSize);
        }

        // Draw final suction cup at tip
        sr.setColor(0.9f, 0.3f, 0.8f, 1f); // Bright tip
        float tipCupSize = thickness[segments - 1] * 0.4f * suctionScale[segments - 1];
        sr.circle(pos[segments - 1].x, pos[segments - 1].y, tipCupSize);

        sr.end();
    }

    // ===== Convenience Methods =====

    public void setTargetPoint(float x, float y) {
        // This is now handled in update(delta, x, y) but kept for compatibility if
        // needed
        // or we can remove it. For now, let's just update the tempTarget.
        tempTarget.set(x, y);
    }

    /**
     * Update curl detection state. This computes whether the tentacle has formed
     * a closed loop by checking if the tip is close to earlier segments.
     */
    private void updateCurlDetection() {
        // To detect a "closed curl", we check if the tip is close to any segment
        // in the middle portion of the tentacle (not the base, not the very tip)
        isCurledCached = false;
        hasFullCurlCached = false;
        
        if (segments < 10) {
            return; // Too few segments to form a meaningful curl
        }
        
        Vector2 tip = pos[segments - 1];
        
        // Check if tip has curled back toward middle segments
        int checkStart = segments / 3; // Start checking from 1/3 of the way
        int checkEnd = (2 * segments) / 3; // Check up to 2/3 of the way
        
        float closestDist = Float.MAX_VALUE;
        int closestSegment = -1;
        
        for (int i = checkStart; i < checkEnd; i++) {
            float dist = tip.dst(pos[i]);
            if (dist < closestDist) {
                closestDist = dist;
                closestSegment = i;
            }
        }
        
        // If tip is close enough to a middle segment, we have a curl
        float curlThresholdDist = segmentLength * 3f; // Within 3 segment lengths
        if (closestDist < curlThresholdDist && closestSegment > 0) {
            isCurledCached = true;
            
            // Calculate curl center as midpoint between tip and closest segment
            curlCenter.set(pos[closestSegment]).add(tip).scl(0.5f);
            
            // Calculate radius as distance from center to tip plus margin
            curlRadius = curlCenter.dst(tip) + segmentLength * 2f;
            
            // Check if we have a "full curl" by seeing how closed the loop is
            // Count how many segments are within the curl radius
            int segmentsInCurl = 0;
            for (int i = closestSegment; i < segments; i++) {
                if (curlCenter.dst(pos[i]) < curlRadius) {
                    segmentsInCurl++;
                }
            }
            
            // Full curl if majority of the tail segments are in the loop
            float curlCompleteness = segmentsInCurl / (float)(segments - closestSegment);
            hasFullCurlCached = curlCompleteness >= curlDetectionThreshold;
        } else {
            // No curl detected
            curlCenter.set(tip);
            curlRadius = 40f;
        }
    }

    public Vector2 getCurlCenter() {
        return curlCenter;
    }

    public float getCurlRadius() {
        return curlRadius;
    }

    public boolean isCurled() {
        return isCurledCached;
    }

    public boolean hasFullCurl() {
        return hasFullCurlCached;
    }

    public boolean isPlayerCaptured() {
        return isPlayerCaptured;
    }

    public void setPlayerCaptured(boolean captured) {
        if (dead || staticMode) return;
        this.isPlayerCaptured = captured;
        if (captured) {
            captureHitCounter = 0;           // reset 3‑hit release counter only
            hasCapturedThisCurl = true;
        }
    }
    
    /**
     * Release the player from capture.
     */
    public void releasePlayer() {
        isPlayerCaptured = false;
        captureHitCounter = 0; // reset per-capture count
    }
    
    /**
     * Check if this tentacle can capture the player right now.
     * Returns false if already captured or already captured this curl cycle.
     */
    public boolean canCapture() {
        return !isPlayerCaptured && !hasCapturedThisCurl && hasFullCurl();
    }
    
    /**
     * Get the number of hits taken during current capture.
     */
    public int getHitsThisCapture() { return captureHitCounter; }

    /** Set static mode (LevelMaker). */
    public void setStaticMode(boolean staticMode) { this.staticMode = staticMode; }
    public boolean isStaticMode() { return staticMode; }

    /** Provide target array for diamond drops on death. */
    public void setDropTarget(com.badlogic.gdx.utils.Array<com.jjmc.chromashift.environment.collectible.Collectible> target) {
        this.dropTarget = target;
    }

    /** Respawn/reset full health (editor rebuild or game respawn). */
    public void respawn() {
        hitPointsRemaining = maxHits;
        dead = false;
        isPlayerCaptured = false;
        captureHitCounter = 0;
        hasCapturedThisCurl = false;
    }

    // ===== Getters for tweaking parameters at runtime (optional) =====
    public void setSpringStiffness(float stiffness) {
        this.springStiffness = stiffness;
    }

    public void setSpringDamping(float damping) {
        this.springDamping = damping;
    }

    public void setGlobalDamping(float damping) {
        this.globalDamping = damping;
    }

    public void setTargetAttract(float attract) {
        this.targetAttract = attract;
    }

    public void setMaxVelocity(float maxVel) {
        this.maxVelocity = maxVel;
    }

    public void setSuctionWaveSpeed(float speed) {
        this.suctionWaveSpeed = speed;
    }

    public void setSuctionWaveOffset(float offset) {
        this.suctionWaveOffset = offset;
    }

    public void setTipFollowLerp(float lerp) {
        this.tipFollowLerp = lerp;
    }

    public void setWaveBaseAmplitude(float a) {
        this.waveBaseAmplitude = a;
    }

    public void setWaveTipFactor(float f) {
        this.waveTipFactor = f;
    }

    public void setIdleWaveFrequency(float freq) {
        this.idleWaveFrequency = freq;
    }

    public void setIdleWavePropagation(float prop) {
        this.idleWavePropagation = prop;
    }

    public void setMouseInfluence(float influence) {
        this.mouseInfluence = influence;
    }

    public void setMouseDistanceThreshold(float threshold) {
        this.mouseDistanceThreshold = threshold;
    }

    // ===== Enemy Interface Implementation =====

    @Override
    public void takeDamage(float damage) {
        // Compatibility wrapper: treat any damage as 1 hit
        applyHit(1);
    }
    
    /**
     * Apply hit-based damage. Ignores damage values - only counts hits.
     * Regular attacks = 1 hit, Skill attacks = 2-3 hits.
     * Tentacle always requires exactly 6 regular hits to die.
     * @param hitValue Number of hits to apply (1 for regular, 2-3 for skills)
     */
    public void applyHit(int hitValue) {
        if (dead) return;
        if (hitValue <= 0) return;
        
        hitPointsRemaining -= hitValue;
        if (hitPointsRemaining < 0) hitPointsRemaining = 0;
        
        if (isPlayerCaptured) {
            captureHitCounter += hitValue; // Per-capture release counter
            if (captureHitCounter >= 3) {
                releasePlayer();
            }
        }
        
        if (hitPointsRemaining <= 0) {
            die();
        }
    }
    
    /**
     * Apply damage to a specific segment of the tentacle.
     * Note: Damage value is ignored - converted to 1 hit for hit-based system.
     * @param damage Ignored - treated as 1 hit
     * @param segmentIndex Index of the segment that was hit
     */
    public void applyDamage(int damage, int segmentIndex) {
        if (segmentIndex < 0 || segmentIndex >= segments || dead) return;
        applyHit(1); // Regular attacks always count as 1 hit
    }
    
    /**
     * Apply hit-based damage to a specific segment (for skills).
     * @param hitValue Number of hits (1 for regular, 2-3 for skills)
     * @param segmentIndex Index of the segment that was hit
     */
    public void applyHitToSegment(int hitValue, int segmentIndex) {
        if (segmentIndex < 0 || segmentIndex >= segments || dead) return;
        applyHit(hitValue);
    }
    
    /**
     * Get segment hitbox for collision detection.
     */
    public com.badlogic.gdx.math.Circle getSegmentHitbox(int index) {
        if (index < 0 || index >= segments) return null;
        return segmentHitboxes[index];
    }
    
    /**
     * Get all segment hitboxes.
     */
    public com.badlogic.gdx.math.Circle[] getSegmentHitboxes() {
        return segmentHitboxes;
    }

    @Override
    public float getHealth() { return hitPointsRemaining; }

    @Override
    public boolean isAlive() { return !dead; }

    @Override
    public Rectangle getBounds() {
        // Calculate bounds encompassing all segments
        if (segments == 0) {
            bounds.set(anchor.x, anchor.y, 0, 0);
            return bounds;
        }

        float minX = pos[0].x;
        float maxX = pos[0].x;
        float minY = pos[0].y;
        float maxY = pos[0].y;

        for (int i = 1; i < segments; i++) {
            if (pos[i].x < minX) minX = pos[i].x;
            if (pos[i].x > maxX) maxX = pos[i].x;
            if (pos[i].y < minY) minY = pos[i].y;
            if (pos[i].y > maxY) maxY = pos[i].y;
        }

        // Add padding for thickness
        float maxThickness = thickness[0];
        bounds.set(minX - maxThickness, minY - maxThickness, 
                   (maxX - minX) + maxThickness * 2, (maxY - minY) + maxThickness * 2);
        return bounds;
    }

    /**
     * Reset tentacle to full health.
     */
    public void resetHealth() { respawn(); }
    
    /**
     * Get maximum hit points.
     */
    public int getMaxHits() { return maxHits; }

    private void die() {
        if (dead) return;
        releasePlayer();
        dead = true;
        if (dropTarget != null) {
            int count = 3 + com.badlogic.gdx.math.MathUtils.random(0,2);
            float tipX = pos[segments - 1].x;
            float tipY = pos[segments - 1].y;
            float spread = 28f;
            for (int i = 0; i < count; i++) {
                float ox = tipX + com.badlogic.gdx.math.MathUtils.random(-spread/2f, spread/2f);
                float oy = tipY + com.badlogic.gdx.math.MathUtils.random(-spread/2f, spread/2f);
                dropTarget.add(new com.jjmc.chromashift.environment.collectible.Diamond(ox, oy));
            }
        }
    }

    /**
     * Get the segment count of this tentacle.
     * @return Number of segments
     */
    public int getSegmentCount() { return segments; }
    public boolean isSleeping() { return sleeping; }
    
    /**
     * Get the unique ID of this tentacle.
     * @return Unique ID string
     */
    public String getUniqueId() { return uniqueId; }
    public void setUniqueId(String id) { if (id != null && !id.isEmpty()) this.uniqueId = id; }

    /** Convenience: tip X */
    public float getTipX() { return pos[segments - 1].x; }
    /** Convenience: tip Y */
    public float getTipY() { return pos[segments - 1].y; }
}