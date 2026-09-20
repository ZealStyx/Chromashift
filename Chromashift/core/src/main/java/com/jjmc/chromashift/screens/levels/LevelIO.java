package com.jjmc.chromashift.screens.levels;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.Array;

/**
 * Simple JSON-based level load/save utility using libGDX Json and file handles.
 * Levels should call LevelIO.load("levels/level1.json") to get a LevelState
 * structure. Saved changes are written to the local storage so they persist
 * between runs.
 */
public class LevelIO {

    public static class LevelState {
        public Meta meta;
        public SpawnData spawn;
        public Array<WallData> walls;
        public Array<InteractableData> interactables;
        public Array<BoxData> boxes;
        public Array<OrbData> orbs;
        public Array<LaunchpadData> launchpads;
        public Array<LaserData> lasers;
        public Array<MirrorData> mirrors;
        public Array<GlassData> glasses;
        public Array<DiamondData> diamonds;
        public Array<ShopData> shops;
        public Array<TentacleData> tentacles;
        public Array<TriggerData> triggers;
            public Array<KeyData> keys;
            public Array<LockedDoorData> lockedDoors;
            public Array<HealthPotionData> healthPotions;
        public BossData boss;

        public static class Meta {
            public String name;
            public int version;
        }

        public static class SpawnData {
            public float x, y;
        }

        public static class WallData {
            public float x, y, width, height;
        }

        public static class InteractableData {
            public String type;
            public String id;
            public float x, y;
            public String targetId;
            public String color;
            public String openDirection;
            public String orientation;
            public int cols;
            public int rows;
            // Door open/close speeds (units per second). Defaults to 3.0f when not set.
            public float openSpeed = 3f;
            public float closeSpeed = 3f;
            // Portal links: optional lever ids required to activate the portal
            public String lever1Id;
            public String lever2Id;
            // Portal persisted state, default inactive
            public String portalState;
        }

        public static class BoxData {
            public float x, y;
            public String color = "CYAN";
            // Respawn area dimensions (centered on x,y). Defaults set by editor if 0.
            public float areaW = 1600f;
            public float areaH = 1200f;
        }

        public static class OrbData {
            public float x, y;
            // Whether this orb should bounce on collisions (default: true for backward
            // compatibility)
            public boolean bouncy = true;
            // Respawn area dimensions (centered on x,y). Defaults set by editor if 0.
            public float areaW = 1600f;
            public float areaH = 1200f;
        }

        public static class LaunchpadData {
            public float x, y;
            public String direction; // UP, DOWN, LEFT, RIGHT, etc.
            public float speed = 600f; // default launch speed
        }

        public static class LaserData {
            public String id;
            public float x, y;
            public float rotation = 0f; // degrees
            public int maxBounces = 8;
            // Optional control links: button IDs to rotate left/right, and lever ID to
            // rotate on toggle
            public String leftButtonId;
            public String rightButtonId;
            public String leverId;
            public float rotateStep = 90f;
            // If true, spawn as an interactable LaserRay that the player can rotate
            public boolean rotating = false;
        }

        public static class MirrorData {
            public String id; // optional unique id for linking
            public float x, y, width, height;
            public float angleDeg = 45f;
        }

        public static class GlassData {
            public float x, y, width, height;
            public boolean rainbow = true;
            public float speed = 1.5f;
            public String color = "CYAN"; // user-selected base color (RAINBOW overrides)
        }

        public static class DiamondData {
            public float x, y;
        }

        public static class ShopData {
            public float x, y;
        }

        public static class TentacleData {
            public float x, y;
            public int segments = 30; // Default segment count
        }

        public static class TriggerData {
            public String id; // unique identifier for this trigger
            public float x, y;
            public float width = 64f;
            public float height = 64f;
            public String color = "RED"; // debug color name
        }

        public static class KeyData {
            public float x, y;
        }

        public static class LockedDoorData {
            public float x, y;
            public String orientation = "VERTICAL";
        }
        
        public static class HealthPotionData {
            public float x, y;
        }

        public static class BossData {
            public float x, y;
            public boolean guardian; // true = BossGuardian, false = FinalBoss
        }
    }

    private static final Json json = new Json();

    static {
        // Be permissive when parsing level files: ignore unknown fields so
        // that small-format differences or extra values in shipped JSON won't
        // break the editor. This helps avoid SerializationException when
        // loading hand-edited or legacy level data.
        try {
            json.setIgnoreUnknownFields(true);
            json.setOutputType(com.badlogic.gdx.utils.JsonWriter.OutputType.json);
            json.setUsePrototypes(false);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Load a level JSON. First try local (writable) path so user edits persist.
     * If not present locally, fall back to internal (packaged) asset and copy it
     * to local for edits.
     * 
     * @param path relative path under assets (e.g. "levels/level1.json")
     */
    public static LevelState load(String path) {
        try {
            // Primary behavior: load the packaged internal (build/resource) level first
            // so both TestSceneScreen and LevelMakerScreen use the same source of truth.
            FileHandle internal = Gdx.files.internal(path);
            if (internal != null && internal.exists()) {
                String text = internal.readString();
                try {
                    LevelState s = json.fromJson(LevelState.class, text);
                    // Ensure new fields are initialized (for backward compatibility with old level files)
                    ensureArraysInitialized(s);
                    // Deduplicate objects in case JSON has duplicates
                    deduplicateObjects(s);
                    // Copy internal asset to workspace only if workspace file doesn't exist
                    // Previously overwrote workspace edits - fixed to preserve workspace changes
                    try {
                        java.io.File assetsDir = findProjectAssetsDir();
                        if (assetsDir != null) {
                            java.io.File out = new java.io.File(assetsDir,
                                    path.replace('/', java.io.File.separatorChar));
                            if (!out.exists()) {
                                java.io.File parent = out.getParentFile();
                                if (parent != null && !parent.exists())
                                    parent.mkdirs();
                                Gdx.files.absolute(out.getAbsolutePath()).writeString(text, false);
                                Gdx.app.log("LevelIO",
                                        "Copied internal level to workspace assets: " + out.getAbsolutePath());
                            }
                        }
                    } catch (Exception ex) {
                        Gdx.app.error("LevelIO",
                                "Failed to copy internal level to workspace assets: " + ex.getMessage());
                    }
                    return s;
                } catch (Exception parseEx) {
                    Gdx.app.error("LevelIO", "Failed to parse level JSON from internal asset: " + path, parseEx);
                    try {
                        Gdx.app.error("LevelIO", "Content preview: " + text.substring(0, Math.min(400, text.length())));
                    } catch (Exception ignored) {
                    }
                }
            }
            // If internal not available or parsing failed, try the workspace assets folder
            // as a fallback.
            java.io.File assetsDir = findProjectAssetsDir();
            if (assetsDir != null) {
                java.io.File candidate = new java.io.File(assetsDir, path.replace('/', java.io.File.separatorChar));
                if (candidate.exists()) {
                    String text = Gdx.files.absolute(candidate.getAbsolutePath()).readString();
                    try {
                        LevelState s = json.fromJson(LevelState.class, text);
                        ensureArraysInitialized(s);
                        deduplicateObjects(s);
                        return s;
                    } catch (Exception parseEx) {
                        Gdx.app.error("LevelIO",
                                "Failed to parse level JSON from workspace asset: " + candidate.getAbsolutePath(),
                                parseEx);
                        try {
                            Gdx.app.error("LevelIO",
                                    "Content preview: " + text.substring(0, Math.min(400, text.length())));
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        // return empty default state
        LevelState s = new LevelState();
        s.meta = new LevelState.Meta();
        s.meta.name = path;
        s.meta.version = 1;
        s.spawn = new LevelState.SpawnData();
        s.spawn.x = 0;
        s.spawn.y = 40;
        s.walls = new Array<>();
        s.interactables = new Array<>();
        s.boxes = new Array<>();
        s.orbs = new Array<>();
        s.launchpads = new Array<>();
        s.lasers = new Array<>();
        s.mirrors = new Array<>();
        s.glasses = new Array<>();
        s.diamonds = new Array<>();
        s.shops = new Array<>();
        s.boss = null;
        s.tentacles = new Array<>();
        s.keys = new Array<>();
        s.lockedDoors = new Array<>();
        s.healthPotions = new Array<>();
        return s;
    }

    /**
     * Save the level state to workspace assets and mirror into build resources
     * (overwrites).
     */
    public static boolean save(String path, LevelState state) {
        try {
            // Deduplicate objects before saving
            deduplicateObjects(state);
            
            String text = json.prettyPrint(state);
            // Attempt to write directly into the project's assets folder. Do NOT write to
            // application-local storage to avoid creating duplicate level files outside the
            // project's assets directory.
            java.io.File assetsDir = findProjectAssetsDir();
            if (assetsDir == null) {
                Gdx.app.error("LevelIO", "Project assets folder not found; aborting save for: " + path);
                return false;
            }
            java.io.File out = new java.io.File(assetsDir, path.replace('/', java.io.File.separatorChar));
            java.io.File parent = out.getParentFile();
            if (parent != null && !parent.exists())
                parent.mkdirs();
            try {
                Gdx.files.absolute(out.getAbsolutePath()).writeString(text, false);
                Gdx.app.log("LevelIO", "Saved level to workspace assets: " + out.getAbsolutePath());
                // Also mirror to build resources so the running game/editor sees the latest
                // file
                try {
                    boolean ok = writeToBuildResources(path, text);
                    if (ok) {
                        Gdx.app.log("LevelIO", "Mirrored level to build resources: " + path);
                    } else {
                        Gdx.app.log("LevelIO", "Build resources directory not found; skipping mirror for: " + path);
                    }
                } catch (Exception ex) {
                    Gdx.app.error("LevelIO", "Failed to mirror saved level to build resources: " + ex.getMessage(), ex);
                }
                return true;
            } catch (Exception ex) {
                Gdx.app.error("LevelIO", "Failed to write level file to workspace assets: " + ex.getMessage(), ex);
                return false;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Load a level preferring the workspace `assets/` copy first, then copy it into
     * the game's build resources folder (if found) so the running game sees the
     * latest file. This is intended for use by the Level Maker/editor which edits
     * files in the workspace and needs them synced into the built resources.
     */
    public static LevelState loadFromWorkspaceThenCopyToBuild(String path) {
        try {
            // Try workspace assets first
            java.io.File assetsDir = findProjectAssetsDir();
            if (assetsDir != null) {
                java.io.File candidate = new java.io.File(assetsDir, path.replace('/', java.io.File.separatorChar));
                if (candidate.exists()) {
                    String text = Gdx.files.absolute(candidate.getAbsolutePath()).readString();
                    try {
                        LevelState s = json.fromJson(LevelState.class, text);
                        ensureArraysInitialized(s);
                        deduplicateObjects(s);
                        // Try to write into build resources so the packaged/internal copy
                        // reflects the editor changes for runtime tests.
                        try {
                            boolean ok = writeToBuildResources(path, text);
                            if (ok)
                                Gdx.app.log("LevelIO", "Copied workspace level to build resources: " + path);
                        } catch (Exception ex) {
                            Gdx.app.error("LevelIO",
                                    "Failed to copy workspace level to build resources: " + ex.getMessage(), ex);
                        }
                        return s;
                    } catch (Exception parseEx) {
                        Gdx.app.error("LevelIO",
                                "Failed to parse level JSON from workspace asset: " + candidate.getAbsolutePath(),
                                parseEx);
                        try {
                            Gdx.app.error("LevelIO",
                                    "Content preview: " + text.substring(0, Math.min(400, text.length())));
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Exception e) {
            Gdx.app.error("LevelIO", "Error while loading from workspace: " + e.getMessage(), e);
        }
        // Fallback to the regular load (internal-first behavior)
        return load(path);
    }

    /**
     * Attempt to write the given content into a discovered build resources folder.
     * Searches common module build output locations.
     */
    private static boolean writeToBuildResources(String path, String text) {
        try {
            java.io.File buildRes = findBuildResourcesDir();
            if (buildRes == null)
                return false;
            java.io.File out = new java.io.File(buildRes, path.replace('/', java.io.File.separatorChar));
            java.io.File parent = out.getParentFile();
            if (parent != null && !parent.exists())
                parent.mkdirs();
            Gdx.files.absolute(out.getAbsolutePath()).writeString(text, false);
            return true;
        } catch (Exception ex) {
            Gdx.app.error("LevelIO", "Failed writing to build resources: " + ex.getMessage(), ex);
            return false;
        }
    }

    /**
     * Heuristic: find a build/resources/main folder for common modules so the
     * editor can copy files into the build output used by the desktop launcher.
     */
    private static java.io.File findBuildResourcesDir() {
        try {
            java.io.File dir = new java.io.File(System.getProperty("user.dir"));
            int depth = 0;
            while (dir != null && depth < 8) {
                // check common module build locations
                java.io.File[] candidates = new java.io.File[] {
                        new java.io.File(dir, "lwjgl3/build/resources/main"),
                        new java.io.File(dir, "core/build/resources/main"),
                        new java.io.File(dir, "build/resources/main")
                };
                for (java.io.File c : candidates) {
                    if (c.exists() && c.isDirectory())
                        return c;
                }
                dir = dir.getParentFile();
                depth++;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Remove duplicate objects from the level state based on type, position, and dimensions.
     * Objects are considered duplicates if they have the same type and identical properties.
     */
    private static void deduplicateObjects(LevelState state) {
        if (state == null) return;
        
        // Deduplicate walls
        state.walls = deduplicateWalls(state.walls);
        
        // Deduplicate interactables (by type, position, targetId)
        state.interactables = deduplicateInteractables(state.interactables);
        
        // Deduplicate boxes
        state.boxes = deduplicateBoxes(state.boxes);
        
        // Deduplicate orbs
        state.orbs = deduplicateOrbs(state.orbs);
        
        // Deduplicate launchpads
        state.launchpads = deduplicateLaunchpads(state.launchpads);
        
        // Deduplicate lasers (by position and rotation)
        state.lasers = deduplicateLasers(state.lasers);
        
        // Deduplicate mirrors
        state.mirrors = deduplicateMirrors(state.mirrors);
        
        // Deduplicate glasses
        state.glasses = deduplicateGlasses(state.glasses);
        
        // Deduplicate diamonds
        state.diamonds = deduplicateDiamonds(state.diamonds);
        
        // Deduplicate shops
        state.shops = deduplicateShops(state.shops);
        
        // Deduplicate tentacles
        state.tentacles = deduplicateTentacles(state.tentacles);
        
        // Deduplicate triggers
        state.triggers = deduplicateTriggers(state.triggers);
        
        // Deduplicate keys
        state.keys = deduplicateKeys(state.keys);
        
        // Deduplicate locked doors
        state.lockedDoors = deduplicateLockedDoors(state.lockedDoors);
        
        // Deduplicate health potions
        state.healthPotions = deduplicateHealthPotions(state.healthPotions);
    }
    
    private static Array<LevelState.WallData> deduplicateWalls(Array<LevelState.WallData> walls) {
        if (walls == null || walls.size == 0) return walls;
        Array<LevelState.WallData> unique = new Array<>();
        for (LevelState.WallData wall : walls) {
            boolean duplicate = false;
            for (LevelState.WallData existing : unique) {
                if (floatEquals(wall.x, existing.x) && 
                    floatEquals(wall.y, existing.y) &&
                    floatEquals(wall.width, existing.width) &&
                    floatEquals(wall.height, existing.height)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) unique.add(wall);
        }
        if (unique.size < walls.size) {
            Gdx.app.log("LevelIO", "Removed " + (walls.size - unique.size) + " duplicate walls");
        }
        return unique;
    }
    
    private static Array<LevelState.InteractableData> deduplicateInteractables(Array<LevelState.InteractableData> interactables) {
        if (interactables == null || interactables.size == 0) return interactables;
        Array<LevelState.InteractableData> unique = new Array<>();
        for (LevelState.InteractableData obj : interactables) {
            boolean duplicate = false;
            for (LevelState.InteractableData existing : unique) {
                if (stringEquals(obj.type, existing.type) &&
                    stringEquals(obj.id, existing.id) &&
                    floatEquals(obj.x, existing.x) &&
                    floatEquals(obj.y, existing.y) &&
                    stringEquals(obj.targetId, existing.targetId)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) unique.add(obj);
        }
        if (unique.size < interactables.size) {
            Gdx.app.log("LevelIO", "Removed " + (interactables.size - unique.size) + " duplicate interactables");
        }
        return unique;
    }
    
    private static Array<LevelState.BoxData> deduplicateBoxes(Array<LevelState.BoxData> boxes) {
        if (boxes == null || boxes.size == 0) return boxes;
        Array<LevelState.BoxData> unique = new Array<>();
        for (LevelState.BoxData box : boxes) {
            boolean duplicate = false;
            for (LevelState.BoxData existing : unique) {
                if (floatEquals(box.x, existing.x) && 
                    floatEquals(box.y, existing.y) &&
                    stringEquals(box.color, existing.color)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) unique.add(box);
        }
        if (unique.size < boxes.size) {
            Gdx.app.log("LevelIO", "Removed " + (boxes.size - unique.size) + " duplicate boxes");
        }
        return unique;
    }
    
    private static Array<LevelState.OrbData> deduplicateOrbs(Array<LevelState.OrbData> orbs) {
        if (orbs == null || orbs.size == 0) return orbs;
        Array<LevelState.OrbData> unique = new Array<>();
        for (LevelState.OrbData orb : orbs) {
            boolean duplicate = false;
            for (LevelState.OrbData existing : unique) {
                if (floatEquals(orb.x, existing.x) && 
                    floatEquals(orb.y, existing.y)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) unique.add(orb);
        }
        if (unique.size < orbs.size) {
            Gdx.app.log("LevelIO", "Removed " + (orbs.size - unique.size) + " duplicate orbs");
        }
        return unique;
    }
    
    private static Array<LevelState.LaunchpadData> deduplicateLaunchpads(Array<LevelState.LaunchpadData> launchpads) {
        if (launchpads == null || launchpads.size == 0) return launchpads;
        Array<LevelState.LaunchpadData> unique = new Array<>();
        for (LevelState.LaunchpadData pad : launchpads) {
            boolean duplicate = false;
            for (LevelState.LaunchpadData existing : unique) {
                if (floatEquals(pad.x, existing.x) && 
                    floatEquals(pad.y, existing.y) &&
                    stringEquals(pad.direction, existing.direction)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) unique.add(pad);
        }
        if (unique.size < launchpads.size) {
            Gdx.app.log("LevelIO", "Removed " + (launchpads.size - unique.size) + " duplicate launchpads");
        }
        return unique;
    }
    
    private static Array<LevelState.LaserData> deduplicateLasers(Array<LevelState.LaserData> lasers) {
        if (lasers == null || lasers.size == 0) return lasers;
        Array<LevelState.LaserData> unique = new Array<>();
        for (LevelState.LaserData laser : lasers) {
            boolean duplicate = false;
            for (LevelState.LaserData existing : unique) {
                if (stringEquals(laser.id, existing.id) &&
                    floatEquals(laser.x, existing.x) && 
                    floatEquals(laser.y, existing.y) &&
                    floatEquals(laser.rotation, existing.rotation)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) unique.add(laser);
        }
        if (unique.size < lasers.size) {
            Gdx.app.log("LevelIO", "Removed " + (lasers.size - unique.size) + " duplicate lasers");
        }
        return unique;
    }
    
    private static Array<LevelState.MirrorData> deduplicateMirrors(Array<LevelState.MirrorData> mirrors) {
        if (mirrors == null || mirrors.size == 0) return mirrors;
        Array<LevelState.MirrorData> unique = new Array<>();
        for (LevelState.MirrorData mirror : mirrors) {
            boolean duplicate = false;
            for (LevelState.MirrorData existing : unique) {
                if (floatEquals(mirror.x, existing.x) && 
                    floatEquals(mirror.y, existing.y) &&
                    floatEquals(mirror.width, existing.width) &&
                    floatEquals(mirror.height, existing.height) &&
                    floatEquals(mirror.angleDeg, existing.angleDeg)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) unique.add(mirror);
        }
        if (unique.size < mirrors.size) {
            Gdx.app.log("LevelIO", "Removed " + (mirrors.size - unique.size) + " duplicate mirrors");
        }
        return unique;
    }
    
    private static Array<LevelState.GlassData> deduplicateGlasses(Array<LevelState.GlassData> glasses) {
        if (glasses == null || glasses.size == 0) return glasses;
        Array<LevelState.GlassData> unique = new Array<>();
        for (LevelState.GlassData glass : glasses) {
            boolean duplicate = false;
            for (LevelState.GlassData existing : unique) {
                if (floatEquals(glass.x, existing.x) && 
                    floatEquals(glass.y, existing.y) &&
                    floatEquals(glass.width, existing.width) &&
                    floatEquals(glass.height, existing.height)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) unique.add(glass);
        }
        if (unique.size < glasses.size) {
            Gdx.app.log("LevelIO", "Removed " + (glasses.size - unique.size) + " duplicate glasses");
        }
        return unique;
    }
    
    private static Array<LevelState.DiamondData> deduplicateDiamonds(Array<LevelState.DiamondData> diamonds) {
        if (diamonds == null || diamonds.size == 0) return diamonds;
        Array<LevelState.DiamondData> unique = new Array<>();
        for (LevelState.DiamondData diamond : diamonds) {
            boolean duplicate = false;
            for (LevelState.DiamondData existing : unique) {
                if (floatEquals(diamond.x, existing.x) && 
                    floatEquals(diamond.y, existing.y)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) unique.add(diamond);
        }
        if (unique.size < diamonds.size) {
            Gdx.app.log("LevelIO", "Removed " + (diamonds.size - unique.size) + " duplicate diamonds");
        }
        return unique;
    }
    
    private static Array<LevelState.ShopData> deduplicateShops(Array<LevelState.ShopData> shops) {
        if (shops == null || shops.size == 0) return shops;
        Array<LevelState.ShopData> unique = new Array<>();
        for (LevelState.ShopData shop : shops) {
            boolean duplicate = false;
            for (LevelState.ShopData existing : unique) {
                if (floatEquals(shop.x, existing.x) && 
                    floatEquals(shop.y, existing.y)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) unique.add(shop);
        }
        if (unique.size < shops.size) {
            Gdx.app.log("LevelIO", "Removed " + (shops.size - unique.size) + " duplicate shops");
        }
        return unique;
    }
    
    private static Array<LevelState.TentacleData> deduplicateTentacles(Array<LevelState.TentacleData> tentacles) {
        if (tentacles == null || tentacles.size == 0) return tentacles;
        Array<LevelState.TentacleData> unique = new Array<>();
        for (LevelState.TentacleData tent : tentacles) {
            boolean duplicate = false;
            for (LevelState.TentacleData existing : unique) {
                if (floatEquals(tent.x, existing.x) && 
                    floatEquals(tent.y, existing.y)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) unique.add(tent);
        }
        if (unique.size < tentacles.size) {
            Gdx.app.log("LevelIO", "Removed " + (tentacles.size - unique.size) + " duplicate tentacles");
        }
        return unique;
    }
    
    private static Array<LevelState.TriggerData> deduplicateTriggers(Array<LevelState.TriggerData> triggers) {
        if (triggers == null || triggers.size == 0) return triggers;
        Array<LevelState.TriggerData> unique = new Array<>();
        for (LevelState.TriggerData trigger : triggers) {
            boolean duplicate = false;
            for (LevelState.TriggerData existing : unique) {
                if (stringEquals(trigger.id, existing.id) &&
                    floatEquals(trigger.x, existing.x) && 
                    floatEquals(trigger.y, existing.y)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) unique.add(trigger);
        }
        if (unique.size < triggers.size) {
            Gdx.app.log("LevelIO", "Removed " + (triggers.size - unique.size) + " duplicate triggers");
        }
        return unique;
    }
    
    private static Array<LevelState.KeyData> deduplicateKeys(Array<LevelState.KeyData> keys) {
        if (keys == null || keys.size == 0) return keys;
        Array<LevelState.KeyData> unique = new Array<>();
        for (LevelState.KeyData key : keys) {
            boolean duplicate = false;
            for (LevelState.KeyData existing : unique) {
                if (floatEquals(key.x, existing.x) && 
                    floatEquals(key.y, existing.y)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) unique.add(key);
        }
        if (unique.size < keys.size) {
            Gdx.app.log("LevelIO", "Removed " + (keys.size - unique.size) + " duplicate keys");
        }
        return unique;
    }
    
    private static Array<LevelState.LockedDoorData> deduplicateLockedDoors(Array<LevelState.LockedDoorData> doors) {
        if (doors == null || doors.size == 0) return doors;
        Array<LevelState.LockedDoorData> unique = new Array<>();
        for (LevelState.LockedDoorData door : doors) {
            boolean duplicate = false;
            for (LevelState.LockedDoorData existing : unique) {
                if (floatEquals(door.x, existing.x) && 
                    floatEquals(door.y, existing.y) &&
                    stringEquals(door.orientation, existing.orientation)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) unique.add(door);
        }
        if (unique.size < doors.size) {
            Gdx.app.log("LevelIO", "Removed " + (doors.size - unique.size) + " duplicate locked doors");
        }
        return unique;
    }
    
    private static Array<LevelState.HealthPotionData> deduplicateHealthPotions(Array<LevelState.HealthPotionData> potions) {
        if (potions == null || potions.size == 0) return potions;
        Array<LevelState.HealthPotionData> unique = new Array<>();
        for (LevelState.HealthPotionData potion : potions) {
            boolean duplicate = false;
            for (LevelState.HealthPotionData existing : unique) {
                if (floatEquals(potion.x, existing.x) && 
                    floatEquals(potion.y, existing.y)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) unique.add(potion);
        }
        if (unique.size < potions.size) {
            Gdx.app.log("LevelIO", "Removed " + (potions.size - unique.size) + " duplicate health potions");
        }
        return unique;
    }
    
    /**
     * Compare floats with epsilon tolerance
     */
    private static boolean floatEquals(float a, float b) {
        return Math.abs(a - b) < 0.5f; // 0.5px tolerance for editor snapping
    }
    
    /**
     * Null-safe string equality check
     */
    private static boolean stringEquals(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
    
    /**
     * Ensure all array fields in the level state are initialized to prevent null pointer exceptions.
     */
    private static void ensureArraysInitialized(LevelState s) {
        if (s.walls == null) s.walls = new Array<>();
        if (s.interactables == null) s.interactables = new Array<>();
        if (s.boxes == null) s.boxes = new Array<>();
        if (s.orbs == null) s.orbs = new Array<>();
        if (s.launchpads == null) s.launchpads = new Array<>();
        if (s.lasers == null) s.lasers = new Array<>();
        if (s.mirrors == null) s.mirrors = new Array<>();
        if (s.glasses == null) s.glasses = new Array<>();
        if (s.diamonds == null) s.diamonds = new Array<>();
        if (s.shops == null) s.shops = new Array<>();
        if (s.tentacles == null) s.tentacles = new Array<>();
        if (s.triggers == null) s.triggers = new Array<>();
        // New collectible/interactable arrays
        if (s.keys == null) s.keys = new Array<>();
        if (s.lockedDoors == null) s.lockedDoors = new Array<>();
        if (s.healthPotions == null) s.healthPotions = new Array<>();
    }

    /**
     * Try to discover the project's assets directory by walking up from the current
     * working
     * directory and looking for a folder named `assets` that also contains an
     * `assets.txt` or
     * `levels` subfolder. Returns null if not found.
     */
    private static java.io.File findProjectAssetsDir() {
        try {
            java.io.File dir = new java.io.File(System.getProperty("user.dir"));
            int depth = 0;
            while (dir != null && depth < 8) {
                java.io.File assets = new java.io.File(dir, "assets");
                if (assets.exists() && assets.isDirectory()) {
                    // heuristics: assets.txt or levels folder present
                    if (new java.io.File(assets, "assets.txt").exists()
                            || new java.io.File(assets, "levels").exists()) {
                        return assets;
                    }
                }
                dir = dir.getParentFile();
                depth++;
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
