package top.yzljc.onlineReward.afk;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import top.yzljc.onlineReward.database.CacheManager;
import top.yzljc.onlineReward.database.PlayerData;
import top.yzljc.onlineReward.manager.RewardManager;

/**
 * 玩家事件监听器 —— 处理加入/退出/移动等事件，追踪在线时长和 AFK 状态。
 */
public final class PlayerListener implements Listener {

    private final JavaPlugin plugin;
    private final CacheManager cacheManager;
    private final RewardManager rewardManager;
    private final AfkDetector afkDetector;

    public PlayerListener(JavaPlugin plugin, CacheManager cacheManager,
                          RewardManager rewardManager, AfkDetector afkDetector) {
        this.plugin = plugin;
        this.cacheManager = cacheManager;
        this.rewardManager = rewardManager;
        this.afkDetector = afkDetector;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        PlayerData data = cacheManager.getPlayerData(player.getUniqueId(), player.getName());
        data.setLastJoinTime(System.currentTimeMillis());
        data.setLastActiveTime(System.currentTimeMillis());
        data.markDirty();

        afkDetector.registerPlayer(player.getUniqueId());

        plugin.getLogger().info("玩家 " + player.getName() + " 加入了。累计在线: " +
                RewardManager.formatTime(data.getTotalOnlineSeconds()));
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // 结算离线前的时长
        PlayerData data = cacheManager.getPlayerData(player.getUniqueId(), player.getName());
        if (data != null) {
            long now = System.currentTimeMillis();
            long lastCheck = data.getLastRewardCheckTime();
            if (lastCheck > 0) {
                long elapsed = (now - lastCheck) / 1000;
                if (elapsed > 0 && elapsed < 86400) {
                    data.addOnlineSeconds(elapsed);
                }
            }
            data.setLastActiveTime(now);
            data.markDirty();
        }

        cacheManager.saveAndRemove(player.getUniqueId());
        afkDetector.unregisterPlayer(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) return;

        Player player = event.getPlayer();
        afkDetector.updateActivity(player.getUniqueId());

        PlayerData data = cacheManager.getPlayerData(player.getUniqueId(), player.getName());
        if (data != null) {
            data.setLastActiveTime(System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        afkDetector.updateActivity(player.getUniqueId());

        PlayerData data = cacheManager.getPlayerData(player.getUniqueId(), player.getName());
        if (data != null) {
            data.setLastActiveTime(System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        afkDetector.updateActivity(player.getUniqueId());

        PlayerData data = cacheManager.getPlayerData(player.getUniqueId(), player.getName());
        if (data != null) {
            data.setLastActiveTime(System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        afkDetector.updateActivity(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        afkDetector.updateActivity(player.getUniqueId());
    }
}
