package top.yzljc.onlineReward.manager;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ConfigManager {

    private final JavaPlugin plugin;
    private final List<RewardDefinition> rewards = new ArrayList<>();
    private String resetType;
    private int checkIntervalSeconds;
    private int saveIntervalMinutes;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Loads and validates configuration.
     */
    public void loadConfig() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        // Load reward definitions
        rewards.clear();
        ConfigurationSection rewardsSection = config.getConfigurationSection("rewards");
        if (rewardsSection == null) {
            plugin.getLogger().warning("config.yml 中未找到 'rewards' 节点！");
            return;
        }

        for (String key : rewardsSection.getKeys(false)) {
            ConfigurationSection rewardSection = rewardsSection.getConfigurationSection(key);
            if (rewardSection == null) continue;

            long time = rewardSection.getLong("time", 0);
            List<String> commands = rewardSection.getStringList("commands");
            List<String> messages = rewardSection.getStringList("messages");
            String permission = rewardSection.getString("permission", "");
            boolean repeatable = rewardSection.getBoolean("repeatable", false);

            if (time <= 0) {
                plugin.getLogger().warning("奖励 '" + key + "' 的时间无效: " + time + "，已跳过。");
                continue;
            }

            if (commands.isEmpty() && messages.isEmpty()) {
                plugin.getLogger().warning("奖励 '" + key + "' 没有配置 commands 或 messages，已跳过。");
                continue;
            }

            rewards.add(new RewardDefinition(key, time, commands, messages, permission, repeatable));
        }

        // Sort rewards by time ascending
        rewards.sort(Comparator.comparingLong(RewardDefinition::getTime));

        // Load general settings
        this.resetType = config.getString("reward-reset", "never").toLowerCase();
        this.checkIntervalSeconds = config.getInt("check-interval", 60);
        this.saveIntervalMinutes = config.getInt("save-interval", 5);

        plugin.getLogger().info("已加载 " + rewards.size() + " 个奖励。重置模式: " + resetType +
                "，检查间隔: " + checkIntervalSeconds + "秒，保存间隔: " + saveIntervalMinutes + "分钟");
    }

    public List<RewardDefinition> getRewards() {
        return rewards;
    }

    public String getResetType() {
        return resetType;
    }

    public int getCheckIntervalSeconds() {
        return checkIntervalSeconds;
    }

    public int getSaveIntervalMinutes() {
        return saveIntervalMinutes;
    }

    /**
     * Reward definition loaded from config.
     */
    public static final class RewardDefinition {
        private final String key;
        private final long time;         // seconds
        private final List<String> commands;
        private final List<String> messages;
        private final String permission;
        private final boolean repeatable;

        public RewardDefinition(String key, long time, List<String> commands,
                                List<String> messages, String permission, boolean repeatable) {
            this.key = key;
            this.time = time;
            this.commands = new ArrayList<>(commands);
            this.messages = new ArrayList<>(messages);
            this.permission = permission;
            this.repeatable = repeatable;
        }

        public String getKey() {
            return key;
        }

        /** Reward time threshold in seconds. */
        public long getTime() {
            return time;
        }

        public List<String> getCommands() {
            return commands;
        }

        public List<String> getMessages() {
            return messages;
        }

        public String getPermission() {
            return permission;
        }

        public boolean isRepeatable() {
            return repeatable;
        }
    }
}
