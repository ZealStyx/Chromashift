package com.jjmc.chromashift.environment.interactable;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Circle;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.Array;
import com.jjmc.chromashift.player.PlayerCollision;
import com.jjmc.chromashift.environment.Solid;
import com.jjmc.chromashift.player.Player;

/**
 * A simple circular orb (16x16) with basic physics that collides with solids
 * but does not block the player. The player can interact (press F) to apply an impulse.
 */
public class Orb implements Interactable, Pickable {
    private float x, y;
    private final float radius = 12f;
    private float vx = 0f, vy = 0f;
    // Maximum allowed speed (pixels/sec) to prevent velocity stacking from launchpads
    private float maxSpeed = 1000f;
    private final Array<Solid> solids;
    private Array<Interactable> interactables;
    private final Circle circle;
    private boolean inRange = false;
    
    // Bounciness factor (1.0 = perfectly elastic, 0.0 = no bounce)
    private float bounciness = 1f;
    // Toggle for bounce behavior against solids
    private boolean bounceEnabled = true;
    
    // Velocity getters/setters for collision handling
    public float getVelocityX() { return vx; }
    public float getVelocityY() { return vy; }
    public void setVelocity(float vx, float vy) { 
        // Set raw velocity; bounciness is applied only on collisions when bouncing
        this.vx = vx; 
        this.vy = vy;
    }
    
    public void setBounciness(float bounciness) {
        this.bounciness = Math.max(0f, Math.min(1f, bounciness));
    }
    public boolean isBounceEnabled() { return bounceEnabled; }
    public void setBounceEnabled(boolean enabled) { this.bounceEnabled = enabled; }
    
    private boolean isCollidingWithSelf(Interactable other) {
        return other == this || (other instanceof Orb && ((Orb)other).holder == this.holder);
    }

    // pick-up state
    private boolean held = false;
    private Player holder = null;
    private final float holdOffsetX = 0f;
    private final float holdOffsetY = 6f;

    public Orb(float x, float y, Array<Solid> solids) {
        this.x = x;
        this.y = y;
        this.solids = solids;
        this.circle = new Circle(x + radius, y + radius, radius);
        // Store original spawn for respawn logic
        this.spawnX = x;
        this.spawnY = y;
        // Default respawn area: large rectangle centered around spawn
        this.respawnArea = new Rectangle(x - 800f, y - 600f, 1600f, 1200f);
    }

    @Override
    public Rectangle getBounds() {
        return new Rectangle(circle.x - radius, circle.y - radius, radius * 2, radius * 2);
    }

    @Override
    public void update(float delta) {
        // follow holder if held
        if (held && holder != null) {
            float centerX = holder.getHitboxX() + holder.getHitboxWidth() / 2f;
            float centerY = holder.getHitboxY() + holder.getHitboxHeight() / 2f;
            x = centerX - radius + holdOffsetX;
            y = centerY + holdOffsetY;
            circle.setPosition(x + radius, y + radius);
            vx = 0f; vy = 0f;
            return;
        }

        float gravity = -800f;
        vy += gravity * delta;
        
        // Apply friction/damping - Orb is more slippery than Box
        float groundFriction = 8f;   // Lower friction than box
        float airDamping = 0.5f;     // Less air resistance than box
        
        // Check if on ground (vy close to 0 and collision detected)
        boolean onGround = Math.abs(vy) < 0.1f;
        if (onGround) {
            // Direct velocity reduction for ground friction
            float frictionForce = groundFriction * 60f; // Scale for better control
            if (Math.abs(vx) <= frictionForce * delta) {
                vx = 0; // Stop completely if speed is very low
            } else {
                // Apply friction in opposite direction of movement
                float direction = vx > 0 ? -1 : 1;
                vx += direction * frictionForce * delta;
            }
        } else {
            // Air resistance
            vx *= (1f - airDamping * delta);
            vy *= (1f - airDamping * delta);
        }

        Rectangle before = getBounds();

        x += vx * delta;
        y += vy * delta;
        circle.setPosition(x + radius, y + radius);

        if (solids != null) {
            Rectangle resolved = getBounds();
            PlayerCollision.resolveSolidCollision(resolved, solids);

            float appliedX = resolved.x - before.x;
            float appliedY = resolved.y - before.y;

            boolean blockedX = Math.abs(appliedX - (x - before.x)) > 0.001f;
            boolean blockedY = Math.abs(appliedY - (y - before.y)) > 0.001f;

            if (blockedX) {
                if (bounceEnabled) {
                    vx = -vx * bounciness;
                } else {
                    vx = 0f;
                }
            }
            if (blockedY) {
                if (bounceEnabled) {
                    vy = -vy * bounciness;
                } else {
                    vy = 0f;
                }
            }

            x = resolved.x;
            y = resolved.y;
            circle.setPosition(x + radius, y + radius);
        }

        // Clamp velocity to avoid runaway speeds (e.g., multiple launchpad impulses)
        float speedSq = vx * vx + vy * vy;
        if (speedSq > maxSpeed * maxSpeed) {
            float scale = maxSpeed / (float)Math.sqrt(speedSq);
            vx *= scale;
            vy *= scale;
        }
        
        // Handle collisions with other interactables
        if (!held && interactables != null) {
            for (Interactable other : interactables) {
                if (other != this && !isCollidingWithSelf(other)) {
                    Rectangle otherBounds = other.getBounds();
                    Rectangle thisBounds = getBounds();
                    if (thisBounds.overlaps(otherBounds)) {
                        // Simple elastic collision response
                        float centerX = circle.x;
                        float centerY = circle.y;
                        float otherCenterX = otherBounds.x + otherBounds.width/2;
                        float otherCenterY = otherBounds.y + otherBounds.height/2;
                        
                        // Direction from other to this
                        float dx = centerX - otherCenterX;
                        float dy = centerY - otherCenterY;
                        float len = (float)Math.sqrt(dx*dx + dy*dy);
                        if (len > 0.001f) {
                            dx /= len;
                            dy /= len;
                            
                            // Resolve overlap
                            float overlap = (radius + otherBounds.width/2) - len;
                            if (overlap > 0) {
                                x += dx * overlap/2;
                                y += dy * overlap/2;
                                circle.setPosition(x + radius, y + radius);
                                
                                // Exchange velocities (elastic collision)
                                if (other instanceof Box || other instanceof Orb) {
                                    float tmpVx = vx;
                                    float tmpVy = vy;
                                    
                                    // Get other object's velocity if available
                                    float otherVx = 0, otherVy = 0;
                                    if (other instanceof Box b) {
                                        otherVx = b.getVelocityX();
                                        otherVy = b.getVelocityY();
                                    } else if (other instanceof Orb o) {
                                        otherVx = o.getVelocityX();
                                        otherVy = o.getVelocityY();
                                    }
                                    
                                    // Apply collision response with bounciness
                                    setVelocity(otherVx, otherVy);
                                    if (other instanceof Box b) {
                                        b.setVelocity(tmpVx, tmpVy);
                                    } else if (other instanceof Orb o) {
                                        o.setVelocity(tmpVx, tmpVy);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Respawn check (skip during editor delete mode so orbs can be removed)
        if (respawnArea != null && !EDITOR_DELETE_MODE) {
            float cx = circle.x;
            float cy = circle.y;
            if (!respawnArea.contains(cx, cy)) {
                respawn();
            }
        }
    }

    @Override
    public void render(SpriteBatch batch) {
        // Draw orb as a circular sprite so it appears round rather than square
        ensureOrbTexture();
        com.badlogic.gdx.graphics.Color prev = batch.getColor();
        float pr = prev.r, pg = prev.g, pb = prev.b, pa = prev.a;
        try {
            batch.flush();
            com.badlogic.gdx.graphics.Color c = com.badlogic.gdx.graphics.Color.YELLOW;
            batch.setColor(c.r, c.g, c.b, 1f);
            // draw circular texture centered on orb
            batch.draw(ORB_CIRCLE, circle.x - radius, circle.y - radius, radius * 2f, radius * 2f);
            batch.flush();
        } finally {
            batch.setColor(pr, pg, pb, pa);
        }
    }

    // PIXEL for batch drawing (lazy)
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

    // Circular texture for orb rendering
    private static com.badlogic.gdx.graphics.Texture ORB_CIRCLE;
    private static void ensureOrbTexture() {
        if (ORB_CIRCLE == null) {
            int size = 24; // orb is 24x24
            com.badlogic.gdx.graphics.Pixmap pm = new com.badlogic.gdx.graphics.Pixmap(size, size, com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888);
            pm.setColor(com.badlogic.gdx.graphics.Color.CLEAR);
            pm.fill();
            pm.setColor(com.badlogic.gdx.graphics.Color.WHITE);
            // draw filled circle centered in pixmap
            int cx = size/2, cy = size/2, r = size/2;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int dx = x - cx;
                    int dy = y - cy;
                    if (dx*dx + dy*dy <= r*r) {
                        pm.drawPixel(x, y);
                    }
                }
            }
            ORB_CIRCLE = new com.badlogic.gdx.graphics.Texture(pm);
            pm.dispose();
        }
    }

    private com.badlogic.gdx.graphics.Camera gameCamera;
    
    public void setCamera(com.badlogic.gdx.graphics.Camera camera) {
        this.gameCamera = camera;
    }
    
    @Override
    public void debugDraw(ShapeRenderer shape) {
        // Draw orb
        shape.setColor(inRange ? 0.2f : 0.9f, 0.8f, 0.1f, 1f);
        shape.circle(circle.x, circle.y, circle.radius);
        
        // Draw throw guide when held
        if (held && holder != null && gameCamera != null) {
            float startX = circle.x;
            float startY = circle.y;
            
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

    public static void disposeStatic() {
        if (PIXEL != null) { try { PIXEL.dispose(); } catch (Exception ignored) {} PIXEL = null; }
        if (ORB_CIRCLE != null) { try { ORB_CIRCLE.dispose(); } catch (Exception ignored) {} ORB_CIRCLE = null; }
    }
    @Override
    public void interact() {
        // small upward impulse
        this.vy = 200f;
    }

    @Override
    public void checkInteraction(Rectangle playerHitbox) {
        Rectangle r = new Rectangle(circle.x - radius - 8, circle.y - radius - 8, radius * 2 + 16, radius * 2 + 16);
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
        return getBounds();
    }

    // --- Pickable implementation ---
    @Override
    public void pickUp(Player player) {
        if (player == null) return;
        held = true;
        holder = player;
        vx = 0f; vy = 0f;
        float centerX = holder.getHitboxX() + holder.getHitboxWidth() / 2f;
        float centerY = holder.getHitboxY() + holder.getHitboxHeight() / 2f;
        x = centerX - radius + holdOffsetX;
        y = centerY + holdOffsetY;
        circle.setPosition(x + radius, y + radius);
    }

    @Override
    public void throwWithVelocity(float vx, float vy) {
        held = false;
        holder = null;
        this.vx = vx;
        this.vy = vy;
    }

    @Override
    public boolean isHeld() { return held; }

    @Override
    public void drop() { held = false; holder = null; }

    // Expose current holder for systems (e.g., launchpads) that need to affect both
    public Player getHolder() { return holder; }

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
        circle.setPosition(x + radius, y + radius);
    }
}
