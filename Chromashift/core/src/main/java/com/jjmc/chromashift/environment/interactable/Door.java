package com.jjmc.chromashift.environment.interactable;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;
import com.jjmc.chromashift.environment.Solid;
import com.jjmc.chromashift.environment.Wall;

public class Door implements Interactable, Solid {
    private final Rectangle bounds;
    private boolean open;
    private float openProgress; // For smooth animation (0 = closed, 1 = open)
    private float openSpeed = 3f; // units per second
    private float closeSpeed = 3f; // units per second

    public enum OpenDirection {
        UP,    // opens bottom -> top
        DOWN,  // opens top -> bottom
        LEFT,  // opens right -> left (visible shrinks from left side)
        RIGHT  // opens left -> right (visible shrinks from right side)
    }

    private OpenDirection openDirection = OpenDirection.UP;

    // Sprite resources: door.png is 32x64 (1 col, 2 rows). Row 0 = vertical, Row 1 = horizontal
    private static Texture doorTexture;
    private final TextureRegion vertRegion;
    private final TextureRegion horizRegion;
    private final TextureRegion cachedPartial = new TextureRegion();

    // Direct position constructor - honors exact saved position
    public Door(float x, float y, int cols, int rows, OpenDirection dir, float openSpeed, float closeSpeed) {
        float width = cols * 32f;
        float height = rows * 32f;
        bounds = new Rectangle(x, y, width, height);
        this.openDirection = dir != null ? dir : OpenDirection.UP;
        this.openSpeed = openSpeed > 0 ? openSpeed : 3f;
        this.closeSpeed = closeSpeed > 0 ? closeSpeed : 3f;
        TextureRegion[][] tiles = loadDoorTiles();
        this.vertRegion = tiles[0][0];
        this.horizRegion = tiles[1][0];
    }

    public Door(float x, Solid baseSolid, int cols, int rows) {
        // For backward compat: if baseSolid is actually a dummy positioned at saved Y, use its intended position
        // The LevelLoader now passes exact x,y, but old code used baseSolid top. We detect if x is already absolute.
        Rectangle baseRect = baseSolid != null ? baseSolid.getBounds() : new Rectangle(x, 0, 32, 32);
        float y = baseRect.y + baseRect.height;
        // If baseSolid is a dummy 1x1 wall created at idd.y-32, y will be idd.y-31. Correct to idd.y if close.
        // Better: if caller passed x as absolute and baseSolid is dummy, we should honor x as is and use saved y if available.
        // For now, if baseSolid height is 1 (dummy), treat x as absolute and y as from baseSolid + offset
        float width = cols * 32f;
        float height = rows * 32f;
        // Heuristic: if baseSolid is tiny (dummy), use x directly and y = baseRect.y + baseRect.height
        // which should be close to saved y. The new direct constructor is preferred.
        bounds = new Rectangle(x, y, width, height);
        this.openDirection = OpenDirection.UP;
        this.openSpeed = 3f;
        this.closeSpeed = 3f;
        TextureRegion[][] tiles = loadDoorTiles();
        this.vertRegion = tiles[0][0];
        this.horizRegion = tiles[1][0];
    }

    public Door(float x, Solid baseSolid, int cols, int rows, boolean openUpwards, float openSpeed, float closeSpeed) {
        // backwards-compatible constructor: boolean -> OpenDirection
        Rectangle baseRect = baseSolid.getBounds();
        float y = baseRect.y + baseRect.height;
        float width = cols * 32f;
        float height = rows * 32f;
        bounds = new Rectangle(x, y, width, height);
        this.openDirection = openUpwards ? OpenDirection.UP : OpenDirection.DOWN;
        this.openSpeed = openSpeed;
        this.closeSpeed = closeSpeed;
        TextureRegion[][] tiles = loadDoorTiles();
        this.vertRegion = tiles[0][0];
        this.horizRegion = tiles[1][0];
    }

    public Door(float x, Solid baseSolid, int cols, int rows, OpenDirection dir, float openSpeed, float closeSpeed) {
        Rectangle baseRect = baseSolid.getBounds();
        float y = baseRect.y + baseRect.height;
        float width = cols * 32f;
        float height = rows * 32f;
        bounds = new Rectangle(x, y, width, height);
        this.openDirection = dir;
        this.openSpeed = openSpeed;
        this.closeSpeed = closeSpeed;
        TextureRegion[][] tiles = loadDoorTiles();
        this.vertRegion = tiles[0][0];
        this.horizRegion = tiles[1][0];
    }

    // Anchor-aware constructor: position door relative to a specific cell of an anchor wall
    public Door(Wall anchor, int anchorCol, int anchorRow, int cols, int rows, OpenDirection dir, float openSpeed, float closeSpeed) {
        // Use same placement logic as Wall(anchor, ...)
        float cellSize = 32f;
        int anchorCols = Math.max(1, (int) (anchor.bounds.width / cellSize));
        int anchorRows = Math.max(1, (int) (anchor.bounds.height / cellSize));

        float newWidth = cols * cellSize;
        float newHeight = rows * cellSize;

        // X position
        float x;
        if (anchorCol <= 0) {
            x = anchor.bounds.x - newWidth; // left
        } else if (anchorCol > anchorCols) {
            x = anchor.bounds.x + anchor.bounds.width; // right
        } else {
            x = anchor.bounds.x + (anchorCol - 1) * cellSize;
        }

        // Y position
        float y;
        if (anchorRow <= 0) {
            y = anchor.bounds.y + anchor.bounds.height; // on top
        } else if (anchorRow > anchorRows) {
            y = anchor.bounds.y - newHeight; // below
        } else {
            y = anchor.bounds.y + (anchorRow - 1) * cellSize; // inside
        }

        bounds = new Rectangle(x, y, newWidth, newHeight);
        this.openDirection = dir;
        this.openSpeed = openSpeed;
        this.closeSpeed = closeSpeed;
        TextureRegion[][] tiles = loadDoorTiles();
        this.vertRegion = tiles[0][0];
        this.horizRegion = tiles[1][0];
    }

    private TextureRegion[][] loadDoorTiles() {
        if (doorTexture == null) {
            doorTexture = new Texture(Gdx.files.internal("environment/door.png"));
        }
        // Split into 32x32 tiles; returns [rows][cols]
        return TextureRegion.split(doorTexture, 32, 32);
    }

    @Override
    public Rectangle getBounds() {
        return bounds;
    }

    @Override
    public Rectangle getCollisionBounds() {
        // When fully open (>=0.99), disable collision completely
        if (openProgress >= 0.99f) return null;
        final int TILE = 32;
        if (openDirection == OpenDirection.UP || openDirection == OpenDirection.DOWN) {
            float rawVisible = bounds.height * (1f - openProgress);
            // If less than half tile visible, treat as open
            if (rawVisible < TILE * 0.5f) return null;
            int visibleRows = (int)Math.ceil(rawVisible / TILE);
            if (visibleRows <= 0) return null;
            float visibleHeight = Math.min(bounds.height, visibleRows * TILE);
            float yOffset = (openDirection == OpenDirection.UP) ? bounds.y : (bounds.y + bounds.height - visibleHeight);
            return new Rectangle(bounds.x, yOffset, bounds.width, visibleHeight);
        } else {
            float rawVisible = bounds.width * (1f - openProgress);
            if (rawVisible < TILE * 0.5f) return null;
            int visibleCols = (int)Math.ceil(rawVisible / TILE);
            if (visibleCols <= 0) return null;
            float visibleWidth = Math.min(bounds.width, visibleCols * TILE);
            float xOffset = (openDirection == OpenDirection.RIGHT) ? bounds.x : (bounds.x + bounds.width - visibleWidth);
            return new Rectangle(xOffset, bounds.y, visibleWidth, bounds.height);
        }
    }

    @Override
    public boolean isSolid() {
        return getCollisionBounds() != null;
    }

    @Override
    public boolean isBlocking() {
        return getCollisionBounds() != null;
    }

    @Override
    public void update(float delta) {
        // Smooth open/close interpolation
        float target = open ? 1f : 0f;
        if (openProgress != target) {
            float diff = target - openProgress;
            float speed = diff > 0 ? openSpeed : closeSpeed;
            openProgress += Math.signum(diff) * speed * delta;
            openProgress = Math.max(0, Math.min(1, openProgress));
        }
    }

    @Override
    public void render(SpriteBatch batch) {
        // Tile the 32x32 sprite across the door bounds so the texture doesn't stretch.
        final int TILE = 32;
        if (openDirection == OpenDirection.UP || openDirection == OpenDirection.DOWN) {
            float visibleHeight = bounds.height * (1f - openProgress);
            if (visibleHeight <= 0f) return;
            float yOffset = (openDirection == OpenDirection.UP) ? bounds.y : (bounds.y + bounds.height - visibleHeight);

            // Number of full tiles horizontally and vertically
            int tilesX = (int)Math.ceil(bounds.width / TILE);
            int tilesY = (int)Math.ceil(visibleHeight / TILE);

            int baseX = vertRegion.getRegionX();
            int baseY = vertRegion.getRegionY();

            for (int ix = 0; ix < tilesX; ix++) {
                float drawX = bounds.x + ix * TILE;
                float remainingW = Math.min(TILE, bounds.x + bounds.width - drawX);
                for (int iy = 0; iy < tilesY; iy++) {
                    float drawY = yOffset + iy * TILE;
                    float remainingH = Math.min(TILE, yOffset + visibleHeight - drawY);

                    if (remainingW >= TILE && remainingH >= TILE) {
                        // full tile
                        batch.draw(vertRegion, drawX, drawY, TILE, TILE);
                    } else {
                        int drawW = Math.max(1, (int)remainingW);
                        int drawH = Math.max(1, (int)remainingH);
                        if (remainingW >= TILE && remainingH >= TILE) {
                            batch.draw(vertRegion, drawX, drawY, TILE, TILE);
                        } else {
                            cachedPartial.setTexture(vertRegion.getTexture());
                            cachedPartial.setRegion(baseX, baseY, drawW, drawH);
                            batch.draw(cachedPartial, drawX, drawY, drawW, drawH);
                        }
                    }
                }
            }

        } else {
            float visibleWidth = bounds.width * (1f - openProgress);
            if (visibleWidth <= 0f) return;
            float xOffset = (openDirection == OpenDirection.RIGHT) ? bounds.x : (bounds.x + bounds.width - visibleWidth);

            int tilesX = (int)Math.ceil(visibleWidth / TILE);
            int tilesY = (int)Math.ceil(bounds.height / TILE);

            int baseX = horizRegion.getRegionX();
            int baseY = horizRegion.getRegionY();

            for (int ix = 0; ix < tilesX; ix++) {
                float drawX = xOffset + ix * TILE;
                float remainingW = Math.min(TILE, xOffset + visibleWidth - drawX);
                for (int iy = 0; iy < tilesY; iy++) {
                    float drawY = bounds.y + iy * TILE;
                    float remainingH = Math.min(TILE, bounds.y + bounds.height - drawY);

                    int drawW = Math.max(1, (int)remainingW);
                    int drawH = Math.max(1, (int)remainingH);
                    if (remainingW >= TILE && remainingH >= TILE) {
                        batch.draw(horizRegion, drawX, drawY, TILE, TILE);
                    } else {
                        cachedPartial.setTexture(horizRegion.getTexture());
                        cachedPartial.setRegion(baseX, baseY, drawW, drawH);
                        batch.draw(cachedPartial, drawX, drawY, drawW, drawH);
                    }
                }
            }
        }
    }

    @Override
    public void debugDraw(ShapeRenderer shape) {
        // Fade color to show open/close progress
        Color c = new Color(0, 0, 1, 1 - 0.5f * openProgress);
        shape.setColor(c);
        if (openDirection == OpenDirection.UP || openDirection == OpenDirection.DOWN) {
            float visibleHeight = bounds.height * (1f - openProgress);
            float yOffset = (openDirection == OpenDirection.UP) ? bounds.y : (bounds.y + bounds.height - visibleHeight);
            shape.rect(bounds.x, yOffset, bounds.width, visibleHeight);
        } else {
            float visibleWidth = bounds.width * (1f - openProgress);
            float xOffset = (openDirection == OpenDirection.RIGHT) ? bounds.x : (bounds.x + bounds.width - visibleWidth);
            shape.rect(xOffset, bounds.y, visibleWidth, bounds.height);
        }
    }

    @Override
    public void interact() {
        open = !open;
    }

    public boolean isOpen() {
        return open;
    }

    public void setOpen(boolean open) {
        this.open = open;
    }

    public void setOpenDirection(OpenDirection dir) {
        this.openDirection = dir;
    }

    public void setOpenSpeed(float openSpeed) {
        this.openSpeed = openSpeed;
    }

    public void setCloseSpeed(float closeSpeed) {
        this.closeSpeed = closeSpeed;
    }
    public static void disposeStatic() {
        if (doorTexture != null) {
            try { doorTexture.dispose(); } catch (Exception ignored) {}
            doorTexture = null;
        }
    }
}
