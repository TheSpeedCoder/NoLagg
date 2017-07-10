package com.bergerkiller.bukkit.nolagg.chunks.antiloader;

import com.bergerkiller.bukkit.common.bases.IntVector2;
import com.bergerkiller.bukkit.common.conversion.Conversion;
import com.bergerkiller.bukkit.common.utils.ChunkUtil;
import com.bergerkiller.bukkit.common.utils.PlayerUtil;
import com.bergerkiller.bukkit.nolagg.chunks.ChunkSendQueue;
import com.bergerkiller.reflection.net.minecraft.server.NMSPlayerChunk;

import org.bukkit.entity.Player;

import java.util.ArrayList;

@SuppressWarnings("rawtypes")
public class DummyInstancePlayerList extends ArrayList {
    private static final long serialVersionUID = -1878411514739243453L;
    public static boolean FILTER = false;
    private DummyPlayerManager playerManager;
    private IntVector2 location;

    @SuppressWarnings("unchecked")
    public static void replace(DummyPlayerManager playerManager, Object playerInstance) {
        DummyInstancePlayerList list = new DummyInstancePlayerList();
        list.playerManager = playerManager;
        list.location = Conversion.toIntVector2.convert(NMSPlayerChunk.location.get(playerInstance));
        list.addAll(NMSPlayerChunk.players.get(playerInstance));
        NMSPlayerChunk.players.set(playerInstance, list);
    }

    @Override
    public boolean contains(Object o) {
        if (super.contains(o)) {
            if (!FILTER) {
                return true;
            }
            Player player = Conversion.toPlayer.convert(o);
            if (PlayerUtil.isChunkVisible(player, this.location.x, this.location.z)) {
                // Remove from queue
                ChunkSendQueue.bind(player).removePair(this.location);
                return true;
            }

            // Player still has to receive this chunk
            // Perform custom removal logic, preventing the unload chunk being sent
            // This is to overcome the [0,0] chunk hole problem
            super.remove(o);
            if (super.isEmpty()) {
                // Remove this player instance from the player manager
                this.playerManager.removeInstance(this.location);
                //ChunkUtil.setChunkUnloading(this.playerManager.world, location.x, location.z, true);
            }
        }
        return false;
    }
}
