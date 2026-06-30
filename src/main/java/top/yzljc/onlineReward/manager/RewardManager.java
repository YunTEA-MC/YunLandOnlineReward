package top.yzljc.onlineReward.manager;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import top.yzljc.onlineReward.afk.AfkDetector;
import top.yzljc.onlineReward.database.CacheManager;
import top.yzljc.onlineReward.database.PlayerData;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.Locale;

public final class RewardManager {

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final CacheManager cacheManager;
    private final AfkDetector afkDetector;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public RewardManager(JavaPlugin plugin, ConfigManager configManager,
                         CacheManager cacheManager, AfkDetector afkDetector) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.cacheManager = cacheManager;
        this.afkDetector = afkDetector;
    }

    /**
     * 检查并发放奖励。
     */
    public void checkRewards(Player player) {
        if (player == null || !player.isOnline()) return;

        PlayerData data = cacheManager.getPlayerData(player.getUniqueId(), player.getName());
        if (data == null) return;

        // 先检查是否需要重置（每日/每周）
        checkReset(data);

        // 挂机期间：只推进 lastRewardCheckTime 锚点，不累加时长，不发奖励
        if (afkDetector.isAfk(player.getUniqueId())) {
            data.setLastRewardCheckTime(System.currentTimeMillis());
            return;
        }

        // 更新在线时长
        updateOnlineTime(data, player);

        // 根据重置模式选择对应的时间
        long effectiveTime = getEffectiveTime(data);

        for (ConfigManager.RewardDefinition reward : configManager.getRewards()) {
            if (effectiveTime < reward.getTime()) continue;

            if (!reward.isRepeatable() && data.hasClaimedReward(reward.getTime())) continue;

            // 权限检查
            if (!reward.getPermission().isEmpty() && !player.hasPermission(reward.getPermission())) continue;

            // 发放奖励
            grantReward(player, data, reward);
        }
    }

    /**
     * 获取用于奖励判定的有效时长。
     * 每日/每周模式 → 周期内时长；永不重置 → 累计总时长。
     */
    private long getEffectiveTime(PlayerData data) {
        String resetType = configManager.getResetType();
        if ("never".equals(resetType)) {
            return data.getTotalOnlineSeconds();
        }
        return data.getPeriodOnlineSeconds();
    }

    /**
     * 更新玩家的在线时长。
     */
    private void updateOnlineTime(PlayerData data, Player player) {
        long now = System.currentTimeMillis();
        long lastCheck = data.getLastRewardCheckTime();

        if (lastCheck <= 0) {
            data.setLastRewardCheckTime(now);
            return;
        }

        long elapsedMillis = now - lastCheck;
        // 防止时间跳跃异常（如服务器时间被人为修改）
        if (elapsedMillis < 0 || elapsedMillis > 600_000) { // 最大 10 分钟间隔
            elapsedMillis = 0;
        }

        long elapsedSeconds = elapsedMillis / 1000;
        if (elapsedSeconds > 0) {
            data.addOnlineSeconds(elapsedSeconds);
        }

        data.setLastRewardCheckTime(now);

        // 同步 AFK 检测器的最新活动时间
        data.setLastActiveTime(afkDetector.getLastActiveTime(data.getUuid()));
    }

    /**
     * 检查奖励是否该重置（跨日/跨周）。
     * 重置时会清空已领取记录和当前周期的在线时长。
     */
    private void checkReset(PlayerData data) {
        String resetType = configManager.getResetType();
        if ("never".equals(resetType)) return;

        long now = System.currentTimeMillis();
        long lastReset = data.getLastResetTime();

        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime nowDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone);
        LocalDateTime resetDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(lastReset), zone);

        boolean shouldReset = false;

        switch (resetType) {
            case "daily" -> {
                LocalDate nowDate = nowDateTime.toLocalDate();
                LocalDate resetDate = resetDateTime.toLocalDate();
                shouldReset = nowDate.isAfter(resetDate);
            }
            case "weekly" -> {
                WeekFields weekFields = WeekFields.of(Locale.getDefault());
                int nowWeek = nowDateTime.get(weekFields.weekOfYear());
                int resetWeek = resetDateTime.get(weekFields.weekOfYear());
                int nowYear = nowDateTime.getYear();
                int resetYear = resetDateTime.getYear();
                shouldReset = nowYear > resetYear || (nowYear == resetYear && nowWeek > resetWeek);
            }
        }

        if (shouldReset) {
            data.clearClaimedRewards();
            data.setPeriodOnlineSeconds(0); // 重置周期时长
            data.setLastResetTime(now);
            plugin.getLogger().info("已重置 " + data.getPlayerName() + " 的奖励数据（模式: " + resetType + "）");
        }
    }

    /**
     * 发放奖励：发送消息 + 执行命令。
     */
    private void grantReward(Player player, PlayerData data, ConfigManager.RewardDefinition reward) {
        // 先标记已领取，防止重复发放
        data.markRewardClaimed(reward.getTime());

        // 使用 MiniMessage 发送消息
        for (String messageTemplate : reward.getMessages()) {
            String formatted = formatPlaceholders(messageTemplate, player, reward);
            Component component = miniMessage.deserialize(formatted);
            Audience.audience(player).sendMessage(component);
        }

        // 以控制台身份执行命令（主线程）
        for (String commandTemplate : reward.getCommands()) {
            String command = formatPlaceholders(commandTemplate, player, reward);
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            });
        }

        plugin.getLogger().info("发放奖励 '" + reward.getKey() + "' (" +
                reward.getTime() + "秒) 给 " + player.getName());
    }

    /**
     * 替换字符串中的占位符。
     * 支持: %player% %time% %reward_key% %total_time% %period_time%
     */
    private String formatPlaceholders(String text, Player player, ConfigManager.RewardDefinition reward) {
        PlayerData data = cacheManager.getPlayerData(player.getUniqueId(), player.getName());
        long totalSeconds = data != null ? data.getTotalOnlineSeconds() : 0;
        long periodSeconds = data != null ? data.getPeriodOnlineSeconds() : 0;

        return text
                .replace("%player%", player.getName())
                .replace("%time%", formatTime(reward.getTime()))
                .replace("%reward_key%", reward.getKey())
                .replace("%total_time%", formatTime(totalSeconds))
                .replace("%period_time%", formatTime(periodSeconds));
    }

    /**
     * 把秒数格式化成易读的时长字符串。
     */
    public static String formatTime(long totalSeconds) {
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (hours > 0) {
            return hours + "小时" + minutes + "分" + seconds + "秒";
        } else if (minutes > 0) {
            return minutes + "分" + seconds + "秒";
        } else {
            return seconds + "秒";
        }
    }

    /**
     * 获取玩家下一个未领取的奖励（供信息展示用）。
     * 返回 null 表示全部已领取。
     */
    public ConfigManager.RewardDefinition getNextReward(Player player) {
        PlayerData data = cacheManager.getPlayerData(player.getUniqueId(), player.getName());
        if (data == null) return null;

        long effectiveTime = getEffectiveTime(data);

        for (ConfigManager.RewardDefinition reward : configManager.getRewards()) {
            if (reward.isRepeatable()) continue;

            if (!data.hasClaimedReward(reward.getTime())) {
                if (!reward.getPermission().isEmpty() && !player.hasPermission(reward.getPermission())) {
                    continue;
                }
                return reward;
            }
        }
        return null;
    }
}
