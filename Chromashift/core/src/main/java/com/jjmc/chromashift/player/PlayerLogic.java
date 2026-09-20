package com.jjmc.chromashift.player;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.Array;
import com.jjmc.chromashift.environment.Wall;
import com.jjmc.chromashift.environment.Solid;

public class PlayerLogic {
    public static void handleAttackCooldown(Player player, float delta) {
        if (player.attackCooldownTimer > 0) player.attackCooldownTimer -= delta;
        if (player.airAttacking) {
            player.airAttackTimer -= delta;
            if (player.airAttackTimer <= 0f) player.airAttacking = false;
        }
        if (player.dashCooldownTimer > 0) player.dashCooldownTimer -= delta;
    }

    public static void handleDash(Player player, float delta, Array<Wall> walls) {
        handleDash(player, delta, walls, null);
    }

    public static void handleDash(Player player, float delta, Array<Wall> walls, Array<Solid> solids) {
        if (!player.isDashing()) return;

        player.dashTimer -= delta;
        float dir = player.isFacingLeft() ? -1f : 1f;

        // Store position before dash movement
        float oldX = player.getX();

        // Sub-step the dash movement to avoid tunneling through thin solids.
        float totalDx = dir * player.getConfig().dashSpeed * delta;
        int steps = Math.max(1, (int)Math.ceil(Math.abs(totalDx) / 8f));
        float stepDx = totalDx / steps;
        boolean blocked = false;

        for (int i = 0; i < steps; i++) {
            Rectangle beforeStep = player.getHitboxRect();

            // Proactively check forward solids only (ignore solids behind the player)
            if (solids != null) {
                for (Solid s : solids) {
                    if (!s.isBlocking()) continue;
                    Rectangle sb = s.getCollisionBounds();
                    if (sb == null) continue;
                    // Vertical overlap check
                    if (sb.y > beforeStep.y + beforeStep.height || sb.y + sb.height < beforeStep.y) continue;

                    if (stepDx > 0f) {
                        // moving right: consider solids whose left edge lies between current right and next right
                        float currRight = beforeStep.x + beforeStep.width;
                        float nextRight = currRight + stepDx;
                        if (sb.x >= currRight && sb.x <= nextRight) {
                            blocked = true;
                            break;
                        }
                    } else if (stepDx < 0f) {
                        // moving left: consider solids whose right edge lies between next left and current left
                        float currLeft = beforeStep.x;
                        float nextLeft = currLeft + stepDx;
                        float sbRight = sb.x + sb.width;
                        if (sbRight <= currLeft && sbRight >= nextLeft) {
                            blocked = true;
                            break;
                        }
                    }
                }
                if (blocked) break;
            }

            // Apply one sub-step (we still resolve walls after move)
            player.setX(player.getX() + stepDx);
            Rectangle afterStep = player.getHitboxRect();
            PlayerCollision.resolveWallCollision(afterStep, walls);
            // Note: we've already checked solids for forward blocking, so avoid resolving
            // with solids here to prevent behind-solid interference. But still call solid
            // resolution to keep consistency (it will only move if overlapping).
            if (solids != null) {
                PlayerCollision.resolveSolidCollision(afterStep, solids);
            }

            // Apply resolved offset for this step
            float applied = afterStep.x - beforeStep.x;
            player.setX(player.getX() + applied);
        }

        // If any sub-step was blocked, cancel the dash and set cooldown
        if (blocked || Math.abs(player.getX() - oldX) < Math.abs(totalDx) * 0.25f) {
            player.dashTimer = 0f;
            player.setDashing(false);
            player.dashCooldownTimer = player.getConfig().dashCooldown;
            player.dashUsed = true;
        }

        player.setVelocityY(0f);
        player.canJump = true; // Enable jumping during dash
        player.setAnimation("dash", player.isFacingLeft());

        if (player.dashTimer <= 0f) {
            player.setDashing(false);
            player.dashCooldownTimer = player.getConfig().dashCooldown;
            player.dashUsed = true;
        }
    }

    public static void handleGroundMovement(Player player, float delta, Array<Wall> walls) {
        handleGroundMovement(player, delta, walls, null);
    }

    public static void handleGroundMovement(Player player, float delta, Array<Wall> walls, Array<Solid> solids) {
        if (player.isAttacking()) return;

        Rectangle beforeMove = player.getHitboxRect();

        boolean inputLeft = Gdx.input.isKeyPressed(player.getKeyLeft());
        boolean inputRight = Gdx.input.isKeyPressed(player.getKeyRight());

        if (inputLeft) {
            // Prevent moving left if wall sliding on the left wall
            if (!player.isWallSliding() || !player.isFacingLeft()) {
                player.setX(player.getX() - player.getEffectiveSpeed() * delta);
                player.setFacingLeft(true);
                player.setMoving(true);
            }
        } else if (inputRight) {
            // Prevent moving right if wall sliding on the right wall
            if (!player.isWallSliding() || player.isFacingLeft()) {
                player.setX(player.getX() + player.getEffectiveSpeed() * delta);
                player.setFacingLeft(false);
                player.setMoving(true);
            }
        }

        // If on ground and walking opposite current horizontal momentum, gently reduce it
        if (player.isOnGround()) {
            float vx = player.getVelocityX();
            float decel = player.getEffectiveSpeed() * 1.5f; // gentle decel toward 0 when pushing opposite
            if (inputLeft && vx > 0f) {
                vx -= decel * delta;
                if (vx < 0f) vx = 0f;
                player.setVelocityX(vx);
            } else if (inputRight && vx < 0f) {
                vx += decel * delta;
                if (vx > 0f) vx = 0f;
                player.setVelocityX(vx);
            }
        }

        // After movement, check and resolve collisions
        Rectangle afterMove = player.getHitboxRect();
        PlayerCollision.resolveWallCollision(afterMove, walls);
        if (solids != null) {
            PlayerCollision.resolveSolidCollision(afterMove, solids);
        }
        // Update player position based on resolved hitbox
        player.setX(player.getX() + (afterMove.x - beforeMove.x));
    }

    public static void handleJump(Player player) {
        // Allow jumping when either on ground or canJump is true (during dash)
        if (!player.isAttacking() && (Gdx.input.isKeyJustPressed(player.getKeyJump()) || Gdx.input.isKeyJustPressed(com.badlogic.gdx.Input.Keys.SPACE))
            && (player.isOnGround() || player.canJump)) {
            // Apply full jump force regardless of falling state (overwrite vertical velocity)
            player.setOnGround(false);
            player.setVelocityY(player.getConfig().jumpForce);

            // Play jump sound (fallback to Dash if JumpSkill not loaded)
            try {
                com.chromashift.helper.SoundManager.play("JumpSkill");
            } catch (Exception ex) {
                com.chromashift.helper.SoundManager.play("Dash");
            }

            // If we're dashing, end the dash
            if (player.isDashing()) {
                player.setDashing(false);
                player.dashTimer = 0f;
            }

            player.canJump = false;
            player.setWallSliding(false);
            player.setOnWall(false);
            player.groundedBySolid = false;
        }
    }

    public static void handleAttack(Player player, float delta) {
        if (!player.isAttacking() && 
            com.badlogic.gdx.Gdx.input.isButtonJustPressed(com.badlogic.gdx.Input.Buttons.LEFT) && 
            !player.isWallSliding() && 
            !player.isDashing() && 
            player.attackCooldownTimer <= 0f) {
            
            player.setAttacking(true);
            player.setAnimation("attack", player.isFacingLeft());
            player.attackCooldownTimer = player.getConfig().attackCooldown;
            
            // Activate the attack hitbox
            player.attack();
            
            if (!player.isOnGround()) {
                player.airAttacking = true;
                player.airAttackTimer = player.getConfig().airAttackDuration;
                player.setVelocityY(0f);
                float d = player.isFacingLeft() ? -1f : 1f;
                player.setX(player.getX() + d * player.getConfig().airAttackLungeSpeed * delta);
            }
        }
    }

    /**
     * Check for enemy hits when player attacks.
     * Call this method when the attack animation reaches its peak frame.
     * @param player The player performing the attack
     * @param enemies Array of Enemy objects to check for hits
     */
    public static void checkEnemyHits(Player player, Array<com.jjmc.chromashift.environment.enemy.Enemy> enemies) {
        if (!player.isAttacking() || enemies == null) return;

        // Create attack hitbox in front of player
        Rectangle hitbox = player.getHitboxRect();
        float attackRange = 40f; // Range of melee attack
        float attackWidth = 50f; // Width of attack hitbox
        float attackHeight = hitbox.height;
        
        float attackX;
        if (player.isFacingLeft()) {
            attackX = hitbox.x - attackRange;
        } else {
            attackX = hitbox.x + hitbox.width;
        }
        float attackY = hitbox.y;
        
        Rectangle attackBox = new Rectangle(attackX, attackY, attackRange + attackWidth, attackHeight);
        
        // Check for overlaps with enemy bounds
        for (com.jjmc.chromashift.environment.enemy.Enemy enemy : enemies) {
            if (enemy == null || !enemy.isAlive()) continue;
            
            Rectangle enemyBounds = enemy.getBounds();
            if (enemyBounds != null && attackBox.overlaps(enemyBounds)) {
                enemy.takeDamage(1); // Deal 1 damage per hit
            }
        }
    }

    public static void handleVerticalMovement(Player player, float delta, Array<Wall> walls) {
        handleVerticalMovement(player, delta, walls, null);
    }

    public static void handleVerticalMovement(Player player, float delta, Array<Wall> walls, Array<Solid> solids) {
        // If dash hover timer or an active skill requests gravity disabled, keep the player suspended briefly
        boolean suppressGravity = false;
        if (player.dashHoverRemaining > 0f) {
            player.dashHoverRemaining -= delta;
            if (player.dashHoverRemaining < 0f) player.dashHoverRemaining = 0f;
            suppressGravity = true;
        }
        // If a skill requests gravity disabled (e.g., dash skill or slash skill), suppress gravity
        if (player.getActiveSkill() != null && player.getActiveSkill().isRequestingDisableGravity()) {
            suppressGravity = true;
        }
        // Also suppress gravity while the built-in dash state is active
        if (player.isDashing()) suppressGravity = true;

        if (suppressGravity) {
            // Keep player suspended: zero vertical velocity and mark airborne
            player.setVelocityY(0f);
            player.setOnGround(false);
            return;
        }
        boolean wasOnGround = player.isOnGround();
        boolean isOnPlatform = false;

        // Check for platform/wall landing
        if (player.getVelocityY() <= 0 || wasOnGround) {
            isOnPlatform = handlePlatformLanding(player, walls);
            
            // Add solid ground check
            if (!isOnPlatform && solids != null) {
                Rectangle hitbox = player.getHitboxRect();
                // Create a small sensor below the player
                Rectangle groundSensor = new Rectangle(
                    hitbox.x + 2, // Add small inset to prevent edge cases
                    hitbox.y - 2, // Check slightly below the player
                    hitbox.width - 4, // Reduce width slightly
                    4  // Small height to detect ground
                );
                
                for (Solid solid : solids) {
                    if (!solid.isBlocking()) continue;
                    Rectangle bounds = solid.getCollisionBounds();
                    if (bounds == null) continue;
                    
                    if (groundSensor.overlaps(bounds)) {
                        player.groundedBySolid = true;
                        isOnPlatform = true;
                        // Only adjust Y position if we're above the solid
                        if (hitbox.y >= bounds.y + bounds.height - 5) {
                            player.setY(bounds.y + bounds.height);
                            player.setVelocityY(0);
                        }
                        break;
                    }
                }
            }
        }

        // Update ground state
        if (isOnPlatform || player.groundedBySolid) {
            player.setOnGround(true);
            player.canJump = true;
            player.setWallSliding(false);
            player.setOnWall(false);
        } else {
            player.setOnGround(false);
            player.groundedBySolid = false;
        }

        if (!player.isOnGround() && !player.isWallSliding()) {
            Rectangle beforeMove = player.getHitboxRect();
            player.setVelocityY(player.getVelocityY() + player.getConfig().gravity * delta);

            // Terminal velocity to prevent excessive speed (configurable)
            float maxFallSpeed = player.getConfig().maxFallSpeed;
            if (player.getVelocityY() < maxFallSpeed) {
                player.setVelocityY(maxFallSpeed);
            }

            player.setY(player.getY() + player.getVelocityY() * delta);

            // Resolve vertical collisions: produce a resolved hitbox and snap the player
            Rectangle afterMove = player.getHitboxRect();
            // First resolve with walls and solids to get final hitbox
            PlayerCollision.resolveWallCollision(afterMove, walls);
            if (solids != null) {
                PlayerCollision.resolveSolidCollision(afterMove, solids);
            }

            // Compute how much the hitbox moved compared to beforeMove
            float yDiff = afterMove.y - beforeMove.y;

            // Snap player's Y so the hitbox aligns exactly with the resolved position
            player.setY(afterMove.y - player.hitboxOffsetY);

            // Handle ceiling and floor collisions: if we've been adjusted less than expected, zero velocity
            if (player.getVelocityY() > 0 && Math.abs(yDiff) < Math.abs(player.getVelocityY() * delta * 0.5f)) {
                player.setVelocityY(0);
            }
            if (player.getVelocityY() < 0 && Math.abs(yDiff) < Math.abs(player.getVelocityY() * delta * 0.5f)) {
                player.setVelocityY(0);
            }
        }
        else if (player.isWallSliding()) {
            player.setVelocityY(player.getConfig().wallSlideSpeed);
            player.setY(player.getY() + player.getVelocityY() * delta);

            Rectangle afterMove = player.getHitboxRect();
            PlayerCollision.resolveWallCollision(afterMove, walls);
            if (solids != null) {
                PlayerCollision.resolveSolidCollision(afterMove, solids);
            }
            // Snap player's Y to the resolved hitbox (prevent burying into solids)
            player.setY(afterMove.y - player.hitboxOffsetY);
        }
        else {
            player.setVelocityY(0f);
        }
    }

    public static boolean handlePlatformLanding(Player player, Array<Wall> walls) {
        Wall landingWall = null;
        float closestDistance = Float.MAX_VALUE;

        // Find the best wall to land on
        for (Wall w : walls) {
            float feetY = player.getHitboxY();
            float platformY = w.bounds.y + w.bounds.height;
            
            boolean withinPlatformX = player.getHitboxX() + player.getHitboxWidth() > w.bounds.x && 
                                    player.getHitboxX() < w.bounds.x + w.bounds.width;
            
            float verticalDistance = Math.abs(feetY - platformY);
            float verticalThreshold = 4f; // Consistent threshold
            
            if (withinPlatformX && verticalDistance <= verticalThreshold && verticalDistance < closestDistance) {
                closestDistance = verticalDistance;
                landingWall = w;
            }
        }
        
        if (landingWall != null) {
            float platformY = landingWall.bounds.y + landingWall.bounds.height;
            player.setY(platformY);
            player.groundedBySolid = true;
            player.setVelocityY(0f);
            player.setOnGround(true);
            player.canJump = true;
            player.setWallSliding(false);
            player.setOnWall(false);
            return true;
        }
        
        return false;
    }

    public static void handleGroundCheck(Player player, float groundY) {
        if (player.getY() <= groundY) {
            player.setY(groundY);
            player.setVelocityY(0f);
            player.setOnGround(true);
            player.canJump = true;  // Ensure we can jump when on ground
            player.setWallSliding(false);
            player.setOnWall(false);
        }
    }

    public static void updateWallState(Player player, Array<Wall> walls) {
        player.updateWallSensor();
        boolean colliding = PlayerCollision.checkWallCollision(player.getWallSensor(), walls);
        player.setOnWall(colliding && !player.isOnGround());
        player.setWallSliding(player.isOnWall() && player.getVelocityY() < 0f);
    }

    public static void handleWallJump(Player player) {
        if (player.isWallSliding() && Gdx.input.isKeyJustPressed(player.getKeyJump())) {
            player.setWallSliding(false);
            player.setOnWall(false);
            player.setOnGround(false);
            player.canJump = false;
            player.setVelocityY(player.getConfig().jumpForce);
            player.setX(player.getX() + (player.isFacingLeft() ? 
                       player.getConfig().wallJumpForceX : -player.getConfig().wallJumpForceX) * 0.016f);
            player.setAnimation("jump", player.isFacingLeft());
        }
    }

    public static void handleAnimationState(Player player) {
        if (player.isAttacking() && player.getAnim().isAnimationFinished()) {
            player.setAttacking(false);
            player.airAttacking = false;
        }
        
        if (player.isAttacking()) {
            player.setAnimation("attack", player.isFacingLeft());
            return;
        }
        
        if (player.isWallSliding()) {
            player.setAnimation("wallslide", !player.isFacingLeft());
        } else if (player.isOnGround()) {
            player.setAnimation(player.isMoving() ? "run" : "idle", player.isFacingLeft());
        } else {
            float velocityY = player.getVelocityY();
            player.setAnimation(Math.abs(velocityY) > 1f ? 
                                (velocityY > 0f ? "jump" : "fall") : "fall", 
                                player.isFacingLeft());
        }
    }
}
