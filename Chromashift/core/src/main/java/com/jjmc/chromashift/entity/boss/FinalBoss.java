package com.jjmc.chromashift.entity.boss;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.Array;
import com.jjmc.chromashift.effects.SFX;
import com.jjmc.chromashift.environment.Solid;
import com.jjmc.chromashift.environment.Wall;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.Color;

/**
 * Example boss instance with configured attacks and health.
 */
public class FinalBoss extends Boss {
    // Chasing parameters for smooth Moon Lord-style movement
    private float chasingDistance = 200f;  // Distance to maintain from player
    private float chasingAcceleration = 300f;  // Acceleration toward target
    private float chasingMaxSpeed = 400f;  // Max movement speed
    private float chasingDamping = 0.92f;  // Smoothing factor (0.9-0.95 feels natural)
    private float chasingVelocityX = 0f;
    private float chasingVelocityY = 0f;
    // Hover/orbit tuning to mimic Moon Lord-style glide instead of hard chase
    private float hoverRadius = 220f;            // Desired lateral distance from player
    private float orbitAngleDeg = 25f;           // Pitch angle for threatening offset
    private float verticalLagFactor = 0.18f;     // Lower = slower vertical corrections
    private float sideLerp = 0.08f;              // Smoothing when swapping sides
    private float orbitSideSign = 1f;            // -1 = left of player, 1 = right of player
    private float altitudeBias = 40f;            // Extra height above hover to feel looming
    // Vertical search distance used to locate ground for the eruption attack
    private float groundAttackOffset = 300f;
    // Flag to track if an attack is in progress
    private boolean isAttacking = false;
    // References to game objects for collision detection
    private Array<Solid> solids;
    private Array<Wall> walls;
    // keep references to the attacks so debug keys can trigger them
    private Attack debugAttack1, debugAttack2, debugAttack3, debugAttack4;
    // Player position tracking for attacks and movement
    private float lastPlayerX = 0f;
    private float lastPlayerY = 0f;
    private float prevPlayerX = 0f;
    private float prevPlayerY = 0f;
    // Pools to reuse SFX instances and reduce allocations
    private final List<SFX> indicatorPool = new ArrayList<>();
    private final List<SFX> attackPool = new ArrayList<>();
    // Arena trigger state (used to bias movement and attack patterns)
    private enum TriggerZone { TRIGGER_1, TRIGGER_2, TRIGGER_3, TRIGGER_4, TRIGGER_5, TRIGGER_6, NONE }
    private TriggerZone activeTriggerZone = TriggerZone.NONE;
    private Rectangle trigger6Bounds = null; // boundary zone for attack spawning
    private TriggerSide activeTrigger = TriggerSide.NONE; // kept for compatibility
    private enum TriggerSide { LEFT, RIGHT, NONE }
    // Default bounds pulled from bossroom1.json; can be overridden via setArenaBounds
    private float arenaLeft = -288f;  // bossroom1 start
    private float arenaRight = 1024f; // bossroom1 end
    private float triggerWidth = 0f; // half-deadzone around the arena midpoint (0 = hard split)
    private float triggerCooldown = 0.25f; // debounce so we don't flip every frame
    private float triggerCooldownTimer = 0f;
    // Launchpad anchors from bossroom1.json (can be overridden via setter)
    private float launchpadLeftX = -32f;
    private float launchpadRightX = 736f;
    private float launchpadY = 256f;
    // Debug colors for triggers (used in overlay text)
    private final Color triggerLeftColor = Color.RED.cpy();
    private final Color triggerRightColor = Color.CYAN.cpy();
    // Debug toggle for trigger info in the overlay
    private boolean debugTriggerInfo = true;

    public void setEnvironment(Array<Solid> solids, Array<Wall> walls) {
        this.solids = solids;
        this.walls = walls;
    }

    // Get preferred attack index based on current trigger zone
    // Returns -1 if no preference (any attack can be used)
    // Note: TRIGGER_6 is only for boundary checking, not attack selection
    private int getPreferredAttackForTrigger() {
        switch (activeTriggerZone) {
            case TRIGGER_1:
            case TRIGGER_2:
                return 3; // attack4 (wave attack)
            case TRIGGER_3:
            case TRIGGER_4:
                return 1; // attack2 (ground eruption)
            case TRIGGER_5:
                return 0; // attack1 (directional wave)
            case TRIGGER_6:
                return -1; // trigger_6 is boundary only, no attack preference
            default:
                return -1; // no preference
        }
    }

    // Check if an attack index is allowed in current trigger zone
    private boolean isAttackAllowedInTrigger(int attackIdx) {
        // attack3 (burst) can work anywhere
        if (attackIdx == 2) return true;
        
        // In trigger_4, allow attack1, attack2, and attack4 freely
        if (activeTriggerZone == TriggerZone.TRIGGER_4 && (attackIdx == 0 || attackIdx == 1 || attackIdx == 3)) {
            return true;
        }
        
        int preferred = getPreferredAttackForTrigger();
        // If no preference, all attacks allowed
        if (preferred == -1) return true;
        
        // Preferred attack is always allowed
        if (attackIdx == preferred) return true;
        
        // Other attacks have 20% chance to spawn in wrong trigger zone
        return com.badlogic.gdx.math.MathUtils.random() < 0.20f;
    }

    // Check if a position is within trigger_6 bounds (attack spawn boundary)
    private boolean isPositionInTrigger6(float x, float y, float width, float height) {
        if (trigger6Bounds == null) return true; // no boundary set, allow all
        
        // Check if attack area overlaps with trigger_6
        Rectangle attackArea = new Rectangle(x, y, width, height);
        return trigger6Bounds.overlaps(attackArea);
    }

    // Set trigger_6 bounds from the level (call this during initialization)
    public void setTrigger6Bounds(Rectangle bounds) {
        this.trigger6Bounds = bounds;
    }

    // Get trigger_6 bounds for attack positioning
    private Rectangle getTrigger6Bounds() {
        return trigger6Bounds;
    }
    @SuppressWarnings("unused")
    public FinalBoss() {
        super(1000f); // 1000 health points

        // debug overlay font (constructed on game thread)
        try {
            debugFont = new BitmapFont();
            debugFont.getData().setScale(1.0f);
            debugFont.setColor(Color.WHITE);
        } catch (Exception ignored) {}

        // Explicit mapping of sprite rows for each attack (0-based indices)
        final int ROW_ATTACK_1 = 0; // attack one -> row 1 in editor (0-based index 0)
        final int ROW_ATTACK_2 = 1; // attack two -> row 2
        final int ROW_ATTACK_3 = 2; // attack three -> row 3
        final int ROW_ATTACK_4 = 3; // attack four -> row 4

        // Scale up boss sprite sizes (1.5x scale)
        final float SCALE = 3f;
        final float scaledBodyWidth = 224f * SCALE;   // 336f
        final float scaledBodyHeight = 240f * SCALE;  // 360f
        final float scaledArmWidth = 224f * SCALE;    // 336f
        final float scaledArmHeight = 240f * SCALE;   // 360f

        // Initialize boss parts with scaled dimensions before positioning
        initBody("entity/boss/body/bossmain.png", 1, 15, scaledBodyWidth, scaledBodyHeight, 0, 0);
        bodyAnim.addAnimation("idle", 0, 0, 15, 0.1f, true);
        playBody("idle", false);

        // Initialize arms from bossarm.png (4 rows x 15 cols) with scaled dimensions
        initLeftUpper("entity/boss/body/bossarm.png", 4, 15, scaledArmWidth, scaledArmHeight, 0, 0f);
        leftUpperArmAnim.addAnimation("idle", 0, 0, 15, 0.1f, true);
        playLeftUpper("idle", false);

        initLeftLower("entity/boss/body/bossarm.png", 4, 15, scaledArmWidth, scaledArmHeight, 0, 0);
        leftLowerArmAnim.addAnimation("idle", 1, 0, 15, 0.1f, true);
        playLeftLower("idle", false);

        initRightUpper("entity/boss/body/bossarm.png", 4, 15, scaledArmWidth, scaledArmHeight, 0, 0f);
        rightUpperArmAnim.addAnimation("idle", 2, 0, 15, 0.1f, true);
        playRightUpper("idle", false);

        initRightLower("entity/boss/body/bossarm.png", 4, 15, scaledArmWidth, scaledArmHeight, 0, 0);
        rightLowerArmAnim.addAnimation("idle", 3, 0, 15, 0.1f, true);
        playRightLower("idle", false);

        // Position the boss in the world (can be changed later)
        setPosition(0f, 0f);

    // Use custom scheduler (this instance will manage attack patterns)
    setUseCustomAttackScheduler(true);

        // Create attack definitions with staggered spawning to prevent freezing
        final String attackSprite = "entity/boss/attack/boss_attack.png";
        final int spriteRows = 4;
        final int spriteCols = 25;
        final float attackWidth = 182f * SCALE/2;   // 273f
        final float attackHeight = 182f * SCALE/2;  // 273f

        // Prewarm SFX pools to avoid first-use GL stalls when attacks trigger.
        // Instead of creating everything in a single frame (which causes a big
        // hitch), stagger creation across several frames using scheduleAction
        // so texture uploads happen gradually during idle frames.
        try {
            for (int i = 0; i < 6; i++) {
                final int idx = i;
                // small stagger so work is spread over frames
                float delay = 0.02f * idx;
                scheduleAction(delay, new Runnable() {
                    @Override
                    public void run() {
                        try {
                            SFX indicator = new SFX(attackSprite, spriteRows, spriteCols, attackWidth, attackHeight * 0.5f);
                            indicator.addAnimation("warning", 0, 0, 5, 0.08f, true);
                            indicator.setAutoRemove(false);
                            indicator.reset();
                            // mark finished so obtainPooledSFX can find it for reuse
                            indicator.markFinishedForReuse();
                            indicatorPool.add(indicator);

                            SFX atk = new SFX(attackSprite, spriteRows, spriteCols, attackWidth, attackHeight);
                            atk.addAnimation("attack", ROW_ATTACK_1, 0, 25, 0.03f, false);
                            atk.setAutoRemove(true);
                            atk.reset();
                            atk.markFinishedForReuse();
                            attackPool.add(atk);
                        } catch (Exception ignored) {
                            // ignore errors during staggered prewarm
                        }
                    }
                });
            }
        } catch (Exception ignored) {
            // If scheduling fails, fall back to on-demand creation.
        }
    
        // Attack 1 - Directional wave attack based on player movement
        debugAttack1 = new Attack(4.5f) { // longer cooldown for powerful attack
            @Override
            protected void start(final Boss boss) {
                final FinalBoss bossInst = (boss instanceof FinalBoss) ? (FinalBoss) boss : null;
                final float px = bossInst != null ? bossInst.lastPlayerX : boss.getX();
                final float py = bossInst != null ? bossInst.lastPlayerY : boss.getY();

                // Choose launchpad side based on where the player is (left/right half of arena)
                float mid = (arenaLeft + arenaRight) * 0.5f;

                // Determine attack direction based on player movement
                float moveX = bossInst != null ? (bossInst.lastPlayerX - bossInst.prevPlayerX) : 0;
                boolean movingRight = moveX >= 0; // true if moving right or standing still

                if (bossInst != null && bossInst.isAttacking) {
                    return; // Don't start another attack if one is in progress
                }
                if (bossInst != null) {
                    bossInst.isAttacking = true;
                }

                // Calculate spacing and positions
                float spacing = attackWidth * 0.5f; // Closer spacing between attacks
                float firstAttackX = !movingRight ? px - (spacing * 2.5f) : px + (spacing * 2.5f);

                // No warning indicators for this attack (removed to reduce allocations/hitches)

                // Spawn 6 attacks in sequence based on movement direction
                for (int i = 0; i < 6; i++) {
                    final int index = i;
                    boss.scheduleAction(0.6f + (i * 0.15f), new Runnable() {
                        public void run() {
                            float xOffset = !movingRight ? (index * spacing) : (-index * spacing);
                            final float spawnX = firstAttackX + xOffset - attackWidth / 2;
                            final float groundY = findGroundY(spawnX + attackWidth / 2f, py, attackWidth);
                            final float spawnY = groundY - 35f; // anchor wave just above ground like eruption
                            // obtain or create attack SFX from pool
                            SFX atk = obtainPooledSFX(attackPool, new Supplier<SFX>() {
                                @Override
                                public SFX get() {
                                    SFX s = new SFX(attackSprite, spriteRows, spriteCols, attackWidth, attackHeight);
                                    s.addAnimation("attack", ROW_ATTACK_1, 0, 25, 0.03f, false);
                                    s.setAutoRemove(true);
                                    return s;
                                }
                            });
                            if (atk != null) {
                                // Check if spawn position is within trigger_6 bounds
                                if (bossInst != null && bossInst.isPositionInTrigger6(spawnX, spawnY, attackWidth, attackHeight)) {
                                    // Ensure this pooled instance uses the correct animation definition
                                    atk.addAnimation("attack", ROW_ATTACK_1, 0, 25, 0.03f, false);
                                    atk.reset();
                                    atk.play("attack");
                                    boss.spawnEffect(atk, spawnX, spawnY);
                                }
                            }

                            // No indicator to remove for this attack

                            // Reset attacking flag after last impact
                            if (index == 5 && bossInst != null) {
                                bossInst.isAttacking = false;
                            }
                        }
                    });
                }
            }
        };
        addAttack(debugAttack1);
    
    // Configure phase-specific attack priorities and frequency multipliers
        // Attack 2 definition (ground eruption)
        debugAttack2 = new Attack(6.0f) {
            @Override
            protected void start(final Boss boss) {
                final FinalBoss bossInst = (boss instanceof FinalBoss) ? (FinalBoss) boss : null;
                if (bossInst != null && bossInst.isAttacking) {
                    return; // Don't start if another attack is in progress
                }
                if (bossInst != null) {
                    bossInst.isAttacking = true;
                }

                final float px = bossInst != null ? bossInst.lastPlayerX : boss.getX();
                final float py = bossInst != null ? bossInst.lastPlayerY : boss.getY();

                // Find the nearest ground below the player
                float groundY = findGroundY(px, py, attackWidth);
                
                // Validate that we found actual ground (not just default fallback)
                // Check if ground is reasonably close (within search range)
                float distanceToGround = Math.abs(groundY - (py - groundAttackOffset));
                if (distanceToGround < 10f) {
                    // No valid ground found, abort this attack
                    if (bossInst != null) {
                        bossInst.isAttacking = false;
                    }
                    return;
                }

                // Ground crack warning effect (positioned at ground level)
                final AtomicReference<SFX> warningRef = new AtomicReference<>();
                final float warnX = px - attackWidth / 2;
                final float warnY = groundY - 30;
                // obtain a pooled indicator (should be prewarmed); reset and spawn synchronously
                SFX warning = obtainPooledSFX(indicatorPool, new Supplier<SFX>() {
                    @Override
                    public SFX get() {
                        SFX w = new SFX(attackSprite, spriteRows, spriteCols, attackWidth, attackHeight * 0.5f);
                        // use the same animation mapping as the prewarmed indicators (row 0)
                        w.addAnimation("warning", 0, 0, 5, 0.08f, true);
                        return w;
                    }
                });
                if (warning != null && bossInst != null && bossInst.isPositionInTrigger6(warnX, warnY, attackWidth, attackHeight * 0.5f)) {
                    warning.reset();
                    // Ensure the pooled instance has the expected animation mapping for this attack
                    warning.addAnimation("warning", 0, 0, 5, 0.08f, true);
                    warning.play("warning");
                    boss.spawnEffect(warning, warnX, warnY);
                    warningRef.set(warning);
                }

                // Schedule the eruption
                final float eruption_px = px;
                final float eruption_y = groundY;
                boss.scheduleAction(2.0f, new Runnable() {
                    public void run() {
                        // Vertical eruption effect (use pooled SFX to avoid GL allocations)
                                SFX eruption = obtainPooledSFX(attackPool, new Supplier<SFX>() {
                            @Override
                            public SFX get() {
                                SFX s = new SFX(attackSprite, spriteRows, spriteCols, attackWidth, attackHeight * 2f);
                                s.addAnimation("attack", ROW_ATTACK_2, 0, 20, .25f, false);
                                s.setAutoRemove(true);
                                return s;
                            }
                        });
                        if (eruption != null) {
                            final float eruptX = eruption_px - attackWidth / 2;
                            final float eruptY = eruption_y - 35;
                            // Check if spawn position is within trigger_6 bounds
                            if (bossInst != null && bossInst.isPositionInTrigger6(eruptX, eruptY, attackWidth, attackHeight * 2f)) {
                                // ensure pooled instance has correct animation row for the eruption
                                eruption.addAnimation("attack", ROW_ATTACK_2, 0, 20, .1f, false);
                                eruption.reset();
                                eruption.play("attack");
                                boss.spawnEffect(eruption, eruptX, eruptY);
                            }
                        }
                        boss.safeRemoveEffect(warningRef.get());

                        // Reset attack flag after animation completes
                        boss.scheduleAction(0.25f, new Runnable() {
                            public void run() {
                                if (bossInst != null) {
                                    bossInst.isAttacking = false;
                                }
                            }
                        });
                    }
                });
            }
        };
        addAttack(debugAttack2);

        // Attack 3 - Quick burst attack (original)
        debugAttack3 = new Attack(2.5f) {
            @Override
            protected void start(Boss boss) {
                final FinalBoss bossInst = (boss instanceof FinalBoss) ? (FinalBoss) boss : null;
                if (bossInst != null && bossInst.isAttacking) {
                    return; // Don't start if another attack is in progress
                }
                if (bossInst != null) {
                    bossInst.isAttacking = true;
                }

                float px = bossInst != null ? bossInst.lastPlayerX : boss.getX();
                float py = bossInst != null ? bossInst.lastPlayerY : boss.getY();
                // Use pooled SFX for quick burst attack to avoid GL allocations during spawn.
                        SFX burst = obtainPooledSFX(attackPool, new Supplier<SFX>() {
                    @Override
                    public SFX get() {
                        SFX s = new SFX(attackSprite, spriteRows, spriteCols, attackWidth, attackHeight);
                        s.addAnimation("attack", ROW_ATTACK_3, 0, 20, 0.04f, false);
                        s.setAutoRemove(true);
                        return s;
                    }
                });
                if (burst != null) {
                    final float burstX = px - attackWidth / 2;
                    final float burstY = py - attackHeight / 2;
                    // Check if spawn position is within trigger_6 bounds
                    if (bossInst != null && bossInst.isPositionInTrigger6(burstX, burstY, attackWidth, attackHeight)) {
                        // ensure the pooled instance has the correct animation mapping for this attack
                        burst.addAnimation("attack", ROW_ATTACK_3, 0, 20, 0.04f, false);
                        burst.reset();
                        burst.play("attack");
                        boss.spawnEffect(burst, burstX, burstY);
                    }
                }

                // Reset attack flag after animation completes
                boss.scheduleAction(0.04f * 20, new Runnable() {
                    public void run() {
                        if (bossInst != null) {
                            bossInst.isAttacking = false;
                        }
                    }
                });
            }
        };
        addAttack(debugAttack3);

        // Attack 4 - Directional wave attack like Attack 1 but with different animation
        debugAttack4 = new Attack(5.0f) { // longer cooldown since it's a wave
            @Override
            protected void start(final Boss boss) {
                final FinalBoss bossInst = (boss instanceof FinalBoss) ? (FinalBoss) boss : null;
                if (bossInst != null && bossInst.isAttacking) {
                    return; // Don't start if another attack is in progress
                }
                if (bossInst != null) {
                    bossInst.isAttacking = true;
                }

                final float px = bossInst != null ? bossInst.lastPlayerX : boss.getX();
                final float py = bossInst != null ? bossInst.lastPlayerY : boss.getY();

                // Determine direction based on player position relative to arena center
                Rectangle t6 = bossInst != null ? bossInst.getTrigger6Bounds() : null;
                float arenaCenterX = (arenaLeft + arenaRight) * 0.5f;
                if (t6 != null) {
                    arenaCenterX = t6.x + t6.width * 0.5f;
                }
                
                // If player is on left side, sweep left-to-right; if on right, sweep right-to-left
                boolean sweepLeftToRight = px < arenaCenterX;
                
                // Determine spawn height based on active trigger zone
                // trigger_3, 5 → spawn above (upper area)
                // trigger_1, 2, 4 → spawn below (lower area)
                TriggerZone zone = bossInst != null ? bossInst.activeTriggerZone : TriggerZone.NONE;
                boolean trigger4Special = zone == TriggerZone.TRIGGER_4;
                boolean spawnAbove = false; // default to below unless upper triggers
                if (zone == TriggerZone.TRIGGER_3 || zone == TriggerZone.TRIGGER_5) {
                    spawnAbove = true;  // upper triggers: above
                } else {
                    spawnAbove = false; // lower/none/trigger6/trigger4: below
                }
                
                // Start position: very edge of trigger_6 (or arena if no trigger_6)
                float startX, sweepY;
                if (t6 != null) {
                    startX = sweepLeftToRight ? t6.x : (t6.x + t6.width);
                    // Set height based on trigger zone: upper 75% for above, lower 25% for below
                    sweepY = spawnAbove ? (t6.y + t6.height * 0.75f) : (t6.y + t6.height * 0.25f);
                } else {
                    // Fallback to launchpad positions if trigger_6 not set
                    startX = sweepLeftToRight ? launchpadLeftX : launchpadRightX;
                    sweepY = launchpadY;
                }

                // Calculate spacing and positions
                float spacing = attackWidth * 0.25f;
                final float playerYLocal = py;
                final float upperSearchY = spawnAbove ? sweepY : (t6 != null ? (t6.y + t6.height * 0.75f) : sweepY);
                // For lower triggers, bias search further down relative to player
                final float lowerSearchY = (zone == TriggerZone.TRIGGER_1 || zone == TriggerZone.TRIGGER_2 || trigger4Special)
                    ? Math.min(sweepY, playerYLocal - 120f)
                    : (spawnAbove ? (t6 != null ? (t6.y + t6.height * 0.25f) : sweepY) : sweepY);
                final boolean finalSpawnAbove = spawnAbove;
                final TriggerZone finalZone = zone;
                final boolean finalTrigger4Special = trigger4Special;

                // Build spawn X positions. For trigger_4, fire two at center then expand left/right.
                java.util.List<Float> spawnXs = new java.util.ArrayList<>();
                if (trigger4Special) {
                    float centerX = (t6 != null) ? (t6.x + t6.width * 0.5f) : (arenaLeft + arenaRight) * 0.5f;
                    float specialSpacing = attackWidth * 0.3f;
                    // Two quick strikes at center
                    spawnXs.add(centerX);
                    spawnXs.add(centerX);
                    // Then expand outward, alternating right/left
                    for (int i = 1; i <= 5; i++) {
                        float off = specialSpacing * i;
                        spawnXs.add(centerX + off);
                        spawnXs.add(centerX - off);
                    }
                } else {
                    // Default sweep list of X positions (13 waves)
                    for (int i = 0; i < 13; i++) {
                        float xOffset = sweepLeftToRight ? (i * spacing) : (-i * spacing);
                        spawnXs.add(startX + xOffset);
                    }
                }

                // No warning indicators for this attack (removed to cut allocations/hitches)

                // Spawn attacks in sequence
                final boolean finalSweepLeftToRight = sweepLeftToRight;
                for (int i = 0; i < spawnXs.size(); i++) {
                    final int index = i;
                    boss.scheduleAction(1.5f + (i * 0.25f), new Runnable() {
                        public void run() {
                            float spawnCenterX = spawnXs.get(index);
                            final float spawnX = spawnCenterX - attackWidth / 2;
                            
                            // Determine search bounds based on trigger zone and trigger_6 bounds
                            float searchMinY, searchMaxY;
                            if (t6 != null) {
                                // Respect trigger_6 vertical bounds
                                if (finalSpawnAbove) {
                                    // Upper area: search in top half of trigger_6
                                    searchMinY = t6.y + t6.height * 0.5f;
                                    searchMaxY = t6.y + t6.height;
                                } else {
                                    // Lower area (triggers 1, 2, 4): search in bottom half
                                    searchMinY = t6.y;
                                    searchMaxY = t6.y + t6.height * 0.5f;
                                }
                            } else {
                                // Fallback if no trigger_6 defined
                                searchMinY = playerYLocal - 200f;
                                searchMaxY = playerYLocal + 200f;
                            }
                            
                            // Find ground at this exact spawn X position within trigger area
                            float searchCenterY = (searchMinY + searchMaxY) * 0.5f;
                            float groundY = findGroundYInRange(spawnX + attackWidth / 2f, searchCenterY, attackWidth, searchMinY, searchMaxY);
                            
                            // Place wave just above the detected ground surface (follow contour)
                            float spawnY = groundY - 35f;
                            
                            // Final clamp inside trigger_6 bounds
                            if (t6 != null) {
                                float minY = t6.y;
                                float maxY = t6.y + t6.height - attackHeight;
                                if (spawnY < minY) spawnY = minY;
                                if (spawnY > maxY) spawnY = maxY;
                            }
                            // Create attack SFX on the GL thread to avoid OpenGL calls from background threads
                            // Use pooled attack SFX to avoid creating a new SpriteAnimator here
                            SFX wave = obtainPooledSFX(attackPool, new Supplier<SFX>() {
                                @Override
                                public SFX get() {
                                    SFX s = new SFX(attackSprite, spriteRows, spriteCols, attackWidth, attackHeight);
                                    s.addAnimation("attack", ROW_ATTACK_4, 0, 20, 0.05f, false);
                                    s.setAutoRemove(true);
                                    return s;
                                }
                            });
                            if (wave != null) {
                                // Check if spawn position is within trigger_6 bounds
                                if (bossInst != null && bossInst.isPositionInTrigger6(spawnX, spawnY, attackWidth, attackHeight)) {
                                    // Ensure a pooled instance gets the correct animation mapping
                                    wave.addAnimation("attack", ROW_ATTACK_4, 0, 20, 0.05f, false);
                                    wave.reset();
                                    wave.play("attack");
                                    boss.spawnEffect(wave, spawnX, spawnY);
                                }
                            }

                            // No indicator to remove for this attack

                            // Reset attacking flag after last impact
                            if (index == spawnXs.size() - 1 && bossInst != null) {
                                bossInst.isAttacking = false;
                            }
                        }
                    });
                }
            }
        };
        addAttack(debugAttack4);

    // Configure phase-specific weights and per-attack cooldowns.
    // Attacks are in order: 0=attack1, 1=attack2, 2=attack3, 3=attack4
    // Phase weights (higher = more likely). These arrays map to attack indices above.
    // Phase 1: mostly attacks 1 and 2
    configurePhaseWeights(1, new float[]{0.40f, 0.40f, 0.10f, 0.10f});
    // Phase 2: balanced across all attacks
    configurePhaseWeights(2, new float[]{0.25f, 0.25f, 0.25f, 0.25f});
    // Phase 3: prioritize attacks 3 and 4 (more frequent)
    configurePhaseWeights(3, new float[]{0.10f, 0.10f, 0.40f, 0.40f});

    // Per-attack per-phase cooldown overrides (index 0 unused — indexes 1..3 = phases)
    // If you leave these unset, the boss will fall back to baseCooldown * phaseMultiplier.
    debugAttack1.configurePerPhaseCooldowns(new float[]{0f, 4.5f, 4.0f, 3.5f}); // attack1 speeds up across phases
    debugAttack2.configurePerPhaseCooldowns(new float[]{0f, 6.0f, 5.0f, 4.0f}); // ground eruption slows slightly then speeds
    debugAttack3.configurePerPhaseCooldowns(new float[]{0f, 2.5f, 2.0f, 1.5f}); // quick burst becomes quicker
    debugAttack4.configurePerPhaseCooldowns(new float[]{0f, 5.0f, 4.0f, 3.0f}); // wave attack becomes faster in later phases

    // Keep general per-phase multipliers as fallbacks (optional tuning)
    configurePhaseCooldownMultiplier(1, 1.0f);
    configurePhaseCooldownMultiplier(2, 0.85f);
    configurePhaseCooldownMultiplier(3, 0.7f);
    }

    // Helper: obtain pooled SFX instance (reuses finished instances or creates new via supplier)
    // Must be called on the GL thread when creating new instances
    private SFX obtainPooledSFX(List<SFX> pool, Supplier<SFX> creator) {
        for (SFX s : pool) {
            if (s.isFinished()) {
                return s;
            }
        }
        long t0 = Boss.SFX_PROFILING ? System.nanoTime() : 0L;
        SFX n = creator.get();
        long t1 = Boss.SFX_PROFILING ? System.nanoTime() : 0L;
        pool.add(n);
        if (Boss.SFX_PROFILING) {
            try { com.badlogic.gdx.Gdx.app.log("SFX_PROF", "obtainPooledSFX: createdMillis=" + ((t1-t0)/1_000_000.0)); } catch (Exception ignored) {}
        }
        return n;
    }

    // Find the highest ground below a given point within a vertical search band
    private float findGroundY(float centerX, float centerY, float width) {
        float groundY = centerY - groundAttackOffset;
        float range = groundAttackOffset * 2f; // Expand search range to find topmost surfaces
        Rectangle searchArea = new Rectangle(centerX - (width / 2f), centerY - range, width, range * 2f);

        if (solids != null) {
            for (Solid solid : solids) {
                if (!solid.isBlocking()) continue;
                Rectangle bounds = solid.getCollisionBounds();
                if (bounds != null && bounds.overlaps(searchArea)) {
                    float top = bounds.y + bounds.height;
                    if (top > groundY) {
                        groundY = top;
                    }
                }
            }
        }

        if (walls != null) {
            for (Wall wall : walls) {
                Rectangle b = wall.getBounds();
                if (b != null && b.overlaps(searchArea)) {
                    float top = b.y + b.height;
                    if (top > groundY) {
                        groundY = top;
                    }
                }
            }
        }

        return groundY;
    }

    // Find the highest ground at X position within a specific Y range (respects trigger zones)
    private float findGroundYInRange(float centerX, float centerY, float width, float minY, float maxY) {
        float groundY = minY;
        boolean found = false;

        // 1) Prefer surfaces below the trigger band
        float belowMin = minY - 160f;
        float belowMax = minY;
        Rectangle belowArea = new Rectangle(centerX - (width / 2f), belowMin, width, belowMax - belowMin);
        found = collectHighestSurface(belowArea, belowMin, belowMax);
        if (found) {
            groundY = tempGroundY;
        }

        // 2) If none below, search inside the trigger band
        if (!found) {
            Rectangle bandArea = new Rectangle(centerX - (width / 2f), minY, width, maxY - minY);
            found = collectHighestSurface(bandArea, minY, maxY);
            if (found) {
                groundY = tempGroundY;
            }
        }

        // 3) If still none, allow slightly above the band
        if (!found) {
            float aboveMin = maxY;
            float aboveMax = maxY + 160f;
            Rectangle aboveArea = new Rectangle(centerX - (width / 2f), aboveMin, width, aboveMax - aboveMin);
            found = collectHighestSurface(aboveArea, aboveMin, aboveMax);
            if (found) {
                groundY = tempGroundY;
            }
        }

        // 4) Last resort: full-range
        if (!found) {
            groundY = findGroundY(centerX, centerY, width);
        }

        return groundY;
    }

    // Temp holder for helper searches (single-threaded use only)
    private float tempGroundY = Float.NEGATIVE_INFINITY;

    // Collect highest surface within area and Y bounds; sets tempGroundY and returns true if found
    private boolean collectHighestSurface(Rectangle area, float minY, float maxY) {
        float best = Float.NEGATIVE_INFINITY;
        boolean hit = false;

        if (solids != null) {
            for (Solid solid : solids) {
                if (!solid.isBlocking()) continue;
                Rectangle bounds = solid.getCollisionBounds();
                if (bounds != null && bounds.overlaps(area)) {
                    float top = bounds.y + bounds.height;
                    if (top >= minY && top <= maxY && top > best) {
                        best = top;
                        hit = true;
                    }
                }
            }
        }

        if (walls != null) {
            for (Wall wall : walls) {
                Rectangle b = wall.getBounds();
                if (b != null && b.overlaps(area)) {
                    float top = b.y + b.height;
                    if (top >= minY && top <= maxY && top > best) {
                        best = top;
                        hit = true;
                    }
                }
            }
        }

        tempGroundY = hit ? best : Float.NEGATIVE_INFINITY;
        return hit;
    }

    private String colorToHex(Color c) {
        if (c == null) return "null";
        int r = (int)(c.r * 255f) & 0xFF;
        int g = (int)(c.g * 255f) & 0xFF;
        int b = (int)(c.b * 255f) & 0xFF;
        return String.format("#%02X%02X%02X", r, g, b);
    }

    // --- Debug overlay ---
    private BitmapFont debugFont;
    private boolean debugOverlayEnabled = true;
    private final GlyphLayout glyph = new GlyphLayout();

    // --- Custom scheduling state ---
    private boolean phase1ChainActive = false;
    // Phase1 patterns (A/B/C) indexed by attack list order (0=attack1, 1=attack2, 2=attack3, 3=attack4)
    private final int[][] phase1Patterns = new int[][]{
        {1, 3, 1, 2}, // Neutral A: ground -> wave -> ground -> burst
        {3, 1, 2, 3}, // Neutral B
        {2, 1, 3, 1}  // Neutral C
    };
    // Trigger-biased variants so left/right edge influence the cadence
    private final int[][] phase1PatternsLeft = new int[][]{
        {1, 1, 2, 3}, // Emphasize eruptions when player hugs left wall
        {3, 1, 3, 2},
        {1, 2, 3, 3}
    };
    private final int[][] phase1PatternsRight = new int[][]{
        {3, 3, 1, 2}, // Favor sweeping waves when player hugs right wall
        {2, 3, 1, 3},
        {3, 1, 2, 1}
    };
    private int phase1PatternIndex = 0;
    private final float phase1Pause = 0.75f; // slightly longer pause between attacks in phase1 sequence

    private int phase2RandomsSinceGuaranteed = 0;
    private int lastStartedAttackIndex = -1;
    // Phase2 patterns (A/B/C) — neutral and trigger-biased variants
    private final int[][] phase2Patterns = new int[][]{
        {0, 3, 1, 2},
        {3, 1, 0, 3},
        {2, 3, 0, 1}
    };
    private final int[][] phase2PatternsLeft = new int[][]{
        {1, 0, 2, 3}, // Lead with eruptions when cornered left
        {1, 2, 3, 0},
        {2, 1, 3, 0}
    };
    private final int[][] phase2PatternsRight = new int[][]{
        {3, 0, 1, 2}, // Lead with waves when cornered right
        {3, 2, 0, 1},
        {0, 3, 2, 1}
    };
    private int phase2PatternIndex = 0;

    // Phase3 sequence state
    // Phase3 patterns (A/B/C) — sequences of 5 attacks each
    private final int[][] phase3Patterns = new int[][]{
        {0, 3, 2, 1, 3},
        {1, 0, 2, 3, 1},
        {3, 2, 0, 1, 2}
    };
    private final int[][] phase3PatternsLeft = new int[][]{
        {1, 2, 3, 1, 2}, // Tight, ground-focused when player pins to left
        {1, 3, 2, 1, 3},
        {2, 1, 3, 2, 1}
    };
    private final int[][] phase3PatternsRight = new int[][]{
        {3, 0, 3, 2, 0}, // More waves to sweep from right
        {3, 2, 0, 3, 1},
        {0, 3, 2, 0, 1}
    };
    private int phase3PatternIndex = 0;
    private int phase3SeqPos = 0;
    private int phase3SeqRepeatCount = 0;
    private final float phase3AttackPause = 0.9f; // slightly longer
    private final float phase3ShortPause = 0.6f;

    // Low-HP modifiers
    private final float lowHpThreshold = 0.15f;
    private float attackDamageMultiplier = 1.0f; // exposed for attacks to use if desired

    @Override
    public void disposeParts() {
        // Dispose animators first
        super.disposeParts();
        // Dispose debug font
        if (debugFont != null) {
            try { debugFont.dispose(); } catch (Exception ignored) {}
            debugFont = null;
        }
    }

    @Override
    public void render(com.badlogic.gdx.graphics.g2d.SpriteBatch batch) {
        // Render boss visuals and active effects
        super.render(batch);

        // Draw debug overlay if enabled and font is available
        if (!debugOverlayEnabled || debugFont == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            int p = getPhase();
            sb.append("Phase: ").append(p).append('\n');

            // Phase weights
            float[] weights = getPhaseWeights(p);
            if (weights != null) {
                sb.append("Weights: ");
                for (int i = 0; i < weights.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(String.format("%d:%.2f", i, weights[i]));
                }
                sb.append('\n');
            }

            // Per-attack cooldowns for current phase
            List<Attack> atks = getAttacks();
            for (int i = 0; i < atks.size(); i++) {
                Attack a = atks.get(i);
                float base = a.getCooldown();
                float phaseCd = a.getCooldownForPhase(this);
                String name = a.getClass().getSimpleName();
                if (name == null || name.isEmpty()) name = "Attack#" + i;
                sb.append(String.format("%s (idx %d): base=%.2fs phase=%.2fs", name, i, base, phaseCd));
                if (i < atks.size()-1) sb.append('\n');
            }

            if (debugTriggerInfo) {
                sb.append('\n');
                sb.append("Trigger Zone: ").append(activeTriggerZone).append('\n');
                sb.append("Trigger (L/R): ").append(activeTrigger).append('\n');
                int preferredAtk = getPreferredAttackForTrigger();
                sb.append("Preferred Attack: ").append(preferredAtk >= 0 ? preferredAtk : "any").append('\n');
                sb.append(String.format("Arena L/R: %.1f / %.1f", arenaLeft, arenaRight)).append('\n');
                sb.append(String.format("Pads L/R/Y: %.1f / %.1f / %.1f", launchpadLeftX, launchpadRightX, launchpadY)).append('\n');
                sb.append(String.format("Trigger colors L/R: %s / %s",
                        colorToHex(triggerLeftColor), colorToHex(triggerRightColor)));
            }

            // Draw text in top-left corner above boss position
            float drawX = getX() - 200f; // offset left of boss
            float drawY = getY() + 300f; // above boss
            glyph.setText(debugFont, sb.toString());
            debugFont.draw(batch, glyph, drawX, drawY);
        } catch (Exception ignored) {}
    }

    // --- Custom scheduler helpers ---
    private boolean tryStartAttackIndex(int idx) {
        List<Attack> atks = getAttacks();
        if (atks == null || idx < 0 || idx >= atks.size()) return false;
        
        // Check if attack is allowed in current trigger zone
        if (!isAttackAllowedInTrigger(idx)) {
            // Try to use preferred attack for this trigger instead
            int preferred = getPreferredAttackForTrigger();
            if (preferred >= 0 && preferred < atks.size() && preferred != idx) {
                idx = preferred;
            } else if (idx != 2) {
                // If preferred not available, try attack3 (burst) which works anywhere
                idx = 2;
            }
        }
        
        Attack a = atks.get(idx);
        if (a.tryStart(this)) {
            // set boss cooldown so automatic scheduling (if any) doesn't interfere
            setAttackCooldownTimer(a.getCooldownForPhase(this));
            lastStartedAttackIndex = idx;
            return true;
        }
        return false;
    }

    private int[][] patternSetForPhase1() {
        if (activeTrigger == TriggerSide.LEFT) return phase1PatternsLeft;
        if (activeTrigger == TriggerSide.RIGHT) return phase1PatternsRight;
        return phase1Patterns;
    }

    private int[][] patternSetForPhase2() {
        if (activeTrigger == TriggerSide.LEFT) return phase2PatternsLeft;
        if (activeTrigger == TriggerSide.RIGHT) return phase2PatternsRight;
        return phase2Patterns;
    }

    private int[][] patternSetForPhase3() {
        if (activeTrigger == TriggerSide.LEFT) return phase3PatternsLeft;
        if (activeTrigger == TriggerSide.RIGHT) return phase3PatternsRight;
        return phase3Patterns;
    }

    private int pickPatternIndex(int[][] patterns, int lastIndex, int maxAttempts) {
        if (patterns == null || patterns.length == 0) return 0;
        int chosen = lastIndex;
        int attempts = 0;
        while (attempts++ < Math.max(1, maxAttempts)) {
            int candidate = com.badlogic.gdx.math.MathUtils.random(0, patterns.length-1);
            if (candidate != lastIndex) {
                chosen = candidate;
                break;
            }
        }
        return Math.max(0, Math.min(patterns.length - 1, chosen));
    }

    private void startPhase1Chain() {
        if (phase1ChainActive) return;
        phase1ChainActive = true;
        final int[][] patterns = patternSetForPhase1();
        int chosen1 = pickPatternIndex(patterns, phase1PatternIndex, 5);
        final int[] pattern = patterns[Math.max(0, Math.min(patterns.length - 1, chosen1))];
        phase1PatternIndex = chosen1;
        // schedule the pattern attacks in sequence
        for (int i = 0; i < pattern.length; i++) {
            final int attackIdx = pattern[i];
            final float delay = i * phase1Pause;
            scheduleAction(delay, new Runnable() {
                @Override
                public void run() {
                    if (getPhase() != 1) { phase1ChainActive = false; return; }
                    tryStartAttackIndex(attackIdx);
                }
            });
        }
        // after the pattern, allow next pattern to start (loop)
        scheduleAction(pattern.length * phase1Pause + 0.05f, new Runnable() {
            @Override
            public void run() {
                if (getPhase() != 1) { phase1ChainActive = false; return; }
                phase1ChainActive = false; // allow restart which will schedule next loop
            }
        });
    }

    private boolean phase2ChainActive = false;
    private final float phase2Pause = 0.5f;
    private void startPhase2Chain() {
        if (phase2ChainActive) return;
        phase2ChainActive = true;
        final int[][] patterns = patternSetForPhase2();
        int patternsLen = patterns.length;
        int chosen2 = phase2PatternIndex;
        if (patternsLen > 1) {
            int attempts = 0;
            while (attempts++ < 6) {
                chosen2 = com.badlogic.gdx.math.MathUtils.random(0, patternsLen-1);
                if (chosen2 != phase2PatternIndex) break;
            }
        }
        int[] pattern = patterns[chosen2];
        phase2PatternIndex = chosen2;
        // if the pattern would start with the same attack that just ran, try a different one
        if (pattern.length > 0 && pattern[0] == lastStartedAttackIndex) {
            for (int i = 0; i < patternsLen; i++) {
                int alt = (chosen2 + 1 + i) % patternsLen;
                if (patterns[alt].length > 0 && patterns[alt][0] != lastStartedAttackIndex) {
                    pattern = patterns[alt];
                    phase2PatternIndex = alt;
                    break;
                }
            }
        }
        // schedule the pattern
        for (int i = 0; i < pattern.length; i++) {
            final int attackIdx = pattern[i];
            final float delay = i * phase2Pause;
            scheduleAction(delay, new Runnable() {
                @Override
                public void run() {
                    if (getPhase() != 2) { phase2ChainActive = false; return; }
                    tryStartAttackIndex(attackIdx);
                }
            });
        }
        // after pattern, advance and allow restart
        scheduleAction(pattern.length * phase2Pause + 0.05f, new Runnable() {
            @Override
            public void run() {
                if (getPhase() != 2) { phase2ChainActive = false; return; }
                // randomized already; allow next run to randomize again
                phase2ChainActive = false;
            }
        });
    }

    private boolean phase3ChainActive = false;
    private void startPhase3Chain() {
        if (phase3ChainActive) return;
        phase3ChainActive = true;
        final int[][] patterns = patternSetForPhase3();
        final int patternsLen = patterns.length;
        int chosen3 = phase3PatternIndex;
        if (patternsLen > 1) {
            int attempts = 0;
            while (attempts++ < 6) {
                chosen3 = com.badlogic.gdx.math.MathUtils.random(0, patternsLen-1);
                if (chosen3 != phase3PatternIndex) break;
            }
        }
        final int[] pattern = patterns[Math.max(0, Math.min(patternsLen - 1, chosen3))];
        phase3PatternIndex = chosen3;
        // Determine low-HP speed multiplier
        float speedMult = 1.0f;
        if (getHealthSystem() != null) {
            float cur = getHealthSystem().getCurrentHealth();
            float max = getHealthSystem().getMaxHealth();
            if (max > 0f && (cur / max) <= lowHpThreshold) speedMult = 0.75f; // faster when low HP
        }
        final float pause = phase3AttackPause * speedMult;
        // schedule pattern twice
        float offset = 0f;
        for (int rep = 0; rep < 2; rep++) {
            for (int s = 0; s < pattern.length; s++) {
                final int idx = pattern[s];
                final float delay = offset + s * pause;
                scheduleAction(delay, new Runnable() {
                    @Override
                    public void run() {
                        if (getPhase() != 3) { phase3ChainActive = false; return; }
                        tryStartAttackIndex(idx);
                    }
                });
            }
            offset += pattern.length * pause;
        }
        // after two repeats, short pause then allow restart
        final float totalDur = offset + phase3ShortPause * speedMult;
        scheduleAction(totalDur, new Runnable() {
            @Override
            public void run() {
                if (getPhase() != 3) { phase3ChainActive = false; return; }
                // randomized already; allow next run to randomize again
                phase3ChainActive = false;
            }
        });
    }

    @Override
    public void update(float delta) {
        // Run parent logic (attacks, effects, scheduling, etc.) first
        super.update(delta);

        if (triggerCooldownTimer > 0f) {
            triggerCooldownTimer = Math.max(0f, triggerCooldownTimer - delta);
        }

        // Smooth chasing toward player with acceleration, clamp, and damping
        if (lastPlayerX != 0 || lastPlayerY != 0) {
            // Body extents (use actual dimensions to stay correct if scaled differently)
            float bodyWidthHalf = bodyWidth * 0.5f;
            float bodyHeightHalf = bodyHeight * 0.5f;

            // Current body center
            float bossCenterX = getX() + bodyWidthHalf;
            float bossCenterY = getY() + bodyHeightHalf;

            // Compute bobbing offset (reuse inherited bob timer state)
            float hoverOffset = baseHoverHeight + (float)(Math.sin(bobTimer * bobFrequency) * bobAmplitude) + altitudeBias;

            // Decide which side to favor: trigger overrides, otherwise follow player drift
            float desiredSide = orbitSideSign;
            if (activeTrigger == TriggerSide.LEFT) desiredSide = -1f;
            else if (activeTrigger == TriggerSide.RIGHT) desiredSide = 1f;
            else {
                float moveX = lastPlayerX - prevPlayerX;
                if (Math.abs(moveX) > 0.25f) desiredSide = Math.signum(moveX);
            }
            orbitSideSign += (desiredSide - orbitSideSign) * sideLerp;

            // Target anchor: offset sideways and slightly above for a stalking arc
            float angleRad = (float)Math.toRadians(orbitAngleDeg);
            float offsetX = orbitSideSign * hoverRadius;
            float offsetY = (float)(Math.sin(angleRad) * hoverRadius);
            float targetX = lastPlayerX + offsetX;
            float targetY = lastPlayerY + hoverOffset + offsetY;

            // Vector to target
            float dx = targetX - bossCenterX;
            float dy = targetY - bossCenterY;

            // Apply eased acceleration with slower vertical catch-up to avoid snapping
            chasingVelocityX += dx * chasingAcceleration * delta;
            chasingVelocityY += dy * chasingAcceleration * delta * (1f - verticalLagFactor);

            // Clamp speed
            float speed = (float)Math.sqrt(chasingVelocityX * chasingVelocityX + chasingVelocityY * chasingVelocityY);
            if (speed > chasingMaxSpeed) {
                float scale = chasingMaxSpeed / speed;
                chasingVelocityX *= scale;
                chasingVelocityY *= scale;
            }

            // Damping for weighty feel
            chasingVelocityX *= chasingDamping;
            chasingVelocityY *= (chasingDamping - 0.05f);

            // Apply movement (re-center by body extents)
            float newCenterX = bossCenterX + chasingVelocityX * delta;
            float newCenterY = bossCenterY + chasingVelocityY * delta;
            setPosition(newCenterX - bodyWidthHalf, newCenterY - bodyHeightHalf);
        }

        // Debug controls: press 1-4 to manually trigger each configured attack.
        try {
            if (!isAttacking) {  // Only allow new attacks if no attack is in progress
                if (Gdx.input.isKeyJustPressed(Input.Keys.NUM_1)) {
                    if (debugAttack1 != null) debugAttack1.tryStart(this);
                }
                if (Gdx.input.isKeyJustPressed(Input.Keys.NUM_2)) {
                    if (debugAttack2 != null) debugAttack2.tryStart(this);
                }
                if (Gdx.input.isKeyJustPressed(Input.Keys.NUM_3)) {
                    if (debugAttack3 != null) debugAttack3.tryStart(this);
                }
                if (Gdx.input.isKeyJustPressed(Input.Keys.NUM_4)) {
                    if (debugAttack4 != null) debugAttack4.tryStart(this);
                }
            }
        } catch (Exception ignored) {
        }

        // Toggle debug overlay with F2
        try {
            if (Gdx.input.isKeyJustPressed(Input.Keys.F2)) {
                debugOverlayEnabled = !debugOverlayEnabled;
            }
        } catch (Exception ignored) {}
        // Custom attack scheduler (this instance manages patterns per phase)
        try {
            // adjust low-HP modifiers
            if (getHealthSystem() != null) {
                float cur = getHealthSystem().getCurrentHealth();
                float max = getHealthSystem().getMaxHealth();
                if (max > 0f && (cur / max) <= lowHpThreshold) {
                    attackDamageMultiplier = 1.15f;
                } else {
                    attackDamageMultiplier = 1.0f;
                }
            }

            int p = getPhase();
            // Phase 1: patterned sequences until phase change
            if (p == 1) {
                // Ensure the chain is running
                startPhase1Chain();
            }

            // Phase 2: random (no same twice); after 3 randoms -> guaranteed attack4
            if (p == 2) {
                // Use patterned sequences for phase2
                startPhase2Chain();
            }

            // Phase 3: deterministic sequence [1,4,3,2] repeat twice, short pause, repeat
            if (p == 3) {
                // start a chained sequence if not active
                startPhase3Chain();
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void setTarget(float x, float y) {
        // Store previous position before updating
        this.prevPlayerX = this.lastPlayerX;
        this.prevPlayerY = this.lastPlayerY;
        // capture last known player position for attacks that target the player
        this.lastPlayerX = x;
        this.lastPlayerY = y;
        refreshTriggerState(x);
        super.setTarget(x, y);
    }

    // Allow arena bounds to be injected by the room/level setup
    public void setArenaBounds(float left, float right) {
        this.arenaLeft = left;
        this.arenaRight = right;
    }

    public void setTriggerWidth(float width) {
        this.triggerWidth = Math.max(0f, width);
    }

    public void setLaunchpads(float leftX, float rightX, float y) {
        this.launchpadLeftX = leftX;
        this.launchpadRightX = rightX;
        this.launchpadY = y;
    }

    private void refreshTriggerState(float playerX) {
        if (!(arenaLeft < arenaRight)) return; // arena not configured
        float mid = (arenaLeft + arenaRight) * 0.5f;
        float dead = Math.max(0f, triggerWidth); // half-deadzone (0 = exact center split)

        // Update legacy left/right trigger for compatibility
        TriggerSide newSide = TriggerSide.NONE;
        if (playerX < mid - dead) newSide = TriggerSide.LEFT;
        else if (playerX > mid + dead) newSide = TriggerSide.RIGHT;

        if (triggerCooldownTimer > 0f) return;
        if (newSide != activeTrigger) {
            activeTrigger = newSide;
            triggerCooldownTimer = triggerCooldown;
        }
    }

    // Set the current trigger zone (called by level/room when player enters a trigger)
    public void setActiveTriggerZone(String triggerId) {
        if (triggerId == null) {
            activeTriggerZone = TriggerZone.NONE;
            return;
        }
        
        switch (triggerId.toLowerCase()) {
            case "trigger_1":
                activeTriggerZone = TriggerZone.TRIGGER_1;
                break;
            case "trigger_2":
                activeTriggerZone = TriggerZone.TRIGGER_2;
                break;
            case "trigger_3":
                activeTriggerZone = TriggerZone.TRIGGER_3;
                break;
            case "trigger_4":
                activeTriggerZone = TriggerZone.TRIGGER_4;
                break;
            case "trigger_5":
                activeTriggerZone = TriggerZone.TRIGGER_5;
                break;
            case "trigger_6":
                activeTriggerZone = TriggerZone.TRIGGER_6;
                break;
            default:
                activeTriggerZone = TriggerZone.NONE;
                break;
        }
    }
}