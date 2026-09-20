package com.jjmc.chromashift.environment;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.chromashift.helper.SoundManager;
import com.chromashift.helper.SpriteAnimator;
import com.jjmc.chromashift.environment.interactable.Interactable;
import com.jjmc.chromashift.environment.interactable.Box;
import com.jjmc.chromashift.environment.interactable.Orb;
import com.jjmc.chromashift.environment.Solid;
import com.jjmc.chromashift.player.Player;

/**
 * Launchpad that launches the player, boxes, or orbs in a specified direction with set speed.
 * Has a solid base similar to Button that blocks movement.
 * 
 * UP: 64x32 sprite (environment/launchpad/launchpad.png)
 * LEFT/RIGHT: 32x64 sprite (environment/launchpad/launchpad_left_right.png)
 *   - 4 rows, 4 cols
 *   - Rows 0-1: RIGHT (row 0=idle, row 1=extended)
 *   - Rows 2-3: LEFT (row 2=idle, row 3=extended)
 */
public class Launchpad implements Interactable, Solid {
    public enum LaunchDirection {
        UP(0, 1),
        LEFT(-1, 0),
        RIGHT(1, 0);

        public final float dx;
        public final float dy;

        LaunchDirection(float dx, float dy) {
            this.dx = dx;
            this.dy = dy;
        }
    }

    private final Rectangle bounds; // sprite bounds
    private final Rectangle detectionBounds; // activation area
    private final Rectangle solidBounds; // solid base area
    private final LaunchDirection direction;
    private final float launchSpeed;
    private final SpriteAnimator anim;
    
    private boolean isLaunching = false;
    private float launchCooldown = 0f;
    private static final float COOLDOWN_TIME = 0.5f;
    // During a single activation frame, allow launching multiple overlapping objects
    private boolean activationArmed = false;

    /**
     * Create a launchpad at the specified position.
     * @param x Bottom-left X coordinate
     * @param y Bottom-left Y coordinate (base of sprite)
     * @param direction Launch direction
     * @param launchSpeed Launch speed in pixels/second
     */
    public Launchpad(float x, float y, LaunchDirection direction, float launchSpeed) {
        this.direction = direction;
        this.launchSpeed = launchSpeed;

        if (direction == LaunchDirection.UP) {
            // UP: 64x32 horizontal sprite
            float spriteW = 64f, spriteH = 32f;
            this.bounds = new Rectangle(x, y, spriteW, spriteH);
            
            // Detection area: 36x2 at top center
            float detectionW = 36f, detectionH = 2f;
            float detectionX = x + (spriteW - detectionW) / 2f;
            float detectionY = y + 16f;
            this.detectionBounds = new Rectangle(detectionX, detectionY, detectionW, detectionH);
            
            // Solid base: 42x16 at bottom center
            float solidW = 42f, solidH = 16f;
            float solidX = x + (spriteW - solidW) / 2f;
            float solidY = y;
            this.solidBounds = new Rectangle(solidX, solidY, solidW, solidH);
            
            // Animation: 2 rows, 4 frames
            this.anim = new SpriteAnimator("environment/launchpad/launchpad.png", 2, 4);
            this.anim.addAnimation("idle", 0, 0, 1, 1f, true);
            this.anim.addAnimation("extend", 0, 0, 4, 0.015f, false);
            this.anim.addAnimation("retract", 1, 0, 4, 0.015f, false);
            
        } else {
            // LEFT/RIGHT: 32x64 vertical sprite
            float spriteW = 32f, spriteH = 64f;
            this.bounds = new Rectangle(x, y, spriteW, spriteH);
            
            // Detection area and solid area depend on direction
            if (direction == LaunchDirection.LEFT) {
                // Detection on right side (8x36 vertical)
                float detectionW = 16f, detectionH = 20f;
                float detectionX = x + 16f;
                float detectionY = y + (spriteH - detectionH) / 2f;
                this.detectionBounds = new Rectangle(detectionX, detectionY, detectionW, detectionH);
                
                // Solid base on left side (16x42 vertical)
                float solidW = 16f, solidH = 42f;
                float solidX = x;
                float solidY = y + (spriteH - solidH) / 2f;
                this.solidBounds = new Rectangle(solidX, solidY, solidW, solidH);
                
            } else { // RIGHT
                // Detection on left side (8x36 vertical)
                float detectionW = 16f, detectionH = 20f;
                float detectionX = x;
                float detectionY = y + (spriteH - detectionH) / 2f;
                this.detectionBounds = new Rectangle(detectionX, detectionY, detectionW, detectionH);
                
                // Solid base on right side (16x42 vertical)
                float solidW = 16f, solidH = 42f;
                float solidX = x + spriteW - solidW;
                float solidY = y + (spriteH - solidH) / 2f;
                this.solidBounds = new Rectangle(solidX, solidY, solidW, solidH);
            }
            
            // Animation: 4 rows, 4 cols
            // Rows 0-1 = RIGHT (0=idle, 1=extended)
            // Rows 2-3 = LEFT (2=idle, 3=extended)
            this.anim = new SpriteAnimator("environment/launchpad/launchpad_left_right.png", 4, 4);
            if (direction == LaunchDirection.LEFT) {
                this.anim.addAnimation("idle", 2, 0, 1, 1f, true);
                this.anim.addAnimation("extend", 0, 0, 4, 0.015f, false);
                this.anim.addAnimation("retract", 1, 0, 4, 0.015f, false);
            } else { // LEFT
                this.anim.addAnimation("idle", 0, 0, 1, 1f, true);
                this.anim.addAnimation("extend", 2, 0, 4, 0.015f, false);
                this.anim.addAnimation("retract", 3, 0, 4, 0.015f, false);
            }
        }
        
        this.anim.play("idle", true);
    }

    /**
     * Convenience constructor with default launch speed
     */
    public Launchpad(float x, float y, LaunchDirection direction) {
        this(x, y, direction, 
            (direction == LaunchDirection.LEFT || direction == LaunchDirection.RIGHT) ? 900f : 600f); 
    }

    /**
     * Returns sprite bounds (for rendering/general reference)
     */
    public Rectangle getSpriteBounds() {
        return bounds;
    }
    
    /**
     * Returns solid collision bounds (Solid interface - what blocks movement)
     */
    @Override
    public Rectangle getBounds() {
        return solidBounds;
    }
    
    @Override
    public boolean isSolid() {
        return true;
    }
    
    @Override
    public boolean isBlocking() {
        return true;
    }

    public Rectangle getDetectionBounds() {
        return detectionBounds;
    }

    public LaunchDirection getDirection() {
        return direction;
    }

    public float getLaunchSpeed() {
        return launchSpeed;
    }

    @Override
    public void update(float delta) {
        // Reset activation window each frame
        activationArmed = false;
        // Update cooldown timer
        if (launchCooldown > 0f) {
            launchCooldown -= delta;
            if (launchCooldown <= 0f) {
                launchCooldown = 0f;
                isLaunching = false;
            }
        }

        // Update animation
        anim.update(delta);

        // Animation state machine
        if (isLaunching) {
            // If extend animation finished, start retracting
            if (anim.getCurrentAnimationName().equals("extend") && anim.isAnimationFinished()) {
                anim.play("retract", false);
            }
            // If retract animation finished, return to idle
            else if (anim.getCurrentAnimationName().equals("retract") && anim.isAnimationFinished()) {
                anim.play("idle", true);
            }
        }
    }

    /**
     * Check if player is in detection area and launch them immediately.
     * Checks back sensor to prevent launching if player's back is against a wall.
     */
    public void checkAndLaunchPlayer(Player player, com.badlogic.gdx.utils.Array<Wall> walls) {
        boolean canLaunchNow = (launchCooldown <= 0f) || activationArmed;
        if (!canLaunchNow) return;

        Rectangle playerBounds = new Rectangle(
            player.getHitboxX(),
            player.getHitboxY(),
            player.getHitboxWidth(),
            player.getHitboxHeight()
        );

        if (detectionBounds.overlaps(playerBounds)) {
            if (!activationArmed) activationArmed = true;
            launchPlayer(player);
        }
    }

    /**
     * Check if box is in detection area and launch it immediately
     */
    public void checkAndLaunchBox(Box box) {
        boolean canLaunchNow = (launchCooldown <= 0f) || activationArmed;
        if (!canLaunchNow) return;

        if (detectionBounds.overlaps(box.getBounds())) {
            if (!activationArmed) activationArmed = true;
            launchBox(box);
            // If the box is being held, also launch the holder so player isn't skipped by order
            if (box.isHeld()) {
                Player holder = box.getHolder();
                if (holder != null) {
                    launchPlayer(holder);
                }
            }
        }
    }

    /**
     * Check if orb is in detection area and launch it immediately
     */
    public void checkAndLaunchOrb(Orb orb) {
        boolean canLaunchNow = (launchCooldown <= 0f) || activationArmed;
        if (!canLaunchNow) return;

        if (detectionBounds.overlaps(orb.getBounds())) {
            if (!activationArmed) activationArmed = true;
            launchOrb(orb);
            // If the orb is being held, also launch the holder so player isn't skipped by order
            if (orb.isHeld()) {
                Player holder = orb.getHolder();
                if (holder != null) {
                    launchPlayer(holder);
                }
            }
        }
    }

    private void launchPlayer(Player player) {
        // Push horizontally in the launch direction (away from pad)
        float launchVx = -direction.dx * launchSpeed;
        float launchVy = direction.dy * launchSpeed;
        // log removed
        
        // Set player facing direction based on launch direction
        if (direction == LaunchDirection.LEFT) {
            player.setFacingLeft(true);
        } else if (direction == LaunchDirection.RIGHT) {
            player.setFacingLeft(false);
        }
        // For UP direction, keep current facing
        
        // For vertical launch, set Y velocity (replaces existing)
        if (launchVy != 0) {
            player.setVelocityY(launchVy);
        }
        
        // For horizontal launch, ADD to existing X velocity to create arc trajectory
        // This preserves the player's vertical momentum (falling/rising) while adding horizontal push
        if (launchVx != 0) {
            player.setVelocityX(player.getVelocityX() + launchVx);
        }

        // Ensure the player's dash is reset when launched so they can dash again
        // mid-air after a launch (restores dash availability and timers).
        try {
            player.resetDash();
        } catch (NoSuchMethodError e) {
            // Older Player classes may not have resetDash; ignore gracefully.
        }

        triggerAnimation();
    }

    private void launchBox(Box box) {
        float launchVx = -direction.dx * launchSpeed;
        float launchVy = direction.dy * launchSpeed;
        float vx = box.getVelocityX();
        float vy = box.getVelocityY();
        if (launchVy != 0) vy = launchVy;           // replace vertical when launching up
        if (launchVx != 0) vx = vx + launchVx;      // add horizontal for arc behavior
        box.setVelocity(vx, vy);
        triggerAnimation();
    }

    private void launchOrb(Orb orb) {
        float launchVx = -direction.dx * launchSpeed;
        float launchVy = direction.dy * launchSpeed;
        float vx = orb.getVelocityX();
        float vy = orb.getVelocityY();
        if (launchVy != 0) vy = launchVy;           // replace vertical when launching up
        if (launchVx != 0) vx = vx + launchVx;      // add horizontal for arc behavior
        orb.setVelocity(vx, vy);
        triggerAnimation();
    }

    private void triggerAnimation() {
        if (!isLaunching) {
            isLaunching = true;
            launchCooldown = COOLDOWN_TIME;
            anim.play("extend", false);
            SoundManager.play("Launchpad");
        }
    }

    @Override
    public void render(SpriteBatch batch) {
        anim.render(batch, bounds.x, bounds.y, bounds.width, bounds.height);
    }

    @Override
    public void debugDraw(ShapeRenderer shape) {
        // Draw sprite bounds in white (semi-transparent)
        shape.setColor(1f, 1f, 1f, 0.3f);
        shape.rect(bounds.x, bounds.y, bounds.width, bounds.height);
        
        // Draw solid base in white (opaque)
        shape.setColor(1f, 1f, 1f, 0.8f);
        shape.rect(solidBounds.x, solidBounds.y, solidBounds.width, solidBounds.height);
        
        // Draw detection area in yellow
        shape.setColor(1f, 1f, 0f, 0.7f);
        shape.rect(detectionBounds.x, detectionBounds.y, detectionBounds.width, detectionBounds.height);
    }

    @Override
    public void interact() {
        // Launchpad doesn't use manual interaction, it's automatic on contact
    }

    @Override
    public boolean canInteract() {
        return false; // Automatic trigger, not manual interaction
    }

    @Override
    public void checkInteraction(Rectangle playerHitbox) {
        // Not used - launchpad uses checkAndLaunchPlayer instead
    }
}
