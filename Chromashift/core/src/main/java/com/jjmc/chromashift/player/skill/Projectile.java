package com.jjmc.chromashift.player.skill;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Array;
import com.chromashift.helper.SpriteAnimator;
import com.jjmc.chromashift.environment.Solid;
import com.jjmc.chromashift.environment.enemy.Enemy;
import com.jjmc.chromashift.player.Player;

/**
 * Projectile for skills. Moves, animates, hits stuff.
 */
public class Projectile {
    private float x, y;
    private float width = 32f, height = 32f;
    private Vector2 direction = new Vector2(1f, 0f);
    private float speed = 200f;
    private float damage = 10f;
    private SpriteAnimator animator;
    private boolean isActive = true;
    private Player owner;
    private float lifetime = 0f;
    private float maxLifetime = 10f; // Max 10 seconds before auto-destroy
    private boolean hasHit = false;
    // Rotation (deg) for drawing
    private float rotationDeg = 0f;
    
    // Perp wobble (shuriken)
    private boolean useSineWave = false;
    private float sineTime = 0f;
    private float sineAmplitude = 12f; // Wobble amplitude (smaller by default)
    private float sineFrequency = 3f; // Wobbles per second
    private float baseX = 0f; // Base position along travel path
    private float baseY = 0f; // Base position along travel path
    
    // Split timer for the main shot
    private boolean isMainProjectile = false;
    private float splitTimer = 0f;
    private float splitDelay = 1.5f; // splits after this if it doesn't hit
    private boolean splitTriggered = false; // avoid double-spawn
    
    // Homing for split shots
    private boolean isHoming = false;
    private float maxTurnRatePerSecond = 180f; // max turn speed
    private Enemy currentTarget = null;
    
    public Projectile(float x, float y, Vector2 direction, float speed, float damage,
                     String spritePath, int frameCount, float frameDuration, Player owner) {
        this.x = x;
        this.y = y;
        this.baseX = x; // Store initial base position for sine wave
        this.baseY = y; // Store initial base position for sine wave
        this.direction = direction.nor();
        // compute rotation based on direction
        this.rotationDeg = this.direction.angleDeg();
        this.speed = speed;
        this.damage = damage;
        this.owner = owner;
        
        // Sprite anim
        try {
            animator = new SpriteAnimator(spritePath, 1, frameCount);
            animator.addAnimation("fly", 0, 0, frameCount, frameDuration, true);
            animator.play("fly", false);
        } catch (Exception e) {
            Gdx.app.error("Projectile", "Failed to load projectile sprite: " + spritePath, e);
        }
    }
    
    /** Mark as the main shot that can split. */
    public void setMainProjectile(boolean isMain, float splitDelay) {
        this.isMainProjectile = isMain;
        this.splitDelay = splitDelay;
        this.splitTimer = 0f;
        this.splitTriggered = false;
    }
    
    /** Enable/disable homing. */
    public void setHoming(boolean homing) {
        this.isHoming = homing;
    }
    
    /** Enable shuriken wobble. */
    public void setSineWave(boolean enabled, float amplitude, float frequency) {
        this.useSineWave = enabled;
        this.sineAmplitude = amplitude;
        this.sineFrequency = frequency;
    }
    
    /** Set homing turn rate (deg/sec). */
    public void setMaxTurnRate(float degreesPerSecond) {
        this.maxTurnRatePerSecond = degreesPerSecond;
    }
    
    /** Main shot timed out and didn't hit? */
    public boolean shouldSplit() {
        return isMainProjectile && splitTriggered && !hasHit;
    }
    
    /** Current center position. */
    public Vector2 getPosition() {
        posCache.set(x + width / 2f, y + height / 2f);
        return posCache;
    }
    /** Current direction. */
    public Vector2 getDirection() {
        dirCache.set(direction);
        return dirCache;
    }
    /** Copy for external use that needs new instance */
    public Vector2 getPositionCopy() {
        return new Vector2(x + width / 2f, y + height / 2f);
    }
    public Vector2 getDirectionCopy() {
        return new Vector2(direction);
    }
    public void update(float delta, Array<Solid> solids, Array<Enemy> enemies) {
        if (!isActive) return;
        
        lifetime += delta;
        if (lifetime > maxLifetime) {
            isActive = false;
            return;
        }
        
        // Split timer (main shot)
        if (isMainProjectile && !hasHit && !splitTriggered) {
            splitTimer += delta;
            if (splitTimer >= splitDelay) {
                // Time to split: flip flag and end this one
                splitTriggered = true;
                // log removed
                isActive = false;
                return;
            }
        }
        
        // Homing (split shots) - reuse temp vectors
        if (isHoming && enemies != null) {
            if (currentTarget == null || !currentTarget.isAlive()) {
                currentTarget = findClosestEnemy(enemies);
            }
            if (currentTarget != null && currentTarget.isAlive()) {
                tmpVec1.set(x + width / 2f, y + height / 2f);
                Rectangle targetBounds = currentTarget.getBounds();
                tmpVec2.set(targetBounds.x + targetBounds.width / 2f, targetBounds.y + targetBounds.height / 2f);
                tmpVec2.sub(tmpVec1).nor();
                float currentAngle = direction.angleDeg();
                float desiredAngle = tmpVec2.angleDeg();
                float angleDiff = desiredAngle - currentAngle;
                while (angleDiff > 180f) angleDiff -= 360f;
                while (angleDiff < -180f) angleDiff += 360f;
                float maxTurnThisFrame = maxTurnRatePerSecond * delta;
                float turnAmount = Math.max(-maxTurnThisFrame, Math.min(maxTurnThisFrame, angleDiff));
                float newAngle = currentAngle + turnAmount;
                direction.set(1f, 0f).rotateDeg(newAngle).nor();
                rotationDeg = newAngle;
            }
        }
        
        // Move
        float moveX = direction.x * speed * delta;
        float moveY = direction.y * speed * delta;

        if (useSineWave) {
            // Step along the path
            baseX += moveX;
            baseY += moveY;

            tmpPerp.set(-direction.y, direction.x).nor();

            // Sine offset perpendicular to path
            sineTime += delta;
            float sineOffset = (float) Math.sin(sineTime * sineFrequency * 2f * Math.PI) * sineAmplitude;

            x = baseX + tmpPerp.x * sineOffset;
            y = baseY + tmpPerp.y * sineOffset;
        } else {
            // No wobble: straight move
            x += moveX;
            y += moveY;
        }
        
        // Anim tick
        if (animator != null) {
            animator.update(delta);
        }
        
        // Solids
        Rectangle projBounds = getBounds();
        for (Solid solid : solids) {
            Rectangle solidBounds = solid.getBounds();
            if (solidBounds != null && solidBounds.overlaps(projBounds)) {
                if (isMainProjectile) {
                    // log removed
                }
                isActive = false;
                hasHit = true;
                splitTriggered = false; // no split on hit
                return;
            }
        }
        
        // Enemies
        for (Enemy enemy : enemies) {
            Rectangle enemyBounds = enemy.getBounds();
            if (enemyBounds != null && enemyBounds.overlaps(projBounds)) {
                // Skip owner
                if (enemyBounds.x != owner.getX() || enemyBounds.y != owner.getY()) {
                    // Projectiles count as 2 hits for tentacles
                    if (enemy instanceof com.jjmc.chromashift.environment.enemy.Tentacle) {
                        ((com.jjmc.chromashift.environment.enemy.Tentacle) enemy).applyHit(2);
                    } else {
                        enemy.takeDamage((int) damage);
                    }
                    if (isMainProjectile) {
                        // log removed
                    }
                    isActive = false;
                    hasHit = true;
                    splitTriggered = false; // no split on hit
                    return;
                }
            }
        }
    }
    
    /** Closest living enemy. */
    private Enemy findClosestEnemy(Array<Enemy> enemies) {
        if (enemies == null || enemies.size == 0) return null;
        Enemy closest = null;
        float closestDist = Float.MAX_VALUE;
        tmpVec1.set(x + width / 2f, y + height / 2f);
        for (Enemy enemy : enemies) {
            if (!enemy.isAlive()) continue;
            Rectangle bounds = enemy.getBounds();
            tmpVec2.set(bounds.x + bounds.width / 2f, bounds.y + bounds.height / 2f);
            float dist = tmpVec1.dst(tmpVec2);
            if (dist < closestDist) {
                closestDist = dist;
                closest = enemy;
            }
        }
        return closest;
    }
    
    public void render(SpriteBatch batch) {
        if (!isActive || animator == null) return;
        TextureRegion region = animator.getCurrentFrameRegion();
        if (region == null) return;
        // Draw rotated at center
        batch.draw(region, x, y, width / 2f, height / 2f, width, height, 1f, 1f, rotationDeg);
    }
    
    private final Rectangle boundsCache = new Rectangle();
    private final Vector2 posCache = new Vector2();
    private final Vector2 dirCache = new Vector2();
    public Rectangle getBounds() {
        boundsCache.set(x, y, width, height);
        return boundsCache;
    }
    
    public boolean isActive() {
        return isActive;
    }
    
    public boolean isFinished() {
        return !isActive;
    }
    
    public boolean hasHit() {
        return hasHit;
    }
    
    public void setWidth(float width) {
        this.width = width;
    }
    
    public void setHeight(float height) {
        this.height = height;
    }
    
    public void dispose() {
        if (animator != null) {
            animator.dispose();
        }
    }
}
