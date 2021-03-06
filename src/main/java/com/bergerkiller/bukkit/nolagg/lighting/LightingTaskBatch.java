package com.bergerkiller.bukkit.nolagg.lighting;

import com.bergerkiller.bukkit.common.bases.IntVector2;
import com.bergerkiller.bukkit.common.utils.WorldUtil;
import com.bergerkiller.bukkit.common.wrappers.LongHashSet;
import org.bukkit.Chunk;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Contains all the chunk coordinates that have to be fixed,
 * and handles the full process of this fixing.
 * It is literally a batch of chunks being processed.
 */
public class LightingTaskBatch implements LightingTask {
    public static final int MAX_PROCESSING_TICK_TIME = 30; // max ms per tick processing
    public final World world;
    private final List<LightingChunk> chunks;
    private final LongHashSet chunksCoords = new LongHashSet();
    private final Object waitObject = new Object();
    private Runnable activeTask = null;

    public LightingTaskBatch(World world, List<IntVector2> chunkCoordinates) {
        // Initialization
        this.world = world;
        this.chunks = new ArrayList<LightingChunk>(chunkCoordinates.size());
        for (IntVector2 coord : chunkCoordinates) {
            this.chunks.add(new LightingChunk(coord.x, coord.z));
            this.chunksCoords.add(coord.x, coord.z);
        }
        // Accessibility
        // Load the chunk with data
        for (LightingChunk lc : this.chunks) {
            for (LightingChunk neigh : this.chunks) {
                lc.notifyAccessible(neigh);
            }
        }
    }

    @Override
    public World getWorld() {
        return world;
    }

    public LongHashSet getChunks() {
        return chunksCoords;
    }

    @Override
    public int getChunkCount() {
        int faults = 0;
        for (LightingChunk chunk : this.chunks) {
            if (chunk.hasFaults()) {
                faults++;
            }
        }
        return faults;
    }

    @Override
    public boolean containsChunk(int chunkX, int chunkZ) {
        return chunksCoords.contains(chunkX, chunkZ);
    }

    @Override
    public void process() {
        // Load
        startLoading();
        waitForCompletion();
        // Fix
        fix();
        // Apply
        startApplying();
        waitForCompletion();
    }

    /**
     * Waits the calling thread until a task is completed
     */
    public void waitForCompletion() {
        synchronized (waitObject) {
            try {
                waitObject.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void syncTick() {
        final Runnable t = activeTask;
        if (t != null) {
            t.run();
        }
    }

    private void completed() {
        activeTask = null;
        synchronized (waitObject) {
            waitObject.notifyAll();
        }
    }

    /**
     * Starts loads the chunks that are to be fixed into memory.
     * This is done in several ticks.
     */
    public void startLoading() {
        activeTask = new Runnable() {
            @Override
            public void run() {
                boolean loaded = false;
                long startTime = System.currentTimeMillis();
                // Load chunks
                for (LightingChunk lc : LightingTaskBatch.this.chunks) {
                    if (lc.isFilled) {
                        continue;
                    }
                    loaded = true;
                    lc.fill(world.getChunkAt(lc.chunkX, lc.chunkZ));
                    // Too long?
                    if ((System.currentTimeMillis() - startTime) > MAX_PROCESSING_TICK_TIME) {
                        break;
                    }
                }
                // Nothing loaded, all is done?
                if (!loaded) {
                    LightingTaskBatch.this.completed();
                }
            }
        };
    }

    /**
     * Starts applying the new data to the world.
     * This is done in several ticks.
     */
    public void startApplying() {
        activeTask = new Runnable() {
            @Override
            public void run() {
                boolean applied = false;
                long startTime = System.currentTimeMillis();
                // Apply data to chunks and unload if needed
                for (LightingChunk lc : LightingTaskBatch.this.chunks) {
                    if (lc.isApplied) {
                        continue;
                    }
                    applied = true;
                    Chunk bchunk = world.getChunkAt(lc.chunkX, lc.chunkZ);
                    // Save to chunk
                    lc.saveToChunk(bchunk);
                    // Resend to players
                    boolean isPlayerNear = WorldUtil.queueChunkSend(world, lc.chunkX, lc.chunkZ);
                    // Try to unload if no player near
                    LightingTaskBatch.this.chunksCoords.remove(lc.chunkX, lc.chunkZ);
                    if (!isPlayerNear) {
                        world.unloadChunkRequest(lc.chunkX, lc.chunkZ, true);
                    }
                    // Too long?
                    if ((System.currentTimeMillis() - startTime) > MAX_PROCESSING_TICK_TIME) {
                        break;
                    }
                }
                // Nothing applied, all is done?
                if (!applied) {
                    LightingTaskBatch.this.completed();
                }
            }
        };
    }

    /**
     * Performs the (slow) fixing procedure (call from another thread)
     */
    public void fix() {
        // Initialize light
        for (LightingChunk chunk : chunks) {
            chunk.initLight();
        }
        // Spread
        boolean hasFaults;
        do {
            hasFaults = false;
            for (LightingChunk chunk : chunks) {
                hasFaults |= chunk.spread();
            }
        } while (hasFaults);
        this.completed();
    }
}
