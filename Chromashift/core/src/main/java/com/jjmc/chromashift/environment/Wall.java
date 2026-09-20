package com.jjmc.chromashift.environment;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Rectangle;

public class Wall implements Solid {
    public final Rectangle bounds;
    // Tiling texture (32x32) for walls
    private static Texture solidTexture;
    private final TextureRegion solidRegion;

    public Wall(float x, float y, int cols, int rows) {
        // Convert columns and rows to actual width and height (32 pixels per cell)
        float width = cols * 32f;
        float height = rows * 32f;
        bounds = new Rectangle(x, y, width, height);
        TextureRegion[][] tiles = loadSolidTiles();
        this.solidRegion = tiles[0][0];
    }

    /**
     * Create a wall that is positioned relative to an existing (anchor) wall.
     * The anchor cell coordinates are 1-based: col=1 is the leftmost cell of the anchor,
     * row=1 is the bottom-most cell of the anchor.
     * The new wall's bottom-left corner will be placed at the anchor cell's location.
     *
     * Example: new Wall(anchor, 5, 1, 2, 3) will place the new wall at column 5, row 1
     * of the anchor (counting cells from anchor's origin) and size it 2x3 cells.
     */
    public Wall(Wall anchor, int anchorCol, int anchorRow, int cols, int rows) {
        // Anchor-aware placement rules (cell grid = 32px)
        // anchorCol / anchorRow are interpreted like this:
        // - If anchorRow <= 0: place new wall on TOP of the anchor (y = anchor.top)
        // - If anchorRow between 1..anchorRows: place at that cell row (bottom-based)
        // - If anchorRow > anchorRows: place new wall BELOW the anchor (y = anchor.y - newHeight)
        // - If anchorCol <= 0: place new wall to the LEFT of the anchor (x = anchor.x - newWidth)
        // - If anchorCol between 1..anchorCols: place at that cell column
        // - If anchorCol > anchorCols: place new wall to the RIGHT of the anchor (x = anchor.right)

        float cellSize = 32f;
        int anchorCols = Math.max(1, (int) (anchor.bounds.width / cellSize));
        int anchorRows = Math.max(1, (int) (anchor.bounds.height / cellSize));

        float newWidth = cols * cellSize;
        float newHeight = rows * cellSize;

        // X position
        float x;
        if (anchorCol <= 0) {
            // place to the left
            x = anchor.bounds.x - newWidth;
        } else if (anchorCol > anchorCols) {
            // place to the right
            x = anchor.bounds.x + anchor.bounds.width;
        } else {
            // inside anchor columns (1-based)
            x = anchor.bounds.x + (anchorCol - 1) * cellSize;
        }

        // Y position
        float y;
        if (anchorRow <= 0) {
            // place on top
            y = anchor.bounds.y + anchor.bounds.height;
        } else if (anchorRow > anchorRows) {
            // place below
            y = anchor.bounds.y - newHeight;
        } else {
            // inside anchor rows (1-based)
            y = anchor.bounds.y + (anchorRow - 1) * cellSize;
        }

        bounds = new Rectangle(x, y, newWidth, newHeight);
        TextureRegion[][] tiles = loadSolidTiles();
        this.solidRegion = tiles[0][0];
    }

    private TextureRegion[][] loadSolidTiles() {
        if (solidTexture == null) {
            solidTexture = new Texture(Gdx.files.internal("environment/solid.png"));
        }
        return TextureRegion.split(solidTexture, 32, 32);
    }

    @Override
    public Rectangle getBounds() {
        return bounds;
    }

    @Override
    public boolean isSolid() {
        return true;
    }

    private final TextureRegion cachedPartial = new TextureRegion();
    @Override
    public void render(SpriteBatch batch) {
        final int TILE = 32;
        int tilesX = (int)Math.ceil(bounds.width / TILE);
        int tilesY = (int)Math.ceil(bounds.height / TILE);
        int baseX = solidRegion.getRegionX();
        int baseY = solidRegion.getRegionY();

        for (int ix = 0; ix < tilesX; ix++) {
            float drawX = bounds.x + ix * TILE;
            float remainingW = Math.min(TILE, bounds.x + bounds.width - drawX);
            for (int iy = 0; iy < tilesY; iy++) {
                float drawY = bounds.y + iy * TILE;
                float remainingH = Math.min(TILE, bounds.y + bounds.height - drawY);

                if (remainingW >= TILE && remainingH >= TILE) {
                    batch.draw(solidRegion, drawX, drawY, TILE, TILE);
                } else {
                    int drawW = Math.max(1, (int)remainingW);
                    int drawH = Math.max(1, (int)remainingH);
                    cachedPartial.setTexture(solidRegion.getTexture());
                    cachedPartial.setRegion(baseX, baseY, drawW, drawH);
                    batch.draw(cachedPartial, drawX, drawY, drawW, drawH);
                }
            }
        }
    }

    @Override
    public void debugDraw(ShapeRenderer shape) {
        shape.setColor(Color.DARK_GRAY);
        shape.rect(bounds.x, bounds.y, bounds.width, bounds.height);
    }

    @Override
    public boolean isBlocking() {
        return true;
    }

    public static void disposeStatic() {
        dispose();
    }
    public static void dispose() {
        if (solidTexture != null) {
            solidTexture.dispose();
            solidTexture = null;
        }
    }

    public float getX() {
        return bounds.x;
    }

    public float getY() {
        return bounds.y;
    }

    public float getWidth() {
        return bounds.width;
    }
    
    public float getHeight() {
        return bounds.height;
    }
}
