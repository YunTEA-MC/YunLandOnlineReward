package top.yzljc.onlineReward;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import top.yzljc.onlineReward.afk.AfkDetector;
import top.yzljc.onlineReward.afk.PlayerListener;
import top.yzljc.onlineReward.database.CacheManager;
import top.yzljc.onlineReward.database.DatabaseManager;
import top.yzljc.onlineReward.database.PlayerData;
import top.yzljc.onlineReward.manager.ConfigManager;
import top.yzljc.onlineReward.manager.RewardManager;

/**
 * OnlineReward - Minecraft 在线时长奖励插件。
 * <p>
 * 追踪玩家在线时长，达到配置的时间阈值后发放奖励。
 * 使用 SQLite 持久化存储 + 内存缓存，数据在以下时机写入数据库：
 * <ul>
 *   <li>每隔 N 分钟自动刷入（默认 5 分钟）</li>
 *   <li>玩家下线时</li>
 *   <li>服务器关闭时（onDisable）</li>
 * </ul>
 * </p>
 */
public final class OnlineReward extends JavaPlugin {

    private DatabaseManager databaseManager;
    private CacheManager cacheManager;
    private ConfigManager configManager;
    private RewardManager rewardManager;
    private AfkDetector afkDetector;
    private PlayerListener playerListener;

    private BukkitTask checkTask;
    private BukkitTask cleanupTask;

    @Override
    public void onEnable() {
        // 初始化各管理器
        this.configManager = new ConfigManager(this);
        this.databaseManager = new DatabaseManager(this);
        this.cacheManager = new CacheManager(this, databaseManager);
        this.afkDetector = new AfkDetector();
        this.rewardManager = new RewardManager(this, configManager, cacheManager, afkDetector);
        this.playerListener = new PlayerListener(this, cacheManager, rewardManager, afkDetector);

        // 加载配置
        configManager.loadConfig();
        afkDetector.loadConfig(getConfig());

        // 初始化数据库
        databaseManager.init();

        // 启动定时刷入任务（异步）
        cacheManager.startFlushTask();

        // 注册事件监听器
        getServer().getPluginManager().registerEvents(playerListener, this);

        // 注册命令
        getCommand("onlinereward").setExecutor(this);

        // 启动异步奖励检查
        long checkIntervalTicks = configManager.getCheckIntervalSeconds() * 20L;
        checkTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                try {
                    rewardManager.checkRewards(player);
                } catch (Exception e) {
                    getLogger().warning("检查玩家 " + player.getName() + " 的奖励时出错: " + e.getMessage());
                }
            }
        }, checkIntervalTicks, checkIntervalTicks);

        // 启动每日数据清理任务
        long cleanupIntervalTicks = 24L * 60L * 60L * 20L;
        long initialDelayTicks = 5L * 60L * 20L;
        cleanupTask = Bukkit.getScheduler().runTaskTimer(this,
                () -> cacheManager.runCleanup(),
                initialDelayTicks, cleanupIntervalTicks);

        // 注册当前在线玩家（处理 /reload 场景）
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerData data = cacheManager.getPlayerData(player.getUniqueId(), player.getName());
            data.setLastJoinTime(System.currentTimeMillis());
            afkDetector.registerPlayer(player.getUniqueId());
        }

        getLogger().info("OnlineReward v" + getPluginMeta().getVersion() + " 已启用！");
    }

    @Override
    public void onDisable() {
        // 取消定时任务
        if (checkTask != null) {
            checkTask.cancel();
        }
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }

        // 保存所有在线玩家的数据
        for (Player player : Bukkit.getOnlinePlayers()) {
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
        }

        // 停止定时刷入任务
        cacheManager.stopFlushTask();

        // 同步刷入全部数据
        cacheManager.flushAllSync();

        // 关闭数据库
        databaseManager.close();

        getLogger().info("OnlineReward 已禁用，所有数据已保存。");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("OnlineReward v" + getPluginMeta().getVersion() +
                    " | /onlinereward reload — 重载配置"));
            return true;
        }

        if ("reload".equalsIgnoreCase(args[0])) {
            reloadPlugin(sender);
            return true;
        }

        sender.sendMessage(Component.text("未知子命令，用法: /onlinereward reload"));
        return true;
    }

    private void reloadPlugin(CommandSender sender) {
        var mm = MiniMessage.miniMessage();

        // 取消旧任务
        if (checkTask != null) checkTask.cancel();

        // 重载配置
        configManager.loadConfig();
        afkDetector.loadConfig(getConfig());

        // 重启检查任务
        long checkIntervalTicks = configManager.getCheckIntervalSeconds() * 20L;
        checkTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                try {
                    rewardManager.checkRewards(player);
                } catch (Exception e) {
                    getLogger().warning("检查玩家 " + player.getName() + " 的奖励时出错: " + e.getMessage());
                }
            }
        }, checkIntervalTicks, checkIntervalTicks);

        // 重启刷入任务
        cacheManager.stopFlushTask();
        cacheManager.startFlushTask();

        sender.sendMessage(mm.deserialize("<green>OnlineReward 配置已重载。</green>"));
        getLogger().info("配置已重载 (操作者: " + sender.getName() + ")");
    }

    // -- 公开访问器，供其他插件/API 调用 --

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public CacheManager getCacheManager() {
        return cacheManager;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public RewardManager getRewardManager() {
        return rewardManager;
    }

    public AfkDetector getAfkDetector() {
        return afkDetector;
    }
}
