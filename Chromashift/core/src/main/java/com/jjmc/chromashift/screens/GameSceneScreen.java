package com.jjmc.chromashift.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.jjmc.chromashift.environment.Solid;
import com.jjmc.chromashift.environment.Wall;
import com.jjmc.chromashift.environment.interactable.Button;
import com.jjmc.chromashift.environment.interactable.Interactable;
import com.jjmc.chromashift.environment.interactable.Box;
import com.jjmc.chromashift.environment.interactable.Target;
import com.jjmc.chromashift.environment.interactable.Orb;
import com.jjmc.chromashift.environment.Spawn;
import com.jjmc.chromashift.player.Player;
import com.jjmc.chromashift.player.PlayerConfig;
import com.jjmc.chromashift.screens.ui.MainMenuScreen;
import com.jjmc.chromashift.screens.TestMenuScreen;
import com.jjmc.chromashift.entity.boss.BossGuardian;
import com.jjmc.chromashift.entity.boss.FinalBoss;
import com.chromashift.helper.CameraController;
import com.chromashift.helper.SpriteAnimator;

/**
 * Test scene specifically designed to exercise Doors, Buttons and Levers.
 * - Left: Button (pressure) -> Door A
 * - Center: Lever (press F) -> Door B (toggle)
 * - Right: Independent door/obstacles for edge cases
 * Provides on-screen instructions and debug visuals.
*/
@SuppressWarnings("unused")
public class GameSceneScreen implements Screen {
    private OrthographicCamera camera;
    private CameraController camController;
    private SpriteBatch batch;
    private ShapeRenderer shape;
    private BitmapFont font;
    private SpriteAnimator backgroundAnimator;

    public Player player;
    private FinalBoss boss;
    private BossGuardian bossGuardian;
    private Initialize.Context ctx;
    private Stage uiStage;

    private Array<Wall> walls;
    private Array<Interactable> interactables;
    // Track the most recently spawned portal for camera panning during spawn
    private com.jjmc.chromashift.environment.interactable.Portal spawnedPortal;
    // Camera easing from portal back to player
    private boolean easingFromPortal = false;
    private float portalEaseRemaining = 0f;
    private float portalEaseDuration = 0.6f;
    private com.badlogic.gdx.math.Vector2 portalEaseStart = new com.badlogic.gdx.math.Vector2();
    private boolean lastPortalWasSpawning = false;
    private Array<Solid> solids;
    private Array<com.jjmc.chromashift.environment.collectible.Collectible> collectibles;
    private Array<com.jjmc.chromashift.environment.interactable.Shop> shops;

    // Player spawn for respawn key
    private float playerSpawnX;
    private float playerSpawnY;
    private Spawn spawnMarker;

    // Base area walls so render can reference their bounds
    private Wall baseLeft;
    private Wall baseCenter;
    private Wall baseRight;

    // Ground disabled: set far below so it never collides
    private float groundY = -100000f;

    // Current level path for save/load and visited levels tracking
    private String currentLevelPath = "levels/level1.json";
    public Array<String> visitedLevels = new Array<>();
    private com.jjmc.chromashift.screens.levels.LevelLoader.LoadMode loadMode = com.jjmc.chromashift.screens.levels.LevelLoader.LoadMode.ORIGINAL;
    // Camera zoom settings
    private float desiredZoom = 2f;// <1 = zoom in a bit
    private float zoomLerpSpeed = 5f; // how fast camera zooms to target

    // Tentacle System
    private Array<com.jjmc.chromashift.environment.enemy.Tentacle> tentacles;
    private Array<com.jjmc.chromashift.environment.enemy.TentacleCapture> tentacleCaptures;
    // Cache enemies list so we can perform a post-tentacle-update collision pass.
    private Array<com.jjmc.chromashift.environment.enemy.Enemy> enemies;

    // Level loading system
    private com.jjmc.chromashift.screens.levels.LevelLoadingManager loadingManager;
    private com.jjmc.chromashift.screens.levels.LoadingOverlay loadingOverlay;
    private boolean gameplayEnabled = false;
    // Game Over overlay state
    private boolean gameOverActive = false;
    private float gameOverTimer = 0f;
    private float gameOverMinDisplay = 1.0f; // seconds before accepting input
    private Runnable gameOverAction;
    // Pause menu state and dialogs
    private boolean paused = false;
    private com.badlogic.gdx.scenes.scene2d.ui.Dialog pauseDialog;
    private com.badlogic.gdx.scenes.scene2d.ui.Dialog settingsDialog;

    // HUD layout constants
    private final int HUD_BAR_WIDTH = 220;
    private final int HUD_BAR_HEIGHT = 16;
    private final int HUD_BAR_GAP = 18;
    private final int HUD_MARGIN_TOP = 36;
    private final int HUD_NAME_OFFSET_Y = 18;

    // Constructor with default level (NEW GAME - always load original)
    public GameSceneScreen() {
        this("levels/level1.json", com.jjmc.chromashift.screens.levels.LevelLoader.LoadMode.ORIGINAL);
        try {
            font = new BitmapFont(Gdx.files.internal("ui/default.fnt"));
        } catch (Exception e) {
            font = new BitmapFont(); // Fallback
        }
    }

    // Constructor with custom level path (NEW GAME - always load original)
    public GameSceneScreen(String levelPath) {
        this(levelPath, com.jjmc.chromashift.screens.levels.LevelLoader.LoadMode.ORIGINAL);
    }

    // Constructor with custom level path andx load mode (for CONTINUE)
    public GameSceneScreen(String levelPath, com.jjmc.chromashift.screens.levels.LevelLoader.LoadMode mode) {
        this.currentLevelPath = levelPath;
        this.loadMode = mode;
    }

    @Override
    public void show() {
        // Detach any previous UI Stage (e.g., menu) so its actors stop receiving input
        try {
            Gdx.input.setInputProcessor(null);
        } catch (Throwable ignored) {
        }
        // Reset editor flags when entering game mode
        com.jjmc.chromashift.environment.interactable.Box.EDITOR_DELETE_MODE = false;
        com.jjmc.chromashift.environment.interactable.Orb.EDITOR_DELETE_MODE = false;
        // Enable gameplay-only visibility culling
        try {
            com.chromashift.helper.VisibilityCuller.setEnabled(true);
        } catch (Throwable ignored) {
        }

        // Use Initialize helper to create common systems
        ctx = Initialize.createCommon(500, 180, null);
        camera = ctx.camera;
        camController = ctx.camController;
        batch = ctx.batch;
        shape = ctx.shape;
        font = ctx.font;

        // Initialize loading system
        loadingManager = new com.jjmc.chromashift.screens.levels.LevelLoadingManager();
        loadingOverlay = new com.jjmc.chromashift.screens.levels.LoadingOverlay(
                loadingManager, batch, shape, font, new com.badlogic.gdx.utils.viewport.ScreenViewport());
        gameplayEnabled = false;

        // Initialize background animator for bossroom levels
        backgroundAnimator = null;

        // Load everything via the unified LevelLoader
        // Track current level and mark visited for save/load (use currentLevelPath from
        // constructor)
        this.visitedLevels.clear();
        this.visitedLevels.add(currentLevelPath);
        // Prefer workspace copy when available so editor changes (door speeds, links)
        // are reflected immediately during playtesting.
        com.jjmc.chromashift.screens.levels.LevelLoader.Result loaded;
        try {
            loaded = com.jjmc.chromashift.screens.levels.LevelLoader.loadFromWorkspace(currentLevelPath, loadMode);
        } catch (Exception ex) {
            loaded = com.jjmc.chromashift.screens.levels.LevelLoader.load(currentLevelPath, loadMode);
        }

        // Log load mode for debugging
        Gdx.app.log("TestSceneScreen", "Loaded level " + currentLevelPath + " with mode: " + loadMode);

        // Adopt loaded collections so updates/render iterate the same instances
        this.walls = loaded.walls;
        this.solids = loaded.solids;
        this.interactables = loaded.interactables;
        this.collectibles = loaded.collectibles;

        // Initialize UI stage for shop dialogs
        uiStage = new Stage(new ScreenViewport());

        // Use InputMultiplexer to allow both UI and game input
        // Stage gets priority for UI clicks, but keyboard input still works for player
        InputMultiplexer multiplexer = new InputMultiplexer();
        multiplexer.addProcessor(uiStage);
        Gdx.input.setInputProcessor(multiplexer);

        // Boss selection based on level
        if (loaded.boss != null) {
            // Use loaded boss (could be FinalBoss or BossGuardian)
            if (loaded.boss instanceof FinalBoss) {
                this.boss = (FinalBoss) loaded.boss;
            } else if (loaded.boss instanceof BossGuardian) {
                this.bossGuardian = (BossGuardian) loaded.boss;
            }
        } else if (currentLevelPath.contains("bossroom1")) {
            // FinalBoss for bossroom1
            this.boss = new FinalBoss();
            Wall base = (walls.size > 0) ? walls.first() : null;
            float bx = (base != null) ? (base.bounds.x + base.bounds.width / 2f) : 0f;
            float by = (base != null) ? (base.bounds.y + base.bounds.height + 200f) : 200f;
            boss.setPosition(bx, by);
            boss.setEnvironment(solids, walls);
        } else if (currentLevelPath.contains("bossroom")) {
            // BossGuardian for other bossroom levels
            this.bossGuardian = new BossGuardian();
            Wall base = (walls.size > 0) ? walls.first() : null;
            float bx = (base != null) ? (base.bounds.x + base.bounds.width / 2f) : 0f;
            float by = (base != null) ? (base.bounds.y + base.bounds.height + 400f) : 400f;
            bossGuardian.setPosition(bx, by);
            bossGuardian.setEnvironment(solids, walls);
            // Remove boss and its enemy adapters once all guardians are dead
            bossGuardian.setOnDefeated(() -> {
                Gdx.app.postRunnable(() -> {
                    if (enemies != null) {
                        for (int i = enemies.size - 1; i >= 0; i--) {
                            if (enemies.get(i) instanceof com.jjmc.chromashift.environment.enemy.BossGuardianEnemyAdapter) {
                                enemies.removeIndex(i);
                            }
                        }
                    }
                    // Spawn a new portal at boss center with configurable Y
                    try {
                        com.jjmc.chromashift.environment.interactable.Portal portal =
                                new com.jjmc.chromashift.environment.interactable.Portal(
                                        bossGuardian.getBossCenter().x,
                                        bossGuardian.defeatDoorY+25);
                        // Start in spawning animation state and pan camera to it
                        portal.setState(com.jjmc.chromashift.environment.interactable.Portal.PortalState.SPAWNING);
                        // Hook level progression
                        portal.setOnPlayerEnter(() -> advanceToNextLevel());
                        interactables.add(portal);
                        spawnedPortal = portal;
                        lastPortalWasSpawning = true;
                    } catch (Exception ignored) {}
                    bossGuardian = null;
                });
            });

            // Setup spawn sequence completion callback
            bossGuardian.setOnSpawnSequenceComplete(() -> {
                gameplayEnabled = true;
                Gdx.app.log("TestSceneScreen", "BossGuardian spawn sequence complete - fight begins!");
                // Save a checkpoint of player state (items/health) for boss retry
                try {
                    com.jjmc.chromashift.player.PlayerIO.PlayerState state = com.jjmc.chromashift.player.PlayerIO
                            .capture(player, currentLevelPath, visitedLevels);
                    com.jjmc.chromashift.database.PlayerDAO.savePlayerState(1, state);
                    Gdx.app.log("TestSceneScreen", "✓ Boss checkpoint saved for BossGuardian");
                } catch (Exception e) {
                    Gdx.app.log("TestSceneScreen", "Failed to save boss checkpoint: " + e.getMessage());
                }
            });

            // Start spawn sequence
            bossGuardian.startSpawn();
            gameplayEnabled = false; // Disable gameplay until spawn completes
        } else {
            this.boss = null;
            this.bossGuardian = null;
        }

        // Setup background animator for bossroom levels
        if (currentLevelPath.contains("bossroom1")) {
            try {
                // Animated background for FinalBoss room: 38 frames, 480x300 per frame
                backgroundAnimator = new SpriteAnimator("entity/bg_final.png", 1, 38);
                backgroundAnimator.addAnimation("bg", 0, 0, 38, 0.033f, true); // ~30fps, looping
                backgroundAnimator.play("bg", false);
                Gdx.app.log("TestSceneScreen", "Loaded background animator for bossroom1 (bg_final.png)");
            } catch (Exception e) {
                Gdx.app.error("TestSceneScreen", "Failed to load bossroom1 background (bg_final): " + e.getMessage());
                e.printStackTrace();
                backgroundAnimator = null;
            }
        } else if (currentLevelPath.contains("bossroom")) {
            // Static background for Guardian bossroom
            try {
                backgroundAnimator = new SpriteAnimator("entity/guardian_bg.jpg", 1, 1);
                backgroundAnimator.addAnimation("bg", 0, 0, 1, 1f, true);
                backgroundAnimator.play("bg", false);
                Gdx.app.log("TestSceneScreen", "Loaded static background for bossroom (guardian)");
            } catch (Exception e) {
                Gdx.app.error("TestSceneScreen", "Failed to load guardian bossroom background: " + e.getMessage());
                e.printStackTrace();
                backgroundAnimator = null;
            }
        }

        // Player at spawn
        PlayerConfig cfg = new PlayerConfig();
        player = ctx.createPlayer(loaded.spawnX, loaded.spawnY, cfg);
        // Apply preferred color from DB
        try {
            String preferredName = com.jjmc.chromashift.database.PlayerDAO.loadPreferredColor(1);
            if (preferredName != null && !preferredName.isEmpty()) {
                com.jjmc.chromashift.player.PlayerType pt = com.jjmc.chromashift.player.PlayerType
                        .fromName(preferredName);
                player.setType(pt);
                Gdx.app.log("TestSceneScreen", "Applied preferred player color: " + preferredName);
            }
        } catch (Exception e) {
            Gdx.app.error("TestSceneScreen", "Failed to apply preferred color: " + e.getMessage());
        }
        player.setRespawnPoint(player.getX(), player.getY());
        playerSpawnX = player.getX();
        playerSpawnY = player.getY();

        // On death, show Game Over; then respawn (normal) or reload (bossroom)
        try {
            player.setOnDeathHandler(source -> {
                boolean inBoss = currentLevelPath != null && (currentLevelPath.contains("bossroom1") || currentLevelPath.contains("bossroom"));
                if (inBoss) {
                    gameOverAction = () -> {
                        ((com.badlogic.gdx.Game) Gdx.app.getApplicationListener()).setScreen(
                                new GameSceneScreen(currentLevelPath,
                                        com.jjmc.chromashift.screens.levels.LevelLoader.LoadMode.SAVED_IF_EXISTS));
                    };
                } else {
                    gameOverAction = () -> {
                        try { player.respawn(); } catch (Throwable ignored2) {}
                    };
                }
                gameOverActive = true;
                gameOverTimer = 0f;
                return true; // suppress default respawn
            });
        } catch (Throwable ignored) {}

        // Load saves only for continue flows; for new-game flows, clear any prior save
        // first
        // NOTE: DO NOT load player state here for SAVED_IF_EXISTS - it causes spawn coordinate bugs
        // because the level save already positioned the player correctly via loaded.spawnX/Y
        if (loadMode == com.jjmc.chromashift.screens.levels.LevelLoader.LoadMode.ORIGINAL) {
            // New game - delete any old saves
            try {
                com.jjmc.chromashift.database.PlayerDAO.deletePlayerSave(1);
                Gdx.app.log("TestSceneScreen", "Previous save cleared for new game start");
            } catch (Exception ignored) {
                // If delete fails, continue with default new player
            }
        } 
        // else if (false) {
        //     // DISABLED: This was causing spawn bugs by overriding the level's spawn position
        //     try {
        //         com.jjmc.chromashift.database.PlayerDAO.loadPlayerStateFromDB(1, player);
        //         // Also restore visited levels
        //         com.badlogic.gdx.utils.Array<String> loadedVisited = com.jjmc.chromashift.database.PlayerDAO
        //                 .loadVisitedLevelsFromDB(1);
        //         if (loadedVisited != null && loadedVisited.size > 0) {
        //             this.visitedLevels.clear();
        //             this.visitedLevels.addAll(loadedVisited);
        //         }
        //         Gdx.app.log("TestSceneScreen", "✓ Player state restored from database");
        //         if (player != null) {
        //             player.setKeyCount(0);
        //         }

        //         // Keep respawn marker + saved spawn in sync with the loaded respawn point.
        //         // Otherwise the marker can show the JSON spawn while respawn uses the saved checkpoint.
        //         playerSpawnX = player.getRespawnX();
        //         playerSpawnY = player.getRespawnY();
        //     } catch (Exception ex) {
        //         Gdx.app.log("TestSceneScreen", "Could not restore player state (first run?): " + ex.getMessage());
        //     }
        // } 
        else {
            try {
                com.jjmc.chromashift.database.PlayerDAO.deletePlayerSave(1);
                Gdx.app.log("TestSceneScreen", "Previous save cleared for new game start");
                try {
                    com.jjmc.chromashift.screens.levels.GameLevelSave.clearAllOverridesForNewGame(1);
                    Gdx.app.log("TestSceneScreen", "Cleared per-level override saves for new game start");
                } catch (Exception ignored2) {
                }
            } catch (Exception ignored) {
                // If delete fails, continue with default new player
            }
        }

        // Set player reference in boss if it's BossGuardian
        if (bossGuardian != null) {
            bossGuardian.setPlayer(player);
        }

        // Initialize player skills (all except shuriken) with default keybinds
        player.equipSkillToSlot(new com.jjmc.chromashift.player.skill.DashSkill(player), 'Q');
        player.equipSkillToSlot(new com.jjmc.chromashift.player.skill.JumpSkill(player), 'E');
        player.equipSkillToSlot(new com.jjmc.chromashift.player.skill.SplitSkill(player), 'R');
        player.equipSkillToSlot(new com.jjmc.chromashift.player.skill.SlashSkill(player), 'C');

        // Visible spawn marker (static frame by default)
        spawnMarker = new Spawn(playerSpawnX, playerSpawnY);

        // Instantiate shops now that we have Player and Stage
        shops = new Array<>();
        for (com.jjmc.chromashift.screens.levels.LevelIO.LevelState.ShopData sd : loaded.shopDataList) {
            com.jjmc.chromashift.environment.interactable.Shop shop = new com.jjmc.chromashift.environment.interactable.Shop(
                    sd.x, sd.y, player, uiStage);
            shops.add(shop);
            interactables.add(shop); // Add to interactables for collision/interaction
        }
        // Assign player to any LockedDoor instances so they can check keys
        for (int i = 0; i < interactables.size; i++) {
            com.jjmc.chromashift.environment.interactable.Interactable it = interactables.get(i);
            if (it instanceof com.jjmc.chromashift.environment.interactable.LockedDoor ld) {
                ld.setPlayer(player);
            }
        }

        // Initialize Tentacle System from loaded data
        tentacles = loaded.tentacles;
        tentacleCaptures = new Array<>();
        for (com.jjmc.chromashift.environment.enemy.Tentacle t : tentacles) {
            // Only set up captures and drops for alive tentacles
            if (t.isAlive()) {
                tentacleCaptures.add(new com.jjmc.chromashift.environment.enemy.TentacleCapture(t, player));
            }
            // Set drop target for all tentacles (in case they respawn or for consistency)
            t.setDropTarget(collectibles);
        }

        // Set up enemy tracking for player melee attacks (store in field for later use)
                enemies = new Array<>();
                for (com.jjmc.chromashift.environment.enemy.Tentacle t : tentacles) {
                    // Only add alive tentacles to enemy list
                    if (t.isAlive()) {
                        enemies.add(t);
                    }
                }
                // Add BossGuardian's three guardians as enemies for attack/projectile collision
                if (bossGuardian != null) {
                    enemies.add(new com.jjmc.chromashift.environment.enemy.BossGuardianEnemyAdapter(bossGuardian, 1));
                    enemies.add(new com.jjmc.chromashift.environment.enemy.BossGuardianEnemyAdapter(bossGuardian, 2));
                    enemies.add(new com.jjmc.chromashift.environment.enemy.BossGuardianEnemyAdapter(bossGuardian, 3));
                }
        player.setEnemies(enemies);

        // Register BossGuardian crystal spawn callback once on init
        if (bossGuardian != null) {
            bossGuardian.setOnCrystalSpawn(crystal -> {
                // Track active crystals to gate boss attacks
                bossGuardian.notifyCrystalSpawned();
                // Register as interactable for update/render and as enemy for combat
                interactables.add(new com.jjmc.chromashift.environment.interactable.Interactable() {
                    @Override public void update(float delta) { crystal.update(delta); }
                    @Override public void render(com.badlogic.gdx.graphics.g2d.SpriteBatch batch) { crystal.render(batch); }
                    @Override public void interact() {}
                    @Override public void checkInteraction(com.badlogic.gdx.math.Rectangle playerHitbox) {}
                    @Override public boolean canInteract() { return false; }
                    @Override public com.badlogic.gdx.math.Rectangle getBounds() { return crystal.getBounds(); }
                    @Override public void debugDraw(com.badlogic.gdx.graphics.glutils.ShapeRenderer shape) { crystal.debugDraw(shape); }
                });
                enemies.add(crystal);
                player.setEnemies(enemies);

                // When crystal dies, spawn a DamageOrb and notify boss
                crystal.setOnDestroyed((cx, cy) -> {
                    try { bossGuardian.notifyCrystalDestroyed(); } catch (Throwable ignored) {}
                    try {
                        // Collect boss enemies (guardian adapters only)
                        com.badlogic.gdx.utils.Array<com.jjmc.chromashift.environment.enemy.Enemy> bossTargets = new com.badlogic.gdx.utils.Array<>();
                        for (int ei = 0; ei < enemies.size; ei++) {
                            com.jjmc.chromashift.environment.enemy.Enemy e = enemies.get(ei);
                            if (e instanceof com.jjmc.chromashift.environment.enemy.BossGuardianEnemyAdapter) {
                                bossTargets.add(e);
                            }
                        }
                        // Aim initial velocity toward player
                        float px = player.getX() + player.getHitboxWidth() / 2f;
                        float py = player.getY() + player.getHitboxHeight() / 2f;
                        float dx = px - cx;
                        float dy = py - cy;
                        float len = (float)Math.sqrt(dx*dx + dy*dy);
                        if (len < 0.001f) len = 1f;
                        float speed = 260f;
                        float ivx = dx / len * speed;
                        float ivy = dy / len * speed;

                        com.jjmc.chromashift.environment.interactable.DamageOrb dOrb = new com.jjmc.chromashift.environment.interactable.DamageOrb(
                                cx, cy, ivx, ivy, solids, bossTargets);
                        interactables.add(dOrb);
                    } catch (Throwable ignored) {}
                });
            });
        }

        // Wire portal callbacks for level progression
        for (int i = 0; i < interactables.size; i++) {
            if (interactables.get(i) instanceof com.jjmc.chromashift.environment.interactable.Portal portal) {
                portal.setOnPlayerEnter(() -> advanceToNextLevel());
            }
        }

        // Register all objects with loading manager
        registerLoadableObjects();

        // Start loading sequence
        loadingManager.setOnLoadingComplete(() -> {
            gameplayEnabled = true;
            Gdx.app.log("TestSceneScreen", "Level loading complete - gameplay enabled!");
            // For boss rooms, also save a checkpoint right after load
            try {
                if (currentLevelPath != null && (currentLevelPath.contains("bossroom1") || currentLevelPath.contains("bossroom"))) {
                    com.jjmc.chromashift.player.PlayerIO.PlayerState state = com.jjmc.chromashift.player.PlayerIO
                            .capture(player, currentLevelPath, visitedLevels);
                    com.jjmc.chromashift.database.PlayerDAO.savePlayerState(1, state);
                    Gdx.app.log("TestSceneScreen", "✓ Boss checkpoint saved after load");
                }
            } catch (Exception e) {
                Gdx.app.log("TestSceneScreen", "Failed to save boss checkpoint after load: " + e.getMessage());
            }
        });
        loadingManager.startLoading();
    }

    /**
     * Register all level objects with the loading manager.
     */
    private void registerLoadableObjects() {
        // Register environment (walls, solids, interactables, collectibles)
        loadingManager.registerLoadableObject(
                new com.jjmc.chromashift.screens.levels.LoadableEnvironment(
                        "Environment", walls, solids, interactables, collectibles));

        // Register enemies
        if (tentacles != null && tentacles.size > 0) {
            loadingManager.registerLoadableObject(
                    new com.jjmc.chromashift.screens.levels.LoadableEnemies(tentacles));
        }

        // Register boss
        if (boss != null) {
            loadingManager.registerLoadableObject(
                    new com.jjmc.chromashift.screens.levels.LoadableBoss(boss));
        }
        if (bossGuardian != null) {
            loadingManager.registerLoadableObject(
                    new com.jjmc.chromashift.screens.levels.LoadableBoss(bossGuardian));
        }

        // Register player (last so everything is ready when player activates)
        if (player != null) {
            loadingManager.registerLoadableObject(
                    new com.jjmc.chromashift.screens.levels.LoadablePlayer(player));
        }
    }

    @Override
    public void render(float delta) {
        // Game Over overlay handling
        if (gameOverActive) {
            gameOverTimer += delta;
            // Clear screen
            Gdx.gl.glClearColor(0.0f, 0.0f, 0.0f, 1f);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

            // Dim background region
            shape.setProjectionMatrix(camController.getCamera().combined);
            shape.begin(ShapeRenderer.ShapeType.Filled);
            shape.setColor(0f, 0f, 0f, 0.65f);
            float vw = camController.getCamera().viewportWidth * camController.getCamera().zoom;
            float vh = camController.getCamera().viewportHeight * camController.getCamera().zoom;
            float vx = camController.getCamera().position.x - vw / 2f;
            float vy = camController.getCamera().position.y - vh / 2f;
            shape.rect(vx, vy, vw, vh);
            shape.end();

            // Title + hint
            batch.setProjectionMatrix(camController.getCamera().combined);
            batch.begin();
            batch.setColor(1f,1f,1f,1f);
            try {
                font.setColor(Color.WHITE);
                String title = "GAME OVER";
                com.badlogic.gdx.graphics.g2d.GlyphLayout layout = new com.badlogic.gdx.graphics.g2d.GlyphLayout(font, title);
                float cx = camController.getCamera().position.x - layout.width / 2f;
                float cy = camController.getCamera().position.y + layout.height * 2f;
                font.draw(batch, layout, cx, cy);

                String hint = (gameOverTimer >= gameOverMinDisplay) ? "Press ENTER to continue" : " ";
                com.badlogic.gdx.graphics.g2d.GlyphLayout layout2 = new com.badlogic.gdx.graphics.g2d.GlyphLayout(font, hint);
                font.setColor(Color.LIGHT_GRAY);
                font.draw(batch, layout2, camController.getCamera().position.x - layout2.width / 2f, cy - 40f);
            } finally {
                batch.end();
            }

            if (gameOverTimer >= gameOverMinDisplay && Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) {
                gameOverActive = false;
                Runnable act = gameOverAction;
                gameOverAction = null;
                if (act != null) act.run();
            }
            return;
        }
        // Update loading manager first
        if (loadingManager != null && !loadingManager.isReady()) {
            loadingManager.update(delta);
        }

        // Update background animator if present
        if (backgroundAnimator != null) {
            backgroundAnimator.update(delta);
        }

        // ESC opens in-game menu instead of going to main menu
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            if (paused) {
                hidePauseMenu();
            } else {
                showPauseMenu();
            }
        }

        // Skip gameplay updates during loading
        if (!gameplayEnabled || (loadingManager != null && !loadingManager.isReady())) {
            renderLoadingScreen(delta);
            return;
        }

        // Respawn player to initial spawn with R
        // if (Gdx.input.isKeyJustPressed(Input.Keys.R)) {
        // player.respawn();
        // if (spawnMarker != null) {
        // spawnMarker.setPosition(playerSpawnX, playerSpawnY);
        // spawnMarker.playOnce();
        // }
        // // Reset tentacle capture states on respawn
        // for (com.jjmc.chromashift.environment.enemy.Tentacle t : tentacles) {
        // t.setPlayerCaptured(false);
        // }
        // }

        // Quick save/load: F11 = save, F12 = load (via DAO)
        try {
            if (Gdx.input.isKeyJustPressed(Input.Keys.F11)) {
                // Ensure player record exists in database
                try {
                    com.jjmc.chromashift.database.PlayerDAO.getPlayerIdByName("DefaultPlayer");
                } catch (Exception e) {
                    // Player doesn't exist, create it
                    try {
                        System.out.println("[DEBUG] Creating default player record");

                        com.jjmc.chromashift.database.PlayerDAO.createPlayer("DefaultPlayer", currentLevelPath);
                    } catch (Exception ex2) {
                        System.err.println("[ERROR] Failed to create player: " + ex2.getMessage());
                    }
                }

                // Save player state to database
                com.jjmc.chromashift.player.PlayerIO.PlayerState state = com.jjmc.chromashift.player.PlayerIO
                        .capture(player, currentLevelPath, visitedLevels);
                try {
                    System.out.println("[DEBUG] Attempting to save player state for ID: 1");
                    System.out.println(
                            "[DEBUG] Player state: x=" + state.x + ", y=" + state.y + ", diamonds=" + state.diamonds);
                    com.jjmc.chromashift.database.PlayerDAO.savePlayerState(1, state);
                    System.out.println("[DEBUG] Save completed successfully");
                    Gdx.app.log("TestSceneScreen", "✓ Player save saved to database");
                } catch (Exception ex) {
                    System.err.println("[ERROR] Failed to save player state: " + ex.getMessage());
                    ex.printStackTrace();
                    Gdx.app.error("TestSceneScreen", "Player save failed: " + ex.getMessage());
                }

                // Save current level state with all GameObjects
                com.jjmc.chromashift.screens.levels.LevelLoader.Result result = new com.jjmc.chromashift.screens.levels.LevelLoader.Result();
                result.walls.addAll(walls);
                result.interactables.addAll(interactables);
                result.collectibles.addAll(collectibles);
                result.tentacles.addAll(tentacles);
                result.boss = (boss != null) ? boss : bossGuardian;
                boolean levelOk = com.jjmc.chromashift.screens.levels.GameLevelSave.saveLevelOverrides(currentLevelPath,
                        result);
                Gdx.app.log("TestSceneScreen", "Level state " + (levelOk ? "saved" : "failed"));
            }
            if (Gdx.input.isKeyJustPressed(Input.Keys.F12)) {
                try {
                    System.out.println("[DEBUG] Attempting to load player state for ID: 1");
                    com.jjmc.chromashift.database.PlayerDAO.loadPlayerStateFromDB(1, player);

                    // Also restore visited levels
                    com.badlogic.gdx.utils.Array<String> loadedVisited = com.jjmc.chromashift.database.PlayerDAO
                            .loadVisitedLevelsFromDB(1);
                    if (loadedVisited != null && loadedVisited.size > 0) {
                        visitedLevels.clear();
                        visitedLevels.addAll(loadedVisited);
                        System.out.println("[DEBUG] Restored " + visitedLevels.size + " visited levels");
                    }

                    System.out.println("[DEBUG] Load completed successfully");
                    Gdx.app.log("TestSceneScreen",
                            "✓ Player loaded from database with " + visitedLevels.size + " visited levels");
                } catch (Exception ex) {
                    System.err.println("[ERROR] Failed to load player: " + ex.getMessage());
                    ex.printStackTrace();
                    Gdx.app.error("TestSceneScreen", "Failed to load player: " + ex.getMessage());
                }
            }
        } catch (Exception ignored) {
        }

        // First update non-button interactables
        Array<Rectangle> objectBounds = new Array<>();
        for (int i = 0; i < interactables.size; i++) {
            Interactable interactable = interactables.get(i);
            interactable.checkInteraction(player.getHitboxRect());
            if (interactable instanceof Box box) {
                objectBounds.add(box.getActivationBounds());
            } else if (interactable instanceof Orb orb) {
                objectBounds.add(orb.getActivationBounds());
            }
            // Check launchpad triggers
            if (interactable instanceof com.jjmc.chromashift.environment.Launchpad launchpad) {
                launchpad.checkAndLaunchPlayer(player, walls);
                // Check for boxes and orbs on launchpad (use indexed loop to avoid nested
                // iterator)
                for (int j = 0; j < interactables.size; j++) {
                    Interactable obj = interactables.get(j);
                    if (obj instanceof Box box) {
                        launchpad.checkAndLaunchBox(box);
                    } else if (obj instanceof Orb orb) {
                        launchpad.checkAndLaunchOrb(orb);
                    }
                }
            }
            if (!(interactable instanceof Button)) {
                interactable.update(delta);
            }
        }

        // Finalize target states after all laser updates
        try {
            Target.finalizeFrame();
        } catch (Throwable ignored) {
        }

        // Then update buttons with collected bounds
        for (int i = 0; i < interactables.size; i++) {
            Interactable interactable = interactables.get(i);
            if (interactable instanceof Button b) {
                b.update(delta, player.getHitboxRect(), objectBounds);
            }
        }

        // Update collectibles and check for collection
        for (int i = collectibles.size - 1; i >= 0; i--) {
            com.jjmc.chromashift.environment.collectible.Collectible c = collectibles.get(i);
            c.update(delta);
            c.checkCollision(player);
            // Remove collected items
            if (c.isCollected()) {
                collectibles.removeIndex(i);
            }
        }

        // Player update
        player.update(delta, groundY, solids, interactables, 1);

        // Update Tentacle System (physics & capture applied AFTER player attack
        // activation)
        for (com.jjmc.chromashift.environment.enemy.TentacleCapture tc : tentacleCaptures) {
            tc.update(delta);
        }
        // Perform a second collision check now that tentacle segment positions are
        // freshly updated.
        // This fixes ordering issue where Player updated (and attacked) before Tentacle
        // updated its segment hitboxes.
        if (player.getAttackHitbox() != null && player.getAttackHitbox().isActive()) {
            player.getAttackHitbox().checkEnemyCollisions(enemies);
        }

        // Clean up dead tentacle references but KEEP them in array for save system
        if (tentacles.size > 0) {
            for (int i = tentacles.size - 1; i >= 0; i--) {
                com.jjmc.chromashift.environment.enemy.Tentacle t = tentacles.get(i);
                if (!t.isAlive()) {
                    // Remove capture handler referencing this tentacle
                    for (int c = tentacleCaptures.size - 1; c >= 0; c--) {
                        if (tentacleCaptures.get(c).getTentacle() == t) {
                            tentacleCaptures.removeIndex(c);
                            break;
                        }
                    }
                    // Remove from enemies list used by Player attack system
                    if (enemies != null) {
                        enemies.removeValue(t, false);
                        player.setEnemies(enemies);
                    }
                    // DON'T remove from tentacles array - keep for save system
                    // tentacles.removeIndex(i);
                }
            }
        }

        // Boss update - set target to player position
        // Compute active trigger and provide bounds each frame
        String activeTrigger = null;
        Rectangle playerRect = player.getHitboxRect();
        for (int i = 0; i < interactables.size; i++) {
            Interactable it = interactables.get(i);
            if (it instanceof com.jjmc.chromashift.environment.TriggerZone tz) {
                String triggerId = tz.getId();
                // FinalBoss boundary zone
                if (boss != null && "trigger_6".equalsIgnoreCase(triggerId) && tz.getBounds() != null) {
                    boss.setTrigger6Bounds(tz.getBounds());
                    // Don't mark as active trigger
                }
                // Provide BossGuardian with trigger_1 bounds for crystal gating
                if (bossGuardian != null && "trigger_1".equalsIgnoreCase(triggerId) && tz.getBounds() != null) {
                    bossGuardian.setTriggerBounds(triggerId, tz.getBounds());
                }
                // Active trigger if player overlaps
                if (tz.getBounds() != null && tz.getBounds().overlaps(playerRect)) {
                    activeTrigger = triggerId;
                }
            }
        }

        // Boss update - set target to player position
        if (boss != null) {
            boss.setTarget(player.getX() + player.getHitboxWidth() / 2, player.getHitboxHeight() / 2 + player.getY());
            boss.setActiveTriggerZone(activeTrigger);
            boss.update(delta);
        }

        // BossGuardian update (skip if currently spawning)
        if (bossGuardian != null && !bossGuardian.isSpawning()) {
            bossGuardian.setTarget(player.getX() + player.getHitboxWidth() / 2,
                    player.getY() + player.getHitboxHeight() / 2);
            bossGuardian.setActiveTriggerZone(activeTrigger);
            bossGuardian.update(delta);
        } else if (bossGuardian != null && bossGuardian.isSpawning()) {
            // Update spawn sequence (handled in boss.update() during spawn)
            bossGuardian.update(delta);
        }

        // Check laser beams against the player hitbox; if intersecting, kill the player
        Rectangle playerHit = player.getHitboxRect();
        float beamThickness = 6f; // matches Laser/LaserRay outer thickness
        for (int i = 0; i < interactables.size; i++) {
            Interactable it = interactables.get(i);
            java.util.ArrayList<com.badlogic.gdx.math.Vector2> pts = null;
            if (it instanceof com.jjmc.chromashift.environment.interactable.Laser l) {
                pts = l.getCachedPoints();
            } else if (it instanceof com.jjmc.chromashift.environment.interactable.LaserRay lr) {
                pts = lr.getCachedPoints();
            }
            if (pts == null || pts.size() < 2)
                continue;
            if (beamIntersectsRect(pts, playerHit, beamThickness)) {
                // kill player instantly
                try {
                    player.getHealthSystem().kill(it);
                } catch (Throwable ignored) {
                }
                break;
            }
        }

        // Handle camera effects during BossGuardian spawn sequence
        if (bossGuardian != null && bossGuardian.isSpawning()) {
            // Lock camera to boss center during spawn
            Vector2 spawnFocus = bossGuardian.getSpawnCameraFocus();
            if (spawnFocus != null) {
                Vector3 spawnLockPos = new Vector3(spawnFocus.x, spawnFocus.y, 0);
                camController.lockCamera(spawnLockPos);
            }
            // Apply spawn zoom
            float spawnZoom = bossGuardian.getSpawnCameraZoom();
            camController.setTargetZoom(spawnZoom, 0.1f);
        } else if (spawnedPortal != null && spawnedPortal.isSpawning()) {
            // Pan camera to spawned portal while it is spawning
            Rectangle pb = spawnedPortal.getBounds();
            Vector3 portalPos = new Vector3(pb.x + pb.width / 2f, (pb.y + pb.height / 2f), 0);
            camController.lockCamera(portalPos);
            // Slight zoom-in during portal spawn
            camController.setTargetZoom(.9f, 0.2f);
            lastPortalWasSpawning = true;
        } else if (spawnedPortal != null && lastPortalWasSpawning && spawnedPortal.isActive() && !easingFromPortal) {
            // Portal finished spawning; begin easing back to player
            Rectangle pb = spawnedPortal.getBounds();
            portalEaseStart.set(pb.x + pb.width / 2f, pb.y + pb.height / 2f);
            portalEaseRemaining = portalEaseDuration;
            easingFromPortal = true;
            lastPortalWasSpawning = false;
        } else if (easingFromPortal) {
            // Ease camera position from portal to player over duration
            portalEaseRemaining = Math.max(0f, portalEaseRemaining - delta);
            float t = 1f - (portalEaseRemaining / portalEaseDuration);
            Vector2 playerCenter = new Vector2(player.getX() + player.getHitboxWidth() / 2f,
                    player.getY() + player.getHitboxHeight() / 2f);
            float easedX = portalEaseStart.x + (playerCenter.x - portalEaseStart.x) * t;
            float easedY = portalEaseStart.y + (playerCenter.y - portalEaseStart.y) * t;
            camController.lockCamera(new Vector3(easedX, easedY, 0));
            // Ease zoom from near-portal (0.9) to desiredZoom
            float easedZoom = 0.9f + (desiredZoom - 0.9f) * t;
            camController.setTargetZoom(easedZoom, 0.2f);
            if (portalEaseRemaining <= 0f) {
                easingFromPortal = false;
                spawnedPortal = null;
            }
        } else {
            // Normal gameplay - follow player
            camController.unlockCamera();
            Vector2 playerCenter = new Vector2(player.getX() + player.getHitboxWidth() / 2f,
                    player.getY() + player.getHitboxHeight() / 2f);
            camController.setTarget(playerCenter);
            camController.setTargetZoom(desiredZoom, zoomLerpSpeed);
            camController.setZoom(desiredZoom);
        }

        camController.update(delta);

        // Smoothly adjust camera zoom toward desiredZoom so view focuses slightly on
        // player
        if (camera != null) {
            camera.update();
        }

        // Draw
        Gdx.gl.glClearColor(0.08f, 0.09f, 0.12f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        // Draw Tentacles using ShapeRenderer (each tentacle manages its own begin/end)
        // Set projection once before drawing
        if (shape != null) {
            shape.setProjectionMatrix(camController.getCamera().combined);
            for (com.jjmc.chromashift.environment.enemy.Tentacle t : tentacles) {
                if (t.isAlive()) {
                    t.draw(shape);
                }
            }
            // No outer begin/end — Tentacle.draw() handles it internally
        }

        batch.setProjectionMatrix(camController.getCamera().combined);
        batch.begin();

        // Render background first (behind everything) using SpriteAnimator
        if (backgroundAnimator != null) {
            batch.setColor(1f, 1f, 1f, 1f); // Ensure full white color (no tint)
            float viewW = camera.viewportWidth * camera.zoom;
            float viewH = camera.viewportHeight * camera.zoom;
            float bgX = camera.position.x - viewW / 2f;
            float bgY = camera.position.y - viewH / 2f;
            backgroundAnimator.render(batch, bgX, bgY, viewW, viewH);
        }

        // Draw world: walls first, then interactables, collectibles, spawn marker,
        // boss, and player
        for (Wall w : walls)
            w.render(batch);
        for (Interactable i : interactables)
            i.render(batch);
        // Render collectibles (diamonds, etc.)
        for (com.jjmc.chromashift.environment.collectible.Collectible c : collectibles) {
            c.render(batch);
        }
        if (spawnMarker != null) {
            spawnMarker.update(delta);
            if (spawnMarker.isVisible()) {
                spawnMarker.render(batch);
            }
        }
        // Render boss between environment and player so it appears above environment
        // but behind player
                                        if (boss != null) {
                                            batch.setColor(1f,1f,1f,1f);
                                            boss.render(batch);
                                        }
                                        if (bossGuardian != null) {
                                            batch.setColor(1f,1f,1f,1f);
                                            bossGuardian.render(batch);
                                        }
                                        // Ensure neutral color before rendering player and any UI drawn with this batch
                                        batch.setColor(1f,1f,1f,1f);
                                        player.render(batch);

        // // Draw debug UI with clean layout
        // float baseX = camController.getCamera().position.x - 480 + 8; // Left align
        // float baseY = camController.getCamera().position.y + 260; // Top of screen
        // float lineHeight = 20f; // Space between lines

        // // Title
        // font.setColor(Color.GOLD);
        // font.draw(batch, "Debug Info (F3 for hitboxes)", baseX, baseY);

        // // Movement controls
        // font.setColor(Color.WHITE);
        // baseY -= lineHeight;
        // font.draw(batch, "Movement:", baseX, baseY);
        // font.setColor(Color.LIGHT_GRAY);
        // baseY -= lineHeight;
        // font.draw(batch, "• A/D - Move Left/Right", baseX + 10, baseY);
        // baseY -= lineHeight;
        // font.draw(batch, "• W - Jump", baseX + 10, baseY);
        // baseY -= lineHeight;
        // font.draw(batch, "• Space - Attack", baseX + 10, baseY);
        // baseY -= lineHeight;
        // font.draw(batch, "• SHIFT - Dash (once until landing)", baseX + 10, baseY);

        // // Combat/Health
        // baseY -= lineHeight * 1.5f;
        // font.setColor(Color.WHITE);
        // font.draw(batch, "Combat:", baseX, baseY);
        // font.setColor(Color.LIGHT_GRAY);
        // baseY -= lineHeight;
        // String healthText = String.format("• Player Health: %.0f/%.0f",
        // player.getHealthSystem().getCurrentHealth(),
        // player.getHealthSystem().getMaxHealth());
        // font.draw(batch, healthText, baseX + 10, baseY);
        // baseY -= lineHeight;
        // String bossHealthText = boss != null ? String.format("• Boss Health:
        // %.0f/%.0f", boss.getHealthSystem().getCurrentHealth(),
        // boss.getHealthSystem().getMaxHealth()) : "• Boss: None";
        // font.draw(batch, bossHealthText, baseX + 10, baseY);
        // baseY -= lineHeight;
        // font.draw(batch, "• O - Test damage (100)", baseX + 10, baseY);

        // // Interaction controls
        // baseY -= lineHeight * 1.5f;
        // font.setColor(Color.WHITE);
        // font.draw(batch, "Interaction:", baseX, baseY);
        // font.setColor(Color.LIGHT_GRAY);
        // baseY -= lineHeight;
        // font.draw(batch, "• G - Pick up/Throw objects (aim with mouse)", baseX + 10,
        // baseY);
        // baseY -= lineHeight;
        // font.draw(batch, "• F - Interact", baseX + 10, baseY);

        // // System controls
        // baseY -= lineHeight * 1.5f;
        // font.setColor(Color.WHITE);
        // font.draw(batch, "System:", baseX, baseY);
        // font.setColor(Color.LIGHT_GRAY);
        // baseY -= lineHeight;
        // font.draw(batch, "• R - Respawn", baseX + 10, baseY);
        // baseY -= lineHeight;
        // font.draw(batch, "• F3 - Show Debug Lines", baseX + 10, baseY);
        // baseY -= lineHeight;
        // font.draw(batch, "• ESC - Exit game", baseX + 10, baseY);

        // Status effects (if any active)
        if (player.getHealthSystem().isInvulnerable()) {
            font.setColor(Color.YELLOW);
            font.draw(batch, "INVULNERABLE",
                    camController.getCamera().position.x - 40, // Center on screen
                    camController.getCamera().position.y + 40);
        }
        batch.end();

        // HUD: Boss Guardian health bars (top-center)
        if (bossGuardian != null) {
            renderBossGuardianHud();
        }

        // Update and draw UI stage for shop dialogs
        uiStage.act(delta);
        uiStage.draw();

        // Debug visuals
        // Invisible ground/bar removed

        if (Gdx.input.isKeyPressed(Input.Keys.F3)) {
            shape.begin(ShapeRenderer.ShapeType.Line);
            for (Wall w : walls)
                w.debugDraw(shape);
            for (Interactable i : interactables)
                i.debugDraw(shape);
            player.debugDrawHitbox(shape);

            // Draw BossGuardian debug info
            if (bossGuardian != null) {
                bossGuardian.renderDebug(shape);
            }

            // Draw tentacle segment hitboxes and curl detection
            for (com.jjmc.chromashift.environment.enemy.Tentacle t : tentacles) {
                if (!t.isAlive())
                    continue;

                shape.setColor(0f, 1f, 1f, 0.5f);
                com.badlogic.gdx.math.Circle[] hitboxes = t.getSegmentHitboxes();
                if (hitboxes != null) {
                    for (com.badlogic.gdx.math.Circle c : hitboxes) {
                        if (c != null) {
                            shape.circle(c.x, c.y, c.radius, 16);
                        }
                    }
                }

                // Draw curl center and radius if curled (yellow)
                if (t.isCurled() && t.hasFullCurl()) {
                    com.badlogic.gdx.math.Vector2 center = t.getCurlCenter();
                    float radius = t.getCurlRadius();

                    shape.setColor(1f, 1f, 0f, 0.6f);
                    shape.circle(center.x, center.y, radius, 32);

                    shape.setColor(1f, 0f, 0f, 0.8f);
                    float crossSize = 10f;
                    shape.line(center.x - crossSize, center.y, center.x + crossSize, center.y);
                    shape.line(center.x, center.y - crossSize, center.x, center.y + crossSize);

                    // Draw hit counter
                    shape.end();
                    batch.begin();
                    font.setColor(Color.YELLOW);
                    font.draw(batch, "Hits: " + t.getHitsThisCapture() + "/3", center.x - 20, center.y + radius + 20);
                    batch.end();
                    shape.begin(ShapeRenderer.ShapeType.Line);
                }
            }

            // Draw player attack hitbox (red)
            if (player.getAttackHitbox() != null) {
                player.getAttackHitbox().debugDraw(shape);
            }

            // Draw respawn areas for boxes and orbs
            shape.setColor(0f, 0.5f, 1f, 0.25f);
            for (Interactable i : interactables) {
                if (i instanceof com.jjmc.chromashift.environment.interactable.Box b) {
                    Rectangle area = b.getRespawnArea();
                    if (area != null)
                        shape.rect(area.x, area.y, area.width, area.height);
                } else if (i instanceof com.jjmc.chromashift.environment.interactable.Orb o) {
                    Rectangle area = o.getRespawnArea();
                    if (area != null)
                        shape.rect(area.x, area.y, area.width, area.height);
                }
            }
            shape.end();

            // Render debug text for BossGuardian
            if (bossGuardian != null) {
                batch.begin();
                bossGuardian.renderDebugText(batch);
                batch.end();
            }
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.O)) {
            player.getHealthSystem().damage(100f, null);
        }

        if (boss != null && Gdx.input.isKeyJustPressed(Input.Keys.K)) {
            boss.getHealthSystem().damage(100f, null);
        }
        if (boss != null && Gdx.input.isKeyJustPressed(Input.Keys.L)) {
            boss.getHealthSystem().heal(100f);
        }
        if (bossGuardian != null && Gdx.input.isKeyJustPressed(Input.Keys.K)) {
            bossGuardian.getHealthSystem().damage(100f, null);
        }
        if (bossGuardian != null && Gdx.input.isKeyJustPressed(Input.Keys.L)) {
            bossGuardian.getHealthSystem().heal(100f);
        }

        // Render loading overlay on top of everything if still loading
        if (loadingManager != null && loadingOverlay != null && !loadingManager.isReady()) {
            loadingOverlay.render();
        }
    }

    /**
     * Render the loading screen while level is initializing.
     */
    private void renderLoadingScreen(float delta) {
        // Clear screen
        Gdx.gl.glClearColor(0.0f, 0.0f, 0.0f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        // Render loading overlay
        if (loadingOverlay != null) {
            loadingOverlay.render();
        }
    }

    // ===== HUD: Boss Guardian Health =====
    private void renderBossGuardianHud() {
        // Use UI stage's camera for screen-space drawing
        com.badlogic.gdx.graphics.Camera uiCam = uiStage.getViewport().getCamera();
        int sw = (int) uiStage.getViewport().getWorldWidth();
        int sh = (int) uiStage.getViewport().getWorldHeight();

        // Compute total width of three bars + gaps
                                        // Determine alive guardians to center visible bars
                                        java.util.ArrayList<Integer> alive = new java.util.ArrayList<Integer>(3);
                                        for (int i = 1; i <= 3; i++) {
                                            if (bossGuardian.getGuardianHealth(i) > 0f) alive.add(i);
                                        }
                                        int count = alive.size();
                                        if (count == 0) return; // nothing to draw
                                        int totalWidth = HUD_BAR_WIDTH * count + HUD_BAR_GAP * Math.max(0, count - 1);
                                        int xStart = (sw - totalWidth) / 2;
        int y = sh - HUD_MARGIN_TOP - HUD_BAR_HEIGHT;

        // Draw bar backgrounds with ShapeRenderer using UI projection
        shape.setProjectionMatrix(uiCam.combined);
        shape.begin(ShapeRenderer.ShapeType.Filled);
        // Background boxes (dark)
        shape.setColor(0f, 0f, 0f, 0.55f);
                                        for (int di = 0; di < count; di++) {
                                            int x = xStart + di * (HUD_BAR_WIDTH + HUD_BAR_GAP);
            shape.rect(x - 2, y - 2, HUD_BAR_WIDTH + 4, HUD_BAR_HEIGHT + 4);
        }
        // Health fill per guardian
                                        for (int di = 0; di < count; di++) {
                                            int i = alive.get(di) - 1;
                                            float hp = bossGuardian.getGuardianHealth(i + 1);
                                            float hpMax = bossGuardian.getGuardianMaxHealth(i + 1);
            float pct = (hpMax <= 0f) ? 0f : Math.max(0f, Math.min(1f, hp / hpMax));
                                            int x = xStart + di * (HUD_BAR_WIDTH + HUD_BAR_GAP);
            // Empty bar (gray)
            shape.setColor(0.25f, 0.25f, 0.25f, 0.9f);
            shape.rect(x, y, HUD_BAR_WIDTH, HUD_BAR_HEIGHT);
            // Filled (red -> orange if low)
            if (pct > 0.33f) shape.setColor(0.8f, 0.15f, 0.15f, 1f);
            else shape.setColor(0.95f, 0.5f, 0.1f, 1f);
            shape.setColor(fill);
            shape.rect(x, y, (int) (HUD_BAR_WIDTH * pct), HUD_BAR_HEIGHT);
        }
        shape.end();

        // Draw names with SpriteBatch using UI projection
                                        batch.setProjectionMatrix(uiCam.combined);
                                        batch.begin();
                                        batch.setColor(1f,1f,1f,1f);
        try {
            font.setColor(Color.WHITE);
            com.badlogic.gdx.graphics.g2d.GlyphLayout layout = new com.badlogic.gdx.graphics.g2d.GlyphLayout();
                                            for (int di = 0; di < count; di++) {
                                                int idx = alive.get(di);
                                                int x = xStart + di * (HUD_BAR_WIDTH + HUD_BAR_GAP);
                                                String name = "Guardian " + idx;
                layout.setText(font, name);
                float tx = x + HUD_BAR_WIDTH / 2f - layout.width / 2f;
                float ty = y + HUD_BAR_HEIGHT + HUD_NAME_OFFSET_Y;
                font.draw(batch, layout, tx, ty);
            }
        } finally {
            batch.end();
        }
    }

    // ===== In-Game Pause Menu =====
    private void showPauseMenu() {
        paused = true;
        // Auto-save when pausing
        try {
            saveAllState(currentLevelPath);
            Gdx.app.log("GameSceneScreen", "Auto-saved on pause");
        } catch (Exception e) {
            Gdx.app.log("GameSceneScreen", "Auto-save on pause failed: " + e.getMessage());
        }
        if (pauseDialog == null) {
            com.badlogic.gdx.scenes.scene2d.ui.Skin dSkin = new com.badlogic.gdx.scenes.scene2d.ui.Skin(
                    Gdx.files.internal("ui/uiskin.json"));
            pauseDialog = new com.badlogic.gdx.scenes.scene2d.ui.Dialog("", dSkin);
            pauseDialog.setModal(true);
            pauseDialog.setMovable(false);

            com.badlogic.gdx.scenes.scene2d.ui.Table content = pauseDialog.getContentTable();
            content.defaults().pad(12);

            com.chromashift.helper.SpriteLabel resumeLbl = com.chromashift.helper.UIHelper.createSpriteLabel("RESUME",
                    "default", 4f);
            com.chromashift.helper.SpriteLabel settingsLbl = com.chromashift.helper.UIHelper
                    .createSpriteLabel("SETTINGS", "default", 4f);
            com.chromashift.helper.SpriteLabel menuLbl = com.chromashift.helper.UIHelper.createSpriteLabel("MENU",
                    "default", 4f);
            // Ensure labels receive input events
            if (resumeLbl != null)
                resumeLbl.setTouchable(com.badlogic.gdx.scenes.scene2d.Touchable.enabled);
            if (settingsLbl != null)
                settingsLbl.setTouchable(com.badlogic.gdx.scenes.scene2d.Touchable.enabled);
            if (menuLbl != null)
                menuLbl.setTouchable(com.badlogic.gdx.scenes.scene2d.Touchable.enabled);

            if (resumeLbl != null) {
                resumeLbl.addListener(new com.badlogic.gdx.scenes.scene2d.utils.ClickListener() {
                    @Override
                    public void clicked(com.badlogic.gdx.scenes.scene2d.InputEvent event, float x, float y) {
                        hidePauseMenu();
                    }
                });
                content.add(resumeLbl).row();
            }
            if (settingsLbl != null) {
                settingsLbl.addListener(new com.badlogic.gdx.scenes.scene2d.utils.ClickListener() {
                    @Override
                    public void clicked(com.badlogic.gdx.scenes.scene2d.InputEvent event, float x, float y) {
                        showSettingsDialog();
                    }
                });
                content.add(settingsLbl).row();
            }
            if (menuLbl != null) {
                menuLbl.addListener(new com.badlogic.gdx.scenes.scene2d.utils.ClickListener() {
                    @Override
                    public void clicked(com.badlogic.gdx.scenes.scene2d.InputEvent event, float x, float y) {
                        saveAllState(currentLevelPath);
                        ((com.badlogic.gdx.Game) Gdx.app.getApplicationListener())
                                .setScreen(new MainMenuScreen());
                    }
                });
                content.add(menuLbl).row();
            }
        }
        pauseDialog.pack();
        pauseDialog.show(uiStage);
        // Make sure dialog itself receives input focus
        pauseDialog.setTouchable(com.badlogic.gdx.scenes.scene2d.Touchable.enabled);
        uiStage.setKeyboardFocus(pauseDialog);
        pauseDialog.setPosition((uiStage.getWidth() - pauseDialog.getWidth()) / 2f,
                (uiStage.getHeight() - pauseDialog.getHeight()) / 2f);
    }

    private void hidePauseMenu() {
        paused = false;
        if (pauseDialog != null)
            pauseDialog.hide();
        if (settingsDialog != null)
            settingsDialog.hide();
    }

    private void showSettingsDialog() {
        if (settingsDialog == null) {
            com.badlogic.gdx.scenes.scene2d.ui.Skin dSkin = new com.badlogic.gdx.scenes.scene2d.ui.Skin(
                    Gdx.files.internal("ui/uiskin.json"));
            settingsDialog = new com.badlogic.gdx.scenes.scene2d.ui.Dialog("", dSkin);
            settingsDialog.setModal(true);
            settingsDialog.setMovable(false);

            com.badlogic.gdx.scenes.scene2d.ui.Table content = settingsDialog.getContentTable();
            content.defaults().pad(8);

            final com.jjmc.chromashift.config.AudioConfig audio = com.jjmc.chromashift.config.AudioConfig.getInstance();
            addVolumeRow(content, "MASTER", audio.getMasterVolume(), v -> audio.setMasterVolume(v));
            addVolumeRow(content, "MUSIC", audio.getMusicVolume(), v -> audio.setMusicVolume(v));
            addVolumeRow(content, "SFX", audio.getSfxVolume(), v -> audio.setSfxVolume(v));

            settingsDialog.button("Close", true);
        }
        settingsDialog.pack();
        settingsDialog.show(uiStage);
        settingsDialog.setPosition((uiStage.getWidth() - settingsDialog.getWidth()) / 2f,
                (uiStage.getHeight() - settingsDialog.getHeight()) / 2f);
    }

    private static final Rectangle beamRectCache = new Rectangle();
    private static final com.badlogic.gdx.math.Vector2 beamA = new com.badlogic.gdx.math.Vector2();
    private static final com.badlogic.gdx.math.Vector2 beamB = new com.badlogic.gdx.math.Vector2();
    private void addVolumeRow(com.badlogic.gdx.scenes.scene2d.ui.Table parent, String title, float initial01,
            java.util.function.Consumer<Float> setter) {
        com.chromashift.helper.SpriteLabel titleLbl = com.chromashift.helper.UIHelper.createSpriteLabel(title,
                "default", 2.5f);
        com.chromashift.helper.SpriteLabel valueLbl = com.chromashift.helper.UIHelper
                .createSpriteLabel(String.format("%.0f%%", initial01 * 100f), "default", 1.8f);
        com.badlogic.gdx.scenes.scene2d.ui.Slider slider = com.chromashift.helper.UIHelper.createSlider(0, 100, 1,
                false, new com.badlogic.gdx.scenes.scene2d.ui.Skin(Gdx.files.internal("ui/uiskin.json")),
                new com.badlogic.gdx.scenes.scene2d.utils.ChangeListener() {
                    @Override
                    public void changed(ChangeEvent event, com.badlogic.gdx.scenes.scene2d.Actor actor) {
                        float val = ((com.badlogic.gdx.scenes.scene2d.ui.Slider) actor).getValue();
                        valueLbl.setText(String.format("%.0f%%", val));
                        setter.accept(val / 100f);
                    }
                });
        slider.setValue(initial01 * 100f);

        com.badlogic.gdx.scenes.scene2d.ui.Table row = new com.badlogic.gdx.scenes.scene2d.ui.Table();
        row.add(titleLbl).right().padRight(10);
        row.add(slider).width(240).padRight(10);
        row.add(valueLbl).left();
        parent.add(row).row();
    }

    /**
     * Return true if any segment in the polyline `points` intersects rectangle `r`
     * within `thickness`.
     */
    private boolean beamIntersectsRect(java.util.ArrayList<com.badlogic.gdx.math.Vector2> points, Rectangle r,
            float thickness) {
        if (points == null || points.size() < 2 || r == null)
            return false;
        float pad = thickness * 0.5f;
        beamRectCache.set(r.x - pad, r.y - pad, r.width + pad * 2f, r.height + pad * 2f);

        for (int i = 0; i < points.size() - 1; i++) {
            com.badlogic.gdx.math.Vector2 a = points.get(i);
            com.badlogic.gdx.math.Vector2 b = points.get(i + 1);
            if (beamRectCache.contains(a.x, a.y) || beamRectCache.contains(b.x, b.y))
                return true;
            // Reuse vectors for rect edges
            beamA.set(beamRectCache.x, beamRectCache.y);
            beamB.set(beamRectCache.x + beamRectCache.width, beamRectCache.y);
            if (segmentsIntersect(a, b, beamA, beamB)) return true;
            beamA.set(beamRectCache.x + beamRectCache.width, beamRectCache.y);
            beamB.set(beamRectCache.x + beamRectCache.width, beamRectCache.y + beamRectCache.height);
            if (segmentsIntersect(a, b, beamA, beamB)) return true;
            beamA.set(beamRectCache.x + beamRectCache.width, beamRectCache.y + beamRectCache.height);
            beamB.set(beamRectCache.x, beamRectCache.y + beamRectCache.height);
            if (segmentsIntersect(a, b, beamA, beamB)) return true;
            beamA.set(beamRectCache.x, beamRectCache.y + beamRectCache.height);
            beamB.set(beamRectCache.x, beamRectCache.y);
            if (segmentsIntersect(a, b, beamA, beamB)) return true;
        }
        return false;
    }

    // Standard 2D segment intersection test
    private boolean segmentsIntersect(com.badlogic.gdx.math.Vector2 p1, com.badlogic.gdx.math.Vector2 p2,
            com.badlogic.gdx.math.Vector2 q1, com.badlogic.gdx.math.Vector2 q2) {
        float o1 = orient(p1, p2, q1);
        float o2 = orient(p1, p2, q2);
        float o3 = orient(q1, q2, p1);
        float o4 = orient(q1, q2, p2);

        if (o1 * o2 < 0f && o3 * o4 < 0f)
            return true;
        return false;
    }

    private float orient(com.badlogic.gdx.math.Vector2 a, com.badlogic.gdx.math.Vector2 b,
            com.badlogic.gdx.math.Vector2 c) {
        return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
    }

    /**
     * Level progression logic: determines next level based on current level.
     * Progression: level1 -> level2 -> level3 -> bossroom -> level4 -> level5 ->
     * level6 -> bossroom
     */
    private void advanceToNextLevel() {
        String nextLevel = getNextLevelPath(currentLevelPath);
        if (nextLevel == null) {
            // No next level mapped: return to main menu
            Gdx.app.log("GameSceneScreen", "No next level defined for: " + currentLevelPath + ". Returning to menu.");
            saveAllState(currentLevelPath);
            ((com.badlogic.gdx.Game) Gdx.app.getApplicationListener())
                    .setScreen(new com.jjmc.chromashift.screens.ui.MainMenuScreen());
            return;
        }

        Gdx.app.log("GameSceneScreen", "Advancing from " + currentLevelPath + " to " + nextLevel);

        // Ensure progression tracking includes the next level
        if (visitedLevels != null && !visitedLevels.contains(nextLevel, false)) {
            visitedLevels.add(nextLevel);
        }

        // 1) Save current level state as-is (player coords belong to current level)
        saveAllState(currentLevelPath);

        // 2) Update the player save so "Continue" resumes in the next level at its spawn,
        // instead of incorrectly restoring the previous level's coordinates into the next level.
        savePlayerForLevelTransition(nextLevel);

        // Transition to next level (load saved state since we just saved)
        ((com.badlogic.gdx.Game) Gdx.app.getApplicationListener()).setScreen(
                new GameSceneScreen(nextLevel,
                        com.jjmc.chromashift.screens.levels.LevelLoader.LoadMode.SAVED_IF_EXISTS));
    }

    /**
     * Save the player's progress for a level transition.
     *
     * Critical: When advancing from level A -> level B, the player's current (x,y) are in
     * level A coordinates. If we set PlayerState.currentLevel = level B without also
     * moving the saved (x,y) to level B's spawn, then Continue will load level B and
     * place the player at an invalid/wrong position.
     */
    private void savePlayerForLevelTransition(String nextLevelPath) {
        if (nextLevelPath == null || player == null) return;
        try {
            float spawnX = 0f;
            float spawnY = 0f;

            // Load next level spawn from ORIGINAL (ignore overrides) so the spawn always matches the JSON spawn marker.
            try {
                com.jjmc.chromashift.screens.levels.LevelLoader.Result next =
                        com.jjmc.chromashift.screens.levels.LevelLoader.loadFromWorkspace(
                                nextLevelPath,
                                com.jjmc.chromashift.screens.levels.LevelLoader.LoadMode.ORIGINAL);
                spawnX = next.spawnX;
                spawnY = next.spawnY;
            } catch (Exception ex1) {
                com.jjmc.chromashift.screens.levels.LevelLoader.Result next =
                        com.jjmc.chromashift.screens.levels.LevelLoader.load(
                                nextLevelPath,
                                com.jjmc.chromashift.screens.levels.LevelLoader.LoadMode.ORIGINAL);
                spawnX = next.spawnX;
                spawnY = next.spawnY;
            }

            com.jjmc.chromashift.player.PlayerIO.PlayerState s =
                    com.jjmc.chromashift.player.PlayerIO.capture(player, nextLevelPath, visitedLevels);

            // Override position/respawn for the next level
            s.x = spawnX;
            s.y = spawnY;
            s.velocityX = 0f;
            s.velocityY = 0f;
            s.onGround = false;
            s.canJump = true;
            s.dashing = false;
            s.dashTimer = 0f;
            s.respawnX = spawnX;
            s.respawnY = spawnY;

            // Persist
            com.jjmc.chromashift.player.PlayerIO.saveToWorkspace("player_save.json", s);
            try {
                com.jjmc.chromashift.database.PlayerDAO.savePlayerState(1, s);
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Returns the next level path based on current level.
     * Progression order: tutorial -> level1 -> level2 -> level3 -> level4 -> level5
     * -> level6 -> bossroom -> bossroom1 -> menu
     */
    private String getNextLevelPath(String current) {
        if (current == null)
            return null;

        // Normalize path for comparison
        String normalized = current.toLowerCase().replace("\\", "/");

        if (normalized.contains("tutorial")) return "levels/level1.json";
        if (normalized.contains("level1")) return "levels/level2.json";
        if (normalized.contains("level2")) return "levels/level3.json";
        if (normalized.contains("level3")) return "levels/level4.json";
        if (normalized.contains("level4")) return "levels/level5.json";
        if (normalized.contains("level5")) return "levels/level6.json";
        if (normalized.contains("level6")) return "levels/bossroom.json";
        if (normalized.contains("bossroom1")) return null;
        if (normalized.contains("bossroom")) return "levels/bossroom1.json";
        return null;
    }
    
    /**
     * Centralized auto-save routine for player and level.
     * Saves to workspace JSON and attempts DB writes via DAOs.
     */
    private void saveAllState(String currentLevelForSave) {
        try {
            // Capture player with current level context and visited levels
            com.jjmc.chromashift.player.PlayerIO.PlayerState playerState = com.jjmc.chromashift.player.PlayerIO
                    .capture(player, currentLevelForSave, visitedLevels);

            // Save player to workspace (legacy) and DAO (DB)
            com.jjmc.chromashift.player.PlayerIO.saveToWorkspace("player_save.json", playerState);
            try {
                com.jjmc.chromashift.database.PlayerDAO.savePlayerState(1, playerState);
                Gdx.app.log("TestSceneScreen", "✓ Player auto-saved to database");
            } catch (Exception dbEx) {
                Gdx.app.log("TestSceneScreen", "Player DB save failed: " + dbEx.getMessage());
            }

            // Prepare level result snapshot
            com.jjmc.chromashift.screens.levels.LevelLoader.Result result = new com.jjmc.chromashift.screens.levels.LevelLoader.Result();
            result.walls.addAll(walls);
            result.solids.addAll(solids);
            result.interactables.addAll(interactables);
            result.collectibles.addAll(collectibles);
            if (tentacles != null) {
                result.tentacles.addAll(tentacles);
            }
            result.boss = (boss != null) ? boss : bossGuardian;
            result.spawnX = playerSpawnX;
            result.spawnY = playerSpawnY;

            boolean levelSaved = com.jjmc.chromashift.screens.levels.GameLevelSave.saveLevelOverrides(
                    currentLevelPath, result);
            Gdx.app.log("TestSceneScreen", "Level state saved: " + levelSaved);
        } catch (Exception e) {
            Gdx.app.log("TestSceneScreen", "Error during auto-save: " + e.getMessage());
        }
    }

    @Override
    public void resize(int width, int height) {
        if (width <= 0 || height <= 0)
            return;
        camera.setToOrtho(false, width, height);
        if (uiStage != null) {
            uiStage.getViewport().update(width, height, true);
        }
    }

    @Override
    public void pause() {
    }

    @Override
    public void resume() {
    }

    @Override
    public void hide() {
        // Autosave when leaving the screen (e.g., going to menu)
        try {
            saveAllState(currentLevelPath);
        } catch (Throwable t) {
            Gdx.app.log("TestSceneScreen", "Autosave on hide failed: " + t.getMessage());
        }
        // Disable culling when leaving gameplay (e.g., to editor/menu)
        try {
            com.chromashift.helper.VisibilityCuller.setEnabled(false);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void dispose() {
        // Autosave on application/window close
        try {
            saveAllState(currentLevelPath);
        } catch (Throwable t) {
            Gdx.app.log("TestSceneScreen", "Autosave on dispose failed: " + t.getMessage());
        }
        if (ctx != null)
            ctx.dispose();
        player.dispose();
        if (boss != null)
            boss.disposeParts();
        if (bossGuardian != null)
            bossGuardian.dispose();
        if (backgroundAnimator != null) {
            backgroundAnimator.dispose();
        }
        // dispose button sprites
        for (Interactable i : interactables)
            if (i instanceof Button b)
                b.dispose();
        // dispose collectibles
        for (com.jjmc.chromashift.environment.collectible.Collectible c : collectibles)
            c.dispose();
        // dispose shops
        for (com.jjmc.chromashift.environment.interactable.Shop s : shops)
            s.dispose();
        // dispose UI stage
        if (uiStage != null)
            uiStage.dispose();
        // dispose wall/shared textures
        com.jjmc.chromashift.environment.Wall.dispose();
    }
}
