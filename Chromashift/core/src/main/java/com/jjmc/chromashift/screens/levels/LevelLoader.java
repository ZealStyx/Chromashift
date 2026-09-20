package com.jjmc.chromashift.screens.levels;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.math.Rectangle;
import com.jjmc.chromashift.environment.Solid;
import com.jjmc.chromashift.environment.Wall;
import com.jjmc.chromashift.environment.interactable.*;
import com.jjmc.chromashift.entity.boss.Boss;
import com.jjmc.chromashift.entity.boss.FinalBoss;
import com.jjmc.chromashift.entity.boss.BossGuardian;

import java.util.HashMap;
import java.util.Map;

/**
 * Unified level loader that converts LevelIO.LevelState into runtime objects
 * (walls,
 * solids, interactables, boss, spawn). Scenes and editors should use this to
 * ensure
 * consistent placement and linking semantics across the project.
 */
public final class LevelLoader {

    private LevelLoader() {
    }

    public static class Result {
        public final Array<Wall> walls = new Array<>();
        public final Array<Solid> solids = new Array<>();
        public final Array<Interactable> interactables = new Array<>();
        public final Array<com.jjmc.chromashift.environment.collectible.Collectible> collectibles = new Array<>();
        // Shop position data (shops need Player and Stage, so they're instantiated by
        // screens)
        public final Array<LevelIO.LevelState.ShopData> shopDataList = new Array<>();
        // Tentacle instances
        public final Array<com.jjmc.chromashift.environment.enemy.Tentacle> tentacles = new Array<>();
        public Boss boss; // optional - can be FinalBoss or BossGuardian
        public float spawnX;
        public float spawnY;
        // Door id -> instance for linking
        public final Map<String, Door> doorMap = new HashMap<>();
        // Button/Lever maps for control wiring
        public final Map<String, Button> buttonMap = new HashMap<>();
        public final Map<String, Lever> leverMap = new HashMap<>();
        // Laser id -> runtime Laser or LaserRay instance (as Interactable)
        public final Map<String, Interactable> laserMap = new HashMap<>();
        // Mirror id -> instance for linking
        public final Map<String, Mirror> mirrorMap = new HashMap<>();
    }

    public enum LoadMode {
        ORIGINAL,           // Always load original level JSON, ignore saves
        SAVED_IF_EXISTS,    // Load saved level if exists, otherwise original
        FORCE_SAVED         // Only load saved level (fail if not found)
    }

    /** Load using packaged/internal-first assets with default mode (apply saves if exist). */
    public static Result load(String path) {
        return load(path, LoadMode.SAVED_IF_EXISTS);
    }
    
    /** Load using packaged/internal-first assets with specified mode. */
    public static Result load(String path, LoadMode mode) {
        LevelIO.LevelState state = LevelIO.load(path);
        Result result = build(state);
        // Apply saved level overrides based on mode
        if (mode == LoadMode.SAVED_IF_EXISTS) {
            GameLevelSave.applyOverridesIfPresent(path, result);
        } else if (mode == LoadMode.FORCE_SAVED) {
            if (!GameLevelSave.applyOverridesIfPresent(path, result)) {
                throw new RuntimeException("Saved level not found: " + path);
            }
        }
        // ORIGINAL mode: skip applying saves
        return result;
    }

    /**
     * Load preferring workspace assets and copying into build output (for editors).
     */
    public static Result loadFromWorkspace(String path) {
        return loadFromWorkspace(path, LoadMode.SAVED_IF_EXISTS);
    }
    
    /**
     * Load preferring workspace assets with specified mode.
     */
    public static Result loadFromWorkspace(String path, LoadMode mode) {
        LevelIO.LevelState state = LevelIO.loadFromWorkspaceThenCopyToBuild(path);
        Result result = build(state);
        // Apply saved level overrides based on mode
        if (mode == LoadMode.SAVED_IF_EXISTS) {
            GameLevelSave.applyOverridesIfPresent(path, result);
        } else if (mode == LoadMode.FORCE_SAVED) {
            if (!GameLevelSave.applyOverridesIfPresent(path, result)) {
                throw new RuntimeException("Saved level not found: " + path);
            }
        }
        // ORIGINAL mode: skip applying saves
        return result;
    }

    /** Build runtime objects from a LevelState. */
    public static Result build(LevelIO.LevelState state) {
        Result out = new Result();
        if (state == null)
            return out;

        java.util.ArrayList<Portal> portals = new java.util.ArrayList<>();

        // Walls first (these are the base Solids)
        if (state.walls != null) {
            for (LevelIO.LevelState.WallData wd : state.walls) {
                Wall w = new Wall(wd.x, wd.y, (int) wd.width, (int) wd.height);
                out.walls.add(w);
                out.solids.add(w);
            }
        }

        // Create doors first so other interactables can link to them
        if (state.interactables != null) {
            for (LevelIO.LevelState.InteractableData idd : state.interactables) {
                if (idd == null || idd.type == null)
                    continue;
                String t = idd.type.trim().toLowerCase();
                if (!"door".equals(t))
                    continue;

                Door.OpenDirection dir = Door.OpenDirection.UP;
                try {
                    if (idd.openDirection != null)
                        dir = Door.OpenDirection.valueOf(idd.openDirection.toUpperCase());
                } catch (Exception ignored) {
                }

                int cols = Math.max(1, idd.cols);
                int rows = Math.max(1, idd.rows);

                float os = 3f, cs = 3f;
                try {
                    os = (idd.openSpeed > 0f) ? idd.openSpeed : 3f;
                } catch (Exception ignored) {
                }
                try {
                    cs = (idd.closeSpeed > 0f) ? idd.closeSpeed : 3f;
                } catch (Exception ignored) {
                }
                // Use exact saved position - new constructor honors x,y directly
                Door d = new Door(idd.x, idd.y, cols, rows, dir, os, cs);

                out.interactables.add(d);
                out.solids.add(d);
                if (idd.id != null)
                    out.doorMap.put(idd.id, d);
            }
        }

        // Mirrors (create early so Buttons/Levers can link to them)
        if (state.mirrors != null) {
            for (LevelIO.LevelState.MirrorData md : state.mirrors) {
                com.jjmc.chromashift.environment.interactable.Mirror mirror = new com.jjmc.chromashift.environment.interactable.Mirror(
                        md.x, md.y, md.width, md.height);
                try {
                    mirror.setAngleDegrees(md.angleDeg);
                } catch (Throwable ignored) {
                }
                out.interactables.add(mirror);
                if (md.id != null && !md.id.isEmpty())
                    out.mirrorMap.put(md.id, mirror);
            }
        }

        // Other interactables (Buttons, Levers) possibly linking to doors, lasers,
        // mirrors
        if (state.interactables != null) {
            for (LevelIO.LevelState.InteractableData idd : state.interactables) {
                if (idd == null || idd.type == null)
                    continue;
                String t = idd.type.trim().toLowerCase();
                if ("door".equals(t))
                    continue; // already handled

                switch (t) {
                    case "button" -> {
                        // Support multiple target ids comma-separated (doors, lasers, mirrors)
                        Array<Door> targets = new Array<>();
                        Array<Interactable> otherTargets = new Array<>();
                        if (idd.targetId != null && !idd.targetId.isEmpty()) {
                            String[] parts = idd.targetId.split(",");
                            for (String p : parts) {
                                String key = p.trim();
                                Door dd = out.doorMap.get(key);
                                if (dd != null)
                                    targets.add(dd);
                                else {
                                    Interactable li = out.laserMap.get(key);
                                    if (li != null)
                                        otherTargets.add(li);
                                    else {
                                        Mirror mi = out.mirrorMap.get(key);
                                        if (mi != null)
                                            otherTargets.add(mi);
                                    }
                                }
                            }
                        }
                        Button.ButtonColor col = Button.ButtonColor.GREEN;
                        try {
                            if (idd.color != null)
                                col = Button.ButtonColor.valueOf(idd.color.toUpperCase());
                        } catch (Exception ignored) {
                        }
                        Solid base = findBaseSolidFor(idd.x, idd.y, out.solids);
                        // Fallback hierarchy for base solid:
                        // 1. Nearest existing wall/solid under X.
                        // 2. Any existing wall (first).
                        // 3. Dummy single-cell wall directly beneath button so constructor logic does
                        // not NPE.
                        if (base == null) {
                            if (out.walls.size > 0) {
                                base = out.walls.first();
                            } else {
                                // Create a lightweight dummy wall one cell below intended button Y
                                float cellX = (float) Math.floor(idd.x / 32f) * 32f;
                                float cellY = (float) Math.floor(idd.y / 32f) * 32f - 32f; // one cell below
                                Wall dummy = new Wall(cellX, cellY, 1, 1);
                                base = dummy; // do not add to out.solids to avoid unintended collisions
                            }
                        }
                        Button b = new Button(idd.x, base, targets, col);
                        if (idd.id != null && !idd.id.isEmpty()) b.setId(idd.id);
                        out.interactables.add(b);
                        out.solids.add(b);
                        // Map button id to runtime instance if id present
                        if (idd.id != null && !idd.id.isEmpty()) {
                            out.buttonMap.put(idd.id, b);
                        }
                        // Attach any non-door interactable targets (e.g., LaserRay emitters)
                        if (otherTargets.size > 0) {
                            for (Interactable it : otherTargets)
                                b.addLinkedInteractable(it);
                        }
                    }
                    case "lever" -> {
                        // Support multiple target ids comma-separated (doors, lasers, mirrors)
                        Array<Door> targets = new Array<>();
                        Array<Interactable> otherTargets = new Array<>();
                        if (idd.targetId != null && !idd.targetId.isEmpty()) {
                            String[] parts = idd.targetId.split(",");
                            for (String p : parts) {
                                String key = p.trim();
                                Door dd = out.doorMap.get(key);
                                if (dd != null)
                                    targets.add(dd);
                                else {
                                    Interactable li = out.laserMap.get(key);
                                    if (li != null)
                                        otherTargets.add(li);
                                    else {
                                        Mirror mi = out.mirrorMap.get(key);
                                        if (mi != null)
                                            otherTargets.add(mi);
                                    }
                                }
                            }
                        }
                        boolean horizontal = false;
                        try {
                            if (idd.orientation != null)
                                horizontal = "HORIZONTAL".equalsIgnoreCase(idd.orientation);
                        } catch (Exception ignored) {
                        }
                        float lx = idd.x;
                        float ly = idd.y;
                        // If missing Y, place on top of nearest base solid
                        if (ly == 0f) {
                            Solid base = findBaseSolidFor(idd.x, idd.y, out.solids);
                            if (base != null)
                                ly = base.getBounds().y + base.getBounds().height;
                        }
                        Lever l = new Lever(lx, ly, 16, 36, horizontal, null);
                        if (idd.id != null && !idd.id.isEmpty()) l.setId(idd.id);
                        for (Door dd : targets)
                            l.setTarget(dd);
                        for (Interactable it : otherTargets)
                            l.setTarget(it);
                        out.interactables.add(l);
                        // Map lever id to runtime instance if id present
                        if (idd.id != null && !idd.id.isEmpty()) {
                            out.leverMap.put(idd.id, l);
                        }
                    }
                    case "portal" -> {
                        Portal portal = new Portal(idd.x, idd.y);
                        portal.setLinkedLeverIds(idd.lever1Id, idd.lever2Id);
                        // Ensure missing links are treated as active from the start
                        portal.setLeverStates(idd.lever1Id == null || idd.lever1Id.isEmpty(),
                                idd.lever2Id == null || idd.lever2Id.isEmpty());
                        if (idd.portalState != null) {
                            try {
                                portal.setState(Portal.PortalState.valueOf(idd.portalState));
                            } catch (Exception ignored) {}
                        }
                        out.interactables.add(portal);
                        portals.add(portal);
                    }
                    case "target" -> {
                        // Support multiple target ids comma-separated (doors, lasers, mirrors)
                        Array<Door> targets = new Array<>();
                        Array<Interactable> otherTargets = new Array<>();
                        if (idd.targetId != null && !idd.targetId.isEmpty()) {
                            String[] parts = idd.targetId.split(",");
                            for (String p : parts) {
                                String key = p.trim();
                                Door dd = out.doorMap.get(key);
                                if (dd != null)
                                    targets.add(dd);
                                else {
                                    Interactable li = out.laserMap.get(key);
                                    if (li != null)
                                        otherTargets.add(li);
                                    else {
                                        Mirror mi = out.mirrorMap.get(key);
                                        if (mi != null)
                                            otherTargets.add(mi);
                                    }
                                }
                            }
                        }
                        Button.ButtonColor col = Button.ButtonColor.RED;
                        try {
                            if (idd.color != null)
                                col = Button.ButtonColor.valueOf(idd.color.toUpperCase());
                        } catch (Exception ignored) {
                        }

                        Target tObj = new Target(idd.x, idd.y, col);
                        for (Door dd : targets)
                            tObj.addLinkedDoor(dd);
                        for (Interactable it : otherTargets)
                            tObj.addLinkedInteractable(it);

                        out.interactables.add(tObj);
                        out.solids.add(tObj);
                    }
                    default -> {
                    }
                }
            }
        }

        // Boxes
        if (state.boxes != null) {
            for (LevelIO.LevelState.BoxData bd : state.boxes) {
                Box box = new Box(bd.x, bd.y, out.solids);
                // apply saved color if present
                if (bd.color != null) {
                    com.badlogic.gdx.graphics.Color base = com.badlogic.gdx.graphics.Color.CYAN;
                    switch (bd.color.toUpperCase()) {
                        case "RED" -> base = com.badlogic.gdx.graphics.Color.RED;
                        case "BLUE" -> base = com.badlogic.gdx.graphics.Color.BLUE;
                        case "GREEN" -> base = com.badlogic.gdx.graphics.Color.GREEN;
                        case "YELLOW" -> base = com.badlogic.gdx.graphics.Color.YELLOW;
                        case "PURPLE" -> base = com.badlogic.gdx.graphics.Color.PURPLE;
                        default -> base = com.badlogic.gdx.graphics.Color.CYAN;
                    }
                    try {
                        box.setColor(base);
                    } catch (Throwable ignored) {
                    }
                }
                out.interactables.add(box);
                // Apply respawn area if dimensions present
                float aw = (bd.areaW > 0f) ? bd.areaW : 1600f;
                float ah = (bd.areaH > 0f) ? bd.areaH : 1200f;
                try {
                    box.setRespawnArea(new com.badlogic.gdx.math.Rectangle(bd.x - aw / 2f, bd.y - ah / 2f, aw, ah));
                } catch (Throwable ignored) {
                }
            }
        }
        // Orbs
        if (state.orbs != null) {
            for (LevelIO.LevelState.OrbData od : state.orbs) {
                Orb orb = new Orb(od.x, od.y, out.solids);
                // Respect saved bounce-enabled flag (default true if absent)
                try {
                    orb.setBounceEnabled(od.bouncy);
                } catch (Throwable ignored) {
                }
                // Apply respawn area
                float aw = (od.areaW > 0f) ? od.areaW : 1600f;
                float ah = (od.areaH > 0f) ? od.areaH : 1200f;
                try {
                    orb.setRespawnArea(new com.badlogic.gdx.math.Rectangle(od.x - aw / 2f, od.y - ah / 2f, aw, ah));
                } catch (Throwable ignored) {
                }
                out.interactables.add(orb);
            }
        }

        // Launchpads
        if (state.launchpads != null) {
            for (LevelIO.LevelState.LaunchpadData lpd : state.launchpads) {
                com.jjmc.chromashift.environment.Launchpad.LaunchDirection dir = com.jjmc.chromashift.environment.Launchpad.LaunchDirection.UP;
                try {
                    if (lpd.direction != null) {
                        dir = com.jjmc.chromashift.environment.Launchpad.LaunchDirection.valueOf(
                                lpd.direction.toUpperCase());
                    }
                } catch (Exception ignored) {
                }

                float speed = (lpd.speed > 0f) ? lpd.speed : 600f;
                com.jjmc.chromashift.environment.Launchpad launchpad = new com.jjmc.chromashift.environment.Launchpad(
                        lpd.x, lpd.y, dir, speed);
                out.interactables.add(launchpad);
                out.solids.add(launchpad);
            }
        }

        // Triggers (non-blocking zones, color-coded, identified by id)
        if (state.triggers != null) {
            for (LevelIO.LevelState.TriggerData td : state.triggers) {
                com.badlogic.gdx.graphics.Color color = com.badlogic.gdx.graphics.Color.RED;
                if (td.color != null) {
                    try {
                        color = com.badlogic.gdx.graphics.Color.valueOf(td.color.toUpperCase());
                    } catch (Exception ignored) {}
                }

                float w = td.width > 0f ? td.width : 64f;
                float h = td.height > 0f ? td.height : 64f;
                String triggerId = (td.id != null && !td.id.isEmpty()) ? td.id : ("trigger_" + (state.triggers.indexOf(td, true) + 1));
                com.jjmc.chromashift.environment.TriggerZone trig = new com.jjmc.chromashift.environment.TriggerZone(td.x, td.y, w, h, triggerId, color);
                out.interactables.add(trig);
            }
        }

        // Lasers (create first; we'll wire references after all interactables are
        // collected)
        Array<com.jjmc.chromashift.environment.interactable.Laser> lasersTemp = new Array<>();
        if (state.lasers != null) {
            for (LevelIO.LevelState.LaserData ld : state.lasers) {
                if (ld.rotating) {
                    // Create a player-rotatable LaserRay interactable at bottom-left (bounds 32x32)
                    com.jjmc.chromashift.environment.interactable.LaserRay lray = new com.jjmc.chromashift.environment.interactable.LaserRay(
                            ld.x, ld.y, true);
                    lray.setRotation(ld.rotation);
                    lray.setMaxBounces(ld.maxBounces);
                    out.interactables.add(lray);
                    if (ld.id != null && !ld.id.isEmpty())
                        out.laserMap.put(ld.id, lray);
                    // We still use lasersTemp list to later wire mirrors/glasses/solids uniformly
                    // by adapting common setter names on LaserRay as well.
                    // Wrap via interface type compatibility by overloading list type if needed.
                } else {
                    com.jjmc.chromashift.environment.interactable.Laser laser = new com.jjmc.chromashift.environment.interactable.Laser(
                            ld.x, ld.y);
                    laser.setRotation(ld.rotation);
                    laser.setMaxBounces(ld.maxBounces);
                    out.interactables.add(laser);
                    if (ld.id != null && !ld.id.isEmpty())
                        out.laserMap.put(ld.id, laser);
                    lasersTemp.add(laser);
                }
            }
        }

        // Glasses
        if (state.glasses != null) {
            for (LevelIO.LevelState.GlassData gd : state.glasses) {
                com.badlogic.gdx.graphics.Color base = com.badlogic.gdx.graphics.Color.CYAN;
                if (gd.color != null) {
                    switch (gd.color.toUpperCase()) {
                        case "RED" -> base = com.badlogic.gdx.graphics.Color.RED;
                        case "BLUE" -> base = com.badlogic.gdx.graphics.Color.BLUE;
                        case "GREEN" -> base = com.badlogic.gdx.graphics.Color.GREEN;
                        case "YELLOW" -> base = com.badlogic.gdx.graphics.Color.YELLOW;
                        case "PURPLE" -> base = com.badlogic.gdx.graphics.Color.PURPLE;
                        default -> base = com.badlogic.gdx.graphics.Color.CYAN;
                    }
                }
                com.jjmc.chromashift.environment.interactable.Glass glass = new com.jjmc.chromashift.environment.interactable.Glass(
                        gd.x, gd.y, gd.width, gd.height,
                        base, true, 1f, gd.rainbow);
                glass.setSpeed(gd.speed);
                out.interactables.add(glass);
            }
        }

        // Wire mirrors, glasses, and solids into lasers, and wire control inputs
        {
            java.util.ArrayList<com.jjmc.chromashift.environment.interactable.Mirror> mlist = new java.util.ArrayList<>();
            java.util.ArrayList<com.jjmc.chromashift.environment.interactable.Glass> glist = new java.util.ArrayList<>();
            java.util.ArrayList<com.jjmc.chromashift.environment.Solid> slist = new java.util.ArrayList<>();
            // collect current mirrors and glasses from interactables
            for (int i = 0; i < out.interactables.size; i++) {
                Interactable it = out.interactables.get(i);
                if (it instanceof com.jjmc.chromashift.environment.interactable.Mirror mi)
                    mlist.add(mi);
                if (it instanceof com.jjmc.chromashift.environment.interactable.Glass gi)
                    glist.add(gi);
            }
            for (int i = 0; i < out.solids.size; i++) {
                slist.add(out.solids.get(i));
            }
            // Also include any Box instances from interactables so lasers can consider them
            // as blocking solids (boxes are pickable interactables but may still block
            // beams
            // when their color matches the beam). We avoid adding other interactables.
            for (int i = 0; i < out.interactables.size; i++) {
                Interactable it = out.interactables.get(i);
                if (it instanceof com.jjmc.chromashift.environment.interactable.Box bx) {
                    slist.add(bx);
                }
            }
            // Wire for Lasers in lasersTemp (mirrors/glasses/solids only)
            for (int i = 0; i < lasersTemp.size; i++) {
                com.jjmc.chromashift.environment.interactable.Laser L = lasersTemp.get(i);
                L.setMirrors(mlist);
                L.setGlasses(glist);
                L.setSolids(slist);
            }
            // Also wire any LaserRay interactables present in out.interactables
            for (int i = 0; i < out.interactables.size; i++) {
                Interactable it = out.interactables.get(i);
                if (it instanceof com.jjmc.chromashift.environment.interactable.LaserRay lr) {
                    lr.setMirrors(mlist);
                    lr.setGlasses(glist);
                    lr.setSolids(slist);
                }
            }
        }

        // Wire levers/buttons to portals after all maps are populated
        for (Portal p : portals) {
            String l1 = p.getRequiredLeverId1();
            String l2 = p.getRequiredLeverId2();
            if (l1 != null) {
                Lever lev = out.leverMap.get(l1);
                if (lev != null) {
                    lev.setOnToggle(() -> p.setLeverActive(l1, lev.isOn()));
                    p.setLeverActive(l1, lev.isOn());
                }
                Button btn = out.buttonMap.get(l1);
                if (btn != null) {
                    btn.addPressListener((id, pressed) -> p.setLeverActive(id, pressed));
                    p.setLeverActive(l1, btn.isPressed());
                }
            }
            if (l2 != null) {
                Lever lev = out.leverMap.get(l2);
                if (lev != null) {
                    lev.setOnToggle(() -> p.setLeverActive(l2, lev.isOn()));
                    p.setLeverActive(l2, lev.isOn());
                }
                Button btn = out.buttonMap.get(l2);
                if (btn != null) {
                    btn.addPressListener((id, pressed) -> p.setLeverActive(id, pressed));
                    p.setLeverActive(l2, btn.isPressed());
                }
            }
        }

        // Diamonds (collectibles)
        if (state.diamonds != null) {
            for (LevelIO.LevelState.DiamondData dd : state.diamonds) {
                com.jjmc.chromashift.environment.collectible.Diamond diamond = new com.jjmc.chromashift.environment.collectible.Diamond(
                        dd.x, dd.y);
                out.collectibles.add(diamond);
            }
        }

        // Keys (collectibles)
        if (state.keys != null) {
            for (LevelIO.LevelState.KeyData kd : state.keys) {
                com.jjmc.chromashift.environment.collectible.Key key = new com.jjmc.chromashift.environment.collectible.Key(
                        kd.x, kd.y);
                out.collectibles.add(key);
            }
        }

        // Health Potions (collectibles)
        if (state.healthPotions != null) {
            for (LevelIO.LevelState.HealthPotionData hpd : state.healthPotions) {
                com.jjmc.chromashift.environment.collectible.HealthPotion potion = 
                        new com.jjmc.chromashift.environment.collectible.HealthPotion(hpd.x, hpd.y);
                out.collectibles.add(potion);
            }
        }

        // Locked Doors (interactables)
        if (state.lockedDoors != null) {
            for (LevelIO.LevelState.LockedDoorData ld : state.lockedDoors) {
                com.jjmc.chromashift.environment.interactable.LockedDoor.Orientation orient =
                        "HORIZONTAL".equalsIgnoreCase(ld.orientation)
                                ? com.jjmc.chromashift.environment.interactable.LockedDoor.Orientation.HORIZONTAL
                                : com.jjmc.chromashift.environment.interactable.LockedDoor.Orientation.VERTICAL;
                com.jjmc.chromashift.environment.interactable.LockedDoor door =
                        new com.jjmc.chromashift.environment.interactable.LockedDoor(ld.x, ld.y, orient);
                out.interactables.add(door);
                out.solids.add(door); // treat as solid until opened
            }
        }

        // Shops (interactables) - Store data for screen-level instantiation
        // Shop requires Player and Stage references, so screens must create them
        if (state.shops != null) {
            for (LevelIO.LevelState.ShopData sd : state.shops) {
                out.shopDataList.add(sd);
            }
        }

        // Tentacles
        if (state.tentacles != null) {
            for (LevelIO.LevelState.TentacleData td : state.tentacles) {
                com.jjmc.chromashift.environment.enemy.Tentacle tentacle = new com.jjmc.chromashift.environment.enemy.Tentacle(
                        td.x, td.y, td.segments);
                out.tentacles.add(tentacle);
            }
        }

        // Boss
        if (state.boss != null) {
            Boss b;
            if (state.boss.guardian) {
                BossGuardian g = new BossGuardian();
                g.setPosition(state.boss.x, state.boss.y);
                g.setEnvironment(out.solids, out.walls);
                b = g;
            } else {
                FinalBoss fb = new FinalBoss();
                fb.setPosition(state.boss.x, state.boss.y);
                fb.setEnvironment(out.solids, out.walls);
                b = fb;
            }
            out.boss = b;
        }

        // Spawn
        if (state.spawn != null) {
            out.spawnX = state.spawn.x;
            out.spawnY = state.spawn.y;
        }

        return out;
    }

    // (Removed anchoring helpers; doors no longer anchor to walls.)

    private static Solid findBaseSolidFor(float x, float y, Array<Solid> solids) {
        if (solids == null || solids.size == 0)
            return null;
        Solid best = null;
        float bestTopY = -Float.MAX_VALUE;
        for (Solid s : solids) {
            Rectangle r = s.getBounds();
            if (r == null)
                continue;
            boolean withinX = x >= r.x - 1e-3f && x <= r.x + r.width + 1e-3f;
            float top = r.y + r.height;
            // choose the solid with top just below (or at) y, or simply the highest under x
            // if y is 0
            boolean below = (y == 0f) ? true : top <= y + 1e-3f;
            if (withinX && below && top > bestTopY) {
                bestTopY = top;
                best = s;
            }
        }
        return best;
    }
}
