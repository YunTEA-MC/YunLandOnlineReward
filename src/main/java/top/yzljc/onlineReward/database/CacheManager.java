package top.yzljc.onlineReward.database;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CacheManager {

    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();
    private BukkitTask flushTask;

    public CacheManager(JavaPlugin plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
    }

    /**
     * Starts the periodic flush task.
     */
    public void startFlushTask() {
        int intervalMinutes = plugin.getConfig().getInt("save-interval", 5);
        long intervalTicks = intervalMinutes * 60L * 20L;
        flushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::flushAll, intervalTicks, intervalTicks);
        plugin.getLogger().info("自动保存任务已启动，间隔: " + intervalMinutes + " min");
    }

    /**
     * Gets a player's data from cache, loading from DB if not present.
     */
    public PlayerData getPlayerData(UUID uuid, String playerName) {
        PlayerData data = cache.get(uuid);
        if (data == null) {
            data = databaseManager.loadPlayerData(uuid, playerName);
            if (data == null) {
                data = new PlayerData(uuid, playerName);
            } else {
                // Update player name and join time
                data.setPlayerName(playerName);
                data.setLastJoinTime(System.currentTimeMillis());
                data.markDirty();
            }
            cache.put(uuid, data);
        }
        return data;
    }

    /**
     * Saves a single player's data and removes from cache.
     * Called when a player quits.
     */
    public void saveAndRemove(UUID uuid) {
        PlayerData data = cache.remove(uuid);
        if (data != null && data.isDirty()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                databaseManager.savePlayerData(data);
            });
        }
    }

    /**
     * Flushes all dirty cache entries to the database.
     */
    public void flushAll() {
        int saved = 0;
        for (PlayerData data : cache.values()) {
            if (data.isDirty()) {
                databaseManager.savePlayerData(data);
                data.markClean();
                saved++;
            }
        }
        if (saved > 0) {
            plugin.getLogger().info("已刷入 " + saved + " 个玩家数据到数据库。");
        }
    }

    /**
     * Flushes all dirty data synchronously (for plugin disable).
     */
    public void flushAllSync() {
        int saved = 0;
        for (PlayerData data : cache.values()) {
            if (data.isDirty()) {
                databaseManager.savePlayerData(data);
                data.markClean();
                saved++;
            }
        }
        if (saved > 0) {
            plugin.getLogger().info("已刷入 " + saved + " 个玩家数据到数据库（关闭时）。");
        }
    }

    /**
     * Cancels the flush task.
     */
    public void stopFlushTask() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
    }

    /**
     * Cleans up old player data from both DB and cache.
     * Players with last_active_time older than the configured days will be removed.
     */
    public void runCleanup() {
        int days = plugin.getConfig().getInt("data-cleanup.keep-days", 30);
        long cutoff = System.currentTimeMillis() - (days * 24L * 3600L * 1000L);

        // Remove from cache first (inactive players not currently online)
        cache.entrySet().removeIf(entry -> {
            PlayerData data = entry.getValue();
            // Only remove from cache if they are offline (lastActiveTime is old)
            if (data.getLastActiveTime() < cutoff) {
                plugin.getLogger().info("已清理缓存数据: " + data.getPlayerName());
                return true;
            }
            return false;
        });

        // Remove from database
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int deleted = databaseManager.cleanupOldData(cutoff);
            if (deleted > 0) {
                plugin.getLogger().info("已从数据库清理 " + deleted + " 个不活跃玩家数据。");
            }
        });
    }

    /**
     * Checks if a player is in cache (i.e., online).
     */
    public boolean isPlayerOnline(UUID uuid) {
        return cache.containsKey(uuid);
    }

    /**
     * Returns the number of cached entries.
     */
    public int getCacheSize() {
        return cache.size();
    }
}
