package com.jjmc.chromashift.environment.interactable;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;
import com.chromashift.helper.SoundManager;
import com.chromashift.helper.SpriteAnimator;
import com.chromashift.helper.VisibilityCuller;
import com.jjmc.chromashift.player.Player;
import com.jjmc.chromashift.environment.Solid;

/**
 * LockedDoor: solid while closed; consumes one player key to open permanently.
 * Implements both Interactable and Solid for collision participation until opened.
 */
public class LockedDoor implements Interactable, Solid {
    public enum Orientation { VERTICAL, HORIZONTAL }

    private final Rectangle bounds;
    private final Orientation orientation;
    private SpriteAnimator animator;
    private boolean open = false;
    private Player player; // set by screen after construction

    private static final float VERTICAL_WIDTH = 32f;
    private static final float VERTICAL_HEIGHT = 64f;
    private static final float HORIZONTAL_WIDTH = 64f;
    private static final float HORIZONTAL_HEIGHT = 32f;

    private static final String VERTICAL_SPRITE = "environment/lockedDoorVertical.png";
    private static final String HORIZONTAL_SPRITE = "environment/lockedDoorHorizontal.png";

    public LockedDoor(float x, float y) {
        this(x, y, Orientation.VERTICAL);
    }

    public LockedDoor(float x, float y, Orientation orientation) {
        this.orientation = orientation == null ? Orientation.VERTICAL : orientation;
        float width = this.orientation == Orientation.VERTICAL ? VERTICAL_WIDTH : HORIZONTAL_WIDTH;
        float height = this.orientation == Orientation.VERTICAL ? VERTICAL_HEIGHT : HORIZONTAL_HEIGHT;
        this.bounds = new Rectangle(x, y, width, height);
        loadAnimator();
    }

    private void loadAnimator() {
        String path = (orientation == Orientation.VERTICAL) ? VERTICAL_SPRITE : HORIZONTAL_SPRITE;
        try {
            animator = new SpriteAnimator(path, 1, 2);
            animator.addAnimation("closed", 0, 0, 1, 1f, true);
            animator.addAnimation("open", 0, 1, 1, 1f, true);
            animator.play("closed", false);
        } catch (Exception e) {
            Gdx.app.error("LockedDoor", "Failed to load door sprite: " + e.getMessage(), e);
            animator = null;
        }
    }

    public void setPlayer(Player player) { this.player = player; }

    @Override
    public Rectangle getBounds() {
        return bounds;
    }
    @Override
    public Rectangle getCollisionBounds() {
        return open ? null : bounds;
    }

    @Override
    public void update(float delta) {
        if (animator == null) return;
        if (VisibilityCuller.isEnabled() && !VisibilityCuller.isVisible(bounds, 128f)) return;
        animator.update(delta);
    }

    @Override
    public void render(SpriteBatch batch) {
        if (animator == null) return;
        if (VisibilityCuller.isEnabled() && !VisibilityCuller.isVisible(bounds, 128f)) return;
        // Ensure the animator reflects the current open/closed state
        String expectedAnim = open ? "open" : "closed";
        if (!expectedAnim.equals(animator.getCurrentAnimationName())) {
            animator.play(expectedAnim, false);
        }
        animator.render(batch, bounds.x, bounds.y, bounds.width, bounds.height);
    }

    @Override
    public void debugDraw(ShapeRenderer shape) {
        shape.setColor(open ? Color.GREEN : Color.ORANGE);
        shape.rect(bounds.x, bounds.y, bounds.width, bounds.height);
    }

    @Override
    public void interact() {
        // Called by generic interaction (e.g., F key). Requires player + key.
        if (open || player == null) return;
        if (player.getKeyCount() > 0 && player.consumeKey()) {
            open = true;
            try { SoundManager.play("DoorOpen"); } catch (Exception ignored) {}
            if (animator != null) animator.play("open", true);
            Gdx.app.log("LockedDoor", "Opened. Remaining keys: " + player.getKeyCount());
        } else {
            try { SoundManager.play("Error"); } catch (Exception ignored) {}
        }
    }

    public boolean isOpen() { return open; }

    public Orientation getOrientation() { return orientation; }

    @Override
    public boolean canInteract() {
        if (open || player == null) return false;
        return player.getKeyCount() > 0 && player.getHitboxRect().overlaps(bounds);
    }

    /**
     * Force-open the door without consuming a key (e.g., boss defeat).
     */
    public void open() {
        if (open) return;
        open = true;
        try { SoundManager.play("DoorOpen"); } catch (Exception ignored) {}
        if (animator != null) animator.play("open", true);
        Gdx.app.log("LockedDoor", "Force-opened by event");
    }

    public void dispose() {
        if (animator != null) animator.dispose();
    }

    // Solid implementation
    @Override
    public boolean isSolid() { return !open; }

    @Override
    public boolean isBlocking() { return !open; }
}
