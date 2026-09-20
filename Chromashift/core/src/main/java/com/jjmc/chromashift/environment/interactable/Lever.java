
package com.jjmc.chromashift.environment.interactable;

import com.chromashift.helper.SoundManager;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;

public class Lever implements Interactable {
    private final Rectangle bounds;
    private boolean on;
    private Runnable onToggle;
    private java.util.List<Interactable> targets = new java.util.ArrayList<>(); // support multiple targets
    private boolean playerNearby = false;
    private boolean horizontal = false;
    private com.chromashift.helper.SpriteAnimator anim;
    private String id;

    public Lever(float x, float y, float w, float h) {
        this(x, y, w, h, false, null);
    }

    public Lever(float x, float y, float w, float h, Interactable target) {
        this(x, y, w, h, false, target);
    }

    /**
     * Create a lever with explicit orientation (horizontal=true) and optional target.
     */
    public Lever(float x, float y, float w, float h, boolean horizontal, Interactable target) {
        // Collision bounds depend on orientation: vertical uses 26x32, horizontal uses 32x26.
        // Sprite is 64x64 and will be drawn centered around the collision rectangle so visual and collision align.
        final float COLLISION_W = horizontal ? 32f : 26f;
        final float COLLISION_H = horizontal ? 26f : 32f;
        bounds = new Rectangle(x, y, COLLISION_W, COLLISION_H);
        if (target != null) this.targets.add(target);
        this.horizontal = horizontal;
        try {
            anim = new com.chromashift.helper.SpriteAnimator("environment/lever/lever.png", 2, 2);
            // Row 0 = vertical, Row 1 = horizontal; each row has 2 frames
            anim.addAnimation("VERTICAL", 0, 0, 2, 0.1f, false);
            anim.addAnimation("HORIZONTAL", 1, 0, 2, 0.1f, false);
            // Select the correct row for orientation and initialize to current 'on' state
            anim.play(horizontal ? "HORIZONTAL" : "VERTICAL", false);
            anim.setFrame(on ? 1 : 0);
        } catch (Exception ignored) {}
    }

    public void setOnToggle(Runnable action) {
        this.onToggle = action;
    }

    public void setTarget(Interactable target) {
        if (target == null) return;
        if (!this.targets.contains(target)) this.targets.add(target);
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    @Override
    public Rectangle getBounds() {
        return bounds;
    }

    @Override
    public void update(float delta) {}

    @Override
    public void checkInteraction(Rectangle playerHitbox) {
        // update whether player is standing in interaction bounds
        playerNearby = playerHitbox.overlaps(bounds);
    }

    @Override
    public boolean canInteract() {
        return playerNearby;
    }

    private String currentAnimName = null;
    @Override
    public void render(SpriteBatch batch) {
        if (anim != null) {
            final float SPRITE_W = 64f;
            final float SPRITE_H = 64f;
            float drawX = bounds.x - (SPRITE_W - bounds.width) * 0.5f;
            float drawY = bounds.y - (SPRITE_H - bounds.height) * 0.5f;
            String targetAnim = horizontal ? "HORIZONTAL" : "VERTICAL";
            if (!targetAnim.equals(currentAnimName)) {
                anim.play(targetAnim, false);
                currentAnimName = targetAnim;
            }
            anim.setFrame(on ? 1 : 0);
            anim.render(batch, drawX, drawY, SPRITE_W, SPRITE_H);
            return;
        }
    }

    @Override
    public void debugDraw(ShapeRenderer shape) {
        // Base block
        shape.setColor(on ? Color.ORANGE : Color.GRAY);
        shape.rect(bounds.x, bounds.y, bounds.width, bounds.height);

        // Draw lever arm pivoting from the center
        float cx = bounds.x + bounds.width * 0.5f;
        float cy = bounds.y + bounds.height * 0.5f;
        float armLen = Math.max(bounds.width, bounds.height) * 1.5f;
        float angleDeg = on ? 30f : -30f;
        double rad = Math.toRadians(angleDeg);
        float ex = cx + (float) Math.cos(rad) * armLen;
        float ey = cy + (float) Math.sin(rad) * armLen;

        // Highlight when player nearby
        shape.setColor(playerNearby ? Color.YELLOW : Color.BLACK);
        shape.line(cx, cy, ex, ey);
    }

    @Override
    public void interact() {
        if (!canInteract()) return;

        on = !on;
        // Play lever sound effect
        SoundManager.play("Lever");
        // Update visual frame to reflect new state (toggle between column 0 and 1)
        try { if (anim != null) anim.setFrame(on ? 1 : 0); } catch (Exception ignored) {}
        if (onToggle != null) onToggle.run();
        for (Interactable t : targets) if (t != null) t.interact();
    }

    public boolean isOn() {
        return on;
    }

    public boolean isPlayerNearby() {
        return playerNearby;
    }
}
