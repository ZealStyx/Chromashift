package com.jjmc.chromashift.player.skill;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Rectangle;
import com.jjmc.chromashift.environment.enemy.Enemy;
import com.jjmc.chromashift.player.Player;
import com.badlogic.gdx.utils.Array;

/**
 * AoE slash around player; invul during cast; resets dash/jump if hit.
 */
public class SlashSkill extends BaseSkill {
    private com.chromashift.helper.SpriteAnimator animator;
    private final float AOE_SIZE = 128f;
    private Array<Enemy> hitEnemies = new Array<>();
    private final int SLASH_DAMAGE = 60;
    
    public SlashSkill(Player player) {
        super(player, "SlashSkill", 1.5f); // 1.5 second cooldown
        this.totalAnimationTime = 1f; // 12 frames spread over 0.9 seconds
        
        // Load slash anim
        try {
            animator = new com.chromashift.helper.SpriteAnimator("player/sfx/skill_slash.png", 1, 12);
            animator.addAnimation("slash", 0, 0, 12, 1f / 12f, false);
        } catch (Exception e) {
            Gdx.app.error("SlashSkill", "Failed to load skill_slash.png", e);
        }
    }
    
    @Override
    public void activate() {
        if (!canCast()) return;
        
        isActive = true;
        animationTimer = 0f;
        hitEnemies.clear();
        requestInvulnerability = true;
        // Invisible and locked during slash
        requestInvisibility = true;
        requestMovementLock = true;
        // Suspend and disable gravity
        requestDisableGravity = true;
        resetDash = false;
        resetJump = false;

        // Ensure immediate i-frames at activation to prevent same-frame damage
        try {
            if (player != null && player.getHealthSystem() != null) {
                player.getHealthSystem().setInvulnerable(true);
            }
        } catch (Throwable ignored) {}
        
        // Play anim
        if (animator != null) {
            animator.play("slash", false);
        }

        // Play sfx
        try {
            com.chromashift.helper.SoundManager.play("SlashSkill");
        } catch (Exception ignored) {}
        
        // log removed for performance
    }
    
    @Override
    protected void updateActive(float delta) {
        if (!isActive || animator == null) return;
        
        // Anim tick
        animator.update(delta);
        
        // Build AoE box at hitbox center
        float centerX = player.getHitboxX() + player.getHitboxWidth() / 2f;
        float centerY = player.getHitboxY() + player.getHitboxHeight() / 2f;
        float aoeX = centerX - AOE_SIZE / 2f;
        float aoeY = centerY - AOE_SIZE / 2f;
        Rectangle aoeBounds = new Rectangle(aoeX, aoeY, AOE_SIZE, AOE_SIZE);
        
        // Check hits
        for (Enemy enemy : player.getEnemies()) {
            if (enemy.getBounds().overlaps(aoeBounds) && !hitEnemies.contains(enemy, true)) {
                // Slash = 3 hits for tentacles
                if (enemy instanceof com.jjmc.chromashift.environment.enemy.Tentacle) {
                    ((com.jjmc.chromashift.environment.enemy.Tentacle) enemy).applyHit(3);
                } else {
                    enemy.takeDamage(SLASH_DAMAGE);
                }
                hitEnemies.add(enemy);
            }
        }
    }
    
    @Override
    public void render(SpriteBatch batch) {
        if (!isActive || animator == null) return;
        
        // Draw at hitbox center
        float centerX = player.getHitboxX() + player.getHitboxWidth() / 2f;
        float centerY = player.getHitboxY() + player.getHitboxHeight() / 2f;
        float renderX = centerX - AOE_SIZE / 2f;
        float renderY = centerY - AOE_SIZE / 2f;
        animator.render(batch, renderX, renderY, AOE_SIZE, AOE_SIZE);
    }
    
    @Override
    public void debugDraw(ShapeRenderer shape) {
        if (!isActive) return;
        // Use same center as actual hitbox to match debug with logic
        float centerX = player.getHitboxX() + player.getHitboxWidth() / 2f;
        float centerY = player.getHitboxY() + player.getHitboxHeight() / 2f;
        float aoeX = centerX - AOE_SIZE / 2f;
        float aoeY = centerY - AOE_SIZE / 2f;
        shape.setColor(Color.RED);
        shape.rect(aoeX, aoeY, AOE_SIZE, AOE_SIZE);
    }
    
    @Override
    public void deactivate() {
        isActive = false;
        requestInvulnerability = false;
        requestInvisibility = false;
        requestMovementLock = false;
        animationTimer = 0f;
        currentCooldown = cooldownTime;

        // End of i-frames: clear HealthSystem invulnerability unless respawn is active
        try {
            if (player != null && player.getHealthSystem() != null && player.getRespawnInvulRemaining() <= 0f) {
                player.getHealthSystem().setInvulnerable(false);
            }
        } catch (Throwable ignored) {}
        
        // Reset dash/jump if we hit anything
        if (hitEnemies.size > 0) {
            resetDash = true;
            resetJump = true;
            
            if (resetDash) {
                player.resetDash();
            }
            if (resetJump) {
                player.setCanJump(true);
            }
        }
        
        hitEnemies.clear();
    }
    
    public void dispose() {
        if (animator != null) {
            animator.dispose();
        }
    }
}
