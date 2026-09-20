package com.jjmc.chromashift.environment.collectible;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;
import com.jjmc.chromashift.player.Player;

/**
 * Abstract base class for all collectible objects.
 * Collectibles are items that the player can pick up by overlapping with them.
 */
public abstract class Collectible {
    protected float x, y;
    protected float width, height;
    protected boolean collected = false;
    protected String id; // Unique stable ID for save/load persistence

    public Collectible(float x, float y, float width, float height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        // Generate deterministic ID based on type and position
        this.id = generateDefaultId();
    }
    
    /**
     * Generate a deterministic ID based on class name and position.
     * Can be overridden by subclasses or set explicitly.
     */
    protected String generateDefaultId() {
        return String.format("%s_%.1f_%.1f", getClass().getSimpleName(), x, y);
    }

    /**
     * Update collectible logic (animations, etc.)
     */
    public abstract void update(float delta);

    /**
     * Render the collectible to the screen
     */
    public abstract void render(SpriteBatch batch);

    /**
     * Debug rendering
     */
    public void debugDraw(ShapeRenderer shape) {
        if (!collected) {
            shape.rect(x, y, width, height);
        }
    }

    /**
     * Called when the player collects this item.
     * Override this to implement collection behavior.
     */
    public abstract void onCollect(Player player);

    /**
     * Check if the player is overlapping with this collectible and collect it if so.
     */
    public void checkCollision(Player player) {
        if (collected) return;
        
        Rectangle collectibleBounds = getBounds();
        Rectangle playerBounds = new Rectangle(
            player.getHitboxX(), 
            player.getHitboxY(), 
            player.getHitboxWidth(), 
            player.getHitboxHeight()
        );

        if (collectibleBounds.overlaps(playerBounds)) {
            onCollect(player);
            collected = true;
        }
    }

    private final Rectangle boundsCache = new Rectangle();
    public Rectangle getBounds() {
        boundsCache.set(x, y, width, height);
        return boundsCache;
    }

    public boolean isCollected() {
        return collected;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    public float getWidth() {
        return width;
    }

    public float getHeight() {
        return height;
    }
    
    /**
     * Get the unique ID of this collectible.
     */
    public String getId() {
        return id;
    }
    
    /**
     * Set the unique ID of this collectible (for loading from save).
     */
    public void setId(String id) {
        this.id = id;
    }
    
    /**
     * Set collected state (for loading from save).
     */
    public void setCollected(boolean collected) {
        this.collected = collected;
    }

    /**
     * Cleanup resources
     */
    public abstract void dispose();
}
