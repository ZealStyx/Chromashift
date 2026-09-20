package com.jjmc.chromashift.player.skill;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.Array;
import com.chromashift.helper.SpriteAnimator;
import com.jjmc.chromashift.environment.enemy.Enemy;
import com.jjmc.chromashift.environment.Solid;
import com.jjmc.chromashift.environment.Wall;
import com.jjmc.chromashift.player.Player;
import com.jjmc.chromashift.player.PlayerCollision;

/**
 * Quick horizontal dash; invulnerable, invisible, damages through.
 */
public class DashSkill extends BaseSkill {
    private com.chromashift.helper.SpriteAnimator animator;
    private final float DASH_DISTANCE = 176f; // Total dash distance
    private float dashStartX = 0f;
    private float dashStartY = 0f;
    private float currentDashX = 0f;
    // dashTargetCenterX is the center X used for animation; dashTargetX is final left X for teleport
    private float dashTargetCenterX = 0f;
    private float dashTargetX = 0f;
    private Array<Enemy> damagedEnemies = new Array<>();
    private final float DASH_SPEED = 500f; // pixels per second during dash animation
    private final int DASH_DAMAGE = 25;
    
    public DashSkill(Player player) {
        super(player, "DashSkill", 2.5f); // 2.5 second cooldown
        this.totalAnimationTime = 0.36f; // 6 frames at ~60fps = ~0.1s per frame * 6
        
        // Load dash anim
        try {
            animator = new com.chromashift.helper.SpriteAnimator("player/sfx/skill_dash.png", 1, 6);
            animator.addAnimation("dash", 0, 0, 6, 0.06f, false);
        } catch (Exception e) {
            Gdx.app.error("DashSkill", "Failed to load skill_dash.png", e);
        }
    }
    
    @Override
    public void activate() {
        if (!canCast()) return;
        
        isActive = true;
        animationTimer = 0f;
        // Anchor at hitbox center
        float playerLeft = player.getHitboxX();
        float playerBottom = player.getHitboxY();
        dashStartX = playerLeft + player.getHitboxWidth() / 2f;
        dashStartY = playerBottom + player.getHitboxHeight() / 2f;
        currentDashX = 0f;
        damagedEnemies.clear();
        requestInvulnerability = true;
        requestInvisibility = true;
        // Ensure immediate i-frames at activation to prevent same-frame damage
        try {
            if (player != null && player.getHealthSystem() != null) {
                player.getHealthSystem().setInvulnerable(true);
            }
        } catch (Throwable ignored) {}
        // Lock movement during dash
        requestMovementLock = true;
        // Reset jump only
        resetDash = false;
        resetJump = true;

        // Play anim
        if (animator != null) {
            // Flip based on facing (sprite right->left)
            boolean facingLeft = player.isFacingLeft();
            animator.play("dash", !facingLeft);
        }

        // Disable gravity while active
        requestDisableGravity = true;

        // Play sfx
        try {
            com.chromashift.helper.SoundManager.play("DashSkill");
        } catch (Exception ignored) {}

        // Compute dash target and damage along path
        boolean facingLeft = player.isFacingLeft();
        float direction = facingLeft ? -1f : 1f;
        float intendedCenterTarget = dashStartX + (direction * DASH_DISTANCE);
        dashTargetCenterX = intendedCenterTarget;
        
        // Only consider solids that are in front of the player (forward raycast)
        float rayStart = dashStartX;
        float rayEnd = intendedCenterTarget;
        float rayMin = Math.min(rayStart, rayEnd);
        float rayMax = Math.max(rayStart, rayEnd);
        
        // Use full hitbox for vertical overlap check
        com.badlogic.gdx.math.Rectangle playerHitbox = player.getHitboxRect();
        for (Solid solid : player.getSolids()) {
            if (!solid.isBlocking()) continue;
            Rectangle b = solid.getCollisionBounds();
            if (b == null) b = solid.getBounds();
            if (b == null) continue;
            // vertical overlap with player full hitbox
            if (b.y > playerHitbox.y + playerHitbox.height || b.y + b.height < playerHitbox.y) continue;
            
            // horizontal overlap with dash ray
            float solidLeft = b.x;
            float solidRight = b.x + b.width;
            
            // only solids in path
            if (solidRight < rayMin || solidLeft > rayMax) continue;
            
            if (facingLeft) {
                // left: stop at solid's right
                if (solidRight <= rayStart && solidRight >= rayEnd) {
                    float newTarget = solidRight + player.getHitboxWidth() / 2f;
                    if (newTarget > dashStartX) newTarget = dashStartX;
                    if (Math.abs(dashStartX - newTarget) < Math.abs(dashStartX - dashTargetCenterX)) {
                        dashTargetCenterX = newTarget;
                    }
                }
            } else {
                // right: stop at solid's left
                if (solidLeft >= rayStart && solidLeft <= rayEnd) {
                    float newTarget = solidLeft - player.getHitboxWidth() / 2f - 1f;
                    if (newTarget < dashStartX) newTarget = dashStartX;
                    if (Math.abs(dashStartX - newTarget) < Math.abs(dashStartX - dashTargetCenterX)) {
                        dashTargetCenterX = newTarget;
                    }
                }
            }
        }

        // Convert center -> left X
        dashTargetX = dashTargetCenterX - player.getHitboxWidth() / 2f;

        // Sweep bounds to hit enemies
        float dashBoundsLeft = Math.min(dashStartX, dashTargetCenterX) - player.getHitboxWidth() / 2f;
        Rectangle dashBounds = new Rectangle(dashBoundsLeft, playerBottom, Math.abs(dashTargetCenterX - dashStartX) + player.getHitboxWidth(), player.getHitboxHeight());
        for (Enemy enemy : player.getEnemies()) {
            Rectangle eb = enemy.getBounds();
            if (eb == null) continue; // enemy may be dead or non-collidable
            if (eb.overlaps(dashBounds) && !damagedEnemies.contains(enemy, true)) {
                enemy.takeDamage(DASH_DAMAGE);
                damagedEnemies.add(enemy);
            }
        }

        Gdx.app.log("DashSkill", "Activated!");
    }
    
    @Override
    protected void updateActive(float delta) {
        if (!isActive || animator == null) return;

        // Advance anim at anchor
        animator.update(delta);
        // Compute signed offset by progress
        float progress = Math.min(1f, animationTimer / totalAnimationTime);
        float dashDistanceActual = dashTargetCenterX - dashStartX; // signed distance
        currentDashX = progress * dashDistanceActual; // signed offset
    }
    
    @Override
    public void render(SpriteBatch batch) {
        if (!isActive || animator == null) return;

        // Draw at anchored center
        float renderCenterX = dashStartX + currentDashX;
        // Center vertically
        float animW = 96f;
        float animH = 96f;
        animator.render(batch, renderCenterX - animW / 2f, dashStartY - animH / 2f, animW, animH);
    }
    
    @Override
    public void deactivate() {
        isActive = false;
        requestInvulnerability = false;
        requestInvisibility = false;
        hasMovementOverride = false;
        requestMovementLock = false;
        animationTimer = 0f;
        currentCooldown = cooldownTime;

        // End of i-frames: clear HealthSystem invulnerability unless respawn is active
        try {
            if (player != null && player.getHealthSystem() != null && player.getRespawnInvulRemaining() <= 0f) {
                player.getHealthSystem().setInvulnerable(false);
            }
        } catch (Throwable ignored) {}

        // Apply resets
        if (resetDash) {
            player.resetDash();
        }
        if (resetJump) {
            player.setCanJump(true);
        }

        // Resolve collisions at target; avoid embedding in solids
        try {
            // dashTargetX = hitbox left
            Rectangle candidate = new Rectangle(dashTargetX, player.getHitboxY(), player.getHitboxWidth(), player.getHitboxHeight());
            // Collect walls
            Array<Wall> walls = new Array<>();
            for (Solid s : player.getSolids()) {
                if (s instanceof Wall w) walls.add(w);
            }
            // Resolve collisions
            PlayerCollision.resolveWallCollision(candidate, walls);
            PlayerCollision.resolveSolidCollision(candidate, player.getSolids());

            // Apply resolved hitbox -> player pos
            player.setX(candidate.x - player.hitboxOffsetX);
            player.setY(candidate.y - player.hitboxOffsetY);

            // Zero Y and short hover
            player.setVelocityY(0f);
            player.setDashHover(0.15f); // 150ms hover
            player.setOnGround(false);
        } catch (Exception e) {
            // Fallback: direct teleport
            player.setX(dashTargetX - player.hitboxOffsetX);
        }

        Gdx.app.log("DashSkill", "Deactivated!");
    }
    
    public void dispose() {
        if (animator != null) {
            animator.dispose();
        }
    }
}
