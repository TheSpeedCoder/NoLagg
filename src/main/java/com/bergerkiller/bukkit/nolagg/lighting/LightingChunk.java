package com.bergerkiller.bukkit.nolagg.lighting;

import com.bergerkiller.bukkit.common.bases.NibbleArrayBase;
import com.bergerkiller.bukkit.common.utils.ChunkUtil;
import com.bergerkiller.bukkit.common.wrappers.ChunkSection;

import org.bukkit.Chunk;

import java.util.logging.Level;

/**
 * Represents a single chunk full with lighting-relevant information.
 * Initialization and use of this chunk in the process is as follows:<br>
 * - New lighting chunks are created for all chunks to be processed<br>
 * - notifyAccessible is called for all chunks, passing in all chunks<br>
 * - fill/fillSection is called for all chunks, after which initLight is called<br>
 * - spread is called on all chunks until all spreading is finished<br>
 * - data from all LightingChunks/Sections is gathered and saved to chunks or region files<br>
 * - possible chunk resends are performed
 */
public class LightingChunk {
    public static final int SECTION_COUNT = 16;
    public static final int OB = ~0xf; // Outside blocks
    public static final int OC = ~0xff; // Outside chunk
    public final LightingChunkSection[] sections = new LightingChunkSection[SECTION_COUNT];
    public final LightingChunkNeighboring neighbors = new LightingChunkNeighboring();
    public final byte[] heightmap = new byte[256];
    public final int chunkX, chunkZ;
    public boolean hasSkyLight = true;
    public boolean isSkyLightDirty = true;
    public boolean isBlockLightDirty = true;
    public boolean isFilled = false;
    public boolean isApplied = false;
    public int startX = 1;
    public int startZ = 1;
    public int endX = 14;
    public int endZ = 14;

    public LightingChunk(int x, int z) {
        this.chunkX = x;
        this.chunkZ = z;
    }

    /**
     * Notifies that a new chunk is accessible.
     *
     * @param chunk that is accessible
     */
    public void notifyAccessible(LightingChunk chunk) {
        final int dx = chunk.chunkX - this.chunkX;
        final int dz = chunk.chunkZ - this.chunkZ;
        // Only check neighbours, ignoring the corners and self
        if (Math.abs(dx) > 1 || Math.abs(dz) > 1 || (dx != 0) == (dz != 0)) {
            return;
        }
        // Results in -16, 16 or 0 for the x/z coordinates
        neighbors.set(dx, dz, chunk);
        // Update start/end coordinates
        if (dx == 1) {
            endX = 15;
        } else if (dx == -1) {
            startX = 0;
        } else if (dz == 1) {
            endZ = 15;
        } else if (dz == -1) {
            startZ = 0;
        }
    }

    public void fill(Chunk chunk) {
        // Fill using chunk sections
        ChunkSection[] chunkSections = ChunkUtil.getSections(chunk);
        for (int section = 0; section < SECTION_COUNT; section++) {
            ChunkSection chunkSection = chunkSections[section];
            if (chunkSection != null) {
                hasSkyLight &= chunkSection.hasSkyLight();
                sections[section] = new LightingChunkSection(this, chunkSection);
            }
        }
        this.isFilled = true;
    }

    private int getTopY() {
        for (int section = SECTION_COUNT; section > 0; section--) {
            if (sections[section - 1] != null) {
                return (section << 4) - 1;
            }
        }
        return 0;
    }

    private int getHeightKey(int x, int z) {
        return x | (z << 4);
    }

    /**
     * Gets the height level (the top block that does not block light)
     *
     * @param x - coordinate
     * @param z - coordinate
     * @return height
     */
    public int getHeight(int x, int z) {
        return heightmap[getHeightKey(x, z)] & 0xff;
    }

    /**
     * Initializes the sky lighting and generates the heightmap
     */
    public void initLight() {
        if (!hasSkyLight) {
            return;
        }
        // Find out the highest possible Y-position
        int topY = getTopY();
        int x, y, z, light, darkLight, height, opacity;
        LightingChunkSection section;
        // Apply initial sky lighting from top to bottom
        for (x = startX; x <= endX; x++) {
            for (z = startZ; z <= endZ; z++) {
                light = 15;
                darkLight = topY;
                height = 0;
                for (y = topY; y >= 0; y--) {
                    if ((section = sections[y >> 4]) == null) {
                        // Skip the remaining 15: they are all inaccessible as well
                        y -= 15;
                        continue;
                    }
                    // Apply the opacity to the light level
                    opacity = section.opacity.get(x, y & 0xf, z);
                    if (light <= 0 || --darkLight <= 0 || (light -= opacity) <= 0) {
                        light = 0;
                    }
                    // No longer in the air? Update height
                    if (light != 15 && y > height) {
                        height = y;
                    }
                    // Apply sky light to block
                    section.skyLight.set(x, y & 0xf, z, light);
                }
                heightmap[getHeightKey(x, z)] = (byte) height;
            }
        }
    }

    private final int getMaxLightLevel(boolean skyLight, int lightLevel, int x, int y, int z) {
        if (x >= 1 && z >= 1 && x <= 14 && z <= 14) {
            // All within this chunk - simplified calculation
            final LightingChunkSection section = this.sections[y >> 4];
            if (section != null) {
                final int dy = y & 0xf;
                final NibbleArrayBase light = skyLight ? section.skyLight : section.blockLight;
                lightLevel = Math.max(lightLevel, light.get(x - 1, dy, z));
                lightLevel = Math.max(lightLevel, light.get(x + 1, dy, z));
                lightLevel = Math.max(lightLevel, light.get(x, dy, z - 1));
                lightLevel = Math.max(lightLevel, light.get(x, dy, z + 1));

                // If dy is also within this section, we can simplify it
                if (dy >= 1 && dy <= 14) {
                    lightLevel = Math.max(lightLevel, light.get(x, dy - 1, z));
                    lightLevel = Math.max(lightLevel, light.get(x, dy + 1, z));
                    return lightLevel;
                }
            }
        } else {
            // Crossing chunk boundaries - requires neighbor checks
            lightLevel = Math.max(lightLevel, getLightLevel(skyLight, x - 1, y, z));
            lightLevel = Math.max(lightLevel, getLightLevel(skyLight, x + 1, y, z));
            lightLevel = Math.max(lightLevel, getLightLevel(skyLight, x, y, z - 1));
            lightLevel = Math.max(lightLevel, getLightLevel(skyLight, x, y, z + 1));
        }

        // Slice below
        if (y >= 1) {
            final LightingChunkSection sectionBelow = this.sections[(y - 1) >> 4];
            if (sectionBelow != null) {
                lightLevel = Math.max(lightLevel, sectionBelow.getLight(skyLight, x, (y - 1) & 0xff, z));
            }
        }

        // Slice above
        if (y <= 254) {
            final LightingChunkSection sectionAbove = this.sections[(y + 1) >> 4];
            if (sectionAbove != null) {
                lightLevel = Math.max(lightLevel, sectionAbove.getLight(skyLight, x, (y + 1) & 0xff, z));
            }
        }

        return lightLevel;
    }

    private final int getLightLevel(boolean skyLight, int x, int y, int z) {
        // Outside the blocks space of this chunk?
        final LightingChunk chunk = (x & OB | z & OB) == 0 ? this : neighbors.get(x >> 4, z >> 4);
        final LightingChunkSection section = chunk.sections[y >> 4];
        return section == null ? 0 : section.getLight(skyLight, x & 0xf, y & 0xf, z & 0xf);
    }

    /**
     * Gets whether this lighting chunk has faults that need to be fixed
     *
     * @return True if there are faults, False if not
     */
    public boolean hasFaults() {
        return isSkyLightDirty || isBlockLightDirty;
    }

    /**
     * Spreads the light from sources to 'zero' light level blocks
     *
     * @return True if spreading was needed, False if not
     */
    public boolean spread() {
        if (hasFaults()) {
            if (isSkyLightDirty) {
                spread(true);
            }
            if (isBlockLightDirty) {
                spread(false);
            }
            return true;
        } else {
            return false;
        }
    }

    private void spread(boolean skyLight) {
        if (skyLight && !hasSkyLight) {
            this.isSkyLightDirty = false;
            return;
        }
        int x, y, z, light, factor, startY, newlight;
        int loops = 0;
        int lasterrx = 0, lasterry = 0, lasterrz = 0;
        final int maxY = getTopY();
        boolean haserror;

        boolean err_neigh_nx = false;
        boolean err_neigh_px = false;
        boolean err_neigh_ny = false;
        boolean err_neigh_py = false;

        LightingChunkSection chunksection;
        // Keep spreading the light in this chunk until it is done
        do {
            haserror = false;
            if (loops++ > 100) {
                lasterrx += this.chunkX << 4;
                lasterrz += this.chunkZ << 4;
                StringBuilder msg = new StringBuilder();
                msg.append("Failed to fix all " + (skyLight ? "Sky" : "Block") + " lighting at [");
                msg.append(lasterrx).append('/').append(lasterry);
                msg.append('/').append(lasterrz).append(']');
                NoLaggLighting.plugin.log(Level.WARNING, msg.toString());
                break;
            }
            // Go through all blocks, using the heightmap for sky light to skip a few
            for (x = startX; x <= endX; x++) {
                for (z = startZ; z <= endZ; z++) {
                    startY = skyLight ? getHeight(x, z) : maxY;
                    for (y = startY; y > 0; y--) {
                        if ((chunksection = this.sections[y >> 4]) == null) {
                            y = ((y >> 4) - 1) << 4; // skip section
                            continue;
                        }
                        factor = Math.max(1, chunksection.opacity.get(x, y & 0xf, z));
                        if (factor == 15) {
                            continue;
                        }

                        // Read the old light level and try to find a light level around it that exceeds
                        light = chunksection.getLight(skyLight, x, y & 0xf, z);
                        newlight = light + factor;
                        newlight = getMaxLightLevel(skyLight, newlight, x, y, z);
                        newlight -= factor;

                        // pick the highest value
                        if (newlight > light) {
                            chunksection.setLight(skyLight, x, y & 0xf, z, newlight);
                            lasterrx = x;
                            lasterry = y;
                            lasterrz = z;
                            err_neigh_nx |= (x == 0);
                            err_neigh_ny |= (y == 0);
                            err_neigh_px |= (x == 15);
                            err_neigh_py |= (y == 15);
                            haserror = true;
                        }
                    }
                }
            }
        } while (haserror);

        if (skyLight) {
            this.isSkyLightDirty = false;
        } else {
            this.isBlockLightDirty = false;
        }

        // When we change blocks at our chunk borders, neighbours have to do another spread cycle
        if (err_neigh_nx) markNeighbor(-1, 0, skyLight);
        if (err_neigh_px) markNeighbor(1, 0, skyLight);
        if (err_neigh_ny) markNeighbor(0, -1, skyLight);
        if (err_neigh_py) markNeighbor(0, 1, skyLight);
    }

    private void markNeighbor(int dx, int dy, boolean skyLight) {
        LightingChunk n = neighbors.get(dx, dy);
        if (n != null) {
            if (skyLight) {
                n.isSkyLightDirty = true;
            } else {
                n.isBlockLightDirty = true;
            }
        }
    }

    /**
     * Applies the lighting information to a chunk
     *
     * @param chunk to save to
     */
    public void saveToChunk(Chunk chunk) {
        ChunkSection[] chunkSections = ChunkUtil.getSections(chunk);
        for (int section = 0; section < SECTION_COUNT; section++) {
            if (chunkSections[section] != null && sections[section] != null) {
                sections[section].saveToChunk(chunkSections[section]);
            }
        }
        this.isApplied = true;
    }
}
