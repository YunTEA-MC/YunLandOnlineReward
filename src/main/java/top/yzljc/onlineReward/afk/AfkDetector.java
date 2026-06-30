package top.yzljc.onlineReward.afk;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AfkDetector {

    private final Map<UUID, Long> lastActiveMap = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> afkMap = new ConcurrentHashMap<>();

    private boolean enabled;
    private long idleTimeoutSeconds;

    /**
     * Loads settings from config.
     */
    public void loadConfig(FileConfiguration config) {
        this.enabled = config.getBoolean("afk.enabled", true);
        this.idleTimeoutSeconds = config.getLong("afk.idle-timeout", 300);
    }

    /**
     * Updates the last active timestamp for a player.
     */
    public void updateActivity(UUID uuid) {
        lastActiveMap.put(uuid, System.currentTimeMillis());
        afkMap.put(uuid, false);
    }

    /**
     * Registers a player when they join.
     */
    public void registerPlayer(UUID uuid) {
        lastActiveMap.put(uuid, System.currentTimeMillis());
        afkMap.put(uuid, false);
    }

    /**
     * Unregisters a player when they quit.
     */
    public void unregisterPlayer(UUID uuid) {
        lastActiveMap.remove(uuid);
        afkMap.remove(uuid);
    }

    /**
     * Checks and updates AFK status for a player.
     * Returns true if the player is currently AFK.
     */
    public boolean isAfk(UUID uuid) {
        if (!enabled) return false;

        Boolean cached = afkMap.get(uuid);
        if (cached != null && cached) {
            // Already marked AFK, check if they've become active again
            Long lastActive = lastActiveMap.get(uuid);
            if (lastActive != null) {
                long idleSeconds = (System.currentTimeMillis() - lastActive) / 1000;
                if (idleSeconds < idleTimeoutSeconds) {
                    // Became active again
                    afkMap.put(uuid, false);
                    return false;
                }
            }
            return true;
        }

        // Check if should be marked AFK
        Long lastActive = lastActiveMap.get(uuid);
        if (lastActive != null) {
            long idleSeconds = (System.currentTimeMillis() - lastActive) / 1000;
            if (idleSeconds >= idleTimeoutSeconds) {
                afkMap.put(uuid, true);
                return true;
            }
        }

        return false;
    }

    /**
     * Gets the idle time for a player in seconds.
     */
    public long getIdleSeconds(UUID uuid) {
        Long lastActive = lastActiveMap.get(uuid);
        if (lastActive == null) return 0;
        return (System.currentTimeMillis() - lastActive) / 1000;
    }

    /**
     * Sets a player's AFK status manually.
     */
    public void setAfk(UUID uuid, boolean afk) {
        afkMap.put(uuid, afk);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getIdleTimeoutSeconds() {
        return idleTimeoutSeconds;
    }

    /**
     * Gets the last active timestamp for a player (for saving to DB).
     */
    public long getLastActiveTime(UUID uuid) {
        Long time = lastActiveMap.get(uuid);
        return time != null ? time : System.currentTimeMillis();
    }
}
