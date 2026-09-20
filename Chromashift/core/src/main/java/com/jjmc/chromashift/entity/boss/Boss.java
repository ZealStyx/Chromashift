package com.jjmc.chromashift.entity.boss;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.jjmc.chromashift.effects.SFX;
import com.jjmc.chromashift.entity.Entity;
import com.chromashift.helper.SpriteAnimator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Simple Boss implementation with multiple attack support.
 *
 * Features:
 * - Holds a list of attack definitions (round-robin)
 * - Schedules delayed actions (indicator -> landing)
 * - Manages spawned SFX for attacks
 *
 * Note: This class deliberately keeps gameplay integration minimal. Attacks
 * can spawn SFX and schedule callbacks; integrate collision/damage with
 * your game loop (check Boss.getActiveEffects()) or extend Attack to deal damage.
 */
public class Boss extends Entity {

    // SpriteAnimator parts for boss segments (independent animations)
    protected SpriteAnimator bodyAnim;
    protected float bodyOffsetX, bodyOffsetY, bodyWidth, bodyHeight;

    protected SpriteAnimator leftUpperArmAnim;
    protected float leftUpperOffsetX, leftUpperOffsetY, leftUpperWidth, leftUpperHeight;

    protected SpriteAnimator leftLowerArmAnim;
    protected float leftLowerOffsetX, leftLowerOffsetY, leftLowerWidth, leftLowerHeight;

    protected SpriteAnimator rightUpperArmAnim;
    protected float rightUpperOffsetX, rightUpperOffsetY, rightUpperWidth, rightUpperHeight;

    protected SpriteAnimator rightLowerArmAnim;
    protected float rightLowerOffsetX, rightLowerOffsetY, rightLowerWidth, rightLowerHeight;

    private final List<Attack> attacks = new ArrayList<>();
    private int nextAttackIndex = 0;
    private float attackCooldownTimer = 0f;
    // --- Phase system (1..3) ---
    // Current phase (1..3). Phase affects attack priority and frequency.
    private int phase = 1;
    // Per-phase explicit attack orders. Index by phase number (1..3). If null the identity order is used.
    private final List<int[]> phaseOrders = new ArrayList<int[]>(4);
    // Per-phase cooldown multiplier (index 1..3). Lower multiplier => more frequent attacks.
    private final float[] phaseCooldownMultipliers = new float[]{0f, 1.0f, 0.85f, 0.7f};
    // Track next index separately per phase to preserve round-robin per-phase (used as fallback).
    private final int[] nextIndexForPhase = new int[4];
    // Whether to automatically change phase based on health percentage. Default ON: phase follows health.
    private boolean autoPhaseByHealth = true;
    // Per-phase weighted selection arrays (if configured, used instead of explicit order). Each float[] holds weights for the attack list indices.
    private final List<float[]> phaseWeights = new ArrayList<float[]>(4);
    // Health threshold configuration for automatic phase transitions (fractions of max health): high->mid, mid->low
    private float phaseHighThreshold = 0.66f;
    private float phaseLowThreshold = 0.33f;

    // Active special effects spawned by the boss (indicator, impact, etc.)
    private final List<SFX> activeEffects = new ArrayList<>();

    // If true, the subclass (e.g. BossInstance) will manage attack scheduling
    // itself and Boss.update() should not run the automatic attack picker.
    private boolean useCustomAttackScheduler = false;

    // Executor for background work (creating SFX payloads, asset parsing, etc.)
    // Note: actual Texture/GL objects must be created on the GL thread. Use
    // this to prepare data and then post creation to the GL thread with
    // Gdx.app.postRunnable.
    // Use a fixed-size pool to avoid creating unbounded threads under heavy load.
    private static final int BG_POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
    private static final ExecutorService bgExecutor = Executors.newFixedThreadPool(BG_POOL_SIZE);

    // Lightweight profiling switch: enable to emit SFX creation/spawn timings.
    // Set to true to gather lightweight logs; keep false in production.
    public static final boolean SFX_PROFILING = false;

    private static void profLog(String tag, String msg) {
        if (!SFX_PROFILING) return;
        try {
            com.badlogic.gdx.Gdx.app.log(tag, msg);
        } catch (Exception ignored) {}
    }

    // Reusable temporary collections to avoid per-frame allocations
    private final List<SFX> effectsToRemove = new ArrayList<>();
    private final List<PendingAction> duePending = new ArrayList<>();

    // Thread-safe queue for removals requested from other contexts. Drained
    // on the GL thread during update() to actually remove from activeEffects.
    private final ConcurrentLinkedQueue<SFX> removalQueue = new ConcurrentLinkedQueue<>();

    // Scheduled delayed actions (delay seconds -> Runnable)
    private final List<PendingAction> pending = new ArrayList<>();

    // Spawning state
    private boolean isSpawning = false;
    private boolean hasSpawned = false;
    private float spawnTimer = 0f;
    private float spawnDuration = 2.0f; // Default spawn animation duration

    // Movement and targeting
    protected float targetX, targetY;
    protected float velocityX, velocityY;
    protected float followSpeed = 50f;  // Speed of movement
    protected float maxSpeed = 1000f;   // Maximum movement speed
    protected float baseHoverHeight = 100f; // Base height above player to maintain
    protected float bobAmplitude = 70f;    // How far to bob up and down
    protected float bobFrequency = 100f;     // How fast to bob
    protected float bobTimer = 0f;
    protected float armPhysicsDelay = 0.2f; // Delay for arm movement (seconds)
    
    // Arm physics tracking
    protected float[] leftUpperPhysicsPos = new float[]{0f, 0f};
    protected float[] leftLowerPhysicsPos = new float[]{0f, 0f};
    protected float[] rightUpperPhysicsPos = new float[]{0f, 0f};
    protected float[] rightLowerPhysicsPos = new float[]{0f, 0f};
    protected float armLerpSpeed = 5f; // How quickly arms catch up

    public Boss(float maxHealth) {
        super(maxHealth);
        
        initBody("entity/boss/body/bossmain.png", 1, 15, 224f, 240f, 0, 0); // center offset
        bodyAnim.addAnimation("idle", 0, 0, 15, 0.1f, true);
        playBody("idle", false);
        
        // Initialize arms from bossarm.png (4 rows x 15 cols)
        // Each row is a different arm type, sharing the same sheet
        float armWidth = 224f;  // Adjust these to match your desired size in world units
        float armHeight = 240f;
        
        // Upper Left Arm (Row 0)
        initLeftUpper("entity/boss/body/bossarm.png", 4, 15, armWidth, armHeight, 0, 0f);
        leftUpperArmAnim.addAnimation("idle", 0, 0, 15, 0.1f, true);
        playLeftUpper("idle", false);
        
        // Lower Left Arm (Row 1)
        initLeftLower("entity/boss/body/bossarm.png", 4, 15, armWidth, armHeight, 0, 0);
        leftLowerArmAnim.addAnimation("idle", 1, 0, 15, 0.1f, true);
        playLeftLower("idle", false);
        
        // Upper Right Arm (Row 2)
        initRightUpper("entity/boss/body/bossarm.png", 4, 15, armWidth, armHeight, 0, 0f);
        rightUpperArmAnim.addAnimation("idle", 2, 0, 15, 0.1f, true);
        playRightUpper("idle", false);
        
        // Lower Right Arm (Row 3)
        initRightLower("entity/boss/body/bossarm.png", 4, 15, armWidth, armHeight, 0, 0);
        rightLowerArmAnim.addAnimation("idle", 3, 0, 15, 0.1f, true);
        playRightLower("idle", false);
    }

    // --- Part initialization helpers ---
    protected void initBody(String spriteSheetPath, int rows, int cols, float w, float h, float offX, float offY) {
        disposeAnimator(bodyAnim);
        bodyAnim = new SpriteAnimator(spriteSheetPath, rows, cols);
        this.bodyWidth = w; this.bodyHeight = h; this.bodyOffsetX = offX; this.bodyOffsetY = offY;
        
        // Set default state time to prevent first-frame flicker
        bodyAnim.reset();
    }

    protected void initLeftUpper(String spriteSheetPath, int rows, int cols, float w, float h, float offX, float offY) {
        disposeAnimator(leftUpperArmAnim);
        leftUpperArmAnim = new SpriteAnimator(spriteSheetPath, rows, cols);
        this.leftUpperWidth = w; this.leftUpperHeight = h; this.leftUpperOffsetX = offX; this.leftUpperOffsetY = offY;
    }

    protected void initLeftLower(String spriteSheetPath, int rows, int cols, float w, float h, float offX, float offY) {
        disposeAnimator(leftLowerArmAnim);
        leftLowerArmAnim = new SpriteAnimator(spriteSheetPath, rows, cols);
        this.leftLowerWidth = w; this.leftLowerHeight = h; this.leftLowerOffsetX = offX; this.leftLowerOffsetY = offY;
    }

    protected void initRightUpper(String spriteSheetPath, int rows, int cols, float w, float h, float offX, float offY) {
        disposeAnimator(rightUpperArmAnim);
        rightUpperArmAnim = new SpriteAnimator(spriteSheetPath, rows, cols);
        this.rightUpperWidth = w; this.rightUpperHeight = h; this.rightUpperOffsetX = offX; this.rightUpperOffsetY = offY;
    }

    protected void initRightLower(String spriteSheetPath, int rows, int cols, float w, float h, float offX, float offY) {
        disposeAnimator(rightLowerArmAnim);
        rightLowerArmAnim = new SpriteAnimator(spriteSheetPath, rows, cols);
        this.rightLowerWidth = w; this.rightLowerHeight = h; this.rightLowerOffsetX = offX; this.rightLowerOffsetY = offY;
    }

    private void disposeAnimator(SpriteAnimator a) {
        if (a != null) {
            try { a.dispose(); } catch (Exception ignored) {}
        }
    }

    /** Add an attack to the boss' repertoire. Attacks are executed round-robin. */
    public void addAttack(Attack a) {
        if (a == null) return;
        attacks.add(a);
    }

    /**
     * Start the boss spawn sequence.
     * This method should be called when the boss first appears.
     * Override in subclasses to customize spawning behavior.
     */
    public void startSpawn() {
        isSpawning = true;
        hasSpawned = false;
        spawnTimer = 0f;
        onSpawnStart();
    }

    /**
     * Called when spawn sequence starts.
     * Override in subclasses for custom spawn initialization.
     */
    protected void onSpawnStart() {
        // Default: fade in, scale up, or play spawn animation
        // Subclasses can override for custom spawn effects
    }

    /**
     * Update spawn sequence progress.
     * Override in subclasses for custom spawn animations.
     * @param delta time since last frame
     * @param progress spawn progress from 0.0 to 1.0
     */
    protected void updateSpawn(float delta, float progress) {
        // Default: simple fade in
        // Subclasses can override for custom spawn behavior
    }

    /**
     * Called when spawn sequence completes.
     * Override in subclasses for post-spawn behavior.
     */
    protected void onSpawnComplete() {
        // Default: nothing special
        // Subclasses can override for spawn completion effects
    }

    /**
     * Check if boss is currently spawning.
     */
    public boolean isSpawning() {
        return isSpawning;
    }

    /**
     * Check if boss has completed spawning.
     */
    public boolean hasSpawned() {
        return hasSpawned;
    }

    /**
     * Set spawn duration in seconds.
     */
    public void setSpawnDuration(float duration) {
        this.spawnDuration = duration;
    }

    @Override
    public void update(float delta) {
        super.update(delta);

        // Handle spawning sequence
        if (isSpawning) {
            spawnTimer += delta;
            float progress = Math.min(spawnTimer / spawnDuration, 1.0f);
            updateSpawn(delta, progress);
            
            if (progress >= 1.0f) {
                isSpawning = false;
                hasSpawned = true;
                onSpawnComplete();
            }
            
            // Skip normal updates during spawn
            return;
        }

        // Use raw delta time to reduce perceived stutter when frames spike.
        // Note: raw delta may be larger during big frame drops; use carefully.
        @SuppressWarnings("deprecation")
        float frameDelta = Gdx.graphics.getRawDeltaTime();

        // Update bob timer and calculate current hover height
        bobTimer += frameDelta;
        float currentHoverHeight = baseHoverHeight + (float)(Math.sin(bobTimer * bobFrequency) * bobAmplitude);
        
        // Movement update
        if (targetX != 0 || targetY != 0) {
            // Calculate desired movement
            float dx = (targetX-112) - x;
            float dy = (targetY + currentHoverHeight) - y; // Maintain hover height above target with bobbing

            // Calculate acceleration towards target
            float ax = dx * followSpeed;
            float ay = dy * followSpeed;

            // Update velocity with acceleration
            velocityX += ax * frameDelta;
            velocityY += ay * frameDelta;
            
            // Apply speed limits
            float speed = (float)Math.sqrt(velocityX * velocityX + velocityY * velocityY);
            if (speed > maxSpeed) {
                velocityX = (velocityX / speed) * maxSpeed;
                velocityY = (velocityY / speed) * maxSpeed;
            }
            
            // Apply movement
            x += velocityX * frameDelta;
            y += velocityY * frameDelta;
            
            // Apply damping
            velocityX *= 0.95f;
            velocityY *= 0.95f;
        }

        // Update arm positions with physics (lerp)
    updateArmPhysics(frameDelta);

        // Update SFX/effects - reuse temporary list to avoid per-frame allocations
        effectsToRemove.clear();
        for (SFX s : activeEffects) {
            s.update(frameDelta);
            if (s.isFinished()) {
                effectsToRemove.add(s);
            }
        }
        // Remove finished effects after iteration
        if (!effectsToRemove.isEmpty()) activeEffects.removeAll(effectsToRemove);

        // Drain removalQueue (thread-safe) to handle safeRemoveEffect requests
        SFX queued;
        while ((queued = removalQueue.poll()) != null) {
            activeEffects.remove(queued);
        }

        // Update SpriteAnimator parts
    if (bodyAnim != null) bodyAnim.update(frameDelta);
    if (leftUpperArmAnim != null) leftUpperArmAnim.update(frameDelta);
    if (leftLowerArmAnim != null) leftLowerArmAnim.update(frameDelta);
    if (rightUpperArmAnim != null) rightUpperArmAnim.update(frameDelta);
    if (rightLowerArmAnim != null) rightLowerArmAnim.update(frameDelta);

        // Update pending actions - reuse duePending list to avoid allocations
        duePending.clear();
        for (PendingAction pa : pending) {
            pa.delay -= frameDelta;
            if (pa.delay <= 0f) duePending.add(pa);
        }
        if (!duePending.isEmpty()) {
            // Remove due actions from the pending list before executing them so
            // they can safely schedule new pending actions without modifying
            // the collection we're iterating.
            pending.removeAll(duePending);
            for (PendingAction pa : duePending) {
                try { pa.action.run(); } catch (Exception ignored) {}
            }
        }

        // Attack scheduling
    if (attackCooldownTimer > 0f) attackCooldownTimer -= frameDelta;
        // Optionally transition phase automatically based on health percentage (if enabled)
        if (autoPhaseByHealth && health != null) {
            float cur = health.getCurrentHealth();
            float max = health.getMaxHealth();
            int newPhase = phase;
            if (max > 0f) {
                float pct = cur / max;
                if (pct <= phaseLowThreshold) newPhase = 3;
                else if (pct <= phaseHighThreshold) newPhase = 2;
                else newPhase = 1;
            }
            if (newPhase != phase) {
                setPhase(newPhase);
            }
        }

            if (!useCustomAttackScheduler && attacks.size() > 0 && attackCooldownTimer <= 0f) {
            // Prefer weighted selection if configured for this phase
            float[] weights = (phase >= 1 && phase < phaseWeights.size()) ? phaseWeights.get(phase) : null;
            if (weights != null && weights.length > 0) {
                // Build a working copy of weights for this attempt, clamped to attacks.size()
                int n = attacks.size();
                float[] work = new float[n];
                float sum = 0f;
                for (int i = 0; i < n; i++) {
                    float w = i < weights.length ? Math.max(0f, weights[i]) : 0f;
                    work[i] = w;
                    sum += w;
                }
                if (sum <= 0f) {
                    // fallback to identity round-robin
                    weights = null;
                } else {
                    boolean started = false;
                    // Try up to n attempts picking by current weights and zeroing invalid choices
                    for (int attempt = 0; attempt < n; attempt++) {
                        // recompute sum
                        float localSum = 0f;
                        for (float v : work) localSum += v;
                        if (localSum <= 0f) break; // nothing left
                        double r = com.badlogic.gdx.math.MathUtils.random() * localSum;
                        int pick = -1;
                        float acc = 0f;
                        for (int i = 0; i < n; i++) {
                            acc += work[i];
                            if (r <= acc) { pick = i; break; }
                        }
                        if (pick < 0) pick = n - 1;
                        Attack a = attacks.get(pick);
                        if (a.tryStart(this)) {
                            attackCooldownTimer = a.getCooldownForPhase(this);
                            started = true;
                            break;
                        } else {
                            // zero out this weight and retry
                            work[pick] = 0f;
                        }
                    }
                    // if none started, fall through to order/identity fallback
                    if (started) {
                        // done for this frame
                        // (no explicit nextIndex update required for weighted mode)
                        return;
                    }
                }
            }

            // Resolve attack order for current phase (fall back to identity order)
            int[] order = (phase >= 1 && phase < phaseOrders.size()) ? phaseOrders.get(phase) : null;
            if (order == null || order.length == 0) {
                // identity order
                int attempts = attacks.size();
                for (int i = 0; i < attempts; i++) {
                    int idx = (nextAttackIndex + i) % attacks.size();
                    Attack a = attacks.get(idx);
                    if (a.tryStart(this)) {
                        attackCooldownTimer = a.getCooldownForPhase(this);
                        nextAttackIndex = (idx + 1) % attacks.size();
                        break;
                    }
                    // otherwise continue trying next attack
                }
            } else {
                // Use explicit phase order
                int orderLen = Math.max(1, order.length);
                int phaseIdx = nextIndexForPhase[Math.max(1, Math.min(3, phase))] % orderLen;
                // Find a startable attack in the order (try each once)
                boolean started = false;
                for (int i = 0; i < orderLen; i++) {
                    int pick = order[(phaseIdx + i) % orderLen];
                    if (attacks.size() == 0) break;
                    int safePick = ((pick % attacks.size()) + attacks.size()) % attacks.size();
                    Attack a = attacks.get(safePick);
                    if (a.tryStart(this)) {
                        attackCooldownTimer = a.getCooldownForPhase(this);
                        // advance phase-specific index
                        nextIndexForPhase[phase] = (phaseIdx + 1) % orderLen;
                        started = true;
                        break;
                    }
                }
                if (!started) {
                    // If none could start, advance the pointer so we don't stall forever
                    nextIndexForPhase[phase] = (phaseIdx + 1) % orderLen;
                }
            }
        }
    }

    @Override
    public void render(SpriteBatch batch) {
        // Draw boss visuals here (omitted). Render active effects on top.
        // Render body and parts in a reasonable layering order.
        // Order: body, left lower, left upper, right lower, right upper, effects
        if (bodyAnim != null) bodyAnim.render(batch, x + bodyOffsetX, y + bodyOffsetY, bodyWidth, bodyHeight);
        if (leftLowerArmAnim != null) leftLowerArmAnim.render(batch, x + leftLowerOffsetX, y + leftLowerOffsetY, leftLowerWidth, leftLowerHeight);
        if (leftUpperArmAnim != null) leftUpperArmAnim.render(batch, x + leftUpperOffsetX, y + leftUpperOffsetY, leftUpperWidth, leftUpperHeight);
        if (rightLowerArmAnim != null) rightLowerArmAnim.render(batch, x + rightLowerOffsetX, y + rightLowerOffsetY, rightLowerWidth, rightLowerHeight);
        if (rightUpperArmAnim != null) rightUpperArmAnim.render(batch, x + rightUpperOffsetX, y + rightUpperOffsetY, rightUpperWidth, rightUpperHeight);

        for (SFX s : activeEffects) s.render(batch);
    }

    // --- Helpers for attacks ---
    public void spawnEffect(SFX sfx, float px, float py) {
        if (sfx == null) return;
        long t0 = SFX_PROFILING ? System.nanoTime() : 0L;
        sfx.setPosition(px, py);
        activeEffects.add(sfx);
        if (SFX_PROFILING) {
            long t1 = System.nanoTime();
            profLog("SFX_PROF", "spawnEffect: added SFX=" + (sfx.getAnim()!=null? sfx.getAnim().toString():"<anim>") + " pos=("+px+","+py+") addMillis=" + ((t1-t0)/1_000_000.0));
        }
    }

    /**
     * Create an SFX in a background thread (callable) and spawn it on the GL thread
     * at (px,py). The callable should avoid creating GL textures directly; prefer
     * using preloaded assets or creating light-weight SFX state. The SFX instance
     * returned by the callable will be added on the main thread.
     */
    public void spawnEffectAsync(final Callable<SFX> creator, final float px, final float py) {
        spawnEffectAsync(creator, px, py, null);
    }

    /**
     * Like spawnEffectAsync but provides a callback invoked on the GL thread with
     * the spawned SFX instance (useful to keep references to indicators).
     */
    public void spawnEffectAsync(final Callable<SFX> creator, final float px, final float py, final Consumer<SFX> onSpawned) {
        if (creator == null) return;
        bgExecutor.submit(new Runnable() {
            @Override
            public void run() {
                long bgStart = SFX_PROFILING ? System.nanoTime() : 0L;
                try {
                    // NOTE: creator.call() may not be allowed to create GL resources
                    // (textures) because it runs off the GL thread. If a caller needs
                    // to perform GL allocations then use spawnEffectAsyncGL below.
                    final SFX s = creator.call();
                    long bgEnd = SFX_PROFILING ? System.nanoTime() : 0L;
                    // Post to GL thread to attach the effect (safe to call GL ops there)
                    Gdx.app.postRunnable(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                long glStart = SFX_PROFILING ? System.nanoTime() : 0L;
                                spawnEffect(s, px, py);
                                if (onSpawned != null) onSpawned.accept(s);
                                long glEnd = SFX_PROFILING ? System.nanoTime() : 0L;
                                if (SFX_PROFILING) {
                                    profLog("SFX_PROF", "spawnEffectAsync: bgMillis=" + ((bgEnd-bgStart)/1_000_000.0)
                                            + " glMillis=" + ((glEnd-glStart)/1_000_000.0)
                                            + " totalMillis=" + ((glEnd-bgStart)/1_000_000.0));
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    });
                } catch (Exception e) {
                    // log and ignore errors from background creation
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * Async helper that runs optional background work, then creates the SFX on
     * the GL thread via the provided Supplier. Use this when SFX construction
     * performs GL calls (texture allocation) which must be done on the GL thread.
     * The bgTask may be null if no background work is needed.
     */
    public void spawnEffectAsyncGL(final Runnable bgTask, final Supplier<SFX> glCreator, final float px, final float py, final Consumer<SFX> onSpawned) {
        if (glCreator == null) return;
        bgExecutor.submit(new Runnable() {
            @Override
            public void run() {
                long bgStart = SFX_PROFILING ? System.nanoTime() : 0L;
                try {
                    if (bgTask != null) {
                        try { bgTask.run(); } catch (Exception ignored) {}
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                long bgEnd = SFX_PROFILING ? System.nanoTime() : 0L;
                // Now post to GL thread to construct any GL resources safely
                Gdx.app.postRunnable(new Runnable() {
                    @Override
                    public void run() {
                        long glStart = SFX_PROFILING ? System.nanoTime() : 0L;
                        try {
                            SFX s = glCreator.get();
                            long glCreated = SFX_PROFILING ? System.nanoTime() : 0L;
                            spawnEffect(s, px, py);
                            if (onSpawned != null) onSpawned.accept(s);
                            long glEnd = SFX_PROFILING ? System.nanoTime() : 0L;
                            if (SFX_PROFILING) {
                                profLog("SFX_PROF", "spawnEffectAsyncGL: bgMillis=" + ((bgEnd-bgStart)/1_000_000.0)
                                        + " glCreateMillis=" + ((glCreated-glStart)/1_000_000.0)
                                        + " spawnMillis=" + ((glEnd-glCreated)/1_000_000.0)
                                        + " totalMillis=" + ((glEnd-bgStart)/1_000_000.0));
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                });
            }
        });
    }

    // --- Helpers to control part animations ---
    public void playBody(String animName, boolean flipX) { if (bodyAnim != null) bodyAnim.play(animName, flipX); }
    public void playLeftUpper(String animName, boolean flipX) { if (leftUpperArmAnim != null) leftUpperArmAnim.play(animName, flipX); }
    public void playLeftLower(String animName, boolean flipX) { if (leftLowerArmAnim != null) leftLowerArmAnim.play(animName, flipX); }
    public void playRightUpper(String animName, boolean flipX) { if (rightUpperArmAnim != null) rightUpperArmAnim.play(animName, flipX); }
    public void playRightLower(String animName, boolean flipX) { if (rightLowerArmAnim != null) rightLowerArmAnim.play(animName, flipX); }

    public void disposeParts() {
        disposeAnimator(bodyAnim);
        disposeAnimator(leftUpperArmAnim);
        disposeAnimator(leftLowerArmAnim);
        disposeAnimator(rightUpperArmAnim);
        disposeAnimator(rightLowerArmAnim);
    }

    public void scheduleAction(float delaySeconds, Runnable r) {
        if (r == null) return;
        pending.add(new PendingAction(Math.max(0f, delaySeconds), r));
    }

    // --- Phase configuration API ---
    /** Set the current phase (1..3). Resets the per-phase round-robin pointer. */
    public void setPhase(int newPhase) {
        if (newPhase < 1) newPhase = 1;
        if (newPhase > 3) newPhase = 3;
        if (this.phase == newPhase) return;
        this.phase = newPhase;
        // reset per-phase pointer for smoother transitions
        this.nextIndexForPhase[newPhase] = 0;
        // also reset global nextAttackIndex to avoid surprises
        this.nextAttackIndex = 0;
    }

    /** Get the current phase (1..3). */
    public int getPhase() { return this.phase; }

    /** Get the multiplier applied to base cooldowns for the given phase. */
    public float getPhaseCooldownMultiplier(int phaseNumber) {
        if (phaseNumber < 1 || phaseNumber >= phaseCooldownMultipliers.length) return 1.0f;
        return phaseCooldownMultipliers[phaseNumber];
    }

    /** Configure explicit attack order for a phase. The array contains indices into the boss' attack list (0-based). */
    public void configurePhaseOrder(int phaseNumber, int[] attackIndices) {
        if (phaseNumber < 1 || phaseNumber > 3) return;
        // defensive copy
        int[] copy = null;
        if (attackIndices != null) {
            copy = new int[attackIndices.length];
            System.arraycopy(attackIndices, 0, copy, 0, attackIndices.length);
        }
        // ensure list size
        while (phaseOrders.size() <= phaseNumber) phaseOrders.add(null);
        phaseOrders.set(phaseNumber, copy);
        // reset pointer
        nextIndexForPhase[phaseNumber] = 0;
    }

    /** Configure per-phase weights. The provided array contains a weight per attack index (0-based). */
    public void configurePhaseWeights(int phaseNumber, float[] weights) {
        if (phaseNumber < 1 || phaseNumber > 3) return;
        float[] copy = null;
        if (weights != null) {
            copy = new float[weights.length];
            System.arraycopy(weights, 0, copy, 0, weights.length);
        }
        while (phaseWeights.size() <= phaseNumber) phaseWeights.add(null);
        phaseWeights.set(phaseNumber, copy);
    }

    /** Allow subclasses to opt out of the default attack scheduler. */
    protected void setUseCustomAttackScheduler(boolean useCustom) {
        this.useCustomAttackScheduler = useCustom;
    }

    /** Called by subclasses to control the internal cooldown timer when using a custom scheduler. */
    protected void setAttackCooldownTimer(float seconds) {
        this.attackCooldownTimer = Math.max(0f, seconds);
    }

    /** Return remaining attack cooldown seconds (for subclasses). */
    protected float getAttackCooldownRemaining() {
        return this.attackCooldownTimer;
    }

    /** Get a copy of the weights array configured for a phase, or null if none configured. */
    public float[] getPhaseWeights(int phaseNumber) {
        if (phaseNumber < 1 || phaseNumber >= phaseWeights.size()) return null;
        float[] w = phaseWeights.get(phaseNumber);
        if (w == null) return null;
        float[] copy = new float[w.length];
        System.arraycopy(w, 0, copy, 0, w.length);
        return copy;
    }

    /** Return the list of configured attacks (read-only view). */
    public List<Attack> getAttacks() {
        return attacks;
    }

    /** Configure cooldown multiplier for the given phase (1..3). Multiplier multiplies each attack's base cooldown. */
    public void configurePhaseCooldownMultiplier(int phaseNumber, float multiplier) {
        if (phaseNumber < 1 || phaseNumber > 3) return;
        phaseCooldownMultipliers[phaseNumber] = Math.max(0.01f, multiplier);
    }

    /** Enable or disable automatic phase changes driven by health percentage. Default: enabled. */
    public void setAutoPhaseByHealth(boolean enabled) { this.autoPhaseByHealth = enabled; }

    /** Configure health thresholds used when auto-phase-by-health is enabled. highThreshold should be > lowThreshold and both in (0,1). */
    public void configurePhaseHealthThresholds(float highThreshold, float lowThreshold) {
        if (highThreshold <= lowThreshold) return;
        if (highThreshold <= 0f || lowThreshold <= 0f) return;
        this.phaseHighThreshold = Math.min(1f, Math.max(0f, highThreshold));
        this.phaseLowThreshold = Math.min(1f, Math.max(0f, lowThreshold));
    }

    /** Schedule a scripted phase transition after a delay (seconds). Useful for stage timers or cutscenes. */
    public void schedulePhaseTransition(float delaySeconds, final int newPhase) {
        scheduleAction(delaySeconds, new Runnable() {
            @Override
            public void run() { setPhase(newPhase); }
        });
    }

    // --- Query active effects for collision/damage if desired ---
    public List<SFX> getActiveEffects() { return activeEffects; }
    
    // Safely remove an effect
    public void safeRemoveEffect(SFX effect) {
        if (effect != null) {
            // Enqueue for removal; drained on the GL thread in update()
            removalQueue.add(effect);
        }
    }
    
    // Set the target for the boss to follow (typically the player)
    public void setTarget(float x, float y) {
        this.targetX = x;
        this.targetY = y;
    }

    /**
     * Linear interpolation between two values
     */
    private float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /**
     * Update arm positions with physics simulation for natural movement.
     * Uses linear interpolation (lerp) to create smooth, delayed following.
     */
    private void updateArmPhysics(float delta) {
        // Helper function for lerp
        // Calculate target positions for arms (relative to body)
        float lerpAmount = armLerpSpeed * delta;
        
        // Left Upper Arm
        float targetLeftUpperX = x + bodyOffsetX - bodyWidth/2;
        float targetLeftUpperY = y + bodyOffsetY;
        leftUpperPhysicsPos[0] = lerp(leftUpperPhysicsPos[0], targetLeftUpperX, lerpAmount);
        leftUpperPhysicsPos[1] = lerp(leftUpperPhysicsPos[1], targetLeftUpperY, lerpAmount);
        leftUpperOffsetX = leftUpperPhysicsPos[0] - x;
        leftUpperOffsetY = leftUpperPhysicsPos[1] - y;
        
        // Left Lower Arm (follows upper arm with more delay)
        float targetLeftLowerX = leftUpperPhysicsPos[0] - leftLowerWidth/4;
        float targetLeftLowerY = leftUpperPhysicsPos[1] - leftLowerHeight/4;
        leftLowerPhysicsPos[0] = lerp(leftLowerPhysicsPos[0], targetLeftLowerX, lerpAmount * 0.8f);
        leftLowerPhysicsPos[1] = lerp(leftLowerPhysicsPos[1], targetLeftLowerY, lerpAmount * 0.8f);
        leftLowerOffsetX = leftLowerPhysicsPos[0] - x;
        leftLowerOffsetY = leftLowerPhysicsPos[1] - y;
        
        // Right Upper Arm
        float targetRightUpperX = x + bodyOffsetX + bodyWidth/2;
        float targetRightUpperY = y + bodyOffsetY;
        rightUpperPhysicsPos[0] = lerp(rightUpperPhysicsPos[0], targetRightUpperX, lerpAmount);
        rightUpperPhysicsPos[1] = lerp(rightUpperPhysicsPos[1], targetRightUpperY, lerpAmount);
        rightUpperOffsetX = rightUpperPhysicsPos[0] - x;
        rightUpperOffsetY = rightUpperPhysicsPos[1] - y;
        
        // Right Lower Arm (follows upper arm with more delay)
        float targetRightLowerX = rightUpperPhysicsPos[0] + rightLowerWidth/4;
        float targetRightLowerY = rightUpperPhysicsPos[1] - rightLowerHeight/4;
        rightLowerPhysicsPos[0] = lerp(rightLowerPhysicsPos[0], targetRightLowerX, lerpAmount * 0.8f);
        rightLowerPhysicsPos[1] = lerp(rightLowerPhysicsPos[1], targetRightLowerY, lerpAmount * 0.8f);
        rightLowerOffsetX = rightLowerPhysicsPos[0] - x;
        rightLowerOffsetY = rightLowerPhysicsPos[1] - y;
    }

    // --- Inner types ---
    private static class PendingAction {
        float delay;
        final Runnable action;
        PendingAction(float delay, Runnable action) { this.delay = delay; this.action = action; }
    }

    /**
     * Base Attack class. Implementations should define start behavior.
     */
    public static abstract class Attack {
        private final float cooldown; // base cooldown
        // optional per-phase cooldown overrides: index by phase number (1..3). If null use multiplier-based cooldown.
        private float[] perPhaseCooldowns = null;

        protected Attack(float cooldownSeconds) { this.cooldown = Math.max(0f, cooldownSeconds); }

        /** Base cooldown (unchanged by phase). */
        public float getCooldown() { return cooldown; }

        /**
         * Get the cooldown to use for the given boss instance's current phase.
         * Default: if a per-phase override is configured, use it; otherwise use
         * baseCooldown * boss.getPhaseCooldownMultiplier(phase).
         */
        public float getCooldownForPhase(Boss boss) {
            if (boss == null) return cooldown;
            int p = boss.getPhase();
            if (perPhaseCooldowns != null && p >= 1 && p < perPhaseCooldowns.length) {
                float v = perPhaseCooldowns[p];
                if (v > 0f) return v;
            }
            float mult = boss.getPhaseCooldownMultiplier(p);
            return Math.max(0f, cooldown * mult);
        }

        /** Configure explicit cooldowns per phase. The array should be indexed by phase number (1..3). Index 0 may be unused. */
        public void configurePerPhaseCooldowns(float[] perPhase) {
            if (perPhase == null) { this.perPhaseCooldowns = null; return; }
            // make a defensive copy and ensure length >= 4 for indices 0..3
            this.perPhaseCooldowns = new float[Math.max(4, perPhase.length)];
            System.arraycopy(perPhase, 0, this.perPhaseCooldowns, 0, Math.min(perPhase.length, this.perPhaseCooldowns.length));
        }

        public boolean tryStart(Boss boss) {
            // For now attacks are started immediately when chosen. The boss's
            // scheduling logic uses the attack's cooldown to space attacks.
            start(boss);
            return true;
        }

        protected abstract void start(Boss boss);
    }

    /**
     * Simple attack that immediately spawns an effect at an offset from boss.
     */
    public static class ImmediateEffectAttack extends Attack {
        private final SFX effectPrototype;
        private final float offsetX, offsetY;

        public ImmediateEffectAttack(float cooldownSeconds, SFX effectPrototype, float offsetX, float offsetY) {
            super(cooldownSeconds);
            this.effectPrototype = effectPrototype;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
        }

        @Override
        protected void start(Boss boss) {
            // spawn a fresh effect instance (caller should provide a new SFX if it needs unique state)
            SFX s = effectPrototype;
            boss.spawnEffect(s, boss.getX() + offsetX, boss.getY() + offsetY);
        }
    }

    /**
     * Indicator -> impact attack: shows an indicator at target, waits, then spawns impact.
     */
    public static class IndicatorSlamAttack extends Attack {
        private final SFX indicatorPrototype;
        private final SFX impactPrototype;
        private final float delaySeconds;
        private final float targetX, targetY;

        public IndicatorSlamAttack(float cooldownSeconds, SFX indicatorPrototype, SFX impactPrototype, float targetX, float targetY, float delaySeconds) {
            super(cooldownSeconds);
            this.indicatorPrototype = indicatorPrototype;
            this.impactPrototype = impactPrototype;
            this.delaySeconds = delaySeconds;
            this.targetX = targetX;
            this.targetY = targetY;
        }

        @Override
        protected void start(final Boss boss) {
            // show indicator immediately
            final SFX ind = indicatorPrototype;
            boss.spawnEffect(ind, targetX, targetY);

            // schedule impact after delay
            boss.scheduleAction(delaySeconds, new Runnable() {
                @Override
                public void run() {
                    // remove indicator if still present (best-effort)
                    boss.safeRemoveEffect(ind);
                    // spawn impact
                    boss.spawnEffect(impactPrototype, targetX, targetY);
                }
            });
        }
    }
}
