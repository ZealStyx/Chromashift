package com.jjmc.chromashift.player.skill;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Vector2;
import com.jjmc.chromashift.player.Player;
import com.badlogic.gdx.utils.Array;

/**
 * Throw a shuriken toward the mouse.
 */
public class ShurikenSkill extends BaseSkill {
    private com.chromashift.helper.SpriteAnimator animator;
    private Projectile activeProjectile;
    private final float PROJECTILE_SPEED = 300f;
    private final int PROJECTILE_DAMAGE = 12;
    private Array<Projectile> projectiles = new Array<>();
    
    public ShurikenSkill(Player player) {
        super(player, "ShurikenSkill", 0.8f); // 0.8 second cooldown
        this.totalAnimationTime = 0.24f; // 4 frames at 0.06s each
        
        // Load cast anim
        try {
            animator = new com.chromashift.helper.SpriteAnimator("player/sfx/skill_shuriken.png", 1, 4);
            animator.addAnimation("cast", 0, 0, 4, 0.06f, false);
        } catch (Exception e) {
            Gdx.app.error("ShurikenSkill", "Failed to load skill_shuriken.png", e);
        }
    }
    
    @Override
    public void activate() {
        if (!canCast()) return;
        
        isActive = true;
        animationTimer = 0f;
        requestInvulnerability = false;
        requestInvisibility = false;
        
        // Aim at mouse
        Vector2 mousePos = getMouseWorldPosition();
        Vector2 playerPos = new Vector2(player.getX(), player.getY());
        Vector2 direction = mousePos.sub(playerPos);
        
        // Fire the shuriken
        try {
            // Spawn above the head (avoid ground)
            float spawnX = player.getX() + player.getHitboxWidth() / 2f - 16f;
            float spawnY = player.getY() + player.getHitboxHeight() + 8f;
            
            Projectile proj = new Projectile(
                spawnX,
                spawnY,
                direction,
                PROJECTILE_SPEED,
                PROJECTILE_DAMAGE,
                "player/sfx/skill_shuriken.png",
                4,
                0.06f,
                player
            );
            // Perpendicular sine wobble
            proj.setSineWave(true, 12f, 3f); // 12px amplitude, ~3 wobbles/sec
            projectiles.add(proj);
            player.activeProjectiles.add(proj);
            activeProjectile = proj;
        } catch (Exception e) {
            Gdx.app.error("ShurikenSkill", "Failed to create projectile", e);
        }
        
        // Play cast anim
        if (animator != null) {
            animator.play("cast", false);
        }
        
        // log removed
    }
    
    @Override
    protected void updateActive(float delta) {
        if (!isActive || animator == null) return;
        
        // Cast anim tick
        animator.update(delta);
        
        // Step the projectile
        if (activeProjectile != null && activeProjectile.isActive()) {
            activeProjectile.update(delta, player.getSolids(), player.getEnemies());
        } else {
            // Done; end the skill
            animationTimer = totalAnimationTime;
        }
    }
    
    public void updateProjectiles(float delta) {
        for (int i = projectiles.size - 1; i >= 0; --i) {
            Projectile proj = projectiles.get(i);
            if (proj == null || !proj.isActive()) {
                if (proj != null) {
                    player.activeProjectiles.removeValue(proj, true);
                    proj.dispose();
                }
                projectiles.removeIndex(i);
            }
        }
    }
    
    @Override
    public void render(SpriteBatch batch) {
        // Draw cast anim on player
        if (isActive && animator != null) {
            animator.render(batch, player.getX() - 16f, player.getY() - 16f, 32f, 32f);
        }
        
        // Draw projectiles
        for (Projectile proj : projectiles) {
            proj.render(batch);
        }
    }
    
    @Override
    public void deactivate() {
        isActive = false;
        animationTimer = 0f;
        currentCooldown = cooldownTime;
        activeProjectile = null;
        
        // log removed
    }
    
    private Vector2 getMouseWorldPosition() {
        // Use camera unproject if available, otherwise fallback to player facing direction
        try {
            com.badlogic.gdx.math.Vector3 mouseVec = new com.badlogic.gdx.math.Vector3(Gdx.input.getX(), Gdx.input.getY(), 0);
            // Try to get camera from player
            java.lang.reflect.Field camField = player.getClass().getDeclaredField("gameCamera");
            camField.setAccessible(true);
            com.badlogic.gdx.graphics.Camera cam = (com.badlogic.gdx.graphics.Camera) camField.get(player);
            if (cam != null) {
                cam.unproject(mouseVec);
                return new Vector2(mouseVec.x, mouseVec.y);
            }
        } catch (Exception ignored) {}
        // Fallback: aim in facing direction
        float dir = player.isFacingLeft() ? -1f : 1f;
        return new Vector2(player.getX() + dir * 100f, player.getY() + player.getHitboxHeight() / 2f);
    }
    
    public void dispose() {
        if (animator != null) {
            animator.dispose();
        }
        for (Projectile proj : projectiles) {
            proj.dispose();
        }
        projectiles.clear();
    }
}
