package com.jjmc.chromashift.player.skill;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.jjmc.chromashift.player.Player;

/**
 * Launch upward with strong vertical velocity; no invul.
 */
public class JumpSkill extends BaseSkill {
    private com.chromashift.helper.SpriteAnimator animator;
    private final float JUMP_VELOCITY = 350f; // upward boost
    private final float MAX_UPWARD_VELOCITY = 700f; // cap (allows launchpad stack)
    private boolean hasAppliedVelocity = false;
    
    public JumpSkill(Player player) {
        super(player, "JumpSkill", 1.5f); // 1 second cooldown
        this.totalAnimationTime = 0.3f; // 5 frames at 0.06s each
        
        // Load jump anim
        try {
            animator = new com.chromashift.helper.SpriteAnimator("player/sfx/skill_jump.png", 1, 5);
            animator.addAnimation("jump", 0, 0, 5, 0.06f, false);
        } catch (Exception e) {
            Gdx.app.error("JumpSkill", "Failed to load skill_jump.png", e);
        }
    }
    
    @Override
    public void activate() {
        if (!canCast()) return;
        
        isActive = true;
        animationTimer = 0f;
        hasAppliedVelocity = false;
        requestInvulnerability = false;
        requestInvisibility = false;
        // No dash reset; jump reset only
        resetDash = false;
        resetJump = true;
        
        // Play anim
        if (animator != null) {
            animator.play("jump", false);
        }

        // Play sfx
        try {
            com.chromashift.helper.SoundManager.play("JumpSkill");
        } catch (Exception ignored) {}
        
        // log removed
    }
    
    @Override
    protected void updateActive(float delta) {
        if (!isActive || animator == null) return;
        
        // Anim tick
        animator.update(delta);
        
        // Boost velocity (stacks with launchpad)
        if (!hasAppliedVelocity) {
            float currentVelocityY = player.getVelocityY();
            float newVelocityY = currentVelocityY + JUMP_VELOCITY;
            
            // Cap at max to avoid excessive stack
            if (newVelocityY > MAX_UPWARD_VELOCITY) {
                newVelocityY = MAX_UPWARD_VELOCITY;
            }
            
            player.setVelocityY(newVelocityY);
            player.setOnGround(false);
            hasAppliedVelocity = true;
        }
    }
    
    @Override
    public void render(SpriteBatch batch) {
        if (!isActive || animator == null) return;
        // Draw at hitbox center
        float centerX = player.getHitboxX() + player.getHitboxWidth() / 2f;
        float centerY = player.getHitboxY() + player.getHitboxHeight() / 2f;
        animator.render(batch, centerX - 24f, centerY - 24f, 48f, 48f);
    }
    
    @Override
    public void deactivate() {
        isActive = false;
        animationTimer = 0f;
        currentCooldown = cooldownTime;
        
        // Apply jump reset
        if (resetJump) {
            player.setCanJump(true);
        }
        
        // log removed
    }
    
    public void dispose() {
        if (animator != null) {
            animator.dispose();
        }
    }
}
