package com.jjmc.chromashift.environment.interactable;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.Array;
import com.jjmc.chromashift.player.PlayerCollision;
import com.jjmc.chromashift.environment.Solid;
import com.jjmc.chromashift.player.Player;

/**
 * A simple 16x16 box with basic physics (gravity, velocity) that collides with solids
 * but does not block the player. The player can interact (press F) when close to apply an impulse.
 */
public class Box implements Interactable, Pickable, com.jjmc.chromashift.environment.Solid {
    private float x, y;
    private float width = 24f, height = 24f;
    private float vx = 0f, vy = 0f;
    private boolean wasGroundedLastFrame = false;
    private final Array<Solid> solids;
    private Array<Interactable> interactables;
    private final Rectangle bounds;
    private boolean inRange = false;
    
    // Velocity getters/setters for collision handling
    public float getVelocityX() { return vx; }
    public float getVelocityY() { return vy; }
    public void setVelocity(float vx, float vy) { 
        this.vx = vx; 
        this.vy = vy; 
    }
    
    private boolean isCollidingWithSelf(Interactable other) {
        return other == this || (other instanceof Box && ((Box)other).holder == this.holder);
    }

    // pick-up state
    private boolean held = false;
    private Player holder = null;
    private final float holdOffsetX = 0f; // relative to player's center
    private final float holdOffsetY = 6f;

    public Box(float x, float y, Array<Solid> solids) {
        this.x = x;
        this.y = y;
        this.solids = solids;
        this.bounds = new Rectangle(x, y, width, height);
        // Store original spawn for respawn logic
        this.spawnX = x;
        this.spawnY = y;
        // Default respawn area: large rectangle centered around spawn
        this.respawnArea = new Rectangle(x - 800f, y - 600f, 1600f, 1200f);
    }

    /**
     * Provide the array of other interactables (including other boxes/orbs)
     * so this box can check and resolve collisions against them.
     */
    public void setInteractables(Array<Interactable> interactables) {
        this.interactables = interactables;
    }

    @Override
    public Rectangle getBounds() {
        return bounds;
    }

    @Override
    public void update(float delta) {
        // If currently held by a player, follow the holder and skip physics
        if (held && holder != null) {
            // position above player's hitbox center
            float centerX = holder.getHitboxX() + holder.getHitboxWidth() / 2f;
            float centerY = holder.getHitboxY() + holder.getHitboxHeight() / 2f;
            x = centerX - width / 2f + holdOffsetX;
            y = centerY + holdOffsetY;
            bounds.set(x, y, width, height);
            vx = 0f; vy = 0f;
            return;
        }

        // simple physics
        float gravity = -800f;
        vy += gravity * delta;
        
        // Apply friction/damping - track grounded via collision flag
        float groundFriction = 40f;
        float airDamping = 1f;
        
        // Determine if on ground by checking if vertical velocity was zeroed by collision last frame
        // We use a dedicated flag set after collision resolution
        boolean onGround = Math.abs(vy) < 1f && wasGroundedLastFrame;
        if (onGround) {
            float frictionForce = groundFriction * 60f;
            if (Math.abs(vx) <= frictionForce * delta) {
                vx = 0;
            } else {
                float direction = vx > 0 ? -1 : 1;
                vx += direction * frictionForce * delta;
            }
        } else {
            vx *= (1f - airDamping * delta);
            vy *= (1f - airDamping * delta * 0.5f);
        }

        // Integrate
        Rectangle before = new Rectangle(bounds);
        x += vx * delta;
        y += vy * delta;
        bounds.set(x, y, width, height);

        // Resolve collisions against solids (walls, platforms, doors)
        if (solids != null) {
            Rectangle resolved = new Rectangle(bounds);
            PlayerCollision.resolveSolidCollision(resolved, solids);

            // apply resolved position and adjust velocities if blocked
            float appliedX = resolved.x - before.x;
            float appliedY = resolved.y - before.y;

            // If horizontal corrected, zero horizontal velocity
            if (Math.abs(appliedX - (x - before.x)) > 0.001f) {
                vx = 0f;
            }
            // If vertical corrected, zero vertical velocity
            if (Math.abs(appliedY - (y - before.y)) > 0.001f) {
                vy = 0f;
            }

            x = resolved.x;
            y = resolved.y;
            bounds.set(x, y, width, height);
            // Track if we landed on ground (vertical correction upward)
            wasGroundedLastFrame = (resolved.y > before.y) || (Math.abs(vy) < 0.1f && resolved.y == before.y);
        } else {
            wasGroundedLastFrame = false;
        }
        
        // Handle collisions with other interactables
        if (!held && interactables != null) {
            for (Interactable other : interactables) {
                if (other != this && !isCollidingWithSelf(other)) {
                    Rectangle otherBounds = other.getBounds();
                    if (bounds.overlaps(otherBounds)) {
                        // Simple elastic collision response
                        float centerX = bounds.x + bounds.width/2;
                        float centerY = bounds.y + bounds.height/2;
                        float otherCenterX = otherBounds.x + otherBounds.width/2;
                        float otherCenterY = otherBounds.y + otherBounds.height/2;
                        
                        // Direction from other to this
                        float dx = centerX - otherCenterX;
                        float dy = centerY - otherCenterY;
                        float len = (float)Math.sqrt(dx*dx + dy*dy);
                        if (len > 0.001f) {
                            dx /= len;
                            dy /= len;
                            
                            // Resolve overlap - push both apart equally to avoid jitter
                            float minDist = (bounds.width + otherBounds.width) * 0.5f;
                            float overlap = minDist - len;
                            if (overlap > 0) {
                                float push = overlap * 0.5f + 0.1f;
                                x += dx * push;
                                y += dy * push;
                                bounds.setPosition(x, y);
                                // Also push other if it's a Box/Orb
                                if (other instanceof Box b) {
                                    b.x -= dx * push;
                                    b.y -= dy * push;
                                    b.bounds.setPosition(b.x, b.y);
                                } else if (other instanceof Orb o) {
                                    o.setPosition(o.getX() - dx * push, o.getY() - dy * push);
                                }
                                
                                // Exchange velocities with damping (elastic collision)
                                if (other instanceof Box || other instanceof Orb) {
                                    float tmpVx = vx;
                                    float tmpVy = vy;
                                    float otherVx = 0, otherVy = 0;
                                    if (other instanceof Box b) {
                                        otherVx = b.getVelocityX();
                                        otherVy = b.getVelocityY();
                                    } else if (other instanceof Orb o) {
                                        otherVx = o.getVelocityX();
                                        otherVy = o.getVelocityY();
                                    }
                                    float damping = 0.8f;
                                    vx = otherVx * damping;
                                    vy = otherVy * damping;
                                    if (other instanceof Box b) {
                                        b.setVelocity(tmpVx * damping, tmpVy * damping);
                                    } else if (other instanceof Orb o) {
                                        o.setVelocity(tmpVx * damping, tmpVy * damping);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Respawn check (skip during editor delete mode so boxes can be removed)
        if (respawnArea != null && !EDITOR_DELETE_MODE) {
            float cx = bounds.x + bounds.width / 2f;
            float cy = bounds.y + bounds.height / 2f;
            if (!respawnArea.contains(cx, cy)) {
                respawn();
            }
        }
    }

    @Override
    public void render(SpriteBatch batch) {
        ensurePixel();
        com.badlogic.gdx.graphics.Color prev = batch.getColor();
        float pr = prev.r, pg = prev.g, pb = prev.b, pa = prev.a;
        try {
            batch.flush();
            com.badlogic.gdx.graphics.Color c = (boxColor == null) ? com.badlogic.gdx.graphics.Color.CYAN : boxColor;
            batch.setColor(c.r, c.g, c.b, 1f);
            batch.draw(PIXEL, bounds.x, bounds.y, bounds.width, bounds.height);
            batch.flush();
        } finally {
            batch.setColor(pr, pg, pb, pa);
        }
    }

    private com.badlogic.gdx.graphics.Camera gameCamera;
    
    public void setCamera(com.badlogic.gdx.graphics.Camera camera) {
        this.gameCamera = camera;
    }
    
    @Override
    public void debugDraw(ShapeRenderer shape) {
        // Draw box
        if (boxColor != null) shape.setColor(boxColor.r, boxColor.g, boxColor.b, 1f);
        else shape.setColor(inRange ? 0.2f : 0.6f, 0.2f, 0.8f, 1f);
        shape.rect(bounds.x, bounds.y, bounds.width, bounds.height);
        
        // Draw throw guide when held
        if (held && holder != null && gameCamera != null) {
            float startX = bounds.x + bounds.width/2;
            float startY = bounds.y + bounds.height/2;
            
            // Convert mouse to world coordinates using camera
            com.badlogic.gdx.math.Vector3 mousePos = new com.badlogic.gdx.math.Vector3(
                Gdx.input.getX(), Gdx.input.getY(), 0
            );
            gameCamera.unproject(mousePos);
            float mouseX = mousePos.x;
            float mouseY = mousePos.y;
            
            float dirX = mouseX - startX;
            float dirY = mouseY - startY;
            float len = (float)Math.sqrt(dirX*dirX + dirY*dirY);
            if (len > 0.001f) {
                dirX /= len;
                dirY /= len;
                
                // Draw aim line
                shape.setColor(1f, 1f, 0.2f, 0.8f);
                float guideLen = 64f; // Length of guide line
                shape.line(startX, startY, 
                          startX + dirX * guideLen,
                          startY + dirY * guideLen);
                          
                // Draw arrow head
                float headLen = 8f;
                float angle = 0.5f; // ~30 degrees
                float ax = dirX * (float)Math.cos(angle) - dirY * (float)Math.sin(angle);
                float ay = dirX * (float)Math.sin(angle) + dirY * (float)Math.cos(angle);
                shape.line(startX + dirX * guideLen,
                          startY + dirY * guideLen,
                          startX + dirX * guideLen - ax * headLen,
                          startY + dirY * guideLen - ay * headLen);
                          
                ax = dirX * (float)Math.cos(-angle) - dirY * (float)Math.sin(-angle);
                ay = dirX * (float)Math.sin(-angle) + dirY * (float)Math.cos(-angle);
                shape.line(startX + dirX * guideLen,
                          startY + dirY * guideLen,
                          startX + dirX * guideLen - ax * headLen,
                          startY + dirY * guideLen - ay * headLen);
            }
        }
    }

    @Override
    public void interact() {
        // apply an upward impulse when player presses F
        this.vy = 240f;
    }

    // --- Pickable implementation ---
    @Override
    public void pickUp(Player player) {
        if (player == null) return;
        held = true;
        holder = player;
        vx = 0f; vy = 0f;
        // immediately position to holder
        float centerX = holder.getHitboxX() + holder.getHitboxWidth() / 2f;
        float centerY = holder.getHitboxY() + holder.getHitboxHeight() / 2f;
        x = centerX - width / 2f + holdOffsetX;
        y = centerY + holdOffsetY;
        bounds.set(x, y, width, height);
    }

    @Override
    public void throwWithVelocity(float vx, float vy) {
        // release and apply velocity
        held = false;
        holder = null;
        this.vx = vx;
        this.vy = vy;
    }

    @Override
    public boolean isHeld() { return held; }

    // Expose current holder for systems (e.g., launchpads) that need to affect both
    public Player getHolder() { return holder; }

    @Override
    public void drop() {
        held = false;
        holder = null;
    }

    // --- Solid implementation ---
    @Override
    public Rectangle getCollisionBounds() { return getBounds(); }

    @Override
    public boolean isSolid() { return true; }

    @Override
    public boolean isBlocking() { return true; }

    // render() and debugDraw() implemented above

    // Box color (can be chosen similar to glass)
    private com.badlogic.gdx.graphics.Color boxColor = null;
    public void setColor(com.badlogic.gdx.graphics.Color c) { this.boxColor = c; }
    /** Returns the box color or null if not set. */
    public com.badlogic.gdx.graphics.Color getColor() { return this.boxColor; }

    // --- Respawn area support ---
    private float spawnX, spawnY;
    private Rectangle respawnArea;
    // When true (set by LevelMaker delete mode) suppress automatic respawn
    public static boolean EDITOR_DELETE_MODE = false;
    public void setRespawnArea(Rectangle area) { if (area != null) this.respawnArea = area; }
    public Rectangle getRespawnArea() { return respawnArea; }
    public void respawn() {
        x = spawnX;
        y = spawnY;
        vx = 0f; vy = 0f;
        bounds.set(x, y, width, height);
    }

    // PIXEL for batch drawing (lazy) - shared white pixel
    private static com.badlogic.gdx.graphics.Texture PIXEL;
    private static void ensurePixel() {
        if (PIXEL == null) {
            com.badlogic.gdx.graphics.Pixmap pm = new com.badlogic.gdx.graphics.Pixmap(1,1, com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888);
            pm.setColor(com.badlogic.gdx.graphics.Color.WHITE);
            pm.fill();
            PIXEL = new com.badlogic.gdx.graphics.Texture(pm);
            pm.dispose();
        }
    }
    public static void disposeStatic() {
        if (PIXEL != null) {
            try { PIXEL.dispose(); } catch (Exception ignored) {}
            PIXEL = null;
        }
    }

    @Override
    public void checkInteraction(Rectangle playerHitbox) {
        // player can interact when near (small radius)
        Rectangle r = new Rectangle(bounds.x - 8, bounds.y - 8, bounds.width + 16, bounds.height + 16);
        inRange = playerHitbox.overlaps(r);
    }

    @Override
    public boolean canInteract() {
        return inRange;
    }
    
    /**
     * Get the collision bounds used for button activation.
     * This is the actual physical bounds of the object that can press buttons.
     */
    public Rectangle getActivationBounds() {
        return bounds;
    }
}
