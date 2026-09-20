package com.jjmc.chromashift.environment.interactable;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;
import com.chromashift.helper.SpriteAnimator;

/**
 * Portal - Teleportation device that transports player to the next level (logic hook).
 * Uses a 225x225 sprite sheet with two rows:
 *  - Row 0: 24-frame spawn (play once)
 *  - Row 1: 18-frame active (loop)
 * The portal only becomes active after both linked triggers report active.
 */
public class Portal implements Interactable {
    
    // ========================================================================
    // CONSTANTS
    // ========================================================================
    
    private static final String ASSET_PATH = "environment/portal.png";
    private static final int FRAME_WIDTH = 225;
    private static final int FRAME_HEIGHT = 225;
    private static final int SPAWN_FRAMES = 24;
    private static final int ACTIVE_FRAMES = 18;
    private static final float ANIMATION_SPEED = 0.1f; // seconds per frame
    
    // Actual interaction hitbox dimensions and offsets within the 225x225 sprite
    private static final int HITBOX_WIDTH = 140;
    private static final int HITBOX_HEIGHT = 154;
    private static final int HITBOX_LEFT_OFFSET = 42;   // 42px from left
    private static final int HITBOX_TOP_OFFSET = 30;    // 30px from top
    private static final int HITBOX_RIGHT_OFFSET = 43;  // 43px from right
    private static final int HITBOX_BOTTOM_OFFSET = 41; // 41px from bottom
    
    // ========================================================================
    // PORTAL STATE ENUM
    // ========================================================================
    
    public enum PortalState {
        INACTIVE,      // Awaiting lever activation
        SPAWNING,      // Playing spawn animation
        ACTIVE         // Looping active animation
    }
    
    // ========================================================================
    // FIELDS
    // ========================================================================
    
    private final Rectangle bounds;           // Full 225x225 sprite bounds
    private final Rectangle hitbox;           // Actual 140x154 interaction hitbox
    private PortalState state = PortalState.INACTIVE;
    private final SpriteAnimator anim;
    private float interactionCooldown = 0f;
    private static final float INTERACTION_COOLDOWN_MAX = 1f;

    // Lever/button linkage (IDs are stored for Level Maker; activation flags are set externally)
    private String requiredLeverId1 = null;
    private String requiredLeverId2 = null;
    private boolean lever1Active = false;
    private boolean lever2Active = false;
    private boolean playerNearby = false;
    
    // Callback to trigger level transition
    private Runnable onPlayerEnter = null;
    
    // ========================================================================
    // CONSTRUCTOR
    // ========================================================================
    
    public Portal(float x, float y) {
        // Bounds are centered on provided x,y (portal visual center)
        float drawX = x - FRAME_WIDTH / 2f;
        float drawY = y - FRAME_HEIGHT / 2f;
        this.bounds = new Rectangle(drawX, drawY, FRAME_WIDTH, FRAME_HEIGHT);
        
        // Calculate hitbox position within the sprite bounds
        float hitboxX = drawX + HITBOX_LEFT_OFFSET;
        float hitboxY = drawY + HITBOX_TOP_OFFSET;
        this.hitbox = new Rectangle(hitboxX, hitboxY, HITBOX_WIDTH, HITBOX_HEIGHT);
        
        this.anim = new SpriteAnimator(ASSET_PATH, 2, 24);
        // spawn row 0 (24 frames), active row 1 (18 frames)
        this.anim.addAnimation("SPAWN", 0, 0, SPAWN_FRAMES, ANIMATION_SPEED, false);
        this.anim.addAnimation("ACTIVE", 1, 0, ACTIVE_FRAMES, ANIMATION_SPEED, true);
        // start idle showing first frame of spawn
        this.anim.play("SPAWN", false);
        this.anim.setFrame(0);
    }
    
    // ========================================================================
    // INITIALIZATION
    // ========================================================================
    
    // ========================================================================
    // LEVER LINKING
    // ========================================================================
    
    public void setLinkedLeverIds(String lever1Id, String lever2Id) {
        this.requiredLeverId1 = lever1Id;
        this.requiredLeverId2 = lever2Id;
        // Missing links should count as already active so single-link portals work.
        this.lever1Active = (lever1Id == null || lever1Id.isEmpty());
        this.lever2Active = (lever2Id == null || lever2Id.isEmpty());
    }
    
    public void setLeverActive(String leverId, boolean active) {
        if (leverId == null) return;
        if (leverId.equals(requiredLeverId1)) lever1Active = active;
        if (leverId.equals(requiredLeverId2)) lever2Active = active;
    }

    public void setLeverStates(boolean lever1, boolean lever2) {
        this.lever1Active = lever1;
        this.lever2Active = lever2;
    }
    
    // ========================================================================
    // STATE MANAGEMENT
    // ========================================================================
    
    /**
     * Check if both required levers are activated.
     */
    private boolean areBothLeversActive() {
        return lever1Active && lever2Active;
    }
    
    /**
     * Activate the portal (start spawn animation).
     */
    private void activate() {
        if (state == PortalState.INACTIVE) {
            state = PortalState.SPAWNING;
            anim.play("SPAWN", false);
            anim.setFrame(0);
            Gdx.app.log("Portal", "Portal activated! Starting spawn animation.");
        }
    }
    
    /**
     * Called when spawn animation completes.
     */
    private void onSpawnComplete() {
        state = PortalState.ACTIVE;
        anim.play("ACTIVE", false);
        Gdx.app.log("Portal", "Portal spawning complete. Now active!");
    }
    
    // ========================================================================
    // UPDATE
    // ========================================================================
    
    @Override
    public void update(float delta) {
        // Update interaction cooldown
        if (interactionCooldown > 0) {
            interactionCooldown -= delta;
        }
        
        // Check if both levers just became active
        if (state == PortalState.INACTIVE && areBothLeversActive()) {
            activate();
        }
        
        // Update current animation
        switch (state) {
            case SPAWNING:
                anim.update(delta);
                if (anim.isAnimationFinished()) {
                    onSpawnComplete();
                }
                break;
                
            case ACTIVE:
                anim.update(delta);
                break;
                
            case INACTIVE:
                // Do nothing
                break;
        }
    }
    
    // ========================================================================
    // RENDERING
    // ========================================================================
    
    @Override
    public void render(SpriteBatch batch) {
        if (batch == null) return;
        // Don't re-play animation every frame - update() handles state transitions
        // Only ensure first frame shown when inactive
        if (state == PortalState.INACTIVE) {
            // Ensure animator is on first frame without restarting each frame
            if (anim.getCurrentFrameIndex() != 0) {
                anim.setFrame(0);
            }
        }
        anim.render(batch, bounds.x, bounds.y, bounds.width, bounds.height);
    }
    
    // ========================================================================
    // INTERACTION
    // ========================================================================
    
    @Override
    public void interact() {
        if (state != PortalState.ACTIVE || interactionCooldown > 0) return;
        interactionCooldown = INTERACTION_COOLDOWN_MAX;
        Gdx.app.log("Portal", "Player entered portal! Triggering level transition...");
        if (onPlayerEnter != null) {
            onPlayerEnter.run();
        }
    }

    @Override
    public void checkInteraction(Rectangle playerHitbox) {
        playerNearby = playerHitbox != null && playerHitbox.overlaps(hitbox);
    }

    @Override
    public boolean canInteract() {
        return state == PortalState.ACTIVE && interactionCooldown <= 0 && playerNearby;
    }
    
    // ========================================================================
    // SERIALIZATION
    // ========================================================================
    
    // Simple map-based serialization helpers (for Level Maker integration)
    public java.util.Map<String, Object> serialize() {
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("type", "Portal");
        data.put("x", bounds.x + bounds.width / 2f);
        data.put("y", bounds.y + bounds.height / 2f);
        data.put("state", state.name());
        data.put("lever1_id", requiredLeverId1);
        data.put("lever2_id", requiredLeverId2);
        return data;
    }

    public void deserialize(java.util.Map<String, Object> data) {
        if (data == null) return;
        Object ox = data.get("x");
        Object oy = data.get("y");
        if (ox instanceof Number && oy instanceof Number) {
            float cx = ((Number) ox).floatValue();
            float cy = ((Number) oy).floatValue();
            bounds.set(cx - FRAME_WIDTH / 2f, cy - FRAME_HEIGHT / 2f, FRAME_WIDTH, FRAME_HEIGHT);
            // Recalculate hitbox position within new bounds
            hitbox.set(bounds.x + HITBOX_LEFT_OFFSET, bounds.y + HITBOX_TOP_OFFSET, 
                      HITBOX_WIDTH, HITBOX_HEIGHT);
        }
        if (data.get("lever1_id") instanceof String) requiredLeverId1 = (String) data.get("lever1_id");
        if (data.get("lever2_id") instanceof String) requiredLeverId2 = (String) data.get("lever2_id");
        if (data.get("state") instanceof String) {
            try {
                state = PortalState.valueOf((String) data.get("state"));
                if (state == PortalState.ACTIVE) anim.play("ACTIVE", false);
                else anim.play("SPAWN", false);
            } catch (Exception ignored) {
                state = PortalState.INACTIVE;
            }
        }
    }
    
    // ========================================================================
    // GETTERS / SETTERS
    // ========================================================================
    
    public PortalState getState() { return state; }
    public void setState(PortalState newState) {
        if (newState == null) return;
        state = newState;
        anim.play(state == PortalState.ACTIVE ? "ACTIVE" : "SPAWN", false);
    }
    public String getRequiredLeverId1() { return requiredLeverId1; }
    public String getRequiredLeverId2() { return requiredLeverId2; }
    public boolean isActive() { return state == PortalState.ACTIVE; }
    public boolean isSpawning() { return state == PortalState.SPAWNING; }
    public boolean isInactive() { return state == PortalState.INACTIVE; }
    
    public void setOnPlayerEnter(Runnable callback) {
        this.onPlayerEnter = callback;
    }

    @Override
    public Rectangle getBounds() { return bounds; }

    @Override
    public void debugDraw(ShapeRenderer shape) {
        if (shape == null) return;
        shape.rect(bounds.x, bounds.y, bounds.width, bounds.height);
    }
}
