package com.jjmc.chromashift.player.skill;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Vector2;
import com.jjmc.chromashift.player.Player;
import com.badlogic.gdx.utils.Array;

/**
 * Fire a shot that splits into three if it doesn't hit.
 */
public class SplitSkill extends BaseSkill {
    private com.chromashift.helper.SpriteAnimator animator;
    private Projectile mainProjectile;
    private final float PROJECTILE_SPEED = 280f;
    private final float SPLIT_PROJECTILE_SPEED = 240f;
    private final int MAIN_DAMAGE = 25;
    private final int SPLIT_DAMAGE = 10;
    private Array<Projectile> projectiles = new Array<>();
    private final float SPLIT_SPREAD_ANGLE = 20f; // fan: -20, 0, +20 deg
    private final float SPLIT_DELAY = 1f; // wait before splitting
    private final float HOMING_TURN_RATE = 35f; // reduced turn speed for fairness
    
    public SplitSkill(Player player) {
        super(player, "SplitSkill", 1.2f); // 1.2 second cooldown
        // Stay alive long enough to watch for the split
        this.totalAnimationTime = 2.0f;
        
        // Load cast anim
        try {
            animator = new com.chromashift.helper.SpriteAnimator("player/sfx/skill_split.png", 1, 4);
            animator.addAnimation("cast", 0, 0, 4, 0.06f, false);
        } catch (Exception e) {
            Gdx.app.error("SplitSkill", "Failed to load skill_split.png", e);
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
        
        // Fire main shot (spawns above the head)
        try {
            mainProjectile = new Projectile(
                player.getX() + player.getHitboxWidth() / 2f - 16f,
                player.getY() + player.getHitboxHeight() + 8f, // above head
                direction,
                PROJECTILE_SPEED,
                MAIN_DAMAGE,
                "player/sfx/skill_split.png",
                4,
                0.06f,
                player
            );
            mainProjectile.setWidth(32f);
            mainProjectile.setHeight(32f);
            mainProjectile.setMainProjectile(true, SPLIT_DELAY);
            projectiles.add(mainProjectile);
            player.activeProjectiles.add(mainProjectile);
        } catch (Exception e) {
            Gdx.app.error("SplitSkill", "Failed to create main projectile", e);
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
        
        // Track the main shot
        if (mainProjectile != null && mainProjectile.isActive()) {
            mainProjectile.update(delta, player.getSolids(), player.getEnemies());
        } else if (mainProjectile != null && !mainProjectile.isActive()) {
            // Split? (timer up, no hit)
            boolean shouldSplit = mainProjectile.shouldSplit();
            boolean hasHit = mainProjectile.hasHit();
            
            // log removed
            
            if (shouldSplit) {
                // Spawn the three here
                spawnSplitProjectiles();
                // log removed
            } else if (hasHit) {
                // log removed
            } else {
                // log removed
            }
            mainProjectile = null;
            
            // Projectile is done, deactivate skill now
            deactivate();
        }
    }
    
    private void spawnSplitProjectiles() {
        if (mainProjectile == null) {
            // error log removed - fail silently
            return;
        }
        
        // Keep position/dir before clearing
        Vector2 spawnPos = mainProjectile.getPositionCopy();
        Vector2 mainDir = mainProjectile.getDirectionCopy();
        
        // log removed
        
        int spawnedCount = 0;
        
        // Make 3 homing shots in a fan
        float[] angles = { -SPLIT_SPREAD_ANGLE, 0f, SPLIT_SPREAD_ANGLE };
        for (float angleOffset : angles) {
            try {
            // Spread from the original direction
                Vector2 spreadDir = mainDir.cpy().rotateDeg(angleOffset).nor();
                
                // log removed
                
                // Spawn a homing split projectile
                Projectile split = new Projectile(
                    spawnPos.x - 16f, // center 32x32
                    spawnPos.y - 16f,
                    spreadDir,
                    SPLIT_PROJECTILE_SPEED,
                    SPLIT_DAMAGE,
                    "player/sfx/the_split.png",
                    4,
                    0.06f,
                    player
                );
                split.setWidth(32f);
                split.setHeight(32f);
                split.setHoming(true);
                split.setMaxTurnRate(HOMING_TURN_RATE);
                
                // Track it
                projectiles.add(split);
                player.activeProjectiles.add(split);
                spawnedCount++;
                
                // log removed
            } catch (Exception e) {
                Gdx.app.error("SplitSkill", "Failed to create split projectile at angle " + angleOffset, e);
            }
        }
        
        // log removed
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
        mainProjectile = null;
        
        // log removed
    }
    
    private Vector2 getMouseWorldPosition() {
        try {
            com.badlogic.gdx.math.Vector3 mouseVec = new com.badlogic.gdx.math.Vector3(Gdx.input.getX(), Gdx.input.getY(), 0);
            java.lang.reflect.Field camField = player.getClass().getDeclaredField("gameCamera");
            camField.setAccessible(true);
            com.badlogic.gdx.graphics.Camera cam = (com.badlogic.gdx.graphics.Camera) camField.get(player);
            if (cam != null) {
                cam.unproject(mouseVec);
                return new Vector2(mouseVec.x, mouseVec.y);
            }
        } catch (Exception ignored) {}
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
