package com.jjmc.chromashift.environment.interactable;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.Array;
import com.chromashift.helper.SpriteAnimator;
import com.chromashift.helper.SoundManager;
import com.jjmc.chromashift.environment.Solid;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

public class Button implements Interactable, Solid {
    public enum ButtonColor {
        RED(0),
        BLUE(1),
        GREEN(2),
        YELLOW(3),
        PURPLE(4);

        private final int rowIndex;

        ButtonColor(int rowIndex) {
            this.rowIndex = rowIndex;
        }

        public int getRowIndex() {
            return rowIndex;
        }
    }

    private final Rectangle bounds;
    private String id;
    private final Door linkedDoor; // legacy single target (keep for compatibility)
    private final Array<Door> linkedDoors = new Array<>();
    private final Array<Interactable> linkedInteractables = new Array<>();
    private boolean pressed;
    private final SpriteAnimator anim;
    private final ButtonColor color;
    private List<BiConsumer<String, Boolean>> pressListeners;
    private static final float BUTTON_WIDTH = 64f;
    private static final float BUTTON_HEIGHT = 32f;
    // two hitboxes centered within the button area
    private final Rectangle solidBounds;      // visual solid (52 x 7)
    // collision-only bounds (may be larger than visual solid so sides block)
    private final Rectangle collisionBounds;
    private final Rectangle activationBounds; // 32 x 10 (activator above solid)

    public Button(float x, Solid baseSolid, Door linkedDoor, ButtonColor color) {
        // Get the Y coordinate from the top of the base solid
        Rectangle baseRect = baseSolid.getBounds();
        float y = baseRect.y + baseRect.height;
        
        // Use fixed size; x,y are the bottom-left of the button
        this.bounds = new Rectangle(x, y, BUTTON_WIDTH, BUTTON_HEIGHT);
        this.linkedDoor = linkedDoor;
        if (linkedDoor != null) linkedDoors.add(linkedDoor);
        this.color = color;

        // Initialize sprite animation (5 rows (colors), 2 columns (unpressed/pressed))
        this.anim = new SpriteAnimator("environment/button/buttons.png", 5, 2);
        // Set up animation for this color's row (2 frames)
        this.anim.addAnimation("default", color.getRowIndex(), 0, 2, 0.1f, false);
        this.anim.play("default", false);

    // compute hitboxes: horizontally centered, vertically anchored near bottom
    float cx = bounds.x + BUTTON_WIDTH * 0.5f;

    // visual solid stays small; we'll create a larger collisionBounds below
    float solidW = 52f, solidH = 7f;
    float actW = 32f, actH = 10f;

    // place solid near bottom (small padding from bottom edge)
    float solidX = cx - solidW * 0.5f;
    float solidY = bounds.y;
    this.solidBounds = new Rectangle(solidX, solidY, solidW, solidH);

    // create a collision-only rectangle that uses the full button width so sides block
    float collisionW = solidW; // match visual solid width (52px)
    float collisionH = solidH; // keep same (visual) height to align with sprite
    // align collision X to the button left so it matches sprite horizontally
    float collisionX = solidX;
    this.collisionBounds = new Rectangle(collisionX, solidY, collisionW, collisionH);

    // activation area sits directly above the solid part
    float actX = cx - actW * 0.5f;
    float actY = solidY + solidH; // directly above solid
    this.activationBounds = new Rectangle(actX, actY, actW, actH);
        this.pressListeners = new ArrayList<>();
    }

    // New: multi-target constructor
    public Button(float x, Solid baseSolid, Array<Door> doors, ButtonColor color) {
        this(x, baseSolid, (doors != null && doors.size > 0) ? doors.first() : null, color);
        if (doors != null) {
            linkedDoors.clear();
            for (Door d : doors) if (d != null) linkedDoors.add(d);
        }
    }

    // Allow linking arbitrary interactables (e.g. LaserRay emitters). These will be
    // invoked via interact() when the button state changes to pressed.
    public void addLinkedInteractable(Interactable it) {
        if (it == null) return;
        if (!linkedInteractables.contains(it, true)) linkedInteractables.add(it);
    }

    public void setId(String id) { this.id = id; }
    public String getId() { return id; }

    /** Register a callback notified when pressed state toggles. */
    public void addPressListener(BiConsumer<String, Boolean> listener) {
        if (listener == null) return;
        if (pressListeners == null) pressListeners = new ArrayList<>();
        pressListeners.add(listener);
    }

    public void update(float delta, Rectangle playerHitbox, Array<Rectangle> objectBounds) {
        boolean wasPressed = pressed;
        pressed = false; // Reset state each frame
        
        // Check for player activation
        if (playerHitbox.overlaps(activationBounds)) {
            pressed = true;
        } else {
            // Check for object activation
            for (Rectangle bounds : objectBounds) {
                if (bounds.overlaps(activationBounds)) {
                    pressed = true;
                    break;
                }
            }
        }

        // Update animation frame based on pressed state
        anim.setFrame(pressed ? 1 : 0);
        if (pressed != wasPressed) {
            if (linkedDoors != null && linkedDoors.size > 0) {
                for (Door d : linkedDoors) if (d != null) d.setOpen(pressed);
            } else if (linkedDoor != null) {
                linkedDoor.setOpen(pressed);
            }
            // Notify any other linked interactables on press
            if (pressed) {
                for (Interactable it : linkedInteractables) if (it != null) it.interact();
                // Play button sound
                SoundManager.play("Button");
            }
            if (pressListeners != null) {
                for (BiConsumer<String, Boolean> cb : pressListeners) if (cb != null) cb.accept(id, pressed);
            }
        }
        anim.update(delta);
    }

    @Override
    public Rectangle getBounds() {
        return bounds;
    }

    @Override
    public Rectangle getCollisionBounds() {
        return collisionBounds;
    }

    @Override
    public void update(float delta) {
        anim.update(delta);
    }

    @Override
    public void render(SpriteBatch batch) {
        anim.render(batch, bounds.x, bounds.y, BUTTON_WIDTH, BUTTON_HEIGHT);
    }

    private static final Color COLLISION_DEBUG_COLOR = new Color(0f, 0.4f, 1f, 0.4f);
    @Override
    public void debugDraw(ShapeRenderer shape) {
        shape.setColor(Color.WHITE);
        shape.rect(bounds.x, bounds.y, bounds.width, bounds.height);

        shape.setColor(Color.DARK_GRAY);
        shape.rect(solidBounds.x, solidBounds.y, solidBounds.width, solidBounds.height);

        shape.setColor(COLLISION_DEBUG_COLOR);
        shape.rect(collisionBounds.x, collisionBounds.y, collisionBounds.width, collisionBounds.height);

        shape.setColor(pressed ? Color.GREEN : Color.LIGHT_GRAY);
        shape.rect(activationBounds.x, activationBounds.y, activationBounds.width, activationBounds.height);
    }

    @Override
    public void interact() {
        // Pressure plates don't use F key
    }

    public boolean isPressed() {
        return pressed;
    }

    public void dispose() {
        if (anim != null) {
            anim.dispose();
        }
    }

    // Solid implementation
    @Override
    public boolean isSolid() {
        return true;
    }

    @Override
    public boolean isBlocking() {
        return true;
    }
}
